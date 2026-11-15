package com.eratw.port.core.proc

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.compat.EngineDialect
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.compat.NicknameKeywordPolicy
import com.eratw.port.core.compat.RelationMasternamePolicy
import com.eratw.port.core.compat.StringCompareDialect
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `RuntimeCompatibilityProfile` 的形状与默认值闸（era-arch-v61u.md §9；ENGC-2 扩到十个字段）。
 *
 * 断言只覆盖「形状对不对、缺省值是不是现行行为」——行为对拍归各消费点（ENGC-3/5）。闸的
 * 三条主张：
 *
 * 1. **profile 只装枚举**：出现 Boolean/String/List 字段 = 长出自由旋钮，正是反模式；
 * 2. **十个 facet + 版本声明就是全部字段**：加第十一个字段必须显式改这里（评审点）；
 * 3. **版本不泄进策略**：4.980/4.981 两档只许差 `corpusVersion`，Modern 只许翻转
 *    `functionResolution`（家族与方言也不许动，D8）—— 任何「读版本号在消费点分叉」都会
 *    先在这里红。
 */
class RuntimeCompatibilityProfileTest {

    /** 全部 facet 名（写法与 §9 表一致；加字段时这里和反射读数一起改）。 */
    private val expectedFields = listOf(
        "corpusVersion",
        "functionResolution",
        "identifierFolding",
        "eventRegistration",
        "lazyLoading",
        "reload",
        "saveLoad",
        "engineFamily",
        "stringCompare",
        "nicknameKeywords",
        "relationMastername",
    )

    @Test
    fun `profile 的字段恰好是十个 facet 加版本声明且全部是枚举`() {
        // 实例字段 = data class 的属性；Companion 的工厂常量是 static 字段，不算 facet。
        val fields = RuntimeCompatibilityProfile::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertEquals(
            expectedFields.sorted(),
            fields.map { it.name }.sorted(),
            "字段集合 = §9 的十个 facet + corpusVersion（多一个字段就是多一个旋钮）",
        )
        for (f in fields) {
            assertTrue(
                f.type.isEnum,
                "${f.name} 的类型是 ${f.type.simpleName}：profile 只许装枚举 facet，布尔/字符串/列表会变成自由旋钮",
            )
        }
    }

    @Test
    fun `单值 facet 现状各只有一个实现值`() {
        // 单值不是「没设计」，是**能力标识**：原版/本移植只声明了这一条事实。
        // 加值会让消费方的 exhaustive when 编译期变红 —— 这是有意的，不是要顺手放宽这里。
        assertEquals(1, IdentifierFolding.values().size, "标识符归一只声明了 OrdinalIgnoreCase（Config.cs:33）")
        assertEquals(1, EventRegistrationPolicy.values().size, "事件注册只有四桶（LabelDictionary.cs:90-105）")
        assertEquals(1, LazyLoadingPolicy.values().size, "v1 能力标识 = EAGER_ONLY（§9.1）；懒激活另立值")
        assertEquals(1, ReloadPolicy.values().size, "重载只有重注册（Process.cs:350-358）")
        assertEquals(1, SaveLoadPolicy.values().size, "LOADDATA 只有先复位后解析（VariableEvaluator.cs:2626-2627）")
    }

    @Test
    fun `LEGACY 档逐项等于现行行为`() {
        for (p in listOf(RuntimeCompatibilityProfile.LEGACY_ERATW_4980, RuntimeCompatibilityProfile.LEGACY_ERATW_4981)) {
            assertEquals(ResolverPolicy.LEGACY_LAST_WINS, p.functionResolution, "Legacy 档：$p")
            assertEquals(IdentifierFolding.ORDINAL_IGNORE_CASE, p.identifierFolding, "Legacy 档：$p")
            assertEquals(EventRegistrationPolicy.FOUR_BUCKET, p.eventRegistration, "Legacy 档：$p")
            assertEquals(LazyLoadingPolicy.EAGER_ONLY, p.lazyLoading, "Legacy 档：$p")
            assertEquals(ReloadPolicy.RE_REGISTER, p.reload, "Legacy 档：$p")
            assertEquals(SaveLoadPolicy.RESET_THEN_PARSE, p.saveLoad, "Legacy 档：$p")
            // ENGC-2/4 的四项：eraTW 档恒为 snake 的已取证方言（4.981 实跑口径）。
            assertEquals(EngineFamily.SNAKE_4981, p.engineFamily, "Legacy 档：$p")
            assertEquals(StringCompareDialect.FIXED, p.stringCompare, "Legacy 档：$p")
            assertEquals(NicknameKeywordPolicy.NAME_CALLNAME_ONLY, p.nicknameKeywords, "Legacy 档：$p")
            assertEquals(RelationMasternamePolicy.EXCLUDED, p.relationMastername, "Legacy 档：$p")
        }
    }

    @Test
    fun `缺省构造逐字段等于 LEGACY_ERATW_4981`() {
        // ENGC-2 的第一验收项：新字段全部带缺省 ⇒ 装配方不传的任何旧调用点行为不变。
        assertEquals(
            RuntimeCompatibilityProfile.LEGACY_ERATW_4981,
            RuntimeCompatibilityProfile(CorpusVersion.V4_981, ResolverPolicy.LEGACY_LAST_WINS),
        )
    }

    @Test
    fun `LEGACY 档与构造默认参的三个方言 facet 吃生成物 DIALECT`() {
        // CONTRACT-ENGCPL §3.1 E3：三个方言值只有一个出处（input yaml → 生成物 DIALECT）。
        // 手抄回调 = 换引擎必须重抄，本用例先红。
        val family = EngineProfiles.DEFAULT_TARGET.family
        val dialect = EngineProfileData.DIALECT.getValue(family)
        for (p in listOf(RuntimeCompatibilityProfile.LEGACY_ERATW_4980, RuntimeCompatibilityProfile.LEGACY_ERATW_4981)) {
            assertEquals(family, p.engineFamily, "Legacy 档的家族：$p")
            assertEquals(dialect.stringCompare, p.stringCompare, "Legacy 档的比较方言：$p")
            assertEquals(dialect.nicknameKeywords, p.nicknameKeywords, "Legacy 档的昵称面：$p")
            assertEquals(dialect.relationMastername, p.relationMastername, "Legacy 档的 RELATION 逆引面：$p")
        }
        val bare = RuntimeCompatibilityProfile(CorpusVersion.V4_981, ResolverPolicy.LEGACY_LAST_WINS)
        assertEquals(
            dialect,
            EngineDialect(bare.stringCompare, bare.nicknameKeywords, bare.relationMastername),
            "构造默认参必须逐字段等于 DIALECT[缺省家族]",
        )
        assertEquals(family, bare.engineFamily, "构造默认家族必须等于缺省档家族")
    }

    @Test
    fun `现代档不改变引擎家族与方言`() {
        // D8：Modern 改的是符号解析，不是运行哪家引擎。
        for (v in CorpusVersion.values()) {
            val modern = RuntimeCompatibilityProfile.modern(v)
            assertEquals(EngineFamily.SNAKE_4981, modern.engineFamily, "Modern($v) 的家族")
            assertEquals(StringCompareDialect.FIXED, modern.stringCompare, "Modern($v) 的比较方言")
            assertEquals(
                NicknameKeywordPolicy.NAME_CALLNAME_ONLY,
                modern.nicknameKeywords,
                "Modern($v) 的昵称关键字面",
            )
            assertEquals(
                RelationMasternamePolicy.EXCLUDED,
                modern.relationMastername,
                "Modern($v) 的 RELATION 逆引口径",
            )
        }
    }

    @Test
    fun `语料版本不改变解析策略也不改动其余 facet`() {
        assertNotEquals(
            RuntimeCompatibilityProfile.LEGACY_ERATW_4980,
            RuntimeCompatibilityProfile.LEGACY_ERATW_4981,
            "版本声明必须进相等性（否则缓存指纹/兼容性报告看不出目标语料）",
        )
        assertEquals(
            RuntimeCompatibilityProfile.LEGACY_ERATW_4980.copy(corpusVersion = CorpusVersion.V4_981),
            RuntimeCompatibilityProfile.LEGACY_ERATW_4981,
            "两个 Legacy 档只许差 corpusVersion；要差别的必须先落成具名 facet 并经 ABI 评审",
        )
        for (v in CorpusVersion.values()) {
            val modern = RuntimeCompatibilityProfile.modern(v)
            assertEquals(ResolverPolicy.MODERN, modern.functionResolution, "Modern($v) 的解析策略")
            assertEquals(
                RuntimeCompatibilityProfile(corpusVersion = v, functionResolution = ResolverPolicy.LEGACY_LAST_WINS),
                modern.copy(functionResolution = ResolverPolicy.LEGACY_LAST_WINS),
                "Modern 只许翻转 functionResolution，其余 facet 与 Legacy 同形（$v）",
            )
        }
    }
}
