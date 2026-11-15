package com.eratw.port.render.adapter

import com.eratw.port.core.host.BusyIndicator
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.view.ConsoleScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ConsoleFeed.busyIndicator] 与「指示器在动 ⇒ hasUnpublishedContent 为真」的钉子（PRAY-BUSY）。
 *
 * 形态约束：指示器是**纯显示态**，不允许因为它在动就多交一帧内容快照。所以这里同时钉两件事：
 * - 重绘循环看 `hasUnpublishedContent()`（活动期为真 ⇒ 每帧 invalidate，呼吸才动得起来）；
 * - `pump()` 在只有指示器变化时仍交空表，且屏上行集不因指示器增减。
 */
class ConsoleFeedBusyIndicatorTest {

    private fun feed(): Pair<LineBufferConsoleSink, ConsoleFeed> {
        val sink = LineBufferConsoleSink(clientWidth = 20)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 100, heightLines = 8)
        return sink to ConsoleFeed(sink, screen)
    }

    @Test
    fun `busyIndicator 直读 sink 且泵不因此多交内容帧`() {
        val (sink, feed) = feed()
        sink.printLine("甲")
        feed.pump()
        assertNull(feed.busyIndicator(), "没有长页时读到 null")
        assertFalse(feed.hasUnpublishedContent(), "稳态帧不该持续 invalidate")
        val rows = feed.screen.list.rowCount

        sink.setBusyIndicator(BusyIndicator(1_200L))
        assertEquals(BusyIndicator(1_200L), feed.busyIndicator(), "必须直读 sink 的活值")
        assertTrue(feed.hasUnpublishedContent(), "指示器在动 ⇒ 重绘循环必须 invalidate（呼吸）")

        val rects = feed.pump()
        assertEquals(emptyList(), rects, "只有指示器变化时 pump 不得交内容帧（纯显示态）")
        assertEquals(rows, feed.screen.list.rowCount, "行集不得因为指示器而变")
        assertTrue(feed.hasUnpublishedContent(), "指示器仍在 ⇒ 下一帧继续 invalidate")

        sink.setBusyIndicator(null)
        assertNull(feed.busyIndicator())
        assertFalse(feed.hasUnpublishedContent(), "清掉后钢稳态回假")
        assertEquals(rows, feed.screen.list.rowCount, "清除同样不动行集")
    }

    @Test
    fun `内容 revision 前进仍照常交帧 指示器不参与`() {
        val (sink, feed) = feed()
        sink.setBusyIndicator(BusyIndicator(2_000L))
        sink.printLine("甲")
        val rects = feed.pump()
        assertTrue(rects.isNotEmpty(), "有真实新行时仍要交内容帧")
        assertEquals(1, feed.screen.list.rowCount)
        assertEquals(BusyIndicator(2_000L), feed.busyIndicator(), "交帧不影响指示器活值")
    }
}
