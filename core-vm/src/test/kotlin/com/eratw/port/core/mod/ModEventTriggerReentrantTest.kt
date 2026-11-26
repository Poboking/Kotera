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
import kotlin.test.assertFailsWith
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * `context.events.trigger` 在**事件派发中**的延迟投递（AL 车）。
 *
 * 与已有两文件的分工：[ModEventTriggerTest] 钉「两次 run 之间」的即时语义与线程/装配闸；
 * [ModEventTriggerDeferredTest] 钉「根级 run 内」延迟到 run 收尾。本文件钉最后一块——
 * 事件处理器里再 `trigger` 原来抛 ISE，现在进**同一条** FIFO 队列、在本轮事件派发收尾
 * （`ProcessRuntime.executeEvent` 的 finally，`eventActive` 已清）按序补派，并新增
 * 「一条 flush 链最多再入队 [ModEventTriggerWiring.MAX_TRIGGER_CHAIN_PER_FLUSH] 轮」的硬闸。
 *
 * 全部断言落在真引擎上：真 `VariableStore` + Assembler 剧本 + 真 `ProcessRuntime` + 真四桶
 * 事件链；事件 `EVT`/`EVT2`/`EVT3` 的 ERB 成员体分别打印 `evt`/`evt2`/`evt3`，「什么时候派发」
 * 直接读成输出序；入队量走 [ModEventTriggerWiring.deferredPendingCount]。
 *
 * 反向证明：把 `ProcessRuntime.executeEvent` finally 里的 `flushDeferred` 注掉，
 * 「处理器里触发的事件在派发收尾补派」一类用例全红（队列只进不出，`pending` 恒为 1）。
 */
class ModEventTriggerReentrantTest {

    // ------------------------------------------------------------------
    // 事件派发中 = 入队；本次派发收尾 = 按序补派
    // ------------------------------------------------------------------

    @Test
    fun `处理器里触发的事件_本轮派发收尾后才跑_不嵌套`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY, ReentrantHarness.EVENT2_KEY))
        h.attach(registry)
        h.establishVmThread()
        val api = h.adapter(registry)
        var outputInsideHandler = ""
        var pendingInsideHandler = 0
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            outputInsideHandler = h.output
            api.trigger(ReentrantHarness.EVENT2_KEY)
            pendingInsideHandler = ModEventTriggerWiring.deferredPendingCount(h.store)
            EventResult.CONTINUE
        })

        api.trigger(ReentrantHarness.EVENT_KEY)

        assertEquals("orig\nevt\n", outputInsideHandler, "处理器里触发不得当场嵌套派发：此刻 EVT2 体还没跑")
        assertEquals(1, pendingInsideHandler, "派发中触发必须进同一延迟队列")
        assertEquals("orig\nevt\nevt2\n", h.output, "EVT2 在本轮 EVT 派发收尾后按序补派（ERB 成员体打印 evt2）")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "补派后队列必须排空")
    }

    @Test
    fun `派发收尾flush与run收尾flush幂等_同一事件不派发两遍`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY, ReentrantHarness.EVENT2_KEY))
        h.attach(registry)
        h.establishVmThread()
        val api = h.adapter(registry)
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("a"))
        registry.register(ReentrantHarness.EVENT2_KEY, EventBucket.LATER, "mod.a", h.handler("b"))
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            api.trigger(ReentrantHarness.EVENT2_KEY)
            EventResult.CONTINUE
        })

        api.trigger(ReentrantHarness.EVENT_KEY)
        assertEquals("orig\nevt\nevt2\n", h.output, "事件派发收尾补派 EVT2 一次")

        // 紧接着跑一次根级 run（空转）：run 收尾 flush 看到的队列已被事件收尾排空，零成本早退，
        // 不得把已经派发过的 EVT/EVT2 再补一遍。
        h.callMain()

        assertEquals("orig\nevt\nevt2\norig\n", h.output, "run 收尾 flush 不得重复派发（两处 flush 幂等）")
        assertEquals(listOf("a", "b"), h.calls, "EVT 处理器 1 次、EVT2 处理器 1 次（不重复派发）")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
        assertNull(ModEventTriggerWiring.lastChainWarning(h.store), "正常补派不得触发链深闸")
    }

    // ------------------------------------------------------------------
    // 连环触发上限（防 A → B → A 死循环）
    // ------------------------------------------------------------------

    @Test
    fun `连环自触发在链上限处停止入队_一条WARN且不死循环`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY))
        h.attach(registry)
        h.establishVmThread()
        val accepted = ArrayList<Boolean>()
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            // 直接调接线层拿 Boolean（EventApi.trigger 是 Unit，看不出来「本次有没有被受理」）。
            accepted += ModEventTriggerWiring.trigger(h.store, ReentrantHarness.EVENT_KEY)
            EventResult.CONTINUE
        })

        h.adapter(registry).trigger(ReentrantHarness.EVENT_KEY)

        val dispatchLimit = 1 + ModEventTriggerWiring.MAX_DEFERRED_DISPATCH_PER_RUN
        assertEquals(dispatchLimit, accepted.size, "首轮即时派发 + 链内最多派发 64 轮后停下（旧行为是无限重入）")
        assertEquals(dispatchLimit - 1, accepted.count { it }, "链内前 63 次再入队受理")
        assertEquals(1, accepted.count { !it }, "链深到顶那一次明确返回 false（不静默丢）")
        assertEquals("orig\n" + "evt\n".repeat(dispatchLimit), h.output, "每一轮都真实派发 ERB 成员体，然后被链深闸截断")

        val warning = ModEventTriggerWiring.lastChainWarning(h.store)
        assertNotNull(warning, "链深到顶必须留诊断（不静默）")
        assertTrue(warning.contains("连环触发链"), "文案要点明连环触发：$warning")
        assertTrue(warning.contains(ReentrantHarness.EVENT_KEY), "文案要带触发事件名：$warning")
        assertTrue(warning.contains("链深="), "文案要带链深：$warning")
        assertEquals(1, h.logs.count { it.contains("连环触发链") }, "一条链只落一条 WARN：${h.logs}")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "截断后队列必须排空")
        assertNull(ModEventTriggerWiring.lastOverflowWarning(h.store), "链深闸先收口，不应再走批量溢出分支")
    }

    @Test
    fun `两个事件互相触发_链上限处停下且不重复派发`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY, ReentrantHarness.EVENT2_KEY))
        h.attach(registry)
        h.establishVmThread()
        val api = h.adapter(registry)
        // A 的处理器触发 B，B 的处理器触发 A ⇒ 若无链深闸会永久乒乓。
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            api.trigger(ReentrantHarness.EVENT2_KEY)
            EventResult.CONTINUE
        })
        registry.register(ReentrantHarness.EVENT2_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            api.trigger(ReentrantHarness.EVENT_KEY)
            EventResult.CONTINUE
        })

        api.trigger(ReentrantHarness.EVENT_KEY)

        val warning = ModEventTriggerWiring.lastChainWarning(h.store)
        assertNotNull(warning, "乒乓链必须被链深闸截断并留 WARN：${h.logs}")
        // 首轮 EVT + 链内 64 轮 = 65 次派发；偶/奇交替 ⇒ EVT 33 次、EVT2 32 次。
        assertEquals(33, h.output.split("evt\n").size - 1, "EVT 体执行 33 次（首轮 + 链内偶数轮）")
        assertEquals(32, h.output.split("evt2\n").size - 1, "EVT2 体执行 32 次")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
    }

    // ------------------------------------------------------------------
    // run 内 + 派发内混用：同一队列、FIFO、不重复
    // ------------------------------------------------------------------

    @Test
    fun `run内触发与派发内触发混用_同一队列FIFO且各派发一次`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(
            setOf(ReentrantHarness.EVENT_KEY, ReentrantHarness.EVENT2_KEY, ReentrantHarness.EVENT3_KEY),
        )
        h.attach(registry)
        val api = h.adapter(registry)
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            api.trigger(ReentrantHarness.EVENT3_KEY)
            EventResult.CONTINUE
        })
        h.hookOnF {
            // run 内先积压两条；其中 EVT 的处理器会在派发时再追加 EVT3。
            api.trigger(ReentrantHarness.EVENT_KEY)
            api.trigger(ReentrantHarness.EVENT2_KEY)
        }

        h.callMain()

        assertEquals(
            "orig\nevt\nevt2\nevt3\n",
            h.output,
            "run 收尾按 FIFO 派发 EVT、EVT2；EVT 处理器追加的 EVT3 排在其后（不插队、不重复）",
        )
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
        assertNull(ModEventTriggerWiring.lastChainWarning(h.store))
    }

    @Test
    fun `处理器一次触发两次_两次都补派不合并`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY, ReentrantHarness.EVENT2_KEY))
        h.attach(registry)
        h.establishVmThread()
        val api = h.adapter(registry)
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            api.trigger(ReentrantHarness.EVENT2_KEY)
            api.trigger(ReentrantHarness.EVENT2_KEY)
            EventResult.CONTINUE
        })

        api.trigger(ReentrantHarness.EVENT_KEY)

        assertEquals("orig\nevt\nevt2\nevt2\n", h.output, "延迟队列不按事件名去重：两次触发补派两次")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
    }

    // ------------------------------------------------------------------
    // 仍然拒绝 / fail-closed 的面（线程 / 装配 / 存在性）
    // ------------------------------------------------------------------

    @Test
    fun `非VM线程触发仍被拒_且不得入队`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY))
        h.attach(registry)
        h.establishVmThread()
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            runCatching { h.adapter(registry).trigger(ReentrantHarness.EVENT_KEY) }
                .onFailure { error.set(it) }
        }
        worker.start()
        worker.join()

        val e = error.get()
        assertTrue(e is IllegalStateException, "非 VM 线程是仅存的即时拒绝面，实际：$e")
        assertTrue(e?.message.orEmpty().contains("只允许 VM 线程调用"), "拒绝文案要指线程闸：${e?.message}")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "被拒的触发不得偷偷入队")
        assertEquals("orig\n", h.output)
    }

    @Test
    fun `事件派发中装配缺失仍UOE_不静默入队`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY))
        // 故意不 attach：EventDispatchRegistry 的触发桥未装配 ⇒ UOE 在派发前就该抛。
        // 但为了进「派发中」相位，需要一次真实派发：先用另一个 registry 建立桥再解绑。
        val throwable = AtomicReference<Throwable>()
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            runCatching { h.adapter(registry).trigger(ReentrantHarness.EVENT_KEY) }
                .onFailure { throwable.set(it) }
            EventResult.CONTINUE
        })
        h.attach(registry)
        h.establishVmThread()
        ModEventWiring.uninstall(h.store, registry)

        val error = assertFailsWith<UnsupportedOperationException> {
            h.adapter(registry).trigger(ReentrantHarness.EVENT_KEY)
        }
        assertTrue(error.message.orEmpty().contains("未装配"), "装配缺失要显式 fail-closed：${error.message}")
        assertNull(throwable.get(), "未进入派发相位，处理器不该跑")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "fail-closed 路径不得留下队列项")
        assertEquals("orig\n", h.output)
    }

    @Test
    fun `处理器里触发未知名仍被存在性闸当场拒绝`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY))
        h.attach(registry)
        h.establishVmThread()
        val api = h.adapter(registry)
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", EventHandler {
            api.trigger("EVQ")
            EventResult.CONTINUE
        })

        api.trigger(ReentrantHarness.EVENT_KEY)

        // 存在性闸在进接线层之前，处理器里的 IAE 被 ModEventWiring 的 WARN 通道隔离，不上抛。
        assertTrue(
            h.logs.any { it.contains("[WARN]") && it.contains("事件处理器异常已隔离") },
            "处理器内 IAE 应被 WARN 通道隔离（不炸派发链）：${h.logs}",
        )
        assertEquals("orig\nevt\n", h.output, "未知名不得派发任何事件")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store), "被拒绝的触发不得入队")
    }

    // ------------------------------------------------------------------
    // 队列溢出（既有口径不变）
    // ------------------------------------------------------------------

    @Test
    fun `run内批量超过上限_仍清空余下并一条WARN`() {
        val h = ReentrantHarness()
        val registry = h.handlers()
        registry.adoptKnownEvents(setOf(ReentrantHarness.EVENT_KEY))
        h.attach(registry)
        registry.register(ReentrantHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.hookOnF {
            val api = h.adapter(registry)
            repeat(ModEventTriggerWiring.MAX_DEFERRED_DISPATCH_PER_RUN + 1) {
                api.trigger(ReentrantHarness.EVENT_KEY)
            }
        }

        h.callMain()

        assertEquals(
            ModEventTriggerWiring.MAX_DEFERRED_DISPATCH_PER_RUN,
            h.calls.size,
            "单次 run 收尾仍只派发 64 条（AL 车不改这条上限）",
        )
        val warning = ModEventTriggerWiring.lastOverflowWarning(h.store)
        assertNotNull(warning, "超限必须留诊断")
        assertTrue(warning.contains("已丢弃 1 条"), "被丢条数可读：$warning")
        assertEquals(0, ModEventTriggerWiring.deferredPendingCount(h.store))
        assertNull(ModEventTriggerWiring.lastChainWarning(h.store), "批量溢出不是连环触发，不该报链深闸")
    }

    // ------------------------------------------------------------------
    // 真引擎夹具
    // ------------------------------------------------------------------

    /**
     * 真引擎夹具：`MAIN` 内 `_CALL f`（嵌套调用 ⇒ hook 在根级 run 内跑），`f` 打印 `orig`；
     * 事件成员 `ev1`/`ev2`/`ev3` 分别打印 `evt`/`evt2`/`evt3`（派发时机与顺序都可读）。
     */
    private class ReentrantHarness {
        val store = VariableStore()
        val logs = ArrayList<String>()
        val calls = ArrayList<String>()

        private val metas: List<FuncMeta> = listOf(
            FuncMeta(index = 0, key = "MAIN", name = "main"),
            FuncMeta(index = 1, key = FUNCTION_KEY, name = "f", argIntCount = 1, defIndex = 1),
            FuncMeta(index = 2, key = EVENT_KEY, name = "ev1", isEvent = true, defIndex = 2),
            FuncMeta(index = 3, key = EVENT2_KEY, name = "ev2", isEvent = true, defIndex = 3),
            FuncMeta(index = 4, key = EVENT3_KEY, name = "ev3", isEvent = true, defIndex = 4),
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

        /** 剧本打印面（派发时机与顺序的唯一读数）。 */
        val output: String get() = runtime.output.toString()

        fun handlers(): EventDispatchRegistry = EventDispatchRegistry()

        fun attach(registry: EventDispatchRegistry) = ModEventWiring.install(store, registry, logs::add)

        fun adapter(registry: EventDispatchRegistry): EventApiAdapter = EventApiAdapter(registry, "mod.a")

        /** 跑一次根级 run（记录 VM 线程身份；MAIN 内的 hook 可积压事件）。 */
        fun callMain(): Long = runtime.callFunction("MAIN")

        /** 首次根级 run：记录 VM 线程身份，让「两次 run 之间」的即时派发可用。 */
        fun establishVmThread(): Long = callMain()

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
            const val EVENT3_KEY = "EVT3"
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

                .func ev3 slots=2
                  _PRINT_LITERAL "evt3", 1
                  _RET
            """.trimIndent()

            private fun argZeroExpr(store: VariableStore): Expr =
                parseOrThrow(listOf(id("ARG"), colon(), i(0)), ReentrantStoreResolver(store))
        }
    }
}

/** 夹具里的表达式只读 `ARG` 一维，不做函数名解析。 */
private class ReentrantStoreResolver(private val store: VariableStore) : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? = store.resolveVarInfo(name, null)

    override fun resolveFunction(name: String): OperandType? = null
}
