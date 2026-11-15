package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.DeclarationDomain
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.PhaseDecl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * §20.4 声明扩域 + 适配器 + 三引擎接线契约闸（v6.6-c）。
 *
 * 钉住：hook 跨包同目标合法 / 同包重复拒绝、相位全局命名空间与固定相位保护、任务相位
 * 引用可解析、jobId 包内唯一、交互钩子跨包合法、ModManifest 四类声明翻译、声明快照
 * 可直接驱动 HookPipeline / PhaseRegistry / JobScheduler / InteractionPipeline。
 */
class ExtensionDeclarationTest {

    @Test
    fun `hook声明_跨包同目标合法_同包同相位重复拒绝`() {
        val a = ModuleDescriptor(
            packageId = "pkgA",
            hookDeclarations = listOf(HookDeclaration("COM300", HookPhase.PREFIX)),
        )
        val b = ModuleDescriptor(
            packageId = "pkgB",
            hookDeclarations = listOf(HookDeclaration("COM300", HookPhase.PREFIX)),
        )
        val snapshot = DeclarationRegistry().addPackage(a).addPackage(b).freeze()
        assertEquals(2, snapshot.hookDeclarations.size)
        assertEquals(2, snapshot.hooksFor("com300").size)

        val duplicate = ModuleDescriptor(
            packageId = "pkgC",
            hookDeclarations = listOf(
                HookDeclaration("COM300", HookPhase.PREFIX),
                HookDeclaration("COM300", HookPhase.PREFIX),
            ),
        )
        val error = assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(duplicate).freeze()
        }
        assertTrue(error.conflicts.any { it.domain == DeclarationDomain.HOOK })
    }

    @Test
    fun `同包同目标不同相位合法`() {
        val descriptor = ModuleDescriptor(
            packageId = "pkgA",
            hookDeclarations = listOf(
                HookDeclaration("COM300", HookPhase.PREFIX),
                HookDeclaration("COM300", HookPhase.POSTFIX),
            ),
        )
        val snapshot = DeclarationRegistry().addPackage(descriptor).freeze()
        assertEquals(2, snapshot.hookDeclarations.size)
    }

    @Test
    fun `相位名跨包重复拒绝_占用固定相位拒绝`() {
        val a = ModuleDescriptor(
            packageId = "pkgA",
            phaseDeclarations = listOf(PhaseDeclaration("FAMILY_TICK")),
        )
        val b = ModuleDescriptor(
            packageId = "pkgB",
            phaseDeclarations = listOf(PhaseDeclaration("family_tick")),
        )
        val error = assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(a).addPackage(b).freeze()
        }
        assertTrue(error.conflicts.any { it.domain == DeclarationDomain.PHASE_JOB })

        val builtin = ModuleDescriptor(
            packageId = "pkgC",
            phaseDeclarations = listOf(PhaseDeclaration("DAY_END")),
        )
        val error2 = assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(builtin).freeze()
        }
        assertTrue(error2.conflicts.any { it.domain == DeclarationDomain.PHASE_JOB })
    }

    @Test
    fun `任务相位引用须可解析`() {
        val bad = ModuleDescriptor(
            packageId = "pkgA",
            jobDeclarations = listOf(JobDeclaration("j1", phase = "NOPE")),
        )
        assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(bad).freeze()
        }

        val okBuiltin = ModuleDescriptor(
            packageId = "pkgA",
            jobDeclarations = listOf(JobDeclaration("j1", phase = "DAY_END")),
        )
        assertEquals(1, DeclarationRegistry().addPackage(okBuiltin).freeze().jobDeclarations.size)

        val okCustom = ModuleDescriptor(
            packageId = "pkgA",
            phaseDeclarations = listOf(PhaseDeclaration("FAMILY_TICK")),
            jobDeclarations = listOf(JobDeclaration("j1", phase = "FAMILY_TICK")),
        )
        assertEquals(1, DeclarationRegistry().addPackage(okCustom).freeze().jobDeclarations.size)
    }

    @Test
    fun `同包jobId重复拒绝`() {
        val duplicate = ModuleDescriptor(
            packageId = "pkgA",
            jobDeclarations = listOf(
                JobDeclaration("j1", phase = "DAY_END"),
                JobDeclaration("j1", phase = "DAY_START"),
            ),
        )
        val error = assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(duplicate).freeze()
        }
        assertTrue(error.conflicts.any { it.domain == DeclarationDomain.PHASE_JOB && it.key == "pkgA|j1" })
    }

    @Test
    fun `interaction钩子同包同阶段重复拒绝_跨包合法`() {
        val a = ModuleDescriptor(
            packageId = "pkgA",
            interactionHookDeclarations = listOf(InteractionHookDeclaration(InteractionPhase.PRE)),
        )
        val b = ModuleDescriptor(
            packageId = "pkgB",
            interactionHookDeclarations = listOf(InteractionHookDeclaration(InteractionPhase.PRE)),
        )
        val snapshot = DeclarationRegistry().addPackage(a).addPackage(b).freeze()
        assertEquals(2, snapshot.interactionHooksFor(InteractionPhase.PRE).size)

        val duplicate = ModuleDescriptor(
            packageId = "pkgC",
            interactionHookDeclarations = listOf(
                InteractionHookDeclaration(InteractionPhase.PRE),
                InteractionHookDeclaration(InteractionPhase.PRE),
            ),
        )
        assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(duplicate).freeze()
        }
    }

    @Test
    fun `ModDeclarationAdapter翻译四类声明`() {
        val manifest = ModManifest(
            packageId = "demo.mod",
            hooks = listOf(
                HookDecl("COM300", ModHookPhase.PREFIX, priority = 5, mayMutateArgs = true),
            ),
            interactionHooks = listOf(InteractionHookDecl(ModInteractionPhase.SETTLEMENT, priority = 2)),
            phases = listOf(PhaseDecl("FAMILY_TICK", priority = 1)),
            jobs = listOf(JobDecl("daily", phase = "FAMILY_TICK", firstDay = 3, everyDays = 2)),
        )
        val snapshot = DeclarationRegistry()
            .addPackage(ModDeclarationAdapter.toDescriptor(manifest))
            .freeze()

        assertEquals(1, snapshot.hookDeclarations.size)
        val hook = snapshot.hookDeclarations.single().declaration
        assertEquals(HookPhase.PREFIX, hook.phase)
        assertEquals(5, hook.priority)
        assertTrue(hook.mayMutateArgs)

        assertEquals(1, snapshot.interactionHookDeclarations.size)
        assertEquals(
            InteractionPhase.SETTLEMENT,
            snapshot.interactionHookDeclarations.single().declaration.phase,
        )

        assertEquals(1, snapshot.phaseDeclarations.size)
        assertEquals("FAMILY_TICK", snapshot.phaseDeclarations.single().declaration.name)

        assertEquals(1, snapshot.jobDeclarations.size)
        val job = snapshot.jobDeclarations.single().declaration
        assertEquals("FAMILY_TICK", job.phase)
        assertEquals(3L, job.firstDay)
        assertEquals(2L, job.everyDays)
    }

    @Test
    fun `声明快照可驱动三引擎`() {
        val manifest = ModManifest(
            packageId = "pkgA",
            hooks = listOf(HookDecl("COM300", ModHookPhase.PREFIX, priority = 1)),
            phases = listOf(PhaseDecl("FAMILY_TICK")),
            jobs = listOf(JobDecl("j1", phase = "FAMILY_TICK", firstDay = 5, everyDays = 3)),
            interactionHooks = listOf(InteractionHookDecl(ModInteractionPhase.PRE)),
        )
        val snapshot = DeclarationRegistry()
            .addPackage(ModDeclarationAdapter.toDescriptor(manifest))
            .freeze()

        val hooks = HookPipeline.of(snapshot.hookDeclarations)
        assertTrue(hooks.hasHooks("com300"))

        val phases = PhaseRegistry.of(snapshot.phaseDeclarations, snapshot.packageIds)
        assertTrue(phases.isDeclared("family_tick"))

        val jobs = JobScheduler.of(snapshot.jobDeclarations)
        assertEquals(1, jobs.dueJobs(GameTime(5, "FAMILY_TICK")).size)
        assertEquals(0, jobs.dueJobs(GameTime(4, "FAMILY_TICK")).size)

        val interactions = InteractionPipeline.of(snapshot.interactionHookDeclarations)
        assertEquals(0, interactions.depth())
    }

    @Test
    fun `声明到绑定到dispatch的端到端`() {
        val manifest = ModManifest(
            packageId = "pkgA",
            hooks = listOf(HookDecl("COM300", ModHookPhase.PREFIX)),
        )
        val snapshot = DeclarationRegistry()
            .addPackage(ModDeclarationAdapter.toDescriptor(manifest))
            .freeze()

        val hooks = HookPipeline.of(snapshot.hookDeclarations)
        val order = mutableListOf<String>()
        hooks.bind("COM300", ModHookPhase.PREFIX, "pkgA") {
            order += "HOOK"
            HookResult.Continue
        }
        hooks.dispatch("COM300", emptyArray()) {
            order += "ORIGINAL"
            ModValue.of(1L)
        }
        assertEquals(listOf("HOOK", "ORIGINAL"), order)
    }
}
