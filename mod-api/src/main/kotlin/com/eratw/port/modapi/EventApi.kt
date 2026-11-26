package com.eratw.port.modapi

/**
 * 事件能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3 / §6.1 贡献点）。
 *
 * mod 经本 API 注册事件处理器/游戏相位钩子，与 ERB 事件共享同一套四桶执行序
 * （ONLY → PRI → NORMAL → LATER，同桶链接序；映射引擎 `EventRegistry` 的桶语义）。
 *
 * ## 接线状态（已接线）
 *
 * 引擎的 `EventRegistry`（core/code）仍是**构建期不可变**、承载 ERB 事件函数的唯一成员链真源；
 * 运行期 Kotlin 处理器由独立的 `EventDispatchRegistry` 承载，在 `ProcessRuntime.dispatchEventMembers`
 * 的**桶边界**混排——同桶顺序 = 先 ERB 成员（链接序），再运行期处理器（全局注册序）。
 *
 * 两条边界须知：
 * - **`ONLY` 桶运行期不可注册**（`register` 抛 IAE）：`#ONLY` 会终止整条事件链，运行期插入会让
 *   base 与其它 Mod 的同事件成员全部失效。要在事件前后拦截/替换，用 hooks 的 `PREFIX` +
 *   `HookResult.SkipOriginal`——hook 目标键灌源 = 函数目录 ∪ 事件目录（2026-11-26 B4），
 *   `hooks.register("EVENT…")` 过拼错闸后绑到 Manifest 里声明的事件键槽上。
 * - 一个会话一份共享注册表：跨 mod 的「桶位 → 桶内注册序」执行序才确定（与 hook 同判例）。
 *
 * clean 语义（§18.7）：mod 须守引擎事件序契约（§6.1），不替换 ERB dispatch；SINGLE 终止旗标
 * 只截断本桶余下成员、不跨桶（与引擎 `EventRegistry` 同口径）。
 */
interface EventApi {
    /**
     * 注册一个事件处理器。[eventKey] 按大写归一；[bucket] 决定执行桶位。
     * 返回的 [Registration] 可在 [EraMod.onDisable] 时撤销；返回值不接也不会泄漏 —— 生产宿主
     * 经 context 构造的注册收集器收走**每个**返回 [Registration] 的注册项（events / hooks /
     * phases / jobs / interactions / actions / ui 全走同一道 track 包装），并在本包 [EraMod.onDisable]
     * **之前**批量撤销（shutdown / 失败回滚都是先撤销后回调）。台架 `TestModSession.disable()`
     * 同口径先代撤后回调（2026-11-26 A3 对齐；旧形态「只裸调 onDisable、不代撤」已拆除）。
     * 要在台架断言「禁用后不再命中」因此直接成立，不自持返回值也不泄漏。
     *
     * ## 事件键必须是宿主事件表里的名字（或已声明的自定义事件）
     *
     * [eventKey] 不是自由命名：生产会话把链接期 `SymbolCatalog.events`（链接语料里定义过的
     * 事件函数；事件名全集是引擎 `Symbols.EVENT_LABEL_NAMES` 的固定标号表（1.824 基线九个；蛇版 4.981 另有
     * `BEFORE_THROW`/`BEFORE_ERROR`，在册 B24，未进本表前不属已知事件），语料定义了其中
     * 哪些、哪些才进表）灌成已知事件集（app 装配 `ModRuntimeAssembly` →
     * `ModRuntimeHost.knownEventKeys` → `install` 在逐 mod onLoad 之前采纳）。已知集非空时，
     * 注册不在集内的键当场抛 [IllegalArgumentException] 并附最接近候选 —— 拦的是「拼错/自造
     * 事件名」：mod 处理器只搭进语料已有事件的四桶链。**自造键的合法通道是 [declare]**
     * （2026-11-26 A1）：先声明自定义事件话题，再对它 register/trigger，同一四桶链派发。
     * 已知集为空（台架 / 单测 / 无链接目录的会话）不校验 ——
     * **台架单测因此拦不住自造键**，装上真语料才会在 onEnable 炸（mod 装载失败围栏逐包回滚
     * 禁用）。注册前先用 [exists] / [knownEventKeys] 自查。
     *
     * @throws IllegalArgumentException [eventKey] 空白；[bucket] 是 [EventBucket.ONLY]；
     *   或已知事件集非空且 [eventKey] 不在其中（附最接近候选）
     */
    fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration

    /**
     * 声明一个 **mod 自定义事件话题**（2026-11-26 A1：自声明、自 trigger、多 mod 可订阅的
     * 共享总线；消解「自定义事件键文档不说、闸拦死」的模糊态）。
     *
     * 声明后该键在 [register] / [trigger] 的存在性闸放行（语料事件 ∪ 已声明自定义事件），
     * 派发走引擎真实事件链：该键无 ERB 成员，四桶只跑运行期 Kotlin 处理器（桶序 /
     * SINGLE 截断 / hook 包裹逐位同口径）。多 mod 协作：话题声明**幂等**（重复声明同一键
     * 不报错，订阅方不依赖声明方的装载序），处理器照旧经 [register] 返回的 [Registration]
     * 随包代撤。
     *
     * 命名空间分离：[eventKey] 与语料已有事件相撞（已知事件集非空时）当场抛
     * [IllegalArgumentException] —— 语料事件直接 [register] 搭进四桶链即可，声明它是
     * 意图混淆，不静默。空白键同样拒绝。
     *
     * @throws IllegalArgumentException [eventKey] 空白，或与语料已有事件相撞
     * @throws UnsupportedOperationException 当前实现未接线自定义事件域（缺省 fail-closed）
     */
    fun declare(eventKey: String) {
        throw UnsupportedOperationException(
            "events.declare($eventKey) 未接线：当前 EventApi 实现没有自定义事件域" +
                "（台架镜像 / 旧装配路径）；不静默假装声明成功",
        )
    }

    /**
     * 主动触发一次事件派发（AE 车，[EraMod] 侧对引擎真实事件链的同步入口）。
     *
     * 走的是**引擎真实事件派发路径**：与 ERB 里 `CALLVENT` / 事件函数本身完全同一条链
     * （四桶序 ONLY → PRI → NORMAL → LATER，每桶先 ERB 成员、再运行期 Kotlin 处理器），
     * 不是台架桩、不复制第二套判定。因此触发时该事件的 ERB 函数体会真的执行、mod 自己
     * 注册在同事件上的处理器（以及别的 mod 的）也会被派发。
     *
     * ## 归一与存在性闸
     *
     * [eventKey] 先归一（`trim` + 大写，与 [register] 同口径）；装配期若灌入了构建期的事件名
     * 集合（生产会话有 `SymbolCatalog` 时恒有），未知事件名当场抛 [IllegalArgumentException]
     * 并附最接近的候选 —— 与 [register] 的拼错闸同一文案口径（避免「触发一个不存在的事件」
     * 被静默吞掉）。集合为空（台架 / 单测无目录）时按 [register] 同口径不校验。
     *
     * ## 线程与重入（只剩一条拒绝面：非 VM 线程）
     *
     * - **只允许 VM 线程调用**：引擎用「VM 根级 run 进入时的线程身份」核对当前线程；首次 VM
     *   run 之前还没有可识别的 VM 线程，此时调用被拒（fail-closed，不猜线程）。这是本方法
     *   **唯一**的即时拒绝面。
     * - **VM 脚本 run 进行中 = 延迟投递（不拒绝）**：从 hook / 相位处理器等「VM run 内的回调」
     *   里调用时，事件名先归一、过存在性闸，随后按 FIFO 入队并立即返回；本次根级 run 收尾时
     *   在**同一 VM 线程**上按入队序走真实事件链派发（因此事件体不会插进当次脚本执行中间）。
     *   单次 run 收尾最多派发 64 条，超限的余下条目整批丢弃并记一条 WARN（不静默）。
     * - **事件派发过程中 = 延迟投递（不拒绝）**：在某个事件处理器里再调用本方法不会抛错，
     *   而是进入**同一条** FIFO 延迟队列，等本次事件派发的四桶与 hook 全部跑完后立即按序派发
     *   （不嵌套派发、不乱序、不丢；见 `EVT.NORMAL` 里触发 `EVT.LATER` 的典型用法）。
     *   两处收尾共用队列且幂等：同一事件不会因为两处 flush 被派发两遍。
     * - **连环触发有硬闸**：处理器互相触发（A → B → A …）时，一条 flush 链内最多再入队 64 轮；
     *   到顶后停止入队、该次调用返回假并不抛错，同时落一条 WARN 到事件接线日志面（文案带触发
     *   事件名与链深，不静默丢、也不死循环）。
     *
     * 换句话说：run 内 / 派发中调用 = 派发到本次 run 或本次事件派发结束之后；两次 VM run
     * 之间调用 = 当刻立即派发。
     *
     * ## 装配缺失
     *
     * 宿主未装配事件派发接线（`ModEventWiring.install` / 引擎派发目标）时抛
     * [UnsupportedOperationException]，不静默丢弃（否则作者会以为事件被触发了）。
     *
     * @throws IllegalArgumentException 事件名为空，或存在性闸拒绝（未知名 + 最近候选）
     * @throws IllegalStateException 非 VM 线程
     * @throws UnsupportedOperationException 宿主未装配事件派发接线
     */
    fun trigger(eventKey: String) {
        // 缺省 = 显式 fail-closed：台架镜像（mod-testing 的 RecordingEvents）与旧装配实现没有
        // 引擎派发目标，宁可当场抛错也不静默丢弃。生产 face（ModContextImpl.events）覆写本方法，
        // 经 EventApiAdapter 走引擎真实派发路径；ack 台架侧补齐另派（本车不改 mod-testing）。
        throw UnsupportedOperationException(
            "events.trigger($eventKey) 未接线：当前 EventApi 实现没有引擎事件派发目标" +
                "（台架镜像 / 旧装配路径）；不静默丢弃触发",
        )
    }

    /**
     * 只读查询：某事件名是否在**当前会话的已知事件集合**里（判断不抛异常）。
     *
     * [eventKey] 按与引擎 `EventDispatchRegistry` 完全同口径归一（`trim` + 大写 ROOT），所以作者
     * 写全小写或带前后空格也能命中；**空白 key 一律返回 false、不抛**（`exists(userInput)` 不必先判空）。
     *
     * 已知事件集合来自链接期 `SymbolCatalog.events`：宿主装配时经 `ModRuntimeHost.knownEventKeys`
     * 灌入（链见 app 侧 `ModRuntimeAssembly`）。**未装配时集合为空 ⇒ 本方法恒 false**：这是有意的
     * fail-safe —— 台架 / 旧装配路径不知道真实事件面，查询面不猜、也不让作者依赖假阳性（要点是
     * 「不知道」不等于「存在」）。
     *
     * 本方法典型用途是「有就触发、没有就跳过」，替代对 [trigger] 未知名异常的 catch；要打印可用
     * 事件名用 [knownEventKeys]。
     *
     * 缺省实现显式 fail-closed 抛 [UnsupportedOperationException]（与 [trigger] 缺省同口径）：
     * 不认识本方法的旧实现绝不静默返回假答案。生产 face（`ModContextImpl.events`，未装配时走空集
     * 兜底）与台架面（mod-testing 的 `RecordingEvents`）覆写本方法。
     */
    fun exists(eventKey: String): Boolean =
        throw UnsupportedOperationException(
            "events.exists($eventKey) 未接线：当前 EventApi 实现没有已知事件集合" +
                "（台架镜像 / 旧装配路径）；不猜事件面",
        )

    /**
     * 只读查询：当前会话已知事件名集合的**快照**，按字典序（`String` 自然序）稳定排序。
     *
     * 供作者自查 / 打印可用事件名（`check-mod` 静态面补不上这块运行期知识）；排序固定让打印与
     * 断言的输出确定。返回的是独立列表：调用方随便增删改它，都不影响引擎注册表或之后的调用。
     *
     * 已知事件集合来源与 [exists] 同一条链（链接期 `SymbolCatalog.events` → 宿主装配时的
     * `ModRuntimeHost.knownEventKeys`）；**未装配时返回空表**（有意 fail-safe，不猜）。
     *
     * 缺省实现显式 fail-closed 抛 [UnsupportedOperationException]。
     */
    fun knownEventKeys(): List<String> =
        throw UnsupportedOperationException(
            "events.knownEventKeys() 未接线：当前 EventApi 实现没有已知事件集合" +
                "（台架镜像 / 旧装配路径）；不猜事件面",
        )
}

/** 可撤销的注册（onDisable 撤销注册用，§18.4）。 */
interface Registration {
    fun unregister()
}

/** 事件四桶执行序（映射引擎 `EventRegistry` 的桶位 0..3）。 */
enum class EventBucket { ONLY, PRI, NORMAL, LATER }

/** 处理器返回值：[SINGLE] 截断本桶余下成员（不跨桶）；[CONTINUE] 继续。 */
enum class EventResult { CONTINUE, SINGLE }

/**
 * 一个事件处理器。[EventContext] 携带事件名与归属包等只读上下文；处理器改游戏状态
 * 仍须经 [RuntimeScheduler.submit]（事件回调可能在主执行线程，但 mod 不假设线程，§18.5）。
 */
fun interface EventHandler {
    fun onEvent(context: EventContext): EventResult
}

/** 事件回调的只读上下文（K1 最小形态；具体相位载荷随 K3 贡献点机器补全）。 */
interface EventContext {
    val eventKey: String
    val packageId: String
}