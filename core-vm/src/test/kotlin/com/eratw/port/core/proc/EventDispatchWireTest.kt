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
        assertEquals(listOf(2, 3, 1, 4), members.map { it.physicalIndex })
        assertTrue(members[0].isOnly, "ONLY 旗标必须随成员带出，不许靠 bucket 反推")
        assertEquals(0, members[0].bucket)
        val rt = runtime(p, metas, reg)
        assertTrue(rt.executeEvent("EVT"))
        assertEquals("o1\n", rt.output.toString(), "ONLY 命中后后续桶全部终止")
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
        val sessionSource = sourceOf("src/main/kotlin/com/eratw/port/core/proc/GameSession.kt")
        assertTrue(
            sessionSource.contains("eventRegistry = modChain?.eventRegistry"),
            "会话必须单路注入 plan 的四桶表",
        )
    }
}
