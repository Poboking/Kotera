package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase as CoreHookPhase
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.TimingSample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 逐 mod 耗时归因闸（§18.5 诊断面扩展；RT2 P1 耗时归因）。
 *
 * 钉住五件事：
 * 1. 账本本身的聚合口径（次数 / 总耗时 / 最大 / 均值 / 最近一次、降序）与写入口校验
 *    （空 / 超长 scope、负耗时 ⇒ `IllegalArgumentException`，不留半笔账）；
 * 2. 四个测量点真在记账：lifecycle（含 before-onLoad 就位）、tick、hook、event；
 * 3. 作者侧只看得到本包读数，看不到别的 mod；
 * 4. 未注入账本时**显式 fail-closed**（不是静默空表）；
 * 5. 异常路径照样落账（抛错的处理器一样耗时间）。
 */
class ModTimingLedgerTest {

    // ---------------------------------------------------------------- 账本本体

    @Test
    fun `账本聚合次数总耗时最大均值最近一次并按总耗时降序`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgA", "tick", 100)
        ledger.record("pkgA", "tick", 300)
        ledger.record("pkgB", "lifecycle.onLoad", 50)

        val all = ledger.snapshot()
        assertEquals(listOf("tick", "lifecycle.onLoad"), all.map { it.scope }, "总耗时大的在前")

        val tick = all.first()
        assertEquals(2L, tick.count)
        assertEquals(400L, tick.totalNanos)
        assertEquals(300L, tick.maxNanos)
        assertEquals(200L, tick.averageNanos)
        assertEquals(300L, tick.lastNanos)

        val load = all.last()
        assertEquals(1L, load.count)
        assertEquals(50L, load.totalNanos)
        assertEquals(50L, load.maxNanos)
        assertEquals(50L, load.averageNanos)
    }

    @Test
    fun `写入口非法参数一律IAE且不留半笔账`() {
        val ledger = ModTimingLedger()
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", "tick", -500) }
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", "", 1) }
        assertThrows<IllegalArgumentException> { ledger.record("pkgA", "   ", 1) }
        assertThrows<IllegalArgumentException> {
            ledger.record("pkgA", "x".repeat(ModTimingLedger.MAX_SCOPE_LENGTH + 1), 1)
        }
        assertTrue(ledger.snapshot().isEmpty(), "非法写入不得留下半笔账")
    }

    @Test
    fun `measureValue 时钟回拨按零记且不掩盖block异常`() {
        // 每对读数 = (起点, 终点)：第一对正常取 "ok"，第二对回拨 -100 后抛错。
        val instants = listOf(1_000L, 1_250L, 2_000L, 1_900L)
        var index = 0
        val ledger = ModTimingLedger(clock = { instants[index++] })

        assertEquals("ok", ledger.measureValue("pkgA", "tick") { "ok" })
        val ex = assertThrows<IllegalStateException> {
            ledger.measureValue("pkgA", "tick") { error("故意抛") }
        }
        assertEquals("故意抛", ex.message, "回拨不得盖掉 block 自己的异常")

        val sample = ledger.snapshot().single()
        assertEquals(2L, sample.count)
        assertEquals(250L, sample.totalNanos, "回拨那次按 0 记")
        assertEquals(250L, sample.maxNanos)
        assertEquals(0L, sample.lastNanos)
    }

    @Test
    fun `按包过滤与按包清零互不干扰`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgA", "tick", 10)
        ledger.record("pkgA", "hook.F.PREFIX", 20)
        ledger.record("pkgB", "tick", 30)

        assertEquals(listOf("hook.F.PREFIX", "tick"), ledger.forPackage("pkgA").map { it.scope })
        assertEquals(1, ledger.forPackage("pkgB").size)
        assertTrue(ledger.forPackage("pkgMissing").isEmpty())

        ledger.resetPackage("pkgA")
        assertTrue(ledger.forPackage("pkgA").isEmpty())
        assertEquals(1, ledger.forPackage("pkgB").size, "只清自己那一包")
    }

    @Test
    fun `measureValue 用注入时钟记账且异常路径照记后原样抛`() {
        var now = 1_000L
        val ledger = ModTimingLedger(clock = { now })

        val value = ledger.measureValue("pkgA", "lifecycle.onLoad") {
            now += 250
            "ok"
        }
        assertEquals("ok", value)

        val ex = assertThrows<IllegalStateException> {
            ledger.measureValue("pkgA", "lifecycle.onLoad") {
                now += 40
                error("故意抛")
            }
        }
        assertEquals("故意抛", ex.message)

        val sample = ledger.forPackage("pkgA").single()
        assertEquals(2L, sample.count, "抛错那一次也算一次")
        assertEquals(290L, sample.totalNanos)
        assertEquals(250L, sample.maxNanos)
        assertEquals(40L, sample.lastNanos)
    }

    // ---------------------------------------------------------------- 装配侧测量点

    @Test
    fun `宿主生命周期四段逐段入账且 onLoad 之前账本就位`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modL")
        val seenInOnLoad = ArrayList<String>()
        host.install(
            listOf(
                "modL" to object : EraMod {
                    override fun onLoad(context: ModContext) {
                        // 账本必须在 onLoad 之前注入，否则作者在初始化里看不到自己的读数。
                        seenInOnLoad += context.timing.samples().map { it.scope }
                    }

                    override fun onEnable() = Unit

                    override fun onDisable() = Unit

                    override fun onUnload() = Unit
                },
            ),
        )
        host.shutdown()

        val scopes = ledger.forPackage("modL").map { it.scope }
        assertEquals(
            listOf("lifecycle.onLoad", "lifecycle.onEnable", "lifecycle.onDisable", "lifecycle.onUnload")
                .sorted(),
            scopes.sorted(),
        )
        assertTrue(seenInOnLoad.isEmpty(), "onLoad 里账本还没记到 onLoad 自己（当次正在跑）")
        assertTrue(
            ledger.forPackage("modL").all { it.count == 1L },
            "each lifecycle segment exactly once: ${ledger.forPackage("modL")}",
        )
    }

    @Test
    fun `相位通知也入账且只达已启用 mod`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modOk", "modBad")
        host.install(
            listOf(
                "modOk" to traceMod(),
                "modBad" to object : EraMod {
                    override fun onLoad(context: ModContext) = Unit

                    override fun onEnable() {
                        error("故意失败")
                    }

                    override fun onDisable() = Unit

                    override fun onUnload() = Unit
                },
            ),
        )
        host.notifyNewGame()
        host.notifySaveLoaded()

        val okScopes = ledger.forPackage("modOk").map { it.scope }
        assertTrue("lifecycle.onNewGame" in okScopes, "已启用 mod 的新局通知必须入账：$okScopes")
        assertTrue("lifecycle.onSaveLoaded" in okScopes, "已启用 mod 的读档通知必须入账：$okScopes")
        assertTrue(
            ledger.forPackage("modBad").none { it.scope == "lifecycle.onNewGame" },
            "onEnable 失败的 mod 不是 active，不该收相位通知更不该有读数",
        )
    }

    @Test
    fun `作者侧只看得到本包读数看不到别的 mod`() {
        val ledger = ModTimingLedger()
        val host = newHost(mutableListOf(), ledger, "modA", "modB")
        host.install(listOf("modA" to traceMod(), "modB" to traceMod()))

        val samplesA = host.contextOf("modA")!!.timing.samples()
        assertTrue(samplesA.isNotEmpty())
        assertTrue(samplesA.all { it.scope.startsWith("lifecycle.") })
        // 两个 mod 各记各的：每人 onLoad 那条 count 都是 1，而不是合并成 2。
        assertEquals(1L, samplesA.single { it.scope == "lifecycle.onLoad" }.count)
        assertEquals(
            1L,
            host.contextOf("modB")!!.timing.samples().single { it.scope == "lifecycle.onLoad" }.count,
        )
    }

    @Test
    fun `未注入账本时作者侧显式抛而不是静默空表`() {
        val host = newHost(mutableListOf(), null, "modNoTiming")
        host.install(listOf("modNoTiming" to traceMod()))
        val ex = assertThrows<UnsupportedOperationException> {
            host.contextOf("modNoTiming")!!.timing.samples()
        }
        assertTrue(ex.message!!.contains("耗时归因面未装配"), "must name the reason: ${ex.message}")
        assertTrue(ex.message!!.contains("modNoTiming"), "must name the package: ${ex.message}")
    }

    @Test
    fun `hook 处理器逐次入账为 hook 键 相位`() {
        val ledger = ModTimingLedger()
        val pipeline = HookPipeline.of(
            listOf(DeclaredHook("modH", HookDeclaration("F", CoreHookPhase.PREFIX))),
        )
        pipeline.timing = ledger
        pipeline.bind("F", HookPhase.PREFIX, "modH", HookHandler { HookResult.Continue })

        repeat(3) {
            pipeline.dispatch("F", emptyArray()) { null }
        }

        val sample = ledger.forPackage("modH").single()
        assertEquals("hook.F.PREFIX", sample.scope)
        assertEquals(3L, sample.count)
    }

    @Test
    fun `事件处理器入账为 event 事件 桶且异常照记`() {
        val store = VariableStore()
        val ledger = ModTimingLedger()
        val registry = EventDispatchRegistry()
        ModEventWiring.install(store, registry, { }, ledger)

        registry.register("EVT", EventBucket.NORMAL, "modE") { EventResult.CONTINUE }
        registry.register("EVT", EventBucket.NORMAL, "modBoom") { error("故意炸") }

        ModEventWiring.runBucket(store, "EVT", EventBucket.NORMAL.ordinal)

        val normal = ledger.forPackage("modE").single()
        assertEquals("event.EVT.NORMAL", normal.scope)
        assertEquals(1L, normal.count)
        assertTrue(
            ledger.forPackage("modBoom").any { it.scope == "event.EVT.NORMAL" && it.count == 1L },
            "抛错的事件处理器也必须落账（耗时不为零）",
        )
    }

    @Test
    fun `节拍入账为 tick 且未接账本时不产生读数`() {
        val ledger = ModTimingLedger()
        val registry = PhaseRegistry.of(emptyList())
        val scheduler = JobScheduler.of(emptyList())
        val measured = GameTickDriver(registry, scheduler, null, ledger, "modT")
        measured.tick(GameTime(1, "DAY_END"))

        val tick = ledger.forPackage("modT").single()
        assertEquals("tick", tick.scope)
        assertEquals(1L, tick.count)

        val quiet = GameTickDriver(registry, scheduler)
        quiet.tick(GameTime(1, "DAY_END"))
        assertEquals(1, ledger.snapshot().size, "没接账本的 driver 不得凭空多出读数")
    }

    @Test
    fun `关态零读数`() {
        val ledger = ModTimingLedger()
        assertTrue(ledger.snapshot().isEmpty())
        assertEquals(emptyList<TimingSample>(), ledger.forPackage("any"))
        assertTrue(ledger.forPackage("any").isEmpty())
    }

    // ---------------------------------------------------------------- 夹具

    private fun traceMod(): EraMod = object : EraMod {
        override fun onLoad(context: ModContext) {
            context.timing.samples()
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(logs: MutableList<String>, ledger: ModTimingLedger?, vararg packages: String): ModRuntimeHost {
        val host = ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )
        host.timing = ledger
        return host
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
                ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }
}
