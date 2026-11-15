package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * E1 异常归因守卫：内联区（`Ternary(arg == <pure>, 0, 1)`）在运行期抛 `CodeEE` 时，
 * `scriptFile` / `funcName` / `line` 必须仍报**调用方**，不能漂到被调 `CHK_DATENOW`。
 *
 * 装置：被调体右侧放一个运行期会抛 `ExprEvalException` 的变量 `BOOM`；VM 的 `_EVAL_INT`
 * 捕获后经 `errorAt(pc)` 组装 `CodeEE`，pc 属于调用方 ⇒ 归因调用方。
 */
class ChkDatenowInlineErrorAttributionTest {

    private var prev: String? = null

    @BeforeTest
    fun enableInline() {
        prev = System.getProperty("eratw.perf.inlineChkDatenow")
        System.setProperty("eratw.perf.inlineChkDatenow", "true")
    }

    @AfterTest
    fun restore() {
        if (prev == null) System.clearProperty("eratw.perf.inlineChkDatenow")
        else System.setProperty("eratw.perf.inlineChkDatenow", prev!!)
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("MAIN_MAP", OperandType.INT, 1)
            .declareVariable("ARG", OperandType.INT, 1)
            .declareVariable("BOOM", OperandType.INT, 1)
            .declareFunction("CHK_DATENOW", OperandType.INT)
            .declareMethod("CHK_DATENOW"),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "test.erb", i + 1, text))
        }.filterNotNull()
    }

    private class BoomVars : VariableReader {
        override fun isConst(name: String): Boolean = false
        override fun readInt(name: String, indices: LongArray): Long {
            if (name.equals("BOOM", ignoreCase = true)) throw ExprEvalException("boom")
            return 0L
        }
        override fun readStr(name: String, indices: LongArray): String = ""
        override fun readFloat(name: String, indices: LongArray): Double = 0.0
        override fun plusInt(name: String, indices: LongArray, delta: Long): Long = 0L
        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 0L
    }

    private class BoomCtx(private val vars: BoomVars) : EvalContext {
        override val variables: VariableReader get() = vars
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw ExprEvalException("unbound $name")
        override fun printSystemLine(message: String) {}
    }

    private class Host(private val program: Program) : VmHost {
        private val vars = BoomVars()
        override val evalContext: EvalContext = BoomCtx(vars)
        override fun scriptFileOf(functionIndex: Int, functionKey: String): String = "test.erb"
        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome = StmtOutcome.Normal
        override fun print(text: String) {}
    }

    @Test
    fun `内联区 CodeEE 仍报调用方 scriptFile-fn-line`() {
        val body = parse(
            "IF ARG:0 == BOOM",
            "RETURNF 0",
            "ELSE",
            "RETURNF 1",
            "ENDIF",
        )
        val c = ErbProgramCompiler(inlineShapeBodies = mapOf("CHK_DATENOW" to body))
        c.compileFunction(
            FuncSig("CALLER", isMethod = true),
            parse("RETURNF CHK_DATENOW(1)"),
            setOf("CHK_DATENOW"),
        )
        // 前提：确实走了内联（否则本测试测的就是嵌套通道的旧归因）。
        assertEquals(1, c.inlineChkDatenowInlined, "未触发内联，归因测试无效")
        val program = c.build()
        val e = assertFailsWith<CodeEE> { VirtualMachine(program, Host(program)).run() }
        assertEquals("CALLER", e.funcName, "fn 必须报调用方")
        assertEquals("test.erb", e.scriptFile, "scriptFile 必须报调用方源文件")
        assertEquals(1, e.line, "line 必须报调用方 0 基行（调用方第 1 行；被调体第 0 行）")
    }
}
