package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 真缺陷 `task-78`：「资源目录名与 `PACKAGE_ID` 不一致」时 check-mod 0 error、pack-mod exit 0 出包，
 * 资源全部静默丢弃。判据：模块根下存在 `.assets` 目录、却没有任何一个等于声明的
 * `<PACKAGE_ID>.assets` ⇒ check-mod ERROR；pack-mod 走同一判据（check 面 ERROR + collect 面 unsafe）。
 *
 * 复现基线：new-mod 骨架（`eratw.mymod.assets/`）、声明 `PACKAGE_ID = "eratw.mymod"`，
 * 把资源目录改名为 `legacy.assets/` 即命中。
 */
class ModCheckResourceDirMismatchTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `legacy assets dir violates declared package id`() {
        val module = packableModuleWithLegacyAssets()

        val findings = findingsOf(module)

        val error = assertNotNull(
            mismatchErrors(findings).firstOrNull(),
            "目录名不匹配必须报 ERROR：${findings.joinToString("; ") { it.message }}",
        )
        assertEquals("legacy.assets", error.file, "ERROR 要定位到实际目录名")
        assertTrue(error.message.contains("legacy.assets"), "要点名实际目录：${error.message}")
        assertTrue(error.message.contains("eratw.mymod"), "要点名声明值：${error.message}")
        assertTrue(error.message.contains("静默丢失"), "要点明后果：${error.message}")
    }

    @Test
    fun `blank package id names both sides`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        replaceOnce(
            declarationsFile(module),
            "const val PACKAGE_ID: String = \"eratw.mymod\"",
            "const val PACKAGE_ID: String = \"\"",
        )

        val error = assertNotNull(mismatchErrors(findingsOf(module)).firstOrNull(), "空白 PACKAGE_ID 也必须报")

        assertTrue(error.message.contains("eratw.mymod.assets"), "要点名实际目录：${error.message}")
        assertTrue(error.message.contains("<空白>"), "要点名空白声明：${error.message}")
    }

    @Test
    fun `bare assets dir is a mismatch`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.move(module.resolve(TEST_ASSET_DIR), module.resolve(".assets"))

        val error = assertNotNull(mismatchErrors(findingsOf(module)).firstOrNull(), "「.assets」目录不是 <PACKAGE_ID>.assets")

        assertTrue(error.message.contains(".assets"), "要点名实际目录：${error.message}")
        assertTrue(error.message.contains("eratw.mymod"), "要点名声明值：${error.message}")
    }

    @Test
    fun `two legacy dirs are both named in the error`() {
        val module = packableModuleWithLegacyAssets()
        Files.createDirectories(module.resolve("another.assets"))
        Files.writeString(module.resolve("another.assets/x.bin"), "x")

        val error = assertNotNull(mismatchErrors(findingsOf(module)).firstOrNull(), "两个不匹配目录也要报")

        assertTrue(error.message.contains("another.assets"), "两个目录都要点名：${error.message}")
        assertTrue(error.message.contains("legacy.assets"), "两个目录都要点名：${error.message}")
    }

    @Test
    fun `matching dir plus foreign dir stays green and packs`() {
        val module = packableModule(tempDir)
        writeModuleFile(module, "other.mod.assets/x.ERB", "x")
        val findings = findingsOf(module)
        val zip = outZip(tempDir)

        assertTrue(mismatchErrors(findings).isEmpty(), "有匹配目录就不该报一致性错：${findings.joinToString("; ") { it.message }}")
        assertNoError(findings)
        val run = runPack(module.toString(), "--out", zip.toString())
        assertEquals(0, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(run.out.contains("非本模块资源前缀（.assets/）"), "外来目录照旧跳过：${run.out}")
    }

    @Test
    fun `new mod scaffold stays green`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)

        val findings = findingsOf(module)

        assertTrue(
            mismatchErrors(findings).isEmpty(),
            "骨架目录名与 PACKAGE_ID 同源，不该红：${findings.joinToString("; ") { it.message }}",
        )
        assertNoError(findings)
    }

    @Test
    fun `no assets directory stays silent`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(module.resolve(TEST_ASSET_README))
        Files.delete(module.resolve(TEST_ASSET_DIR))

        val findings = findingsOf(module)

        assertTrue(mismatchErrors(findings).isEmpty(), "没有资源目录就无从比对：${findings.joinToString("; ") { it.message }}")
        assertTrue(findings.none { it.message.contains("资源目录名与 PACKAGE_ID 不一致") })
    }

    @Test
    fun `missing package id literal is not a mismatch`() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        replaceOnce(
            declarationsFile(module),
            "const val PACKAGE_ID: String = \"eratw.mymod\"",
            "const val NOT_PACKAGE_ID: String = \"eratw.mymod\"",
        )

        val findings = findingsOf(module)

        assertTrue(
            mismatchErrors(findings).isEmpty(),
            "无 PACKAGE_ID 字面量归声明面管，这里不重复报：${findings.joinToString("; ") { it.message }}",
        )
    }

    @Test
    fun `check cli exits one for mismatch`() {
        val module = packableModuleWithLegacyAssets()
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out -> ModCheck.runCli(arrayOf(module.toString()), out) }

        assertEquals(1, exit, "一致性 ERROR 应让 check-mod 退出码为 1：${captured.toString("UTF-8")}")
        assertTrue(captured.toString("UTF-8").contains("资源目录名与 PACKAGE_ID 不一致"), "stdout=${captured.toString("UTF-8")}")
    }

    @Test
    fun `pack cli refuses mismatch without writing zip`() {
        val module = packableModuleWithLegacyAssets()
        val zip = outZip(tempDir)

        val run = runPack(module.toString(), "--out", zip.toString())

        assertEquals(1, run.exit, "out=${run.out} err=${run.err}")
        assertTrue(run.out.contains("资源目录名与 PACKAGE_ID 不一致"), "拒绝原因要上屏：${run.out}")
        assertTrue(run.err.contains("拒绝打包"), "err=${run.err}")
        assertFalse(Files.exists(zip), "拒绝时不得产出 zip")
    }

    @Test
    fun `check and collect share the same mismatch message`() {
        val module = packableModuleWithLegacyAssets()
        val expected = assertNotNull(
            ModResourceDirConsistency.mismatch(listOf("legacy.assets"), listOf("eratw.mymod")),
        )

        val finding = assertNotNull(mismatchErrors(findingsOf(module)).firstOrNull())
        val plan = PackModZip.collect(module, listOf("eratw.mymod"))
        val unsafe = assertNotNull(plan.unsafe.singleOrNull(), "unsafe=${plan.unsafe.map { it.name }}")

        assertEquals(expected, finding.message, "check 面与判据同源")
        assertEquals("legacy.assets/", unsafe.name, "collect 面点名实际目录")
        assertEquals(expected, unsafe.reason, "pack 面与 check 面同源")
        assertTrue(unsafe.hint.contains("PACKAGE_ID"), "hint 要给改法：${unsafe.hint}")
    }

    @Test
    fun `pure predicate releases documented shapes`() {
        assertNull(ModResourceDirConsistency.mismatch(emptyList(), listOf("eratw.mymod")), "没有资源目录放行")
        assertNull(ModResourceDirConsistency.mismatch(listOf("x.assets"), emptyList()), "没有 PACKAGE_ID 放行")
        assertNull(
            ModResourceDirConsistency.mismatch(listOf("eratw.mymod.assets"), listOf("eratw.mymod")),
            "名字匹配放行",
        )
        assertNull(
            ModResourceDirConsistency.mismatch(listOf("b.assets"), listOf("a", "b")),
            "多载体任一匹配即放行",
        )
        assertNotNull(
            ModResourceDirConsistency.mismatch(listOf("b.assets"), listOf("a")),
            "一个都不匹配要拒",
        )
    }

    @Test
    fun `builtin carriers stay silent`() {
        listOf("mod-family", "mod-memory", "mod-template", "mod-livingworld").forEach { name ->
            val root = repoRoot().resolve(name)
            assertTrue(Files.isDirectory(root), "缺少内置载体目录：$root")
            val ids = ModCheckResources.packageIds(ModCheckSources.scan(root, mutableListOf()))
            assertTrue(ids.isNotEmpty(), "$name 应声明 PACKAGE_ID")
            assertNull(
                ModResourceDirConsistency.mismatch(ModResourceDirConsistency.assetDirectoryNames(root), ids),
                "$name 不该报一致性错",
            )
        }
    }

    /** new-mod 骨架 + 一份 ERB 源 + 资源目录改名成 `legacy.assets/`（复现 task-78 的形态）。 */
    private fun packableModuleWithLegacyAssets(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        writeModuleFile(module, TEST_SOURCE, "@SYSTEM\nPRINTL hello\n")
        Files.move(module.resolve(TEST_ASSET_DIR), module.resolve("legacy.assets"))
        return module
    }

    private fun mismatchErrors(findings: List<Finding>): List<Finding> =
        findings.filter { it.severity == Severity.ERROR && it.message.contains("资源目录名与 PACKAGE_ID 不一致") }
}
