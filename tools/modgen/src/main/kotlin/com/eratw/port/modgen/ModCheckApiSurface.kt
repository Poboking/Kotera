package com.eratw.port.modgen

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** mod-api 作者面源码目录（相对仓库根）。 */
private const val API_SOURCE_DIR = "mod-api/src/main/kotlin/com/eratw/port/modapi"

/** 版本号与版本表所在的作者面入口文件。 */
private const val API_FILE = "ModApi.kt"

/** 版本表所在小节标题（`ModApi` 的 KDoc 一级标题）。 */
private const val CURRENT_VALUES_HEADING = "## 当前取值"

/** 作者上下文类型名：版本表里既可能写全名，也可能写别名。 */
private const val CONTEXT_TYPE = "ModContext"

/** 版本表里指向当前上下文的别名。 */
private const val CONTEXT_ALIAS = "context"

/** 上下文身份属性：不属于「作者面新增」的登记义务（不在版本表点名也不报）。 */
private const val IDENTITY_PROPERTY = "packageId"

/** 仓库根的判定文件。 */
private const val ROOT_MARKER = "settings.gradle.kts"

/** Kotlin 源码后缀。 */
private const val KOTLIN_SUFFIX = ".kt"

/** KDoc 行首装饰符。 */
private const val KDOC_STAR = "*"

/** KDoc 生成的源码行分隔符。 */
private const val LINE_SEPARATOR = "\n"

private const val FIRST_GROUP = 1

/** 版本表里符号链的段数：只校验 `Owner.member` 两段形态，全限定名跳过。 */
private const val SYMBOL_PART_COUNT = 2

private const val NEWLINE = '\n'

private const val MIN_LINE = 1

private const val LINE_OFFSET = 1

/** `const val VERSION = N` 的取值与位置。 */
private val VERSION_CONST = Regex("""\bconst\s+val\s+VERSION\s*(?::\s*Int\s*)?=\s*(\d+)""")

/** 版本表里一段的起始行：`` `N` = ... ``。 */
private val VERSION_ENTRY = Regex("""^`(\d+)`\s*=""")

/** KDoc 链接 `[符号]`：版本表点名作者面符号的机器可读形态。 */
private val LINK = Regex("""\[([^\]\n]+)\]""")

/** 裸 `Owner.member`（大写开头 = 类型名）：只对 mod-api 里真实存在的类型做宽松校验。 */
private val OWNER_MEMBER = Regex("""\b([A-Z]\w*)\.([a-z]\w*)\b""")

/** 裸 `context.member`：上下文别名一定指向 `ModContext`，严格校验。 */
private val CONTEXT_MEMBER = Regex("""\bcontext\.([a-z]\w*)\b""")

/** v1 段「全部 [ModContext] 属性（a/b/c）」这种斜杠清单。 */
private val CONTEXT_PROPERTY_LIST = Regex("""\[?ModContext]?\s*属性[^（(]{0,60}[（(]([^）)]*)[）)]""")

/** 属性清单里的标识符。 */
private val IDENTIFIER = Regex("""[A-Za-z]\w*""")

/** 类型声明头：`data class X` / `interface Y` / `object Z`（带常见修饰符前缀）。 */
private val TYPE_DECL = Regex(
    """(?:data\s+|value\s+|enum\s+|sealed\s+|abstract\s+|open\s+|expect\s+|actual\s+|annotation\s+|""" +
        """fun\s+)*(?:class|interface|object)\s+(\w+)""",
)

/** 成员属性声明：`val x` / `var x`（构造参数区与类体都能命中）。 */
private val VAL_DECL = Regex("""\b(?:val|var)\s+(\w+)""")

/** 成员函数声明：`fun name(`（含泛型前缀）。 */
private val FUN_DECL = Regex("""\bfun\s+(?:<[^>\n]*>\s*)?(\w+)\s*(?:<[^>\n]*>)?\s*\(""")

/** 块注释与行注释；替换成等长空白以保留字符偏移。 */
private val COMMENT = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")

/**
 * `check-mod` 的作者面版本登记面（v13 起）：把 `ModApi.VERSION` 的递增纪律从口头约定变成可报的错。
 *
 * 作者面规则写在 `ModApi` 对象 KDoc 里：任何**作者可见 API 面**的新增或语义变更都必须让
 * `const val VERSION` +1，并在「## 当前取值」版本表里登记。历史上 `settings` / `services` 两个
 * 属性就是悄悄加上却没 bump 的（后补成 v2 段），本检查把那类漂移变成 ERROR：
 *
 * - `const val VERSION` 与版本表里的最大版本号不一致 ⇒ ERROR（指向 `VERSION` 声明行）；
 * - 版本段里方括号链接 `[Owner.member]` 点名的符号在 mod-api 源码里不存在 ⇒ ERROR；
 * - [ModContext] 的每个 `val` 属性（身份属性 [IDENTITY_PROPERTY] 除外）都必须在版本表被点名
 *   ⇒ ERROR，指向 `ModContext.kt` 的属性声明行。
 *
 * 只读源码文本，不做编译期反射；KDoc 链接是版本表点名符号的唯一机器可读形态 —— 反引号里的
 * 宿主内部符号（如 `ModRuntimeHost.language`）不属于作者面，不参与校验。抽不到必需输入
 * （格式变更、文件缺失、读失败）就整面返回空列表：**宁可漏报，不许假红**。
 */
internal object ModCheckApiSurface {

    /**
     * 分析一份「仓库相对路径 → 文件原文」快照，返回发现（按 文件/行 排序）。
     *
     * 抽不到 `ModApi.VERSION` / 版本表 / `ModContext` 就返回空列表（静默）。
     */
    fun analyze(sources: Map<String, String>): List<Finding> = ApiSurfaceRun(sources).findings()

    /**
     * 从 [start] 指向的 mod 模块向上找仓库根，读 mod-api 源码后检查，发现追加进 [findings]。
     *
     * 仓库根 / mod-api 源码缺失或读失败就静默返回（检查面只对「能读到真源」的仓库生效）。
     */
    fun check(start: Path, findings: MutableList<Finding>) {
        val repoRoot = generateSequence(start.toAbsolutePath().normalize()) { path -> path.parent }
            .firstOrNull { path -> Files.isRegularFile(path.resolve(ROOT_MARKER)) } ?: return
        val sources = loadSources(repoRoot) ?: return
        findings += analyze(sources)
    }

    /**
     * 读 [repoRoot] 下 mod-api 同包源码，键 = 仓库相对路径（正斜杠）。
     *
     * 返回 null 表示整体不可用（目录缺失 / [API_FILE] 缺失 / 任一文件读失败）——调用方据此静默。
     */
    fun loadSources(repoRoot: Path): Map<String, String>? {
        val dir = repoRoot.resolve(API_SOURCE_DIR)
        if (!Files.isDirectory(dir)) return null
        val files = kotlinFiles(dir)
        val texts = files.associate { path ->
            val label = repoRoot.relativize(path).toString().replace('\\', '/')
            label to runCatching { Files.readString(path) }.getOrNull()
        }
        val usable = texts.size == files.size &&
            texts.values.all { text -> text != null } &&
            texts.keys.any { key -> key.endsWith("/$API_FILE") }
        return if (usable) texts.mapValues { (_, text) -> text.orEmpty() } else null
    }
}

/** 一次检查的完整输入；任一必需项抽不到就不构造（整面静默）。 */
private class Inputs(
    val apiKey: String,
    val apiText: String,
    val version: VersionInfo,
    val segments: List<VersionSegment>,
    val types: Map<String, TypeShape>,
    val sources: Map<String, String>,
)

/** `const val VERSION` 的取值与在 `ModApi.kt` 原文里的字符偏移。 */
private class VersionInfo(val value: Int, val offset: Int)

/** 版本表里的一段：版本号 + 该段占用的 KDoc 行。 */
private class VersionSegment(val version: Int, val lines: MutableList<LinePiece> = mutableListOf())

/** 一行 KDoc：原文 + 去掉行首星号后的干净文本 + 该行在源文件里的起始偏移。 */
private class LinePiece(val raw: String, val cleaned: String, val offset: Int)

/** 版本表里点名的一个符号：`Owner.member`。 */
private class SymbolRef(
    val owner: String,
    val member: String,
    val lineOffset: Int,
    val ownerMustExist: Boolean,
)

/** 类型声明及其成员（属性名 / 函数名 → 声明处相对类型声明头的偏移）。 */
private class TypeShape(
    val name: String,
    val file: String,
    val vals: Map<String, Int>,
    val funs: Map<String, Int>,
) {
    val members: Set<String> get() = vals.keys + funs.keys
}

/** 类型声明里的成员区间（左开右闭，都是原文偏移）。 */
private class Region(val start: Int, val end: Int)

/** 一次检查：解析输入 → 版本一致性 → 符号存在性 → 属性登记完整性。 */
private class ApiSurfaceRun(private val sources: Map<String, String>) {

    /** 全部发现，按 文件/行 排序；输入不完整就空列表。 */
    fun findings(): List<Finding> {
        val inputs = parse() ?: return emptyList()
        val findings = mutableListOf<Finding>()
        checkVersion(inputs, findings)
        val symbols = inputs.segments
            .flatMap { segment -> segment.lines }
            .flatMap { line -> symbolsInLine(line, inputs.types) }
            .distinctBy { symbol -> Triple(symbol.owner, symbol.member, symbol.lineOffset) }
        checkSymbols(inputs, symbols, findings)
        checkProperties(inputs, symbols, findings)
        return findings.sortedWith(compareBy({ it.file }, { it.line ?: 0 }))
    }

    /** 抽取必需输入：`VERSION` 常量、版本表分段、mod-api 类型成员表、`ModContext` 本体。 */
    private fun parse(): Inputs? {
        val apiKey = sources.keys.filter { key -> key.endsWith("/$API_FILE") }.minOrNull()
        val apiText = apiKey?.let(sources::get)
        val version = apiText?.let { text ->
            VERSION_CONST.find(text)?.let { match ->
                VersionInfo(match.groupValues[FIRST_GROUP].toInt(), match.range.first)
            }
        }
        val segments = apiText?.let { text ->
            val heading = text.indexOf(CURRENT_VALUES_HEADING)
            if (heading < 0) null else versionSegments(text, heading)
        }
        val types = parseTypes()
        val versioned = apiKey != null && apiText != null && version != null && !segments.isNullOrEmpty()
        return if (versioned && CONTEXT_TYPE in types) {
            Inputs(apiKey.orEmpty(), apiText.orEmpty(), version, segments.orEmpty(), types, sources)
        } else {
            null
        }
    }

    /** 从「## 当前取值」标题起、到 KDoc 闭合标记止，切出逐行文本与版本段。 */
    private fun versionSegments(text: String, heading: Int): List<VersionSegment> {
        val closing = text.indexOf("*/", heading)
        val end = if (closing > heading) closing else text.length
        val segments = mutableListOf<VersionSegment>()
        pieces(text, heading, end).forEach { piece ->
            val match = VERSION_ENTRY.find(piece.cleaned)
            if (match != null) {
                segments += VersionSegment(match.groupValues[FIRST_GROUP].toInt())
            }
            segments.lastOrNull()?.lines?.add(piece)
        }
        return segments
    }

    /** 解析全部 mod-api 源码里的类型声明与成员；同名类型后出现的覆盖先出现的。 */
    private fun parseTypes(): Map<String, TypeShape> {
        val shapes = mutableMapOf<String, TypeShape>()
        sources.forEach { (file, raw) ->
            val text = blankComments(raw)
            val declared = TYPE_DECL.findAll(text).toList()
            declared.forEachIndexed { index, match ->
                val limit = declared.getOrNull(index + 1)?.range?.first ?: text.length
                val shape = typeShapeAt(file, text, match, limit)
                shapes[shape.name] = shape
            }
        }
        return shapes
    }

    /** 单个类型声明：主构造参数区 + 类体里抽 `val`/`var` 与 `fun` 名字。 */
    private fun typeShapeAt(file: String, text: String, match: MatchResult, limit: Int): TypeShape {
        val regions = headerRegions(text, match.range.last + LINE_OFFSET, limit)
        val vals = mutableMapOf<String, Int>()
        val funs = mutableMapOf<String, Int>()
        regions.forEach { region ->
            val body = text.substring(region.start, region.end.coerceAtMost(text.length))
            VAL_DECL.findAll(body).forEach { decl ->
                vals.putIfAbsent(decl.groupValues[FIRST_GROUP], region.start + decl.range.first)
            }
            FUN_DECL.findAll(body).forEach { decl ->
                funs.putIfAbsent(decl.groupValues[FIRST_GROUP], region.start + decl.range.first)
            }
        }
        return TypeShape(match.groupValues[FIRST_GROUP], file, vals, funs)
    }

    /** 一行 KDoc 里的符号；[types] 用来决定裸 `Owner.member` 是否属于可校验面。 */
    private fun symbolsInLine(line: LinePiece, types: Map<String, TypeShape>): List<SymbolRef> {
        val linked = LINK.findAll(line.raw)
            .mapNotNull { match -> linkSymbol(match.groupValues[FIRST_GROUP], line.offset) }
            .toList()
        val bare = OWNER_MEMBER.findAll(line.raw)
            .mapNotNull { match ->
                val owner = match.groupValues[1]
                if (owner in types) {
                    SymbolRef(owner, match.groupValues[2], line.offset, ownerMustExist = false)
                } else {
                    null
                }
            }
            .toList()
        val alias = CONTEXT_MEMBER.findAll(line.raw)
            .map { match -> SymbolRef(CONTEXT_TYPE, match.groupValues[1], line.offset, true) }
            .toList()
        return linked + bare + alias
    }

    /** KDoc 链接内容 → 符号；只认 `Owner.member` 两段，全限定名 / 单段类型名跳过。 */
    private fun linkSymbol(content: String, offset: Int): SymbolRef? {
        val parts = content.trim().split('.').map { part -> part.trim() }
        if (parts.size != SYMBOL_PART_COUNT) return null
        val owner = if (parts[0] == CONTEXT_ALIAS) CONTEXT_TYPE else parts[0]
        return SymbolRef(owner, parts[1], offset, ownerMustExist = true)
    }

    /** VERSION 与版本表最大版本号不一致 ⇒ ERROR（指向 `const val VERSION` 行）。 */
    private fun checkVersion(inputs: Inputs, findings: MutableList<Finding>) {
        val tableMax = inputs.segments.maxOf { segment -> segment.version }
        if (tableMax == inputs.version.value) return
        findings += Finding(
            severity = Severity.ERROR,
            file = inputs.apiKey,
            line = lineOf(inputs.apiText, inputs.version.offset),
            message = "作者面版本号漂移：const val VERSION = ${inputs.version.value}，" +
                "但「当前取值」版本表最大是 $tableMax —— 任何作者可见 API 面的新增/语义变更都必须 +1，" +
                "并登记进版本表（VERSION 与版本表最大版本号必须相等）",
        )
    }

    /** 版本表点名的符号必须真实存在于 mod-api：owner 缺失 / member 缺失都报 ERROR。 */
    private fun checkSymbols(inputs: Inputs, symbols: List<SymbolRef>, findings: MutableList<Finding>) {
        symbols.forEach { symbol ->
            val shape = inputs.types[symbol.owner]
            when {
                shape == null && symbol.ownerMustExist -> findings += unknownOwner(inputs, symbol)
                shape == null -> Unit
                symbol.member !in shape.members -> findings += unknownMember(inputs, symbol)
                else -> Unit
            }
        }
    }

    /** `ModContext` 的每个属性（身份属性除外）都必须在版本表被点名 ⇒ 否则 ERROR。 */
    private fun checkProperties(inputs: Inputs, symbols: List<SymbolRef>, findings: MutableList<Finding>) {
        val named = symbols
            .filter { symbol -> symbol.owner == CONTEXT_TYPE }
            .map { symbol -> symbol.member }
            .toSet()
        val listed = inputs.segments
            .map { segment -> segment.lines.joinToString(LINE_SEPARATOR) { line -> line.cleaned } }
            .mapNotNull { text -> CONTEXT_PROPERTY_LIST.find(text)?.groupValues?.get(FIRST_GROUP) }
            .flatMap { listedText -> IDENTIFIER.findAll(listedText).map { match -> match.value } }
            .toSet()
        val shape = inputs.types[CONTEXT_TYPE] ?: return
        val text = inputs.sources[shape.file] ?: return
        shape.vals.forEach { (name, offset) ->
            if (name != IDENTITY_PROPERTY && name !in named && name !in listed) {
                findings += unregisteredProperty(shape.file, lineOf(text, offset), name)
            }
        }
    }
}

/** 版本表点名了 mod-api 里不存在的类型 ⇒ ERROR。 */
private fun unknownOwner(inputs: Inputs, symbol: SymbolRef): Finding = Finding(
    severity = Severity.ERROR,
    file = inputs.apiKey,
    line = lineOf(inputs.apiText, symbol.lineOffset),
    message = "版本表点名了 mod-api 里不存在的类型：${symbol.owner}.${symbol.member}" +
        " —— mod-api 源码中没有声明 ${symbol.owner}；版本表只能登记真实存在的作者面符号",
)

/** 版本表点名的类型存在、但成员不存在 ⇒ ERROR。 */
private fun unknownMember(inputs: Inputs, symbol: SymbolRef): Finding = Finding(
    severity = Severity.ERROR,
    file = inputs.apiKey,
    line = lineOf(inputs.apiText, symbol.lineOffset),
    message = "版本表点名的作者面符号 ${symbol.owner}.${symbol.member} 在 mod-api 源码里不存在" +
        " —— 登记了不存在的符号，说明版本表与源码已经漂移（改名后没同步登记）",
)

/** `ModContext` 属性没进版本表 ⇒ ERROR。 */
private fun unregisteredProperty(file: String, line: Int, name: String): Finding = Finding(
    severity = Severity.ERROR,
    file = file,
    line = line,
    message = "ModContext 属性 $name 未在 ModApi.kt「当前取值」版本表登记" +
        " —— 作者面新增属性必须 +1 并在版本表点名（历史上 settings/services 就是这样漏 bump 的）",
)

/** 把版本表覆盖到的每一行切出来：原文 / 去星号文本 / 行起始偏移。 */
private fun pieces(text: String, start: Int, end: Int): List<LinePiece> {
    val lines = mutableListOf<LinePiece>()
    var offset = start
    while (offset < end) {
        val newline = text.indexOf(NEWLINE, offset)
        val lineEnd = if (newline in offset until end) newline else end
        val raw = text.substring(offset, lineEnd)
        lines += LinePiece(raw, cleanKdocLine(raw), offset)
        offset = lineEnd + LINE_OFFSET
    }
    return lines
}

/** KDoc 行去掉行首星号与首尾空白（` * `13` = ...` → `` `13` = ... ``）。 */
private fun cleanKdocLine(raw: String): String = raw.trimStart().removePrefix(KDOC_STAR).trim()

/** 类型声明头之后的可解析区间：主构造参数区（若有）+ 类体（若有）。 */
private fun headerRegions(text: String, from: Int, limit: Int): List<Region> {
    var index = from
    while (index < limit && text[index].isWhitespace()) index += 1
    val constructor = if (index < limit && text[index] == '(') {
        matchingEnd(text, index, '(', ')')?.let { end -> Region(index + LINE_OFFSET, end) }
    } else {
        null
    }
    val bodyFrom = constructor?.let { region -> region.end + LINE_OFFSET } ?: from
    var brace = bodyFrom
    while (brace < limit && text[brace] != '{') brace += 1
    val body = if (brace < limit) {
        matchingEnd(text, brace, '{', '}')?.let { end -> Region(brace + LINE_OFFSET, end) }
    } else {
        null
    }
    return listOfNotNull(constructor, body)
}

/** 引号感知的括号配对：返回与 [openIndex] 处开括号配对的闭括号偏移，配对不上返回 null。 */
private fun matchingEnd(text: String, openIndex: Int, openChar: Char, closeChar: Char): Int? {
    var depth = 0
    var index = openIndex
    var quote: Char? = null
    while (index < text.length) {
        val char = text[index]
        when {
            quote != null -> {
                if (char == '\\') index += LINE_OFFSET else if (char == quote) quote = null
            }
            char == '"' || char == '\'' -> quote = char
            char == openChar -> depth += 1
            char == closeChar -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += LINE_OFFSET
    }
    return null
}

/** 把块注释 / 行注释替换成等长空白，保留换行与字符偏移。 */
private fun blankComments(text: String): String =
    COMMENT.replace(text) { match ->
        match.value.map { char -> if (char == NEWLINE) NEWLINE else ' ' }.joinToString("")
    }

/** 字符偏移折算成 1 起算的行号。 */
private fun lineOf(text: String, offset: Int): Int =
    text.take(offset.coerceIn(0, text.length)).count { char -> char == NEWLINE } + MIN_LINE

/** 目录下的 Kotlin 源码（IO 失败按空处理，交给调用方静默）。 */
private fun kotlinFiles(dir: Path): List<Path> =
    try {
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { path -> path.name.endsWith(KOTLIN_SUFFIX) }.toList()
        }
    } catch (_: IOException) {
        emptyList()
    }
