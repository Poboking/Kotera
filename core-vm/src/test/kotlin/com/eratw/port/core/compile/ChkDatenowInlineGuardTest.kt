package com.eratw.port.core.compile

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E1 定向内联的**逐条 guard 负例**：不满足任一条件必须不内联（`inlineChkDatenowInlined == 0`）。
 *
 * 这条测试是「防按名字内联」的机械闸：即使调用名是 `CHK_DATENOW`，体不同/实参不对/
 * `ARG` 下标不是 0，都不许触发。
 */
class ChkDatenowInlineGuardTest {

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
            .declareFunction("CHK_DATENOW", OperandType.INT)
            .declareMethod("CHK_DATENOW")
            .declareFunction("MYFUNC", OperandType.INT)
            .declareMethod("MYFUNC"),
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

    private fun inlined(body: List<Statement>, vararg caller: String): Int {
        val c = ErbProgramCompiler(inlineShapeBodies = mapOf("CHK_DATENOW" to body))
        c.compileFunction(
            FuncSig("CALLER", isMethod = true),
            parse(*caller),
            setOf("CHK_DATENOW", "MYFUNC"),
        )
        return c.inlineChkDatenowInlined
    }

    private fun canonicalBody(): List<Statement> = parse(
        "IF ARG:0 == MAIN_MAP",
        "RETURNF 0",
        "ELSE",
        "RETURNF 1",
        "ENDIF",
    )

    @Test
    fun `正向 - 原形体与单实参内联`() {
        assertEquals(1, inlined(canonicalBody(), "RETURNF CHK_DATENOW(1)"))
    }

    @Test
    fun `负例 - 实参数不是 1 不内联`() {
        assertEquals(0, inlined(canonicalBody(), "RETURNF CHK_DATENOW()"))
        assertEquals(0, inlined(canonicalBody(), "RETURNF CHK_DATENOW(1, 2)"))
    }

    @Test
    fun `负例 - 体不是纯 IF-双 RETURNF 形状不内联`() {
        val body = parse(
            "PRINT 1",
            "IF ARG:0 == MAIN_MAP",
            "RETURNF 0",
            "ELSE",
            "RETURNF 1",
            "ENDIF",
        )
        assertEquals(0, inlined(body, "RETURNF CHK_DATENOW(1)"))
    }

    @Test
    fun `负例 - 实参含 FuncCall 不内联`() {
        assertEquals(0, inlined(canonicalBody(), "RETURNF CHK_DATENOW(MYFUNC(1))"))
    }

    @Test
    fun `负例 - ARG 下标不是 0 不内联`() {
        val body = parse(
            "IF ARG:1 == MAIN_MAP",
            "RETURNF 0",
            "ELSE",
            "RETURNF 1",
            "ENDIF",
        )
        assertEquals(0, inlined(body, "RETURNF CHK_DATENOW(1)"))
    }

    @Test
    fun `负例 - 同名但体不同（RETURNF 2-3）不内联`() {
        val body = parse(
            "IF ARG:0 == MAIN_MAP",
            "RETURNF 2",
            "ELSE",
            "RETURNF 3",
            "ENDIF",
        )
        assertEquals(0, inlined(body, "RETURNF CHK_DATENOW(1)"))
    }

    @Test
    fun `负例 - 同名但体不同（左右返回互换）不内联`() {
        val body = parse(
            "IF ARG:0 == MAIN_MAP",
            "RETURNF 1",
            "ELSE",
            "RETURNF 0",
            "ENDIF",
        )
        assertEquals(0, inlined(body, "RETURNF CHK_DATENOW(1)"))
    }
}
