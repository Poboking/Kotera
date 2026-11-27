package com.eratw.port.core.save

import com.eratw.port.core.patch.DeclaredEntityRef
import com.eratw.port.core.patch.EntityRefScope
import java.io.EOFException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** sidecar v3 的固定声明段：ERF3 + 版本 + 数量 + 有序（包、canonical 键、scope）。 */
object EntityRefDeclarationCodec {
    const val MAGIC: Int = 0x45524633
    const val VERSION: Int = 1
    const val MAX_BYTES: Int = 8 * 1024 * 1024
    const val MAX_ENTRIES: Int = 1 shl 20
    private const val MAX_NAME_BYTES: Int = 1024
    private const val MAX_KEY_BYTES: Int = 512

    fun encode(declarations: Collection<DeclaredEntityRef>): ByteArray {
        require(declarations.size <= MAX_ENTRIES) { "entity-ref 声明数超容量：${declarations.size}" }
        val ordered = declarations.sortedWith(compareBy({ it.packageId }, { it.key }, { it.scope.ordinal }))
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeInt(MAGIC)
        out.writeShort(VERSION)
        out.writeInt(ordered.size)
        var previous: DeclaredEntityRef? = null
        for (declaration in ordered) {
            validate(declaration)
            require(previous == null || compare(previous, declaration) < 0) { "entity-ref 声明重复或未严格排序" }
            previous = declaration
            writeText(out, declaration.packageId, MAX_NAME_BYTES, "packageId")
            writeText(out, declaration.key, MAX_KEY_BYTES, "canonical key")
            out.writeByte(declaration.scope.ordinal)
        }
        out.flush()
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_BYTES) { "entity-ref 声明段超容量：${bytes.size}" }
        return bytes
    }

    fun decode(bytes: ByteArray): List<DeclaredEntityRef> = try {
        decodeValidated(bytes)
    } catch (e: EOFException) {
        throw IllegalArgumentException("entity-ref 声明段被截断", e)
    }

    private fun decodeValidated(bytes: ByteArray): List<DeclaredEntityRef> {
        require(bytes.size in 10..MAX_BYTES) { "entity-ref 声明段长度越界：${bytes.size}" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "entity-ref 声明段魔数不符" }
        require(input.readUnsignedShort() == VERSION) { "entity-ref 声明段版本不支持" }
        val count = input.readInt()
        require(count in 0..MAX_ENTRIES) { "entity-ref 声明数越界：$count" }
        val result = ArrayList<DeclaredEntityRef>(count)
        var previous: DeclaredEntityRef? = null
        repeat(count) {
            val packageId = readText(input, MAX_NAME_BYTES, "packageId")
            val key = readText(input, MAX_KEY_BYTES, "canonical key")
            val code = input.readUnsignedByte()
            require(code < EntityRefScope.entries.size) { "entity-ref scope 编码非法：$code" }
            val declaration = DeclaredEntityRef(packageId, key, EntityRefScope.entries[code])
            validate(declaration)
            require(previous == null || compare(previous, declaration) < 0) {
                "entity-ref 声明重复或排序错误：$packageId/$key"
            }
            previous = declaration
            result += declaration
        }
        require(input.available() == 0) { "entity-ref 声明段尾部多余字节" }
        return result
    }

    private fun validate(declaration: DeclaredEntityRef) {
        require(declaration.packageId.isNotEmpty()) { "entity-ref packageId 为空" }
        require(declaration.key.isNotEmpty() && declaration.key == declaration.key.uppercase(Locale.ROOT)) {
            "entity-ref key 不是 canonical 形：${declaration.key}"
        }
        require(declaration.key.trim() == declaration.key) { "entity-ref key 含首尾空白：${declaration.key}" }
    }

    private fun compare(a: DeclaredEntityRef, b: DeclaredEntityRef): Int {
        val pkg = a.packageId.compareTo(b.packageId)
        if (pkg != 0) return pkg
        val key = a.key.compareTo(b.key)
        return if (key != 0) key else a.scope.ordinal.compareTo(b.scope.ordinal)
    }

    private fun writeText(out: DataOutputStream, text: String, cap: Int, what: String) {
        val bytes = strictEncode(text, what)
        require(bytes.size <= cap) { "$what 超容量：${bytes.size}" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream, cap: Int, what: String): String {
        val length = input.readInt()
        require(length in 1..cap) { "$what 长度越界：$length" }
        require(length <= input.available()) { "$what 长度超出剩余字节：$length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return strictDecode(bytes, what)
    }

    private fun strictEncode(text: String, what: String): ByteArray {
        val encoded = try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text))
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("$what 不是合法 UTF-8 文本", e)
        }
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return bytes
    }

    private fun strictDecode(bytes: ByteArray, what: String): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("$what 不是合法 UTF-8", e)
        }
    }
}
