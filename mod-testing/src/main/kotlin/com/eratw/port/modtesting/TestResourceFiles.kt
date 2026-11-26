package com.eratw.port.modtesting

import com.eratw.port.core.mod.ModResourceStore
import java.io.File

/**
 * 台架放资源：落盘到引擎同一布局 `<resourceRoot>/mods/rt2/<packageId>.assets/<name>`。
 *
 * **名字/包名校验不在这里重写**（对拍纪律）：先经真 [ModResourceStore] 的 `apiFor(packageId)`
 * 与 `exists(name)` 走一遍引擎校验 —— 非法名抛的 `IllegalArgumentException` 与真引擎逐字同源；
 * 落盘前再用 canonical 路径复核目标在包目录内（第二道，防校验与拼接之间的意外）。
 *
 * [validator] 必须是**静默** store（[TestWorldState.resourceValidator]）：生产投放资源不经
 * `ModResourceApi`、投放本身不产 WARN，校验若复用作者可见 WARN 的那个 store，这次 scan 会把
 * 历史超限 / 坏文件的 WARN 提前灌进 `resourceWarnings` 并随投放次数重复。
 *
 * 存储无缓存：引擎 `keys/open` 每次现读盘，故写后即读得到，无需刷新索引。
 */
internal fun writeTestResource(
    resourceRoot: File,
    validator: ModResourceStore,
    packageId: String,
    name: String,
    bytes: ByteArray,
) {
    // 触发引擎校验：packageId 形态非法 / 资源名非法都会在这里抛（与生产同文案）。
    validator.apiFor(packageId).exists(name)
    val assetDir = File(File(resourceRoot, RT2_DIR), packageId + ModResourceStore.ASSET_DIR_SUFFIX)
    val target = File(assetDir, name).canonicalFile
    require(target.toPath().startsWith(assetDir.canonicalFile.toPath())) { "资源名越出包目录：[$name]" }
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
}
