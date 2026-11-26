package com.eratw.port.modtemplate

import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.create
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setRef
import com.eratw.port.modapi.setStr

/**
 * 手写的类型化视图 + 门面 —— 等价于生成物里的 `XxxView` / `XxxFacade`。
 *
 * ## 抄写模板时**不要**照抄这一段
 *
 * 真实 mod 由 `tools/modgen-accessors` 生成（见 [TemplateKeys] 的 KDoc）。这里手写是为了让
 * 「clone 即编译」成立，顺便演示生成物应该长成什么样。
 *
 * ## 为什么业务代码值得多这一层
 *
 * [`TemplateView`] 的 `var` 属性读写直接落到实体列上：业务代码写 `view.level = 3`，
 * 而不是 `entities.setInt(id, "LEVEL", 3)`。字段名拼错从「静默读回 0」变成编译错误，
 * 并且整条 mod 侧链 0 个 `.raw`（引用字段的位编码/解包只发生在这一层）。
 */
class TemplateView internal constructor(
    private val entities: EntityApi,
    /** 实体身份；跨帧/跨天持久化只存它（charIndex 是瞬时行号，禁止持久化）。 */
    val id: EntityId,
) {

    /** 等级（读失败哨兵 = 0）。 */
    var level: Long
        get() = entities.getInt(id, TemplateKeys.level)
        set(value) {
            entities.setInt(id, TemplateKeys.level, value)
        }

    /** 昵称（读失败哨兵 = 空串）。 */
    var nickname: String
        get() = entities.getStr(id, TemplateKeys.nickname)
        set(value) {
            entities.setStr(id, TemplateKeys.nickname, value)
        }

    /** 师承引用（读失败哨兵 = [EntityId.NULL]）。 */
    var mentor: EntityId
        get() = entities.getRef(id, TemplateKeys.mentor)
        set(value) {
            entities.setRef(id, TemplateKeys.mentor, value)
        }
}

/** `template.profile` 组件的创建 / 绑定门面。 */
class TemplateFacade(private val entities: EntityApi) {

    /** 建实体。**多步写的第一步必须是 `create`**：freeze 之后唯一的失败模式是实体不存在。 */
    fun create(): TemplateView = TemplateView(entities, entities.create(TemplateKeys.archetype))

    /** 绑定一个已存在的实体。 */
    fun of(id: EntityId): TemplateView = TemplateView(entities, id)
}
