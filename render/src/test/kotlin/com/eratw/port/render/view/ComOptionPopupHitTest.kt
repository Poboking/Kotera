package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.HitTarget
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 泡茶子选单弹窗的渲染级端到端（玩家「点红茶没反应」案的命中半段回归锁）。
 *
 * 语料 `ERB/魔改内容/qol/QOL_USERCOM.ERB @COM_OPTION`（实跑输出）：
 * `<div rect='20px, -81px, 248px, 71px' border='2px' bcolor='#888888' depth='-1'
 *   padding='10px,10px'><button value='0'>[泡茶]</button>…<br>…<button value='99'>[关闭]</button><br></div>`
 * —— ypos=-81px 是**负 y**：块从锚行行条顶向上伸（原版 `Y_POS = MOUSEY() - 10 - DIV_HEIGHT`，
 * 坐标系 0 是底边），属逃逸块（Top<0），depth=-1 画在正文之后（front 层）。
 *
 * 三个断言对应玩家操作的三步：
 * 1. 弹窗按钮进引擎选项快照（右栏能点 → core 半段已由 replay probe 钉死）；
 * 2. 屏上点 [泡红茶]（value=1）的像素能命中 div 子行按钮（命中半段）；
 * 3. 命中的提交文本 = "1"（`selectedString` 口径）。
 */
class ComOptionPopupHitTest {

    private val metrics = CellMetrics()
    private val clientWidth = 60
    private val heightLines = 20

    /** vmdbg 实跑 `COM_OPTION(301)` 的控制台尾行原文（去掉 shape 对齐件，保留按钮与 br）。 */
    private val popupHtml =
        "<div rect='20px, -81px, 248px, 71px' border='2px' bcolor='#888888' depth='-1' padding='10px,10px'>" +
            "<button value='0'>[泡茶]</button><br>" +
            "<button value='1'>[泡红茶]</button><br>" +
            "<button value='99'>[关闭]</button><br></div>"

    /** 语料 `INPUT -1, 1` 的等待请求（INT 取值 + 默认值 -1 + 带鼠标）。 */
    private fun popupInputRequest() = com.eratw.port.core.host.InputRequest(
        type = com.eratw.port.core.host.InputRequest.InputType.INT,
        form = com.eratw.port.core.host.InputRequest.RequestForm.VALUE,
        mouseInput = true,
        hasDef = true,
        defInt = -1L,
    )

    private fun buildScreen(): Pair<ConsoleScreen, ScreenComposer> {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        // 语料里 HTML_PRINT …, 1 走打印缓冲；INPUT -1, 1 的等待入口 noteInputWait
        // 先 flushPending 再冻结可点集合（LineBufferConsoleSink.noteInputWait:906-926）。
        sink.printHtml(popupHtml, true)
        sink.noteInputWait(popupInputRequest())
        val screen = ConsoleScreen(clientWidth, 100, heightLines)
        screen.submitSnapshot(ConsoleAdapter.adapt(sink.snapshot()))
        val composer = ScreenComposer(
            clientWidth,
            heightLines,
            metrics,
            canvasHeightPx = heightLines * metrics.lineHeight,
        )
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.list.dirtyStrips())
        return screen to composer
    }

    /** ① 引擎选项快照里有三枚弹窗按钮（含 value=0 / 1 / 99，Long 型）。 */
    @Test
    fun `弹窗按钮进引擎选项快照`() {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        sink.printHtml(popupHtml, true)
        sink.noteInputWait(popupInputRequest())
        val options = sink.currentOptions()
        val values = options.map { it.value }.filterIsInstance<Long>().sorted()
        assertEquals(listOf(0L, 1L, 99L), values, "弹窗三枚按钮都要进可点集合，实际=$options")
        assertEquals("1", options.first { it.value == 1L }.submitValue, "点红茶提交的是字符串 \"1\"")
    }

    /** ② 屏上 [泡红茶] 按钮的像素命中 div 子行按钮，值 = 1。 */
    @Test
    fun `点泡红茶的像素命中弹窗按钮`() {
        val (screen, composer) = buildScreen()
        val targets = screen.divTargets()
        assertEquals(3, targets.size, "三枚弹窗按钮都要进命中表，实际=${targets.map { it.value }}")
        val redTea = targets.first { it.value == 1L }
        // 命中矩形中心：divHitAt 吃画布像素（半开区间 [xPx, xPx+width)）。
        val xPx = (redTea.xPx + redTea.widthPx / 2).toInt()
        val yPx = (redTea.yPx + redTea.heightPx / 2).toInt()
        val hit = screen.divHitAt(xPx, yPx)
        assertNotNull(hit, "泡红茶按钮中心 (${xPx},${yPx}) 必须命中")
        assertEquals(1L, hit.value)
    }

    /** ③ 命中回环的提交文本与画面按钮一致（selectedString 口径）。 */
    @Test
    fun `命中回环提交文本为按钮值`() {
        val (screen, _) = buildScreen()
        val redTea = screen.divTargets().first { it.value == 1L }
        val xPx = (redTea.xPx + redTea.widthPx / 2).toInt()
        val yPx = (redTea.yPx + redTea.heightPx / 2).toInt()
        val hit = screen.divHitAt(xPx, yPx) ?: error("② 已断言命中，此处不可达")
        val submitted = screen.buttons.selectedString(
            HitTarget(-1, hit.colStart, hit.colEnd, hit.value, hit.label),
            ButtonAcceptance.INT,
        )
        assertEquals("1", submitted, "INT 等待下点 [泡红茶] 要提交 \"1\"")
    }

    /**
     * ④ 真机时序复刻（世代闸）：玩家点 + 后引擎的真实序列 =
     * 命令面板打印（CLEARLINE 1 删 + 行）→ 弹窗 HTML_PRINT（缓冲）→ INPUT -1,1 挂起。
     * `isCurrentGeneration("1")` 查的 `publishedOptions()` 必须含弹窗三按钮 ——
     * 缺了它真机点击在 `handleConsoleTap:2686` 被世代闸静默吞掉（「点了没反应」形态）。
     */
    @Test
    fun `真机时序 世代闸快照含弹窗按钮`() {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        // T0：命令面板行（PRINTBUTTONC 形态的 + 按钮与命令按钮同行）
        sink.printButtonC("[+]", -301L, false)
        sink.printButtonC("泡茶", 301L, true)
        sink.printLine("")
        // T1：CLEARLINE 1（USERCOM.ERB:387，删掉 + 按钮所在行）
        sink.deleteLine(1)
        // T2：弹窗 HTML_PRINT …, 1（缓冲路）
        sink.printHtml(popupHtml, true)
        // T3：INPUT -1, 1 挂起
        sink.noteInputWait(popupInputRequest())
        // T4：世代闸读数（GameHost.isCurrentGeneration → publishedOptions）
        val published = sink.publishedOptions().map { it.submitValue }
        assertTrue(
            "0" in published && "1" in published && "99" in published,
            "世代闸快照必须含弹窗三按钮，实际=$published",
        )
    }
}
