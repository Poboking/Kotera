package com.eratw.port.modmemory

import com.eratw.port.modapi.EntityId

/**
 * 一条知识的全字段值对象（判据 2 的五类语义载体；字段键 = [com.eratw.port.modmemory.generated.MemoryKeys]）。
 *
 * [MemoryMod.record] 按本对象一次落全字段；业务改单个字段走
 * [com.eratw.port.modmemory.generated.MemoryView] 的属性（如衰减只动 confidence）。
 * data class 构造器带命名参数即自文档，detekt 的 LongParameterList 对 data class 豁免
 * （ignoreDataClasses 默认档）。
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
