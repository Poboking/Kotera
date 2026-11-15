package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MR-1b 三面遍历完整性审计闸（测试侧，生产零改动）。
 *
 * 两个用途：
 * 1. 反向证明①的精确锚：面① / 面② / 面③ 各一个「单面恢复」用例，只断言本面。
 *    从 [ALL_THREE_FACES] 摘掉任一面（只改装置、不改核心）⇒ 恰是对应该面的用例红。
 * 2. 三面声明形状闸 + 取证：核心 [EntitySnapshot.remapped] 只覆盖载荷内两面 + 槽身份，
 *    第三面（Manifest entityRefs 变量槽）不在核心快照里（EntitySnapshot.kt:20 KDoc 归
 *    VM 变量层、生产侧尚未编排，§12.1）。本审计逐条钉住「三面缺一不可」。
 *
 * 位域 / 跨设备重映射是 RT2 自研语义，Emuera C# 无对应物 ⇒ TODO(verify) + 卡点
 * 「原版无 EntityId，权威 = 蓝图 §7 + §10」。
 */
class EntityIdentityRemapAuditTest {

    private val c = IdentityFixtureConstants

    private fun plan(): Pair<SourceDevice, ConflictPlan> {
        val src = buildSourceDeviceA()
        val p = planConflictRemap(src, conflictingImportRegistry())
        return src to p
    }

    // ------------------------------------------------------------------
    // 反向证明① 的精确锚：每面一个独立恢复用例
    // ------------------------------------------------------------------

    @Test
    fun `面①_组件EntityRef字段单独恢复`() {
        val (src, p) = plan()
        val remapped = src.device.remap(p.table) // 默认 ALL_THREE_FACES
        val got = remapped.entityRefFieldValue(c.FIELD_REF, EntityIdCodec.handleSlotOf(src.s1))
        assertEquals(p.table.remap(src.c1), got, "面① 组件 EntityRef 字段整体重映射")
        assertEquals(p.newClinic, EntityIdCodec.pkgShortIdOf(got))
        assertTrue(EntityIdCodec.isWellFormed(got))
    }

    @Test
    fun `面②_关系边端点单独恢复`() {
        val (src, p) = plan()
        val remapped = src.device.remap(p.table)
        val edge = remapped.snapshot.edges.single()
        assertEquals(p.table.remap(src.s1), edge.from, "面② 出边端点")
        assertEquals(p.table.remap(src.c1), edge.to, "面② 入边端点")
        assertEquals(c.EDGE_VALUE, edge.value, "面② 边值不是引用，原样")
    }

    @Test
    fun `面③_Manifest变量槽单独恢复`() {
        val (src, p) = plan()
        val remapped = src.device.remap(p.table)
        val vSchool = remapped.variableSlots[c.VAR_SCHOOL]!!
        val vClinic = remapped.variableSlots[c.VAR_CLINIC]!!
        assertEquals(p.table.remap(src.s1), vSchool, "面③ school 包 entityRefs 槽")
        assertEquals(p.table.remap(src.c1), vClinic, "面③ clinic 包 entityRefs 槽")
        assertEquals(p.newSchool, EntityIdCodec.pkgShortIdOf(vSchool))
        assertEquals(p.newClinic, EntityIdCodec.pkgShortIdOf(vClinic))
    }

    // ------------------------------------------------------------------
    // 声明形状闸：三面名单来自声明，未声明槽不在名单里
    // ------------------------------------------------------------------

    @Test
    fun `三面声明形状闸_EntityRef与entityRefs键集合`() {
        val src = buildSourceDeviceA()
        // 面① 的声明键来自 ComponentSchema.normalizedEntityRefFieldKeys（仅显式声明字段）。
        assertEquals(setOf(c.FIELD_REF.uppercase()), src.device.componentEntityRefKeys)
        // 面③ 的声明键来自 ModuleDescriptor.normalizedEntityRefKeys（两包各一个）。
        assertEquals(
            setOf(c.VAR_SCHOOL.uppercase(), c.VAR_CLINIC.uppercase()),
            src.device.declaredRefKeys,
        )
        // 载荷里 isEntityRef 的字段名集合恰等于面①声明（schema 声明与载荷旗标对齐）。
        val refFieldNames = src.device.snapshot.fields
            .filter { it.isEntityRef }
            .map { it.name.uppercase() }
            .toSet()
        assertEquals(src.device.componentEntityRefKeys, refFieldNames)
        // 未声明槽不在任何名单里（诚实边界的形状证据）。
        assertFalse(c.VAR_RAW.uppercase() in src.device.declaredRefKeys)
        assertFalse(c.FIELD_UNDECLARED_HOLDER.uppercase() in src.device.componentEntityRefKeys)
        assertFalse(c.FIELD_ORDINARY.uppercase() in src.device.componentEntityRefKeys)
        // 三面数据齐备：组件字段有值、边恰一条、变量槽两包各一个。
        assertEquals(1, src.device.snapshot.edges.size)
        assertEquals(2, src.device.variableSlots.size)
    }

    // ------------------------------------------------------------------
    // 取证：核心 remapped 只覆盖两面 + 槽身份；第三面必须由上层补
    // ------------------------------------------------------------------

    @Test
    fun `核心remapped覆盖两面加槽身份_第三面变量槽须由上层补`() {
        val (src, p) = plan()
        val table = p.table

        // 核心 EntitySnapshot.remapped：只改 slots / isEntityRef 字段 / 边端点。
        val core = src.device.snapshot.remapped(table)
        val slotS1 = EntityIdCodec.handleSlotOf(src.s1)
        val coreField = core.fields.first { it.isEntityRef }
            .intValues.first { it.slot == slotS1 }.value
        assertEquals(table.remap(src.c1), coreField, "核心覆盖了面①")
        assertEquals(table.remap(src.s1), core.edges.single().from, "核心覆盖了面② 出边端点")
        assertEquals(table.remap(src.c1), core.edges.single().to, "核心覆盖了面② 入边端点")
        for (s in core.slots) {
            assertTrue(s.pkgShortId == p.newSchool || s.pkgShortId == p.newClinic, "核心覆盖了槽身份")
        }

        // 核心 remapped 的入参只有 EntitySnapshot + table，产出物 [core] 里没有变量槽这个面
        // （EntitySnapshot 构造参数只有 slots / archetypes / fields / relationKinds / edges）。
        // 第三面是装置 / VM 变量层的独立存储，核心方法在签名层面就触不到它：
        // 仅调用核心动作后，装置变量槽仍是旧 ID（pkg 段旧号）⇒ 引用名单不完整。
        val beforeThird = src.device.variableSlots[c.VAR_SCHOOL]!!
        // [core] 是上面已算出的核心 remapped 产出：载荷两面 + 槽身份已换，但不触碰装置变量槽。
        assertEquals(table.remap(src.s1), core.edges.single().from, "核心动作确实发生了")
        assertEquals(beforeThird, src.device.variableSlots[c.VAR_SCHOOL]!!, "核心动作不改装置变量槽")
        assertEquals(
            src.oldSchoolShortId, EntityIdCodec.pkgShortIdOf(beforeThird),
            "只靠核心 remapped 时第三面 pkg 段仍是旧号",
        )

        // 装置补上面③后，变量槽才换到新号 —— 证明三面缺一不可。
        val withThirdFace = src.device.remap(table)
        assertEquals(
            p.newSchool,
            EntityIdCodec.pkgShortIdOf(withThirdFace.variableSlots[c.VAR_SCHOOL]!!),
            "装置补齐第三面后变量槽才到新号",
        )
    }
}
