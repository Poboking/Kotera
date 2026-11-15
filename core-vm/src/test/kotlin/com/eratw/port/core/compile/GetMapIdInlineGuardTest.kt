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
 * MAPID 形状内联的**逐条 guard 负例**：
 * 只有 `RETURNF ARG:0 / <正整数字面量>` 且实参/体都干净时才内联；
 * 名字对但体不同、实参含 `FuncCall`、体读 `LOCAL`、多实参、`ARG:1`、除数 0/负/非字面量全部拒。
 */
class GetMapIdInlineGuardTest {

    private var prev: String? = null

    @BeforeTest
    fun enableInline() {
        prev = System.getProperty("eratw.perf.inlineGetMapId")
        System.setProperty("eratw.perf.inlineGetMapId", "true")
    }

    @AfterTest
    fun restore() {
        if (prev == null) System.clearProperty("eratw.perf.inlineGetMapId")
        else System.setProperty("eratw.perf.inlineGetMapId", prev!!)
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("MAIN_MAP", OperandType.INT, 1)
            .declareVariable("ARG", OperandType.INT, 1)
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("D", OperandType.INT, 0)
            .declareFunction("GET_MAPID", OperandType.INT)
            .declareMethod("GET_MAPID")
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
        val c = ErbProgramCompiler(inlineShapeBodies = mapOf("GET_MAPID" to body))
        c.compileFunction(
            FuncSig("CALLER", isMethod = true),
            parse(*caller),
            setOf("GET_MAPID", "MYFUNC"),
        )
        return c.inlineGetMapIdInlined
    }

    private fun body(vararg lines: String): List<Statement> = parse(*lines)

    @Test
    fun `正向 - RETURNF ARG 除以正整数字面量内联`() {
        assertEquals(1, inlined(body("RETURNF ARG:0 / 100"), "RETURNF GET_MAPID(101)"))
        assertEquals(1, inlined(body("RETURNF ARG:0 / 37"), "RETURNF GET_MAPID(101)"))
    }

    @Test
    fun `负例 - 名字对但体不同`() {
        assertEquals(0, inlined(body("RETURNF ARG:0 + 1"), "RETURNF GET_MAPID(101)"))
        assertEquals(0, inlined(body("RETURNF ARG:0"), "RETURNF GET_MAPID(101)"))
        assertEquals(0, inlined(body("RETURNF ARG:0 / 100", "RETURNF ARG:0 / 100"), "RETURNF GET_MAPID(101)"))
    }

    @Test
    fun `负例 - 实参含 FuncCall`() {
        assertEquals(0, inlined(body("RETURNF ARG:0 / 100"), "RETURNF GET_MAPID(MYFUNC(1))"))
    }

    @Test
    fun `负例 - 体实际引用 LOCAL`() {
        assertEquals(0, inlined(body("RETURNF LOCAL:0 / 100"), "RETURNF GET_MAPID(101)"))
        assertEquals(0, inlined(body("RETURNF ARG:0 / LOCAL:0"), "RETURNF GET_MAPID(101)"))
    }

    @Test
    fun `负例 - 多实参或零实参`() {
        assertEquals(0, inlined(body("RETURNF ARG:0 / 100"), "RETURNF GET_MAPID(1, 2)"))
        assertEquals(0, inlined(body("RETURNF ARG:0 / 100"), "RETURNF GET_MAPID()"))
    }

    @Test
    fun `负例 - ARG 下标不是 0`() {
        assertEquals(0, inlined(body("RETURNF ARG:1 / 100"), "RETURNF GET_MAPID(101)"))
    }

    @Test
    fun `负例 - 除数 0-负-非字面量`() {
        assertEquals(0, inlined(body("RETURNF ARG:0 / 0"), "RETURNF GET_MAPID(101)"))
        assertEquals(0, inlined(body("RETURNF ARG:0 / -100"), "RETURNF GET_MAPID(101)"))
        assertEquals(0, inlined(body("RETURNF ARG:0 / D"), "RETURNF GET_MAPID(101)"))
    }
}
