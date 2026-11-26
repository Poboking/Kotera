package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 字符串驻留池：去重、空串哨兵、重置、越界。 */
class StringTableTest {

    @Test
    fun `下标 0 固定为空串`() {
        val pool = StringTable()
        assertEquals("", pool.lookup(0))
        assertEquals(1, pool.distinctCount)
        assertEquals(0, pool.intern(""))
    }

    @Test
    fun `相同内容只占一个槽位`() {
        val pool = StringTable()
        val a = pool.intern("フラン")
        val b = pool.intern("フラン")
        val c = pool.intern("フランドール")
        assertEquals(a, b)
        assertEquals(a + 1, c)
        assertEquals(3, pool.distinctCount) // "" + 两个不同串
        assertEquals("フラン", pool.lookup(a))
    }

    @Test
    fun `idOf 对未驻留串返回 null`() {
        val pool = StringTable()
        pool.intern("x")
        assertEquals(1, pool.idOf("x"))
        assertNull(pool.idOf("y"))
    }

    @Test
    fun `clear 后回到只剩空串`() {
        val pool = StringTable()
        pool.intern("a")
        pool.intern("b")
        pool.clear()
        assertEquals(1, pool.distinctCount)
        assertEquals(1, pool.intern("a")) // "" 固定占 0
    }

    @Test
    fun `越界下标抛 CodeEE`() {
        val pool = StringTable()
        assertFailsWith<CodeEE> { pool.lookup(-1) }
        assertFailsWith<CodeEE> { pool.lookup(99) }
    }

    // ---------------- compact（复位链的串表半边，契约见 StringTable.compact 的三条） ----------------

    @Test
    fun `compact 保留项逐字不变且按旧下标升序重编号`() {
        val pool = StringTable()
        val ids = IntArray(6) { pool.intern("s$it") } // 1..5（0 恒空串）
        val live = pool.newLiveMask()
        live[ids[2]] = true
        live[ids[4]] = true
        val snapshot = listOf(pool.lookup(ids[2]), pool.lookup(ids[4]))
        val remap = pool.compact(live)

        assertEquals(1, remap[ids[2]], "首个保留项接在空串之后")
        assertEquals(2, remap[ids[4]], "第二个保留项按旧序编号（升序 = 确定函数，不看哈希序）")
        assertEquals(snapshot[0], pool.lookup(remap[ids[2]]), "逐字不变：契约 1")
        assertEquals(snapshot[1], pool.lookup(remap[ids[4]]), "逐字不变：契约 1")
        assertEquals("", pool.lookup(0), "0 恒为空串且恒映到 0")
        assertEquals(3, pool.distinctCount, "只留空串 + 两条保留项")
        assertEquals(ids.size + 1, remap.size, "remap 长度 = 压实前的条数")
    }

    @Test
    fun `compact 未保留的旧下标映到 -1 读到即响`() {
        val pool = StringTable()
        val a = pool.intern("留着")
        val b = pool.intern("丢弃")
        val live = pool.newLiveMask()
        live[a] = true
        val remap = pool.compact(live)

        assertEquals(-1, remap[b], "漏登记的持有者读到的是越界，不是另一条串的静默错值（契约 3）")
        assertFailsWith<CodeEE> { pool.lookup(remap[b]) }
        assertNull(pool.idOf("丢弃"), "未保留的串本体连反查项一起归还")
        assertEquals(a, remap[a], "只留空串 + 一条 ⇒ 该项恰好回到原位")
    }

    @Test
    fun `compact 全存活时恒等映射且不重建表`() {
        val pool = StringTable()
        val a = pool.intern("甲")
        val b = pool.intern("乙")
        val live = pool.newLiveMask()
        for (i in 1 until pool.distinctCount) live[i] = true
        val remap = pool.compact(live)

        assertEquals(a, remap[a], "全存活 = 恒等映射")
        assertEquals(b, remap[b], "全存活 = 恒等映射")
        assertEquals(3, pool.distinctCount, "零收益的压实不付重建成本")
        assertEquals(b, pool.idOf("乙"), "表没换，旧的反查项仍在原位")
    }

    @Test
    fun `compact 后新 intern 接在保留项之后`() {
        val pool = StringTable()
        repeat(50) { pool.intern("junk$it") }
        val keep = pool.intern("GLOBALS 槽里的串")   // 压实前的旧下标
        val live = pool.newLiveMask()
        live[keep] = true
        val remap = pool.compact(live)

        assertEquals(1, remap[keep], "保留项重编号后是小下标（持有者要改写的就是这一格）")
        assertEquals("GLOBALS 槽里的串", pool.lookup(remap[keep]), "逐字不变：契约 1")
        assertEquals(2, pool.intern("复位后再写的串"), "新串接在存活项之后，不复用被丢弃的旧位")
        assertEquals(3, pool.distinctCount)
        assertEquals(2, pool.intern("复位后再写的串"), "驻留去重在压实后照旧成立")
    }

    // ---------------- charsTotal（2026-11-17 lane-k：串池字节的唯一真读数） ----------------

    /**
     * [StringTable.charsTotal] 是**增量维护**的 O(1) 标量：每条写入路径（入池/清池/压实）
     * 各自对齐一次，查询侧不扫。口径 = 池内各条 `length` 之和（UTF-16 code unit，空串 0 字）。
     *
     * 为什么必须在三条路径上都对：报告线程取这一枚数做「串池是不是那 380 MiB 无名差额」的判据
     * （真机 96,168 条，见 `LiveSetCensus.strTableChars` 的 KDoc），压实后不重算就会把复位链上
     * 已归还的字节继续算在账上 —— 一个偏大的假读数比没有读数更坏。
     */
    @Test
    fun `charsTotal 在入池清池与压实三条路径上都等于逐条长度之和`() {
        fun walked(pool: StringTable): Long =
            (0 until pool.distinctCount).sumOf { pool.lookup(it).length.toLong() }

        val pool = StringTable()
        assertEquals(0L, pool.charsTotal, "下标 0 空串贡献 0 字")

        pool.intern("一")            // 1
        pool.intern("二十二")        // +3 = 4
        assertEquals(4L, pool.charsTotal)
        val long = pool.intern("フランドール")  // +6 = 10，且去重不重复计数
        val oneId = pool.idOf("一")
        assertEquals(oneId, pool.intern("一"), "去重：同一内容永远拿到同一索引")
        assertEquals(10L, pool.charsTotal, "重复驻留不许二次累加")
        assertEquals(walked(pool), pool.charsTotal)

        // 压实：只留「フランドール」⇒ 归还不存活项的字数，口径 = 新池逐条之和（不是减出来的）。
        val live = pool.newLiveMask()
        live[long] = true
        pool.compact(live)
        assertEquals(6L, pool.charsTotal, "压实后只算保留项：${walked(pool)}")
        assertEquals(walked(pool), pool.charsTotal)

        // 全存活捷径也必须让读数与「新池 = 老池」对齐。
        val keepAll = pool.newLiveMask()
        for (i in 1 until pool.distinctCount) keepAll[i] = true
        pool.compact(keepAll)
        assertEquals(6L, pool.charsTotal, "全存活压实不换表，读数不变")

        pool.clear()
        assertEquals(0L, pool.charsTotal, "清池归零（只留空串）")
        assertEquals(walked(pool), pool.charsTotal)
    }

    /**
     * 实测尺（**算术闸，不是性能闸**）：把真机条目数按两个口径折成 MiB，钉住换算本身。
     *
     * - 口径 A（桌面短串尺）：`96,168 × 266 B ≈ 24.4 MiB`。266 B/条来自本类类头记的桌面读数
     *   90,625 条 / 池面 23.0 MiB（`StringTable.kt:18-27`）。
     * - 口径 B（整行长文本）：`96,168 × 2,000 字/条 × 2 B/字 ≈ 366.8 MiB`——正是 512 MiB 上限里
     *   那约 380 MiB 无名差额的量级。
     *
     * 两数之差就是本笔要判的那一问；用例只钉换算（`×2` 是 UTF-16 口径、`1048576` 是 MiB），
     * **不造大夹具、不改语料**要真机读数。
     */
    @Test
    fun `真机96168条在两个口径下的MiB换算`() {
        val entries = 96_168L
        val bytesPerMiB = 1_048_576L
        // 口径 A：桌面尺 266 B/条（23.0 MiB / 90,625 条）。
        val desktopBytesPerEntry = 266L
        val aBytes = entries * desktopBytesPerEntry
        assertEquals(25_580_688L, aBytes, "96,168 × 266")
        // 24.398 MiB ⇒ 两位小数 24.40；用 0.05 容差，不把断言绑死在某一种四舍五入上。
        assertEquals(24.4, aBytes.toDouble() / bytesPerMiB, 0.05, "口径 A ≈ 24.4 MiB")
        // 交叉核对桌面尺自身：90,625 条 × 266 B ≈ 23.0 MiB（类头 KDoc 的读数）。
        assertEquals(23.0, (90_625L * desktopBytesPerEntry).toDouble() / bytesPerMiB, 0.1)

        // 口径 B：平均 2,000 字/条、UTF-16 每字 2 B。
        val charsPerEntry = 2_000L
        val bBytes = entries * charsPerEntry * 2L
        assertEquals(384_672_000L, bBytes, "96,168 × 2000 × 2")
        assertEquals(366.9, bBytes.toDouble() / bytesPerMiB, 0.05, "口径 B ≈ 366.9 MiB（366.85 进一位）")
    }
}
