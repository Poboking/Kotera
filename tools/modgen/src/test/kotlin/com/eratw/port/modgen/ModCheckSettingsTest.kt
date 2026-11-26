package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 测试里塞进脚手架的设置面源码（行号可预期：1 = package，2 = 空行，3 = 正文首行）。 */
private const val SETTINGS_SAMPLE = "eratw/mymod/SettingsSample.kt"

private const val SETTINGS_MARK = "设置项"

/** 往模块 `src/main/kotlin/` 里写一份源码文本（不编译，只给 check-mod 的文本抽取面看）。 */
private fun writeSettingsSource(module: Path, body: String, relative: String = SETTINGS_SAMPLE): Path {
    val file = module.resolve("src/main/kotlin").resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\n$body\n")
    return file
}

/** 只挑设置面的发现（消息统一带「设置项」），别把 schema / 资源 / 文案面的发现掺进断言。 */
private fun settingsFindings(module: Path): List<Finding> =
    findingsOf(module).filter { finding -> finding.message.contains(SETTINGS_MARK) }

/**
 * `check-mod` 的设置声明面（v5 `intSetting` / `strSetting` / `boolSetting` / `enumSetting`）：
 * 声明键与 `settings.int/str/bool/enumName/has` 读取键双向对拍。
 *
 * 断言口径分三层：
 * - 静默面：合法写法（字面量 / 本模块 `const val` 引用）必须 0 发现，一键漏报也不许假红；
 * - 抽不到就静默：变量实参、`setting(IntSettingDecl(...))`、`settings` 起别名都必须抑制交叉判据；
 * - 报红面：键形态 / 合同期必炸的写法报 ERROR，静态面可能抽不全的报 WARN。
 */
class ModCheckSettingsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun literalDeclarationsAndReadsAreSilent() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |intSetting("eratw.mymod.volume", default = 5L, range = 0L..10L)
            |boolSetting("eratw.mymod.enabled", default = true)
            |strSetting(key = "eratw.mymod.title", default = "x")
            |val volume = context.settings.int("eratw.mymod.volume")
            |val enabled = context.settings.bool("eratw.mymod.enabled")
            |val title = view.settings.str("eratw.mymod.title")
            |val known = context.settings.has("eratw.mymod.enabled")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "字面量声明 + 读取不该有发现：${findings.map { it.message }}")
    }

    @Test
    fun constKeyReferencesAreResolvedOnBothSides() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |object SampleKeys {
            |    const val VOLUME_KEY: String = "eratw.mymod.volume"
            |    const val MODE_KEY = "eratw.mymod.mode"
            |}
            |intSetting(SampleKeys.VOLUME_KEY, default = 5L, range = 0L..10L)
            |enumSetting(SampleKeys.MODE_KEY, default = "a", candidates = listOf("a", "b"))
            |val volume = context.settings.int(SampleKeys.VOLUME_KEY)
            |val mode = context.settings.enumName(SampleKeys.MODE_KEY)
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "本模块常量引用两侧都该解析：${findings.map { it.message }}")
    }

    @Test
    fun undeclaredReadWarnsButCheckPasses() {
        val module = module()
        writeSettingsSource(module, "val missing = context.settings.int(\"eratw.mymod.missing\")")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "用了未声明应恰一条 WARN：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity, "静态面可能抽不全，运行期才 fail-closed，只许 WARN")
        assertTrue(finding.file.endsWith("SettingsSample.kt"), "应点名读取所在文件：${finding.file}")
        assertEquals(3, finding.line, "应点名读取所在行：${finding.line}")
        assertTrue(finding.message.contains("设置项键未声明：eratw.mymod.missing"), "message=${finding.message}")
        assertEquals(0, exit, "只有 WARN 时 check-mod 退出码必须为 0")
        assertTrue(captured.toString("UTF-8").contains("设置项键未声明"), "stdout 应打印该 WARN")
    }

    @Test
    fun declaredButNeverReadWarns() {
        val module = module()
        writeSettingsSource(module, "intSetting(\"eratw.mymod.unused\", default = 3L)")

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "声明了但全模块无读取应恰一条 WARN：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity, "键可能预留给后续代码，只许 WARN")
        assertEquals(3, finding.line)
        assertTrue(finding.message.contains("设置项声明了但全模块无读取：eratw.mymod.unused"), "message=${finding.message}")
    }

    @Test
    fun duplicateDeclarationWarns() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |intSetting("eratw.mymod.dup", default = 1L)
            |boolSetting("eratw.mymod.dup", default = true)
            |val dup = context.settings.int("eratw.mymod.dup")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "重复声明应恰一条 WARN：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity, "重复只是冗余，不崩，只许 WARN")
        assertEquals(4, finding.line, "应点名第二条声明（正文第 2 行 = 文件第 4 行）")
        assertTrue(finding.message.contains("设置项键重复声明：eratw.mymod.dup"), "message=${finding.message}")
    }

    @Test
    fun illegalKeyIsError() {
        val module = module()
        writeSettingsSource(module, "intSetting(\"eratw mymod bad\", default = 1L)")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "非法键应恰一条：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.ERROR, finding.severity, "运行期 requireKey 当场抛，必须 ERROR")
        assertEquals(3, finding.line)
        assertTrue(finding.message.contains("设置项键形态非法"), "message=${finding.message}")
        assertEquals(1, exit, "有 ERROR 时 check-mod 退出码必须为 1")
    }

    @Test
    fun emptyEnumCandidatesIsError() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |enumSetting("eratw.mymod.mode", default = "a", candidates = listOf())
            |val mode = context.settings.enumName("eratw.mymod.mode")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "空候选应恰一条：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.ERROR, finding.severity, "声明期 EnumSettingDecl 当场拒绝，必须 ERROR")
        assertEquals(3, finding.line)
        assertTrue(finding.message.contains("设置项 eratw.mymod.mode 的枚举候选为空"), "message=${finding.message}")
    }

    @Test
    fun invertedIntRangeIsError() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |intSetting("eratw.mymod.range", default = 1L, range = 10L..1L)
            |val range = context.settings.int("eratw.mymod.range")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "反向 range 应恰一条：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.ERROR, finding.severity, "声明期 IntSettingDecl 当场拒绝，必须 ERROR")
        assertTrue(finding.message.contains("的 range 下界 10 大于上界 1"), "message=${finding.message}")
    }

    @Test
    fun unresolvableDeclarationSilencesCrossChecks() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |val key = "eratw.mymod.dynamic"
            |intSetting(key, default = 1L)
            |val missing = context.settings.int("eratw.mymod.definitely.missing")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "声明侧抽不到键就整面静默：${findings.map { it.message }}")
    }

    @Test
    fun unresolvableReadSuppressesUnusedWarn() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |intSetting("eratw.mymod.only", default = 1L)
            |val name = "eratw.mymod.only"
            |val value = context.settings.int(name)
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "读取侧抽不到键就不许报「声明了没用」：${findings.map { it.message }}")
    }

    @Test
    fun declarationObjectFormSilencesCrossChecks() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |setting(IntSettingDecl(key = "eratw.mymod.legacy", default = 1L))
            |val missing = context.settings.int("eratw.mymod.other")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "setting(IntSettingDecl(...)) 形式拿不到键集合，须静默：${findings.map { it.message }}")
    }

    @Test
    fun aliasedSettingsApiSuppressesUnusedWarn() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |intSetting("eratw.mymod.only", default = 1L)
            |val api = context.settings
            |val value = api.int("eratw.mymod.only")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "settings 起别名后读取面不完整，须静默：${findings.map { it.message }}")
    }

    @Test
    fun commentsAndForeignApiAreIgnored() {
        val module = module()
        writeSettingsSource(
            module,
            """
            |intSetting("eratw.mymod.keep", default = 1L)
            |val keep = context.settings.int("eratw.mymod.keep")
            |// intSetting("eratw.mymod.ghost", default = 2L)
            |/* val ghost = context.settings.int("eratw.mymod.ghost2") */
            |val other = otherContext.int("eratw.mymod.foreign")
            """.trimMargin(),
        )

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "注释与外来接收者都不算设置面：${findings.map { it.message }}")
    }

    @Test
    fun scaffoldSettingsFaceIsSilent() {
        val module = module()

        val findings = settingsFindings(module)
        assertTrue(findings.isEmpty(), "new-mod 生成物必须 0 设置面发现：${findings.map { it.message }}")
    }

    @Test
    fun scaffoldConstKeysAreActuallyResolved() {
        val module = module()
        replaceOnce(
            declarationsFile(module),
            "        boolSetting(\n            Keys.SETTING_UPGRADE_ENABLED,",
            "        boolSetting(Keys.SETTING_UPGRADE_ENABLED)\n" +
                "        boolSetting(\n            Keys.SETTING_UPGRADE_ENABLED,",
        )

        val findings = settingsFindings(module)
        assertEquals(1, findings.size, "常量引用必须被解析出来才可能撞重复：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity)
        assertTrue(finding.message.contains("设置项键重复声明：eratw.mymod.upgradeEnabled"), "message=${finding.message}")
    }

    @Test
    fun builtinCarriersHaveNoSettingsFindings() {
        val root = repoRoot()
        val carriers = listOf("mod-family", "mod-memory", "mod-template", "mod-livingworld")
        val offenders = carriers.flatMap { name ->
            settingsFindings(root.resolve(name)).map { finding -> "$name: ${finding.severity} ${finding.message}" }
        }

        assertTrue(offenders.isEmpty(), "内置载体 + 无设置模块必须是 0 error / 0 warning：$offenders")
    }

    @Test
    fun dslNamesStayInSyncWithModApiSource() {
        val modApi = repoRoot().resolve("mod-api/src/main/kotlin/com/eratw/port/modapi")
        val dsl = modApi.resolve("DeclDslSettings.kt").readText()
        listOf("intSetting", "strSetting", "boolSetting", "enumSetting").forEach { name ->
            assertTrue(
                dsl.contains("fun $name("),
                "DeclDslSettings.kt 里找不到 fun $name(；声明入口改名了要同步 ModCheckSettings",
            )
        }
        val api = modApi.resolve("SettingsApi.kt").readText()
        listOf("has", "int", "str", "bool", "enumName").forEach { name ->
            assertTrue(
                api.contains("fun $name(key: String):"),
                "SettingsApi.kt 里找不到 fun $name(key: String):；读取入口改名了要同步 ModCheckSettings",
            )
        }
    }
}
