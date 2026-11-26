package com.eratw.port.core.frontend

import com.eratw.port.core.proc.SystemVariableBootstrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STAB-A1：装载期标签扫描对「无归属 `#` 行」必须复刻原版的「发 level-1 告警后仍丢弃」。
 *
 * 原版锚 `reference/emuera-cs/MinorShift.Emuera.Runtime.Script.Loader/ErbLoader.cs:413-423`：
 * `if (charStream.Current == '#')` 且 `logicalLine2` 不是 `FunctionLabelLine` 时
 * `ParserMediator.Warn(Lang.Error.InvalidSharp.Text, scriptPosition, 1)`，随后 `continue`
 * 丢弃该行。移植侧 `LabelTable.scanLabels` 此前只 `continue`、不发告警，属忠实复刻缺口。
 *
 * 本文件把三件事钉死：
 *  1. 每条无归属 `#` 行产生一条 InvalidSharp 告警（行号 = 该行物理行号）；
 *  2. 该行仍被丢弃 —— 产物标签集合与 dimSpecs 不变（丢弃行为不改）；
 *  3. 紧跟 `@` 函数声明的合法 `#` 行不误报（curPos 有效时走原有分派）。
 *
 * 文案单源：断言直接引用 [SystemVariableBootstrap.INVALID_SHARP_REASON]（通用 `#` 行文案，
 * 与 #DIM 专用的 ERB_FUNCTION_DIM_REASON 分工），防止出现第二份玩家可见字面量。
 */
class StabilityA1InvalidSharpWarnTest {

    @Test
    fun `无归属 sharp 行产生一条告警且行号正确`() {
        val warnings = ArrayList<LoadWarning>()
        val labels = LabelTable.scanLabels(
            """
            #DIM ORPHAN, 5
            @TARGET
            RETURN
            """.trimIndent(),
            file = "ORPHAN.ERB",
            warnings = warnings,
        )

        assertEquals(listOf("TARGET"), labels.map { it.name }, "无归属 # 行不得改动产物标签集合")
        assertEquals(1, warnings.size, "每条无归属 # 行一条 level 1 告警，实得：${warnings.map { it.message }}")
        assertEquals("ORPHAN.ERB", warnings[0].file, "告警文件定位到扫描源")
        assertEquals(1, warnings[0].line, "告警行号 = 无归属 # 行所在物理行")
        assertEquals(
            SystemVariableBootstrap.INVALID_SHARP_REASON,
            warnings[0].message,
            "通用 # 行文案复用单源常量，不写第二份字面量",
        )
        assertTrue(warnings[0].message.contains("InvalidSharp"), "留痕带原版错误名")
    }

    @Test
    fun `无归属 sharp 行仍被丢弃不改产物`() {
        val warnings = ArrayList<LoadWarning>()
        val labels = LabelTable.scanLabels(
            """
            #DIM ORPHAN, 5
            @TARGET
            RETURN
            #LOCALSIZE 100
            RETURN
            """.trimIndent(),
            file = "DROP.ERB",
            warnings = warnings,
        )

        // 两行无归属 #（第 1、4 行）：@TARGET 之后先被普通行 RETURN 清掉 curPos，
        // 于是第 4 行 #LOCALSIZE 也无归属 —— 两条都发告警、两条都丢弃。
        assertEquals(2, warnings.size, "两条无归属 # 行各发一条告警，实得：${warnings.map { it.line }}")
        assertEquals(listOf(1, 4), warnings.map { it.line }, "行号按各自物理行")
        assertEquals(1, labels.size, "产物标签集合不变")
        assertEquals("TARGET", labels[0].name)
        assertTrue(labels[0].dimSpecs.isEmpty(), "被丢弃的 #DIM 不得注册进标签")
        assertEquals(0, labels[0].localSize, "被丢弃的 #LOCALSIZE 不得生效")
    }

    @Test
    fun `无归属 PRI 行也告警且文案不含 DIM 字样`() {
        val warnings = ArrayList<LoadWarning>()
        val labels = LabelTable.scanLabels(
            """
            #PRI
            @TARGET
            RETURN
            """.trimIndent(),
            file = "PRI_ORPHAN.ERB",
            warnings = warnings,
        )

        // 原版 InvalidSharp 是通用文案（emuera-zhs.xml:562「#行只能在函数声明后立刻使用」），
        // 不带 #DIM 字样 —— #PRI 这类非 #DIM 指令不得套用 #DIM 专用丢弃理由。
        assertEquals(listOf("TARGET"), labels.map { it.name }, "无归属 #PRI 行仍被丢弃")
        assertEquals(1, warnings.size, "无归属 #PRI 行一条告警，实得：${warnings.map { it.message }}")
        assertEquals(1, warnings[0].line, "告警行号 = 该行物理行")
        assertEquals(SystemVariableBootstrap.INVALID_SHARP_REASON, warnings[0].message, "通用文案单源")
        assertFalse(warnings[0].message.contains("#DIM"), "通用 # 行告警不得张冠李戴套用 #DIM 文案")
        assertTrue(warnings[0].message.contains("InvalidSharp"), "仍带原版错误名")
    }

    @Test
    fun `紧跟函数声明的合法 sharp 行不误报`() {
        val warnings = ArrayList<LoadWarning>()
        val labels = LabelTable.scanLabels(
            """
            @TARGET
            #PRI
            RETURN
            """.trimIndent(),
            file = "LEGAL.ERB",
            warnings = warnings,
        )

        assertEquals(listOf("TARGET"), labels.map { it.name })
        assertTrue(labels[0].isPri, "合法 #PRI 仍要按原有分派生效")
        assertTrue(warnings.isEmpty(), "紧跟函数声明的 # 行不得误报 InvalidSharp，实得：${warnings.map { it.message }}")
    }
}
