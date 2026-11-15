package com.eratw.port.core.save

import com.eratw.port.core.vars.CharIdentity
import com.eratw.port.core.vars.CsvValue
import com.eratw.port.core.vars.CsvValueSlot
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.ModCsvValueKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer

/** Mod CSV 段/包装损坏。宿主的恢复路径按 fail-closed 上警告（不改变 .sav 语义）。 */
class ModCsvFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** 分片载荷拆包结果：[storage] 是原 `MST1` 存储字节，[csv] 是本包的 CSV 注入值。 */
class ModCsvChunkPayload(val storage: ByteArray, val csv: ModCsvValueBag)

/**
 * Mod 注入 CSV 值的 `.rt2` 编码（值只进**激活包**自己的分片，不碰 `.sav`）。
 *
 * 分片包装（大端）：
 * ```
 * i32 魔数 "R2PK" = 0x5232504B
 * u16 版本 = 1
 * i32 + bytes  MST1 存储载荷（原 ModStorageSectionCodec.encode 字节）
 * i32 + bytes  CSV 值段
 * ```
 * **空 CSV 段不包装**：[encode] 原样返回 storage 字节 ⇒ 无 Mod CSV 值的档与改动前逐字节一致；
 * [decode] 见前四字节不是 "R2PK" 就当旧裸 `MST1` 载荷（csv 空）。这样旧档、纯 Base 档、
 * 没装注入位计划的会话都不产生任何字节变化。
 */
object ModCsvValueCodec {

    /** 分片包装：CSV 段空 ⇒ 原样返回 [storage]（不改旧档/无值档的字节）。 */
    fun encode(storage: ByteArray, csv: ModCsvValueBag): ByteArray {
        if (csv.isEmpty) return storage
        val csvBytes = encodeCsv(csv)
        val buffer = ByteArrayOutputStream(storage.size + csvBytes.size + PACKAGE_HEADER_BYTES)
        DataOutputStream(buffer).use { out ->
            out.writeInt(PACKAGE_MAGIC)
            out.writeShort(VERSION)
            out.writeInt(storage.size)
            out.write(storage)
            out.writeInt(csvBytes.size)
            out.write(csvBytes)
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): ModCsvChunkPayload = try {
        if (hasPackageMagic(bytes)) decodePackage(bytes) else ModCsvChunkPayload(bytes, ModCsvValueBag.EMPTY)
    } catch (e: EOFException) {
        throw ModCsvFormatException("Mod CSV 分片字节不足: ${e.message}", e)
    }

    fun encodeCsv(bag: ModCsvValueBag): ByteArray = CsvSegmentCodec.encode(bag)

    fun decodeCsv(bytes: ByteArray): ModCsvValueBag = CsvSegmentCodec.decode(bytes)

    /**
     * 只并**已激活包**的 CSV 值：孤儿片（未激活包）的载荷原样透传，它的值不写回变量池
     * （否则「禁用 school.mod 读存再启用」会拿到旧值覆盖当前档）。
     */
    fun mergeApplied(chunks: List<PackageChunk>, appliedPackages: Set<String>): ModCsvValueBag {
        val pairs = ArrayList<Pair<ModCsvValueKey, CsvValue>>()
        for (chunk in chunks) {
            if (chunk.packageId !in appliedPackages) continue
            for ((slot, value) in decode(chunk.payload).csv.entries) {
                pairs += slot.toKey() to value
            }
        }
        return ModCsvValueBag.of(pairs)
    }
}

// ----------------------------------------------------------------------
// 常量
// ----------------------------------------------------------------------

private const val PACKAGE_MAGIC = 0x5232504B
private const val CSV_MAGIC = 0x52324356
private const val VERSION = 1
private const val KIND_CSV_NO = 0
private const val KIND_ENTITY = 1
private const val VALUE_INT = 0
private const val VALUE_STR = 1
private const val MAGIC_BYTES = 4
private const val MAX_TEXT_BYTES = 0xFFFF

/** 包装头长度：魔数 4 + 版本 2 + storage 长度 4 + csv 长度 4。 */
private const val PACKAGE_HEADER_BYTES = 14

// ----------------------------------------------------------------------
// 分片包装
// ----------------------------------------------------------------------

private fun hasPackageMagic(bytes: ByteArray): Boolean =
    bytes.size >= MAGIC_BYTES && ByteBuffer.wrap(bytes, 0, MAGIC_BYTES).int == PACKAGE_MAGIC

private fun decodePackage(bytes: ByteArray): ModCsvChunkPayload {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    input.readInt()
    requireVersion(input.readUnsignedShort())
    val storage = readBlob(input, "存储载荷")
    val csv = ModCsvValueCodec.decodeCsv(readBlob(input, "CSV 值段"))
    if (input.available() != 0) throw ModCsvFormatException("Mod CSV 分片尾部多余 ${input.available()} 字节")
    return ModCsvChunkPayload(storage, csv)
}

private fun readBlob(input: DataInputStream, label: String): ByteArray {
    val length = input.readInt()
    if (length < 0 || length > input.available()) {
        throw ModCsvFormatException("Mod CSV $label 长度越界: $length（剩余 ${input.available()}）")
    }
    return ByteArray(length).also { input.readFully(it) }
}

private fun requireVersion(version: Int) {
    if (version != VERSION) throw ModCsvFormatException("Mod CSV 版本 $version 不支持（本引擎 v$VERSION）")
}

// ----------------------------------------------------------------------
// CSV 值段
// ----------------------------------------------------------------------

/**
 * CSV 值段（大端）：
 * ```
 * i32 魔数 "R2CV" = 0x52324356
 * u16 版本 = 1
 * i32 条目数
 *   每条：u16+pkg / u16+tableId / u16+keyword（UTF-8）
 *         u8 identityKind（0 = CsvNo int；1 = EntityId long）+ 身份值
 *         u8 valueKind（0 = int long；1 = str u16+UTF-8）+ 值
 * ```
 * 条目按「包 / 表 / 关键字 / 身份形态 / 身份值」排序落盘（字节可复现，不依赖 HashMap 序）。
 */
private object CsvSegmentCodec {

    fun encode(bag: ModCsvValueBag): ByteArray {
        fun kind(identity: CharIdentity): Int = if (identity is CharIdentity.CsvNo) KIND_CSV_NO else KIND_ENTITY
        fun sortValue(identity: CharIdentity): Long = when (identity) {
            is CharIdentity.CsvNo -> identity.no.toLong()
            is CharIdentity.Entity -> identity.entityId
        }
        val ordered = bag.entries.entries
            .map { it.key to it.value }
            .sortedWith { a, b ->
                compareValuesBy(
                    a.first, b.first,
                    { it.packageId },
                    { it.tableId },
                    { it.keyword },
                    { kind(it.identity) },
                    { sortValue(it.identity) },
                )
            }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(CSV_MAGIC)
            out.writeShort(VERSION)
            out.writeInt(ordered.size)
            for ((slot, value) in ordered) writeEntry(out, slot, value)
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): ModCsvValueBag = try {
        decodeBody(bytes)
    } catch (e: EOFException) {
        throw ModCsvFormatException("Mod CSV 值段字节不足: ${e.message}", e)
    }

    private fun decodeBody(bytes: ByteArray): ModCsvValueBag {
        if (bytes.size < MAGIC_BYTES) throw ModCsvFormatException("Mod CSV 值段长度不足: ${bytes.size}")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val count = readHeader(input)
        val slots = LinkedHashMap<CsvValueSlot, CsvValue>(count * 2)
        repeat(count) {
            val entry = readEntry(input)
            slots[entry.first] = entry.second
        }
        if (input.available() != 0) throw ModCsvFormatException("Mod CSV 值段尾部多余 ${input.available()} 字节")
        return ModCsvValueBag.ofSlots(slots)
    }

    private fun readHeader(input: DataInputStream): Int {
        if (input.readInt() != CSV_MAGIC) throw ModCsvFormatException("Mod CSV 值段魔数不符")
        requireVersion(input.readUnsignedShort())
        val count = input.readInt()
        if (count < 0 || count > input.available()) {
            throw ModCsvFormatException("Mod CSV 值段条目数非法: $count（剩余 ${input.available()}）")
        }
        return count
    }

    private fun writeEntry(out: DataOutputStream, slot: CsvValueSlot, value: CsvValue) {
        writeText(out, slot.packageId)
        writeText(out, slot.tableId)
        writeText(out, slot.keyword)
        when (val identity = slot.identity) {
            is CharIdentity.CsvNo -> {
                out.writeByte(KIND_CSV_NO)
                out.writeInt(identity.no)
            }
            is CharIdentity.Entity -> {
                out.writeByte(KIND_ENTITY)
                out.writeLong(identity.entityId)
            }
        }
        when (value) {
            is CsvValue.IntValue -> {
                out.writeByte(VALUE_INT)
                out.writeLong(value.v)
            }
            is CsvValue.StrValue -> {
                out.writeByte(VALUE_STR)
                writeText(out, value.v)
            }
        }
    }

    private fun readEntry(input: DataInputStream): Pair<CsvValueSlot, CsvValue> {
        val packageId = readText(input)
        val tableId = readText(input)
        val keyword = readText(input)
        val identity = readIdentity(input)
        return CsvValueSlot(packageId, tableId, keyword, identity) to readValue(input)
    }

    private fun readIdentity(input: DataInputStream): CharIdentity =
        when (val kind = input.readUnsignedByte()) {
            KIND_CSV_NO -> {
                val no = input.readInt()
                if (no <= 0) throw ModCsvFormatException("Mod CSV 值段的 CSV 番号非法: $no")
                CharIdentity.CsvNo(no)
            }
            KIND_ENTITY -> CharIdentity.Entity(input.readLong())
            else -> throw ModCsvFormatException("Mod CSV 值段身份形态未知: $kind")
        }

    private fun readValue(input: DataInputStream): CsvValue =
        when (val kind = input.readUnsignedByte()) {
            VALUE_INT -> CsvValue.IntValue(input.readLong())
            VALUE_STR -> CsvValue.StrValue(readText(input))
            else -> throw ModCsvFormatException("Mod CSV 值段值形态未知: $kind")
        }

    private fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) throw ModCsvFormatException("Mod CSV 文本超 u16 长度上限: ${bytes.size}")
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
