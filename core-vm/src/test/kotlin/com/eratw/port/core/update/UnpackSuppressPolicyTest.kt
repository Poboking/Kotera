package com.eratw.port.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 随包解包让位判据（[UpdatePolicy.unpackSuppressedByNewerDiskData]）、逐文件让位档
 * 与两路读数装配的定向钉。
 *
 * 根因叙述（「下载完更新、重启后游戏本体还是旧版」的完整链）：首启档解包的逐文件判据是
 * 「目标存在且长度相同 ⇒ 跳过」，长度不同的文件一律被 APK 内那份覆盖 —— 盘上是数据包
 * 更新过的新语料、APK 里还是旧语料时，每次启动都会把更新打回旧版并整树重写约 919 MiB，
 * 且 digest/marker 随解包回写（版本回退），更新通道从此在「又提示更新 → 下载 → 应用 →
 * 打回」里循环。
 *
 * 本函数只回答「这份盘是不是比随包数据新」这一个事实，交由装载相位逐文件裁决
 * （[UpdatePolicy.bundledAssetNeedsCopy]）：让位档下已在位的不碰、缺失的照补，整轮解包
 * 照跑。**不得**在调用方用它把整轮解包短路。
 *
 * 每种边界各钉一条（判错的代价不对称）：
 * - 误让位 / 误 suppress（把换新 APK 也判成让位、整轮跳过）：新 APK 的语料永远落不了盘，
 *   覆盖安装失效；
 * - 漏让位 / 漏 suppress（数据包更新过的盘被解包覆盖）：主根因复发，冷启全量重写约
 *   919 MiB + 标记回拨，更新通道变成永动机循环。
 *
 * 另钉两路读数装配（判据本身的入参从哪来，CONTRACT-FIX-P13-SUPPRESS F2/F4）：
 * - [UpdatePolicy.sanitizeDataVersionMarker]：marker 坏值（负值/超界）对称化归 0 —— 巨值
 *   marker 会对任何未来 versionCode 恒让位，连换新 APK 的自愈路都关死；
 * - [UpdatePolicy.effectiveDiskDataVersion]：盘上版本 = marker 与文件级摘要**取大** ——
 *   应用段先落摘要、marker 后改名且失败只记日志，marker 停旧版时单信 marker 会把新版
 *   语料覆盖回旧版，循环以同形复发。两路 lane 合流后这个读数同时供空间预检与
 *   `GameAssetInstaller` 的逐文件让位档。
 */
class UnpackSuppressPolicyTest {

    @Test
    fun `盘上数据版本高于随包时必须让位`() {
        // 0.2.45 设备（随包 21）吃过 22 号数据包：本轮解包必须跳过覆盖。
        assertTrue(UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion = 22L, bundledVersionCode = 21L))
    }

    @Test
    fun `同版重装不让位（幂等解包照常）`() {
        // 首启档「长度相同跳过」对同版内容是正确的；判成真会把换 APK 的恢复路径断掉。
        assertFalse(UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion = 21L, bundledVersionCode = 21L))
    }

    @Test
    fun `换新 APK 不让位（随包语料要落盘）`() {
        assertFalse(UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion = 21L, bundledVersionCode = 22L))
    }

    @Test
    fun `新装设备不让位（没装过数据盘上版本为 0）`() {
        assertFalse(UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion = 0L, bundledVersionCode = 21L))
        assertFalse(UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion = 0L, bundledVersionCode = 0L))
    }

    // ------------------------------------------------------------------
    // marker 坏值对称化（F4）：解析失败/负值/超界一律归 0（fail-open）
    // ------------------------------------------------------------------

    @Test
    fun `marker 坏值一律归零 空串非数字负值溢出巨串`() {
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = ""))
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "   "))
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "abc"))
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "12x"))
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "-1"))
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "99999999999999999999"))
    }

    @Test
    fun `marker 超上界归零（自愈路不许被巨值关死） 上界恰好仍有效`() {
        val overMax = (UpdatePolicy.DATA_VERSION_MARKER_MAX + 1L).toString()
        assertEquals(expected = 0L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = overMax))
        assertEquals(
            expected = UpdatePolicy.DATA_VERSION_MARKER_MAX,
            actual = UpdatePolicy.sanitizeDataVersionMarker(raw = UpdatePolicy.DATA_VERSION_MARKER_MAX.toString()),
        )
    }

    @Test
    fun `marker 正常值原样保留 词法与旧内联链逐字相同（空白已剥 加号宽容）`() {
        assertEquals(expected = 21L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "21"))
        assertEquals(expected = 22L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = " 22 "))
        assertEquals(expected = 23L, actual = UpdatePolicy.sanitizeDataVersionMarker(raw = "+23"))
    }

    // ------------------------------------------------------------------
    // 盘上版本读数两源取大（F2）：marker 停旧版时以 digest 为准
    // ------------------------------------------------------------------

    @Test
    fun `盘上版本读数取两源大者 digest 新 marker 旧时以 digest 为准`() {
        assertEquals(
            expected = 22L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = 21L, digestVersion = 22L),
        )
        assertEquals(
            expected = 22L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = 22L, digestVersion = 21L),
        )
        assertEquals(
            expected = 21L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = 21L, digestVersion = 0L),
        )
    }

    @Test
    fun `盘上版本读数两源坏值一律归零（fail-open 不让位）`() {
        assertEquals(
            expected = 0L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = 0L, digestVersion = 0L),
        )
        assertEquals(
            expected = 0L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = Long.MAX_VALUE, digestVersion = 0L),
        )
        assertEquals(
            expected = 0L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = 0L, digestVersion = Long.MAX_VALUE),
        )
        assertEquals(
            expected = 21L,
            actual = UpdatePolicy.effectiveDiskDataVersion(markerVersion = -5L, digestVersion = 21L),
        )
    }

    @Test
    fun `marker 停旧版而摘要已新版时让位必须成立（随包不许打回）`() {
        // F2 病形：数据包 22 号应用段摘要已落、marker 改名失败停在 21 —— suppress 读数必须
        // 看到 22，否则随包解包把 22 号语料逐个覆盖回 APK 内 21 号，循环复发。
        val diskDataVersion = UpdatePolicy.effectiveDiskDataVersion(markerVersion = 21L, digestVersion = 22L)
        assertTrue(
            UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion = diskDataVersion, bundledVersionCode = 21L),
        )
    }
}
