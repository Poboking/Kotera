package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.FieldType

/**
 * 内存实体表：槽位表 + 两张列存（INT / STR），语义照 `EntityKernel` 的失败哨兵口径。
 *
 * 与生产一致的三条：
 * - `create` 未注册原型 `require` 抛 `IllegalArgumentException`（fail-closed，不静默建）；
 * - 未注册字段 / 不存在实体的读返回 0 / ""，写返回 false（clean 哨兵，不复刻官方 VM 怪癖）；
 * - `query` 按 EntityId 数值升序返回快照。
 */
internal class EntityStore(private val schema: MemorySchema) {

    /** 一个实体槽位：原型名（大写）+ 生命周期状态。 */
    internal class Slot(val archetype: String, var state: EntityState)

    private val slots = LinkedHashMap<Long, Slot>()
    private val intColumns = HashMap<Long, MutableMap<String, Long>>()
    private val strColumns = HashMap<Long, MutableMap<String, String>>()
    private var lastId = 0L

    /** 真删时的级联回调（关系图在这里摘边；由 [TestWorldState] 装配）。 */
    var onDestroy: (EntityId) -> Unit = {}

    /** 槽位表只读视图（`query` 用；外部只读不写）。 */
    val snapshot: Map<Long, Slot> get() = slots

    fun create(archetype: String): EntityId {
        val key = normalize(archetype)
        require(key in schema.archetypes) { "原型未注册: $archetype" }
        lastId += 1L
        val id = EntityId(lastId)
        slots[id.raw] = Slot(key, EntityState.ACTIVE)
        return id
    }

    fun destroy(id: EntityId): Boolean {
        if (slots.remove(id.raw) == null) return false
        intColumns.remove(id.raw)
        strColumns.remove(id.raw)
        onDestroy(id)
        return true
    }

    fun exists(id: EntityId): Boolean = slots.containsKey(id.raw)

    fun stateOf(id: EntityId): EntityState? = slots[id.raw]?.state

    fun setState(id: EntityId, state: EntityState): Boolean {
        val slot = slots[id.raw] ?: return false
        slot.state = state
        return true
    }

    fun getInt(id: EntityId, field: String): Long {
        val key = normalize(field)
        if (!exists(id) || schema.fieldTypes[key] != FieldType.INT) return 0L
        return intColumns[id.raw]?.get(key) ?: 0L
    }

    fun setInt(id: EntityId, field: String, value: Long): Boolean {
        val key = normalize(field)
        val writable = exists(id) && schema.fieldTypes[key] == FieldType.INT
        if (writable) {
            intColumns.getOrPut(id.raw) { HashMap() }[key] = value
        }
        return writable
    }

    fun getStr(id: EntityId, field: String): String {
        val key = normalize(field)
        if (!exists(id) || schema.fieldTypes[key] != FieldType.STR) return ""
        return strColumns[id.raw]?.get(key) ?: ""
    }

    fun setStr(id: EntityId, field: String, value: String): Boolean {
        val key = normalize(field)
        val writable = exists(id) && schema.fieldTypes[key] == FieldType.STR
        if (writable) {
            strColumns.getOrPut(id.raw) { HashMap() }[key] = value
        }
        return writable
    }
}

/**
 * 实体能力面的生命周期段（create / destroy / exists / stateOf / setState）。
 *
 * 拆成抽象基类 + 子类两截只是为了让**每个类体直接声明的函数**都低于 detekt 的
 * `TooManyFunctions` 阈值（`EntityApi` 有 12 个方法，一个类装不下还不许 `@Suppress`）。
 */
internal abstract class EntityLifecycleApi(protected val store: EntityStore) : EntityApi {

    override fun create(archetype: String): EntityId = store.create(archetype)

    override fun destroy(id: EntityId): Boolean = store.destroy(id)

    override fun exists(id: EntityId): Boolean = store.exists(id)

    override fun stateOf(id: EntityId): EntityState? = store.stateOf(id)

    override fun setState(id: EntityId, state: EntityState): Boolean = store.setState(id, state)
}

/** 实体能力面的其余段：字段读写 + 引用 + 按原型查询。 */
internal class MemoryEntityApi(store: EntityStore) : EntityLifecycleApi(store) {

    override fun getInt(id: EntityId, field: String): Long = store.getInt(id, field)

    override fun setInt(id: EntityId, field: String, value: Long): Boolean = store.setInt(id, field, value)

    override fun getStr(id: EntityId, field: String): String = store.getStr(id, field)

    override fun setStr(id: EntityId, field: String, value: String): Boolean = store.setStr(id, field, value)

    /** 与 `getInt` 同源：只把同一张 INT 列包装成 [EntityId]（失败即 [EntityId.NULL]）。 */
    override fun getRef(id: EntityId, field: String): EntityId = EntityId(store.getInt(id, field))

    /** 与 `setInt` 同源：把 [value] 的位域解包进同一张 INT 列。 */
    override fun setRef(id: EntityId, field: String, value: EntityId): Boolean =
        store.setInt(id, field, value.raw)

    override fun query(archetype: String, state: EntityState?): List<EntityId> {
        val key = normalize(archetype)
        return store.snapshot.entries.asSequence()
            .filter { entry -> entry.value.archetype == key && (state == null || entry.value.state == state) }
            .map { entry -> EntityId(entry.key) }
            .sortedBy { id -> id.raw }
            .toList()
    }
}
