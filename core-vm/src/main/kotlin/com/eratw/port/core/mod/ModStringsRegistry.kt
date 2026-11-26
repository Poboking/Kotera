package com.eratw.port.core.mod

import com.eratw.port.modapi.StringsApi
import com.eratw.port.modapi.StringsSpec

/**
 * 会话级 mod 自带文案表（按 packageId 隔离；[ModRuntimeHost] 持一份，全链共享）。
 *
 * 为什么单独成表而不是塞进各 mod 的 `ModContextImpl`：文案的**生命周期**跟着包走（`onLoad`
 * 声明、卸载/失败回滚即清空），而同一份表要同时被「作者读侧（[LiveStringsApi]）」与
 * 「渲染侧标签解析缝（`ModUiSurface.stringResolver`）」读 —— 两份状态必然分叉，故单点持有。
 *
 * 实现口径：
 *
 * - 只读面（[resolve] / [has] / [keys]）在**任意线程**安全：表在建表线程写、读侧不就地改
 *   结构（`LinkedHashMap` 读不写）；引擎侧本来就只在 VM 线程写（`onLoad` / 卸载）。
 * - 空表近零成本：[resolve] 先查包表，包未声明文案直接返回 `null`（不建表、不分配）。
 * - 键形态闸与上限在**声明期**（不是读期）：坏键当场 [IllegalArgumentException]，不静默跳过。
 *
 * @see LiveStringsApi 作者可见的实现（绑 [StringsApi]）
 */
class ModStringsRegistry {

    private val tables = LinkedHashMap<String, LinkedHashMap<String, StringsEntry>>()

    /**
     * 声明一个键的三语原文。重复声明同一键 = 后值覆盖前值（不累积、不抛）。
     *
     * @return `true` = 新键；`false` = 覆盖了既有键（调用方可据此 WARN）。
     */
    fun declare(packageId: String, key: String, zh: String, en: String, ja: String): Boolean {
        require(packageId.isNotBlank()) { "packageId 不能为空" }
        requireValidKey(packageId, key)
        requireText(packageId, key, "zh", zh)
        requireText(packageId, key, "en", en)
        requireText(packageId, key, "ja", ja)
        val table = tables.getOrPut(packageId) { LinkedHashMap() }
        val previous = table.put(key, StringsEntry(zh = zh, en = en, ja = ja))
        return previous == null
    }

    /** 按语言解析原文：包/键未声明 ⇒ `null`；语言码未知 ⇒ 回落 ZH。 */
    fun resolve(packageId: String, key: String, language: String): String? =
        tables[packageId]?.get(key)?.text(language)

    /** 本包是否声明过 [key]。 */
    fun has(packageId: String, key: String): Boolean = tables[packageId]?.containsKey(key) == true

    /** 本包已声明的键集（快照，调用方可自由遍历）。 */
    fun keys(packageId: String): Set<String> = tables[packageId]?.keys?.toSet() ?: emptySet()

    /** 诊断只读：本包已声明的键数（不构造键集快照；未知包 0）。 */
    internal fun sizeOf(packageId: String): Int = tables[packageId]?.size ?: 0

    /** 清空本包文案（卸载 / 失败回滚 / 重新装载前的既有口径）。 */
    fun clear(packageId: String) {
        tables.remove(packageId)
    }

    /** 全表是否为空（诊断/自证用：关态与无文案会话都应为 true）。 */
    fun isEmpty(): Boolean = tables.isEmpty()

    /** 有文案的包数（诊断用）。 */
    fun packageCount(): Int = tables.size

    /** 一档键的三语原文。 */
    private class StringsEntry(val zh: String, val en: String, val ja: String) {
        fun text(language: String): String = when (language.lowercase()) {
            LANGUAGE_EN -> en
            LANGUAGE_JA -> ja
            else -> zh
        }
    }

    private fun requireValidKey(packageId: String, key: String) {
        require(key.isNotEmpty()) { "文案键不能为空：$packageId" }
        require(key.length <= StringsSpec.MAX_KEY_LENGTH) {
            "文案键超过 ${StringsSpec.MAX_KEY_LENGTH} 字符：$packageId.$key"
        }
        require(KEY_PATTERN.matches(key)) {
            "文案键只许小写字母/数字/下划线/点/横线：$packageId.$key"
        }
    }

    private fun requireText(packageId: String, key: String, language: String, text: String) {
        require(text.isNotBlank()) { "文案原文不能空白：$packageId.$key[$language]" }
        require(text.length <= StringsSpec.MAX_TEXT_LENGTH) {
            "文案原文超过 ${StringsSpec.MAX_TEXT_LENGTH} 字符：$packageId.$key[$language]"
        }
    }
}

/**
 * 作者可见的文案面实现：声明落进会话级 [ModStringsRegistry]，读侧实时取当前语言。
 *
 * 语言经 [language] 提供者读（生产上 = `{ host.language }`）：壳层换语言后作者下次
 * `of()` 立刻取到新语言，不需要重装 mod。
 */
internal class LiveStringsApi(
    private val registry: ModStringsRegistry,
    private val packageId: String,
    private val languageProvider: () -> String,
    private val warn: (String) -> Unit,
) : StringsApi {

    override fun declare(key: String, zh: String, en: String, ja: String) {
        val fresh = registry.declare(packageId, key, zh, en, ja)
        if (!fresh) warn("文案键重复声明，后值覆盖前值：$packageId.$key")
    }

    override fun of(key: String): String = registry.resolve(packageId, key, languageProvider()) ?: key

    override fun has(key: String): Boolean = registry.has(packageId, key)

    override fun language(): String = languageProvider()
}

/**
 * 公开的逐包文案面构造入口（生产 [ModContextImpl.adoptStrings] 装配用的同一实现）。
 *
 * 宿主与台架需要的只是「会话表 + 包身份 + 语言提供者 + warn sink」这一个构造点：三语校验、
 * 重复声明 WARN（文案「文案键重复声明，后值覆盖前值：pkg.key」）、缺失回退全在
 * [ModStringsRegistry] 与 [LiveStringsApi] 里。
 */
fun ModStringsRegistry.apiFor(
    packageId: String,
    languageProvider: () -> String,
    warn: (String) -> Unit,
): StringsApi = LiveStringsApi(this, packageId, languageProvider, warn)

/**
 * 未装配时的显式 fail-closed 桩：四个入口一律抛 [UnsupportedOperationException]。
 *
 * 不返回空表 / 键本身兜底：静默兜底会让作者以为声明生效了（与 `FailClosedResourceApi`
 * 同一口径）。
 */
internal class FailClosedStringsApi(private val packageId: String) : StringsApi {

    override fun declare(key: String, zh: String, en: String, ja: String): Nothing = fail()

    override fun of(key: String): Nothing = fail()

    override fun has(key: String): Nothing = fail()

    override fun language(): Nothing = fail()

    private fun fail(): Nothing = throw UnsupportedOperationException(
        "文案面未装配：$packageId（宿主未注入 StringsApi；本会话/旧装配路径不提供 mod 自带文案）。",
    )
}

/**
 * 把会话级文案表绑进单个 mod 的 [ModContextImpl]（`onLoad` 之前调）。
 *
 * 文件级 extension 而非 [ModContextImpl] 成员：该类的成员函数数已抵 detekt
 * `TooManyFunctions` 阈值（见 `ModContextFaces.kt` 的装配口径），本刀不新增成员函数。
 */
internal fun ModContextImpl.adoptStrings(registry: ModStringsRegistry, languageProvider: () -> String) {
    stringsApi = LiveStringsApi(
        registry = registry,
        packageId = packageId,
        languageProvider = languageProvider,
        warn = { message -> logger.warn(message) },
    )
}

/** 文案键形态闸（声明期，不静默跳过）：只许小写字母/数字/下划线/点/横线。 */
private val KEY_PATTERN = Regex("[a-z0-9_.-]+")

/** 语言码（与 `SettingsLanguage.stored` 同值：`zh` / `en` / `ja`）。 */
private const val LANGUAGE_EN: String = "en"
private const val LANGUAGE_JA: String = "ja"

/**
 * 缺省语言码：壳层未注入当前设置语言时的回落值（= `SettingsLanguage.DEFAULT`）。
 *
 * 公开常量而不是各写一份字面量：core 侧 [ModRuntimeHost.language] 与 app 侧共用它，
 * 保证「没接线时行为逐位不变」的口径只有一处真值。
 */
const val DEFAULT_MOD_LANGUAGE: String = "zh"
