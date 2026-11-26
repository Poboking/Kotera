package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.setInt
import com.eratw.port.modfamily.generated.FamilyFacade
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modfamily.generated.FamilyView
import com.eratw.port.modtesting.LogEntry
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals

import kotlin.test.assertTrue

/**
 * 继任模式的**自动接线面**（task-33 / Lane AO）：`family.growth` 日拍结算家督继承。
 *
 * 与 [FamilySuccessionSettingsTest] 分工：那边钉「设置值 → chooseSuccessor 选谁」的纯选择口径，
 * 这里钉「真实触发点 + 写回既有字段」—— 触发点 = 引擎每游戏日跑的 `family.growth` 任务
 * （声明 `job(GROWTH, everyDays = 1L)` + `onEnable` 绑定 `advanceOneDay`，引擎 `GameTickDriver.tick`
 * 里 `jobs.runDue` 自动调用）；台架用 `context.fireJob(FamilyDeclarations.GROWTH)` 等价模拟一日到达。
 *
 * 写回口径只用既有字段（不新增字段）：新家督继承死者 `title`、自己的 `father` 清成 NULL；
 * 其余在册直系子嗣 `father` 改挂新家督；PARENT/LINEAGE 关系边刻意不动（§7 RETIRED 保留关系与数据）。
 *
 * 台架限制如实说明：`:mod-testing` 的 `EntityStore.create` 只接受已声明原型（未注册抛 IAE），本包
 * 只声明 `family.child` 一个原型 ⇒ **主角/非本家族实体在结构上进不了实体表，进不了候选池**；
 * 用例用「非该父直系（father = NULL）」「在册但已 RETIRED」两种可造形态钉排除面，不假装造过主角实体。
 */
class FamilySuccessionWiringTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
        val family: FamilyFacade get() = FamilyFacade(world.entities)
        val logs: List<LogEntry> get() = context.logEntries
    }

    private fun fixture(mode: String = FamilyDeclarations.SUCCESSION_DEFAULT): Fixture {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        session.context.settingsRecorder.declare(FamilyDeclarations.SUCCESSION_MODE_KEY, mode)
        return Fixture(world, mod, session)
    }

    /** 收养一个家督（父引用为空）并直接钉年龄；标题即家督称号。 */
    private fun head(f: Fixture, title: String = "当主"): EntityId = f.mod.adoptChild(title, null)

    /** 收养一个子嗣并直接钉年龄（不靠成长拍数，让「谁最大」一眼可读）。 */
    private fun childAged(f: Fixture, title: String, age: Long, father: EntityId?): EntityId {
        val child = f.mod.adoptChild(title, father)
        f.world.entities.setInt(child, FamilyKeys.age, age)
        return child
    }

    private fun viewOf(f: Fixture, id: EntityId): FamilyView = f.family.of(id)

    private fun successionLogs(f: Fixture): List<LogEntry> =
        f.logs.filter { it.message.contains("家督继任") }

    private fun assertLogged(f: Fixture, mode: String, head: EntityId, successor: EntityId) {
        val log = successionLogs(f).singleOrNull()
        assertTrue(log != null, "eldest/youngest 自动继任必须记一条既有日志面：${f.logs.map { it.message }}")
        assertEquals("INFO", log.level, "继任日志走 INFO 级")
        assertTrue(log.message.contains("mode=$mode"), "日志要带模式：${log.message}")
        assertTrue(log.message.contains("前任=${head.raw}"), "日志要带前任：${log.message}")
        assertTrue(log.message.contains("继任=${successor.raw}"), "日志要带继任：${log.message}")
    }

    private fun assertNoSuccession(f: Fixture) {
        assertTrue(successionLogs(f).isEmpty(), "本拍不该产生继任日志：${successionLogs(f).map { it.message }}")
    }

    @Test
    fun eldestModeSettlesTheOldestChildOnTheNextGrowthTick() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val head = head(f)
        val first = childAged(f, "甲", 1L, head)
        val oldest = childAged(f, "乙", 3L, head)
        val last = childAged(f, "丙", 2L, head)

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH), "family.growth 已绑定 ⇒ 日拍必须能触发")

        assertEquals(oldest, viewOf(f, first).father, "其余直系子嗣改挂新家督")
        assertEquals(oldest, viewOf(f, last).father)
        assertEquals(EntityId.NULL, viewOf(f, oldest).father, "新家督自己的原父引用必须清成 NULL")
        assertEquals("当主", viewOf(f, oldest).title, "新家督继承死者的家督称号")
        assertEquals("甲", viewOf(f, first).title, "兄弟称号不被改")
        assertLogged(f, FamilyDeclarations.SUCCESSION_ELDEST, head, oldest)
    }

    @Test
    fun youngestModeSettlesTheYoungestChildOnTheNextGrowthTick() {
        val f = fixture(FamilyDeclarations.SUCCESSION_YOUNGEST)
        val head = head(f)
        val youngest = childAged(f, "甲", 1L, head)
        val middle = childAged(f, "乙", 2L, head)
        val oldest = childAged(f, "丙", 3L, head)

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(EntityId.NULL, viewOf(f, youngest).father, "youngest = 年龄最小者（甲=1）成为新家督")
        assertEquals(youngest, viewOf(f, middle).father)
        assertEquals(youngest, viewOf(f, oldest).father)
        assertEquals("当主", viewOf(f, youngest).title)
        assertLogged(f, FamilyDeclarations.SUCCESSION_YOUNGEST, head, youngest)
    }

    @Test
    fun manualModeLeavesFatherAndTitleUntouchedOnTheGrowthTick() {
        val f = fixture(FamilyDeclarations.SUCCESSION_MANUAL)
        val head = head(f)
        val eldest = childAged(f, "甲", 3L, head)
        val incumbent = childAged(f, "乙", 1L, head)

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(head, viewOf(f, eldest).father, "manual = 不自动改，子嗣仍按 father 指死者")
        assertEquals(head, viewOf(f, incumbent).father)
        assertEquals("甲", viewOf(f, eldest).title, "manual 不自动继承称号")
        assertEquals("乙", viewOf(f, incumbent).title)
        assertNoSuccession(f)
        assertEquals(1L, f.mod.state.growthTicks, "manual 不挡成长拍本身")
    }

    @Test
    fun noCandidatesKeepsStateAndDoesNotThrow() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val head = head(f)

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH), "无候选也不能崩")

        assertEquals(1L, f.mod.state.growthTicks)
        assertEquals(0, f.mod.state.registeredChildren)
        assertNoSuccession(f)
    }

    @Test
    fun retiredChildrenAreNotCandidatesAndAreNotRewritten() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val head = head(f)
        val active = childAged(f, "嫡", 1L, head)
        val retiredOlder = childAged(f, "殁", 5L, head)
        // 在册但已退场（不经 markDeceased，模拟台架/恢复装配面的既有形态）：不得继承、不得被改写。
        f.world.entities.setState(retiredOlder, EntityState.RETIRED)

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(EntityId.NULL, viewOf(f, active).father, "已退场的年长者（殁=5）不参与选举 ⇒ 唯一在册的嫡成为家督")
        assertEquals(head, viewOf(f, retiredOlder).father, "死者的 father 不被继任清扫改写")
    }

    @Test
    fun nonDescendantChildrenAreNotCandidates() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val head = head(f)
        val daughter = childAged(f, "嫡", 1L, head)
        val outsider = childAged(f, "庶", 9L, null)

        f.mod.markDeceased(head)
        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(EntityId.NULL, viewOf(f, daughter).father, "非该父直系（庶=9、father=NULL）不参与 ⇒ 嫡（=1）成为家督")
        assertEquals(EntityId.NULL, viewOf(f, outsider).father, "非直系子嗣的 father 不被继任清扫改写")
    }

    @Test
    fun livingHeadTickDoesNotTouchFatherReferences() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val head = head(f)
        val child = childAged(f, "甲", 2L, head)

        assertTrue(f.context.fireJob(FamilyDeclarations.GROWTH))

        assertEquals(head, viewOf(f, child).father, "没有死者 ⇒ 继任清扫零写入")
        assertEquals("甲", viewOf(f, child).title)
        assertNoSuccession(f)
        assertEquals(1L, f.mod.state.growthTicks)
    }
}
