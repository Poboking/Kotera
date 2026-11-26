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

    /**
     * 新开一局完成（默认空实现）。
     *
     * 相位：`EVENTFIRST`（`SYSTEM.ERB` 的 `@EVENTFIRST`）已跑完、原版变量已 [RESETDATA] 复位、
     * 脚本侧的 pending `BEGIN` 已消费，但**玩家尚未操作**（`SHOP` 脚本体未跑）。
     * 对应 `Process.cs:2169-2179` 的 `BEGIN FIRST` 收尾相位（锚见 [com.eratw.port.core.proc.BeginFlow]）。
     *
     * **可多次触发**：玩家回标题再开新局时同一 mod 实例会再收一次（引擎不重建 mod），
     * 实现必须自复位、不得依赖「只调一次」。本回调在 VM 线程上，禁止阻塞、禁止弹 UI。
     */
    fun onNewGame() {}

    /**
     * 读档完成（默认空实现）。
     *
     * 相位：存档的变量池已恢复（`FileSystemOps.loadData`）、mod 侧车（`.rt2`）与 CSV 注入值
     * 已就位、`EVENTLOAD` 已跑完，`SHOW_SHOP` 尚未跑。对应 `Process.cs:2680-2683` 的
     * `LOADDATA` → `EVENTLOAD` 之后那段。
     *
     * 这条回调**只表示「一次读档走到这里了」**，不携带成功/失败标志：`.rt2` 侧车缺失或损坏时
     * 引擎按既有口径 `warn` 后继续，mod 若要判「我的数据在不在」应查 [StorageApi]。
     * 本回调在 VM 线程上，禁止阻塞、禁止弹 UI。
     */
    fun onSaveLoaded() {}

    /**
     * 回合边界（页边界）到达（默认空实现）。
     *
     * 相位：引擎在「玩家操作结束、进入下一页之前」的安全点回调 —— 对应宿主
     * `GameHost` 的 `onTurnReady` 边界（`GameHost.kt:896` 调 `modRuntimeSession.onTurnBoundary()`），
     * 其时本回合的输入/动作已落定、下一回合尚未开始，`DAY` 读数可作边界日。
     * 官方 C# 侧没有与「回合」一一对应的回调（`Process.cs` 的 `BEGIN FIRST` 收尾只在开局一次），
     * 本回调是移植侧扩展，无 C# 行号锚。
     *
     * 与相位派发的关系：宿主会先按边界日派发内建相位（日始/日终/动作点，见
     * [BuiltinPhase]），再回调本方法；需要更细时机的 mod 仍应走 [PhaseApi.subscribe]。
     *
     * 本回调在 VM 线程上，禁止阻塞、禁止弹 UI；一个 mod 抛异常只隔离该 mod，不拖垮本拍其余 mod。
     */
    fun onTurnBoundary() {}

    /**
     * 玩家在设置页改了本包的设置项（默认空实现）。
     *
     * 触发时机：玩家在设置页对本包设置项**写入成功之后**、值已持久化（设置页按包原子写文件
     * 完成后的下一个安全点）。此时设置快照**已更新** —— 回调里 `context.settings` 读到的已是
     * 新值，无需等重开游戏或重开一局；作者在此刷新派生状态即可。
     *
     * `changedKeys` 只含**本包声明过**的设置键（键名按包过滤在宿主灌入侧完成，别的包的键不会
     * 出现在这里），顺序 = 玩家本次写入顺序；一次批量改多项时一次回调带全部键，不逐键拆回调。
     * 键集合与顺序只是通知面，值的真源仍是 `context.settings`（与本面其它 API 同一口径）。
     *
     * 本回调在 VM 线程上（与 [onLoad]/[onNewGame] 同一线程）：禁止阻塞、禁止弹 UI；
     * 一个 mod 抛异常按 per-mod 隔离（记录后不二次抛，不影响其余 mod，也不改本 mod 启用状态）。
     */
    fun onSettingsChanged(changedKeys: List<String>) {}

    /**
     * 本包（manifest）版本升级的迁移通知（默认空实现）——对标 Factorio 的 migrations.lua：
     * mod 改了存储结构后，装载老存档时作者在这里得到通知并搬数据。
     *
     * ## 触发时机（只在装载既有存档时判）
     *
     * 宿主在 [onSaveLoaded] 派发完之后，对本会话装着且启用成功的每个包，读 per-package storage
     * 保留键（core 侧常量 `__eratw.pkgVersion`，`__eratw.` 前缀为宿主保留、作者不得使用）里记账的
     * 「上次版本」，与当前 [ModManifest.version] 比较：
     *
     * - 无记账（首次见到本包：新局、首次 attach、或旧档里没有本包分段）⇒ **只记账当前版本，不回调**；
     * - 记账版本 == 当前版本 ⇒ 什么都不做；
     * - 记账版本 != 当前版本（含降级：新包版本号更小）⇒ 回调本方法（`fromVersion` = 记账版本原文，
     *   `toVersion` = 当前版本原文），回调返回后宿主把当前版本回写记账键。降级同样通知：
     *   是否搬数据、能不能安全回退，由作者自行判断。
     *
     * ## 与 [onMigrate] 的分工
     *
     * [onMigrate] 是**数据版本**（[dataVersion]，Int）闸——落后才调、迁移失败宿主拒载（fail-closed）；
     * 本回调是**包版本**（manifest version，String）**通知面**——只比较、只通知、不拒载、不改本 mod
     * 启用状态。两者互不替代：改了 storage 数据结构走 [dataVersion] + [onMigrate]；只是换了包版本号、
     * 想借机搬旧数据（键改名、格式整理）走本回调。
     *
     * ## 允许做什么 / 线程
     *
     * 与 [onMigrate] 同口径：只许读写本包自己的 [ModContext.storage]，不许碰别的包与引擎全局
     * （§7 Mutation Boundary）。时序严格排在 [onSaveLoaded] **之后**（读档缝先恢复侧车、再跑
     * EVENTLOAD、末尾先扇出 [onSaveLoaded] 再逐包对账派发本回调）。本回调在 VM 线程
     * （与 [onSaveLoaded] 同一线程）：禁止阻塞、禁止弹 UI；抛异常按 per-mod 隔离（记录后不二次抛、
     * 不影响其余 mod、不改本 mod 启用状态），版本照常前滚，不因迁移代码抛错把档锁死在旧读数上。
     */
    fun onPackageUpgrade(fromVersion: String, toVersion: String) {}
    // ------------------------------------------------------------------
    // 版本协商与数据迁移（Lane E）：作者面契约版本 + 每 mod 数据版本
    // ------------------------------------------------------------------

    /**
     * 本 mod 声明自己**需要**的作者面契约版本（[ModApi.VERSION] 口径）。
     *
     * 缺省值 = 编译这套 SDK 时的 [ModApi.VERSION]，所以既有 mod（包括 RT2 波内置载体）
     * 不改一行代码就自动跟随；只有「显式要更新的契约」才需要覆写。递增规则见 [ModApi]。
     *
     * 装载期规则（单向兼容）：`apiVersion > ModApi.VERSION` ⇒ 宿主**只拒这一个 mod**
     * （`active=false`，原因经 `mod.install … failure=…` 日志通道上屏），其余 mod 与本体照常；
     * `<=` ⇒ 正常装载。与 [ModManifest.version]（mod 自己的语义版本，给人看的）是两件独立的事。
     */
    val apiVersion: Int get() = ModApi.VERSION

    /**
     * 本 mod **自己包内数据结构**的版本（作者声明；缺省 0 = 从未做过结构迁移）。
     *
     * 只影响 [onMigrate] 的判断与 `.rt2` 侧车里的 `dataVersion` 记录，与 [apiVersion]（契约版本）、
     * [ModManifest.version]（语义版本）都无关；也**不写进 `ModManifest` 线格式**（manifest 只放
     * mod 的清单信息，数据版本由宿主在侧车段尾自己记）。递增规则由作者自定，但**每次改动本包 storage
     * 里数据的结构/语义就要 +1**，否则旧档不会被迁移（缺省 0 的老档按 0 处理，永远不会触发迁移）。
     */
    val dataVersion: Int get() = 0

    /**
     * 本 mod 声明的**依赖包**（packageId 列表；缺省空 = 无依赖，装载顺序与历史逐位一致）。
     *
     * ## 是什么 / 不是什么
     *
     * - 是**装载/启用顺序约束 + 依赖存在性**：宿主在同一批装载里先装依赖、后装本 mod；依赖不在
     *   本次装载集合（未安装）、已停用（`.off`），或被版本门/自身生命周期判失败 ⇒ 本 mod **拒载**
     *   （`active=false`，原因经 `mod.install … failure=…` 日志通道上屏），与依赖无关的 mod 照常。
     * - **不是版本约束**：要某版作者面契约写 [apiVersion]；要依赖包数据结构的某版由依赖包自己用
     *   [dataVersion] + [onMigrate] 管 —— 这里只认 packageId，不认版本号。
     *
     * ## 装载期规则（fail-closed，不部分装载）
     *
     * 1. 同批依赖按**稳定拓扑序**装载：无依赖声明的 mod 之间保持载体声明序（既有行为逐位不变），
     *    同层节点也保持声明序（可复现、可对拍）。
     * 2. 依赖成环（含指向自己）⇒ **环上所有成员全部拒载**；诊断消息点名环成员，环外 mod 照常。
     * 3. 依赖存在但未成功启用（版本门拒、onLoad/onEnable 抛错）⇒ 依赖者一并拒载（含链式传递）。
     * 4. 拒载的 mod 不建 context、不激活 storage、不进生命周期，与版本门拒载同一条回滚口径；
     *    已启用的无关 mod 不会因它被回滚。
     */
    val requires: List<String> get() = emptyList()

    /**
     * 读档发现档内数据版本落后于本 mod 的声明时，宿主在迁移窗口调一次（默认空实现）。
     *
     * ## 调用窗口
     *
     * - 只在**读档恢复**出 `storedDataVersion < dataVersion` 时调用，两个参数分别是档内记录版本与
     *   本 mod 当前声明版本；`storedDataVersion` 由 `.rt2` 侧车按包记录，**旧档缺这条记录视作 0**。
     * - 首装 / 新局 / 该包在档里没有分段（无侧车数据）⇒ **不调用**，宿主直接把当前 [dataVersion]
     *   记进侧车。
     * - 装载期可预置容器（读档恢复后重新装载、或测试预置）时，调用点严格在 [onLoad] 之后、
     *   [onEnable] 之前；**生产读档缝**（侧车恢复发生在首轮 [onEnable] 之后，见
     *   `ModRuntimeHost.rebindStorage` 的时序偏离登记）在恢复缝调用，早于 [onSaveLoaded]。
     * - `storedDataVersion > dataVersion`（用户装了旧版 mod 打开新档）⇒ **不调用**：宿主拒绝该 mod
     *   （`active=false` + 原因上屏），不静默降级写坏新版数据。
     * - 在 VM 线程（与 [onLoad]/[onEnable]/[onSaveLoaded] 同一线程）上执行：禁止阻塞、禁止弹 UI、
     *   不得 `RuntimeScheduler.submit` 后等结果（会自锁）。
     *
     * ## 允许做什么
     *
     * **只许读写本包自己的** [ModContext.storage]，或用已注册的本包能力搬本包数据；不许改别的 mod
     * 的数据 / storage，不许改引擎全局状态（§7 Mutation Boundary）。失败按 per-mod 隔离：该 mod 判失败
     * （`active=false`，原因进失败通道），其余 mod 与本体不受影响；失败时宿主不删旧段，下次读档仍会重试。
     *
     * ## 幂等口径
     *
     * 宿主**在回调成功返回后**才把新版本写回侧车；回调中途抛异常/崩溃会保持旧版本号，下次读档
     * **再调一次**。实现应尽量可重入（必要时先落自己的完成标记）。
     */
    fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}
}
