package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 本文件私有 CLI 副本（各 NewMod 测试文件自带一份，避免顶层声明撞名）。 */
private class OnboardingCliResult(val exit: Int, val out: String, val err: String)

private fun runOnboardingCli(vararg args: String): OnboardingCliResult {
    val capturedOut = ByteArrayOutputStream()
    val capturedErr = ByteArrayOutputStream()
    val exit = PrintStream(capturedOut, true, "UTF-8").use { out ->
        PrintStream(capturedErr, true, "UTF-8").use { err ->
            NewModScaffold.runCli(arrayOf(*args), out, err)
        }
    }
    return OnboardingCliResult(exit, capturedOut.toString("UTF-8"), capturedErr.toString("UTF-8"))
}

/**
 * DX-E：new-mod 生成物的**作者上手注释**面 —— 第一天拿到骨架就知道下一步做什么：
 * 身份三处一致、check-mod 自检命令、声明 DSL 指路、settings 声明↔读数配对、资源目录、事件注册。
 * 只断言注释关键句出现在生成物里（生成物能编译由 generateScaffoldSmoke 冒烟兜底）。
 */
class NewModOnboardingScaffoldTest {

    @TempDir
    lateinit var tempDir: Path

    private fun scaffoldModule(): Path {
        val module = tempDir.resolve("mod-mymod")
        val result = runOnboardingCli(
            "new-mod",
            "--package",
            "eratw.mymod",
            "--name",
            "My",
            "--out",
            module.toString(),
        )
        assertEquals(0, result.exit, "stderr=${result.err}")
        return module
    }

    @Test
    fun declarationsCarryCarrierIdentityAndCheckModGuidance() {
        val declarations = scaffoldModule()
            .resolve("src/main/kotlin/eratw/mymod/MyDeclarations.kt")
            .readText()
        // 身份三处一致性：PACKAGE_ID / override packageId / modDeclarations 首参。
        assertTrue(declarations.contains("包身份三处必须同值"), "缺身份三处一致指引")
        assertTrue(declarations.contains("modDeclarations 的**首参**"), "缺 modDeclarations 首参点名")
        // check-mod 自检命令（与 ModCheck 的 Usage 对齐：modgen check-mod <mod dir>）。
        assertTrue(declarations.contains("modgen check-mod <本模块目录>"), "缺 check-mod 自检命令")
        // modDeclarations DSL 入口指路 + settings 声明与读数一对的指引。
        assertTrue(declarations.contains("DeclDsl.kt"), "缺 DeclDsl 指路")
        assertTrue(
            declarations.contains("读数在 MyMod.kt 的 onDailyTick"),
            "缺 settings 声明→读数配对指引",
        )
    }

    @Test
    fun buildScriptCarriesFirstStepsAndAssetsPointer() {
        val build = scaffoldModule().resolve("build.gradle.kts").readText()
        assertTrue(build.contains("settings.gradle.kts"), "缺 include 第一步指引")
        assertTrue(build.contains(".assets/"), "缺随包资源目录指路")
        assertTrue(build.contains("README.txt"), "缺资源名字规则指引")
    }

    @Test
    fun modCarriesEventRegistrationGuidance() {
        val mod = scaffoldModule().resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()
        assertTrue(mod.contains("context.events.register("), "缺事件注册最小示例注释")
        assertTrue(mod.contains("EventBucket.NORMAL"), "缺事件桶示例")
        assertTrue(mod.contains("IllegalArgumentException"), "缺拼错事件名当场拒的说明")
        assertTrue(mod.contains("context.events.exists("), "缺事件名自查入口")
    }

    @Test
    fun onboardingCommentsKeepCheckModFindingsAtZero() {
        val module = scaffoldModule()
        // 与既有 0 发现基线用例同法：先按 schema 造出 src/generated，否则「未发现生成物目录」
        // 的 WARN 属接入前基线，与本笔的注释面无关。
        writeGenerated(module)
        assertEquals(emptyList(), findingsOf(module))
    }
}
