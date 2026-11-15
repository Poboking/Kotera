package com.eratw.port.modlivingworld

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modmemory.MemoryDeclarations as NativeMemoryDeclarations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Living World 四包的**声明事务**闸（§21.9 判据 4 的声明面半边 + 生产共存形状）。
 *
 * 三层：① 生产注册表全部六只载体（family + memory + eratw.lw 四包）同一次 freeze 零冲突
 * —— 这是 ModCarrierRegistry 一次装六包的声明前提；② 重名 fail-closed（同名关系 kind /
 * 同名字段跨包 → DeclarationConflictException 整包拒绝，反向证明④）；③ 各包 manifest
 * 形状与常量同源自检（照 MemoryModTest 的声明自检口径）。
 */
class LivingWorldDeclarationTest {

    /** 生产注册表的全部载体声明（ModCarrierRegistry.providers 的声明面投影）。 */
    private fun productionManifests(): List<ModManifest> = listOf(
        FamilyDeclarations.manifest,
        NativeMemoryDeclarations.manifest,
        CharacterDeclarations.manifest,
        RelationshipDeclarations.manifest,
        MemoryDeclarations.manifest,
        EventDeclarations.manifest,
    )

    private fun freeze(manifests: List<ModManifest>) {
        val registry = DeclarationRegistry()
        for (manifest in manifests) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
        }
        registry.freeze()
    }

    // ------------------------------------------------------------------
    // 1. 判据 4 正向半边：六载体同事务共存（lw 四包与既有两包零冲突）
    // ------------------------------------------------------------------

    @Test
    fun `生产注册表全部六载体同一次声明事务冻结零冲突`() {
        val manifests = productionManifests()
        assertEquals(
            listOf(
                "eratw.family",
                "eratw.memory",
                "eratw.lw.character",
                "eratw.lw.relationship",
                "eratw.lw.memory",
                "eratw.lw.event",
            ),
            manifests.map { it.packageId },
            "四逻辑包各自独立 packageId，与既有两载体共存（生产 ModCarrierRegistry 的形状）",
        )
        freeze(manifests)
    }

    // ------------------------------------------------------------------
    // 2. 判据 4 反向半边（反向证明④）：重名 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `反向证明 跨包同名关系kind声明事务整包拒绝`() {
        // 与 RelationshipDeclarations.RELATION_AFFINITY 同名的第二个包：寻址歧义，整包拒绝。
        val clone = ModManifest(
            packageId = "lw.clone",
            relationships = listOf(
                RelationshipDecl(RelationshipDeclarations.RELATION_AFFINITY, directed = true),
            ),
        )
        val exception = assertFailsWith<DeclarationConflictException> {
            freeze(listOf(RelationshipDeclarations.manifest, clone))
        }
        val message = exception.message ?: ""
        assertTrue(
            message.contains("lw.rel.affinity"),
            "冲突报告必须点名撞车的 kind：$message",
        )
        assertTrue(
            exception.conflicts.all { it.first.packageId != it.second.packageId },
            "冲突定位必须双边点名（谁先谁后）：${exception.conflicts}",
        )
    }

    @Test
    fun `反向证明 跨包同名字段声明事务整包拒绝`() {
        // 字段名是全局一张表（引擎按字段名寻址）：与 CharacterDeclarations.FIELD_WARMTH 同名即拒。
        val clone = ModManifest(
            packageId = "lw.clone",
            components = listOf(
                ComponentDecl(
                    "lw.clone.persona",
                    listOf(FieldDecl(CharacterDeclarations.FIELD_WARMTH, FieldType.INT)),
                ),
            ),
        )
        val exception = assertFailsWith<DeclarationConflictException> {
            freeze(listOf(CharacterDeclarations.manifest, clone))
        }
        assertTrue(
            (exception.message ?: "").contains(CharacterDeclarations.FIELD_WARMTH),
            "冲突报告必须点名撞车的字段：${exception.message}",
        )
    }

    // ------------------------------------------------------------------
    // 3. 声明真源自检（四包 manifest 与常量同源，漂移即红）
    // ------------------------------------------------------------------

    @Test
    fun `四包manifest与常量声明同源`() {
        val character = CharacterDeclarations.manifest
        assertEquals(CharacterDeclarations.PACKAGE_ID, character.packageId)
        assertEquals(
            listOf(CharacterDeclarations.COMPONENT_PERSONA, CharacterDeclarations.COMPONENT_SCHEDULE),
            character.components.map { it.name },
        )
        assertEquals(
            listOf(
                CharacterDeclarations.FIELD_WARMTH,
                CharacterDeclarations.FIELD_GRIT,
            ),
            character.components.first().fields.map { it.name },
        )
        assertEquals(
            listOf(
                CharacterDeclarations.FIELD_EVENING_SLOT,
                CharacterDeclarations.FIELD_ACTIVITY,
                CharacterDeclarations.FIELD_CRAFT_STAGE,
            ),
            character.components.last().fields.map { it.name },
        )
        assertEquals(
            listOf(CharacterDeclarations.ARCHETYPE_NPC),
            character.archetypes.map { it.name },
        )
        assertEquals(
            listOf(CharacterDeclarations.COMPONENT_PERSONA, CharacterDeclarations.COMPONENT_SCHEDULE),
            character.archetypes.single().components,
        )
        val job = character.jobs.single()
        assertEquals(CharacterDeclarations.JOB_DAY_TICK, job.jobId)
        assertEquals(0L, job.firstDay)
        assertEquals(1L, job.everyDays)

        val relationship = RelationshipDeclarations.manifest
        assertEquals(RelationshipDeclarations.PACKAGE_ID, relationship.packageId)
        val affinity = relationship.relationships.single()
        assertEquals(RelationshipDeclarations.RELATION_AFFINITY, affinity.name)
        assertTrue(affinity.directed, "双向独立 = 两条方向相反的有向边各自持值")
        assertTrue(!affinity.autoInverse, "反向是独立数据不是镜像，不建自动反向边")

        val memory = MemoryDeclarations.manifest
        assertEquals(MemoryDeclarations.PACKAGE_ID, memory.packageId)
        val subject = memory.components.single().fields.single { it.name == MemoryDeclarations.FIELD_SUBJECT }
        assertTrue(subject.isEntityRef, "subject 存 EntityId，跨设备重映射要按 schema 改写")
        val knows = memory.relationships.single()
        assertEquals(MemoryDeclarations.RELATION_KNOWS, knows.name)
        assertTrue(knows.directed)
        assertTrue(knows.cascadeOnDestroy, "fact 被删时 KNOWS 边随之清掉")

        val event = EventDeclarations.manifest
        assertEquals(EventDeclarations.PACKAGE_ID, event.packageId)
        assertEquals(
            listOf(EventDeclarations.RELATION_TENSION),
            event.relationships.map { it.name },
            "事件包只带自己的边型，与关系 Mod 的 affinity 互不覆盖（判据 4）",
        )
    }
}
