package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `:tools:modgen-accessors` 的拒载契约：**非法输入必须在 emit/parse 期红，不许静默改名**。
 *
 * 正常发射路径见 [AccessorEmitTest]，CLI 语义见 [AccessorEmitCliTest]。这里每个用例钉的都是
 * 一条 `require` 的实测判例：包名/类名注入、组件名/原型名字面量注入、字段名非 ASCII 或关键字、
 * 线名/属性名重名、EntityRef 类型错配、覆盖表指向不存在的字段、组件行与头行冲突。
 */
class AccessorEmitValidationTest {
    @Test
    fun viewAndKeysMemberNamesAreReservedAgainstFieldNames() {
        // 2026-11-16 改造：旧用例钉的是「EntityApi 成员名（getRef 等）不许当字段名」，
        // 那条闸随「字段展开成方法」一起消失了（见 entityApiMembersNoLongerConstrainFieldNames）。
        // 新形状下真正会撞的是 View / Keys 自己的成员名，这里改为钉它们。
        val names = EntityApi::class.java.methods.map { it.name.substringBefore('-') }.toSet()
        assertTrue("getRef" in names)
        assertTrue("setRef" in names)
        for (field in listOf("id", "entities", "component", "archetype", "equals", "hashCode", "toString")) {
            assertFailsWith<IllegalArgumentException>(field) { AccessorEmit.emit(schema(field)) }
        }
        // 只差一个字符的邻名照旧放行。
        for (field in listOf("ids", "entity", "components", "archetypes")) {
            assertEquals(listOf(field), viewProperties(AccessorEmit.emit(schema(field))))
        }
    }

    @Test
    fun parserRejectsMalformedRowsAndReportsTheirLine() {
        for (header in listOf("", "\n", "schema", "schema\tpkg\tExample", "wrong\tpkg\tExample\tfamily",
            "schema\tpkg\tExample\tfamily\textra")) {
            val error = assertFailsWith<IllegalArgumentException> { parseSchema(header) }
            assertContains(error.message.orEmpty(), "line 1")
        }
        val header = "schema\texample.generated\tExample\tfamily\n"
        // 2026-11-16 改造：`field` 行第 5 列现在是合法的属性名覆盖，故「多一列」改成 6 列；
        // 空行也被解析器跳过（老实现把空行当错误行），行号断言下移到下方。
        for (row in listOf("# comment", "field\tage\tINT", "field\tage\tINT\tfalse\tx\ty",
            "wrong\tage\tINT\tfalse", "field\t\tINT\tfalse", "field\t \tINT\tfalse",
            "field\tage\tint\tfalse", "field\tage\tFLOAT\tfalse")) {
            val error = assertFailsWith<IllegalArgumentException>(row) { parseSchema(header + row + "\n") }
            assertContains(error.message.orEmpty(), "line 2")
        }
        // 空行不报错，但物理行号不因此挪动：错误仍报真实行号 4。
        assertEquals(
            2,
            parseSchema(header + "field\tage\tINT\tfalse\n\nfield\ttitle\tSTR\tfalse\n").component.fields.size,
        )
        val error = assertFailsWith<IllegalArgumentException> {
            parseSchema(header + "field\tage\tINT\tfalse\n\nfield\ttitle\tWRONG\n")
        }
        assertContains(error.message.orEmpty(), "line 4")
        // 同一个线名被覆盖两次：第二行报重复覆盖（只写一次覆盖、另一行不写不算重复）。
        val duplicate = assertFailsWith<IllegalArgumentException> {
            parseSchema(header + "field\tage\tINT\tfalse\tx\nfield\tage\tINT\tfalse\ty\n")
        }
        assertContains(duplicate.message.orEmpty(), "line 3")
        assertContains(duplicate.message.orEmpty(), "duplicate property override for age")
        assertEquals(
            mapOf("age" to "x"),
            parseSchema(header + "field\tage\tINT\tfalse\nfield\tage\tINT\tfalse\tx\n").propertyOverrides,
        )
        // 覆盖列不许留空。
        assertContains(assertFailsWith<IllegalArgumentException> {
            parseSchema(header + "field\tage\tINT\tfalse\t\n")
        }.message.orEmpty(), "line 2")
    }

    @Test
    fun entityReferenceBooleanMustBeStrictlyLowercaseTrueOrFalse() {
        for (boolean in listOf("TRUE", "True", "FALSE", "False", "yes", "1", "0", "", " true", "false ")) {
            val error = assertFailsWith<IllegalArgumentException>(boolean) {
                parseSchema("schema\texample.generated\tExample\tfamily\nfield\tfather\tINT\t$boolean")
            }
            assertContains(error.message.orEmpty(), "line 2")
            assertContains(error.message.orEmpty(), "true or false")
        }
    }

    @Test
    fun invalidAsciiFieldNamesAndSourceInjectionAreRejected() {
        for (name in listOf("", " ", "_age", "age_", "age__years", "1age", "age_2years", "年齢", "âge",
            "age-name", "age.name", "age name", "`age`", "age\tname", "age\nname", "age\"", "age\\", "age;fun")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(schema(name)) }
        }
    }

    @Test
    fun hardSoftAndCamelizedKotlinKeywordsAreRejected() {
        for (name in listOf("class", "when", "is", "object", "in", "return", "typealias", "get", "set", "by",
            "field", "constructor", "data", "suspend", "private", "value", "context", "Class", "GET", "CONTEXT")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(schema(name)) }
        }
    }

    @Test
    fun duplicateWireNamesAndPropertyCollisionsAreRejectedInEitherOrder() {
        // 线名唯一（大写归一）+ 属性名唯一：两套判据分别命中。
        for (names in listOf(listOf("age", "AGE"), listOf("fooBar", "FOObar"), listOf("title", "Title"),
            listOf("father_id", "fatherId"), listOf("x_Y", "xY"))) {
            for (order in listOf(names, names.reversed())) {
                val error = assertFailsWith<IllegalArgumentException>(order.toString()) {
                    AccessorEmit.emit(schema(*order.toTypedArray()))
                }
                assertContains(error.message.orEmpty(), "Duplicate")
            }
        }
        // 2026-11-16 改造：`age` + `set_age` / `age` + `setAge` 旧实现会撞 setName 对
        // （字段展开成 getXxx/setXxx 方法）；新形状只有属性名，这两个名字互不相干，必须能生成。
        for (names in listOf(listOf("age", "set_age"), listOf("age", "setAge"))) {
            val source = AccessorEmit.emit(schema(*names.toTypedArray()))
            assertEquals(listOf("age", "setAge"), keyProperties(source))
            assertEquals(listOf("age", "setAge"), viewProperties(source))
        }
    }

    @Test
    fun stringEntityReferencesAreRejectedRatherThanSilentlyTreatedAsStrings() {
        val invalid = schema("father").copy(
            components = listOf(
                ComponentSpec(ComponentDecl("family", listOf(FieldDecl("father", FieldType.STR, true))), "component"),
            ),
        )
        assertFailsWith<IllegalArgumentException> { AccessorEmit.emit(invalid) }
        assertFailsWith<IllegalArgumentException> {
            AccessorEmit.emit(parseSchema("schema\texample.generated\tExample\tfamily\nfield\tfather\tSTR\ttrue"))
        }
    }

    @Test
    fun invalidPackagesClassesAndComponentInjectionAreRejected() {
        val valid = schema("age")
        for (name in listOf("", ".example", "example.", "example..generated", "example.class", "example.by", "9example",
            "example-name", "example/name", "example\nimport injected", "example;import", "`example`")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.copy(packageName = name)) }
        }
        for (name in listOf("", "9Example", "_Example", "class", "data", "EntityApi", "EntityId", "Long", "String",
            "Boolean", "Bad.Name", "Bad Name", "Bad\nclass Injected", "Bad\"", "Bad;", "`Example`")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.copy(baseName = name)) }
        }
        // 2026-11-16 改造：组件名不再当 Kotlin 类名用，于是**不再**要求是标识符 ——
        // `memory.factData`、`lw.char.persona` 这种带点的名字是合法的（AccessorEmit 里有注释，
        // 迁移 mod-memory 时撞出来的）。现在只要求非空 + 塞进字符串字面量不炸。
        for (name in listOf("", " ", "family\"", "family\\", "family\$", "family\t", "family\n")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.withSingleComponent(name)) }
        }
        for (name in listOf("family", "family.name", "memory.factData", "1family")) {
            val source = AccessorEmit.emit(valid.withSingleComponent(name))
            assertContains(source, "ComponentKey(\"$name\")")
        }
        // 原型名与组件名走**同一条**字面量判据（AccessorEmit.literalSafe），允许点分（`family.child`）。
        // 早期版本的 archetype 分支只挡了 tab/换行，`a"b` / `$x` / `a\b` 都能过 —— 前者产物不是合法
        // Kotlin，中者变成字符串模板插值，后者是退格转义（**静默改名**，线名少一个字符）。这条把
        // 那三个洞钉住。
        for (name in listOf("", " ", "family\nchild", "family\rchild", "family\"child", "family\\child",
            "family\$child")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.copy(archetype = name)) }
        }
        assertContains(AccessorEmit.emit(valid.copy(archetype = "family.child")), "ArchetypeKey(\"family.child\")")
    }

    @Test
    fun standardLibraryPackageIsRejectedWithoutRejectingSimilarNames() {
        val valid = schema("age")
        for (name in listOf("kotlin", "kotlin.demo")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.copy(packageName = name)) }
        }
        for (name in listOf("kotlinx.demo", "demo.kotlin")) {
            assertTrue(AccessorEmit.emit(valid.copy(packageName = name)).startsWith("package $name\n"))
        }
    }

    /** 新能力（2026-11-16）：覆盖指向不存在的字段必须报错，不许静默忽略。 */
    @Test
    fun propertyOverrideTargetingUnknownFieldIsRejected() {
        val schema = SchemaDecl(
            "example.generated", "Example",
            ComponentDecl("family", listOf(FieldDecl("age"))),
            propertyOverrides = mapOf("nope" to "x"),
        )
        val error = assertFailsWith<IllegalArgumentException> { AccessorEmit.emit(schema) }
        assertContains(error.message.orEmpty(), "unknown field")
        assertContains(error.message.orEmpty(), "nope")
    }

    @Test
    fun componentLinesConflictWithHeaderColumnAndMustNotCollideWithFieldsOrEachOther() {
        // 头行第 4 列（老单组件写法）与 `component` 行是两种互斥写法。
        assertFailsWith<IllegalArgumentException> {
            parseSchema("schema\texample.generated\tNpc\tfamily\ncomponent\tlw.char.persona\n")
        }
        // 组件行不能重名，键属性名也不能重名（同一个 object 里两个成员同名 = 生成物编译不过）。
        assertFailsWith<IllegalArgumentException> {
            parseSchema("schema\texample.generated\tNpc\ncomponent\tlw.a.persona\ncomponent\tlw.a.persona\n")
        }
        assertFailsWith<IllegalArgumentException>("Duplicate key property") {
            AccessorEmit.emit(
                parseSchema("schema\texample.generated\tNpc\ncomponent\tlw.a.persona\ncomponent\tlw.b.persona\n"),
            )
        }
        // 键属性名与字段属性名同住 `Keys`，撞了也得拦。
        assertFailsWith<IllegalArgumentException> {
            val npc = "schema\texample.generated\tNpc\ncomponent\tfamily\nfield\tcomponent\tINT\tfalse\n"
            AccessorEmit.emit(parseSchema(npc))
        }
        // 字段行不能出现在任何组件之前。
        assertFailsWith<IllegalArgumentException> {
            parseSchema("schema\texample.generated\tNpc\nfield\tage\tINT\tfalse\n")
        }
    }

    /** 换掉唯一那个组件的名字（键属性名保持 `component`，与头行第 4 列的老写法同形）。 */
    private fun SchemaDecl.withSingleComponent(name: String) =
        copy(components = listOf(ComponentSpec(ComponentDecl(name, fields), "component")))
}
