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
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lane AS：`EraMod.requires` 的装载序与 fail-closed 拒载面（`ModRuntimeHost.install` 接线）。
 *
 * 两类分开成两个类：装载序（回归钉子：无依赖声明时逐位一致）与拒载裁决（缺依赖 / 成环 / 自环 /
 * 依赖被版本门或生命周期判死）。断言面 = `install` 返回的 [ModRuntimeState] 顺序与失败类型、
 * 生命周期 trace、以及宿主可观察状态（`contextOf` / `storage.isActive`）。
 */
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

    override fun submit(action: () -> Unit) = action()
}

/** 记录生命周期 trace 的依赖声明 mod；[requires] / [apiVersion] 由各用例覆写。 */
private class DepMod(
    private val trace: MutableList<String>,
    private val label: String,
    override val requires: List<String> = emptyList(),
    override val apiVersion: Int = ModApi.VERSION,
    private val failOnEnable: Boolean = false,
) : EraMod {
    override fun onLoad(context: ModContext) {
        trace += "$label:onLoad(${context.packageId})"
    }

    override fun onEnable() {
        trace += "$label:onEnable"
        if (failOnEnable) error("$label 故意在 onEnable 抛错")
    }

    override fun onDisable() {
        trace += "$label:onDisable"
    }

    override fun onUnload() {
        trace += "$label:onUnload"
    }
}

private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
    ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    )

/** 装载序：无依赖声明逐位一致（回归钉子）+ 单依赖/链式/菱形/平坦节点的稳定拓扑序。 */
class ModDependencyOrderTest {

    @Test
    fun `无依赖声明时装载顺序与载体声明序逐位一致`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modA", "modB", "modC")
        val states = host.install(
            listOf(
                "modA" to DepMod(trace, "modA"),
                "modB" to DepMod(trace, "modB"),
                "modC" to DepMod(trace, "modC"),
            ),
        )
        assertEquals(listOf("modA", "modB", "modC"), states.map { it.packageId })
        assertEquals(
            listOf(
                "modA:onLoad(modA)", "modA:onEnable",
                "modB:onLoad(modB)", "modB:onEnable",
                "modC:onLoad(modC)", "modC:onEnable",
            ),
            trace,
        )
        assertTrue(states.all { it.active })
    }

    @Test
    fun `单依赖时依赖先装载且报告按装载序`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "app", "base")
        val states = host.install(
            listOf(
                "app" to DepMod(trace, "app", requires = listOf("base")),
                "base" to DepMod(trace, "base"),
            ),
        )
        assertEquals(listOf("base", "app"), states.map { it.packageId })
        assertEquals(
            listOf("base:onLoad(base)", "base:onEnable", "app:onLoad(app)", "app:onEnable"),
            trace,
        )
    }

    @Test
    fun `链式依赖按声明反序也先装满链路再装依赖者`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "c", "b", "a")
        val states = host.install(
            listOf(
                "c" to DepMod(trace, "c", requires = listOf("b")),
                "b" to DepMod(trace, "b", requires = listOf("a")),
                "a" to DepMod(trace, "a"),
            ),
        )
        assertEquals(listOf("a", "b", "c"), states.map { it.packageId })
        assertEquals(
            listOf("a", "b", "c"),
            trace.filter { it.endsWith(":onEnable") }.map { it.removeSuffix(":onEnable") },
        )
    }

    @Test
    fun `菱形依赖时同层节点保持载体声明序`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "d", "b", "c", "a")
        val states = host.install(
            listOf(
                "d" to DepMod(trace, "d", requires = listOf("b", "c")),
                "b" to DepMod(trace, "b", requires = listOf("a")),
                "c" to DepMod(trace, "c", requires = listOf("a")),
                "a" to DepMod(trace, "a"),
            ),
        )
        assertEquals(listOf("a", "b", "c", "d"), states.map { it.packageId })
        assertEquals(
            listOf(
                "a:onLoad(a)", "a:onEnable",
                "b:onLoad(b)", "b:onEnable",
                "c:onLoad(c)", "c:onEnable",
                "d:onLoad(d)", "d:onEnable",
            ),
            trace,
        )
    }

    @Test
    fun `平坦节点在拓扑约束内保持声明序`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "x", "app", "y", "base", "z")
        val states = host.install(
            listOf(
                "x" to DepMod(trace, "x"),
                "app" to DepMod(trace, "app", requires = listOf("base")),
                "y" to DepMod(trace, "y"),
                "base" to DepMod(trace, "base"),
                "z" to DepMod(trace, "z"),
            ),
        )
        assertEquals(listOf("x", "y", "base", "app", "z"), states.map { it.packageId })
        assertTrue(states.all { it.active })
    }
}

/** 拒载裁决：缺依赖 / 成环 / 自环 / 依赖被版本门拒 / 依赖生命周期失败，全部 fail-closed。 */
class ModDependencyRejectTest {

    @Test
    fun `缺依赖拒载且其余 mod 照常装载`() {
        val trace = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val host = newHost(logs, "base", "app", "x")
        val states = host.install(
            listOf(
                "base" to DepMod(trace, "base"),
                "app" to DepMod(trace, "app", requires = listOf("ghost")),
                "x" to DepMod(trace, "x"),
            ),
        )
        assertEquals(listOf("base", "x", "app"), states.map { it.packageId })
        val failure = assertIs<ModDependencyMissingException>(states.single { it.packageId == "app" }.failure)
        assertTrue(failure.message.orEmpty().contains("ghost"))
        assertNull(host.contextOf("app"))
        assertFalse(host.storage.isActive("app"))
        assertNotNull(host.contextOf("base"))
        assertNotNull(host.contextOf("x"))
        assertFalse(trace.any { it.startsWith("app:") })
    }

    @Test
    fun `成环时环上成员全部拒载且环外照常`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "a", "b", "c")
        val states = host.install(
            listOf(
                "a" to DepMod(trace, "a", requires = listOf("b")),
                "b" to DepMod(trace, "b", requires = listOf("a")),
                "c" to DepMod(trace, "c"),
            ),
        )
        assertEquals(listOf("c", "a", "b"), states.map { it.packageId })
        val failure = assertIs<ModDependencyCycleException>(states.single { it.packageId == "a" }.failure)
        assertTrue(failure.message.orEmpty().contains("a -> b -> a"))
        assertFalse(states.single { it.packageId == "b" }.active)
        assertTrue(states.single { it.packageId == "c" }.active)
        assertNull(host.contextOf("a"))
        assertNull(host.contextOf("b"))
        assertFalse(trace.any { it.startsWith("a:") || it.startsWith("b:") })
    }

    @Test
    fun `requires 指向自身按环拒载并点名自身`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "self", "other")
        val states = host.install(
            listOf(
                "self" to DepMod(trace, "self", requires = listOf("self")),
                "other" to DepMod(trace, "other"),
            ),
        )
        assertEquals(listOf("other", "self"), states.map { it.packageId })
        val failure = assertIs<ModDependencyCycleException>(states.single { it.packageId == "self" }.failure)
        assertTrue(failure.message.orEmpty().contains("self -> self"))
        assertTrue(states.single { it.packageId == "other" }.active)
        assertNull(host.contextOf("self"))
    }

    @Test
    fun `依赖被版本门拒载时依赖者一并拒载`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "app", "base")
        val states = host.install(
            listOf(
                "app" to DepMod(trace, "app", requires = listOf("base")),
                "base" to DepMod(trace, "base", apiVersion = ModApi.VERSION + 1),
            ),
        )
        assertEquals(listOf("base", "app"), states.map { it.packageId })
        assertIs<ModApiVersionMismatchException>(states.single { it.packageId == "base" }.failure)
        val failure = assertIs<ModDependencyUnavailableException>(states.single { it.packageId == "app" }.failure)
        assertTrue(failure.message.orEmpty().contains("base"))
        assertNull(host.contextOf("app"))
        assertFalse(host.storage.isActive("app"))
        assertFalse(trace.any { it.startsWith("app:") })
    }

    @Test
    fun `依赖 onEnable 失败时依赖者一并拒载且根因挂到 cause`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "app", "base")
        val states = host.install(
            listOf(
                "app" to DepMod(trace, "app", requires = listOf("base")),
                "base" to DepMod(trace, "base", failOnEnable = true),
            ),
        )
        assertEquals(listOf("base", "app"), states.map { it.packageId })
        assertFalse(states.single { it.packageId == "base" }.active)
        val failure = assertIs<ModDependencyUnavailableException>(states.single { it.packageId == "app" }.failure)
        assertNotNull(failure.cause)
        assertTrue(failure.message.orEmpty().contains("base"))
        assertNull(host.contextOf("app"))
        assertFalse(host.storage.isActive("app"))
        assertNotNull(host.failureOf("base"))
    }

    @Test
    fun `已停用依赖（上一批 onEnable 失败）也算缺并拒载依赖者`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "base", "app")
        val first = host.install(listOf("base" to DepMod(trace, "base", failOnEnable = true)))
        assertFalse(first.single().active)
        val second = host.install(
            listOf("app" to DepMod(trace, "app", requires = listOf("base"))),
        )
        assertEquals(listOf("app"), second.map { it.packageId })
        val failure = assertIs<ModDependencyMissingException>(second.single().failure)
        assertTrue(failure.message.orEmpty().contains("base"))
        assertNull(host.contextOf("app"))
        assertFalse(host.storage.isActive("app"))
        assertFalse(trace.any { it.startsWith("app:") })
    }
}
