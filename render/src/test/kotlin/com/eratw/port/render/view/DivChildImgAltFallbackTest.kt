package com.eratw.port.render.view

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * div 子行 `<img>` **缺帧回落 AltText**（MODERN-R9-B，ADR-005 复刻缺口）。
 *
 * 断言的原版语义（`reference/emuera-cs/`）：
 * - 构造期无条件重建 AltText（`ConsoleImagePart.cs:44-59`）；
 * - 绘制前守卫 `aSprite != null && aSprite.IsCreated`（`:168`），**不成立**的那一支
 *   `graph.DrawString(AltText, Config.DefaultFont, ForeColor, new Point(PointX, pointY))`
 *   （`:177`；文本模式 `:181` 同落点）—— 三条要点：**裸 `PointX`**（不加
 *   `Config.DrawingParam_ShapePositionShift`）、**裸 `pointY`**（不加 `ypos`）、
 *   色 `Config.ForeColor`（不是焦点/按下色）；
 * - div 子行走同一条绘制路径（`ConsoleDivPart.cs:198-201` 逐子行 `DrawTo`），内容盒裁剪
 *   `:183,196`。
 *
 * 全部用例只吃 headless 栅格（纯 JVM），读数走 `Raster.pixelAt`。三条形态在这里分开钉：
 * ① 尺寸源 miss ⇒ 展平成 TEXT（`ScreenComposerDivImgMissTest` 既有）；
 * ② 未接尺寸源 ⇒ `altText` 恒 null ⇒ 静默（本类 `未接尺寸源` 用例）；
 * ③ 尺寸已知、帧取不到 ⇒ 画 AltText（本类其余用例）。
 */
class DivChildImgAltFallbackTest {

    private val metrics = CellMetrics(18, 18)

    private val background = ScreenScene.DEFAULT_BACKGROUND
    private val fore = 0xFFC0C0C0.toInt()
    private val red = 0xFFFF0000.toInt()

    /** 块锚定的行条顶（`ConsoleDivPart.DrawTo` 的 pointY；EmueraConsole.cs:1870）。 */
    private val anchorTopPx = 10

    /** `max(2, FontSize/6)` = 3（`Config.cs:461-465`，DivChildGraphicTest 同值）。 */
    private val shapeShift = 3

    /** 接上尺寸源 ⇒ `<img>` 命中支、`altText` 已填（[HtmlRunFlatten.emitImage]）。 */
    private fun sized(source: HtmlRunFlatten.ImageSizeSource) = ScreenComposer(
        clientWidthCells = 100,
        heightLines = 20,
        metrics = metrics,
        canvasHeightPx = 20 * 18,
        sizeSource = source,
    )

    private fun sizeOf(w: Int, h: Int): HtmlRunFlatten.ImageSizeSource =
        HtmlRunFlatten.ImageSizeSource { (w shl 16) or h }

    private fun block(
        owner: ScreenComposer,
        children: List<String>,
        widthPx: Int = 100,
        heightPx: Int = 60,
    ): DivOverlayPlanner.Block {
        val raw = DisplayDivBlock(
            altHeadTag = "<div>",
            anchorCol = 0,
            anchorPointXPx = 0,
            xOffsetPx = 0,
            divXOffsetPx = 0,
            pointYPx = 0,
            yOffsetPx = 0,
            widthPx = widthPx,
            heightPx = heightPx,
            subDivisionWidthPx = widthPx,
            depth = 0,
            backgroundColor = null,
            margin = emptyList(),
            padding = emptyList(),
            border = emptyList(),
            radius = emptyList(),
            borderColors = emptyList(),
            isRelative = true,
            isEscaped = true,
            children = children.map { DisplayDivChildLine(it) },
        )
        return owner.divPlanner.plan(
            blocks = listOf(raw),
            anchorTopPx = anchorTopPx,
            anchorPointXPx = 0,
            canvasHeightPx = owner.canvasHeightPx,
            visibleTopLine = 0,
            visibleBottomLine = 100,
            anchorLineNo = 0,
        ).all.single()
    }

    private fun compose(
        owner: ScreenComposer,
        children: List<String>,
        sprites: SpriteTable = SpriteTable(),
        pressed: ButtonLayer.DivFocus? = null,
    ): Raster = owner.compose(
        commands = emptyList(),
        rowCount = 20,
        scene = ScreenScene(),
        sprites = sprites,
        overlay = DivOverlayFrame(emptyList(), listOf(block(owner, children))),
        focusedDiv = null,
        pressedDiv = pressed,
    )

    private fun solid(width: Int, height: Int, argb: Int): Raster =
        Raster(width, height).also { it.fillRect(0, 0, width, height, argb) }

    /** 有表项但 `isCreated == false`：资源登记 → 单条释放 → 回滚复活（SpriteTable KDoc 条款 2）。 */
    private fun deadTable(name: String): SpriteTable {
        val table = SpriteTable()
        val id = table.add(name, solid(20, 20, red), fromCsv = true)
        table.dispose(id)
        table.disposeAll(false)
        return table
    }

    // ------------------------------------------------------------------
    // 1) 尺寸已知、sprite 表无该名、altText 有值 ⇒ 前景色字形
    // ------------------------------------------------------------------

    @Test
    fun `帧缺失时把 AltText 当文本画`() {
        val owner = sized(sizeOf(20, 20))
        val canvas = compose(
            owner,
            listOf("<img src='NO_SUCH_SPRITE' width='20px' height='20px'>"),
        )
        // 裸列游标 0 + 行顶 10 + GLYPH_INSET 2 ⇒ 首字形落 (0..8, 12..26)。
        assertEquals(fore, canvas.pixelAt(1, 13), "缺帧：画重建的 `<img …>` 文案（ConsoleImagePart.cs:168,177）")
        assertTrue(
            (20 until 60).any { canvas.pixelAt(it, 13) == fore },
            "整串一次画（`:177` 的 DrawString 单次调用），不是只出首字形",
        )
    }

    // ------------------------------------------------------------------
    // 2) altText == null ⇒ 一个字形都没有（旧行为保留）
    // ------------------------------------------------------------------

    @Test
    fun `未接尺寸源时空文案保持静默`() {
        // 未接尺寸源 ⇒ emitImage 判不了图元在不在，altText 保持 null（契约 §0 第 3 条）。
        val plain = ScreenComposer(
            clientWidthCells = 100,
            heightLines = 20,
            metrics = metrics,
            canvasHeightPx = 20 * 18,
        )
        val canvas = compose(plain, listOf("<img src='NO_SUCH_SPRITE' width='20px' height='20px'>"))
        assertEquals(background, canvas.pixelAt(3, 10), "帧缺失但无 altText ⇒ 旧行为：不落墨")
        assertTrue(
            (0 until 100).none { canvas.pixelAt(it, 13) == fore },
            "这条形态里行带上一个字形都没有",
        )
    }

    // ------------------------------------------------------------------
    // 3) 表里有条目但 isCreated == false ⇒ 同样出 altText
    // ------------------------------------------------------------------

    @Test
    fun `死条目同样回落 AltText`() {
        val owner = sized(sizeOf(20, 20))
        val table = deadTable("DEAD_IMG")
        // 先钉住「键在、对象死」这对读数（SpriteTable KDoc 复刻条款 2）。
        assertNotNull(table.find("DEAD_IMG"), "回滚复活：字典键仍在")
        assertFalse(table.find("DEAD_IMG")!!.isCreated, "对象已 Dispose：IsCreated 假")
        val canvas = compose(
            owner,
            listOf("<img src='DEAD_IMG' width='20px' height='20px'>"),
            sprites = table,
        )
        assertEquals(fore, canvas.pixelAt(1, 13), "`IsCreated` 守卫不成立 ⇒ 画 AltText（`:168,175-182`）")
    }

    // ------------------------------------------------------------------
    // 4) 按下图缺失退主图、主图也不可得 ⇒ 退 altText
    // ------------------------------------------------------------------

    @Test
    fun `按下图与主图都缺失时回落 AltText`() {
        val owner = sized(sizeOf(20, 20))
        val canvas = compose(
            owner,
            listOf("<img src='GONE' srcb='GONE_B' width='20px' height='20px'>"),
            pressed = ButtonLayer.DivFocus(0, 0, 0, 3),
        )
        // 按下图查不到 ⇒ 退主图（`:164` cImageB == null 判式）；主图也查不到 ⇒ 退 AltText。
        assertEquals(fore, canvas.pixelAt(1, 13), "两图都不可得：画 AltText 而不是留空")
        assertTrue(
            (20 until 60).any { canvas.pixelAt(it, 13) == fore },
            "回落的是整串文案",
        )
    }

    // ------------------------------------------------------------------
    // 5) 有帧时逐像素不变：altText 不许盖到图上
    // ------------------------------------------------------------------

    @Test
    fun `帧可用时仍按原几何贴图且不画 AltText`() {
        val owner = sized(sizeOf(20, 20))
        val sprites = SpriteTable().also { it.add("RED", solid(20, 20, red)) }
        val canvas = compose(
            owner,
            listOf("<img src='RED' width='20px' height='20px'>"),
            sprites = sprites,
        )
        assertEquals(red, canvas.pixelAt(shapeShift, 10), "图左上角 = 裸列游标 + shift（:171, ConsoleRectangleShapePart 同式）")
        assertEquals(red, canvas.pixelAt(22, 29), "20×20 的 1:1 贴图铺满 (3,10)-(23,30)")
        assertEquals(background, canvas.pixelAt(shapeShift - 1, 10), "shift 之前不落图（几何未变）")
        assertEquals(background, canvas.pixelAt(1, 13), "有帧时 altText 一个字都不许画（回落只在缺帧支）")
    }

    // ------------------------------------------------------------------
    // 6) 回落不加 divShapeShift、不加 ypos
    // ------------------------------------------------------------------

    @Test
    fun `回落落点用裸 PointX 与裸 pointY`() {
        val owner = sized(sizeOf(20, 20))
        val canvas = compose(
            owner,
            listOf("<img src='NO_SUCH_SPRITE' width='20px' height='20px' ypos='10px'>"),
        )
        // 裸 PointX = 0：若误加 shift(3)，x=1 会落空（首块从 x=3 起）。
        assertEquals(fore, canvas.pixelAt(1, 13), "裸 PointX：首字形从 x=0 起（不带 ShapePositionShift）")
        // 裸 pointY = 行顶 10：若误加 ypos(10)，字形会整体下移 10px（首字形落到 y=22 起），
        // 于是 y=13 落空、y=30 反而有墨。这两个读数正是「不加 ypos」的判据。
        assertEquals(fore, canvas.pixelAt(1, 13), "裸 pointY：落点不受 ypos 影响")
        assertEquals(background, canvas.pixelAt(1, 30), "y=30 处不落字形（加了 ypos 才会铺到这里）")
    }

    // ------------------------------------------------------------------
    // 7) 生产端：emitImage 的 altText 与 emitImageAltText 的文本段同串
    // ------------------------------------------------------------------

    private fun htmlRun(html: String) = TextRun(
        text = html,
        fore = 0xC0C0C0,
        back = 0,
        styleMask = 0,
        fontName = "msgothic",
        kind = RunKind.HTML,
    )

    @Test
    fun `命中支的 altText 与 miss 文本段同串`() {
        val tag = "<img src='PIC' width='20px' height='20px'>"
        val metrics = CellMetrics(18)
        // 命中支：尺寸源给出 20×20 ⇒ IMAGE 段，altText 必须已填。
        val hit = HtmlRunFlatten(metrics, sizeOf(20, 20), scriptFontPixelSize = 18)
            .flatten(htmlRun(tag))
            .single()
        val image = hit.graphic as InlineGraphic.Image
        assertEquals(
            "<img src='PIC' height='20px' width='20px'>",
            image.altText,
            "构造期重建（ConsoleImagePart.cs:44-59；次序 src→srcb→srcm→height→width→ypos）",
        )
        // miss 支：尺寸源查不到 ⇒ emitImageAltText 的 TEXT 段。
        val miss = HtmlRunFlatten(metrics, HtmlRunFlatten.ImageSizeSource { null }, scriptFontPixelSize = 18)
            .flatten(htmlRun(tag))
            .single()
        assertEquals(RunKind.TEXT, miss.kind)
        assertEquals(
            miss.text,
            image.altText,
            "同一标签：命中支载体上的 altText 与 miss 文本段逐字符同串（单源 imageAltText）",
        )
    }
}
