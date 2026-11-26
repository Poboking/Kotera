package com.eratw.port.core.mod

import com.eratw.port.modapi.TimingSample
import com.eratw.port.modtesting.RecordingTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 台架耗时替身 × 生产耗时账本的**语义对拍**（Lane BB + 写侧扩展）。
 *
 * 左边 = `:core-vm` 的 [ModTimingLedger]（生产框架账本；`context.timing.samples()` / `reset()` 的底）；
 * 右边 = `:mod-testing` 的 [RecordingTiming]（作者单测用的录制替身）。两边吃同一串 `record` /
 * `measure` 输入、同一份假时钟，断言「scope 集合、次数、聚合量、降序、校验、上限」逐项一致 ——
 * 台架替身一旦漂移，这里先红。
 *
 * **无口径差异**（写侧扩展后两面逐条对齐）：空白 / 超长 scope、负耗时都显式
 * `IllegalArgumentException` 且不留半笔账；单包 scope 数到顶都只 WARN 一次并忽略新 scope；
 * `measure` 与 `measureValue` 同构（`try/finally` 落账、时钟回拨按 0 记、非法 scope 到落账时才抛）。
 * 唯一接线差异是 WARN 落点：生产进宿主 `logSink`（整行含包前缀），台架进 `warn` 回调的消息体。
 *
 * 不覆盖：生产侧四相位的自动记账点（lifecycle / tick / hook / event，见 `ModTimingLedgerTest`）；
 * 本文件只对拍「怎么写账、怎么读账、怎么清账、怎么拒绝非法写」。
 */
class ModTimingRecordingParityTest {

    @Test
    fun `同一记账序列两面scope集合次数与聚合量逐项一致`() {
        val bench = Bench()
        val reads = listOf(
            "tick" to 1_500L,
            "lifecycle.onLoad" to 40_000L,
            "tick" to 2_500L,
            "hook.TEMPLATE.PING.PREFIX" to 300L,
            "event.TEMPLATE.DAY_TICK.NORMAL" to 8_000L,
        )

        reads.forEach { (scope, nanos) -> bench.recordBoth(scope, nanos) }

        assertEquals(bench.ledger.forPackage(PACKAGE_ID), bench.recorder.samples(), "两面读数（含降序）必须逐项一致")
        assertEquals(
            bench.ledger.forPackage(PACKAGE_ID).map(TimingSample::scope),
            bench.recorder.samples().map(TimingSample::scope),
            "scope 降序也必须一致",
        )
        assertEquals(
            setOf("tick", "lifecycle.onLoad", "hook.TEMPLATE.PING.PREFIX", "event.TEMPLATE.DAY_TICK.NORMAL"),
            bench.recorder.scopeNames(),
            "scope 集合应完整落账",
        )
        // tick 只记两次：count 是 2 而不是 5（同 scope 累加、不重复建键）。
        assertEquals(2L, bench.recorder.samples().single { it.scope == "tick" }.count)
    }

    @Test
    fun `measure值在假时钟下两面同口径且异常照样落账后原样抛`() {
        val instants = listOf(0L, 10L, 10L, 25L)
        val ledger = ModTimingLedger(clockFrom(instants))
        val recorder = RecordingTiming(PACKAGE_ID, clockFrom(instants))

        assertEquals(1, ledger.measureValue(PACKAGE_ID, "tick") { 1 })
        assertEquals(1, recorder.measure("tick") { 1 })
        assertEquals(2, ledger.measureValue(PACKAGE_ID, "tick") { 2 })
        assertEquals(2, recorder.measure("tick") { 2 })

        assertEquals(ledger.forPackage(PACKAGE_ID), recorder.samples(), "measure 的假时钟落账两面必须一致")

        val boom = 7
        val productionEx = assertFailsWith<IllegalStateException> {
            ledger.measureValue(PACKAGE_ID, "hook.SLOT.PREFIX") { error("boom $boom") }
        }
        val recorderEx = assertFailsWith<IllegalStateException> {
            recorder.measure("hook.SLOT.PREFIX") { error("boom $boom") }
        }

        assertEquals("boom $boom", productionEx.message, "生产面应原样抛用户异常")
        assertEquals(productionEx.message, recorderEx.message, "台架也要原样抛，不许吞")
        assertEquals(
            ledger.forPackage(PACKAGE_ID),
            recorder.samples(),
            "异常路径的耗时也必须落账（失败不是免费的），两面读数一致",
        )
        assertEquals(
            1L,
            recorder.samples().single { it.scope == "hook.SLOT.PREFIX" }.count,
            "抛异常的那次仍应计一次",
        )
    }

    @Test
    fun `并列总耗时两面保持首次出现序`() {
        val bench = Bench()

        bench.recordBoth("tick", 500L)
        bench.recordBoth("hook.A.PREFIX", 700L)
        bench.recordBoth("event.B.NORMAL", 700L)

        assertEquals(
            listOf("hook.A.PREFIX", "event.B.NORMAL", "tick"),
            bench.ledger.forPackage(PACKAGE_ID).map(TimingSample::scope),
            "生产面并列应按首次出现序（插入序 + 稳定排序）",
        )
        assertEquals(
            bench.ledger.forPackage(PACKAGE_ID).map(TimingSample::scope),
            bench.recorder.samples().map(TimingSample::scope),
            "台架并列序必须与生产面一致",
        )
    }

    @Test
    fun `负耗时显式写入两面都IAE且不留半笔账`() {
        val bench = Bench()
        bench.recordBoth("tick", 900L)

        listOf(-1L, -40L).forEach { illegal ->
            assertFailsWith<IllegalArgumentException>("负耗时 $illegal 生产面应显式拒绝") {
                bench.ledger.record(PACKAGE_ID, "tick", illegal)
            }
            assertFailsWith<IllegalArgumentException>("负耗时 $illegal 台架应显式拒绝") {
                bench.recorder.record("tick", illegal)
            }
        }

        assertEquals(1L, bench.ledger.forPackage(PACKAGE_ID).single().count, "拒绝不得留下半笔账")
        assertEquals(bench.ledger.forPackage(PACKAGE_ID), bench.recorder.samples())
        assertEquals(900L, bench.recorder.samples().single().totalNanos)
    }

    @Test
    fun `measure时钟回拨两面都按零记且不盖掉block异常`() {
        val ledger = ModTimingLedger(clockFrom(listOf(2_000L, 1_900L)))
        val recorder = RecordingTiming(PACKAGE_ID, clockFrom(listOf(2_000L, 1_900L)))

        val productionEx = assertFailsWith<IllegalStateException> {
            ledger.measureValue(PACKAGE_ID, "hook.R.PREFIX") { error("boom") }
        }
        val recorderEx = assertFailsWith<IllegalStateException> {
            recorder.measure("hook.R.PREFIX") { error("boom") }
        }

        assertEquals("boom", productionEx.message)
        assertEquals(productionEx.message, recorderEx.message, "回拨不得盖掉 block 自己的异常")
        assertEquals(ledger.forPackage(PACKAGE_ID), recorder.samples(), "回拨处置两面一致")
        val sample = recorder.samples().single()
        assertEquals(1L, sample.count, "回拨当次仍计一次")
        assertEquals(0L, sample.totalNanos, "回拨按零记")
        assertEquals(0L, sample.lastNanos)
    }

    @Test
    fun `reset只清本包的账两面同语义`() {
        val ledger = ModTimingLedger()
        val mine = RecordingTiming(PACKAGE_ID)
        val other = RecordingTiming(OTHER_PACKAGE_ID)

        ledger.record(PACKAGE_ID, "tick", 1_000L)
        ledger.record(OTHER_PACKAGE_ID, "tick", 9_000L)
        mine.record("tick", 1_000L)
        other.record("tick", 9_000L)

        ledger.resetPackage(PACKAGE_ID)
        mine.reset()

        assertEquals(emptyList(), mine.samples(), "reset 应清空本包账")
        assertEquals(emptyList(), ledger.forPackage(PACKAGE_ID), "生产面 resetPackage 同语义")
        assertEquals(9_000L, other.samples().single().totalNanos, "别的包（别的替身）不受影响")
        assertEquals(9_000L, ledger.forPackage(OTHER_PACKAGE_ID).single().totalNanos, "生产面别的包也不受影响")
        assertTrue(other.samples().single().scope == "tick")
    }

    @Test
    fun `samples只返回本包读数而不管别的替身怎么记`() {
        val mine = RecordingTiming(PACKAGE_ID)
        val other = RecordingTiming(OTHER_PACKAGE_ID)

        mine.record("lifecycle.onLoad", 3_000L)
        other.record("tick", 70_000L)

        assertEquals(listOf("lifecycle.onLoad"), mine.samples().map(TimingSample::scope))
        assertEquals(listOf("tick"), other.samples().map(TimingSample::scope), "替身各自成账，互不可见")
    }

    @Test
    fun `空白与超长scope两面都显式拒绝`() {
        val bench = Bench()
        val tooLong = "s".repeat(ModTimingLedger.MAX_SCOPE_LENGTH + 1)

        listOf("", " ", "\t\n", tooLong).forEach { illegal ->
            assertFailsWith<IllegalArgumentException>("非法 scope 生产面应显式拒绝") {
                bench.ledger.record(PACKAGE_ID, illegal, 1L)
            }
            assertFailsWith<IllegalArgumentException>("非法 scope 台架应显式拒绝") {
                bench.recorder.record(illegal, 1L)
            }
        }

        var prodBlockRuns = 0
        var benchBlockRuns = 0
        assertFailsWith<IllegalArgumentException> {
            bench.ledger.measureValue(PACKAGE_ID, "") { prodBlockRuns += 1 }
        }
        assertFailsWith<IllegalArgumentException> {
            bench.recorder.measure("") { benchBlockRuns += 1 }
        }
        assertEquals(1, prodBlockRuns, "生产 measureValue 是 finally 落账才校验，block 已执行")
        assertEquals(prodBlockRuns, benchBlockRuns, "两面 measure 对非法 scope 的执行时序一致")
        assertEquals(emptyList(), bench.ledger.forPackage(PACKAGE_ID), "拒绝不得留下半笔账")
        assertEquals(emptyList(), bench.recorder.samples())
    }

    @Test
    fun `上限常量两面同值`() {
        assertEquals(ModTimingLedger.MAX_SCOPE_LENGTH, RecordingTiming.MAX_SCOPE_LENGTH)
        assertEquals(ModTimingLedger.MAX_DISTINCT_SCOPES, RecordingTiming.MAX_DISTINCT_SCOPES)
    }

    @Test
    fun `单包scope数到顶两面都WARN一次并忽略新scope`() {
        val prodLogs = mutableListOf<String>()
        val benchWarns = mutableListOf<String>()
        val bench = Bench(prodLogs, benchWarns)
        val cap = ModTimingLedger.MAX_DISTINCT_SCOPES
        repeat(cap) { index -> bench.recordBoth("scope.$index", 1L) }
        assertEquals(cap, bench.ledger.forPackage(PACKAGE_ID).size)
        assertEquals(cap, bench.recorder.samples().size)

        bench.recordBoth("scope.extra", 9L)
        assertEquals(cap, bench.ledger.forPackage(PACKAGE_ID).size, "生产面新 scope 被忽略")
        assertEquals(cap, bench.recorder.samples().size, "台架新 scope 被忽略")
        assertEquals(1, prodLogs.size)
        assertEquals(1, benchWarns.size, "两面都只 WARN 一次")
        assertTrue(prodLogs.single().contains("scope.extra") && prodLogs.single().contains("$cap"))
        assertTrue(benchWarns.single().contains("scope.extra") && benchWarns.single().contains("$cap"))

        bench.recordBoth("scope.0", 4L)
        assertEquals(2L, bench.ledger.forPackage(PACKAGE_ID).single { it.scope == "scope.0" }.count)
        assertEquals(2L, bench.recorder.samples().single { it.scope == "scope.0" }.count, "既有 scope 继续累加")
        assertEquals(bench.ledger.forPackage(PACKAGE_ID), bench.recorder.samples())
    }

    /** 对拍台：生产账本 + 台架替身各一，输入同一串；WARN 可分别回收。 */
    private class Bench(
        prodLogs: MutableList<String> = mutableListOf(),
        benchWarns: MutableList<String> = mutableListOf(),
    ) {

        val ledger = ModTimingLedger(logSink = { line -> prodLogs += line })

        val recorder = RecordingTiming(PACKAGE_ID, warn = { message -> benchWarns += message })

        fun recordBoth(scope: String, nanos: Long) {
            ledger.record(PACKAGE_ID, scope, nanos)
            recorder.record(scope, nanos)
        }
    }

    private companion object {
        const val PACKAGE_ID = "parity.timing.mod"
        const val OTHER_PACKAGE_ID = "parity.timing.other"

        /** 由固定读数序列造时钟：每次读数取下一条，越界后重复最后一条。 */
        fun clockFrom(instants: List<Long>): () -> Long {
            var index = 0
            return {
                val value = instants[minOf(index, instants.lastIndex)]
                index += 1
                value
            }
        }
    }
}
