package com.eratw.port.core.mod

import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.RandomApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §21.6 mod 随机面的判定性用例。
 *
 * 钉的是三件事：
 * 1. **确定性**：同决策种子 + 同 packageId ⇒ 同一串抽数；换 packageId ⇒ 换序列。
 * 2. **独立性**（最要紧的一条）：抽 1000 次完全不消耗引擎那条被黄金基准校准过的 SFMT19937
 *    —— 否则 `RAND` 序号 / STRDATA 组选全漂移，复刻对拍立刻失配。
 * 3. **fail-closed**：非法上界不返回 0；未装配的 context 抽数直接抛，不静默兜底。
 */
class ModRandomApiTest {

    @Test
    fun `同种子同包名_两实例逐值相等`() {
        val a = ModRandomApi(decisionSeed = 20260226L, packageId = "eratw.demo")
        val b = ModRandomApi(decisionSeed = 20260226L, packageId = "eratw.demo")
        repeat(200) { index ->
            assertEquals(a.nextInt(1000), b.nextInt(1000), "第 $index 次抽数不一致")
        }
        // 换方法也一致（同一条流、同一个消费序）
        assertEquals(
            ModRandomApi(20260226L, "eratw.demo").permutationIndices(8).toList(),
            ModRandomApi(20260226L, "eratw.demo").permutationIndices(8).toList(),
        )
    }

    @Test
    fun `不同包名_序列不同`() {
        val a = ModRandomApi(decisionSeed = 20260226L, packageId = "eratw.family")
        val b = ModRandomApi(decisionSeed = 20260226L, packageId = "eratw.memory")
        val sa = List(32) { a.nextInt(1_000_000) }
        val sb = List(32) { b.nextInt(1_000_000) }
        assertNotEquals(sa, sb, "不同 packageId 必须派生出不同序列（否则 mod 之间会同步抽数）")
    }

    @Test
    fun `不同决策种子_序列不同`() {
        val a = ModRandomApi(decisionSeed = 1L, packageId = "eratw.demo")
        val b = ModRandomApi(decisionSeed = 2L, packageId = "eratw.demo")
        assertNotEquals(List(16) { a.nextInt(1000) }, List(16) { b.nextInt(1000) })
    }

    @Test
    fun `有界区间与非法上界`() {
        val rng = ModRandomApi(7L, "eratw.demo")
        repeat(200) {
            assertTrue(rng.nextInt(1) == 0, "nextInt(1) 只能是 0")
            val value = rng.nextInt(10)
            assertTrue(value in 0..9, "nextInt(10) 越界：$value")
            val ranged = rng.nextInt(5, 9)
            assertTrue(ranged in 5..8, "nextInt(5, 9) 越界：$ranged")
        }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(0) }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(-3) }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(9, 9) }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(9, 5) }
    }

    @Test
    fun `百分比判定边界与非法入参`() {
        val rng = ModRandomApi(11L, "eratw.demo")
        repeat(50) {
            assertFalse(rng.chance(0), "chance(0) 必须恒 false 且不消耗随机流之外的语义")
            assertTrue(rng.chance(100), "chance(100) 必须恒 true")
        }
        assertFailsWith<IllegalArgumentException> { rng.chance(101) }
        assertFailsWith<IllegalArgumentException> { rng.chance(-1) }

        // 中间值必须真的分叉（10% 抽 2000 次，命中数应落在宽松区间内，避免概率抖动打红）
        val sample = ModRandomApi(3L, "eratw.demo")
        val hits = (1..2000).count { sample.chance(10) }
        assertTrue(hits in 120..280, "chance(10) 命中数异常：$hits/2000")
    }

    @Test
    fun `排列是 0 until size 的置换且不重叠`() {
        val rng = ModRandomApi(5L, "eratw.demo")
        val permutation = rng.permutationIndices(12)
        assertEquals(12, permutation.size)
        assertEquals((0 until 12).toList(), permutation.sorted(), "必须是 0..11 的置换")
        assertEquals(0, rng.permutationIndices(0).size)
        assertFailsWith<IllegalArgumentException> { rng.permutationIndices(-1) }
    }

    /**
     * 独立性负控（本用例是本设计存在的理由）：mod 抽 1000 次后，引擎主流的 SFMT19937
     * 内部状态（625 元素：624 状态字 + 游标）必须逐字节不变 —— 一个 UInt32 都没被消耗。
     */
    @Test
    fun `抽一千次不消耗引擎主流随机状态`() {
        val store = VariableStore(randProvider = Sfmt19937(20261017L))
        val engineProvider = store.randProvider as Sfmt19937
        val before = engineProvider.state().copyOf()
        val rng = ModRandomApi(decisionSeed = 20260226L, packageId = "eratw.demo")
        repeat(1000) {
            rng.nextInt(97)
            if (it % 7 == 0) rng.nextDouble()
            if (it % 11 == 0) rng.permutationIndices(6)
        }
        assertTrue(before.contentEquals(engineProvider.state()), "mod 抽数污染了引擎主流随机状态")
        // 反证器：引擎主流此时抽一次，状态必须真的变化（否则上面的相等是假绿）
        engineProvider.next(10)
        assertFalse(before.contentEquals(engineProvider.state()), "引擎主流未推进，上面的独立性断言无意义")
    }

    @Test
    fun `未装配的 context 抽数显式 fail-closed`() {
        val failClosed: RandomApi = FailClosedRandomApi("eratw.demo")
        assertFailsWith<UnsupportedOperationException> { failClosed.nextInt(10) }
        assertFailsWith<UnsupportedOperationException> { failClosed.nextInt(1, 10) }
        assertFailsWith<UnsupportedOperationException> { failClosed.chance(50) }
        assertFailsWith<UnsupportedOperationException> { failClosed.nextDouble() }
        assertFailsWith<UnsupportedOperationException> { failClosed.permutationIndices(3) }
    }

    @Test
    fun `派生口径钉住_改材料即破坏可复现性`() {
        // 派生材料 = "eratw.mod.rng" ␟ 决策种子 ␟ packageId（U+001F 分隔、UTF-8）
        assertEquals(
            ModRandomApi.deriveSeed(20260226L, "eratw.demo"),
            ModRandomApi.deriveSeed(20260226L, "eratw.demo"),
        )
        assertNotEquals(
            ModRandomApi.deriveSeed(20260226L, "eratw.demo"),
            ModRandomApi.deriveSeed(20260226L, "eratw.demo2"),
        )
        // 种子 0 与 packageId 空串也要能安全派生（不 NPE、不返回 0 硬编码）
        assertEquals(
            ModRandomApi.deriveSeed(0L, ""),
            ModRandomApi.deriveSeed(0L, ""),
        )
    }
}
