package com.eratw.port.modmemory.generated

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FieldKey
import com.eratw.port.modapi.create
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.intKey
import com.eratw.port.modapi.refKey
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setRef
import com.eratw.port.modapi.setStr
import com.eratw.port.modapi.strKey

/**
 * 生成物，请勿手改。
 *
 * 源 = `<base>.schema.tsv`（`schema` com.eratw.port.modmemory.generated，组件 `memory.factData`），由 `:tools:modgen-accessors` 生成。
 *
 * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，
 * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 */
object MemoryKeys {
    val component: ComponentKey = ComponentKey("memory.factData")

    val archetype: ArchetypeKey = ArchetypeKey("memory.fact")

    /** INT 字段。 */
    val subject: FieldKey<EntityId> = refKey("subject")

    /** INT 字段。 */
    val obj: FieldKey<EntityId> = refKey("object")

    /** INT 字段。 */
    val source: FieldKey<EntityId> = refKey("source")

    /** STR 字段。 */
    val predicate: FieldKey<String> = strKey("predicate")

    /** STR 字段。 */
    val tags: FieldKey<String> = strKey("tags")

    /** INT 字段。 */
    val occurredAt: FieldKey<Long> = intKey("occurredAt")

    /** INT 字段。 */
    val acquiredAt: FieldKey<Long> = intKey("acquiredAt")

    /** INT 字段。 */
    val provenance: FieldKey<Long> = intKey("provenance")

    /** INT 字段。 */
    val confidence: FieldKey<Long> = intKey("confidence")

    /** INT 字段。 */
    val visibility: FieldKey<Long> = intKey("visibility")

    /** INT 字段。 */
    val importance: FieldKey<Long> = intKey("importance")
}

/** `memory.factData` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */
class MemoryView internal constructor(
    private val entities: EntityApi,
    /** 实体身份。 */
    val id: EntityId,
) {
    var subject: EntityId
        get() = entities.getRef(id, MemoryKeys.subject)
        set(value) {
            entities.setRef(id, MemoryKeys.subject, value)
        }

    var obj: EntityId
        get() = entities.getRef(id, MemoryKeys.obj)
        set(value) {
            entities.setRef(id, MemoryKeys.obj, value)
        }

    var source: EntityId
        get() = entities.getRef(id, MemoryKeys.source)
        set(value) {
            entities.setRef(id, MemoryKeys.source, value)
        }

    var predicate: String
        get() = entities.getStr(id, MemoryKeys.predicate)
        set(value) {
            entities.setStr(id, MemoryKeys.predicate, value)
        }

    var tags: String
        get() = entities.getStr(id, MemoryKeys.tags)
        set(value) {
            entities.setStr(id, MemoryKeys.tags, value)
        }

    var occurredAt: Long
        get() = entities.getInt(id, MemoryKeys.occurredAt)
        set(value) {
            entities.setInt(id, MemoryKeys.occurredAt, value)
        }

    var acquiredAt: Long
        get() = entities.getInt(id, MemoryKeys.acquiredAt)
        set(value) {
            entities.setInt(id, MemoryKeys.acquiredAt, value)
        }

    var provenance: Long
        get() = entities.getInt(id, MemoryKeys.provenance)
        set(value) {
            entities.setInt(id, MemoryKeys.provenance, value)
        }

    var confidence: Long
        get() = entities.getInt(id, MemoryKeys.confidence)
        set(value) {
            entities.setInt(id, MemoryKeys.confidence, value)
        }

    var visibility: Long
        get() = entities.getInt(id, MemoryKeys.visibility)
        set(value) {
            entities.setInt(id, MemoryKeys.visibility, value)
        }

    var importance: Long
        get() = entities.getInt(id, MemoryKeys.importance)
        set(value) {
            entities.setInt(id, MemoryKeys.importance, value)
        }
}

/** `memory.factData` 组件的创建/绑定门面。 */
class MemoryFacade(private val entities: EntityApi) {
    /** 建实体。`create` 必须是多步写的第一步：freeze 之后唯一的失败模式是实体不存在。 */
    fun create(): MemoryView = MemoryView(entities, entities.create(MemoryKeys.archetype))

    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): MemoryView = MemoryView(entities, id)
}
