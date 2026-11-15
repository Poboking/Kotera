package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclaredInteractionHook
import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredPhase
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.ModValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * RuntimeDiagnostics 生产写点契约闸（RT2 波 G 车 G2）。
 *
 * 钉住：HOOK / INTERACTION / PHASE / DECISION 四域及 JOB 域的记录口径、null 与
 * disabled 完全旁路、seq 顺序、JSON lines 九键，以及 DECISION reasonCode 直接复用
 * DecisionElimination.reasonCode / DecisionReasonCodes。
 */
class RuntimeDiagnosticsWireTest {

    private fun hookDecl(
        pkg: String,
        target: String = "COM300",
        phase: HookPhase = HookPhase.PREFIX,
        priority: Int = 0,
    ): DeclaredHook = DeclaredHook(
        packageId = pkg,
        declaration = HookDeclaration(
            targetKey = target,
            phase = phase,
            priority = priority,
        ),
    )

    private fun ihookDecl(pkg: String, phase: InteractionPhase): DeclaredInteractionHook =
        DeclaredInteractionHook(pkg, InteractionHookDeclaration(phase))

    private fun phaseDecl(pkg: String, name: String): DeclaredPhase =
        DeclaredPhase(pkg, PhaseDeclaration(name, 0))

    private fun jobDecl(
        pkg: String,
        jobId: String,
        phase: String = "DAY_END",
        firstDay: Long = 0L,
    ): DeclaredJob = DeclaredJob(pkg, JobDeclaration(jobId, phase, firstDay, null, 0))

    private fun candidate(
        pkg: String = "pkg.a",
        key: String = "k",
        constraints: List<String> = emptyList(),
        cooldownKey: String? = null,
        cooldownDays: Int? = null,
    ): ActionCandidate = ActionCandidate(
        providerPackage = pkg,
        candidateKey = key,
        actor = EntityId(1L),
        constraints = constraints,
        cooldownKey = cooldownKey,
        cooldownDays = cooldownDays,
        handlerKey = key,
    )

    private fun actionCtx(day: Long = 10L): ActionSelectionContext =
        ActionSelectionContext("ACTION", GameTime(day, "DAY_START"))

    private fun interactionContext(id: String): InteractionContext = InteractionContext(
        interactionId = id,
        actor = EntityId(1L),
        targets = listOf(EntityId(2L)),
    )

    private fun toModInteractionPhase(phase: InteractionPhase): ModInteractionPhase = when (phase) {
        InteractionPhase.PRECONDITION -> ModInteractionPhase.PRECONDITION
        InteractionPhase.PRE -> ModInteractionPhase.PRE
        InteractionPhase.MAIN -> ModInteractionPhase.MAIN
        InteractionPhase.POST -> ModInteractionPhase.POST
        InteractionPhase.SETTLEMENT -> ModInteractionPhase.SETTLEMENT
    }

    @Test
    fun `hook_正常异常与未声明绑定三条记录`() {
        val diag = RuntimeDiagnostics()
        val pipeline = HookPipeline.of(
            listOf(hookDecl("pkgA", priority = 0), hookDecl("pkgB", priority = 1)),
            diagnostics = diag,
        )
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgA") { HookResult.Continue }
        pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgB") { error("boom") }

        val outcome = pipeline.dispatch("COM300", emptyArray()) { ModValue.of(7L) }

        assertEquals(ModValue.of(7L), outcome.returnValue)
        assertEquals(1, outcome.failures.size)
        val records = diag.snapshot()
        assertEquals(2, records.size)
        assertEquals(DiagnosticDomain.HOOK, records[0].domain)
        assertEquals("COM300", records[0].key)
        assertEquals(DiagnosticOutcome.OK, records[0].outcome)
        assertEquals("pkgA", records[0].packageId)
        assertEquals(DiagnosticOutcome.FAILED, records[1].outcome)
        assertEquals("HANDLER_THREW", records[1].reasonCode)
        assertEquals("pkgB", records[1].packageId)
        assertEquals("IllegalStateException", records[1].detail)

        assertThrows<IllegalArgumentException> {
            pipeline.bind("COM300", ModHookPhase.PREFIX, "pkgC") { HookResult.Continue }
        }
        val rejected = diag.snapshot().last()
        assertEquals(DiagnosticDomain.HOOK, rejected.domain)
        assertEquals(DiagnosticOutcome.REJECTED, rejected.outcome)
        assertEquals("UNREGISTERED_SLOT", rejected.reasonCode)
        assertEquals("pkgC", rejected.packageId)
        assertEquals("COM300", rejected.key)
    }

    @Test
    fun `hook_无槽完全旁路不记录`() {
        val diag = RuntimeDiagnostics()
        val pipeline = HookPipeline.of(emptyList(), diagnostics = diag)
        var calls = 0
        val outcome = pipeline.dispatch("COM300", emptyArray()) {
            calls++
            ModValue.of(1L)
        }
        assertEquals(ModValue.of(1L), outcome.returnValue)
        assertEquals(1, calls)
        assertTrue(diag.snapshot().isEmpty())
    }

    @Test
    fun `hook_null完全旁路且行为逐字段不变`() {
        val plain = HookPipeline.of(listOf(hookDecl("pkgA")))
        plain.bind("COM300", ModHookPhase.PREFIX, "pkgA") { HookResult.SkipOriginal }
        val plainOutcome = plain.dispatch("COM300", emptyArray()) { ModValue.of(1L) }

        val diag = RuntimeDiagnostics()
        val wired = HookPipeline.of(listOf(hookDecl("pkgA")), diagnostics = diag)
        wired.bind("COM300", ModHookPhase.PREFIX, "pkgA") { HookResult.SkipOriginal }
        val wiredOutcome = wired.dispatch("COM300", emptyArray()) { ModValue.of(1L) }

        assertEquals(plainOutcome.returnValue, wiredOutcome.returnValue)
        assertEquals(plainOutcome.originalRan, wiredOutcome.originalRan)
        assertEquals(plainOutcome.replaced, wiredOutcome.replaced)
        assertEquals(plainOutcome.failures.size, wiredOutcome.failures.size)
        assertFalse(wiredOutcome.originalRan)
        assertEquals(1, diag.snapshot().size)
    }

    @Test
    fun `interaction_四种结果各一条记录`() {
        val diag = RuntimeDiagnostics()
        fun runOne(
            phase: InteractionPhase,
            id: String,
            handler: InteractionHandler,
        ): InteractionRunResult {
            val pipeline = InteractionPipeline.of(listOf(ihookDecl("pkgA", phase)), diagnostics = diag)
            pipeline.registerHook(toModInteractionPhase(phase), "pkgA", handler)
            return pipeline.run(interactionContext(id)) { ModValue.of(1L) }
        }

        runOne(InteractionPhase.PRECONDITION, "I-CONTINUE") { InteractionResult.CONTINUE }
        runOne(InteractionPhase.PRE, "I-INTERRUPT") { InteractionResult.INTERRUPT }
        runOne(InteractionPhase.PRE, "I-REPLACE") { ctx ->
            ctx.replacedResult = ModValue.of(9L)
            InteractionResult.REPLACE
        }
        runOne(InteractionPhase.POST, "I-THROW") { error("boom") }

        val records = diag.snapshot()
        assertEquals(4, records.size)
        assertEquals(DiagnosticDomain.INTERACTION, records[0].domain)
        assertEquals("I-CONTINUE", records[0].key)
        assertEquals(DiagnosticOutcome.OK, records[0].outcome)
        assertEquals("OK", records[0].reasonCode)
        assertEquals("I-INTERRUPT", records[1].key)
        assertEquals(DiagnosticOutcome.SKIPPED, records[1].outcome)
        assertEquals("INTERRUPT", records[1].reasonCode)
        assertEquals("I-REPLACE", records[2].key)
        assertEquals(DiagnosticOutcome.OK, records[2].outcome)
        assertEquals("REPLACE", records[2].reasonCode)
        assertEquals("I-THROW", records[3].key)
        assertEquals(DiagnosticOutcome.FAILED, records[3].outcome)
        assertEquals("HANDLER_THREW", records[3].reasonCode)
        assertEquals("IllegalStateException", records[3].detail)
        assertTrue(records.all { it.packageId == "pkgA" })
    }

    @Test
    fun `interaction_多处理器顺序与seq单调`() {
        val diag = RuntimeDiagnostics()
        val pipeline = InteractionPipeline.of(
            listOf(
                ihookDecl("pkgA", InteractionPhase.PRE),
                ihookDecl("pkgA", InteractionPhase.POST),
            ),
            diagnostics = diag,
        )
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { InteractionResult.CONTINUE }
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { InteractionResult.CONTINUE }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") { InteractionResult.CONTINUE }
        pipeline.run(interactionContext("I-ORDER")) { ModValue.of(1L) }

        val records = diag.snapshot()
        assertEquals(listOf(1L, 2L, 3L), records.map { it.seq })
        assertEquals(listOf("I-ORDER", "I-ORDER", "I-ORDER"), records.map { it.key })
        assertTrue(records.all { it.outcome == DiagnosticOutcome.OK })
    }

    @Test
    fun `interaction_null完全旁路且失败隔离不变`() {
        val plain = InteractionPipeline.of(listOf(ihookDecl("pkgA", InteractionPhase.POST)))
        plain.registerHook(ModInteractionPhase.POST, "pkgA") { error("boom") }
        val plainResult = plain.run(interactionContext("I-PLAIN")) { ModValue.of(5L) }

        val diag = RuntimeDiagnostics()
        val wired = InteractionPipeline.of(
            listOf(ihookDecl("pkgA", InteractionPhase.POST)),
            diagnostics = diag,
        )
        wired.registerHook(ModInteractionPhase.POST, "pkgA") { error("boom") }
        val wiredResult = wired.run(interactionContext("I-WIRED")) { ModValue.of(5L) }

        assertEquals(plainResult.returnValue, wiredResult.returnValue)
        assertEquals(plainResult.interrupted, wiredResult.interrupted)
        assertEquals(plainResult.replaced, wiredResult.replaced)
        assertEquals(plainResult.failures, wiredResult.failures)
        assertEquals(1, diag.snapshot().size)
        assertEquals(DiagnosticOutcome.FAILED, diag.snapshot().single().outcome)
    }

    @Test
    fun `phase_成功无处理器与异常三类记录`() {
        val diag = RuntimeDiagnostics()
        val registry = PhaseRegistry.of(
            listOf(phaseDecl("pkgA", "TICK"), phaseDecl("pkgB", "TICK")),
            diagnostics = diag,
        )
        registry.subscribe("TICK", "pkgA") { }
        registry.subscribe("TICK", "pkgB") { error("boom") }

        val report = registry.dispatch(GameTime(2, "TICK"))

        assertEquals(2, report.fired)
        assertEquals(1, report.failures.size)
        val records = diag.snapshot()
        assertEquals(2, records.size)
        assertEquals(DiagnosticDomain.PHASE, records[0].domain)
        assertEquals(DiagnosticOutcome.FAILED, records[0].outcome)
        assertEquals("HANDLER_THREW", records[0].reasonCode)
        assertEquals("pkgB", records[0].packageId)
        assertEquals("TICK", records[0].key)
        assertEquals(DiagnosticOutcome.OK, records[1].outcome)
        assertEquals("TICK", records[1].key)
        assertNull(records[1].packageId)

        val empty = PhaseRegistry.of(emptyList(), diagnostics = diag)
        empty.dispatch(GameTime(3, "DAY_END"))
        val skipped = diag.snapshot().last()
        assertEquals(DiagnosticOutcome.SKIPPED, skipped.outcome)
        assertEquals("NO_HANDLERS", skipped.reasonCode)
        assertEquals("DAY_END", skipped.key)
    }

    @Test
    fun `phase_null完全旁路且报告逐字段不变`() {
        val plainRegistry = PhaseRegistry.of(listOf(phaseDecl("pkgA", "TICK")))
        plainRegistry.subscribe("TICK", "pkgA") { error("boom") }
        val plainReport = plainRegistry.dispatch(GameTime(2, "TICK"))

        val diag = RuntimeDiagnostics()
        val wiredRegistry = PhaseRegistry.of(
            listOf(phaseDecl("pkgA", "TICK")),
            diagnostics = diag,
        )
        wiredRegistry.subscribe("TICK", "pkgA") { error("boom") }
        val wiredReport = wiredRegistry.dispatch(GameTime(2, "TICK"))

        assertEquals(plainReport.fired, wiredReport.fired)
        assertEquals(plainReport.failures.size, wiredReport.failures.size)
        assertEquals(
            plainReport.failures.single().packageId,
            wiredReport.failures.single().packageId,
        )
        assertEquals(2, diag.snapshot().size)
    }

    @Test
    fun `tick_每tick一条PHASE且JOB独立成域`() {
        val diag = RuntimeDiagnostics()
        val registry = PhaseRegistry.of(
            listOf(phaseDecl("pkgA", "TICK")),
            diagnostics = diag,
        )
        registry.subscribe("TICK", "pkgA") { }
        val scheduler = JobScheduler.of(
            listOf(jobDecl("pkgA", "j1", phase = "TICK", firstDay = 1L)),
            diagnostics = diag,
        )
        scheduler.bind("pkgA", "j1") { }
        val driver = GameTickDriver(registry, scheduler, diag)

        val report = driver.tick(GameTime(1, "TICK"))

        assertEquals(1, report.firedPhaseHandlers)
        assertEquals(1, report.firedJobs)
        val records = diag.snapshot()
        assertEquals(
            listOf(DiagnosticDomain.PHASE, DiagnosticDomain.JOB, DiagnosticDomain.PHASE),
            records.map { it.domain },
        )
        assertEquals("TICK", records[0].key)
        assertEquals(DiagnosticOutcome.OK, records[0].outcome)
        assertEquals("pkgA::j1", records[1].key)
        assertEquals("pkgA", records[1].packageId)
        assertEquals(DiagnosticOutcome.OK, records[1].outcome)
        assertEquals("TICK", records[2].key)
        assertEquals(DiagnosticOutcome.OK, records[2].outcome)
    }

    @Test
    fun `tick_无相位时记录SKIPPED`() {
        val diag = RuntimeDiagnostics()
        val driver = GameTickDriver(
            PhaseRegistry.of(emptyList(), diagnostics = diag),
            JobScheduler.of(emptyList(), diagnostics = diag),
            diag,
        )

        driver.tick(GameTime(1, ""))

        val record = diag.snapshot().single()
        assertEquals(DiagnosticDomain.PHASE, record.domain)
        assertEquals(DiagnosticOutcome.SKIPPED, record.outcome)
        assertEquals("NO_PHASE", record.reasonCode)
        assertEquals("", record.key)
    }

    @Test
    fun `decision_约束淘汰记录REJECTED并复用淘汰原因码`() {
        val diag = RuntimeDiagnostics()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 5L, diagnostics = diag)
        val api = pipeline.actionApi("pkg.a")
        api.constraint("no") { false }
        api.provide("ACTION") {
            listOf(candidate(key = "blocked", constraints = listOf("no")))
        }
        api.on("blocked") { error("should-not-run") }

        val report = pipeline.run(actionCtx())

        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        val records = diag.snapshot()
        assertEquals(2, records.size)
        assertEquals(DiagnosticDomain.DECISION, records[0].domain)
        assertEquals(DiagnosticOutcome.REJECTED, records[0].outcome)
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, records[0].reasonCode)
        assertEquals(report.eliminations.single().reasonCode, records[0].reasonCode)
        assertEquals("ACTION", records[0].key)
        assertEquals("pkg.a", records[0].packageId)
        assertEquals(DiagnosticOutcome.SKIPPED, records[1].outcome)
        assertEquals(DecisionReasonCodes.NO_CANDIDATES, records[1].reasonCode)
        assertNull(records[1].packageId)
    }

    @Test
    fun `decision_冷却淘汰记录COOLDOWN`() {
        val diag = RuntimeDiagnostics()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 7L, diagnostics = diag)
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") {
            listOf(candidate(key = "cd", cooldownKey = "cd", cooldownDays = 2))
        }
        var calls = 0
        api.on("cd") { calls++ }

        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(actionCtx(day = 10L)).outcome)
        assertEquals(ActionExecutionOutcome.NONE, pipeline.run(actionCtx(day = 11L)).outcome)

        val records = diag.snapshot()
        assertEquals(3, records.size)
        assertEquals(DiagnosticOutcome.OK, records[0].outcome)
        assertEquals(DiagnosticOutcome.REJECTED, records[1].outcome)
        assertEquals(DecisionReasonCodes.COOLDOWN, records[1].reasonCode)
        assertEquals(DiagnosticOutcome.SKIPPED, records[2].outcome)
        assertEquals(DecisionReasonCodes.NO_CANDIDATES, records[2].reasonCode)
        assertEquals(1, calls)
    }

    @Test
    fun `decision_handler异常记录FAILED_HANDLER_FAILED`() {
        val diag = RuntimeDiagnostics()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 11L, diagnostics = diag)
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "bad")) }
        api.on("bad") { error("boom") }

        val report = pipeline.run(actionCtx())

        assertEquals(ActionExecutionOutcome.FAILED, report.outcome)
        val record = diag.snapshot().single()
        assertEquals(DiagnosticDomain.DECISION, record.domain)
        assertEquals(DiagnosticOutcome.FAILED, record.outcome)
        assertEquals(DecisionReasonCodes.HANDLER_FAILED, record.reasonCode)
        assertEquals("pkg.a", record.packageId)
        assertEquals("ACTION", record.key)
        assertEquals("IllegalStateException", record.detail)
    }

    @Test
    fun `decision_无候选记录SKIPPED_NO_CANDIDATES`() {
        val diag = RuntimeDiagnostics()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 13L, diagnostics = diag)

        val report = pipeline.run(actionCtx())

        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        val record = diag.snapshot().single()
        assertEquals(DiagnosticDomain.DECISION, record.domain)
        assertEquals(DiagnosticOutcome.SKIPPED, record.outcome)
        assertEquals("NO_CANDIDATES", record.reasonCode)
        assertEquals(DecisionReasonCodes.NO_CANDIDATES, record.reasonCode)
        assertNull(record.packageId)
        assertEquals("ACTION", record.key)
    }

    @Test
    fun `decision_选中成功记录OK`() {
        val diag = RuntimeDiagnostics()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 17L, diagnostics = diag)
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "ok")) }
        var calls = 0
        api.on("ok") { calls++ }

        val report = pipeline.run(actionCtx())

        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertEquals(1, calls)
        val record = diag.snapshot().single()
        assertEquals(DiagnosticDomain.DECISION, record.domain)
        assertEquals(DiagnosticOutcome.OK, record.outcome)
        assertEquals("pkg.a", record.packageId)
        assertEquals("ACTION", record.key)
    }

    @Test
    fun `decision_null完全旁路且报告逐字段不变`() {
        fun run(diag: RuntimeDiagnostics?): DecisionReport {
            val pipeline = DecisionPipeline.of(listOf("pkg.a"), 19L, diagnostics = diag)
            val api = pipeline.actionApi("pkg.a")
            api.constraint("no") { false }
            api.provide("ACTION") {
                listOf(
                    candidate(key = "blocked", constraints = listOf("no")),
                    candidate(key = "ok"),
                )
            }
            api.on("blocked") { }
            api.on("ok") { }
            return pipeline.run(actionCtx())
        }

        val plain = run(null)
        val diag = RuntimeDiagnostics()
        val wired = run(diag)

        assertEquals(plain.outcome, wired.outcome)
        assertEquals(plain.evaluated, wired.evaluated)
        assertEquals(plain.chosen?.candidateKey, wired.chosen?.candidateKey)
        assertEquals(plain.eliminations.size, wired.eliminations.size)
        assertEquals(plain.failures.size, wired.failures.size)
        assertTrue(diag.snapshot().isNotEmpty())
    }

    @Test
    fun `exportJsonLines_九键且每行一条`() {
        val diag = RuntimeDiagnostics()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 23L, diagnostics = diag)
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "ok")) }
        api.on("ok") { }
        pipeline.run(actionCtx())

        val lines = diag.exportJsonLines().lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        val keys = Regex("\"(seq|day|phase|domain|package|key|outcome|reason|detail)\":")
            .findAll(lines.single())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            listOf("seq", "day", "phase", "domain", "package", "key", "outcome", "reason", "detail"),
            keys,
        )
        assertTrue(lines.single().startsWith("{\"seq\":1,"))
        assertTrue(lines.single().endsWith("}"))
    }

    @Test
    fun `disabled_关闭态零构造不记录不取detail`() {
        val diag = RuntimeDiagnostics.disabled()
        var detailCalls = 0
        diag.record(
            day = 1L,
            phase = "DAY_START",
            domain = DiagnosticDomain.HOOK,
            packageId = "pkgA",
            key = "COM300",
            outcome = DiagnosticOutcome.OK,
            reasonCode = "OK",
            detail = {
                detailCalls++
                "should-not-run"
            },
        )

        val hook = HookPipeline.of(listOf(hookDecl("pkgA")), diagnostics = diag)
        hook.bind("COM300", ModHookPhase.PREFIX, "pkgA") { error("boom") }
        hook.dispatch("COM300", emptyArray()) { ModValue.of(1L) }

        val decision = DecisionPipeline.of(listOf("pkg.a"), 29L, diagnostics = diag)
        val api = decision.actionApi("pkg.a")
        api.constraint("no") { false }
        api.provide("ACTION") { listOf(candidate(key = "blocked", constraints = listOf("no"))) }
        api.on("blocked") { }
        decision.run(actionCtx())

        assertEquals(0, detailCalls)
        assertTrue(diag.snapshot().isEmpty())
        assertFalse(diag.isEnabled)
    }
}
