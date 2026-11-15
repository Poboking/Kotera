package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program

/**
 * 四池容器「真 Program 载荷」夹具（CONTRACT-RT2-M2CODEC 判据 1；测试专用，确定性、纯 JVM）。
 *
 * 与 [ModuleContainerFixture]（M2-2 的合成字节夹具）的分工：那份的字节是 codec 往返
 * 专用（codeWords/poolBlob 是无语义的合成值）；本夹具从**真实零基线 Program**（生产
 * [CodeBuilder] + [ErbcWriter.encode]）出字节 —— poolBlob = Program 自身 `.erbc` 序列化
 * 原样直存（`ModuleContainerAdapter` KDoc「载荷解释」的生产口径），codeWords/callTargets/
 * relocations/四计数全部从同一 Program 派生，喂生产 `ModuleContainerCodec.encode`。
 * **禁止手搓假字节自证**：装配链端到端用例的容器字节一律走本夹具。
 */
internal object ModuleContainerProgramFixture {

    /**
     * 从真实零基线 Program 构造容器模型：指令流、callTargets（全部 `_CALL.a` 原始值，
     * 含 super 与外部导入哨兵）、重定位项（按 [OpcodeRelocation] 机器表逐槽派生）、
     * 四计数与 poolBlob 全部同源自 Program。非 FUNCTION 类槽（NONE）不进重定位表
     * （容器格式的 relocations = 池引用与函数下标的自描述面，NONE 槽无信息）。
     */
    fun model(
        packageId: String,
        program: Program,
        imports: List<String> = emptyList(),
        exports: List<ExportMeta> = emptyList(),
        artifactSha256: String = "sha256-" + packageId,
    ): ModuleContainer {
        val words = program.codeSource.toIntArray()
        val callTargets = ArrayList<Int>()
        val relocations = ArrayList<RelocationEntry>()
        for (pc in 0 until program.instructionCount) {
            val w = pc * Instruction.WORDS
            val op = words[w]
            val reloc = OpcodeRelocation.of(op)
            for (slot in 0..2) {
                val category = reloc.at(slot)
                if (category == PoolRef.NONE) continue
                relocations += RelocationEntry(pc, op, slot, category)
                if (category == PoolRef.FUNCTION) callTargets += words[w + 1 + slot]
            }
        }
        return ModuleContainer(
            role = ContainerRole.MODULE,
            requiredFeatures = RequiredFeatures.REQUIRED_MASK,
            isaVersion = ModuleContainerFormat.ISA_VERSION,
            descriptor = ContainerDescriptor(
                packageId = packageId,
                artifactSha256 = artifactSha256,
            ),
            imports = imports,
            callTargets = callTargets,
            relocations = relocations,
            exports = exports,
            pools = ZeroBasePools(
                longCount = program.constantCount,
                stringCount = program.stringCount,
                exprCount = program.exprCount,
                statementCount = program.statementCount,
                baseMarker = 0,
                codeWords = words.toList(),
                poolBlob = ErbcWriter.encode(program).toList(),
            ),
        )
    }

    /** 正控字节：走生产 [ModuleContainerCodec.encode]（先 fail-closed 校验再写）。 */
    fun containerBytes(
        packageId: String,
        program: Program,
        imports: List<String> = emptyList(),
        exports: List<ExportMeta> = emptyList(),
    ): ByteArray = ModuleContainerCodec.encode(model(packageId, program, imports, exports))

    /** LINKED 态字节：三段占位（本格式 LINKED 态不携带程序载荷，适配层登记余量）。 */
    fun linkedBytesFor(packageId: String): ByteArray = ModuleContainerCodec.encode(
        ModuleContainer(
            role = ContainerRole.LINKED_PROGRAM,
            requiredFeatures = RequiredFeatures.REQUIRED_MASK,
            isaVersion = ModuleContainerFormat.ISA_VERSION,
            descriptor = ContainerDescriptor(packageId = packageId),
            imports = emptyList(),
            callTargets = emptyList(),
            relocations = emptyList(),
            exports = emptyList(),
            linkedSegments = LinkedSegments(
                symbolCatalog = byteArrayOf(1).toList(),
                dispatch = byteArrayOf(2).toList(),
                eventRegistry = byteArrayOf(3).toList(),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 真实零基线 Program 形态（CodeBuilder 直发，与 F3 e2e / ModChainAssemblerTest 同源）
    // ------------------------------------------------------------------

    /** base：ENTRY@0 调 X@1；X 打印 BASE-X、置 111 返回（与 F3 e2e 的 baseProgram 同款）。 */
    fun baseProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-X"), 1, 0, 2)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        return b.build(0)
    }

    /** 加性模块：MOD_NEW 打印 MOD-NEW（base 无同名函数；指令流内 `_CALL.a` 全为非负）。 */
    fun additiveProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("MOD_NEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("MOD-NEW"), 1, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(0)
    }

    /**
     * 覆写模块：X 覆写 base 的 X；`withSuper=true` 时体内带 `CALL SUPER`
     * （`_CALL.a = CALL_SUPER_SENTINEL` 原始哨兵，链接期 `CallRelocation` 还原）。
     */
    fun overrideProgram(withSuper: Boolean): Program {
        val b = CodeBuilder()
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("MOD-X"), 1, 0, 0)
        if (withSuper) {
            b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
            b.emit(Opcode._PRINT_INT, 0, 1, 0, 0)
        } else {
            b.emit(Opcode._SET_INT, 0, b.constant(222L), 0, 0)
        }
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(0)
    }
}
