package com.eratw.port.modmemory

import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId

/**
 * 一条知识的全字段值对象（判据 2 的五类语义载体；字段键 = [MemoryDeclarations] 的 FIELD_*）。
 *
 * [MemoryAccessors.write] 按本对象一次落全字段；业务改单个字段走 [MemoryAccessors] 的
 * 热路径访问器（如衰减只动 confidence）。data class 构造器带命名参数即自文档，
 * detekt 的 LongParameterList 对 data class 豁免（ignoreDataClasses 默认档）。
 */
data class FactValues(
    /** 事实关于谁（可为 [EntityId.NULL]：关于世界而非某人的事实）。 */
    val subject: EntityId,
    /** 事实的宾语侧；无宾语传 [EntityId.NULL]。 */
    val obj: EntityId = EntityId.NULL,
    /** 知识来源（讲述者）；owner 亲历时 = owner 自身。 */
    val source: EntityId = EntityId.NULL,
    /** 谓词：这条事实断言什么（如「结识」「赠予」）。 */
    val predicate: String,
    /** 自由标签，多值用半角逗号分隔（约定不进引擎）。 */
    val tags: String = "",
    /** 事实发生的游戏日。 */
    val occurredAt: Long = 0L,
    /** owner 获知的游戏日。 */
    val acquiredAt: Long = 0L,
    /** 首录者入账来源（进 INT 列与 KNOWS 边值，见 [Provenance]）。 */
    val provenance: Provenance = Provenance.FIRSTHAND,
    /** owner 对它的主观分量。 */
    val importance: Importance = Importance.NORMAL,
    /** 谁知道这条知识的存在。 */
    val visibility: Visibility = Visibility.PRIVATE,
    /** 置信度 0..100 闭区间（0 = 全然不信，100 = 深信不疑）。 */
    val confidence: Long,
)

/**
 * 记忆 Mod 的**手写薄访问器**（契约判据 8 允许「modgen-accessors 或手写薄访问器皆可」；
 * 本模块字段一次性声明完毕、无生成器链，手写一层最薄间接即可）。
 *
 * 职责与 `:tools:modgen-accessors` 生成物同形：把字段名字符串收拢到一处，业务代码写类型
 * 不写字符串、更**看不到 `.raw`**（实体引用走 [EntityApi.getRef]/[EntityApi.setRef]，
 * 位编码的包装/解包只发生在引擎侧，照 `EntityApi.kt:62` KDoc）。
 *
 * 真源一致性：字段键逐项 = [MemoryDeclarations] 的 FIELD_* 常量，由
 * `MemoryModTest.declarationsAndAccessorsDescribeTheSameFiveCategorySchema` 钉住
 * （声明改了不改这里 = 红）。
 */
class MemoryAccessors(private val entities: EntityApi) {

    /** 全字段一次落盘（[record] 的写路径；字段顺序即 [MemoryDeclarations] 声明序）。 */
    fun write(fact: EntityId, v: FactValues) {
        entities.setRef(fact, MemoryDeclarations.FIELD_SUBJECT, v.subject)
        entities.setRef(fact, MemoryDeclarations.FIELD_OBJECT, v.obj)
        entities.setRef(fact, MemoryDeclarations.FIELD_SOURCE, v.source)
        entities.setStr(fact, MemoryDeclarations.FIELD_PREDICATE, v.predicate)
        entities.setStr(fact, MemoryDeclarations.FIELD_TAGS, v.tags)
        entities.setInt(fact, MemoryDeclarations.FIELD_OCCURRED_AT, v.occurredAt)
        entities.setInt(fact, MemoryDeclarations.FIELD_ACQUIRED_AT, v.acquiredAt)
        entities.setInt(fact, MemoryDeclarations.FIELD_PROVENANCE, v.provenance.code)
        entities.setInt(fact, MemoryDeclarations.FIELD_IMPORTANCE, v.importance.code)
        entities.setInt(fact, MemoryDeclarations.FIELD_VISIBILITY, v.visibility.code)
        entities.setInt(fact, MemoryDeclarations.FIELD_CONFIDENCE, v.confidence)
    }

    /** 衰减热路径：读置信度（衰减每游戏日对全量 fact 各读一次）。 */
    fun confidence(fact: EntityId): Long =
        entities.getInt(fact, MemoryDeclarations.FIELD_CONFIDENCE)

    /** 衰减热路径：写置信度。 */
    fun setConfidence(fact: EntityId, v: Long): Boolean =
        entities.setInt(fact, MemoryDeclarations.FIELD_CONFIDENCE, v)

    /** 衰减热路径：读首录来源码值（选衰减率用）。 */
    fun provenance(fact: EntityId): Long =
        entities.getInt(fact, MemoryDeclarations.FIELD_PROVENANCE)
}
