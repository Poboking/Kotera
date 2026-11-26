package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 惰性物化失败在**运行期**收敛成 [CodeEE] 红字口径（stab-b 修复批 2026-11-18，报告条目 2）。
 *
 * ## 判词与锚
 *
 * 移植自有面（ADR-005 判词，组长裁定）：erbc 是移植自研格式，原版无惰性物化对应面
 * （原版 ERB 装载期一次性解析，坏脚本 = 装载期红字/拒载，顶层 `catch (Exception)`
 * Process.cs:462-476 红字继续）—— 原版无锚，判据自立。裁定内容：运行期物化失败转
 * CodeEE（损坏原因 + 位置，玩家可见），该语句失败、会话继续；禁止吞异常、禁止静默；
 * 同节点首个失败即收敛（后续请求重抛同一实例，不重复解码新建错误对象）。
 *
 * ## 门（RuntimeMaterializationGate）
 *
 * 「运行期」的判定 = `Program.releaseStatementRaws()`（ProcessRuntime 构造期调用的同一
 * 公开入口，本测试直接调它翻位）。门关（装载趟 / 诊断快照 / 变异差分对拍）一切异常形状
 * **逐字保留** —— ErbcStreamEquivalenceTest / LazyStatementDecodeTest 的「异常类型与消息
 * 逐字相同」对拍靠这条不变量；本文件门关侧的每条断言同时是那两个差分测试的独立反向
 * 证明（把门改成无条件收敛，门关断言当场转红）。
 *
 * ## 损坏注入方式
 *
 * 只动**字符串内容**字节（表内偏移与记录结构不动）：装载趟的偏移/边界校验不读串内容
 * （LazyStatementDecodeTest KDoc：「运算符/变量名的枚举译码与 String 内容解码延到物化」），
 * 所以坏串能穿过装载期、在首次物化时才炸 —— 正是报告条目 2 的触发链（「装载期头校验与
 * CRC 通过、坏记录延迟到首次执行才解」）。
 */
class ErbcRuntimeMaterializationTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 覆盖面样本：双池根（int / str）/ 具名形参 / 调用点实参；名字取夹具独有串便于定点损坏。 */
    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("STABFNMAIN", 0, 0, 4)
        b.declareFunction("STABFNCALLEE", 1, 2, 6, paramNames = listOf("STABLOCAL", "STABREF"))
        val poolRoot = b.expr(
            com.eratw.port.core.expr.Binary(
                OperatorCode.PLUS,
                IntLiteral(7L, 3, 1),
                VarRef("A", emptyList(), isNoArg = false, operandType = OperandType.INT),
                OperandType.INT,
                3,
                1,
            ),
        )
        val strRoot = b.expr(StrLiteral("STABDAMAGE", 5, 0))
        b.emit(Opcode._EVAL_INT, poolRoot, 0, SlotAddr.NONE, 3)
        b.emit(Opcode._EVAL_STR, strRoot, 0, SlotAddr.NONE, 3)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE, 4)
        b.declareCallArgs(
            1,
            listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "A", present = true)),
        )
        b.emit(
            Opcode.WAIT,
            b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 1, "WAIT")),
            0,
            SlotAddr.NONE,
            1,
        )
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(name: String): Path {
        val f = dir.resolve(name)
        Files.write(f, ErbcWriter.encode(sampleProgram(), ErbcEncodeOptions(version = ErbcFormat.VERSION_2)))
        return f
    }

    /** 在 [section] 段内把 [needle] 串的内容字节改出一个非法 UTF-8 续字节（偏移表不动）。 */
    private fun corruptStringIn(clean: ByteArray, section: Int, needle: String): ByteArray {
        val hdr = ProgramLoader.probe(clean, null)
        val ends = ProgramLoader.sectionEnds(hdr, clean.size)
        val from = hdr.offsets[section]
        val span = clean.copyOfRange(from, ends[section])
        val bytes = needle.toByteArray(Charsets.UTF_8)
        var at = indexOf(span, bytes)
        assertNotNull(at, "夹具自检：串 $needle 应落在段 $section 内（找得到才谈得上定点损坏）")
        val out = clean.copyOf()
        out[from + at + 1] = 0x80.toByte() // 独立 0x80 = 无主续字节，严格 UTF-8 解码必炸
        return out
    }

    private fun indexOf(span: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in 0..span.size - needle.size) {
            for (j in needle.indices) {
                if (span[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun damagedFile(name: String, section: Int, needle: String): Path {
        val f = dir.resolve(name)
        val clean = ErbcWriter.encode(sampleProgram(), ErbcEncodeOptions(version = ErbcFormat.VERSION_2))
        Files.write(f, corruptStringIn(clean, section, needle))
        return f
    }

    // ------------------------------------------------------------------
    // 语句池：解码损坏的运行期收敛
    // ------------------------------------------------------------------
    //
    // 无侧车时，语句记录的**索引趟**逐条整文法回放（buildRecordOffsets → readStatement），
    // 串内容在装载期就解 ⇒ 内容损坏属装载期失败，到不了运行期。要复刻报告条目 2 的
    // 触发链（「装载期头校验与 CRC 通过、坏记录延迟到首次执行才解」）必须走**侧车档**：
    // 偏移表免掉整趟回放，CRC 只盖记录区 ⇒ 串表（记录区之前）损坏照样配对成功，
    // 延迟到首次物化才炸 —— 这正是生产（erbcc 带侧车）的真实形状。
    //
    // 夹具自举：侧车要求 recordBytes = 记录区真实长度。16 条 WAIT 语句记录布局全同
    // （`u8 tag + i32 line + u32 rawIdx + u32 codeNameIdx + args`，ErbcWriter.stmtHead）
    // ⇒ 偏移表 = i×L，只剩 L 一个未知数；用「配对校验放行」当 oracle（写侧 CRC 自洽，
    // 唯有真 L 能过 recordBytes 对账 + 抽查回放的边界对齐）从 1 起逐个校准。
    // 抽查点 = 首两条/末两条/每 1/8 条带起点（recordSampleIndices）：16 条时
    // {0,1,2,4,6,8,10,12,14,15}，**第 3 条不在抽查里** ⇒ 坏串只挂第 3 条的 raw，
    // 配对照样放行，损坏延迟到 statementAt(3) 才炸。

    private val stmtCount = 16

    private fun multiStatementProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("STABFNMAIN", 0, 0, 4)
        val stmts = ArrayList<Int>(stmtCount)
        for (i in 0 until stmtCount) {
            stmts.add(b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i, "STABRAW%02d".format(i))))
        }
        for (k in stmts.indices) b.emit(Opcode.WAIT, stmts[k], 0, SlotAddr.NONE, k)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /** 配准记录长度 L：唯一能让侧车配对放行（无「不可信」分段标记）的候选值。 */
    private fun calibrateRecordLength(clean: ByteArray, work: Path): Int {
        for (l in 1..64) {
            val cand = work.resolve("cand$l")
            Files.createDirectories(cand)
            val program = cand.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME)
            Files.write(program, clean)
            writeSidecar(program, l)
            val stages = ArrayList<String>()
            ProgramLoader.loadMapped(program) { stage -> stages += stage }
            if (stages.none { it.contains("不可信") }) return l
        }
        error("夹具自检：1..64 内校准不出记录长度（语句记录布局与 1×L 假设不符）")
    }

    private fun writeSidecar(program: Path, recordLength: Int) {
        StmtOffsetSidecar.write(
            StmtOffsetSidecar.pathFor(program),
            IntArray(stmtCount) { it * recordLength },
            stmtCount * recordLength,
        )
    }

    /** 侧车档 + 「记录区外」坏串：CRC 仍配对（CRC 只盖记录区），损坏延迟到首物化。 */
    private fun damagedSidecarFile(name: String): Path {
        val clean = ErbcWriter.encode(multiStatementProgram(), ErbcEncodeOptions(version = ErbcFormat.VERSION_2))
        val l = calibrateRecordLength(clean, dir)
        val work = dir.resolve(name)
        Files.createDirectories(work)
        val program = work.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME)
        // 坏串 = 第 3 条语句的 raw（串表在记录区之前；记录区 CRC 不受影响）。
        val hdr = ProgramLoader.probe(clean, null)
        val ends = ProgramLoader.sectionEnds(hdr, clean.size)
        val from = hdr.offsets[ErbcFormat.SECTION_STATEMENTS]
        val needle = "STABRAW03".toByteArray(Charsets.UTF_8)
        val span = clean.copyOfRange(from, ends[ErbcFormat.SECTION_STATEMENTS])
        val at = indexOf(span, needle)
        assertNotNull(at, "夹具自检：坏串应落在段 3 内")
        val corrupted = clean.copyOf()
        corrupted[from + at + 1] = 0x80.toByte() // 独立 0x80 = 无主续字节，严格 UTF-8 解码必炸
        Files.write(program, corrupted)
        writeSidecar(program, l)
        return program
    }

    /** 判据①转换 + 带位置（文案含下标与产物标识、结构化 line 取记录头）+ 根因进因果链。 */
    @Test
    fun `门开——损坏串在语句首物化时收敛为CodeEE`() {
        val f = damagedSidecarFile("stmt-damage")
        val p = ProgramLoader.loadMapped(f) // 装载绿：CRC 只盖记录区，坏串延迟到首物化
        p.releaseStatementRaws() // 运行期交接（= ProcessRuntime 构造期的同一公开调用）
        val e = assertFailsWith<CodeEE> { p.statement(3) }
        assertTrue("产物损坏：语句 3 物化失败" in e.message!!, "实为：${e.message}")
        assertTrue("program.erbc" in e.message!!, "文案带产物标识：${e.message}")
        assertEquals(3, e.line, "line = 记录头的 0 基源行（夹具第 3 条 line=3）")
        val cause = assertNotNull(e.cause, "根因必须可诊断（不吞）")
        assertTrue(cause is ErbcFormatException, "根因 = 装载层解码异常，实为 ${cause.javaClass.name}")
    }

    /** 判据③同节点不重复抛：首个失败即收敛，二次请求重抛同一实例（非静默：仍上抛）。 */
    @Test
    fun `同节点二次请求重抛同一收敛实例`() {
        val f = damagedSidecarFile("stmt-damage-memo")
        val p = ProgramLoader.loadMapped(f)
        p.releaseStatementRaws()
        val first = assertFailsWith<CodeEE> { p.statement(3) }
        val second = assertFailsWith<CodeEE> { p.statement(3) }
        assertSame(first, second, "同节点不重复抛（不重新解码、不新建错误对象）；非静默（仍上抛受控错误）")
    }

    /** 反向证明（门关侧）：同一损坏档不做运行期交接时保持装载层异常形状 —— 若把门改成无条件收敛，本条转红。 */
    @Test
    fun `门关——同一损坏档保持装载层异常形状`() {
        val f = damagedSidecarFile("stmt-damage-gateoff")
        val p = ProgramLoader.loadMapped(f) // 不 releaseStatementRaws
        val e = assertFailsWith<ErbcFormatException> { p.statement(3) }
        assertTrue(e !is CodeEE, "门关不得收敛（否则 ErbcStreamEquivalenceTest 的对拍会红）")
    }

    // ------------------------------------------------------------------
    // 表达式池：解码损坏的运行期收敛
    // ------------------------------------------------------------------

    @Test
    fun `门开——损坏串在表达式首物化时收敛为CodeEE且同节点收敛`() {
        val f = damagedFile("expr-damage.erbc", ErbcFormat.SECTION_EXPRESSIONS, "STABDAMAGE")
        val p = ProgramLoader.loadMapped(f)
        p.releaseStatementRaws()
        var damaged = -1
        var first: CodeEE? = null
        for (k in 0 until p.exprCount) {
            try {
                p.expr(k)
            } catch (e: CodeEE) {
                damaged = k
                first = e
                break
            }
        }
        val e = assertNotNull(first, "损坏的池根必须在首次物化时收敛成 CodeEE")
        assertTrue("产物损坏：表达式 $damaged 物化失败" in e.message!!, "实为：${e.message}")
        assertTrue(f.fileName.toString() in e.message!!, "文案带产物标识：${e.message}")
        val second = assertFailsWith<CodeEE> { p.expr(damaged) }
        assertSame(e, second, "同节点不重复抛")
    }

    // ------------------------------------------------------------------
    // 池层越界分支（契约点 :2102 / node OOB / :1363 / :1303）的门控形状
    // ------------------------------------------------------------------

    /** 门开：四个物化面的越界分支收敛成 CodeEE（VM 判界先行，这里兜绕过 VM 的直取面）。 */
    @Test
    fun `门开——池层越界收敛为CodeEE`() {
        val p = ProgramLoader.loadMapped(writeArtifact("clean-gateon.erbc"))
        p.releaseStatementRaws()
        val s = assertFailsWith<CodeEE> { p.statement(-1) }
        assertTrue("语句池下标越界: -1" in s.message!!, s.message)
        val x = assertFailsWith<CodeEE> { p.expr(-1) }
        assertTrue("表达式池下标越界: -1" in x.message!!, x.message)
        val fn = assertFailsWith<CodeEE> { p.functions[-1] }
        assertTrue("函数下标越界: -1" in fn.message!!, fn.message)
        val pn = assertFailsWith<CodeEE> { p.paramNames(1).get(-1) }
        assertTrue("形参名下标越界: -1" in pn.message!!, pn.message)
    }

    /** 门关：越界分支保持原形状（IndexOutOfBoundsException / ErbcDecodeException）—— 反向证明。 */
    @Test
    fun `门关——池层越界保持原形状`() {
        val p = ProgramLoader.loadMapped(writeArtifact("clean-gateoff.erbc"))
        assertFailsWith<IndexOutOfBoundsException> { p.statement(-1) }
        assertFailsWith<ErbcDecodeException> { p.expr(-1) }
        assertFailsWith<IndexOutOfBoundsException> { p.functions[-1] }
        assertFailsWith<IndexOutOfBoundsException> { p.paramNames(1).get(-1) }
    }
}
