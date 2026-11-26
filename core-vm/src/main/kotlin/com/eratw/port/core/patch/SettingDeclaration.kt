package com.eratw.port.core.patch

/**
 * 设置项的值类型（Manifest `settings` 段的类型标签，线格式只认这四个枚举名）。
 *
 * 与 mod-api 的 `SettingKind` 一一对应：翻译器 `ModDeclarationAdapter` 在装载相位把作者面
 * 声明翻成本类型，core 侧不认识 mod-api 的类。
 */
enum class SettingType {
    INT,
    STR,
    BOOL,
    ENUM,
}

/**
 * 一条作者声明的 mod 设置项（core 侧声明形态；`RT2MANIFEST/5` 的 `settings` 段每行一条）。
 *
 * 形状密复制 mod-api 的 `SettingDecl` 家族（core `ModuleDescriptor` 的既有口径：声明事务
 * 类型不直接引用 SDK 公开类型，翻译在 `ModDeclarationAdapter` 里做；例如 `HookDecl` 与
 * `HookDeclaration` 也是两套）。两边的构造期不变量必须逐条一致 —— 线格式解码与装载期
 * 翻译**各拒一次**，任何一侧放松都等于放开第二条进包路径。
 *
 * 键是作者自创命名空间（`[A-Za-z0-9_.:-]`），同一包内唯一；默认值口径见 [defaultText]。
 */
sealed class SettingDeclaration {

    /** 本包内唯一的设置项键。 */
    abstract val key: String

    /** 值类型。 */
    abstract val type: SettingType

    /** 设置页标题兜底原文（空串 = 用键；三语本地化在壳层文案表）。 */
    abstract val title: String

    /** 设置页说明原文（空串 = 不显示说明行）。 */
    abstract val description: String

    /** 默认值的规范持久化文本（未改 / 缺文件 / 坏值 / 越界时的回退值，与落盘口径同一份）。 */
    abstract fun defaultText(): String

    companion object {
        /** 设置项键字符集（与 mod-api 侧逐字符一致）。 */
        private val KEY_PATTERN = Regex("[A-Za-z0-9_.:-]+")

        /** 单个文本字段上限（与 mod-api 侧一致）。 */
        const val MAX_TEXT_LENGTH = 512

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

/** 整数设置项（[min] / [max] 为可选闭区间；越界的持久化值读侧回退默认）。 */
data class IntSettingDeclaration(
    override val key: String,
    val default: Long = 0L,
    val min: Long? = null,
    val max: Long? = null,
    override val title: String = "",
    override val description: String = "",
) : SettingDeclaration() {
    init {
        requireKey(key)
        requireText("设置项 $key 的标题", title)
        requireText("设置项 $key 的说明", description)
        require(min == null || max == null || min <= max) { "设置项 $key 的 min=$min 大于 max=$max" }
        require(min == null || default >= min) { "设置项 $key 的默认值 $default 小于 min=$min" }
        require(max == null || default <= max) { "设置项 $key 的默认值 $default 大于 max=$max" }
    }

    override val type: SettingType get() = SettingType.INT

    override fun defaultText(): String = default.toString()
}

/** 字符串设置项（不含换行）。 */
data class StrSettingDeclaration(
    override val key: String,
    val default: String = "",
    override val title: String = "",
    override val description: String = "",
) : SettingDeclaration() {
    init {
        requireKey(key)
        requireText("设置项 $key 的标题", title)
        requireText("设置项 $key 的说明", description)
        requireText("设置项 $key 的默认值", default)
    }

    override val type: SettingType get() = SettingType.STR

    override fun defaultText(): String = default
}

/** 布尔设置项。 */
data class BoolSettingDeclaration(
    override val key: String,
    val default: Boolean = false,
    override val title: String = "",
    override val description: String = "",
) : SettingDeclaration() {
    init {
        requireKey(key)
        requireText("设置项 $key 的标题", title)
        requireText("设置项 $key 的说明", description)
    }

    override val type: SettingType get() = SettingType.BOOL

    override fun defaultText(): String = default.toString()
}

/** 枚举设置项（候选清单声明期固定，默认值必须在清单里）。 */
data class EnumSettingDeclaration(
    override val key: String,
    val default: String,
    val candidates: List<String>,
    override val title: String = "",
    override val description: String = "",
) : SettingDeclaration() {
    init {
        requireKey(key)
        requireText("设置项 $key 的标题", title)
        requireText("设置项 $key 的说明", description)
        require(candidates.isNotEmpty()) { "设置项 $key 的枚举候选不能为空" }
        for (candidate in candidates) {
            require(candidate.isNotBlank()) { "设置项 $key 的枚举候选不能为空串" }
            requireText("设置项 $key 的枚举候选", candidate)
        }
        require(candidates.toSet().size == candidates.size) { "设置项 $key 的枚举候选重复：$candidates" }
        require(default in candidates) { "设置项 $key 的默认值「$default」不在候选 $candidates 里" }
    }

    override val type: SettingType get() = SettingType.ENUM

    override fun defaultText(): String = default
}
