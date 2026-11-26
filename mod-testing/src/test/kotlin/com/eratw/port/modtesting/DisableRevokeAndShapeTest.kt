package com.eratw.port.modtesting

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.bind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3 台架形状保真（2026-11-26）：消两世界行为不一致的 MOD-016/017/018 同族。
 *
 * - **代撤对齐**：`TestModSession.disable()` 与生产同序（先批量代撤本包七面注册项，再
 *   onDisable）—— 旧形态「只裸调 onDisable」下作者不自持返回值时，台架禁用后处理器仍命中、
 *   生产却先撤销（MOD-017/018 实锤的两世界差异）；
 * - **形状保真**：`TestContext.adoptProductionShape` 一次灌入生产事件键集 + hook 目标键集，
 *   两面存在性闸与生产同开 —— 模板自造事件键在台架当场红（MOD-016 的「单测全绿、生产炸」）。
 */
class DisableRevokeAndShapeTest {

    private class TrackingMod : EraMod {
        lateinit var context: ModContext
        var onDisableSeen = false
        var selfHeld: Registration? = null
        var calls = 0

        override fun onLoad(context: ModContext) {
            this.context = context
        }

        override fun onEnable() {
            context.events.register(EVENT_KEY, EventBucket.NORMAL) { calls += 1; EventResult.CONTINUE }
            context.hooks.register(TestSchema.PING.name, HookPhase.PREFIX) { calls += 100; HookResult.Continue }
            context.jobs.bind(TestSchema.TICK) { calls += 1000 }
            // 自持一份返回值：代撤后再 unregister 必须是幂等 no-op（不抛、不双撤）。
            selfHeld = context.events.register(EVENT_KEY, EventBucket.LATER) { calls += 10_000; EventResult.CONTINUE }
        }

        override fun onDisable() {
            onDisableSeen = true
        }

        override fun onUnload() = Unit

        companion object {
            const val EVENT_KEY = "TEST.EVENT"
        }
    }

    @Test
    fun `disable 先代撤后回调_禁用后处理器不再命中`() {
        val mod = TrackingMod()
        val session = runMod(mod, world = TestSchema.world(), packageId = TestSchema.PACKAGE_ID)
        // 禁用前：四笔注册全部命中（事件两桶 + hook + job）。
        assertEquals(
            listOf(EventResult.CONTINUE, EventResult.CONTINUE),
            session.context.dispatchEvent(TrackingMod.EVENT_KEY),
        )
        session.context.dispatchHook(TestSchema.PING.name)
        assertTrue(session.context.fireJob(TestSchema.TICK.name))
        assertTrue(mod.calls >= 1111, "禁用前四笔注册都命中：calls=${mod.calls}")

        val beforeDisable = mod.calls
        mod.calls = 0
        session.disable()
        assertTrue(mod.onDisableSeen, "onDisable 照常回调")

        // 禁用后：代撤生效，派发全部旁路（生产同口径）。
        assertEquals(emptyList(), session.context.dispatchEvent(TrackingMod.EVENT_KEY), "事件处理器已代撤")
        session.context.dispatchHook(TestSchema.PING.name, HookPhase.PREFIX)
        assertFalse(session.context.fireJob(TestSchema.TICK.name), "job 绑定已代撤")
        assertEquals(0, mod.calls, "禁用后零命中（代撤生效）：calls=${mod.calls}")
        assertTrue(beforeDisable > 0)
    }

    @Test
    fun `自持返回值在代撤后再撤销是幂等no-op`() {
        val mod = TrackingMod()
        val session = runMod(mod, world = TestSchema.world(), packageId = TestSchema.PACKAGE_ID)
        session.disable()
        // 不抛 = 幂等（代撤已清该注册；二次撤销同一 Registration 必须无害）。
        mod.selfHeld!!.unregister()
        assertEquals(emptyList(), session.context.dispatchEvent(TrackingMod.EVENT_KEY))
    }

    @Test
    fun `adoptProductionShape_自造键当场红_已知键放行`() {
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) = Unit
            override fun onEnable() = Unit
            override fun onDisable() = Unit
            override fun onUnload() = Unit
        }
        val session = runMod(mod, world = TestSchema.world(), packageId = TestSchema.PACKAGE_ID)

        // 缺省（未灌形状）= 两面都不校验（既有口径不变）：自造键放行。
        session.context.events.register("MY_EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }

        // 灌生产形状（真语料事件名 + hook 目标键并集口径）后，同闸全开。
        session.context.adoptProductionShape(
            eventKeys = setOf("EVENTTURNEND"),
            functionKeys = setOf("TEST.PING", "EVENTTURNEND"),
        )
        val evtErr = assertFailsWith<IllegalArgumentException> {
            session.context.events.register("MY_EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
        }
        assertTrue(evtErr.message!!.contains("MY_EVENT"), "自造事件键当场红：${evtErr.message}")
        // 已知事件键照常注册（注意：灌形状后 MY_EVENT 的既有注册不会被追溯撤销，闸只拦新注册）。
        session.context.events.register("EVENTTURNEND", EventBucket.NORMAL) { EventResult.CONTINUE }

        val hookErr = assertFailsWith<IllegalArgumentException> {
            session.context.hooks.register("TEST.PNG", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(hookErr.message!!.contains("TEST.PING"), "拼错 hook 键附最近候选：${hookErr.message}")
        session.context.hooks.register("TEST.PING", HookPhase.PREFIX) { HookResult.Continue }
    }

    @Test
    fun `台架 declare 与生产同口径_自造键声明后可注册可派发_语料键拒绝`() {
        val world = TestSchema.world()
        val session = runMod(
            object : EraMod {
                override fun onLoad(context: ModContext) = Unit
                override fun onEnable() = Unit
                override fun onDisable() = Unit
                override fun onUnload() = Unit
            },
            world,
            TestSchema.PACKAGE_ID,
        )
        val ctx = session.context
        // 未灌形状：语料闸关闭，declare 任意键（含与语料形态同名的键）都放行 —— 与生产
        // 「无链接目录会话」同口径。
        ctx.events.declare("MOD_A_EVT")
        ctx.events.register("MOD_A_EVT", EventBucket.NORMAL) { EventResult.CONTINUE }
        assertEquals(listOf(EventResult.CONTINUE), ctx.dispatchEvent("MOD_A_EVT"), "声明后注册 + 派发走通")
        assertTrue(ctx.events.exists("MOD_A_EVT"), "声明键 exists 恒真")

        // 灌形状后：语料事件不能声明为自定义事件（命名空间分离，文案与生产逐字一致）。
        ctx.adoptProductionShape(eventKeys = setOf("EVENTTURNEND"))
        val e = assertFailsWith<IllegalArgumentException> { ctx.events.declare("EVENTTURNEND") }
        assertTrue(e.message!!.contains("语料已有事件"), "实际：${e.message}")
        assertFalse(ctx.events.exists("UNDECLARED_EVT"))
    }
}
