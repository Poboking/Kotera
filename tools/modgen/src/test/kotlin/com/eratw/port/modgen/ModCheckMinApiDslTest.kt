package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `check-mod` 的 min-api 声明面**主判据**：载体源码 `modDeclarations(…, minApi = <字面量>) { … }`
 * 命名实参（旧 `override val minApi` 形态另见 [ModCheckMinApiTest]，那是兼容扫描）。
 *
 * 判据沿用 DB 波口径：负数 ⇒ ERROR；高于当前 `ModApi.VERSION` ⇒ WARN；0 / 等于 / 低于 VERSION
 * 不报；抽不到字面量（变量 / 表达式 / 位置实参 / 注释）⇒ 整面静默（宁可漏报，不许假红）。
 */
class ModCheckMinApiDslTest {

    @TempDir
    lateinit var tempDir: Path

    /** 声明落在载体正文第 1 行 ⇒ 文件第 4 行（1 = package、2 = 空行、3 = 类头）。 */
    private val declarationLine = 4

    @Test
    fun negativeDslLiteralIsErrorWithFileAndLine() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = -1""")

        val found = minApiFindings(module)

        assertEquals(1, found.size, "负数应恰一条发现：${found.map { it.message }}")
        val finding = found.single()
        assertEquals(Severity.ERROR, finding.severity, "装配期 require 必抛 ⇒ 必然失效，只能 ERROR")
        assertTrue(finding.file.endsWith("MinApiSample.kt"), "应点名声明所在文件：${finding.file}")
        assertEquals(declarationLine, finding.line, "应点名声明所在行：${finding.line}")
        assertTrue(finding.message.contains("负数") && finding.message.contains("-1"), "message=${finding.message}")
    }

    @Test
    fun negativeDslLiteralFailsCliWithExitOne() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = -3""")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        val stdout = captured.toString("UTF-8")
        assertEquals(1, exit, "min-api 面 ERROR 应让 check-mod 退出码为 1")
        assertTrue(stdout.contains("MinApiSample.kt:4: ERROR: min-api 声明为负数"), "stdout=$stdout")
    }

    @Test
    fun zeroDslLiteralIsSilent() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = 0""")

        assertTrue(minApiFindings(module).isEmpty(), "0 = 不设闸（pack 侧也不写该键），不报")
    }

    @Test
    fun exactlyCurrentDslVersionIsSilent() {
        val version = repoMinApiVersion()
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = $version""")

        assertTrue(minApiFindings(module).isEmpty(), "等于 VERSION 是缺省口径，不报")
    }

    @Test
    fun aboveCurrentDslVersionWarnsButCliStillExitsZero() {
        val module = minApiModule(tempDir, version = 5)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = 6""")
        val captured = ByteArrayOutputStream()

        val found = minApiFindings(module)
        assertEquals(1, found.size, "高于 VERSION 应恰一条 WARN：${found.map { it.message }}")
        val finding = found.single()
        assertEquals(Severity.WARN, finding.severity, "等引擎升级即可装载，属预留 ⇒ 只 WARN")
        assertTrue(finding.message.contains("ModApi.VERSION = 5"), "应点名当前引擎版本：${finding.message}")

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }
        assertEquals(0, exit, "只有 WARN 时 check-mod 必须退 0")
    }

    @Test
    fun farFutureDslVersionWarns() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = 999""")

        val found = minApiFindings(module)
        assertEquals(1, found.size, "999 应恰一条 WARN：${found.map { it.message }}")
        assertEquals(Severity.WARN, found.single().severity)
        assertTrue(found.single().message.contains("要等引擎升到 v999"), "message=${found.single().message}")
    }

    @Test
    fun missingMinApiArgumentIsSilent() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", version = "1.0"""")

        assertTrue(minApiFindings(module).isEmpty(), "没写 minApi = 没设闸，不报")
    }

    @Test
    fun variableDslValueSilencesWholeFace() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = target""")

        assertTrue(minApiFindings(module).isEmpty(), "抽不到字面量 ⇒ 整面静默，不当证据报红")
    }

    @Test
    fun expressionDslValueSilencesWholeFace() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", minApi = 1 + 1""")

        assertTrue(minApiFindings(module).isEmpty(), "表达式不是字面量 ⇒ 整面静默")
    }

    @Test
    fun positionalThirdArgumentIsNotRead() {
        val module = minApiModule(tempDir)
        writeMinApiDeclarationsCarrier(module, """"eratw.mymod", "1.0", 999""")

        assertTrue(minApiFindings(module).isEmpty(), "只认命名实参 minApi = …；位置实参不抽（宁可漏报）")
    }

    @Test
    fun multilineCallIsDetectedWithInnerLineNumber() {
        val module = minApiModule(tempDir)
        val file = module.resolve("src/main/kotlin/eratw/mymod/MinApiSample.kt")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            "package eratw.mymod\n\nclass MinApiCarrier : EraMod {\n" +
                "    override val manifest: ModManifest = modDeclarations(\n" +
                "        packageId = \"eratw.mymod\",\n" +
                "        minApi = -3,\n" +
                "    )\n}\n",
        )

        val found = minApiFindings(module)

        assertEquals(1, found.size, "跨行调用的命名实参也应抽到：${found.map { it.message }}")
        val finding = found.single()
        assertEquals(Severity.ERROR, finding.severity)
        assertEquals(6, finding.line, "行号应指向 minApi 实参所在行：${finding.line}")
    }

    @Test
    fun commentedDslDeclarationIsIgnored() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(
            module,
            "    // modDeclarations(\"eratw.mymod\", minApi = -9) 是注释，不算声明",
        )

        assertTrue(minApiFindings(module).isEmpty(), "注释已被 ModCheckSources 剥掉，不得误报")
    }
}
