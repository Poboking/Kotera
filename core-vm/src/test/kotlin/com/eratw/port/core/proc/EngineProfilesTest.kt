package com.eratw.port.core.proc

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.compat.NicknameKeywordPolicy
import com.eratw.port.core.compat.RelationMasternamePolicy
import com.eratw.port.core.compat.StringCompareDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ENGC-2：家族 → 档案的装配闸。
 *
 * 三条主张：
 * 1. **方言只有生成物一个出处**：`profileFor` 的方言字段必须逐值等于
 *    `EngineProfileData.DIALECT`（手抄值会先在这里红）；
 * 2. **缺省档逐字段不变**：SNAKE + 缺省引数 == `LEGACY_ERATW_4981`；
 * 3. **印章档位表自洽**：标识唯一、引擎串/版本串唯一，且三个家族各覆盖一次
 *    （B6 的写侧按同一张表出包）。
 */
class EngineProfilesTest {

    @Test
    fun `缺省家族装配出 LEGACY_ERATW_4981`() {
        assertEquals(
            RuntimeCompatibilityProfile.LEGACY_ERATW_4981,
            EngineProfiles.profileFor(EngineFamily.SNAKE_4981),
        )
    }

    @Test
    fun `三档方言逐值等于生成物`() {
        for (family in EngineFamily.values()) {
            val profile = EngineProfiles.profileFor(family)
            val dialect = EngineProfileData.DIALECT.getValue(family)
            assertEquals(family, profile.engineFamily, "家族：$family")
            assertEquals(dialect.stringCompare, profile.stringCompare, "比较方言：$family")
            assertEquals(dialect.nicknameKeywords, profile.nicknameKeywords, "昵称面：$family")
            assertEquals(dialect.relationMastername, profile.relationMastername, "RELATION 逆引面：$family")
            // 本波不改符号解析：三档都是 last-wins（函数面差异走 ENGC-3 的可见性门控）。
            assertEquals(ResolverPolicy.LEGACY_LAST_WINS, profile.functionResolution, "解析：$family")
            assertEquals(CorpusVersion.V4_981, profile.corpusVersion, "语料版本：$family")
        }
    }

    @Test
    fun `V24 档带两者皆小于的方言与主名关键字`() {
        val v24 = EngineProfiles.profileFor(EngineFamily.V24_1824)
        assertEquals(StringCompareDialect.LEGACY_BOTH_LT, v24.stringCompare)
        assertEquals(NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME, v24.nicknameKeywords)
        assertEquals(RelationMasternamePolicy.INCLUDED, v24.relationMastername)
    }

    @Test
    fun `EM 档两组关键字 facet 各走各的（逆引收主名但 GETNUM 不路由主名）`() {
        // 这是第三 facet 存在的全部理由：em 的 relationDic 收 MASTERNAME（RELATION 面可达），
        // 而 GetKeywordDictionary 无 MASTERNAME case（GETNUM 面 -1）。两个 facet 不得被
        // 一个开关顺手带出另一个（spec: dialect.relation_mastername 的正/负锚）。
        val em = EngineProfiles.profileFor(EngineFamily.EM_EE_1824_V22)
        assertEquals(NicknameKeywordPolicy.NAME_CALLNAME_ONLY, em.nicknameKeywords)
        assertEquals(RelationMasternamePolicy.INCLUDED, em.relationMastername)
        val snake = EngineProfiles.profileFor(EngineFamily.SNAKE_4981)
        assertEquals(NicknameKeywordPolicy.NAME_CALLNAME_ONLY, snake.nicknameKeywords)
        assertEquals(RelationMasternamePolicy.EXCLUDED, snake.relationMastername)
    }

    @Test
    fun `显式语料版本只改 corpusVersion`() {
        val v4980 = EngineProfiles.profileFor(EngineFamily.V24_1824, CorpusVersion.V4_980)
        val v4981 = EngineProfiles.profileFor(EngineFamily.V24_1824, CorpusVersion.V4_981)
        assertEquals(CorpusVersion.V4_980, v4980.corpusVersion)
        assertEquals(v4980.copy(corpusVersion = CorpusVersion.V4_981), v4981)
        assertNotEquals(v4980, v4981, "语料版本必须进相等性（缓存指纹要能区分）")
    }

    @Test
    fun `印章档位表标识与组合唯一且覆盖三个家族`() {
        val targets = EngineProfiles.TARGETS
        assertEquals(targets.size, targets.map { it.profileId }.toSet().size, "profileId 必须唯一")
        assertEquals(
            targets.size,
            targets.map { it.engine to it.version }.toSet().size,
            "engine + version 组合必须唯一",
        )
        assertEquals(
            EngineFamily.values().toSet(),
            targets.map { it.family }.toSet(),
            "三个家族各要有且只有一条可出包的档位",
        )
        for (t in targets) {
            assertTrue(t.profileId.isNotBlank() && t.engine.isNotBlank() && t.version.isNotBlank(), "$t")
            assertEquals(t.family, EngineProfiles.profileFor(t.family, t.corpusVersion).engineFamily, "$t")
        }
    }

    @Test
    fun `按标识查表只认精确匹配`() {
        assertSame(EngineProfiles.TARGETS[0], EngineProfiles.target("snake_4981"))
        assertEquals(null, EngineProfiles.target("SNAKE_4981"))
        assertEquals(null, EngineProfiles.target("snake_4981 "))
        assertEquals(null, EngineProfiles.target(""))
    }

    // ==================================================================
    // ENGCPL-L2：档位表是生成物的投影（防「生成器漏字段」与「手抄第二份」）
    // ==================================================================

    @Test
    fun `档位表逐字段等于生成物 TargetSpec`() {
        assertEquals(EngineProfileData.TARGETS.size, EngineProfiles.TARGETS.size, "档位数不许丢")
        for (i in EngineProfileData.TARGETS.indices) {
            val spec = EngineProfileData.TARGETS[i]
            val target = EngineProfiles.TARGETS[i]
            assertEquals(spec.profileId, target.profileId, "TARGETS[$i].profileId")
            assertEquals(spec.engine, target.engine, "TARGETS[$i].engine")
            assertEquals(spec.version, target.version, "TARGETS[$i].version")
            assertEquals(spec.family, target.family, "TARGETS[$i].family")
            assertEquals(
                CorpusVersion.valueOf(spec.corpusVersionId),
                target.corpusVersion,
                "TARGETS[$i].corpusVersion（corpusVersionId 收口点）",
            )
            assertEquals(
                spec.fullStatementCoverage,
                target.fullStatementCoverage,
                "TARGETS[$i].fullStatementCoverage（审计短路的唯一依据）",
            )
        }
        assertEquals(
            EngineProfileData.TARGETS.map { it.profileId },
            EngineProfiles.TARGETS.map { it.profileId },
            "顺序必须与生成物一致（报错并列顺序与 erbcc 支持表同序）",
        )
    }

    @Test
    fun `G4 五元组与基点亮值逐字段相等`() {
        // 基点 = 手写表（CONTRACT-ENGCPL §2 G4）：L2 把来源换成生成物后，这五个字段不许变。
        val expected = listOf(
            listOf("snake_4981", "snake", "4.981", "SNAKE_4981", "V4_981"),
            listOf("em_ee_1824_v22", "em_ee", "1824+v22+EMv18+EEv57", "EM_EE_1824_V22", "V4_981"),
            listOf("v24_1824", "v24", "1824+v24", "V24_1824", "V4_981"),
        )
        assertEquals(
            expected,
            EngineProfiles.TARGETS.map {
                listOf(it.profileId, it.engine, it.version, it.family.name, it.corpusVersion.name)
            },
            "五元组（profileId/engine/version/family/corpusVersion）逐字段等于基点",
        )
    }

    @Test
    fun `DEFAULT_TARGET 指向生成物声明的缺省档`() {
        assertEquals(EngineProfileData.DEFAULT_TARGET_PROFILE_ID, EngineProfiles.DEFAULT_TARGET.profileId)
        assertSame(
            EngineProfiles.target(EngineProfileData.DEFAULT_TARGET_PROFILE_ID),
            EngineProfiles.DEFAULT_TARGET,
            "DEFAULT_TARGET 必须是档位表里的同一个实例（不是另建一份）",
        )
    }

    @Test
    fun `targetOf 与 target 同源`() {
        for (t in EngineProfiles.TARGETS) {
            assertSame(t, EngineProfiles.target(t.profileId), "target(${t.profileId})")
            assertSame(t, EngineProfiles.targetOf(t.family), "targetOf(${t.family})")
        }
        assertEquals(
            EngineFamily.values().toSet(),
            EngineProfiles.TARGETS.map { it.family }.toSet(),
            "每个家族恰有一条档位（targetOf 不许有歧义）",
        )
    }

    @Test
    fun `profileFor 缺省语料版本等于缺省档声明`() {
        assertEquals(
            EngineProfiles.DEFAULT_TARGET.corpusVersion,
            EngineProfiles.profileFor(EngineFamily.SNAKE_4981).corpusVersion,
            "缺省引数必须取自 DEFAULT_TARGET.corpusVersion",
        )
        for (t in EngineProfiles.TARGETS) {
            assertEquals(
                t.corpusVersion,
                EngineProfiles.profileFor(t.family).corpusVersion,
                "${t.profileId} 的缺省语料版本",
            )
        }
    }
}
