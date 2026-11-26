package com.eratw.port.core.mod

import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.Registration
import java.util.Locale

/**
 * 运行期事件处理器注册表（era-arch-v61u.md §6.1 贡献点 / §20 事件面；RT2 P1 真接线）。
 *
 * ## 为什么不是第二套事件系统
 *
 * 事件**成员链**仍由构建期的 `EventRegistry`（LSC 四桶视图）唯一派生，本表只承载
 * **运行期注册的 Kotlin 处理器**，两者在 `ProcessRuntime.dispatchEventMembers` 的
 * **同一桶位**里混排执行：同桶内先跑 ERB 成员（链接序 = base 先、各 Mod 后），再按注册序
 * 跑本表里该桶的 Kotlin 处理器。所以「四桶序 ONLY → PRI → NORMAL → LATER」与
 * 「SINGLE 只截断本桶、不跨桶」两条引擎语义逐字保留，本表不含任何派发判定。
 *
 * ## 一个会话一份（跨 mod 共享）
 *
 * 与 [HookPipeline] 同理：注册序要跨 mod 才有确定意义，所以每个 [ModContextImpl] 装载时经
 * [ModContextImpl.adoptEventRegistry] 换成宿主那一份。各自一份会把全局注册序按 mod 分组拆散。
 *
 * ## ONLY 桶不开放
 *
 * 运行期注册拒绝 [EventBucket.ONLY]：原版 `#ONLY` 的语义是「执行完即终止整条事件链」
 * （`CalledFunction.cs:453-455` 的 `IsOnly` + `:445-451` 的 `FinishEvent()`），是**构建期按函数
 * 旗标分桶**的结果；运行期插一个 ONLY 处理器会让 base 与其它 mod 的同事件成员全部失效，是比
 * 「改不生效」危险得多的静默陷阱。要拦截/替换事件用 hooks 的 `PREFIX` + `HookResult.SkipOriginal`
 * （§20.1），那条路有 Replace/SkipOriginal 的显式语义与失败隔离——2026-11-26 B4 起 hook
 * 目标键灌源 = 函数 ∪ 事件并集，这条建议在生产装配下真正走通（旧灌源只有函数键时事件键被
 * 拼错闸拦死）。
 *
 * ## 线程
 *
 * 注册发生在 `onLoad` / `onEnable`，派发发生在 VM 线程的事件派发内，两者同线程且都是同步调用，
 * 故不加锁（与 [HookPipeline] 同口径）。派发前取列表快照，处理器内部注册/撤销不影响本轮。
 */
// TooManyFunctions：A1（declare 声明域）与 B4（灌源并集）把读数面从 10 扩到 12——
// register/trigger/exists/declaredEventKeys 各自语义独立且 KDoc 契约绑定，硬拆伤可读性。
@Suppress("TooManyFunctions")
class EventDispatchRegistry {

    /** 一个已注册处理器：归属包 + 处理器（派发时只读）。 */
    internal class Entry(
        val packageId: String,
        val handler: EventHandler,
    )

    /** 事件键（大写归一）→ 四桶各自的注册序处理器列表。 */
    private val buckets = HashMap<String, Array<MutableList<Entry>>>()

    /**
     * 已知事件名集合（[canonical] 归一后的只读快照）：非空时才校验 [register] 的事件名。
     *
     * 缺省空集 = **不校验**：台架 / 单测的会话里没有构建期 `EventRegistry`（不知道真实事件面），
     * 此时任意事件名都放行，与加闸前逐位一致。装配侧（app `ModRuntimeAssembly.attach`）在
     * `install` 之前经 [ModRuntimeHost.knownEventKeys] 灌入真实事件名集合。
     *
     * 只整体替换、不清单条；写入与读都在装配/注册线程，同 [register] 的线程口径，不加锁。
     */
    private var knownEvents: Set<String> = emptySet()

    /**
     * mod 自定义事件域（2026-11-26 A1）：[declare] 声明的话题键（[canonical] 归一，会话内累计）。
     *
     * 声明后的键进入 [register] / [trigger] 的存在性闸（与语料事件同一条四桶链派发，
     * 引擎侧 [com.eratw.port.core.proc.ProcessRuntime.executeEvent] 对无 ERB 成员的键
     * 只跑运行期处理器）。声明是**幂等**的：同一键多个 mod 重复声明不报错（共享总线
     * 话题的订阅不依赖装载序），但与 [knownEvents] 相撞 = IAE（命名空间分离，见 [declare]）。
     */
    private val declaredEvents = LinkedHashSet<String>()

    /**
     * 主动触发（`context.events.trigger`）的引擎派发桥（AE 车）：由 [ModEventWiring.install]
     * 在装配期指向 `store.aux` 里的引擎派发目标，[uninstall] 置回 null。
     *
     * 只在**真有人调用** `events.trigger` 时才读；事件注册/派发热路径零开销。null = 主动触发
     * 通道未装配（[trigger] 显式 fail-closed，不静默丢弃）。
     */
    private var triggerBridge: ((String) -> Boolean)? = null

    /** 装配/撤销主动触发桥；传 null = 回到「未装配」态（[trigger] 抛 UOE）。 */
    internal fun adoptTriggerBridge(bridge: ((String) -> Boolean)?) {
        triggerBridge = bridge
    }

    /**
     * 主动触发一次事件（`context.events.trigger` 的引擎侧入口；AE 车）。
     *
     * 归一与存在性闸与 [register] 同口径（同一个 [canonical] / 同一套最近候选），区别在拒绝
     * 文案点明「主动触发」而不是「注册」。通过闸门后交给 [triggerBridge]：它负责唯一的即时闸
     * （VM 线程）、「VM run 内 / 事件派发中延迟投递（绝非当场重入派发）」的口径与连环触发
     * 上限，并调引擎真实派发路径（见 [ModEventTriggerWiring]）。
     *
     * @throws IllegalArgumentException 事件键为空，或 [knownEvents] 非空且事件名未知
     * @throws UnsupportedOperationException 主动触发通道未装配（宿主未执行 [ModEventWiring.install]）
     */
    fun trigger(eventKey: String, origin: String): Boolean {
        require(eventKey.isNotBlank()) { "事件键不能为空（eventKey 按大写归一）" }
        val key = canonical(eventKey)
        val known = knownEvents
        require(known.isEmpty() || key in known || key in declaredEvents) {
            unknownTriggerEventMessage(origin, eventKey, key, known)
        }
        val bridge = triggerBridge ?: throw UnsupportedOperationException(
            "事件主动触发通道未装配（$origin / events.trigger($eventKey)）：宿主未执行 " +
                "ModEventWiring.install（事件派发接线缺失）；不静默丢弃触发",
        )
        return bridge(key)
    }

    /**
     * 只读查询：事件名是否在 [knownEvents] 里（判断不抛异常；空 key 恒 false）。
     *
     * 归一口径与 [register] / [trigger] 的存在性闸完全相同（同一个 [canonical]），但**空白 key
     * 返回 false 而不是抛 IAE**：查询面问的是「有没有这个事件」，不是「这个键合不合法」。
     *
     * [knownEvents] 为空（装配侧未灌入 = 台架 / 旧装配路径）⇒ 恒 false。这与 [register] /
     * [trigger] 在空集时「不校验、任意名放行」**有意不同**：那两条是为不改旧行为，查询面则必须
     * 给确定答案，猜一个真事件名比答「不知道」危险得多（fail-safe）。
     */
    fun exists(eventKey: String): Boolean {
        if (eventKey.isBlank()) return false
        val key = canonical(eventKey)
        val known = knownEvents
        // 自定义事件域是会话运行期状态：声明过就存在，不依赖语料事件面是否装配。
        return key in declaredEvents || (known.isNotEmpty() && key in known)
    }

    /**
     * 只读快照：当前 [knownEvents] 的字典序稳定排序副本；改返回值不影响本表。
     *
     * 未装配（集合为空）⇒ 空表。排序取 [String] 自然序而非装配序（装配序由 app 侧
     * `SymbolCatalog.events` 的遍历序决定，跨会话不必稳定），让作者打印 / 用例断言确定。
     *
     * 返回**独立可变副本**（`ArrayList`）而不是只读视图：作者/用例拿它随便清空、增删都不会
     * 影响引擎；查询面不靠「抛 UOE 的不可变列表」来表达快照语义（那会让作者误以为读引擎状态）。
     */
    fun knownEventKeys(): List<String> = knownEvents.sorted().toMutableList()

    /**
     * 采纳「已知事件名集合」作为 [register] 的存在性闸（装配侧在 `install` 之前调用）。
     *
     * 入参逐个过 [canonical]（trim + 大写），与 `EventRegistry.eventKeys` / `FuncMeta.key`
     * 同口径，所以作者写全小写或带空白也算命中。传空集 = 关闭校验（缺省即关闭）。
     */
    internal fun adoptKnownEvents(keys: Set<String>) {
        knownEvents = keys.mapTo(LinkedHashSet(keys.size)) { canonical(it) }
    }

    /**
     * 声明一个 mod 自定义事件话题（2026-11-26 A1）：声明后该键在 [register] / [trigger]
     * 的存在性闸放行，多 mod 可在同一话题上挂处理器 / 主动触发（共享总线）。
     *
     * 语义裁定（消解「文档不说、闸拦死」的模糊态）：
     * - **幂等**：同一键重复声明不报错 —— 订阅方 mod 不依赖声明方的装载序，多 mod
     *   各自声明同一话题是合法协作形态；
     * - **命名空间分离**：[knownEvents] 非空且键是语料事件 = IAE（语料事件直接
     *   [register] 搭进四桶链，声明它是意图混淆 —— 当场红，不静默）；
     * - 派发走引擎真实事件链：该键无 ERB 成员，`executeEvent` 只跑运行期处理器
     *   （四桶序 / SINGLE 截断 / hook 包裹逐位同口径）；
     * - 会话内累计、不随包卸载回收：话题是共享总线键，不是某包的私有资产
     *   （处理器本身经 [register] 返回的 [Registration] 随包代撤）。
     *
     * @throws IllegalArgumentException [eventKey] 空白；或语料事件面已装配且键是语料事件
     */
    fun declare(eventKey: String, packageId: String) {
        require(eventKey.isNotBlank()) { "事件键不能为空（eventKey 按大写归一）" }
        val key = canonical(eventKey)
        val known = knownEvents
        require(known.isEmpty() || key !in known) {
            "事件 \"$eventKey\"（来自 $packageId）是语料已有事件，不能声明为自定义事件：" +
                "直接 events.register 搭进它的四桶链即可（自定义事件域与语料事件命名空间分离）"
        }
        declaredEvents.add(key)
    }

    /** 自定义事件域快照（字典序稳定排序的独立副本；会话内累计）。 */
    fun declaredEventKeys(): List<String> = declaredEvents.sorted().toMutableList()

    /**
     * 注册一个事件处理器。
     *
     * 存在性闸（[knownEvents] 非空时生效）拦的是**拼错事件名**：拼错时处理器永远不会被派发，
     * 加闸前是静默失效，加闸后当场拒绝并给出最接近的候选，让作者在 `onLoad` 就发现。
     *
     * @throws IllegalArgumentException 事件键为空；[bucket] 是 [EventBucket.ONLY]（见类 KDoc）；
     * 或 [knownEvents] 非空且事件名不在其中（未知名拼错，附最接近候选）。
     */
    fun register(
        eventKey: String,
        bucket: EventBucket,
        packageId: String,
        handler: EventHandler,
    ): Registration {
        require(eventKey.isNotBlank()) { "事件键不能为空（eventKey 按大写归一）" }
        require(bucket != EventBucket.ONLY) {
            "events.register 不接受 ONLY 桶（$packageId/$eventKey）：原版 #ONLY 是构建期分桶的" +
                "「执行完即终止整条事件链」，运行期插入会让 base 与其它 mod 的同事件成员全部失效；" +
                "要拦截或替换事件请用 hooks 的 PREFIX + HookResult.SkipOriginal（§20.1）"
        }
        val key = canonical(eventKey)
        val known = knownEvents
        require(known.isEmpty() || key in known || key in declaredEvents) {
            unknownEventMessage(packageId, eventKey, key, known)
        }
        val slot = bucket.ordinal
        val entry = Entry(packageId, handler)
        buckets.getOrPut(key) { newBucketLists() }[slot] += entry
        return object : Registration {
            override fun unregister() {
                buckets[key]?.get(slot)?.remove(entry)
            }
        }
    }

    /** 该事件名是否有任何运行期注册（派发前的旁路判据；未知名 false）。 */
    fun hasHandlers(eventKey: String): Boolean =
        buckets[canonical(eventKey)]?.any { it.isNotEmpty() } ?: false

    /** 某事件某桶的处理器快照（按注册序）；桶位越界或未注册返回空表。 */
    internal fun handlersOf(eventKey: String, bucketIndex: Int): List<Entry> =
        buckets[canonical(eventKey)]?.getOrNull(bucketIndex)?.toList() ?: emptyList()

    /**
     * 诊断只读：本包在各桶里的处理器数（下标 = [EventBucket.ordinal]，ONLY 桶恒 0）。
     *
     * 遍历现读，不构造快照、不改注册面；未知包返回全零表。四桶计数合成一个函数返回
     * `IntArray`，避免为读数新增四个成员函数。
     */
    internal fun countsOf(packageId: String): IntArray {
        val counts = IntArray(BUCKET_COUNT)
        for (bucketLists in buckets.values) {
            for (slot in bucketLists.indices) {
                counts[slot] += bucketLists[slot].count { it.packageId == packageId }
            }
        }
        return counts
    }

    private fun newBucketLists(): Array<MutableList<Entry>> = Array(BUCKET_COUNT) { ArrayList() }

    companion object {
        /** 四桶位数（`EventBucket` 的 ordinal 直接当桶位下标）。 */
        private const val BUCKET_COUNT = 4

        /** 事件键归一：与 `EventRegistry` / `FuncMeta.key` 同口径（去空白 + 大写）。 */
        private fun canonical(key: String): String = key.trim().uppercase(Locale.ROOT)
    }
}

/** 未知名拒绝消息里最多给几个候选（只给最近的几个，不倒全量事件表）。 */
private const val NEAREST_EVENT_NAME_LIMIT = 3

/**
 * 未知事件名的拒绝消息：含**原始**事件名（作者按它 grep 自己的注册点）、归属包与最接近候选。
 *
 * 原始名而非归一后的 [key]：作者手里写的就是原始字面量，消息里的名字要能直接对回源码。
 */
private fun unknownEventMessage(
    packageId: String,
    eventKey: String,
    key: String,
    known: Set<String>,
): String {
    val nearest = nearestEventNames(key, known)
    val hint = if (nearest.isEmpty()) "" else "；最接近的候选：${nearest.joinToString(", ")}"
    return "未知事件名 \"$eventKey\"（来自 $packageId）：构建期 EventRegistry 里没有这个事件，" +
        "拼错的处理器永远不会被派发，故注册当场拒绝（不静默失效）$hint；" +
        "如果这是 mod 自定义事件，先 events.declare 声明话题再注册"
}

/**
 * 主动触发被存在性闸拒绝时的消息（与 [unknownEventMessage] 同口径，动词换成「主动触发」）。
 *
 * 归一后的 [key] 不进文案，作者手里写的是原始 [eventKey]；最近候选口径与注册闸一致。
 */
private fun unknownTriggerEventMessage(
    origin: String,
    eventKey: String,
    key: String,
    known: Set<String>,
): String {
    val nearest = nearestEventNames(key, known)
    val hint = if (nearest.isEmpty()) "" else "；最接近的候选：${nearest.joinToString(", ")}"
    return "未知事件名 \"$eventKey\"（来自 $origin 的 events.trigger）：构建期 EventRegistry 里没有" +
        "这个事件，主动触发一个不存在的事件会静默无效果，故当场拒绝$hint；" +
        "如果这是 mod 自定义事件，先 events.declare 声明话题再触发"
}

/** 至多 [NEAREST_EVENT_NAME_LIMIT] 个最接近的已知事件名，按（编辑距离，名字）排序，结果确定。 */
private fun nearestEventNames(key: String, known: Set<String>): List<String> =
    known.asSequence()
        .map { it to editDistance(key, it) }
        .sortedWith(compareBy({ it.second }, { it.first }))
        .take(NEAREST_EVENT_NAME_LIMIT)
        .map { it.first }
        .toList()

/** 编辑距离（Levenshtein，逐行滚动，只留两行）：事件名短，O(n·m) 的朴素实现足够。 */
private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
