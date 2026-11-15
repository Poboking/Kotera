package com.eratw.port.vmdbg

import com.eratw.port.core.snapshot.RuntimeSnapshot
import com.eratw.port.core.snapshot.RuntimeSnapshot.Value
import com.eratw.port.core.snapshot.RuntimeSnapshotSections
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `snapshot validate|expand|diff` 的 CLI 面用例（MODERN-T3 R1）：用 [MiniCorpus] 产**真快照**
 * 跑三个子命令，并钉死「分析器不装配会话」—— 分析路径的入参只有快照文件，不读
 * `--erbc/--game`，也不建沙箱（源码哨兵用例）。
 *
 * 退出码口径：0 PASS / 1 用法 / 2 主文件缺失 / 3 INVALID（CONTRACT-MODERN-T3-R1 §3.2）。
 */
class CmdSnapAnalyzeTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-snap-analyze-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun assemble(): Assembler.Assembled =
        Assembler.assemble(Args.parse(MiniCorpus.args(built, work).toList()))

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val code = Cli.run(
            arrayOf(*args),
            out = PrintStream(buf, true, Charsets.UTF_8),
            err = PrintStream(ebuf, true, Charsets.UTF_8),
        )
        return code to buf.toString(Charsets.UTF_8) + ebuf.toString(Charsets.UTF_8)
    }

    /** 用既有 `snapshot` 落一份真快照（主文件 + sidecar），返回主文件路径。 */
    private fun captureSnapshot(): Path {
        val snap = work.resolve("snapshot.dbg")
        val code = CmdSnap.snapshot(assemble(), snap, PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(snap), "快照主文件应落盘: $snap")
        assertTrue(Files.isRegularFile(CmdSnap.sidecarPath(snap)), "sidecar 应落盘")
        return snap
    }

    private fun syntheticSidecar(rngLen: Int): String = buildString {
        append("{\"format\":1")
        append(",\"createdAt\":\"2026-11-08T10:00:00+08:00\"")
        append(",\"commit\":\"\"")
        append(",\"randSeed\":20260226")
        append(",\"rngState\":[").append((0 until rngLen).joinToString(",") { "0" }).append(']')
        append(",\"consoleTail\":[]")
        append(",\"funcStack\":[]")
        append(",\"funcDepth\":0}")
    }

    private fun binary(magic: Long, version: Int): ByteArray {
        val b = ByteArray(40)
        for (k in 0 until 8) b[k] = ((magic ushr (8 * k)) and 0xFFL).toByte()
        for (k in 0 until 4) b[8 + k] = ((version ushr (8 * k)) and 0xFF).toByte()
        return b
    }

    // ------------------------------------------------------------------
    // v1 夹具（MODERN-R11-R1SNAP）：手工构造分节，不经会话采集
    // ------------------------------------------------------------------

    private fun v1Section(name: String, vararg fields: Pair<String, Value>): RuntimeSnapshot.Section =
        RuntimeSnapshot.Section(name, linkedMapOf(*fields))

    private fun v1Bytes(savedUserVars: Long = 5): ByteArray = RuntimeSnapshot.encode(
        RuntimeSnapshot.Snapshot(
            RuntimeSnapshot.Header(
                buildStamp = "unit-v1",
                createdAt = "2026-11-09T00:00:00Z",
                saveIdentity = "slot-1",
            ),
            listOf(
                v1Section(
                    RuntimeSnapshotSections.VM,
                    "callDepth" to Value.Int64(2),
                    "pc" to Value.Text("unavailable"),
                ),
                v1Section(
                    RuntimeSnapshotSections.VARS,
                    "bytes" to Value.Blob(byteArrayOf(1, 2, 3, 4)),
                    "encoding" to Value.Text("binary"),
                    "savedUserVars" to Value.Int64(savedUserVars),
                ),
                v1Section(
                    RuntimeSnapshotSections.RNG,
                    "state" to Value.IntList(List(625) { it.toLong() }),
                    "cursor" to Value.Int64(624),
                ),
                v1Section(
                    RuntimeSnapshotSections.DISPLAY,
                    "tail" to Value.TextList(listOf("hello")),
                    "lineCount" to Value.Int64(3),
                ),
                v1Section(
                    RuntimeSnapshotSections.RESOURCES,
                    "gfx.entries" to Value.Int64(0),
                ),
                v1Section(
                    RuntimeSnapshotSections.EVENTS,
                    "lines" to Value.TextList(listOf("evt-1")),
                    "count" to Value.Int64(1),
                ),
            ),
        ),
    )

    private fun writeV1(name: String, bytes: ByteArray): Path {
        Files.createDirectories(work)
        val p = work.resolve(name)
        Files.write(p, bytes)
        return p
    }

    /** 解压 v1 外壳、篡改解压后正文、重压：保留魔数以便仍被判为 v1，制造内部坏档。 */
    private fun mutateV1(encoded: ByteArray, block: (ByteArray) -> Unit): ByteArray {
        val raw = GZIPInputStream(ByteArrayInputStream(encoded)).use { it.readBytes() }
        block(raw)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // validate
    // ------------------------------------------------------------------

    @Test
    fun validateRealSnapshotPasses() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("container=text" in text, text)
        assertTrue("mainSha256=" in text, text)
        assertTrue("rngState.len=625" in text, text)
        assertTrue("funcDepth=" in text, text)
        assertTrue("sidecar=" in text && "sidecar=absent" !in text, text)
    }

    @Test
    fun validateMissingMainIsExit2() {
        val (code, text) = run("snapshot", "validate", tmp.resolve("nope.dbg").toString())
        assertEquals(2, code, text)
        assertTrue("status=MISSING" in text, text)
        assertTrue("快照主文件不存在" in text, text)
    }

    @Test
    fun validateMissingSidecarIsOnlyWarning() {
        val snap = captureSnapshot()
        Files.delete(CmdSnap.sidecarPath(snap))
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("sidecar=absent" in text, text)
        assertTrue("warning=sidecar 缺席" in text, text)
    }

    @Test
    fun validateBrokenSidecarIsExit3() {
        val snap = captureSnapshot()
        Files.writeString(CmdSnap.sidecarPath(snap), "{not json", Charsets.UTF_8)
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("sidecar 不可解析" in text, text)
    }

    @Test
    fun validateRngLengthViolationIsExit3() {
        val main = work.resolve("bad-rng.dbg")
        Files.createDirectories(work)
        Files.writeString(main, "12345\n1|2\n", Charsets.UTF_8)
        Files.writeString(CmdSnap.sidecarPath(main), syntheticSidecar(624), Charsets.UTF_8)
        val (code, text) = run("snapshot", "validate", main.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("rngState 长度=624" in text, text)
    }

    @Test
    fun validateBinaryVersionMismatchIsExit3() {
        val main = work.resolve("bad-version.dbg")
        Files.createDirectories(work)
        Files.write(main, binary(SnapSidecar.BINARY_MAGIC, 1807))
        Files.writeString(CmdSnap.sidecarPath(main), syntheticSidecar(0), Charsets.UTF_8)
        val (code, text) = run("snapshot", "validate", main.toString())
        assertEquals(3, code, text)
        assertTrue("container=binary" in text, text)
        assertTrue("版本=1807" in text, text)
    }

    // ------------------------------------------------------------------
    // expand
    // ------------------------------------------------------------------

    @Test
    fun expandRealSnapshotPrintsSections() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "expand", snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("vars=not-expanded(offline; use restore)" in text, text)
        assertTrue("--- func stack (top first) ---" in text, text)
        assertTrue("--- console tail (oldest first) ---" in text, text)
        assertTrue("rngState.len=625" in text, text)
        assertTrue("rngState.digest=" in text, text)
        assertTrue("mainSha256=" in text, text)
    }

    @Test
    fun expandOutWritesFileAndPrintsOneLine() {
        val snap = captureSnapshot()
        val target = tmp.resolve("expanded.txt")
        val (code, text) = run("snapshot", "expand", snap.toString(), "--out", target.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS out=" in text, text)
        assertTrue(target.toString() in text, text)
        val written = Files.readString(target, Charsets.UTF_8)
        assertTrue("mainSha256=" in written, written)
        assertTrue("vars=not-expanded" in written, written)
    }

    // ------------------------------------------------------------------
    // diff
    // ------------------------------------------------------------------

    @Test
    fun diffSameSnapshotReportsSame() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "diff", snap.toString(), snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("main.bytesSame=true" in text, text)
        assertTrue("same=true" in text, text)
        assertTrue(text.trimEnd().endsWith("same=true"), text)
    }

    @Test
    fun diffChangedSidecarStillExitsZero() {
        val snapA = captureSnapshot()
        val snapB = work.resolve("b.dbg")
        Files.copy(snapA, snapB)
        val sideA = Files.readString(CmdSnap.sidecarPath(snapA), Charsets.UTF_8)
        val sideB = sideA.replace("\"randSeed\":20260226", "\"randSeed\":7")
        assertTrue(sideB != sideA, "randSeed 改写应生效")
        Files.writeString(CmdSnap.sidecarPath(snapB), sideB, Charsets.UTF_8)

        val (code, text) = run("snapshot", "diff", snapA.toString(), snapB.toString())
        assertEquals(0, code, text)
        assertTrue("main.bytesSame=true" in text, text)
        assertTrue("randSeed.a=20260226 randSeed.b=7 same=false" in text, text)
        assertTrue(text.trimEnd().endsWith("same=false"), text)
    }

    @Test
    fun diffMissingSecondPathIsUsageError() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "diff", snap.toString())
        assertEquals(1, code, text)
        assertTrue("diff 缺少第二个快照路径" in text, text)
    }

    @Test
    fun diffMissingMainIsExit2() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "diff", snap.toString(), tmp.resolve("nope.dbg").toString())
        assertEquals(2, code, text)
        assertTrue("status=MISSING" in text, text)
    }

    // ------------------------------------------------------------------
    // v1 容器（MODERN-R11-R1SNAP S1 / S2）
    // ------------------------------------------------------------------

    @Test
    fun v0SnapshotStillUsesSidecarPathNotV1() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(0, code, text)
        assertTrue("container=rtsnap1" !in text, "v0 主文件不得被误判进 v1 分支: $text")
        assertTrue("status=PASS" in text, text)
    }

    @Test
    fun validateV1PassesWithSectionFacts() {
        val p = writeV1("v1.rtsnap.gz", v1Bytes())
        val (code, text) = run("snapshot", "validate", p.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("container=rtsnap1" in text, text)
        assertTrue("version=1" in text, text)
        assertTrue("sections=vm,vars,rng,display,resources,events" in text, text)
        assertTrue("section.vars.fields=3" in text, text)
    }

    @Test
    fun validateV1WrongVersionIsExit3NotCrash() {
        val bad = mutateV1(v1Bytes()) { raw ->
            raw[7] = 2
            raw[8] = 0
            raw[9] = 0
            raw[10] = 0
        }
        val p = writeV1("v1-bad-version.rtsnap.gz", bad)
        val (code, text) = run("snapshot", "validate", p.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("problem=" in text, text)
    }

    @Test
    fun validateV1BadHeaderLengthIsExit3NotCrash() {
        val bad = mutateV1(v1Bytes()) { raw ->
            raw[11] = 0x7F
            raw[12] = 0x7F
            raw[13] = 0x7F
            raw[14] = 0x7F
        }
        val p = writeV1("v1-bad-header.rtsnap.gz", bad)
        val (code, text) = run("snapshot", "validate", p.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("headerLen" in text, text)
    }

    @Test
    fun expandV1PrintsSectionsAndVarsEncoding() {
        val p = writeV1("v1.rtsnap.gz", v1Bytes())
        val (code, text) = run("snapshot", "expand", p.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("container=rtsnap1" in text, text)
        assertTrue("--- section vars" in text, text)
        assertTrue("vars.bytes=" in text, text)
        assertTrue("vars.encoding=binary" in text, text)
        assertTrue("display.lineCount=3" in text, text)
    }

    @Test
    fun expandV1OutWritesFile() {
        val p = writeV1("v1.rtsnap.gz", v1Bytes())
        val target = tmp.resolve("v1-expanded.txt")
        val (code, text) = run("snapshot", "expand", p.toString(), "--out", target.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS out=" in text, text)
        assertTrue("--- section vars" in Files.readString(target, Charsets.UTF_8))
    }

    @Test
    fun diffV1SameReportsConsistent() {
        val p = writeV1("v1.rtsnap.gz", v1Bytes())
        val (code, text) = run("snapshot", "diff", p.toString(), p.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("一致" in text, text)
        assertTrue("same=true" in text, text)
    }

    @Test
    fun diffV1OneVariableDiffReportsFirstField() {
        val pa = writeV1("a.rtsnap.gz", v1Bytes(savedUserVars = 5))
        val pb = writeV1("b.rtsnap.gz", v1Bytes(savedUserVars = 6))
        val (code, text) = run("snapshot", "diff", pa.toString(), pb.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("first.section=vars" in text, text)
        assertTrue("first.path=savedUserVars" in text, text)
        assertTrue("same=false" in text, text)
    }

    @Test
    fun diffV1BadSideIsExit3NotCrash() {
        val pa = writeV1("a.rtsnap.gz", v1Bytes())
        val bad = mutateV1(v1Bytes()) { raw -> raw[7] = 2 }
        val pb = writeV1("b-bad.rtsnap.gz", bad)
        val (code, text) = run("snapshot", "diff", pa.toString(), pb.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("problem.b=" in text, text)
    }

    // ------------------------------------------------------------------
    // 离线铁律（源码哨兵）
    // ------------------------------------------------------------------

    @Test
    fun analyzerSourcesNeverAssembleSession() {
        for (name in listOf("SnapSidecar.kt", "CmdSnapAnalyze.kt")) {
            val candidates = listOf(
                File("src/main/kotlin/com/eratw/port/vmdbg/$name"),
                File("tools/vmdbg/src/main/kotlin/com/eratw/port/vmdbg/$name"),
            )
            val file = candidates.firstOrNull { it.isFile }
                ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
            val src = file.readText(Charsets.UTF_8)
            // 钉「调用」而不是字样：KDoc 里引用 [Assembler.assemble] 说明「不调它」是允许的，
            // 真调用一定带 '('。
            assertTrue("Assembler.assemble(" !in src, "$name 属离线分析器，不得装配会话（出现 Assembler.assemble 调用）")
            assertTrue("GameSession" !in src, "$name 不得依赖 GameSession")
        }
    }
}
