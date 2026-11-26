package com.eratw.port.core.mod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「释放整包存档段」API（[ModStorageStore.release]）的定向用例。
 *
 * 钉住 EE 审计（`ModStorageUninstallRetentionE2ETest` 的取证链）缺的那一格：存档收段走
 * `storageProvider().sectionsSnapshot()` 全表，而容器原先没有「删整包段」的入口，于是卸载/停用/
 * 坏包之后的孤儿段永久随档走。本 API 只删**内存容器**里的段（盘上清单与 captureChunks 跳过是
 * 下一波 app 侧接线），且不改「停用/坏包默认保留孤儿段」的既有语义。
 *
 * 六条主用例对应任务验收项 1–6；另加版本记录随段释放、空 packageId 拒绝两条边界。
 * 反证：把 [ModStorageStore.release] 改成就地 `return false` 的 no-op，则本文件前两条当场红
 * （`release 激活段` / `release 孤儿段`）。
 */
class ModStorageReleaseTest {

    // 1. 激活段：释放后快照/容器/编码都不再含它，且重装从零开始。
    @Test
    fun releaseActiveSectionRemovesItFromSnapshotAndContainer() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v")
        assertTrue(store.isActive("mod.a"))

        assertTrue(store.release("mod.a"), "删掉存在的段必须报 true")

        assertEquals(emptyList(), store.sectionsSnapshot(), "释放后 sectionsSnapshot 不得再含该段")
        assertEquals(emptySet(), store.packages())
        assertEquals(-1, store.sizeOf("mod.a"), "段已不在容器：sizeOf 走缺失口径")
        assertFalse(store.isActive("mod.a"), "释放 = 整包遗忘，不得留下「激活但无段」的矛盾标记")
        assertNull(store.apiFor("mod.a").get("k"), "重装（activate 出的新空段）不得回魂旧值")
    }

    // 2. 孤儿段同样可释放：只对 active 生效的过滤器在这里会红。
    @Test
    fun releaseOrphanSectionWorksTheSameWay() {
        val seeded = ModStorageStore()
        seeded.apiFor("mod.live").put("k", "live")
        seeded.apiFor("mod.gone").put("heirloom", "keep-me")
        val store = ModStorageStore.load(seeded.encode(), knownPackages = setOf("mod.live"))
        assertEquals(setOf("mod.gone"), store.inactivePackages(), "前置：mod.gone 是读档留下的孤儿段")

        assertTrue(store.release("mod.gone"))

        assertEquals(listOf("mod.live"), store.sectionsSnapshot().map { it.packageId })
        assertEquals(-1, store.sizeOf("mod.gone"))
        assertEquals(emptySet(), store.inactivePackages())
    }

    // 3. 不存在的 packageId：幂等 no-op，不抛、不误伤已有包。
    @Test
    fun releaseUnknownPackageIsIdempotentNoOp() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v")

        assertFalse(store.release("never.installed"), "没有该段返回 false，不抛")
        assertFalse(store.release("never.installed"), "重复调用同样幂等")
        assertFalse(store.release("mod.b"), "前缀/近名不得误删")

        assertEquals("v", store.apiFor("mod.a").get("k"))
        assertEquals(listOf("mod.a"), store.sectionsSnapshot().map { it.packageId })
    }

    // 4. 只影响目标包：同前缀名（a.b / a.b.c / a.bc）不得被连带。
    @Test
    fun releaseOnePackageDoesNotTouchOthersIncludingSamePrefixNames() {
        val store = ModStorageStore()
        store.apiFor("a.b").put("k", "ab")
        store.apiFor("a.b.c").put("k", "abc")
        store.apiFor("a.bc").put("k", "abc-plain")

        assertTrue(store.release("a.b"))

        assertEquals(setOf("a.b.c", "a.bc"), store.packages(), "按 packageId 精确释放，不是前缀匹配")
        assertEquals("abc", store.apiFor("a.b.c").get("k"))
        assertEquals("abc-plain", store.apiFor("a.bc").get("k"))
        assertEquals(1, store.sizeOf("a.b.c"), "别人的段一个字节都不动")
        assertEquals(1, store.sizeOf("a.bc"))
    }

    // 5. 释放后再 encode → load 往返，被释放段不回魂。
    @Test
    fun releasedSectionDoesNotComeBackThroughEncodeLoadRoundTrip() {
        val store = ModStorageStore()
        store.apiFor("mod.keep").put("k", "keep")
        store.apiFor("mod.drop").put("k", "drop")
        assertTrue(store.release("mod.drop"))

        val bytes = store.encode()
        val reloaded = ModStorageStore.load(bytes, knownPackages = setOf("mod.keep", "mod.drop"))

        assertEquals(setOf("mod.keep"), reloaded.packages(), "load 全表重建也不得把已释放段带回来")
        assertEquals(listOf("mod.keep"), reloaded.sectionsSnapshot().map { it.packageId })
        assertNull(reloaded.apiFor("mod.drop").get("k"))
    }

    // 6. 真 codec 编解码读数：段数与字节数同步变少，减少量 = 被删段自编码（去容器头）的字节数。
    @Test
    fun releaseShrinksDecodedSectionCountAndEncodedBytesUsingRealCodec() {
        val store = ModStorageStore()
        for (id in listOf("mod.a", "mod.b", "mod.c")) store.apiFor(id).put("k", "v-$id")
        val before = store.encode()
        val beforeSections = ModStorageSectionCodec.decode(before, setOf("mod.a", "mod.b", "mod.c"))
        assertEquals(listOf("mod.a", "mod.b", "mod.c"), beforeSections.map { it.packageId })
        val victimBytes = ModStorageSectionCodec.encode(
            listOf(beforeSections.single { it.packageId == "mod.b" }),
        ).size - SECTION_CONTAINER_HEADER_BYTES

        assertTrue(store.release("mod.b"))
        val after = store.encode()
        val afterSections = ModStorageSectionCodec.decode(after, setOf("mod.a", "mod.c"))

        assertEquals(listOf("mod.a", "mod.c"), afterSections.map { it.packageId }, "解码段数必须少一")
        assertEquals(before.size - victimBytes, after.size, "字节减少量必须正好等于被删段的编码长度")
    }

    // 边界：版本记录必须随段一起走，否则重装后旧版本号冒充「档里已有记录」，跳过首次迁移。
    @Test
    fun releaseAlsoDropsDataVersionRecord() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v")
        store.setDataVersion("mod.a", 7)
        assertEquals(7, store.sectionsSnapshot().single { it.packageId == "mod.a" }.dataVersion)

        assertTrue(store.release("mod.a"))
        store.activate("mod.a")

        assertEquals(
            0,
            store.sectionsSnapshot().single { it.packageId == "mod.a" }.dataVersion,
            "释放再重装必须回到「无记录 → 缺省 0」口径，不能残留 7",
        )
    }

    // 边界：空 packageId 是非法入参（与 activate 同口径），且不得因校验失败误删任何段。
    @Test
    fun releaseRejectsBlankPackageId() {
        val store = ModStorageStore()
        store.apiFor("mod.a").put("k", "v")
        assertFailsWith<IllegalArgumentException> { store.release("   ") }
        assertEquals(listOf("mod.a"), store.sectionsSnapshot().map { it.packageId })
    }

    private companion object {
        /** MST1 容器头 = magic(4) + version(2) + sectionCount(4)；单段自编码减掉它才是段本体长度。 */
        const val SECTION_CONTAINER_HEADER_BYTES = 10
    }
}
