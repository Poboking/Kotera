package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ImageBandMerge]：首页标题图（36 条 1041×16 横带逐行铺）合成一张等比缩小的图。
 *
 * 判据：把 N 行等高纯图片行折成一行图（等比、居中靠 align）+ 其余行清空；
 * 放得下时**原样返回**（同一个 List 实例，避免无谓抖动）；不足 [ImageBandMerge.merge] 的
 * `minBands` 或尺寸不一致时不合并。
 */
class ImageBandMergeTest {

    private fun band(w: Int, h: Int, resource: String = "TW_title000", y: Int = 0): RenderLine =
        RenderLine(
            runs = listOf(
                TextRun(
                    text = "",
                    fore = 0xC0C0C0,
                    back = 0,
                    styleMask = 0,
                    fontName = "黑体",
                    kind = RunKind.IMAGE,
                    graphic = InlineGraphic.Image(
                        resource = resource,
                        widthPx = w,
                        heightPx = h,
                        yOffsetPx = y,
                        widthCells = maxOf(1, w / 20),
                    ),
                ),
            ),
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.CENTER,
        )

    @Test
    fun `竖带块合成一张等比缩小的图并收窄行数`() {
        val lines = (0 until 12).map { band(1000, 40, "TW_title%03d".format(it)) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 40,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 200,
        )
        assertEquals(5, out.size, "12 条横带合成 1 张图 + 4 个承载空行（其余行删掉，屏上内容变短）")
        val g = out[0].runs.single().graphic as InlineGraphic.Image
        // 块高 12*40=480 → 高限 200 ⇒ scale=200/480≈0.4167；宽 1000→416、高 480→200
        assertEquals(417, g.widthPx, "等比缩到高限：1000 × 200/480 = 416.7 ⇒ 417")
        assertEquals(200, g.heightPx)
        assertEquals(21, g.widthCells, "占宽按缩后像素宽算格数：ceil(417/20) = 21")
        assertEquals(5, g.rowSpan, "整块跨 5 行（绘制侧据此不按单行高压缩）")
        assertEquals(
            (0 until 12).map { "TW_title%03d".format(it) },
            g.bandResources,
            "35 张切片资源名整串带下去（单画首张只有最上面 16px 一条）",
        )
        assertEquals(Alignment.CENTER, out[0].align, "居中沿用行对齐（脚本 p align=center）")
        // ceil(200/40) = 5 行承载；其余行删掉。承载行必须带一个空格 run（否则折行时被丢掉）
        assertEquals(1, out[1].runs.size, "承载行补一个半角空格（图比行高矮时）")
        assertEquals(" ", out[1].runs.single().text)
        assertTrue(out[1].isLineEnd, "承载行必须是已收尾行，否则被 appendMerged 并进上一行")
        assertEquals(1, out[4].runs.size, "共 5 行：1 图 + 4 承载行")
        assertEquals(out[0].runs.single().text.length, 21, "占宽负载是 21 个半角空格")
    }

    @Test
    fun `块高上限按绘制后的自然高取比例`() {
        // 真机形态：缺 height 的横带每带高 = FontSize，可以大于行高（36 > 31）。
        // 高限 200 必须对 12*36=432 取比例（⇒ 绘制高正好 200），而不是对 12*31=372
        // 取比例（那样算出 232，合并块仍会压到下面的文字行）。
        val lines = (0 until 12).map { band(1000, 36) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 31,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 200,
        )
        val g = out[0].runs.single().graphic as InlineGraphic.Image
        assertEquals(200, g.heightPx, "等比缩到高限，不带行高比值的 14% 溢出")
        assertEquals((200 + 31 - 1) / 31, g.rowSpan)
        assertEquals(12, g.bandResources?.size, "12 张切片都要留在块里")
        assertEquals(7, out.size, "ceil(200/31) = 7 行承载")
    }

    @Test
    fun `放得下时原样返回`() {
        val lines = (0 until 12).map { band(400, 40) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 40,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 1080,
        )
        assertSame(lines, out, "scale >= 1 ⇒ 不合并、返回同一实例")
    }

    @Test
    fun `不足最小行数或尺寸不一致不合并`() {
        val few = (0 until 3).map { band(1000, 40) }
        assertSame(
            few,
            ImageBandMerge.merge(few, 40, 20f, 1000, 100),
            "少于 minBands 不合并",
        )
        val mixed = (0 until 12).map { band(if (it % 2 == 0) 1000 else 800, 40) }
        assertSame(
            mixed,
            ImageBandMerge.merge(mixed, 40, 20f, 1000, 100),
            "各带尺寸不一致不合并（宁可漏合，不可错合）",
        )
    }

    @Test
    fun `近方形图块即使连续多行也不合并`() {
        // 8 张同尺寸立绘/头像（宽高比 1）不是同一张图切出的横带，缩成一块会毁版面。
        val portraits = (0 until 12).map { band(200, 200, "portrait%02d".format(it)) }
        assertSame(
            portraits,
            ImageBandMerge.merge(portraits, 40, 20f, 1000, 100),
            "宽高比 < 4 的块不参与竖带合并",
        )
    }

    @Test
    fun `合并块之前的普通行必须保留`() {        // 回归（深审 R9 render-B2）：首页标题图前面由脚本打印的文字行，在标题图被合并后
        // 曾整段丢失（`out` 首次创建时没有补回跳过的前缀行）。
        val title = RenderLine(
            runs = listOf(TextRun("标题文字", 0xC0C0C0, 0, 0, "黑体")),
            isLogicalLine = true,
            isLineEnd = true,
        )
        val lines = listOf(title) + (0 until 12).map { band(1000, 40, "TW_title%03d".format(it)) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 40,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 200,
        )
        assertSame(title, out[0], "合并块之前的普通文字行原样保留，不丢")
        assertEquals(6, out.size, "1 前缀行 + 1 张合成图 + 4 个承载空行")
        assertTrue(
            out[1].runs.single().graphic is InlineGraphic.Image,
            "合成图紧跟在前缀行之后",
        )
    }

    // ==================================================================
    // 第二趟：单张超高图（4.981 首页标题图那一支）
    // ==================================================================

    private fun blank(): RenderLine = RenderLine(
        runs = emptyList(),
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun textLine(text: String): RenderLine = RenderLine(
        runs = listOf(TextRun(text, 0xC0C0C0, 0, 0, "黑体")),
        isLogicalLine = true,
        isLineEnd = true,
    )

    /**
     * 4.981 真机档的标题图：`height='3500'` 是百分比（`Utils.cs:342-362`），端口按**生效字号** 41 乘
     * ⇒ 高 1435px（比 1080 的屏还高）、缺宽按基图 1041:557 推得宽 2682px。
     */
    private fun tallTitle(w: Int = 2682, h: Int = 1435): RenderLine = RenderLine(
        runs = listOf(
            TextRun(
                text = "",
                fore = 0xC0C0C0,
                back = 0,
                styleMask = 0,
                fontName = "黑体",
                kind = RunKind.IMAGE,
                graphic = InlineGraphic.Image(
                    resource = "TW_title",
                    widthPx = w,
                    heightPx = h,
                    yOffsetPx = 0,
                    widthCells = w / 20,
                ),
            ),
        ),
        isLogicalLine = true,
        isLineEnd = true,
        align = Alignment.CENTER,
    )

    private fun collapse(lines: List<RenderLine>) = ImageBandMerge.merge(
        lines = lines,
        lineHeightPx = 43,
        cellPixelWidth = 20f,
        availWidthPx = 2400,
        maxBlockHeightPx = 648,
    )

    @Test
    fun `单张超高图缩到块高上限并塌缩它预留的空行`() {
        // 4.981 `TITLE.ERB:61-62`：一张 35 行高的图 + 脚本腾出的 34 行空白。
        // 图比整个窗口（30 行）还高 ⇒ 图所在行被推出绘制窗口、整张图一条命令都不出。
        val lines = listOf(tallTitle()) + List(34) { blank() } + List(12) { textLine("文字$it") }
        val out = collapse(lines)

        val g = out[0].runs.single().graphic as InlineGraphic.Image
        assertEquals(648, g.heightPx, "缩到高限 648（= 0.6 屏高那一档预算）")
        assertEquals(1211, g.widthPx, "等比缩：2682 × 648/1435，不单独夹宽")
        assertEquals(16, g.rowSpan, "ceil(648/43) = 16 行承载")
        assertNull(g.bandResources, "单张图不是竖带块，绘制侧不走逐片堆叠")
        assertEquals(Alignment.CENTER, out[0].align, "居中沿用脚本的 p align=center")
        assertEquals(28, out.size, "1 图 + 15 承载 + 12 文字 = 28 行，落得进 30 行窗口")
        assertEquals(out.last(), lines.last(), "图后面的文字行原样在末尾，一行不丢")
    }

    @Test
    fun `不高过块上限的图一行都不动`() {
        // 立绘（180px 那族）与 bodyGraph 都远小于 648：这一趟必须完全放行、返回同一实例，
        // 否则就是拿「标题图缩放」去改所有立绘的版面。
        val lines = listOf(tallTitle(h = 645)) + List(20) { blank() } + List(5) { textLine("x") }
        assertSame(lines, ImageBandMerge.merge(lines, 43, 20f, 2400, 648))
    }

    @Test
    fun `已合成的竖带块不被第二趟重复缩放`() {
        // 第一趟的产物 heightPx 已 ≤ 高限、且带 rowSpan/bandResources；第二趟必须认出它。
        val lines = (0 until 12).map { band(1000, 40, "TW_title%03d".format(it)) }
        val out = ImageBandMerge.merge(lines, 40, 20f, 1000, 200)
        assertSame(out, ImageBandMerge.merge(out, 40, 20f, 1000, 200), "第二趟幂等：不重复缩、不重复塌行")
    }

    @Test
    fun `预留空行不足时补承载行`() {
        // 图缩到 16 行，但脚本只腾了 3 行 ⇒ 后面 12 行由承载行补上，
        // 图不得压在文字行上（那正是 2026-11「图片遮住文字」那一族的复发条件）。
        val lines = listOf(tallTitle()) + List(3) { blank() } + List(5) { textLine("y") }
        val out = collapse(lines)
        assertEquals(21, out.size, "1 图 + 15 承载 + 5 文字")
        for (r in 1 until 16) {
            assertTrue(out[r].isLineEnd, "第 $r 承载行必须已收尾，否则被 appendMerged 并进上一行")
        }
        assertTrue(out[16].runs.single().text.startsWith("y"), "文字行紧跟在承载块之后")
    }

    @Test
    fun `图与文字同行的混排行不参与缩放`() {
        // 判据与 bandOf 同口径（宁可漏合，不可错合）：混排行缩了会把同行文字一起推走。
        val mixed = RenderLine(
            runs = listOf(
                TextRun("说明", 0xC0C0C0, 0, 0, "黑体"),
                TextRun(
                    text = "",
                    fore = 0xC0C0C0,
                    back = 0,
                    styleMask = 0,
                    fontName = "黑体",
                    kind = RunKind.IMAGE,
                    graphic = InlineGraphic.Image(
                        resource = "TW_title",
                        widthPx = 2682,
                        heightPx = 1435,
                        yOffsetPx = 0,
                        widthCells = 134,
                    ),
                ),
            ),
            isLogicalLine = true,
            isLineEnd = true,
        )
        val lines = listOf(mixed) + List(34) { blank() }
        assertSame(lines, ImageBandMerge.merge(lines, 43, 20f, 2400, 648))
    }

    // ==================================================================
    // div 覆盖块守卫（2026-11-10 玩家报「幻存神签仍然错位」的根修回归）
    // ==================================================================

    /** 最小 div 覆盖块（几何足够让守卫判「在场」即可；细节归 DivOverlayTest）。 */
    private fun divBlock(widthPx: Int, heightPx: Int): DisplayDivBlock = DisplayDivBlock(
        altHeadTag = "<div>",
        anchorCol = 0,
        anchorPointXPx = 0,
        xOffsetPx = 0,
        divXOffsetPx = 0,
        pointYPx = 0,
        yOffsetPx = 0,
        widthPx = widthPx,
        heightPx = heightPx,
        subDivisionWidthPx = 0,
        depth = 0,
        backgroundColor = null,
        margin = emptyList(),
        padding = emptyList(),
        border = emptyList(),
        radius = emptyList(),
        borderColors = emptyList(),
        isRelative = true,
        isEscaped = true,
        children = emptyList(),
        sourceRange = IntRange.EMPTY,
    )

    /**
     * 真机档的神签行：`DLC 神签目录 神签.ERB:64/:80` 一行 HTML_PRINT 同时铺
     * `<img height='IMG_H_PX'>` + `<div height='IMG_H_PX'>`（两者同高），脚本按
     * `IMG_H_PX / 行高` 预留空行、底部文字落在预留区之后。真机实测（2026-11-10 截图）：
     * 行高 19、图 569px、块高预算 431（内容高 60%）⇒ 此前 img 被缩到 431、预留行被塌缩，
     * div 盒仍按 569 全尺寸画，盒底悬进上移后的底部文字区。
     */
    private fun omikujiRow(): RenderLine = RenderLine(
        runs = listOf(
            TextRun(
                text = "",
                fore = 0xC0C0C0,
                back = 0,
                styleMask = 0,
                fontName = "黑体",
                kind = RunKind.IMAGE,
                graphic = InlineGraphic.Image(
                    resource = "神签_76",
                    widthPx = 404,
                    heightPx = 569,
                    yOffsetPx = 0,
                    widthCells = 41,
                ),
            ),
        ),
        isLogicalLine = true,
        isLineEnd = true,
        divBlocks = listOf(divBlock(widthPx = 622, heightPx = 569)),
    )

    @Test
    fun `带div覆盖块的超高图行不参与塌缩`() {
        val lines = listOf(omikujiRow()) + List(30) { blank() } + List(4) { textLine("底部文字$it") }
        assertSame(
            lines,
            ImageBandMerge.merge(lines, 19, 10f, 1554, 431),
            "img+覆盖块+预留行是三件一体的脚本排版：不缩图、不塌行，宁可漏合",
        )
    }

    @Test
    fun `同几何不带覆盖块的行照旧塌缩`() {
        // 对照组：守卫只挡「带覆盖块」的行；神签以外的超高图（标题图一族）必须仍走塌缩，
        // 否则等于把 2026-10-30 的标题图修复整段退掉。
        val bare = listOf(omikujiRow().copy(divBlocks = emptyList())) +
            List(30) { blank() } + List(4) { textLine("底部文字$it") }
        val out = ImageBandMerge.merge(bare, 19, 10f, 1554, 431)
        val g = out[0].runs.single().graphic as InlineGraphic.Image
        assertEquals(431, g.heightPx, "不带覆盖块：img 照旧缩到块高预算")
        assertTrue(g.heightPx < 569, "塌缩确实发生（569 → 431），守卫不是判据失效")
    }

    @Test
    fun `带div覆盖块的横带行不参与合并`() {
        val banded = (0 until 12).map {
            band(1000, 40, "TW_title%03d".format(it)).copy(divBlocks = listOf(divBlock(widthPx = 1000, heightPx = 40)))
        }
        assertSame(
            banded,
            ImageBandMerge.merge(banded, 40, 20f, 1000, 200),
            "覆盖块的锚行序不能被折行合并挪动：带块行整段原样（第一趟同口径）",
        )
    }

    // ==================================================================
    // 预留区混入带 div 的空行（2026-11-10 深审补，与图行 div 守卫同一根因的另一入口）
    // ==================================================================

    /**
     * 只印 div 不印文字的行：runs 为空（`isBlankLine` 的旧判据会把它当「空白」消费掉，
     * 换成不带 divBlocks 的 SPACER —— 覆盖块随行凭空消失）。
     */
    private fun blankWithDiv(): RenderLine = RenderLine(
        runs = emptyList(),
        isLogicalLine = true,
        isLineEnd = true,
        divBlocks = listOf(divBlock(widthPx = 622, heightPx = 569)),
    )

    @Test
    fun `预留区混入带div的空行时整段不塌缩`() {
        // 图行本身不带块（图行守卫不拦），但脚本预留区里有一条挂 div 的空行：
        // 覆盖块锚定在这条空行上、与图同属一段脚本排版。塌缩若动手，这条空行要么被删、
        // 要么被换成 SPACER（块凭空消失），锚行序也随之漂移 ⇒ 预留区一见带块行，
        // 整张图原样放行（宁可漏合）。
        val lines = listOf(omikujiRow().copy(divBlocks = emptyList())) +
            List(3) { blank() } + blankWithDiv() + List(27) { blank() } +
            List(4) { textLine("底部文字$it") }
        assertSame(
            lines,
            ImageBandMerge.merge(lines, 19, 10f, 1554, 431),
            "预留区带 div 覆盖块：img 与全部预留行一行不动，覆盖块不得随行消失",
        )
    }

    @Test
    fun `预留区首行就带div时同样整段不塌缩`() {
        // 边界：带块空行是图行后面第一条 —— 扫描在 j=i+1 就撞上，不得误走
        // 「没有预留空行」分支之外的缩图路径（两分支在这里等价：都原样放行）。
        val lines = listOf(omikujiRow().copy(divBlocks = emptyList())) +
            blankWithDiv() + List(29) { blank() } + List(4) { textLine("底部文字$it") }
        assertSame(lines, ImageBandMerge.merge(lines, 19, 10f, 1554, 431))
    }
}
