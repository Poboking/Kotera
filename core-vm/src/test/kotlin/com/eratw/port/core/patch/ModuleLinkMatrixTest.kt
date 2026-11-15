package com.eratw.port.core.patch

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OverrideSignatureException
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.SymbolConflictException
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M2-3 组合矩阵 + Base-only Legacy Golden（契约 CONTRACT-RT2-M2-3.md 判据 a–d）。
 *
 * 本文件消费 [ModuleLinker] / [SymbolCatalog] / [ModChainRuntimePlan] / [LinkFingerprint]
 * 的公开 API，补的是「组合矩阵」与「golden 恒等」；既有 [ModuleLinkerTest]（8 例）与
 * [LinkCandidateAssemblerTest]（10 例）的形状不在这里重复。
 *
 * ## 2026-11-12 架构裁决 B' 后的口径
 *
 * 链接序由公共规划层 [ModuleLinkOrder] 按「依赖约束 → [LinkOrder] 显式排序配置 →
 * `packageId` 规范兜底」裁决，输入 List 的枚举顺序不参与链接语义。因此契约判据 b 的
 * **两种拓扑都成立**：无依赖拓扑与「有依赖边但非全序」的 DAG，任意输入排列重链都
 * 逐字节一致。显式排序配置是有意语义（玩家 / 组合管理器主动改变），会改变产物；
 * 但同一配置下仍与输入排列无关。
 *
 * 旧的「现状钉 + 同序幂等」用例已在此转为真正的排列不变性回归测试；文件名与输入序
 * 不再是模块优先级的表达通道。
 *
 * ## 2026-11-12 二审补充
 *
 * 排列不变性改为**穷举**：3/4 模块经 [PermutationFixtures.all] 走全部 6/24 种排列，
 * 大集合走固定种子洗牌；无依赖、非全序 DAG、显式排序配置三种形状各自在逐字节快照层穷举。
 * 另补「不同 [LinkOrder] 经依赖约束归一到同一最终链接序 ⇒ 缓存指纹相同」，坐实指纹
 * 由有效语义而非原始配置文本决定。语义冻结（就绪模块优先级）见 `LinkOrder` KDoc 与
 * `CONTRACT-RT2-M2-3.md` §3.1；本文件**只提供定向验证**，不代表完整 Gate / 真机验收。
 */
class ModuleLinkMatrixTest {

    // ------------------------------------------------------------------
    // 夹具：base 与零基线模块（全部经 CodeBuilder 真编译，不手搓 Program）
    // ------------------------------------------------------------------

    private class MatrixModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String> = emptyMap(),
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    /** base：ENTRY@0（_RET，line 0）+ BASEFN@1（_RET，line 1）。 */
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

    /**
     * 每个模块一个函数、两条指令（_SET_INT 带常量池 + _RET），行号 1 / 2 用于行号折回判据。
     * [tag] 落在常量池里，让不同模块的指令字与池内容彼此可分辨（防空快照恒绿）。
     */
    private fun pkg(
        id: String,
        requires: List<String> = emptyList(),
        overrideFunctions: Set<String> = emptySet(),
    ): ModuleDescriptor = ModuleDescriptor(
        packageId = id,
        requires = requires,
        overrideFunctions = overrideFunctions,
    )

    private fun matrixModule(
        id: String,
        fn: String,
        tag: Long,
        decl: ModuleDescriptor = ModuleDescriptor(packageId = id),
        signature: String = "",
    ): LinkableModule {
        val b = CodeBuilder()
        b.declareFunction(fn, b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._SET_INT, 0, b.constant(tag), 0, LINE_FIRST)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, LINE_SECOND)
        return MatrixModule(
            descriptor = decl,
            program = b.build(0),
            functions = listOf(FuncMeta(index = 0, key = fn)),
            signatures = if (signature.isEmpty()) {
                emptyMap()
            } else {
                mapOf(fn.uppercase(Locale.ROOT) to signature)
            },
        )
    }

    private fun link(
        modules: List<LinkableModule>,
        policy: ResolverPolicy = ResolverPolicy.MODERN,
        linkOrder: LinkOrder = LinkOrder(),
    ): LinkedProgram = ModuleLinker.link(
        LinkInputs(
            compileBase(),
            baseMetas,
            modules,
            policy,
            linkOrder = linkOrder,
        ),
    )

    /** base-only 的 LSC：无模块 ⇒ 不进链接器，直接由 base 元数据构建纯目录。 */
    private fun baseCatalog(policy: ResolverPolicy): SymbolCatalog =
        SymbolCatalog.build(
            baseMetas.map { SymbolCandidate(meta = it, ownerPackageId = ModuleLinker.BASE_OWNER_ID) },
            policy,
        )

    // ------------------------------------------------------------------
    // 全量快照：函数池 / 函数表 / 行号表 / LinkedProgram 逐字段展开，不许只比哈希
    // ------------------------------------------------------------------

    private data class FnSnap(
        val name: String,
        val entryPc: Int,
        val arity: Int,
        val slotCount: Int,
        val params: List<String>,
        val labels: List<String>,
        val labelPcs: List<Int>,
    )

    private data class LinkedSnapshot(
        val code: List<Int>,
        val lines: List<Int>,
        val longs: List<Long>,
        val strings: List<String>,
        val exprCount: Int,
        val statementCount: Int,
        val functions: List<FnSnap>,
        val entry: Int,
        val globalSlots: Int,
        val callArgs: Map<Int, List<String>>,
        val moduleOrder: List<String>,
        val dispatch: List<Int>,
        val superChain: List<Int>,
        val bindings: List<String>,
        val events: List<String>,
        val candidates: List<String>,
        val imports: List<String>,
        val hasOverrides: Boolean,
    )

    private fun snapshotOf(linked: LinkedProgram): LinkedSnapshot {
        val p = linked.program
        val cat = linked.catalog
        return LinkedSnapshot(
            code = p.code.toList(),
            lines = p.lines.toList(),
            longs = (0 until p.constantCount).map { p.constant(it) },
            strings = (0 until p.stringCount).map { p.string(it) },
            exprCount = p.exprCount,
            statementCount = p.statementCount,
            functions = p.functions.map {
                FnSnap(
                    it.name, it.entryPc, it.arity, it.slotCount,
                    it.paramNames, it.labelNames, it.labelPcs,
                )
            },
            entry = p.entryFunction,
            globalSlots = p.globalSlots,
            callArgs = p.callArgs.mapValues { (_, args) -> args.map { it.toString() } },
            moduleOrder = linked.moduleOrder,
            dispatch = cat.dispatch.toList(),
            superChain = cat.superChain.toList(),
            bindings = cat.bindings.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" },
            events = cat.events.entries.sortedBy { it.key }
                .map { (k, v) -> "$k=${v.joinToString(";") { it.toString() }}" },
            candidates = cat.candidates.map { it.toString() },
            imports = linked.imports.packageIds.sorted().map { pkg ->
                val resolved = linked.imports.importsOf(pkg)?.imports ?: emptyList()
                "$pkg=${resolved.joinToString(";") { it.toString() }}"
            },
            hasOverrides = linked.hasOverrides,
        )
    }

    // ------------------------------------------------------------------
    // 判据 a：Base-only Legacy Golden
    // ------------------------------------------------------------------

    @Test
    fun `base-only 恒等 dispatch 逐项且分派幂等 两政策同组对拍`() {
        val legacy = baseCatalog(ResolverPolicy.LEGACY_LAST_WINS)
        val modern = baseCatalog(ResolverPolicy.MODERN)
        for (cat in listOf(legacy, modern)) {
            // 不许只断言表长或「hasOverrides=false」一层：逐项断言 dispatch[i] == i。
            assertEquals(baseMetas.size, cat.dispatch.size)
            for (i in cat.dispatch.indices) {
                assertEquals(i, cat.dispatch[i], "dispatch[$i] 必须是恒等项")
                assertEquals(i, cat.dispatch[cat.dispatch[i]], "dispatch[$i] 必须是不动点")
            }
            assertFalse(cat.hasOverrides, "base-only 的 HAS_OVERRIDES 必须为 false")
        }
        // 两政策同组对拍不变：表内容、绑定键、胜者下标逐项相同。
        assertEquals(legacy.dispatch.toList(), modern.dispatch.toList())
        assertEquals(legacy.superChain.toList(), modern.superChain.toList())
        assertEquals(legacy.bindings.keys, modern.bindings.keys)
        assertEquals(0, legacy.winnerIndexOf("ENTRY"))
        assertEquals(1, legacy.winnerIndexOf("BASEFN"))
    }

    @Test
    fun `base-only 三视图派生 program 原实例直用且 dispatch 为 null`() {
        val base = compileBase()
        for (policy in ResolverPolicy.values()) {
            val cat = baseCatalog(policy)
            val plan = ModChainRuntimePlan.fromLinked(base, cat)
            // 无模块 = base 原产物逐字节直用（同一实例，不是重建一份等价产物）。
            assertSame(base, plan.program, "base-only 必须直接用 base 原产物")
            assertNull(plan.functionDispatch, "无覆盖 ⇒ VM 直调快路径，不启用查表")
            assertFalse(plan.hasOverrides)
            // FunctionTable 与 baseMetas 逐下标同序。
            for ((i, meta) in baseMetas.withIndex()) {
                assertEquals(meta.key, plan.functionTable.byFunctionIndex(i)?.key)
            }
            assertNull(plan.functionTable.byFunctionIndex(baseMetas.size))
        }
    }

    @Test
    fun `base-only golden 零变化 胜者逐名对齐且 superChain 全为负一`() {
        val cat = baseCatalog(ResolverPolicy.LEGACY_LAST_WINS)
        assertEquals(setOf("ENTRY", "BASEFN"), cat.bindings.keys)
        assertTrue(cat.events.isEmpty())
        for (v in cat.superChain) {
            assertEquals(-1, v, "base-only 不存在任何前实现")
        }
        for (candidate in cat.candidates) {
            assertEquals(ModuleLinker.BASE_OWNER_ID, candidate.ownerPackageId)
            assertFalse(candidate.declaredOverride)
        }
    }

    // ------------------------------------------------------------------
    // 判据 b：组合确定性矩阵
    // ------------------------------------------------------------------

    @Test
    fun `DAG 全序组合打乱输入序逐字节一致`() {
        val a = matrixModule("a", "FA", 11L)
        val b = matrixModule("b", "FB", 22L, pkg("b", requires = listOf("a")))
        val c = matrixModule("c", "FC", 33L, pkg("c", requires = listOf("b")))
        val inOrder = snapshotOf(link(listOf(a, b, c)))
        val shuffled = snapshotOf(link(listOf(c, b, a)))
        // 全量快照逐字段对拍（指令字 / 行号表 / 四池 / 函数表 / LSC / 导入 / 链接序）。
        assertEquals(inOrder, shuffled)
        assertEquals(listOf("a", "b", "c"), inOrder.moduleOrder)
        assertEquals(listOf("a", "b", "c"), shuffled.moduleOrder)
        assertEquals(listOf("ENTRY", "BASEFN", "FA", "FB", "FC"), shuffled.functions.map { it.name })
    }

    @Test
    fun `二到四模块组合全量快照幂等`() {
        for (n in 2..4) {
            val modules = (1..n).map { i -> matrixModule("m$i", "M$i", i * 10L) }
            val first = snapshotOf(link(modules))
            val second = snapshotOf(link(modules))
            assertEquals(first, second, "$n 模块组合重复 link 必须逐字节一致")
            assertEquals(2 + n, first.functions.size, "$n 模块组合函数表长度")
            assertEquals(n, first.longs.size, "$n 模块组合常量池条目数")
            assertEquals((1..n).map { idx -> "m$idx" }, first.moduleOrder)
        }
    }

    @Test
    fun `组合快照含非平凡内容 防空快照恒绿`() {
        val snap = snapshotOf(
            link(
                listOf(
                    matrixModule("p1", "P1", 101L),
                    matrixModule("p2", "P2", 202L),
                ),
            ),
        )
        // 快照必须真的装载了代码 / 行号 / 池 / 函数表，而不是全空结构互等。
        assertTrue(snap.code.size > BASE_INSTRUCTION_WORDS, "指令字快照不能为空")
        assertTrue(snap.lines.isNotEmpty(), "行号表快照不能为空")
        assertEquals(listOf(101L, 202L), snap.longs, "两模块的常量池逐位进快照")
        assertEquals(listOf("ENTRY", "BASEFN", "P1", "P2"), snap.functions.map { it.name })
        assertTrue(snap.candidates.isNotEmpty(), "LSC 候选表不能为空")
        assertEquals(listOf(0, 1, 2, 3), snap.dispatch)
        // 换一组模块（改 tag）⇒ 快照必须跟着变，证明对拍不是恒真。
        val changed = snapshotOf(
            link(
                listOf(
                    matrixModule("p1", "P1", 303L),
                    matrixModule("p2", "P2", 404L),
                ),
            ),
        )
        assertTrue(snap != changed, "常量池变化必须让快照变化")
        assertEquals(listOf(303L, 404L), changed.longs)
    }

    /**
     * 契约判据 b 的「无依赖拓扑打乱输入序逐字节一致」分支：B' 归一后转为**正向回归测试**，
     * 二审加强为 3 / 4 模块**穷举全部 6 / 24 种排列**（不只挑几个手写排列）。
     * 全量快照逐字段对拍：函数表 / 常量池 / 行号表 / LSC / 导入 / 链接序。
     */
    @Test
    fun `无依赖拓扑三与四模块全排列逐字节一致 规范序兜底`() {
        for (n in 3..4) {
            val modules = (1..n).map { i -> matrixModule("m$i", "M$i", i * 10L) }
            val canonical = snapshotOf(link(modules))
            assertEquals(canonical, snapshotOf(link(modules)), "同序重复 link 幂等")
            assertEquals(modules.map { it.descriptor.packageId }, canonical.moduleOrder)
            val permutations = PermutationFixtures.all(modules)
            assertEquals(PermutationFixtures.factorial(n), permutations.size)
            for (permutation in permutations) {
                assertEquals(
                    canonical,
                    snapshotOf(link(permutation)),
                    "打乱输入序重链必须逐字节一致：${permutation.map { it.descriptor.packageId }}",
                )
            }
        }
    }

    /**
     * GPT 裁决点名补的形状：有依赖边但**不是全序**的 DAG（a → c，b / d 独立）。
     * 只用全序 DAG 会掩盖 tie-break 的不确定性；二审改为穷举 24 种排列，
     * 专钉「依赖边没钉死的部分」也排列不变。
     */
    @Test
    fun `部分序 DAG 四模块全排列逐字节一致`() {
        val modules = listOf(
            matrixModule("a", "FA", 11L),
            matrixModule("b", "FB", 22L),
            matrixModule("c", "FC", 33L, pkg("c", requires = listOf("a"))),
            matrixModule("d", "FD", 44L),
        )
        val canonical = snapshotOf(link(modules))
        assertEquals(listOf("a", "b", "c", "d"), canonical.moduleOrder)
        val permutations = PermutationFixtures.all(modules)
        assertEquals(PermutationFixtures.factorial(modules.size), permutations.size)
        for (permutation in permutations) {
            assertEquals(
                canonical,
                snapshotOf(link(permutation)),
                "部分序 DAG 打乱输入序重链必须逐字节一致：${permutation.map { it.descriptor.packageId }}",
            )
        }
    }

    /** n 较大时不穷举 n!，改用固定种子洗牌，仍在逐字节快照层验证排列不变性。 */
    @Test
    fun `固定种子大集合排列逐字节一致`() {
        val modules = (1..7).map { i ->
            val decl = if (i == 4) pkg("m4", requires = listOf("m2")) else pkg("m$i")
            matrixModule("m$i", "M$i", i * 10L, decl)
        }
        val canonical = snapshotOf(link(modules))
        assertEquals(modules.map { it.descriptor.packageId }, canonical.moduleOrder)
        for (permutation in PermutationFixtures.seeded(modules)) {
            assertEquals(
                canonical,
                snapshotOf(link(permutation)),
                "大集合输入 ${permutation.map { it.descriptor.packageId }} 必须逐字节一致",
            )
        }
    }

    /** [LinkOrder] 是**有意语义**：它会改变产物，但同一配置下 24 种输入排列产物全等。 */
    @Test
    fun `显式排序配置是有意语义 且四模块全排列与输入序无关`() {
        val modules = listOf(
            matrixModule("a", "FA", 11L),
            matrixModule("b", "FB", 22L),
            matrixModule("c", "FC", 33L),
            matrixModule("d", "FD", 44L),
        )
        val order = LinkOrder(preferredPackageIds = listOf("d", "b", "a", "c"))
        val custom = snapshotOf(link(modules, linkOrder = order))
        assertEquals(listOf("d", "b", "a", "c"), custom.moduleOrder)
        assertEquals(
            listOf("ENTRY", "BASEFN", "FD", "FB", "FA", "FC"),
            custom.functions.map { it.name },
        )
        for (permutation in PermutationFixtures.all(modules)) {
            assertEquals(
                custom,
                snapshotOf(link(permutation, linkOrder = order)),
                "同一显式排序配置下，输入排列不得改变产物",
            )
        }
        val defaultSnap = snapshotOf(link(modules))
        assertEquals(listOf("a", "b", "c", "d"), defaultSnap.moduleOrder)
        assertTrue(custom != defaultSnap, "显式排序配置必须有意改变链接产物")
    }

    /** 链接序有语义的下游：LEGACY last-wins 胜者不随偶然输入排列漂移。 */
    @Test
    fun `LEGACY 胜者随输入排列不变 显式排序配置才有意改变链接序`() {
        val first = matrixModule("x1", "X", 101L, signature = "sig-x")
        val second = matrixModule("x3", "X", 103L, signature = "sig-x")
        val canonical = link(listOf(first, second), ResolverPolicy.LEGACY_LAST_WINS)
        val shuffled = link(listOf(second, first), ResolverPolicy.LEGACY_LAST_WINS)
        assertEquals(listOf("x1", "x3"), canonical.moduleOrder)
        assertEquals(canonical.moduleOrder, shuffled.moduleOrder)
        assertEquals(WINNER_SECOND, canonical.catalog.winnerIndexOf("X"))
        assertEquals(WINNER_SECOND, shuffled.catalog.winnerIndexOf("X"))
        assertEquals(snapshotOf(canonical), snapshotOf(shuffled), "输入排列不得改变 LEGACY 覆盖产物")

        val custom = link(
            listOf(second, first),
            ResolverPolicy.LEGACY_LAST_WINS,
            LinkOrder(preferredPackageIds = listOf("x3", "x1")),
        )
        assertEquals(listOf("x3", "x1"), custom.moduleOrder)
        assertEquals(listOf(103L, 101L), snapshotOf(custom).longs, "显式排序改变常量池叠加序")
        assertTrue(snapshotOf(canonical) != snapshotOf(custom), "显式排序配置必须改变产物")
    }

    /**
     * 最小夹具：用 `packageId` 代产物哈希，判据是「最终链接序是否进指纹」。
     * 返回「最终链接序 + 指纹」二元组，让「同序同指纹」与「换序换指纹」可分别断言。
     */
    private fun fingerprintOf(
        modules: List<LinkableModule>,
        order: LinkOrder = LinkOrder(),
    ): Pair<List<String>, String> {
        val linked = link(modules, linkOrder = order)
        return linked.moduleOrder to LinkFingerprint.compute(
            LinkFingerprint.Inputs(
                baseProgramSha256 = "base-sha",
                moduleArtifactSha256s = linked.moduleOrder,
                compilerAbi = "abi",
                linkerVersion = "v1",
                bytecodeIsaVersion = 1,
                builtinRegistryFingerprint = "builtin",
                runtimeProfileFingerprint = "profile",
                resolutionPolicy = ResolverPolicy.MODERN,
                variableSchemaFingerprint = "vars",
                entitySchemaFingerprint = "entity",
                manifests = linked.moduleOrder.map { ModuleDescriptor(packageId = it) },
            ),
        )
    }

    /** ABI-8：指纹按最终规范链接序渲染，输入排列无语义、显式排序改变链接序必须换指纹。 */
    @Test
    fun `ABI-8 指纹 输入排列无关 显式配置改变链接序则指纹改变`() {
        val a = matrixModule("a", "FA", 11L)
        val b = matrixModule("b", "FB", 22L)
        val c = matrixModule("c", "FC", 33L)
        val baseline = fingerprintOf(listOf(a, b, c))
        assertEquals(baseline.second, fingerprintOf(listOf(c, a, b)).second, "输入排列不得改变缓存指纹")
        assertEquals(baseline.second, fingerprintOf(listOf(b, c, a)).second, "输入排列不得改变缓存指纹")
        val custom = fingerprintOf(listOf(a, b, c), LinkOrder(listOf("c", "a", "b")))
        assertTrue(baseline.second != custom.second, "显式排序改变链接序 ⇒ 指纹必须改变，防旧缓存错配")
    }

    /**
     * 二审追加：缓存由**有效语义**决定，不由原始配置文本的字节决定。
     * b 硬依赖 a，即使配置把 b 排最前，它初始也不就绪；四份文本不同的 [LinkOrder]
     * 经依赖约束归一后给出同一条最终链接序 ⇒ 指纹必须相同，无理由让缓存失效。
     */
    @Test
    fun `不同 LinkOrder 经依赖归一到同一链接序则缓存指纹相同`() {
        val modules = listOf(
            matrixModule("a", "FA", 11L),
            matrixModule("b", "FB", 22L, pkg("b", requires = listOf("a"))),
            matrixModule("c", "FC", 33L),
        )
        val canonical = fingerprintOf(modules)
        assertEquals(listOf("a", "b", "c"), canonical.first)
        for (order in listOf(
            LinkOrder(listOf("a", "b", "c")),
            LinkOrder(listOf("ghost", "a", "b")),
            LinkOrder(listOf("b", "a")),
        )) {
            val result = fingerprintOf(modules, order)
            assertEquals(
                canonical.first,
                result.first,
                "配置 ${order.preferredPackageIds} 应归一到同一最终链接序",
            )
            assertEquals(
                canonical.second,
                result.second,
                "最终链接序相同 ⇒ 指纹必须相同（不哈希配置文本）",
            )
        }
        // 真正改变链接序的配置仍必须换指纹，防止旧缓存错配。
        val changed = fingerprintOf(modules, LinkOrder(listOf("c", "a", "b")))
        assertEquals(listOf("c", "a", "b"), changed.first)
        assertTrue(canonical.second != changed.second)
    }

    // ------------------------------------------------------------------
    // 判据 c：政策分叉矩阵
    // ------------------------------------------------------------------

    @Test
    fun `MODERN 未声明重名整组拒绝并点名提供者`() {
        val first = matrixModule("x1", "X", 101L, signature = "sig-x")
        val undeclared = matrixModule("x3", "X", 103L, signature = "sig-x")
        val ex = assertFailsWith<SymbolConflictException> {
            link(listOf(first, undeclared), ResolverPolicy.MODERN)
        }
        assertEquals("X", ex.key.uppercase(Locale.ROOT))
        assertEquals(listOf("x1", "x3"), ex.providers)
        assertTrue("X" in ex.message!!, ex.message)
    }

    @Test
    fun `LEGACY last-wins 胜者为后者且 dispatch 非恒等`() {
        val first = matrixModule("x1", "X", 101L, signature = "sig-x")
        val undeclared = matrixModule("x3", "X", 103L, signature = "sig-x")
        val linked = link(listOf(first, undeclared), ResolverPolicy.LEGACY_LAST_WINS)
        assertEquals(WINNER_SECOND, linked.catalog.winnerIndexOf("X"))
        assertTrue(linked.hasOverrides)
        assertEquals(WINNER_SECOND, linked.catalog.dispatch[PREV_IMPL])
        assertEquals(PREV_IMPL, linked.catalog.superChain[WINNER_SECOND])
        assertEquals(WINNER_SECOND, linked.catalog.dispatch[linked.catalog.dispatch[PREV_IMPL]])
    }

    @Test
    fun `MODERN 显式覆盖签名闸 一致放行 空与不一致拒绝`() {
        val first = matrixModule("x1", "X", 101L, signature = "sig-x")
        val declared = matrixModule(
            "x2", "X", 102L,
            pkg("x2", overrideFunctions = setOf("X")), signature = "sig-x",
        )
        val ok = link(listOf(first, declared), ResolverPolicy.MODERN)
        assertEquals(WINNER_SECOND, ok.catalog.winnerIndexOf("X"))
        assertTrue(ok.hasOverrides)

        val emptySig = matrixModule("x4", "X", 104L, pkg("x4", overrideFunctions = setOf("X")))
        val emptyEx = assertFailsWith<OverrideSignatureException> {
            link(listOf(first, emptySig), ResolverPolicy.MODERN)
        }
        assertEquals("X", emptyEx.key.uppercase(Locale.ROOT))
        assertTrue("签名信息不完整" in emptyEx.message!!, emptyEx.message)

        val mismatch = matrixModule(
            "x5", "X", 105L,
            pkg("x5", overrideFunctions = setOf("X")), signature = "sig-other",
        )
        val mismatchEx = assertFailsWith<OverrideSignatureException> {
            link(listOf(first, mismatch), ResolverPolicy.MODERN)
        }
        assertTrue("签名不一致" in mismatchEx.message!!, mismatchEx.message)
    }

    @Test
    fun `LEGACY 显式覆盖不查签名 空指纹与不一致指纹都放行`() {
        val first = matrixModule("x1", "X", 101L, signature = "sig-x")
        for ((id, tag, sig) in listOf(
            Triple("x4", 104L, ""),
            Triple("x5", 105L, "sig-other"),
        )) {
            val declared = matrixModule(
                id, "X", tag,
                pkg(id, overrideFunctions = setOf("X")), signature = sig,
            )
            val linked = link(listOf(first, declared), ResolverPolicy.LEGACY_LAST_WINS)
            assertEquals(WINNER_SECOND, linked.catalog.winnerIndexOf("X"))
            assertTrue(linked.hasOverrides)
        }
    }

    // ------------------------------------------------------------------
    // 判据 d：HAS_OVERRIDES 判据矩阵
    // ------------------------------------------------------------------

    @Test
    fun `有真实覆盖则 HAS_OVERRIDES 为真且 dispatch 非恒等逐项`() {
        val first = matrixModule("x1", "X", 101L, signature = "sig-x")
        val declared = matrixModule(
            "x2", "X", 102L,
            pkg("x2", overrideFunctions = setOf("X")), signature = "sig-x",
        )
        val linked = link(listOf(first, declared))
        assertTrue(linked.hasOverrides)
        val dispatch = linked.catalog.dispatch
        assertEquals(listOf(0, 1, WINNER_SECOND, WINNER_SECOND), dispatch.toList())
        assertTrue(dispatch.indices.any { dispatch[it] != it }, "必须存在非恒等项")
        for (i in dispatch.indices) {
            assertEquals(dispatch[i], dispatch[dispatch[i]], "分派幂等式 dispatch[$i]")
        }
    }

    @Test
    fun `纯追加则 HAS_OVERRIDES 为假且 dispatch 恒等逐项`() {
        val linked = link(
            listOf(
                matrixModule("n1", "N1", 1L),
                matrixModule("n2", "N2", 2L),
                matrixModule("n3", "N3", 3L),
            ),
        )
        assertFalse(linked.hasOverrides)
        for (i in linked.catalog.dispatch.indices) {
            assertEquals(i, linked.catalog.dispatch[i], "纯追加 dispatch[$i] 必须恒等")
        }
        assertEquals(listOf(0, 1, 2, 3, 4), linked.catalog.dispatch.toList())
        assertNull(ModChainRuntimePlan.of(linked).functionDispatch)
    }

    @Test
    fun `嵌套组合行号折回且 HAS_OVERRIDES 透传`() {
        val na = matrixModule("na", "NA", 1L)
        val nb = matrixModule("nb", "X", 2L, pkg("nb", requires = listOf("na")), signature = "sig-x")
        val nc = matrixModule(
            "nc", "X", 3L,
            pkg("nc", requires = listOf("nb"), overrideFunctions = setOf("X")),
            signature = "sig-x",
        )
        val linked = link(listOf(nc, na, nb))
        assertEquals(listOf("na", "nb", "nc"), linked.moduleOrder, "依赖边把链接序钉死")
        val p = linked.program
        // 每个模块两条指令，行号保持模块内原值（tail 段下标折回，不重编行号）。
        for (m in 0 until MODULE_COUNT_NESTED) {
            val off = BASE_INSTRUCTION_COUNT + m * INSTR_PER_MODULE
            assertEquals(LINE_FIRST, p.lineOf(off), "模块 $m 首指令行号")
            assertEquals(LINE_SECOND, p.lineOf(off + 1), "模块 $m 次指令行号")
        }
        assertTrue(linked.hasOverrides)
        assertEquals(NESTED_WINNER, linked.catalog.winnerIndexOf("X"))
        assertEquals(NESTED_PREV, linked.catalog.superChain[NESTED_WINNER])
    }

    @Test
    fun `嵌套组合纯追加时行号折回且 HAS_OVERRIDES 为假`() {
        val na = matrixModule("na", "NA", 1L)
        val nb = matrixModule("nb", "NB", 2L, pkg("nb", requires = listOf("na")))
        val nc = matrixModule("nc", "NC", 3L, pkg("nc", requires = listOf("nb")))
        val linked = link(listOf(nc, nb, na))
        assertEquals(listOf("na", "nb", "nc"), linked.moduleOrder)
        val p = linked.program
        for (m in 0 until MODULE_COUNT_NESTED) {
            val off = BASE_INSTRUCTION_COUNT + m * INSTR_PER_MODULE
            assertEquals(LINE_FIRST, p.lineOf(off))
            assertEquals(LINE_SECOND, p.lineOf(off + 1))
        }
        assertFalse(linked.hasOverrides)
        for (i in linked.catalog.dispatch.indices) {
            assertEquals(i, linked.catalog.dispatch[i])
        }
    }

    companion object {
        const val LINE_FIRST = 1
        const val LINE_SECOND = 2
        const val BASE_INSTRUCTION_COUNT = 2
        const val INSTR_PER_MODULE = 2
        const val BASE_INSTRUCTION_WORDS = 8
        const val MODULE_COUNT_NESTED = 3
        const val PREV_IMPL = 2
        const val WINNER_SECOND = 3
        const val NESTED_PREV = 3
        const val NESTED_WINNER = 4
    }
}
