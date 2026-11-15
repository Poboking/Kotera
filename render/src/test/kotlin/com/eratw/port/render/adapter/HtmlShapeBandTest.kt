package com.eratw.port.render.adapter

import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4 参 `<shape type='rect'>` 的**像素条带**行为（2026-10-23 真机「少女祈祷中」裸标签红）。
 *
 * ## 现象
 *
 * 语料 `ERB/魔改内容/PRINT系/UI_Components_&_Tables.ERB` 的 `@HTML_RECT_BAR` 渐变分支把进度条
 * 切成上百个 `param='0, 30, 1, 64'`（1 单位 = `FontSize / 100` 像素，真机字号 40 ⇒ 0.4px）
 * 的小矩形。真机整屏显示成 `<shape type='rect' param="0, 30, 1, 64" color="#7F7FDA">` 原文。
 *
 * ## 两处口径错误（本文件逐个钉住）
 *
 * 1. **判定用了整数**：原版 `Utils.MixedNum.ToPixelf` 是**浮点**（spec: `Utils.cs:60-71`），
 *    `1 * 18 / 100 = 0.18f` 满足 `Width > 0f`（spec: `ConsoleShapePart.cs:61-68`）；移植侧走
 *    `base * num / 100` 整数除 ⇒ 0 ⇒ 每块都被判「不可渲染」⇒ 落叶 AltText 变文本。
 * 2. **占宽按块各算一格**：本层折行的宽度单位是半角格，逐块 `cellsFor` 会让 40px 的条吃掉
 *    上百格（≈2000px）。改成连续条带按「总宽 ÷ 半角格宽」折整格数、逐格采样颜色。
 *
 * 夹具字号 18px ⇒ 半角步进 9.36px（[CellMetrics.cellPixelWidth]），期望值都按此手算。
 */
class HtmlShapeBandTest {

    private val metrics = CellMetrics(18)

    private fun flatten(html: String): List<TextRun> =
        HtmlRunFlatten(metrics).flatten(
            TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML),
        )

    /** 渐变条：`chunks` 个 `unitPx` 宽的小矩形，颜色依次 `#000000`、`#000001`… */
    private fun gradientBar(chunks: Int, unitPx: Int, yUnits: Int = 30, hUnits: Int = 64): String =
        buildString {
            for (i in 0 until chunks) {
                append("<shape type='rect' param='0, ").append(yUnits).append(", ")
                    .append(unitPx).append("px, ").append(hUnits).append("' color='#")
                    .append(i.toString(16).uppercase().padStart(6, '0')).append("'>")
            }
        }

    /**
     * 9 块 × 2px = 18px 总宽 ⇒ 折 2 格（`ceil(18 / 9.36)`），每格 9px；
     * 颜色取每格区间中点所在块：中点 4.5px 落在第 3 块（累计 6px）⇒ `#000002`，
     * 中点 13.5px 落在第 7 块（累计 14px）⇒ `#000006`。
     */
    @Test
    fun `渐变条合成为一个带渐变的矩形且不回落成文本`() {
        val runs = flatten(gradientBar(9, 2) + "[3％]少女祈祷中…")
        // ① 最要紧的一条：裸标签绝不能再出现在文本段里
        assertTrue(
            runs.none { it.kind == RunKind.TEXT && it.text.contains("<shape") },
            "4 参 rect 不许回落成 AltText 文本：${runs.map { it.text }}",
        )
        val rects = runs.filter { it.graphic is InlineGraphic.Rect }.map { it.graphic as InlineGraphic.Rect }
        // ② 整条折成**一个**矩形：宽高按像素、占宽按格，渐变停点按位置降采样
        assertEquals(1, rects.size, "整条合成为一个段（像素级平滑，不再逐格出块）")
        val bar = rects[0]
        assertEquals(2, bar.widthCells, "18px / 9.36px ⇒ 2 格")
        assertEquals(18, bar.widthPx, "像素宽 = 各块之和")
        assertEquals(5, bar.yOffsetPx, "y = 18 * 30 / 100 = 5.4 ⇒ 5")
        assertEquals(11, bar.heightPx, "h = 18 * 64 / 100 = 11.52 ⇒ 11")
        val stops = bar.gradientArgb
        assertTrue(stops != null && stops.size == 2, "2 格 ⇒ 2 个渐变停点：${stops?.toList()}")
        assertEquals(0xFF000002L.toInt(), stops!![0], "首停点取第 1 格区间中点所在块的颜色")
        assertEquals(0xFF000006L.toInt(), stops[1], "次停点同理")
        assertEquals(stops[0], bar.colorArgb, "纯色字段取首停点（无 shader 的绘制侧仍有可见色）")
        // ③ 条带之后的文字照常接在后面（占宽跟着条带走）
        assertEquals("[3％]少女祈祷中…", runs.last().text)
    }

    /** 单块 4 参 rect 不参与合并，逐像素口径与改前一致（`px` 后缀 ⇒ 原值）。 */
    @Test
    fun `单块四参矩形口径不变`() {
        val g = flatten("<shape type='rect' param='0,1px,440px,2px' color='#FF8800'>")
            .single().graphic as InlineGraphic.Rect
        assertEquals(0, g.xOffsetPx)
        assertEquals(1, g.yOffsetPx)
        assertEquals(440, g.widthPx)
        assertEquals(2, g.heightPx)
        assertEquals(48, g.widthCells, "ceil(440 / 9.36) = 48")
    }

    /** 不同纵偏移/不同高度的两块不许并成一条带（各自的矩形要落在各自的位置上）。 */
    @Test
    fun `不同高或不同纵偏移的块不合并`() {
        val runs = flatten(
            "<shape type='rect' param='0, 10, 100px, 20px' color='#110000'>" +
                "<shape type='rect' param='0, 40, 100px, 20px' color='#220000'>",
        )
        val rects = runs.filter { it.graphic is InlineGraphic.Rect }.map { it.graphic as InlineGraphic.Rect }
        assertEquals(2, rects.size)
        assertEquals(listOf(1, 7), rects.map { it.yOffsetPx }, "y = 18 * 10 / 100 = 1.8 ⇒ 1；18 * 40 / 100 = 7.2 ⇒ 7")
        assertEquals(listOf(20, 20), rects.map { it.heightPx }, "h 带 px 后缀 ⇒ 原值 20")
        assertEquals(listOf(0xFF110000L.toInt(), 0xFF220000L.toInt()), rects.map { it.colorArgb })
    }

    /** 高度不足 1px 的细条照 `ConsoleRectangleShapePart.cs:28-33` 抬到 1px，不许整段消失。 */
    @Test
    fun `微小高度抬到一像素`() {
        val g = flatten("<shape type='rect' param='0, 0, 10px, 1' color='#FFFFFF'>")
            .single().graphic as InlineGraphic.Rect
        assertEquals(1, g.heightPx, "h = 18 * 1 / 100 = 0.18 ⇒ 0，但原版抬到 1")
        assertEquals(10, g.widthPx)
    }

    /**
     * 0.2.46「战斗能力不显示升级进度条」的**两把尺**同值闸：
     * [HtmlRunFlatten.flushRectBands] 把连续 `1px` 色块合成一个矩形（占格 = 总像素宽 ÷ 半角格宽），
     * core 折行预算 [CellWidth.visibleHtmlCells] 必须给同一个数。分叉时真机 136 列下
     * `Lv2 [条] Lv3` 会被 core 折成三行，条与等级标签分家。
     */
    @Test
    fun `连续色块的折行预算等于展平后的占格`() {
        val html = gradientBar(180, 1, yUnits = 37, hUnits = 63)
        val rect = HtmlRunFlatten(metrics, scriptFontPixelSize = 18)
            .flatten(
                TextRun(
                    html, fore = 0xC0C0C0, back = 0, styleMask = 0,
                    fontName = "msgothic", kind = RunKind.HTML,
                ),
            )
            .mapNotNull { it.graphic as? InlineGraphic.Rect }
            .single()
        val budget = CellWidth.visibleHtmlCells(html, 18L)
        println("PARITY render=${rect.widthCells} budget=$budget htmlLen=${html.length}")
        assertEquals(20, rect.widthCells, "180 × 1px = 180px ⇒ ceil(180 / 9.36) = 20 格")
        assertEquals(rect.widthCells, budget, "折行预算与渲染展平必须同一把尺")
    }

    /**
     * 0.2.46 根修的**全形态同值闸**：同一条 HTML 在 core 折行预算
     * [CellWidth.visibleHtmlCells] 与 render 展平后的实际占格上必须给出同一个数。
     *
     * 分叉过的四类形态逐条钉在这里：连续条带（逐块各计 1 格）、条带中夹文本 / `<br>`
     * （渲染跨内容合并、core 分段）、y/h 同值异写（core 按原始参数文本、render 按折算
     * 像素判同）、以及普通单块 / img / space / 横向偏移（防修复波及其他形态）。
     *
     * 唯一**不纳入**本闸的是错误形态 `<shape>` 的 AltText 文本回落：core 按标签原文
     * 近似计宽、render 画的是规范化重建文案，两者差 1 格。这是登记过的文本近似偏差
     * （`CellWidthHeadlessShapeRulerTest` 钉的就是「标签原文」读数），不是图形占宽的两把尺，
     * 且只影响本就超宽的裸标签行；按 ADR-005 保留原版重建文案语义，不改 core 的近似。
     */
    @Test
    fun `全形态 折行预算等于渲染展平占格`() {
        val cases = linkedMapOf(
            "连续180x1px条" to gradientBar(180, 1, 37, 63),
            "连续60x3px条" to gradientBar(60, 3, 37, 63),
            "条+尾随文本" to gradientBar(9, 2, 30, 64) + "[3％]少女祈祷中…",
            "Lv2条Lv3混排" to "Lv2 " + gradientBar(180, 1, 37, 63) + " Lv3",
            "单块四参rect" to "<shape type='rect' param='0,1px,440px,2px' color='#FF8800'>",
            "两块不同y" to "<shape type='rect' param='0, 10, 100px, 20px' color='#110000'>" +
                "<shape type='rect' param='0, 40, 100px, 20px' color='#220000'>",
            "带横向偏移两块" to "<shape type='rect' param='5px,37,10px,63' color='#110000'>" +
                "<shape type='rect' param='5px,37,10px,63' color='#220000'>",
            "单参rect" to "<shape type='rect' param='216px' color='red'>",
            "shapeSpace" to "<shape type='space' param='40px'>文字",
            "条中夹文本" to gradientBar(3, 10, 30, 64) + "X" + gradientBar(3, 10, 30, 64),
            "条中夹br" to gradientBar(3, 10, 30, 64) + "<br>" + gradientBar(3, 10, 30, 64),
            "y同值异写" to "<shape type='rect' param='0,100,3px,63' color='#110000'>" +
                "<shape type='rect' param='0,18px,3px,63' color='#220000'>",
            "w混合px与百分比" to "<shape type='rect' param='0,37,3px,63' color='#110000'>" +
                "<shape type='rect' param='0,37,300,63' color='#220000'>",
            "img显式width" to "<img src='pic' width='180px'>AB",
            "文本+条混排" to " " + gradientBar(180, 1, 37, 63),
            "条+space+条" to gradientBar(3, 10, 30, 64) +
                "<shape type='space' param='2px'>" + gradientBar(3, 10, 30, 64),
        )
        for ((name, html) in cases) {
            val runs = HtmlRunFlatten(metrics, scriptFontPixelSize = 18).flatten(
                TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML),
            )
            val rendered = runs.sumOf { it.graphic?.widthCells ?: Grid.cells(it.text) }
            val budget = CellWidth.visibleHtmlCells(html, 18L)
            assertEquals(
                budget, rendered,
                "[$name] 折行预算与渲染展平必须同一把尺（段宽 ${runs.map { it.graphic?.widthCells ?: Grid.cells(it.text) }}）",
            )
        }
    }

    /**
     * 条带前后文本的**段序**：`HTML_PRINT " " + HTML_RECT_BAR(…)` 这类语料
     * （`UI_Components_&_Tables.ERB:1303` 等 5 处）里空格在条之前，展平后不许被挪到条后。
     */
    @Test
    fun `文本在条带之前时 段序不颠倒`() {
        val runs = HtmlRunFlatten(metrics, scriptFontPixelSize = 18).flatten(
            TextRun(
                "名 " + gradientBar(9, 2, 30, 64) + " 値",
                fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML,
            ),
        )
        assertEquals(3, runs.size, "文本 / 条 / 文本 三段")
        assertEquals("名 ", runs[0].text, "前缀文本先落段")
        assertTrue(runs[1].graphic is InlineGraphic.Rect, "条带居中")
        assertEquals(" 値", runs[2].text, "后缀文本最后落段")
    }
}
