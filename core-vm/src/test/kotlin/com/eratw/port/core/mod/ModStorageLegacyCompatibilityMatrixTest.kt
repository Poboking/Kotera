package com.eratw.port.core.mod

import com.eratw.port.modapi.ModValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task-115 向后兼容矩阵（MST1 侧车容器面）：v1（无 dataVersion 字段）与 v2（段尾 i32
 * dataVersion）的**手写旧字节**逐档验证「读得进 + 语义等价 + 不静默降级」。
 *
 * 纪律：字节按 [ModStorageSectionCodec] 线格式常量手工拼装（[raw] 只按规范写定长字段），
 * **不得**用 `ModStorageSectionCodec.encode` 生成后再解码 —— 那测的是自己读自己。
 *
 * 语义口径（[ModStorageStore] KDoc）：`dataVersionsByPackage[包] == null` = 容器里根本没有
 * 该包的段（无记录）；MST1 v1 段解码出 `dataVersion = 0` 是**显式记录到 0**，load 时无条件
 * 登记（ModStorageStore.kt:160），不得当成「无记录」——否则从 0 递增的迁移永不触发。
 * task-115 原文把 v1 读成「无记录」的措辞与此实现语义冲突，本矩阵按实现的实际语义钉死，
 * 口径冲突已作为「发现的不兼容」上报，不在测试里伪造实现。
 */
class ModStorageLegacyCompatibilityMatrixTest {

    @Test
    fun `05 MST1-v1 手写旧档读得进且 dataVersion 记为显式 0 并非无记录`() {
        val legacy = raw(version = 1, sectionCount = 1) {
            text("legacy.pkg")
            writeInt(3)
            text("alpha")
            writeByte(1)
            writeLong(42L)
            text("beta")
            writeByte(2)
            sized("旧值".toByteArray(Charsets.UTF_8))
            text("gone")
            writeByte(0)
        }
        // 前置：v1 没有段尾 dataVersion 字段（每段到条目读完即结束）。
        assertEquals(0x4D, legacy[0].toInt() and 0xFF)
        assertEquals(1, ((legacy[4].toInt() and 0xFF) shl 8) or (legacy[5].toInt() and 0xFF))
        assertEquals(1, ((legacy[6].toInt() and 0xFF) shl 24) or ((legacy[7].toInt() and 0xFF) shl 16) or
            ((legacy[8].toInt() and 0xFF) shl 8) or (legacy[9].toInt() and 0xFF))

        val section = ModStorageSectionCodec.decode(legacy, setOf("legacy.pkg")).single()
        assertEquals("legacy.pkg", section.packageId)
        assertEquals(0, section.dataVersion)
        assertEquals(false, section.orphan)
        assertEquals(ModValue.IntValue(42L), section.entries["alpha"])
        assertEquals(ModValue.StrValue("旧值"), section.entries["beta"])
        assertTrue(section.isTombstone("gone"))

        // 旧段升级写回：encode 必升 v2，且 dataVersion 保持 0（不丢字段、不猜新值）。
        val upgraded = ModStorageSectionCodec.encode(listOf(section))
        val writtenVersion = ((upgraded[4].toInt() and 0xFF) shl 8) or (upgraded[5].toInt() and 0xFF)
        assertEquals(2, writtenVersion)
        assertEquals(0, ModStorageSectionCodec.decode(upgraded, setOf("legacy.pkg")).single().dataVersion)
    }

    @Test
    fun `05b MST1-v1 旧档按 knownPackages 标记 orphan 且不丢段`() {
        val legacy = raw(version = 1, sectionCount = 1) {
            text("not.installed")
            writeInt(1)
            text("k")
            writeByte(1)
            writeLong(1L)
        }
        val kept = ModStorageSectionCodec.decode(legacy, emptySet()).single()
        assertEquals("not.installed", kept.packageId)
        assertTrue(kept.orphan, "knownPackages 里没有的包必须整段保留并标 orphan")
        assertEquals(ModValue.IntValue(1L), kept.entries["k"])
    }

    @Test
    fun `06 MST1-v2 手写旧档 dataVersion 表逐段原样读回`() {
        val legacy = raw(version = 2, sectionCount = 2) {
            text("a.pkg")
            writeInt(1)
            text("k")
            writeByte(1)
            writeLong(7L)
            writeInt(3)
            text("b.pkg")
            writeInt(0)
            writeInt(0)
        }
        val sections = ModStorageSectionCodec.decode(legacy, setOf("a.pkg", "b.pkg"))
        assertEquals(2, sections.size)
        assertEquals("a.pkg", sections[0].packageId)
        assertEquals(3, sections[0].dataVersion)
        assertEquals(ModValue.IntValue(7L), sections[0].entries["k"])
        assertEquals("b.pkg", sections[1].packageId)
        assertEquals(0, sections[1].dataVersion)
        assertTrue(sections[1].entries.isEmpty())

        // 容器级：dataVersion 表取自段内字段，逐包登记（不是全 0、也不是全缺）。
        val store = ModStorageStore.load(legacy, setOf("a.pkg", "b.pkg"))
        assertEquals(3, store.dataVersionsByPackage["a.pkg"])
        assertEquals(0, store.dataVersionsByPackage["b.pkg"])
        // 「无记录」只对容器里没有段的包成立 —— null 与显式 0 必须可区分。
        assertNull(store.dataVersionsByPackage["absent.pkg"])
        assertTrue(store.packages().containsAll(listOf("a.pkg", "b.pkg")))
    }

    // ------------------------------------------------------------------
    // 线格式 helper（大端定长，照 ModStorageSectionCodec 规范手写）
    // ------------------------------------------------------------------

    private fun raw(version: Int, sectionCount: Int, sections: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(0x4D535431)
            it.writeShort(version)
            it.writeInt(sectionCount)
            it.sections()
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.sized(bytes: ByteArray) {
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.text(value: String) = sized(value.toByteArray(Charsets.UTF_8))
}
