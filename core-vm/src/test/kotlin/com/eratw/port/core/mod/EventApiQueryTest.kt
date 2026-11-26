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
import com.eratw.port.modapi.EventApi
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * 事件名只读查询面（[EventApi.exists] / [EventApi.knownEventKeys]，AE 车）的语义闸。
 *
 * 钉住的口径：
 * - 归一与引擎 `EventDispatchRegistry` 的 known 闸完全同口径（trim + 大写 ROOT）；
 * - 空白 key 一律 false、不抛；未知名 false（查询面不抛异常，替代对 `trigger` 的 catch）；
 * - 已知集字典序稳定排序、返回独立快照（改返回值不影响引擎与后续调用）；
 * - 未装配（直连构造的 context）exists 恒 false、knownEventKeys 空表（fail-safe 不猜）；
 * - 与 `trigger` 的 known 闸口径一致：exist=true ⟺ 该名不会被 known 闸判未知名。
 */
class EventApiQueryTest {

    private fun newRegistry(vararg known: String): EventDispatchRegistry =
        EventDispatchRegistry().also { it.adoptKnownEvents(known.toSet()) }

    private fun newAdapter(vararg known: String): EventApiAdapter = EventApiAdapter(newRegistry(*known), "demo")

    @Test
    fun `空白事件键 exists 恒 false 且不抛`() {
        val api = newAdapter("EVENTTURNEND")

        assertFalse(api.exists(""))
        assertFalse(api.exists("   "))
        assertFalse(api.exists("\t\n"))
        // 空白键不触发引擎侧的空白 IAE（register/trigger 才抛），查询面按「没有这个事件」答。
        assertTrue(api.knownEventKeys().contains("EVENTTURNEND"))
    }

    @Test
    fun `大小写与前后空格归一后命中`() {
        val api = newAdapter("eventTurnEnd")

        assertTrue(api.exists("EVENTTURNEND"))
        assertTrue(api.exists("  eventturnend  "))
        assertTrue(api.exists("EventTurnEnd"))
        assertFalse(api.exists("EVENTTURNENDX"))
    }

    @Test
    fun `未知事件名 exists 恒 false`() {
        val api = newAdapter("EVENTTURNEND", "EVT.NORMAL")

        assertFalse(api.exists("EVENTTURNINT"))
        assertFalse(api.exists("EVENTTURNEND.EXTRA"))
        assertFalse(api.exists("EVT"))
    }

    @Test
    fun `knownEventKeys 字典序稳定排序`() {
        val api = newAdapter("zeta", "ALPHA", "  beta ")

        assertEquals(listOf("ALPHA", "BETA", "ZETA"), api.knownEventKeys())
        // 同一实例连续两次读一致（排序不依赖 HashSet 遍历序）。
        assertEquals(api.knownEventKeys(), api.knownEventKeys())
    }

    @Test
    fun `knownEventKeys 是只读快照 改返回值不影响引擎`() {
        val api = newAdapter("zeta", "ALPHA", "beta")
        val first = api.knownEventKeys()

        assertEquals(listOf("ALPHA", "BETA", "ZETA"), first)
        // 调用方拿到的是独立列表：就地清空它不影响引擎状态与后续调用。
        (first as java.util.Collection<*>).clear()

        assertEquals(listOf("ALPHA", "BETA", "ZETA"), api.knownEventKeys())
        assertNotSame(first, api.knownEventKeys())
        assertTrue(api.exists("ALPHA"))
    }

    @Test
    fun `未装配 context exists 恒 false 且 knownEventKeys 空表`() {
        // 直连构造、未经宿主 adoptEventRegistry 的 context：事件面是空集兜底（不猜事件面）。
        val context = newUnassembledContext()

        assertFalse(context.events.exists("EVENTTURNEND"))
        assertFalse(context.events.exists(""))
        assertTrue(context.events.knownEventKeys().isEmpty())
    }

    @Test
    fun `EventApi 缺省实现 exists 与 knownEventKeys 显式 fail_closed`() {
        val impl = object : EventApi {
            override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration =
                error("本用例只测缺省实现，不注册")
        }

        val existsFailure = assertThrows<UnsupportedOperationException> { impl.exists("EVENTTURNEND") }
        assertTrue(existsFailure.message!!.contains("events.exists("))
        assertTrue(existsFailure.message!!.contains("未接线"))

        val keysFailure = assertThrows<UnsupportedOperationException> { impl.knownEventKeys() }
        assertTrue(keysFailure.message!!.contains("events.knownEventKeys()"))
        assertTrue(keysFailure.message!!.contains("未接线"))
    }

    @Test
    fun `exists 与 trigger 的 known 闸口径一致`() {
        val registry = newRegistry("EVENTTURNEND", "EVT.NORMAL")
        // 装配桥：已知名会被 known 闸放行后调桥；未知名在闸上先抛 IAE（桥根本读不到）。
        registry.adoptTriggerBridge { true }
        val api = EventApiAdapter(registry, "demo")

        assertTrue(api.exists("eventturnend"))
        api.trigger("  EVENTTURNEND  ") // 已知 ⇒ 不抛未知名

        assertFalse(api.exists("EVENTTURNINT"))
        val failure = assertThrows<IllegalArgumentException> { api.trigger("EVENTTURNINT") }
        assertTrue(failure.message!!.contains("未知事件名"))
    }

    @Test
    fun `未装配 registry 查询面不猜但触发沿用空集放行口径`() {
        // 空已知集：按 KDoc，register/trigger 沿用「不校验」放行，查询面则必须答「不知道」⇒ false。
        val registry = newRegistry()
        registry.adoptTriggerBridge { true }
        val api = EventApiAdapter(registry, "demo")

        assertFalse(api.exists("EVENTTURNEND"))
        assertTrue(api.knownEventKeys().isEmpty())
        api.trigger("EVENTTURNEND") // 不抛未知名（与空集放行口径一致，见 exists KDoc）
    }

    // ---- 直连构造 context 的最小依赖（照 ModContextImplTest 同形，未走宿主装配）----

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

    private fun newSnapshot(packageId: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private fun newUnassembledContext(packageId: String = "demo"): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = newSnapshot(packageId),
        entityKernel = newKernel(),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )
}
