package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RT2 波 D 车 D3：跨设备身份重映射可执行原型装置 + 负控（CONTRACT-RT2-D3 §2）。
 *
 * 用真实核心组件（[EntityIdRemapTable] / [PackageShortIdRegistry] / [EntityKernel] /
 * [EntitySnapshot] / ModuleDescriptor.entityRefs / ComponentSchema 的 EntityRef 字段 /
 * 关系边）证明蓝图 §7 跨设备迁移协议的三条负控：
 *
 * 1. Mod A/B 各存实体引用（三处齐全）→ 导入侧 shortId 冲突 → 分配新 shortId →
 *    整体重映射 → 全部已声明引用正确恢复（含重映射快照灌回真内核的恢复面）；
 * 2. 未声明 entity-ref 的普通 Long 里的旧 ID 原样不动（引擎不猜值、不乱改）；
 * 3. 导入遇到重复 EntityId ⇒ 拒绝并报告，不静默重分配；
 * 4. （可选）32767 耗尽策略 = 拒绝新包注册 + 诊断，不静默截断。
 *
 * 原型允许 fake 宿主桥，核心组件必须是真的：本装置的存储/编解码/重映射/恢复全部
 * 走真类（EntityKernel / PackageShortIdRegistry / EntityIdRemapTable / EntitySnapshot），
 * 三面遍历编排与重复导入检测是测试侧装置职责（EntityIdRemapTable.kt:18-19 KDoc）。
 * 断言期望值全部写死（身份常量见 CrossDeviceRemapConstants，pkg 段 4/5 等段值直书）。
 *
 * 位域 / 跨设备重映射是 RT2 自研语义，Emuera C# 无对应物 ⇒ 权威 = 蓝图 §7 + §10，
 * TODO(verify)：不拿 C# 里任何 Long 变量的处理当 EntityId 的锚。
 */
class CrossDeviceRemapPrototypeTest {

    private val c = CrossDeviceRemapConstants

    // ------------------------------------------------------------------
    // 负控 1：三面齐全 → 冲突 → 新 shortId → 整体重映射 → 全部声明引用恢复
    // ------------------------------------------------------------------

    @Test
    fun `负控1_三面齐全冲突导入整体重映射全部声明引用恢复`() {
        val src = buildTavernGuildSource()
        val importReg = crossDeviceConflictingImportRegistry()
        val plan = planConflictRemap(src, importReg)
        // 导入侧分配写死：2/3 已被占，单调分配必得 4/5。
        assertEquals(4, plan.newTavern, "冲突包 tavern 的新 shortId 写死 = 4")
        assertEquals(5, plan.newGuild, "冲突包 guild 的新 shortId 写死 = 5")
        assertEquals(2, src.device.registry.shortIdOf(c.TAVERN), "源注册表短 ID 不被本次导入改写（仍是旧号 2）")
        assertEquals(3, src.device.registry.shortIdOf(c.GUILD), "源注册表短 ID 不被本次导入改写（仍是旧号 3）")
        assertEquals(4, importReg.shortIdOf(c.TAVERN), "导入侧注册表已登记 tavern = 4")
        assertEquals(5, importReg.shortIdOf(c.GUILD), "导入侧注册表已登记 guild = 5")
        val remapped = src.device.remap(plan.table)

        // 槽身份（实体自身归属包）：pkg 段全部换到新号 4/5（EntitySnapshot.kt:20-24）。
        for (s in remapped.snapshot.slots) {
            assertTrue(s.pkgShortId == 4 || s.pkgShortId == 5, "槽身份 pkg 段 ∈ 写死新号 4/5")
        }

        // 面① 组件 EntityRef 字段：member.patronId 存 patron → 逐位等于写死新 id。
        val slotMember = EntityIdCodec.handleSlotOf(c.SRC_MEMBER)
        val refVal = remapped.entityRefFieldValue(c.FIELD_REF, slotMember)
        assertEquals(c.NEW_PATRON, refVal, "面① 字段值逐位 == encode(5,0,0,槽1) 写死常量")
        assertEquals(5, EntityIdCodec.pkgShortIdOf(refVal), "面① pkg 段 == 写死 5")
        assertEquals(0, EntityIdCodec.generationOf(refVal), "面① generation 不变（写死 0）")
        assertEquals(1, EntityIdCodec.handleSlotOf(refVal), "面① handleSlot 不变（写死槽 1）")
        assertTrue(EntityIdCodec.isWellFormed(refVal))

        // 面② 关系边两端点：from=member→新号、to=patron→新号；边值不是引用，逐位不变。
        val edge = remapped.snapshot.edges.single()
        assertEquals(c.NEW_MEMBER, edge.from, "面② 出边端点 == encode(4,0,槽0) 写死常量")
        assertEquals(c.NEW_PATRON, edge.to, "面② 入边端点 == encode(5,0,槽1) 写死常量")
        assertEquals(4, EntityIdCodec.pkgShortIdOf(edge.from), "面② from pkg 段 == 写死 4")
        assertEquals(5, EntityIdCodec.pkgShortIdOf(edge.to), "面② to pkg 段 == 写死 5")
        assertEquals(c.EDGE_VALUE, edge.value, "面② 边值 777 不是引用，逐位不变")

        // 面③ Manifest entityRefs 变量槽：两包各一个，逐位等于写死新 id。
        val vTavern = remapped.variableSlots[c.VAR_TAVERN]!!
        val vGuild = remapped.variableSlots[c.VAR_GUILD]!!
        assertEquals(c.NEW_MEMBER, vTavern, "面③ tavern 变量槽恢复")
        assertEquals(c.NEW_PATRON, vGuild, "面③ guild 变量槽恢复")

        // 恢复面：重映射快照灌回同声明的空真内核（EntityKernel.restore 预校验先于写入）。
        val dst = c.newKernel()
        dst.restore(remapped.snapshot)
        assertTrue(dst.exists(c.NEW_MEMBER), "新 id（pkg=4）在导入侧内核存活")
        assertTrue(dst.exists(c.NEW_PATRON), "新 id（pkg=5）在导入侧内核存活")
        assertFalse(dst.exists(c.SRC_MEMBER), "旧 id（pkg=2）在导入侧内核不存在")
        assertEquals(c.NEW_PATRON, dst.getInt(c.NEW_MEMBER, c.FIELD_REF), "真内核恢复面① 引用值不变")
        assertEquals(777L, dst.getRelationValue(c.NEW_MEMBER, c.RELATION, c.NEW_PATRON), "真内核恢复边值不变")
        assertTrue(
            dst.queryRelations(c.NEW_MEMBER, c.RELATION, outgoing = true)
                .contentEquals(longArrayOf(c.NEW_PATRON)),
            "真内核恢复边端点指向新号",
        )
        assertEquals(c.PLAIN_VALUE, dst.getInt(c.NEW_MEMBER, c.FIELD_PLAIN), "普通整数字段不受重映射影响")
        assertEquals(c.NOTE_VALUE, dst.getStr(c.NEW_MEMBER, c.FIELD_NOTE), "STR 字段不受重映射影响")
    }

    // ------------------------------------------------------------------
    // 负控 2：未声明 entity-ref 的普通 Long 里的旧 ID 原样不动
    // ------------------------------------------------------------------

    @Test
    fun `负控2_未声明Long旧ID原样不动`() {
        val src = buildTavernGuildSource()
        val plan = planConflictRemap(src, crossDeviceConflictingImportRegistry())
        val remapped = src.device.remap(plan.table)
        val slotMember = EntityIdCodec.handleSlotOf(c.SRC_MEMBER)

        // 未声明 INT 组件字段（memoId）存了旧 EntityId：逐位不变，pkg 段仍是旧号 3。
        val holder = remapped.ordinaryFieldValue(c.FIELD_UNDECLARED, slotMember)
        assertEquals(c.SRC_PATRON, holder, "未声明组件字段里的旧 EntityId 原样不动（逐位）")
        assertEquals(3, EntityIdCodec.pkgShortIdOf(holder), "pkg 段仍是旧值，证明没被扫描猜值")

        // 未声明裸 Long 变量槽（SCRATCH_ID）：原样不动。
        val raw = remapped.rawLongs[c.VAR_RAW]!!
        assertEquals(c.SRC_PATRON, raw, "未声明裸 Long 变量槽里的旧 EntityId 原样不动（逐位）")
        assertEquals(3, EntityIdCodec.pkgShortIdOf(raw), "裸 Long 的 pkg 段仍是旧值")

        // 普通整数字段（rank=88）也不误伤。
        assertEquals(c.PLAIN_VALUE, remapped.ordinaryFieldValue(c.FIELD_PLAIN, slotMember), "普通整数不被改写")

        // 形状证据：未声明键不在任何声明名单里（遍历面按声明走，不是按数值猜）。
        assertFalse(c.VAR_RAW.uppercase() in remapped.declaredVarRefKeys, "裸 Long 槽不在 Manifest entityRefs 名单")
        assertFalse(c.FIELD_UNDECLARED.uppercase() in remapped.declaredComponentRefKeys, "未声明字段不在 EntityRef 名单")

        // 对照：同一次导入里，声明过的引用照换（声明面与未声明面的分界是声明本身）。
        assertEquals(c.NEW_PATRON, remapped.entityRefFieldValue(c.FIELD_REF, slotMember), "声明引用已到新号")
    }

    // ------------------------------------------------------------------
    // 负控 3：导入遇到重复 EntityId ⇒ 拒绝并报告，不静默重分配
    // ------------------------------------------------------------------

    @Test
    fun `负控3_重复EntityId导入拒绝并报告`() {
        val src = buildTavernGuildSource()
        val overlapping = overlappingBranchReproducing(c.SRC_MEMBER)

        val ex = assertFailsWith<CrossDeviceDuplicateEntityIdException> {
            importBranches(src.device, overlapping)
        }
        assertTrue(c.SRC_MEMBER in ex.duplicates, "报告含重复的 EntityId（写死 encode(2,0,槽0)）")
        assertTrue("重复 EntityId" in ex.message!!, "诊断文案点名重复 EntityId")
        assertTrue("拒绝" in ex.message!!, "诊断文案点名拒绝（不静默）")

        // 正向对照：两分支无重叠 ⇒ 正常导入，不误拒（不是一律拒绝）。
        val disjoint = disjointBranch()
        val merged = importBranches(src.device, disjoint)
        assertTrue(merged.containsAll(src.device.collectedEntityIds()), "无重叠分支 A 侧全量并入")
        assertTrue(merged.containsAll(disjoint.collectedEntityIds()), "无重叠分支 B 侧全量并入")
        assertTrue(
            src.device.collectedEntityIds().intersect(disjoint.collectedEntityIds()).isEmpty(),
            "对照前提：两分支引用集合确无交集",
        )
    }

    // ------------------------------------------------------------------
    // 负控 4（可选）：32767 耗尽 = 拒绝新包注册 + 诊断，不静默截断
    // ------------------------------------------------------------------

    @Test
    fun `负控4_32767耗尽拒绝新包注册并诊断`() {
        val reg = PackageShortIdRegistry()
        var last = 0
        for (i in 2..EntityIdCodec.MAX_PKG_SHORT_ID) {
            last = reg.allocate("bulk.pkg.$i")
        }
        assertEquals(32767, last, "单调分配顶格写死 = 32767")
        val ex = assertFailsWith<IllegalStateException> { reg.allocate("one.more") }
        assertTrue("耗尽" in ex.message!!, "诊断文案含容量耗尽")
        // 不静默截断：顶格号仍归原包、越界新包从未写入。
        assertEquals(32767, reg.shortIdOf("bulk.pkg.32767"), "顶格号仍归原包")
        assertNull(reg.shortIdOf("one.more"), "越界新包未被静默写入")
    }
}
