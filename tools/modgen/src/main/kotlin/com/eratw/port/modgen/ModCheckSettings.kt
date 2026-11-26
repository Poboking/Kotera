package com.eratw.port.modgen

/** 设置项声明面的四种声明入口（与 mod-api 的 DeclDslSettings.kt 同源，文本哨兵防漂移）。 */
private enum class SettingCall(val entry: String) {
    INT("intSetting"),
    STR("strSetting"),
    BOOL("boolSetting"),
    ENUM("enumSetting"),
}

/** 一处设置声明：键（抽不到为 null）+ 入口类型 + 整个实参文本（range / candidates 二次解析用）。 */
private class Declaration(
    val key: String?,
    val kind: SettingCall,
    val args: String,
    val file: String,
    val line: Int,
)

/** 一处设置读取：键（抽不到为 null，读取面因此不完整 ⇒ 不报「声明了没用」）。 */
private class Read(val key: String?, val file: String, val line: Int)

private const val FIRST_GROUP = 1

private const val NAME_GROUP = 1

private const val VALUE_GROUP = 2

private const val LOW_GROUP = 1

private const val HIGH_GROUP = 2

/** 与 mod-api `SettingDecl.requireKey` 的 MAX_TEXT_LENGTH 同口径的键长上限。 */
private const val MAX_KEY_LENGTH = 512

/** 与 mod-api `SettingDecl.requireKey` 的 `KEY_PATTERN` 同口径：`[A-Za-z0-9_.:-]+`。 */
private val KEY_SHAPE = Regex("[A-Za-z0-9_.:-]+")

/** 四种声明调用的入口；[SettingCall.entry] 与这里的字面量靠文本哨兵对齐。 */
private val DECL_SITE = Regex("""\b(intSetting|strSetting|boolSetting|enumSetting)\s*\(""")

/**
 * 声明调用的首个实参：位置实参或 `key = ...` 具名实参；组 1 = 字面量或标识符链。
 *
 * 组尾的 `(?=\s*[,)]|$)` 保证只认整枚实参，不把 `Keys.A + "x"` 这类拼接截成 `Keys.A`。
 */
private val FIRST_ARGUMENT = Regex(
    """^\s*(?:key\s*=\s*)?("(?:[^"\\]|\\.)*"|[A-Za-z_][\w.]*)(?=\s*[,)]|$)""",
)

/** `settings.int(...)` / `.str` / `.bool` / `.enumName` / `.has` 的键实参：组 1 字面量、组 2 标识符链。 */
private val READ_ARGUMENT = Regex(
    """\bsettings\s*\.\s*(?:int|str|bool|enumName|has)\s*\(\s*""" +
        """(?:("(?:[^"\\]|\\.)*")|([A-Za-z_][\w.]*))\s*(?=[,)])""",
)

/** 模块内 `const val NAME: String = "字面量"`：两侧都用常量引用键（内置载体 / 生成物）时的解析表。 */
private val CONST_LITERAL = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*"([^"\\$]*)"""")

/** `range = 下界..上界`，两端都是十进制字面量（带不带 `L` 后缀都认）。 */
private val INT_RANGE = Regex("""\brange\s*=\s*(-?\d+)L?\s*\.\.\s*(-?\d+)L?""")

/** `candidates = listOf()` / `emptyList()`：声明期必炸的空候选清单。 */
private val EMPTY_CANDIDATES = Regex(
    """\bcandidates\s*=\s*(?:emptyList\s*\(\s*\)|listOf\s*(?:<[^>\n]*>)?\s*\(\s*\))""",
)

/**
 * 抽不到键的声明形式：通用入口 `setting(...)` 与四个声明对象的直接构造。
 *
 * 这类写法一行内可以声明任意键，静态面拿不到键集合 ⇒ 声明集合不完整，交叉判据整体静默。
 */
private val OPAQUE_DECLARATION = Regex(
    """\b(?:IntSettingDecl|StrSettingDecl|BoolSettingDecl|EnumSettingDecl|setting)\s*\(""",
)

/**
 * `settings` 出现在非 `.` 之后的位置（如 `val api = context.settings` 起别名、或把整个 API 传参）。
 *
 * 别名之后的 `api.int("k")` 静态面认不出接收者就是 SettingsApi，读取集合会漏 —— 命中即视读取面
 * 不完整，不报「声明了没用」（宁可漏报，不许假红）。
 */
private val SETTINGS_ALIAS = Regex("""\bsettings\b\s*(?!\.)""")

/**
 * `check-mod` 的「设置声明面」（v5 `intSetting` / `strSetting` / `boolSetting` / `enumSetting`）：
 * 声明键与读取键必须自洽。
 *
 * 为什么值得单独一条检查：设置项在 manifest 里声明一次后，设置页自动出控件、值按包持久化；
 * [com.eratw.port.modapi.SettingsApi] 对**未声明**的键 fail-closed 抛 `IllegalArgumentException`，
 * 而对声明了却从不读取的键毫无提示。作者两类错都只能等玩家改设置或运行到那行才暴露：
 * ① 声明了但从不读（玩家改了没反应）；② 读了没声明的键（作者以为能调，界面却根本不显示）。
 *
 * 只做**文本抽取**，不做 Kotlin 语法分析（宁可漏报，不许假红）：
 * - 声明键：`intSetting(...)` / `strSetting(...)` / `boolSetting(...)` / `enumSetting(...)` 的首个
 *   实参（位置实参与 `key = ...` 两种写法）；
 * - 读取键：`settings.int(...)` / `.str` / `.bool` / `.enumName` / `.has` 的首个实参；
 * - 实参既认**字符串字面量**，也认本模块内的 `const val NAME: String = "..."` 常量引用
 *   （`Keys.SETTING_X` / `FooDeclarations.SETTING_X` 取末段解析；内置载体与 new-mod 生成物都是
 *   这种写法，不解析常量就整面全是噪声）。
 *
 * 静默口径（抽不到就沉默，理由都是同一句：静态面看不到的值不能当证据）：
 * - 声明侧有一处键抽不到（变量 / 插值 / 常量不在本模块内）⇒ 声明集合不完整：不再报
 *   「用了未声明」与「声明了没用」；
 * - 源码里出现 `setting(IntSettingDecl(...))` 这类静态面拿不到键的声明形式 ⇒ 同样静默；
 * - 读取侧有一处键抽不到，或 `settings` 被起别名 / 整体传参 ⇒ 读取集合不完整：不再报
 *   「声明了没用」（读取可能就在抽不到的那一处）；
 * - 整模块既没有声明也没有读取 ⇒ 什么都不报。
 *
 * 严重度（与运行期后果对齐）：
 * - 键形态非法 = ERROR（与 `SettingDecl.requireKey` 同口径：非空、只许 `[A-Za-z0-9_.:-]`、不超
 *   512 字符；声明期当场 throw，等于 mod 载不进来）；
 * - enum 的 `candidates = listOf()` / `emptyList()` = ERROR（声明期 `EnumSettingDecl` 当场 throw）；
 * - int 的 `range` 两端都是字面量且下界大于上界 = ERROR（声明期 `IntSettingDecl` 当场 throw）；
 * - 同一键重复声明 = WARN（设置页会出两个同名控件；属冗余写法，不崩）；
 * - 用了未声明 = WARN（运行期 fail-closed 抛，但静态面可能抽不全，不拿 ERROR 卡作者自检）；
 * - 声明了但全模块无读取 = WARN（键可能预留给后续代码或宿主，报 ERROR 会假红）。
 *
 * 多包模块：本面不按包拆声明集合，一处读取命中**任一**载体的声明键就算已声明 —— 与
 * [ModCheckStrings] / [ModCheckImages] 的多包口径一致，宁可漏报。
 *
 * 与 mod-api 的入口名/键形态同源：`ModCheckSettingsTest.dslNamesStayInSyncWithModApiSource`
 * 的文本哨兵会红，若声明 DSL 或 [com.eratw.port.modapi.SettingsApi] 换了名字而这里没跟上。
 */
internal object ModCheckSettings {

    /** 扫描 [sources] 的设置声明与读取，双向对拍后写入 [findings]。 */
    fun check(sources: List<SourceFile>, findings: MutableList<Finding>) {
        val consts = constValues(sources)
        val texts = sources.map { source -> source.label to codeText(source) }
        val declarations = texts.flatMap { (file, text) -> declarationsIn(file, text, consts) }
        val declared = mutableMapOf<String, Declaration>()
        declarations.forEach { declaration -> checkDeclaration(declaration, declared, findings) }
        val reads = texts.flatMap { (file, text) -> readsIn(file, text, consts) }
        val opaque = texts.any { (_, text) -> OPAQUE_DECLARATION.containsMatchIn(text) }
        val aliased = texts.any { (_, text) -> SETTINGS_ALIAS.containsMatchIn(text) }
        val declarationComplete = !opaque && declarations.all { declaration -> declaration.key != null }
        val readComplete = !aliased && reads.all { read -> read.key != null }
        checkReads(declared, reads, declarationComplete, readComplete, findings)
    }

    /** 单个源文件的设置声明；实参抽不到键时也记一条（key = null），用来标记声明面不完整。 */
    private fun declarationsIn(
        file: String,
        text: String,
        consts: Map<String, String?>,
    ): List<Declaration> = DECL_SITE.findAll(text).mapNotNull { match ->
        val entry = SettingCall.entries.firstOrNull { call -> call.entry == match.groupValues[FIRST_GROUP] }
        val args = callArgs(text, match.range.last) ?: return@mapNotNull null
        val token = FIRST_ARGUMENT.find(args)?.groupValues?.get(FIRST_GROUP) ?: ""
        val line = lineOf(text, match.range.first)
        Declaration(resolveKey(token, consts), entry ?: SettingCall.STR, args, file, line)
    }.toList()

    /** 单个源文件的设置读取；`settings` 的接收者不论（context / view 都认）。 */
    private fun readsIn(file: String, text: String, consts: Map<String, String?>): List<Read> =
        READ_ARGUMENT.findAll(text).map { match ->
            val token = match.groupValues[LITERAL_GROUP].ifEmpty { match.groupValues[IDENTIFIER_GROUP] }
            Read(resolveKey(token, consts), file, lineOf(text, match.range.first))
        }.toList()

    /** 本模块 `const val` 字符串常量表：名字 → 唯一字面量值；同名不同值记 null（解析不了 ⇒ 静默）。 */
    private fun constValues(sources: List<SourceFile>): Map<String, String?> {
        val values = mutableMapOf<String, MutableSet<String>>()
        sources.forEach { source ->
            CONST_LITERAL.findAll(codeText(source)).forEach { match ->
                values.getOrPut(match.groupValues[NAME_GROUP]) { mutableSetOf() } += match.groupValues[VALUE_GROUP]
            }
        }
        return values.mapValues { (_, literals) -> literals.singleOrNull() }
    }

    /** 一枚键实参 → 键文本；字面量带 `$` / 转义或常量解析不出时返回 null（该侧不完整）。 */
    private fun resolveKey(token: String, consts: Map<String, String?>): String? = when {
        token.startsWith("\"") -> token.removeSurrounding("\"")
            .takeIf { body -> '$' !in body && '\\' !in body }
        token.isEmpty() -> null
        else -> consts[token.substringAfterLast('.')]
    }

    /** 单个声明的自检：键形态 / 重复 / int 区间 / enum 候选；合法键进 [declared] 供交叉判据。 */
    private fun checkDeclaration(
        declaration: Declaration,
        declared: MutableMap<String, Declaration>,
        findings: MutableList<Finding>,
    ) {
        val key = declaration.key ?: return
        if (!KEY_SHAPE.matches(key) || key.length > MAX_KEY_LENGTH) {
            findings += illegalKey(declaration.file, declaration.line, key)
            return
        }
        if (declared.putIfAbsent(key, declaration) != null) {
            findings += duplicate(declaration, key)
        }
        when (declaration.kind) {
            SettingCall.INT -> {
                val match = INT_RANGE.find(declaration.args)
                val low = match?.groupValues?.get(LOW_GROUP)?.toLongOrNull()
                val high = match?.groupValues?.get(HIGH_GROUP)?.toLongOrNull()
                if (low != null && high != null && low > high) findings += invertedRange(declaration, key, low, high)
            }
            SettingCall.ENUM -> if (EMPTY_CANDIDATES.containsMatchIn(declaration.args)) {
                findings += emptyCandidates(declaration, key)
            }
            SettingCall.STR, SettingCall.BOOL -> Unit
        }
    }

    /** 读取侧核对：读取键形态 / 用了未声明；两侧都完整时再报「声明了但全模块无读取」。 */
    private fun checkReads(
        declared: Map<String, Declaration>,
        reads: List<Read>,
        declarationComplete: Boolean,
        readComplete: Boolean,
        findings: MutableList<Finding>,
    ) {
        val used = mutableSetOf<String>()
        reads.forEach { read ->
            val key = read.key
            if (key == null) return@forEach
            if (!KEY_SHAPE.matches(key) || key.length > MAX_KEY_LENGTH) {
                findings += illegalKey(read.file, read.line, key)
            } else {
                used += key
                if (declarationComplete && key !in declared) findings += undeclared(read, key)
            }
        }
        if (!declarationComplete || !readComplete) return
        declared.forEach { (key, declaration) ->
            if (key !in used) findings += unused(declaration, key)
        }
    }

    /**
     * 从 [open] 处的 `(` 起取整段实参文本（不含外层括号）；括号/引号配不平返回 null 跳过。
     *
     * 只处理 `(` `)` 与 `"` 配对（注释已由 [ModCheckSources] 剥掉）；这是文本抽取，够用即可。
     */
    private fun callArgs(text: String, open: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '(' -> depth += 1
                char == ')' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(open + 1, index)
                }
            }
        }
        return null
    }

}

private fun illegalKey(file: String, line: Int, key: String): Finding = Finding(
    severity = Severity.ERROR,
    file = file,
    line = line,
    message = "设置项键形态非法：[$key]（与运行期 requireKey 同口径：非空、只许 [A-Za-z0-9_.:-]、" +
        "不超 $MAX_KEY_LENGTH 字符；声明期当场抛，故 ERROR）",
)

private fun duplicate(declaration: Declaration, key: String): Finding = Finding(
    severity = Severity.WARN,
    file = declaration.file,
    line = declaration.line,
    message = "设置项键重复声明：$key（同一模块里声明了多次；设置页会出多个同名控件，建议删成一笔）",
)

private fun invertedRange(declaration: Declaration, key: String, low: Long, high: Long): Finding = Finding(
    severity = Severity.ERROR,
    file = declaration.file,
    line = declaration.line,
    message = "设置项 $key 的 range 下界 $low 大于上界 $high（声明期 IntSettingDecl 当场拒绝，故 ERROR）",
)

private fun emptyCandidates(declaration: Declaration, key: String): Finding = Finding(
    severity = Severity.ERROR,
    file = declaration.file,
    line = declaration.line,
    message = "设置项 $key 的枚举候选为空（candidates = listOf()/emptyList()；" +
        "声明期 EnumSettingDecl 当场拒绝，故 ERROR）",
)

private fun undeclared(read: Read, key: String): Finding = Finding(
    severity = Severity.WARN,
    file = read.file,
    line = read.line,
    message = "设置项键未声明：$key（本模块源码里没有 intSetting/strSetting/boolSetting/" +
        "enumSetting 声明它；SettingsApi 对未声明键 fail-closed 抛，故 WARN；" +
        "补一条对应声明或修正键拼写）",
)

private fun unused(declaration: Declaration, key: String): Finding = Finding(
    severity = Severity.WARN,
    file = declaration.file,
    line = declaration.line,
    message = "设置项声明了但全模块无读取：$key（源码里没有 settings.int/str/bool/enumName/has " +
        "读它；玩家改了没反应，故 WARN；若是预留给后续代码可忽略）",
)

private const val LITERAL_GROUP = 1

private const val IDENTIFIER_GROUP = 2

/** 去注释后的整文件文本（行间用换行连回，跨行正则可匹配、行号可回算）。 */
private fun codeText(source: SourceFile): String = source.code.joinToString("\n") { line -> line.text }

/** [text] 里 [offset] 处的 1 基行号（[text] 是 [codeText] 的产物，行号与原始文件一致）。 */
private fun lineOf(text: String, offset: Int): Int = text.take(offset).count { char -> char == '\n' } + 1



