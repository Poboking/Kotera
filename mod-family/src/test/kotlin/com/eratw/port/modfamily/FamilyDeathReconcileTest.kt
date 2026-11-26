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
import com.eratw.port.modapi.setInt
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modtesting.LogEntry
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-015 切片 S2 的**死亡对齐面**：ERB 直调 `ENTITY_SET_STATE(id, 3)` 只改实体段 state、不碰
 * [FamilyDeclarations.CHILD_IDS]，名册与实体段因此出现分歧；日拍的第一件事
 * [reconcileRetiredChildren] 把分歧对齐，随后的继任清扫用 [deceasedHeads] 发现死者。
 *
 * 与 [FamilyDeathSemanticsTest] 分工：那边钉 A1（Kotlin `markDeceased`）的语义；这里钉 A2（ERB 直调）
 * ⇒ 下一日拍收口，以及「死者发现面不再只依赖在册子嗣的 father 反查」。
 *
 * 用例锚定的 ADR-015 条目：
 * - D2：实体段 state 与 mod 存储的名册是两份独立权威，必须显式对齐（本片的 reconcile 步）；
 * - D3：旧档无 RETIRED 记录即活着 —— 非 RETIRED / 已销毁的实体绝不误判成死亡；
 * - D4/D5：不删实体、不断 PARENT/LINEAGE 边、只改名册这一份权威；
 * - D7：清扫时机固定在下一日拍，reconcile 必须与 settle 同批（先 reconcile 后 settle）；
 * - manual：reconcile 照跑（名册是数据一致性面），只是不自动结算继任。
 *
 * 写盘观测手段与 [FamilyDeathSemanticsTest] 同口径：`MemoryStorage` 无 put 计数，用哨兵值反证 ——
 * 往 [FamilyDeclarations.CHILD_IDS] 塞不可解析的哨兵后若仍被覆盖，说明该路径无条件写盘。
 */
class FamilyDeathReconcileTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
    }

    private fun fixture(mode: String = FamilyDeclarations.SUCCESSION_ELDEST): Fixture {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        session.context.settingsRecorder.declare(FamilyDeclarations.SUCCESSION_MODE_KEY, mode)
        return Fixture(world, mod, session)
    }

    private fun childAged(f: Fixture, title: String, age: Long, father: EntityId?): EntityId {
        val child = f.mod.adoptChild(title, father)
        f.world.entities.setInt(child, FamilyKeys.age, age)
        return child
    }

    private fun roster(f: Fixture): String? = f.context.storage.get(FamilyDeclarations.CHILD_IDS)

    private fun reconcileLogs(f: Fixture): List<LogEntry> =
        f.context.logEntries.filter { it.message.contains("mod.family reconcile") }

    private fun successionLogs(f: Fixture): List<LogEntry> =
        f.context.logEntries.filter { it.message.contains("家督继任") }

    @Test
    fun erbRetiredChildIsReconciledBeforeTheGrowthStepAndWrittenThrough() {
        val f = fixture()
        val father = f.world.entities.create(FamilyKeys.archetype)
        val dead = f.mod.adoptChild("殁", father)
        val alive = f.mod.adoptChild("生", father)
        // ERB 直调：只改实体段 state，名册原样不动（D2 的两份权威出现分歧）。
        f.world.entities.setState(dead, EntityState.RETIRED)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(1, f.mod.state.registeredChildren, "下一日拍把已标死的子嗣从名册摘掉")
        assertEquals(alive.raw.toString(), roster(f), "名册写穿：只剩活着的")
        assertEquals(0L, f.world.entities.getInt(dead, FamilyKeys.age), "reconcile 排在成长之前 ⇒ 死者不再涨年龄")
        assertEquals(1L, f.world.entities.getInt(alive, FamilyKeys.age), "活着的照常成长")
        assertTrue(f.world.entities.exists(dead), "D4：死亡不删实体（RETIRED 也算存在）")
        assertEquals("殁", f.world.entities.getStr(dead, FamilyKeys.title), "身份数据保留")
        assertEquals(father, f.world.entities.getRef(dead, FamilyKeys.father), "父引用字段保留")
        assertTrue(f.world.relationships.has(father, FamilyDeclarations.PARENT, dead), "D5：PARENT 边不断")
        assertTrue(f.world.relationships.has(dead, FamilyDeclarations.LINEAGE, father), "D5：LINEAGE 反向边不断")

        val log = reconcileLogs(f).singleOrNull()
        assertTrue(log != null, "有摘除必须记一条日志：${f.context.logEntries.map { it.message }}")
        assertEquals("INFO", log.level, "reconcile 日志走 INFO 级")
        assertTrue(log.message.contains("n=1"), "日志要带本拍摘除数：${log.message}")
        assertTrue(successionLogs(f).isEmpty(), "本用例的死者没有家督位要传，不该产生继任日志")
    }

    @Test
    fun deceasedHeadWithoutRemainingRosterChildrenIsStillDiscovered() {
        val f = fixture()
        val father = f.mod.adoptChild("当主", father = null)
        val only = f.mod.adoptChild("独子", father)

        // 唯一的子嗣先被 ERB 标死 ⇒ 下一日拍把它摘掉，名册只剩父辈（子嗣反查面断掉）。
        f.world.entities.setState(only, EntityState.RETIRED)
        f.context.fireJob(FamilyDeclarations.GROWTH)
        assertEquals(1, f.mod.state.registeredChildren)
        assertFalse(deceasedHeads(f.mod.successionView).contains(father), "父辈此刻还活着，不该被当成死者")

        // 父辈随后被 ERB 标死：名册里没有子嗣可按 father 反查它，只能靠实体段枚举发现。
        f.world.entities.setState(father, EntityState.RETIRED)
        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(0, f.mod.state.registeredChildren, "已标死的父辈也随日拍摘除")
        assertEquals("", roster(f), "空名册写空串（键保持存在）")
        assertTrue(
            deceasedHeads(f.mod.successionView).contains(father),
            "无在册子嗣的死者必须被 deceasedHeads 发现（实体段 query 来源）",
        )
        assertEquals(2, reconcileLogs(f).size, "两拍各摘一人")
        assertTrue(successionLogs(f).isEmpty(), "没有家督位候选 ⇒ 不产生继任日志")
    }

    @Test
    fun rosterEntriesWhoseEntityIsGoneAreNotTreatedAsDeaths() {
        val f = fixture()
        val child = f.mod.adoptChild("甲", father = null)
        val rosterBefore = roster(f)
        // 实体真删（不是死亡）：名册里的 id 成了幽灵，但 D3 口径下它既不是 RETIRED、也不该被摘。
        assertTrue(f.world.entities.destroy(child))

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(rosterBefore, roster(f), "不存在实体不摘除、不写盘（D3：无 RETIRED 记录即未死）")
        assertEquals(1, f.mod.state.registeredChildren, "幽灵条目留给读档路径的 exists 过滤，日拍不动它")
        assertTrue(reconcileLogs(f).isEmpty(), "零摘除 ⇒ 零日志")
        assertTrue(successionLogs(f).isEmpty())
    }

    @Test
    fun secondTickWithoutRosterChangesDoesNotWriteTheRosterAgain() {
        val f = fixture()
        val father = f.world.entities.create(FamilyKeys.archetype)
        val dead = f.mod.adoptChild("殁", father)
        f.mod.adoptChild("生", father)
        f.world.entities.setState(dead, EntityState.RETIRED)
        f.context.fireJob(FamilyDeclarations.GROWTH)
        assertEquals(1, reconcileLogs(f).size)

        // 哨兵：名册无变化时若仍无条件写盘，哨兵会被真实名册覆盖。
        f.context.storage.put(FamilyDeclarations.CHILD_IDS, "sentinel-not-a-roster")
        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals("sentinel-not-a-roster", roster(f), "幂等：无摘除不写盘")
        assertEquals(1, reconcileLogs(f).size, "无摘除不重复记日志")
    }

    @Test
    fun severalRetiredChildrenAreRemovedInOneWriteThroughAndLoggedOnce() {
        val f = fixture()
        val father = f.world.entities.create(FamilyKeys.archetype)
        val first = f.mod.adoptChild("殁1", father)
        val second = f.mod.adoptChild("殁2", father)
        val alive = f.mod.adoptChild("生", father)
        f.world.entities.setState(first, EntityState.RETIRED)
        f.world.entities.setState(second, EntityState.RETIRED)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(alive.raw.toString(), roster(f), "同拍摘除多人只写穿一次名册")
        assertEquals(1, f.mod.state.registeredChildren)
        val log = reconcileLogs(f).singleOrNull()
        assertTrue(log != null && log.message.contains("n=2"), "一次日志汇总本拍摘除数：${reconcileLogs(f).map { it.message }}")
    }

    @Test
    fun manualModeStillReconcilesTheRosterButSkipsSettlement() {
        val f = fixture(FamilyDeclarations.SUCCESSION_MANUAL)
        val head = f.mod.adoptChild("当主", father = null)
        val alive = f.mod.adoptChild("甲", head)
        val dead = f.mod.adoptChild("殁", head)
        f.mod.markDeceased(head)
        f.world.entities.setState(dead, EntityState.RETIRED)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(1, reconcileLogs(f).size, "manual 下 reconcile（数据一致性面）照跑")
        assertEquals(1, f.mod.state.registeredChildren, "已标死的子嗣被摘除")
        assertEquals(head, f.world.entities.getRef(alive, FamilyKeys.father), "manual：不自动改父引用")
        assertEquals("甲", f.world.entities.getStr(alive, FamilyKeys.title), "manual：不自动继承称号")
        assertTrue(successionLogs(f).isEmpty(), "manual 模式不自动结算继任")
        assertEquals(1L, f.mod.state.growthTicks, "manual 不挡成长拍本身")
    }

    @Test
    fun erbRetiredHeadIsSettledAfterBeingReconciledOnTheSameTick() {
        val f = fixture()
        val head = f.mod.adoptChild("当主", father = null)
        val eldest = childAged(f, "甲", 3L, head)
        val youngest = childAged(f, "乙", 1L, head)
        // ERB 直调：家督的 state 变 RETIRED，但名册仍把它当成员。
        f.world.entities.setState(head, EntityState.RETIRED)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(2, f.mod.state.registeredChildren, "死者在成长前就被摘除，两名子嗣仍在册")
        val log = successionLogs(f).singleOrNull()
        assertTrue(log != null, "A2 触发也要在下一日拍结算继任：${f.context.logEntries.map { it.message }}")
        assertTrue(log.message.contains("前任=${head.raw}"), "日志要带前任：${log.message}")
        assertTrue(log.message.contains("继任=${eldest.raw}"), "eldest 规则：最年长者继任：${log.message}")
        assertEquals(EntityId.NULL, f.world.entities.getRef(eldest, FamilyKeys.father), "新家督自己的父引用清成 NULL")
        assertEquals(eldest, f.world.entities.getRef(youngest, FamilyKeys.father), "其余直系改挂新家督")
        assertEquals("当主", f.world.entities.getStr(eldest, FamilyKeys.title), "新家督继承死者称号")
        assertTrue(f.world.entities.exists(head), "D4：结算继任不删死者实体")
    }

    @Test
    fun headFoundThroughBothDiscoverySourcesIsSettledOnlyOnce() {
        val f = fixture()
        val head = f.mod.adoptChild("当主", father = null)
        val child = childAged(f, "甲", 2L, head)
        // 死者还在名册里：子嗣 father 反查与实体段枚举会同时发现它。
        f.world.entities.setState(head, EntityState.RETIRED)
        assertEquals(
            1,
            deceasedHeads(f.mod.successionView).count { it == head },
            "两处来源合并后按 raw 去重：同一个死者只出现一次",
        )

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(1, successionLogs(f).size, "同一死者只结算一次")
        assertEquals(1, reconcileLogs(f).size)
        assertEquals(EntityId.NULL, f.world.entities.getRef(child, FamilyKeys.father))
    }

    @Test
    fun ticksWithNoDeceasedAreByteIdenticalOnTheRosterAndLogNothing() {
        val f = fixture()
        val first = f.mod.adoptChild("甲", father = null)
        val second = f.mod.adoptChild("乙", father = null)
        val rosterBefore = roster(f)

        repeat(3) { f.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(rosterBefore, roster(f), "普通成长拍零名册写盘")
        assertTrue(reconcileLogs(f).isEmpty(), "零摘除 ⇒ 零日志")
        assertTrue(successionLogs(f).isEmpty())
        assertEquals(3L, f.world.entities.getInt(first, FamilyKeys.age))
        assertEquals(3L, f.world.entities.getInt(second, FamilyKeys.age))
        assertEquals(3L, f.mod.state.growthTicks)
    }
}
