package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B1a：CALLSHARP「No native method」的**行级联结记录**（2026-11-06 报错相位翻案）。
 *
 * 原版口径：联结期 `CALLSHARP_Instruction.SetJumpTo`（FunctionIdentifier.cs:1193-1210）对常量名
 * 查 `PluginManager.HasMethod`，未命中 `:1204 ParserMediator.Warn(isError: true)` 只把
 * `line.IsError/ErrMes` 记到行上（ParserMediator.cs:129-130），命中把 CallFunc 绑死（:1208），
 * **此后不再重查**；官方执行期走到该行由 Process.cs:1017-1019 抛 `CodeEE(ErrMes)` —— 抛的是
 * **记录值**，不是当次的查询结果。本移植版按**通用策略**（2026-11-09）不抛：打一行同文案的
 * 错误行后跳过该语句，脚本继续（有意偏离 ADR-005，登记见 `specs/verify-notes.md` §35）。
 *
 * 移植侧裁决点后移到「该行第一次执行」（注册表挂 store.aux、宿主装配晚于语料装载，
 * GameHost.kt:3476 ⇒ 装载期注册表恒空，照抄原版相位会误杀已接桩的 ModelSet/Chat）；
 * 机制与不重查口径见 `core/fn/PluginMethods.kt` 的 SharpLinkTable。
 * 本钉四例把「记行错 → 提示一次 → 不复活 / 命中 → 绑死 → 不受 clear 影响」
 * 在对象树通道（Android 生产默认）与扁平通道（归因/装置）各钉一遍。
 */
class CallSharpLinkPhaseTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "linkphase.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("SHARP_LINK_PROBE", 0),
            parse("@SHARP_LINK_PROBE", *lines),
            setOf(),
            ExprCallTable(),
        )
        return c.build("SHARP_LINK_PROBE")
    }

    private fun runtimeWith(
        program: Program,
        store: VariableStore,
        sink: LineBufferConsoleSink =
            LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900),
    ): ProcessRuntime =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = sink,
        )

    /** 控制台上屏的**错误行**文案（printError 通道；Kind.ERROR 段）。 */
    private fun errorLines(sink: LineBufferConsoleSink): List<String> =
        sink.snapshot().flatMap { it.segments }
            .filter { it.kind == LineBufferConsoleSink.Kind.ERROR }
            .map { it.text }

    /** 对象树通道（Android 生产默认形态）：关掉 flat 直读（Program 的 internal 开关，同模块测试可及）。 */
    private fun asTreeChannel(program: Program): Program {
        program.flatStatementsEnabled = false
        return program
    }

    // -----------------------------------------------------------------
    // 未命中：行级错误记一次，之后抛记录值（注册表再长出同名也不复活）
    // -----------------------------------------------------------------

    @Test
    fun `flat 未命中首执行记行错 接线后重执行仍跳过不复活`() {
        val store = VariableStore()
        val sink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900)
        val rt = runtimeWith(compile("CALLSHARP Ghost(1)"), store, sink)
        rt.execute()
        assertEquals(
            listOf("No native method Ghost found"),
            errorLines(sink),
            "首执行打官方原文（FunctionIdentifier.cs:1204）后跳过，不抛",
        )
        // 模拟「宿主此刻才把方法接上」：行级错误已记死，重执行照记录值走 ——
        // 原版 CallFunc 在联结期已定（:1208），IsError 行不因后注册而复活。
        var hits = 0
        nativeMethods(store.aux).register("Ghost") { hits++ }
        rt.execute()
        assertEquals(0, hits, "二次执行不重查注册表：后注册的方法不会被调到")
        assertEquals(1, errorLines(sink).size, "提示也只在首执行出一次")
    }

    @Test
    fun `对象树通道未命中同口径`() {
        val store = VariableStore()
        val sink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900)
        val rt = runtimeWith(asTreeChannel(compile("CALLSHARP Ghost(1)")), store, sink)
        rt.execute()
        assertEquals(listOf("No native method Ghost found"), errorLines(sink))
        var hits = 0
        nativeMethods(store.aux).register("Ghost") { hits++ }
        rt.execute()
        assertEquals(0, hits, "树通道同口径：不重查注册表")
        assertEquals(1, errorLines(sink).size)
    }

    // -----------------------------------------------------------------
    // 命中：方法本体绑死（= 原版联结期 CallFunc），clear 注册表不影响已绑定行
    // -----------------------------------------------------------------

    @Test
    fun `flat 命中绑死 清空注册表后该行照常执行`() {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val rt = runtimeWith(compile("CALLSHARP Ghost(1)"), store)
        rt.execute()
        assertEquals(listOf(0), calls, "首执行走注册表命中")
        // 原版 ClearMethods（PluginManager.cs:545-548）不清已绑定的 SpCallSharpArgment.CallFunc
        // ⇒ 联结期绑定的行继续可用；移植侧绑定缓存在 SharpLinkTable，同口径。
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0, 1), calls, "重执行用绑定的方法本体，不重查（也不因空表报错）")
    }

    @Test
    fun `对象树通道命中绑死同口径`() {
        val store = VariableStore()
        val calls = mutableListOf<Int>()
        nativeMethods(store.aux).register("Ghost") { _: Array<NativeParam> -> calls.add(calls.size) }
        val rt = runtimeWith(asTreeChannel(compile("CALLSHARP Ghost(1)")), store)
        rt.execute()
        nativeMethods(store.aux).clear()
        rt.execute()
        assertEquals(listOf(0, 1), calls)
    }

    // -----------------------------------------------------------------
    // 首执行前的判名不消耗实参求值：无桩时同函数里 CALLSHARP 之前的语句照常生效
    // -----------------------------------------------------------------

    @Test
    fun `无桩提示落该行 实参不求值 前后语句都照常执行`() {
        // 官方 IsError 检查点在 DoInstruction 之前（Process.cs:1017-1019）⇒ 该行自己的实参
        // 不求值；本移植版只是把「抛」换成「提示 + 跳过」，前后语句都正常生效。
        val store = VariableStore()
        val sink = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900)
        val rt = runtimeWith(
            compile("PRINTL marker", "CALLSHARP Ghost(1)", "PRINTL after"),
            store,
            sink,
        )
        rt.execute()
        assertEquals(listOf("No native method Ghost found"), errorLines(sink))
        val out = rt.output.toString()
        assertTrue("marker" in out, "前置 PRINTL 已生效：提示只属于 CALLSHARP 那一行")
        assertTrue("after" in out, "后续语句照常执行：跳过不等于中断")
    }
}
