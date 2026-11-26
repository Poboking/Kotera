package com.eratw.port.modlivingworld.generated

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FieldKey
import com.eratw.port.modapi.create
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.intKey
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setStr
import com.eratw.port.modapi.strKey

/**
 * 生成物，请勿手改。
 *
 * 源 = `<base>.schema.tsv`（`schema` com.eratw.port.modlivingworld.generated，组件 `lw.char.persona`），由 `:tools:modgen-accessors` 生成。
 *
 * 字段键 = **字段名本身**，不带组件名前缀：引擎的字段命名空间是全局一张表，按名字大写寻址，
 * 组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 */
object NpcKeys {
    val persona: ComponentKey = ComponentKey("lw.char.persona")
    val schedule: ComponentKey = ComponentKey("lw.char.schedule")

    val archetype: ArchetypeKey = ArchetypeKey("lw.char.npc")

    /** INT 字段。 */
    val warmth: FieldKey<Long> = intKey("lwWarmth")

    /** INT 字段。 */
    val grit: FieldKey<Long> = intKey("lwGrit")

    /** INT 字段。 */
    val eveningSlot: FieldKey<Long> = intKey("lwEveningSlot")

    /** STR 字段。 */
    val activity: FieldKey<String> = strKey("lwActivity")

    /** INT 字段。 */
    val craftStage: FieldKey<Long> = intKey("lwCraftStage")
}

/** `lw.char.persona`, `lw.char.schedule` 组件的类型化视图：`var` 属性的读写直接落到实体列上。 */
class NpcView internal constructor(
    private val entities: EntityApi,
    /** 实体身份。 */
    val id: EntityId,
) {
    var warmth: Long
        get() = entities.getInt(id, NpcKeys.warmth)
        set(value) {
            entities.setInt(id, NpcKeys.warmth, value)
        }

    var grit: Long
        get() = entities.getInt(id, NpcKeys.grit)
        set(value) {
            entities.setInt(id, NpcKeys.grit, value)
        }

    var eveningSlot: Long
        get() = entities.getInt(id, NpcKeys.eveningSlot)
        set(value) {
            entities.setInt(id, NpcKeys.eveningSlot, value)
        }

    var activity: String
        get() = entities.getStr(id, NpcKeys.activity)
        set(value) {
            entities.setStr(id, NpcKeys.activity, value)
        }

    var craftStage: Long
        get() = entities.getInt(id, NpcKeys.craftStage)
        set(value) {
            entities.setInt(id, NpcKeys.craftStage, value)
        }
}

/** `lw.char.persona`, `lw.char.schedule` 组件的创建/绑定门面。 */
class NpcFacade(private val entities: EntityApi) {
    /** 建实体。`create` 必须是多步写的第一步：freeze 之后唯一的失败模式是实体不存在。 */
    fun create(): NpcView = NpcView(entities, entities.create(NpcKeys.archetype))

    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): NpcView = NpcView(entities, id)
}
