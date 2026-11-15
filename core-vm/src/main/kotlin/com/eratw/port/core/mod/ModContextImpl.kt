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
import com.eratw.port.modapi.InteractionApi
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.JobApi
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModLogger
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.PhaseApi
import com.eratw.port.modapi.PhaseHandler
import com.eratw.port.modapi.QueryApi
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RelationshipApi
import com.eratw.port.modapi.RosterProjectionApi
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.StorageApi
import com.eratw.port.modapi.UiApi

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
 * 真实主执行调度器接线属组长）、[events]（显式 fail-closed，见 [FailClosedEventApi]）、
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
    val hookPipeline = HookPipeline.of(snapshot.hookDeclarations)
    val phaseRegistry = PhaseRegistry.of(snapshot.phaseDeclarations)
    val jobScheduler = JobScheduler.of(
        snapshot.jobDeclarations,
        snapshot = null,
        templates = snapshot.jobTemplateDeclarations,
    )
    val interactionPipeline = InteractionPipeline.of(snapshot.interactionHookDeclarations)

    private val hookAdapter = HookApiAdapter(hookPipeline, packageId)
    private val phaseAdapter = PhaseApiAdapter(phaseRegistry, packageId)
    private val jobAdapter = JobApiAdapter(jobScheduler, packageId)
    private val interactionAdapter = InteractionApiAdapter(interactionPipeline, packageId)
    private val kernelApi = KernelEntityApi(entityKernel, pkgShortId)
    private val uiRegistry = InstanceUiRegistry()

    // 注册项登记：只把适配器返回的 Registration 交给宿主的 per-mod 收集器，原样返回。
    // 包装层不含任何注册判定，判定完全委托既有适配器实例（不重写适配器）。
    private val track: (Registration) -> Registration = { registration ->
        registrationSink(registration)
        registration
    }

    override val logger: ModLogger = PackageLogger(packageId, logSink)
    override val entities: EntityApi = kernelApi
    override val relationships: RelationshipApi = kernelApi
    // E 车：投射公开面。注入真投射器则转发，未注入则四入口显式 fail-closed（不半装）。
    override val roster: RosterProjectionApi = KernelRosterProjectionApi(projector, packageId)
    override val events: EventApi = FailClosedEventApi()

    override val hooks: HookApi = object : HookApi {
        override fun register(targetKey: String, phase: HookPhase, handler: HookHandler): Registration =
            track(hookAdapter.register(targetKey, phase, handler))
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
    override val gameState: GameStateApi = InjectedGameState(intVarView, strVarView)

    override val ui: UiApi = object : UiApi {
        override fun registerInteraction(labelKey: String, onClick: () -> Unit): Registration =
            track(uiRegistry.registerInteraction(labelKey, onClick))
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
        storageView.target = newStore.apiFor(packageId)
    }

    /** 装配产物的可观测面：ui 登记表的当前键与命中（真实输入状态机接线属组长）。 */
    fun uiRegisteredKeys(): Set<String> = uiRegistry.registeredKeys()

    /** 命中并触发一个 ui 贡献项；撤销后立即返回 false。 */
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

/** mod 日志面：实例级、可注入 sink、按 packageId 归属，测试用内存收集，不设静态全局。 */
private class PackageLogger(
    private val packageId: String,
    private val sink: (String) -> Unit,
) : ModLogger {
    override fun info(message: String) = emit("INFO", message, null)
    override fun warn(message: String) = emit("WARN", message, null)
    override fun error(message: String, cause: Throwable?) = emit("ERROR", message, cause)

    private fun emit(level: String, message: String, cause: Throwable?) {
        val tail = cause?.let { " cause=${it::class.java.simpleName}:${it.message}" } ?: ""
        sink("[$packageId][$level] $message$tail")
    }
}

/**
 * 事件能力面的显式 fail-closed 实现（组长裁定 §3.2.1）。
 *
 * 引擎 EventRegistry 是构建期不可变、从链接产物 LSC 事件视图派生的表，承载的是 ERB 事件函数；
 * 今天没有运行期注册 Kotlin 处理器的钩子缝。把 Kotlin 处理器接进贡献点、与 ERB 事件按四桶序
 * 混排是 M2-6 的脏活。本波不造第二套事件系统、不登记后假派发、不改 EventRegistry 与
 * SymbolCatalog、不写「已接」；也不返回静默吞掉的 no-op 注册。register 一律当场抛异常，
 * 让调用方清楚知道生产消费点未接。真实接线属 M2-6，由组长处理。
 */
private class FailClosedEventApi : EventApi {
    override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration =
        throw UnsupportedOperationException(
            "M2-6：events.register($eventKey/$bucket) 生产消费点未接；" +
                "EventRegistry 是构建期不可变表，今天没有运行期注册 Kotlin 处理器的钩子缝，本波不造第二套事件系统",
        )
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
 * 只读 curated 视图由构造期注入；未注入时给干净缺省 0 与空串。
 * 这是未接线时的缺省，不是真实 VariableStore 读数；真实授权视图接线属组长（K3）。
 */
private class InjectedGameState(
    private val intVar: (String) -> Long,
    private val strVar: (String) -> String,
) : GameStateApi {
    override fun getIntVar(name: String): Long = intVar(name)
    override fun getStrVar(name: String): String = strVar(name)
}

/** 实例级界面贡献登记表：registerInteraction 返回可撤销的 Registration，撤销后立即不再命中。 */
private class InstanceUiRegistry : UiApi {
    private val actions = LinkedHashMap<String, () -> Unit>()

    override fun registerInteraction(labelKey: String, onClick: () -> Unit): Registration {
        actions[labelKey] = onClick
        return object : Registration {
            override fun unregister() {
                actions.remove(labelKey)
            }
        }
    }

    fun registeredKeys(): Set<String> = LinkedHashSet(actions.keys)

    fun click(labelKey: String): Boolean {
        val action = actions[labelKey] ?: return false
        action()
        return true
    }
}
