package com.eratw.port.core.mod

import com.eratw.port.modapi.ModUiElement

/**
 * 宿主只读查询：某包经 [com.eratw.port.modapi.UiApi.registerImage] 声明的图片元素（声明序快照）。
 *
 * 数据来源是 per-context 的实例级登记表（[ModContextImpl.uiRegistry]），不另存副本、不做缓存：
 * 撤销 / onDisable / 失败回滚走的是同一份 [com.eratw.port.modapi.Registration]，因此查询结果
 * 永远与登记表一致，不会出现「浮层上还挂着已撤销元素」的第二种真相。
 *
 * 为什么是文件级 extension 而不是 [ModRuntimeHost] 成员：[ModRuntimeHost] 与 [ModContextImpl]
 * 的成员函数数都抵 detekt TooManyFunctions 阈值，装配/查询细节按既有口径收在文件级
 * （同 `adoptPerModState`）。空集时上层直接拿到共享空表，不构造额外列表。
 *
 * 未安装的包返回空表、不抛错：查询面不制造状态；该包是否装载失败由宿主错误围栏另行记录。
 */
fun ModRuntimeHost.uiElementsOf(packageId: String): List<ModUiElement.Image> =
    installedContexts.firstOrNull { it.packageId == packageId }?.uiRegistry?.registeredImages().orEmpty()

/**
 * 宿主只读查询：某包当前**可见**的交互项键（声明序快照；DX-F1）。
 *
 * 可见性谓词在快照瞬间**现求值**：谓词返回 `false` 或抛异常（已按口径 WARN 降级）的项不出现在
 * 返回集里，点击面（`uiClick`）对它们同样不命中 —— 快照与命中共用同一份判定，不会出现
 * 「快照里有、点了没反应」的第二种真相。谓词缺省（`null`）= 恒可见，永远在内。
 *
 * 数据来源与一致性口径同 [uiElementsOf]：直接读 per-context 的实例级登记表，撤销 / onDisable /
 * 失败回滚走同一份 [com.eratw.port.modapi.Registration]，查询结果永远与登记表一致。
 * 未安装的包返回空表、不抛错（查询面不制造状态）。
 */
fun ModRuntimeHost.uiInteractionKeysOf(packageId: String): Set<String> =
    installedContexts.firstOrNull { it.packageId == packageId }?.uiRegistry?.registeredKeys().orEmpty()
