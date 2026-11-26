package com.eratw.port.core.mod

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EventApi
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
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
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.PhaseApi
import com.eratw.port.modapi.PhaseHandler
import com.eratw.port.modapi.Registration

/**
 * §20 四项能力的 mod-api facade 适配器（K3 的引擎侧半边）。
 *
 * 每个适配器把**本 Mod 的 PackageId** 注入引擎的声明校验与执行序，mod 侧 API 形状保持
 * 干净（`register`/`subscribe`/`bind` 不暴露包身份）。生产装配（`ModContext` 实现、
 * 生命周期接线）仍归 K3 脏活；本文件只证 facade 与引擎之间的缝。
 */

/** `HookApi` → [HookPipeline]：绑定到本包已声明的 hook 槽。 */
class HookApiAdapter(
    private val pipeline: HookPipeline,
    private val packageId: String,
    /**
     * 函数目录供给（MODDX4-B3 组长接线）：直连宿主侧共享目录的同一份集合（归一在供给方），
     * 缺省恒空集 = 与灌缝前逐位一致（不校验）。生产路径由 [ModContextImpl] 传入，
     * 直用本适配器不供给则 `knownFunctionKeys()` 答空集——比 UOE 更诚实的空集口径
     * （空集语义 = 「目录未灌」而非「目录为空」，与 events 台架缺省同形）。
     */
    private val knownKeysProvider: () -> Set<String> = { emptySet() },
) : HookApi {
    override fun register(targetKey: String, phase: HookPhase, handler: HookHandler): Registration =
        pipeline.bind(targetKey, phase, packageId, handler)

    /** 只读投影：与 [register] 同一条共享槽表的单源投影（序口径见 [HookPipeline.registrations]）。 */
    override fun registrations(): List<HookRegistration> = pipeline.registrations()

    /** 只读快照（MODDX4-B3）：直连 [knownKeysProvider] 的同一份集合，不二记。 */
    override fun knownFunctionKeys(): Set<String> = knownKeysProvider()
}

/**
 * `EventApi` → [EventDispatchRegistry]：注册时注入本包身份，桶位与派发序归引擎侧。
 *
 * 与 [HookApiAdapter] 的差别：事件处理器**不需要** Manifest 声明（事件名是 base 语料里已有的
 * 函数键，mod 只是搭进同一个四桶链；mod 自定义事件域（A1）的键经 [declare] 声明后同此），
 * 所以这里没有「槽未声明」的校验；`ONLY` 桶的拒绝在
 * [EventDispatchRegistry.register] 里单点判（含指引文案）。
 */
class EventApiAdapter(
    private val registry: EventDispatchRegistry,
    private val packageId: String,
) : EventApi {
    override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration =
        registry.register(eventKey, bucket, packageId, handler)

    /** 自定义事件话题声明（A1）：归一 / 语料相撞闸在 [EventDispatchRegistry.declare] 单点判。 */
    override fun declare(eventKey: String) {
        registry.declare(eventKey, packageId)
    }

    /**
     * 主动触发（AE + AG + AL 车）：归一 + 存在性闸在 [EventDispatchRegistry.trigger] 里；线程闸 /
     * run 内与事件派发中的延迟入队 / 连环触发上限都在 [ModEventTriggerWiring.trigger] 里。本适配器
     * 只补上本包身份（未知名拒绝消息要能指回是哪个 mod 触发的）。
     */
    override fun trigger(eventKey: String) {
        registry.trigger(eventKey, packageId)
    }

    /** 只读查询：归一口径与共享 [registry] 的 known 闸同源（同一个 `canonical`）。 */
    override fun exists(eventKey: String): Boolean = registry.exists(eventKey)

    /** 只读快照：源与 [exists] 同一个共享注册表，会话内装配一次全 mod 可见。 */
    override fun knownEventKeys(): List<String> = registry.knownEventKeys()
}

/** `PhaseApi` → [PhaseRegistry]：订阅时注入本包身份（派发序按包链接序）。 */
class PhaseApiAdapter(
    private val registry: PhaseRegistry,
    private val packageId: String,
) : PhaseApi {
    override fun subscribe(phase: String, handler: PhaseHandler): Registration =
        registry.subscribe(phase, packageId, handler)
}

/**
 * `JobApi` → [JobScheduler]：静态任务槽与动态任务模板都注入本包身份。
 *
 * 五个入口一一委托调度器的同名包作用域方法；`bindTemplate` 返回的 `Registration` 由调度器
 * 撤销模板处理器绑定（`onDisable` 语义），本适配器不另造注册表。
 */
class JobApiAdapter(
    private val scheduler: JobScheduler,
    private val packageId: String,
) : JobApi {
    override fun bind(jobId: String, handler: JobHandler): Registration =
        scheduler.bind(packageId, jobId, handler)

    override fun bindTemplate(templateKey: String, handler: JobHandler): Registration =
        scheduler.bindTemplate(packageId, templateKey, handler)

    override fun scheduleOnce(templateKey: String, atDay: Long, payloadRef: EntityId?): String =
        scheduler.scheduleOnce(packageId, templateKey, atDay, payloadRef)

    override fun cancel(instanceKey: String): Boolean =
        scheduler.cancel(packageId, instanceKey)

    override fun reschedule(instanceKey: String, atDay: Long): Boolean =
        scheduler.reschedule(packageId, instanceKey, atDay)
}

/** `InteractionApi` → [InteractionPipeline]：注册时注入本包身份；运行委托给引擎管线。 */
class InteractionApiAdapter(
    private val pipeline: InteractionPipeline,
    private val packageId: String,
) : InteractionApi {
    override fun registerHook(phase: InteractionPhase, handler: InteractionHandler): Registration =
        pipeline.registerHook(phase, packageId, handler)

    override fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult =
        pipeline.run(context, main)
}
