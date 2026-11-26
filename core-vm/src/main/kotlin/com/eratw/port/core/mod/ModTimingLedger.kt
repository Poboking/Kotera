package com.eratw.port.core.mod

import com.eratw.port.modapi.TimingSample
import java.util.Locale

/**
 * 逐 mod 耗时账本（§18.5 诊断面扩展；RT2 P1 归因）。
 *
 * ## 为什么不是 [com.eratw.port.core.diag.RuntimeDiagnostics]
 *
 * 诊断总线的记录形状（九字段）被 `RuntimeDiagnosticsTest` 钉死、且不含 elapsed 字段；往里塞耗时会
 * 破坏「诊断 = 事件流」的语义与既有闸。耗时是**统计**不是事件，故独立成簿。
 *
 * ## 写入侧
 *
 * - **引擎**：lifecycle / tick / hook / event 四个已知点经 [measureValue] 自动落账；
 * - **作者**：经 `context.timing.record(scope, nanos)` / `context.timing.measure(scope) { … }`
 *   自报自己的代码段（RT2 P1 写侧扩展，见 `TimingApi`）。
 *
 * 两侧写进**同一张表**（同一 `(packageId, scope)` 聚合结构），故 `samples()` / [forPackage] 的
 * `count / totalNanos / maxNanos / averageNanos / lastNanos` 口径对作者 scope 与引擎 scope 完全
 * 一致，宿主 `mod.timing …` 日志（设置页「最慢 top3」同源）也一起收。
 *
 * ## 口径
 *
 * - 时间源注入（缺省 `System.nanoTime`）：测试给假时钟即可断言数值，不引入对宿主时钟的依赖。
 * - 键 = `(packageId, scope)`，scope 是稳定字符串（`lifecycle.onLoad` / `tick` /
 *   `hook.<TARGETKEY>.PREFIX` / `event.<EVENTKEY>.NORMAL`），不随调用次数增长。
 * - 只记聚合量（次数 / 总耗时 / 最大 / 最近一次），不保留逐次样本：归因要的是「哪个 mod 的哪一段
 *   占了多少」，不是时间线回放（回放走诊断面）。
 * - **上限**（两条，防无界增长；引擎与作者 scope 同一口径）：
 *   - 单 scope 长度 ≤ [MAX_SCOPE_LENGTH]：超限抛 [IllegalArgumentException]，**不截断** ——
 *     截断会把两个不同 scope 并成一笔账，归因就错了；scope 是作者写的字面量，报错比静默改字可修。
 *   - 单包不同 scope 数 ≤ [MAX_DISTINCT_SCOPES]：到顶后新 scope 记一条 WARN 并忽略该条，既有
 *     scope 继续累加，表不再增长。这里选 WARN 而不是抛：scope 数超限是「作者写了太多动态
 *     scope」的归因问题，不该让 mod 业务代码整段崩掉；但静默吞又会让作者以为计时生效了。
 *
 * ## 线程
 *
 * 写入只在 VM 线程（生命周期回调、节拍、hook 派发、事件桶派发、作者回调都在 VM 线程），故不加锁；
 * 与 [HookPipeline] / [ModContextImpl] 的线程口径一致（ADR-003 单线程）。
 *
 * ## 旁路
 *
 * 装配侧不建账本时（`ModRuntimeHost.timing == null`）四个测量点全部零开销：不取时钟、不建对象、
 * 不进 HashMap —— golden 与「关态零日志」判据逐位不变。
 *
 * @param clock 时间源（缺省 `System.nanoTime`）。
 * @param logSink 宿主日志面（scope 数到顶的 WARN 经它落痕；缺省丢弃 = 老装配形态，行为不变）。
 */
class ModTimingLedger(
    private val clock: () -> Long = System::nanoTime,
    private val logSink: (String) -> Unit = {},
) {

    /** 一个 `(packageId, scope)` 的聚合读数（可变只在本类内，对外是快照）。 */
    private class Sample(
        val packageId: String,
        val scope: String,
    ) {
        var count: Long = 0L
        var totalNanos: Long = 0L
        var maxNanos: Long = 0L
        var lastNanos: Long = 0L
    }

    /** 键 = `packageId ␟ scope`（U+001F 不会出现在包名/scope 里）。 */
    private val samples = LinkedHashMap<String, Sample>()

    /** 每包已记的不同 scope 数（= [samples] 里该包的键数；独立维护，避免每次记账扫全表）。 */
    private val scopeCounts = HashMap<String, Int>()

    /** 已打过「scope 数到顶」WARN 的包（小写 packageId；每包只警告一次，不刷日志）。 */
    private val warnedPackages = HashSet<String>()

    /** 当前时钟读数（供「先取起点、再手工落账」的调用点用）。 */
    fun startNanos(): Long = clock()

    /**
     * 记一笔耗时（纳秒）到 `(packageId, scope)` 的聚合读数。
     *
     * 校验（抛 [IllegalArgumentException]，且抛在动账本之前、不留半笔账）：[scope] 空白、[scope]
     * 超过 [MAX_SCOPE_LENGTH]、[nanos] 为负。单包 scope 数到 [MAX_DISTINCT_SCOPES] 后，新 scope
     * 只 WARN 一次并忽略，既有 scope 照常累加（这条通道不抛）。
     *
     * 与 [measureValue] 同口径：都进同一张表，`samples()` 的 count / total / max / average / last
     * 完全一致。引擎测量路径经 [measureValue] 调用本方法，时钟回拨已在那边夹到 0，故不会触发
     * 负值校验。
     */
    fun record(packageId: String, scope: String, nanos: Long) {
        requireAccountable(packageId, scope, nanos)
        val packageKey = packageId.lowercase(Locale.ROOT)
        val key = packageKey + KEY_SEPARATOR + scope
        if (key !in samples) {
            val distinct = scopeCounts[packageKey] ?: 0
            if (distinct >= MAX_DISTINCT_SCOPES) {
                warnScopeLimit(packageId, scope, warnedPackages, logSink)
                return
            }
            scopeCounts[packageKey] = distinct + 1
        }
        val sample = samples.getOrPut(key) { Sample(packageId, scope) }
        sample.count++
        sample.totalNanos += nanos
        if (nanos > sample.maxNanos) sample.maxNanos = nanos
        sample.lastNanos = nanos
    }

    /**
     * 包一段代码并记账（异常照样落账后原样抛出，失败耗时不是「免费」的）。
     *
     * 时钟回拨（`clock()` 差值 < 0）按 0 记：引擎测量路径不该因宿主时钟抖动抛异常，更不该用它
     * 盖掉 [block] 自己抛出的异常。
     */
    fun <T> measureValue(packageId: String, scope: String, block: () -> T): T {
        val started = clock()
        try {
            return block()
        } finally {
            record(packageId, scope, (clock() - started).coerceAtLeast(0L))
        }
    }

    /** 全量快照（按总耗时降序 —— 「先看谁最贵」）。 */
    fun snapshot(): List<TimingSample> = sortedSamples { true }

    /** 某 mod 自己的快照（`context.timing.samples()` 的底），[packageId] 精确匹配。 */
    fun forPackage(packageId: String): List<TimingSample> = sortedSamples { it.packageId == packageId }

    /**
     * 超预算包名查询：累计耗时严格超过 [budgetNanos] 的包，返回其包名集合（纯函数）。
     *
     * ## 口径
     *
     * - 预算是**单包累计耗时上限**（纳秒），由宿主注入，账本本身不持有也不揣测预算值；
     * - 单包累计耗时 = 该包所有 scope 的 [TimingSample.totalNanos] 之和（与 [forPackage]
     *   逐条相加同口径，不做平均、不取单次最大）；
     * - **严格大于**预算才算超预算：恰好等于预算视为未超（预算即上限，不是下限）；
     * - [budgetNanos] 为 null 或 ≤ 0 ⇒ 恒返回空集，宿主不建/不传预算时行为与过去逐字一致
     *   （关态零告警），本方法不因此抛异常；
     * - 返回的包名与 [record] 时传入的 `packageId` 原样一致，与 [forPackage] 的精确匹配口径相同。
     *
     * ## 纯函数
     *
     * 只读现有聚合读数，不写 [samples] / [scopeCounts] / [warnedPackages]，不改
     * [record] / [snapshot] / [forPackage] 的语义，也不产出日志（告警格式由宿主报告层决定，
     * 见 `ModTimingReport`）。多次调用同参结果相同，调用前后 [snapshot] 逐字不变。
     *
     * @param budgetNanos 单包累计耗时预算（纳秒）；null 或 ≤ 0 表示不启用预算告警。
     * @return 累计耗时超过预算的包名集合（顺序不保证；未超预算的包不在其中）。
     */
    fun overBudgetPackages(budgetNanos: Long?): Set<String> {
        if (budgetNanos == null || budgetNanos <= 0L) return emptySet()
        return samples.values
            .groupBy { it.packageId }
            .filterValues { entries -> entries.sumOf { it.totalNanos } > budgetNanos }
            .keys
    }

    /** 清空全部读数（测试与「重载后重新归因」用；同时复位 scope 上限计数）。 */
    fun reset() {
        samples.clear()
        scopeCounts.clear()
        warnedPackages.clear()
    }

    /** 只清某个 mod 的读数（`context.timing.reset()` 的底；不影响别的 mod，并复位本包上限计数）。 */
    fun resetPackage(packageId: String) {
        val packageKey = packageId.lowercase(Locale.ROOT)
        val prefix = packageKey + KEY_SEPARATOR
        samples.keys.removeAll { it.startsWith(prefix) }
        scopeCounts.remove(packageKey)
        warnedPackages.remove(packageKey)
    }

    private fun sortedSamples(filter: (Sample) -> Boolean): List<TimingSample> =
        samples.values
            .filter(filter)
            .sortedByDescending { it.totalNanos }
            .map { sample ->
                TimingSample(
                    scope = sample.scope,
                    count = sample.count,
                    totalNanos = sample.totalNanos,
                    maxNanos = sample.maxNanos,
                    averageNanos = if (sample.count == 0L) 0L else sample.totalNanos / sample.count,
                    lastNanos = sample.lastNanos,
                )
            }

    companion object {
        /** 单个 scope 的最大字符数；超限抛 [IllegalArgumentException]（截断会让不同 scope 并账）。 */
        const val MAX_SCOPE_LENGTH: Int = 128

        /** 单包最多可记的不同 scope 数；到顶后新 scope WARN 一次并忽略（防无界增长）。 */
        const val MAX_DISTINCT_SCOPES: Int = 256

        /** 键分隔符（控制字符，包名与 scope 都不会含）。 */
        private const val KEY_SEPARATOR = '\u001F'
    }
}

/** 写入口校验：非法输入抛 IAE，且抛在动账本之前（不留半笔账）。 */
private fun requireAccountable(packageId: String, scope: String, nanos: Long) {
    require(scope.isNotBlank()) {
        "耗时 scope 不能为空或空白：[$scope]（$packageId；空 scope 无法归因）"
    }
    require(scope.length <= ModTimingLedger.MAX_SCOPE_LENGTH) {
        "耗时 scope 过长：length=${scope.length} > ${ModTimingLedger.MAX_SCOPE_LENGTH}" +
            "（$packageId；拒绝而不是截断 —— 截断会把两个不同 scope 并成一笔账）"
    }
    require(nanos >= 0L) {
        "耗时不能为负：nanos=$nanos（$packageId scope=[$scope]；" +
            "负值来自时钟回拨，测量路径已按 0 记）"
    }
}

/** 单包 scope 数到顶：每包只 WARN 一次，之后的新 scope 忽略（账本不再增长）。 */
private fun warnScopeLimit(
    packageId: String,
    scope: String,
    warnedPackages: MutableSet<String>,
    logSink: (String) -> Unit,
) {
    if (!warnedPackages.add(packageId.lowercase(Locale.ROOT))) return
    logSink(
        "[$packageId][WARN] mod.timing 本包已记满 ${ModTimingLedger.MAX_DISTINCT_SCOPES} 个 scope，" +
            "忽略新 scope=[$scope]（既有 scope 继续累加；要腾位置请先 context.timing.reset()）",
    )
}
