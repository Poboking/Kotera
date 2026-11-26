package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DIV-WIRE-2 帧级集成：**真机那条路**（`ConsoleFrame.draw`）上 div 子行按钮可点、tooltip 可取。
 *
 * 这条钉的是「接线」而不是「算法」：`ButtonLayer` 的 div 命中表靠
 * `ConsoleScreen.updateDivOverlay(overlay, metrics, canvasHeightPx)` 每帧喂同一份
 * [com.eratw.port.render.adapter.DivOverlayFrame]（`ConsoleFrame.draw` 里紧挨
 * `divOverlayFrame(screen)` 之后那一行）。少了这行，B 路所有单测照样绿、真机却点不到 ——
 * 所以这里从 sink 打印开始、走到 `frame.draw`、再用**像素**去问命中。
 *
 * 口径：命中几何照 `ConsoleDivPart.TestChildHitbox`（`ConsoleDivPart.cs:134-172`）——
 * 盒矩形宽用脚本 `width`（不是绘制侧 `width+2`）、子行带 `rect.Y + 子行号 × 行高`、
 * 子行 x 带含 `xInset`。
 */
class DivFrameHitTest {

    private val metrics = CellMetrics()
    private val clientWidth = 40
    private val heightLines = 20

    /** 一张 div 卡：底 `#363636`、`padding='4px'`、子行里一颗 `value='7'` 的按钮（带 title）。 */
    private val pageHtml =
        "<div xpos='0px' ypos='0px' width='120px' height='40px' padding='4px' color='#363636'>" +
            "<button value='7' title='棋子卡'>DIVBTN</button></div>"

    private fun buildScreen(): Pair<ConsoleScreen, ScreenComposer> {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        sink.printHtml(pageHtml, false)
        val screen = ConsoleScreen(clientWidth, 100, heightLines)
        screen.submitSnapshot(ConsoleAdapter.adapt(sink.snapshot()))
        val composer = ScreenComposer(
            clientWidth,
            heightLines,
            metrics,
            canvasHeightPx = heightLines * metrics.lineHeight,
        )
        return screen to composer
    }

    /**
     * `frame.draw` 之后，命中表必须已经按**这一帧的几何**建好：
     * 像素 → 子行按钮值；盒外 null；tooltip 取到的是按钮 label。
     */
    @Test
    fun `ConsoleFrame 每帧喂命中表后 div 子行按钮可点`() {
        val (screen, composer) = buildScreen()
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.list.dirtyStrips())

        // 子行首字形：内容盒左沿（= borderRect.x + padding）+ 半个格，取字形中心像素。
        val rows = screen.visibleRows()
        assertEquals(1, rows.size, "div 页只有一条锚定行（子行不是显示行）")
        val rowH = metrics.lineHeight
        val rowTop = composer.rowTopPx(0, rows.size)
        // padding=4：内容盒左沿 = 盒 x(0) + 4；子行 y = 盒顶 + 4
        val xPx = 4 + metrics.cellsToPixels(0) + 2
        val yPx = rowTop + 4 + rowH / 2

        val hit = screen.divHitAt(xPx, yPx)
        assertNotNull(hit, "帧级路径上 div 子行按钮必须可点（$xPx,$yPx）")
        assertEquals(7L, hit.value, "点到的应是 value='7' 那颗")
        assertEquals(0, hit.blockIndex, "Plan.all 全局下标：只有一颗块")
        assertEquals(0, hit.childRow)
        assertEquals("棋子卡", screen.divTooltipAt(xPx, yPx), "tooltip 取按钮 title")
    }

    /** 盒子外（画布右下方）必须不命中，且不会误报成正文行。 */
    @Test
    fun `盒外像素不命中 div`() {
        val (screen, composer) = buildScreen()
        ConsoleFrame(composer).draw(screen, screen.list.dirtyStrips())
        assertNull(screen.divHitAt(composer.canvasWidthPx - 1, composer.canvasHeightPx - 1))
    }

    // ---- B9 定案锁（判词 7）：真语料负 depth 弹窗在场时，正文行链接仍可点 ----

    /**
     * 语料 `ERB/魔改内容/qol/QOL_USERCOM.ERB @COM_OPTION`（负 depth 在 :284 的 ATR 行）与
     * `ERB/魔改内容/PRINT系/UI_Container_Framework.ERB:275` 的 `STYLE_POPUP` 同形：弹窗只给
     * `depth='-1'`，整帧 depth 键表全负。
     *
     * 官方在这种帧里正文行**点不动**：正文行那趟扫描只在首个非负 depth 键处插一次
     * （1.824 `EmueraConsole.cs:2342-2343` 摊平升序 + `:2350` 的 `if (!flag3 && num7 >= 0)`；
     * 蛇版 4.981 `Emuera/UI/Game/EmueraConsole.cs:2604-2605` + `:2612` 的 `if (!zeroTested && depth >= 0)`），
     * 键表全负 ⇒ 门闸永不触发 ⇒ `HtmlManager.cs:723-739` 登记的正文链接与 `PRINTBUTTON` 整片失效。
     * 移植侧按判词 7 取优雅侧：`ButtonLayer.kt:836` / `:865-869` 的正文行扫描无门闸。
     *
     * 本用例是**定案锁**（正文行像素没被负 depth 弹窗吞、点击链仍能提交）；
     * 能测出「改回官方形」的突变敏感锁在 `ButtonLayerDivHitTest` 的 ⑥ 节提示用例上 ——
     * 理由见那里的 KDoc：键全负时 hitAt 两种写法同解（都回 null），点击面靠调用方兜底。
     */
    @Test
    fun `真语料负 depth 弹窗在场时正文行按钮仍可点`() {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        // 正文行可点件（语料 `PRINTBUTTON 泡茶, 301` 一路）＋弹窗锚行，弹窗 box 压在同一段行带上
        sink.printButtonC("泡茶", 301L, true)
        sink.printHtml(
            "<div rect='20px, -81px, 248px, 71px' border='2px' bcolor='#888888' depth='-1' padding='10px,10px'>" +
                "<button value='1'>[泡红茶]</button><br><button value='99'>[关闭]</button><br></div>",
            true,
        )
        sink.noteInputWait(
            com.eratw.port.core.host.InputRequest(
                type = com.eratw.port.core.host.InputRequest.InputType.INT,
                form = com.eratw.port.core.host.InputRequest.RequestForm.VALUE,
                mouseInput = true,
                hasDef = true,
                defInt = -1L,
            ),
        )
        val screen = ConsoleScreen(clientWidth, 100, heightLines)
        screen.submitSnapshot(ConsoleAdapter.adapt(sink.snapshot()))
        val composer = ScreenComposer(
            clientWidth,
            heightLines,
            metrics,
            canvasHeightPx = heightLines * metrics.lineHeight,
        )
        ConsoleFrame(composer).draw(screen, screen.list.dirtyStrips())

        val divs = screen.divTargets()
        assertTrue(divs.isNotEmpty(), "弹窗子行按钮要进命中表，否则本用例是空转")
        assertTrue(
            divs.all { it.depth < 0 },
            "本帧 depth 键表全负（语料 popup 只给 depth='-1'），实际=" + divs.map { it.depth },
        )

        val body = screen.buttons.all().first { it.value == 301L }
        val rows = screen.visibleRows()
        val xPx = metrics.cellsToPixels(body.colStart) + 2
        val yPx = composer.rowTopPx(body.row, rows.size) + metrics.lineHeight / 2
        assertNull(
            screen.divHitAt(xPx, yPx),
            "B9 定案：负 depth 弹窗在场也要让回正文行像素（官方在此整趟跳过正文扫描 ⇒ 点不动）",
        )
        assertEquals("301", screen.click(body.row, body.colStart), "让位后正文行按钮照旧提交")

        // 对照：弹窗自己的子行按钮仍可点（负键先扫，官方同判），证明上面的 null 不是命中表没喂
        val tea = divs.first { it.value == 1L }
        val teaHit = screen.divHitAt((tea.xPx + tea.widthPx / 2).toInt(), (tea.yPx + tea.heightPx / 2).toInt())
        assertNotNull(teaHit, "弹窗 [泡红茶] 中心像素必须命中 div")
        assertEquals(1L, teaHit.value)
    }

    /** 没画过帧（命中表未喂）时一律不命中 —— 证明命中表确实来自 `ConsoleFrame` 的每帧喂参。 */
    @Test
    fun `未喂帧时无 div 命中`() {
        val (screen, composer) = buildScreen()
        assertNull(screen.divHitAt(4, 4), "没有 frame.draw 就没有命中表")
        // 画一帧后同一个像素就有命中 —— 差异只可能来自接线。
        ConsoleFrame(composer).draw(screen, screen.list.dirtyStrips())
        assertNotNull(screen.divHitAt(6, composer.rowTopPx(0, 1) + 4 + metrics.lineHeight / 2))
    }
}
