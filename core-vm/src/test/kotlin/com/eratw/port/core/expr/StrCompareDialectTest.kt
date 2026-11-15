package com.eratw.port.core.expr

import com.eratw.port.core.compat.StringCompareDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * ENGC-4 字符串 `>=`/`<=` 方言闸（B5）。
 *
 * 四个引擎的取值（真源 = `specs/engine-profiles.yaml` 的 `dialect.string_compare_ge_le`，
 * 探针读数见 `dialect.probe` 的 txt00..txt02）：
 * - v24：两处都编译成 `compare < 0`（`OperatorMethod.cs:487` / `:518`，官方 bug）；
 * - em / snake：`c >= 0` / `c <= 0`（em `:478` / `:509`；snake `:547` / `:578`）；
 * - cs：两处均 `< 0`（`OperatorMethodManager.cs:331` / `:367`），与 v24 同派。
 *
 * 缺省 [StringCompareDialect.FIXED] = 4.981 实跑口径；本闸同时钉「缺省不变」与
 * 「legacy 档真的翻面」，并证明方言不外溢到 `EQUAL`/`GREATER`/`LESS` 与 `CASE … TO` 区间判定。
 */
class StrCompareDialectTest {
    private class DialectContext(dialect: StringCompareDialect) : TestContext() {
        override val stringCompareDialect: StringCompareDialect = dialect
    }

    private fun ev(op: String, a: String, b: String, dialect: StringCompareDialect): Long =
        parseOrThrow(listOf(s(a), op(op), s(b))).getIntValue(DialectContext(dialect))

    private fun fixed(op: String, a: String, b: String): Long = ev(op, a, b, StringCompareDialect.FIXED)
    private fun legacy(op: String, a: String, b: String): Long =
        ev(op, a, b, StringCompareDialect.LEGACY_BOTH_LT)

    @Test
    fun `缺省语境是 fixed 档（4_981 实跑口径，eraTW 零干扰）`() {
        // TestContext 不覆写 ⇒ 走 EvalContext 的缺省 getter，必须等于 4.981 口径。
        val ctx = TestContext()
        assertEquals(StringCompareDialect.FIXED, ctx.stringCompareDialect)
        assertEquals(1L, parseOrThrow(listOf(s("a"), op(">="), s("a"))).getIntValue(ctx))
        assertEquals(1L, parseOrThrow(listOf(s("a"), op("<="), s("a"))).getIntValue(ctx))
    }

    @Test
    fun `fixed 档探针读数与 em snake 一致`() {
        // dialect.probe.readings: txt00..txt02 = 1, 0, 1（em_ee / snake 同值）。
        assertEquals(1L, fixed(">=", "a", "a"))
        assertEquals(0L, fixed(">=", "a", "b"))
        assertEquals(1L, fixed(">=", "b", "a"))
        assertEquals(1L, fixed("<=", "a", "a"))
        assertEquals(1L, fixed("<=", "a", "b"))
        assertEquals(0L, fixed("<=", "b", "a"))
    }

    @Test
    fun `legacy 档探针读数与 v24 一致（两处都按 compare 小于零）`() {
        // dialect.probe.readings: txt00..txt02 = 0, 1, 0（v24）。
        assertEquals(0L, legacy(">=", "a", "a"))
        assertEquals(1L, legacy(">=", "a", "b"))
        assertEquals(0L, legacy(">=", "b", "a"))
        assertEquals(0L, legacy("<=", "a", "a"))
        assertEquals(1L, legacy("<=", "a", "b"))
        assertEquals(0L, legacy("<=", "b", "a"))
    }

    @Test
    fun `方言不明外溢到等于大于小于`() {
        // v24 只改了 GE/LE 两个重载（OperatorMethod.cs:487/:518）；其余四个比较保持不变。
        for (op in listOf("==", "!=", ">", "<")) {
            for ((a, b) in listOf("a" to "a", "a" to "b", "b" to "a")) {
                assertEquals(fixed(op, a, b), legacy(op, a, b), "$op($a,$b) 不应随方言变化")
            }
        }
    }

    @Test
    fun `方言不明外溢到 CASE TO 区间判定`() {
        // SELECTCASE … CASE lo TO hi 走 CaseExpression 的独立判定（CaseExpression.cs:62-70），
        // 装配成 NOT(a > b)，不读 [EvalContext.stringCompareDialect]。
        val range = strCaseRange(StrLiteral("b"), StrLiteral("a"), StrLiteral("c"), 1)
        assertEquals(1L, range.getIntValue(DialectContext(StringCompareDialect.FIXED)))
        assertEquals(1L, range.getIntValue(DialectContext(StringCompareDialect.LEGACY_BOTH_LT)))
        val outside = strCaseRange(StrLiteral("z"), StrLiteral("a"), StrLiteral("c"), 1)
        assertEquals(0L, outside.getIntValue(DialectContext(StringCompareDialect.FIXED)))
        assertEquals(0L, outside.getIntValue(DialectContext(StringCompareDialect.LEGACY_BOTH_LT)))
    }

    @Test
    fun `反向证明 两档在三组探针读数上全部互异`() {
        val readings = listOf(">=" to ("a" to "a"), ">=" to ("a" to "b"), "<=" to ("a" to "a"))
        for ((op, pair) in readings) {
            val (a, b) = pair
            assertNotEquals(fixed(op, a, b), legacy(op, a, b), "$op($a,$b) 两档应互异")
        }
    }
}
