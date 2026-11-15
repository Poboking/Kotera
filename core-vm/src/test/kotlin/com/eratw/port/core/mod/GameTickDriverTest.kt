package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredPhase
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.modapi.GameTime
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GameTickDriver 契约闸（§20.2，ABI-10 Candidate）。
 *
 * 钉住：相位先于任务的固定节拍序、两边失败合并上报、相位过滤、任务态随节拍推进、
 * 无处理器完全旁路。
 */
class GameTickDriverTest {

    private fun phase(pkg: String, name: String): DeclaredPhase =
        DeclaredPhase(pkg, PhaseDeclaration(name, 0))

    private fun job(
        pkg: String,
        jobId: String,
        phase: String = "DAY_END",
        firstDay: Long = 0L,
        everyDays: Long? = null,
    ): DeclaredJob = DeclaredJob(pkg, JobDeclaration(jobId, phase, firstDay, everyDays, 0))

    private fun driver(
        phases: List<DeclaredPhase> = emptyList(),
        jobs: List<DeclaredJob> = emptyList(),
    ): Triple<GameTickDriver, PhaseRegistry, JobScheduler> {
        val registry = PhaseRegistry.of(phases)
        val scheduler = JobScheduler.of(jobs)
        return Triple(GameTickDriver(registry, scheduler), registry, scheduler)
    }

    @Test
    fun `相位先于任务执行`() {
        val (tick, registry, scheduler) = driver(
            phases = listOf(phase("pkgA", "TICK")),
            jobs = listOf(job("pkgA", "j1", phase = "TICK", firstDay = 1)),
        )
        val order = mutableListOf<String>()
        registry.subscribe("TICK", "pkgA") { order += "phase" }
        scheduler.bind("pkgA", "j1") { order += "job" }
        val report = tick.tick(GameTime(1, "TICK"))
        assertEquals(listOf("phase", "job"), order)
        assertEquals(1, report.firedPhaseHandlers)
        assertEquals(1, report.firedJobs)
        assertFalse(report.hasFailures)
    }

    @Test
    fun `相位与任务失败合并上报且互不阻塞`() {
        val (tick, registry, scheduler) = driver(
            phases = listOf(phase("pkgA", "TICK")),
            jobs = listOf(job("pkgA", "bad", phase = "TICK"), job("pkgA", "good", phase = "TICK")),
        )
        registry.subscribe("TICK", "pkgA") { error("phase boom") }
        scheduler.bind("pkgA", "bad") { error("job boom") }
        var good = 0
        scheduler.bind("pkgA", "good") { good++ }
        val report = tick.tick(GameTime(1, "TICK"))
        assertEquals(1, report.phase.failures.size)
        assertEquals(1, report.jobs.failures.size)
        assertEquals(2, report.failureCount)
        assertTrue(report.hasFailures)
        assertEquals(1, good)
        assertEquals(1, report.firedPhaseHandlers)
        assertEquals(2, report.firedJobs)
    }

    @Test
    fun `任务按节拍相位过滤`() {
        val (tick, _, scheduler) = driver(
            jobs = listOf(job("pkgA", "j1", phase = "DAY_END", firstDay = 1)),
        )
        var runs = 0
        scheduler.bind("pkgA", "j1") { runs++ }
        assertTrue(tick.tick(GameTime(1, "DAY_START")).jobs.fired.isEmpty())
        assertEquals(0, runs)
        assertEquals(1, tick.tick(GameTime(1, "DAY_END")).jobs.fired.size)
        assertEquals(1, runs)
    }

    @Test
    fun `任务态随节拍推进且不重复触发`() {
        val (tick, _, scheduler) = driver(
            jobs = listOf(job("pkgA", "periodic", phase = "DAY_END", firstDay = 2, everyDays = 3)),
        )
        var runs = 0
        scheduler.bind("pkgA", "periodic") { runs++ }
        tick.tick(GameTime(2, "DAY_END"))
        assertEquals(5L, scheduler.nextFireDay("pkgA", "periodic"))
        assertTrue(tick.tick(GameTime(3, "DAY_END")).jobs.fired.isEmpty())
        tick.tick(GameTime(5, "DAY_END"))
        assertEquals(2, runs)
        assertEquals(8L, scheduler.nextFireDay("pkgA", "periodic"))
    }

    @Test
    fun `空驱动器完全旁路`() {
        val (tick, _, _) = driver()
        val report = tick.tick(GameTime(1, "DAY_END"))
        assertEquals(0, report.firedPhaseHandlers)
        assertEquals(0, report.firedJobs)
        assertEquals(0, report.failureCount)
        assertFalse(report.hasFailures)
    }

    @Test
    fun `空相位节拍不派发相位但仍跑到期任务`() {
        val (tick, registry, scheduler) = driver(
            phases = listOf(phase("pkgA", "TICK")),
            jobs = listOf(job("pkgA", "j1", phase = "DAY_END", firstDay = 1)),
        )
        var phaseRuns = 0
        registry.subscribe("TICK", "pkgA") { phaseRuns++ }
        var jobRuns = 0
        scheduler.bind("pkgA", "j1") { jobRuns++ }
        val report = tick.tick(GameTime(1, ""))
        assertEquals(0, phaseRuns)
        assertEquals(0, report.firedPhaseHandlers)
        assertEquals(1, jobRuns)
        assertEquals(1, report.firedJobs)
    }
}
