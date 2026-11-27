package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.modapi.GameTime
import java.util.Locale

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
 *
 * 回合边界（[tickTurn]）是 [tick] 的扩展：旧节拍只认识单个相位名，回合边界按宿主时钟
 * （[TurnPhaseClock]）一次给出多个内建相位，逐相位派发后任务仍按空相位跑一次。
 */
class GameTickDriver(
    private val phases: PhaseRegistry,
    private val jobs: JobScheduler,
    private val diagnostics: RuntimeDiagnostics? = null,
    private val timing: ModTimingLedger? = null,
    private val packageId: String = "",
) {
    /** 推进一个节拍：相位派发在前，任务触发在后；诊断写点不改变节拍顺序。 */
    fun tick(time: GameTime): GameTickReport =
        if (timing == null) tickOnce(time) else timing.measureValue(packageId, "tick") { tickOnce(time) }

    /**
     * 推进一个「回合边界」节拍：先按 [phasePlan] 逐相位真派发，再以空相位跑到期任务一次。
     *
     * 与 [tick] 的关系：旧节拍把唯一相位名放在 [GameTime.phase] 里；回合边界的相位集合由
     * 宿主时钟决定（[TurnPhaseClock]：日变 = 日终 + 日始、同日 = 动作点、首边界/回退 = 日始），
     * 所以这里接一个相位计划，逐相位派发后才合并报告。**任务口径不变**：本边界仍以空相位
     * （不限相位）跑到期任务恰好一次 —— `App` 侧「空相位是裁定」旧用例的任务断言因此保持原样。
     *
     * 生产多 Mod 接线传 [runJobs] = false：先让每个包派发完全部相位，再由宿主对全链共享任务
     * 调度器调用一次 [JobScheduler.runDue]，避免首个包在后续包相位之前消费全链任务。
     * 诊断口径（fired 与 skipped 在读数上可分）：
     * - 到点且 fired > 0 ⇒ `OK` / `OK`，`phase` 为真实相位名（`DAY_START` 等）；
     * - 到点但没有处理器 ⇒ `SKIPPED` / `NO_HANDLERS`；
     * - 处理器绑了但本边界没轮到该相位 ⇒ `SKIPPED` / `PHASE_NOT_DUE`。
     */
    fun tickTurn(day: Long, phasePlan: List<String>, runJobs: Boolean = true): GameTickReport {
        val body = { tickTurnOnce(day, phasePlan, runJobs) }
        return if (timing == null) body() else timing.measureValue(packageId, "tick") { body() }
    }

    private fun tickTurnOnce(day: Long, phasePlan: List<String>, runJobs: Boolean): GameTickReport {
        val planned = phasePlan.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
        val perPhase = planned.map { phase -> phase to phases.dispatch(GameTime(day = day, phase = phase)) }
        val phaseReport = PhaseRegistry.PhaseDispatchReport(
            fired = perPhase.sumOf { it.second.fired },
            failures = perPhase.flatMap { it.second.failures },
        )
        val notDue = (phases.boundPhases() - planned.toSet()).sorted()
        val jobTime = GameTime(day = day, phase = "")
        val jobReport = if (runJobs) jobs.runDue(jobTime) else JobScheduler.JobRunReport(emptyList(), emptyList())
        val diagnostics = diagnostics
        if (diagnostics != null) {
            for ((phase, report) in perPhase) {
                diagnostics.record(
                    day = day,
                    phase = phase,
                    domain = DiagnosticDomain.PHASE,
                    packageId = null,
                    key = phase,
                    outcome = if (report.fired > 0) DiagnosticOutcome.OK else DiagnosticOutcome.SKIPPED,
                    reasonCode = if (report.fired > 0) "OK" else "NO_HANDLERS",
                    detail = { "fired=${report.fired};failures=${report.failures.size}" },
                )
            }
            for (phase in notDue) {
                diagnostics.record(
                    day = day,
                    phase = phase,
                    domain = DiagnosticDomain.PHASE,
                    packageId = null,
                    key = phase,
                    outcome = DiagnosticOutcome.SKIPPED,
                    reasonCode = "PHASE_NOT_DUE",
                    detail = { "fired=0;failures=0" },
                )
            }
        }
        return GameTickReport(jobTime, phaseReport, jobReport, planned, notDue)
    }

    private fun tickOnce(time: GameTime): GameTickReport {
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
    /** 本拍计划发生的相位（[GameTickDriver.tick] 恒空；[GameTickDriver.tickTurn] 为边界相位集合，大写归一）。 */
    val plannedPhases: List<String> = emptyList(),
    /** 已绑处理器但本拍未到点的相位（诊断记 SKIPPED / PHASE_NOT_DUE）。 */
    val notDuePhases: List<String> = emptyList(),
) {
    val firedPhaseHandlers: Int get() = phase.fired
    val firedJobs: Int get() = jobs.fired.size
    val hasFailures: Boolean get() = phase.failures.isNotEmpty() || jobs.failures.isNotEmpty()
    val failureCount: Int get() = phase.failures.size + jobs.failures.size
}

/**
 * `TIME` 读数 → 自定义相位键的单一换算口（ModDX-4 M5P 判据 1；唯一换算点，调用方只有
 * app 页边界 tick 段）。
 *
 * 裁定（撤销旧「TIME→无权威映射」搁置）：引擎**不引入时段语义**——朝/昼/夜等玩法命名归
 * mod 策略，本口只给确定性键 `"time." + <TIME 原值>`（十进制原样拼缀，含 0 与负值，
 * 不做区间解释）。[ticks] 为 null（宿主 TIME 读数不可用）⇒ 返回空串 = 无相位，
 * 与「页边界不派发 time 相位」的现状同形 fail-open。官方 C# 无游戏相位概念
 * （移植侧扩展，无 C# 对应物；`TIME` 变量本身锚 VariableCode.kt:54 TIME=720918）。
 */
object TimePhaseKey {
    /** 相位键前缀（小写字面量；派发与订阅两侧经大写归一后匹配，作者面写法不拘大小写）。 */
    const val PREFIX: String = "time."

    /** 单一换算：TIME 原值 → `"time.<原值>"`；null（读数不可用）→ 空串 = 无相位。 */
    fun of(ticks: Long?): String = if (ticks == null) "" else PREFIX + ticks
}
