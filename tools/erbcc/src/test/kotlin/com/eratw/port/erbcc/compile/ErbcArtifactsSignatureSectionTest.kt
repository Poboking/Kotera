package com.eratw.port.erbcc.compile

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.BaseSignatureCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * P15S：base 签名段随标准产物落盘（余量三写侧）+ 装载侧读回的生产链证明。
 *
 * 覆盖链：`CorpusCompile.compile` → `CompileResult.signatures`（M2-5 导出）→
 * `ErbcArtifacts.write(signatures = …)`（cmdCompile 生产形态）→ meta 尾部旁路块 →
 * `BootMetadata.read`（core-vm 读侧剥离容差）→ `BaseSignatureCatalog.parse`
 * （消费方 fail-closed 判读）。关态不变式：缺省空表 ⇒ meta 字节与本参数加入前
 * 逐字节相同（旧调用点/旧档零变化）。读侧单侧 fail-closed 边界在 core-vm
 * `BootMetaSignatureTailTest`。
 */
class ErbcArtifactsSignatureSectionTest {

    /** mini 语料（照 ErbccAudit2Test 口径）：两个用户函数，其中一个带形参。 */
    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(root.resolve("00_const.ERH"), "#DIM CONST G_CONST = 7\n".toByteArray(Charsets.UTF_8))
        Files.write(
            root.resolve("01_main.ERB"),
            """
            @MINI_P(ARG, ARG:1)
            PRINTFORML P={ARG:1}

            @MINI_MAIN
            CALL MINI_P(1, 2)
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    private fun compile(dir: Path): CompileResult {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(5)}")
        assertNotNull(r.program)
        return r
    }

    private fun writeArtifacts(
        dir: Path,
        result: CompileResult,
        signatures: Map<String, String>,
        outName: String = "out",
    ): WriteReport = ErbcArtifacts.write(
        dir.resolve(outName), result.program!!, dir.resolve("corpus"),
        result.sourceFiles, result.stats, result.bootMetadata,
        signatures = signatures,
    )

    @Test
    fun `cmdCompile 形态落盘 meta 尾部带段 且 manifest 登记与实际字节一致`(@TempDir dir: Path) {
        val result = compile(dir)
        assertTrue(result.signatures.isNotEmpty(), "mini 语料必须导出签名（CompileResult → 落盘接线的输入前提）")
        val report = writeArtifacts(dir, result, result.signatures)
        val metaText = Files.readString(report.metaPath!!)
        // 段头紧跟 end 行（生产写侧唯一形态），段头计数 = 表大小。
        val tail = metaText.substringAfterLast("end\n")
        assertTrue(tail.startsWith("${SignatureExport.SECTION} ${result.signatures.size}\n"), tail.take(80))
        // manifest 的 meta HashEntry 与实际字节逐项一致（CMP-74 对账口径）。
        assertEquals(report.meta!!.sha256, ErbcArtifacts.sha256Hex(Files.readAllBytes(report.metaPath!!)))
        assertEquals(report.meta!!.bytes, Files.size(report.metaPath!!))
    }

    @Test
    fun `签名段按键排序 乱序输入 map 两次落盘字节相同`(@TempDir dir: Path) {
        val result = compile(dir)
        val reversed = LinkedHashMap<String, String>()
        result.signatures.entries.sortedByDescending { it.key }.forEach { reversed[it.key] = it.value }
        val a = writeArtifacts(dir, result, reversed, outName = "o1")
        val b = writeArtifacts(dir, result, reversed, outName = "o2")
        assertTrue(Files.readAllBytes(a.metaPath!!).contentEquals(Files.readAllBytes(b.metaPath!!)), "同输入两次落盘逐字节相同")
        // 段内记录行按键升序（encodeSection 排序），与输入 map 顺序无关。
        val recordKeys = Files.readString(a.metaPath!!)
            .lineSequence()
            .filter { it.startsWith("${SignatureExport.RECORD} ") }
            .map { it.removePrefix("${SignatureExport.RECORD} ").substringBefore(' ') }
            .toList()
        assertEquals(recordKeys.sorted(), recordKeys, "记录行必须按键升序")
    }

    @Test
    fun `缺省与空表字节逐字节相同且不含段 关态不变式`(@TempDir dir: Path) {
        val result = compile(dir)
        // 旧调用点（不传 signatures，6 个位置实参）与新调用点空表：字节必须一致。
        val legacy = ErbcArtifacts.write(
            dir.resolve("legacy"), result.program!!, dir.resolve("corpus"),
            result.sourceFiles, result.stats, result.bootMetadata,
        )
        val empty = writeArtifacts(dir, result, emptyMap(), outName = "empty")
        assertTrue(
            Files.readAllBytes(legacy.metaPath!!).contentEquals(Files.readAllBytes(empty.metaPath!!)),
            "旧调用点与空表字节逐字节相同",
        )
        val legacyText = Files.readString(legacy.metaPath!!)
        assertFalse(legacyText.contains("${SignatureExport.SECTION} "), "关态不得出现段头")
        // 关态字节 = BootMetaWriter 不带签名段的历史口径（接线前逐字节不变）。
        assertEquals(BootMetaWriter.text(result.bootMetadata, legacy.artifact.sha256), legacyText)
    }

    @Test
    fun `带段产物经 BootMetadata 读回 模型与无段版本一致`(@TempDir dir: Path) {
        val result = compile(dir)
        val withSection = BootMetadata.read(writeArtifacts(dir, result, result.signatures, "with").metaPath!!)
        val without = BootMetadata.read(writeArtifacts(dir, result, emptyMap(), "without").metaPath!!)
        assertEquals(without.encodeText(), withSection.encodeText(), "旁路块不进模型：读回重编码与无段一致")
    }

    @Test
    fun `BaseSignatureCatalog parse 解出的表与传入表相等`(@TempDir dir: Path) {
        val result = compile(dir)
        val metaText = Files.readString(writeArtifacts(dir, result, result.signatures, "with").metaPath!!)
        assertEquals(result.signatures, BaseSignatureCatalog.parse(metaText))
    }

    @Test
    fun `签名段覆盖 CompileResult 全部导出键`(@TempDir dir: Path) {
        val result = compile(dir)
        val metaText = Files.readString(writeArtifacts(dir, result, result.signatures).metaPath!!)
        val recordKeys = metaText
            .lineSequence()
            .filter { it.startsWith("${SignatureExport.RECORD} ") }
            .map { it.removePrefix("${SignatureExport.RECORD} ").substringBefore(' ') }
            .toSet()
        assertEquals(result.signatures.keys, recordKeys, "段内键集必须与 CompileResult 导出键集一致")
    }

    @Test
    fun `cmdCompile 生产链落盘 meta 带签名段`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val out = dir.resolve("out-cli")
        // cmdCompile 收到的是已剥掉 compile 子命令的参数表（照 EngineProfileMiniCorpusTest 口径）。
        // 本用例锁「Main → ErbcArtifacts.write 的 signatures 传参」这最后一跳：反向证明②摘掉
        // 该实参时，只有本用例红（直接调 write 的用例不受影响）。
        val code = com.eratw.port.erbcc.cmdCompile(listOf("--corpus", corpus.toString(), "--out", out.toString()))
        assertEquals(0, code, "cmdCompile 应退出 0")
        val metaText = Files.readString(out.resolve("erbc.v1").resolve(BootMetaWriter.FILE_NAME))
        val tail = metaText.substringAfterLast("end\n")
        assertTrue(
            tail.startsWith("${SignatureExport.SECTION} "),
            "生产 cmdCompile 落盘的 meta 必须带签名段；尾部：${tail.take(80)}",
        )
    }
}
