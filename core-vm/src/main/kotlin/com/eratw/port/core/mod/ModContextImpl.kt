package com.eratw.port.core.mod

import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.entity.KernelQueryApi
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.ActionApi
import com.eratw.port.modapi.ActionConstraint
import com.eratw.port.modapi.ActionHandler
import com.eratw.port.modapi.ActionProvider
import com.eratw.port.modapi.DurableStory
import com.eratw.port.modapi.AudioApi
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EventApi
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.FunctionApi
import com.eratw.port.modapi.GameStateApi
import com.eratw.port.modapi.HookApi
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookRegistration
import com.eratw.port.modapi.InteractionApi
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.JobApi
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModLogger
import com.eratw.port.modapi.ModResourceApi
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.PhaseApi
import com.eratw.port.modapi.RandomApi
import com.eratw.port.modapi.PhaseHandler
import com.eratw.port.modapi.QueryApi
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RelationshipApi
import com.eratw.port.modapi.RosterProjectionApi
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.SettingsApi
import com.eratw.port.modapi.StorageApi
import com.eratw.port.modapi.StringsApi
import com.eratw.port.modapi.TimingApi
import com.eratw.port.modapi.TimingSample
import com.eratw.port.modapi.UiApi
import com.eratw.port.modapi.modUiImage
import java.util.Locale
/**
 * [ModContext] 的生产装配对象（RT2 波 C 车 C1，era-arch-v61u.md §12.1 缺的半边）。
 *
 * 把装载期冻结的 [DeclarationSnapshot]、本 mod 的 [packageId] 与宿主注入依赖组装成一个
 * mod 可见的服务入口：四个 §20 引擎经既有 factory 从冻结声明直接构造、再经
 * `ModExtensionApiAdapters.kt` 的四个适配器注入本包身份；实体/关系/函数走既有生产适配器。
 * 本车只做接线装配，不新建引擎、不改既有引擎判定、不碰 `EventRegistry`。
 *
 * 六个没有现成实现的 facade 全部落在本文件内、全部是实例级对象：
 * [logger]（可注入 sink、按包归属）、[runtime]（由构造参数注入的宿主调度器决定，
 * 真实主执行调度器接线属组长）、[events]（未装接线时显式 fail-closed，见 [FailClosedEventApi]；
 * 宿主装载时经 [adoptEventRegistry] 换成全链共享的 [EventDispatchRegistry]）、
 * [storage]（可重绑的委托视图，初值绑定 [storageStore] 里本包那一段；未注入容器时是
 * 「一 context 一容器」的等价缺省；读档恢复后经 [rebindStorage] 重绑到重建容器，公开
 * [StorageApi] 形状一字不变——重绑对 mod 透明）、
 * [gameState]（构造期注入只读视图，
 * 未注入时是干净缺省而非真实读数）、[ui]（实例级登记表）。
 *
 * 构造期即 fail-closed：packageId 为空或不在冻结快照的 packageIds 里当场抛，不返回半装配
 * context。所有可变状态挂本实例，无任何静态可变全局（无具名单例、无伴生可变成员、无顶层可变变量）。
 *
 * K3-B（RT2 波 G 车 G1）另装配两个 §21 facade：[actions] 委托注入的 [DecisionPipeline] 的包作用域
 * `actionApi(packageId)`，并把三个注册入口返回的 [Registration] 全部经 [track] 交给宿主的 per-mod
 * 收集器（onDisable 批量撤销不得绕过）；未注入 decisionPipeline 时是显式 fail-closed 桩
 * （见 [FailClosedActionApi]），不造第二套仲裁器、不返回静默 no-op 注册。[query] 恒可装配，
 * wrap 既有必填的 entityKernel（KernelQueryApi）。
 *
 * RT2 车次表 E 车另装配 [roster]：注入 [ICharaSlotProjector] 时委托
 * [KernelRosterProjectionApi] 转发（不自建映射、不重写判定），未注入时是显式 fail-closed 桩。
 * 真投射器的生产构造点与 `VariableStore.charaIndexResolver` 注入属 F 车，本车不接。
 *
 * 真实 `IEntityKernel` / `NativeMethodRegistry` 的宿主注入点、pkgShortId 分配、VM dispatch 挂钩、
 * 游戏时钟推进、任务态落 `.rt2` 一律属「需组长接线」，本波用构造参数注入加测试 fake。
 */
class ModContextImpl(
    override val packageId: String,
    snapshot: DeclarationSnapshot,
    entityKernel: IEntityKernel,
    pkgShortId: Int,
    nativeMethods: NativeMethodRegistry,
    logSink: (String) -> Unit,
    override val runtime: RuntimeScheduler,
    intVarView: (String) -> Long = { 0L },
    strVarView: (String) -> String = { "" },
    /**
     * 存在性探测供给（DX-C）：按名答「本视图可读吗」，与 [intVarView]/[strVarView] 同源灌入。
     * 缺省 null = 台架 / 未接线形态 ⇒ [GameStateApi.hasIntVar]/[GameStateApi.hasStrVar]
     * 恒 false（查询面空集口径：不抛、不伪装成有数据，与「台架空集不校验」同形）。
     */
    hasIntVarView: ((String) -> Boolean)? = null,
    hasStrVarView: ((String) -> Boolean)? = null,
    private val registrationSink: (Registration) -> Unit = {},
    private val decisionPipeline: DecisionPipeline? = null,
    storageStore: ModStorageStore = ModStorageStore(),
    /**
     * 真角色槽投射器（RT2 车次表 E 车）。缺省 null = F 车生产构造点尚未接线，
     * 此时 [roster] 是显式 fail-closed 桩；既有构造点零改动、生命周期零行为差。
     */
    projector: ICharaSlotProjector? = null,
) : ModContext {

    init {
        require(packageId.isNotBlank()) { "packageId 不能为空（构造期 fail-closed）" }
        require(packageId in snapshot.packageIds) {
            "声明快照不含 packageId=$packageId（构造期不一致，fail-closed，不返回半装配 ModContext）"
        }
    }

    // 装配产物：四引擎由冻结声明快照经既有 factory 直接构造，本车不新建引擎、不改判定。
    // 暴露为可读装配产物，供宿主 dispatch 接线与测试据此验证注册命中与否；真实接线属组长。
    // hookPipeline 是 var：宿主装配时经 [adoptHookPipeline] 换成**全链共享**的那一份（§20.1 多 Mod
    // 非替换式挂钩的确定性序要求 priority 全局排序，各自一份 pipeline 会把序按 mod 分组拆散）。
    var hookPipeline: HookPipeline = HookPipeline.of(snapshot.hookDeclarations)
        private set
    val phaseRegistry = PhaseRegistry.of(snapshot.phaseDeclarations)
    var jobScheduler = JobScheduler.of(
        snapshot.jobDeclarations,
        snapshot = null,
        templates = snapshot.jobTemplateDeclarations,
    )
        internal set
    // interactionPipeline 是 var：宿主装配时经 [adoptInteractionPipeline] 换成**全链共享**的那一份
    // （§21.4 交互阶段执行序是「阶段 → packageRank（声明链接序）→ packageId」，各自一份会把序
    // 按 mod 分组拆散）。页边界生产触发点在宿主侧，`run(...)` 只走这条共享管线。
    var interactionPipeline: InteractionPipeline = InteractionPipeline.of(snapshot.interactionHookDeclarations)
        private set

    private var hookAdapter =
        HookApiAdapter(hookPipeline, packageId, knownKeysProvider = { knownFunctionKeys })
    private val phaseAdapter = PhaseApiAdapter(phaseRegistry, packageId)
    internal var jobAdapter = JobApiAdapter(jobScheduler, packageId)
    private var interactionAdapter = InteractionApiAdapter(interactionPipeline, packageId)
    internal var durableInteractionHost: DurableInteractionHost? = null
    internal var durableRuntime: DurableInteractionRuntime? = null
    internal var activeStorageStore = storageStore
    private val kernelApi = KernelEntityApi(entityKernel, pkgShortId)

    // internal 而非 private：宿主只读查询（ModUiHostQuery.kt 的文件级 extension）要读本 context 的
    // 界面贡献快照。用属性暴露而不是加 adopt/查询成员函数 —— 本类成员函数已抵 detekt
    // TooManyFunctions 阈值（与 [servicesAdapter]/[resourceApi] 同一口径）。
    // internal 而非 private：宿主只读查询（ModUiHostQuery.kt 的文件级 extension）要读本 context 的
    // 界面贡献快照。用属性暴露而不是加 adopt/查询成员函数 —— 本类成员函数已抵 detekt
    // TooManyFunctions 阈值（与 [servicesAdapter]/[resourceApi] 同一口径）。
    // 构造灌入 logSink/packageId：可见性谓词抛异常的 WARN 落宿主既有日志面（DX-F1），不另开通道。
    internal val uiRegistry = InstanceUiRegistry(logSink, packageId)

    // 注册项登记：只把适配器返回的 Registration 交给宿主的 per-mod 收集器，原样返回。
    // 包装层不含任何注册判定，判定完全委托既有适配器实例（不重写适配器）。
    private val track: (Registration) -> Registration = { registration ->
        registrationSink(registration)
        registration
    }

    // 每包最近日志环（MODDX2 二期 G2）：实例级状态挂在 context 上（禁静态可变全局），只记
    // PackageLogger.emit 已发出的行；宿主读数缝 = ModContextFaces.kt 的文件级 extension
    // recentLogLines（本类成员函数数已抵 detekt TooManyFunctions 阈值，不加成员函数）。
    internal val logBuffer = ModLogBuffer()
    override val logger: ModLogger = PackageLogger(packageId, logSink, logBuffer)
    override val entities: EntityApi = kernelApi
    override val relationships: RelationshipApi = kernelApi
    // E 车：投射公开面。注入真投射器则转发，未注入则四入口显式 fail-closed（不半装）。
    override val roster: RosterProjectionApi = KernelRosterProjectionApi(projector, packageId)
    // 事件面（§6.1）：宿主装配时经 [adoptEventRegistry] 换成**全链共享**注册表；未注入时是显式
    // fail-closed。注册项与其它面同口径经 [track] 交给宿主收集器 —— 绕过 track = shutdown / 失败
    // 回滚不撤销，onDisable 之后处理器仍在共享表里命中。
    override val events: EventApi = object : EventApi {
        override fun register(
            eventKey: String,
            bucket: EventBucket,
            handler: EventHandler,
        ): Registration = track((eventAdapter ?: failClosedEvents).register(eventKey, bucket, handler))

        /** 自定义事件话题声明（A1 车）：未装配时走 [failClosedEvents] 的接口缺省 UOE（显式 fail-closed）。 */
        override fun declare(eventKey: String) {
            (eventAdapter ?: failClosedEvents).declare(eventKey)
        }

        /** 主动触发（AE 车）：未装配时走 [failClosedEvents] 的显式 fail-closed（抛 UOE）。 */
        override fun trigger(eventKey: String) {
            (eventAdapter ?: failClosedEvents).trigger(eventKey)
        }

        /**
         * 只读查询：未装配时走 [failClosedEvents] 的空集口径（false），与接口缺省 UOE
         * 不同——生产 face 的「不知道」必须答否，不能让作者 catch 空集。
         */
        override fun exists(eventKey: String): Boolean =
            (eventAdapter ?: failClosedEvents).exists(eventKey)

        /** 只读快照：未装配时走 [failClosedEvents] 的空表（同 [exists] 的 fail-safe 口径）。 */
        override fun knownEventKeys(): List<String> =
            (eventAdapter ?: failClosedEvents).knownEventKeys()
    }


    /**
     * 已知可挂 hook 目标键集合（MODDX4-B3 发现面；2026-11-26 B4 改判为「函数 ∪ 事件」并集）：
     * 非空时 [hooks] 的 `register` 对**目录外目标键**当场抛 [IllegalArgumentException]（附最接近
     * 候选），空集（缺省）不校验。
     *
     * 真源 = 链接期 `SymbolCatalog` 的 `bindings.keys ∪ events.keys`（函数键与语料事件键同表；
     * 并集理由 = EventApi / EventDispatchRegistry 一贯建议「拦截/替换事件用 hooks PREFIX +
     * SkipOriginal」，dispatchEvent 缝也确实按事件名查槽——灌源若只有函数键，该建议在生产
     * 装配下被拼错闸拦死）。装配侧（app `ModRuntimeAssembly.attach` 的 hook 目标键注入行）取
     * 并集、宿主 [ModRuntimeHost.install] 在每个 context 的 onLoad 之前透传进来（链见
     * [ModRuntimeHost.knownFunctionKeys]）。写入侧逐键归一（trim + 大写 ROOT，与
     * `EventDispatchRegistry.adoptKnownEvents` 同位同口径），所以灌入未归一的键也安全。
     * 事件键过拼错闸 ≠ 能绑上：是否真有槽仍由 [HookPipeline.bind] 的「槽未声明」闸把守
     * （Manifest.hooks 未声明该事件键 = IAE，fail-closed 不变）。
     * **不进构造签名**：与 [gameStateWrite] / [servicesAdapter] 同口径，改装配不改构造。
     * 缺省空集 = 台架 / 单测 / 无链接目录的会话口径（与 events 的 `knownEventKeys` 空集口径同形）。
     */
    internal var knownFunctionKeys: Set<String> = emptySet()
        set(value) {
            field = value.mapTo(LinkedHashSet(value.size)) { it.trim().uppercase(Locale.ROOT) }
        }

    override val hooks: HookApi = object : HookApi {
        override fun register(targetKey: String, phase: HookPhase, handler: HookHandler): Registration {
            // 拼错闸（MODDX4-B3）：函数目录非空时，bind 到目录外的键当场拒绝并给最近候选
            //（口径照 EventDispatchRegistry.register 的 known 闸逐字同构；空集 = 缺省不校验）。
            // 闸只拦「目录里根本没有这个函数」的拼错；目录内但槽未声明的键仍走 HookPipeline.bind
            // 的既有「槽未声明」IAE —— 那是「漏声明」不是「拼错」，文案与候选都不适用。
            val known = this@ModContextImpl.knownFunctionKeys
            val key = canonicalFunctionKey(targetKey)
            require(known.isEmpty() || key in known) {
                unknownHookTargetMessage(packageId, targetKey, key, known)
            }
            return track(hookAdapter.register(targetKey, phase, handler))
        }

        /**
         * 只读投影：委托 [hookAdapter]（与 register 同一条槽表；[adoptHookPipeline] 换共享引擎后
         * 自动跟随）。本面没有「未装配」分支：hookPipeline 构造期就由冻结快照建成（不同于 events
         * 的 adopt 缝），未装配 context 也答真实槽表（快照无 hook 声明时是空表）——不抛、不猜，
         * 与接口缺省 UOE「宿主未接线」口径有意的两路（先例照 [EventApi.knownEventKeys]）。
         */
        override fun registrations(): List<HookRegistration> = hookAdapter.registrations()

        /**
         * 只读快照：委托 [knownFunctionKeys]（与 [register] 拼错闸同一份集合，同源不二记）；
         * 未灌源 = 空集（fail-safe 不猜，先例照 [EventApi.knownEventKeys]）。字典序稳定排序的
         * 独立副本，调用方随便增删不影响引擎。
         */
        override fun knownFunctionKeys(): Set<String> = this@ModContextImpl.knownFunctionKeys.toSortedSet()
    }

    override val phases: PhaseApi = object : PhaseApi {
        override fun subscribe(phase: String, handler: PhaseHandler): Registration =
            track(phaseAdapter.subscribe(phase, handler))
    }

    // 动态任务模板（§21.1）：五个入口全部委托已注入 packageId 的适配器；bindTemplate 返回的
    // Registration 与静态槽/actions 同口径经 track 交给宿主收集器（onDisable 不留残项）。
    override val jobs: JobApi = object : JobApi {
        override fun bind(jobId: String, handler: JobHandler): Registration =
            track(jobAdapter.bind(jobId, handler))

        override fun bindTemplate(templateKey: String, handler: JobHandler): Registration =
            track(jobAdapter.bindTemplate(templateKey, handler))

        override fun scheduleOnce(templateKey: String, atDay: Long, payloadRef: EntityId?): String =
            jobAdapter.scheduleOnce(templateKey, atDay, payloadRef)

        override fun cancel(instanceKey: String): Boolean =
            jobAdapter.cancel(instanceKey)

        override fun reschedule(instanceKey: String, atDay: Long): Boolean =
            jobAdapter.reschedule(instanceKey, atDay)
    }

    override val interactions: InteractionApi = object : InteractionApi {
        override fun registerHook(phase: InteractionPhase, handler: InteractionHandler): Registration =
            track(interactionAdapter.registerHook(phase, handler))

        override fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult =
            interactionAdapter.run(context, main)

        override fun runDurable(context: InteractionContext, story: DurableStory): InteractionRunResult =
            runDurableInteraction(context, story)
    }

    // 决策/仲裁面（§21.3）：注入 DecisionPipeline 时委托其包作用域 facade，三个注册入口返回的
    // Registration 一律经 track 交给宿主收集器——绕过 track = onDisable 留残项（G1 用例 4 必红）。
    // 未注入时显式 fail-closed，不造第二套仲裁器、不返回静默 no-op 注册（§12.1）。
    override val actions: ActionApi = decisionPipeline
        ?.actionApi(packageId)
        ?.let { scoped ->
            object : ActionApi {
                override fun provide(domain: String, provider: ActionProvider): Registration =
                    track(scoped.provide(domain, provider))

                override fun on(handlerKey: String, handler: ActionHandler): Registration =
                    track(scoped.on(handlerKey, handler))

                override fun constraint(key: String, predicate: ActionConstraint): Registration =
                    track(scoped.constraint(key, predicate))
            }
        }
        ?: FailClosedActionApi(packageId)

    // 查询面（§21.5）恒可装配：KernelQueryApi 只 wrap 既有必填 entityKernel，与决策管线无关。
    override val query: QueryApi = KernelQueryApi(entityKernel)

    // FunctionApi.register 返回 Unit、没有 Registration；其卸载期反注册面属「需组长接线」，
    // 见交付清单。本波不为此伪造第二套注册表或假撤销。
    override val functions: FunctionApi = NativeFunctionApi(nativeMethods)
    // RT2 D 车：storage 视图可重绑。初值仍是构造期绑定的本包段（未恢复时行为逐值不变）；
    // 读档 adoptStorage 后宿主经 [ModRuntimeHost.rebindStorage] 调 [rebindStorage] 换指向。
    private val storageView = RebindableStorage(storageStore.apiFor(packageId))
    override val storage: StorageApi get() = storageView
    // 受控写面（ModDX4-B1）：宿主在 onLoad 之前经文件级 extension adoptGameStateWrite 注入
    // （本类成员函数数已抵 detekt TooManyFunctions 阈值，adopt 缝收在 ModDx4WriteGrantGate.kt 的
    // 文件级 extension）。null = 旧装配形态（写 setter 走缺省 fail-closed UOE，不静默吞写）。
    // 声明在 gameStateImpl 之前：后者经 provider 闭包惰性读它（读写都在运行期，初始化序安全）。
    internal var gameStateWrite: ModDx4GameStateWrite? = null
    override val gameState: GameStateApi =
        InjectedGameState(intVarView, strVarView, hasIntVarView, hasStrVarView, writeProvider = { gameStateWrite })

    // 确定性随机面（§21.6）：宿主装配时经 [adoptRandomSeed] 注入派生流；未注入时显式
    // fail-closed，不返回静默 0/false 兜底（那会让作者以为随机生效了）。
    private var randomApi: RandomApi? = null
    override val rng: RandomApi get() = randomApi ?: FailClosedRandomApi(packageId)

    // 事件面（§6.1）：宿主装配时经 [adoptEventRegistry] 换成**全链共享**注册表；未注入时是显式
    // fail-closed 桩（直接构造本类的测试/旧装配路径行为逐值不变，不伪装成已接线）。
    private val failClosedEvents: EventApi = FailClosedEventApi()
    private var eventAdapter: EventApiAdapter? = null

    // 耗时归因面（§18.5 诊断面扩展）：宿主装配时经 [adoptTimingLedger] 注入会话账本；未注入时
    // 显式 fail-closed（读不返回静默空表、写不静默丢弃 —— 那会让作者以为自己的 mod 不耗时）。
    private var timingAdapter: TimingApi? = null
    override val timing: TimingApi get() = timingAdapter ?: FailClosedTimingApi(packageId)

    // per-mod 设置面（RT2 per-mod 配置）：宿主在 onLoad 之前经 [adoptSettings] 注入本包快照；
    // 未注入时显式 fail-closed（不返回声明默认值 —— 那会让作者以为玩家改的值生效了）。
    private var settingsApi: SettingsApi? = null
    override val settings: SettingsApi get() = settingsApi ?: FailClosedSettingsApi(packageId)

    // 跨 mod Kotlin 服务面（RT2 P1 工程契约）：宿主装配时经 [adoptServices] 换成**全链共享**
    // 注册表 + 本包身份适配器；未注入时显式 fail-closed（不返回静默空表 —— 那会让作者以为
    // 服务没发出去是别的原因）。装配入口是文件级 extension：宿主类函数数已抵 detekt
    // TooManyFunctions 阈值，本类不再新增成员函数。
    internal var servicesAdapter: ModServiceRegistry? = null
    override val services: ModServiceRegistry get() = servicesAdapter ?: FailClosedServices(packageId)

    // mod 资源面（RT2 资源注入第一刀）：宿主在 install 里、onLoad 之前把本包视图写进 resourceApi；
    // 未注入时显式 fail-closed（不返回空表 —— 那会让作者以为自己的资源打进了包）。
    // internal var 而非 adopt 成员函数：本类成员函数已近 detekt TooManyFunctions 上限，装配细节
    // 收在 ModResourceStore.kt，改装配不改构造签名。
    internal var resourceApi: ModResourceApi? = null
    override val resources: ModResourceApi get() = resourceApi ?: FailClosedResourceApi(packageId)

    // mod 自带三语文案面（RT2 多语言文案）：宿主在 install 里、onLoad 之前经 `adoptStrings`
    // 绑上会话级文案表 + 语言提供者；未装配时显式 fail-closed（不返回键本身兜底 —— 那会让
    // 作者以为声明生效了）。
    internal var stringsApi: StringsApi? = null
    override val strings: StringsApi get() = stringsApi ?: FailClosedStringsApi(packageId)

    // mod 音频面（Lane AN；RT2 资源注入第二刀）：宿主在 install 里、onLoad 之前把本包播放
    // 适配器写进 audioApi；未注入时显式 fail-closed（不返回静默 false —— 那会让作者以为
    // 播放成功只是手机没声音）。internal var 而非 adopt 成员函数：类内函数数已在 detekt
    // 上限附近，装配细节收在 ModAudioWiring.kt。
    internal var audioApi: AudioApi? = null
    override val audio: AudioApi get() = audioApi ?: FailClosedAudioApi(packageId)

    override val ui: UiApi = object : UiApi {
        override fun registerInteraction(
            labelKey: String,
            visible: (() -> Boolean)?,
            onClick: () -> Unit,
        ): Registration = track(uiRegistry.registerInteraction(labelKey, visible, onClick))

        override fun registerImage(resourceName: String, altText: String, maxSidePx: Int?): Registration =
            track(uiRegistry.registerImage(resourceName, altText, maxSidePx))
    }

    /**
     * 读档恢复后把本 context 的 storage 视图重绑到重建容器 [newStore] 的本包段（RT2 D 车）。
     *
     * internal：重绑对 mod 透明，不进 mod-api 公开 ABI；只许宿主在读档缝同步调用。
     * [newStore] 里本包段存在但未激活（孤儿口径）时，新视图保持 fail-closed 拒读写，
     * 不静默启用（[ModStorageStore.apiFor] 的既有守卫，未恢复前的拒绝语义照旧）；
     * 本包不在 [newStore] 里（新装 mod 无旧档）时 apiFor 登记并激活空段，与首装同形态。
     */
    internal fun rebindStorage(newStore: ModStorageStore) {
        activeStorageStore = newStore
        storageView.target = newStore.apiFor(packageId)
    }

    /**
     * 换用宿主提供的**全链共享** hook 引擎（§20.1 多 Mod 非替换式挂钩，RT2 P1-5 接线）。
     *
     * 为什么必须共享一份：槽序的权威排序是「priority 升序 + 链接声明序」，它跨 mod 才成立；
     * 每个 context 各持一份 pipeline 时，每份都知道全部槽但只收自己包的 binding，派发按
     * context 分组而非全局序 —— 两个 mod 各自的 priority 会变成「先装的 mod 整体优先」。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前调用
     * （[HookApi] 绑定必须落在这份共享引擎上）。
     */
    internal fun adoptHookPipeline(shared: HookPipeline) {
        hookPipeline = shared
        hookAdapter = HookApiAdapter(shared, packageId, knownKeysProvider = { knownFunctionKeys })
    }

    /**
     * 换用宿主提供的**全链共享**交互阶段管线（§21.4，RT2 P0-LAST 生产触发点接线）。
     *
     * 为什么必须共享一份：交互阶段钩子的执行序是「阶段 → packageRank（声明链接序）→
     * packageId」，跨 mod 才有全局确定性；每个 context 各持一份时，两个 mod 各自的
     * PRE/POST 会退化成「先装的 mod 整体优先」。宿主页边界把玩家真实输入送进这条共享
     * 管线评估/执行（`context.interactions.registerHook` 的绑定必须落在这份上）。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前调用。
     */
    internal fun adoptInteractionPipeline(shared: InteractionPipeline) {
        interactionPipeline = shared
        interactionAdapter = InteractionApiAdapter(shared, packageId)
    }

    /**
     * 注入本 mod 的确定性随机流（§21.6，RT2 P1-7 收账）。
     *
     * 种子 = 宿主当前决策种子（装配期由安装级熵基座派生、读档期换成档内种子），本类再按
     * `packageId` 派生成**独立** SFMT19937（不碰引擎那条被黄金基准校准过的流，见 [ModRandomApi]）。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前（首装）与读档采纳
     * 档内种子之后调用。重复调用 = 换种子从头重放（[ModRandomApi] 的 v1 确定性口径）。
     */
    internal fun adoptRandomSeed(decisionSeed: Long) {
        randomApi = ModRandomApi(decisionSeed, packageId)
    }

    /**
     * 换用宿主提供的**全链共享**事件注册表（§6.1 贡献点，RT2 P1 接线）。
     *
     * 为什么必须共享一份：事件处理器的执行序是「桶位 → 桶内注册序」，注册序跨 mod 才有意义；
     * 每个 context 各持一份注册表时，各 mod 的处理器会按 mod 分组而不是按全局注册序排。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前调用
     * （[EventApi] 注册必须落在这份共享表上）。
     */
    internal fun adoptEventRegistry(shared: EventDispatchRegistry) {
        eventAdapter = EventApiAdapter(shared, packageId)
    }

    /**
     * 注入宿主会话的耗时账本（§18.5 诊断面扩展，RT2 P1 耗时归因）。
     *
     * 一个会话一份账本：四个测量点（lifecycle / tick / hook / event）都往同一簿上写，
     * shutdown 时由宿主一次性打 `mod.timing …` 行；作者侧经本 context 读写自己包的读数
     * （写侧 = `timing.record` / `timing.measure`，RT2 P1 写侧扩展，与引擎测量点同表同口径）。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前调用。
     */
    internal fun adoptTimingLedger(ledger: ModTimingLedger) {
        timingAdapter = PackageTimingApi(packageId, ledger)
    }

    /**
     * 注入宿主会话的**单次耗时预算**（§18.5 阈值诊断）。
     *
     * 与账本不同：预算是「越阈值才记一条」的判定器，挂在共享 hook 引擎与共享交互管线上
     * （两处都是全链一份），因此本方法只把同一个实例扇出到两条共享管线，不额外持有。
     * 预算为 null 的会话不调用本方法，两处保持零开销直调。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前调用。
     */
    internal fun adoptHandlerBudget(budget: ModHandlerBudget) {
        hookPipeline.handlerBudget = budget
        interactionPipeline.handlerBudget = budget
    }

    /**
     * 注入本包的设置快照（RT2 per-mod 配置：作者声明一次、设置页自动出、值按包持久化）。
     *
     * 快照 = 声明清单（Manifest `settings` 段翻译）+ 设备侧按包配置文件里已持久化的值文本；
     * 文件缺失 / 损坏 / 越界 / 类型不符的键在快照内**回退声明默认值**（[ModSettingsSnapshot]
     * 的解析口径）。core 只认这份不可变快照，不读文件、不碰 Android API。
     *
     * internal：装配细节，不进 mod-api 公开 ABI；只许宿主在 `onLoad` 之前调用
     * （作者面 `settings` 只有只读 getter，快照注入后不再变）。
     */
    internal fun adoptSettings(snapshot: ModSettingsSnapshot) {
        settingsApi = PackageSettingsApi(snapshot)
    }

    /** 装配产物的可观测面：ui 登记表的**当前可见**键与命中（谓词现求值，DX-F1；真实输入状态机接线属组长）。 */
    fun uiRegisteredKeys(): Set<String> = uiRegistry.registeredKeys()

    /** 命中并触发一个 ui 贡献项；撤销或当前不可见立即返回 false（不触发回调）。 */
    fun uiClick(labelKey: String): Boolean = uiRegistry.click(labelKey)
}

/**
 * storage 视图的可重绑委托（RT2 D 车）：[StorageApi] 四成员逐次转发到当前目标，
 * 换目标 = 一次引用赋值。目标视图自身携带激活/隔离守卫，本类不复制任何判定、不吞任何异常，
 * 所以重绑前后 [StorageApi] 契约语义逐值不变（缺键 null、未激活 fail-closed 抛错）。无静态状态。
 */
private class RebindableStorage(initial: StorageApi) : StorageApi {

    /** 当前目标视图；@Volatile 保证换指向后的跨线程可见性（写者只限 VM 线程读档缝）。 */
    @Volatile
    var target: StorageApi = initial

    override fun put(key: String, value: String) = target.put(key, value)

    override fun get(key: String): String? = target.get(key)

    override fun remove(key: String): Boolean = target.remove(key)

    override fun keys(): Set<String> = target.keys()
}

private const val MAX_MESSAGE_LENGTH: Int = 1000

private const val MAX_LINES_PER_CONTEXT: Int = 1000

private const val TRUNCATED_SUFFIX: String = "…(截断)"

private const val SUPPRESSED_NOTICE: String =
    "[%s][WARN] 日志超过 %d 条上限，后续同类日志静默（本行之前已抑制 %d 条）"

/**
 * mod 日志面：实例级、可注入 sink、按 packageId 归属，测试用内存收集，不设静态全局。
 *
 * 三条硬化（作者在 `onFrame`/每日一拍里打日志不会把玩家拖慢）：
 * 1. 单条消息超 [MAX_MESSAGE_LENGTH] 个 Char 就截断并追加 [TRUNCATED_SUFFIX]（不整条丢：作者
 *    最需要看的往往是被截断处之前的关键字）；
 * 2. 空白消息（含只有空格/换行）静默丢弃且不计数 —— 作者常写 `logger.info("")` 当分隔，收它没意义；
 * 3. 每个 context（= 每个 packageId 一次装配）最多落 [MAX_LINES_PER_CONTEXT] 条，之后**只打一条**
 *    [SUPPRESSED_NOTICE] 元告警，再往后静默 —— 洪水时既不刷屏也不静默到查不出原因。
 *
 * 常量放文件级、不放伴生对象：core 层禁静态可变全局，源码形状闸会拦 `companion object`。
 *
 * MODDX2 二期 G2 追加第 4 条口径：emit 实际发出的每行（正文行、截断后的内容、只发一次的
 * 抑制元告警）同步写进 [buffer] 的每包最近日志环 —— 只记「已发出的行」，不解除上面三条
 * 任何一条抑制；空白与被静默吞掉的行不进环。宿主回看缝见 ModContextFaces.kt 的 recentLogLines。
 */
private class PackageLogger(
    private val packageId: String,
    private val sink: (String) -> Unit,
    private val buffer: ModLogBuffer,
) : ModLogger {
    private var emitted: Int = 0
    private var suppressed: Int = 0
    private var notifiedSuppression: Boolean = false

    override fun info(message: String) = emit("INFO", message, null)
    override fun warn(message: String) = emit("WARN", message, null)
    override fun error(message: String, cause: Throwable?) = emit("ERROR", message, cause)

    private fun emit(level: String, message: String, cause: Throwable?) {
        if (message.isBlank() && cause == null) return
        if (emitted >= MAX_LINES_PER_CONTEXT) {
            suppressed++
            if (!notifiedSuppression) {
                notifiedSuppression = true
                val notice = SUPPRESSED_NOTICE.format(packageId, MAX_LINES_PER_CONTEXT, suppressed)
                buffer.append(notice)
                sink(notice)
            }
            return
        }
        emitted++
        val raw = message
        val body = if (raw.length <= MAX_MESSAGE_LENGTH) raw else raw.take(MAX_MESSAGE_LENGTH) + TRUNCATED_SUFFIX
        val tail = cause?.let { " cause=${it::class.java.simpleName}:${it.message}" } ?: ""
        val line = "[$packageId][$level] $body$tail"
        // 环先入、sink 后写：环只镜像 emit 的产出，与 sink 收到的行逐字节一致（记账口径）。
        buffer.append(line)
        sink(line)
    }
}

/**
 * 事件能力面在**未装配接线**时的显式 fail-closed 实现（组长裁定 §3.2.1）。
 *
 * 宿主装载路径（`ModRuntimeHost.install`）会先经 [adoptEventRegistry] 把本 context 换成全链共享的
 * [EventDispatchRegistry]，因此生产会话走不到这里；本类只剩「直连构造的 context」（单测、工具）
 * 与宿主装配失败前的窗口。
 *
 * 留 fail-closed 而不是静默 no-op 的理由：注册被吞掉时作者会以为处理器在跑，事件不触发根本查不出来。
 * 现在生产缝在 `ProcessRuntime.dispatchEventMembers` 的桶边界（见 `ModEventWiring`）。
 */
private class FailClosedEventApi : EventApi {
    override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration =
        throw UnsupportedOperationException(
            "M2-6：events.register($eventKey/$bucket) 生产消费点未接（本 context 未经宿主 adoptEventRegistry 装配）；" +
                "生产会话里宿主会注入全链共享的 EventDispatchRegistry",
        )

    /**
     * 未装配的只读查询答「没有」而不是抛：接口缺省的 UOE 是给**不认识本方法的旧实现**的显式
     * fail-closed；本类就是「已装配面的空集兜底」这一确定状态（不知道事件面 ⇒ exists 恒 false），
     * 与 [EventApi.exists] KDoc 的「未装配时集合空 ⇒ 恒 false」逐字对应（作者不必 catch）。
     */
    override fun exists(eventKey: String): Boolean = false

    /** 未装配时已知事件集为空（见 [exists] 的 fail-safe 口径）。 */
    override fun knownEventKeys(): List<String> = emptyList()
}

/**
 * 决策管线未装配时的显式 fail-closed 行动面（组长裁定 §3.2.1 先例，与 [FailClosedEventApi] 同口径）。
 *
 * 宿主没有注入 `DecisionPipeline` 时，引擎侧没有可消费的候选/处理器/约束注册缝：本桩一律当场抛
 * 异常并点名 packageId 与 §12.1，让调用方清楚知道生产装配点未接；不返回静默吞掉的 no-op
 * Registration、不登记后假派发、不造第二套仲裁器（真实接线归宿主注入 DecisionPipeline）。
 */
private class FailClosedActionApi(
    private val packageId: String,
) : ActionApi {
    override fun provide(domain: String, provider: ActionProvider): Registration = fail("provide", domain)

    override fun on(handlerKey: String, handler: ActionHandler): Registration = fail("on", handlerKey)

    override fun constraint(key: String, predicate: ActionConstraint): Registration = fail("constraint", key)

    private fun fail(entry: String, key: String): Nothing = throw UnsupportedOperationException(
        "决策管线未装配：$packageId（待宿主注入 DecisionPipeline，§12.1）；" +
            "actions.$entry($key) 不提供静默 no-op 注册",
    )
}

/**
 * 随机面未装配时的显式 fail-closed（与 [FailClosedActionApi] 同口径，§12.1）。
 *
 * 不返回静默的 0 / false 兜底：那会让 mod 作者以为「随机生效了」，而实际上每次抽数都恒定 ——
 * 是比抛错难查得多的失真。装配正常时这条桩不可达（[ModRuntimeHost.install] 必注入）。
 */
internal class FailClosedRandomApi(
    private val packageId: String,
) : RandomApi {
    override fun nextInt(bound: Int): Int = fail("nextInt($bound)")

    override fun nextInt(minInclusive: Int, maxExclusive: Int): Int =
        fail("nextInt($minInclusive, $maxExclusive)")

    override fun chance(percent: Int): Boolean = fail("chance($percent)")

    override fun nextDouble(): Double = fail("nextDouble()")

    override fun permutationIndices(size: Int): IntArray = fail("permutationIndices($size)")

    private fun fail(call: String): Nothing = throw UnsupportedOperationException(
        "随机面未装配：$packageId（待宿主注入派生流，§12.1）；rng.$call 不提供静默兜底",
    )
}

/**
 * 只读 curated 视图由构造期注入；未注入时给干净缺省 0 与空串。
 * 这是未接线时的缺省，不是真实 VariableStore 读数；真实授权视图接线属组长（K3）。
 *
 * 存在性探测供给（DX-C）缺省 null = 台架 / 未接线形态：两探测恒 false（空集口径），
 * 与读数缺省 0 / 空串同一诚实边界——不伪装成有数据。哨兵语义不变（K1 冻结契约）：
 * 探测 false 时 [getIntVar]/[getStrVar] 照旧返回哨兵，不抛。
 *
 * 受控写面（ModDX4-B1）：[writeProvider] 缺省给 null = 未装配形态，两个 setter 显式
 * fail-closed 抛 UOE（与接口缺省同文案口径）——不静默吞写；宿主在 onLoad 之前经
 * [adoptGameStateWrite]（本文件底部的文件级 extension）注入 [ModDx4GameStateWrite] 后，
 * setter 走「授权闸当场判 + RuntimeScheduler 排队到转移边界」的生产语义。
 */
private class InjectedGameState(
    private val intVar: (String) -> Long,
    private val strVar: (String) -> String,
    private val hasIntVarView: ((String) -> Boolean)? = null,
    private val hasStrVarView: ((String) -> Boolean)? = null,
    /** 写供给的惰性读取（宿主 onLoad 前注入；每次 setter 现读，注入前的调用走 UOE）。 */
    private val writeProvider: () -> ModDx4GameStateWrite? = { null },
) : GameStateApi {
    override fun getIntVar(name: String): Long = intVar(name)
    override fun getStrVar(name: String): String = strVar(name)

    override fun hasIntVar(name: String): Boolean = hasIntVarView?.invoke(name) ?: false

    override fun hasStrVar(name: String): Boolean = hasStrVarView?.invoke(name) ?: false

    override fun setIntVar(name: String, value: Long) {
        val w = writeProvider() ?: throw UnsupportedOperationException(
            "gameState.setIntVar($name) 未接线：本 context 没有受控写面供给" +
                "（宿主未注入 ModDx4GameStateWrite，台架镜像 / 旧装配路径）；不静默吞写",
        )
        w.enqueueInt(name, value)
    }

    override fun setStrVar(name: String, value: String) {
        val w = writeProvider() ?: throw UnsupportedOperationException(
            "gameState.setStrVar($name) 未接线：本 context 没有受控写面供给" +
                "（宿主未注入 ModDx4GameStateWrite，台架镜像 / 旧装配路径）；不静默吞写",
        )
        w.enqueueStr(name, value)
    }
}

/**
 * 耗时归因面的本包视图（§18.5 扩展）：把共享账本按 [packageId] 过滤成本 mod 自己的读数。
 *
 * 读写都落在**同一份**会话账本上（[adoptTimingLedger] 注入的那份）：写侧把作者的
 * `record` / `measure` 转给账本，与引擎四个测量点同表同口径；不新开账本、不复制统计口径。
 *
 * 只读本包是**有意**的边界：归因面不是跨 mod 探针面（跨 mod 看宿主 shutdown 日志）。
 * 过滤与排序都在账本侧完成。
 */
private class PackageTimingApi(
    private val packageId: String,
    private val ledger: ModTimingLedger,
) : TimingApi {
    override fun samples(): List<TimingSample> = ledger.forPackage(packageId)

    override fun reset() = ledger.resetPackage(packageId)

    override fun record(scope: String, nanos: Long) = ledger.record(packageId, scope, nanos)
}

/**
 * 耗时面未装配时的显式 fail-closed（与 [FailClosedRandomApi] 同口径）。
 *
 * 读不返回静默空表（空表会被读成「我的 mod 没有耗时样本」）、写不静默丢弃（丢弃会让作者以为
 * 计时生效了），而真相是「这个 context 没接账本」。
 * 装配正常时这条桩不可达（[ModRuntimeHost.install] 在 onLoad 之前注入）。
 */
private class FailClosedTimingApi(
    private val packageId: String,
) : TimingApi {
    override fun samples(): List<TimingSample> = fail("samples()")

    override fun reset(): Unit = fail("reset()")

    override fun record(scope: String, nanos: Long): Unit = fail("record($scope)")

    private fun fail(call: String): Nothing = throw UnsupportedOperationException(
        "耗时归因面未装配：$packageId（待宿主注入会话账本，§12.1）；timing.$call 不提供静默兜底",
    )
}

/**
 * 实例级界面贡献登记表：[registerInteraction] / [registerImage] 返回可撤销的 Registration，
 * 撤销后立即不再命中；图片元素按 resourceName 去重（同资源重复声明 = 后声明覆盖，与交互项
 * 按键覆盖同口径）。
 *
 * 交互项载体（DX-F1）：值从裸回调升级为 [InteractionAction]（点击回调 + 可见性谓词）。谓词
 * 在 [registeredKeys] / [click] 被宿主查询/命中时**现求值**，不在注册期求值：同一项可随
 * gameState / settings 变化反复显隐。谓词 `null` = 恒可见；谓词抛异常按「不可见 + WARN」
 * 降级（WARN 走构造灌入的 [logSink]，与资源渲染降级同口径），不崩、不让坏谓词砖掉 UI 面。
 *
 * internal 而非 private：宿主只读查询落在文件级 extension（ModUiHostQuery.kt）里读本表快照，
 * 这样不用给 [ModContextImpl] / [ModRuntimeHost] 再加成员函数（两者函数数都抵 detekt
 * TooManyFunctions 阈值）。表仍是实例状态，无静态状态。
 */
internal class InstanceUiRegistry(
    private val logSink: (String) -> Unit,
    private val packageId: String,
) : UiApi {

    /** 交互项载体：点击回调 + 可见性谓词（`null` = 恒可见）。private 即可，不出 internal 面。 */
    private class InteractionAction(
        val onClick: () -> Unit,
        val visible: (() -> Boolean)?,
    )

    private val actions = LinkedHashMap<String, InteractionAction>()
    private val images = LinkedHashMap<String, ModUiElement.Image>()

    override fun registerInteraction(
        labelKey: String,
        visible: (() -> Boolean)?,
        onClick: () -> Unit,
    ): Registration {
        val action = InteractionAction(onClick, visible)
        actions[labelKey] = action
        return object : Registration {
            override fun unregister() {
                // 只撤自己那一笔：同键被后声明覆盖时，旧 registration 撤销不得误删新项
                // （与 registerImage 的同资源守卫同口径，兑现本类 KDoc「按键覆盖同口径」的既称谓）。
                if (actions[labelKey] === action) {
                    actions.remove(labelKey)
                }
            }
        }
    }

    override fun registerImage(resourceName: String, altText: String, maxSidePx: Int?): Registration {
        val element = modUiImage(resourceName, altText, maxSidePx)
        images[element.resourceName] = element
        return object : Registration {
            override fun unregister() {
                // 只撤自己那一笔：同资源被后声明覆盖时，旧 registration 撤销不得误删新元素。
                if (images[element.resourceName] === element) {
                    images.remove(element.resourceName)
                }
            }
        }
    }

    /**
     * 交互项键快照（声明序，**只含当前可见项**）：可见性谓词现求值；谓词缺省（`null`）的
     * 恒可见项永远在内，谓词抛异常的项按不可见降级（已 WARN）。宿主浮层拍快照即自动滤掉
     * 不可见项，无需各消费点自查。
     */
    fun registeredKeys(): Set<String> {
        val out = LinkedHashSet<String>()
        for ((key, action) in actions) {
            if (isActionVisible(key, action)) out += key
        }
        return out
    }

    /** 图片元素快照（声明序）。空集返回共享空表，不构造新列表（无元素时零成本旁路）。 */
    fun registeredImages(): List<ModUiElement.Image> =
        if (images.isEmpty()) emptyList() else images.values.toList()

    /** 诊断只读：图片元素数（不构造快照）。 */
    fun registeredImageCount(): Int = images.size

    /** 命中并触发：不可见项不命中（返回 false，不触发回调），与快照「不在面上」同口径。 */
    fun click(labelKey: String): Boolean {
        val action = actions[labelKey] ?: return false
        val hit = isActionVisible(labelKey, action)
        if (hit) action.onClick()
        return hit
    }

    /**
     * 现求值可见性：谓词缺省（`null`）= 可见；谓词返回 `false` 或**抛任意 Throwable** =
     * 不可见（异常落一条 WARN 到 [logSink]，不上抛——隔离口径同 [InteractionPipeline]，
     * 查询/命中路径不许被坏谓词打断）。
     */
    private fun isActionVisible(labelKey: String, action: InteractionAction): Boolean {
        val predicate = action.visible ?: return true
        return try {
            predicate()
        } catch (t: Throwable) {
            logSink(
                "[ui][WARN] 交互项可见性谓词抛异常，按不可见降级 pkg=$packageId key=$labelKey error=$t",
            )
            false
        }
    }
}

// ---- hook 拼错闸的消息与候选口径（MODDX4-B3；与 EventDispatchRegistry 未知名闸同构）----
// 文案与最近候选口径被 mod-testing 的 RecordingHooks 逐字镜像（对拍用例断言两串相等），
// 改这里必须同步改镜像并跑 ModDx4HookCatalog 的对拍用例。

/** 未知名拒绝消息里最多给几个候选（只给最近的几个，不倒全量函数表）。 */
private const val NEAREST_FUNCTION_KEY_LIMIT: Int = 3

/** hook 目标键归一：与 `HookPipeline.canonical` / `FuncMeta.key` 同口径（去空白 + 大写）。 */
private fun canonicalFunctionKey(key: String): String = key.trim().uppercase(Locale.ROOT)

/**
 * 未知 hook 目标键的拒绝消息：含**原始**键（作者按它 grep 自己的注册点）、归属包与最接近候选。
 * 原始名而非归一后的 [key]：作者手里写的就是原始字面量，消息里的名字要能直接对回源码。
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
