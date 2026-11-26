package com.eratw.port.core.patch

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.modapi.BuiltinPhase
import com.eratw.port.modapi.JobCatchUp as ModJobCatchUp
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.JobTemplateKey
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseDecl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M6-3 声明 / Manifest 契约闸（§21.1，ABI-10 Candidate）。
 *
 * 钉住：mod-api 与 core 两侧 `JobTemplateDecl` / `JobTemplateDeclaration` 的边界（空键、
 * priority<0、phase 空、REPLAY 缺 maxCatchUp、非 REPLAY 带 maxCatchUp）；RT2MANIFEST/3
 * 的 phase-jobs 段 B0/B1/B2 混合往返、声明序保真、字节确定；/1 与 /2 明示拒载文案；
 * 适配器翻译与未声明相位拒；B2 构造期不变量归一成格式异常。
 */
class JobTemplateManifestTest {

    // -----------------------------------------------------------------------
    // 判据 1：声明形态边界（mod-api + core 双侧）
    // -----------------------------------------------------------------------
    @Test
    fun `mod_api JobTemplateDecl 边界`() {
        assertThrows<IllegalArgumentException> { JobTemplateDecl(templateKey = "") }
        assertThrows<IllegalArgumentException> { JobTemplateDecl(templateKey = " ", phase = "DAY_END") }
        assertThrows<IllegalArgumentException> { JobTemplateDecl(templateKey = "T", phase = " ") }
        assertThrows<IllegalArgumentException> { JobTemplateDecl(templateKey = "T", priority = -1) }
        assertThrows<IllegalArgumentException> {
            JobTemplateDecl(templateKey = "T", catchUp = ModJobCatchUp.REPLAY)
        }
        assertThrows<IllegalArgumentException> {
            JobTemplateDecl(templateKey = "T", catchUp = ModJobCatchUp.REPLAY, maxCatchUp = 0L)
        }
        assertThrows<IllegalArgumentException> {
            JobTemplateDecl(templateKey = "T", catchUp = ModJobCatchUp.MISS_ONCE, maxCatchUp = 2L)
        }
        // 合法默认与 REPLAY 正上限。
        val ok = JobTemplateDecl("T")
        assertEquals("DAY_END", ok.phase)
        assertEquals(ModJobCatchUp.MISS_ONCE, ok.catchUp)
        assertEquals(null, ok.maxCatchUp)
        assertEquals(3L, JobTemplateDecl("T", "DAY_END", 0, ModJobCatchUp.REPLAY, 3L).maxCatchUp)
    }

    @Test
    fun `core JobTemplateDeclaration 边界`() {
        assertThrows<IllegalArgumentException> { JobTemplateDeclaration(templateKey = "") }
        assertThrows<IllegalArgumentException> { JobTemplateDeclaration(templateKey = "T", phase = "") }
        assertThrows<IllegalArgumentException> { JobTemplateDeclaration(templateKey = "T", priority = -1) }
        assertThrows<IllegalArgumentException> {
            JobTemplateDeclaration(templateKey = "T", catchUp = JobCatchUp.REPLAY)
        }
        assertThrows<IllegalArgumentException> {
            JobTemplateDeclaration(templateKey = "T", catchUp = JobCatchUp.REPLAY, maxCatchUp = 0L)
        }
        assertThrows<IllegalArgumentException> {
            JobTemplateDeclaration(templateKey = "T", catchUp = JobCatchUp.MISS_SKIP, maxCatchUp = 1L)
        }
        assertEquals("MISS_ONCE", JobTemplateDeclaration("T").catchUp.name)
        assertEquals("DO_DAY", JobTemplateDeclaration("T", "do_day").normalizedPhase())
    }

    // -----------------------------------------------------------------------
    // 判据 2：/3 phase-jobs B0/B1/B2 混合往返、声明序、字节确定
    // -----------------------------------------------------------------------
    private fun declarationDescriptor(): ModuleDescriptor = ModuleDescriptor(
        packageId = "mod.jobs",
        phaseDeclarations = listOf(
            PhaseDeclaration("Z_PHASE", priority = 1),
            PhaseDeclaration("A_PHASE"),
        ),
        jobDeclarations = listOf(
            JobDeclaration("Z_JOB", "Z_PHASE", firstDay = 0L, everyDays = 2L, priority = 3),
            JobDeclaration("A_JOB", "DAY_END", firstDay = 5L, everyDays = null),
        ),
        jobTemplateDeclarations = listOf(
            JobTemplateDeclaration("Z_TPL", "Z_PHASE", priority = 2),
            JobTemplateDeclaration("A_TPL", "DAY_START", catchUp = JobCatchUp.MISS_SKIP),
            JobTemplateDeclaration(
                "M_TPL", "A_PHASE", priority = 4,
                catchUp = JobCatchUp.REPLAY, maxCatchUp = 7L,
            ),
        ),
    )

    @Test
    fun `Manifest3 phase_jobs B0B1B2混合往返_声明序保真_字节确定`() {
        val d = declarationDescriptor()
        val b1 = ManifestCodec.encode(d)
        val b2 = ManifestCodec.encode(d)
        assertTrue(b1.contentEquals(b2))
        val back = ManifestCodec.decode(b1)
        assertEquals(d.phaseDeclarations, back.phaseDeclarations)
        assertEquals(d.jobDeclarations, back.jobDeclarations)
        assertEquals(d.jobTemplateDeclarations, back.jobTemplateDeclarations)
        assertTrue(b1.contentEquals(ManifestCodec.encode(back)))

        val text = String(b1, Charsets.UTF_8)
        val lines = text.split("\n")
        assertEquals("RT2MANIFEST/3", lines[0])
        val phaseJobStart = lines.indexOf("@phase-jobs")
        assertTrue(phaseJobStart >= 0)
        val body = run {
            val end = (phaseJobStart + 1 until lines.size).first { lines[it] == "/" }
            lines.subList(phaseJobStart + 1, end)
        }
        assertTrue(body.any { it == "B0" })
        assertTrue(body.any { it == "B1" })
        assertTrue(body.any { it == "B2" })
        // 段内计数 = 2 Phase + 2 Job + 3 Template。
        assertEquals("I7", body.first())
        // 声明序保真：Z 在 A 前。
        val zIndex = body.indexOf("S" + hex("Z_TPL"))
        val aIndex = body.indexOf("S" + hex("A_TPL"))
        assertTrue(zIndex in 0 until aIndex)
    }

    // -----------------------------------------------------------------------
    // 判据 3：/1 与 /2 明示拒载文案
    // -----------------------------------------------------------------------
    @Test
    fun `Manifest1 与 Manifest2 明示拒载并给迁移提示`() {
        for (old in listOf("RT2MANIFEST/1", "RT2MANIFEST/2")) {
            val lines = encodeLines(declarationDescriptor())
            lines[0] = old
            val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
            val m = ex.message!!
            assertTrue(m.contains("$old 已拒载"))
            // /8（write-grants）双读上线后拒载文案改成「/3 至 /8」（ManifestCodec.kt 的 magic 闸），迁移提示逐字跟改；
            // 「文案必须列全实收版本」的自证判据在 ManifestCodecTest.legacyRejectionCopyListsEveryVersionTheDecoderAccepts。
            assertTrue(m.contains("请用 /3 至 /8 的编码器重出 Manifest"))
            assertTrue(m.contains("B2"))
            assertTrue(m.contains("RT2MANIFEST/3"))
        }
    }

    // -----------------------------------------------------------------------
    // 判据 4：适配器翻译与未声明相位拒
    // -----------------------------------------------------------------------
    @Test
    fun `adapter 翻译 jobTemplates_包内声明相位可引用`() {
        val manifest = ModManifest(
            packageId = "demo.jobs",
            phases = listOf(PhaseDecl("FAMILY_TICK")),
            jobTemplates = listOf(
                JobTemplateDecl("APPOINTMENT", "FAMILY_TICK", priority = 2),
                JobTemplateDecl("GIFT", "DAY_END", catchUp = ModJobCatchUp.REPLAY, maxCatchUp = 3L),
            ),
        )
        val descriptor = ModDeclarationAdapter.toDescriptor(manifest)
        assertEquals(2, descriptor.jobTemplateDeclarations.size)
        assertEquals("APPOINTMENT", descriptor.jobTemplateDeclarations[0].templateKey)
        assertEquals("FAMILY_TICK", descriptor.jobTemplateDeclarations[0].phase)
        assertEquals(JobCatchUp.REPLAY, descriptor.jobTemplateDeclarations[1].catchUp)
        assertEquals(3L, descriptor.jobTemplateDeclarations[1].maxCatchUp)
    }

    @Test
    fun `adapter 未声明相位拒`() {
        val manifest = ModManifest(
            packageId = "demo.bad",
            jobTemplates = listOf(JobTemplateDecl("APPOINTMENT", "NOT_DECLARED")),
        )
        val ex = assertThrows<IllegalArgumentException> { ModDeclarationAdapter.toDescriptor(manifest) }
        assertTrue(ex.message!!.contains("未声明的相位"))
        // 固定相位始终可引用。
        val ok = ModDeclarationAdapter.toDescriptor(
            ModManifest(
                packageId = "demo.ok",
                jobTemplates = listOf(JobTemplateDecl(JobTemplateKey("T"), BuiltinPhase.ACTION)),
            ),
        )
        assertEquals("ACTION", ok.jobTemplateDeclarations.single().phase)
    }

    // -----------------------------------------------------------------------
    // 判据 5：B2 构造期不变量与非法枚举经 guarded 归一成格式异常
    // -----------------------------------------------------------------------
    @Test
    fun `B2 非REPLAY带maxCatchUp与非法枚举被拒`() {
        run {
            val lines = encodeLines(declarationDescriptor())
            val start = lines.indexOf("@phase-jobs")
            val b2 = (start until lines.size).first { lines[it] == "B2" }
            // B2 布局：tag, key, phase, priority, catchUp(E), maxCatchUp(I/Z)。
            // 首条模板是 MISS_ONCE；给它塞一个 maxCatchUp ⇒ 非 REPLAY 带上限，构造期拒绝。
            lines[b2 + 5] = "I3"
            val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
            assertTrue(ex.message!!.contains("JobTemplateDeclaration"))
        }
        run {
            val lines = encodeLines(declarationDescriptor())
            val start = lines.indexOf("@phase-jobs")
            val b2 = (start until lines.size).first { lines[it] == "B2" }
            lines[b2 + 4] = "E" + hex("NOPE")
            val ex = assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
            assertTrue(ex.message!!.contains("非法枚举"))
        }
        run {
            // B2 缺段内 maxCatchUp 行（把 REPLAY 的上限行删掉）⇒ 截断或构造失败。
            val lines = encodeLines(declarationDescriptor())
            val start = lines.indexOf("@phase-jobs")
            val b2s = (start until lines.size).filter { lines[it] == "B2" }
            val replayB2 = b2s[2]
            lines.removeAt(replayB2 + 5)
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(toBytes(lines)) }
        }
    }

    private fun encodeLines(d: ModuleDescriptor): MutableList<String> =
        String(ManifestCodec.encode(d), Charsets.UTF_8).split("\n").dropLast(1).toMutableList()

    private fun toBytes(lines: List<String>): ByteArray =
        (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    private fun hex(s: String): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xff
            sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
        }
        return sb.toString()
    }
}
