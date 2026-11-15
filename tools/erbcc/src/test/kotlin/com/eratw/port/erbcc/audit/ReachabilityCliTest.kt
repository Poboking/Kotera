package com.eratw.port.erbcc.audit

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `cmdAudit --reachability` 的 CLI 面（MODERN-R6 §3.4 / §4；MODERN-R8-B 加基线开关）。
 *
 * 用例各自钉一件事：
 * 1. **缺省关**：stdout / 退出码 / 未绑定符号 TSV 与「改动前」冻结的基线逐字节相同
 *    （基线 `resources/reachability/cli-default-off/` 是动 `Main.kt` 之前用同一份合成语料录的）；
 * 2. **显式开**：多出 summary 行 + 可达性 TSV，未绑定符号 TSV 一个字节不变；
 * 3. **确定性**：同一语料两次运行可达性 TSV 逐字节相同，且不含绝对路径；
 * 4. **基线面**（MODERN-R8-B）：`--reach-write-expect` 写出的七行、`--reach-expect` 对上/漂移
 *    两个方向（漂移退 1 且 stderr 逐字段一行）、坏基线与入参矛盾退 2（子进程真读退出码）。
 *
 * 语料由 [ReachabilityCliFixture] 现场写进 `@TempDir`，不依赖 `ERATW_CORPUS`。
 * 环境量掩码：编译读数里的耗时（`（N ms）`）与输出文件绝对路径在基线与断言两侧同样掩码
 * （见 [normalize]）—— 这两项本来就是环境量，掩码不改判据；`SymbolLinkAudit` 的既有输出
 * 一字未动。
 *
 * 退出码 2 只能靠**子进程**钉：`err()` 走 `exitProcess`，进程内调 `cmdAudit` 会把跑 JUnit 的
 * JVM 一起带走（同 `AllowListCharsetTest` 的口径）；退出码 1 是 `return 1`，进程内可断言。
 */
class ReachabilityCliTest {

    @TempDir
    lateinit var dir: Path

    private class CliRun(val code: Int, val stdout: String, val stderr: String, val out: Path)

    private fun runAudit(game: Path, out: Path, vararg extra: String): CliRun {
        Files.createDirectories(out.toAbsolutePath().normalize().parent)
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
        val code: Int
        try {
            code = com.eratw.port.erbcc.cmdAudit(
                listOf(
                    "--corpus", game.resolve("ERB").toString(),
                    "--game-root", game.toString(),
                    "--out", out.toString(),
                ) + extra.toList(),
            )
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return CliRun(code, normalize(outBuf.toString("UTF-8"), out), errBuf.toString("UTF-8"), out)
    }

    /**
     * 环境量掩码（基线与现网两侧同口径）：
     * - CRLF → LF（Windows 控制台）；
     * - `（N ms）` → `（<ms>）`（编译耗时）；
     * - `--out` 的绝对路径 → `<out>`（临时目录每次不同）。
     */
    private fun normalize(stdout: String, out: Path): String =
        stdout.replace("\r\n", "\n")
            .replace(Regex("（\\d+ ms）"), "（<ms>）")
            .replace(out.toAbsolutePath().normalize().toString(), "<out>")

    private fun baseline(name: String): String {
        val stream = ReachabilityCliTest::class.java.getResourceAsStream(
            "/reachability/cli-default-off/$name",
        )
        assertNotNull(stream, "缺基线资源 $name（应随测试一同提交）")
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    /** 报错行与 stdout 同口径掩码（CRLF → LF）。 */
    private fun normalizeErr(text: String, out: Path): String =
        text.replace("\r\n", "\n").replace(out.toAbsolutePath().normalize().toString(), "<out>")

    /**
     * 基线面的 stderr 行（只挑本路新增的那一种）。
     *
     * `audit` 的 stderr 本来就有编译器诊断（`[erbcc-parallel]`、`notes N 条…`，见
     * `SymbolLinkAudit` 的既有输出），所以「stderr 为空」不是本路能断言的判据 ——
     * 能断言的是「本路没有往 stderr 多写一行」：基线漂移行按前缀点名。
     */
    private fun driftLines(run: CliRun): List<String> =
        run.stderr.replace("\r\n", "\n").split('\n').filter { it.startsWith("reachability 基线不一致：") }

    // -------------------------------------------------------------------------
    // 基线段（MODERN-R8-B）的装置
    // -------------------------------------------------------------------------

    /** 跑一次 `audit --reachability --reach-out <reach>`（基线读写都挂在这条路上）。 */
    private fun runReach(
        game: Path,
        tag: String,
        vararg extra: String,
    ): CliRun = runAudit(
        game,
        dir.resolve("$tag/unresolved-symbols.tsv"),
        "--reachability",
        "--reach-out",
        dir.resolve("$tag/reachability.tsv").toString(),
        *extra,
    )

    private fun sha256Of(path: Path): String {
        val d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 基线正文的字段块（`key=value` 七行），用于逐项改值造漂移。 */
    private fun expectLines(path: Path): MutableList<String> =
        Files.readString(path).split('\n').dropLast(1).toMutableList()

    private fun rewrite(path: Path, body: MutableList<String>) = Files.writeString(path, body.joinToString("\n") + "\n")

    @Test
    fun `缺省关时 stdout 退出码与 TSV 与冻结基线逐字节相同`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val out = dir.resolve("off").resolve("unresolved-symbols.tsv")
        val run = runAudit(game, out)
        assertEquals(0, run.code, "缺省关的审计应照常退出 0")
        assertEquals(baseline("stdout.txt"), run.stdout, "缺省关的 stdout 必须与改动前逐字节相同")
        assertEquals(baseline("unresolved-symbols.tsv"), Files.readString(out), "缺省关的 TSV 必须与改动前逐字节相同")
        assertEquals(emptyList(), driftLines(run), "缺省关不得有任何基线漂移行")
        assertFalse(run.stdout.contains("基线"), "缺省关不得出现任何基线字样")
        assertFalse(run.stderr.contains("reachability 基线"), "缺省关不得出现任何基线字样")
        assertFalse(
            Files.exists(out.toAbsolutePath().normalize().parent.resolve("reachability.tsv")),
            "缺省关不应产出可达性报告",
        )
    }

    @Test
    fun `显式开启追加 summary 行与可达性 TSV`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val out = dir.resolve("on").resolve("unresolved-symbols.tsv")
        val reachOut = dir.resolve("on").resolve("reach.tsv")
        val run = runAudit(game, out, "--reachability", "--reach-out", reachOut.toString())
        assertEquals(0, run.code, "开启报告不应改变退出码")

        val pattern = Regex(
            "^reachability: roots=(\\d+) reachable=(\\d+) dynamic-calls=(\\d+) portability=(\\d+) persist=(\\d+)$",
            RegexOption.MULTILINE,
        )
        val hit = pattern.find(run.stdout)
        assertNotNull(hit, "应打印 reachability summary 行，实际 stdout:\n${run.stdout}")
        val roots = hit.groupValues[1].toInt()
        val reachable = hit.groupValues[2].toInt()
        val portability = hit.groupValues[4].toInt()
        // 语料只有 @SYSTEM_TITLE 一个根，@SUB_ONE 由 CALL 到达；GETFONT() 一行落进可移植性诊断。
        assertEquals(1, roots, "合成语料应只有 SYSTEM_TITLE 一个根")
        assertTrue(reachable >= 2, "@SYSTEM_TITLE 与 @SUB_ONE 都应可达，实际 $reachable")
        assertTrue(portability >= 1, "PRINTFORML GETFONT() 应命中可移植性诊断，实际 $portability")
        // summary 落在「明细：」之后、「结论：」之前（不动既有输出的位置约定）。
        val summaryAt = run.stdout.indexOf(hit.value)
        assertTrue(summaryAt > run.stdout.indexOf("明细："), "summary 应在明细行之后")
        assertTrue(summaryAt < run.stdout.indexOf("结论："), "summary 应在结论行之前")

        val tsv = Files.readString(reachOut)
        assertTrue(tsv.startsWith("kind\tgrade\tfunction\tfile\tline\tdetail\n"), "首行应是固定表头")
        assertTrue(
            tsv.contains("reachable\t-\tSYSTEM_TITLE\t01_MAIN.ERB\t1\tdepth=0 root=SYSTEM_TITLE"),
            "根自身应占一行（depth=0）:\n$tsv",
        )
        assertTrue(
            tsv.contains("reachable\t-\tSUB_ONE\t01_MAIN.ERB\t8\tdepth=1 root=SYSTEM_TITLE"),
            "CALL 边应把 @SUB_ONE 记成 depth=1:\n$tsv",
        )
        assertTrue(
            tsv.contains("portability\tdisplay\tSYSTEM_TITLE\t01_MAIN.ERB\t5\tGETFONT PRINTFORML GETFONT()"),
            "GETFONT 行应判 display:\n$tsv",
        )
        assertFalse(
            tsv.contains(dir.toAbsolutePath().normalize().toString()),
            "报告不应含绝对路径",
        )
        // 未绑定符号 TSV 与缺省关基线逐字节相同（开启报告不改既有产物）。
        assertEquals(baseline("unresolved-symbols.tsv"), Files.readString(out))
    }

    @Test
    fun `同一语料两次运行记录逐字节相同`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val first = dir.resolve("r1").resolve("reach.tsv")
        val second = dir.resolve("r2").resolve("reach.tsv")
        runAudit(game, dir.resolve("r1").resolve("u.tsv"), "--reachability", "--reach-out", first.toString())
        runAudit(game, dir.resolve("r2").resolve("u.tsv"), "--reachability", "--reach-out", second.toString())
        assertEquals(Files.readString(first), Files.readString(second), "同一语料两次运行应逐字节相同")
    }

    // -------------------------------------------------------------------------
    // --reach-write-expect：写出的七行与 sha 口径
    // -------------------------------------------------------------------------

    @Test
    fun `写基线落七行固定键序且sha对TSV落盘字节取`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val expect = dir.resolve("w/baseline/reachability.expect")
        val run = runReach(game, "w", "--reach-write-expect", expect.toString())
        assertEquals(0, run.code, "写基线不应改变退出码。stdout:\n${run.stdout}\nstderr:\n${run.stderr}")
        assertEquals(emptyList(), driftLines(run), "写基线不产生漂移行")
        assertFalse(run.stderr.contains("reachability 基线"), "写基线只在 stdout 追加一行，stderr 不许多写")
        val written = expect.toAbsolutePath().normalize()
        assertContains(run.stdout, "reachability 基线已写入：$written", message = "应打印落点的绝对路径：\n${run.stdout}")
        assertTrue(Files.isRegularFile(written), "父目录应被自动创建：$written")

        val lines = expectLines(written)
        assertEquals(7, lines.size, "基线必须恰好七行：\n${lines.joinToString("\n")}")
        assertEquals("version=1", lines[0])
        val summary = Regex(
            "^reachability: roots=(\\d+) reachable=(\\d+) dynamic-calls=(\\d+) portability=(\\d+) persist=(\\d+)$",
            RegexOption.MULTILINE,
        ).find(run.stdout)!!
        val counts = summary.groupValues.drop(1).map { it.toInt() }
        assertEquals("roots=${counts[0]}", lines[1])
        assertEquals("reachable=${counts[1]}", lines[2])
        assertEquals("dynamicCalls=${counts[2]}", lines[3])
        assertEquals("portability=${counts[3]}", lines[4])
        assertEquals("persist=${counts[4]}", lines[5])
        // sha 只对 TSV 文本取（不含文件名）：与本次落盘的 reachability.tsv 字节 sha 相同。
        val tsv = dir.resolve("w/reachability.tsv")
        assertEquals("tsvSha256=${sha256Of(tsv)}", lines[6], "tsvSha256 必须等于 TSV 落盘字节的 sha256")
        assertTrue(
            Files.readString(written).all { it.code in 0x20..0x7E || it == '\n' },
            "基线必须是纯 ASCII：\n${Files.readString(written)}",
        )
        // 写基线不改可达性 TSV / 未绑定符号 TSV 的字节。
        assertEquals(baseline("unresolved-symbols.tsv"), Files.readString(dir.resolve("w/unresolved-symbols.tsv")))
    }

    @Test
    fun `两次写同一读数文件字节相同`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val a = dir.resolve("d1/reachability.expect")
        val b = dir.resolve("d2/reachability.expect")
        runReach(game, "d1", "--reach-write-expect", a.toString())
        runReach(game, "d2", "--reach-write-expect", b.toString())
        assertEquals(sha256Of(a), sha256Of(b), "同一语料两次写基线必须逐字节相同")
        assertEquals(Files.readString(a), Files.readString(b))
    }

    // -------------------------------------------------------------------------
    // --reach-expect：对上 / 漂移两个方向
    // -------------------------------------------------------------------------

    @Test
    fun `对上基线退0打一致且无漂移行`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val expect = dir.resolve("ok/reachability.expect")
        runReach(game, "ok", "--reach-write-expect", expect.toString())
        val run = runReach(game, "ok2", "--reach-expect", expect.toString())
        assertEquals(0, run.code, "基线一致应退 0。stderr:\n${run.stderr}")
        assertEquals(emptyList(), driftLines(run), "一致时不该有漂移行")
        assertContains(run.stdout, "reachability 基线一致")
        // 「一致」那行落在可移植性明细之后、结论之前。
        assertTrue(run.stdout.indexOf("reachability 基线一致") > run.stdout.indexOf("可移植性明细："))
        assertTrue(run.stdout.indexOf("reachability 基线一致") < run.stdout.indexOf("结论："))
    }

    @Test
    fun `roots 漂移退1且stderr恰一行点名roots`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val expect = dir.resolve("drift/reachability.expect")
        runReach(game, "drift", "--reach-write-expect", expect.toString())
        val lines = expectLines(expect)
        val at = lines.indexOfFirst { it.startsWith("roots=") }
        val roots = lines[at].substringAfter('=').toInt()
        lines[at] = "roots=${roots + 1}"
        rewrite(expect, lines)

        val run = runReach(game, "drift2", "--reach-expect", expect.toString())
        assertEquals(1, run.code, "漂移应退 1（读数红，不是入参错）。stderr:\n${run.stderr}")
        val err = normalizeErr(run.stderr, dir.resolve("drift2/unresolved-symbols.tsv"))
        val drift = driftLines(run).map { normalizeErr(it, dir.resolve("drift2/unresolved-symbols.tsv")) }
        assertEquals(1, drift.size, "一条字段漂移恰一行 stderr，实为：\n$err")
        assertEquals("reachability 基线不一致：roots 期望 ${roots + 1} 实际 $roots", drift[0])
        assertFalse(err.contains("tsvSha256"), "只漂 roots 时不许把 sha 一起列出来")
        assertFalse(run.stdout.contains("reachability 基线一致"), "漂移时不许打「基线一致」")
    }

    @Test
    fun `只改sha一位退1且点名tsvSha256`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val expect = dir.resolve("sha/reachability.expect")
        runReach(game, "sha", "--reach-write-expect", expect.toString())
        val lines = expectLines(expect)
        val at = lines.indexOfFirst { it.startsWith("tsvSha256=") }
        val sha = lines[at].substringAfter('=')
        val flipped = sha.dropLast(1) + if (sha.last() == '0') '1' else '0'
        lines[at] = "tsvSha256=$flipped"
        rewrite(expect, lines)

        val run = runReach(game, "sha2", "--reach-expect", expect.toString())
        assertEquals(1, run.code)
        val err = normalizeErr(run.stderr, dir.resolve("sha2/unresolved-symbols.tsv"))
        val drift = driftLines(run).map { normalizeErr(it, dir.resolve("sha2/unresolved-symbols.tsv")) }
        assertEquals(1, drift.size, "一条字段漂移恰一行 stderr，实为：\n$err")
        assertEquals("reachability 基线不一致：tsvSha256 期望 $flipped 实际 $sha", drift[0])
        assertFalse(err.contains("roots"), "只漂 sha 时不许把计数字段一起列出来")
    }

    // -------------------------------------------------------------------------
    // 退出码 2：入参矛盾 + 坏基线（子进程真读退出码）
    // -------------------------------------------------------------------------

    @Test
    fun `入参矛盾与坏基线退2（子进程读真退出码）`() {
        val game = ReachabilityCliFixture.writeGame(dir)
        val expect = dir.resolve("bad/reachability.expect")
        runReach(game, "bad", "--reach-write-expect", expect.toString())

        val badMissing = dir.resolve("bad/missing.expect")
        val withoutPersist = expectLines(expect).filterNot { it.startsWith("persist=") }
        Files.writeString(badMissing, withoutPersist.joinToString("\n") + "\n")
        val badVersion = dir.resolve("bad/version.expect")
        Files.writeString(badVersion, expectLines(expect).joinToString("\n").replace("version=1", "version=2") + "\n")

        data class Case(val tag: String, val args: List<String>, val stderr: String)
        val cases = listOf(
            Case(
                "expect 缺 --reachability",
                listOf("--reach-expect", expect.toString()),
                "--reach-expect 需要配合 --reachability",
            ),
            Case(
                "write-expect 缺 --reachability",
                listOf("--reach-write-expect", expect.toString()),
                "--reach-write-expect 需要配合 --reachability",
            ),
            Case(
                "两开关同给",
                listOf(
                    "--reachability",
                    "--reach-expect", expect.toString(),
                    "--reach-write-expect", expect.toString(),
                ),
                "--reach-expect 与 --reach-write-expect 互斥",
            ),
            Case(
                "坏基线缺键",
                listOf("--reachability", "--reach-expect", badMissing.toString()),
                "可达性基线缺字段 persist",
            ),
            Case(
                "坏基线版本不认识",
                listOf("--reachability", "--reach-expect", badVersion.toString()),
                "可达性基线 version 不认识（期望 1，实际 2）",
            ),
        )
        for (c in cases) {
            val out = dir.resolve("sub-${c.tag.hashCode()}/unresolved-symbols.tsv")
            Files.createDirectories(out.parent)
            val r = runCliSubprocess(
                "audit",
                "--corpus", game.resolve("ERB").toString(),
                "--game-root", game.toString(),
                "--out", out.toString(),
                *c.args.toTypedArray(),
            )
            assertEquals(2, r.code, "「${c.tag}」应退 2。stdout:\n${r.stdout}\nstderr:\n${r.stderr}")
            assertContains(r.stderr, c.stderr, message = "「${c.tag}」的报错要点名原因，实为：\n${r.stderr}")
        }
    }

    /** 一个子进程的读数（stdout/stderr 都按 UTF-8 解码）。 */
    private class ProcRun(val code: Int, val stdout: String, val stderr: String)

    /**
     * 起子进程跑真 `main`。`err()` 走 `exitProcess(2)`，进程内调 `cmdAudit` 会把 JUnit 的 JVM
     * 一起带走，所以退出码 2 只能这样钉；编码与行尾显式钉 UTF-8，不靠本机 cp936 默认值。
     */
    private fun runCliSubprocess(vararg args: String): ProcRun {
        val javaHome = Path.of(System.getProperty("java.home"))
        val java = javaHome.resolve("bin").resolve(if (File.separatorChar == '\\') "java.exe" else "java")
        val tag = args.joinToString("_").hashCode().toString()
        val outFile = dir.resolve("proc-$tag.out")
        val errFile = dir.resolve("proc-$tag.err")
        val cmd = buildList {
            add(java.toString())
            add("-Dfile.encoding=UTF-8")
            add("-Dsun.stdout.encoding=UTF-8")
            add("-Dsun.stderr.encoding=UTF-8")
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("com.eratw.port.erbcc.MainKt")
            addAll(args)
        }
        val proc = ProcessBuilder(cmd)
            .redirectOutput(outFile.toFile())
            .redirectError(errFile.toFile())
            .start()
        proc.outputStream.close()
        val code = proc.waitFor()
        return ProcRun(code, Files.readString(outFile), Files.readString(errFile))
    }
}

/** CLI 用例的合成语料（`@TempDir` 现场写；与录制基线时逐字节同一份）。 */
internal object ReachabilityCliFixture {

    fun writeGame(dir: Path): Path {
        val game = dir.resolve("game")
        val erb = game.resolve("ERB")
        Files.createDirectories(erb)
        // 空 CSV：GameDataLoader 走缺省注册表（与 SymbolLinkAuditTest 同口径）
        Files.createDirectories(game.resolve("CSV"))
        Files.writeString(erb.resolve("DIM.ERH"), "#DIM CONST C_BASE = 3\n")
        Files.writeString(
            erb.resolve("01_MAIN.ERB"),
            """
            @SYSTEM_TITLE
            #DIM BUF, 3
            BUF:0 = C_BASE
            PRINTFORML {BUF:0}
            PRINTFORML GETFONT()
            CALL SUB_ONE

            @SUB_ONE
            #DIM BAD, 10 = NONEXISTENT
            BAD:0 = 1
            PRINTFORML ONE
            """.trimIndent() + "\n",
        )
        return game
    }
}
