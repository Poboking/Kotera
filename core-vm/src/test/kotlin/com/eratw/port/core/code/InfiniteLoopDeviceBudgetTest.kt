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
 * loopguard 波（2026-11-09）：装置预算命中 ⇒ 问玩家一次 ⇒ 继续则抬预算续跑。
 *
 * ## 缺陷（上报「可能进入了无限循环因此选择强制终止 @ BASE自然変動」，0.2.42 / HONOR LGN-NX1）
 * 上报那一份的抛点经复核**不是**本文件的装置预算，而是墙钟闸 + 玩家在框里点了「是」
 * （VirtualMachine.infiniteLoopExitAt，guardCause = PlayerChoseExit，文案
 * Lang.Error.SelectExitInfiniteLoopMB）。但同一条链上还压着一个**真偏差**：
 * steps > maxStepsPerRun 命中时移植层直接 throw StepLimitExceeded（DeviceBudget），
 * 命中即按装载失败收口 —— 官方**从不按指令数中止**。
 *
 * ## 官方口径（本文件的 oracle）
 * * reference/emuera-cs 的 Process.cs:493-512（checkInfiniteLoop）：唯一判据是墙钟（:496），
 *   撞线弹 YesNo（:505），只有「是」才 throw CodeEE（:507）；点「否」⇒ state.lineCount = 0 +
 *   startTime.Restart()（:509-510）**继续跑**。
 * * Process.cs:1012-1015：每 10000 行采一次。
 * * 全仓没有任何「每 run 指令上限」——于是装置预算这一维**只能长成「问玩家」的形状**：
 *   保留它（不删，因为没有「没有它也不会把设备钉死」的正证），但中止权必须回到玩家手里。
 *
 * ## 五条判据（对应波次契约 §4）
 * 1. 墙钟闸命中 ⇒ 问一次 ⇒ 继续可继续跑（回归锁，防本波把官方那条改坏）；
 * 2. 装置预算命中 ⇒ 问一次 ⇒ 继续后**预算抬升**且脚本继续；
 * 3. 选「中止」⇒ 抛 StepLimitExceeded，成因位/文案/现场与改造前逐字一致；
 * 4. headless（宿主没有弹框通道）⇒ 仍然抛、且可诊断（绝不静默、绝不悬挂）；
 * 5. 正常短脚本 ⇒ 零询问、预算不抬。
 */
class InfiniteLoopDeviceBudgetTest {
    private companion object {
        /** 墙钟闸的采样频率（Process.cs:1012 的 state.lineCount % 10000 == 0）。 */
        const val CHECK_LINES = 10_000

        /** 墙钟阈值 1 ms（> 0 即开关打开，口径同 Process.cs:989）。 */
        const val ALERT_MS = 1L
    }

    /** 语句 marker 的载体：PRINTL，语义全在测试宿主里做（判据与真语料无关）。 */
    private fun printl(): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL")

    /**
     * iterations 次语句 marker 派发的循环体（_ADD/_LT/_JUMP* 都不是语句 marker）。
     * 每条迭代 = 1 条 marker + 5 条其它指令。
     */
    private fun markerLoop(iterations: Int): Program {
        val src = listOf(
            ".func main slots=4",
            "  _SET_INT R0, 0",
            "  _SET_INT R1, 1",
            "  _SET_INT R3, " + iterations,
            "loop:",
            "  PRINTL stmt#0",
            "  _ADD R0, R0, R1",
            "  _LT R2, R0, R3",
            "  _JUMP_IF_FALSE R2, done",
            "  _JUMP loop",
            "done:",
            "  _HALT",
        ).joinToString("\n")
        return Assembler.assemble(src, statements = listOf(printl()))
    }

    /** 装置预算询问的一次记录（askDeviceBudgetContinue 的四个实参原样留存）。 */
    private data class DeviceAsk(
        val alert: InfiniteLoopAlert,
        val steps: Long,
        val budget: Long,
        val nextBudget: Long,
    )

    /**
     * 同时接两条询问通道的宿主。
     *
     * @param answer 两条通道共用的答复（Continue / Exit）
     * @param sleepPerSegment 每段第一条语句真实睡一次，让该段墙钟必然越过 ALERT_MS
     * @param maxAsks 询问次数上限：反向证明（抬预算那一行被删）时会「每指令一问」，
     *   到这个上限就改答「退出」，让用例**失败**而不是把测试进程挂死
     */
    private class BudgetHost(
        private val answer: InfiniteLoopAnswer = InfiniteLoopAnswer.Continue,
        private val sleepPerSegment: Boolean = false,
        private val maxAsks: Int = 64,
    ) : VmHost {
        override val evalContext: EvalContext? = null

        /** 派发过的语句 marker 次数（= 移植侧的「行」口径）。 */
        var dispatches = 0
            private set

        /** 墙钟闸的询问（VmHost.askInfiniteLoop）。 */
        val wallAsks = mutableListOf<InfiniteLoopAlert>()

        /** 装置预算的询问（VmHost.askDeviceBudgetContinue）。 */
        val deviceAsks = mutableListOf<DeviceAsk>()

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            dispatches++
            if (sleepPerSegment && dispatches % CHECK_LINES == 1) Thread.sleep(5L)
            return StmtOutcome.Normal
        }

        override fun askInfiniteLoop(alert: InfiniteLoopAlert): InfiniteLoopAnswer {
            wallAsks += alert
            return answer
        }

        override fun askDeviceBudgetContinue(
            alert: InfiniteLoopAlert,
            steps: Long,
            budget: Long,
            nextBudget: Long,
        ): InfiniteLoopAnswer {
            deviceAsks += DeviceAsk(alert, steps, budget, nextBudget)
            if (deviceAsks.size >= maxAsks) return InfiniteLoopAnswer.Exit()
            return answer
        }
    }

    /** 只留官方那一维：装置预算推到不可能触发（Long.MAX_VALUE）。 */
    private val officialOnly = VmConfig(
        maxStepsPerRun = Long.MAX_VALUE,
        maxRunMillis = 0L,
        infiniteLoopAlertTimeMs = ALERT_MS,
    )

    // ------------------------------------------------------------------
    // 1. 墙钟闸：问一次、答继续就继续跑（本波的回归锁）
    // ------------------------------------------------------------------

    @Test
    fun `墙钟闸命中问一次，答继续后脚本跑完`() {
        val program = markerLoop(CHECK_LINES + 50)
        val host = BudgetHost(answer = InfiniteLoopAnswer.Continue, sleepPerSegment = true)
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        assertEquals(1, host.wallAsks.size, "越过一次 10000 行界 ⇒ 只问一次")
        assertTrue(host.deviceAsks.isEmpty(), "墙钟闸命中的不是装置预算这一维")
        assertEquals(CHECK_LINES + 50, host.dispatches, "答继续后必须一行不少跑完")
        assertTrue(vm.executedSteps > CHECK_LINES, "正常返回：executedSteps=${vm.executedSteps}")
    }

    // ------------------------------------------------------------------
    // 2. 装置预算：问一次 ⇒ 继续 ⇒ 抬预算 ⇒ 脚本继续
    // ------------------------------------------------------------------

    @Test
    fun `装置预算命中问一次，答继续后预算抬升且脚本跑完`() {
        val budget = 100L
        val program = markerLoop(120)
        val host = BudgetHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(
            program,
            host,
            VmConfig(maxStepsPerRun = budget, maxRunMillis = 0L, infiniteLoopAlertTimeMs = 0L),
        )

        vm.run()

        assertTrue(host.wallAsks.isEmpty(), "墙钟闸关着（阈值 0）：只该走装置预算这一维")
        assertTrue(
            host.deviceAsks.size >= 2,
            "预算 " + budget + " 而这条链远超 2x 预算 ⇒ 至少问两次（问完必须真的往前跑）：实际 " +
                host.deviceAsks.size + " 次",
        )
        assertTrue(
            host.deviceAsks.size < 10,
            "抬过预算之后撞线次数必须显著少于「每条指令都问一次」（实际 " + host.deviceAsks.size +
                " 次）：这一条是「预算没抬」的反向证明",
        )
        val first = host.deviceAsks.first()
        assertEquals(budget + 1L, first.steps, "撞线读数 = 预算 + 1（既有检查点口径不变）")
        assertEquals(budget, first.budget, "第一次撞线时预算 = 配置值")
        assertEquals(2L * budget, first.nextBudget, "答继续 ⇒ 再加一整份基础预算（官方复位后给满额）")
        assertEquals(
            first.nextBudget,
            host.deviceAsks[1].budget,
            "第二次撞线必须按**抬升后**的预算判 —— 否则预算根本没抬",
        )
        assertTrue(
            host.deviceAsks.all { it.nextBudget > it.budget },
            "每一档 nextBudget 都必须严格大于 budget（否则会在原地反复问框、pc 不前进）",
        )
        assertEquals(120, host.dispatches, "答继续后整条链必须跑完")
        assertTrue(vm.executedSteps > 2L * budget, "实际步数必须越过 2x 预算：${vm.executedSteps}")
    }

    // ------------------------------------------------------------------
    // 3. 选「中止」⇒ 抛、且文案/成因位/现场不变
    // ------------------------------------------------------------------

    @Test
    fun `答中止时按设备预算口径抛出，文案与成因位不变`() {
        val budget = 100L
        val program = markerLoop(120)
        val host = BudgetHost(answer = InfiniteLoopAnswer.Exit())
        val vm = VirtualMachine(
            program,
            host,
            VmConfig(maxStepsPerRun = budget, maxRunMillis = 0L, infiniteLoopAlertTimeMs = 0L),
        )

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }

        assertEquals(1, host.deviceAsks.size, "中止只问一次，不再往后跑")
        assertTrue(host.dispatches < 120, "中止后不许把整条链跑完：实际派发 ${host.dispatches} 条 marker")
        assertEquals(LoopGuardCause.DeviceBudget, e.guardCause, "玩家中止仍归装置预算成因位")
        assertEquals(budget + 1L, e.steps, "抛点仍 = 预算 + 1")
        assertEquals(-1L, e.elapsedMs, "步数闸的 elapsedMs 必须仍是 -1（时间口径才带读数）")
        val msg = e.message.orEmpty()
        assertTrue(
            msg.contains("执行步数超过上限(" + (budget + 1L) + ")，疑似死循环"),
            "步数闸文案必须逐字保留（既有装置/失败屏断言依赖它）：" + msg,
        )
        assertTrue(msg.contains("@"), "必须带调用链现场（可诊断）：" + msg)
    }

    // ------------------------------------------------------------------
    // 4. headless：没有弹框通道 ⇒ 仍然抛，且可诊断
    // ------------------------------------------------------------------

    @Test
    fun `没有弹框通道时仍然抛出且带现场`() {
        val budget = 100L
        val program = markerLoop(120)
        // 不覆写 askDeviceBudgetContinue ⇒ 缺省 NoUi（等於没有可用 UI 的装置/headless 宿主）。
        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            override fun print(text: String) {}
            override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome =
                StmtOutcome.Normal
        }
        val vm = VirtualMachine(
            program,
            host,
            VmConfig(maxStepsPerRun = budget, maxRunMillis = 0L, infiniteLoopAlertTimeMs = 0L),
        )

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }

        assertEquals(LoopGuardCause.DeviceBudget, e.guardCause)
        assertEquals(budget + 1L, e.steps)
        val msg = e.message.orEmpty()
        assertTrue(
            msg.contains("执行步数超过上限(" + (budget + 1L) + ")，疑似死循环"),
            "headless 必须保留可诊断中止（不许静默、不许悬挂）：" + msg,
        )
    }

    // ------------------------------------------------------------------
    // 5. 正常短脚本：零询问、预算不抬
    // ------------------------------------------------------------------

    @Test
    fun `正常短脚本一次都不问`() {
        val budget = 10_000L
        val program = markerLoop(3)
        val host = BudgetHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(
            program,
            host,
            VmConfig(maxStepsPerRun = budget, maxRunMillis = 0L, infiniteLoopAlertTimeMs = 0L),
        )

        vm.run()

        assertTrue(host.deviceAsks.isEmpty(), "短脚本不该碰装置预算")
        assertTrue(host.wallAsks.isEmpty(), "阈值 0 = 墙钟闸整条关闭")
        assertEquals(3, host.dispatches)
        assertTrue(vm.executedSteps < budget, "短脚本步数必须远低于预算：${vm.executedSteps}")
    }
}
