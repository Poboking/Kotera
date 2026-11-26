package com.eratw.port.core.vars

import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.ICharaSlotProjector
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M5-2 行操作钩子接点（[VariableStore] 五接点）的纯接线验收。
 *
 * 范界：只验钩子的**调用次数 / 顺序 / 映射内容**与「原生行变更完成之后才调」的时序，
 * 不重复真实桥大测试（投射判据、存读档、关系桥归 RealCharaSlotProjectorTest）。
 * 记录器是 [ICharaSlotProjector] 的最小实现，不持内核、不搬数据。
 *
 * 映射口径（EntityContracts.kt:299-306）：`map[旧行号] = 新行号`，-1 表示该行已删；
 * 行删除钩子只带「被删瞬间的实际原生行号」，不带映射表。
 *
 * 五接点与预期形态（契约 §2.2）：
 * - [VariableStore.deleteCharacter] / [VariableStore.deleteCharacters]：原生行删完后
 *   按**实际删除顺序**逐次回调；
 * - [VariableStore.swapCharacters]：换位后以「恒等 + a 对换 b」permutation 回调；
 * - [VariableStore.pickUpCharacters]：不单设接点，由内部 swap/delete 复合出整场映射；
 * - [VariableStore.sortCharactersByInt] / [VariableStore.sortCharactersByStr]：由排序
 *   实现产出的 order 取**逆**回调（禁止事后按列值猜映射）。
 */
class VariableStoreRowHooksTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 记录钩子调用的最小 [ICharaSlotProjector]。
     *
     * 每次回调同时抓 [VariableStore.characters] 的**行数与 NO 列快照**——那是「钩子
     * 调在原生行变更完成之后」的可观测证据：删除后行数已减、重排后 NO 列已换位。
     */
    private class Recorder(private val store: VariableStore) : ICharaSlotProjector {
        val deleted = ArrayList<Int>()
        val countsAtDelete = ArrayList<Int>()
        val noAtDelete = ArrayList<List<Long>>()
        val permuted = ArrayList<IntArray>()
        val countsAtPermute = ArrayList<Int>()
        val noAtPermute = ArrayList<List<Long>>()

        override fun project(entityId: Long): Int = -1

        override fun unproject(entityId: Long): Boolean = false

        override fun charIndexOf(entityId: Long): Int = -1

        override val rosterGeneration: Long get() = 0L

        override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int = -1

        override fun entityAt(charIndex: Int): Long = EntityIdCodec.NULL

        override fun projectedEntities(): LongArray = LongArray(0)

        override fun syncFromCharaSlot(entityId: Long) = Unit

        override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) {
            permuted.add(oldToNewIndexMap.copyOf())
            countsAtPermute.add(store.characters.count)
            noAtPermute.add(noSnapshot())
        }

        override fun onCharacterRowDeleted(deletedIndex: Int) {
            deleted.add(deletedIndex)
            countsAtDelete.add(store.characters.count)
            noAtDelete.add(noSnapshot())
        }

        override fun onWorldReset() = Unit

        private fun noSnapshot(): List<Long> =
            (0 until store.characters.count).map { store.readInt("NO", longArrayOf(it.toLong())) }
    }

    /** 4 行原生角色（NO 1..4）；CFLAG:0 逐行 10 / 30 / 20 / 0，给排序留一个非对合顺序。 */
    private fun storeOf4(): VariableStore {
        val store = VariableStore()
        for (no in 1L..4L) {
            store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            store.addCharacter(no)
        }
        // MASTER 出厂 0 会钉住行 0（SORTCHARA 的 fixMaster 语义），排序类用例要的是
        // 自由排序，故统一置「无主人公」；需要钉住语义的用例自己写回。
        store.writeInt("MASTER", longArrayOf(0), -1L)
        store.writeInt("CFLAG", longArrayOf(0, 0), 10L)
        store.writeInt("CFLAG", longArrayOf(1, 0), 30L)
        store.writeInt("CFLAG", longArrayOf(2, 0), 20L)
        store.writeInt("CFLAG", longArrayOf(3, 0), 0L)
        return store
    }

    /** 旁路对账快照：行数 + 每行 NO / CFLAG:0 + 三个引擎指针。 */
    private fun snapshot(store: VariableStore): String {
        val sb = StringBuilder()
        sb.append("count=").append(store.characters.count)
        for (row in 0 until store.characters.count) {
            sb.append("|r").append(row)
                .append(":no=").append(store.readInt("NO", longArrayOf(row.toLong())))
                .append(",c0=").append(store.readInt("CFLAG", longArrayOf(row.toLong(), 0)))
        }
        for (name in listOf("MASTER", "TARGET", "ASSI")) {
            sb.append('|').append(name).append('=').append(store.readInt(name, longArrayOf(0)))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 接点一/二：单参删除、数组参删除
    // ------------------------------------------------------------------

    @Test
    fun `deleteCharacter_行删完成后单次回调实际行号`() {
        val store = storeOf4()
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.deleteCharacter(1)
        assertContentEquals(intArrayOf(1), rec.deleted.toIntArray())
        assertEquals(listOf(3), rec.countsAtDelete, "回调瞬间行已删（4 变 3）")
        assertEquals(listOf(listOf(1L, 3L, 4L)), rec.noAtDelete, "回调瞬间 NO 列已是删后状态")
        assertTrue(rec.permuted.isEmpty(), "删除不走排列接点")
    }

    @Test
    fun `deleteCharacters_按实际删除顺序逐次回调`() {
        val store = storeOf4()
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.deleteCharacters(longArrayOf(0, 3))
        assertContentEquals(intArrayOf(3, 0), rec.deleted.toIntArray(), "降序摘除 = 实际删除顺序")
        assertEquals(listOf(3, 2), rec.countsAtDelete, "每次回调时行数已减 1")
        assertEquals(
            listOf(listOf(1L, 2L, 3L), listOf(2L, 3L)),
            rec.noAtDelete,
            "回调瞬间的 NO 列逐次收窄",
        )
        assertEquals(2, store.characters.count)
        assertTrue(rec.permuted.isEmpty())
    }

    // ------------------------------------------------------------------
    // 接点三：交换
    // ------------------------------------------------------------------

    @Test
    fun `swap_恒等加对换映射_自交换零调用`() {
        val store = storeOf4()
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.swapCharacters(0, 2)
        assertEquals(1, rec.permuted.size)
        assertContentEquals(intArrayOf(2, 1, 0, 3), rec.permuted.single(), "整表恒等 + 0 与 2 对换")
        assertEquals(listOf(4), rec.countsAtPermute)
        assertEquals(listOf(listOf(3L, 2L, 1L, 4L)), rec.noAtPermute, "回调瞬间行已换位")
        store.swapCharacters(1, 1)
        assertEquals(1, rec.permuted.size, "自交换行未变更，接点零调用")
        assertTrue(rec.deleted.isEmpty())
    }

    // ------------------------------------------------------------------
    // 接点四/五：两种排序（映射 = 排序实现产出 order 的逆）
    // ------------------------------------------------------------------

    @Test
    fun `sortInt_由排序实现产出order的逆映射`() {
        val store = storeOf4()
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.sortCharactersByInt("CFLAG", longArrayOf(0))
        // 键 10/30/20/0 升序：新位置到旧位置 order = 3,0,2,1；钩子收的是它的逆。
        assertContentEquals(intArrayOf(1, 3, 2, 0), rec.permuted.single())
        assertEquals(listOf(listOf(4L, 1L, 3L, 2L)), rec.noAtPermute, "回调瞬间 NO 列已重排")
        assertTrue(rec.deleted.isEmpty())
    }

    @Test
    fun `sortStr_由排序实现产出order的逆映射`() {
        val store = storeOf4()
        for ((row, v) in listOf(0 to "b", 1 to "d", 2 to "c", 3 to "a")) {
            store.writeStr("CSTR", longArrayOf(row.toLong(), 0), v)
        }
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.sortCharactersByStr("CSTR", longArrayOf(0))
        // "a"/"b"/"c"/"d" 落在旧行 3/0/2/1 ⇒ order = 3,0,2,1 ⇒ 逆 = 1,3,2,0。
        assertContentEquals(intArrayOf(1, 3, 2, 0), rec.permuted.single())
        assertEquals(listOf(listOf(4L, 1L, 3L, 2L)), rec.noAtPermute)
    }

    @Test
    fun `sort_钉MASTER时映射仍为order逆`() {
        val store = storeOf4()
        store.writeInt("MASTER", longArrayOf(0), 1L)
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.sortCharactersByInt("CFLAG", longArrayOf(0))
        // 行 1 钉住不动；旧行 3 / 0 / 2 依次填新位 0 / 2 / 3 ⇒ order = 3,1,0,2 ⇒ 逆 = 2,1,3,0。
        assertContentEquals(intArrayOf(2, 1, 3, 0), rec.permuted.single())
        assertEquals(listOf(listOf(4L, 2L, 1L, 3L)), rec.noAtPermute)
    }

    // ------------------------------------------------------------------
    // pickUp 的复合面（无独立接点）
    // ------------------------------------------------------------------

    @Test
    fun `pickUp_经swap与delete复合出noList顺序且无独立接点`() {
        val store = storeOf4()
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.pickUpCharacters(longArrayOf(2, 0))
        assertEquals(2, store.characters.count)
        // 复合回放：把记录到的 permutation / 删除按发生顺序作用到「原行号标签」上，
        // 终态必须逐位等于 noList 的顺序（钩子面没有独立 pickUp 接点，契约裁定）。
        val labels = MutableList(4) { it }
        for (map in rec.permuted) {
            assertEquals(labels.size, map.size, "permutation 覆盖整表")
            val next = MutableList(labels.size) { -1 }
            for (old in map.indices) if (map[old] >= 0) next[map[old]] = labels[old]
            labels.clear()
            labels.addAll(next)
        }
        for (d in rec.deleted) labels.removeAt(d)
        assertEquals(listOf(2, 0), labels.toList(), "复合映射终态 = noList 顺序，零串号")
        assertEquals(listOf(listOf(3L, 1L, 2L), listOf(3L, 1L)), rec.noAtDelete)
        assertEquals(listOf(listOf(3L, 2L, 1L, 4L), listOf(3L, 1L, 2L, 4L)), rec.noAtPermute)
        assertTrue(rec.deleted.isNotEmpty() && rec.permuted.isNotEmpty(), "两族接点都真的跑过")
    }

    // ------------------------------------------------------------------
    // 旁路零变化 / 非接点行操作
    // ------------------------------------------------------------------

    @Test
    fun `无projector旁路零变化`() {
        val withHook = storeOf4()
        val rec = Recorder(withHook)
        withHook.charaSlotProjector = rec
        val bare = storeOf4()
        for (store in listOf(withHook, bare)) {
            store.deleteCharacters(longArrayOf(1, 0))
            store.swapCharacters(0, 1)
            store.sortCharactersByInt("CFLAG", longArrayOf(0))
            store.pickUpCharacters(longArrayOf(0, 1))
        }
        assertEquals(snapshot(bare), snapshot(withHook), "null 旁路与有钩子侧的行状态逐位相同")
        assertTrue(rec.deleted.isNotEmpty() && rec.permuted.isNotEmpty(), "有钩子侧确实跑了接点")
    }

    @Test
    fun `尾增与整行复制不在五接点内零调用`() {
        val store = storeOf4()
        val rec = Recorder(store)
        store.charaSlotProjector = rec
        store.registerTemplate(CharacterTemplate(no = 5L, name = "角色5", csvNo = 5L))
        assertEquals(4, store.addCharacter(5L))
        assertEquals(5, store.addPseudoCharacter())
        store.copyCharacter(0L, 1L)
        assertTrue(rec.deleted.isEmpty() && rec.permuted.isEmpty(), "尾增/整行复制不动行号，接点零调用")
    }
}