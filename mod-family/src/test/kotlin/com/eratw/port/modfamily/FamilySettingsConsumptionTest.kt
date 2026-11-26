package com.eratw.port.modfamily

import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getStr
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 载体**消费** per-mod 设置的行为面（task-12 / Lane L）：`mod-family` 两个已声明键。
 *
 * 装置只用 `:mod-testing` 的内存台架（`TestWorld` + `TestContext.settingsRecorder`），
 * 不启真引擎 —— 与 [FamilyModTest]（真宿主）分工不同：那边防宿主接线回归，这里钉
 * 「设置键值 → 业务结果」的映射，且每条用例都是秒级。
 *
 * 三个事实是这组用例的立论：
 * 1. **缺省值来自声明**：`GROWTH_PER_DAY_KEY` 的声明默认 = 1（`FamilyDeclarations`
 *    的 `intSetting(default = 1L)`），台架按声明文本预置 "1" 复现缺省口径；
 * 2. **每拍现读**：`advanceOneDay` / `adoptChild` 每次调用都查 `context.settings`，
 *    中途改值下一拍/下一次收养立即生效（[settingsAreReadFreshOnEveryTick] 反向钉住
 *    「onLoad 缓存成 val」这种退法）；
 * 3. **fail-closed**：未声明的键在台架上抛 `IllegalArgumentException`（真宿主上是
 *    `UnsupportedOperationException`，见 `FamilyModTest` 的未注入用例），载体不拿
 *    默认值兜底 —— 读值在改状态之前，抛时账本与实体都原封不动。
 */
class FamilySettingsConsumptionTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
    }

    private fun fixture(): Fixture {
        val world = TestWorld.of(FamilyDeclarations)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        // 按声明默认值预置两个键（= 生产快照在「持久化值缺省」时的 resolveText 结果）。
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        return Fixture(world, mod, session)
    }

    @Test
    fun growthPerDayThreeGrowsThreeDaysPerTick() {
        val f = fixture()
        f.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "3")
        val child = f.mod.adoptChild("甲", father = null)

        repeat(3) { f.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(9L, f.world.entities.getInt(child, FamilyKeys.age), "growthPerDay=3 × 3 拍 = 9 岁")
        assertEquals(3L, f.mod.state.growthTicks, "账本记的是「天」不是「岁」：3 拍 = 3 天")
        assertEquals("3", f.context.storage.get(FamilyDeclarations.GROWTH_TICKS))
    }

    @Test
    fun growthPerDayDeclarationDefaultOneGrowsOneDayPerTick() {
        val f = fixture()
        val child = f.mod.adoptChild("甲", father = null)

        repeat(3) { f.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(3L, f.world.entities.getInt(child, FamilyKeys.age), "声明默认 1 × 3 拍 = 3 岁")
    }

    @Test
    fun showTitlesFalseWritesEmptyTitleInsteadOfSkippingTheField() {
        val f = fixture()
        f.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "false")

        val child = f.mod.adoptChild("甲", father = null)

        assertEquals("", f.world.entities.getStr(child, FamilyKeys.title), "showTitles=false ⇒ 空串标题")
        assertEquals(0L, f.world.entities.getInt(child, FamilyKeys.age))
    }

    @Test
    fun showTitlesTrueKeepsTheGivenTitle() {
        val f = fixture()

        val child = f.mod.adoptChild("甲", father = null)

        assertEquals("甲", f.world.entities.getStr(child, FamilyKeys.title))
    }

    @Test
    fun settingsAreReadFreshOnEveryTick() {
        val f = fixture()
        val first = f.mod.adoptChild("甲", father = null)

        // 第一拍按缺省 1 走
        f.context.fireJob(FamilyDeclarations.GROWTH)
        assertEquals(1L, f.world.entities.getInt(first, FamilyKeys.age))

        // 中途改设置：下一拍立即生效（若 onLoad 缓存成 val，这里仍会是 +1）
        f.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "3")
        f.context.fireJob(FamilyDeclarations.GROWTH)
        assertEquals(4L, f.world.entities.getInt(first, FamilyKeys.age), "第二拍 +3 ⇒ 1+3")

        // 称号开关同理：下一次收养立即按新值写
        f.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "false")
        val second = f.mod.adoptChild("乙", father = null)
        assertEquals("", f.world.entities.getStr(second, FamilyKeys.title))
        assertEquals("甲", f.world.entities.getStr(first, FamilyKeys.title), "旧实体不回溯改写")
    }

    /**
     * 清掉「持久值」但声明仍在时，读的是**声明默认值**（1），不是抛 IAE。
     *
     * 本用例原名 `undeclaredGrowthKeyFailsClosedBeforeMutatingAnyState`，钉的是台架旧语义
     * （RecordingSettings 自造的「未声明键 ⇒ IAE」）。台架自 paritysem 波起直接复用生产
     * `ModSettingsSnapshot`：未落值的声明读 default、只有**未声明**的键才抛 IAE。
     * 所以这一格的正向语义是「回落到声明默认 1」，与生产一致；
     * 「真·缺设置面」的 fail-closed 已由 mod-memory / mod-family 的注入缺失用例覆盖。
     */
    @Test
    fun clearedGrowthValueFallsBackToDeclarationDefault() {
        val f = fixture()
        val child = f.mod.adoptChild("甲", father = null)

        f.context.settingsRecorder.clear()
        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(1L, f.mod.state.growthTicks, "默认 1 ⇒ 拍照常跑，账本 +1")
        assertEquals(1L, f.world.entities.getInt(child, FamilyKeys.age), "默认 1 ⇒ 长 1 岁")
    }
}
