package com.eratw.port.modtesting

import com.eratw.port.core.mod.DEFAULT_MOD_LANGUAGE
import com.eratw.port.core.mod.ModStringsRegistry
import com.eratw.port.core.mod.apiFor
import com.eratw.port.modapi.StringsApi

/**
 * 三语文案面（测试替身）：**声明、校验、回退链、重复声明 WARN 与生产面同一份实现**
 * （生产 [com.eratw.port.core.mod.LiveStringsApi] 经公开工厂 [ModStringsRegistry.apiFor] 装配，
 * 台架不另写语义）—— 所以台架绿 = 引擎绿：坏键声明期抛的文案逐字一致、缺失回退 zh、
 * 未声明返回键、重复声明后值覆盖且走同一句 WARN。
 *
 * 与生产面的唯一差别：语言不像生产那样由壳层注入，而是本夹具的 [languageCode]（用例可改，
 * 断言「换语言后取到新语言」）。重复声明的 WARN 由 [onWarn] 送出（缺省丢弃）：
 * [TestContext] 把它同时接到本 context 的 [TestContext.logEntries] 与会话级
 * [TestWorld.logEntries]，与生产「warn = { logger.warn(it) }」的侧链同口径。
 */
class RecordingStrings(
    private val packageId: String,
    var languageCode: String = DEFAULT_MOD_LANGUAGE,
    onWarn: (String) -> Unit = {},
) : StringsApi {

    private val registry = ModStringsRegistry()

    private val api: StringsApi = registry.apiFor(packageId, { languageCode }, onWarn)

    override fun declare(key: String, zh: String, en: String, ja: String) {
        api.declare(key, zh, en, ja)
    }

    override fun of(key: String): String = api.of(key)

    override fun has(key: String): Boolean = api.has(key)

    override fun language(): String = api.language()

    /** 已声明的键集（用例断言声明真的发生了）。 */
    fun keys(): Set<String> = registry.keys(packageId)

    /** 指定语言的原文（`null` = 未声明；用例断言三语各自落位）。 */
    fun text(key: String, language: String): String? = registry.resolve(packageId, key, language)
}
