package com.eratw.port.erbcc

import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.lexer.Token
import java.io.File
import java.io.IOException

/**
 * `dump-tokens` —— 词法面的跨语言对拍夹具（CONTRACT-RUSTFULL §6.4 P2-1）。
 *
 * 为什么需要它：Rust 前端的第一段是「ERB 字节 → 逻辑行 → Token 流」。这一段的对错无法从产物
 * 反推（产物错在千里之外），所以必须把 Kotlin 侧的**中间结果**导出来，让 Rust 用同一口径逐
 * token 比。表不是手抄的：由活实现 [LogicalLineReader] + [ErbLexer] 直接跑出来。
 *
 * 口径（写进 dump 头，读侧据此判断可比性）：
 * - `LexerConfig()` 缺省（`debugMode=false`、宏表空）；`[IF 宏]` 按「未定义」处理。
 * - 只导**逻辑行**与该行 token：`CODE` 走 `analyse`（整行 Token 流），标签行先取标签名再补其余
 *   token，`SHARP` 先取关键字再补其余 token。
 * - **不做语句层分片**：这是本夹具的已知边界。语料里 `PRINTFORML … {TALKER:0}` 这种裸 `{X:0}`
 *   参数只在语句层（且只在部分语句）合法，整行 `analyse` 必抛。这类行**不跳过**，而是把错误
 *   位置原样导出成 `E <行号> "<消息>" <列>`，Rust 侧连「抛在第几列」一起对上。语句层的合法
 *   分片由 P2-2（stmt lane）另立对拍面。
 *
 * 用法：`erbcc dump-tokens <语料根> [--limit N] [--substr 过滤] [--out <tsv>]`
 *
 * 输出（LF 分隔、单空格分列、字符串列按 JSON 方言转义）：
 * ```
 * #format erbcc-dump-tokens 1
 * #lexer-config default flags=ALLOW_ASSIGNMENT|ALLOW_SINGLE_QUOTATION_STR
 * file <相对路径> <逻辑行数>
 * L <行号> <CODE|FUNCTION_LABEL|GOTO_LABEL|SHARP> "<content>"
 * T <行号> <序号> <类型字符> "<text>" <line> <col>
 * E <行号> "<错误文本>" <列>
 * ```
 * 读文件本身失败（编码探测不了、BOM 损坏）的文件 `file` 计数写 0，并紧随一行不带行号的
 * `E 0 "<错误文本>" 0`。
 */
internal object DumpTokens {
    private const val FORMAT = "erbcc-dump-tokens 1"

    /** 为什么固定这组 flags：见 [tokensOf] 的注释（整行 `analyse` 的对拍口径）。 */
    private const val FLAGS_EXPR = "ALLOW_ASSIGNMENT|ALLOW_SINGLE_QUOTATION_STR"
    private val FLAGS: Int = LexAnalyzeFlag.ALLOW_ASSIGNMENT or LexAnalyzeFlag.ALLOW_SINGLE_QUOTATION_STR

    private const val HEAP_BITS = 20
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
    private const val USAGE = "usage: erbcc dump-tokens <语料根> [--limit N] [--substr 过滤] [--out <tsv>]"

    fun run(args: List<String>): Int = when {
        args.any { it == "--help" || it == "-h" } -> {
            println(USAGE)
            0
        }
        else -> {
            val parsed = parseArgs(args)
            if (parsed is Parsed.Bad) {
                System.err.println("错误: dump-tokens ${parsed.reason}")
                2
            } else {
                dump((parsed as Parsed.Ok).opts)
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

    private fun dump(opts: Options): Int {
        val files = opts.dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("erb", true) || it.extension.equals("erh", true)) }
            .filter { opts.substr == null || it.path.replace('\\', '/').contains(opts.substr) }
            .sortedBy { it.path.replace('\\', '/') }
            .take(opts.limit)
            .toList()
        val out = StringBuilder(1 shl HEAP_BITS)
        out.append("#format ").append(FORMAT).append('\n')
        out.append("#lexer-config default flags=").append(FLAGS_EXPR).append('\n')
        val lexer = ErbLexer(LexerConfig())
        for (f in files) {
            val rel = f.relativeTo(opts.dir).path.replace('\\', '/')
            val (logical, body) = readAndDump(f, rel, lexer)
            out.append("file ").append(rel).append(' ').append(logical).append('\n').append(body)
        }
        val f = opts.outPath
        if (f == null) {
            print(out)
            return 0
        }
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(out.toString())
        val rows = out.lineSequence().count { it.startsWith("T ") }
        System.err.println("已导出 ${files.size} 个文件 / $rows 个 token → ${f.absolutePath}")
        return 0
    }

    /**
     * 读一个文件并吐出它的全部逻辑行/Token 记录。读盘/编码层面的失败（[IOException]）与
     * `readLogicalLine` 的致命错误（[LexError]）都折成一行 `file … 0` + `E`，不中断整批。
     */
    private fun readAndDump(f: File, rel: String, lexer: ErbLexer): Pair<Int, StringBuilder> {
        val body = StringBuilder()
        return try {
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(f.readBytes()), rel)
            var n = 0
            var line = reader.readLogicalLine()
            while (line != null) {
                n++
                dumpLineSafe(body, lexer, line, rel)
                line = reader.readLogicalLine()
            }
            reader.fileEnd()
            n to body
        } catch (e: LexError) {
            body.setLength(0)
            body.append("E ").append(e.lineNo).append(' ').append(quote("${e.message}")).append(" 0\n")
            0 to body
        } catch (e: IOException) {
            body.setLength(0)
            body.append("E 0 ").append(quote("${e::class.simpleName}: ${e.message}")).append(" 0\n")
            0 to body
        }
    }

    private fun dumpLineSafe(sb: StringBuilder, lexer: ErbLexer, line: LogicalLine, rel: String) {
        try {
            sb.append("L ").append(line.lineNo).append(' ').append(line.kind.name).append(' ')
                .append(quote(line.content)).append('\n')
            tokensOf(lexer, line, rel).forEachIndexed { idx, t ->
                sb.append("T ").append(line.lineNo).append(' ').append(idx).append(' ')
                    .append(t.type.typeChar).append(' ').append(quote(t.text)).append(' ')
                    .append(t.line).append(' ').append(t.col).append('\n')
            }
        } catch (e: LexError) {
            sb.append("E ").append(line.lineNo).append(' ').append(quote(e.message ?: "")).append(' ')
                .append(e.column).append('\n')
        }
    }

    /**
     * 为什么不用 `tokenizeLine`：它只带 ALLOW_ASSIGNMENT，语料里 `'`（单引号字面量）会当场抛
     * 「予期しない文字」——那是**语句层**按上下文给 flags 的结果，不是词法能力。本夹具量的是
     * 「词法能力面」，所以统一给 [FLAGS] 并在 dump 头写明；语句层分片由 P2-2 另立对拍面。
     */
    private fun tokensOf(lexer: ErbLexer, line: LogicalLine, rel: String): List<Token> =
        when (line.kind) {
            LineKind.CODE -> lexer.analyse(CharStream(line.content), LexEndWith.EoL, FLAGS, line.lineNo)
            LineKind.FUNCTION_LABEL, LineKind.GOTO_LABEL -> {
                val (_, name, rest) = lexer.parseLabelLine(line.content, line.lineNo, rel)
                // 标签名合成一个 Identifier token（列号 1 = 跳过分隔符后的首字符），让 Rust 侧
                // 永远看到「完整一行的 token 流」，不必在比较器里补规则。
                listOf(Token.Identifier(name, line.lineNo, 1)) + rest
            }
            LineKind.SHARP -> {
                val (kw, rest) = lexer.parseSharpLine(line.content, line.lineNo, rel)
                listOf(Token.Identifier(kw, line.lineNo, 1)) + rest
            }
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
