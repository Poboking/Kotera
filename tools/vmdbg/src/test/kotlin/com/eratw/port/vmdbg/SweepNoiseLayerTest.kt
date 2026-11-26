package com.eratw.port.vmdbg

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * sweep summary 的失败分层（CONTRACT-DBG-TOOLING §4）与 recheck 批量复核。
 *
 * 素材 = MiniCorpus 既有两族失败（与状态无关、fresh 单跑结论可预期）：
 * - `MINI_METHOD`（#FUNCTION 方法）→ sweep 裸调撞 methodCallError → **噪声族**（NoisePolicy 模式 4）；
 * - `MINI_CODEEE`（CALLFORM 未定义函数）→ 非噪声 → **fresh 线索**，且 fresh 单跑同样失败
 *   （未定义与状态串染无关）→ recheck 应判 CONFIRMED。
 */
class SweepNoiseLayerTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-noise-layer")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.PrintStream(buf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, System.err)
        return code to buf.toString(Charsets.UTF_8)
    }

    @Test
    fun `summary 分层 - 噪声不占 TOP 榜位且单独成表`() {
        val outDir = tmp.resolve("sweep-out")
        val (code, text) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString()))
        assertEquals(0, code, text)
        val summary = Files.readString(outDir.resolve("summary.md"), Charsets.UTF_8)

        assertTrue("- 失败分层: " in summary, "分层行必须存在\n$text")
        assertTrue("## 失败指纹 TOP（fresh 线索" in summary, "TOP 表标题必须带 fresh 标记\n$summary")
        // MINI_METHOD 是 #FUNCTION 拒调 = 已知噪声：不进 TOP 表、进噪声表
        val topSection = summary.substringAfter("## 失败指纹 TOP").substringBefore("## 已知噪声")
        assertTrue("MINI_METHOD" !in topSection, "噪声函数不许占 fresh TOP 榜位\n$topSection")
        assertTrue("## 已知噪声" in summary, "有噪声时必须出噪声表\n$summary")
        val noiseSection = summary.substringAfter("## 已知噪声")
        assertTrue("MINI_METHOD" in noiseSection, "噪声表必须列 MINI_METHOD\n$noiseSection")
        // MINI_CODEEE 是真线索：留在 fresh TOP
        assertTrue("MINI_CODEEE" in topSection, "fresh 线索必须在 TOP 表\n$topSection")
    }

    @Test
    fun `recheck 把 sweep 失败复核成 verdict 对照`() {
        val outDir = tmp.resolve("sweep-out")
        val (sweepCode, sweepText) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString()))
        assertEquals(0, sweepCode, sweepText)
        val jsonl = outDir.resolve("sweep.jsonl")

        val reOut = tmp.resolve("recheck-out")
        val (code, text) = run(
            *MiniCorpus.args(built, work, "recheck", "--from", jsonl.toString(), "--out", reOut.toString()),
        )
        // MINI_CODEEE / MINI_METHOD 两族失败在 fresh 单跑下同样失败（结论与状态串染无关）
        // ⇒ 全部 CONFIRMED ⇒ 退出码 3（有真线索）。
        assertEquals(3, code, text)
        assertTrue("verdict: CONFIRMED=" in text, text)
        assertTrue("CLEAR=0" in text, "MiniCorpus 的失败与串染无关，不应有洗清\n$text")

        val recheckJsonl = reOut.resolve("recheck.jsonl")
        val recheckMd = reOut.resolve("recheck.md")
        assertTrue(Files.isRegularFile(recheckJsonl), "recheck.jsonl 必须落盘")
        assertTrue(Files.isRegularFile(recheckMd), "recheck.md 必须落盘")
        val md = Files.readString(recheckMd, Charsets.UTF_8)
        assertTrue("CONFIRMED" in md, md)
        // 对照口径：fresh 现场必须与 sweep 同状态（同族失败）
        val rows = Files.readAllLines(recheckJsonl, Charsets.UTF_8).filter { it.isNotBlank() }
            .mapNotNull { Jsonl.parseLine(it) }
        assertTrue(rows.isNotEmpty(), "recheck.jsonl 不应为空")
        for (row in rows) {
            assertEquals(row["status"], row["freshStatus"], "fresh 复核必须复用 sweep 同口径（全 0/空串实参）\n$row")
            assertEquals("CONFIRMED", row["verdict"], "MiniCorpus 失败族 fresh 单跑仍应失败\n$row")
        }
    }

    @Test
    fun `recheck 支持 status 过滤与上限`() {
        val outDir = tmp.resolve("sweep-out")
        val (sweepCode, sweepText) = run(*MiniCorpus.args(built, work, "sweep", "--out", outDir.toString()))
        assertEquals(0, sweepCode, sweepText)
        val jsonl = outDir.resolve("sweep.jsonl")

        val (code, text) = run(
            *MiniCorpus.args(
                built, work, "recheck",
                "--from", jsonl.toString(),
                "--status", "STEP_LIMIT", // MiniCorpus 没有这个状态的失败 ⇒ 选出 0 条
                "--max", "1",
            ),
        )
        assertEquals(0, code, "无命中条目 ⇒ 无 CONFIRMED ⇒ 退出码 0\n$text")
        assertTrue("selected=0" in text, "过滤后应为空选集\n$text")
    }

    @Test
    fun `digest 校验 - 当前编译器产物放行且行协议不受污染`() {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.PrintStream(buf, true, Charsets.UTF_8)
        // MiniCorpus 产物是当场 erbcc 编的（digest = 当前树）⇒ 缺省校验应放行；
        // 校验通过不打 stdout（行协议面，VmdbgCliTest 实测被 OK 行污染过）。
        val code = Cli.run(
            arrayOf(*MiniCorpus.args(built, work, "call", "MINI_CODEEE")),
            out, System.err,
        )
        val text = buf.toString(Charsets.UTF_8)
        assertTrue("compilerDigest" !in text, "放行不许往 stdout 打校验行\n$text")
        assertEquals(3, code, "MINI_CODEEE 单跑失败 ⇒ 退出码 3（校验放行、既有行为不变）\n$text")
    }

    @Test
    fun `digest 校验 - 声明 digest 被篡改时拒载`() {
        // 手改 manifest 的 compiler-digest 行 → 缺省校验必须 fail-fast（STALE 形态）。
        // CliError 走 err 流（Main.run 的因果链打印），err 单独捕获。
        val manifest = built.erbcDir.resolve("manifest.txt")
        val original = Files.readString(manifest, Charsets.UTF_8)
        val tampered = original.replaceFirst(
            Regex("compiler-digest [0-9a-fA-F]+"),
            "compiler-digest " + "0".repeat(64),
        )
        assertTrue(tampered != original, "替换必须生效（MiniCorpus 产物必须带 compiler-digest 行）")
        Files.writeString(manifest, tampered, Charsets.UTF_8)
        val errBuf = java.io.ByteArrayOutputStream()
        val code = Cli.run(
            arrayOf(*MiniCorpus.args(built, work, "call", "MINI_CODEEE")),
            java.io.PrintStream(java.io.ByteArrayOutputStream(), true, Charsets.UTF_8),
            java.io.PrintStream(errBuf, true, Charsets.UTF_8),
        )
        assertEquals(1, code, "STALE 必须 CliError 退出码 1\n${errBuf.toString(Charsets.UTF_8)}")
        val errText = errBuf.toString(Charsets.UTF_8)
        assertTrue("产物由旧编译器产出" in errText, errText)
        assertTrue("--no-digest-check" in errText, "错误信息必须指路豁免开关\n$errText")
    }
}
