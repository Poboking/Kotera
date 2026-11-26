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
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 跨 mod 服务面接线闸（RT2 P1）：全链共享一份表、adopt 早于 onLoad、卸载 / 安装失败回滚 /
 * 读档拒载三条路径撤销发布、多包冲突上抛。
 */
class ModServiceWiringTest {

    /** 发布方魔改实现类、消费方只认这个接口（生产作者面形状）。 */
    private interface Greeter {
        fun greet(name: String): String
    }

    private class GreeterImpl(private val prefix: String) : Greeter {
        override fun greet(name: String): String = "$prefix$name"
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

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private fun newHost(vararg packages: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packages),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    /** 生命周期最小实现的测试 mod：onLoad 里跑观察/发布钩子，可选在 onEnable 抛错触发回滚。 */
    private class ServiceMod(
        private val failOnEnable: Boolean = false,
        private val onLoadHook: (ModContext) -> Unit = {},
    ) : EraMod {
        override fun onLoad(context: ModContext) = onLoadHook(context)

        override fun onEnable() {
            if (failOnEnable) error("故意在 onEnable 抛异常")
        }

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    @Test
    fun `发布方 onLoad 发布后消费方 onLoad 查到同一实例（全链共享表且 adopt 早于 onLoad）`() {
        val host = newHost("modA", "modB")
        val impl = GreeterImpl("hi ")
        var found: Greeter? = null
        val states = host.install(
            listOf(
                "modA" to ServiceMod { context ->
                    context.services.publish(Greeter::class.java, impl, context.packageId)
                },
                "modB" to ServiceMod { context ->
                    found = context.services.find(Greeter::class.java)
                },
            ),
        )

        assertTrue(states.all { it.active }, "两个 mod 都必须装配成功（未 adopt 的话 publish 会抛）")
        assertSame(impl, found, "后装 mod 必须查到先装 mod 发布的同一个对象")
        assertSame(impl, host.services.find(Greeter::class.java))
        assertEquals(listOf("modA"), host.services.owners(Greeter::class.java))
        assertEquals(setOf(Greeter::class.java.name), host.services.publishedTypes())
        assertEquals("hi 玩家", host.services.find(Greeter::class.java)?.greet("玩家"))
    }

    @Test
    fun `安装失败回滚时撤销该包已发布服务（反向证明：回滚后查不到）`() {
        val host = newHost("modA")
        val impl = GreeterImpl("x ")
        val states = host.install(
            listOf(
                "modA" to ServiceMod(failOnEnable = true) { context ->
                    context.services.publish(Greeter::class.java, impl, context.packageId)
                },
            ),
        )

        assertFalse(states[0].active)
        assertNotNull(states[0].failure)
        assertNull(host.services.find(Greeter::class.java), "失败回滚必须把该包发布的服务摘干净")
        assertTrue(host.services.owners(Greeter::class.java).isEmpty())
        assertTrue(host.services.publishedTypes().isEmpty())
    }

    @Test
    fun `shutdown 卸载后发布的服务撤销（反向证明：卸载前可查、卸载后为 null）`() {
        val host = newHost("modA", "modB")
        val impl = GreeterImpl("bye ")
        var foundAtLoad: Greeter? = null
        host.install(
            listOf(
                "modA" to ServiceMod { context ->
                    context.services.publish(Greeter::class.java, impl, context.packageId)
                },
                "modB" to ServiceMod { context ->
                    foundAtLoad = context.services.find(Greeter::class.java)
                },
            ),
        )
        assertSame(impl, foundAtLoad)
        assertNotNull(host.services.find(Greeter::class.java))

        host.shutdown()
        assertNull(host.services.find(Greeter::class.java), "卸载后必须查不到已撤销的发布")
        assertTrue(host.services.publishedTypes().isEmpty())
    }

    @Test
    fun `多包发布同类型时查找上抛并点名双方（契约边界 3）`() {
        val host = newHost("modA", "modB")
        val states = host.install(
            listOf(
                "modA" to ServiceMod { context ->
                    context.services.publish(Greeter::class.java, GreeterImpl("a "), context.packageId)
                },
                "modB" to ServiceMod { context ->
                    context.services.publish(Greeter::class.java, GreeterImpl("b "), context.packageId)
                },
            ),
        )

        assertTrue(states.all { it.active }, "发布本身不是错误：冲突在查找时暴露")
        assertEquals(listOf("modA", "modB"), host.services.owners(Greeter::class.java))
        val failure = assertThrows<IllegalStateException> { host.services.find(Greeter::class.java) }
        assertTrue(failure.message.orEmpty().contains("modA"))
        assertTrue(failure.message.orEmpty().contains("modB"))
    }
}
