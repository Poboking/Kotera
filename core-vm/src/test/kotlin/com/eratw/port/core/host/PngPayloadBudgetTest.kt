package com.eratw.port.core.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OOM-C 波（2026-11-09）：堆外 PNG 载荷的**活跃映射上界**。
 *
 * 治的是 OOM-B 结案时挂着的 `TODO(verify)` 第三项：ART 没有公开 unmap，映射丢弃引用后
 * 活到 GC。平台限制改不了，但「无界累积」可以改 —— 满额就让 [spill] 返回 null，调用方
 * 回落堆内闭包（那一份至少还进池级账本、还被预算闸看得见）。
 *
 * 本文件钉四件事：满额回落且不留垃圾文件、close 腾出名额后可再 spill、上界默认值与
 * 读数行、空载荷 / 已关闭不算「满额回落」（拒绝计数只记真正的满额）。
 *
 * 传任意非空字节即可：spill 只写文件并映射，不校验 PNG；decode 不在本文件的判据内
 * （逐字复原由既有 PngPayloadStoreTest 守护）。
 */
class PngPayloadBudgetTest {

    private fun tempDir(): Path = Files.createTempDirectory("png-budget-test")

    private fun payloadFiles(dir: Path): List<Path> =
        Files.newDirectoryStream(dir, "payload-*.png").use { it.toList() }

    @Test
    fun `满额 spill 回落 null 且删掉刚写的文件`() {
        val dir = tempDir()
        val store = MappedPngPayloadStore(dir, maxLiveMappings = 2)
        val first = assertNotNull(store.spill(byteArrayOf(1, 2, 3)))
        assertNotNull(store.spill(byteArrayOf(4, 5, 6)))
        assertEquals(2, store.stats().live)
        assertEquals(2, store.stats().peak)
        assertEquals(2, payloadFiles(dir).size)

        assertNull(store.spill(byteArrayOf(7, 8, 9)))
        assertEquals(1L, store.stats().rejected)
        assertEquals(2, payloadFiles(dir).size, "满额那次写出的文件必须删掉，不许留垃圾")
        assertEquals(2, store.stats().peak, "拒绝的那一发不许把峰值顶上去")

        first.close()
        assertEquals(1, store.stats().live)
        assertNotNull(store.spill(byteArrayOf(10, 11, 12)), "close 腾出名额后应能再 spill")
        assertEquals(2, store.stats().live)
        store.close()
    }

    @Test
    fun `默认上界 256 且读数行可直接进报告`() {
        val store = MappedPngPayloadStore(tempDir())
        val s = store.stats()
        assertEquals(256, s.maxLiveMappings)
        assertEquals(0, s.live)
        assertEquals(0, s.peak)
        assertEquals(0L, s.rejected)
        assertTrue(s.line().contains("上界 256 张"), s.line())
        assertTrue(s.line().contains("满额回落 0 次"), s.line())
        store.close()
    }

    @Test
    fun `空载荷与已关闭不算满额回落`() {
        val store = MappedPngPayloadStore(tempDir(), maxLiveMappings = 1)
        assertNull(store.spill(ByteArray(0)))
        assertEquals(0L, store.stats().rejected)
        store.close()
        assertNull(store.spill(byteArrayOf(1)))
        assertEquals(0L, store.stats().rejected, "这两条是早退分支，不是上界拒绝")
    }

    @Test
    fun `满额回落后已存在的载荷仍可解码形状稳定`() {
        val store = MappedPngPayloadStore(tempDir(), maxLiveMappings = 1)
        val payload = assertNotNull(store.spill(byteArrayOf(9, 8, 7)))
        assertNotNull(payload.readBytes(), "已存在的载荷不受后续满额影响")
        assertNull(store.spill(byteArrayOf(6, 5, 4)))
        assertEquals(1, store.stats().live)
        assertEquals(1, store.stats().peak)
        store.close()
    }
}
