package com.eratw.port.core.mod

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Program
import com.eratw.port.core.proc.FuncMeta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * RT2 G2 波（P1-5 余量二）：ERB Mod 模块容器 `.erbm` v1 的读写。
 *
 * ## 为什么另开一个容器
 *
 * `core-vm/.../patch/ModuleContainer.kt` 的 `ModuleContainerCodec`（magic `ERBM`）是
 * **M2-4 legacy `.patch` 转码**的独占模型：零基线四池 + imports + callTargets +
 * relocations + exports，不携带 `Program` / `FuncMeta` / 签名表，与 `LinkableModule`
 * 的输入形态（`Program + List<FuncMeta> + signatureHashOf`）不同形。本波不碰那个面。
 *
 * 本容器只做「直存」：`Program` 复用 `ErbcWriter.encode` / `ErbcReader.decode` 的
 * 既有字节格式（不重造序列化），外面套一层长度前缀记录区放 packageId、编译档位、
 * `FuncMeta` 全字段与签名指纹表。解码产物可直接转成 [CompiledModule] 喂
 * `ModChainAssembler.assemble`。
 *
 * ## magic 刻意不叫 `ERBM`
 *
 * `ModuleContainerCodec.decode` 读**前 4 字节**判 role（`ERBM` → MODULE / `ERBC` →
 * LINKED_PROGRAM）。本波 magic 取 `ERTM1`（前 4 字节 `ERTM`），避免同名前缀被误判成
 * legacy MODULE 容器后按四池格式解出垃圾。扩展名沿用 `.erbm`（G 车第一批
 * `ModChainAssembly.ERB_MODULE_EXT` 已钉），magic 与扩展名不同名是防解码歧义的刻意设计。
 *
 * ## fail-closed
 *
 * 写侧先校验再写（零基线、metas 与函数表同序同长、稠密 0 基下标、签名表键归一），
 * 读侧先核尾部 SHA-256 摘要再解析，任一坏档形态（坏 magic / 坏版本 / 未知档位 /
 * 越界长度 / 摘要不符 / 尾部多余字节）一律抛 `IllegalArgumentException` 整包拒，
 * 不返回半成品、不静默跳过坏记录。容器是 RT2 自研（原版 Emuera 无 Mod 代码容器可比），
 * `TODO(verify)`：语义权威 = 蓝图 §16.1 / §10 / §12.1 与 `LinkableModule` 契约。
 */
object ErbModuleContainer {

    /** 5 字节 ASCII；前 4 字节刻意不等于 legacy `ERBM` / `ERBC`。 */
    const val MAGIC: String = "ERTM1"

    const val VERSION: Int = 1

    /** 编译档位位：Mod 包编译档（`allowSuperCalls=true`）。 */
    const val FLAG_ALLOW_SUPER_CALLS: Int = 0x1

    private const val KNOWN_FLAGS = FLAG_ALLOW_SUPER_CALLS
    private const val DIGEST_BYTES = 32

    /** 头部固定字段：version + flags 两个 unsigned short。 */
    private const val HEADER_FIXED_BYTES = 4

    /** 档位报错文案的十六进制基数。 */
    private const val HEX_RADIX = 16
    private const val MAX_CONTAINER_BYTES = 512 * 1024 * 1024
    private const val MAX_PROGRAM_BYTES = 256 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_FUNCTIONS = 1 shl 20
    private const val MAX_SIGNATURES = 1 shl 20
    private const val MAX_PACKAGE_ID_BYTES = 256

    // FuncMeta 八个 Boolean 的位序；与 writeMeta / readMeta 一一对应。
    private const val B_EVENT = 0x1
    private const val B_METHOD = 0x2
    private const val B_ONLY = 0x4
    private const val B_PRI = 0x8
    private const val B_LATER = 0x10
    private const val B_SINGLE = 0x20
    private const val B_RETURNS_STR = 0x40
    private const val B_RETURNS_FLOAT = 0x80

    /** 容器解码产物。[toCompiledModule] 是装配器的直接输入。 */
    class ErbModule(
        val packageId: String,
        val allowSuperCalls: Boolean,
        val program: Program,
        val functions: List<FuncMeta>,
        val signatures: Map<String, String>,
    ) {
        fun toCompiledModule(): CompiledModule =
            CompiledModule(packageId, program, functions, signatures)
    }

    /** 生产写入：先校验模型，再序列化，末尾附全量 SHA-256 摘要。 */
    fun encode(module: ErbModule): ByteArray {
        validateForWrite(module)
        val body = ByteArrayOutputStream().use { buf ->
            val out = DataOutputStream(buf)
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.writeShort(VERSION)
            out.writeShort(if (module.allowSuperCalls) FLAG_ALLOW_SUPER_CALLS else 0)
            writeString(out, module.packageId)
            val programBytes = ErbcWriter.encode(module.program)
            out.writeInt(programBytes.size)
            out.write(programBytes)
            out.writeInt(module.functions.size)
            for (meta in module.functions) writeMeta(out, meta)
            out.writeInt(module.signatures.size)
            for ((key, hash) in module.signatures) {
                writeString(out, key)
                writeString(out, hash)
            }
            out.flush()
            buf.toByteArray()
        }
        val digest = sha256(body)
        return body + digest
    }

    /** 便捷写入：由 [CompiledModule] + 编译档位构造模型。 */
    fun encode(module: CompiledModule, allowSuperCalls: Boolean): ByteArray =
        encode(ErbModule(module.packageId, allowSuperCalls, module.program, module.functions, module.signatures))

    /** 生产读取：摘要 → 头部 → 记录区，全套读侧 fail-closed 校验。 */
    fun decode(bytes: ByteArray): ErbModule {
        require(bytes.size >= MAGIC.length + HEADER_FIXED_BYTES + DIGEST_BYTES) {
            ".erbm 容器长度不足（${bytes.size} 字节）—— 整包拒绝"
        }
        require(bytes.size <= MAX_CONTAINER_BYTES) {
            ".erbm 容器超过上界 $MAX_CONTAINER_BYTES 字节（实为 ${bytes.size}）—— 整包拒绝"
        }
        val bodyLen = bytes.size - DIGEST_BYTES
        val expected = bytes.copyOfRange(bodyLen, bytes.size)
        val actual = sha256(bytes.copyOfRange(0, bodyLen))
        require(MessageDigest.isEqual(expected, actual)) {
            ".erbm 容器 SHA-256 摘要不符（传输损坏 / 被单独替换过）—— 整包拒绝"
        }
        val inStream = DataInputStream(ByteArrayInputStream(bytes, 0, bodyLen))
        val magic = ByteArray(MAGIC.length).also { inStream.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == MAGIC) {
            ".erbm magic 不符（期望 $MAGIC）—— 整包拒绝；注意 legacy `ERBM` 属 M2-4 转码面"
        }
        val version = inStream.readUnsignedShort()
        require(version == VERSION) { ".erbm 版本不符（期望 $VERSION，实为 $version）—— 整包拒绝" }
        val flags = inStream.readUnsignedShort()
        require(flags and KNOWN_FLAGS.inv() == 0) {
            ".erbm 含未知档位位 0x${flags.toString(HEX_RADIX)} —— 整包拒绝"
        }
        val packageId = readString(inStream)
        val programLen = readCount(inStream, MAX_PROGRAM_BYTES, "program 字节数")
        val programBytes = ByteArray(programLen).also { inStream.readFully(it) }
        val program = ErbcReader.decode(programBytes, allowImportCalls = true)
        require(isZeroBase(program)) { ".erbm 内的 Program 不是零基线形态 —— 整包拒绝" }

        val functionCount = readCount(inStream, MAX_FUNCTIONS, "FuncMeta 条数")
        val functions = ArrayList<FuncMeta>(functionCount)
        repeat(functionCount) { functions += readMeta(inStream) }
        require(functions.size == program.functions.size) {
            ".erbm 的 FuncMeta（${functions.size} 条）与 Program 函数表" +
                "（${program.functions.size} 条）不同长 —— 整包拒绝"
        }
        for (i in functions.indices) {
            require(functions[i].index == i) {
                ".erbm 的 functions[$i].index=${functions[i].index} 不是稠密 0 基下标 —— 整包拒绝"
            }
        }

        val signatureCount = readCount(inStream, MAX_SIGNATURES, "签名条数")
        val signatures = LinkedHashMap<String, String>(signatureCount * 2)
        repeat(signatureCount) {
            val key = readString(inStream)
            val hash = readString(inStream)
            require(key.isNotEmpty() && hash.isNotEmpty()) { ".erbm 签名记录的键或指纹为空 —— 整包拒绝" }
            val norm = key.uppercase(Locale.ROOT)
            require(signatures.put(norm, hash) == null) {
                ".erbm 签名键重复（$norm）—— 整包拒绝"
            }
        }
        require(inStream.available() == 0) { ".erbm 记录区之后有多余字节 —— 整包拒绝" }
        return ErbModule(packageId, flags and FLAG_ALLOW_SUPER_CALLS != 0, program, functions, signatures)
    }

    // ------------------------------------------------------------------
    // 写侧校验
    // ------------------------------------------------------------------

    private fun validateForWrite(module: ErbModule) {
        val pkg = module.packageId
        require(pkg.isNotBlank()) { ".erbm 的 packageId 不得为空 —— 整包拒绝" }
        require(pkg.toByteArray(Charsets.UTF_8).size <= MAX_PACKAGE_ID_BYTES) {
            ".erbm 的 packageId 超过 $MAX_PACKAGE_ID_BYTES 字节 —— 整包拒绝"
        }
        require(isZeroBase(module.program)) {
            "模块 [$pkg] 的 Program 不是零基线形态 —— 链接器只收零基线 Module，整包拒绝"
        }
        require(module.functions.size == module.program.functions.size) {
            "模块 [$pkg] 的 FuncMeta（${module.functions.size} 条）与 Program 函数表" +
                "（${module.program.functions.size} 条）不同长 —— 整包拒绝"
        }
        for (i in module.functions.indices) {
            val m = module.functions[i]
            require(m.index == i) {
                "模块 [$pkg] 的 functions[$i].index=${m.index} 不是稠密 0 基下标 —— 整包拒绝"
            }
            require(m.key.isNotEmpty()) { "模块 [$pkg] 的 functions[$i].key 为空 —— 整包拒绝" }
        }
        for ((key, hash) in module.signatures) {
            require(key.isNotEmpty() && hash.isNotEmpty()) {
                "模块 [$pkg] 的签名记录键或指纹为空 —— 整包拒绝"
            }
            require(key == key.uppercase(Locale.ROOT)) {
                "模块 [$pkg] 的签名键 [$key] 未大写归一 —— 整包拒绝"
            }
        }
    }

    private fun isZeroBase(p: Program): Boolean =
        p.codeBase == 0 && p.statementBase == 0 && p.stringBase == 0 &&
            p.longBase == 0 && p.exprBase == 0 && p.functionBase == 0

    // ------------------------------------------------------------------
    // 记录编码
    // ------------------------------------------------------------------

    private fun writeMeta(out: DataOutputStream, m: FuncMeta) {
        out.writeInt(m.index)
        writeString(out, m.key)
        writeString(out, m.name)
        var bits = 0
        if (m.isEvent) bits = bits or B_EVENT
        if (m.isMethod) bits = bits or B_METHOD
        if (m.isOnly) bits = bits or B_ONLY
        if (m.isPri) bits = bits or B_PRI
        if (m.isLater) bits = bits or B_LATER
        if (m.isSingle) bits = bits or B_SINGLE
        if (m.returnsStr) bits = bits or B_RETURNS_STR
        if (m.returnsFloat) bits = bits or B_RETURNS_FLOAT
        out.writeShort(bits)
        out.writeInt(m.argIntCount)
        out.writeInt(m.argStrCount)
        out.writeInt(m.localSize)
        out.writeInt(m.localsSize)
        out.writeInt(m.floatLocalSize)
        out.writeInt(m.defIndex)
        writeString(out, m.position)
    }

    private fun readMeta(inStream: DataInputStream): FuncMeta {
        val index = inStream.readInt()
        require(index >= 0) { ".erbm 的 FuncMeta.index 为负（$index）—— 整包拒绝" }
        val key = readString(inStream)
        val name = readString(inStream)
        val bits = inStream.readUnsignedShort()
        require(bits and 0xFF.inv() == 0) { ".erbm 的 FuncMeta 含未知标志位 —— 整包拒绝" }
        val argIntCount = readCount(inStream, Int.MAX_VALUE, "argIntCount")
        val argStrCount = readCount(inStream, Int.MAX_VALUE, "argStrCount")
        val localSize = readCount(inStream, Int.MAX_VALUE, "localSize")
        val localsSize = readCount(inStream, Int.MAX_VALUE, "localsSize")
        val floatLocalSize = readCount(inStream, Int.MAX_VALUE, "floatLocalSize")
        val defIndex = inStream.readInt()
        val position = readString(inStream)
        require(key.isNotEmpty()) { ".erbm 的 FuncMeta.key 为空 —— 整包拒绝" }
        return FuncMeta(
            index = index,
            key = key,
            name = name,
            isEvent = bits and B_EVENT != 0,
            isMethod = bits and B_METHOD != 0,
            isOnly = bits and B_ONLY != 0,
            isPri = bits and B_PRI != 0,
            isLater = bits and B_LATER != 0,
            isSingle = bits and B_SINGLE != 0,
            argIntCount = argIntCount,
            argStrCount = argStrCount,
            localSize = localSize,
            localsSize = localsSize,
            floatLocalSize = floatLocalSize,
            returnsStr = bits and B_RETURNS_STR != 0,
            returnsFloat = bits and B_RETURNS_FLOAT != 0,
            defIndex = defIndex,
            position = position,
        )
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "字符串超过 $MAX_STRING_BYTES 字节 —— 整包拒绝" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(inStream: DataInputStream): String {
        val len = readCount(inStream, MAX_STRING_BYTES, "字符串长度")
        val bytes = ByteArray(len).also { inStream.readFully(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readCount(inStream: DataInputStream, max: Int, what: String): Int {
        val n = inStream.readInt()
        require(n >= 0 && n <= max) { ".erbm 的 $what 越界（$n，上界 $max）—— 整包拒绝" }
        return n
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
