package com.eratw.port.core.mod

import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.SettingDecl

/**
 * mod 存储的宿主保留键前缀（ModDX2-A）：以 `__eratw.` 开头的 storage 键归宿主记账专用，作者不得
 * put/remove。分层强拒已由 ModDX-4 BS 落地（方案 A 宿主专用门）：作者面 BoundStorage
 * put/remove 对保留前缀 fail-closed 拒；宿主记账只经 `putReservedKey`（ModStorageStore 唯一
 * 宿主写门，非保留前缀键同样拒——双向 fail-closed）。本常量为该前缀的单源。
 */
const val MOD_STORAGE_RESERVED_KEY_PREFIX: String = "__eratw."

/**
 * per-package 版本记账键（ModDX2-A）：每包 storage 段里记录「宿主最后一次记账的本包 manifest
 * 版本」的保留键，值 = 版本原文字符串。落在 [ModStorageStore] 的普通分段里，随 `.rt2` 侧车
 * 免费往返（零格式变更）。保留前缀约定见 [MOD_STORAGE_RESERVED_KEY_PREFIX]。
 */
const val MOD_STORAGE_PACKAGE_VERSION_KEY: String = "__eratw.pkgVersion"

/**
 * 驱动 [EraMod] 四段生命周期的生产宿主（RT2 波 C 车 C1，era-arch-v61u.md §18.4 / §12.1）。
 *
 * 固定时序 `onLoad(context) → onEnable() → …运行… → onDisable() → onUnload()`：
 * [install] 逐 mod 走前两段，[shutdown] 逐 mod 走后两段，[runAll] 把四段连起来供短生命周期
 * 与测试一次跑完。每个 mod 的 [ModContextImpl] 与 per-mod 注册项列表挂本 host 实例，
 * 无任何静态可变全局。
 *
 * 失败隔离（§18.4 / 裁定 §3.2.3）：任一 mod 在 onLoad 或 onEnable 抛异常，只该 mod 判失败、
 * 只回滚它自己已注册的全部项、失败原因进 logger 与 [ModRuntimeState] 报告；循环继续处理其余
 * mod，host 不整体 abort、不吞异常不留痕。失败 mod 在 shutdown 阶段的 onDisable / onUnload
 * 一律用异常隔离包住且由 shutDown 标志保证幂等，不二次抛。
 *
 * 构造段同在围栏内（W3-FAILCLOSE）：[storage] 激活、[ModContextImpl] 构造、Entry 登记任一环节
 * 抛异常，产出与 onLoad/onEnable 失败同形的失败报告项（active=false + 原因进日志面），循环继续、
 * 不整链 abort —— 构造期校验（如声明快照缺包的 require，ModContextImpl init）不再直穿 install，
 * 与 ModCarrierRegistry KDoc「不在快照内 = 逐 mod fail-closed（active=false 上日志面）」对齐。
 * 回退选择：storage 用既有对称操作 [ModStorageStore.deactivate]（只移出激活集、数据整段留下，
 * 与「禁用不等于删除」口径一致；构造前已激活的包——如读档 knownPackages——跳过回退，不把
 * 装前状态误改成禁用）。构造失败的 mod 未进生命周期（onLoad 没跑），不登记 entries，
 * shutdown 不走它的 onDisable / onUnload。边界：重复装载同 packageId 仍是硬失败（require
 * abort 整链、不产出失败报告项），**唯一放宽**是「本次 install 之前已登记且已回滚失败」的
 * entry 允许同会话重装替换（[openReinstallSlot]）；批内重复 / 正常装载中 / 已 shutdown 的
 * 重复一律照旧硬失败，消息逐字不变。
 *
 * 撤销语义（裁定 §3.2.4）：host 维护 per-mod 的 [Registration] 列表；[revoke] 逐个
 * unregister，单个抛错只记录不中断其余撤销，撤完 hook / phase / job / interaction / ui
 * 各引擎侧都不再命中。撤销只作用于传入的那一个 mod 条目，绝不触碰其它 mod。
 *
 * K3-B（RT2 波 G 车 G1）：构造可选的 [DecisionPipeline] 逐个透传给每个 [ModContextImpl]，使其
 * `actions` facade 成为决策管线「同包 facade」并经宿主的 per-mod 注册收集器纳入 onDisable
 * 撤销；缺省 null 时各 context 的 `actions` 是显式 fail-closed 桩，其余生命周期零行为不变。
 *
 * RT2 车次表 E 车：构造可选的 [ICharaSlotProjector] 逐个透传给每个 [ModContextImpl]，使其
 * `roster` facade 成为真投射桥的公开转发面；缺省 null 时是显式 fail-closed 桩。
 * **两车都只加可选参数，既有 `ModRuntimeHost(` 调用点零改动**；真投射器的生产构造点
 * （`RealCharaSlotProjector` + `VariableStore.charaIndexResolver`）属 F 车，不在本类接线。
 *
 * 真实主执行调度器、pkgShortId 分配、四引擎与 VM dispatch 的宿主注入点属「需组长接线」，
 * 本波用构造参数注入加测试 fake。
 *
 * ## 版本协商（Lane E：API/ABI 版本 + 每 mod 数据版本）
 *
 * 装载期有两道**逐 mod**（互不牵连）的版本门，都在进入生命周期之前判、都不 abort 整链：
 *
 * 1. **契约版本门**：[EraMod.apiVersion] `> ModApi.VERSION` ⇒ 只拒这一个 mod
 *    （[ModApiVersionMismatchException]），`<=` 正常装载（老 mod 不声明 = 编译期值）；
 * 2. **数据版本降级门**：[ModStorageStore.dataVersionsByPackage] 有记录且 `>` [EraMod.dataVersion]
 *    （旧版 mod 打开新档）⇒ 只拒这一个 mod（[ModDataVersionDowngradeException]），数据整段留下、
 *    本会话不启用 —— fail-closed，不许静默降级写坏新版数据。
 *
 * 两条失败都经 [ModRuntimeState] 交给装配侧，由既有 `mod.install pkg=… active=false failure=…`
 * 行上屏（格式不变）。
 *
 * 迁移窗口（正常进度）：容器里该包版本记录 `<` declared 时，在 onLoad 之后、onEnable 之前调
 * [EraMod.onMigrate]，成功才把 declared 写回容器；无版本记录（新局/无侧车）直接写 declared、
 * 不迁移。生产读档缝的时序偏离登记见 [rebindStorage]。
 */
class ModRuntimeHost(
    private val snapshot: DeclarationSnapshot,
    private val entityKernel: IEntityKernel,
    private val nativeMethods: NativeMethodRegistry,
    private val logSink: (String) -> Unit,
    private val runtime: RuntimeScheduler,
    private val shortIdOf: (String) -> Int = { 1 },
    private val intVarView: (String) -> Long = { 0L },
    private val strVarView: (String) -> String = { "" },
    /**
     * 变量存在性探测供给（ModDX-C 组长接线）：生产 = `defaultHost` 从 [com.eratw.port.android.AssemblyInputs.hasIntVarView]
     * / [com.eratw.port.android.AssemblyInputs.hasStrVarView] 透传（与 intVarView/strVarView 同源读数）。
     * null = 台架 / 未接线形态 ⇒ `context.gameState.hasIntVar/hasStrVar` 恒 false（空集口径，不抛）。
     */
    private val hasIntVarView: ((String) -> Boolean)? = null,
    private val hasStrVarView: ((String) -> Boolean)? = null,
    private val decisionPipeline: DecisionPipeline? = null,
    /**
     * 会话级 mod 存储容器。宿主（存档侧）经它 [ModStorageStore.encode] / [ModStorageStore.load]，
     * 每个 mod 拿到的是绑到本包那一段的视图，跨包写不进去。缺省新建空容器 = 与今天等价的未接线形态。
     */
    val storage: ModStorageStore = ModStorageStore(),
    /** 真角色槽投射器（E 车公开面注入缝）；null = F 车未接线，roster 显式 fail-closed。 */
    private val projector: ICharaSlotProjector? = null,
) {

    init {
        // 单包存储阈值 WARN 接既有 logSink（与 mod.assetmap / mod.resources 同一出口，见 ModStorageFootprint.kt）。
        // 接线放构造期：任何写入路径（作者 put / 宿主 putReservedKey）发生前就已就绪，生产无需装配侧改。
        storage.bindStorageFootprintWarningSink(logSink)
    }

    internal class Entry(
        val mod: EraMod,
        val context: ModContextImpl,
        val registrations: MutableList<Registration>,
    ) {
        var enabled: Boolean = false
        var failure: Throwable? = null
        var shutDown: Boolean = false
    }

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 注册面读数失败的 WARN 出口（诊断只读查询不许抛：异常降级为零读数，原因照装载日志口径上屏）。
     * 属性出口不占 [ModRuntimeHost] 的成员函数额度（detekt `TooManyFunctions` 长期临界）。
     */
    internal val readoutWarningSink: (String) -> Unit get() = logSink

    /**
     * **全链共享**的 hook 执行引擎（§20.1；RT2 P1-5 真接线）。
     *
     * 一个会话一份：槽序（priority 升序 + 链接声明序）跨 mod 才有意义，所以每个
     * [ModContextImpl] 装载时经 [ModContextImpl.adoptHookPipeline] 换成这一份，`context.hooks`
     * 的绑定全落在这里。lazy = 关态 / 无 mod 声明的会话不建表（零成本）。
     *
     * 装配侧经 [hookPipeline] 取它挂到 [FunctionHookWiring]（`ProcessRuntime.doCall` /
     * `executeEvent` 的调用点现读 `store.aux`）。
     */
    private val sharedHookPipeline: HookPipeline by lazy {
        HookPipeline.of(snapshot.hookDeclarations)
    }

    /** 本会话的全链 hook 引擎（公开面：装配侧把它交给 [FunctionHookWiring.install]）。 */
    val hookPipeline: HookPipeline get() = sharedHookPipeline

    /**
     * **全链共享**的交互阶段管线（§21.4，RT2 P0-LAST 生产触发点接线）。
     *
     * 一个会话一份：交互阶段（PRECONDITION/PRE/MAIN/POST/SETTLEMENT）的执行序是
     * 「阶段 → packageRank（声明链接序）→ packageId」，packageRank 跨 mod 才有意义；每个
     * context 各持一份会把序按 mod 分组拆散。所以每个 [ModContextImpl] 装载时经
     * [ModContextImpl.adoptInteractionPipeline] 换成这一份，`context.interactions.registerHook`
     * 的绑定全落在这里。lazy = 关态 / 无交互声明的会话不建表（零成本）。
     *
     * 宿主页边界（app 侧 `ModInteractionWire`）把玩家真实输入送进 `run(...)`。
     */
    private val sharedInteractionPipeline: InteractionPipeline by lazy {
        InteractionPipeline.of(snapshot.interactionHookDeclarations)
    }

    /** 本会话的全链交互管线（公开面：页边界输入触发点与 UI 观察面共用一份）。 */
    val interactionPipeline: InteractionPipeline get() = sharedInteractionPipeline

    /**
     * **全链共享**的任务调度器（ABI-10）：静态槽与动态模板都只有一份任务态。
     *
     * 每个 context 装载时经 [ModContextImpl.adoptJobScheduler] 换成这一份；宿主页边界在全部
     * 相位派发后只调用一次 [JobScheduler.runDue]，避免逐包推进别人的槽位或重复消费实例。
     */
    private val sharedJobScheduler: JobScheduler by lazy {
        JobScheduler.of(
            snapshot.jobDeclarations,
            templates = snapshot.jobTemplateDeclarations,
        )
    }

    /** 本会话的全链任务调度器（生产节拍与存档任务态共用）。 */
    val jobScheduler: JobScheduler get() = sharedJobScheduler

    /**
     * 已装载 mod 的 context 只读快照（UI 贡献项枚举面；按装序）。
     *
     * 返回新 List，但元素是活对象：**只许 VM 线程读**（装载/卸载与各 context 的注册都在
     * VM 线程改 `entries` 与注册表）。主线程只读 `ModUiSurface` 的不可变快照。
     */
    val installedContexts: List<ModContextImpl> get() = entries.values.map { it.context }

    /**
     * **全链共享**的事件处理器注册表（§6.1 贡献点；RT2 P1 真接线）。
     *
     * 一个会话一份：执行序是「桶位 → 桶内注册序」，注册序跨 mod 才确定，所以每个
     * [ModContextImpl] 装载时经 [ModContextImpl.adoptEventRegistry] 换成这一份。
     *
     * 装配侧经 [ModEventWiring.install] 把它挂到 `store.aux`，`ProcessRuntime` 在
     * `dispatchEventMembers` 的桶边界现读（未装 = 完全旁路，golden 零变化）。
     */
    val eventHandlers: EventDispatchRegistry = EventDispatchRegistry()

    /**
     * 宿主级随机派生种子覆盖值（§21.6）：非 null 时**优先于**决策管线的当前种子。
     *
     * 读档采纳档内种子后由 [adoptRandomSeed] 写入，此后才装载的 mod 也要用同一颗种子。
     */
    private var randomSeedOverride: Long? = null

    /**
     * 会话级耗时账本（§18.5 诊断面扩展，RT2 P1 耗时归因）。
     *
     * 装配侧在 `install` 之前赋值：四个测量点（lifecycle / tick / hook / event）都往这一簿上写，
     * shutdown 后由宿主一次性打 `mod.timing …` 行。为 null（缺省）时四个测量点完全零开销 ——
     * golden 与「关态零日志」判据逐位不变。
     */
    var timing: ModTimingLedger? = null

    /**
     * 会话级单次耗时预算（§18.5 阈值诊断）。
     *
     * 装配侧在 `install` 之前赋值：装上后 hook / event / interaction 三类作者处理器逐次计时，
     * 越阈值记一条诊断并计数（不中断、不改语义）。为 null（缺省）时三处完全零开销 ——
     * 与既有派发逐位不变。**不进构造签名**：与 [timing] 同口径，改装配不改构造。
     */
    var handlerBudget: ModHandlerBudget? = null

    /** 可挂起剧情的引擎等待边界；null = 未接线，作者调用 runDurable 当场 fail-closed。 */
    var durableInteractionHost: DurableInteractionHost? = null

    /**
     * 逐包设置快照（RT2 per-mod 配置；键 = packageId，缺省空表 = 不注入）。
     *
     * 装配侧在 `install` 之前赋值（app 侧 `ModSettingsStore` 读设备侧按包配置文件 + 解
     * manifest 的 `settings` 声明）。**不进构造签名**：与 [timing] 同口径，改装配不改构造
     * （detekt 结构性 ID 不重刷）。缺省空表时本包的 `context.settings` 是显式 fail-closed 桩。
     */
    var settings: Map<String, ModSettingsSnapshot> = emptyMap()

    /**
     * 已知事件名集合（构建期 `EventRegistry` 的事件面）：非空时 `context.events.register`
     * 对**未知事件名**当场抛 [IllegalArgumentException]（附最接近候选），空集（缺省）不校验。
     *
     * 加闸的理由：事件名拼错（如 `EVENTTURNEND` 写成 `EVENTTURNED`）加闸前是静默永不触发，
     * 作者与玩家都看不到；灌入真实事件名集合后，错误在 `onLoad` 注册的那一刻就暴露。
     *
     * 装配侧在 `install` 之前赋值（app 侧 `ModRuntimeAssembly.attach` 取构建期
     * `SymbolCatalog.events` 的键）。**不进构造签名**：与 [timing]/[handlerBudget]/[settings]
     * 同口径，改装配不改构造。缺省空集 = 台架 / 单测 / 无构建期事件面的会话口径。
     */
    var knownEventKeys: Set<String> = emptySet()

    /**
     * 已知可挂函数键集合（链接期 `SymbolCatalog.bindings` 的非事件胜者键）：非空时
     * `context.hooks.register` 对**目录外目标键**当场抛 [IllegalArgumentException]（附最接近候选），
     * 空集（缺省）不校验 —— 与 [knownEventKeys] 完全同构（MODDX4-B3 发现面）。
     *
     * 加闸的理由：hook 目标键拼错（含 Manifest 声明与 bind 两处一致地拼错——那种错槽表照建、
     * 处理器照绑，hook 却永不触发）加闸前是静默失效；灌入真实函数目录后，错误在 `onLoad`
     * 注册的那一刻就暴露。
     *
     * 装配侧在 `install` 之前赋值（app 侧 `ModRuntimeAssembly.attach` 取链接产物
     * `SymbolCatalog` 的 `bindings.keys ∪ events.keys` —— 2026-11-26 B4：hook 目标键含事件键，
     * 「hooks PREFIX 拦事件」的建议在生产装配下走通；能否绑定仍由 HookPipeline 的
     * 「槽未声明」闸把守）。**不进构造签名**：与 [knownEventKeys] 同口径，改装配
     * 不改构造。缺省空集 = 台架 / 单测 / 无链接目录的会话口径。归一（trim + 大写 ROOT）收在
     * context 写入侧，本属性只透传原始集合。
     */
    var knownFunctionKeys: Set<String> = emptySet()

    /**
     * 会话级受控写落库面（ModDX4-B1：`GameStateApi.setIntVar` / `setStrVar` 的生产供给）。
     *
     * 装配侧在 `install` 之前赋值（app 侧 `ModRuntimeAssembly.attach` 构造
     * [ModDx4WriteApplier]：授权闸 + 转移边界兑现的 apply 闭包）；install 在每个 context 的
     * onLoad 之前经 [ModContextImpl.adoptGameStateWrite] 扇出按包写 facade。
     * 为 null（缺省）时各 context 的写 setter 保持缺省 fail-closed UOE（不静默吞写）——
     * 与 [settings] / [knownEventKeys] 同口径，**不进构造签名**，改装配不改构造。
     */
    var gameStateWriteApplier: ModDx4WriteApplier? = null

    /**
     * **全链共享**的跨 mod Kotlin 服务注册表（RT2 P1 工程契约；mod 之间按接口类型发布/查找）。
     *
     * 一个会话一份：查找方与发布方必须落在同一份表上才可能跨 mod 命中，所以每个
     * [ModContextImpl] 装载时经 [ModContextImpl.adoptServices] 换成「本包身份 + 这一份表」。
     * 卸载 / 安装失败回滚 / 读档拒载三条撤销路径都调 [ModServiceRegistryImpl.revokePackage]
     * 把该包发布的服务摘干净（契约边界 4：卸载即撤销）。
     *
     * **不进构造签名**：与 [timing] / [settings] 同口径，改装配不改构造（detekt 结构性 ID 不重刷）。
     */
    val services: ModServiceRegistryImpl = ModServiceRegistryImpl()

    /**
     * 逐包资源存储（RT2 mod 资源注入第一刀）：按 packageId 懒解析 `mods/rt2/<pkg>.assets/`。
     *
     * 装配侧在 `install` 之前赋值（app 侧 `ModRuntimeAssembly.attach` 以
     * `manifestDirFor(gameRoot)` 为根构造）。为 null（缺省）时本包 `context.resources`
     * 保持显式 fail-closed 桩，不返回空表兜底。
     *
     * 类型是 store 而非单实例 [com.eratw.port.modapi.ModResourceApi]：资源面**按包隔离**，
     * 每个 context 在 `install` 时经 [ModResourceStore.apiFor] 取**本包视图**（各自根目录不同，
     * 拿不到别的包的资源）。**不进构造签名**：与 [timing]/[handlerBudget]/[settings] 同口径，
     * 改装配不改构造（detekt 结构性 ID 不重刷）。
     */
    var resources: ModResourceStore? = null

    /**
     * 会话级 mod 自带三语文案表（RT2 多语言文案）：按 packageId 隔离，`install` 时经
     * `adoptStrings` 绑进各 context，卸载 / 失败回滚时按包清空（[revokeEntry]）。
     *
     * 空表近零成本（包未声明文案时解析直接返回 null）。渲染侧（app `ModUiSurface`）也读
     * **这一份**表 —— 两份状态必然分叉。**不进构造签名**：与 [resources] 同口径。
     */
    var strings: ModStringsRegistry = ModStringsRegistry()

    /**
     * 当前设置界面的语言码（`zh` / `en` / `ja`，取值 = `SettingsLanguage.stored`）。
     *
     * 壳层装配时注入（本类不认识 Android `Context`，不自判 Locale）；未注入时按 [DEFAULT_MOD_LANGUAGE]。
     * 作者 `context.strings.of()` 与 [com.eratw.port.modapi.StringsApi.language] 实时读本属性，
     * 换语言后不需要重装 mod。渲染侧另有更细的一路：浮层按 `ModUiCopy.lang` 逐帧解析。
     */
    var language: String = DEFAULT_MOD_LANGUAGE

    /**
     * 宿主音频播放汇（Lane AN；RT2 资源注入第二刀）：app 侧用 platform 的 MediaPlayer 封装实现。
     *
     * 装配侧在 `install` 之前赋值（app 侧 `ModRuntimeAssembly.attach` 经 `bindAudioSink`）；为
     * null（缺省）时 `context.audio` 保持显式 fail-closed 桩，不返回静默 false 兜底。
     * **不进构造签名**：与 [timing]/[resources]/[settings] 同口径，改装配不改构造。
     */
    var audioSink: ModAudioSink? = null

    /**
     * 逐 mod 装载并启用：为每个 mod 装配 [ModContextImpl]，把其注册项收集到本 mod 专属列表。
     * 失败围栏盖两段：构造段（[storage] 激活 → [ModContextImpl] 构造 → Entry 登记）与
     * onLoad/onEnable 段。任一 mod 失败只回滚它自己（含失败原因进日志面），产出同形失败报告项
     * （active=false），循环继续，不整体 abort。硬失败例外：重复装载同 packageId 是
     * registry 编程错误而非坏包降级，require 当场 abort 整链、不产出失败报告项 —— 唯一放宽是
     * 本次调用前已登记且已回滚失败的 entry 走同会话重装替换（[openReinstallSlot]，旧条目幂等
     * 回滚后摘除、再走下面正常装载路径；storage 段重新激活同一份数据）。
     *
     * Lane AS 起再叠一层依赖声明（[EraMod.requires]）：装载顺序走 [planModInstall] 的稳定拓扑序
     * （无依赖声明时与声明表逐位一致），缺依赖/成环/依赖未成功启用的 mod 一律 fail-closed 拒载
     * （`active=false` + 原因进报告，走 `mod.install … failure=…` 上屏），其余无关 mod 照常。
     */
    fun install(mods: List<Pair<String, EraMod>>): List<ModRuntimeState> {
        val report = ArrayList<ModRuntimeState>(mods.size)
        // 事件名存在性闸的事件面必须在逐个 mod 的 onLoad 之前就位（作者在 onLoad 里注册事件）；
        // 缺省空集 = 不校验，与加闸前逐位一致（台架 / 无构建期 EventRegistry 的会话）。
        eventHandlers.adoptKnownEvents(knownEventKeys)
        // 依赖声明先算装载规划（顺序 + 规划期拒载）；规划只裁决顺序与拒载，装载/回滚仍走下面既有围栏。
        // 快照与规划同点取：之后出现的同 id 只可能是「批内重复」（硬失败），
        // 「本次调用前已登记且已回滚」的那一类才允许重装替换（见 [openReinstallSlot]）。
        val (plan, preexistingIds) = planInstallWithEntrySnapshot(mods, entries, storage)
        for ((packageId, mod) in plan.ordered) {
            openReinstallSlot(packageId, entries, preexistingIds, services, strings)
            // 版本门（Lane E）：契约版与数据版降级都在进生命周期之前判，只拒这一个 mod（fail-closed：
            // 数据整段留下、本会话不启用），失败原因进报告由装配侧打既有 `mod.install … active=false …` 行。
            val registrations = ArrayList<Registration>()
            val storageWasActive = storage.isActive(packageId)
            val entry: Entry
            try {
                // 依赖的运行时状态规划看不到（版本门/onLoad/onEnable 都发生在规划之后）：到依赖者真正
                // 装载时复查，依赖没启用 ⇒ 本 mod 不建 context 直接拒载入账（走围栏落报告失败项）。
                plan.dependencyFailure(packageId, entries)?.let { throw it }
                enforceVersionGate(packageId, mod, storage, logSink)
                // 必须先显式激活：apiFor 只登记「完全未知」的包，被 deactivate 过的包带着数据在容器里，
                // 不激活就直接 fail-closed —— 这一行就是「禁用 → 重新启用」的接回点。
                storage.activate(packageId)
                val context = ModContextImpl(
                    packageId = packageId,
                    snapshot = snapshot,
                    entityKernel = entityKernel,
                    pkgShortId = shortIdOf(packageId),
                    nativeMethods = nativeMethods,
                    logSink = logSink,
                    runtime = runtime,
                    intVarView = intVarView,
                    strVarView = strVarView,
                    hasIntVarView = hasIntVarView, hasStrVarView = hasStrVarView,
                    registrationSink = { registrations += it },
                    decisionPipeline = decisionPipeline,
                    storageStore = storage,
                    projector = projector,
                )
                context.durableInteractionHost = durableInteractionHost
                entry = Entry(mod, context, registrations)
                entries[packageId] = entry
                adoptSharedFaces(
                    context,
                    sharedHookPipeline,
                    sharedInteractionPipeline,
                    eventHandlers,
                    services,
                )
                context.adoptJobScheduler(sharedJobScheduler)
                // onLoad 之前的逐 context 供给就位（随机种子 / 账本 / 预算 / 设置快照 / 受控写面）：
                // 作者在初始化里就可能消费，任何一项缺席 = 漏记或拒接；先于 onLoad 即可。
                val randomSeed = randomSeedOverride ?: decisionPipeline?.currentDecisionSeed ?: 0L
                adoptPreLoadFaces(this, context, packageId, randomSeed)
                // 函数目录透传（MODDX4-B3，与 knownEventKeys → eventHandlers 同款灌缝；作者在 onLoad
                // 里就可能 register hook）：归一收在 context 写入侧（ModContextImpl.knownFunctionKeys
                // setter），缺省空集 = 不校验，与加闸前逐位一致。
                context.knownFunctionKeys = knownFunctionKeys
            } catch (t: Throwable) {
                // storage 回退 = 既有对称操作 deactivate：activate 的副作用是「建空段 + 进激活集」，
                // deactivate 只逆转后者、数据整段留下；装前已激活的包（读档 knownPackages）跳过，
                // 不把装前状态误改成禁用。此时还没有 context.logger（PackageLogger 在 ModContextImpl
                // 内装配且对本文件不可见），经 logSink 落同格式错误行，失败原因不吞不留痕。
                // 版本门的异常也走这道围栏（fail-closed：数据不删、本会话不启用）。
                if (!storageWasActive) storage.deactivate(packageId)
                logSink("[$packageId][ERROR] 装配失败（构造段），本 mod 不进入生命周期 " +
                    "cause=${t::class.java.simpleName}:${t.message}")
                report += ModRuntimeState(packageId, active = false, failure = t)
                continue
            }
            runLoadEnable(entry, mod, packageId, storageWasActive)
            report += ModRuntimeState(packageId, entry.enabled, entry.failure)
        }
        // 规划期就判死的（缺依赖 / 成环 / 依赖被拒的传递闭包）：按载体声明序并进报告，不进装载循环。
        report += plan.rejected
        return report
    }

    /**
     * 生命周期两段（onLoad → 数据版本迁移 → onEnable）与失败回滚的单一落点。
     *
     * 抽成成员函数只为把 install 留在 detekt LongMethod 阈值内，行为逐位不变：失败仍只回滚本 mod
     * （先还任务态 → 原因入条目 → 撤销注册项 → 存储段对称 deactivate）。
     */
    private fun runLoadEnable(entry: Entry, mod: EraMod, packageId: String, storageWasActive: Boolean) {
        // 迁移窗口的前半：数据版本在 onLoad 之前取一次，保证整个窗口内判断一致
        // （onLoad 里作者不得改版本，这是装配口径）。
        val storedDataVersion = storage.dataVersionsByPackage[packageId]
        var jobStateBeforeLoad: JobSchedulerSnapshot? = null
        try {
            jobStateBeforeLoad = packageJobState(packageId)
            runMeasured(timing, packageId, "lifecycle.onLoad") { mod.onLoad(entry.context) }
            // 迁移窗口：onLoad 之后、onEnable 之前。无记录 ⇒ 落 declared 不迁移；
            // 落后 ⇒ 迁移成功后落 declared；降级已在进生命周期之前拒过，这里不会出现。
            migrateForInstall(entry, storedDataVersion, mod.dataVersion, storage, timing)?.let { throw it }
            runMeasured(timing, packageId, "lifecycle.onEnable") { mod.onEnable() }
            entry.enabled = true
        } catch (t: Throwable) {
            jobStateBeforeLoad?.let { sharedJobScheduler.restorePackageState(packageId, it) }
            entry.failure = t
            entry.context.logger.error("onLoad 或 onEnable 失败，仅回滚本 mod 已注册项", t)
            revokeFailedInstall(entry, storageWasActive)
        }
    }

    /** 装载前捕获本包动态任务态，失败时原样还回共享调度器。 */
    private fun packageJobState(packageId: String): JobSchedulerSnapshot {
        val snapshot = sharedJobScheduler.snapshot()
        return JobSchedulerSnapshot(
            instances = snapshot.instances.filter { it.packageId == packageId },
            nextSeqByTemplate = snapshot.nextSeqByTemplate.filterKeys { it.startsWith("$packageId::") },
        )
    }

    /**
     * 生命周期段安装失败的统一回滚：注册面 revoke + 存储段对称 deactivate。
     *
     * deactivate 的判据与构造段 catch（install 内）相同：只有「本次 install 调用前该包非激活」
     * 才回退，装前已激活的包（读档 knownPackages）跳过、不把装前状态误改成禁用。deactivate 是
     * activate 的对称操作，只移激活位、数据整段留下 ⇒ 失败包按孤儿段保留（禁用≠删除）。
     */
    private fun revokeFailedInstall(entry: Entry, storageWasActive: Boolean) {
        revokeEntry(entry, "安装失败回滚", RevokeFaces(services, strings))
        if (!storageWasActive) storage.deactivate(entry.context.packageId)
    }

    /**
     * 关闭全部 mod：先批量撤销各自注册项，再走 onDisable → onUnload。
     * 两段回调都用异常隔离包住（失败 mod 不二次抛），shutDown 标志保证二次调用幂等。
     *
     * 收尾把全部条目的存储段统一退回非激活（[ModStorageStore.deactivate]，activate 的对称操作）：
     * 会话结束后它们不再算「在用」，侧车快照按孤儿段原样保留（数据不删）。该操作幂等
     * （只 `activePackages -= packageId`），与读档缝 [rejectEntryOnRestore] 已做过的 deactivate
     * 语义一致、重复调用是 no-op，不改变任何段的数据/版本。
     */
    fun shutdown(): List<ModRuntimeState> {
        val report = ArrayList<ModRuntimeState>(entries.size)
        for ((packageId, entry) in entries) {
            if (!entry.shutDown) {
                revokeEntry(entry, "onDisable 批量撤销", RevokeFaces(services, strings))
                try {
                    runMeasured(timing, packageId, "lifecycle.onDisable") { entry.mod.onDisable() }
                } catch (t: Throwable) {
                    entry.context.logger.warn(
                        "onDisable 异常已隔离（不二次抛）：${t::class.java.simpleName}: ${t.message}",
                    )
                }
                try {
                    runMeasured(timing, packageId, "lifecycle.onUnload") { entry.mod.onUnload() }
                } catch (t: Throwable) {
                    entry.context.logger.warn(
                        "onUnload 异常已隔离（不二次抛）：${t::class.java.simpleName}: ${t.message}",
                    )
                }
                entry.shutDown = true
            }
            report += ModRuntimeState(packageId, entry.enabled, entry.failure)
        }
        // 统一收口（循环外一次）：幂等 deactivate，重复调用不报错、不清数据。
        for (packageId in entries.keys) storage.deactivate(packageId)
        return report
    }

    /** 一次跑完四段；[duringRun] 是运行期观察钩子，便于钉住撤销前后引擎命中差异。 */
    fun runAll(
        mods: List<Pair<String, EraMod>>,
        duringRun: () -> Unit = {},
    ): List<ModRuntimeState> {
        install(mods)
        duringRun()
        return shutdown()
    }

    /** 取某 mod 的装配 context（供宿主 dispatch 接线与测试观察）。 */
    fun contextOf(packageId: String): ModContext? = entries[packageId]?.context

    /**
     * 只读：本包是否处于「活注册面」状态 —— 已装配、onEnable 成功、且未走 [shutdown]。
     *
     * 注册面读数（[registeredFacesOf]）用它给「随生命周期收口的声明面」设闸：宿主注入的
     * 设置声明快照与条目生命周期无关（未装载 / 装配失败 / 已停用都能在 [settings] 里查到），
     * 但读数契约要求「未知 / 未装载 / 已停用的 packageId 天然全零」，故由宿主按条目状态出闸。
     */
    internal fun isLivePackage(packageId: String): Boolean {
        val entry = entries[packageId] ?: return false
        return entry.enabled && !entry.shutDown
    }

    /**
     * 读档恢复后把全部已装 context 的 storage 视图重绑到重建容器 [newStore]（RT2 D 车），
     * 并在同一缝完成**数据版本核对/迁移**（Lane E）。
     *
     * 前一段的原子性口径不变：只由读档缝（ModRuntimeSession.adoptStorage）同步调用——同一 VM 线程、
     * 同一同步块内「换会话容器 + 逐 context 重绑」两步完成，中间无观察点。后一段逐 enabled mod 核对
     * [ModStorageStore.dataVersionsByPackage]：无记录 ⇒ 直接落该 mod 的 declared（新局/该包在档里没有分段，
     * 不迁移）；`stored < declared` ⇒ 调 [EraMod.onMigrate]，成功才落 declared；`stored > declared`
     * 或迁移抛异常 ⇒ **只拒该 mod**（撤销注册、active=false、canonical 失败行经 logSink 上屏、
     * 它的段退回非激活），其余 mod 与本体照常。
     *
     * 逐包委托 [ModContextImpl.rebindStorage]：孤儿段保持 fail-closed，新装 mod 登记空段，
     * 不触发激活集变更、不删除任何数据（「禁用不等于删除」在重绑后仍逐字节成立）。
     *
     * ## 时序偏离登记（诚实欠账，不是完成态）
     *
     * 规格要求「onMigrate 在 onLoad 之后、onEnable 之前」。装载期可预置容器（读档后重新装载 / 测试
     * 预置）时 [install] 严格满足；但**生产读档缝**的侧车恢复发生在 GameHost 装配时首轮
     * onLoad/onEnable 之后（本函数由 ModRuntimeSession.adoptStorage 调），onEnable 已经跑过 ——
     * 这是现有架构的顺序（还原架没有「先读档再装 mod」的相位），本缝只能保证「在 onSaveLoaded
     * 之前」。卡点与建议接法见交付报告的 TODO(verify)。
     */
    fun rebindStorage(newStore: ModStorageStore) {
        for (entry in entries.values) {
            entry.context.rebindStorage(newStore)
        }
        reconcileDataVersions(entries.values, newStore, timing, logSink, RevokeFaces(services, strings))
    }

    /** 取某 mod 的安装失败原因；未失败返回 null。 */
    fun failureOf(packageId: String): Throwable? = entries[packageId]?.failure

    /**
     * 新开一局的通知（[EraMod.onNewGame]，默认空实现）：只达**已启用且未关闭**的 mod。
     *
     * 与 [install] / [shutdown] 分开挂是刻意的：安装与卸载的 trace 有精确断言，
     * 任何游戏相位通知都不许混进去。回调抛异常按 per-mod 隔离（记录后不二次抛），
     * 不 revoke、不改 enabled/failure —— 一次通知失败不该让 mod 掉线。
     */
    fun notifyNewGame() {
        notifyEntries(entries.values, "onNewGame", timing) { it.onNewGame() }
    }

    /**
     * 读档走到末尾的通知（[EraMod.onSaveLoaded]，默认空实现）：语义与隔离口径同 [notifyNewGame]。
     *
     * 注意这是「一次读档走到这里了」，不携带成功/失败标志。
     */
    fun notifySaveLoaded() {
        notifyEntries(entries.values, "onSaveLoaded", timing) { it.onSaveLoaded() }
    }

    /** 读档迁移完成后恢复各包续体；无记录零动作，单包失败只隔离并留日志。 */
    fun resumeDurableInteractions() {
        for (entry in entries.values) {
            if (!entry.enabled || entry.shutDown) continue
            try {
                entry.context.resumeDurableInteraction(entry.mod.dataVersion)
            } catch (t: Throwable) {
                logSink("mod.durable resume pkg=${entry.context.packageId} 失败已隔离：${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * 本包版本升级的迁移通知（[EraMod.onPackageUpgrade]，ModDX2-A）：派发范围 = 只 [packageId]
     * 这一个 mod 的 entry（entries 按包过滤；包未装载/未知 ⇒ no-op，不抛），未启用（onLoad/onEnable
     * 失败、读档拒载）与已关闭的 entry 由 [notifyEntries] 的活体过滤天然不达。
     *
     * 调用点 = 装配侧读档末尾的对账（app `ModRuntimeSession`：先派发 [notifySaveLoaded]，再逐包
     * 比较 per-package 记账键 [MOD_STORAGE_PACKAGE_VERSION_KEY] 与当前 manifest 版本，不等才派发
     * 本面并回写），故生产时序上严格排在 onSaveLoaded 之后（契约判据；本面不感知对账，只原样
     * 转发 from/to——`fromVersion` = 记账键里上次版本的原文，`toVersion` = 当前 manifest version
     * 原文，真源（记账键）由装配侧读写）。
     *
     * 隔离与 timing 口径同 [notifyNewGame]（scope = `lifecycle.onPackageUpgrade`，与 `lifecycle.*`
     * 族同表，不产生动态 scope）：抛异常记录后不二次抛、不 revoke、不改 enabled/failure，绝不许
     * 混进 install/shutdown 的精确 trace。
     */
    fun notifyPackageUpgrade(packageId: String, fromVersion: String, toVersion: String) {
        val entry = entries[packageId] ?: return
        notifyEntries(listOf(entry), "onPackageUpgrade", timing) {
            it.onPackageUpgrade(fromVersion, toVersion)
        }
    }

    /**
     * 设置变更通知（[EraMod.onSettingsChanged]，默认空实现）：派发范围 = **只 [packageId] 这一个
     * mod** 的 entry（entries 按包过滤；包未装载/未知 ⇒ no-op，不抛），不是全体扇出 ——
     * 设置键按包声明，别的包收到无关键只会空跑。
     *
     * `changedKeys` 的键名按包过滤在宿主灌入侧（app 设置页写入点）完成，本面只原样转发；
     * 调用时机 = 玩家写入成功、设置快照已更新之后（回调里 `context.settings` 读到的已是新值）。
     * 隔离与 timing 口径同 [notifyNewGame]（scope = `lifecycle.onSettingsChanged`，与
     * `lifecycle.*` 族同表，不产生动态 scope）：抛异常记录后不二次抛、不 revoke、不改
     * enabled/failure，绝不许混进 install/shutdown 的精确 trace。
     */
    fun notifySettingsChanged(packageId: String, changedKeys: List<String>) {
        val entry = entries[packageId] ?: return
        notifyEntries(listOf(entry), "onSettingsChanged", timing) { it.onSettingsChanged(changedKeys) }
    }

    /**
     * 按包设置快照**重注缝**（ModDX-A2 组长接线）：玩家改设置后，app 侧在派发
     * [notifySettingsChanged] **之前**调本面——先让本包 entry 的 `context.settings` 换成
     * 盘上新快照（[ModContextImpl.adoptSettings]），再派发回调，兑现「回调里
     * `context.settings` 读到的已是新值」（[EraMod.onSettingsChanged] KDoc 判据）。
     *
     * 双写：宿主 [settings] 表同步更新（重新启用/下一局 install 的 adopt 用同号新值，不回旧）；
     * 包未装载（entries 无此包）只更新表、不派发（与 notifySettingsChanged 的按包过滤同口径）。
     * 快照由调用方重读盘上值构造（app 侧 `ModSettingsStore.snapshotsFor`），core 不读文件。
     */
    fun rebindSettings(packageId: String, snapshot: ModSettingsSnapshot) {
        settings = settings + (packageId to snapshot)
        entries[packageId]?.let { it.context.adoptSettings(snapshot) }
    }

    /**
     * 只读读数缝（MODDX2-G1）：本会话为 [packageId] 登记的设置项**声明**（作者面
     * [SettingDecl] 形态，声明序 = manifest `settings` 段原文序）。
     *
     * 单一真源 = [settings] 登记表里该包 [ModSettingsSnapshot] 的声明清单（app 装配侧在
     * [install] 之前从 mods/rt2 的 manifest `settings` 段解出注入，[install] 时经
     * `adoptPerModState` 绑进各 context；玩家改值后由 [rebindSettings] 同号新值）。本面只做
     * **投影**（经 [ModDeclarationAdapter.toSettingDecl] 反向翻译回作者面形态），不另建第二张
     * 登记表、不缓存、与 [ModSettingsSnapshot] 的值读取共用同一份声明真源。
     *
     * **包未装载 / 未登记 ⇒ 空表不抛**（查询空集语义）。与作者面 `context.settings` 的
     * fail-closed UOE（`FailClosedSettingsApi`）是**有意区分**的两套口径，理由：
     * 作者面抛 UOE 是因为「宿主没注入快照」属于接线错误，若静默空集，作者会误读成
     * 「我没声明任何设置项」，失真必须上抛（见 `FailClosedSettingsApi` KDoc）；而本面是
     * 宿主自照面，调用方（设置页桥接 / 诊断）问的是「宿主给这个包登记了什么声明」——
     * 未登记的包空集就是这个问题的如实答案，查询面不制造状态（同 `uiElementsOf` 的
     * 「未安装包返回空表」口径）。声明登记与条目生命周期无关：装载失败 / 版本门拒载的包，
     * 其声明仍在登记表里照常可查（与 [isLivePackage] KDoc 对 [settings] 表的描述一致），
     * 不按 entry 状态出闸、不隐藏「登记了但没装起来」的事实。
     *
     * 返回新 List（元素为反向投影新建的不可变数据类），调用方可安全持有、跨线程读。
     */
    fun declaredSettings(packageId: String): List<SettingDecl> =
        settings[packageId]?.declarations?.map { ModDeclarationAdapter.toSettingDecl(it) } ?: emptyList()

    /**
     * 回合边界通知（[EraMod.onTurnBoundary]，默认空实现）：只达**已启用且未关闭**的 mod。
     *
     * 触发点是宿主回合边界安全点（`GameHost.kt:896` 的 `onTurnBoundary()`），与该边界的
     * 相位派发同拍；时间账本 scope 固定 `lifecycle.onTurnBoundary`（与 [notifyNewGame] 的
     * `lifecycle.*` 族同表，不产生动态 scope）。隔离口径同 [notifyNewGame]：抛异常只记录
     * 不 revoke，且绝不许混进 install/shutdown 的精确 trace。
     */
    fun notifyTurnBoundary() {
        notifyEntries(entries.values, "onTurnBoundary", timing) { it.onTurnBoundary() }
    }

    /**
     * 换掉所有 mod 的随机派生种子（§21.6 v1 确定性口径：读档采纳档内决策种子后**从头重放**）。
     *
     * 为什么在宿主层做：种子是会话级量（决策种子），每个 context 只是按 packageId 派生自己的
     * 一条流；读档缝必须一次换齐，否则同档重载后不同 mod 的随机序会错位到不同纪元。
     *
     * 同时记成宿主级覆盖值：之后才 [install] 的 mod 也必须用同一颗种子，否则同一会话里
     * 早装与晚装的 mod 会落在不同纪元（读档后动态启用 mod 就会踩到）。
     */
    fun adoptRandomSeed(decisionSeed: Long) {
        randomSeedOverride = decisionSeed
        fanoutRandomSeed(entries.values, decisionSeed)
    }

    /**
     * 宿主按包日志回看聚合缝（MODDX4-G8）：本会话里 [packageId] 这包最近发出的日志行快照
     * （旧到新、时序保持、只读副本），底是 G2 落的每包有界环——装配会话内各包
     * [ModContextImpl] 各持一份 [ModLogBuffer]，本缝按包归属找到该包 context 后委托
     * [ModContextImpl.recentLogLines]（internal 读数缝，G2 的设计读者就是宿主诊断面）取
     * 只读副本；本缝只做按包归属过滤，不加锁、不再复制缓冲语义（环自身实例锁 + 快照，
     * 口径照 G2 既有约定）。记账口径（哪些行进环、哪些被抑制）见 [MOD_LOG_BUFFER_CAPACITY]。
     *
     * 为什么是类成员而非文件级 extension：本文件的**文件级**函数数已在阈值上沿（10 个，
     * 文件级阈值 11，文件级 ID 在仓内从不进 baseline——既有各文件一律挪函数保绿），
     * 再加一个就顶爆文件级 `TooManyFunctions`；而类级 `TooManyFunctions` 已是既有存量
     * （baseline 冻结项），成员计数不再产生任何 detekt 增量红。注册面读数先例
     * `registeredFacesOf`（ModRegisteredFaces.kt 的文件级 extension）是同族先例——本缝与它
     * 同口径：按 packageId 从活装配会话现读，跨线程可见性照读数家族既有取舍
     * （[installedContexts] 的活引用 + 环自身的实例锁）。
     *
     * 为什么 public：app 读出面（设置页「已安装的 Mod」卡的每包日志回看入口）是生产消费方，
     * G2 的读数缝是 internal（core 内），本缝是它对宿主面（app 模块）的公共出口——
     * 宿主诊断面、非 mod 作者面，`ModApi.kt` 零改动、零 VERSION 影响。
     *
     * 查询空集语义（与 [declaredSettings] 同口径）：包未装载 / 未知 / 无日志 ⇒ 空表不抛——
     * 「没有」就是如实答案，查询面不制造状态。装载失败的包（onLoad/onEnable 抛异常被回滚的
     * entry 仍在 entries 里）其已发出的日志照样可回看，那正是报障现场；构造段失败（context
     * 未建）的包没有环，自然空表。异常降级照 `registeredFacesOf` 家族口径：查询路径绝不抛，
     * 读数失败经 [readoutWarningSink] WARN 一条后回空表。会话销毁后的读数由 app 侧通道按
     * 「活会话」出闸（宿主引用随会话收口置空），core 层的环是只读历史快照、无生命周期出闸，
     * 与本缝口径一致。
     */
    fun recentLogs(packageId: String): List<String> {
        if (packageId.isBlank()) return emptyList()
        return runCatching {
            installedContexts.firstOrNull { it.packageId == packageId }?.recentLogLines().orEmpty()
        }.getOrElse { error ->
            readoutWarningSink(
                "mod 日志读数失败（packageId=$packageId）：${error.javaClass.simpleName}: ${error.message}",
            )
            emptyList()
        }
    }
}

/** 单 mod 的终局状态：是否启用成功、失败原因（进交付报告，不吞异常）。 */
data class ModRuntimeState(
    val packageId: String,
    val active: Boolean,
    val failure: Throwable?,
)

/**
 * 会话级撤销面：服务发布表 + 自带三语文案表。
 *
 * 绑成一个值传：这两者必须同进同退（撤注册项的同时撤文案、撤服务发布），而文件级的
 * `revokeEntry` / `rejectEntryOnRestore` / `reconcileDataVersions` 已经把参数表顶到
 * detekt `LongParameterList` 阈值上沿，再各加一个 `strings` 形参就过不了闸。
 */
internal class RevokeFaces(
    val services: ModServiceRegistryImpl,
    val strings: ModStringsRegistry,
)

/**
 * 撤销单个 mod 的全部注册项：委托 [revokeRegistrations] 逐个撤销，单个抛错不中断其余但要记录。
 * 只处理传入的 entry，绝不触碰其它 mod（双 mod 失败隔离的回滚面）。
 *
 * 放文件级而非宿主类内：宿主类函数数已被 detekt `TooManyFunctions` 压到阈值上沿（10 个），
 * 这类无第二份状态的单 entry 操作挪出来才不撑爆；实现只读 entry 自身字段，不摸宿主其它状态。
 *
 * [faces] 的文案半是「本包自带文案表的清空面」：`onLoad` 里声明的文案必须随卸载/失败回滚一起消失
 * （否则重新装载前的旧键还能被渲染侧解析到，与注册项撤销不同步）。
 */
internal fun revokeEntry(
    entry: ModRuntimeHost.Entry,
    reason: String,
    faces: RevokeFaces,
) {
    // 契约边界 4：撤销注册项与撤销服务发布是同一件事的两半（卸载/失败回滚后不允许再被查到）。
    faces.services.revokePackage(entry.context.packageId)
    // 渲染面同步：撤掉本包文案，避免「贡献项没了但旧文案仍出现在别处」的错位。
    faces.strings.clear(entry.context.packageId)
    val pending = entry.registrations.toList()
    entry.registrations.clear()
    revokeRegistrations(pending) { t ->
        entry.context.logger.warn(
            "$reason：单个 unregister 抛错已记录并跳过，继续撤销其余项" +
                "（${t::class.java.simpleName}: ${t.message}）",
        )
    }
}

/**
 * 相位通知的公共扇出（文件级私有：宿主类自身函数数已被 detekt 阈值压到 11，这类纯循环
 * 放外面才不撑爆 `TooManyFunctions`，同时不引入任何第二份状态）。
 *
 * 隔离口径：只发已启用且未关闭的 mod；单个抛异常记录后继续，不 revoke、不改 enabled/failure。
 */
private fun notifyEntries(
    entries: Collection<ModRuntimeHost.Entry>,
    tag: String,
    ledger: ModTimingLedger?,
    call: (EraMod) -> Unit,
) {
    for (entry in entries) {
        if (!entry.enabled || entry.shutDown) continue
        // mod 的相位回调可能抛任何 Throwable（含 VM 抛出的 Error 子类），隔离口径就是全兜；
        // 用 runCatching 而非 catch(Throwable)，既保住全兜语义又不必引入 detekt 抑制。
        runCatching { runMeasured(ledger, entry.context.packageId, "lifecycle.$tag") { call(entry.mod) } }
            .onFailure { t ->
                entry.context.logger.warn(
                    "$tag 异常已隔离（不二次抛）：${t::class.java.simpleName}: ${t.message}",
                )
            }
    }
}

/**
 * 有账本时测量、无账本时零开销直调（文件级私有：宿主类内函数数已到 detekt 阈值上沿，
 * 这类无状态包装放外面才不撑爆 `TooManyFunctions`）。
 *
 * 旁路零点：`ledger == null` 时不取时钟、不建对象、不进 HashMap ⇒ golden 与关态零日志逐位不变。
 */
private fun runMeasured(
    ledger: ModTimingLedger?,
    packageId: String,
    scope: String,
    block: () -> Unit,
) {
    if (ledger == null) block() else ledger.measureValue(packageId, scope, block)
}

/** 随机派生种子的公共扇出：已关闭的 mod 不再跟着换（与 [notifyEntries] 同口径）。 */
private fun fanoutRandomSeed(entries: Collection<ModRuntimeHost.Entry>, decisionSeed: Long) {
    for (entry in entries) {
        if (entry.shutDown) continue
        entry.context.adoptRandomSeed(decisionSeed)
    }
}

/**
 * 逐个撤销注册项：单个 unregister 抛错只经 [onError] 记录，不中断其余撤销。
 *
 * 纯函数式工具（不持有任何状态），故放在宿主类外：宿主类自身的函数数已被 detekt 的
 * `TooManyFunctions` 压到阈值上沿，这类无状态循环不该再占一个名额。
 * 用 `runCatching` 全兜 Throwable，与「注册项回滚不能因单个坏项中断」的既有语义一致。
 */
fun revokeRegistrations(registrations: List<Registration>, onError: (Throwable) -> Unit) {
    for (registration in registrations) {
        runCatching { registration.unregister() }.onFailure(onError)
    }
}

/**
 * 版本门（Lane E）：先判作者面契约版（mod 要求比本体内核新 ⇒ 拒），再判数据版降级
 * （档内记录版本高于 mod 声明 ⇒ 拒，避免旧版 mod 写坏新版数据）。放行则直接返回。
 *
 * 拒绝时的处置：落非 canonical 错误行（canonical 的 `mod.install … active=false failure=…`
 * 行由装配侧按 [ModRuntimeState] 报告打，这里不重复），把该包段退回非激活，然后抛出交给
 * install 的构造段围栏统一记报告。fail-closed：即使包在容器里本来是激活态（预置/读档容器），
 * 被拒的 mod 也不许留激活段，否则它的数据会被后续写出当成「在用」。
 *
 * 文件级私有：宿主类内函数数已抵 detekt `TooManyFunctions` 阈值，这类无状态判定放外面，
 * 与 [notifyEntries] / [runMeasured] 同一取舍。
 */
private fun enforceVersionGate(
    packageId: String,
    mod: EraMod,
    storage: ModStorageStore,
    logSink: (String) -> Unit,
) {
    val failure = when {
        mod.apiVersion > ModApi.VERSION ->
            ModApiVersionMismatchException(packageId, mod.apiVersion, ModApi.VERSION)
        else -> {
            val stored = storage.dataVersionsByPackage[packageId]
            if (stored != null && stored > mod.dataVersion) {
                ModDataVersionDowngradeException(packageId, stored, mod.dataVersion)
            } else {
                return
            }
        }
    }
    logSink(
        "[$packageId][ERROR] 版本协商失败，本 mod 不进入生命周期 " +
            "cause=${failure::class.java.simpleName}:${failure.message}",
    )
    storage.deactivate(packageId)
    throw failure
}
/**
 * 迁移窗口的调用器：成功返回 null，失败返回 [ModDataMigrationException]（装载期原样抛给既有
 * per-mod 隔离 catch，读档缝据此拒载）。
 *
 * 用 `runCatching` 全兜 Throwable：mod 回调可能抛任何 Throwable，隔离口径就是全兜；例外零开销
 * 口径与 [runMeasured] 相同（有账本记账、无账本不取时钟）。
 */
private fun runMigration(
    entry: ModRuntimeHost.Entry,
    fromDataVersion: Int,
    toDataVersion: Int,
    ledger: ModTimingLedger?,
): Throwable? = runCatching {
    runMeasured(ledger, entry.context.packageId, "lifecycle.onMigrate") {
        entry.mod.onMigrate(fromDataVersion, toDataVersion)
    }
}.exceptionOrNull()?.let {
    ModDataMigrationException(entry.context.packageId, fromDataVersion, toDataVersion, it)
}

/**
 * 装载期数据版本门与迁移（onLoad 之后、onEnable 之前的窗口）：返回失败原因，null = 窗口可以放行。
 *
 * - 无记录（新局 / 无侧车 / 档里没有本包分段）⇒ 直接落 declared，不迁移；
 * - 记录 == declared ⇒ 什么都不做；
 * - 记录 < declared ⇒ 调 `onMigrate`，成功后落 declared；
 * - 记录 > declared ⇒ 降级，已在进生命周期之前拒过，这里按 fail-closed 也返回失败。
 *
 * 用 when 表达式而非多 return，规避 detekt 的 ReturnCount 上限；迁移异常已由 [runMigration]
 * 包成 [ModDataMigrationException]。
 */
private fun migrateForInstall(
    entry: ModRuntimeHost.Entry,
    storedDataVersion: Int?,
    declaredDataVersion: Int,
    storage: ModStorageStore,
    ledger: ModTimingLedger?,
): Throwable? = when {
    storedDataVersion == null -> {
        storage.setDataVersion(entry.context.packageId, declaredDataVersion)
        null
    }
    storedDataVersion > declaredDataVersion ->
        ModDataVersionDowngradeException(entry.context.packageId, storedDataVersion, declaredDataVersion)
    storedDataVersion < declaredDataVersion ->
        runMigration(entry, storedDataVersion, declaredDataVersion, ledger) ?: run {
            storage.setDataVersion(entry.context.packageId, declaredDataVersion)
            null
        }
    else -> null
}

/**
 * 读档缝的数据版本核对/迁移（Lane E）。逐「已启用且未关闭」的 mod：
 * 无记录 ⇒ 落 declared（不迁移）；落后 ⇒ 迁移成功后落 declared；降级/迁移失败 ⇒ 只拒该 mod。
 *
 * 单个 mod 的任何失败都不影响其余 mod（per-mod 隔离口径与 install/shutdown 一致）。
 */
private fun reconcileDataVersions(
    entries: Collection<ModRuntimeHost.Entry>,
    store: ModStorageStore,
    ledger: ModTimingLedger?,
    logSink: (String) -> Unit,
    faces: RevokeFaces,
) {
    for (entry in entries) {
        if (!entry.enabled || entry.shutDown) continue
        val packageId = entry.context.packageId
        val declared = entry.mod.dataVersion
        val stored = store.dataVersionsByPackage[packageId]
        when {
            stored == null -> store.setDataVersion(packageId, declared)
            stored > declared -> {
                val failure = ModDataVersionDowngradeException(packageId, stored, declared)
                rejectEntryOnRestore(entry, failure, store, logSink, faces)
            }
            stored < declared -> {
                val failure = runMigration(entry, stored, declared, ledger)
                if (failure == null) {
                    store.setDataVersion(packageId, declared)
                } else {
                    rejectEntryOnRestore(entry, failure, store, logSink, faces)
                }
            }
        }
    }
}

/**
 * 读档缝版本门的拒载：撤销该 mod 全部注册项、标失败/停用、把它的段退回非激活（数据整段留下，
 * 后续按孤儿段原样写回 —— 不删数据、不静默降级），并打 canonical 失败行。
 *
 * 生产读档缝没有 install 报告可复用，这行日志就是「原因上屏」的唯一通道；格式必须与装配侧
 * `mod.install pkg=… active=false failure=<简单类名>: <消息>` 逐字一致（诊断面按它解析）。
 */
private fun rejectEntryOnRestore(
    entry: ModRuntimeHost.Entry,
    failure: Throwable,
    store: ModStorageStore,
    logSink: (String) -> Unit,
    faces: RevokeFaces,
) {
    entry.failure = failure
    entry.enabled = false
    // 读档拒载同样是「本包不再启用」：服务发布与自带文案一并撤销（渲染侧不许再解析到停用包的键）。
    faces.services.revokePackage(entry.context.packageId)
    faces.strings.clear(entry.context.packageId)
    val pending = entry.registrations.toList()
    entry.registrations.clear()
    revokeRegistrations(pending) { t ->
        entry.context.logger.warn(
            "读档版本拒载回滚：单个 unregister 抛错已记录并跳过（${t::class.java.simpleName}: ${t.message}）",
        )
    }
    store.deactivate(entry.context.packageId)
    logSink(
        "mod.install pkg=${entry.context.packageId} active=false failure=" +
            "${failure::class.java.simpleName}: ${failure.message}",
    )
}
