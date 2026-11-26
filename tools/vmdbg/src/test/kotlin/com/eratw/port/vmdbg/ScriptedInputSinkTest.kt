package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `--inputs` 脚本 sink（call/trace 的真实入口复现，2026-11-26）机制锁：
 * - INT 请求按脚本序交付（`--inputs "768,0"` → 第一次 768、第二次 0）；
 * - 脚本耗尽回落 [ImmediateInputSink] 口径：INT 取默认/0、STR 取默认/空串；
 * - INT 请求遇到非整数项 → [CliError]，**不静默回 0**（否则脚本形同虚设还看起来跑绿）；
 * - 任意键等待（readAnyKey）不消费脚本；STR 请求原样交付（数字步落 STR 等待点照原版收文本）。
 */
class ScriptedInputSinkTest {

    private fun intReq(def: Long? = null) = InputRequest(
        type = InputRequest.InputType.INT,
        hasDef = def != null,
        defInt = def ?: 0L,
    )

    private fun strReq(def: String? = null) = InputRequest(
        type = InputRequest.InputType.STR,
        hasDef = def != null,
        defStr = def ?: "",
    )

    @Test
    fun intScriptDeliveredInOrder() {
        val sink = ScriptedInputSink(listOf("768", "0"))
        assertEquals(InputOutcome(intValue = 768L), sink.waitInput(intReq()))
        assertEquals(InputOutcome(intValue = 0L), sink.waitInput(intReq()))
        assertEquals(2, sink.consumed)
    }

    @Test
    fun stringScriptDeliveredVerbatim() {
        val sink = ScriptedInputSink(listOf("高級料理"))
        assertEquals("高級料理", sink.waitInput(strReq()).strValue)
        assertEquals(1, sink.consumed)
    }

    @Test
    fun exhaustedScriptFallsBackToImmediateSink() {
        val sink = ScriptedInputSink(listOf("5"))
        assertEquals(5L, sink.waitInput(intReq()).intValue)
        // 耗尽：无默认 → 0；有默认 → 默认值（与 ImmediateInputSink 同口径）。
        assertEquals(0L, sink.waitInput(intReq()).intValue)
        assertEquals(42L, sink.waitInput(intReq(def = 42L)).intValue)
        assertEquals("", sink.waitInput(strReq()).strValue)
        assertEquals("默认", sink.waitInput(strReq(def = "默认")).strValue)
        assertEquals(1, sink.consumed)
    }

    @Test
    fun nonIntegerItemOnIntRequestIsFatal() {
        val sink = ScriptedInputSink(listOf("abc"))
        val err = assertFailsWith<CliError> { sink.waitInput(intReq()) }
        assertEquals(true, err.message!!.contains("不是整数"))
    }

    @Test
    fun anyKeyWaitDoesNotConsumeScript() {
        val sink = ScriptedInputSink(listOf("768"))
        sink.readAnyKey(anyKey = true, stopMesSkip = true)
        assertEquals(0, sink.consumed)
        assertEquals(768L, sink.waitInput(intReq()).intValue)
    }
}
