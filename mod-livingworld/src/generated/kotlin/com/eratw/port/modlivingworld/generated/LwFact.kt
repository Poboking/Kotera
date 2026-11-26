package com.eratw.port.modlivingworld.generated

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
 * 源 = `<base>.schema.tsv`（`schema` com.eratw.port.modlivingworld.generated，组件 `lw.mem.factData`），由 `:tools:modgen-accessors` 生成。
 *
 * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，
 * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 */
object LwFactKeys {
    val component: ComponentKey = ComponentKey("lw.mem.factData")

    val archetype: ArchetypeKey = ArchetypeKey("lw.mem.fact")

    /** INT 字段。 */
    val subject: FieldKey<EntityId> = refKey("lwMemSubject")

    /** STR 字段。 */
    val predicate: FieldKey<String> = strKey("lwMemPredicate")

    /** INT 字段。 */
    val occurredAt: FieldKey<Long> = intKey("lwMemAt")
}

/** `lw.mem.factData` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */
class LwFactView internal constructor(
    private val entities: EntityApi,
    /** 实体身份。 */
    val id: EntityId,
) {
    var subject: EntityId
        get() = entities.getRef(id, LwFactKeys.subject)
        set(value) {
            entities.setRef(id, LwFactKeys.subject, value)
        }

    var predicate: String
        get() = entities.getStr(id, LwFactKeys.predicate)
        set(value) {
            entities.setStr(id, LwFactKeys.predicate, value)
        }

    var occurredAt: Long
        get() = entities.getInt(id, LwFactKeys.occurredAt)
        set(value) {
            entities.setInt(id, LwFactKeys.occurredAt, value)
        }
}

/** `lw.mem.factData` 组件的创建/绑定门面。 */
class LwFactFacade(private val entities: EntityApi) {
    /** 建实体。`create` 必须是多步写的第一步：freeze 之后唯一的失败模式是实体不存在。 */
    fun create(): LwFactView = LwFactView(entities, entities.create(LwFactKeys.archetype))

    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): LwFactView = LwFactView(entities, id)
}
