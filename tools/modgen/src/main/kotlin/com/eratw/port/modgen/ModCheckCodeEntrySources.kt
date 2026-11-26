package com.eratw.port.modgen

/** 源码声明的入口类：FQN + 声明文件标签 + 行号。 */
internal class DeclaredEntrypoint(val fqn: String, val file: String, val line: Int)

/**
 * 从作者源码里抽 `class <Name> ... : EraMod` 的入口 FQN（[ModCheckCodeEntry] 的一致性核对输入）。
 *
 * 只做文本近似：`package` 行取第一处命中，`: EraMod` 行往回最多 [CLASS_LOOKBACK_LINES] 行找
 * 最近的 `class` 声明（跨行构造参数/泛型都容忍）；抽不到就静默（宁可漏报不许假红）。
 */
internal object ModCheckCodeEntrySources {

    /** 入口类声明里 `class` 关键字：允许声明与 `: EraMod` 跨行（构造参数/泛型可带空白）。 */
    private val CLASS_KEYWORD = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)""")

    /** `: EraMod` 父类型出现（行内任意位置；接口列表里也可以在它后面）。 */
    private val ERA_MOD_SUPERTYPE = Regex(""":\s*EraMod\b""")

    /** 文件级 `package` 声明（Kotlin 单文件单包，取第一处命中）。 */
    private val PACKAGE_DECLARATION = Regex("""^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")

    /** `: EraMod` 与 `class` 声明之间最多回看几行（构造参数列表跨多行的余量）。 */
    private const val CLASS_LOOKBACK_LINES = 8

    /** 源码里全部 `class <Name> ... : EraMod` 推出的 FQN（文件 `package` 行 + 类名）。 */
    fun declaredEntrypoints(sources: List<SourceFile>): List<DeclaredEntrypoint> =
        sources.flatMap { source ->
            val packageName = packageOf(source)
            if (packageName == null) {
                emptyList()
            } else {
                eraModClasses(source).map { DeclaredEntrypoint("$packageName.${it.name}", source.label, it.line) }
            }
        }

    /** 单文件的 `package` 声明（没有 package 行 ⇒ null，含默认包的 Kotlin 源）。 */
    private fun packageOf(source: SourceFile): String? =
        source.code.firstNotNullOfOrNull { line -> PACKAGE_DECLARATION.matchEntire(line.text)?.groupValues?.get(1) }

    /** 单文件里每个 `: EraMod` 行往回找一个 `class` 声明（跨行构造参数/泛型都容忍）。 */
    private fun eraModClasses(source: SourceFile): List<SourceClass> =
        source.code.indices
            .filter { index -> ERA_MOD_SUPERTYPE.containsMatchIn(source.code[index].text) }
            .mapNotNull { index -> classAt(source.code, index) }

    /** 从 `: EraMod` 所在行往回 [CLASS_LOOKBACK_LINES] 行找最近的 `class Name`。 */
    private fun classAt(code: List<CodeLine>, index: Int): SourceClass? {
        val low = maxOf(0, index - CLASS_LOOKBACK_LINES)
        for (line in index downTo low) {
            val match = CLASS_KEYWORD.find(code[line].text) ?: continue
            return SourceClass(match.groupValues[1], code[line].number)
        }
        return null
    }
}

/** `class` 声明：类名 + 行号（FQN 由所在文件 `package` 行拼）。 */
private class SourceClass(val name: String, val line: Int)
