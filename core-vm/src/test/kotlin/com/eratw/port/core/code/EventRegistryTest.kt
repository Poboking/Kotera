package com.eratw.port.core.code

import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EventRegistry] 四桶派生判据（M3-1）。
 *
 * 断言面：四桶执行序（ONLY→PRI→NORMAL→LATER，同桶链接序稳定，跨包成员全保留）、
 * 与 [FunctionTable.eventCallOrder]（base-only 装载视图）对同一批元数据的**双路同序**
 * （桶语义单一真源证明）、SINGLE 旗标透传、LSC 脱节负控。
 */
class EventRegistryTest {

    private fun eventMeta(
        index: Int,
        key: String,
        isOnly: Boolean = false,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
        defIndex: Int = index,
    ): FuncMeta = FuncMeta(
        index = index,
        key = key,
        isEvent = true,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        defIndex = defIndex,
    )

    private fun candidate(meta: FuncMeta): SymbolCandidate =
        SymbolCandidate(meta = meta, ownerPackageId = "pkg${meta.index % 2}")

    /** 构造带事件的 catalog：candidates 直接给，政策用 LEGACY（事件不参与覆盖语义无关政策）。 */
    private fun catalogOf(metas: List<FuncMeta>): SymbolCatalog =
        SymbolCatalog.build(metas.map { candidate(it) }, ResolverPolicy.LEGACY_LAST_WINS)

    @Test
    fun `四桶执行序与跨包成员全保留`() {
        // 同名事件 6 个成员：链接序 = index 升序；桶位乱序。
        val metas = listOf(
            eventMeta(0, "EVT", isLater = true),
            eventMeta(1, "EVT"),
            eventMeta(2, "EVT", isPri = true),
            eventMeta(3, "EVT", isOnly = true),
            eventMeta(4, "EVT", isLater = true),
            eventMeta(5, "EVT", isPri = true),
        )
        val reg = EventRegistry.of(catalogOf(metas))
        val members = reg.membersOf("EVT")
        // 并列加桶（LabelDictionary.cs:228-235，在册 B23）：#ONLY(3) 除桶 0 外还落桶 2 ⇒ 7 条。
        // 桶序：ONLY(3) → PRI(2,5) → NORMAL(1,3) → LATER(0,4)；同桶保持链接序（2 在 5 前、0 在 4 前）。
        assertContentEquals(intArrayOf(3, 2, 5, 1, 3, 0, 4), members.map { it.physicalIndex }.toIntArray())
        // 全部成员保留（一对多基数），归属包透传（candidate helper 的 pkg = index % 2）。
        assertEquals(7, members.size)
        assertEquals("pkg1", members[0].ownerPackageId)
        assertEquals(0, members[0].bucket)
        assertEquals(2, members[3].bucket)
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), members.map { it.bucket }, "桶位单调不减，重复项各带自己的桶")
        assertEquals(3, members[4].physicalIndex, "#ONLY 成员的桶 2 那份")
        assertTrue(members[4].isOnly, "桶 2 那份仍带 ONLY 旗标（旗标随成员带出，不随桶变形）")
        // 非事件键空表；键大写归一。
        assertTrue(reg.membersOf("NOT_EVENT").isEmpty())
        assertEquals(1, reg.eventKeys.size)
        assertEquals(setOf("EVT"), reg.eventKeys)
        assertFalse(reg.hasSingle("EVT"))
    }

    @Test
    fun `与FunctionTable双路对拍同序`() {
        // 三事件 × 各 3-4 成员，桶位与装载序交叉；base-only 装载视图（FunctionTable）
        // 与链接派生视图（EventRegistry）对同一批元数据必须同序（桶语义单一真源）。
        val metas = mutableListOf<FuncMeta>()
        var idx = 0
        for (key in listOf("EVENTFIRST", "EVENTTURNEND", "EVT3")) {
            val plans = listOf(
                arrayOf(false, false, true), // LATER：只落桶 3
                arrayOf(true, false, false), // ONLY：落桶 0 与桶 2
                arrayOf(false, true, false), // PRI：只落桶 1
                arrayOf(true, false, true),  // ONLY+LATER：落桶 0、2、3
                arrayOf(false, true, true),  // PRI+LATER：落桶 1 与 3（PC 装载期警告「会被调用2次」）
            )
            for (p in plans) {
                metas += eventMeta(idx, key, isOnly = p[0], isPri = p[1], isLater = p[2], defIndex = idx)
                idx++
            }
        }
        val table = FunctionTable(metas)
        val reg = EventRegistry.of(catalogOf(metas))
        for (key in listOf("EVENTFIRST", "EVENTTURNEND", "EVT3", "eventturnend")) {
            // 逐对比较 (成员, 桶)：两路都必须给出「外层桶序 + 内层装载序」的同一串展开
            val viaTable = table.eventDispatchOrder(key)!!.map { it.first.index to it.second }
            val viaRegistry = reg.membersOf(key).map { it.physicalIndex to it.bucket }
            assertEquals(viaTable, viaRegistry, "键 $key 双路同序失败")
        }
        // 并列加桶后展开条数严格大于成员数（重复项在场），不是排他单选。
        // EVT3 的五条成员下标 10..14 = LATER / ONLY / PRI / ONLY+LATER / PRI+LATER：
        //   桶 0 = 11,13；桶 1 = 12,14；桶 2 = 11（ONLY 唯一进桶 2 的那位：13/14 都被 `!isPri && !isLater`
        //   挡在桶 2 外，LabelDictionary.cs:234）；桶 3 = 10,13,14 ⇒ 5 成员展开成 8 条。
        val expanded = reg.membersOf("EVT3")
        assertEquals(5, metas.count { it.key == "EVT3" })
        assertEquals(
            listOf(11, 13, 12, 14, 11, 10, 13, 14),
            expanded.map { it.physicalIndex },
            "5 成员展开成 8 条：ONLY 多落桶 2，ONLY+LATER 与 PRI+LATER 各多落桶 3",
        )
        assertEquals(listOf(0, 0, 1, 1, 2, 3, 3, 3), expanded.map { it.bucket }, "桶位单调不减")
        assertTrue(expanded.size > metas.count { it.key == "EVT3" }, "展开条数必须大于成员数")
    }

    @Test
    fun `SINGLE旗标透传`() {
        val metas = listOf(
            eventMeta(0, "EVT", isSingle = true),
            eventMeta(1, "EVT"),
        )
        val reg = EventRegistry.of(catalogOf(metas))
        val members = reg.membersOf("evt")
        assertTrue(members[0].isSingle)
        assertFalse(members[1].isSingle)
        assertTrue(reg.hasSingle("EVT"))
    }

    @Test
    fun `事件不参与覆盖全部成员保留`() {
        // MODERN 政策下事件成员即使「重名」也不冲突（事件不进 bindings、不参与覆盖）。
        val metas = listOf(
            eventMeta(0, "EVT"),
            eventMeta(1, "EVT"),
        )
        val reg = EventRegistry.of(
            SymbolCatalog.build(
                metas.map { SymbolCandidate(meta = it, ownerPackageId = "m") },
                ResolverPolicy.MODERN,
            ),
        )
        assertEquals(2, reg.membersOf("EVT").size)
    }

    @Test
    fun `ONLY旗标随四桶表带出M2-6`() {
        val metas = listOf(
            eventMeta(0, "EVT", isOnly = true),
            eventMeta(1, "EVT"),
        )
        val reg = EventRegistry.of(catalogOf(metas))
        val members = reg.membersOf("EVT")
        // #ONLY 并列落桶 0 与桶 2（LabelDictionary.cs:228-231 第四个 if 不看 isOnly），
        // 同桶链接序不变；旗标随成员带出，不许靠桶位反推。
        assertContentEquals(intArrayOf(0, 0, 1), members.map { it.physicalIndex }.toIntArray())
        assertTrue(members[0].isOnly)
        assertEquals(0, members[0].bucket)
        assertTrue(members[1].isOnly, "桶 2 那份同样带 ONLY 旗标：真 VM 里它被 FinishEvent 截掉，但投桶照 C#")
        assertEquals(2, members[1].bucket)
        assertFalse(members[2].isOnly)
        assertEquals(2, members[2].bucket)
    }

    /**
     * 装载序扰动（B23 收口判据②）：换链接次序只改「同桶内的先后」，桶位形状不变；
     * 同一批候选双建双跑必须逐行同序（不靠 HashMap 迭代序）。
     */
    @Test
    fun `装载序扰动_桶位形状不变仅桶内链接序随链接序`() {
        val pl = eventMeta(0, "EVT", isPri = true, isLater = true)
        val only = eventMeta(1, "EVT", isOnly = true)
        val pri = eventMeta(2, "EVT", isPri = true)
        val later = eventMeta(3, "EVT", isLater = true)
        val plain = eventMeta(4, "EVT")
        val forward = EventRegistry.of(catalogOf(listOf(pl, only, pri, later, plain)))
        val reversed = EventRegistry.of(catalogOf(listOf(plain, later, pri, only, pl)))
        val again = EventRegistry.of(catalogOf(listOf(pl, only, pri, later, plain)))
        val bucketShape = listOf(0, 1, 1, 2, 2, 3, 3)
        assertEquals(bucketShape, forward.membersOf("EVT").map { it.bucket })
        assertEquals(bucketShape, reversed.membersOf("EVT").map { it.bucket }, "换链接次序不改桶位形状")
        // 正序链接 0..4：桶 1 = pl, pri；桶 2 = only, plain；桶 3 = pl, later
        assertEquals(listOf(1, 0, 2, 1, 4, 0, 3), forward.membersOf("EVT").map { it.physicalIndex })
        // 逆序链接（4,3,2,1,0）：桶 1 = pri, pl；桶 2 = plain, only；桶 3 = later, pl
        assertEquals(listOf(1, 2, 0, 4, 1, 3, 0), reversed.membersOf("EVT").map { it.physicalIndex })
        assertEquals(
            forward.membersOf("EVT").map { it.physicalIndex to it.bucket },
            again.membersOf("EVT").map { it.physicalIndex to it.bucket },
            "同夹具双建双跑必须逐行同序",
        )
    }

    // 「LSC 脱节 / 非事件成员污染」的负控不可达：SymbolCatalog 只有 build 一个构建
    // 出口（private constructor，ABI-7 封装），合法输入下 events 与候选集必然一致。
    // EventRegistry.of 里对应的 require 是防未来演化的防御判据，不立用例。
}
