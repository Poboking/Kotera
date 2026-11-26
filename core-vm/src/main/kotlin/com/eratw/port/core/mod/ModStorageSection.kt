package com.eratw.port.core.mod

import com.eratw.port.modapi.ModValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/**
 * Existing key mapped to null is an ABSENT tombstone, not a missing key.
 *
 * [dataVersion] 是**作者面数据版本**（`EraMod.dataVersion`）在侧车里的落点：装载/迁移成功后由宿主
 * 写进容器，读档时用来判断要不要调 `onMigrate`。缺这个字段的旧档（MST1 v1 字节）解码视作 0。
 */
class ModStorageSection(
    val packageId: String,
    entries: Map<String, ModValue?> = emptyMap(),
    val orphan: Boolean = false,
    val dataVersion: Int = 0,
) {
    init {
        require(dataVersion >= 0) { "dataVersion 不能为负：$dataVersion" }
    }

    val entries: Map<String, ModValue?> = Collections.unmodifiableMap(LinkedHashMap(entries))

    fun isTombstone(key: String): Boolean = entries.containsKey(key) && entries[key] == null

    override fun equals(other: Any?): Boolean = other is ModStorageSection &&
        packageId == other.packageId && entries == other.entries && orphan == other.orphan &&
        dataVersion == other.dataVersion

    override fun hashCode(): Int =
        31 * (31 * (31 * packageId.hashCode() + entries.hashCode()) + orphan.hashCode()) + dataVersion
}

class ModStorageFormatException(
    val reason: Reason,
    val offset: Int,
    detail: String,
) : IllegalArgumentException("$reason at offset $offset: $detail") {
    enum class Reason {
        BAD_MAGIC, UNSUPPORTED_VERSION, TRUNCATED, INVALID_UTF8, INVALID_COUNT,
        UNKNOWN_VALUE_TYPE, NON_CANONICAL_ORDER, DUPLICATE_PACKAGE, DUPLICATE_KEY, TRAILING_DATA,
        INVALID_DATA_VERSION,
    }
}

/**
 * MST1 容器编解码：大端定宽整数 + 无符号 UTF-8 字节序，不做 Unicode 归一化。
 *
 * ## 版本与双读
 *
 * - v1（历史档）：每段 = packageId + 条目表，**没有** dataVersion 字段；
 * - v2（当前写出）：每段末尾追加 i32 dataVersion；写出恒为 v2。
 *
 * 解码同时接受 v1/v2：v1 的段按 `dataVersion = 0` 补齐（=「旧档缺字段视作 0」，于是作者从 0 起
 * 递增 dataVersion 时，老档第一次读会自然触发一次迁移）。v1→v2 的升级只发生在**写出**时，
 * 旧档本身不就地改写。
 */
object ModStorageSectionCodec {
    private const val MAGIC = 0x4D535431

    /** 当前写出版本；v2 = 每段末尾追加 i32 dataVersion。 */
    const val VERSION = 2

    /** 仍在读的旧版本：v1 段不含 dataVersion 字段，解码时补 0。 */
    private const val LEGACY_VERSION = 1

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
                // v2：段尾记录本包数据版本（结构版本），读档迁移判断用；v1 段没有这 4 字节。
                output.writeInt(section.dataVersion)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray, knownPackages: Set<String>): List<ModStorageSection> {
        val input = Reader(bytes)
        if (input.int32("magic") != MAGIC) fail(ModStorageFormatException.Reason.BAD_MAGIC, 0, "Expected MST1")
        val version = input.uint16("version")
        if (version != VERSION && version != LEGACY_VERSION) {
            fail(ModStorageFormatException.Reason.UNSUPPORTED_VERSION, 4, "Unsupported version $version")
        }
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
            val dataVersion = if (version < VERSION) {
                0
            } else {
                val dataVersionOffset = input.offset
                val value = input.int32("data version")
                if (value < 0) {
                    fail(
                        ModStorageFormatException.Reason.INVALID_DATA_VERSION,
                        dataVersionOffset,
                        "Negative data version $value",
                    )
                }
                value
            }
            sections += ModStorageSection(packageId, entries, packageId !in knownPackages, dataVersion)
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

    /**
     * 写入侧入档闸（M2-BUG-04）：[text] 必须能被 MST1 段格式编码 —— 合法 UTF-16（严格 UTF-8
     * 编码器可编码，孤立代理对拒）且 UTF-8 字节数 ≤ 0xFFFF（u16 长度前缀上限）。判据单源 =
     * [utf8] 本体（同一编码器、同一上限，不另抄一份判据）。
     *
     * 为什么判据必须前移到写入入口：容器写入口（[ModStorageStore] 的作者面 `put` / 宿主面
     * `putReservedKey`）在**落写之前**调本闸，使容器里每段「按构造可编码」—— 存档抓取
     * （ModSidecarStoragePort.captureChunks 逐段 [encode]）与足迹诊断（ModStorageFootprint）
     * 的编码从此不可能因作者数据抛 IAE。修复前该判据只在编码期兑现：超限/非法文本先落写、
     * 再经诊断旁路抛 IAE（写已落地却报错 + 毒条目留在段里令本包后续每次 put 连锁抛 +
     * 存档抓取整链失败被 saveData 的 catch(Throwable) 折成存档失败）。
     *
     * 解码方向天然满足本闸（u16 前缀定界 + 严格 UTF-8 解码拒绝），无需复查；包装后仍带
     * [utf8] 的原始判据文本（超限字节数 / Invalid UTF-16），作者可据此定位。
     */
    internal fun requireEncodableText(label: String, text: String) {
        try {
            utf8(text, label)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "$label 无法编入 MST1 段格式（须为合法 UTF-16 且 UTF-8 编码后不超过 65535 字节）：${error.message}",
                error,
            )
        }
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
