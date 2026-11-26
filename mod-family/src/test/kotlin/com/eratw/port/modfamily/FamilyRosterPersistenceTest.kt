package com.eratw.port.modfamily

import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.add
import com.eratw.port.modapi.create
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.put
import com.eratw.port.modapi.query
import com.eratw.port.modapi.setInt
import com.eratw.port.modfamily.generated.FamilyKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 子嗣名单的存档往返（task-46）：名单的真源是 mod 存储键 [FamilyDeclarations.CHILD_IDS]，
 * [FamilyMod.onSaveLoaded] 从它重建进程内缓存，重建时用 EntityStore 校验身份。
 *
 * ## 为什么模拟「先 onLoad 再恢复侧车」
 *
 * 生产读档缝里，`.rt2` 侧车（实体段与 mod 存储段）的恢复发生在首轮 onLoad/onEnable **之后**、
 * onSaveLoaded **之前**（见 `ModRuntimeHost` 的时序说明）。本文件的 [reload] 严格照这个顺序搭：
 * 新宿主 install（onLoad 读到空存储）→ `rebindStorage` 换上恢复后的存储 → `notifySaveLoaded`。
 * 于是「只靠 onLoad 还原必然为空」这条生产事实在测试里被钉住，而不是用内存台架的巧合盖掉。
 *
 * ## 名单不是 `query(archetype)` 派生
 *
 * `family.child` 原型里既有 adoptChild 登记的成员，也有外部直接 create 的家人实体（父亲、旁支）；
 * schema 没有「是否登记」字段，纯 query 派生会把两者混为一谈。故本文件用
 * [entityStoreOnlyEntitiesAreNotRosterMembers] 钉住反向边界：**EntityStore 是身份的权威源，
 * 不是名单的真源** —— 它只负责校验名单里的 id 还活着。
 */
class FamilyRosterPersistenceTest {

    private class InlineRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    /**
     * [store] 是读档后重绑进各 context 的存储容器（新会话为 null，此时 [ModRuntimeHost.storage]
     * 就是本会话自己的容器）。再存一次要从它 encode：`rebindStorage` 只重绑 context 视图，
     * 不会替换宿主侧那个属性 —— 拿 `host.storage.encode()` 会读到空段。
     */
    private class Session(
        val host: ModRuntimeHost,
        val mod: FamilyMod,
        val kernel: EntityKernel,
        val store: ModStorageStore? = null,
    ) {
        val entities get() = checkNotNull(host.contextOf(FamilyDeclarations.PACKAGE_ID)).entities
        val relationships get() = checkNotNull(host.contextOf(FamilyDeclarations.PACKAGE_ID)).relationships
    }

    private val logs = mutableListOf<String>()

    private fun kernelFor(): EntityKernel {
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(FamilyDeclarations.manifest))
        val snapshot = registry.freeze()
        return EntityKernel(
            schemas = snapshot.componentSchemas.values.map { it.schema }.toList(),
            relationshipSchemas = snapshot.relationshipSchemas.values.map { it.schema }.toList(),
            archetypes = snapshot.entityArchetypes.values.map { it.archetype }.toList(),
        )
    }

    /** 装一个真实家族 Mod；[storageBytes] 非空时按生产读档顺序在 onLoad 之后换上恢复后的存储。 */
    private fun install(kernel: EntityKernel, storageBytes: ByteArray? = null): Session {
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(FamilyDeclarations.manifest))
        val snapshot = registry.freeze()
        val host = ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = InlineRuntime(),
            shortIdOf = { 7 },
        )
        host.settings = mapOf(
            FamilyDeclarations.PACKAGE_ID to ModSettingsSnapshot(
                FamilyDeclarations.PACKAGE_ID,
                ModDeclarationAdapter.toDescriptor(FamilyDeclarations.manifest).settings,
            ),
        )
        val mod = FamilyMod()
        val report = host.install(listOf(FamilyDeclarations.PACKAGE_ID to mod))
        assertEquals(1, report.size)
        assertNull(report.single().failure, "装载不得失败：${report.single()}；日志=$logs")
        if (storageBytes != null) {
            val store = ModStorageStore.load(storageBytes, knownPackages = setOf(FamilyDeclarations.PACKAGE_ID))
            host.rebindStorage(store)
            return Session(host, mod, kernel, store)
        }
        return Session(host, mod, kernel)
    }

    private fun start(): Session = install(kernelFor())

    /** 存档 → 新会话：导出实体快照与 mod 存储，新宿主装同一 mod，按生产时序恢复侧车。 */
    private fun reload(session: Session): Session {
        val entitySnapshot = session.kernel.exportSnapshot()
        val storageBytes = session.store?.encode() ?: session.host.storage.encode()
        assertFalse(storageBytes.isEmpty(), "存档必须写出非空 mod 存储段")
        val kernel = kernelFor()
        kernel.restore(entitySnapshot)
        return install(kernel, storageBytes)
    }

    @Test
    fun rosterIsWrittenThroughAndRebuiltOnlyAfterSaveLoaded() {
        val session = start()
        val father = session.entities.create(FamilyKeys.archetype)
        val elder = session.mod.adoptChild(title = "长女", father = father)
        val younger = session.mod.adoptChild(title = "次女", father = father)
        session.entities.setInt(elder, FamilyKeys.age, 10L)
        session.entities.setInt(younger, FamilyKeys.age, 3L)

        val persisted = session.host.storage.apiFor(FamilyDeclarations.PACKAGE_ID)
        assertEquals(
            "${elder.raw},${younger.raw}",
            persisted.get(FamilyDeclarations.CHILD_IDS),
            "登记必须写穿到持久真源（顺序 = 登记序）",
        )

        val reloaded = reload(session)
        assertEquals(0, reloaded.mod.state.registeredChildren, "侧车恢复晚于 onLoad：此刻缓存还只能是空的")
        reloaded.host.notifySaveLoaded()
        assertEquals(2, reloaded.mod.state.registeredChildren, "onSaveLoaded 必须把名单重建回来")
        assertEquals(elder, reloaded.mod.chooseSuccessor(father), "读档后仍能枚举：长嗣优先选长子")

        // 退场也要写穿：标记死亡后再存档、再读档，不能复活。
        reloaded.mod.markDeceased(elder)
        val afterDeath = reload(reloaded)
        afterDeath.host.notifySaveLoaded()
        assertEquals(1, afterDeath.mod.state.registeredChildren, "已退场的子嗣不得随存档回来")
        assertEquals(younger, afterDeath.mod.chooseSuccessor(father))
        assertEquals(EntityState.RETIRED, afterDeath.entities.stateOf(elder), "死亡只改状态、身份与关系仍留着")
    }

    @Test
    fun restoredRosterStillFeedsDailyGrowth() {
        val session = start()
        val child = session.mod.adoptChild(title = "独子", father = null)
        val reloaded = reload(session)
        reloaded.host.notifySaveLoaded()

        reloaded.mod.advanceOneDay()
        assertEquals(1L, reloaded.entities.getInt(child, FamilyKeys.age), "重建后的名单必须继续参与成长")
        assertEquals(1L, reloaded.mod.state.growthTicks)
    }

    /**
     * 钉子（存档计数覆盖 Minor）：成长计数是随存档走的实例状态，必须在
     * [com.eratw.port.modapi.EraMod.onSaveLoaded] 与名册一同**采纳**。生产对同类「重复/连续读档」
     * 计数的语义是从存档采纳、相等即 no-op（app `ModDecisionJobState.adoptSeedFromSave`，
     * ModRuntimeAssembly.kt:1111），而不是在 onLoad 读到的空值上继续累加 —— 后者会把存档里
     * 已推进的计数覆盖成 1。修复前本测试在第一个断言即失败（读档后 growthTicks 恒为 0）。
     */
    @Test
    fun growthTicksAreAdoptedFromSaveAcrossConsecutiveReloads() {
        // 连续推进 15 拍：存储里的计数就是本次存档真值。
        val first = start()
        repeat(15) { first.mod.advanceOneDay() }
        // 有侧车的会话读重绑后的 store，无侧车的新会话读宿主容器（rebindStorage 不换宿主属性）。
        val ticksOf = { s: Session ->
            (s.store ?: s.host.storage).apiFor(FamilyDeclarations.PACKAGE_ID)
                .get(FamilyDeclarations.GROWTH_TICKS)
        }
        assertEquals("15", ticksOf(first), "夹具自检：存档计数应为 15")

        // 生产读档缝：install（onLoad 读到装配期空存储）→ rebindStorage → notifySaveLoaded。
        val second = reload(first)
        second.host.notifySaveLoaded()
        assertEquals(15L, second.mod.state.growthTicks, "读档后必须采纳存档里的 15，而不是 onLoad 看到的 0")

        // 下一拍必须在存档计数上继续推进，不能从 0 写回、覆盖真值。
        second.mod.advanceOneDay()
        assertEquals(16L, second.mod.state.growthTicks, "下一拍应在存档计数基础上推进")
        assertEquals("16", ticksOf(second), "写回值不得被覆盖成 1")

        // 连续再读两次同一张档：计数被原样采纳，不叠加、不回卷（对齐 adoptSeedFromSave 的 no-op 语义）。
        val third = reload(second)
        third.host.notifySaveLoaded()
        assertEquals(16L, third.mod.state.growthTicks, "连续读档不得改变存档计数")
        val fourth = reload(second)
        fourth.host.notifySaveLoaded()
        assertEquals(16L, fourth.mod.state.growthTicks, "重复读同一张档，计数仍应是 16")
    }

    @Test
    fun persistedIdsMissingFromEntityStoreAreDropped() {
        val session = start()
        val child = session.mod.adoptChild(title = "真子", father = null)
        // 槽 0 世代 1：合法位形，但 EntityStore 里没有这张身份（原始存档里被清掉的幽灵）。
        val ghost = EntityId(EntityIdCodec.encode(7, 1, 0))
        assertFalse(session.entities.exists(ghost), "夹具自检：幽灵 id 不得指向任何实体")
        session.host.storage.apiFor(FamilyDeclarations.PACKAGE_ID)
            .put(FamilyDeclarations.CHILD_IDS, "${ghost.raw},${child.raw}")

        val reloaded = reload(session)
        reloaded.host.notifySaveLoaded()
        assertEquals(1, reloaded.mod.state.registeredChildren, "EntityStore 里不存在的 id 不进名单")
        assertEquals(child, reloaded.mod.chooseSuccessor(null), "幽灵不得被选成继任者")
    }

    @Test
    fun duplicatePersistedIdsCollapseToASingleRosterEntry() {
        val session = start()
        val child = session.mod.adoptChild(title = "独女", father = null)
        // 重复 + 前后空白：解析必须去重并容错，否则一拍成长会把这个孩子推进两岁。
        session.host.storage.apiFor(FamilyDeclarations.PACKAGE_ID)
            .put(FamilyDeclarations.CHILD_IDS, "${child.raw}, ${child.raw},${child.raw}")

        val reloaded = reload(session)
        reloaded.host.notifySaveLoaded()
        assertEquals(1, reloaded.mod.state.registeredChildren)
        reloaded.mod.advanceOneDay()
        assertEquals(1L, reloaded.entities.getInt(child, FamilyKeys.age), "重复 id 不得让年龄一拍长两次")
    }

    @Test
    fun retiredFatherKeepsChildEnumerableAndGrowing() {
        val session = start()
        val father = session.entities.create(FamilyKeys.archetype)
        val child = session.mod.adoptChild(title = "嗣子", father = father)
        session.mod.markDeceased(father)

        val reloaded = reload(session)
        reloaded.host.notifySaveLoaded()
        assertEquals(EntityState.RETIRED, reloaded.entities.stateOf(father))
        assertEquals(1, reloaded.mod.state.registeredChildren)
        assertEquals(child, reloaded.mod.chooseSuccessor(father), "父虽退常规轮询，直系子嗣仍可继任")
        reloaded.mod.advanceOneDay()
        assertEquals(1L, reloaded.entities.getInt(child, FamilyKeys.age))
    }

    @Test
    fun entityStoreOnlyEntitiesAreNotRosterMembers() {
        val session = start()
        val strayFather = session.entities.create(FamilyKeys.archetype)
        val strayChild = session.entities.create(FamilyKeys.archetype)
        session.entities.setInt(strayChild, FamilyKeys.age, 7L)
        session.relationships.add(strayFather, FamilyDeclarations.PARENT, strayChild)
        assertEquals(2, session.entities.query(FamilyKeys.archetype).size, "夹具：EntityStore 里确有两个家人原型实体")

        val reloaded = reload(session)
        reloaded.host.notifySaveLoaded()
        assertEquals(0, reloaded.mod.state.registeredChildren, "未登记的实体不是在册子嗣 —— 名单不是 query(archetype) 派生")
        assertNull(reloaded.mod.chooseSuccessor(null))
        reloaded.mod.advanceOneDay()
        assertEquals(7L, reloaded.entities.getInt(strayChild, FamilyKeys.age), "不在册者不参与成长")
    }
}
