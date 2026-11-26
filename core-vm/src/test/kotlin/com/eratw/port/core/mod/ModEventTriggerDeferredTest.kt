package com.eratw.port.core.mod

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.colon
import com.eratw.port.core.expr.i
import com.eratw.port.core.expr.id
import com.eratw.port.core.expr.parseOrThrow
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.HookDecls
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookResult
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * `context.events.trigger` 在 **VM 根级 run 内**的延迟投递（AG 车）。
 *
 * 与 [ModEventTriggerTest] 的分工：那个文件钉「run 之外」的既有边界（归一 / 存在性 / 线程 /
 * 事件派发重入 / 装配缺失）；本文件钉「run 内」这一新相位——PREFIX/POSTFIX hook 之类回调都在
 * 根级 run 里跑（ERB `_CALL f` → `VmHost.dispatchNestedHookCall`，见
 * `FunctionHookWiringNestedCallTest`），那正是 mod 最想「响应一次调用顺手抛事件」的位置。
 *
 * 全部断言落在**真引擎**上（真 `VariableStore` + Assembler 剧本 + 真 `ProcessRuntime` +
 * 真四桶事件链）：事件 `EVT`/`EVT2` 的 ERB 成员体分别打印 `evt`/`evt2`，因此「什么时候派发」
 * 直接可读成输出序；入队/排空量走 [ModEventTriggerWiring.deferredPendingCount]。
 *
 * 反向证明：去掉 `ProcessRuntime.runEntry` finally 里的 `flushDeferred` 调用，本文件
 * 「run 收尾按序派发」一类的用例全红（队列只进不出）。
 */
class ModEventTriggerDeferredTest {

    // ------------------------------------------------------------------
    // run 内 = 入队；run 收尾 = 按序派发
    // ------------------------------------------------------------------

    @Test
    fun `run内hook触发不当时派发_run收尾才派发ERB成员与处理器`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        registry.register(DeferredHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(registry)
        var outputInsideHook = ""
        var pendingInsideHook = 0
        h.hookOnF {
            h.adapter(registry).trigger(DeferredHarness.EVENT_KEY)
            outputInsideHook = h.output
            pendingInsideHook = ModEventTriggerWiring.deferredPendingCount(h.store)
        }

        h.callMain()

        assertEquals("", outputInsideHook, "run 内触发不得当场派发：hook 执行时事件体还没跑")
        assertEquals(1, pendingInsideHook, "run 内触发必须进延迟队列（FIFO 待派发）")
        assertEquals("orig\nevt\n", h.output, "run 收尾后事件才真正派发（ERB 成员体打印 evt）")
        assertEquals(listOf("n"), h.calls, "运行期处理器也随收尾派发各跑一次")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "收尾派发后队列必须排空")
    }

    @Test
    fun `run内两次触发_按入队序FIFO派发`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY, DeferredHarness.EVENT2_KEY))
        h.attach(registry)
        h.hookOnF {
            val api = h.adapter(registry)
            api.trigger(DeferredHarness.EVENT2_KEY)
            api.trigger(DeferredHarness.EVENT_KEY)
        }

        h.callMain()

        assertEquals("orig\nevt2\nevt\n", h.output, "派发序 = 入队序（EVT2 先入先发），不是事件名字典序")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
    }

    @Test
    fun `每次run各自收尾_不跨run累积`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY, DeferredHarness.EVENT2_KEY))
        h.attach(registry)
        var run = 0
        h.hookOnF {
            val api = h.adapter(registry)
            api.trigger(if (run == 0) DeferredHarness.EVENT_KEY else DeferredHarness.EVENT2_KEY)
        }

        run = 0
        h.callMain()
        assertEquals("orig\nevt\n", h.output, "第一次 run 只带出自己的积压")
        run = 1
        h.callMain()
        assertEquals("orig\nevt\norig\nevt2\n", h.output, "第二次 run 的积压在它自己的收尾派发，不与上一次混合")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
    }

    // ------------------------------------------------------------------
    // run 之外的既有语义不变
    // ------------------------------------------------------------------

    @Test
    fun `两次run之间触发仍当刻立即派发`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        registry.register(DeferredHarness.EVENT_KEY, EventBucket.LATER, "mod.a", h.handler("l"))
        h.attach(registry)

        h.callMain()
        assertEquals("orig\n", h.output, "没有积压时收尾不派发任何东西")
        h.adapter(registry).trigger(DeferredHarness.EVENT_KEY)

        assertEquals("orig\nevt\n", h.output, "run 之间调用 = 当刻立即派发（旧语义不变）")
        assertEquals(listOf("l"), h.calls)
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "立即派发路径不进队列")
    }

    @Test
    fun `事件派发中触发_改为延迟补派而非拒绝_且只补一次`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        val reentryErrors = ArrayList<String>()
        var retriggered = false
        registry.register(
            DeferredHarness.EVENT_KEY,
            EventBucket.NORMAL,
            "mod.a",
            EventHandler {
                // AL 车：处理器里再 trigger 不再抛 ISE，而是进同一延迟队列，等本轮派发收尾补派。
                runCatching {
                    if (!retriggered) {
                        retriggered = true
                        h.adapter(registry).trigger(DeferredHarness.EVENT_KEY)
                    }
                }.onFailure { reentryErrors += "${it::class.simpleName}: ${it.message}" }
                EventResult.CONTINUE
            },
        )
        h.attach(registry)
        h.hookOnF { h.adapter(registry).trigger(DeferredHarness.EVENT_KEY) }

        h.callMain()

        // 旧断言是「处理器内再触发抛 ISE」；AL 车把该拒绝面改成延迟投递，断言改为更强的正向
        // 口径：不嵌套（第一次派发收尾后才跑第二次）、不丢、只补一次、队列排空。
        assertTrue(reentryErrors.isEmpty(), "处理器内再触发不得再被拒：$reentryErrors")
        assertEquals("orig\nevt\nevt\n", h.output, "第一次派发收尾后才补派第二次（不嵌套、不丢）")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "补派后队列必须排空")
    }

    @Test
    fun `非VM线程触发仍被拒_且不得入队`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        h.attach(registry)
        h.callMain()
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            runCatching { h.adapter(registry).trigger(DeferredHarness.EVENT_KEY) }
                .onFailure { error.set(it) }
        }
        worker.start()
        worker.join()

        val e = error.get()
        assertTrue(e is IllegalStateException, "非 VM 线程必须被拒，实际：$e")
        assertTrue(e?.message.orEmpty().contains("只允许 VM 线程调用"), "拒绝文案要指线程闸：${e?.message}")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "被拒的触发不得偷偷入队")
        assertEquals("orig\n", h.output)
    }

    @Test
    fun `run内装配缺失仍显式fail_closed_不静默入队`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        // 故意不 attach：模拟宿主未执行 ModEventWiring.install（主动触发桥未装配）。
        val thrown = AtomicReference<Throwable>()
        h.hookOnF {
            runCatching { h.adapter(registry).trigger(DeferredHarness.EVENT_KEY) }
                .onFailure { thrown.set(it) }
        }

        h.callMain()

        val e = thrown.get()
        assertTrue(e is UnsupportedOperationException, "装配缺失必须当场抛错：$e")
        assertTrue(
            e?.message.orEmpty().contains("未装配"),
            "装配缺失文案：${e?.message}",
        )
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "fail-closed 路径不得留下队列项")
        assertEquals("orig\n", h.output, "没有任何派发")
    }

    // ------------------------------------------------------------------
    // 上限与告警（不静默）
    // ------------------------------------------------------------------

    @Test
    fun `run内批量触发超过上限_只派发64条并WARN丢弃余下`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        registry.register(DeferredHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(registry)
        val total = ModEventTriggerWiring.MAX_DEFERRED_DISPATCH_PER_RUN + 1
        h.hookOnF {
            val api = h.adapter(registry)
            repeat(total) { api.trigger(DeferredHarness.EVENT_KEY) }
        }

        h.callMain()

        assertEquals(
            ModEventTriggerWiring.MAX_DEFERRED_DISPATCH_PER_RUN,
            h.calls.size,
            "单次 run 收尾最多派发 ${ModEventTriggerWiring.MAX_DEFERRED_DISPATCH_PER_RUN} 条",
        )
        val warning = ModEventTriggerWiring.lastOverflowWarning(h.store)
        assertNotNull(warning, "超限必须留诊断（不静默丢）")
        assertTrue(warning.contains("超过上限"), "诊断文案要指上限：$warning")
        assertTrue(warning.contains("已丢弃 1 条"), "被丢条数可读：$warning")
        assertTrue(h.logs.any { it.contains("[WARN]") && it.contains("延迟队列") }, "告警要落到接线日志面：${h.logs}")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "超限后余下条目整批清掉")
    }

    @Test
    fun `空队列run收尾零派发`() {
        val h = DeferredHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(DeferredHarness.EVENT_KEY))
        registry.register(DeferredHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(registry)
        var run = 0
        h.hookOnF {
            // 只有第一次 run 触发；第二次 run 的 hook 不触发，收尾必须是纯空转。
            if (run == 0) h.adapter(registry).trigger(DeferredHarness.EVENT_KEY)
        }

        run = 0
        h.callMain()
        run = 1
        h.callMain()

        assertEquals(1, h.calls.size, "只派发第一次 run 积压的那一条")
        assertEquals("orig\nevt\norig\n", h.output, "第二次 run 收尾不得多派发任何事件")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
        assertNull(ModEventTriggerWiring.lastOverflowWarning(h.store), "正常路径不得有超限诊断")
    }

    // ------------------------------------------------------------------
    // 真引擎夹具
    // ------------------------------------------------------------------

    /**
     * 真引擎夹具：`MAIN` 内部 `_CALL f`（嵌套调用 ⇒ hook 在根级 run 内执行），`f` 打印 `orig`；
     * 事件成员 `ev1`/`ev2` 分别打印 `evt`/`evt2`（派发时机可读）。
     */
    private class DeferredHarness {
        val store = VariableStore()
        val logs = ArrayList<String>()
        val calls = ArrayList<String>()

        private val metas: List<FuncMeta> = listOf(
            FuncMeta(index = 0, key = "MAIN", name = "main"),
            FuncMeta(index = 1, key = FUNCTION_KEY, name = "f", argIntCount = 1, defIndex = 1),
            FuncMeta(index = 2, key = EVENT_KEY, name = "ev1", isEvent = true, defIndex = 2),
            FuncMeta(index = 3, key = EVENT2_KEY, name = "ev2", isEvent = true, defIndex = 3),
        )
        private val program: Program = Assembler.assemble(SOURCE, listOf(argZeroExpr(store)))
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

        /** 剧本打印面（派发时机的唯一读数）。 */
        val output: String get() = runtime.output.toString()

        fun handlers(): EventDispatchRegistry = EventDispatchRegistry()

        fun attach(registry: EventDispatchRegistry) = ModEventWiring.install(store, registry, logs::add)

        fun adapter(registry: EventDispatchRegistry): EventApiAdapter = EventApiAdapter(registry, "mod.a")

        /** 宿主按名调用 MAIN：根级 run 入口（这也记录 VM 线程身份）。 */
        fun callMain(): Long = runtime.callFunction("MAIN")

        /** 在 `F` 的 PREFIX 里跑 [body]：`_CALL f` 期间 `isInVmRun()` 为真。 */
        fun hookOnF(body: () -> Unit) {
            val pipeline = HookPipeline.of(listOf(HookDecls.prefix("mod.a", targetKey = FUNCTION_KEY)))
            pipeline.bind(FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") {
                body()
                HookResult.Continue
            }
            FunctionHookWiring.install(store, pipeline, logs::add)
        }

        fun handler(label: String): EventHandler = EventHandler {
            calls += label
            EventResult.CONTINUE
        }

        companion object {
            const val EVENT_KEY = "EVT"
            const val EVENT2_KEY = "EVT2"
            const val FUNCTION_KEY = "F"
            private const val BASE_OWNER = "__base__"
            private val SOURCE: String = """
                .func main slots=4
                  _SET_INT R0, 10
                  _CALL f, 1, R1
                  _RET R1

                .func f arity=1 slots=2
                  _EVAL_INT ${'$'}0, R1
                  _PRINT_LITERAL "orig", 1
                  _RET R1

                .func ev1 slots=2
                  _PRINT_LITERAL "evt", 1
                  _RET

                .func ev2 slots=2
                  _PRINT_LITERAL "evt2", 1
                  _RET
            """.trimIndent()

            private fun argZeroExpr(store: VariableStore): Expr =
                parseOrThrow(listOf(id("ARG"), colon(), i(0)), DeferredStoreResolver(store))
        }
    }
}

/** 夹具里的表达式只读 `ARG` 一维，不做函数名解析。 */
private class DeferredStoreResolver(private val store: VariableStore) : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? = store.resolveVarInfo(name, null)

    override fun resolveFunction(name: String): OperandType? = null
}
