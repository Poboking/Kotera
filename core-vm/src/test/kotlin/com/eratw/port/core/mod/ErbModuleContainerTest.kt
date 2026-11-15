package com.eratw.port.core.mod

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.proc.FuncMeta
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RT2 G2 波（P1-5 余量二）：`.erbm` v1 容器的往返与 fail-closed 判据。
 *
 * 锁三件事：① 逐字段往返（packageId / 档位 / 零基线 Program / FuncMeta 全字段 /
 * 签名指纹表）；② 坏档形态一律整包拒、不返回半成品；③ magic 刻意不等于 legacy
 * `ERBM`（M2-4 转码面），防止 `ModuleContainerCodec.decode` 按四池格式误判。
 *
 * 夹具口径同 `ModChainAssemblerTest`：零基线 Program 用真 [CodeBuilder] 编译，
 * 不硬编码 Program 字节；载体字节只从 [ErbModuleContainer.encode] 来。
 */
class ErbModuleContainerTest {

    /** 两函数零基线模块：ENTRY@0 / TARGET@1，各一条 _RET。 */
    private fun zeroBaseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    /**
     * super 承载模块：X 体内发 `CALL SUPER` 哨兵、Y 体内发 `TRYCALL SUPER` 哨兵
     * （两个设计值各一）。哨兵由编译器产出（ErbProgramCompiler allowSuperCalls=true），
     * 容器只负责直存 —— 本夹具用 CodeBuilder 直填哨兵下标，形态与编译产物逐位同源。
     */
    private fun superBearingProgram(): Program {
        val b = CodeBuilder()
        val x = b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        b.declareFunction("Y", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.TRY_CALL_SUPER_SENTINEL, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        return b.build(x)
    }

    /** 全字段带非零值，确保 readMeta / writeMeta 的位序与标量逐个被覆盖。 */
    private fun metas(): List<FuncMeta> = listOf(
        FuncMeta(
            index = 0,
            key = "ENTRY",
            name = "Entry",
            isEvent = true,
            isOnly = true,
            isPri = true,
            argIntCount = 2,
            argStrCount = 3,
            localSize = 4,
            localsSize = 5,
            floatLocalSize = 6,
            defIndex = 7,
            position = "MOD.ERB:12",
        ),
        FuncMeta(
            index = 1,
            key = "TARGET",
            name = "Target",
            isMethod = true,
            isLater = true,
            isSingle = true,
            returnsStr = true,
            argIntCount = 1,
            argStrCount = 1,
            localSize = 8,
            localsSize = 9,
            floatLocalSize = 10,
            defIndex = 11,
            position = "MOD.ERB:20",
        ),
    )

    private fun module(allowSuperCalls: Boolean = true) = ErbModuleContainer.ErbModule(
        packageId = "com.example.mod",
        allowSuperCalls = allowSuperCalls,
        program = zeroBaseProgram(),
        functions = metas(),
        signatures = linkedMapOf("TARGET" to "fp-target", "ENTRY" to "fp-entry"),
    )

    /** 把 body 改动后重附正确摘要，用于越过摘要闸去测更深的读侧校验。 */
    private fun reseal(body: ByteArray): ByteArray =
        body + MessageDigest.getInstance("SHA-256").digest(body)

    private fun bodyOf(encoded: ByteArray): ByteArray = encoded.copyOfRange(0, encoded.size - 32)

    private fun assertZeroBase(p: Program) {
        assertEquals(0, p.codeBase)
        assertEquals(0, p.statementBase)
        assertEquals(0, p.stringBase)
        assertEquals(0, p.longBase)
        assertEquals(0, p.exprBase)
        assertEquals(0, p.functionBase)
    }

    // ------------------------------------------------------------------
    // ① 往返逐字段一致
    // ------------------------------------------------------------------

    @Test
    fun `import哨兵模块往返_模块读路放行而base读路仍拒`() {
        // M2-4：零基线 Module 的跨包调用就是负导入下标 —— 容器读路必须放行，
        // 而 base .erbc 读路（allowImportCalls=false 缺省）对同一份字节仍照拒。
        val b = CodeBuilder()
        val x = b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, -1, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._CALL, -(CallSentinels.IMPORT_INDEX_MAX + 1), 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        val p = b.build(x)
        val m = ErbModuleContainer.ErbModule(
            "kojo.import",
            allowSuperCalls = false,
            program = p,
            functions = listOf(FuncMeta(index = 0, key = "X")),
            signatures = emptyMap(),
        )
        val round = ErbModuleContainer.decode(ErbModuleContainer.encode(m))
        val words = round.program.codeSource.toIntArray()
        val calls = (0 until round.program.instructionCount)
            .filter { words[it * 4] == Opcode._CALL }
            .map { words[it * 4 + 1] }
        assertContentEquals(
            intArrayOf(-1, -(CallSentinels.IMPORT_INDEX_MAX + 1)),
            calls.toIntArray(),
            "导入哨兵下标必须逐位保真",
        )
        assertFailsWith<ErbcDecodeException>("base 读路不得放行导入哨兵") {
            ErbcReader.decode(ErbcWriter.encode(p))
        }
    }

    @Test
    fun `往返逐字段一致_档位为零基线全字段保真`() {
        val src = module()
        val encoded = ErbModuleContainer.encode(src)
        val out = ErbModuleContainer.decode(encoded)

        assertEquals("com.example.mod", out.packageId)
        assertTrue(out.allowSuperCalls)
        assertZeroBase(out.program)
        assertEquals(src.program.functions.size, out.program.functions.size)
        assertEquals(src.program.instructionCount, out.program.instructionCount)
        for (i in src.program.functions.indices) {
            assertEquals(src.program.functions[i].name, out.program.functions[i].name, "函数 $i 名")
        }
        assertEquals(src.functions.size, out.functions.size)
        for (i in src.functions.indices) {
            val a = src.functions[i]
            val b = out.functions[i]
            assertEquals(a.index, b.index, "meta[$i].index")
            assertEquals(a.key, b.key, "meta[$i].key")
            assertEquals(a.name, b.name, "meta[$i].name")
            assertEquals(a.isEvent, b.isEvent, "meta[$i].isEvent")
            assertEquals(a.isMethod, b.isMethod, "meta[$i].isMethod")
            assertEquals(a.isOnly, b.isOnly, "meta[$i].isOnly")
            assertEquals(a.isPri, b.isPri, "meta[$i].isPri")
            assertEquals(a.isLater, b.isLater, "meta[$i].isLater")
            assertEquals(a.isSingle, b.isSingle, "meta[$i].isSingle")
            assertEquals(a.returnsStr, b.returnsStr, "meta[$i].returnsStr")
            assertEquals(a.returnsFloat, b.returnsFloat, "meta[$i].returnsFloat")
            assertEquals(a.argIntCount, b.argIntCount, "meta[$i].argIntCount")
            assertEquals(a.argStrCount, b.argStrCount, "meta[$i].argStrCount")
            assertEquals(a.localSize, b.localSize, "meta[$i].localSize")
            assertEquals(a.localsSize, b.localsSize, "meta[$i].localsSize")
            assertEquals(a.floatLocalSize, b.floatLocalSize, "meta[$i].floatLocalSize")
            assertEquals(a.defIndex, b.defIndex, "meta[$i].defIndex")
            assertEquals(a.position, b.position, "meta[$i].position")
        }
        assertEquals(src.signatures, out.signatures)
        // 解码产物可直接喂装配器。
        val cm = out.toCompiledModule()
        assertEquals(out.packageId, cm.packageId)
        assertEquals(out.program, cm.program)
        assertEquals(out.functions, cm.functions)
    }

    @Test
    fun `非Mod档位也能往返_但不带allowSuperCalls位`() {
        val encoded = ErbModuleContainer.encode(module(allowSuperCalls = false))
        assertFalse(ErbModuleContainer.decode(encoded).allowSuperCalls)
    }

    @Test
    fun `super哨兵模块往返_档位与两个设计哨兵下标逐位保真`() {
        // D-3 kojo 覆盖契约的 super 链载体（wave/rt2-superfix）：覆写体带 CALL/TRYCALL
        // SUPER 哨兵的 Mod 编译档必须能过容器往返 —— 容器直存 Program 字节（ErbModule
        // 的 encode 只做模型校验与摘要，不做哨兵改写），装载闸收窄后两个设计哨兵值
        // 不再是 decode 的拒绝形态。既有往返夹具全是 `_RET` 程序，钉不住这条承载面。
        val program = superBearingProgram()
        val src = ErbModuleContainer.ErbModule(
            packageId = "com.example.supermod",
            allowSuperCalls = true,
            program = program,
            functions = listOf(FuncMeta(index = 0, key = "X"), FuncMeta(index = 1, key = "Y")),
            signatures = linkedMapOf("X" to "fp-x", "Y" to "fp-y"),
        )
        val encoded = ErbModuleContainer.encode(src)
        val out = ErbModuleContainer.decode(encoded)

        assertTrue(out.allowSuperCalls, "Mod 包编译档位必须保真")
        assertContentEquals(src.program.code, out.program.code, "Program 指令字必须逐字直存保真")
        val callPcs = (0 until out.program.instructionCount).filter { out.program.op(it) == Opcode._CALL }
        assertEquals(2, callPcs.size, "样题必须恰好两个 _CALL 站点（两哨兵各一）")
        assertEquals(CallSentinels.CALL_SUPER_SENTINEL, out.program.a(callPcs[0]), "CALL SUPER 哨兵下标原样保留")
        assertEquals(CallSentinels.TRY_CALL_SUPER_SENTINEL, out.program.a(callPcs[1]), "TRYCALL SUPER 哨兵下标原样保留")
        // 哨兵不是写侧变换面：decode 产物再编码逐字节回到原串（直存纪律的闭合证据）。
        assertContentEquals(encoded, ErbModuleContainer.encode(out))
    }

    @Test
    fun `magic刻意不是legacy ERBM_前四字节避开ModuleContainerCodec判定`() {
        val encoded = ErbModuleContainer.encode(module())
        assertEquals("ERTM1", String(encoded.copyOfRange(0, 5), Charsets.US_ASCII))
        assertEquals("ERTM", String(encoded.copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(String(encoded.copyOfRange(0, 4), Charsets.US_ASCII) != "ERBM")
    }

    // ------------------------------------------------------------------
    // ② 读侧 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `截断长度不足_整包拒`() {
        val short = ErbModuleContainer.encode(module()).copyOfRange(0, 20)
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(short) }
    }

    @Test
    fun `尾部摘要不符_整包拒`() {
        val broken = ErbModuleContainer.encode(module()).copyOf()
        broken[10] = (broken[10].toInt() xor 0x01).toByte()
        val e = assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(broken) }
        assertTrue(e.message!!.contains("SHA-256"))
    }

    @Test
    fun `坏magic整包拒_即使摘要正确`() {
        val body = bodyOf(ErbModuleContainer.encode(module()))
        body[0] = 'X'.code.toByte()
        val e = assertFailsWith<IllegalArgumentException> {
            ErbModuleContainer.decode(reseal(body))
        }
        assertTrue(e.message!!.contains("magic"))
    }

    @Test
    fun `坏版本整包拒_即使摘要正确`() {
        val body = bodyOf(ErbModuleContainer.encode(module()))
        body[6] = 2 // version short 的低位（MAGIC 5 字节 + 2 字节 version）
        val e = assertFailsWith<IllegalArgumentException> {
            ErbModuleContainer.decode(reseal(body))
        }
        assertTrue(e.message!!.contains("版本"))
    }

    @Test
    fun `未知档位位整包拒_即使摘要正确`() {
        val body = bodyOf(ErbModuleContainer.encode(module()))
        body[8] = (body[8].toInt() or 0x02).toByte() // flags short 低位 = 未知位
        val e = assertFailsWith<IllegalArgumentException> {
            ErbModuleContainer.decode(reseal(body))
        }
        assertTrue(e.message!!.contains("档位"))
    }

    @Test
    fun `记录区之后多余字节整包拒`() {
        val body = bodyOf(ErbModuleContainer.encode(module())) + byteArrayOf(0)
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(reseal(body)) }
    }

    // ------------------------------------------------------------------
    // ③ 写侧 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `metas与Program函数表不同长_写侧整包拒`() {
        val src = module()
        val bad = ErbModuleContainer.ErbModule(
            src.packageId, src.allowSuperCalls, src.program, src.functions.take(1), src.signatures,
        )
        val e = assertFailsWith<IllegalArgumentException> { ErbModuleContainer.encode(bad) }
        assertTrue(e.message!!.contains("不同长"))
    }

    @Test
    fun `index非稠密0基_写侧整包拒`() {
        val src = module()
        val bad = ErbModuleContainer.ErbModule(
            src.packageId,
            src.allowSuperCalls,
            src.program,
            listOf(src.functions[0], src.functions[1].copyish(index = 5)),
            src.signatures,
        )
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.encode(bad) }
    }

    @Test
    fun `签名键未大写归一_写侧整包拒`() {
        val src = module()
        val bad = ErbModuleContainer.ErbModule(
            src.packageId, src.allowSuperCalls, src.program, src.functions,
            linkedMapOf("target" to "fp-target"),
        )
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.encode(bad) }
    }

    @Test
    fun `packageId为空_写侧整包拒`() {
        val src = module()
        val bad = ErbModuleContainer.ErbModule(
            "  ", src.allowSuperCalls, src.program, src.functions, src.signatures,
        )
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.encode(bad) }
    }

    /** 只改 index 的 FuncMeta 复制（字段全显式，避免依赖 copy()。 */
    private fun FuncMeta.copyish(index: Int): FuncMeta = FuncMeta(
        index = index,
        key = key,
        name = name,
        isEvent = isEvent,
        isMethod = isMethod,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        argIntCount = argIntCount,
        argStrCount = argStrCount,
        localSize = localSize,
        localsSize = localsSize,
        floatLocalSize = floatLocalSize,
        returnsStr = returnsStr,
        returnsFloat = returnsFloat,
        defIndex = defIndex,
        position = position,
    )
}
