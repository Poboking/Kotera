package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.DeclaredInteractionHook
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionHookContext
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * 多目标互动执行引擎（§20.3，ABI-11 Candidate；纯 JVM，无静态可变全局）。
 *
 * ## 五阶段执行序
 *
 * ```
 * PRECONDITION → PRE → MAIN → POST → SETTLEMENT
 * ```
 *
 * 阶段钩子按「priority 升序 → 包链接序 → 包内注册序」确定性执行；MAIN 阶段的钩子先于主流程运行
 * （返回 REPLACE 可跳过主流程），[main] 是默认主流程。BEFORE_MAIN 阶段已被 REPLACE
 * 时 MAIN 阶段（钩子与主流程）整体跳过，POST/SETTLEMENT 照常运行。
 *
 * ## 中断协议
 *
 * - [InteractionResult.CONTINUE]：继续；
 * - [InteractionResult.INTERRUPT]：立即中止本次管线（不跑 MAIN 及其后阶段），返回已得结果；
 * - [InteractionResult.REPLACE]：以 [InteractionHookContext.replacedResult] 替换结果。
 *   PRE 阶段替换 ⇒ 跳过 MAIN、继续 POST/SETTLEMENT；POST 阶段替换 ⇒ 改写结果供 SETTLEMENT。
 *
 * **不取消原版 ERB 逻辑**（ADR-005）：本引擎只作用于 Mod 互动管线；原版流程替换经
 * Override + CALL SUPER（D-3）。
 *
 * ## 嵌套与失败恢复（§19.4.2 契约的可执行形态）
 *
 * - 上下文栈：每次 [run] push 一帧，正常/异常返回都 pop（finally 保证 unwind）；
 *   内层帧独立，外层上下文逐字不变；内层异常不会泄漏内层上下文到外层。
 * - 深度闸：栈深达到 [maxDepth] 时拒绝继续 push（抛
 *   [InteractionDepthExceededException]），不栈溢出崩溃。
 * - 失败隔离：单个处理器抛异常 = 记入结果的 failures（PackageId），管线继续
 *   （§18.4 注册隔离）；主流程异常不被吞，照常抛给外层。
 * - 快照隔离：每次 [run] 开始时锁定处理器列表，本轮中途注册/退订不影响本轮。
 */
class InteractionPipeline private constructor(
    private val maxDepth: Int,
    private val declarations: List<DeclaredInteractionHook>,
    private val diagnostics: RuntimeDiagnostics? = null,
) {
    private data class BindingKey(val phase: InteractionPhase, val packageId: String)

    private val packageRank: Map<String, Int> = buildMap {
        declarations.map { it.packageId }.distinct().forEachIndexed { index, pkg -> put(pkg, index) }
    }
    private val declaredPriority = declarations.associate {
        BindingKey(it.declaration.phase, it.packageId) to it.declaration.priority
    }

    private val handlers = LinkedHashMap<BindingKey, MutableList<InteractionHandler>>()
    private val stack = ArrayDeque<InteractionContext>()

    /** 当前嵌套深度。 */
    fun depth(): Int = stack.size

    /** 当前最内层上下文；无嵌套时为 null。 */
    fun current(): InteractionContext? = stack.lastOrNull()

    /**
     * 会话级单次耗时预算（§18.5 阈值诊断）：装配侧装上后，每次作者处理器调用按
     * interactionId 计时，越 [ModHandlerBudget.thresholdNanos] 记一条 INTERACTION 诊断并计数；
     * 不中断、不改返回值。为 null（缺省）时零开销直调 —— 与既有调用逐位不变。
     */
    internal var handlerBudget: ModHandlerBudget? = null

    /**
     * 订阅一个交互阶段钩子（须在 Manifest 声明过该阶段）。同一包同阶段可注册多个，
     * 按注册序执行。
     *
     * @throws IllegalArgumentException 该包的该阶段钩子未声明
     */
    fun registerHook(
        phase: ModInteractionPhase,
        packageId: String,
        handler: InteractionHandler,
    ): Registration {
        val enginePhase = toEnginePhase(phase)
        require(declarations.any { it.declaration.phase == enginePhase && it.packageId == packageId }) {
            "交互阶段钩子未声明：$packageId/$phase（先经 Manifest 声明）"
        }
        val key = BindingKey(enginePhase, packageId)
        val list = handlers.getOrPut(key) { ArrayList() }
        list += handler
        return object : Registration {
            override fun unregister() {
                handlers[key]?.remove(handler)
            }
        }
    }

    /** 诊断只读：本包在全部交互阶段的处理器数（遍历累加，不构造快照；未知包 0）。 */
    internal fun handlerCountOf(packageId: String): Int {
        var count = 0
        for ((key, list) in handlers) {
            if (key.packageId == packageId) count += list.size
        }
        return count
    }

    /**
     * 只读查询：是否存在任何已注册处理器（ModDX-4 M4：宿主回合级触发据此零成本旁路——
     * 无注册 = 不建上下文、不跑管线、零日志）。注册与声明一一对应（[registerHook]
     * 对未声明阶段强拒），故本查询亦等价「无 mod 声明并注册 interaction hook」。
     */
    fun hasRegisteredHandlers(): Boolean = handlers.isNotEmpty()

    /**
     * 运行一次多目标互动。[main] 是主流程；返回最终结果与失败包列表。
     *
     * @throws InteractionDepthExceededException 嵌套深度超过 [maxDepth]
     */
    fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult {
        if (stack.size >= maxDepth) throw InteractionDepthExceededException(maxDepth)
        val snapshot = snapshotHandlers()
        stack.addLast(context)
        try {
            val failures = ArrayList<String>()
            var result: ModValue? = null
            var replaced = false

            for (phase in BEFORE_MAIN) {
                for ((pkg, handler) in snapshot[phase].orEmpty()) {
                    val hookContext = InteractionHookContext(pkg, context, toModPhase(phase))
                    when (invoke(handler, hookContext, pkg, failures)) {
                        InteractionResult.CONTINUE -> Unit
                        InteractionResult.INTERRUPT -> return InteractionRunResult(result, true, replaced, failures)
                        InteractionResult.REPLACE -> {
                            result = hookContext.replacedResult
                            replaced = true
                        }
                    }
                }
            }

            if (!replaced) {
                for ((pkg, handler) in snapshot[InteractionPhase.MAIN].orEmpty()) {
                    val hookContext = InteractionHookContext(pkg, context, ModInteractionPhase.MAIN)
                    when (invoke(handler, hookContext, pkg, failures)) {
                        InteractionResult.CONTINUE -> Unit
                        InteractionResult.INTERRUPT -> return InteractionRunResult(result, true, replaced, failures)
                        InteractionResult.REPLACE -> {
                            result = hookContext.replacedResult
                            replaced = true
                            break
                        }
                    }
                }
            }

            if (!replaced) result = main()

            for (phase in AFTER_MAIN) {
                for ((pkg, handler) in snapshot[phase].orEmpty()) {
                    val hookContext = InteractionHookContext(pkg, context, toModPhase(phase))
                    when (invoke(handler, hookContext, pkg, failures)) {
                        InteractionResult.CONTINUE -> Unit
                        InteractionResult.INTERRUPT -> return InteractionRunResult(result, true, replaced, failures)
                        InteractionResult.REPLACE -> {
                            result = hookContext.replacedResult
                            replaced = true
                        }
                    }
                }
            }

            return InteractionRunResult(result, interrupted = false, replaced = replaced, failures = failures)
        } finally {
            stack.removeLast()
        }
    }

    private fun invoke(
        handler: InteractionHandler,
        context: InteractionHookContext,
        packageId: String,
        failures: MutableList<String>,
    ): InteractionResult = try {
        val result = invokeMeasured(handler, context, packageId)
        when (result) {
            InteractionResult.CONTINUE ->
                recordInteraction(context, packageId, DiagnosticOutcome.OK, "OK")
            InteractionResult.INTERRUPT ->
                recordInteraction(context, packageId, DiagnosticOutcome.SKIPPED, "INTERRUPT")
            InteractionResult.REPLACE ->
                recordInteraction(context, packageId, DiagnosticOutcome.OK, "REPLACE")
        }
        result
    } catch (t: InteractionDepthExceededException) {
        recordInteraction(context, packageId, DiagnosticOutcome.FAILED, "HANDLER_THREW", t)
        throw t
    } catch (t: Throwable) {
        failures += packageId
        recordInteraction(context, packageId, DiagnosticOutcome.FAILED, "HANDLER_THREW", t)
        InteractionResult.CONTINUE
    }

    /**
     * 调一次作者处理器：装了单次耗时预算才计时（key = interactionId），否则零开销直调。
     *
     * 不吞异常：处理器抛出的异常照原样穿出（外层 [invoke] 的既有失败隔离接住），
     * 耗时在预算的 finally 里照记。
     */
    private fun invokeMeasured(
        handler: InteractionHandler,
        context: InteractionHookContext,
        packageId: String,
    ): InteractionResult {
        val budget = handlerBudget
        return if (budget == null) {
            handler.onInteraction(context)
        } else {
            budget.run(packageId, DiagnosticDomain.INTERACTION, context.interaction.interactionId) {
                handler.onInteraction(context)
            }
        }
    }

    /** 写一条 INTERACTION 诊断；null diagnostics 直接旁路。 */
    private fun recordInteraction(
        context: InteractionHookContext,
        packageId: String,
        outcome: DiagnosticOutcome,
        reasonCode: String,
        cause: Throwable? = null,
    ) {
        val diagnostics = diagnostics ?: return
        if (cause == null) {
            diagnostics.record(
                day = null,
                phase = null,
                domain = DiagnosticDomain.INTERACTION,
                packageId = packageId,
                key = context.interaction.interactionId,
                outcome = outcome,
                reasonCode = reasonCode,
            )
        } else {
            diagnostics.record(
                day = null,
                phase = null,
                domain = DiagnosticDomain.INTERACTION,
                packageId = packageId,
                key = context.interaction.interactionId,
                outcome = outcome,
                reasonCode = reasonCode,
                detail = { cause.javaClass.simpleName.ifEmpty { cause.javaClass.name } },
            )
        }
    }

    private fun snapshotHandlers(): Map<InteractionPhase, List<Pair<String, InteractionHandler>>> {
        val out = LinkedHashMap<InteractionPhase, MutableList<Pair<String, InteractionHandler>>>()
        val keys = handlers.keys.sortedWith(
            compareBy(
                { it.phase.ordinal },
                { declaredPriority[it] ?: 0 },
                { packageRank[it.packageId] ?: Int.MAX_VALUE },
                { it.packageId },
            ),
        )
        for (key in keys) {
            val list = out.getOrPut(key.phase) { ArrayList() }
            for (handler in handlers[key].orEmpty()) list += key.packageId to handler
        }
        return out
    }

    companion object {
        private val BEFORE_MAIN = listOf(InteractionPhase.PRECONDITION, InteractionPhase.PRE)
        private val AFTER_MAIN = listOf(InteractionPhase.POST, InteractionPhase.SETTLEMENT)

        /** 从声明事务快照的交互钩子声明构建。[maxDepth] 是嵌套深度闸（默认 32）。 */
        fun of(
            declarations: List<DeclaredInteractionHook>,
            maxDepth: Int = 32,
            diagnostics: RuntimeDiagnostics? = null,
        ): InteractionPipeline {
            require(maxDepth > 0) { "maxDepth 必须为正：$maxDepth" }
            return InteractionPipeline(maxDepth, declarations.toList(), diagnostics)
        }

        private fun toEnginePhase(phase: ModInteractionPhase): InteractionPhase = when (phase) {
            ModInteractionPhase.PRECONDITION -> InteractionPhase.PRECONDITION
            ModInteractionPhase.PRE -> InteractionPhase.PRE
            ModInteractionPhase.MAIN -> InteractionPhase.MAIN
            ModInteractionPhase.POST -> InteractionPhase.POST
            ModInteractionPhase.SETTLEMENT -> InteractionPhase.SETTLEMENT
        }

        private fun toModPhase(phase: InteractionPhase): ModInteractionPhase = when (phase) {
            InteractionPhase.PRECONDITION -> ModInteractionPhase.PRECONDITION
            InteractionPhase.PRE -> ModInteractionPhase.PRE
            InteractionPhase.MAIN -> ModInteractionPhase.MAIN
            InteractionPhase.POST -> ModInteractionPhase.POST
            InteractionPhase.SETTLEMENT -> ModInteractionPhase.SETTLEMENT
        }
    }
}

/** 互动嵌套深度超过上限（显式拒绝，不栈溢出崩溃）。 */
class InteractionDepthExceededException(val maxDepth: Int) :
    IllegalStateException("互动嵌套深度超限：$maxDepth（拒绝继续 push）")
