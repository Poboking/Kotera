package com.eratw.port.core.host

/**
 * 长页忙碌指示器的**纯显示态**载荷（`EVENTTRAIN` 这类上百秒的日始长段用）。
 *
 * ## 它是什么、不是什么
 *
 * - 只是一份「宿主想让我在屏上显示忙碌」的瞬时读数：`elapsedMs` = 本页已经跑了多久。
 * - **不进任何缓冲**：不打印行、不改 `displayLines`、不碰 [LineBufferConsoleSink.revision] /
 *   `frameSnapshot()` / `publishedOptions()`；因此它与金标哈希链（E2EGolden / L3HashChain）
 *   以及语料自己的 `CLEARLINE` 完全无关。
 * - 谁读它：渲染层的 `ConsoleFeed.busyIndicator()` 与 `CanvasConsoleView` 的横带绘制；
 *   纯几何/形态层在 render-canvas 的 `BusyIndicatorBand`。两者都只画像素，不做语义。
 *
 * 线程口径：VM 线程写（宿主检查点）、UI 线程读（每帧绘制）。[LineBufferConsoleSink]
 * 用 `@Volatile` 单字段发布，只装不可变实例。
 *
 * @property elapsedMs 本页已消耗的墙钟毫秒（宿主用同一只单调时钟测量）
 */
data class BusyIndicator(val elapsedMs: Long) {

    companion object {
        /**
         * 指示器开始上屏的最小页时长（毫秒）。
         *
         * 这是**唯一**的阈值定义：`app` 侧推点（`RunTrail.BUSY_INDICATOR_MIN_MS` 是它的别名）
         * 与绘制闸（render-canvas 的 `BusyIndicatorBand`）同读这一个常量。
         * 1 s 以内的页本来就快到玩家察觉不到，不该闪一下横带。
         */
        const val MIN_ELAPSED_MS: Long = 1_000L
    }
}
