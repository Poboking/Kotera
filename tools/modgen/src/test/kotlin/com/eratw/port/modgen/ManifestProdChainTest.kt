package com.eratw.port.modgen

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.CsvPatchOp
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.CharaRouteDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.CsvKeywordDecl
import com.eratw.port.modapi.CsvOp
import com.eratw.port.modapi.CsvValueDecl
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.GlobalDimDecl
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobCatchUp
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseDecl
import com.eratw.port.modapi.RelationshipDecl
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestProdChainTest {
    @TempDir
    lateinit var tempDir: Path

    private val sections = listOf(
        "meta", "requires", "load-after", "external-calls", "override-functions",
        "global-dims", "private-dims", "csv-patches", "csv-value-patches", "entity-refs",
        "component-schemas", "entity-archetypes", "relationship-schemas",
        "hooks", "interaction-hooks", "phase-jobs", "exported-macros", "artifact-sha256",
    )

    private fun fullSource() = ManifestSource(
        packageId = "mod.full",
        version = "01.2.3+Build",
        requires = listOf("mod.z", "mod.a"),
        loadAfter = listOf("mod.late", "mod.early"),
        globalDims = listOf(
            GlobalDimDecl("Z_REF", dims = listOf(2)),
            GlobalDimDecl("A_REF"),
            GlobalDimDecl("LABELS", DimType.STR, listOf(2, 3), isSaved = false),
        ),
        csvKeywords = listOf(
            CsvKeywordDecl("CFLAG", -1, "MixedCase"),
            CsvKeywordDecl("TALENT", 7, "Alias", isAlias = true),
        ),
        csvValues = listOf(
            CsvValueDecl("CFLAG", "NO:1", "初期值", CsvOp.ADD, "1"),
            CsvValueDecl("CFLAG", "NO:1", "备注", CsvOp.REPLACE, "  原文\n保留|  "),
            CsvValueDecl("TALENT", "NO:2", "削除", CsvOp.REMOVE),
        ),
        entityRefs = linkedSetOf("Z_REF", "A_REF"),
        components = listOf(
            ComponentDecl("State", listOf(FieldDecl("owner", isEntityRef = true), FieldDecl("note", FieldType.STR))),
        ),
        archetypes = listOf(ArchetypeDecl("Actor", listOf("State"))),
        relationships = listOf(RelationshipDecl("Pair", directed = false, dedupeEdges = false, cascadeOnDestroy = false)),
        hooks = listOf(
            HookDecl("Z_TARGET", HookPhase.PREFIX, 2, mayMutateArgs = true),
            HookDecl("A_TARGET", HookPhase.POSTFIX, 1, mayMutateReturn = true, runOnFailure = true),
        ),
        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.SETTLEMENT, 3)),
        phases = listOf(PhaseDecl("Z_PHASE", 2), PhaseDecl("A_PHASE", 1)),
        jobs = listOf(JobDecl("Z_JOB", "Z_PHASE", 7L, 3L, 2), JobDecl("A_JOB", "DAY_END", 9L)),
        jobTemplates = listOf(
            JobTemplateDecl("Z_TEMPLATE", "Z_PHASE", 2, JobCatchUp.REPLAY, 3L),
            JobTemplateDecl("A_TEMPLATE", "A_PHASE", catchUp = JobCatchUp.MISS_SKIP),
        ),
        // M5-4 刀 1：chara-field-routes 域走 component-schemas 段判别 tag B1（全链路往返）。
        charaRoutes = listOf(
            CharaRouteDecl("CFLAG", listOf(7, 3)),
            CharaRouteDecl("CSTR"),
        ),
    )

    private fun minimalSource() = ManifestSource(
        packageId = "mod.sample",
        version = "1",
        components = listOf(ComponentDecl("Counter", listOf(FieldDecl("value")))),
        csvValues = listOf(CsvValueDecl("CFLAG", "NO:1", "初期值", CsvOp.REPLACE, "1")),
    )

    private fun assetBytes(): ByteArray = assertNotNull(javaClass.getResourceAsStream("/minimal.manifest"))
        .use { it.readBytes() }

    private fun assertDimEquals(expected: DimSpec, actual: DimSpec) {
        assertEquals(expected.name, actual.name)
        assertEquals(expected.isStr, actual.isStr)
        assertContentEquals(expected.dims, actual.dims)
        assertEquals(expected.isConst, actual.isConst)
        assertEquals(expected.isRef, actual.isRef)
        assertEquals(expected.isGlobal, actual.isGlobal)
        assertEquals(expected.isSavedata, actual.isSavedata)
        assertEquals(expected.isCharaData, actual.isCharaData)
        assertEquals(expected.isStatic, actual.isStatic)
        assertEquals(expected.isPrivate, actual.isPrivate)
        assertContentEquals(expected.defaultsInt, actual.defaultsInt)
        assertEquals(expected.defaultsStr, actual.defaultsStr)
        assertEquals(expected.defaultsFloat?.toRawBits(), actual.defaultsFloat?.toRawBits())
        assertEquals(expected.isFloat, actual.isFloat)
    }

    private fun assertDescriptorEquals(expected: ModuleDescriptor, actual: ModuleDescriptor) {
        assertEquals(expected.globalDims.size, actual.globalDims.size)
        expected.globalDims.zip(actual.globalDims).forEach { (e, a) -> assertDimEquals(e, a) }
        assertEquals(expected.copy(globalDims = emptyList()), actual.copy(globalDims = emptyList()))
    }

    @Test
    fun factoryPreservesEveryDeclarationAndMetadataField() {
        val s = fullSource()
        val expected = ModManifest(
            packageId = s.packageId,
            version = s.version,
            requires = s.requires,
            loadAfter = s.loadAfter,
            entityRefs = s.entityRefs,
            globalDims = s.globalDims,
            csvKeywords = s.csvKeywords,
            csvValues = s.csvValues,
            components = s.components,
            archetypes = s.archetypes,
            relationships = s.relationships,
            hooks = s.hooks,
            interactionHooks = s.interactionHooks,
            phases = s.phases,
            jobs = s.jobs,
            jobTemplates = s.jobTemplates,
            charaRoutes = s.charaRoutes,
        )
        assertEquals(expected, ManifestFactory.create(s))
    }

    @Test
    fun fullSourceRoundTripsEveryDescriptorField() {
        val source = fullSource()
        val expected = ModDeclarationAdapter.toDescriptor(ManifestFactory.create(source))
        val actual = ManifestCodec.decode(ManifestBuild.build(source))
        assertDescriptorEquals(expected, actual)
    }

    @Test
    fun repeatedBuildsAreByteIdentical() {
        val source = fullSource()
        val bytes = ManifestBuild.build(source)
        assertContentEquals(bytes, ManifestBuild.build(source))
        assertContentEquals(bytes, ManifestCodec.encode(ManifestCodec.decode(bytes)))
    }

    @Test
    fun setInsertionOrderDoesNotChangeBytes() {
        val source = fullSource()
        assertContentEquals(
            ManifestBuild.build(source),
            ManifestBuild.build(source.copy(entityRefs = linkedSetOf("A_REF", "Z_REF"))),
        )
    }

    /**
     * component-schemas 段的条目判别首 token 清单（B1 = 路由；S = 缺省裸 schema；B0 = 显式裸）。
     * 只认条目边界上的 tag —— B1 同时是字段 isEntityRef 的布尔 token，全文 grep 会误伤。
     */
    private fun componentSchemasEntryTags(bytes: ByteArray): List<String> {
        val all = bytes.toString(Charsets.UTF_8).removeSuffix("\n").split("\n")
        val start = all.indexOf("@component-schemas")
        var i = start + 2 // 跳过段头与计数行
        val tags = ArrayList<String>()
        while (all[i] != "/") {
            when (val head = all[i]) {
                "B1" -> {
                    tags += "B1"
                    i += 3 + all[i + 2].substring(1).toInt() // tag + S变量 + I格数 + I×格数
                }
                "B0" -> {
                    tags += "B0"
                    i += schemaEntryWidth(all, i)
                }
                else -> {
                    assertTrue(head.startsWith("S"), "非条目首 token：$head")
                    tags += "S"
                    i += schemaEntryWidth(all, i)
                }
            }
        }
        return tags
    }

    /** 裸 schema 条目宽度（i 指向条目首 S 包行）：S包 + S名 + I字段数 + 每字段 (S,E,B) 三行。 */
    private fun schemaEntryWidth(all: List<String>, i: Int): Int = 3 + 3 * all[i + 2].substring(1).toInt()

    /**
     * M5-4 刀 1 判据 4（无路由 manifest 往返逐字节不变）：既有载体（family/memory/lw 四包，
     * 均不声明路由）的字节不被声明面扩面改动 —— 缺省形不落判别 tag 行，write → read → write
     * 逐位一致；入库资产同口径。这条用例锁死「资产零重钉」。
     */
    @Test
    fun routelessManifestRoundTripsByteIdentical() {
        for (source in listOf(minimalSource(), fullSource().copy(charaRoutes = emptyList()))) {
            val bytes = ManifestBuild.build(source)
            assertTrue(
                !componentSchemasEntryTags(bytes).contains("B1"),
                "无路由的 component-schemas 段不得产出路由判别 tag 行",
            )
            assertContentEquals(bytes, ManifestCodec.encode(ManifestCodec.decode(bytes)))
        }
        // 入库资产（无路由）同口径：decode → encode 逐位还原。
        val asset = assetBytes()
        assertContentEquals(asset, ManifestCodec.encode(ManifestCodec.decode(asset)))
    }

    /** 带路由的 manifest：路由走 component-schemas 段 B1 子类型，全链路往返保真且字节稳定。 */
    @Test
    fun routedManifestRoundTripsThroughComponentSchemasSubtype() {
        val source = fullSource()
        val bytes = ManifestBuild.build(source)
        assertEquals(listOf("S", "B1", "B1"), componentSchemasEntryTags(bytes), "裸 schema 缺省形 + 两条路由各一条 B1")
        val back = ManifestCodec.decode(bytes)
        assertEquals(
            source.charaRoutes.map { it.variable to it.cells },
            back.charaRoutes.map { it.variable to it.cells },
            "路由声明全链路（source → descriptor → bytes → decode）保真",
        )
        assertContentEquals(bytes, ManifestCodec.encode(back))
    }

    @Test
    fun emitsAllEighteenSectionsInCodecOrder() {
        for (source in listOf(fullSource(), ManifestSource("mod.empty"))) {
            val lines = ManifestBuild.build(source).toString(Charsets.UTF_8).lines()
            assertEquals("RT2MANIFEST/3", lines.first())
            assertEquals(sections.map { "@$it" }, lines.filter { it.startsWith("@") })
        }
    }

    private fun sectionBlocks(bytes: ByteArray): List<String> {
        val text = bytes.toString(Charsets.UTF_8)
        return text.substringAfter('\n').splitToSequence("/\n").filter { it.isNotEmpty() }.map { "$it/\n" }.toList()
    }

    private fun corruptedBytes(blocks: List<String>): ByteArray =
        ("RT2MANIFEST/3\n" + blocks.joinToString("")).toByteArray(Charsets.UTF_8)

    @Test
    fun movingAnyWholeSectionIsRejected() {
        val bytes = ManifestBuild.build(fullSource())
        val original = sectionBlocks(bytes)
        assertEquals(18, original.size)
        assertContentEquals(bytes, corruptedBytes(original))
        for (index in original.indices) {
            val moved = original.toMutableList()
            val block = moved.removeAt(index)
            moved.add(if (index == 0) moved.size else 0, block)
            assertFailsWith<ManifestFormatException>(sections[index]) {
                ManifestCodec.decode(corruptedBytes(moved))
            }
        }
    }

    @Test
    fun removingAnySectionIsRejected() {
        val original = sectionBlocks(ManifestBuild.build(fullSource()))
        assertEquals(18, original.size)
        for (index in original.indices) {
            val missing = original.toMutableList().also { it.removeAt(index) }
            assertFailsWith<ManifestFormatException>(sections[index]) {
                ManifestCodec.decode(corruptedBytes(missing))
            }
        }
    }

    @Test
    fun emptyDeclarationsBuildDecodeAndFreezeWithoutConflicts() {
        val descriptor = ManifestCodec.decode(ManifestBuild.build(ManifestSource("mod.empty")))
        assertEquals(ModuleDescriptor("mod.empty"), descriptor)
        val snapshot = DeclarationRegistry().addPackage(descriptor).freeze()
        assertEquals(listOf("mod.empty"), snapshot.packageIds)
        assertTrue(snapshot.globalDims.isEmpty())
        assertTrue(snapshot.privateDims.values.all { it.isEmpty() })
        assertTrue(snapshot.declaredCsvKeywords.isEmpty())
        assertTrue(snapshot.effectiveCsvKeywords.isEmpty())
        assertTrue(snapshot.csvValueCells.isEmpty())
        assertTrue(snapshot.componentSchemas.isEmpty())
        assertTrue(snapshot.entityArchetypes.isEmpty())
        assertTrue(snapshot.relationshipSchemas.isEmpty())
        assertTrue(snapshot.hookDeclarations.isEmpty())
        assertTrue(snapshot.interactionHookDeclarations.isEmpty())
        assertTrue(snapshot.phaseDeclarations.isEmpty())
        assertTrue(snapshot.jobDeclarations.isEmpty())
        assertTrue(snapshot.jobTemplateDeclarations.isEmpty())
        assertTrue(snapshot.charaFieldRoutes.isEmpty(), "无路由 ⇒ 快照路由域空（M5-4 判据 5）")
    }

    @Test
    fun checkedInAssetMatchesProductionBytesAndDecodes() {
        val bytes = assetBytes()
        assertContentEquals(ManifestBuild.build(minimalSource()), bytes)
        val descriptor = ManifestCodec.decode(bytes)
        assertDescriptorEquals(ModDeclarationAdapter.toDescriptor(ManifestFactory.create(minimalSource())), descriptor)
        val snapshot = DeclarationRegistry(csvTableCapacities = mapOf("CFLAG" to 2))
            .addPackage(descriptor, "minimal.manifest").freeze()
        assertEquals("value", assertNotNull(snapshot.componentSchema("Counter")).fields.single().name)
        val cell = assertNotNull(snapshot.csvCellPlan("CFLAG", "NO:1", "初期值"))
        assertEquals(CsvPatchOp.REPLACE, cell.effectiveOp)
        assertEquals("1", cell.effectiveValue)
    }

    @Test
    fun writesFullSourceToDiskAndReadsItBack() {
        val source = fullSource()
        val output = tempDir.resolve("新目录/full.manifest")
        ManifestBuild.write(source, output)
        val bytes = Files.readAllBytes(output)
        assertContentEquals(ManifestBuild.build(source), bytes)
        assertDescriptorEquals(
            ModDeclarationAdapter.toDescriptor(ManifestFactory.create(source)),
            ManifestCodec.decode(bytes),
        )
    }

    @Test
    fun commandLineEntryProducesTheCheckedInAsset() {
        val output = tempDir.resolve("sample/minimal.manifest")
        main(arrayOf(output.toString()))
        assertContentEquals(assetBytes(), Files.readAllBytes(output))
    }

    @Test
    fun longValuesBeyondFloatingPointPrecisionSurvive() {
        val large = 9_007_199_254_740_993L
        val source = ManifestSource(
            packageId = "mod.large",
            jobs = listOf(JobDecl("LONG_JOB", firstDay = large, everyDays = Long.MAX_VALUE)),
            jobTemplates = listOf(JobTemplateDecl("LONG_TEMPLATE", catchUp = JobCatchUp.REPLAY, maxCatchUp = large)),
        )
        val actual = ManifestCodec.decode(ManifestBuild.build(source))
        assertEquals(large, actual.jobDeclarations.single().firstDay)
        assertEquals(Long.MAX_VALUE, actual.jobDeclarations.single().everyDays)
        assertEquals(large, actual.jobTemplateDeclarations.single().maxCatchUp)
    }

    @Test
    fun identityAndVersionAreNotRewritten() {
        val source = minimalSource().copy(packageId = "mod.exact01", version = " 01.0+Build ")
        val manifest = ManifestFactory.create(source)
        val actual = ManifestCodec.decode(ManifestBuild.build(source))
        assertEquals(source.packageId, manifest.packageId)
        assertEquals(source.version, manifest.version)
        assertEquals(source.packageId, actual.packageId)
        assertEquals(source.version, actual.version)
        assertEquals(source.packageId, actual.componentSchemas.single().packageId)
    }

    @Test
    fun invalidDeclarationsFailBeforeOverwritingAnAsset() {
        val output = tempDir.resolve("keep.manifest")
        val original = assetBytes()
        Files.write(output, original)
        val badSources = listOf(
            ManifestSource(" "),
            minimalSource().copy(csvValues = listOf(CsvValueDecl("CFLAG", "NO:1", "初期值", CsvOp.REMOVE, "1"))),
            minimalSource().copy(components = listOf(ComponentDecl("Invalid", listOf(FieldDecl("ref", FieldType.STR, true))))),
            minimalSource().copy(jobTemplates = listOf(JobTemplateDecl("Bad", phase = "UNDECLARED_PHASE"))),
        )
        for (source in badSources) {
            assertFailsWith<IllegalArgumentException> { ManifestBuild.write(source, output) }
            assertContentEquals(original, Files.readAllBytes(output))
        }
    }

    @Test
    fun commandLineRequiresExactlyOneOutputPath() {
        assertFailsWith<IllegalArgumentException> { main(emptyArray()) }
        assertFailsWith<IllegalArgumentException> { main(arrayOf("a", "b")) }
    }
}
