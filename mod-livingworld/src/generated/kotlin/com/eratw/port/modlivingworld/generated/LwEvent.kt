package com.eratw.port.modlivingworld.generated

import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FieldKey
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.intKey
import com.eratw.port.modapi.setInt

/**
 * 生成物，请勿手改。
 *
 * 源 = `<base>.schema.tsv`（`schema` com.eratw.port.modlivingworld.generated，组件 `lw.event.eventData`），由 `:tools:modgen-accessors` 生成。
 *
 * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，
 * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 */
object LwEventKeys {
    val component: ComponentKey = ComponentKey("lw.event.eventData")

    /** INT 字段。 */
    val seen: FieldKey<Long> = intKey("lwEventSeen")
}

/** `lw.event.eventData` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */
class LwEventView internal constructor(
    private val entities: EntityApi,
    /** 实体身份。 */
    val id: EntityId,
) {
    var seen: Long
        get() = entities.getInt(id, LwEventKeys.seen)
        set(value) {
            entities.setInt(id, LwEventKeys.seen, value)
        }
}

/** `lw.event.eventData` 组件的创建/绑定门面。 */
class LwEventFacade(private val entities: EntityApi) {
    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): LwEventView = LwEventView(entities, id)
}
