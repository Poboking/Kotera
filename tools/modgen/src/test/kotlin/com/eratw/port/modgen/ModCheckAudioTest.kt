package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 测试里塞进脚手架的音频声明源码（行号可预期：1 = package，2 = 空行，3 = 正文首行）。 */
private const val AUDIO_SAMPLE = "src/main/kotlin/eratw/mymod/AudioSample.kt"

private const val AUDIO_WARNING = "音频资源不存在"

private const val AUDIO_ERROR = "音频资源名非法"

/** 往模块里写一份音频声明源码文件。 */
private fun writeAudioSource(module: Path, body: String): Path {
    val file = module.resolve(AUDIO_SAMPLE)
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\n$body\n")
    return file
}

/** 在包资源目录里放一个假音频文件（内容不重要，存在性才是被测面）。 */
private fun writeAsset(module: Path, relative: String) {
    val file = module.resolve("eratw.mymod.assets").resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "ogg-bytes")
}

/** 只挑音频播放声明面的发现，别把 schema/文案/资源面的发现掺进断言。 */
private fun audioFindings(module: Path): List<Finding> =
    findingsOf(module).filter { finding -> finding.message.contains("音频") }

/**
 * `check-mod` 的音频播放声明面（v11 `AudioApi`）：源码文本抽 `audio.play("...")` 的名字，
 * 对照 `<packageId>.assets/` 判存在性（缺文件 WARN、名字非法 ERROR、抽不到静默通过），
 * 外加 `new-mod` 生成物的注释版音频示例与 README 说明。
 */
class ModCheckAudioTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun existingAudioProducesNoFinding() {
        val module = module()
        writeAsset(module, "audio/theme.ogg")
        writeAudioSource(module, "fun start(context: ModContext) {\n    context.audio.play(\"audio/theme.ogg\")\n}")

        assertTrue(audioFindings(module).isEmpty(), "文件在就什么都不该报：${audioFindings(module)}")
    }

    @Test
    fun missingAudioProducesSingleWarningWithLocation() {
        val module = module()
        writeAudioSource(module, "fun start(context: ModContext) {\n    context.audio.play(\"audio/missing.ogg\")\n}")

        val findings = audioFindings(module)
        assertEquals(1, findings.size, "缺文件只报一条 WARN：$findings")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity, "缺文件是降级（play 只回 false + WARN），不该假红")
        assertEquals(4, finding.line, "行号应指向 play 调用那行（1 = package，2 = 空行，3 = 函数头）")
        assertNotNull(finding.file, "缺文件应带定位文件")
        assertTrue(finding.file.contains("AudioSample.kt"), "定位文件应是声明所在源文件：${finding.file}")
        assertTrue(finding.message.contains(AUDIO_WARNING), "文案应点名音频面：${finding.message}")
        assertTrue(finding.message.contains("eratw.mymod.assets/"), "文案应给出期望资源根：${finding.message}")
    }

    @Test
    fun illegalAudioNameIsErrorAndFailsCli() {
        val module = module()
        writeAudioSource(module, "fun start(context: ModContext) {\n    context.audio.play(\"audio/../escape.ogg\")\n}")

        val findings = audioFindings(module)
        assertEquals(1, findings.size, "非法名只报一条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "非法名应 ERROR")
        assertTrue(findings.single().message.contains(AUDIO_ERROR), "文案应点名非法：${findings.single().message}")

        val captured = ByteArrayOutputStream()
        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }
        assertEquals(1, exit, "有 ERROR 时 check-mod 退出码应为 1；out=${captured.toString("UTF-8")}")
    }

    @Test
    fun nonLiteralArgumentIsSilentlySkipped() {
        val module = module()
        writeAudioSource(module, "fun start(context: ModContext, name: String) {\n    context.audio.play(name)\n}")

        assertTrue(audioFindings(module).isEmpty(), "抽不到字面量就跳过（宁可漏报，不许假红）")
    }

    @Test
    fun concatenatedArgumentIsSilentlySkipped() {
        val module = module()
        writeAudioSource(
            module,
            "fun start(context: ModContext) {\n" +
                "    context.audio.play(\"audio/\" + \"theme.ogg\")\n" +
                "}",
        )

        assertTrue(audioFindings(module).isEmpty(), "字符串拼接抽不到首字面量就跳过（宁可漏报）")
    }

    @Test
    fun commentedOutPlayIsIgnored() {
        val module = module()
        writeAudioSource(
            module,
            "fun start(context: ModContext) {\n    // context.audio.play(\"audio/missing.ogg\")\n}",
        )

        assertTrue(audioFindings(module).isEmpty(), "注释里的播放声明不该被当成真声明")
    }

    /**
     * 骨架真调 `audio.play`，但播放名走 `Keys.THEME_AUDIO` 常量 —— 本面只抽 `play("…")` 字面量实参，
     * 抽不到就静默（宁可漏报）。钉的是「开箱骨架 0 音频发现」：作者拿到新模块不该先看到一条自己
     * 没写错的告警；要让它自检这个名字，就把调用改成字面量。
     */
    @Test
    fun scaffoldedModuleHasNoAudioFinding() {
        val module = module()

        assertNoError(findingsOf(module))
        assertTrue(audioFindings(module).isEmpty(), "脚手架应保持 0 音频发现：${audioFindings(module)}")
        val mod = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()
        assertTrue(
            mod.contains("context.audio.play(") && mod.contains("Keys.THEME_AUDIO"),
            "骨架必须真调 play 且走常量名 —— 否则「0 发现」是因为根本没调用，而不是常量引用",
        )
    }

    @Test
    fun scaffoldShipsRealAudioCallAndReadme() {
        val module = tempDir.resolve("mod-mymod")
        val exit = NewModScaffold.runCli(
            arrayOf("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", module.toString()),
        )

        assertEquals(0, exit)
        val mod = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()
        assertTrue(mod.contains("context.audio.play("), "Mod.kt 缺音频真调用")
        assertTrue(mod.contains("Keys.THEME_AUDIO"), "Mod.kt 的播放名应走 Keys.THEME_AUDIO 常量")
        assertTrue(mod.contains("context.audio.stop()"), "Mod.kt 缺 onDisable 的 stop() 收尾")
        assertTrue(mod.contains("assets/audio/"), "Mod.kt 应说明音频放哪个目录")

        val readme = module.resolve("eratw.mymod.assets/README.txt").readText()
        assertTrue(readme.contains("音频（v11）"), "assets README 缺音频说明")
        assertTrue(readme.contains("Keys.THEME_AUDIO"), "README 应写清骨架的播放名走常量")
        assertTrue(readme.contains("context.audio.stop()"), "README 应写 stop() 收尾")
        assertTrue(readme.contains("false"), "README 应写清 play 失败只回 false 不抛")
        assertTrue(readme.contains("字面量"), "README 应写清 check-mod 只抽字面量、常量引用静默")
        assertFalse(readme.contains("invalidate"), "音频走独立播放汇，不该提图片缓存失效口径")
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
        assertFalse(Files.exists(module), "--check 不得落盘（含音频 README 条目）")
    }

    @Test
    fun playNameStaysInSyncWithAudioApiSource() {
        val source = repoRoot().resolve("mod-api/src/main/kotlin/com/eratw/port/modapi/AudioApi.kt")
        val text = source.readText()

        assertTrue(text.contains("fun play("), "mod-api 侧 AudioApi.play 改名了，文本抽取会漏：$source")
        assertTrue(text.contains("resourceName: String"), "mod-api 侧 play 的首参改名了：$source")
        assertTrue(text.contains("fun stop()"), "mod-api 侧 stop 改名了：$source")
    }
}
