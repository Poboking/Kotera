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
 * ## 注册相位与重名（2026-11-26 B3 起：新增 / 替换双闸）
 *
 * 注册在 mod [EraMod.onEnable] 期完成（K3 把注册接进生命周期）。两个写口、两种意图，各走各的闸：
 * - [register] = **新增**：名字已存在（宿主内建或先注册者）抛 [IllegalArgumentException]，
 *   复刻原版 `PluginManager` 的 `Dictionary.Add` 崩溃口径，不静默覆盖；
 * - [override] = **显式替换**：只认**已存在**的名字（宿主经 CALLSHARP 通道注册的本地方法，
 *   或先于本 mod 注册的名字），替换其调用本体；名字不存在抛 [IllegalArgumentException]。
 *   覆盖意图必须显式落笔（作者写 `override` 才生效），同名注册永远不会被静默顶掉。
 *
 * 装配序契约（B3）：mod 生命周期先于语料 CALLSHARP 装载趟裁决（生产装配序 =
 * 宿主注册终表 → mod attach → prefillSharpLinks），因此 override 在装载趟之前生效——
 * **常量名 CALLSHARP 行联结期就绑到覆盖后的本体**（与原版「插件 DLL 先于脚本联结」同相位模型）。
 * 名字按大写归一取键。
 *
 * clean 语义（§18.7）：出参回写机制是**契约**（原版 IPluginMethod.Execute 无返回值、结果全靠
 * 改实参槽），本 facade 保留这一干净机制但不复刻官方装载期怪癖；ADR-005 仍约束 ERB 语句面。
 */
interface FunctionApi {
    /**
     * 注册一个可被 ERB CALLSHARP 调用的函数（**新增**）。[name] 已存在抛 [IllegalArgumentException]。
     */
    fun register(name: String, function: ModFunction)

    /**
     * 显式替换一个**已存在**的 CALLSHARP 可调函数（B3：mod 覆盖宿主内建/先注册者的唯一合法通道）。
     * [name] 不存在抛 [IllegalArgumentException]（fail-closed 对称：拼错的名字在替换口当场红，
     * 不静默变新增）。替换自下一个调用起生效；生产装配下常量名 CALLSHARP 行的装载趟联结
     * 晚于 mod 注册，联结绑到的就是替换后的本体。
     */
    fun override(name: String, function: ModFunction)

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