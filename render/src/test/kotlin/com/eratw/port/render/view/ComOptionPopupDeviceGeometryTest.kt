package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.HitTarget
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.DivOverlayRow
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 泡茶弹窗点击的**真机几何复刻**（玩家 0.2.44 仍复现「点红茶没反应」案的 View 路半段）。
 *
 * `ComOptionPopupHitTest` 走的是 headless `ConsoleFrame`/`ScreenComposer` 路；
 * 真机画布（`CanvasConsoleView.drawScreen`）的 div 喂参是**另一份实现**：
 * 行顶来自 `RowLayout.plan`（底对齐 + 纯图行压缩），锚行顶按
 * `tops[clamped] + (idx - clamped) * lineHeightPx - paddingTop` 折算（窗口外 clamp 外推），
 * 命中吃 `clientX/clientY`（减 padding 的内容区坐标）。
 * 本测试在纯 JVM 上逐行复刻 `drawScreen:2947-2956` 与 `divOverlayFrame:3178-3201`
 * 的算式（View 壳不可实例化，几何全在 `:render` 模块），验证：
 * 1. 真机几何下弹窗三枚按钮进命中表；
 * 2. 触点（弹窗按钮中心）按真机换算命中 value=1；
 * 3. **页面底部对齐裁顶**（历史行被裁掉、锚行贴底）时弹窗（锚行上方 -81px）仍在命中表；
 * 4. 压缩行场景（锚行上方有纯图行）锚行顶折算正确、命中不漂。
 */
class ComOptionPopupDeviceGeometryTest {

    private val metrics = CellMetrics()
    private val clientWidth = 60
    private val lineHeightPx = metrics.lineHeight.toFloat()

    private val popupHtml =
        "<div rect='20px, -81px, 248px, 71px' border='2px' bcolor='#888888' depth='-1' padding='10px,10px'>" +
            "<button value='0'>[泡茶]</button><br>" +
            "<button value='1'>[泡红茶]</button><br>" +
            "<button value='99'>[关闭]</button><br></div>"

    private fun popupInputRequest() = com.eratw.port.core.host.InputRequest(
        type = com.eratw.port.core.host.InputRequest.InputType.INT,
        form = com.eratw.port.core.host.InputRequest.RequestForm.VALUE,
        mouseInput = true,
        hasDef = true,
        defInt = -1L,
    )

    /** 真机 drawScreen 的复刻：RowLayout 行顶 → divOverlayFrame 折算 → updateDivOverlay。 */
    private fun buildDeviceLikeScreen(
        totalLogRows: Int,
        paddingTopPx: Int = 24,
        paddingBottomPx: Int = 24,
        canvasHeightPx: Int = 800,
        artRowsBeforeAnchor: Int = 0,
    ): ConsoleScreen {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        // 历史行（语料里是立绘/状态栏；这里普通文本行，artRowsBeforeAnchor 行用图行模拟压缩场景）
        repeat(artRowsBeforeAnchor) { sink.printHtml("<img src='x' width='100px' height='10px'>", false) }
        repeat(totalLogRows - artRowsBeforeAnchor - 1) { sink.printLine("历史行") }
        // 弹窗打印在最后一条显示行（锚行），随后 INPUT -1,1 等待入口 flush + 冻结
        sink.printHtml(popupHtml, true)
        sink.noteInputWait(popupInputRequest())

        val screen = ConsoleScreen(clientWidth, 100, 20)
        screen.submitSnapshot(ConsoleAdapter.adapt(sink.snapshot()))

        // ---- 真机 drawScreen:2944-2956 的复刻 ----
        val frameCmds = ArrayList<com.eratw.port.render.displaylist.DrawCmd>()
        consoleListOf(screen).renderInto(frameCmds)
        val windowRows = consoleListOf(screen).rowCount
        val contentTop = paddingTopPx.toFloat()
        val contentBottom = (canvasHeightPx - paddingBottomPx).toFloat()
        val layout = RowLayout.plan(
            cmds = frameCmds,
            windowRows = windowRows,
            contentTop = contentTop,
            contentBottom = contentBottom,
            lineHeightPx = lineHeightPx,
            minArtRowHeightPx = 6f,
        )
        // 真机 divOverlayFrame:3178-3201
        val clientHeightPx = (canvasHeightPx - paddingTopPx - paddingBottomPx)
        val scan = screen.divOverlayScan((clientHeightPx / lineHeightPx).toInt() + 1)
        if (scan.isNotEmpty()) {
            val base = screen.visibleRowBase
            val firstIndex = screen.log.firstIndex
            val rowCount = layout.tops.size
            val rows = scan.map { r ->
                val idx = r.retainedIndex - base
                val clamped = idx.coerceIn(0, rowCount - 1)
                val topContent = (layout.tops[clamped] + (idx - clamped) * lineHeightPx - paddingTopPx).toInt()
                DivOverlayRow(r.blocks, topContent, r.lineNo)
            }
            val overlay = com.eratw.port.render.adapter.DivOverlayPlanner(
                metrics, CellMetrics.DEFAULT_FONT_PIXEL_SIZE,
                com.eratw.port.render.adapter.HtmlRunFlatten(metrics),
            ).planFrame(
                rows = rows,
                canvasHeightPx = clientHeightPx,
                visibleTopLine = firstIndex + base,
                visibleBottomLine = firstIndex + base + screen.visibleRows().size - 1,
            )
            screen.updateDivOverlay(overlay, metrics, clientHeightPx)
        } else {
            screen.updateDivOverlay(DivOverlayFrame.EMPTY, metrics, clientHeightPx)
        }
        return screen
    }

    /** ConsoleScreen 的 DisplayList 访问（screen.list 是 public）。 */
    private fun consoleListOf(screen: ConsoleScreen) = screen.list

    /** 真机命中换算：view 坐标 → 内容区坐标（clientX/clientY = 减 padding）。 */
    private fun hitAtViewCoords(
        screen: ConsoleScreen,
        viewX: Float,
        viewY: Float,
        paddingTopPx: Int,
    ): ButtonLayer.DivHit? {
        // 真机 contentLeftPx = paddingLeft + inset；本测试布局无居中偏移，取 0。
        val clientX = (viewX - 0f).toInt()
        val clientY = (viewY - paddingTopPx).toInt()
        return screen.divHitAt(clientX, clientY)
    }

    /** ① 真机几何下三枚弹窗按钮进命中表。 */
    @Test
    fun `真机几何复刻 弹窗三按钮进命中表`() {
        val screen = buildDeviceLikeScreen(totalLogRows = 12)
        val targets = screen.divTargets()
        assertEquals(listOf(0L, 1L, 99L), targets.map { it.value as Long }.sorted(), "三枚弹窗按钮都要进命中表")
    }

    /** ② 触点（弹窗按钮中心，view 坐标）按真机换算命中 [泡红茶]。 */
    @Test
    fun `真机几何复刻 触点命中泡红茶`() {
        val paddingTop = 24
        val screen = buildDeviceLikeScreen(totalLogRows = 12, paddingTopPx = paddingTop)
        val redTea = screen.divTargets().first { it.value == 1L }
        // divHit 的 xPx/yPx 已是内容区坐标 → view 坐标 = 内容区 + paddingTop
        val viewX = redTea.xPx + redTea.widthPx / 2
        val viewY = redTea.yPx + redTea.heightPx / 2 + paddingTop
        val hit = hitAtViewCoords(screen, viewX, viewY, paddingTop)
        assertNotNull(hit, "触点 (${viewX},${viewY}) 必须命中 [泡红茶]")
        assertEquals(1L, hit.value)
        // 提交口径
        val submitted = screen.buttons.selectedString(
            HitTarget(-1, hit.colStart, hit.colEnd, hit.value, hit.label),
            ButtonAcceptance.INT,
        )
        assertEquals("1", submitted)
    }

    /** ③ 底对齐裁顶（历史长页）时弹窗仍在命中表且位置正确。 */
    @Test
    fun `真机几何复刻 底对齐裁顶时弹窗仍可点`() {
        val paddingTop = 24
        val screen = buildDeviceLikeScreen(totalLogRows = 60, paddingTopPx = paddingTop)
        val redTea = screen.divTargets().firstOrNull { it.value == 1L }
        assertNotNull(redTea, "60 行历史 + 底对齐裁顶：弹窗按钮必须仍在命中表")
        val viewX = redTea.xPx + redTea.widthPx / 2
        val viewY = redTea.yPx + redTea.heightPx / 2 + paddingTop
        val hit = hitAtViewCoords(screen, viewX, viewY, paddingTop)
        assertEquals(1L, hit?.value)
    }

    /** ④ 压缩行场景（锚行上方有纯图行）锚行顶折算正确、命中不漂移。 */
    @Test
    fun `真机几何复刻 压缩行下命中不漂移`() {
        val paddingTop = 24
        val screen = buildDeviceLikeScreen(totalLogRows = 12, paddingTopPx = paddingTop, artRowsBeforeAnchor = 3)
        val targets = screen.divTargets()
        assertEquals(listOf(0L, 1L, 99L), targets.map { it.value as Long }.sorted(), "压缩行场景三按钮仍要进命中表")
        val redTea = targets.first { it.value == 1L }
        val viewX = redTea.xPx + redTea.widthPx / 2
        val viewY = redTea.yPx + redTea.heightPx / 2 + paddingTop
        assertEquals(1L, hitAtViewCoords(screen, viewX, viewY, paddingTop)?.value)
    }
}
