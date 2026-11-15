package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.DeclarationDomain
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.JobTemplateDeclaration
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.JobContext
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M6-3 生产接线闸（RT2 波 G 车 G3，era-arch-v61u.md §21.1 / §20.2 / §5 / §18.3）。
 *
 * 钉住 jobTemplate 声明事务与动态任务 API 的生产可达性：
 * 1. `DeclarationSnapshot.jobTemplateDeclarations` 保声明序且 `jobTemplatesFor` 按包过滤；
 * 2. `freeze()` 拒绝包内重复 templateKey、未声明相位，且 templateKey 与 jobId 命名空间分离；
 * 3. `ModContextImpl.jobScheduler` 收到快照模板，`jobs` 五个入口全部委托 `JobApiAdapter`；
 * 4. `bindTemplate` 的 Registration 经 track 收集，`onDisable` 后可撤销；
 * 5. 未声明模板 IAE、cancel/reschedule 幂等、未绑定 handler 的实例照常推进。
 *
 * 全部断言只经公开声明/ facade / 调度器观测面，不读私有字段。
 */
class JobApiWireTest {

    private fun kernel(): EntityKernel = EntityKernel(
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

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private fun newContext(
        packageId: String,
        snapshot: DeclarationSnapshot,
        registrations: MutableList<Registration> = mutableListOf(),
    ): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = snapshot,
        entityKernel = kernel(),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
        registrationSink = { registrations += it },
    )

    private fun templateSnapshot(
        packageId: String = "mod.a",
        templateKey: String = "APPT",
        phase: String = "DAY_END",
    ): DeclarationSnapshot = DeclarationRegistry()
        .addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = packageId,
                    jobTemplates = listOf(JobTemplateDecl(templateKey, phase = phase)),
                ),
            ),
        )
        .freeze()

    @Test
    fun `快照 jobTemplateDeclarations 保声明序且 jobTemplatesFor 按包过滤`() {
        val snapshot = DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    jobTemplateDeclarations = listOf(
                        JobTemplateDeclaration("APPT", phase = "DAY_END"),
                        JobTemplateDeclaration("GIFT", phase = "DAY_START", priority = 1),
                    ),
                ),
            )
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.b",
                    jobTemplateDeclarations = listOf(JobTemplateDeclaration("MAIL")),
                ),
            )
            .freeze()

        assertEquals(
            listOf("APPT", "GIFT", "MAIL"),
            snapshot.jobTemplateDeclarations.map { it.declaration.templateKey },
        )
        assertEquals(listOf("mod.a", "mod.a", "mod.b"), snapshot.jobTemplateDeclarations.map { it.packageId })
        assertEquals(
            listOf("APPT", "GIFT"),
            snapshot.jobTemplatesFor("mod.a").map { it.declaration.templateKey },
        )
        assertEquals(
            listOf("MAIL"),
            snapshot.jobTemplatesFor("mod.b").map { it.declaration.templateKey },
        )
        assertTrue(snapshot.jobTemplatesFor("mod.c").isEmpty())
    }

    @Test
    fun `包内 templateKey 重复整包拒绝并点名包与键`() {
        val ex = assertThrows<DeclarationConflictException> {
            DeclarationRegistry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        jobTemplateDeclarations = listOf(
                            JobTemplateDeclaration("APPT"),
                            JobTemplateDeclaration("APPT", phase = "DAY_START"),
                        ),
                    ),
                    source = "A.erbc",
                )
                .freeze()
        }
        val conflict = ex.conflicts.single()
        assertEquals(DeclarationDomain.PHASE_JOB, conflict.domain)
        assertTrue(conflict.key.contains("mod.a"))
        assertTrue(conflict.key.contains("APPT"))
        assertTrue(conflict.reason.contains("重复"))
        assertTrue(ex.message!!.contains("mod.a"))
        assertTrue(ex.message!!.contains("APPT"))
    }

    @Test
    fun `模板相位固定与事务内声明通过_未声明相位拒绝`() {
        val builtin = DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    jobTemplateDeclarations = listOf(
                        JobTemplateDeclaration("APPT", phase = "DAY_START"),
                    ),
                ),
            )
            .freeze()
        assertEquals("DAY_START", builtin.jobTemplatesFor("mod.a").single().declaration.phase)

        val declared = DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    phaseDeclarations = listOf(PhaseDeclaration("TICK")),
                    jobTemplateDeclarations = listOf(
                        JobTemplateDeclaration("APPT", phase = "tick"),
                    ),
                ),
            )
            .freeze()
        assertEquals("tick", declared.jobTemplatesFor("mod.a").single().declaration.phase)

        val ex = assertThrows<DeclarationConflictException> {
            DeclarationRegistry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        jobTemplateDeclarations = listOf(
                            JobTemplateDeclaration("APPT", phase = "NOPE"),
                        ),
                    ),
                )
                .freeze()
        }
        assertTrue(ex.conflicts.single().reason.contains("未声明的相位"))
    }

    @Test
    fun `templateKey 与 jobId 同名不冲突且各自可解析`() {
        val snapshot = DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    jobDeclarations = listOf(JobDeclaration("SAME", phase = "DAY_END", firstDay = 10L)),
                    jobTemplateDeclarations = listOf(JobTemplateDeclaration("SAME")),
                ),
            )
            .freeze()
        assertEquals("SAME", snapshot.jobsOf("mod.a").single().declaration.jobId)
        assertEquals("SAME", snapshot.jobTemplatesFor("mod.a").single().declaration.templateKey)

        val ctx = newContext("mod.a", snapshot)
        var staticRuns = 0
        var templateRuns = 0
        ctx.jobs.bind("SAME") { staticRuns++ }
        ctx.jobs.bindTemplate("SAME") { templateRuns++ }
        ctx.jobs.scheduleOnce("SAME", 10L)
        val report = ctx.jobScheduler.runDue(GameTime(10L, "DAY_END"))
        assertEquals(1, report.fired.size)
        assertEquals(1, report.firedInstances.size)
        assertEquals(1, staticRuns)
        assertEquals(1, templateRuns)
    }
    @Test
    fun `jobs bindTemplate 后 runDue 触发模板实例`() {
        val ctx = newContext("mod.a", templateSnapshot())
        val seen = mutableListOf<JobContext>()
        ctx.jobs.bindTemplate("APPT") { seen += it }
        val key = ctx.jobs.scheduleOnce("APPT", 10L)
        assertEquals("mod.a::APPT#0", key)

        val report = ctx.jobScheduler.runDue(GameTime(10L, "DAY_END"))
        assertEquals(1, report.firedInstances.size)
        assertEquals(1, seen.size)
        assertEquals("APPT", seen.single().templateKey)
        assertEquals(key, seen.single().instanceKey)
        assertEquals(JobState.DONE, ctx.jobScheduler.instances().single().state)
    }

    @Test
    fun `scheduleOnce 返回实例键且 JobContext 透传 templateKey_instanceKey_payloadRef`() {
        val ctx = newContext("mod.a", templateSnapshot())
        var captured: JobContext? = null
        ctx.jobs.bindTemplate("APPT") { captured = it }
        val key = ctx.jobs.scheduleOnce("APPT", 7L, EntityId(123L))

        ctx.jobScheduler.runDue(GameTime(7L, "DAY_END"))
        val context = assertNotNull(captured)
        assertEquals("mod.a", context.packageId)
        assertEquals("APPT", context.templateKey)
        assertEquals(key, context.instanceKey)
        assertEquals(EntityId(123L), context.payloadRef)
        assertEquals(7L, context.time.day)
        assertEquals("DAY_END", context.time.phase)
    }

    @Test
    fun `jobs cancel 幂等且取消后不触发`() {
        val ctx = newContext("mod.a", templateSnapshot())
        var runs = 0
        ctx.jobs.bindTemplate("APPT") { runs++ }
        val key = ctx.jobs.scheduleOnce("APPT", 10L)

        assertTrue(ctx.jobs.cancel(key))
        assertFalse(ctx.jobs.cancel(key))
        assertFalse(ctx.jobs.cancel("mod.a::APPT#99"))
        assertTrue(ctx.jobScheduler.runDue(GameTime(10L, "DAY_END")).firedInstances.isEmpty())
        assertEquals(0, runs)
        assertEquals(JobState.CANCELED, ctx.jobScheduler.instances().single().state)
    }

    @Test
    fun `jobs reschedule 同日 true 改日生效 DONE 后 false`() {
        val ctx = newContext("mod.a", templateSnapshot())
        var runs = 0
        ctx.jobs.bindTemplate("APPT") { runs++ }
        val key = ctx.jobs.scheduleOnce("APPT", 10L)

        assertTrue(ctx.jobs.reschedule(key, 10L))
        assertTrue(ctx.jobs.reschedule(key, 20L))
        assertThrows<IllegalArgumentException> { ctx.jobs.reschedule(key, -1L) }
        assertTrue(ctx.jobScheduler.runDue(GameTime(15L, "DAY_END")).firedInstances.isEmpty())
        assertEquals(0, runs)

        assertEquals(1, ctx.jobScheduler.runDue(GameTime(20L, "DAY_END")).firedInstances.size)
        assertEquals(1, runs)
        assertFalse(ctx.jobs.reschedule(key, 30L))
    }

    @Test
    fun `未声明模板 IAE 且未绑定 handler 的到期实例照常推进`() {
        val ctx = newContext("mod.a", templateSnapshot())
        assertThrows<IllegalArgumentException> { ctx.jobs.bindTemplate("NOPE") { } }
        assertThrows<IllegalArgumentException> { ctx.jobs.scheduleOnce("NOPE", 1L) }
        assertThrows<IllegalArgumentException> { ctx.jobs.scheduleOnce("APPT", -1L) }

        val key = ctx.jobs.scheduleOnce("APPT", 10L)
        val report = ctx.jobScheduler.runDue(GameTime(10L, "DAY_END"))
        assertTrue(report.firedInstances.isEmpty())
        assertTrue(report.failures.isEmpty())
        assertEquals(JobState.DONE, ctx.jobScheduler.instances().single().state)
        assertTrue(ctx.jobScheduler.runDue(GameTime(11L, "DAY_END")).firedInstances.isEmpty())
        assertEquals(key, ctx.jobScheduler.instances().single().instanceKey)
    }
    @Test
    fun `onDisable 撤销 bindTemplate 注册后实例到期不再调 handler`() {
        val registrations = mutableListOf<Registration>()
        val ctx = newContext("mod.a", templateSnapshot(), registrations)
        var templateRuns = 0
        ctx.jobs.bindTemplate("APPT") { templateRuns++ }
        assertEquals(1, registrations.size, "bindTemplate 的 Registration 经 track 收集")

        ctx.jobs.scheduleOnce("APPT", 10L)
        registrations.forEach { it.unregister() }
        val report = ctx.jobScheduler.runDue(GameTime(10L, "DAY_END"))
        assertTrue(report.firedInstances.isEmpty())
        assertEquals(0, templateRuns)
        assertEquals(JobState.DONE, ctx.jobScheduler.instances().single().state)
    }

    @Test
    fun `无模板声明时调度器完全旁路且既有静态任务槽行为不变`() {
        val bare = JobScheduler.of(emptyList(), null, emptyList())
        val emptyReport = bare.runDue(GameTime(10L, "DAY_END"))
        assertTrue(emptyReport.fired.isEmpty())
        assertTrue(emptyReport.firedInstances.isEmpty())
        assertTrue(bare.templates().isEmpty())

        val snapshot = DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    jobDeclarations = listOf(
                        JobDeclaration("J1", phase = "DAY_END", firstDay = 10L, everyDays = null),
                    ),
                ),
            )
            .freeze()
        val ctx = newContext("mod.a", snapshot)
        assertTrue(ctx.jobScheduler.templates().isEmpty())
        var runs = 0
        ctx.jobs.bind("J1") { runs++ }
        assertEquals(1, ctx.jobScheduler.runDue(GameTime(10L, "DAY_END")).fired.size)
        assertEquals(1, runs)
    }

    @Test
    fun `jobs 四个动态入口全部可达且不落默认 UnsupportedOperationException`() {
        val ctx = newContext("mod.a", templateSnapshot())
        val registration = ctx.jobs.bindTemplate("APPT") { }
        val key = ctx.jobs.scheduleOnce("APPT", 10L)
        assertTrue(ctx.jobs.reschedule(key, 12L))
        assertTrue(ctx.jobs.cancel(key))
        assertFalse(ctx.jobs.cancel(key))
        registration.unregister()
        assertEquals("mod.a::APPT#0", key)
    }

    @Test
    fun `JobScheduler 收到快照模板且声明序与 priority 决定模板槽序`() {
        val snapshot = DeclarationRegistry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    jobTemplateDeclarations = listOf(
                        JobTemplateDeclaration("LOW", phase = "DAY_END", priority = 5),
                        JobTemplateDeclaration("HIGH", phase = "DAY_END", priority = 1),
                        JobTemplateDeclaration("SAME_PRIORITY", phase = "DAY_END", priority = 5),
                    ),
                ),
            )
            .freeze()
        val ctx = newContext("mod.a", snapshot)
        assertEquals(
            listOf("HIGH", "LOW", "SAME_PRIORITY"),
            ctx.jobScheduler.templates().map { it.templateKey },
        )
    }
}