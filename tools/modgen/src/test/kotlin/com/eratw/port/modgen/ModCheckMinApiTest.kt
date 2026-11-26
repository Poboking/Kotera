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
 * `check-mod` 的 min-api 声明面判据：载体源码 `override val minApi: Int = <字面量>` 的
 * 负数 ⇒ ERROR、高于当前 `ModApi.VERSION` ⇒ WARN、0 / 等于 VERSION 不报。
 *
 * 夹具是「临时假仓库（`settings.gradle.kts` + mod-api 的 `VERSION` 真源）+ new-mod 骨架」：
 * 引擎版本从假仓库源码抽，所以用例不写死版本号，也不依赖真实仓库当前是 v几。
 */
class ModCheckMinApiTest {

    @TempDir
    lateinit var tempDir: Path

    /** 声明落在载体正文第 1 行 ⇒ 文件第 4 行（1 = package、2 = 空行、3 = 类头）。 */
    private val declarationLine = 4

    @Test
    fun negativeLiteralIsErrorWithFileAndLine() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = -1")

        val found = minApiFindings(module)

        assertEquals(1, found.size, "负数应恰一条发现：${found.map { it.message }}")
        val finding = found.single()
        assertEquals(Severity.ERROR, finding.severity, "装配期 require 必抛 ⇒ 必然失效，只能 ERROR")
        assertTrue(finding.file.endsWith("MinApiSample.kt"), "应点名声明所在文件：${finding.file}")
        assertEquals(declarationLine, finding.line, "应点名声明所在行：${finding.line}")
        assertTrue(finding.message.contains("负数"), "message=${finding.message}")
        assertTrue(finding.message.contains("-1"), "message 应带声明值：${finding.message}")
    }

    @Test
    fun negativeLiteralFailsCliWithExitOne() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = -3")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        val stdout = captured.toString("UTF-8")
        assertEquals(1, exit, "min-api 面 ERROR 应让 check-mod 退出码为 1")
        assertTrue(stdout.contains("MinApiSample.kt:4: ERROR: min-api 声明为负数"), "stdout=$stdout")
    }

    @Test
    fun zeroMeansNoGateAndIsSilent() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = 0")

        assertTrue(minApiFindings(module).isEmpty(), "0 = 不设闸（pack 侧也不写该键），不报")
    }

    @Test
    fun exactlyCurrentVersionIsSilent() {
        val version = repoMinApiVersion()
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = $version")

        assertTrue(minApiFindings(module).isEmpty(), "等于 VERSION 是缺省口径，不报")
    }

    @Test
    fun belowCurrentVersionIsSilent() {
        val version = repoMinApiVersion()
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = ${version - 1}")

        assertTrue(minApiFindings(module).isEmpty(), "低于 VERSION 的声明合法，不报：${minApiFindings(module)}")
    }

    @Test
    fun aboveCurrentVersionWarnsButCliStillExitsZero() {
        val version = repoMinApiVersion()
        val target = version + 1
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = $target")
        val captured = ByteArrayOutputStream()

        val found = minApiFindings(module)
        assertEquals(1, found.size, "高于 VERSION 应恰一条 WARN：${found.map { it.message }}")
        val finding = found.single()
        assertEquals(Severity.WARN, finding.severity, "等引擎升级即可装载，属预留 ⇒ 只 WARN")
        assertTrue(
            finding.message.contains("要等引擎升到 v$target"),
            "应点名要等引擎升到哪个版本：${finding.message}",
        )
        assertTrue(finding.message.contains("ModApi.VERSION = $version"), "应点名当前引擎版本：${finding.message}")

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }
        assertEquals(0, exit, "只有 WARN 时 check-mod 必须退 0")
    }

    @Test
    fun farFutureVersionWarns() {
        val module = minApiModule(tempDir)
        writeMinApiCarrier(module, "    override val minApi: Int = 999")

        val found = minApiFindings(module)
        assertEquals(1, found.size, "999 应恰一条 WARN：${found.map { it.message }}")
        assertEquals(Severity.WARN, found.single().severity)
        assertTrue(found.single().message.contains("要等引擎升到 v999"), "message=${found.single().message}")
    }

    @Test
    fun engineVersionComesFromModApiSourceNotHardcoded() {
        // 假仓库 VERSION = 5：minApi = 6 必须按「高于当前引擎」判 WARN，说明上限不是写死的 17。
        val module = minApiModule(tempDir, version = 5)
        writeMinApiCarrier(module, "    override val minApi: Int = 6")

        val found = minApiFindings(module)
        assertEquals(1, found.size, "假仓库 VERSION=5 下 6 应判 WARN：${found.map { it.message }}")
        assertEquals(Severity.WARN, found.single().severity)
        assertTrue(found.single().message.contains("ModApi.VERSION = 5"), "message=${found.single().message}")
    }

    @Test
    fun versionConstantSentinelInRealModApiSource() {
        val text = Files.readString(repoRoot().resolve("mod-api/src/main/kotlin/com/eratw/port/modapi/ModApi.kt"))

        assertTrue(
            text.contains("const val VERSION: Int ="),
            "mod-api 侧 VERSION 声明形态改了，源码抽取会漏（本面会静默失效）：$text",
        )
    }
}
