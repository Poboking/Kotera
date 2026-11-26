package com.eratw.port.modgen

import com.eratw.port.modgen.accessors.AccessorEmit
import com.eratw.port.modgen.accessors.parseSchema
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 一次 CLI 调用的三条观测面：退出码 + stdout + stderr。 */
private class CliResult(val exit: Int, val out: String, val err: String)

/** 直接调 [NewModScaffold.runCli]（进程内），与 `tools/modgen-accessors` 的测试同法。 */
private fun run(vararg args: String): CliResult {
    val capturedOut = ByteArrayOutputStream()
    val capturedErr = ByteArrayOutputStream()
    val exit = PrintStream(capturedOut, true, "UTF-8").use { out ->
        PrintStream(capturedErr, true, "UTF-8").use { err ->
            NewModScaffold.runCli(arrayOf(*args), out, err)
        }
    }
    return CliResult(exit, capturedOut.toString("UTF-8"), capturedErr.toString("UTF-8"))
}

private fun scaffold(moduleDir: Path, vararg extra: String): CliResult =
    run("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", moduleDir.toString(), *extra)

/** 目录内全部文件的相对路径 → 文本内容（排序，用于幂等字节比对）。 */
private fun snapshot(moduleDir: Path): Map<String, String> {
    val snapshot = TreeMap<String, String>()
    val paths = Files.walk(moduleDir)
    try {
        paths.forEach { path ->
            if (Files.isRegularFile(path)) {
                snapshot[moduleDir.relativize(path).toString()] = Files.readString(path)
            }
        }
    } finally {
        paths.close()
    }
    return snapshot
}

/** 生成面：写盘内容、幂等、冲突拒绝、`--check` 绝不落盘、schema 能被 accessor 生成器吃下。 */
class NewModScaffoldTest {

    @TempDir
    lateinit var tempDir: Path

    private fun moduleDir(): Path = tempDir.resolve("mod-mymod")

    private fun generatedKotlin(moduleDir: Path, fileName: String): String =
        Files.readString(moduleDir.resolve("src/main/kotlin/eratw/mymod/$fileName"))

    @Test
    fun generatesEverySkeletonFile() {
        val module = moduleDir()
        val result = scaffold(module)

        assertEquals(0, result.exit, "stderr=${result.err}")
        listOf(
            "build.gradle.kts",
            "src/main/kotlin/eratw/mymod/MyDeclarations.kt",
            "src/main/kotlin/eratw/mymod/MyMod.kt",
            "schemas/My.schema.tsv",
            "src/test/kotlin/eratw/mymod/MyModTest.kt",
        ).forEach { relative ->
            assertTrue(Files.isRegularFile(module.resolve(relative)), "缺文件：$relative")
        }
        val buildScript = Files.readString(module.resolve("build.gradle.kts"))
        assertTrue(buildScript.contains("api(project(\":mod-api\"))"))
        assertTrue(buildScript.contains("kotlin.srcDir(\"src/generated/kotlin\")"))
        assertTrue(
            buildScript.contains("testImplementation(project(\":mod-testing\"))"),
            "生成的测试要能用 :mod-testing 的录制替身，脚本必须声明依赖",
        )
        val modTest = Files.readString(module.resolve("src/test/kotlin/eratw/mymod/MyModTest.kt"))
        assertTrue(modTest.contains("TestWorld.of(MyDeclarations)"), "生成的测试必须建真台架世界")
        assertTrue(modTest.contains("context.dispatchEvent("), "生成的测试必须断言事件真被派发")
        assertTrue(modTest.contains("context.dispatchHook("), "生成的测试必须断言钩子真被调用")
        assertTrue(modTest.contains("context.firePhase("), "生成的测试必须断言相位边界真被触发")
        assertTrue(modTest.contains("context.uiImages"), "生成的测试必须断言图片元素注册结果")
        assertTrue(modTest.contains("context.timingRecorder.scopeNames()"), "生成的测试必须断言耗时记账有 scope")
        assertTrue(result.out.contains("NEXT: 在 settings.gradle.kts 里自己加 include(\":mod-mymod\")"))
    }

    @Test
    fun declarationsKeepPackageIdentityAndDeclareAllFourShapes() {
        val module = moduleDir()
        scaffold(module)
        val declarations = generatedKotlin(module, "MyDeclarations.kt")

        assertTrue(declarations.contains("@ModCarrier"))
        assertTrue(declarations.contains("object MyDeclarations : ModCarrierDeclaration"))
        assertTrue(declarations.contains("const val PACKAGE_ID: String = \"eratw.mymod\""))
        assertTrue(declarations.contains("component(Keys.COMPONENT)"))
        assertTrue(declarations.contains("archetype(Keys.ARCHETYPE)"))
        assertTrue(declarations.contains("relation(Keys.KNOWS)"))
        assertTrue(declarations.contains("job(Keys.DAILY_TICK, everyDays = 1L)"))
        assertTrue(declarations.contains("intKey(\"eratw.mymod.level\")"))
        assertTrue(declarations.contains("strKey(\"eratw.mymod.nickname\")"))
        assertTrue(declarations.contains("const val EVENT_KEY: String = \"EVENTFIRST\""), "事件名必须声明成常量")
        assertTrue(declarations.contains("val HOOK_TARGET: FunctionKey = FunctionKey(\"SYSTEM_TITLE\")"), "钩子目标必须是函数键")
        assertTrue(declarations.contains("const val MENU_LABEL: String = \"eratw.mymod.menu\""), "交互项文案键必须带包前缀")
        assertTrue(declarations.contains("const val BANNER_IMAGE: String = \"images/banner.png\""), "图片资源名必须声明成常量")
        assertTrue(
            declarations.contains("const val THEME_AUDIO: String = \"audio/theme.ogg\""),
            "音频播放名必须声明成常量（骨架不随包二进制音频）",
        )
        assertTrue(
            declarations.contains("hook(Keys.HOOK_TARGET, HookPhase.PREFIX, HookOptions(priority = 10))"),
            "钩子槽必须在 manifest 里真声明（否则运行期 register 抛）",
        )
    }

    @Test
    fun lifecycleUsesContextForReal() {
        val module = moduleDir()
        scaffold(module)
        val mod = generatedKotlin(module, "MyMod.kt")

        assertTrue(mod.contains("override fun onLoad(context: ModContext)"))
        assertTrue(mod.contains("context.storage.get(MyDeclarations.Keys.TICK_COUNT)"))
        assertTrue(mod.contains("context.jobs.bind(MyDeclarations.Keys.DAILY_TICK)"))
        assertTrue(mod.contains("context.rng.chance("))
        assertTrue(mod.contains("context.entities.query("))
        assertTrue(mod.contains("context.entities.setInt("))
        assertTrue(mod.contains("context.storage.put(MyDeclarations.Keys.TICK_COUNT"))
        assertTrue(mod.contains("override fun onNewGame()"))
        assertTrue(mod.contains("override fun onTurnBoundary()"))
        assertTrue(mod.contains("context.events.register("), "onEnable 必须真注册事件处理器")
        assertTrue(mod.contains("MyDeclarations.Keys.EVENT_KEY"), "事件键必须来自声明的常量")
        assertTrue(mod.contains("EventBucket.NORMAL"), "事件必须选一个真实桶")
        assertTrue(mod.contains("context.hooks.register("), "onEnable 必须真挂钩子")
        assertTrue(mod.contains("MyDeclarations.Keys.HOOK_TARGET.name"), "钩子目标必须走声明里的函数键")
        assertTrue(mod.contains("context.phases.subscribe(BuiltinPhase.DAY_START.name)"), "相位必须订阅引擎真派发的内建相位")
        assertTrue(mod.contains("context.ui.registerImage("), "onEnable 必须真注册图片元素")
        assertTrue(mod.contains("MyDeclarations.Keys.BANNER_IMAGE"), "图片必须走声明的资源名")
        assertTrue(mod.contains("context.ui.registerInteraction("), "onEnable 必须真注册交互项")
        assertTrue(mod.contains("MyDeclarations.Keys.MENU_LABEL"), "交互项 labelKey 必须指向本包文案键")
        assertTrue(
            mod.contains("context.interactions.registerHook(InteractionPhase.PRE)"),
            "onEnable 必须真注册 Manifest 已声明相位的互动钩子（只写注释不算）",
        )
        assertTrue(mod.contains("context.audio.play("), "onEnable 必须真调 audio.play（只写注释不算）")
        assertTrue(
            mod.contains("MyDeclarations.Keys.THEME_AUDIO"),
            "音频播放名必须走声明的常量（骨架不随包二进制音频，常量引用还可让 check-mod 保持 0 发现）",
        )
        assertTrue(mod.contains("context.audio.stop()"), "onDisable 必须真调 audio.stop() 收尾")
        assertTrue(
            mod.contains("context.timing.measure(\"author.eratw.mymod.dailyTick\")"),
            "每日任务必须用 timing.measure 真记账",
        )
    }

    @Test
    fun schemaIsAcceptedByAccessorGenerator() {
        val module = moduleDir()
        scaffold(module)
        val schema = Files.readString(module.resolve("schemas/My.schema.tsv"))

        val emitted = AccessorEmit.emit(parseSchema(schema))
        assertTrue(emitted.contains("package eratw.mymod.generated"))
        assertTrue(emitted.contains("val level: FieldKey<Long> = intKey(\"eratw.mymod.level\")"))
        assertTrue(emitted.contains("val nickname: FieldKey<String> = strKey(\"eratw.mymod.nickname\")"))
    }

    @Test
    fun secondRunRefusesWithoutForceAndWritesNothing() {
        val module = moduleDir()
        scaffold(module)
        val marker = "sentinel-content"
        Files.writeString(module.resolve("build.gradle.kts"), marker)

        val second = scaffold(module)

        assertEquals(1, second.exit)
        assertTrue(second.err.contains("CONFLICT: build.gradle.kts"))
        assertTrue(second.err.contains("REFUSED: "))
        assertFalse(second.out.contains("WROTE:"), "拒绝时不得写盘：${second.out}")
        assertEquals(marker, Files.readString(module.resolve("build.gradle.kts")))
    }

    @Test
    fun forceOverwritesAndSecondGenerationIsByteIdentical() {
        val module = moduleDir()
        scaffold(module)
        val first = snapshot(module)

        val second = scaffold(module, "--force")

        assertEquals(0, second.exit, "stderr=${second.err}")
        assertEquals(first, snapshot(module))
    }

    @Test
    fun checkModePrintsPlanAndNeverTouchesDisk() {
        val module = moduleDir()
        val result = scaffold(module, "--check")

        assertEquals(0, result.exit, "stderr=${result.err}")
        assertTrue(result.out.contains("WOULD WRITE: build.gradle.kts"))
        assertEquals("", result.err)
        assertFalse(Files.exists(module), "--check 不得建目录")
    }

    @Test
    fun checkModeReportsConflictWithExitOne() {
        val module = moduleDir()
        val preexisting = module.resolve("schemas/My.schema.tsv")
        Files.createDirectories(preexisting.parent)
        Files.writeString(preexisting, "old-schema")

        val result = scaffold(module, "--check")

        assertEquals(1, result.exit)
        assertTrue(result.err.contains("CONFLICT: schemas/My.schema.tsv"))
        assertEquals("old-schema", Files.readString(preexisting))
        assertFalse(Files.exists(module.resolve("build.gradle.kts")))
    }
}

/** 参数校验与安全面：取证参数不写盘、拒绝路径穿越、帮助与未知子命令的退出码。 */
class NewModScaffoldValidationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun moduleDir(): Path = tempDir.resolve("mod-mymod")

    @Test
    fun rejectsPackageIdThatWouldEscapeOutputDir() {
        val module = moduleDir()
        val escape = tempDir.resolve("escape")

        val result = run("new-mod", "--package", "../escape", "--name", "My", "--out", module.toString())

        assertEquals(2, result.exit)
        assertTrue(result.err.startsWith("ERROR: 非法 packageId："), "stderr=${result.err}")
        assertFalse(Files.exists(module))
        assertFalse(Files.exists(escape))
    }

    @Test
    fun rejectsPackageSegmentThatCannotBeKotlinPackage() {
        val module = moduleDir()

        val result = run("new-mod", "--package", "eratw.my-mod", "--name", "My", "--out", module.toString())

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("同时用作 Kotlin 包名"), "stderr=${result.err}")
        assertFalse(Files.exists(module))
    }

    /**
     * MOD-008：模板把包身份派生成三语文案键，运行期键只许全小写 `[a-z0-9_.-]+`
     * （ModStringsRegistry.requireValidKey）——大写包身份生成的骨架 onLoad 即抛 IAE，
     * 必须在脚手架入口就地拒绝。
     */
    @Test
    fun rejectsUppercasePackageIdThatStringsKeysCannotCarry() {
        val module = moduleDir()

        val result = run("new-mod", "--package", "eratw.MyMod", "--name", "My", "--out", module.toString())

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("--package 必须全小写"), "stderr=${result.err}")
        assertFalse(Files.exists(module))
    }

    /**
     * MOD-008：骨架派生的最长文案键 = `packageId + .greeting`，必须落进运行期 64 字符上限
     * （ModStringsLimits.MAX_KEY_LENGTH）——超长包身份同样会让骨架「生成成功」而装载即崩。
     */
    @Test
    fun rejectsPackageIdLongerThanDerivedStringsKeyBudget() {
        val tooLong = "eratw." + "a".repeat(50)
        val module = moduleDir()

        val result = run("new-mod", "--package", tooLong, "--name", "My", "--out", module.toString())

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("--package 过长"), "stderr=${result.err}")
        assertTrue(result.err.contains("64 字符上限"), "stderr=${result.err}")
        assertFalse(Files.exists(module))
    }

    /**
     * 边界对齐闸：脚手架长度上限（64 − 后缀 9 = 55）与模板实际派生键逐字符对齐——
     * 模板后缀变长或上限改小都会让最长派生键越过 64 而在这里红。
     */
    @Test
    fun packageIdAtStringsKeyBudgetBoundaryKeepsDerivedKeysLegal() {
        val atLimit = "eratw." + "a".repeat(49)
        assertEquals(55, atLimit.length, "边界包身份应恰为 64 − 9 = 55 字符")

        val cli = run("new-mod", "--package", atLimit, "--name", "My", "--out", moduleDir().toString(), "--check")
        assertEquals(0, cli.exit, "stderr=${cli.err}")

        val files = NewModTemplates.files(
            ScaffoldRequest(
                packageId = atLimit,
                kotlinPackage = atLimit,
                className = "My",
                moduleName = "mod-my",
                outputDir = tempDir.resolve("mod-my"),
            ),
        ).filterIsInstance<ScaffoldFile>()
        val modKotlin = files.single { it.relativePath.endsWith("/MyMod.kt") }.content
        val declarations = files.single { it.relativePath.endsWith("/MyDeclarations.kt") }.content
        val keys = Regex("context\\.strings\\.(?:declare|of|has)\\(\\s*\"([^\"]+)\"")
            .findAll(modKotlin)
            .map { it.groupValues[1] }
            .plus(
                Regex("const val MENU_LABEL: String = \"([^\"]+)\"")
                    .findAll(declarations)
                    .map { it.groupValues[1] },
            ).toList()
        assertTrue(keys.size >= 3, "模板必须真派生三语文案键，实际=$keys")
        keys.forEach { key ->
            assertTrue(key.matches(Regex("[a-z0-9_.-]+")), "派生键含运行期不收的字符：$key")
            assertTrue(key.length <= ModStringsLimits.MAX_KEY_LENGTH, "派生键超 64 上限：$key")
        }
        assertEquals(
            ModStringsLimits.MAX_KEY_LENGTH,
            keys.maxOf { it.length },
            "脚手架上限与模板派生键预算必须逐字符对齐，实际最长派生键=${keys.maxOf { it.length }}",
        )
    }

    @Test
    fun rejectsNonPascalCaseClassName() {
        val module = moduleDir()

        val result = run("new-mod", "--package", "eratw.mymod", "--name", "my-mod", "--out", module.toString())

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("--name 必须是 PascalCase 标识符"), "stderr=${result.err}")
        assertFalse(Files.exists(module))
    }

    @Test
    fun rejectsUnknownFlagWithoutTouchingDisk() {
        val module = moduleDir()

        val result = run("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", module.toString(), "--bogus")

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("未知参数：--bogus"), "stderr=${result.err}")
        assertFalse(Files.exists(module))
    }

    @Test
    fun rejectsStrayPositionalArgument() {
        val module = moduleDir()

        val result = run(
            "new-mod", "--package", "eratw.mymod", "--name", "My", "--out", module.toString(), "x",
        )

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("未知参数：x"), "stderr=${result.err}")
    }

    @Test
    fun rejectsUnknownSubcommand() {
        val result = run("make-mod", "--package", "eratw.mymod")

        assertEquals(2, result.exit)
        assertTrue(result.err.contains("未知子命令：make-mod"), "stderr=${result.err}")
    }

    @Test
    fun helpPrintsUsageAndSucceedsWithoutWriting() {
        val result = run("new-mod", "--help")

        assertEquals(0, result.exit)
        assertTrue(result.out.contains("用法：new-mod --package <id>"))
        assertEquals("", result.err)
    }

    @Test
    fun warnsWhenModuleDirectoryIsNotModPrefixed() {
        val plain = tempDir.resolve("plain")

        val result = scaffold(plain)

        assertEquals(0, result.exit, "stderr=${result.err}")
        assertTrue(result.out.contains("WARN: 模块目录名不以 mod- 开头"), "stdout=${result.out}")
        assertTrue(result.out.contains("include(\":plain\")"))
        assertTrue(Files.isRegularFile(plain.resolve("build.gradle.kts")))
    }
}
