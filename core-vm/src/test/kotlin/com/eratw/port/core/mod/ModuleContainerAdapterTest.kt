package com.eratw.port.core.mod

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.ExportMeta
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleContainerCodec
import com.eratw.port.core.patch.ModuleContainerFormat
import com.eratw.port.core.patch.ModuleContainerProgramFixture
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 四池容器补丁 → Mod 链输入形态适配器（CONTRACT-RT2-M2CODEC 裁定 4）的判据面：
 *
 * - **加性正控**：真 Program 容器（[ModuleContainerProgramFixture]，禁止手搓假字节）
 *   → 适配 → 五步链真装配 → plan 三视图可派生 → 真 VM 执行（新增函数可调、base 零漂移）。
 * - **覆写 + CALL SUPER（判据 1 的核心单元级降级位）**：覆写容器体内的 `CALL SUPER`
 *   原始哨兵经适配进链接器、`CallRelocation` 回填 base 前实现 + DIRECT 位、真 VM 跑出
 *   「Mod 体 → SUPER 回 base」。**覆盖声明在测试侧以 manifest 形态注入** —— 冻结的 v3
 *   四池容器格式没有覆盖声明通道（ContainerDescriptor 只有五个标量字段），生产装配
 *   （免 manifest + MODERN）对覆写容器补丁 fail-closed，这一负控由 app 侧
 *   `ModChainAssemblySessionEndToEndTest` 钉死；本类按契约判据 1 的诚实降级阀
 *   （覆写行为断言降核心单元级）钉「哨兵链路 + 覆盖分派机制」本身。
 * - **对账负控**：容器自描述（四计数 / codeWords / relocations）与载荷实体脱节 → 整包拒。
 * - **余量负控**：LINKED 态（无程序载荷）与外部导入哨兵载荷（`ErbcReader` 装载闸不认
 *   第三类负值）→ 显式 fail-closed，不留静默面。
 */
class ModuleContainerAdapterTest {

    private class RecordingHost : VmHost {
        val lines = ArrayList<String>()

        override val evalContext: EvalContext? = null

        /**
         * 与生产 console 的 `loggedLines()` 同口径：VM 的 PRINT 每次送出的文本带行尾换行
         * （Emuera 一行一打印），日志行不保留它 —— app 侧既有端到端判据
         * `ModChainAssemblySessionEndToEndTest` 断言的 `["MOD-X", "BASE-X", "111"]`
         * 就是这层规范化后的形状。夹具只去掉行尾换行，不改打印次数与顺序，
         * 因此「三次独立打印」的断言强度不降低。
         */
        override fun print(text: String) {
            lines.add(text.removeSuffix("\n").removeSuffix("\r"))
        }

        override fun onFunctionEnter(funcIndex: Int) = Unit
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
    )

    /** 覆写套件同源的 X 签名规范串（容器导出侧与 base 侧车同一条串）。 */
    private val xCanonical = "SIG1|ret:I"

    private fun adapt(
        bytes: ByteArray,
        sourceName: String = "mods/patch.patch",
    ): ModuleContainerAdapter.AdaptedModule = ModuleContainerAdapter.adapt(
        ModuleContainerCodec.decode(bytes),
        sourceName,
    )

    /**
     * VM 跑指定函数入口。[entryFunctionIndex] 是 FunctionTable 下标
     * （`VirtualMachine.run(Int)` 的契约，见 `Vm.kt:1388`），**不是** `FunctionInfo.entryPc`；
     * 缺省 0 = ENTRY。
     */
    private fun run(
        program: Program,
        dispatch: IntArray?,
        entryFunctionIndex: Int = 0,
    ): Pair<Long, List<String>> {
        val host = RecordingHost()
        val vm = VirtualMachine(program, host, functionDispatch = dispatch)
        return vm.run(entryFunctionIndex) to host.lines
    }

    // ------------------------------------------------------------------
    // 加性正控：适配 → 五步链 → plan → 真 VM
    // ------------------------------------------------------------------

    @Test
    fun `加性容器适配_编译模块与内存Manifest可还原且零基线`() {
        val program = ModuleContainerProgramFixture.additiveProgram()
        val adapted = adapt(ModuleContainerProgramFixture.containerBytes("mod.add", program))

        val cm = adapted.compiled
        assertEquals("mod.add", cm.packageId)
        val p = cm.program
        assertEquals(0, p.codeBase, "六基线全 0")
        assertEquals(0, p.functionBase)
        assertEquals(1, p.functions.size)
        assertEquals("MOD_NEW", p.functions[0].name)
        assertEquals(program.instructionCount, p.instructionCount, "载荷指令流经字节往返保持")

        val meta = cm.functions.single()
        assertEquals(0, meta.index)
        assertEquals("MOD_NEW", meta.key)
        assertEquals(0, meta.defIndex)
        assertEquals(0, meta.argIntCount)
        assertEquals(0, meta.argStrCount)
        assertEquals("mods/patch.patch", meta.position)
        assertTrue(cm.signatures.isEmpty(), "无导出签名 = 指纹表空（覆盖闸 fail-closed 的输入）")

        // 内存内 ManifestSource 可被生产 ManifestCodec 还原（五步链第一步的唯一 descriptor 入口）。
        val d = ManifestCodec.decode(adapted.source.bytes)
        assertEquals("mod.add", d.packageId)
        assertTrue(d.externalCalls.isEmpty())
        assertNull(ModuleContainerFormat.probe(adapted.source.bytes), "manifest 字节不是容器，探测必须 miss")
    }

    @Test
    fun `加性容器经五步链装配_plan三视图派生_新增函数真VM可调`() {
        val adapted = adapt(
            ModuleContainerProgramFixture.containerBytes("mod.add", ModuleContainerProgramFixture.additiveProgram()),
        )
        val chain = ModChainAssembler.assemble(
            sources = listOf(adapted.source),
            compiledModules = listOf(adapted.compiled),
            base = ModuleContainerProgramFixture.baseProgram(),
            baseMetas = baseMetas,
            policy = ResolverPolicy.MODERN,
        )
        val plan = requireNotNull(ModChainRuntimePlan.of(chain))
        assertTrue(plan.functionTable.all().any { it.key == "MOD_NEW" }, "新增函数进 FunctionTable")
        assertTrue(chain.linked!!.moduleOrder == listOf("mod.add"), "链接序含容器补丁包")
        assertTrue(!plan.hasOverrides && plan.functionDispatch == null, "纯加性 = 直调快路径")

        val (result, lines) = run(plan.program, plan.functionDispatch)
        assertEquals(111L, result, "base 行为零漂移")
        assertEquals(listOf("BASE-X"), lines)

        val newFnIndex = plan.program.functions.indexOfFirst { it.name == "MOD_NEW" }
        assertTrue(newFnIndex >= 0, "新增函数进 FunctionTable")
        val (newResult, newLines) = run(plan.program, plan.functionDispatch, newFnIndex)
        assertEquals(0L, newResult)
        assertEquals(listOf("MOD-NEW"), newLines, "新增函数真 VM 可调")
    }

    // ------------------------------------------------------------------
    // 覆写 + CALL SUPER（判据 1 降级位：核心单元级；声明通道为测试侧 manifest 注入）
    // ------------------------------------------------------------------

    @Test
    fun `覆写容器适配_签名导出进指纹表_声明经manifest注入后SUPER回base`() {
        val adapted = adapt(
            ModuleContainerProgramFixture.containerBytes(
                "mod.ovr",
                ModuleContainerProgramFixture.overrideProgram(withSuper = true),
                exports = listOf(ExportMeta(kind = 0, name = "X", signature = xCanonical)),
            ),
            sourceName = "mods/ovr.patch",
        )
        assertEquals(
            BaseSignatureCatalog.fingerprint(xCanonical),
            adapted.compiled.signatures["X"],
            "导出表 SIG1 规范串 → 指纹表（MODERN 覆盖闸的模块侧输入）",
        )

        // 声明通道：冻结 v3 格式无覆盖声明位，这里以 manifest 形态注入（M2-4/格式扩展
        // 立项前的机制面验证；生产免 manifest 路对覆写 fail-closed，app e2e 钉死）。
        val declared = ModuleDescriptor(
            packageId = "mod.ovr",
            overrideFunctions = setOf("X"),
        )
        val source = ManifestSource("mod.ovr", ManifestCodec.encode(declared))
        val baseSignatureOf = BaseSignatureCatalog.signatureHashOf(
            BaseSignatureCatalog.parse("end\nsignatures 1\nsignature X $xCanonical\n"),
        )
        val chain = ModChainAssembler.assemble(
            sources = listOf(source),
            compiledModules = listOf(adapted.compiled),
            base = ModuleContainerProgramFixture.baseProgram(),
            baseMetas = baseMetas,
            policy = ResolverPolicy.MODERN,
            baseSignatureOf = baseSignatureOf,
        )
        val plan = requireNotNull(ModChainRuntimePlan.of(chain))
        assertTrue(plan.hasOverrides, "声明覆盖 + 签名一致 ⇒ 覆盖成立")
        assertTrue(plan.functionDispatch!!.indices.any { plan.functionDispatch[it] != it }, "dispatch 非恒等")

        val (result, lines) = run(plan.program, plan.functionDispatch)
        assertEquals(
            listOf("MOD-X", "BASE-X", "111"),
            lines,
            "Mod 体先打印，CALL SUPER 回 base 打印 BASE-X，base 返回值 111 再由 Mod 体打印",
        )
        assertEquals(111L, result, "base X 的返回值经 SUPER 链流回根")
    }

    // ------------------------------------------------------------------
    // 对账负控：容器自描述 vs 载荷实体脱节
    // ------------------------------------------------------------------

    @Test
    fun `对账负控 四计数与载荷不符整包拒`() {
        val m = ModuleContainerProgramFixture.model("mod.add", ModuleContainerProgramFixture.additiveProgram())
        val bad = m.copy(pools = m.pools!!.copy(longCount = m.pools.longCount + 1))
        // decode 可过（读侧不查四计数 vs 载荷），适配层对账拒。
        val decoded = ModuleContainerCodec.decode(ModuleContainerCodec.writeBytes(bad))
        val e = assertFailsWith<IllegalArgumentException> {
            ModuleContainerAdapter.adapt(decoded, "mods/bad.patch")
        }
        assertTrue(e.message!!.contains("long 计数与载荷不符"), "报错点名池与两侧值：${e.message}")
    }

    @Test
    fun `对账负控 codeWords与载荷指令流不符整包拒`() {
        val m = ModuleContainerProgramFixture.model("mod.add", ModuleContainerProgramFixture.additiveProgram())
        val trimmed = m.pools!!.codeWords.dropLast(1)
        val bad = m.copy(pools = m.pools.copy(codeWords = trimmed))
        val decoded = ModuleContainerCodec.decode(ModuleContainerCodec.writeBytes(bad))
        val e = assertFailsWith<IllegalArgumentException> {
            ModuleContainerAdapter.adapt(decoded, "mods/bad.patch")
        }
        assertTrue(e.message!!.contains("codeWords"), "报错点名指令流对账：${e.message}")
    }

    @Test
    fun `对账负控 重定位pc越出载荷指令区间整包拒`() {
        val m = ModuleContainerProgramFixture.model("mod.add", ModuleContainerProgramFixture.additiveProgram())
        val relocs = m.relocations.toMutableList()
        relocs[0] = relocs[0].copy(pc = 99)
        val bad = m.copy(relocations = relocs)
        val decoded = ModuleContainerCodec.decode(ModuleContainerCodec.writeBytes(bad))
        val e = assertFailsWith<IllegalArgumentException> {
            ModuleContainerAdapter.adapt(decoded, "mods/bad.patch")
        }
        assertTrue(e.message!!.contains("relocations[0].pc=99"), "报错点名越界 pc：${e.message}")
    }

    @Test
    fun `对账负控 重定位操作码与载荷不符整包拒`() {
        val m = ModuleContainerProgramFixture.model("mod.add", ModuleContainerProgramFixture.additiveProgram())
        val relocs = m.relocations.toMutableList()
        // 把 STRING 类重定位指向 _RET 所在 pc（该槽机器分类同为 NONE/STRING 中语义相容的形态
        // 以能过 decode 机器闸为准）——对账层查「载荷该 pc 的实际操作码」。
        val retPc = ModuleContainerProgramFixture.additiveProgram().let { p ->
            var found = -1
            val words = p.codeSource.toIntArray()
            for (pc in 0 until p.instructionCount) {
                if (words[pc * 4] == Opcode._RET) {
                    found = pc
                    break
                }
            }
            found
        }
        relocs[0] = relocs[0].copy(pc = retPc)
        val bad = m.copy(relocations = relocs)
        val decoded = ModuleContainerCodec.decode(ModuleContainerCodec.writeBytes(bad))
        val e = assertFailsWith<IllegalArgumentException> {
            ModuleContainerAdapter.adapt(decoded, "mods/bad.patch")
        }
        assertTrue(
            e.message!!.contains("relocations[0] 声明"),
            "报错点名声明与实际操作码不符：${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // 余量负控：LINKED 态 / 导入哨兵载荷
    // ------------------------------------------------------------------

    @Test
    fun `余量负控 LINKED态容器整包拒并点名消费面未接线`() {
        val bytes = ModuleContainerProgramFixture.linkedBytesFor("linked.cache")
        val decoded = ModuleContainerCodec.decode(bytes)
        val e = assertFailsWith<IllegalArgumentException> {
            ModuleContainerAdapter.adapt(decoded, "mods/linked.patch")
        }
        assertTrue(e.message!!.contains("LINKED_PROGRAM 消费面未接线"), "点名余量：${e.message}")
    }

    @Test
    fun `余量负控 导入哨兵载荷经装载闸fail_closed`() {
        // 容器格式自身接受导入哨兵（validateCallTargets 放行），但 poolBlob 直存 .erbc 的
        // 载荷被 ErbcReader 装载闸拒（superfix 裁定只放行两个 super 哨兵）—— 余量登记，
        // 见 ModuleContainerAdapter KDoc「已登记余量」。
        val b = com.eratw.port.core.code.CodeBuilder()
        b.declareFunction("MOD_A", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.external(0), 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        val program = b.build(0)
        val bytes = ModuleContainerProgramFixture.containerBytes(
            "mod.ext",
            program,
            imports = listOf("EXT_FN"),
        )
        val decoded = ModuleContainerCodec.decode(bytes)
        val e = assertFailsWith<IllegalArgumentException> {
            ModuleContainerAdapter.adapt(decoded, "mods/ext.patch")
        }
        assertTrue(
            e.message!!.contains("四池载荷不是合法零基线 .erbc 程序"),
            "报错带源名与 fail-closed 定位：${e.message}",
        )
    }

    // ------------------------------------------------------------------
    // FuncMeta 还原口径：事件成员 / 入口位
    // ------------------------------------------------------------------

    @Test
    fun `事件成员与入口位按命名约定还原`() {
        val b = com.eratw.port.core.code.CodeBuilder()
        b.declareFunction("EVENTFIRST#0", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        b.declareFunction("EVENTFIRST", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        val program = b.build(0)
        val adapted = adapt(ModuleContainerProgramFixture.containerBytes("mod.event", program))

        val member = adapted.compiled.functions[0]
        assertTrue(member.isEvent, "成员位 = 事件（EVENTFIRST#0 命名约定）")
        assertEquals("EVENTFIRST", member.key, "成员键取 # 前根名")
        assertEquals("EVENTFIRST", member.name)

        val entry = adapted.compiled.functions[1]
        assertTrue(!entry.isEvent, "裸事件名 = 合成入口位（isEvent=false，接管入口）")
        assertEquals("EVENTFIRST", entry.key)
        assertEquals("·event-entry·", entry.position, "入口位带合成标记")
    }

    // ------------------------------------------------------------------
    // 防御性锚：重定位派生夹具与机器表同源（缺池引用面的容器不该有 relocation）
    // ------------------------------------------------------------------

    @Test
    fun `夹具锚 重定位项按机器表派生且FUNCTION槽进callTargets`() {
        val m = ModuleContainerProgramFixture.model("mod.add", ModuleContainerProgramFixture.additiveProgram())
        // additive 程序：_PRINT_LITERAL(a=STRING) / _RET —— 重定位恰一条 STRING。
        assertTrue(m.relocations.all { OpcodeRelocation.of(it.opcode).at(it.slot) == it.category })
        assertTrue(m.relocations.none { it.category == PoolRef.FUNCTION }, "加性程序无 _CALL")
        assertTrue(m.callTargets.isEmpty())
    }
}
