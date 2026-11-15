package com.eratw.port.modapi

/**
 * 实体能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3，wrap `IEntityKernel`/`EntityKernel` M4-1）。
 *
 * 统一实体系统入口：mod **不自维护实体库**，创建/查询/修改/销毁都经本 API 进 Runtime Core。
 * 字段（Component）按名字寻址，须先在声明事务注册（[ModManifest.components] → DeclarationRegistry
 * 的 component-schemas 域）；未注册字段的读写返回失败哨兵而不是隐式建列（§5 声明先行）。
 *
 * ## 转移边界（§18.4 / §7）
 *
 * [create]/[destroy]/[setState] 等改实体结构的操作只在转移边界放行（ERB 不在执行时）；
 * mod 经 [RuntimeScheduler.submit] 排队，不在后台线程直接调。
 *
 * clean 语义（§18.7）：失败哨兵取干净口径——[getInt] 未注册/不存在返回 0、[getStr] 返回 ""、
 * 写操作返回 false；不复刻官方 VM 怪癖（ADR-005 只约束 ERB/vanilla 面）。
 */
interface EntityApi {

    /**
     * 建实体（初始 [EntityState.ACTIVE]）。[archetype] 须已注册（[ModManifest.archetypes]）；
     * 返回新 [EntityId]。archetype 未注册时抛 [IllegalArgumentException]（声明先行，fail-closed）。
     */
    fun create(archetype: String): EntityId

    /** 真删：级联清关系边、槽位 generation 递增。不存在返回 false。 */
    fun destroy(id: EntityId): Boolean

    /** 存活判定（全位域比对 + 未销毁；[EntityState.RETIRED] 也算存在）。 */
    fun exists(id: EntityId): Boolean

    /** 生命周期状态；不存在返回 null（干净口径，不暴露引擎的 0 哨兵）。 */
    fun stateOf(id: EntityId): EntityState?

    /** 改生命周期状态；不存在或状态非法返回 false。 */
    fun setState(id: EntityId, state: EntityState): Boolean

    /** 整型组件字段；未注册字段/不存在返回 0。 */
    fun getInt(id: EntityId, field: String): Long

    fun setInt(id: EntityId, field: String, value: Long): Boolean

    /** 字符串组件字段；未注册字段/不存在返回 ""。 */
    fun getStr(id: EntityId, field: String): String

    fun setStr(id: EntityId, field: String, value: String): Boolean

    /**
     * 类型化实体引用读取（RT2 车次表 E 车 / P1-6 ②，era-arch-v61u.md §22.5）。
     *
     * **与 [getInt] 同源、不改列存**：本方法只把同一张 INT 列的 Long 包装成 [EntityId]，
     * 引擎侧不新增列类型、不动 EntityIdCodec、不动 .rt2 格式。字段须在声明事务里
     * 标 `isEntityRef = true`（[ModManifest.components] → schema）。未注册字段/不存在
     * 返回 [EntityId.NULL]（即 [getInt] 的 0 哨兵被包装后的形状）。
     *
     * 业务 mod 写 `accessors.father(child)` 得到的就是 [EntityId]，不该自己碰 `.raw`；
     * 类型化访问器由 `:tools:modgen-accessors` 生成，生成物里 0 个 `.raw`。
     *
     * TODO(verify)：本方法是移植侧 RT2 自建公开面，原版 Emuera 无对应 SDK 方法可比；
     * 卡点 = 无官方 SDK 可比，只能以 §22.5 P1-6 ② 与真引擎 INT 列对拍作依据。
     */
    fun getRef(id: EntityId, field: String): EntityId

    /**
     * 类型化实体引用写入，返回是否成功（失败哨兵同 [setInt]）。
     *
     * **与 [setInt] 同源、不改列存**：只是把 [value] 的位域解包进同一张 INT 列；
     * 引擎侧实现 `KernelEntityApi.setRef` = `kernel.setInt(id.raw, field, value.raw)`。
     * 于是「经 [setRef] 写、[getInt] 读」与「经 [setInt] 写、[getRef] 读」逐位等价，
     * 声明事务 / ManifestCodec / 存档格式零接触。
     */
    fun setRef(id: EntityId, field: String, value: EntityId): Boolean

    /**
     * 按 archetype + 生命周期筛实体（[state] = null 表示不限）。
     * 返回 [EntityId] 数值升序的快照列表（与引擎 ENTITY_QUERY 同口径），mod 可自由持有。
     */
    fun query(archetype: String, state: EntityState? = null): List<EntityId>
}