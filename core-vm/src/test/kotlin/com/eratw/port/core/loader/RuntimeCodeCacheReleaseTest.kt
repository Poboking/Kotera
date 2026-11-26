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
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.perf.HeapMeter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 堆压回收运行期代码缓存（[Program.releaseRuntimeCodeCaches]）的定向单测。
 *
 * 背景一句话：上界只保证**有界**，不保证**够小**。2026-11-26 真机报告（512 MiB 堆、崩溃时
 * used 461 MiB）账面点名只 149 MiB，桌面 512m 浸泡的类直方图把差额指到解码结点簇 ——
 * 那是「丢了重解即可」的缓存。本类钉四件事：
 * 1. **还的是缓存槽，不是语义**：回收后同一下标重解出逐字段相同的新实例，整池再编码逐字节一致；
 * 2. **钉住表所在块不还**：CALLSHARP 候选 / 数据节的身份契约（契约 C2）跨回收成立；
 * 3. **读数如实**：还了多少块就报多少块，第二次调为空操作（幂等）；
 * 4. **非惰性档零行为**：编译期产物（`CodeBuilder`）上没有惰性池，回收是 no-op。
 */
class RuntimeCodeCacheReleaseTest {

    @Test
    fun `非惰性 Program 上回收是空操作（编译期产物没有惰性缓存）`() {
        val built = sampleProgram(statements = 40)
        val release = built.releaseRuntimeCodeCaches()
        assertTrue(release.isEmpty, "非惰性档没有可还的块")
        assertEquals("语句块 0 / 条目 0 / 节点块 0", release.line)
    }

    @Test
    fun `堆压回收归还语句块与节点块，第二次为空操作`(@TempDir dir: Path) {
        val loaded = load(dir, statements = 600)
        repeat(600) { loaded.statement(it) }
        loaded.expr(0)
        val before = loaded.codeCacheCensus
        assertTrue(before != null, "惰性档必须有代缓存读数")
        assertTrue(before!!.stmtBlocks > 0, "物化 600 条后条目缓存应有块")
        assertTrue(before.nodeBlocks > 0, "节点表应有块")

        val release = loaded.releaseRuntimeCodeCaches()
        assertFalse(release.isEmpty, "有缓存块时必须真的还")
        assertTrue(release.statementBlocks > 0, "语句条目缓存整块还")
        assertTrue(release.nodeBlocks > 0, "节点表整块还")
        val after = loaded.codeCacheCensus!!
        assertEquals(0, after.stmtBlocks, "还完条目缓存块数归零")
        assertEquals(0, after.nodeBlocks, "还完节点表块数归零")

        val again = loaded.releaseRuntimeCodeCaches()
        assertTrue(again.isEmpty, "没有可还的块时幂等返回空读数")
    }

    @Test
    fun `回收只丢缓存不丢语义：重解逐字段相同、整池再编码逐字节一致`(@TempDir dir: Path) {
        val loaded = load(dir, statements = 400)
        val original = Files.readAllBytes(dir.resolve("release.erbc"))
        val first = (0 until 400).map { loaded.statement(it) }
        val exprBefore = loaded.expr(0)

        val release = loaded.releaseRuntimeCodeCaches()
        assertFalse(release.isEmpty)

        // 结构语义：同一句重解出的实例逐字段相同（data class 相等），池根同理
        // 注：Statement 实现是普通类（等值即同一实例），所以逐条比**形状**（类名 + toString 含函数码/行号），
        // 逐字段红线交给下面的整池再编码逐字节比对。
        assertEquals(
            first.map { it.javaClass.name + "|" + it },
            (0 until 400).map { loaded.statement(it).javaClass.name + "|" + loaded.statement(it) },
            "重解出的语句形状必须逐条相同",
        )
        assertEquals(exprBefore, loaded.expr(0), "重解出的池根必须 data class 相等")
        // 内容红线：整池再编码逐字节回到原容器（回收不落字）
        assertArrayEquals(
            original,
            ErbcWriter.encode(loaded, ErbcEncodeOptions(version = ErbcFormat.VERSION_2)),
            "回收后整池再编码必须逐字节一致",
        )
    }

    @Test
    fun `钉住表所在块不还：回收后钉住下标仍是同一实例`(@TempDir dir: Path) {
        val loaded = load(dir, statements = 400)
        val pinned = (0 until 400).map { loaded.statement(it) }
        loaded.pinStatementIdentities(intArrayOf(3, 200))
        assertEquals(2, loaded.codeCacheCensus!!.stmtPins, "两条钉住下标都已物化并转移实例")

        val release = loaded.releaseRuntimeCodeCaches()
        assertFalse(release.isEmpty, "其余块仍应被还")
        assertSame(pinned[3], loaded.statement(3), "钉住下标必须跨回收恒同一实例")
        assertSame(pinned[200], loaded.statement(200), "钉住下标必须跨回收恒同一实例")
        assertTrue(release.statementEntries > 0, "含钉住的块按槽清：非钉住条目照样归还")
        assertNotSame(pinned[4], loaded.statement(4), "同块非钉住条目已清槽，重解得新实例")
        assertEquals(2, loaded.codeCacheCensus!!.stmtPins, "钉住表本身不因回收变化")
    }

    /**
     * 按槽清空的块若已无任何条目，背衬也要一并弃 —— 否则 census 的 stmtBlocks 仍把空壳读成
     * 「缓存还在」，堆压读数与实况脱节。钉住在物化之前下达（装载期 sidecar 的真实次序），
     * 于是块里只剩非钉住条目，清完即为空背衬。
     */
    @Test
    fun `按槽清空的块不留空背衬：回收后 census 的语句块归零`(@TempDir dir: Path) {
        val loaded = load(dir, statements = 400)
        loaded.pinStatementIdentities(intArrayOf(3))
        loaded.statement(7)
        assertTrue(loaded.codeCacheCensus!!.stmtBlocks > 0, "物化过 ⇒ 块表有常驻")

        val release = loaded.releaseRuntimeCodeCaches()
        assertTrue(release.statementEntries > 0, "非钉住条目照样按槽归还")
        assertEquals(0, loaded.codeCacheCensus!!.stmtBlocks, "块已清空 ⇒ 整块弃，不再读成缓存还在")
        val again = loaded.statement(3)
        assertSame(again, loaded.statement(3), "钉住下标重解后进缓存，第二次读数同实例")
        assertEquals(1, loaded.codeCacheCensus!!.stmtPins, "钉住表本身不因回收变化")
    }

    @Test
    fun `钉住区间查询按块边界判定`() {
        val pins = StatementPins(limit = 100)
        pins.pinStatementIdentities(intArrayOf(5, 60, 61))
        assertTrue(pins.hasPinnedIn(0, 100), "整段命中")
        assertFalse(pins.hasPinnedIn(0, 5), "上界开区间：5 不在 [0,5)")
        assertTrue(pins.hasPinnedIn(5, 10), "左闭：5 在 [5,10)")
        assertFalse(pins.hasPinnedIn(6, 60), "60 不在 [6,60)")
        assertTrue(pins.hasPinnedIn(55, 62), "夹住 60/61")
        assertFalse(pins.hasPinnedIn(62, 100), "两侧之外无钉住")
        assertFalse(pins.hasPinnedIn(10, 10), "空区间恒假")
    }

    /**
     * 真产物实测：跨池铺到与真机同量级的缓存占用，量「堆压回收」实际还回多少常驻。
     *
     * 环境门 `ERATW_ERBC_DIR`（含 program.erbc 的目录）；缺变量跳过，不干扰 CI 主读数。
     * 断言只钉「必须还出节点块」与「回收不抬高常驻」，具体 MiB 打日志留证（机器负载会抖）。
     */
    @Test
    fun `真产物实测：跨池铺满后回收报常驻腾出量（env 未设则跳过）`() {
        val dir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("program.erbc")), "无 ERATW_ERBC_DIR 产物：跳过")
        val loaded = ProgramLoader.loadMapped(dir!!.resolve("program.erbc"), flatStatements = false)
        val total = loaded.statementCount
        val samples = 60_000
        for (k in 0 until samples) {
            loaded.statement((k.toLong() * total / samples).toInt())
        }
        val before = HeapMeter.settle().first
        val release = loaded.releaseRuntimeCodeCaches()
        val after = HeapMeter.settle().first
        val msg = "[release] total=" + total + " sampled=" + samples +
            " 语句块=" + release.statementBlocks + " 节点块=" + release.nodeBlocks +
            " 常驻 " + (before shr 20) + "MiB -> " + (after shr 20) + "MiB"
        println(msg)
        assertTrue(release.nodeBlocks > 0, "真产物上回收必须还出节点块")
        assertTrue(after <= before, "回收不该把常驻抬高")
    }

    /** 编码落盘并走映射装载（惰性档；装载收尾由 loader 按生产常量武装）。 */
    private fun load(dir: Path, statements: Int): Program {
        val f = dir.resolve("release.erbc")
        Files.write(
            f,
            ErbcWriter.encode(sampleProgram(statements), ErbcEncodeOptions(version = ErbcFormat.VERSION_2)),
        )
        return ProgramLoader.loadMapped(f)
    }

    /** 与 RuntimeCodeCacheBudgetTest.BudgetFixture 同形的覆盖面样本（PRINT/WAIT/共享结点）。 */
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
