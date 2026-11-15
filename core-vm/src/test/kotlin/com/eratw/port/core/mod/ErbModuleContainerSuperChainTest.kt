package com.eratw.port.core.mod

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.RelocationException
import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RT2 superfix 波（wave/rt2-superfix）：`.erbm` 容器语境的 super 哨兵承载链
 * （契约 CONTRACT-RT2-SUPERFIX §1.1 / §1.4）。
 *
 * **为什么必须容器语境**：既有 super 链测试（`KojoOverrideContractTest`）全部停在
 * 内存 `LinkableModule` 直连链接器，`ErbModuleCompilerTest` 只断内存编译产物的哨兵
 * 存在 —— 两条路都绕开了容器装载闸；F3 波（`ModChainAssemblySessionEndToEndTest`
 * 判据 2）正是栽在这一步：哨兵下标过不了 `ErbcReader.validate` 的 `a < 0` 判据，
 * 容器 decode 即整包拒。本套件把链条从**真容器字节**起步钉死：
 *
 * 1. 正向：`ErbModuleContainer.encode(allowSuperCalls = true)` 真 bytes → decode →
 *    `toCompiledModule` → `ModChainAssembler.assemble` 五步链 → 链接产物里模块覆写体的
 *    `CALL SUPER` 站点已还原为 base 前实现的物理下标并置 DIRECT 位
 *    （`CallRelocation` 的 SuperCall 语义，经生产装配链端到端可达）。
 * 2. 负控（未还原出口自检钉板）：带 `CALL SUPER` 哨兵但**无前一同名实现**的模块，
 *    容器 decode 必须放行（哨兵是装载闸的两个设计例外），但装配链在链接期
 *    `RelocationException` 整包拒 —— 哨兵绝不许以未还原形态活着出装载闸。
 */
class ErbModuleContainerSuperChainTest {

    // ------------------------------------------------------------------
    // 夹具：base（ENTRY@0 调 X@1；X 打印 BASE-X、置 111 并返回）—— 形态与
    // app 模块 ModChainAssemblySessionEndToEndTest 的 base 夹具同源。
    // ------------------------------------------------------------------

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
    )

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-X"), 1, 0, 2)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        return b.build(entry)
    }

    /** X 覆写签名规范串；容器签名表与 base 侧目录同源这一条串（口径同 app e2e 夹具）。 */
    private val xCanonical = "SIG1|ret:I"

    private val xFingerprint = BaseSignatureCatalog.fingerprint(xCanonical)

    /** 覆写 X 的零基线模块：PRINT MOD-X → CALL SUPER → RET。 */
    private fun superOverrideModule(): CompiledModule {
        val b = CodeBuilder()
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("MOD-X"), 1, 0, 0)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return CompiledModule(
            "mod.ovr",
            b.build(0),
            listOf(FuncMeta(index = 0, key = "X")),
            mapOf("X" to xFingerprint),
        )
    }

    /** 加性单函数 ONLY_HERE 的模块：体内带 `CALL SUPER`，但 base 与自身都没有前实现。 */
    private fun lonelySuperModule(): CompiledModule {
        val b = CodeBuilder()
        b.declareFunction("ONLY_HERE", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return CompiledModule(
            "mod.lonely",
            b.build(0),
            listOf(FuncMeta(index = 0, key = "ONLY_HERE")),
        )
    }

    /** 容器真字节（Mod 包编译档位）：encode → decode 全走生产面，不经任何内存直通。 */
    private fun containerBytes(cm: CompiledModule): ByteArray =
        ErbModuleContainer.encode(cm, allowSuperCalls = true)

    // ------------------------------------------------------------------
    // 1：正向 —— 容器真字节过装载进装配链，super 站点还原为 base 前实现 + DIRECT 位
    // ------------------------------------------------------------------

    @Test
    fun `容器真字节过装载进装配链_CALL_SUPER还原为base前实现物理下标并置DIRECT位`() {
        val bytes = containerBytes(superOverrideModule())
        val cm = ErbModuleContainer.decode(bytes).toCompiledModule()
        val manifest = ManifestCodec.encode(
            ModuleDescriptor(packageId = "mod.ovr", overrideFunctions = setOf("X")),
        )
        val base = baseProgram()

        val chain = ModChainAssembler.assemble(
            sources = listOf(ManifestSource("mod.ovr.manifest", manifest)),
            compiledModules = listOf(cm),
            base = base,
            baseMetas = baseMetas,
            baseSignatureOf = { key -> if (key == "X") xFingerprint else "" },
        )

        assertFalse(chain.byPassed, "容器在场必须真装配")
        assertTrue(chain.hasOverrides, "X 被覆写 ⇒ HAS_OVERRIDES")
        val linked = assertNotNull(chain.linked).program
        // 模块指令拼接在 base 之后：模块覆写体里的 _CALL 站点（哨兵入链时的唯一 _CALL）
        val callPc = (base.instructionCount until linked.instructionCount)
            .first { linked.op(it) == Opcode._CALL }
        assertEquals(
            1,
            linked.a(callPc),
            "CALL SUPER 必须还原为 base 前实现 X 的物理下标（base 共 2 函数，X=1）",
        )
        assertTrue(
            linked.b(callPc) and Opcode.DIRECT_CALL_FLAG != 0,
            "SUPER 必须置 DIRECT 位（绕过 dispatch，防 super 链二次跳转成环）",
        )
    }

    // ------------------------------------------------------------------
    // 2：负控（未还原出口自检钉板）—— 哨兵过装载闸，无前实现 ⇒ 链接期整包拒
    // ------------------------------------------------------------------

    @Test
    fun `容器语境super哨兵无前一同名实现_装载闸放行但链接期整包拒`() {
        // 装载闸放行是本波收窄后的设计行为：容器 decode 必须成功（对照修复前
        // `ErbcDecodeException` 整包拒的现态红）；「无前实现」的拦截只能在链接期出口。
        val bytes = containerBytes(lonelySuperModule())
        val decoded = ErbModuleContainer.decode(bytes)
        val callPc = (0 until decoded.program.instructionCount)
            .first { decoded.program.op(it) == Opcode._CALL }
        assertEquals(
            CallSentinels.CALL_SUPER_SENTINEL,
            decoded.program.a(callPc),
            "容器 decode 必须放行哨兵下标（直存保真）",
        )

        val manifest = ManifestCodec.encode(ModuleDescriptor(packageId = "mod.lonely"))
        val ex = assertFailsWith<RelocationException> {
            ModChainAssembler.assemble(
                sources = listOf(ManifestSource("mod.lonely.manifest", manifest)),
                compiledModules = listOf(decoded.toCompiledModule()),
                base = baseProgram(), // base 无 ONLY_HERE ⇒ 前一同名实现不存在
                baseMetas = baseMetas,
            )
        }
        assertTrue(
            ex.message!!.contains("没有前一同名实现"),
            "应报 CallRelocation 的无前实现链接错误，实为: ${ex.message}",
        )
    }
}
