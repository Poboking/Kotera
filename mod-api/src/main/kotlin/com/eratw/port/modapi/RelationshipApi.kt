package com.eratw.port.modapi

/**
 * 关系能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3，wrap `IEntityKernel` 关系面 +
 * `RelationProjectionSynchronizer` 关系投影协议 M5-1）。
 *
 * 亲属/师徒/从属等**有向边**统一经本 API；mod 不自维护关系图。关系类型须先在声明事务注册
 * （[ModManifest.relationships] → DeclarationRegistry 的 relationship-schemas 域）；方向语义、
 * 去重、级联、自动反向边都是 mod 声明的业务含义（§7），引擎不替 mod 猜。
 *
 * ## 边值（关系度）
 *
 * 边恒带一个 Long 值（关系度，§7 关系投影协议：原生 RELATION 格是 Long 值，退场要无损同步回
 * 实体图）。[getRelationValue] 无边返回 0；[setRelationValue] 改既有边的值，**不隐式建边**
 * （边不存在/类型未注册返回 false，与「不隐式建列」同纪律）。
 *
 * clean 语义（§18.7）：取干净口径，不复刻官方 bug；无向边两个方向写到同一条边（值只存一份）。
 */
interface RelationshipApi {

    /** 关系类型是否已注册（声明事务先行）。 */
    fun hasKind(kind: String): Boolean

    /** 有向边 from --kind--> to。任一端不存在或类型未注册返回 false。 */
    fun add(from: EntityId, kind: String, to: EntityId): Boolean

    fun remove(from: EntityId, kind: String, to: EntityId): Boolean

    fun has(from: EntityId, kind: String, to: EntityId): Boolean

    /** 边值（关系度）；无边返回 0。无向边按同一条边读。 */
    fun valueOf(from: EntityId, kind: String, to: EntityId): Long

    /** 改既有边的值；边不存在/类型未注册返回 false（不隐式建边）。 */
    fun setValue(from: EntityId, kind: String, to: EntityId, value: Long): Boolean

    /**
     * 邻接查询：[outgoing] = true 出边 / false 入边。返回 [EntityId] 数值升序的快照列表。
     */
    fun neighbors(id: EntityId, kind: String, outgoing: Boolean): List<EntityId>
}