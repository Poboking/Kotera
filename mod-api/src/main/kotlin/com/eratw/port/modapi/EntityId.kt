package com.eratw.port.modapi

/**
 * 扩展实体的稳定身份（RT2 Kotlin Mod SDK，era-arch-v61u.md §7/§18.3）。
 *
 * 这是 mod 作者看到的**类型安全包装**：底层就是一个 Long，与引擎 `EntityIdCodec` 编码的
 * 64 位位域（pkgShortId + generation + handleSlot）逐位同值。mod 不自己编码/解码位域
 * （那是引擎侧 `EntityIdCodec` 的职责），只持有引擎经 [EntityApi.create] 发下来的 [EntityId]。
 *
 * ## Mod 引用铁律（§7，与 ICharaSlotProjector 同口径）
 *
 * 跨帧/跨天持久化引用扩展角色**只存 [EntityId]**，用前经引擎现换 charIndex；严禁持久化
 * 临时 charIndex（行排列/删除随时发生）。[EntityId] 是终身不变的身份，charIndex 是瞬时行号。
 *
 * ## 空值与存活
 *
 * [NULL]（raw == 0）是唯一合法空值。一个非空 [EntityId] 仍可能悬空（实体已 destroy 或槽位
 * 复用后 generation 不符）——存活判定走 [EntityApi.exists]，引擎按全位域比对，mod 不自己猜。
 *
 * clean 语义（§18.7）：本类型不复刻任何官方 bug；位域编码的诚实边界（未声明 EntityRef 的
 * 普通 Long 不被引擎当实体引用重映射）由引擎侧 schema 声明承担，mod 须按 §7 显式声明持久引用。
 */
@JvmInline
value class EntityId(val raw: Long) {

    /** 是否空引用（raw == 0）。 */
    val isNull: Boolean get() = raw == NULL.raw

    override fun toString(): String = if (isNull) "EntityId(NULL)" else "EntityId($raw)"

    companion object {
        /** 空引用；与引擎 `EntityIdCodec.NULL` 同值（0L）。 */
        val NULL: EntityId = EntityId(0L)
    }
}

/**
 * 实体生命周期三态（mod 侧干净枚举，映射引擎 `EntityLifecycle` 的 Int）。
 *
 * [RETIRED] 与销毁不同：留数据与关系、退出常规轮询（考古/复活类业务由 mod 自决）；
 * [EntityApi.destroy] 才是真删（级联清关系边、槽位 generation 递增）。
 */
enum class EntityState {
    ACTIVE,
    INACTIVE,
    RETIRED,
}