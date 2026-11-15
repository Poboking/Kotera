package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclaredInteractionHook
import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredJobTemplate
import com.eratw.port.core.patch.DeclaredPhase
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.JobTemplateDeclaration
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.JobContext
import com.eratw.port.modapi.ModValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * §20 facade 适配器契约闸（v6.6-c）。
 *
 * 钉住：四个适配器把本包身份注入引擎（mod 侧 API 不暴露包身份）；错误包绑定 fail-closed；
 * facade 与引擎之间的缝可直接消费。
 */
class ModExtensionApiAdapterTest {

    private fun hookDecl(pkg: String, phase: HookPhase = HookPhase.PREFIX): DeclaredHook =
        DeclaredHook(pkg, HookDeclaration("COM300", phase))

    @Test
    fun `HookApiAdapter_绑定带本包身份`() {
        val pipeline = HookPipeline.of(listOf(hookDecl("pkgA"), hookDecl("pkgB")))
        val order = mutableListOf<String>()
        HookApiAdapter(pipeline, "pkgA").register("COM300", ModHookPhase.PREFIX) {
            order += "A"
            HookResult.Continue
        }
        HookApiAdapter(pipeline, "pkgB").register("COM300", ModHookPhase.PREFIX) {
            order += "B"
            HookResult.Continue
        }
        pipeline.dispatch("COM300", emptyArray()) {
            order += "ORIG"
            ModValue.of(1L)
        }
        assertEquals(listOf("A", "B", "ORIG"), order)
    }

    @Test
    fun `PhaseApiAdapter_订阅带本包身份`() {
        val registry = PhaseRegistry.of(
            listOf(
                DeclaredPhase("pkgA", PhaseDeclaration("TICK")),
                DeclaredPhase("pkgB", PhaseDeclaration("TICK")),
            ),
            packageOrder = listOf("pkgA", "pkgB"),
        )
        val order = mutableListOf<String>()
        PhaseApiAdapter(registry, "pkgB").subscribe("TICK") { order += "B" }
        PhaseApiAdapter(registry, "pkgA").subscribe("TICK") { order += "A" }
        registry.dispatch(GameTime(1, "TICK"))
        assertEquals(listOf("A", "B"), order)
    }

    @Test
    fun `JobApiAdapter_绑定带本包身份`() {
        val scheduler = JobScheduler.of(
            listOf(DeclaredJob("pkgA", JobDeclaration("j1", phase = "DAY_END", firstDay = 5))),
        )
        var ran = 0
        JobApiAdapter(scheduler, "pkgA").bind("j1") { ran++ }
        assertEquals(1, scheduler.runDue(GameTime(5, "DAY_END")).fired.size)
        assertEquals(1, ran)
    }

    @Test
    fun `InteractionApiAdapter_注册与运行`() {
        val pipeline = InteractionPipeline.of(
            listOf(
                DeclaredInteractionHook(
                    "pkgA",
                    InteractionHookDeclaration(InteractionPhase.PRE),
                ),
            ),
        )
        var hookRan = false
        val api = InteractionApiAdapter(pipeline, "pkgA")
        api.registerHook(ModInteractionPhase.PRE) {
            hookRan = true
            InteractionResult.CONTINUE
        }
        val context = InteractionContext(
            interactionId = "I1",
            actor = EntityId(1L),
            targets = listOf(EntityId(2L)),
        )
        val result = api.run(context) { ModValue.of(9L) }
        assertTrue(hookRan)
        assertEquals(ModValue.of(9L), result.returnValue)
    }

    @Test
    fun `错误包绑定拒绝`() {
        val hooks = HookPipeline.of(listOf(hookDecl("pkgA")))
        assertThrows<IllegalArgumentException> {
            HookApiAdapter(hooks, "pkgB").register("COM300", ModHookPhase.PREFIX) {
                HookResult.Continue
            }
        }

        val scheduler = JobScheduler.of(
            listOf(DeclaredJob("pkgA", JobDeclaration("j1", phase = "DAY_END"))),
        )
        assertThrows<IllegalArgumentException> {
            JobApiAdapter(scheduler, "pkgB").bind("j1") { }
        }

        val interactions = InteractionPipeline.of(
            listOf(
                DeclaredInteractionHook(
                    "pkgA",
                    InteractionHookDeclaration(InteractionPhase.PRE),
                ),
            ),
        )
        assertThrows<IllegalArgumentException> {
            InteractionApiAdapter(interactions, "pkgB").registerHook(ModInteractionPhase.PRE) {
                InteractionResult.CONTINUE
            }
        }
    }

    private fun declaredTemplate(pkg: String, key: String): DeclaredJobTemplate =
        DeclaredJobTemplate(pkg, JobTemplateDeclaration(key, phase = "DAY_END"))

    @Test
    fun `JobApiAdapter_动态模板四方法都带本包身份`() {
        val scheduler = JobScheduler.of(
            emptyList(),
            null,
            listOf(declaredTemplate("pkgA", "APPT")),
        )
        val api = JobApiAdapter(scheduler, "pkgA")
        val contexts = mutableListOf<JobContext>()
        val reg = api.bindTemplate("APPT") { contexts += it }
        val key = api.scheduleOnce("APPT", 10L, EntityId(7L))
        assertEquals("pkgA::APPT#0", key)
        assertTrue(api.reschedule(key, 12L))
        assertTrue(api.cancel(key))
        assertFalse(api.cancel(key))
        assertTrue(scheduler.runDue(GameTime(12L, "DAY_END")).firedInstances.isEmpty())
        assertTrue(contexts.isEmpty())

        // 撤销 bindTemplate 注册后实例照常推进但不调用 handler。
        reg.unregister()
        val unboundKey = api.scheduleOnce("APPT", 20L)
        assertTrue(scheduler.runDue(GameTime(20L, "DAY_END")).firedInstances.isEmpty())
        assertEquals(
            JobState.DONE,
            scheduler.instances().single { it.instanceKey == unboundKey }.state,
        )
        assertTrue(contexts.isEmpty())

        // 重新绑定后 handler 与 payloadRef 透传。
        api.bindTemplate("APPT") { contexts += it }
        val liveKey = api.scheduleOnce("APPT", 25L, EntityId(9L))
        val report = scheduler.runDue(GameTime(25L, "DAY_END"))
        assertEquals(1, report.firedInstances.size)
        assertEquals(liveKey, contexts.single().instanceKey)
        assertEquals(EntityId(9L), contexts.single().payloadRef)
    }

    @Test
    fun `JobApiAdapter_错误包模板入口fail_closed`() {
        val scheduler = JobScheduler.of(
            emptyList(),
            null,
            listOf(declaredTemplate("pkgA", "APPT")),
        )
        val wrong = JobApiAdapter(scheduler, "pkgB")
        assertThrows<IllegalArgumentException> { wrong.bindTemplate("APPT") { } }
        assertThrows<IllegalArgumentException> { wrong.scheduleOnce("APPT", 1L) }

        val key = JobApiAdapter(scheduler, "pkgA").scheduleOnce("APPT", 1L)
        assertFalse(wrong.cancel(key))
        assertFalse(wrong.reschedule(key, 2L))
    }
}
