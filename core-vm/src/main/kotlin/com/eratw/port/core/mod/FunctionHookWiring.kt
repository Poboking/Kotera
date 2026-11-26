package com.eratw.port.core.mod

import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue

/**
 * 函数 / 事件 Hook 的**生产接线**（§20.1 的执行侧）。
 *
 * [HookPipeline] 只管语义（槽序、Replace / SkipOriginal、失败隔离），本对象管「引擎哪一处调它」
 * 以及「引擎实参 ↔ `Array<ModCallArg>`」「`ModValue?` ↔ 返回值」的翻译。
 *
 * ## 为什么必须有这一层
 *
 * `HookPipeline` 从 K1 起就建好、`bind` 成功、单测全绿，但**全仓没有一处生产代码调用 `dispatch`**
 * ⇒ mod 作者声明的 hook 永远不触发（只有一条没人读的 OK 诊断）。本对象把两个宿主侧调用漏斗接上：
 *
 * 1. [dispatchCall]：`ProcessRuntime.doCall` —— 宿主按名调用脚本函数的唯一漏斗
 *    （`callFunction` / `tryCall` / `callForm` 三条入口共用）。按**函数键**查槽。
 * 2. [dispatchEvent]：`ProcessRuntime.executeEvent` —— 宿主派发事件（`EVENTTURNEND` /
 *    `EVENTCOM` …）。按**事件名**查槽。
 *
 * ## ERB 内部 CALL（嵌套调用，task-25 起也覆盖）
 *
 * ERB 里 `CALL` 指令产生的嵌套调用**不经过** [dispatchCall]（VM 指令内直接 `callInto`），
 * 但 v1 起 VM 在调用边界留了接管点 [com.eratw.port.core.code.VmHost.dispatchNestedHookCall]：
 * `ProcessRuntime` 在该点用嵌套 VM 跑「原函数」、外面同样包 [dispatchCall] ⇒
 * 函数键、槽序、Replace / SkipOriginal / 实参回流 / 返回值投影与「宿主按名调用」**同一套语义**。
 * 无接线 / 该键无槽时该接管点直接返回 null（零分配旁路，连实参数组都不构造）。
 * 2026-11-26 B1 起三类调用点全部接管：字符串返回位（`isStrRet`，CALLF 家族）、运行期按名
 * 调用点（`_CALL_BY_NAME` / `_CALL_STR`）与 `depth == 0` 的尾跳（哨兵携值交回 run）——
 * 三个旧旁路位拆除，与 int 常规调用共用同一条接管语义。
 *
 * ## 旁路（golden 零变化）
 *
 * 槽未安装（关态 / 无 mod）或该键无声明槽时，连实参数组都不构造，直接调原函数原样返回。
 * 处理器抛异常由 [HookPipeline] 隔离进 failures，这里只落日志面，不打断原函数。
 *
 * ## 实参序（写进契约，别再猜）
 *
 * Emuera 的调用约定是双桶：先整数实参、后字符串实参。[dispatchCall] 摊平成
 * 「前 `intArgs.size` 项 = 整数桶（按调用序），其后 = 字符串桶（按调用序）」，与
 * [ModCallArg.isString] 一一对应。整数桶被前缀 hook 改动（声明了 `mayMutateArgs`）后
 * **回流到原函数**：原函数拿到的是改后的实参。
 */
object FunctionHookWiring {
    /** `store.aux` 槽名（家族前缀 `mod.`，全小写）。 */
    const val AUX_SLOT = "mod.functionhooks"

    /** 空实参（事件没有实参）；大小为零的数组不可被追加，共享安全。 */
    private val NO_ARGS: Array<ModCallArg> = emptyArray()

    /**
     * 槽内容：本会话的全链 hook 引擎 + 日志面。
     *
     * 用**可变 holder** 而不是每次新建槽：`RuntimeAux` 没有单槽删除，读档重装 = 换 holder 里的
     * pipeline（与 `SidecarSaveWiring` 同范式）；[uninstall] 按引擎实例同一性判「是不是我这一份」。
     */
    internal class Handle(
        var pipeline: HookPipeline?,
        var logSink: (String) -> Unit,
    )

    /** 把本会话的全链引擎挂到 [store]；重复安装 = 覆盖（读档后重装同形）。 */
    fun install(store: VariableStore, pipeline: HookPipeline, logSink: (String) -> Unit) {
        val handle = store.aux.slot(AUX_SLOT) { Handle(pipeline, logSink) }
        handle.pipeline = pipeline
        handle.logSink = logSink
    }

    /** 解绑**本会话那一份**：传入别的会话的引擎时 no-op（与投射桥收口同口径）。 */
    fun uninstall(store: VariableStore, pipeline: HookPipeline) {
        val handle: Handle = store.aux.peek(AUX_SLOT) ?: return
        if (handle.pipeline === pipeline) handle.pipeline = null
    }

    /**
     * 带函数 hook 的调用（双返回通道统一入口，2026-11-26 B1/B2）。
     * 无槽 / 无绑定 = 完全旁路 [original]（不构造实参数组）。
     *
     * [original] 拿到的是**可能被前缀 hook 改过**的双桶实参（未声明 `mayMutateArgs` 的 hook 改不动），
     * 并以 [ModValue] 交回原函数返回值：整数通道包 [ModValue.IntValue]、字符串通道包
     * [ModValue.StrValue]。[strRet] = 调用位期望的返回通道（`_CALL.b` bit28）；返回值投影按它
     * 折算——错配的替换值记 WARN 后折成该通道零值（int → 0 / str → `""`），不静默也不抛。
     *
     * 三类调用点共用本函数（嵌套接管的三个旁路位已于 B1 全部拆除）：
     * ① 宿主按名调用（`ProcessRuntime.doCall`，恒 int 通道）；
     * ② VM 指令级嵌套 `CALL`（`VmHost.dispatchNestedHookCall`，int / str 随调用位）；
     * ③ 运行期按名调用点（`_CALL_BY_NAME` / `_CALL_STR`，实参元数据在宿主槽）与尾跳
     *    （`_CALL.b` bit30，depth == 0 经哨兵携值交回 run）。
     */
    // LongParameterList：B2 双通道签名（strRet + original 回调）是三类调用点共用的裁决面，
    // 参数全部承重（int/str 实参 + 返回通道 + 原函数通道），硬拆伤调用点可读性。
    @Suppress("LongParameterList")
    fun dispatchCall(
        store: VariableStore,
        key: String,
        intArgs: LongArray,
        strArgs: Array<String>,
        strRet: Boolean,
        original: (LongArray, Array<String>) -> ModValue,
    ): ModValue {
        val handle = store.aux.peek<Handle>(AUX_SLOT)
        val pipeline = handle?.pipeline
        return if (handle == null || pipeline == null || !pipeline.hasHooks(key)) {
            original(intArgs, strArgs)
        } else {
            val args = combine(intArgs, strArgs)
            val outcome = pipeline.dispatch(key, args) {
                original(intsOf(args, intArgs.size), strsOf(args, intArgs.size))
            }
            reportFailures(handle, outcome.failures)
            resultOf(handle, key, outcome.returnValue, strRet)
        }
    }

    /**
     * 带事件 hook 的事件派发。返回值语义同 [com.eratw.port.modapi.ModValue.IntValue]：
     * 非 0 = 事件「执行了」，0 = 未执行（`executeEvent` 的既有契约）。
     */
    fun dispatchEvent(store: VariableStore, key: String, original: () -> Boolean): Boolean {
        val handle = store.aux.peek<Handle>(AUX_SLOT)
        val pipeline = handle?.pipeline
        return if (handle == null || pipeline == null || !pipeline.hasHooks(key)) {
            original()
        } else {
            val outcome = pipeline.dispatch(key, NO_ARGS) { ModValue.IntValue(if (original()) 1L else 0L) }
            reportFailures(handle, outcome.failures)
            booleanResultOf(handle, key, outcome.returnValue)
        }
    }

    /**
     * 返回值投影（双通道统一，2026-11-26 B1/B2）：同通道直通；错配记日志后折成期望通道的
     * 零值（int 通道收到字符串替换值 → WARN + 0；str 通道收到整数替换值 → WARN + `""`），
     * 不静默也不抛——原函数返回值（[original] 通道交回的 null）折零值同口径。
     */
    private fun resultOf(handle: Handle, key: String, returned: ModValue?, strRet: Boolean): ModValue =
        when {
            returned == null -> if (strRet) ModValue.StrValue("") else ModValue.IntValue(0L)
            strRet -> when (returned) {
                is ModValue.StrValue -> returned
                is ModValue.IntValue -> {
                    handle.logSink(
                        "[hook][WARN] key=$key 的 Postfix/Replace 返回了整数值，调用位要字符串返回值" +
                            "（isStrRet），本值被折成空串交割",
                    )
                    ModValue.StrValue("")
                }
            }
            else -> when (returned) {
                is ModValue.IntValue -> returned
                is ModValue.StrValue -> {
                    // int 调用点收到字符串替换值：无法交割，记日志后按 0 返回（不静默）。
                    handle.logSink(
                        "[hook][WARN] key=$key 的 Postfix/Replace 返回了字符串值，「宿主按名调用」只接整数返回值，" +
                            "本值被丢弃（按 0 返回）",
                    )
                    ModValue.IntValue(0L)
                }
            }
        }

    /** 返回值投影：整数非 0 = true；字符串值记日志后按 false。 */
    private fun booleanResultOf(handle: Handle, key: String, returned: ModValue?): Boolean = when (returned) {
        is ModValue.IntValue -> returned.value != 0L
        is ModValue.StrValue -> {
            handle.logSink(
                "[hook][WARN] 事件 $key 的 Postfix/Replace 返回了字符串值，事件派发只认整数返回值，" +
                    "本值被丢弃（按 false 处理）",
            )
            false
        }
        else -> false
    }

    private fun combine(intArgs: LongArray, strArgs: Array<String>): Array<ModCallArg> {
        val out = Array(intArgs.size + strArgs.size) { index ->
            if (index < intArgs.size) {
                ModCallArg(isString = false, intValue = intArgs[index])
            } else {
                ModCallArg(isString = true, strValue = strArgs[index - intArgs.size])
            }
        }
        return out
    }

    private fun intsOf(args: Array<ModCallArg>, count: Int): LongArray =
        if (count == 0) EMPTY_LONGS else LongArray(count) { args[it].intValue }

    private fun strsOf(args: Array<ModCallArg>, offset: Int): Array<String> =
        if (args.size == offset) EMPTY_STRINGS else Array(args.size - offset) { args[offset + it].strValue }

    /** 失败隔离的日志面投影：每个被隔离的处理器一行（诊断环由 [HookPipeline] 自己写）。 */
    private fun reportFailures(handle: Handle, failures: List<HookPipeline.HookFailure>) {
        for (failure in failures) {
            val cause = failure.cause
            handle.logSink(
                "[${failure.packageId}][WARN] hook 处理器异常已隔离 key=${failure.targetKey} " +
                    "phase=${failure.phase.name} cause=${cause.javaClass.simpleName}: ${cause.message}",
            )
        }
    }

    private val EMPTY_LONGS: LongArray = LongArray(0)

    private val EMPTY_STRINGS: Array<String> = emptyArray()
}
