package com.eratw.port.erbcc.compile

import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `#LOCALSIZE` / `#LOCALSSIZE` / `#LOCALFSIZE` 的**官方合法域**必须能编译、落盘、
 * 装载回同值。
 *
 * 官方口径（spec: LogicalLineParser.cs:236-244、:259、:272）：`<= 0` 与
 * `>= int.MaxValue` 告警后丢弃，其余 `1..int.MaxValue-1` 原样写 `LocalLength` /
 * `LocalsLength`。旧实现把三只尺寸域压在 16 位位域里、`FuncMeta` 构造期 `require`
 * 直接抛异常 —— erbcc 不做容错，整个编译崩掉。真第三方语料 eratohoYMAEM
 * `ERB/MAIN/CHARA_LIST.ERB:26` 的 `#LOCALSIZE 3000000` 就是这样踩中的。
 */
class LocalSizeWideRangeTest {

    private fun compileCorpus(dir: Path, vararg files: Pair<String, String>): CompileResult {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        for ((name, text) in files) {
            Files.write(corpus.resolve(name), text.toByteArray(Charsets.UTF_8))
        }
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合法声明的合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(5)}")
        assertNotNull(r.program)
        return r
    }

    @Test
    fun `合法大尺寸编译通过且侧车原值往返`(@TempDir dir: Path) {
        val r = compileCorpus(
            dir,
            "01_big.ERB" to """
                @BIG_LOCALS
                #LOCALSIZE 3000000
                #LOCALSSIZE 2999999
                #LOCALFSIZE 3000000
                PRINTL BIG
            """.trimIndent(),
        )
        val mem = assertNotNull(r.bootMetadata.functions.firstOrNull { it.key == "BIG_LOCALS" })
        assertEquals(3_000_000, mem.localSize, "内存编译的 #LOCALSIZE 必须原值保留")
        assertEquals(2_999_999, mem.localsSize, "内存编译的 #LOCALSSIZE 必须原值保留")
        assertEquals(3_000_000, mem.floatLocalSize, "内存编译的 #LOCALFSIZE 必须原值保留")

        val report = ErbcArtifacts.write(
            dir.resolve("out"), r.program!!, dir.resolve("corpus"), r.sourceFiles, r.stats, r.bootMetadata,
        )
        val metaPath = assertNotNull(report.metaPath, "落盘必须同时写侧车")
        val loaded = assertNotNull(BootMetadata.read(metaPath).functions.firstOrNull { it.key == "BIG_LOCALS" })
        assertEquals(3_000_000, loaded.localSize, "侧车装载回的 #LOCALSIZE 必须原值保留（不许截断）")
        assertEquals(2_999_999, loaded.localsSize, "侧车装载回的 #LOCALSSIZE 必须原值保留（不许截断）")
        assertEquals(3_000_000, loaded.floatLocalSize, "侧车装载回的 #LOCALFSIZE 必须原值保留（不许截断）")
    }

    @Test
    fun `官方越界值告警丢弃后保持未声明`(@TempDir dir: Path) {
        val r = compileCorpus(
            dir,
            "01_edge.ERB" to """
                @ZERO_LOCALS
                #LOCALSIZE 0
                PRINTL ZERO

                @MAX_LOCALS
                #LOCALSIZE 2147483647
                PRINTL MAX
            """.trimIndent(),
        )
        val zero = assertNotNull(r.bootMetadata.functions.firstOrNull { it.key == "ZERO_LOCALS" })
        assertEquals(0, zero.localSize, "<= 0 在原版告警后丢弃，保持未声明（0）")
        val max = assertNotNull(r.bootMetadata.functions.firstOrNull { it.key == "MAX_LOCALS" })
        assertEquals(0, max.localSize, ">= int.MaxValue 在原版告警后丢弃，保持未声明（0）")
    }
}
