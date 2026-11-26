package com.eratw.port.core.err

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4.981 整数四则溢出钳制的定向回归（2026-11-20 溢出族，P0）。
 *
 * 权威出处：`reference/emuera-skia-4.981/.../SafeArithmetic.cs:7-65`（`SafeAdd` /
 * `SafeSubtract` / `SafeMultiply` / `SafeNegate`）与接线点 `OperatorMethod.cs:278`
 * （PlusIntInt）/ `:307`（MinusIntInt）/ `:321`（MultIntInt）/ `:763`（MinusInt）。
 *
 * 钉四件事：
 * 1. 四个算子的**饱和方向**逐条对齐 C#（加/减看左操作数符号、乘看两操作数符号是否一致、
 *    取负恒 MAX）—— 方向写错不会抛错，只会算出一个看似正常的小数，最难发现；
 * 2. 告警文案逐字对齐（含取负的**双负号**）；
 * 3. 溢出告警是「每次都打」的（4.981 `PrintWarning` 不去重），但落**独立** overflow 桶，
 *    不与 RAND 的 once 留痕挤同一个窗口；
 * 4. 变量层（`++` / `--` / `+=`）经 `VariableToken.cs:752` 的 `SafeAdd`，表达式层经
 *    `EvalContext.reportArithmeticOverflow` —— 两条路都既上屏又留痕。
 */
class SafeArithmeticOverflowTest {

    private fun warnings(): Pair<MutableList<String>, (String) -> Unit> {
        val lines = ArrayList<String>()
        return lines to { m: String -> lines.add(m) }
    }

    // ------------------------------------------------------------------
    // 一、四个算子的饱和方向（SafeArithmetic.cs:7-65）
    // ------------------------------------------------------------------

    @Test
    fun `加法按左操作数符号饱和`() {
        val (lines, warn) = warnings()
        assertEquals(Long.MAX_VALUE, SafeArithmetic.add(Long.MAX_VALUE, 1, warn))
        assertEquals(Long.MIN_VALUE, SafeArithmetic.add(Long.MIN_VALUE, -1, warn))
        // 正 + 正溢出即使结果为负也按 a > 0 取 MAX（判据是 a，不是和）。
        assertEquals(Long.MAX_VALUE, SafeArithmetic.add(Long.MAX_VALUE, Long.MAX_VALUE, warn))
        assertEquals(7L, SafeArithmetic.add(3L, 4L, warn))
        assertEquals(
            listOf(
                "整数溢出: 9223372036854775807 + 1",
                "整数溢出: -9223372036854775808 + -1",
                "整数溢出: 9223372036854775807 + 9223372036854775807",
            ),
            lines,
        )
    }

    @Test
    fun `减法按左操作数符号饱和`() {
        val (lines, warn) = warnings()
        assertEquals(Long.MIN_VALUE, SafeArithmetic.subtract(Long.MIN_VALUE, 1, warn))
        assertEquals(Long.MAX_VALUE, SafeArithmetic.subtract(Long.MAX_VALUE, -1, warn))
        assertEquals(0L, SafeArithmetic.subtract(Long.MIN_VALUE, Long.MIN_VALUE, warn))
        assertEquals(
            listOf(
                "整数溢出: -9223372036854775808 - 1",
                "整数溢出: 9223372036854775807 - -1",
            ),
            lines,
        )
    }

    @Test
    fun `乘法按两操作数符号是否一致饱和`() {
        val (lines, warn) = warnings()
        assertEquals(Long.MAX_VALUE, SafeArithmetic.multiply(Long.MAX_VALUE, 2, warn))
        assertEquals(Long.MIN_VALUE, SafeArithmetic.multiply(Long.MIN_VALUE, 2, warn))
        // 符号相反 ⇒ 负溢出 ⇒ MIN（`(a > 0) == (b > 0)` 为 false）。
        assertEquals(Long.MIN_VALUE, SafeArithmetic.multiply(Long.MAX_VALUE, -2, warn))
        // MIN * -1 必须先拦下：JVM 的 MIN / -1 会抛 ArithmeticException，不能漏出来。
        assertEquals(Long.MAX_VALUE, SafeArithmetic.multiply(Long.MIN_VALUE, -1, warn))
        assertEquals(Long.MAX_VALUE, SafeArithmetic.multiply(-1, Long.MIN_VALUE, warn))
        // 零乘任何数不告警、也不走除法判据。
        assertEquals(0L, SafeArithmetic.multiply(Long.MIN_VALUE, 0, warn))
        assertEquals(12L, SafeArithmetic.multiply(3L, 4L, warn))
        assertEquals(
            listOf(
                "整数溢出: 9223372036854775807 * 2",
                "整数溢出: -9223372036854775808 * 2",
                "整数溢出: 9223372036854775807 * -2",
                "整数溢出: -9223372036854775808 * -1",
                "整数溢出: -1 * -9223372036854775808",
            ),
            lines,
        )    }

    @Test
    fun `取负恒饱和到最大整数且文案带双负号`() {
        val (lines, warn) = warnings()
        assertEquals(Long.MAX_VALUE, SafeArithmetic.negate(Long.MIN_VALUE, warn))
        assertEquals(5L, SafeArithmetic.negate(-5L, warn))
        assertEquals(-5L, SafeArithmetic.negate(5L, warn))
        assertEquals(listOf("整数溢出: --9223372036854775808"), lines)
    }

    // ------------------------------------------------------------------
    // 二、overflow 桶：每次留痕、独立分桶、有界
    // ------------------------------------------------------------------

    @Test
    fun `overflow 桶每次留痕且与 once 家族分桶`() {
        val w = ClampWarnings()
        w.once(RAND_TOKEN_CLAMP_KIND, "RAND不能指定0以下的值(0)（已钳制为 0，不再中断运行）")
        repeat(3) { assertEquals("整数溢出: 1 + 9223372036854775807", w.overflow("整数溢出: 1 + 9223372036854775807")) }
        assertEquals(3, w.overflowCount(), "4.981 PrintWarning 不去重")
        assertEquals(3, w.overflowLines().size)
        assertEquals(1, w.lines().size, "RAND 的 once 留痕不被溢出刷掉")
    }

    @Test
    fun `overflow 桶留痕封顶但计数完整`() {
        val w = ClampWarnings()
        repeat(200) { w.overflow("整数溢出: 9223372036854775807 + 1") }
        assertEquals(64, w.overflowLines().size)
        assertEquals(200, w.overflowCount())
        assertEquals(136, w.overflowSuppressedCount())
        assertTrue(w.lines().isEmpty(), "overflow 不落通用留痕桶")
    }

    // ------------------------------------------------------------------
    // 三、变量层：++ / -- / += 走 VariableToken.cs:752 的 SafeAdd
    // ------------------------------------------------------------------

    private fun storeWithCnt(value: Long): VariableStore = VariableStore().apply {
        declareUserVar(DimParser.parse("#DIM", "CNT", isPrivate = false))
        writeInt("CNT", longArrayOf(0), value)
    }

    @Test
    fun `前置自增在最大整数饱和并上屏留痕`() {
        val store = storeWithCnt(Long.MAX_VALUE)
        val sink = ArrayList<String>()
        store.systemLineSink = { sink.add(it) }
        assertEquals(Long.MAX_VALUE, store.plusInt("CNT", longArrayOf(0), 1L))
        assertEquals(Long.MAX_VALUE, store.readInt("CNT", longArrayOf(0)))
        assertEquals(listOf("整数溢出: 9223372036854775807 + 1"), sink, "上屏一次")
        assertEquals(listOf("整数溢出: 9223372036854775807 + 1"), store.aux.clampWarnings().overflowLines())
    }

    @Test
    fun `前置自减在最小整数饱和`() {
        val store = storeWithCnt(Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, store.plusInt("CNT", longArrayOf(0), -1L))
        assertEquals(Long.MIN_VALUE, store.readInt("CNT", longArrayOf(0)))
        assertEquals(
            listOf("整数溢出: -9223372036854775808 + -1"),
            store.aux.clampWarnings().overflowLines(),
        )
    }

    @Test
    fun `后置自增返回饱和前的旧值 MAX 减一`() {
        // 4.981 OperatorMethod.cs:853-863：新值被 SafeAdd 钳成 MAX，后置返回 `新值 - 1`。
        val store = storeWithCnt(Long.MAX_VALUE)
        val vr = VarRef("CNT", listOf(IntLiteral(0L)), operandType = OperandType.INT)
        assertEquals(Long.MAX_VALUE - 1, UnaryAfter(OperatorCode.INCREMENT, vr).getIntValue(TestEvalContext(store)))
        assertEquals(Long.MAX_VALUE, store.readInt("CNT", longArrayOf(0)))
        assertEquals(1, store.aux.clampWarnings().overflowCount())
    }

    // ------------------------------------------------------------------
    // 四、表达式层：reportArithmeticOverflow 上屏 + 落 overflow 桶
    // ------------------------------------------------------------------

    @Test
    fun `表达式加溢出既上屏又落 overflow 桶`() {
        val store = VariableStore()
        val ctx = BuiltinEvalContext(TestEvalContext(store), store)
        val node = Binary(OperatorCode.PLUS, IntLiteral(Long.MAX_VALUE), IntLiteral(1L), OperandType.INT)
        assertEquals(Long.MAX_VALUE, node.getIntValue(ctx))
        assertEquals(
            listOf("整数溢出: 9223372036854775807 + 1"),
            store.aux.clampWarnings().overflowLines(),
        )
    }
}
