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
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 事件名存在性闸（Lane U）：`EventDispatchRegistry.register` 在灌入已知事件名集合后，
 * 对**拼错的事件名**当场抛 [IllegalArgumentException] 并给最接近候选，而不是静默永不触发；
 * 集合为空（台架 / 无构建期 `EventRegistry` 的会话）逐位放行。
 */
class EventDispatchRegistryKnownKeysTest {

    private fun newRegistry(vararg known: String): EventDispatchRegistry =
        EventDispatchRegistry().also { it.adoptKnownEvents(known.toSet()) }

    private fun continueHandler(): EventHandler = EventHandler { EventResult.CONTINUE }

    @Test
    fun `已知事件名注册成功_撤销后不再有处理器`() {
        val registry = newRegistry("EVENTTURNEND", "EVENTTURNSTART")
        val registration = registry.register(
            "EVENTTURNEND",
            EventBucket.NORMAL,
            "modA",
            continueHandler(),
        )
        assertTrue(registry.hasHandlers("EVENTTURNEND"), "已知事件名必须照常注册")
        registration.unregister()
        assertFalse(registry.hasHandlers("EVENTTURNEND"), "撤销后不得残留处理器")
    }

    @Test
    fun `拼错事件名当场硬拒_消息含原始名归属包与最接近候选`() {
        val registry = newRegistry(
            "EVENTTURNEND",
            "EVENTTURNSTART",
            "EVENTWAIT",
            "EVENTLOAD",
            "EVENTSAVE",
            "EVENTSHOW",
            "EVENTZZZZOUTLIER",
        )
        val ex = assertThrows<IllegalArgumentException> {
            registry.register("EVENTTURNED", EventBucket.NORMAL, "modTypo", continueHandler())
        }
        val message = ex.message!!
        assertTrue(message.contains("EVENTTURNED"), "消息必须含原始拼错名（作者按它 grep 源码）：$message")
        assertTrue(message.contains("modTypo"), "消息必须含归属包：$message")
        assertTrue(message.contains("EVENTTURNEND"), "最接近候选必须含正确事件名：$message")
        assertFalse(
            message.contains("EVENTZZZZOUTLIER"),
            "候选只给最近的几个、不倒全量事件表：$message",
        )
        assertFalse(registry.hasHandlers("EVENTTURNED"), "硬拒后不得留下半条注册")
    }

    @Test
    fun `归一口径 trim 加大写_作者写小写或带空白也算命中`() {
        // 已知集本身也按同一口径归一（装配侧读到的是大写集合，这里故意给脏数据双向钉住）。
        val registry = newRegistry(" eventturnend ")
        registry.register("eventturnend", EventBucket.PRI, "modA", continueHandler())
        assertTrue(registry.hasHandlers("EVENTTURNEND"))
        assertThrows<IllegalArgumentException> {
            registry.register(" EVENTTURNED ", EventBucket.PRI, "modA", continueHandler())
        }
    }

    @Test
    fun `空集等于不校验_台架场景任意事件名放行`() {
        val fresh = EventDispatchRegistry()
        fresh.register("ANYTHING_GOES", EventBucket.NORMAL, "modA", continueHandler())
        assertTrue(fresh.hasHandlers("ANYTHING_GOES"), "未灌集时缺省必须不校验")

        val adoptedNothing = newRegistry()
        adoptedNothing.register("EVENTTURNED", EventBucket.NORMAL, "modA", continueHandler())
        assertTrue(adoptedNothing.hasHandlers("EVENTTURNED"), "显式传空集同样关闭校验")
    }

    @Test
    fun `adoptKnownEvents 可重设_再传空集即关闸`() {
        val registry = newRegistry("EVENTTURNEND")
        assertThrows<IllegalArgumentException> {
            registry.register("EVENTTURNED", EventBucket.NORMAL, "modA", continueHandler())
        }
        registry.adoptKnownEvents(emptySet())
        registry.register("EVENTTURNED", EventBucket.NORMAL, "modA", continueHandler())
        assertTrue(registry.hasHandlers("EVENTTURNED"), "重设空集必须关闸（不是只加不减）")
    }

    @Test
    fun `候选排序确定_同一输入两次拒绝消息逐字相同`() {
        val registry = newRegistry("EVENTTURNEND", "EVENTTURNSTOP", "EVENTTURNAAAA")
        fun rejectedMessage(): String = assertThrows<IllegalArgumentException> {
            registry.register("EVENTTURNE", EventBucket.NORMAL, "modA", continueHandler())
        }.message!!
        assertEquals(rejectedMessage(), rejectedMessage(), "候选排序必须确定（编辑距离 + 名字序）")
    }
}

/**
 * 宿主面接线：`ModRuntimeHost.knownEventKeys` 在 `install` 之前灌入后，拼错事件名的 mod
 * 在 `onLoad` 注册那一刻就失败、只回滚它自己，同批正确 mod 照常启用。
 */
class ModRuntimeHostKnownEventKeysTest {

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
                ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }

    /** 在 `onLoad` 里注册一个事件处理器的最小 mod（事件名由外部给，便于故意写错）。 */
    private class EventRegisterMod(private val eventKey: String) : EraMod {
        var loads: Int = 0
            private set

        override fun onLoad(context: ModContext) {
            loads++
            context.events.register(eventKey, EventBucket.NORMAL) { EventResult.CONTINUE }
        }

        override fun onEnable() {
            loads++
        }

        override fun onDisable() {
            loads++
        }

        override fun onUnload() {
            loads++
        }
    }

    @Test
    fun `灌入 knownEventKeys 后_拼错事件名的 mod 装载失败被隔离而正确 mod 照常启用`() {
        val logs = mutableListOf<String>()
        val host = ModRuntimeHost(
            snapshot = newSnapshot("modGood", "modTypo"),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )
        host.knownEventKeys = setOf("EVENTTURNEND")
        val report = host.install(
            listOf(
                "modGood" to EventRegisterMod("EVENTTURNEND"),
                "modTypo" to EventRegisterMod("EVENTTURNED"),
            ),
        )

        assertEquals(2, report.size)
        assertNull(report[0].failure, "正确事件名的 mod 不得受牵连")
        assertTrue(report[0].active, "正确事件名的 mod 必须照常启用")
        assertFalse(report[1].active, "拼错事件名必须判该 mod 装载失败（active=false）")
        val failure = assertNotNull(host.failureOf("modTypo"), "失败原因必须挂在该 mod 上")
        assertTrue(failure is IllegalArgumentException, "失败原因必须是事件名闸的 IAE：$failure")
        assertTrue(failure.message!!.contains("EVENTTURNED"), "失败原因必须带拼错名：${failure.message}")
        assertTrue(
            host.eventHandlers.hasHandlers("EVENTTURNEND"),
            "正确 mod 的处理器必须仍在会话共享注册表里",
        )
        assertTrue(logs.isNotEmpty(), "失败必须上日志面，不许静默")
    }
}
