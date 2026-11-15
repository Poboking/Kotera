package com.eratw.port.core.proc

import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.code.LoopGuardCause
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * loopguard 波：装置预算询问的**宿主接线**（ProcessRuntime 侧）。
 *
 * VM 只负责「命中 ⇒ 问一次 ⇒ 继续则抬预算」；`loopguard.device_budget_continue` 这条
 * 轨迹行归宿主落（波次契约 §2 第 2 条）。本文件钉两件接线事实：
 *
 * 1. 生产通道（[ProcessRuntime] 的 `infiniteLoopPrompt`）同时服务两条闸：墙钟闸
 *    （`askInfiniteLoop`）与装置预算（`askDeviceBudgetContinue`）用**同一条弹框 lambda**，
 *    不另造第二张框；答「继续」时恰好落一行轨迹，答「退出」/无通道时不落。
 * 2. 无通道（`infiniteLoopPrompt = null` ⇒ 缺省 NoUi）时仍按
 *    `StepLimitExceeded(DeviceBudget)` 可诊断中止 —— headless 不静默、不悬挂。
 */
class InfiniteLoopDeviceBudgetTraceTest {
    private data class Fn(val name: String, val lines: Array<String>)

    private class Pb(val program: Program, val names: List<String>) {
        fun index(name: String): Int =
            program.functions.indices.first {
                program.functions[it].name.equals(name, ignoreCase = true)
            }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("COUNT", OperandType.INT, 1),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "loopguard.erb", i + 1, text))
        }
    }

    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            compiler.compileFunction(
                FuncSig(name = f.name, arity = labels[i].paramRefs.size, paramNames = labels[i].paramRefs),
                parsed[i],
                known,
            )
        }
        return Pb(compiler.build(fns.first().name), fns.map { it.name })
    }

    /** 500 次空转 FOR：约 3000 条指令，预算 200 ⇒ 必然多次撞线。 */
    private fun longLoop(): Pb = compile(
        Fn(
            "MAIN",
            arrayOf(
                "@MAIN",
                "FOR LOCAL, 0, 500",
                "COUNT = LOCAL",
                "NEXT",
                "RETURN",
            ),
        ),
    )

    private fun runtime(
        pb: Pb,
        store: VariableStore,
        config: VmConfig,
        prompt: ((InfiniteLoopAlert) -> InfiniteLoopAnswer)? = null,
        trace: ((String) -> Unit)? = null,
    ): ProcessRuntime {
        val metas = pb.names.map { name ->
            val index = pb.index(name)
            val arity = pb.program.functions[index].arity
            FuncMeta(index = index, key = name, argIntCount = arity, argStrCount = arity, defIndex = index)
        }
        val host = object : BuiltinHost {}
        return ProcessRuntime(
            pb.program,
            store,
            FunctionTable(metas),
            config,
            builtinHost = host,
            infiniteLoopPrompt = prompt,
            loopGuardTrace = trace,
        )
    }

    // ------------------------------------------------------------------
    // 1. 答「继续」⇒ 抬预算 + 落轨迹行
    // ------------------------------------------------------------------

    @Test
    fun `答继续时落一行 device_budget_continue 轨迹`() {
        val pb = longLoop()
        val prompts = mutableListOf<InfiniteLoopAlert>()
        val trace = mutableListOf<String>()
        val rt = runtime(
            pb,
            VariableStore(),
            VmConfig(maxStepsPerRun = 200L, maxRunMillis = 0L, infiniteLoopAlertTimeMs = 0L),
            prompt = { alert ->
                prompts += alert
                InfiniteLoopAnswer.Continue
            },
            trace = { trace += it },
        )

        rt.execute()

        assertTrue(prompts.isNotEmpty(), "预算 200 跑一条约 3000 条指令的链 ⇒ 必须问到玩家")
        assertEquals(prompts.size, trace.size, "每次答「继续」恰好落一行轨迹")
        assertTrue(
            trace.all { it.startsWith("loopguard.device_budget_continue") },
            "轨迹行前缀必须逐字一致：${trace}",
        )
        assertTrue(
            trace.first().contains("budget=200"),
            "首行要带撞线预算，事后才对得上「哪一档被抬高」：${trace.first()}",
        )
        assertTrue(
            trace.any { it.contains("next=400") },
            "抬预算后的下一档要可对账：${trace}",
        )
        assertTrue(
            prompts.all { it.message().isNotEmpty() },
            "弹框正文仍用官方模板（同一条框，不另造文案）",
        )
    }

    // ------------------------------------------------------------------
    // 2. 没有弹框通道 ⇒ 可诊断中止，且不落「继续」轨迹
    // ------------------------------------------------------------------

    @Test
    fun `没有弹框通道时按设备预算中止且不落轨迹`() {
        val pb = longLoop()
        val trace = mutableListOf<String>()
        val rt = runtime(
            pb,
            VariableStore(),
            VmConfig(maxStepsPerRun = 200L, maxRunMillis = 0L, infiniteLoopAlertTimeMs = 0L),
            prompt = null,
            trace = { trace += it },
        )

        val e = assertFailsWith<StepLimitExceeded> { rt.execute() }

        assertEquals(LoopGuardCause.DeviceBudget, e.guardCause)
        assertTrue(trace.isEmpty(), "没有作答就没有「继续」轨迹：${trace}")
        assertTrue(
            e.message.orEmpty().contains("执行步数超过上限"),
            "headless 仍是可诊断的步数口径中止：${e.message}",
        )
    }
}
