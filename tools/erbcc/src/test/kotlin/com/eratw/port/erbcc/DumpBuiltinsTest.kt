package com.eratw.port.erbcc

import com.eratw.port.core.fn.Builtins
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `dump-builtins` 的形状闸：Rust 腿读这张表当年装载期判据，表少一条/多一条/列数变了
 * 都会让 Rust 侧静默按错口径跑，所以这里钉住三件事 —— 覆盖全、排序口径、列数。
 */
class DumpBuiltinsTest {

    private fun rows(): List<String> =
        builtinsTableText().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()

    @Test
    fun covers_registry_exactly_once() {
        val names = rows().map { it.substringBefore('\t') }
        assertEquals(Builtins.registry.size(), names.size, "导出行数必须等于注册面条数")
        assertEquals(names.size, names.toSet().size, "不许重名（注册面本身就是大小写不敏感唯一）")
        assertTrue(names.contains("ABS"), "ABS 是高频内建函数，必须在表里")
        // 语句名（PRINTFORM 等）不在函数表里（在 OpcodeTable.kt:1083 的 StatementAlias）。
        // 这条是防止把两张表并成一张：Rust 侧按「函数表 / 指令表」两套口径消费。
        assertTrue(names.none { it == "PRINTFORM" }, "语句名不许混进函数表")
    }

    @Test
    fun sorted_by_utf16_order() {
        // 排序口径 = String.compareTo（UTF-16 码元序），与产物按字符串键排序同一条（K20）。
        val names = rows().map { it.substringBefore('\t') }
        assertEquals(names.sorted(), names, "表体必须按 UTF-16 序升序，否则同一注册面两次导出会抖")
    }

    @Test
    fun columns_and_placeholder_flag_consistent() {
        val expected = 8
        val placeholders = Builtins.registry.placeholderNames()
        rows().forEach { row ->
            val cols = row.split('\t')
            assertEquals(
                expected, cols.size,
                "列数必须是 $expected（name/ret/min/max/omit/variadic/placeholder/needsExprArgs）：$row",
            )
            val name = cols[0]
            val flag = cols[6]
            assertEquals(
                if (name in placeholders) "1" else "0", flag,
                "placeholder 位必须与 registry.placeholderNames() 一致：$name",
            )
            // 成对变参组只允许占位以外的真实现声明；声明了就必须是四个数（或 `-`）。
            val variadic = cols[5]
            if (variadic != "-") {
                assertEquals(4, variadic.split('/').size, "成对变参组必须四段：$name → $variadic")
            }
        }
    }

    @Test
    fun writes_file_and_reports_count() {
        val tmp = java.nio.file.Files.createTempDirectory("erbcc-dump-builtins").resolve("t.tsv").toFile()
        tmp.deleteOnExit()
        assertEquals(0, cmdDumpBuiltins(listOf("--out", tmp.absolutePath)))
        assertTrue(tmp.isFile && tmp.length() > 0, "落盘产物必须非空")
        assertEquals(builtinsTableText(), tmp.readText(), "落盘内容必须与内存表逐字节一致")
    }
}
