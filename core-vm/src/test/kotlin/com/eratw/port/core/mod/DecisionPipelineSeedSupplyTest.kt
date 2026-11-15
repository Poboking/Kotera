package com.eratw.port.core.mod

import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * W2-SEED 的管线侧种子供给缝闸（CONTRACT-RT2-SEED 判据 2/4 的 core 定向面）。
 *
 * 钉住：供给后管线种子 = 档内种子（draw 实际收到新种子）、供给后 restoreState 相等闸
 * 自然通过、一次性闸（二次供给拒）、已消费闸（抽取发生后供给拒）、相等闸本体语义不变
 * （不供给直接 restore 仍拒）、供给缝不在仲裁路径上（不供给 draw 收构造种子）。
 *
 * 本车是功能新增（不是 bug 修复），根因层断言不适用 AGENTS §3.1。
 */
class DecisionPipelineSeedSupplyTest {

    private fun candidate(
        pkg: String = "pkg.a",
        key: String = "k",
        actor: Long = 1L,
        weight: Int = 1,
    ): ActionCandidate = ActionCandidate(
        providerPackage = pkg,
        candidateKey = key,
        actor = EntityId(actor),
        weight = weight,
        handlerKey = key,
    )

    private fun ctx(
        domain: String = "ACTION",
        day: Long = 10L,
        phase: String = "DAY_START",
    ): ActionSelectionContext = ActionSelectionContext(
        domain = domain,
        time = GameTime(day, phase),
    )

    /** 决策随机流 spy：记录每次 draw 实际收到的种子（供给缝的判据面）。 */
    private class SeedSpyRandom : DecisionRandomSource {
        val seeds = ArrayList<Long>()

        override fun draw(seed: Long, key: DecisionDrawKey, bound: Long): Long {
            seeds += seed
            return 0L
        }
    }

    private fun registerOne(pipeline: DecisionPipeline) {
        val api = pipeline.actionApi("pkg.a")
        api.provide("ACTION") { listOf(candidate(key = "a", weight = 1)) }
        api.on("a") {}
    }

    @Test
    fun `01 供给后抽取实际使用档内种子`() {
        val seedA = DecisionSeed.derive("entropy-a".toByteArray())
        val seedB = DecisionSeed.derive("entropy-b".toByteArray())
        assertNotEquals(seedA, seedB)
        val random = SeedSpyRandom()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), seedA, random = random)
        registerOne(pipeline)

        assertEquals(seedA, pipeline.currentDecisionSeed, "供给前当前种子读数 = 构造种子")
        pipeline.supplySeed(seedB)
        assertEquals(seedB, pipeline.currentDecisionSeed, "供给后当前种子读数 = 档内种子")
        pipeline.run(ctx())
        assertEquals(1, random.seeds.size)
        assertEquals(seedB, random.seeds.single(), "draw 收到的是供给后的档内种子（判据 2 的随机流锚）")
    }

    @Test
    fun `02 供给后restoreState相等闸自然通过且快照种子随档`() {
        val seedA = DecisionSeed.derive("entropy-a".toByteArray())
        val seedB = DecisionSeed.derive("entropy-b".toByteArray())
        val source = DecisionPipeline.of(listOf("pkg.a"), seedB)
        registerOne(source)
        source.run(ctx(day = 3L))
        val saved = source.snapshotState()
        assertEquals(seedB, saved.decisionSeed)

        // 新局管线（种子 A，未消费）：先供给档内种子 B，再回灌持久态——相等闸自然放行。
        val target = DecisionPipeline.of(listOf("pkg.a"), seedA)
        registerOne(target)
        target.supplySeed(saved.decisionSeed)
        target.restoreState(saved)
        assertEquals(seedB, target.snapshotState().decisionSeed, "重载后管线种子 = 档内种子（判据 2）")
        assertEquals(saved.dayCounts, target.snapshotState().dayCounts, "计数随档无损")
        assertEquals(saved.nextOrdinal, target.snapshotState().nextOrdinal, "序号随档无损")
    }

    @Test
    fun `03 一次性闸二次供给拒`() {
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), DecisionSeed.derive("a".toByteArray()))
        pipeline.supplySeed(42L)
        val again = assertThrows<IllegalArgumentException> { pipeline.supplySeed(43L) }
        assertTrue(again.message!!.contains("一次性"), "点名一次性闸：" + again.message)
        assertEquals(42L, pipeline.currentDecisionSeed, "拒绝后种子不漂移")
    }

    @Test
    fun `04 已消费闸抽取后供给拒`() {
        val seed = DecisionSeed.derive("a".toByteArray())
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), seed)
        registerOne(pipeline)
        pipeline.run(ctx())
        assertEquals(1, pipeline.snapshotState().nextOrdinal, "一轮仲裁推进了序号 = 已消费")
        val refused = assertThrows<IllegalArgumentException> { pipeline.supplySeed(42L) }
        assertTrue(refused.message!!.contains("已消费"), "点名已消费闸（判据 4 的保持面）：" + refused.message)
        assertEquals(seed, pipeline.currentDecisionSeed, "拒绝后种子保持装配值")
    }

    @Test
    fun `05 相等闸本体不动且供给后空态回灌放行`() {
        val seedA = DecisionSeed.derive("entropy-a".toByteArray())
        val seedB = DecisionSeed.derive("entropy-b".toByteArray())
        val saved = DecisionPipeline.of(listOf("pkg.a"), seedB).snapshotState()
        assertEquals(0, saved.nextOrdinal, "空态快照序号为 0")

        // 不供给直接回灌异种子态：restoreState 相等闸原样 fail-closed（判据 4 本体不动）。
        val gate = DecisionPipeline.of(listOf("pkg.a"), seedA)
        val mismatch = assertThrows<IllegalArgumentException> { gate.restoreState(saved) }
        assertTrue(mismatch.message!!.contains("种子不符"), "相等闸语义不动：" + mismatch.message)
        assertEquals(seedA, gate.currentDecisionSeed, "闸拒绝后种子不被改写")

        // 供给后同一态回灌放行（判据 2 的采纳序）。
        val adopt = DecisionPipeline.of(listOf("pkg.a"), seedA)
        adopt.supplySeed(seedB)
        adopt.restoreState(saved)
        assertEquals(seedB, adopt.currentDecisionSeed, "采纳后种子 = 档内种子")
    }

    @Test
    fun `06 不供给时仲裁路径零改动`() {
        // 同一管线不供给直接跑：draw 收到构造种子（供给缝不在仲裁路径上）。
        val seed = DecisionSeed.derive("entropy-a".toByteArray())
        val random = SeedSpyRandom()
        val pipeline = DecisionPipeline.of(listOf("pkg.a"), seed, random = random)
        registerOne(pipeline)
        pipeline.run(ctx())
        assertEquals(seed, random.seeds.single(), "不供给 = draw 收构造种子")
        val state = pipeline.snapshotState()
        assertEquals(seed, state.decisionSeed)
        assertEquals(1, state.nextOrdinal)
        assertTrue(state.dayCounts.isNotEmpty(), "每日记账不受供给缝影响")
    }
}
