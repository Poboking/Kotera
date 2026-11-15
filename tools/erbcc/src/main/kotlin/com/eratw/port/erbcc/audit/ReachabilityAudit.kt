package com.eratw.port.erbcc.audit

import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.erbcc.LabelTable
import com.eratw.port.erbcc.Symbols
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.CorpusScan
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 可达性 / 可移植性报告（MODERN-R6 最小版：**只报告**，不预热、不改脚本）。
 *
 * 权威与范围：
 * - 根集合 = 事件名（`IdentifierDictionary.cs:72-89`，9 个）∪ 系统名（`:91-135`，含
 *   `COM` / `COM_ABLE` / `ABLUP` 三个正则 `:631-649`）。两者一律经
 *   [Symbols.isEventLabelName] / [Symbols.isSystemLabelName] 判定，本文件不另建表。
 * - 调用图是**文本级**的静态近似，且报告是**下界**：变量目标（`TRYCALLLIST` 等）、
 *   运行期拼出来的名字都不可静态穷举，一律落成可见的 `dynamic-call` 行（带 `file:line`
 *   与原文），**不**猜测展开。唯一例外是「嵌入项全是编译期常量」的 FORMS 模板
 *   （`CALLFORM SUB_%C_OK%` / `SUB_{C_OK}` / 整串就是一个字符串常量）—— `#DIM CONST`
 *   的值装载后不可变（必须有初值 `UserDefinedVariableData.cs:294-298`，赋值给 CONST 报
 *   `LeftIsConst` 告警 `ArgumentParser.cs:903-905`），目标名因此可**完整**求值，按静态边
 *   计入；只要有一个嵌入项不是常量、或求值结果不是合法标识符，仍维持 `dynamic-call`。
 *   初值允许**传递引用**更早声明的 CONST（引擎在声明解析相位就把初值式 Restructure 成
 *   SingleTerm 存值：`UserDefinedVariableData.cs:405-421`、`VariableTerm.cs:337-339`、
 *   `UserDefinedVariableToken.cs:35`），审计镜像为传递解析（[resolveConstChains]，
 *   MODERN-R13-R6D-①）；表达式初值仍不折（本波 ① 契约窄口径，见 [parseDimDecl] KDoc）。
 * - `#DEFINE` 宏（MODERN-R12-R6D）同走这条求值路径，但只落**嵌入位**：引擎的宏展开是词法
 *   token 流替换（C# `LexicalAnalyzer.cs:942-976 expandMacro` 只换 IdentifierWord；Kotlin
 *   镜像 `LexicalAnalyzer.kt:689-765` 只换 Token.Identifier），FORM 串**文本段**是原样字符
 *   （`LexicalAnalyzer.cs:1073-1146` / Kotlin `analyseFormattedString` 的 sb.append），非 FORM
 *   语句的 CALL 目标走 `ArgumentParser.cs:730` 的 ReadString 原文读取，都不查宏表。所以
 *   `CALLFORM M(…)`（M 是宏）调的是名为 M 的函数本身 ⇒ S1 静态边指向 M（引擎实际调用面），
 *   不按宏体改写；而 `%M%` / `{M}` 嵌入经引擎 `Analyse` 展开后若折叠成编译期常量（宏体 =
 *   单个整数字面量 / 单个引号串 / 单个标识符递归到 `#DIM CONST`，或**整串常量表达式**的
 *   窄折叠面 —— 引擎 Restructure 对算术折叠的判据见 [macroBodyValue] KDoc，MODERN-R13-R6D-②），
 *   目标名可**完整**求值 ⇒ 按静态边计入。宏体是窄判据之外的表达式、拼接、空体、循环引用 ⇒
 *   引擎侧是运行期求值或装载报错，审计一律 `dynamic-call`（下界）。宏表来源 = 编译侧既有
 *   ERH 解析器 [LabelTable.collectErhSymbols]
 *   （不新造解析器）；同名宏被 `#DIM` 声明遮蔽按更保守一侧（不可求值），见 [constOrMacroValueOf]。
 * - 可移植性诊断按 `persist` / `display` / `unknown` 三档分级，判据见 [PortabilityGrade]。
 *
 * 行号口径：1 基物理行号 = `LogicalLine.lineNo + 1`（`EraStreamReader.cs:30` 的 LineNo 是
 * 0 基），报告读者可直接跳到编辑器同一行。
 *
 * 确定性：文件按绝对路径字典序枚举（[CorpusCompile.listCorpusFiles]），行按
 * `(kind, grade, file, line, function, detail)` 排序，输出里没有绝对路径 / 时间戳 /
 * 主机名 / 耗时。
 */
object ReachabilityAudit {

    /**
     * 扫一遍语料，产出可达集 + 动态调用点 + 可移植性诊断。
     *
     * @param corpusRoot 语料根（通常是 `<gameRoot>/ERB`；[CorpusCompile.listCorpusFiles] 同口径）
     * @param extraRoots 额外根（`--roots A,B`）：名字命中的函数按 depth=0 追加为根
     * @param fileLimit 只扫前 N 个文件（调试档；与 `audit --limit` 同口径）
     * @param bootMetadata 已算好的侧车（`audit` 里由 [SymbolLinkAudit] 的编译产出直接复用，
     *   不再编译第二遍）；缺省 null ⇒ 持久化标志只取源码里 `#DIM` 声明的修饰符原文
     */
    fun audit(
        corpusRoot: Path,
        extraRoots: List<String> = emptyList(),
        fileLimit: Int? = null,
        bootMetadata: BootMetadata? = null,
    ): ReachabilityReport {
        val root = corpusRoot.toAbsolutePath().normalize()
        val all = try {
            CorpusCompile.listCorpusFiles(root)
        } catch (_: Exception) {
            emptyList()
        }
        val files = if (fileLimit != null && fileLimit in 1 until all.size) all.take(fileLimit) else all
        val scan = CorpusScan.scan(files)
        val graph = buildGraph(root, files, scan.macroNames)
        val resolver = DeclResolver(scan, graph, bootMetadata)

        // ---- BFS：根 = 事件名 ∪ 系统名（∪ 显式追加）----
        val depth = HashMap<String, Int>()
        val reachedFrom = HashMap<String, String>()
        val queue = ArrayDeque<FuncBlock>()
        val roots = ArrayList<FuncBlock>()
        for (fn in graph.functions) {
            if (!isRootName(fn.nameUpper)) continue
            if (depth.putIfAbsent(fn.key, 0) != null) continue
            reachedFrom[fn.key] = fn.nameUpper
            roots.add(fn)
            queue.addLast(fn)
        }
        for (raw in extraRoots) {
            val name = raw.trim().uppercase(Locale.ROOT)
            if (name.isEmpty()) continue
            for (fn in graph.byName[name] ?: emptyList()) {
                if (depth.putIfAbsent(fn.key, 0) != null) continue
                reachedFrom[fn.key] = fn.nameUpper
                roots.add(fn)
                queue.addLast(fn)
            }
        }

        val rows = ArrayList<ReachabilityRow>()
        val dynamicRows = ArrayList<ReachabilityRow>()
        while (queue.isNotEmpty()) {
            val fn = queue.removeFirst()
            val d = depth[fn.key] ?: 0
            val from = reachedFrom[fn.key] ?: fn.nameUpper
            if (d > 0) {
                rows.add(
                    ReachabilityRow(
                        ReachabilityKind.REACHABLE, REACH_GRADE_NONE, fn.nameUpper, fn.file, fn.line,
                        "depth=$d root=$from",
                    ),
                )
            }
            for (line in fn.code) {
                val stmt = statementOf(line.content)
                if (stmt !in CALL_STATEMENTS && stmt !in JUMP_STATEMENTS) continue
                val rest = line.content.substring(identEnd(line.content, 0))
                when (val target = parseCallTarget(stmt, rest, fn, graph)) {
                    is CallTarget.Func -> {
                        for (def in graph.byName[target.nameUpper] ?: emptyList()) {
                            if (depth.putIfAbsent(def.key, d + 1) != null) continue
                            reachedFrom[def.key] = from
                            queue.addLast(def)
                        }
                    }
                    is CallTarget.Label -> {
                        val def = resolveLabel(graph, fn, target.nameUpper) ?: continue
                        if (depth.putIfAbsent(def.key, d + 1) != null) continue
                        reachedFrom[def.key] = from
                        queue.addLast(def)
                    }
                    is CallTarget.Dynamic -> dynamicRows.add(
                        ReachabilityRow(
                            ReachabilityKind.DYNAMIC_CALL, REACH_GRADE_NONE, fn.nameUpper, fn.file,
                            line.lineNo + 1, "${stmt} ${snippet(line.content)}",
                        ),
                    )
                }
            }
        }
        // 根自身也占一行（depth=0），报告里一眼能看出「哪些名字被判成了根」
        for (fn in roots) {
            rows.add(
                ReachabilityRow(
                    ReachabilityKind.REACHABLE, REACH_GRADE_NONE, fn.nameUpper, fn.file, fn.line,
                    "depth=0 root=${fn.nameUpper}",
                ),
            )
        }

        // ---- 可移植性诊断：只扫可达函数 ----
        for (fn in graph.functions) {
            if (fn.key !in depth) continue
            for (line in fn.code) {
                val token = portabilityToken(line.content) ?: continue
                val grade = gradeLine(resolver, fn, line.content)
                rows.add(
                    ReachabilityRow(
                        ReachabilityKind.PORTABILITY, grade.label, fn.nameUpper, fn.file,
                        line.lineNo + 1, "$token ${snippet(line.content)}",
                    ),
                )
            }
        }

        val allRows = (rows + dynamicRows).sortedWith(
            compareBy(
                { it.kind.label },
                { it.grade },
                { it.file },
                { it.line },
                { it.function },
                { it.detail },
            ),
        )
        return ReachabilityReport(
            roots = roots.size,
            reachable = depth.size,
            rows = allRows,
        )
    }

    /** `reachable` / `dynamic-call` 的 grade 列固定写 `-`（契约 §3.5）。 */
    private const val REACH_GRADE_NONE = "-"

    /**
     * 根判定：事件名 ∪ 系统名。
     *
     * `IdentifierDictionary.IsSystemLabelName` 的名单已经包含全部事件名
     * （`IdentifierDictionary.cs:95-113` 与 `:76-84` 同源），两个判定取并集只为可读性。
     */
    private fun isRootName(name: String): Boolean =
        Symbols.isEventLabelName(name) || Symbols.isSystemLabelName(name)

    // ------------------------------------------------------------------
    // 语句位调用边
    // ------------------------------------------------------------------

    /** 语句位可产生调用边的关键字（契约 §3.2.3）。 */
    private val CALL_STATEMENTS: Set<String> = setOf(
        "CALL", "CALLFORM", "CALLF", "CALLFORMF",
        "TRYCALL", "TRYCALLFORM", "TRYCCALL", "TRYCCALLFORM",
        "TRYCALLLIST", "TRYJUMPLIST", "TRYGOTOLIST", "CALLEVENT",
    )

    /** 同文件跳转：目标先在本函数内找标签，再退到本文件（契约 §3.2.3）。 */
    private val JUMP_STATEMENTS: Set<String> = setOf("JUMP", "GOTO")

    /** 首参是 FORMS 串的调用：`CALLFORM 名前(…)`。 */
    private val FORM_CALL_STATEMENTS: Set<String> =
        setOf("CALLFORM", "CALLFORMF", "TRYCALLFORM", "TRYCCALLFORM")

    /** 目标是列表变量（`TRYCALLLIST FOO, …`）的调用：一律动态。 */
    private val LIST_CALL_STATEMENTS: Set<String> =
        setOf("TRYCALLLIST", "TRYJUMPLIST", "TRYGOTOLIST")

    /** 一条调用语句解析出的目标。 */
    private sealed class CallTarget {
        /** 静态函数名（已大写）。 */
        class Func(val nameUpper: String) : CallTarget()

        /** 静态标签名（已大写），只对 `JUMP` / `GOTO` 产生。 */
        class Label(val nameUpper: String) : CallTarget()

        /** 静态不可判定：模板 / 变量 / 列表 / 表达式 —— 落成 `dynamic-call` 行。 */
        object Dynamic : CallTarget()
    }

    /**
     * 解析语句位的调用目标。
     *
     * S1（整串就是一个标识符）。
     * - `CALL FOO(…)` / `CALL "FOO"`（不带 `%`）⇒ [CallTarget.Func]；
     * - `CALLFORM "FOO"` ⇒ [CallTarget.Func]；`CALLFORM FOO(…)` 同样 ⇒ [CallTarget.Func]
     *   （R6-C 前的实现把所有**裸** FORMS 目标一律判 dynamic —— 上游 `literal == null` 早退，
     *   与本函数 KDoc 及契约 §3.1 的 S1 不符；本波按 S1 定义修正，仍要求整段是标识符）。
     * 非 FORMS 语句的 S1 判据逐字未动。
     *
     * S2 / S3 / S4（本波新增）：FORMS 串的嵌入项**全是**编译期常量时，按常量值代入后若恰好
     * 是合法标识符 ⇒ 静态边。三形态同一条求值路径，差别只在常量长什么样：
     * - S2 `CALLFORM SUB_%C_OK%` + `#DIM CONST C_OK = 7` ⇒ 目标 `SUB_7`；
     * - S3 `CALLFORM SUB_{C_OK}` ⇒ 同上；
     * - S4 `CALLFORM %TARGET%` + `#DIMS CONST TARGET = "FOO"` ⇒ 目标 `FOO`。
     * 求值规则见 [evalConstForm]，常量来源见 [constOrMacroValueOf]。
     *
     * R6-D（宏）：嵌入项也可以是 `#DEFINE` 宏名 —— 求值顺序 = 先查宏表（同名 `#DIM` 声明
     * 存在即保守放弃），宏体收敛成单个字面量 / 单个常量名才代入；**裸文本段**的宏名**不**展开
     * （引擎 FORM 文本段不查宏表，`LexicalAnalyzer.cs:1073-1146`；`CALLFORM M(…)` 调的是 @M
     * 本身 ⇒ S1 静态边指向 M，契约 §0.1-②），因此裸形态无需任何宏分支。
     *
     * 其余一律 [CallTarget.Dynamic]：含非常量嵌入项、列表目标、表达式目标、常量未声明 /
     * 不是 CONST / 初值不是单个字面量（数组常量、表达式常量）、求值结果不是标识符。
     * **不许猜**：不做前缀匹配、不做 glob、不做「只有唯一同名函数就当它」，也不裁掉变量部分
     * 只取字面量前缀 —— `CALLFORM SUB_{LOCAL}`（LOCAL 是非常量）是 dynamic，不是 `SUB_`。
     */
    private fun parseCallTarget(stmt: String, restRaw: String, fn: FuncBlock, graph: CorpusGraph): CallTarget {
        if (stmt in LIST_CALL_STATEMENTS) return CallTarget.Dynamic
        val rest = restRaw.trimStart()
        if (rest.isEmpty()) return CallTarget.Dynamic
        if (stmt in FORM_CALL_STATEMENTS) {
            val literal = quotedLiteral(rest)
            val form = literal ?: formTextOf(rest) ?: return CallTarget.Dynamic
            if ('%' !in form && '{' !in form) {
                // S1 判据：整串就是一个标识符。引号形态取引号内文本（既有判据去一层花括号），
                // 裸形态取到结束字符为止的**整段**文本 —— 不按词边界截前缀（`FOO BAR` 是
                // 名字里带空格的动态目标，不是 `FOO`）。
                val name = if (literal != null) literal.trim().trim('{', '}').trim() else form
                return callTargetOf(stmt, name)
            }
            return callTargetOf(stmt, evalConstForm(form) { name -> constOrMacroValueOf(graph, fn, name) })
        }
        val literal = quotedLiteral(rest)
        val rawName: String? = if (literal != null) {
            if ('%' in literal) return CallTarget.Dynamic
            literal.trim()
        } else {
            if (rest[0] == '%' || rest[0] == '(' || rest[0] == '[') return CallTarget.Dynamic
            val end = identEnd(rest, 0)
            if (end == 0) return CallTarget.Dynamic
            rest.substring(0, end)
        }
        return callTargetOf(stmt, rawName)
    }

    /** 目标名 → 目标类别；空串或不是合法标识符 ⇒ [CallTarget.Dynamic]。 */
    private fun callTargetOf(stmt: String, rawName: String?): CallTarget {
        if (rawName.isNullOrEmpty() || !isIdentifier(rawName)) return CallTarget.Dynamic
        return if (stmt in JUMP_STATEMENTS) {
            CallTarget.Label(rawName.uppercase(Locale.ROOT))
        } else {
            CallTarget.Func(rawName.uppercase(Locale.ROOT))
        }
    }

    /** FORMS 串的结束字符：左圆括号 / 左方括号 / 逗号 / 分号。 */
    private const val FORM_TERMINATORS = "([,;"

    /**
     * 从调用语句的实参原文里切出 FORMS 串：到第一个结束字符为止。
     *
     * 结束字符集 = 原版 `FormStrEndWith.LeftParenthesis_Bracket_Comma_Semicolon`
     * （spec: ArgumentParser.cs:725 的 `AnalyseFormattedString(…, trim: true)`；字符分派见
     * `LexicalAnalyzer.cs:1083-1112`）。尾部空白按 `trim: true` 截掉（同函数
     * `LexicalAnalyzer.cs:1223-1227` 只截首/末文本段）。引号形态由 [quotedLiteral] 另走一路
     * （原版引号分支传 `trim: false`）。
     */
    private fun formTextOf(rest: String): String? {
        if (rest.isEmpty()) return null
        var end = 0
        while (end < rest.length && rest[end] !in FORM_TERMINATORS) end++
        return rest.substring(0, end).trimEnd(' ', '\t')
    }

    /**
     * 把 FORMS 串的嵌入项按编译期常量代入；任一项求不出 / 结果不是合法标识符 ⇒ null。
     *
     * 只处理两种插值（spec: `LexicalAnalyzer.cs:1113-1132`）：`%expr%` 与 `{expr}`，且 `expr`
     * 必须**整段就是一个标识符**（带下标 `%X:1%`、表达式、空白一律不求值），该标识符还得在
     * 常量表里（[constValueOf]）。整数常量按十进制文本代入、字符串常量按原值代入 —— 这正是
     * 原版常量项在 FORMS 里的文本形态。
     *
     * 其它字符原样保留：`\%` / `\n` 一类转义与三连记号（`$$$` 等）都不展开，它们会留在结果里
     * ⇒ 不是标识符 ⇒ 返回 null（维持 dynamic），不做近似。
     */
    private fun evalConstForm(text: String, constOf: (String) -> String?): String? {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '%' -> {
                    val end = text.indexOf('%', i + 1)
                    if (end < 0) return null
                    sb.append(constOperand(text.substring(i + 1, end), constOf) ?: return null)
                    i = end + 1
                }
                '{' -> {
                    val end = text.indexOf('}', i + 1)
                    if (end < 0) return null
                    sb.append(constOperand(text.substring(i + 1, end), constOf) ?: return null)
                    i = end + 1
                }
                else -> {
                    sb.append(text[i])
                    i++
                }
            }
        }
        val name = sb.toString()
        return if (isIdentifier(name)) name else null
    }

    /** 单个嵌入项求值：整段是一个标识符且命中常量表才有值（否则 null = 不可求值）。 */
    private fun constOperand(expr: String, constOf: (String) -> String?): String? =
        if (isIdentifier(expr)) constOf(expr) else null

    /**
     * FORMS 嵌入项的求值入口（MODERN-R12-R6D）：先看是不是 `#DEFINE` 宏，再退回
     * [constValueOf] 的 `#DIM CONST` 路径。
     *
     * 宏遮蔽口径（契约 §1-2）：同名声明（本函数私有或全局 `#DIM`）存在 ⇒ 宏视为被遮蔽、
     * 不可求值 ⇒ dynamic。合法语料里宏名撞变量名原版装载期即拒（`Symbols.checkUserVarName`
     * / `checkUserPrivateVarName` 都把宏名表传进去查撞，ERH `#DIM` 撞宏见
     * `Symbols.kt:1248-1256`），审计不替坏语料挑边 —— 也不「fallback 到声明值」：引擎在
     * 词法层无条件展开宏（`expandMacro` 先于一切名字解析），撞名语料本身编不出来，挑哪边
     * 都是猜。
     */
    private fun constOrMacroValueOf(graph: CorpusGraph, fn: FuncBlock, name: String, depth: Int = 0): String? {
        if (depth >= MACRO_EXPANSION_LIMIT) return null
        val upper = name.uppercase(Locale.ROOT)
        val body = graph.macros[upper] ?: return constValueOf(graph, fn, name)
        if (upper in fn.privateDecls || upper in graph.globalDecls) return null
        return macroBodyValue(graph, fn, body, depth)
    }

    /**
     * 宏体 → 可代入文本；不可求值 ⇒ null。收四形态（引擎把宏体**重新词法化**成 token 流
     * 再拼回使用点（`LexicalAnalyzer.kt:756-759`），逐 token 折叠后静态可证的形态）：
     * - 单个整数字面量（十进制 / 0x 十六进制 / 一元正负号）→ [intLiteralText] 按值折十进制文本
     *   （引擎嵌入项 = 常量表达式，值即此数）；
     * - 单个引号串字面量（整段剥引号后无内嵌引号）→ 原样代入（S4 同形：嵌入值 = 该常量串）；
     * - 单个裸标识符 → 递归 [constOrMacroValueOf]：链上再遇宏就继续展开（引擎的展开是
     *   「迭代到无可换」，`LexicalAnalyzer.kt:737-763`），最终落在 `#DIM CONST` 上才有值，
     *   落在普通变量名上 = 运行期引用 ⇒ null；
     * - **整串常量表达式**（MODERN-R13-R6D-② 窄折叠面）→ [constExprTyped] 按引擎 Restructure
     *   同判据求值：整数四则 `+ - * / %`、一元正负、括号、串拼接 `"A" + "B"`、串重复
     *   `"A" * 2`（双序同收），操作数可为字面量 / `#DIM CONST` 名 / 宏名（递归）。
     *   C# 判据链：CALLFORM 目标在装载相位无条件 Restructure（`ArgumentParser.cs:725-731`，
     *   form 分支 `AnalyseFormattedString` → `ToStrFormTerm` → `Restructure`）；嵌入项经
     *   `Analyse` 过宏展开（`LexicalAnalyzer.cs:731` 的 `expandMacro` 收尾；`%…%` 与花括号嵌入的
     *   分派在 `:1109-1126`）；StrForm 对 SingleTerm 嵌入折进文本段（`StrForm.cs:364-395`）、
     *   全嵌入折完即 IsConst（`StrFormTerm.cs:26-40` 收成 SingleStrTerm）；二目/一目算术的
     *   OperatorMethod 全部 `CanRestructure = true`（`OperatorMethodManager.cs:18` `+`,
     *   `:51` `-`, `:65` `*`, `:125` `/`, `:144` `%`, `:32` 串拼接, `:79` 串乘,
     *   `:541`/`:555` 一元正负），折叠入口 = `FunctionMethodTerm.cs:35-58`（方法可折且
     *   实参全 SingleTerm ⇒ `GetValue`）。
     *
     * 窄判据之外一律 null（维持 dynamic，不许猜）：
     * - 比较 / 逻辑 / 位 / 移位 / 三目：引擎虽可折（`OperatorMethodManager.cs:159-691` 的
     *   `CanRestructure = true`），但等价镜像要收 `&&` `!&` `^^` 一族两字符算子的分词与
     *   短路求值语义（Kotlin 侧无既有解析器可复用），窄判据不收 ⇒ 少报；
     * - 自增 / 自减：引擎本来就不折（`OperatorMethodManager.cs:605-647` `CanRestructure = false`）；
     * - 方法调用（`VARSIZE(…)` / `UNICODE(…)` 等）：`FunctionMethodTerm.CanRestructure` 因
     *   方法而异，erbcc 审计无内建方法表 ⇒ dynamic（4.980 语料实有 `#DEFINE DEF_CULC_RELATION_NULL
     *   VARSIZE("TALENT") + 1`、`DEF_LIST_MARKHEART UNICODE(0x2665)`，均维持 dynamic）；
     * - 混型算术（`"A" + 1`）、除零 / 模零、负数或超万倍的串重复：引擎在装载 Restructure 相位
     *   抛 CodeEE（`OperatorMethodManager.cs:97-104` `:132-135` `:151-155`；`CanNotAppliedBinaryOp`
     *   `:847`）⇒ 该文件装不出来，审计无从静态判定 ⇒ null。
     */
    private fun macroBodyValue(graph: CorpusGraph, fn: FuncBlock, body: String, depth: Int): String? {
        val b = body.trim()
        if (b.isEmpty()) return null
        intLiteralText(b)?.let { return it }
        if (b.length >= 2 && b.first() == '"' && b.last() == '"' &&
            !b.substring(1, b.length - 1).contains('"')
        ) {
            return b.substring(1, b.length - 1)
        }
        if (isIdentifier(b)) return constOrMacroValueOf(graph, fn, b, depth + 1)
        return constExprText(graph, fn, b, depth)
    }

    /**
     * FORMS 模板嵌入项的常量值（可代入文本）；null = 不可求值。
     *
     * 作用域序 = 当前 `@函数` 定义的私有 `#DIM` 表 → 函数外的全局表
     * （spec: IdentifierDictionary.cs:429-447 —— 私有表命中即返回，不再看全局）。
     * 私有声明存在但**不是**可求值常量时返回 null：同名全局常量被遮蔽，不越过遮蔽取值。
     * 值来自 `#DIM CONST` 的单个字面量初值，或经 [resolveConstChains] 解析成字面值的
     * 传递引用初值（MODERN-R13-R6D-①）。`#DEFINE` 宏在**另一张表**
     * （[CorpusGraph.macros]，编译侧 ERH 解析器收集），展开判定见 [constOrMacroValueOf]。
     */
    private fun constValueOf(graph: CorpusGraph, fn: FuncBlock, name: String): String? =
        constDeclOf(graph, fn, name)?.constValue

    /** [constValueOf] 的声明读数版：私有表命中即返回（含不可求值的），否则看全局表。 */
    private fun constDeclOf(graph: CorpusGraph, fn: FuncBlock, name: String): VarDecl? {
        val upper = name.uppercase(Locale.ROOT)
        fn.privateDecls[upper]?.let { return it }
        return graph.globalDecls[upper]
    }

    // ------------------------------------------------------------------
    // 宏体常量表达式的窄折叠（MODERN-R13-R6D-②）
    // ------------------------------------------------------------------

    /**
     * 常量表达式的带类型值：引擎 `SingleLongTerm` / `SingleStrTerm` 的镜像。
     *
     * 分型是**等价性**的最低要求：串 `"5"` 与整数 `5` 在嵌入位都代出文本 `5`，但在表达式里
     * `"5" + 1` 引擎是装载报错（`CanNotAppliedBinaryOp`，`OperatorMethodManager.cs:847`），
     * 不分型就会把报错形态算成 `6` 或 `"51"` —— 那是给编不出来的语料补边。
     */
    private sealed class ConstVal {
        /** 整数（引擎 `SingleLongTerm.Int`，C# `long` = Kotlin `Long`，同为 64 位补码）。 */
        class IntVal(val value: Long) : ConstVal()

        /** 字符串（引擎 `SingleStrTerm.Str`）。 */
        class StrVal(val value: String) : ConstVal()
    }

    /** 表达式 token（[constExprTokens] 的产物，窄判据算子集只有五则 + 括号）。 */
    private sealed class ConstToken {
        class IntLit(val value: Long) : ConstToken()

        class StrLit(val value: String) : ConstToken()

        class Ident(val name: String) : ConstToken()

        object LParen : ConstToken()

        object RParen : ConstToken()

        class Op(val symbol: String) : ConstToken()
    }

    /** 表达式求出值的可代入文本：整数折十进制（引擎 `FormatCurlyBrace` 的 `ToString()` 同形）。 */
    private fun constExprText(graph: CorpusGraph, fn: FuncBlock, body: String, depth: Int): String? =
        when (val v = constExprTyped(graph, fn, body, depth)) {
            is ConstVal.IntVal -> v.value.toString()
            is ConstVal.StrVal -> v.value
            null -> null
        }

    /**
     * 宏体整串 → 常量表达式求值（窄判据，见 [macroBodyValue] KDoc 的 C# 锚与不收面）。
     *
     * 递归深度 = [MACRO_EXPANSION_LIMIT]（契约口径照 R6D 深度闸 100）：递归只经「宏名 /
     * 常量名操作数」进入（[identValueTyped]），与宏展开链同一条深度轴；括号嵌套的引擎侧
     * 只受解析栈约束、无显式上限，审计同样以该闸兜底（超深 ⇒ null，少报）。
     */
    private fun constExprTyped(graph: CorpusGraph, fn: FuncBlock, body: String, depth: Int): ConstVal? {
        if (depth >= MACRO_EXPANSION_LIMIT) return null
        val tokens = constExprTokens(body) ?: return null
        if (tokens.isEmpty()) return null
        val parser = ConstExprParser(tokens, graph, fn, depth)
        val value = parser.parseExpr() ?: return null
        return if (parser.atEnd()) value else null
    }

    /**
     * 操作数标识符的带类型取值：先宏表（遮蔽口径与 [constOrMacroValueOf] 同一条：同名
     * `#DIM` 声明存在 ⇒ 被遮蔽 ⇒ null，不替坏语料挑边），再 `#DIM CONST`（[constDeclOf]，
     * 值经 [resolveConstChains] 已折成字面文本；类型按 [VarDecl.isStr] 分型）。
     */
    private fun identValueTyped(graph: CorpusGraph, fn: FuncBlock, name: String, depth: Int): ConstVal? {
        if (depth >= MACRO_EXPANSION_LIMIT) return null
        val upper = name.uppercase(Locale.ROOT)
        val macroBody = graph.macros[upper]
        if (macroBody != null) {
            if (upper in fn.privateDecls || upper in graph.globalDecls) return null
            return constExprTyped(graph, fn, macroBody, depth + 1)
        }
        val decl = constDeclOf(graph, fn, name) ?: return null
        val value = decl.constValue ?: return null
        return if (decl.isStr) {
            ConstVal.StrVal(value)
        } else {
            ConstVal.IntVal(value.toLongOrNull() ?: return null)
        }
    }

    /**
     * 表达式 token 流；任何窄判据外的字符（下标 `:`、比较符、方法调用、`%` 嵌入等）⇒ null。
     *
     * 整数字面量只出**无符号** token（正负号交给一元算子 —— 与引擎 `ExpressionParser` 的
     * 词法同构：`-2` 是 UnaryOp(Minus) 套 SingleLongTerm(2)，折叠后同值）；`0x` 十六进制
     * 折进 token 值。串字面量无转义（与 [quotedLiteral] 同一约定：下一个引号即结束）。
     */
    private fun constExprTokens(text: String): List<ConstToken>? {
        val tokens = ArrayList<ConstToken>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' -> i++
                c == '(' -> { tokens.add(ConstToken.LParen); i++ }
                c == ')' -> { tokens.add(ConstToken.RParen); i++ }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '%' -> {
                    tokens.add(ConstToken.Op(c.toString())); i++
                }
                c == '"' -> {
                    val end = text.indexOf('"', i + 1)
                    if (end < 0) return null
                    tokens.add(ConstToken.StrLit(text.substring(i + 1, end)))
                    i = end + 1
                }
                c.isDigit() -> {
                    val start = i
                    while (i < text.length && text[i].isDigit()) i++
                    val digits = text.substring(start, i)
                    val value = if (digits == "0" && i < text.length &&
                        (text[i] == 'x' || text[i] == 'X')
                    ) {
                        val hexStart = i + 1
                        i = hexStart
                        while (i < text.length && (text[i].isDigit() || text[i] in 'a'..'f' || text[i] in 'A'..'F')) i++
                        if (i == hexStart) return null
                        text.substring(hexStart, i).toLongOrNull(16) ?: return null
                    } else {
                        digits.toLongOrNull() ?: return null
                    }
                    tokens.add(ConstToken.IntLit(value))
                }
                c.isLetterOrDigit() || c == '_' -> {
                    val end = identEnd(text, i)
                    if (end == i) return null
                    tokens.add(ConstToken.Ident(text.substring(i, end)))
                    i = end
                }
                else -> return null
            }
        }
        return tokens
    }

    /**
     * 递归下降求值器（窄判据算子集，优先级与结合性镜像引擎 `ExpressionParser` 的
     * `TermStack`：乘除模 > 加减（`OperatorCode.cs:11-15` 取 `& 0xFF`：`+`/`-` = 128、
     * `*`/`/`/`%` = 144），同级左结合（`ExpressionParser.cs:86-87` 的
     * `lastPriority() >= priority` 即先约）；一元正负对这组算子的**值**等价于最紧绑定
     * —— 截断除法与取余对取负可分配（`(-a)/b == -(a/b)`、`(-a)%b == -(a%b)`），故取
     * 「一元最紧」的标准下降不产生与引擎不同的值。
     *
     * 溢出语义：C# 默认 unchecked 环绕 = Kotlin `Long` 环绕，两侧同值；除零 / 模零在引擎
     * 是装载报错（`OperatorMethodManager.cs:132-135`/`:151-155`）⇒ null。
     */
    private class ConstExprParser(
        private val tokens: List<ConstToken>,
        private val graph: CorpusGraph,
        private val fn: FuncBlock,
        private val depth: Int,
    ) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= tokens.size

        /** `expr := term (('+' | '-') term)*` —— 加减同级左结合。 */
        fun parseExpr(): ConstVal? {
            var left = parseTerm() ?: return null
            while (!atEnd() && tokens[pos] is ConstToken.Op) {
                val sym = (tokens[pos] as ConstToken.Op).symbol
                if (sym != "+" && sym != "-") break
                pos++

                val right = parseTerm() ?: return null
                left = when {
                    left is ConstVal.IntVal && right is ConstVal.IntVal ->
                        ConstVal.IntVal(if (sym == "+") left.value + right.value else left.value - right.value)
                    left is ConstVal.StrVal && right is ConstVal.StrVal ->
                        // 引擎 PlusStrStr（OperatorMethodManager.cs:28-45）：串加只收串+串
                        ConstVal.StrVal(left.value + right.value)
                    else -> return null
                }
            }
            return left
        }


        /** `term := unary (('*' | '/' | '%') unary)*`。 */
        private fun parseTerm(): ConstVal? {
            var left = parseUnary() ?: return null
            while (!atEnd() && tokens[pos] is ConstToken.Op) {
                val sym = (tokens[pos] as ConstToken.Op).symbol
                if (sym != "*" && sym != "/" && sym != "%") break
                pos++
                val right = parseUnary() ?: return null
                left = applyMultiplicative(sym, left, right) ?: return null
            }
            return left
        }

        /** `unary := ('+' | '-') unary | primary`。 */
        private fun parseUnary(): ConstVal? {
            if (!atEnd() && tokens[pos] is ConstToken.Op) {
                val sym = (tokens[pos] as ConstToken.Op).symbol
                if (sym == "+" || sym == "-") {
                    pos++
                    val operand = parseUnary() ?: return null
                    // 引擎 PlusInt（:537-549）恒等；MinusInt（:551-567）只收整数、负号取反
                    if (operand !is ConstVal.IntVal) return null
                    return if (sym == "-") ConstVal.IntVal(-operand.value) else operand
                }
            }
            return parsePrimary()
        }

        private fun parsePrimary(): ConstVal? {
            if (atEnd()) return null
            return when (val token = tokens[pos]) {
                is ConstToken.IntLit -> { pos++; ConstVal.IntVal(token.value) }
                is ConstToken.StrLit -> { pos++; ConstVal.StrVal(token.value) }
                is ConstToken.Ident -> {
                    pos++
                    identValueTyped(graph, fn, token.name, depth + 1)
                }
                is ConstToken.LParen -> {
                    pos++
                    val inner = parseExpr() ?: return null
                    if (atEnd() || tokens[pos] !is ConstToken.RParen) return null
                    pos++
                    inner
                }
                is ConstToken.RParen, is ConstToken.Op -> null
            }
        }

        /** 乘除模与串重复（引擎 MultIntInt / DivIntInt / ModIntInt / MultStrInt）。 */
        private fun applyMultiplicative(sym: String, left: ConstVal, right: ConstVal): ConstVal? {
            if (left is ConstVal.IntVal && right is ConstVal.IntVal) {
                return when (sym) {
                    "*" -> ConstVal.IntVal(left.value * right.value)
                    "/" -> if (right.value == 0L) null else ConstVal.IntVal(left.value / right.value)
                    else -> if (right.value == 0L) null else ConstVal.IntVal(left.value % right.value)
                }
            }
            if (sym == "*") {
                // 引擎 MultStrInt（:75-118）：串*整数与整数*串双序同收；负数 / 超万倍装载报错
                val (str, count) = when {
                    left is ConstVal.StrVal && right is ConstVal.IntVal -> left.value to right.value
                    left is ConstVal.IntVal && right is ConstVal.StrVal -> right.value to left.value
                    else -> return null
                }
                if (count < 0 || count >= 10000) return null
                if (str.isEmpty() || count == 0L) return ConstVal.StrVal("")
                return ConstVal.StrVal(str.repeat(count.toInt()))
            }
            return null
        }
    }

    private fun isIdentifier(text: String): Boolean =
        text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '_' }

    /** 标识符字符数（语句名、函数名、变量名的**词边界**）：字母（含日文）/ 数字 / `_`。 */
    private fun identEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c.isLetterOrDigit() || c == '_') i++ else break
        }
        return i
    }

    /** 语句名（行首 token，大写）；行首不是标识符时返回空串。 */
    private fun statementOf(text: String): String =
        text.substring(0, identEnd(text, 0)).uppercase(Locale.ROOT)

    /** 标签解析：先本函数，再本文件（同名标签按先到者胜，契约 §3.2.3）。 */
    private fun resolveLabel(graph: CorpusGraph, fn: FuncBlock, labelUpper: String): FuncBlock? {
        if (labelUpper in fn.labels) return fn
        return graph.labelOwner[fn.file]?.get(labelUpper)
    }

    // ------------------------------------------------------------------
    // 可移植性分级
    // ------------------------------------------------------------------

    /**
     * 可移植性令牌（specs 权威 builtin-functions.yaml 的条目行，实现锚在反编译源）。
     *
     * - `CLIENTWIDTH`（yaml:1448）/ `CLIENTHEIGHT`（yaml:1456）：`ClientSizeMethod`，
     *   `FunctionMethodCreator.cs:6777-6800` —— 返回 PC 主窗口客户区宽高；
     * - `GETFONT`（yaml:1384）：`FunctionMethodCreator.cs:3732-3746` —— 返回当前字体名；
     * - `GGETFONT`（yaml:1536）/ `GGETFONTSIZE`（:1544）/ `GGETFONTSTYLE`（:1552）：
     *   `FunctionMethodCreator.cs:6197-6224` / `:6162-6196` —— 读图形缓冲区的字体设置；
     * - `GDRAWTEXT`（yaml:1696）：`FunctionMethodCreator.cs:6475-6531` —— 用当前字体往
     *   图形缓冲区写字（像素级文字度量）。
     *
     * 判据是「词边界命中」（大小写不敏感）：`GETFONT` 不会误命中 `GGETFONT`。
     */
    private val PORTABILITY_TOKENS: List<String> = listOf(
        "CLIENTWIDTH", "CLIENTHEIGHT", "GETFONT",
        "GGETFONT", "GGETFONTSIZE", "GGETFONTSTYLE", "GDRAWTEXT",
    )

    /**
     * `GETCONFIG` / `GETCONFIGS` 的**显示类键名**白名单。
     *
     * 契约只允许纳入「键名能在反编译源里找到原文」的键。上表逐条对过
     * `ConfigData.cs:55-67` 的条目原文（日文名 / 英文名两档都是官方原文）：
     * `描画インターフェース`/`Drawing interface`、`ウィンドウ幅`/`Window width`、
     * `ウィンドウ高さ`/`Window height`、`PRINTCを並べる数`/`Items per line for PRINTC`、
     * `PRINTCの文字数`/`Number of Item characters for PRINTC`、`フォント名`/`Font name`、
     * `フォントサイズ`/`Font size`、`一行の高さ`/`Line height`；派发口径见
     * `ConfigData.cs:350-425`（`GetConfigValueInERB` 的 `case ConfigCode.*` 分组）。
     *
     * 其余键（`FPS` / `TEXT COLOR` 一类）**不**纳入：前者的键名是 `フレーム毎秒` /
     * `FPS`（`ConfigData.cs:75`，与帧率有关、不是几何/字体），后者在本版没有对应条目原文
     * （语料里 `GETCONFIG("TEXT COLOR")` 其实是运行期 `InvalidConfigName` 错误）。
     */
    private val GETCONFIG_DISPLAY_KEYS: Set<String> = setOf(
        "描画インターフェース", "Drawing interface",
        "ウィンドウ幅", "Window width",
        "ウィンドウ高さ", "Window height",
        "PRINTCを並べる数", "Items per line for PRINTC",
        "PRINTCの文字数", "Number of Item characters for PRINTC",
        "フォント名", "Font name",
        "フォントサイズ", "Font size",
        "一行の高さ", "Line height",
    )

    /**
     * 一行里命中的第一个可移植性令牌（固定顺序 ⇒ 结果确定）。无命中返回 null。
     */
    private fun portabilityToken(text: String): String? {
        for (token in PORTABILITY_TOKENS) {
            val hit = indexOfWord(text, token)
            if (hit >= 0) return token
        }
        // GETCONFIG("<显示类键>") / GETCONFIGS("<显示类键>")
        for (fnName in listOf("GETCONFIG", "GETCONFIGS")) {
            var from = 0
            while (true) {
                val at = indexOfWord(text, fnName, from)
                if (at < 0) break
                from = at + fnName.length
                val open = text.indexOf('(', from)
                if (open < 0) continue
                val literal = quotedLiteral(text.substring(open + 1).trimStart()) ?: continue
                if (literal in GETCONFIG_DISPLAY_KEYS) return "$fnName($literal)"
            }
        }
        return null
    }

    /** 词边界查找（大小写不敏感）：命中的 `from` 之后第一个下标，未命中 -1。 */
    private fun indexOfWord(text: String, word: String, from: Int = 0): Int {
        var i = from
        while (true) {
            val at = text.indexOf(word, i, ignoreCase = true)
            if (at < 0) return -1
            val before = if (at == 0) ' ' else text[at - 1]
            val afterIdx = at + word.length
            val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
            if (!isIdentChar(before) && !isIdentChar(after)) return at
            i = at + 1
        }
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * 给一条命中令牌的行定档。
     *
     * 判据按契约 §3.3：
     * - 该行是赋值语句，且**任一**赋值目标声明的修饰符带 `SAVEDATA` 或 `GLOBAL` ⇒ [PortabilityGrade.PERSIST]；
     * - 该行没有赋值（打印/绘图/格式化/几何）⇒ [PortabilityGrade.DISPLAY]；
     * - 赋值目标解析不出声明（宏名、`%` 目标、未知名）⇒ [PortabilityGrade.UNKNOWN]（照实输出，不降成 display）。
     */
    private fun gradeLine(resolver: DeclResolver, fn: FuncBlock, text: String): PortabilityGrade {
        val targets = assignmentTargets(text)
        if (targets.isEmpty()) return PortabilityGrade.DISPLAY
        var unknown = false
        for (name in targets) {
            when (resolver.classify(fn, name)) {
                TargetClass.PERSIST -> return PortabilityGrade.PERSIST
                TargetClass.UNKNOWN -> unknown = true
                TargetClass.PLAIN -> Unit
            }
        }
        return if (unknown) PortabilityGrade.UNKNOWN else PortabilityGrade.DISPLAY
    }

    /**
     * 一行里的赋值目标名（未经解析）。空列表 = 该行不是赋值语句；
     * 列表里的 null = 目标不可静态解析（`%` 目标、表达式、字面量）。
     */
    private fun assignmentTargets(text: String): List<String?> {
        val stmtEnd = identEnd(text, 0)
        val stmt = text.substring(0, stmtEnd).uppercase(Locale.ROOT)
        if (stmt == "SETVAR" || stmt == "SETVARS" || stmt == "SETVAREX" || stmt == "SETVARSX") {
            val first = text.substring(stmtEnd).trimStart().substringBefore(',').trim()
            return listOf(leadingIdentifier(first))
        }
        if (stmt == "VARSET" || stmt == "VARSETS") {
            val first = text.substring(stmtEnd).trimStart().substringBefore(',').trim()
            return listOf(leadingIdentifier(first))
        }
        val op = findAssignmentOperator(text)
        if (op < 0) return emptyList()
        val lhs = text.substring(0, op).trimEnd()
        if (lhs.isEmpty()) return listOf(null)
        return listOf(leadingIdentifier(lhs.trimStart()))
    }

    /** 形如 `BUF:0` / `BUF` / `BUF[0]` 的左侧取变量名；以 `%` / `(` / `"` 开头 ⇒ null。 */
    private fun leadingIdentifier(text: String): String? {
        if (text.isEmpty()) return null
        if (text[0] == '%' || text[0] == '(' || text[0] == '"' || text[0] == '{') return null
        val end = identEnd(text, 0)
        if (end == 0) return null
        return text.substring(0, end)
    }

    /**
     * 顶层（不在字符串内）的赋值运算符下标：`=` 与复合赋值 `+=` `-=` `*=` `/=` `%=` `'=`。
     *
     * 比较运算符（`==` / `!=` / `<=` / `>=`）不算赋值：`==` 整体跳过，`!=`/`<=`/`>=` 的 `=`
     * 因 next 不是 `=` 且 prev 不在复合集里 —— 这里按 prev 判：`!`/`<`/`>` 不在复合集 ⇒ 命中。
     * 为此 `!`/`<`/`>` 也必须显式排除，否则 `IF X >= 1` 会被当成赋值。
     */
    private fun findAssignmentOperator(text: String): Int {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                inString = !inString
                i++
                continue
            }
            if (!inString && c == '=') {
                val next = if (i + 1 < text.length) text[i + 1] else ' '
                if (next == '=') {
                    i += 2
                    continue
                }
                val prev = if (i > 0) text[i - 1] else ' '
                if (prev == '!') { i++; continue }
                // 复合赋值（`+=` / `-=` / `*=` / `/=` / `%=` / `'=`）的算子位置
                if (prev == '+' || prev == '-' || prev == '*' || prev == '/' || prev == '%' || prev == '\'') {
                    return i - 1
                }
                // `<` / `>` / `!` 开头的比较符（单字符形态不会出现，这里只是兜底）
                if (prev == '<' || prev == '>') { i++; continue }
                return i
            }
            i++
        }
        return -1
    }

    private fun snippet(text: String): String {
        val flat = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()
        return if (flat.length <= DETAIL_MAX) flat else flat.take(DETAIL_MAX - 1) + "…"
    }

    private const val DETAIL_MAX = 160
}

/** 一条报告行的类别（TSV 的 `kind` 列）。 */
enum class ReachabilityKind(val label: String) {
    /** 可达函数（`detail` = `depth=<n> root=<name>`）。 */
    REACHABLE("reachable"),

    /** 静态不可判定的调用目标（`detail` = `<语句名> <原文>`）。 */
    DYNAMIC_CALL("dynamic-call"),

    /** 可移植性诊断（`grade` 三档）。 */
    PORTABILITY("portability"),
}

/**
 * 可移植性分级（TSV 的 `grade` 列）。
 *
 * - [PERSIST]：命中令牌且该行写持久变量（声明带 `SAVEDATA` 或 `GLOBAL`）—— 存档格式面；
 * - [DISPLAY]：命中令牌但不写持久变量 —— 展示适配面（打印/绘图/格式化/几何）；
 * - [UNKNOWN]：赋值目标解析不出声明（宏 / `%` 目标 / 未知名）—— 照实输出，不默认成 display。
 */
enum class PortabilityGrade(val label: String) {
    PERSIST("persist"),
    DISPLAY("display"),
    UNKNOWN("unknown"),
}

/** 一条报告行（TSV 一行；[grade] 对 `reachable` / `dynamic-call` 恒为 `-`）。 */
class ReachabilityRow(
    val kind: ReachabilityKind,
    val grade: String,
    val function: String,
    val file: String,
    val line: Int,
    val detail: String,
)

/**
 * 一次可达性 / 可移植性扫描的结果。
 *
 * [rows] 已按 `(kind, grade, file, line, function, detail)` 排序；[file] 是语料根相对路径
 * （`/` 分隔），输出不含绝对路径 / 时间戳 / 主机名 / 耗时 ⇒ 同一语料两次运行逐字节相同。
 */
class ReachabilityReport(
    /** 根函数个数（事件名 ∪ 系统名 ∪ 显式追加，按定义计）。 */
    val roots: Int,
    /** 可达函数个数（含根）；`roots` 为 0 时它也是 0。 */
    val reachable: Int,
    val rows: List<ReachabilityRow>,
) {
    val dynamicCalls: Int get() = rows.count { it.kind == ReachabilityKind.DYNAMIC_CALL }

    val portability: Int get() = rows.count { it.kind == ReachabilityKind.PORTABILITY }

    val persist: Int get() = rows.count {
        it.kind == ReachabilityKind.PORTABILITY && it.grade == PortabilityGrade.PERSIST.label
    }

    /** CLI 的 summary 行（`--reachability` 时才打印）。 */
    fun summaryLine(): String =
        "reachability: roots=$roots reachable=$reachable dynamic-calls=$dynamicCalls " +
            "portability=$portability persist=$persist"

    /**
     * TSV 正文（`kind/grade/function/file/line/detail`，首行表头，LF 行尾）。
     *
     * 这是**唯一**的正文构造：[writeTsv] 落盘与基线 sha256（[ReachabilityExpect.sha256Hex]）
     * 都取这一份，两侧分叉会让「写盘字节」与「基线指纹」各说各话（MODERN-R8-B）。
     */
    fun tsvText(): String {
        val sb = StringBuilder("kind\tgrade\tfunction\tfile\tline\tdetail\n")
        for (r in rows) {
            sb.append(r.kind.label).append('\t')
                .append(r.grade).append('\t')
                .append(clean(r.function)).append('\t')
                .append(clean(r.file)).append('\t')
                .append(r.line).append('\t')
                .append(clean(r.detail)).append('\n')
        }
        return sb.toString()
    }

    /** 写 TSV（格式同 [tsvText]），父目录不存在则创建。 */
    fun writeTsv(out: Path) {
        out.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
        Files.writeString(out, tsvText())
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

// ---------------------------------------------------------------------------
// 扫描内部模型
// ---------------------------------------------------------------------------

/**
 * 一个 `@函数` 定义块。
 *
 * [key] = `文件:声明行` 作为定义身份：同名多定义（事件桶的 4 个成员、跨文件重名）各自成节点，
 * 与原版「每个 FunctionLabelLine 各持一份私有作用域」一致（`FunctionLabelLine.cs:15`、`:98-117`）。
 */
private class FuncBlock(val nameUpper: String, val file: String, val line: Int) {
    val key: String = "$file:$line"

    /** 本函数体内的代码逻辑行（`#` / `$` / `@` 行不在内）。 */
    val code = ArrayList<LogicalLine>()

    /** 本函数体内的 `$标签`（大写），供 `JUMP` / `GOTO` 解析。 */
    val labels = HashSet<String>()

    /** 本函数体内的 `#DIM` 声明：变量名（大写）→ 声明读数（是否持久化、常量初值）。 */
    val privateDecls = HashMap<String, VarDecl>()
}

private class CorpusGraph {
    val functions = ArrayList<FuncBlock>()

    /** 函数名（大写）→ 全部定义（装载序）。 */
    val byName = HashMap<String, MutableList<FuncBlock>>()

    /** 文件 → `$标签`（大写）→ 首个声明它的函数（同文件回退用）。 */
    val labelOwner = HashMap<String, HashMap<String, FuncBlock>>()

    /**
     * 全局用户变量声明表（R13-SUPER 起**仅 ERH**）：变量名（大写）→ 声明读数（是否持久化、
     * 常量初值）。
     *
     * 引擎合法性口径（MODERN-R13-TAIL 勘察、R13-SUPER 剔除超集）：官方引擎里全局用户变量
     * 的唯一注册口是 ERH —— `ErhLoader.cs:42-67` 把全部 ERH 的 `#DIM` 行收进 dimlines
     * 队列、`:249-268` analyzeSharpDimLines 逐条 `Create` + `idDic.AddUseDefinedVariable`
     * （`:268`）。ERB 文件里的 `#` 行必须**紧随**上一条经 `addLine`（`ErbLoader.cs:529-538`）
     * 加入的行是 FunctionLabelLine 才交给 `ParseSharpLine`（`LogicalLineParser.cs:20`；ERB 侧
     * 注册点 `:279-291` 的 `Create(isPrivate: true)` + `label.AddPrivateVariable`）；
     * 否则 `InvalidSharp` 警告（`ErbLoader.cs:415-417`、文案 `Lang.cs:2430`
     * 「関数宣言の直後以外で#行が使われています」，icon=1 非致命）后 `:423` continue
     * 整行丢弃 —— 不经解析、不注册、`noError` 不置 false。文件开头尚无任何 `@` 行时
     * `logicalLine2` 是 `NullLine`（`ErbLoader.cs:378`）⇒ ERB 顶层 `#DIM` 一律
     * InvalidSharp + 整行忽略 ⇒ 官方引擎不存在「ERB 函数外全局声明」这一状态，
     * 本表不再收录 ERB 侧声明（此前把 ERB 函数外 `#DIM` 一并收入 isErh=false 是
     * 审计侧超集镜像，R13-SUPER 剔除；剔除前口径见 git 历史）。
     * 「紧随」的占位口径：`;` 注释被 `ReadEnabledLine` 的空白规约整行吃掉
     * （`EraStreamReader.cs:124-129` → `LexicalAnalyzer.cs:647-676` 分号即吞到行尾）、
     * 连续 `#` 行（`#` 分支 `:423` continue 不经 addLine）与方括号预处理行
     * （`ErbLoader.cs:390-406`，无 continue、fall-through 到 `:507-508` 以**陈旧上一行**
     * 重走 addLine，不改变 logicalLine2 的 FunctionLabelLine 性）都不顶掉上一条行；
     * `{}` 续行块归约成单逻辑行（`EraStreamReader.cs:136-183`）后按块首字符分派；
     * `[SKIPSTART]` 区内的行在分派前就被跳过（`ErbLoader.cs:409-412`），连警告都没有。
     * 4.980 语料实测（R13-SUPER 勘察）：3818 个 ERB 文件里「首个 `@` 行之前 / 空名
     * `@` 行之后」的 `#DIM` 声明 **0 例** —— 本表剔除 ERB 侧对语料零漂移。
     * `魔改内容/DokuToolLib/DOKU_DCCS.ERB:69` 的 `#DIMS COMF_PRENAME`（此前误记为
     * 本表收录例）在 `:9` `@USE_DCCS(...)` 之后，其间只有注释与 `[SKIPSTART]`/`[SKIPEND]`
     * 注释块 ⇒ 引擎侧上一条 addLine 行仍是该 FunctionLabelLine，合法注册为该函数私有；
     * 审计同判（落 `FuncBlock.privateDecls`），与本表无关。
     */
    val globalDecls = HashMap<String, VarDecl>()

    /**
     * `#DEFINE` 宏表：宏名（大写归一）→ 展开体原文（顶层注释已剥、参数化宏与重名已被
     * 编译侧解析器拒收）。来源 = [LabelTable.collectErhSymbols]（MODERN-R12-R6D 复用既有
     * ERH 解析器，不新造解析器），见 [buildGraph] 的 ERH 预扫。
     */
    val macros = HashMap<String, String>()
}

/**
 * 一条 `#DIM` 声明在审计侧的读数。
 *
 * [persisted] = 修饰符带 `GLOBAL`/`SAVEDATA`（存档面，契约 R6 §3.3）；
 * [isStr] = `#DIMS`（字符串型；`#DIM` / `#DIMF` 都是整数型，引擎只按「是否 DIMS」分型，
 * `UserDefinedVariableData.cs:410-413`）；
 * [seq] / [isErh] = 声明序与来源文件（传递解析的可见性轴，见 [refVisible]）；
 * [refName] = 初值为**单个裸标识符**时的引用名（大写；传递折叠的入口，MODERN-R13-R6D-①）；
 * [constValue] = `CONST` 标量初值的**可代入文本**（整数按十进制、`#DIMS` 按原串），
 * null = 不是可求值常量（非常量 / 初值不是单个字面量 / 同名重复声明值不一致 /
 * 传递链断 / 链目标不可见），带 [refName] 的声明在 [resolveConstChains] 里填终值。
 */
private class VarDecl(
    val persisted: Boolean,
    val isStr: Boolean,
    val refName: String?,
    var constValue: String?,
    val seq: Int,
    val isErh: Boolean,
) {
    /**
     * 本声明到字面量叶子的**全链长**（ref 边数；字面量 / 无引用 / 断链 = 0）。
     * 深度闸按它卡（[resolveConstChains]）：目标可能早已按更短的走链折好，只数本次
     * 走链跳数会把长链漏过闸 —— 全链长与外层遍历序无关，保证同一语料同一判定。
     */
    var chainHops: Int = 0
}

private class DimDecl(
    val name: String,
    val persisted: Boolean,
    val isStr: Boolean,
    val constValue: String?,
    val refName: String?,
)

/** `"…"` 字面量（不处理转义：ERB 串里没有反斜杠转义，双引号即结束）。 */
private fun quotedLiteral(text: String): String? {
    if (text.isEmpty() || text[0] != '"') return null
    val end = text.indexOf('"', 1)
    if (end < 0) return null
    return text.substring(1, end)
}

/** 行首 `@` 后到 `(` / `,` / `;` / 空白之前的函数名（原样，未大写）。`ErbLoader.cs:388-427`。 */
private fun labelNameOf(content: String): String =
    content.removePrefix("@").takeWhile { it != '(' && it != ',' && it != ';' && !it.isWhitespace() }

/** 行首 `$` 后的标签名（原样，未大写）。 */
private fun gotoLabelNameOf(content: String): String =
    content.removePrefix("$").takeWhile { it != ';' && !it.isWhitespace() }

/** `#DIM` 的修饰符表（与 `CorpusScan.MODIFIERS` / `UserDefinedVariableData.cs:54-264` 同口径）。 */
private val DIM_MODIFIERS: List<String> =
    listOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")

/**
 * 解析一条 `#DIM` / `#DIMS` / `#DIMF` 声明（`text` 含前导 `#`），返回名字、「是否持久化」、
 * 「是否字符串型」、「`CONST` 标量初值的可代入文本」与「单个标识符初值的引用名」。
 *
 * 持久化判据 = 修饰符里带 `GLOBAL` 或 `SAVEDATA`（契约 §3.3；位定义见
 * `UserDefinedVariableData.cs:54-264` 与 `VariableData.cs:3961-3997` 的存档筛选）。
 * 修饰符按**词级**判定（忽略大小写、忽略词界之后的字母数字）——与 `CorpusScan` 的 A2 修复
 * 同口径：旧实现 `startsWith` 会把 `#DIMS CONST CHARADATA_TYPES` 的变量名误当 `CONST`。
 *
 * 常量初值（本波 R6-C 基础上 R6D2-① 扩传递引用）：认**单个字面量**——`#DIM`/`#DIMF` 的整数
 * （十进制或 `0x` 十六进制，可带一元正负号）与 `#DIMS` 的字符串（`"…"` 之后只许行尾注释），
 * 或**单个裸标识符**（引用更早声明的 `CONST`，终值由 [resolveConstChains] 按声明序解析）。
 * 拒绝面：初值表（逗号 ⇒ 数组常量）、带尺寸的数组声明（`#DIM CONST X, 20 = …`）、表达式。
 * 引擎依据：初值式在**声明解析相位**就被 Restructure 折成 `SingleTerm` 存值
 * （`UserDefinedVariableData.cs:405-421`，非 SingleTerm 直接 `InitialValueOnlyConst` 报错；
 * `CONST` 必须有初值 `:294-298`/`:346-348`，且不得带尺寸 `:438-441` 只禁多维）；CONST 变量
 * 引用可折（`UserDefinedVariableToken.cs:35` `CanRestructure = isConst` + `VariableTerm.cs:337-339`
 * 折 `GetValue`），装载期读值经 `IfNullInitArray` 惰性落到声明初值（`VariableData.cs:1311-1338`）。
 * **表达式初值维持不折**（R6D2-① 契约窄口径：引擎虽同样在声明相位折算术
 * （`FunctionMethodTerm.cs:35-58` + `OperatorMethodManager.cs` 的 `CanRestructure = true`），
 * 审计只镜像「引用更早 CONST」这一窄面 —— 表达式形态落 dynamic，少报不猜）。
 *
 * `#DEFINE` 宏不进**这张** `#DIM` 表（宏与变量是两张表，遮蔽口径见 [constOrMacroValueOf]）：
 * 宏表由 [buildGraph] 的 ERH 预扫用编译侧解析器 [LabelTable.collectErhSymbols] 另行收集
 * （MODERN-R12-R6D）。审计拿到的原文行仍来自 `LogicalLineReader` 的行分类
 * （`core/lexer/LogicalLineReader.kt` 的 `LineKind.SHARP`，不含宏展开 —— 引擎的宏展开是
 * 词法 token 流相位，FORM 文本段与 CALL 目标都不查宏表），`[IF 宏]` 判定依旧用
 * `LogicalLineReader` 的宏名集（`EraStreamReader`/`ErbLoader.cs:94`）。
 */
private fun parseDimDecl(text: String): DimDecl? {
    var s = text.trim()
    if (!s.startsWith("#")) return null
    s = s.substring(1).trim()
    val kw = s.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
    if (kw != "DIM" && kw != "DIMS" && kw != "DIMF") return null
    var head = s.substring(kw.length).trim()
    var persisted = false
    var isConst = false
    var changed = true
    while (changed) {
        changed = false
        for (mod in DIM_MODIFIERS) {
            if (head.length < mod.length) continue
            if (!head.regionMatches(0, mod, 0, mod.length, ignoreCase = true)) continue
            val nextIsIdentChar = head.length > mod.length &&
                (head[mod.length].isLetterOrDigit() || head[mod.length] == '_')
            if (nextIsIdentChar) continue
            head = head.substring(mod.length).trim()
            if (mod == "GLOBAL" || mod == "SAVEDATA") persisted = true
            if (mod == "CONST") isConst = true
            changed = true
        }
    }
    val name = head.takeWhile { it != ',' && it != '=' && it != ';' && !it.isWhitespace() }.trim()
    if (name.isEmpty()) return null
    val tail = head.substring(name.length)
    val constValue = if (isConst) constLiteralValue(kw, tail) else null
    // 传递引用（MODERN-R13-R6D-①）：初值不是上面任何字面量形态、且整段是单个裸标识符
    // ⇒ 记引用名，终值由 [resolveConstChains] 按声明序解析。引擎侧宏引用初值同样可折
    // （`Analyse` 过 expandMacro，LexicalAnalyzer.cs:731），但那会把宏展开面再拉进声明表
    // ——窄判据不收，引用名在宏表里时 [resolvedValueOf] 查不到目标 ⇒ null（少报）。
    val refName = if (isConst && constValue == null) constRefName(tail) else null
    return DimDecl(name, persisted, kw == "DIMS", constValue, refName)
}

/**
 * `#DIM CONST` 初值段 → 单个裸标识符引用名（大写）；其余形态 null。
 *
 * 与 [constLiteralValue] 同一条物理判据：有尺寸段（名字与 `=` 之间出现逗号 ⇒ 数组常量，
 * `UserDefinedVariableData.cs:300-343`）或初值表（`#DIM CONST X = A, B`）不折；行尾注释
 * 剥掉（语料实有 `= DEFAULT_MOVECOST ;注释` 形态，`UserDefinedVariableData.cs:428-431`
 * 之后只要求 EOL）；`@…` 原文串、表达式、下标都不是标识符 ⇒ null。
 */
private fun constRefName(tail: String): String? {
    val eq = tail.indexOf('=')
    if (eq < 0) return null
    if (',' in tail.substring(0, eq)) return null
    val body = tail.substring(eq + 1).substringBefore(';').trim()
    if (body.isEmpty() || ',' in body || !body.all { it.isLetterOrDigit() || it == '_' }) return null
    return body.uppercase(Locale.ROOT)
}

// ---------------------------------------------------------------------------
// CONST 初值的传递解析（MODERN-R13-R6D-①）
// ---------------------------------------------------------------------------

/**
 * 传递链深度闸 = [ReachabilityAudit.MACRO_EXPANSION_LIMIT]（契约口径照 R6D 深度闸 100）。
 * 引擎对 CONST 链本身无递归 —— 每条声明在自己的解析相位对**已折好**的存储取值
 * （`VariableData.cs:1311-1338` 的惰性初始化），链长无引擎上限；真实语料链长 ≤ 1，
 * 闸只是防御面，超深 ⇒ null（少报）。
 */
/**
 * 宏展开深度上限：对齐引擎「1 文 100 次」的展开数上限（C# `LexicalAnalyzer.cs:292-294` /
 * `:959-963` MacroOverLimit，Kotlin 镜像 `LexicalAnalyzer.kt:748-755` 抛 LexError）。
 * 超限行在引擎是**装载报错**（编不出来）⇒ 审计无从静态判定 ⇒ null（保守 dynamic）。
 *
 * R6D2 起它同时是两处深度闸的单一来源：宏体表达式求值（object 内 [macroBodyValue] 一族）
 * 与 CONST 传递链（[CONST_CHAIN_DEPTH_LIMIT]）—— 契约口径照 R6D 的 100。
 */
private const val MACRO_EXPANSION_LIMIT = 100

/** CONST 传递链深度闸（= [MACRO_EXPANSION_LIMIT]，单一来源见其 KDoc）。 */
private const val CONST_CHAIN_DEPTH_LIMIT = MACRO_EXPANSION_LIMIT

/**
 * 引用目标的**声明序可见性**（引擎装载序的镜像，MODERN-R13-R6D-①）：
 * - ERH 侧声明（[VarDecl.isErh]）只可见**更早声明**的 ERH 声明：引擎把全部 ERH 的
 *   `#DIM` 行收进单队列、再按队列序逐条建 token 并注册（`ErhLoader.cs:39-67` 收集、
 *   `:253-268` 逐条 `Create` + `AddUseDefinedVariable`），此刻 ERB 侧一个都未注册
 *   （`Process.cs:309-327`：`LoadHeaderFiles` await 完成后才开 `LoadErbDir`）；
 * - ERB 侧声明（R13-SUPER 起全部是函数内私有；ERB 函数外全局已被剔除，见
 *   [CorpusGraph.globalDecls] KDoc）可见**全部 ERH 声明**（装载序在前），以及同函数内
 *   扫描序更早的私有声明（跨函数 / 跨文件的 ERB→ERB 引用在查找面上就不可达：
 *   `resolveConstChains` 只查本函数私有表与全局表）。下面的文件序分析自 R13-SUPER 起
 *   主要服务 ERH 队列序（ERH→ERH 跨文件链的 `seq` 轴，引擎 = `Config.GetFiles` 序、
 *   审计 = `CorpusCompile.listCorpusFiles` 的字典序镜像，两序万一倒挂时落在保守侧 = 不折）。
 * - 装载序锚（MODERN-R13-TAIL 勘察，R6D2 ② TODO 收账）：上句的「引擎按文件序解析」
 *   不是无条件的字典序 —— `Config.GetFiles`（`Config.cs:644-652`）→ 私有 `getFiles`
 *   （`:654-706`）：`Directory.GetFiles`（`:678`，.NET 不保证返回序）仅当配置
 *   `SortWithFilename` 才 `Array.Sort`（文件 `:679-682`、子目录 `:695-698`；该配置默认
 *   false `ConfigData.cs:85`，属性 `Config.cs:113`、装载 `:343`），每层先文件后子目录
 *   （`:683-704`）；ERB 装载另有井号名子目录预通道（`ErbLoader.cs:214` 无排序的
 *   `Directory.GetDirectories` + `:227`，先于主列表 `:252`，去重 `:256`）；ERH 同走
 *   `Config.GetFiles`（`ErhLoader.cs:42`）。游戏实态：4.980 与 4.981 两套官方语料的
 *   emuera.config 均为 `SEARCH SUBFOLDERS:YES` + `SORT FILENAMES:YES`（两份 `:39`-`:40`），
 *   且 ERB 树任意深度无井号名子目录 ⇒ 实际跑显式排序路径；但 `Array.Sort(string[])`
 *   走 BCL culture 敏感比较器，与审计的 UTF-16 码元全路径字典序
 *   （`CorpusCompile.listCorpusFiles` 的 `sortedBy`，`CorpusCompile.kt:428`）不恒等 ——
 *   实测倒挂对：顶层 `CHARA_LIST.ERB` 引擎先装（顶层先行），审计字典序把
 *   `CHARALIST_DT/` 排在其前（'L' 0x4C < '_' 0x5F）。
 *   保守方向论证（上句「倒挂落保守侧」的补强）：「引擎折、审计不折」= 少报 = 下界
 *   原则内；反方向在引擎可干净装载的语料上不可达 —— 字典序在前、引擎序在后意味着
 *   引用方初值在引擎解析时撞未注册名，声明相位 Restructure 非 SingleTerm 即抛
 *   CodeEE(InitialValueOnlyConst)（`UserDefinedVariableData.cs:405-409`），
 *   `ParseSharpLine` 的 catch 转 Warn + 返回 false（`LogicalLineParser.cs:301-305`）⇒
 *   `noError=false` 文件装载失败（`ErbLoader.cs:419-422`）；同文件对两侧同为行序、
 *   不受装载序影响。⇒ 干净语料上「审计折 ⇒ 引擎必折」，镜像保守性成立。
 *
 * 私有→私有只发生在同一 `@函数` 内（私有表按函数挂 `FunctionLabelLine.cs:98-107`，
 * 表达式解析经 `IdentifierDictionary.cs:429-447` 的 `GetPrivateVariable`），同文件
 * 扫描序 = 行序 ⇒ `seq` 直接可比；同函数后声明的私有在引用时刻**尚未注册**（引擎是
 * 装载报错），审计同样不可见 ⇒ null。
 */
private fun refVisible(context: VarDecl, target: VarDecl): Boolean =
    if (context.isErh) {
        target.isErh && target.seq < context.seq
    } else {
        target.isErh || (!target.isErh && target.seq < context.seq)
    }

/**
 * 一条 [VarDecl.refName] 链的迭代走链求值；断链 / 不可见 / 同型不成立 / 超深 ⇒ null。
 *
 * 每跳做与引擎单声明折叠同判据的校验：
 * - 目标必须是 `CONST`（`UserDefinedVariableToken.cs:35` 的 `CanRestructure = isConst` ——
 *   非 CONST 目标装载期不折，运行期才有值 ⇒ 审计断链）；
 * - 目标与引用方**同型**（`DIM`↔`DIM`、`DIMS`↔`DIMS`；异型是装载报错
 *   `UserDefinedVariableData.cs:410-413` `NotMatchVarTypeAndInitialValue` ⇒ 文件装不出来，
 *   审计不折）；
 * - 目标按声明序可见（[refVisible]），作用域序与 `ReachabilityAudit.constDeclOf` 同一条：
 *   私有表命中即用（哪怕不可见也不越权落到全局 —— 遮蔽口径，R6-C KDoc）；
 * - 终值已折好的目标直接取值（外层遍历序无关：每条声明的走链自含，值只由图决定 ——
 *   与引擎「声明相位折好初值、使用点读存储」同构，`VariableData.cs:1311-1338`）。
 *
 * 可见性使引用图**无环**（每条边要么声明序严格递减、要么从 ERB 侧跳进封闭递减的 ERH
 * 子图），同名自引用被 `===` 与可见性双重挡下；[CONST_CHAIN_DEPTH_LIMIT] 深度闸只作
 * 迭代预算的防御面（契约口径照 R6D 深度闸 100），超深 ⇒ null（少报）。
 */
private fun resolveConstChains(graph: CorpusGraph) {
    val work = ArrayList<Pair<FuncBlock?, VarDecl>>(graph.globalDecls.size)
    for (decl in graph.globalDecls.values) work.add(null to decl)
    for (fn in graph.functions) for (decl in fn.privateDecls.values) work.add(fn to decl)
    for ((owner, decl) in work) {
        if (decl.refName == null || decl.constValue != null) continue
        var hops = 0
        var cur = decl
        var curOwner = owner
        var value: String? = null
        var resolvedHops = -1
        while (true) {
            val ref = cur.refName ?: break
            hops++
            if (hops >= CONST_CHAIN_DEPTH_LIMIT) break
            val target = curOwner?.privateDecls?.get(ref) ?: graph.globalDecls[ref] ?: break
            if (target === cur) break
            if (!refVisible(cur, target)) break
            if (target.isStr != cur.isStr) break
            if (target.constValue != null) {
                // 全链长 = 已走跳数 + 目标自身链长（目标可能早已按更短的走链折好）；
                // 只数本次走链会把长链漏过闸 —— 闸卡全链长（VarDecl.chainHops）。
                val total = hops + target.chainHops
                if (total >= CONST_CHAIN_DEPTH_LIMIT) break
                value = target.constValue
                resolvedHops = total
                break
            }
            if (target.refName == null) break
            cur = target
            curOwner = curOwner?.takeIf { ref in it.privateDecls }
        }
        decl.constValue = value
        decl.chainHops = if (resolvedHops >= 0) resolvedHops else 0
    }
}

/**
 * `#DIM CONST` 初值段 → 可代入 FORMS 模板的文本；任何不能确定成「单个字面量」的形态 ⇒ null。
 *
 * @param kw 声明关键字（`DIM` / `DIMS` / `DIMF`，大写）——决定初值是字符串还是整数
 * @param tail 名字之后的原文（含尺寸段与 `=` 初值段）
 */
private fun constLiteralValue(kw: String, tail: String): String? {
    val eq = tail.indexOf('=')
    if (eq < 0) return null
    // 尺寸段：`#DIM CONST X, 20 = …` 是 1 维数组常量（UserDefinedVariableData.cs:300-343），
    // 裸名取值不成立 ⇒ 不代入。
    if (',' in tail.substring(0, eq)) return null
    val init = tail.substring(eq + 1)
    if (kw == "DIMS") {
        val body = init.trimStart(' ', '\t')
        val literal = quotedLiteral(body) ?: return null
        // 引号串之后只许空或行尾注释（`;` 起），否则是初值表 / 杂字 ⇒ 不代入。
        val after = body.substring(2 + literal.length).trimStart(' ', '\t')
        if (after.isNotEmpty() && !after.startsWith(";")) return null
        return literal
    }
    val body = init.substringBefore(';').trim()
    if (body.isEmpty() || ',' in body) return null
    return intLiteralText(body)
}

/**
 * 整数字面量原文 → 十进制文本（`0x` 十六进制按值折算；值 = 原版 `SingleLongTerm.Int`）。
 *
 * 只认 `[+-]?` + 十进制数字 / `0x` 十六进制；其余（表达式、常量名、浮点、括号）⇒ null。
 */
private fun intLiteralText(text: String): String? {
    var i = 0
    var negative = false
    if (text.startsWith("+")) {
        i = 1
    } else if (text.startsWith("-")) {
        negative = true
        i = 1
    }
    if (i >= text.length) return null
    val digits = text.substring(i)
    val magnitude: Long? = if (digits.length > 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
        val hex = digits.substring(2)
        if (hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) hex.toLongOrNull(16) else null
    } else {
        if (digits.all { it.isDigit() }) digits.toLongOrNull() else null
    }
    val value = magnitude ?: return null
    return (if (negative) -value else value).toString()
}

/**
 * 语料静态扫描：逻辑行 → 函数块 / `$标签` / `#DIM` 声明 / 代码行。
 *
 * 逻辑行来自 [LogicalLineReader]（`EraStreamReader.ReadEnabledLine` 的移植：`{}` 续行、
 * `[IF]` 一族预处理、`;` 注释都已在 reader 里处理），因此本函数只做分类：
 * `@` 起新函数块、`$` 归当前函数、`#` 解析声明、其余进代码行。
 *
 * 读失败 / 二进制 / 词法错行**跳过**（不炸整批）——报告是下界，缺的行不冒充已覆盖。
 */
private fun buildGraph(root: Path, files: List<Path>, macroNames: Set<String>): CorpusGraph {
    val graph = CorpusGraph()
    // 声明序（MODERN-R13-R6D-① 传递解析的可见性轴，[refVisible]）：文件按
    // [CorpusCompile.listCorpusFiles] 的字典序 × 行内出现序，全图单调递增。
    var declSeq = 0
    // ---- ERH `#DEFINE` 宏表预扫（MODERN-R12-R6D）----
    // 宏在原版是 ERH 全量先装、ERB 后装（spec: ErbLoader.cs:40-75 + ErhLoader.cs:119-124），
    // 宏名对全部文件可见、与文件遍历序无关 ⇒ 先于主循环单独收一遍。解析直接复用编译侧
    // ERH 解析器 [LabelTable.collectErhSymbols]（宏名/宏体/注释剥离/参数化宏拒收/跨文件
    // 先到者胜的既有口径，本波不新造解析器）；跨文件重名传 priorMacroKeys 保持「先到者胜」。
    // 读失败 / 二进制与主循环同口径跳过 —— 下界报告，缺的宏不冒充已覆盖。
    for (path in files) {
        if (!path.fileName.toString().endsWith(".erh", ignoreCase = true)) continue
        val rel = root.relativize(path).toString().replace('\\', '/')
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            continue
        }
        if (hasNul(bytes)) continue
        val text = try {
            // 字节 → 物理行 → 逻辑行装配器吃的整文本：BOM/编码口径与主循环同一份
            //（[LogicalLineReader.readAllLines]），collectErhSymbols 内部按 CR/LF/CRLF 重切
            //（erhLogicalLineSequence → LogicalLineReader.splitLines），joinToString("\n") 无损。
            LogicalLineReader.readAllLines(bytes).joinToString("\n")
        } catch (_: Exception) {
            continue
        }
        val erh = try {
            LabelTable.collectErhSymbols(text, rel, graph.macros.keys)
        } catch (_: Exception) {
            continue
        }
        for ((key, value) in erh.macros) graph.macros.putIfAbsent(key, value)
    }
    for (path in files) {
        val rel = root.relativize(path).toString().replace('\\', '/')
        val isErhFile = path.fileName.toString().endsWith(".erh", ignoreCase = true)
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            continue
        }
        if (hasNul(bytes)) continue
        val reader = try {
            LogicalLineReader(LogicalLineReader.readAllLines(bytes), rel, macroNames = macroNames)
        } catch (_: Exception) {
            continue
        }
        var cur: FuncBlock? = null
        while (true) {
            val line = try {
                reader.readLogicalLine() ?: break
            } catch (_: LexError) {
                continue
            }
            when (line.kind) {
                LineKind.FUNCTION_LABEL -> {
                    val name = labelNameOf(line.content)
                    if (name.isEmpty()) {
                        cur = null
                        continue
                    }
                    val fn = FuncBlock(name.uppercase(Locale.ROOT), rel, line.lineNo + 1)
                    graph.functions.add(fn)
                    graph.byName.getOrPut(fn.nameUpper) { ArrayList() }.add(fn)
                    cur = fn
                }
                LineKind.GOTO_LABEL -> {
                    val label = gotoLabelNameOf(line.content)
                    if (label.isEmpty()) continue
                    val key = label.uppercase(Locale.ROOT)
                    cur?.labels?.add(key)
                    cur?.let { owner ->
                        graph.labelOwner.getOrPut(rel) { HashMap() }.putIfAbsent(key, owner)
                    }
                }
                LineKind.SHARP -> {
                    val decl = parseDimDecl(line.content) ?: continue
                    if (!isErhFile && cur == null) {
                        // R13-SUPER：ERB「函数外顶层」#DIM（首个 @ 行之前 / 空名 @ 行之后）
                        // 在引擎是 InvalidSharp 警告 + 整行忽略（ErbLoader.cs:413-424，文案
                        // Lang.cs:2430）—— 该名从未注册，镜像为不进 globalDecls、不占声明序。
                        // ERH（唯一合法注册口，ErhLoader.cs:249-268）与 ERB 函数上下文内
                        // （ParseSharpLine 私有注册，ErbLoader.cs:419）口径不变。
                        continue
                    }
                    val table = cur?.privateDecls ?: graph.globalDecls
                    val key = decl.name.uppercase(Locale.ROOT)
                    // 同名重复声明：持久化位沿用既有口径（后者胜），但常量值/引用名一旦
                    // 不一致就作废 ——原版对重复的用户变量名是装载期报错，这里不挑一个值来猜。
                    val prev = table[key]
                    val consistent =
                        prev == null || (prev.constValue == decl.constValue && prev.refName == decl.refName)
                    declSeq++
                    table[key] = VarDecl(
                        persisted = decl.persisted,
                        isStr = decl.isStr,
                        refName = if (consistent) decl.refName else null,
                        constValue = if (consistent) decl.constValue else null,
                        seq = declSeq,
                        isErh = isErhFile,
                    )
                }
                LineKind.CODE -> cur?.code?.add(line)
            }
        }
        runCatching { reader.fileEnd() }
    }
    resolveConstChains(graph)
    return graph
}

private fun hasNul(bytes: ByteArray): Boolean {
    val end = minOf(bytes.size, 8192)
    for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
    return false
}

/** 赋值目标的一档判定（内部用语，与 [PortabilityGrade] 一一对应）。 */
private enum class TargetClass { PERSIST, PLAIN, UNKNOWN }

/**
 * 持久化声明的查询面。
 *
 * 主源是扫描出的 `#DIM` 修饰符原文（与编译器解析的是同一行文本）；[bootMetadata] 给出时
 * 作为**权威覆盖**参与判定：
 * - `privateDims`（`BootMetadata.PrivateDim.spec.isGlobal` / `.isSavedata`，`UserVariables.kt:43-96`）按函数键归属；
 * - `globalDims`（`BootMetadata.RawDim.raw`）是 `#DIM` 声明原文，按 [parseDimDecl] 重新取修饰符
 *   （侧车把它当原文存，解析在装载期做）。
 *
 * 覆盖表优先于扫描表：侧车只登记**解析成功**的声明，同名的失败声明不该被当成持久变量。
 */
private class DeclResolver(
    private val scan: CorpusScan,
    private val graph: CorpusGraph,
    bootMetadata: BootMetadata?,
) {
    private val privateOverride: Map<String, Map<String, Boolean>> = run {
        if (bootMetadata == null) return@run emptyMap()
        val table = HashMap<String, MutableMap<String, Boolean>>()
        for (pd in bootMetadata.privateDims) {
            val spec = pd.spec
            // 位定义：core.vars.DimSpec.isGlobal / isSavedata（`UserDefinedVariableData.cs:54-264`）
            val persisted = spec.isGlobal || spec.isSavedata
            table.getOrPut(pd.functionKey.uppercase(Locale.ROOT)) { HashMap() }[spec.name.uppercase(Locale.ROOT)] =
                persisted
        }
        table
    }

    private val globalOverride: Map<String, Boolean> = buildMap {
        if (bootMetadata == null) return@buildMap
        for (gd in bootMetadata.globalDims) {
            val decl = parseDimDecl(gd.raw) ?: continue
            put(decl.name.uppercase(Locale.ROOT), decl.persisted)
        }
    }

    /**
     * 一个赋值目标名（null = 不可静态解析）落在哪一档。
     *
     * 解析顺序：宏名 ⇒ unknown；侧车覆盖 ⇒ 扫描出的私有声明 ⇒ 扫描出的全局声明 ⇒
     * 内建/系统变量表（[CorpusScan.global]，含 `BuiltinSymbols` 兜底）⇒ 都没有 ⇒ unknown。
     *
     * TODO(verify)：系统变量（`MONEY` / `DAY` 一类）经 `scan.global` 只判定「名字存在」，
     * 它们的 `isSavedata` 位在运行期 `VariableCode` 里（`VariableToken.cs:88-154`），本报告
     * 拿不到 ⇒ 现按 PLAIN 处理（可能少报 persist）。补锚需要把 `VariableRegistry` 的位表
     * 引进静态面，属跨模块接线，见交付回执的「需组长接线」。
     */
    fun classify(fn: FuncBlock, name: String?): TargetClass {
        if (name == null) return TargetClass.UNKNOWN
        val upper = name.uppercase(Locale.ROOT)
        if (upper in scan.macroNames) return TargetClass.UNKNOWN
        val flags = listOfNotNull(
            privateOverride[fn.nameUpper]?.get(upper),
            fn.privateDecls[upper]?.persisted,
            globalOverride[upper],
            graph.globalDecls[upper]?.persisted,
        )
        return when {
            flags.any { it } -> TargetClass.PERSIST
            flags.isNotEmpty() -> TargetClass.PLAIN
            scan.global.resolveVariable(name, null) != null -> TargetClass.PLAIN
            else -> TargetClass.UNKNOWN
        }
    }
}
