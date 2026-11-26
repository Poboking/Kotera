package com.eratw.port.modtesting

import com.eratw.port.modapi.RandomApi

/**
 * 台架专用的确定性随机替代流（63 位 LCG）——**仅作确定性替身，不镜像生产派生**。
 *
 * 与 ERB 的 `RAND` 是两条独立流，也与生产的 [com.eratw.port.core.mod.ModRandomApi]
 * （「决策种子 + packageId」经 SHA-256 派生 SFMT19937）不是同一条：数值不同、非法入参文案不同。
 * [TestContext] 自 paritysem 起改用生产 `ModRandomApi`，本类保留给需要「与生产无关的
 * 可复现流」的极少数用例直接构造；要验与真机同流请用生产类。
 *
 * 实现细节：63 位正数域 LCG（`state = state * MULTIPLIER + INCREMENT`，逐位截断到 63 位），
 * 取高位做 `nextInt`，避免低位周期短。
 */
internal class TestRandom(seed: Long = DEFAULT_SEED) : RandomApi {

    private var state: Long = if (seed == 0L) DEFAULT_SEED else seed

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "nextInt(bound) 的 bound 必须 > 0，实际 $bound" }
        return (raw().ushr(1) % bound.toLong()).toInt()
    }

    override fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
        require(maxExclusive > minInclusive) {
            "nextInt(min, max) 必须 max > min，实际 [$minInclusive, $maxExclusive)"
        }
        // 宽度用 Long 算：最大 2^32-1，不会像 Int 减法那样溢出成负数。
        val width = maxExclusive.toLong() - minInclusive.toLong()
        val offset = if (width <= Int.MAX_VALUE) {
            nextInt(width.toInt()).toLong()
        } else {
            // 超宽区间（宽度 > Int.MAX_VALUE）：走与 nextInt(bound) 相同的取高位取模语义。
            raw().ushr(1) % width
        }
        return (minInclusive.toLong() + offset).toInt()
    }

    override fun chance(percent: Int): Boolean {
        require(percent in 0..PERCENT_RANGE) {
            "chance(percent) 的 percent 必须在 0..$PERCENT_RANGE，实际 $percent"
        }
        return when {
            percent <= 0 -> false
            percent >= PERCENT_RANGE -> true
            else -> nextInt(PERCENT_RANGE) < percent
        }
    }

    override fun nextDouble(): Double = raw().toDouble() / (POSITIVE_MASK.toDouble() + 1.0)

    override fun permutationIndices(size: Int): IntArray {
        require(size >= 0) { "permutationIndices(size) 的 size 必须 >= 0，实际 $size" }
        val indices = IntArray(size) { index -> index }
        for (index in size - 1 downTo 1) {
            val other = nextInt(index + 1)
            val tmp = indices[index]
            indices[index] = indices[other]
            indices[other] = tmp
        }
        return indices
    }

    /** 推进 63 位正数域 LCG 状态并取值（避开 `Long.MIN_VALUE` 与符号位）。 */
    private fun raw(): Long {
        // Long 乘法/加法自然溢出即模 2^64，正是 LCG 所需；再与掩码截断到 63 位正数域。
        state = (state * MULTIPLIER + INCREMENT) and POSITIVE_MASK
        return state
    }

    private companion object {
        const val DEFAULT_SEED: Long = 20261117L
        const val MULTIPLIER: Long = 6364136223846793005L
        const val INCREMENT: Long = 1442695040888963407L

        /** 63 位正数域掩码。 */
        const val POSITIVE_MASK: Long = Long.MAX_VALUE
        const val PERCENT_RANGE: Int = 100
    }
}
