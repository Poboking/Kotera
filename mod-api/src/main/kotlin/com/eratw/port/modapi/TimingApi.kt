package com.eratw.port.modapi

/**
 * 耗时归因面（§18.5 诊断面扩展；RT2 P1）。
 *
 * 框架在四个已知点给**每个 mod 单独**记账，作者可据此判断「我这 mod 到底慢在哪」：
 *
 * | scope | 含义 |
 * |---|---|
 * | `lifecycle.onLoad` / `onEnable` / `onDisable` / `onUnload` | 生命周期单次回调耗时 |
 * | `lifecycle.onNewGame` / `onSaveLoaded` | 相位通知耗时 |
 * | `tick` | 本 mod 的一个节拍（相位派发 + 到期任务） |
 * | `hook.<TARGETKEY>.PREFIX` / `.POSTFIX` | 本 mod 的某个 hook 处理器单次调用 |
 * | `event.<EVENTKEY>.PRI` / `.NORMAL` / `.LATER` | 本 mod 的某个事件处理器单次调用 |
 *
 * ## 写侧（作者自报，RT2 P1 写侧扩展）
 *
 * 框架的四个测量点之外，作者可经 [record]（或包一层的 [measure]）把自己的代码段记进**同一张表**：
 * 同名 scope 累加、`averageNanos = totalNanos / count`，口径与上表的引擎测量点完全相同。因此作者
 * scope 会与引擎 scope 一起出现在 [samples] 的降序表里，也会进宿主 `mod.timing …` 日志（设置页
 * 「最慢 top3」同源）。建议用 `author.<模块>.<动作>` 之类稳定、可读的前缀与引擎 scope 区分。
 *
 * ## 两条须知（不然会看错数）
 *
 * 1. **只有本包**：[samples] 只返回调用方自己的 packageId 的读数，看不到别的 mod（归因面不是
 *    跨 mod 的探针面）。需要全局视角请用宿主侧日志（`mod.timing …` 行）。
 * 2. **是墙钟不是 CPU**：含调度等待与 IO。nanoTime 口径，`averageNanos` 是整数均值。
 *
 * ## 线程
 *
 * 只在 VM 线程调用（与其它 `context.xxx` 同口径）：读的是本拍/本回调刚写入的账，不加锁。
 */
interface TimingApi {

    /**
     * 本包的耗时读数，**按总耗时降序**（最贵的第一条）。
     *
     * 未记录任何读数时返回空表；未装配账本（直接构造的 context、旧装配路径）时**显式抛**
     * `UnsupportedOperationException` —— 不做「静默空表」兜底，那会让作者以为自己的 mod 不耗时。
     */
    fun samples(): List<TimingSample>

    /** 清空本包读数（重新归因时用；不影响别的 mod）。 */
    fun reset()

    /**
     * 记一笔耗时（纳秒）到 [scope] 的聚合读数（写侧入口；引擎四个测量点同样经它落账）。
     *
     * ## 与 [measure] 的关系
     *
     * [measure] 只是「取 `System.nanoTime()` 起止 + `try/finally` 调本方法」的语法糖；校验、上限
     * 与聚合语义全在实现侧账本里，本方法只交一笔读数。需要精确控制计时区间时直接调本方法。
     *
     * ## 契约（生产账本与台架替身逐条对齐）
     *
     * - [scope] 空白、或超过实现侧上限（当前 128 字符）⇒ `IllegalArgumentException`，且不留半笔账；
     * - [nanos] 为负 ⇒ `IllegalArgumentException`（测量路径已把时钟回拨夹到 0，正常不会走到这）；
     * - 单包不同 scope 数达实现侧上限（当前 256）后，新 scope 记一条 WARN 后忽略，既有 scope
     *   继续累加 —— 防止运行时拼接的 scope（含变量）把读数表撑成无界增长。
     *
     * ## 缺省实现是显式失败
     *
     * 未接账本的老装配 / 台架若不覆盖本方法，这里抛 `UnsupportedOperationException` —— 不做
     * 「静默丢弃」兜底，那会让作者以为自己的计时生效了。
     */
    fun record(scope: String, nanos: Long) {
        throw UnsupportedOperationException(
            "耗时写入面未装配：scope=[$scope]；timing.record 不提供静默丢弃兜底",
        )
    }
}

/**
 * 包一段代码并把实测墙钟耗时记进 [scope]（写侧便捷入口；返回值与异常原样透传）。
 *
 * 起止都取 `System.nanoTime()`（含调度等待与 IO，与框架引擎测量点同口径），`try/finally` 保证
 * [block] 抛异常也照记一笔 —— 失败耗时不是免费的。异常原样抛回，不被记账吞掉。
 *
 * 与 [TimingApi.record] 的关系：本函数只负责测量，落账一律交给 [TimingApi.record]；因此校验
 * （空白 / 超长 scope、负耗时）与上限语义与直接调 [TimingApi.record] 完全一致。
 *
 * 注意：非法 [scope] 要到 `finally` 落账时才抛，会盖掉 [block] 自己抛的异常 —— 需要「先校验再跑」
 * 时请自己先 `require(scope.isNotBlank())`，或直接调 [TimingApi.record]。
 *
 * scope 命名建议用 `author.<模块>.<动作>` 这类稳定、可读的字符串；不要用运行时拼接结果当 scope
 * （读表会无界增长，到上限后新 scope 只 WARN 并忽略）。
 */
inline fun <T> TimingApi.measure(scope: String, block: () -> T): T {
    val started = System.nanoTime()
    try {
        return block()
    } finally {
        record(scope, (System.nanoTime() - started).coerceAtLeast(0L))
    }
}

/**
 * 一条耗时读数（[TimingApi.samples] 的元素）。
 *
 * @property scope 稳定作用域名（见 [TimingApi] 的表），不随调用次数增长。
 * @property count 该作用域累计调用次数。
 * @property totalNanos 累计墙钟耗时（纳秒）。
 * @property maxNanos 单次最大耗时（纳秒）。
 * @property averageNanos 整数均值（`totalNanos / count`）。
 * @property lastNanos 最近一次耗时（纳秒）。
 */
data class TimingSample(
    val scope: String,
    val count: Long,
    val totalNanos: Long,
    val maxNanos: Long,
    val averageNanos: Long,
    val lastNanos: Long,
)
