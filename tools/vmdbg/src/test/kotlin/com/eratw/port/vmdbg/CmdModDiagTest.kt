package com.eratw.port.vmdbg

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `moddiag` 只读摘要命令的机制锁（CONTRACT-MODDX-3 §2 Lane DX3-B）：
 * 正常读真实 exportJsonLines 产物并按核心判据排序、空环 / OK / SKIPPED 不出摘要、
 * 坏路径 / 坏行 / 缺字段 / 非法枚举都折成退出码 1 + 含行号的清晰错误。
 *
 * 经 [Cli.run] 走真实注册分支：命令没注册时用例 1 的退出码与摘要行就会红。
 */
class CmdModDiagTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-moddiag-test")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val code = Cli.run(
            arrayOf(*args),
            out = PrintStream(buf, true, Charsets.UTF_8),
            err = PrintStream(ebuf, true, Charsets.UTF_8),
        )
        return code to (buf.toString(Charsets.UTF_8) + ebuf.toString(Charsets.UTF_8))
    }

    private fun write(name: String, text: String): Path =
        tmp.resolve(name).also { Files.writeString(it, text, Charsets.UTF_8) }

    /** 真实导出产物：2×JOB/com.a/FAILED + EVENT/com.b/FAILED + PHASE/null/REJECTED + OK + SKIPPED。 */
    private fun sample(): String {
        val d = RuntimeDiagnostics()
        fun put(domain: DiagnosticDomain, pkg: String?, outcome: DiagnosticOutcome, key: String) =
            d.record(day = null, phase = null, domain = domain, packageId = pkg, key = key,
                outcome = outcome, reasonCode = "R")
        repeat(2) { put(DiagnosticDomain.JOB, "com.a", DiagnosticOutcome.FAILED, "j$it") }
        put(DiagnosticDomain.EVENT, "com.b", DiagnosticOutcome.FAILED, "e")
        put(DiagnosticDomain.PHASE, null, DiagnosticOutcome.REJECTED, "p")
        put(DiagnosticDomain.JOB, "com.a", DiagnosticOutcome.OK, "ok")
        put(DiagnosticDomain.JOB, "com.a", DiagnosticOutcome.SKIPPED, "skip")
        return d.exportJsonLines()
    }

    @Test
    fun `正常读JSONL出摘要表且排序正确`() {
        val path = write("diag.jsonl", sample())
        val (code, text) = run("moddiag", path.toString())
        assertEquals(0, code, "有效输入应成功；非 0 说明注册分支或解析有问题: $text")
        assertTrue("records=6" in text, "六条原始记录都读到: $text")
        assertTrue("groups=3" in text, "OK/SKIPPED 不上摘要: $text")
        val rows = text.lineSequence().filter { it.startsWith("mod.diag.summary ") }.toList()
        assertEquals(
            listOf(
                "mod.diag.summary count=2 domain=JOB package=com.a outcome=FAILED",
                "mod.diag.summary count=1 domain=EVENT package=com.b outcome=FAILED",
                "mod.diag.summary count=1 domain=PHASE package=<engine> outcome=REJECTED",
            ),
            rows,
        )
    }

    @Test
    fun `空环与只有OK的文件groups为0`() {
        val empty = write("empty.jsonl", "")
        val (code0, text0) = run("moddiag", empty.toString())
        assertEquals(0, code0)
        assertTrue("records=0" in text0 && "groups=0" in text0, text0)
        assertTrue(text0.lineSequence().none { it.startsWith("mod.diag.summary ") }, "空环无摘要行")

        val d = RuntimeDiagnostics()
        d.record(day = null, phase = null, domain = DiagnosticDomain.JOB, packageId = "com.a",
            key = "k", outcome = DiagnosticOutcome.OK, reasonCode = "R")
        val okOnly = write("ok.jsonl", d.exportJsonLines())
        val (code1, text1) = run("moddiag", okOnly.toString())
        assertEquals(0, code1)
        assertTrue("records=1" in text1 && "groups=0" in text1, text1)
    }

    @Test
    fun `坏路径给清晰错误`() {
        val missing = tmp.resolve("missing.jsonl").toString()
        val (code, text) = run("moddiag", missing)
        assertEquals(1, code)
        assertTrue("文件不存在" in text, "清晰错误: $text")
    }

    @Test
    fun `坏行不可解析给清晰错误含行号`() {
        val path = write("bad.jsonl", "not-json\n")
        val (code, text) = run("moddiag", path.toString())
        assertEquals(1, code)
        assertTrue("第 1 行" in text && "不是可解析" in text, "错误要能定位到行: $text")
    }

    @Test
    fun `JSONL字段缺失给清晰错误`() {
        val path = write("missing-field.jsonl", "{\"seq\":1,\"day\":null,\"phase\":null,\"domain\":\"JOB\"," +
            "\"package\":null,\"key\":\"k\",\"reason\":\"R\"}\n")
        val (code, text) = run("moddiag", path.toString())
        assertEquals(1, code)
        assertTrue("第 1 行缺字段 outcome" in text, "缺字段错误要点名行号与字段名: $text")
    }

    @Test
    fun `非法枚举给清晰错误`() {
        val path = write("bad-enum.jsonl", "{\"seq\":1,\"day\":null,\"phase\":null,\"domain\":\"NOPE\"," +
            "\"package\":null,\"key\":\"k\",\"outcome\":\"FAILED\",\"reason\":\"R\",\"detail\":null}\n")
        val (code, text) = run("moddiag", path.toString())
        assertEquals(1, code)
        assertTrue("第 1 行 domain 非法" in text, text)
    }
}
