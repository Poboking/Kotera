package com.eratw.port.core.loader

import com.eratw.port.core.patch.BaseSignatureCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * P15S：`BootMetadata.read` 对尾部签名段（M2-5 旁路块）的流式剥离容差 + fail-closed 边界。
 *
 * 语义权威 = `BaseSignatureCatalog`（段语法与 fail-closed 判读）与
 * `BootMetaCodec.SignatureTailFilterReader`（剥离状态机）的 KDoc；写侧 = erbcc
 * `SignatureExport.encodeSection`（段头 `signatures <N>` 紧跟 `end` 行，记录行
 * `signature <KEY> <canonical>` 按键排序）。本类用手拼段文本从读侧单侧证明：
 * 带段旧读链不炸且模型不变、四条 fail-closed 边界照旧拒（不因旁路块弱化）、
 * `parse(text)` 整文入口保持严格。带真实写侧的落盘往返在 erbcc
 * `ErbcArtifactsSignatureSectionTest`（两模块对拍）。
 */
class BootMetaSignatureTailTest {

    /** 最小合法模型：labels + fileByKey（其余字段缺省）；encodeText 以 `end` 行收尾。 */
    private fun minimalMeta(): BootMetadata = BootMetadata(
        labels = mapOf(
            "FN_A" to mapOf("S" to 3),
            "FN_B" to emptyMap(),
        ),
        fileByKey = mapOf("100.ERB" to "100.ERB"),
    )

    /** 手拼规范串（读侧扫描器可解的五要素形态；与 erbcc 写侧 render 同格式）。 */
    private val canonicalA = "SIG1|I,-,r,0|ret:I"

    private val canonicalB = "SIG1|S,-,R,1|ret:S"

    /** 段文本：`signatures <declared>` 段头 + 逐条 `signature <recordBody>` 行。 */
    private fun section(recordBodies: List<String>, declared: Int = recordBodies.size): String =
        buildString {
            append(BaseSignatureCatalog.SECTION).append(' ').append(declared).append('\n')
            recordBodies.forEach { body ->
                append(BaseSignatureCatalog.RECORD).append(' ').append(body).append('\n')
            }
        }

    private fun writeMeta(dir: Path, name: String, text: String): Path {
        val p = dir.resolve(name)
        Files.write(p, text.toByteArray(Charsets.UTF_8))
        return p
    }

    @Test
    fun `带段侧车读回成功 旁路块不进模型且扫描器解出真表`(@TempDir dir: Path) {
        val base = minimalMeta()
        val baseText = base.encodeText()
        // 自检：手拼规范串必须被读侧扫描器认出（不认 = 本测试自身构造坏了）。
        assertNotNull(BaseSignatureCatalog.decode(canonicalA), "canonicalA 必须可解")
        assertNotNull(BaseSignatureCatalog.decode(canonicalB), "canonicalB 必须可解")
        val withSection =
            baseText + section(listOf("FN_A $canonicalA", "FN_B $canonicalB"))
        val read = BootMetadata.read(writeMeta(dir, "program.meta", withSection))
        assertEquals(baseText, read.encodeText(), "读回重编码与无段文本逐字节一致（旁路块不进模型）")
        // 同一文本交给扫描器（生产消费链 = ModChainAssembly 有界尾读 → parse）解出真表。
        val table = BaseSignatureCatalog.parse(withSection)
        assertEquals(mapOf("FN_A" to canonicalA, "FN_B" to canonicalB), table)
    }

    @Test
    fun `空段 signatures 0 读回成功`(@TempDir dir: Path) {
        val base = minimalMeta()
        val withSection = base.encodeText() + section(emptyList(), declared = 0)
        val read = BootMetadata.read(writeMeta(dir, "program.meta", withSection))
        assertEquals(base.encodeText(), read.encodeText())
    }

    @Test
    fun `段体后尾随非 signature 行 既有 end 闸照旧拒`(@TempDir dir: Path) {
        val bad = minimalMeta().encodeText() +
            section(listOf("FN_A $canonicalA")) +
            "labels X 1\n"
        val ex = assertThrows<BootMetaFormatException> {
            BootMetadata.read(writeMeta(dir, "program.meta", bad))
        }
        assertTrue(ex.message!!.contains("end 之后还有记录"), ex.message)
    }

    @Test
    fun `end 与段头夹空行 不容差 严格相邻`(@TempDir dir: Path) {
        val bad = minimalMeta().encodeText() + "\n" + section(listOf("FN_A $canonicalA"))
        val ex = assertThrows<BootMetaFormatException> {
            BootMetadata.read(writeMeta(dir, "program.meta", bad))
        }
        assertTrue(ex.message!!.contains("end 之后还有记录"), ex.message)
    }

    @Test
    fun `正文出现段头 照旧透传由状态机按期望 end 拒`(@TempDir dir: Path) {
        val baseText = minimalMeta().encodeText()
        val bad = baseText.removeSuffix("end\n") + section(emptyList(), declared = 3) + "end\n"
        val ex = assertThrows<BootMetaFormatException> {
            BootMetadata.read(writeMeta(dir, "program.meta", bad))
        }
        assertTrue(ex.message!!.contains("期望 end"), ex.message)
    }

    @Test
    fun `parse 整文入口保持严格 不认旁路块`() {
        val withSection = minimalMeta().encodeText() + section(listOf("FN_A $canonicalA"))
        val ex = assertThrows<BootMetaFormatException> { BootMetadata.parse(withSection) }
        assertTrue(ex.message!!.contains("end 之后还有记录"), ex.message)
    }

    @Test
    fun `多 labels 大模型带段读回 缓冲增长与判别不炸`(@TempDir dir: Path) {
        val big = BootMetadata(labels = (0 until 2000).associate { "FN_$it" to mapOf("S" to it) })
        val withSection = big.encodeText() +
            section((0 until 500).map { "KEY_$it $canonicalA" })
        val read = BootMetadata.read(writeMeta(dir, "program.meta", withSection))
        assertEquals(big.encodeText(), read.encodeText())
        assertEquals(500, BaseSignatureCatalog.parse(withSection).size)
    }
}
