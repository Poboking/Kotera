package com.eratw.port.modfamily

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.NativeCharaField
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.add
import com.eratw.port.modapi.get
import com.eratw.port.modapi.has
import com.eratw.port.modapi.neighbors
import com.eratw.port.modapi.put
import com.eratw.port.modapi.remove
import com.eratw.port.modapi.setValue
import com.eratw.port.modapi.valueOf
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modapi.create
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.setStr
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.setRef
import com.eratw.port.modapi.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * family F2 纵向切片 × M5-4 路由声明（CONTRACT-RT2-M54IMPL3 判据 2；RECON-RT2-M54 R5
 * 「第一消费者 = family 子嗣」的端到端验收）：**真宿主 + 真内核 + 真 freeze**（基建照
 * `FamilyModTest.startProjected` 的 E 车切片，那份文件零改写——本文件自带等价装置）。
 *
 * 喂入链全真：`FamilyDeclarations.manifest` 的 F2 路由声明（ABL/EXP/JUEL/CFLAG 全跨度）→
 * 真 `DeclarationRegistry.freeze` 派生（`snapshot.charaFieldRoutes`）→ 投射器第五参。
 * 装置不手喂任何映射行；attach 期终审的 app 侧本体（`wireCharaFieldRoutes`）由刀 2 的
 * `CharaFieldRouteAssemblyTest` 在生产路径钉死——系统族 isStr 由 freeze 正确派生、格号
 * 以出厂尺寸展开，装置直连投射器与生产输出同形。
 *
 * F2 业务闭环（era-arch-v61u.md:1807 的业务反推）：没有路由面时 `withdrawChild` = ERB
 * 写的育成数据全蒸发（空表行为锁钉住的「不搬」语义）；有路由面后 ——
 * presentChild → 育成事件写行（ABL/EXP/JUEL/CFLAG）→ withdrawChild（capture 进实体负载）
 * → 再 presentChild（pour 逐格注回）。
 */
class FamilyCharaRouteSliceTest {

    /** 同线程直接执行：证 mod 侧调用形状，不证宿主调度器（与 FamilyModTest 同口径）。 */
    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private class Session(
        val host: ModRuntimeHost,
        val family: FamilyMod,
        val kernel: EntityKernel,
        val store: VariableStore,
        val routes: List<NativeCharaField>,
    ) {
        val context: ModContext get() = checkNotNull(host.contextOf(FamilyDeclarations.PACKAGE_ID))
        val entities get() = context.entities
    }

    /** 真 freeze 派生（可给替代 manifest 做无路由对照）+ 真内核 + 真投射器 + 真宿主。 */
    private fun start(manifestOverride: ModManifest? = null): Session {
        val manifest = manifestOverride ?: FamilyDeclarations.manifest
        val pkgs = PackageShortIdRegistry()
        val shortId = pkgs.allocate(FamilyDeclarations.PACKAGE_ID)
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
        val snapshot: DeclarationSnapshot = registry.freeze()
        val routes = snapshot.charaFieldRoutes.routes.map { it.field }
        val kernel = EntityKernel(
            snapshot.componentSchemas.values.map { it.schema }.toList(),
            snapshot.relationshipSchemas.values.map { it.schema }.toList(),
            snapshot.entityArchetypes.values.map { it.archetype }.toList(),
        )
        val store = VariableStore()
        repeat(3) { i ->
            val no = (i + 1).toLong()
            store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            store.addCharacter(no)
        }
        val projector = RealCharaSlotProjector(store, kernel, VirtualCharaNoRegistry(), pkgs, routes)
        store.charaSlotProjector = projector
        val mod = FamilyMod()
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { },
            runtime = InlineRuntime(),
            shortIdOf = { shortId },
            projector = projector,
        )
        // per-mod 设置面同生产序（install 前注入）；声明默认值当测试替身，不改载体语义。
        host.settings = mapOf(
            FamilyDeclarations.PACKAGE_ID to ModSettingsSnapshot(
                FamilyDeclarations.PACKAGE_ID,
                ModDeclarationAdapter.toDescriptor(manifest).settings,
            ),
        )
        val report = host.install(listOf(FamilyDeclarations.PACKAGE_ID to mod))
        assertEquals(1, report.size)
        assertNull(report.single().failure, "装载不得失败：${report.single().failure}")
        assertTrue(report.single().active, "onEnable 后应为启用态")
        return Session(host, mod, kernel, store, routes)
    }

    // ------------------------------------------------------------------
    // F2 主切片：presentChild → 写行 → withdrawChild → 再 presentChild 逐格回归
    // ------------------------------------------------------------------

    @Test
    fun `f2切片_present写行_withdraw脱水_再present逐格回归`() {
        val s = start()
        // 装置自证：域清单 = 声明派生的全跨度逐格行（ABL 100 + EXP 100 + JUEL 200 + CFLAG 1000）。
        assertEquals(1400, s.routes.size, "四族全跨度派生（出厂尺寸权威，mod 不写尺寸）")

        val father = s.entities.create(FamilyKeys.archetype)
        val child = s.family.adoptChild("育成子", father = father)
        assertEquals(3, s.family.presentChild(child), "ADDVOIDCHARA 语义：伪角色尾部追加")
        val row = s.family.charIndexOfPresentedChild(child)
        assertTrue(row >= 0, "登场行号同世代现换命中")

        // 育成事件写面（RECON Q5 调用场景：ERB 按行号写四族格子的替身——引擎写行走同一 store 面）。
        s.store.writeInt("ABL", longArrayOf(row.toLong(), 2), 31L)
        s.store.writeInt("EXP", longArrayOf(row.toLong(), 5), 120L)
        s.store.writeInt("JUEL", longArrayOf(row.toLong(), 10), 77L)
        s.store.writeInt("CFLAG", longArrayOf(row.toLong(), 17), 9L)

        // withdrawChild = unproject = capture：行格子进实体镜像（没有路由面时这一步就是数据蒸发）。
        assertTrue(s.family.withdrawChild(child), "退场必须成功")
        assertEquals(31L, s.entities.getInt(child, "ABL:2"), "ABL 育成值随实体走")
        assertEquals(120L, s.entities.getInt(child, "EXP:5"), "EXP 育成值随实体走")
        assertEquals(77L, s.entities.getInt(child, "JUEL:10"), "JUEL 育成值随实体走")
        assertEquals(9L, s.entities.getInt(child, "CFLAG:17"), "CFLAG 里程碑随实体走")

        // 再 presentChild = project = pour：镜像逐格注回新行（成长数据回归，不跟旧行号走）。
        val row2 = s.family.presentChild(child)
        assertEquals(3, row2, "退场删行后尾部追加：数据跟实体走")
        assertEquals(31L, s.store.readInt("ABL", longArrayOf(row2.toLong(), 2)), "ABL 逐格注回")
        assertEquals(120L, s.store.readInt("EXP", longArrayOf(row2.toLong(), 5)), "EXP 逐格注回")
        assertEquals(77L, s.store.readInt("JUEL", longArrayOf(row2.toLong(), 10)), "JUEL 逐格注回")
        assertEquals(9L, s.store.readInt("CFLAG", longArrayOf(row2.toLong(), 17)), "CFLAG 逐格注回")

        // 成长拍只动实体权威字段（age），路由域镜像不受 advanceOneDay 影响（路由域 ≠ mod 字段）。
        s.family.advanceOneDay()
        assertEquals(1L, s.entities.getInt(child, FamilyKeys.age))
        assertEquals(31L, s.entities.getInt(child, "ABL:2"), "成长拍不动路由域镜像")
        s.host.shutdown()
    }

    @Test
    fun `f2切片_未路由格退场即丢_负向可见`() {
        val s = start()
        val child = s.family.adoptChild("切片子", father = null)
        val row = s.family.presentChild(child)
        assertTrue(row >= 0)
        // 路由格（ABL:2 ∈ 全跨度声明）与未路由格（CSTR 不在 F2 声明，KDoc 裁定）同写。
        s.store.writeInt("ABL", longArrayOf(row.toLong(), 2), 31L)
        s.store.writeStr("CSTR", longArrayOf(row.toLong(), 3), "未路由的行上串")

        assertTrue(s.family.withdrawChild(child))
        assertEquals(31L, s.entities.getInt(child, "ABL:2"), "路由格随实体走")
        // CSTR 未声明 = 未派生镜像字段 = 内核面上没有接退场数据的格子。
        assertFalse(
            s.entities.setStr(child, "CSTR:3", "不该写进去"),
            "CSTR 未路由：镜像字段不存在，内核注册闸拒写",
        )

        val row2 = s.family.presentChild(child)
        assertEquals(31L, s.store.readInt("ABL", longArrayOf(row2.toLong(), 2)), "路由格逐格注回")
        assertEquals(
            "",
            s.store.readStr("CSTR", longArrayOf(row2.toLong(), 3)),
            "未路由格退场即丢：再 present 读回出厂空串（ADR-005 诚实损失可见，丢失必须发生）",
        )
        s.host.shutdown()
    }

    // ------------------------------------------------------------------
    // F2 路由族选择纪律（§23.3：按调用场景选，不罗列）
    // ------------------------------------------------------------------

    @Test
    fun `f2路由族声明_四族全跨度_不罗列`() {
        val routes = FamilyDeclarations.manifest.charaRoutes
        assertEquals(
            listOf("ABL", "EXP", "JUEL", "CFLAG"),
            routes.map { it.variable.uppercase() },
            "F2 场景族 = 训练写面四族（声明序保真；KDoc 裁定 CSTR 不进 v1）",
        )
        assertTrue(
            routes.all { it.cells.isEmpty() },
            "全跨度声明（cells 空 = freeze 以出厂尺寸展开）：ERB 育成写面按任意格号写，族级一行覆盖千格",
        )
        assertFalse(
            routes.any { it.variable.equals("CSTR", ignoreCase = true) },
            "CSTR 刻意不声明（契约候选池第五族）：F2 调用场景无字符串行落点，解锁路径 = 补一行声明",
        )
    }

    // ------------------------------------------------------------------
    // 无路由对照：F2 业务反推的基线（同宿主摘掉路由声明，withdraw 不搬育成数据）
    // ------------------------------------------------------------------

    @Test
    fun `无路由对照_退场不搬育成数据_f2业务反推基线`() {
        // 声明里的路由摘掉（copy 空表）冻结：镜像不派生、fields 空表——present/withdraw
        // 照常建行维护映射，但育成数据不随实体走（这正是没有路由面时的业务损失形态）。
        val routeless = FamilyDeclarations.manifest.copy(charaRoutes = emptyList())
        val s = start(routeless)
        assertTrue(s.routes.isEmpty() && s.routes.isEmpty(), "无路由声明 ⇒ 派生清单空（刀 1 判据 5 同源）")

        val child = s.family.adoptChild("无路由子", father = null)
        val row = s.family.presentChild(child)
        assertEquals(3, row, "无路由面：行照建（空表行为锁语义）")
        s.store.writeInt("ABL", longArrayOf(row.toLong(), 2), 31L)
        assertTrue(s.family.withdrawChild(child))
        assertEquals(0L, s.entities.getInt(child, "ABL:2"), "无镜像字段：组件保持出厂 0（数据蒸发的基线形状）")
        assertEquals(-1, s.family.charIndexOfPresentedChild(child), "退场解映射")
        s.host.shutdown()
    }
}
