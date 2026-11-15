package com.eratw.port.core.loader

/**
 * 可判等的字符串归一流：内容相等 ⇒ 同一实例，内容一字不改。
 *
 * 归一不是语义操作 —— C# 侧对字符串身份零依赖（`Argument.cs:5-9` 的
 * IsConst/ConstStr/ConstInt 全是按内容消费的普通字段，全仓无 `ReferenceEquals` /
 * `string.Intern` / `RuntimeHelpers.GetHashCode`），所以 `.erbc` 装载后再编码逐字节相同。
 *
 * 实例由**调用方持有**（无 object 单例、无静态可变状态）；装载单线程（ADR-001）。
 *
 * @param initialCapacity 起始桶数（向上取 2 的幂，至少 16）
 */
class StringPool(initialCapacity: Int = 1 shl 12) {

    /** 累计读数（装载期一次摊销，之后不再变）。 */
    class Snapshot(
        /** [intern] 调用次数。 */
        val requests: Long,
        /** 命中已有实例的次数（含传入的就是代表实例本身）。 */
        val hits: Long,
        /** 表内不同内容数（= 归一的代表实例数）。 */
        val distinct: Int,
        /** 被丢弃的重复实例的估算字节数（口径：40 B 头 + 2 B/字符）。 */
        val savedBytes: Long,
    ) {
        val misses: Long get() = requests - hits

        val hitRate: Double get() = if (requests == 0L) 0.0 else hits.toDouble() / requests

        override fun toString(): String =
            "StringPool(requests=$requests, hits=$hits, misses=$misses, distinct=$distinct, " +
                "hitRate=${(hitRate * 1000).toInt() / 10.0}%, saved=${savedBytes shr 10} KiB)"

        companion object {
            val EMPTY = Snapshot(0, 0, 0, 0)
        }
    }

    private var table: Array<String?> = arrayOfNulls<String>(poolCapacity(initialCapacity))
    private var mask: Int = table.size - 1
    private var size: Int = 0

    private var requests: Long = 0
    private var hits: Long = 0
    private var savedBytes: Long = 0

    /**
     * 运行期去重表上界（已武装后生效）；[Int.MAX_VALUE] = 未武装（装载趟按旧口径无界）。
     *
     * 为什么要有这一条：去重表把**每个不同内容的代表实例**钉在开放寻址表里，永不归还 ——
     * 装载趟去重的对象是语料固定面（几万条），运行期去重的对象是「玩到过的全部文本」，
     * 随游玩覆盖面无界增长（2026-11-14 桌面 soak512 实测 25 分钟爬到 ~1M 条 / 96 MiB `byte[]`），
     * 512 MiB 挡位上就是 OOM 缺口的主力之一。上界触发时**整表重置**：表里的代表实例
     * 只要还被语句/片持有就照常活着（归一不改变任何引用者持有的实例），丢掉的只是
     * 「跨代去重」这一层收益 —— 内容相等语义逐字不变。
     */
    private var runtimeDistinctBudget: Int = Int.MAX_VALUE

    /** 运行期整表重置次数（诊断/单测读数；装载趟不计）。 */
    var runtimeDistinctResets: Int = 0
        private set

    /** 表内不同内容数（重置后从 0 重新累计）。 */
    val distinct: Int get() = size

    /**
     * 装载收尾武装：之后去重表条数超过 [maxDistinct] 就整表重置。
     * 只许在装载趟结束后调（`Program.armRuntimeCodeCacheBudgets`）；对未武装实例零影响。
     */
    fun armRuntimeDistinctBudget(maxDistinct: Int) {
        runtimeDistinctBudget = maxDistinct
    }

    /** 返回与 [s] 内容相等的代表实例（可能就是 [s] 自己）。 */
    fun intern(s: String): String {
        requests++
        if (size * 3 >= table.size * 2) grow()
        var i = slotOf(s)
        while (true) {
            val cur = table[i]
            if (cur == null) {
                table[i] = s
                size++
                if (size > runtimeDistinctBudget) resetTableForRuntime()
                return s
            }
            if (cur === s || cur == s) {
                hits++
                if (cur !== s) savedBytes += ESTIMATED_STRING_BYTES + 2L * s.length
                return cur
            }
            i = (i + 1) and mask
        }
    }

    /** 运行期上界触发：整表重置（代表实例归引用者所有，表壳换成小容量新表）。 */
    private fun resetTableForRuntime() {
        table = arrayOfNulls(poolCapacity(INITIAL_TABLE_CAPACITY))
        mask = table.size - 1
        size = 0
        runtimeDistinctResets++
    }

    /** 当前读数快照（只读，可反复取）。 */
    fun snapshot(): Snapshot = Snapshot(requests, hits, size, savedBytes)

    private fun slotOf(s: String): Int {
        val h = s.hashCode()
        return (h xor (h ushr 16)) and mask
    }

    private fun grow() {
        val old = table
        table = arrayOfNulls<String>(old.size shl 1)
        mask = table.size - 1
        for (e in old) {
            if (e == null) continue
            var i = slotOf(e)
            while (table[i] != null) i = (i + 1) and mask
            table[i] = e
        }
    }

    private companion object {
        /**
         * 一个重复 String 被丢弃时释放的估算保留量：对象头 + 缓存哈希 + 字节数组头 ≈ 40 B，
         * 再按 2 B/字符 计正文（语料为日文，UTF-16 口径；拉丁串会高估）。粗估，不做精确测量。
         */
        const val ESTIMATED_STRING_BYTES = 40L

        /** 运行期整表重置后的新表容量（与构造缺省同值；重置不是扩容反向，只还驻留不还语义）。 */
        const val INITIAL_TABLE_CAPACITY = 1 shl 12
    }
}

private fun poolCapacity(n: Int): Int {
    var c = 16
    while (c < n) c = c shl 1
    return c
}
