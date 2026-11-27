package com.eratw.port.erbcc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.DimFoldArg
import com.eratw.port.core.vars.DimFunctionFolders

/**
 * `dump-fold` 子命令：导出**常量折叠的探针表**（CONTRACT-RUSTFULL §6.3）。
 *
 * 为什么不是「可折名单」而是「输入 → 结果」：可折叠性（原版 `CanRestructure`）**不在**
 * `Builtins.registry` 里 —— 它是 `LocalLayout.kt:188-201` 经 `functionFolder` 注入的
 * 运行期回调（`DimFunctionFolders.pureStatic`，`UserVariables.kt:185-203`）。从注册表
 * 反推「哪些函数可折」等于凭空造第二份真源，注册面一变就分叉。所以这里**真调折叠器**
 * 并把结果记下来：Rust 侧要复刻的是「同一输入折出同一值」。
 *
 * 覆盖折叠器的全部分支（`PureStaticDimFunctionFolder.foldInt/foldStr`）：
 * `VARSIZE` / `POWER` / `COLOR_FROMNAME` / `GETNUM` / `STRLENSU`（int）、`UNICODE` /
 * `GETLINESTR`（str）。运算符 `<<`/`>>` 不在这里（它们在 `DimParser` 的 Folder）。
 *
 * 探针用**缺省参数**的折叠器（`varSizes`/`keywordTables` 空、`consoleWidth` 缺省）：
 * 因此依赖变量表的用例（`VARSIZE`、`GETNUM`）返回「不折叠」—— 那本身也是要在 Rust 侧
 * 复刻的行为（没表就不折，而不是伪造 0）。
 *
 * 结果列：`folded:<值>` / `null`（不折叠）/ `throw:<异常类名>`（折叠路径上抛，如 `transparent`）。
 */
internal fun cmdDumpFold(args: List<String>): Int =
    emitDumpTable(parseDumpOptions(args, "dump-fold"), ::foldProbeTableText, "常量折叠探针")

/** 折叠入口：`foldInt` / `foldStr`。 */
private enum class Entry { INT, STR }

/**
 * 一条探针。实参用**数据串**写（`i:2|i:10`、`s:red`、`v:FLAG|i:0`），
 * 不在代码里散布裸数字：表里放的是「测试向量」，读的人一眼能看出输入是什么。
 */
private class FoldProbe(val fn: String, val entry: Entry, val spec: String)

private fun tokToArg(tok: String): DimFoldArg {
    val sep = tok.indexOf(':')
    require(sep > 0) { "探针实参写法应为 <kind>:<值>：$tok" }
    val kind = tok.substring(0, sep)
    val v = tok.substring(sep + 1)
    return when (kind) {
        "i" -> DimFoldArg.IntArg(v.toLong())
        "s" -> DimFoldArg.StrArg(v)
        "v" -> DimFoldArg.IdentArg(v)
        else -> error("未知实参种类：$kind")
    }
}

private fun tokToText(tok: String): String {
    val kind = tok.substring(0, tok.indexOf(':'))
    val v = tok.substring(tok.indexOf(':') + 1)
    return if (kind == "s") "\"$v\"" else v
}

/** 探针清单（20 条，覆盖 7 个可折名字的边界与失败方向）。 */
private val PROBES: List<FoldProbe> = listOf(
    // POWER：Math.Pow 语义（spec FunctionMethodCreator.cs:4172-4190）
    FoldProbe("POWER", Entry.INT, "i:2|i:10"),
    FoldProbe("POWER", Entry.INT, "i:2|i:0"),
    FoldProbe("POWER", Entry.INT, "i:-2|i:3"),
    FoldProbe("POWER", Entry.INT, "i:10|i:-1"), // 0.1 → 向零截断 0
    // COLOR_FROMNAME：Color.FromName（:3817-3835）
    FoldProbe("COLOR_FROMNAME", Entry.INT, "s:red"),
    FoldProbe("COLOR_FROMNAME", Entry.INT, "s:Red"), // 大小写不敏感
    FoldProbe("COLOR_FROMNAME", Entry.INT, "s:lightskyblue"),
    FoldProbe("COLOR_FROMNAME", Entry.INT, "s:transparent"), // 抛 CodeEE
    FoldProbe("COLOR_FROMNAME", Entry.INT, "s:nosuchcolor"), // -1
    // STRLENSU：UTF-16 码元数（:5275-5288）
    FoldProbe("STRLENSU", Entry.INT, "s:abc"),
    FoldProbe("STRLENSU", Entry.INT, "s:あいう"),
    FoldProbe("STRLENSU", Entry.INT, "s:😀"), // 代理对 = 2 码元
    // VARSIZE：无尺寸表 ⇒ 不折叠（:3441-3473）
    FoldProbe("VARSIZE", Entry.INT, "v:FLAG|i:0"),
    FoldProbe("VARSIZE", Entry.INT, "v:NOSUCHVAR|i:0"),
    // GETNUM：无关键字表 ⇒ 不折叠（:4814-4832 + ConstantData.cs:849-871）
    FoldProbe("GETNUM", Entry.INT, "v:TALENT|s:普通"),
    // UNICODE：低 16 位码元（:5700-5720）；越界抛
    FoldProbe("UNICODE", Entry.STR, "i:65"),
    FoldProbe("UNICODE", Entry.STR, "i:12354"), // U+3042 あ
    FoldProbe("UNICODE", Entry.STR, "i:65535"), // U+FFFF（上限）
    FoldProbe("UNICODE", Entry.STR, "i:1114112"), // 越界 → 抛
    // GETLINESTR：按 consoleWidth 填充（:3453 口径，缺省宽度）
    FoldProbe("GETLINESTR", Entry.STR, "s:abc"),
)

/** 表体（抽成函数供测试直接取）。 */
internal fun foldProbeTableText(): String {
    val folder = DimFunctionFolders.pureStatic()
    val sb = StringBuilder()
    sb.append("# erbcc dump-fold v1 —— 常量折叠探针（真调 DimFunctionFolders.pureStatic() 缺省参数）\n")
    sb.append("# 结果列: folded:<值> / null（不折叠）/ throw:<异常类简单名>\n")
    sb.append("# fn\tentry\targs\tresult\n")
    for (p in PROBES) {
        val toks = p.spec.split('|')
        val args = toks.map(::tokToArg)
        val argsText = toks.joinToString(",") { tokToText(it) }
        val result = try {
            val v = if (p.entry == Entry.INT) {
                folder.foldInt(p.fn, args)?.toString()
            } else {
                folder.foldStr(p.fn, args)
            }
            if (v == null) "null" else "folded:$v"
        } catch (e: CodeEE) {
            // 折叠路径上的**规定报错**（如 `COLOR_FROMNAME("transparent")`、`UNICODE` 越界）：
            // Rust 侧要复刻「这个输入必须报错」而不是算出值。其它异常不许吞：那是缺陷。
            "throw:${e.javaClass.simpleName}"
        }
        sb.append(p.fn).append('\t').append(p.entry.name.lowercase()).append('\t')
            .append(argsText).append('\t').append(result.replace('\t', ' ')).append('\n')
    }
    return sb.toString()
}
