package com.eratw.port.erbcc

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.ArgBuilders
import com.eratw.port.core.stmt.ArgCtx
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.StmtParseConfig
import com.eratw.port.core.stmt.StmtParseException
import java.io.File
import java.io.IOException

/**
 * `dump-args` —— 参数构建层（P2-2b / B1 批）跨语言对拍夹具（CONTRACT-RUSTFULL §6.13）。
 *
 * 本版只量**单表达式/常量族**（`ArgType` ∈ [B1_TYPES]）：表达式树已由 `dump-expr` 钉住，
 * 这一层剩下的是「参数条数 / 类型闸 / 单值形态 / 常量身份（`isConst`/`constStr`/`constInt`）」。
 * `PRINT`（38%）、`CASE`、`INT_ANY`、`SP_*` 等批次另起版本，格式沿用本文件。
 *
 * **错误面只比分类、不比文案**：`F` 行只落「该参数构造成立/不成立」，不落错误文本 ——
 * 官方的错误文案来自 `LangKey` 资源表，Rust 侧要另建一份文本表才有逐字可比性，
 * 那属语义面（方案 1）的活。计数（`ok`/`fail`）逐文件闭合，分类不一致即立刻在 sha 上暴露。
 *
 * 用法：`erbcc dump-args <语料根> [--limit N] [--substr 过滤] [--out <tsv>]`
 */
internal object DumpArgs {
    private const val FORMAT = "erbcc-dump-args 1"
    private const val USAGE = "usage: erbcc dump-args <语料根> [--limit N] [--substr 过滤] [--out <tsv>]"

    /** B1+B2 批：单表达式与常量族（`VOID` 45.6 万条）+ `PRINT` 家族的三种参数形态。 */
    private val B1_TYPES = setOf(
        ArgType.VOID,
        ArgType.EXPRESSION,
        ArgType.EXPRESSION_NULLABLE,
        ArgType.INT_EXPRESSION,
        ArgType.INT_EXPRESSION_NULLABLE,
        ArgType.STR,
        ArgType.STR_NULLABLE,
        ArgType.KEYWORD,
        // B2：PRINT 家族（全语料 128 万条）。`FORM_STR_NULLABLE`（PRINTFORM）要
        // `FormStringTerm`（P2-2c 未移植面）⇒ 不进本批，两侧同样计 skip。
        ArgType.SP_PRINTV,
        ArgType.STR_EXPRESSION,
        ArgType.STR_EXPRESSION_NULLABLE,
        // B3：INT_ANY（可变个数整型实参，23 万条）。
        ArgType.INT_ANY,
    )

    /**
     * 不做「表单 token 预探」的类型：`STR`/`KEYWORD` 族是原文直取（`ArgBuilders.kt:287-294`）；
     * `SP_PRINTV` 走私用词法标志（`'…'` 即字符串），拿默认标志预探会把 `'('` 判成词法错误。
     */
    private val RAW_TEXT_TYPES =
        setOf(ArgType.STR, ArgType.STR_NULLABLE, ArgType.KEYWORD, ArgType.SP_PRINTV)

    fun run(args: List<String>): Int {
        var root: String? = null
        var outPath: String? = null
        var limit = Int.MAX_VALUE
        var substr: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--help", "-h" -> {
                    println(USAGE)
                    return 0
                }
                "--out" -> {
                    outPath = args.getOrNull(i + 1) ?: return bad("--out 缺路径")
                    i += 2
                }
                "--limit" -> {
                    val n = args.getOrNull(i + 1)?.toIntOrNull()
                    if (n == null || n <= 0) return bad("--limit 要给正整数")
                    limit = n
                    i += 2
                }
                "--substr" -> {
                    substr = args.getOrNull(i + 1) ?: return bad("--substr 缺值")
                    i += 2
                }
                else -> if (a.startsWith("--")) return bad("不认识的参数: $a") else {
                    root = a
                    i++
                }
            }
        }
        val dir = root?.let(::File)
        if (dir == null || !dir.isDirectory) return bad("需要一个语料目录")

        val files = dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("erb", true) || it.extension.equals("erh", true)) }
            .filter { substr == null || it.path.replace('\\', '/').contains(substr!!) }
            .sortedBy { it.path.replace('\\', '/') }
            .take(limit)
            .toList()

        val out = StringBuilder(1 shl 20)
        out.append("#format ").append(FORMAT).append('\n')
        out.append("#parser loose args=B1\n")
        val stmtParser = LineParser(
            resolver = LooseResolver(),
            stmtConfig = StmtParseConfig(useScopedVariableInstruction = false),
        )
        var tOk = 0
        var tFail = 0
        var tSkip = 0
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            val body = StringBuilder()
            val counts = try {
                dumpFile(f, stmtParser, body)
            } catch (e: IOException) {
                body.setLength(0)
                intArrayOf(0, 0, 1, 0)
            }
            out.append("file ").append(rel).append(' ').append(counts[0]).append(' ')
                .append(counts[1]).append(' ').append(counts[2]).append(' ').append(counts[3]).append('\n')
                .append(body)
            tOk += counts[1]
            tFail += counts[2]
            tSkip += counts[3]
        }
        val p = outPath?.let(::File)
        if (p == null) {
            print(out)
            return 0
        }
        p.absoluteFile.parentFile?.mkdirs()
        p.writeText(out.toString())
        System.err.println("已导出 ${files.size} 个文件 / 成功 $tOk / 失败 $tFail / 跳过 $tSkip → ${p.absolutePath}")
        return 0
    }

    private fun bad(reason: String): Int {
        System.err.println("错误: dump-args $reason")
        return 2
    }

    private fun dumpFile(f: File, stmtParser: LineParser, body: StringBuilder): IntArray {
        val reader = LogicalLineReader(LogicalLineReader.readAllLines(f.readBytes()), "file")
        var lines = 0
        var ok = 0
        var fail = 0
        var skip = 0
        var line = reader.readLogicalLine()
        while (line != null) {
            val ln = line
            val lineNo = ln.lineNo
            lines++
            if (ln.kind == LineKind.CODE) {
                val probe = try {
                    stmtParser.probeCodeLine(ln.content, ln.lineNo)
                } catch (e: StmtParseException) {
                    skip++
                    line = reader.readLogicalLine()
                    continue
                } catch (e: LexError) {
                    skip++
                    line = reader.readLogicalLine()
                    continue
                }
                // PRINT 家族的参数形态编在语句名后缀里（`PrintSpec.kt:44-125`），
                // 生产路径靠 `ArgCtx.argTypeOverride` 传给 `ArgBuilders.create`（`LineParser.kt:265-278`）。
                var argType = probe.code.argType
                if (argType == ArgType.PRINT) {
                    val spec = PrintSpec.parse(probe.code)
                    if (spec == null) {
                        fail++
                        body.append("F ").append(lineNo).append('\n')
                        line = reader.readLogicalLine()
                        continue
                    }
                    argType = spec.argType
                }
                // REPEAT 的 INT_EXPRESSION 会被改写成 SpForNextArgument（B4 批），本版整行跳过。
                if (argType !in B1_TYPES || probe.code.name.equals("REPEAT", ignoreCase = true)) {
                    skip++
                } else {
                    val stmtName = ln.content.trimStart(' ', '\t')
                        .takeWhile { it.isLetterOrDigit() || it == '_' }
                    val ctx = ArgCtx(
                        line = lineNo,
                        code = probe.code,
                        statementName = stmtName,
                        argText = probe.argText,
                        resolver = LooseResolver(),
                        config = ParserConfig(),
                        warn = { },
                        lexerConfig = LexerConfig(),
                        argTypeOverride = argType,
                    )
                    // 含 FORM token 的整行本版不量（Rust 侧 `pop_terms` 见 `TokKind::Form` 即 `FormSkip`）：
                    // 必须在 build 之前探。**只对走表达式词法的类型探**：`STR`/`KEYWORD` 族
                    // 是原文直取（`ArgBuilders.kt:287-294`），`CUSTOMDRAWLINE =` 这类行原文本身
                    // 不是合法表达式，拿 EoL 词法去探会把它们误判成失败（实测 58 条差异）。
                    if (argType !in RAW_TEXT_TYPES) {
                        val tokens = try {
                            ErbLexer(LexerConfig()).analyse(
                                CharStream(probe.argText), LexEndWith.EoL, LexAnalyzeFlag.NONE, lineNo,
                            )
                        } catch (e: LexError) {
                            fail++
                            body.append("F ").append(lineNo).append('\n')
                            line = reader.readLogicalLine()
                            continue
                        }
                        if (tokens.any { it is Token.FormStr }) {
                            skip++
                            line = reader.readLogicalLine()
                            continue
                        }
                    }
                    try {
                        val arg = ArgBuilders.create(ctx)
                        // 含 FormStringTerm 的参数（`"..."` 内嵌 `{...}`）本版不量：表达式层夹具
                        // 已整行跳过它们，这里同样计 skip（两侧口径必须一致）。用 describe 的
                        // `IllegalStateException` 兜底而不是白名单遍历：Form 可能藏在任意子节点。
                        val term = try {
                            termOf(arg)
                        } catch (e: IllegalStateException) {
                            skip++
                            line = reader.readLogicalLine()
                            continue
                        }
                        ok++
                        body.append("A ").append(lineNo).append(' ')
                            .append(arg::class.simpleName).append(' ')
                            .append(arg.shape.name).append(' ')
                            .append(if (arg.isConst) '1' else '0').append(' ')
                            .append(arg.constStr?.let(::quote) ?: "-").append(' ')
                            .append(arg.constInt?.toString() ?: "-").append(' ')
                            .append(term).append('\n')
                    } catch (e: StmtParseException) {
                        fail++
                        body.append("F ").append(lineNo).append('\n')
                    } catch (e: ExprParseException) {
                        fail++
                        body.append("F ").append(lineNo).append('\n')
                    } catch (e: ExprEvalException) {
                        fail++
                        body.append("F ").append(lineNo).append('\n')
                    }
                }
            }
            line = reader.readLogicalLine()
        }
        reader.fileEnd()
        return intArrayOf(lines, ok, fail, skip)
    }


    /** 表达式树里是否出现 `FormStringTerm`（本版夹具不量的面）。 */
    private fun containsForm(e: Expr): Boolean = when (e) {
        is com.eratw.port.core.expr.FormStringTerm -> true
        is com.eratw.port.core.expr.VarRef -> e.indices.any(::containsForm)
        is com.eratw.port.core.expr.KeywordIndexArg -> containsForm(e.expr)
        is com.eratw.port.core.expr.FuncCall -> e.args.any { it != null && containsForm(it) }
        is com.eratw.port.core.expr.Unary -> containsForm(e.operand)
        is com.eratw.port.core.expr.UnaryAfter -> containsForm(e.operand)
        is com.eratw.port.core.expr.Binary -> containsForm(e.left) || containsForm(e.right)
        is com.eratw.port.core.expr.Ternary ->
            containsForm(e.cond) || containsForm(e.ifTrue) || containsForm(e.ifFalse)
        else -> false
    }

    private fun termOf(arg: Argument): String = when (arg) {
        is ExpressionArgument -> arg.term?.let { DumpExpr.describe(it) } ?: "-"
        // PRINTV 没有单 term，落全部实参（两侧同一序列化，逐字节可比）。
        is SpPrintVArgument -> "PV(" + arg.terms.joinToString("|") { DumpExpr.describe(it) } + ")"
        is ExpressionArrayArgument ->
            "AR(" + arg.terms.joinToString("|") { it?.let { t -> DumpExpr.describe(t) } ?: "-" } + ")"
        else -> "-"
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
