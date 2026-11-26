package com.eratw.port.core.save

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [SaveDiagnostics] 的失败观察者 + 上报冷却闸（2026-11-20 存档失败可上报化）。
 *
 * ## 钉哪四条
 *
 * 1. [SaveDiagnostics.report] 在错误留痕后**同步**回调观察者，且回调时环形缓冲里已有这
 *    一条失败（宿主据此拍现场，报告才不会漏掉触发它的那次）；
 * 2. [SaveDiagnostics.note]（退路/非失败）**不**触发观察者 —— 降级成功不许伪装成失败上报；
 * 3. 观察者抛异常（含 Error）不冒泡、不影响 [SaveDiagnostics.report] 的留痕与返回；
 * 4. [SaveDiagnostics.claimFailureAlert] 的窗口：同 op 10 分钟内只放行一次、异 op 互不影响、
 *    到点放行、时钟回拨不冻结（可注入时钟，不真睡 10 分钟）。
 *
 * 观察者是进程级可变状态：每条用例前后都复位，避免污染同 JVM 的其它存档用例。
 */
class SaveDiagnosticsAlertTest {

    @BeforeTest
    fun resetBefore() {
        SaveDiagnostics.onFailure = null
        SaveDiagnostics.alertClock = { System.currentTimeMillis() }
        SaveDiagnostics.clear()
    }

    @AfterTest
    fun resetAfter() {
        SaveDiagnostics.onFailure = null
        SaveDiagnostics.alertClock = { System.currentTimeMillis() }
        SaveDiagnostics.clear()
    }

    @Test
    fun `report 回调观察者且回调时环形缓冲已含该次失败`() {
        var got: SaveDiagnostics.Failure? = null
        var snapshotAtCallback: List<String> = emptyList()
        SaveDiagnostics.onFailure = { failure ->
            got = failure
            // 顺序契约：入队先行，观察者里读 snapshot() 必须能看到触发它的那一条。
            snapshotAtCallback = SaveDiagnostics.snapshot()
        }

        SaveDiagnostics.report(
            "SAVEDATA",
            "sav/save1.sav",
            "write",
            IllegalStateException("disk full"),
            "dir=files/sav",
        )

        val failure = assertNotNull(got, "report 必须回调观察者")
        assertEquals("SAVEDATA", failure.op)
        assertEquals("sav/save1.sav", failure.path)
        assertEquals("write", failure.stage)
        assertEquals("java.lang.IllegalStateException", failure.errorClass)
        assertEquals("disk full", failure.message)
        assertTrue(
            snapshotAtCallback.any { it.contains("SAVEDATA") && it.contains("stage=write") },
            "回调时环形缓冲必须已含该次失败：$snapshotAtCallback",
        )
    }

    @Test
    fun `note 不触发观察者`() {
        var calls = 0
        SaveDiagnostics.onFailure = { calls++ }

        SaveDiagnostics.note("SAVEDATA", "sav/save1.sav", "换名失败、已退原地覆盖")

        assertEquals(0, calls, "note 是降级成功，不是失败，不许触发上报")
        assertTrue(SaveDiagnostics.snapshot().any { it.contains("退路") })
    }

    @Test
    fun `观察者抛 Error 不冒泡且失败留痕照写`() {
        SaveDiagnostics.onFailure = { throw AssertionError("observer boom") }

        // 不许抛：调用方在失败的 catch 块里，冒一次就把「存档失败」升级成「进程崩溃」。
        SaveDiagnostics.report("SAVEGLOBAL", "sav/global.sav", "encode", RuntimeException("encode boom"))

        val trail = SaveDiagnostics.snapshot()
        assertTrue(
            trail.any { it.contains("SAVEGLOBAL") && it.contains("stage=encode") },
            "观察者抛异常不许影响报告留痕：$trail",
        )
    }

    @Test
    fun `同 op 十分钟冷却、异 op 独立、到点放行`() {
        var now = 1_000_000L
        SaveDiagnostics.alertClock = { now }

        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEDATA"), "首次必须放行")
        assertFalse(SaveDiagnostics.claimFailureAlert("SAVEDATA"), "同 op 冷却中必须挡下")
        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEGLOBAL"), "异 op 互不影响")

        now += SaveDiagnostics.ALERT_COOLDOWN_MS - 1
        assertFalse(SaveDiagnostics.claimFailureAlert("SAVEDATA"), "窗口内仍挡")

        now += 1
        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEDATA"), "到点必须放行")
    }

    @Test
    fun `时钟回拨按冷却已过处理不冻结上报`() {
        var now = 10_000_000L
        SaveDiagnostics.alertClock = { now }

        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEDATA"))
        now -= 60_000L
        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEDATA"), "负差值不许把上报冻死（宁可多报）")
    }

    @Test
    fun `clear 同时清冷却表`() {
        var now = 5_000_000L
        SaveDiagnostics.alertClock = { now }

        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEDATA"))
        assertFalse(SaveDiagnostics.claimFailureAlert("SAVEDATA"))
        SaveDiagnostics.clear()
        assertTrue(SaveDiagnostics.claimFailureAlert("SAVEDATA"), "clear 是单测隔离口径，须一并清冷却表")
    }
}
