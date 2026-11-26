package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.mod.EventDispatchRegistry
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventContext
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 批 2 出口判据：**`context.events.register` 的运行期处理器真进事件派发**（§6.1 事件贡献点）。
 *
 * 改造前 `ModContextImpl.events` 是 `FailClosedEventApi`（register 当场抛「M2-6 生产消费点未接」），
 * 作者写了处理器也永远不会被调用。现在接线的语义是：
 * - 事件成员链仍由构建期 [EventRegistry] 唯一派生，注册表只补**运行期 Kotlin 处理器**；
 * - 同桶顺序 = 先 ERB 成员（链接序），再运行期处理器（全局注册序）；
 * - `#ONLY` 桶在运行期硬拒（它终止整条链，运行期插入会让 base 与其它 Mod 的成员全部失效）；
 * - 未安装接线 / 该事件零注册 = 完全旁路（golden 零变化的前提）。
 *
 * 夹具走生产 LSC 构建器 [SymbolCatalog.build] → [EventRegistry.of]，不手搓四桶表。
 */
class EventWiringTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun mainMeta(): FuncMeta = FuncMeta(index = 0, key = "MAIN", name = "main")

    private fun eventMeta(
        index: Int,
        isPri: Boolean = false,
        isLater: Boolean = false,
        isSingle: Boolean = false,
    ): FuncMeta = FuncMeta(
        index = index,
        key = WiringHarness.EVENT_KEY,
        name = "ev$index",
        isEvent = true,
        isPri = isPri,
        isLater = isLater,
        isSingle = isSingle,
        defIndex = index,
    )

    /** PRI(p0,p1) → NORMAL(n0) → LATER(l0)：桶位交叉，用来判「桶优先于注册序」。 */
    private fun mixedMetas(): List<FuncMeta> = listOf(
        mainMeta(),
        eventMeta(1, isPri = true),
        eventMeta(2, isPri = true),
        eventMeta(3),
        eventMeta(4, isLater = true),
    )

    private val mixedMarkers = listOf("p0", "p1", "n0", "l0")

    // ------------------------------------------------------------------
    // 旁路：未装 / 零注册
    // ------------------------------------------------------------------

    @Test
    fun `未安装接线时注册表里的处理器完全不被调用`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        assertTrue(h.runEvent())
        assertEquals("p0\np1\nn0\nl0\n", h.output, "ERB 成员链逐位不变")
        assertEquals(emptyList(), h.calls, "未挂 store.aux ⇒ 处理器一次都不该被调用")
    }

    @Test
    fun `已安装接线但该事件零注册时仍完全旁路`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        registry.register("OTHEREVENT", EventBucket.NORMAL, "mod.a", h.handler("other"))
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals("p0\np1\nn0\nl0\n", h.output)
        assertEquals(emptyList(), h.calls, "注册在别的事件名上不该被本事件唤起")
    }

    // ------------------------------------------------------------------
    // 桶位序 + 「ERB 成员先、处理器后」
    // ------------------------------------------------------------------

    @Test
    fun `处理器按桶位而非注册序执行且排在本桶ERB成员之后`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        // 故意逆序注册：LATER → NORMAL → PRI。若实现按注册序跑，断言立刻红。
        registry.register(WiringHarness.EVENT_KEY, EventBucket.LATER, "mod.a", h.handler("later"))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("normal"))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.PRI, "mod.a", h.handler("pri"))
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals("p0\np1\nn0\nl0\n", h.output)
        assertEquals(
            listOf(
                // 快照 = 处理器被调用瞬间剧本已打印出的面 ⇒ 证明本桶 ERB 成员先跑完。
                "pri@mod.a|p0,p1",
                "normal@mod.a|p0,p1,n0",
                "later@mod.a|p0,p1,n0,l0",
            ),
            h.calls,
            "桶位序 PRI → NORMAL → LATER，且每桶处理器排在该桶 ERB 成员之后",
        )
    }

    @Test
    fun `同桶多处理器按全局注册序而非包名序`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.zzz", h.handler("first"))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.aaa", h.handler("second"))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.mmm", h.handler("third"))
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals(
            listOf("first@mod.zzz|p0,p1,n0", "second@mod.aaa|p0,p1,n0", "third@mod.mmm|p0,p1,n0"),
            h.calls,
            "同桶顺序 = 注册序（跨 mod 的执行序必须确定，不能按包名/哈希排）",
        )
    }

    // ------------------------------------------------------------------
    // SINGLE 截断口径
    // ------------------------------------------------------------------

    @Test
    fun `处理器返回 SINGLE 只截断本桶余下处理器不跨桶`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        registry.register(WiringHarness.EVENT_KEY, EventBucket.PRI, "mod.a", h.handler("a1", EventResult.SINGLE))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.PRI, "mod.b", h.handler("a2"))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals(
            listOf("a1@mod.a|p0,p1", "n@mod.a|p0,p1,n0"),
            h.calls,
            "PRI 桶的 a2 被截断；NORMAL 桶不受影响（与 #SINGLE 只截断本桶同口径）",
        )
    }

    @Test
    fun `ERB的SINGLE截断本桶时该桶处理器也不再跑`() {
        val h = WiringHarness(
            markers = listOf("p1", "p2", "n0"),
            metas = listOf(
                mainMeta(),
                eventMeta(1, isPri = true, isSingle = true),
                eventMeta(2, isPri = true),
                eventMeta(3),
            ),
            singleAt = setOf(0),
        )
        val registry = h.handlers()
        registry.register(WiringHarness.EVENT_KEY, EventBucket.PRI, "mod.a", h.handler("pri"))
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals("p1\nn0\n", h.output, "PRI 桶 p2 被 ERB 的 #SINGLE 跳过")
        assertEquals(
            listOf("n@mod.a|p1,n0"),
            h.calls,
            "本桶被截断 ⇒ 该桶运行期处理器也跳过；下一桶的处理器照跑",
        )
    }

    // ------------------------------------------------------------------
    // ONLY 硬拒 / 异常隔离 / 撤销
    // ------------------------------------------------------------------

    @Test
    fun `ONLY桶注册被硬拒并指向 hooks`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        val ex = assertFailsWith<IllegalArgumentException> {
            registry.register(WiringHarness.EVENT_KEY, EventBucket.ONLY, "mod.a", h.handler("only"))
        }
        val message = ex.message!!
        assertTrue(message.contains("ONLY"), "报错要点名 ONLY 桶：$message")
        assertTrue(message.contains("PREFIX"), "报错要指路 hooks PREFIX + SkipOriginal：$message")
        assertFalse(registry.hasHandlers(WiringHarness.EVENT_KEY), "拒绝必须不留下半条注册")
    }

    @Test
    fun `处理器抛异常只隔离自己后续处理器照跑`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.boom") {
            error("故意抛错")
        }
        registry.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("after"))
        h.attach(registry)
        assertTrue(h.runEvent(), "处理器炸掉不能把事件派发整个拖垮")
        assertEquals(listOf("after@mod.a|p0,p1,n0"), h.calls, "后一个处理器必须照跑")
        assertEquals(1, h.logs.size, "隔离要留一条取证面：${h.logs}")
        assertTrue(h.logs.single().contains("mod.boom"), "日志要点名肇事包：${h.logs.single()}")
        assertTrue(h.logs.single().contains("已隔离"), "日志要表明是隔离而非静默吞：${h.logs.single()}")
    }

    @Test
    fun `unregister 之后不再派发且 hasHandlers 复位`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        val registration = registry.register(
            WiringHarness.EVENT_KEY,
            EventBucket.NORMAL,
            "mod.a",
            h.handler("n"),
        )
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals(1, h.calls.size)
        registration.unregister()
        assertFalse(registry.hasHandlers(WiringHarness.EVENT_KEY))
        assertTrue(h.runEvent())
        assertEquals(1, h.calls.size, "撤销后不得再唤起")
    }

    @Test
    fun `安装卸载按注册表实例同一性判本会话`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val mine = h.handlers()
        val other = h.handlers()
        mine.register(WiringHarness.EVENT_KEY, EventBucket.NORMAL, "mod.a", h.handler("n"))
        h.attach(mine)
        // 拿别人的注册表来解绑必须是 no-op：否则后一会话会把本会话的接线拆掉。
        h.detach(other)
        assertTrue(h.runEvent())
        assertEquals(1, h.calls.size, "实例不同 ⇒ 解绑 no-op")
        h.detach(mine)
        assertTrue(h.runEvent())
        assertEquals(1, h.calls.size, "解绑本会话后回到完全旁路")
    }

    @Test
    fun `事件名大小写与空白归一`() {
        val h = WiringHarness(mixedMarkers, mixedMetas())
        val registry = h.handlers()
        registry.register("  evt  ", EventBucket.NORMAL, "mod.a", h.handler("n"))
        assertTrue(registry.hasHandlers("EVT"))
        h.attach(registry)
        assertTrue(h.runEvent())
        assertEquals(1, h.calls.size, "注册侧归一大写 + trim，派发侧事件名大写")
    }
}

/**
 * [EventWiringTest] 的真运行时夹具：真 `VariableStore` + [Assembler] 汇编的最小 Program +
 * 生产 LSC 派生的四桶表 + 真 [ProcessRuntime]。
 *
 * 夹具**不预装接线**，每个用例自己决定要不要 [attach] —— 这样「未装 = 完全旁路」可被直接断言。
 */
private class WiringHarness(
    markers: List<String>,
    metas: List<FuncMeta>,
    singleAt: Set<Int> = emptySet(),
) {

    val store = VariableStore()

    /** [ModEventWiring] 的 logSink：用来断言处理器异常的隔离取证面。 */
    val logs = ArrayList<String>()

    /** 处理器调用序。每条 = `标签@包名|调用瞬间剧本已打印出的内容`。 */
    val calls = ArrayList<String>()

    private val program: Program = Assembler.assemble(sourceOf(markers, singleAt))

    private val registry: EventRegistry = EventRegistry.of(
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
        eventRegistry = registry,
    )

    /** 剧本打印面：ERB 成员链是否真跑了、跑在什么位置。 */
    val output: String get() = runtime.output.toString()

    fun handlers(): EventDispatchRegistry = EventDispatchRegistry()

    fun attach(registry: EventDispatchRegistry) = ModEventWiring.install(store, registry, logs::add)

    fun detach(registry: EventDispatchRegistry) = ModEventWiring.uninstall(store, registry)

    fun runEvent(key: String = EVENT_KEY): Boolean = runtime.executeEvent(key)

    /** 记一笔调用（含当时的剧本面快照）并返回指定结果。 */
    fun handler(label: String, result: EventResult = EventResult.CONTINUE): EventHandler =
        EventHandler { context: EventContext ->
            calls += "$label@${context.packageId}|${output.trim().replace('\n', ',')}"
            result
        }

    companion object {

        const val EVENT_KEY: String = "EVT"

        private const val BASE_OWNER = "__base__"

        private fun sourceOf(markers: List<String>, singleAt: Set<Int>): String = buildString {
            append(".func main slots=1\n")
            append("  _HALT\n")
            for ((i, marker) in markers.withIndex()) {
                append(".func ev${i + 1} slots=2\n")
                append("  _PRINT_LITERAL \"$marker\", 1\n")
                if (i in singleAt) {
                    append("  _SET_INT R0, 1\n")
                    append("  _RET R0\n")
                } else {
                    append("  _RET\n")
                }
            }
        }
    }
}
