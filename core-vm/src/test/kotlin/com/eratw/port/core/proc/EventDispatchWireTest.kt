package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.LinkInputs
import com.eratw.port.core.patch.LinkableModule
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * M2-6 出口判据：**事件一对多四桶表接进运行期派发**（era-arch-v61u.md §3 / §6 / §12.1）。
 *
 * 装配器第五步早已 `EventRegistry.of(linked.catalog)` 产表，但 `ProcessRuntime.executeEvent`
 * 一直读 `FunctionTable.eventCallOrder`（§22.7 第 28 条「仍成立」项）。本文件锁两半：
 * - **执行器换源**：四桶表在场时按它逐成员派发，base 重名 + 多 Mod 同名成员的
 *   ONLY → PRI → NORMAL → LATER、同桶链接序**逐位**对拍（真 VM 打印序列，不只看表）；
 * - **生产装配点**：`GameSession` 从 `modChain.eventRegistry` 单路注入同一实例，旁路态 null。
 *
 * 夹具走生产 LSC 构建器 [SymbolCatalog.build]（`ModuleLinker.link` 的同一出口），
 * 不手搓 `EventRegistry`，也不注入假表冒充装配结果。打印序列用 [Assembler] 的最小真 Program，
 * 与 `ProcessRuntimeTest` 既有事件派发装置同口径。
 */
class EventDispatchWireTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** main@0 是非事件函数，事件成员占 1..N；每个成员打印自己的唯一标记。 */
    private fun program(markers: List<String>, singleAt: Set<Int> = emptySet()): Program {
        val src = buildString {
            append(".func main slots=1\n")
            append("  _HALT\n")
            for ((i, marker) in markers.withIndex()) {
                append(".func ev${i + 1} slots=2\n")
                append("  _PRINT_LITERAL \"$marker\", 1\n")
                if (i in singleAt) {
                    append("  _SET_INT R0, 1\n")
                    append("  _RET R0\n")
                } else {
                    append("  _RET\n")
                }
            }
        }
        return Assembler.assemble(src)
    }

    private fun mainMeta(): FuncMeta = FuncMeta(index = 0, key = "MAIN", name = "main")

    private fun eventMeta(
        index: Int,
        isOnly: Boolean = false,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
    ): FuncMeta = FuncMeta(
        index = index,
        key = "EVT",
        name = "ev$index",
        isEvent = true,
        isOnly = isOnly,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        defIndex = index,
    )

    /** 扰动夹具：物理下标 index 与装载序 defIndex 反号（派发按 defIndex 取装载序）。 */
    private fun evOf(
        index: Int,
        def: Int,
        isPri: Boolean = false,
        isLater: Boolean = false,
    ): FuncMeta = FuncMeta(
        index = index,
        key = "EVT",
        name = "ev$index",
        isEvent = true,
        isPri = isPri,
        isLater = isLater,
        defIndex = def,
    )

    /** 生产 LSC 构建器派生四桶表；owners 逐候选给归属包（= 链接序的 base / 各包）。 */
    private fun registryOf(metas: List<FuncMeta>, owners: List<String>): EventRegistry {
        assertEquals(metas.size, owners.size, "候选与归属包必须一一对应")
        val candidates = metas.mapIndexed { i, m ->
            SymbolCandidate(meta = m, ownerPackageId = owners[i])
        }
        return EventRegistry.of(SymbolCatalog.build(candidates, ResolverPolicy.LEGACY_LAST_WINS))
    }

    private fun runtime(
        program: Program,
        metas: List<FuncMeta>,
        registry: EventRegistry?,
    ): ProcessRuntime = ProcessRuntime(
        program,
        VariableStore(),
        FunctionTable(metas),
        VmConfig(),
        eventRegistry = registry,
    )

    private fun sourceOf(relative: String): String {
        val file = listOf(
            File(relative),
            File("core-vm/$relative"),
            File("../core-vm/$relative"),
        ).firstOrNull { it.isFile } ?: error("找不到源码: $relative")
        return file.readText()
    }

    private val nineMarkers = listOf("b0", "b1", "b2", "b3", "b4", "a0", "a1", "c0", "c1")

    /** index 1..5 = base 重名事件；6..7 = mod.a；8..9 = mod.b。桶位交叉乱序。 */
    private fun nineMetas(): List<FuncMeta> = listOf(
        mainMeta(),
        eventMeta(1, isLater = true),
        eventMeta(2),
        eventMeta(3, isPri = true),
        eventMeta(4, isLater = true),
        eventMeta(5, isPri = true),
        eventMeta(6),
        eventMeta(7, isLater = true),
        eventMeta(8, isPri = true),
        eventMeta(9),
    )

    private val nineOwners = listOf(
        "__base__", "__base__", "__base__", "__base__", "__base__", "__base__",
        "mod.a", "mod.a", "mod.b", "mod.b",
    )

    /** PRI(3,5,8) → NORMAL(2,6,9) → LATER(1,4,7)，同桶保持链接序。 */
    private val expectedOrder = listOf(3, 5, 8, 2, 6, 9, 1, 4, 7)
    private val expectedOutput = "b2\nb4\nc0\nb1\na0\nc1\nb0\nb3\na1\n"

    // ------------------------------------------------------------------
    // 四桶顺序逐位
    // ------------------------------------------------------------------

    @Test
    fun `四桶顺序逐位_base重名加两Mod同名`() {
        val p = program(nineMarkers)
        val metas = nineMetas()
        val reg = registryOf(metas, nineOwners)
        assertEquals(expectedOrder, reg.membersOf("EVT").map { it.physicalIndex }, "四桶派生序逐位")
        assertEquals(
            listOf(
                "__base__", "__base__", "mod.b", "__base__", "mod.a", "mod.b",
                "__base__", "__base__", "mod.a",
            ),
            reg.membersOf("EVT").map { it.ownerPackageId },
            "一对多基数全保留且归属包按链接序透传",
        )
        val rt = runtime(p, metas, reg)
        assertTrue(rt.executeEvent("EVT"))
        assertEquals(expectedOutput, rt.output.toString(), "真 VM 派发序列逐位")
    }

    @Test
    fun `旁路态无四桶表时逐位不变`() {
        val p = program(nineMarkers)
        val metas = nineMetas()
        val rt = runtime(p, metas, null)
        assertTrue(rt.executeEvent("EVT"))
        assertEquals(expectedOutput, rt.output.toString(), "关态走原 FunctionTable 视图，输出零变化")
        assertEquals(expectedOrder, FunctionTable(metas).eventCallOrder("EVT")!!.map { it.index })
    }

    @Test
    fun `SINGLE返回1只截断本桶后续桶照派`() {
        val p = program(listOf("p1", "p2", "n1", "l1", "n2"), singleAt = setOf(0))
        val metas = listOf(
            mainMeta(),
            eventMeta(1, isPri = true, isSingle = true),
            eventMeta(2, isPri = true),
            eventMeta(3),
            eventMeta(4, isLater = true),
            eventMeta(5),
        )
        val reg = registryOf(metas, List(metas.size) { "pkg" })
        assertEquals(
            listOf(1, 2, 3, 5, 4),
            reg.membersOf("EVT").map { it.physicalIndex },
        )
        val rt = runtime(p, metas, reg)
        assertTrue(rt.executeEvent("EVT"))
        assertEquals("p1\nn1\nn2\nl1\n", rt.output.toString(), "同桶 p2 被跳过，NORMAL/LATER 照派")
    }

    @Test
    fun `ONLY成员终止整链且旗标随四桶表带出`() {
        val p = program(listOf("n1", "o1", "p1", "l1"))
        val metas = listOf(
            mainMeta(),
            eventMeta(1),
            eventMeta(2, isOnly = true),
            eventMeta(3, isPri = true),
            eventMeta(4, isLater = true),
        )
        val reg = registryOf(metas, List(metas.size) { "pkg" })
        val members = reg.membersOf("EVT")
        // 并列加桶（在册 B23）：成员 2（#ONLY）除桶 0 外还落桶 2，桶 2 内链接序 1 在它之前。
        assertEquals(listOf(2, 3, 1, 2, 4), members.map { it.physicalIndex })
        assertTrue(members[0].isOnly, "ONLY 旗标必须随成员带出，不许靠 bucket 反推")
        assertEquals(0, members[0].bucket)
        val rt = runtime(p, metas, reg)
        assertTrue(rt.executeEvent("EVT"))
        assertEquals("o1\n", rt.output.toString(), "ONLY 命中后后续桶全部终止（桶 2 那份随整链作废）")
    }

    /**
     * B23 的最终形状：在端口真 VM 上复现 PC-CMP-VERDICT.md 5.4 的在册实测派发序。
     * 成员集照 `eratw-android/fixtures/pc-cmp/PC-CMP-01-BUCKETS.ERB` 的 8 个 @EVENTTURNEND
     * 成员：成员 1 = #PRI+#LATER 同体（装载期引擎原话「同时使用#PRI和#LATER是重复的
     * (这个函数会被调用2次)」）、成员 5 = #SINGLE 返回 0（不截断）、成员 7 = #SINGLE 返回 1
     * （只截本桶，ProcessState.cs:336-340）。PC 屏上序 = 1-PL, 2-P, 3-N, 5-SINGLE0,
     * 6-AFTER-SINGLE, 7-SINGLE1, 1-PL, 4-L，且 8-AFTER-SINGLE1 缺席。
     */
    @Test
    fun `并列加桶_同成员派发两次且single只截本桶`() {
        val markers = listOf(
            "1-PL", "2-P", "3-N", "4-L", "5-SINGLE0", "6-AFTER-SINGLE", "7-SINGLE1", "8-AFTER-SINGLE1",
        )
        // singleAt=6 ⇒ 第 7 个成员体尾是 _SET_INT R0, 1 + _RET R0（返回 1 才截本桶）
        val p = program(markers, singleAt = setOf(6))
        val metas = listOf(
            mainMeta(),
            eventMeta(1, isPri = true, isLater = true),
            eventMeta(2, isPri = true),
            eventMeta(3),
            eventMeta(4, isLater = true),
            eventMeta(5, isSingle = true),
            eventMeta(6),
            eventMeta(7, isSingle = true),
            eventMeta(8),
        )
        val reg = registryOf(metas, List(metas.size) { "__base__" })
        val members = reg.membersOf("EVT")
        assertEquals(
            listOf(1, 2, 3, 5, 6, 7, 8, 1, 4),
            members.map { it.physicalIndex },
            "成员 1（#PRI+#LATER）在桶 1 与桶 3 各投一次 ⇒ 展开 9 条 > 8 成员",
        )
        assertEquals(listOf(1, 1, 2, 2, 2, 2, 2, 3, 3), members.map { it.bucket }, "桶位单调不减")
        val rt = runtime(p, metas, reg)
        assertTrue(rt.executeEvent("EVT"))
        val out = rt.output.toString()
        assertEquals(
            "1-PL\n2-P\n3-N\n5-SINGLE0\n6-AFTER-SINGLE\n7-SINGLE1\n1-PL\n4-L\n",
            out,
            "逐行 = PC 屏上派发序（PC-CMP-VERDICT 5.4 在册 27 行的桶序部分）",
        )
        assertEquals(2, out.lineSequence().count { it == "1-PL" }, "同一成员被派发两次")
        assertFalse(out.contains("8-AFTER-SINGLE1"), "#SINGLE 返回 1 只截本桶：桶 2 后续成员不再执行")
        // 旁路（无四桶表，走 FunctionTable.eventDispatchOrder）同夹具双跑必须逐行同序
        val bypass = runtime(p, metas, null)
        assertTrue(bypass.executeEvent("EVT"))
        assertEquals(out, bypass.output.toString(), "四桶表路与 FunctionTable 路逐行同序")
    }

    /**
     * 装载序扰动（B23 收口判据②，端口真 VM）：两条建表道都钉。
     *
     *  - 稠密快道（[FunctionTable] init 的刀 D 判据：下标序即装载序，FuncMeta.kt:475-486）——
     *    夹具 index == defIndex，只换构造入参次序，展开序与派发输出逐行不变；
     *  - 非稠密慢道（下标留空洞 ⇒ loadOrder = sortedBy defIndex）——index 与 defIndex 反号，
     *    展开序必须按 defIndex 装载序，既不按下标序也不按入参序。
     *
     * 慢道里 #PRI+#LATER 成员（index 1 / defIndex 4）在桶 1 与桶 3 各派发一次，
     * 两次之间隔着 defIndex 更小的成员（index 5 / defIndex 1，桶 3 在它之前）。
     */
    @Test
    fun `装载序扰动_换构造序与defIndex派发输出逐行不变`() {
        val markers = listOf("1-PL", "2-P", "3-N", "4-L")
        val p = program(markers)
        // 快道夹具：index == defIndex（ erbcc 产物的真实形状），四个成员覆盖三桶，
        // 成员 1 = #PRI+#LATER ⇒ 桶 1 与桶 3 各一次。
        val mPl = eventMeta(1, isPri = true, isLater = true)
        val mPri = eventMeta(2, isPri = true)
        val mNorm = eventMeta(3)
        val mLat = eventMeta(4, isLater = true)
        val dense = listOf(mainMeta(), mPl, mPri, mNorm, mLat)
        val densePairs = listOf(1 to 1, 2 to 1, 3 to 2, 1 to 3, 4 to 3)
        val denseOut = "1-PL\n2-P\n3-N\n1-PL\n4-L\n"
        val denseShuffles = listOf(
            "正序" to dense,
            "逆序" to listOf(dense[0], mLat, mNorm, mPri, mPl),
            "乱序" to listOf(mainMeta(), mLat, mPl, mNorm, mPri),
        )
        for ((label, input) in denseShuffles) {
            assertEquals(
                densePairs,
                FunctionTable(input).eventDispatchOrder("EVT")?.map { it.first.index to it.second },
                "稠密快道 $label：下标序即装载序，换入参次序不许改展开序",
            )
            val rt = runtime(p, input, null)
            assertTrue(rt.executeEvent("EVT"))
            assertEquals(denseOut, rt.output.toString(), "稠密快道 $label：派发输出逐行同")
        }
        // 慢道夹具：filler 占 index 9 ⇒ byIndex 不稠密（dense 判据不成立）+ defIndex 与 index 反号
        // ⇒ 装载序 = m4(1) < m3(2) < m2(3) < m1(4)。
        val filler = FuncMeta(index = 9, key = "FILLER", name = "filler", defIndex = 9)
        val sPl = evOf(1, 4, isPri = true, isLater = true)
        val sPri = evOf(2, 3, isPri = true)
        val sNorm = evOf(3, 2)
        val sLat = evOf(4, 1, isLater = true)
        val sparse = listOf(mainMeta(), sPl, sPri, sNorm, sLat, filler)
        // 桶 1 = m2(def3), m1(def4)；桶 2 = m3(def2)；桶 3 = m4(def1), m1(def4)
        // ⇒ 重复项 m1 的两次派发之间隔着 defIndex 更小的 m4，扰动感落在派发序上而不只是表序上。
        val sparsePairs = listOf(2 to 1, 1 to 1, 3 to 2, 4 to 3, 1 to 3)
        val sparseOut = "2-P\n1-PL\n3-N\n4-L\n1-PL\n"
        for ((label, input) in listOf("正序" to sparse, "逆序" to sparse.reversed())) {
            assertEquals(
                sparsePairs,
                FunctionTable(input).eventDispatchOrder("EVT")?.map { it.first.index to it.second },
                "非稠密慢道 $label：展开序必须按 defIndex 装载序，不是下标序也不是入参序",
            )
            val rt = runtime(p, input, null)
            assertTrue(rt.executeEvent("EVT"))
            assertEquals(sparseOut, rt.output.toString(), "非稠密慢道 $label：旁路派发输出逐行同")
        }
        // 双路同序：链接序 = defIndex 序（生产装配同口径）时四桶表与 FunctionTable 逐行一致
        val byDef = sparse.sortedBy { it.defIndex }
        val reg = registryOf(byDef, List(byDef.size) { "__base__" })
        assertEquals(
            FunctionTable(sparse).eventDispatchOrder("EVT")!!.map { it.first.index to it.second },
            reg.membersOf("EVT").map { it.physicalIndex to it.bucket },
            "双路逐行同序（含重复项与各自己的桶位）",
        )
        val rtSparse = runtime(p, byDef, reg)
        assertTrue(rtSparse.executeEvent("EVT"))
        assertEquals(sparseOut, rtSparse.output.toString(), "四桶表路在慢道夹具上同派发序")
    }

    /**
     * 端口侧断言（**PC 未证**，TODO(verify)）：两次派发之间整链被打断 ⇒ 第二次派发不再发生。
     *
     * 端口目前没有 ERB 的 TRY/ROLLBACK/ENDTRY 语句与 MANUALRESET 内置
     * （`core-vm/src/main/kotlin` grep 该三名零命中），故取同形可观测量：桶 2 成员调用
     * 不存在的函数下标（Vm.kt:2631「函数下标越界」抛 CodeEE）打断整链。
     * 对照组（同夹具去掉打断）证明第二次派发本来会发生，排除「整链根本没跑起来」的假绿。
     * 真实 TRY 回滚 / MANUALRESET 与桶推进、#SINGLE 截断的交叠语义待端口补齐该语句后再钉。
     */
    @Test
    fun `端口侧断言_两次派发之间链中断则第二次不再发生`() {
        // 与 program() 同形（行 3 必须是真函数名，不能把插值写成字面量）。
        fun source(markers: List<String>, raiseAt: Set<Int>): String = buildString {
            append(".func main slots=1\n")
            append("  _HALT\n")
            for ((i, marker) in markers.withIndex()) {
                append(".func ev" + (i + 1) + " slots=2\n")
                append("  _PRINT_LITERAL \"$marker\", 1\n")
                if (i in raiseAt) {
                    append("  _CALL #999, 0\n")
                }
                append("  _RET\n")
            }
        }
        val markers = listOf("1-PL", "2-N", "3-L")
        val metas = listOf(
            mainMeta(),
            eventMeta(1, isPri = true, isLater = true),
            eventMeta(2),
            eventMeta(3, isLater = true),
        )
        val reg = registryOf(metas, List(metas.size) { "__base__" })
        assertEquals(listOf(1, 2, 1, 3), reg.membersOf("EVT").map { it.physicalIndex }, "桶 1=1 / 桶 2=2 / 桶 3=1,3")
        val control = runtime(Assembler.assemble(source(markers, emptySet())), metas, reg)
        assertTrue(control.executeEvent("EVT"))
        assertEquals("1-PL\n2-N\n1-PL\n3-L\n", control.output.toString(), "对照组：无打断时第二次派发照发生")
        val aborted = runtime(Assembler.assemble(source(markers, setOf(1))), metas, reg)
        assertFailsWith<CodeEE> { aborted.executeEvent("EVT") }
        assertEquals(
            "1-PL\n2-N\n",
            aborted.output.toString(),
            "端口侧断言：链中断后成员 1 的第二次派发（桶 3）与成员 3 都不再执行",
        )
    }

    // ------------------------------------------------------------------
    // 换源可区分 + 负控
    // ------------------------------------------------------------------

    @Test
    fun `四桶表在场即接管_派发源退回旧表会被看见`() {
        val p = program(listOf("m1", "m2", "m3"))
        // 执行器换源单元夹具：FunctionTable 视图只有 2 个 EVT 成员（idx3 是非事件），
        // 四桶表视图有 3 个。两路可区分 ⇒ 退回 table.eventCallOrder 会少派一个成员。
        // 这不是「生产装配」的证明（那由 GameSession 用例单独锁），只证执行器读的是注入表。
        val tableMetas = listOf(
            mainMeta(),
            eventMeta(1),
            eventMeta(2),
            FuncMeta(index = 3, key = "OTHER", name = "ev3"),
        )
        val regMetas = listOf(mainMeta(), eventMeta(1), eventMeta(2), eventMeta(3))
        val reg = registryOf(regMetas, List(regMetas.size) { "pkg" })
        assertEquals(2, FunctionTable(tableMetas).eventCallOrder("EVT")!!.size)
        assertEquals(3, reg.membersOf("EVT").size)
        val rt = runtime(p, tableMetas, reg)
        assertTrue(rt.executeEvent("EVT"))
        assertEquals("m1\nm2\nm3\n", rt.output.toString(), "必须按四桶表的 3 个成员派发")
    }

    @Test
    fun `表在场时未知事件与非事件同名负控不回退`() {
        val p = program(listOf("m1"))
        val metas = listOf(mainMeta(), eventMeta(1))
        val reg = registryOf(metas, listOf("__base__", "__base__"))
        val rt = runtime(p, metas, reg)
        assertFalse(rt.executeEvent("NO_SUCH_EVENT"), "事件不存在静默 false")
        assertFailsWith<CodeEE> { rt.executeEvent("MAIN") }
    }

    // ------------------------------------------------------------------
    // 生产装配点（真 ModuleLinker.link → plan → GameSession）
    // ------------------------------------------------------------------

    private class WireModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String>,
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    private fun wireBase(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        b.declareFunction("EVT", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(entry)
    }

    private val wireMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
        FuncMeta(index = 2, key = "EVT", isEvent = true),
    )

    private fun wirePlan(): ModChainRuntimePlan {
        val b = CodeBuilder()
        val entry = b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        val module = WireModule(
            descriptor = ModuleDescriptor(packageId = "mod.ovr", overrideFunctions = setOf("X")),
            program = b.build(entry),
            functions = listOf(FuncMeta(index = 0, key = "X")),
            signatures = mapOf("X" to "sig-x"),
        )
        val linked = ModuleLinker.link(
            LinkInputs(
                base = wireBase(),
                baseMetas = wireMetas,
                modules = listOf(module),
                policy = ResolverPolicy.MODERN,
                baseSignatureOf = { key: String ->
                    if (key.uppercase(Locale.ROOT) == "X") "sig-x" else ""
                },
            ),
        )
        return ModChainRuntimePlan.of(linked)
    }

    @Test
    fun `生产装配点_四桶表随plan单路注入且旁路为null`(@TempDir dir: Path) {
        val plan = wirePlan()
        val base = wireBase()
        val metadata = BootMetadata(functions = wireMetas)
        val withMod = GameSession(
            gameRoot = dir,
            program = base,
            metadata = metadata,
            modChain = plan,
        )
        assertSame(
            plan.eventRegistry,
            withMod.runtime.eventRegistry,
            "正式会话必须复用 plan 的同一张四桶表，不重建第二份",
        )
        assertEquals(
            listOf(2),
            plan.eventRegistry.membersOf("EVT").map { it.physicalIndex },
            "链接产物的 base 事件成员进四桶表",
        )
        val bypassRoot = Files.createDirectory(dir.resolve("bypass"))
        val bypass = GameSession(gameRoot = bypassRoot, program = base, metadata = metadata)
        assertNull(bypass.runtime.eventRegistry, "旁路态不得带四桶表")
    }

    @Test
    fun `源码哨兵_派发器读四桶表且会话单路注入`() {
        val runtimeSource = sourceOf("src/main/kotlin/com/eratw/port/core/proc/ProcessRuntime.kt")
        assertTrue(runtimeSource.contains("registry.membersOf(name)"), "executeEvent 必须读四桶表")
        assertTrue(
            runtimeSource.contains("EventMemberRef(it.physicalIndex"),
            "派发成员必须来自四桶表的物理下标，不是 FunctionTable 重建",
        )
        assertTrue(
            runtimeSource.contains("table.eventDispatchOrder(name)"),
            "旁路（无四桶表）必须吃并列加桶展开，不是排他单桶（在册 B23）",
        )
        val sessionSource = sourceOf("src/main/kotlin/com/eratw/port/core/proc/GameSession.kt")
        assertTrue(
            sessionSource.contains("eventRegistry = modChain?.eventRegistry"),
            "会话必须单路注入 plan 的四桶表",
        )
    }
}
