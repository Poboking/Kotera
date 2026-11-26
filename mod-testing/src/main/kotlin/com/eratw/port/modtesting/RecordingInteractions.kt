package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.InteractionApi
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionHookContext
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration

/**
 * 录制型 [InteractionApi]：**测试替身，不是生产实现**。
 *
 * 作者声明的交互阶段钩子来自 Manifest 的 `interactionHook`（槽表见 [MemorySchema]）；
 * [registerHook] 只允许「本包 + 已声明阶段」，[run] 手动跑一次交互相位机，不必启动 `:core-vm` 引擎。
 *
 * 相位机照生产 `InteractionPipeline`（core/mod/InteractionPipeline.kt）：
 *
 * - 相位序 `PRECONDITION → PRE → MAIN → POST → SETTLEMENT`；`main` 回调夹在 MAIN 钩子之后；
 * - 若 BEFORE_MAIN（PRECONDITION/PRE）里出现 [InteractionResult.REPLACE]，**MAIN 钩子与 main 都跳过**，
 *   直接进 AFTER_MAIN（POST/SETTLEMENT）；
 * - [InteractionResult.INTERRUPT] 立即中止整次交互（`interrupted = true`）；
 * - 同一相位内：先按声明里的**包首次出现序**，再按包名字典序，最后按注册序；
 * - 处理器抛异常按失败隔离（`failures` 记包名、当 CONTINUE 继续），只有嵌套深度超限重新抛；
 *   隔离面与生产一致：**任意 Throwable** 都被隔离（生产 `InteractionPipeline.invoke` 同口径），
 *   不会中断后续处理器与后续相位。
 *
 * ## 与生产的差异
 *
 * 台架不跑真实交互结算（`main` 由用例传入），不做渲染/日志；深度超限用台架自己的
 * [InteractionDepthExceededException]（生产类在 `:core-vm`，夹具不能反向依赖）。
 * 生产把失败写进 `RuntimeDiagnostics`，台架只回传被隔离的包名列表（`failures`）。
 */
class RecordingInteractions internal constructor(
    private val packageId: String,
    private val table: InteractionRecordTable,
) : InteractionApi {

    /** 本包已注册 handler 的相位（按注册序去重）。断言「作者注册项」用。 */
    val registeredPhases: List<InteractionPhase> get() = table.phasesOf(packageId)

    /** 最近一次 [run] 的结果（`null` = 还没跑过）。 */
    val lastRun: InteractionRunResult? get() = table.lastRun

    /** 最近一次 [run] 实际执行到的相位序（含 MAIN；被 INTERRUPT/REPLACE 跳过的不记）。 */
    val lastPhaseOrder: List<InteractionPhase> get() = table.lastPhaseOrder

    /** 当前嵌套深度（正常调用栈外恒为 0）。 */
    val depth: Int get() = table.depth

    override fun registerHook(phase: InteractionPhase, handler: InteractionHandler): Registration =
        table.registerHook(phase, packageId, handler)

    override fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult =
        table.run(context, main)

    /**
     * 便利入口：攒一次交互上下文并跑完相位机。
     *
     * @param main 被夹在 MAIN 钩子之后执行的「原交互体」，缺省不产出值。
     */
    fun runOnce(
        actor: EntityId,
        targets: List<EntityId>,
        interactionId: String = DEFAULT_INTERACTION_ID,
        primaryTarget: EntityId = targets.firstOrNull() ?: actor,
        main: () -> ModValue? = { null },
    ): InteractionRunResult = run(
        InteractionContext(
            interactionId = interactionId,
            actor = actor,
            targets = targets,
            primaryTarget = primaryTarget,
        ),
        main,
    )

    companion object {
        /** 台架缺省交互 id（用例只关心相位机时可省参）。 */
        const val DEFAULT_INTERACTION_ID: String = "test.interaction"
    }
}

/** 交互相位机嵌套深度超限（生产同判例：`InteractionDepthExceededException`）。 */
class InteractionDepthExceededException(
    val maxDepth: Int,
) : IllegalStateException("交互嵌套深度超过上限 $maxDepth（疑似 handler 里递归触发交互）")

/** 一个会话一份的交互阶段绑定表（语义照生产 `InteractionPipeline`）。 */
internal class InteractionRecordTable(
    private val slots: List<MemorySchema.InteractionSlotSpec>,
) {

    private class Binding(val packageId: String, val phase: InteractionPhase, val handler: InteractionHandler)

    private val bindings = mutableListOf<Binding>()

    /** 包声明序（声明里该包首次出现的槽序）；决定同相位的跨包先后。 */
    private val packageRank: Map<String, Int> = slots
        .sortedBy { it.declarationOrder }
        .map { it.packageId }
        .distinct()
        .withIndex()
        .associate { (index, pkg) -> pkg to index }

    private val stack = ArrayDeque<InteractionContext>()

    var lastRun: InteractionRunResult? = null
        private set

    var lastPhaseOrder: List<InteractionPhase> = emptyList()
        private set

    val depth: Int get() = stack.size

    fun phasesOf(packageId: String): List<InteractionPhase> {
        val out = LinkedHashSet<InteractionPhase>()
        for (binding in bindings) if (binding.packageId == packageId) out += binding.phase
        return out.toList()
    }

    fun registerHook(phase: InteractionPhase, packageId: String, handler: InteractionHandler): Registration {
        require(slots.any { it.phase == phase && it.packageId == packageId }) {
            "交互阶段钩子未声明：$packageId/$phase（先经 Manifest 声明）"
        }
        val binding = Binding(packageId, phase, handler)
        bindings += binding
        return object : Registration {
            override fun unregister() {
                bindings.remove(binding)
            }
        }
    }

    fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult {
        if (stack.size >= MAX_DEPTH) throw InteractionDepthExceededException(MAX_DEPTH)
        val runner = PhaseRunner(context, snapshotHandlers(), RunState(), mutableListOf(), mutableListOf())
        stack.addLast(context)
        try {
            val interrupted = when {
                runner.runBlock(BEFORE_MAIN) -> true
                runner.state.replaced -> runner.runBlock(AFTER_MAIN)
                runner.runBlock(listOf(InteractionPhase.MAIN)) -> true
                else -> {
                    if (!runner.state.replaced) runner.state.result = main()
                    runner.runBlock(AFTER_MAIN)
                }
            }
            return runner.finish(interrupted)
        } finally {
            stack.removeLast()
        }
    }

    /**
     * 单次 [run] 的阶段执行器：持有本次快照/状态，把原本的长参数列表收进字段。
     * 失败隔离面照生产 `InteractionPipeline.invoke`：任意 Throwable 记包名后按 CONTINUE 继续，
     * 只有嵌套深度超限重新抛（生产 `InteractionPipeline.kt:167-190` 同口径）。
     */
    private inner class PhaseRunner(
        private val context: InteractionContext,
        private val snapshot: Map<InteractionPhase, List<Binding>>,
        val state: RunState,
        private val failures: MutableList<String>,
        private val startedPhases: MutableList<InteractionPhase>,
    ) {
        /** 依次跑一个阶段块；返回 true 表示被 INTERRUPT 中止。 */
        fun runBlock(phases: List<InteractionPhase>): Boolean {
            for (phase in phases) {
                startedPhases += phase
                if (runPhase(phase, snapshot[phase].orEmpty())) return true
            }
            return false
        }

        private fun runPhase(phase: InteractionPhase, handlers: List<Binding>): Boolean {
            for (binding in handlers) {
                val hookContext = InteractionHookContext(binding.packageId, context, phase)
                when (invoke(binding, hookContext)) {
                    InteractionResult.CONTINUE -> Unit
                    InteractionResult.INTERRUPT -> return true
                    InteractionResult.REPLACE -> {
                        state.result = hookContext.replacedResult
                        state.replaced = true
                        if (phase == InteractionPhase.MAIN) break
                    }
                }
            }
            return false
        }

        private fun invoke(binding: Binding, hookContext: InteractionHookContext): InteractionResult =
            try {
                binding.handler.onInteraction(hookContext)
            } catch (exception: InteractionDepthExceededException) {
                throw exception
            } catch (_: Throwable) {
                failures += binding.packageId
                InteractionResult.CONTINUE
            }

        /** 收尾并记录 [lastRun] / [lastPhaseOrder]。 */
        fun finish(interrupted: Boolean): InteractionRunResult {
            val result = InteractionRunResult(
                returnValue = state.result,
                interrupted = interrupted,
                replaced = state.replaced,
                failures = failures.toList(),
            )
            lastRun = result
            lastPhaseOrder = startedPhases.toList()
            return result
        }
    }

    /** run 开始时锁定 handler 快照并排序：相位序 → 包声明序 → 包名 → 注册序。 */
    private fun snapshotHandlers(): Map<InteractionPhase, List<Binding>> =
        bindings
            .sortedWith(
                compareBy(
                    { it.phase.ordinal },
                    { packageRank[it.packageId] ?: Int.MAX_VALUE },
                    { it.packageId },
                ),
            )
            .groupBy { it.phase }

    private class RunState {
        var result: ModValue? = null
        var replaced: Boolean = false
    }

    private companion object {
        const val MAX_DEPTH: Int = 32
        val BEFORE_MAIN: List<InteractionPhase> = listOf(InteractionPhase.PRECONDITION, InteractionPhase.PRE)
        val AFTER_MAIN: List<InteractionPhase> = listOf(InteractionPhase.POST, InteractionPhase.SETTLEMENT)
    }
}
