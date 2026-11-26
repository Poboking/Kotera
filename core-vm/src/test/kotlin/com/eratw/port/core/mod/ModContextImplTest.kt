package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ModContextImpl 装配闸（RT2 波 C 车 C1）：14 属性逐项装配、四引擎 factory 复用、实例隔离、
 * 注册项按 packageId 归属、EventApi 显式拒接、构造期 fail-closed。
 */
class ModContextImplTest {

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

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(
                        packageId = pkg,
                        hooks = listOf(HookDecl("COM300", HookPhase.PREFIX)),
                        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                        jobs = listOf(JobDecl("j1", phase = "DAY_END", firstDay = 0, everyDays = 1)),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        val actions = mutableListOf<() -> Unit>()

        override fun submit(action: () -> Unit) {
            actions += action
            action()
        }
    }

    private fun newContext(
        packageId: String,
        snapshot: DeclarationSnapshot = newSnapshot(packageId),
        logs: MutableList<String> = mutableListOf(),
        runtime: RuntimeScheduler = FakeRuntime(),
        nativeMethods: NativeMethodRegistry = NativeMethodRegistry(),
        intVarView: (String) -> Long = { 0L },
        strVarView: (String) -> String = { "" },
        hasIntVarView: ((String) -> Boolean)? = null,
        hasStrVarView: ((String) -> Boolean)? = null,
        registrationSink: (Registration) -> Unit = {},
    ): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = snapshot,
        entityKernel = newKernel(),
        pkgShortId = 1,
        nativeMethods = nativeMethods,
        logSink = { logs += it },
        runtime = runtime,
        intVarView = intVarView,
        strVarView = strVarView,
        hasIntVarView = hasIntVarView,
        hasStrVarView = hasStrVarView,
        registrationSink = registrationSink,
    )

    private fun readSource(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core/mod/$name"),
            File("core-vm/src/main/kotlin/com/eratw/port/core/mod/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("定位不到源码 $name（防假绿）：cwd=${System.getProperty("user.dir")}")
        return file.readText()
    }

    private fun codeOnly(text: String): String =
        text.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")

    @Test
    fun `14 个属性逐项非空且是预期适配器类型`() {
        val ctx = newContext("modA")
        assertEquals("modA", ctx.packageId)
        assertNotNull(ctx.logger)
        assertNotNull(ctx.runtime)
        assertTrue(ctx.entities is KernelEntityApi)
        assertTrue(ctx.relationships is KernelEntityApi)
        assertSame(ctx.entities, ctx.relationships)
        assertNotNull(ctx.events)
        assertNotNull(ctx.hooks)
        assertNotNull(ctx.phases)
        assertNotNull(ctx.jobs)
        assertNotNull(ctx.interactions)
        assertTrue(ctx.functions is NativeFunctionApi)
        assertNotNull(ctx.storage)
        assertNotNull(ctx.gameState)
        assertNotNull(ctx.ui)
    }

    @Test
    fun `四引擎用声明快照构造后 register 都能拿到 Registration`() {
        val ctx = newContext("modA")
        val rh = ctx.hooks.register("COM300", HookPhase.PREFIX) { HookResult.Continue }
        val rp = ctx.phases.subscribe("DAY_END") { }
        val rj = ctx.jobs.bind("j1") { }
        val ri = ctx.interactions.registerHook(InteractionPhase.PRE) { InteractionResult.CONTINUE }
        val ru = ctx.ui.registerInteraction("BTN") { }
        assertNotNull(rh)
        assertNotNull(rp)
        assertNotNull(rj)
        assertNotNull(ri)
        assertNotNull(ru)
        // 重复的同一槽绑定仍被引擎拒绝：装配层没有放宽既有判定。
        assertThrows<IllegalArgumentException> {
            ctx.hooks.register("COM300", HookPhase.PREFIX) { HookResult.Continue }
        }
    }

    @Test
    fun `entities relationships functions 走 fake 内核可调用`() {
        val ctx = newContext("modA")
        val id = ctx.entities.create("Child")
        assertFalse(id.isNull)
        assertTrue(ctx.entities.exists(id))
        assertEquals(0L, ctx.entities.getInt(id, "hp"))
        assertTrue(ctx.relationships.hasKind("PARENT"))
        ctx.functions.register("ADDHP") { }
        assertTrue(ctx.functions.has("ADDHP"))
    }

    @Test
    fun `注册项按 packageId 归属只命中本包声明槽`() {
        val snapshot = newSnapshot("modA", "modB")
        val ctx = newContext("modA", snapshot)
        var aHits = 0
        ctx.hooks.register("COM300", HookPhase.PREFIX) {
            aHits++
            HookResult.Continue
        }
        ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(1, aHits)
    }

    @Test
    fun `单独 unregister 立即不再命中`() {
        val ctx = newContext("modA")
        var hits = 0
        val reg = ctx.hooks.register("COM300", HookPhase.PREFIX) {
            hits++
            HookResult.Continue
        }
        ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(1, hits)
        reg.unregister()
        ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(1, hits)
    }

    @Test
    fun `logger 按 packageId 归属且两个实例互不串扰`() {
        val logsA = mutableListOf<String>()
        val logsB = mutableListOf<String>()
        val a = newContext("modA", logs = logsA)
        val b = newContext("modB", logs = logsB)
        a.logger.info("hello")
        b.logger.info("world")
        assertEquals(listOf("[modA][INFO] hello"), logsA)
        assertEquals(listOf("[modB][INFO] world"), logsB)
        a.logger.error("boom", IllegalStateException("x"))
        assertTrue(logsA[1].contains("[modA][ERROR]") && logsA[1].contains("boom"))
        assertFalse(logsB.any { it.contains("boom") })
    }

    @Test
    fun `runtime 的 isOnMainThread 与 submit 由注入调度器决定`() {
        val fake = FakeRuntime()
        val ctx = newContext("modA", runtime = fake)
        assertTrue(ctx.runtime.isOnMainThread)
        var ran = 0
        ctx.runtime.submit { ran++ }
        assertEquals(1, ran)
        assertEquals(1, fake.actions.size)
    }

    @Test
    fun `storage 实例级隔离且 keys 返回快照`() {
        val a = newContext("modA").storage
        val b = newContext("modB").storage
        a.put("k", "v")
        assertEquals("v", a.get("k"))
        assertNull(b.get("k"))
        assertEquals(setOf("k"), a.keys())
        val snap = a.keys()
        a.put("k2", "v2")
        assertEquals(setOf("k"), snap)
        assertTrue(a.remove("k"))
        assertFalse(a.remove("k"))
    }

    @Test
    fun `gameState 未注入缺省读数且注入只读视图生效`() {
        val def = newContext("modA")
        assertEquals(0L, def.gameState.getIntVar("ANY"))
        assertEquals("", def.gameState.getStrVar("ANY"))
        val wired = newContext(
            "modA",
            intVarView = { name -> if (name == "DAY") 7L else 0L },
            strVarView = { name -> if (name == "PLACE") "神社" else "" },
        )
        assertEquals(7L, wired.gameState.getIntVar("DAY"))
        assertEquals("神社", wired.gameState.getStrVar("PLACE"))
        assertEquals(0L, wired.gameState.getIntVar("MISSING"))
        assertEquals("", wired.gameState.getStrVar("MISSING"))
    }

    @Test
    fun `gameState 存在性探测 供给存在时声明答真缺失答假`() {
        val wired = newContext(
            "modA",
            hasIntVarView = { it == "DAY" },
            hasStrVarView = { it == "PLACE" },
        )
        assertTrue(wired.gameState.hasIntVar("DAY"))
        assertFalse(wired.gameState.hasIntVar("MISSING"))
        assertTrue(wired.gameState.hasStrVar("PLACE"))
        assertFalse(wired.gameState.hasStrVar("MISSING"))
    }

    @Test
    fun `gameState 存在探测未供给时缺省恒假且哨兵语义零变化`() {
        val def = newContext("modA")
        assertFalse(def.gameState.hasIntVar("ANY"))
        assertFalse(def.gameState.hasStrVar("ANY"))
        // K1 冻结契约：探测位缺省（未接线形态）不改变读侧哨兵语义——缺变量仍 0 / 空串，不抛。
        assertEquals(0L, def.gameState.getIntVar("ANY"))
        assertEquals("", def.gameState.getStrVar("ANY"))
        // 供给已接线时同样如此：探测 true / false 之外的缺变量读数走哨兵原路。
        val wired = newContext("modA", hasIntVarView = { it == "DAY" })
        assertTrue(wired.gameState.hasIntVar("DAY"))
        assertFalse(wired.gameState.hasIntVar("MISSING"))
        assertEquals(0L, wired.gameState.getIntVar("MISSING"))
    }

    @Test
    fun `ui 撤销后立即不再命中`() {
        val ctx = newContext("modA")
        var clicks = 0
        val reg = ctx.ui.registerInteraction("BTN") { clicks++ }
        assertEquals(setOf("BTN"), ctx.uiRegisteredKeys())
        assertTrue(ctx.uiClick("BTN"))
        assertEquals(1, clicks)
        reg.unregister()
        assertEquals(emptySet<String>(), ctx.uiRegisteredKeys())
        assertFalse(ctx.uiClick("BTN"))
        assertEquals(1, clicks)
    }

    @Test
    fun `EventApi 反复拒接且不构造第二份事件系统`() {
        val ctx = newContext("modA")
        repeat(3) {
            val ex = assertThrows<UnsupportedOperationException> {
                ctx.events.register("EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
            }
            assertTrue(ex.message!!.contains("M2-6"))
            assertTrue(ex.message!!.contains("生产消费点未接"))
        }
        val body = codeOnly(
            readSource("ModContextImpl.kt")
                .substringAfter("private class FailClosedEventApi")
                .substringBefore("private class InjectedGameState"),
        )
        assertFalse(body.contains("MutableMap"))
        assertFalse(body.contains("MutableList"))
        assertFalse(body.contains("HashMap"))
    }

    @Test
    fun `构造期缺依赖 fail-closed 不返回半装配 context`() {
        val snapshot = newSnapshot("modA")
        assertThrows<IllegalArgumentException> { newContext("ghost", snapshot) }
        assertThrows<IllegalArgumentException> { newContext("   ", snapshot) }
    }

    /**
     * 反向证明：宿主 adopt 之前 [ModContextImpl.services] 必须是显式 fail-closed 桩。
     *
     * 若它返回空注册表（或静默 no-op），作者会以为服务发布生效了，装配缺失被静默吞掉；
     * 这里四个入口都要求抛错并点名 packageId。
     */
    @Test
    fun `未 adopt 服务注册表时 services 是显式 fail-closed 桩`() {
        val ctx = newContext("modA")
        val operations: List<() -> Any?> = listOf(
            { ctx.services.find(CharSequence::class.java) },
            { ctx.services.publish(CharSequence::class.java, "impl", ctx.packageId) },
            { ctx.services.owners(CharSequence::class.java) },
            { ctx.services.publishedTypes() },
        )
        for (operation in operations) {
            val failure = assertThrows<UnsupportedOperationException> { operation() }
            assertTrue(
                failure.message.orEmpty().contains("modA"),
                "fail-closed 文案必须点名 packageId：${failure.message}",
            )
        }
    }
}
