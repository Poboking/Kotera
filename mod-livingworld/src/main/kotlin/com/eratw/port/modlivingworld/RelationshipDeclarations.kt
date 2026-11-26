package com.eratw.port.modlivingworld

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modlivingworld.generated.BondKeys

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
 * ## 字段名不在这里
 *
 * 组件、原型与备忘字段的名字由 [BondKeys] 提供（`:tools:modgen-accessors` 生成），声明与
 * [com.eratw.port.modlivingworld.generated.BondView] 的读写列指着同一批键。
 *
 * ## 与事件 Mod 的共存（判据 4 正向半边）
 *
 * 事件 Mod（[EventDeclarations]）在同一对实体上声明**自己的**边型 `lw.rel.tension` 与
 * **自己的**字段（全局命名空间各占一名）：两包经声明事务（同一次
 * freeze）共存，互不覆盖 —— 各自 kind 的边值、各自字段的数据都独立持久。
 * **重名 fail-closed**：谁想声明同名边型/同名字段，`DeclarationRegistry.freeze` 当场
 * 抛 DeclarationConflictException 整包拒绝（负向半边由 LivingWorldDeclarationTest 钉住）。
 */
@ModCarrier
object RelationshipDeclarations : ModCarrierDeclaration {

    const val PACKAGE_ID: String = "eratw.lw.relationship"

    /** [ModCarrierDeclaration] 的实现面；与 [PACKAGE_ID] 同值，保留常量给既有调用面。 */
    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = RelationshipMod()

    /** 好感边型：有向、去重、destroy 级联清边（缺省组），边值 = 好感度（Mod 语义）。 */
    val AFFINITY: RelationKey = RelationKey("lw.rel.affinity")

    /**
     * 本载体的全部声明。`override` 是 [ModCarrierDeclaration] 的实现面 —— 构建期注册表
     * 从各声明 object 直接取这份 manifest 编资产，不再由 `BuiltinManifestBuild` 逐个点名。
     */
    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("生活世界 · 关系")
        author("eratw-port")
        description("双向好感度与关系备注")
        component(BondKeys.component) {
            str(BondKeys.bondNotes)
        }
        archetype(BondKeys.archetype) {
            uses(BondKeys.component)
        }
        // 双向独立 = 两条方向相反的有向边各自持值；不设 inverse（反向是独立数据，
        // 不是同一份数据的镜像 —— 这正是「双向独立」的声明面表达）。
        relation(AFFINITY)
    }
}
