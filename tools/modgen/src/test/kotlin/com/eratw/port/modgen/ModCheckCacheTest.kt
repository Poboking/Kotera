package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `check-mod --changed-only` 增量模式的「省重算」面：指纹命中只在「输入一致 + 上次 0 error」时
 * 发生，输入一改就失效重算并重新落缓存；缺省模式与 `--no-cache` 全程不碰缓存。
 */
class ModCheckCacheTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `first run writes cache and second run reports cached hit`() {
        val module = healthyModule(tempDir)
        val first = runCheck("--changed-only", module.toString())
        assertEquals(0, first.exit, "stdout=${first.out} stderr=${first.err}")
        assertTrue(first.out.contains("check-mod: 0 error(s)"), "stdout=${first.out}")
        assertTrue(Files.isRegularFile(ModCheckCache.cacheFile(module)), "cache file should be written")

        val second = runCheck("--changed-only", module.toString())
        assertEquals(0, second.exit)
        assertEquals("check-mod eratw.mymod: up to date (cached)", second.out.trimEnd())
        assertEquals("", second.err)
    }

    @Test
    fun `incremental alias behaves like changed only`() {
        val module = healthyModule(tempDir)
        assertEquals(0, runCheck("--incremental", module.toString()).exit)
        val second = runCheck("--incremental", module.toString())
        assertEquals("check-mod eratw.mymod: up to date (cached)", second.out.trimEnd())
    }

    @Test
    fun `schema change invalidates the cached result`() {
        val module = healthyModule(tempDir)
        assertEquals(0, runCheck("--changed-only", module.toString()).exit)
        val before = ModCheckFingerprint.compute(module, emptyList(), null)

        replaceOnce(schemaFile(module), "\tnickname", "\ttitle")

        val after = ModCheckFingerprint.compute(module, emptyList(), null)
        assertNotEquals(before, after, "schema edit must move the fingerprint")
        val run = runCheck("--changed-only", module.toString())
        assertEquals(1, run.exit, "stale generated file must be reported: stdout=${run.out}")
        assertFalse(run.out.contains("up to date (cached)"), "stdout=${run.out}")
        assertTrue(run.out.contains("error(s)"), "stdout=${run.out}")
    }

    @Test
    fun `generated change invalidates the cached result`() {
        val module = healthyModule(tempDir)
        assertEquals(0, runCheck("--changed-only", module.toString()).exit)
        val before = ModCheckFingerprint.compute(module, emptyList(), null)

        Files.writeString(generatedFile(module), generatedFile(module).readText() + "\n// tweak\n")

        val after = ModCheckFingerprint.compute(module, emptyList(), null)
        assertNotEquals(before, after, "generated edit must move the fingerprint")
        val run = runCheck("--changed-only", module.toString())
        assertFalse(run.out.contains("up to date (cached)"), "stdout=${run.out}")
    }

    @Test
    fun `source change invalidates and then re-caches`() {
        val module = healthyModule(tempDir)
        assertEquals(0, runCheck("--changed-only", module.toString()).exit)

        Files.writeString(declarationsFile(module), declarationsFile(module).readText() + "\n// note\n")

        val full = runCheck("--changed-only", module.toString())
        assertEquals(0, full.exit, "stdout=${full.out}")
        assertTrue(full.out.contains("check-mod: 0 error(s)"), "stdout=${full.out}")
        val cached = runCheck("--changed-only", module.toString())
        assertEquals("check-mod eratw.mymod: up to date (cached)", cached.out.trimEnd())
    }

    @Test
    fun `default mode writes no cache`() {
        val module = healthyModule(tempDir)
        assertEquals(0, runCheck(module.toString()).exit)
        assertFalse(Files.exists(ModCheckCache.cacheFile(module)), "default mode must not touch the cache")
    }

    @Test
    fun `no cache switch never reads or writes the cache`() {
        val module = healthyModule(tempDir)
        val first = runCheck("--changed-only", "--no-cache", module.toString())
        assertEquals(0, first.exit)
        assertTrue(first.out.contains("check-mod: 0 error(s)"), "stdout=${first.out}")
        assertFalse(Files.exists(ModCheckCache.cacheFile(module)), "--no-cache must not write cache")

        assertEquals(0, runCheck("--changed-only", module.toString()).exit)
        assertTrue(Files.isRegularFile(ModCheckCache.cacheFile(module)), "control run should have cached")

        val bypass = runCheck("--changed-only", "--no-cache", module.toString())
        assertEquals(0, bypass.exit)
        assertTrue(bypass.out.contains("check-mod: 0 error(s)"), "stdout=${bypass.out}")
        assertFalse(bypass.out.contains("up to date"), "--no-cache must ignore the cache: stdout=${bypass.out}")
    }

    @Test
    fun `usage errors still exit with two in changed only mode`() {
        val run = runCheck("--changed-only")
        assertEquals(2, run.exit)
        assertTrue(run.err.contains("missing <mod dir>"), "stderr=${run.err}")
    }
}

/**
 * 增量模式的「不许省」面：error 结论永不缓存、坏缓存/不可写缓存静默回退全量、
 * `--json` 契约不被缓存污染。
 */
class ModCheckCacheSafetyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `error result is never cached`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(generatedFile(module))

        val first = runCheck("--changed-only", module.toString())
        assertEquals(1, first.exit)
        assertFalse(Files.exists(ModCheckCache.cacheFile(module)), "error result must not be written to cache")

        val second = runCheck("--changed-only", module.toString())
        assertEquals(1, second.exit, "error must be recomputed and reported every run")
        assertTrue(second.out.contains("1 error(s)"), "stdout=${second.out}")
        assertFalse(second.out.contains("up to date"), "stdout=${second.out}")
    }

    @Test
    fun `cached clean result is not reused after inputs break`() {
        val module = healthyModule(tempDir)
        assertEquals(0, runCheck("--changed-only", module.toString()).exit)
        val cache = ModCheckCache.cacheFile(module)
        val cachedText = cache.readText()

        Files.delete(generatedFile(module))
        val broken = runCheck("--changed-only", module.toString())
        assertEquals(1, broken.exit, "stdout=${broken.out}")
        assertFalse(broken.out.contains("up to date"), "stdout=${broken.out}")
        assertEquals(cachedText, cache.readText(), "the error run must not rewrite the clean cache")

        val again = runCheck("--changed-only", module.toString())
        assertEquals(1, again.exit, "still broken inputs must never hit the cache")
    }

    @Test
    fun `hand written cache entry with errors is not honored`() {
        val module = healthyModule(tempDir)
        val fingerprint = ModCheckFingerprint.compute(module, emptyList(), null)
        val entry = ModCheckCacheEntry(fingerprint, "eratw.mymod", 1, 0)
        ModCheckCache.write(ModCheckCache.cacheFile(module), entry)

        val run = runCheck("--changed-only", module.toString())
        assertEquals(0, run.exit, "an errors>0 cache entry must never short circuit: stdout=${run.out}")
        assertFalse(run.out.contains("up to date"), "stdout=${run.out}")
        assertTrue(run.out.contains("check-mod: 0 error(s)"), "stdout=${run.out}")
    }

    @Test
    fun `corrupt cache falls back to a full run`() {
        val module = healthyModule(tempDir)
        val cache = ModCheckCache.cacheFile(module)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "{ not json at all")

        val run = runCheck("--changed-only", module.toString())
        assertEquals(0, run.exit, "stdout=${run.out} stderr=${run.err}")
        assertTrue(run.out.contains("check-mod: 0 error(s)"), "stdout=${run.out}")
        assertEquals("", run.err, "bad cache must be silent")

        val second = runCheck("--changed-only", module.toString())
        assertEquals("check-mod eratw.mymod: up to date (cached)", second.out.trimEnd())
    }

    @Test
    fun `unwritable cache path falls back silently`() {
        val module = healthyModule(tempDir)
        val cache = ModCheckCache.cacheFile(module)
        Files.createDirectories(cache)

        val run = runCheck("--changed-only", module.toString())
        assertEquals(0, run.exit, "stdout=${run.out} stderr=${run.err}")
        assertTrue(run.out.contains("check-mod: 0 error(s)"), "stdout=${run.out}")
        assertEquals("", run.err, "unwritable cache must be silent")

        val second = runCheck("--changed-only", module.toString())
        assertEquals(0, second.exit)
        assertTrue(second.out.contains("check-mod: 0 error(s)"), "stdout=${second.out}")
    }

    @Test
    fun `json output stays stable on a cached hit`() {
        val module = healthyModule(tempDir)
        val first = runCheck("--changed-only", "--json", module.toString())
        assertEquals(0, first.exit, "stderr=${first.err}")
        assertTrue(first.out.contains("\"schema\""), "stdout=${first.out}")

        val second = runCheck("--changed-only", "--json", module.toString())
        assertEquals(0, second.exit)
        assertEquals(ModCheckJson.render(emptyList()), second.out, "cached hit must keep the frozen JSON shape")
        assertFalse(second.out.contains("cache"), "no cache field may leak into the JSON: stdout=${second.out}")
        assertFalse(second.out.contains("cached"), "no cache marker may leak into the JSON: stdout=${second.out}")
        assertEquals("", second.err)
    }

    @Test
    fun `fingerprint is deterministic and includes tool version and resources`() {
        val module = healthyModule(tempDir)
        val base = ModCheckFingerprint.compute(module, emptyList(), null)
        assertEquals(base, ModCheckFingerprint.compute(module, emptyList(), null))
        assertNotEquals(base, ModCheckFingerprint.compute(module, emptyList(), "art"), "resources dir is an input")
        assertNotEquals(
            base,
            ModCheckFingerprint.compute(module, emptyList(), null, "next"),
            "tool version is an input",
        )

        Files.writeString(declarationsFile(module), declarationsFile(module).readText() + "\n// note\n")
        assertNotEquals(base, ModCheckFingerprint.compute(module, emptyList(), null), "source content is an input")
    }
}

/** 建一个「生成物已同步」的干净模块（scaffold 后补生成物，check-mod 0 error）。 */
private fun healthyModule(root: Path): Path {
    val module = scaffoldModule(root)
    writeGenerated(module)
    return module
}

private class CacheCliRun(val exit: Int, val out: String, val err: String)

private fun runCheck(vararg args: String): CacheCliRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = PrintStream(out, true, "UTF-8").use { outStream ->
        PrintStream(err, true, "UTF-8").use { errStream ->
            ModCheck.runCli(arrayOf(*args), outStream, errStream)
        }
    }
    return CacheCliRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
}
