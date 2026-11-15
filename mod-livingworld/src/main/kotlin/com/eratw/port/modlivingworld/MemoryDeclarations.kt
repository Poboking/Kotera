package com.eratw.port.modlivingworld

import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl

/**
 * Living World 记忆 Mod（eratw.lw.memory）的**全部声明，只此一份真源**（§21.9 判据 3
 * 「亲历不自动传播」的声明面；era-arch-v61u.md §21.9 / §21.10 M10-1）。
 *
 * ## 为什么自声明而不复用 eratw.memory（交付说明口径）
 *
 * 判据 3 冻结口径给两条路：「记忆包按 M91 模型自声明或复用 eratw.memory」。本包取
 * **自声明最小模型**：四逻辑包按波次要求全部定义在 `:mod-livingworld` 一个 Gradle 模块内、
 * 各自独立 packageId；复用 eratw.memory 会把跨 Gradle 模块依赖与跨包实体归属搅进演示面。
 * 模型形状照 M91 已收口的先例（fact 实体 + `owner --KNOWS--> fact` 有向边，边值 = 认知
 * 来源码），字段名全部 `lw` 前缀（全局字段命名空间防撞），语义最小化：判据 3 只需要
 * 「谁、关于谁、什么事、何时 + 亲历/听闻」五类里的四类。
 */
object MemoryDeclarations {

    const val PACKAGE_ID: String = "eratw.lw.memory"

    /** 知识持有者原型（测试/演示用最小形状）。 */
    const val ARCHETYPE_PERSON: String = "lw.mem.person"

    /** 知识（fact）原型：[COMPONENT_FACT] 是它唯一挂载的组件。 */
    const val ARCHETYPE_FACT: String = "lw.mem.fact"

    const val COMPONENT_FACT: String = "lw.mem.factData"

    /** 这条事实关于谁（实体引用三类之一）。 */
    const val FIELD_SUBJECT: String = "lwMemSubject"

    /** 谓词：这条事实断言什么（文本类）。 */
    const val FIELD_PREDICATE: String = "lwMemPredicate"

    /** 事实发生的游戏日（游戏日类）。 */
    const val FIELD_OCCURRED_AT: String = "lwMemAt"

    /**
     * 知识边：`owner --KNOWS--> fact`，有向、无自动反向边、destroy 级联清边（缺省组）。
     * **知识只经 KNOWS 边可达**：引擎不做任何自动传播，「A 亲历的事实不会自动成为 B 的
     * 知识」由 [LivingWorldCombinationTest] 钉住 —— B 想知道，必须有人显式调
     * [MemoryMod.teach] 给 B 挂上自己的边。边值 = 该 owner 持有这条知识的
     * [Provenance].code（同一条 fact 可被多人分别 KNOWS，各人认知来源挂在各自的边上）。
     */
    const val RELATION_KNOWS: String = "lw.mem.knows"

    val manifest: ModManifest = ModManifest(
        packageId = PACKAGE_ID,
        version = "0.1",
        components = listOf(
            ComponentDecl(
                name = COMPONENT_FACT,
                fields = listOf(
                    FieldDecl(FIELD_SUBJECT, FieldType.INT, isEntityRef = true),
                    FieldDecl(FIELD_PREDICATE, FieldType.STR),
                    FieldDecl(FIELD_OCCURRED_AT, FieldType.INT),
                ),
            ),
        ),
        archetypes = listOf(
            ArchetypeDecl(ARCHETYPE_PERSON),
            ArchetypeDecl(ARCHETYPE_FACT, listOf(COMPONENT_FACT)),
        ),
        relationships = listOf(
            RelationshipDecl(name = RELATION_KNOWS, directed = true),
        ),
    )
}

/**
 * [MemoryDeclarations.RELATION_KNOWS] 边值（认知来源）的取值枚举（判据 3 冻结口径）：
 * 至少含 FIRSTHAND / HEARSAY 两档；码值进边值，一经发布即固化，只增不改。
 */
enum class Provenance(val code: Long) {
    /** owner 亲历（亲眼所见/亲手所为）：只有首录者可以以此档入账。 */
    FIRSTHAND(1L),

    /** 听闻（他人转述）：经 [MemoryMod.teach] 学来的知识对学习者落此档。 */
    HEARSAY(2L),
}
