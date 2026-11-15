package com.eratw.port.core.snapshot

import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.snapshot.RuntimeSnapshot.Header
import com.eratw.port.core.snapshot.RuntimeSnapshot.Section
import com.eratw.port.core.snapshot.RuntimeSnapshot.Snapshot
import com.eratw.port.core.snapshot.RuntimeSnapshot.Value
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v1 容器定向用例（契约 §1 / §2 / §3 反向证明 A、C、D 的钉子）。
 *
 * 只依赖 core 纯 JVM 面：会话用无参 [VariableStore] 与 [LineBufferConsoleSink]，
 * 快照一律由 [RuntimeSnapshotSections.collect] 现采，不装配真实游戏、不读语料。
 * 值模型里 [Value.Blob] 是普通类（按引用比较），故 round-trip 逐字段比：字节块走
 * contentEquals，其余 data class 走 equals —— 断言的是「字节真还原」而非「对象同一」。
 */
class RuntimeSnapshotTest {

    // ------------------------------------------------------------------
    // round-trip（反向证明 D：采集器漏掉 display 节必红）
    // ------------------------------------------------------------------

    @Test
    fun roundTripPreservesCollectedSectionsIncludingDisplay() {
        val original = sampleSnapshot()
        val back = RuntimeSnapshot.decode(RuntimeSnapshot.encode(original))

        assertEquals(original.sectionNames(), back.sectionNames(), "节名与写入序必须还原")
        assertTrue("display" in back.sectionNames(), "display 节必须存在（采集器漏采则本例红）")
        assertEquals(RuntimeSnapshotSections.KNOWN_SECTIONS, back.sectionNames(), "六节齐备且顺序一致")

        assertEquals(original.header.buildStamp, back.header.buildStamp)
        assertEquals(original.header.createdAt, back.header.createdAt)
        assertEquals(original.header.saveIdentity, back.header.saveIdentity)
        assertEquals(original.header.projectIdentity, back.header.projectIdentity)
        assertEquals(original.header.extra, back.header.extra, "未知头部键与换行转义须原样还原")
        assertEquals(original.truncated, back.truncated)

        for (section in original.sections) {
            val other = checkNotNull(back.section(section.name)) { "节 ${section.name} 丢失" }
            assertEquals(section.fields.size, other.fields.size, "节 ${section.name} 字段数")
            for ((key, value) in section.fields) {
                assertValueEquals(value, other.fields[key], "${section.name}.$key")
            }
        }
    }

    @Test
    fun unknownSectionIsCarriedThroughForForwardCompatibility() {
        val snap = Snapshot(
            Header(buildStamp = "b", extra = mapOf("future.key" to "v1\nv2")),
            listOf(Section("future.section", linkedMapOf("k" to Value.Text("hello")))),
        )
        val back = RuntimeSnapshot.decode(RuntimeSnapshot.encode(snap))
        assertEquals("future.section", back.sectionNames().single())
        assertEquals("hello", (back.section("future.section")!!.fields["k"] as Value.Text).value)
        assertEquals("v1\nv2", back.header.extra["future.key"])
    }

    // ------------------------------------------------------------------
    // 坏档拒绝且不崩（契约 S1 / 反向证明 A）
    // ------------------------------------------------------------------

    @Test
    fun badMagicIsRejectedWithoutCrash() {
        val bad = mutateRaw(RuntimeSnapshot.encode(sampleSnapshot())) { raw ->
            raw[0] = 0
        }
        val ex = assertFailsWith<RuntimeSnapshot.SnapshotFormatException> { RuntimeSnapshot.decode(bad) }
        assertTrue("magic" in (ex.message ?: ""), "诊断须点出魔数: ${ex.message}")
    }

    @Test
    fun wrongVersionIsRejectedWithoutCrash() {
        // version 是魔数之后的 u32le（偏移 7..10），改成 2 不改魔数，仍应被读侧拒绝。
        val bad = mutateRaw(RuntimeSnapshot.encode(sampleSnapshot())) { raw ->
            raw[7] = 2
            raw[8] = 0
            raw[9] = 0
            raw[10] = 0
        }
        val ex = assertFailsWith<RuntimeSnapshot.SnapshotFormatException> { RuntimeSnapshot.decode(bad) }
        assertTrue("版本" in (ex.message ?: ""), "诊断须点出版本: ${ex.message}")
    }

    @Test
    fun truncatedGzipIsRejectedWithoutCrash() {
        val good = RuntimeSnapshot.encode(sampleSnapshot())
        val cut = good.copyOfRange(0, good.size / 2)
        assertFailsWith<RuntimeSnapshot.SnapshotFormatException> { RuntimeSnapshot.decode(cut) }
    }

    @Test
    fun headerLengthOverflowIsRejectedWithoutCrash() {
        // headerLen 是魔数 + version 之后的 u32le（偏移 11..14）。
        val bad = mutateRaw(RuntimeSnapshot.encode(sampleSnapshot())) { raw ->
            raw[11] = 0x7F
            raw[12] = 0x7F
            raw[13] = 0x7F
            raw[14] = 0x7F
        }
        val ex = assertFailsWith<RuntimeSnapshot.SnapshotFormatException> { RuntimeSnapshot.decode(bad) }
        assertTrue("headerLen" in (ex.message ?: ""), "诊断须点出头部长度: ${ex.message}")
    }

    @Test
    fun isSnapshotProbeRecognizesOnlyV1() {
        val good = RuntimeSnapshot.encode(sampleSnapshot())
        assertTrue(RuntimeSnapshot.isSnapshot(good))
        assertFalse(RuntimeSnapshot.isSnapshot(byteArrayOf(1, 2, 3)))
        assertFalse(RuntimeSnapshot.isSnapshot("not a snapshot".toByteArray()))
        val magicBroken = mutateRaw(good) { raw -> raw[0] = 0 }
        assertFalse(RuntimeSnapshot.isSnapshot(magicBroken), "魔数不符的 v1 外壳不算 v1")
    }

    // ------------------------------------------------------------------
    // 体积截断上限（契约 S3）
    // ------------------------------------------------------------------

    @Test
    fun encodeWithinTrimsLongestSectionAndMarksTruncated() {
        val big = ByteArray(512 * 1024)
        java.util.Random(42L).nextBytes(big) // java.util.Random，不是 kotlin.random
        val snap = Snapshot(
            Header(buildStamp = "b"),
            listOf(
                Section(RuntimeSnapshotSections.VARS, linkedMapOf("bytes" to Value.Blob(big))),
                Section(
                    RuntimeSnapshotSections.RNG,
                    linkedMapOf("state" to Value.IntList(List(625) { it.toLong() })),
                ),
            ),
        )
        val maxBytes = 64L * 1024L
        assertTrue(RuntimeSnapshot.encode(snap).size.toLong() > maxBytes, "夹具须先真的超限")
        val bytes = RuntimeSnapshot.encodeWithin(snap, maxBytes)
        assertTrue(bytes.size.toLong() <= maxBytes, "落盘字节须回到上限内: ${bytes.size}")
        val back = RuntimeSnapshot.decode(bytes)
        assertTrue(back.truncated, "超限截断必须在头部记 truncated")
        val vars = checkNotNull(back.section(RuntimeSnapshotSections.VARS))
        val trimmed = (vars.fields["bytes"] as? Value.Blob)?.bytes?.size ?: 0
        assertTrue(trimmed < big.size, "最长节的字节块须被砍短: $trimmed")
    }

    // ------------------------------------------------------------------
    // 采集不含像素（契约 S1 / §4）
    // ------------------------------------------------------------------

    @Test
    fun collectCarriesNoPixelPayload() {
        val store = VariableStore()
        // 真装一个图形池，让资源节采到「占用字节数」这类计数字段而非像素本体。
        store.aux.slot(GRAPHICS_SLOT) { GraphicsPool() }.get(7)
        val sections = RuntimeSnapshotSections.collect(
            store, LineBufferConsoleSink(), events = listOf("evt-1"),
        )
        assertEquals(RuntimeSnapshotSections.KNOWN_SECTIONS, sections.map { it.name })

        val pixelContentKeys = listOf("pixelData", "pixels", "bitmap", "rgba", "argb", "pngBytes", "png", "image")
        for (section in sections) {
            for ((key, value) in section.fields) {
                assertTrue(
                    pixelContentKeys.none { key.equals(it, true) || key.endsWith(it, true) },
                    "${section.name}.$key 疑似承载像素内容",
                )
                if (value is Value.Blob) {
                    assertEquals(RuntimeSnapshotSections.VARS, section.name, "字节块只允许是变量节存档字节")
                    assertEquals("bytes", key)
                }
                if (section.name != RuntimeSnapshotSections.VARS) {
                    assertFalse(value is Value.Blob, "${section.name}.$key 不得携带字节块")
                }
            }
        }
        val res = checkNotNull(sections.firstOrNull { it.name == RuntimeSnapshotSections.RESOURCES })
        val pixelBytes = res.fields["gfx.pixelBytes"]
        if (pixelBytes != null) {
            assertTrue(pixelBytes is Value.Int64, "gfx.pixelBytes 只能是整数计数，不是像素字节块")
        }
    }

    // ------------------------------------------------------------------
    // 自动落盘缺省关（契约 S3 / 反向证明 C）
    // ------------------------------------------------------------------

    @Test
    fun autoDumpDefaultsToOff() {
        assertFalse(RuntimeSnapshotAutoDump.enabled(null), "缺省必须关")
        assertFalse(RuntimeSnapshotAutoDump.enabled(""))
        assertFalse(RuntimeSnapshotAutoDump.enabled("false"))
        assertFalse(RuntimeSnapshotAutoDump.enabled("0"))
        assertTrue(RuntimeSnapshotAutoDump.enabled("true"))
        assertTrue(RuntimeSnapshotAutoDump.enabled(" TRUE "))
        assertEquals("eratw.snapshot.autodump", RuntimeSnapshotAutoDump.PROPERTY)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun sampleSnapshot(): Snapshot {
        val sections = RuntimeSnapshotSections.collect(
            VariableStore(),
            LineBufferConsoleSink(),
            events = listOf("evt-1", "evt-2"),
        )
        return Snapshot(
            Header(
                buildStamp = "unit-build-1",
                createdAt = "2026-11-09T00:00:00Z",
                saveIdentity = "slot-7",
                projectIdentity = mapOf("program.erbc" to "abcdef0123"),
                extra = mapOf("note" to "line1\nline2"),
            ),
            sections,
        )
    }

    private fun assertValueEquals(expected: Value, actual: Value?, where: String) {
        assertNotNull(actual, "$where 字段缺席")
        if (expected is Value.Blob && actual is Value.Blob) {
            assertTrue(expected.bytes.contentEquals(actual.bytes), "$where 字节块内容不一致")
        } else {
            assertEquals(expected, actual, where)
        }
    }

    private fun mutateRaw(encoded: ByteArray, block: (ByteArray) -> Unit): ByteArray {
        val raw = GZIPInputStream(ByteArrayInputStream(encoded)).use { it.readBytes() }
        block(raw)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
