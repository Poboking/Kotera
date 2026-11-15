package com.eratw.port.core.err

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4.981 12.2.0「RAND 随机数入口安全保护」+ SafeArithmetic 钳制告警的定向回归。
 *
 * 权威出处：`CHANGELOG.md:105-112`、`Creator.Method.cs:3793-3857`（RandMethod）、
 * `VariableToken.cs:1413-1436`（RandToken）、`SafeArithmetic.cs:67-84`（零除）。
 *
 * 本文件钉的三件事，都是「移植侧照抄 4.981 时最容易写错」的形状：
 * 1. `clampedWarned` 是**每个类各一份 static**（RandMethod 一份、RandToken 一份），
 *    不是整个 RAND 家族共用一个开关 ⇒ 按 kind 分桶；
 * 2. 铁律 4 禁静态可变全局 ⇒ 状态挂 [RuntimeAux] 会话槽，`clear()` 后重新允许首次告警；
 * 3. 方法位钳制返回**下界 min**（伪变量位返回 0），且非法参数**不消耗 RNG**。
 */
class ClampWarningsTest {

    private class RecordingEvalContext(private val base: TestEvalContext) : EvalContext by base {
        val systemLines = ArrayList<String>()

        override fun printSystemLine(message: String) {
            systemLines.add(message)
        }
    }

    private fun harness(): Triple<VariableStore, BuiltinEvalContext, RecordingEvalContext> {
        val store = VariableStore().apply {
            randProvider = RandProvider { bound -> bound - 1 }
        }
        val rec = RecordingEvalContext(TestEvalContext(store))
        val ctx = BuiltinEvalContext(rec, store)
        // 伪变量通道（RAND:n）没有 EvalContext，生产由 ProcessRuntime.init 接这条线。
        store.systemLineSink = { rec.systemLines.add(it) }
        return Triple(store, ctx, rec)
    }

    private fun randMethod(ctx: BuiltinEvalContext, vararg args: Long): Long =
        (ctx.callFunction("RAND", args.map { Value.IntV(it) }) as Value.IntV).v

    // ------------------------------------------------------------------
    // 一、会话槽与 once 分桶
    // ------------------------------------------------------------------

    @Test
    fun `once 按 kind 分桶且不同 kind 互不干扰`() {
        val w = ClampWarnings()
        assertEquals("A", w.once(RAND_TOKEN_CLAMP_KIND, "A"))
        assertNull(w.once(RAND_TOKEN_CLAMP_KIND, "A2"), "同一 kind 只在首次返回文本")
        assertEquals("B", w.once(RAND_METHOD_CLAMP_KIND, "B"), "4.981 里 RandMethod 有自己那份 static")
        assertEquals(listOf("A", "B"), w.lines())
    }

    @Test
    fun `告警状态挂在 RuntimeAux 会话槽且 clear 后重置`() {
        val aux = RuntimeAux()
        val first = aux.clampWarnings()
        assertTrue(first === aux.clampWarnings(), "同一会话必须复用同一实例")
        assertEquals("A", first.once(RAND_TOKEN_CLAMP_KIND, "A"))

        aux.clear() // RESTART / 读档重建
        val second = aux.clampWarnings()
        assertTrue(second !== first, "clear 后不应复用旧对象")
        assertEquals("A2", second.once(RAND_TOKEN_CLAMP_KIND, "A2"), "新会话应重新允许首次告警")
    }

    @Test
    fun `every 每次都返回供上屏但留痕有界`() {
        // 4.981 SafeDivide 是每次 PrintWarning；循环里反复踩零时无上限留痕会喂爆堆
        // （收件箱里本就有 OOM 簇）。上屏照发，留痕封顶 64 条。
        val w = ClampWarnings()
        repeat(200) { assertEquals("零除: 1 / 0", w.every("零除: 1 / 0")) }
        assertEquals(64, w.lines().size)
        assertEquals(200 - 64, w.suppressedCount())
    }

    // ------------------------------------------------------------------
    // 二、文案唯一真源
    // ------------------------------------------------------------------

    @Test
    fun `三类告警文案逐字对齐 4_981`() {
        assertEquals("RAND不能指定0以下的值(0)（已钳制为 0，不再中断运行）", randTokenClampMessage(0L))
        assertEquals("RAND不能指定0以下的值(-3)（已钳制为 0，不再中断运行）", randTokenClampMessage(-3L))
        assertEquals(
            "函数RAND: 最大值被指定为小于或等于最小值的值(0)（已钳制为下界，不再中断运行）",
            randMethodClampMessage("RAND", 0L),
        )
        assertEquals("零除: 10 / 0", zeroDivideWarning(10L, isModulo = false))
        assertEquals("零除: 10 \\ 0", zeroDivideWarning(10L, isModulo = true), "取余支是反斜杠")
    }

    // ------------------------------------------------------------------
    // 三、RAND 方法位（Creator.Method.cs RandMethod）
    // ------------------------------------------------------------------

    @Test
    fun `RAND 单参零钳制返回下界零且首次告警一次`() {
        val (_, ctx, rec) = harness()
        assertEquals(0L, randMethod(ctx, 0L))
        assertEquals(
            listOf("函数RAND: 最大值被指定为小于或等于最小值的值(0)（已钳制为下界，不再中断运行）"),
            rec.systemLines,
        )
        assertEquals(0L, randMethod(ctx, 0L), "第二次仍钳制")
        assertEquals(1, rec.systemLines.size, "clampedWarned：后续触发静默，不刷屏")
    }

    @Test
    fun `RAND 双参 max 不大于 min 时返回 min 而不是零`() {
        // 与 RandToken（伪变量恒返回 0）的关键差别：RandMethod 返回**下界 min**。
        val (_, ctx, rec) = harness()
        assertEquals(5L, randMethod(ctx, 5L, 3L))
        assertEquals(5L, randMethod(ctx, 5L, 5L), "max == min 也是空区间")
        assertEquals(1, rec.systemLines.size, "整数/两种入参共用 RandMethod 那一份 flag")
    }

    @Test
    fun `非法参数不消耗 RNG 且合法上界不受影响`() {
        val (store, ctx, rec) = harness()
        val calls = ArrayList<Long>()
        store.randProvider = RandProvider { bound -> calls.add(bound); bound - 1 }

        assertEquals(0L, randMethod(ctx, 0L))
        assertTrue(calls.isEmpty(), "钳制路径必须在 GetNextRand 之前 return：$calls")

        assertEquals(4L, randMethod(ctx, 5L), "桩按 bound-1 返回 ⇒ RAND(5) = 4")
        assertEquals(listOf(5L), calls)
        assertEquals(1, rec.systemLines.size, "合法调用不得补一条告警")
    }

    @Test
    fun `伪变量与方法位的 clampedWarned 各自独立`() {
        // 反向证明：4.981 里 RandMethod.clampedWarned 与 RandToken.clampedWarned 是
        // 两个不同类的 private static；若移植侧图省事合成一个 kind，第二条告警会丢。
        val (store, ctx, rec) = harness()
        assertEquals(0L, randMethod(ctx, 0L))
        assertEquals(0L, store.readInt("RAND", longArrayOf(0L)))
        assertEquals(2, rec.systemLines.size, "两条通道各留一条：${rec.systemLines}")
        assertTrue(rec.systemLines[0].contains("已钳制为下界"))
        assertTrue(rec.systemLines[1].contains("已钳制为 0"))
    }
}
