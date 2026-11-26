package com.eratw.port.core.screen

/**
 * 提交回注缝：语义界面点了一颗控件之后，把**原版按钮会提交的那个文本**交给输入队列。
 *
 * 生产实现就一行：`ScreenSubmitter { host.inputSink.submit(it) }`（与右侧选项栏同一条路，
 * 不新增第二条输入通道）。测试实现是记录器。
 */
fun interface ScreenSubmitter {
    /** 返回 true = 队列收下了；false = 当下不在等待（玩家已经提交过一页）。 */
    fun submit(text: String): Boolean
}

/** 一次点按的结果。每一种都有独立语义，不合并成 Boolean —— 界面要据此决定提示什么。 */
sealed interface ScreenPressResult {
    /** 收下：把 [submitValue] 交给了输入队列。 */
    data class Accepted(
        val epoch: Long,
        val controlId: String,
        val submitValue: String,
    ) : ScreenPressResult

    /** 世代不符：玩家看到的是旧页，此刻的等待已经是另一页（迟到的手指不许写进新页）。 */
    data class Stale(val expectedEpoch: Long, val currentEpoch: Long) : ScreenPressResult

    /** 当前没有可接管的页（未进入等待 / 已清空）。 */
    data object NoScreen : ScreenPressResult

    /** 本页等待不收值（WAIT / ENTER / VOID 等），任何控件都不该提交。 */
    data object NotInteractive : ScreenPressResult

    /** 页内没有这个 controlId（旧帧残影 / 拼写错）。 */
    data object UnknownControl : ScreenPressResult

    /** 提交被输入队列拒收（已不在等待），或宿主未接 submitter。 */
    data object Rejected : ScreenPressResult
}

/**
 * 语义页宿主：持有当前冻结的 [SemanticScreen]，并对外提供**带世代校验的点按**（HYB-1 前置装置）。
 *
 * ## 世代（epoch）的语义
 *
 * - 只在**内容变化**时推进（指纹相同 = 同一屏，不推进）。界面因此不会因为同一页被重复
 *   归约而刷新、也不会点着点着 id 变了。
 * - 点按必须带自己看到的那一代。世代不符一律 [ScreenPressResult.Stale]，**不**「尽力点到
 *   最近的相似控件」—— 那是把玩家的手指变成随机数。
 *
 * ## 线程
 *
 * 归约/发布在 VM 线程（等待点），点按在 UI 线程；两者只在这一处交汇，用实例锁串行。
 * 提交回调在锁外调用，避免输入队列的回流（收下后立刻重归约）造成自锁。
 */
class SemanticScreenHost(
    private val submitter: ScreenSubmitter? = null,
) {
    private val lock = Any()
    private var screen: SemanticScreen? = null
    private var epochCounter: Long = 0L

    /** 当前页的只读快照；null = 没有可接管页。 */
    fun latest(): SemanticScreen? = synchronized(lock) { screen }

    /**
     * 发布一页：内容（指纹）与当前页不同才换页并推进世代，返回盖章后的新页；
     * 相同则原样保留并返回 null（调用方据此跳过无意义的界面刷新）。
     */
    fun offer(candidate: SemanticScreen): SemanticScreen? = synchronized(lock) {
        val current = screen
        if (current != null && current.fingerprint == candidate.fingerprint) {
            null
        } else {
            epochCounter += 1
            val stamped = candidate.copy(epoch = epochCounter)
            screen = stamped
            stamped
        }
    }

    /** 清空当前页（离开等待、读档、切场景时调）。已发出的世代号不回退，永不复用。 */
    fun clear() {
        synchronized(lock) { screen = null }
    }

    /**
     * 点按一颗控件：世代校验 → 找到控件 → 把它的**提交值**交给 [submitter]。
     *
     * 注意这里提交的是 [SemanticControl.submitValue]，不是 [SemanticControl.label]：
     * 地图格子的标签是本地图内的号、提交值是 `MAIN_MAP*100+LOCAL`，用标签点会走错地方。
     *
     * 结构说明：判定拆成 [resolve]（锁内、只返回一个决定）与 [submit]（锁外、只做提交），
     * 这样返回语句只有一条 —— detekt 的 ReturnCount 上限已经是本仓常态约束，而「六个早退
     * 分支」正是最容易漏掉某个拒绝原因的写法（旧的空实现就是漏了 NotInteractive）。
     */
    fun press(controlId: String, expectedEpoch: Long): ScreenPressResult =
        when (val resolution = resolve(controlId, expectedEpoch)) {
            is Resolution.Ready -> submit(resolution)
            is Resolution.Fail -> resolution.result
        }

    /** 锁内判定：只回答「这一按该怎么处置」，不产生任何副作用。 */
    private fun resolve(controlId: String, expectedEpoch: Long): Resolution = synchronized(lock) {
        val current = screen
        val control = current?.controls?.firstOrNull { it.controlId == controlId }
        when {
            current == null -> Resolution.Fail(ScreenPressResult.NoScreen)
            !current.request.acceptsValue -> Resolution.Fail(ScreenPressResult.NotInteractive)
            current.epoch != expectedEpoch ->
                Resolution.Fail(ScreenPressResult.Stale(expectedEpoch, current.epoch))
            control == null -> Resolution.Fail(ScreenPressResult.UnknownControl)
            else -> Resolution.Ready(control, current.epoch)
        }
    }

    /** 锁外提交：提交回调可能触发输入队列回流（收下后立刻重归约），不能在锁内调。 */
    private fun submit(ready: Resolution.Ready): ScreenPressResult =
        submitter?.let { target ->
            if (target.submit(ready.control.submitValue)) {
                ScreenPressResult.Accepted(ready.epoch, ready.control.controlId, ready.control.submitValue)
            } else {
                ScreenPressResult.Rejected
            }
        } ?: ScreenPressResult.Rejected

    private sealed interface Resolution {
        data class Ready(val control: SemanticControl, val epoch: Long) : Resolution
        data class Fail(val result: ScreenPressResult) : Resolution
    }
}
