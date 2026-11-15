package com.eratw.port.core.snapshot

import com.eratw.port.core.Version
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * RuntimeSnapshot v1：诊断快照容器（MODERN-R11-R1SNAP S1）。
 *
 * ## 它是什么、不是什么
 *
 * 这是**诊断面**的容器：把「出错那一刻的运行时」压成一个自描述文件，离线分析器
 * （`vmdbg snapshot validate|expand|diff`）不装配会话、不读游戏根就能读它。
 * **不是**存档格式，**不参与**正常存读档，也不进 VM 热路径 —— 只有调用方在触发点
 * （崩溃 / 脏退出 / 调试命令）现采一次。
 *
 * 变量面刻意**复用引擎存档字节**（`SaveCapture` + `SaveCodec`，见
 * [RuntimeSnapshotSections.vars]），不发明第二种变量序列化；分析器展开时读的是同一条编码。
 *
 * ## 容器布局（外层 GZIP 之后）
 *
 * ```
 * "RTSNP1\0"            魔数（7 字节）
 * u32le version         当前 1
 * u32le headerLen       头部字节数（UTF-8 文本，key=value 行）
 * header[headerLen]
 * repeat { u16le nameLen; name[UTF-8]; u32le bodyLen; body[bodyLen] }   节表，长度逐节显式
 * u16le 0               节表终止（nameLen = 0）
 * ```
 *
 * 头部字段：`buildStamp`（构建戳）、`createdAt`（ISO-8601）、`saveIdentity`（存档身份）、
 * `truncated`（是否因上限被截断）、`project.<文件名>`（项目身份：erbc/meta/stmt.idx 的 sha256，
 * 缺失则整条不写）、以及调用方塞进来的未知键（读侧原样保留，前向兼容）。
 *
 * 每节 body 是一串字段：`u16le nameLen; name; u8 tag; value`。tag 见 [Value]：
 * 文本 / 整数 / 布尔 / 文本表 / 整数表 / 字节块。**未知 tag 一律拒收**（宁可报坏档也不猜字节）；
 * **未知节名不拒收**（自描述编码，原样读回来给 `expand` 显示）——这是「未知节跳过」的落地方式。
 *
 * ## 坏档口径
 *
 * 魔数不符 / 版本不符 / gzip 截断 / 任一长度越界 / 字段 tag 未知 / 节表尾部有多余字节
 * 一律抛 [SnapshotFormatException]（消息带偏移与实测值），**绝不**抛 IndexOutOfBounds 之类的
 * 裸运行时异常：分析器据此给 `status=INVALID` 与非零退出码，而不是崩在半个字节上。
 *
 * ## 体积上限
 *
 * [encodeWithin] 先按正常编码；超 [DEFAULT_MAX_BYTES] 时反复「截断当前最大的那一节的
 * 最长字段」（列表砍半、字节块砍半、整数字落单就直接摘掉），并置头部 `truncated=true`。
 * 上限判的是**压缩后**的落盘字节（设备口径 16 MiB，见契约；实测读数属 TODO(verify)）。
 */
object RuntimeSnapshot {

    /** 容器魔数 `RTSNP1\0`（7 字节，不含 C 字符串结尾的额外 0）。 */
    val MAGIC: ByteArray = byteArrayOf(0x52, 0x54, 0x53, 0x4E, 0x50, 0x31, 0x00)

    /** 当前格式版本；读侧只认这一个（版本不符 = 坏档，不做兼容猜测）。 */
    const val VERSION: Int = 1

    /** 单文件上限（压缩后字节）：契约 S3 的 16 MiB。 */
    const val DEFAULT_MAX_BYTES: Long = 16L * 1024 * 1024

    /** 解压后上限：防「小文件炸开成大缓冲」（坏档 / 刻意构造的压缩炸弹）。 */
    const val MAX_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    /** 头部文本上限：正常只有几个短键，超过即是坏档。 */
    const val MAX_HEADER_BYTES: Int = 64 * 1024

    /** 截断轮的次数上限（每轮至少砍掉一半，正常一两轮就够；上限只为防死循环）。 */
    private const val MAX_TRIM_ROUNDS = 24

    /** 单节字段数 / 单列表项数 / 单字符串 / 单字节块的健全性上限（坏档快速判死）。 */
    private const val MAX_FIELD_COUNT = 1_000_000
    private const val MAX_LIST_COUNT = 4_000_000
    private const val MAX_TEXT_BYTES = 48 * 1024 * 1024

    // ------------------------------------------------------------------
    // 值模型
    // ------------------------------------------------------------------

    /** 一个节字段的取值。tag 是按 1:1 映射进容器的类型标记（见 [encode] 的实现）。 */
    sealed interface Value {
        /** UTF-8 文本。 */
        data class Text(val value: String) : Value

        /** 64 位整数。 */
        data class Int64(val value: Long) : Value

        /** 布尔。 */
        data class Bool(val value: Boolean) : Value

        /** 文本表（按序）。 */
        data class TextList(val items: List<String>) : Value

        /** 整数表（按序）。 */
        data class IntList(val items: List<Long>) : Value

        /** 字节块（变量节的存档字节就走它；**不含像素**，像素永不进快照）。 */
        class Blob(val bytes: ByteArray) : Value
    }

    /** 一个分节：节名 + 有序字段表。 */
    data class Section(val name: String, val fields: Map<String, Value>) {
        /** 取文本字段；缺席或类型不符给 null。 */
        fun text(key: String): String? = (fields[key] as? Value.Text)?.value

        /** 取整数字段；缺席或类型不符给 null。 */
        fun long(key: String): Long? = (fields[key] as? Value.Int64)?.value
    }

    /**
     * 头部。
     *
     * @param buildStamp 构建戳（[defaultBuildStamp] 的产物，或宿主注入的 `BuildConfig` 戳）
     * @param createdAt ISO-8601 生成时刻（墙钟，只用于人读与文件名对账）
     * @param saveIdentity 存档身份（槽号与存档字节 sha256 一类；拿不到就空串）
     * @param projectIdentity 项目身份：`程序文件名 -> sha256`（缺失则空表；不许编造）
     * @param extra 未知头部键（读侧原样保留，写侧原样写回；前向兼容面）
     */
    data class Header(
        val buildStamp: String = defaultBuildStamp(),
        val createdAt: String = "",
        val saveIdentity: String = "",
        val projectIdentity: Map<String, String> = emptyMap(),
        val extra: Map<String, String> = emptyMap(),
    )

    /** 一份快照：头部 + 有序分节表 + 截断标记。 */
    data class Snapshot(
        val header: Header,
        val sections: List<Section>,
        val truncated: Boolean = false,
    ) {
        /** 取节；缺席返回 null。 */
        fun section(name: String): Section? = sections.firstOrNull { it.name == name }

        /** 节名表（保持写入序）。 */
        fun sectionNames(): List<String> = sections.map { it.name }
    }

    /** 快照不可解析（CLI 折成 INVALID / 退出码 3，永远不是崩溃）。 */
    class SnapshotFormatException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    /**
     * 编码为落盘字节（外层 GZIP）。**不**施加体积上限 —— 要上限用 [encodeWithin]。
     */
    fun encode(snapshot: Snapshot): ByteArray {
        val body = ByteArrayOutputStream(1 shl 16)
        body.write(MAGIC)
        writeIntLe(body, VERSION)
        val headerBytes = encodeHeader(snapshot.header, snapshot.truncated)
        if (headerBytes.size > MAX_HEADER_BYTES) {
            throw SnapshotFormatException("头部过大（${headerBytes.size} > $MAX_HEADER_BYTES）：项目身份键过多？")
        }
        writeIntLe(body, headerBytes.size)
        body.write(headerBytes)
        for (section in snapshot.sections) {
            val nameBytes = section.name.toByteArray(Charsets.UTF_8)
            if (nameBytes.isEmpty() || nameBytes.size > 0xFFFF) {
                throw SnapshotFormatException("节名长度非法（${nameBytes.size}）: ${section.name}")
            }
            val payload = encodeSection(section)
            writeShortLe(body, nameBytes.size)
            body.write(nameBytes)
            writeIntLe(body, payload.size)
            body.write(payload)
        }
        writeShortLe(body, 0)
        return gzip(body.toByteArray())
    }

    /**
     * 编码并在超过 [maxBytes] 时**截断最长节**直到落盘字节回到上限内（置 `truncated=true`）。
     *
     * 判定的是压缩后大小：每轮重新压一次（单份 ≤16 MiB 的压缩是几十毫秒量级，只在
     * 触发点上跑一次）。极端情况下（头部本身超限 / 所有节都摘光仍超）返回最后一次结果，
     * 不抛、也不无限循环。
     */
    fun encodeWithin(snapshot: Snapshot, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray {
        var current = snapshot
        var bytes = encode(current)
        var round = 0
        while (bytes.size.toLong() > maxBytes && round < MAX_TRIM_ROUNDS) {
            val trimmed = trimOnce(current) ?: break
            current = trimmed.copy(truncated = true)
            bytes = encode(current)
            round++
        }
        return bytes
    }

    /** 解析落盘字节；任何不合规都抛 [SnapshotFormatException]（见类 KDoc 的坏档口径）。 */
    fun decode(bytes: ByteArray): Snapshot {
        val body = gunzip(bytes)
        val reader = BodyReader(body)
        val magic = reader.take(MAGIC.size, "魔数")
        if (!magic.contentEquals(MAGIC)) {
            throw SnapshotFormatException(
                "magic 不是 RTSNP1（实测 ${hexPreview(magic)}；本文件不是 v1 快照）",
            )
        }
        val version = reader.i32("version")
        if (version != VERSION) {
            throw SnapshotFormatException("版本=$version（本读侧只认 $VERSION；用对应版本的读侧或重采）")
        }
        val headerLen = reader.i32("headerLen")
        if (headerLen < 0 || headerLen > MAX_HEADER_BYTES) {
            throw SnapshotFormatException("headerLen=$headerLen（合法区间 0..$MAX_HEADER_BYTES）")
        }
        val headerText = reader.text(headerLen, "头部")
        val (header, truncated) = decodeHeader(headerText)
        val sections = ArrayList<Section>()
        while (true) {
            val nameLen = reader.u16("节表长度")
            if (nameLen == 0) break
            val name = reader.text(nameLen, "节名")
            val bodyLen = reader.i32("节 $name 长度")
            if (bodyLen < 0) throw SnapshotFormatException("节 $name 的长度=$bodyLen（负数非法）")
            val payload = reader.take(bodyLen, "节 $name 正文")
            sections += decodeSection(name, payload)
        }
        reader.expectEnd("节表")
        return Snapshot(header, sections, truncated)
    }

    /** 读文件并解析；文件缺失 / 读失败折成 [SnapshotFormatException]。 */
    fun decodeFile(path: Path): Snapshot = try {
        decode(Files.readAllBytes(path))
    } catch (e: SnapshotFormatException) {
        throw e
    } catch (e: IOException) {
        throw SnapshotFormatException("快照读取失败：${path}（${e.message}）")
    }

    /** 落盘（目录自动建；先写临时文件再替换，避免半份快照被下一次分析器读到）。 */
    fun writeFile(path: Path, snapshot: Snapshot, maxBytes: Long = DEFAULT_MAX_BYTES): Long {
        val bytes = encodeWithin(snapshot, maxBytes)
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return bytes.size.toLong()
    }

    /**
     * 这份字节是不是 v1 快照（**只探魔数**，不解全文）：
     * GZIP 外壳 + 解压后前 7 字节等于 [MAGIC]。任何异常都返回 false（v0 快照照旧走 sidecar 路径）。
     */
    fun isSnapshot(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return false
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val head = ByteArray(MAGIC.size)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) return@use false
                    read += n
                }
                head.contentEquals(MAGIC)
            }
        }.getOrDefault(false)
    }

    /** 默认构建戳：`Version.VERSION` + 可用的编译指纹（`eratw.build.stamp` 系统性质）。 */
    fun defaultBuildStamp(): String {
        val stamp = runCatching { System.getProperty("eratw.build.stamp") }.getOrNull()
        return if (stamp.isNullOrBlank()) Version.VERSION else Version.VERSION + "+" + stamp.trim()
    }

    // ------------------------------------------------------------------
    // 头部
    // ------------------------------------------------------------------

    private const val KEY_BUILD_STAMP = "buildStamp"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_SAVE_IDENTITY = "saveIdentity"
    private const val KEY_TRUNCATED = "truncated"
    private const val PROJECT_PREFIX = "project."

    private fun encodeHeader(header: Header, truncated: Boolean): ByteArray {
        val text = StringBuilder(256)
        text.append(KEY_BUILD_STAMP).append('=').append(escapeHeader(header.buildStamp)).append('\n')
        text.append(KEY_CREATED_AT).append('=').append(escapeHeader(header.createdAt)).append('\n')
        text.append(KEY_SAVE_IDENTITY).append('=').append(escapeHeader(header.saveIdentity)).append('\n')
        text.append(KEY_TRUNCATED).append('=').append(if (truncated) "true" else "false").append('\n')
        for ((file, sha) in header.projectIdentity) {
            text.append(PROJECT_PREFIX).append(escapeHeader(file)).append('=')
                .append(escapeHeader(sha)).append('\n')
        }
        for ((key, value) in header.extra) {
            if (key.isEmpty() || key.contains('=') || key.contains('\n')) continue
            text.append(escapeHeader(key)).append('=').append(escapeHeader(value)).append('\n')
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeHeader(text: String): Pair<Header, Boolean> {
        var buildStamp = ""
        var createdAt = ""
        var saveIdentity = ""
        var truncated = false
        val project = LinkedHashMap<String, String>()
        val extra = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val at = line.indexOf('=')
            if (at <= 0) throw SnapshotFormatException("头部行缺 '='（${line.take(48)}）")
            val key = unescapeHeader(line.substring(0, at))
            val value = unescapeHeader(line.substring(at + 1))
            when {
                key == KEY_BUILD_STAMP -> buildStamp = value
                key == KEY_CREATED_AT -> createdAt = value
                key == KEY_SAVE_IDENTITY -> saveIdentity = value
                key == KEY_TRUNCATED -> truncated = value.equals("true", ignoreCase = true)
                key.startsWith(PROJECT_PREFIX) -> project[key.removePrefix(PROJECT_PREFIX)] = value
                else -> extra[key] = value
            }
        }
        return Header(buildStamp, createdAt, saveIdentity, project, extra) to truncated
    }

    /** 头部文本转义：`\` 与换行（值里出现换行不折断 key=value 行）。 */
    private fun escapeHeader(raw: String): String =
        raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescapeHeader(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val d = raw[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(d)
            }
            i += 2
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // 节
    // ------------------------------------------------------------------

    private const val TAG_TEXT: Byte = 0x53 // 'S'
    private const val TAG_INT: Byte = 0x49 // 'I'
    private const val TAG_BOOL: Byte = 0x42 // 'B'
    private const val TAG_TEXT_LIST: Byte = 0x4C // 'L'
    private const val TAG_INT_LIST: Byte = 0x4E // 'N'
    private const val TAG_BLOB: Byte = 0x52 // 'R'

    private fun encodeSection(section: Section): ByteArray {
        val out = ByteArrayOutputStream(1 shl 10)
        writeIntLe(out, section.fields.size)
        for ((name, value) in section.fields) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            writeShortLe(out, nameBytes.size)
            out.write(nameBytes)
            when (value) {
                is Value.Text -> {
                    out.write(TAG_TEXT.toInt())
                    writeBytes(out, value.value.toByteArray(Charsets.UTF_8))
                }

                is Value.Int64 -> {
                    out.write(TAG_INT.toInt())
                    writeLongLe(out, value.value)
                }

                is Value.Bool -> {
                    out.write(TAG_BOOL.toInt())
                    out.write(if (value.value) 1 else 0)
                }

                is Value.TextList -> {
                    out.write(TAG_TEXT_LIST.toInt())
                    writeIntLe(out, value.items.size)
                    for (item in value.items) writeBytes(out, item.toByteArray(Charsets.UTF_8))
                }

                is Value.IntList -> {
                    out.write(TAG_INT_LIST.toInt())
                    writeIntLe(out, value.items.size)
                    for (item in value.items) writeLongLe(out, item)
                }

                is Value.Blob -> {
                    out.write(TAG_BLOB.toInt())
                    writeBytes(out, value.bytes)
                }
            }
        }
        return out.toByteArray()
    }

    private fun decodeSection(name: String, payload: ByteArray): Section {
        val reader = BodyReader(payload)
        val count = reader.i32("节 $name 的字段数")
        if (count < 0 || count > MAX_FIELD_COUNT) {
            throw SnapshotFormatException("节 $name 的字段数=$count（合法区间 0..$MAX_FIELD_COUNT）")
        }
        val fields = LinkedHashMap<String, Value>(maxOf(4, minOf(count, 64)))
        for (k in 0 until count) {
            val nameLen = reader.u16("节 $name 第 $k 个字段名长度")
            val fieldName = reader.text(nameLen, "节 $name 第 $k 个字段名")
            val tag = reader.u8("节 $name 字段 $fieldName 的类型")
            val value = when (tag.toByte()) {
                TAG_TEXT -> Value.Text(reader.text(reader.i32("字段 $fieldName 文本长度"), "字段 $fieldName 文本"))
                TAG_INT -> Value.Int64(reader.i64("字段 $fieldName"))
                TAG_BOOL -> Value.Bool(reader.u8("字段 $fieldName") != 0)
                TAG_TEXT_LIST -> {
                    val n = readCount(reader, "字段 $fieldName 项数")
                    val items = ArrayList<String>(minOf(n, 1024))
                    for (i in 0 until n) {
                        items += reader.text(reader.i32("字段 $fieldName 第 $i 项长度"), "字段 $fieldName 第 $i 项")
                    }
                    Value.TextList(items)
                }

                TAG_INT_LIST -> {
                    val n = readCount(reader, "字段 $fieldName 项数")
                    val items = ArrayList<Long>(minOf(n, 1024))
                    for (i in 0 until n) items += reader.i64("字段 $fieldName 第 $i 项")
                    Value.IntList(items)
                }

                TAG_BLOB -> Value.Blob(reader.take(reader.i32("字段 $fieldName 字节数"), "字段 $fieldName 字节"))
                else -> throw SnapshotFormatException(
                    "节 $name 字段 $fieldName 的类型标记=0x${Integer.toHexString(tag)} 不可识别（坏档或更新版本）",
                )
            }
            if (fieldName in fields) throw SnapshotFormatException("节 $name 出现重复字段 $fieldName")
            fields[fieldName] = value
        }
        reader.expectEnd("节 $name 正文")
        return Section(name, fields)
    }

    private fun readCount(reader: BodyReader, what: String): Int {
        val n = reader.i32(what)
        if (n < 0 || n > MAX_LIST_COUNT) throw SnapshotFormatException("$what=$n（合法区间 0..$MAX_LIST_COUNT）")
        return n
    }

    // ------------------------------------------------------------------
    // 截断（超上限时砍「最长节的最长字段」）
    // ------------------------------------------------------------------

    /** 一轮截断；返回 null = 已无东西可砍（全部节都已摘光）。 */
    private fun trimOnce(snapshot: Snapshot): Snapshot? {
        if (snapshot.sections.isEmpty()) return null
        var victimIndex = -1
        var victimSize = -1
        for ((index, section) in snapshot.sections.withIndex()) {
            val size = encodeSection(section).size
            if (size > victimSize) {
                victimSize = size
                victimIndex = index
            }
        }
        if (victimIndex < 0) return null
        val victim = snapshot.sections[victimIndex]
        val trimmedFields = trimFields(victim.fields)
        val sections = ArrayList(snapshot.sections)
        if (trimmedFields.isEmpty()) {
            sections.removeAt(victimIndex)
        } else {
            sections[victimIndex] = Section(victim.name, trimmedFields)
        }
        return snapshot.copy(sections = sections)
    }

    /** 砍「最长那个字段」：列表/字节块/文本砍半，单值（整数/布尔）直接摘掉。 */
    private fun trimFields(fields: Map<String, Value>): Map<String, Value> {
        if (fields.isEmpty()) return fields
        var victimKey: String? = null
        var victimSize = -1L
        for ((key, value) in fields) {
            val size = weightOf(value)
            if (size > victimSize) {
                victimSize = size
                victimKey = key
            }
        }
        val key = victimKey ?: return fields
        val trimmed = trimValue(fields.getValue(key))
        val out = LinkedHashMap<String, Value>(fields.size)
        for ((k, v) in fields) {
            if (k == key) {
                if (trimmed != null) out[k] = trimmed
            } else {
                out[k] = v
            }
        }
        return out
    }

    private fun weightOf(value: Value): Long = when (value) {
        is Value.Text -> value.value.length.toLong()
        is Value.Int64 -> 8L
        is Value.Bool -> 1L
        is Value.TextList -> value.items.sumOf { it.length.toLong() + 2L }
        is Value.IntList -> value.items.size.toLong() * 8L
        is Value.Blob -> value.bytes.size.toLong()
    }

    /** 砍半；砍不动（已空/单值）返回 null = 让调用方把这个字段摘掉。 */
    private fun trimValue(value: Value): Value? = when (value) {
        is Value.Text -> {
            val half = value.value.length / 2
            if (half <= 0) null else Value.Text(value.value.substring(0, half) + "…")
        }

        is Value.TextList -> {
            val half = value.items.size / 2
            if (half <= 0) null else Value.TextList(value.items.subList(0, half))
        }

        is Value.IntList -> {
            val half = value.items.size / 2
            if (half <= 0) null else Value.IntList(value.items.subList(0, half))
        }

        is Value.Blob -> {
            val half = value.bytes.size / 2
            if (half <= 0) null else Value.Blob(value.bytes.copyOfRange(0, half))
        }

        is Value.Int64, is Value.Bool -> null
    }

    // ------------------------------------------------------------------
    // 字节工具
    // ------------------------------------------------------------------

    /** 一个带越界检查的游标读取器：所有错误都折成 [SnapshotFormatException]（坏档不崩）。 */
    private class BodyReader(private val bytes: ByteArray) {
        private var at = 0

        private fun remaining(): Int = bytes.size - at

        private fun require(n: Int, what: String) {
            if (n < 0 || remaining() < n) {
                throw SnapshotFormatException("$what 越界（需要 $n 字节，偏移 $at 处只剩 ${remaining()}）")
            }
        }

        fun u8(what: String): Int {
            require(1, what)
            return bytes[at++].toInt() and 0xFF
        }

        fun u16(what: String): Int {
            require(2, what)
            val v = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            at += 2
            return v
        }

        fun i32(what: String): Int {
            require(4, what)
            var v = 0
            for (k in 0 until 4) v = v or ((bytes[at + k].toInt() and 0xFF) shl (8 * k))
            at += 4
            return v
        }

        fun i64(what: String): Long {
            require(8, what)
            var v = 0L
            for (k in 0 until 8) v = v or ((bytes[at + k].toLong() and 0xFFL) shl (8 * k))
            at += 8
            return v
        }

        fun take(n: Int, what: String): ByteArray {
            require(n, what)
            if (n > MAX_TEXT_BYTES) throw SnapshotFormatException("$what 过大（$n > $MAX_TEXT_BYTES）")
            val out = bytes.copyOfRange(at, at + n)
            at += n
            return out
        }

        fun text(n: Int, what: String): String {
            if (n < 0) throw SnapshotFormatException("$what 长度为负（$n）")
            return String(take(n, what), Charsets.UTF_8)
        }

        fun expectEnd(what: String) {
            if (remaining() != 0) throw SnapshotFormatException("$what 之后还有 ${remaining()} 字节多余数据")
        }
    }

    private fun writeIntLe(out: ByteArrayOutputStream, v: Int) {
        for (k in 0 until 4) out.write((v ushr (8 * k)) and 0xFF)
    }

    private fun writeShortLe(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeLongLe(out: ByteArrayOutputStream, v: Long) {
        for (k in 0 until 8) out.write(((v ushr (8 * k)) and 0xFFL).toInt())
    }

    private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeIntLe(out, bytes.size)
        out.write(bytes)
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(maxOf(64, raw.size / 2))
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(minOf(bytes.size.toLong() * 2L, 1L shl 20).toInt().coerceAtLeast(64))
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw SnapshotFormatException("解压后超过上限 $MAX_UNCOMPRESSED_BYTES 字节（可疑压缩炸弹）")
                    }
                    out.write(buf, 0, n)
                }
            }
        } catch (e: SnapshotFormatException) {
            throw e
        } catch (e: IOException) {
            throw SnapshotFormatException("gzip 外壳解不开（截断或不是 GZIP）：${e.message}")
        }
        return out.toByteArray()
    }

    private fun hexPreview(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it) }
}

/**
 * 自动落盘的**开关判式**（契约 S3）：`System.getProperty("eratw.snapshot.autodump")`。
 *
 * 缺省 **false**：只有显式写成 `true`（大小写不敏感）才开。宿主（app）在触发点前读一次；
 * 判式本身放在 core 是为了让「缺省关」这条形状能被定向单测钉死（`RuntimeSnapshotTest`），
 * 而不是只靠装机观察。
 *
 * 关态语义：调用方在判式返回 false 时**不得**产生任何副作用（不落文件、不改既有报告）。
 */
object RuntimeSnapshotAutoDump {

    /** 开关的系统性质名（app 侧唯一读取点）。 */
    const val PROPERTY: String = "eratw.snapshot.autodump"

    /**
     * 开关是否打开。
     *
     * @param raw 性质原文；缺省取 [System.getProperty]（测试可直接传值，不污染进程性质表）
     */
    fun enabled(raw: String? = System.getProperty(PROPERTY)): Boolean =
        raw?.trim()?.equals("true", ignoreCase = true) == true
}
