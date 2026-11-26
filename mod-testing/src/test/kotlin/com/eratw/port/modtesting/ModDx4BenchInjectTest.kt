package com.eratw.port.modtesting

import com.eratw.port.core.mod.ActionExecutionOutcome
import com.eratw.port.core.mod.DecisionPipeline
import com.eratw.port.core.mod.DecisionReasonCodes
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FunctionApi
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModFunction
import com.eratw.port.modapi.RosterProjectionApi
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ModDX4-B6 台架 face 注入口定向钉：roster / actions / functions 三面的可选注入与缺省逐位不变。
 *
 * 钉住契约判据四件事：
 * 1. 三面互不强制：`BenchFaces` 按面独立注入，没给的面维持 fail-closed 桩（调用即 UOE）；
 * 2. 缺省逐位不变（反向证明）：不注时既有的 `runMod` / `newContext` 路径注册即抛 UOE、
 *    同一 world 再发的缺省 context 摘注入即回桩；
 * 3. 最小 actions 仲裁真跑通：注入 core-vm `DecisionPipeline.actionApi` 包作用域 facade
 *    （与生产 `ModContextImpl.actions` 委托的同一真仲裁器）后，`onEnable` 注册 → `pipeline.run`
 *    → 处理器执行 → 副作用落回台架世界本体，全链可达；冷却记账等决策路径与 core-vm 语义同源；
 * 4. 注入口差异边界：Registration 归调用方自管（生产装配会经 track 收集供 onDisable 撤销，
 *    台架没有宿主收集器——见 [BenchFaces] KDoc）。
 */
class ModDx4BenchInjectTest {

    @Test
    fun injectedActionsFaceRunsRealArbitrationThroughProductionPipeline() {
        val world = TestSchema.world()
        val pipeline = DecisionPipeline.of(listOf(TestSchema.PACKAGE_ID), decisionSeed = 42L)
        val mod = ArbitrationMod()
        val session = runMod(
            mod,
            world,
            TestSchema.PACKAGE_ID,
            BenchFaces(actions = pipeline.actionApi(TestSchema.PACKAGE_ID)),
        )

        val actor = world.entities.create(TestSchema.ARCHETYPE.name)
        mod.actor = actor
        val report = pipeline.run(selection(day = 1L))

        assertEquals(1, report.evaluated)
        assertEquals(1, mod.constraintCalls)
        assertEquals(TestSchema.PACKAGE_ID, report.chosenPackageId)
        assertEquals(ArbitrationMod.CANDIDATE_KEY, report.chosen?.candidateKey)
        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertTrue(report.eliminations.isEmpty())
        assertEquals(1, mod.executedCount)
        // 处理器经 context.entities 写的是台架世界本体：仲裁真到达 mod 业务、副作用落回同一份状态
        // （session.context 与 world 共享同一份实体存储）。
        assertEquals(2L, world.entities.getInt(actor, TestSchema.AGE.name))
        assertEquals(2L, session.context.entities.getInt(actor, TestSchema.AGE.name))
    }

    @Test
    fun injectedPipelineEnforcesCooldownAcrossRuns() {
        val world = TestSchema.world()
        val pipeline = DecisionPipeline.of(listOf(TestSchema.PACKAGE_ID), decisionSeed = 7L)
        val mod = ArbitrationMod(cooldownKey = "eat-cd", cooldownDays = 1)
        runMod(
            mod,
            world,
            TestSchema.PACKAGE_ID,
            BenchFaces(actions = pipeline.actionApi(TestSchema.PACKAGE_ID)),
        )
        mod.actor = world.entities.create(TestSchema.ARCHETYPE.name)

        val day1 = pipeline.run(selection(day = 1L))
        assertEquals(ActionExecutionOutcome.COMPLETED, day1.outcome)
        assertEquals(1, mod.executedCount)

        // 同日重试：COMPLETED 后冷却已记账（day + cooldownDays），候选被 COOLDOWN 淘汰、处理器不执行。
        val sameDayAgain = pipeline.run(selection(day = 1L))
        assertNull(sameDayAgain.chosen)
        assertEquals(ActionExecutionOutcome.NONE, sameDayAgain.outcome)
        val elimination = sameDayAgain.eliminations.single()
        assertEquals(DecisionReasonCodes.COOLDOWN, elimination.reasonCode)
        assertEquals(TestSchema.PACKAGE_ID, elimination.packageId)
        assertEquals(ArbitrationMod.CANDIDATE_KEY, elimination.candidateKey)
        assertEquals(1, mod.executedCount)

        // 冷却到期（day=2 不再早于 until=2）：候选重新可执行。
        val day2 = pipeline.run(selection(day = 2L))
        assertEquals(ActionExecutionOutcome.COMPLETED, day2.outcome)
        assertEquals(2, mod.executedCount)
    }

    @Test
    fun defaultRunModKeepsFailClosedStubs() {
        val world = TestSchema.world()
        val plain = world.newContext(TestSchema.PACKAGE_ID)
        val provideEx = assertFailsWith<UnsupportedOperationException> {
            plain.actions.provide(ArbitrationMod.DOMAIN) { emptyList() }
        }
        assertTrue(provideEx.message!!.contains("actions.provide"), provideEx.message)
        val rosterEx = assertFailsWith<UnsupportedOperationException> {
            plain.roster.project(EntityId(1L))
        }
        assertTrue(rosterEx.message!!.contains("roster.project"), rosterEx.message)
        val functionsEx = assertFailsWith<UnsupportedOperationException> {
            plain.functions.has("TEST.PING")
        }
        assertTrue(functionsEx.message!!.contains("functions.has"), functionsEx.message)
    }

    @Test
    fun runModWithoutInjectionStillThrowsUoeWhenModRegistersActions() {
        val world = TestSchema.world()
        // 缺省（不注）路径逐位不变：mod 在 onEnable 经 context.actions 注册，装配当场抛 UOE
        // （与本注入口引入前的行为一致，不静默吞注册）。
        val ex = assertFailsWith<UnsupportedOperationException> {
            runMod(ArbitrationMod(), world, TestSchema.PACKAGE_ID)
        }
        assertTrue(ex.message!!.contains("台架未实现 actions."), ex.message)
    }

    @Test
    fun facesInjectIndependentlyAndUninjectedFacesStayStubbed() {
        val world = TestSchema.world()
        val pipeline = DecisionPipeline.of(listOf(TestSchema.PACKAGE_ID), decisionSeed = 1L)
        val mod = ArbitrationMod()
        val session = runMod(
            mod,
            world,
            TestSchema.PACKAGE_ID,
            BenchFaces(actions = pipeline.actionApi(TestSchema.PACKAGE_ID)),
        )
        // 只注 actions：roster / functions 仍是缺省桩。
        val rosterEx = assertFailsWith<UnsupportedOperationException> {
            session.context.roster.project(EntityId(1L))
        }
        assertTrue(rosterEx.message!!.contains("roster.project"), rosterEx.message)
        val functionsEx = assertFailsWith<UnsupportedOperationException> {
            session.context.functions.has("TEST.PING")
        }
        assertTrue(functionsEx.message!!.contains("functions.has"), functionsEx.message)

        // actions 真接到了注入面：onEnable 的注册不再抛 UOE，仲裁可达处理器。
        mod.actor = world.entities.create(TestSchema.ARCHETYPE.name)
        val report = pipeline.run(selection(day = 1L))
        assertEquals(ArbitrationMod.CANDIDATE_KEY, report.chosen?.candidateKey)
        assertEquals(1, mod.executedCount)
    }

    @Test
    fun newContextWithoutInjectionReturnsToStubs() {
        val world = TestSchema.world()
        val pipeline = DecisionPipeline.of(listOf(TestSchema.PACKAGE_ID), decisionSeed = 1L)
        val mod = ArbitrationMod()
        runMod(
            mod,
            world,
            TestSchema.PACKAGE_ID,
            BenchFaces(actions = pipeline.actionApi(TestSchema.PACKAGE_ID)),
        )
        mod.actor = world.entities.create(TestSchema.ARCHETYPE.name)
        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(selection(day = 1L)).outcome)

        // 摘注入 → 回桩：注入只跟本次装配走，同一 world 再发的缺省 context 三面全是桩。
        val plain = world.newContext(TestSchema.PACKAGE_ID)
        val ex = assertFailsWith<UnsupportedOperationException> {
            plain.actions.provide(ArbitrationMod.DOMAIN) { emptyList() }
        }
        assertTrue(ex.message!!.contains("actions.provide"), ex.message)
        assertFailsWith<UnsupportedOperationException> { plain.roster.project(EntityId(1L)) }
        assertFailsWith<UnsupportedOperationException> { plain.functions.has("TEST.PING") }

        // 注入会话不受摘注入影响：管线与注入 context 仍可继续仲裁。
        pipeline.run(selection(day = 1L))
        assertEquals(2, mod.executedCount)
    }

    @Test
    fun rosterAndFunctionsFacesInjectIndependently() {
        val world = TestSchema.world()
        val roster = FixedRoster(charIndex = 3, generation = 11L)
        val functions = InMemoryFunctions()
        val mod = RosterFunctionsMod()
        runMod(
            mod,
            world,
            TestSchema.PACKAGE_ID,
            BenchFaces(roster = roster, functions = functions),
        )
        assertEquals(3, mod.projected)
        assertEquals(11L, mod.generationSeen)
        assertEquals(3, mod.checkedRow)
        assertTrue(mod.registeredSeen)
        // 注入面本体就是 context 那一份：onEnable 之后的注册经同一实现反查为真。
        functions.register("TEST.OTHER") { }
        assertTrue(functions.has("test.other"))
    }

    /** 仲裁选择上下文（domain 固定为行动域）。 */
    private fun selection(day: Long): ActionSelectionContext =
        ActionSelectionContext(domain = ArbitrationMod.DOMAIN, time = GameTime(day))

    /**
     * 仲裁示范 mod：`onEnable` 经 `context.actions` 注册约束 / 候选提供者 / 处理器——注入口装上后
     * 这三个入口直达注入实现本体（生产同源真仲裁器）；缺省（不注）时第一个注册入口即抛 UOE。
     * 处理器把候选行动者的 [TestSchema.AGE] 写成 2，副作用落在台架世界本体。
     */
    private class ArbitrationMod(
        private val cooldownKey: String? = null,
        private val cooldownDays: Int? = null,
    ) : EraMod {

        lateinit var context: ModContext

        var actor: EntityId? = null

        var constraintCalls = 0

        var executedCount = 0

        override fun onLoad(context: ModContext) {
            this.context = context
        }

        override fun onEnable() {
            context.actions.constraint(GATE_KEY) {
                constraintCalls += 1
                true
            }
            context.actions.provide(DOMAIN) {
                listOf(
                    ActionCandidate(
                        providerPackage = context.packageId,
                        candidateKey = CANDIDATE_KEY,
                        actor = requireNotNull(actor),
                        constraints = listOf(GATE_KEY),
                        cooldownKey = cooldownKey,
                        cooldownDays = cooldownDays,
                        handlerKey = HANDLER_KEY,
                    ),
                )
            }
            context.actions.on(HANDLER_KEY) { ctx ->
                executedCount += 1
                context.entities.setInt(ctx.candidate.actor, TestSchema.AGE.name, 2L)
            }
        }

        override fun onDisable() = Unit

        override fun onUnload() = Unit

        companion object {
            const val DOMAIN: String = "ACTION"
            const val CANDIDATE_KEY: String = "eat"
            const val HANDLER_KEY: String = "eat.handler"
            const val GATE_KEY: String = "eat.gate"
        }
    }

    /** 注入示范：固定读数的角色槽投射面（值随意定，只为证明 context 面委托到了注入实现本体）。 */
    private class FixedRoster(private val charIndex: Int, private val generation: Long) : RosterProjectionApi {

        override fun project(id: EntityId): Int = charIndex

        override fun unproject(id: EntityId): Boolean = false

        override val rosterGeneration: Long get() = generation

        override fun charIndexOfChecked(id: EntityId, expectedGeneration: Long): Int =
            if (expectedGeneration == generation) charIndex else -1
    }

    /** 注入示范：内存函数面（注册 / 显式替换 / 反查，键按大写归一——与 `FunctionApi` 契约口径一致）。 */
    private class InMemoryFunctions : FunctionApi {

        private val registered = LinkedHashSet<String>()

        override fun register(name: String, function: ModFunction) {
            require(registered.add(canonical(name))) { "函数重名注册：$name" }
        }

        override fun override(name: String, function: ModFunction) {
            require(canonical(name) in registered) { "函数未注册，override 只能替换已存在：$name" }
        }

        override fun has(name: String): Boolean = canonical(name) in registered

        private fun canonical(name: String): String = name.trim().uppercase(Locale.ROOT)
    }

    /** 注入示范 mod：`onEnable` 里经 `context.roster` / `context.functions` 消费注入面并留读数。 */
    private class RosterFunctionsMod : EraMod {

        lateinit var context: ModContext

        var projected: Int? = null

        var generationSeen: Long? = null

        var checkedRow: Int? = null

        var registeredSeen = false

        override fun onLoad(context: ModContext) {
            this.context = context
        }

        override fun onEnable() {
            projected = context.roster.project(EntityId(1L))
            val generation = context.roster.rosterGeneration
            generationSeen = generation
            checkedRow = context.roster.charIndexOfChecked(EntityId(1L), generation)
            context.functions.register("TEST.PING") { args ->
                args.forEach { arg -> arg.intValue += 1L }
            }
            registeredSeen = context.functions.has("test.ping")
        }

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }
}
