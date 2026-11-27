package com.eratw.port.erbcc

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.StatementAliases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `dump-opcodes` 形状闸。Rust 腿按这张表生成 opcode 常量与别名解析，
 * 编号一错就整条产物错位（不是报错，是**跑出别的语句**），所以钉死「编号→名字」逐号对齐。
 */
class DumpOpcodesTest {

    private fun section(tag: String): List<List<String>> =
        opcodesTableText().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it[0] == tag }
            .toList()

    @Test
    fun opcode_section_is_dense_and_matches_names() {
        val rows = section("opcode")
        assertEquals(Opcode.NAMES.size, rows.size, "操作码段必须一条不漏")
        // 编号连续无空洞（tableswitch 的前提，OpcodeTable.kt 头部注释）
        assertEquals((0 until Opcode.NAMES.size).toList(), rows.map { it[1].toInt() })
        rows.forEachIndexed { i, r -> assertEquals(Opcode.NAMES[i], r[2], "idx=$i 名字必须与 NAMES 逐字相同") }
        // 内部指令段边界：0..INTERNAL_COUNT-1 全标 internal
        (0 until Opcode.INTERNAL_COUNT).forEach { assertEquals("internal", rows[it][3], "内部指令 #$it") }
    }

    @Test
    fun alias_section_covers_all_and_resolves_back() {
        val rows = section("alias")
        assertEquals(StatementAliases.ALL.size, rows.size, "别名一条不漏")
        val names = rows.map { it[1] }
        assertEquals(names.size, names.toSet().size, "别名不许重名（BY_NAME 是 Map 语义）")
        assertEquals(names.sorted(), names, "别名段必须按 UTF-16 序，产物才确定性")
        rows.forEach { r ->
            val op = r[2].toInt()
            assertEquals(
                r[1], StatementAliases.BY_NAME[r[1]]?.name,
                "导出的 ${r[1]} 必须能在注册面里回查（否则表与真源脱钩）",
            )
            assertTrue(op in 0 until Opcode.NAMES.size, "别名 ${r[1]} 的操作码 $op 越界")
        }
    }

    @Test
    fun kind_ranges_are_contiguous_and_hit_the_documented_bounds() {
        val rows = section("kind")
        assertEquals(Opcode.KIND_RANGES.size, rows.size)
        // 语句段 29..197 连续无缝：段与段首尾相接
        val sorted = rows.map { Pair(it[2].toInt(), it[3].toInt()) }.sortedBy { it.first }
        assertEquals(Opcode.INTERNAL_COUNT, sorted.first().first, "首个语句段必须从 INTERNAL_COUNT 起")
        sorted.zipWithNext().forEach { (a, b) -> assertEquals(a.second + 1, b.first, "kind 段之间不许有缝") }
        assertEquals(Opcode.MAX_STATEMENT_OPCODE, sorted.last().second, "末个语句段必须到 MAX_STATEMENT_OPCODE")
    }

    @Test
    fun writes_file_identical_to_memory_table() {
        val tmp = java.nio.file.Files.createTempDirectory("erbcc-dump-opcodes").resolve("o.tsv").toFile()
        tmp.deleteOnExit()
        assertEquals(0, cmdDumpOpcodes(listOf("--out", tmp.absolutePath)))
        assertEquals(opcodesTableText(), tmp.readText())
    }
}
