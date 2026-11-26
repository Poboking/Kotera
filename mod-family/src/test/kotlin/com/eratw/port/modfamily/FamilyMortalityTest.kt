package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modtesting.LogEntry
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-015 切片 S6 的**时间驱动死亡**面（D6 的 C 级触发）：`family.mortalityPermille` 由日拍
 * [FamilyMod.advanceOneDay] 消费，位置固定在 reconcile 之后、成长之前（D7 的相位序）。
 *
 * 本组用例的两条不能让步的口径：
 *
 * 1. **默认 0‰ = 零随机数消耗**。这是本片最重要的隐藏契约：`context.rng` 是各 mod 独立的派生流，
 *    但默认配置一旦在日拍里「抽了不用」，同种子下后续所有 `nextInt` 都会漂移，破坏 rng mod 的确定性。
 *    验证手法不依赖内部字段：同 `decisionSeed` 建一条参照 [TestContext]（`TestContext.rng` 是生产同一
 *    实现 [com.eratw.port.modapi.RandomApi]，按 `(decisionSeed, packageId)` 从头派生），把日拍后的
 *    抽数与参照流**逐位比较**；日拍若多抽一次，整段就对不上。
 * 2. **命中只走 S1 的 [FamilyMod.markDeceased]**：本组用例断言的是 A1 写路径的既有副作用
 *    （实体 RETIRED + 名册摘除写穿 + 幂等），抽样本身不另写标死。
 *
 * 锚定的 ADR-015 条目：
 * - D6-C：`mortalityPermille` 默认 0 = 关闭，>0 才启用；候选 = 在册子嗣的父辈 ∪ 名册中的父辈；
 * - D7：日拍相位序 = reconcile → 时间驱动抽样 → 成长 → 继任清扫；
 * - D2/D4/D5：死者只改 state + 摘名册，不删实体、不断关系边、不碰投射；
 * - D3：已 RETIRED / 已销毁的候选在收集阶段就跳过，不占随机流。
 *
 * 候选顺序按 [EntityId.raw] 升序（实现 KDoc 见 [FamilyRosterView.sampleMortality]）⇒ 同种子 + 同名册
 * 的抽样结果逐位可复现，用例用「参照流 + 每候选恰好一抽」把次数与顺序一起钉住。
 */
class FamilyMortalityTest {

    private class Fixture(val world: TestWorld, val mod: FamilyMod, val session: TestModSession) {
        val context: TestContext get() = session.context
    }

    /**
     * 装配一个台架会话。[mode] 缺省用 [FamilyDeclarations.SUCCESSION_MANUAL]：本组用例钉的是
     * 「谁被抽死」，继任结算会改写父引用/称号，会污染死亡断言（继任由 S2 的既有用例覆盖）。
     */
    private fun fixture(seed: Long = 0L, mode: String = FamilyDeclarations.SUCCESSION_MANUAL): Fixture {
        val world = TestWorld.of(FamilyDeclarations, decisionSeed = seed)
        val mod = FamilyMod()
        val session = runMod(mod, world, FamilyDeclarations)
        session.context.settingsRecorder.declare(FamilyDeclarations.GROWTH_PER_DAY_KEY, "1")
        session.context.settingsRecorder.declare(FamilyDeclarations.SHOW_TITLES_KEY, "true")
        session.context.settingsRecorder.declare(FamilyDeclarations.SUCCESSION_MODE_KEY, mode)
        return Fixture(world, mod, session)
    }

    private fun declarePermille(f: Fixture, permille: Int) {
        f.context.settingsRecorder.declare(FamilyDeclarations.MORTALITY_PERMILLE_KEY, permille.toString())
    }

    /**
     * 与 [f] 同 `decisionSeed` 的参照流：新建一个会话（不跑任何日拍），从头取 [count] 个抽数。
     * 台架 [TestContext.rng] 是生产同一实现，同种子 + 同包 ⇒ 与本会话未消耗前的流逐位一致。
     */
    private fun referenceDraws(seed: Long, count: Int): List<Int> {
        val reference = fixture(seed)
        return List(count) { reference.context.rng.nextInt(1000) }
    }

    private fun drawsAfter(f: Fixture, count: Int): List<Int> = List(count) { f.context.rng.nextInt(1000) }

    private fun roster(f: Fixture): String? = f.context.storage.get(FamilyDeclarations.CHILD_IDS)

    private fun mortalityLogs(f: Fixture): List<LogEntry> =
        f.context.logEntries.filter { it.message.contains("mod.family mortality") }

    private fun reconcileLogs(f: Fixture): List<LogEntry> =
        f.context.logEntries.filter { it.message.contains("mod.family reconcile") }

    @Test
    fun zeroPermilleConsumesNoRandomNumbersAndRetiresNobody() {
        val seed = 20_261_118L
        val f = fixture(seed)
        // 名册非空（两代同堂）：抽样候选至少两人，若实现「先抽后判 0」就会耗掉随机数。
        val head = f.mod.adoptChild("当主", father = null)
        val child = f.mod.adoptChild("甲", head)
        f.mod.adoptChild("乙", head)
        val expected = referenceDraws(seed, 12)

        repeat(3) { f.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(expected, drawsAfter(f, 12), "默认 0‰：日拍必须零随机数消耗（流位置与接线前逐位一致）")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(head), "0‰ 不产生任何死亡")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(child))
        assertEquals(3L, f.world.entities.getInt(child, FamilyKeys.age), "成长照常推进")
        assertEquals(3, f.mod.state.registeredChildren, "名册不因 0‰ 变化")
        assertTrue(mortalityLogs(f).isEmpty(), "零标死 ⇒ 零 mortality 日志")
    }

    @Test
    fun thousandPermilleRetiresEveryCandidateAndSkipsTheirGrowth() {
        val f = fixture(seed = 1L)
        declarePermille(f, 1000)
        val first = f.mod.adoptChild("甲", father = null)
        val second = f.mod.adoptChild("乙", father = null)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        listOf(first, second).forEach { id ->
            assertEquals(EntityState.RETIRED, f.world.entities.stateOf(id), "1000‰ ⇒ 本拍必死")
            assertEquals(0L, f.world.entities.getInt(id, FamilyKeys.age), "抽样在成长之前 ⇒ 本拍死者不涨年龄")
        }
        assertEquals(0, f.mod.state.registeredChildren, "死者同拍摘出名册（走 markDeceased 的 D2 对齐）")
        assertEquals("", roster(f), "名册写穿为空串")
        val log = mortalityLogs(f).single()
        assertEquals("INFO", log.level)
        assertTrue(log.message.contains("n=2"), "一条日志汇总本拍标死数：${log.message}")
    }

    @Test
    fun childrenOfALivingHeadAreNotCandidates() {
        val f = fixture(seed = 2L)
        declarePermille(f, 1000)
        val head = f.mod.adoptChild("当主", father = null)
        val childA = f.mod.adoptChild("甲", head)
        val childB = f.mod.adoptChild("乙", head)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(head), "名册中的父辈是候选")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(childA), "有父引用的子嗣不是本拍候选")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(childB))
        assertEquals(1L, f.world.entities.getInt(childA, FamilyKeys.age), "活着的子嗣照常成长")
        assertEquals(2, f.mod.state.registeredChildren, "死者摘除后名册只剩两名子嗣")
        assertEquals(head, f.world.entities.getRef(childA, FamilyKeys.father), "manual：不自动改父引用（死亡只记状态）")
        assertTrue(mortalityLogs(f).single().message.contains("n=1"), "本拍只标死父辈一人")
    }

    @Test
    fun intermediatePermilleIsDeterministicForTheSameDecisionSeed() {
        val first = retiredPattern(seed = 424_242L, permille = 400, children = 8)
        val second = retiredPattern(seed = 424_242L, permille = 400, children = 8)
        assertEquals(first, second, "同种子 + 同名册 ⇒ 抽样结果逐位相同（顺序确定性）")

        val other = retiredPattern(seed = 777_777L, permille = 400, children = 8)
        assertNotEquals(first, other, "换决策种子 ⇒ 抽样结果必须改变（不是常量表）")
    }

    @Test
    fun alreadyRetiredCandidateIsFilteredBeforeTheDraw() {
        val seed = 5_150L
        val f = fixture(seed)
        declarePermille(f, 1000)
        val head = f.mod.adoptChild("当主", father = null)
        val child = f.mod.adoptChild("甲", head)
        assertTrue(f.mod.markDeceased(head), "先由 A1 标死父辈（名册摘除，子嗣留册）")
        val expected = referenceDraws(seed, 8)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(expected, drawsAfter(f, 8), "唯一候选（子嗣的父引用）已 RETIRED ⇒ 收集阶段过滤，不占随机流")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(child), "活着的人不受牵连")
        assertEquals(1L, f.world.entities.getInt(child, FamilyKeys.age), "成长照常")
        assertTrue(mortalityLogs(f).isEmpty(), "无可抽候选 ⇒ 零 mortality 日志")
    }

    @Test
    fun emptyRosterDrawsNothingAndLogsNothing() {
        val seed = 31L
        val f = fixture(seed)
        declarePermille(f, 1000)
        val expected = referenceDraws(seed, 9)

        repeat(3) { f.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(expected, drawsAfter(f, 9), "空名册 ⇒ 候选为空 ⇒ 零抽数")
        assertEquals(3L, f.mod.state.growthTicks, "日拍照常推进")
        assertTrue(mortalityLogs(f).isEmpty(), "空名册零日志")
        assertTrue(reconcileLogs(f).isEmpty())
    }

    @Test
    fun eachCandidateIsDrawnExactlyOncePerTick() {
        val seed = 8_675_309L
        val f = fixture(seed)
        declarePermille(f, 1)
        repeat(20) { f.mod.adoptChild("子$it", father = null) }
        val reference = referenceDraws(seed, 30)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(
            reference.drop(20),
            drawsAfter(f, 10),
            "20 名候选 ⇒ 本拍恰好消耗 20 个随机数（每候选一次，不多不少）",
        )
    }

    @Test
    fun reconcileRunsBeforeTheMortalitySweepWithinTheSameTick() {
        val f = fixture(seed = 6L)
        declarePermille(f, 1000)
        val head = f.mod.adoptChild("当主", father = null)
        val child = f.mod.adoptChild("甲", head)
        // A2：ERB 直调只改实体段 state，名册还留着 ⇒ 本拍 reconcile 必须先摘掉它。
        val erbDead = f.mod.adoptChild("殁", father = null)
        f.world.entities.setState(erbDead, EntityState.RETIRED)

        f.context.fireJob(FamilyDeclarations.GROWTH)

        val ordered = f.context.logEntries.filter {
            it.message.contains("mod.family reconcile") || it.message.contains("mod.family mortality")
        }
        assertEquals(2, ordered.size, "本拍一条 reconcile、一条 mortality：${ordered.map { it.message }}")
        assertTrue(ordered[0].message.contains("mod.family reconcile"), "reconcile 排在抽样之前：${ordered[0].message}")
        assertTrue(ordered[1].message.contains("mod.family mortality"), "抽样在其后：${ordered[1].message}")

        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(head), "候选 = 名册中的父辈，1000‰ 必死")
        assertEquals(EntityState.ACTIVE, f.world.entities.stateOf(child))
        assertEquals(1L, f.world.entities.getInt(child, FamilyKeys.age), "成长排在抽样之后，活着的子嗣照涨")
        assertEquals(1, f.mod.state.registeredChildren)
        assertTrue(mortalityLogs(f).single().message.contains("n=1"), "抽样只标死一名候选")

        // 本拍死者已由 markDeceased 摘除名册 ⇒ 下一拍 reconcile 无残留可摘，也不会重复记日志。
        f.context.fireJob(FamilyDeclarations.GROWTH)
        assertEquals(1, reconcileLogs(f).size, "markDeceased 已对齐名册 ⇒ 下一拍 reconcile 无可摘")
        assertEquals(1, mortalityLogs(f).size, "候选集合此刻为空 ⇒ 不再抽、不再记")
        assertEquals(2L, f.world.entities.getInt(child, FamilyKeys.age))
    }

    @Test
    fun settingIsReadEveryTickAndNotCachedAtLoad() {
        val seed = 2_718L
        val f = fixture(seed)
        val first = f.mod.adoptChild("甲", father = null)
        val second = f.mod.adoptChild("乙", father = null)
        val reference = referenceDraws(seed, 20)

        f.context.fireJob(FamilyDeclarations.GROWTH)
        assertEquals(reference.take(5), drawsAfter(f, 5), "默认 0‰ 的第一拍零抽数")

        // 中途改设置：下一拍立即生效（每拍现读，不做 onLoad 缓存）。
        declarePermille(f, 1000)
        f.context.fireJob(FamilyDeclarations.GROWTH)

        assertEquals(reference.drop(7).take(5), drawsAfter(f, 5), "第二拍恰好抽 2 次（两名候选）")
        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(first), "改设置后下一拍即生效")
        assertEquals(EntityState.RETIRED, f.world.entities.stateOf(second))
        assertEquals(0, f.mod.state.registeredChildren)
    }

    @Test
    fun headReferencedBySeveralChildrenIsDrawnOncePerTick() {
        val seed = 112_358L
        val f = fixture(seed)
        declarePermille(f, 1)
        val head = f.mod.adoptChild("当主", father = null)
        repeat(3) { f.mod.adoptChild("子$it", head) }
        val reference = referenceDraws(seed, 6)

        repeat(2) { f.context.fireJob(FamilyDeclarations.GROWTH) }

        assertEquals(
            reference.drop(2),
            drawsAfter(f, 4),
            "同一父辈被三名子嗣引用 + 自身 father=NULL ⇒ 去重后每拍只抽 1 次",
        )
        assertEquals(2L, f.mod.state.growthTicks)
    }

    /** 同一种子跑 [children] 名 father=NULL 候选、单拍 400‰，返回每人是否被抽死的位图。 */
    private fun retiredPattern(seed: Long, permille: Int, children: Int): List<Boolean> {
        val f = fixture(seed)
        declarePermille(f, permille)
        val ids = (1..children).map { f.mod.adoptChild("子$it", father = null) }
        f.context.fireJob(FamilyDeclarations.GROWTH)
        return ids.map { f.world.entities.stateOf(it) == EntityState.RETIRED }
    }
}
