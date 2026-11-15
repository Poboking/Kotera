package com.eratw.port.core.entity

import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MR-1b 身份原型装置（ABI-1 身份 Gate 的原型半边）：蓝图 §7:156「持久实体引用完整名单
 * = 三面，缺一不可」+ §10 ABI-1 Gate + §11 MR-1 行。
 *
 * 链路：创建 → Mod A/B 各存实体引用 → 跨设备导入触发 shortId 冲突 → 导入侧给冲突包
 * 分配新 shortId 并整体重映射 → 存读档 → 逐面对拍恢复。
 *
 * 三面（缺一不可）：① 组件载荷中 ComponentSchema 声明为 EntityRef 的字段、② 全部关系边
 * 端点、③ 各 Package Manifest 显式声明的 entityRefs 变量槽。装置 = 测试侧的「导入/装载
 * 相位三面遍历编排器」，生产代码零改动；核心只提供内层动作 [EntityIdRemapTable.remap]。
 *
 * 位域 / 跨设备重映射是 RT2 自研语义，Emuera C# 无对应物 ⇒ 语义结论按契约写
 * TODO(verify) + 卡点「原版无 EntityId，权威 = 蓝图 §7 + §10」，不拿 C# 里某个 Long
 * 变量的处理当 EntityId 的锚。
 *
 * 本 lane 过 = ABI-1 **身份原型**半边；ABI-1 仍不得冻结（还差 MR-2b-2 真装置 +
 * 生产接线），**0/8 Frozen 不变**。
 */
class EntityIdentityPrototypeTest {

    private val c = IdentityFixtureConstants
    private val binding = SidecarBinding(uniqueCode = 0x1234_5678L, scriptVersion = 7L, saveTextHash = 99)

    // ------------------------------------------------------------------
    // 判据 1：三面全恢复（§3.2.1）
    // ------------------------------------------------------------------

    @Test
    fun `判据1_三面全恢复_crossDevice冲突导入整体重映射`() {
        val src = buildSourceDeviceA()
        val plan = planConflictRemap(src, conflictingImportRegistry())
        val remapped = src.device.remap(plan.table)

        val s1New = plan.table.remap(src.s1)
        val c1New = plan.table.remap(src.c1)
        val slotS1 = EntityIdCodec.handleSlotOf(src.s1)

        // 面① 组件 EntityRef 字段：s1.homeroomTeacherId 存 c1 → 重映射后指向新号。
        val refVal = remapped.entityRefFieldValue(c.FIELD_REF, slotS1)
        assertEquals(c1New, refVal, "面① 组件 EntityRef 字段逐项恢复")
        assertEquals(plan.newClinic, EntityIdCodec.pkgShortIdOf(refVal), "面① pkg 段 == 新分配值")
        assertTrue(EntityIdCodec.isWellFormed(refVal))
        assertNotEquals(EntityIdCodec.NULL, refVal)

        // 面② 关系边两个端点：from=s1→新号, to=c1→新号；边值不是引用，原样。
        val edge = remapped.snapshot.edges.single()
        assertEquals(s1New, edge.from, "面② 出边端点恢复")
        assertEquals(c1New, edge.to, "面② 入边端点恢复")
        assertEquals(plan.newSchool, EntityIdCodec.pkgShortIdOf(edge.from))
        assertEquals(plan.newClinic, EntityIdCodec.pkgShortIdOf(edge.to))
        assertTrue(EntityIdCodec.isWellFormed(edge.from) && EntityIdCodec.isWellFormed(edge.to))
        assertEquals(c.EDGE_VALUE, edge.value, "面② 边值不是引用，逐位不变")

        // 面③ Manifest entityRefs 变量槽：两包各一个。
        val vSchool = remapped.variableSlots[c.VAR_SCHOOL]!!
        val vClinic = remapped.variableSlots[c.VAR_CLINIC]!!
        assertEquals(s1New, vSchool, "面③ school 变量槽恢复")
        assertEquals(c1New, vClinic, "面③ clinic 变量槽恢复")
        assertEquals(plan.newSchool, EntityIdCodec.pkgShortIdOf(vSchool))
        assertEquals(plan.newClinic, EntityIdCodec.pkgShortIdOf(vClinic))
        assertTrue(EntityIdCodec.isWellFormed(vSchool) && EntityIdCodec.isWellFormed(vClinic))

        // 槽身份：实体自身归属包也换了（否则整图不自洽）——不属于引用面但身份必备。
        for (s in remapped.snapshot.slots) {
            assertTrue(
                s.pkgShortId == plan.newSchool || s.pkgShortId == plan.newClinic,
                "槽身份 pkg 段 == 某冲突包的新分配值",
            )
        }

        // 综合：每个引用良构、非 NULL，pkg 段等于新分配值。
        val allRefs = listOf(refVal, edge.from, edge.to, vSchool, vClinic)
        for (r in allRefs) {
            assertTrue(EntityIdCodec.isWellFormed(r))
            assertNotEquals(EntityIdCodec.NULL, r)
            assertTrue(
                EntityIdCodec.pkgShortIdOf(r) == plan.newSchool ||
                    EntityIdCodec.pkgShortIdOf(r) == plan.newClinic,
            )
        }
        // 重映射只换 pkg 段：generation 与 handleSlot 不变。
        assertEquals(EntityIdCodec.generationOf(src.c1), EntityIdCodec.generationOf(refVal))
        assertEquals(EntityIdCodec.handleSlotOf(src.c1), EntityIdCodec.handleSlotOf(refVal))
    }

    // ------------------------------------------------------------------
    // 判据 2：未声明裸 Long 不猜值（负控，§3.2.2）
    // ------------------------------------------------------------------

    @Test
    fun `判据2_未声明Long不猜值负控`() {
        val src = buildSourceDeviceA()
        val plan = planConflictRemap(src, conflictingImportRegistry())
        val remapped = src.device.remap(plan.table)
        val slotS1 = EntityIdCodec.handleSlotOf(src.s1)

        // 未声明 INT 组件字段（legacyHolder）存了旧 EntityId：原样不动，逐位不变。
        val holder = remapped.ordinaryFieldValue(c.FIELD_UNDECLARED_HOLDER, slotS1)
        assertEquals(src.c1, holder, "未声明组件字段里的旧 EntityId 原样不动")
        assertEquals(
            EntityIdCodec.pkgShortIdOf(src.c1), EntityIdCodec.pkgShortIdOf(holder),
            "pkg 段仍是旧值，证明没被扫描猜值",
        )

        // 未声明裸 Long 变量槽（RAW_OLD_ID）：原样不动。
        val raw = remapped.rawLongs[c.VAR_RAW]!!
        assertEquals(src.c1, raw, "未声明裸 Long 变量槽里的旧 EntityId 原样不动")
        assertEquals(EntityIdCodec.pkgShortIdOf(src.c1), EntityIdCodec.pkgShortIdOf(raw))

        // 普通整数字段（score=88）也不误伤。
        assertEquals(c.ORDINARY_VALUE, remapped.ordinaryFieldValue(c.FIELD_ORDINARY, slotS1))
        // 装置没有把未声明槽纳入声明键集合（形状证据）。
        assertFalse(c.VAR_RAW.uppercase() in remapped.declaredRefKeys)
        assertFalse(c.FIELD_UNDECLARED_HOLDER.uppercase() in remapped.componentEntityRefKeys)
    }

    // ------------------------------------------------------------------
    // 判据 3：重复 EntityId 导入拒绝（负控，§3.2.3）
    // ------------------------------------------------------------------

    @Test
    fun `判据3_重复EntityId导入拒绝负控`() {
        val src = buildSourceDeviceA()
        val overlapping = independentBranchWithOverlap(src.s1)

        val ex = assertFailsWith<DuplicateEntityIdImportException> {
            importBranches(src.device, overlapping)
        }
        assertTrue(src.s1 in ex.duplicates, "报告含重复 EntityId")
        assertTrue("重复 EntityId" in ex.message!!, ex.message)

        // 正向对照：两分支无重叠 ⇒ 正常导入，不误拒（不是一律拒绝）。
        val disjoint = independentBranchDisjoint()
        val merged = importBranches(src.device, disjoint)
        assertTrue(merged.containsAll(src.device.allEntityIds()))
        assertTrue(merged.containsAll(disjoint.allEntityIds()))
        assertTrue(src.device.allEntityIds().intersect(disjoint.allEntityIds()).isEmpty())
    }

    // ------------------------------------------------------------------
    // 判据 4：generation 退休 / 销毁复用负控（§3.2.4）
    // ------------------------------------------------------------------

    @Test
    fun `判据4_generation退休与销毁复用负控`() {
        // 正常复用：generation +1、旧 id 判失效、槽复用列残值清零。
        val k = c.newKernel()
        val a = k.create(2, c.ARCHETYPE)
        assertTrue(k.setInt(a, c.FIELD_ORDINARY, 42L))
        assertTrue(k.setStr(a, c.FIELD_STR, "旧值"))
        assertTrue(k.destroy(a))
        assertFalse(k.exists(a), "旧 id 经 resolveAlive 判失效")
        val b = k.create(2, c.ARCHETYPE)
        assertEquals(EntityIdCodec.handleSlotOf(a), EntityIdCodec.handleSlotOf(b), "同槽复用")
        assertEquals(EntityIdCodec.generationOf(a) + 1, EntityIdCodec.generationOf(b))
        assertEquals(0L, k.getInt(b, c.FIELD_ORDINARY), "槽复用列残值清零（int）")
        assertEquals("", k.getStr(b, c.FIELD_STR), "槽复用列残值清零（str）")

        // 顶格（65535 次复用）句柄槽永久退休不可回收。
        val k2 = c.newKernel()
        var id = k2.create(2, c.ARCHETYPE)
        val slot = EntityIdCodec.handleSlotOf(id)
        repeat(EntityIdCodec.MAX_GENERATION) {
            assertTrue(k2.destroy(id))
            id = k2.create(2, c.ARCHETYPE)
        }
        assertEquals(EntityIdCodec.MAX_GENERATION, EntityIdCodec.generationOf(id))
        assertEquals(slot, EntityIdCodec.handleSlotOf(id))
        val oldTopId = id
        assertTrue(k2.destroy(oldTopId)) // 第 0xFFFF 次 destroy ⇒ 槽退休
        val next = k2.create(2, c.ARCHETYPE)
        assertNotEquals(slot, EntityIdCodec.handleSlotOf(next), "顶格槽永不复用")
        assertEquals(0, EntityIdCodec.generationOf(next))
        assertFalse(k2.exists(oldTopId), "退休槽上的旧 id 仍判失效")
    }

    // ------------------------------------------------------------------
    // 判据 5：映射单射 / 良构 / 越域拒绝，只换 pkg 段（§3.2.5）
    // ------------------------------------------------------------------

    @Test
    fun `判据5_映射单射良构越域拒绝且只换pkg段`() {
        // 非单射（两旧 → 一新 = 合并两个分支，v1 不支持）。
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(2 to 9, 3 to 9)) }
        // 越域：源 / 目标越 1..32767；内核包 1 号不参与。
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(0 to 9)) }
        assertFailsWith<IllegalArgumentException> {
            EntityIdRemapTable.of(mapOf(EntityIdCodec.MAX_PKG_SHORT_ID + 1 to 9))
        }
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(2 to 0)) }
        assertFailsWith<IllegalArgumentException> {
            EntityIdRemapTable.of(mapOf(2 to EntityIdCodec.MAX_PKG_SHORT_ID + 1))
        }
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(1 to 9)) }
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(2 to 1)) }

        // 非良构 id 拒绝重映射（声明为 entity-ref 的槽里出现非实体值 = Mod 违约）。
        val table = EntityIdRemapTable.of(mapOf(2 to 9))
        assertFailsWith<IllegalArgumentException> { table.remap(-1L) }
        assertFailsWith<IllegalArgumentException> { table.remap(1L shl 32) } // pkg 段为 0 的非 NULL

        // 只换 pkg 段：generation / handleSlot 不变；NULL / 未映射包原样。
        val before = EntityIdCodec.encode(2, 7, 0x4000)
        val after = table.remap(before)
        assertEquals(9, EntityIdCodec.pkgShortIdOf(after))
        assertEquals(7, EntityIdCodec.generationOf(after))
        assertEquals(0x4000, EntityIdCodec.handleSlotOf(after))
        val unmapped = EntityIdCodec.encode(3, 7, 0x4000)
        assertEquals(unmapped, table.remap(unmapped))
        assertEquals(EntityIdCodec.NULL, table.remap(EntityIdCodec.NULL))
    }

    // ------------------------------------------------------------------
    // 判据 6：并集冲突不半并（§3.2.6）
    // ------------------------------------------------------------------

    @Test
    fun `判据6_并集冲突不半并_registry与内核两边都未动`() {
        // ---- registry 层：同号异包 / 同包异号 逐条拒绝 + 注册表未半并 ----
        // 同包异 ID：右树先占 2 给别的包，再给 shared 分 3 号。
        val left = PackageShortIdRegistry()
        left.allocate("shared") // 2
        val right = PackageShortIdRegistry()
        right.allocate("blocker") // 2
        right.allocate("shared") // 3
        val leftCount = left.count
        assertFailsWith<RegistryConflictException> { left.mergeSnapshot(right.encode()) }
        assertEquals(leftCount, left.count, "同包异号：注册表未半并")
        assertEquals(2, left.shortIdOf("shared"))
        assertNull(left.shortIdOf("blocker"))

        // 同 ID 异包：左 2=aaa，右 2=bbb。
        val l2 = PackageShortIdRegistry()
        l2.allocate("aaa")
        val r2 = PackageShortIdRegistry()
        r2.allocate("bbb")
        val l2Count = l2.count
        assertFailsWith<RegistryConflictException> { l2.mergeSnapshot(r2.encode()) }
        assertEquals(l2Count, l2.count, "同号异包：注册表未半并")
        assertEquals(2, l2.shortIdOf("aaa"))
        assertNull(l2.shortIdOf("bbb"))

        // ---- SaveService 层：冲突在并集期抛，内核与本地注册表两边都未动 ----
        val src = buildSourceDeviceA()
        val payload = EntityPayloadCodec.encode(src.device.snapshot)
        val split = PackageShortIdRegistry()
        split.allocate("aaa.mod") // 2
        val bad = SidecarSaveBundle(binding, split.encode(), payload, emptyList())
        val reg = conflictingImportRegistry()
        reg.allocate(c.SCHOOL)
        val k = c.newKernel()
        val svc = newIdentitySaveService(k, reg)
        val countBefore = reg.count
        val schoolBefore = reg.shortIdOf(c.SCHOOL)
        assertFailsWith<RegistryConflictException> { svc.restoreBundle(bad, binding) }
        assertEquals(0, k.liveCount, "并注册表先于灌实体：冲突时内核仍是空态")
        assertEquals(countBefore, reg.count, "本地注册表未被半并")
        assertEquals(schoolBefore, reg.shortIdOf(c.SCHOOL))
    }

    // ------------------------------------------------------------------
    // 判据 7：32767 耗尽策略 + 孤儿锁定不回收（§3.2.7）
    // ------------------------------------------------------------------

    @Test
    fun `判据7_32767耗尽拒绝与孤儿锁定不回收`() {
        // 顶格：分配到 32767 后注册新包 = 拒绝 + 诊断，不静默截断。
        val reg = PackageShortIdRegistry()
        var last = 0
        for (i in 2..EntityIdCodec.MAX_PKG_SHORT_ID) {
            last = reg.allocate("bulk.pkg.$i")
        }
        assertEquals(EntityIdCodec.MAX_PKG_SHORT_ID, last)
        val ex = assertFailsWith<IllegalStateException> { reg.allocate("one.more") }
        assertTrue("耗尽" in ex.message!!, ex.message)
        // 不静默截断：顶格号仍归原包、越界新包从未写入。
        assertEquals(
            EntityIdCodec.MAX_PKG_SHORT_ID,
            reg.shortIdOf("bulk.pkg.${EntityIdCodec.MAX_PKG_SHORT_ID}"),
        )
        assertNull(reg.shortIdOf("one.more"))

        // 孤儿载荷存在期间短 ID 锁定不回收：lock 后 encode/decode 往返保留、并集保留、
        // allocate 幂等（永不回收复用）。
        val reg2 = PackageShortIdRegistry()
        val orphan = reg2.allocate("orphan.mod")
        reg2.lock(orphan)
        assertTrue(reg2.isLocked(orphan))
        val restored = PackageShortIdRegistry.decode(reg2.encode())
        assertEquals(orphan, restored.shortIdOf("orphan.mod"))
        assertTrue(restored.isLocked(orphan), "锁定随二进制快照持久，短 ID 不回收")
        assertEquals(orphan, restored.allocate("orphan.mod"), "同一包幂等，短 ID 不回收不复用")
        val mergeTarget = PackageShortIdRegistry()
        mergeTarget.mergeSnapshot(reg2.encode())
        assertTrue(mergeTarget.isLocked(orphan), "并集保留锁定，孤儿片存在期间不回收")
    }

    // ------------------------------------------------------------------
    // 判据 8：存读档闭环 + .rt2 绑定三元组失配零写入（§3.2.8）
    // ------------------------------------------------------------------

    @Test
    fun `判据8_存读档闭环与rt2绑定三元组失配零写入`() {
        val src = buildSourceDeviceA()
        val importReg = conflictingImportRegistry()
        val plan = planConflictRemap(src, importReg)
        val remapped = src.device.remap(plan.table)
        val s1New = plan.table.remap(src.s1)
        val c1New = plan.table.remap(src.c1)

        // ---- 段A：重映射后实体图经 EntitySnapshot / EntityPayloadCodec 往返 ----
        val bytes = EntityPayloadCodec.encode(remapped.snapshot)
        val decoded = EntityPayloadCodec.decode(bytes)
        val dst = c.newKernel()
        dst.restore(decoded)
        assertTrue(dst.exists(s1New), "身份不变：重映射后 id 仍存活")
        assertEquals(plan.newSchool, EntityIdCodec.pkgShortIdOf(s1New))
        assertEquals(c1New, dst.getInt(s1New, c.FIELD_REF), "组件 EntityRef 字段值不变")
        assertEquals(c.ORDINARY_VALUE, dst.getInt(s1New, c.FIELD_ORDINARY), "组件普通值不变")
        assertEquals(src.c1, dst.getInt(s1New, c.FIELD_UNDECLARED_HOLDER), "未声明字段旧 ID 原样")
        assertEquals(c.NICKNAME, dst.getStr(s1New, c.FIELD_STR))
        assertEquals(c.EDGE_VALUE, dst.getRelationValue(s1New, c.RELATION, c1New), "边值不变")
        assertContentEquals(longArrayOf(c1New), dst.queryRelations(s1New, c.RELATION, outgoing = true))

        // ---- 段B：.rt2 绑定三元组失配零写入 ----
        // 把重映射后的快照灌进空内核，走 SaveService 出容器字节（内存字节，无磁盘 I/O）。
        val srcKernel = c.newKernel()
        srcKernel.restore(remapped.snapshot)
        val captureSvc = newIdentitySaveService(srcKernel, importReg)
        val back = SidecarSaveBundle.decode(captureSvc.captureBundle(binding).encode())

        for (bad in listOf(
            binding.copy(uniqueCode = binding.uniqueCode + 1),
            binding.copy(scriptVersion = binding.scriptVersion + 1),
            binding.copy(saveTextHash = binding.saveTextHash + 1),
        )) {
            val dstReg = conflictingImportRegistry()
            dstReg.allocate(c.SCHOOL)
            val dstKernel = c.newKernel()
            val dstSvc = newIdentitySaveService(dstKernel, dstReg)
            val regCountBefore = dstReg.count
            val outcome = dstSvc.restoreBundle(back, bad)
            assertIs<SaveService.RestoreOutcome.BindingMismatch>(outcome)
            assertEquals(0, dstKernel.liveCount, "绑定失配：内核零写入")
            assertEquals(regCountBefore, dstReg.count, "绑定失配：注册表未并")
        }
        // 正向对照：三元组全等 ⇒ Applied，实体恢复。
        val okReg = conflictingImportRegistry()
        okReg.allocate(c.SCHOOL)
        val okKernel = c.newKernel()
        val okSvc = newIdentitySaveService(okKernel, okReg)
        assertEquals(SaveService.RestoreOutcome.Applied, okSvc.restoreBundle(back, binding))
        assertTrue(okKernel.exists(s1New))
    }
}
