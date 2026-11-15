package com.eratw.port.render.displaylist

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DisplayList.prepare` 右沿语义：只裁**画布外像素**，不丢**段对象链**
 * （玩家反馈：点「会话」后，会话及同一列的游戏选项整批消失）。
 *
 * 原版 `ConsoleStyledString.cs:119,129-138` 由 GDI/Skia 裁画布，节点照常遍历、按钮对象
 * 与命中区仍在；移植侧旧实现在 `col >= clientWidth` 时直接 `break`，当前 run **及其后
 * 所有 run** 都不产 `DrawCmd`。等宽列网格上一旦某列算宽越过右沿，后面整批按钮
 * （含每一行同一列）就一起从屏上消失 —— 这不是「不可见的像素」，是「对象被丢弃」。
 *
 * 语料锚：`QOL_USERCOM.ERB` 的 `@COM_OPTION` 收尾行有
 * `<shape type='space' param='-{TOTAL_WIDTH_PX}px'>` 这种负位移段，把列游标拉回屏内
 * 后再画关闭按钮；负位移支本来就抢在判界之前处理，但**夹在溢出段与负位移段之间的
 * 普通 run** 会先撞上 `break`，于是负位移与整批按钮一起被丢掉。
 */
class DisplayListRightEdgeClipTest {

    private val width = 137

    private fun text(s: String, kind: RunKind = RunKind.TEXT, value: Any? = null) =
        TextRun(s, 0, null, 0, "msgothic", kind, buttonValue = value)

    private fun space(cells: Int) =
        TextRun("", 0, null, 0, "msgothic", RunKind.SHAPE, graphic = InlineGraphic.Space(cells))

    private fun row(runs: List<TextRun>) = DisplayRow(
        runs,
        cells = runs.sumOf { it.graphic?.widthCells ?: Grid.cells(it.text) },
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun render(runs: List<TextRun>): List<DrawCmd> {
        val dl = DisplayList(width)
        dl.submit(listOf(row(runs)))
        return dl.render()
    }

    @Test
    fun `右沿之外只裁像素不丢对象链 负位移拉回后整批按钮仍能画`() {
        val cmds = render(
            listOf(
                text("A".repeat(140)),                     // col 0 -> 140，越过右沿（画布裁到 137）
                text("X"),                                 // col 140：旧实现在这里 break
                space(-141),                              // 负 space 拉回 col 0（@COM_OPTION 收尾行同形）
                text("会话[300]", RunKind.BUTTON, 300L),
                text("扫除[410]", RunKind.BUTTON, 410L),
                text("学习[412]", RunKind.BUTTON, 412L),
            ),
        )
        val buttons = cmds.filter { it.kind == RunKind.BUTTON }
        assertEquals(
            3,
            buttons.size,
            "负位移把列游标拉回屏内后，后续 3 颗按钮必须仍生成 DrawCmd；" +
                "旧 break 会把它们连同负位移段一起整批丢掉（实际 colStart=${cmds.map { it.colStart }}）",
        )
        assertEquals(listOf(0, 9, 18), buttons.map { it.colStart }, "按钮列位从 0 起依次排开")
        assertTrue(cmds.none { it.colStart == 140 }, "起点在右沿之外的段不产绘制命令（对象链保留、像素仍裁）")
    }

    @Test
    fun `两行同构列网格 每一行的末列按钮都不会被整批丢掉`() {
        fun gridRow(): List<TextRun> {
            val runs = ArrayList<TextRun>()
            // 6 列 × 23 格 = 138 > 137：收尾时列游标越过右沿
            repeat(6) { i ->
                runs += text("选项[$i]", RunKind.BUTTON, i.toLong())
                runs += space(16)
            }
            // 夹一个普通 run（旧实现在这里 break），再负位移拉回，最后一颗「同一列」按钮
            runs += text("|")
            runs += space(-139)
            runs += text("会话[300]", RunKind.BUTTON, 300L)
            return runs
        }

        val dl = DisplayList(width)
        dl.submit(listOf(row(gridRow()), row(gridRow())))
        val cmds = dl.render()
        val perRowButtons = (0..1).map { r ->
            cmds.count { it.row == r && it.kind == RunKind.BUTTON }
        }
        assertEquals(
            listOf(7, 7),
            perRowButtons,
            "两行各 6 颗列按钮 + 1 颗回退按钮都在；旧 break 会在每一行都把后续按钮整批丢掉" +
                "（实际=${cmds.filter { it.kind == RunKind.BUTTON }.map { it.row to it.colStart }}）",
        )
    }

    @Test
    fun `右沿外的普通 run 游标照常推进 不影响后续负位移回退`() {
        val cmds = render(
            listOf(
                text("A".repeat(140)),
                text("B".repeat(10)), // 右沿外的普通段：不产命令但推进游标 10 格
                space(-150),          // 140 + 10 - 150 = 0
                text("会话[300]", RunKind.BUTTON, 300L),
            ),
        )
        assertEquals(1, cmds.count { it.kind == RunKind.BUTTON }, "回退后的按钮必须画得出")
        assertEquals(2, cmds.size, "只画首段（裁到 137）与回退后的按钮；右沿外 10 格不产命令")
    }
}
