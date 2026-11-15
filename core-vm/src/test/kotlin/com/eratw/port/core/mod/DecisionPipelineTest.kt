package com.eratw.port.core.mod

import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.ReservationKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DecisionPipeline 契约闸（§21.3 / §21.3.5 / §21.4 / §21.6，ABI-12 Candidate）。
 *
 * 钉住：无候选旁路、收集序（包链接序 + 包内注册序 + domain 过滤）、去重、约束短路与
 * 异常隔离、冷却只记 COMPLETED、perDay/perActorPerDay 含失败计数与换日边界、预留冲突/
 * 占用/释放、优先级分层、加权抽取前缀和、决策随机流独立性负控与派生键、处理器失败/取消/
 * 未注册、provider 失败与归属冒名、注册撤销、报告可复现。
 *
 * 本车是功能新增（不是 bug 修复），根因层断言不适用 AGENTS §3.1。
 */
class DecisionPipelineTest {

    private fun candidate(
        pkg: String = "pkg.a",
        key: String = "k",
        actor: Long = 1L,
        priority: Int = 0,
        weight: Int = 1,
        constraints: List<String> = emptyList(),
        cooldownKey: String? = null,
        cooldownDays: Int? = null,
        perDay: Int? = null,
        perActorPerDay: Int? = null,
        claims: List<ReservationKey> = emptyList(),
        handlerKey: String = key,
        payloadRef: EntityId? = null,
    ): ActionCandidate = ActionCandidate(
        providerPackage = pkg,
        candidateKey = key,
        actor = EntityId(actor),
        priority = priority,
        weight = weight,
        constraints = constraints,
        cooldownKey = cooldownKey,
        cooldownDays = cooldownDays,
        perDay = perDay,
        perActorPerDay = perActorPerDay,
        reservationClaims = claims,
        handlerKey = handlerKey,
        payloadRef = payloadRef,
    )

    private fun ctx(
        domain: String = "ACTION",
        day: Long = 10L,
        phase: String = "DAY_START",
        actor: Long? = null,
        metadata: Map<String, String> = emptyMap(),
    ): ActionSelectionContext = ActionSelectionContext(
        domain = domain,
        time = GameTime(day, phase),
        actor = actor?.let { EntityId(it) },
        metadata = metadata,
    )

    /** 决策随机流 spy：记录派生键/上界/种子，按 [answers] 逐次回答（越界则默认 0）。 */
    private class SpyRandom(private val answers: List<Long> = emptyList()) : DecisionRandomSource {
        val keys = ArrayList<DecisionDrawKey>()
        val bounds = ArrayList<Long>()
        val seeds = ArrayList<Long>()

        override fun draw(seed: Long, key: DecisionDrawKey, bound: Long): Long {
            seeds += seed
            keys += key
            bounds += bound
            val index = keys.size - 1
            return answers.getOrElse(index) { 0L }
        }
    }

    @Test
    fun `01 无 provider 完全旁路`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), decisionSeed = 1L)
        var handlerCalls = 0
        pipeline.actionApi("pkg.a").on("k") { handlerCalls++ }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertNull(report.chosen)
        assertNull(report.chosenPackageId)
        assertEquals("ACTION", report.domain)
        assertEquals(0, report.evaluated)
        assertTrue(report.eliminations.isEmpty())
        assertTrue(report.failures.isEmpty())
        assertTrue(report.reservationsReleased.isEmpty())
        assertEquals(0, handlerCalls)
        assertTrue(pipeline.heldReservations().isEmpty())
    }

    @Test
    fun `02 多 provider 收集序等于包链接序加包内注册序`() {
        val calls = mutableListOf<String>()
        val random = SpyRandom()
        val pipeline = DecisionPipeline.of(listOf("pkg.b", "pkg.a"), decisionSeed = 7L, random = random)
        val apiA = pipeline.actionApi("pkg.a")
        apiA.provide("ACTION") {
            calls += "a1"
            listOf(candidate("pkg.a", "a1"))
        }
        apiA.provide("ACTION") {
            calls += "a2"
            listOf(candidate("pkg.a", "a2"))
        }
        val apiB = pipeline.actionApi("pkg.b")
        apiB.provide("ACTION") {
            calls += "b1"
            listOf(candidate("pkg.b", "b1"))
        }
        apiB.provide("EVENT") {
            calls += "b-event"
            listOf(candidate("pkg.b", "bE"))
        }
        apiA.on("a1") {}
        apiA.on("a2") {}
        apiB.on("b1") {}

        val report = pipeline.run(ctx(domain = "ACTION"))

        assertEquals(listOf("b1", "a1", "a2"), calls)
        assertEquals(listOf("b1", "a1", "a2"), random.keys.single().candidateKeys)
        assertEquals(3, report.evaluated)
        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
    }

    @Test
    fun `03 去重同包同候选键只留首个`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 3L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") {
            listOf(candidate(key = "dup", weight = 1), candidate(key = "dup", weight = 9))
        }
        api.on("dup") {}

        val report = pipeline.run(ctx())

        assertEquals(2, report.evaluated)
        val eliminated = report.eliminations.single()
        assertEquals("pkg.a", eliminated.packageId)
        assertEquals("dup", eliminated.candidateKey)
        assertEquals(DecisionReasonCodes.PRIORITY_LOST, eliminated.reasonCode)
        assertEquals("duplicate", eliminated.detail)
        assertEquals(1, report.chosen?.weight)
    }

    @Test
    fun `04 约束 false 淘汰与约束异常隔离`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 5L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        api.constraint("no") { false }
        api.constraint("boom") { throw IllegalStateException("约束炸了") }
        api.provide("ACTION") {
            listOf(
                candidate(key = "blocked", constraints = listOf("no")),
                candidate(key = "throwing", constraints = listOf("boom")),
                candidate(key = "ok"),
            )
        }
        var blockedCalls = 0
        var throwingCalls = 0
        var okCalls = 0
        api.on("blocked") { blockedCalls++ }
        api.on("throwing") { throwingCalls++ }
        api.on("ok") { okCalls++ }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertEquals("ok", report.chosen?.candidateKey)
        assertEquals(0, blockedCalls)
        assertEquals(0, throwingCalls)
        assertEquals(1, okCalls)
        assertEquals(listOf("blocked", "throwing"), report.eliminations.map { it.candidateKey })
        assertTrue(report.eliminations.all { it.reasonCode == DecisionReasonCodes.CONSTRAINT_FALSE })
        val failure = report.failures.single()
        assertEquals(DecisionFailurePhase.CONSTRAINT, failure.phase)
        assertEquals("pkg.a", failure.packageId)
        assertEquals("throwing", failure.key)
    }

    @Test
    fun `05 约束按列表序短路`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 11L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var secondCalls = 0
        api.constraint("first") { false }
        api.constraint("second") {
            secondCalls++
            true
        }
        api.provide("ACTION") { listOf(candidate(key = "c", constraints = listOf("first", "second"))) }
        api.on("c") {}

        val report = pipeline.run(ctx())

        assertEquals(0, secondCalls)
        assertEquals(1, report.eliminations.size)
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, report.eliminations.single().reasonCode)
        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
    }

    @Test
    fun `06 未注册约束 fail-closed`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 12L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var handlerCalls = 0
        api.provide("ACTION") { listOf(candidate(key = "c", constraints = listOf("ghost"))) }
        api.on("c") { handlerCalls++ }

        val report = pipeline.run(ctx())

        val eliminated = report.eliminations.single()
        assertEquals(DecisionReasonCodes.CONSTRAINT_FALSE, eliminated.reasonCode)
        assertEquals("unregistered constraint: ghost", eliminated.detail)
        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertEquals(0, handlerCalls)
        assertEquals(0, report.failures.size)
    }
    @Test
    fun `07 冷却期内淘汰与过期恢复`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 13L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { listOf(candidate(key = "cd", cooldownKey = "cd", cooldownDays = 2)) }
        api.on("cd") { calls++ }

        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 10)).outcome)
        val sameDay = pipeline.run(ctx(day = 10))
        assertEquals(ActionExecutionOutcome.NONE, sameDay.outcome)
        assertEquals(DecisionReasonCodes.COOLDOWN, sameDay.eliminations.single().reasonCode)
        val nextDay = pipeline.run(ctx(day = 11))
        assertEquals(DecisionReasonCodes.COOLDOWN, nextDay.eliminations.single().reasonCode)
        val recovered = pipeline.run(ctx(day = 12))
        assertEquals(ActionExecutionOutcome.COMPLETED, recovered.outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `08 冷却只在 COMPLETED 后记账`() {
        val failing = DecisionPipeline.of(listOf("pkg.a"), 14L, SpyRandom())
        val failingApi = failing.actionApi("pkg.a")
        var failingCalls = 0
        failingApi.provide("ACTION") { listOf(candidate(key = "f", cooldownKey = "burn", cooldownDays = 2)) }
        failingApi.on("f") {
            failingCalls++
            throw IllegalStateException("处理器炸了")
        }
        assertEquals(ActionExecutionOutcome.FAILED, failing.run(ctx(day = 10)).outcome)
        val second = failing.run(ctx(day = 10))
        assertEquals(ActionExecutionOutcome.FAILED, second.outcome)
        assertTrue(second.eliminations.none { it.reasonCode == DecisionReasonCodes.COOLDOWN })
        assertEquals(2, failingCalls)

        val cancelling = DecisionPipeline.of(listOf("pkg.a"), 15L, SpyRandom())
        val cancellingApi = cancelling.actionApi("pkg.a")
        var cancellingCalls = 0
        cancellingApi.provide("ACTION") { listOf(candidate(key = "c", cooldownKey = "burn2", cooldownDays = 2)) }
        cancellingApi.on("c") { context ->
            cancellingCalls++
            context.cancellation.requestCancel()
        }
        assertEquals(ActionExecutionOutcome.CANCELED, cancelling.run(ctx(day = 10)).outcome)
        assertEquals(ActionExecutionOutcome.CANCELED, cancelling.run(ctx(day = 10)).outcome)
        assertEquals(2, cancellingCalls)
    }

    @Test
    fun `09 perDay 限额含失败也计入`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 16L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { listOf(candidate(key = "p", perDay = 2)) }
        api.on("p") {
            calls++
            throw IllegalStateException("处理器炸了")
        }

        assertEquals(ActionExecutionOutcome.FAILED, pipeline.run(ctx(day = 5)).outcome)
        assertEquals(ActionExecutionOutcome.FAILED, pipeline.run(ctx(day = 5)).outcome)
        val limited = pipeline.run(ctx(day = 5))
        assertEquals(ActionExecutionOutcome.NONE, limited.outcome)
        assertEquals(DecisionReasonCodes.LIMIT_DAY, limited.eliminations.single().reasonCode)
        assertEquals(2, calls)
    }

    @Test
    fun `10 perActorPerDay 限额按行动者分桶`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 17L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { selection ->
            listOf(candidate(key = "a", actor = selection.actor?.raw ?: 1L, perActorPerDay = 1))
        }
        api.on("a") { calls++ }

        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 5, actor = 1L)).outcome)
        val sameActor = pipeline.run(ctx(day = 5, actor = 1L))
        assertEquals(ActionExecutionOutcome.NONE, sameActor.outcome)
        assertEquals(DecisionReasonCodes.LIMIT_ACTOR, sameActor.eliminations.single().reasonCode)
        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 5, actor = 2L)).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `11 换日后限额重新计数`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 18L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { listOf(candidate(key = "d", perDay = 1)) }
        api.on("d") { calls++ }

        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 5)).outcome)
        assertEquals(DecisionReasonCodes.LIMIT_DAY, pipeline.run(ctx(day = 5)).eliminations.single().reasonCode)
        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 6)).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `12 预留冲突淘汰且同轮同键不互斥`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 21L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        val claim = ReservationKey("chara", "7")
        var inner: DecisionReport? = null
        api.provide("ACTION") { selection ->
            when (selection.metadata["mode"]) {
                "outer" -> listOf(candidate(key = "outer", claims = listOf(claim)))
                "inner" -> listOf(candidate(key = "inner", claims = listOf(claim)))
                else -> listOf(
                    candidate(key = "hi", priority = 5, claims = listOf(claim)),
                    candidate(key = "lo", priority = 0, claims = listOf(claim)),
                )
            }
        }
        api.on("outer") { inner = pipeline.run(ctx(day = 10, metadata = mapOf("mode" to "inner"))) }
        api.on("inner") {}
        api.on("hi") {}
        api.on("lo") {}

        val outerReport = pipeline.run(ctx(day = 10, metadata = mapOf("mode" to "outer")))
        assertEquals(ActionExecutionOutcome.COMPLETED, outerReport.outcome)
        val innerReport = assertNotNull(inner)
        assertEquals(ActionExecutionOutcome.NONE, innerReport.outcome)
        val conflict = innerReport.eliminations.single()
        assertEquals(DecisionReasonCodes.RESERVATION_CONFLICT, conflict.reasonCode)
        assertEquals("inner", conflict.candidateKey)
        assertTrue(pipeline.heldReservations().isEmpty())

        val sameRound = pipeline.run(ctx(day = 10))
        assertEquals("hi", sameRound.chosen?.candidateKey)
        assertTrue(sameRound.eliminations.none { it.reasonCode == DecisionReasonCodes.RESERVATION_CONFLICT })
        assertEquals(DecisionReasonCodes.PRIORITY_LOST, sameRound.eliminations.single().reasonCode)
    }

    @Test
    fun `13 选中后 held 含 claims 且结束全部释放`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 22L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        val chara = ReservationKey("chara", "1")
        val slot = ReservationKey("slot", "2")
        var observed: Set<ReservationKey> = emptySet()
        api.provide("ACTION") { listOf(candidate(key = "r", claims = listOf(chara, slot, chara))) }
        api.on("r") { observed = pipeline.heldReservations() }

        val report = pipeline.run(ctx())

        assertEquals(setOf(chara, slot), observed)
        assertEquals(listOf(chara, slot), report.reservationsReleased)
        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertTrue(pipeline.heldReservations().isEmpty())
    }

    @Test
    fun `14 低优先级层全部 PRIORITY_LOST`() {
        val random = SpyRandom()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 23L, random)
        val api = pipeline.actionApi("pkg.a")
        var highCalls = 0
        var lowCalls = 0
        api.provide("ACTION") {
            listOf(
                candidate(key = "h1", priority = 5),
                candidate(key = "l1", priority = 0),
                candidate(key = "h2", priority = 5),
            )
        }
        api.on("h1") { highCalls++ }
        api.on("h2") { highCalls++ }
        api.on("l1") { lowCalls++ }

        val report = pipeline.run(ctx())

        assertEquals(5, report.chosen?.priority)
        assertEquals(0, lowCalls)
        assertEquals(1, highCalls)
        assertEquals(listOf("h1", "h2"), random.keys.single().candidateKeys)
        assertEquals(2L, random.bounds.single())
        val lowElimination = report.eliminations.single { it.candidateKey == "l1" }
        assertEquals(DecisionReasonCodes.PRIORITY_LOST, lowElimination.reasonCode)
        assertEquals(3, report.evaluated)
    }

    @Test
    fun `15 加权抽取前缀和确定性`() {
        fun pick(answer: Long): String? {
            val random = SpyRandom(listOf(answer))
            val pipeline = DecisionPipeline.of(listOf("pkg.a"), 31L, random)
            val api = pipeline.actionApi("pkg.a")
            api.provide("ACTION") {
                listOf(
                    candidate(key = "a", priority = 1, weight = 1),
                    candidate(key = "b", priority = 1, weight = 3),
                    candidate(key = "c", priority = 1, weight = 1),
                )
            }
            api.on("a") {}
            api.on("b") {}
            api.on("c") {}
            val report = pipeline.run(ctx())
            assertEquals(5L, random.bounds.single())
            assertEquals(31L, random.seeds.single())
            assertEquals(listOf("a", "b", "c"), random.keys.single().candidateKeys)
            return report.chosen?.candidateKey
        }

        assertEquals("a", pick(0L))
        assertEquals("b", pick(1L))
        assertEquals("b", pick(2L))
        assertEquals("b", pick(3L))
        assertEquals("c", pick(4L))
    }
    @Test
    fun `16 同 seed 同候选集两次 run 选出同一候选`() {
        fun runOnce(): String? {
            val pipeline = DecisionPipeline.of(listOf("pkg.a"), 42L)
            val api = pipeline.actionApi("pkg.a")
            api.provide("ACTION") {
                listOf(
                    candidate(key = "a", weight = 1),
                    candidate(key = "b", weight = 5),
                    candidate(key = "c", weight = 2),
                )
            }
            api.on("a") {}
            api.on("b") {}
            api.on("c") {}
            return pipeline.run(ctx()).chosen?.candidateKey
        }

        val first = runOnce()
        val second = runOnce()
        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `17 决策随机源越界 fail-closed`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 1L, SpyRandom(listOf(5L)))
        val api = pipeline.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { listOf(candidate(key = "a", weight = 2)) }
        api.on("a") { calls++ }

        assertThrows<IllegalStateException> { pipeline.run(ctx()) }
        assertEquals(0, calls)
        assertTrue(pipeline.heldReservations().isEmpty())
    }

    @Test
    fun `18 决策随机流独立性负控`() {
        val expected = Sfmt19937(12345L).let { rng -> List(20) { rng.next(1000L) } }

        val untouched = Sfmt19937(12345L)
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 99L)
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") {
            listOf(candidate(key = "a", weight = 2), candidate(key = "b", weight = 3))
        }
        api.on("a") {}
        api.on("b") {}
        repeat(20) { index -> pipeline.run(ctx(day = 10L + index)) }

        val after = List(20) { untouched.next(1000L) }
        assertEquals(expected, after)
    }

    @Test
    fun `19 决策随机流派生键逐段变化`() {
        fun keyOf(
            day: Long,
            phase: String,
            domain: String,
            nth: Int,
            keys: List<String>,
        ): DecisionDrawKey {
            val random = SpyRandom()
            val pipeline = DecisionPipeline.of(
                listOf("pkg.a"),
                decisionSeed = 777L,
                random = random,
                arbitrationOrdinal = { nth },
            )
            val api = pipeline.actionApi("pkg.a")
            api.provide(domain) { keys.map { candidate(key = it, weight = 1) } }
            keys.forEach { key -> api.on(key) {} }
            pipeline.run(ctx(domain = domain, day = day, phase = phase))
            assertEquals(777L, random.seeds.single())
            return random.keys.single()
        }

        val base = keyOf(10L, "DAY_START", "ACTION", 0, listOf("a", "b"))
        assertEquals(DecisionDrawKey(10L, "DAY_START", "ACTION", listOf("a", "b"), 0), base)
        assertNotEquals(base, keyOf(11L, "DAY_START", "ACTION", 0, listOf("a", "b")))
        assertNotEquals(base, keyOf(10L, "DAY_END", "ACTION", 0, listOf("a", "b")))
        assertNotEquals(base, keyOf(10L, "DAY_START", "EVENT", 0, listOf("a", "b")))
        assertNotEquals(base, keyOf(10L, "DAY_START", "ACTION", 1, listOf("a", "b")))
        assertNotEquals(base, keyOf(10L, "DAY_START", "ACTION", 0, listOf("b", "a")))
        assertNotEquals(base, keyOf(10L, "DAY_START", "ACTION", 0, listOf("a", "b", "c")))
    }

    @Test
    fun `20 Sha256 决策随机流派生确定性与边界`() {
        val key = DecisionDrawKey(3L, "DAY_START", "ACTION", listOf("a", "b"), 0)
        val first = Sha256DecisionRandom.draw(1234L, key, 1_000_000L)
        val second = Sha256DecisionRandom.draw(1234L, key, 1_000_000L)
        assertEquals(first, second, "每次新建 SFMT 实例 => 同键同 seed 必同值（复用实例会破）")
        assertTrue(first >= 0L && first < 1_000_000L)
        assertEquals(0L, Sha256DecisionRandom.draw(1L, key, 1L))
        assertThrows<IllegalArgumentException> { Sha256DecisionRandom.draw(1L, key, 0L) }
        assertThrows<IllegalArgumentException> { Sha256DecisionRandom.draw(1L, key, -5L) }
        val distinct = (1L..8L).map { Sha256DecisionRandom.draw(it, key, 1_000_000_000L) }.distinct()
        assertTrue(distinct.size >= 2, "不同 decisionSeed 必须派生出不同抽取")
    }

    @Test
    fun `21 handler 抛异常 FAILED 且隔离预留释放`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 24L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        val claim = ReservationKey("chara", "9")
        var calls = 0
        var observed: Set<ReservationKey> = emptySet()
        api.provide("ACTION") { listOf(candidate(key = "boom", claims = listOf(claim))) }
        api.on("boom") {
            calls++
            observed = pipeline.heldReservations()
            throw IllegalStateException("处理器炸了")
        }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.FAILED, report.outcome)
        assertEquals(1, calls)
        assertEquals(setOf(claim), observed)
        assertEquals(listOf(claim), report.reservationsReleased)
        assertTrue(pipeline.heldReservations().isEmpty())
        val failure = report.failures.single()
        assertEquals("pkg.a", failure.packageId)
        assertEquals("boom", failure.key)
        assertEquals(DecisionFailurePhase.HANDLER, failure.phase)
        assertEquals("boom", report.chosen?.candidateKey)
    }

    @Test
    fun `22 取消 CANCELED 且预留释放`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 25L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        val claim = ReservationKey("slot", "3")
        var calls = 0
        api.provide("ACTION") { listOf(candidate(key = "work", claims = listOf(claim))) }
        api.on("work") { context ->
            calls++
            context.cancellation.requestCancel()
        }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.CANCELED, report.outcome)
        assertEquals(1, calls)
        assertEquals(listOf(claim), report.reservationsReleased)
        assertTrue(pipeline.heldReservations().isEmpty())
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `23 未注册 handlerKey 淘汰且不执行`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 26L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var realCalls = 0
        api.provide("ACTION") {
            listOf(candidate(key = "ghost", handlerKey = "nope"), candidate(key = "real"))
        }
        api.on("real") { realCalls++ }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertEquals("real", report.chosen?.candidateKey)
        assertEquals(1, realCalls)
        val eliminated = report.eliminations.single()
        assertEquals("ghost", eliminated.candidateKey)
        assertEquals(DecisionReasonCodes.HANDLER_FAILED, eliminated.reasonCode)
        assertEquals("unregistered handler: nope", eliminated.detail)
        assertEquals(DecisionFailurePhase.HANDLER, report.failures.single().phase)
    }

    @Test
    fun `24 provider 抛异常隔离其他 provider 照跑`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a", "pkg.b"), 27L, SpyRandom())
        pipeline.actionApi("pkg.a").provide("ACTION") { throw IllegalStateException("provider 炸了") }
        var calls = 0
        val apiB = pipeline.actionApi("pkg.b")
        apiB.provide("ACTION") { listOf(candidate("pkg.b", "b")) }
        apiB.on("b") { calls++ }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.COMPLETED, report.outcome)
        assertEquals("b", report.chosen?.candidateKey)
        assertEquals("pkg.b", report.chosenPackageId)
        assertEquals(1, calls)
        val failure = report.failures.single()
        assertEquals("pkg.a", failure.packageId)
        assertEquals(DecisionFailurePhase.PROVIDER, failure.phase)
    }

    @Test
    fun `25 providerPackage 冒名导致 provider 失败`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 28L, SpyRandom())
        var calls = 0
        pipeline.actionApi("pkg.a").provide("ACTION") { listOf(candidate("pkg.b", "stolen")) }
        pipeline.actionApi("pkg.a").on("stolen") { calls++ }
        pipeline.actionApi("pkg.b").on("stolen") { calls++ }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertEquals(0, report.evaluated)
        assertEquals(0, calls)
        assertTrue(report.eliminations.isEmpty())
        val failure = report.failures.single()
        assertEquals("pkg.a", failure.packageId)
        assertEquals(DecisionFailurePhase.PROVIDER, failure.phase)
    }

    @Test
    fun `26 actionApi 注册与撤销`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 29L, SpyRandom())
        val api = pipeline.actionApi("pkg.a")
        var calls = 0
        val providerReg = api.provide("ACTION") { listOf(candidate(key = "x", constraints = listOf("gate"))) }
        val handlerReg = api.on("x") { calls++ }
        var constraintReg = api.constraint("gate") { true }
        assertThrows<IllegalArgumentException> { api.on("x") {} }
        assertThrows<IllegalArgumentException> { api.constraint("gate") { true } }

        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx()).outcome)
        assertEquals(1, calls)

        constraintReg.unregister()
        constraintReg.unregister()
        val unregistered = pipeline.run(ctx())
        assertEquals(ActionExecutionOutcome.NONE, unregistered.outcome)
        assertEquals("unregistered constraint: gate", unregistered.eliminations.single().detail)
        assertEquals(1, calls)

        constraintReg = api.constraint("gate") { true }
        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx()).outcome)
        assertEquals(2, calls)

        handlerReg.unregister()
        val noHandler = pipeline.run(ctx())
        assertEquals(ActionExecutionOutcome.NONE, noHandler.outcome)
        assertEquals(DecisionReasonCodes.HANDLER_FAILED, noHandler.eliminations.single().reasonCode)
        assertEquals(2, calls)

        providerReg.unregister()
        val noProvider = pipeline.run(ctx())
        assertEquals(ActionExecutionOutcome.NONE, noProvider.outcome)
        assertEquals(0, noProvider.evaluated)
        assertTrue(noProvider.eliminations.isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun `27 跨包 handler 不解析（包作用域隔离）`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a", "pkg.b"), 30L, SpyRandom())
        pipeline.actionApi("pkg.a").provide("ACTION") { listOf(candidate("pkg.a", "x")) }
        var pkgBCalls = 0
        pipeline.actionApi("pkg.b").on("x") { pkgBCalls++ }

        val report = pipeline.run(ctx())

        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertEquals(0, pkgBCalls)
        assertEquals(DecisionReasonCodes.HANDLER_FAILED, report.eliminations.single().reasonCode)
    }

    private fun eliminationTuples(report: DecisionReport): List<List<String>> =
        report.eliminations.map { listOf(it.packageId, it.candidateKey, it.reasonCode, it.detail ?: "-") }

    private fun failureTuples(report: DecisionReport): List<List<String>> =
        report.failures.map { listOf(it.packageId, it.key, it.phase.name) }

    @Test
    fun `28 报告字段两次 run 逐字段相等`() {
        fun runOnce(): DecisionReport {
            val random = SpyRandom(listOf(0L, 3L))
            val pipeline = DecisionPipeline.of(listOf("pkg.a", "pkg.b"), 4242L, random)
            val apiA = pipeline.actionApi("pkg.a")
            apiA.constraint("no") { false }
            apiA.provide("ACTION") { selection ->
                if (selection.metadata["mode"] == "warm") {
                    listOf(candidate("pkg.a", "burned", priority = 4, cooldownKey = "burn", cooldownDays = 3))
                } else {
                    listOf(
                        candidate("pkg.a", "dup", priority = 4),
                        candidate("pkg.a", "dup", priority = 4),
                        candidate("pkg.a", "blocked", priority = 4, constraints = listOf("no")),
                        candidate("pkg.a", "burned", priority = 4, cooldownKey = "burn", cooldownDays = 3),
                        candidate("pkg.a", "low", priority = 1),
                        candidate("pkg.a", "w1", priority = 4, weight = 2),
                        candidate("pkg.a", "w2", priority = 4, weight = 2),
                    )
                }
            }
            apiA.on("burned") {}
            apiA.on("blocked") {}
            apiA.on("low") {}
            apiA.on("dup") { throw IllegalStateException("dup 不该被执行") }
            apiA.on("w1") { throw IllegalStateException("w1 不该被执行") }
            apiA.on("w2") { throw IllegalStateException("w2 炸了") }
            pipeline.run(ctx(day = 10, metadata = mapOf("mode" to "warm")))
            return pipeline.run(ctx(day = 10))
        }

        val first = runOnce()
        val second = runOnce()

        assertEquals(first.domain, second.domain)
        assertEquals(first.evaluated, second.evaluated)
        assertEquals(7, first.evaluated)
        assertEquals(eliminationTuples(first), eliminationTuples(second))
        assertEquals(6, first.eliminations.size)
        assertEquals(first.chosen?.candidateKey, second.chosen?.candidateKey)
        assertEquals("w2", first.chosen?.candidateKey)
        assertEquals(ActionExecutionOutcome.FAILED, first.outcome)
        assertEquals(first.outcome, second.outcome)
        assertEquals(failureTuples(first), failureTuples(second))
        assertEquals(listOf("pkg.a", "w2", "HANDLER"), failureTuples(first).single())
        assertEquals(first.reservationsReleased, second.reservationsReleased)
        assertTrue(first.reservationsReleased.isEmpty())
        assertEquals(
            listOf("duplicate", null, "cooldown until day 13", null, null, null),
            first.eliminations.map { it.detail },
        )
    }
}