package com.eratw.port.render.button

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 行内口径的长按提示（`ButtonLayer.tooltipInline`）—— **素质 / 种族那类不可点文本的
 * 长按提示回归锁**（玩家反馈：「属性里的素质好像不能长按查看了？0.2.44版本」）。
 *
 * ## 缺陷现场
 *
 * 素质屏由语料 `ERB/fromEN/HTML_TALENTS/HTML_TALENTS.ERB` 的 `MO_TALENT` 打成
 * `<font color='..'><nonbutton title='..'>[素质]</nonbutton></font>`，经 `HTML_PRINT`
 * 落到**正文行**（不是 div 子行）；提示只挂在**不可点**文本上，该行没有任何可点按钮。
 *
 * 原版这条路是通的：`EmueraConsole.cs:2368` 按**每个节点自己的** `Top/Bottom` 判纵盒，
 * 判通过就把该节点所属串记成光标候选（`:2373-2379`，**非按钮节点同样记**），
 * `:1891-1895` 的 ToolTip 因此能从非按钮串上取到 `Title`；文本节点的缺省盒是
 * `AConsoleDisplayNode.cs:29-31` 的 `Top = 0 / Bottom = Config.FontSize`，语料口径
 * `LINE HEIGHT == FONT SIZE == 18` 下就是整行带。
 *
 * 端口丢提示的机制不是「没有提示数据」：[ButtonLayerTest] 的
 * 「不可点文本也能带提示且不变成可点」已钉住 [ButtonLayer.tooltip] 查得到；丢在**壳体把
 * 点击面的可点性判据复用到提示面** —— `CanvasConsoleView.tooltipAt` 曾用 `hitInline`（只认识
 * **可点**目标）判「该点有没有节点」，纯文本行恒判空，于是整行提示被 return null 掉，
 * 而可点按钮的提示照旧正常（玩家感知即「只有素质看不了」）。
 *
 * 本夹具把正确判据钉在渲染层：纵门只跟**提示所属节点**自己的盒走，与「可不可点」无关。
 * 下面第一组里的 `hitInline == null` 就是那条错误判据的**反向证明**载体。
 */
class TooltipInlineTest {

    private fun row(vararg runs: TextRun) =
        DisplayRow(runs.toList(), runs.sumOf { Grid.cells(it.text) }, true, true)

    private fun text(s: String, tooltip: String? = null) =
        TextRun(s, 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.TEXT, null, tooltip = tooltip)

    /**
     * 素质屏形态：整行只有不可点文本，`title` 挂在它上面。
     *
     * - `tooltip` 查得到（数据面，`ButtonLayerTest` 已钉）
     * - `hitInline` 恒 null（**旧壳体判据的反向证明**：拿可点性当纵门 → 整行判空）
     * - `tooltipInline` 必须查得到（本笔落地的正确判据）
     */
    @Test
    fun `不可点文本的素质提示在行内口径下仍查得到`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(text("[阿求]", tooltip = "種族：人類<br>能力：知性"))))

        assertEquals("種族：人類<br>能力：知性", layer.tooltip(0, 0), "提示区间本身在不可点文本上")
        assertEquals("種族：人類<br>能力：知性", layer.tooltip(0, 2))

        // 反向证明：旧壳体那道门用的就是这条查询 —— 纯文本行恒 null。
        assertNull(layer.hitInline(0, 1, yInRowPx = 9f, fontSizePx = 18), "不可点文本不进命中表，hitInline 恒 null")
        assertNull(layer.hit(0, 1), "提示不得让不可点文本变成可点")

        // 正确判据：文本节点走缺省盒 [0, FontSize]，语料口径下整行带 → 行内任意 y 都查得到。
        assertEquals("種族：人類<br>能力：知性", layer.tooltipInline(0, 1, yInRowPx = 0f, fontSizePx = 18))
        assertEquals("種族：人類<br>能力：知性", layer.tooltipInline(0, 1, yInRowPx = 9f, fontSizePx = 18))
        assertEquals("種族：人類<br>能力：知性", layer.tooltipInline(0, 1, yInRowPx = 18f, fontSizePx = 18))
    }

    /** 行外列 / 行外行 / 负坐标：一律 null（与 [ButtonLayer.tooltip] 同一道区间门）。 */
    @Test
    fun `行外列与非法坐标不弹提示`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(text("[素质]", tooltip = "说明"))))
        assertNull(layer.tooltipInline(0, 9, yInRowPx = 9f, fontSizePx = 18), "列越出行区间")
        assertNull(layer.tooltipInline(1, 1, yInRowPx = 9f, fontSizePx = 18), "行越出行表")
        assertNull(layer.tooltipInline(-1, 1, yInRowPx = 9f, fontSizePx = 18))
        assertNull(layer.tooltipInline(0, -1, yInRowPx = 9f, fontSizePx = 18))
    }

    /**
     * 图形节点的提示仍受**它自己**的节点盒约束（`EmueraConsole.cs:2368` 的 `Top/Bottom`）。
     *
     * 图元 `ypos = 10`、高 8 ⇒ 节点盒 `[10, 18]`：盒外（y=4）不弹、盒内（y=12）弹 ——
     * 这条与点击的 `hitInline` 判据同源，证明本笔没有把纵门整个拆掉（只换了「按谁的盒判」）。
     */
    @Test
    fun `图形节点的提示按自身纵盒判而不再是整行带`() {
        val image = InlineGraphic.Image(
            resource = "立绘",
            widthPx = 18,
            heightPx = 8,
            yOffsetPx = 10,
            widthCells = 1,
            altText = null,
            rowSpan = 0,
        )
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                DisplayRow(
                    listOf(
                        TextRun(
                            "", 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.IMAGE, 7L,
                            buttonId = 2, graphic = image, tooltip = "图提示",
                        ),
                    ),
                    cells = 1,
                    isLogicalLine = true,
                    isLineEnd = true,
                ),
            ),
        )
        assertNotNull(layer.hit(0, 0), "图段在源按钮内 ⇒ 是可点目标（纵门按图元盒加严）")
        assertEquals("图提示", layer.tooltip(0, 0))
        assertEquals("图提示", layer.tooltipInline(0, 0, yInRowPx = 12f, fontSizePx = 18), "盒内")
        assertEquals("图提示", layer.tooltipInline(0, 0, yInRowPx = 10f, fontSizePx = 18), "盒上沿（闭区间）")
        assertEquals("图提示", layer.tooltipInline(0, 0, yInRowPx = 18f, fontSizePx = 18), "盒下沿（闭区间）")
        assertNull(layer.tooltipInline(0, 0, yInRowPx = 4f, fontSizePx = 18), "盒上方：该点不在任何节点上")
        // 与点击判据同一条结论（`hitInline` 的同一份盒）。
        assertNull(layer.hitInline(0, 0, yInRowPx = 4f, fontSizePx = 18))
        assertNotNull(layer.hitInline(0, 0, yInRowPx = 12f, fontSizePx = 18))
    }

    /**
     * **端到端（仍纯 JVM）**：把语料真实形态的 HTML 走一遍 `HtmlRunFlatten` 展平，
     * 再喂 `ButtonLayer`，长按仍要查得到。
     *
     * 串取自 `HTML_TALENTS.ERB:79` 的 `MO_TALENT` 返回式（颜色/加粗/`nonbutton title`
     * 三层嵌套），`[素质]` 是正文行里的普通文本。这里同时断言**旧判据的反向证明**：
     * 同一坐标 `hitInline` 为 null（该点没有可点目标），而旧壳体正是拿它当纵门。
     *
     * 顺带覆盖同族受害者「**无 value 的 `<button title>`**」：`HtmlRunFlatten` 对
     * `value == null` 的 `<button>` 落成 TEXT 段（PC 上照样弹提示，见 `Kernel.kt` 的
     * `TextRun.tooltip` KDoc），它同样没有可点目标，上次一起被掐。
     */
    @Test
    fun `语料形态 HTML 展平后长按仍查得到而非按钮同族也不再被掐`() {
        val metrics = CellMetrics(18)
        val talent = "<font color='#FF78C8'><b><nonbutton title='種族：人類<br>能力：知性'>[阿求]</nonbutton></b></font>"
        val noValueButton = "<button title='这一格没有按钮值'>说明</button>"

        fun lay(html: String): ButtonLayer {
            val runs = HtmlRunFlatten(metrics).flatten(
                TextRun(html, 0xC0C0C0, 0, 0, "msgothic", RunKind.HTML),
            )
            val row = DisplayRow(
                runs = runs,
                cells = runs.sumOf { it.graphic?.widthCells ?: Grid.cells(it.text) },
                isLogicalLine = true,
                isLineEnd = true,
            )
            return ButtonLayer().apply { attach(listOf(row)) }
        }

        // ① 素质屏语料形态
        val talentLayer = lay(talent)
        assertEquals(
            "種族：人類<br>能力：知性",
            talentLayer.tooltipInline(0, 3, yInRowPx = 9f, fontSizePx = 18),
            "展平链没把 title 带下来，或纵门又按可点性判了",
        )
        assertNull(talentLayer.hitInline(0, 3, yInRowPx = 9f, fontSizePx = 18), "反向证明：该点没有可点目标")
        // 上面这条 null 就是 0.2.44 复合判据（`gated && hitInline == null ⇒ null`）在这条语料形态上
        // 必然判空的原因 —— 修复前 tooltipAt 拿它当纵门，玩家的这一下长按什么都不会弹。
        // 运行期反向证明（把该判据插回去必须变红）记在 specs/verify-notes.md 的 2026-11-12 节。

        // ② 无 value 的 <button title>：展平成 TEXT，提示仍在、仍不可点
        val plainLayer = lay(noValueButton)
        assertEquals("这一格没有按钮值", plainLayer.tooltipInline(0, 1, yInRowPx = 9f, fontSizePx = 18))
        assertNull(plainLayer.hit(0, 1), "value 缺失 ⇒ 进不了命中表（HtmlRunFlatten 落 TEXT）")
    }
}
