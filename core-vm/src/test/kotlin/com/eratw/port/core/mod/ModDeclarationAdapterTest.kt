package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.CsvKeywordDecl
import com.eratw.port.modapi.CsvOp
import com.eratw.port.modapi.CsvValueDecl
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.GlobalDimDecl
import com.eratw.port.modapi.JobCatchUp
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseDecl
import com.eratw.port.modapi.RelationshipDecl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K2 闸：Kotlin mod 声明 → DeclarationRegistry 五域（era-arch-v61u.md §18.8 K2/§5）。
 *
 * 验收复用 M1 判据：双 mod 抢号 = 整包拒绝（无部分注册），冲突一次报齐双边定位。
 * 本闸证 mod 侧 [ModManifest] 经 [ModDeclarationAdapter] 翻译进引擎权威 ModuleDescriptor 后，
 * 与既有 ERB mod 走**同一个**声明事务（不绕过、不另造第二套扩展系统）。
 */
class ModDeclarationAdapterTest {

    private fun manifest(
        packageId: String,
        components: List<ComponentDecl> = emptyList(),
        archetypes: List<ArchetypeDecl> = emptyList(),
        relationships: List<RelationshipDecl> = emptyList(),
        csvKeywords: List<CsvKeywordDecl> = emptyList(),
        csvValues: List<CsvValueDecl> = emptyList(),
        globalDims: List<GlobalDimDecl> = emptyList(),
    ) = ModManifest(
        packageId = packageId,
        components = components,
        archetypes = archetypes,
        relationships = relationships,
        csvKeywords = csvKeywords,
        csvValues = csvValues,
        globalDims = globalDims,
    )

    @Test
    fun `五域翻译进同一冻结快照`() {
        val m = manifest(
            "demo.mod",
            components = listOf(ComponentDecl("Stats", listOf(FieldDecl("hp", FieldType.INT)))),
            archetypes = listOf(ArchetypeDecl("Child", listOf("Stats"))),
            relationships = listOf(RelationshipDecl("PARENT")),
            csvKeywords = listOf(CsvKeywordDecl("CFLAG", -1, "MY_FLAG")),
            csvValues = listOf(CsvValueDecl("CFLAG", "row0", "NAME", CsvOp.REPLACE, "新值")),
            globalDims = listOf(GlobalDimDecl("MOD_STATE", DimType.INT)),
        )
        val descriptor = ModDeclarationAdapter.toDescriptor(m)
        // 翻译保真：schema 自带 packageId、字段类型、原型引用。
        assertEquals("demo.mod", descriptor.packageId)
        assertEquals("demo.mod", descriptor.componentSchemas.single().packageId)
        assertEquals(ComponentFieldType.INT, descriptor.componentSchemas.single().fields.single().type)
        assertEquals(listOf("Stats"), descriptor.entityArchetypes.single().components)

        val snapshot = DeclarationRegistry(csvTableCapacities = mapOf("CFLAG" to 10))
            .addPackage(descriptor)
            .freeze()
        assertNotNull(snapshot.componentSchema("Stats"))
        assertNotNull(snapshot.archetype("Child"))
        assertNotNull(snapshot.relationship("PARENT"))
        assertNotNull(snapshot.globalDim("MOD_STATE"))
        assertEquals(0, snapshot.csvKeywordIndex("CFLAG", "MY_FLAG")) // -1 自动分配首位
        assertNotNull(snapshot.csvCellPlan("CFLAG", "row0", "NAME"))
    }

    @Test
    fun `双 mod 抢同名组件整包拒绝且双边定位`() {
        val a = ModDeclarationAdapter.toDescriptor(
            manifest("a.mod", components = listOf(ComponentDecl("Stats", listOf(FieldDecl("hp"))))),
        )
        val b = ModDeclarationAdapter.toDescriptor(
            manifest("b.mod", components = listOf(ComponentDecl("Stats", listOf(FieldDecl("mp"))))),
        )
        val ex = assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(a).addPackage(b).freeze()
        }
        assertTrue(ex.conflicts.isNotEmpty())
        val pkgs = ex.conflicts.flatMap { listOf(it.first.packageId, it.second.packageId) }.toSet()
        assertTrue("a.mod" in pkgs && "b.mod" in pkgs)
    }

    @Test
    fun `双 mod 抢同 CSV 显式下标整包拒绝`() {
        val a = ModDeclarationAdapter.toDescriptor(
            manifest("a.mod", csvKeywords = listOf(CsvKeywordDecl("CFLAG", 0, "FLAG_A"))),
        )
        val b = ModDeclarationAdapter.toDescriptor(
            manifest("b.mod", csvKeywords = listOf(CsvKeywordDecl("CFLAG", 0, "FLAG_B"))),
        )
        assertThrows<DeclarationConflictException> {
            DeclarationRegistry(csvTableCapacities = mapOf("CFLAG" to 10))
                .addPackage(a).addPackage(b).freeze()
        }
    }

    @Test
    fun `双 mod 各用自动分配不撞号`() {
        val a = ModDeclarationAdapter.toDescriptor(
            manifest("a.mod", csvKeywords = listOf(CsvKeywordDecl("CFLAG", -1, "FLAG_A"))),
        )
        val b = ModDeclarationAdapter.toDescriptor(
            manifest("b.mod", csvKeywords = listOf(CsvKeywordDecl("CFLAG", -1, "FLAG_B"))),
        )
        val snapshot = DeclarationRegistry(csvTableCapacities = mapOf("CFLAG" to 10))
            .addPackage(a).addPackage(b).freeze()
        val ia = snapshot.csvKeywordIndex("CFLAG", "FLAG_A")
        val ib = snapshot.csvKeywordIndex("CFLAG", "FLAG_B")
        assertNotNull(ia)
        assertNotNull(ib)
        assertTrue(ia != ib)
    }

    @Test
    fun `双 mod 抢同名全局变量整包拒绝`() {
        val a = ModDeclarationAdapter.toDescriptor(manifest("a.mod", globalDims = listOf(GlobalDimDecl("MOD_STATE"))))
        val b = ModDeclarationAdapter.toDescriptor(manifest("b.mod", globalDims = listOf(GlobalDimDecl("MOD_STATE"))))
        assertThrows<DeclarationConflictException> {
            DeclarationRegistry().addPackage(a).addPackage(b).freeze()
        }
    }

    @Test
    fun `冲突整包拒绝后无部分注册`() {
        // a 声明合法组件 + 与 b 抢同名原型：整包拒绝，快照不可得（freeze 抛异常 = 无冻结快照）。
        val a = ModDeclarationAdapter.toDescriptor(
            manifest(
                "a.mod",
                components = listOf(ComponentDecl("StatsA", listOf(FieldDecl("hp")))),
                archetypes = listOf(ArchetypeDecl("Child", listOf("StatsA"))),
            ),
        )
        val b = ModDeclarationAdapter.toDescriptor(
            manifest(
                "b.mod",
                components = listOf(ComponentDecl("StatsB", listOf(FieldDecl("mp")))),
                archetypes = listOf(ArchetypeDecl("Child", listOf("StatsB"))),
            ),
        )
        val registry = DeclarationRegistry().addPackage(a).addPackage(b)
        val ex = assertThrows<DeclarationConflictException> { registry.freeze() }
        // 原型抢号冲突在场；合法组件不因整包拒绝而单独落地（无快照即无部分注册）。
        assertTrue(ex.conflicts.any { it.key.equals("CHILD", ignoreCase = true) })
    }

    @Test
    fun `jobTemplates 翻译进 phase-jobs 声明域且声明序保真`() {
        val m = ModManifest(
            packageId = "demo.mod",
            phases = listOf(PhaseDecl("FAMILY_TICK")),
            jobTemplates = listOf(
                JobTemplateDecl("APPOINTMENT", "FAMILY_TICK", priority = 2),
                JobTemplateDecl("GIFT", "DAY_END", catchUp = JobCatchUp.REPLAY, maxCatchUp = 3L),
                JobTemplateDecl("MAIL", "DAY_START", catchUp = JobCatchUp.MISS_SKIP),
            ),
        )
        val descriptor = ModDeclarationAdapter.toDescriptor(m)
        assertEquals(3, descriptor.jobTemplateDeclarations.size)
        assertEquals(
            listOf("APPOINTMENT", "GIFT", "MAIL"),
            descriptor.jobTemplateDeclarations.map { it.templateKey },
        )
        assertEquals("FAMILY_TICK", descriptor.jobTemplateDeclarations[0].phase)
        assertEquals("REPLAY", descriptor.jobTemplateDeclarations[1].catchUp.name)
        assertEquals(3L, descriptor.jobTemplateDeclarations[1].maxCatchUp)
        assertEquals("MISS_SKIP", descriptor.jobTemplateDeclarations[2].catchUp.name)
        assertEquals("DAY_START", descriptor.jobTemplateDeclarations[2].phase)
    }

    @Test
    fun `jobTemplate 引用未声明相位拒绝`() {
        val m = ModManifest(
            packageId = "bad.mod",
            jobTemplates = listOf(JobTemplateDecl("T1", phase = "NOPE_PHASE")),
        )
        val ex = assertThrows<IllegalArgumentException> { ModDeclarationAdapter.toDescriptor(m) }
        assertTrue(ex.message!!.contains("未声明的相位"))
    }
}