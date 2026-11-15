package com.eratw.port.modapi

/**
 * mod API 的干净值类型（RT2 Kotlin Mod SDK，era-arch-v61u.md §20.1）。
 *
 * hook 返回值、交互替换结果等跨执行环境传递的值统一走本类型，不暴露 VM 内部值表示
 * （§18.2 铁边界）。[IntValue] 对应 ERB 的整型/Long，[StrValue] 对应字符串；
 * 接收方按实际类型分支处理，不做隐式转换。
 *
 * clean 语义（§18.7）：不复刻官方 VM 的值怪癖；类型不匹配由调用方显式判，不静默折叠。
 */
sealed interface ModValue {

    /** 整型/Long 值。 */
    data class IntValue(val value: Long) : ModValue

    /** 字符串值。 */
    data class StrValue(val value: String) : ModValue

    companion object {
        fun of(value: Long): ModValue = IntValue(value)

        fun of(value: String): ModValue = StrValue(value)
    }
}
