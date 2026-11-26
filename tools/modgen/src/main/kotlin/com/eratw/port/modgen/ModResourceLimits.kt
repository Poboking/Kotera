package com.eratw.port.modgen

/**
 * 资源面上限的**构建期副本**。
 *
 * 真源在运行期：`core-vm/src/main/kotlin/com/eratw/port/core/mod/ModResourceStore.kt` 的
 * `MAX_RESOURCE_FILE_BYTES` / `MAX_RESOURCE_TOTAL_BYTES` / `ASSET_DIR_SUFFIX`（该常量对
 * `:tools:modgen` 不可见 —— modgen 不依赖运行期实现，避免方向闸倒过来）。
 *
 * **必须与 ModResourceStore 同值；改一处必须改两处。**
 * 漂移由 `ModCheckResourcesTest.runtimeLimitsStayInSyncWithStoreSource` 的文本哨兵兜底：
 * 它读 `ModResourceStore.kt` 源码文本比对数值，任何一边改了另一没跟上就红。
 */
internal object ModResourceLimits {

    /** 单文件字节上限（与运行期 `MAX_RESOURCE_FILE_BYTES` 同值）：4 MiB。 */
    const val MAX_RESOURCE_FILE_BYTES: Long = 4L * 1024L * 1024L

    /** 单包资源总量上限（与运行期 `MAX_RESOURCE_TOTAL_BYTES` 同值）：16 MiB。 */
    const val MAX_RESOURCE_TOTAL_BYTES: Long = 16L * 1024L * 1024L

    /** 包资源目录后缀（与运行期 `ASSET_DIR_SUFFIX` 同值）：`mods/rt2/<packageId>.assets/`。 */
    const val ASSET_DIR_SUFFIX: String = ".assets"
}
