package com.eratw.port.core.err

/**
 * 4.981 `SafeArithmetic` 的整型四则钳制（`SafeArithmetic.cs:5-65`）。
 *
 * 复刻目标蛇版 4.981 把 `+` / `-` / `*` / 一元 `-` 从「unchecked 回绕」改成
 * 「checked 溢出 ⇒ `PrintWarning` + 饱和到 MAX/MIN」：
 *
 * | 运算 | 告警文案（`SafeArithmetic.cs`） | 饱和值 |
 * |---|---|---|
 * | `a + b`（:7-20） | `整数溢出: a + b` | `a > 0 ? MAX : MIN` |
 * | `a - b`（:22-35） | `整数溢出: a - b` | `a > 0 ? MAX : MIN` |
 * | `a * b`（:37-50） | `整数溢出: a * b` | `(a > 0) == (b > 0) ? MAX : MIN` |
 * | `-a`（:52-65） | `整数溢出: -a` | 恒 `MAX`（不看符号） |
 *
 * 接线点：表达式层 `OperatorMethod.cs:278`（PlusIntInt）/ `:307`（MinusIntInt）/
 * `:321`（MultIntInt）/ `:763`（MinusInt）；变量层 `++`/`--`/`+=` 经
 * `VariableToken.cs:752` 的 `SafeAdd`（`PlusValue`）—— `OperatorMethod.cs:807-817`
 * 一族的 `catch (OverflowException)` 因 SafeAdd 已吞掉溢出而不可达，见
 * `VariableStore.plusIntEntry` 的 KDoc。
 *
 * ## 为什么不用 `java.lang.Math.addExact` 一族
 *
 * `Math.addExact` / `subtractExact` / `multiplyExact` / `negateExact` 是 **Android API 24**
 * 才有的方法（android.jar 的 `java.lang.Math`），本工程 minSdk = 21
 * （`app/build.gradle.kts:326-345`）；直接调用会在 Android 5.0/5.1/6.0 的旧机型上
 * `NoSuchMethodError` 崩溃。core-vm 的字节码引用面必须能在 minSdk 上解析，故这里用
 * 位判据手写溢出检测（配合 JVM `ladd/lsub/lmul/lneg` 的回绕结果），不引入 API 级依赖。
 *
 * ## 告警去向
 *
 * 4.981 的 `PrintWarning` 是**每次都打、不去重**（`SafeAdd` 里没有 `clampedWarned`，
 * 那一族只有 RAND 有）。移植侧照走 [ClampWarnings] 的 overflow 桶（every 口径）并上屏；
 * `warn` 回调由调用方决定「上屏 + 留痕」的接线：
 *
 * * 表达式层 → [com.eratw.port.core.expr.EvalContext.reportArithmeticOverflow]
 *   （缺省上屏，`BuiltinEvalContext` 额外落 `ClampWarnings` overflow 桶）；
 * * 槽码/栈码层 → `VmHost.print`（VM 不持有 `RuntimeAux`，只上屏）；
 * * 变量层（`++`/`--`/`+=`）→ `VariableStore.systemLineSink` + overflow 桶。
 */
object SafeArithmetic {

    /** `SafeArithmetic.cs:7-20`：溢出时 `a > 0 ? MAX : MIN`（判据是**左操作数**，不是结果）。 */
    fun add(a: Long, b: Long, warn: (String) -> Unit): Long {
        val r = a + b
        // 同号相加、结果异号 ⇒ 回绕。((a xor r) and (b xor r)) < 0 是 JVM 侧的经典判据：
        // r 与两个操作数都不同号，当且仅当 a、b 同号且和的符号翻转。
        if (((a xor r) and (b xor r)) < 0L) {
            warn(integerOverflowAddWarning(a, b))
            return if (a > 0L) Long.MAX_VALUE else Long.MIN_VALUE
        }
        return r
    }

    /** `SafeArithmetic.cs:22-35`：溢出时 `a > 0 ? MAX : MIN`。 */
    fun subtract(a: Long, b: Long, warn: (String) -> Unit): Long {
        val r = a - b
        // a 与 b 异号、且 a 与结果异号 ⇒ 回绕（`MIN - 1`、`MAX - (-1)`）。
        if (((a xor b) and (a xor r)) < 0L) {
            warn(integerOverflowSubtractWarning(a, b))
            return if (a > 0L) Long.MAX_VALUE else Long.MIN_VALUE
        }
        return r
    }

    /**
     * `SafeArithmetic.cs:37-50`：溢出时 `(a > 0) == (b > 0) ? MAX : MIN`。
     *
     * `MIN * -1` 必须先于除法判据拦下：JVM 的 `Long.MIN_VALUE / -1` 抛
     * `ArithmeticException`（与 .NET 的 `OverflowException` 同域但类型不同，不能让它漏出）。
     */
    fun multiply(a: Long, b: Long, warn: (String) -> Unit): Long {
        val r = a * b
        // `MIN * -1` 必须先于除法判据拦下：JVM 的 `Long.MIN_VALUE / -1` 抛
        // `ArithmeticException`（与 .NET 的 `OverflowException` 同域但类型不同，不能漏出）。
        // 其余情形用「回绕结果除回左操作数」判溢出；0 乘任何数恒安全。
        val overflow = when {
            a == -1L && b == Long.MIN_VALUE -> true
            a == 0L || b == 0L -> false
            else -> r / a != b
        }
        if (!overflow) return r
        warn(integerOverflowMultiplyWarning(a, b))
        return if ((a > 0L) == (b > 0L)) Long.MAX_VALUE else Long.MIN_VALUE
    }

    /** `SafeArithmetic.cs:52-65`：唯一溢出形态是 `-Long.MIN_VALUE`，饱和值**恒** MAX。 */
    fun negate(a: Long, warn: (String) -> Unit): Long {
        if (a == Long.MIN_VALUE) {
            warn(integerOverflowNegateWarning(a))
            return Long.MAX_VALUE
        }
        return -a
    }
}
