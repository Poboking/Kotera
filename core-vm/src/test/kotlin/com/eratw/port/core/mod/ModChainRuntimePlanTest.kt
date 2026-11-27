package com.eratw.port.core.mod

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.LinkInputs
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.LinkableModule
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.DimSpec
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-5 余量一的出口判据：**链接产物三视图（program / FunctionTable / dispatch）一致，
 * 并且真的能在 VM 上把覆盖分派出去**。
 *
 * 这条用例补的是 G 车第一批留下的缝：`functionDispatch` 已经能进 VM、`SymbolCatalog`
 * 已经产出 dispatch/superChain，但**没有人把「链接产物」翻成运行期三张表**——
 * 于是生产 dispatch 恒 null、`hasOverrides` 在正式会话不可达。本文件走的是真链路：
 * [ModuleLinker] 真链接 + [ModChainRuntimePlan] 真派生 + [VirtualMachine] 真执行，
 * 不用手搓 dispatch 数组冒充产物（`VmDispatchTest` 已锁「表能干活」，这里锁「表从哪来」）。
 *
 * 断言面：
 * - 覆盖声明 + 签名一致 ⇒ 同名函数被覆盖，ENTRY 调到覆盖实现（222）；
 * - 模块体内 `CALL_SUPER` ⇒ 链接器回填前实现并置 DIRECT 位，经三表后仍回 111；
 * - 只加新函数（无覆盖）⇒ `hasOverrides=false`、`functionDispatch=null`（性能闸快路径）；
 * - 装配链路（真 Manifest 解码 + 真声明事务）→ plan 可派生；无 Mod 旁路 ⇒ null；
 * - 三表脱节（program 函数表被换小）⇒ 构造期拒绝，不留给运行期错函数；
 * - 链接产物 catalog 随链保留（同一实例；withPrivateDims 重建路也不弃）（SUPCAT-W 判据 1）。
 */
class ModChainRuntimePlanTest {

    private class RecordingHost : VmHost {
        override val evalContext: EvalContext? = null

        override fun print(text: String) = Unit

        override fun onFunctionEnter(funcIndex: Int) = Unit
    }

    private class TestModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String> = emptyMap(),
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** base：ENTRY@0（`_CALL X` → R0 → `_RET R0`）+ X@1（R0 = 111）。 */
    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 2)
        b.emit(Opcode._RET, 0, 0, 0, 3)
        return b.build(entry)
    }

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"), FuncMeta(index = 1, key = "X"))

    private val baseSignature: (String) -> String = { key ->
        if (key.uppercase(Locale.ROOT) == "X") "sig-x" else ""
    }

    /** 覆盖 X 的实现：R0 = 222（不调前实现）。 */
    private fun overrideProgram(constant: Long): Program {
        val b = CodeBuilder()
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(constant), 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        return b.build(0)
    }

    /** 覆盖 X 但体内 `CALL SUPER` 回前实现，把它的结果落 R0 原样返回（c = 结果槽 0）。 */
    private fun overrideWithSuperProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun overrideModule(program: Program): LinkableModule = TestModule(
        descriptor = ModuleDescriptor(packageId = "mod.ovr", overrideFunctions = setOf("X")),
        program = program,
        functions = listOf(FuncMeta(index = 0, key = "X")),
        signatures = mapOf("X" to "sig-x"),
    )

    private fun linked(modules: List<LinkableModule>) =
        ModuleLinker.link(LinkInputs(baseProgram(), baseMetas, modules, ResolverPolicy.MODERN, baseSignature))

    private fun runEntry(plan: ModChainRuntimePlan): Long =
        VirtualMachine(plan.program, RecordingHost(), functionDispatch = plan.functionDispatch).run(0)

    // ------------------------------------------------------------------
    // 分派真生效
    // ------------------------------------------------------------------

    @Test
    fun `覆盖声明生效且三表长度一致`() {
        val linked = linked(listOf(overrideModule(overrideProgram(222L))))
        val plan = ModChainRuntimePlan.of(linked)

        assertTrue(plan.hasOverrides, "声明了覆盖且确实改写了胜者 ⇒ hasOverrides")
        val dispatch = requireNotNull(plan.functionDispatch) { "有覆盖必须带 dispatch 表" }
        assertEquals(plan.program.functions.size, dispatch.size, "三表长度必须一致")
        // 幂等式：表里每个值指向自己。
        for ((from, to) in dispatch.withIndex()) {
            assertEquals(to, dispatch[to], "dispatch 幂等式在 $from 上不成立")
        }
        // FunctionTable 与服务表同下标：X 的元数据在覆盖实现上。
        assertEquals(222L, runEntry(plan), "ENTRY 调 X 必须进覆盖实现")
    }

    @Test
    fun `CALL_SUPER 经三表回到前实现`() {
        val plan = ModChainRuntimePlan.of(linked(listOf(overrideModule(overrideWithSuperProgram()))))
        // 覆盖体内 CALL SUPER 已被链接器回填成「前实现 + DIRECT 位」：最终执行入口 = 111。
        assertEquals(111L, runEntry(plan), "CALL SUPER 必须真的跑到 base 的 X")
    }

    @Test
    fun `无覆盖时 dispatch 为 null 走直调快路径`() {
        val b = CodeBuilder()
        b.declareFunction("CNEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        val plan = ModChainRuntimePlan.of(
            linked(
                listOf(
                    TestModule(
                        descriptor = ModuleDescriptor(packageId = "mod.add"),
                        program = b.build(0),
                        functions = listOf(FuncMeta(index = 0, key = "CNEW")),
                    ),
                ),
            ),
        )
        assertTrue(!plan.hasOverrides, "只加新函数不构成覆盖")
        assertNull(plan.functionDispatch, "无覆盖必须走 VM 直调快路径（性能闸）")
        assertEquals(111L, runEntry(plan), "无覆盖时 base 行为不变")
        assertTrue(
            plan.program.functions.size == 3,
            "链接后函数表 = base 2 + 模块 1（实际 ${plan.program.functions.size}）",
        )
    }

    // ------------------------------------------------------------------
    // 装配链路：真 Manifest 解码 → 真声明事务 → plan
    // ------------------------------------------------------------------

    @Test
    fun `装配链路可派生且无Mod旁路返回null`() {
        val descriptor = ModuleDescriptor(packageId = "mod.ovr", overrideFunctions = setOf("X"))
        val source = ManifestSource("mod.ovr.manifest", ManifestCodec.encode(descriptor))
        val chain = ModChainAssembler.assemble(
            sources = listOf(source),
            compiledModules = listOf(
                CompiledModule(
                    packageId = "mod.ovr",
                    program = overrideProgram(222L),
                    functions = listOf(FuncMeta(index = 0, key = "X")),
                    signatures = mapOf("X" to "sig-x"),
                ),
            ),
            base = baseProgram(),
            baseMetas = baseMetas,
            baseSignatureOf = baseSignature,
        )
        val plan = requireNotNull(ModChainRuntimePlan.of(chain)) { "非旁路装配必须派生运行期计划" }
        assertEquals(222L, runEntry(plan))

        // 旁路：无源无模块 ⇒ program 就是 base，plan = null（调用方保持既有三表）。
        val bypass = ModChainAssembler.assemble(
            sources = emptyList(),
            compiledModules = emptyList(),
            base = baseProgram(),
            baseMetas = baseMetas,
        )
        assertNull(ModChainRuntimePlan.of(bypass), "无 Mod 必须完全旁路")
    }

    // ------------------------------------------------------------------
    // SUPCAT-W 判据 1：链接产物 catalog 随链保留（同一实例，派生不复制）
    // ------------------------------------------------------------------

    @Test
    fun `plan保留链接产物catalog同一实例`() {
        val linked = linked(listOf(overrideModule(overrideProgram(222L))))
        val plan = ModChainRuntimePlan.of(linked)
        assertTrue(
            plan.catalog === linked.catalog,
            "SUPCAT-W 判据 1：plan.catalog 必须是链接产物同一实例（只共享引用，不复制不二次装配）",
        )
    }

    @Test
    fun `KI-GLOBAL-DIM—of(chain)聚合descriptor的globalDims且不弃catalog`() {
        val linked = linked(listOf(overrideModule(overrideProgram(222L))))
        // 第三方口上补丁的 global #DIM 随 descriptor 到装配链：of(chain) 必须把它们
        // 聚合进 plan.globalDims（GameSession 运行期 declare 的消费者），且重建路不弃 catalog。
        val gd = DimSpec(
            "K53_CHILD_PERSONALITY",
            isStr = true, dims = intArrayOf(4), isGlobal = true, isSavedata = true,
        )
        val gconst = DimSpec("K53C_TEST", isStr = false, dims = intArrayOf(), isConst = true)
        val chain = AssembledChain(
            program = linked.program,
            descriptors = listOf(
                ModuleDescriptor(
                    packageId = "mod.kojo",
                    globalDims = listOf(gd, gconst),
                ),
            ),
            snapshot = null,
            linked = linked,
            eventRegistry = EventRegistry.of(linked.catalog),
            hasOverrides = false,
            byPassed = false,
        )
        val plan = requireNotNull(ModChainRuntimePlan.of(chain))
        assertEquals(2, plan.globalDims.size, "补丁全局 #DIM 必须聚合进 plan 供运行期 declare")
        assertTrue(plan.globalDims.any { it.name == "K53_CHILD_PERSONALITY" && it.isSavedata })
        assertTrue(plan.globalDims.any { it.name == "K53C_TEST" && it.isConst })
        assertTrue(plan.catalog === linked.catalog, "重建路必须载带 catalog 同一实例")
    }

    @Test
    fun `KI-GLOBAL-DIM—旁路fromLinked的globalDims恒空`() {
        val linked = linked(listOf(overrideModule(overrideProgram(222L))))
        val plan = ModChainRuntimePlan.of(linked)
        assertTrue(plan.globalDims.isEmpty(), "无 descriptor 的直派生路径不应带出全局 #DIM")
    }

    @Test
    fun `withPrivateDims重建路径也载带catalog`() {
        val linked = linked(listOf(overrideModule(overrideProgram(222L))))
        // descriptor 带 privateDims ⇒ of(chain) 走 withPrivateDims 重建路：判据 1 的
        // 「派生处保留，不再弃」必须在这条重建路上同样成立。
        val chain = AssembledChain(
            program = linked.program,
            descriptors = listOf(
                ModuleDescriptor(
                    packageId = "mod.ovr",
                    overrideFunctions = setOf("X"),
                    privateDims = mapOf(
                        "X" to listOf(DimSpec("PRIV_X", isStr = false, dims = intArrayOf())),
                    ),
                ),
            ),
            snapshot = null,
            linked = linked,
            eventRegistry = EventRegistry.of(linked.catalog),
            hasOverrides = true,
            byPassed = false,
        )
        val plan = requireNotNull(ModChainRuntimePlan.of(chain))
        assertEquals(1, plan.privateDims.size, "夹具前提：privateDims 非空才走 withPrivateDims 重建路")
        assertTrue(
            plan.catalog === linked.catalog,
            "重建路必须载带 catalog 同一实例（不许在 withPrivateDims 派生处弃掉）",
        )
    }

    // ------------------------------------------------------------------
    // F3 组合面：硬依赖不静默 + 加载序不改变语义
    // ------------------------------------------------------------------

    @Test
    fun `requires 指向缺失包时整组拒绝`() {
        val ex = assertFailsWith<LinkOrderException> {
            linked(
                listOf(
                    TestModule(
                        descriptor = ModuleDescriptor(
                            packageId = "mod.ext",
                            requires = listOf("mod.core"),
                        ),
                        program = overrideProgram(222L),
                        functions = listOf(FuncMeta(index = 0, key = "X")),
                        signatures = mapOf("X" to "sig-x"),
                    ),
                ),
            )
        }
        assertTrue("mod.core" in ex.message!!, "报错必须点名缺哪个包：${ex.message}")
    }

    @Test
    fun `requires 版本区间内通过真链路`() {
        fun coreModule() = TestModule(
            descriptor = ModuleDescriptor(
                packageId = "mod.core",
                version = "1.5.0",
                overrideFunctions = setOf("X"),
            ),
            program = overrideProgram(555L),
            functions = listOf(FuncMeta(index = 0, key = "X")),
            signatures = mapOf("X" to "sig-x"),
        )

        fun extModule() = TestModule(
            descriptor = ModuleDescriptor(
                packageId = "mod.ext",
                requires = listOf("mod.core@[1.0.0,2.0.0]"),
                overrideFunctions = setOf("X"),
            ),
            program = overrideWithSuperProgram(),
            functions = listOf(FuncMeta(index = 0, key = "X")),
            signatures = mapOf("X" to "sig-x"),
        )

        // 区间内 ⇒ 真链路通过，链接序 core → ext（requires 硬依赖）：
        // ext 的 CALL SUPER 回到 core 的实现（555），与裸 requires 同语义。
        val plan = ModChainRuntimePlan.of(linked(listOf(extModule(), coreModule())))
        assertEquals(555L, runEntry(plan), "区间内必须与裸 requires 同语义：CALL SUPER 回 core 实现")
    }

    @Test
    fun `requires 版本越界时整组拒绝并四元组点名`() {
        val ex = assertFailsWith<LinkOrderException> {
            linked(
                listOf(
                    TestModule(
                        descriptor = ModuleDescriptor(
                            packageId = "mod.ext",
                            requires = listOf("mod.core@[2.0.0,3.0.0]"),
                        ),
                        program = overrideProgram(222L),
                        functions = listOf(FuncMeta(index = 0, key = "X")),
                        signatures = mapOf("X" to "sig-x"),
                    ),
                    TestModule(
                        descriptor = ModuleDescriptor(
                            packageId = "mod.core",
                            version = "1.5.0",
                            overrideFunctions = setOf("X"),
                        ),
                        program = overrideProgram(555L),
                        functions = listOf(FuncMeta(index = 0, key = "X")),
                        signatures = mapOf("X" to "sig-x"),
                    ),
                ),
            )
        }
        // 四元组：依赖方包 / 目标包 / 声明区间 / 实际串。
        assertTrue("包 [mod.ext]" in ex.message!!, "依赖方包：${ex.message}")
        assertTrue("硬依赖 [mod.core]" in ex.message!!, "目标包：${ex.message}")
        assertTrue("版本区间 [2.0.0,3.0.0]" in ex.message!!, "声明区间：${ex.message}")
        assertTrue("实际 [1.5.0]" in ex.message!!, "实际串：${ex.message}")
        assertTrue("版本越界" in ex.message!!, "越界判定：${ex.message}")
    }

    @Test
    fun `扩展组合两路加载序给同一结果`() {
        fun coreModule() = TestModule(
            descriptor = ModuleDescriptor(packageId = "mod.core", overrideFunctions = setOf("X")),
            program = overrideProgram(555L),
            functions = listOf(FuncMeta(index = 0, key = "X")),
            signatures = mapOf("X" to "sig-x"),
        )

        fun extOverride() = TestModule(
            descriptor = ModuleDescriptor(
                packageId = "mod.ext.ovr",
                requires = listOf("mod.core"),
                overrideFunctions = setOf("X"),
            ),
            program = overrideWithSuperProgram(),
            functions = listOf(FuncMeta(index = 0, key = "X")),
            signatures = mapOf("X" to "sig-x"),
        )

        fun extAdd(): TestModule {
            val b = CodeBuilder()
            b.declareFunction("CNEW", b.here(), arity = 0, slotCount = 1)
            b.emit(Opcode._RET, 0, 0, 0, 0)
            return TestModule(
                descriptor = ModuleDescriptor(packageId = "mod.ext.add", requires = listOf("mod.core")),
                program = b.build(0),
                functions = listOf(FuncMeta(index = 0, key = "CNEW")),
            )
        }

        // 两条输入序：requires 强制 core 在前，ext.ovr / ext.add 的相对序由输入决定。
        val a = ModChainRuntimePlan.of(linked(listOf(coreModule(), extOverride(), extAdd())))
        val b = ModChainRuntimePlan.of(linked(listOf(extAdd(), coreModule(), extOverride())))

        assertEquals(555L, runEntry(a), "ext.ovr 的 CALL SUPER 应回到 core 的实现（不是 base）")
        assertEquals(555L, runEntry(b), "加载序变了，语义结果必须不变")
        assertEquals(a.hasOverrides, b.hasOverrides)
        assertEquals(
            a.program.functions.size,
            b.program.functions.size,
            "同一组合的物理函数表长度不随输入序变化",
        )
    }

    // ------------------------------------------------------------------
    // 三表脱节 = 构造期拒绝
    // ------------------------------------------------------------------

    @Test
    fun `program 函数表与候选集脱节时拒绝`() {
        val linked = linked(listOf(overrideModule(overrideProgram(222L))))
        val smaller = linked.program // 同一 program 先把函数表砍短：拿一个自建短程序冒充
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        val shortProgram = b.build(entry)
        val ex = assertFailsWith<IllegalArgumentException> {
            ModChainRuntimePlan.fromLinked(shortProgram, linked.catalog)
        }
        assertTrue("三表脱节" in ex.message!!, ex.message)
        // 对照：真 program 不拒（证明拒绝来自长度不一致而不是别的）。
        assertEquals(smaller.functions.size, ModChainRuntimePlan.of(linked).program.functions.size)
    }
}
