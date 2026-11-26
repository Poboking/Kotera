package com.eratw.port.modgen

/**
 * 三语文案面上限的**构建期副本**。
 *
 * 真源是作者面 API：`mod-api/src/main/kotlin/com/eratw/port/modapi/StringsApi.kt` 里
 * `StringsSpec.MAX_KEY_LENGTH` / `StringsSpec.MAX_TEXT_LENGTH`（该常量对 `:tools:modgen`
 * 不可见 —— modgen 只读源码文本，不依赖 mod-api 编译产物，避免方向闸倒过来）。运行期
 * `core-vm/src/main/kotlin/com/eratw/port/core/mod/ModStringsRegistry.kt` 的 `requireValidKey` /
 * `requireText` 照这两个数值拒收，所以这里必须逐字同值：**改一处必须改两处**。
 *
 * 漂移由 `ModCheckStringsTest.stringsLimitsStayInSyncWithStringsApiSource` 的文本哨兵兜底：
 * 它读 `StringsApi.kt` 源码文本比对数值，任何一边改了另一没跟上就红。
 */
internal object ModStringsLimits {

    /** 文案键长上限（与 mod-api `StringsSpec.MAX_KEY_LENGTH` 同值）：64。 */
    const val MAX_KEY_LENGTH: Int = 64

    /** 单条文案文本长上限（与 mod-api `StringsSpec.MAX_TEXT_LENGTH` 同值）：512。 */
    const val MAX_TEXT_LENGTH: Int = 512
}
