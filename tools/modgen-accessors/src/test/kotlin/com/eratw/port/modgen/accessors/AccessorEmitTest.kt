package com.eratw.port.modgen.accessors

import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modfamily.generated.FamilyAccessors
import com.eratw.port.modgen.accessors.generated.FreshAccessors
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccessorEmitTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun familyBusinessCodeUsesOnlyTypedAccessors() {
        val entities: EntityApi = FakeEntityApi()
        val child = entities.create("person")
        val parent = entities.create("person")
        val family = FamilyAccessors(entities)

        assertTrue(family.setAge(child, 42L))
        assertTrue(family.setTitle(child, "家族の記録"))
        assertTrue(family.setFather(child, parent))
        val age: Long = family.age(child)
        val title: String = family.title(child)
        val father: EntityId = family.father(child)
        assertEquals(42L, age)
        assertEquals("家族の記録", title)
        assertEquals(parent, father)
    }

    @Test
    fun freshlyGeneratedClassIsCompiledAndCalledThroughTypedAccessors() {
        val entities: EntityApi = FakeEntityApi()
        val child = entities.create("person")
        val parent = entities.create("person")
        val fresh = FreshAccessors(entities)

        assertTrue(fresh.setAge(child, 73L))
        assertTrue(fresh.setDisplayName(child, "新しい名前"))
        assertTrue(fresh.setFatherId(child, parent))
        val age: Long = fresh.age(child)
        val displayName: String = fresh.displayName(child)
        val fatherId: EntityId = fresh.fatherId(child)
        assertEquals(73L, age)
        assertEquals("新しい名前", displayName)
        assertEquals(parent, fatherId)
    }

    @Test
    fun longAndEntityReferenceBitsMatchNativeApiInBothDirections() {
        // TEST INFRASTRUCTURE: raw IDs and native calls are the independent bit/wire oracle.
        val api = FakeEntityApi()
        val child = api.create("person")
        val family = FamilyAccessors(api)
        val fresh = FreshAccessors(api)
        assertFalse(api.exists(EntityId(Long.MIN_VALUE)), "Negative dangling references are not live entities")
        val random = Random(0x5745L)
        val values = (listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L,
            -9_007_199_254_740_993L, 0x123456789abcdefL) +
            (0..63).flatMap { listOf(1L shl it, (1L shl it).inv()) } +
            List(32) { random.nextLong() }).distinct()

        for (bits in values) {
            assertTrue(family.setAge(child, bits))
            assertTrue(fresh.setAge(child, bits))
            assertEquals(bits, api.getInt(child, "age"))
            assertEquals(bits, api.getInt(child, "AGE"))
            assertEquals(bits, family.age(child))
            assertEquals(bits, fresh.age(child))
            assertTrue(api.setInt(child, "age", bits.inv()))
            assertTrue(api.setInt(child, "AGE", bits.inv()))
            assertEquals(bits.inv(), family.age(child))
            assertEquals(bits.inv(), fresh.age(child))

            val reference = EntityId(bits)
            assertTrue(family.setFather(child, reference))
            assertTrue(fresh.setFatherId(child, reference))
            assertEquals(bits, api.getInt(child, "father"))
            assertEquals(bits, api.getInt(child, "father_ID"))
            assertEquals(reference, family.father(child))
            assertEquals(reference, fresh.fatherId(child))
            assertTrue(api.setInt(child, "father", bits.inv()))
            assertTrue(api.setInt(child, "father_ID", bits.inv()))
            assertEquals(bits.inv(), family.father(child).raw)
            assertEquals(bits.inv(), fresh.fatherId(child).raw)
        }
    }

    @Test
    fun stringsRoundTripWithoutTrimmingEscapingOrNewlineConversion() {
        // TEST INFRASTRUCTURE: native API checks distinguish STR routing from INT routing.
        val api = FakeEntityApi()
        val entity = api.create("person")
        val family = FamilyAccessors(api)
        val fresh = FreshAccessors(api)
        for (value in listOf("", "  leading and trailing  ", "家族・名前", "e\u0301", "\"\\\t\r\n\u0000")) {
            assertTrue(family.setTitle(entity, value))
            assertTrue(fresh.setDisplayName(entity, value))
            assertEquals(value, api.getStr(entity, "title"))
            assertEquals(value, api.getStr(entity, "display_name"))
            assertEquals(value, family.title(entity))
            assertEquals(value, fresh.displayName(entity))
            val nativeValue = "native:$value"
            assertTrue(api.setStr(entity, "title", nativeValue))
            assertTrue(api.setStr(entity, "display_name", nativeValue))
            assertEquals(nativeValue, family.title(entity))
            assertEquals(nativeValue, fresh.displayName(entity))
        }
    }

    @Test
    fun failedWritesReturnFalseAndPreserveAllThreeTypes() {
        val api = FakeEntityApi()
        val entity = api.create("person")
        val father = api.create("person")
        val family = FamilyAccessors(api)
        val fresh = FreshAccessors(api)
        assertTrue(family.setAge(entity, Long.MIN_VALUE))
        assertTrue(family.setTitle(entity, "old family"))
        assertTrue(family.setFather(entity, father))
        assertTrue(fresh.setAge(entity, Long.MAX_VALUE))
        assertTrue(fresh.setDisplayName(entity, "old fresh"))
        assertTrue(fresh.setFatherId(entity, father))

        api.rejectWrites = true
        assertFalse(family.setAge(entity, 1L))
        assertFalse(family.setTitle(entity, "changed"))
        assertFalse(family.setFather(entity, EntityId.NULL))
        assertFalse(fresh.setAge(entity, 2L))
        assertFalse(fresh.setDisplayName(entity, "changed"))
        assertFalse(fresh.setFatherId(entity, EntityId.NULL))
        assertEquals(Triple(Long.MIN_VALUE, "old family", father), familyValues(family, entity))
        assertEquals(Triple(Long.MAX_VALUE, "old fresh", father), freshValues(fresh, entity))
    }

    @Test
    fun absentValuesAndMissingOrDestroyedEntitiesUseCleanDefaults() {
        // TEST INFRASTRUCTURE: explicitly manufacture an unknown ID without creating it.
        val api = FakeEntityApi()
        val entity = api.create("person")
        val destroyed = api.create("person")
        val family = FamilyAccessors(api)
        val fresh = FreshAccessors(api)
        assertTrue(family.setAge(destroyed, 11L))
        assertTrue(fresh.setDisplayName(destroyed, "removed"))
        assertTrue(api.destroy(destroyed))
        assertDefaults(family, fresh, entity)
        for (missing in listOf(EntityId.NULL, EntityId(-77L), destroyed)) {
            assertDefaults(family, fresh, missing)
            assertWritesRejected(family, fresh, missing)
            assertDefaults(family, fresh, missing)
            assertFalse(api.exists(missing))
        }
    }

    @Test
    fun undeclaredFieldsCannotBeCreatedByAccessors() {
        val api = FakeEntityApi(intFields = emptySet(), strFields = emptySet())
        val entity = api.create("person")
        val family = FamilyAccessors(api)
        val fresh = FreshAccessors(api)
        assertDefaults(family, fresh, entity)
        assertWritesRejected(family, fresh, entity)
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
        val family = FamilyAccessors(api)
        val fresh = FreshAccessors(api)
        val otherFamily = FamilyAccessors(otherApi)
        val otherFresh = FreshAccessors(otherApi)
        assertTrue(family.setAge(first, 10L))
        assertTrue(family.setTitle(first, "first"))
        assertTrue(family.setFather(first, second))
        assertTrue(family.setAge(second, 20L))
        assertTrue(family.setTitle(second, "second"))
        assertTrue(family.setFather(second, first))
        assertTrue(fresh.setAge(first, 30L))
        assertTrue(fresh.setDisplayName(first, "fresh"))
        assertTrue(fresh.setFatherId(first, EntityId.NULL))
        assertTrue(otherFamily.setAge(otherEntity, 40L))
        assertTrue(otherFamily.setTitle(otherEntity, "other family"))
        assertTrue(otherFamily.setFather(otherEntity, otherEntity))
        assertTrue(otherFresh.setAge(otherEntity, 50L))
        assertTrue(otherFresh.setDisplayName(otherEntity, "other fresh"))
        assertTrue(otherFresh.setFatherId(otherEntity, otherEntity))
        assertTrue(api.setInt(first, "other_age", 60L))
        assertTrue(api.setStr(first, "other_title", "unrelated component"))
        assertTrue(api.setInt(first, "other_father", -60L))

        assertEquals(Triple(10L, "first", second), familyValues(FamilyAccessors(api), first))
        assertEquals(Triple(20L, "second", first), familyValues(family, second))
        assertEquals(Triple(30L, "fresh", EntityId.NULL), freshValues(FreshAccessors(api), first))
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
        assertEquals("", api.getStr(first, "displayName"))
        assertEquals(0L, api.getInt(first, "fatherId"))
    }

    @Test
    fun bothResourcesDeclareTheExpectedWireNamesAndTypes() {
        val family = SchemaDecl("com.eratw.port.modfamily.generated", "FamilyAccessors", ComponentDecl("family", listOf(
            FieldDecl("age", FieldType.INT), FieldDecl("title", FieldType.STR), FieldDecl("father", FieldType.INT, true),
        )))
        val fresh = SchemaDecl("com.eratw.port.modgen.accessors.generated", "FreshAccessors", ComponentDecl("family", listOf(
            FieldDecl("AGE", FieldType.INT), FieldDecl("display_name", FieldType.STR), FieldDecl("father_ID", FieldType.INT, true),
        )))
        assertEquals(family, parseSchema(resource("family.schema.tsv")))
        assertEquals(fresh, parseSchema(resource("fresh.schema.tsv")))
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
        for (resourceName in listOf("family.schema.tsv", "fresh.schema.tsv")) {
            val source = AccessorEmit.emit(parseSchema(resource(resourceName)))
            assertContains(source, "entities.getRef(e, ")
            assertContains(source, "entities.setRef(e, ")
            assertFalse(".raw" in source, "$resourceName 生成物不得泄漏位域")
            assertFalse("EntityId(entities.getInt" in source, "$resourceName 不得再手写包装")
            assertFalse("entities.setInt(e, \"father\", v.raw)" in source)
        }
        // 生成访问器与公开引用口落在同一张 INT 列：两个方向逐值等价。
        val api = FakeEntityApi()
        val child = api.create("person")
        val father = api.create("person")
        val family = FamilyAccessors(api)
        assertTrue(family.setFather(child, father))
        assertEquals(father, api.getRef(child, "father"), "生成访问器写入后公开引用口读到同一身份")
        assertEquals(family.father(child), api.getRef(child, "father"))
        assertTrue(api.setRef(child, "father", EntityId.NULL))
        assertEquals(EntityId.NULL, family.father(child), "公开引用口写入后生成访问器读到同一 INT 列")
    }

    @Test
    fun newEntityApiRefMemberNamesAreReservedAgainstFieldNames() {
        val names = EntityApi::class.java.methods.map { it.name.substringBefore('-') }.toSet()
        assertTrue("getRef" in names)
        assertTrue("setRef" in names)
        for (field in listOf("getRef", "setRef", "get_ref", "set_ref")) {
            assertFailsWith<IllegalArgumentException>(field) { AccessorEmit.emit(schema(field)) }
        }
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
        assertTrue(parseSchema("schema\texample.generated\tEmptyAccessors\tfamily").component.fields.isEmpty())
    }

    @Test
    fun parserRejectsMalformedRowsAndReportsTheirLine() {
        for (header in listOf("", "\n", "schema", "schema\tpkg\tAccessors", "wrong\tpkg\tAccessors\tfamily",
            "schema\tpkg\tAccessors\tfamily\textra")) {
            assertContains(assertFailsWith<IllegalArgumentException> { parseSchema(header) }.message.orEmpty(), "line 1")
        }
        val header = "schema\texample.generated\tAccessors\tfamily\n"
        for (row in listOf("", " ", "# comment", "field\tage\tINT", "field\tage\tINT\tfalse\textra",
            "wrong\tage\tINT\tfalse", "field\t\tINT\tfalse", "field\t \tINT\tfalse",
            "field\tage\tint\tfalse", "field\tage\tFLOAT\tfalse")) {
            val error = assertFailsWith<IllegalArgumentException>(row) { parseSchema(header + row + "\n") }
            assertContains(error.message.orEmpty(), "line 2")
        }
        val error = assertFailsWith<IllegalArgumentException> {
            parseSchema(header + "field\tage\tINT\tfalse\n\nfield\ttitle\tSTR\tfalse\n")
        }
        assertContains(error.message.orEmpty(), "line 3")
    }

    @Test
    fun entityReferenceBooleanMustBeStrictlyLowercaseTrueOrFalse() {
        for (boolean in listOf("TRUE", "True", "FALSE", "False", "yes", "1", "0", "", " true", "false ")) {
            val error = assertFailsWith<IllegalArgumentException>(boolean) {
                parseSchema("schema\texample.generated\tAccessors\tfamily\nfield\tfather\tINT\t$boolean")
            }
            assertContains(error.message.orEmpty(), "line 2")
            assertContains(error.message.orEmpty(), "true or false")
        }
    }

    @Test
    fun camelCasePreservesMixedCaseWireKeysAndDeclarationOrder() {
        val names = listOf("zValue", "AGE", "display_name", "father_ID", "URL_Value", "iOS_buildID", "HTTP_v2_ID", "a1_B2")
        val getters = listOf("zValue", "age", "displayName", "fatherId", "urlValue", "iOSBuildID", "httpV2Id", "a1B2")
        val schema = schema(*names.toTypedArray())
        val source = AccessorEmit.emit(schema)
        val expectedMethods = getters.flatMap { listOf(it, "set" + it.replaceFirstChar { c -> c.uppercaseChar() }) }
        assertEquals(expectedMethods, methodNames(source))
        val wireKeys = Regex("entities\\.(?:get|set)(?:Int|Str)\\(e, \"([^\"]+)\"").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(names.flatMap { listOf(it, it) }, wireKeys, "键必须逐字节等于声明里的字段名：不拼组件名、不改大小写")
        val reversed = schema.copy(component = schema.component.copy(fields = schema.component.fields.reversed()))
        assertEquals(expectedMethods.chunked(2).reversed().flatten(), methodNames(AccessorEmit.emit(reversed)))
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
    fun duplicateWireNamesAndGetterSetterCollisionsAreRejectedInEitherOrder() {
        // fooBar/fOObar would be distinct generated methods: wire-name uniqueness must reject them independently.
        for (names in listOf(listOf("age", "AGE"), listOf("fooBar", "FOObar"), listOf("title", "Title"),
            listOf("father_id", "fatherId"), listOf("age", "set_age"), listOf("age", "setAge"), listOf("x_Y", "xY"))) {
            for (order in listOf(names, names.reversed())) {
                assertFailsWith<IllegalArgumentException>(order.toString()) { AccessorEmit.emit(schema(*order.toTypedArray())) }
            }
        }
    }

    @Test
    fun entityApiReflectionMembersAndIndirectSetterCollisionsAreRejected() {
        val members = EntityApi::class.java.methods.map { it.name.substringBefore('-') }.toSet()
        assertTrue(members.containsAll(listOf("getInt", "setInt", "getStr", "setStr", "setState", "getRef", "setRef")))
        for (name in members + setOf("equals", "hashCode", "toString", "int", "str", "state", "get_int", "set_int", "GET_STR")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(schema(name)) }
        }
    }

    @Test
    fun stringEntityReferencesAreRejectedRatherThanSilentlyTreatedAsStrings() {
        val invalid = schema("father").copy(component = ComponentDecl("family", listOf(FieldDecl("father", FieldType.STR, true))))
        assertFailsWith<IllegalArgumentException> { AccessorEmit.emit(invalid) }
        assertFailsWith<IllegalArgumentException> {
            AccessorEmit.emit(parseSchema("schema\texample.generated\tAccessors\tfamily\nfield\tfather\tSTR\ttrue"))
        }
    }

    @Test
    fun invalidPackagesClassesAndComponentInjectionAreRejected() {
        val valid = schema("age")
        for (name in listOf("", ".example", "example.", "example..generated", "example.class", "example.by", "9example",
            "example-name", "example/name", "example\nimport injected", "example;import", "`example`")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.copy(packageName = name)) }
        }
        for (name in listOf("", "9Accessors", "_Accessors", "class", "data", "EntityApi", "EntityId", "Long", "String", "Boolean",
            "Bad.Name", "Bad Name", "Bad\nclass Injected", "Bad\"", "Bad;", "`Accessors`")) {
            assertFailsWith<IllegalArgumentException>(name) { AccessorEmit.emit(valid.copy(accessorName = name)) }
        }
        for (name in listOf("", "family.name", "family\"", "family\\", "family\n", "family;", "1family")) {
            assertFailsWith<IllegalArgumentException>(name) {
                AccessorEmit.emit(valid.copy(component = ComponentDecl(name, valid.component.fields)))
            }
        }
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

    @Test
    fun cliWritesChecksReportsChangedLineAndChecksRestoredBytesWithoutTouchingMtime() {
        val input = writeSchema()
        val output = temp.resolve("nested/generated/FamilyAccessors.kt")
        val expected = AccessorEmit.emit(parseSchema(resource("family.schema.tsv"))).toByteArray(Charsets.UTF_8)
        val written = cli(input.toString(), output.toString())
        assertEquals(0, written.code, written.err)
        assertContains(written.out, "WROTE:")
        assertEquals("", written.err)
        assertContentEquals(expected, Files.readAllBytes(output))
        assertCheckReadOnly(input, output, 0, "OK:")

        val source = expected.toString(Charsets.UTF_8)
        val offset = source.indexOf("setTitle")
        assertTrue(offset >= 0)
        val changed = expected.copyOf().also { it[offset] = 'x'.code.toByte() }
        Files.write(output, changed)
        val stale = assertCheckReadOnly(input, output, 1, "STALE:")
        val line = source.lineSequence().indexOfFirst { "fun setTitle(" in it } + 1
        assertContains(stale.err, "first diff at line $line")
        Files.write(output, expected)
        assertCheckReadOnly(input, output, 0, "OK:")
    }

    @Test
    fun cliCheckOfMissingArtifactDoesNotCreateDirectories() {
        val input = writeSchema()
        val output = temp.resolve("not-created/deep/Accessors.kt")
        val result = cli("--check", input.toString(), output.toString())
        assertEquals(1, result.code, result.err)
        assertContains(result.err, "MISSING:")
        assertContains(result.err, "first diff at line 1")
        assertEquals("", result.out)
        assertFalse(Files.exists(output.parent.parent))
        assertFalse(Files.exists(output))
    }

    @Test
    fun cliRejectsCrlfArtifactsAndMissingFinalNewlineWithoutRewriting() {
        val input = writeSchema()
        val output = temp.resolve("FamilyAccessors.kt")
        val source = AccessorEmit.emit(parseSchema(resource("family.schema.tsv")))
        for (noncanonical in listOf(source.replace("\n", "\r\n"), source.removeSuffix("\n"))) {
            Files.writeString(output, noncanonical, Charsets.UTF_8)
            assertCheckReadOnly(input, output, 1, "STALE:")
        }
        // CRLF is accepted for the input, but the generated output must still be canonical LF.
        val crlfInput = resource("family.schema.tsv").replace("\r\n", "\n").replace("\n", "\r\n")
        Files.writeString(input, crlfInput, Charsets.UTF_8)
        assertEquals(0, cli(input.toString(), output.toString()).code)
        assertContentEquals(source.toByteArray(Charsets.UTF_8), Files.readAllBytes(output))
        assertCheckReadOnly(input, output, 0, "OK:")
    }

    @Test
    fun invalidSchemasNeverOverwriteExistingOutputOrCreateMissingParents() {
        val input = writeSchema()
        val output = temp.resolve("preserved.kt")
        val bytes = "keep this output\r\n".toByteArray(Charsets.UTF_8)
        Files.write(output, bytes)
        val header = "schema\texample.generated\tAccessors\tfamily\n"
        for (invalid in listOf(header + "field\tage\tINT\tFalse", header + "field\tfather\tSTR\ttrue",
            header + "field\tage\tINT\tfalse\nfield\tAGE\tINT\tfalse", "schema\tbad;package\tAccessors\tfamily")) {
            Files.writeString(input, invalid, Charsets.UTF_8)
            for (check in listOf(false, true)) {
                val mtime = pinMtime(output)
                val result = cli(*cliArgs(check, input, output))
                assertEquals(2, result.code, result.err)
                assertContains(result.err, "ERROR:")
                assertEquals("", result.out)
                assertUnchanged(output, bytes, mtime)
                val missing = temp.resolve("never-created/Accessors.kt")
                assertEquals(2, cli(*cliArgs(check, input, missing)).code)
                assertFalse(Files.exists(missing.parent))
            }
        }
    }

    @Test
    fun cliNeverOverwritesSchemaWhenInputAndOutputAreTheSamePath() {
        val input = writeSchema()
        val bytes = Files.readAllBytes(input)
        for (output in listOf(input, temp.resolve("unused/../schema.tsv"))) {
            for (check in listOf(false, true)) {
                val mtime = pinMtime(input)
                val result = cli(*cliArgs(check, input, output))
                assertEquals(2, result.code, result.err)
                assertContains(result.err, "must be different files")
                assertEquals("", result.out)
                assertUnchanged(input, bytes, mtime)
            }
        }
        assertFalse(Files.exists(temp.resolve("unused")))
    }

    @Test
    fun cliArgumentErrorsAreUsageErrorsWithoutCreatingOutput() {
        val input = writeSchema().toString()
        val output = temp.resolve("not-created/Accessors.kt")
        for (args in listOf(emptyArray<String>(), arrayOf("--check"), arrayOf(input), arrayOf("--check", input),
            arrayOf("--unknown", input, output.toString()), arrayOf(input, output.toString(), "extra"),
            arrayOf(input, "--check"), arrayOf("--check", input, output.toString(), "extra"))) {
            val result = cli(*args)
            assertEquals(2, result.code, result.err)
            assertContains(result.err, "Usage:")
            assertEquals("", result.out)
            assertFalse(Files.exists(output.parent))
        }
    }

    @Test
    fun cliMissingInputAndIoFailuresReturnErrorRatherThanStale() {
        val input = writeSchema()
        val directory = Files.createDirectory(temp.resolve("directory.kt"))
        val missingOutput = temp.resolve("not-created/Accessors.kt")
        for ((schemaPath, output) in listOf(temp.resolve("missing.tsv") to missingOutput,
            directory to missingOutput, input to directory)) {
            for (check in listOf(false, true)) {
                val result = cli(*cliArgs(check, schemaPath, output))
                assertEquals(2, result.code, result.err)
                assertContains(result.err, "ERROR:")
                assertEquals("", result.out)
                assertFalse(Files.exists(missingOutput.parent))
                assertTrue(Files.isDirectory(directory))
            }
        }
    }

    private fun schema(vararg names: String) = SchemaDecl(
        "example.generated", "ExampleAccessors", ComponentDecl("family", names.map { FieldDecl(it) }),
    )

    private fun methodNames(source: String): List<String> =
        Regex("    fun ([A-Za-z0-9]+)\\(").findAll(source).map { it.groupValues[1] }.toList()

    private fun resource(name: String): String =
        assertNotNull(javaClass.getResource("/$name"), "Missing test resource: $name")
            .openStream().use { it.readBytes().toString(Charsets.UTF_8) }

    private fun familyValues(accessors: FamilyAccessors, entity: EntityId) =
        Triple(accessors.age(entity), accessors.title(entity), accessors.father(entity))

    private fun freshValues(accessors: FreshAccessors, entity: EntityId) =
        Triple(accessors.age(entity), accessors.displayName(entity), accessors.fatherId(entity))

    private fun assertDefaults(family: FamilyAccessors, fresh: FreshAccessors, entity: EntityId) {
        assertEquals(Triple(0L, "", EntityId.NULL), familyValues(family, entity))
        assertEquals(Triple(0L, "", EntityId.NULL), freshValues(fresh, entity))
    }

    private fun assertWritesRejected(family: FamilyAccessors, fresh: FreshAccessors, entity: EntityId) {
        assertFalse(family.setAge(entity, 1L))
        assertFalse(family.setTitle(entity, "not stored"))
        assertFalse(family.setFather(entity, entity))
        assertFalse(fresh.setAge(entity, 2L))
        assertFalse(fresh.setDisplayName(entity, "not stored"))
        assertFalse(fresh.setFatherId(entity, entity))
    }

    private fun writeSchema(): Path = Files.writeString(temp.resolve("schema.tsv"), resource("family.schema.tsv"), Charsets.UTF_8)

    private data class CliResult(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val code = PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err -> runCli(arrayOf(*args), out, err) }
        }
        return CliResult(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    private fun cliArgs(check: Boolean, input: Path, output: Path): Array<String> =
        (if (check) listOf("--check", input.toString(), output.toString()) else listOf(input.toString(), output.toString())).toTypedArray()

    private fun pinMtime(path: Path): FileTime {
        Files.setLastModifiedTime(path, FileTime.fromMillis(946_684_800_000L))
        return Files.getLastModifiedTime(path)
    }

    private fun assertUnchanged(path: Path, bytes: ByteArray, mtime: FileTime) {
        assertContentEquals(bytes, Files.readAllBytes(path))
        assertEquals(mtime, Files.getLastModifiedTime(path), "Read-only/error path changed the output mtime")
    }

    private fun assertCheckReadOnly(input: Path, output: Path, expectedCode: Int, diagnostic: String): CliResult {
        val bytes = Files.readAllBytes(output)
        val mtime = pinMtime(output)
        val result = cli("--check", input.toString(), output.toString())
        assertEquals(expectedCode, result.code, result.err)
        if (expectedCode == 0) {
            assertContains(result.out, diagnostic)
            assertEquals("", result.err)
        } else {
            assertContains(result.err, diagnostic)
            assertEquals("", result.out)
        }
        assertUnchanged(output, bytes, mtime)
        return result
    }

    // TEST INFRASTRUCTURE: independent, case-sensitive wire storage, not a mocked generated facade.
    private class FakeEntityApi(
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
        // 于是 :96-103 的 INT 列对拍（setFather 后 getInt 读、setInt 后 father() 读）
        // 在改成 getRef/setRef 之后逐值不变，正好证明「不改 codec 不改列存」。
        override fun getRef(id: EntityId, field: String): EntityId = EntityId(getInt(id, field))

        override fun setRef(id: EntityId, field: String, value: EntityId): Boolean =
            setInt(id, field, value.raw)

        override fun query(archetype: String, state: EntityState?): List<EntityId> = records.entries
            .filter { it.value.archetype == archetype && (state == null || it.value.state == state) }
            .map { it.key }
            .sortedBy { it.raw }
    }
}
