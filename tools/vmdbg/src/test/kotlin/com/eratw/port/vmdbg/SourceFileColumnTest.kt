package com.eratw.port.vmdbg

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文件维度（MODERN-R7 F2）：disasm / call / where / sweep 的 `file` 列口径。
 *
 * 断言基点是**产物自身的事实**（`Program.functions` 的 entryPc + `BootMetadata` 的
 * `position` 文件列），不复用被测的 `SourceMap.of` 组装结果 —— 否则「拿被测实现证自己」。
 * 口径三条：
 * - 只有 `文件:行号` 形 position 才算真实文件；无归属 / 合成标记 / 不可拆 ⇒ `-`；
 * - 绝对路径掩成 `<absolute>`，输出面不出现跑这台机的目录结构；
 * - sweep 记录的 `file` 是**该函数自身**的文件，不是失败点所在文件（失败点按行/pc 另看）。
 */
class SourceFileColumnTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-filecol-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, System.err)
        return code to buf.toString(Charsets.UTF_8)
    }

    private fun kv(text: String): Map<String, String> =
        text.lineSequence()
            .takeWhile { it.contains('=') }
            .mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
            }
            .toMap()

    /**
     * 独立推导：按 entryPc 区间找出 pc 的归属函数，再取它的 `position` 文件列。
     * 与 `SourceMap` 的组装实现无关（只共用已由 R4-C 钉死的 `parsePosition`）。
     */
    private fun expectedFileAt(program: Program, metadata: BootMetadata, pc: Int): String {
        var best = -1
        for (i in program.functions.indices) {
            val e = program.functions[i].entryPc
            if (e <= pc && (best < 0 || e > program.functions[best].entryPc)) best = i
        }
        if (best < 0) return "-"
        val pos = metadata.functions.firstOrNull { it.index == best }?.position
        val parsed = SourceMap.parsePosition(pos)
        return if (parsed.kind == PositionKind.FILE_LINE) maskFileColumn(parsed.file) else "-"
    }

    // ------------------------------------------------------------------ disasm
    @Test
    fun disasm每条指令的file等于该pc所属函数的position文件() {
        val program = ProgramLoader.loadMapped(built.erbcDir.resolve("program.erbc"))
        val metadata = BootMetadata.read(built.erbcDir.resolve("program.meta"))
        val (code, text) = run(*MiniCorpus.args(built, work, "disasm", "0", "-n", "4"))
        assertEquals(0, code, text)

        val rows = text.lines().filter { it.startsWith("pc=") && "instructionCount" !in it }
        assertEquals(4, rows.size, text)
        val rx = Regex("^pc=(\\d+) line=(-?\\d+) file=(\\S+) words=\\[")
        for (row in rows) {
            val m = rx.find(row) ?: error("指令行格式不符（缺 file=）：$row")
            val pc = m.groupValues[1].toInt()
            assertEquals(expectedFileAt(program, metadata, pc), m.groupValues[3], "pc=$pc 的 file 列")
            assertTrue(m.groupValues[3].endsWith("mini.erb"), row)
        }
        // header 不带 file=
        assertTrue("file=" !in text.lineSequence().first(), text)

        // 两跑确定性（S6）：同一产物 + 同一输入，file 列逐字节相同
        val (code2, text2) = run(*MiniCorpus.args(built, work, "disasm", "0", "-n", "4"))
        assertEquals(0, code2)
        assertEquals(text, text2, "同一产物两次 disasm 应逐字节相同")
    }

    // ------------------------------------------------------------------ where 旧档 / 绝对路径
    @Test
    fun where读旧档无file键时输出空值不炸() {
        Files.createDirectories(work)
        // R7 之前的 last-error.json：没有 file 键
        Files.writeString(
            work.resolve("last-error.json"),
            Jsonl.obj(
                listOf(
                    "status" to "CODEEE", "funcName" to "OLD_FN", "line" to 3, "pc" to 9,
                    "callChain" to null, "message" to "旧档", "steps" to 5, "elapsedMs" to 1,
                ),
            ) + "\n",
            Charsets.UTF_8,
        )
        val (code, text) = run(*MiniCorpus.args(built, work, "where"))
        assertEquals(0, code, text)
        assertEquals("CODEEE", kv(text)["status"])
        val fileLine = text.lineSequence().firstOrNull { it.startsWith("file=") }
        assertEquals("file=", fileLine, "旧档缺该键 ⇒ 空值（不编造、不落 -）：$text")
    }

    @Test
    fun where回读绝对路径档时遮罩不上屏() {
        Files.createDirectories(work)
        Files.writeString(
            work.resolve("last-error.json"),
            Jsonl.obj(
                listOf(
                    "status" to "CODEEE", "funcName" to "OLD_FN", "line" to 3, "pc" to 9,
                    "callChain" to null, "message" to "脏档", "steps" to 5, "elapsedMs" to 1,
                    "file" to "C:/secret/leak.ERB",
                ),
            ) + "\n",
            Charsets.UTF_8,
        )
        val (code, text) = run(*MiniCorpus.args(built, work, "where"))
        assertEquals(0, code, text)
        assertEquals("file=<absolute>", text.lineSequence().first { it.startsWith("file=") }, text)
        assertFalse("secret" in text, "绝对路径不得原样上屏：$text")
    }

    // ------------------------------------------------------------------ sweep 口径
    @Test
    fun sweep记录的file是该函数自身的文件而非失败点文件() {
        // 两文件语料：caller.erb 的函数调用 inner.erb 的函数，后者在**自己的文件里**炸。
        // 于是两条记录的 line/pc 是同一个失败点（inner.erb），file 却必须各归各家 ——
        // 这就是「函数自身文件」与「失败点文件」的判别面。
        val corpus = buildTwoFileCorpus(tmp.resolve("two-file"))
        val outDir = tmp.resolve("sweep-two-file")
        val (code, text) = run(
            "--erbc", corpus.erbcDir.toString(), "--game", corpus.gameRoot.toString(),
            "--work", work.toString(), "sweep", "--out", outDir.toString(),
        )
        assertEquals(0, code, text)

        val recs = Files.readAllLines(outDir.resolve("sweep.jsonl"), Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { Jsonl.parseLine(it) }
            .associateBy { it["key"] }
        val outer = recs["OUTER_CALLER"] ?: error("缺 OUTER_CALLER 记录：$recs")
        val inner = recs["INNER_FAIL"] ?: error("缺 INNER_FAIL 记录：$recs")

        assertEquals("CODEEE", outer["status"], outer.toString())
        assertEquals("CODEEE", inner["status"], inner.toString())
        // 同一失败点：行与 pc 相同（错误带着内层函数的现场穿透上来）
        assertEquals(inner["line"], outer["line"], "两条记录应是同一失败点：$outer / $inner")
        assertEquals(inner["pc"], outer["pc"], "两条记录应是同一失败点：$outer / $inner")
        // 文件各归各家：外层记录不能显示失败点所在的内层文件
        assertTrue((outer["file"] ?: "").endsWith("caller.erb"), "外层 file 应是函数自身文件：$outer")
        assertTrue((inner["file"] ?: "").endsWith("inner.erb"), "内层 file 应是函数自身文件：$inner")
        assertFalse((outer["file"] ?: "").endsWith("inner.erb"), "file 不能取失败点所在文件：$outer")
    }

    // ------------------------------------------------------------------ 口径单测
    @Test
    fun 文件列口径_合成与不可拆落横杠_绝对路径遮罩() {
        assertEquals("-", fileColumn(null, PositionKind.NONE))
        assertEquals("-", fileColumn(null, PositionKind.UNSPLITTABLE))
        assertEquals("-", fileColumn("·event-entry·", PositionKind.SYNTHETIC))
        assertEquals("<absolute>", fileColumn("/tmp/x.ERB", PositionKind.FILE_LINE))
        assertEquals("<absolute>", fileColumn("C:/tmp/x.ERB", PositionKind.FILE_LINE))
        assertEquals("ERB/SUB/A.ERB", fileColumn("ERB/SUB/A.ERB", PositionKind.FILE_LINE))
        // 合成事件入口的真实来源：erbcc 写的占位串 ⇒ 落 -
        assertEquals("-", fileColumn(SourceMap.parsePosition("·event-entry·").file, SourceMap.parsePosition("·event-entry·").kind))
    }

    /** 两文件语料（自建，形状照 [MiniCorpus]）：返回值带 game 根与 erbc 目录。 */
    private class TwoFileCorpus(val gameRoot: Path, val erbcDir: Path)

    private fun buildTwoFileCorpus(dir: Path): TwoFileCorpus {
        val game = dir.resolve("game")
        val erbDir = game.resolve("ERB")
        Files.createDirectories(erbDir)
        Files.writeString(game.resolve("emuera.config"), "")
        Files.writeString(
            erbDir.resolve("caller.erb"),
            listOf("@OUTER_CALLER", "\tCALL INNER_FAIL", "").joinToString("\n"),
        )
        Files.writeString(
            erbDir.resolve("inner.erb"),
            listOf("@INNER_FAIL", "\tCALLFORM MINI_NOPE_XYZ", "").joinToString("\n"),
        )
        val result = CorpusCompile.compile(erbDir)
        check(result.ok) { "两文件语料编译失败: ${result.failures.joinToString("; ")}" }
        val outDir = dir.resolve("erbc-two")
        ErbcArtifacts.write(outDir, result.program!!, erbDir, result.sourceFiles, result.stats, result.bootMetadata)
        return TwoFileCorpus(game, outDir.resolve("erbc.v1"))
    }
}
