package com.eratw.port.core.mod

/**
 * 把**全链共享**的钩子 / 交互 / 事件 / 服务四个面绑进单个 mod 的 [ModContextImpl]
 * （任务槽同属共享面，另走 [ModContextImpl.adoptJobScheduler]：并进本函数会让参数数超阈值）。
 *
 * 必须在生命周期两段（[com.eratw.port.modapi.EraMod.onLoad] / `onEnable`）之前调：作者在这两段里
 * 注册的钩子/事件/服务必须落进共享实例，否则会静默退化。
 *
 * 为什么这五处必须共享同一份实例（漏一处就是静默语义错误，不是性能问题）：
 *
 * - 函数钩子：执行序是**全局** `priority`，各建一份会退化成「按 mod 分组」排序；
 * - 交互阶段钩子：执行序 = 阶段 → 声明链接序，各一份会把跨 mod 的 PRE/POST 序按包拆散；
 * - 任务槽/模板：执行序是**全局** priority，各建一份还会让先驱动包提前推进后续包任务态；
 * - 事件处理器：执行序 = 桶位 → 桶内注册序，各一份会打乱跨 mod 注册序；
 * - 跨 mod Kotlin 服务：查找方与发布方必须落在同一张表上才可能命中。
 *
 * 抽成文件级函数（而不是 [ModRuntimeHost] 的成员）是为了让 `install` 留在 detekt
 * `LongMethod` 阈值内 —— 该类的类内函数数与 `install` 的语句数都已在上限附近。
 */
internal fun adoptSharedFaces(
    context: ModContextImpl,
    hookPipeline: HookPipeline,
    interactionPipeline: InteractionPipeline,
    eventRegistry: EventDispatchRegistry,
    services: ModServiceRegistryImpl,
) {
    context.adoptHookPipeline(hookPipeline)
    context.adoptInteractionPipeline(interactionPipeline)
    context.adoptEventRegistry(eventRegistry)
    context.adoptServices(services)
}

/**
 * 宿主诊断读数缝（MODDX2 二期 G2）：本 context 最近发出的日志行快照（旧到新、时序保持、
 * 只读副本），底是 [ModContextImpl.logBuffer] 的每包有界环，记账口径见
 * [MOD_LOG_BUFFER_CAPACITY] —— 只记 emit 已发出的行（正文、截断后内容、抑制元告警），
 * 空白与被静默抑制的行不在其中。
 *
 * 为什么是文件级 extension 而不是成员函数：[ModContextImpl] 类内成员函数数已抵 detekt
 * TooManyFunctions 阈值（与 [adoptSharedFaces] 分流 install 的理由同源）。为什么 internal、
 * 不进 mod-api：这是宿主诊断面（真机报障时回看「mod 最近打了什么」），不是作者面 ——
 * 本波零作者面变更、零 VERSION 影响。
 */
internal fun ModContextImpl.recentLogLines(): List<String> = logBuffer.recentLines()

/**
 * 换用宿主提供的**全链共享**任务调度器（ABI-10）。
 *
 * 槽序的权威排序是 priority 升序 + 链接声明序，只有跨 mod 共享才成立；每包各持一份全链声明
 * 时，先驱动的包会把后续包槽位提前推进，后续包即使有处理器也不再到期。
 *
 * internal：只许宿主在 onLoad 之前调用，作者绑定必须落在这份共享调度器上。文件级而非成员函数：
 * [ModContextImpl] 类内函数数已抵 detekt TooManyFunctions 阈值（同 [recentLogLines] 分流口径）。
 */
internal fun ModContextImpl.adoptJobScheduler(shared: JobScheduler) {
    jobScheduler = shared
    jobAdapter = JobApiAdapter(shared, packageId)
}
