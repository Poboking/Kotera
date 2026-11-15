package com.eratw.port.core.entity

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.CharaRouteDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.RelationshipDecl
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PACKAGE_ID: String = "m54.routes"
private const val ARCHETYPE: String = "PERSON"

/**
 * 用户 CHARADATA 声明（生产 = 语料装载相位 `#DIM CHARADATA` 同路；attach 终审读 def 的
 * 前提）。M_MEMO 是 STR——freeze 期占位 INT、由装置的 attach 终审等价步骤以 def 重造行。
 */
private val MOOD_SPEC = DimSpec("M_MOOD", isStr = false, dims = intArrayOf(8), isCharaData = true)
private val MEMO_SPEC = DimSpec("M_MEMO", isStr = true, dims = intArrayOf(4), isCharaData = true)

/**
 * M5-4 刀 3 端到端逐域对拍（CONTRACT-RT2-M54IMPL3 判据 1/3/5；蓝图 era-arch-v61u.md:185
 * 「不只验 CFLAG」+ :1807 收口条件）。与 [CharaFullDomainDehydrateTest]（手喂映射行时代的
 * 行为锁，零改写保留）的差别在**喂入链**：域清单不再由装置手喂，而是走真声明链——
 * manifest 的 `charaRoutes` 路由声明 → 真 `DeclarationRegistry.freeze` 派生（合成
 * `__native_chara__` 镜像 schema + 逐格 NativeCharaField）→ 投射器第五参。
 *
 * 五类域矩阵（每域都过「写行 → capture/pour 双向 → 退场 → 再 present 逐格回归」）：
 * - CFLAG / TALENT / ABL：系统 1 维整数族经路由声明（显式格）；
 * - CHARADATA：用户角色变量 M_MOOD（INT）+ M_MEMO（STR，占位终审修正靶）经路由声明；
 * - 关系行：原生 RELATION 格经 [RelationProjectionSynchronizer] 关系桥（M5-3 已落路，
 *   本波对拍其在路由域在场时不回归）。
 * - ITEM：形状锁随行——域清单不含 ITEM（官方全局承载，VariableData.cs:3016 在案）。
 *
 * 装置诚实边界：attach 期终审（变量存在性 / 角色性 / dims / STR 占位重造）在生产住在 app
 * 模块 `wireCharaFieldRoutes`（core-vm 看不见 app），本装置按其可观察契约做**等价步骤**
 * （[RoutedFixture.attachVerifiedFields] / [RoutedFixture.kernelSchemas]），终审本体由
 * 刀 2 的 `CharaFieldRouteAssemblyTest` 五用例在生产路径上钉死。
 */
class CharaRouteDomainRoundTripTest {

    private val binding = SidecarBinding(uniqueCode = 0x0054_0003L, scriptVersion = 8L, saveTextHash = 43)

    /** 五类域期望值（0 值格、负值、Long 大值、边界格、空串、非 ASCII 串齐上）。 */
    private class DomainExpectations(
        val cflag: Map<Long, Long> = emptyMap(),
        val talent: Map<Long, Long> = emptyMap(),
        val abl99: Long = 0L,
        val mood: Long = 0L,
        val memo: String = "",
    )

    private val fullDomains = DomainExpectations(
        cflag = mapOf(0L to 42L, 1L to 0L, 999L to 999_777_000_111L),
        talent = mapOf(0L to 7L, 999L to -5L),
        abl99 = 99L,
        mood = 12L,
        memo = "備忘123",
    )

    private fun writeDomainCells(store: VariableStore, row: Int, x: DomainExpectations) {
        val r = row.toLong()
        for ((k, v) in x.cflag) store.writeInt("CFLAG", longArrayOf(r, k), v)
        for ((k, v) in x.talent) store.writeInt("TALENT", longArrayOf(r, k), v)
        store.writeInt("ABL", longArrayOf(r, 99), x.abl99)
        store.writeInt("M_MOOD", longArrayOf(r, 3), x.mood)
        store.writeStr("M_MEMO", longArrayOf(r, 1), x.memo)
    }

    private fun assertKernelFace(tag: String, kernel: EntityKernel, e: Long, x: DomainExpectations) {
        for ((k, v) in x.cflag) assertEquals(v, kernel.getInt(e, "CFLAG:$k"), "$tag 镜像 CFLAG:$k ← 行格")
        for ((k, v) in x.talent) assertEquals(v, kernel.getInt(e, "TALENT:$k"), "$tag 镜像 TALENT:$k ← 行格")
        assertEquals(x.abl99, kernel.getInt(e, "ABL:99"), "$tag 镜像 ABL:99 ← 行格")
        assertEquals(x.mood, kernel.getInt(e, "M_MOOD:3"), "$tag 镜像 M_MOOD:3 ← 行格")
        assertEquals(x.memo, kernel.getStr(e, "M_MEMO:1"), "$tag 镜像 M_MEMO:1 ← 行格（STR 占位修正后）")
    }

    private fun assertRowFace(store: VariableStore, row: Int, x: DomainExpectations) {
        val r = row.toLong()
        for ((k, v) in x.cflag) assertEquals(v, store.readInt("CFLAG", longArrayOf(r, k)), "CFLAG[$row][$k]")
        for ((k, v) in x.talent) assertEquals(v, store.readInt("TALENT", longArrayOf(r, k)), "TALENT[$row][$k]")
        assertEquals(x.abl99, store.readInt("ABL", longArrayOf(r, 99)), "ABL[$row][99]")
        assertEquals(x.mood, store.readInt("M_MOOD", longArrayOf(r, 3)), "M_MOOD[$row][3]")
        assertEquals(x.memo, store.readStr("M_MEMO", longArrayOf(r, 1)), "M_MEMO[$row][1]")
    }

    /**
     * 逐域对拍装置：真声明 → 真 freeze 派生 → 投射器第五参 + 关系桥；域清单来源 =
     * `snapshot.charaFieldRoutes`（刀 1 派生面），不是手喂映射行。
     */
    private class RoutedFixture(routes: List<CharaRouteDecl> = listOf(
        CharaRouteDecl("CFLAG", listOf(0, 1, 999)),
        CharaRouteDecl("TALENT", listOf(0, 999)),
        CharaRouteDecl("ABL", listOf(99)),
        CharaRouteDecl("M_MOOD", listOf(3)),
        CharaRouteDecl("M_MEMO", listOf(1)),
    )) {
        val manifest = ModManifest(
            packageId = PACKAGE_ID,
            components = listOf(
                ComponentDecl(name = "routeCarrier", fields = listOf(FieldDecl("hp", FieldType.INT))),
            ),
            archetypes = listOf(ArchetypeDecl(ARCHETYPE, listOf("routeCarrier"))),
            relationships = listOf(RelationshipDecl("BOND")),
            charaRoutes = routes,
        )

        val snapshot: DeclarationSnapshot = DeclarationRegistry().also {
            it.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
        }.freeze()

        val store = VariableStore()
        val pkgs = PackageShortIdRegistry()
        val pkgId: Int = pkgs.allocate(PACKAGE_ID)
        val reg = VirtualCharaNoRegistry()

        /** attach 终审等价步骤的产物：投射器第五参（生产 = wireCharaFieldRoutes 同形输出）。 */
        val fields: List<NativeCharaField>
        val kernel: EntityKernel
        val projector: RealCharaSlotProjector
        val sync: RelationProjectionSynchronizer

        init {
            // 用户变量先声明（生产序：语料装载相位先于会话装配；终审读 def 的前提）。
            store.declareUserVar(MOOD_SPEC)
            store.declareUserVar(MEMO_SPEC)
            fields = attachVerifiedFields(snapshot, store)
            kernel = EntityKernel(
                kernelSchemas(snapshot, store),
                snapshot.relationshipSchemas.values.map { it.schema }.toList(),
                snapshot.entityArchetypes.values.map { it.archetype }.toList(),
                16,
            )
            projector = RealCharaSlotProjector(store, kernel, reg, pkgs, fields)
            sync = RelationProjectionSynchronizer(kernel, projector, "BOND")
            store.charaSlotProjector = projector
            projector.attachRelations(sync)
            repeat(3) { i ->
                val no = (i + 1).toLong()
                store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
                store.addCharacter(no)
            }
        }

        fun newEntity(): Long = kernel.create(pkgId, ARCHETYPE)

        fun saveService() = SaveService(kernel, projector, pkgs, reg)

        /**
         * attach 期终审的装置等价步骤（生产本体 = app 模块 `wireCharaFieldRoutes` 的
         * `CharaRouteVerdict.review`，四闸同序）：变量存在性（双源：用户变量 ∪ 系统注册表）、
         * 角色性、1 维族单格 + 格号越运行期尺寸、STR 占位以 def 重造行。
         */
        private fun attachVerifiedFields(
            snapshot: DeclarationSnapshot,
            store: VariableStore,
        ): List<NativeCharaField> = snapshot.charaFieldRoutes.routes.map { r ->
            val f = r.field
            val def = store.userVar(f.variable)?.def ?: store.registry.def(f.variable)
            checkNotNull(def) { "路由变量 ${f.variable} 未注册（attach 终审闸 1）" }
            check(def.isCharacterData) { "变量 ${f.variable} 不是角色变量（attach 终审闸 2）" }
            check(
                f.indices.size == 1 && def.dims.size == 1 &&
                    f.indices[0] >= 0 && f.indices[0] < def.dims[0],
            ) { "变量 ${f.variable} 格 ${f.indices.toList()} 越出运行期尺寸 ${def.dims.toList()}（终审闸 3）" }
            if (def.type == VarType.STR && !f.isStr) {
                NativeCharaField(f.variable, f.indices, f.field, true) // 占位 INT → 以 def 重造 STR 行
            } else {
                f
            }
        }

        /**
         * 内核 schema 清单（[EntityKernel] 构造实参）：镜像 schema 里被终审重造的占位字段
         * 同步修正型别（不修正 = pour 静默写空串 / 内核类型闸拒写——刀 2 判据 2 点名的危害）。
         */
        private fun kernelSchemas(
            snapshot: DeclarationSnapshot,
            store: VariableStore,
        ): List<ComponentSchema> {
            val fix = HashMap<String, ComponentFieldType>()
            for (r in snapshot.charaFieldRoutes.routes) {
                val f = r.field
                val def = store.userVar(f.variable)?.def ?: store.registry.def(f.variable)
                if (def != null && def.type == VarType.STR && !f.isStr) {
                    fix[f.field.uppercase(Locale.ROOT)] = ComponentFieldType.STR
                }
            }
            return snapshot.componentSchemas.values.map { d -> d.schema }.map { s ->
                if (fix.isEmpty()) {
                    s
                } else {
                    val fixed = s.fields.map { fs ->
                        val t = fix[fs.name.uppercase(Locale.ROOT)]
                        if (t == null) fs else ComponentFieldSpec(fs.name, t, fs.isEntityRef)
                    }
                    if (fixed == s.fields) s else ComponentSchema(s.packageId, s.name, fixed)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 判据 1 主对拍：五类域写行 → 退场 capture → 再 present pour 逐格回归
    // ------------------------------------------------------------------

    @Test
    fun `五类域写行_退场capture进镜像_再present逐格注回`() {
        val f = RoutedFixture()
        assertEquals(
            8,
            f.fields.size,
            "域清单 = 真声明派生的逐格行（CFLAG 3 + TALENT 2 + ABL 1 + M_MOOD 1 + M_MEMO 1）",
        )
        assertTrue(
            f.fields.all { it.variable != "ITEM" },
            "域清单不含 ITEM（官方全局承载，形状锁随声明面延续）",
        )
        val e = f.newEntity()
        // 组件预置残值：capture 必须读行现值，行上 0 赢过镜像残值。
        assertTrue(f.kernel.setInt(e, "CFLAG:1", 55L))
        assertTrue(f.kernel.setInt(e, "M_MOOD:3", 66L))
        val row = f.projector.project(e)
        assertEquals(3, row, "3 行原生角色之后尾部追加")
        writeDomainCells(f.store, row, fullDomains)
        assertRowFace(f.store, row, fullDomains)

        // 退场 = capture（活行格子 → 镜像字段；生产 = unproject 内 syncFromCharaSlot）。
        assertTrue(f.projector.unproject(e))
        assertKernelFace("退场后", f.kernel, e, fullDomains)
        assertEquals(
            0L,
            f.kernel.getInt(e, "CFLAG:1"),
            "capture 读活行现值：行上 0 赢过 pour 进行的组件残值 55（不是读组件残值）",
        )

        // 再 present = pour（镜像字段 → 活行格子）逐格注回。
        val row2 = f.projector.project(e)
        assertEquals(3, row2)
        assertRowFace(f.store, row2, fullDomains)
    }

    @Test
    fun `五类域_经captureBundle存档同步_restoreBundle重投射逐格回归`() {
        val f = RoutedFixture()
        val e = f.newEntity()
        val row = f.projector.project(e)
        writeDomainCells(f.store, row, fullDomains)

        // 存档同步方向：captureBundle 先对投射实体 sync（capture）再 export（SaveService 不变量）。
        val bundle = f.saveService().captureBundle(binding)
        assertKernelFace("captureBundle 后", f.kernel, e, fullDomains)

        val f2 = RoutedFixture()
        assertIs<SaveService.RestoreOutcome.Applied>(f2.saveService().restoreBundle(bundle, binding))
        assertKernelFace("restoreBundle 后", f2.kernel, e, fullDomains)
        val row2 = f2.projector.project(e)
        assertEquals(3, row2)
        assertEquals(10_000L, f2.store.readInt("NO", longArrayOf(row2.toLong())), "虚拟 NO 随档还原")
        assertRowFace(f2.store, row2, fullDomains)
    }

    // ------------------------------------------------------------------
    // 判据 1 关系行：路由域在场时关系桥对拍不回归（M5-3 已落路）
    // ------------------------------------------------------------------

    @Test
    fun `关系行_路由域在场经关系桥对拍不回归`() {
        val f = RoutedFixture()
        val e1 = f.newEntity()
        val e2 = f.newEntity()
        assertTrue(f.kernel.addRelation(e1, "BOND", e2))
        assertTrue(f.kernel.setRelationValue(e1, "BOND", e2, 100L))
        val row1 = f.projector.project(e1)
        val row2 = f.projector.project(e2)
        val key2 = f.reg.virtualNoOf(e2)!!.toLong()
        // 路由域与关系格并存：同一行上两类域互不串（ERB 写面替身直写路由格）。
        f.store.writeInt("CFLAG", longArrayOf(row1.toLong(), 0), 42L)
        assertEquals(100L, f.projector.readCell(row1, key2), "进场把图边值写进原生格")
        assertEquals(42L, f.store.readInt("CFLAG", longArrayOf(row1.toLong(), 0)), "路由格照常可写")

        // 原版互动直改关系格 → 退场差分回写图（关系桥独占关系格，路由面不碰它）。
        f.projector.writeCell(row1, key2, 200L)
        assertTrue(f.projector.unproject(e2))
        assertEquals(200L, f.kernel.getRelationValue(e1, "BOND", e2), "退场差分回写：格变化进图")
        assertTrue(f.sync.lastReport!!.isClean)

        // e1 退场 = 路由域 capture（unproject 只 capture 退场者自己；e1 退场才轮到它）。
        assertTrue(f.projector.unproject(e1))
        assertEquals(42L, f.kernel.getInt(e1, "CFLAG:0"), "路由域照常 capture，不受关系桥影响")

        // 再 present：路由域 pour 注回 + 图边值写回格（关系桥 pour 方向）。
        val r1 = f.projector.project(e1)
        assertEquals(3, r1)
        assertEquals(42L, f.store.readInt("CFLAG", longArrayOf(r1.toLong(), 0)), "路由域逐格注回")
        val r2 = f.projector.project(e2)
        assertEquals(4, r2)
        assertEquals(200L, f.projector.readCell(r1, key2), "重投射进场：图边值写回原生格，度数无损")
    }

    // ------------------------------------------------------------------
    // 判据 3：未路由格子退场丢失（负向可见——丢失必须发生，不是静默兜底）
    // ------------------------------------------------------------------

    @Test
    fun `未路由格子_退场即丢_负向可见`() {
        val f = RoutedFixture(routes = listOf(CharaRouteDecl("CFLAG", listOf(0, 1))))
        assertEquals(2, f.fields.size, "声明驱动口径：只派生声明的格（R6 v1）")
        val e = f.newEntity()
        // 未声明格未派生镜像字段——内核注册面上无此列（写不进 = 没地方接退场数据）。
        assertFalse(
            f.kernel.setInt(e, "CFLAG:999", 1L),
            "未路由格 CFLAG:999 无镜像列（丢失的结构性原因，不是静默兜底）",
        )
        val row = f.projector.project(e)
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 0), 42L) // 路由格
        f.store.writeInt("CFLAG", longArrayOf(row.toLong(), 999), 777L) // 未路由格

        assertTrue(f.projector.unproject(e))
        assertEquals(42L, f.kernel.getInt(e, "CFLAG:0"), "路由格随实体走")

        val row2 = f.projector.project(e)
        assertEquals(42L, f.store.readInt("CFLAG", longArrayOf(row2.toLong(), 0)), "路由格逐格注回")
        assertEquals(
            0L,
            f.store.readInt("CFLAG", longArrayOf(row2.toLong(), 999)),
            "未路由格退场即丢：再 present 读回出厂 0，写过的 777 不在（负向断言：丢失确实发生）",
        )
    }

    // ------------------------------------------------------------------
    // ITEM 形状锁（声明面下不破）
    // ------------------------------------------------------------------

    @Test
    fun `item域_声明面下全局承载_路由域往返全程不动`() {
        val f = RoutedFixture()
        val itemDef = f.store.registry.def("ITEM")
        assertTrue(itemDef != null && !itemDef.isCharacterData, "ITEM 非角色变量（VariableData.cs:3016 在案）")
        assertEquals(Storage.G_INT1, itemDef!!.storage, "承载面 = 全局 1 维整数列")
        assertFalse(
            f.snapshot.charaFieldRoutes.routes.any { it.field.variable.equals("ITEM", ignoreCase = true) },
            "路由域不含 ITEM：角色行上没有它的格子",
        )
        for (i in 0..9) f.store.writeInt("ITEM", longArrayOf(i.toLong()), (i * 7 + 3).toLong())
        val before = (0..9).map { f.store.readInt("ITEM", longArrayOf(it.toLong())) }

        val e = f.newEntity()
        val row = f.projector.project(e)
        writeDomainCells(f.store, row, fullDomains)
        assertTrue(f.projector.unproject(e))
        val row2 = f.projector.project(e)
        assertRowFace(f.store, row2, fullDomains)

        val after = (0..9).map { f.store.readInt("ITEM", longArrayOf(it.toLong())) }
        assertEquals(before, after, "声明驱动的脱水面不触碰全局 ITEM 列（逐格）")
    }

    // ------------------------------------------------------------------
    // 判据 5：内存账实测读数（RECON TODO-4 收口）
    // ------------------------------------------------------------------

    @Test
    fun `cflag全跨度派生_内存账读数_千格乘列宽乘实体量级`() {
        // 多台装置分段计量：段内只做 SoA 列实体化（字段名一次成型、无显式 GC——detekt
        // ExplicitGarbageCollectionCall 纪律；setInt 对已大写字面不产生新串）。逐台取差、
        // 取最大：某一段撞上自然 young GC 时其余段读数仍干净，最大值 = 单台镜像列足迹。
        val fixtures = List(6) { RoutedFixture(routes = listOf(CharaRouteDecl("CFLAG"))) }
        assertEquals(1000, fixtures[0].fields.size, "全跨度声明 = freeze 以出厂尺寸逐格派生")
        // 实体量级 64：先建满（容量定在 64+）；「× 实体量级」在 SoA 里实现为**列宽**——
        // 列按字段共享、按槽寻址，不按实体逐份复制。
        val entities = fixtures.map { f -> LongArray(64) { f.newEntity() } }
        val columns = Array(1000) { "CFLAG:$it" }
        val heapUsed: () -> Long = { Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() }
        val readings = ArrayList<Long>(fixtures.size)
        var prev = heapUsed()
        for (fx in fixtures) {
            val probe = entities[fixtures.indexOf(fx)][0]
            for (i in 0 until 1000) assertTrue(fx.kernel.setInt(probe, columns[i], i.toLong()))
            val now = heapUsed()
            readings += now - prev
            prev = now
        }
        val perFixtureKb = (readings.maxOrNull() ?: 0L) / 1024
        println(
            "M5-4 内存账读数：CFLAG 全跨度镜像 SoA 列 1000 字段（列宽按容量取整覆盖 64 实体）" +
                "≈ ${perFixtureKb}KB/台（RECON TODO-4 粗算 1000 格 x 8B x 64 = 512KB/族量级）；" +
                "各段读数 KB = ${readings.map { it / 1024 }}",
        )
        assertTrue(
            perFixtureKb > 100,
            "单台镜像列足迹必须可见（RECON 粗算下限 512KB 量级，读数进交付）：$perFixtureKb KB",
        )

        // 实体量级自检（测量段外）：全部 64 实体写满读回——同一批 SoA 列按槽寻址，
        // 实体各写各的值互不串（「× 实体量级」不新增列分配，只占用列宽）。
        for ((idx, fx) in fixtures.withIndex()) {
            for (e in entities[idx]) {
                for (i in 0 until 1000) assertTrue(fx.kernel.setInt(e, columns[i], i.toLong() + idx + 1L))
            }
            assertEquals(137L + idx + 1L, fx.kernel.getInt(entities[idx][3], "CFLAG:137"), "列按槽隔离：实体间互不串值")
        }
    }
}
