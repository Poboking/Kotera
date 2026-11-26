package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 测试里塞进脚手架的图片声明源码（行号可预期：1 = package，2 = 空行，3 = 正文首行）。 */
private const val UI_SAMPLE = "src/main/kotlin/eratw/mymod/UiSample.kt"

private const val IMAGE_WARNING = "图片资源不存在"

private const val IMAGE_ERROR = "图片资源名非法"

/** 往模块里写一份图片声明源码文件。 */
private fun writeUiSource(module: Path, body: String): Path {
    val file = module.resolve(UI_SAMPLE)
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\n$body\n")
    return file
}

/** 在包资源目录里放一个假图片文件（内容不重要，存在性才是被测面）。 */
private fun writeAsset(module: Path, relative: String) {
    val file = module.resolve("eratw.mymod.assets").resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "png-bytes")
}

/** 只挑图片声明面的发现，别把 schema/资源面的发现掺进断言。 */
private fun imageFindings(module: Path): List<Finding> =
    findingsOf(module).filter { finding -> finding.message.contains("图片") }

/**
 * `check-mod` 的图片元素声明面：源码文本抽 `modUiImage("...")` / `ModUiElement.Image(resourceName = "...")`，
 * 对照 `<packageId>.assets/` 判存在性（缺文件 WARN、名字非法 ERROR、抽不到静默通过），
 * 外加 `new-mod` 生成物的注释版图片示例与 README 说明。
 */
class ModCheckImagesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun existingImageProducesNoFinding() {
        val module = module()
        writeAsset(module, "images/banner.png")
        writeUiSource(module, "val banner = modUiImage(\"images/banner.png\")")

        val findings = findingsOf(module)

        assertNoError(findings)
        assertTrue(
            findings.none { it.message.contains("图片") },
            "文件存在时不该有图片面发现：${findings.joinToString("; ") { it.message }}",
        )
    }

    @Test
    fun missingImageReportsOneWarningWithFileAndLine() {
        val module = module()
        writeUiSource(module, "val banner = modUiImage(\"images/missing.png\")")

        val findings = findingsOf(module)
        val image = findings.filter { it.message.contains("图片") }

        assertNoError(findings)
        assertEquals(1, image.size, "缺文件应恰一条 WARN：${findings.joinToString("; ") { it.message }}")
        val finding = image.single()
        assertEquals(Severity.WARN, finding.severity, "缺文件是设计允许的降级，只能 WARN")
        assertTrue(finding.file.endsWith("UiSample.kt"), "应点名声明所在文件：${finding.file}")
        assertEquals(3, finding.line, "应点名声明所在行：${finding.line}")
        assertTrue(finding.message.contains("$IMAGE_WARNING：images/missing.png"), "message=${finding.message}")
    }

    @Test
    fun illegalImageNameReportsErrorAndExitOne() {
        val module = module()
        writeUiSource(module, "val banner = modUiImage(\"images/../banner.png\")")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        val image = imageFindings(module)
        assertEquals(1, image.size, "非法名应恰一条发现：${image.map { it.message }}")
        assertEquals(Severity.ERROR, image.single().severity)
        assertTrue(image.single().message.contains(IMAGE_ERROR), "message=${image.single().message}")
        assertEquals(1, exit, "图片面 ERROR 应让 check-mod 退出码为 1")
        assertTrue(captured.toString("UTF-8").contains(IMAGE_ERROR), "stdout=${captured.toString("UTF-8")}")
    }

    @Test
    fun noImageDeclarationIsSilent() {
        val module = module()

        assertTrue(imageFindings(module).isEmpty(), "没有图片声明时图片面必须静默")
    }

    @Test
    fun extractsBothDeclarationStyles() {
        val module = module()
        writeUiSource(
            module,
            """
            |val a = modUiImage("a.png")
            |val b = ModUiElement.Image(resourceName = "b.png")
            """.trimMargin(),
        )

        val image = imageFindings(module)

        assertEquals(2, image.size, "两种写法各抽一条：${image.map { it.message }}")
        assertTrue(image.all { it.severity == Severity.WARN })
        assertTrue(image.any { it.message.contains("$IMAGE_WARNING：a.png") }, "缺 modUiImage 那条")
        assertTrue(image.any { it.message.contains("$IMAGE_WARNING：b.png") }, "缺 ModUiElement.Image 那条")
    }

    @Test
    fun commentedDeclarationIsIgnored() {
        val module = module()
        writeUiSource(
            module,
            """
            |// val ghost = modUiImage("images/ghost.png")
            |/* val ghost2 = ModUiElement.Image(resourceName = "images/ghost2.png") */
            |val real = 1
            """.trimMargin(),
        )

        assertTrue(imageFindings(module).isEmpty(), "注释里的示例不能被当成声明")
    }

    @Test
    fun nonLiteralArgumentIsSkipped() {
        val module = module()
        writeUiSource(module, "val name = \"x.png\"\nval img = modUiImage(name)")

        assertTrue(imageFindings(module).isEmpty(), "非字面量实参抽不到就跳过（宁可漏报）")
    }

    @Test
    fun concatenatedAndInterpolatedLiteralsAreSkipped() {
        val module = module()
        writeUiSource(
            module,
            """
            |val a = modUiImage("images/" + name)
            |val b = ModUiElement.Image(resourceName = "images/${'$'}{name}.png")
            |val c = modUiImage("images/" + "banner.png")
            """.trimMargin(),
        )

        val findings = imageFindings(module)

        assertNoError(findings)
        assertTrue(
            findings.isEmpty(),
            "拼接/插值截出的片段（images/、images/${'$'}{name}.png）不是作者声明的资源名，" +
                "KDoc 口径是整段跳过，不许拿片段报非法名或缺文件：${findings.map { it.message }}",
        )
    }

    @Test
    fun scaffoldShipsCommentedImageExampleAndReadme() {
        val module = tempDir.resolve("mod-mymod")
        val exit = NewModScaffold.runCli(
            arrayOf("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", module.toString()),
        )

        assertEquals(0, exit)
        val mod = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()
        assertTrue(mod.contains("modUiImage(\"images/banner.png\""), "Mod.kt 缺注释版图片元素用法")
        assertTrue(mod.contains("assets/images/"), "Mod.kt 应说明图片放哪个目录")
        assertTrue(mod.contains("取消下面这行注释"), "Mod.kt 应说明怎么启用示例")

        val readme = module.resolve("eratw.mymod.assets/README.txt").readText()
        assertTrue(readme.contains("图片元素"), "assets README 缺图片元素说明")
        assertTrue(readme.contains("4 MiB"), "README 应写名单文件上限")
        assertTrue(readme.contains("BitmapFactory"), "README 应写清宿主解码口径")
    }

    @Test
    fun scaffoldCheckModeStillWritesNothing() {
        val module = tempDir.resolve("mod-preview")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            NewModScaffold.runCli(
                arrayOf(
                    "new-mod", "--package", "eratw.mymod", "--name", "My",
                    "--out", module.toString(), "--check",
                ),
                out,
            )
        }

        assertEquals(0, exit)
        assertFalse(Files.exists(module), "--check 不得落盘（含新增的资源条目）")
        assertTrue(
            captured.toString("UTF-8").contains("WOULD WRITE: eratw.mymod.assets/README.txt"),
            "预览缺 assets README：${captured.toString("UTF-8")}",
        )
    }

    @Test
    fun dslNamesStayInSyncWithModApiSource() {
        val source = repoRoot().resolve("mod-api/src/main/kotlin/com/eratw/port/modapi/ModUiElement.kt")
        val text = source.readText()

        assertTrue(text.contains("fun modUiImage("), "mod-api 侧 modUiImage DSL 改名了，文本抽取会漏：$source")
        assertTrue(text.contains("data class Image("), "mod-api 侧 ModUiElement.Image 改名了：$source")
        assertTrue(text.contains("val resourceName: String"), "mod-api 侧 resourceName 参数改名了：$source")
    }
}
