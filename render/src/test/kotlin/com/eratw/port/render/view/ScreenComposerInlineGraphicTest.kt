package com.eratw.port.render.view

import com.eratw.port.render.DirtyRect
import com.eratw.port.render.GlyphKey
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import org.junit.jupiter.api.Assertions.assertArrayEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 正文行内图形落像素（MODERN-R10-A）：[ScreenComposer.drawRun] 的图形分发像素钉子。
 *
 * 夹具几何（与 `ScreenComposerTest` / `ConsoleFrameTest` 同一组整数，期望值不来自实现）：
 * 字号 25 ⇒ 半角格恰 13px、行高 25px、`ShapePositionShift = max(2, 25/6) = 4`
 * （`Config.cs:461-465`，单源 `ScreenComposer.divShapeShift`）；画布 20 格 × 4 行 = 260×100，
 * `rowTopPx(row, 4) = 25*row`。背景取不透明的 `0xFF102030`（非黑：透明 no-op 与黑底可区分）。
 *
 * 语义出处（契约 §2 已勘事实，逐条照官方）：
 * - Image 命中支 `ConsoleImagePart.cs:159-184`（:171 x 带 shift、:172 y = 行顶+ypos、
 *   :163-167 换按下图、:168 守卫）；缺帧回落 `:175-182`（裸 PointX/pointY、Config.ForeColor）；
 * - Rect `ConsoleRectangleShapePart.cs:38-48`（:43 x 带 shift、:45 只看 isSelecting ⇒
 *   headless 不换焦点色、:58 非正宽不画）；alpha 补齐 `DrawCmd.kt:140-144`；
 * - Space `ConsoleSpacePart.cs:14-16`（空 DrawTo）；
 * - 竖带块是 `ImageBandMerge` 端口自产物（官方无此形态），口径对照 canvas
 *   `CanvasConsoleView.kt:4414-4445`（srcH 占比、缺片跳过不推进 y、不回落 altText）。
 *
 * 覆盖契约 §4 的 12 条用例；反向证明 A-E 的靶子用例在各自 KDoc 点名。
 * TODO(verify)：真机像素对账（adb 空）；headless 最近邻缩放与真机双线性差异（既有登记）。
 */
class ScreenComposerInlineGraphicTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 25, requestedLineHeight = 25)
    private val composer = ScreenComposer(clientWidthCells = 20, heightLines = 4, metrics = metrics)

    private val background = 0xFF102030.toInt()
    private val fore = 0xFFC0C0C0.toInt()
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun solid(w: Int, h: Int, argb: Int): Raster {
        val r = Raster(w, h)
        r.fillRect(0, 0, w, h, argb)
        return r
    }

    private fun table(vararg pairs: Pair<String, Raster>): SpriteTable {
        val t = SpriteTable()
        for ((name, raster) in pairs) t.add(name, raster)
        return t
    }

    private fun imageCmd(row: Int, col: Int, image: InlineGraphic.Image): DrawCmd =
        DrawCmd(row, col, col + image.widthCells, fore, null, 0, RunKind.IMAGE, emptyList(), image)

    private fun rectCmd(row: Int, col: Int, rect: InlineGraphic.Rect): DrawCmd =
        DrawCmd(row, col, col + rect.widthCells, fore, null, 0, RunKind.SHAPE, emptyList(), rect)

    private fun textCmd(row: Int, col: Int, text: String): DrawCmd =
        DrawCmd(
            row, col, col + text.length, fore, null, 0, RunKind.TEXT,
            text.map { GlyphKey(ScreenRows.DEFAULT_FONT, 0, it.code) },
        )

    private fun compose(
        commands: List<DrawCmd>,
        sprites: SpriteTable = SpriteTable(),
        focused: HitTarget? = null,
    ): Raster = composer.compose(
        commands = commands,
        rowCount = 4,
        scene = ScreenScene(background),
        sprites = sprites,
        focusedButton = focused,
    )

    /** 用例 1（反向证明 A/C 的靶子）：命中支 x = 列游标px + shift、y = 行顶 + ypos。 */
    @Test
    fun `img 命中：像素落在列游标px加shift起、行顶加ypos`() {
        val canvas = compose(
            listOf(imageCmd(1, 2, InlineGraphic.Image("IMG", 20, 20, 3, 2))),
            table("IMG" to solid(20, 20, red)),
        )
        assertEquals(red, canvas.pixelAt(30, 28), "x = 2*13+4 = 30，y = 25+3 = 28（ConsoleImagePart.cs:171-172）")
        assertEquals(red, canvas.pixelAt(49, 47), "20×20 右下角")
        assertEquals(background, canvas.pixelAt(29, 28), "左沿外一像素")
        assertEquals(background, canvas.pixelAt(26, 28), "裸列游标 26 处不许有墨：shift 必须加上（漏加即 C 红）")
        assertEquals(background, canvas.pixelAt(50, 28), "右沿外一像素")
        assertEquals(background, canvas.pixelAt(30, 27), "y 不加 GLYPH_INSET：行顶+ypos 上一像素还是背景")
    }

    /** 用例 1 补钉：尺寸缺省走 inlineBox（高退字号、宽按基准纵横比，ConsoleImagePart.cs:66-80）。 */
    @Test
    fun `img 缺省尺寸：高退字号、宽按基准纵横比`() {
        val canvas = compose(
            listOf(imageCmd(0, 0, InlineGraphic.Image("IMG", 0, 0, 0, 4))),
            table("IMG" to solid(20, 10, red)),
        )
        assertEquals(red, canvas.pixelAt(4, 0), "高缺省 = 字号 25，宽缺省 = 20*25/10 = 50，x 从 shift=4 起")
        assertEquals(red, canvas.pixelAt(53, 24), "50×25 右下角（最近邻放大）")
        assertEquals(background, canvas.pixelAt(54, 0), "宽 50 右沿外")
        assertEquals(background, canvas.pixelAt(4, 25), "高 25 下沿外")
        assertEquals(background, canvas.pixelAt(3, 0), "shift 左沿外")
    }

    /**
     * 用例 2（反向证明 B 的靶子）：缺帧 + altText ⇒ 字形块在**裸**列游标、行顶、段前景色。
     * 同屏设非 0 shift（4）与 ypos（5）、并给焦点命中区：三条「不加」一次钉死
     * （ConsoleImagePart.cs:175-182 在换图判式 :163-167 之外）。
     */
    @Test
    fun `缺帧加altText：字形块在裸列游标、行顶、段前景色`() {
        val altFore = 0xFF123456.toInt()
        val img = InlineGraphic.Image("NO_SUCH", 20, 20, 5, 2, altText = "AB")
        val cmd = DrawCmd(1, 2, 4, altFore, null, 0, RunKind.IMAGE, emptyList(), img)
        val canvas = compose(listOf(cmd), focused = HitTarget(1, 0, 10, 7L))
        assertEquals(altFore, canvas.pixelAt(26, 27), "裸 x=2*13=26（不加 shift）、y=25+2、色=段 fore（焦点不换色）")
        assertEquals(altFore, canvas.pixelAt(37, 47), "A 块 x26..37 × y27..47")
        assertEquals(altFore, canvas.pixelAt(39, 27), "B 从 col1=39 起")
        assertEquals(altFore, canvas.pixelAt(50, 47), "B 块右下角")
        assertEquals(background, canvas.pixelAt(25, 27), "裸游标左沿外：误加 shift 时 26 会变背景（B 红）")
        assertEquals(background, canvas.pixelAt(38, 27), "字形间隙 1px（GLYPH_GAP_PX）")
        assertEquals(background, canvas.pixelAt(51, 27), "B 右沿间隙")
        assertEquals(background, canvas.pixelAt(26, 48), "盒高 25-2*2=21：y27..47，下一像素背景")
    }

    /** 用例 3：死条目（dispose 后 disposeAll(false) 回滚复活）⇒ 同缺帧回落，原像素不上屏。 */
    @Test
    fun `死条目未创建：同缺帧回落且不画原像素`() {
        val t = SpriteTable()
        val id = t.add("DEAD", solid(20, 20, red), fromCsv = true)
        t.dispose(id)
        t.disposeAll(false)
        val entry = t.find("DEAD")
        assertTrue(entry != null && !entry.isCreated, "夹具：回滚复活的死条目（ConsoleImagePart.cs:168 的 IsCreated 守卫）")
        val canvas = compose(
            listOf(imageCmd(1, 2, InlineGraphic.Image("DEAD", 20, 20, 0, 2, altText = "AB"))),
            t,
        )
        assertEquals(fore, canvas.pixelAt(26, 27), "!isCreated ⇒ AltText 回落（不是静默）")
        assertTrue(canvas.pixels.none { it == red }, "死条目的像素一个都不许上屏")
    }

    /** 用例 4（反向证明 D 的靶子）：altText == null ⇒ 整屏一个像素都不出（R9-B 静默口径）。 */
    @Test
    fun `altText为null的缺帧：一个像素都不出`() {
        val canvas = compose(listOf(imageCmd(1, 2, InlineGraphic.Image("NO_SUCH", 20, 20, 0, 2))))
        assertTrue(canvas.pixels.all { it == background }, "altText=null：缺帧保持静默，画了任何一笔即 D 红")
    }

    /** 用例 5：焦点命中区间盖住图形段 ⇒ 换 srcb；srcb 缺失退主图；半开区间两侧 + 行判。 */
    @Test
    fun `焦点盖住图形段换srcb、缺失退主图、半开区间`() {
        val cmd = imageCmd(1, 2, InlineGraphic.Image("RED", 20, 20, 0, 2, buttonResource = "BLUE"))
        val both = table("RED" to solid(20, 20, red), "BLUE" to solid(20, 20, blue))
        assertEquals(red, compose(listOf(cmd), both).pixelAt(30, 25), "无焦点画主图")
        assertEquals(blue, compose(listOf(cmd), both, HitTarget(1, 0, 10, 7L)).pixelAt(30, 25), "盖住 ⇒ 换按下图（:163-167）")
        val onlyRed = table("RED" to solid(20, 20, red))
        assertEquals(red, compose(listOf(cmd), onlyRed, HitTarget(1, 0, 10, 7L)).pixelAt(30, 25), "srcb 缺失退主图（:164）")
        assertEquals(red, compose(listOf(cmd), both, HitTarget(1, 4, 10, 7L)).pixelAt(30, 25), "命中区起点=段终点：半开不算盖住")
        assertEquals(red, compose(listOf(cmd), both, HitTarget(1, 0, 2, 7L)).pixelAt(30, 25), "命中区终点=段起点：半开不算盖住")
        assertEquals(red, compose(listOf(cmd), both, HitTarget(0, 0, 10, 7L)).pixelAt(30, 25), "行不相等不算盖住")
    }

    /** 用例 6（反向证明 E 的靶子）：rect 纯色缺 alpha 也画不透明块；位置含 shift+偏移；不换焦点色。 */
    @Test
    fun `rect纯色：缺alpha也画不透明块、位置含shift与偏移、不换焦点色`() {
        val cmd = rectCmd(1, 2, InlineGraphic.Rect(2, 3, 20, 10, 0x00FF00, 2))
        val canvas = compose(listOf(cmd))
        assertEquals(green, canvas.pixelAt(32, 28), "0x00FF00 必须补 alpha=0xFF；x=26+4+2=32，y=25+3=28（不补即 E 红）")
        assertEquals(green, canvas.pixelAt(51, 37), "20×10 右下角")
        assertEquals(background, canvas.pixelAt(31, 28), "左沿外")
        assertEquals(background, canvas.pixelAt(52, 28), "右沿外")
        assertEquals(background, canvas.pixelAt(32, 27), "上沿外")
        assertEquals(background, canvas.pixelAt(32, 38), "下沿外")
        assertEquals(green, compose(listOf(cmd), focused = HitTarget(1, 0, 10, 7L)).pixelAt(32, 28), "官方 :45 只看 isSelecting ⇒ headless 不换焦点色")
    }

    /** 用例 6 补钉：w<=0 / h<=0 不画（ConsoleRectangleShapePart.cs:58 的 Width>0 守卫同档）。 */
    @Test
    fun `rect非正宽高：不画`() {
        val zeroW = compose(listOf(rectCmd(1, 2, InlineGraphic.Rect(0, 0, 0, 10, 0x00FF00, 2))))
        assertTrue(zeroW.pixels.all { it == background }, "零宽不画")
        val negH = compose(listOf(rectCmd(1, 2, InlineGraphic.Rect(0, 0, 20, -1, 0x00FF00, 2))))
        assertTrue(negH.pixels.all { it == background }, "负高不画")
    }

    /** 用例 7（反向证明 A 的靶子之一）：渐变逐列停点 index=(tx-x)*stops.size/w，停点也补 alpha。 */
    @Test
    fun `rect渐变：逐列停点与fillChildGradient算式一致`() {
        val rect = InlineGraphic.Rect(0, 0, 8, 4, 0, 1, gradientArgb = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF))
        val canvas = compose(listOf(rectCmd(0, 0, rect)))
        // x=4 起铺 8 列：index=(tx-4)*3/8 ⇒ 4..6 停点0、7..9 停点1、10..11 停点2。
        assertEquals(red, canvas.pixelAt(4, 0), "停点0 首列")
        assertEquals(red, canvas.pixelAt(6, 3), "停点0 末列（6*3/8=0 误式会在这里翻绿）")
        assertEquals(green, canvas.pixelAt(7, 0), "停点1 首列（9/8=1）")
        assertEquals(green, canvas.pixelAt(9, 3), "停点1 末列（15/8=1）")
        assertEquals(blue, canvas.pixelAt(10, 0), "停点2 首列（18/8=2）")
        assertEquals(blue, canvas.pixelAt(11, 3), "停点2 末列（21/8=2）")
        assertEquals(background, canvas.pixelAt(3, 0), "shift 左沿外")
        assertEquals(background, canvas.pixelAt(12, 0), "段宽右沿外")
        assertEquals(background, canvas.pixelAt(4, 4), "段高下沿外")
    }

    /** 用例 8：space 不占像素、列游标照常前进（ConsoleSpacePart.cs:14-16 空 DrawTo）。 */
    @Test
    fun `space：不占像素、后续文本落点不变`() {
        val space = DrawCmd(0, 1, 4, fore, null, 0, RunKind.SHAPE, emptyList(), InlineGraphic.Space(3))
        val canvas = compose(listOf(textCmd(0, 0, "A"), space, textCmd(0, 4, "B")))
        assertEquals(fore, canvas.pixelAt(0, 2), "A 在 col0")
        assertEquals(fore, canvas.pixelAt(52, 2), "space 占 3 格 ⇒ B 落在 col4=52px（游标照常前进）")
        for (y in 0 until 25) {
            for (x in 13 until 52) {
                assertEquals(background, canvas.pixelAt(x, y), "space 区域 ($x,$y) 不许有墨")
            }
        }
    }

    /** 用例 9（反向证明 A 的靶子之一）：band 两片异色堆叠；缺片跳过不推进 y；整块不回落 altText。 */
    @Test
    fun `band：两片异色堆叠、缺片跳过不推进y、不回落altText`() {
        val t = table("B0" to solid(20, 10, red), "B2" to solid(20, 10, blue))
        val bands2 = InlineGraphic.Image("B0", 20, 20, 0, 2, bandResources = listOf("B0", "B2"))
        val canvas = compose(listOf(imageCmd(1, 0, bands2)), t)
        assertEquals(red, canvas.pixelAt(4, 25), "x=0+shift4，y=行顶25；片高 20*10/20=10")
        assertEquals(red, canvas.pixelAt(23, 34), "上片右下角")
        assertEquals(blue, canvas.pixelAt(4, 35), "下片紧接上片（y 推进 10）")
        assertEquals(blue, canvas.pixelAt(23, 44), "下片右下角")
        assertEquals(background, canvas.pixelAt(4, 45), "整块高 20 下沿外")
        assertEquals(background, canvas.pixelAt(3, 25), "shift 左沿外")
        assertEquals(background, canvas.pixelAt(24, 25), "段宽右沿外")
        val bands3 = bands2.copy(bandResources = listOf("B0", "NO_SUCH", "B2"), altText = "XX")
        val canvas3 = compose(listOf(imageCmd(1, 0, bands3)), t)
        assertArrayEquals(canvas.pixels, canvas3.pixels, "中间缺片：跳过不推进 y、不回落 altText（逐像素同两片版）")
        val allMissing = bands2.copy(bandResources = listOf("NX1", "NX2"), altText = "XX")
        val canvasM = compose(listOf(imageCmd(1, 0, allMissing)), t)
        assertTrue(canvasM.pixels.all { it == background }, "全缺片：不画也不回落 altText（canvas 同口径）")
    }

    /** 用例 10：超高图向下溢出画到后续行、不压缩（官方从不按行高缩图，GraphicGeometry.kt:117-123）。 */
    @Test
    fun `超高图：向下溢出画到后续行、不压缩`() {
        val canvas = compose(
            listOf(imageCmd(1, 0, InlineGraphic.Image("TALL", 10, 40, 0, 1))),
            table("TALL" to solid(10, 40, red)),
        )
        assertEquals(red, canvas.pixelAt(4, 25), "行1 行顶起画")
        assertEquals(red, canvas.pixelAt(13, 64), "高 40 溢出行带：画到 row2（y50 起）深处，不压缩")
        assertEquals(background, canvas.pixelAt(4, 65), "高 40 下沿外")
        assertEquals(background, canvas.pixelAt(14, 64), "宽 10 右沿外")
        assertEquals(background, canvas.pixelAt(3, 25), "shift 左沿外")
    }

    /** 用例 11 之一：右沿越界 ⇒ 裁到画布边界（Raster.blit 静默裁剪，GDI+ 同口径）。 */
    @Test
    fun `右沿越界：裁剪到画布`() {
        val canvas = compose(
            listOf(imageCmd(0, 19, InlineGraphic.Image("IMG", 20, 20, 0, 2))),
            table("IMG" to solid(20, 20, red)),
        )
        assertEquals(red, canvas.pixelAt(251, 0), "x = 19*13+4 = 251")
        assertEquals(red, canvas.pixelAt(259, 9), "画布最后一列仍画（251+20 越界部分裁掉）")
        assertEquals(background, canvas.pixelAt(250, 0), "左沿外")
    }

    /** 用例 11 之二（S6 不变式）：含图形行整段重绘与整屏逐像素相同。 */
    @Test
    fun `repaintRows含图形行：行段重绘与整屏逐像素相同`() {
        val t = table("IMG" to solid(20, 20, red))
        val frame1 = listOf(
            textCmd(0, 0, "AB"),
            rectCmd(1, 2, InlineGraphic.Rect(0, 0, 20, 10, 0x00FF00, 2)),
        )
        val frame2 = listOf(
            textCmd(0, 0, "AB"),
            rectCmd(1, 2, InlineGraphic.Rect(0, 0, 20, 10, 0x0000FF, 2)),
            imageCmd(2, 2, InlineGraphic.Image("IMG", 20, 20, 0, 2)),
        )
        val target = Raster(composer.canvasWidthPx, composer.canvasHeightPx)
        composer.composeInto(target, frame1, rowCount = 4, scene = ScreenScene(background), sprites = t)
        val drawn = composer.repaintRows(
            target, frame2, rowCount = 4,
            strips = listOf(DirtyRect(1, 3, 0, 20)),
            backgroundArgb = background,
            sprites = t,
        )
        assertEquals(2, drawn, "行段 [1,3) 恰两行")
        assertArrayEquals(compose(frame2, t).pixels, target.pixels, "含图形行：按行重绘与整屏逐像素相同")
    }

    /** 用例 11 之三：部分行段 ⇒ 图形裁在段内、段外一个像素不动（repaintRows 列裁剪口径）。 */
    @Test
    fun `repaintRows部分行段：图形裁在段内、段外不动`() {
        val t = table("IMG" to solid(20, 20, red))
        val frame1 = listOf(textCmd(0, 0, "AB"))
        val frame2 = listOf(
            textCmd(0, 0, "AB"),
            rectCmd(1, 3, InlineGraphic.Rect(0, 0, 40, 10, 0x0000FF, 3)),
            imageCmd(2, 3, InlineGraphic.Image("IMG", 20, 20, 0, 2)),
        )
        val target = Raster(composer.canvasWidthPx, composer.canvasHeightPx)
        composer.composeInto(target, frame1, rowCount = 4, scene = ScreenScene(background), sprites = t)
        val drawn = composer.repaintRows(
            target, frame2, rowCount = 4,
            strips = listOf(DirtyRect(1, 3, 4, 6)),
            backgroundArgb = background,
            sprites = t,
        )
        assertEquals(2, drawn)
        // 行段列 [4,6) ⇒ 像素 [52,78)。rect 本体 x43..82 y25..34、image 本体 x43..62 y50..69。
        assertEquals(background, target.pixelAt(43, 25), "段外左侧：rect 起点 43 不许画")
        assertEquals(background, target.pixelAt(51, 25), "段左沿外一像素")
        assertEquals(blue, target.pixelAt(52, 25), "段内左沿起画")
        assertEquals(blue, target.pixelAt(77, 25), "段内右沿末列")
        assertEquals(blue, target.pixelAt(52, 34), "rect 下沿末行")
        assertEquals(background, target.pixelAt(78, 25), "段外右侧：rect 延伸到 82 也不许画")
        assertEquals(background, target.pixelAt(52, 35), "rect 下沿外")
        assertEquals(background, target.pixelAt(43, 50), "段外：image 起点 43 不许画")
        assertEquals(red, target.pixelAt(52, 50), "段内 image 左沿")
        assertEquals(red, target.pixelAt(62, 59), "image 右沿末列（段内）")
        assertEquals(red, target.pixelAt(52, 69), "image 下沿末行")
        assertEquals(background, target.pixelAt(63, 50), "段内但图外：擦背景后不许有残墨")
        assertEquals(background, target.pixelAt(52, 70), "image 下沿外")
    }

    /** 用例 11 之四（ConsoleFrame 接线）：按行路径透传 sprites，图形行换资源只重绘一行。 */
    @Test
    fun `ConsoleFrame按行路径透传sprites：图形行换图只重绘一行`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 4)
        val frame = ConsoleFrame(composer)
        val t = table("RED" to solid(20, 20, red), "BLUE" to solid(20, 20, blue))
        val scene = ScreenScene(background)
        fun lines(resource: String) = listOf(
            ScreenRows.textLine("AAA"),
            ScreenRows.line(
                TextRun(
                    text = "",
                    fore = fore,
                    back = null,
                    styleMask = 0,
                    fontName = ScreenRows.DEFAULT_FONT,
                    kind = RunKind.IMAGE,
                    graphic = InlineGraphic.Image(resource, 20, 20, 0, 2),
                ),
            ),
            ScreenRows.textLine("CCC"),
        )
        var rects = screen.submitSnapshot(lines("RED"))
        val first = frame.draw(screen, rects, scene = scene, sprites = t)
        assertTrue(frame.lastWasFull, "首帧整屏")
        assertEquals(red, first.pixelAt(4, 50), "rowCount=3 ⇒ row1 行顶 = 100-2*25 = 50，x = 0+shift4")
        rects = screen.submitSnapshot(lines("BLUE"))
        val second = frame.draw(screen, rects, scene = scene, sprites = t)
        assertFalse(frame.lastWasFull, "图形行换资源只脏一行 ⇒ 按行路径（不退整屏）")
        assertEquals(1, frame.lastRowsRepainted, "只有中间那一行变")
        assertEquals(blue, second.pixelAt(4, 50), "按行路径必须拿到 sprites 才能换图（没透传这里还是红）")
        val expected = composer.composeInto(
            Raster(composer.canvasWidthPx, composer.canvasHeightPx),
            screen.list.render(), rowCount = 3, scene = scene, sprites = t,
        )
        assertArrayEquals(expected.pixels, second.pixels, "按行与整屏逐像素相同")
    }

    /** 用例 12：文本 + 图形混排，文本段像素与纯文本帧逐字节相同（文本路径一字不动的直接对拍）。 */
    @Test
    fun `文本图形混排：文本段像素与纯文本帧逐字节相同`() {
        val t = table("IMG" to solid(20, 20, red))
        val mixed = compose(
            listOf(
                textCmd(0, 0, "AB"),
                imageCmd(0, 2, InlineGraphic.Image("IMG", 20, 20, 0, 2)),
                textCmd(0, 4, "CD"),
            ),
            t,
        )
        assertEquals(fore, mixed.pixelAt(0, 2), "A 在 col0")
        assertEquals(fore, mixed.pixelAt(13, 2), "B 在 col1")
        assertEquals(red, mixed.pixelAt(30, 0), "图在 col2：x=26+4=30")
        assertEquals(red, mixed.pixelAt(49, 19), "图右下角")
        assertEquals(fore, mixed.pixelAt(52, 2), "C 在 col4（图占 2 格，游标照常前进）")
        assertEquals(fore, mixed.pixelAt(65, 2), "D 在 col5")
        assertEquals(background, mixed.pixelAt(25, 2), "B 与图之间的间隙")
        assertEquals(background, mixed.pixelAt(29, 0), "图左沿外（shift 间隙）")
        assertEquals(background, mixed.pixelAt(50, 0), "图右沿外")
        val textOnly = compose(listOf(textCmd(0, 0, "AB"), textCmd(0, 4, "CD")))
        for (y in 0 until 25) {
            for (x in 0 until 26) {
                assertEquals(textOnly.pixelAt(x, y), mixed.pixelAt(x, y), "AB 文本区 ($x,$y) 逐字节不变")
            }
            for (x in 52 until 77) {
                assertEquals(textOnly.pixelAt(x, y), mixed.pixelAt(x, y), "CD 文本区 ($x,$y) 逐字节不变")
            }
        }
    }
}