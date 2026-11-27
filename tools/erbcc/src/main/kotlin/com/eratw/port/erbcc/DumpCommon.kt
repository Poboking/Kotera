package com.eratw.port.erbcc

import java.io.File

/**
 * 三个 `dump-*` 子命令的共同参数面与出口（`dump-builtins`/`dump-opcodes`/`dump-fold`）。
 *
 * 抽出来的原因不是「少写几行」，而是三条命令的参数语义必须一致：都只认 `--out <tsv>`，
 * 都以「缺 `--out` 就写 stdout」为默认，都在参数错时给同一形状的错误并返回 2。
 * 各命令只负责**表体**（各自的事实源不同），参数与落盘不各写一份。
 */
internal sealed interface DumpParse {
    /** 参数合法；`outPath == null` 表示写 stdout。 */
    data class Ok(val outPath: String?) : DumpParse

    /** `--help`：打印用法并成功退出。 */
    data class Help(val text: String) : DumpParse

    /** 参数非法：错误已上屏，返回 2。 */
    data object Bad : DumpParse
}

/** 解析 `dump-*` 的参数（只认 `--out <path>` 与 `--help`）。 */
internal fun parseDumpOptions(args: List<String>, cmd: String): DumpParse {
    var outPath: String? = null
    var wantHelp = false
    var bad: String? = null
    var i = 0
    while (i < args.size && bad == null) {
        when (val a = args[i]) {
            "--out" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    bad = "--out 缺路径"
                } else {
                    outPath = v
                    i++
                }
            }
            "--help", "-h" -> wantHelp = true
            else -> bad = "不认识的参数: $a"
        }
        i++
    }
    return when {
        bad != null -> {
            System.err.println("错误: $cmd $bad")
            DumpParse.Bad
        }
        wantHelp -> DumpParse.Help("usage: erbcc $cmd [--out <tsv>]")
        else -> DumpParse.Ok(outPath)
    }
}

/** 统一出口：`--help` 打印用法、参数错返回 2、正常则 stdout 或落盘并返回 0。 */
internal fun emitDumpTable(parse: DumpParse, text: () -> String, detail: String): Int =
    when (parse) {
        is DumpParse.Help -> {
            println(parse.text)
            0
        }
        DumpParse.Bad -> 2
        is DumpParse.Ok -> writeOrPrint(parse.outPath, text(), detail)
    }

private fun writeOrPrint(path: String?, t: String, detail: String): Int {
    val f = path?.let(::File)
    if (f == null) {
        print(t)
    } else {
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(t)
        // 数据行数不含 `#` 注释行：调用方据此核对「注册面大小 ↔ 导出条数」
        val rows = t.lineSequence().count { it.isNotEmpty() && !it.startsWith("#") }
        System.err.println("已导出 $rows 行（$detail）→ ${f.absolutePath}")
    }
    return 0
}
