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
import com.eratw.port.modapi.EventApi
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.Registration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `context.events.trigger`（AE 车）的真引擎夹具测试。
 *
 * 夹具照 `EventWiringTest` / `ModTestingParityTest` 的 `EventEngineHarness` 同款：
 * 真 `VariableStore` + Assembler 剧本 + 生产 LSC 四桶表 + 真 `ProcessRuntime`，事件 `EVT`
 * 的 ERB 成员体打印 `evt`。本文件的断言全部落在**引擎真实派发路径**上（不是台架镜像）：
 *
 * - 正常触发能同时唤起 ERB 事件成员与注册在 `EventDispatchRegistry` 上的 Kotlin 处理器；
 * - 归一 / 存在性 / 线程 / 重入 / 装配缺失五类边界逐条钉死；
 * - 旁路与撤销：未装主动触发桥、`ModEventWiring.uninstall` 之后都显式 fail-closed（UOE）。
 */
class ModEventTriggerTest {

    // ------------------------------------------------------------------
    // 正常派发
    // ------------------------------------------------------------------

    @Test
    fun `主动触发走真实事件链_ERB成员与运行期处理器都跑起来`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(TriggerHarness.EVENT_KEY))
        registry.register(TriggerHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(registry)

        h.adapter(registry).trigger(TriggerHarness.EVENT_KEY)

        assertEquals("evt\n", h.output, "ERB 事件成员体必须真的执行（不是台架桩）")
        assertEquals(listOf("n"), h.calls, "mod 注册在同事件上的处理器必须被派发")
    }

    @Test
    fun `连续两次触发可观测_事件成员与处理器各跑两次`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(TriggerHarness.EVENT_KEY))
        registry.register(TriggerHarness.EVENT_KEY, EventBucket.LATER, "mod.a", h.handler("l"))
        h.attach(registry)

        val api = h.adapter(registry)
        api.trigger(TriggerHarness.EVENT_KEY)
        api.trigger(TriggerHarness.EVENT_KEY)

        assertEquals("evt\nevt\n", h.output, "两次触发 = 两次真实派发，输出可数")
        assertEquals(listOf("l", "l"), h.calls, "处理器调用次数可观测（无独立计数器，按处理器/输出计）")
    }

    // ------------------------------------------------------------------
    // 归一 + 存在性闸
    // ------------------------------------------------------------------

    @Test
    fun `事件名归一_小写与带空白与原文命中同一事件`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(TriggerHarness.EVENT_KEY))
        h.attach(registry)

        val api = h.adapter(registry)
        api.trigger(" evt ")
        api.trigger("Evt")

        assertEquals("evt\nevt\n", h.output, "trim + 大写归一后与原文同义")
    }

    @Test
    fun `未知名触发被存在性闸拒绝且给出最近候选`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(TriggerHarness.EVENT_KEY))
        h.attach(registry)

        val error = assertFailsWith<IllegalArgumentException> {
            h.adapter(registry).trigger("EVQ")
        }
        assertTrue(error.message.orEmpty().contains("\"EVQ\""), "消息要含作者写的原始名：${error.message}")
        assertTrue(error.message.orEmpty().contains("最接近的候选"), "消息要附最近候选：${error.message}")
        assertTrue(error.message.orEmpty().contains(TriggerHarness.EVENT_KEY), "候选里要有 EVT：${error.message}")
        assertEquals("", h.output, "存在性闸拒绝时不得发生任何派发")
    }

    // ------------------------------------------------------------------
    // 线程闸（VM 线程假设的核实方式：runEntry 记录的线程对象身份）
    // ------------------------------------------------------------------

    @Test
    fun `非VM线程触发被拒`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        h.attach(registry)
        val api = h.adapter(registry)
        val failure = AtomicReference<Throwable?>()

        val worker = Thread {
            try {
                api.trigger(TriggerHarness.EVENT_KEY)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.start()
        worker.join()

        val thrown = failure.get()
        assertTrue(thrown is IllegalStateException, "非 VM 线程必须被拒，实际=$thrown")
        assertTrue(thrown.message.orEmpty().contains("VM 线程"), "拒绝文案要点明线程口径：${thrown.message}")
        assertEquals("", h.output, "被拒的触发不得派发")
    }

    @Test
    fun `首次VM run之前触发被拒_不猜线程`() {
        val h = TriggerHarness()
        val registry = h.handlers()
        h.attach(registry)

        val error = assertFailsWith<IllegalStateException> {
            h.adapter(registry).trigger(TriggerHarness.EVENT_KEY)
        }
        assertTrue(error.message.orEmpty().contains("VM 线程"), "未记录 VM 线程时 fail-closed：${error.message}")
    }

    // ------------------------------------------------------------------
    // 重入闸
    // ------------------------------------------------------------------

    @Test
    fun `事件处理器内再触发_延迟到本轮派发收尾补派一次`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(TriggerHarness.EVENT_KEY))
        h.attach(registry)
        val api = h.adapter(registry)
        var retriggered = false
        registry.register(TriggerHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a") {
            // AL 车：处理器里再触发不再抛 ISE，而是进同一延迟队列，等本轮事件派发收尾按序补派。
            if (!retriggered) {
                retriggered = true
                api.trigger(TriggerHarness.EVENT_KEY)
            }
            EventResult.CONTINUE
        }

        api.trigger(TriggerHarness.EVENT_KEY)

        // 旧断言是「处理器内重入抛 ISE、只派发一次」；AL 车把拒绝面改成延迟投递后，断言改为
        // 更强的正向口径：不得嵌套（此刻不能有第二次派发）、也不得丢（收尾补派恰好一次）。
        assertEquals("evt\nevt\n", h.output, "处理器里的触发延迟到本轮派发收尾补派一次（不嵌套、不丢）")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "补派后队列必须排空")
    }

    // ------------------------------------------------------------------
    // 装配缺失 / 撤销：显式 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `主动触发桥未装配时显式fail_closed`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(TriggerHarness.EVENT_KEY))
        // 注意：故意不调用 h.attach(registry) —— 模拟宿主未执行 ModEventWiring.install。
        val error = assertFailsWith<UnsupportedOperationException> {
            h.adapter(registry).trigger(TriggerHarness.EVENT_KEY)
        }
        assertTrue(error.message.orEmpty().contains("未装配"), "装配缺失要显式抛错：${error.message}")
        assertEquals("", h.output)
    }

    @Test
    fun `uninstall之后主动触发回到fail_closed`() {
        val h = TriggerHarness()
        h.establishVmThread()
        val registry = h.handlers()
        h.attach(registry)
        val api = h.adapter(registry)
        api.trigger(TriggerHarness.EVENT_KEY)
        assertEquals("evt\n", h.output)

        h.detach(registry)
        assertFailsWith<UnsupportedOperationException> { api.trigger(TriggerHarness.EVENT_KEY) }
        assertEquals("evt\n", h.output, "撤销后不得再派发")
    }

    @Test
    fun `EventApi缺省实现trigger显式fail_closed`() {
        val error = assertFailsWith<UnsupportedOperationException> {
            // 旧装配路径 / 台架镜像只实现 register，trigger 落在接口缺省体上显式 fail-closed。
            object : EventApi {
                override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration =
                    error("本用例不注册处理器")
            }.trigger(TriggerHarness.EVENT_KEY)
        }
        assertTrue(error.message.orEmpty().contains("未接线"), "缺省实现不静默丢弃：${error.message}")
    }

    // ------------------------------------------------------------------
    // 真引擎夹具
    // ------------------------------------------------------------------

    private class TriggerHarness {
        val store = VariableStore()
        val logs = ArrayList<String>()
        val calls = ArrayList<String>()

        private val metas: List<FuncMeta> = listOf(
            FuncMeta(index = 0, key = "MAIN", name = "main"),
            FuncMeta(index = 1, key = EVENT_KEY, name = "ev1", isEvent = true, defIndex = 1),
        )
        private val program: Program = Assembler.assemble(SOURCE)
        private val eventRegistry: EventRegistry = EventRegistry.of(
            SymbolCatalog.build(
                metas.map { SymbolCandidate(meta = it, ownerPackageId = BASE_OWNER) },
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

        val output: String get() = runtime.output.toString()

        fun handlers(): EventDispatchRegistry = EventDispatchRegistry()

        fun attach(registry: EventDispatchRegistry) = ModEventWiring.install(store, registry, logs::add)

        fun detach(registry: EventDispatchRegistry) = ModEventWiring.uninstall(store, registry)

        fun adapter(registry: EventDispatchRegistry): EventApiAdapter = EventApiAdapter(registry, "mod.a")

        /** 跑一次根级 run（main 立即 HALT）：这就是「VM 线程」身份的记录点。 */
        fun establishVmThread(): Long = runtime.execute()

        fun handler(label: String, result: EventResult = EventResult.CONTINUE): EventHandler = EventHandler {
            calls += label
            result
        }

        companion object {
            const val EVENT_KEY = "EVT"
            private const val BASE_OWNER = "__base__"

            private val SOURCE: String = """
                .func main slots=1
                  _HALT
                .func ev1 slots=2
                  _PRINT_LITERAL "evt", 1
                  _RET
            """.trimIndent()
        }
    }
}
