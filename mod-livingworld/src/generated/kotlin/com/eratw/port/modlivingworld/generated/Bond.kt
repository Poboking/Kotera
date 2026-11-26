package com.eratw.port.modlivingworld.generated

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FieldKey
import com.eratw.port.modapi.create
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.setStr
import com.eratw.port.modapi.strKey

/**
 * 生成物，请勿手改。
 *
 * 源 = `<base>.schema.tsv`（`schema` com.eratw.port.modlivingworld.generated，组件 `lw.rel.bondData`），由 `:tools:modgen-accessors` 生成。
 *
 * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，
 * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 */
object BondKeys {
    val component: ComponentKey = ComponentKey("lw.rel.bondData")

    val archetype: ArchetypeKey = ArchetypeKey("lw.rel.pair")

    /** STR 字段。 */
    val bondNotes: FieldKey<String> = strKey("lwBondNotes")
}

/** `lw.rel.bondData` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */
class BondView internal constructor(
    private val entities: EntityApi,
    /** 实体身份。 */
    val id: EntityId,
) {
    var bondNotes: String
        get() = entities.getStr(id, BondKeys.bondNotes)
        set(value) {
            entities.setStr(id, BondKeys.bondNotes, value)
        }
}

/** `lw.rel.bondData` 组件的创建/绑定门面。 */
class BondFacade(private val entities: EntityApi) {
    /** 建实体。`create` 必须是多步写的第一步：freeze 之后唯一的失败模式是实体不存在。 */
    fun create(): BondView = BondView(entities, entities.create(BondKeys.archetype))

    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): BondView = BondView(entities, id)
}
