package com.eratw.port.core.mod

import com.eratw.port.modapi.EventBucket

/**
 * 一个已装载 mod 的注册面读数（只读诊断面）：回答作者最常问的「我的 mod 为什么没反应」——
 * hook 绑上几条、事件处理器落进哪个桶、交互阶段钩子 / 服务 / 文案 / 设置项 / 图片各登记了几个。
 *
 * 只收录**真能从活注册表现数**的面；数不到的（例如待派发队列、宿主侧渲染结果）不进本快照，
 * 避免编造 0 让作者误判「注册成功但没触发」。本类不持有第二份状态，也不做缓存，
 * 每次 [ModRuntimeHost.registeredFacesOf] 现读活对象后新建一份小快照。
 */
data class RegisteredFaces(
    /** 当前真正绑定在槽上的 hook 处理器数（`bind` 成功且未撤销的条数；槽声明数不计入）。 */
    val hooks: Int = 0,
    /** `continueOnHandlerFailure=false` 的槽在处理器异常后退槽的条数（重新绑定会移出）。 */
    val retiredHooks: Int = 0,
    /**
     * 四桶事件处理器数，键固定为 [EVENT_BUCKET_ORDER]（派发序 PRI/NORMAL/LATER/ONLY）。
     *
     * ONLY 桶运行期恒为 0（[EventDispatchRegistry.register] 当场拒绝）：保留该键是为了让
     * 「我写 ALL 还是 ONLY」这类常见疑问在读数里一眼可见，而不是报错之后才去翻文档。
     */
    val events: Map<String, Int> = ZERO_EVENTS,
    /** 交互阶段钩子处理器数（`interactions.registerHook` 成功且未退订的条数，跨全部阶段）。 */
    val interactions: Int = 0,
    /** 已发布服务条数（同类型重新发布 = 替换，不增条）。 */
    val services: Int = 0,
    /** 已声明文案键数。 */
    val strings: Int = 0,
    /** 已声明设置项数（宿主注入快照里的声明清单条数；未注入 / 未装载 / 已停用 = 0）。 */
    val settings: Int = 0,
    /** 本包经 `ui.registerImage` 登记且未退订的图片数（同资源名后声明覆盖 = 一条）。 */
    val images: Int = 0,
) {
    companion object {
        /** 四桶派发序（与 [EventBucket] 的运行序一致，UI 定序渲染不再自己排）。 */
        val EVENT_BUCKET_ORDER: List<String> = listOf("PRI", "NORMAL", "LATER", "ONLY")

        private val ZERO_EVENTS: Map<String, Int> = EVENT_BUCKET_ORDER.associateWith { 0 }

        /** 全零读数：未知 / 未装载的包、读数降级、测试夹具缺省。 */
        val EMPTY: RegisteredFaces = RegisteredFaces(events = ZERO_EVENTS)
    }
}

/**
 * 只读注册面读数：按 [packageId] 从活注册表现数，回答「我的 mod 到底注册上了什么」。
 *
 * 数据源逐项（口径见 [RegisteredFaces] 各字段 KDoc）：
 * - [RegisteredFaces.hooks] / [RegisteredFaces.retiredHooks] ← [ModRuntimeHost.hookPipeline]；
 * - [RegisteredFaces.events] ← [ModRuntimeHost.eventHandlers] 的四桶；
 * - [RegisteredFaces.interactions] ← [ModRuntimeHost.interactionPipeline]；
 * - [RegisteredFaces.services] ← [ModRuntimeHost.services]；
 * - [RegisteredFaces.strings] ← [ModRuntimeHost.strings]；
 * - [RegisteredFaces.settings] ← 宿主注入的 [ModRuntimeHost.settings] 声明清单**按条目生命周期出闸**
 *   （未装载 / 装配失败 / 已停用 = 0，见 [ModRuntimeHost.isLivePackage]）；
 * - [RegisteredFaces.images] ← 本包装配出的 [ModContextImpl.uiRegistry]。
 *
 * 约定：**绝不抛** —— 查询路径上任何异常都降级为 [RegisteredFaces.EMPTY]，并照装载日志口径
 * WARN 一条；未知 / 未装载 / 已停用的 packageId 天然全零（停用卸载后各面注册表已被撤销）。
 * 只读：不改注册面、无定时器、无缓存、不构造全量快照（只做计数遍历）。
 */
fun ModRuntimeHost.registeredFacesOf(packageId: String): RegisteredFaces {
    if (packageId.isBlank()) return RegisteredFaces.EMPTY
    return runCatching { facesOf(this, packageId) }.getOrElse { error ->
        readoutWarningSink(
            "mod 注册面读数失败（packageId=$packageId）：${error.javaClass.simpleName}: ${error.message}",
        )
        RegisteredFaces.EMPTY
    }
}

/** [registeredFacesOf] 的直读路径（异常由调用方统一降级，WARN 只留一处出口）。 */
private fun facesOf(host: ModRuntimeHost, packageId: String): RegisteredFaces {
    val hookCounts = host.hookPipeline.bindingCountsOf(packageId)
    val context = host.installedContexts.firstOrNull { it.packageId == packageId }
    return RegisteredFaces(
        hooks = hookCounts[0],
        retiredHooks = hookCounts[1],
        events = eventBucketsOf(host.eventHandlers.countsOf(packageId)),
        interactions = host.interactionPipeline.handlerCountOf(packageId),
        services = host.services.publishedCountOf(packageId),
        strings = host.strings.sizeOf(packageId),
        settings = if (host.isLivePackage(packageId)) {
            host.settings[packageId]?.declarations?.size ?: 0
        } else {
            0
        },
        images = context?.uiRegistry?.registeredImageCount() ?: 0,
    )
}

/** 四桶计数 → 定序读数表（ONLY 桶恒 0，理由见 [RegisteredFaces.events]）。 */
private fun eventBucketsOf(counts: IntArray): Map<String, Int> = mapOf(
    "PRI" to counts[EventBucket.PRI.ordinal],
    "NORMAL" to counts[EventBucket.NORMAL.ordinal],
    "LATER" to counts[EventBucket.LATER.ordinal],
    "ONLY" to 0,
)
