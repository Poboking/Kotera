package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 长页忙碌指示器的 core 面钉子（PRAY-BUSY）。
 *
 * 三条硬约束里这里钉两条：
 * 1. **零行缓冲影响**：置/清指示器时 `revision`、`frameSnapshot()`、`publishedOptions()`
 *    与活表行逐值不变 —— 金标哈希链（E2EGolden / L3HashChain）与语料自己的 `CLEARLINE`
 *    都看不到它；
 * 2. **桥接必须转发**：[ConsoleSink.setBusyIndicator] 的接口缺省是 no-op，[ConsoleBridge]
 *    漏转发时 `ctx.console` 那条路永远写不到真 sink。
 */
class BusyIndicatorHostTest {

    private fun frozenSink(): LineBufferConsoleSink {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.holdFrame()
        s.printLine("甲")
        s.printLine("乙")
        s.publishFrame()
        return s
    }

    @Test
    fun `置清指示器不改变 revision 帧快照与选项快照`() {
        val sink = frozenSink()
        val revision0 = sink.revision
        val frame0 = sink.frameSnapshot()
        val options0 = sink.publishedOptions()
        val lines0 = sink.lines()
        val lineCount0 = sink.lineCount
        assertTrue(frame0.isNotEmpty(), "夹具必须先有一帧可见内容")

        sink.setBusyIndicator(BusyIndicator(1_500L))
        assertNotNull(sink.busyIndicator(), "写入侧必须可读回")
        assertEquals(revision0, sink.revision, "置指示器不得推进 revision")
        assertEquals(frame0, sink.frameSnapshot(), "置指示器不得换帧快照")
        assertEquals(options0, sink.publishedOptions(), "置指示器不得重算选项快照")
        assertEquals(lines0, sink.lines(), "置指示器不得改活表")
        assertEquals(lineCount0, sink.lineCount, "置指示器不得改逻辑行计数")

        sink.setBusyIndicator(BusyIndicator(9_999L))
        assertEquals(revision0, sink.revision, "改读数同样不得推进 revision")
        assertEquals(9_999L, sink.busyIndicator()?.elapsedMs)

        sink.setBusyIndicator(null)
        assertNull(sink.busyIndicator(), "清除后读出 null")
        assertEquals(revision0, sink.revision, "清指示器不得推进 revision")
        assertEquals(frame0, sink.frameSnapshot(), "清指示器不得换帧快照")
        assertEquals(options0, sink.publishedOptions(), "清指示器不得重算选项快照")
        assertEquals(lines0, sink.lines(), "清指示器不得改活表")
        assertEquals(lineCount0, sink.lineCount, "清指示器不得改逻辑行计数")
    }

    @Test
    fun `桥接器把指示器转发到真 sink`() {
        val sink = LineBufferConsoleSink(clientWidth = 40)
        val bridge: ConsoleSink = ConsoleBridge(sink, null, StringBuilder(), Long.MAX_VALUE)

        bridge.setBusyIndicator(BusyIndicator(2_000L))
        assertEquals(BusyIndicator(2_000L), sink.busyIndicator(), "ConsoleBridge 漏转发时这里读到 null")

        bridge.setBusyIndicator(null)
        assertNull(sink.busyIndicator(), "清除同样必须转发")
    }

    @Test
    fun `delegate 缺席与 Noop 宿主走缺省 no-op 不抛`() {
        val bridge: ConsoleSink = ConsoleBridge(null, null, StringBuilder(), Long.MAX_VALUE)
        bridge.setBusyIndicator(BusyIndicator(1_000L))
        bridge.setBusyIndicator(null)

        NoopConsoleSink.setBusyIndicator(BusyIndicator(1_000L))
        NoopConsoleSink.setBusyIndicator(null)
        assertEquals(0L, NoopConsoleSink.lineCount, "缺省宿主仍不记账")
    }
}
