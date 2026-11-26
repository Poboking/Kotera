package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventContext
import com.eratw.port.modapi.EventResult

/**
 * 运行期事件处理器的**生产接线**（§6.1 贡献点的执行侧；RT2 P1 真接线）。
 *
 * [EventDispatchRegistry] 只管数据（谁的处理器、什么桶、注册序），本对象管「引擎哪一处调它」
 * 以及「事件上下文构造 / 异常隔离 / 日志落点」。
 *
 * ## 为什么必须有这一层
 *
 * `EventApi` 从 K1 起就冻结了契约、`HookPipeline` 式适配器写了一半，但生产侧 `context.events`
 * 一直是 `FailClosedEventApi`：`register` 当场抛「M2-6 生产消费点未接」⇒ mod 作者没法挂事件。
 * 本对象把 `ProcessRuntime.dispatchEventMembers` 接成真派发点：
 *
 * ```
 * 桶 0（ONLY，构建期）→ 桶 1（PRI）→ 桶 2（NORMAL）→ 桶 3（LATER）
 * 每桶：先跑 ERB 成员（链接序）→ 再跑本桶运行期处理器（注册序）
 * ```
 *
 * ## 与 [FunctionHookWiring] 的分工（别混）
 *
 * - hooks 是**前缀/后缀包裹**：能改实参、改返回值、短路（`Replace` / `SkipOriginal`），
 *   按事件名查槽，需要 Manifest 声明；
 * - events 是**桶内成员**：只能在自己的桶位跑、只能返回 `CONTINUE` / `SINGLE`（截断本桶余下），
 *   不需要 Manifest 声明（事件函数是 base 语料定义的，mod 只是搭车）。
 *
 * ## 旁路（golden 零变化）
 *
 * 槽未安装（关态 / 无 mod）或该事件名无任何运行期注册时，不构造任何上下文、不进入注册表查询
 * 之外的代码，事件链逐位不变。处理器抛异常按 per-mod 隔离（记录后继续下一个），不打断事件链。
 *
 * ## SINGLE 的口径
 *
 * 与引擎 `#SINGLE` 同口径：**只截断本桶余下成员，不跨桶**（`ProcessState.cs:330-333` +
 * `CalledFunction.cs:433-443` 的 `ShiftNextGroup()`）。ERB 成员返回 1 截断时，本桶的运行期处理器
 * 也一并跳过（它们同属该桶的余下成员）；运行期处理器返回 `SINGLE` 只截断本桶余下的运行期处理器。
 */
object ModEventWiring {
    /** `store.aux` 槽名（家族前缀 `mod.`，全小写）。 */
    const val AUX_SLOT = "mod.events.handlers"

    /**
     * 槽内容：本会话的共享注册表 + 日志面。
     *
     * 用**可变 holder** 而不是每次新建槽：`RuntimeAux` 没有单槽删除，读档重装 = 换 holder 里的
     * registry（与 [FunctionHookWiring] 同范畴）；[uninstall] 按实例同一性判「是不是我这一份」。
     */
    private class Handle(
        var registry: EventDispatchRegistry?,
        var logSink: (String) -> Unit,
        var timing: ModTimingLedger? = null,
        var budget: ModHandlerBudget? = null,
    )

    /** 把本会话的共享注册表挂到 [store]；重复安装 = 覆盖（读档后重装同形）。 */
    fun install(
        store: VariableStore,
        registry: EventDispatchRegistry,
        logSink: (String) -> Unit,
        timing: ModTimingLedger? = null,
        budget: ModHandlerBudget? = null,
    ) {
        val handle = store.aux.slot(AUX_SLOT) { Handle(registry, logSink, timing, budget) }
        handle.registry = registry
        handle.logSink = logSink
        handle.timing = timing
        handle.budget = budget
        // 主动触发通道（AE 车）：装配期把「注册表 → 引擎派发目标」的桥接上；引擎派发目标由
        // ProcessRuntime 构造期挂进 store.aux（本装配点拿不到 ProcessRuntime 实例）。
        registry.adoptTriggerBridge { key -> ModEventTriggerWiring.trigger(store, key) }
    }

    /** 解绑**本会话那一份**：传入别的会话的注册表时 no-op。 */
    fun uninstall(store: VariableStore, registry: EventDispatchRegistry) {
        val handle: Handle = store.aux.peek(AUX_SLOT) ?: return
        if (handle.registry === registry) {
            handle.registry = null
            // 主动触发桥一并撤销：解绑后 events.trigger 回到显式 fail-closed（UOE）。
            registry.adoptTriggerBridge(null)
            // 事件面收口包含「主动触发」的两条通路：公共面由上面那行撤销，raw 面
            // （`store.aux` 里的 `mod.events.trigger` 目标，由 ProcessRuntime 构造期挂上）
            // 必须一起摘掉。CL/CO 两波审计发现 shutdown 漏了这一项：公共面已 fail-closed，
            // 但 raw 路径在 VM 线程上仍能派发进已收口的会话。这里按会话级前提无条件解绑
            // （目标实例只有引擎自己持有，本层拿不到引用），与 detach 的幂等语义一致。
            detachModTriggerTarget(store)        }
    }

    /** 该事件名是否有运行期注册（派发前的旁路判据；连桶遍历都不做）。 */
    fun hasHandlers(store: VariableStore, eventKey: String): Boolean =
        store.aux.peek<Handle>(AUX_SLOT)?.registry?.hasHandlers(eventKey) == true

    /**
     * 本会话的日志面（未装接线时 null）：供同家族接线（如 [ModEventTriggerWiring] 的延迟队列
     * 超限告警）复用**同一个** logSink，不各自另开通道。
     */
    internal fun logSink(store: VariableStore): ((String) -> Unit)? =
        store.aux.peek<Handle>(AUX_SLOT)?.logSink

    /**
     * 跑某桶的运行期处理器（在该桶 ERB 成员之后调用）。
     *
     * 命中 `EventResult.SINGLE` 即停止本桶余下的运行期处理器（与引擎 `#SINGLE`
     * 「只截断本桶、不跨桶」同口径）；本桶的 ERB 成员此时已执行完，故不需要回传截断旗标。
     */
    fun runBucket(store: VariableStore, eventKey: String, bucketIndex: Int) {
        val handle = store.aux.peek<Handle>(AUX_SLOT)
        val registry = handle?.registry
        if (handle == null || registry == null) return
        for (entry in registry.handlersOf(eventKey, bucketIndex)) {
            // 处理器抛任何 Throwable 都只隔离这一个：事件链是游戏主相位，决不能因 mod 崩掉。
            val result = runCatching { invokeHandler(handle, entry, eventKey, bucketIndex) }
                .getOrElse { t ->
                    handle.logSink(
                        "[${entry.packageId}][WARN] 事件处理器异常已隔离 key=$eventKey bucket=$bucketIndex " +
                            "cause=${t::class.java.simpleName}: ${t.message}",
                    )
                    EventResult.CONTINUE
                }
            if (result == EventResult.SINGLE) break
        }
    }

    /**
     * 调一次处理器：先过单次耗时预算（装了才计时），内层保留既有耗时账本测量。
     *
     * 异常照原样抛出（由调用点的既有隔离接住），耗时在 finally 里照记 —— 抛错的处理器一样耗时间。
     * 预算为 null 时直接走 [invokeMeasured]，不取时钟、不拼操作名字符串。
     *
     * 预算诊断域用 [DiagnosticDomain.EVENT]：本处是事件桶派发的运行期处理器调用点，
     * 键空间是「事件名 + 桶名」（如 `EVT.NORMAL`）。不借用 [DiagnosticDomain.PHASE]：相位域
     * 的键是 PhaseRegistry 的相位名；也不借用 [DiagnosticDomain.HOOK]：其键空间是 hook
     * targetKey —— 写入别的域会让工具侧按域判读时错位。
     */
    private fun invokeHandler(
        handle: Handle,
        entry: EventDispatchRegistry.Entry,
        eventKey: String,
        bucketIndex: Int,
    ): EventResult {
        val context = HandlerContext(eventKey, entry.packageId)
        val budget = handle.budget
        return if (budget == null) {
            invokeMeasured(handle, entry, context, eventKey, bucketIndex)
        } else {
            budget.run(entry.packageId, DiagnosticDomain.EVENT, eventKey + "." + bucketName(bucketIndex)) {
                invokeMeasured(handle, entry, context, eventKey, bucketIndex)
            }
        }
    }

    /**
     * 既有耗时账本测量（`event.<EVENTKEY>.<桶名>`）；无账本时零开销直调。
     *
     * 逻辑与预算接入前逐字相同，只抽出来给 [invokeHandler] 的两条路径共用。
     */
    private fun invokeMeasured(
        handle: Handle,
        entry: EventDispatchRegistry.Entry,
        context: HandlerContext,
        eventKey: String,
        bucketIndex: Int,
    ): EventResult {
        val ledger = handle.timing
        return if (ledger == null) {
            entry.handler.onEvent(context)
        } else {
            ledger.measureValue(entry.packageId, "event.$eventKey.${bucketName(bucketIndex)}") {
                entry.handler.onEvent(context)
            }
        }
    }

    /** 桶下标 → 桶名（`ONLY` / `PRI` / `NORMAL` / `LATER`）。 */
    private fun bucketName(bucketIndex: Int): String = EventBucket.entries[bucketIndex].name

    /** 事件回调的只读上下文（`EventContext` 的引擎侧最小实现）。 */
    private class HandlerContext(
        override val eventKey: String,
        override val packageId: String,
    ) : EventContext
}
