package com.eratw.port.erbcc

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.BuiltinSymbols
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.StmtParseConfig
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.StmtResolver
import java.io.File
import java.io.IOException

/**
 * `dump-stmt` —— 语句层分派面的跨语言对拍夹具（CONTRACT-RUSTFULL §6.10 P2-2a）。
 *
 * 这一版只量**结构面**（行级分派），不量参数树：
 * 每行导出「语句类名 / FunctionCode 名 / Argument.shape 名 / 分派特化字段 / 原文」。
 * 参数的组成（37 语句 × 47 标签的构建规则）由同一夹具的后续版本（`erbcc-dump-stmt 2`）
 * 补上，P2-2b 才对参数树取证；本节矩阵里两档结论分别署名，不把结构面 0 差异当 P2-2 收口。
 *
 * 解析用**宽松符号器**（用户裁决的「方案 2」）：未知名字不报「未声明」，而是按内建表兜底、
 * 兜不到就当 1 维 INT 变量。这样做的理由：本夹具量的是分派结构，不是语义；语义面（方案 1，
 * 全语料符号预扫）另立一档，二者结论不得互相顶替。
 *
 * 用法：`erbcc dump-stmt <语料根> [--limit N] [--substr 过滤] [--out <tsv>]`
 *
 * 输出（LF 分隔、单空格分列、字符串列按 JSON 方言转义，口径同 `dump-tokens`）：
 * ```
 * #format erbcc-dump-stmt 1
 * #parser loose stmtConfig=useScopedVariableInstruction:false
 * file <相对路径> <逻辑行数> <语句数> <错误数>
 * S <行号> <语句类名> <FunctionCode 名> <Argument.shape> <分派特化> "<原文>"
 * E <行号> <列> "<语句名>" "<错误信息>"
 * ```
 * `分派特化` 只写分派层可见的字段（赋值运算符 / 跳转与调用标签 / PRINT 形态），
 * 不写参数内部结构（那是 P2-2b 的面）：无则写 `-`。
 */
internal object DumpStmt {
    private const val FORMAT = "erbcc-dump-stmt 1"
    private const val USAGE = "usage: erbcc dump-stmt <语料根> [--limit N] [--substr 过滤] [--out <tsv>]"
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
    private const val HEAP_BITS = 20

    fun run(args: List<String>): Int = when {
        args.any { it == "--help" || it == "-h" } -> {
            println(USAGE)
            0
        }
        else -> {
            val parsed = parseArgs(args)
            when (parsed) {
                is Parsed.Bad -> {
                    System.err.println("错误: dump-stmt ${parsed.reason}")
                    2
                }
                is Parsed.Ok -> dump(parsed.opts)
            }
        }
    }

    private sealed interface Parsed {
        class Ok(val opts: Options) : Parsed
        class Bad(val reason: String) : Parsed
    }

    private class Options(val dir: File, val outPath: File?, val limit: Int, val substr: String?)

    private class Accum {
        var root: String? = null
        var outPath: String? = null
        var limit = Int.MAX_VALUE
        var substr: String? = null
    }

    private class Step(val advance: Int, val error: String?)

    private fun parseArgs(args: List<String>): Parsed {
        val acc = Accum()
        var bad: String? = null
        var i = 0
        while (i < args.size && bad == null) {
            val a = args[i]
            if (!a.startsWith("--")) {
                acc.root = a
                i++
            } else {
                val step = option(a, args.getOrNull(i + 1), acc)
                if (step.error == null) i += step.advance else bad = step.error
            }
        }
        val dir = acc.root?.let(::File)
        return when {
            bad != null -> Parsed.Bad(bad ?: "")
            dir == null || !dir.isDirectory -> Parsed.Bad("需要一个语料目录")
            else -> Parsed.Ok(Options(dir, acc.outPath?.let(::File), acc.limit, acc.substr))
        }
    }

    private fun option(a: String, v: String?, acc: Accum): Step = when (a) {
        "--out" -> if (v == null) Step(0, "--out 缺路径") else run { acc.outPath = v; Step(2, null) }
        "--limit" -> {
            val n = v?.toIntOrNull()
            if (n == null || n <= 0) Step(0, "--limit 要给正整数") else run { acc.limit = n; Step(2, null) }
        }
        "--substr" -> if (v == null) Step(0, "--substr 缺值") else run { acc.substr = v; Step(2, null) }
        else -> Step(0, "不认识的参数: $a")
    }

    /**
     * 宽松符号器（方案 2）：内建表优先；**未知函数名按 `(...)` 调用形态放行、返回 INT**，
     * 未知变量折成 1 维 INT。方案 2 的判据是「分派结构一致」，不是「语义可解析」——
     * 未知名报「未声明」属方案 1（全语料符号预扫）的语义面，不得在本档混用。
     */
    private class LooseResolver : StmtResolver {
        override fun resolveVariable(name: String, subKey: String?): VarInfo =
            BuiltinSymbols.variable(name)?.let {
                VarInfo(it.name, it.type, it.dimension, it.isCharacterData, it.isConst)
            } ?: VarInfo(name, OperandType.INT, 1, false, false)

        override fun resolveFunction(name: String): OperandType =
            BuiltinSymbols.function(name) ?: OperandType.INT

        override fun canReturnFloat(name: String): Boolean =
            BuiltinSymbols.function(name) == null && BuiltinSymbols.canReturnFloat(name)

        override fun canBeReferencedBare(name: String): Boolean =
            BuiltinSymbols.function(name) != null || !BuiltinSymbols.isBuiltinMethod(name)

        override fun isUserMethod(name: String): Boolean = false
    }

    private fun dump(opts: Options): Int {
        val files = opts.dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("erb", true) || it.extension.equals("erh", true)) }
            .filter { opts.substr == null || it.path.replace('\\', '/').contains(opts.substr) }
            .sortedBy { it.path.replace('\\', '/') }
            .take(opts.limit)
            .toList()
        val out = StringBuilder(1 shl HEAP_BITS)
        out.append("#format ").append(FORMAT).append('\n')
        out.append("#parser loose stmtConfig=useScopedVariableInstruction:false").append('\n')
        val parser = LineParser(
            resolver = LooseResolver(),
            stmtConfig = StmtParseConfig(useScopedVariableInstruction = false),
        )
        var statements = 0
        var errors = 0
        for (f in files) {
            val rel = f.relativeTo(opts.dir).path.replace('\\', '/')
            val fileDump = readAndDump(f, rel, parser)
            statements += fileDump.statements
            errors += fileDump.errors
            out.append("file ").append(rel).append(' ').append(fileDump.logical).append(' ')
                .append(fileDump.statements).append(' ').append(fileDump.errors).append('\n')
                .append(fileDump.body)
        }
        val path = opts.outPath
        if (path == null) {
            print(out)
            return 0
        }
        path.absoluteFile.parentFile?.mkdirs()
        path.writeText(out.toString())
        System.err.println(
            "已导出 ${files.size} 个文件 / $statements 条分派 / $errors 处分派错误 → ${path.absolutePath}",
        )
        return 0
    }

    private class FileDump(
        val logical: Int,
        val statements: Int,
        val errors: Int,
        val body: StringBuilder,
    )

    private fun readAndDump(f: File, rel: String, parser: LineParser): FileDump {
        val body = StringBuilder()
        var logical = 0
        var statements = 0
        var errors = 0
        try {
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(f.readBytes()), rel)
            var line = reader.readLogicalLine()
            while (line != null) {
                logical++
                try {
                    writeProbe(body, line, parser)
                    statements++
                } catch (e: StmtParseException) {
                    errors++
                    body.append('E').append(' ').append(e.line).append(' ').append(e.col).append(' ')
                        .append(quote(e.statementName)).append(' ').append(quote(messageOf(e))).append('\n')
                } catch (e: LexError) {
                    errors++
                    body.append('E').append(' ').append(line.lineNo).append(' ').append(e.column).append(' ')
                        .append(quote("")).append(' ').append(quote(e.message ?: "")).append('\n')
                }
                line = reader.readLogicalLine()
            }
            reader.fileEnd()
        } catch (e: LexError) {
            body.setLength(0)
            body.append("E 0 0 \"\" ").append(quote(e.message ?: "")).append('\n')
            return FileDump(0, 0, 1, body)
        } catch (e: IOException) {
            body.setLength(0)
            body.append("E 0 0 \"\" ").append(quote("${e::class.simpleName}: ${e.message}")).append('\n')
            return FileDump(0, 0, 1, body)
        }
        return FileDump(logical, statements, errors, body)
    }

    /**
     * 一行分派记录。标签行/`#` 行没有分派问题（`__NULL__`），只记原文。
     *
     * 交叉核对：整行解析成功时，探针的类名/语句码必须与真实构造一致 —— 探针与
     * [LineParser.parseCodeLine] 共享判定助手，但顺序若被单边改动，这一行就会在夹具里
     * 变成 `X`（夹具自身的守门，不是 Rust 侧的对拍面）。
     */
    private fun writeProbe(sb: StringBuilder, line: LogicalLine, parser: LineParser) {
        if (line.kind != LineKind.CODE) {
            val cls = when (line.kind) {
                LineKind.FUNCTION_LABEL -> "FuncLabelStmt"
                LineKind.GOTO_LABEL -> "GotoLabelStmt"
                else -> "SharpStmt"
            }
            sb.append("S ").append(line.lineNo).append(' ').append(cls).append(" __NULL__ \"\" - ")
                .append(quote(line.content)).append('\n')
            return
        }
        val probe = parser.probeCodeLine(line.content, line.lineNo)
        sb.append("S ").append(line.lineNo).append(' ').append(probe.className).append(' ')
            .append(probe.code.name).append(' ').append(quote(probe.argText)).append(' ')
            .append(probe.extra).append(' ').append(quote(line.content.trimStart(' ', '\t'))).append('\n')
        val stmt = try {
            parser.parseLogicalLine(line)
        } catch (_: Exception) {
            null
        }
        if (stmt != null && (stmt.javaClass.simpleName != probe.className || stmt.code.name != probe.code.name)) {
            sb.append("X ").append(line.lineNo).append(" probe=").append(probe.className)
                .append('/').append(probe.code.name).append(" actual=")
                .append(stmt.javaClass.simpleName).append('/').append(stmt.code.name).append('\n')
        }
    }

    /** 去掉异常信息里的行号/列前缀（行号列号已分列导出，避免同事实两处表述漂移）。 */
    private fun messageOf(e: StmtParseException): String {
        // 异常 message 的形态是 `语句解析错误(行:N,列:C)<语句名>: <错误文本>`；
        // 行号/列号/语句名已分列导出，这里只留 `<错误文本>`（语句名可能为空串）。
        var tail = (e.message ?: "").substringAfter(")")
        if (e.statementName.isNotEmpty()) tail = tail.removePrefix(e.statementName)
        return tail.removePrefix(":").trimStart()
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
                    sb.append("\\u").append(c.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
