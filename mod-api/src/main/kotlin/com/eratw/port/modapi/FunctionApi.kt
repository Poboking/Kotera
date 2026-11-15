package com.eratw.port.modapi

/**
 * ERB 可调函数能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3，wrap `NativeMethodRegistry` /
 * `PluginMethods` 的 CALLSHARP 通道）。
 *
 * ERB 调 Kotlin 经注册过的函数：mod 经 [register] 把一个 [ModFunction] 绑到一个名字，
 * ERB 侧 `CALLSHARP "名字", 实参…` 即可调用（语句层把实参转成 [ModCallArg] 数组交给函数本体，
 * 再把**变量实参**位置上的值回写）。这是「双执行环境，一个 Runtime」的 ERB→Kotlin 半边
 * （§18.2）；Kotlin→ERB 半边经实体/事件/状态各 API。
 *
 * ## 注册相位与重名
 *
 * 注册在 mod [EraMod.onEnable] 期完成（K3 把注册接进生命周期）。重名拒绝（不静默覆盖，
 * 与引擎 `NativeMethodRegistry.register` 同口径）；名字按大写归一取键。
 *
 * clean 语义（§18.7）：出参回写机制是**契约**（原版 IPluginMethod.Execute 无返回值、结果全靠
 * 改实参槽），本 facade 保留这一干净机制但不复刻官方装载期怪癖；ADR-005 仍约束 ERB 语句面。
 */
interface FunctionApi {
    /**
     * 注册一个可被 ERB CALLSHARP 调用的函数。[name] 重名抛 [IllegalArgumentException]。
     */
    fun register(name: String, function: ModFunction)

    /** 是否已注册（名字按大写归一）。 */
    fun has(name: String): Boolean
}

/**
 * 一个可被 ERB 调用的 Kotlin 函数（clean 语义的 `IPluginMethod` 等价物）。
 *
 * 无返回值——结果通过改 [ModCallArg] 的 [ModCallArg.intValue]/[ModCallArg.strValue] 带回
 * （出参回写，与引擎 CALLSHARP 语句层口径一致）。用哪一侧值由接收方按 [ModCallArg.isString] 判。
 */
fun interface ModFunction {
    fun call(args: Array<ModCallArg>)
}

/**
 * 单个实参槽（mod 侧干净形态，wrap 引擎 `NativeParam`）。
 *
 * [intValue]/[ModCallArg.strValue] 是 `var`：函数本体改它们把结果带回语句层（出参回写）。
 * [isString] 是转换期的来源类型，决定接收方读哪一侧。适配器负责把 mod 改后的值回写进引擎
 * 的 `NativeParam` 数组（mod 不持有引擎对象，§18.2 铁边界）。
 */
class ModCallArg(
    val isString: Boolean = false,
    var intValue: Long = 0L,
    var strValue: String = "",
)