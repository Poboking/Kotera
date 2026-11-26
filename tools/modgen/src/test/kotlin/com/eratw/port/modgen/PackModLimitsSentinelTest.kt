package com.eratw.port.modgen

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 哨兵：`pack-mod` 侧的 [PackLimits] 必须与 app 导入端 `ModZipGuard` **同源同值**。
 *
 * 构建工具不依赖 `:app`（方向不能倒过来），所以常量只能在 modgen 侧复制一份；本测试读
 * `app/src/main/kotlin/com/eratw/port/android/ModZipGuard.kt` 的**源码文本**，按定义形态抽数值
 * 与 `PackLimits` 的声明比对。任一侧改了数值而另一侧没跟上 ⇒ 这里红。
 *
 * 除数值外还钉住**判据形态**（与 guard 的 `>` / 边界口径一致，而非只看常量）：
 * - 条目数闸是「开条目时 `entries >= MAX_ENTRIES` 就拒」⇒ 恰好 512 条为上限；
 * - 体积闸是严格 `>` ⇒ 恰好等于上限放行；
 * - 压缩比先过 `RATIO_MIN_BYTES` 门槛，再用乘法 `MAX_COMPRESSION_RATIO * archiveBytes` 比较
 *   （不是整数除法，避免边界差一）。
 */
class PackModLimitsSentinelTest {

    @Test
    fun limitsStayInSyncWithAppGuardSource() {
        val source = shellSource(GUARD_SOURCE).readText()
        assertEquals(packInt(source, ENTRIES_DEF, "MAX_ENTRIES"), PackLimits.MAX_ENTRIES)
        assertEquals(productOf(source, ENTRY_BYTES_DEF, "MAX_ENTRY_BYTES"), PackLimits.MAX_ENTRY_BYTES)
        assertEquals(productOf(source, TOTAL_BYTES_DEF, "MAX_TOTAL_BYTES"), PackLimits.MAX_TOTAL_BYTES)
        assertEquals(packLong(source, RATIO_DEF, "MAX_COMPRESSION_RATIO"), PackLimits.MAX_COMPRESSION_RATIO)
        assertEquals(productOf(source, RATIO_MIN_DEF, "RATIO_MIN_BYTES"), PackLimits.RATIO_MIN_BYTES)
        assertEquals(productOf(source, BYTES_PER_MIB_DEF, "BYTES_PER_MIB"), PackLimits.BYTES_PER_MIB)
    }

    @Test
    fun guardDecisionFormsMatchPackSideSemantics() {
        val source = shellSource(GUARD_SOURCE).readText()
        assertTrue(
            ENTRY_COUNT_CLAUSE.containsMatchIn(source),
            "guard 的条目数判据形态变了（不再是开条目时 entries >= MAX_ENTRIES），PackLimits 的边界口径要跟着核",
        )
        assertTrue(
            ENTRY_SIZE_CLAUSE.containsMatchIn(source),
            "guard 的单条目判据形态变了（不再是 entryBytes > MAX_ENTRY_BYTES）",
        )
        assertTrue(
            TOTAL_SIZE_CLAUSE.containsMatchIn(source),
            "guard 的整包判据形态变了（不再是 totalBytes > MAX_TOTAL_BYTES）",
        )
        assertTrue(
            RATIO_CLAUSE.containsMatchIn(source),
            "guard 的压缩比判据形态变了（不再是 totalBytes > RATIO_MIN_BYTES && " +
                "totalBytes > MAX_COMPRESSION_RATIO * archiveBytes）",
        )
    }

    private fun packInt(text: String, regex: Regex, label: String): Int =
        assertNotNull(regex.find(text), "源码里找不到常量定义：$label").groupValues[1].toInt()

    private fun packLong(text: String, regex: Regex, label: String): Long =
        assertNotNull(regex.find(text), "源码里找不到常量定义：$label").groupValues[1].toLong()

    private fun productOf(text: String, regex: Regex, label: String): Long {
        val match = assertNotNull(regex.find(text), "源码里找不到常量定义：$label")
        return match.groupValues.drop(1).map { it.toLong() }.reduce { acc, value -> acc * value }
    }

    private companion object {
        /** 待比对真源：app 侧的导入闸（只读，本波不许改）。 */
        const val GUARD_SOURCE = "app/src/main/kotlin/com/eratw/port/android/ModZipGuard.kt"

        /** 类型标注在 guard 里是省略的，仍容忍显式写法，只认定义形态与数值。 */
        private const val OPT_TYPE = """(?:: (?:Int|Long))?"""

        val ENTRIES_DEF = Regex("""const val MAX_ENTRIES$OPT_TYPE = (\d+)""")
        val ENTRY_BYTES_DEF = Regex("""const val MAX_ENTRY_BYTES$OPT_TYPE = (\d+)L \* (\d+)L \* (\d+)L""")
        val TOTAL_BYTES_DEF = Regex("""const val MAX_TOTAL_BYTES$OPT_TYPE = (\d+)L \* (\d+)L \* (\d+)L""")
        val RATIO_DEF = Regex("""const val MAX_COMPRESSION_RATIO$OPT_TYPE = (\d+)L""")
        val RATIO_MIN_DEF = Regex("""const val RATIO_MIN_BYTES$OPT_TYPE = (\d+)L \* (\d+)L \* (\d+)L""")
        val BYTES_PER_MIB_DEF = Regex("""const val BYTES_PER_MIB$OPT_TYPE = (\d+)L \* (\d+)L""")

        val ENTRY_COUNT_CLAUSE = Regex("""entries >= MAX_ENTRIES""")
        val ENTRY_SIZE_CLAUSE = Regex("""entryBytes > MAX_ENTRY_BYTES""")
        val TOTAL_SIZE_CLAUSE = Regex("""totalBytes > MAX_TOTAL_BYTES""")
        val RATIO_CLAUSE = Regex(
            """totalBytes > RATIO_MIN_BYTES &&\s*totalBytes > MAX_COMPRESSION_RATIO \* archiveBytes""",
        )
    }
}
