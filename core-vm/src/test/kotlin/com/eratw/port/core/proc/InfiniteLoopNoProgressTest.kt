package com.eratw.port.core.proc

import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.code.LoopGuardCause
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 死循环告警判据升级的**集成级**红→绿锁（QB-A3 · 无进展判死）——本 lane 从旧稿
 * `wave/qb-a3`（f980b216f）移植并接上 VmHost plumbing 后在当前 main 复验。
 *
 * ## 案情锚（BOARD「QQ 反馈群 bug 汇总」A3 条）
 * 银时君（0.2.42）`StepLimitExceeded @ BASE自然変動`：12 地点清扫链**每轮屏上都有新行**
 * （「今日在神社扫除了0的垃圾…」×12 地点），却因段落墙钟越过 360 s 被问、答错即按启动失败
 * 收口 —— 「有进展的长执行」与「无进展的真死循环」共用墙钟判据就是误报机器（阈值
 * 100 s → 180 s → 360 s 三连上调是直证）。
 *
 * ## oracle
 * * 官方唯一的死循环判据是段落墙钟 + 问玩家（1.824 `Process.cs:495-496` / `:507` / `:509-510`；
 *   采样 1.824 `Process.cs:1012-1014`／skia `Process.ScriptProc.cs:34-36`；4.981 skia
 *   同构 `Process.cs:494-496`），**从不看屏上有没有新内容** ——
 *   本移植判据升级为「无进展墙钟」是**有意偏离官方**（ADR-005 例外，登记于
 *   `docs/UPSTREAM-BUG-PR.md` 与 [VmHost.loopGuardProgressStamp] 的 KDoc）：进展信号 =
 *   屏上可见变更（宿主经 [VmHost.loopGuardProgressStamp] 注入，生产源 =
 *   `LineBufferConsoleSink.revision`），撞线采样点上戳变了 ⇒ 有进展 ⇒ 不问；戳没变 ⇒
 *   无进展时长越过阈值才问。真死循环保护不删。
 * * 「原版从不因跑太久自己中止」（skia `Process.cs:481-515`、1.824 `:493-512`）：中止权全在玩家的 YesNo 框；
 *   本移植的 `StepLimitExceeded(PlayerChoseExit)` = 玩家选「退出」那半的复刻（skia `:508`、1.824 `:507`）。
 *
 * ## 确定性
 * 假钟（每读一次 +5 s）+ 阈值 10 s：第 2 个采样点（约 2 万行）必撞线，与机器快慢无关。
 * 打印链每个采样点进展戳都在涨 ⇒ 全程零询问；静默链（只写变量、屏上一字未长）⇒
 * 问一次，答「退出」抛 [StepLimitExceeded]（[LoopGuardCause.PlayerChoseExit]）——
 * 保护仍在的那一半。
 *
 * ## 红→绿
 * * 修复前：打印链在撞线采样点被问（红）；
 * * 修复后：打印链零询问跑完（绿），静默链仍被问（两条都绿）。
 */
class InfiniteLoopNoProgressTest {

    private companion object {
        /** 告警阈值（装置口径直接给 [VmConfig]，不经会话配置）。 */
        const val ALERT_MS = 10_000L

        /** 假钟每读一次前进的毫秒数：run 起点一次 + 每采样点一次 ⇒ 第 2 采样点 elapsed = 10 s。 */
        const val CLOCK_STEP_MS = 5_000L

        /** 循环轮数：≥ 2 万行（每轮至少 2 个语句 marker）⇒ 至少 2 个采样点。 */
        const val ITERATIONS = 15_000
    }

    /** 假钟：每读一次前进 [CLOCK_STEP_MS]，读数与真实耗时无关。 */
    private class StepClock {
        var nowMs = 0L
            private set

        fun tick(): Long {
            nowMs += CLOCK_STEP_MS
            return nowMs
        }
    }

    /** 记录每次告警的宿主通道（答「继续」= 只记录不终止，答「退出」= 抛）。 */
    private class PromptRecorder(val exitOnPrompt: Boolean) {
        val prompts = mutableListOf<InfiniteLoopAlert>()
        val answer: (InfiniteLoopAlert) -> InfiniteLoopAnswer = { alert ->
            prompts += alert
            if (exitOnPrompt) InfiniteLoopAnswer.Exit() else InfiniteLoopAnswer.Continue
        }
    }

    private fun parse(vararg lines: String): List<Statement> {
        // 计数器用内建 LOCAL 槽位（LOCAL:0 / LOCAL:1）：内建名解析期/运行期都无需登记，
        // defineLocalLengths(local = 4) 已备好长度 —— 与生产 #DIM 私有名路径无关，减少夹具面。
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "noprogress.erb", i, text))
        }
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("LOOP_PROBE", 0), parse(*lines), setOf("LOOP_PROBE"))
        return c.build("LOOP_PROBE")
    }

    private fun runtime(
        program: Program,
        clock: StepClock,
        recorder: PromptRecorder,
    ): ProcessRuntime {
        val metas = listOf(
            FuncMeta(index = 0, key = "LOOP_PROBE", argIntCount = 0, argStrCount = 0, defIndex = 0),
        )
        return ProcessRuntime(
            program,
            VariableStore().apply {
                defineLocalLengths("LOOP_PROBE", VariableStore.LocalLengths(local = 4))
            },
            FunctionTable(metas),
            VmConfig(
                maxStepsPerRun = Long.MAX_VALUE,
                maxRunMillis = 0L,
                infiniteLoopAlertTimeMs = ALERT_MS,
                monotonicMs = { clock.tick() },
            ),
            consoleSink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
            infiniteLoopPrompt = recorder.answer,
        )
    }

    // ------------------------------------------------------------------
    // ① 误报场景：每轮屏上有新行的合法长执行 —— 不问，跑完
    // ------------------------------------------------------------------

    /**
     * 修复前红：撞线采样点（约 2 万行）墙钟 ≥ 阈值，宿主被问（`prompts` 非空）。
     * 修复后绿：进展戳在涨 ⇒ 零询问，整条链跑完、屏上有内容。
     */
    @Test
    fun `每轮屏上有新行的合法长执行不被判死`() {
        val clock = StepClock()
        val recorder = PromptRecorder(exitOnPrompt = false)
        val rt = runtime(
            compile(
                "@LOOP_PROBE",
                "FOR LOCAL:0, 0, $ITERATIONS",
                "\tPRINTL あ",
                "NEXT",
            ),
            clock,
            recorder,
        )

        rt.execute()

        assertEquals(
            0,
            recorder.prompts.size,
            "屏上每轮都有新行 ⇒ 有进展的长执行，一次都不该问；实际 ${recorder.prompts.size} 次",
        )
        assertTrue(rt.output.isNotEmpty(), "打印链必须真的产出过屏上内容（进展信号的来源）")
    }

    // ------------------------------------------------------------------
    // ② 死循环场景：屏上零变化的无进展循环 —— 仍被问，保护不删
    // ------------------------------------------------------------------

    /**
     * 修复前后都绿：只写变量、屏上一字未长的循环 = 无进展（变量写在移植侧不可观测，
     * 且玩家视角「屏上不动」就是卡了）⇒ 问一次，答「退出」抛
     * [StepLimitExceeded]（[LoopGuardCause.PlayerChoseExit]，文案 = 官方退出串）。
     */
    @Test
    fun `屏上零变化的无进展循环仍被闸住`() {
        val clock = StepClock()
        val recorder = PromptRecorder(exitOnPrompt = true)
        val rt = runtime(
            compile(
                "@LOOP_PROBE",
                "FOR LOCAL:0, 0, $ITERATIONS",
                "\tLOCAL:1 = LOCAL:0",
                "NEXT",
            ),
            clock,
            recorder,
        )

        val e = assertFailsWith<StepLimitExceeded> { rt.execute() }

        assertEquals(1, recorder.prompts.size, "无进展循环被问恰好一次")
        // 反向证明的「时刻」锚（与本波 §4② 配套，独立于次数断言）：假钟几何 = run 起点
        // 一读 + 每采样点一读（采样体内 elapsed 取撞线那次读数），故首问必须落在
        // 「首个 elapsed ≥ 阈值」的采样点且 elapsed 恰等于阈值。本波把
        // infiniteLoopAlertTimeMs 改小（假钟步长 5 s 之下）时，首问应前移到第 1 采样点
        // （elapsed = 5 000）而不是继续赖在第 2 采样点——此断言届时必须变红；改小阈值
        // 而告警时刻纹丝不动，才说明判据没在测它声称的东西。
        assertEquals(
            ALERT_MS,
            recorder.prompts.first().elapsedMs,
            "无进展链首问时刻 = 首个 elapsed ≥ 阈值的采样点（基准几何：第 2 采样点、elapsed 恰为阈值 10 s）",
        )
        assertEquals(LoopGuardCause.PlayerChoseExit, e.guardCause, "抛点 = 玩家选择退出")
        assertTrue(rt.output.isEmpty(), "静默链必须真的零屏上输出（否则①的对照不成立）")
    }
}
