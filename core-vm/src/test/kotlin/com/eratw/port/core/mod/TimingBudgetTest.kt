package com.eratw.port.core.mod

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单包累计耗时预算查询（`ModTimingLedger.overBudgetPackages`）的纯层判据（ModDX-3 A）。
 *
 * 钉住四件事：
 * 1. budget 为 null / 0 / 负数时恒返回空集（宿主不注入预算 = 既有行为逐字不变）；
 * 2. 累计耗时跨 scope 求和，严格大于预算才超（等于预算不算）；
 * 3. 多包隔离：只标记超预算的包；
 * 4. 纯函数：调用前后 [ModTimingLedger.snapshot] / [ModTimingLedger.forPackage] 逐字不变，
 *    查询后继续 [ModTimingLedger.record] 的聚合语义不受影响。
 */
class TimingBudgetTest {

    @Test
    fun `budget为空或非正时超预算集恒空且不改快照`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgA", "tick", 100)
        ledger.record("pkgB", "tick", 5)
        val before = ledger.snapshot()

        assertTrue(ledger.overBudgetPackages(null).isEmpty(), "budget=null 不启用告警")
        assertTrue(ledger.overBudgetPackages(0L).isEmpty(), "budget=0 不启用告警")
        assertTrue(ledger.overBudgetPackages(-1L).isEmpty(), "budget<0 不启用告警")
        assertEquals(before, ledger.snapshot(), "查询不得改写快照")
    }

    @Test
    fun `单包累计跨scope求和严格大于预算才超`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgA", "tick", 40)
        ledger.record("pkgA", "hook.F.PREFIX", 30)
        ledger.record("pkgB", "tick", 5)

        // pkgA 合计 70（跨两个 scope），pkgB 合计 5。
        assertEquals(setOf("pkgA"), ledger.overBudgetPackages(69L), "70 > 69 应超预算")
        assertTrue(ledger.overBudgetPackages(70L).isEmpty(), "恰好等于预算不算超预算")
    }

    @Test
    fun `边界值等于预算不超预算值为1时耗时1不超耗时2超`() {
        val ledger = ModTimingLedger()
        ledger.record("exact", "tick", 1)
        ledger.record("over", "tick", 2)

        assertEquals(setOf("over"), ledger.overBudgetPackages(1L), "预算=1：耗时1不超，耗时2超")
        assertEquals(emptySet<String>(), ledger.overBudgetPackages(Long.MAX_VALUE), "极高预算无超预算包")
    }

    @Test
    fun `多包隔离只标记超预算包且重复查询结果一致`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgHeavy", "tick", 1_000)
        ledger.record("pkgLight", "tick", 10)
        ledger.record("pkgZero", "tick", 0)

        val first = ledger.overBudgetPackages(100L)
        val second = ledger.overBudgetPackages(100L)
        assertEquals(setOf("pkgHeavy"), first, "只标记累计超预算的包")
        assertEquals(first, second, "同参多次调用结果一致")
    }

    @Test
    fun `纯查询不改记账语义_查询后继续record聚合正确`() {
        val ledger = ModTimingLedger()
        ledger.record("pkgA", "tick", 10)
        val forPackageBefore = ledger.forPackage("pkgA")
        val snapshotBefore = ledger.snapshot()

        assertEquals(setOf("pkgA"), ledger.overBudgetPackages(5L))

        assertEquals(forPackageBefore, ledger.forPackage("pkgA"), "forPackage 语义不变")
        assertEquals(snapshotBefore, ledger.snapshot(), "snapshot 语义不变")
        ledger.record("pkgA", "tick", 20)
        val sample = ledger.forPackage("pkgA").single()
        assertEquals(2L, sample.count, "查询后 record 照常累加次数")
        assertEquals(30L, sample.totalNanos, "查询后 record 照常累加耗时")
    }
}
