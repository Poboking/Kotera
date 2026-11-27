package com.eratw.port.erbcc

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.ExprParseException
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.ArgsEndWith
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.ExprTokenKind
import com.eratw.port.core.expr.OperatorCode as ExprOperatorCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.StmtParseConfig
import com.eratw.port.core.stmt.StmtParseException
import java.io.File
import java.io.IOException

/**
 * `dump-expr` —— 表达式层（P2-2b/c 的前置面）跨语言对拍夹具（CONTRACT-RUSTFULL §6.12）。
 *
 * 口径：对每条 CODE 行的**参数原文**（`LineParser.probeCodeLine` 的 `argText`，即分派层
 * `splitArgs` 的结果）按 `reduceArguments(EoL)` 解析成一个表达式列表，逐项落**规范序列化**。
 * 这样量的是表达式的骨架（节点种类 / 运算优先级结合 / 变量下标补全 / 函数调用类型），
 * 而不是某条语句的参数构造规则 —— 参数构造规则属 P2-2b，另版夹具再量。
 *
 * 跳过面（`K` 行，两侧都跳、不计入判据）：
 * - 参数原文为空（空参数原文在不少语句里合法，语义由语句层决定）；
 * - 出现 FORM_STRING 词法 token（格式化串求值面的移植在 P2-2c 的后续版；本版只量纯表达式）。
 *
 * 用法：`erbcc dump-expr <语料根> [--limit N] [--substr 过滤] [--out <tsv>]`
 *
 * 输出（LF、单空格分列；字符串按 JSON 方言转义）：
 * ```
 * #format erbcc-dump-expr 1
 * #parser loose exprConfig=systemNoTarget:false,compatiRAND:false
 * file <相对路径> <行数> <解析成功> <解析失败> <跳过>
 * X <行号> <序列化列表>
 * E <行号> <列> "<错误文本>"
 * K <行号>
 * ```
 */

/**
 * 词法 Token → 表达式 Token。逐条同 `stmt/ParseSupport.kt:197-222` 的 `Token.toExprToken`，
 * 只因 `toExprTokens` 是 core-vm 的 `internal`（跨模块不可见）而在本模块复刻一份；
 * 两侧改一处必须同步另一处（对拍差异会立刻在 `dump-expr` sha 上暴露）。
 */
private fun toExprTokens(tokens: List<Token>): List<ExprToken> = tokens.map { t ->
    when (t) {
        is Token.Identifier -> ExprToken(ExprTokenKind.IDENTIFIER, t.code, line = t.line, col = t.col)
        is Token.Integer -> ExprToken(ExprTokenKind.INT_LITERAL, t.text, intValue = t.value, line = t.line, col = t.col)
        is Token.Float -> ExprToken(ExprTokenKind.FLOAT_LITERAL, t.raw, line = t.line, col = t.col, floatValue = t.value)
        is Token.StringLiteral -> ExprToken(ExprTokenKind.STR_LITERAL, t.value, line = t.line, col = t.col)
        is Token.Operator -> ExprToken(
            ExprTokenKind.OPERATOR,
            ExprOperatorCode.toOperatorString(
                ExprOperatorCode.fromCSharpName(t.code.name) ?: error("未映射的运算符 ${t.code.name}"),
            ),
            line = t.line, col = t.col,
        )
        is Token.FormStr -> ExprToken(ExprTokenKind.FORM_STRING, "<FORM>", line = t.line, col = t.col, form = t.form)
        is Token.Symbol -> ExprToken(
            when (t.char) {
                '(' -> ExprTokenKind.LPAREN
                ')' -> ExprTokenKind.RPAREN
                '[' -> ExprTokenKind.LBRACKET
                ']' -> ExprTokenKind.RBRACKET
                ',' -> ExprTokenKind.COMMA
                ':' -> ExprTokenKind.COLON
                '@' -> ExprTokenKind.AT
                '.' -> ExprTokenKind.DOT
                else -> throw StmtParseException(t.line, t.col, "", "表达式层不支持的分隔符 '${t.char}'")
            },
            t.char.toString(), line = t.line, col = t.col,
        )
    }
}

internal object DumpExpr {
    private const val FORMAT = "erbcc-dump-expr 1"
    private const val USAGE = "usage: erbcc dump-expr <语料根> [--limit N] [--substr 过滤] [--out <tsv>]"

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
        out.append("#parser loose exprConfig=systemNoTarget:false,compatiRAND:false\n")
        val lexer = ErbLexer()
        val stmtParser = LineParser(
            resolver = LooseResolver(),
            stmtConfig = StmtParseConfig(useScopedVariableInstruction = false),
        )
        var ok = 0
        var err = 0
        var skip = 0
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            val body = StringBuilder()
            val counts = try {
                dumpFile(f, rel, lexer, stmtParser, body)
            } catch (e: LexError) {
                body.setLength(0)
                body.append("E 0 0 ").append(quote(e.message ?: "")).append('\n')
                intArrayOf(0, 0, 1, 0)
            } catch (e: IOException) {
                body.setLength(0)
                body.append("E 0 0 ").append(quote("${e::class.simpleName}: ${e.message}")).append('\n')
                intArrayOf(0, 0, 1, 0)
            }
            out.append("file ").append(rel).append(' ').append(counts[0]).append(' ')
                .append(counts[1]).append(' ').append(counts[2]).append(' ').append(counts[3]).append('\n')
                .append(body)
            ok += counts[1]
            err += counts[2]
            skip += counts[3]
        }
        val p = outPath?.let(::File)
        if (p == null) {
            print(out)
            return 0
        }
        p.absoluteFile.parentFile?.mkdirs()
        p.writeText(out.toString())
        System.err.println("已导出 ${files.size} 个文件 / 成功 $ok / 失败 $err / 跳过 $skip → ${p.absolutePath}")
        return 0
    }

    private fun bad(reason: String): Int {
        System.err.println("错误: dump-expr $reason")
        return 2
    }

    /** 返回 `[行数, 成功, 失败, 跳过]`。 */
    private fun dumpFile(
        f: File,
        rel: String,
        lexer: ErbLexer,
        stmtParser: LineParser,
        body: StringBuilder,
    ): IntArray {
        val reader = LogicalLineReader(LogicalLineReader.readAllLines(f.readBytes()), rel)
        var lines = 0
        var ok = 0
        var err = 0
        var skip = 0
        var line = reader.readLogicalLine()
        while (line != null) {
            lines++
            if (line.kind == LineKind.CODE) {
                // 分派层就失败的行不进表达式面（Rust 侧 `parse_code_line` 为 Err 时同样跳过，
                // 计入 skip 而不是 err，两侧口径必须一致）。
                val probe = try {
                    stmtParser.probeCodeLine(line.content, line.lineNo)
                } catch (e: StmtParseException) {
                    skip++
                    line = reader.readLogicalLine()
                    continue
                } catch (e: LexError) {
                    skip++
                    line = reader.readLogicalLine()
                    continue
                }
                val text = probe.argText
                if (text.isEmpty()) {
                    skip++
                } else {
                    val tokens = try {
                        lexer.analyse(CharStream(text), LexEndWith.EoL, LexAnalyzeFlag.NONE, line.lineNo)
                    } catch (e: LexError) {
                        err++
                        body.append("E ").append(line.lineNo).append(' ').append(e.column).append(' ')
                            .append(quote(e.message ?: "")).append('\n')
                        line = reader.readLogicalLine()
                        continue
                    }
                    if (tokens.any { it is Token.FormStr }) {
                        skip++
                    } else {
                        try {
                            val list = ExprParser(toExprTokens(tokens), LooseResolver(), ParserConfig())
                                .reduceArguments(ArgsEndWith.EoL)
                            ok++
                            body.append("X ").append(line.lineNo).append(' ')
                                .append(list.joinToString("|") { if (it == null) "_" else describe(it) })
                                .append('\n')
                        } catch (e: ExprParseException) {
                            err++
                            body.append("E ").append(line.lineNo).append(' ').append(e.col).append(' ')
                                .append(quote(tailOf(e.message))).append('\n')
                        } catch (e: ExprEvalException) {
                            err++
                            body.append("E ").append(line.lineNo).append(" -1 ")
                                .append(quote(e.message ?: "")).append('\n')
                        } catch (e: StmtParseException) {
                            err++
                            body.append("E ").append(line.lineNo).append(' ').append(e.col).append(' ')
                                .append(quote(tailOf(e.message))).append('\n')
                        }
                    }
                }
            }
            line = reader.readLogicalLine()
        }
        reader.fileEnd()
        return intArrayOf(lines, ok, err, skip)
    }

    /** `解析错误(行:N,列:C): xxx` → `xxx`（行号列号已分列导出）。 */
    private fun tailOf(message: String?): String {
        val m = message ?: ""
        val idx = m.lastIndexOf("): ")
        return if (idx >= 0) m.substring(idx + 3) else m
    }

    // ------------------------------------------------------------------
    // 规范序列化 —— Rust `expr::describe` 的函数对偶，任一侧改动必须同步
    // ------------------------------------------------------------------

    internal fun describe(e: Expr): String = when (e) {
        is IntLiteral -> "L" + e.value
        is StrLiteral -> "S" + quote(e.value)
        is FloatLiteral -> "F" + java.lang.Double.doubleToRawLongBits(e.value).toULong().toString(16)
        is NullTerm -> if (e.isInt) "NI" else "NS"
        is VarRef -> buildString {
            append('V').append(e.name).append('/').append(typeChar(e.operandType)).append(flagChar(e.isConst))
                .append('/').append(flagChar(e.isNoArg)).append('/').append(e.subKey ?: "-").append('/')
            append(e.indices.joinToString(",") { describe(it) })
        }
        is KeywordIndexArg -> "K" + e.varName + "#" + e.dim + ":" + describe(e.expr)
        is FuncCall -> "C" + e.name + "/" + typeChar(e.operandType) + "(" +
            e.args.joinToString(",") { if (it == null) "_" else describe(it) } + ")"
        is Unary -> "U" + e.op.name + "(" + describe(e.operand) + ")"
        is UnaryAfter -> "A" + e.op.name + "(" + describe(e.operand) + ")"
        is Binary -> "B" + e.op.name + "/" + typeChar(e.operandType) +
            "(" + describe(e.left) + "," + describe(e.right) + ")"
        is Ternary -> "T" + typeChar(e.operandType) +
            "(" + describe(e.cond) + "," + describe(e.ifTrue) + "," + describe(e.ifFalse) + ")"
        else -> error("未覆盖的表达式节点: ${e::class.simpleName}")
    }

    private fun typeChar(t: OperandType): Char = when (t) {
        OperandType.INT -> 'I'
        OperandType.STR -> 'S'
        OperandType.FLOAT -> 'F'
    }

    private fun flagChar(b: Boolean): Char = if (b) '1' else '0'

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
