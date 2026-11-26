package com.eratw.port.core.mod

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 回合边界相位时钟（P0-WIRE）映射闸。
 *
 * 钉住：只从 `DAY` 推导（不读 TIME）、首边界/回退给日始、跨日给日终 + 日始、同日给动作点、
 * 同一输入可重放（确定性）、reset 后重新从首边界开始。
 */
class TurnPhaseClockTest {

    @Test
    fun `首边界给日始`() {
        assertEquals(listOf("DAY_START"), TurnPhaseClock().plan(5L))
    }

    @Test
    fun `跨日给日终加日始`() {
        val clock = TurnPhaseClock()
        clock.plan(5L)
        assertEquals(listOf("DAY_END", "DAY_START"), clock.plan(6L))
    }

    @Test
    fun `同一游戏日重复边界给动作点`() {
        val clock = TurnPhaseClock()
        clock.plan(5L)
        assertEquals(listOf("ACTION"), clock.plan(5L))
        assertEquals(listOf("ACTION"), clock.plan(5L), "同日可重复出动作点，不推进日终")
    }

    @Test
    fun `日回退只开日始不补旧日终`() {
        val clock = TurnPhaseClock()
        clock.plan(7L)
        assertEquals(listOf("DAY_START"), clock.plan(2L))
        assertEquals(listOf("DAY_END", "DAY_START"), clock.plan(3L), "回退后水位跟到 2，再前进按正常跨日")
    }

    @Test
    fun `reset 后重新按首边界给日始`() {
        val clock = TurnPhaseClock()
        clock.plan(5L)
        clock.plan(5L)
        clock.reset()
        assertEquals(listOf("DAY_START"), clock.plan(5L))
    }
}
