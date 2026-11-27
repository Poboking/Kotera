package com.eratw.port.erbcc

import com.eratw.port.core.stmt.BuiltinSymbols
import com.eratw.port.core.stmt.FunctionCode
import java.io.File

/**
 * `dump-funcodes` —— 语句标签表的**唯一导出面**（CONTRACT-RUSTFULL §6.10 P2-2a）。
 *
 * 为什么另立一张表而不是复用 `dump-opcodes`：`opcodes.tsv` 的 `alias` 行只覆盖 315 个语句名、
 * 其 `canonical` 列只有 168 个分组目标，而 `FunctionCode` 枚举有 317 项（2026-11-27 实测差集
 * 含 `CALLFORM`/`CASE`/`CLEARBIT`/`DEBUGPRINTFORM` 等）。拿它当「名字 → StmtTag」的真源会漏项，
 * 且漏项只在具体语料行上表现为「语句名查不到」，不会报错。
 *
 * 本表直接遍历活枚举与活 `BY_NAME`，Rust 侧只许读这张表建映射（AGENTS §5：表只许导出不许手抄）。
 *
 * 用法：`erbcc dump-funcodes [--out <tsv>]`
 *
 * 输出（LF、单空格分列）：
 * ```
 * #format erbcc-dump-funcodes 1
 * code <ordinal> <NAME> <ARG_TYPE> <FLAG> <KIND>
 * byname <KEY> <NAME>
 * ```
 */
internal object DumpFuncodes {
    private const val FORMAT = "erbcc-dump-funcodes 1"

    fun run(args: List<String>): Int {
        var outPath: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--out" -> {
                    outPath = args.getOrNull(i + 1)
                    if (outPath == null) {
                        System.err.println("错误: dump-funcodes --out 缺路径")
                        return 2
                    }
                    i++
                }
                "--help", "-h" -> {
                    println("usage: erbcc dump-funcodes [--out <tsv>]")
                    return 0
                }
                else -> {
                    System.err.println("错误: dump-funcodes 不认识的参数: ${args[i]}")
                    return 2
                }
            }
            i++
        }

        val sb = StringBuilder(1 shl 16)
        sb.append("#format ").append(FORMAT).append('\n')
        for (code in FunctionCode.entries) {
            sb.append("code ").append(code.ordinal).append(' ').append(code.name).append(' ')
                .append(code.argType.name).append(' ').append(code.flag).append(' ')
                .append(code.kind.name).append('\n')
        }
        for ((key, code) in FunctionCode.BY_NAME.entries.sortedBy { it.key }) {
            sb.append("byname ").append(key).append(' ').append(code.name).append('\n')
        }
        // 语句层分派还需要两张解析期符号表：内建方法名单（`parseCodeLine` 第 3 步的 METHOD 分支）
        // 与内建变量面（表达式层静态类型判定）。这两个集合都在 core-vm 里以字面表存在，
        // Rust 侧只许读这里导出的内容（AGENTS §5：表只许导出不许手抄）。
        for (name in BuiltinSymbols.methods.sorted()) {
            sb.append("method ").append(name).append('\n')
        }
        for ((key, spec) in BuiltinSymbols.variables.entries.sortedBy { it.key }) {
            sb.append("var ").append(key).append(' ').append(spec.name).append(' ')
                .append(spec.type.name).append(' ').append(spec.dimension).append(' ')
                .append(if (spec.isCharacterData) 1 else 0).append(' ')
                .append(if (spec.isConst) 1 else 0).append('\n')
        }

        val f = outPath?.let(::File)
        if (f == null) {
            print(sb)
        } else {
            f.absoluteFile.parentFile?.mkdirs()
            f.writeText(sb.toString())
            val codes = FunctionCode.entries.size
            val keys = FunctionCode.BY_NAME.size
            System.err.println("已导出 $codes 条语句定义 / $keys 条名字映射 → ${f.absolutePath}")
        }
        return 0
    }
}
