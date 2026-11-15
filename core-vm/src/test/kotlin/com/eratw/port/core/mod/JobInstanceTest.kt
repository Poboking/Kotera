package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredJobTemplate
import com.eratw.port.core.patch.JobCatchUp
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.JobTemplateDeclaration
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.JobContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 动态任务实例契约闸（§21.1，ABI-10 Candidate）。
 *
 * 钉住：实例键格式与序号单调、模板未声明/负日拒绝、cancel/reschedule 幂等、payloadRef
 * 透传、MISS_ONCE/MISS_SKIP/REPLAY 三档补算（含 time.day 与 truncations/CATCHUP_TRUNCATED）、
 * 相位过滤、handler 失败隔离、静态槽同批零回归、无实例旁路、快照恢复后序号接续。
 */
class JobInstanceTest {

    private fun template(
        pkg: String,
        key: String,
        phase: String = "DAY_END",
        priority: Int = 0,
        catchUp: JobCatchUp = JobCatchUp.MISS_ONCE,
        maxCatchUp: Long? = null,
    ): DeclaredJobTemplate = DeclaredJobTemplate(
        pkg,
        JobTemplateDeclaration(key, phase, priority, catchUp, maxCatchUp),
    )

    private fun scheduler(vararg templates: DeclaredJobTemplate): JobScheduler =
        JobScheduler.of(emptyList(), null, templates.toList())

    @Test
    fun `实例键格式与序号按模板单调`() {
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        val k1 = s.scheduleOnce("pkgA", "APPOINTMENT", 10L)
        val k2 = s.scheduleOnce("pkgA", "APPOINTMENT", 5L, EntityId(9L))
        assertEquals("pkgA::APPOINTMENT#0", k1)
        assertEquals("pkgA::APPOINTMENT#1", k2)
        // instances() 确定性序：atDay 升序 + 实例键。
        assertEquals(listOf(k2, k1), s.instances().map { it.instanceKey })
    }

    @Test
    fun `模板未声明与负触发日拒绝`() {
        val empty = scheduler()
        assertThrows<IllegalArgumentException> {
            empty.scheduleOnce("pkgA", "NOPE", 1L)
        }
        assertThrows<IllegalArgumentException> {
            empty.bindTemplate("pkgA", "NOPE") { }
        }
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        assertThrows<IllegalArgumentException> {
            s.scheduleOnce("pkgA", "APPOINTMENT", -1L)
        }
    }

    @Test
    fun `MISS_ONCE 当日触发一次且time_day用now`() {
        val seen = mutableListOf<JobContext>()
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        s.bindTemplate("pkgA", "APPOINTMENT") { seen += it }
        s.scheduleOnce("pkgA", "APPOINTMENT", 10L)

        val report = s.runDue(GameTime(10L, "DAY_END"))
        assertEquals(1, report.firedInstances.size)
        assertEquals(1, seen.size)
        assertEquals(10L, seen.single().time.day)
        assertEquals("DAY_END", seen.single().time.phase)
        assertEquals("APPOINTMENT", seen.single().templateKey)
        assertEquals("pkgA::APPOINTMENT#0", seen.single().instanceKey)
        assertEquals(JobState.DONE, s.instances().single().state)
        assertTrue(s.runDue(GameTime(11L, "DAY_END")).firedInstances.isEmpty())
    }

    @Test
    fun `MISS_ONCE 过期只补一次不是每个错过日各一次`() {
        val seen = mutableListOf<Long>()
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        s.bindTemplate("pkgA", "APPOINTMENT") { seen += it.time.day }
        s.scheduleOnce("pkgA", "APPOINTMENT", 3L)

        assertTrue(s.runDue(GameTime(2L, "DAY_END")).firedInstances.isEmpty())
        val report = s.runDue(GameTime(14L, "DAY_END"))
        assertEquals(1, report.firedInstances.size)
        assertEquals(listOf(14L), seen)
        assertEquals(JobState.DONE, s.instances().single().state)
    }

    @Test
    fun `MISS_SKIP 过期不触发进skipped_当日触发`() {
        val expiredRuns = mutableListOf<Long>()
        val expired = scheduler(template("pkgA", "MAIL", catchUp = JobCatchUp.MISS_SKIP))
        expired.bindTemplate("pkgA", "MAIL") { expiredRuns += it.time.day }
        val expiredKey = expired.scheduleOnce("pkgA", "MAIL", 3L)

        val report = expired.runDue(GameTime(10L, "DAY_END"))
        assertTrue(report.firedInstances.isEmpty())
        assertEquals(listOf(expiredKey), report.skipped)
        assertTrue(expiredRuns.isEmpty())
        assertEquals(JobState.DONE, expired.instances().single().state)

        val sameDayRuns = mutableListOf<Long>()
        val sameDay = scheduler(template("pkgA", "MAIL", catchUp = JobCatchUp.MISS_SKIP))
        sameDay.bindTemplate("pkgA", "MAIL") { sameDayRuns += it.time.day }
        sameDay.scheduleOnce("pkgA", "MAIL", 10L)
        val sameReport = sameDay.runDue(GameTime(10L, "DAY_END"))
        assertEquals(1, sameReport.firedInstances.size)
        assertTrue(sameReport.skipped.isEmpty())
        assertEquals(listOf(10L), sameDayRuns)
    }

    @Test
    fun `REPLAY 逐错过日各触发一次且time_day递进`() {
        val seen = mutableListOf<Long>()
        val s = scheduler(
            template("pkgA", "GIFT", catchUp = JobCatchUp.REPLAY, maxCatchUp = 10L),
        )
        s.bindTemplate("pkgA", "GIFT") { seen += it.time.day }
        s.scheduleOnce("pkgA", "GIFT", 5L)

        val report = s.runDue(GameTime(8L, "DAY_END"))
        assertEquals(listOf(5L, 6L, 7L, 8L), seen)
        assertEquals(1, report.firedInstances.size)
        assertTrue(report.truncations.isEmpty())
        assertEquals(JobState.DONE, s.instances().single().state)
    }

    @Test
    fun `REPLAY 超maxCatchUp截断并报CATCHUP_TRUNCATED`() {
        val seen = mutableListOf<Long>()
        val s = scheduler(
            template("pkgA", "GIFT", catchUp = JobCatchUp.REPLAY, maxCatchUp = 3L),
        )
        s.bindTemplate("pkgA", "GIFT") { seen += it.time.day }
        val key = s.scheduleOnce("pkgA", "GIFT", 5L)

        val report = s.runDue(GameTime(10L, "DAY_END"))
        assertEquals(listOf(5L, 6L, 7L), seen)
        val truncation = report.truncations.single()
        assertEquals(key, truncation.instanceKey)
        assertEquals(6L, truncation.requested)
        assertEquals(3L, truncation.applied)
        assertEquals("CATCHUP_TRUNCATED", truncation.reasonCode)
        assertEquals(JobState.DONE, s.instances().single().state)
    }

    @Test
    fun `相位过滤_只在模板相位派发`() {
        var runs = 0
        val s = scheduler(template("pkgA", "APPOINTMENT", phase = "DAY_END"))
        s.bindTemplate("pkgA", "APPOINTMENT") { runs++ }
        s.scheduleOnce("pkgA", "APPOINTMENT", 10L)

        assertTrue(s.runDue(GameTime(10L, "DAY_START")).firedInstances.isEmpty())
        assertEquals(0, runs)
        // 空相位 = 不限相位。
        assertEquals(1, s.runDue(GameTime(10L, "")).firedInstances.size)
        assertEquals(1, runs)
    }

    @Test
    fun `handler失败隔离_实例仍DONE且其余实例继续`() {
        val s = scheduler(
            template("pkgA", "BAD"),
            template("pkgB", "GOOD"),
        )
        s.bindTemplate("pkgA", "BAD") { error("boom") }
        var goodRuns = 0
        s.bindTemplate("pkgB", "GOOD") { goodRuns++ }
        val badKey = s.scheduleOnce("pkgA", "BAD", 10L)
        s.scheduleOnce("pkgB", "GOOD", 10L)

        val report = s.runDue(GameTime(10L, "DAY_END"))
        assertEquals(2, report.firedInstances.size)
        assertEquals(1, report.failures.size)
        assertEquals(badKey, report.failures.single().instanceKey)
        assertEquals("BAD", report.failures.single().jobId)
        assertEquals(1, goodRuns)
        assertTrue(s.instances().all { it.state == JobState.DONE })
        assertTrue(s.runDue(GameTime(11L, "DAY_END")).firedInstances.isEmpty())
    }

    @Test
    fun `REPLAY 处理失败仍逐日补齐并DONE`() {
        val s = scheduler(
            template("pkgA", "GIFT", catchUp = JobCatchUp.REPLAY, maxCatchUp = 5L),
        )
        s.bindTemplate("pkgA", "GIFT") { error("boom") }
        s.scheduleOnce("pkgA", "GIFT", 5L)

        val report = s.runDue(GameTime(7L, "DAY_END"))
        assertEquals(3, report.failures.size)
        assertEquals(JobState.DONE, s.instances().single().state)
        assertTrue(s.runDue(GameTime(8L, "DAY_END")).firedInstances.isEmpty())
    }

    @Test
    fun `cancel 幂等且取消后不触发`() {
        var runs = 0
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        s.bindTemplate("pkgA", "APPOINTMENT") { runs++ }
        val key = s.scheduleOnce("pkgA", "APPOINTMENT", 10L)
        assertTrue(s.cancel("pkgA", key))
        assertFalse(s.cancel("pkgA", key))
        assertFalse(s.cancel("pkgA", "pkgA::APPOINTMENT#99"))
        assertFalse(s.cancel("pkgB", key))
        assertTrue(s.runDue(GameTime(10L, "DAY_END")).firedInstances.isEmpty())
        assertEquals(0, runs)
        assertEquals(JobState.CANCELED, s.instances().single().state)
    }

    @Test
    fun `reschedule 同日true_改日生效_完成与取消后false`() {
        var runs = 0
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        s.bindTemplate("pkgA", "APPOINTMENT") { runs++ }
        val key = s.scheduleOnce("pkgA", "APPOINTMENT", 10L)

        assertTrue(s.reschedule("pkgA", key, 10L))
        assertTrue(s.reschedule("pkgA", key, 20L))
        assertThrows<IllegalArgumentException> { s.reschedule("pkgA", key, -1L) }
        assertTrue(s.runDue(GameTime(15L, "DAY_END")).firedInstances.isEmpty())
        assertEquals(0, runs)
        assertEquals(1, s.runDue(GameTime(20L, "DAY_END")).firedInstances.size)
        assertEquals(1, runs)
        assertFalse(s.reschedule("pkgA", key, 30L))
        assertFalse(s.reschedule("pkgB", key, 30L))
        assertFalse(s.reschedule("pkgA", "pkgA::APPOINTMENT#99", 30L))
    }

    @Test
    fun `payloadRef与模板键透传到JobContext`() {
        var ctx: JobContext? = null
        val s = scheduler(template("pkgA", "APPOINTMENT"))
        s.bindTemplate("pkgA", "APPOINTMENT") { ctx = it }
        val key = s.scheduleOnce("pkgA", "APPOINTMENT", 10L, EntityId(123L))
        s.runDue(GameTime(10L, "DAY_END"))

        val context = assertNotNull(ctx)
        assertEquals(key, context.instanceKey)
        assertEquals("APPOINTMENT", context.templateKey)
        assertEquals(EntityId(123L), context.payloadRef)
        assertEquals("pkgA", context.packageId)
    }

    @Test
    fun `静态槽与模板实例同批派发_静态语义不变`() {
        val staticJob = DeclaredJob(
            "pkgA",
            JobDeclaration("J1", "DAY_END", firstDay = 10L, everyDays = null, priority = 0),
        )
        val s = JobScheduler.of(
            listOf(staticJob),
            null,
            listOf(template("pkgA", "APPOINTMENT")),
        )
        var staticRuns = 0
        var instanceRuns = 0
        s.bind("pkgA", "J1") { staticRuns++ }
        s.bindTemplate("pkgA", "APPOINTMENT") { instanceRuns++ }
        s.scheduleOnce("pkgA", "APPOINTMENT", 10L)

        val report = s.runDue(GameTime(10L, "DAY_END"))
        assertEquals(1, report.fired.size)
        assertEquals(1, report.firedInstances.size)
        assertEquals(1, staticRuns)
        assertEquals(1, instanceRuns)
        assertEquals(JobScheduler.DONE, s.nextFireDay("pkgA", "J1"))
    }

    @Test
    fun `无模板无实例旁路`() {
        val s = JobScheduler.of(emptyList(), null, emptyList())
        val report = s.runDue(GameTime(10L, "DAY_END"))
        assertTrue(report.fired.isEmpty())
        assertTrue(report.firedInstances.isEmpty())
        assertTrue(report.failures.isEmpty())
        assertTrue(report.skipped.isEmpty())
        assertTrue(report.truncations.isEmpty())
        assertTrue(s.instances().isEmpty())
        assertTrue(s.snapshot().instances.isEmpty())
        assertTrue(s.snapshot().nextSeqByTemplate.isEmpty())
    }

    @Test
    fun `快照恢复后实例保留且序号接续`() {
        val tpl = template("pkgA", "APPOINTMENT")
        val s = scheduler(tpl)
        s.scheduleOnce("pkgA", "APPOINTMENT", 10L)
        s.scheduleOnce("pkgA", "APPOINTMENT", 12L)
        val snap = s.snapshot()

        val restored = JobScheduler.of(emptyList(), snap, listOf(tpl))
        assertEquals(2, restored.instances().size)
        assertEquals("pkgA::APPOINTMENT#2", restored.scheduleOnce("pkgA", "APPOINTMENT", 14L))
        assertEquals(
            listOf("pkgA::APPOINTMENT#0", "pkgA::APPOINTMENT#1", "pkgA::APPOINTMENT#2"),
            restored.instances().map { it.instanceKey },
        )
    }
}
