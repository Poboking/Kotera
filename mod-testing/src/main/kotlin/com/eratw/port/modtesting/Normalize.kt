package com.eratw.port.modtesting

import java.util.Locale

/**
 * 引擎侧统一的键归一：**去空白 + 大写**（字段名 / 原型名 / 关系类型 / 任务 id / 相位名 / 事件名 /
 * hook target 都按它寻址）。
 *
 * 引擎侧同口径的 canonical（同一份 `trim().uppercase(Locale.ROOT)`）：
 * `EventDispatchRegistry.canonical`（core/mod/EventDispatchRegistry.kt）、
 * `HookPipeline.canonical`（core/mod/HookPipeline.kt）、`GameScheduler.canonical`（任务/相位名）。
 * 台架必须同归一，否则作者在内存实现上过的用例会在真引擎上读回失败哨兵（或反之，作者在台上
 * 拼错的带空白键永远命不中）。
 */
internal fun normalize(name: String): String = name.trim().uppercase(Locale.ROOT)
