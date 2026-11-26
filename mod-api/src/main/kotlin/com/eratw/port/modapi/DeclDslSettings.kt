package com.eratw.port.modapi

/**
 * 声明 DSL 的设置段：`setting` / `intSetting` / `strSetting` / `boolSetting` / `enumSetting`。
 *
 * 继承链位置：`ModManifestBaseBuilder` ← `ModManifestSchemaBuilder` ←
 * `ModManifestRelationsBuilder` ← **本段** ← `ModManifestDataBuilder` ← `ModManifestBuilder`。
 * 本段只追加 [SettingDecl] 到基段的累积容器，不重复持有状态、不做第二套校验
 * （不变量全部在四个 `*SettingDecl` 的 `init` 里）。
 *
 * 键是作者自创命名空间（习惯 `族名.项名`，如 `family.notice`），引擎不解释语义。
 * 设置项**声明一次**后：设置页自动渲染对应控件、值按包持久化到设备侧、mod 经
 * [ModContext.settings] 读回。
 */
open class ModManifestSettingsBuilder internal constructor(
    packageId: String,
    version: String,
) : ModManifestRelationsBuilder(packageId, version) {

    /**
     * 声明一条设置项（通用入口：四个类型的声明对象都收）。
     *
     * 类型安全靠密封家族保证：`setting(IntSettingDecl(...))` 的默认值只能是 Long，
     * 枚举项的默认值必须在候选清单里，写错在声明期当场抛。
     */
    fun setting(decl: SettingDecl) {
        settings += decl
    }

    /** 声明整数设置项（[range] 可选闭区间；设置页据此校验并 clamp）。 */
    fun intSetting(
        key: String,
        default: Long = 0L,
        range: LongRange? = null,
        title: String = "",
        description: String = "",
    ) {
        settings += IntSettingDecl(
            key = key,
            default = default,
            min = range?.first,
            max = range?.last,
            title = title,
            description = description,
        )
    }

    /** 声明字符串设置项（设置页单行文本框）。 */
    fun strSetting(
        key: String,
        default: String = "",
        title: String = "",
        description: String = "",
    ) {
        settings += StrSettingDecl(key = key, default = default, title = title, description = description)
    }

    /** 声明布尔设置项（设置页开关）。 */
    fun boolSetting(
        key: String,
        default: Boolean = false,
        title: String = "",
        description: String = "",
    ) {
        settings += BoolSettingDecl(key = key, default = default, title = title, description = description)
    }

    /** 声明枚举设置项（[candidates] 声明期固定、非空、去重；[default] 必须在清单里）。 */
    fun enumSetting(
        key: String,
        default: String,
        candidates: List<String>,
        title: String = "",
        description: String = "",
    ) {
        settings += EnumSettingDecl(
            key = key,
            default = default,
            candidates = candidates,
            title = title,
            description = description,
        )
    }
}
