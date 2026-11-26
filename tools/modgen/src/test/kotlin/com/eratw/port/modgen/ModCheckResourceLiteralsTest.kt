package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 模块根上写 ERB 的文件名（行号可预期：正文首行 = 第 1 行）。 */
private const val ERB_SOURCE = "mod_vis.ERB"

/** 写一份模块根 ERB 源码（内容只要 ASCII；检查面按 ISO-8859-1 逐字节解码，行号不漂）。 */
private fun writeErb(module: Path, body: String) {
    Files.writeString(module.resolve(ERB_SOURCE), "$body\n")
}

/** 写一份 Kotlin 载体源码（`package` 后空一行，正文首行 = 第 3 行）。 */
private fun writeLiteralSource(module: Path, body: String) {
    val file = module.resolve("src/main/kotlin/eratw/mymod/LiteralSample.kt")
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\n$body\n")
}

/** 在旁挂资源树里放一个文件：`<rootName>/mod/<dir>/<relative>`。 */
private fun writeTreeFile(module: Path, rootName: String, dir: String, relative: String) {
    val file = module.resolve(rootName).resolve("mod").resolve(dir).resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "bytes")
}

/** 在包资源目录里放一个文件：`<packageId>.assets/<relative>`。 */
private fun writeAsset(module: Path, relative: String) {
    val file = module.resolve("eratw.mymod.assets").resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "png-bytes")
}

/** 只挑资源字面量面的发现（token / 汇总 WARN / 包资源缺文件），不掺别的检查面。 */
private fun literalFindings(module: Path, declaredDir: String? = null): List<Finding> =
    ModCheck.check(module.toFile(), emptyList(), declaredDir).filter { finding ->
        finding.message.contains(ModCheckResourceLiterals.MISSING_TOKEN) ||
            finding.message.contains("资源字面量") ||
            finding.message.contains("旁挂资源树不存在") ||
            finding.message.contains("包资源目录不存在") ||
            finding.message.contains("包资源不存在") ||
            finding.message.contains("资源名非法")
    }

/**
 * 资源字面量面：Kotlin 载体的 `context.resources.*` / `context.ui.registerImage` 对照
 * `<packageId>.assets/`，ERB 的 `GCREATEFROMFILE`/`LOADTEXT`/`PLAYSOUND` 对照
 * `resources/mod/<dir>/`、`sound/mod/<dir>/`；缺文件/名字非法/树缺失的严重度与静默边界见
 * [ModCheckResourceLiterals] 的 KDoc。
 */
class ModCheckResourceLiteralsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun sidecarImageLiteralHittingRealFileIsSilent() {
        val module = module()
        writeTreeFile(module, "resources", "mydir", "a.png")
        writeErb(module, "GCREATEFROMFILE \"mod/mydir/a.png\", 0")

        val findings = literalFindings(module, "mydir")

        assertNoError(findings)
        assertTrue(findings.isEmpty(), "字面量命中真实文件时资源字面量面必须静默：${findings.map { it.message }}")
    }

    @Test
    fun sidecarImageLiteralMissingFileIsErrorWithLocation() {
        val module = module()
        writeTreeFile(module, "resources", "mydir", "keep.txt")
        writeErb(module, "GCREATEFROMFILE \"mod/mydir/missing.png\", 0")

        val findings = literalFindings(module, "mydir")

        assertEquals(1, findings.size, "缺文件应恰一条：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.ERROR, finding.severity, "ERB 侧缺资源没有任何运行期提示，必须 ERROR")
        assertTrue(finding.file.endsWith(ERB_SOURCE), "应点名 ERB 文件：${finding.file}")
        assertEquals(1, finding.line, "应点名 ERB 行号：${finding.line}")
        assertTrue(finding.message.contains(ModCheckResourceLiterals.MISSING_TOKEN), "message=${finding.message}")
        assertTrue(finding.message.contains("resources/mod/mydir/missing.png"), "应写真实落点：${finding.message}")
    }

    @Test
    fun erbVariableLiteralIsSkipped() {
        val module = module()
        writeTreeFile(module, "resources", "mydir", "keep.txt")
        writeErb(
            module,
            """
            GCREATEFROMFILE "mod/%DIR%/a.png", 0
            LOADTEXT "resources/mod/mydir/%NAME%.txt"
            """.trimIndent(),
        )

        val findings = literalFindings(module, "mydir")

        assertNoError(findings)
        assertTrue(findings.isEmpty(), "% 变量插值抽不到字面量必须静默（宁可漏报）：${findings.map { it.message }}")
    }

    @Test
    fun concatenatedKotlinAndErbLiteralsAreSkipped() {
        val module = module()
        writeTreeFile(module, "resources", "mydir", "keep.txt")
        writeLiteralSource(module, "val bytes = context.resources.open(\"images/\" + name)")
        writeErb(module, "GCREATEFROMFILE \"mod/mydir/\" + name, 0")

        val findings = literalFindings(module, "mydir")

        assertNoError(findings)
        assertTrue(
            findings.isEmpty(),
            "拼接截出的片段（images/、mod/mydir/）不是作者声明的资源名，KDoc 口径是整段跳过，" +
                "不许拿片段报资源名非法：${findings.map { it.message }}",
        )
    }

    @Test
    fun audioTreeLiteralHittingRealFileIsSilent() {
        val module = module()
        writeTreeFile(module, "sound", "mydir", "se.mp3")
        writeErb(module, "PLAYSOUND \"mod/mydir/se.mp3\"")

        val findings = literalFindings(module, "mydir")

        assertNoError(findings)
        assertTrue(findings.isEmpty(), "PLAYSOUND 命中 sound/mod/<dir>/ 时静默：${findings.map { it.message }}")
    }

    @Test
    fun audioTreeLiteralMissingFileIsError() {
        val module = module()
        writeTreeFile(module, "sound", "mydir", "keep.mp3")
        writeErb(module, "PLAYSOUND \"mod/mydir/missing.mp3\"")

        val findings = literalFindings(module, "mydir")

        val finding = findings.singleOrNull() ?: error("期望恰一条发现：${findings.map { it.message }}")
        assertEquals(Severity.ERROR, finding.severity)
        assertTrue(finding.message.contains("sound/mod/mydir/missing.mp3"), "音频落点应是 sound/：${finding.message}")
    }

    @Test
    fun declaredDirMismatchDoesNotDuplicateMediaError() {
        val module = module()
        writeTreeFile(module, "resources", "other", "a.png")
        writeErb(module, "GCREATEFROMFILE \"mod/mydir/a.png\", 0")

        val all = ModCheck.check(module.toFile(), emptyList(), "mydir")
        val errors = errorsOf(all)

        assertTrue(errors.isNotEmpty(), "声明目录与现实目录不符应由旁挂资源树面报错")
        assertTrue(
            all.none { it.message.contains(ModCheckResourceLiterals.MISSING_TOKEN) },
            "树名不符已由旁挂资源树面报 ERROR，字面量面不得重复报：${all.map { it.message }}",
        )
    }

    @Test
    fun missingTreeWithLiteralsReportsSingleWarning() {
        val module = module()
        writeErb(
            module,
            """
            GCREATEFROMFILE "mod/mydir/a.png", 0
            GCREATEFROMFILE "mod/mydir/b.png", 0
            """.trimIndent(),
        )

        val all = ModCheck.check(module.toFile(), emptyList())
        val warnings = all.filter { it.message.contains("旁挂资源树不存在") }

        assertEquals(1, warnings.size, "树整体缺失只汇总一条 WARN，不逐条刷屏：${all.map { it.message }}")
        assertEquals(Severity.WARN, warnings.single().severity, "没声明 <dir> 时树缺失只 WARN（可能故意引用外部）")
        assertTrue(warnings.single().message.contains("2 处"), "汇总应带条数：${warnings.single().message}")
        assertNoError(all)
    }

    @Test
    fun externalDirLiteralIsSilent() {
        val module = module()
        writeTreeFile(module, "resources", "mydir", "a.png")
        writeErb(
            module,
            """
            GCREATEFROMFILE "mod/other-mod/a.png", 0
            PLAYSOUND "se/base.mp3"
            """.trimIndent(),
        )

        val findings = literalFindings(module, "mydir")

        assertNoError(findings)
        assertTrue(findings.isEmpty(), "非本包 <dir> 的路径必须静默：${findings.map { it.message }}")
    }

    @Test
    fun kotlinResourceLiteralHittingAssetIsSilent() {
        val module = module()
        writeAsset(module, "images/x.png")
        writeLiteralSource(module, "val bytes = context.resources.open(\"images/x.png\")")

        val findings = literalFindings(module)

        assertNoError(findings)
        assertTrue(findings.isEmpty(), "包资源命中时静默：${findings.map { it.message }}")
    }

    @Test
    fun kotlinResourceLiteralMissingAssetIsWarning() {
        val module = module()
        writeAsset(module, "images/keep.png")
        writeLiteralSource(module, "val bytes = context.resources.open(\"images/missing.png\")")

        val findings = literalFindings(module)

        assertEquals(1, findings.size, "缺文件应恰一条：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity, "运行期 open/size 返 null、图片按 altText 降级，非必然失效故 WARN")
        assertTrue(finding.message.contains("包资源不存在：images/missing.png"), "message=${finding.message}")
    }

    @Test
    fun kotlinRegisterImageLiteralIsChecked() {
        val module = module()
        writeAsset(module, "images/keep.png")
        writeLiteralSource(module, "registrations += context.ui.registerImage(\"images/banner.png\", altText = \"b\")")

        val finding = literalFindings(module).singleOrNull() ?: error("registerImage 字面量应被检查")

        assertEquals(Severity.WARN, finding.severity)
        assertTrue(finding.message.contains("images/banner.png"), "message=${finding.message}")
    }

    @Test
    fun jsonOutputCarriesStableTokenAndKeepsShape() {
        val module = module()
        writeTreeFile(module, "resources", "mydir", "keep.txt")
        writeErb(module, "GCREATEFROMFILE \"mod/mydir/missing.png\", 0")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString(), "--resources", "mydir", "--json"), out)
        }
        val json = captured.toString("UTF-8")

        assertEquals(1, exit, "缺字面量 ERROR 应让 check-mod 退出码为 1")
        assertTrue(json.contains("\"schema\": \"1\""), "JSON schema 形状未变：$json")
        assertTrue(json.contains("\"ok\": false"), "JSON 应判 not-ok：$json")
        assertTrue(json.contains("\"severity\": \"ERROR\""), "finding 仍是四键形状：$json")
        assertTrue(json.contains(ModCheckResourceLiterals.MISSING_TOKEN), "CI 需要稳定标记：$json")
    }

    @Test
    fun cleanScaffoldGetsNoResourceLiteralFinding() {
        val module = module()

        val findings = literalFindings(module)

        assertTrue(findings.isEmpty(), "干净脚手架不得新增发现（既有契约不漂移）：${findings.map { it.message }}")
    }
}
