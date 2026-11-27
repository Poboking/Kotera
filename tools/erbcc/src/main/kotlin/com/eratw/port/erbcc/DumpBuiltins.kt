package com.eratw.port.erbcc

import com.eratw.port.core.fn.Builtins

/**
 * `dump-builtins` 子命令：把内建函数的**语义表**从 core-vm 的活注册表导出成 TSV
 * （CONTRACT-RUSTFULL §6.3）。
 *
 * 为什么要有它：Rust 腿要按「名字 / 返回类型 / 参数个数上下界 / 省略位 / 成对变参组 /
 * 是否占位 / 是否需要未求值实参」这些属性做装载期检查与降级决策。这些属性的**唯一事实源**
 * 是 `Builtins.registry`（Kotlin 注册面），手抄一份进 Rust 就等于养第二份口径 —— 注册面
 * 一变，两张表就静默分叉。所以这里只做导出，Rust 读表（`erbcc-rs/src/tables.rs`）。
 *
 * 排序口径：`String.compareTo`（= UTF-16 码元序），与产物里按字符串键排序同一口径
 * （CONTRACT §6.2 K20）；同一注册面两次导出逐字节相同。
 *
 * 边界：本表**只有函数**。语句/命令（`PRINTFORM`、`IF`、`SIF`……）在 `Opcode`/`StatementAliases`
 * （`OpcodeTable.kt:1005`），由 `dump-opcodes` 导出；两者不许并成一张表。
 */
internal fun cmdDumpBuiltins(args: List<String>): Int =
    emitDumpTable(parseDumpOptions(args, "dump-builtins"), ::builtinsTableText, "内建函数")

/** 表体（抽成函数供测试直接取，不必落盘）。 */
internal fun builtinsTableText(): String {
    val reg = Builtins.registry
    val sb = StringBuilder()
    sb.append("# erbcc dump-builtins v1 —— 内建函数语义表（单源 = core-vm 的 Builtins.registry）\n")
    sb.append("# 列: name\tret\tminArgs\tmaxArgs\tomitStart\tvariadic\tplaceholder\tneedsExprArgs\n")
    sb.append("# variadic 记 `fixedPrefix/groupSize/exactFormArgs/minGroupArgs`，无则 `-`\n")
    sb.append("# 只覆盖函数；语句名见 dump-opcodes；查表一律 ASCII 大小写不敏感\n")
    for (name in reg.names().sorted()) {
        val m = reg[name] ?: continue
        val variadic = m.variadicGroup?.let {
            "${it.fixedPrefix}/${it.groupSize}/${it.exactFormArgs}/${it.minGroupArgs}"
        } ?: "-"
        sb.append(name).append('\t')
            .append(m.retType.name).append('\t')
            .append(m.minArgs).append('\t')
            .append(m.maxArgs).append('\t')
            .append(m.omitStart).append('\t')
            .append(variadic).append('\t')
            .append(if (reg.isPlaceholder(name)) "1" else "0").append('\t')
            .append(if (reg.needsExprArgs(name)) "1" else "0").append('\n')
    }
    return sb.toString()
}
