package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.KernelQueryApi
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionConstraint
import com.eratw.port.modapi.ActionHandler
import com.eratw.port.modapi.ActionProvider
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.IntCompareOp
import com.eratw.port.modapi.IntFieldCmp
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.queryPlan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K3-B ModContext 装配 actions / query 的接线闸（RT2 波 G 车 G1，era-arch-v61u.md §21.3 / §21.5 / §12.1）。
 *
 * 钉住四件事：
 * 1. 未注入 `DecisionPipeline` 时 `actions` 是**显式 fail-closed**（不返回静默 no-op 注册）；
 * 2. 注入后三个注册入口（provider / handler / constraint）都真的生效，且包作用域隔离；
 * 3. 三个注册入口的 `Registration` 全部经 `track` 收进宿主 per-mod 收集器 ⇒ `onDisable` 后不留残项；
 * 4. `query` 恒可装配，且与 entities 同源、与 `KernelQueryApi` 直调逐条一致（EntityId 升序）。
 *
 * 全部断言只经公共 facade 与既有引擎观测面，不读私有字段、不依赖静态可变全局。
 */
class ModContextActionQueryWireTest {

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(
                        packageId = pkg,
                        hooks = listOf(HookDecl("COM300", HookPhase.PREFIX)),
                        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                        jobs = listOf(JobDecl("j1", phase = "DAY_END", firstDay = 0, everyDays = 1)),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private fun newContext(
        packageId: String,
        kernel: EntityKernel = newKernel(),
        snapshot: DeclarationSnapshot = newSnapshot(packageId),
        decisionPipeline: DecisionPipeline? = null,
        registrationSink: (Registration) -> Unit = {},
    ): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = snapshot,
        entityKernel = kernel,
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
        registrationSink = registrationSink,
        decisionPipeline = decisionPipeline,
    )

    private fun newHost(
        kernel: EntityKernel,
        vararg packages: String,
        decisionPipeline: DecisionPipeline? = null,
    ): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = kernel,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
        decisionPipeline = decisionPipeline,
    )

    private fun candidate(
        providerPackage: String,
        candidateKey: String,
        handlerKey: String = "h",
        constraints: List<String> = emptyList(),
        priority: Int = 0,
        weight: Int = 1,
    ): ActionCandidate = ActionCandidate(
        providerPackage = providerPackage,
        candidateKey = candidateKey,
        actor = EntityId(1L),
        priority = priority,
        weight = weight,
        constraints = constraints,
        handlerKey = handlerKey,
    )

    private fun selection(domain: String, day: Long = 0L): ActionSelectionContext =
        ActionSelectionContext(domain = domain, time = GameTime(day, "DAY"))

    @Test
    fun `未注入 DecisionPipeline 时 actions 三个入口全部 fail-closed 并点名 packageId 与 12 条`() {
        val ctx = newContext("modA")
        val provider = ActionProvider { emptyList() }
        val handler = ActionHandler { }
        val predicate = ActionConstraint { true }
        val failures = listOf(
            assertThrows<UnsupportedOperationException> { ctx.actions.provide("ACTION", provider) },
            assertThrows<UnsupportedOperationException> { ctx.actions.on("h", handler) },
            assertThrows<UnsupportedOperationException> { ctx.actions.constraint("gate", predicate) },
        )
        for (ex in failures) {
            val message = assertNotNull(ex.message)
            assertTrue(message.contains("决策管线未装配"), message)
            assertTrue(message.contains("modA"), message)
            assertTrue(message.contains("DecisionPipeline"), message)
            assertTrue(message.contains("§12.1"), message)
        }
        // 三条路径的文案各点名被拒入口，便于生产排障；不是同一个泛化字符串。
        assertTrue(failures[0].message!!.contains("actions.provide(ACTION)"), failures[0].message!!)
        assertTrue(failures[1].message!!.contains("actions.on(h)"), failures[1].message!!)
        assertTrue(failures[2].message!!.contains("actions.constraint(gate)"), failures[2].message!!)
    }

    @Test
    fun `注入 DecisionPipeline 后 provide 与 on 生效且约束谓词真的被求值`() {
        val pipeline = DecisionPipeline.of(listOf("modA"), 42L)
        val ctx = newContext("modA", decisionPipeline = pipeline)
        var constraintCalls = 0
        val executed = mutableListOf<String>()
        ctx.actions.constraint("gate") {
            constraintCalls++
            true
        }
        ctx.actions.provide("ACTION") {
            listOf(candidate("modA", "eat", constraints = listOf("gate")))
        }
        ctx.actions.on("h") { context -> executed += context.candidate.candidateKey }

        val report = pipeline.run(selection("ACTION"))

        assertEquals(1, report.evaluated)
        assertEquals(1, constraintCalls)
        assertEquals("eat", report.chosen?.candidateKey)
        assertEquals("modA", report.chosenPackageId)
        assertEquals(listOf("eat"), executed)
        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertTrue(report.eliminations.isEmpty())
    }

    @Test
    fun `约束返回 false 时该候选被淘汰且处理器一次都不执行`() {
        val pipeline = DecisionPipeline.of(listOf("modA"), 43L)
        val ctx = newContext("modA", decisionPipeline = pipeline)
        var executed = 0
        ctx.actions.constraint("gate") { false }
        ctx.actions.provide("ACTION") {
            listOf(candidate("modA", "eat", constraints = listOf("gate")))
        }
        ctx.actions.on("h") { executed++ }

        val report = pipeline.run(selection("ACTION"))

        assertEquals(1, report.evaluated)
        assertNull(report.chosen)
        assertEquals(0, executed)
        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertEquals(
            listOf(DecisionReasonCodes.CONSTRAINT_FALSE),
            report.eliminations.map { it.reasonCode },
        )
    }

    @Test
    fun `两个包两个 context 的 provider handler 与 constraint 互不可见`() {
        val pipeline = DecisionPipeline.of(listOf("modA", "modB"), 44L)
        val kernel = newKernel()
        val snapshot = newSnapshot("modA", "modB")
        val contextA = newContext("modA", kernel = kernel, snapshot = snapshot, decisionPipeline = pipeline)
        val contextB = newContext("modB", kernel = kernel, snapshot = snapshot, decisionPipeline = pipeline)

        var hitsA = 0
        contextA.actions.constraint("gate") { true }
        contextA.actions.on("h") { hitsA++ }
        contextA.actions.provide("A_ONLY") {
            listOf(candidate("modA", "a1", constraints = listOf("gate")))
        }
        // B 包用同名 handlerKey / 同名约束键，但只在 A 包注册过：按包作用域应解析不到。
        contextB.actions.provide("B_CONSTRAINT") {
            listOf(candidate("modB", "b1", constraints = listOf("gate")))
        }
        contextB.actions.provide("B_HANDLER") {
            listOf(candidate("modB", "b2"))
        }

        // A 域：A 的 provider / handler / constraint 全部命中。
        val reportA = pipeline.run(selection("A_ONLY"))
        assertEquals(1, reportA.evaluated)
        assertEquals("a1", reportA.chosen?.candidateKey)
        assertEquals(1, hitsA)

        // B 域候选引用了 A 包的约束键 ⇒ 对 B 包是「未注册约束」，被 CONSTRAINT_FALSE 淘汰。
        val reportConstraint = pipeline.run(selection("B_CONSTRAINT"))
        assertEquals(1, reportConstraint.evaluated)
        assertNull(reportConstraint.chosen)
        val eliminated = reportConstraint.eliminations.single()
        assertEquals("b1", eliminated.candidateKey)
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, eliminated.reasonCode)
        assertTrue(eliminated.detail!!.contains("unregistered constraint: gate"), eliminated.detail!!)

        // B 域候选引用了 A 包注册的同名 handlerKey ⇒ 对 B 包是「未注册处理器」，处理器不执行。
        val reportHandler = pipeline.run(selection("B_HANDLER"))
        assertEquals(1, reportHandler.evaluated)
        assertNull(reportHandler.chosen)
        assertEquals(1, hitsA)
        assertTrue(
            reportHandler.failures.any {
                it.phase == DecisionFailurePhase.HANDLER && it.packageId == "modB" && it.key == "h"
            },
            reportHandler.failures.toString(),
        )
    }

    @Test
    fun `actions 三个入口的 Registration 都被 registrationSink 收集且撤销后立即失效`() {
        val pipeline = DecisionPipeline.of(listOf("modA"), 45L)
        val collected = mutableListOf<Registration>()
        val ctx = newContext("modA", decisionPipeline = pipeline, registrationSink = { collected += it })
        ctx.actions.provide("ACTION") { listOf(candidate("modA", "c1")) }
        ctx.actions.on("h") { }
        ctx.actions.constraint("gate") { true }
        assertEquals(3, collected.size, "provide/on/constraint 三个 Registration 都必须进 per-mod 收集器")

        val before = pipeline.run(selection("ACTION"))
        assertEquals("c1", before.chosen?.candidateKey)

        collected.forEach { it.unregister() }

        val after = pipeline.run(selection("ACTION"))
        assertEquals(0, after.evaluated)
        assertNull(after.chosen)
        assertEquals(ActionExecutionOutcome.NONE, after.outcome)
    }

    @Test
    fun `ModRuntimeHost 全生命周期结束后 actions 注册全部撤销`() {
        val pipeline = DecisionPipeline.of(listOf("modA"), 46L)
        val kernel = newKernel()
        val host = newHost(kernel, "modA", decisionPipeline = pipeline)
        var hits = 0
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.actions.constraint("gate") { true }
                context.actions.provide("ACTION") {
                    listOf(candidate("modA", "c1", constraints = listOf("gate")))
                }
                context.actions.on("h") { hits++ }
            }

            override fun onEnable() {}
            override fun onDisable() {}
            override fun onUnload() {}
        }

        val states = host.runAll(
            listOf("modA" to mod),
            duringRun = {
                val report = pipeline.run(selection("ACTION"))
                assertEquals(1, report.evaluated)
                assertEquals("c1", report.chosen?.candidateKey)
                assertEquals(1, hits)
            },
        )

        assertTrue(states[0].active)
        // onDisable 之后：provider 不再产出、handler 不再执行、constraint 不再留存。
        val after = pipeline.run(selection("ACTION"))
        assertEquals(0, after.evaluated)
        assertNull(after.chosen)
        assertEquals(1, hits)
        assertEquals(ActionExecutionOutcome.NONE, after.outcome)
    }

    @Test
    fun `无 decisionPipeline 的 host 生命周期零行为变化且 actions 仍 fail-closed`() {
        val kernel = newKernel()
        val host = newHost(kernel, "modA")
        var hookHits = 0
        var phaseHits = 0
        var jobHits = 0
        var interactionHits = 0
        var uiHits = 0
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.hooks.register("COM300", HookPhase.PREFIX) {
                    hookHits++
                    HookResult.Continue
                }
                context.phases.subscribe("DAY_END") { phaseHits++ }
                context.jobs.bind("j1") { jobHits++ }
                context.interactions.registerHook(InteractionPhase.PRE) {
                    interactionHits++
                    InteractionResult.CONTINUE
                }
                context.ui.registerInteraction("BTN") { uiHits++ }
            }

            override fun onEnable() {}
            override fun onDisable() {}
            override fun onUnload() {}
        }

        val states = host.runAll(
            listOf("modA" to mod),
            duringRun = {
                val ctx = host.contextOf("modA")!! as ModContextImpl
                ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
                assertEquals(1, ctx.phaseRegistry.dispatch(GameTime(0, "DAY_END")).fired)
                assertEquals(1, ctx.jobScheduler.runDue(GameTime(0, "DAY_END")).fired.size)
                ctx.interactionPipeline.run(
                    com.eratw.port.modapi.InteractionContext("I", EntityId(1L), emptyList()),
                ) { ModValue.of(1L) }
                assertTrue(ctx.uiClick("BTN"))
                assertEquals(1, hookHits)
                assertEquals(1, phaseHits)
                assertEquals(1, jobHits)
                assertEquals(1, interactionHits)
                assertEquals(1, uiHits)
                // 无 decisionPipeline ⇒ actions 仍是显式 fail-closed，不静默吞注册。
                assertThrows<UnsupportedOperationException> {
                    ctx.actions.provide("ACTION") { emptyList() }
                }
            },
        )

        assertTrue(states[0].active)
        val ctx = host.contextOf("modA")!! as ModContextImpl
        ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(0, ctx.phaseRegistry.dispatch(GameTime(0, "DAY_END")).fired)
        assertFalse(ctx.uiClick("BTN"))
        assertEquals(1, hookHits)
        assertEquals(1, phaseHits)
        assertEquals(1, jobHits)
        assertEquals(1, interactionHits)
        assertEquals(1, uiHits)
    }

    @Test
    fun `query 恒可用且与 KernelQueryApi 直调逐条一致并按 EntityId 升序`() {
        val kernel = newKernel()
        val ctx = newContext("modA", kernel = kernel)
        val first = ctx.entities.create("Child")
        val second = ctx.entities.create("Child")
        val third = ctx.entities.create("Child")
        ctx.entities.setInt(first, "hp", 5L)
        ctx.entities.setInt(second, "hp", 0L)
        ctx.entities.setInt(third, "hp", 9L)

        val plan = queryPlan {
            archetype("Child")
            where(IntFieldCmp("hp", IntCompareOp.GT, 0L))
        }
        val viaFacade = ctx.query.query(plan)
        val viaEngine = KernelQueryApi(kernel).query(plan)

        assertEquals(viaEngine, viaFacade)
        assertEquals(listOf(first, third), viaFacade)
        assertEquals(
            viaFacade.map { it.raw },
            viaFacade.map { it.raw }.sorted(),
            "结果必须按 EntityId 原始 Long 数值升序",
        )
    }

    @Test
    fun `query 与 entities 同源_entities 新建后 query 立即可见且销毁后消失`() {
        val ctx = newContext("modA")
        val plan = queryPlan { archetype("Child") }
        assertTrue(ctx.query.query(plan).isEmpty())

        val id = ctx.entities.create("Child")
        assertEquals(listOf(id), ctx.query.query(plan))
        assertEquals(ctx.entities.query("Child"), ctx.query.query(plan))

        ctx.entities.setInt(id, "hp", 1L)
        assertEquals(
            listOf(id),
            ctx.query.query(queryPlan { archetype("Child"); where(IntFieldCmp("hp", IntCompareOp.EQ, 1L)) }),
        )

        assertTrue(ctx.entities.destroy(id))
        assertTrue(ctx.query.query(plan).isEmpty())
        assertTrue(ctx.query.query(queryPlan { archetype("Child"); where(IntFieldCmp("hp", IntCompareOp.EQ, 1L)) }).isEmpty())
    }
}