package com.eratw.port.core.entity

import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M5-2 真实投射桥验收：MR-2b-1 四行判据用真实桥（[RealCharaSlotProjector] + 真实
 * [VariableStore]/[CharacterTable] 行）复跑（era-arch-v61u.md §7 前置原型 Gate 的
 * 生产形态重证）+ 5 进 2 出零串号 + delete/swap/sort/pickUp 四类映射同步 +
 * syncFromCharaSlot 脱水对拍 + 关系桥度数语义（§7：原生 RELATION 格是 Long 度数）。
 *
 * 注水/脱水域清单 = CFLAG 两格 + CSTR 一格（schema 形态归宿主声明侧；全量逐域
 * 对拍归 M5-4，本类只证桥面时序与对拍通道成立）。
 */
class RealCharaSlotProjectorTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private val binding = SidecarBinding(uniqueCode = 0x1234_5678L, scriptVersion = 7L, saveTextHash = 99)

    /** 3 行原生角色（NO 1/2/3）+ 真实桥 + 绑定回填 + 关系同步器。 */
    private class Fixture {
        val kernel = EntityKernel(
            listOf(
                ComponentSchema(
                    "test.pkg",
                    "NativeChara",
                    listOf(
                        ComponentFieldSpec("CF0"),
                        ComponentFieldSpec("CF3"),
                        ComponentFieldSpec("NICK", ComponentFieldType.STR),
                    ),
                ),
            ),
            listOf(RelationshipSchema("PARENT")),
            listOf(EntityArchetype("PERSON", listOf("NativeChara"))),
            16,
        )
        val pkgs = PackageShortIdRegistry()
        val pkgId: Int = pkgs.allocate("test.pkg")
        val reg = VirtualCharaNoRegistry()
        val store = VariableStore()
        val domains = listOf(
            NativeCharaField("CFLAG", longArrayOf(0), "CF0"),
            NativeCharaField("CFLAG", longArrayOf(3), "CF3"),
            NativeCharaField("CSTR", longArrayOf(2), "NICK", isStr = true),
        )
        val projector = RealCharaSlotProjector(store, kernel, reg, pkgs, domains)
        val bridge = RecordingBridge(projector)
        val sync = RelationProjectionSynchronizer(kernel, bridge, "PARENT")

        init {
            store.charaSlotProjector = projector
            projector.attachRelations(sync)
            // 原生 MASTER 指针出厂 0 会钉住行 0，sort/pickUp 语义因此分岔：装置统一置
            // 无主人公（-1），需要 pin 语义的用例自己显式写回。
            setPointer("MASTER", -1L)
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
        }

        fun setPointer(name: String, value: Long) {
            store.writeInt(name, longArrayOf(0), value)
        }

        fun pointer(name: String): Long = store.readInt(name, longArrayOf(0))

        fun newEntity(): Long = kernel.create(pkgId, "PERSON")

        fun charaNum(): Int = store.characters.count
    }

    /**
     * 关系桥写格日志（[RelationProjectionSynchronizer] 只经桥读写原生矩阵）：
     * 用来断言退场清扫**只碰退场者自己的行与列**——退场者的列键随行删除离场后
     * 不再可寻址，事后读那一列读的是别人接管的物理槽位，观测不到「已清成默认」。
     */
    private class RecordingBridge(private val inner: RelationProjectionBridge) : RelationProjectionBridge {
        val writes = ArrayList<Triple<Int, Long, Long>>()

        override val relationDefault: Long get() = inner.relationDefault

        override fun readCell(charIndex: Int, targetKey: Long): Long = inner.readCell(charIndex, targetKey)

        override fun writeCell(charIndex: Int, targetKey: Long, value: Long) {
            writes.add(Triple(charIndex, targetKey, value))
            inner.writeCell(charIndex, targetKey, value)
        }

        override fun rosterRows(): List<RosterRow> = inner.rosterRows()

        override fun graphIdentityOf(charIndex: Int): Long = inner.graphIdentityOf(charIndex)
    }

    private fun saveService(f: Fixture) = SaveService(f.kernel, f.projector, f.pkgs, f.reg)

    // ------------------------------------------------------------------
    // 投射面
    // ------------------------------------------------------------------

    @Test
    fun `project_未存在实体或未注册包返负一`() {
        val f = Fixture()
        assertEquals(-1, f.projector.project(0xdeadbeefL), "悬空 EntityId 拒投")
        val ghost = f.kernel.create(999, "PERSON") // pkg 999 未在注册表 backing
        assertEquals(-1, f.projector.project(ghost), "pkg 段无注册 backing 拒投（fail-closed）")
    }

    @Test
    fun `project_幂等建行NO写虚拟号`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e)
        assertEquals(3, row, "3 原生角色之后尾部追加")
        assertEquals(row, f.projector.project(e), "已投射幂等返回当前行号")
        assertEquals(10_000L, f.store.readInt("NO", longArrayOf(row.toLong())), "NO 列 = 首个虚拟 NO")
        assertEquals(e, f.projector.entityAt(row))
        assertEquals(0L, f.projector.rosterGeneration, "尾增不递增世代（§7）")
        assertEquals(4, f.charaNum())
    }

    @Test
    fun `project_注水从组件到活行`() {
        val f = Fixture()
        val e = f.newEntity()
        assertTrue(f.kernel.setInt(e, "CF0", 7L))
        assertTrue(f.kernel.setInt(e, "CF3", 9L))
        assertTrue(f.kernel.setStr(e, "NICK", "喵"))
        val row = f.projector.project(e)
        assertEquals(7L, f.store.readInt("CFLAG", longArrayOf(row.toLong(), 0)))
        assertEquals(9L, f.store.readInt("CFLAG", longArrayOf(row.toLong(), 3)))
        assertEquals("喵", f.store.readStr("CSTR", longArrayOf(row.toLong(), 2)))
    }

    // ------------------------------------------------------------------
    // 退场面
    // ------------------------------------------------------------------

    @Test
    fun `unproject_脱水删行指针清扫`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e) // 3
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0), 42L)
        f.store.writeStr("CSTR", longArrayOf(row.toLong(), 2), "汪")
        // 引擎已知指针：等于 deletedPos 置默认、大于者减 1、小于者不动。
        f.setPointer("TARGET", row.toLong()) // TARGET[0] = 3
        f.store.writeInt("TARGET", longArrayOf(9), row + 1L) // TARGET[9] = 4
        f.store.writeInt("TARGET", longArrayOf(2), row - 1L) // TARGET[2] = 2
        f.setPointer("ASSI", row.toLong())
        f.setPointer("PLAYER", row + 1L)

        assertTrue(f.projector.unproject(e))
        assertEquals(3, f.charaNum(), "退场删行")
        assertEquals(42L, f.kernel.getInt(e, "CF0"), "活行 CFLAG 脱水进组件")
        assertEquals("汪", f.kernel.getStr(e, "NICK"), "活行 CSTR 脱水进组件")
        assertEquals(-1L, f.pointer("TARGET"), "TARGET[0] == deletedPos 置默认")
        assertEquals(row.toLong(), f.store.readInt("TARGET", longArrayOf(9)), "TARGET[9] = 4 > 3 减 1")
        assertEquals(row - 1L, f.store.readInt("TARGET", longArrayOf(2)), "TARGET[2] = 2 < 3 不动")
        assertEquals(-1L, f.pointer("ASSI"))
        assertEquals(row.toLong(), f.pointer("PLAYER"))
        assertEquals(-1, f.projector.charIndexOf(e))
        assertTrue(f.projector.unproject(e).not(), "未投射返回 false")
    }

    @Test
    fun `unproject_未投射返回假`() {
        val f = Fixture()
        assertEquals(false, f.projector.unproject(f.newEntity()))
    }

    // ------------------------------------------------------------------
    // 行操作钩子（引擎侧直接行变更）
    // ------------------------------------------------------------------

    @Test
    fun `引擎删投射行_钩子解映射并递增世代`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e)
        val gen = f.projector.rosterGeneration
        f.store.deleteCharacter(row.toLong()) // 原版 DELCHARA 即时语义，不脱水（ADR-005）
        assertEquals(-1, f.projector.charIndexOf(e), "行随删除解映射，格子无从读取")
        assertEquals(EntityIdCodec.NULL, f.projector.entityAt(row))
        assertEquals(gen + 1, f.projector.rosterGeneration, "退场删行递增世代")
    }

    @Test
    fun `引擎删原生行_其余投射左移`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertEquals(3, f.projector.project(e1))
        assertEquals(4, f.projector.project(e2))
        f.store.deleteCharacter(0)
        assertEquals(2, f.projector.charIndexOf(e1), "行 3 左移到 2")
        assertEquals(3, f.projector.charIndexOf(e2), "行 4 左移到 3")
        assertEquals(e1, f.projector.entityAt(2))
    }

    @Test
    fun `swap_映射换位`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertEquals(3, f.projector.project(e1))
        assertEquals(4, f.projector.project(e2))
        val gen = f.projector.rosterGeneration
        f.store.swapCharacters(3, 4)
        assertEquals(4, f.projector.charIndexOf(e1))
        assertEquals(3, f.projector.charIndexOf(e2))
        assertEquals(gen + 1, f.projector.rosterGeneration)
    }

    @Test
    fun `sort_由排序实现产出映射_行内容与投射行对位`() {
        val f = Fixture()
        val e = f.newEntity()
        val row = f.projector.project(e) // 3
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0), 0L)
        f.store.writeInt("CFLAG", longArrayOf(0, 0), 30L)
        f.store.writeInt("CFLAG", longArrayOf(1, 0), 10L)
        f.store.writeInt("CFLAG", longArrayOf(2, 0), 20L)
        val gen = f.projector.rosterGeneration
        f.setPointer("TARGET", 0L) // TARGET 出厂 1；显式置 0 才测得到「重定位到旧行 0 的新位置」
        f.store.sortCharactersByInt("CFLAG", longArrayOf(0))
        // 键 = 0/30/10/20 → 升序 e(0)@0、角色2(10)@1、角色3(20)@2、角色1(30)@3。
        assertEquals(0, f.projector.charIndexOf(e))
        assertEquals(10_000L, f.store.readInt("NO", longArrayOf(0)), "投射行随排序移动，NO 列跟随")
        assertEquals(1L, f.store.readInt("NO", longArrayOf(3)))
        assertEquals(gen + 1, f.projector.rosterGeneration)
        // 钉「非 bug 修复」形态：排序后既有 TARGET 重定位行为照旧（本装置 MASTER=-1，
        // TARGET 出厂 0 指向旧行 0 的角色1 → 重定位到新行 3）。
        assertEquals(3L, f.pointer("TARGET"))
    }

    @Test
    fun `pickUp_复合钩子_未选中投射实体随行删除`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertEquals(3, f.projector.project(e1))
        assertEquals(4, f.projector.project(e2))
        f.store.pickUpCharacters(longArrayOf(1, 3)) // 保留原生行 1 与 e1；e2 在尾删段出局
        assertEquals(2, f.charaNum())
        assertEquals(1, f.projector.charIndexOf(e1), "e1 换位到表头段")
        assertEquals(-1, f.projector.charIndexOf(e2), "未选中 = 行删除钩子解映射（ADR-005 即时语义）")
        assertTrue(f.projector.rosterGeneration > 0)
    }

    @Test
    fun `五进二出零串号`() {
        val f = Fixture()
        val ids = List(5) { f.newEntity() }
        ids.forEachIndexed { i, e -> assertEquals(3 + i, f.projector.project(e)) }
        // 脚本直写行特征（脱水面之外的对拍键）。
        for (row in 3..7) f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0), (row + 100).toLong())
        assertTrue(f.projector.unproject(ids[1]))
        assertTrue(f.projector.unproject(ids[3]))
        assertEquals(6, f.charaNum())
        // 实体序 → (退场后行号, 该实体原行特征)：特征跟着实体走，跟着行号变的是位置。
        val expected = listOf(Triple(0, 3, 103L), Triple(2, 4, 105L), Triple(4, 5, 107L))
        for ((idx, row, feature) in expected) {
            assertEquals(row.toLong(), f.projector.charIndexOf(ids[idx]).toLong(), "实体 #$idx 行号")
            assertEquals(feature, f.store.readInt("CFLAG", longArrayOf(row.toLong(), 0)), "实体 #$idx 行特征对位，零串号")
        }
        for (idx in intArrayOf(1, 3)) assertEquals(-1, f.projector.charIndexOf(ids[idx]))
    }

    // ------------------------------------------------------------------
    // MR-2b-1 四行判据（真实桥复跑）
    // ------------------------------------------------------------------

    @Test
    fun `判据一_声明EntityId引用跨删除平移与存读档仍有效`() {
        val f = Fixture()
        val child = f.newEntity()
        f.reg.allocate(child)
        val row0 = f.projector.project(child) // 行 3
        val declaredRef: Long = child
        assertEquals(row0, f.projector.charIndexOf(declaredRef))

        // 变更 Roster：原版 DELCHARA 删原生行 1 → child 平移 3→2。
        f.store.deleteCharacter(1)
        assertEquals(2, f.projector.charIndexOf(declaredRef), "EntityId 引用经现换仍解析到当前行")

        // 存读档：EntityId 与虚拟 NO 映射随 .rt2 恢复，引用仍可重投射解析。
        val bundle = saveService(f).captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(saveService(f2).restoreBundle(bundle, binding))
        assertTrue(f2.kernel.exists(declaredRef))
        assertEquals(child, f2.reg.entityOf(f.reg.virtualNoOf(child)!!))
        assertEquals(10_000, f.reg.virtualNoOf(child))
        val row2 = f2.projector.project(declaredRef)
        assertEquals(row2, f2.projector.charIndexOf(declaredRef))
    }

    @Test
    fun `判据二_世代快照可判过期`() {
        val f = Fixture()
        val a = f.newEntity()
        val b = f.newEntity()
        assertEquals(3, f.projector.project(a))
        assertEquals(4, f.projector.project(b))
        val genSnapshot = f.projector.rosterGeneration
        val cachedRow = 3
        assertEquals(a, f.projector.entityAt(cachedRow))
        f.store.swapCharacters(3, 4)
        assertTrue(f.projector.rosterGeneration != genSnapshot, "世代递增使 Mod 能判过期")
        assertEquals(b, f.projector.entityAt(cachedRow), "旧缓存行号现在指向别人——过期可见")
    }

    @Test
    fun `判据三_过期世代经校验通道换CHARA被拒`() {
        val f = Fixture()
        val child = f.newEntity()
        val other = f.newEntity()
        assertEquals(3, f.projector.project(child))
        assertEquals(4, f.projector.project(other))
        val genAtRef = f.projector.rosterGeneration
        assertEquals(3, f.projector.charIndexOfChecked(child, genAtRef), "当前世代放行")
        f.store.swapCharacters(3, 4)
        assertEquals(-1, f.projector.charIndexOfChecked(child, genAtRef), "过期世代经校验通道被拒")
        assertEquals(
            f.projector.charIndexOf(child),
            f.projector.charIndexOfChecked(child, f.projector.rosterGeneration),
            "新世代快照放行并返回当前行",
        )
    }

    @Test
    fun `判据四_未声明裸Long引擎不猜值不乱改`() {
        val f = Fixture()
        val child = f.newEntity()
        val row0 = f.projector.project(child) // 行 3
        val undeclaredLong: Long = row0.toLong()
        f.store.deleteCharacter(0) // child 平移 3→2
        assertEquals(row0.toLong(), undeclaredLong, "裸 Long 逐位不变（引擎没有改写它的通道）")
        assertEquals(2, f.projector.charIndexOf(child))
        assertEquals(EntityIdCodec.NULL, f.projector.entityAt(undeclaredLong.toInt()), "旧行号已悬空且不修复")
        assertEquals(2, f.projector.charIndexOfChecked(child, f.projector.rosterGeneration))
    }

    // ------------------------------------------------------------------
    // D 方案全链路 + 关系桥度数语义
    // ------------------------------------------------------------------

    @Test
    fun `D方案全链路_投射改值退场排序重投射存读档`() {
        val f = Fixture()
        val e = f.newEntity()
        val no = f.reg.allocate(e)
        val row = f.projector.project(e) // 行 3
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0), 42L)
        f.store.writeStr("CSTR", longArrayOf(row.toLong(), 2), "喵")

        assertTrue(f.projector.unproject(e))
        assertEquals(42L, f.kernel.getInt(e, "CF0"), "退场脱水")
        f.reg.retire(e) // 撤销投射退休号（宿主侧动作，号永不复用）

        f.store.swapCharacters(0, 1) // 排序原生（e 已退场）
        val no2 = f.reg.allocate(e)
        assertNotEquals(no, no2, "退休号永不复用，重投射拿新号")
        val row2 = f.projector.project(e)
        assertEquals(42L, f.store.readInt("CFLAG", longArrayOf(row2.toLong(), 0)), "重投射注水恢复脱水值")
        assertEquals("喵", f.store.readStr("CSTR", longArrayOf(row2.toLong(), 2)))
        assertEquals(no2.toLong(), f.store.readInt("NO", longArrayOf(row2.toLong())))

        val bundle = saveService(f).captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(saveService(f2).restoreBundle(bundle, binding))
        assertTrue(f2.kernel.exists(e))
        assertEquals(42L, f2.kernel.getInt(e, "CF0"), "存读档无损")
        assertEquals(e, f2.reg.entityOf(no2))
    }

    @Test
    fun `关系桥_度数回写与只清退场者行列`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertTrue(f.kernel.addRelation(e1, "PARENT", e2))
        assertTrue(f.kernel.setRelationValue(e1, "PARENT", e2, 100L))
        val row1 = f.projector.project(e1) // 3
        val row2 = f.projector.project(e2) // 4
        val key1 = f.reg.virtualNoOf(e1)!!.toLong()
        val key2 = f.reg.virtualNoOf(e2)!!.toLong()
        assertEquals(100L, f.projector.readCell(row1, key2), "进场把图里边值（度数）写进原生格")
        assertEquals(0L, f.projector.readCell(row2, key1), "有向边：反方向无此边，格值是默认度数 0")

        // 原版互动直改关系格：e1 行对 e2 列 100→200；e1 行对原生列另写一格（与 e2 无关）。
        f.projector.writeCell(row1, key2, 200L)
        f.projector.writeCell(row1, 1L, 555L) // 原生行 0 的 NO=1 是列键

        val writesBefore = f.bridge.writes.size
        assertTrue(f.projector.unproject(e2))
        assertEquals(200L, f.kernel.getRelationValue(e1, "PARENT", e2), "投射期度数变化差分回写进图")
        // 清扫面收缩证据：退场只写「退场者行 → 任何列键」与「任何行 → 退场者列键」两族格，
        // 别人的行与行之间一格不碰（禁全表清零）。退场者的列键随行删除离场后不再可寻址，
        // 所以用写格日志对账，而不是删行后再去读那一列（那已是别人的物理槽位）。
        val clears = f.bridge.writes.drop(writesBefore).filter { it.third == 0L }
        assertTrue(clears.isNotEmpty(), "退场确实清了残留")
        assertTrue(
            clears.all { it.first == row2 || it.second == key2 },
            "清残留只碰退场者自己的行与列，实写=$clears",
        )
        assertEquals(555L, f.projector.readCell(row1, 1L), "别人行上的关系格一格不动（禁全表清零）")
        assertTrue(f.sync.lastReport!!.isClean)
        // 退场者的列键随行删除离场：再按漂移键读写当场拒绝（fail-closed，绝不写别人的格）。
        assertFailsWith<IllegalArgumentException> { f.projector.readCell(row1, key2) }
    }

    @Test
    fun `rosterRows_列键取NO列_行号升序`() {
        val f = Fixture()
        val e = f.newEntity()
        f.projector.project(e)
        val rows = f.projector.rosterRows()
        assertEquals(4, rows.size)
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.charIndex })
        assertEquals(listOf(1L, 2L, 3L, 10_000L), rows.map { it.columnKey }, "原生 = CSV NO、投射 = 虚拟 NO")
    }

    // ------------------------------------------------------------------
    // 活动期闸 × 退场时序交互（M5-3）
    // ------------------------------------------------------------------

    @Test
    fun `闸拒绝发生在退场时序之前_基线与世代原样_页边界恢复后照常退场`() {
        val f = Fixture()
        val e = f.newEntity()
        f.projector.project(e)
        val activeBefore = f.sync.activeProjections
        val generationBefore = f.projector.rosterGeneration
        val probe: () -> Boolean = { true }
        f.projector.bindActivityGate(probe)
        val error = assertFailsWith<IllegalStateException> { f.projector.unproject(e) }
        assertTrue(error.message!!.contains("活动调用栈"), "拒文案含活动调用栈语义：${error.message}")
        assertEquals(activeBefore, f.sync.activeProjections, "拒绝必须发生在退场时序之前，进场基线原样")
        assertEquals(generationBefore, f.projector.rosterGeneration, "拒绝不动世代")
        f.projector.unbindActivityGate(probe)
        assertTrue(f.projector.unproject(e), "页边界（VM 让出）恢复后照常退场")
        assertEquals(0, f.sync.activeProjections, "退场基线正常消费")
    }
    @Test
    fun `脱水_组件字段未注册fail拒绝`() {
        val f = Fixture()
        // 同一内核、只把脱水域指向未注册字段 GHOST：注水侧 getInt 对未注册字段读 0
        // （行照建），脱水侧 setInt 返回 false ⇒ require 当场抛，不静默丢数据。
        val broken = RealCharaSlotProjector(
            f.store,
            f.kernel,
            null,
            null,
            listOf(NativeCharaField("CFLAG", longArrayOf(0), "GHOST")),
        )
        val e = f.newEntity()
        val row = broken.project(e)
        assertTrue(row >= 0, "未注册字段的注水读 0，行照建")
        val ex = assertFailsWith<IllegalArgumentException> { broken.syncFromCharaSlot(e) }
        assertTrue(ex.message!!.contains("GHOST"), "fail-closed 文案带字段名")
    }}
