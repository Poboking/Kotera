package com.eratw.port.core.err

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.StringHost
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 除零语义与**取证面**（2026-11-09 lane bug-divzero 建档；2026-11-12 按 4.981 重写判据）。
 *
 * ## 为什么改判据
 *
 * 玩家上报「除数为0的除法运算 @ 催眠発動 行764」（0.2.39 / Xiaomi 24129PN74C）里，
 * 该物理行 `LOCAL:3 = MAX(0, …)` 有 **6 处** `X / MAXBASE:TARGET:*`，取证只能猜是哪一项
 * 为 0 —— 于是 11-09 波给整数除零补了 operandA/operandB/exprText 三件取证。
 *
 * 但复刻目标蛇版 4.981 **根本不会中断**：`OperatorMethod.cs:374/:388` 的
 * `DivIntInt` / `ModIntInt` 已改走 `SafeArithmetic.SafeDivide` / `SafeModulo`
 * （:67-84），`b == 0` 时 `PrintWarning("零除: …")` 并**返回 0**。移植侧仍停在 1.824
 * 的抛 CodeEE，才是这批上报的真正根因（引擎侧版本漂移，不是语料缺状态初始化）。
 *
 * ⇒ 整数通道的判据从「抛错 + 三件取证」改成「**钳制 + 告警 + 求值序 + 栈平衡**」；
 * 三件取证本身仍由**浮点除零**与 `CodeEE` 转换点保留，下面留作反向证明。
 *
 * ## 反向证明（本轮**不**改）
 * * 浮点除零仍抛 `ExprEvalException`（`OperatorMethod.cs:987` / `:1137` 的
 *   `DivFloatFloat` / `DivMixedFloat`；`SafeFloatDivide` 是未接线的死代码）；
 * * 非零除数的 C# 整除/取余语义、`long.MinValue / -1` 溢出硬错不变；
 * * `CodeEE` 的取证字段不进 message、不参与行号换算。
 */
class ArithmeticErrorContextTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 最小变量池：**必须有一个角色**（[VariableStore.addPseudoCharacter]）。
     *
     * 角色数组变量（`MAXBASE` / `BASE` / `CFLAG` …）的下标 0 是**角色登记号**，空池里
     * CHARANUM = 0 ⇒ 读 `MAXBASE:0:12` 会先抛「超出角色登记编号的最大范围」，
     * 那条 CodeEE 会把零除路径整个盖掉（本用例第一版就是这么红的）。
     */
    private fun store(): VariableStore = VariableStore().apply { addPseudoCharacter() }

    private fun intVar(name: String, vararg idx: Long): VarRef =
        VarRef(name, idx.map { IntLiteral(it) }, operandType = OperandType.INT)

    /** 记录系统行输出的求值语境：钳制告警走 `EvalContext.printSystemLine`。 */
    private class RecordingEvalContext(private val base: TestEvalContext) : EvalContext by base {
        val systemLines = ArrayList<String>()

        override fun printSystemLine(message: String) {
            systemLines.add(message)
        }
    }

    private fun div(left: Expr, right: Expr): Binary =
        Binary(OperatorCode.DIV, left, right, OperandType.INT)

    private fun mod(left: Expr, right: Expr): Binary =
        Binary(OperatorCode.MOD, left, right, OperandType.INT)

    private fun storeWithCnt(value: Long): VariableStore = store().apply {
        declareUserVar(DimParser.parse("#DIM", "CNT", isPrivate = false))
        writeInt("CNT", longArrayOf(0), value)
    }

    // ------------------------------------------------------------------
    // 一、整型零除：4.981 钳制为零 + 每次警告（不中断）
    // ------------------------------------------------------------------

    @Test
    fun `整型除零钳制为零且警告点名被除数`() {
        // 形态照抄上报行：`… / MAXBASE:TARGET:情緒`（TARGET 位 0、情绪位 12 的显式下标形态）。
        // MAXBASE 是角色 2D 变量，未初始化即 0 —— 4.981 下这里不再抛错，脚本继续跑。
        val ctx = RecordingEvalContext(TestEvalContext(store()))
        assertEquals(0L, div(IntLiteral(10L), intVar("MAXBASE", 0L, 12L)).getIntValue(ctx))
        assertEquals(listOf("零除: 10 / 0"), ctx.systemLines, "SafeDivide.cs:68-77 的 PrintWarning 文案")
    }

    @Test
    fun `整型取模零同口径但警告用反斜杠`() {
        val ctx = RecordingEvalContext(TestEvalContext(store()))
        assertEquals(0L, mod(IntLiteral(10L), intVar("MAXBASE", 0L, 12L)).getIntValue(ctx))
        assertEquals(listOf("零除: 10 \\ 0"), ctx.systemLines, "SafeModulo.cs:80-88 用反斜杠，与除法可区分")
    }

    @Test
    fun `嵌套二元表达式零除仍为零且左子树照常求值`() {
        val ctx = RecordingEvalContext(TestEvalContext(store()))
        // (7 - 5) / (3 - 3) ⇒ 左值 2、除数 0。
        val left = Binary(OperatorCode.MINUS, IntLiteral(7L), IntLiteral(5L), OperandType.INT)
        val right = Binary(OperatorCode.MINUS, IntLiteral(3L), IntLiteral(3L), OperandType.INT)
        assertEquals(0L, div(left, right).getIntValue(ctx))
        assertEquals(listOf("零除: 2 / 0"), ctx.systemLines)
    }

    @Test
    fun `每次零除都告警不去重`() {
        // 4.981 的 SafeDivide 是 PrintWarning 不去重（`clampedWarned` 只属于 RAND 家族）。
        val ctx = RecordingEvalContext(TestEvalContext(store()))
        val node = div(IntLiteral(10L), IntLiteral(0L))
        repeat(3) { assertEquals(0L, node.getIntValue(ctx)) }
        assertEquals(List(3) { "零除: 10 / 0" }, ctx.systemLines)
    }

    // ------------------------------------------------------------------
    // 二、求值序：4.981 改成左先右后（OperatorMethod.cs:374/:388）
    // ------------------------------------------------------------------

    @Test
    fun `带副作用的左子树按 4_981 求值且只求值一次`() {
        val store = storeWithCnt(5L)
        val ctx = RecordingEvalContext(TestEvalContext(store))
        // 前置 ++ 把 CNT 从 5 写成 6，并返回新值 6（Expr.Unary.changeValue）。
        // 1.824 是「先取除数判 0 后抛错」，左子树根本不求值；4.981 的
        // SafeDivide(arguments[0], arguments[1]) 是 C# 实参左到右 ⇒ 左子树正常求值一次。
        val bumped = Unary(OperatorCode.INCREMENT, intVar("CNT", 0L))
        assertEquals(0L, div(bumped, IntLiteral(0L)).getIntValue(ctx))
        assertEquals(6L, store.readInt("CNT", longArrayOf(0)), "不求值会留 5，重复求值会变 7")
        assertEquals(listOf("零除: 6 / 0"), ctx.systemLines, "告警带的是已求值的左操作数值")
    }

    @Test
    fun `取负最小整数在钳制路径上不被额外补求值`() {
        val store = storeWithCnt(Long.MIN_VALUE)
        val ctx = RecordingEvalContext(TestEvalContext(store))
        val negated = Unary(OperatorCode.MINUS, intVar("CNT", 0L))
        // MinusInt 自己打一行「取负不生效」（Expr.kt:621），零除再打一行。
        // 钳制路径若还保留「为取证补读左子树」，这里会多出第三行 —— 那是旧 bug-divzero
        // 波最容易写坏的地方，现在没有取证补读，恰好两行。
        assertEquals(0L, div(negated, IntLiteral(0L)).getIntValue(ctx))
        assertEquals(2, ctx.systemLines.size, "不得为取证重复求值：${ctx.systemLines}")
        assertTrue(ctx.systemLines.last().startsWith("零除: "), ctx.systemLines.toString())
    }

    @Test
    fun `纯左值钳制后变量不变`() {
        val store = storeWithCnt(7L)
        val ctx = RecordingEvalContext(TestEvalContext(store))
        assertEquals(0L, div(intVar("CNT", 0L), IntLiteral(0L)).getIntValue(ctx))
        assertEquals(listOf("零除: 7 / 0"), ctx.systemLines)
        assertEquals(7L, store.readInt("CNT", longArrayOf(0)), "纯读不得改变变量")
    }

    // ------------------------------------------------------------------
    // 三、反向证明：浮点除零仍抛错，取证面只在这一支
    // ------------------------------------------------------------------

    @Test
    fun `浮点除零仍抛 ExprEvalException 且只给表达式原文`() {
        val ctx = TestEvalContext(store())
        val node = Binary(OperatorCode.DIV, FloatLiteral(10.5), FloatLiteral(0.0), OperandType.FLOAT)
        val e = assertFailsWith<ExprEvalException> { node.getFloatValue(ctx) }
        assertEquals(DIVIDE_BY_ZERO_TEXT, e.message, "4.981 的钳制只覆盖整数，浮点仍是官方硬错")
        assertNull(e.operandA, "10.5 折成 Long 会变成 10，误导取证 ⇒ 留 null")
        assertNull(e.operandB)
        assertEquals("10.5 / 0.0", e.exprText)
    }

    @Test
    fun `非除零错误不带取证字段`() {
        val ctx = TestEvalContext(store())
        // 字符串乘负值：另一条运算符错误（Error.MultiplyNegativeToStr），与除零无关。
        val node = Binary(OperatorCode.MULT, StrLiteral("あ"), IntLiteral(-1L), OperandType.STR)
        val e = assertFailsWith<ExprEvalException> { node.getStrValue(ctx) }
        assertEquals("字符串无法与负值(-1)进行相乘运算", e.message)
        assertNull(e.operandA, "非算术取证错误不许带操作数")
        assertNull(e.operandB)
        assertNull(e.exprText, "非算术取证错误不许带表达式原文")
    }

    // ------------------------------------------------------------------
    // 四、三条编译通道（表达式池 / 寄存器 / 栈码）语义必须一致
    // ------------------------------------------------------------------

    @Test
    fun `表达式池通道同样钳制且告警走系统行`() {
        // 设备上真实走的那条：erbcc 把表达式塞进 expr 池，_EVAL_INT 求值。
        val ctx = RecordingEvalContext(TestEvalContext(store()))
        val program = Assembler.assemble(
            """
            .func main slots=2
              _EVAL_INT ${'$'}0, R0
              _HALT
            """.trimIndent(),
            exprs = listOf(div(IntLiteral(10L), intVar("MAXBASE", 0L, 12L))),
        )
        val t = runCatching { VirtualMachine(program, StringHost(ctx)).run() }.exceptionOrNull()
        assertEquals(null, t, "_EVAL_INT 零除不得抛 CodeEE，实际 ${t?.javaClass?.name}: ${t?.message}")
        assertEquals(listOf("零除: 10 / 0"), ctx.systemLines)
    }

    @Test
    fun `寄存器支钳制且结果槽写零`() {
        val p = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, 10
              _SET_INT R1, 0
              _DIV R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        val host = StringHost()
        assertEquals(0L, VirtualMachine(p, host).run(), "_RET R2 应回钳制值 0")
        assertEquals("零除: 10 / 0\n", host.output.toString())
    }

    @Test
    fun `栈码支钳制且保持栈平衡`() {
        // 关键回归：旧实现抛错时把被除数留在栈上（peek 取证）无所谓；钳制后不中断，
        // 若只弹除数不弹被除数，_STK_POP_SLOT_I R0 会读到 10 而不是 0。
        val div = Assembler.assemble(
            """
            .func main slots=2
              _STK_PUSH_IMM 10
              _STK_PUSH_IMM 0
              _STK_DIV
              _STK_POP_SLOT_I R0
              _RET R0
            """.trimIndent(),
        )
        val divHost = StringHost()
        assertEquals(0L, VirtualMachine(div, divHost).run(), "栈深必须与正常路径一致")
        assertEquals("零除: 10 / 0\n", divHost.output.toString())

        val mod = Assembler.assemble(
            """
            .func main slots=2
              _STK_PUSH_IMM 10
              _STK_PUSH_IMM 0
              _STK_MOD
              _STK_POP_SLOT_I R0
              _RET R0
            """.trimIndent(),
        )
        val modHost = StringHost()
        assertEquals(0L, VirtualMachine(mod, modHost).run(), "_STK_MOD 同样要栈平衡")
        assertEquals("零除: 10 \\ 0\n", modHost.output.toString())
    }

    // ------------------------------------------------------------------
    // 五、CodeEE 本体：取证字段不进 message / 不进行号换算
    // ------------------------------------------------------------------

    @Test
    fun `取证字段不改变 CodeEE 的文案与行号换算`() {
        // 纯构造用例：浮点除零的 ExprEvalException → CodeEE 转换点仍要透传这三件，
        // 本用例钉的是「取证字段一律不进 message / 不改 displayLine / 不进去重签名」。
        val withContext = CodeEE(
            DIVIDE_BY_ZERO_TEXT,
            funcName = "催眠発動",
            line = 764, // 0 基下标（上报行号口径）
            pc = 7,
            scriptFile = "催眠.ERB",
            operandA = null,
            operandB = 0L,
            exprText = "X / MAXBASE:0:12",
        )
        val withoutContext = CodeEE(DIVIDE_BY_ZERO_TEXT, "催眠発動", 764, 7, scriptFile = "催眠.ERB")
        assertEquals(withoutContext.message, withContext.message, "取证字段一律不进 message")
        assertEquals(765, withContext.displayLine, "1 基对外行号 = 0 基 + 1（erbDisplayLine 唯一出口）")
        assertEquals(withoutContext.displayLine, withContext.displayLine)
        assertEquals(withoutContext.funcName, withContext.funcName)
        assertEquals(withoutContext.line, withContext.line)
        assertEquals(withoutContext.pc, withContext.pc)
        assertTrue(withContext.exprText!!.contains("MAXBASE:0:12"))
    }
}
