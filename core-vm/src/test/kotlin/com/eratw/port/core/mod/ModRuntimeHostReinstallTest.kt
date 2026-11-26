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
import com.eratw.port.core.vars.VariableStore
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val PKG: String = "mod.a"
private const val HOOK_KEY: String = "COM300"
private const val EVENT_KEY: String = "EVENT"
private const val STORAGE_KEY: String = "k"
private const val STORED_VALUE: String = "v1"
private const val DUPLICATE_MESSAGE: String = "packageId 重复装载：$PKG"

private interface ReinstallProbeService

private class ReinstallRuntime : RuntimeScheduler {
    override val isOnMainThread: Boolean get() = true

    override fun submit(action: () -> Unit) = action()
}

private class ReinstallAudioSink : ModAudioSink {
    var stopCount = 0

    override fun play(packageId: String, bytes: ByteArray, loop: Boolean, volume: Float): Boolean = true

    override fun stop() {
        stopCount++
    }
}

private class ReinstallTriggerTarget : ModEventTriggerTarget {
    override fun isOnVmThread(): Boolean = true

    override fun isInEventDispatch(): Boolean = false

    override fun isInVmRun(): Boolean = false

    override fun triggerEvent(eventKey: String): Boolean = true
}

private fun reinstallKernel(): EntityKernel = EntityKernel(
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

private fun reinstallSnapshot(vararg packages: String): DeclarationSnapshot {
    val registry = DeclarationRegistry()
    for (pkg in packages) {
        registry.addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = pkg,
                    hooks = listOf(HookDecl(HOOK_KEY, HookPhase.PREFIX)),
                    interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                ),
            ),
        )
    }
    return registry.freeze()
}

/** 记录生命周期 trace 的探针 mod：可按阶段故意失败；onLoad 可挂写 storage / 注册面脚本。 */
private class ReinstallProbeMod(
    private val trace: MutableList<String>,
    private val label: String,
    private val failOnLoad: Boolean = false,
    private val failOnEnable: Boolean = false,
    private val onLoadScript: (ModContext) -> Unit = {},
) : EraMod {
    override fun onLoad(context: ModContext) {
        trace += "$label:onLoad(${context.packageId})"
        onLoadScript(context)
        if (failOnLoad) error("$label 故意在 onLoad 抛异常")
    }

    override fun onEnable() {
        trace += "$label:onEnable"
        if (failOnEnable) error("$label 故意在 onEnable 抛异常")
    }

    override fun onDisable() {
        trace += "$label:onDisable"
    }

    override fun onUnload() {
        trace += "$label:onUnload"
    }
}

private fun newReinstallHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
    ModRuntimeHost(
        snapshot = reinstallSnapshot(*packages),
        entityKernel = reinstallKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = ReinstallRuntime(),
    )

/** 注册面全项脚本（hook / event / interaction / service / strings），供「旧注册项不复活」断言。 */
private fun registerProbeFaces(context: ModContext, onHook: () -> Unit = {}) {
    context.hooks.register(HOOK_KEY, HookPhase.PREFIX) {
        onHook()
        HookResult.Continue
    }
    context.events.register(EVENT_KEY, EventBucket.NORMAL) { EventResult.CONTINUE }
    context.interactions.registerHook(InteractionPhase.PRE) { InteractionResult.CONTINUE }
    context.services.publish(ReinstallProbeService::class.java, object : ReinstallProbeService {}, context.packageId)
    context.strings.declare("greet", "你好", "hello", "こんにちは")
}

/**
 * 同会话重装回滚态 mod（Lane DZ / task-123）的判据面：
 * 放宽 `install` 的重复 packageId 闸后，只有「本次调用前已登记且已回滚」的 entry 可替换；
 * 正常装载中 / 已 shutdown / 批内重复一律保持既有硬失败语义与消息。
 */
class ModRuntimeHostReinstallTest {

    @Test
    fun `onEnable 失败后同会话重装成功并把失败态翻转`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        val first = host.install(listOf(PKG to ReinstallProbeMod(trace, "fail", failOnEnable = true)))
        assertFalse(first.single().active)
        assertTrue(host.failureOf(PKG) is IllegalStateException)
        val failedContext = host.contextOf(PKG)
        assertFalse(host.storage.isActive(PKG), "失败回滚必须把存储段退回非激活（数据留下）")

        val state = host.install(listOf(PKG to ReinstallProbeMod(trace, "ok"))).single()
        assertEquals(PKG, state.packageId)
        assertTrue(state.active, "回滚态 entry 必须允许同会话重装")
        assertNull(state.failure)
        assertNull(host.failureOf(PKG), "替换后失败读数必须清空")
        assertNotSame(failedContext, host.contextOf(PKG), "替换后必须是新 context")
        assertEquals(
            listOf("fail:onLoad($PKG)", "fail:onEnable", "ok:onLoad($PKG)", "ok:onEnable"),
            trace,
        )
        assertTrue(logs.none { it.contains("packageId 重复装载") }, "回滚态重装不得再走硬失败：$logs")
    }

    @Test
    fun `onLoad 失败后重装成功且旧注册面不复活`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        var oldHits = 0
        var newHits = 0
        host.install(
            listOf(
                PKG to ReinstallProbeMod(trace, "fail", failOnLoad = true) { ctx ->
                    registerProbeFaces(ctx) { oldHits++ }
                },
            ),
        )
        assertEquals(RegisteredFaces.EMPTY, host.registeredFacesOf(PKG), "onLoad 失败回滚必须撤掉全部注册面")
        host.install(
            listOf(PKG to ReinstallProbeMod(trace, "ok") { ctx -> registerProbeFaces(ctx) { newHits++ } }),
        )
        val faces = host.registeredFacesOf(PKG)
        assertEquals(1, faces.hooks)
        assertEquals(mapOf("PRI" to 0, "NORMAL" to 1, "LATER" to 0, "ONLY" to 0), faces.events)
        assertEquals(1, faces.interactions)
        assertEquals(1, faces.services)
        assertEquals(1, faces.strings)
        val ctx = host.contextOf(PKG)!! as ModContextImpl
        ctx.hookPipeline.dispatch(HOOK_KEY, emptyArray()) { ModValue.of(1L) }
        assertEquals(0, oldHits, "旧 mod 的 hook 处理器不得复活")
        assertEquals(1, newHits)
    }

    @Test
    fun `正常装载中的同 id 重复仍硬失败且原条目不受影响`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        var hits = 0
        host.install(listOf(PKG to ReinstallProbeMod(trace, "ok") { ctx -> registerProbeFaces(ctx) { hits++ } }))
        val liveContext = host.contextOf(PKG)
        val ex = assertThrows<IllegalArgumentException> {
            host.install(listOf(PKG to ReinstallProbeMod(trace, "dup")))
        }
        assertEquals(DUPLICATE_MESSAGE, ex.message)
        assertEquals(listOf("ok:onLoad($PKG)", "ok:onEnable"), trace, "第二份不得进生命周期")
        assertSame(liveContext, host.contextOf(PKG), "硬失败不得动已启用的原条目")
        assertEquals(1, host.registeredFacesOf(PKG).hooks)
        (host.contextOf(PKG)!! as ModContextImpl).hookPipeline.dispatch(HOOK_KEY, emptyArray()) {
            ModValue.of(1L)
        }
        assertEquals(1, hits)
    }

    @Test
    fun `已 shutdown 的回滚态 entry 仍硬失败不替换`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        host.install(listOf(PKG to ReinstallProbeMod(trace, "fail", failOnEnable = true)))
        val failedContext = host.contextOf(PKG)
        host.shutdown()
        val ex = assertThrows<IllegalArgumentException> {
            host.install(listOf(PKG to ReinstallProbeMod(trace, "afterShutdown")))
        }
        assertEquals(DUPLICATE_MESSAGE, ex.message)
        val afterShutdown = listOf(
            "fail:onLoad($PKG)", "fail:onEnable", "fail:onDisable", "fail:onUnload",
        )
        assertEquals(afterShutdown, trace, "已 shutdown 不得再进生命周期（shutdown 自身的收口除外）")
        assertSame(failedContext, host.contextOf(PKG))
    }

    @Test
    fun `批内重复同 id 仍硬失败且不把第一份失败态替换掉`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        val ex = assertThrows<IllegalArgumentException> {
            host.install(
                listOf(
                    PKG to ReinstallProbeMod(trace, "first", failOnEnable = true),
                    PKG to ReinstallProbeMod(trace, "second"),
                ),
            )
        }
        assertEquals(DUPLICATE_MESSAGE, ex.message)
        assertEquals(listOf("first:onLoad($PKG)", "first:onEnable"), trace)
        assertTrue(host.failureOf(PKG) is IllegalStateException, "批内重复是编程错误：不得降级成替换")
        assertFalse(host.storage.isActive(PKG))
    }

    @Test
    fun `替换保留 storage 段的既有数据并重新激活同一段`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        host.install(
            listOf(
                PKG to ReinstallProbeMod(trace, "fail", failOnEnable = true) { ctx ->
                    ctx.storage.put(STORAGE_KEY, STORED_VALUE)
                },
            ),
        )
        assertFalse(host.storage.isActive(PKG))
        var observed: String? = null
        host.install(
            listOf(
                PKG to ReinstallProbeMod(trace, "ok") { ctx ->
                    observed = ctx.storage.get(STORAGE_KEY)
                    ctx.storage.put("k2", "v2")
                },
            ),
        )
        assertEquals(STORED_VALUE, observed, "重装必须激活同一份段、读回旧值（不是新建空段）")
        assertTrue(host.storage.isActive(PKG))
        assertEquals("v2", (host.contextOf(PKG)!! as ModContextImpl).storage.get("k2"))
    }

    @Test
    fun `连续两次失败回滚后第三次重装仍可成功`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        host.install(listOf(PKG to ReinstallProbeMod(trace, "f1", failOnEnable = true)))
        host.install(listOf(PKG to ReinstallProbeMod(trace, "f2", failOnEnable = true)))
        val states = host.install(listOf(PKG to ReinstallProbeMod(trace, "ok")))
        assertTrue(states.single().active)
        assertNull(states.single().failure)
        assertEquals(
            listOf(
                "f1:onLoad($PKG)", "f1:onEnable",
                "f2:onLoad($PKG)", "f2:onEnable",
                "ok:onLoad($PKG)", "ok:onEnable",
            ),
            trace,
        )
    }

    @Test
    fun `替换后 shutdown 只收口新条目且存储段退回非激活`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        host.install(listOf(PKG to ReinstallProbeMod(trace, "fail", failOnEnable = true)))
        host.install(listOf(PKG to ReinstallProbeMod(trace, "ok")))
        val report = host.shutdown()
        assertEquals(1, report.size, "旧 entry 已摘除，收口报告只有新条目")
        assertTrue(report.single().active)
        assertEquals(
            listOf(
                "fail:onLoad($PKG)", "fail:onEnable",
                "ok:onLoad($PKG)", "ok:onEnable", "ok:onDisable", "ok:onUnload",
            ),
            trace,
            "旧失败条目不得参与 onDisable / onUnload",
        )
        assertFalse(host.storage.isActive(PKG))
        assertEquals(RegisteredFaces.EMPTY, host.registeredFacesOf(PKG))
    }

    @Test
    fun `重装不重绑会话级 aux 槽且收口全部解绑`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        val store = VariableStore()
        val audio = ReinstallAudioSink()
        val target = ReinstallTriggerTarget()
        val root: File = Files.createTempDirectory("eratw-reinstall-aux").toFile()
        try {
            FunctionHookWiring.install(store, host.hookPipeline) { logs += it }
            ModEventWiring.install(store, host.eventHandlers, { logs += it })
            ModEventTriggerWiring.install(store, target)
            ModAudioWiring.install(store, audio, ModResourceStore(root), { logs += it })
            val hookSlot = store.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT)
            assertTrue(hookSlot!!.pipeline === host.hookPipeline)
            host.install(
                listOf(
                    PKG to ReinstallProbeMod(trace, "fail", failOnEnable = true) { ctx ->
                        registerProbeFaces(ctx)
                    },
                ),
            )
            host.install(listOf(PKG to ReinstallProbeMod(trace, "ok") { ctx -> registerProbeFaces(ctx) }))
            assertSame(
                hookSlot,
                store.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT),
                "重装不得重绑会话级 hook 槽",
            )
            assertTrue(ModEventWiring.hasHandlers(store, EVENT_KEY), "重装后新条目的事件处理器必须在会话级槽里")
            assertSame(audio, ModAudioWiring.sinkOf(store), "重装不得重绑会话级播放汇")
            assertSame(
                target,
                store.aux.peek<ModEventTriggerWiring.Handle>(ModEventTriggerWiring.AUX_SLOT)?.target,
                "重装不得重绑会话级触发目标",
            )
            host.shutdown()
            FunctionHookWiring.uninstall(store, host.hookPipeline)
            ModEventWiring.uninstall(store, host.eventHandlers)
            ModAudioWiring.uninstall(store)
            ModEventTriggerWiring.uninstall(store, target)
            assertNull(store.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT)?.pipeline)
            assertFalse(ModEventWiring.hasHandlers(store, EVENT_KEY))
            assertNull(store.aux.peek<ModEventTriggerWiring.Handle>(ModEventTriggerWiring.AUX_SLOT)?.target)
            assertNull(ModAudioWiring.sinkOf(store))
            assertTrue(audio.stopCount >= 1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `构造段失败不登记 entry 重装走正常装载路径不误判替换`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newReinstallHost(logs, PKG)
        val missing = "mod.missing"
        val first = host.install(listOf(missing to ReinstallProbeMod(trace, "ghost")))
        assertFalse(first.single().active)
        assertNull(host.contextOf(missing))
        val second = host.install(listOf(missing to ReinstallProbeMod(trace, "ghost2")))
        assertFalse(second.single().active)
        assertNull(host.contextOf(missing))
        assertEquals(emptyList<String>(), trace, "构造段失败不进生命周期（重装也是构造段失败）")
        assertNull(host.failureOf(missing), "构造失败不登记 entry，失败读数只在报告里")
    }
}
