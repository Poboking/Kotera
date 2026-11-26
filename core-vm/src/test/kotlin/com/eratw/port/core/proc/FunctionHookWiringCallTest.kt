package com.eratw.port.core.proc

import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §20.1 函数 hook 的**生产接线**（宿主调用侧，P1-5 收账）：`ProcessRuntime.doCall` →
 * [com.eratw.port.core.mod.FunctionHookWiring.dispatchCall]。
 *
 * 这一半锁「宿主按名调用脚本函数」这条漏斗（`callFunction` / `tryCall` / `callForm` 共用
 * `doCall`）：槽语义（Replace / SkipOriginal / Postfix 改值 / 实参回流 / 失败隔离 /
 * priority 全局序）全在 **真 VM** 上跑，不是 `HookPipeline` 单测的同义反复。
 *
 * 反向证明（最重要的一条）：未装接线、或该键无声明槽时，返回值与打印面必须与改造前
 * 逐位一致 —— 这是 golden 零变化的前提。
 */
class FunctionHookWiringCallTest {

    @Test
    fun `未装接线_宿主调用完全旁路`() {
        val h = HookHarness()
        assertEquals(HookHarness.ARG_VALUE, h.callF(), "未装接线时必须原样返回原函数值")
        assertEquals("orig\n", h.output, "原函数体必须真的执行")
        assertTrue(h.logs.isEmpty(), "旁路路径不该有任何接线日志")
    }

    @Test
    fun `装了接线但该键无声明槽_仍然完全旁路`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", targetKey = "OTHER"))
        h.attach(pipeline)
        assertEquals(HookHarness.ARG_VALUE, h.callF(), "无槽键 = 旁路，不受整条链是否装线影响")
        assertEquals("orig\n", h.output)
    }

    @Test
    fun `前缀Replace_短路原函数与其余hook`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a"))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        assertEquals(42L, h.callF(), "前缀 Replace 的返回值就是本次调用返回值")
        assertEquals("", h.output, "短路后原函数体不许执行")
    }

    @Test
    fun `后缀声明mayMutateReturn_改返回值生效`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.postfix("mod.a", mayMutateReturn = true))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.POSTFIX, "mod.a") { context ->
            context.returnValue = ModValue.IntValue(77L)
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(77L, h.callF())
        assertEquals("orig\n", h.output, "后缀是在原函数之后，原函数必须跑过")
    }

    @Test
    fun `后缀未声明mayMutateReturn_改返回值被引擎忽略`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.postfix("mod.a", mayMutateReturn = false))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.POSTFIX, "mod.a") { context ->
            context.returnValue = ModValue.IntValue(77L)
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(HookHarness.ARG_VALUE, h.callF(), "未授权 = 隔离，改不动返回值")
    }

    @Test
    fun `SkipOriginal_原函数体不跑_后缀照跑`() {
        val h = HookHarness()
        val pipeline = h.pipeline(
            HookDecls.prefix("mod.a"),
            HookDecls.postfix("mod.b", mayMutateReturn = true),
        )
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") { HookResult.SkipOriginal }
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.POSTFIX, "mod.b") { context ->
            context.returnValue = ModValue.IntValue(999L)
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(999L, h.callF(), "跳过原函数后，返回值为后缀改出的值")
        assertEquals("", h.output, "SkipOriginal = 原函数体的副作用不许出现")
    }

    @Test
    fun `前缀声明mayMutateArgs_实参改动回流到原函数`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", mayMutateArgs = true))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") { context ->
            context.args[0].intValue = 99L
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(99L, h.callF(), "原函数拿到的必须是前缀改后的实参")
    }

    @Test
    fun `前缀未声明mayMutateArgs_实参改动不回流`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a", mayMutateArgs = false))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") { context ->
            context.args[0].intValue = 99L
            HookResult.Continue
        }
        h.attach(pipeline)
        assertEquals(HookHarness.ARG_VALUE, h.callF(), "未授权 = 拿到实参副本，改动不回流")
    }

    @Test
    fun `处理器抛异常_失败隔离且原函数照跑`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a"))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") {
            throw IllegalStateException("boom")
        }
        h.attach(pipeline)
        assertEquals(HookHarness.ARG_VALUE, h.callF(), "处理器异常不许改变调用结果")
        assertEquals("orig\n", h.output, "处理器异常不许拖垮原函数")
        assertTrue(
            h.logs.any { it.contains("已隔离") && it.contains("mod.a") },
            "失败必须进日志面（可归因到包），实得：${h.logs}",
        )
    }

    @Test
    fun `两个mod同槽_按priority全局序执行`() {
        val h = HookHarness()
        val seen = ArrayList<String>()
        // 声明序 = mod.b 在前，但 priority 让 mod.a 先跑：证明序不按包分组、不按装载序。
        val pipeline = h.pipeline(
            HookDecls.prefix("mod.b", priority = 1),
            HookDecls.prefix("mod.a", priority = 0),
        )
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.b") { context ->
            seen += context.packageId
            HookResult.Continue
        }
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") { context ->
            seen += context.packageId
            HookResult.Continue
        }
        h.attach(pipeline)
        h.callF()
        assertEquals(listOf("mod.a", "mod.b"), seen, "priority 升序对整条链生效（跨包）")
    }

    @Test
    fun `uninstall之后回到完全旁路`() {
        val h = HookHarness()
        val pipeline = h.pipeline(HookDecls.prefix("mod.a"))
        pipeline.bind(HookHarness.FUNCTION_KEY, ModHookPhase.PREFIX, "mod.a") {
            HookResult.Replace(ModValue.IntValue(42L))
        }
        h.attach(pipeline)
        assertEquals(42L, h.callF())
        h.detach(pipeline)
        assertEquals(HookHarness.ARG_VALUE, h.callF(), "解绑后必须逐位回到改造前行为")
        assertEquals("orig\n", h.output)
    }
}
