package com.eratw.port.core.proc

import com.eratw.port.core.code.CallArg

/**
 * 嵌套调用 hook 的双桶实参：前 [intArgs] 项 = 整数桶（按调用序），后 [strArgs] 项 = 字符串桶
 * （按调用序），与 [com.eratw.port.core.mod.FunctionHookWiring.dispatchCall] 的实参形同构。
 */
internal class NestedHookArgs(val intArgs: LongArray, val strArgs: Array<String>)

/** 一次「可被钩子接管的嵌套调用」的解析结果（[ProcessRuntime.dispatchNestedHookCall] 内部用）。 */
internal class NestedHookTarget(val meta: FuncMeta, val key: String, val args: NestedHookArgs)

private val NO_LONGS: LongArray = LongArray(0)
private val NO_STRS: Array<String> = emptyArray()

/**
 * 把调用点实参投影成 hook 的双桶实参（VM 嵌套调用接管点专用）。
 *
 * 两种产物形态：
 * - **有逐位元数据**（v2 / 内存产物，[CallArg] 表）：按 [CallArg.bucket] 从调用方帧读值，
 *   并要求每个类型桶的槽号在调用序上连续 `0..n-1`、且无省略位（`present == false`）。
 *   省略位的原生语义是「调用点不写该形参、由被调装载期默认值顶替」
 *   （`CalledFunction.cs:133-200` / `ErbLoader.cs:714-722`），双桶 API 表达不了缺口，
 *   故整体放弃投影（返回 null ⇒ 调用方走原生、不派发 hook），不凭空补 0/`""`。
 * - **无元数据**（汇编器 / `.erbc` v1 产物）：`.erbc` v1 容器没有实参类型段
 *   （见 [com.eratw.port.core.code.CallArgSource.ERBC_V1]），退回引擎自身的 v1 类型桶口径
 *   ——实参全部按 int 桶位置从调用方 `R0..` 读，str 桶为空。
 *
 * @param argc 调用点实参数（VM 已按被调 `arity` / `slotCount` 夹取，与原生复制同一上界）。
 * @return null = 该调用点不能安全投影，调用方**不得**派发 hook（走原生调用）。
 */
internal fun decodeNestedHookArgs(
    callArgs: List<CallArg>?,
    argc: Int,
    readInt: (Int) -> Long,
    readStr: (Int) -> String,
): NestedHookArgs? =
    if (callArgs == null) {
        val n = argc.coerceAtLeast(0)
        NestedHookArgs(LongArray(n) { readInt(it) }, NO_STRS)
    } else {
        decodeByMeta(callArgs, argc, readInt, readStr)
    }

/** 有逐位元数据时的投影（校验失败整体放弃，见 [decodeNestedHookArgs] 的 KDoc）。 */
private fun decodeByMeta(
    callArgs: List<CallArg>,
    argc: Int,
    readInt: (Int) -> Long,
    readStr: (Int) -> String,
): NestedHookArgs? {
    var intCount = 0
    var strCount = 0
    var continuous = true
    for (a in callArgs) {
        val expected = if (a.isStr) strCount else intCount
        continuous = continuous && a.present && a.position < argc && a.bucket == expected
        if (a.isStr) strCount++ else intCount++
    }
    if (!continuous) return null
    val ints = if (intCount == 0) NO_LONGS else LongArray(intCount)
    val strs = if (strCount == 0) NO_STRS else Array(strCount) { "" }
    for (a in callArgs) {
        if (a.isStr) strs[a.bucket] = readStr(a.bucket) else ints[a.bucket] = readInt(a.bucket)
    }
    return NestedHookArgs(ints, strs)
}
