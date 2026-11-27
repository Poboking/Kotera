package com.eratw.port.erbcc

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.StatementAliases

/**
 * `dump-opcodes` 子命令：导出**指令/语句表**（CONTRACT-RUSTFULL §6.3）。
 *
 * 为什么要它：Rust 腿必须与 Kotlin 腿产出**逐号相同**的操作码编号 —— 那份产物要能被
 * core-vm 直接执行、也要能与 Kotlin 产物做 `--first-diff` 对拍。编号的真源是
 * `Opcode.NAMES`（`OpcodeTable.kt:882`，连续无空洞，`tableswitch` 的前提）与
 * `StatementAliases.ALL`（`OpcodeTable.kt:1005`，ERB 书写面的别名 → 操作码 + 变体）。
 * 语句名**不在**内建函数表里（`PRINTFORM` 是 `OpcodeTable.kt:1083` 的 `StatementAlias`），
 * 所以这张表不能并入 `dump-builtins`。
 *
 * 三张子表：
 * 1. `opcode`：`idx	 name	class`，class ∈ `internal` / kind 名（flow…other）/ `stack` / `extension`；
 * 2. `alias`：`name	opcode	variant	canonical`，按 UTF-16 序（K20 口径）；
 * 3. `kind`：`kind	lo	hi`，kind 段边界（含端点，29..197 首尾相接）。
 */
internal fun cmdDumpOpcodes(args: List<String>): Int =
    emitDumpTable(
        parseDumpOptions(args, "dump-opcodes"),
        ::opcodesTableText,
        "${Opcode.NAMES.size} 个操作码 / ${StatementAliases.ALL.size} 条别名 / " +
            "${Opcode.KIND_RANGES.size} 个 kind 段",
    )

/** 操作码的分类标签（与 VM 内部分段一致）。 */
private fun opcodeClass(op: Int): String {
    val kind = Opcode.kindOf(op)
    return when {
        Opcode.isInternal(op) -> "internal"
        Opcode.isStackOp(op) -> "stack"
        kind != null -> kind.name.lowercase()
        Opcode.isExtension(op) -> "extension"
        else -> "-"
    }
}

/** 表体（抽成函数供测试直接取）。 */
internal fun opcodesTableText(): String {
    val sb = StringBuilder()
    sb.append("# erbcc dump-opcodes v1 —— 指令/语句表（单源 = Opcode.NAMES + StatementAliases.ALL）\n")
    sb.append("# ISA 编号必须逐号一致：Rust 腿据此生成 opcode 常量、别名解析与反汇编名\n")
    sb.append("# const internalCount\t").append(Opcode.INTERNAL_COUNT).append('\n')
    sb.append("# const maxStatementOpcode\t").append(Opcode.MAX_STATEMENT_OPCODE).append('\n')
    sb.append("# const opcodeCount\t").append(Opcode.NAMES.size).append('\n')

    sb.append("# section: opcode\tidx\tname\tclass\n")
    Opcode.NAMES.forEachIndexed { idx, name ->
        sb.append("opcode\t").append(idx).append('\t').append(name).append('\t')
            .append(opcodeClass(idx)).append('\n')
    }

    sb.append("# section: alias\tname\topcode\tvariant\tcanonical\n")
    for (a in StatementAliases.ALL.sortedWith(compareBy { it.name })) {
        sb.append("alias\t").append(a.name).append('\t').append(a.opcode).append('\t')
            .append(a.variant).append('\t').append(a.canonical).append('\n')
    }

    sb.append("# section: kind\tkind\tlo\thi\n")
    for ((k, r) in Opcode.KIND_RANGES.entries.sortedBy { it.value.first }) {
        sb.append("kind\t").append(k.name.lowercase()).append('\t').append(r.first).append('\t')
            .append(r.last).append('\n')
    }
    return sb.toString()
}
