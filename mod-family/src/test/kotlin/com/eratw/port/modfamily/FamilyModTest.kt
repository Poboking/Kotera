package com.eratw.port.modfamily

import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 家族 F1 纵向切片（era-arch-v61u.md §23.2 F1）：拿**真实宿主**驱动**真实 mod**，
 * 把「设计能承载」变成「跑起来是这样」。
 *
 * ## 为什么这个文件同时是 R1
 *
 * 原派工单把 R1 定成「今天应当红一朵形状已知的红」的独立台架。W2 的存储容器落地后，
 * 那条判据已经拆成两段并且各归其位：**语义层的往返**由本文件（禁用→保留→重启用）与
 * [ModStorageStoreTest]（编解码字节层）当场验真；**盘上层**的 `.rt2` 落点仍无生产构造点
 * （车次表 C 车）。所以独立红台架**取消**，改成在这里显式登记那条未接线的层 ——
 * 一套只会红在「还没接线」上的测试，量不出进度，只会把真红淹掉（AGENTS §3.1 同一类失效形态）。
 *
 * ## 本文件不含「main 不许 import 引擎」的源码扫描
 *
 * 那条判据由 `mod-family/build.gradle.kts` 的依赖方向在**编译期**保证（§23.0 追加裁定 1）：
 * main source set 的 classpath 上根本没有 `com.eratw.port.core.*`，写都写不出来。
 * 测试里再扫一遍源码是花成本买编译器已经给的东西。
 */
class FamilyModTest {

    /** 同线程直接执行：证 mod 侧调用形状，不证宿主调度器（那是 GameTickDriver 的用例面）。 */
    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private val logs = mutableListOf<String>()

    private fun freeze(vararg manifests: ModManifest): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (manifest in manifests) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest))
        }
        return registry.freeze()
    }

    private fun kernelFor(snapshot: DeclarationSnapshot): EntityKernel = EntityKernel(
        schemas = snapshot.componentSchemas.values.map { it.schema }.toList(),
        relationshipSchemas = snapshot.relationshipSchemas.values.map { it.schema }.toList(),
        archetypes = snapshot.entityArchetypes.values.map { it.archetype }.toList(),
    )

    private class Session(
        val host: ModRuntimeHost,
        val family: FamilyMod,
        val kernel: EntityKernel,
        val store: VariableStore? = null,
    ) {
        val context: ModContext get() = checkNotNull(host.contextOf(FamilyDeclarations.PACKAGE_ID))
        val entities get() = context.entities
        val relationships get() = context.relationships
    }

    private fun start(
        manifests: List<ModManifest> = listOf(FamilyDeclarations.manifest),
        mods: List<Pair<String, EraMod>> = listOf(FamilyDeclarations.PACKAGE_ID to FamilyMod()),
        shortIdOf: (String) -> Int = { pkg -> if (pkg == FamilyDeclarations.PACKAGE_ID) 7 else 8 },
        projector: ICharaSlotProjector? = null,
        store: VariableStore? = null,
        // E 车纵向切片：真投射桥必须和宿主共用**同一个** EntityKernel 实例，
        // 否则 project 的 kernel.exists 查的是另一张表，必然静默返回 -1。
        kernelOverride: EntityKernel? = null,
    ): Session {
        val snapshot = freeze(*manifests.toTypedArray())
        val kernel = kernelOverride ?: kernelFor(snapshot)
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = shortIdOf,
            projector = projector,
        )
        val report = host.install(mods)
        assertEquals(1, report.size)
        assertNull(report.single().failure, "装载不得失败：${report.single()}；日志=$logs")
        assertTrue(report.single().active, "onEnable 后应为启用态")
        return Session(host, mods.single().second as FamilyMod, kernel, store)
    }

    // ------------------------------------------------------------------
    // 1. 第一个真实 EraMod 走完了真实宿主
    // ------------------------------------------------------------------

    @Test
    fun firstRealEraModInstallsAndEnablesThroughTheHost() {
        val session = start()
        assertNotNull(session.host.contextOf(FamilyDeclarations.PACKAGE_ID))
        assertTrue(session.family.state.enabled)
        assertEquals(0L, session.family.state.growthTicks)
        assertEquals(0, session.family.state.registeredChildren)
    }

    // ------------------------------------------------------------------
    // 2. F1 的三件事：建实体 / 写身份 / 挂关系
    // ------------------------------------------------------------------

    @Test
    fun adoptedChildExistsWithItsIdentityAndParentEdge() {
        val session = start()
        val father = session.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        val child = session.family.adoptChild(title = "长女", father = father)

        assertTrue(session.entities.exists(child), "create 之后实体必须存在")
        assertEquals(EntityState.ACTIVE, session.entities.stateOf(child))
        assertEquals("长女", session.entities.getStr(child, FamilyDeclarations.FIELD_TITLE))
        assertEquals(0L, session.entities.getInt(child, FamilyDeclarations.FIELD_AGE))
        assertEquals(father, session.entities.getInt(child, FamilyDeclarations.FIELD_FATHER).let { EntityId(it) })

        assertTrue(
            session.relationships.has(father, FamilyDeclarations.RELATION_PARENT, child),
            "父→子正向边必须挂上",
        )
        assertEquals(listOf(child), session.relationships.neighbors(father, FamilyDeclarations.RELATION_PARENT, true))
        assertTrue(
            session.relationships.has(child, FamilyDeclarations.RELATION_LINEAGE, father),
            "autoInverse 生成的子→父反向边必须在公开面查得到（这就是 lineage 要进五域声明的原因）",
        )
        assertEquals(listOf(father), session.relationships.neighbors(child, FamilyDeclarations.RELATION_LINEAGE, true))
    }

    @Test
    fun childWithoutFatherCarriesNullRefAndNoEdges() {
        val session = start()
        val child = session.family.adoptChild(title = "孤女", father = null)
        assertEquals(EntityId.NULL.raw, session.entities.getInt(child, FamilyDeclarations.FIELD_FATHER))
        assertEquals(emptyList(), session.relationships.neighbors(child, FamilyDeclarations.RELATION_LINEAGE, false))
    }

    @Test
    fun growthTickAdvancesEveryRegisteredChildExactlyOnce() {
        val session = start()
        val a = session.family.adoptChild("甲", father = null)
        val b = session.family.adoptChild("乙", father = null)
        repeat(3) { session.family.advanceOneDay() }
        assertEquals(3L, session.entities.getInt(a, FamilyDeclarations.FIELD_AGE))
        assertEquals(3L, session.entities.getInt(b, FamilyDeclarations.FIELD_AGE))
        assertEquals(3L, session.family.state.growthTicks)
        assertEquals("3", session.context.storage.get(FamilyDeclarations.STORAGE_GROWTH_TICKS))
    }

    @Test
    fun bindingAnUndeclaredJobIsRejectedRatherThanSilentlyAccepted() {
        // 反向证明：如果 onEnable 的 bind 是个 no-op，这条也会「过」—— 所以必须验未声明的那个。
        val session = start()
        assertFailsWith<IllegalArgumentException> {
            session.context.jobs.bind("family.not.declared") {}
        }
    }

    // ------------------------------------------------------------------
    // 3. 已故成员：RETIRED 而不是 destroy（§22.7 第 20 条的实证）
    // ------------------------------------------------------------------

    @Test
    fun deceasedChildKeepsItsIdentityRelationsAndData() {
        val session = start()
        val father = session.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        val child = session.family.adoptChild("次女", father)
        session.entities.setStr(child, FamilyDeclarations.FIELD_TITLE, "次女·成年")
        repeat(2) { session.family.advanceOneDay() }

        session.family.markDeceased(child)

        assertTrue(session.entities.exists(child), "RETIRED 不是删除：身份仍在表上")
        assertEquals(EntityState.RETIRED, session.entities.stateOf(child))
        assertEquals("次女·成年", session.entities.getStr(child, FamilyDeclarations.FIELD_TITLE), "数据留着")
        assertEquals(2L, session.entities.getInt(child, FamilyDeclarations.FIELD_AGE), "历史年龄留着")
        assertTrue(
            session.relationships.has(father, FamilyDeclarations.RELATION_PARENT, child),
            "族谱边留着 —— 这就是不走 destroy 的全部理由",
        )
        assertEquals(0, session.family.state.registeredChildren, "但已退常规轮询，不再长年龄")
    }

    @Test
    fun retiredChildStopsGrowingWhileLivingOnesContinue() {
        val session = start()
        val gone = session.family.adoptChild("故者", null)
        val living = session.family.adoptChild("存者", null)
        session.family.markDeceased(gone)
        repeat(4) { session.family.advanceOneDay() }
        assertEquals(0L, session.entities.getInt(gone, FamilyDeclarations.FIELD_AGE))
        assertEquals(4L, session.entities.getInt(living, FamilyDeclarations.FIELD_AGE))
    }

    // ------------------------------------------------------------------
    // 4. 跨包扩展：不改家族 Mod 也能给孩子加自己的数据（F1 七问第 4 问）
    // ------------------------------------------------------------------

    private class SchoolMod : EraMod {
        companion object {
            const val PACKAGE_ID: String = "demo.school"

            /** 字段键 = 字段名本身（引擎的字段表全局一张，见 [FamilyDeclarations] 同条 KDoc）。 */
            const val FIELD_TRAINING: String = "training"

            val MANIFEST: ModManifest = ModManifest(
                packageId = PACKAGE_ID,
                components = listOf(ComponentDecl("school", listOf(FieldDecl(FIELD_TRAINING, FieldType.INT)))),
            )
        }
        private lateinit var context: ModContext
        var lastChild: EntityId? = null

        override fun onLoad(context: ModContext) {
            this.context = context
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit

        /** 别的包往**家族建的实体**上写自己的字段，家族一行不改。 */
        fun enroll(child: EntityId, hours: Long) {
            lastChild = child
            assertTrue(context.entities.setInt(child, FIELD_TRAINING, hours), "写入必须成功")
        }

        fun hoursOf(child: EntityId): Long = context.entities.getInt(child, FIELD_TRAINING)
    }

    @Test
    fun anotherPackageExtendsTheSameChildWithoutTouchingTheFamilyMod() {
        val snapshot = freeze(FamilyDeclarations.manifest, SchoolMod.MANIFEST)
        val kernel = kernelFor(snapshot)
        val school = SchoolMod()
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { pkg -> if (pkg == FamilyDeclarations.PACKAGE_ID) 7 else 8 },
        )
        val report = host.install(
            listOf(FamilyDeclarations.PACKAGE_ID to FamilyMod(), SchoolMod.PACKAGE_ID to school),
        )
        assertEquals(listOf(null, null), report.map { it.failure }, "两个 mod 都得干净装载：$logs")

        val family = (host.contextOf(FamilyDeclarations.PACKAGE_ID)!!)
        val child = family.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        family.entities.setStr(child, FamilyDeclarations.FIELD_TITLE, "兼读生")
        school.enroll(child, hours = 12L)

        assertEquals(12L, school.hoursOf(child), "学校包写的字段读得回来")
        assertEquals("兼读生", family.entities.getStr(child, FamilyDeclarations.FIELD_TITLE), "家族包的字段不受影响")
        assertEquals(
            family.entities.getInt(child, FamilyDeclarations.FIELD_AGE),
            school.hoursOf(child) - 12L,
            "字段命名空间是全局一张表，隔离靠名字不重名：家族读 age、学校读 training，互不串值",
        )
    }

    // ------------------------------------------------------------------
    // 5. 跨包身份不撞号：P0-1 契约陷阱的守卫
    // ------------------------------------------------------------------

    @Test
    fun entitiesFromDifferentPackagesDifferInThePackageBitsOfTheirId() {
        val snapshot = freeze(FamilyDeclarations.manifest, SchoolMod.MANIFEST)
        val kernel = kernelFor(snapshot)
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            // 真实注入（不是 { 1 } 那个测试假默认）：两个包必须拿到不同的 pkgShortId。
            shortIdOf = { pkg -> if (pkg == FamilyDeclarations.PACKAGE_ID) 7 else 8 },
        )
        host.install(
            listOf(FamilyDeclarations.PACKAGE_ID to FamilyMod(), SchoolMod.PACKAGE_ID to SchoolMod()),
        )
        val familyCtx = host.contextOf(FamilyDeclarations.PACKAGE_ID)!!
        val schoolCtx = host.contextOf(SchoolMod.PACKAGE_ID)!!
        val a = familyCtx.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        val b = schoolCtx.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        assertNotEqualRaw(a, b)
        assertEquals(7, EntityIdCodec.pkgShortIdOf(a.raw), "家族包的 shortId 必须落在身份里")
        assertEquals(8, EntityIdCodec.pkgShortIdOf(b.raw))
        // 夹具自检（订正 v6.9-c 工单口径）：单内核只有一张**全局**槽表，create 走
        // allocateSlot()，槽不随包分空间（EntityKernel.kt:150-165）⇒ 一个内核里的两个包
        // 必然占不同槽，于是本用例证的是「宿主把对的 pkgShortId 盖进了身份」，
        // **不证**同槽跨包不撞号（两 id 的槽本来就不同，撞不撞号看不出来）。
        assertNotEquals(EntityIdCodec.handleSlotOf(a.raw), EntityIdCodec.handleSlotOf(b.raw))
        // 同槽跨包不撞号要在编码层证：把同一槽显式盖到两个包上，位段必须分开。
        val sameSlotInPkg7 = EntityIdCodec.encode(7, 0, 5)
        val sameSlotInPkg8 = EntityIdCodec.encode(8, 0, 5)
        assertTrue(sameSlotInPkg7 != sameSlotInPkg8, "同槽跨包必须得到不同身份（撞号 = 串数据）")
        assertEquals(5, EntityIdCodec.handleSlotOf(sameSlotInPkg7))
        assertEquals(5, EntityIdCodec.handleSlotOf(sameSlotInPkg8))
    }

    private fun assertNotEqualRaw(a: EntityId, b: EntityId) {
        assertTrue(a.raw != b.raw, "两个包的实体身份不得相同（撞号 = 串数据）")
    }

    // ------------------------------------------------------------------
    // 6. 禁用 → 数据保留 → 重启用 → 原值回来（语义层；盘上层见 C 车）
    // ------------------------------------------------------------------

    @Test
    fun disablingTheModPreservesItsStorageAndReenablingRestoresTheValues() {
        val session = start()
        repeat(5) { session.family.advanceOneDay() }
        session.host.shutdown() // 走 onDisable，把计数写进 mod 存储

        val bytes = session.host.storage.encode()
        assertTrue(bytes.isNotEmpty())

        // 下一次会话：家族 mod 被禁用（不在 knownPackages 里）。
        val offline = ModStorageStore.load(bytes, knownPackages = emptySet())
        assertEquals(setOf(FamilyDeclarations.PACKAGE_ID), offline.inactivePackages())
        assertContentEquals(bytes, offline.encode(), "禁用态原样再编码必须逐字节相同")

        // 玩家重新启用它。
        offline.activate(FamilyDeclarations.PACKAGE_ID)
        val api = offline.apiFor(FamilyDeclarations.PACKAGE_ID)
        assertEquals("5", api.get(FamilyDeclarations.STORAGE_GROWTH_TICKS), "重启用必须拿回原计数，不是 0")
    }

    // ------------------------------------------------------------------
    // 7. 声明与生成的访问器同源（§22.7 第 24 条的真源风险）
    // ------------------------------------------------------------------

    @Test
    fun declarationsAndGeneratedAccessorsDescribeTheSameSchema() {
        val component = FamilyDeclarations.manifest.components.single()
        assertEquals(FamilyDeclarations.COMPONENT_FAMILY, component.name)
        assertEquals(
            listOf("age", "father", "title"),
            component.fields.map { it.name }.sorted(),
            "字段清单变了就必须重跑 :tools:modgen-accessors 的 generateFamilyAccessors",
        )
        // 字段键口径 = 声明里的字段名本身（引擎按这张全局表寻址，组件名不参与）。
        // 这一条就是「生成器拼了 组件名.字段名」那笔静默错数据的闸：常量对不上声明 ⇒ 红。
        assertEquals(
            component.fields.map { it.name }.toSet(),
            setOf(FamilyDeclarations.FIELD_AGE, FamilyDeclarations.FIELD_TITLE, FamilyDeclarations.FIELD_FATHER),
            "字段常量必须逐项等于 FieldDecl 的名字，否则读写全部落在失败哨兵上",
        )
        assertEquals("age", FamilyDeclarations.FIELD_AGE)
        assertEquals("title", FamilyDeclarations.FIELD_TITLE)
        assertEquals("father", FamilyDeclarations.FIELD_FATHER)
        // EntityRef 只能是整型（core 的不变量）；这里钉住声明层不漂移。
        assertTrue(component.fields.single { it.name == "father" }.isEntityRef)
        assertEquals(FieldType.INT, component.fields.single { it.name == "father" }.type)
    }

    @Test
    fun archetypeAndRelationshipAreDeclaredThroughTheSameManifest() {
        val manifest = FamilyDeclarations.manifest
        assertEquals(
            listOf(FamilyDeclarations.ARCHETYPE_CHILD),
            manifest.archetypes.map { it.name },
        )
        assertEquals(
            listOf(FamilyDeclarations.COMPONENT_FAMILY),
            manifest.archetypes.single().components,
        )
        val parent = manifest.relationships.single { it.name == FamilyDeclarations.RELATION_PARENT }
        assertTrue(parent.autoInverse, "反向边由声明生成，业务不手写第二遍")
        assertEquals(FamilyDeclarations.RELATION_LINEAGE, parent.inverseName)
        assertFalse(parent.cascadeOnDestroy, "族谱不得因为一次 destroy 就被抹边（KDoc 有裁定）")
        // 反向边类型必须也在五域里声明，否则 freeze 直接红（DeclarationRegistry.kt:582-594）。
        val lineage = manifest.relationships.single { it.name == FamilyDeclarations.RELATION_LINEAGE }
        assertFalse(lineage.autoInverse, "parent↔lineage 不得互相折返")
        assertNull(lineage.inverseName, "未启用 autoInverse 时带 inverseName 是声明层非法形状")
        assertFalse(lineage.cascadeOnDestroy, "两个方向的级联口径必须一致，否则半边族谱会先被抹")
    }

    // ------------------------------------------------------------------
    // 8. RT2 车次表 E 车 / F2：真投射器注入真宿主 → 家族经公开面上下场
    //    （真 DeclarationRegistry.freeze + 真 EntityKernel + 真 VariableStore
    //      + 真 RealCharaSlotProjector + 真 ModRuntimeHost + 真 FamilyMod）
    // ------------------------------------------------------------------

    /** 三行原生角色 + 真投射桥；shortId 用真实注册表分配，不是 { 1 } 假默认。 */
    private fun startProjected(): Session {
        val pkgs = PackageShortIdRegistry()
        val shortId = pkgs.allocate(FamilyDeclarations.PACKAGE_ID)
        val snapshot = freeze(FamilyDeclarations.manifest)
        val kernel = kernelFor(snapshot)
        val store = VariableStore()
        repeat(3) { i ->
            val no = (i + 1).toLong()
            store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            store.addCharacter(no)
        }
        val projector = RealCharaSlotProjector(store, kernel, VirtualCharaNoRegistry(), pkgs)
        store.charaSlotProjector = projector
        return start(
            shortIdOf = { shortId },
            projector = projector,
            store = store,
            kernelOverride = kernel,
        )
    }

    @Test
    fun familyModPresentsAndWithdrawsAChildThroughThePublicRosterFacade() {
        val session = startProjected()
        val store = checkNotNull(session.store)
        val father = session.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        val child = session.family.adoptChild("登场子", father = father)

        // P1-6 ②：生成访问器经 setRef 写的父引用，公开 getRef 与 INT 列逐位同源。
        assertEquals(father, session.entities.getRef(child, FamilyDeclarations.FIELD_FATHER))
        assertEquals(father.raw, session.entities.getInt(child, FamilyDeclarations.FIELD_FATHER))

        assertEquals(3, store.characters.count, "装置先铺 3 行原生角色")
        val generation = session.family.rosterGeneration
        val row = session.family.presentChild(child)
        assertEquals(3, row, "ADDVOIDCHARA 语义：伪角色尾部追加在末行")
        assertEquals(4, store.characters.count, "登场后行数 +1")
        assertEquals(1, session.family.presentedChildren)
        assertEquals(row, session.family.charIndexOfPresentedChild(child), "同世代现换 CHARA 命中")
        assertEquals(generation, session.family.rosterGeneration, "尾增不递增世代")

        assertTrue(session.family.withdrawChild(child), "退场必须成功")
        assertEquals(3, store.characters.count, "退场后行数 -1")
        assertEquals(0, session.family.presentedChildren)
        assertEquals(generation + 1, session.family.rosterGeneration, "删行递增世代")
        assertEquals(-1, session.family.charIndexOfPresentedChild(child), "过期世代不再换得行号")
        assertTrue(session.entities.exists(child), "退场只撤角色行，不删扩展实体（§7）")
    }

    @Test
    fun familyModRosterCallsFailClosedWithoutProjectorWiring() {
        // F 车生产构造点未接线时，家族 mod 的投射调用不得静默拿到 -1/false/0。
        val session = start()
        val child = session.family.adoptChild("未接线子", father = null)
        val failure = assertFailsWith<UnsupportedOperationException> { session.family.presentChild(child) }
        assertTrue("F 车" in failure.message.orEmpty(), "异常必须点名 F 车装配缺口：${failure.message}")
        assertEquals(0, session.family.presentedChildren)
    }
}
