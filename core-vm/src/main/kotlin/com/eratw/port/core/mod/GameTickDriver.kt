package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.modapi.GameTime

/**
 * 游戏节拍驱动器（§20.2，ABI-10 Candidate；纯 JVM，无静态可变全局）。
 *
 * 一个节拍固定为「先派发相位、再跑到期任务」：
 * 相位处理器负责推进世界状态（日始 / 日终 / 动作点），任务处理器在同一节拍内看到的
 * 是相位推进之后的状态。两步各自失败隔离、互不阻塞（§18.4），合并报告供宿主上屏。
 *
 * 无相位处理器、无任务时 tick 为完全旁路（两边报告皆空，golden 零变化）。
 * 任务态持久化用 [JobSchedulerStateCodec] 编码 [JobScheduler.snapshot] 的字节，`.rt2`
 * 分段装配归宿主接线（K3/M4-3）。
 */
class GameTickDriver(
    private val phases: PhaseRegistry,
    private val jobs: JobScheduler,
    private val diagnostics: RuntimeDiagnostics? = null,
) {
    /** 推进一个节拍：相位派发在前，任务触发在后；诊断写点不改变节拍顺序。 */
    fun tick(time: GameTime): GameTickReport {
        val phaseReport = phases.dispatch(time)
        val jobReport = jobs.runDue(time)
        val diagnostics = diagnostics
        if (diagnostics != null) {
            val reasonCode = when {
                time.phase.isBlank() -> "NO_PHASE"
                phaseReport.fired > 0 -> "OK"
                else -> "NO_HANDLERS"
            }
            diagnostics.record(
                day = time.day,
                phase = time.phase.ifBlank { null },
                domain = DiagnosticDomain.PHASE,
                packageId = null,
                key = time.phase,
                outcome = if (phaseReport.fired > 0) {
                    DiagnosticOutcome.OK
                } else {
                    DiagnosticOutcome.SKIPPED
                },
                reasonCode = reasonCode,
            )
        }
        return GameTickReport(time, phaseReport, jobReport)
    }
}

/** 一次节拍的合并报告。 */
class GameTickReport(
    val time: GameTime,
    val phase: PhaseRegistry.PhaseDispatchReport,
    val jobs: JobScheduler.JobRunReport,
) {
    val firedPhaseHandlers: Int get() = phase.fired
    val firedJobs: Int get() = jobs.fired.size
    val hasFailures: Boolean get() = phase.failures.isNotEmpty() || jobs.failures.isNotEmpty()
    val failureCount: Int get() = phase.failures.size + jobs.failures.size
}
