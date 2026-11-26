package com.eratw.port.core.stmt

/**
 * 形参默认值可引用的**编译期常量表**（按值类型分三张，名一律大写归一）。
 *
 * 三张表同源于 ERH 符号层（`#DIM CONST` / `#DIMS CONST` / `#DIMF CONST`），语义上是
 * 「同一张 ERH 常量表按类型分查」—— 拆成三个构造参数会把 [LineParser] 顶破 detekt
 * LongParameterList（构造参数上限 6，已占 4 个开关/配置位），故收进这一个不可变载体。
 *
 * 三支分别对应原版装载期 `Restructure` 的 `SingleLongTerm` / `SingleStrTerm` /
 * `SingleFloatTerm`（锚 `ErbLoader.cs:724-752`：`def = term.Restructure(exm) as SingleTerm`）：
 *
 * - [ints]：`#DIM CONST` 名与 `#DEFINE` 宏常量 —— 也同时喂 `#LOCALSIZE` / `#DIM` 尺寸折叠
 *   （[com.eratw.port.core.compile.DimSizeEvaluator]）；
 * - [strs]：`#DIMS CONST X = "..."` —— 形参默认值 `@F(ARGS:1 = MYSTR)`；
 * - [floats]：`#DIMF CONST X = 1.5` —— 形参默认值 `@F(#DIMF 形参 = MYFLT)`
 *   （PC 判词：4.981 桌面宿主 `--CliDebug`，ERH `#DIMF CONST MYFLT = 1.5` +
 *   `@F(ARG, PFLT = MYFLT)` + `CALL F, 0` ⇒ 15（×10 口径））。
 *
 * 后两张与 [ints] 分开的原因：ERH `#DIMS` / `#DIMF` 的原文 DimSpec 在符号表 `build`
 * 阶段即在册（解析前），而 erbcc 的 `foldStringConstants` 要等解析循环之后才算得出表；
 * 这里让调用方先把**已在册的 ERH 常量**折成表喂进来，避免时序倒置。
 *
 * 缺省 [EMPTY] = 三支都不折（保持旧行为：非字面量默认值由
 * [com.eratw.port.core.compile.CallDefaultsCollector.unsupported] 带进装载报告，
 * 绝不静默折成 0 / 空串）。
 */
class ParamConsts(
    /** `#DIM CONST` 名 / `#DEFINE` 宏 → 整数（名大写归一）。 */
    val ints: Map<String, Long> = emptyMap(),
    /** `#DIMS CONST` 名 → 字符串（名大写归一）。 */
    val strs: Map<String, String> = emptyMap(),
    /** `#DIMF CONST` 名 → 浮点（名大写归一）。 */
    val floats: Map<String, Double> = emptyMap(),
) {
    companion object {
        /** 三支都空：常量名一律不折（= 旧行为）。 */
        val EMPTY = ParamConsts()
    }
}
