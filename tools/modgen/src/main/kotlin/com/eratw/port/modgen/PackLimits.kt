package com.eratw.port.modgen

import java.nio.file.Files

/**
 * `pack-mod` 侧对导入端不可信输入闸的**构建期副本**。
 *
 * 真源在 app：`app/src/main/kotlin/com/eratw/port/android/ModZipGuard.kt` 的
 * `MAX_ENTRIES:39` / `MAX_ENTRY_BYTES:42` / `MAX_TOTAL_BYTES:45` /
 * `MAX_COMPRESSION_RATIO:48` / `RATIO_MIN_BYTES:51`。那五个常量对 `:tools:modgen` 不可见
 * （构建工具不依赖 app，避免方向闸倒过来），故按 [ModResourceLimits] 的既有先例在构建期复制，
 * 用 [PackModLimitsSentinelTest] 读 `ModZipGuard.kt` 源码文本比对数值兜底：**任何一边改了
 * 另一没跟上就红**。
 *
 * 本对象同时是三道「不需要压缩体积」的判据的唯一落点（条目数 / 单条目 / 整包解压量）；
 * 压缩比要等成包后才知道 zip 字节数，由 [ratioUnsafe] 在 [PackModZip.build] 里补判。
 * 拒绝一律走 [PackUnsafe] 通道（[PackPlan.unsafe] → exit 1），消息必须点名条目/判据/
 * 实测值 vs 上限：作者看 stderr 就能改，不用猜。
 */
internal object PackLimits {

    /** 条目数上限（含目录项与所有被跳过的条目）：与 `ModZipGuard.MAX_ENTRIES` 同值。 */
    const val MAX_ENTRIES: Int = 512

    /** 单条目解压后字节上限：与 `ModZipGuard.MAX_ENTRY_BYTES` 同值（8 MiB）。 */
    const val MAX_ENTRY_BYTES: Long = 8L * 1024L * 1024L

    /** 整包累计解压字节上限：与 `ModZipGuard.MAX_TOTAL_BYTES` 同值（32 MiB）。 */
    const val MAX_TOTAL_BYTES: Long = 32L * 1024L * 1024L

    /** 压缩比上限：与 `ModZipGuard.MAX_COMPRESSION_RATIO` 同值；解压 >[RATIO_MIN_BYTES] 才判。 */
    const val MAX_COMPRESSION_RATIO: Long = 120L

    /** 压缩比闸的生效下限：与 `ModZipGuard.RATIO_MIN_BYTES` 同值（1 MiB）。 */
    const val RATIO_MIN_BYTES: Long = 1L * 1024L * 1024L

    /** 1 MiB 的字节数（文案里的 MiB 读数按它换算）。 */
    const val BYTES_PER_MIB: Long = 1024L * 1024L

    /** 批量判据（整包）在消息里的条目名：没有单个条目可点名时用它定位判据。 */
    private const val PACKAGE_LABEL = "整包"

    /** 条目数超限的改法。 */
    private val HINT_ENTRY_COUNT = "删掉或合并多余条目，把 zip 条目数降到 $MAX_ENTRIES 以内"

    /** 单条目超限的改法。 */
    private val HINT_ENTRY_SIZE =
        "拆分或删减该文件（导入端单条目解压上限 ${MAX_ENTRY_BYTES / BYTES_PER_MIB} MiB）"

    /** 整包解压量超限的改法。 */
    private val HINT_TOTAL =
        "删减资源或 ERB 源，把整包解压总量降到 ${MAX_TOTAL_BYTES / BYTES_PER_MIB} MiB 以内"

    /** 压缩比超限的改法。 */
    private const val HINT_RATIO = "去掉高倍可压缩的构造内容（大量重复/全零填充），或改用素材原文件"

    /**
     * 收集阶段的三道预算闸打包入口：条目数（含包元数据）/ 单条目 / 整包解压量。
     * 压缩比要等成包后的 zip 字节数，由 [ratioUnsafe] 在 [PackModZip.build] 补判。
     */
    fun budgetUnsafe(entries: List<PackEntry>, generated: List<GeneratedEntry>): List<PackUnsafe> {
        val found = mutableListOf<PackUnsafe>()
        entryCountUnsafe(entries, generated)?.let { found += it }
        entries.forEach { entry -> entrySizeUnsafe(entry.name, Files.size(entry.path))?.let { found += it } }
        generated.forEach { entry -> entrySizeUnsafe(entry.name, entry.bytes.size.toLong())?.let { found += it } }
        val totalBytes = entries.sumOf { Files.size(it.path) } + generated.sumOf { it.bytes.size.toLong() }
        totalBytesUnsafe(totalBytes)?.let { found += it }
        return found
    }

    /**
     * 条目数闸：guard 数的是**整包所有条目**，所以打包器自己写的包元数据条目
     * （[generated]，0 或 1 条）也必须计入账本，否则「512 个模块条目 + 1 条元数据」会被
     * 放行而导入端正好在第 513 条拒收。超限时点名**第一条放不进预算的模块条目**。
     */
    fun entryCountUnsafe(
        moduleEntries: List<PackEntry>,
        generated: List<GeneratedEntry>,
    ): PackUnsafe? {
        val total = moduleEntries.size + generated.size
        if (total <= MAX_ENTRIES) return null
        val firstOver = moduleEntries.getOrNull(MAX_ENTRIES - generated.size)
        return PackUnsafe(
            name = firstOver?.name ?: PACKAGE_LABEL,
            reason = "条目数 $total 超过导入端上限 $MAX_ENTRIES（含包元数据条目 ${generated.size} 条）",
            hint = HINT_ENTRY_COUNT,
        )
    }

    /**
     * 单条目闸：解压后字节数（= 磁盘源文件字节数，zip 解压可逐字节还原）超过
     * `ModZipGuard.MAX_ENTRY_BYTES` 即拒。边界判据与 guard 同口径：恰好等于上限**放行**。
     */
    fun entrySizeUnsafe(name: String, bytes: Long): PackUnsafe? {
        if (bytes <= MAX_ENTRY_BYTES) return null
        return PackUnsafe(
            name = name,
            reason = "解压后 $bytes 字节（${bytes / BYTES_PER_MIB} MiB）超过导入端单条目上限 " +
                "$MAX_ENTRY_BYTES 字节（${MAX_ENTRY_BYTES / BYTES_PER_MIB} MiB）",
            hint = HINT_ENTRY_SIZE,
        )
    }

    /**
     * 整包解压量闸：所有条目（含包元数据）解压字节之和超过 `ModZipGuard.MAX_TOTAL_BYTES`
     * 即拒。恰好等于上限放行（与 guard 的 `>` 同口径）。
     */
    fun totalBytesUnsafe(totalBytes: Long): PackUnsafe? {
        if (totalBytes <= MAX_TOTAL_BYTES) return null
        return PackUnsafe(
            name = PACKAGE_LABEL,
            reason = "整包解压合计 $totalBytes 字节（${totalBytes / BYTES_PER_MIB} MiB）超过导入端上限 " +
                "$MAX_TOTAL_BYTES 字节（${MAX_TOTAL_BYTES / BYTES_PER_MIB} MiB）",
            hint = HINT_TOTAL,
        )
    }

    /**
     * 压缩比闸：**成包后**才能判（分母是整份 zip 字节数）。判据逐字照抄
     * `ModZipGuard.Progress.overLimit`（`ModZipGuard.kt:128-133`）的形态：
     * `解压量 > RATIO_MIN_BYTES && 解压量 > MAX_COMPRESSION_RATIO * zip字节数`，
     * 用乘法而不是除法比较，避免整数除法在边界上与 guard 差一。小包（解压量不越过
     * [RATIO_MIN_BYTES]）压缩比天然虚高，不判——与 guard 一致，不误杀。
     */
    fun ratioUnsafe(totalBytes: Long, archiveBytes: Long): PackUnsafe? {
        val belowGate = totalBytes <= RATIO_MIN_BYTES
        val withinRatio = archiveBytes > 0L && totalBytes <= MAX_COMPRESSION_RATIO * archiveBytes
        if (belowGate || withinRatio) return null
        val ratio = if (archiveBytes > 0L) totalBytes / archiveBytes else Long.MAX_VALUE
        return PackUnsafe(
            name = PACKAGE_LABEL,
            reason = "解压/压缩 = $totalBytes/$archiveBytes 字节（$ratio 倍）超过导入端压缩比上限 " +
                "$MAX_COMPRESSION_RATIO 倍，疑似压缩炸弹（解压量已越过判据门槛 $RATIO_MIN_BYTES 字节）",
            hint = HINT_RATIO,
        )
    }
}
