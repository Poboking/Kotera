package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `check-mod --fix` 的修复语义：只重建「生成物与 schema 不一致」这一类，其余只报不修；
 * 写盘是 tmp + 原子改名 + 回读校验；第二次 `--fix` 必须 0 修改（幂等）。
 */
class ModCheckFixTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fix rewrites a stale generated file to the emitter bytes`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        makeGeneratedStale(module)
        val run = runCheckFix(listOf(module.toString(), "--fix"))
        assertEquals(0, run.exit, "stdout=${run.out} stderr=${run.err}")
        assertEquals(expectedGenerated(module), Files.readString(generatedFile(module)))
        assertTrue(run.out.contains("check-mod: fixed"), "stdout=${run.out}")
    }

    @Test
    fun `second fix pass reports no changes`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        makeGeneratedStale(module)
        assertEquals(0, runCheckFix(listOf(module.toString(), "--fix")).exit)
        val fixedBytes = Files.readAllBytes(generatedFile(module))
        val second = runCheckFix(listOf(module.toString(), "--fix", "--json"))
        assertEquals(0, second.exit, "stdout=${second.out}")
        assertTrue(fixedBytes.contentEquals(Files.readAllBytes(generatedFile(module))))
        assertTrue(second.out.contains("\"fixes\": []"), "stdout=${second.out}")
    }

    @Test
    fun `fix on an already clean module changes nothing`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val before = Files.readAllBytes(generatedFile(module))
        val run = runCheckFix(listOf(module.toString(), "--fix", "--json"))
        assertEquals(0, run.exit, "stdout=${run.out}")
        assertTrue(before.contentEquals(Files.readAllBytes(generatedFile(module))))
        assertTrue(run.out.contains("\"fixes\": []"), "stdout=${run.out}")
    }

    @Test
    fun `dry run reports the plan without writing`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        makeGeneratedStale(module)
        val before = Files.readString(generatedFile(module))
        val run = runCheckFix(listOf(module.toString(), "--dry-run", "--json"))
        assertEquals(1, run.exit, "stdout=${run.out}")
        assertEquals(before, Files.readString(generatedFile(module)))
        assertTrue(run.out.contains("\"status\": \"WOULD_FIX\""), "stdout=${run.out}")
    }

    @Test
    fun `fix and dry run are mutually exclusive`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val run = runCheckFix(listOf(module.toString(), "--fix", "--dry-run"))
        assertEquals(2, run.exit)
        assertEquals("", run.out)
        assertTrue(run.err.contains("mutually exclusive"), "stderr=${run.err}")
    }

    @Test
    fun `fix leaves a broken schema untouched`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val schema = schemaFile(module)
        Files.writeString(schema, "not a schema at all\n")
        val run = runCheckFix(listOf(module.toString(), "--fix"))
        assertEquals(1, run.exit, "stdout=${run.out}")
        assertEquals("not a schema at all\n", Files.readString(schema))
        assertFalse(run.out.contains("check-mod: fixed"), "stdout=${run.out}")
    }

    @Test
    fun `fix does not fabricate a missing generated file`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(generatedFile(module))
        val run = runCheckFix(listOf(module.toString(), "--fix"))
        assertEquals(1, run.exit, "stdout=${run.out}")
        assertFalse(Files.exists(generatedFile(module)))
        assertFalse(run.out.contains("check-mod: fixed"), "stdout=${run.out}")
    }

    @Test
    fun `fix leaves a carrier package id mismatch untouched`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        replaceOnce(
            declarationsFile(module),
            "override val packageId: String get() = PACKAGE_ID",
            "override val packageId: String get() = \"eratw.other\"",
        )
        val carrier = declarationsFile(module)
        val before = Files.readAllBytes(carrier)
        val run = runCheckFix(listOf(module.toString(), "--fix", "--json"))
        assertEquals(1, run.exit, "stdout=${run.out}")
        assertTrue(before.contentEquals(Files.readAllBytes(carrier)))
        assertTrue(run.out.contains("\"fixes\": []"), "stdout=${run.out}")
    }
}
