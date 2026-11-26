package com.eratw.port.modfamily.generated

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
 * 源 = `<base>.schema.tsv`（`schema` com.eratw.port.modfamily.generated，组件 `family`），由 `:tools:modgen-accessors` 生成。
 *
 * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，
 * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 */
object FamilyKeys {
    val component: ComponentKey = ComponentKey("family")

    val archetype: ArchetypeKey = ArchetypeKey("family.child")

    /** INT 字段。 */
    val age: FieldKey<Long> = intKey("age")

    /** STR 字段。 */
    val title: FieldKey<String> = strKey("title")

    /** INT 字段。 */
    val father: FieldKey<EntityId> = refKey("father")
}

/** `family` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */
class FamilyView internal constructor(
    private val entities: EntityApi,
    /** 实体身份。 */
    val id: EntityId,
) {
    var age: Long
        get() = entities.getInt(id, FamilyKeys.age)
        set(value) {
            entities.setInt(id, FamilyKeys.age, value)
        }

    var title: String
        get() = entities.getStr(id, FamilyKeys.title)
        set(value) {
            entities.setStr(id, FamilyKeys.title, value)
        }

    var father: EntityId
        get() = entities.getRef(id, FamilyKeys.father)
        set(value) {
            entities.setRef(id, FamilyKeys.father, value)
        }
}

/** `family` 组件的创建/绑定门面。 */
class FamilyFacade(private val entities: EntityApi) {
    /** 建实体。`create` 必须是多步写的第一步：freeze 之后唯一的失败模式是实体不存在。 */
    fun create(): FamilyView = FamilyView(entities, entities.create(FamilyKeys.archetype))

    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): FamilyView = FamilyView(entities, id)
}
