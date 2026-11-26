package com.eratw.port.core.mod

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 单包存储段的阈值诊断（task-185）：只读 [footprint] 读数 + 写入后每包每会话一次 WARN。
 *
 * 钉四件事：
 * 1. [footprint] 的字节口径与 [ModStorageStore.encode] / [ModStorageSectionCodec] 同源（含容器头与段尾
 *    dataVersion），读数本身只读 —— 调用前后整库编码字节不变；
 * 2. 判据是**严格大于**阈值：恰好 1024 键 / 恰好 256 KiB 不告警，多一键/多一字节才告警；
 * 3. 告警走既有 logSink 出口（[ModRuntimeHost] 构造期绑定），每包每会话一次，release 不清去重位；
 * 4. 诊断**不改写入语义**：无 sink 不抛、不拒写，未越限的写入字节与诊断前逐字节一致。
 *
 * 边界用例的字节推导（ASCII 键值，字节数 = 字符数）：MST1 容器头 10，单段 = 2 字节包名长 + 包名 +
 * 4 字节条目数 + Σ(2 键长 + 键 + 1 tag + 2 值长 + 值) + 4 字节 dataVersion。取 pkg = "mod.a"（5 字节）、
 * 4 个 2 字节键，则 262144 = 53 + Σ值长，取 65535×3 + 65486。
 */
class ModStorageFootprintTest {

    @Test
    fun footprintOfMissingPackageIsAllZero() {
        val store = ModStorageStore()
        val readout = store.footprint("mod.ghost")
        assertEquals("mod.ghost", readout.packageId)
        assertEquals(0, readout.keys)
        assertEquals(0, readout.encodedBytes)
        assertFalse(readout.overKeysLimit)
        assertFalse(readout.overBytesLimit)
    }

    @Test
    fun footprintOfEmptyActivatedSectionCountsHeaderAndSegmentOverhead() {
        val store = ModStorageStore()
        store.activate("mod.a")
        val readout = store.footprint("mod.a")
        assertEquals(0, readout.keys)
        // 10 容器头 + 2 包名长 + 5 包名 + 4 条目数 + 4 dataVersion（空段没有键值行）。
        assertEquals(25, readout.encodedBytes)
        assertFalse(readout.overKeysLimit)
        assertFalse(readout.overBytesLimit)
    }

    @Test
    fun footprintBytesMatchWholeStoreEncodingAndReadIsSideEffectFree() {
        val store = ModStorageStore()
        val api = store.apiFor("mod.a")
        api.put("alpha", "one")
        api.put("beta", "")
        api.put("gamma", "three")
        val before = store.encode()
        val readout = store.footprint("mod.a")
        assertEquals(3, readout.keys)
        assertEquals(before.size, readout.encodedBytes, "单包时整库编码 = 10 字节容器头 + 该段，与单段独立编码同形")
        assertContentEquals(before, store.encode(), "footprint 只读：调用前后字节一致")
    }

    @Test
    fun exactlyAtKeyLimitDoesNotWarnAndOneKeyOverWarnsOnce() {
        val store = ModStorageStore()
        val warnings = mutableListOf<String>()
        store.bindStorageFootprintWarningSink { warnings += it }

        store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS)
        assertEquals(emptyList<String>(), warnings, "恰好 $MOD_STORAGE_WARN_KEYS 键不越限：判据是严格大于")
        assertFalse(store.footprint("mod.a").overKeysLimit)

        store.apiFor("mod.a").put("k-over", "v")
        assertEquals(1, warnings.size)
        val line = warnings.single()
        assertTrue(line.contains("[mod.a][WARN]"), line)
        assertTrue(line.contains("mod.storage"), line)
        assertTrue(line.contains("keys=${MOD_STORAGE_WARN_KEYS + 1}"), line)
        assertTrue(line.contains("阈值 $MOD_STORAGE_WARN_KEYS"), line)
        assertTrue(line.contains("encodedBytes="), line)
        assertTrue(line.contains("阈值 $MOD_STORAGE_WARN_BYTES"), line)
        assertTrue(store.footprint("mod.a").overKeysLimit)
        assertFalse(store.footprint("mod.a").overBytesLimit)

        repeat(500) { store.apiFor("mod.a").put("more-$it", "v") }
        assertEquals(1, warnings.size, "同包同会话只上屏一次：去重位不因继续写入而追加")
    }

    @Test
    fun exactlyAtByteLimitDoesNotWarnAndOneEntryOverWarnsOnce() {
        val store = ModStorageStore()
        val warnings = mutableListOf<String>()
        store.bindStorageFootprintWarningSink { warnings += it }
        val api = store.apiFor("mod.a")
        api.put("k0", "x".repeat(65535))
        api.put("k1", "x".repeat(65535))
        api.put("k2", "x".repeat(65535))
        api.put("k3", "x".repeat(65486))
        assertEquals(MOD_STORAGE_WARN_BYTES, store.footprint("mod.a").encodedBytes)
        assertEquals(emptyList<String>(), warnings, "恰好 256 KiB 不越限：判据是严格大于")
        assertFalse(store.footprint("mod.a").overBytesLimit)

        api.put("k4", "y")
        assertEquals(MOD_STORAGE_WARN_BYTES + 8, store.footprint("mod.a").encodedBytes)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("encodedBytes=${MOD_STORAGE_WARN_BYTES + 8}"), warnings.single())
        assertTrue(store.footprint("mod.a").overBytesLimit)
        assertFalse(store.footprint("mod.a").overKeysLimit)
    }

    @Test
    fun twoPackagesWarnIndependentlyOnceEach() {
        val store = ModStorageStore()
        val warnings = mutableListOf<String>()
        store.bindStorageFootprintWarningSink { warnings += it }
        store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS + 1)
        store.putSmallKeys("mod.b", MOD_STORAGE_WARN_KEYS + 1)
        assertEquals(2, warnings.size, "两个包各一次")
        assertEquals(1, warnings.count { it.contains("[mod.a][WARN]") })
        assertEquals(1, warnings.count { it.contains("[mod.b][WARN]") })
        assertTrue(store.footprint("mod.a").overKeysLimit)
        assertTrue(store.footprint("mod.b").overKeysLimit)
    }

    @Test
    fun unboundSinkNeitherThrowsNorConsumesTheOneShotWarning() {
        val store = ModStorageStore()
        store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS + 1)
        assertTrue(store.footprint("mod.a").overKeysLimit, "无 sink 时写入照常，越限事实可读")
        assertEquals(emptySet<String>(), store.warnedFootprintPackages, "无出口不标记，留给接线后的第一次写入")

        val warnings = mutableListOf<String>()
        store.bindStorageFootprintWarningSink { warnings += it }
        store.apiFor("mod.a").put("after-bind", "v")
        assertEquals(1, warnings.size, "绑定后下一次写再判，补上本会话的唯一一次")
        store.apiFor("mod.a").put("after-bind-2", "v")
        assertEquals(1, warnings.size)
    }

    @Test
    fun releaseZeroesFootprintButKeepsSessionDedup() {
        val store = ModStorageStore()
        val warnings = mutableListOf<String>()
        store.bindStorageFootprintWarningSink { warnings += it }
        store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS + 1)
        assertEquals(1, warnings.size)

        assertTrue(store.release("mod.a"))
        val released = store.footprint("mod.a")
        assertEquals(0, released.keys)
        assertEquals(0, released.encodedBytes)
        assertFalse(released.overKeysLimit)
        assertFalse(released.overBytesLimit)

        store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS + 1)
        assertEquals(1, warnings.size, "release 只摘数据，不重置本会话已提醒过的判断")
    }

    @Test
    fun diagnosticsDoNotChangeEncodedBytes() {
        val plain = ModStorageStore()
        val watched = ModStorageStore()
        val warnings = mutableListOf<String>()
        watched.bindStorageFootprintWarningSink { warnings += it }
        for (store in listOf(plain, watched)) {
            store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS + 1)
            store.apiFor("mod.b").put("k", "v")
        }
        assertEquals(1, warnings.size, "watched 侧确实走了告警路径")
        assertContentEquals(plain.encode(), watched.encode(), "诊断不改写入语义：两库落盘字节逐字节一致")
    }

    @Test
    fun reservedHostKeysCountTowardTheSameFootprint() {
        val store = ModStorageStore()
        val warnings = mutableListOf<String>()
        store.bindStorageFootprintWarningSink { warnings += it }
        store.putSmallKeys("mod.a", MOD_STORAGE_WARN_KEYS)
        assertEquals(emptyList<String>(), warnings)

        store.putReservedKey("mod.a", MOD_STORAGE_RESERVED_KEY_PREFIX + "pkgVersion", "1")
        assertEquals(1, warnings.size, "宿主保留键同样占段内字节，写入后同一判据要判")
        assertEquals(MOD_STORAGE_WARN_KEYS + 1, store.footprint("mod.a").keys)
    }

    private fun ModStorageStore.putSmallKeys(packageId: String, count: Int) {
        val api = apiFor(packageId)
        for (index in 0 until count) api.put("k$index", "v")
    }
}
