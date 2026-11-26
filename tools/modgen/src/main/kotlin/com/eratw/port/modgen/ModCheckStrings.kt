package com.eratw.port.modgen

/** 三语文案面支持的语言，顺序与 `StringsApi.declare(key, zh, en, ja)` 的实参顺序一致。 */
private enum class StringsLanguage(val tag: String) {
    ZH("zh"),
    EN("en"),
    JA("ja"),
}

/** 一处语言实参的静态形态；抽不到（变量 / 拼接 / 插值）记 [Opaque]，相关判据静默。 */
private sealed interface StringsSlot {
    /** 完整字符串字面量（空串也算，完整性判据会抓）。 */
    data class Literal(val text: String) : StringsSlot

    /** 实参缺失（位置实参少给 / 具名实参没写）。 */
    data object Missing : StringsSlot

    /** 实参抽不到稳定值（变量 / 拼接 / 插值），不能当证据。 */
    data object Opaque : StringsSlot
}

/** 一次 `declare(...)` 的实参抽取结果：键（抽不到为 null）+ 每种语言的形态。 */
private class StringsParsed(val key: String?, val slots: Map<StringsLanguage, StringsSlot>)

/** 一处 `declare(...)` 声明；[stringsDsl] 区分本面 DSL 与作者自己的同名函数。 */
private class StringsDeclaration(
    val key: String?,
    val stringsDsl: Boolean,
    val slots: Map<StringsLanguage, StringsSlot>,
    val file: String,
    val line: Int,
)

/** 一处 `strings.of/has` 读取：键抽不到为 null（该读取跳过，不做任何结论）。 */
private class StringsRead(val key: String?, val file: String, val line: Int)

/** 正则组号（`groupValues` 下标）。 */
private const val RECEIVER_GROUP = 1

private const val LITERAL_GROUP = 1

private const val IDENTIFIER_GROUP = 2

private const val CONST_NAME_GROUP = 1

private const val CONST_VALUE_GROUP = 2

private const val NAMED_NAME_GROUP = 1

private const val NAMED_VALUE_GROUP = 2

/** 与运行期 `ModStringsRegistry.requireValidKey` 同口径的键形态：`[a-z0-9_.-]+`。 */
private val KEY_SHAPE = Regex("[a-z0-9_.-]+")

/** 声明调用点；组 1 = 接收者链（`context.strings.` / 空），用来区分本面 DSL 与同名函数。 */
private val DECLARE_SITE = Regex("""\b((?:[A-Za-z_]\w*\s*\.\s*)*)declare\s*\(""")

/** 具名实参 `名字 = 表达式`（组 1 = 名字、组 2 = 表达式；`==` 不算赋值）。 */
private val NAMED_ARGUMENT = Regex("""^([A-Za-z_]\w*)\s*=(?!=)\s*(.+)$""")

/** `strings.of/has(...)` 的首个实参：组 1 = 字符串字面量、组 2 = 标识符链（常量引用）。 */
private val READ_ARGUMENT = Regex(
    """\bstrings\s*\.\s*(?:of|has)\s*\(\s*(?:("(?:[^"\\]|\\.)*")|([A-Za-z_][\w.]*))\s*(?=[,)])""",
)

/** 模块内 `const val NAME: String = "字面量"`：键用常量引用时的解析表（与设置面同一套写法）。 */
private val CONST_LITERAL = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*"([^"\\$]*)"""")

/**
 * `check-mod` 的 mod 自带三语文案面（v10 `StringsApi`）：声明自洽 + 「用了没声明」。
 *
 * 为什么值得单独一条检查：[StringsApi.of] 对未声明的键**原样返回键**、不抛也不返回空串 —— 这是
 * 「文案没声明」的可见标记，但对作者来说，`declare("greeting", …)` 写成 `of("gretting")` 时
 * 玩家只会看到 `gretting` 这个技术标识，运行期没有任何报错。它是本仓最难查的一类作者错误，
 * 所以静态面兜一道；声明自身的三条判据（三语齐全 / 重复 / 键形态 / 文本超长）同理。
 *
 * 只做**文本抽取**，不做 Kotlin 语法分析（宁可漏报，不许假红）：
 * - 声明键：`上下文.strings.declare("键", …)` / `declare(key = "键", …)` 的首个实参，可为跨行；
 * - 读取键：`strings.of("键")` / `strings.has("键")` 的首个实参（`context.strings` 与参数名
 *   `strings` 都认，别的 API 的 `of` / `has` 不认）；
 * - 键既认字符串字面量，也认本模块内的 `const val NAME: String = "…"` 常量引用；
 * - 三语实参按 `zh`/`en`/`ja` 具名、或位置实参第 2/3/4 个判定，**字面量才算数**：
 *   变量 / 拼接 / 插值记「抽不到」，该语言的齐全与超长判据一律跳过。
 *
 * 判据与严重度（分类口径：必然失效 ⇒ ERROR；可能失效 / 可改写 ⇒ WARN）：
 * - 键形态非法（空白键、超 [ModStringsLimits.MAX_KEY_LENGTH]、非 `[a-z0-9_.-]`）= ERROR：
 *   运行期 `requireValidKey` 当场抛，等于整个 mod 载不进来，必然失效；
 * - 同一键重复声明 = ERROR：运行期后值静默覆盖前值，前一条文案永远到不了玩家，静态面能确定；
 * - 缺 / 空三语文案 = ERROR：`declare` 对 ZH/EN/JA 三条原文逐个 `requireText`（`require(isNotBlank())`），
 *   空串 / 漏写都在声明阶段抛 `IllegalArgumentException` ⇒ 装配失败、mod 载不进来，必然失效；
 * - 单条文本超 [ModStringsLimits.MAX_TEXT_LENGTH] = ERROR：同一条 `requireText` 也 `require` 上限，
 *   声明阶段就抛 ⇒ 载不进来，必然失效；长文本应改走随包 resources；
 * - 用了没声明 = WARN：[StringsApi.of] 原样回键不抛，且运行期拼键是合法写法，静态面可能抽不全。
 *
 * 静默口径（抽不到就沉默，理由都是同一句：静态面看不到的值不能当证据）：
 * - 声明侧任一处键抽不到（变量 / 插值 / 常量不在本模块内）⇒ 整个检查面直接静默 ——
 *   那一处可以声明任意键，任何「未声明」结论都可能是假红；
 * - 某语言的实参抽不到（变量 / 拼接 / 插值）⇒ 该语言的齐全 / 超长判据跳过；
 * - 键里带 `$`（运行期插值）的读取 ⇒ 该读取跳过；
 * - 源码里没有 `strings.of` / `strings.has` 且没有本面 `declare` ⇒ 什么都不报。
 *
 * 反向方向（声明了但从未使用）**静默**：未使用的键不是运行期缺陷（键可能是给宿主 / 后续代码 /
 * 另一个包的载体留的），报 WARN 只会训练作者忽略警告。真正需要抓的是「用了却没声明」。
 *
 * 多包模块：本面不按包拆声明集合，一处读取命中**任一**载体的声明键就算已声明 —— 与
 * [ModCheckImages] 的多包口径一致，宁可漏报。上限常量见 [ModStringsLimits]，与 mod-api
 * `StringsSpec` 同值，漂移由测试文本哨兵兜底。
 */
internal object ModCheckStrings {

    /** 扫描 [sources] 的三语文案面：声明自检 + 「用了没声明」，结果写入 [findings]。 */
    fun check(sources: List<SourceFile>, findings: MutableList<Finding>) {
        val consts = constValues(sources)
        val texts = sources.map { source -> source.label to codeText(source) }
        val declarations = texts.flatMap { (file, text) -> declarationsIn(file, text, consts) }
        if (declarations.any { declaration -> declaration.key == null }) return
        val declared = mutableMapOf<String, StringsDeclaration>()
        declarations.forEach { declaration -> checkDeclaration(declaration, declared, findings) }
        val reads = texts.flatMap { (file, text) -> readsIn(file, text, consts) }
        checkReads(declared, reads, findings)
    }

    /** 单个源文件的声明；键抽不到时也记一条（key = null），用来把整个检查面判成不完整。 */
    private fun declarationsIn(file: String, text: String, consts: Map<String, String?>): List<StringsDeclaration> =
        DECLARE_SITE.findAll(text).map { match ->
            val receiver = match.groupValues[RECEIVER_GROUP].filterNot { char -> char.isWhitespace() }
            val args = callArgs(text, match.range.last)
            val parsed = parseDeclaration(splitArguments(args.orEmpty()), consts)
            StringsDeclaration(
                key = parsed.key,
                stringsDsl = receiver.endsWith("strings."),
                slots = parsed.slots,
                file = file,
                line = lineOf(text, match.range.first),
            )
        }.toList()

    /** 单个源文件的 `strings.of/has` 读取；常量引用也解析（内置载体就是这么写的）。 */
    private fun readsIn(file: String, text: String, consts: Map<String, String?>): List<StringsRead> =
        READ_ARGUMENT.findAll(text).map { match ->
            val token = match.groupValues[LITERAL_GROUP].ifEmpty { match.groupValues[IDENTIFIER_GROUP] }
            StringsRead(resolveKey(token, consts), file, lineOf(text, match.range.first))
        }.toList()

    /** 单个声明的分派：非本面 DSL 只登记键（避免「未声明」假红），本面 DSL 才判四条判据。 */
    private fun checkDeclaration(
        declaration: StringsDeclaration,
        declared: MutableMap<String, StringsDeclaration>,
        findings: MutableList<Finding>,
    ) {
        val key = declaration.key ?: return
        when {
            !declaration.stringsDsl -> declared.putIfAbsent(key, declaration)
            !KEY_SHAPE.matches(key) || key.length > ModStringsLimits.MAX_KEY_LENGTH -> {
                declared.putIfAbsent(key, declaration)
                findings += illegalKey(declaration.file, declaration.line, key)
            }
            else -> checkValidDeclaration(declaration, key, declared, findings)
        }
    }

    /** 合法声明的重复 / 三语齐全 / 文本超长判据；重复时两处都要改，故后续判据照跑。 */
    private fun checkValidDeclaration(
        declaration: StringsDeclaration,
        key: String,
        declared: MutableMap<String, StringsDeclaration>,
        findings: MutableList<Finding>,
    ) {
        val previous = declared[key]
        if (previous != null) findings += duplicate(declaration, key, previous) else declared[key] = declaration
        val missing = StringsLanguage.entries.filter { language ->
            val slot = declaration.slots[language]
            slot == null || slot is StringsSlot.Missing ||
                (slot is StringsSlot.Literal && slot.text.isBlank())
        }
        if (missing.isNotEmpty()) findings += incomplete(declaration, key, missing)
        val overlong = StringsLanguage.entries.mapNotNull { language ->
            val literal = declaration.slots[language] as? StringsSlot.Literal ?: return@mapNotNull null
            if (literal.text.length <= ModStringsLimits.MAX_TEXT_LENGTH) return@mapNotNull null
            language to literal.text.length
        }
        if (overlong.isNotEmpty()) findings += overlongText(declaration, key, overlong)
    }

    /** 读取面核对：用了、但本模块没声明过的键报 WARN（抽不到键的读取直接跳过）。 */
    private fun checkReads(
        declared: Map<String, StringsDeclaration>,
        reads: List<StringsRead>,
        findings: MutableList<Finding>,
    ) {
        reads.forEach { read ->
            val key = read.key ?: return@forEach
            if (key !in declared) findings += undeclared(read, key)
        }
    }

    /** 键形态非法（空 / 空白 / 超长 / 非小写字符集）：运行期 declare 当场抛，故 ERROR。 */
    private fun illegalKey(file: String, line: Int, key: String): Finding = Finding(
        severity = Severity.ERROR,
        file = file,
        line = line,
        message = "文案键形态非法：[$key]（与运行期 requireValidKey 同口径：非空、只许 [a-z0-9_.-]、" +
            "不超 ${ModStringsLimits.MAX_KEY_LENGTH} 字符；declare 当场抛，等于 mod 载不进来，故 ERROR）",
    )

    /** 同一模块同一键声明了不止一次：运行期后值静默覆盖前值，前一条文案玩家永远看不到。 */
    private fun duplicate(declaration: StringsDeclaration, key: String, previous: StringsDeclaration): Finding =
        Finding(
            severity = Severity.ERROR,
            file = declaration.file,
            line = declaration.line,
            message = "文案键重复声明：$key（${previous.file}:${previous.line} 已声明过；" +
                "运行期后值静默覆盖前值，前一条文案永远到不了玩家，故 ERROR；删成一笔）",
        )

    /** 三语缺一：`declare` 对三条原文逐个 requireText，空串 / 漏写在声明阶段 require 抛 ⇒ 载不进来。 */
    private fun incomplete(
        declaration: StringsDeclaration,
        key: String,
        missing: List<StringsLanguage>,
    ): Finding = Finding(
        severity = Severity.ERROR,
        file = declaration.file,
        line = declaration.line,
        message = "文案键 $key 缺 ${missing.joinToString("/") { language -> language.tag }} 文案" +
            "（declare 要求 ZH/EN/JA 一次给齐；运行期 requireText 对空串 require 抛，" +
            "装配失败、mod 载不进来，故 ERROR）",
    )

    /** 单条文本超长：运行期 requireText 的 require 拒收（声明阶段抛、mod 载不进来），长度应改走随包 resources。 */
    private fun overlongText(
        declaration: StringsDeclaration,
        key: String,
        overlong: List<Pair<StringsLanguage, Int>>,
    ): Finding = Finding(
        severity = Severity.ERROR,
        file = declaration.file,
        line = declaration.line,
        message = "文案键 $key 的 ${overlong.joinToString("、") { (language, length) ->
            "${language.tag} 文案 $length 字符"
        }} 超过上限 ${ModStringsLimits.MAX_TEXT_LENGTH}（运行期 requireText 的 require 拒收，" +
            "声明阶段抛、mod 载不进来，故 ERROR；长文本改走随包 resources 文件）",
    )
}

/** 本模块 `const val` 字符串常量表：名字 → 唯一字面量值；同名不同值记 null（解析不了 ⇒ 静默）。 */
private fun constValues(sources: List<SourceFile>): Map<String, String?> {
    val values = mutableMapOf<String, MutableSet<String>>()
    sources.forEach { source ->
        CONST_LITERAL.findAll(codeText(source)).forEach { match ->
            values.getOrPut(match.groupValues[CONST_NAME_GROUP]) { mutableSetOf() } +=
                match.groupValues[CONST_VALUE_GROUP]
        }
    }
    return values.mapValues { (_, literals) -> literals.singleOrNull() }
}

/** 按顶层逗号切实参（字符串内逗号、括号内逗号不算）；只处理三种括号与 `"` 配对。 */
private fun splitArguments(args: String): List<String> {
    if (args.isBlank()) return emptyList()
    val mask = stringMask(args)
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    args.forEachIndexed { index, char ->
        if (mask[index]) return@forEachIndexed
        when {
            char == ',' && depth == 0 -> {
                parts += args.substring(start, index)
                start = index + 1
            }
            char == '(' || char == '[' || char == '{' -> depth += 1
            char == ')' || char == ']' || char == '}' -> depth -= 1
        }
    }
    parts += args.substring(start)
    return parts.map { part -> part.trim() }.filter { part -> part.isNotEmpty() }
}

/**
 * 标出 [text] 里处于字符串字面量内部的字符（含引号与转义序列）；切分与取实参文本共用。
 *
 * 只做 `"` + `\` 的配对，不做 Kotlin 语法分析：这是文本抽取，够用即可，配不平就当没看见。
 */
private fun stringMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length)
    var inString = false
    var escaped = false
    text.forEachIndexed { index, char ->
        if (inString) mask[index] = true
        when {
            escaped -> {
                mask[index] = true
                escaped = false
            }
            char == '\\' && inString -> {
                mask[index] = true
                escaped = true
            }
            char == '"' -> {
                mask[index] = true
                inString = !inString
            }
        }
    }
    return mask
}

/**
 * 从 [open] 处的 `(` 起取整段实参文本（不含外层括号）；括号配不平返回 null 跳过。
 *
 * 字符串内部字符按 [stringMask] 跳过（注释已由 [ModCheckSources] 剥掉）。
 */
private fun callArgs(text: String, open: Int): String? {
    val mask = stringMask(text)
    var depth = 0
    for (index in open until text.length) {
        if (mask[index]) continue
        when (text[index]) {
            '(' -> depth += 1
            ')' -> {
                depth -= 1
                if (depth == 0) return text.substring(open + 1, index)
            }
        }
    }
    return null
}

/** 一次声明的实参文本 → 键 + 三语形态；`key =` 具名与位置实参两种写法都认。 */
private fun parseDeclaration(arguments: List<String>, consts: Map<String, String?>): StringsParsed {
    fun slot(token: String): StringsSlot {
        val literal = literalOf(token)
        return if (literal == null) StringsSlot.Opaque else StringsSlot.Literal(literal)
    }
    val parsed = arguments.map { argument ->
        val match = NAMED_ARGUMENT.find(argument.trim())
        val name = match?.groupValues?.get(NAMED_NAME_GROUP)
        val value = match?.groupValues?.get(NAMED_VALUE_GROUP)?.trim() ?: argument.trim()
        name to value
    }
    val names = parsed.map { (name, _) -> name }
    val namedKey = names.indexOfFirst { name -> name == "key" }
    val positionalKey = names.indexOf(null)
    val keyIndex = if (namedKey >= 0) namedKey else positionalKey
    val key = if (keyIndex >= 0) resolveKey(parsed[keyIndex].second, consts) else null
    val slots: MutableMap<StringsLanguage, StringsSlot> =
        StringsLanguage.entries.associateWith { StringsSlot.Missing }.toMutableMap()
    var nextPositional = 0
    arguments.indices.forEach { index ->
        if (index == keyIndex) return@forEach
        val language = when (names[index]) {
            null -> StringsLanguage.entries.getOrNull(nextPositional++) ?: return@forEach
            StringsLanguage.ZH.tag -> StringsLanguage.ZH
            StringsLanguage.EN.tag -> StringsLanguage.EN
            StringsLanguage.JA.tag -> StringsLanguage.JA
            else -> return@forEach
        }
        slots[language] = slot(parsed[index].second)
    }
    return StringsParsed(key, slots)
}

/** 一枚键实参 → 键文本；字面量带 `$` / 转义或常量解析不出时返回 null（该侧不完整）。 */
private fun resolveKey(token: String, consts: Map<String, String?>): String? =
    literalOf(token) ?: consts[token.substringAfterLast('.')]

/**
 * 完整字符串字面量 → 正文；抽不到稳定值返回 null。
 *
 * 返回 null 的形态：带 `$` 插值 / 转义（前后引号夹不住稳定正文），以及**表达式** ——
 * `"a" + "b"` 这类字面量拼接同样首尾带引号，但正文里含内部引号；不把它当完整字面量，
 * 否则拼出的垃圾正文会冒充作者的键/文案去报形态非法（KDoc 的总口径：抽不到就静默，不许假红）。
 */
private fun literalOf(token: String): String? {
    if (token.length < 2 || !token.startsWith('"') || !token.endsWith('"')) return null
    val body = token.substring(1, token.length - 1)
    return body.takeIf { text -> '$' !in text && '\\' !in text && '"' !in text }
}

/** 去注释后的整文件文本（行间用 `\n` 连回，跨行正则可匹配、行号可回算）。 */
private fun codeText(source: SourceFile): String = source.code.joinToString("\n") { line -> line.text }

/** [text] 里 [offset] 处的 1 基行号（[text] 是 [codeText] 的产物，行号与原始文件一致）。 */
private fun lineOf(text: String, offset: Int): Int = text.take(offset).count { char -> char == '\n' } + 1

private fun undeclared(read: StringsRead, key: String): Finding = Finding(
    severity = Severity.WARN,
    file = read.file,
    line = read.line,
    message = "文案键未声明：$key（本模块源码里没有 declare(\"$key\", …)；" +
        "of() 未声明时原样回键，玩家会看到技术标识，故只 WARN；" +
        "补 declare(\"$key\", …) 或修正键拼写）",
)
