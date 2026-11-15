package com.eratw.port.erbcc.audit

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 可达性基线（MODERN-R8-B）：格式、写读往返、逐字段对拍、坏基线拒绝。
 *
 * 钉的口径来自契约 MODERN-R8-B §3 S2：
 * 1. 基线正文是纯 ASCII `key=value`，`version=1` 起头、六字段键序固定、无注释、LF 行尾；
 * 2. `tsvSha256` = [ReachabilityReport.tsvText] 的 UTF-8 字节 SHA-256（与 `writeTsv` 落盘同源）；
 * 3. 对拍逐字段一行、字段顺序固定 `roots, reachable, dynamicCalls, portability, persist, tsvSha256`；
 * 4. 坏基线（缺键 / 未知键 / 重复键 / version≠1 / 非整数 / sha 非 64 位小写十六进制 / 非文件）
 *    一律抛 [IllegalStateException]（CLI 侧映射成退出码 2），消息确定性且不泄内部异常名。
 *
 * 退出码本身不在本文件断言：`err()` 会 `exitProcess`（把跑 JUnit 的 JVM 一起带走），
 * 退出码 1/2 的 CLI 面由 `ReachabilityCliTest` 起子进程钉住。
 */
class ReachabilityExpectTest {

    @TempDir
    lateinit var dir: Path

    /** 一份形状固定的小报告（两行可达 + 一行动态调用 + 一行可移植性，persist=1）。 */
    private fun sampleReport() = ReachabilityReport(
        roots = 3,
        reachable = 7,
        rows = listOf(
            ReachabilityRow(
                ReachabilityKind.REACHABLE, "-", "SYSTEM_TITLE", "01_MAIN.ERB", 1,
                "depth=0 root=SYSTEM_TITLE",
            ),
            ReachabilityRow(ReachabilityKind.DYNAMIC_CALL, "-", "SYSTEM_TITLE", "01_MAIN.ERB", 4, "CALL 未知目标"),
            ReachabilityRow(ReachabilityKind.PORTABILITY, "persist", "SYSTEM_TITLE", "01_MAIN.ERB", 5, "GETCONFIG 存档"),
            ReachabilityRow(ReachabilityKind.PORTABILITY, "display", "SUB", "02_SUB.ERB", 9, "CLIENTWIDTH 显示"),
        ),
    )

    /** 独立的 sha256（不借被测实现），专门用来验「取的是 TSV 的 UTF-8 字节」。 */
    private fun sha256Reference(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // 1. 正文格式
    // -------------------------------------------------------------------------

    @Test
    fun `基线正文是固定七行的纯ASCII键值`() {
        val report = sampleReport()
        val expect = ReachabilityExpect.of(report)
        val text = ReachabilityExpect.render(expect)
        val lines = text.split('\n').dropLast(1) // 结尾一个 LF ⇒ split 尾项是空串
        assertEquals(7, lines.size, "基线必须恰好七行（version + 六字段），实际：\n$text")
        assertEquals("version=1", lines[0])
        assertEquals("roots=3", lines[1])
        assertEquals("reachable=7", lines[2])
        assertEquals("dynamicCalls=1", lines[3])
        assertEquals("portability=2", lines[4])
        assertEquals("persist=1", lines[5])
        assertEquals("tsvSha256=${sha256Reference(report.tsvText().toByteArray(Charsets.UTF_8))}", lines[6])
        assertTrue(text.endsWith("\n") && !text.contains('\r'), "行尾必须是 LF、结尾一个换行")
        assertTrue(
            text.all { it.code in 0x20..0x7E || it == '\n' },
            "基线必须是纯 ASCII（无 BOM、无注释、无时间戳）：\n$text",
        )
        assertTrue(ReachabilityExpect.of(report).tsvSha256.matches(Regex("^[0-9a-f]{64}$")), "sha 必须 64 位小写十六进制")
    }

    @Test
    fun `sha256Hex 对上公开向量`() {
        // 公开向量：空串与 "abc"（证明取的是 UTF-8 字节、输出小写十六进制）。
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ReachabilityExpect.sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ReachabilityExpect.sha256Hex("abc"),
        )
    }

    @Test
    fun `tsvText 与 writeTsv 落盘逐字节相同`() {
        // 反向证明 B 的守卫：tsvText 与写盘一旦分叉（写盘多写一行/换行不同），基线 sha 就与真实
        // TSV 脱钩 —— 本条在任何 TSV 形状上都要求两侧同一份构造。
        val report = sampleReport()
        val out = dir.resolve("sub/reach.tsv")
        report.writeTsv(out)
        assertEquals(report.tsvText(), Files.readString(out), "writeTsv 必须逐字节写出 tsvText()")
        assertEquals(
            sha256Reference(report.tsvText().toByteArray(Charsets.UTF_8)),
            sha256Reference(Files.readAllBytes(out)),
            "基线 sha 的口径必须是「TSV 落盘字节」本身",
        )
    }

    // -------------------------------------------------------------------------
    // 2. 写读往返与确定性
    // -------------------------------------------------------------------------

    @Test
    fun `写读往返六项读数相等`() {
        val expect = ReachabilityExpect.of(sampleReport())
        val written = ReachabilityExpect.write(dir.resolve("a/b/reachability.expect"), expect)
        assertTrue(Files.isRegularFile(written), "父目录应被自动创建：$written")
        val back = ReachabilityExpect.read(written)
        assertEquals(expect.roots, back.roots)
        assertEquals(expect.reachable, back.reachable)
        assertEquals(expect.dynamicCalls, back.dynamicCalls)
        assertEquals(expect.portability, back.portability)
        assertEquals(expect.persist, back.persist)
        assertEquals(expect.tsvSha256, back.tsvSha256)
        assertEquals(ReachabilityExpect.render(expect), Files.readString(written), "写盘正文必须等于 render")
    }

    @Test
    fun `两次写同一读数文件字节相同`() {
        val expect = ReachabilityExpect.of(sampleReport())
        val first = ReachabilityExpect.write(dir.resolve("e1.expect"), expect)
        val second = ReachabilityExpect.write(dir.resolve("e2.expect"), expect)
        assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second), "同一读数两次写出必须逐字节相同")
        assertEquals(
            sha256Reference(Files.readAllBytes(first)),
            sha256Reference(Files.readAllBytes(second)),
            "同一读数两次写出的文件 sha256 必须相同（确定性的直接判据）",
        )
    }

    @Test
    fun `行尾空白与CRLF与空行都容忍`() {
        val expect = ReachabilityExpect.of(sampleReport())
        val body = ReachabilityExpect.render(expect)
            .replace("\n", "  \r\n") // 行尾空白 + CRLF
            .replaceFirst("version=1  \r\n", "version=1  \r\n\r\n") // 夹一个空行
        val p = dir.resolve("crlf.expect")
        Files.writeString(p, body)
        assertEquals(expect.tsvSha256, ReachabilityExpect.read(p).tsvSha256, "读数本身不该受行尾风格影响")
    }

    // -------------------------------------------------------------------------
    // 3. 对拍：一致 / 单字段漂移 / 全字段漂移的顺序
    // -------------------------------------------------------------------------

    @Test
    fun `读数一致时对拍为空`() {
        val expect = ReachabilityExpect.of(sampleReport())
        assertEquals(emptyList(), ReachabilityExpect.compare(expect, expect))
        // 从文件读回来的（经过文本往返）也必须判一致。
        val back = ReachabilityExpect.read(ReachabilityExpect.write(dir.resolve("same.expect"), expect))
        assertEquals(emptyList(), ReachabilityExpect.compare(expect, back), "文本往返后仍应判一致")
    }

    @Test
    fun `roots 漂移只报 roots 一行`() {
        val expect = ReachabilityExpect.of(sampleReport())
        val drifted = ReachabilityExpect(
            expect.roots + 1, expect.reachable, expect.dynamicCalls, expect.portability, expect.persist,
            expect.tsvSha256,
        )
        val drift = ReachabilityExpect.compare(expect, drifted)
        assertEquals(listOf("reachability 基线不一致：roots 期望 3 实际 4"), drift)
        assertEquals(1, drift.size, "一条漂移只占一行，不许把其它字段一起列出来")
    }

    @Test
    fun `tsvSha256 漂移只报 tsvSha256 一行`() {
        val expect = ReachabilityExpect.of(sampleReport())
        val flipped = expect.tsvSha256.let { it.dropLast(1) + if (it.last() == '0') '1' else '0' }
        val drifted = ReachabilityExpect(
            expect.roots, expect.reachable, expect.dynamicCalls, expect.portability, expect.persist, flipped,
        )
        assertEquals(
            listOf("reachability 基线不一致：tsvSha256 期望 ${expect.tsvSha256} 实际 $flipped"),
            ReachabilityExpect.compare(expect, drifted),
        )
    }

    @Test
    fun `全字段漂移按固定字段顺序逐行输出`() {
        val expect = ReachabilityExpect(1, 2, 3, 4, 5, "0".repeat(64))
        val drifted = ReachabilityExpect(11, 12, 13, 14, 15, "f".repeat(64))
        val drift = ReachabilityExpect.compare(expect, drifted)
        assertEquals(6, drift.size, "六个字段各一行")
        assertEquals(
            listOf("roots", "reachable", "dynamicCalls", "portability", "persist", "tsvSha256"),
            drift.map { it.substringAfter("不一致：").substringBefore(' ') },
            "字段顺序固定（契约 S1），实为：\n${drift.joinToString("\n")}",
        )
        assertEquals("reachability 基线不一致：roots 期望 1 实际 11", drift[0])
        assertEquals("reachability 基线不一致：tsvSha256 期望 ${"0".repeat(64)} 实际 ${"f".repeat(64)}", drift[5])
    }

    // -------------------------------------------------------------------------
    // 4. 坏基线：全部拒绝，且拒绝得可读
    // -------------------------------------------------------------------------

    @Test
    fun `缺字段按固定顺序先报`() {
        val good = ReachabilityExpect.render(ReachabilityExpect.of(sampleReport()))
        val cases = mapOf(
            "缺 version" to good.lines().filterNot { it.startsWith("version=") }.joinToString("\n"),
            "缺 persist" to good.lines().filterNot { it.startsWith("persist=") }.joinToString("\n"),
            "缺 tsvSha256" to good.lines().filterNot { it.startsWith("tsvSha256=") }.joinToString("\n"),
        )
        val expectedField = mapOf("缺 version" to "version", "缺 persist" to "persist", "缺 tsvSha256" to "tsvSha256")
        for ((tag, body) in cases) {
            val p = writeRaw("missing-${tag.hashCode()}.expect", body + "\n")
            val e = assertFailsWith(IllegalStateException::class, "缺字段未被拒：$tag") { ReachabilityExpect.read(p) }
            assertContains(e.message!!, "缺字段 ${expectedField[tag]}", message = "报错要点名缺哪个字段：$tag ⇒ ${e.message}")
        }
    }

    @Test
    fun `版本不认识与非法计数与非法sha都拒`() {
        val good = ReachabilityExpect.of(sampleReport())
        val shapes = listOf(
            "version=2" to ReachabilityExpect.render(good).replace("version=1", "version=2"),
            "roots 非整数" to ReachabilityExpect.render(good).replace("roots=3", "roots=3x"),
            "persist 空值" to ReachabilityExpect.render(good).replace("persist=1", "persist="),
            "sha 63 位" to ReachabilityExpect.render(good).replace(good.tsvSha256, good.tsvSha256.dropLast(1)),
            "sha 大写" to ReachabilityExpect.render(good).replace(good.tsvSha256, good.tsvSha256.uppercase()),
            "sha 含非 hex" to ReachabilityExpect.render(good).replace(good.tsvSha256, "g" + good.tsvSha256.drop(1)),
            "未知键" to ReachabilityExpect.render(good) + "extra=1\n",
            "重复键" to ReachabilityExpect.render(good) + "roots=3\n",
            "不是 key=value" to ReachabilityExpect.render(good) + "garbage\n",
        )
        for ((tag, body) in shapes) {
            val p = writeRaw("bad-${tag.hashCode()}.expect", body)
            val e = assertFailsWith(IllegalStateException::class, "坏基线未被拒：$tag") { ReachabilityExpect.read(p) }
            assertFalse(
                e.message!!.contains("Exception") || e.message!!.contains("malformed", ignoreCase = true),
                "报错不许泄 JVM 内部异常名（$tag ⇒ ${e.message}）",
            )
        }
    }

    @Test
    fun `缺文件与目录都按不是文件拒`() {
        val missing = assertFailsWith(IllegalStateException::class) {
            ReachabilityExpect.read(dir.resolve("nope.expect"))
        }
        assertContains(missing.message!!, "可达性基线不是文件")
        val asDir = Files.createDirectory(dir.resolve("as-dir"))
        val onDir = assertFailsWith(IllegalStateException::class) { ReachabilityExpect.read(asDir) }
        assertContains(onDir.message!!, "可达性基线不是文件")
    }

    private fun writeRaw(name: String, body: String): Path {
        val p = dir.resolve(name)
        Files.createDirectories(p.parent)
        Files.write(p, body.toByteArray(Charsets.UTF_8))
        return p
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray, message: String) {
        assertEquals(expected.toList(), actual.toList(), message)
    }
}
