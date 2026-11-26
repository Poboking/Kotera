package com.eratw.port.modtesting

import com.eratw.port.modapi.EventApi
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventContext
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.Registration

/**
 * 录制型 [EventApi]：**测试替身，不是生产实现**。
 *
 * 作者在 `onEnable` 里注册的事件处理器会收进本台架的表；用例用 [fire] / [fireAll] 手动派发，
 * 不必启动 `:core-vm` 引擎。派发语义照生产 `EventDispatchRegistry`（core/mod/EventDispatchRegistry.kt）
 * 与 `ProcessRuntime.dispatchEventMembers` 的两条桶边界：
 *
 * - **四桶序** `ONLY → PRI → NORMAL → LATER`（[fireAll]）；[fire] 只派发指定的一桶；
 * - **`SINGLE` 只截断本桶余下处理器，不跨桶** —— 本桶后续不再跑，下一桶照常跑；
 * - **`ONLY` 运行期不可注册**（生产同判例：`#ONLY` 是构建期分桶，运行期插入会让 base 与其它
 *   mod 的同事件成员全部失效）。
 *
 * ## 一个会话一份共享表
 *
 * 表挂在 [TestWorldState] 上，同一 [TestWorld] 的多个 [TestContext]（多 mod）共享一份注册表，
 * 于是「桶位 → 桶内注册序」跨 mod 才确定（与生产 `EventDispatchRegistry` 的「一个会话一份」同口径）。
 * 每个 context 的 [RecordingEvents] 只是带包身份的视图：[packagesFor] 能看出某事件被哪些包注册过。
 *
 * ## 事件存在性闸（与生产同口径）
 *
 * 生产 `EventDispatchRegistry` 的 `knownEvents` 缺省是空集 = **不校验**（台架 / 单测没有构建期
 * `EventRegistry`）；装配侧在 `install` 前灌入真实事件名后才拦拼错名。本台架提供同名入口
 * [adoptKnownEvents]：缺省不拦，注入后注册拼错名当场抛 [IllegalArgumentException] 并附最近
 * 候选（拒绝文案与生产 `unknownEventMessage` 逐字一致，对拍用例断言两串相等）。
 *
 * ## 与生产的差异
 *
 * 台架不模拟 ERB 事件成员链本身（那属于引擎构建期 `EventRegistry`），只跑运行期 Kotlin 处理器；
 * 派发线程不在台架范围内（生产 `ModEventWiring` 在派发线程上调处理器）。运行期处理器的**异常隔离面
 * 与生产一致**：每个处理器各自 runCatching，抛异常只隔离它一个（落 WARN 诊断），后续处理器照跑
 * （生产见 `ModEventWiring.runBucket`）。
 */
class RecordingEvents internal constructor(
    private val packageId: String,
    private val table: EventRecordTable,
) : EventApi {

    /** 一次注册的只读快照（断言「作者注册了什么」用）。 */
    data class Recorded(
        val eventKey: String,
        val bucket: EventBucket,
        val packageId: String,
    )

    /** 本会话里所有已注册的事件处理器（按注册序；跨 mod 共享）。 */
    val registrations: List<Recorded> get() = table.registrations()

    override fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration {
        require(eventKey.isNotBlank()) { "事件键不能为空（eventKey 按大写归一）" }
        require(bucket != EventBucket.ONLY) {
            "events.register 不接受 ONLY 桶（$packageId/$eventKey）：原版 #ONLY 是构建期分桶的" +
                "「执行完即终止整条事件链」，运行期插入会让 base 与其它 mod 的同事件成员全部失效；" +
                "要拦截或替换事件请用 hooks 的 PREFIX + HookResult.SkipOriginal（§20.1）"
        }
        table.requireKnown(eventKey, packageId)
        return table.register(normalize(eventKey), bucket, packageId, handler)
    }

    /**
     * 采纳「已知事件名集合」作为 [register] 的存在性闸（生产 `EventDispatchRegistry.adoptKnownEvents`
     * 的同位入口；装配侧在 `install` 之前灌入）。
     *
     * 入参逐个 [normalize]（trim + 大写），与 `EventRegistry.eventKeys` / `FuncMeta.key` 同口径，
     * 所以作者写全小写或带空白也算命中。传空集 = 关闭校验（缺省即关闭）。
     */
    fun adoptKnownEvents(keys: Set<String>) {
        table.adoptKnownEvents(keys)
    }

    /**
     * 派发**指定一桶**：按该桶内注册序回调，遇 [EventResult.SINGLE] 立即返回（只截断本桶）。
     *
     * @return 实际跑过的处理器返回值（按调用序），便于断言 `SINGLE` 截断在哪一位。
     */
    fun fire(eventKey: String, bucket: EventBucket): List<EventResult> = table.fire(normalize(eventKey), bucket)

    /**
     * 派发完整四桶链：桶序 `ONLY → PRI → NORMAL → LATER`，每桶内按注册序；
     * `SINGLE` 只截断**本桶**、下个桶照常跑（生产同口径）。
     */
    fun fireAll(eventKey: String): List<EventResult> = table.fireAll(normalize(eventKey))

    /** 某事件被哪些包注册过（按「桶序 + 注册序」的首次出现序，去重）。断言跨 mod 装配用。 */
    fun packagesFor(eventKey: String): List<String> = table.packagesFor(normalize(eventKey))

    /** 某事件已注册的处理器总数（四桶合计）。 */
    fun handlerCount(eventKey: String): Int = table.handlerCount(normalize(eventKey))

    /**
     * 只读查询：与生产 `EventDispatchRegistry.exists` **同口径**（[normalize] 后判已知；
     * 空白 key 恒 false、不抛；未注入已知集 = 空集 ⇒ 恒 false）。
     */
    override fun exists(eventKey: String): Boolean {
        if (eventKey.isBlank()) return false
        val key = normalize(eventKey)
        // 自定义事件域是会话运行期状态：声明过就存在，不依赖语料事件面是否装配（生产同口径）。
        return key in table.declaredEventKeysSnapshot || key in table.knownEventKeysSnapshot
    }

    /**
     * 声明一个 mod 自定义事件话题（2026-11-26 A1）：与生产 `EventDispatchRegistry.declare`
     * 同口径（幂等；与已知语料事件相撞 IAE，文案逐字一致）。声明后 [register] / [fire]
     * / [fireAll] 对该键照常工作（台架本就无 ERB 成员链，四桶只跑 Kotlin 处理器）。
     */
    override fun declare(eventKey: String) {
        require(eventKey.isNotBlank()) { "事件键不能为空（eventKey 按大写归一）" }
        table.declare(eventKey, packageId)
    }

    /** 自定义事件域快照（字典序稳定排序的独立副本；与生产 `declaredEventKeys` 同口径）。 */
    fun declaredEventKeys(): List<String> = table.declaredEventKeysSnapshot

    /**
     * 只读快照：与生产 `EventDispatchRegistry.knownEventKeys` **同口径**（字典序稳定排序的独立
     * 列表；未注入已知集 ⇒ 空表）。改返回值不影响台架表或后续调用。
     */
    override fun knownEventKeys(): List<String> = table.knownEventKeysSnapshot
}

/** 一个会话一份的事件注册表（语义照生产 `EventDispatchRegistry`，只承载运行期处理器）。 */
// TooManyFunctions：A1 declare/declaredEventKeys 镜像 + 快照族把面扩到 11——语义各自独立
// 且与生产 EventDispatchRegistry 逐字对拍绑定，硬拆伤镜像可读性。
@Suppress("TooManyFunctions")
internal class EventRecordTable(
    /**
     * 处理器异常隔离的诊断出口（与生产 `ModEventWiring.runBucket` 的 logSink 同位）：
     * 入参 = 肇事包 + 诊断正文。缺省丢弃（只建表、不关心日志的用例不受影响）。
     */
    private val warnSink: (packageId: String, message: String) -> Unit = { _, _ -> },
) {

    private class Entry(val packageId: String, val handler: EventHandler)

    /** 事件键（大写归一）→ 四桶各自的注册序处理器列表。 */
    private val buckets = HashMap<String, Array<MutableList<Entry>>>()

    /**
     * 已知事件名集合（归一后的只读快照）：非空时才校验 [register] 的事件名。
     *
     * 缺省空集 = 不校验（台架没有构建期 `EventRegistry`）；与生产 `EventDispatchRegistry.knownEvents`
     * 同位同口径，只整体替换、不清单条。
     */
    private var knownEvents: Set<String> = emptySet()

    /** mod 自定义事件域（A1，2026-11-26）：与生产 `EventDispatchRegistry.declaredEvents` 同位同口径。 */
    private val declaredEvents = LinkedHashSet<String>()

    /** 采纳已知事件名集合（逐个归一，见 [RecordingEvents.adoptKnownEvents]）。 */
    fun adoptKnownEvents(keys: Set<String>) {
        knownEvents = keys.mapTo(LinkedHashSet(keys.size)) { normalize(it) }
    }

    /**
     * 已知事件名快照（归一后，字典序稳定排序）：只读查询面用，改返回值不影响本表。
     *
     * 返回独立可变副本（与生产 `EventDispatchRegistry.knownEventKeys` 同口径）；用属性而非成员
     * 函数：本表成员函数数已抵 detekt TooManyFunctions 阈值（阈值 11，与生产
     * `EventDispatchRegistry.countsOf` 的「不为读数新增函数」同一口径）。
     */
    internal val knownEventKeysSnapshot: List<String>
        get() = knownEvents.sorted().toMutableList()

    /** 自定义事件域快照（归一后，字典序稳定排序）：只读查询面用，改返回值不影响本表。 */
    internal val declaredEventKeysSnapshot: List<String>
        get() = declaredEvents.sorted().toMutableList()

    /**
     * 存在性闸：注入过已知事件名后，注册拼错名当场拒绝（附最近候选）。
     *
     * 文案与生产 `EventDispatchRegistry.unknownEventMessage` 逐字一致 —— 对拍用例直接断言两串相等。
     */
    fun requireKnown(eventKey: String, packageId: String) {
        val known = knownEvents
        val key = normalize(eventKey)
        require(known.isEmpty() || key in known || key in declaredEvents) {
            unknownEventMessage(packageId, eventKey, key, known)
        }
    }

    /**
     * 声明自定义事件话题（与生产 `EventDispatchRegistry.declare` 逐字同口径：幂等；
     * 与已知语料事件相撞 IAE）。
     */
    fun declare(eventKey: String, packageId: String) {
        val known = knownEvents
        val key = normalize(eventKey)
        require(known.isEmpty() || key !in known) {
            "事件 \"$eventKey\"（来自 $packageId）是语料已有事件，不能声明为自定义事件：" +
                "直接 events.register 搭进它的四桶链即可（自定义事件域与语料事件命名空间分离）"
        }
        declaredEvents.add(key)
    }

    fun register(eventKey: String, bucket: EventBucket, packageId: String, handler: EventHandler): Registration {
        val entry = Entry(packageId, handler)
        buckets.getOrPut(eventKey) { newBucketLists() }[bucket.ordinal] += entry
        return object : Registration {
            override fun unregister() {
                buckets[eventKey]?.get(bucket.ordinal)?.remove(entry)
            }
        }
    }

    fun fire(eventKey: String, bucket: EventBucket): List<EventResult> {
        val results = mutableListOf<EventResult>()
        for (entry in entriesOf(eventKey, bucket)) {
            // 与生产 `ModEventWiring.runBucket` 同口径：处理器抛任何 Throwable 都只隔离这一个，
            // 事件链是游戏主相位，决不能因 mod 崩掉；隔离后按 Continue 继续本桶后续处理器。
            val result = runCatching { entry.handler.onEvent(EventContextImpl(eventKey, entry.packageId)) }
                .getOrElse { t ->
                    warnSink(
                        entry.packageId,
                        "事件处理器异常已隔离 key=$eventKey bucket=${bucket.ordinal} " +
                            "cause=${t::class.java.simpleName}: ${t.message}",
                    )
                    EventResult.CONTINUE
                }
            results += result
            if (result == EventResult.SINGLE) break
        }
        return results
    }

    fun fireAll(eventKey: String): List<EventResult> {
        val results = mutableListOf<EventResult>()
        for (bucket in BUCKET_ORDER) results += fire(eventKey, bucket)
        return results
    }

    fun registrations(): List<RecordingEvents.Recorded> {
        val out = mutableListOf<RecordingEvents.Recorded>()
        for ((eventKey, bucketLists) in buckets) {
            for (bucket in BUCKET_ORDER) {
                for (entry in bucketLists[bucket.ordinal]) {
                    out += RecordingEvents.Recorded(eventKey, bucket, entry.packageId)
                }
            }
        }
        return out
    }

    fun packagesFor(eventKey: String): List<String> {
        val owners = LinkedHashSet<String>()
        for (bucket in BUCKET_ORDER) {
            for (entry in entriesOf(eventKey, bucket)) owners += entry.packageId
        }
        return owners.toList()
    }

    fun handlerCount(eventKey: String): Int = BUCKET_ORDER.sumOf { entriesOf(eventKey, it).size }

    private fun entriesOf(eventKey: String, bucket: EventBucket): List<Entry> =
        buckets[eventKey]?.get(bucket.ordinal)?.toList().orEmpty()

    private fun newBucketLists(): Array<MutableList<Entry>> = Array(BUCKET_ORDER.size) { ArrayList() }

    private class EventContextImpl(
        override val eventKey: String,
        override val packageId: String,
    ) : EventContext

    private companion object {
        val BUCKET_ORDER: List<EventBucket> =
            listOf(EventBucket.ONLY, EventBucket.PRI, EventBucket.NORMAL, EventBucket.LATER)
    }
}

/** 未知名拒绝消息里最多给几个候选（与生产 `NEAREST_EVENT_NAME_LIMIT` 同值）。 */
private const val NEAREST_EVENT_NAME_LIMIT = 3

/**
 * 未知事件名的拒绝消息：含**原始**事件名（作者按它 grep 自己的注册点）、归属包与最接近候选。
 *
 * 与生产 `EventDispatchRegistry.kt` 的 `unknownEventMessage` 逐字一致（原始名而非归一后的 key：
 * 作者手里写的就是原始字面量，消息里的名字要能直接对回源码）。
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
