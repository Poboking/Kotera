package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 惰性语句池条目缓存的**有界性**与身份契约（`LazyStatementRecords` 的块级 LRU + 钉住表）。
 *
 * ## 为什么边界断言落在这一层
 *
 * `cached` 是「物化过的语句实例」的唯一权威存放处：装载期只建记录偏移表，运行期第一次取某条
 * 语句才解码并写回缓存。改造前它只进不出（池生命周期内无上界），于是「玩到过多少代码」直接
 * 等于堆占用 —— 全量语料语句池 1,712,420 条 × 277 B ≈ 474 MB，在 512 MiB 挡位上是一颗迟早
 * 要炸的雷（取值口径与算式见 `RUNTIME_STMT_ENTRY_BUDGET` 的 KDoc）。
 *
 * ## 身份契约
 *
 * 语句实例的身份在三个地方被当真：`Vm.statementOf` 的 64 槽直接映射备忘、
 * `GameSession` 的数据节 `IdentityHashMap<Statement, Int>`、`sharpStatementIndices()` 的
 * CALLSHARP 候选。逐出块会让被覆盖的下标重新解码成**新实例**，所以：
 *
 * 1. 每个被逐出块所在的批次都会推进 `statementCacheEpoch`，`Vm` 据此整张作废备忘；
 * 2. `pinStatementIdentities` 钉住的条目（= 上面后两个持有者的下标并集）永不进
 *    `cached`、永不逐出，身份恒定；
 * 3. 未钉住的条目本来就不保证跨逐出同一实例 —— 逐出后重解出的语句逐字段相同即可，
 *    这正是「VM 帧里存 pc 下标、取指时按下标重取」的前提（本文件 `assertSameStatement`
 *    把那一条钉成断言）。
 */
class StatementCacheBoundTest {
    // ==================================================================
    // (a) 未超预算：同下标恒同一实例（既有契约不许回退）
    // ==================================================================

    @Test
    fun `未超预算时同一下标反复取仍是同一实例且零逐出`(@TempDir dir: Path) {
        val program = open(dir, statements = 5_000, stmtEntries = 16_384)
        try {
            val first = program.statement(7)
            repeat(4) {
                assertSame(first, program.statement(7), "未超预算：同一下标必须恒同一实例")
            }
            val census = program.codeCacheCensus
            assertEquals(0, census?.stmtEvictions, "未超预算不许逐出（实际 census=$census）")
            assertEquals(1, census?.stmtBlocks, "5,000 条只落在第 0 块（实际 census=$census）")
        } finally {
            program.releaseMmap()
        }
    }

    // ==================================================================
    // (b) 钉住集：跨逐出必须仍是同一实例
    // ==================================================================

    @Test
    fun `钉住的下标跨超预算逐出仍解出同一实例`(@TempDir dir: Path) {
        // 3 块（12,288 条）× 4,096；预算 = 2 块 ⇒ 第 3 块一填就必然逐出最冷块。
        val program = open(dir, statements = 12_288, stmtEntries = 8_192)
        try {
            val pinnedIndex = 100
            val pinnedFirst = program.statement(pinnedIndex)
            program.pinStatementIdentities(intArrayOf(pinnedIndex))
            assertEquals(1, program.codeCacheCensus?.stmtPins, "钉住一条之后读数应为 1")

            touchBlock1(program)
            touchBlock2(program)
            val census = program.codeCacheCensus
            assertTrue(
                (census?.stmtEvictions ?: 0) > 0,
                "填到第 3 块必然超预算逐出（实际 census=$census）",
            )
            assertSame(
                pinnedFirst,
                program.statement(pinnedIndex),
                "钉住的下标跨逐出必须仍解出同一实例（身份契约的例外面）",
            )
        } finally {
            program.releaseMmap()
        }
    }

    // ==================================================================
    // (c) 上界：块数被压住、逐出计数增长、重解逐字段相同
    // ==================================================================

    @Test
    fun `超预算时块数被压住且逐出后重解出的语句逐字段相同`(@TempDir dir: Path) {
        val program = open(dir, statements = 12_288, stmtEntries = 8_192)
        try {
            val coldIndex = 5
            val cold = program.statement(coldIndex)
            val censusBefore = program.codeCacheCensus
            assertEquals(1, censusBefore?.stmtBlocks, "取一条只分配第 0 块（实际 census=$censusBefore）")

            touchBlock1(program)
            touchBlock2(program)

            val census = program.codeCacheCensus
            assertEquals(2, census?.stmtBlocks, "预算 2 块 ⇒ 条目缓存不许超过 2 块（实际 census=$census）")
            assertTrue((census?.stmtEvictions ?: 0) > 0, "超预算必须真逐出（实际 census=$census）")

            val again = program.statement(coldIndex)
            assertNotSame(cold, again, "被逐出块里的下标重新物化应得到新实例（旧的已不在缓存里）")
            assertSameStatement(cold, again)
        } finally {
            program.releaseMmap()
        }
    }

    @Test
    fun `未钉住的下标随块逐出而失去实例身份（上界真的生效）`(@TempDir dir: Path) {
        val program = open(dir, statements = 12_288, stmtEntries = 8_192)
        try {
            // 先摸第 0 块（戳最早 ⇒ 后面必然是最冷块），再摸并钉住第 1 块里的 1 条。
            val coldFirst = program.statement(0)
            val pinnedIndex = 4_096 + 3
            val pinnedFirst = program.statement(pinnedIndex)
            program.pinStatementIdentities(intArrayOf(pinnedIndex))

            touchBlock2(program)
            assertTrue(
                (program.codeCacheCensus?.stmtEvictions ?: 0) > 0,
                "第 2 块填进来就超预算，必须逐出第 0 块（实际 census=${program.codeCacheCensus}）",
            )

            val coldAgain = program.statement(0)
            assertNotSame(coldFirst, coldAgain, "未钉住的下标被逐出后不许再返回旧实例（否则上界无效）")
            assertSameStatement(coldFirst, coldAgain)
            assertSame(pinnedFirst, program.statement(pinnedIndex), "钉住的那条不受影响")
        } finally {
            program.releaseMmap()
        }
    }

    // ==================================================================
    // (d) 逐出纪元：上层（`Vm.statementOf` 的 64 槽备忘）据此作废实例缓存
    // ==================================================================

    @Test
    fun `逐出会推进语句缓存纪元而命中路径不推进`(@TempDir dir: Path) {
        val program = open(dir, statements = 12_288, stmtEntries = 8_192)
        try {
            val epochAtStart = program.statementCacheEpoch
            program.statement(0)
            assertSame(program.statement(0), program.statement(0), "命中路径不许换实例")
            assertEquals(epochAtStart, program.statementCacheEpoch, "只命中、不逐出 ⇒ 纪元不许动")

            touchBlock1(program)
            touchBlock2(program)
            assertTrue(
                program.statementCacheEpoch > epochAtStart,
                "逐出过块之后纪元必须推进（初值 $epochAtStart，实际 ${program.statementCacheEpoch}）",
            )
        } finally {
            program.releaseMmap()
        }
    }

    // ==================================================================
    // 装置
    // ==================================================================

    /** 摸进第 1 块（下标 4,096）：块级 LRU 只看块，填 1 条就把 `liveBlocks` 加一。 */
    private fun touchBlock1(program: Program) {
        program.statement(4_096)
    }

    /** 摸进第 2 块（下标 8,192）⇒ 块数 3 > 预算 2 ⇒ 逐出最冷块。 */
    private fun touchBlock2(program: Program) {
        program.statement(8_192)
    }

    /**
     * 逐字段比对两条语句（[Statement] 不是 data class，没有结构相等）。
     *
     * 断言的是「逐出后重解码解出的条目与逐出前那条逐项相同」：语句类（= 记录 tag）、代码名、
     * 行号、原文、打印规格与实参记录里的表达式逐项相等 —— 这正是「运行中不靠实例身份也能
     * 继续跑」的依据（VM 帧里存的是 pc 下标，不是语句引用；取指走 [Program.statement] 重新解码）。
     */
    private fun assertSameStatement(
        expected: Statement,
        actual: Statement,
    ) {
        assertEquals(expected.javaClass, actual.javaClass, "语句类必须一致（= 记录 tag 一致）")
        assertEquals(expected.code, actual.code, "code 名必须一致")
        assertEquals(expected.line, actual.line, "行号必须一致")
        assertEquals(expected.raw, actual.raw, "原文必须一致")
        if (expected is PrintStmt && actual is PrintStmt) {
            assertEquals(expected.print.code, actual.print.code, "打印命令必须一致")
            assertEquals(expected.print.name, actual.print.name, "打印命令名必须一致")
            assertEquals(expected.print.argType, actual.print.argType, "打印实参类型必须一致")
            assertEquals(expected.print.isLineEnd, actual.print.isLineEnd, "是否换行必须一致")
            assertEquals(expected.print.flag, actual.print.flag, "打印标志必须一致")
        }
        val e = expected.args
        val a = actual.args
        assertEquals(e.javaClass, a.javaClass, "参数记录形态必须一致")
        if (e is ExpressionArgument && a is ExpressionArgument) {
            assertSameExpr(e.term, a.term)
        }
    }

    private fun assertSameExpr(
        expected: Expr?,
        actual: Expr?,
    ) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, "表达式空/非空必须一致")
            return
        }
        when (expected) {
            is StrLiteral -> {
                assertTrue(actual is StrLiteral, "结点类必须一致：期望 StrLiteral，实际 ${actual.javaClass.simpleName}")
                assertEquals(expected.value, actual.value, "字符串字面量必须一致")
                assertEquals(expected.line, actual.line, "字面量行号必须一致")
                assertEquals(expected.col, actual.col, "字面量列号必须一致")
            }

            is VarRef -> {
                assertTrue(actual is VarRef, "结点类必须一致：期望 VarRef，实际 ${actual.javaClass.simpleName}")
                assertEquals(expected.name, actual.name, "变量名必须一致")
                assertEquals(expected.operandType, actual.operandType, "操作数类型必须一致")
            }

            else -> assertEquals(expected, actual, "其余结点按结构相等比对")
        }
    }

    /**
     * 落盘一份惰性语句池产物并装载，然后把条目预算注到 [stmtEntries]。
     *
     * 走 `CodeBuilder` + [ErbcWriter] 这条路（而不是手拼字节）：段 3 自带局部串表、段 4 自带
     * 函数名局部串表、段 5 必须非空，手拼一点点错位就会在装载期报「标志位与段表不一致」这类
     * 与本题无关的错，反而掩盖条目缓存本身的行为。
     *
     * `loadMapped` 缺省 `lazyStatements = true`（[com.eratw.port.core.loader.MappedErbc.toProgram]），
     * 所以装载出来的就是 [LazyStatementRecords]；再调一次 [Program.armRuntimeCodeCacheBudgets]
     * 把上界压到块级 LRU 真会逐出的量级。
     */
    private fun open(
        dir: Path,
        statements: Int,
        stmtEntries: Int,
    ): Program {
        val f = dir.resolve("stmt-cache.erbc")
        Files.write(
            f,
            ErbcWriter.encode(
                sampleProgram(statements),
                ErbcEncodeOptions(version = ErbcFormat.VERSION_2),
            ),
        )
        val program = ProgramLoader.loadMapped(f)
        // 预算注小：节点表 / 规范化表 / 串池都给宽裕值，只让语句条目缓存受界 ——
        // 本文件断言的就是这一条上界。
        program.armRuntimeCodeCacheBudgets(
            stmtEntries = stmtEntries,
            nodeEntries = 1 shl 12,
            internForms = 1 shl 14,
            internStrings = 1 shl 14,
        )
        return program
    }

    /**
     * 与 `RuntimeCodeCacheBudgetTest.BudgetFixture.sampleProgram` 同形的语句池样本：
     * 偶数条 PRINTL（带一条字符串实参，覆盖「参数里有表达式子结点」的解锁路径）、
     * 奇数条 WAIT（`VoidArgument`，覆盖「参数不带子结点」的路径）。
     *
     * 每条语句都在代码段里发一条 `WAIT` 指令，保证段 5 非空且语句池条目全部可达。
     */
    private fun sampleProgram(statements: Int): Program {
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
                        argType = ArgType.STR_EXPRESSION,
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
