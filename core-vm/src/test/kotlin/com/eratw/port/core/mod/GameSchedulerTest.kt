package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredJobTemplate
import com.eratw.port.core.patch.DeclaredPhase
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.JobTemplateDeclaration
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

    private fun template(pkg: String, key: String, priority: Int = 0): DeclaredJobTemplate =
        DeclaredJobTemplate(pkg, JobTemplateDeclaration(key, phase = "DAY_END", priority = priority))

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
    fun `活跃包过滤不推进非活跃静态槽与动态实例`() {
        val scheduler = JobScheduler.of(
            listOf(job("pkgA", "a", firstDay = 10L), job("pkgB", "b", firstDay = 10L)),
            templates = listOf(template("pkgA", "ta"), template("pkgB", "tb")),
        )
        scheduler.scheduleOnce("pkgA", "ta", 10L)
        val inactiveInstance = scheduler.scheduleOnce("pkgB", "tb", 10L)
        var aRuns = 0
        var bRuns = 0
        var bSlotRuns = 0
        scheduler.bind("pkgA", "a") { aRuns++ }
        scheduler.bind("pkgB", "b") { bSlotRuns++ }
        scheduler.bindTemplate("pkgB", "tb") { bRuns++ }

        val filtered = scheduler.runDue(GameTime(10, "DAY_END"), setOf("pkgA"))

        assertEquals(listOf("a"), filtered.fired.map { it.jobId })
        assertEquals(1, aRuns)
        assertEquals(0, bRuns)
        assertEquals(0, bSlotRuns)
        assertEquals(10L, scheduler.nextFireDay("pkgB", "b"))
        assertEquals(JobState.PENDING, scheduler.instances().single { it.instanceKey == inactiveInstance }.state)
        assertTrue(scheduler.runDue(GameTime(10, "DAY_END"), emptySet()).fired.isEmpty())

        val unfiltered = scheduler.runDue(GameTime(10, "DAY_END"))
        assertEquals(listOf("b"), unfiltered.fired.map { it.jobId })
        assertEquals(JobScheduler.DONE, scheduler.nextFireDay("pkgB", "b"))
        assertEquals(1, bRuns)
        assertEquals(1, bSlotRuns)
        assertEquals(JobState.DONE, scheduler.instances().single { it.instanceKey == inactiveInstance }.state)
    }

    @Test
    fun `跨包动态实例按模板priority再按声明序执行`() {
        val scheduler = JobScheduler.of(
            emptyList(),
            templates = listOf(
                template("pkgA", "late", priority = 10),
                template("pkgB", "first", priority = 0),
                template("pkgC", "second", priority = 0),
            ),
        )
        val order = mutableListOf<String>()
        scheduler.scheduleOnce("pkgA", "late", 5L)
        scheduler.scheduleOnce("pkgB", "first", 10L)
        scheduler.scheduleOnce("pkgC", "second", 1L)
        scheduler.bindTemplate("pkgA", "late") { order += "late" }
        scheduler.bindTemplate("pkgB", "first") { order += "first" }
        scheduler.bindTemplate("pkgC", "second") { order += "second" }

        val report = scheduler.runDue(GameTime(10, "DAY_END"))

        assertEquals(listOf("first", "second", "late"), order)
        assertEquals(3, report.firedInstances.size)
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

    @Test
    fun `旧代注册回滚不删新一代处理器`() {
        val scheduler = JobScheduler.of(
            listOf(job("pkgA", "j1")),
            templates = listOf(template("pkgA", "t1")),
        )
        val staleJob = scheduler.bind("pkgA", "j1") { error("旧代") }
        staleJob.unregister()
        var freshRuns = 0
        scheduler.bind("pkgA", "j1") { freshRuns++ }
        // 重装之后旧代 Registration 才晚到：必须按身份判据跳过，不得删掉新处理器。
        staleJob.unregister()
        scheduler.runDue(GameTime(0, "DAY_END"))
        assertEquals(1, freshRuns)

        val staleTemplate = scheduler.bindTemplate("pkgA", "t1") { error("旧代模板") }
        staleTemplate.unregister()
        var freshInstanceRuns = 0
        scheduler.bindTemplate("pkgA", "t1") { freshInstanceRuns++ }
        staleTemplate.unregister()
        scheduler.scheduleOnce("pkgA", "t1", 0L)
        scheduler.runDue(GameTime(0, "DAY_END"))
        assertEquals(1, freshInstanceRuns)
    }
}
