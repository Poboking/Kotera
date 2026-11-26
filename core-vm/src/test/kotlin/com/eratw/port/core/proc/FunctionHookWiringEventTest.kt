package com.eratw.port.core.proc

import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.patch.HookPhase as DeclHookPhase
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §20.1 函数 hook 接线的**事件侧与翻译契约**（`ProcessRuntime.executeEvent` →
 * [FunctionHookWiring.dispatchEvent]，以及双向翻译 `双桶实参 ↔ Array<ModCallArg>` /
 * `ModValue? ↔ 返回值`）。
 *
 * 事件是 mod 最常用的生命相位（`EVENTTURNEND` / `EVENTCOM` …），靶键用**事件名**；
 * 翻译契约（int 桶在前、str 桶在后；非整数返回值丢弃并告警）在 KDoc 里已写死，
 * 这里逐条锁住，避免以后被「顺手改得更好」。
 */
class FunctionHookWiringEventTest {

    @Test
    fun `事件未装接线_正常派发`() {
        val h = HookHarness()
        assertTrue(h.runEvent(), "无接线时事件必须照常执行")
        assertEquals("ev\n", h.output)
    }

    @Test
    fun `事件前缀Replace_短路整条成员链`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.event("mod.a"))
        pipeline.bind(HookHarness.EVENT_KEY, ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(0L))
        }
        h.attach(pipeline)
        assertFalse(h.runEvent(), "Replace 值为 0 = 事件「未执行」")
        assertEquals("", h.output, "短路后事件成员链不许执行")
    }

    @Test
    fun `事件SkipOriginal_成员链不跑`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.event("mod.a"))
        pipeline.bind(HookHarness.EVENT_KEY, ModHookPhase.PREFIX, "mod.a") { HookResult.SkipOriginal }
        h.attach(pipeline)
        assertFalse(h.runEvent(), "跳过原派发后返回值为空 = 未执行")
        assertEquals("", h.output)
    }

    @Test
    fun `事件后缀声明mayMutateReturn_改返回值`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.event("mod.a", DeclHookPhase.POSTFIX, mayMutateReturn = true))
        pipeline.bind(HookHarness.EVENT_KEY, ModHookPhase.POSTFIX, "mod.a") { context ->
            context.returnValue = ModValue.IntValue(0L)
            HookResult.Continue
        }
        h.attach(pipeline)
        assertFalse(h.runEvent(), "后缀把「执行了」改成「未执行」")
        assertEquals("ev\n", h.output, "后缀在原派发之后，成员链必须跑过")
    }

    @Test
    fun `事件处理器抛异常_失败隔离且派发照跑`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.event("mod.a"))
        pipeline.bind(HookHarness.EVENT_KEY, ModHookPhase.PREFIX, "mod.a") {
            throw IllegalStateException("boom")
        }
        h.attach(pipeline)
        assertTrue(h.runEvent(), "处理器异常不许改变派发结果")
        assertEquals("ev\n", h.output)
        assertTrue(
            h.logs.any { it.contains("已隔离") && it.contains(HookHarness.EVENT_KEY) },
            "失败必须进日志面（可归因到键），实得：${h.logs}",
        )
    }

    @Test
    fun `实参摊平序_int桶在前str桶在后`() {
        val h = HookHarness()
        val seen = ArrayList<String>()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", mayMutateArgs = true))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") { context ->
            context.args.forEachIndexed { index, arg ->
                seen += "$index:${arg.isString}:${arg.intValue}:${arg.strValue}"
            }
            HookResult.Continue
        }
        h.attach(pipeline)
        val returned =
            FunctionHookWiring.dispatchCall(
                h.store,
                HookHarness.FUNCTION_KEY,
                longArrayOf(1L, 2L),
                arrayOf("a", "b"),
                strRet = false,
            ) { _, _ -> ModValue.IntValue(7L) }.let { (it as ModValue.IntValue).value }
        assertEquals(7L, returned)
        assertEquals(
            listOf("0:false:1:", "1:false:2:", "2:true:0:a", "3:true:0:b"),
            seen,
            "摊平序契约：int 桶按调用序在前，str 桶按调用序在后",
        )
    }

    @Test
    fun `函数调用点返回字符串值_记警告并按0返回`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a"))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("nope"))
        }
        h.attach(pipeline)
        assertEquals(0L, h.callF(), "函数调用点只接整数返回值，字符串替换值按 0 交割")
        assertTrue(
            h.logs.any { it.contains("只接整数返回值") },
            "丢弃必须留告警，不许静默，实得：${h.logs}",
        )
    }

    @Test
    fun `事件派发点返回字符串值_记警告并按false处理`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.event("mod.a"))
        pipeline.bind(HookHarness.EVENT_KEY, ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.StrValue("nope"))
        }
        h.attach(pipeline)
        assertFalse(h.runEvent(), "事件派发只认整数返回值，字符串替换值按 false 处理")
        assertTrue(
            h.logs.any { it.contains("事件派发只认整数返回值") },
            "丢弃必须留告警，不许静默，实得：${h.logs}",
        )
    }

    @Test
    fun `事件无实参_hook拿到空实参数组`() {
        val h = HookHarness()
        var observed: Int? = null
        val pipeline = h.pipeline(HookDecls.event("mod.a"))
        pipeline.bind(HookHarness.EVENT_KEY, ModHookPhase.PREFIX, "mod.a") { context ->
            observed = context.args.size
            HookResult.Continue
        }
        h.attach(pipeline)
        assertTrue(h.runEvent())
        assertEquals(0, observed, "事件没有实参，hook 必须拿到空数组（不是 null / 不崩）")
    }
}
