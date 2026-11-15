package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityLifecycle
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.RelationshipApi

/**
 * [EntityApi] + [RelationshipApi] 的引擎侧适配器（K1 原型，era-arch-v61u.md §18.3/§18.8）：
 * wrap [IEntityKernel]（M4-1 实体内核，实体面与关系面同一个接口）。
 *
 * ## 证手感原型
 *
 * mod 经 mod-api 的 [EntityId]/[EntityState] 干净类型操作实体，本适配器翻译成内核的 Long 位域
 * 与 Int 三态。mod 不持有内核对象、不自编码位域（§18.2 铁边界）；[EntityId] 与内核 Long 逐位同值
 * （`EntityIdCodec` 编码），适配器只做包装/解包。
 *
 * @param pkgShortId 本 mod 的包短 ID（[create] 编进 EntityId 的 pkg 段；由 PackageShortIdRegistry 分配）。
 *
 * clean 语义（§18.7）：[stateOf] 把内核的 0 失败哨兵翻成 null（mod 侧干净口径）；未注册 archetype
 * 的 [create] 由内核 require 抛 [IllegalArgumentException]（声明先行，与契约一致）。生产生命周期/
 * 转移边界接线属 K3，本原型只证 facade 包裹内核可用。
 */
class KernelEntityApi(
    private val kernel: IEntityKernel,
    private val pkgShortId: Int,
) : EntityApi, RelationshipApi {

    // ---- EntityApi ----

    override fun create(archetype: String): EntityId = EntityId(kernel.create(pkgShortId, archetype))

    override fun destroy(id: EntityId): Boolean = kernel.destroy(id.raw)

    override fun exists(id: EntityId): Boolean = kernel.exists(id.raw)

    override fun stateOf(id: EntityId): EntityState? = fromRaw(kernel.stateOf(id.raw))

    override fun setState(id: EntityId, state: EntityState): Boolean = kernel.setState(id.raw, toRaw(state))

    override fun getInt(id: EntityId, field: String): Long = kernel.getInt(id.raw, field)

    override fun setInt(id: EntityId, field: String, value: Long): Boolean = kernel.setInt(id.raw, field, value)

    override fun getStr(id: EntityId, field: String): String = kernel.getStr(id.raw, field)

    override fun setStr(id: EntityId, field: String, value: String): Boolean = kernel.setStr(id.raw, field, value)

    // P1-6 ② / E 车：类型化引用口只做包装/解包，底下仍是同一张 INT 列。
    // 不新增列类型、不碰 EntityIdCodec / DeclarationRegistry / ManifestCodec / .rt2。
    override fun getRef(id: EntityId, field: String): EntityId = EntityId(kernel.getInt(id.raw, field))

    override fun setRef(id: EntityId, field: String, value: EntityId): Boolean =
        kernel.setInt(id.raw, field, value.raw)

    override fun query(archetype: String, state: EntityState?): List<EntityId> =
        kernel.query(archetype, state?.let { toRaw(it) } ?: 0).map { EntityId(it) }

    // ---- RelationshipApi ----

    override fun hasKind(kind: String): Boolean = kernel.hasRelationKind(kind)

    override fun add(from: EntityId, kind: String, to: EntityId): Boolean =
        kernel.addRelation(from.raw, kind, to.raw)

    override fun remove(from: EntityId, kind: String, to: EntityId): Boolean =
        kernel.removeRelation(from.raw, kind, to.raw)

    override fun has(from: EntityId, kind: String, to: EntityId): Boolean =
        kernel.hasRelation(from.raw, kind, to.raw)

    override fun valueOf(from: EntityId, kind: String, to: EntityId): Long =
        kernel.getRelationValue(from.raw, kind, to.raw)

    override fun setValue(from: EntityId, kind: String, to: EntityId, value: Long): Boolean =
        kernel.setRelationValue(from.raw, kind, to.raw, value)

    override fun neighbors(id: EntityId, kind: String, outgoing: Boolean): List<EntityId> =
        kernel.queryRelations(id.raw, kind, outgoing).map { EntityId(it) }

    private fun toRaw(state: EntityState): Int = when (state) {
        EntityState.ACTIVE -> EntityLifecycle.ACTIVE
        EntityState.INACTIVE -> EntityLifecycle.INACTIVE
        EntityState.RETIRED -> EntityLifecycle.RETIRED
    }

    private fun fromRaw(raw: Int): EntityState? = when (raw) {
        EntityLifecycle.ACTIVE -> EntityState.ACTIVE
        EntityLifecycle.INACTIVE -> EntityState.INACTIVE
        EntityLifecycle.RETIRED -> EntityState.RETIRED
        else -> null
    }
}