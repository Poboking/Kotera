package com.eratw.port.core.mod

import com.eratw.port.core.vars.VariableStore

/**
 * 引擎侧「主动触发事件」的派发目标（AE 车）：由 [com.eratw.port.core.proc.ProcessRuntime] 在
 * 构造期注册进 `store.aux`，[ModEventTriggerWiring] 只在真正被调用时查它。
 *
 * 四个方法的语义：
 * - [isOnVmThread]：当前线程是否就是 VM 根级 run 的线程（引擎在 `runEntry` 进入时记录）；
 * - [isInEventDispatch]：是否正处于一次事件派发内 ⇒ 触发**延迟投递**到本次派发收尾（AL 车），
 *   不再拒绝；
 * - [isInVmRun]：是否正处于一次根级 VM run 内 ⇒ 触发**延迟投递**（AG 车），
 *   由 [ModEventTriggerWiring.flushDeferred] 在 run 收尾时统一派发；
 * - [triggerEvent]：走引擎真实事件派发路径同步派发一次事件，返回「事件是否存在并已派发」。
 */
internal interface ModEventTriggerTarget {
    fun isOnVmThread(): Boolean

    fun isInEventDispatch(): Boolean

    fun isInVmRun(): Boolean

    fun triggerEvent(eventKey: String): Boolean
}

/**
 * `context.events.trigger(...)` 的引擎侧旁路判据、唯一即时闸（VM 线程）与延迟队列
 * （AE 车 + AG 车 + AL 车）。
 *
 * 为什么不是直接让 [com.eratw.port.core.proc.ProcessRuntime] 实现 `EventApi`：mod 面的
 * `context.events` 由 [ModContextImpl] 持有、只认 [EventApiAdapter]；引擎派发目标是会话级、
 * 由宿主 Constructor 装配 ⇒ 两边经 `store.aux` 的单槽解耦（与 [FunctionHookWiring] /
 * [ModEventWiring] 同一「家族前缀 `mod.`、实例会话级、无静态可变全局」口径）。
 *
 * ## 两个延迟位置（同一队列、同一 FIFO）
 *
 * 只有「两次根级 run 之间」的调用是**当刻立即派发**；其余两个位置原本都拒绝，现在都改为
 * 入 [Handle.deferred] 并立即返回（当次脚本 / 当次派发过程中不跑事件体，顺序因此可预期）：
 *
 * - **根级 VM run 内**（[ModEventTriggerTarget.isInVmRun]，AG 车）：PREFIX/POSTFIX hook、
 *   相位回调都在根级 run 里跑。根级 run 收尾时 [flushDeferred] 在**同一 VM 线程**上按入队序
 *   走引擎真实事件链派发 —— 事件体不会插进当次脚本执行中间。
 * - **事件派发中**（[ModEventTriggerTarget.isInEventDispatch]，AL 车）：作者最自然的写法就是
 *   在 `EVT.NORMAL` 处理器里接着触发 `EVT.LATER`。派发期间入队，等**本次事件派发的四桶与
 *   hook 全部跑完**（`ProcessRuntime.executeEvent` 的 finally，`eventActive` 已清）再 flush ——
 *   不会嵌套派发、乱序，也不会丢。
 *
 * 两处 flush 幂等：队列在 flush 时逐条 `removeFirst` 抽干，第二处看到的队列只剩「本次新入队」
 * 或为空（空 = 一次 `store.aux` 查表 + 两次字段读直接早退），同一事件不会被派发两遍。
 *
 * ## 防失控（连环触发）
 *
 * 一条 flush 链里继续触发会追加进同一队列（A 的处理器触发 B、B 的处理器触发 A……）。两道闸：
 *
 * - 单条 flush 链最多**派发** [MAX_DEFERRED_DISPATCH_PER_RUN] 条（run 批量触发的口径）；
 * - 链内**再入队**最多 [MAX_TRIGGER_CHAIN_PER_FLUSH] 轮：链深到顶后 `trigger` 停止入队、
 *   返回 false 并落一条 WARN 到 [ModEventWiring] 的同一日志面（文案带触发 key 与链深），
 *   不静默丢、也不死循环。
 *
 * 延迟派发内部会再进事件派发（事件成员各自是根级 run，见 `ProcessRuntime` 的 `eventActive` /
 * `inRun`）：那些嵌套收尾由 [Handle.flushing] 短路，不会递归抽队列。
 *
 * ## 旁路成本
 *
 * 没人调用 `events.trigger` 时近零开销：本对象只被 `ProcessRuntime` 的两处收尾各调一次
 * [flushDeferred]（run 收尾 / 事件派发收尾），它先做一次 `store.aux` 查表 + 字段读，队列空
 * 就直接返回（不进派发链、不构造事件上下文）；`ProcessRuntime` 构造期只多一次 `aux.slot` 登记。
 *
 * ## 线程假设怎么核实
 *
 * 不假设「调用方自称 VM 线程」：`ProcessRuntime.runEntry` 在真正进入根级 VM 执行时把
 * `Thread.currentThread()` 记进 `vmThread` 字段，闸门拿**同一线程对象身份**比对
 * （`===`，非线程名/非 threadLocal 猜测）；首次 run 之前 `vmThread == null` ⇒ 拒绝。
 * 单测用「主测试线程跑过 run 后通过 / 新起线程被拒」两侧读数钉死，不靠约定。
 */
internal object ModEventTriggerWiring {
    /** `store.aux` 槽名（家族前缀 `mod.`，全小写）。 */
    const val AUX_SLOT = "mod.events.trigger"

    /** 单次根级 run 收尾最多派发多少条延迟触发（超限余下整批丢弃 + WARN，防事件风暴）。 */
    const val MAX_DEFERRED_DISPATCH_PER_RUN = 64

    /**
     * 一条 flush 链内允许的连环触发深度（链内再入队上限）。
     *
     * 与 [MAX_DEFERRED_DISPATCH_PER_RUN] 分开：前者管「一次 run 积压多少」，后者管
     * 「A 处理器触发 B、B 处理器再触发 A」的环有多深。到顶即停止入队并 WARN（不静默丢）。
     */
    const val MAX_TRIGGER_CHAIN_PER_FLUSH = 64

    /**
     * 槽内容：可换的派发目标 + 延迟队列与 flush 链状态
     * （[uninstall] 按实例同一性判「是不是我这一份」）。
     */
    internal class Handle(
        var target: ModEventTriggerTarget?,
    ) {
        /** 待派发事件名（FIFO；已归一、存在性已过闸）。run 内与事件派发内共用这一条队列。 */
        val deferred: ArrayDeque<String> = ArrayDeque()

        /** 是否正在做延迟派发（防「派发 → 事件成员 run 收尾 → 再派发」递归）。 */
        var flushing: Boolean = false

        /** 当前 flush 链已派发条数（链深；链内再入队闸门读它）。 */
        var chainDispatched: Int = 0

        /** 当前 flush 链是否已因链深到顶告过警（一条链只落一条 WARN）。 */
        var chainWarned: Boolean = false

        /** 最近一次超限截断的告警文案（取证面；null = 从未发生过）。 */
        var lastOverflowWarning: String? = null

        /** 最近一次连环触发链到顶的告警文案（取证面；null = 从未发生过）。 */
        var lastChainWarning: String? = null
    }

    /** 把引擎派发目标挂到 [store]；重复安装 = 覆盖（读档后重装同形）。 */
    fun install(store: VariableStore, target: ModEventTriggerTarget) {
        val handle = store.aux.slot(AUX_SLOT) { Handle(target) }
        handle.target = target
        // 正常路径队列在每次 run / 事件派发收尾都会排空；残留项只可能来自异常收尾的上一个
        // 会话，不能跨会话派发，重装时清掉。
        handle.deferred.clear()
        handle.flushing = false
        handle.chainDispatched = 0
        handle.chainWarned = false
    }

    /** 解绑**本会话那一份**：传入别的目标时 no-op。 */
    fun uninstall(store: VariableStore, target: ModEventTriggerTarget) {
        val handle: Handle = store.aux.peek(AUX_SLOT) ?: return
        if (handle.target === target) {
            handle.target = null
            handle.deferred.clear()
        }
    }

    /**
     * 唯一即时闸（VM 线程）+ 真实派发 / 延迟入队（[ModExtensionApiAdapters] 的唯一消费点，
     * 事件名已归一、存在性已过闸）。
     *
     * 分派口径（按顺序）：
     * 1. 不在事件派发中、也不在根级 run 内 ⇒ **当刻立即派发**（旧语义不变）；
     * 2. 在事件派发中 / 根级 run 内 ⇒ 入 FIFO 延迟队列并立即返回；事件派发由
     *    [flushDeferred] 在派发收尾派发，run 内积压由 run 收尾派发；
     * 3. 正处在一次 flush 链内且链深已到 [MAX_TRIGGER_CHAIN_PER_FLUSH] ⇒ **停止入队**、
     *    落一条 WARN 并返回 false（不静默丢，也不让它无限循环）。
     *
     * 返回值：`true` = 本次已派发或已受理入队（收尾时派发）；`false` = 链深到顶被截断（已 WARN）。
     *
     * @throws UnsupportedOperationException 引擎派发目标未装配（本 context 未经宿主装配）
     * @throws IllegalStateException 非 VM 线程（唯一保留的拒绝面）
     */
    fun trigger(store: VariableStore, canonicalKey: String): Boolean {
        val handle: Handle = store.aux.peek(AUX_SLOT) ?: throw missingTarget(canonicalKey)
        val target = handle.target ?: throw missingTarget(canonicalKey)
        check(target.isOnVmThread()) {
            "events.trigger($canonicalKey) 只允许 VM 线程调用（当前线程不是记录到的 VM 线程；" +
                "引擎在首次根级 run 进入时才记录 VM 线程）"
        }
        val inEventDispatch = target.isInEventDispatch()
        return when {
            !inEventDispatch && !target.isInVmRun() -> target.triggerEvent(canonicalKey)
            handle.flushing && handle.chainDispatched >= MAX_TRIGGER_CHAIN_PER_FLUSH ->
                refuseChain(canonicalKey, handle, ModEventWiring.logSink(store))
            else -> {
                // AG / AL 车：run 内与事件派发内 = 延迟投递，不当场派发（根级 run 不可重入、
                // 事件派发不可嵌套），也不拒绝。
                handle.deferred.addLast(canonicalKey)
                true
            }
        }
    }

    /**
     * 延迟派发的唯一消费点（两处收尾都调它）：① 根级 run 收尾
     * （`ProcessRuntime.runEntry` 的 finally）；② 事件派发收尾
     * （`ProcessRuntime.executeEvent` 的 finally，`eventActive` 清理之后）。
     *
     * 空队列近零开销：一次 `store.aux` 查表 + 字段读，没有待派发项就直接返回。
     * [Handle.flushing] 为真直接返回：延迟派发会再进 [ModEventTriggerTarget.triggerEvent]
     * （事件成员各自是根级 run），那些嵌套收尾不许再抽一次队列，否则递归。
     */
    fun flushDeferred(store: VariableStore) {
        val handle: Handle = store.aux.peek(AUX_SLOT) ?: return
        drain(handle, ModEventWiring.logSink(store))
    }

    /** [flushDeferred] 的实做：先把「无需派发」的路径逐一短路掉，再抽队列。 */
    private fun drain(handle: Handle, logSink: ((String) -> Unit)?) {
        if (handle.flushing || handle.deferred.isEmpty()) return
        val target = handle.target
        if (target == null || target.isInEventDispatch()) {
            // 目标已解绑：队列里的 key 无处可派发，显式清掉，避免下次重装后跨会话补派；
            // 事件派发中：此刻 executeEvent 还挂着 eventActive，派发会被它的重入闸拒——留给
            // executeEvent 的 finally（四桶 + hook 全部跑完后）那一处 flush。
            if (target == null) handle.deferred.clear()
            return
        }
        handle.flushing = true
        handle.chainDispatched = 0
        handle.chainWarned = false
        try {
            var dispatched = 0
            while (handle.deferred.isNotEmpty() && dispatched < MAX_DEFERRED_DISPATCH_PER_RUN) {
                val key = handle.deferred.removeFirst()
                dispatched++
                handle.chainDispatched++
                // 单个事件派发失败（如 ERB 事件体抛 CodeEE）只隔离这一条，余下照发：
                // 收尾相位不能因一条坏触发把积压全部丢掉，也绝不能把异常抛回给 run 的调用方。
                runCatching { target.triggerEvent(key) }.onFailure { t ->
                    logSink?.invoke(
                        "[WARN] events.trigger 延迟派发异常已隔离 key=$key " +
                            "cause=${t::class.java.simpleName}: ${t.message}",
                    )
                }
            }
            if (handle.deferred.isNotEmpty()) {
                val dropped = handle.deferred.size
                handle.deferred.clear()
                val warning = "[WARN] events.trigger 延迟队列在单次 VM run 收尾超过上限 " +
                    "$MAX_DEFERRED_DISPATCH_PER_RUN 条，已丢弃 $dropped 条（触发方批量 trigger 时请自行限流）"
                handle.lastOverflowWarning = warning
                logSink?.invoke(warning)
            }
        } finally {
            handle.flushing = false
        }
    }

    /** 链深到顶：停止入队 + 一条链只落一条 WARN（返回 false 让调用方知道本次没被受理）。 */
    private fun refuseChain(canonicalKey: String, handle: Handle, logSink: ((String) -> Unit)?): Boolean {
        if (!handle.chainWarned) {
            handle.chainWarned = true
            val warning = "[WARN] events.trigger 连环触发链达到上限 $MAX_TRIGGER_CHAIN_PER_FLUSH 轮，" +
                "已停止继续入队 key=$canonicalKey 链深=${handle.chainDispatched}" +
                "（事件处理器里互相 trigger 形成环；请改为状态驱动或减少触发）"
            handle.lastChainWarning = warning
            logSink?.invoke(warning)
        }
        return false
    }

    /** 延迟队列当前长度（测试与取证面；生产路径不读）。 */
    internal fun deferredPendingCount(store: VariableStore): Int =
        store.aux.peek<Handle>(AUX_SLOT)?.deferred?.size ?: 0

    /** 最近一次超限截断的告警文案（测试与取证面；null = 从未发生过）。 */
    internal fun lastOverflowWarning(store: VariableStore): String? =
        store.aux.peek<Handle>(AUX_SLOT)?.lastOverflowWarning

    /** 最近一次连环触发链到顶的告警文案（测试与取证面；null = 从未发生过）。 */
    internal fun lastChainWarning(store: VariableStore): String? =
        store.aux.peek<Handle>(AUX_SLOT)?.lastChainWarning

    private fun missingTarget(canonicalKey: String): UnsupportedOperationException =
        UnsupportedOperationException(
            "事件主动触发目标未装配（events.trigger($canonicalKey)）：宿主没有把引擎派发目标" +
                "挂进 store.aux（本 context 未经生产会话装配，或引擎侧接线缺失）；不静默丢弃触发",
        )
}

/**
 * 会话收口的**宿主侧**解绑：不按目标实例判同一性，直接摘掉槽内目标并清空延迟队列。
 *
 * 为什么需要它（与 [ModEventTriggerWiring.uninstall] 的分工）：目标实例是 `ProcessRuntime`
 * 构造期注册的匿名对象，持有引用的只有引擎自己；`ModRuntimeSession.shutdown()` 这一层拿不到
 * 它 ⇒ 只能按「一个 store 只挂一个 `ProcessRuntime`、会话结束即整份作废」的会话级前提无条件解绑。
 *
 * 收口前的不对称（CL / CO 两波审计发现的）：shutdown 只拆了 functionhooks / events / audio
 * 三槽，本槽的 `target` 仍指着一个已收口的会话 ⇒ 公共面 `context.events.trigger` 已
 * fail-closed（bridge 被置空），但 raw 路径在 VM 线程上仍能真派发进去。生产上不可达（无生产
 * 调用点），但收口必须对称；解绑后 [ModEventTriggerWiring.trigger] /
 * [ModEventTriggerWiring.flushDeferred] 一律走「目标未装配」的 fail-closed 路径，向已收口
 * 会话派发在结构上不可能。
 *
 * 幂等：无槽 / 目标已为空时直接返回。
 *
 * 为什么放文件级而不是放进 [ModEventTriggerWiring]：那个 object 已到 detekt 的成员函数上限
 * （对象内 11 个即报），收口动作放外面既不挤额度、也让「判定与派发实现」同「会话收尾动作」
 * 分层；唯一调用点是 [ModEventWiring.uninstall]。
 */
internal fun detachModTriggerTarget(store: VariableStore) {
    val handle: ModEventTriggerWiring.Handle =
        store.aux.peek(ModEventTriggerWiring.AUX_SLOT) ?: return
    handle.target = null
    handle.deferred.clear()
}
