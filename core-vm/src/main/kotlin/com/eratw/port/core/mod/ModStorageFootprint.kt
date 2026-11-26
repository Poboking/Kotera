package com.eratw.port.core.mod

/**
 * 单包存储段的诊断软阈值（键数）：越过只上屏一次 WARN，**不拒写**。
 *
 * 数值来源（本切片 task-185 契约裁定；MST1 格式侧没有这个上限）：
 * - 与 [MOD_STORAGE_WARN_BYTES] 描述同一个「典型预算」：MST1 一行 str 值的固定开销是 u16 键长 2 字节 +
 *   1 字节 wire tag + u16 值长 2 字节（[ModStorageSectionCodec.encode] 的写出顺序），故
 *   1024 键 ×（5 字节开销 + 键字节 + 值字节）≈ 1024 × 256 B = 256 KiB，两个阈值对齐同一档；
 * - 段自身固定开销只有 2 字节包名长 + 包名 + 4 字节条目数 + 4 字节 dataVersion，容器头 10 字节，
 *   相对键值行可忽略；所以 1024 键 ≈ 256 KiB 是同一档预算的两种切法，不是两个独立口径；
 * - 阈值是**可调诊断参数**：MST1 单段本身没有总大小上限（编码只按 u16 限单键/单值长度，见
 *   [ModStorageSectionCodec.encode]），调大调小都不改编码字节、不改读写语义，越过也照常写入。
 */
const val MOD_STORAGE_WARN_KEYS: Int = 1024

/**
 * 单包存储段的诊断软阈值（编码后字节）：口径 = [ModStorageSectionCodec.encode] 编一个段得到的字节数
 * （含 10 字节容器头与段尾 dataVersion）。数值推导与「为什么不是格式上限」见 [MOD_STORAGE_WARN_KEYS]。
 */
const val MOD_STORAGE_WARN_BYTES: Int = 256 * 1024

/**
 * 单包存储段的只读足迹读数（诊断面，不参与写入路径的任何判定）。
 *
 * [encodedBytes] 与 [ModStorageStore.encode] 同源：单段经 [ModStorageSectionCodec.encode] 编出的字节数。
 * [keys] 含 ABSENT 墓碑位（墓碑占位且随档走，与 [ModStorageStore.sizeOf] 口径一致）。容器里没有该包的段
 * 时全 0 —— [ModStorageStore.release] 摘掉段之后就是这组读数。
 *
 * [overKeysLimit] / [overBytesLimit] 是**严格大于**阈值（等于阈值不算越限），判据单源在 [footprint]。
 */
data class ModStorageFootprint(
    val packageId: String,
    val keys: Int,
    val encodedBytes: Int,
    val overKeysLimit: Boolean,
    val overBytesLimit: Boolean,
)

/**
 * 读某包的存储足迹（只读，不建段、不改状态、不上屏）。
 *
 * 口径与 [ModStorageStore.encode] 取同一字节数：先按 [ModStorageStore.sectionsSnapshot] 的同样字段
 * （`orphan` 是派生元数据，不进字节）把该段拼成 [ModStorageSection]，再走
 * [ModStorageSectionCodec.encode] 编码单段。因此本读数**不会**因为编码实现变化而与真实落盘字节漂移；
 * 也正因为走同一份编码器，它不适合放在每次写入的热路径上反复调用（见 [noteStorageFootprint] 的调用面）。
 *
 * 未激活但仍有数据的段（被禁用的 mod）同样可读：字节照旧随档走，孤儿段语义不变。
 */
fun ModStorageStore.footprint(packageId: String): ModStorageFootprint {
    val values = sections[packageId]
        ?: return ModStorageFootprint(
            packageId,
            keys = 0,
            encodedBytes = 0,
            overKeysLimit = false,
            overBytesLimit = false,
        )
    val encodedBytes = ModStorageSectionCodec.encode(
        listOf(
            ModStorageSection(
                packageId,
                LinkedHashMap(values),
                orphan = packageId !in activePackages,
                dataVersion = dataVersionsByPackage[packageId] ?: 0,
            ),
        ),
    ).size
    return ModStorageFootprint(
        packageId = packageId,
        keys = values.size,
        encodedBytes = encodedBytes,
        overKeysLimit = values.size > MOD_STORAGE_WARN_KEYS,
        overBytesLimit = encodedBytes > MOD_STORAGE_WARN_BYTES,
    )
}

/**
 * 写入路径的阈值会诊入口：越限且本包本会话还没告警过时，经 [ModStorageStore.footprintWarningSink]
 * 上屏一条 WARN（每包每会话一次）。
 *
 * 语义边界（故意收窄，越出任何一条都算改写入语义）：
 * - **只读旁路**：本函数不写段、不拒写、不改写入顺序 —— 调用点在写入完成之后，返回值丢弃；
 *   「不抛」由写侧入档闸保证（M2-BUG-04：put / putReservedKey 落写前过
 *   `ModStorageSectionCodec.requireEncodableText` ⇒ 段按构造可编码，[footprint] 的 encode
 *   不可能因段内容抛 IAE）；
 * - 判据单源 = [footprint] 的两个 `over…` 位（严格大于阈值）；
 * - 去重状态 = [ModStorageStore.warnedFootprintPackages]（每包一次，跨 release 保留：release 只摘数据，
 *   不重置「本会话已提醒过」的判断）；sink 未绑定时不标记、不抛，等绑定后下一次写再判；
 * - 性能：未告警包每次写入会多编码一遍自己的段。c 上限是固定的：键数越限前至多写 1024 次、字节越限
 *   前至多写若干次，首次越限即告警，之后同包写入直接命中去重位、零编码。
 */
internal fun ModStorageStore.noteStorageFootprint(packageId: String) {
    if (packageId in warnedFootprintPackages) return
    val readout = footprint(packageId)
    val sink = footprintWarningSink
    if ((readout.overKeysLimit || readout.overBytesLimit) && sink != null) {
        warnedFootprintPackages += packageId
        sink(
            "[$packageId][WARN] mod.storage 单包存储超阈值：" +
                "keys=${readout.keys}（阈值 $MOD_STORAGE_WARN_KEYS），" +
                "encodedBytes=${readout.encodedBytes}（阈值 $MOD_STORAGE_WARN_BYTES）；" +
                "数据照常写入，仅提示自查写入量。",
        )
    }
}

/**
 * 把阈值 WARN 接到既有诊断通道（生产 = [ModRuntimeHost] 构造时绑上它的 `logSink`，与 `mod.assetmap` /
 * `mod.resources` 的 WARN 同一出口；本切片不另造诊断总线，也不往 `store.aux` 加槽）。
 *
 * core-vm 内可见（host 与同模块测试用）；app 侧无需可改：接线由 host 在构造期完成。
 */
internal fun ModStorageStore.bindStorageFootprintWarningSink(sink: (String) -> Unit) {
    footprintWarningSink = sink
}
