package com.eratw.port.modtemplate

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FieldKey
import com.eratw.port.modapi.intKey
import com.eratw.port.modapi.refKey
import com.eratw.port.modapi.strKey

/**
 * 手写的键集合 —— 等价于 `:tools:modgen-accessors` 生成物里的 `XxxKeys`。
 *
 * ## 抄写模板时**不要**照抄这一段
 *
 * 真实 mod 的正确流程是：
 * 1. 在 `tools/modgen-accessors/schemas/<name>.schema.tsv` 里写 `schema` / `component` /
 *    `field` / `archetype` 行（第 5 列可覆盖生成的属性名）；
 * 2. 跑 `.\gradlew.bat --configure-on-demand :tools:modgen-accessors:generate<Name>Accessors`，
 *    生成 `XxxKeys` + `XxxView` + `XxxFacade` 到 `mod-<模块名>/src/generated/kotlin`；
 * 3. 模块 `build.gradle.kts` 里 `kotlin.srcDir("src/generated/kotlin")`，生成目录入库，
 *    `:tools:modgen-accessors` 的 `check<Name>Accessors` 闸负责判过期。
 *
 * 本模板**故意不接生成目录**：它要保证干净 clone 上 `:mod-template:compileKotlin` 直接通过，
 * 不需要先跑生成任务。代价是这份手写产物**不受 `check*Accessors` 的过期闸保护** ——
 * schema 改了不会提醒你同步。真实 mod 用生成物就自动有这份保护。
 *
 * ## 抄写划重点
 *
 * - 字段键 = **字段名本身**，不带组件名前缀：引擎字段命名空间是全局一张表（按名字大写寻址），
 *   组件名不参与。拼成带前缀的键不报错，只会静默读回失败哨兵（读 0 / 读空串 / 写 false）。
 * - 字段名建议带自己的包前缀（本模板用 `template.` 前缀），避免与别家 mod 撞全局命名空间。
 * - [EntityId] 字段用 [refKey]（INT 列存、按引用重映射）；普通整数用 [intKey]。
 */
object TemplateKeys {

    /** 组件名（`ModManifest.components` 里的同一个名字）。 */
    val component: ComponentKey = ComponentKey("template.profile")

    /** 原型名（`ModManifest.archetypes` 里的同一个名字）。 */
    val archetype: ArchetypeKey = ArchetypeKey("template.actor")

    /** INT 字段：演员等级。 */
    val level: FieldKey<Long> = intKey("template.level")

    /** STR 字段：演员昵称。 */
    val nickname: FieldKey<String> = strKey("template.nickname")

    /** EntityRef 字段：师承引用（写 [EntityId]，不用自己碰 `.raw`）。 */
    val mentor: FieldKey<EntityId> = refKey("template.mentor")
}
