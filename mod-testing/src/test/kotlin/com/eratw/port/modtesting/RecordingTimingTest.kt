package com.eratw.port.modtesting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 台架耗时替身 [RecordingTiming] 的写入口自测。
 *
 * 语义对拍见 `:core-vm` 的 `ModTimingRecordingParityTest`（与生产 `ModTimingLedger` 逐项比对）；
 * 本文件只钉替身自己的 `record` / `measure` 行为，便于在 `:mod-testing` 单模块内跑红。
 */
class RecordingTimingTest {

    @Test
    fun `record聚合次数总耗时最大均值最近一次`() {
        val timing = RecordingTiming(PACKAGE_ID)
        timing.record("author.custom", 100L)
        timing.record("author.custom", 300L)

        val sample = timing.samples().single()
        assertEquals("author.custom", sample.scope)
        assertEquals(2L, sample.count)
        assertEquals(400L, sample.totalNanos)
        assertEquals(300L, sample.maxNanos)
        assertEquals(200L, sample.averageNanos)
        assertEquals(300L, sample.lastNanos)
    }

    @Test
    fun `record非法参数IAE且不留半笔账`() {
        val timing = RecordingTiming(PACKAGE_ID)
        assertFailsWith<IllegalArgumentException> { timing.record("", 1L) }
        assertFailsWith<IllegalArgumentException> { timing.record("  ", 1L) }
        assertFailsWith<IllegalArgumentException> { timing.record("tick", -1L) }
        val tooLong = "s".repeat(RecordingTiming.MAX_SCOPE_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> { timing.record(tooLong, 1L) }
        assertTrue(timing.samples().isEmpty(), "非法写入不得留下半笔账")
    }

    @Test
    fun `measure值异常都原样透传且异常照记`() {
        val timing = RecordingTiming(PACKAGE_ID)

        assertEquals(42, timing.measure("author.ok") { 42 })
        val ex = assertFailsWith<IllegalStateException> {
            timing.measure("author.ok") { error("故意抛") }
        }
        assertEquals("故意抛", ex.message)

        val sample = timing.samples().single()
        assertEquals(2L, sample.count, "异常那一次也算一次")
        assertTrue(sample.totalNanos >= 0L)
    }

    @Test
    fun `单包scope数到顶只WARN一次并忽略新scope`() {
        val warns = mutableListOf<String>()
        val timing = RecordingTiming(PACKAGE_ID, warn = { message -> warns += message })
        repeat(RecordingTiming.MAX_DISTINCT_SCOPES) { index ->
            timing.record("scope.$index", 1L)
        }
        assertEquals(RecordingTiming.MAX_DISTINCT_SCOPES, timing.samples().size)

        timing.record("scope.extra", 9L)
        assertEquals(RecordingTiming.MAX_DISTINCT_SCOPES, timing.samples().size, "新 scope 被忽略")
        assertEquals(1, warns.size)
        val warning = warns.single()
        assertTrue(warning.contains("scope.extra"))
        assertTrue(warning.contains("${RecordingTiming.MAX_DISTINCT_SCOPES}"))

        timing.record("scope.0", 4L)
        assertEquals(2L, timing.samples().single { it.scope == "scope.0" }.count, "既有 scope 继续累加")
        timing.record("scope.more", 9L)
        assertEquals(1, warns.size, "每替身只 WARN 一次")
    }

    @Test
    fun `reset清空并复位上限计数`() {
        val warns = mutableListOf<String>()
        val timing = RecordingTiming(PACKAGE_ID, warn = { message -> warns += message })
        repeat(RecordingTiming.MAX_DISTINCT_SCOPES) { index ->
            timing.record("scope.$index", 1L)
        }
        timing.record("scope.extra", 1L)
        assertEquals(1, warns.size)

        timing.reset()
        assertTrue(timing.samples().isEmpty())
        timing.record("after.reset", 1L)
        assertEquals(listOf("after.reset"), timing.samples().map { it.scope }, "reset 后重新记账不受旧上限计数影响")
    }

    private companion object {
        const val PACKAGE_ID = "bench.timing.mod"
    }
}
