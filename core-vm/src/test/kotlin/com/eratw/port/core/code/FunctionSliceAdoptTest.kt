package com.eratw.port.core.code

import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.core.compile.LocalDim
import com.eratw.port.core.compile.LocalLayout
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 函数字节切片「编译→切片→重放」等价闸（MODERN-R11-R4B2）。
 *
 * 钉的是一条结构性事实：命中缓存改走 [ErbProgramCompiler.adoptFunction] 重放字节后，
 * 产出的 Program 与「同一份函数体真编译一次」逐项相同 —— 指令字、行号、函数表（含
 * labelPcs / paramNames）、调用点实参表、四个池的内容与条数、全局槽读数全部对拍。
 * 若 adopt 少登记一项副作用（标签、callArgs、REF 回填点）或池下标没按新基址重写，
 * 这里的逐项断言就会在对应字段上红，而不是等到 .erbc 字节对拍才发现。
 *
 * 覆盖形态：同一 builder「编译→切片→adopt」对「直接再编译一次」、空函数、带 _CALL、
 * 带 REF 形参位、以及 label/callArgs/pool 在「切片录制环境与重放环境不同」下的偏移重映射。
 */
class FunctionSliceAdoptTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("B", OperandType.INT, 1)
            .declareVariable("S", OperandType.STR, 1)
            .declareVariable("RESULT", OperandType.INT, 0)
            .declareVariable("SRC_ARR", OperandType.INT, 1)
            .declareVariable("REF_TGT", OperandType.INT, 1)
            .declareFunction("MYFUNC", OperandType.INT)
            .declareMethod("MYFUNC")
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
            p.parseLogicalLine(LogicalLine(kind, "slice.erb", i + 1, text))
        }.filterNotNull()
    }

    /** 两条路径一律同一编译档，避免发射面开关把结构对拍连带打红。 */
    private fun newCompiler(layout: LocalLayout = GlobalNameLayout()): ErbProgramCompiler =
        ErbProgramCompiler(layout, fuseCompareJump = false)

    /** 逐项对拍两个 Program 的全部可观察字节面。 */
    private fun assertSameProgram(a: Program, b: Program, tag: String) {
        assertEquals(a.code.toList(), b.code.toList(), "$tag code")
        assertEquals(a.lines.toList(), b.lines.toList(), "$tag lines")
        assertEquals(a.instructionCount, b.instructionCount, "$tag instructionCount")
        assertEquals(a.functions.size, b.functions.size, "$tag functions.size")
        for (i in a.functions.indices) {
            val x = a.functions[i]
            val y = b.functions[i]
            assertEquals(x.name, y.name, "$tag fn[$i].name")
            assertEquals(x.entryPc, y.entryPc, "$tag fn[$i].entryPc")
            assertEquals(x.arity, y.arity, "$tag fn[$i].arity")
            assertEquals(x.slotCount, y.slotCount, "$tag fn[$i].slotCount")
            assertEquals(x.paramNames, y.paramNames, "$tag fn[$i].paramNames")
            assertEquals(x.labelNames, y.labelNames, "$tag fn[$i].labelNames")
            assertEquals(x.labelPcs, y.labelPcs, "$tag fn[$i].labelPcs")
        }
        assertEquals(a.callArgs, b.callArgs, "$tag callArgs")
        assertEquals(a.globalSlots, b.globalSlots, "$tag globalSlots")
        assertEquals(a.constantCount, b.constantCount, "$tag constantCount")
        assertEquals(a.stringCount, b.stringCount, "$tag stringCount")
        assertEquals(a.exprCount, b.exprCount, "$tag exprCount")
        assertEquals(a.statementCount, b.statementCount, "$tag statementCount")
        for (i in 0 until a.constantCount) assertEquals(a.constant(i), b.constant(i), "$tag constant[$i]")
        for (i in 0 until a.stringCount) assertEquals(a.string(i), b.string(i), "$tag string[$i]")
        for (i in 0 until a.exprCount) assertEquals(a.expr(i), b.expr(i), "$tag expr[$i]")
        for (i in 0 until a.statementCount) {
            assertEquals(a.statement(i)::class.simpleName, b.statement(i)::class.simpleName, "$tag statement[$i].class")
            assertEquals(a.statement(i).raw, b.statement(i).raw, "$tag statement[$i].raw")
        }
    }

    /**
     * 镜像法：A = TARGET / F_FIRST / F_SECOND 全真编译；B = TARGET 真编译、F_FIRST 录制切片、
     * F_SECOND 用 adoptFunction 重放同一片切片。两条路径除「F_SECOND 是 adopt 还是真编译」
     * 外逐项相同（同名、同序、同函数体、同编译档），故 Program 可直接逐项对拍。
     */
    private fun mirror(body: List<Statement>, known: Set<String>): Pair<Program, Program> {
        val target = parse("RETURN")
        val a = newCompiler()
        a.compileFunction(FuncSig("TARGET"), target, known)
        a.compileFunction(FuncSig("F_FIRST"), body, known)
        a.compileFunction(FuncSig("F_SECOND"), body, known)
        val pa = a.build()

        val b = newCompiler()
        b.compileFunction(FuncSig("TARGET"), target, known)
        val slice = b.compileFunctionRecorded(FuncSig("F_FIRST"), body, known)
        b.adoptFunction(slice, FuncSig("F_SECOND"))
        val pb = b.build()
        return pa to pb
    }

    @Test
    fun `编译到切片再 adopt 与直接再编译一次逐字节同`() {
        val body = parse(
            "A = 1 + 2",
            "PRINTFORML V={A}",
            "${'$'}LBL",
            "IF A > 0",
            "PRINTL BIG",
            "ELSE",
            "PRINTL SMALL",
            "ENDIF",
            "CALL TARGET, A, 3",
            "GOTO LBL",
        )
        val (pa, pb) = mirror(body, setOf("TARGET", "F_FIRST", "F_SECOND"))
        assertSameProgram(pa, pb, "rich")
    }

    @Test
    fun `空函数`() {
        val (pa, pb) = mirror(emptyList(), setOf("TARGET", "F_FIRST", "F_SECOND"))
        assertSameProgram(pa, pb, "empty")
    }

    @Test
    fun `带 _CALL 与逐位 callArgs`() {
        val body = parse("CALL TARGET, 1, 2", "PRINTL E", "RETURN")
        val (pa, pb) = mirror(body, setOf("TARGET", "F_FIRST", "F_SECOND"))
        assertSameProgram(pa, pb, "call")
    }

    @Test
    fun `带 REF 形参位的回填点相对化`() {
        val dims = mapOf("WRITER" to listOf(LocalDim("REF_TGT", isStr = false, ref = true)))
        val known = setOf("WRITER", "F_FIRST", "F_SECOND")
        fun pre(): ErbProgramCompiler {
            val c = newCompiler(DimSpecLayout(GlobalNameLayout(), dims))
            c.compileFunction(FuncSig("WRITER", 1, paramNames = listOf("REF_TGT")), parse("RETURN"), known)
            return c
        }
        val body = parse("CALL WRITER, SRC_ARR", "RETURN")
        val a = pre()
        a.compileFunction(FuncSig("F_FIRST"), body, known)
        a.compileFunction(FuncSig("F_SECOND"), body, known)
        val pa = a.build()
        val b = pre()
        val slice = b.compileFunctionRecorded(FuncSig("F_FIRST"), body, known)
        b.adoptFunction(slice, FuncSig("F_SECOND"))
        val pb = b.build()
        assertSameProgram(pa, pb, "ref")
    }

    /**
     * 偏移重映射专项：切片在「长前置」（高入口 pc、高池基址）录制，重放到「短前置」
     * （低入口 pc、低池基址）的 builder。delta ≠ 0 且池下标全变 —— 若 adoptSlice 照抄
     * 旧池下标，指令会指向错误的池条目，与「从同一短前置真编译一次」的逐项对拍必红。
     * 对照侧与被测侧前置状态逐项相同，故整体 Program 可比。
     */
    @Test
    fun `label callArgs pool 下标跨 builder 偏移重映射`() {
        val body = parse(
            "A = 111 + 222",
            "PRINTFORML P={A}",
            "${'$'}LBL",
            "CALL TARGET, 7, 8",
            "GOTO LBL",
        )
        val known = setOf("TARGET", "F", "SOURCE", "PAD_SHORT", "PAD_LONG_A", "PAD_LONG_B")
        val target = parse("RETURN")

        // S：长前置下录制 SOURCE，切片带高入口 pc 与高池基址
        val s = newCompiler()
        s.compileFunction(FuncSig("TARGET"), target, known)
        s.compileFunction(FuncSig("PAD_LONG_A"), parse("PRINTL LONGA", "A = 333", "RETURN"), known)
        s.compileFunction(FuncSig("PAD_LONG_B"), parse("PRINTL LONGB", "A = 444", "RETURN"), known)
        val slice = s.compileFunctionRecorded(FuncSig("SOURCE"), body, known)
        assertTrue(slice.entryPc > 0, "切片须在非零 pc 录制，才能测出 delta 平移")
        assertTrue(slice.poolOpCount > 0, "切片须含池追加，才能测出池下标重写")

        fun shortPrefix(c: ErbProgramCompiler) {
            c.compileFunction(FuncSig("TARGET"), target, known)
            c.compileFunction(FuncSig("PAD_SHORT"), parse("PRINTL SHORT", "RETURN"), known)
        }

        val a = newCompiler()
        shortPrefix(a)
        a.compileFunction(FuncSig("F"), body, known)
        val pa = a.build()

        val b = newCompiler()
        shortPrefix(b)
        b.adoptFunction(slice, FuncSig("F"))
        val pb = b.build()

        assertSameProgram(pa, pb, "remap")
    }
}
