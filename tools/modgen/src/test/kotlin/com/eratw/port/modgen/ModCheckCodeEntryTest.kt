package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [ModCheckCodeEntry] 的定向用例：代码面触发、内层清单候选、两类 ERROR 的判据与文案。
 *
 * 夹具口径：`scaffoldModule` 生成 `eratw.mymod` 骨架，再按用例补 `code/manifest.json`、
 * `code.mod` 或 `src/main/kotlin/.../MyMod.kt`（`class MyMod : EraMod`）。
 */
internal class ModCheckCodeEntryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `code dir with matching manifest and source entrypoint is clean`() {
        val module = scaffoldModule(tempDir)
        writeSourceEntry(module, "class MyMod : EraMod\n")
        writeInnerManifest(module, encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT))
        assertTrue(codeEntryFindings(module).isEmpty(), "expected clean: ${codeEntryFindings(module)}")
    }

    @Test
    fun `manifest without entrypoint is missing error`() {
        val module = scaffoldModule(tempDir)
        writeInnerManifest(module, """{"packageId":"$TEST_PACKAGE_ID"}""")
        val findings = codeEntryFindings(module)
        assertEquals(1, findings.size, "findings=$findings")
        assertTrue("CODE_ENTRYPOINT_MISSING" in findings.single().message, "message=${findings.single().message}")
        assertTrue("entrypoint" in findings.single().message, "message=${findings.single().message}")
    }

    @Test
    fun `code dir without manifest is missing error naming candidates`() {
        val module = scaffoldModule(tempDir)
        Files.createDirectories(module.resolve("code"))
        val findings = codeEntryFindings(module)
        assertEquals(1, findings.size, "findings=$findings")
        val message = findings.single().message
        assertTrue("CODE_ENTRYPOINT_MISSING" in message, "message=$message")
        assertTrue("code/manifest.json" in message, "message=$message")
        assertTrue("code.mod" in message, "message=$message")
    }

    @Test
    fun `manifest entrypoint not matching source class is mismatch`() {
        val module = scaffoldModule(tempDir)
        writeSourceEntry(module, "class OtherMod : EraMod\n")
        writeInnerManifest(module, encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT))
        val message = codeEntryFindings(module).single().message
        assertTrue("CODE_ENTRYPOINT_MISMATCH" in message, "message=$message")
        assertTrue(TEST_ENTRYPOINT in message, "message=$message")
        assertTrue("eratw.mymod.OtherMod" in message, "message=$message")
    }

    @Test
    fun `manifest entrypoint with invalid shape is mismatch`() {
        val module = scaffoldModule(tempDir)
        writeInnerManifest(module, encodeInnerManifest(TEST_PACKAGE_ID, "eratw.mymod.badname"))
        val message = codeEntryFindings(module).single().message
        assertTrue("CODE_ENTRYPOINT_MISMATCH" in message, "message=$message")
        assertTrue("形态非法" in message, "message=$message")
        assertTrue("eratw.mymod.badname" in message, "message=$message")
    }

    @Test
    fun `code mod zip is recognized as inner manifest`() {
        val module = scaffoldModule(tempDir)
        writeSourceEntry(module, "class MyMod : EraMod\n")
        writeCodeModZip(module, encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT))
        assertTrue(codeEntryFindings(module).isEmpty(), "expected clean: ${codeEntryFindings(module)}")
    }

    @Test
    fun `source EraMod declaration alone is silent without code artifact`() {
        val module = scaffoldModule(tempDir)
        writeSourceEntry(module, "class MyMod : EraMod\n")
        assertTrue(codeEntryFindings(module).isEmpty(), "expected silent: ${codeEntryFindings(module)}")
    }

    @Test
    fun `plain module without code face has no code entry finding`() {
        val module = scaffoldModule(tempDir)
        assertTrue(codeEntryFindings(module).isEmpty(), "expected silent: ${codeEntryFindings(module)}")
    }

    @Test
    fun `constructor params split across lines still locate the entry class`() {
        val module = scaffoldModule(tempDir)
        writeSourceEntry(
            module,
            "class MyMod(\n    private val context: Any,\n    private val name: String,\n) : EraMod\n",
        )
        writeInnerManifest(module, encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT))
        assertTrue(codeEntryFindings(module).isEmpty(), "expected clean: ${codeEntryFindings(module)}")
    }

    @Test
    fun `entrypoint with wrong package is mismatch`() {
        val module = scaffoldModule(tempDir)
        writeSourceEntry(module, "class MyMod : EraMod\n")
        writeInnerManifest(module, encodeInnerManifest(TEST_PACKAGE_ID, "other.pkg.MyMod"))
        val message = codeEntryFindings(module).single().message
        assertTrue("CODE_ENTRYPOINT_MISMATCH" in message, "message=$message")
        assertTrue("other.pkg.MyMod" in message, "message=$message")
        assertTrue(TEST_ENTRYPOINT in message, "message=$message")
    }

    @Test
    fun `valid code artifact module reports zero errors`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        writeSourceEntry(module, "class MyMod : EraMod\n")
        writeInnerManifest(module, encodeInnerManifest(TEST_PACKAGE_ID, TEST_ENTRYPOINT, apiVersion = 1))
        assertNoError(findingsOf(module))
        assertTrue(codeEntryFindings(module).isEmpty(), "expected clean: ${codeEntryFindings(module)}")
    }

    /** 只挑代码入口面的 finding，避免其它检查器的 findings 干扰计数。 */
    private fun codeEntryFindings(module: Path): List<Finding> =
        findingsOf(module).filter { it.message.startsWith("CODE_ENTRYPOINT_") }

    /** 写作者源码入口类（包名固定 `eratw.mymod`，与 [TEST_ENTRYPOINT] 的包前缀一致）。 */
    private fun writeSourceEntry(module: Path, declaration: String) {
        writeModuleFile(module, "src/main/kotlin/eratw/mymod/MyMod.kt", "package eratw.mymod\n\n$declaration")
    }

    /** 写作者随源码存放的内层清单。 */
    private fun writeInnerManifest(module: Path, text: String) {
        writeModuleFile(module, "code/manifest.json", text)
    }

    /** 现造 `code.mod`：只带 classes.dex + manifest.json（check-mod 只读清单，不校验 dex）。 */
    private fun writeCodeModZip(module: Path, manifest: String) {
        val target = writeModuleFile(module, "code.mod", "").also { Files.deleteIfExists(it) }
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0x64, 0x65, 0x78))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
