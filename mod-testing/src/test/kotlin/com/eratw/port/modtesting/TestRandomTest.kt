package com.eratw.port.modtesting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [TestRandom] 自测：钉住「LCG 状态确实逐步推进」与「任意合法区间边界正确」两条语义。
 *
 * 前一版 raw() 是纯表达式体、从不把递推结果写回 state，导致整条流退化成常量；本文件的
 * 「连续抽数不退化成常量」系列在该实现下必红。
 */
class TestRandomTest {

    @Test
    fun `连续抽数不退化成常量（LCG状态逐次推进）`() {
        val rng = TestRandom()
        val firstDouble = rng.nextDouble()
        val doubles = List(32) { rng.nextDouble() }
        assertTrue(
            doubles.any { it != firstDouble },
            "连续 nextDouble 不得全等于首值：state 不写回时整条流是常量",
        )
        assertTrue(doubles.all { it >= 0.0 && it < 1.0 }, "nextDouble 必须落在 [0,1)")

        val ints = List(32) { rng.nextInt(Int.MAX_VALUE) }
        assertTrue(
            ints.distinct().size > 1,
            "连续 nextInt(Int.MAX_VALUE) 不得恒为同一值：${ints.take(4)}",
        )
    }

    @Test
    fun `permutationIndices连续两次不再固定同一排列`() {
        val rng = TestRandom(99L)
        val first = rng.permutationIndices(16).toList()
        val second = rng.permutationIndices(16).toList()
        assertNotEquals(first, second, "两次排列不应固定相同：state 不写回时每次都是同一排列")
    }

    @Test
    fun `宽区间nextInt不因Int减法溢出抛错且落在界内`() {
        val rng = TestRandom()
        repeat(64) {
            val value = rng.nextInt(Int.MIN_VALUE, Int.MAX_VALUE)
            assertTrue(
                value >= Int.MIN_VALUE && value < Int.MAX_VALUE,
                "宽区间抽数必须落在 [Int.MIN_VALUE, Int.MAX_VALUE)：$value",
            )
        }
    }

    @Test
    fun `窄区间与端点边界正确且同种子可复现`() {
        val left = TestRandom(2024L)
        val right = TestRandom(2024L)
        repeat(64) {
            val a = left.nextInt(-5, 6)
            val b = right.nextInt(-5, 6)
            assertEquals(a, b, "同种子必须逐值可复现")
            assertTrue(a in -5..5, "窄区间抽数越界：$a")
        }
        repeat(8) {
            assertEquals(7, left.nextInt(7, 8), "宽度为 1 的区间只能返回下界")
        }
    }
}
