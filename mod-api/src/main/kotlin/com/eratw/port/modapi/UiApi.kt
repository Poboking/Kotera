package com.eratw.port.modapi

/**
 * 界面贡献能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3 / §6.1 互动贡献 + render）。
 *
 * mod 经本 API 贡献菜单项/交互项，不自绘绕过引擎渲染管线（一个运行时，§9）。贡献项的点击
 * 回调经引擎输入状态机派发；mod 改游戏状态仍须经 [RuntimeScheduler.submit]（§18.5）。
 *
 * ## 文案纪律（AGENTS.md §6）
 *
 * 玩家可见文案**禁止内联字面量**：[labelKey] 是所在域三语文案表（ZH/EN/JA）的键，不是文案本身。
 * mod 增文案条目须三语同加并同步对应形状闸计数（漏一档编译不过）。
 *
 * ## 接线状态（诚实边界，K1 只冻结契约）
 *
 * §6.1 互动贡献点与 render 的接线是 **K3 脏活**。本接口在 K1 冻结 mod 侧贡献契约形状；
 * 生产适配器（贡献项进引擎显示列表/命中区）待 K3。
 *
 * clean 语义（§18.7）：取干净贡献语义，不复刻官方 UI 怪癖；ADR-005 仍约束 vanilla 渲染面。
 */
interface UiApi {
    /**
     * 贡献一个交互项。[labelKey] 指向三语文案表键；[onClick] 是点击回调（经引擎输入状态机派发）。
     * 返回的 [Registration] 可在 [EraMod.onDisable] 时撤销。
     */
    fun registerInteraction(labelKey: String, onClick: () -> Unit): Registration
}