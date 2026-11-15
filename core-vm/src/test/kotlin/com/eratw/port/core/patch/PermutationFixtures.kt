package com.eratw.port.core.patch

import java.util.Random

/**
 * 判据 b 的排列夹具：小规模**穷举全部排列**，大规模用固定种子的确定性洗牌。
 *
 * 不用 `kotlin.random`（核心层纯 JVM 纪律），也不碰系统时钟或全局可变状态：同一输入在
 * 任何 JVM、任何运行次数下都产出同一组排列，因此「排列不变性」的回归不会偶发假绿。
 * 手写 4 种排列不足以覆盖就绪节点的出现顺序，n ≤ 4 时 n! 的成本可忽略，直接穷举。
 */
internal object PermutationFixtures {

    /** 递归取尽 n! 个排列；顺序稳定（按原列表元素为头依次展开）。 */
    fun <T> all(items: List<T>): List<List<T>> {
        if (items.isEmpty()) return listOf(emptyList())
        val out = ArrayList<List<T>>(factorial(items.size))
        for ((i, head) in items.withIndex()) {
            val rest = items.toMutableList().also { it.removeAt(i) }
            for (tail in all(rest)) out += listOf(head) + tail
        }
        return out
    }

    /**
     * 固定种子 Fisher-Yates：每次调用都新建 [Random]，不共享游标 ⇒ 结果可逐次复现。
     * 用于 n 较大（不可能穷举 n!）时覆盖多种就绪节点出现顺序。
     */
    fun <T> seeded(items: List<T>, count: Int = DEFAULT_SHUFFLE_COUNT): List<List<T>> {
        val random = Random(FIXED_SEED)
        val out = ArrayList<List<T>>(count)
        repeat(count) {
            val work = items.toMutableList()
            for (i in work.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val tmp = work[i]
                work[i] = work[j]
                work[j] = tmp
            }
            out += work.toList()
        }
        return out
    }

    /** n!，供测试断言「确实穷举到位」而不写死 6 / 24 字面量。 */
    fun factorial(n: Int): Int {
        var acc = 1
        for (i in 2..n) acc *= i
        return acc
    }

    const val DEFAULT_SHUFFLE_COUNT = 32
    const val FIXED_SEED = 0x5EED1234L
}
