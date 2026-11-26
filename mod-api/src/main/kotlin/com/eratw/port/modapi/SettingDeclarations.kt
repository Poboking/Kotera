package com.eratw.port.modapi

/**
 * 设置项的**声明类型**（RT2 per-mod 配置面，Manifest `settings` 段与设置页共用的类型标签）。
 *
 * 四档 = 四种玩家可见控件的语义：`INT` 数字输入/滑杆（可带上下界）、`STR` 文本框、
 * `BOOL` 开关、`ENUM` 下拉/分段（候选清单声明期固定，运行期不接受清单外的值）。
 */
enum class SettingKind {
    INT,
    STR,
    BOOL,
    ENUM,
}

/**
 * 一条「作者声明一次」的 mod 设置项。
 *
 * ## 为什么用 sealed class 而不是「一个 data class + 一车可空字段」
 *
 * 设置项的本质是**带类型的值**：int 的默认值是 Long、enum 的默认值必须 ∈ 候选清单、
 * bool 的默认值只能是 true/false。把这些塞进同一个「`type: String` + `defaultText: String`」
 * 的容器里，作者写错类型只会在编译期之后、玩家改设置那一刻才炸；sealed 家族让每个子类的
 * 构造签名天然收窄成「这个类型能表达的东西」，校验在 [init] 当场拒载（与 `JobTemplateDecl`
 * 的 `REPLAY ⇒ maxCatchUp` 同口径：声明期拒绝，不留到运行期猜）。
 *
 * ## 声明面 → 线格式 → 运行期 → 设置页
 *
 * 同一个 [key] 贯穿四处：Manifest `settings` 段（`RT2MANIFEST/5`）、宿主注入 [SettingsApi]
 * 的快照、设备侧按包配置文件、设置页控件。**键是作者自创命名空间**（习惯用
 * `族名.项名`，如 `family.notice`），引擎不解释语义，只保证「声明了什么、按什么类型存」。
 *
 * 键的字符集收窄到 `[A-Za-z0-9_.:-]`：它同时是设置文件的键与设置页的稳定标识，
 * 放空白/等号/引号进来只会让持久化转义与排障变复杂。
 */
sealed class SettingDecl {

    /** 本包内唯一的设置项键（作者自创命名空间，见类 KDoc）。 */
    abstract val key: String

    /** 值类型（决定设置页控件与 [SettingsApi] 的类型化读取入口）。 */
    abstract val kind: SettingKind

    /** 设置页里显示的标题（三语文案由设置页本地化；这里只作声明期兜底原文，空串 = 用键）。 */
    abstract val title: String

    /** 设置页里显示的一句话说明（空串 = 不显示说明行）。 */
    abstract val description: String

    /**
     * 默认值的**规范持久化文本**（与 settings 文件里落盘的 value 同一口径）。
     *
     * 未改过 / 文件缺失 / 文件损坏 / 值越界时，[SettingsApi] 的类型化 getter 一律回退到这个
     * 文本再解析 —— 三处共用一份口径，避免「设置页显示一个值、mod 读到另一个值」。
     */
    abstract fun defaultText(): String

    companion object {
        /** 设置项键字符集：`[A-Za-z0-9_.:-]`（见类 KDoc）。 */
        private val KEY_PATTERN = Regex("[A-Za-z0-9_.:-]+")

        /** 单个文本字段上限（标题/说明/默认值/候选名）。防止有人把整篇小作文塞进 manifest。 */
        internal const val MAX_TEXT_LENGTH = 512

        internal fun requireKey(key: String) {
            require(key.isNotBlank()) { "设置项键不能为空" }
            require(KEY_PATTERN.matches(key)) { "设置项键只允许 [A-Za-z0-9_.:-]，实际「$key」" }
            require(key.length <= MAX_TEXT_LENGTH) { "设置项键过长（上限 $MAX_TEXT_LENGTH）：$key" }
        }

        internal fun requireText(name: String, text: String) {
            require(text.length <= MAX_TEXT_LENGTH) { "$name 过长（上限 $MAX_TEXT_LENGTH）" }
            require('\n' !in text && '\r' !in text) { "$name 不得包含换行" }
        }
    }
}

/** 整数设置项：可带闭区间上下界（设置页据此校验并 clamp；越界持久化值读侧回退默认）。 */
data class IntSettingDecl(
    override val key: String,
    val default: Long = 0L,
    val min: Long? = null,
    val max: Long? = null,
    override val title: String = "",
    override val description: String = "",
) : SettingDecl() {
    init {
        SettingDecl.requireKey(key)
        SettingDecl.requireText("设置项 $key 的标题", title)
        SettingDecl.requireText("设置项 $key 的说明", description)
        require(min == null || max == null || min <= max) { "设置项 $key 的 min=$min 大于 max=$max" }
        require(min == null || default >= min) { "设置项 $key 的默认值 $default 小于 min=$min" }
        require(max == null || default <= max) { "设置项 $key 的默认值 $default 大于 max=$max" }
    }

    override val kind: SettingKind get() = SettingKind.INT

    override fun defaultText(): String = default.toString()
}

/** 字符串设置项：默认值任意文本（不含换行；设置页用单行文本框）。 */
data class StrSettingDecl(
    override val key: String,
    val default: String = "",
    override val title: String = "",
    override val description: String = "",
) : SettingDecl() {
    init {
        SettingDecl.requireKey(key)
        SettingDecl.requireText("设置项 $key 的标题", title)
        SettingDecl.requireText("设置项 $key 的说明", description)
        SettingDecl.requireText("设置项 $key 的默认值", default)
    }

    override val kind: SettingKind get() = SettingKind.STR

    override fun defaultText(): String = default
}

/** 布尔设置项：设置页用开关。 */
data class BoolSettingDecl(
    override val key: String,
    val default: Boolean = false,
    override val title: String = "",
    override val description: String = "",
) : SettingDecl() {
    init {
        SettingDecl.requireKey(key)
        SettingDecl.requireText("设置项 $key 的标题", title)
        SettingDecl.requireText("设置项 $key 的说明", description)
    }

    override val kind: SettingKind get() = SettingKind.BOOL

    override fun defaultText(): String = default.toString()
}

/**
 * 枚举设置项：候选清单**声明期固定**（非空、去重、默认值必须在清单里）。
 *
 * 运行期/持久化只认清单原文（精确匹配、不做大小写归一）：这是作者面与玩家面之间唯一
 * 可枚举的取值约定，放开「文件里写个新值也算数」会让设置页与 mod 读到两套值。
 */
data class EnumSettingDecl(
    override val key: String,
    val default: String,
    val candidates: List<String>,
    override val title: String = "",
    override val description: String = "",
) : SettingDecl() {
    init {
        SettingDecl.requireKey(key)
        SettingDecl.requireText("设置项 $key 的标题", title)
        SettingDecl.requireText("设置项 $key 的说明", description)
        require(candidates.isNotEmpty()) { "设置项 $key 的枚举候选不能为空" }
        for (candidate in candidates) {
            require(candidate.isNotBlank()) { "设置项 $key 的枚举候选不能为空串" }
            SettingDecl.requireText("设置项 $key 的枚举候选", candidate)
        }
        require(candidates.toSet().size == candidates.size) { "设置项 $key 的枚举候选重复：$candidates" }
        require(default in candidates) { "设置项 $key 的默认值「$default」不在候选 $candidates 里" }
    }

    override val kind: SettingKind get() = SettingKind.ENUM

    override fun defaultText(): String = default
}
