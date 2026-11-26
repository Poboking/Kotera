package com.eratw.port.core.mod

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-26 A1：mod **自定义事件声明域**。
 *
 * 旧形态（REVIEW-MOD-ARCH-20261123 A1）：事件键 = 语料事件名（`SymbolCatalog.events`），
 * mod 自造键被存在性闸当场 IAE，且无任何合法声明通道 ——「文档不说、闸拦死」的模糊态。
 *
 * 新形态（消解裁定：开声明域，不走「永不开放」）：
 * - `EventDispatchRegistry.declare`：幂等声明共享总线话题；与语料事件相撞 IAE（命名空间分离）；
 * - 声明后的键过 [EventDispatchRegistry.register] / [trigger] 的存在性闸，多 mod 可订阅/触发；
 * - 引擎派发：`ProcessRuntime.executeEvent` 对无 ERB 成员的键不再直接 false —— 有运行期
 *   处理器就照常走四桶链（只跑 Kotlin 成员；旧形态连声明了也静默无效果，残疾）。
 */
class CustomEventDomainTest {

    // ---------------- 声明域语义 ----------------

    @Test
    fun `declare 空白键 IAE`() {
        val registry = EventDispatchRegistry()
        assertThrows<IllegalArgumentException> { registry.declare("  ", "mod.a") }
        assertEquals(emptyList(), registry.declaredEventKeys())
    }

    @Test
    fun `declare 语料已有事件 IAE 命名空间分离`() {
        val registry = EventDispatchRegistry().also { it.adoptKnownEvents(setOf("EVENTTURNEND")) }
        val e = assertThrows<IllegalArgumentException> { registry.declare("eventturnend", "mod.a") }
        assertTrue(e.message!!.contains("语料已有事件"), "实际：${e.message}")
        assertTrue(e.message!!.contains("events.register"), "指引文案要点名 register：${e.message}")
        assertEquals(emptyList(), registry.declaredEventKeys())
    }

    @Test
    fun `declare 幂等_多 mod 共享话题不报错`() {
        val registry = EventDispatchRegistry()
        registry.declare("MY_EVT", "mod.a")
        registry.declare("my_evt ", "mod.b") // 归一后同键 = 幂等
        assertEquals(listOf("MY_EVT"), registry.declaredEventKeys())
    }

    @Test
    fun `声明后 register 与 trigger 过存在性闸`() {
        val registry = EventDispatchRegistry().also { it.adoptKnownEvents(setOf("EVENTFIRST")) }
        registry.declare("MOD_A_EVT", "mod.a")
        // register / trigger 不再对自定义键报未知名。
        val reg = registry.register("MOD_A_EVT", EventBucket.NORMAL, "mod.a") { EventResult.CONTINUE }
        registry.adoptTriggerBridge { true }
        assertTrue(registry.trigger("MOD_A_EVT", "mod.a"), "声明键过 trigger 闸并进桥")
        reg.unregister()
        assertFalse(registry.hasHandlers("MOD_A_EVT"), "撤销后桶空")
    }

    @Test
    fun `未声明且非语料的键仍被闸拒绝_文案带 declare 指引`() {
        val registry = EventDispatchRegistry().also { it.adoptKnownEvents(setOf("EVENTFIRST")) }
        val e = assertThrows<IllegalArgumentException> {
            registry.register("MOD_A_EVT", EventBucket.NORMAL, "mod.a") { EventResult.CONTINUE }
        }
        assertTrue(e.message!!.contains("events.declare"), "未知名文案要指引 declare 通道：${e.message}")
        val t = assertThrows<IllegalArgumentException> { registry.trigger("MOD_A_EVT", "mod.a") }
        assertTrue(t.message!!.contains("events.declare"), "trigger 同口径：${t.message}")
    }

    @Test
    fun `exists 声明键恒真_未声明未知键恒假`() {
        val registry = EventDispatchRegistry().also { it.adoptKnownEvents(setOf("EVENTFIRST")) }
        assertFalse(registry.exists("MOD_A_EVT"))
        registry.declare("MOD_A_EVT", "mod.a")
        assertTrue(registry.exists("MOD_A_EVT"), "声明过的自定义事件在 exists 里恒真")
        assertFalse(registry.exists("OTHER_EVT"))
        assertFalse(registry.exists(""))
    }

    // ---------------- 引擎派发（无 ERB 成员键照常走四桶链） ----------------

    @Test
    fun `executeEvent_无ERB成员但已声明且有处理器_四桶只跑Kotlin成员`() {
        val h = CustomEventHarness()
        val registry = h.handlers()
        registry.declare("MY_EVT", "mod.a")
        val calls = ArrayList<String>()
        val r1 = registry.register("MY_EVT", EventBucket.PRI, "mod.a") { calls += "pri"; EventResult.CONTINUE }
        registry.register("MY_EVT", EventBucket.LATER, "mod.b") { calls += "later"; EventResult.CONTINUE }
        h.attach(registry)

        assertTrue(h.runtime.executeEvent("MY_EVT"), "自定义事件有成员被派发 ⇒ 返回「执行了」")
        assertEquals(listOf("pri", "later"), calls, "四桶序 PRI → LATER，跨 mod 共享链")
        r1.unregister()
        calls.clear()
        assertTrue(h.runtime.executeEvent("MY_EVT"), "仍有 LATER 成员")
        assertEquals(listOf("later"), calls, "撤销只影响本包本桶注册")
    }

    @Test
    fun `executeEvent_无成员且无处理器_仍返回 false`() {
        val h = CustomEventHarness()
        assertFalse(h.runtime.executeEvent("GHOST_EVT"), "旧口径保留：没有任何成员 = 未执行")
    }
}

/**
 * 照 `ModEventTriggerTest.TriggerHarness` 同款的最小引擎夹具：main 空、无任何 ERB 事件成员
 * —— 自定义事件域的键天然无 ERB 成员链，派发只可能落到运行期处理器。
 */
private class CustomEventHarness {
    val store = VariableStore()
    val logs = ArrayList<String>()

    private val metas: List<FuncMeta> = listOf(
        FuncMeta(index = 0, key = "MAIN", name = "main"),
    )
    private val program: Program = Assembler.assemble(SOURCE)
    private val eventRegistry: EventRegistry = EventRegistry.of(
        SymbolCatalog.build(
            metas.map { SymbolCandidate(meta = it, ownerPackageId = "__base__") },
            ResolverPolicy.LEGACY_LAST_WINS,
        ),
    )

    val runtime: ProcessRuntime = ProcessRuntime(
        program,
        store,
        FunctionTable(metas),
        VmConfig(),
        eventRegistry = eventRegistry,
    )

    fun handlers(): EventDispatchRegistry = EventDispatchRegistry()

    fun attach(registry: EventDispatchRegistry) = ModEventWiring.install(store, registry, logs::add)

    companion object {
        private val SOURCE: String = """
            .func main slots=1
              _HALT
        """.trimIndent()
    }
}
