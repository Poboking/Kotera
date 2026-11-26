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
 * 钉五件事：
 * 1. [com.eratw.port.modapi.StorageApi] 契约语义在换后端后**一字不变**（缺键 null、删不存在 false、
 *    keys 是快照、跨包严格隔离）—— 否则就是借着接线偷偷改契约；
 * 2. **禁用不等于删除**：deactivate 一个字节都不掉，activate 回来原值还在；
 * 3. **孤儿段无损**：读档时没装的包整段保留，且再编码逐字节不变（被禁 mod 的数据不会因为
 *    别人增删而变形）；
 * 4. 未激活时的读写是 **fail-closed 抛错**，不是静默 no-op —— 静默 no-op 就是 §3.1 禁的「隐藏表象」；
 * 5. **保留命名空间双向门**（ModDX-4 BS 方案 A）：作者视图写侧拒保留前缀键（put/remove）、宿主
 *    专用门 [ModStorageStore.putReservedKey] 只收保留前缀键 —— 两扇门各自只放行自己的一半；
 *    读侧不隐藏保留键（keys 与 get 一一对应口径含保留键）。
 * 6. **写侧入档闸**（M2-BUG-04）：键/值必须能被 MST1 段格式编码（合法 UTF-16 且 UTF-8 ≤ 65535
 *    字节），拒在**落写之前** —— 修复前判据只在编码期兑现：超限文本先落写、再经足迹诊断旁路抛
 *    IAE（写已落地却报错 + 毒条目令本包后续每次 put 连锁抛 + 存档抓取整链失败）。
 *
 * 与 ModStorageSectionTest 的分工：那套管**格式**（字节、排序、非法输入），本套管**容器的行为**。
 * 分片编码已在生产存档路径上（ModSidecarStoragePort.captureChunks 逐段
 * [ModStorageSectionCodec.encode]），整表 [ModStorageStore.encode] 的直接调用点在测试/台架。
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

    // ------------------------------------------------------------------
    // 5. 保留命名空间双向门（ModDX-4 BS：作者面强拒 + 宿主专用门）
    // ------------------------------------------------------------------

    /** 作者 put 保留前缀键 fail-closed 拒（判据 1）：非法入参当场暴露，且不落任何字节。 */
    @Test
    fun authorPutOnReservedPrefixIsRejected() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        assertFailsWith<IllegalArgumentException> {
            api.put(MOD_STORAGE_PACKAGE_VERSION_KEY, "author-crafted")
        }
        assertNull(api.get(MOD_STORAGE_PACKAGE_VERSION_KEY), "拒写不得留半字节")
        assertEquals(emptySet(), api.keys())
    }

    /** 作者 remove 保留前缀键 fail-closed 拒：宿主已记账的键不许被作者删掉（先经宿主门种入真键）。 */
    @Test
    fun authorRemoveOnReservedPrefixIsRejected() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        store.putReservedKey("mod.a", MOD_STORAGE_PACKAGE_VERSION_KEY, "1.0")
        assertFailsWith<IllegalArgumentException> { api.remove(MOD_STORAGE_PACKAGE_VERSION_KEY) }
        assertEquals("1.0", api.get(MOD_STORAGE_PACKAGE_VERSION_KEY), "拒删后宿主记账原值仍在（读侧零变化）")
        assertEquals(1, store.sizeOf("mod.a"))
    }

    /** 非保留键照常（判据 3）：同后缀无前缀的键不受门影响 —— 门只认保留前缀形状。 */
    @Test
    fun nonReservedKeysStillWorkOnAuthorView() {
        val api = ModStorageStore().apiFor("mod.a")
        api.put("pkgVersion", "author-data")
        assertEquals("author-data", api.get("pkgVersion"))
        assertTrue(api.remove("pkgVersion"))
        assertEquals(emptySet(), api.keys())
    }

    /** 双向门的另一半（组长裁决）：putReservedKey 对非保留前缀键同样拒 —— 保留命名空间只有一扇门。 */
    @Test
    fun putReservedKeyRejectsNonReservedKeysToo() {
        val store = ModStorageStore()
        store.activate("mod.a")
        assertFailsWith<IllegalArgumentException> { store.putReservedKey("mod.a", "pkgVersion", "1") }
        assertFailsWith<IllegalArgumentException> { store.putReservedKey("mod.a", "normal", "1") }
        assertEquals(0, store.sizeOf("mod.a"), "拒写不得凭空落键")
    }

    /** 宿主写保留键成功（判据 3）且读侧零变化（判据 2）：keys() 照旧含保留键，与 get 一一对应。 */
    @Test
    fun hostPutReservedKeySucceedsAndReadSideStaysUnchanged() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        store.putReservedKey("mod.a", MOD_STORAGE_PACKAGE_VERSION_KEY, "1.0")
        assertEquals("1.0", api.get(MOD_STORAGE_PACKAGE_VERSION_KEY))
        assertEquals(setOf(MOD_STORAGE_PACKAGE_VERSION_KEY), api.keys(), "keys() 含保留键不变（读侧零变化）")
        api.put("author-data", "v")
        assertEquals(
            setOf(MOD_STORAGE_PACKAGE_VERSION_KEY, "author-data"),
            api.keys(),
            "保留键与普通键在 keys() 里共存，谁也不隐藏谁",
        )
    }

    /** 宿主门的状态闸：未激活 / 未知包 fail-closed（视图侧 requireActive 的宿主侧对偶）。 */
    @Test
    fun hostPutReservedKeyFailsClosedOnInactiveOrUnknownPackage() {
        val store = ModStorageStore()
        store.activate("mod.a")
        store.deactivate("mod.a")
        assertFailsWith<IllegalStateException> {
            store.putReservedKey("mod.a", MOD_STORAGE_PACKAGE_VERSION_KEY, "1.0")
        }
        assertFailsWith<IllegalStateException> {
            store.putReservedKey("mod.never", MOD_STORAGE_PACKAGE_VERSION_KEY, "1.0")
        }
    }

    // ------------------------------------------------------------------
    // 6. 写侧入档闸（M2-BUG-04）：键/值必须能被 MST1 段格式编码，拒在落写之前
    // ------------------------------------------------------------------

    /** 超过 u16 上限（65535 字节）的值：拒在落写之前，段不被毒化、后续 put 照常。 */
    @Test
    fun putRejectsValueOverMst1U16LimitBeforeLandingWrite() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        val oversized = "x".repeat(65_536)
        val error = assertFailsWith<IllegalArgumentException> { api.put("big", oversized) }
        assertTrue(
            "MST1" in error.message!!,
            "错误须点名入档约束（MST1），不得只报编码器内部标签：${error.message}",
        )
        assertFalse(api.keys().contains("big"), "被拒的写不得落进段（修复前 = 值先进段再抛 IAE）")
        assertNull(api.get("big"))
        api.put("after", "ok")
        assertEquals("ok", api.get("after"), "段未被毒化：后续普通写照常（修复前 = 每次后续 put 连锁抛）")
    }

    /** 超过 u16 上限的键：同样拒在落写之前（键也是 u16 长度前缀寻址，超限即不可编码）。 */
    @Test
    fun putRejectsKeyOverMst1U16LimitBeforeLandingWrite() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        val oversizedKey = "k".repeat(65_536)
        assertFailsWith<IllegalArgumentException> { api.put(oversizedKey, "v") }
        assertEquals(emptySet(), api.keys(), "被拒的键不得落进段")
    }

    /** 孤立代理对（非法 UTF-16）：严格编码器拒，拒在落写之前（存档抓取同判据）。 */
    @Test
    fun putRejectsLoneSurrogateTextBeforeLandingWrite() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        val poisoned = "abc\uD800def"
        assertFailsWith<IllegalArgumentException> { api.put("surrogate", poisoned) }
        assertNull(api.get("surrogate"), "非法 UTF-16 不得落进段")
        api.put("after", "ok")
        assertEquals("ok", api.get("after"), "段未被毒化：后续普通写照常")
    }

    /** 恰好 u16 上限的值合法（闭区间），且整段可编码 —— 入档闸的上边界钉死。 */
    @Test
    fun putBoundaryValueAtU16LimitIsAcceptedAndEncodable() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        val boundary = "x".repeat(65_535)
        api.put("boundary", boundary)
        assertEquals(boundary, api.get("boundary"))
        assertTrue(store.encode().isNotEmpty(), "按构造可编码：含边界值的段 encode 不抛")
    }

    /** 宿主专用门同闸：putReservedKey 的超限值同样拒在落写之前（同一份格式、同一份判据）。 */
    @Test
    fun putReservedKeyAlsoGatedBeforeLandingWrite() {
        val store = ModStorageStore()
        store.activate("mod.a")
        assertFailsWith<IllegalArgumentException> {
            store.putReservedKey("mod.a", MOD_STORAGE_PACKAGE_VERSION_KEY, "x".repeat(65_536))
        }
        assertEquals(0, store.sizeOf("mod.a"), "宿主门被拒的写同样不得落进段")
    }
}
