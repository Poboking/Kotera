package com.eratw.port.core.mod

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
 * DecisionPipeline 状态快照/恢复契约闸（M6-2，§21.3.5 + §8）。
 *
 * 钉住：空态与满态往返、抽取序号生产路径自持与跨快照延续、注入 lambda 的波 E 语义、
 * 每日/每行动者限额跨快照保留、冷却跨快照保留、held 预留跨快照保留与陈旧预留回收、
 * 种子不符 fail-closed、快照与恢复的不可变性、restore 后报告形状不变。
 *
 * 本车是功能新增（不是 bug 修复），根因层断言不适用 AGENTS §3.1。
 */
class DecisionPipelineStateTest {

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
    )

    private fun ctx(
        domain: String = "ACTION",
        day: Long = 10L,
        phase: String = "DAY_START",
        actor: Long? = null,
    ): ActionSelectionContext = ActionSelectionContext(
        domain = domain,
        time = GameTime(day, phase),
        actor = actor?.let { EntityId(it) },
    )

    /** 决策随机流 spy：记录派生键/上界/种子，按 [answers] 逐次回答（默认 0）。 */
    private class SpyRandom(private val answers: List<Long> = emptyList()) : DecisionRandomSource {
        val keys = ArrayList<DecisionDrawKey>()
        val bounds = ArrayList<Long>()
        val seeds = ArrayList<Long>()

        override fun draw(seed: Long, key: DecisionDrawKey, bound: Long): Long {
            seeds += seed
            keys += key
            bounds += bound
            return answers.getOrElse(keys.size - 1) { 0L }
        }
    }

    private fun registerThree(pipeline: DecisionPipeline) {
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
    }

    @Test
    fun `01 空态快照往返且无 provider 旁路仍 NONE`() {
        val seed = DecisionSeed.derive("eratw-f1".toByteArray())
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), seed)
        val state = pipeline.snapshotState()
        assertEquals(seed, state.decisionSeed)
        assertEquals(0, state.nextOrdinal)
        assertTrue(state.dayCounts.isEmpty())
        assertTrue(state.actorCounts.isEmpty())
        assertTrue(state.cooldownUntil.isEmpty())
        assertTrue(state.heldReservations.isEmpty())

        val restored = DecisionPipeline.of(listOf("pkg.a"), state)
        assertEquals(state, restored.snapshotState())
        val report = restored.run(ctx())
        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertNull(report.chosen)
        assertEquals(0, report.evaluated)
        assertTrue(report.eliminations.isEmpty())
        assertTrue(report.failures.isEmpty())
        assertTrue(report.reservationsReleased.isEmpty())
    }

    @Test
    fun `02 满状态恢复逐段还原`() {
        val full = DecisionState(
            decisionSeed = 4242L,
            nextOrdinal = 9,
            dayCounts = listOf(
                DayCountEntry("pkg.b", "z", 10L, 2),
                DayCountEntry("pkg.a", "k", 10L, 1),
            ),
            actorCounts = listOf(
                ActorCountEntry("pkg.a", "k", 10L, 7L, 1),
                ActorCountEntry("pkg.b", "z", 10L, 3L, 2),
            ),
            cooldownUntil = listOf(
                CooldownEntry("pkg.b::x", 12L),
                CooldownEntry("pkg.a::cd", 11L),
            ),
            heldReservations = listOf(
                ReservationKey("slot", "2"),
                ReservationKey("chara", "1"),
            ),
        )
        val restored = DecisionPipeline.of(listOf("pkg.a", "pkg.b"), full)
        val actual = restored.snapshotState()
        assertEquals(4242L, actual.decisionSeed)
        assertEquals(9, actual.nextOrdinal)
        assertEquals(
            full.dayCounts.sortedWith(compareBy({ it.packageId }, { it.candidateKey }, { it.day })),
            actual.dayCounts,
        )
        assertEquals(
            full.actorCounts.sortedWith(
                compareBy({ it.packageId }, { it.candidateKey }, { it.day }, { it.actorRaw }),
            ),
            actual.actorCounts,
        )
        assertEquals(full.cooldownUntil.sortedBy { it.scopedKey }, actual.cooldownUntil)
        assertEquals(full.heldReservations.sortedWith(compareBy({ it.kind }, { it.key })), actual.heldReservations)
        assertEquals(setOf(ReservationKey("slot", "2"), ReservationKey("chara", "1")), restored.heldReservations())
    }

    @Test
    fun `03 perDay 限额跨快照保留`() {
        val a = DecisionPipeline.of(listOf("pkg.a"), 16L)
        val api = a.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { listOf(candidate(key = "p", perDay = 1)) }
        api.on("p") { calls++ }
        assertEquals(ActionExecutionOutcome.COMPLETED, a.run(ctx(day = 5)).outcome)

        val b = DecisionPipeline.of(listOf("pkg.a"), a.snapshotState())
        val apiB = b.actionApi("pkg.a")
        apiB.provide("ACTION") { listOf(candidate(key = "p", perDay = 1)) }
        apiB.on("p") { calls++ }

        val limited = b.run(ctx(day = 5))
        assertEquals(ActionExecutionOutcome.NONE, limited.outcome)
        assertEquals(DecisionReasonCodes.LIMIT_DAY, limited.eliminations.single().reasonCode)
        assertEquals(1, calls)
        assertEquals(ActionExecutionOutcome.COMPLETED, b.run(ctx(day = 6)).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `04 perActorPerDay 限额跨快照保留`() {
        val claim = ReservationKey("chara", "4")
        val a = DecisionPipeline.of(listOf("pkg.a"), 17L)
        val api = a.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") { selection ->
            listOf(
                candidate(
                    key = "act",
                    actor = selection.actor?.raw ?: 1L,
                    perActorPerDay = 1,
                    claims = listOf(claim),
                ),
            )
        }
        api.on("act") { calls++ }
        assertEquals(ActionExecutionOutcome.COMPLETED, a.run(ctx(day = 5, actor = 1L)).outcome)

        val b = DecisionPipeline.of(listOf("pkg.a"), a.snapshotState())
        val apiB = b.actionApi("pkg.a")
        apiB.provide("ACTION") { selection ->
            listOf(candidate(key = "act", actor = selection.actor?.raw ?: 1L, perActorPerDay = 1))
        }
        apiB.on("act") { calls++ }

        val limited = b.run(ctx(day = 5, actor = 1L))
        assertEquals(ActionExecutionOutcome.NONE, limited.outcome)
        assertEquals(DecisionReasonCodes.LIMIT_ACTOR, limited.eliminations.single().reasonCode)
        assertEquals(1, calls)
        assertEquals(ActionExecutionOutcome.COMPLETED, b.run(ctx(day = 5, actor = 2L)).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `05 冷却跨快照保留与过期恢复`() {
        val a = DecisionPipeline.of(listOf("pkg.a"), 18L)
        val api = a.actionApi("pkg.a")
        var calls = 0
        api.provide("ACTION") {
            listOf(candidate(key = "cd", cooldownKey = "cd", cooldownDays = 2))
        }
        api.on("cd") { calls++ }
        assertEquals(ActionExecutionOutcome.COMPLETED, a.run(ctx(day = 10)).outcome)

        val b = DecisionPipeline.of(listOf("pkg.a"), a.snapshotState())
        val apiB = b.actionApi("pkg.a")
        apiB.provide("ACTION") {
            listOf(candidate(key = "cd", cooldownKey = "cd", cooldownDays = 2))
        }
        apiB.on("cd") { calls++ }

        val sameDay = b.run(ctx(day = 10))
        assertEquals(ActionExecutionOutcome.NONE, sameDay.outcome)
        assertEquals(DecisionReasonCodes.COOLDOWN, sameDay.eliminations.single().reasonCode)
        val nextDay = b.run(ctx(day = 11))
        assertEquals(DecisionReasonCodes.COOLDOWN, nextDay.eliminations.single().reasonCode)
        assertEquals(ActionExecutionOutcome.COMPLETED, b.run(ctx(day = 12)).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `06 held 预留跨快照保留`() {
        val claim = ReservationKey("chara", "7")
        val a = DecisionPipeline.of(listOf("pkg.a"), 55L)
        var captured: DecisionState? = null
        val api = a.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "hold", claims = listOf(claim))) }
        api.on("hold") { captured = a.snapshotState() }
        assertEquals(ActionExecutionOutcome.COMPLETED, a.run(ctx(day = 3)).outcome)
        assertTrue(a.heldReservations().isEmpty())

        val heldState = assertNotNull(captured)
        assertEquals(listOf(claim), heldState.heldReservations)
        val b = DecisionPipeline.of(listOf("pkg.a"), heldState)
        assertEquals(setOf(claim), b.heldReservations())

        val apiB = b.actionApi("pkg.a")
        var calls = 0
        apiB.provide("ACTION") { listOf(candidate(key = "other", claims = listOf(claim))) }
        apiB.on("other") { calls++ }
        val report = b.run(ctx(day = 3))
        assertEquals(ActionExecutionOutcome.NONE, report.outcome)
        assertEquals(DecisionReasonCodes.RESERVATION_CONFLICT, report.eliminations.single().reasonCode)
        assertEquals(0, calls)
    }

    @Test
    fun `07 ordinal 跨快照延续不归零`() {
        val randomA = SpyRandom()
        val a = DecisionPipeline.of(listOf("pkg.a"), 777L, randomA)
        registerThree(a)
        assertEquals(ActionExecutionOutcome.COMPLETED, a.run(ctx(day = 10)).outcome)
        val firstNth = randomA.keys.single().nth
        assertEquals(0, firstNth)
        val state = a.snapshotState()
        assertEquals(1, state.nextOrdinal)

        val randomB = SpyRandom()
        val b = DecisionPipeline.of(listOf("pkg.a"), state, randomB)
        assertEquals(1, b.snapshotState().nextOrdinal)
        registerThree(b)
        b.run(ctx(day = 10))
        a.run(ctx(day = 10))

        assertEquals(1, randomB.keys.single().nth)
        assertEquals(1, randomA.keys.last().nth)
        assertNotEquals(firstNth, randomB.keys.single().nth)
        assertEquals(randomA.keys.last(), randomB.keys.single())
    }

    @Test
    fun `08 注入 lambda 路径不推进内部序号（波 E 语义）`() {
        val random = SpyRandom()
        var lambdaCalls = 0
        val pipeline = DecisionPipeline.of(
            listOf("pkg.a"),
            9L,
            random,
            arbitrationOrdinal = {
                lambdaCalls++
                5
            },
        )
        registerThree(pipeline)
        repeat(2) { pipeline.run(ctx(day = 10)) }
        assertEquals(2, lambdaCalls)
        assertEquals(listOf(5, 5), random.keys.map { it.nth })
        assertEquals(0, pipeline.snapshotState().nextOrdinal)
    }

    @Test
    fun `09 陈旧预留回收与存活预留保留`() {
        val alive = ReservationKey("chara", "1")
        val stale = ReservationKey("slot", "2")
        val state = DecisionState(
            decisionSeed = 123L,
            nextOrdinal = 0,
            dayCounts = emptyList(),
            actorCounts = emptyList(),
            cooldownUntil = emptyList(),
            heldReservations = listOf(alive, stale, stale),
        )
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 123L)
        val report = pipeline.restoreState(state) { it == alive }
        assertEquals(listOf(stale), report.droppedStaleReservations)
        assertEquals(listOf(alive), pipeline.heldReservations().toList())

        val keepAll = DecisionPipeline.of(listOf("pkg.a"), 123L)
        val keepReport = keepAll.restoreState(state)
        assertTrue(keepReport.droppedStaleReservations.isEmpty())
        assertEquals(listOf(alive, stale), keepAll.heldReservations().toList())
    }

    @Test
    fun `10 种子不符 fail-closed 且快照与恢复不可变`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 111L)
        val foreign = DecisionState(
            decisionSeed = 222L,
            nextOrdinal = 0,
            dayCounts = emptyList(),
            actorCounts = emptyList(),
            cooldownUntil = emptyList(),
            heldReservations = emptyList(),
        )
        val mismatch = assertThrows<IllegalArgumentException> { pipeline.restoreState(foreign) }
        assertTrue(mismatch.message!!.contains("种子不符"))

        val a = DecisionPipeline.of(listOf("pkg.a"), 111L)
        val api = a.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "p", perDay = 3)) }
        api.on("p") {}
        a.run(ctx(day = 1))
        a.run(ctx(day = 1))
        val snap = a.snapshotState()
        assertEquals(2, snap.dayCounts.single().count)
        a.run(ctx(day = 1))
        assertEquals(2, snap.dayCounts.single().count, "快照必须与实例隔离")

        val mutableDays = mutableListOf(DayCountEntry("pkg.a", "p", 5L, 2))
        val mutableState = DecisionState(
            decisionSeed = 111L,
            nextOrdinal = 0,
            dayCounts = mutableDays,
            actorCounts = mutableListOf(),
            cooldownUntil = mutableListOf(),
            heldReservations = mutableListOf(),
        )
        val q = DecisionPipeline.of(listOf("pkg.a"), 111L)
        q.restoreState(mutableState)
        mutableDays.clear()
        val apiQ = q.actionApi("pkg.a")
        apiQ.provide("ACTION") { listOf(candidate(key = "p", perDay = 3)) }
        apiQ.on("p") {}
        assertEquals(ActionExecutionOutcome.COMPLETED, q.run(ctx(day = 5)).outcome)
        assertEquals(ActionExecutionOutcome.NONE, q.run(ctx(day = 5)).outcome)
        assertEquals(
            DecisionReasonCodes.LIMIT_DAY,
            q.run(ctx(day = 5)).eliminations.single().reasonCode,
        )
    }

    @Test
    fun `11 restore 覆盖实例内既有计数`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), 31L)
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "p", perDay = 1)) }
        api.on("p") {}
        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 5)).outcome)
        assertEquals(
            DecisionReasonCodes.LIMIT_DAY,
            pipeline.run(ctx(day = 5)).eliminations.single().reasonCode,
        )
        val empty = pipeline.snapshotState().copy(
            dayCounts = emptyList(),
            actorCounts = emptyList(),
            nextOrdinal = 0,
        )
        pipeline.restoreState(empty)
        assertEquals(ActionExecutionOutcome.COMPLETED, pipeline.run(ctx(day = 5)).outcome)
    }

    @Test
    fun `12 restore 前后报告淘汰形状一致`() {
        fun build(seed: Long): DecisionPipeline {
            val random = SpyRandom()
            val pipeline = DecisionPipeline.of(listOf("pkg.a", "pkg.b"), seed, random)
            val apiA = pipeline.actionApi("pkg.a")
            apiA.constraint("no") { false }
            apiA.provide("ACTION") {
                listOf(
                    candidate("pkg.a", "dup", priority = 4),
                    candidate("pkg.a", "dup", priority = 4),
                    candidate("pkg.a", "blocked", priority = 4, constraints = listOf("no")),
                    candidate("pkg.a", "low", priority = 1),
                    candidate("pkg.a", "w1", priority = 4, weight = 2),
                    candidate("pkg.a", "w2", priority = 4, weight = 2),
                )
            }
            apiA.on("dup") {}
            apiA.on("blocked") {}
            apiA.on("low") {}
            apiA.on("w1") {}
            apiA.on("w2") {}
            return pipeline
        }

        val a = build(4242L)
        val before = a.run(ctx(day = 10))
        val b = build(4242L)
        b.restoreState(a.snapshotState())
        val after = b.run(ctx(day = 10))

        assertEquals(tuples(before), tuples(after))
        assertEquals(before.evaluated, after.evaluated)
        assertEquals(before.chosen?.candidateKey, after.chosen?.candidateKey)
        assertEquals(before.outcome, after.outcome)
        assertEquals(before.failures.size, after.failures.size)
    }

    @Test
    fun `13 同 seed 同候选集 restore 后抽取键一致`() {
        val randomA = SpyRandom()
        val a = DecisionPipeline.of(listOf("pkg.a"), 8080L, randomA)
        registerThree(a)
        repeat(4) { a.run(ctx(day = 7)) }
        val state = a.snapshotState()
        assertEquals(4, state.nextOrdinal)

        val randomB = SpyRandom()
        val b = DecisionPipeline.of(listOf("pkg.a"), state, randomB)
        registerThree(b)
        val reportA = a.run(ctx(day = 7))
        val reportB = b.run(ctx(day = 7))

        assertEquals(randomA.keys.last(), randomB.keys.single())
        assertEquals(4, randomB.keys.single().nth)
        assertEquals(reportA.chosen?.candidateKey, reportB.chosen?.candidateKey)
        assertEquals(reportA.outcome, reportB.outcome)
    }

    private fun tuples(report: DecisionReport): List<List<String>> =
        report.eliminations.map { listOf(it.packageId, it.candidateKey, it.reasonCode, it.detail ?: "-") }
}
