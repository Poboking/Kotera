package com.eratw.port.modtesting

import com.eratw.port.modapi.TimingApi
import com.eratw.port.modapi.TimingSample

/**
 * 录制型耗时面（测试替身），与生产 `ModTimingLedger`（`:core-vm` 的 `ModTimingLedger.kt`）**逐条对齐**：
 *
 * - 键 = scope 稳定字符串，同名 scope **累加**（次数 / 总耗时 / 单次最大 / 最近一次）；
 * - [samples] 返回本包读数，**按 `totalNanos` 降序**（最贵的在前；并列保持首次出现序，与生产面
 *   「插入序 + 稳定排序」一致）；
 * - `averageNanos = totalNanos / count` 整数均值；
 * - [reset] 只清本替身的账（本替身 = 一个包的一个 context，故即「只清本包」；生产面同语义的底是
 *   `ModTimingLedger.resetPackage`），同时复位 scope 上限计数；
 * - [record] 校验与生产同口径：空白 scope、超过 [MAX_SCOPE_LENGTH] 的 scope、负纳秒
 *   一律 [IllegalArgumentException]，且抛在动账本之前（不留半笔账）；
 * - 单包不同 scope 数到 [MAX_DISTINCT_SCOPES] 后，新 scope 经 [warn] 记一条 WARN 后**忽略**，
 *   既有 scope 继续累加，表不再增长（生产面同样只 WARN 不抛）；
 * - [measure] 与生产 `measureValue` 同构：`try/finally` 落账、返回值 / 异常原样透传、时钟回拨
 *   （差值为负）按 0 记；非法 scope 同生产口径，要到 `finally` 落账时才抛（block 已执行）。
 *
 * ## 与生产面唯一的接线差异
 *
 * 生产账本把 WARN 整行（含 `[packageId][WARN]` 前缀）交给宿主 `logSink`；台架替身把**消息体**交给
 * [warn] 回调，由 `TestContext` 包成 `LogEntry(level = "WARN", packageId, message)`。聚合 / 排序 /
 * 校验 / 上限语义完全相同，只是日志落点不同。
 *
 * ## 线程
 *
 * 与生产面同口径：只在 VM 线程用（台架单线程），不加锁。
 *
 * @param packageId 包身份（异常文案点名它；[samples] 天然只返回本包读数）。
 * @param clock 时间源（缺省 `System.nanoTime`）；[measure] 用它取耗时，测试可注入假时钟。
 * @param warn 降级日志回调（scope 数到顶时收到一次消息体；缺省丢弃）。
 */
class RecordingTiming(
    val packageId: String,
    private val clock: () -> Long = System::nanoTime,
    private val warn: (String) -> Unit = {},
) : TimingApi {

    /** 一个 scope 的聚合读数（对外只经 [samples] 的快照）。 */
    private class Sample {
        var count: Long = 0L
        var totalNanos: Long = 0L
        var maxNanos: Long = 0L
        var lastNanos: Long = 0L
    }

    private val entries = LinkedHashMap<String, Sample>()

    /** 「scope 数到顶」WARN 是否已发过（每替身一次，不刷日志；[reset] 复位）。 */
    private var limitWarned = false

    override fun samples(): List<TimingSample> = entries.map { (scope, sample) ->
        TimingSample(
            scope = scope,
            count = sample.count,
            totalNanos = sample.totalNanos,
            maxNanos = sample.maxNanos,
            averageNanos = if (sample.count == 0L) 0L else sample.totalNanos / sample.count,
            lastNanos = sample.lastNanos,
        )
    }.sortedByDescending { it.totalNanos }

    override fun reset() {
        entries.clear()
        limitWarned = false
    }

    override fun record(scope: String, nanos: Long) {
        requireAccountable(scope, nanos)
        if (scope !in entries && entries.size >= MAX_DISTINCT_SCOPES) {
            if (!limitWarned) {
                limitWarned = true
                warn(
                    "mod.timing 本包已记满 $MAX_DISTINCT_SCOPES 个 scope，忽略新 scope=[$scope]" +
                        "（既有 scope 继续累加；要腾位置请先 context.timing.reset()）",
                )
            }
            return
        }
        val sample = entries.getOrPut(scope) { Sample() }
        sample.count += 1L
        sample.totalNanos += nanos
        if (nanos > sample.maxNanos) sample.maxNanos = nanos
        sample.lastNanos = nanos
    }

    /**
     * 包一段代码并记账（照生产 `ModTimingLedger.measureValue`）：异常**照样落账后原样抛出**，
     * 时钟回拨按 0 记；非法 [scope] 会在 `finally` 落账时抛（block 已经跑完）。
     */
    fun <T> measure(scope: String, block: () -> T): T {
        val started = clock()
        try {
            return block()
        } finally {
            record(scope, (clock() - started).coerceAtLeast(0L))
        }
    }

    /** 当前已记账的 scope 集合（断言「该出现的 scope 出现了」用；顺序 = 首次出现序）。 */
    fun scopeNames(): Set<String> = entries.keys.toSet()

    private fun requireAccountable(scope: String, nanos: Long) {
        require(scope.isNotBlank()) {
            "耗时 scope 不能为空或空白：[$scope]（$packageId；空 scope 无法归因）"
        }
        require(scope.length <= MAX_SCOPE_LENGTH) {
            "耗时 scope 过长：length=${scope.length} > $MAX_SCOPE_LENGTH" +
                "（$packageId；拒绝而不是截断 —— 截断会把两个不同 scope 并成一笔账）"
        }
        require(nanos >= 0L) {
            "耗时不能为负：nanos=$nanos（$packageId scope=[$scope]；" +
                "负值来自时钟回拨，measure 已按 0 记）"
        }
    }

    companion object {
        /** 单个 scope 的最大字符数；与生产 `ModTimingLedger.MAX_SCOPE_LENGTH` 同值（parity 用例钉住）。 */
        const val MAX_SCOPE_LENGTH: Int = 128

        /** 单包最多可记的不同 scope 数；与生产 `ModTimingLedger.MAX_DISTINCT_SCOPES` 同值。 */
        const val MAX_DISTINCT_SCOPES: Int = 256
    }
}
