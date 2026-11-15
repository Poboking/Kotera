package com.eratw.port.modlivingworld

import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl

/**
 * 关系 Mod（eratw.lw.relationship）的**全部声明，只此一份真源**（§21.9 判据 4「双向独立
 * 关系数据」与「两 Mod 合法扩展同一关系空间不互相覆盖」的声明面）。
 *
 * ## 模型
 *
 * 好感是**有向边**：`A --lw.rel.affinity--> B` 与 `B --lw.rel.affinity--> A` 是两条独立边，
 * 边值（关系度）各自独立 —— 「双向独立关系数据」由 directed = true 的单一边型承担，A→B
 * 写 5 不动 B→A 的 3。边值语义由本 Mod 给（引擎只保证 Long 边值随边持久化，§7 关系投影协议）。
 *
 * ## 与事件 Mod 的共存（判据 4 正向半边）
 *
 * 事件 Mod（[EventDeclarations]）在同一对实体上声明**自己的**边型 `lw.rel.tension` 与
 * **自己的**字段（全局命名空间各占一名）：两包经声明事务（同一次
 * freeze）共存，互不覆盖 —— 各自 kind 的边值、各自字段的数据都独立持久。
 * **重名 fail-closed**：谁想声明同名边型/同名字段，`DeclarationRegistry.freeze` 当场
 * 抛 DeclarationConflictException 整包拒绝（负向半边由 LivingWorldDeclarationTest 钉住）。
 */
object RelationshipDeclarations {

    const val PACKAGE_ID: String = "eratw.lw.relationship"

    /** 关系数据组件（判据 4「各自字段」半边）： bondData 挂在原型上承载自由文本备忘。 */
    const val COMPONENT_BOND_DATA: String = "lw.rel.bondData"

    /** 关系备忘原型（最小形状；字段写不检原型挂载，挂载只为查询圈定）。 */
    const val ARCHETYPE_PAIR: String = "lw.rel.pair"

    /** 备忘字段（全局命名空间，lw 前缀防撞）。 */
    const val FIELD_BOND_NOTES: String = "lwBondNotes"

    /** 好感边型：有向、去重、destroy 级联清边（缺省组），边值 = 好感度（Mod 语义）。 */
    const val RELATION_AFFINITY: String = "lw.rel.affinity"

    val manifest: ModManifest = ModManifest(
        packageId = PACKAGE_ID,
        version = "0.1",
        components = listOf(
            ComponentDecl(
                name = COMPONENT_BOND_DATA,
                fields = listOf(
                    FieldDecl(FIELD_BOND_NOTES, FieldType.STR),
                ),
            ),
        ),
        archetypes = listOf(
            ArchetypeDecl(ARCHETYPE_PAIR, listOf(COMPONENT_BOND_DATA)),
        ),
        relationships = listOf(
            // 双向独立 = 两条方向相反的有向边各自持值；不设 autoInverse（反向是独立数据，
            // 不是同一份数据的镜像 —— 这正是「双向独立」的声明面表达）。
            RelationshipDecl(name = RELATION_AFFINITY, directed = true),
        ),
    )
}
