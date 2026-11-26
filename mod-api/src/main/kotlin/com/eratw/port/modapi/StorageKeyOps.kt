package com.eratw.port.modapi

// ---- 存储 / 任务 / 全局变量的类型化键快捷面 ----

/** 按类型化键读存储条目。 */
fun StorageApi.get(key: StorageKey): String? = get(key.name)

/** 按类型化键写存储条目。 */
fun StorageApi.put(key: StorageKey, value: String) = put(key.name, value)

/** 按类型化键删存储条目。 */
fun StorageApi.remove(key: StorageKey): Boolean = remove(key.name)

/** 按类型化键注册周期任务处理器。 */
fun JobApi.bind(key: JobKey, handler: JobHandler): Registration = bind(key.name, handler)

/** 按类型化键读全局整型变量。 */
fun GameStateApi.getIntVar(key: GlobalKey): Long = getIntVar(key.name)

/** 按类型化键读全局字符串变量。 */
fun GameStateApi.getStrVar(key: GlobalKey): String = getStrVar(key.name)
