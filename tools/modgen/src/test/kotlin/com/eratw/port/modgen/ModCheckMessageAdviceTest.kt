package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * check-mod 报错消息的「作者友好化」专项：ERROR/WARN 消息必须点名
 * 「位置（文件名或键名/包名）+ 违规事实 + 一句话修复建议」。
 *
 * 本文件只断言消息形态（点名与建议片段），严重度与退出码的口径归各自的 ModCheck*Test。
 */
class ModCheckMessageAdviceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun staleGeneratedMessageNamesSchemaFileAndRegenAdvice() {
        val module = module()
        // 在生成物尾部多写一行，让生成物与 schema 不再逐字节一致（最常见：改了 schema 忘了重生成）。
        val generated = generatedFile(module)
        Files.writeString(generated, generated.readText() + "\n")

        val stale = findingsOf(module).filter { it.message.contains("生成物过期") }

        assertEquals(1, stale.size, "过期生成物应恰一条 ERROR：${stale.map { it.message }}")
        val finding = stale.single()
        assertTrue(
            finding.file.endsWith("generated/My.kt"),
            "发现应点名生成物文件本身：${finding.file}",
        )
        assertTrue(
            finding.message.contains("My.schema.tsv"),
            "消息应点名对应的 schema 文件：${finding.message}",
        )
        assertTrue(
            finding.message.contains(":tools:modgen-accessors"),
            "消息应给出重跑生成器的修复建议：${finding.message}",
        )
    }

    @Test
    fun fieldKeyNotInSchemaMessageNamesKeyAndFile() {
        val module = module()
        val declarations = declarationsFile(module)
        replaceOnce(
            declarations,
            """val NICKNAME: FieldKey<String> = strKey("eratw.mymod.nickname")""",
            "val NICKNAME: FieldKey<String> = strKey(\"eratw.mymod.nickname\")\n" +
                """        val EXTRA: FieldKey<Long> = intKey("eratw.mymod.extra")""",
        )

        val unknown = findingsOf(module).filter { it.message.contains("未在任何 schema 中声明") }

        assertEquals(1, unknown.size, "多余字段键应恰一条 ERROR：${unknown.map { it.message }}")
        val finding = unknown.single()
        assertTrue(
            finding.message.contains("eratw.mymod.extra"),
            "消息应点名多余的键名：${finding.message}",
        )
        assertTrue(
            finding.file == "src/main/kotlin/eratw/mymod/MyDeclarations.kt" && finding.line != null,
            "发现应点名键声明所在的文件与行：${finding.file}:${finding.line}",
        )
        assertTrue(
            finding.message.contains("field 行"),
            "消息应给出核对 schema field 行的建议：${finding.message}",
        )
    }

    @Test
    fun missingAudioMessageNamesPackageAssetRootAndFixAdvice() {
        val module = module()
        val sample = module.resolve("src/main/kotlin/eratw/mymod/AudioAdviceSample.kt")
        Files.createDirectories(sample.parent)
        Files.writeString(
            sample,
            "package eratw.mymod\n\nfun tick(context: com.eratw.port.modapi.ModContext) {\n" +
                "    context.audio.play(\"audio/missing.ogg\")\n}\n",
        )

        val missing = findingsOf(module).filter { it.message.contains("音频资源不存在") }

        assertEquals(1, missing.size, "缺音频文件应恰一条 WARN：${missing.map { it.message }}")
        val finding = missing.single()
        assertTrue(
            finding.message.contains("音频资源不存在：audio/missing.ogg"),
            "消息应点名缺失的资源名：${finding.message}",
        )
        assertTrue(
            finding.message.contains("eratw.mymod.assets/"),
            "消息应点名包资源根（包名 + assets 目录）：${finding.message}",
        )
        assertTrue(
            finding.message.contains("把文件放进"),
            "消息应给出放置文件或修正资源名的建议：${finding.message}",
        )
    }

    @Test
    fun missingCarrierPackageIdMessageNamesFixAdvice() {
        val module = module()
        val declarations = declarationsFile(module)
        replaceOnce(declarations, "    const val PACKAGE_ID: String = \"eratw.mymod\"\n", "")

        val missing = findingsOf(module).filter { it.message.contains("缺少 const val PACKAGE_ID") }

        assertEquals(1, missing.size, "缺 PACKAGE_ID 应恰一条 ERROR：${missing.map { it.message }}")
        val finding = missing.single()
        assertEquals("src/main/kotlin/eratw/mymod/MyDeclarations.kt", finding.file, "应点名载体声明文件")
        assertTrue(
            finding.message.contains("三处一致"),
            "消息应给出与 packageId、manifest 首参对齐的建议：${finding.message}",
        )
    }
}
