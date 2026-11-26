package com.eratw.port.core.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 执行期入力滞留队列（[LateInputQueue]）的口径钉子（PLAY-INPUT-LATE · 2026-11-23）。
 *
 * 语义锚 = 原版脚本与消息泵同线程：脚本执行期间的点击躺在 Windows 消息队列里，
 * 到 `EmueraConsole.WaitInput` 的 `DoEvents`（`EmueraConsole.cs:1040-1082`）才被取出，
 * 并且按**取出那一刻**的版面命中（`MainWindow.cs:824-856`）。本队列就是那段队列的等价物，
 * 因此钉的是四件事：顺序、有界、限时、按当刻版面裁决。
 */
class LateInputQueueTest {

    private fun offer(
        q: LateInputQueue,
        at: Long,
        text: String,
        kind: LateInputQueue.Kind = LateInputQueue.Kind.BUTTON,
        mouse: Int? = 1,
    ) = q.offer(at, kind, text, mesSkip = false, mouseButton = mouse)

    /** 顺序：同一窗口里的两笔按提出先后回放，且各只回放一次（drain 即消费）。 */
    @Test
    fun `按提出顺序回放且只回放一次`() {
        val q = LateInputQueue(ttlMs = 250L)
        assertTrue(offer(q, 1000L, "500"))
        assertTrue(offer(q, 1001L, "400"))
        val first = q.drain(1100L) { true }
        assertEquals(listOf("500", "400"), first.map { it.text })
        assertEquals(2L, q.replayed)
        assertTrue(q.drain(1100L) { true }.isEmpty(), "drain 即消费，第二拍不许再放一次")
        assertEquals(2L, q.replayed)
    }

    /** 限时：超过 TTL 的笔在取出时丢弃并计数，不许在换页之后兑现。 */
    @Test
    fun `超过 TTL 的入力丢弃并计入 expired`() {
        val q = LateInputQueue(ttlMs = 250L)
        assertTrue(offer(q, 1000L, "500"))
        assertEquals(
            listOf("500"),
            q.drain(1200L) { true }.map { it.text },
            "年龄 200ms < 250ms ⇒ 照原版在下一个 DoEvents 兑现",
        )
        assertEquals(1L, q.replayed)

        assertTrue(offer(q, 2000L, "600"))
        assertTrue(q.drain(2400L) { true }.isEmpty(), "年龄 400ms > 250ms ⇒ 丢弃")
        assertEquals(1L, q.expired)
        assertEquals(0L, q.layoutMismatch, "超时不是版面不匹配，两个桶不许混")
    }

    /** 有界：同类第 3 笔挤掉最旧的一笔（只留最近两笔），并计入 overflow。 */
    @Test
    fun `同类超额丢最旧并计入 overflow`() {
        val q = LateInputQueue(ttlMs = 250L, capacityPerKind = 2)
        assertTrue(offer(q, 1000L, "a"))
        assertTrue(offer(q, 1001L, "b"))
        // 返回值只回答「挤掉了没有」，新的一笔恒被留下（不许被宿主当「这笔丢了」记）。
        assertFalse(offer(q, 1002L, "c"), "同类第三笔应报告「挤掉了最旧的一笔」")
        assertEquals(1L, q.overflow)
        assertEquals(2, q.size, "新的一笔被留下 ⇒ 队列仍是 2 笔")
        assertEquals(listOf("b", "c"), q.drain(1100L) { true }.map { it.text })
        assertEquals(0, q.size, "两笔已取出，队列清空")
    }

    /** 两类互不挤占：按钮与文本各自有界（原版也是两套消息）。 */
    @Test
    fun `文本与按钮各占各自的界`() {
        val q = LateInputQueue(ttlMs = 250L, capacityPerKind = 2)
        assertTrue(offer(q, 1000L, "b1"))
        assertTrue(offer(q, 1001L, "b2"))
        assertTrue(offer(q, 1002L, "t1", kind = LateInputQueue.Kind.TEXT, mouse = null))
        assertTrue(offer(q, 1003L, "t2", kind = LateInputQueue.Kind.TEXT, mouse = null))
        assertEquals(0L, q.overflow, "两类各自两笔，未超额")
        assertEquals(4, q.size)
    }

    /** 版面已变：当刻命中不到的值丢弃并计入 layoutMismatch，不许猜着交出去。 */
    @Test
    fun `当刻版面命中不到的值丢弃并计入 layoutMismatch`() {
        val q = LateInputQueue(ttlMs = 250L)
        assertTrue(offer(q, 1000L, "500"))
        assertTrue(offer(q, 1001L, "401"))
        val kept = q.drain(1100L) { it.text == "401" }
        assertEquals(listOf("401"), kept.map { it.text })
        assertEquals(1L, q.layoutMismatch)
        assertEquals(1L, q.replayed)
    }

    /** 收口清空：会话关闭 / 回标题时滞留笔数归零。 */
    @Test
    fun `clear 清空且不动计数`() {
        val q = LateInputQueue(ttlMs = 250L)
        assertTrue(offer(q, 1000L, "500"))
        q.clear()
        assertEquals(0, q.size)
        assertTrue(q.drain(1050L) { true }.isEmpty())
        assertEquals(0L, q.replayed)
    }

    /**
     * 同值双触发闸（按值命中的安全侧）：一屏里有两个**同值**按钮（窄屏下 `[<]`/`[>]` 这类重复值
     * 就是常态）时，一笔点击回放**只提交一次**，并把「这一拍版面有歧义」记进 [LateInputQueue.ambiguousHits]。
     *
     * 判据不是「猜是哪一个」，而是「一笔点击只兑现一次 + 歧义可判定」：
     * 值命中命中到几处由 `ReplayPlan.ambiguous` 报出来，丢的那一路（命中不到）另走 `layoutMismatch`。
     */
    @Test
    fun `同屏两个同值按钮时一笔点击只回放一次且歧义可判定`() {
        val q = LateInputQueue(ttlMs = 250L)
        assertTrue(offer(q, 1000L, "500"))
        // 当刻版面：两个同值按钮 + 一个别的值。
        val plan = q.drainForLayout(1100L, listOf("500", "500", "401"))
        assertEquals(1, plan.entries.size, "一笔点击只许回放一笔（不许同值双触发）")
        assertEquals("500", plan.entries[0].text)
        assertEquals(1, plan.ambiguous, "同值两处 ⇒ 歧义必须可判定并被记下")
        assertEquals(1L, q.ambiguousHits)
        assertEquals(1L, q.replayed, "只算一笔")
        assertEquals(0L, q.layoutMismatch, "命中到了就不算版面不匹配")

        // 版面里没有这个值 ⇒ 丢弃并计 layoutMismatch（安全侧，不误点）。
        assertTrue(offer(q, 2000L, "700"))
        val dropped = q.drainForLayout(2100L, listOf("500", "401"))
        assertTrue(dropped.entries.isEmpty())
        assertEquals(1L, q.layoutMismatch)
    }

    /** 文本提交与选项集无关：纯文本等待（选项集为空）里滞留的文本照样回放。 */
    @Test
    fun `文本提交不受选项集影响`() {
        val q = LateInputQueue(ttlMs = 250L)
        assertTrue(offer(q, 1000L, "こんにちは", kind = LateInputQueue.Kind.TEXT, mouse = null))
        val plan = q.drainForLayout(1100L, emptyList())
        assertEquals(1, plan.entries.size)
        assertEquals(0, plan.ambiguous)
        assertEquals(0L, q.layoutMismatch)
    }
}
