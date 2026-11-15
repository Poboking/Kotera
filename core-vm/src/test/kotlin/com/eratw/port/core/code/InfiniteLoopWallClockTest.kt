package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 死循环告警的**墙钟口径**与**继续后退避**（2026-11-10，「无限循环误报」根治）。
 *
 * ## oracle
 * * 官方判据只有一条：`Config.InfiniteLoopAlertTime > 0` 且 `state.lineCount % 10000 == 0`
 *   时比 `startTime.ElapsedMilliseconds`（`Process.cs:470-489`、`:989-992`）；撞线弹 YesNo，
 *   答「否」⇒ `state.lineCount = 0` + `startTime.Restart()` 继续跑（`:486-487`），
 *   答「是」才 `throw CodeEE`（`:484`）。
 * * **本移植有意偏离的两处**（都不改上面那条结构，只改「量什么时间」与「问多密」）：
 *   1. 时间只量 **VM 真在跑** 的时长（[VmConfig.monotonicMs] 可注入，宿主扣掉后台累计）；
 *   2. 答「继续」后**同 run 内阈值翻倍**（`escalatedLoopAlertMs`，封顶
 *      [VirtualMachine.LOOP_ALERT_ESCALATE_CAP_MS] = 30 分钟）。
 *
 * ## 为什么必须有假钟
 * 真钟下「359 s 不问 / 361 s 问」要真睡六分钟；本文件用 [FakeClockHost] 把
 * 「注入给 VM 的毫秒读数」写成**已执行行数的函数**，于是每个断言都精确到位、且与机器快慢无关。
 *
 * 时钟读点只有两处（其余路径全是常量为假的比较）：
 * * 段落起点（run 开头的 `segStartMs`）；
 * * 每满 [CHECK_LINES] 行一次的告警采样点。
 * 故 `clock(k) = f(已派发 marker 数 k)`：run 起点 `k = 0`，第 N 行的那次采样 `k = N - 1`。
 */
class InfiniteLoopWallClockTest {

    private companion object {
        /** 原版采样频率（`Process.cs:990` 的 `state.lineCount % 10000 == 0`）。 */
        const val CHECK_LINES = 10_000

        /** 本轮出厂缺省（`PortFactoryConfig.INFINITE_LOOP_ALERT_MS`）= 6 分钟。 */
        const val SIX_MIN_MS = 360_000L
    }

    /** 语句 marker 的载体：`PRINTL`（语义全在测试宿主里做，与判据无关）。 */
    private fun printl(): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL")

    /** [iterations] 次语句 marker 派发；`_ADD`/`_LT`/`_JUMP*` 不是 marker ⇒ 行数 == iterations。 */
    private fun markerLoop(iterations: Int): Program {
        val src = """
            .func main slots=4
              _SET_INT R0, 0
              _SET_INT R1, 1
              _SET_INT R3, $iterations
            loop:
              PRINTL stmt#0
              _ADD R0, R0, R1
              _LT R2, R0, R3
              _JUMP_IF_FALSE R2, done
              _JUMP loop
            done:
              _HALT
        """.trimIndent()
        return Assembler.assemble(src, statements = listOf(printl()))
    }

    /**
     * 假钟宿主：把「注入给 VM 的毫秒读数」写成**已派发 marker 数**的函数。
     *
     * @param readout 已派发 marker 数 ⇒ 注入的毫秒读数（宿主侧口径，**可**已扣掉后台累计）
     * @param answers 第 n 次告警的答复（越界后一律 [InfiniteLoopAnswer.Continue]）
     * @param backgroundMs [VmHost.loopGuardBackgroundMs] 的返回值（取证位）
     */
    private class FakeClockHost(
        private val readout: (Long) -> Long,
        private val answers: List<InfiniteLoopAnswer> = emptyList(),
        private val backgroundMs: Long = 0L,
    ) : VmHost {
        override val evalContext: EvalContext? = null

        /** 已派发的语句 marker 数 = 移植侧的「行」。 */
        var dispatches = 0L
            private set

        /** 宿主收到的告警现场，按到达顺序。 */
        val alerts = mutableListOf<InfiniteLoopAlert>()

        /** 宿主收到的翻倍留痕，按到达顺序（base, eff, nth）。 */
        val escalations = mutableListOf<Triple<Long, Long, Int>>()

        /** 注入时钟被读了几次（判「VM 真的用了这把尺」，而不是偷偷读真钟）。 */
        var clockReads = 0
            private set

        /** 注入给 [VmConfig.monotonicMs] 的读数。 */
        fun now(): Long {
            clockReads++
            return readout(dispatches)
        }

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            dispatches++
            return StmtOutcome.Normal
        }

        override fun askInfiniteLoop(alert: InfiniteLoopAlert): InfiniteLoopAnswer {
            alerts += alert
            return answers.getOrElse(alerts.size - 1) { InfiniteLoopAnswer.Continue }
        }

        override fun loopAlertEscalated(baseMs: Long, effectiveMs: Long, nth: Int) {
            escalations += Triple(baseMs, effectiveMs, nth)
        }

        override fun loopGuardBackgroundMs(): Long = backgroundMs
    }

    /** 只开官方那一维（装置步数闸推到不可能触发，墙钟预算关）。 */
    private fun officialOnly(host: FakeClockHost, alertMs: Long = SIX_MIN_MS) = VmConfig(
        maxStepsPerRun = Long.MAX_VALUE,
        maxRunMillis = 0L,
        infiniteLoopAlertTimeMs = alertMs,
        monotonicMs = { host.now() },
    )

    // ------------------------------------------------------------------
    // ① 阈值就是出厂缺省 360 s：359 s 不问，361 s 问一次
    // ------------------------------------------------------------------

    /**
     * 359 s（注入读数 358,964 ms）⇒ **零告警**，且整条链一行不少跑完。
     *
     * 这一条直接把「出厂缺省 100 s 会误报」钉死：校准前的线上实际值 15 s 下必问 23 次。
     */
    @Test
    fun `假钟 359 s 时零告警`() {
        val host = FakeClockHost(readout = { k -> k * 359L / 10L })
        val vm = VirtualMachine(markerLoop(CHECK_LINES), host, officialOnly(host))

        vm.run()

        assertEquals(0, host.alerts.size, "359 s < 360 s ⇒ 一次都不该问")
        assertEquals(CHECK_LINES.toLong(), host.dispatches, "整条链必须跑完")
        assertTrue(host.clockReads > 0, "注入的时钟必须真的被读过（否则这条用例测不到东西）")
    }

    /**
     * 361 s（注入读数 360,963 ms）⇒ **恰好一次**告警，现场读数与注入值逐字对上。
     *
     * `elapsedMs` 精确等于假钟给的值是刻意的：真钟下这里会是 0 ms（测试只跑几毫秒），
     * 于是「VM 用的是注入尺」这件事本身也被这条断言证明。
     */
    @Test
    fun `假钟 361 s 时恰好一次告警且现场读数来自注入时钟`() {
        val host = FakeClockHost(readout = { k -> k * 361L / 10L })
        val vm = VirtualMachine(markerLoop(CHECK_LINES), host, officialOnly(host))

        vm.run()

        assertEquals(1, host.alerts.size, "361 s >= 360 s ⇒ 恰好问一次")
        val alert = host.alerts.single()
        assertEquals(360_963L, alert.elapsedMs, "现场墙钟必须来自注入的假钟")
        assertEquals(SIX_MIN_MS, alert.alertTimeMs, "首个告警用的是会话生效阈值（不翻倍）")
        assertEquals(1, alert.attempt, "首个告警是第 1 次")
        assertEquals(CHECK_LINES.toLong(), alert.linesExecuted, "行数口径 = marker 派发次数")
    }

    // ------------------------------------------------------------------
    // ② 后台时长不进墙钟（根治误报的第二半）
    // ------------------------------------------------------------------

    /**
     * **同一段墙钟、只差「后台累计」**：不扣 ⇒ 问；扣掉 600 s ⇒ 不问。
     *
     * 注意后台必须是**run 中途攒起来**的：`elapsedMs` 是「读数 − 段落起点」的**差值**，
     * 一个恒定的偏移会在做差时自己抵消，测不到任何东西。所以这里的中段（第 5000 行起）
     * 一次性垫上 600,000 ms —— 就是「这中间手机被切到后台放了十分钟」的形状。
     *
     * * 不扣：第 10000 行读数 = 99,990 + 600,000 = 699,990 ms ≥ 360,000 ⇒ 告警；
     * * 扣掉：同一时刻读数 = 99,990 ms < 360,000 ⇒ 不问。
     *
     * 反向证明的落点也在这里：把 `VmConfig.monotonicMs` 的注入位改回直读
     * `System.nanoTime()`，两半都读到 ≈0 ms ⇒ **两半都不问** ⇒ 前一红色。
     */
    @Test
    fun `扣掉 600 s 后台后零告警而不扣的同一条链会告警`() {
        // 第 5000 行起垫上 600,000 ms 后台；其余按每行 10 ms 匀速前进。
        val raw = { k: Long -> k * 10L + if (k >= 5_000L) 600_000L else 0L }

        val notDeducted = FakeClockHost(readout = { k -> raw(k) })
        VirtualMachine(markerLoop(CHECK_LINES), notDeducted, officialOnly(notDeducted)).run()
        assertEquals(1, notDeducted.alerts.size, "不扣后台 ⇒ 墙钟读数是 699,990 ms，必须问")
        assertEquals(699_990L, notDeducted.alerts.single().elapsedMs)

        val deducted = FakeClockHost(readout = { k -> raw(k) - (if (k >= 5_000L) 600_000L else 0L) })
        VirtualMachine(markerLoop(CHECK_LINES), deducted, officialOnly(deducted)).run()
        assertEquals(0, deducted.alerts.size, "扣掉 600 s 后台 ⇒ 前台只跑了 99,990 ms，不该问")
        assertEquals(CHECK_LINES.toLong(), deducted.dispatches, "整条链必须跑完")
    }

    /**
     * 告警现场必须带**累计后台毫秒**（取证位）：下一份上报能一眼分清
     * 「慢机 + 后台抢占」与「真死循环」。
     */
    @Test
    fun `告警现场带累计后台毫秒`() {
        // 每行 40 ms + 第 5000 行起垫 600 s 后台；扣掉后台后第 10000 行读数 399,960 ms ⇒ 告警。
        val host = FakeClockHost(
            readout = { k -> k * 40L },
            backgroundMs = 600_000L,
        )
        val vm = VirtualMachine(markerLoop(CHECK_LINES), host, officialOnly(host))

        vm.run()

        val alert = host.alerts.single()
        assertEquals(399_960L, alert.elapsedMs, "前台读数")
        assertEquals(600_000L, alert.backgroundMs, "宿主累计后台毫秒必须进现场")
    }

    // ------------------------------------------------------------------
    // ③ 答「继续」后同 run 内阈值翻倍，不立刻再问
    // ------------------------------------------------------------------

    /**
     * 每行 40 ms ⇒ 每 10000 行窗口涨 400,000 ms：
     * * 第 10000 行：elapsed 399,960 ≥ 360,000 ⇒ 第 1 问，答「继续」，生效阈值 → 720,000；
     * * 第 20000 行：elapsed 400,000 < 720,000 ⇒ **不该再问**（官方口径下这里会立刻再问）；
     * * 第 30000 行：elapsed 800,000 ≥ 720,000 ⇒ 第 2 问，`attempt = 2`。
     *
     * 反向证明：删掉翻倍 ⇒ 第 20000 行就会问第 2 次（`elapsedMs = 400,000`），
     * 本用例的两条断言同时红。
     */
    @Test
    fun `答继续后阈值翻倍_下一问落在二倍处而不是立刻再问`() {
        val host = FakeClockHost(readout = { k -> k * 40L })
        val vm = VirtualMachine(markerLoop(3 * CHECK_LINES), host, officialOnly(host))

        vm.run()

        assertEquals(2, host.alerts.size, "一次翻倍只该把第二问推后，不该消失")
        assertEquals(399_960L, host.alerts[0].elapsedMs, "第 1 问的现场")
        assertEquals(
            800_000L,
            host.alerts[1].elapsedMs,
            "第 2 问必须落在翻倍后的 720,000 阈值之上（不是 400,000 的旧阈值）",
        )
        assertEquals(2, host.alerts[1].attempt, "第 2 问的序号")
        assertEquals(
            listOf(Triple(SIX_MIN_MS, 720_000L, 2), Triple(SIX_MIN_MS, 1_440_000L, 3)),
            host.escalations,
            "每答一次「继续」留一条痕：base 恒为会话生效值，eff 为翻倍后的新生效值",
        )
    }

    /** 翻倍封顶 30 分钟：360→720→1440→1800，之后不再变（也不再留痕）。 */
    @Test
    fun `翻倍封顶三十分钟`() {
        // 每行 200 ms ⇒ 每个 10000 行窗口涨 2,000,000 ms，任何生效阈值都会被撞到。
        val host = FakeClockHost(readout = { k -> k * 200L })
        val vm = VirtualMachine(markerLoop(6 * CHECK_LINES), host, officialOnly(host))

        vm.run()

        assertEquals(
            listOf(
                Triple(SIX_MIN_MS, 720_000L, 2),
                Triple(SIX_MIN_MS, 1_440_000L, 3),
                Triple(SIX_MIN_MS, 1_800_000L, 4),
            ),
            host.escalations,
            "360→720→1440→1800（封顶）",
        )
        assertEquals(6, host.alerts.size, "封顶之后仍然会问（真死循环必须还能被问出来）")
        assertEquals(SIX_MIN_MS, host.alerts[0].alertTimeMs, "base 永远是会话生效值，不跟着翻")
        assertEquals(1_800_000L, host.alerts[5].alertTimeMs, "封顶后每一问都用 30 分钟")
    }

    /** 短脚本（行数不到 10000）零询问、零翻倍 —— 与官方「每 10000 行才看一次」一致。 */
    @Test
    fun `短脚本零询问零翻倍`() {
        val host = FakeClockHost(readout = { k -> k * 1_000_000L })
        val vm = VirtualMachine(markerLoop(CHECK_LINES / 2), host, officialOnly(host))

        vm.run()

        assertEquals(0, host.alerts.size, "行数不到采样界 ⇒ 一次都不问")
        assertEquals(0, host.escalations.size)
        assertEquals(
            1,
            host.clockReads,
            "只有 run 起点那一次读钟（段落起点是无条件取的，与改造前一致），采样点一次都没到",
        )
    }

    // ------------------------------------------------------------------
    // ④ 两处墙钟比较共用同一把尺（maxRunMillis 也读注入值）
    // ------------------------------------------------------------------

    /**
     * [VmConfig.maxRunMillis] 与 [VmConfig.infiniteLoopAlertTimeMs] 共用 [VmConfig.monotonicMs]。
     *
     * 注入「每行 1000 ms」的假钟 + `maxRunMillis = 1000`：第一次 65536 步检查点上的读数
     * 就是千万毫秒级 ⇒ 必然撞闸。真钟下这条链只要几毫秒，**根本不会抛** ——
     * 所以「抛了」本身就是「这条闸读的是注入尺」的证据。
     */
    @Test
    fun `装置墙钟预算也读同一把注入时钟`() {
        val host = FakeClockHost(readout = { k -> k * 1_000L })
        val vm = VirtualMachine(
            markerLoop(20_000),
            host,
            VmConfig(
                maxStepsPerRun = Long.MAX_VALUE,
                maxRunMillis = 1_000L,
                infiniteLoopAlertTimeMs = 0L,
                monotonicMs = { host.now() },
            ),
        )

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }

        assertEquals(LoopGuardCause.DeviceBudget, e.guardCause)
        assertTrue(e.elapsedMs >= 1_000L, "读数必须来自注入的假钟，实际 ${e.elapsedMs}")
    }

    // ------------------------------------------------------------------
    // ⑤ 0 = 关闭的语义逐字不变
    // ------------------------------------------------------------------

    /** 阈值 0 仍是整条判据的开关位（`Process.cs:989`），连时钟都不读。 */
    @Test
    fun `阈值 0 时不读时钟也不问`() {
        val host = FakeClockHost(readout = { k -> k * 1_000_000L })
        val vm = VirtualMachine(markerLoop(2 * CHECK_LINES), host, officialOnly(host, alertMs = 0L))

        vm.run()

        assertEquals(0, host.alerts.size)
        assertEquals(
            1,
            host.clockReads,
            "0 = 关闭 ⇒ 除了 run 起点那一次（既有行为），采样点一次都不读钟",
        )
        assertEquals((2 * CHECK_LINES).toLong(), host.dispatches)
    }
}
