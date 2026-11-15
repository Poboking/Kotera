package com.eratw.port.core.mod

import com.eratw.port.modapi.EntityId
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
) : HookApi {
    override fun register(targetKey: String, phase: HookPhase, handler: HookHandler): Registration =
        pipeline.bind(targetKey, phase, packageId, handler)
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
