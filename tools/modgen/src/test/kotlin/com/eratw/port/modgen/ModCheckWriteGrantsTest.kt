package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 测试里塞进脚手架的写授权面源码（行号可预期：1 = package，2 = 空行，3 = 正文首行）。 */
private const val GRANT_SAMPLE = "eratw/mymod/GrantSample.kt"

private const val GRANT_MARK = "writeGrant"

private const val READ_MARK = "读未声明键"

/** 往模块 `src/main/kotlin/` 里写一份源码文本（不编译，只给 check-mod 的文本抽取面看）。 */
private fun writeGrantSource(module: Path, body: String, relative: String = GRANT_SAMPLE): Path {
    val file = module.resolve("src/main/kotlin").resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\n$body\n")
    return file
}

/** 只挑写授权面（16/17）的发现，别把 schema / 资源 / 文案面的发现掺进断言。 */
private fun grantFindings(module: Path): List<Finding> =
    findingsOf(module).filter { finding ->
        finding.message.contains(GRANT_MARK) || finding.message.contains(READ_MARK)
    }

/**
 * `check-mod` 的写授权声明面（ModDX4-B1：16 `writeGrant` 对拍 + 17 读侧静态提示）。
 *
 * 断言口径分两层：
 * - 静默面：合法写法（字面量声明 / 声明过的读取）必须 0 发现，宁可漏报不许假红；
 * - 报红面：重复声明（大写归一判）= ERROR（原文同形构造期抛、大小写异形装载相位归一拒载，
 *   两种形态都载不进来）；跨包重叠 = WARN（不拒包）；
 *   读未声明键 = WARN（提示面，非拦截 —— 生产读面全量放行）。
 */
class ModCheckWriteGrantsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun literalGrantsAndCoveredReadsAreSilent() {
        val module = module()
        writeGrantSource(
            module,
            """
            |val manifest = modDeclarations("eratw.mymod") {
            |    writeGrant("DAY")
            |    writeGrant("PLACE", DimType.STR)
            |    global(GlobalKey("MOD_STATE"))
            |}
            |val day = context.gameState.getIntVar("DAY")
            |val place = context.gameState.getStrVar("PLACE")
            |val hasDay = context.gameState.hasIntVar("day")
            |val own = context.gameState.getIntVar("MOD_STATE")
            """.trimMargin(),
        )

        val findings = grantFindings(module)
        assertTrue(findings.isEmpty(), "字面量声明 + 已覆盖读取不该有发现：${findings.map { it.message }}")
    }

    @Test
    fun duplicateGrantKeysAreErrorCanonicalUppercase() {
        val module = module()
        writeGrantSource(
            module,
            """
            |val manifest = modDeclarations("eratw.mymod") {
            |    writeGrant("DAY")
            |    writeGrant("day")
            |}
            """.trimMargin(),
        )

        val findings = grantFindings(module)
        val errors = findings.filter { it.severity == Severity.ERROR }
        assertEquals(1, errors.size, "重复声明恰一条 ERROR：${findings.map { it.message }}")
        assertTrue(errors.single().message.contains("writeGrant 键重复声明"), errors.single().message)
    }

    @Test
    fun readingUndeclaredKeyIsWarnNotError() {
        val module = module()
        writeGrantSource(
            module,
            """
            |val day = context.gameState.getIntVar("DAY")
            |val hidden = context.gameState.hasStrVar("NOPE")
            """.trimMargin(),
        )

        val findings = grantFindings(module)
        assertTrue(findings.all { it.severity == Severity.WARN }, "读未声明键只 WARN：${findings.map { it.message }}")
        assertEquals(2, findings.size, "两个未声明键各一条提示：${findings.map { it.message }}")
        assertTrue(findings.any { it.message.contains("读未声明键：DAY") })
        assertTrue(findings.any { it.message.contains("读未声明键：NOPE") })
    }

    @Test
    fun nonLiteralArgumentsAreSilentlySkipped() {
        val module = module()
        writeGrantSource(
            module,
            """
            |val key = pickKey()
            |val manifest = modDeclarations("eratw.mymod") {
            |    writeGrant(key)
            |}
            |val day = context.gameState.getIntVar(key)
            """.trimMargin(),
        )

        val findings = grantFindings(module)
        assertTrue(findings.isEmpty(), "变量实参抽不到就静默（宁可漏报不许假红）：${findings.map { it.message }}")
    }

    @Test
    fun crossPackageGrantOverlapIsWarnNotError() {
        val module = module()
        writeGrantSource(module, "val manifest = modDeclarations(\"eratw.mymod\") { writeGrant(\"DAY\") }")
        // 兄弟模块也声明同一键（同目录 mod-* 才进扫描面）。
        val sibling = tempDir.resolve("mod-brother")
        Files.createDirectories(sibling.resolve("src/main/kotlin/eratw/brother"))
        Files.writeString(
            sibling.resolve("src/main/kotlin/eratw/brother/BrotherDeclarations.kt"),
            "package eratw.brother\n\nval manifest = modDeclarations(\"eratw.brother\") { writeGrant(\"day\") }\n",
        )

        val findings = grantFindings(module)
        val warns = findings.filter { it.severity == Severity.WARN }
        assertTrue(
            warns.any { it.message.contains("writeGrant 跨包重叠") && it.message.contains("DAY") },
            "跨包重叠 WARN：${findings.map { it.message }}",
        )
        assertTrue(findings.none { it.severity == Severity.ERROR }, "重叠不拒包：${findings.map { it.message }}")
    }
}
