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
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ModRuntimeHost 生命周期闸（RT2 波 C 车 C1）：四段时序、双 mod 失败隔离、onDisable 撤销、
 * 失败 mod 幂等、EventApi 拒接、无静态可变全局、撤销容错。
 */
class ModRuntimeHostTest {

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
                        jobTemplates = listOf(JobTemplateDecl("once")),
                    ),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        val actions = mutableListOf<() -> Unit>()

        override fun submit(action: () -> Unit) {
            actions += action
            action()
        }
    }

    private class TraceMod(
        private val trace: MutableList<String>,
        private val label: String,
        private val failOnEnable: Boolean = false,
        private val disableThrows: Boolean = false,
        private val failOnNewGame: Boolean = false,
        private val onLoadHook: (ModContext) -> Unit = {},
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            trace += "$label:onLoad(${context.packageId})"
            onLoadHook(context)
        }

        override fun onEnable() {
            trace += "$label:onEnable"
            if (failOnEnable) throw IllegalStateException("$label 故意在 onEnable 抛异常")
        }

        override fun onDisable() {
            trace += "$label:onDisable"
            if (disableThrows) throw IllegalStateException("$label 故意在 onDisable 抛异常")
        }

        override fun onUnload() {
            trace += "$label:onUnload"
        }

        override fun onNewGame() {
            trace += "$label:onNewGame"
            if (failOnNewGame) error("$label 故意在 onNewGame 抛异常")
        }

        override fun onSaveLoaded() {
            trace += "$label:onSaveLoaded"
        }

        override fun onTurnBoundary() {
            trace += "$label:onTurnBoundary"
        }
    }

    /**
     * 回合边界故意抛错的 mod：验证宿主扇出「per-mod 隔离、不二次抛、不 revoke、不混 trace」。
     * 生命周期空实现用自增计数占位（避免 detekt `EmptyFunctionBlock` 新增违例）。
     */
    private class TurnBoundaryBoomMod(private val trace: MutableList<String>) : EraMod {
        var loads: Int = 0
            private set

        override fun onLoad(context: ModContext) {
            loads++
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

        override fun onTurnBoundary() {
            trace += "modB:onTurnBoundary"
            error("modB 故意在 onTurnBoundary 抛异常")
        }
    }

    private fun newHost(logs: MutableList<String>, vararg packages: String): ModRuntimeHost =
        ModRuntimeHost(
            snapshot = newSnapshot(*packages),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = FakeRuntime(),
        )

    private fun readSource(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core/mod/$name"),
            File("core-vm/src/main/kotlin/com/eratw/port/core/mod/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("定位不到源码 $name（防假绿）：cwd=${System.getProperty("user.dir")}")
        return file.readText()
    }

    private fun codeOnly(text: String): String =
        text.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")

    @Test
    fun `四段生命周期时序且 onLoad 收到一致 packageId`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modA")
        host.runAll(listOf("modA" to TraceMod(trace, "modA")))
        assertEquals(
            listOf("modA:onLoad(modA)", "modA:onEnable", "modA:onDisable", "modA:onUnload"),
            trace,
        )
    }

    @Test
    fun `双 mod 隔离_onEnable 失败只判该 mod 且只回滚其注册项`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB")
        val trace = mutableListOf<String>()
        var aHits = 0
        var bHits = 0
        var aJobHits = 0
        var bJobHits = 0
        val a = TraceMod(trace, "modA", failOnEnable = true) { ctx ->
            ctx.hooks.register("COM300", HookPhase.PREFIX) {
                aHits++
                HookResult.Continue
            }
            ctx.jobs.bind("j1") { aJobHits++ }
        }
        val b = TraceMod(trace, "modB") { ctx ->
            ctx.hooks.register("COM300", HookPhase.PREFIX) {
                bHits++
                HookResult.Continue
            }
            ctx.jobs.bind("j1") { bJobHits++ }
        }
        val states = host.install(listOf("modA" to a, "modB" to b))
        assertEquals(2, states.size)
        assertFalse(states[0].active)
        assertNotNull(states[0].failure)
        assertTrue(states[1].active)
        assertNull(states[1].failure)

        val ctxA = host.contextOf("modA")!! as ModContextImpl
        val ctxB = host.contextOf("modB")!! as ModContextImpl
        // 全链共享一份 HookPipeline（priority 全局序的前提，见 ModRuntimeHost.hookPipeline）：
        // 任一 context 的 dispatch 都走同一张表，失败 modA 的绑定已被 revoke 掉。
        ctxA.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(0, aHits)
        assertEquals(1, bHits)
        assertTrue(ctxA.hookPipeline === ctxB.hookPipeline, "两个 context 必须指向同一份 pipeline")
        assertTrue(ctxA.jobScheduler === ctxB.jobScheduler, "两个 context 必须指向同一份全链任务调度器")
        assertTrue(ctxA.jobScheduler === host.jobScheduler, "作者绑定与宿主派发必须共用任务态")
        val jobReport = host.jobScheduler.runDue(GameTime(0, "DAY_END"))
        assertEquals(1, jobReport.fired.size)
        assertEquals(0, aJobHits)
        assertEquals(1, bJobHits)
        ctxB.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(2, bHits)

        assertTrue(logs.any { it.contains("[modA][ERROR]") && it.contains("onLoad 或 onEnable 失败") })

        host.shutdown()
        assertEquals(
            listOf(
                "modA:onLoad(modA)", "modA:onEnable", "modB:onLoad(modB)", "modB:onEnable",
                "modA:onDisable", "modA:onUnload", "modB:onDisable", "modB:onUnload",
            ),
            trace,
        )
    }

    @Test
    fun `构造段失败_快照缺包只判该 mod 且第一项不受影响且不整链炸`() {
        val logs = mutableListOf<String>()
        // snapshot 只声明 modA；modB 不在声明快照 ⇒ ModContextImpl 构造期 require 抛
        // （W3-FAILCLOSE 前该异常直穿 install 整链，与 ModCarrierRegistry fail-closed 承诺矛盾）。
        val host = newHost(logs, "modA")
        val trace = mutableListOf<String>()
        var aHits = 0
        val a = TraceMod(trace, "modA") { ctx ->
            ctx.hooks.register("COM300", HookPhase.PREFIX) {
                aHits++
                HookResult.Continue
            }
        }
        val states = host.install(listOf("modA" to a, "modB" to TraceMod(trace, "modB")))
        assertEquals(2, states.size)
        assertEquals("modA", states[0].packageId)
        assertTrue(states[0].active)
        assertNull(states[0].failure)
        assertEquals("modB", states[1].packageId)
        assertFalse(states[1].active)
        assertNotNull(states[1].failure)
        assertTrue(states[1].failure!!.message!!.contains("声明快照不含 packageId=modB"))
        // 失败原因进日志面（与 onLoad/onEnable 失败同格式），且 install 不抛、正常返回。
        assertTrue(logs.any { it.contains("[modB][ERROR]") && it.contains("装配失败（构造段）") })
        // 构造失败 mod 未进生命周期：不登记 entries，shutdown 不走它的 onDisable/onUnload。
        assertNull(host.contextOf("modB"))
        // storage 回退：activate 已按既有对称操作退回非激活（禁用不等于删除，空段留存）。
        assertFalse(host.storage.isActive("modB"))
        // 第一项不受影响：modA 仍启用、注册仍命中。
        val ctxA = host.contextOf("modA")!! as ModContextImpl
        ctxA.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(1, aHits)
        host.shutdown()
        assertEquals(
            listOf("modA:onLoad(modA)", "modA:onEnable", "modA:onDisable", "modA:onUnload"),
            trace,
        )
    }

    @Test
    fun `重复装载同 packageId 仍硬失败 abort 不降级`() {
        val host = newHost(mutableListOf(), "modA")
        val trace = mutableListOf<String>()
        val ex = assertThrows<IllegalArgumentException> {
            host.install(
                listOf("modA" to TraceMod(trace, "modA"), "modA" to TraceMod(trace, "modA-二次")),
            )
        }
        assertTrue(ex.message!!.contains("packageId 重复装载"))
        // registry 编程错误不走失败报告降级：第一项已完整装载并保持在 entries，不受第二项影响。
        assertNotNull(host.contextOf("modA"))
        assertEquals(
            listOf("modA:onLoad(modA)", "modA:onEnable"),
            trace,
        )
    }

    @Test
    fun `失败 mod 的 disable 与 unload 异常被隔离且 shutdown 幂等`() {
        val logs = mutableListOf<String>()
        val host = newHost(logs, "modA")
        val trace = mutableListOf<String>()
        val mod = TraceMod(trace, "modA", failOnEnable = true, disableThrows = true)
        host.install(listOf("modA" to mod))
        val first = host.shutdown()
        assertFalse(first[0].active)
        assertNotNull(first[0].failure)
        assertTrue(logs.any { it.contains("onDisable 异常已隔离") })
        val traceSizeAfterFirst = trace.size
        val second = host.shutdown()
        assertEquals(traceSizeAfterFirst, trace.size)
        assertEquals(first, second)
    }

    @Test
    fun `失败装载回滚本包动态任务态且不吞其它包实例`() {
        val host = newHost(mutableListOf(), "modA", "modB")
        val trace = mutableListOf<String>()
        var bJobHits = 0
        val a = TraceMod(trace, "modA", failOnEnable = true) { ctx ->
            ctx.jobs.scheduleOnce("once", 3L)
        }
        val b = TraceMod(trace, "modB") { ctx ->
            ctx.jobs.scheduleOnce("once", 3L)
            ctx.jobs.bindTemplate("once") { bJobHits++ }
        }

        val states = host.install(listOf("modA" to a, "modB" to b))

        assertFalse(states[0].active)
        assertTrue(states[1].active)
        assertEquals(listOf("modB::once#0"), host.jobScheduler.instances().map { it.instanceKey })
        assertFalse(host.jobScheduler.snapshot().nextSeqByTemplate.containsKey("modA::once"))
        val report = host.jobScheduler.runDue(GameTime(3, "DAY_END"))
        assertEquals(1, report.firedInstances.size)
        assertEquals(1, bJobHits)
        host.shutdown()
    }

    @Test
    fun `host 批量撤销后 hook phase job interaction ui 各引擎侧都不再命中`() {
        val host = newHost(mutableListOf(), "modA")
        var hookHits = 0
        var phaseHits = 0
        var jobHits = 0
        var interactionHits = 0
        var uiHits = 0
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.hooks.register("COM300", HookPhase.PREFIX) {
                    hookHits++
                    HookResult.Continue
                }
                context.phases.subscribe("DAY_END") { phaseHits++ }
                context.jobs.bind("j1") { jobHits++ }
                context.interactions.registerHook(InteractionPhase.PRE) {
                    interactionHits++
                    InteractionResult.CONTINUE
                }
                context.ui.registerInteraction("BTN") { uiHits++ }
            }

            override fun onEnable() {}
            override fun onDisable() {}
            override fun onUnload() {}
        }
        host.runAll(
            listOf("modA" to mod),
            duringRun = {
                val ctx = host.contextOf("modA")!! as ModContextImpl
                ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
                ctx.phaseRegistry.dispatch(GameTime(0, "DAY_END"))
                ctx.jobScheduler.runDue(GameTime(0, "DAY_END"))
                ctx.interactionPipeline.run(
                    InteractionContext("I", EntityId(1L), emptyList()),
                ) { ModValue.of(1L) }
                assertTrue(ctx.uiClick("BTN"))
                assertEquals(1, hookHits)
                assertEquals(1, phaseHits)
                assertEquals(1, jobHits)
                assertEquals(1, interactionHits)
                assertEquals(1, uiHits)
            },
        )
        val ctx = host.contextOf("modA")!! as ModContextImpl
        ctx.hookPipeline.dispatch("COM300", emptyArray()) { ModValue.of(1L) }
        assertEquals(0, ctx.phaseRegistry.dispatch(GameTime(0, "DAY_END")).fired)
        assertTrue(ctx.jobScheduler.runDue(GameTime(1, "DAY_END")).fired.isEmpty())
        ctx.interactionPipeline.run(
            InteractionContext("I", EntityId(1L), emptyList()),
        ) { ModValue.of(1L) }
        assertFalse(ctx.uiClick("BTN"))
        assertEquals(1, hookHits)
        assertEquals(1, phaseHits)
        assertEquals(1, jobHits)
        assertEquals(1, interactionHits)
        assertEquals(1, uiHits)
    }

    @Test
    fun `单个 unregister 抛错不中断其余撤销且被记录`() {
        val host = newHost(mutableListOf(), "modA")
        val errors = mutableListOf<Throwable>()
        var normalUnregistered = false
        val throwing = object : Registration {
            override fun unregister() = throw IllegalStateException("故意抛错")
        }
        val normal = object : Registration {
            override fun unregister() {
                normalUnregistered = true
            }
        }
        revokeRegistrations(listOf(throwing, normal)) { errors += it }
        assertTrue(normalUnregistered)
        assertEquals(1, errors.size)
        assertEquals("故意抛错", errors[0].message)
    }

    @Test
    fun `EventApi 装载后真可注册且进会话共享注册表`() {
        val host = newHost(mutableListOf(), "modA")
        host.install(listOf("modA" to TraceMod(mutableListOf(), "modA")))
        val ctx = host.contextOf("modA")!!
        val registration = ctx.events.register("EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
        assertTrue(host.eventHandlers.hasHandlers("EVENT"), "注册必须落进会话共享注册表（否则派发侧读不到）")
        registration.unregister()
        assertFalse(host.eventHandlers.hasHandlers("EVENT"))
        // ONLY 桶在运行期硬拒：它终止整条事件链，运行期插入会让 base 与其它 Mod 的成员全失效。
        val ex = assertThrows<IllegalArgumentException> {
            ctx.events.register("EVENT", EventBucket.ONLY) { EventResult.CONTINUE }
        }
        assertTrue(ex.message!!.contains("ONLY"))
    }

    @Test
    fun `事件注册经 track 收集_关闭与失败回滚都会真撤销`() {
        val host = newHost(mutableListOf(), "modE")
        host.install(
            listOf(
                "modE" to object : EraMod {
                    override fun onLoad(context: ModContext) {
                        context.events.register("EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
                    }

                    override fun onEnable() = Unit

                    override fun onDisable() = Unit

                    override fun onUnload() = Unit
                },
            ),
        )
        assertTrue(host.eventHandlers.hasHandlers("EVENT"), "onLoad 里注册的处理器必须落进会话共享表")
        host.shutdown()
        assertFalse(
            host.eventHandlers.hasHandlers("EVENT"),
            "shutdown 必须撤销本 mod 的事件注册（绕过 track = onDisable 之后处理器仍在命中）",
        )

        // 装配失败回滚同口径：onEnable 抛错时 onLoad 期间注册的处理器必须被撤销。
        val failing = newHost(mutableListOf(), "modF")
        failing.install(
            listOf(
                "modF" to object : EraMod {
                    override fun onLoad(context: ModContext) {
                        context.events.register("EVENT2", EventBucket.NORMAL) { EventResult.CONTINUE }
                    }

                    override fun onEnable() {
                        error("故意失败")
                    }

                    override fun onDisable() = Unit

                    override fun onUnload() = Unit
                },
            ),
        )
        assertFalse(
            failing.eventHandlers.hasHandlers("EVENT2"),
            "装配失败回滚必须撤销 onLoad 期间注册的事件处理器",
        )
    }

    @Test
    fun `两 host 两 context 的日志 storage ui 互不串扰`() {
        val logsA = mutableListOf<String>()
        val logsB = mutableListOf<String>()
        val hostA = newHost(logsA, "modA")
        val hostB = newHost(logsB, "modB")
        hostA.install(listOf("modA" to object : EraMod {
            override fun onLoad(context: ModContext) {
                context.logger.info("fromA")
                context.storage.put("k", "A")
                context.ui.registerInteraction("BTN") { }
            }

            override fun onEnable() {}
            override fun onDisable() {}
            override fun onUnload() {}
        }))
        hostB.install(listOf("modB" to object : EraMod {
            override fun onLoad(context: ModContext) {
                context.logger.info("fromB")
                context.storage.put("k", "B")
                context.ui.registerInteraction("BTN") { }
            }

            override fun onEnable() {}
            override fun onDisable() {}
            override fun onUnload() {}
        }))
        val a = hostA.contextOf("modA")!! as ModContextImpl
        val b = hostB.contextOf("modB")!! as ModContextImpl
        assertEquals("A", a.storage.get("k"))
        assertEquals("B", b.storage.get("k"))
        assertTrue(logsA.any { it.contains("[modA][INFO] fromA") })
        assertTrue(logsB.any { it.contains("[modB][INFO] fromB") })
        assertFalse(logsA.any { it.contains("fromB") })
        assertFalse(logsB.any { it.contains("fromA") })
        assertEquals(setOf("BTN"), a.uiRegisteredKeys())
        assertEquals(setOf("BTN"), b.uiRegisteredKeys())
        hostA.shutdown()
        assertEquals(emptySet<String>(), a.uiRegisteredKeys())
        assertEquals(setOf("BTN"), b.uiRegisteredKeys())
    }

    @Test
    fun `新局与读档通知只达已启用 mod 且异常被隔离`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB", "modC")
        host.install(
            listOf(
                "modA" to TraceMod(trace, "modA"),
                // modB 的通知故意抛错：必须只影响它自己，且不二次抛。
                "modB" to TraceMod(trace, "modB", failOnNewGame = true),
                "modC" to TraceMod(trace, "modC"),
            ),
        )
        trace.clear()

        host.notifyNewGame()
        assertEquals(listOf("modA:onNewGame", "modB:onNewGame", "modC:onNewGame"), trace)
        assertTrue(
            logs.any { it.contains("onNewGame 异常已隔离") && it.contains("modB") },
            "modB 的失败必须留痕且只留它自己：$logs",
        )

        trace.clear()
        host.notifySaveLoaded()
        assertEquals(listOf("modA:onSaveLoaded", "modB:onSaveLoaded", "modC:onSaveLoaded"), trace)
        host.shutdown()
    }

    @Test
    fun `安装失败与已关闭的 mod 不再收相位通知`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modOk", "modBad")
        host.install(
            listOf(
                "modOk" to TraceMod(trace, "modOk"),
                "modBad" to TraceMod(trace, "modBad", failOnEnable = true),
            ),
        )
        trace.clear()

        host.notifyNewGame()
        host.notifySaveLoaded()
        assertEquals(listOf("modOk:onNewGame", "modOk:onSaveLoaded"), trace)

        // 已彻底关闭的 mod 也不再收：shutdown 之后连 enabled 都不再兑现通知。
        host.shutdown()
        trace.clear()
        host.notifyNewGame()
        assertEquals(emptyList<String>(), trace)
    }

    @Test
    fun `相位通知不混进安装与卸载的精确 trace`() {
        val trace = mutableListOf<String>()
        val host = newHost(mutableListOf(), "modA")
        host.runAll(listOf("modA" to TraceMod(trace, "modA")))
        assertEquals(
            listOf("modA:onLoad(modA)", "modA:onEnable", "modA:onDisable", "modA:onUnload"),
            trace,
        )
    }

    @Test
    fun `回合边界通知只达已启用 mod 且入账固定 scope`() {
        val logs = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = newHost(logs, "modA", "modB")
        host.install(
            listOf(
                "modA" to TraceMod(trace, "modA"),
                // modB 的回合通知故意抛错：必须只影响它自己，且不二次抛。
                "modB" to TurnBoundaryBoomMod(trace),
            ),
        )
        val ledger = ModTimingLedger()
        host.timing = ledger
        trace.clear()

        host.notifyTurnBoundary()
        assertEquals(listOf("modA:onTurnBoundary", "modB:onTurnBoundary"), trace)
        assertTrue(
            ledger.forPackage("modA").any { it.scope == "lifecycle.onTurnBoundary" },
            "回合边界通知必须入账固定 scope lifecycle.onTurnBoundary：${ledger.forPackage("modA")}",
        )
        assertTrue(
            logs.any { it.contains("onTurnBoundary 异常已隔离") && it.contains("modB") },
            "modB 的失败必须留痕且只留它自己：$logs",
        )

        // 收口之后不再兑现回合通知（幂等旁路）。
        host.shutdown()
        trace.clear()
        host.notifyTurnBoundary()
        assertEquals(emptyList<String>(), trace)
    }

    @Test
    fun `随机面在 onLoad 之前就位_同种子可复现_换种子重放`() {
        // mod 在 onLoad 里就要能抽数（初始化随机布局是常见需求）⇒ adopt 必须早于生命周期。
        fun drawsAfterAdopt(seed: Long?): List<Int> {
            val host = newHost(mutableListOf(), "modR")
            if (seed != null) host.adoptRandomSeed(seed)
            var drawn: List<Int> = emptyList()
            val mod = object : EraMod {
                override fun onLoad(context: ModContext) {
                    drawn = List(8) { context.rng.nextInt(1000) }
                }

                override fun onEnable() = Unit

                override fun onDisable() = Unit

                override fun onUnload() = Unit
            }
            host.install(listOf("modR" to mod))
            return drawn
        }

        val first = drawsAfterAdopt(null)
        assertEquals(8, first.size, "onLoad 阶段的 context.rng 必须可用（未装配会抛 fail-closed）")
        assertEquals(first, drawsAfterAdopt(null), "同一决策种子下两次装配必须抽到同一串")
        assertNotEquals(first, drawsAfterAdopt(20260226L), "换决策种子必须换序列")
        assertEquals(
            drawsAfterAdopt(20260226L),
            drawsAfterAdopt(20260226L),
            "同一档内种子下必须可复现（读档重放口径）",
        )
    }

    @Test
    fun `源码形状不含静态可变全局与 Android API`() {
        for (name in listOf("ModContextImpl.kt", "ModRuntimeHost.kt")) {
            val code = codeOnly(readSource(name))
            assertFalse(code.contains("companion object"), "$name 不该有伴生对象")
            assertFalse(
                Regex("""^object\s+\w+""", RegexOption.MULTILINE).containsMatchIn(code),
                "$name 不该有具名单例",
            )
            assertFalse(
                Regex("""^var\s""", RegexOption.MULTILINE).containsMatchIn(code),
                "$name 不该有顶层可变变量",
            )
            assertFalse(code.contains("kotlin.random"), "$name 禁 kotlin.random")
            assertFalse(code.contains("android."), "$name 禁 Android API")
        }
    }
}
