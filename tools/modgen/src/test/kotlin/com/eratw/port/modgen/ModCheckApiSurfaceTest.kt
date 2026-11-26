package com.eratw.port.modgen

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 夹具：在 `ModContext` 末尾塞一个未登记的新属性（`brandNew`）再补 `}`。 */
private const val NEW_CONTEXT_TAIL =
    "    val ui: UiApi\n\n    /** 夹具：未登记的新属性。 */\n    val brandNew: String\n}"

/** 真实 `ModApi.kt` 里的 VERSION 声明原文（数值随 bump 变化，夹具按当下原文改坏）。 */
private val VERSION_DECLARATION = Regex("""const val VERSION: Int = -?\d+""")

/** VERSION 声明末尾那个数值。 */
private val VERSION_DIGITS = Regex("""-?\d+$""")

/**
 * 作者面版本登记面（[ModCheckApiSurface]）的单测。
 *
 * 夹具口径：直接读真实 mod-api 源码再按需改坏 —— 本检查面本身就是对「真实作者面文本」的哨兵，
 * 用临时假源码会把「真实仓库现在 0 ERROR」这条关键证据从测试里拿掉。真实仓库哨兵见
 * [real repository mod-api is clean] 与 [real mod-family check chain has no Finding]。
 */
class ModCheckApiSurfaceTest {

    /** 读真实 mod-api 源码成可变快照（键 = 仓库相对路径）；行尾统一成 LF 便于按 "\n" 改夹具。 */
    private fun realSources(): MutableMap<String, String> =
        ModCheckApiSurface.loadSources(repoRoot())?.mapValues { (_, text) ->
            text.replace("\r\n", "\n")
        }?.toMutableMap()
            ?: error("mod-api sources not found under ${repoRoot()}")

    /** 快照里 `ModApi.kt` 的相对路径。 */
    private fun apiFile(sources: Map<String, String>): String =
        sources.keys.single { key -> key.endsWith("/ModApi.kt") }

    /** 快照里 `ModContext.kt` 的相对路径。 */
    private fun contextFile(sources: Map<String, String>): String =
        sources.keys.single { key -> key.endsWith("/ModContext.kt") }

    /** 改坏快照里的一处文本（断言确实命中，避免静默无效夹具）。 */
    private fun mutate(sources: MutableMap<String, String>, file: String, old: String, new: String) {
        val text = sources.getValue(file)
        assertTrue(text.contains(old), "夹具里找不到要改坏的文本：$old")
        sources[file] = text.replace(old, new)
    }

    /**
     * 夹具里 `const val VERSION: Int = N` 的原文。
     *
     * 版本号由真实 `ModApi.kt` 决定、每次 bump 都会变，夹具**不能写死数值**——否则本模块的测试会
     * 跟着作者的每一次 bump 一起红（汇流到 main 后才发现，与本检查项要防的正是同一类漂移）。
     */
    private fun versionDeclaration(sources: Map<String, String>, file: String): String {
        val match = VERSION_DECLARATION.find(sources.getValue(file))
        assertTrue(match != null, "夹具里找不到 const val VERSION 声明")
        return match!!.value
    }

    /** 把夹具里的 VERSION 声明改写成 `当前值 + delta`（ahead 用 +1、behind 用 -1）。 */
    private fun versionShifted(
        sources: Map<String, String>,
        file: String,
        delta: Int,
    ): Pair<String, String> {
        val declaration = versionDeclaration(sources, file)
        val digits = VERSION_DIGITS.find(declaration)
        assertTrue(digits != null, "夹具里 VERSION 声明没有数值：$declaration")
        val shifted = declaration.substringBeforeLast('=').trimEnd() + " = " + (digits!!.value.toInt() + delta)
        return declaration to shifted
    }

    /** 真实仓库直接分析必须 0 发现（谁漏 bump、谁点名不存在符号，这条先红）。 */
    @Test
    fun `real repository mod-api is clean`() {
        assertEquals(emptyList(), ModCheckApiSurface.analyze(realSources()))
    }

    /** 真实仓库经 `check(mod 目录)` 的发现也必须是空（含仓库根发现 + 源码读取全链路）。 */
    @Test
    fun `real repository check is clean`() {
        val findings = mutableListOf<Finding>()
        ModCheckApiSurface.check(repoRoot(), findings)
        assertEquals(emptyList(), findings)
    }

    /** VERSION 比版本表大 ⇒ 漂移 ERROR（指向 `const val VERSION` 行）。 */
    @Test
    fun `VERSION ahead of the version table is an error`() {
        val sources = realSources()
        val api = apiFile(sources)
        val (from, to) = versionShifted(sources, api, +1)
        mutate(sources, api, from, to)
        val expectedLine = sources.getValue(api).lines().indexOfFirst { line ->
            line.contains("const val VERSION")
        } + 1
        val error = errorsOf(ModCheckApiSurface.analyze(sources)).single()
        assertTrue(error.message.contains("版本号漂移"), error.message)
        assertTrue(error.file.endsWith("ModApi.kt"), error.file)
        assertEquals(expectedLine, error.line)
    }

    /** VERSION 比版本表小（忘改表或忘 bump 的反向）⇒ 同样 ERROR。 */
    @Test
    fun `VERSION behind the version table is an error`() {
        val sources = realSources()
        val api = apiFile(sources)
        val (from, to) = versionShifted(sources, api, -1)
        mutate(sources, api, from, to)
        assertHasError(ModCheckApiSurface.analyze(sources), "版本号漂移")
    }

    /** 版本表方括号链接点名的成员不存在 ⇒ ERROR。 */
    @Test
    fun `link naming a missing member is an error`() {
        val sources = realSources()
        mutate(sources, apiFile(sources), "[EventApi.trigger]", "[EventApi.triggerNope]")
        val findings = ModCheckApiSurface.analyze(sources)
        assertHasError(findings, "EventApi.triggerNope")
        assertTrue(errorsOf(findings).all { error -> error.file.endsWith("ModApi.kt") })
    }

    /** 版本表点名的类型不存在 ⇒ ERROR。 */
    @Test
    fun `link naming a missing type is an error`() {
        val sources = realSources()
        mutate(sources, apiFile(sources), "[EventApi.trigger]", "[EngineApi.trigger]")
        assertHasError(ModCheckApiSurface.analyze(sources), "不存在的类型")
    }

    /** 版本表点名了 `ModContext` 上不存在的属性 ⇒ ERROR。 */
    @Test
    fun `link naming a missing ModContext property is an error`() {
        val sources = realSources()
        mutate(sources, apiFile(sources), "[ModContext.strings]", "[ModContext.strins]")
        assertHasError(ModCheckApiSurface.analyze(sources), "ModContext.strins")
    }

    /** `ModContext` 新增属性但没进版本表 ⇒ ERROR（指向 ModContext.kt 的属性声明行）。 */
    @Test
    fun `unregistered ModContext property is an error`() {
        val sources = realSources()
        val context = contextFile(sources)
        mutate(sources, context, "    val ui: UiApi\n}", NEW_CONTEXT_TAIL)
        val error = errorsOf(ModCheckApiSurface.analyze(sources)).single()
        assertTrue(error.message.contains("brandNew"), error.message)
        assertTrue(error.file.endsWith("ModContext.kt"), error.file)
        val expectedLine = sources.getValue(context).lines().indexOfFirst { line ->
            line.contains("val brandNew")
        } + 1
        assertEquals(expectedLine, error.line)
    }

    /** 新增属性 + 版本表同步点名 ⇒ 不报（避免检查面把正常登记也当错）。 */
    @Test
    fun `registered new ModContext property is accepted`() {
        val sources = realSources()
        mutate(sources, contextFile(sources), "    val ui: UiApi\n}", NEW_CONTEXT_TAIL)
        mutate(
            sources,
            apiFile(sources),
            "按 v12 及更早编译的 mod 不调用即完全不受影响。",
            "按 v12 及更早编译的 mod 不调用即完全不受影响，另见 [ModContext.brandNew]。",
        )
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** 版本表行格式变了（反引号没了）⇒ 抽不到版本段 ⇒ 整面静默。 */
    @Test
    fun `version table format change is silent`() {
        val sources = realSources()
        val api = apiFile(sources)
        sources[api] = sources.getValue(api).replace(Regex("""`(\d+)`\s*="""), "\$1 = ")
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** 「## 当前取值」标题被改 ⇒ 找不到版本表 ⇒ 整面静默。 */
    @Test
    fun `missing current values heading is silent`() {
        val sources = realSources()
        mutate(sources, apiFile(sources), "## 当前取值", "## 现在取值")
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** `ModApi.kt` 缺失 ⇒ 整面静默。 */
    @Test
    fun `missing ModApi source is silent`() {
        val sources = realSources()
        sources.remove(apiFile(sources))
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** `ModContext.kt` 缺失 ⇒ 整面静默。 */
    @Test
    fun `missing ModContext source is silent`() {
        val sources = realSources()
        sources.remove(contextFile(sources))
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** VERSION 常量改名 ⇒ 抽不到 `const val VERSION` ⇒ 整面静默。 */
    @Test
    fun `renamed version const is silent`() {
        val sources = realSources()
        val api = apiFile(sources)
        val declaration = versionDeclaration(sources, api)
        mutate(sources, api, declaration, declaration.replace("const val VERSION:", "const val VERSION_OLD:"))
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** 反引号里的宿主内部符号不是 KDoc 链接，不算作者面 ⇒ 不报（避免假红）。 */
    @Test
    fun `host internal backticked symbol is not an author surface`() {
        val sources = realSources()
        mutate(
            sources,
            apiFile(sources),
            "壳层注入（`ModRuntimeHost.audioSink`）",
            "壳层注入（`ModRuntimeHost.audioSink` / `SomeHostApi.sink`）",
        )
        assertEquals(emptyList(), ModCheckApiSurface.analyze(sources))
    }

    /** 真实仓库哨兵：`check-mod` 链路上 mod-family 不因本检查面产生 ERROR。 */
    @Test
    fun `real mod-family check chain has no Finding`() {
        val root = repoRoot()
        val schema = root.resolve("tools/modgen-accessors/src/test/resources/family.schema.tsv")
        assertNoError(findingsOf(root.resolve("mod-family"), listOf(schema)))
    }
}
