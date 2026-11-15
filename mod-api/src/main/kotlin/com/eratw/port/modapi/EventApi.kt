package com.eratw.port.modapi

/**
 * 事件能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3 / §6.1 贡献点）。
 *
 * mod 经本 API 注册事件处理器/游戏相位钩子，与 ERB 事件共享同一套四桶执行序
 * （ONLY → PRI → NORMAL → LATER，同桶链接序；映射引擎 `EventRegistry` 的桶语义）。
 *
 * ## 接线状态（诚实边界，K1 只冻结契约）
 *
 * 引擎今天的 `EventRegistry`（core/code）是**构建期不可变**、从 ERB 链接产物的 LSC 事件视图
 * 派生的——它承载的是 ERB 事件函数，**还没有**运行期注册 Kotlin 处理器的钩子缝。把 Kotlin
 * 处理器接进 §6.1 贡献点、与 ERB 事件按四桶序混排，是 **K3 脏活**（依赖 M2-6/M3-1 的贡献点机器）。
 * 本接口在 K1 冻结 mod 侧契约形状；生产适配器与执行序接线待 K3，不在本波凭空造第二套事件系统。
 *
 * clean 语义（§18.7）：mod 须守引擎事件序契约（§6.1），不替换 ERB dispatch；SINGLE 终止旗标
 * 只截断本桶余下成员、不跨桶（与引擎 `EventRegistry` 同口径）。
 */
interface EventApi {
    /**
     * 注册一个事件处理器。[eventKey] 按大写归一；[bucket] 决定执行桶位。
     * 返回的 [Registration] 可在 [EraMod.onDisable] 时撤销。
     */
    fun register(eventKey: String, bucket: EventBucket, handler: EventHandler): Registration
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