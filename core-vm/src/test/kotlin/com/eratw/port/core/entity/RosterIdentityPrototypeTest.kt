package com.eratw.port.core.entity

import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Roster 最小身份原型（MR-2b-1，蓝图 §7「前置原型 Gate」诚实四行判据 + §16 方案 D 全链路）。
 *
 * ## 这个原型证什么
 *
 * §7 裁定：「原型不过，ABI-1 不得以『已验证角色身份模型』名义冻结，M5 不开工。」
 * 本原型用**已落码的核心组件**（[EntityKernel] / [VirtualCharaNoRegistry] / [RosterPinner] /
 * [RelationProjectionSynchronizer] / [SaveService] / [VirtualCharaIndexResolver]）组一个最小
 * 可执行装置，逐条咬住 §7 的诚实四行判据：
 *
 * 1. 声明 entity-ref 存 EntityId → 变更 Roster 后引用仍有效（经 [ICharaSlotProjector.charIndexOf]
 *    现换 charIndex），且 EntityId 随 .rt2 存读档不变；
 * 2. 存原生引用时快照 [ICharaSlotProjector.rosterGeneration] → 变更 Roster 后 Mod 自检能判过期；
 * 3. 过期世代快照经引擎校验通道 [ICharaSlotProjector.charIndexOfChecked] 换 CHARA → 拒绝；
 * 4. 普通 Long 存旧 charIndex 且未声明 → 引擎不猜值、不乱改（负控只验不误伤，悬空可见）。
 *
 * 末一条全链路用例走方案 D 的「投射 → 改 CFLAG/关系 → 退场 → 排序原生 → 重投射 → 存读档再校验」。
 *
 * ## 边界（与脏活的分界）
 *
 * 宿主角色行桥/关系矩阵桥这里用 fake（[RosterBridge]/[RelationProjectionBridge] 接缝，M5-2
 * 才接真实 VariableStore/CharacterTable）；ROSTER_GENERATION/ENTITY_TO_CHARA 的**内置函数注册**
 * 归 M4-2，本原型只证其核心底座（世代原语 + 校验通道）成立。真实语料互动对拍归 MR-2b-2。
 */
class RosterIdentityPrototypeTest {

    private val schemas = listOf(ComponentSchema("pkg", "Profile", listOf(ComponentFieldSpec("level"))))
    private val relations = listOf(RelationshipSchema("PARENT"))
    private val archetypes = listOf(EntityArchetype("PERSON", listOf("Profile")))
    private val binding = SidecarBinding(uniqueCode = 0x1234_5678L, scriptVersion = 7L, saveTextHash = 99)

    /**
     * 最小宿主桥：原生角色行表（尾部追加、删除左移、可排列）+ RELATION 格 + 活行 CFLAG。
     * 列键：原生行 = CSV NO（seed 时喂），投射行 = 虚拟 NO（注水时从注册表取，投射期内稳定）。
     */
    private class FakeHost(val k: EntityKernel, val reg: VirtualCharaNoRegistry) :
        RosterBridge, RelationProjectionBridge {

        class Row(var columnKey: Long, var charIndex: Int, var identity: Long) {
            val cells = HashMap<Long, Long>()
            var liveFlag = 0L // 模拟 CFLAG 活行值（脱水/注水的搬运对象）
        }

        val rows = ArrayList<Row>()
        override val relationDefault = 0L
        private var nextKey = 10000L

        fun seedNative(columnKey: Long, identity: Long): Int {
            val idx = rows.size
            rows += Row(columnKey, idx, identity)
            return idx
        }

        fun rowAt(charIndex: Int): Row = rows.first { it.charIndex == charIndex }

        /** 原版脚本直改关系格（不经任何引擎钩子）。 */
        fun scriptSet(charIndex: Int, targetKey: Long, value: Long) {
            rowAt(charIndex).cells[targetKey] = value
        }

        /** 宿主侧行排列（SORTCHARA）：先动物理行，再调投射钩子同步映射。 */
        fun permuteRows(oldToNew: IntArray) {
            val placed = arrayOfNulls<Row>(rows.size)
            for (r in rows) placed[oldToNew[r.charIndex]] = r
            rows.clear()
            for (i in placed.indices) {
                val r = placed[i]!!
                r.charIndex = i
                rows += r
            }
        }

        override fun allocateRow(): Int {
            val idx = rows.size
            rows += Row(nextKey++, idx, EntityIdCodec.NULL)
            return idx
        }

        override fun releaseRow(charIndex: Int) {
            rows.removeAll { it.charIndex == charIndex }
            for (r in rows) if (r.charIndex > charIndex) r.charIndex--
        }

        override fun pourFromEntity(entityId: Long, charIndex: Int) {
            val r = rowAt(charIndex)
            r.identity = entityId
            reg.virtualNoOf(entityId)?.let { r.columnKey = it.toLong() }
            r.liveFlag = k.getInt(entityId, "level") // 注水：组件 → 活行
        }

        override fun captureToEntity(entityId: Long, charIndex: Int) {
            k.setInt(entityId, "level", rowAt(charIndex).liveFlag) // 脱水：活行 → 组件
        }

        override fun readCell(charIndex: Int, targetKey: Long): Long =
            rowAt(charIndex).cells[targetKey] ?: relationDefault

        override fun writeCell(charIndex: Int, targetKey: Long, value: Long) {
            rowAt(charIndex).cells[targetKey] = value
        }

        override fun rosterRows(): List<RosterRow> =
            rows.sortedBy { it.charIndex }.map { RosterRow(it.charIndex, it.columnKey) }

        override fun graphIdentityOf(charIndex: Int): Long = rowAt(charIndex).identity
    }

    private inner class Fixture {
        val k = EntityKernel(schemas, relations, archetypes, 16)
        val reg = VirtualCharaNoRegistry()
        val pkg = PackageShortIdRegistry()
        val host = FakeHost(k, reg)
        val sync = RelationProjectionSynchronizer(k, host, "PARENT")
        val p = RosterPinner(k, host, sync)
        val resolver = VirtualCharaIndexResolver(reg) { p.charIndexOf(it) }
        val svc = SaveService(k, p, pkg, reg)
    }

    // ------------------------------------------------------------------
    // 世代原语语义钉
    // ------------------------------------------------------------------

    @Test
    fun `世代尾增不递增排列删除递增`() {
        val f = Fixture()
        val a = f.k.create(1, "PERSON")
        val b = f.k.create(1, "PERSON")
        assertEquals(0L, f.p.rosterGeneration)
        f.p.project(a) // 尾增
        f.p.project(b) // 尾增
        assertEquals(0L, f.p.rosterGeneration, "尾部追加不动既有行号，世代不递增")
        f.host.permuteRows(intArrayOf(1, 0))
        f.p.onCharacterRowsPermuted(intArrayOf(1, 0))
        assertEquals(1L, f.p.rosterGeneration, "行排列即递增")
        f.host.releaseRow(0)
        f.p.onCharacterRowDeleted(0)
        assertEquals(2L, f.p.rosterGeneration, "行删除（含退场删行）即递增")
    }

    // ------------------------------------------------------------------
    // §7 诚实四行判据
    // ------------------------------------------------------------------

    @Test
    fun `判据一_声明EntityId引用跨删除平移与存读档仍有效`() {
        val f = Fixture()
        val anchorA = f.k.create(1, "PERSON")
        f.host.seedNative(1L, anchorA) // 原生行 0
        val nativeB = f.k.create(1, "PERSON")
        f.host.seedNative(2L, nativeB) // 原生行 1
        val child = f.k.create(1, "PERSON")
        val no = f.reg.allocate(child)
        val row0 = f.p.project(child) // 行 2
        assertEquals(2, row0)
        // 声明 entity-ref 槽里存的是 EntityId（稳定身份），不是 charIndex。
        val declaredRef: Long = child
        assertEquals(row0, f.p.charIndexOf(declaredRef))
        assertEquals(row0, f.resolver.resolve(no.toLong(), f.host.rows.size))

        // 变更 Roster：原版 DELCHARA 删原生行 1 → child 平移 2→1。
        f.host.releaseRow(1)
        f.p.onCharacterRowDeleted(1)
        assertEquals(1, f.p.charIndexOf(declaredRef), "EntityId 引用经现换仍解析到当前行")
        assertEquals(1, f.resolver.resolve(no.toLong(), f.host.rows.size))

        // 存读档：EntityId 与虚拟 NO 映射随 .rt2 恢复，引用仍可重投射解析。
        val bundle = f.svc.captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.svc.restoreBundle(bundle, binding))
        assertTrue(f2.k.exists(declaredRef))
        assertEquals(declaredRef, f2.reg.entityOf(no))
        val row2 = f2.p.project(declaredRef) // 读档后重投射
        assertEquals(row2, f2.p.charIndexOf(declaredRef))
        assertEquals(row2, f2.resolver.resolve(no.toLong(), f2.host.rows.size))
    }

    @Test
    fun `判据二_世代快照可判过期`() {
        val f = Fixture()
        val a = f.k.create(1, "PERSON")
        val b = f.k.create(1, "PERSON")
        f.host.seedNative(1L, a)
        f.host.seedNative(2L, b)
        // Mod 存原生引用时一并快照世代（缓存 charIndex=0 指向 a）。
        val genSnapshot = f.p.rosterGeneration
        val cachedRow = 0
        assertEquals(a, f.host.rowAt(cachedRow).identity)
        // 变更 Roster：SORTCHARA 交换两原生行。
        f.host.permuteRows(intArrayOf(1, 0))
        f.p.onCharacterRowsPermuted(intArrayOf(1, 0))
        // Mod 自检：世代变了 ⇒ 缓存 charIndex 过期，必须刷新（判过期是 Mod 契约义务）。
        assertTrue(f.p.rosterGeneration != genSnapshot, "世代递增使 Mod 能判过期")
        assertEquals(b, f.host.rowAt(cachedRow).identity, "旧缓存行号现在指向别人——过期可见")
    }

    @Test
    fun `判据三_过期世代经校验通道换CHARA被拒`() {
        val f = Fixture()
        val child = f.k.create(1, "PERSON")
        f.reg.allocate(child)
        val other = f.k.create(1, "PERSON")
        f.reg.allocate(other)
        val row = f.p.project(child) // 行 0
        f.p.project(other) // 行 1
        val genAtRef = f.p.rosterGeneration
        assertEquals(row, f.p.charIndexOfChecked(child, genAtRef), "当前世代放行")
        // 变更 Roster：排列 → 世代递增。
        f.host.permuteRows(intArrayOf(1, 0))
        f.p.onCharacterRowsPermuted(intArrayOf(1, 0))
        assertEquals(-1, f.p.charIndexOfChecked(child, genAtRef), "过期世代经校验通道被拒")
        assertEquals(
            f.p.charIndexOf(child),
            f.p.charIndexOfChecked(child, f.p.rosterGeneration),
            "新世代快照放行并返回当前行",
        )
    }

    @Test
    fun `判据四_未声明Long旧charIndex引擎不猜值不乱改`() {
        val f = Fixture()
        val anchor = f.k.create(1, "PERSON")
        f.host.seedNative(1L, anchor) // 原生行 0
        val child = f.k.create(1, "PERSON")
        f.reg.allocate(child)
        val row0 = f.p.project(child) // 行 1
        // Mod 裸 Long 存了临时 charIndex（未声明 entity-ref、未带世代）。
        val undeclaredLong: Long = row0.toLong()
        // 变更 Roster：删原生行 0 → child 平移 1→0。
        f.host.releaseRow(0)
        f.p.onCharacterRowDeleted(0)
        // 引擎不误伤：裸 Long 逐位不变（引擎没有任何扫描/改写它的通道，不猜它是角色编号）。
        assertEquals(row0.toLong(), undeclaredLong)
        // 但它已悬空：旧行号 1 已不存在（只剩 1 行），引擎不把它修复成 child 的新行。
        assertEquals(0, f.p.charIndexOf(child))
        assertEquals(EntityIdCodec.NULL, f.p.entityAt(undeclaredLong.toInt()))
        // 对照：声明过的 EntityId 引用经现换仍正确（引擎只保护声明引用，不保护裸 Long）。
        assertEquals(0, f.p.charIndexOfChecked(child, f.p.rosterGeneration))
    }

    // ------------------------------------------------------------------
    // 方案 D 全链路 + 无 Mod 旁路
    // ------------------------------------------------------------------

    @Test
    fun `D方案全链路_投射改值退场排序重投射存读档`() {
        val f = Fixture()
        val anchor = f.k.create(1, "PERSON")
        f.host.seedNative(1L, anchor) // 原生 A 行 0
        val nativeB = f.k.create(1, "PERSON")
        f.host.seedNative(2L, nativeB) // 原生 B 行 1
        val child = f.k.create(1, "PERSON")
        val no = f.reg.allocate(child)
        assertTrue(f.k.addRelation(child, "PARENT", anchor))
        assertTrue(f.k.setRelationValue(child, "PARENT", anchor, 100L))

        // 投射：进场恢复关系子矩阵 + 注水。
        val row = f.p.project(child) // 行 2
        assertEquals(100L, f.host.rowAt(row).cells[1L], "进场把图里边值写进原生格")
        // 投射期间原版互动改 CFLAG 活行与关系格。
        f.host.rowAt(row).liveFlag = 42L
        f.host.scriptSet(row, 1L, 200L)
        // 退场：脱水 → 回写清残留 → 删行（时序由 RosterPinner 强制）。
        assertTrue(f.p.unproject(child))
        assertEquals(42L, f.k.getInt(child, "level"), "活行 CFLAG 脱水进组件")
        assertEquals(200L, f.k.getRelationValue(child, "PARENT", anchor), "投射期关系变化回写进图")
        assertTrue(f.sync.lastReport!!.isClean)
        f.reg.retire(child) // 撤销投射退休号（永不复用）

        // 排序原生（child 已退场，排列只动原生行）。
        f.host.permuteRows(intArrayOf(1, 0))
        f.p.onCharacterRowsPermuted(intArrayOf(1, 0))
        // 重投射：拿新号（旧号已退休），注水恢复组件值。
        val no2 = f.reg.allocate(child)
        assertNotEquals(no, no2, "退休号永不复用，重投射拿新号")
        val row2 = f.p.project(child)
        assertEquals(42L, f.host.rowAt(row2).liveFlag, "重投射注水恢复脱水值")
        assertEquals(row2, f.resolver.resolve(no2.toLong(), f.host.rows.size))
        assertEquals(VirtualCharaIndexResolver.OOR, f.resolver.resolve(no.toLong(), f.host.rows.size), "旧退休号解析失败")

        // 存读档再校验：身份/组件/关系无损。
        val bundle = f.svc.captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.svc.restoreBundle(bundle, binding))
        assertTrue(f2.k.exists(child))
        assertEquals(child, f2.reg.entityOf(no2))
        assertEquals(42L, f2.k.getInt(child, "level"))
        assertEquals(200L, f2.k.getRelationValue(child, "PARENT", anchor))
    }

    @Test
    fun `无Mod旁路_空注册表原生下标原样虚拟域OOR`() {
        val f = Fixture() // 注册表空 = 无 Mod
        val native = f.k.create(1, "PERSON")
        f.host.seedNative(1L, native)
        // 原生下标原样返回（golden 逐位不变）；虚拟域无命中 → OOR（完全旁路）。
        assertEquals(0, f.resolver.resolve(0L, f.host.rows.size))
        assertEquals(VirtualCharaIndexResolver.OOR, f.resolver.resolve(10000L, f.host.rows.size))
        assertEquals(0L, f.p.rosterGeneration, "无行操作世代恒 0")
    }
}