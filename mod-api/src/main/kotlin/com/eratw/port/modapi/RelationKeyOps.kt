package com.eratw.port.modapi

// ---- 关系边快捷面（按类型化键，全局命名空间）----

/** 按类型化键读关系边值（无边返回 0）。 */
fun RelationshipApi.valueOf(from: EntityId, kind: RelationKey, to: EntityId): Long =
    valueOf(from, kind.name, to)

/** 按类型化键建边。 */
fun RelationshipApi.add(from: EntityId, kind: RelationKey, to: EntityId): Boolean = add(from, kind.name, to)

/** 按类型化键删边。 */
fun RelationshipApi.remove(from: EntityId, kind: RelationKey, to: EntityId): Boolean =
    remove(from, kind.name, to)

/** 按类型化键判定边存在。 */
fun RelationshipApi.has(from: EntityId, kind: RelationKey, to: EntityId): Boolean =
    has(from, kind.name, to)

/** 按类型化键取邻居。 */
fun RelationshipApi.neighbors(id: EntityId, kind: RelationKey, outgoing: Boolean = true): List<EntityId> =
    neighbors(id, kind.name, outgoing)

/** 按类型化键写边值。 */
fun RelationshipApi.setValue(from: EntityId, kind: RelationKey, to: EntityId, value: Long): Boolean =
    setValue(from, kind.name, to, value)
