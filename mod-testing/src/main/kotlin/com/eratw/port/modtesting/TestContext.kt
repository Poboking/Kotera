package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModRandomApi
import com.eratw.port.modapi.ActionApi
import com.eratw.port.modapi.ActionConstraint
import com.eratw.port.modapi.ActionHandler
import com.eratw.port.modapi.ActionProvider
import com.eratw.port.modapi.AudioApi
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EventApi
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.FunctionApi
import com.eratw.port.modapi.GameStateApi
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookApi
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionApi
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobApi
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModLogger
import com.eratw.port.modapi.ModResourceApi
import com.eratw.port.modapi.ModServiceRegistry
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.PhaseApi
import com.eratw.port.modapi.QueryApi
import com.eratw.port.modapi.RandomApi
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RelationshipApi
import com.eratw.port.modapi.RosterProjectionApi
import com.eratw.port.modapi.SettingsApi
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.StorageApi
import com.eratw.port.modapi.StringsApi
import com.eratw.port.modapi.TimingApi
import com.eratw.port.modapi.UiApi

/**
 * 内存版 [ModContext]：把 mod 的业务逻辑装进一个不需要引擎的上下文里跑。
 *
 * ## 作者需要知道的
 *
 * - 实体 / 关系 / 查询 / 存储是真语义（失败哨兵与生产一致），可以放心断言业务结果；
 * - 任务 / 事件 / 钩子 / 交互 / 相位是**录制型**：`onEnable` 注册的处理器会收进表，用
 *   [fireJob] / [dispatchEvent] / [dispatchHook] / [firePhase] 手动触发（不用等游戏日）；
 *   细粒度断言走 [eventsRecorder] / [hooksRecorder] / [interactionsRecorder]；
 * - `rng` 是**生产同一实现**（[ModRandomApi]）：按 `(decisionSeed, packageId)` 派生独立 SFMT19937
 *   流，与会话决策种子（[TestWorld.decisionSeed]，缺省 0）同口径 ⇒ 同 `decisionSeed` + 同包 +
 *   同调用序在台架与真机取到**逐位相同**的序列；
 * - `services` 是**生产同一实现**（`ModServiceRegistryImpl` + 本包适配器）：共享一张会话级表，
 *   卸载/`close` 即整包撤销；台架不重写注册、冲突与 owner 校验；
 * - [roster] / [actions] / [functions] 缺省是**显式 fail-closed 桩**（调用即抛
 *   `UnsupportedOperationException`）；需要这些面时两条路：改用 `:core-vm` 宿主级装配，或经
 *   [BenchFaces] 注入口（`runMod` / [TestWorld.newContext] 的可选参数）按面注入自备实现——
 *   桩仍是不注时的缺省，注入只对本 context 生效（见 [BenchFaces] KDoc）；
 * - `ui` 是**录制型**（[RecordingUi]）：`registerInteraction` / `registerImage` 都按生产
 *   `InstanceUiRegistry` 的语义落表（同键/同资源后声明覆盖、撤销只撤自己那笔、可见性谓词
 *   现求值、谓词异常降级 WARN 文案逐字同源），用例用 [uiRecorder] 观察登记项、用
 *   [RecordingUi.click] 模拟宿主派发点击；宿主浮层的解码/降级不归台架；
 * - [resources] 是**真实现 + 录制装饰**：底层复用 `:core-vm` 的 `ModResourceStore`（根 = 台架
 *   临时目录 `mods/rt2/<packageId>.assets/`，名字非法抛 `IllegalArgumentException`、单文件/整包
 *   超限按引擎口径 WARN 跳过 —— 与真引擎同一份实现，台架不另写语义），外面套
 *   [RecordingResources] 只记录 `keys` / `exists` / `size` / `open` 的调用与结果、不改返回值或
 *   异常；用例用 [resourcesRecorder] 断言「我读了什么」（见 `TestWorld.resource` 投放资源）；
 * - [audio] 是**录制型**（[RecordingAudio]）：与生产 `PackageAudioApi` 同一套降级链（缺资源 /
 *   非法名 / 空文件 ⇒ WARN + false，音量夹取），受理成功的调用落 [audioRecorder] ⇒ 作者能断言
 *   「我播了什么」；不模拟真的出声与坏容器（那是宿主播放器侧语义）；
 * - [timing] 是**录制型**（[RecordingTiming]）：聚合/排序/均值/校验/上限口径与生产 `ModTimingLedger`
 *   逐条一致，作者写面 `context.timing.record` / `measure` 与只读 `samples` / `reset` 都通；
 *   要直接注入假时钟或取替身内部状态时走 [timingRecorder]；`runtime.submit` 同步立即执行；
 * - [logger] 缺省**逐条原样录制**（作者断言看到自己写的原文）；要在台架复现生产的截断/空白丢弃/
 *   行数上限+元告警三条硬化，调 [hardenLogger]（缺省参数 = 生产口径，逐字对拍由 core-vm 的
 *   `LoggerHardeningParityTest` 锁）。降级链产生的 WARN（[timing] / [audio]）与生产一样走
 *   [logger]，故同样受硬化与行数额度约束；只看「降级有痕」用 [logEntries] 即可；
 * - [gameState] 读侧与生产同口径（哨兵 0 / 空串，大写归一）；写侧（v20 `setIntVar` /
 *   `setStrVar`）走**授权闸镜像**：本包声明域（manifest 的 global-dims ∪ write-grants，
 *   [TestWorld.of] 按 manifests 算好授权表）外的键当场抛 [IllegalArgumentException]，
 *   文案与生产 `ModDx4WriteGate` 逐字对齐；通过后经 [runtime] 提交 —— 台架的
 *   `TestRuntime.submit` 是同步内联执行，写立即生效（生产语义「边界后可见」在台架等价于
 *   「调用后可见」，时序判据由 core-vm 的排队测试锁）。
 */
// TooManyFunctions：A3 七面 track 包装 + adoptProductionShape + 代撤面把面扩到 13——
// 七面各自是独立 API 委托（高内聚低耦合的形状保真设计），硬拆伤台架镜像可读性。
@Suppress("TooManyFunctions")
class TestContext internal constructor(
    override val packageId: String,
    internal val world: TestWorldState,
    /**
     * 本包受控写授权表（ModDX4-B1；[TestWorld.of] / [TestWorld.ofManifests] 按 manifests
     * 计算）。缺省 [BenchWriteAuth.EMPTY] = 无授权（手工 newContext / 未声明写面）——
     * 生产同口径：未授权键写 IAE。
     */
    private val writeAuth: BenchWriteAuth = BenchWriteAuth.EMPTY,
    /**
     * 本 context 的桩面注入口（ModDX4-B6）：缺省 [BenchFaces()] = roster / actions / functions
     * 三面全部维持 fail-closed 桩，行为与注入口引入前逐位一致。注入只跟本次装配走：同一
     * [TestWorld] 再发的缺省 context 仍是桩（不粘 world）。字段语义与典型来源见 [BenchFaces]。
     */
    faces: BenchFaces = BenchFaces(),
) : ModContext {

    private val logs = mutableListOf<LogEntry>()
    private val loggerImpl = TestLogger(packageId, logs)
    private val eventsImpl = RecordingEvents(packageId, world.eventTable)
    private val hooksImpl = RecordingHooks(packageId, world.hookTable)
    private val jobsImpl = TestJobs(packageId, world.schema)
    private val phasesImpl = TestPhases(packageId, world.schema)
    private val randomImpl = ModRandomApi(world.decisionSeed, packageId)
    private val gameStateImpl = TestGameState()
    private val runtimeImpl = TestRuntime()

    /**
     * 本包注册项收集器（A3 代撤对齐，2026-11-26）：生产 `ModContextImpl.track` 的同位机制 ——
     * 七个返回 [Registration] 的面（events / hooks / phases / jobs / interactions / actions / ui）
     * 全部经本函数包装，宿主（[TestModSession.disable]）在 onDisable **之前**批量代撤。
     * 消 MOD-017/018 同族：作者不自持返回值时，台架禁用后处理器同样不再命中（与生产一致）。
     */
    private val trackedRegistrations = ArrayList<Registration>()

    /** 收集一个注册项并原样透传（faces 的包装层专用）。 */
    private fun track(reg: Registration): Registration {
        trackedRegistrations += reg
        return reg
    }

    /** 代撤本包全部注册项（生产同序：先代撤后 onDisable；异常不中断后续撤销）。 */
    internal fun revokeTrackedRegistrations() {
        for (reg in trackedRegistrations) runCatching { reg.unregister() }
        trackedRegistrations.clear()
    }

    /**
     * 灌入生产形状（A3 台架形状保真，2026-11-26）：事件键集 + 可挂函数键集一次注入，
     * 两面存在性闸与生产同开（缺省空集 = 两面都不校验，与生产「台架 / 无链接目录会话」
     * 同口径，行为不变）。
     *
     * 消 MOD-016 同族：模板自造事件键 / 拼错 hook 目标键在台架上单测全绿、装进真语料才在
     * onEnable 炸整包回滚。用例用真语料的事件名 / 函数目录灌入后，同样的自造键在台架
     * 当场红（拒绝文案与生产逐字一致，对拍已钉），两世界同闸。
     *
     * @param eventKeys 真语料链接产物的事件名全集（生产 `SymbolCatalog.events.keys`）；
     *   空集 = 事件面不校验（缺省）。
     * @param functionKeys 真语料链接产物的可挂函数键全集（生产 hook 目标键灌源 =
     *   `bindings.keys ∪ events.keys`，B4 并集口径）；空集 = hook 面不校验（缺省）。
     */
    fun adoptProductionShape(eventKeys: Set<String> = emptySet(), functionKeys: Set<String> = emptySet()) {
        eventsImpl.adoptKnownEvents(eventKeys)
        hooksImpl.adoptKnownFunctionKeys(functionKeys)
    }

    /**
     * gameState 的作者面：读侧委托 [gameStateImpl]（哨兵语义），写侧走授权闸镜像
     * （[GatedBenchGameState]，文案与生产逐字对齐）。[setGameIntVar] / [setGameStrVar]
     * 的**预置通道**不经本面（它们是作者用例的 arrange 手段，不是被测 API）。
     */
    private val gameStateApi: GameStateApi =
        GatedBenchGameState(packageId, gameStateImpl, writeAuth) { action -> runtimeImpl.submit(action) }

    private val timingImpl = RecordingTiming(
        packageId = packageId,
        // 生产把耗时面的降级 WARN 接到 `context.logger.warn`（见 ModRuntimeAdopt 的装配），
        // 台架照抄这条侧链：走同一个 logger，硬化开启时同样计入行数额度、同样被截断/抑制。
        warn = { message -> loggerImpl.warn(message) },
    )
    // 三桩面（ModDX4-B6）：faces 给了哪面就用注入实现，没给的面维持 fail-closed 桩（互不强制）。
    private val rosterImpl: RosterProjectionApi = faces.roster ?: TestRoster()
    private val interactionsImpl = RecordingInteractions(packageId, world.interactionTable)
    private val actionsImpl: ActionApi = faces.actions ?: TestActions()
    private val functionsImpl: FunctionApi = faces.functions ?: TestFunctions()
    private val uiImpl = RecordingUi(packageId) { message ->
        loggerImpl.warn(message)
        world.recordWarn(packageId, message)
    }
    private val stringsImpl = RecordingStrings(
        packageId,
        // 重复声明的 WARN 与生产同一句（文案由 LiveStringsApi 拼）：
        // 生产侧链是「warn → context.logger.warn」，台架再加一路会话级日志（作者看 TestWorld.logEntries）。
        onWarn = { message ->
            loggerImpl.warn(message)
            world.recordWarn(packageId, message)
        },
    )

    /**
     * 音频面（录制型替身）：资源字节走本包 [resources]（引擎同源 `ModResourceStore`），
     * 降级链与生产 `PackageAudioApi` 一致 —— 缺资源 / 非法名 / 空文件 ⇒ WARN + `false` 不抛，
     * 音量 0..1 越界夹取；受理成功的调用落 [RecordingAudio.plays]。
     *
     * 惰性构造：不碰音频的用例不会触发 [world] 的资源临时目录，也不会多一行 WARN。
     * WARN 同时进本 context 的 [logEntries]（作者用 `context.logEntries` 断言「降级有痕」）。
     */
    private val audioImpl: RecordingAudio by lazy {
        RecordingAudio(packageId, resources) { message -> loggerImpl.warn(message) }
    }

    // ------------------------------------------------------------------
    // 七个返回 Registration 的面的 track 包装层（A3 代撤对齐，2026-11-26）。
    //
    // 生产 `ModContextImpl` 对 events / hooks / phases / jobs / interactions / actions / ui
    // 七面统一 `track` 收集、宿主在 onDisable 之前批量代撤（shutdown / 失败回滚同序）；
    // 台架旧形态直接裸暴露替身面，disable 只裸调 onDisable —— 作者不自持返回值时
    // 「禁用后处理器仍命中」，正是 MOD-017/018 的两世界差异。包装层只覆写返回
    // Registration 的入口（其余方法 `by` 委托替身面，行为逐位不变）。
    // ------------------------------------------------------------------

    private val eventsTracked = object : EventApi by eventsImpl {
        override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler) =
            track(eventsImpl.register(eventKey, bucket, handler))
    }

    private val hooksTracked = object : HookApi by hooksImpl {
        override fun register(targetKey: String, phase: HookPhase, handler: HookHandler) =
            track(hooksImpl.register(targetKey, phase, handler))
    }

    private val phasesTracked = object : PhaseApi by phasesImpl {
        override fun subscribe(phase: String, handler: com.eratw.port.modapi.PhaseHandler) =
            track(phasesImpl.subscribe(phase, handler))
    }

    private val jobsTracked = object : JobApi by jobsImpl {
        override fun bind(jobId: String, handler: com.eratw.port.modapi.JobHandler) =
            track(jobsImpl.bind(jobId, handler))

        override fun bindTemplate(templateKey: String, handler: com.eratw.port.modapi.JobHandler) =
            track(jobsImpl.bindTemplate(templateKey, handler))
    }

    private val interactionsTracked = object : InteractionApi by interactionsImpl {
        override fun registerHook(phase: InteractionPhase, handler: InteractionHandler) =
            track(interactionsImpl.registerHook(phase, handler))
    }

    private val actionsTracked = object : ActionApi by actionsImpl {
        override fun provide(domain: String, provider: ActionProvider) =
            track(actionsImpl.provide(domain, provider))

        override fun on(handlerKey: String, handler: ActionHandler) =
            track(actionsImpl.on(handlerKey, handler))

        override fun constraint(key: String, predicate: ActionConstraint) =
            track(actionsImpl.constraint(key, predicate))
    }

    private val uiTracked = object : UiApi by uiImpl {
        override fun registerInteraction(
            labelKey: String,
            visible: (() -> Boolean)?,
            onClick: () -> Unit,
        ) = track(uiImpl.registerInteraction(labelKey, visible, onClick))

        override fun registerImage(resourceName: String, altText: String, maxSidePx: Int?) =
            track(uiImpl.registerImage(resourceName, altText, maxSidePx))
    }

    override val logger: ModLogger get() = loggerImpl

    override val runtime: RuntimeScheduler get() = runtimeImpl

    override val entities: EntityApi get() = world.entities

    override val relationships: RelationshipApi get() = world.relationships

    override val roster: RosterProjectionApi get() = rosterImpl

    override val events: EventApi get() = eventsTracked

    override val hooks: HookApi get() = hooksTracked

    override val phases: PhaseApi get() = phasesTracked

    override val jobs: JobApi get() = jobsTracked

    override val interactions: InteractionApi get() = interactionsTracked

    override val actions: ActionApi get() = actionsTracked

    override val query: QueryApi get() = world.query

    override val functions: FunctionApi get() = functionsImpl

    override val storage: StorageApi get() = world.storage

    override val gameState: GameStateApi get() = gameStateApi

    override val rng: RandomApi get() = randomImpl

    override val timing: TimingApi get() = timingImpl

    /**
     * 设置面：读侧与生产同一实现（manifest `settings` 声明 → 生产快照 + 逐包适配器），
     * 用例用 [settingsRecorder] 造持久化值。manifest 未声明设置时保持台架便利语义（见其 KDoc）。
     */
    private val settingsImpl: RecordingSettings =
        RecordingSettings(packageId, declarations = world.settingsDeclarationsOf(packageId))

    override val settings: SettingsApi get() = settingsImpl

    /**
     * 服务面（生产同一实现）：本包视角的 [ModServiceRegistry]（共享会话级注册表），
     * 冒名发布/多包冲突/撤销语义与真机逐字一致；会话结束（[TestWorld.close]）整包撤销。
     */
    private val servicesImpl: ModServiceRegistry = world.servicesFor(packageId)

    override val services: ModServiceRegistry get() = servicesImpl

    /**
     * 资源面：真实现（引擎同源 `ModResourceStore`，根 = 台架临时目录
     * `mods/rt2/<packageId>.assets/`）+ [RecordingResources] 录制装饰。
     *
     * 装饰器逐调用透传（返回值 / IAE 文案原样），只多记一层「读了哪些名字」；用例用
     * [TestWorld.resource] 投放资源，用 [resourcesRecorder] 断言读调用。
     */
    private val resourcesImpl: RecordingResources by lazy {
        RecordingResources(world.resources.apiFor(packageId))
    }

    override val resources: ModResourceApi get() = resourcesImpl

    override val ui: UiApi get() = uiTracked

    /** 自带三语文案面真实现（引擎同源校验/回退；用例用 [stringsRecorder] 断言）。 */
    override val strings: StringsApi get() = stringsImpl

    /** 录制型音频面（与生产 `PackageAudioApi` 同一套降级链；用例用 [audioRecorder] 断言）。 */
    override val audio: AudioApi get() = audioImpl

    /** 本 context 收到的日志（按发生序；未硬化时逐字原文，硬化见 [hardenLogger]）。 */
    val logEntries: List<LogEntry> get() = logs.toList()

    /**
     * 打开生产日志硬化的模拟（缺省**关**：逐条原样录制，作者断言看到自己写的原文）。
     *
     * 开启后 [logger] 落 [logEntries] 时按生产 `PackageLogger` 三条口径处理：
     * 单条超 [maxMessageLength] 字符截断并追加「…(截断)」（cause 在截断**之后**拼接）；
     * 空白消息且无 cause 静默丢弃、不占行数；每个 context 最多 [maxLinesPerContext] 行正文，
     * 越限后正文静默，仅首次落一条等级 `WARN` 的元告警（正文 = 生产行正文，不含包名前缀）。
     *
     * 缺省参数 = 生产 1000/1000 口径；传更小的值可在台架上用少量日志逼近边界（作者自测洪水保护）。
     * 可重复调用，每次重置行数/抑制计数（已落 [logEntries] 的记录不回滚）。
     */
    fun hardenLogger(
        maxMessageLength: Int = HARDEN_MAX_MESSAGE_LENGTH,
        maxLinesPerContext: Int = HARDEN_MAX_LINES_PER_CONTEXT,
    ) = loggerImpl.harden(maxMessageLength, maxLinesPerContext)

    /** [logger] 是否处于硬化模拟态（缺省 `false` = 现状）。 */
    val isLoggerHardened: Boolean get() = loggerImpl.isHardened

    /** 硬化模拟下被静默的越限条数（生产只暴露首次抑制数，台架额外给总数便于断言口径）。 */
    val suppressedLogLineCount: Int get() = loggerImpl.suppressedLineCount

    /** 当前已绑定的任务槽（断言 `onEnable` 的注册真的发生了）。 */
    val boundJobIds: Set<String> get() = jobsImpl.boundJobIds

    /** 本 context 的录制型事件注册表视图（断言注册项 / 手动派发用）。 */
    val eventsRecorder: RecordingEvents get() = eventsImpl

    /** 本 context 的录制型钩子绑定表视图（断言调用序 / 手动派发用）。 */
    val hooksRecorder: RecordingHooks get() = hooksImpl

    /** 本 context 的三语文案面真身：用例在这里 [RecordingStrings.declare] / 改 [RecordingStrings.languageCode]。 */
    val stringsRecorder: RecordingStrings get() = stringsImpl

    /** 本 context 的设置面真身：用例在这里 [RecordingSettings.declare] 键值。 */
    val settingsRecorder: RecordingSettings get() = settingsImpl

    /**
     * 本 context 的耗时面真身：作者写面（`context.timing.record` / `measure`）与生产账本同口径，
     * 这里额外暴露替身以便注入假时钟 / 直接查 [RecordingTiming.scopeNames]；归因断言统一走
     * `context.timing.samples()`（降序，同名累计、并列保持首次出现序）。
     */
    val timingRecorder: RecordingTiming get() = timingImpl

    /**
     * 本 context 的音频面真身：用例在这里断言「我播了什么」——
     * [RecordingAudio.plays]（资源名/loop/生效音量）、[RecordingAudio.current]（当前曲目）、
     * [RecordingAudio.stopCount]（该停时真的调了停）、[RecordingAudio.warnings]（降级文案）。
     */
    val audioRecorder: RecordingAudio get() = audioImpl

    /** 本 context 的录制型交互相位机视图（断言注册相位 / 最近一次 run 用）。 */
    val interactionsRecorder: RecordingInteractions get() = interactionsImpl

    /** 本 context 经 `ui.registerImage` 声明的图片元素（声明序；撤销后立即消失）。 */
    val uiImages: List<ModUiElement.Image> get() = uiImpl.images

    /**
     * 本 context 的界面贡献替身真身：登记项（[RecordingUi.elements] / [RecordingUi.find]）、
     * 当前可见键（[RecordingUi.visibleKeys]，口径 = 生产 `InstanceUiRegistry.registeredKeys()`）、
     * 模拟点击（[RecordingUi.click]）与点击结果账本（[RecordingUi.clickOutcomes]）都在这里。
     * 其中的数据类是台架观察面，不是作者 API。
     */
    val uiRecorder: RecordingUi get() = uiImpl

    /**
     * 本 context 的资源面录制视图：底层仍是引擎同源 `ModResourceStore`，这里只多看一层
     * 「读了哪些名字、各几次」（[RecordingResources.calls] / [RecordingResources.count]）。
     * 数据类是台架观察面，不是作者 API。
     */
    val resourcesRecorder: RecordingResources get() = resourcesImpl

    /** 手动触发一个任务槽（等价「游戏日到达 + 相位匹配」）；未绑定返回 false。 */
    fun fireJob(jobId: String): Boolean = jobsImpl.fire(jobId, DEFAULT_TIME)

    /** [JobKey] 重载：作者直接传声明里的键。 */
    fun fireJob(job: JobKey): Boolean = fireJob(job.name)

    /** 派发完整事件链（四桶序），返回各处理器结果（便于断言 SINGLE 截断）。 */
    fun dispatchEvent(eventKey: String): List<EventResult> = eventsImpl.fireAll(eventKey)

    /** 派发单桶事件，返回该桶实际跑过的处理器结果。 */
    fun dispatchEvent(eventKey: String, bucket: EventBucket): List<EventResult> =
        eventsImpl.fire(eventKey, bucket)

    /** 派发一个钩子槽（缺省 Prefix 相位；`Replace` 后短路）。 */
    fun dispatchHook(
        targetKey: String,
        phase: HookPhase = HookPhase.PREFIX,
        args: Array<ModCallArg> = emptyArray(),
    ): HookResult = hooksImpl.dispatch(targetKey, phase, args)

    /**
     * 某 hook 槽是否已被撤销（只读；`continueOnHandlerFailure=false` 的槽首次处理器异常后为 true，
     * 同一槽重新注册即解除。见 `RecordingHooks`）。
     */
    fun isHookSlotRetired(
        targetKey: String,
        phase: HookPhase = HookPhase.PREFIX,
        packageId: String = this.packageId,
    ): Boolean = hooksImpl.isSlotRetired(targetKey, phase, packageId)

    /** 已撤销的 hook 槽数（只读，会话级）。 */
    val retiredHookSlots: Int get() = hooksImpl.retiredSlotCount

    /** 手动派发一个相位，返回派发的处理器数。 */
    fun firePhase(phase: String): Int = phasesImpl.fire(phase, DEFAULT_TIME)

    /**
     * 预置整型全局变量（作者要构造「当前日期/金钱」类前置条件时用）。
     *
     * **预置通道，不经授权闸**：这是用例的 arrange 手段（等价「世界本来就是这个状态」），
     * 不是被测的 [GameStateApi.setIntVar] —— 后者走 [gameState] 的授权闸镜像。
     */
    fun setGameIntVar(name: String, value: Long) {
        gameStateImpl.presetIntVar(name, value)
    }

    /** 预置字符串全局变量（预置通道，口径同 [setGameIntVar]，不经授权闸）。 */
    fun setGameStrVar(name: String, value: String) {
        gameStateImpl.presetStrVar(name, value)
    }

    private companion object {
        val DEFAULT_TIME: GameTime = GameTime(day = 1L)
    }
}

/**
 * 一个 mod 包的受控写授权表（ModDX4-B1 台架形态；[TestWorld.of] 按 manifests 计算）。
 *
 * 键一律大写归一（与生产 [com.eratw.port.core.mod.ModDx4WriteGate] 同口径）；[grants] =
 * manifest `write-grants` 段（归一键 → 授权类型位），[ownDims] = 本包 `global-dims` 段
 * （归一键 → 条目；`addressable` = 0 维/1 维非角色可按名寻址，与生产声明期校验同判）。
 */
internal class BenchWriteAuth(
    val grants: Map<String, Boolean>,
    val ownDims: Map<String, BenchOwnDimEntry>,
) {

    /** 本包自家 global-dim 的授权条目（类型位 + 本写面可否按名寻址）。 */
    internal class BenchOwnDimEntry(val isStr: Boolean, val addressable: Boolean)

    companion object {
        /** 无授权（手工构造的 context / 未声明写面）：生产同口径，全部键写 IAE。 */
        val EMPTY = BenchWriteAuth(emptyMap(), emptyMap())

        /** 键的大写归一（生产 NamePolicy.canonical 同口径：trim + 大写）。 */
        internal fun canonical(name: String): String = name.trim().uppercase(java.util.Locale.ROOT)
    }
}

/**
 * 台架的 gameState 作者面：读侧委托 [reads]（哨兵语义），写侧授权闸镜像。
 *
 * 授权判定与失败文案与生产 `ModDx4WriteGate.authorize` 逐字对齐（三态：自家变量 / 已授权 /
 * 未授权，各带类型一致性与可寻址形态检查）—— 台架不另写第二套语义，作者在台架看到的 IAE
 * 与真机同文。通过后经构造注入的 [submit]（= 该 context 的 [RuntimeScheduler]）排队；
 * 台架 `TestRuntime` 是同步内联，写立即生效。
 */
private class GatedBenchGameState(
    private val packageId: String,
    private val reads: TestGameState,
    private val auth: BenchWriteAuth,
    private val submit: (() -> Unit) -> Unit,
) : GameStateApi {

    override fun getIntVar(name: String): Long = reads.getIntVar(name)

    override fun getStrVar(name: String): String = reads.getStrVar(name)

    override fun hasIntVar(name: String): Boolean = reads.hasIntVar(name)

    override fun hasStrVar(name: String): Boolean = reads.hasStrVar(name)

    override fun setIntVar(name: String, value: Long) {
        authorize(name, wantStr = false)
        submit { reads.presetIntVar(name, value) }
    }

    override fun setStrVar(name: String, value: String) {
        authorize(name, wantStr = true)
        submit { reads.presetStrVar(name, value) }
    }

    /** 授权判定（生产 `ModDx4WriteGate.authorize` 镜像：自家变量 / 已授权 / 未授权三态）。 */
    private fun authorize(name: String, wantStr: Boolean) {
        val canonical = BenchWriteAuth.canonical(name)
        val own = auth.ownDims[canonical]
        if (own != null) {
            if (!own.addressable) {
                throw IllegalArgumentException(
                    "全局变量写不可寻址：$packageId/$name（本包声明的变量是 2 维以上形态，" +
                        "本写面与读侧同口径只达 0 维标量与 1 维非角色数组的裸引用格）",
                )
            }
            requireType(own.isStr, wantStr, name, "本包 global-dims 声明")
            return
        }
        val granted = auth.grants[canonical]
            ?: throw IllegalArgumentException(
                "全局变量写未授权：$packageId/$name（write-grants 未声明该键；" +
                    "未声明的键不可写 —— fail-closed，不静默吞写）",
            )
        requireType(granted, wantStr, name, "write-grants 声明")
    }

    /** 类型一致性（生产同文案口径：INT 授权只走整型写、STR 只走字符串写，不隐式转）。 */
    private fun requireType(grantedIsStr: Boolean, wantStr: Boolean, name: String, via: String) {
        if (grantedIsStr != wantStr) {
            throw IllegalArgumentException(
                "全局变量写类型不符：$packageId/$name（$via 为 " +
                    "${if (grantedIsStr) "STR" else "INT"}，本写入口是 " +
                    "${if (wantStr) "STR" else "INT"} 通道；不隐式转换）",
            )
        }
    }
}
