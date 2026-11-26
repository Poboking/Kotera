package com.eratw.port.core.proc

/**
 * 执行期入力滞留队列（PLAY-INPUT-LATE · 2026-11-23 真机「点地图键没反应/按不到键」收口）。
 *
 * ## 屏上发生的事
 *
 * 动态地图页（`ERB/魔改内容/qol/qol_MAP.ERB:32-60`）每个 `ANIMATERECOLOREDMAPS` 毫秒
 * 做一遍「`CLEARLINE LINECOUNT - LINESTART` → 整屏重印 → `TINPUTSNF`」，重印的那一段
 * 在 VM 线程上是**纯执行期**（`isAwaiting == false`）。玩家点在重印窗口里的那一下，
 * 宿主 `GameHost.deliver` 直接丢弃（`!sink.isAwaiting` ⇒ `inputGateDropped++` 后 return），
 * 于是「点两三次才动」；点到「返回」时被丢，就卡在地图页出不来。
 *
 * ## 原版为什么没有这个窗口
 *
 * 原版脚本与消息泵同线程（`Process.cs:444-470` 的 `DoScript` 外层循环）：脚本跑的时候
 * 没人取鼠标消息，玩家的每一次点击都躺在 Windows 消息队列里 **原样、排好序**，等到
 * `EmueraConsole.WaitInput` 那一轮 `DoEvents`（`EmueraConsole.cs:1040-1082`）才被取出，
 * 并且是**按取出那一刻的版面**解释（`MainWindow.cs:824-856` 的命中分支）。
 * 也就是说：原版「执行期点击」既不丢，也不会按旧版面误命中。
 *
 * ## 本类做什么
 *
 * 只做原版消息队列那一段的等价物：把执行期的那一笔**有界、限时**地留住，到下一个
 * 发布边界（`LineBufferConsoleSink.publishFrame`，即宿主侧的 `DoEvents` 时机）交给
 * 宿主按**当刻**版面重新裁决一次 —— 命中不到就丢并记 `layoutMismatch`，不许猜。
 *
 * 线程：只在宿主线程组里用（`GameHost.deliver` 在 UI/输入线程 offer，
 * `replayLateInputs` 在 VM 线程 drain），内部锁自持，不回调外部。
 *
 * ## 为什么按值命中，而不是用原始点坐标重新命中（2026-11-23 与组长对齐的取舍）
 *
 * 原版是「按命中点解释」的（`MainWindow.cs:824-856` 在消息泵里拿当时的鼠标点去问按钮矩形），
 * 移植侧本可以照抄：把 (x, y) 也留在队列里，回放时再问一次视图的命中表。**没有这么做**，三条理由：
 *
 * 1. **线程归属**：命中表在 `CanvasConsoleView`（`hitOnCurrentView` / `RowLayout`）里，那些对象
 *    是 UI/渲染线程的既有状态；回放点在 VM 线程的帧发布边界（见 `onFramePublished`），
 *    跨线程去问视图等于把一个已定的单线程不变量（ADR-003）打开一个洞。
 * 2. **另一条路也被堵死**：`QueueInputSink.onWaitEntered` 的 KDoc 明写「实现不得回调 sink 自身
 *    （会自锁）」，所以唯一的「脚本线程上的等待入口」也不能用来发包，坐标级方案只能再引入
 *    一次跨线程 hop 与一套快照协议 —— 收益只在「同值不同位」的极端版面。
 * 3. **安全侧**：按值命中在版面已换页时命中不到 ⇒ 计 `layoutMismatch` 丢弃（不猜、不误点）；
 *    同值多处 ⇒ 仍只回放一笔（`ambiguousHits` 记账）。真出问题时错的方向是「少点一次」，
 *    不是「点到别的按钮」，与原版「绝不按旧版面误命中」的判据同侧。
 *
 * 代价写清楚：若某一屏有两个同值按钮且玩家要的是其中一个，回放只能兑现「值」这一层语义，
 * 分不清是哪一个 —— 这与移植侧本来就按值交付的输入面（`submitButtonValue`）逐字一致，
 * 不是本队列新增的限制。
 *
 * @param ttlMs 滞留上限。原版消息会一直躺到下一次 `DoEvents`，但移植侧重印窗口是毫秒级，
 *   超时的点击按原版「玩家已经看过两屏之后再兑现」没有意义，故给一个短上限并单独计数。
 * @param capacityPerKind 每类事件最多留几笔（超出丢最旧）。同原版队列一样是「最近几笔」，
 *   不是无界缓冲。
 */
class LateInputQueue(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacityPerKind: Int = DEFAULT_CAPACITY_PER_KIND,
) {

    /** 事件类型：文本提交与按钮点击分开计量（原版也是两套消息）。 */
    enum class Kind { TEXT, BUTTON }

    /** 一笔被留住的入力。携带提出时刻与当时的解析结果。 */
    class Entry internal constructor(
        val kind: Kind,
        val text: String,
        val mesSkip: Boolean,
        val mouseButton: Int?,
        val offeredAtMs: Long,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Entry>()

    /** 超时丢弃计数。 */
    var expired: Long = 0L
        private set

    /** 同类超额丢最旧的计数。 */
    var overflow: Long = 0L
        private set

    /** 回放时**当刻版面**命中不到（选项集里没有这个值）而丢弃的计数。 */
    var layoutMismatch: Long = 0L
        private set

    /** 真正回放出去的笔数。 */
    var replayed: Long = 0L
        private set

    /**
     * 回放时「同一笔点击在当刻版面里命中到**多处**同值按钮」的次数（诊断用）。
     *
     * 与四个丢弃桶并列但不属于丢弃：同值多处时**仍只提交一次**（一笔点击只兑现一次，
     * 与原版按命中点解释一次一致），这里只记录「这一拍版面本身有歧义」这件事，
     * 供真机现场判断是「按值命中撞上同值按钮」还是「版面根本不匹配」。
     */
    var ambiguousHits: Long = 0L
        private set

    /** [drainForLayout] 的结果：可回放的笔 + 当刻版面的歧义命中数。 */
    class ReplayPlan(val entries: List<Entry>, val ambiguous: Int)

    /** 当前滞留笔数（诊断面）。 */
    val size: Int get() = synchronized(lock) { pending.size }

    /**
     * 留一笔执行期入力。返回值只回答一件事：**有没有为腾位置挤掉更旧的一笔**
     * （false = 挤掉了，且已计入 [overflow]）。新的一笔**恒被留下** —— 宿主不许把这个
     * 返回值当成「这笔被丢了」（2026-11-23 组长口径：桶不许说谎）。
     *
     * 同类型满了就把**最旧**的一笔挤掉：玩家在重印窗口里连点两下，想看的是最后那一下，
     * 与原版消息队列被 `PeekMessage` 逐条取出的顺序一致（先入先出，超界只留最近几笔）。
     */
    fun offer(
        nowMs: Long,
        kind: Kind,
        text: String,
        mesSkip: Boolean,
        mouseButton: Int?,
    ): Boolean = synchronized(lock) {
        val sameKind = pending.count { it.kind == kind }
        var dropped = false
        if (sameKind >= capacityPerKind) {
            val oldest = pending.indexOfFirst { it.kind == kind }
            if (oldest >= 0) {
                pending.removeAt(oldest)
                overflow++
                dropped = true
            }
        }
        pending.addLast(Entry(kind, text, mesSkip, mouseButton, nowMs))
        !dropped
    }

    /**
     * 取出所有可回放的笔（按提出顺序），并把**当刻版面**裁决交给 [accept]：
     * false = 版面已变（命中不到）⇒ 丢弃并计入 [layoutMismatch]。
     *
     * 超过 [ttlMs] 的笔在取出时丢弃并计入 [expired]；为 0/负数时按「只留到下一拍」处理。
     * 被接受的笔计入 [replayed]。
     */
    fun drain(nowMs: Long, accept: (Entry) -> Boolean): List<Entry> = synchronized(lock) {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList<Entry>(pending.size)
        while (pending.isNotEmpty()) {
            val e = pending.removeFirst()
            val age = nowMs - e.offeredAtMs
            when {
                ttlMs > 0L && age > ttlMs -> expired++
                !accept(e) -> layoutMismatch++
                else -> out += e
            }
        }
        replayed += out.size
        out
    }

    /** 丢弃全部滞留（会话收口 / 回到标题）。 */
    fun clear() = synchronized(lock) { pending.clear() }

    /**
     * 宿主侧的标准回放裁决：[drain] 的「按当刻版面命中」实现。
     *
     * 判据（与 `GameHost.replayLateInputs` 一致）：
     * - 文本提交与选项集无关，放行（原版文本框提交同理）；
     * - 按钮：当刻选项集里**有同值项**才放行，命中数与 0 记 [layoutMismatch] 丢弃；
     * - 同值多处（窄屏下 `[<]`/`[>]` 这类重复值）**仍只产生一笔回放**，另有 [ambiguousHits]
     *   记歧义次数 —— 一笔点击只兑现一次，不许同值双触发。
     *
     * 这层刻意**不做坐标级重新命中**：见类头的「为什么按值命中」节。
     */
    fun drainForLayout(nowMs: Long, optionValues: List<String>): ReplayPlan {
        var ambiguous = 0
        val kept = drain(nowMs) { e ->
            if (e.kind == Kind.TEXT) {
                true
            } else {
                val hits = optionValues.count { it == e.text }
                if (hits > 1) ambiguous++
                hits >= 1
            }
        }
        if (ambiguous > 0) synchronized(lock) { ambiguousHits += ambiguous }
        return ReplayPlan(kept, ambiguous)
    }

    companion object {
        /**
         * 滞留上限（毫秒）。动态地图的重印窗口按真机实测是「一次整屏重印」的量级，
         * 250 ms 覆盖 FPS=3（333 ms 间隔）那一档的窗口并留一点余量；更久的点击
         * 在原版里也确实会在下一屏兑现，但那已经属于「玩家自己都换页了」，按超时丢弃。
         */
        const val DEFAULT_TTL_MS: Long = 250L

        /** 每类事件最多留几笔（原版消息队列被连续点击压满时的等价有界口径）。 */
        const val DEFAULT_CAPACITY_PER_KIND: Int = 2
    }
}
