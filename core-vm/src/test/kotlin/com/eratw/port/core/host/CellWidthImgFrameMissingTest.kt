package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * `<img>` **缺帧面**（尺寸表命中、绘制时帧拿不到）在 core 折行/行内游标这一侧的读数
 * （MODERN-R12-DIVW2）。与 [CellWidthImgMissTest]（图元 miss 面）并列的第三种接线状态：
 *
 * 判据 = 反编译 `ConsoleImagePart.cs`：
 * - `:60-65` 单出口：`cImage == null` ⇒ `Text = AltText` 后立刻 `return`，显式
 *   `width`/`height` 全不生效；
 * - `:104-115` `SetWidth`：`cImage == null` 支的宽 = `StringMeasure.GetDisplayLength(Text)`
 *   —— 移植侧 = [CellWidth.displayCells]（与渲染侧 `Grid.cells` 同口径）；
 * - `:168` 绘制守卫 `aSprite != null && aSprite.IsCreated` 不成立 ⇒ `:175-182` 画 AltText。
 *   尺寸表命中而帧不可用（帧表空 / 缓冲未建 / 位图解不出）时，原版的**画**落 AltText，
 *   R9-B 已复刻；本波把**量**统一到同一把文本尺（宽度判据唯一名下 =
 *   [CellWidth.imgWidthPxOrNull]，render 展平 `HtmlRunFlatten.emitImage` 消费同一判据）。
 *
 * 四态分界（[frameAvailableOf] 的接线纪律与 `imageSizeOf` 同构）：
 * 1. `imageSizeOf` 未接线 ⇒ 判不了 miss，保持旧读数（CellWidthImgMissTest 钉过，不重复）；
 * 2. 接线且查表 miss ⇒ AltText 文本格（miss 面，既有）；
 * 3. 接线且命中、`frameAvailableOf` **未接线**（null）⇒ 判不了缺帧面，宽度保持图宽（旧读数）；
 * 4. 接线且命中、`frameAvailableOf` 已接线 ⇒ 帧可用 = 图宽（有帧路径逐位不变），
 *    帧不可用 = AltText 文本格（本波新增，显式 `width` 同样不生效）。
 */
class CellWidthImgFrameMissingTest {

    private val fontPx = 18L

    /** 宿主已接线、帧不可用（draw 时拿不到帧）。 */
    private val frameGone: (String) -> Boolean = { false }

    /** 宿主已接线、帧可用。 */
    private val frameOk: (String) -> Boolean = { true }

    private fun sized(w: Int, h: Int): (String) -> ImageSizePx? = { ImageSizePx(w, h) }

    // ------------------------------------------------------------------
    // 1. 尺寸命中 + 帧不可用：AltText 当文本（显式 width 不生效）
    // ------------------------------------------------------------------

    @Test
    fun `尺寸命中帧不可用按 AltText 文本计格且显式宽不生效`() {
        val tag = "<img src='pic' width='180px'>"
        assertNull(
            CellWidth.graphicWidthPxOrNull(tag, fontPx, sized(64, 32), frameGone),
            "null = 落原版单出口（调用方按 AltText 文本量宽，ConsoleImagePart.cs:104-115）",
        )
        val cells = CellWidth.visibleHtmlCells(tag, fontPx, sized(64, 32), frameGone)
        assertEquals(
            CellWidth.displayCells(CellWidth.imgAltText(tag, fontPx)!!),
            cells,
            "宽 = AltText 的显示格（与画回落同一把文本尺）",
        )
        assertEquals(29, cells, "重建文案 `<img src='pic' width='180px'>` = 29 半角格")
        assertNotEquals(20, cells, "显式 width='180px' 不生效 —— 单出口的 return 在尺寸计算之前")
    }

    @Test
    fun `缺宽命中帧不可用同样落 AltText 文本尺`() {
        val tag = "<img src='pic' height='30px'>"
        val cells = CellWidth.visibleHtmlCells(tag, fontPx, sized(100, 50), frameGone)
        assertEquals(
            CellWidth.displayCells(CellWidth.imgAltText(tag, fontPx)!!),
            cells,
            "纵横比推宽支同样被单出口跳过（:60-65 的 return 在 :66-82 之前）",
        )
        assertEquals(29, cells, "重建文案 `<img src='pic' height='30px'>` = 29 半角格（不是推宽的 7 格）")
    }

    // ------------------------------------------------------------------
    // 2. 帧可用 / 未接线：旧读数逐位不变（有帧路径与接线前面 untouched）
    // ------------------------------------------------------------------

    @Test
    fun `帧可用时命中支读数逐位不变`() {
        val explicit = "<img src='pic' width='180px' height='50'>"
        assertEquals(180L, CellWidth.graphicWidthPxOrNull(explicit, fontPx, sized(64, 32), frameOk))
        assertEquals(20, CellWidth.visibleHtmlCells(explicit, fontPx, sized(64, 32), frameOk))
        val aspect = "<img src='pic' height='30px'>"
        assertEquals(60L, CellWidth.graphicWidthPxOrNull(aspect, fontPx, sized(100, 50), frameOk))
        assertEquals(7, CellWidth.visibleHtmlCells(aspect, fontPx, sized(100, 50), frameOk))
    }

    @Test
    fun `帧查询未接线时保持图宽旧读数`() {
        val tag = "<img src='pic' width='180px'>"
        assertEquals(
            180L,
            CellWidth.graphicWidthPxOrNull(tag, fontPx, sized(64, 32), null),
            "判不了缺帧面 ⇒ 与接线前同读数（imageSizeOf 未接线时同一纪律）",
        )
        assertEquals(20, CellWidth.visibleHtmlCells(tag, fontPx, sized(64, 32), null))
    }

    @Test
    fun `基图高为零仍按 miss 支处理`() {
        val tag = "<img src='pic' width='180px'>"
        assertNull(
            CellWidth.graphicWidthPxOrNull(tag, fontPx, sized(100, 0), frameOk),
            "基图高 <= 0 是 miss 面（C2 既有判据），优先于帧面",
        )
        assertEquals(
            CellWidth.displayCells(CellWidth.imgAltText(tag, fontPx)!!),
            CellWidth.visibleHtmlCells(tag, fontPx, sized(100, 0), frameOk),
        )
    }

    // ------------------------------------------------------------------
    // 3. sink 行内游标接线：div 锚点按 AltText 文本宽推进
    // ------------------------------------------------------------------

    private fun card(xpos: Int) =
        "<div xpos='${xpos}px' ypos='0px' width='300px' height='180px'>台词</div>"

    private fun singleBlock(sink: LineBufferConsoleSink): DisplayDivBlock =
        sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single()

    @Test
    fun `帧不可用时 div 锚点按 AltText 文本宽推进`() {
        val tag = "<img src='face' width='180px' height='180px'>"
        val sink = LineBufferConsoleSink(
            clientWidth = 400,
            scriptFontSize = 18,
            imageSizeOf = { ImageSizePx(64, 32) },
            frameAvailableOf = frameGone,
        )
        sink.printHtml("<nobr>" + tag + card(20) + "</nobr>", false)
        val b = singleBlock(sink)

        // 重建文案 = `<img src='face' height='180px' width='180px'>`（height 在 width 之前
        // 重写，ConsoleImagePart.cs:52-57 次序）= 45 半角格。
        assertEquals(
            CellWidth.displayCells(CellWidth.imgAltText(tag, 18L)!!),
            b.anchorCol,
            "锚点格列 = AltText 文本宽（与折行同尺），不是图宽的 20",
        )
        assertEquals(45, b.anchorCol, "重建文案 45 格（硬编码读数防回归）")
        assertEquals(405, b.anchorPointXPx, "锚点像素 = 45 格 × 18/2（textWidthPx 的未接线回落式）")
        assertEquals(20, b.xOffsetPx, "xpos 仍由块自己带，锚点不掺它")

        // 控制：同一形态、帧可用 ⇒ 既有读数（图宽 180px / 20 格）逐位不变。
        val framed = LineBufferConsoleSink(
            clientWidth = 400,
            scriptFontSize = 18,
            imageSizeOf = { ImageSizePx(64, 32) },
            frameAvailableOf = frameOk,
        )
        framed.printHtml("<nobr><img src='face' width='180px' height='180px'>" + card(20) + "</nobr>", false)
        val fb = singleBlock(framed)
        assertEquals(180, fb.anchorPointXPx, "帧可用：锚点像素 = 图宽（原版像素游标，不变）")
        assertEquals(20, fb.anchorCol, "帧可用：锚点格列 = 图宽折格（不变）")
    }

    // ------------------------------------------------------------------
    // 4. sink 折行预算接线：帧不可用的图元按文本宽参与折行
    // ------------------------------------------------------------------

    @Test
    fun `帧不可用时折行预算按 AltText 文本宽判行`() {
        // 行宽 30 格；前缀「ああ」4 格。HTML 段原子折行（cur.col + 段宽 > 行宽 ⇒ 整段挪行）。
        // htmlOption = true（toPrintBuffer 形态）：HTML 段可排在打印缓冲行中段、随普通
        // Flush 折行（直印形态恒自成一行，测不了同 row 折行）。帧不可用 ⇒ 段宽 = AltText
        // 文本 44 格 ⇒ 4 + 44 > 30 ⇒ 挪到第 2 行（共 2 行）；帧可用 ⇒ 段宽 = 图宽 20 格
        // ⇒ 4 + 20 = 24 <= 30 ⇒ 留在第 1 行（共 1 行）。
        fun sinkOf(frames: Boolean) = LineBufferConsoleSink(
            clientWidth = 30,
            scriptFontSize = 18,
            imageSizeOf = { ImageSizePx(64, 32) },
            frameAvailableOf = if (frames) frameOk else frameGone,
        )

        val gone = sinkOf(false)
        gone.print("ああ")
        gone.printHtml("<img src='pic' width='180px' height='180px'>", true)
        gone.newLine()
        assertEquals(
            2,
            gone.snapshot().size,
            "帧不可用：折行预算按 AltText 文本宽，HTML 段整段挪行",
        )

        val ok = sinkOf(true)
        ok.print("ああ")
        ok.printHtml("<img src='pic' width='180px' height='180px'>", true)
        ok.newLine()
        assertEquals(1, ok.snapshot().size, "帧可用：图宽 20 格留在首行（有帧路径不变）")
    }
}
