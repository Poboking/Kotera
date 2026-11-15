package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 组件 EntityRef 字段的完整生命周期 / 删除契约回归闸（蓝图 §7 / §19.4 增量1，v6.5-c）。
 *
 * GPT 家族审计跟进裁定：增量1 此前只有原则（「destroy 不清组件 EntityRef 字段」），
 * 没有状态转换 / 异常路径 / 回归测试。本闸对照**已落**的 [EntityKernel] 把契约咬死，
 * 把「原则」推进到「已单元验证」（§12.1 五态口径的第 3 态）。
 *
 * 契约核心（实树锚 EntityKernel.kt:192-207 destroy / :415-436 removeEdgesOf）：
 * - **KEEP_INVALID 是 v1 唯一删除策略**：目标 destroy 后，引用它的组件 EntityRef 字段
 *   **原值保留**（引擎不 cascade-null）、目标 generation 失效 ⇒ `exists` 判否；
 * - **删除契约分歧**：同一次 destroy，关系边级联清除（removeEdgesOf 全 kind），
 *   组件 EntityRef 字段不动 —— 二者删除语义不同，Mod 自选承载面；
 * - **槽复用不复活旧引用**：旧字段值仍指旧 generation 的 id，引擎不重写到新实体（ABA 防护）；
 * - **Retire ≠ 清理**：RETIRED/INACTIVE 只是状态旗标（setState），数据 / 边 / 引用全保留；
 * - **无参照完整性闸**：写入不校验引用是否存活（不猜值），Mod 用 `ENTITY_EXISTS` 自检。
 *
 * 位域 / generation / EntityRef 是 RT2 自研语义，Emuera C# 无对应物 ⇒ 权威 = 蓝图 §7 + §10，
 * 非 C# 锚（TODO(verify) 口径同 EntityIdentityRemapAuditTest）。
 */
class EntityRefLifecycleContractTest {

    private val lineageSchema = ComponentSchema(
        "pkg", "Lineage",
        listOf(
            ComponentFieldSpec("parent", ComponentFieldType.INT, isEntityRef = true),
            ComponentFieldSpec("ordinary", ComponentFieldType.INT, isEntityRef = false),
        ),
    )

    private fun kernel(initialCapacity: Int = 8): EntityKernel = EntityKernel(
        schemas = listOf(
            lineageSchema,
            ComponentSchema("pkg", "Profile", listOf(ComponentFieldSpec("level"))),
        ),
        relationshipSchemas = listOf(RelationshipSchema("PARENT")),
        archetypes = listOf(EntityArchetype("PERSON", listOf("Lineage", "Profile"))),
        initialCapacity = initialCapacity,
    )

    // ------------------------------------------------------------------
    // 删除契约分歧：边级联清，组件 EntityRef 字段 KEEP_INVALID
    // ------------------------------------------------------------------

    @Test
    fun `destroy目标_组件EntityRef字段悬空保留而关系边级联清除`() {
        val k = kernel()
        val parent = k.create(1, "PERSON")
        val child = k.create(1, "PERSON")
        assertTrue(k.setInt(child, "parent", parent))
        assertTrue(k.addRelation(child, "PARENT", parent))

        assertTrue(k.destroy(parent))

        // 身份失效：目标不存在，存进去的引用也随之悬空。
        assertFalse(k.exists(parent))
        assertFalse(k.exists(k.getInt(child, "parent")))
        // KEEP_INVALID：字段原值逐字保留，引擎不 cascade-null（不全表扫描清零）。
        assertEquals(parent, k.getInt(child, "parent"))
        // 删除契约分歧：同一次 destroy，关系边被级联清除。
        assertFalse(k.hasRelation(child, "PARENT", parent))
        assertContentEquals(LongArray(0), k.queryRelations(child, "PARENT", outgoing = true))
        // 引用者本身完好：destroy 目标不动引用者的其它数据。
        assertTrue(k.exists(child))
        assertEquals(EntityLifecycle.ACTIVE, k.stateOf(child))
    }

    @Test
    fun `多引用者_目标销毁后全部字段悬空保留全部边清除`() {
        val k = kernel()
        val parent = k.create(1, "PERSON")
        val children = LongArray(3) { k.create(1, "PERSON") }
        for (c in children) {
            assertTrue(k.setInt(c, "parent", parent))
            assertTrue(k.addRelation(c, "PARENT", parent))
        }

        assertTrue(k.destroy(parent))

        // 不全表扫描清零：每个引用者的字段都原值保留（悬空），每条边都清除。
        for (c in children) {
            assertEquals(parent, k.getInt(c, "parent"))
            assertFalse(k.exists(k.getInt(c, "parent")))
            assertFalse(k.hasRelation(c, "PARENT", parent))
            assertContentEquals(LongArray(0), k.queryRelations(c, "PARENT", outgoing = true))
            assertTrue(k.exists(c))
        }
    }

    // ------------------------------------------------------------------
    // 槽复用：generation ABA 防护，旧引用不复活不重写
    // ------------------------------------------------------------------

    @Test
    fun `槽复用_旧EntityRef字段不复活不重写到新实体`() {
        val k = kernel(initialCapacity = 4)
        val parent = k.create(1, "PERSON")
        val child = k.create(1, "PERSON")
        assertTrue(k.setInt(child, "parent", parent))
        assertTrue(k.destroy(parent))

        // 复用同槽的新实体：新 generation，新 EntityId。
        val reused = k.create(1, "PERSON")
        assertEquals(EntityIdCodec.handleSlotOf(parent), EntityIdCodec.handleSlotOf(reused))
        assertNotEquals(parent, reused)
        assertEquals(EntityIdCodec.generationOf(parent) + 1, EntityIdCodec.generationOf(reused))
        assertTrue(k.exists(reused))

        // 旧字段值仍指旧 id（引擎不重写到新实体），旧 id 仍判不存在（generation 失配）。
        assertEquals(parent, k.getInt(child, "parent"))
        assertFalse(k.exists(k.getInt(child, "parent")))
        assertNotEquals(reused, k.getInt(child, "parent"))
    }

    // ------------------------------------------------------------------
    // Retire ≠ 清理：RETIRED / INACTIVE 引用与边全保留
    // ------------------------------------------------------------------

    @Test
    fun `RETIRED与INACTIVE目标_引用与关系边仍有效`() {
        val k = kernel()
        val parent = k.create(1, "PERSON")
        val child = k.create(1, "PERSON")
        assertTrue(k.setInt(child, "parent", parent))
        assertTrue(k.addRelation(child, "PARENT", parent))

        // RETIRED：归档意图，引擎侧待遇 = 退出轮询，但身份 / 数据 / 边 / 引用全保留。
        assertTrue(k.setState(parent, EntityLifecycle.RETIRED))
        assertTrue(k.exists(parent))
        assertEquals(parent, k.getInt(child, "parent"))
        assertTrue(k.exists(k.getInt(child, "parent")))
        assertTrue(k.hasRelation(child, "PARENT", parent))

        // INACTIVE：暂时休眠，同样不清理。
        assertTrue(k.setState(parent, EntityLifecycle.INACTIVE))
        assertTrue(k.exists(parent))
        assertEquals(parent, k.getInt(child, "parent"))
        assertTrue(k.hasRelation(child, "PARENT", parent))

        // 只有 destroy 才让引用悬空（与 setState 的删除契约根本不同）。
        assertTrue(k.destroy(parent))
        assertFalse(k.exists(parent))
        assertFalse(k.exists(k.getInt(child, "parent")))
        assertFalse(k.hasRelation(child, "PARENT", parent))
    }

    // ------------------------------------------------------------------
    // 异常路径：写入不校验引用存活（无参照完整性闸，不猜值）
    // ------------------------------------------------------------------

    @Test
    fun `写入不校验引用存活_悬空与NULL引用均可存储`() {
        val k = kernel()
        val child = k.create(1, "PERSON")
        // 良构但不存在的 id（槽越界）：写入照样成功，引擎不做参照完整性校验。
        val ghost = EntityIdCodec.encode(1, 0, 9999)
        assertTrue(EntityIdCodec.isWellFormed(ghost))
        assertFalse(k.exists(ghost))
        assertTrue(k.setInt(child, "parent", ghost))
        assertEquals(ghost, k.getInt(child, "parent"))
        // NULL 引用（0L）也只是普通 Long，可存储；存在性自检是 Mod 契约义务。
        assertTrue(k.setInt(child, "parent", EntityIdCodec.NULL))
        assertEquals(EntityIdCodec.NULL, k.getInt(child, "parent"))
        assertFalse(k.exists(k.getInt(child, "parent")))
    }

    @Test
    fun `destroy幂等负控_NULL与已销毁返回false且引用者字段不受影响`() {
        val k = kernel()
        val parent = k.create(1, "PERSON")
        val child = k.create(1, "PERSON")
        assertTrue(k.setInt(child, "parent", parent))
        assertTrue(k.destroy(parent))
        val fieldAfter = k.getInt(child, "parent")
        // 重复 destroy / destroy NULL：返回 false，不再动任何字段。
        assertFalse(k.destroy(parent))
        assertFalse(k.destroy(EntityIdCodec.NULL))
        assertEquals(fieldAfter, k.getInt(child, "parent"))
        assertEquals(parent, k.getInt(child, "parent"))
    }

    // ------------------------------------------------------------------
    // 声明形状：删除待遇不区分 isEntityRef，区分只在重映射面
    // ------------------------------------------------------------------

    @Test
    fun `普通Long字段删除待遇与EntityRef相同_仅重映射面区分`() {
        val k = kernel()
        val parent = k.create(1, "PERSON")
        val child = k.create(1, "PERSON")
        assertTrue(k.setInt(child, "parent", parent))
        assertTrue(k.setInt(child, "ordinary", parent))
        assertTrue(k.destroy(parent))
        // destroy 不扫描组件字段：声明 EntityRef 与否，删除待遇相同（都原值保留）。
        assertEquals(parent, k.getInt(child, "parent"))
        assertEquals(parent, k.getInt(child, "ordinary"))
        // 区分只在跨设备重映射遍历面：只有显式声明的字段进 EntityRef 键集合。
        assertEquals(setOf("PARENT"), lineageSchema.normalizedEntityRefFieldKeys())
        assertFalse("ORDINARY" in lineageSchema.normalizedEntityRefFieldKeys())
    }
}