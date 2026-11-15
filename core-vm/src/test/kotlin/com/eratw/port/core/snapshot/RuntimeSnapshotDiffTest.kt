package com.eratw.port.core.snapshot

import com.eratw.port.core.snapshot.RuntimeSnapshot.Header
import com.eratw.port.core.snapshot.RuntimeSnapshot.Section
import com.eratw.port.core.snapshot.RuntimeSnapshot.Snapshot
import com.eratw.port.core.snapshot.RuntimeSnapshot.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 首差定位的定向用例（契约 §2 / 反向证明 B 的钉子）。
 *
 * 用手工构造的分节精确控制分歧位置，不依赖会话采集；断言的是「首个分歧」的
 * section 与字段路径，以及「相同 ⇒ 无差」。顺序固定这一条由
 * [firstDivergenceIsFirstNotLastWhenMultipleDiffs] 单独钉住。
 */
class RuntimeSnapshotDiffTest {

    @Test
    fun identicalSnapshotsReportNoDiff() {
        val snap = snap(
            section(RuntimeSnapshotSections.VARS, "bytes" to Value.Blob(byteArrayOf(1, 2, 3))),
            section(RuntimeSnapshotSections.DISPLAY, "lineCount" to Value.Int64(0)),
        )
        val result = RuntimeSnapshotDiff.diff(snap, snap)
        assertTrue(result.same)
        assertTrue(result.divergences.isEmpty())
        assertNull(result.first)
    }

    @Test
    fun oneVariableDiffReportsFirstField() {
        val a = snap(section(RuntimeSnapshotSections.VARS, "savedUserVars" to Value.Int64(5)))
        val b = snap(section(RuntimeSnapshotSections.VARS, "savedUserVars" to Value.Int64(6)))
        val result = RuntimeSnapshotDiff.diff(a, b)
        assertFalse(result.same)
        assertEquals(1, result.divergences.size)
        assertEquals(RuntimeSnapshotSections.VARS, result.first!!.section)
        assertEquals("savedUserVars", result.first!!.path)
    }

    @Test
    fun oneRngDrawDiffReportsFirstStateSlot() {
        val stateA = List(625) { it.toLong() }
        val stateB = stateA.toMutableList().also { it[17] = 999L }
        val a = snap(section(RuntimeSnapshotSections.RNG, "state" to Value.IntList(stateA)))
        val b = snap(section(RuntimeSnapshotSections.RNG, "state" to Value.IntList(stateB)))
        val result = RuntimeSnapshotDiff.diff(a, b)
        assertEquals(RuntimeSnapshotSections.RNG, result.first!!.section)
        assertEquals("state[17]", result.first!!.path)
    }

    @Test
    fun firstDivergenceIsFirstNotLastWhenMultipleDiffs() {
        val a = snap(
            section(
                RuntimeSnapshotSections.VARS,
                "savedUserVars" to Value.Int64(1),
                "encoding" to Value.Text("text"),
            ),
            section(RuntimeSnapshotSections.DISPLAY, "lineCount" to Value.Int64(0)),
        )
        val b = snap(
            section(
                RuntimeSnapshotSections.VARS,
                "savedUserVars" to Value.Int64(2),
                "encoding" to Value.Text("binary"),
            ),
            section(RuntimeSnapshotSections.DISPLAY, "lineCount" to Value.Int64(9)),
        )
        val result = RuntimeSnapshotDiff.diff(a, b)
        assertTrue(result.divergences.size >= 3, "夹具须产生多个分歧")
        assertEquals(result.divergences.first(), result.first, "首差必须取第一个（反向证明 B）")
        assertTrue(result.first != result.divergences.last(), "首差不得是末个分歧")
        assertEquals(RuntimeSnapshotSections.VARS, result.first!!.section)
        assertEquals("savedUserVars", result.first!!.path)
    }

    @Test
    fun roundTripThenDiffIsSame() {
        val original = snap(
            section(RuntimeSnapshotSections.VARS, "bytes" to Value.Blob(byteArrayOf(9, 8, 7))),
            section(RuntimeSnapshotSections.RNG, "state" to Value.IntList(listOf(1L, 2L, 3L))),
        )
        val decoded = RuntimeSnapshot.decode(RuntimeSnapshot.encode(original))
        assertTrue(RuntimeSnapshotDiff.diff(original, decoded).same, "编解码往返后逐字段应一致")
    }

    private fun header(): Header = Header(buildStamp = "b", createdAt = "t", saveIdentity = "s")

    private fun snap(vararg sections: Section): Snapshot = Snapshot(header(), sections.toList())

    private fun section(name: String, vararg fields: Pair<String, Value>): Section =
        Section(name, linkedMapOf(*fields))
}
