package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.FieldDecl
import kotlin.test.assertNotNull

/**
 * 三个测试类共用的夹具：手搓 schema、读 classpath 资源、从生成物文本里抽取结构。
 *
 * 拆到独立文件是为了让每个测试类都留在 detekt `LargeClass` 阈值内，同时保证
 * 「生成什么」/「拒载什么」/「CLI 怎么报」三个关注点各自独立。
 */

/** 手搓一份单组件 schema（键属性名固定 `component`，与头行第 4 列的老写法同形）。 */
internal fun schema(vararg names: String): SchemaDecl = SchemaDecl(
    "example.generated", "Example", ComponentDecl("family", names.map { FieldDecl(it) }),
)

/** 顶层函数没有 `javaClass`，借这个空对象拿 classpath 资源锚点。 */
private object TestResources

/** 读 test classpath 下的 schema 资源（按 UTF-8 解）。 */
internal fun resource(name: String): String =
    assertNotNull(TestResources::class.java.getResource("/$name"), "Missing test resource: $name")
        .openStream().use { it.readBytes().toString(Charsets.UTF_8) }

/** `<base>Keys` 里的字段键属性名，按声明顺序。 */
internal fun keyProperties(source: String): List<String> =
    Regex("    val ([A-Za-z][A-Za-z0-9]*): FieldKey<").findAll(source).map { it.groupValues[1] }.toList()

/** `<base>View` 里的属性名，按声明顺序。 */
internal fun viewProperties(source: String): List<String> =
    Regex("    var ([A-Za-z][A-Za-z0-9]*): ").findAll(source).map { it.groupValues[1] }.toList()

/** 生成物里的线名（`intKey("x")` / `strKey("x")` / `refKey("x")`），按声明顺序。 */
internal fun wireNames(source: String): List<String> =
    Regex("(?:intKey|strKey|refKey)\\(\"([^\"]+)\"\\)").findAll(source).map { it.groupValues[1] }.toList()

/** 生成物里的方法名（Facade 的 create/of）。 */
internal fun functionNames(source: String): List<String> =
    Regex("    fun ([A-Za-z0-9]+)\\(").findAll(source).map { it.groupValues[1] }.toList()

// TEST INFRASTRUCTURE: independent, case-sensitive wire storage, not a mocked generated facade.
internal class FakeEntityApi(
    private val intFields: Set<String> = setOf("age", "father", "AGE", "father_ID", "other_age", "other_father"),
    private val strFields: Set<String> = setOf("title", "display_name", "other_title"),
) : EntityApi {
    private data class Record(val archetype: String, var state: EntityState = EntityState.ACTIVE)
    private val records = mutableMapOf<EntityId, Record>()
    private val ints = mutableMapOf<EntityId, MutableMap<String, Long>>()
    private val strings = mutableMapOf<EntityId, MutableMap<String, String>>()
    private var nextId = 1L
    var rejectWrites = false

    fun fixtureEntity(raw: Long): EntityId {
        val id = EntityId(raw)
        require(!id.isNull && !exists(id))
        records[id] = Record("person")
        return id
    }

    override fun create(archetype: String): EntityId {
        require(archetype in setOf("person", "other")) { "Undeclared archetype: $archetype" }
        while (EntityId(nextId).isNull || exists(EntityId(nextId))) nextId++
        val id = EntityId(nextId++)
        records[id] = Record(archetype)
        return id
    }

    override fun destroy(id: EntityId): Boolean {
        if (records.remove(id) == null) return false
        ints.remove(id)
        strings.remove(id)
        return true
    }

    override fun exists(id: EntityId): Boolean = id in records

    override fun stateOf(id: EntityId): EntityState? = records[id]?.state

    override fun setState(id: EntityId, state: EntityState): Boolean {
        val record = records[id] ?: return false
        record.state = state
        return true
    }

    override fun getInt(id: EntityId, field: String): Long =
        if (exists(id) && field in intFields) ints[id]?.get(field) ?: 0L else 0L

    override fun setInt(id: EntityId, field: String, value: Long): Boolean {
        if (rejectWrites || !exists(id) || field !in intFields) return false
        ints.getOrPut(id) { mutableMapOf() }[field] = value
        return true
    }

    override fun getStr(id: EntityId, field: String): String =
        if (exists(id) && field in strFields) strings[id]?.get(field) ?: "" else ""

    override fun setStr(id: EntityId, field: String, value: String): Boolean {
        if (rejectWrites || !exists(id) || field !in strFields) return false
        strings.getOrPut(id) { mutableMapOf() }[field] = value
        return true
    }

    // P1-6 ②：类型化引用口共用**同一张 ints 表**，不是第二套列存。
    // 于是生成访问器（setRef）与原生口（getInt）的逐值对拍在同一个列上成立，
    // 正好证明「不改 codec 不改列存」。
    override fun getRef(id: EntityId, field: String): EntityId = EntityId(getInt(id, field))

    override fun setRef(id: EntityId, field: String, value: EntityId): Boolean =
        setInt(id, field, value.raw)

    override fun query(archetype: String, state: EntityState?): List<EntityId> = records.entries
        .filter { it.value.archetype == archetype && (state == null || it.value.state == state) }
        .map { it.key }
        .sortedBy { it.raw }
}
