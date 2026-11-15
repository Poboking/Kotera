package com.eratw.port.android

/**
 * 预览模块的 UiPrefs 最小桩：只保留 SettingsStrings 设置页文案表引用到的三种枚举。
 *
 * 真 UiPrefs 是 Android 件（Context/SharedPreferences），不能拷进纯 JVM 的 uipreview；
 * 设置卡源码只经 SettingsStrings 用到这几个类型，故此处同包补形状，不复制任何存取逻辑。
 */
object UiPrefs {
    enum class LoadingStyle { PIXEL, MODERN }
    enum class RailButtonSize { SMALL, NORMAL, LARGE, XLARGE }
    enum class RailLayoutMode { ROW_SCROLL, COLUMN, EXPANDED }
}
