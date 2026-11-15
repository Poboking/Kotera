package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument

/**
 * M2-2 容器二进制夹具（测试专用，确定性、纯 JVM）。
 *
 * 正控字节走 [ModuleContainerCodec.encode]（先 fail-closed 校验再写）；
 * 负控字节走 [ModuleContainerCodec.writeBytes]（仅序列化、不做语义校验），
 * 再用 [withHeaderLines] / [withMagic] / [withContainerVersion] 定点改坏一个字段，
 * 交给 [ModuleContainerCodec.decode] 的读侧 fail-closed 闸拒绝 —— 这样「写侧校验」
 * 与「读侧校验」是两条独立路径，不会互相屏蔽。
 *
 * 真实 legacy .erbc 字节由 [legacyErbcBytes] 用生产 [ErbcWriter.encode] 生成，
 * 供 ModuleContainerLegacyReaderTest 用真实旧 Reader 装载；不是手搓 magic 的假夹具。
 */
internal object ModuleContainerFixture {

    const val H_ROLE_MODULE = "CONTAINER_ROLE MODULE"
    const val H_ROLE_LINKED = "CONTAINER_ROLE LINKED_PROGRAM"
    const val H_FEATURES = "REQUIRED_FEATURES 0x7"
    const val H_ISA = "ISA_VERSION 3"
    const val H_END = "---"

    fun moduleHeaderLines(): List<String> = listOf(H_ROLE_MODULE, H_FEATURES, H_ISA, H_END)
    fun linkedHeaderLines(): List<String> = listOf(H_ROLE_LINKED, H_FEATURES, H_ISA, H_END)

    /** 合法 MODULE 模型：零基线四池 + 2 条 imports + 5 个 callTargets（含 super 与外部哨兵）+ 3 条合法重定位。 */
    fun moduleModel(): ModuleContainer = ModuleContainer(
        role = ContainerRole.MODULE,
        requiredFeatures = RequiredFeatures.REQUIRED_MASK,
        isaVersion = ModuleContainerFormat.ISA_VERSION,
        descriptor = ContainerDescriptor(
            packageId = "mod.demo",
            version = "1.0.0",
            requires = listOf("base.core"),
            loadAfter = listOf("mod.alpha"),
            artifactSha256 = "sha256-demo-module",
        ),
        imports = listOf("ext.FOO", "ext.BAR"),
        callTargets = listOf(
            0,
            CallSentinels.external(0),
            CallSentinels.CALL_SUPER_SENTINEL,
            CallSentinels.TRY_CALL_SUPER_SENTINEL,
            CallSentinels.external(1),
        ),
        relocations = listOf(
            RelocationEntry(pc = 0, opcode = Opcode._CALL, slot = 0, category = PoolRef.FUNCTION),
            RelocationEntry(pc = 1, opcode = Opcode._SET_INT, slot = 1, category = PoolRef.CONSTANT),
            RelocationEntry(pc = 2, opcode = Opcode._PRINT_LITERAL, slot = 0, category = PoolRef.STRING),
        ),
        exports = listOf(ExportMeta(kind = 0, name = "MODFN", signature = "()I")),
        pools = ZeroBasePools(
            longCount = 0,
            stringCount = 2,
            exprCount = 0,
            statementCount = 0,
            baseMarker = 0,
            codeWords = listOf(0x0016_0000, 0x0001_0001, -1),
            poolBlob = byteArrayOf(9, 8, 7, 6, 5).toList(),
        ),
    )

    /** 合法 LINKED 模型：导入表/重定位表/哨兵全空，三段原样保真。 */
    fun linkedModel(): ModuleContainer = ModuleContainer(
        role = ContainerRole.LINKED_PROGRAM,
        requiredFeatures = RequiredFeatures.REQUIRED_MASK,
        isaVersion = ModuleContainerFormat.ISA_VERSION,
        descriptor = ContainerDescriptor(packageId = "linked.program", artifactSha256 = "sha256-linked"),
        imports = emptyList(),
        callTargets = emptyList(),
        relocations = emptyList(),
        exports = listOf(ExportMeta(kind = 0, name = "MAIN", signature = "()V")),
        linkedSegments = LinkedSegments(
            symbolCatalog = byteArrayOf(11, 12, 13).toList(),
            dispatch = byteArrayOf(21, 22).toList(),
            eventRegistry = byteArrayOf(31).toList(),
        ),
    )

    /** 正控：走生产 encode（先校验再写）。 */
    fun moduleBytes(): ByteArray = ModuleContainerCodec.encode(moduleModel())
    fun linkedBytes(): ByteArray = ModuleContainerCodec.encode(linkedModel())

    /** 负控：仅序列化、跳过写侧校验，供各变异 helper 定点改坏。 */
    fun raw(model: ModuleContainer): ByteArray = ModuleContainerCodec.writeBytes(model)

    fun rawModule(): ByteArray = raw(moduleModel())
    fun rawLinked(): ByteArray = raw(linkedModel())

    // ---------- 字节定点变异 ----------

    fun withMagic(bytes: ByteArray, magic: String): ByteArray {
        val m = magic.toByteArray(Charsets.US_ASCII)
        require(m.size == 4) { "magic 必须 4 字节" }
        val out = bytes.copyOf()
        m.copyInto(out, 0)
        return out
    }

    fun withContainerVersion(bytes: ByteArray, version: Int): ByteArray {
        val out = bytes.copyOf()
        out[4] = (version and 0xFF).toByte()
        out[5] = ((version shr 8) and 0xFF).toByte()
        return out
    }

    fun headerTextOf(bytes: ByteArray): String {
        val len = u16At(bytes, 6)
        return String(bytes, 8, len, Charsets.UTF_8)
    }

    fun withHeaderLines(bytes: ByteArray, lines: List<String>): ByteArray =
        withHeaderText(bytes, lines.joinToString("\n", postfix = "\n"))

    /** 重建前缀：magic 与 containerVersion 原样，headerLen 改写，载荷原样保留。 */
    fun withHeaderText(bytes: ByteArray, headerText: String): ByteArray {
        val newHeader = headerText.toByteArray(Charsets.UTF_8)
        val oldLen = u16At(bytes, 6)
        val payload = bytes.copyOfRange(8 + oldLen, bytes.size)
        val out = ByteArray(8 + newHeader.size + payload.size)
        bytes.copyInto(out, 0, 0, 8)
        out[6] = (newHeader.size and 0xFF).toByte()
        out[7] = ((newHeader.size shr 8) and 0xFF).toByte()
        newHeader.copyInto(out, 8)
        payload.copyInto(out, 8 + newHeader.size)
        return out
    }

    private fun u16At(bytes: ByteArray, pos: Int): Int =
        (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)

    // ---------- 真实 legacy .erbc ----------

    /**
     * 用生产 [CodeBuilder] 编一份最小可装载 v2 产物（一条 WAIT + 一条 halt），
     * 再由 [ErbcWriter.encode] 落字节。行号表长度与本段指令数自洽，是 [ErbcWriter] 的硬约束。
     */
    fun legacyErbcBytes(): ByteArray {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val id = b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 1, "WAIT"))
        b.emit(Opcode.WAIT, id, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return ErbcWriter.encode(b.build(0))
    }
}
