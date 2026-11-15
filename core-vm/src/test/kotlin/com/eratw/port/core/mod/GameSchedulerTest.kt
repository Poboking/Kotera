package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredPhase
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.modapi.GameTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PhaseRegistry + JobScheduler 契约闸（§20.2，ABI-10 Candidate）。
 *
 * 钉住：自定义相位确定性派发、固定相位可订阅/未声明拒绝、一次性任务不复活、周期任务
 * 跳过错过周期、任务态快照往返、多任务稳定序、失败隔离、无任务完全旁路、相位过滤。
 */
class GameSchedulerTest {

    private fun phase(pkg: String, name: String, priority: Int = 0): DeclaredPhase =
        DeclaredPhase(pkg, PhaseDeclaration(name, priority))

    private fun job(
        pkg: String,
        jobId: String,
        phase: String = "DAY_END",
        firstDay: Long = 0L,
        everyDays: Long? = null,
        priority: Int = 0,
    ): DeclaredJob = DeclaredJob(pkg, JobDeclaration(jobId, phase, firstDay, everyDays, priority))

    @Test
    fun `自定义相位按包链接序与注册序派发`() {
        val registry = PhaseRegistry.of(
            listOf(phase("pkgB", "FAMILY_TICK"), phase("pkgA", "FAMILY_TICK")),
            packageOrder = listOf("pkgB", "pkgA"),
        )
        val order = mutableListOf<String>()
        registry.subscribe("FAMILY_TICK", "pkgA") { order += "A" }
        registry.subscribe("FAMILY_TICK", "pkgB") { order += "B1" }
        registry.subscribe("FAMILY_TICK", "pkgB") { order += "B2" }
        val report = registry.dispatch(GameTime(day = 3, phase = "FAMILY_TICK"))
        assertEquals(listOf("B1", "B2", "A"), order)
        assertEquals(3, report.fired)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `固定相位可订阅_未声明相位拒绝`() {
        val registry = PhaseRegistry.of(emptyList())
        registry.subscribe("DAY_END", "pkgA") { }
        assertThrows<IllegalArgumentException> { registry.subscribe("NOPE", "pkgA") { } }
        assertTrue(registry.isDeclared("DAY_START"))
        assertTrue(registry.isDeclared("DAY_END"))
        assertTrue(registry.isDeclared("ACTION"))
    }

    @Test
    fun `一次性任务到日触发一次且不复活`() {
        val declarations = listOf(job("pkgA", "j1", firstDay = 10, everyDays = null))
        val scheduler = JobScheduler.of(declarations)
        var runs = 0
        scheduler.bind("pkgA", "j1") { runs++ }
        assertTrue(scheduler.runDue(GameTime(9, "DAY_END")).fired.isEmpty())
        assertEquals(1, scheduler.runDue(GameTime(10, "DAY_END")).fired.size)
        assertEquals(1, runs)
        assertEquals(JobScheduler.DONE, scheduler.nextFireDay("pkgA", "j1"))
        assertTrue(scheduler.runDue(GameTime(11, "DAY_END")).fired.isEmpty())

        val restored = JobScheduler.of(declarations, scheduler.snapshot())
        assertTrue(restored.runDue(GameTime(11, "DAY_END")).fired.isEmpty())
        assertEquals(JobScheduler.DONE, restored.nextFireDay("pkgA", "j1"))
    }

    @Test
    fun `周期任务按everyDays推进且跳过错过周期`() {
        val declarations = listOf(job("pkgA", "j2", firstDay = 10, everyDays = 5))
        val scheduler = JobScheduler.of(declarations)
        var runs = 0
        scheduler.bind("pkgA", "j2") { runs++ }
        scheduler.runDue(GameTime(10, "DAY_END"))
        assertEquals(15L, scheduler.nextFireDay("pkgA", "j2"))
        assertTrue(scheduler.runDue(GameTime(12, "DAY_END")).fired.isEmpty())
        scheduler.runDue(GameTime(16, "DAY_END"))
        assertEquals(20L, scheduler.nextFireDay("pkgA", "j2"))
        scheduler.runDue(GameTime(100, "DAY_END"))
        assertEquals(105L, scheduler.nextFireDay("pkgA", "j2"))
        assertEquals(3, runs)
    }

    @Test
    fun `任务快照往返_重启后下次触发日恢复`() {
        val declarations = listOf(job("pkgA", "j2", firstDay = 10, everyDays = 5))
        val scheduler = JobScheduler.of(declarations)
        scheduler.bind("pkgA", "j2") { }
        scheduler.runDue(GameTime(10, "DAY_END"))

        val restored = JobScheduler.of(declarations, scheduler.snapshot())
        assertEquals(15L, restored.nextFireDay("pkgA", "j2"))
        var runs = 0
        restored.bind("pkgA", "j2") { runs++ }
        assertTrue(restored.runDue(GameTime(14, "DAY_END")).fired.isEmpty())
        assertEquals(1, restored.runDue(GameTime(15, "DAY_END")).fired.size)
        assertEquals(1, runs)
    }

    @Test
    fun `同一触发点多任务稳定序`() {
        val scheduler = JobScheduler.of(
            listOf(
                job("pkgA", "j1", priority = 10),
                job("pkgB", "j2", priority = 0),
                job("pkgA", "j3", priority = 0),
            ),
        )
        val due = scheduler.dueJobs(GameTime(10, "DAY_END"))
        assertEquals(listOf("j2", "j3", "j1"), due.map { it.jobId })
    }

    @Test
    fun `一个任务抛异常_其余任务继续且报告失败`() {
        val scheduler = JobScheduler.of(
            listOf(job("pkgA", "bad"), job("pkgB", "good")),
        )
        scheduler.bind("pkgA", "bad") { error("boom") }
        var good = 0
        scheduler.bind("pkgB", "good") { good++ }
        val report = scheduler.runDue(GameTime(10, "DAY_END"))
        assertEquals(2, report.fired.size)
        assertEquals(1, report.failures.size)
        assertEquals("bad", report.failures.single().jobId)
        assertEquals(1, good)
    }

    @Test
    fun `无任务完全旁路`() {
        val scheduler = JobScheduler.of(emptyList())
        val report = scheduler.runDue(GameTime(10, "DAY_END"))
        assertTrue(report.fired.isEmpty())
        assertTrue(report.failures.isEmpty())
        assertTrue(scheduler.dueJobs(GameTime(10, "DAY_END")).isEmpty())
        assertTrue(scheduler.snapshot().nextFireDays.isEmpty())
    }

    @Test
    fun `未声明任务绑定拒绝与相位过滤`() {
        val scheduler = JobScheduler.of(listOf(job("pkgA", "j1", phase = "DAY_END")))
        assertThrows<IllegalArgumentException> { scheduler.bind("pkgA", "nope") { } }
        assertTrue(scheduler.dueJobs(GameTime(10, "DAY_START")).isEmpty())
        assertEquals(1, scheduler.dueJobs(GameTime(10, "DAY_END")).size)
        assertEquals(1, scheduler.dueJobs(GameTime(10, "")).size)
    }

    @Test
    fun `相位处理器异常_失败隔离且其余处理器继续`() {
        val registry = PhaseRegistry.of(
            listOf(phase("pkgA", "TICK"), phase("pkgB", "TICK")),
        )
        var b = 0
        registry.subscribe("TICK", "pkgA") { error("boom") }
        registry.subscribe("TICK", "pkgB") { b++ }
        val report = registry.dispatch(GameTime(1, "TICK"))
        assertEquals(2, report.fired)
        assertEquals(1, report.failures.size)
        assertEquals("pkgA", report.failures.single().packageId)
        assertEquals(1, b)
    }
}
