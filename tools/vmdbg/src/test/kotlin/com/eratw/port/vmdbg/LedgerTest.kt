package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ledger 指纹与 diff（CONTRACT-DBG1 路2 ledger 规格）：
 * - 指纹归一化：数字 → #、空白折叠 —— 同点漂移折同指纹；步数/耗时不进指纹；
 * - diff 四类归属（合成两条 run 文件，断言四类归属与计数）：新增 / 回归 / 修复 / 共有；
 * - 同 key 多条取最后一条（sweep 占位覆写协议）；
 * - BOM 台账容忍（2026-10-25 修）：base 带 EF BB BF 时首条失败不得被吞、不得错报新增。
 */
class LedgerTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-ledger-test")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun writeJsonl(name: String, lines: List<String>): Path {
        val p = tmp.resolve(name)
        Files.write(p, lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
        return p
    }

    /** 构造一条记录行（与 sweep.jsonl 同形状）；[file] 非 null 时按 sweep 的位置追加在行尾。 */
    private fun rec(
        fn: String,
        status: String,
        cls: String = "-",
        line: Int = -1,
        pc: Int = -1,
        msg: String = "",
        file: String? = null,
    ): String {
        val fields = mutableListOf<Pair<String, Any?>>(
            "fn" to fn, "key" to fn, "status" to status, "cls" to cls,
            "line" to line, "pc" to pc, "msg" to msg, "steps" to 1, "elapsedMs" to 1,
        )
        if (file != null) fields.add("file" to file)
        return Jsonl.obj(fields)
    }

    @Test
    fun fingerprintNormalizesDigitsAndWhitespace() {
        val a = Ledger.fingerprint("CodeEE", "FN", 3, 7, "下标 12 越界  (size=10)")
        val b = Ledger.fingerprint("CodeEE", "FN", 3, 7, "下标 34 越界\t(size=99)  ")
        assertEquals(a, b, "数字/空白漂移应折成同指纹")
        // 步数/耗时不进指纹：没有对应参数 —— message 相同即同指纹（结构性保证）
        val c = Ledger.fingerprint("CodeEE", "FN", 3, 8, "下标 12 越界 (size=10)")
        assertNotEquals(a, c, "pc 变了指纹必须变")
        val d = Ledger.fingerprint("CodeEE", "FN2", 3, 7, "下标 12 越界 (size=10)")
        assertNotEquals(a, d, "函数变了指纹必须变")
        assertEquals(64, a.length, "sha256 小写十六进制 64 位")
    }

    @Test
    fun diffClassifiesAddedFixedRegressedAndCommon() {
        // base：FN1 以形状 A 失败；FN2 PASS；FN3 以形状 B 失败
        // head：FN1 PASS（修复）；FN2 以形状 C 失败（新增）；FN3 以形状 D 失败（回归：函数 base 失败过、形状变了）；形状 B 仍在 FN3？——不，FN3 形状 B 消失、形状 D 出现
        // 为凑「共有」，让 FN4 在两侧都以形状 E 失败
        val fpA = Ledger.fingerprint("CodeEE", "FN1", 3, 7, "失败A")
        val fpB = Ledger.fingerprint("CodeEE", "FN3", 5, 9, "失败B")
        val fpC = Ledger.fingerprint("CodeEE", "FN2", 2, 4, "失败C")
        val fpD = Ledger.fingerprint("CodeEE", "FN3", 6, 11, "失败D")
        val fpE = Ledger.fingerprint("CodeEE", "FN4", 1, 1, "失败E")

        val base = writeJsonl(
            "base.jsonl",
            listOf(
                rec("FN1", "CODEEE", "CodeEE", 3, 7, "失败A"),
                rec("FN2", "PASS"),
                rec("FN3", "CODEEE", "CodeEE", 5, 9, "失败B"),
                rec("FN4", "CODEEE", "CodeEE", 1, 1, "失败E"),
            ),
        )
        val head = writeJsonl(
            "head.jsonl",
            listOf(
                rec("FN1", "PASS"),
                rec("FN2", "CODEEE", "CodeEE", 2, 4, "失败C"),
                rec("FN3", "CODEEE", "CodeEE", 6, 11, "失败D"),
                rec("FN4", "CODEEE", "CodeEE", 1, 1, "失败E"),
            ),
        )

        val d = CmdLedger.diff(CmdLedger.loadTerminal(base), CmdLedger.loadTerminal(head))
        // 修复：base 失败、head 无该指纹（FN1 形状 A；FN3 形状 B 也在 head 消失）
        assertEquals(setOf(fpA, fpB), d.fixed.keys, "修复集不符")
        // 新增：head 失败、base 无该指纹、且该函数在 base 没失败过（FN2 在 base PASS）
        assertEquals(setOf(fpC), d.added.keys, "新增集不符")
        // 回归：head 失败、base 无该指纹、但该函数在 base 以别的指纹失败过（FN3 形状 B → 形状 D）
        assertEquals(setOf(fpD), d.regressed.keys, "回归集不符")
        // 共有：两侧都失败（FN4 形状 E）
        assertEquals(setOf(fpE), d.common.keys, "共有集不符")

        // CLI 出口：计数表 + 分节
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val code = Cli.run(
            arrayOf("--work", tmp.toString(), "ledger", "diff", "--base", base.toString(), "--head", head.toString()),
            out, System.err,
        )
        assertEquals(0, code)
        val text = buf.toString(Charsets.UTF_8)
        assertTrue("新增 1 / 回归 1 / 修复 2 / 共有 1" in text, text)
        assertTrue("== 新增 NEW (1) ==" in text, text)
        assertTrue("== 回归 REGRESSED (1) ==" in text, text)
    }

    @Test
    fun fileColumnDoesNotEnterFingerprintOrDiffOutput() {
        // 同一批记录两份：一份带 file 列、一份不带 —— 指纹同值、`ledger diff` 输出逐字节相同。
        // 本项钉的是「file 只是显示列」：把 file 拼进指纹材料（反向证明 B）这里必红。
        fun diffText(base: Path, head: Path): String {
            val buf = ByteArrayOutputStream()
            val out = PrintStream(buf, true, Charsets.UTF_8)
            assertEquals(0, Cli.run(arrayOf("ledger", "diff", "--base", base.toString(), "--head", head.toString()), out, System.err))
            return buf.toString(Charsets.UTF_8)
        }

        val plainBase = writeJsonl(
            "fp-base-plain.jsonl",
            listOf(rec("FN1", "CODEEE", "CodeEE", 3, 7, "失败A"), rec("FN2", "PASS")),
        )
        val plainHead = writeJsonl(
            "fp-head-plain.jsonl",
            listOf(rec("FN3", "CODEEE", "CodeEE", 2, 4, "失败C"), rec("FN1", "PASS")),
        )
        val fileBase = writeJsonl(
            "fp-base-file.jsonl",
            listOf(
                rec("FN1", "CODEEE", "CodeEE", 3, 7, "失败A", file = "ERB/A.ERB"),
                rec("FN2", "PASS", file = "ERB/B.ERB"),
            ),
        )
        val fileHead = writeJsonl(
            "fp-head-file.jsonl",
            listOf(
                rec("FN3", "CODEEE", "CodeEE", 2, 4, "失败C", file = "ERB/C.ERB"),
                rec("FN1", "PASS", file = "ERB/A.ERB"),
            ),
        )

        fun fps(p: Path): Map<String, String> = CmdLedger.loadTerminal(p).values.associate {
            it.key to Ledger.fingerprint(it.cls, it.fn, it.line, it.pc, it.msg)
        }
        assertEquals(fps(plainBase), fps(fileBase), "带不带 file 列，指纹必须同值")
        assertEquals(fps(plainHead), fps(fileHead), "带不带 file 列，指纹必须同值")

        val a = diffText(plainBase, plainHead)
        val b = diffText(fileBase, fileHead)
        assertEquals(a, b, "ledger diff 输出必须逐字节相同（file 不参与判定）")
        assertTrue("file" !in a, "diff 行不新增 file 列：$a")
        assertTrue("新增 1" in a, a)
    }

    @Test
    fun loadTerminalTakesLastRecordPerKeyAndDropsRunning() {
        val jsonl = writeJsonl(
            "last.jsonl",
            listOf(
                rec("FN1", "RUNNING"),
                rec("FN1", "PASS"),
                rec("FN1", "CODEEE", "CodeEE", 1, 2, "三次跑"),
                rec("FN2", "RUNNING"),
            ),
        )
        val byKey = CmdLedger.loadTerminal(jsonl)
        // 同 key 最后一条为准（sweep 占位覆写协议）；只剩 RUNNING 的 key 不进表
        assertEquals(setOf("FN1"), byKey.keys)
        assertEquals("CODEEE", byKey["FN1"]?.status)
    }

    @Test
    fun diffWithBomPrefixedBaseKeepsFirstRecord() {
        // 复现 2026-10-25 实测缺陷：PS 5.1 `Out-File -Encoding utf8` 合并出的 base 带 BOM，
        // 修前 loadTerminal 静默丢首条 ⇒ FN1 的失败从 base 消失 ⇒ 错报一条「新增」。
        val lines = listOf(
            rec("FN1", "CODEEE", "CodeEE", 3, 7, "失败A"),
            rec("FN2", "PASS"),
        )
        val head = writeJsonl("head-bom.jsonl", lines)
        // base 同内容，但文件字节级带 BOM 前缀（EF BB BF，模拟 Windows 写侧）
        val bomBase = tmp.resolve("base-bom.jsonl")
        Files.write(
            bomBase,
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8),
        )
        val d = CmdLedger.diff(CmdLedger.loadTerminal(bomBase), CmdLedger.loadTerminal(head))
        val fpA = Ledger.fingerprint("CodeEE", "FN1", 3, 7, "失败A")
        assertTrue(d.added.isEmpty(), "BOM base 不得错报新增：${d.added.keys}")
        assertTrue(d.fixed.isEmpty(), "BOM base 不得错报修复：${d.fixed.keys}")
        assertEquals(setOf(fpA), d.common.keys, "首条失败必须落在共有")
    }
}
