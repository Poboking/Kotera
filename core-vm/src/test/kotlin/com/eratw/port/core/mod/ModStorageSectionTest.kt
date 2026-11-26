package com.eratw.port.core.mod

import com.eratw.port.core.mod.ModStorageFormatException.Reason
import com.eratw.port.modapi.ModValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModStorageSectionTest {
    @Test
    fun emptyContainerHasExactTenByteHeader() {
        val expected = hex("4d535431000200000000")
        assertContentEquals(expected, ModStorageSectionCodec.encode(emptyList()))
        assertTrue(ModStorageSectionCodec.decode(expected, emptySet()).isEmpty())
    }

    @Test
    fun threeWireTagsAndBigEndianWidthsMatchHandwrittenBytes() {
        val section = ModStorageSection("p", linkedMapOf(
            "c" to ModValue.StrValue("中\u0000"),
            "b" to ModValue.IntValue(0x0102030405060708L),
            "a" to null,
        ))
        // v2：段尾多一个 i32 dataVersion（本段 0）。
        val expected = hex(
            "4d5354310002000000010001700000000300016100000162010102030405060708" +
                "000163020004e4b8ad0000000000",
        )
        assertContentEquals(expected, ModStorageSectionCodec.encode(listOf(section)))
        assertEquals(listOf(section), ModStorageSectionCodec.decode(expected, setOf("p")))
        assertContentEquals(expected, ModStorageSectionCodec.encode(ModStorageSectionCodec.decode(expected, emptySet())))
    }

    @Test
    fun legacyV1BytesDecodeWithDataVersionZeroAndReEncodeAsV2() {
        val legacy = raw(version = 1, sectionCount = 1) {
            text("p"); writeInt(1); text("k"); writeByte(0)
        }
        val decoded = ModStorageSectionCodec.decode(legacy, setOf("p")).single()
        assertEquals(0, decoded.dataVersion, "旧档缺 dataVersion 字段必须视作 0")
        assertTrue(decoded.isTombstone("k"))

        val upgraded = ModStorageSectionCodec.encode(listOf(decoded))
        val writtenVersion = ((upgraded[4].toInt() and 0xFF) shl 8) or (upgraded[5].toInt() and 0xFF)
        assertEquals(ModStorageSectionCodec.VERSION, writtenVersion, "写出必须升级成当前格式版本")
        assertEquals(decoded, ModStorageSectionCodec.decode(upgraded, setOf("p")).single())
        val reencoded = ModStorageSectionCodec.encode(ModStorageSectionCodec.decode(upgraded, emptySet()))
        assertContentEquals(upgraded, reencoded, "v2 再编码必须逐字节稳定")
    }

    @Test
    fun negativeDataVersionIsRejectedInV2Segments() {
        val bytes = raw(version = 2) { text("p"); writeInt(0); writeInt(-1) }
        assertEquals(17, rejected(bytes, Reason.INVALID_DATA_VERSION).offset)
    }

    @Test
    fun allLongBitsAndUnicodeStringsRoundTripWithoutLoss() {
        val values = linkedMapOf<String, ModValue?>()
        listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L).forEachIndexed { index, value ->
            values["n$index"] = ModValue.IntValue(value)
        }
        listOf("", "中文", "e\u0301", "\u00E9", "\uD800\uDC00", " \u0000\t\r\n ").forEachIndexed { index, value ->
            values["s$index"] = ModValue.StrValue(value)
        }
        values["deleted"] = null
        val original = ModStorageSection("package.one", values)
        val bytes = ModStorageSectionCodec.encode(listOf(original))
        val decoded = ModStorageSectionCodec.decode(bytes, setOf(original.packageId))
        assertEquals(listOf(original), decoded)
        assertEquals(original.hashCode(), decoded.single().hashCode())
        assertContentEquals(bytes, ModStorageSectionCodec.encode(decoded))
    }

    @Test
    fun insertionOrderDoesNotAffectEncodedBytes() {
        val entries = linkedMapOf<String, ModValue?>("z" to null, "a" to ModValue.IntValue(7), "m" to ModValue.StrValue("text"))
        val first = listOf(ModStorageSection("z", entries), ModStorageSection("a", entries))
        val second = first.reversed().map { ModStorageSection(it.packageId, it.entries.entries.reversed().associate { e -> e.toPair() }) }
        assertContentEquals(ModStorageSectionCodec.encode(first), ModStorageSectionCodec.encode(second))
        repeat(8) { assertContentEquals(ModStorageSectionCodec.encode(first), ModStorageSectionCodec.encode(first)) }
    }

    @Test
    fun orderingUsesUnsignedUtf8BytesInsteadOfUtf16OrSignedBytes() {
        val expected = listOf("", "\u0000", "A", "a", "aa", "\u0080", "\uE000", "\uD800\uDC00")
        val entries = expected.reversed().associateWith { ModValue.IntValue(1) }
        val input = expected.reversed().map { ModStorageSection(it, entries) }
        val decoded = ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(input), expected.toSet())
        assertEquals(expected, decoded.map { it.packageId })
        decoded.forEach { assertEquals(expected, it.entries.keys.toList()) }
    }

    @Test
    fun namesAreNotTrimmedCaseFoldedOrUnicodeNormalized() {
        val names = listOf("x", "X", " x ", "e\u0301", "\u00E9")
        val original = names.mapIndexed { index, name ->
            ModStorageSection(name, names.associateWith { ModValue.IntValue(index.toLong()) })
        }
        val decoded = ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(original), setOf("X", "\u00E9"))
        assertEquals(names.toSet(), decoded.map { it.packageId }.toSet())
        decoded.forEach {
            assertEquals(names.toSet(), it.entries.keys)
            assertEquals(it.packageId !in setOf("X", "\u00E9"), it.orphan)
        }
    }

    @Test
    fun unknownPackagesSurviveAndReenableWithoutChangingAnyByte() {
        val original = listOf(
            ModStorageSection("active", mapOf("n" to ModValue.IntValue(8))),
            ModStorageSection("disabled", mapOf("gone" to null, "s" to ModValue.StrValue("保留"))),
        )
        val bytes = ModStorageSectionCodec.encode(original)
        val disabled = ModStorageSectionCodec.decode(bytes, setOf("active"))
        assertFalse(disabled[0].orphan)
        assertTrue(disabled[1].orphan)
        assertEquals(original[1].entries, disabled[1].entries)
        assertContentEquals(bytes, ModStorageSectionCodec.encode(disabled))
        val enabled = ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(disabled), setOf("active", "disabled"))
        assertEquals(original, enabled)
        assertContentEquals(bytes, ModStorageSectionCodec.encode(enabled))
        assertTrue(ModStorageSectionCodec.decode(bytes, emptySet()).all { it.orphan })
    }

    @Test
    fun orphanSegmentBytesRemainIdenticalWhileActiveSegmentChangesSize() {
        val orphanBytes = body {
            text("z.off")
            writeInt(3)
            text("gone"); writeByte(0)
            text("n"); writeByte(1); writeLong(Long.MIN_VALUE)
            text("text"); writeByte(2); text("留")
            writeInt(7)
        }
        val original = raw(version = 2, sectionCount = 2) {
            text("a.on"); writeInt(1); text("v"); writeByte(2); text("old"); writeInt(0)
            write(orphanBytes)
        }
        val decoded = ModStorageSectionCodec.decode(original, setOf("a.on"))
        assertTrue(decoded[1].orphan)
        assertContentEquals(original, ModStorageSectionCodec.encode(decoded))
        val changed = ModStorageSectionCodec.encode(listOf(
            ModStorageSection("a.on", mapOf("more" to null, "v" to ModValue.StrValue("a much longer replacement"))),
            decoded[1],
        ))
        assertContentEquals(orphanBytes, changed.copyOfRange(changed.size - orphanBytes.size, changed.size))
        assertEquals(decoded[1], ModStorageSectionCodec.decode(changed, setOf("a.on"))[1])
    }

    @Test
    fun tombstoneRemainsDistinctFromMissingZeroAndEmptyString() {
        val original = ModStorageSection("p", mapOf("deleted" to null, "zero" to ModValue.IntValue(0), "empty" to ModValue.StrValue("")))
        val restored = ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(listOf(original)), setOf("p")).single()
        assertEquals(3, restored.entries.size)
        assertTrue(restored.isTombstone("deleted"))
        assertTrue(restored.entries.containsKey("deleted"))
        assertNull(restored.entries["deleted"])
        assertFalse(restored.isTombstone("missing"))
        assertFalse(restored.entries.containsKey("missing"))
        assertFalse(restored.isTombstone("zero"))
        assertFalse(restored.isTombstone("empty"))
        assertEquals(original, restored)
    }

    @Test
    fun emptySectionsAndEmptyKeysAreValid() {
        val input = listOf(ModStorageSection("empty"), ModStorageSection("", mapOf("" to null)))
        val bytes = ModStorageSectionCodec.encode(input)
        val decoded = ModStorageSectionCodec.decode(bytes, setOf("", "empty"))
        assertTrue(decoded[0].isTombstone(""))
        assertTrue(decoded[1].entries.isEmpty())
        assertContentEquals(bytes, ModStorageSectionCodec.encode(decoded))
    }

    @Test
    fun orphanFlagIsDerivedMetadataAndIsNotWrittenToTheFormat() {
        val active = ModStorageSection("p", mapOf("a" to null))
        val orphan = ModStorageSection("p", active.entries, orphan = true)
        assertContentEquals(ModStorageSectionCodec.encode(listOf(active)), ModStorageSectionCodec.encode(listOf(orphan)))
        assertFalse(ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(listOf(orphan)), setOf("p")).single().orphan)
    }

    @Test
    fun invalidUtf8IsRejectedInEveryTextPositionIncludingOrphans() {
        val invalid = listOf(hex("80"), hex("c080"), hex("eda080"), hex("f4908080"), hex("f5808080"), hex("e228a1"), hex("e282"))
        for (textBytes in invalid) {
            val cases = listOf(
                raw { sized(textBytes); writeInt(0) } to "packageId",
                raw { text("p"); writeInt(1); sized(textBytes); writeByte(0) } to "key",
                raw { text("p"); writeInt(1); text("k"); writeByte(2); sized(textBytes) } to "STR value",
            )
            for ((bytes, label) in cases) {
                for (known in listOf(emptySet(), setOf("p"))) {
                    val error = rejected(bytes, Reason.INVALID_UTF8, known)
                    assertTrue(error.message.orEmpty().contains(label))
                }
            }
        }
    }

    @Test
    fun utf8ErrorReportsTheActualInvalidByteOffset() {
        val bytes = raw { sized(hex("6180")); writeInt(0) }
        assertEquals(13, rejected(bytes, Reason.INVALID_UTF8).offset)
    }

    @Test
    fun unpairedUtf16IsRejectedOnEncodeInsteadOfReplaced() {
        for (invalid in listOf("\uD800", "\uDC00", "a\uD800b")) {
            val cases = listOf(
                ModStorageSection(invalid),
                ModStorageSection("p", mapOf(invalid to null)),
                ModStorageSection("p", mapOf("k" to ModValue.StrValue(invalid))),
            )
            for (section in cases) {
                val error = assertFailsWith<IllegalArgumentException> { ModStorageSectionCodec.encode(listOf(section)) }
                assertTrue(error.message.orEmpty().contains("UTF-16"))
            }
        }
    }

    @Test
    fun unknownVersionsAreRejectedBeforeParsingSections() {
        // v1（只读兼容）与 v2（当前写出）之外一律拒：0 与 3 是紧邻边界的非法值，0xFFFF 是 u16 极值。
        for (version in listOf(0, 3, 0xFFFF)) {
            val bytes = raw(version = version, sectionCount = 0) { }
            assertEquals(4, rejected(bytes, Reason.UNSUPPORTED_VERSION).offset)
        }
    }

    @Test
    fun badMagicIsDistinguishableFromBadVersion() {
        val bytes = ModStorageSectionCodec.encode(emptyList())
        bytes[0] = 'X'.code.toByte()
        assertEquals(0, rejected(bytes, Reason.BAD_MAGIC).offset)
    }

    @Test
    fun everyProperPrefixIsRejectedWithATruncationOffset() {
        val bytes = ModStorageSectionCodec.encode(listOf(
            ModStorageSection("包", mapOf("deleted" to null, "long" to ModValue.IntValue(Long.MIN_VALUE), "text" to ModValue.StrValue("文\uD800\uDC00"))),
            ModStorageSection("empty"),
        ))
        for (size in bytes.indices) rejected(bytes.copyOf(size), Reason.TRUNCATED)
    }

    @Test
    fun truncatedLongReportsItsPayloadStart() {
        val bytes = raw { text("p"); writeInt(1); text("k"); writeByte(1); writeInt(123) }
        assertEquals(21, rejected(bytes, Reason.TRUNCATED).offset)
    }

    @Test
    fun negativeSectionCountIsRejected() {
        assertEquals(6, rejected(raw(sectionCount = -1) { }, Reason.INVALID_COUNT).offset)
    }

    @Test
    fun unsignedKeyCountAndHugeSectionCountCannotOverflowOrDriveUnboundedLoops() {
        val unsigned = rejected(raw { text("p"); writeInt(-1) }, Reason.TRUNCATED)
        assertTrue(unsigned.message.orEmpty().contains("4294967295"))
        assertEquals(17, unsigned.offset)
        assertEquals(10, rejected(raw(sectionCount = Int.MAX_VALUE) { }, Reason.TRUNCATED).offset)
    }

    @Test
    fun unknownTagsAreRejectedEvenInOrphanSections() {
        for (tag in listOf(3, 127, 255)) {
            val bytes = raw { text("p"); writeInt(1); text("k"); writeByte(tag) }
            for (known in listOf(emptySet(), setOf("p"))) {
                assertEquals(20, rejected(bytes, Reason.UNKNOWN_VALUE_TYPE, known).offset)
            }
        }
    }

    @Test
    fun trailingBytesAreNotSilentlyIgnored() {
        val bytes = ModStorageSectionCodec.encode(emptyList()) + byteArrayOf(0)
        assertEquals(10, rejected(bytes, Reason.TRAILING_DATA).offset)
    }

    @Test
    fun duplicatePackagesAreRejectedOnEncodeAndDecode() {
        assertFailsWith<IllegalArgumentException> {
            ModStorageSectionCodec.encode(listOf(ModStorageSection("p"), ModStorageSection("p", mapOf("k" to null))))
        }
        val bytes = raw(sectionCount = 2) { repeat(2) { text("p"); writeInt(0) } }
        assertEquals(17, rejected(bytes, Reason.DUPLICATE_PACKAGE).offset)
    }

    @Test
    fun duplicateKeysCannotOverwriteEarlierValuesOrTombstones() {
        val bytes = raw { text("p"); writeInt(2); text("k"); writeByte(0); text("k"); writeByte(1); writeLong(8) }
        assertEquals(21, rejected(bytes, Reason.DUPLICATE_KEY).offset)
    }

    @Test
    fun noncanonicalPackageAndKeyOrderIsRejectedForLosslessOrphanReencoding() {
        val packages = raw(sectionCount = 2) { text("z"); writeInt(0); text("a"); writeInt(0) }
        val keys = raw { text("p"); writeInt(2); text("z"); writeByte(0); text("a"); writeByte(0) }
        rejected(packages, Reason.NON_CANONICAL_ORDER)
        rejected(keys, Reason.NON_CANONICAL_ORDER)
    }

    @Test
    fun u16LimitsUseEncodedByteLengthsAndAcceptTheExactMaximum() {
        val ascii = "a".repeat(65535)
        val multibyte = "\u00E9".repeat(32767) + "a"
        val section = ModStorageSection(ascii, mapOf(multibyte to ModValue.StrValue(ascii)))
        val bytes = ModStorageSectionCodec.encode(listOf(section))
        assertEquals(listOf(section), ModStorageSectionCodec.decode(bytes, setOf(ascii)))
        for (oversized in listOf("a".repeat(65536), "\u00E9".repeat(32768))) {
            for (invalid in listOf(ModStorageSection(oversized), ModStorageSection("p", mapOf(oversized to null)),
                ModStorageSection("p", mapOf("k" to ModValue.StrValue(oversized))))) {
                assertFailsWith<IllegalArgumentException> { ModStorageSectionCodec.encode(listOf(invalid)) }
            }
        }
    }

    @Test
    fun modelsSnapshotInputsAndExposeNoMutableCollectionsOrByteAliases() {
        val values = linkedMapOf<String, ModValue?>("gone" to null, "n" to ModValue.IntValue(3))
        val section = ModStorageSection("p", values)
        values.clear()
        assertEquals(2, section.entries.size)
        assertFailsWith<UnsupportedOperationException> { (section.entries as MutableMap)["gone"] = ModValue.IntValue(5) }
        val encoded = ModStorageSectionCodec.encode(listOf(section))
        val preserved = encoded.copyOf()
        val known = mutableSetOf("p")
        val decoded = ModStorageSectionCodec.decode(encoded, known)
        known.clear()
        encoded.fill(0)
        assertFalse(decoded.single().orphan)
        assertTrue(decoded.single().isTombstone("gone"))
        assertFailsWith<UnsupportedOperationException> { (decoded as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (decoded.single().entries as MutableMap).clear() }
        assertContentEquals(preserved, ModStorageSectionCodec.encode(decoded))
        assertContentEquals(preserved, ModStorageSectionCodec.encode(listOf(section)))
    }

    @Test
    fun sameKeyInDifferentPackagesNeverSharesState() {
        val input = listOf(ModStorageSection("a", mapOf("k" to ModValue.IntValue(1))), ModStorageSection("b", mapOf("k" to null)))
        val decoded = ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(input), setOf("a", "b"))
        assertEquals(ModValue.IntValue(1), decoded[0].entries["k"])
        assertTrue(decoded[1].isTombstone("k"))
        ModStorageSectionCodec.decode(ModStorageSectionCodec.encode(listOf(ModStorageSection("a"))), emptySet())
        assertEquals(input, decoded)
    }

    private fun rejected(bytes: ByteArray, reason: Reason, known: Set<String> = emptySet()): ModStorageFormatException {
        val error = assertFailsWith<ModStorageFormatException> { ModStorageSectionCodec.decode(bytes, known) }
        assertEquals(reason, error.reason, error.message)
        assertTrue(error.offset in 0..bytes.size, error.message)
        assertTrue(error.message.orEmpty().contains("offset ${error.offset}"))
        return error
    }

    private fun raw(version: Int = 1, sectionCount: Int = 1, sections: DataOutputStream.() -> Unit): ByteArray = body {
        writeInt(0x4D535431)
        writeShort(version)
        writeInt(sectionCount)
        sections()
    }

    private fun body(write: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.write() }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.sized(bytes: ByteArray) {
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.text(value: String) = sized(value.toByteArray(Charsets.UTF_8))

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
