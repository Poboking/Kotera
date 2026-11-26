package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BH3-VMCORE 判据 ④：算术溢出与截断语义（2026-11-20 按 4.981 溢出族改钉）。
 *
 * 原版口径（4.981，`reference/emuera-skia-4.981`）：
 * * 加减乘 = `SafeArithmetic`（`SafeArithmetic.cs:7-50`），接线点
 *   `OperatorMethod.cs:278`（PlusIntInt）/ `:307`（MinusIntInt）/ `:321`（MultIntInt）：
 *   `checked` 越界 ⇒ `PrintWarning("整数溢出: a op b")` + **饱和**（`SafeAdd`/`SafeSubtract`
 *   取 `a > 0 ? MAX : MIN`；`SafeMultiply` 取 `(a > 0) == (b > 0) ? MAX : MIN`），
 *   **不再 unchecked 回绕**。这是 2026-11-20 的判据订正：旧断言钉的是 1.824
 *   `OperatorMethodManager.cs:24/:57/:71` 的回绕口径，属于版本漂移，不是回归。
 * * 整除 `DivIntInt`：4.981 `OperatorMethod.cs:374` → `SafeArithmetic.SafeDivide`
 *   （:67-77），`b == 0` 时打「零除: a / 0」警告并**返回 0**，不再中断。非零时仍是 .NET
 *   long 整除**向零截断**；`long.MinValue / -1` 仍是唯一溢出的整除，抛 OverflowException；
 * * 取余 `ModIntInt`：4.981 `OperatorMethod.cs:388` → `SafeModulo`（:79-88）同法钳制；
 * * 取负 `MinusInt`（`OperatorMethod.cs:753-765`）→ `SafeNegate`（:52-65）：`-MIN`
 *   告警并返回 **MAX**（恒 MAX，不看符号）；
 * * `POWER` **语句**（spec: Process.cs:1350-1370）与 `POWER` **方法**
 *   （spec: FunctionMethodCreator.cs:4172-4190）同构：`Math.Pow` 双精度幂 →
 *   NaN/Infinity/`|d| >= 9.223372036854776E+18` 三闸抛错 → `(long)d` 向零截断。
 *
 * 三条通道（表达式池 `_EVAL_INT` / 寄存器 `_ADD` 一族 / 栈码 `_STK_ADD` 一族）同语义
 * （ADR-001），本文件对寄存器与栈码各钉一遍。
 */
class VmArithmeticAuditTest {

    private fun arith(op: String, l: Long, r: Long, host: VmHost = StringHost()): Long {
        val p = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, $l
              _SET_INT R1, $r
              $op R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        return VirtualMachine(p, host).run()
    }

    /** 栈码支：`l r <op>` 弹两个压一个。 */
    private fun stackArith(op: String, l: Long, r: Long, host: VmHost = StringHost()): Long {
        val p = Assembler.assemble(
            """
            .func main slots=2
              _STK_PUSH_IMM $l
              _STK_PUSH_IMM $r
              $op
              _STK_POP_SLOT_I R0
              _RET R0
            """.trimIndent(),
        )
        return VirtualMachine(p, host).run()
    }

    private fun neg(v: Long, host: VmHost = StringHost()): Long {
        val p = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, $v
              _NEG R1, R0
              _RET R1
            """.trimIndent(),
        )
        return VirtualMachine(p, host).run()
    }

    // ------------------------------------------------------------------
    // 4.981 SafeArithmetic 饱和（SafeArithmetic.cs:7-50）
    // ------------------------------------------------------------------

    @Test
    fun `加减乘按 4_981 SafeArithmetic 饱和并告警`() {
        // ADD：SafeAdd 的饱和判据是**左操作数**（a > 0 ? MAX : MIN）。
        val addHost = StringHost()
        assertEquals(Long.MAX_VALUE, arith("_ADD", Long.MAX_VALUE, 1, addHost), "MAX+1 应饱和成 MAX")
        assertEquals("整数溢出: 9223372036854775807 + 1\n", addHost.output.toString())
        assertEquals(Long.MIN_VALUE, arith("_ADD", Long.MIN_VALUE, -1), "MIN+(-1) 应饱和成 MIN")
        assertEquals(Long.MAX_VALUE, arith("_ADD", Long.MAX_VALUE, Long.MAX_VALUE), "MAX+MAX 左操作数为正 ⇒ MAX")

        // SUB：同样看左操作数符号。
        val subHost = StringHost()
        assertEquals(Long.MIN_VALUE, arith("_SUB", Long.MIN_VALUE, 1, subHost), "MIN-1 应饱和成 MIN")
        assertEquals("整数溢出: -9223372036854775808 - 1\n", subHost.output.toString())
        val subHost2 = StringHost()
        assertEquals(Long.MAX_VALUE, arith("_SUB", Long.MAX_VALUE, -1, subHost2), "MAX-(-1) 应饱和成 MAX")
        assertEquals("整数溢出: 9223372036854775807 - -1\n", subHost2.output.toString())
        assertEquals(0L, arith("_SUB", Long.MIN_VALUE, Long.MIN_VALUE), "MIN-MIN = 0 不触发")

        // MUL：符号相同 ⇒ MAX，符号相反 ⇒ MIN（判据是两操作数符号是否一致）。
        val mulHost = StringHost()
        assertEquals(Long.MAX_VALUE, arith("_MUL", Long.MAX_VALUE, 2, mulHost), "MAX*2 应饱和成 MAX")
        assertEquals("整数溢出: 9223372036854775807 * 2\n", mulHost.output.toString())
        assertEquals(Long.MIN_VALUE, arith("_MUL", Long.MIN_VALUE, 2), "MIN*2 应饱和成 MIN（符号相反）")
        assertEquals(Long.MAX_VALUE, arith("_MUL", 4611686018427387904L, 4), "2^62*4 应饱和成 MAX")
        assertEquals(Long.MAX_VALUE, arith("_MUL", Long.MIN_VALUE, -1), "MIN*(-1) 两负 ⇒ MAX")
    }

    @Test
    fun `栈码三条算术通道与寄存器支同口径`() {
        val addHost = StringHost()
        assertEquals(Long.MAX_VALUE, stackArith("_STK_ADD", Long.MAX_VALUE, 1, addHost))
        assertEquals("整数溢出: 9223372036854775807 + 1\n", addHost.output.toString())

        val subHost = StringHost()
        assertEquals(Long.MIN_VALUE, stackArith("_STK_SUB", Long.MIN_VALUE, 1, subHost))
        assertEquals("整数溢出: -9223372036854775808 - 1\n", subHost.output.toString())

        val mulHost = StringHost()
        assertEquals(Long.MIN_VALUE, stackArith("_STK_MUL", Long.MIN_VALUE, 2, mulHost))
        assertEquals("整数溢出: -9223372036854775808 * 2\n", mulHost.output.toString())

        // 正常值不告警、不回绕。
        val okHost = StringHost()
        assertEquals(6L, stackArith("_STK_ADD", 1, 5, okHost))
        assertEquals("", okHost.output.toString())
    }

    @Test
    fun `取负对最小整数饱和到最大整数而不是回绕`() {
        // 4.981 MinusInt（OperatorMethod.cs:753-765）→ SafeNegate（SafeArithmetic.cs:52-65）：
        // 恒返回 long.MaxValue；文案是 C# 插值 `-{a}` 拼出的双负号。
        val host = StringHost()
        assertEquals(Long.MAX_VALUE, neg(Long.MIN_VALUE, host), "NEG(MIN) 应饱和成 MAX")
        assertEquals("整数溢出: --9223372036854775808\n", host.output.toString())
        assertEquals(1L, neg(-1), "非 MIN 不触发")
        assertEquals(-5L, neg(5))
    }

    // ------------------------------------------------------------------
    // 整除 / 取余：向零截断 + 余数符号随被除数（C# `/` 与 `%`）
    // ------------------------------------------------------------------

    @Test
    fun `整除向零截断且余数符号随被除数`() {
        // C# long `/` 与 `%`（OperatorMethodManager.cs:136/:155）与 JVM ldiv/lrem 同语义。
        assertEquals(-3L, arith("_DIV", -7, 2), "-7/2 应向零截断成 -3")
        assertEquals(-1L, arith("_MOD", -7, 2), "-7%2 余数符号随被除数 = -1")
        assertEquals(-3L, arith("_DIV", 7, -2), "7/-2 应向零截断成 -3")
        assertEquals(1L, arith("_MOD", 7, -2), "7%-2 余数符号随被除数 = 1")
        assertEquals(3L, arith("_DIV", -7, -2), "-7/-2 = 3")
        assertEquals(-1L, arith("_MOD", -7, -2), "-7%-2 余数符号随被除数 = -1")
        assertEquals(-2L, arith("_DIV", -8, 3), "-8/3 向零截断成 -2（不是 -3）")
        assertEquals(-2L, arith("_MOD", -8, 3), "-8%3 = -2")
        assertEquals(3L, arith("_DIV", 7, 2))
        assertEquals(1L, arith("_MOD", 7, 2))
    }

    @Test
    fun `最小整数除以负一抛 CodeEE 而取余得零`() {
        // C#：MinValue / -1 是 .NET 唯一溢出整除（OverflowException，
        // spec: OperatorMethodManager.cs:136）；取余两边都得 0 不抛（:155）。
        val min = "-9223372036854775808"
        val p = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, $min
              _SET_INT R1, -1
              _DIV R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        val e = assertFailsWith<CodeEE> { VirtualMachine(p, StringHost()).run() }
        assertTrue(e.message!!.contains("除算溢出"), "实际文案: ${'$'}{e.message}")

        val pm = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, $min
              _SET_INT R1, -1
              _MOD R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        assertEquals(0L, VirtualMachine(pm, StringHost()).run(), "MinValue % -1 = 0")
    }

    @Test
    fun `除零与取余零按 4_981 钳制为零并各打一行警告`() {
        // 4.981 SafeArithmetic.cs:67-84：b == 0 → PrintWarning + return 0，不抛 CodeEE。
        // 这是 2026-11-12 版本漂移订正：旧断言钉的是 1.824
        // OperatorMethodManager.cs:132-135 / :151-154 的 DivideByZero 硬错。
        val divHost = StringHost()
        assertEquals(0L, arith("_DIV", 10, 0, divHost), "_DIV 钳制返回 0")
        assertEquals("零除: 10 / 0\n", divHost.output.toString(), "警告带被除数与运算符")

        val modHost = StringHost()
        assertEquals(0L, arith("_MOD", 10, 0, modHost), "_MOD 钳制返回 0")
        assertEquals("零除: 10 \\ 0\n", modHost.output.toString(), "取余支按 4.981 用反斜杠")
    }

    @Test
    fun `非零除数仍按 C# 语义且 MinValue 溢出照抛`() {
        // 零回归：钳制只作用于 b == 0，不得碰正常算术（MinValue / -1 的硬错在
        // `最小整数除以负一抛 CodeEE 而取余得零` 里另钉）。
        assertEquals(3L, arith("_DIV", 7, 2))
        assertEquals(1L, arith("_MOD", 7, 2))
        assertEquals(0L, arith("_DIV", 0, 7))
    }

    // ------------------------------------------------------------------
    // POWER：语句（Process.cs:1350-1370）+ 方法（FunctionMethodCreator.cs:4172-4190）
    // ------------------------------------------------------------------

    /** 走真实语句层执行 `POWER dest, x, y`，返回 dest 读数。 */
    private fun powerStmt(x: Long, y: Long): Long {
        val store = VariableStore()
        val ctx = ExecContext(variables = store, eval = TestEvalContext(store))
        StatementExecutor().execute(
            instr(
                FunctionCode.POWER,
                SpPowerArgument(
                    VarRef("RESULT", listOf(IntLiteral(0L)), operandType = OperandType.INT),
                    IntLiteral(x),
                    IntLiteral(y),
                ),
            ),
            ctx,
        )
        return store.readInt("RESULT", longArrayOf(0))
    }

    private fun powerMethod(x: Long, y: Long): Long {
        val store = VariableStore()
        val ctx = BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {})
        return (ctx.callFunction("POWER", listOf(Value.IntV(x), Value.IntV(y))) as Value.IntV).v
    }

    @Test
    fun `POWER 语句按原版双精度幂与三闸越界抛错`() {
        assertEquals(1024L, powerStmt(2, 10))
        assertEquals(4611686018427387904L, powerStmt(2, 62), "2^62 在 Int64 内")
        assertEquals(-8L, powerStmt(-2, 3), "负底整数幂")
        assertEquals(9L, powerStmt(3, 2))
        // 2^63 恰为越界闸值（>= 9.223372036854776E+18）—— spec: Process.cs:1364-1367
        assertFailsWith<CodeEE> { powerStmt(2, 63) }
        // 无穷大闸 —— spec: Process.cs:1360-1363
        assertFailsWith<CodeEE> { powerStmt(2, 100) }
    }

    @Test
    fun `POWER 方法与语句同口径`() {
        assertEquals(1024L, powerMethod(2, 10))
        assertEquals(4611686018427387904L, powerMethod(2, 62))
        assertEquals(1L, powerMethod(0, 0))
        assertFailsWith<CodeEE> { powerMethod(2, 63) }
        assertFailsWith<CodeEE> { powerMethod(2, 100) }
    }

    // ------------------------------------------------------------------
    // ROUND：蛇版 4.981 数学扩展（Creator.Method.cs:4544-4577，Creator.cs:100 注册）；
    // 语料 261111 的 DLC/小游戏/锻税.ERB 四处实算使用（语料换代 2026-11-14 落地）。
    // ------------------------------------------------------------------

    /** 整型路：`ctx.callFunction("ROUND", [IntV])` 读数。 */
    private fun roundMethod(x: Long): Long =
        (roundCall(Value.IntV(x)) as Value.IntV).v

    /** 浮点路：任一实参 FloatV ⇒ 整体返回 FloatV（HasFloatArg 分流，:4570-4576）。 */
    private fun roundMethodF(x: Double): Double =
        (roundCall(Value.FloatV(x)) as Value.FloatV).v

    private fun roundCall(arg: Value): Value {
        val store = VariableStore()
        val ctx = BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {})
        return ctx.callFunction("ROUND", listOf(arg))
    }

    @Test
    fun `ROUND 整型路原样返回`() {
        // 整型实参按 ToDouble 后 Math.Round 恒等（整数无小数位），返回 SingleLongTerm（:4574-4575）。
        assertEquals(2L, roundMethod(2))
        assertEquals(0L, roundMethod(0))
        assertEquals(-3L, roundMethod(-3))
        assertEquals(1234567890123L, roundMethod(1234567890123L))
    }

    @Test
    fun `ROUND 浮点路 half 远离零`() {
        // MidpointRounding.AwayFromZero（:4562）：±0.5/±2.5 这类可精确表示的 half 一律远离零，
        // 与 java.lang.Math.round 的「half 向上」不同 —— 这正是用 BigDecimal+HALF_UP 复刻的原因。
        assertEquals(3.0, roundMethodF(2.5), "2.5 → 3（远离零）")
        assertEquals(-3.0, roundMethodF(-2.5), "-2.5 → -3（远离零）")
        assertEquals(1.0, roundMethodF(0.5), "0.5 → 1")
        assertEquals(-1.0, roundMethodF(-0.5), "-0.5 → -1")
        assertEquals(2.0, roundMethodF(2.4))
        assertEquals(3.0, roundMethodF(2.6))
        assertEquals(-2.0, roundMethodF(-2.4))
        assertEquals(-3.0, roundMethodF(-2.6))
    }

    @Test
    fun `ROUND 浮点路非 half 精确值按 BigDecimal 全精度判定`() {
        // 0.49999999999999994 是小于 0.5 的最大 double：java.lang.Math.round 会把它错抬成 1，
        // BigDecimal(d) 是 double 的精确十进制展开 ⇒ HALF_UP 判 < 0.5 → 0，与 C# Math.Round 一致。
        assertEquals(0.0, roundMethodF(0.49999999999999994))
        assertEquals(1.0, roundMethodF(0.5000000000000001))
        // 语料实算形态：锻税.ERB:3250 `TOINT(ROUND(值 * 1.0 * 加值 / 100))` 的一般非 half 值。
        assertEquals(137.0, roundMethodF(136.6))
    }

    @Test
    fun `ROUND 整型路 Int64 越界抛错`() {
        // GetIntValue（:4558-4566）：Math.Round 后 (dret >= long.MaxValue) || (dret <= long.MinValue)
        // 抛 CodeEE —— ToDouble(Long.MAX_VALUE) = 2^63 = 9.223372036854776E18，恰撞上界 ⇒ 抛。
        assertFailsWith<CodeEE> { roundMethod(Long.MAX_VALUE) }
        assertFailsWith<CodeEE> { roundMethod(Long.MIN_VALUE) }
    }
}
