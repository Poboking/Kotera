package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * [PackModZip] 的纯收集面：条目名安全谓词（与运行期资源名同一条）与
 * 「没有 packageId 时只打源、外来 `.assets` 整目录跳过」。
 */
class PackModZipTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `legal entry names are accepted`() {
        listOf(TEST_SOURCE, "a/b.ERH", "$TEST_ASSET_DIR/img/a.txt", "x y/z.txt").forEach { name ->
            assertTrue(PackModZip.isSafeEntryName(name), "should accept: $name")
        }
    }

    @Test
    fun `unsafe entry names are rejected`() {
        val unsafeNames = listOf(
            "../x.ERB", "a/../../x.ERB", "/abs.ERB", "a\\b.ERB", "a//b.ERB",
            "", "a/", "a/./b", "C:/x.ERB", "a\u0000b.ERB",
        )
        unsafeNames.forEach { name -> assertFalse(PackModZip.isSafeEntryName(name), "should reject: $name") }
    }

    @Test
    fun `collect without package ids keeps sources and skips assets`() {
        val root = Files.createDirectories(tempDir.resolve("plain"))
        writeModuleFile(root, TEST_SOURCE, "@SYSTEM\n")
        writeModuleFile(root, "sub/extra.ERH", "#define X 1\n")
        writeModuleFile(root, "$TEST_ASSET_DIR/x.bin", "x")
        val plan = PackModZip.collect(root, emptyList())
        assertEquals(listOf(TEST_SOURCE, "sub/extra.ERH"), plan.entries.map { it.name })
        assertEquals(2, plan.sourceCount)
        assertEquals(0, plan.resourceCount)
        assertTrue(plan.skipped.any { skip -> skip.reason.contains("非本模块资源前缀") }, "skipped=${plan.skipped}")
        assertTrue(plan.unsafeNames.isEmpty(), "unsafe=${plan.unsafeNames}")
    }
}
