package com.eratw.port.modapi

/**
 * 跨 mod Kotlin 服务注册面（RT2 P1 工程契约；会话级一份，全链共享）。
 *
 * 用途：mod 之间**按接口类型**交换纯 Kotlin 对象（数据源、策略、缓存等），不经过 ERB 函数面、
 * 不做序列化。发布方只暴露 [T] 这个接口，消费方 `find` 到它后直接调用。
 *
 * 调用形状：
 * ```
 * // 发布方 onLoad：把实现挂到接口类型上，owner 传自己的 context.packageId
 * context.services.publish(MyDataSource::class.java, myImpl, context.packageId)
 * // 消费方 onLoad（Manifest 里 loadAfter 声明后装）：找不到就是 null，不等待
 * val source = context.services.find(MyDataSource::class.java)
 * ```
 *
 * ## 契约边界（四条，都是刻意的失败可见取舍）
 *
 * 1. **只在 VM 线程调用**：发布与查找同线程同步完成，实现不加锁；后台线程要读服务，先经
 *    [RuntimeScheduler.submit] 回到主执行线程。
 * 2. **不保证装载顺序**：依赖别的 mod 的服务时，必须经 Manifest 的 `loadAfter` 声明自己后装；
 *    发布晚于查找 ⇒ [find] 返回 `null`（不等待、不重试、不抛错）。
 * 3. **同类型只许一个发布方**：两个 mod 发布同一接口类型时，[find] 抛 [IllegalStateException]
 *    并点名双方 packageId —— 不静默取先注册的那个（那会让谁生效取决于装载顺序）。
 * 4. **卸载即撤销**：mod 卸载或安装失败回滚时，它发布的全部服务从注册表撤销，之后 [find]
 *    返回 `null`；已拿到的对象不回收，消费方不得跨对方生命周期长期持有服务引用。
 *
 * 装配状态见 [ModContext.services]：未装配（直接构造的 context、不装配的测试台架）时是
 * 显式 fail-closed 桩，四个入口一律抛 [UnsupportedOperationException]，不返回空注册表
 * （静默空表会让作者以为服务没发出去，也掩盖了装配错误）。
 */
interface ModServiceRegistry {
    /**
     * 发布本 mod 的 [service]，[type] 是消费方查找用的接口类型（通常是 [T] 的 `Class`），
     * [owner] 是发布方 packageId（宿主装配的适配器会校验它等于本包 packageId，冒名发布抛错）。
     *
     * 同包重复发布同类型 = **替换**（幂等重注册，例如读档恢复后重发）；旧对象不被回调、不被回收，
     * 只是从本类型的绑定上摘掉。
     */
    fun <T : Any> publish(type: Class<T>, service: T, owner: String)

    /** 按接口类型查找唯一发布方；无人发布返回 `null`，多包发布抛 [IllegalStateException]。 */
    fun <T : Any> find(type: Class<T>): T?

    /** 该类型的全部发布方 packageId（按发布序）；无人发布返回空表。 */
    fun <T : Any> owners(type: Class<T>): List<String>

    /** 已发布类型的全限定名集合（按首次发布序；宿主侧观测与测试用）。 */
    fun publishedTypes(): Set<String>
}
