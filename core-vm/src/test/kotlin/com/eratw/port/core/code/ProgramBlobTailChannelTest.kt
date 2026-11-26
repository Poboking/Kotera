package com.eratw.port.core.code

import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.proc.FuncMeta
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lane DV 定论实验：**不改版本号的寄生通道**成不成立。
 *
 * 待判命题：把资源（图/音/文本）追加到 program blob 尾部、同时把 programLen 放大，
 * 旧解码器会不会静默接受？若会，`.erbm` 内嵌资源可以走这条寄生通道，不必破坏性版本 bump。
 *
 * 本类只钉**当前实现的现状行为**（main 零改动，不含任何实现）：
 * - `.erbc` 层：`ErbcReader.decode` 读完已知段即以 `return program` 收尾（`ErbcReader.kt:588`），
 *   全程没有「最后一段结束 == 文件尾」判据 ⇒ 尾部字节既不被读也不被校验；
 * - `.erbm` 层：`ErbModuleContainer.decode` 在记录区之后有 `require(available()==0)`
 *   （`ErbModuleContainer.kt:176`）⇒ 外层尾部 fail-closed；但 program 区**内部**的尾部字节
 *   归 `.erbc` 层判，外层看不到。
 *
 * **这些「接受」是未定义行为**：老解码器从未承诺容忍尾部字节，实现随时可能在任一版本
 * 收紧（新增一行 `available()==0` 即翻面），因此**不可作为跨版本契约**，只能当
 * 「同一版本内自产自销」的现状依据。用例名一律带「现状锁 / 反证」，不带「应被接受」。
 *
 * 差分用例是核心：同一段垃圾字节，放进 program 区尾部 → 接受；放进记录区之后 → 整包拒。
 */
class ProgramBlobTailChannelTest {

    // ------------------------------------------------------------------
    // .erbc：program blob 尾部（现状锁 — 未定义行为）
    // ------------------------------------------------------------------

    @Test
    fun `现状锁_program_blob_尾部垃圾当前被静默接受`() {
        val clean = ErbcWriter.encode(minimalProgram())
        val tailed = clean + junkBytes(JUNK_BYTES)
        val baseline = ErbcReader.decode(clean)
        val loaded = ErbcReader.decode(tailed)

        assertEquals(TWO_INSTRUCTIONS, loaded.instructionCount)
        assertContentEquals(baseline.code, loaded.code)
        // probe 只读文件头，尾部字节连段表都不该影响。
        assertContentEquals(ErbcReader.probe(clean).offsets, ErbcReader.probe(tailed).offsets)

        // 尾部形态不影响判定：全零尾巴也照单全收（不是「填充字节被特殊跳过」）。
        val zeroTailed = ErbcReader.decode(clean + ByteArray(JUNK_BYTES))
        assertEquals(TWO_INSTRUCTIONS, zeroTailed.instructionCount)
        assertContentEquals(baseline.code, zeroTailed.code)
    }

    @Test
    fun `现状锁_尾部再放一份完整产物不会被当成第二段指令流`() {
        val clean = ErbcWriter.encode(minimalProgram())
        // 若读侧把尾部当段正文续读，指令数会变成两倍；实际仍只解出第一份。
        val loaded = ErbcReader.decode(clean + clean)

        assertEquals(TWO_INSTRUCTIONS, loaded.instructionCount)
        assertContentEquals(ErbcReader.decode(clean).code, loaded.code)
    }

    @Test
    fun `现状锁_尾部容忍 1 MiB 级垃圾`() {
        val clean = ErbcWriter.encode(minimalProgram())
        val tailed = clean + junkBytes(LARGE_JUNK_BYTES)

        assertEquals(clean.size + LARGE_JUNK_BYTES, tailed.size)
        assertEquals(TWO_INSTRUCTIONS, ErbcReader.decode(tailed).instructionCount)
    }

    @Test
    fun `负控_program_blob_截断一字节必拒`() {
        val clean = ErbcWriter.encode(minimalProgram())
        val truncated = clean.copyOf(clean.size - 1)

        assertFailsWith<ErbcFormatException> { ErbcReader.decode(truncated) }
    }

    @Test
    fun `现状锁_映射装载路同样接受 program_blob 尾部垃圾`() {
        // 生产走的是这条（ProgramLoader.loadMapped），不走 ErbcReader.decode；
        // 两条路的接受/拒绝口径必须一致，不一致就是最容易埋雷的地方。
        val clean = ErbcWriter.encode(minimalProgram())
        val path = Files.createTempFile("blobtail-", ".erbc")
        try {
            Files.write(path, clean + junkBytes(JUNK_BYTES))
            val program = ProgramLoader.loadMapped(path)
            try {
                assertEquals(TWO_INSTRUCTIONS, program.instructionCount)
            } finally {
                program.releaseMmap()
            }
        } finally {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    @Test
    fun `现状锁_全读装载路同样接受 program_blob 尾部垃圾`() {
        val clean = ErbcWriter.encode(minimalProgram())
        val path = Files.createTempFile("blobtail-full-", ".erbc")
        try {
            Files.write(path, clean + junkBytes(JUNK_BYTES))
            val program = ProgramLoader.loadFile(path)
            try {
                assertEquals(TWO_INSTRUCTIONS, program.instructionCount)
            } finally {
                program.releaseMmap()
            }
        } finally {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    // ------------------------------------------------------------------
    // .erbm：program 区尾部寄生 vs 记录区尾部 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `现状锁_erbm_program 区尾部可寄生 payload 且当前解码器接受`() {
        val program = minimalProgram()
        val programBytes = ErbcWriter.encode(program)
        val clean = ErbModuleContainer.encode(
            CompiledModule(MODULE_PACKAGE, program, functionMetas(), emptyMap()),
            allowSuperCalls = false,
        )
        val payload = junkBytes(JUNK_BYTES)
        val spliced = spliceIntoProgramBlob(clean, programBytes, payload)

        assertEquals(clean.size + payload.size, spliced.size)
        // programLen 已放大成「program + payload」。
        assertEquals(programBytes.size + payload.size, readBeInt(spliced, programAreaOffset() - LEN_FIELD_BYTES))

        val decoded = ErbModuleContainer.decode(spliced)
        assertEquals(TWO_INSTRUCTIONS, decoded.program.instructionCount)
        // payload 原样躺在 program 区尾部：新代码按约定切片即可取回，旧代码完全看不见它。
        val payloadStart = programAreaOffset() + programBytes.size
        assertContentEquals(payload, spliced.copyOfRange(payloadStart, payloadStart + payload.size))

        // 现状 1：payload 受外层全量摘要保护 —— 改一个字节就整包拒（不会被静默当垃圾）。
        val tampered = spliced.copyOf()
        tampered[payloadStart] = (tampered[payloadStart] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(tampered) }

        // 现状 2：payload 不被任何读改写保留 —— 解出再写回只重编码 Program，payload 静默丢失。
        val reencoded = ErbModuleContainer.encode(decoded.toCompiledModule(), decoded.allowSuperCalls)
        assertTrue(reencoded.size < spliced.size, "读改写应丢掉 payload，但重编码体积没变小")
    }

    @Test
    fun `现状锁_erbm_记录区之后有多余字节必拒`() {
        val clean = ErbModuleContainer.encode(
            CompiledModule(MODULE_PACKAGE, minimalProgram(), functionMetas(), emptyMap()),
            allowSuperCalls = false,
        )
        val body = clean.copyOfRange(0, clean.size - DIGEST_BYTES)
        val withTrailing = reseal(body + junkBytes(JUNK_BYTES))

        val error = assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(withTrailing) }
        assertTrue(
            error.message.orEmpty().contains(TRAILING_REJECT_MARK),
            "拒绝应落在尾部多余字节判据上，实际文案: ${error.message}",
        )
    }

    @Test
    fun `反证_无尾垃圾的合法容器不满足记录区必拒断言`() {
        val clean = ErbModuleContainer.encode(
            CompiledModule(MODULE_PACKAGE, minimalProgram(), functionMetas(), emptyMap()),
            allowSuperCalls = false,
        )
        // 把上面那条「倒垃圾必拒」断言原样套到干净容器上：它必须失败，否则拒绝不来自尾字节。
        val flipped = runCatching {
            assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(clean) }
        }
        assertTrue(flipped.isFailure, "干净容器竟满足记录区尾部必拒断言 —— 拒绝不是尾字节造成的")
    }

    // ------------------------------------------------------------------
    // 夹具与字节工具
    // ------------------------------------------------------------------

    /** 合法极小产物：入口函数 0、两条指令（_NOP + _HALT）、零基线。 */
    private fun minimalProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", b.here(), 0, 1)
        b.emit(Opcode._NOP, 0, 0, 0, FIRST_LINE)
        b.emit(Opcode._HALT, 0, 0, 0, SECOND_LINE)
        return b.build(0)
    }

    private fun functionMetas(): List<FuncMeta> = listOf(FuncMeta(index = 0, key = "MAIN", position = "MOD.ERB:1"))

    /** 确定性伪随机垃圾（不填 0，避免「全 0 被当尾部填充跳过」这种假解释）。 */
    private fun junkBytes(n: Int): ByteArray = ByteArray(n) { i -> (i * LCG_MULT + LCG_INC).toByte() }

    /**
     * program 区在 `.erbm` 里的起点：MAGIC(5) + version/flags(4) + writeString(packageId: 4+len)
     * + programLen(4)。与 `ErbModuleContainer.encode` 的写序逐字段对应。
     */
    private fun programAreaOffset(): Int =
        ErbModuleContainer.MAGIC.length + VERSION_FLAGS_BYTES + LEN_FIELD_BYTES +
            MODULE_PACKAGE.toByteArray(Charsets.UTF_8).size + LEN_FIELD_BYTES

    /** 把 payload 插进 program 区尾部、放大 programLen 字段、重算全量摘要（模拟寄生写侧）。 */
    private fun spliceIntoProgramBlob(container: ByteArray, programBytes: ByteArray, payload: ByteArray): ByteArray {
        val body = container.copyOfRange(0, container.size - DIGEST_BYTES)
        val area = programAreaOffset()
        val programEnd = area + programBytes.size
        require(programEnd <= body.size) { "program 区越界: $programEnd > ${body.size}" }
        val spliced = ByteArray(body.size + payload.size)
        body.copyInto(spliced, 0, 0, programEnd)
        payload.copyInto(spliced, programEnd)
        body.copyInto(spliced, programEnd + payload.size, programEnd, body.size)
        writeBeInt(spliced, area - LEN_FIELD_BYTES, programBytes.size + payload.size)
        return reseal(spliced)
    }

    private fun reseal(body: ByteArray): ByteArray = body + sha256(body)

    private fun sha256(body: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(body)

    /** `.erbm` 的长度字段由 `DataOutputStream.writeInt` 写出 = 大端。 */
    private fun writeBeInt(dst: ByteArray, at: Int, value: Int) {
        for (i in 0 until LEN_FIELD_BYTES) {
            dst[at + i] = (value ushr ((LEN_FIELD_BYTES - 1 - i) * BITS_PER_BYTE)).toByte()
        }
    }

    private fun readBeInt(src: ByteArray, at: Int): Int =
        ByteBuffer.wrap(src, at, LEN_FIELD_BYTES).order(ByteOrder.BIG_ENDIAN).getInt()

    private companion object {
        const val MODULE_PACKAGE = "lane.dv"
        const val TWO_INSTRUCTIONS = 2
        const val JUNK_BYTES = 64
        const val LARGE_JUNK_BYTES = 1 shl 20
        const val LCG_MULT = 131
        const val LCG_INC = 7
        const val DIGEST_BYTES = 32
        const val LEN_FIELD_BYTES = 4
        const val VERSION_FLAGS_BYTES = 4
        const val BITS_PER_BYTE = 8
        const val FIRST_LINE = 1
        const val SECOND_LINE = 2

        /** 只点 `ErbModuleContainer.kt:176` 的文案特征（全文见该行 require）。 */
        const val TRAILING_REJECT_MARK = "多余字节"
    }
}
