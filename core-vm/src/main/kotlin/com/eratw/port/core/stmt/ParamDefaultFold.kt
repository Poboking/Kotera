package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Unary

/**
 * 形参默认值的**编译期常量折叠**（口径 = 原版装载期 `Restructure`：
 * `def = term.Restructure(exm) as SingleTerm`，spec: ErbLoader.cs:724-752）。
 *
 * 从 [LineParser] 拆出来（该类已顶 detekt `LargeClass`）——这里只做纯函数折叠，
 * 不碰符号表：常量名查表（[LineParser] 的 `dimConsts` / `strConsts`）仍由调用方做。
 * 顶层函数而非 object：core 层铁律禁静态可变全局，`RuntimeDiagnosticsTest` 的
 * 「源码不读墙钟且无静态可变全局」逐文件扫顶层 object 并判红。
 *
 * ## 折哪些形态
 *
 * - 字面量原样返回（[IntLiteral] / [StrLiteral] / [FloatLiteral]）。
 * - 一元 `-` / `+` / `~`：`-1`、`-1.5`、`~0`。
 * - **浮点四则**：任一侧为浮点的 `+ - * /` 按 double 求值（`1.5 + 1` ⇒ `2.5`）。
 *   [com.eratw.port.core.expr.Parser] 的浮点提升表里也只有这四则参与浮点算术
 *   （`%` 按 g5 收紧，不折 FLOAT），比较式结果是 INT、不在此列。
 *
 * ## 为什么不许「折不出就猜」
 *
 * 默认值折不出来时原版是 `ArgCanOnlyConst` 判错（ErbLoader.cs:726-727）；本侧保持
 * 「不注入」（返回 null，调用方登记原文由装载报告带出），省略位停在帧槽默认值。
 * 一旦让调用方按 0 顶替——浮点默认值就会**静默变 0**，这正是本条缺陷的形态。
 *
 * ## 判词来源（4.981 桌面宿主 `--CliDebug`，`1824+v24+EMv18+EEv56+Skiav16`）
 *
 * - `@F_NEG(ARG, PNEG = -1.5)` + `CALL F_NEG, 0` ⇒ -15（负数浮点默认值）
 * - `@F_EXPR(ARG, PEXP = 1.5 + 1)` + `CALL F_EXPR, 0` ⇒ 25（常量算术式）
 * - `@F(ARGF:1 = 2.5)` ⇒ 2.5（纯字面量，早已支持）
 */
internal fun paramDefaultLiteral(e: Expr): Expr? = when (e) {
    is IntLiteral, is StrLiteral, is FloatLiteral -> e
    is Unary -> paramDefaultUnary(e)
    is Binary -> paramDefaultFloatArithmetic(e)
    else -> null
}

private fun paramDefaultUnary(e: Unary): Expr? {
    val operand = paramDefaultLiteral(e.operand) ?: return null
    return when (e.op) {
        OperatorCode.PLUS -> operand
        OperatorCode.MINUS -> paramDefaultNegate(operand)
        OperatorCode.BIT_NOT -> (operand as? IntLiteral)?.let { IntLiteral(it.value.inv()) }
        else -> null
    }
}

private fun paramDefaultNegate(operand: Expr): Expr? = when (operand) {
    is IntLiteral -> IntLiteral(-operand.value)
    is FloatLiteral -> FloatLiteral(-operand.value)
    else -> null
}

/** 浮点四则：只有整棵二元式在解析期被判成 FLOAT 时才折（否则交给常量表那一支）。 */
private fun paramDefaultFloatArithmetic(e: Binary): Expr? {
    // 单出口：detekt ReturnCount 上限 2，这里三个前置判空各写一次 return 就顶破。
    val folded = if (e.operandType != OperandType.FLOAT) {
        null
    } else {
        val left = paramDefaultFloatValue(e.left)
        val right = paramDefaultFloatValue(e.right)
        if (left == null || right == null) null else paramDefaultFloatResult(e.op, left, right)
    }
    return folded?.let(::FloatLiteral)
}

private fun paramDefaultFloatResult(op: OperatorCode, left: Double, right: Double): Double? = when (op) {
    OperatorCode.PLUS -> left + right
    OperatorCode.MINUS -> left - right
    OperatorCode.MULT -> left * right
    OperatorCode.DIV -> left / right
    else -> null
}

/** 取常量式的 double 值（整型字面量按宽化处理，口径同解析期浮点提升表）。 */
private fun paramDefaultFloatValue(e: Expr): Double? = when (val folded = paramDefaultLiteral(e)) {
    is FloatLiteral -> folded.value
    is IntLiteral -> folded.value.toDouble()
    else -> null
}
