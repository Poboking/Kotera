package com.eratw.port.modapi

/**
 * 受授权游戏状态读侧（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3，wrap `VariableStore` 读侧 curated facade）。
 *
 * mod 经本 API **读**游戏状态（全局变量等），不直接持有内部 `VariableStore` 实现对象（§18.2 铁边界）。
 * 这是只读 curated 视图：写游戏状态走 ERB 或专门的改状态 API（经转移边界），不在本面开放任意写，
 * 防 mod 绕过引擎不变量乱改原生变量。
 *
 * ## 接线状态（诚实边界，K1 只冻结契约）
 *
 * 「受授权」的具体白名单（哪些变量可读、按 PackageId 的授权范围）与 `VariableStore` 读侧接线是
 * **K3 脏活**。本接口在 K1 冻结最小读侧形状；curated 授权面随 K3 收口。
 *
 * clean 语义（§18.7）：读返回当前值的快照（不可变），缺变量返回干净哨兵（int 0 / str ""），
 * 不复刻官方 VM 怪癖；后台线程可读不可变快照，改状态仍须经 [RuntimeScheduler.submit]（§18.5）。
 */
interface GameStateApi {
    /** 读一个整型全局变量；缺变量返回 0。 */
    fun getIntVar(name: String): Long

    /** 读一个字符串全局变量；缺变量返回 ""。 */
    fun getStrVar(name: String): String
}