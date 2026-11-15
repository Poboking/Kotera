package com.eratw.port.core.mod

import com.eratw.port.modapi.ModValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话级 mod 存储容器（P1-4 ①，era-arch-v61u.md §18.6 / §8）。
 *
 * 钉四件事：
 * 1. [com.eratw.port.modapi.StorageApi] 契约语义在换后端后**一字不变**（缺键 null、删不存在 false、
 *    keys 是快照、跨包严格隔离）—— 否则就是借着接线偷偷改契约；
 * 2. **禁用不等于删除**：deactivate 一个字节都不掉，activate 回来原值还在；
 * 3. **孤儿段无损**：读档时没装的包整段保留，且再编码逐字节不变（被禁 mod 的数据不会因为
 *    别人增删而变形）；
 * 4. 未激活时的读写是 **fail-closed 抛错**，不是静默 no-op —— 静默 no-op 就是 §3.1 禁的「隐藏表象」。
 *
 * 与 ModStorageSectionTest 的分工：那套管**格式**（字节、排序、非法输入），本套管**容器的行为**。
 * 盘上往返仍无人调用（[ModStorageStore.encode] 无生产调用点），边界在 KDoc 已写明。
 */
class ModStorageStoreTest {

    // ------------------------------------------------------------------
    // 1. StorageApi 契约语义换后端不变
    // ------------------------------------------------------------------

    @Test
    fun missingKeyReadsNullAndMissingRemoveReportsFalse() {
        val api = ModStorageStore().apiFor("mod.a")
        assertNull(api.get("absent"))
        assertFalse(api.remove("absent"))
        assertEquals(emptySet(), api.keys())
    }

    @Test
    fun putOverwritesAndGetReturnsLatest() {
        val api = ModStorageStore().apiFor("mod.a")
        api.put("k", "v1")
        api.put("k", "v2")
        assertEquals("v2", api.get("k"))
        assertEquals(setOf("k"), api.keys())
    }

    @Test
    fun keysReturnsSnapshotNotLiveView() {
        val api = ModStorageStore().apiFor("mod.a")
        api.put("one", "1")
        val snapshot = api.keys()
        api.put("two", "2")
        assertEquals(setOf("one"), snapshot, "keys() 必须是快照：mod 可自由持有（StorageApi KDoc）")
        assertEquals(setOf("one", "two"), api.keys())
    }

    @Test
    fun twoPackagesNeverSeeEachOthersKeysOrValues() {
        val store = ModStorageStore()
        val a = store.apiFor("mod.a")
        val b = store.apiFor("mod.b")
        a.put("shared", "from-a")
        b.put("shared", "from-b")
        assertEquals("from-a", a.get("shared"))
        assertEquals("from-b", b.get("shared"))
        assertTrue(a.remove("shared"))
        assertEquals("from-b", b.get("shared"), "删自己那份不得影响别人")
    }

    @Test
    fun emptyAndUnicodeAndLongValuesRoundTripThroughStore() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        val cases = mapOf(
            "empty" to "",
            "cjk" to "后宫居住标记",
            "newline" to "a\nb\r",
            "emoji" to "🐉",
            "long" to "x".repeat(5_000),
            "tab-and-pipe" to "a\tb|c",
        )
        for ((k, v) in cases) api.put(k, v)
        val back = ModStorageStore.load(store.encode(), setOf("mod.a")).apiFor("mod.a")
        for ((k, v) in cases) assertEquals(v, back.get(k), "键 $k 的值必须逐字符回来")
    }

    // ------------------------------------------------------------------
    // 2. 禁用不等于删除
    // ------------------------------------------------------------------

    @Test
    fun deactivateKeepsEveryEntryAndBlocksReadWrite() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        api.put("keep", "me")
        store.deactivate("mod.a")

        assertFalse(store.isActive("mod.a"))
        assertEquals(setOf("mod.a"), store.inactivePackages())
        assertEquals(1, store.sizeOf("mod.a"), "禁用不得掉条目")
        assertFailsWith<IllegalStateException> { api.get("keep") }
        assertFailsWith<IllegalStateException> { api.put("new", "x") }
        assertFailsWith<IllegalStateException> { api.keys() }
        assertFailsWith<IllegalStateException> { api.remove("keep") }
    }

    @Test
    fun reactivateRestoresTheOriginalValuesNotAnEmptySection() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("count", "7")
        store.deactivate("mod.a")
        store.activate("mod.a")

        val api = store.apiFor("mod.a")
        assertTrue(store.isActive("mod.a"))
        assertEquals("7", api.get("count"), "重启用必须拿回原值，而不是被 activate 清空")
    }

    @Test
    fun deactivatingOnePackageDoesNotDisturbAnother() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("x", "1")
        store.apiFor("mod.b").put("y", "2")
        store.deactivate("mod.a")
        assertEquals("2", store.apiFor("mod.b").get("y"))
    }

    // ------------------------------------------------------------------
    // 3. 读档 / 孤儿段
    // ------------------------------------------------------------------

    @Test
    fun encodeDecodeRoundTripPreservesEveryPackageAndValue() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v-a")
        store.apiFor("mod.b").put("k", "v-b")
        store.deactivate("mod.b")

        val restored = ModStorageStore.load(store.encode(), knownPackages = setOf("mod.a"))
        assertEquals("v-a", restored.apiFor("mod.a").get("k"))
        assertEquals(setOf("mod.b"), restored.inactivePackages(), "已知集合里没有的包必须留成非激活段")
        assertEquals(setOf("mod.a", "mod.b"), restored.packages())
    }

    @Test
    fun reEnablingAnOrphanPackageAfterLoadReturnsItsData() {
        val store = ModStorageStore()
        store.apiFor("mod.gone").put("heirloom", "keep-me")
        val bytes = store.encode()

        // 下一次会话：mod.gone 没装 ⇒ 它是孤儿段；随后玩家重新启用它。
        val restored = ModStorageStore.load(bytes, knownPackages = emptySet())
        assertEquals(setOf("mod.gone"), restored.inactivePackages())
        restored.activate("mod.gone")
        assertEquals("keep-me", restored.apiFor("mod.gone").get("heirloom"))
    }

    @Test
    fun orphanBytesStayIdenticalWhileTheActivePackageGrows() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("one", "1")
        store.apiFor("mod.orphan").put("heirloom", "untouched")
        val before = store.encode()

        store.activate("mod.a")
        store.apiFor("mod.a").put("two", "2")
        store.apiFor("mod.a").put("three", "3")
        val after = store.encode()

        val orphanBefore = ModStorageSectionCodec.decode(before, setOf("mod.a"))
            .first { it.packageId == "mod.orphan" }
        val orphanAfter = ModStorageSectionCodec.decode(after, setOf("mod.a"))
            .first { it.packageId == "mod.orphan" }
        assertEquals(orphanBefore, orphanAfter, "别的包增删条目不得改动孤儿段的任何一个字节语义")
        assertEquals(1, orphanAfter.entries.size)
    }

    @Test
    fun encodeLoadEncodeIsByteIdentical() {
        val store = ModStorageStore()
        store.apiFor("mod.b").put("z", "9")
        store.apiFor("mod.a").put("a", "1")
        store.deactivate("mod.b")
        val first = store.encode()
        val second = ModStorageStore.load(first, knownPackages = setOf("mod.a")).encode()
        assertContentEquals(first, second, "恢复再落盘必须逐字节相同（否则存档比较没有意义）")
    }

    @Test
    fun entryInsertionOrderDoesNotLeakIntoEncodedBytes() {
        val x = ModStorageStore()
        x.apiFor("mod.a").put("alpha", "1")
        x.apiFor("mod.a").put("beta", "2")
        x.apiFor("mod.b").put("k", "1")
        val y = ModStorageStore()
        y.apiFor("mod.b").put("k", "1")
        y.apiFor("mod.a").put("beta", "2")
        y.apiFor("mod.a").put("alpha", "1")
        assertContentEquals(x.encode(), y.encode())
    }

    @Test
    fun loadRejectsCorruptBytesInsteadOfReturningAnEmptyStore() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v")
        val good = store.encode()
        assertFailsWith<ModStorageFormatException> {
            ModStorageStore.load(good.copyOf(good.size - 1), setOf("mod.a"))
        }
        assertFailsWith<ModStorageFormatException> { ModStorageStore.load(byteArrayOf(0, 1, 2, 3), emptySet()) }
    }

    @Test
    fun emptyStoreEncodesAndReloadsAsEmpty() {
        val bytes = ModStorageStore().encode()
        val restored = ModStorageStore.load(bytes, knownPackages = emptySet())
        assertEquals(emptySet(), restored.packages())
    }

    // ------------------------------------------------------------------
    // 4. 与宿主的接缝
    // ------------------------------------------------------------------

    @Test
    fun sectionsSnapshotMarksOrphanAsDerivedFromActivation() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v")
        store.apiFor("mod.b").put("k", "v")
        store.deactivate("mod.b")
        val byName = store.sectionsSnapshot().associateBy { it.packageId }
        assertFalse(byName.getValue("mod.a").orphan)
        assertTrue(byName.getValue("mod.b").orphan)
    }

    /** 格式侧的 ABSENT 墓碑（tag 0）没有 mod 侧生产者，但**读进来不得被丢掉**（KDoc 有裁定）。 */
    @Test
    fun decodedTombstoneSurvivesReencodeEvenThoughStorageApiCannotWriteOne() {
        val carrier = ModStorageSection(
            "mod.a",
            linkedMapOf("live" to ModValue.StrValue("v"), "gone" to null),
        )
        val bytes = ModStorageSectionCodec.encode(listOf(carrier))

        val store = ModStorageStore.load(bytes, knownPackages = setOf("mod.a"))
        val api = store.apiFor("mod.a")
        assertEquals("v", api.get("live"))
        assertNull(api.get("gone"), "墓碑对 mod 侧读作「没有这个键」")
        assertFalse(api.keys().contains("gone"), "keys() 不得报一个 get() 读不到值的键（口径必须自洽）")
        assertFalse(api.remove("gone"), "mod 看不见它，也就删不掉它 —— 返回 false 且不动那一位")
        assertEquals(2, store.sizeOf("mod.a"), "但容器必须仍占着这一位，否则再编码就丢字节")

        assertContentEquals(bytes, store.encode(), "含墓碑的段原样再编码必须逐字节相同")
    }
}
