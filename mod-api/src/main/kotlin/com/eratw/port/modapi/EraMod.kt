package com.eratw.port.modapi

/**
 * 一个 Kotlin mod 的入口契约（RT2 Kotlin Mod SDK 路 A，era-arch-v61u.md §18.4）。
 *
 * mod 作者实现本接口，引擎在打包期把实现类烧进 APK（kotlinc → d8 → dex，**非** DexClassLoader；
 * 路 A 同包编译，设备端零运行期编译器、零动态装载）。引擎按固定时序回调四个生命周期方法，
 * mod 不得自调时序、不得在回调外随意改游戏状态。
 *
 * ## 时序与转移边界（§18.4 / §7 Mutation Boundary）
 *
 * ```
 * onLoad(context) → onEnable() → …运行… → onDisable() → onUnload()
 * ```
 *
 * - [onLoad]：创建实例、读声明、拿 [ModContext]。此时**不得**改实体结构。
 * - [onEnable]：注册事件/功能（经 [ModContext] 的各 API）。
 * - [onDisable]：撤销注册、停任务。
 * - [onUnload]：释放资源。
 *
 * **ERB 正在执行时不得改实体结构**（Active Roster Pinning §7、变量冻结后才执行不变量）；
 * 改 roster/实体结构只在转移边界放行。所有改游戏状态的操作经 [RuntimeScheduler.submit]
 * 排到主执行调度器（§18.5），不在后台线程直接改状态。
 *
 * ## 失败隔离（§18.4）
 *
 * 一个 mod 初始化失败**不拖垮其他 mod 与 base**：引擎按 PackageId 隔离注册，失败 mod 降级
 * 报告。mod 不得创建永不结束的后台线程（卸载时无法回收）。
 *
 * clean 语义（§18.7）：本契约取干净语义，不复刻官方 bug；ADR-005 仍约束 ERB/vanilla VM 面。
 */
interface EraMod {

    /** 创建实例、读声明、拿服务入口。不得在此改实体结构。 */
    fun onLoad(context: ModContext)

    /** 注册事件/功能。 */
    fun onEnable()

    /** 撤销注册、停任务。 */
    fun onDisable()

    /** 释放资源。 */
    fun onUnload()
}