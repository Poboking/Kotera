package com.eratw.port.core.mod

/**
 * 每包最近日志环的容量：默认记最近 50 行（MODDX2 二期 G2）。
 *
 * 记账口径：环只记 **PackageLogger.emit 已实际发出** 的行 —— 普通正文行、按单条上限截断后的
 * 内容、以及只发一次的洪水抑制元告警行。三类不进环的行：
 * 1. 空白消息（emit 里已丢弃，环与 sink 同拍，不提前记账）；
 * 2. 超出每 context 行数预算后被静默抑制的正文行（环不是第二本账，**不解除既有抑制语义**，
 *    只把「发出去的」留下最近一段；被抑制的行在 sink 上也不存在，回看时必须同形）；
 * 3. 不经 PackageLogger.emit 的宿主侧日志（如 ui 可见性谓词的 WARN，落点在 [InstanceUiRegistry]）。
 *
 * 50 行的量级：够覆盖一次报障前后的现场（作者回调几十行的日志段），又不至于在 `onFrame`
 * 每帧打行的最坏情形下无界增长。文件级 const 而非伴生对象：core 层源码形状闸拦伴生对象。
 */
internal const val MOD_LOG_BUFFER_CAPACITY: Int = 50

/**
 * 按包有界的最近日志环（MODDX2 二期 G2）：记 [MOD_LOG_BUFFER_CAPACITY] 行，超容逐出最旧一行。
 *
 * 为什么不用无界列表：作者在 `onFrame` 里打日志时每帧一行，长跑就是数十万行；环是诊断设备，
 * 只保留「报障现场」所需的最近一段。为什么加锁：写侧（VM 线程 emit，ADR-003 单线程）与读侧
 * （宿主诊断 / 设置页回看，可能在别的线程）不同源，照 `HostEventRing` 的实例锁口径收口；
 * 状态挂本实例，无静态可变全局（buffer 由 [ModContextImpl] 每个 context 各持一份）。
 *
 * 本类不判行内容、不去重、不截断 —— emit 侧已做完裁剪，环只做时序保持的容器（单一职责，
 * 记账口径见 [MOD_LOG_BUFFER_CAPACITY] 的 KDoc）。
 */
internal class ModLogBuffer(private val capacity: Int = MOD_LOG_BUFFER_CAPACITY) {

    init {
        require(capacity > 0) { "日志环容量必须为正: $capacity" }
    }

    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)

    /** 记一行已发出的日志；环满时逐出最旧一行（写入顺序 = 调用顺序，时序保持）。 */
    fun append(line: String) {
        synchronized(lock) {
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /**
     * 最近日志快照（旧到新、时序保持）：只读副本，调用方可随意持有，环后续的写入与逐出
     * 不改变已取到的快照。
     */
    fun recentLines(): List<String> = synchronized(lock) { ArrayList(lines) }
}
