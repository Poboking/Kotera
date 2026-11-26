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

/** 测试里塞进脚手架的三语文案源码（行号可预期：1 = package，2 = 空行，3 = 正文首行）。 */
private const val STRINGS_SAMPLE = "eratw/mymod/StringsSample.kt"

private const val STRINGS_WARNING = "文案键未声明"

/** 从 mod-api 源码里抽上限值，用来证明 modgen 侧常量与真源同值（文本哨兵，防漂移）。 */
private val KEY_LIMIT_IN_SOURCE = Regex("""MAX_KEY_LENGTH\s*:\s*Int\s*=\s*(\d+)""")

private val TEXT_LIMIT_IN_SOURCE = Regex("""MAX_TEXT_LENGTH\s*:\s*Int\s*=\s*(\d+)""")

/** 往模块 `src/main/kotlin/` 里写一份源码文本（不编译，只给 check-mod 的文本抽取面看）。 */
private fun writeStringsSource(module: Path, body: String, relative: String = STRINGS_SAMPLE): Path {
    val file = module.resolve("src/main/kotlin").resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, "package eratw.mymod\n\n$body\n")
    return file
}

/** 只挑三语文案面的发现，别把 schema / 资源 / 图片面的发现掺进断言。 */
private fun stringsFindings(module: Path): List<Finding> =
    findingsOf(module).filter { finding -> finding.message.contains("文案") }

/**
 * `check-mod` 的三语文案面（v10 `StringsApi`）：源码文本抽 `declare(...)` 的键集合与
 * `strings.of("...")` / `strings.has("...")` 的读取键，**用了没声明**报 WARN；
 * 抽不到字面量（变量 / 插值 / 动态声明）就静默，宁可漏报不许假红。
 *
 * 每个用例都断言两件事：发现条数与 severity（读取面 WARN 永不 ERROR），以及脚手架生成物自洽。
 *
 * 声明侧另有四条判据（键形态 / 重复声明 / 三语齐全 / 文本超长），严重度见 [ModCheckStrings]：
 * 四条全是 ERROR（键形态/重复运行期当场抛或前值必丢；三语缺项与文本超长走 requireText 的
 * require 在声明阶段抛，mod 载不进来 = 必然失效）；上限常量在 [ModStringsLimits]，由文本哨兵兜底。
 */
class ModCheckStringsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun module(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    @Test
    fun undeclaredReadWarnsButCheckPasses() {
        val module = module()
        writeStringsSource(module, "val text = context.strings.of(\"eratw.mymod.missing\")")
        val captured = ByteArrayOutputStream()

        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "用了没声明应恰一条 WARN：${findings.map { it.message }}")
        val finding = findings.single()
        assertEquals(Severity.WARN, finding.severity, "运行期拼键是合法写法，只许 WARN 不许 ERROR")
        assertTrue(finding.file.endsWith("StringsSample.kt"), "应点名读取所在文件：${finding.file}")
        assertEquals(3, finding.line, "应点名读取所在行：${finding.line}")
        assertTrue(finding.message.contains("$STRINGS_WARNING：eratw.mymod.missing"), "message=${finding.message}")
        assertEquals(0, exit, "只有 WARN 时 check-mod 退出码必须为 0")
        assertTrue(captured.toString("UTF-8").contains(STRINGS_WARNING), "stdout 应打印该 WARN")
    }

    @Test
    fun declaredAndReadIsSilent() {
        val module = module()
        writeStringsSource(
            module,
            """
            |context.strings.declare(
            |    "eratw.mymod.hello",
            |    zh = "你好",
            |    en = "Hello",
            |    ja = "こんにちは",
            |)
            |val text = context.strings.of("eratw.mymod.hello")
            """.trimMargin(),
        )

        assertTrue(stringsFindings(module).isEmpty(), "跨行声明 + 取用同一键不该报：${stringsFindings(module)}")
    }

    @Test
    fun declaredButNeverReadIsSilent() {
        val module = module()
        writeStringsSource(module, "context.strings.declare(\"eratw.mymod.unused\", \"a\", \"b\", \"c\")")

        assertTrue(
            stringsFindings(module).isEmpty(),
            "声明了没用不是运行期缺陷（键可能留给后续代码），报 WARN 只会训练作者忽略警告",
        )
    }

    @Test
    fun interpolatedReadIsSilent() {
        val module = module()
        writeStringsSource(module, "val key = \"eratw.mymod\"\nval text = context.strings.of(\"${'$'}{key}.hello\")")

        assertTrue(stringsFindings(module).isEmpty(), "键里有运行期插值就抽不到稳定值，必须静默")
    }

    @Test
    fun concatenatedLiteralKeyIsSkipped() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.\" + \"join\", zh = \"你好\", en = \"Hello\", ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)

        assertNoError(findings)
        assertTrue(
            findings.isEmpty(),
            "拼接键不是整段字面量，抽不到必须按 KDoc 静默，不许拿「a\" + \"b」片段冒充作者的键报形态非法：" +
                "${findings.map { it.message }}",
        )
    }

    @Test
    fun dynamicDeclareSilencesWholeCheck() {
        val module = module()
        writeStringsSource(
            module,
            """
            |val key = "eratw.mymod.x"
            |context.strings.declare(key, zh = "a", en = "b", ja = "c")
            |val text = context.strings.of("eratw.mymod.definitely.missing")
            """.trimMargin(),
        )

        assertTrue(
            stringsFindings(module).isEmpty(),
            "声明侧有一处抽不到字面量时，任何未声明结论都可能是假红 ⇒ 整体静默",
        )
    }

    @Test
    fun hasCountsAsUse() {
        val module = module()
        writeStringsSource(module, "val present = context.strings.has(\"eratw.mymod.missing\")")

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "has() 与 of() 同为读取面：${findings.map { it.message }}")
        assertEquals(Severity.WARN, findings.single().severity)
    }

    @Test
    fun declarationInAnotherFileCounts() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.shared\", \"a\", \"b\", \"c\")",
            "eratw/mymod/Decls.kt",
        )
        writeStringsSource(module, "val text = context.strings.of(\"eratw.mymod.shared\")")

        assertTrue(stringsFindings(module).isEmpty(), "声明键是本模块全集，跨文件也算已声明")
    }

    @Test
    fun commentedAndForeignReadsAreIgnored() {
        val module = module()
        writeStringsSource(
            module,
            """
            |// val ghost = context.strings.of("eratw.mymod.ghost")
            |/* val ghost2 = context.strings.has("eratw.mymod.ghost2") */
            |val other = other.of("eratw.mymod.foreign")
            """.trimMargin(),
        )

        assertTrue(stringsFindings(module).isEmpty(), "注释示例与别的 API 的 of 都不属于三语文案面")
    }

    @Test
    fun scaffoldDeclaresReadsAndDocumentsStrings() {
        val module = tempDir.resolve("mod-mymod")
        val exit = NewModScaffold.runCli(
            arrayOf("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", module.toString()),
        )

        assertEquals(0, exit)
        val mod = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()
        assertTrue(mod.contains("context.strings.declare("), "Mod.kt 缺三语文案声明示例")
        assertTrue(mod.contains("context.strings.of(\"eratw.mymod.greeting\")"), "Mod.kt 缺 of 取用示例")
        assertTrue(mod.contains("context.strings.has(") || mod.contains("strings.has("), "Mod.kt 应演示 has")

        val readme = module.resolve("eratw.mymod.assets/README.txt").readText()
        assertTrue(readme.contains("三语文案（v10）"), "README 缺三语文案面说明")
        assertTrue(readme.contains("context.strings.of(\"eratw.mymod.greeting\")"), "README 缺 of 示例")

        assertTrue(stringsFindings(module).isEmpty(), "脚手架自己声明并取用的键必须自洽：${stringsFindings(module)}")
    }

    @Test
    fun dslNamesStayInSyncWithStringsApiSource() {
        val source = repoRoot().resolve("mod-api/src/main/kotlin/com/eratw/port/modapi/StringsApi.kt")
        val text = source.readText()

        assertTrue(text.contains("fun declare(key: String"), "mod-api 侧 declare 签名变了，文本抽取会漏：$source")
        assertTrue(text.contains("fun of(key: String): String"), "mod-api 侧 of 改名了：$source")
        assertTrue(text.contains("fun has(key: String): Boolean"), "mod-api 侧 has 改名了：$source")
    }

    @Test
    fun completeThreeLanguagesAreSilent() {
        val module = module()
        writeStringsSource(
            module,
            """
            |context.strings.declare("eratw.mymod.complete", zh = "你好", en = "Hello", ja = "こんにちは")
            |val text = context.strings.of("eratw.mymod.complete")
            """.trimMargin(),
        )

        assertTrue(stringsFindings(module).isEmpty(), "三语齐全且读取命中已声明键必须 0 发现")
    }

    @Test
    fun missingEnglishArgumentIsError() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.partial\", zh = \"你好\", ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "缺 en 实参必须且只能报 1 条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "运行期 requireText 抛、mod 载不进来，必然失效")
        assertEquals(1, errorsOf(findings).size, "缺三语必须计入 ERROR：$findings")
        assertTrue(findings.single().message.contains("缺 en 文案"), findings.single().message)
    }

    @Test
    fun missingJapaneseArgumentIsError() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.partial\", zh = \"你好\", en = \"Hello\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "缺 ja 实参必须且只能报 1 条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "运行期 requireText 抛、mod 载不进来，必然失效")
        assertTrue(findings.single().message.contains("缺 ja 文案"), findings.single().message)
    }

    @Test
    fun missingBothLanguagesReportOneFinding() {
        val module = module()
        writeStringsSource(module, "context.strings.declare(\"eratw.mymod.partial\", zh = \"你好\")")

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "同一处缺两语合并成 1 条（同一作者动作，别双计）：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "运行期 requireText 抛、mod 载不进来，必然失效")
        assertTrue(findings.single().message.contains("缺 en/ja 文案"), findings.single().message)
    }

    @Test
    fun blankLanguageLiteralCountsAsMissing() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.blank\", zh = \"你好\", en = \"\", ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "空串等同缺文案（运行期 requireText 的 isNotBlank 口径）：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "空串在声明阶段 require 抛、mod 载不进来，故 ERROR")
        assertTrue(findings.single().message.contains("缺 en 文案"), findings.single().message)
    }

    @Test
    fun duplicateDeclarationIsError() {
        val module = module()
        writeStringsSource(
            module,
            """
            |context.strings.declare("eratw.mymod.dup", zh = "你好", en = "Hello", ja = "こんにちは")
            |context.strings.declare("eratw.mymod.dup", zh = "你好二", en = "Hello2", ja = "こんにちは2")
            """.trimMargin(),
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "同键重复声明必须报 1 条（第二处）：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "后值静默覆盖前值，前一条文案丢失，必须 ERROR")
        assertTrue(findings.single().message.contains("文案键重复声明：eratw.mymod.dup"), findings.single().message)
        assertTrue(errorsOf(findings).size == 1, "重复声明必须计入 ERROR：$findings")
    }

    @Test
    fun blankKeyIsError() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"\", zh = \"你好\", en = \"Hello\", ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "空键必须报 1 条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "运行期 requireValidKey 当场抛，故 ERROR")
        assertTrue(findings.single().message.contains("文案键形态非法"), findings.single().message)
    }

    @Test
    fun whitespaceKeyIsError() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\" eratw.mymod.x\", zh = \"你好\", en = \"Hello\", ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "带空白的键必须报 1 条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity)
        assertTrue(findings.single().message.contains("文案键形态非法"), findings.single().message)
    }

    @Test
    fun overlongKeyIsError() {
        val module = module()
        val key = "a".repeat(65)
        writeStringsSource(
            module,
            "context.strings.declare(\"$key\", zh = \"你好\", en = \"Hello\", ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "超 64 字符的键必须报 1 条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity)
        assertTrue(findings.single().message.contains("文案键形态非法"), findings.single().message)
    }

    @Test
    fun overlongTextIsError() {
        val module = module()
        val long = "x".repeat(513)
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.long\", zh = \"你好\", en = \"$long\", " +
                "ja = \"こんにちは\")",
        )

        val findings = stringsFindings(module)
        assertEquals(1, findings.size, "单条文本超 512 必须报 1 条：$findings")
        assertEquals(Severity.ERROR, findings.single().severity, "运行期 requireText 的 require 拒收、载不进来，故 ERROR")
        assertTrue(findings.single().message.contains("超过上限 512"), findings.single().message)
        assertTrue(findings.single().message.contains("en 文案 513 字符"), findings.single().message)
        val captured = ByteArrayOutputStream()
        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }
        assertEquals(1, exit, "超长文本是 ERROR，check-mod 必须 exit 1")
    }

    @Test
    fun incompleteDeclarationExitsOne() {
        val module = module()
        writeStringsSource(
            module,
            "context.strings.declare(\"eratw.mymod.partial\", zh = \"你好\", ja = \"こんにちは\")",
        )

        val captured = ByteArrayOutputStream()
        val exit = PrintStream(captured, true, "UTF-8").use { out ->
            ModCheck.runCli(arrayOf(module.toString()), out)
        }

        assertEquals(1, exit, "缺三语是 ERROR（运行期 requireText 抛、mod 载不进来），必须 exit 1")
        assertTrue(captured.toString("UTF-8").contains("缺 en 文案"), captured.toString("UTF-8"))
    }

    @Test
    fun concatenatedDeclareKeySilencesWholeFace() {
        val module = module()
        writeStringsSource(
            module,
            """
            |val prefix = "eratw.mymod"
            |context.strings.declare(prefix + ".hello", zh = "你好", en = "Hello", ja = "こんにちは")
            |val text = context.strings.of("eratw.mymod.definitely.missing")
            """.trimMargin(),
        )

        assertTrue(stringsFindings(module).isEmpty(), "拼出的声明键可声明任意键，任何未声明结论都可能是假红")
    }

    @Test
    fun constKeyDeclarationAndReadAreResolved() {
        val module = module()
        writeStringsSource(
            module,
            """
            |private const val GREETING: String = "eratw.mymod.constgreeting"
            |
            |val text = context.strings.of(GREETING)
            |
            |val declared = context.strings.declare(GREETING, zh = "你好", en = "Hello", ja = "こんにちは")
            """.trimMargin(),
        )

        assertTrue(stringsFindings(module).isEmpty(), "常量键声明 + 读取必须解析成同一键（内置载体就这么写）")
    }

    @Test
    fun builtinCarriersHaveNoStringsFindings() {
        val root = repoRoot()
        val carriers = listOf("mod-family", "mod-memory", "mod-template", "mod-livingworld")
        val offenders = carriers.flatMap { name ->
            stringsFindings(root.resolve(name)).map { finding -> "$name: ${finding.message}" }
        }

        assertTrue(offenders.isEmpty(), "内置载体必须是 0 error / 0 warning：$offenders")
    }

    @Test
    fun stringsLimitsStayInSyncWithStringsApiSource() {
        val source = repoRoot().resolve("mod-api/src/main/kotlin/com/eratw/port/modapi/StringsApi.kt")
        val text = source.readText()
        val maxKey = KEY_LIMIT_IN_SOURCE.find(text)?.groupValues?.get(1)?.toInt()
        val maxText = TEXT_LIMIT_IN_SOURCE.find(text)?.groupValues?.get(1)?.toInt()

        assertEquals(ModStringsLimits.MAX_KEY_LENGTH, maxKey, "modgen 侧 MAX_KEY_LENGTH 与 mod-api 真源漂移：$source")
        assertEquals(ModStringsLimits.MAX_TEXT_LENGTH, maxText, "modgen 侧 MAX_TEXT_LENGTH 与 mod-api 真源漂移：$source")
    }
}
