package com.eratw.port.modapi

// ---- 带类型化键的读写快捷面（不生成 View 时也能拿到编译期类型校验）----

/** 按类型化键建实体。原型名拼错在运行期是 `IllegalArgumentException`，不是静默失败。 */
fun EntityApi.create(archetype: ArchetypeKey): EntityId = create(archetype.name)

/** 按类型化键枚举实体。 */
fun EntityApi.query(archetype: ArchetypeKey, state: EntityState? = null): List<EntityId> =
    query(archetype.name, state)

/** 按类型化键读整型字段。 */
fun EntityApi.getInt(id: EntityId, key: FieldKey<Long>): Long = getInt(id, key.name)

/** 按类型化键写整型字段。 */
fun EntityApi.setInt(id: EntityId, key: FieldKey<Long>, value: Long): Boolean = setInt(id, key.name, value)

/** 按类型化键读字符串字段。 */
fun EntityApi.getStr(id: EntityId, key: FieldKey<String>): String = getStr(id, key.name)

/** 按类型化键写字符串字段。 */
fun EntityApi.setStr(id: EntityId, key: FieldKey<String>, value: String): Boolean = setStr(id, key.name, value)

/** 按类型化键读实体引用字段。 */
fun EntityApi.getRef(id: EntityId, key: FieldKey<EntityId>): EntityId = getRef(id, key.name)

/** 按类型化键写实体引用字段。 */
fun EntityApi.setRef(id: EntityId, key: FieldKey<EntityId>, value: EntityId): Boolean =
    setRef(id, key.name, value)
