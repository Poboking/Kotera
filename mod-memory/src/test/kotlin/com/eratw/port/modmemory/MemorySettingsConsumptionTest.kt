package com.eratw.port.modmemory

import com.eratw.port.modapi.create
import com.eratw.port.modapi.get
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.has
import com.eratw.port.modmemory.generated.MemoryKeys
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestModSession
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 载体**消费** per-mod 设置的行为面（task-12 / Lane L）：`mod-memory` 两个已声明键。
 *
 * 装置只用 `:mod-testing` 的内存台架（`TestWorld` + `TestContext.settingsRecorder`），
 * 不启真引擎 —— 与 [MemoryModTest]（真宿主）分工不同：那边防宿主接线回归，这里钉
 * 「设置键值 → 业务结果」的映射，且每条用例都是秒级。
 *
 * 三个立论：
 * 1. **速率语义**：第一手事实每跌 `decayRate`、传闻每跌 `decayRate * 2`（= 声明常量
 *    `FIRSTHAND_DAILY_DECAY=1` / `HEARSAY_DAILY_DECAY=2` 同倍缩放），下限仍 0；
 * 2. **每拍现读**：`decayOneDay` / `teach` 每次调用都查 `context.settings`，中途改值
 *    下一拍/下一次传授立即生效（[decayRateIsReadFreshOnEveryTick] 反向钉住「onLoad 缓存」）；
 * 3. **fail-closed**：未声明的键在台架上抛 `IllegalArgumentException`（真宿主上是
 *    `UnsupportedOperationException`，见 `MemoryModTest` 的未注入用例），载体不拿默认值兜底。
 */
class MemorySettingsConsumptionTest {

    private class Fixture(val world: TestWorld, val mod: MemoryMod, val session: TestModSession) {
        val context: TestContext get() = session.context
        fun person(): com.eratw.port.modapi.EntityId = world.entities.create(MemoryDeclarations.PERSON)
    }

    private fun fixture(): Fixture {
        val world = TestWorld.of(MemoryDeclarations)
        val mod = MemoryMod()
        val session = runMod(mod, world, MemoryDeclarations)
        // 按声明默认值预置两个键（= 生产快照在「持久化值缺省」时的 resolveText 结果）。
        session.context.settingsRecorder.declare(MemoryDeclarations.DECAY_RATE_KEY, "1")
        session.context.settingsRecorder.declare(MemoryDeclarations.TEACH_REQUIRES_KNOWS_KEY, "false")
        return Fixture(world, mod, session)
    }

    private fun Fixture.recordFact(provenance: Provenance, confidence: Long): com.eratw.port.modapi.EntityId {
        val owner = person()
        return mod.record(
            owner = owner,
            FactValues(
                subject = owner,
                predicate = "settings.test",
                provenance = provenance,
                confidence = confidence,
            ),
        )
    }

    @Test
    fun decayRateTwoDropsFirsthandByTwoAndHearsayByFour() {
        val f = fixture()
        f.context.settingsRecorder.declare(MemoryDeclarations.DECAY_RATE_KEY, "2")
        val firsthand = f.recordFact(Provenance.FIRSTHAND, confidence = 10L)
        val hearsay = f.recordFact(Provenance.HEARSAY, confidence = 10L)

        f.context.fireJob(MemoryDeclarations.DECAY)

        assertEquals(8L, f.world.entities.getInt(firsthand, MemoryKeys.confidence), "第一手跌 rate=2")
        assertEquals(6L, f.world.entities.getInt(hearsay, MemoryKeys.confidence), "传闻跌 rate*2=4")
        assertEquals(1L, f.mod.decayTicks)
        assertEquals("1", f.context.storage.get(MemoryDeclarations.DECAY_TICKS))
    }

    @Test
    fun decayRateDeclarationDefaultOneDropsFirsthandByOneAndHearsayByTwo() {
        val f = fixture()
        val firsthand = f.recordFact(Provenance.FIRSTHAND, confidence = 10L)
        val hearsay = f.recordFact(Provenance.HEARSAY, confidence = 10L)

        f.context.fireJob(MemoryDeclarations.DECAY)

        assertEquals(9L, f.world.entities.getInt(firsthand, MemoryKeys.confidence), "声明默认 1")
        assertEquals(8L, f.world.entities.getInt(hearsay, MemoryKeys.confidence), "传闻 = 1*2")
    }

    @Test
    fun decayRateZeroLeavesConfidenceUntouched() {
        val f = fixture()
        f.context.settingsRecorder.declare(MemoryDeclarations.DECAY_RATE_KEY, "0")
        val fact = f.recordFact(Provenance.FIRSTHAND, confidence = 10L)

        f.context.fireJob(MemoryDeclarations.DECAY)

        assertEquals(10L, f.world.entities.getInt(fact, MemoryKeys.confidence), "rate=0 ⇒ 不衰减")
        assertEquals(1L, f.mod.decayTicks, "日计数照走")
    }

    @Test
    fun confidenceNeverGoesBelowZeroAtHighRate() {
        val f = fixture()
        f.context.settingsRecorder.declare(MemoryDeclarations.DECAY_RATE_KEY, "10")
        val fact = f.recordFact(Provenance.HEARSAY, confidence = 3L)

        f.context.fireJob(MemoryDeclarations.DECAY)

        assertEquals(0L, f.world.entities.getInt(fact, MemoryKeys.confidence), "下限 0（3-20）")
    }

    @Test
    fun decayRateIsReadFreshOnEveryTick() {
        val f = fixture()
        val fact = f.recordFact(Provenance.FIRSTHAND, confidence = 10L)

        f.context.fireJob(MemoryDeclarations.DECAY)
        assertEquals(9L, f.world.entities.getInt(fact, MemoryKeys.confidence))

        // 中途改设置：下一拍立即按 rate=2 走（若 onLoad 缓存成 val，这里仍是 8）
        f.context.settingsRecorder.declare(MemoryDeclarations.DECAY_RATE_KEY, "2")
        f.context.fireJob(MemoryDeclarations.DECAY)
        assertEquals(7L, f.world.entities.getInt(fact, MemoryKeys.confidence), "9-2 ⇒ 7")
        assertEquals(2L, f.mod.decayTicks)
    }

    @Test
    fun teachRequiresKnowsRejectsLearnerWithoutEdgeAndCreatesNoEdge() {
        val f = fixture()
        f.context.settingsRecorder.declare(MemoryDeclarations.TEACH_REQUIRES_KNOWS_KEY, "true")
        val owner = f.person()
        val learner = f.person()
        val fact = f.mod.record(
            owner = owner,
            FactValues(subject = owner, predicate = "gated", confidence = 50L),
        )

        val taught = f.mod.teach(learner = learner, fact = fact)

        assertFalse(taught, "门开启且无边 ⇒ 返回 false（不抛）")
        assertFalse(
            f.world.relationships.has(learner, MemoryDeclarations.KNOWS, fact),
            "门开启且无边 ⇒ 不建边",
        )
        assertEquals(emptyList(), f.mod.queries.knowledgeOf(learner), "学习者知识面仍空")
    }

    @Test
    fun teachRequiresKnowsDefaultFalseStillTeaches() {
        val f = fixture()
        val owner = f.person()
        val learner = f.person()
        val fact = f.mod.record(
            owner = owner,
            FactValues(subject = owner, predicate = "open", confidence = 50L),
        )

        assertTrue(f.mod.teach(learner = learner, fact = fact), "声明默认 false ⇒ 照常传授")
        assertTrue(f.world.relationships.has(learner, MemoryDeclarations.KNOWS, fact))
        assertEquals(
            Provenance.HEARSAY,
            f.mod.queries.provenanceOf(learner, fact),
            "缺省来源 = 听闻",
        )
    }

    /**
     * 清掉「持久值」但声明仍在 ⇒ 衰减倍率回落到声明默认 1（第一手 −1 / 听闻 −2）。
     *
     * 本用例原名 `undeclaredDecayRateFailsClosedBeforeMutatingAnyState`，钉的是台架旧语义
     * （`settingsRecorder.clear()` 被当作「宿主没注入设置面」）。台架自 paritysem 波起复用
     * 生产 `ModSettingsSnapshot`：clear 只清持久值、声明仍在 ⇒ 读 default；
     * 真·未声明键抛 IAE 的行为由台架 `ParitySemanticsTest` 对拍生产原文覆盖。
     */
    @Test
    fun clearedDecayRateFallsBackToDeclarationDefault() {
        val f = fixture()
        val fact = f.recordFact(Provenance.FIRSTHAND, confidence = 10L)

        f.context.settingsRecorder.clear()
        f.context.fireJob(MemoryDeclarations.DECAY)

        assertEquals(1L, f.mod.decayTicks, "默认 1 ⇒ 拍照常跑，账本 +1")
        assertEquals(9L, f.world.entities.getInt(fact, MemoryKeys.confidence), "默认倍率 1 ⇒ 第一手 10 → 9")
    }
}
