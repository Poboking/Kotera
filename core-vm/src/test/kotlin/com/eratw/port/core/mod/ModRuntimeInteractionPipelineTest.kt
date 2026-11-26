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
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * RT2 P0-LAST 第一条死路的 **core 侧根因**：交互阶段管线必须全链共享一份。
 *
 * 缺陷层判定（五层之一）= 装载/编译相位的装配缝：`ModContextImpl` 原先各自
 * `InteractionPipeline.of(...)`，`context.interactions.registerHook` 的绑定落进 per-context
 * 管线，宿主侧没有一处能拿到「含全部 mod 钩子」的引擎 —— 交互管线因此没有生产触发点。
 * 修法 = 宿主 `install` 时 adopt 全链共享那一份（与 adoptHookPipeline/adoptEventRegistry
 * 同源），本文件钉住这个缝的行为面：
 *
 * 1. 两个 mod 的钩子落进同一份管线（`===` 同一实例），从任一 context 的公开 API 触发都能
 *    命中双方，执行序按声明链接序（packageRank）；
 * 2. 撤销单个 mod 只摘它自己的钩子；
 * 3. 单 handler 抛异常按包隔离进 `failures`、其余 handler 照跑、主流程返回值不丢（与
 *    `InteractionPipeline` 既有失败隔离口径一致）。
 */
class ModRuntimeInteractionPipelineTest {

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
                        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private class HookMod(
        private val hits: MutableList<String>,
        private val label: String,
        private val throwsInHandler: Boolean = false,
        private val onRegistration: (Registration) -> Unit = {},
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            onRegistration(
                context.interactions.registerHook(InteractionPhase.PRE) {
                    hits += label
                    if (throwsInHandler) error("$label 故意在交互 handler 抛异常")
                    InteractionResult.CONTINUE
                },
            )
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
        ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )

    @Test
    fun `两个 mod 的交互钩子落进同一份全链共享管线且按声明链接序命中`() {
        val hits = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modA", "modB")
        host.install(listOf("modA" to HookMod(hits, "modA"), "modB" to HookMod(hits, "modB")))
        val ctxA = host.contextOf("modA") as ModContextImpl
        val ctxB = host.contextOf("modB") as ModContextImpl
        assertSame(host.interactionPipeline, ctxA.interactionPipeline)
        assertSame(ctxA.interactionPipeline, ctxB.interactionPipeline)
        assertEquals(2, host.installedContexts.size)
        // 从 B 的公开 API 触发：共享管线应把 A 的钩子也一起评估（跨 mod 全局序）。
        val result = ctxB.interactions.run(
            InteractionContext("host.input.test", EntityId(1L), emptyList()),
        ) { null }
        assertEquals(listOf("modA", "modB"), hits)
        assertTrue(result.failures.isEmpty())
        assertFalse(result.interrupted)
        assertFalse(result.replaced)
        host.shutdown()
    }

    @Test
    fun `单个 mod 撤销后共享管线只剩另一方钩子`() {
        val hits = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modA", "modB")
        var registrationA: Registration? = null
        host.install(
            listOf(
                "modA" to HookMod(hits, "modA", onRegistration = { registrationA = it }),
                "modB" to HookMod(hits, "modB"),
            ),
        )
        assertNotNull(registrationA)
        host.interactionPipeline.run(InteractionContext("t1", EntityId.NULL, emptyList())) { null }
        assertEquals(listOf("modA", "modB"), hits)
        hits.clear()
        registrationA!!.unregister()
        host.interactionPipeline.run(InteractionContext("t2", EntityId.NULL, emptyList())) { null }
        assertEquals(listOf("modB"), hits)
        host.shutdown()
    }

    @Test
    fun `handler 抛异常按包隔离不中断其它 mod 且主流程返回值不丢`() {
        val hits = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modA", "modB")
        host.install(
            listOf(
                "modA" to HookMod(hits, "modA", throwsInHandler = true),
                "modB" to HookMod(hits, "modB"),
            ),
        )
        val result = host.interactionPipeline.run(
            InteractionContext("t3", EntityId.NULL, emptyList()),
        ) { ModValue.of(7L) }
        assertEquals(listOf("modA", "modB"), hits)
        assertEquals(listOf("modA"), result.failures)
        assertNotNull(result.returnValue)
        assertFalse(result.interrupted)
        host.shutdown()
    }
}
