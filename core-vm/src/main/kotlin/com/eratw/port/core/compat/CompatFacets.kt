package com.eratw.port.core.compat

enum class EngineFamily {
    SNAKE_4981,
    EM_EE_1824_V22,
    V24_1824,
}

enum class StringCompareDialect {
    LEGACY_BOTH_LT,
    FIXED,
}

enum class NicknameKeywordPolicy {
    NAME_CALLNAME_ONLY,
    WITH_NICKNAME_MASTERNAME,
}

/**
 * `RELATION` 逆引表是否收录 `MASTERNAME` 列（`relationDic` 的建表循环）。
 *
 * 与 [NicknameKeywordPolicy] **不冗余**：EM 把 `MASTERNAME` 写进 `relationDic`
 * （`RELATION:MASTER:"<名>"` 可解析），但 `GetKeywordDictionary` 没有对应 case，
 * `GETNUM(MASTERNAME, …)` 仍走不到；v24 两者都有，snake/cs 两者都无。
 * 真源：`specs/engine-profiles.yaml` 的 `dialect.relation_mastername`。
 */
enum class RelationMasternamePolicy {
    EXCLUDED,
    INCLUDED,
}

data class EngineDialect(
    val stringCompare: StringCompareDialect,
    val nicknameKeywords: NicknameKeywordPolicy,
    val relationMastername: RelationMasternamePolicy,
)
