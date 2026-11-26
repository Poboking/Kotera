package com.eratw.port.core.err

import com.eratw.port.core.host.RuntimeAux

/**
 * 复刻目标蛇版 4.981 的**钳制类告警**（不是异常，不中断脚本）。
 *
 * 4.981 把两族「参数非法直接抛 CodeEE」改成「钳制为安全值 + 控制台告警」：
 *
 * * **RAND 家族**（`CHANGELOG.md:105-112` 的 12.2.0「RAND 随机数入口安全保护」）：
 *   `RAND:n` 参数 ≤ 0 时钳制返回 `0`；`RAND(min,max)` / `RAND(max)` 参数 `max ≤ min`
 *   时钳制返回下界 `min`。首次触发 `Console.PrintError`，之后静默钳制，避免循环刷屏。
 *   - `VariableToken.cs:1413-1436` 的 `RandToken`（伪变量 `RAND:n`）
 *   - `Creator.Method.cs:3793-3857` 的 `RandMethod`（整数 + 浮点两支共用一个 flag）
 * * **整数四则**（`SafeArithmetic.cs:67-84` 的 `SafeDivide` / `SafeModulo`，由
 *   `OperatorMethod.cs:374` / `:388` 的 `DivIntInt` / `ModIntInt` 调用）：
 *   `b == 0` 时 `Console.PrintWarning("零除: …")` 并返回 `0`，**每次都警告**（不去重）。
 * * **整数溢出**（`SafeArithmetic.cs:7-65` 的 `SafeAdd` / `SafeSubtract` /
 *   `SafeMultiply` / `SafeNegate`，接线点见 [SafeArithmetic] 的 KDoc）：越界时
 *   `Console.PrintWarning("整数溢出: …")` 并饱和，同样**每次都警告**（不去重）。
 *
 * **不在此列的反向证明**：`SafeArithmetic.cs:91-100` 虽定义了 `SafeFloatDivide`，但全仓
 * **无任何调用点**；`OperatorMethod.cs:987` / `:1137` 的 `DivFloatFloat` / `DivMixedFloat`
 * 仍 `throw new CodeEE(trerror.DivideByZero.Text)` ⇒ 浮点除零保持硬错，不跟着钳制。
 *
 * 移植侧此前按 1.824 复刻（`OperatorMethodManager.cs:134/:153` 抛 DivideByZero、
 * `VariableData.cs:846-851` 抛 RandArgIsNegative），真机上报里因此出现
 * 「除数为0的除法运算」「RAND不能指定0以下的值(0)」这类**官方 4.981 不会中断**的报错。
 *
 * ## 会话级状态（铁律 4）
 *
 * 4.981 的 `clampedWarned` 是 `private static bool`，而且**每个类各一份**
 * （`RandMethod` 一份、`RandToken` 一份，互不相干）——所以这里按 [kind] 分桶，不是全 RAND
 * 家族共用一个开关。移植侧禁静态可变全局：状态落到 [RuntimeAux] 的会话槽，
 * `RuntimeAux.clear()`（RESTART / 读档重建）即与之等价的「每 VM 一份」重置。
 * 这比 4.981 的进程级 static 更严：并行测试 / 多开 / 读档都不会串味。
 *
 * 告警文本同时留在本对象里，供错误报告与定向单读取证；上屏由各调用点走各自的
 * `printSystemLine` / `print` 通道，core 层不持有 UI 引用。
 *
 * ## 留痕有界
 *
 * 整数零除按 4.981 是**每次都警告**，脚本若在循环里反复踩零，无上限的 `messages` 会把
 * 堆喂爆（收件箱里本就有 OOM 簇）。上屏照发不误，但**留痕**只保留前 [MAX_RETAINED_LINES]
 * 条，其余计入 [suppressedCount] —— 钳制语义与告警可见性不受影响。
 */
class ClampWarnings {
    private val warnedKinds = HashSet<String>()
    private val messages = ArrayList<String>()
    private var suppressed = 0

    /**
     * 整数溢出告警（4.981 `SafeArithmetic.cs:7-65`）的**独立桶**。
     *
     * 为什么不并进 [messages]：溢出与零除都是「每次上屏不去重」，脚本在循环里踩一次
     * `MAX + 1` 就能连刷几千条；同桶会把 RAND 的两条首次告警（诊断价值最高）挤出
     * [MAX_RETAINED_LINES] 的留痕窗口。分桶后两族各自有界，取证时也分得开。
     */
    private val overflowMessages = ArrayList<String>()
    private var overflowSuppressed = 0
    private var overflowTotal = 0

    /** 同一 [kind] 只留一次告警文本；已警告过返回 null（调用方据此跳过上屏）。 */
    fun once(kind: String, message: String): String? {
        if (!warnedKinds.add(kind)) return null
        retain(message)
        return message
    }

    /** 每次都原样返回供上屏；留痕有界（整数零除与 4.981 的 PrintWarning 同口径）。 */
    fun every(message: String): String {
        retain(message)
        return message
    }

    /**
     * 整数溢出告警：**每次都**原样返回供上屏（4.981 `PrintWarning` 不去重），
     * 留痕落独立 overflow 桶且同样有界。
     */
    fun overflow(message: String): String {
        overflowTotal++
        if (overflowMessages.size < MAX_RETAINED_LINES) {
            overflowMessages.add(message)
        } else {
            overflowSuppressed++
        }
        return message
    }

    private fun retain(message: String) {
        if (messages.size < MAX_RETAINED_LINES) messages.add(message) else suppressed++
    }

    fun lines(): List<String> = messages.toList()

    /** 超出留痕上限、只上屏未留痕的条数。 */
    fun suppressedCount(): Int = suppressed

    /** overflow 桶里已留痕的告警（按触发序）。 */
    fun overflowLines(): List<String> = overflowMessages.toList()

    /** overflow 桶累计触发次数（含未留痕的）。 */
    fun overflowCount(): Int = overflowTotal

    /** overflow 桶超出留痕上限、只上屏未留痕的条数。 */
    fun overflowSuppressedCount(): Int = overflowSuppressed
}

private const val MAX_RETAINED_LINES = 64

/** [RuntimeAux] 槽名；唯一入口是 [RuntimeAux.clampWarnings]。 */
const val CLAMP_WARNINGS_SLOT: String = "clamp-warnings"

/** 与 4.981 `RandToken.clampedWarned` 一一对应的分桶键（与 [RAND_METHOD_CLAMP_KIND] 不共用）。 */
const val RAND_TOKEN_CLAMP_KIND: String = "rand-token"

/** 与 4.981 `RandMethod.clampedWarned` 一一对应的分桶键（整数/浮点两支共用这一份）。 */
const val RAND_METHOD_CLAMP_KIND: String = "rand-method"

fun RuntimeAux.clampWarnings(): ClampWarnings = slot(CLAMP_WARNINGS_SLOT) { ClampWarnings() }

// ==================================================================
// 告警文案唯一真源（逐字对齐 4.981 zhs 表；别在调用点抄第二份）
// ==================================================================

/**
 * `Error.RandArgIsNegative`：`reference/.../Properties/lang/emuera-zhs.xml` 的对应键，
 * 与既有 `VariableStore` 抛错文案同文（真机上报里玩家看到的就是这一句）。
 */
internal const val RAND_ARG_IS_NEGATIVE_TEMPLATE: String = "RAND不能指定0以下的值({0})"

/**
 * `Error.MaximumLowerThanMinimum`：`reference/.../emuera-zhs.xml:967`
 * 「函数{0}: 最大值被指定为小于或等于最小值的值({1})」（`Lang.cs:1190` 是日文原文）。
 */
internal const val MAXIMUM_LOWER_THAN_MINIMUM_TEMPLATE: String =
    "函数{0}: 最大值被指定为小于或等于最小值的值({1})"

/** 4.981 `Creator.Method.cs:3824/:3848` 的追加说明。 */
internal const val CLAMPED_TO_LOWER_SUFFIX: String = "（已钳制为下界，不再中断运行）"

/** 4.981 `VariableToken.cs:1429` 的追加说明。 */
internal const val CLAMPED_TO_ZERO_SUFFIX: String = "（已钳制为 0，不再中断运行）"

/** `RAND:n` 的 n ≤ 0：保留官方原文 + 4.981 追加说明。 */
internal fun randTokenClampMessage(bound: Long): String =
    RAND_ARG_IS_NEGATIVE_TEMPLATE.replace("{0}", bound.toString()) + CLAMPED_TO_ZERO_SUFFIX

/** `RAND(min,max)` / `RAND(max)` 的 max ≤ min：保留官方原文 + 4.981 追加说明。 */
internal fun randMethodClampMessage(name: String, max: Long): String =
    MAXIMUM_LOWER_THAN_MINIMUM_TEMPLATE
        .replace("{0}", name)
        .replace("{1}", max.toString()) + CLAMPED_TO_LOWER_SUFFIX

/**
 * `SafeArithmetic.cs:68-77` / `:80-88` 的 `PrintWarning` 文案。
 * 取余支按 4.981 用反斜杠（`零除: a \ 0`），不是除号 —— 两族在控制台上可区分。
 */
internal fun zeroDivideWarning(a: Long, isModulo: Boolean): String =
    if (isModulo) "零除: $a \\ 0" else "零除: $a / 0"

// ------------------------------------------------------------------
// 整数溢出文案（`SafeArithmetic.cs:7-65` 的 `PrintWarning`，逐字对齐）
// ------------------------------------------------------------------

/** `SafeArithmetic.cs:17`：`整数溢出: {a} + {b}`。 */
internal fun integerOverflowAddWarning(a: Long, b: Long): String = "整数溢出: $a + $b"

/** `SafeArithmetic.cs:32`：`整数溢出: {a} - {b}`。 */
internal fun integerOverflowSubtractWarning(a: Long, b: Long): String = "整数溢出: $a - $b"

/** `SafeArithmetic.cs:47`：`整数溢出: {a} * {b}`。 */
internal fun integerOverflowMultiplyWarning(a: Long, b: Long): String = "整数溢出: $a * $b"

/**
 * `SafeArithmetic.cs:62`：`整数溢出: -{a}`。
 *
 * 注意 C# 插值把字面 `-` 与 `a.ToString()` 直接拼接：`a = long.MinValue` 时上屏是
 * **两个负号**（`整数溢出: --9223372036854775808`），这里 `"整数溢出: -$a"` 逐字同形。
 */
internal fun integerOverflowNegateWarning(a: Long): String = "整数溢出: -$a"
