package com.eratw.port.erbcc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `dump-fold` 探针表的形状闸。表本身是 Rust 腿的**行为判据**（同输入同结果），
 * 所以这里钉三类东西：覆盖到折叠器的全部分支、结果编码合法、关键用例读数不变。
 */
class DumpFoldTest {

    private fun rows(): List<List<String>> =
        foldProbeTableText().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .toList()

    private fun result(fn: String, args: String): String {
        val row = rows().firstOrNull { it[0] == fn && it[2] == args }
            ?: error("探针缺失：$fn($args)")
        return row[3]
    }

    @Test
    fun covers_every_foldable_branch() {
        // 折叠器 foldInt 五个分支 + foldStr 两个分支（UserVariables.kt:232-241）
        val expected = setOf("VARSIZE", "POWER", "COLOR_FROMNAME", "GETNUM", "STRLENSU", "UNICODE", "GETLINESTR")
        assertEquals(expected, rows().map { it[0] }.toSet(), "七个可折名字必须全覆盖")
        assertEquals(4, rows().first().size, "四列：fn/entry/args/result")
    }

    @Test
    fun result_encoding_is_one_of_three_forms() {
        rows().forEach { r ->
            val v = r[3]
            assertTrue(
                v == "null" || v.startsWith("folded:") || v.startsWith("throw:"),
                "结果编码只允许 null / folded: / throw:，这条是 ${r[0]}(${r[2]}) → $v",
            )
        }
    }

    @Test
    fun key_readings_stay_stable() {
        assertEquals("folded:1024", result("POWER", "2,10"))
        assertEquals("folded:-8", result("POWER", "-2,3"))
        assertEquals("folded:0", result("POWER", "10,-1"), "0.1 向零截断为 0")
        // Color.FromName：命中折 (R<<16)+(G<<8)+B；transparent 抛；未知名 -1
        assertEquals("folded:16711680", result("COLOR_FROMNAME", "\"red\""), "red = 0xFF0000")
        assertEquals("throw:CodeEE", result("COLOR_FROMNAME", "\"transparent\""))
        assertEquals("folded:-1", result("COLOR_FROMNAME", "\"nosuchcolor\""))
        // STRLENSU 是 UTF-16 码元数：代理对算 2
        assertEquals("folded:2", result("STRLENSU", "\"😀\""))
        // 无表 ⇒ 不折叠（不是伪造 0）
        assertEquals("null", result("VARSIZE", "FLAG,0"))
        assertEquals("null", result("GETNUM", "TALENT,\"普通\""))
        // UNICODE 越界抛
        assertEquals("throw:CodeEE", result("UNICODE", "1114112"))
    }
}
