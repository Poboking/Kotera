package com.eratw.port.render.view

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.OptionOrigin
import com.eratw.port.core.host.OptionVisual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LeanOptionFilter] 的口径钉子（2026-10-27 立，2026-10-31 按玩家反馈补「编号在开头」那一形）：
 *
 * 本类只回答「这枚候选在屏上印了自己的编号吗」。消费点是右栏的两条规则
 * （`RailOptionRules.filterOptions` 的「隐藏无编号」与历史候选判据 `pinnableMaskOf`；
 * 长按菜单的置顶入口**不**走这条 —— 那条收全部非色块 `pinMenuMaskOf`），
 * 所以这里逐条钉判据本身：正例必须认得出、反例必须剔除。
 * 「调色板豁免」不在本类 —— 那是 `paletteSwatchMask` 在调用侧单独判的，见 `RailOptionRulesTest`。
 *
 * 两种形态都要认（真语料实测）：
 * - 编号在末尾 `移动[400]`：标签原文带着走，纯文本规则就认得出；
 * - 编号在开头 `[100] - 睁开眼睛`：引擎出快照时把编号剥给徽标了，标签只剩 `- 睁开眼睛`，
 *   必须靠 [ConsoleOption.printsOwnNumber] 才认得出 —— 玩家反馈「右边选项里经常找不到
 *   400 移动，经常得直接在左边点」缺的就是这一形（主页面 28 枚候选旧口径全被剔）。
 *
 * 裸 `[31]`（纯数字自动按钮、或前缀被剥空、名字没了的按钮）两种口径下都不算常用指令。
 */
class LeanOptionFilterTest {

    @Suppress("LongParameterList")
    private fun option(
        label: String,
        value: Any = 400L,
        printsOwnNumber: Boolean = false,
        colorRgb: Int? = null,
        lineSerial: Long = 0L,
        visual: OptionVisual = OptionVisual.TEXT,
    ): ConsoleOption = ConsoleOption(
        value = value,
        label = label,
        origin = OptionOrigin.HTML,
        lineSerial = lineSerial,
        printsOwnNumber = printsOwnNumber,
        colorRgb = colorRgb,
        visual = visual,
    )

    @Test
    fun keepsCommandsWhoseNumberIsPrintedAfterTheName() {
        val labels = listOf("会话[31]", "移动[400]", "一键扫除[778]", "CONFIG[810]", "会话 [31]")
        labels.forEach { label ->
            assertTrue(LeanOptionFilter.hasTrailingNumberTag(label), "应保留：$label")
            assertTrue(
                LeanOptionFilter.isCommonCommand(option(label)),
                "末尾形态应进栏：$label",
            )
        }
    }

    /** 真机 2026-10-31 主页面读数：`[100] - 睁开眼睛` 一族，编号已被剥成徽标。 */
    @Test
    fun keepsCommandsWhoseNumberIsPrintedBeforeTheName() {
        val leading = listOf(
            option("- 睁开眼睛", 100L, printsOwnNumber = true),
            option("- OPTION", 400L, printsOwnNumber = true),
            option("- 特殊区域移动", 216L, printsOwnNumber = true),
            option("开始游戏", 0L, printsOwnNumber = true),
            // 编号超出徽标量程（0..999）时引擎不剥前缀，标签原文留着，照样是自带编号的指令。
            option("[2000] 能力编辑", 2000L, printsOwnNumber = true),
        )
        leading.forEach { option ->
            assertTrue(LeanOptionFilter.isCommonCommand(option), "开头形态应进栏：${option.label}")
        }
    }

    @Test
    fun dropsOptionsWithoutPrintedNumber() {
        val labels = listOf(
            "[CONFIG]",     // 只有文字、没有编号
            "退出",          // 无编号
            "开始游戏",      // 屏上没印编号的形态：徽标是提交值兜底出来的，printsOwnNumber=false
            "[＋]",         // 括号里不是数字
            "第[1]章",      // 编号不在末尾，也没被剥成徽标
            "选项[abc]",    // 非数字
            "选项[]",       // 空编号
            "",             // 空标签
            "   ",          // 纯空白
        )
        labels.forEach { label ->
            assertFalse(LeanOptionFilter.isCommonCommand(option(label)), "应剔除：$label")
        }
    }

    @Test
    fun dropsBareNumericButton() {
        // 裸 `[31]`（纯数字自动按钮）没有名字在前，不算常用指令。
        assertFalse(LeanOptionFilter.hasTrailingNumberTag("[31]"))
        assertFalse(LeanOptionFilter.isCommonCommand(option("[31]", 31L)))
        // 前缀被剥空的同一颗按钮：标签没了，编号还在 —— 一样不进栏。
        assertFalse(LeanOptionFilter.isCommonCommand(option("", 31L, printsOwnNumber = true)))
    }

    /**
     * 布娃娃调色板的色块既没有名字也没有编号 ⇒ 判据判它**不是**常用指令。
     * 「真调色板不许被整片裁掉」是调用侧另一件事（`paletteSwatchMask` 豁免），
     * 那里另有钉子，见 `RailOptionRulesTest`。
     */
    @Test
    fun colorSwatchesAreNotCommonCommands() {
        val palette = (0 until 128).map { i ->
            option("■", 80000L + i, colorRgb = (0x010203 * i) and 0xFFFFFF, visual = OptionVisual.COLOR_SWATCH)
        }
        assertTrue(palette.all { it.isColorSwatch })
        assertFalse(palette.any { LeanOptionFilter.isCommonCommand(it) }, "色块不是常用指令")
    }

    /**
     * 玩家点名的那一屏，从引擎真实读数一路钉到右栏候选（2026-10-31 反馈
     * 「右边选项里经常找不到 400 移动，经常得直接在左边点」）。
     *
     * 出处是移动指令 `@QOL_COM400` 的目的地清单：语料 `魔改内容/qol/qol_MAP.ERB:152`
     * `PRINTL [  0] - 返回私室`、`:159` `PRINTFORML [{LOCAL, 3}] - %NAME_FROM_PLACE(LOCAL)%`、
     * `:176` `PRINTFORM [{BUTTON返回, 3}] - 返回`。原版「一行里只有一个 `[n]` 时整行都可点」
     * （`ButtonStringCreator.cs:63-73`）⇒ 编号写在名字**前面**且等于提交值 ⇒ 引擎出快照时
     * 把它剥给徽标，标签只剩 `- 地名` —— 旧口径按「末尾才有数字标签」判，整屏目的地全被剔，
     * 右栏空着，玩家只能回正文里点。
     */
    @Test
    fun keepsTheTravelDestinationListFromTheEngineSnapshot() {
        val sink = LineBufferConsoleSink(clientWidth = 110, maxLines = 200)
        sink.printLine("[  0] - 返回私室")
        sink.printLine("[123] - 博丽神社")
        sink.printLine("[400] - 返回　　　")
        sink.noteInputWait(
            InputRequest(type = InputRequest.InputType.INT, form = InputRequest.RequestForm.VALUE),
        )

        val recognized = sink.currentOptions()
        assertEquals(3, recognized.size, "三行目的地各一枚按钮；实际=$recognized")
        assertEquals(
            listOf("- 返回私室", "- 博丽神社", "- 返回"),
            recognized.map { it.label },
            "行首编号已剥给徽标（徽标口径不变），本句要的是这件事别让右栏整屏空",
        )
        recognized.forEach { option ->
            assertTrue(
                LeanOptionFilter.isCommonCommand(option),
                "三枚目的地都该认成常用指令：${option.label}",
            )
        }
    }

    @Test
    fun ordinaryColoredButtonIsNotCommonJustBecauseItHasColor() {
        val colored = option("普通选项", colorRgb = 0x112233)
        assertFalse(LeanOptionFilter.isCommonCommand(colored))
    }

}
