package com.eratw.port.modtesting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TestGameState] 的存在性探测（DX-C）与哨兵语义自测：台架镜像面须与生产 `GameStateApi`
 * 契约逐值同形——预置键探测 true / 未预置 false / 缺变量读数仍哨兵（K1 冻结契约零变化）。
 */
class TestGameStateTest {

    @Test
    fun `预置变量探测答真`() {
        val gs = TestGameState()
        gs.presetIntVar("DAY", 5L)
        gs.presetStrVar("PLACE", "神社")
        assertTrue(gs.hasIntVar("DAY"))
        assertTrue(gs.hasStrVar("PLACE"))
    }

    @Test
    fun `未预置变量探测答假`() {
        val gs = TestGameState()
        assertFalse(gs.hasIntVar("NOPE"))
        assertFalse(gs.hasStrVar("NOPE"))
    }

    @Test
    fun `探测归一口径与读侧同 按大写归一`() {
        val gs = TestGameState()
        gs.presetIntVar("DAY", 5L)
        gs.presetStrVar("PLACE", "神社")
        assertTrue(gs.hasIntVar("day"))
        assertTrue(gs.hasStrVar("place"))
    }

    @Test
    fun `探测假不改变哨兵语义 缺变量读数仍 0 与空串`() {
        val gs = TestGameState()
        assertFalse(gs.hasIntVar("NOPE"))
        assertFalse(gs.hasStrVar("NOPE"))
        // K1 冻结契约：缺变量 getIntVar 仍 0、getStrVar 仍空串，本波零变化。
        assertEquals(0L, gs.getIntVar("NOPE"))
        assertEquals("", gs.getStrVar("NOPE"))
    }
}
