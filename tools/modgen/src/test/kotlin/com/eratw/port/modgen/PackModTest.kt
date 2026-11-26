package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `pack-mod` 的打包面契约：收集哪些条目、确定性、摘要、拒绝分支。
 * 起点都是 new-mod 骨架（[packableModule]），断言的是 zip 里真实读到的东西。
 */
class PackModTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `packs sources and resources with summary`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        val names = entryNames(zip)
        assertTrue(TEST_SOURCE in names, "names=$names")
        assertTrue(TEST_ASSET_README in names, "names=$names")
        assertTrue(run.out.contains("pack-mod: 条目 2（源 1 / 资源 1）"), "out=${run.out}")
        assertTrue(run.out.contains("pack-mod: 输出 $zip"), "out=${run.out}")
        assertTrue(Regex("pack-mod: sha256 [0-9a-f]{64}").containsMatchIn(run.out), "out=${run.out}")
    }

    @Test
    fun `two runs produce identical bytes`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        val first = packOk(module, zip)
        val firstBytes = Files.readAllBytes(zip)
        val second = packOk(module, zip)
        val secondBytes = Files.readAllBytes(zip)
        assertContentEquals(firstBytes, secondBytes)
        assertEquals(sha256Of(first), sha256Of(second))
    }

    @Test
    fun `warn only module still packs`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(module.resolve(TEST_ASSET_README))
        writeModuleFile(module, TEST_SOURCE, "@SYSTEM\nPRINTL hello\n")
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        assertTrue(Regex("pack-mod: check-mod 0 error\\(s\\), [1-9]").containsMatchIn(run.out), "out=${run.out}")
        assertTrue(run.out.contains("资源 0"), "out=${run.out}")
        assertTrue(TEST_SOURCE in entryNames(zip))
    }

    @Test
    fun `resource entries use package prefix and keep bytes`() {
        val module = packableModule(tempDir)
        val payload = "hello-resource-中文"
        writeModuleFile(module, "$TEST_ASSET_DIR/img/a.txt", payload)
        val zip = outZip(tempDir)
        packOk(module, zip)
        assertTrue("$TEST_ASSET_DIR/img/a.txt" in entryNames(zip))
        assertContentEquals(payload.toByteArray(Charsets.UTF_8), entryBytes(zip, "$TEST_ASSET_DIR/img/a.txt"))
    }

    @Test
    fun `build and git directories are excluded and counted`() {
        val module = packableModule(tempDir)
        writeModuleFile(module, "build/gen/x.ERB", "x")
        writeModuleFile(module, ".git/hooks/y.ERH", "y")
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        val names = entryNames(zip)
        assertTrue(names.none { it.startsWith("build/") || it.startsWith(".git/") }, "names=$names")
        assertTrue(run.out.contains("构建输出目录（build/）"), "out=${run.out}")
        assertTrue(run.out.contains("版本库目录（.git/）"), "out=${run.out}")
    }

    @Test
    fun `module without erb sources is refused`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--out", zip.toString())
        assertEquals(1, run.exit, "out=${run.out}")
        assertTrue(run.err.contains("没有 .ERB/.ERH 源"), "err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `foreign assets directory is skipped`() {
        val module = packableModule(tempDir)
        writeModuleFile(module, "other.mod.assets/x.ERB", "x")
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        val names = entryNames(zip)
        assertTrue(names.none { it.startsWith("other.mod.assets/") }, "names=$names")
        assertTrue(run.out.contains("非本模块资源前缀（.assets/）"), "out=${run.out}")
    }

    @Test
    fun `no directory entries are written`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        val names = entryNames(zip)
        assertTrue(names.none { it.endsWith("/") }, "names=$names")
        assertTrue(Regex("pack-mod: 跳过 [1-9]").containsMatchIn(run.out), "out=${run.out}")
    }
}
