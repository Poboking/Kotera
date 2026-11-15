package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.LinkInputs
import com.eratw.port.core.patch.LinkableModule
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinker
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * RT2 G2 波（P1-5 余量一）**宿主侧三表原子替换接线**的出口判据。
 *
 * `wave/f3` 已落 main 的核心层 `ModChainRuntimePlan` 负责「链接产物 → 运行期三视图」的
 * 派生与自检；本文件锁的是**另一半**：`GameSession` 是否真的把这三张表一次替换进去。
 * 两者缺一，都会回到「链接产物算了但正式会话仍跑 base 三表」的死链。
 *
 * 断言面（全部走真 `ModuleLinker.link` + 真 `GameSession`，不手搓 dispatch 冒充产物）：
 * - 有覆盖：`runtime.program` / 运行期 `FunctionTable` / `runtime.functionDispatch`
 *   三处与 plan **同一份实例**（新 Program 配旧 Meta 表的静默错位无处可藏）；
 * - 旁路态：`modChain=null` 时三表与 base 逐位一致（G 车第一批行为不变）；
 * - `HAS_OVERRIDES` 消费点：无覆盖时 dispatch 不进 VM（null = 物理下标直调快路径）；
 * - 互斥闸：显式 `functionDispatch` 与 `modChain` 共存整包拒（防两套替换源漂移）。
 *
 * 运行期 `FunctionTable` 在 `ProcessRuntime` 里是 private 字段，测试用一次反射读实例身份；
 * 生产代码不为测试开口子。
 */
class ModChainSessionWireTest {

    private class TestModule(
        override val descriptor: ModuleDescriptor,
        override val program: Program,
        override val functions: List<FuncMeta>,
        private val signatures: Map<String, String> = emptyMap(),
    ) : LinkableModule {
        override fun signatureHashOf(functionKey: String): String =
            signatures[functionKey.uppercase(Locale.ROOT)] ?: ""
    }

    /** base：ENTRY@0 / X@1，各一条 _RET（与 gameSession 侧车配对的最小真 Program）。 */
    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"), FuncMeta(index = 1, key = "X"))

    private val baseSignature: (String) -> String = { key ->
        if (key.uppercase(Locale.ROOT) == "X") "sig-x" else ""
    }

    private fun moduleProgram(key: String): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction(key, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(entry)
    }

    /** 真链接 → 核心层真派生（与 ModChainRuntimePlanTest 同一口径，不给测试留第二套）。 */
    private fun plan(override: Boolean): ModChainRuntimePlan {
        val module = if (override) {
            TestModule(
                descriptor = ModuleDescriptor(packageId = "mod.ovr", overrideFunctions = setOf("X")),
                program = moduleProgram("X"),
                functions = listOf(FuncMeta(index = 0, key = "X")),
                signatures = mapOf("X" to "sig-x"),
            )
        } else {
            TestModule(
                descriptor = ModuleDescriptor(packageId = "mod.add"),
                program = moduleProgram("ANEW"),
                functions = listOf(FuncMeta(index = 0, key = "ANEW")),
            )
        }
        val linked = ModuleLinker.link(
            LinkInputs(baseProgram(), baseMetas, listOf(module), ResolverPolicy.MODERN, baseSignature),
        )
        return ModChainRuntimePlan.of(linked)
    }

    private fun bootMeta(): BootMetadata = BootMetadata(functions = baseMetas)

    private fun session(
        root: Path,
        plan: ModChainRuntimePlan?,
        base: Program = baseProgram(),
    ): GameSession = GameSession(
        gameRoot = root,
        program = base,
        metadata = bootMeta(),
        modChain = plan,
    )

    /** `ProcessRuntime.table` 是 private；只读实例身份，不改生产可见性。 */
    private fun runtimeTableOf(session: GameSession): FunctionTable {
        val field = ProcessRuntime::class.java.getDeclaredField("table")
        field.isAccessible = true
        return field.get(session.runtime) as FunctionTable
    }

    // ------------------------------------------------------------------
    // 三表原子替换
    // ------------------------------------------------------------------

    @Test
    fun `真装配三视图原子替换进GameSession_三处只从plan取值`(@TempDir dir: Path) {
        val plan = plan(override = true)
        val s = session(dir, plan)

        assertTrue(plan.hasOverrides)
        // kotlin.test 口径：assertSame(expected, actual, message) —— message 在末位。
        assertSame(plan.program, s.runtime.program, "进 VM 的 Program 必须是链接产物")
        assertSame(
            plan.functionTable,
            runtimeTableOf(s),
            "运行期 FunctionTable 必须是核心层派生的同一实例（不许按 base metas 重建）",
        )
        assertSame(
            plan.functionDispatch,
            s.runtime.functionDispatch,
            "dispatch 必须经 HAS_OVERRIDES 消费点交给 VM",
        )
        assertTrue(s.runtime.functionDispatch!!.indices.any { s.runtime.functionDispatch!![it] != it })
        assertEquals(plan.program.functions.size, plan.functionTable.all().size)
    }

    @Test
    fun `旁路态GameSession三表与base逐位一致`(@TempDir dir: Path) {
        val base = baseProgram()
        val s = session(dir, null, base)
        assertSame(base, s.runtime.program, "旁路态进 VM 的 Program 就是 base 原实例")
        assertNull(s.runtime.functionDispatch, "旁路态 dispatch 恒 null（直调快路径）")
        assertEquals(baseMetas.size, runtimeTableOf(s).all().size)
        assertEquals(baseMetas.map { it.key }, runtimeTableOf(s).all().map { it.key })
    }

    @Test
    fun `无覆盖声明时GameSession不把dispatch交给VM`(@TempDir dir: Path) {
        val plan = plan(override = false)
        val s = session(dir, plan)
        assertTrue(!plan.hasOverrides)
        assertNull(plan.functionDispatch)
        assertNull(s.runtime.functionDispatch, "无覆盖时 VM 走物理下标直调，不交查表")
        assertSame(plan.program, s.runtime.program, "只加新函数也要换成链接产物的 Program")
        assertSame(plan.functionTable, runtimeTableOf(s))
    }

    @Test
    fun `modChain与显式functionDispatch互斥整包拒`(@TempDir dir: Path) {
        val plan = plan(override = true)
        val e = assertFailsWith<IllegalArgumentException> {
            GameSession(
                gameRoot = dir,
                program = baseProgram(),
                metadata = bootMeta(),
                functionDispatch = IntArray(plan.program.functions.size) { it },
                modChain = plan,
            )
        }
        assertTrue(e.message!!.contains("互斥"), "异常须点名互斥，实得：${e.message}")
    }
}
