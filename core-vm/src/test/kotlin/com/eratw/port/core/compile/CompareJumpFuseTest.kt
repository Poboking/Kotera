package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PERF-LOOP：`_LT_JZ` / `_GT_JZ` 循环条件融合的行为闸。
 *
 * 覆盖正/负/零/非常量步长、BREAK/CONTINUE；开/关两档逐变量对比，
 * 并锁住「关态逐字节等于缺省构造」「开态真的发射融合指令」。
 *
 * 真语料读数（2026-11-09）：`GMF_METHOD_UPDATE` 动态指令 **87,200 → 78,120（−10.4%）**，
 * 同进程双产物 A/B flat **1.073×** / object **1.033×**；完整 `CHARA_MOVEMENT`
 * 11,574,711 → 10,394,437 步（−10.2%）。**2026-11-09 全量门后翻默认开**；
 * 关态 = `-Deratw.perf.noCompareJumpFuse=true`（产物逐字节等于翻默认前）。
 */
class CompareJumpFuseTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "loop-fuse.erb", i + 1, text))
        }
    }

    private fun compile(fuse: Boolean, vararg lines: String): Program {
        val c = ErbProgramCompiler(fuseCompareJump = fuse)
        c.compileFunction(FuncSig("T"), parse(*lines), setOf("T"))
        return c.build("T")
    }

    private fun run(program: Program): VariableStore {
        val store = VariableStore()
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()
        return store
    }

    private fun intOf(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    private fun opcodes(p: Program): List<Int> = (0 until p.instructionCount).map { p.op(it) }

    private fun assertSame(lines: Array<String>) {
        val on = run(compile(true, *lines))
        val off = run(compile(false, *lines))
        for (name in listOf("A", "I", "J")) {
            assertEquals(intOf(off, name), intOf(on, name), "变量 $name 开/关不一致")
        }
    }

    @Test
    fun `正步长融合与旧序列同值`() {
        val lines = arrayOf("@T", "A = 0", "FOR I, 0, 5", "A = A + 1", "NEXT")
        assertSame(lines)
        val on = run(compile(true, *lines))
        assertEquals(5L, intOf(on, "A"))
        assertEquals(5L, intOf(on, "I"))
        assertTrue(opcodes(compile(true, *lines)).any { it == Opcode._LT_JZ })
    }

    @Test
    fun `负步长融合与旧序列同值`() {
        val lines = arrayOf("@T", "A = 0", "FOR I, 5, 0, -1", "A = A + 1", "NEXT")
        assertSame(lines)
        val on = run(compile(true, *lines))
        assertEquals(5L, intOf(on, "A"))
        assertEquals(0L, intOf(on, "I"))
        assertTrue(opcodes(compile(true, *lines)).any { it == Opcode._GT_JZ })
    }

    @Test
    fun `非常量步长融合与旧序列同值`() {
        val lines = arrayOf("@T", "S = 2", "A = 0", "FOR I, 0, 10, S", "A = A + 1", "NEXT")
        assertSame(lines)
        val on = run(compile(true, *lines))
        assertEquals(5L, intOf(on, "A"))
        assertEquals(10L, intOf(on, "I"))
        assertTrue(opcodes(compile(true, *lines)).any { it == Opcode._LT_JZ })
    }

    @Test
    fun `零步长直接出体不发射比较跳转`() {
        val lines = arrayOf("@T", "A = 0", "FOR I, 0, 5, 0", "A = A + 1", "NEXT")
        assertSame(lines)
        assertEquals(0L, intOf(run(compile(true, *lines)), "A"))
        assertTrue(
            opcodes(compile(true, *lines)).none { it == Opcode._LT_JZ || it == Opcode._GT_JZ },
            "零步长不应发射比较跳转",
        )
    }

    @Test
    fun `BREAK 与 CONTINUE 在融合循环内同值`() {
        val lines = arrayOf(
            "@T",
            "A = 0",
            "FOR I, 0, 10",
            "IF I == 3",
            "BREAK",
            "ENDIF",
            "A = A + 1",
            "NEXT",
            "J = 0",
            "FOR K, 0, 5",
            "IF K == 2",
            "CONTINUE",
            "ENDIF",
            "J = J + 1",
            "NEXT",
        )
        val on = run(compile(true, *lines))
        val off = run(compile(false, *lines))
        for (name in listOf("A", "I", "J", "K")) {
            assertEquals(intOf(off, name), intOf(on, name), "变量 $name 开/关不一致")
        }
        assertEquals(3L, intOf(on, "A"))
        assertEquals(4L, intOf(on, "I"))
        assertEquals(4L, intOf(on, "J"))
    }

    @Test
    fun `融合产物落盘往返后语义不变`() {
        val lines = arrayOf(
            "@T",
            "A = 0",
            "FOR I, 0, 5",
            "A = A + 1",
            "NEXT",
            "B = 0",
            "FOR J, 5, 0, -1",
            "B = B + 1",
            "NEXT",
        )
        val inMemory = run(compile(true, *lines))
        val loaded = ProgramLoader.load(ErbcWriter.encode(compile(true, *lines)), "loop-fuse.erbc")
        val roundTrip = run(loaded)
        for (name in listOf("A", "I", "B", "J")) {
            assertEquals(intOf(inMemory, name), intOf(roundTrip, name), "落盘往返后 $name 不一致")
        }
    }

    @Test
    fun `缺省构造跟随开关且关态不含融合指令`() {
        val lines = arrayOf("@T", "A = 0", "FOR I, 0, 5", "A = A + 1", "NEXT")
        val dflt = ErbProgramCompiler().run {
            compileFunction(FuncSig("T"), parse(*lines), setOf("T"))
            build("T")
        }
        // 产品档 = 缺省构造；`-Deratw.perf.noCompareJumpFuse=true` 时它必须回到关态。
        val optOut = java.lang.Boolean.getBoolean("eratw.perf.noCompareJumpFuse")
        assertEquals(compile(!optOut, *lines).code.toList(), dflt.code.toList(), "缺省构造必须等于开关对应的那一档")
        val off = compile(false, *lines)
        assertTrue(opcodes(off).none { it == Opcode._LT_JZ || it == Opcode._GT_JZ }, "关态不得发射融合指令")
        if (!optOut) {
            assertTrue(opcodes(dflt).any { it == Opcode._LT_JZ }, "产品档应发射融合指令")
        }
    }
}
