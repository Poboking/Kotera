package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.ArgInterner
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 运行期代码缓存三件套的**上界重置**（2026-11-14 玩家 OOM 根修的定向单测）。
 *
 * 背景一句话：`RecordSource.nodes`（节点表）/ `ArgInterner` 规范表 / `StringPool` 去重表
 * 三件运行期缓存都是「只进不出」，随游玩覆盖面把 512 MiB 堆吃满（桌面 soak512 实测
 * GC 后 live 139 → 434~491 MiB 且逐字恒定），真机叠加图形/渲染后 OOM。本波给三件缓存
 * 接「装载趟无界、运行期越界整表重置」的上界；本类钉三件事：
 * 1. **装载趟无界**：未武装（缺省）实例无论灌多少都不重置（装载读数与改造前逐字相同）；
 * 2. **运行期越界即重置**：武装后越过预算触发重置，诊断计数器如实增长；
 * 3. **重置只丢去重、不丢语义**：跨代读取解出的是逐字段相同的新实例，重编码逐字节一致，
 *    语句缓存身份（契约 C2）与结构相等语义不受影响。
 */
class RuntimeCodeCacheBudgetTest {

    // ==================================================================
    // 1. StringPool：运行期去重表上界
    // ==================================================================

    @Test
    fun `StringPool 未武装时整表不重置（装载趟口径不变）`() {
        val pool = StringPool()
        repeat(5000) { pool.intern("串 $it") }
        assertEquals(0, pool.runtimeDistinctResets, "未武装实例绝不重置")
        assertEquals(5000, pool.distinct, "去重表内就是 5000 条不同内容")
    }

    @Test
    fun `StringPool 武装后越界触发重置且内容语义逐字不变`() {
        val pool = StringPool()
        pool.armRuntimeDistinctBudget(8)
        val first = pool.intern("第一代")
        repeat(64) { pool.intern("重置压力 $it") }
        assertTrue(pool.runtimeDistinctResets >= 1, "越过预算必须触发整表重置")
        // 跨代读取：内容相等语义不变（实例可能换代，内容逐字相同）
        assertEquals(first, pool.intern("第一代"))
        // 同代去重：同内容同实例（归一收益保留）
        val a = pool.intern("同代去重")
        val b = pool.intern("同代去重")
        assertSame(a, b, "同代内同内容必须拿到同一代表实例")
    }

    // ==================================================================
    // 2. ArgInterner：运行期规范化代表表上界
    // ==================================================================

    @Test
    fun `ArgInterner 未武装时规范表不重置（装载趟口径不变）`() {
        val interner = ArgInterner()
        repeat(5000) { i ->
            interner.run(listOf(seedOf(i)))
        }
        assertEquals(0, interner.runtimeFormResets, "未武装实例绝不重置")
    }

    @Test
    fun `ArgInterner 武装后越界触发重置且结构合并照常`() {
        val interner = ArgInterner()
        interner.armRuntimeBudget(8)
        repeat(64) { i ->
            interner.run(listOf(seedOf(i)))
        }
        assertTrue(interner.runtimeFormResets >= 1, "越过预算必须触发规范表整表重置")
        // 重置后的同代内合并：两条结构相等的语句，其参数结点折到同一代表实例
        val s1 = seedOf(7777)
        val s2 = seedOf(7777)
        interner.run(listOf(s1, s2))
        val arg1 = s1.args as ExpressionArgument
        val arg2 = s2.args as ExpressionArgument
        assertSame(arg1, arg2, "重置后的同代内结构相等必须折到同一代表")
    }

    /** 造一条参数为唯一 StrLiteral 的种子形态语句（`internNode` 会把结点登记进规范表）。 */
    private fun seedOf(i: Int): Statement = InstructionStmt(
        FunctionCode.WAIT,
        ExpressionArgument(StrLiteral("第 $i 形态", i, 0)),
        i,
        "WAIT #$i",
    )

    // ==================================================================
    // 3. Program 级：重置只丢缓存、不丢语义（重编码逐字节红线 + 语句身份稳定）
    // ==================================================================

    @Test
    fun `Program 运行期重置后语句身份稳定、表达式结构相等、再编码逐字节一致`(@TempDir dir: Path) {
        val program = BudgetFixture.sampleProgram(statements = 400)
        val f = dir.resolve("budget-reset.erbc")
        Files.write(f, ErbcWriter.encode(program, ErbcEncodeOptions(version = ErbcFormat.VERSION_2)))
        val loaded = ProgramLoader.loadMapped(f)
        val original = Files.readAllBytes(f)

        // 装载收尾已由 loader 武装生产常量；压小预算让「触碰少量语句」就能越过重置线。
        loaded.armRuntimeCodeCacheBudgets(nodeEntries = 4, internForms = 4, internStrings = 4)

        // 第一代：物化一批语句 + 读一批池根，形成缓存代
        val firstTouch = (0 until 200).map { loaded.statement(it) }
        val exprBefore = loaded.expr(0)

        // 第二代：再触碰新语句（预算 4 早被越过，缓存代已翻新数次）
        val laterTouch = (200 until 400).map { loaded.statement(it) }

        // 红线一：语句缓存身份不受节点表重置影响（契约 C2 的数据节键）
        assertSame(loaded.statement(0), firstTouch[0], "语句缓存不重置：同下标恒同一实例")
        assertSame(loaded.statement(300), laterTouch[100], "语句缓存不重置：后段同下标恒同一实例")
        // 红线二：跨代读取的结构语义 —— 表达式池根重放解出的是 data class 相等的结点
        assertEquals(exprBefore, loaded.expr(0), "池根跨代重放必须 data class 相等")
        // 红线三：整池再编码逐字节回到原容器（结构与内容全等，重置不落字）
        assertArrayEquals(
            original,
            ErbcWriter.encode(loaded, ErbcEncodeOptions(version = ErbcFormat.VERSION_2)),
            "运行期重置后整池再编码必须逐字节一致",
        )
    }

    @Test
    fun `loader 缺省武装后触碰语句不触发重置（样本远小于生产预算）`(@TempDir dir: Path) {
        val f = dir.resolve("default-budget.erbc")
        Files.write(
            f,
            ErbcWriter.encode(
                BudgetFixture.sampleProgram(statements = 60),
                ErbcEncodeOptions(version = ErbcFormat.VERSION_2),
            ),
        )
        val loaded = ProgramLoader.loadMapped(f)
        repeat(60) { loaded.statement(it) }
        loaded.expr(0)
        // 走到这没抛任何异常、再编码逐字节一致即口径成立（生产预算下样本永远到不了重置线）。
        val reencoded = ErbcWriter.encode(loaded, ErbcEncodeOptions(version = ErbcFormat.VERSION_2))
        assertArrayEquals(Files.readAllBytes(f), reencoded, "缺省武装下小样本再编码必须逐字节一致")
    }

    private object BudgetFixture {
        /** 与 LazyStatementDecodeTest.sampleProgram 同形的覆盖面样本（PRINT/WAIT/共享结点）。 */
        fun sampleProgram(statements: Int): Program {
            val b = CodeBuilder()
            b.declareFunction("main", 0, 0, 4)
            b.declareFunction("callee", 1, 2, 6)
            val shared = Binary(
                OperatorCode.PLUS,
                IntLiteral(7L, 3, 1),
                VarRef("A", emptyList(), isNoArg = false, operandType = OperandType.INT),
                OperandType.INT,
                3,
                1,
            )
            val poolRoot = b.expr(shared)
            val stmts = ArrayList<Int>(statements)
            for (i in 0 until statements) {
                val line = i % 97 + 1
                val s = if (i % 2 == 0) {
                    PrintStmt(
                        PrintSpec(
                            code = FunctionCode.PRINTL,
                            name = "PRINTL",
                            argType = ArgType.INT_EXPRESSION,
                            isLineEnd = true,
                            flag = 0,
                        ),
                        ExpressionArgument(StrLiteral("第 $i 行", line, 0)),
                        line,
                        "PRINTL 第 $i 行",
                    )
                } else {
                    InstructionStmt(
                        FunctionCode.WAIT,
                        VoidArgument(),
                        line,
                        "WAIT  #$i",
                    )
                }
                stmts.add(b.statement(s))
            }
            b.emit(Opcode._EVAL_INT, poolRoot, 0, SlotAddr.NONE, 3)
            b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE, 4)
            for (k in stmts.indices) {
                b.emit(Opcode.WAIT, stmts[k], 0, SlotAddr.NONE, k % 97 + 1)
            }
            b.emit(Opcode._HALT, 0, 0, 0, -1)
            return b.build(0)
        }
    }
}
