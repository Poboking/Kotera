package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.create
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.has
import com.eratw.port.modapi.put
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-015 切片 S1 的死亡语义面：`mortalityPermille` 接线（S6）+ [FamilyMod.markDeceased] 幂等。
 *
 * 装置只用 `:mod-testing` 内存台架（[TestWorld] + [runMod]），不启真引擎 —— 与 [FamilyModTest]
 * 的真宿主分工一致：那边防宿主接线回归，这里钉语义本身，每条秒级。
 *
 * 用例锚定的 ADR-015 条目（`eratw-架构决策记录.md` 的 ADR-015 一节）：
 * - D1：死亡 = 实体 `state = RETIRED`，不新增状态/墓碑/存档字段；
 * - D2：实体段 state 与 mod 存储的 `family.children` 是两份权威，[FamilyMod.markDeceased]
 *   必须显式对齐（标 state + 从名册摘除并写穿 [FamilyDeclarations.CHILD_IDS]）；
 * - D4/D5：不删实体、不 unproject、不碰 rosterGeneration，PARENT/LINEAGE 边不断；
 * - D6-C：`family.mortalityPermille` 默认 0 = 关闭；>0 时由 [FamilyMod.advanceOneDay] 在 reconcile
 *   之后、成长之前抽样（S6 接线，逐位契约见 [FamilyMortalityTest]）；
 * - D7：死亡只记状态，继任在下一日拍结算（本组用例不依赖该清扫）。
 *
 * 写盘次数的观测手段：`MemoryStorage` 没有 put 计数，故用「哨兵值」反证 —— 第一次标死后把
 * [FamilyDeclarations.CHILD_IDS] 改写成哨兵，再重复标死；若实现仍无条件写盘，哨兵会被覆盖。
 */
class FamilyDeathSemanticsTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
    }

    private fun fixture(): Fixture {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        return Fixture(world, mod, session)
    }

    @Test
    fun mortalityPermilleIsDeclaredDefaultsToZeroAndFallsBackOnBadValues() {
        val f = fixture()
        // 声明确实进了 manifest 的设置表（不只是 Kotlin 常量）。
        assertTrue(FamilyDeclarations.MORTALITY_PERMILLE_KEY in f.context.settings.keys)
        assertEquals(0L, f.context.settings.int(FamilyDeclarations.MORTALITY_PERMILLE_KEY), "未落值 ⇒ 声明默认 0 = 关闭")

        f.context.settingsRecorder.declare(FamilyDeclarations.MORTALITY_PERMILLE_KEY, "1000")
        assertEquals(1000L, f.context.settings.int(FamilyDeclarations.MORTALITY_PERMILLE_KEY))

        // 越界/负值走生产快照的「坏值回落声明默认」口径，不抛。
        f.context.settingsRecorder.declare(FamilyDeclarations.MORTALITY_PERMILLE_KEY, "1001")
        assertEquals(0L, f.context.settings.int(FamilyDeclarations.MORTALITY_PERMILLE_KEY), "超上界 1000 ⇒ 回落默认 0")
        f.context.settingsRecorder.declare(FamilyDeclarations.MORTALITY_PERMILLE_KEY, "-1")
        assertEquals(0L, f.context.settings.int(FamilyDeclarations.MORTALITY_PERMILLE_KEY), "负值 ⇒ 回落默认 0")
    }

    @Test
    fun mortalitySettingIsWiredIntoTheDailyTick() {
        val f = fixture()
        // 1000‰ = 每日必死；两人的 father 都是 NULL ⇒ 各自是名册里的父辈，都是抽样候选。
        f.context.settingsRecorder.declare(FamilyDeclarations.MORTALITY_PERMILLE_KEY, "1000")
        val ids = listOf(f.mod.adoptChild("甲", father = null), f.mod.adoptChild("乙", father = null))

        f.context.fireJob(FamilyDeclarations.GROWTH)

        ids.forEach { id ->
            assertEquals(EntityState.RETIRED, f.world.entities.stateOf(id), "S6 已接线：1000‰ 本拍必死")
            assertEquals(0L, f.world.entities.getInt(id, FamilyKeys.age), "抽样在成长之前 ⇒ 本拍死者不再涨年龄")
        }
        assertEquals(0, f.mod.state.registeredChildren, "死者名册同步摘除（D2）")
        assertEquals("", f.context.storage.get(FamilyDeclarations.CHILD_IDS), "名册写穿为空串")
        val log = f.context.logEntries.filter { it.message.contains("mod.family mortality") }.singleOrNull()
        assertTrue(log != null, "有标死必须记一条日志：${f.context.logEntries.map { it.message }}")
        assertTrue(log.message.contains("n=2"), "日志带本拍标死数：${log.message}")
    }

    @Test
    fun firstMarkDeceasedRetiresChildRemovesFromRosterAndWritesThrough() {
        val f = fixture()
        val dead = f.mod.adoptChild("甲", father = null)
        val alive = f.mod.adoptChild("乙", father = null)

        assertTrue(f.mod.markDeceased(dead), "ACTIVE → RETIRED 本次真的发生")

        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(dead))
        assertEquals(1, f.mod.state.registeredChildren)
        assertEquals(alive.raw.toString(), f.context.storage.get(FamilyDeclarations.CHILD_IDS), "名册写穿：只剩活着的")

        // 读档缝：同一世界换新实例，死亡结果不随存档退回在册。
        val reloaded = FamilyMod()
        val reloadedSession = runMod(reloaded, f.world, FamilyDeclarations)
        assertEquals(1, reloaded.state.registeredChildren, "重载后在册只剩 1 人")
        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(dead), "实体段 state 也持久（D1）")
        reloadedSession.unload()
    }

    @Test
    fun repeatedMarkDeceasedIsIdempotentAndDoesNotTouchStorage() {
        val f = fixture()
        val dead = f.mod.adoptChild("甲", father = null)
        f.mod.adoptChild("乙", father = null)
        assertTrue(f.mod.markDeceased(dead))

        // 哨兵：第二次标死若仍写盘，这个值会被真实名册覆盖。
        f.context.storage.put(FamilyDeclarations.CHILD_IDS, "12345")

        assertFalse(f.mod.markDeceased(dead), "已 RETIRED ⇒ 幂等返回 false")
        assertEquals("12345", f.context.storage.get(FamilyDeclarations.CHILD_IDS), "幂等路径零写盘")
        assertEquals(1, f.mod.state.registeredChildren, "名册缓存不被重复摘除影响")

        assertFalse(f.mod.markDeceased(dead), "第三次同样 false")
        assertEquals("12345", f.context.storage.get(FamilyDeclarations.CHILD_IDS))
    }

    @Test
    fun markDeceasedKeepsIdentityRelationsAndData() {
        val f = fixture()
        val father = f.world.entities.create(FamilyKeys.archetype)
        val child = f.mod.adoptChild("甲", father)
        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertTrue(f.mod.markDeceased(child))

        assertTrue(f.world.entities.exists(child), "D4：不删实体（exists 对 RETIRED 也为真）")
        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(child))
        assertEquals("甲", f.world.entities.getStr(child, FamilyKeys.title), "身份串保留")
        assertEquals(1L, f.world.entities.getInt(child, FamilyKeys.age), "数据保留")
        assertEquals(father, f.world.entities.getRef(child, FamilyKeys.father), "父引用字段保留")
        assertTrue(f.world.relationships.has(father, FamilyDeclarations.PARENT, child), "D5：PARENT 边不断")
        assertTrue(f.world.relationships.has(child, FamilyDeclarations.LINEAGE, father), "D5：LINEAGE 反向边不断")
        // D4 的「不 unproject / 不碰 rosterGeneration」：台架的 roster 面未装配（读 rosterGeneration
        // 抛 UnsupportedOperationException），本用例只能钉「实体与关系不动」；投影面留真宿主用例。
        assertEquals(0, f.mod.presentedChildren, "从未登场 ⇒ 死亡也不产生投射副作用")
    }

    @Test
    fun markDeceasedLeavesSiblingsAndFatherAliveAndGrowing() {
        val f = fixture()
        val father = f.world.entities.create(FamilyKeys.archetype)
        val dead = f.mod.adoptChild("甲", father)
        val alive = f.mod.adoptChild("乙", father)
        f.context.fireJob(FamilyDeclarations.GROWTH)

        f.mod.markDeceased(dead)
        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(dead))
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(alive), "兄弟不受牵连")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(father), "父辈不受牵连")
        assertEquals(2L, f.world.entities.getInt(alive, FamilyKeys.age), "活着的继续长")
        assertEquals(1L, f.world.entities.getInt(dead, FamilyKeys.age), "死者不再长")
    }

    @Test
    fun markDeceasedOnNonRosterEntityStillRetiresItWithoutTouchingRoster() {
        val f = fixture()
        // 直接 create、不经 adoptChild 的父辈：不在 children 名册，但必须能标死
        // （FamilyRosterPersistenceTest 的 retiredFatherKeepsChildEnumerableAndGrowing 依赖它）。
        val father = f.world.entities.create(FamilyKeys.archetype)
        val child = f.mod.adoptChild("甲", father)
        val rosterBefore = f.context.storage.get(FamilyDeclarations.CHILD_IDS)

        assertTrue(f.mod.markDeceased(father), "不在名册但存在的实体照常标死")

        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(father))
        assertEquals(rosterBefore, f.context.storage.get(FamilyDeclarations.CHILD_IDS), "名册无变化 ⇒ 不写盘")
        assertEquals(1, f.mod.state.registeredChildren)
        assertTrue(f.world.entities.exists(child), "子嗣仍在（死亡不级联）")
    }

    @Test
    fun markDeceasedOnMissingEntityReturnsFalseWithoutSideEffects() {
        val f = fixture()
        val child = f.mod.adoptChild("甲", father = null)
        val rosterBefore = f.context.storage.get(FamilyDeclarations.CHILD_IDS)
        val ghost = EntityId(987_654L)

        assertFalse(f.mod.markDeceased(ghost), "实体不存在 ⇒ false")
        assertNull(f.world.entities.stateOf(ghost), "不存在仍是 null")
        assertEquals(rosterBefore, f.context.storage.get(FamilyDeclarations.CHILD_IDS), "零写盘")
        assertEquals(1, f.mod.state.registeredChildren)
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(child))
    }
}
