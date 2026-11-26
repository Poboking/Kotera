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
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.modDeclarations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 注册面只读读数闸（`registeredFacesOf`）：读数 = 实际注册条数、ONLY 桶被拒不涨、
 * 处理器失败退槽后 hooks/retiredHooks 反向变动、停用卸载后归零、未装配降级全零不抛。
 *
 * 反向证明（交付里贴红行）：把 [HookPipeline.bindingCountsOf] 的 bound 恒 0 ⇒
 * 判据 1 与判据 3 立红。
 */
class ModRegisteredFacesTest {

    private interface ProbeService

    private class ScriptedMod(private val script: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = script(context)

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }

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

    /** 声明三条 hook 槽（COM400 = 处理器失败即退槽）与一个交互阶段。 */
    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(
                        packageId = pkg,
                        hooks = listOf(
                            HookDecl("COM300", HookPhase.PREFIX),
                            HookDecl("COM500", HookPhase.PREFIX),
                            HookDecl("COM400", HookPhase.PREFIX, continueOnHandlerFailure = false),
                        ),
                        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    /** 两条设置声明（与 app 侧 `ModSettingsStore.snapshotsFor` 同口径：声明清单取自 manifest）。 */
    private fun settingsSnapshotOf(packageId: String): ModSettingsSnapshot = ModSettingsSnapshot(
        packageId,
        ModDeclarationAdapter.toDescriptor(
            modDeclarations(packageId, version = "1.0") {
                intSetting(key = "cfg.volume", default = 5L, range = 0L..10L, title = "音量")
                strSetting(key = "cfg.label", default = "默认", title = "标签")
            },
        ).settings,
        emptyMap(),
    )

    private fun newHost(vararg packages: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    @Test
    fun `装配后注册面读数等于实际注册条数`() {
        val host = newHost("mod.a")
        host.settings = mapOf("mod.a" to settingsSnapshotOf("mod.a"))
        val report = host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    context.hooks.register("COM300", HookPhase.PREFIX) { HookResult.Continue }
                    context.hooks.register("COM500", HookPhase.PREFIX) { HookResult.Continue }
                    context.events.register("EVENT", EventBucket.PRI) { EventResult.CONTINUE }
                    context.events.register("EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
                    context.events.register("EVENT2", EventBucket.NORMAL) { EventResult.CONTINUE }
                    context.events.register("EVENT", EventBucket.LATER) { EventResult.CONTINUE }
                    context.interactions.registerHook(InteractionPhase.PRE) {
                        InteractionResult.CONTINUE
                    }
                    context.services.publish(
                        ProbeService::class.java,
                        object : ProbeService {},
                        context.packageId,
                    )
                    context.strings.declare("greet", "你好", "hello", "こんにちは")
                    context.strings.declare("bye", "再见", "bye", "さようなら")
                    context.ui.registerImage("res/hint.png", "提示", null)
                    context.ui.registerImage("res/icon.png", "图标", null)
                },
            ),
        )
        assertTrue(report.single().active, "夹具前置：装配必须成功：$report")

        val faces = host.registeredFacesOf("mod.a")
        assertEquals(2, faces.hooks, "两条 hook 绑定")
        assertEquals(0, faces.retiredHooks)
        assertEquals(
            mapOf("PRI" to 1, "NORMAL" to 2, "LATER" to 1, "ONLY" to 0),
            faces.events,
            "四桶事件读数按派发序、ONLY 恒 0",
        )
        assertEquals(1, faces.interactions)
        assertEquals(1, faces.services)
        assertEquals(2, faces.strings)
        assertEquals(2, faces.settings, "设置读数 = 注入快照的声明条数")
        assertEquals(2, faces.images)
    }

    @Test
    fun `事件ONLY桶被拒后读数不涨`() {
        val host = newHost("mod.a")
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    context.events.register("EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
                },
            ),
        )
        val before = host.registeredFacesOf("mod.a").events
        assertEquals(1, before["NORMAL"])

        val context = assertNotNull(host.contextOf("mod.a"))
        assertFailsWith<IllegalArgumentException> {
            context.events.register("EVENT", EventBucket.ONLY) { EventResult.CONTINUE }
        }

        val after = host.registeredFacesOf("mod.a").events
        assertEquals(before, after, "被拒的 ONLY 注册不得改变任何一桶的读数")
        assertEquals(0, after["ONLY"], "ONLY 桶读数恒 0")
    }

    @Test
    fun `处理器失败退槽后retiredHooks涨hooks减`() {
        val host = newHost("mod.a")
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    context.hooks.register("COM400", HookPhase.PREFIX) {
                        throw IllegalStateException("故意在处理器里抛")
                    }
                },
            ),
        )
        assertEquals(1, host.registeredFacesOf("mod.a").hooks, "退槽前：绑定 1 条")

        host.hookPipeline.dispatch("COM400", emptyArray()) { ModValue.of(1L) }

        val faces = host.registeredFacesOf("mod.a")
        assertEquals(0, faces.hooks, "退槽后绑定数减")
        assertEquals(1, faces.retiredHooks, "退槽数涨")
    }

    @Test
    fun `停用卸载后注册面读数归零`() {
        val host = newHost("mod.a")
        host.install(
            listOf(
                "mod.a" to ScriptedMod { context ->
                    context.hooks.register("COM300", HookPhase.PREFIX) { HookResult.Continue }
                    context.events.register("EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
                    context.interactions.registerHook(InteractionPhase.PRE) {
                        InteractionResult.CONTINUE
                    }
                    context.services.publish(
                        ProbeService::class.java,
                        object : ProbeService {},
                        context.packageId,
                    )
                    context.strings.declare("greet", "你好", "hello", "こんにちは")
                    context.ui.registerImage("res/hint.png", "提示", null)
                },
            ),
        )
        assertEquals(1, host.registeredFacesOf("mod.a").hooks, "夹具前置：装配后有读数")

        host.shutdown()

        assertEquals(
            RegisteredFaces.EMPTY,
            host.registeredFacesOf("mod.a"),
            "卸载撤销后各注册面都必须归零",
        )
    }

    @Test
    fun `未装配与未知包降级全零不抛`() {
        val host = newHost("mod.a")

        assertEquals(RegisteredFaces.EMPTY, host.registeredFacesOf("mod.a"))
        assertEquals(RegisteredFaces.EMPTY, host.registeredFacesOf("mod.never-installed"))
        assertEquals(RegisteredFaces.EMPTY, host.registeredFacesOf("  "))
        assertEquals(4, host.registeredFacesOf("mod.never-installed").events.size, "四桶键恒在")
    }
}
