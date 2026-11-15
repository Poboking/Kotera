package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M1-3 生产接缝钉（CONTRACT-RT2-M1-3 §3.2 判据 2/3/4/5）：
 * - [ManifestLoader] 是 DeclarationRegistry 在 main 内首个生产构造点；
 * - 从载体装出的快照查询面与「直接手工喂 descriptor」的快照逐项相等（载体没丢语义）；
 * - 冻结后不可变快照：freeze 幂等、冻结后 addPackage 拒绝、对快照 Map 的写入不泄漏；
 * - 双 Mod 抢同一 CFLAG 关键字 / 同一 entity-ref 键 / 同一组件名 ⇒ 整包拒绝；
 * - 坏档整包拒绝，绝不返回半成品 registry。
 *
 * 边界：entityRefs 在核心 freeze 里无独立冲突域（DeclarationDomain 仅五域，
 * DeclarationRegistry.kt:15-21；entityRefs 全树仅 KDoc / 指纹 / 字段引用，0 事务消费点）。
 * 按蓝图 §7:156「entity-ref 键必须存在于本包 dims 且为整型」，两个 Mod 抢同一
 * entity-ref 键必然各自声明同名 global dim，冲突由 global-dims 全局命名空间承载。
 * 本 lane 不就地补核心，独立域校验列入交付「需组长接线 / 升级架构师」。
 */
class ManifestDeclarationWireTest {

    private fun dim(name: String, isStr: Boolean = false, dims: IntArray = intArrayOf(4)): DimSpec =
        DimSpec(name = name, isStr = isStr, dims = dims, isGlobal = true)

    private fun caps(): Map<String, Int> = mapOf("CFLAG" to 100, "TALENT" to 50)

    private fun baseKeywords(): Map<String, Map<String, Int>> =
        mapOf("CFLAG" to mapOf("NAME" to 0, "CALLNAME" to 1))

    /** 一个不与他包冲突、八域全量铺开的包。 */
    private fun descA(): ModuleDescriptor = ModuleDescriptor(
        packageId = "mod.a",
        version = "1.0",
        requires = listOf("mod.base"),
        globalDims = listOf(dim("A_STATE"), dim("A_NAMES", isStr = true, dims = intArrayOf(2, 3))),
        privateDims = mapOf("@FA" to listOf(dim("A_TMP"))),
        csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "A_FLAG")),
        csvValuePatches = listOf(CsvValuePatch("CFLAG", "NO:1", "memo", CsvPatchOp.ADD, "va")),
        entityRefs = setOf("A_STATE"),
        componentSchemas = listOf(
            ComponentSchema(
                "mod.a", "EduA",
                listOf(
                    ComponentFieldSpec("aId", ComponentFieldType.INT, isEntityRef = true),
                    ComponentFieldSpec("aNote", ComponentFieldType.STR),
                ),
            ),
        ),
        entityArchetypes = listOf(EntityArchetype("StudentA", listOf("EduA"))),
        relationshipSchemas = listOf(
            RelationshipSchema("PARENT_A", autoInverse = true, inverseName = "CHILD_A"),
            RelationshipSchema("CHILD_A"),
        ),
        exportedMacros = mapOf("A_MACRO" to "1"),
        artifactSha256 = "a".repeat(64),
    )

    /** 与 descA 无冲突、且同格值补丁叠加（数据层末位生效）。 */
    private fun descB(): ModuleDescriptor = ModuleDescriptor(
        packageId = "mod.b",
        globalDims = listOf(dim("B_STATE")),
        privateDims = mapOf("@FB" to listOf(dim("B_TMP"))),
        csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "B_FLAG")),
        csvValuePatches = listOf(CsvValuePatch("CFLAG", "NO:1", "memo", CsvPatchOp.REPLACE, "vb")),
        entityRefs = setOf("B_STATE"),
        componentSchemas = listOf(
            ComponentSchema("mod.b", "EduB", listOf(ComponentFieldSpec("bId", ComponentFieldType.INT))),
        ),
        entityArchetypes = listOf(EntityArchetype("StudentB", listOf("EduB"))),
        relationshipSchemas = listOf(RelationshipSchema("PAIR_B", directed = false)),
    )

    private fun sourcesOf(vararg ds: ModuleDescriptor): List<ManifestSource> =
        ds.map { ManifestSource("${it.packageId}.rt2m", ManifestCodec.encode(it)) }

    // -----------------------------------------------------------------------
    // 逐项比较快照查询面（含 DimSpec 的逐字段核对）
    // -----------------------------------------------------------------------
    private fun assertDimSpecEquals(e: DimSpec, a: DimSpec) {
        assertEquals(e.name, a.name)
        assertEquals(e.isStr, a.isStr)
        assertEquals(e.dims.toList(), a.dims.toList())
        assertEquals(e.isConst, a.isConst)
        assertEquals(e.isRef, a.isRef)
        assertEquals(e.isGlobal, a.isGlobal)
        assertEquals(e.isSavedata, a.isSavedata)
        assertEquals(e.isCharaData, a.isCharaData)
        assertEquals(e.isStatic, a.isStatic)
        assertEquals(e.isPrivate, a.isPrivate)
        assertEquals(e.defaultsInt?.toList(), a.defaultsInt?.toList())
        assertEquals(e.defaultsStr, a.defaultsStr)
        assertEquals(e.defaultsFloat?.toRawBits(), a.defaultsFloat?.toRawBits())
        assertEquals(e.isFloat, a.isFloat)
    }

    private fun assertSnapshotsEquivalent(e: DeclarationSnapshot, a: DeclarationSnapshot) {
        assertEquals(e.packageIds, a.packageIds)

        assertEquals(e.globalDims.keys, a.globalDims.keys)
        for (k in e.globalDims.keys) {
            assertEquals(e.globalDims.getValue(k).packageId, a.globalDims.getValue(k).packageId)
            assertDimSpecEquals(e.globalDims.getValue(k).spec, a.globalDims.getValue(k).spec)
        }

        assertEquals(e.privateDims.keys, a.privateDims.keys)
        for (p in e.privateDims.keys) {
            val em = e.privateDims.getValue(p)
            val am = a.privateDims.getValue(p)
            assertEquals(em.keys, am.keys)
            for (f in em.keys) {
                assertEquals(em.getValue(f).size, am.getValue(f).size)
                for (i in em.getValue(f).indices) assertDimSpecEquals(em.getValue(f)[i], am.getValue(f)[i])
            }
        }

        assertEquals(e.declaredCsvKeywords, a.declaredCsvKeywords)
        assertEquals(e.effectiveCsvKeywords, a.effectiveCsvKeywords)
        assertEquals(e.csvValueCells, a.csvValueCells)
        assertEquals(e.componentSchemas, a.componentSchemas)
        assertEquals(e.entityArchetypes, a.entityArchetypes)
        assertEquals(e.relationshipSchemas, a.relationshipSchemas)

        assertEquals(e.globalDim("A_STATE")?.name, a.globalDim("A_STATE")?.name)
        assertEquals(e.privateDimsOf("mod.a", "@FA").map { it.name }, a.privateDimsOf("mod.a", "@FA").map { it.name })
        assertEquals(e.csvKeywordsFor("CFLAG"), a.csvKeywordsFor("CFLAG"))
        assertEquals(e.csvKeywordIndex("CFLAG", "A_FLAG"), a.csvKeywordIndex("CFLAG", "A_FLAG"))
        assertEquals(e.componentSchema("EDUA")?.name, a.componentSchema("EDUA")?.name)
        assertEquals(e.archetype("STUDENTA")?.name, a.archetype("STUDENTA")?.name)
        assertEquals(e.relationship("PARENT_A")?.name, a.relationship("PARENT_A")?.name)
        assertEquals(e.csvCellPlan("CFLAG", "NO:1", "memo"), a.csvCellPlan("CFLAG", "NO:1", "memo"))
    }

    // -----------------------------------------------------------------------
    // 判据 2：生产构造点 + 快照逐项相等
    // -----------------------------------------------------------------------
    @Test
    fun loaderMatchesManualFeedingSnapshotItemByItem() {
        val sources = sourcesOf(descA(), descB())
        val viaLoader = ManifestLoader.load(
            sources,
            csvTableCapacities = caps(),
            baseCsvKeywords = baseKeywords(),
        )
        val manual = DeclarationRegistry(
            csvTableCapacities = caps(),
            baseCsvKeywords = baseKeywords(),
        )
            .addPackage(descA(), "mod.a.rt2m")
            .addPackage(descB(), "mod.b.rt2m")
            .freeze()
        assertSnapshotsEquivalent(manual, viaLoader)
        assertEquals(listOf("mod.a", "mod.b"), viaLoader.packageIds)
        // 动态 ID 两阶段：base 占了 0/1，两个 -1 按链接序得 2/3。
        assertEquals(2, viaLoader.csvKeywordIndex("CFLAG", "A_FLAG"))
        assertEquals(3, viaLoader.csvKeywordIndex("CFLAG", "B_FLAG"))
        // 同格值补丁按链接序叠加、末位生效。
        assertEquals("vb", viaLoader.csvCellPlan("CFLAG", "NO:1", "memo")?.effectiveValue)
    }

    @Test
    fun onCommitFiresExactlyOnceOnSuccessfulLoad() {
        var commits = 0
        ManifestLoader.load(
            sourcesOf(descA()),
            csvTableCapacities = caps(),
            baseCsvKeywords = baseKeywords(),
            onCommit = { commits++ },
        )
        assertEquals(1, commits)
    }

    // -----------------------------------------------------------------------
    // 判据 5：坏档整包拒绝，不返回半成品 registry
    // -----------------------------------------------------------------------
    @Test
    fun loaderRejectsBadManifestWithoutHalfLoadedState() {
        val good = ManifestCodec.encode(descA())
        val bad = good.copyOf(good.size - 3)

        // 坏档在后：先全解码再构造，坏档在 registry 建起来之前就整体抛出。
        val ex = assertFailsWith<ManifestFormatException> {
            ManifestLoader.load(
                listOf(ManifestSource("A", good), ManifestSource("B", bad)),
                csvTableCapacities = caps(),
            )
        }
        assertTrue(ex.message!!.contains("行") || ex.message!!.contains("LF"), ex.message)

        // 坏档在前同样整体拒绝。
        assertFailsWith<ManifestFormatException> {
            ManifestLoader.load(
                listOf(ManifestSource("B", bad), ManifestSource("A", good)),
                csvTableCapacities = caps(),
            )
        }

        // 好档单独仍能装：证明上面拒的是坏档本身，而非留下了半装状态。
        val ok = ManifestLoader.load(
            listOf(ManifestSource("A", good)),
            csvTableCapacities = caps(),
            baseCsvKeywords = baseKeywords(),
        )
        assertEquals(listOf("mod.a"), ok.packageIds)
    }

    // -----------------------------------------------------------------------
    // 判据 3：冻结后不可变快照
    // -----------------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    @Test
    fun frozenSnapshotIsIndependentAndImmutable() {
        val sources = sourcesOf(descA())
        val snap1 = ManifestLoader.load(
            sources, csvTableCapacities = caps(), baseCsvKeywords = baseKeywords(),
        )

        // 尝试污染快照暴露的 Map：即便开了可变口子，也不得泄漏到下一次装载。
        val mutableGlobal = snap1.globalDims as? MutableMap<String, DeclaredGlobalDim>
        mutableGlobal?.put("EVIL", DeclaredGlobalDim("evil", dim("EVIL")))

        val snap2 = ManifestLoader.load(
            sources, csvTableCapacities = caps(), baseCsvKeywords = baseKeywords(),
        )
        assertNotSame(snap1, snap2, "两次装载不得共享可变引用（禁静态可变全局）")
        assertTrue("EVIL" !in snap2.globalDims.keys, "对快照 Map 的写入不得影响下一次装载")
        assertEquals("A_STATE", snap2.globalDim("A_STATE")?.name)

        // 沿用核心既有纪律：freeze 幂等、冻结后 addPackage 拒绝。
        val reg = DeclarationRegistry(csvTableCapacities = caps(), baseCsvKeywords = baseKeywords())
        val frozenOnce = reg.addPackage(descA(), "mod.a.rt2m").freeze()
        assertSame(frozenOnce, reg.freeze())
        assertFailsWith<IllegalStateException> { reg.addPackage(descB(), "mod.b.rt2m") }
    }

    // -----------------------------------------------------------------------
    // 判据 4：双包冲突整包拒绝（报告带 domain + key + 两个 DeclarationSite）
    // -----------------------------------------------------------------------
    @Test
    fun doublePackageConflictIsRejectedWholeTransaction() {
        // (a) 抢同一 CFLAG 关键字（同一显式下标）。
        val a1 = ModuleDescriptor(packageId = "mod.a", csvPatches = listOf(CsvKeywordPatch("CFLAG", 5, "SAME")))
        val b1 = ModuleDescriptor(packageId = "mod.b", csvPatches = listOf(CsvKeywordPatch("CFLAG", 5, "SAME")))
        val e1 = assertFailsWith<DeclarationConflictException> {
            ManifestLoader.load(
                listOf(ManifestSource("A", ManifestCodec.encode(a1)), ManifestSource("B", ManifestCodec.encode(b1))),
                csvTableCapacities = caps(),
            )
        }
        val c1 = e1.conflicts.first { it.domain == DeclarationDomain.CSV_PATCH }
        assertTrue(c1.key.contains("SAME"))
        assertEquals(setOf("mod.a", "mod.b"), setOf(c1.first.packageId, c1.second.packageId))
        assertEquals("A", c1.first.source)
        assertEquals("B", c1.second.source)

        // (b) 抢同一组件名（大小写归一同名）。
        val a2 = ModuleDescriptor(packageId = "mod.a", componentSchemas = listOf(ComponentSchema("mod.a", "Shared")))
        val b2 = ModuleDescriptor(packageId = "mod.b", componentSchemas = listOf(ComponentSchema("mod.b", "shared")))
        val e2 = assertFailsWith<DeclarationConflictException> {
            ManifestLoader.load(
                listOf(ManifestSource("A", ManifestCodec.encode(a2)), ManifestSource("B", ManifestCodec.encode(b2))),
                csvTableCapacities = caps(),
            )
        }
        assertTrue(e2.conflicts.any { it.domain == DeclarationDomain.COMPONENT_SCHEMA && it.key == "SHARED" })

        // (c) 抢同一 entity-ref 键：按蓝图 §7:156 该键须先声明为 dim，
        // 故冲突由 global-dims 全局命名空间承载（核心无 entity-ref 独立域，见类 KDoc）。
        val refDim = dim("LAST_STUDENT_ID")
        val a3 = ModuleDescriptor(packageId = "mod.a", globalDims = listOf(refDim), entityRefs = setOf("LAST_STUDENT_ID"))
        val b3 = ModuleDescriptor(packageId = "mod.b", globalDims = listOf(refDim), entityRefs = setOf("LAST_STUDENT_ID"))
        val e3 = assertFailsWith<DeclarationConflictException> {
            ManifestLoader.load(
                listOf(ManifestSource("A", ManifestCodec.encode(a3)), ManifestSource("B", ManifestCodec.encode(b3))),
                csvTableCapacities = caps(),
            )
        }
        val c3 = e3.conflicts.first { it.key == "LAST_STUDENT_ID" }
        assertEquals(DeclarationDomain.GLOBAL_DIM, c3.domain)
        assertEquals(setOf("mod.a", "mod.b"), setOf(c3.first.packageId, c3.second.packageId))
    }
}
