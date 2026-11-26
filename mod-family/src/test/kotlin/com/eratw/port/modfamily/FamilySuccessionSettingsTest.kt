package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.setInt
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 继任模式设置的**行为面**（task-16 / Lane P）：`family.successionMode` 从「只声明」变成
 * `chooseSuccessor` 真消费的三档规则。
 *
 * 与 [FamilySettingsConsumptionTest] 同装置（`:mod-testing` 内存台架 + `settingsRecorder` 声明键值），
 * 与 lineage 包的 [com.eratw.port.modfamily.lineage.SuccessionTest] 分工不同：那边钉纯函数的排序口径，
 * 这里钉「设置值 → 载体选谁」的映射，以及坏值/未注入两类失败形态。
 *
 * 三条立论：
 * 1. **候选年龄是显式构造的**：用例经 `entities.setInt(child, FamilyKeys.age, …)` 直接给年龄，
 *    让「最年长」不在收养序的第一位 —— 证明选择按年龄而非插入序；
 * 2. **manual 不改现状**：`manual` 原样返回传入的现任（哪怕还有更大的子嗣）；
 * 3. **坏值不崩、未注入先抛**：坏枚举回落声明默认 `eldest`；设置面整体未注入时先抛，
 *    不静默选人。
 */
class FamilySuccessionSettingsTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
    }

    private fun fixture(mode: String = FamilyDeclarations.SUCCESSION_DEFAULT): Fixture {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        // 按声明默认值预置三个键（= 生产快照在「持久化值缺省」时的 resolveText 结果）。
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        session.context.settingsRecorder.declare(FamilyDeclarations.SUCCESSION_MODE_KEY, mode)
        return Fixture(world, mod, session)
    }

    /** 收养一个子嗣并直接钉年龄（不靠成长拍数，让用例的「谁最大」一眼可读）。 */
    private fun childAged(f: Fixture, title: String, age: Long, father: EntityId? = null): EntityId {
        val child = f.mod.adoptChild(title, father)
        f.world.entities.setInt(child, FamilyKeys.age, age)
        return child
    }

    @Test
    fun eldestPicksTheOldestChildRegardlessOfAdoptionOrder() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val first = childAged(f, "甲", 1L)
        val oldest = childAged(f, "乙", 3L)
        val last = childAged(f, "丙", 2L)

        val chosen = f.mod.chooseSuccessor()

        assertEquals(oldest, chosen, "eldest = 年龄最大者（乙=3），不是最先收养的甲")
        assertNotEquals(first, chosen)
        assertNotEquals(last, chosen)
    }

    @Test
    fun youngestPicksTheYoungestChild() {
        val f = fixture(FamilyDeclarations.SUCCESSION_YOUNGEST)
        val youngest = childAged(f, "甲", 1L)
        childAged(f, "乙", 3L)
        childAged(f, "丙", 2L)

        assertEquals(youngest, f.mod.chooseSuccessor(), "youngest = 年龄最小者（甲=1），不是最先收养以外的规矩")
    }

    @Test
    fun manualKeepsTheGivenIncumbentEvenThoughAnotherChildIsOlder() {
        val f = fixture(FamilyDeclarations.SUCCESSION_MANUAL)
        val eldest = childAged(f, "甲", 3L)
        val incumbent = childAged(f, "乙", 1L)

        val chosen = f.mod.chooseSuccessor(incumbent = incumbent)

        assertEquals(incumbent, chosen, "manual = 保留现状，原样返回调用方给的现任")
        assertNotEquals(eldest, chosen, "manual 不得偷偷按 eldest 选人")
    }

    @Test
    fun unknownModeFallsBackToDeclaredDefaultEldest() {
        val f = fixture("crown-prince")
        childAged(f, "甲", 1L)
        val oldest = childAged(f, "乙", 3L)

        assertEquals(oldest, f.mod.chooseSuccessor(), "未知枚举值回落声明默认 eldest，不抛也不随机")
    }

    @Test
    fun fatherFilterOnlyConsidersThatFathersDirectChildren() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val father = childAged(f, "父", 0L)
        val own = childAged(f, "嫡", 2L, father = father)
        val other = childAged(f, "庶", 9L, father = null)

        assertEquals(own, f.mod.chooseSuccessor(father = father), "只在该父代的直系子嗣里选（庶=9 不参与）")
        assertEquals(other, f.mod.chooseSuccessor(), "father=null ⇒ 全体在册子嗣（庶=9 最年长）")
    }

    @Test
    fun noCandidatesMeansNoSuccessor() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)

        assertNull(f.mod.chooseSuccessor(), "没有在册子嗣 ⇒ null，不发明人选")
    }

    /**
     * 清掉「持久值」但声明仍在 ⇒ 继任模式回落到声明默认 `eldest`，照常选出最年长者。
     *
     * 本用例原名 `missingSettingsFaceFailsClosedBeforeChoosingAnyone`，钉的是台架旧语义
     * （`settingsRecorder.clear()` 被当作「宿主没注入设置面」）。台架自 paritysem 波起复用
     * 生产 `ModSettingsSnapshot`：clear 只清持久值、声明仍在 ⇒ 读 default；
     * 真·缺面（manifest 无 settings 段）另有用例覆盖。
     */
    @Test
    fun clearedSuccessionModeFallsBackToDeclarationDefault() {
        val f = fixture(FamilyDeclarations.SUCCESSION_ELDEST)
        val child = childAged(f, "甲", 3L)

        f.context.settingsRecorder.clear()

        assertEquals(child, f.mod.chooseSuccessor(), "默认 eldest ⇒ 选在册最年长者")
        assertEquals(3L, f.world.entities.getInt(child, FamilyKeys.age), "选人不改年龄")
        assertEquals(1, f.mod.state.registeredChildren)
    }
}
