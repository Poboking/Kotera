package com.eratw.port.modgen

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 与冻结值无关的常量解析：源码文本里抽 `a * b * c` 形式的 Long 乘积。 */
private val FILE_LIMIT_LITERAL = Regex("""const val MAX_RESOURCE_FILE_BYTES: Long = (\d+)L \* (\d+)L \* (\d+)L""")

private val TOTAL_LIMIT_LITERAL = Regex("""const val MAX_RESOURCE_TOTAL_BYTES: Long = (\d+)L \* (\d+)L \* (\d+)L""")

private val ASSET_SUFFIX_LITERAL = Regex("""const val ASSET_DIR_SUFFIX: String = "([^"]+)"""")

private fun productOf(text: String, regex: Regex, label: String): Long {
    val match = assertNotNull(regex.find(text), "源码里找不到常量定义：$label")
    return match.groupValues.drop(1).map { it.toLong() }.reduce { acc, value -> acc * value }
}

private fun writeSparseFile(path: Path, size: Long) {
    Files.createDirectories(path.parent)
    RandomAccessFile(path.toFile(), "rw").use { file -> file.setLength(size) }
}

/**
 * `check-mod` 的资源面校验（`<packageId>.assets/`）：与运行期 `ModResourceStore` 同口径。
 * 覆盖合法树、非法名表、单文件超限、整包超限、空目录 WARN、目录缺失静默、目录被文件占用，
 * 以及「modgen 常量 ↔ 运行期真源」的文本哨兵。
 */
class ModCheckResourcesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun assetsDir(module: Path): Path = module.resolve("eratw.mymod.assets")

    @Test
    fun cleanAssetTreeProducesNoError() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)

        val findings = findingsOf(module)

        assertNoError(findings)
        assertTrue(
            findings.none { it.file.contains(".assets") },
            "清理过的资源树不该有资源面发现：${findings.joinToString("; ") { it.message }}",
        )
        assertTrue(Files.isRegularFile(assetsDir(module).resolve("README.txt")))
    }

    @Test
    fun legalResourceNameTableAcceptsNormalRelativeNames() {
        listOf(
            "README.txt",
            "icon.bin",
            "ui/button.png",
            "deep/nested/sound.ogg",
            "a b.txt",
            "中文/名字.txt",
            "a.b/c",
        ).forEach { name -> assertTrue(isLegalResourceName(name), "应判合法：[$name]") }
    }

    @Test
    fun illegalResourceNameTableRejectsTraversalAndSeparators() {
        listOf(
            "/abs.txt",
            "a//b.txt",
            "a/../b.txt",
            "a/./b.txt",
            "a\\b.txt",
            "a/",
            "/",
            "..",
            ".",
            "",
            "a:b.txt",
            "C:/x.txt",
            "ctl\u0001.txt",
        ).forEach { name -> assertFalse(isLegalResourceName(name), "应判非法：[$name]") }
    }

    @Test
    fun singleFileOverLimitReportsError() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        writeSparseFile(assetsDir(module).resolve("big.bin"), ModResourceLimits.MAX_RESOURCE_FILE_BYTES + 1L)

        val findings = findingsOf(module)

        assertHasError(findings, "资源文件超过单文件上限")
    }

    @Test
    fun totalOverLimitReportsError() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        val perFile = 3L * 1024L * 1024L + 512L * 1024L
        repeat(5) { index -> writeSparseFile(assetsDir(module).resolve("chunk-$index.bin"), perFile) }

        val findings = findingsOf(module)

        assertHasError(findings, "资源总量超过单包上限")
        assertTrue(
            errorsOf(findings).none { it.message.contains("超过单文件上限") },
            "单个文件都没超限，不该报单文件错误：${errorsOf(findings).joinToString("; ") { it.message }}",
        )
    }

    @Test
    fun emptyAssetsDirectoryReportsWarning() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(assetsDir(module).resolve("README.txt"))

        val findings = findingsOf(module)

        assertNoError(findings)
        assertTrue(
            findings.any { it.severity == Severity.WARN && it.message.contains("资源目录为空") },
            "空目录应有 WARN：${findings.joinToString("; ") { it.message }}",
        )
    }

    @Test
    fun missingAssetsDirectoryIsSilent() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(assetsDir(module).resolve("README.txt"))
        Files.delete(assetsDir(module))

        val findings = findingsOf(module)

        assertTrue(
            findings.none { it.file.contains(".assets") || it.message.contains(".assets") },
            "资源目录缺失应静默：${findings.joinToString("; ") { it.message }}",
        )
    }

    @Test
    fun assetsPathOccupiedByFileReportsError() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        Files.delete(assetsDir(module).resolve("README.txt"))
        Files.delete(assetsDir(module))
        Files.createFile(assetsDir(module))

        val findings = findingsOf(module)

        assertHasError(findings, "资源路径不是目录")
    }

    @Test
    fun cliReportsResourceErrorWithExitOne() {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        writeSparseFile(assetsDir(module).resolve("big.bin"), ModResourceLimits.MAX_RESOURCE_FILE_BYTES + 1L)
        val captured = java.io.ByteArrayOutputStream()

        val exit = java.io.PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        assertEquals(1, exit, "资源面 ERROR 应让 check-mod 退出码为 1")
        assertTrue(captured.toString("UTF-8").contains("资源文件超过单文件上限"), "stdout=${captured.toString("UTF-8")}")
    }

    @Test
    fun runtimeLimitsStayInSyncWithStoreSource() {
        val storeSource = repoRoot().resolve("core-vm/src/main/kotlin/com/eratw/port/core/mod/ModResourceStore.kt")
        val text = storeSource.readText()

        assertEquals(
            ModResourceLimits.MAX_RESOURCE_FILE_BYTES,
            productOf(text, FILE_LIMIT_LITERAL, "MAX_RESOURCE_FILE_BYTES"),
            "modgen 单文件上限与运行期 ModResourceStore 漂移：$storeSource",
        )
        assertEquals(
            ModResourceLimits.MAX_RESOURCE_TOTAL_BYTES,
            productOf(text, TOTAL_LIMIT_LITERAL, "MAX_RESOURCE_TOTAL_BYTES"),
            "modgen 整包上限与运行期 ModResourceStore 漂移：$storeSource",
        )
        val suffix = assertNotNull(ASSET_SUFFIX_LITERAL.find(text), "运行期源码里找不到 ASSET_DIR_SUFFIX")
        assertEquals(ModResourceLimits.ASSET_DIR_SUFFIX, suffix.groupValues[1], "资源目录后缀必须与运行期一致")
    }
}
