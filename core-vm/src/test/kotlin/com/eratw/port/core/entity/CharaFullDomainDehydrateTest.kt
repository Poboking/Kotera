package com.eratw.port.core.entity

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PACKAGE_ID: String = "m54.domains"

/**
 * M5-4 per-character 全量脱水证明（era-arch-v61u.md §7 第 4 条「不只验 CFLAG」+
 * §23.4 F 车回执卡点「fields 空表只建行 / 维护映射 / 写 NO，全量域清单须由声明事务喂」）。
 *
 * 逐域对拍六域，域 → 生产承载面（装置全真，无 fake 自证）：
 * - CFLAG / TALENT / ABL：`VariableStore.characters.int1` 系统 1 维整数列
 *   （VariableStore.kt readIntEntry / writeIntEntry 的 `C_INT1` 分支；低 16 位 = 9 / 3 / 2，
 *   VariableCode.kt；C# 锚 VariableData.cs:3078/:3079/:3085 的 CharaInt1DVariableToken）；
 * - CHARADATA：`declareUserVar(DimSpec(isCharaData = true))` 的角色追加列
 *   （VariableStore.kt:2518-2528 → CharacterTable.appendIntColumn / appendStrColumn →
 *   CharaIntUserVar / CharaStrUserVar；C# 锚 VariableData.cs:3220-3244
 *   CreateUserDefCharaVariable）；
 * - 关系行：原生 RELATION 矩阵 `int1[11]`（RealCharaSlotProjector.relationColumn）经
 *   RelationProjectionSynchronizer 与实体图边（带值）双向同步；边值随实体段持久
 *   （EntityPayloadCodec v2 的 i64 边值，EntitySnapshot.EdgeSnapshot）；
 * - ITEM：**无 per-character 承载面** —— 官方语义是全局 Int1DVariableToken
 *   （C# 锚 VariableData.cs:3016；移植侧 SystemLayout.int1D → `G_INT1`），
 *   本类以形状锁证明脱水面不触碰全局列，差异照实登记卡点（见下）。
 *
 * 脱水入口全部走生产链，测试不手调 sync：
 * - 退场 = RealCharaSlotProjector.unproject 内的 syncFromCharaSlot（capture 活行 → 组件）；
 * - 存档 = SaveService.captureBundle 内的 captureEntityPayload（先对全部投射实体
 *   syncFromCharaSlot 再 exportSnapshot，SaveService 不变量 1）；
 * - 恢复 = restoreBundle（虚拟 NO → 包注册表 → 实体图，不变量 2）；
 * - 重投射 = project 内的 pour（组件字段 → 活行）。
 *
 * 全量域清单的喂入形状（F 车回执：宿主不得硬编码业务 schema）：组件字段名单由
 * 真 DeclarationRegistry.freeze 的冻结快照遍历得出，宿主只补「字段 → 变量 / 下标」
 * 映射行，装置断言域清单条目数 = 冻结字段数（一个不少）。**变量映射声明域本身
 * 尚不存在**（DeclarationRegistry 五域不含它，present 宿主只能以映射行喂）——该缺口
 * 照实登记为 M5-4 卡点，不在本验证面内补桩。
 *
 * 卡点登记（TODO(verify)）：era-arch-v61u.md §7 把 ITEM 列进 per-character 域清单，
 * 而官方语义（specs 下的 yaml 与 VariableData.cs:3016）把 ITEM 放在全局 1 维整数面，
 * 角色行上没有它的格子 ⇒ 本类对 ITEM 只做「当下形状锁」（域清单不含 ITEM + 脱水
 * 往返全程不动 ITEM 列），per-character ITEM 域的立项归组长裁决。
 */
class CharaFullDomainDehydrateTest {

    private val binding = SidecarBinding(uniqueCode = 0x0054_0004L, scriptVersion = 8L, saveTextHash = 41)

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private class Fixture {

        val manifest = ModManifest(
            packageId = PACKAGE_ID,
            components = listOf(
                ComponentDecl(
                    name = "nativeChara",
                    fields = listOf(
                        FieldDecl("cf0", FieldType.INT),
                        FieldDecl("cf1", FieldType.INT),
                        FieldDecl("cf999", FieldType.INT),
                        FieldDecl("tal0", FieldType.INT),
                        FieldDecl("tal999", FieldType.INT),
                        FieldDecl("abl99", FieldType.INT),
                        FieldDecl("mood", FieldType.INT),
                        FieldDecl("memo", FieldType.STR),
                        FieldDecl("note", FieldType.STR),
                    ),
                ),
            ),
            archetypes = listOf(ArchetypeDecl("PERSON", listOf("nativeChara"))),
            relationships = listOf(RelationshipDecl("BOND")),
        )

        val registry = DeclarationRegistry()

        val snapshot: DeclarationSnapshot = run {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
            registry.freeze()
        }

        val kernel = EntityKernel(
            snapshot.componentSchemas.values.map { it.schema }.toList(),
            snapshot.relationshipSchemas.values.map { it.schema }.toList(),
            snapshot.entityArchetypes.values.map { it.archetype }.toList(),
            16,
        )

        val store = VariableStore()
        val pkgs = PackageShortIdRegistry()
        val pkgId: Int = pkgs.allocate(PACKAGE_ID)
        val reg = VirtualCharaNoRegistry()

        /** 用户 CHARADATA 声明（两个装置同序声明，锁追加列下标一致）。 */
        private val moodSpec = DimSpec("M_MOOD", isStr = false, dims = intArrayOf(8), isCharaData = true)
        private val memoSpec = DimSpec("M_MEMO", isStr = true, dims = intArrayOf(4), isCharaData = true)

        /**
         * 字段 → 变量映射行（宿主声明侧业务 schema；域清单 = 冻结字段全集 × 本表）。
         * elem 下标边界：CFLAG / TALENT 出厂尺寸 1000、ABL / CSTR 100（VariableSizes.kt:111-156）。
         */
        private val variableMapping: Map<String, VariableRoute> = mapOf(
            "CF0" to VariableRoute("CFLAG", longArrayOf(0), false),
            "CF1" to VariableRoute("CFLAG", longArrayOf(1), false),
            "CF999" to VariableRoute("CFLAG", longArrayOf(999), false),
            "TAL0" to VariableRoute("TALENT", longArrayOf(0), false),
            "TAL999" to VariableRoute("TALENT", longArrayOf(999), false),
            "ABL99" to VariableRoute("ABL", longArrayOf(99), false),
            "MOOD" to VariableRoute(moodSpec.name, longArrayOf(3), false),
            "MEMO" to VariableRoute(memoSpec.name, longArrayOf(1), true),
            "NOTE" to VariableRoute("CSTR", longArrayOf(2), true),
        )

        /** 全量域清单：冻结 schema 字段全集派生 + 宿主映射行（缺一个字段当场抛）。 */
        val domains: List<NativeCharaField> = snapshot.componentSchemas.values
            .flatMap { it.schema.fields }
            .map { f ->
                val route = requireNotNull(variableMapping[f.name.uppercase(Locale.ROOT)]) {
                    "冻结字段 ${f.name} 无变量映射行——域清单必须全量，缺一个就是静默丢数据"
                }
                NativeCharaField(route.variable, route.elem, f.name, route.isStr)
            }

        val projector = RealCharaSlotProjector(store, kernel, reg, pkgs, domains)
        val sync = RelationProjectionSynchronizer(kernel, projector, "BOND")

        init {
            store.declareUserVar(moodSpec)
            store.declareUserVar(memoSpec)
            store.charaSlotProjector = projector
            projector.attachRelations(sync)
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
        }

        fun newEntity(): Long = kernel.create(pkgId, "PERSON")

        fun saveService() = SaveService(kernel, projector, pkgs, reg)
    }

    /** 一条映射行：变量名 + 角色位之外的下标 + 是否字符串域。 */
    private class VariableRoute(val variable: String, val elem: LongArray, val isStr: Boolean)

    /**
     * 六域逐格期望值（0 值格、负值、Long 大值、边界 elem、空串、非 ASCII 串齐上）。
     * cflag / talent 的 elem → 值逐格给；mood / note 缺省 0 / 空串是**刻意**的：
     * 用例先给组件预置非零、再往行上写 0，证明 capture 读的是活行现值而不是组件残值。
     */
    private class DomainExpectations(
        val cflag: Map<Long, Long> = emptyMap(),
        val talent: Map<Long, Long> = emptyMap(),
        val abl99: Long = 0L,
        val mood: Long = 0L,
        val memo: String = "",
        val note: String = "",
    )

    private val fullDomains = DomainExpectations(
        cflag = mapOf(0L to 42L, 1L to 0L, 999L to 999_777_000_111L),
        talent = mapOf(0L to 7L, 999L to -5L),
        abl99 = 99L,
        memo = "備忘123",
    )

    private fun writeDomainCells(store: VariableStore, row: Int, x: DomainExpectations) {
        val r = row.toLong()
        for ((k, v) in x.cflag) store.writeInt("CFLAG", longArrayOf(r, k), v)
        for ((k, v) in x.talent) store.writeInt("TALENT", longArrayOf(r, k), v)
        store.writeInt("ABL", longArrayOf(r, 99), x.abl99)
        store.writeInt("M_MOOD", longArrayOf(r, 3), x.mood)
        store.writeStr("M_MEMO", longArrayOf(r, 1), x.memo)
        store.writeStr("CSTR", longArrayOf(r, 2), x.note)
    }

    private fun assertKernelFace(kernel: EntityKernel, e: Long, x: DomainExpectations) {
        for ((k, v) in x.cflag) assertEquals(v, kernel.getInt(e, "CF$k"), "CF$k ← CFLAG[$k]")
        for ((k, v) in x.talent) assertEquals(v, kernel.getInt(e, "TAL$k"), "TAL$k ← TALENT[$k]")
        assertEquals(x.abl99, kernel.getInt(e, "ABL99"), "ABL99 ← ABL[99]")
        assertEquals(x.mood, kernel.getInt(e, "MOOD"), "MOOD ← M_MOOD[3]")
        assertEquals(x.memo, kernel.getStr(e, "MEMO"), "MEMO ← M_MEMO[1]")
        assertEquals(x.note, kernel.getStr(e, "NOTE"), "NOTE ← CSTR[2]")
    }

    private fun assertRowFace(store: VariableStore, row: Int, x: DomainExpectations) {
        val r = row.toLong()
        for ((k, v) in x.cflag) assertEquals(v, store.readInt("CFLAG", longArrayOf(r, k)), "CFLAG[$row][$k]")
        for ((k, v) in x.talent) assertEquals(v, store.readInt("TALENT", longArrayOf(r, k)), "TALENT[$row][$k]")
        assertEquals(x.abl99, store.readInt("ABL", longArrayOf(r, 99)), "ABL[$row][99]")
        assertEquals(x.mood, store.readInt("M_MOOD", longArrayOf(r, 3)), "M_MOOD[$row][3]")
        assertEquals(x.memo, store.readStr("M_MEMO", longArrayOf(r, 1)), "M_MEMO[$row][1]")
        assertEquals(x.note, store.readStr("CSTR", longArrayOf(r, 2)), "CSTR[$row][2]")
    }

    // ------------------------------------------------------------------
    // 主对拍：全域写值 → 脱水 → 复原 → 逐字段无损
    // ------------------------------------------------------------------

    @Test
    fun `全域写值_经captureBundle脱水_restoreBundle复原_重投射逐字段无损`() {
        val f = Fixture()
        assertEquals(
            f.snapshot.componentSchemas.values.sumOf { it.schema.fields.size },
            f.domains.size,
            "全量域清单条目 = 冻结 schema 字段全集（F 车回执：宿主按声明事务喂，不硬编码）",
        )
        val e = f.newEntity()
        // 组件预置非零：证明 capture 读活行现值，CF1 / MOOD 的行上 0 能赢过组件残值。
        assertTrue(f.kernel.setInt(e, "CF1", 55L))
        assertTrue(f.kernel.setInt(e, "MOOD", 66L))
        val row = f.projector.project(e)
        assertEquals(3, row, "3 行原生角色之后尾部追加")
        writeDomainCells(f.store, row, fullDomains)

        // 脱水发生在 captureBundle 生产链内部（captureEntityPayload 先 sync 全投射实体）。
        val bundle = f.saveService().captureBundle(binding)
        assertKernelFace(f.kernel, e, fullDomains)

        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.saveService().restoreBundle(bundle, binding))
        assertKernelFace(f2.kernel, e, fullDomains)
        val row2 = f2.projector.project(e)
        assertEquals(3, row2)
        assertEquals(10_000L, f2.store.readInt("NO", longArrayOf(row2.toLong())), "虚拟 NO 随档还原")
        assertRowFace(f2.store, row2, fullDomains)
    }

    @Test
    fun `退场脱水_引擎删原生行_存读档后域值跟实体走`() {
        val f = Fixture()
        val e1Cells = DomainExpectations(cflag = mapOf(0L to 111L), mood = 12L)
        val e2Cells = DomainExpectations(talent = mapOf(0L to 22L), note = "二号")
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        val row1 = f.projector.project(e1)
        val row2 = f.projector.project(e2)
        writeDomainCells(f.store, row1, e1Cells)
        writeDomainCells(f.store, row2, e2Cells)

        val gen = f.projector.rosterGeneration
        assertTrue(f.projector.unproject(e2), "退场成功")
        assertEquals(4, f.store.characters.count, "3 原生 + e1，e2 的行已删")
        assertEquals(gen + 1, f.projector.rosterGeneration, "退场删行递增世代")
        assertKernelFace(f.kernel, e2, e2Cells)
        f.store.deleteCharacter(1) // 原版 DELCHARA 原生行：e1 行 3→2
        assertEquals(3, f.store.characters.count, "2 原生 + e1")
        assertEquals(gen + 2, f.projector.rosterGeneration, "引擎删行也递增世代")
        assertEquals(2, f.projector.charIndexOf(e1))

        val bundle = f.saveService().captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.saveService().restoreBundle(bundle, binding))
        // .rt2 不带原生行（原生行走 .sav 侧）：f2 的 roster 是全新装置的 3 行原生，
        // 重投射行号取 f2 自己的尾部 —— 域值跟实体走，不跟 f1 的行号。
        val r1 = f2.projector.project(e1)
        val r2 = f2.projector.project(e2)
        assertEquals(3, r1)
        assertEquals(4, r2)
        assertRowFace(f2.store, r1, e1Cells)
        assertRowFace(f2.store, r2, e2Cells)
        // e2 在存档前已退场：行删除漏斗回收其虚拟 NO（旧号作废、nextCandidate 不回退），
        // 故 .rt2 里没有 e2 的号；重投射按恢复后的续发游标拿新号 10002（10001 不复用）。
        assertEquals(10_002L, f2.store.readInt("NO", longArrayOf(r2.toLong())), "e2 重投射拿新号")
    }

    // ------------------------------------------------------------------
    // 关系行域
    // ------------------------------------------------------------------

    @Test
    fun `关系行_原生格度数退场回写图_存读档重投射无损`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertTrue(f.kernel.addRelation(e1, "BOND", e2))
        assertTrue(f.kernel.setRelationValue(e1, "BOND", e2, 100L))
        val row1 = f.projector.project(e1)
        val row2 = f.projector.project(e2)
        val key1 = f.reg.virtualNoOf(e1)!!.toLong()
        val key2 = f.reg.virtualNoOf(e2)!!.toLong()
        assertEquals(100L, f.projector.readCell(row1, key2), "进场把图边值写进原生格")
        assertEquals(
            f.store.config.relationDef,
            f.projector.readCell(row2, key1),
            "有向边反方向格 = 默认度数",
        )

        // 原版互动直改关系格（语料 RELATION:A:B 与 100/200 比大小的用法）。
        f.projector.writeCell(row1, key2, 200L)
        assertTrue(f.projector.unproject(e2))
        assertEquals(200L, f.kernel.getRelationValue(e1, "BOND", e2), "退场差分回写：格变化进图")
        assertTrue(f.sync.lastReport!!.isClean)

        val bundle = f.saveService().captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.saveService().restoreBundle(bundle, binding))
        val r1 = f2.projector.project(e1)
        val r2 = f2.projector.project(e2)
        // e2 退场时虚拟 NO 已回收（行生命周期终点 = 绑定终点），旧列键 10001 作废；
        // 关系格列键按重投射后的活 NO 现取（持久身份是 EntityId，不是 NO）。
        val key2b = requireNotNull(f2.reg.virtualNoOf(e2)).toLong()
        assertEquals(
            200L,
            f2.projector.readCell(r1, key2b),
            "重投射进场：图边值写回原生格，度数无损",
        )
        assertEquals(
            f2.store.config.relationDef,
            f2.projector.readCell(r2, key1),
            "反方向格保持默认",
        )
    }

    @Test
    fun `引擎直删投射行_关系格随行蒸发_损失计数可见不假装无损`() {
        val f = Fixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertTrue(f.kernel.addRelation(e1, "BOND", e2))
        assertTrue(f.kernel.setRelationValue(e1, "BOND", e2, 100L))
        val row1 = f.projector.project(e1)
        val row2 = f.projector.project(e2)
        f.projector.writeCell(row1, f.reg.virtualNoOf(e2)!!.toLong(), 200L)

        f.store.deleteCharacter(row2.toLong()) // 原版 DELCHARA 即时语义：不脱水不回写（ADR-005）
        assertEquals(-1, f.projector.charIndexOf(e2))
        assertEquals(1, f.sync.lostWriteBacks, "基线损失计数可见（不静默）")
        assertEquals(100L, f.kernel.getRelationValue(e1, "BOND", e2), "格上 200 随行蒸发，图保持原值")

        val bundle = f.saveService().captureBundle(binding)
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.saveService().restoreBundle(bundle, binding))
        f2.projector.project(e1)
        f2.projector.project(e2)
        assertEquals(
            100L,
            f2.projector.readCell(f2.projector.charIndexOf(e1), f2.reg.virtualNoOf(e2)!!.toLong()),
            "重投射进场格 = 图值（诚实损失，不是 200）",
        )
    }

    // ------------------------------------------------------------------
    // F 车回执行为锁 + ITEM 形状锁
    // ------------------------------------------------------------------

    @Test
    fun `fields空表_只建行维护映射写NO_不搬业务数据`() {
        val f = Fixture()
        val kernel = EntityKernel(
            f.snapshot.componentSchemas.values.map { it.schema }.toList(),
            f.snapshot.relationshipSchemas.values.map { it.schema }.toList(),
            f.snapshot.entityArchetypes.values.map { it.archetype }.toList(),
            16,
        )
        val store = VariableStore()
        val reg = VirtualCharaNoRegistry()
        val empty = RealCharaSlotProjector(store, kernel, reg, null, emptyList())
        store.charaSlotProjector = empty
        repeat(3) { i ->
            val no = (i + 1).toLong()
            store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            store.addCharacter(no)
        }
        val e = kernel.create(f.pkgId, "PERSON")
        val row = empty.project(e)
        assertEquals(3, row, "空域清单：行照建")
        assertEquals(10_000L, store.readInt("NO", longArrayOf(row.toLong())), "空域清单也写 NO 列")
        store.writeInt("CFLAG", longArrayOf(row.toLong(), 0), 42L)
        assertTrue(empty.unproject(e), "退场照常（映射维护在空清单下成立）")
        assertEquals(3, store.characters.count, "退场删行")
        assertEquals(0L, kernel.getInt(e, "CF0"), "不搬业务数据：组件保持出厂 0")
        assertEquals(-1, empty.charIndexOf(e), "退场解映射")
    }

    @Test
    fun `item域_官方语义全局承载_脱水往返全程不动`() {
        val f = Fixture()
        val itemDef = assertNotNull(f.store.registry.def("ITEM"))
        assertFalse(itemDef.isCharacterData, "ITEM 非角色变量（官方 Int1DVariableToken，VariableData.cs:3016）")
        assertEquals(Storage.G_INT1, itemDef.storage, "承载面 = 全局 1 维整数列")
        assertFalse(
            f.domains.any { it.variable == "ITEM" },
            "全量域清单不含 ITEM：角色行上没有它的格子",
        )
        for (i in 0..9) f.store.writeInt("ITEM", longArrayOf(i.toLong()), (i * 7 + 3).toLong())
        val before = (0..9).map { f.store.readInt("ITEM", longArrayOf(it.toLong())) }

        val e = f.newEntity()
        val row = f.projector.project(e)
        writeDomainCells(f.store, row, fullDomains)
        val bundle = f.saveService().captureBundle(binding)
        assertTrue(f.projector.unproject(e))
        val f2 = Fixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.saveService().restoreBundle(bundle, binding))
        f2.projector.project(e)

        val after = (0..9).map { f.store.readInt("ITEM", longArrayOf(it.toLong())) }
        assertEquals(before, after, "per-character 脱水面不触碰全局 ITEM 列（逐格）")
    }
}
