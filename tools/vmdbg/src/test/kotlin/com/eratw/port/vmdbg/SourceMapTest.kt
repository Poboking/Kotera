package com.eratw.port.vmdbg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SourceMap] 纯函数层：P1..P6 六类形状 + 行号两列口径（MODERN-R4-C 契约 §2.1/§2.4）。
 *
 * 全部夹具**内存构造**（`SourceMap.build`），不依赖真产物、不读文件、不起会话；
 * 真产物读数在 [CmdSrcMapTest] 的集成项里。
 */
class SourceMapTest {

    /** 夹具：函数名缺省 `F0..Fn`，行号表按 pc 直取（越界回落 -1，与 `Program.lineOf` 同口径）。 */
    private fun map(
        entryPcs: List<Int>,
        positions: List<String?>,
        instructionCount: Int,
        lines: IntArray,
        names: List<String> = entryPcs.indices.map { "F$it" },
    ): SourceMap = SourceMap.build(names, entryPcs, instructionCount, positions) { pc ->
        if (pc in lines.indices) lines[pc] else -1
    }

    // ------------------------------------------------------------------ P1 正常
    @Test
    fun `P1 正常 position 拆出文件与行号且归属到正确函数`() {
        val m = map(
            entryPcs = listOf(0, 4),
            positions = listOf("ERB/SUB/A.ERB:10", "ERB/B.ERB:20"),
            instructionCount = 8,
            lines = intArrayOf(10, 11, 12, 13, 20, 21, 22, 23),
        )

        val a = m.at(2)
        assertTrue(a.owned)
        assertEquals("F0", a.funcName)
        assertEquals(0, a.funcIndex)
        assertEquals("ERB/SUB/A.ERB", a.file)
        assertEquals(PositionKind.FILE_LINE, a.positionKind)
        // 断言口径：line0 = Program.lineOf 原值（0 基），line1 = line0 + 1。
        assertEquals(12, a.line0)
        assertEquals(13, a.line1)

        val b = m.at(5)
        assertEquals("F1", b.funcName)
        assertEquals(1, b.funcIndex)
        assertEquals("ERB/B.ERB", b.file)
        assertEquals(21, b.line0)
        assertEquals(22, b.line1)
    }

    @Test
    fun `P1 拆分行号用最后一个冒号且盘符形文件被判绝对`() {
        val p = SourceMap.parsePosition("C:/tmp/erb/x.ERB:12")
        assertEquals(PositionKind.FILE_LINE, p.kind)
        assertEquals("C:/tmp/erb/x.ERB", p.file)
        assertEquals(12, p.startLine)
        assertTrue(SourceMap.isAbsolutePath(p.file!!))
        // 相对路径不是绝对路径（正常产物的形态）。
        assertFalse(SourceMap.isAbsolutePath("ERB/SUB/A.ERB"))
        // 冒号后非十进制 ⇒ 不可拆（P3），不是「文件列 = 整个串」。
        assertEquals(PositionKind.UNSPLITTABLE, SourceMap.parsePosition("ERB/A.ERB:12x").kind)
        assertEquals(PositionKind.UNSPLITTABLE, SourceMap.parsePosition("ERB/A.ERB").kind)
        assertEquals(PositionKind.NONE, SourceMap.parsePosition("").kind)
        assertEquals(PositionKind.NONE, SourceMap.parsePosition(null).kind)
    }

    // ------------------------------------------------------------------ P2 合成标记
    @Test
    fun `P2 合成标记不当真实文件且计入审计`() {
        val m = map(
            entryPcs = listOf(0, 3),
            positions = listOf("·event-entry·", "ERB/A.ERB:5"),
            instructionCount = 6,
            lines = intArrayOf(1, 2, 3, 5, 6, 7),
        )

        val loc = m.at(1)
        assertTrue(loc.owned)
        assertTrue(loc.synthetic)
        assertEquals(PositionKind.SYNTHETIC, loc.positionKind)
        assertEquals("·event-entry·", loc.file)
        assertEquals(-1, m.summarize(0)!!.positionLine)

        val r = m.audit()
        assertEquals(1, r.funcSyntheticPosition)
        assertEquals(3, r.pcSynthetic)
        // 合成标记不是文件：distinctFiles 只数 P1 拆出的真实文件。
        assertEquals(1, r.distinctFiles)
        assertEquals(0, r.funcNoPosition)
    }

    // ------------------------------------------------------------------ P3 不可拆
    @Test
    fun `P3 不可拆 position 显式标记且计入 funcNoPosition`() {
        val m = map(
            entryPcs = listOf(0, 3),
            positions = listOf("ERB/A.ERB:10", "no-colon-here"),
            instructionCount = 6,
            lines = intArrayOf(10, 11, 12, 30, 31, 32),
        )

        val loc = m.at(4)
        assertTrue(loc.owned, "pc 归属到了函数（有区间）")
        assertNull(loc.file, "不可拆 ⇒ file 为 null，不回落成整串")
        assertTrue(loc.positionUnsplittable)
        assertEquals(PositionKind.UNSPLITTABLE, loc.positionKind)
        // 行号面与 position 是否可拆无关：行号表照常给值。
        assertEquals(31, loc.line0)
        assertEquals(32, loc.line1)

        val r = m.audit()
        assertEquals(1, r.funcNoPosition)
        assertEquals(0, r.funcSyntheticPosition)
        assertEquals(1, r.distinctFiles)
    }

    // ------------------------------------------------------------------ P4 无归属
    @Test
    fun `P4 pc 小于最小 entryPc 显式无归属且计入 pcUnowned`() {
        val m = map(
            entryPcs = listOf(5, 10),
            positions = listOf("ERB/A.ERB:1", "ERB/B.ERB:2"),
            instructionCount = 20,
            lines = IntArray(20) { it },
        )

        for (pc in 0 until 5) {
            val loc = m.at(pc)
            assertFalse(loc.owned, "pc=$pc 落在首函数之前")
            assertNull(loc.funcName)
            assertEquals(-1, loc.funcIndex)
            assertNull(loc.file)
            assertEquals(-1, loc.line0)
            assertEquals(-1, loc.line1)
        }
        assertEquals(0, m.at(5).funcIndex, "最小 entryPc 起归属第一个函数（原下标 0）")
        assertEquals(1, m.at(10).funcIndex)

        val r = m.audit()
        assertEquals(20, r.pcTotal)
        assertEquals(5, r.pcUnowned)
        assertEquals(15, r.pcTotal - r.pcUnowned)

        // 越界 pc 也走「无归属」返回（退出码判定在 CLI，纯层不抛）。
        assertFalse(m.at(-1).owned)
        assertFalse(m.at(20).owned)
    }

    // ------------------------------------------------------------------ P5 行号未知
    @Test
    fun `P5 lineOf 返回 -1 时 line0 与 line1 都原样为 -1`() {
        val m = map(
            entryPcs = listOf(0),
            positions = listOf("ERB/A.ERB:1"),
            instructionCount = 3,
            lines = intArrayOf(-1, -1, 7),
        )
        val unknown = m.at(0)
        assertEquals(-1, unknown.line0)
        assertEquals(-1, unknown.line1, "负数原样，不做 0 基换算")
        val known = m.at(2)
        assertEquals(7, known.line0)
        assertEquals(8, known.line1)
        assertEquals(2, m.audit().linesUnknown)

        val s = m.summarize(0)!!
        assertEquals(7, s.line0Min)
        assertEquals(7, s.line0Max)
        assertEquals(8, s.line1Max)
        assertEquals(2, s.linesUnknown)
    }

    // ------------------------------------------------------------------ P6 entryPc 非递增
    @Test
    fun `P6 entryPc 原序非递增照实报并按 entryPc 重建区间`() {
        val m = map(
            entryPcs = listOf(10, 2, 6),
            positions = listOf("ERB/A.ERB:1", "ERB/B.ERB:2", "ERB/C.ERB:3"),
            instructionCount = 12,
            lines = IntArray(12) { it },
        )

        // 原下标不动：区间按 entryPc 排序，但输出 funcIndex 用原下标。
        assertFalse(m.at(1).owned)
        assertEquals(1, m.at(2).funcIndex)
        assertEquals(1, m.at(5).funcIndex)
        assertEquals(2, m.at(6).funcIndex)
        assertEquals(2, m.at(9).funcIndex)
        assertEquals(0, m.at(10).funcIndex)
        assertEquals(0, m.at(11).funcIndex)

        val r = m.audit()
        assertEquals(1, r.funcNonIncreasingEntry, "pair(10→2) 一处非递增；2→6 递增不计")
        assertEquals(2, r.pcUnowned)
        assertEquals(3, r.funcTotal)
    }

    @Test
    fun `P6 entryPc 重复时前一个区间为空且后一个拥有重合 pc`() {
        val m = map(
            entryPcs = listOf(3, 3, 7),
            positions = listOf("ERB/A.ERB:1", "ERB/B.ERB:2", "ERB/C.ERB:3"),
            instructionCount = 10,
            lines = IntArray(10) { it },
        )
        assertEquals(1, m.audit().funcNonIncreasingEntry, "entryPc 重复计入非递增")
        assertEquals(0, m.summarize(0)!!.instrCount, "重复入口的前一个区间为空")
        assertEquals(4, m.summarize(1)!!.instrCount)
        assertEquals(1, m.at(3).funcIndex, "重合 pc 归后一个（稳定排序）")
        assertEquals(3, m.audit().pcUnowned, "首函数入口之前的 3 个 pc 无归属")
    }

    // ------------------------------------------------------------------ 行号口径 & 名字查询
    @Test
    fun `行号两列在全域上恒为 line1 = line0 + 1 除非 line0 为负`() {
        val m = map(
            entryPcs = listOf(0, 3),
            positions = listOf("ERB/A.ERB:10", "ERB/B.ERB:20"),
            instructionCount = 6,
            lines = intArrayOf(9, -1, 11, 19, 20, -1),
        )
        for (pc in 0 until m.instructionCount) {
            val loc = m.at(pc)
            if (loc.line0 < 0) {
                assertEquals(-1, loc.line1, "pc=$pc")
            } else {
                assertEquals(loc.line0 + 1, loc.line1, "pc=$pc")
            }
        }
    }

    @Test
    fun `名字查询忽略大小写且找不到返回 null`() {
        val m = map(
            entryPcs = listOf(0, 2),
            positions = listOf("ERB/a.ERB:1", "ERB/b.ERB:2"),
            instructionCount = 4,
            lines = intArrayOf(1, 2, 3, 4),
            names = listOf("MINI_PASS", "EVENTFIRST#0"),
        )
        assertEquals(0, m.findFunction("mini_pass"))
        assertEquals(1, m.findFunction("eventfirst#0"))
        assertNull(m.findFunction("MINI_PASSX"))
        assertEquals("MINI_PASS", m.summarize(0)!!.name)
        assertEquals("ERB/a.ERB", m.summarize(0)!!.file)
    }
}
