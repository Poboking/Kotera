package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 4 份满额资源恰好顶到总量上限，外加骨架自带 README 即越界（阈值本体不在这里复制）。 */
private const val RESOURCE_CHUNKS = 4

/** DOS 时间下限年份，与 PackModZip 的固定时间戳同源。 */
private const val DOS_EPOCH_YEAR = 1980

/**
 * `pack-mod` 的 CLI 契约：退出码三档（0 出包 / 1 已拒绝 / 2 用法或 IO）、
 * `--out` 的安全闸、资源阈值拒绝、条目顺序与固定时间戳。
 */
class PackModCliTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `usage error exits with two`() {
        val run = runPack()
        assertEquals(2, run.exit)
        assertTrue(run.err.contains("用法: modgen pack-mod"), "err=${run.err}")
    }

    @Test
    fun `output value cannot swallow the next option`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--out", "--json")
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("缺少取值"), "err=${run.err}")
        assertTrue(run.err.contains("--json"), "err should name the swallowed token; err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `schema value cannot swallow the next option`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--schema", "--json", "--out", zip.toString())
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("缺少取值"), "err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `output inside module is refused`() {
        val module = packableModule(tempDir)
        val inside = module.resolve("pkg.zip")
        val run = runPack(module.toString(), "--out", inside.toString())
        assertEquals(2, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("不能落在模块目录内"), "err=${run.err}")
        assertFalse(Files.exists(inside))
    }

    @Test
    fun `missing output parent is created`() {
        val module = packableModule(tempDir)
        val zip = tempDir.resolve("deep/a/b/pkg.zip")
        packOk(module, zip)
        assertTrue(Files.isRegularFile(zip))
    }

    @Test
    fun `check error refuses and leaves no zip`() {
        val module = packableModule(tempDir)
        Files.delete(generatedFile(module))
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--out", zip.toString())
        assertEquals(1, run.exit, "out=${run.out}")
        assertTrue(run.out.contains(": ERROR:"), "findings should be printed verbatim; out=${run.out}")
        assertTrue(run.err.contains("拒绝打包：check-mod 有"), "err=${run.err}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `oversized single resource is refused`() {
        val module = packableModule(tempDir)
        val oversized = (ModResourceLimits.MAX_RESOURCE_FILE_BYTES + 1).toInt()
        Files.write(module.resolve("$TEST_ASSET_DIR/big.bin"), ByteArray(oversized))
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--out", zip.toString())
        assertEquals(1, run.exit, "out=${run.out}")
        assertTrue(run.out.contains(": ERROR:"), "out=${run.out}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `oversized total resources are refused`() {
        val module = packableModule(tempDir)
        val chunk = ByteArray(ModResourceLimits.MAX_RESOURCE_FILE_BYTES.toInt())
        repeat(RESOURCE_CHUNKS) { index -> Files.write(module.resolve("$TEST_ASSET_DIR/chunk-$index.bin"), chunk) }
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--out", zip.toString())
        assertEquals(1, run.exit, "out=${run.out}")
        assertTrue(run.out.contains(": ERROR:"), "out=${run.out}")
        assertFalse(Files.exists(zip))
    }

    @Test
    fun `entries follow lexical order`() {
        val module = packableModule(tempDir)
        writeModuleFile(module, "zeta.ERB", "z")
        writeModuleFile(module, "alpha.ERB", "a")
        writeModuleFile(module, "mid/nested.ERH", "n")
        val zip = outZip(tempDir)
        packOk(module, zip)
        val names = entryNames(zip)
        assertEquals(names.sorted(), names, "zip entry order must be lexical: $names")
        assertTrue(names.indexOf("alpha.ERB") < names.indexOf(TEST_SOURCE))
        assertTrue(names.indexOf(TEST_SOURCE) < names.indexOf("zeta.ERB"))
    }

    @Test
    fun `entry timestamps are fixed at dos epoch`() {
        val module = packableModule(tempDir)
        val zip = outZip(tempDir)
        packOk(module, zip)
        val expected = LocalDateTime.of(DOS_EPOCH_YEAR, 1, 1, 0, 0, 0)
        val stamps = ZipFile(zip.toFile()).use { file ->
            file.entries().asSequence().map { entry -> entry.getTimeLocal() }.toSet()
        }
        assertEquals(setOf(expected), stamps)
    }
}
