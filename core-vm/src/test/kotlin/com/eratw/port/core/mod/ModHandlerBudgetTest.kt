package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclaredInteractionHook
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase as CoreHookPhase
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 作者处理器单次耗时预算闸（§18.5 阈值诊断）。
 *
 * 钉住六件事：
 * 1. 未超阈值 / 恰好等于阈值：不记诊断、不计数（判据是严格大于）；
 * 2. 超阈值：记**一条**诊断（domain / packageId / key / reason / detail 全对）且该包计数 +1；
 * 3. 处理器抛异常：异常**原样**穿出，耗时照记（抛错的慢处理器一样是慢的）；
 * 4. `reset` 清计数不动诊断环；计数按 packageId 隔离；
 * 5. 三类接线点（hook / event / interaction）装上预算后真的计时、摘掉后完全旁路
 *    （用「时钟被读次数」证明旁路 = 不读时钟）；
 * 6. 宿主装配：`host.handlerBudget` 在 `install` 前赋值即扇出到共享 hook 引擎与交互管线。
 */
class ModHandlerBudgetTest {

    // ------------------------------------------------------------ 预算本体

    @Test
    fun `未超阈值不记诊断且计数为零`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD, diagnostics = diagnostics)

        val value = budget.run("pkgA", DiagnosticDomain.HOOK, "F.PREFIX") {
            now += THRESHOLD - 1
            "ok"
        }

        assertEquals("ok", value, "预算不得改变处理器返回值")
        assertTrue(diagnostics.snapshot().isEmpty(), "未超阈值不得记诊断")
        assertEquals(0L, budget.slowCount("pkgA"))
    }

    @Test
    fun `恰好等于阈值不记_严格大于才记`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD, diagnostics = diagnostics)

        budget.run("pkgA", DiagnosticDomain.HOOK, "F.PREFIX") { now += THRESHOLD }
        assertTrue(diagnostics.snapshot().isEmpty(), "elapsed == threshold 不是越阈值")
        assertEquals(0L, budget.slowCount("pkgA"))

        budget.run("pkgA", DiagnosticDomain.HOOK, "F.PREFIX") { now += THRESHOLD + 1 }
        assertEquals(1, diagnostics.snapshot().size)
        assertEquals(1L, budget.slowCount("pkgA"))
    }

    @Test
    fun `超阈值记一条诊断且字段全对`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD, diagnostics = diagnostics)

        budget.run("pkgA", DiagnosticDomain.HOOK, "F.PREFIX") { now += OVER }

        val record = diagnostics.snapshot().single()
        assertEquals(DiagnosticDomain.HOOK, record.domain)
        assertEquals("pkgA", record.packageId)
        assertEquals("F.PREFIX", record.key)
        assertEquals(DiagnosticOutcome.OK, record.outcome, "慢不等于失败：不中断、不改语义，outcome 仍是 OK")
        assertEquals(ModHandlerBudget.REASON_SLOW, record.reasonCode)
        assertEquals("elapsedNanos=$OVER thresholdNanos=$THRESHOLD", record.detail)
        assertEquals(1L, budget.slowCount("pkgA"))
    }

    @Test
    fun `处理器抛异常原样穿出且耗时照记`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD, diagnostics = diagnostics)

        val ex = assertThrows<IllegalStateException> {
            budget.run("pkgA", DiagnosticDomain.INTERACTION, "INT-1") {
                now += OVER
                error("boom")
            }
        }

        assertEquals("boom", ex.message, "异常必须原样抛，不许被预算包一层")
        assertEquals(1, diagnostics.snapshot().size, "抛错的慢处理器一样记")
        assertEquals(1L, budget.slowCount("pkgA"))
    }

    @Test
    fun `reset清计数不动诊断环`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD, diagnostics = diagnostics)
        budget.run("pkgA", DiagnosticDomain.HOOK, "F.PREFIX") { now += OVER }
        assertEquals(1L, budget.slowCount("pkgA"))

        budget.reset()

        assertEquals(0L, budget.slowCount("pkgA"))
        assertEquals(1, diagnostics.snapshot().size, "reset 只清计数；诊断环是会话历史，不清")
    }

    @Test
    fun `计数按包隔离`() {
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD)

        budget.run("pkgA", DiagnosticDomain.HOOK, "F.PREFIX") { now += OVER }
        budget.run("pkgA", DiagnosticDomain.HOOK, "F.POSTFIX") { now += OVER }
        budget.run("pkgB", DiagnosticDomain.HOOK, "F.PREFIX") { now += THRESHOLD - 1 }

        assertEquals(2L, budget.slowCount("pkgA"))
        assertEquals(0L, budget.slowCount("pkgB"))
        assertEquals(0L, budget.slowCount("never-seen"))
    }

    @Test
    fun `非法阈值当场拒`() {
        assertThrows<IllegalArgumentException> { ModHandlerBudget(thresholdNanos = 0L) }
        assertThrows<IllegalArgumentException> { ModHandlerBudget(thresholdNanos = -1L) }
    }

    // ------------------------------------------------------------ 三类接线点

    @Test
    fun `hook接线点装上预算计时_摘掉完全旁路`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        var clockReads = 0
        val budget = ModHandlerBudget(
            clock = { clockReads++; now },
            thresholdNanos = THRESHOLD,
            diagnostics = diagnostics,
        )
        val pipeline = HookPipeline.of(
            listOf(DeclaredHook("modH", HookDeclaration("F", CoreHookPhase.PREFIX))),
        )
        pipeline.bind("F", ModHookPhase.PREFIX, "modH") {
            now += OVER
            HookResult.Continue
        }

        pipeline.handlerBudget = budget
        pipeline.dispatch("F", emptyArray()) { null }
        val readsWithBudget = clockReads
        assertTrue(readsWithBudget > 0, "装上预算必须读时钟")
        val record = diagnostics.snapshot().single()
        assertEquals(DiagnosticDomain.HOOK, record.domain)
        assertEquals("F.PREFIX", record.key)
        assertEquals(1L, budget.slowCount("modH"))

        pipeline.handlerBudget = null
        pipeline.dispatch("F", emptyArray()) { null }
        assertEquals(readsWithBudget, clockReads, "摘掉预算后必须零时钟读（完全旁路）")
    }

    @Test
    fun `event接线点装上预算计时_摘掉完全旁路`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        var clockReads = 0
        val budget = ModHandlerBudget(
            clock = { clockReads++; now },
            thresholdNanos = THRESHOLD,
            diagnostics = diagnostics,
        )
        val store = VariableStore()
        val registry = EventDispatchRegistry()
        registry.register("EVT", EventBucket.NORMAL, "modE") {
            now += OVER
            EventResult.CONTINUE
        }

        ModEventWiring.install(store, registry, { }, null, budget)
        ModEventWiring.runBucket(store, "EVT", EventBucket.NORMAL.ordinal)
        val readsWithBudget = clockReads
        assertTrue(readsWithBudget > 0, "装上预算必须读时钟")
        val record = diagnostics.snapshot().single()
        assertEquals(DiagnosticDomain.EVENT, record.domain, "事件桶派发归 EVENT 诊断域")
        assertEquals("EVT.NORMAL", record.key)
        assertEquals("modE", record.packageId)
        assertEquals(1L, budget.slowCount("modE"))

        ModEventWiring.install(store, registry, { }, null, null)
        ModEventWiring.runBucket(store, "EVT", EventBucket.NORMAL.ordinal)
        assertEquals(readsWithBudget, clockReads, "摘掉预算后必须零时钟读（完全旁路）")
    }

    /**
     * 事件处理器超阈值 ⇒ 诊断 `domain == EVENT`（专项钉域，不靠「接线点旁路」用例顺带断言）。
     *
     * 将来若有人把 EVENT 挪到旧域中间、或让事件接线回退到别的域，本用例与形状闸会同时红。
     */
    @Test
    fun `event超阈值诊断域是EVENT`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        val budget = ModHandlerBudget(clock = { now }, thresholdNanos = THRESHOLD, diagnostics = diagnostics)
        val store = VariableStore()
        val registry = EventDispatchRegistry()
        registry.register("EVT_DOMAIN", EventBucket.PRI, "modEvt") {
            now += OVER
            EventResult.CONTINUE
        }

        ModEventWiring.install(store, registry, { }, null, budget)
        ModEventWiring.runBucket(store, "EVT_DOMAIN", EventBucket.PRI.ordinal)

        val record = diagnostics.snapshot().single()
        assertEquals(DiagnosticDomain.EVENT, record.domain, "event 处理器诊断必须落 EVENT 域，不许借 PHASE")
        assertEquals("EVT_DOMAIN.PRI", record.key)
        assertEquals("modEvt", record.packageId)
        assertEquals(ModHandlerBudget.REASON_SLOW, record.reasonCode)
        assertTrue(
            "\"domain\":\"EVENT\"" in diagnostics.exportJsonLines(),
            "导出按名写 domain，工具侧按 EVENT 判读",
        )
    }

    @Test
    fun `interaction接线点装上预算计时_摘掉完全旁路`() {
        val diagnostics = RuntimeDiagnostics()
        var now = 0L
        var clockReads = 0
        val budget = ModHandlerBudget(
            clock = { clockReads++; now },
            thresholdNanos = THRESHOLD,
            diagnostics = diagnostics,
        )
        val pipeline = InteractionPipeline.of(
            listOf(DeclaredInteractionHook("pkgA", InteractionHookDeclaration(InteractionPhase.PRE))),
        )
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            now += OVER
            InteractionResult.CONTINUE
        }

        pipeline.handlerBudget = budget
        pipeline.run(interactionContext("INT-1")) { ModValue.of(1L) }
        val readsWithBudget = clockReads
        assertTrue(readsWithBudget > 0, "装上预算必须读时钟")
        val record = diagnostics.snapshot().single()
        assertEquals(DiagnosticDomain.INTERACTION, record.domain)
        assertEquals("INT-1", record.key)
        assertEquals(1L, budget.slowCount("pkgA"))

        pipeline.handlerBudget = null
        pipeline.run(interactionContext("INT-1")) { ModValue.of(1L) }
        assertEquals(readsWithBudget, clockReads, "摘掉预算后必须零时钟读（完全旁路）")
    }

    // ------------------------------------------------------------ 宿主装配

    @Test
    fun `宿主在install前赋预算即扇出到共享hook引擎与交互管线`() {
        val budget = ModHandlerBudget(thresholdNanos = THRESHOLD)
        val host = newHost("modL")
        host.handlerBudget = budget

        host.install(listOf("modL" to traceMod()))

        assertSame(budget, host.hookPipeline.handlerBudget, "hook 引擎必须拿到同一个预算实例")
        assertSame(budget, host.interactionPipeline.handlerBudget, "交互管线必须拿到同一个预算实例")
    }

    // ------------------------------------------------------------ 夹具

    private fun interactionContext(id: String): InteractionContext = InteractionContext(
        interactionId = id,
        actor = EntityId(1L),
        targets = listOf(EntityId(2L), EntityId(3L)),
        primaryTarget = EntityId(2L),
    )

    private fun traceMod(): EraMod = object : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(pkg: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(pkg),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { },
        runtime = FakeRuntime(),
    )

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private fun newSnapshot(pkg: String): DeclarationSnapshot =
        DeclarationRegistry()
            .addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)))
            .freeze()

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }

    private companion object {
        /** 假阈值（纳秒）：小到假时钟一步就能越过。 */
        const val THRESHOLD: Long = 100L

        /** 一次「明显超阈值」的假耗时。 */
        const val OVER: Long = 250L
    }
}
