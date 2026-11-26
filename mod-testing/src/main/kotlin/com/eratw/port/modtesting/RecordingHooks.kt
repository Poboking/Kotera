package com.eratw.port.modtesting

import com.eratw.port.modapi.HookApi
import com.eratw.port.modapi.HookContext
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookRegistration
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration

/**
 * 录制型 [HookApi]：**测试替身，不是生产实现**。
 *
 * 作者声明的 hook 槽来自各 mod 的 Manifest（见 [MemorySchema] 的 hook 槽表）；[register] 只允许
 * 绑定到「本包 + 已声明槽」，[dispatch] 手动跑一次劫持链，不必启动 `:core-vm` 引擎。
 *
 * 派发语义照生产 `HookPipeline`（core/mod/HookPipeline.kt）：
 *
 * - 同一 target+phase 下，所有已绑定包按 **priority 升序 → 声明序（Manifest 里的出现序）** 跑；
 * - [HookResult.Replace] 立即返回，**跳过原函数与其余所有 hook**（含 POSTFIX），替换值即返回值；
 * - [HookResult.SkipOriginal] 只在 **PREFIX** 阶段生效（跳过原函数体）；**POSTFIX 阶段按 Continue
 *   处理**（生产 `HookPipeline` 同口径：postfix 没有「原函数」可跳）；
 * - 槽未授权 `mayMutateArgs` 时，每个处理器拿到**本次调用独享的 [ModCallArg] 克隆**，
 *   改它不会回写到后续处理器（与生产逐槽隔离同口径）；
 * - **返回值通道**：POSTFIX 阶段每个处理器经 `HookContext.returnValue` 看到当前返回值，授权
 *   `mayMutateReturn` 的槽改它即写回（[dispatchChain] 的 `HookChainResult.returnValue` 带回结局）；
 *   PREFIX 阶段该上下文值恒为 null（生产同口径）。
 *
 * ## 与生产的差异
 *
 * 台架没有真实 ERB 函数体，但 [dispatchWithOriginal] 可让用例传入「原函数」回调，从而跑完整
 * `PREFIX → 原函数 → POSTFIX` 串联链（与生产 `HookPipeline.dispatch` 同序、同短路、同返回值通道）。
 * 失败路径也已对齐：原函数抛异常时按 `runOnFailure` 补跑 POSTFIX 槽（返回值丢弃）后原异常照穿；
 * 处理器异常按 `continueOnHandlerFailure` 决定「保持绑定」还是「首次抛异常后撤销本槽」。相位级
 * [dispatchChain] 的 `originalThrew=true` 就是生产 `HookPipeline.runFailurePostfix` 的同构入口
 * （补跑段语义见该方法 KDoc）；[dispatch] 仍是「不涉原函数」的纯相位派发。
 *
 * ## 失败日志面的文案边界（对拍钉死）
 *
 * 处理器异常在两侧都会落一行**隔离 WARN**，它必须**逐字相同**（台架只少生产前缀
 * `[packageId][WARN] `，格式见 `FunctionHookWiring.reportFailures`）：
 * `hook 处理器异常已隔离 key=… phase=… cause=<异常类名>: <message>`。这条说的是「该处理器被隔离、
 * 派发继续」，与撤销无关，缺省 `continueOnHandlerFailure=true` 的槽也只落这一条。
 *
 * **撤销 WARN 是台架独有的**（`hook 槽已撤销 key=… phase=…（处理器首次抛异常，…）`）：生产把撤销
 * 写进 `RuntimeDiagnostics` 诊断 `HANDLER_THREW_SLOT_RETIRED`，**日志面没有撤销行**。故两条 WARN
 * 各自存在、不互相冒充——用例应断言「隔离行逐字相等」「撤销行只台架有且不含『已隔离』」「引擎日志
 * 不含『槽已撤销』」。台架没有诊断环，撤销的唯一可观测面就是这条 WARN 与 [retiredSlotCount]。
 *
 * ## hook 目标键拼错闸（与生产同口径，MODDX4-B3）
 *
 * 生产 `ModContextImpl.hooks.register` 的拼错闸（真源 = 链接期 `SymbolCatalog.bindings`）在台架的
 * 同位入口是 [adoptKnownFunctionKeys]：缺省不拦，注入后 [register] 绑定到目录外目标键当场抛
 * [IllegalArgumentException] 并附最近候选（拒绝文案与生产 `unknownHookTargetMessage` 逐字一致，
 * 对拍用例断言两串相等）。[knownFunctionKeys] 与生产同口径：归一 + 字典序稳定排序的独立快照，
 * 未注入 = 空集。
 */
class RecordingHooks internal constructor(
    private val packageId: String,
    private val table: HookRecordTable,
) : HookApi {

    /** 一次 hook 处理器调用的只读记录（断言「谁跑了、跑出什么结局」用）。 */
    data class HookCall(
        val packageId: String,
        val targetKey: String,
        val phase: HookPhase,
        val result: HookResult,
    )

    /**
     * 一次相位级劫持链的完整结局（[dispatchChain] 的返回）。
     *
     * [result] 是该相位的结局（生产 `HookPipeline` 的 HookOutcome 对应面）；[returnValue] 是跑完后
     * 的返回值（POSTFIX 阶段由「原函数返回值」起步、经授权槽改写；PREFIX 阶段恒为 null）；
     * [replaced] 表示链上有槽返回了 [HookResult.Replace]（此时 [returnValue] 即替换值）。
     */
    data class HookChainResult(
        val result: HookResult,
        val returnValue: ModValue?,
        val replaced: Boolean,
    )

    /**
     * 一次 `PREFIX → 原函数 → POSTFIX` 串联链的完整终局（[dispatchWithOriginal] 的返回；
     * 形状照生产 `HookPipeline.HookOutcome`）。
     *
     * @property returnValue 终局返回值（POSTFIX 授权槽可改；PREFIX `Replace` 时为替换值；
     *   原函数被跳过且无 POSTFIX 改值时保持原函数返回值语义，即 null / 原返回值）
     * @property originalRan 原函数体是否真的跑过（PREFIX `SkipOriginal` 或 `Replace` 时为 false）
     * @property replaced 链上是否出现 [HookResult.Replace]（出现即短路，原函数与其余相位都不跑）
     * @property failures 被隔离的处理器归属包（按发生序；台架无 `RuntimeDiagnostics`，只回传包名）
     */
    data class HookChainOutcome(
        val returnValue: ModValue?,
        val originalRan: Boolean,
        val replaced: Boolean,
        val failures: List<String>,
    )

    /** 最近一次 [dispatch] 的调用序（按调用先后；跨 mod 共享表，但只记「实际跑过的」处理器）。 */
    val calls: List<HookCall> get() = table.calls

    override fun register(targetKey: String, phase: HookPhase, handler: HookHandler): Registration {
        // 拼错闸（MODDX4-B3）：目录非空时，目录外目标键当场拒绝并给最近候选
        //（口径照生产 ModContextImpl.hooks.register 的闸逐字同构；空集 = 缺省不校验）。
        val known = table.adoptedKnownFunctionKeys
        val key = normalize(targetKey)
        require(known.isEmpty() || key in known) {
            unknownHookTargetMessage(packageId, targetKey, key, known)
        }
        return table.register(key, phase, packageId, handler)
    }

    /**
     * 采纳「可挂函数键集合」作为 [register] 的拼错闸（生产 `ModContextImpl.hooks` 拼错闸的同位
     * 入口；装配侧在 `install` 之前灌入）。入参逐个 [normalize]（trim + 大写），与
     * `FuncMeta.key` 同口径，所以作者写全小写或带空白也算命中。传空集 = 关闭校验（缺省即关闭）。
     */
    fun adoptKnownFunctionKeys(keys: Set<String>) {
        table.adoptedKnownFunctionKeys = keys
    }

    /**
     * 只读快照：与生产 `ModContextImpl.hooks.knownFunctionKeys` **同口径**（字典序稳定排序的独立
     * 集合；未注入已知集 = 空集）。改返回值不影响台架表或后续调用。
     */
    override fun knownFunctionKeys(): Set<String> = table.knownFunctionKeysSnapshot

    /**
     * 只读投影：本会话全部已声明 hook 槽（跨 mod 共享表），序口径与生产 `HookPipeline.registrations`
     * 同源（functionKey 字典序 → priority → 声明序）。槽表来自各 mod 的 Manifest 声明
     * （[MemorySchema] 的 hook 槽表），声明了但未绑定处理器的槽同样在投影里（与生产同口径）。
     */
    override fun registrations(): List<HookRegistration> = table.registrationsSnapshot

    /**
     * 手动派发一次劫持链。
     *
     * @param args 引擎传给原函数的实参；未授权 `mayMutateArgs` 的槽会收到克隆，改不动 [args] 本身。
     * @return 链的结局：[HookResult.Replace]（立即短路）/ [HookResult.SkipOriginal]（跳过原函数，
     *   仅 PREFIX）/ [HookResult.Continue]（跑原函数）。
     */
    fun dispatch(
        targetKey: String,
        phase: HookPhase = HookPhase.PREFIX,
        args: Array<ModCallArg> = emptyArray(),
    ): HookResult = dispatchChain(targetKey, phase, args).result

    /**
     * 手动派发一次相位级劫持链并带回**返回值通道**的结局。
     *
     * @param returnValue POSTFIX 阶段的起始返回值（生产里就是原函数的返回值）；PREFIX 阶段忽略。
     * @param originalThrew 声明「原函数已经抛异常」：仅对 POSTFIX 生效，走生产
     *   `HookPipeline.runFailurePostfix` 的补跑段——只跑声明 `runOnFailure` 的槽、上下文
     *   `returnValue` 恒为 null、处理器结果与返回值一律丢弃（Replace 也不生效），结局返回值恒为 null；
     *   原异常不在这里重抛（调用方按生产语义自行穿出，见 [dispatchWithOriginal]）。
     * @return 相位结局 + 最终返回值 + 是否发生替换。
     */
    fun dispatchChain(
        targetKey: String,
        phase: HookPhase = HookPhase.PREFIX,
        args: Array<ModCallArg> = emptyArray(),
        returnValue: ModValue? = null,
        originalThrew: Boolean = false,
    ): HookChainResult = if (phase == HookPhase.POSTFIX && originalThrew) {
        table.dispatchFailurePostfix(normalize(targetKey), args)
    } else {
        table.dispatchChain(normalize(targetKey), phase, args, returnValue)
    }

    /**
     * 跑一次完整的 `PREFIX → 原函数 → POSTFIX` 串联链（生产 `HookPipeline.dispatch` 的同构入口）。
     *
     * 语义逐条对齐生产：PREFIX 按 priority → 声明序；`Replace` 立即短路（原函数与 POSTFIX 都不跑）；
     * `SkipOriginal` 只在 PREFIX 生效（只跳过 [original]，POSTFIX 照跑）；被隔离的处理器进
     * [HookChainOutcome.failures] 且不影响其余处理器；[original] 抛出的异常照原样穿出（生产同样
     * 不吞原函数异常）。
     *
     * @param args 引擎传给原函数的实参；未授权 `mayMutateArgs` 的槽收到克隆，改不动 [args] 本身。
     * @param original 「原函数体」：返回生产里原函数的返回值。
     */
    fun dispatchWithOriginal(
        targetKey: String,
        args: Array<ModCallArg> = emptyArray(),
        original: () -> ModValue?,
    ): HookChainOutcome = table.dispatchWithOriginal(normalize(targetKey), args, original)

    /** 清空 [calls]（每条用例开头清一次，免得跨用例串味）。 */
    fun clearCalls() {
        table.clearCalls()
    }

    /** 某槽是否已被撤销（`continueOnHandlerFailure=false` 的槽首次处理器异常后为 true）。 */
    fun isSlotRetired(
        targetKey: String,
        phase: HookPhase = HookPhase.PREFIX,
        packageId: String = this.packageId,
    ): Boolean = table.isSlotRetired(normalize(targetKey), phase, packageId)

    /** 已撤销的槽数（会话级只读面）。 */
    val retiredSlotCount: Int get() = table.retiredSlotCount
}

/** 一个会话一份的 hook 绑定表（语义照生产 `HookPipeline`，只承载已声明槽上的运行期绑定）。 */
@Suppress("TooManyFunctions") // 11 个面全是同一次会话的绑定表读写（绑定/撤销/查询/清理），拆开就丢原子性
internal class HookRecordTable(
    private val slots: List<MemorySchema.HookSlotSpec>,
    private val warnSink: (packageId: String, message: String) -> Unit = { _, _ -> },
) {

    private data class BindingKey(val targetKey: String, val phase: HookPhase, val packageId: String)

    private val handlers = HashMap<BindingKey, HookHandler>()

    /**
     * 已知可挂函数键集合（归一后的只读快照）：非空时才校验 [register] 的目标键。
     *
     * 缺省空集 = 不校验（台架没有链接期函数目录）；与生产 `ModContextImpl.knownFunctionKeys`
     * 同位同口径。写入侧逐个 [normalize]（生产同样收在 context 属性写入侧，见
     * `ModContextImpl.knownFunctionKeys` 的 setter），只整体替换、不清单条。
     */
    internal var adoptedKnownFunctionKeys: Set<String> = emptySet()
        set(value) {
            field = value.mapTo(LinkedHashSet(value.size)) { normalize(it) }
        }

    /** 已撤销的槽（`continueOnHandlerFailure=false` 的槽首次处理器异常后进入；重新注册移出）。 */
    private val retired = LinkedHashSet<BindingKey>()

    /**
     * 统一的槽清理点：把同一 key 从 [handlers]（绑定）与 [retired]（撤销读数）**一并**抹掉。
     *
     * 生产 `HookPipeline` 对同一 key 的两处状态始终互斥：绑定 = 仅 handlers、撤销 = 仅
     * retiredSlots、注销 = 两者皆空。本表所有状态迁移（register / unregister / 撤销收尾）
     * 都先经这里清掉旧状态再写入目标状态，从根源杜绝「撤销后 handler 残留堵住同槽重注册」
     * 与「注销后 retired 残留让 isSlotRetired / retiredSlotCount 对已注销槽说谎」。
     */
    private fun clearSlot(key: BindingKey) {
        handlers.remove(key)
        retired.remove(key)
    }

    /** 已撤销槽数（只读）。 */
    val retiredSlotCount: Int get() = retired.size

    /**
     * 已声明 hook 槽的只读投影快照（functionKey 字典序 → priority → 声明序；与生产
     * `HookPipeline.registrations` 同口径）：改返回值不影响本表。槽表构造期冻结
     * （[slots] 来自 Manifest 声明），绑定状态不改变投影。
     *
     * 用属性而非成员函数：本表成员函数数已抵 detekt TooManyFunctions 阈值（与
     * `EventRecordTable.knownEventKeysSnapshot` 的「不为读数新增函数」同一口径）。
     */
    internal val registrationsSnapshot: List<HookRegistration>
        get() = slots
            .sortedWith(compareBy({ it.targetKey }, { it.priority }, { it.declarationOrder }))
            .map { HookRegistration(it.targetKey, it.phase, it.priority, it.packageId) }

    /**
     * 已知函数键快照（归一后，字典序稳定排序）：只读查询面用，改返回值不影响本表。
     *
     * 返回独立副本（与生产 `ModContextImpl.hooks.knownFunctionKeys` 同口径）；用属性而非成员
     * 函数：本表成员函数数已抵 detekt TooManyFunctions 阈值（与 [registrationsSnapshot] 的
     * 「不为读数新增函数」同一口径）。
     */
    internal val knownFunctionKeysSnapshot: Set<String>
        get() = adoptedKnownFunctionKeys.toSortedSet()

    /** 某槽是否已被撤销（只读；只影响本槽绑定，不跨包/跨相位）。 */
    fun isSlotRetired(targetKey: String, phase: HookPhase, packageId: String): Boolean =
        BindingKey(targetKey, phase, packageId) in retired

    private val recordedCalls = mutableListOf<RecordingHooks.HookCall>()

    val calls: List<RecordingHooks.HookCall> get() = recordedCalls

    fun register(targetKey: String, phase: HookPhase, packageId: String, handler: HookHandler): Registration {
        val slot = slots.firstOrNull { it.targetKey == targetKey && it.phase == phase && it.packageId == packageId }
            ?: throw IllegalArgumentException(
                "hook 槽未声明：$packageId/$targetKey/$phase（先经 Manifest 声明）",
            )
        val bindingKey = BindingKey(slot.targetKey, phase, packageId)
        require(bindingKey !in handlers) {
            "hook 处理器重复绑定：$packageId/$targetKey/$phase（一个槽只允许一个 handler）"
        }
        // 重新注册 = 该槽重新可用（撤销只影响上一次绑定，不跨注册残留；同生产 `HookPipeline.bind`）：
        // 先经统一清理点抹掉上一绑定残留的撤销读数，再写入新绑定。
        clearSlot(bindingKey)
        handlers[bindingKey] = handler
        return object : Registration {
            override fun unregister() {
                // 解绑即两处都清（同生产 `HookPipeline.bind` 的 unregister）。
                clearSlot(bindingKey)
            }
        }
    }

    /**
     * 相位级劫持链，语义逐条照生产 `HookPipeline`：
     *
     * - 排序 `priority → declarationOrder`；[HookResult.Replace] 立即短路并带回替换值；
     * - POSTFIX 阶段 `HookContext.returnValue` 从 [initialReturnValue] 起步，只有 `mayMutateReturn`
     *   的槽在返回 Continue 后写回；SkipOriginal 在 POSTFIX 被忽略（生产同口径）；
     * - PREFIX 阶段上下文 returnValue 恒为 null，SkipOriginal 记下并最终回传 [HookResult.SkipOriginal]。
     *
     * 原函数已经抛异常时的补跑段走 [dispatchFailurePostfix]（生产 `HookPipeline.runFailurePostfix` 同构）。
     */
    fun dispatchChain(
        targetKey: String,
        phase: HookPhase,
        args: Array<ModCallArg>,
        initialReturnValue: ModValue?,
    ): RecordingHooks.HookChainResult {
        val ordered = slots
            .filter { it.targetKey == targetKey && it.phase == phase }
            .filterNot { BindingKey(it.targetKey, it.phase, it.packageId) in retired }
            .sortedWith(compareBy({ it.priority }, { it.declarationOrder }))
        var skipOriginal = false
        var current = if (phase == HookPhase.POSTFIX) initialReturnValue else null
        for (slot in ordered) {
            val handler = handlers[BindingKey(slot.targetKey, slot.phase, slot.packageId)] ?: continue
            val context = HookContext(
                packageId = slot.packageId,
                targetKey = slot.targetKey,
                phase = slot.phase,
                args = argsFor(slot, args),
                returnValue = current,
            )
            val result = handler.onHook(context)
            recordedCalls += RecordingHooks.HookCall(slot.packageId, slot.targetKey, slot.phase, result)
            when (result) {
                HookResult.Continue -> if (phase == HookPhase.POSTFIX && slot.mayMutateReturn) {
                    current = context.returnValue
                }
                HookResult.SkipOriginal -> if (phase == HookPhase.PREFIX) skipOriginal = true
                is HookResult.Replace ->
                    return RecordingHooks.HookChainResult(result, result.value, replaced = true)
            }
        }
        val outcome = if (skipOriginal) HookResult.SkipOriginal else HookResult.Continue
        return RecordingHooks.HookChainResult(outcome, current, replaced = false)
    }

    /**
     * 串联链：语义逐条照生产 `HookPipeline.dispatch`（`core/mod/HookPipeline.kt:131-236`）：
     *
     * - 无槽 / 无绑定 ⇒ 完全旁路，只调 [original]；
     * - PREFIX 段：`Replace` 立即返回（`originalRan = false`、`replaced = true`）；
     *   `SkipOriginal` 记下（仍跑余下 PREFIX 槽与全部 POSTFIX 槽）；
     * - 原函数：未被跳过时调用 [original]，抛出的异常照原样穿出（生产同样不吞）；
     * - POSTFIX 段：上下文 `returnValue` 从原函数返回值起步，只有 `mayMutateReturn` 槽在 Continue 后写回，
     *   `Replace` 改值并 break，`SkipOriginal` 按 Continue 处理；
     * - 每段内单个处理器抛异常 ⇒ 记进 failures、继续，不影响其余处理器与原函数。
     */
    fun dispatchWithOriginal(
        targetKey: String,
        args: Array<ModCallArg>,
        original: () -> ModValue?,
    ): RecordingHooks.HookChainOutcome {
        // 已绑定槽按 priority → 声明序（生产 HookPipeline.of 的排序同口径）；已撤销槽不进链。
        val bound = slots
            .filter { it.targetKey == targetKey }
            .sortedWith(compareBy({ it.priority }, { it.declarationOrder }))
            .mapNotNull { slot ->
                val key = BindingKey(slot.targetKey, slot.phase, slot.packageId)
                if (key in retired) return@mapNotNull null
                handlers[key]?.let { handler -> slot to handler }
            }
        if (bound.isEmpty()) {
            return RecordingHooks.HookChainOutcome(
                returnValue = original(),
                originalRan = true,
                replaced = false,
                failures = emptyList(),
            )
        }
        val failures = mutableListOf<String>()
        val prefix = runPrefix(bound, args, failures)
        val replacement = prefix.replacement
        val outcome = if (replacement != null) {
            // PREFIX 短路：原函数与 POSTFIX 都不跑（生产 HookPipeline.kt:167-172）。
            RecordingHooks.HookChainOutcome(
                returnValue = replacement.value,
                originalRan = false,
                replaced = true,
                failures = failures,
            )
        } else {
            // 生产在原函数抛异常时先补跑 runOnFailure 的 POSTFIX 槽（返回值丢弃），随后原异常照穿
            // （HookPipeline.kt:194-197 / runFailurePostfix）；台架同口径。
            val originalReturn = if (prefix.skippedOriginal) {
                null
            } else {
                // 用 runCatching 而非 catch(Throwable)（detekt 禁泛型 catch）；语义同：补跑失败段后原异常照穿。
                val attempted = runCatching { original() }
                attempted.exceptionOrNull()?.let { thrown ->
                    runFailurePostfix(bound, args, failures)
                    throw thrown
                }
                attempted.getOrNull()
            }
            val postfix = runPostfix(bound, args, originalReturn, failures)
            RecordingHooks.HookChainOutcome(
                returnValue = postfix.returnValue,
                originalRan = !prefix.skippedOriginal,
                replaced = postfix.replaced,
                failures = failures,
            )
        }
        return outcome
    }

    /** PREFIX 段结果：是否要求跳过原函数 + 短路替换值（非 null 即整条链终止）。 */
    private data class PrefixRun(val skippedOriginal: Boolean, val replacement: HookResult.Replace?)

    /** POSTFIX 段结果：终局返回值 + 是否被 Replace 改写。 */
    private data class PostfixRun(val returnValue: ModValue?, val replaced: Boolean)

    /**
     * 原函数抛异常时的补跑段（生产 `HookPipeline.runFailurePostfix` 同口径）：只跑 POSTFIX 且
     * 声明了 `runOnFailure` 的槽，上下文 `returnValue` 为 null，结果与返回值一律丢弃；处理器自己的
     * 异常照旧隔离进 [RecordingHooks.HookChainOutcome.failures]。
     */
    private fun runFailurePostfix(
        bound: List<Pair<MemorySchema.HookSlotSpec, HookHandler>>,
        args: Array<ModCallArg>,
        failures: MutableList<String>,
    ) {
        val failureSlots = bound.filter {
            it.first.phase == HookPhase.POSTFIX && it.first.runOnFailure &&
                BindingKey(it.first.targetKey, it.first.phase, it.first.packageId) !in retired
        }
        for ((slot, handler) in failureSlots) {
            val context = hookContextOf(slot, argsFor(slot, args), returnValue = null)
            val result = invokeIsolated(slot, handler, context, failures, ::noteHandlerFailure)
            if (result != null) {
                recordedCalls += RecordingHooks.HookCall(slot.packageId, slot.targetKey, slot.phase, result)
            }
        }
    }

    /**
     * 生产 `HookPipeline.runFailurePostfix` 的同构补跑段（[RecordingHooks.dispatchChain] 的
     * `originalThrew=true` 走这里）：原函数抛异常后**只跑** POSTFIX 里声明 `runOnFailure` 且未撤销的槽；
     * 上下文 `returnValue` 恒为 null，处理器结果与返回值一律丢弃（Replace 也不生效），
     * 结局固定 Continue + null + replaced=false。处理器自己的异常照旧隔离（失败进列表、撤销面照常收尾）。
     */
    fun dispatchFailurePostfix(
        targetKey: String,
        args: Array<ModCallArg>,
    ): RecordingHooks.HookChainResult {
        val failures = mutableListOf<String>()
        val failureSlots = slots
            .filter { it.targetKey == targetKey && it.phase == HookPhase.POSTFIX && it.runOnFailure }
            .filterNot { BindingKey(it.targetKey, it.phase, it.packageId) in retired }
            .sortedWith(compareBy({ it.priority }, { it.declarationOrder }))
        for (slot in failureSlots) {
            val handler = handlers[BindingKey(slot.targetKey, slot.phase, slot.packageId)] ?: continue
            val context = hookContextOf(slot, argsFor(slot, args), returnValue = null)
            val result = invokeIsolated(slot, handler, context, failures, ::noteHandlerFailure)
            if (result != null) {
                recordedCalls += RecordingHooks.HookCall(slot.packageId, slot.targetKey, slot.phase, result)
            }
        }
        return RecordingHooks.HookChainResult(HookResult.Continue, null, replaced = false)
    }

    /**
     * 处理器异常的两条面（对拍钉死的文案边界）：
     *
     * 1. **隔离行**必须与生产 `FunctionHookWiring.reportFailures` 逐字一致（台架只少前缀
     *    `[packageId][WARN] `）：`hook 处理器异常已隔离 key=… phase=… cause=<异常类名>: <message>`。
     *    它表达「该处理器被隔离、派发继续」，与撤销无关；
     * 2. **撤销行**是台架独有：`hook 槽已撤销 key=… phase=…（处理器首次抛异常，…）`，生产把撤销写进
     *    `RuntimeDiagnostics`（`HANDLER_THREW_SLOT_RETIRED`）而**日志面没有撤销行**，故两条 WARN
     *    各自存在、不互相冒充（隔离行不含「槽已撤销」，撤销行不含「已隔离」）。
     */
    private fun noteHandlerFailure(slot: MemorySchema.HookSlotSpec, cause: Throwable) {
        warnSink(
            slot.packageId,
            "hook 处理器异常已隔离 key=${slot.targetKey} phase=${slot.phase.name} " +
                "cause=${cause.javaClass.simpleName}: ${cause.message}",
        )
        if (slot.continueOnHandlerFailure) return
        val key = BindingKey(slot.targetKey, slot.phase, slot.packageId)
        if (key in retired) return // 首次撤销才收尾（同生产 retiredSlots.add 的去重）
        // 撤销即解绑：本次绑定作废，同槽重新 register 因而能成功（同生产 handleHandlerFailure）。
        clearSlot(key)
        retired.add(key)
        warnSink(
            slot.packageId,
            "hook 槽已撤销 key=${slot.targetKey} phase=${slot.phase.name}（处理器首次抛异常，" +
                "continueOnHandlerFailure=false，后续派发不再进入）",
        )
    }

    private fun runPrefix(
        bound: List<Pair<MemorySchema.HookSlotSpec, HookHandler>>,
        args: Array<ModCallArg>,
        failures: MutableList<String>,
    ): PrefixRun {
        var skipped = false
        for ((slot, handler) in bound.filter { it.first.phase == HookPhase.PREFIX }) {
            val context = hookContextOf(slot, argsFor(slot, args), returnValue = null)
            val result = invokeIsolated(slot, handler, context, failures, ::noteHandlerFailure) ?: continue
            recordedCalls += RecordingHooks.HookCall(slot.packageId, slot.targetKey, slot.phase, result)
            when (result) {
                HookResult.Continue -> Unit
                is HookResult.Replace -> return PrefixRun(skipped, result)
                HookResult.SkipOriginal -> skipped = true
            }
        }
        return PrefixRun(skipped, null)
    }

    private fun runPostfix(
        bound: List<Pair<MemorySchema.HookSlotSpec, HookHandler>>,
        args: Array<ModCallArg>,
        initialReturnValue: ModValue?,
        failures: MutableList<String>,
    ): PostfixRun {
        var current = initialReturnValue
        for ((slot, handler) in bound.filter { it.first.phase == HookPhase.POSTFIX }) {
            val context = hookContextOf(slot, argsFor(slot, args), returnValue = current)
            val result = invokeIsolated(slot, handler, context, failures, ::noteHandlerFailure) ?: continue
            recordedCalls += RecordingHooks.HookCall(slot.packageId, slot.targetKey, slot.phase, result)
            when (result) {
                HookResult.Continue -> if (slot.mayMutateReturn) current = context.returnValue
                is HookResult.Replace -> return PostfixRun(result.value, replaced = true)
                HookResult.SkipOriginal -> Unit
            }
        }
        return PostfixRun(current, replaced = false)
    }

    fun clearCalls() {
        recordedCalls.clear()
    }
}

private fun hookContextOf(
    slot: MemorySchema.HookSlotSpec,
    args: Array<ModCallArg>,
    returnValue: ModValue?,
): HookContext = HookContext(
    packageId = slot.packageId,
    targetKey = slot.targetKey,
    phase = slot.phase,
    args = args,
    returnValue = returnValue,
)

/** 未授权 `mayMutateArgs` 时逐槽克隆实参，模拟生产 `HookPipeline.isolatedArgs`。 */
private fun argsFor(slot: MemorySchema.HookSlotSpec, args: Array<ModCallArg>): Array<ModCallArg> {
    if (slot.mayMutateArgs) return args
    return Array(args.size) { index ->
        val arg = args[index]
        ModCallArg(isString = arg.isString, intValue = arg.intValue, strValue = arg.strValue)
    }
}

/**
 * 调一次处理器并隔离异常（失败记包名、返回 null = 该处理器跳过）；生产同口径见
 * `HookPipeline.kt:175-184`。[onHandlerFailure] 是失败面回调（隔离 WARN + 必要时撤销本槽，
 * 见 `HookRecordTable.noteHandlerFailure`）。用 runCatching 而非 `catch (Throwable)` 是 detekt 口径
 * （禁泛型 catch），语义与之相同：任意 Throwable 都隔离。
 */
private fun invokeIsolated(
    slot: MemorySchema.HookSlotSpec,
    handler: HookHandler,
    context: HookContext,
    failures: MutableList<String>,
    onHandlerFailure: (MemorySchema.HookSlotSpec, Throwable) -> Unit,
): HookResult? {
    val outcome = runCatching { handler.onHook(context) }
    val thrown = outcome.exceptionOrNull() ?: return outcome.getOrNull()
    failures += slot.packageId
    onHandlerFailure(slot, thrown)
    return null
}

// ---- hook 目标键拼错闸的消息与候选口径（MODDX4-B3）----
// 文案逐字抄自生产 ModContextImpl.kt 的 unknownHookTargetMessage（同 events 闸的镜像纪律）：
// 对拍用例直接断言两串相等，改任何一侧都要同步另一侧。

/** 未知名拒绝消息里最多给几个候选（与生产 `NEAREST_FUNCTION_KEY_LIMIT` 同值）。 */
private const val NEAREST_FUNCTION_KEY_LIMIT = 3

/**
 * 未知 hook 目标键的拒绝消息：含**原始**键（作者按它 grep 自己的注册点）、归属包与最接近候选。
 * 与生产 `ModContextImpl.kt` 的 `unknownHookTargetMessage` 逐字一致（原始名而非归一后的 key：
 * 作者手里写的就是原始字面量，消息里的名字要能直接对回源码）。
 */
private fun unknownHookTargetMessage(
    packageId: String,
    targetKey: String,
    key: String,
    known: Set<String>,
): String {
    val nearest = nearestFunctionKeyNames(key, known)
    val hint = if (nearest.isEmpty()) "" else "；最接近的候选：${nearest.joinToString(", ")}"
    return "未知函数键 \"$targetKey\"（来自 $packageId）：链接期 SymbolCatalog 函数目录里没有这个函数，" +
        "拼错的 hook 永远不会被派发，故绑定当场拒绝（不静默失效）$hint"
}

/** 至多 [NEAREST_FUNCTION_KEY_LIMIT] 个最接近的已知键，按（编辑距离，名字）排序，结果确定。 */
private fun nearestFunctionKeyNames(key: String, known: Set<String>): List<String> =
    known.asSequence()
        .map { it to editDistance(key, it) }
        .sortedWith(compareBy({ it.second }, { it.first }))
        .take(NEAREST_FUNCTION_KEY_LIMIT)
        .map { it.first }
        .toList()

/** 编辑距离（Levenshtein，逐行滚动，只留两行）：函数键短，O(n·m) 的朴素实现足够。 */
private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
