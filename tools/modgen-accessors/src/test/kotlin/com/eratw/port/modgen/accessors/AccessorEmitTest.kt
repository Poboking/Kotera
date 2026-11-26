package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setRef
import com.eratw.port.modapi.setStr
import com.eratw.port.modfamily.generated.FamilyFacade
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modgen.accessors.generated.FreshFacade
import com.eratw.port.modgen.accessors.generated.FreshKeys
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `:tools:modgen-accessors` 的生成器行为契约：**正常 schema 生成什么**。
 *
 * 2026-11-16 生成物从「一个扁平方法类 `<base>Accessors`」升级成三件套：
 * `<base>Keys`（类型化字段键）/ `<base>View`（`var` 属性视图）/ `<base>Facade`（`create` + `of`）。
 * 本文件随之改造，调用点统一成 `facade.of(id).<prop>`。
 *
 * **取不到 Boolean 的地方**：`View` 的属性 setter 必须返回 Unit，所以
 * `entities.setInt/setStr/setRef` 的 Boolean 返回值在属性这条路上被丢弃。「写被拒要拿到
 * false」只能直连 `api.setInt/setStr/setRef`（键是同一份 `Keys.<prop>`），这也是文档写明的
 * 正规口径；属性这条路能验的是「被拒时旧值不动」。
 *
 * 单类过大后按关注点拆成三个类：本类管正常发射；非法输入拒载见 [AccessorEmitValidationTest]；
 * CLI 读写与 `--check` 语义见 [AccessorEmitCliTest]。
 */
class AccessorEmitTest {
    @Test
    fun familyBusinessCodeUsesOnlyTypedAccessors() {
        val entities: EntityApi = FakeEntityApi()
        val child = entities.create("person")
        val parent = entities.create("person")
        val family = FamilyFacade(entities)

        family.of(child).age = 42L
        family.of(child).title = "家族の記録"
        family.of(child).father = parent
        val age: Long = family.of(child).age
        val title: String = family.of(child).title
        val father: EntityId = family.of(child).father
        assertEquals(42L, age)
        assertEquals("家族の記録", title)
        assertEquals(parent, father)
        // 键就是字段名本身：生成器不许拼组件名前缀、不许改大小写。
        assertEquals("family", FamilyKeys.component.name)
        assertEquals("age", FamilyKeys.age.name)
        assertEquals("title", FamilyKeys.title.name)
        assertEquals("father", FamilyKeys.father.name)
    }

    @Test
    fun freshlyGeneratedClassIsCompiledAndCalledThroughTypedAccessors() {
        val entities: EntityApi = FakeEntityApi()
        val child = entities.create("person")
        val parent = entities.create("person")
        val fresh = FreshFacade(entities)

        fresh.of(child).age = 73L
        fresh.of(child).displayName = "新しい名前"
        fresh.of(child).fatherId = parent
        val age: Long = fresh.of(child).age
        val displayName: String = fresh.of(child).displayName
        val fatherId: EntityId = fresh.of(child).fatherId
        assertEquals(73L, age)
        assertEquals("新しい名前", displayName)
        assertEquals(parent, fatherId)

        // Facade.create() 也在这条链上（fresh 的原型名 `person` 是测试替身认识的唯一原型名）。
        val created = fresh.create()
        created.age = 5L
        created.displayName = "作られた"
        assertTrue(entities.exists(created.id))
        assertEquals(5L, fresh.of(created.id).age)
        assertEquals("作られた", fresh.of(created.id).displayName)
        assertEquals("person", FreshKeys.archetype.name)
    }

    @Test
    fun longAndEntityReferenceBitsMatchNativeApiInBothDirections() {
        // TEST INFRASTRUCTURE: raw IDs and native calls are the independent bit/wire oracle.
        val api = FakeEntityApi()
        val child = api.create("person")
        val family = FamilyFacade(api)
        val fresh = FreshFacade(api)
        assertFalse(api.exists(EntityId(Long.MIN_VALUE)), "Negative dangling references are not live entities")
        val random = Random(0x5745L)
        val values = (listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L,
            -9_007_199_254_740_993L, 0x123456789abcdefL) +
            (0..63).flatMap { listOf(1L shl it, (1L shl it).inv()) } +
            List(32) { random.nextLong() }).distinct()

        for (bits in values) {
            family.of(child).age = bits
            fresh.of(child).age = bits
            assertEquals(bits, api.getInt(child, "age"))
            assertEquals(bits, api.getInt(child, "AGE"))
            assertEquals(bits, family.of(child).age)
            assertEquals(bits, fresh.of(child).age)
            assertEquals(bits, api.getInt(child, FamilyKeys.age))
            assertTrue(api.setInt(child, "age", bits.inv()))
            assertTrue(api.setInt(child, "AGE", bits.inv()))
            assertEquals(bits.inv(), family.of(child).age)
            assertEquals(bits.inv(), fresh.of(child).age)

            val reference = EntityId(bits)
            family.of(child).father = reference
            fresh.of(child).fatherId = reference
            assertEquals(bits, api.getInt(child, "father"))
            assertEquals(bits, api.getInt(child, "father_ID"))
            assertEquals(reference, family.of(child).father)
            assertEquals(reference, fresh.of(child).fatherId)
            assertEquals(reference, api.getRef(child, "father"))
            assertEquals(reference, api.getRef(child, FreshKeys.fatherId))
            assertTrue(api.setInt(child, "father", bits.inv()))
            assertTrue(api.setInt(child, "father_ID", bits.inv()))
            assertEquals(bits.inv(), family.of(child).father.raw)
            assertEquals(bits.inv(), fresh.of(child).fatherId.raw)
        }
    }

    @Test
    fun stringsRoundTripWithoutTrimmingEscapingOrNewlineConversion() {
        // TEST INFRASTRUCTURE: native API checks distinguish STR routing from INT routing.
        val api = FakeEntityApi()
        val entity = api.create("person")
        val family = FamilyFacade(api)
        val fresh = FreshFacade(api)
        for (value in listOf("", "  leading and trailing  ", "家族・名前", "e\u0301", "\"\\\t\r\n\u0000")) {
            family.of(entity).title = value
            fresh.of(entity).displayName = value
            assertEquals(value, api.getStr(entity, "title"))
            assertEquals(value, api.getStr(entity, "display_name"))
            assertEquals(value, family.of(entity).title)
            assertEquals(value, fresh.of(entity).displayName)
            val nativeValue = "native:$value"
            assertTrue(api.setStr(entity, "title", nativeValue))
            assertTrue(api.setStr(entity, "display_name", nativeValue))
            assertEquals(nativeValue, family.of(entity).title)
            assertEquals(nativeValue, fresh.of(entity).displayName)
            assertEquals(nativeValue, api.getStr(entity, FamilyKeys.title))
        }
    }

    @Test
    fun failedWritesReturnFalseAndPreserveAllThreeTypes() {
        val api = FakeEntityApi()
        val entity = api.create("person")
        val father = api.create("person")
        val family = FamilyFacade(api)
        val fresh = FreshFacade(api)
        family.of(entity).age = Long.MIN_VALUE
        family.of(entity).title = "old family"
        family.of(entity).father = father
        fresh.of(entity).age = Long.MAX_VALUE
        fresh.of(entity).displayName = "old fresh"
        fresh.of(entity).fatherId = father

        api.rejectWrites = true
        // 属性的 setter 丢掉 Boolean，判失败必须直连原生口（键是同一份）。
        assertFalse(api.setInt(entity, FamilyKeys.age, 1L))
        assertFalse(api.setStr(entity, FamilyKeys.title, "changed"))
        assertFalse(api.setRef(entity, FamilyKeys.father, EntityId.NULL))
        assertFalse(api.setInt(entity, FreshKeys.age, 2L))
        assertFalse(api.setStr(entity, FreshKeys.displayName, "changed"))
        assertFalse(api.setRef(entity, FreshKeys.fatherId, EntityId.NULL))
        // 属性这条路写被拒时是静默的：不落值，也不许把旧值抹掉。
        family.of(entity).age = 9L
        fresh.of(entity).displayName = "silently dropped"
        assertEquals(Triple(Long.MIN_VALUE, "old family", father), familyValues(family, entity))
        assertEquals(Triple(Long.MAX_VALUE, "old fresh", father), freshValues(fresh, entity))
    }

    @Test
    fun absentValuesAndMissingOrDestroyedEntitiesUseCleanDefaults() {
        // TEST INFRASTRUCTURE: explicitly manufacture an unknown ID without creating it.
        val api = FakeEntityApi()
        val entity = api.create("person")
        val destroyed = api.create("person")
        val family = FamilyFacade(api)
        val fresh = FreshFacade(api)
        family.of(destroyed).age = 11L
        fresh.of(destroyed).displayName = "removed"
        assertTrue(api.destroy(destroyed))
        assertDefaults(family, fresh, entity)
        for (missing in listOf(EntityId.NULL, EntityId(-77L), destroyed)) {
            assertDefaults(family, fresh, missing)
            assertWritesRejected(api, missing)
            assertDefaults(family, fresh, missing)
            assertFalse(api.exists(missing))
        }
    }

    @Test
    fun undeclaredFieldsCannotBeCreatedByAccessors() {
        val api = FakeEntityApi(intFields = emptySet(), strFields = emptySet())
        val entity = api.create("person")
        val family = FamilyFacade(api)
        val fresh = FreshFacade(api)
        assertDefaults(family, fresh, entity)
        assertWritesRejected(api, entity)
        assertDefaults(family, fresh, entity)
        assertTrue(api.exists(entity))
    }

    @Test
    fun entitiesComponentsAndAccessorInstancesStayIsolatedWithExactWireKeys() {
        // TEST INFRASTRUCTURE: same low 32 bits deliberately test full-width entity addressing.
        val api = FakeEntityApi()
        val first = api.fixtureEntity(0x100000001L)
        val second = api.fixtureEntity(0x200000001L)
        val otherApi = FakeEntityApi()
        val otherEntity = otherApi.fixtureEntity(first.raw)
        val family = FamilyFacade(api)
        val fresh = FreshFacade(api)
        val otherFamily = FamilyFacade(otherApi)
        val otherFresh = FreshFacade(otherApi)
        family.of(first).age = 10L
        family.of(first).title = "first"
        family.of(first).father = second
        family.of(second).age = 20L
        family.of(second).title = "second"
        family.of(second).father = first
        fresh.of(first).age = 30L
        fresh.of(first).displayName = "fresh"
        fresh.of(first).fatherId = EntityId.NULL
        otherFamily.of(otherEntity).age = 40L
        otherFamily.of(otherEntity).title = "other family"
        otherFamily.of(otherEntity).father = otherEntity
        otherFresh.of(otherEntity).age = 50L
        otherFresh.of(otherEntity).displayName = "other fresh"
        otherFresh.of(otherEntity).fatherId = otherEntity
        assertTrue(api.setInt(first, "other_age", 60L))
        assertTrue(api.setStr(first, "other_title", "unrelated component"))
        assertTrue(api.setInt(first, "other_father", -60L))

        assertEquals(Triple(10L, "first", second), familyValues(FamilyFacade(api), first))
        assertEquals(Triple(20L, "second", first), familyValues(family, second))
        assertEquals(Triple(30L, "fresh", EntityId.NULL), freshValues(FreshFacade(api), first))
        assertEquals(Triple(0L, "", EntityId.NULL), freshValues(fresh, second))
        assertEquals(Triple(40L, "other family", otherEntity), familyValues(otherFamily, otherEntity))
        assertEquals(Triple(50L, "other fresh", otherEntity), freshValues(otherFresh, otherEntity))
        assertEquals(10L, api.getInt(first, "age"))
        assertEquals(30L, api.getInt(first, "AGE"))
        assertEquals("fresh", api.getStr(first, "display_name"))
        assertEquals(0L, api.getInt(first, "father_ID"))
        assertEquals(60L, api.getInt(first, "other_age"))
        assertEquals("unrelated component", api.getStr(first, "other_title"))
        assertEquals(-60L, api.getInt(first, "other_father"))
        // 属性名不是线名：按属性名去读必须仍是缺省值。
        assertEquals("", api.getStr(first, "displayName"))
        assertEquals(0L, api.getInt(first, "fatherId"))
    }

    @Test
    fun bothResourcesDeclareTheExpectedWireNamesAndTypes() {
        val family = SchemaDecl(
            "com.eratw.port.modfamily.generated", "Family",
            ComponentDecl("family", listOf(
                FieldDecl("age", FieldType.INT), FieldDecl("title", FieldType.STR),
                FieldDecl("father", FieldType.INT, true),
            )),
            archetype = "family.child",
            propertyOverrides = emptyMap(),
        )
        val fresh = SchemaDecl(
            "com.eratw.port.modgen.accessors.generated", "Fresh",
            ComponentDecl("family", listOf(
                FieldDecl("AGE", FieldType.INT), FieldDecl("display_name", FieldType.STR),
                FieldDecl("father_ID", FieldType.INT, true),
            )),
            archetype = "person",
        )
        assertEquals(family, parseSchema(resource("family.schema.tsv")))
        assertEquals(fresh, parseSchema(resource("fresh.schema.tsv")))
        // 原型键与字段键一起钉住：`family.child` / `person`，字段键 = 线名本身。
        assertEquals("family.child", FamilyKeys.archetype.name)
        assertEquals("person", FreshKeys.archetype.name)
        assertEquals("AGE", FreshKeys.age.name)
        assertEquals("display_name", FreshKeys.displayName.name)
        assertEquals("father_ID", FreshKeys.fatherId.name)
    }

    @Test
    fun emissionIsDeterministicAndMatchesBothActualArtifactsByteForByte() {
        for ((resourceName, property) in listOf(
            "family.schema.tsv" to "accessors.familyOutput",
            "fresh.schema.tsv" to "accessors.freshOutput",
        )) {
            val text = resource(resourceName)
            val first = AccessorEmit.emit(parseSchema(text))
            val second = AccessorEmit.emit(parseSchema(text))
            assertContentEquals(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
            assertFalse('\r' in first)
            assertTrue(first.endsWith("\n"))
            val artifact = Path.of(assertNotNull(System.getProperty(property), "Gradle must provide $property"))
            assertTrue(Files.isRegularFile(artifact), "Missing generated artifact: $artifact")
            assertContentEquals(first.toByteArray(Charsets.UTF_8), Files.readAllBytes(artifact), property)
        }
    }

    @Test
    fun entityRefFieldsEmitTypedRefAccessorsWithZeroRawLeakage() {
        // P1-6 ② / E 车：isEntityRef 字段必须生成 getRef/setRef；生成物 0 个 .raw、
        // 0 个手写 EntityId(getInt(...))。判据 5 的闸（AccessorEmit 退回旧写法必红）。
        // 2026-11-16 起参数名从 `e` 改成 Receiver 属性 `id`（View 的 val id）。
        for (resourceName in listOf("family.schema.tsv", "fresh.schema.tsv")) {
            val source = AccessorEmit.emit(parseSchema(resource(resourceName)))
            assertContains(source, "entities.getRef(id, ")
            assertContains(source, "entities.setRef(id, ")
            assertFalse(".raw" in source, "$resourceName 生成物不得泄漏位域")
            assertFalse("EntityId(entities.getInt" in source, "$resourceName 不得再手写包装")
            assertFalse("entities.setInt(id, \"father\", v.raw)" in source)
            assertFalse("entities.setInt(id, \"father_ID\", v.raw)" in source)
        }
        // 生成访问器与公开引用口落在同一张 INT 列：两个方向逐值等价。
        val api = FakeEntityApi()
        val child = api.create("person")
        val father = api.create("person")
        val family = FamilyFacade(api)
        family.of(child).father = father
        assertEquals(father, api.getRef(child, "father"), "生成访问器写入后公开引用口读到同一身份")
        assertEquals(family.of(child).father, api.getRef(child, "father"))
        assertTrue(api.setRef(child, "father", EntityId.NULL))
        assertEquals(EntityId.NULL, family.of(child).father, "公开引用口写入后生成访问器读到同一 INT 列")
        assertTrue(api.setRef(child, FamilyKeys.father, father))
        assertEquals(father.raw, api.getInt(child, "father"))
    }

    @Test
    fun parserAcceptsLfOrCrlfWithOptionalFinalNewline() {
        for (name in listOf("family.schema.tsv", "fresh.schema.tsv")) {
            val canonical = resource(name).replace("\r\n", "\n").removeSuffix("\n")
            val expected = parseSchema(canonical)
            for (ending in listOf("\n", "\r\n")) {
                val body = canonical.replace("\n", ending)
                for (text in listOf(body, body + ending)) {
                    assertEquals(expected, parseSchema(text))
                    assertEquals(AccessorEmit.emit(expected), AccessorEmit.emit(parseSchema(text)))
                }
            }
        }
        assertTrue(parseSchema("schema\texample.generated\tEmpty\tfamily").component.fields.isEmpty())
    }

    @Test
    fun camelCasePreservesMixedCaseWireKeysAndDeclarationOrder() {
        val names = listOf(
            "zValue", "AGE", "display_name", "father_ID",
            "URL_Value", "iOS_buildID", "HTTP_v2_ID", "a1_B2",
        )
        val properties = listOf(
            "zValue", "age", "displayName", "fatherId",
            "urlValue", "iOSBuildID", "httpV2Id", "a1B2",
        )
        val schema = schema(*names.toTypedArray())
        val source = AccessorEmit.emit(schema)
        assertEquals(properties, keyProperties(source))
        assertEquals(properties, viewProperties(source))
        assertEquals(names, wireNames(source), "键必须逐字节等于声明里的字段名：不拼组件名、不改大小写")
        val reversed = schema.copy(
            components = listOf(
                ComponentSpec(schema.component.copy(fields = schema.component.fields.reversed()), "component"),
            ),
        )
        val reversedSource = AccessorEmit.emit(reversed)
        assertEquals(properties.reversed(), keyProperties(reversedSource))
        assertEquals(properties.reversed(), viewProperties(reversedSource))
        assertEquals(names.reversed(), wireNames(reversedSource))
    }

    @Test
    fun entityApiMembersNoLongerConstrainFieldNames() {
        // 旧生成器把字段展开成 `fun getAge(e)` / `fun setAge(e, v)`，于是「字段名 == EntityApi
        // 成员名」和「字段名 == setXxx」都会撞方法表，必须反射 EntityApi 逐名拒绝。
        // 新形状把方法收回 Facade 一处（只有 create/of），这层间接冲突整体消失 ——
        // 反射断言保留，另把「现在必须能照常生成」也钉住。
        val members = EntityApi::class.java.methods.map { it.name.substringBefore('-') }.toSet()
        assertTrue(members.containsAll(listOf("getInt", "setInt", "getStr", "setStr", "setState", "getRef", "setRef")))
        for ((wire, property) in listOf(
            "getInt" to "getInt", "setRef" to "setRef", "setState" to "setState",
            "get_int" to "getInt", "set_int" to "setInt", "GET_STR" to "getStr",
        )) {
            val source = AccessorEmit.emit(schema(wire))
            assertEquals(listOf(wire), wireNames(source))
            assertEquals(listOf(property), keyProperties(source))
            assertEquals(listOf(property), viewProperties(source))
            assertEquals(listOf("of"), functionNames(source), "生成物里除 Facade 的 of/create 外不得有方法")
        }
    }

    /** 新能力（2026-11-16）：`field` 行第 5 列 = 属性名覆盖，给线名是硬关键字的字段让路。 */
    @Test
    fun fieldRowFifthColumnOverridesPropertyNameForKeywordField() {
        val text = "schema\texample.generated\tExample\tfamily\nfield\tobject\tINT\ttrue\tobj\n"
        val schema = parseSchema(text)
        assertEquals(mapOf("object" to "obj"), schema.propertyOverrides)
        val source = AccessorEmit.emit(schema)
        assertContains(source, "val obj: FieldKey<EntityId> = refKey(\"object\")")
        assertContains(source, "var obj: EntityId")
        assertContains(source, "entities.getRef(id, ExampleKeys.obj)")
        assertContains(source, "entities.setRef(id, ExampleKeys.obj, value)")
        assertFalse("var object" in source)
        assertFalse("val object:" in source)
        // 覆盖后的属性名照旧过标识符/关键字校验。
        assertFailsWith<IllegalArgumentException> {
            AccessorEmit.emit(schema.copy(propertyOverrides = mapOf("object" to "class")))
        }
        assertFailsWith<IllegalArgumentException> {
            AccessorEmit.emit(schema.copy(propertyOverrides = mapOf("object" to "ob j")))
        }
        // 覆盖表键是线名，不许撞字段本身之外的任何东西。
        val twoFields = "schema\texample.generated\tExample\tfamily\n" +
            "field\tage\tINT\tfalse\nfield\ttitle\tSTR\tfalse\ttitle\n"
        assertEquals(mapOf("title" to "title"), parseSchema(twoFields).propertyOverrides)
    }

    /** 新能力（2026-11-16）：只有声明了原型名的 schema 才生成 `Facade.create()`。 */
    @Test
    fun facadeEmitsCreateOnlyWhenArchetypeIsDeclared() {
        val withArchetype = parseSchema(resource("fresh.schema.tsv"))
        assertEquals(listOf("create", "of"), functionNames(AccessorEmit.emit(withArchetype)))
        val withoutArchetype = withArchetype.copy(archetype = null)
        val source = AccessorEmit.emit(withoutArchetype)
        assertEquals(listOf("of"), functionNames(source))
        assertFalse("ArchetypeKey" in source)
        assertFalse("fun create()" in source)
        assertContains(source, "class FreshFacade(private val entities: EntityApi)")
        assertContains(source, "fun of(id: EntityId): FreshView = FreshView(entities, id)")
    }

    @Test
    fun multiComponentSchemaEmitsOneKeyPerComponentAndOneFlatView() {
        // 一个原型挂两个组件（livingworld 的 NPC = 性格 + 日程）：`Keys` 里一个组件一个 `ComponentKey`，
        // 但 View 上的属性是**平铺**的 —— 字段寻址本来就跨组件共享一张全局表，把 View 也按组件分层
        // 只是给调用方多套一层，没有任何收益。
        val schema = parseSchema(
            "schema\texample.generated\tNpc\n" +
                "component\tlw.char.persona\n" +
                "field\twarmth\tINT\tfalse\n" +
                "component\tlw.char.schedule\tschedule\n" +
                "field\teveningSlot\tINT\tfalse\n" +
                "archetype\tlw.char.npc\n",
        )
        assertEquals(listOf("persona", "schedule"), schema.components.map { it.keyProperty })
        assertEquals(listOf("lw.char.persona", "lw.char.schedule"), schema.components.map { it.declaration.name })
        assertEquals(listOf("warmth", "eveningSlot"), schema.fields.map { it.name })
        val source = AccessorEmit.emit(schema)
        assertContains(source, "    val persona: ComponentKey = ComponentKey(\"lw.char.persona\")\n")
        assertContains(source, "    val schedule: ComponentKey = ComponentKey(\"lw.char.schedule\")\n")
        assertEquals(listOf("warmth", "eveningSlot"), keyProperties(source))
        assertEquals(listOf("warmth", "eveningSlot"), viewProperties(source))
        assertContains(source, "fun create(): NpcView = NpcView(entities, entities.create(NpcKeys.archetype))")
    }

    private fun familyValues(facade: FamilyFacade, entity: EntityId): Triple<Long, String, EntityId> {
        val view = facade.of(entity)
        return Triple(view.age, view.title, view.father)
    }

    private fun freshValues(facade: FreshFacade, entity: EntityId): Triple<Long, String, EntityId> {
        val view = facade.of(entity)
        return Triple(view.age, view.displayName, view.fatherId)
    }

    private fun assertDefaults(family: FamilyFacade, fresh: FreshFacade, entity: EntityId) {
        assertEquals(Triple(0L, "", EntityId.NULL), familyValues(family, entity))
        assertEquals(Triple(0L, "", EntityId.NULL), freshValues(fresh, entity))
    }

    /**
     * 「写被拒」只能从原生口读 Boolean（属性 setter 返回 Unit），键仍是同一份 `Keys.<prop>`。
     */
    private fun assertWritesRejected(api: FakeEntityApi, entity: EntityId) {
        assertFalse(api.setInt(entity, FamilyKeys.age, 1L))
        assertFalse(api.setStr(entity, FamilyKeys.title, "not stored"))
        assertFalse(api.setRef(entity, FamilyKeys.father, entity))
        assertFalse(api.setInt(entity, FreshKeys.age, 2L))
        assertFalse(api.setStr(entity, FreshKeys.displayName, "not stored"))
        assertFalse(api.setRef(entity, FreshKeys.fatherId, entity))
        // 同一列的裸名口径必须一致。
        assertFalse(api.setInt(entity, "age", 1L))
        assertFalse(api.setStr(entity, "display_name", "not stored"))
        assertFalse(api.setRef(entity, "father_ID", entity))
    }
}
