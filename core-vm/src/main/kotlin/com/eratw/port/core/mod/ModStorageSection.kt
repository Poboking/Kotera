package com.eratw.port.core.mod

import com.eratw.port.modapi.ModValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** An existing key mapped to null is an ABSENT tombstone, not a missing key. */
class ModStorageSection(
    val packageId: String,
    entries: Map<String, ModValue?> = emptyMap(),
    val orphan: Boolean = false,
) {
    val entries: Map<String, ModValue?> = Collections.unmodifiableMap(LinkedHashMap(entries))

    fun isTombstone(key: String): Boolean = entries.containsKey(key) && entries[key] == null

    override fun equals(other: Any?): Boolean = other is ModStorageSection &&
        packageId == other.packageId && entries == other.entries && orphan == other.orphan

    override fun hashCode(): Int = 31 * (31 * packageId.hashCode() + entries.hashCode()) + orphan.hashCode()
}

class ModStorageFormatException(
    val reason: Reason,
    val offset: Int,
    detail: String,
) : IllegalArgumentException("$reason at offset $offset: $detail") {
    enum class Reason {
        BAD_MAGIC, UNSUPPORTED_VERSION, TRUNCATED, INVALID_UTF8, INVALID_COUNT,
        UNKNOWN_VALUE_TYPE, NON_CANONICAL_ORDER, DUPLICATE_PACKAGE, DUPLICATE_KEY, TRAILING_DATA,
    }
}

/** MST1 uses big-endian fixed-width integers and unsigned UTF-8 byte ordering, without Unicode normalization. */
object ModStorageSectionCodec {
    private const val MAGIC = 0x4D535431
    const val VERSION = 1

    fun encode(sections: Collection<ModStorageSection>): ByteArray {
        val ordered = sections.map { utf8(it.packageId, "packageId") to it }
            .sortedWith { a, b -> compareBytes(a.first, b.first) }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeShort(VERSION)
            output.writeInt(ordered.size)
            var previous: ByteArray? = null
            for ((packageBytes, section) in ordered) {
                require(previous == null || compareBytes(previous, packageBytes) != 0) { "Duplicate packageId: ${section.packageId}" }
                previous = packageBytes
                output.writeShort(packageBytes.size)
                output.write(packageBytes)
                output.writeInt(section.entries.size)
                val entries = section.entries.map { utf8(it.key, "key") to it.value }
                    .sortedWith { a, b -> compareBytes(a.first, b.first) }
                for ((keyBytes, value) in entries) {
                    output.writeShort(keyBytes.size)
                    output.write(keyBytes)
                    when (value) {
                        null -> output.writeByte(0)
                        is ModValue.IntValue -> {
                            output.writeByte(1)
                            output.writeLong(value.value)
                        }
                        is ModValue.StrValue -> {
                            output.writeByte(2)
                            val text = utf8(value.value, "string value")
                            output.writeShort(text.size)
                            output.write(text)
                        }
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray, knownPackages: Set<String>): List<ModStorageSection> {
        val input = Reader(bytes)
        if (input.int32("magic") != MAGIC) fail(ModStorageFormatException.Reason.BAD_MAGIC, 0, "Expected MST1")
        val version = input.uint16("version")
        if (version != VERSION) fail(ModStorageFormatException.Reason.UNSUPPORTED_VERSION, 4, "Unsupported version $version")
        val count = input.int32("section count")
        if (count < 0) fail(ModStorageFormatException.Reason.INVALID_COUNT, 6, "Negative section count $count")
        input.requireCount(count.toLong(), 6, "sections")
        val sections = mutableListOf<ModStorageSection>()
        var previousPackage: ByteArray? = null
        repeat(count) {
            val packageOffset = input.offset
            val (packageId, packageBytes) = input.text("packageId")
            checkOrder(previousPackage, packageBytes, packageOffset, ModStorageFormatException.Reason.DUPLICATE_PACKAGE)
            previousPackage = packageBytes
            val entryCount = input.int32("key count").toLong() and 0xFFFFFFFFL
            input.requireCount(entryCount, 3, "entries")
            val entries = linkedMapOf<String, ModValue?>()
            var previousKey: ByteArray? = null
            repeat(entryCount.toInt()) {
                val keyOffset = input.offset
                val (key, keyBytes) = input.text("key")
                checkOrder(previousKey, keyBytes, keyOffset, ModStorageFormatException.Reason.DUPLICATE_KEY)
                previousKey = keyBytes
                val typeOffset = input.offset
                val value = when (val type = input.uint8("value type")) {
                    0 -> null
                    1 -> ModValue.IntValue(input.int64("LONG value"))
                    2 -> ModValue.StrValue(input.text("STR value").first)
                    else -> fail(ModStorageFormatException.Reason.UNKNOWN_VALUE_TYPE, typeOffset, "Unknown value type $type")
                }
                entries[key] = value
            }
            sections += ModStorageSection(packageId, entries, packageId !in knownPackages)
        }
        if (input.remaining != 0) fail(ModStorageFormatException.Reason.TRAILING_DATA, input.offset, "Trailing ${input.remaining} bytes")
        return Collections.unmodifiableList(sections)
    }

    private fun utf8(text: String, label: String): ByteArray {
        val encoded = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-16 in $label", error)
        }
        require(encoded.remaining() <= 0xFFFF) { "$label exceeds u16 UTF-8 length: ${encoded.remaining()}" }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val difference = (a[index].toInt() and 0xFF).compareTo(b[index].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return a.size.compareTo(b.size)
    }

    // Reject noncanonical input so re-encoding orphan sections preserves every accepted byte.
    private fun checkOrder(previous: ByteArray?, current: ByteArray, offset: Int, duplicate: ModStorageFormatException.Reason) {
        if (previous == null) return
        val comparison = compareBytes(previous, current)
        if (comparison == 0) fail(duplicate, offset, "Duplicate identity")
        if (comparison > 0) fail(ModStorageFormatException.Reason.NON_CANONICAL_ORDER, offset, "Expected ascending UTF-8 byte order")
    }

    private fun fail(reason: ModStorageFormatException.Reason, offset: Int, detail: String): Nothing =
        throw ModStorageFormatException(reason, offset, detail)

    private class Reader(private val bytes: ByteArray) {
        var offset = 0
            private set
        val remaining: Int get() = bytes.size - offset

        fun requireCount(count: Long, minimumSize: Int, label: String) {
            if (count > remaining.toLong() / minimumSize) {
                fail(ModStorageFormatException.Reason.TRUNCATED, offset, "$count $label cannot fit in $remaining bytes")
            }
        }

        private fun requireBytes(size: Int, label: String) {
            if (size > remaining) fail(ModStorageFormatException.Reason.TRUNCATED, offset, "$label needs $size bytes; $remaining remain")
        }

        fun uint8(label: String): Int {
            requireBytes(1, label)
            return bytes[offset++].toInt() and 0xFF
        }

        fun uint16(label: String): Int {
            requireBytes(2, label)
            return (uint8(label) shl 8) or uint8(label)
        }

        fun int32(label: String): Int {
            requireBytes(4, label)
            val value = ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
            return value
        }

        fun int64(label: String): Long {
            requireBytes(8, label)
            val value = ByteBuffer.wrap(bytes, offset, 8).long
            offset += 8
            return value
        }

        fun text(label: String): Pair<String, ByteArray> {
            val size = uint16("$label length")
            requireBytes(size, label)
            val start = offset
            val buffer = ByteBuffer.wrap(bytes, offset, size)
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer).toString()
            } catch (error: CharacterCodingException) {
                fail(ModStorageFormatException.Reason.INVALID_UTF8, buffer.position(), "Invalid UTF-8 in $label")
            }
            offset += size
            return text to bytes.copyOfRange(start, offset)
        }
    }
}
