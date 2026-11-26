package com.eratw.port.modapi

/**
 * mod 自带三语文案面（RT2 多语言文案注入第一刀）。
 *
 * ## 为什么有这一面
 *
 * 交互贡献项（[UiApi.registerInteraction]）与其它玩家可见文字原先只能指向宿主文案表的键
 * （`ModUiCopy` 的既有槽位），第三方 mod 想显示自己的文字就必须挤进宿主表——既改不动宿主
 * 表，也没法随包发布。本面把**文案的所有权还给作者**：作者在自己的 `onLoad` 里
 * [declare] 三语原文，宿主按当前设置语言解析并上屏（见 `ModUiSurface` 的标签解析缝）。
 *
 * ## 声明口径
 *
 * - 声明落进**本包**的文案表（宿主在 `onLoad` 之前注入本包视图，作者在初始化里即可 [declare]）。
 *   实现不做相位闸：`onEnable` 之后声明的新键仍被接受，读侧走活表、下一次渲染即可上屏；
 *   语义上文案仍应集中在 `onLoad` 声明完（与交互项注册同时序，便于回滚与审查）。
 * - [declare] 一次给齐 ZH/EN/JA 三档原文（漏一档 = [IllegalArgumentException]，
 *   与「三语同加」的文案纪律同口径，不提供单语声明）。
 * - 键形态：非空、只许小写字母/数字/`_`/`.`/`-`、长度 ≤ [StringsSpec.MAX_KEY_LENGTH]。
 *   键在本包内唯一；重复声明同一键 = **后值覆盖前值** + 宿主 WARN（热改文案时不被静默吞掉）。
 * - 原文非空白、长度 ≤ [StringsSpec.MAX_TEXT_LENGTH]。
 *
 * ## 解析口径
 *
 * [of] 取**当前设置语言**的原文；该语言缺失（或在 [declare] 之前读）时回落 ZH；键未声明
 * 时**原样返回键**（不返回空串、不抛 —— 这既是「文案没声明」的可见标记，也让老 mod 的
 * 键值行为逐位不变）。
 *
 * 语言结论只经宿主壳层的既有读取单路（`SettingsLanguage`）传入，本面不读偏好、不自判 Locale。
 *
 * 线程：与其余 mod 面一致，只在 VM 线程调用。
 */
interface StringsApi {

    /**
     * 声明一个三语文案键（见类型 KDoc 的声明口径；实现不做相位闸，语义上仍应在 `onLoad` 声明）。
     *
     * @throws IllegalArgumentException 键形态非法 / 任一档原文空白或超长。
     */
    fun declare(key: String, zh: String, en: String, ja: String)

    /**
     * 按当前设置语言取原文：当前语言 → ZH → 键本身（未声明时原样返回键）。 [key] 不合法时
     * 原样返回键，不抛（读侧不因坏键打断渲染）。
     */
    fun of(key: String): String

    /** 本包是否声明过 [key]（键非法时为 false）。 */
    fun has(key: String): Boolean

    /** 当前设置语言码（`zh` / `en` / `ja`；壳层未注入时为 `zh`）。 */
    fun language(): String
}

/**
 * 文案面的口径常量（作者与宿主实现共用同一份，不各写一份魔数）。
 *
 * 上限的取法：键是**稳定标识**（写进代码与存档引用），放宽会让键表退化成自由文本；原文
 * 是 UI 文字，512 已远超一条菜单/按钮/提示的合理长度，超了通常是作者贴错内容。
 */
object StringsSpec {
    /** 文案键字符上限。 */
    const val MAX_KEY_LENGTH: Int = 64

    /** 单档原文字符上限。 */
    const val MAX_TEXT_LENGTH: Int = 512
}
