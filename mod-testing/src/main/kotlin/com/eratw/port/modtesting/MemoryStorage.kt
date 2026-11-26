package com.eratw.port.modtesting

import com.eratw.port.modapi.StorageApi

/**
 * 内存存储实现：缺键 `get` → `null`、`remove` → `false`（与生产 `StorageApi` 契约一致）。
 *
 * 键按原样大小写保存（生产侧 storage 键是 mod 自己管理的字符串命名空间，不参与引擎大写归一）。
 */
internal class MemoryStorage : StorageApi {

    private val values = LinkedHashMap<String, String>()

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun get(key: String): String? = values[key]

    override fun remove(key: String): Boolean = values.remove(key) != null

    /** 独立快照：调用方改返回集合不会反向改台架状态。 */
    override fun keys(): Set<String> = values.keys.toSet()
}
