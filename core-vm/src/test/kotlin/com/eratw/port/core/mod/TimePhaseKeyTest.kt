package com.eratw.port.core.mod

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * TIME→自定义相位键单一换算口（ModDX-4 M5P 判据 1/4）映射闸。
 *
 * 钉住：键 = `"time." + <TIME 原值>` 十进制原样（0 与 Long.MAX_VALUE 边界、负值不解释区间）、
 * null（读数不可用）→ 空串 = 无相位（fail-open，与未接线前同形）。引擎不引入时段语义的
 * 裁定见 [TimePhaseKey] 类头。
 */
class TimePhaseKeyTest {

    @Test
    fun `TIME为零给time_0`() {
        assertEquals("time.0", TimePhaseKey.of(0L))
    }

    @Test
    fun `TIME为Long最大值给十进制原样键`() {
        assertEquals("time.9223372036854775807", TimePhaseKey.of(Long.MAX_VALUE))
    }

    @Test
    fun `TIME为负值原样拼缀不解释`() {
        assertEquals("time.-1", TimePhaseKey.of(-1L))
    }

    @Test
    fun `读数不可用给空串`() {
        assertEquals("", TimePhaseKey.of(null))
    }
}
