package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * min-api 声明面的**静默边界**：抽不到字面量 / 抽不到 `ModApi.VERSION` 真源 / 没有声明时，
 * 宁可漏报也不许假红（与 [ModCheckImages] / [ModCheckStrings] 同一套「文本抽取不到就沉默」口径）。
 */
class ModCheckMinApiSilenceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun variableValueGoesSilent() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    private val target = 999\n    override val minApi: Int = target")

        assertTrue(minApiFindings(module).isEmpty(), "变量实参抽不到 → 整面静默：${minApiFindings(module)}")
    }

    @Test
    fun expressionValueGoesSilent() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = 1 + 1")

        assertTrue(minApiFindings(module).isEmpty(), "表达式抽不到 → 整面静默：${minApiFindings(module)}")
    }

    @Test
    fun getterFormGoesSilent() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int get() = 999")

        assertTrue(minApiFindings(module).isEmpty(), "getter 形式抽不到 → 整面静默：${minApiFindings(module)}")
    }

    @Test
    fun opaqueDeclarationSilencesWholeFace() {
        val module = minApiModule(tempDir)
        // 同一模块里一处负数（本可报）、一处抽不到：抽不全就不能下任何结论，整面静默。
        writeMinApiCarrier(module, "    override val minApi: Int = -1")
        val opaque = module.resolve("src/main/kotlin/eratw/mymod/Opaque.kt")
        Files.createDirectories(opaque.parent)
        Files.writeString(
            opaque,
            "package eratw.mymod\n\nclass Opaque : EraMod {\n    override val minApi: Int = target\n}\n",
        )

        assertTrue(minApiFindings(module).isEmpty(), "有一处抽不到就整面静默：${minApiFindings(module)}")
    }

    @Test
    fun commentedDeclarationIsIgnored() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(
            module,
            """
            |    // override val minApi: Int = -1
            |    /* override val minApi: Int = 999 */
            |    private val real = 1
            """.trimMargin(),
        )

        assertTrue(minApiFindings(module).isEmpty(), "注释里的示例不是声明：${minApiFindings(module)}")
    }

    @Test
    fun noDeclarationIsSilent() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    private val real = 1")

        assertTrue(minApiFindings(module).isEmpty(), "没声明 minApi 时什么都不报：${minApiFindings(module)}")
    }

    @Test
    fun missingModApiSourceGoesSilent() {
        // 有仓库根标记但读不到 mod-api 真源：没有「当前引擎版本」这个上限，判据无从谈起。
        fakeRepoRoot(tempDir)
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        writeMinApiCarrier(module, "    override val minApi: Int = 999")

        assertTrue(minApiFindings(module).isEmpty(), "真源缺失 → 整面静默：${minApiFindings(module)}")
    }

    @Test
    fun missingVersionConstantGoesSilent() {
        // mod-api 源码在，但没有 `const val VERSION`（抽取口径漂移的形态）：静默，不许拿默认值硬判。
        fakeRepoRoot(tempDir)
        val api = tempDir.resolve("mod-api/src/main/kotlin/com/eratw/port/modapi/ModApi.kt")
        Files.createDirectories(api.parent)
        Files.writeString(api, "package com.eratw.port.modapi\n\nobject ModApi {\n    val VERSION_TEXT = \"17\"\n}\n")
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        writeMinApiCarrier(module, "    override val minApi: Int = 999")

        assertTrue(minApiFindings(module).isEmpty(), "VERSION 抽不到 → 整面静默：${minApiFindings(module)}")
    }
}
