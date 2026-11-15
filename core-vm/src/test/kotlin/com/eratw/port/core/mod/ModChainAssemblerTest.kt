package com.eratw.port.core.mod

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.LinkInputs
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.LinkableModule
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinkOrder
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimSpec
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ModChainAssembler 的出口判据（契约第 3.3 节逐条 Gate + 第 3.2 节裁定 F 失败整包）。
// 夹具一律走 ManifestCodec.encode(descriptor) 生成 ManifestSource.bytes（裁定 E）：
// 不硬编码 magic、段数、行结构；本车 worktree 的 RT2MANIFEST/1 与合流后的 2 自动适配。
// 零基线 Program 用 CodeBuilder 真实编译（照 ModuleLinkerTest 的夹具口径），
// FuncMeta 的 key 用大写；同名事件一对多靠「不同 declareFunction 名 + 相同 FuncMeta.key」
// 实现，绕开 CodeBuilder 对函数名的去重约束（函数名与规范化键本就两条面）。

class ModChainAssemblerTest {

    private data class Fn(val declName: String, val meta: FuncMeta)

    /** 造零基线模块：每个函数体一条 _RET，函数名互异，FuncMeta.index 稠密 0 基。 */
    private fun compileModule(fns: List<Fn>): Program {
        val b = CodeBuilder()
        var entry = 0
        for ((i, f) in fns.withIndex()) {
            val idx = b.declareFunction(f.declName, b.here(), arity = 0, slotCount = 1)
            if (i == 0) entry = idx
            b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        }
        return b.build(entry)
    }

    /** base：ENTRY@0（RET）+ BASEFN@1（RET）。 */
    private fun compileBase(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("BASEFN", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        return b.build(entry)
    }

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "BASEFN"),
    )

    /** 裁定 E：载体字节只从 encode 来，不碰 magic / 段结构。 */
    private fun sourceOf(name: String, descriptor: ModuleDescriptor): ManifestSource =
        ManifestSource(name, ManifestCodec.encode(descriptor))

    private fun singleModule(packageId: String): Pair<ManifestSource, CompiledModule> {
        val d = ModuleDescriptor(packageId = packageId)
        val fns = listOf(Fn("ANEW", FuncMeta(index = 0, key = "ANEW")))
        return sourceOf("$packageId.manifest", d) to
            CompiledModule(packageId, compileModule(fns), fns.map { it.meta })
    }

    private fun assembleChain(
        sources: List<ManifestSource>,
        compiled: List<CompiledModule>,
        base: Program = compileBase(),
        metas: List<FuncMeta> = baseMetas,
        policy: ResolverPolicy = ResolverPolicy.MODERN,
        trace: ModChainTrace = ModChainTrace(),
    ): AssembledChain = ModChainAssembler.assemble(
        sources = sources,
        compiledModules = compiled,
        base = base,
        baseMetas = metas,
        policy = policy,
        trace = trace,
    )

    /** 直接走既有链接器的对拍用模块（不改任何既有文件）。 */
    private class DirectModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String> = emptyMap(),
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    // ------------------------------------------------------------------
    // Gate 1：五步顺序可观测
    // ------------------------------------------------------------------

    @Test
    fun `五步顺序可观测钉住 decode 到 EventRegistry of`() {
        val (src, cm) = singleModule("mod.a")
        val trace = ModChainTrace()
        val result = assembleChain(
            sources = listOf(src),
            compiled = listOf(cm),
            trace = trace,
        )
        assertEquals(
            listOf("decode", "load(freeze)", "validate", "link", "EventRegistry.of"),
            trace.snapshot(),
        )
        // 事实产物钉顺序：注册表消费的是 link 的 catalog，descriptor 来自 decode。
        assertFalse(result.byPassed)
        assertNotNull(result.snapshot)
        assertNotNull(result.linked)
        assertNotNull(result.eventRegistry)
        assertEquals(listOf("mod.a"), result.snapshot!!.packageIds)
        assertEquals(result.linked!!.catalog.events.keys, result.eventRegistry!!.eventKeys)
        assertEquals(listOf("mod.a"), result.descriptors.map { it.packageId })
    }

    // ------------------------------------------------------------------
    // Gate 2：双 mod 组合
    // ------------------------------------------------------------------

    @Test
    fun `双 mod 组合 moduleOrder 与装载序及 plan 一致且 catalog 含两包候选`() {
        val dP = ModuleDescriptor(packageId = "mod.p")
        val dQ = ModuleDescriptor(packageId = "mod.q")
        val pFns = listOf(Fn("PFN", FuncMeta(index = 0, key = "PFN")))
        val qFns = listOf(Fn("QFN", FuncMeta(index = 0, key = "QFN")))
        val result = assembleChain(
            sources = listOf(sourceOf("P.manifest", dP), sourceOf("Q.manifest", dQ)),
            compiled = listOf(
                CompiledModule("mod.p", compileModule(pFns), pFns.map { it.meta }),
                CompiledModule("mod.q", compileModule(qFns), qFns.map { it.meta }),
            ),
        )
        val linked = assertNotNull(result.linked)
        assertEquals(listOf("mod.p", "mod.q"), linked.moduleOrder)
        assertEquals(
            ModuleLinkOrder.plan(result.descriptors).map { it.packageId },
            linked.moduleOrder,
        )
        val owners = linked.catalog.candidates.map { it.ownerPackageId }.toSet()
        assertTrue("mod.p" in owners)
        assertTrue("mod.q" in owners)
        assertFalse(result.hasOverrides)
    }

    // ------------------------------------------------------------------
    // Gate 3：事件一对多四桶 ONLY / PRI / NORMAL / LATER，同桶按链接序
    // ------------------------------------------------------------------

    @Test
    fun `事件一对多四桶 ONLY PRI NORMAL LATER 且同桶按链接序`() {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("BASE_EV_ONLY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        b.declareFunction("BASE_EV_NORMAL", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        val base = b.build(entry)
        val metas = listOf(
            FuncMeta(index = 0, key = "ENTRY"),
            FuncMeta(index = 1, key = "EV", isEvent = true, isOnly = true),
            FuncMeta(index = 2, key = "EV", isEvent = true),
        )
        // 同一事件键 EV 在三个来源各有成员，跨四桶；NORMAL 桶三人验证同桶链接序。
        val aFns = listOf(
            Fn("A_EV_PRI", FuncMeta(index = 0, key = "EV", isEvent = true, isPri = true)),
            Fn("A_EV_NORMAL", FuncMeta(index = 1, key = "EV", isEvent = true)),
            Fn("A_EV_LATER", FuncMeta(index = 2, key = "EV", isEvent = true, isLater = true)),
        )
        val bFns = listOf(
            Fn("B_EV_NORMAL", FuncMeta(index = 0, key = "EV", isEvent = true, isSingle = true)),
            Fn("B_EV_LATER", FuncMeta(index = 1, key = "EV", isEvent = true, isLater = true)),
        )
        val dA = ModuleDescriptor(packageId = "mod.a")
        val dB = ModuleDescriptor(packageId = "mod.b")
        val result = assembleChain(
            sources = listOf(sourceOf("A.manifest", dA), sourceOf("B.manifest", dB)),
            compiled = listOf(
                CompiledModule("mod.a", compileModule(aFns), aFns.map { it.meta }),
                CompiledModule("mod.b", compileModule(bFns), bFns.map { it.meta }),
            ),
            base = base,
            metas = metas,
        )
        val registry = assertNotNull(result.eventRegistry)
        val members = registry.membersOf("EV")
        assertEquals(7, members.size)
        assertEquals(listOf(0, 1, 2, 2, 2, 3, 3), members.map { it.bucket })
        assertEquals(
            listOf(
                ModuleLinker.BASE_OWNER_ID, "mod.a",
                ModuleLinker.BASE_OWNER_ID, "mod.a", "mod.b",
                "mod.a", "mod.b",
            ),
            members.map { it.ownerPackageId },
        )
        // 同桶按链接序 = 绝对物理下标递增（不硬编码具体数值，只钉单调性）。
        val normalPhys = members.filter { it.bucket == 2 }.map { it.physicalIndex }
        assertEquals(normalPhys.sorted(), normalPhys)
        assertEquals(
            result.linked!!.catalog.eventMembersOf("EV").map { it.physicalIndex },
            members.map { it.physicalIndex }.sorted(),
        )
        assertTrue(registry.hasSingle("EV"))
        assertTrue("EV" in registry.eventKeys)
        assertFalse(registry.hasSingle("UNKNOWN"))
        // 事件不参与覆盖，无同名非事件覆盖时 hasOverrides 仍为 false。
        assertFalse(result.hasOverrides)
    }

    // ------------------------------------------------------------------
    // Gate 4：HAS_OVERRIDES 双形
    // ------------------------------------------------------------------

    @Test
    fun `只加新函数 HAS_OVERRIDES 为 false 且 dispatch 恒等`() {
        val (src, cm) = singleModule("mod.c")
        val result = assembleChain(sources = listOf(src), compiled = listOf(cm))
        assertFalse(result.hasOverrides)
        val dispatch = result.linked!!.catalog.dispatch
        assertTrue(dispatch.indices.all { dispatch[it] == it })
    }

    @Test
    fun `同名显式覆盖 HAS_OVERRIDES 为 true 且 winnerIndexOf 指向胜者`() {
        // 覆盖声明经 encode 落到 override-functions 段，decode 后仍生效；两侧签名一致。
        val dA = ModuleDescriptor(packageId = "mod.a")
        val dB = ModuleDescriptor(packageId = "mod.b", overrideFunctions = setOf("X"))
        val aFns = listOf(Fn("X", FuncMeta(index = 0, key = "X")))
        val bFns = listOf(Fn("X", FuncMeta(index = 0, key = "X")))
        val result = assembleChain(
            sources = listOf(sourceOf("A.manifest", dA), sourceOf("B.manifest", dB)),
            compiled = listOf(
                CompiledModule("mod.a", compileModule(aFns), aFns.map { it.meta }, mapOf("X" to "sig-x")),
                CompiledModule("mod.b", compileModule(bFns), bFns.map { it.meta }, mapOf("X" to "sig-x")),
            ),
        )
        assertTrue(result.hasOverrides)
        val catalog = result.linked!!.catalog
        // base 两函数 + mod.a 的 X = 物理 2；胜者 mod.b 的 X = 物理 3。
        assertEquals(3, catalog.winnerIndexOf("X"))
        assertEquals(3, catalog.dispatch[2])
        assertEquals(2, catalog.superIndexOf(3))
    }

    // ------------------------------------------------------------------
    // Gate 5：base 段逐指令逐位不变
    // ------------------------------------------------------------------

    @Test
    fun `链接后 base 段逐指令逐位不变`() {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("BASEFN", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._SET_INT, 0, b.constant(42L), 0, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        val base = b.build(entry)
        val metas = listOf(
            FuncMeta(index = 0, key = "ENTRY"),
            FuncMeta(index = 1, key = "BASEFN"),
        )
        val before = (0 until base.instructionCount).map {
            listOf(base.op(it), base.a(it), base.b(it), base.c(it))
        }
        val (src, cm) = singleModule("mod.a")
        val result = assembleChain(
            sources = listOf(src),
            compiled = listOf(cm),
            base = base,
            metas = metas,
        )
        val linked = result.linked!!.program
        val after = (0 until base.instructionCount).map {
            listOf(linked.op(it), linked.a(it), linked.b(it), linked.c(it))
        }
        assertEquals(before, after)
        assertEquals(base.entryFunction, linked.entryFunction)
        for (i in base.functions.indices) {
            assertEquals(base.functions[i].name, linked.functions[i].name)
            assertEquals(base.functions[i].entryPc, linked.functions[i].entryPc)
        }
        // 池引用值逐位（base 常量池下标 0 = 42）。
        assertEquals(base.constant(base.b(1)), linked.constant(linked.b(1)))
    }

    // ------------------------------------------------------------------
    // Gate 6：无 Mod 完全旁路
    // ------------------------------------------------------------------

    @Test
    fun `无 Mod 完全旁路返回 base 原实例且无任何链接痕迹`() {
        val base = compileBase()
        val trace = ModChainTrace()
        val result = assembleChain(
            sources = emptyList(),
            compiled = emptyList(),
            base = base,
            trace = trace,
        )
        assertTrue(result.byPassed)
        assertSame(base, result.program)
        assertNull(result.snapshot)
        assertNull(result.linked)
        assertNull(result.eventRegistry)
        assertTrue(result.descriptors.isEmpty())
        assertFalse(result.hasOverrides)
        // 轨迹证明五步都没走（若误调 link 会撞空集拒绝而抛）。
        assertEquals(listOf("bypass"), trace.snapshot())
        assertEquals(2, result.program.instructionCount)
        assertEquals(Opcode._RET, result.program.op(0))
        assertEquals(Opcode._RET, result.program.op(1))
        assertEquals(base.entryFunction, result.program.entryFunction)
        assertEquals(base.functions.map { it.name }, result.program.functions.map { it.name })
        assertEquals(base.constantCount, result.program.constantCount)
        assertEquals(base.stringCount, result.program.stringCount)
    }

    // ------------------------------------------------------------------
    // Gate 7 + 裁定 F：失败整包拒绝
    // ------------------------------------------------------------------

    @Test
    fun `坏 manifest 整包拒绝并保留 ManifestFormatException`() {
        val bad = ManifestSource("broken.manifest", "NOT_THE_MANIFEST\n".toByteArray())
        val (_, cm) = singleModule("mod.a")
        val ex = assertFailsWith<ManifestFormatException> {
            assembleChain(sources = listOf(bad), compiled = listOf(cm))
        }
        assertTrue(ex.message!!.isNotBlank())
    }

    @Test
    fun `配对不齐 缺一个 CompiledModule 整包拒绝`() {
        val dA = ModuleDescriptor(packageId = "mod.a")
        val dB = ModuleDescriptor(packageId = "mod.b")
        val (_, cm) = singleModule("mod.a")
        val ex = assertFailsWith<IllegalArgumentException> {
            assembleChain(
                sources = listOf(sourceOf("A.manifest", dA), sourceOf("B.manifest", dB)),
                compiled = listOf(cm),
            )
        }
        assertTrue("配对" in ex.message!!, ex.message)
    }

    @Test
    fun `配对不齐 packageId 对不上整包拒绝`() {
        val dA = ModuleDescriptor(packageId = "mod.a")
        val fns = listOf(Fn("ANEW", FuncMeta(index = 0, key = "ANEW")))
        val ex = assertFailsWith<IllegalArgumentException> {
            assembleChain(
                sources = listOf(sourceOf("A.manifest", dA)),
                compiled = listOf(
                    CompiledModule("mod.z", compileModule(fns), fns.map { it.meta }),
                ),
            )
        }
        assertTrue("mod.a" in ex.message!!, ex.message)
    }

    @Test
    fun `FuncMeta index 不稠密 被装配阶段 validate 整包拒绝`() {
        val trace = ModChainTrace()
        val (src, cm) = singleModule("mod.a")
        val ex = assertFailsWith<IllegalArgumentException> {
            assembleChain(
                sources = listOf(src),
                compiled = listOf(
                    CompiledModule(
                        cm.packageId,
                        cm.program,
                        listOf(FuncMeta(index = 1, key = "ANEW")),
                    ),
                ),
                trace = trace,
            )
        }
        assertTrue("稠密" in ex.message!!, ex.message)
        // validate 在装配阶段就抛：没有 validate 点、没进 link。
        assertEquals(listOf("decode", "load(freeze)"), trace.snapshot())
    }

    @Test
    fun `模块产物基线非 0 被装配阶段 validate 整包拒绝`() {
        val trace = ModChainTrace()
        val d = ModuleDescriptor(packageId = "mod.a")
        val b = CodeBuilder(codeBase = 4)
        val entry = b.declareFunction("ANEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val prog = b.build(entry)
        val ex = assertFailsWith<IllegalArgumentException> {
            assembleChain(
                sources = listOf(sourceOf("A.manifest", d)),
                compiled = listOf(
                    CompiledModule("mod.a", prog, listOf(FuncMeta(index = 0, key = "ANEW"))),
                ),
                trace = trace,
            )
        }
        assertTrue("零基线" in ex.message!!, ex.message)
        assertEquals(listOf("decode", "load(freeze)"), trace.snapshot())
    }

    @Test
    fun `声明事务 freeze 冲突整包拒绝`() {
        // 单 descriptor 内两个同名 global-dim ⇒ freeze 的 Global×Global 冲突，
        // onCommit 不触发，异常整包抛，拿不到半成品。
        val clash = DimSpec(name = "CLASH", isStr = false, dims = intArrayOf(1))
        val d = ModuleDescriptor(packageId = "mod.a", globalDims = listOf(clash, clash))
        val (_, cm) = singleModule("mod.a")
        val ex = assertFailsWith<DeclarationConflictException> {
            assembleChain(
                sources = listOf(sourceOf("A.manifest", d)),
                compiled = listOf(cm),
            )
        }
        assertTrue(ex.conflicts.isNotEmpty())
    }

    @Test
    fun `link 缺硬依赖整包拒绝`() {
        val d = ModuleDescriptor(packageId = "mod.a", requires = listOf("missing.pkg"))
        val (_, cm) = singleModule("mod.a")
        val ex = assertFailsWith<LinkOrderException> {
            assembleChain(
                sources = listOf(sourceOf("A.manifest", d)),
                compiled = listOf(cm),
            )
        }
        assertTrue("missing.pkg" in ex.message!!, ex.message)
    }

    // ------------------------------------------------------------------
    // Gate 8：与既有链接器同参对拍，证明只接线、不改判定、零回归
    // ------------------------------------------------------------------

    @Test
    fun `装配器 link 与直接 ModuleLinker 同参逐项一致 零回归对拍`() {
        val base = compileBase()
        val dA = ModuleDescriptor(packageId = "mod.a")
        val fns = listOf(Fn("ANEW", FuncMeta(index = 0, key = "ANEW")))
        val progA = compileModule(fns)
        val metasA = fns.map { it.meta }
        val direct = ModuleLinker.link(
            LinkInputs(
                base,
                baseMetas,
                listOf(DirectModule(dA, progA, metasA)),
                ResolverPolicy.MODERN,
            ),
        )
        val via = assembleChain(
            sources = listOf(sourceOf("A.manifest", dA)),
            compiled = listOf(CompiledModule("mod.a", progA, metasA)),
            base = base,
        ).linked!!
        assertEquals(direct.moduleOrder, via.moduleOrder)
        assertEquals(direct.hasOverrides, via.hasOverrides)
        assertEquals(direct.catalog.winnerIndexOf("ANEW"), via.catalog.winnerIndexOf("ANEW"))
        assertEquals(direct.program.instructionCount, via.program.instructionCount)
        for (pc in 0 until base.instructionCount) {
            assertEquals(direct.program.op(pc), via.program.op(pc))
            assertEquals(direct.program.a(pc), via.program.a(pc))
            assertEquals(direct.program.b(pc), via.program.b(pc))
            assertEquals(direct.program.c(pc), via.program.c(pc))
        }
        assertEquals(
            EventRegistry.of(direct.catalog).eventKeys,
            EventRegistry.of(via.catalog).eventKeys,
        )
    }
}
