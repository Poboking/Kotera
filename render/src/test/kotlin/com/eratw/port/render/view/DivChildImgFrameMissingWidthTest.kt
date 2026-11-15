package com.eratw.port.render.view

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * div 子行 `<img>` **缺帧行宽度口径**（MODERN-R12-DIVW2，R9-B 验收登记②）：
 * 尺寸表**命中**而绘制时**帧拿不到**时，宽度必须与画回落（R9-B）走**同一把 AltText
 * 文本尺** —— 子行里后续列从 AltText 文本宽起算，不再按图宽入表（旧病：行宽用图宽、
 * 画用文本宽，后续列偏 `|图宽 − 文本宽|`，与回落文案叠字）。
 *
 * 断言的原版语义（`reference/emuera-cs/`）：
 * - `ConsoleImagePart.cs:60-65` 单出口：`cImage == null` ⇒ `Text = AltText`，
 *   显式 `width`/`height` 全不生效；`:104-115` 按 AltText 文本量宽；
 * - div 子行同路（`ConsoleDivPart.cs:198-201` 逐子行 `DrawTo`，节点管线同一套）；
 * - 绘制侧缺帧回落（`:168` 守卫不成立 ⇒ `:175-182`）R9-B 已复刻，本波把**量**统一过去。
 *
 * 判据唯一名下 = core `CellWidth.imgWidthPxOrNull`；render 侧消费点 =
 * `HtmlRunFlatten.emitImage`（帧不可用 ⇒ 与尺寸 miss 同一条 `emitImageAltText` 单出口）。
 * 帧可用性来源 = [HtmlRunFlatten.ImageSizeSource.frameAvailable]（缺省 true = 未接线，
 * 保持旧读数 —— 与尺寸源未接线同一纪律）。
 *
 * 全部用例只吃 headless 栅格（纯 JVM），读数走 `ChildRow.cmds` 列位与 `Raster.pixelAt`。
 */
class DivChildImgFrameMissingWidthTest {

    private val metrics = CellMetrics(18, 18)

    private val background = ScreenScene.DEFAULT_BACKGROUND
    private val fore = 0xFFC0C0C0.toInt()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** 块锚定的行条顶（`ConsoleDivPart.DrawTo` 的 pointY；EmueraConsole.cs:1870）。 */
    private val anchorTopPx = 10

    /** 尺寸恒命中 20×60、帧可用性可注入的尺寸源（`frameAvailable` 覆写 = 已接线）。 */
    private class Sourced(private val frames: Boolean) : HtmlRunFlatten.ImageSizeSource {
        override fun destSize(resource: String): Int? = (20 shl 16) or 60
        override fun frameAvailable(resource: String): Boolean = frames
    }

    /** 尺寸 miss 源（C2 既有面，作同尺对拍的控制组）。 */
    private val missSource = HtmlRunFlatten.ImageSizeSource { null }

    /** SAM 缺省（不覆写 `frameAvailable` = 未接帧判定）：保持图宽旧读数。 */
    private val plainSource = HtmlRunFlatten.ImageSizeSource { (20 shl 16) or 60 }

    private fun composer(source: HtmlRunFlatten.ImageSizeSource) = ScreenComposer(
        clientWidthCells = 100,
        heightLines = 20,
        metrics = metrics,
        canvasHeightPx = 20 * 18,
        sizeSource = source,
    )

    private fun block(
        owner: ScreenComposer,
        children: List<String>,
        widthPx: Int = 600,
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
            heightPx = 60,
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
    ): Raster = owner.compose(
        commands = emptyList(),
        rowCount = 20,
        scene = ScreenScene(),
        sprites = sprites,
        overlay = DivOverlayFrame(emptyList(), listOf(block(owner, children))),
    )

    private fun solid(width: Int, height: Int, argb: Int): Raster =
        Raster(width, height).also { it.fillRect(0, 0, width, height, argb) }

    // ------------------------------------------------------------------
    // 1) 缺帧：后续列从 AltText 文本宽起算（红 X 不再叠在图宽列上）
    // ------------------------------------------------------------------

    @Test
    fun `缺帧时子行后续列从 AltText 文本宽起算`() {
        val owner = composer(Sourced(frames = false))
        val row = block(owner, listOf("<img src='PIC' width='20px'><font color='#FF0000'>X</font>"))
            .children.single()
        assertEquals(
            28,
            row.cmds.last().colStart,
            "缺帧：后续列 = AltText 文本宽 28 格（与画回落同尺），不是图宽的 3 格",
        )
        val canvas = compose(owner, listOf("<img src='PIC' width='20px'><font color='#FF0000'>X</font>"))
        assertEquals(
            red,
            canvas.pixelAt(263, 13),
            "红 X 落在第 28 格（cellsToPixels(28) = 262）—— 行宽与画同一把文本尺",
        )
        assertEquals(
            fore,
            canvas.pixelAt(30, 13),
            "第 3 格是 AltText 的字形：不再被按图宽推进的后续列叠盖（旧病读数 = 红）",
        )
    }

    // ------------------------------------------------------------------
    // 2) 控制组：帧可用 ⇒ 有帧路径逐格不变
    // ------------------------------------------------------------------

    @Test
    fun `帧可用时有帧路径逐格不变`() {
        val owner = composer(Sourced(frames = true))
        val sprites = SpriteTable().also { it.add("PIC", solid(20, 20, blue)) }
        val row = block(owner, listOf("<img src='PIC' width='20px'><font color='#FF0000'>X</font>"))
            .children.single()
        assertEquals(
            3,
            row.cmds.last().colStart,
            "帧可用：后续列 = 图宽 3 格（cellsFor(20px) = ceil(20 / 9.36)）—— 接线前读数不变",
        )
        val canvas = compose(
            owner,
            listOf("<img src='PIC' width='20px'><font color='#FF0000'>X</font>"),
            sprites = sprites,
        )
        assertEquals(blue, canvas.pixelAt(5, 13), "有帧：图照常贴图（左上角 = 裸列游标 + shift 3）")
        assertEquals(red, canvas.pixelAt(30, 13), "有帧：红 X 落在第 3 格（图宽列）")
        assertEquals(background, canvas.pixelAt(263, 13), "有帧：第 28 格起为空 —— 图宽读数没被本波改动")
    }

    // ------------------------------------------------------------------
    // 3) 控制组：尺寸 miss 面与缺帧面同一把文本尺
    // ------------------------------------------------------------------

    @Test
    fun `尺寸 miss 面与缺帧面同一把文本尺`() {
        val owner = composer(missSource)
        val row = block(owner, listOf("<img src='PIC' width='20px'><font color='#FF0000'>X</font>"))
            .children.single()
        assertEquals(
            28,
            row.cmds.last().colStart,
            "miss 面（C2 既有）的后续列 = AltText 文本宽 —— 缺帧面必须同值（一条判据）",
        )
    }

    // ------------------------------------------------------------------
    // 4) 控制组：未接帧判定（SAM 缺省）保持图宽旧读数
    // ------------------------------------------------------------------

    @Test
    fun `未接帧判定时保持图宽旧读数`() {
        val owner = composer(plainSource)
        val row = block(owner, listOf("<img src='PIC' width='20px'><font color='#FF0000'>X</font>"))
            .children.single()
        assertEquals(
            3,
            row.cmds.last().colStart,
            "frameAvailable 缺省 true = 未接线 ⇒ 判不了缺帧面，宽度保持图宽（接线前读数）",
        )
    }
}
