package com.eratw.port.core.fn

import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.ImageSizePx
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 缺帧面（MODERN-R12-WIRE 接线）在**回读列坐标**的读数：`consolePrintedLine` 的段宽必须
 * 吃 `frameAvailableOf`（与 sink 折行/行内游标同一份数据源），否则 `HTML_GETPRINTEDSTR` /
 * `GETDISPLAYLINE` 的按钮包裹位置与屏上错位。
 *
 * ## 钉的是哪条链
 *
 * sink 侧（排版真相）在 DIVW2 已接线：`segmentWidth` → `CellWidth.visibleHtmlCells(…,
 * frameAvailableOf)`，帧不可用的 `<img>` 按 AltText 文本量宽。回读侧（本面）此前没有
 * 帧参（`visibleHtmlCells(seg.text, graphicFontPx, imageSizeOf)`）⇒ 段宽保持**图宽**，
 * 而 sink 排出的 `buttons.startCol` 已按**文本宽**推进 —— 两侧分叉，按钮开不出/开错段。
 * 生产喂参点 = `ConsoleBuiltinHost.printedLinesOf`（取 `sink?.frameAvailableOf`）。
 *
 * 判据唯一名下 = `CellWidth.imgWidthPxOrNull` 的文档表（缺帧 ⇒ AltText 文本尺、显式
 * width/height 不生效）；本文件不复制第二份读数，格数一律与 `CellWidth.displayCells(
 * CellWidth.imgAltText(tag, 18L))` 比。
 */
class HtmlPrintedWidthFrameMissingTest {

    private class Fixture(
        val sink: LineBufferConsoleSink,
        val line: LineBufferConsoleSink.DisplayLine,
    ) {
        /** [withFrame] = false 复刻「回读链漏喂帧参」的接线前形态（红面锚）。 */
        fun printed(withFrame: Boolean): ConsolePrintedLine = consolePrintedLine(
            line,
            sink.defaultFontName,
            sink.scriptFontSize.toLong(),
            sink.imageSizeOf,
            frameAvailableOf = if (withFrame) sink.frameAvailableOf else null,
        )
    }

    /** HTML 段后接一颗 PRINTBUTTON（原版 AppendButton，PrintStringBuffer.cs:103-123）。 */
    private fun htmlThenButton(
        html: String,
        frames: Boolean,
    ): Fixture {
        val sink = LineBufferConsoleSink(
            scriptFontSize = 18,
            imageSizeOf = { ImageSizePx(64, 32) },
            frameAvailableOf = { frames },
        )
        sink.printHtml(html, option = true)
        sink.printButton(BUTTON_TEXT, BUTTON_VALUE)
        sink.newLine()
        return Fixture(sink, sink.lines().single())
    }

    @Test
    fun `帧不可用时回读按钮包裹随 AltText 文本宽开合`() {
        val html = "<img src='a.png' width='40px'>"
        val fx = htmlThenButton(html, frames = false)

        // sink 侧（排版真相）：帧不可用 ⇒ 段宽 = AltText 文本格，按钮列随之推进。
        val altCells = CellWidth.displayCells(CellWidth.imgAltText(html, 18L)!!)
        assertEquals(altCells, fx.line.buttons.single().startCol)
        assertTrue(altCells > CellWidth.graphicCells(40L, 18L), "夹具前提：文本宽 > 图宽（分叉才可观测）")

        // 回读侧（本面）：喂了帧参 ⇒ 段宽与 sink 同尺，按钮照常开在 HTML 段之后。
        val printed = fx.printed(withFrame = true)
        assertEquals("$html<button value='1'>$BUTTON_TEXT</button>", printed.html)

        // 红面锚：回读链漏喂帧参（接线前形态）⇒ 段宽停在图宽 5 格 < 按钮列，
        // 按钮开不出来 —— 这就是本接线要消灭的「回读与屏上错位」。
        val legacy = fx.printed(withFrame = false)
        assertFalse(
            legacy.html.contains("<button"),
            "漏喂帧参的回读开不出按钮（图宽 5 格 < 按钮 startCol），证明帧参真的在起作用",
        )
    }

    @Test
    fun `帧可用时回读读数与接线前逐值一致`() {
        val html = "<img src='a.png' width='40px' height='18px'>"
        val fx = htmlThenButton(html, frames = true)

        val expectedCells = CellWidth.graphicCells(40L, 18L)
        assertEquals(5, expectedCells, "40px / (18/2*1.04) 向上取整 = 5 格（有帧路径不变）")
        assertEquals(expectedCells, fx.line.buttons.single().startCol)
        assertEquals("$html<button value='1'>$BUTTON_TEXT</button>", fx.printed(withFrame = true).html)
    }

    @Test
    fun `帧查询未接线的 sink 回读保持旧读数`() {
        // 生产在 headless / 未喂帧参的构造点：sink.frameAvailableOf = null ⇒ 回读侧即使
        // 喂 null 也判不了缺帧面，读数与接线前逐位一致（DIVW2 关态语义）。
        val sink = LineBufferConsoleSink(
            scriptFontSize = 18,
            imageSizeOf = { ImageSizePx(64, 32) },
        )
        sink.printHtml("<img src='a.png' width='40px'>", option = true)
        sink.printButton(BUTTON_TEXT, BUTTON_VALUE)
        sink.newLine()
        val printed = consolePrintedLine(
            sink.lines().single(),
            sink.defaultFontName,
            sink.scriptFontSize.toLong(),
            sink.imageSizeOf,
            frameAvailableOf = sink.frameAvailableOf,
        )
        assertEquals(
            "<img src='a.png' width='40px'><button value='1'>$BUTTON_TEXT</button>",
            printed.html,
            "未接线 = 图宽旧读数（与 CellWidthImgFrameMissingTest 的第 3 态同一纪律）",
        )
    }

    private companion object {
        const val BUTTON_TEXT = "继续"
        const val BUTTON_VALUE = 1L
    }
}
