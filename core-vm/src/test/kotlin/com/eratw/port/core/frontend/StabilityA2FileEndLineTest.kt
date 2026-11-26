package com.eratw.port.core.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STAB-A2：未闭合预处理区（`[SKIPSTART]` 等）的收尾告警行号必须是**文件末行**。
 *
 * 原版锚 `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Loader/ErbLoader.cs:513`
 * `pPState.FileEnd(scriptPosition)` + `:169-180` `PPState.FileEnd(ScriptPosition? position)`
 * —— 带真实 `scriptPosition`。移植侧 `LabelTable.scanLabels` 此前把行号硬编码 0，
 * 定位失真。本波改为消费 `erhLogicalLineSequence` 时记录最后物理行号再传 `pp.fileEnd`。
 *
 * 同仓正确口径：`tools/erbcc/src/main/kotlin/com/eratw/port/erbcc/compile/CorpusScan.kt:292`
 * 的 `pp.fileEnd(lines.size)`。本文件断言 `line == 文件末行（非 0）`，并钉住闭合区不误报。
 */
class StabilityA2FileEndLineTest {

    @Test
    fun `未闭合 skipstart 告警行号等于文件末行`() {
        val warnings = ArrayList<LoadWarning>()
        val labels = LabelTable.scanLabels(
            """
            @VISIBLE
            RETURN
            [SKIPSTART]
            @SWALLOWED
            RETURN
            """.trimIndent(),
            file = "UNCLOSED.ERB",
            warnings = warnings,
        )

        assertEquals(listOf("VISIBLE"), labels.map { it.name }, "[SKIPSTART] 之后的标签不产出")
        assertEquals(1, warnings.size, "只报最内层未闭合区间一次")
        assertEquals("UNCLOSED.ERB", warnings[0].file)
        assertEquals(5, warnings[0].line, "告警行号必须是文件末行（5），不得硬编码 0")
        assertTrue(warnings[0].message.contains("SKIPEND"), "报缺少的闭合指令名，实得：${warnings[0].message}")
    }

    @Test
    fun `闭合的预处理区不产生未闭合告警`() {
        val warnings = ArrayList<LoadWarning>()
        val labels = LabelTable.scanLabels(
            """
            @VISIBLE
            [SKIPSTART]
            @SWALLOWED
            [SKIPEND]
            @AFTER
            """.trimIndent(),
            file = "CLOSED.ERB",
            warnings = warnings,
        )

        assertEquals(listOf("VISIBLE", "AFTER"), labels.map { it.name }, "闭合后标签正常产出")
        assertTrue(warnings.isEmpty(), "闭合区不得误报未闭合，实得：${warnings.map { it.message }}")
    }

    @Test
    fun `未闭合 if 区告警行号也取文件末行`() {
        val warnings = ArrayList<LoadWarning>()
        LabelTable.scanLabels(
            """
            @VISIBLE
            [IF UNDEFINED_MACRO]
            RETURN
            """.trimIndent(),
            file = "IFUNCLOSED.ERB",
            warnings = warnings,
        )

        assertEquals(1, warnings.size)
        assertEquals(3, warnings[0].line, "ELSEIF 报成 ENDIF，行号取文件末行（3）")
        assertTrue(warnings[0].message.contains("ENDIF"), "未闭合 IF 告警报缺少 ENDIF，实得：${warnings[0].message}")
    }
}
