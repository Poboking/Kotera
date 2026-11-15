package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `C-CONTENT-MARGIN@1`：画面左右留白居中（壳层圆屏适配）的解算闸（纯 JVM，不需要设备）。
 *
 * 语义（2026-11-14 用户裁定）：留白 = 给屏套上的**模拟边框**，只修正「物理内容宽 /
 * 可绘制宽」这两个**输入**——守护、min、居中、列数消费同一个修正后的输入，不另起第二把尺：
 *
 * 1. **关 = 零影响**：`contentMarginPx = 0` 与不传参逐值相同（I1 金标，读数与
 *    [ScreenLayoutWindowNarrowingRegressTest] 的满宽档一致）；
 * 2. **对称留白**：未接窗口宽 ⇒ 版面宽 = 物理内容宽 − 2×留白、偏移 = 留白（两侧黑边居中）；
 * 3. **守护看去留白宽**：留白把守护输入宽收窄，字号压到「去留白区域刚好铺下 136 列」
 *    ——列数守恒、地图不折行，这是留白档位存在的意义；
 * 4. **与窗口宽 min 合成**：窄窗居中于去留白区域（余 1 px 归左不变），宽窗被留白封顶；
 * 5. **不变式**：偏移 ≥ 留白、版面宽 + 2×偏移 ≤ 物理内容宽；
 * 6. **退化**：留白 ≥ 内容宽一半不崩（列数 ≥ 1）。
 *
 * 期望值全部按算式独立复算（半角格宽 = 字号/2×1.04，列数 = 可绘制宽 ÷ 格宽向下取整），
 * 不引用生产分支——两边一起漂用例恒绿的坑（同 [ScreenLayoutWindowLayoutTest] 纪律）。
 */
class ScreenLayoutContentMarginTest {

    /** 仓内既有算例（`ScreenLayoutWindowLayoutTest` / `ScreenLayoutWindowNarrowingRegressTest` 同一组读数）。 */
    private companion object {
        const val CONTENT_WIDTH_PX = 2400
        const val DRAWABLE_WIDTH_PX = 2395 // 物理内容宽扣掉 5px 形状预留（守护字号 33 的 shift）
        const val HEIGHT_PX = 1080
        const val DENSITY_DPI = 400
        const val SCRIPT_FONT_PX = 18
    }

    /** 与 `CanvasConsoleView.resolveWithInset` 同口径的入口（可绘制宽 = 物理内容宽 − shift）。 */
    private fun resolve(windowWidthPx: Int = 0, marginPx: Int = 0): ScreenLayout =
        ScreenLayout.resolve(
            widthPx = DRAWABLE_WIDTH_PX,
            heightPx = HEIGHT_PX,
            densityDpi = DENSITY_DPI,
            configFontPx = SCRIPT_FONT_PX,
            contentWidthPx = CONTENT_WIDTH_PX,
            windowWidthPx = windowWidthPx,
            contentMarginPx = marginPx,
        )

    /** 列数算式独立复算：`可绘制宽 ÷ (字号/2 × 1.04)` 向下取整（同 `ScreenLayout.columnsFor`）。 */
    private fun columnsFor(widthPx: Int, fontPx: Int): Int =
        (widthPx / (fontPx / 2f * 1.04f)).toInt().coerceAtLeast(1)

    // ------------------------------------------------------------------
    // 1) 关 = 零影响（金标）
    // ------------------------------------------------------------------

    @Test
    fun `留白为0时全部读数与不传参逐值相同`() {
        val off = resolve(marginPx = 0)
        val defaultArg = resolve()
        assertEquals(defaultArg.fontPixelSize, off.fontPixelSize)
        assertEquals(defaultArg.lineHeight, off.lineHeight)
        assertEquals(defaultArg.columns, off.columns)
        assertEquals(defaultArg.rows, off.rows)
        assertEquals(defaultArg.layoutWidthPx, off.layoutWidthPx)
        assertEquals(defaultArg.contentInsetLeftPx, off.contentInsetLeftPx)
        // 且与仓内既有满宽金标逐值一致（守护字号 33、满宽 139 列、无偏移）。
        assertEquals(2400, off.layoutWidthPx, "未开留白 ⇒ 版面宽 = 物理内容宽")
        assertEquals(0, off.contentInsetLeftPx, "未开留白 ⇒ 无偏移")
        assertEquals(33, off.fontPixelSize, "守护在物理宽 2395px 上压出的字号（既有金标）")
        assertEquals(139, off.columns, "满宽列数（既有金标）")
    }

    // ------------------------------------------------------------------
    // 2) 对称留白：版面收窄到去留白宽、偏移 = 留白
    // ------------------------------------------------------------------

    @Test
    fun `未接窗口宽时版面收窄到去留白宽且偏移等于留白`() {
        val margin = 60
        val layout = resolve(marginPx = margin)
        assertEquals(CONTENT_WIDTH_PX - 2 * margin, layout.layoutWidthPx, "版面宽 = 物理内容宽 − 2×留白")
        assertEquals(margin, layout.contentInsetLeftPx, "偏移 = 留白（两侧黑边对称、内容居中）")
        // 守护随输入宽收窄把字号压到 32：capFont = floor(2275 / (136×0.52)) = 32。
        assertEquals(32, layout.fontPixelSize, "守护看去留白宽，字号让步")
        // 列数 = 去留白可绘制宽 ÷ 格宽（独立复算）：(2280−5) ÷ 16.64 = 136。
        assertEquals(columnsFor(DRAWABLE_WIDTH_PX - 2 * margin, 32), layout.columns)
        assertEquals(136, layout.columns, "守护保住 136 列——地图不折行")
    }

    // ------------------------------------------------------------------
    // 3) 守护看去留白宽：字号让步、列数守恒
    // ------------------------------------------------------------------

    @Test
    fun `留白收窄守护输入宽时字号压小但列数不低于守护下限`() {
        val margin = 240 // 每侧 10%（2400px 内容宽）
        val layout = resolve(marginPx = margin)
        assertEquals(27, layout.fontPixelSize, "capFont = floor(1915 / (136×0.52)) = 27")
        assertEquals(
            columnsFor(DRAWABLE_WIDTH_PX - 2 * margin, 27),
            layout.columns,
            "列数按去留白可绘制宽独立复算",
        )
        assertTrue(layout.columns >= ScreenLayout.DEFAULT_COLUMNS, "守护下限 136 不因留白跌破")
        assertEquals(1920, layout.layoutWidthPx)
        assertEquals(margin, layout.contentInsetLeftPx)
        // 反向对拍：同一输入关掉守护（minColumns=0）字号不被压——让步来自守护而非留白本身。
        val unguarded = ScreenLayout.resolve(
            widthPx = DRAWABLE_WIDTH_PX,
            heightPx = HEIGHT_PX,
            densityDpi = DENSITY_DPI,
            configFontPx = SCRIPT_FONT_PX,
            contentWidthPx = CONTENT_WIDTH_PX,
            contentMarginPx = margin,
            minColumns = 0,
        )
        assertEquals(34, unguarded.fontPixelSize, "无守护 ⇒ 配置字号原样（18×2.5×0.75 = 33.75 → 34）")
        assertTrue(unguarded.columns < layout.columns, "无守护 ⇒ 列数随留白变少（玩家自己关的守护）")
    }

    // ------------------------------------------------------------------
    // 4) 与窗口宽 min 合成
    // ------------------------------------------------------------------

    @Test
    fun `窄窗口居中于去留白区域且整数除余1归左`() {
        val margin = 240
        val available = CONTENT_WIDTH_PX - 2 * margin // 1920
        val narrow = resolve(windowWidthPx = 1500, marginPx = margin)
        assertEquals(1500, narrow.layoutWidthPx, "窗口宽窄于去留白区 ⇒ min 取窗口宽")
        assertEquals(margin + (available - 1500) / 2, narrow.contentInsetLeftPx, "居中于去留白区域")
        assertEquals(450, narrow.contentInsetLeftPx)
        // 余 1 归左：窗口宽 1501 ⇒ 去留白区 − 窗口宽 = 419，整除得 209，右沿贴 contentWidth−1。
        val odd = resolve(windowWidthPx = 1501, marginPx = margin)
        assertEquals(1501, odd.layoutWidthPx)
        assertEquals(449, odd.contentInsetLeftPx, "419 / 2 整数除 = 209，+240 = 449（余 1 归左）")
        assertEquals(CONTENT_WIDTH_PX - 1, odd.layoutWidthPx + 2 * odd.contentInsetLeftPx)
        // 窗口宽窄化不改字号（守护只吃物理宽与留白，不吃窗口宽——与既有口径一致）。
        assertEquals(27, odd.fontPixelSize)
    }

    @Test
    fun `宽窗口被留白封顶与未接窗口宽同读数`() {
        val margin = 240
        val wide = resolve(windowWidthPx = 5000, marginPx = margin)
        val unwired = resolve(marginPx = margin)
        assertEquals(unwired.layoutWidthPx, wide.layoutWidthPx, "min(5000, 1920) = 1920 = 去留白宽")
        assertEquals(unwired.contentInsetLeftPx, wide.contentInsetLeftPx)
        assertEquals(unwired.fontPixelSize, wide.fontPixelSize)
        assertEquals(unwired.columns, wide.columns)
    }

    // ------------------------------------------------------------------
    // 5) 不变式扫掠 + 退化
    // ------------------------------------------------------------------

    @Test
    fun `扫掠任意留白与窗口宽组合不变式恒成立`() {
        for (margin in intArrayOf(0, 1, 37, 60, 120, 240, 480, 900)) {
            for (window in intArrayOf(0, 1, 1200, 1920, 2400, 5000)) {
                val layout = resolve(windowWidthPx = window, marginPx = margin)
                assertTrue(layout.contentInsetLeftPx >= margin, "偏移 ≥ 留白（m=$margin w=$window）")
                assertTrue(layout.layoutWidthPx >= 0, "版面宽非负（m=$margin w=$window）")
                assertTrue(
                    layout.layoutWidthPx + 2 * layout.contentInsetLeftPx <= CONTENT_WIDTH_PX,
                    "版面宽 + 2×偏移 ≤ 物理内容宽（m=$margin w=$window）",
                )
                assertTrue(layout.columns >= 1, "列数至少 1（m=$margin w=$window）")
                assertTrue(layout.rows >= 1, "行数至少 1（m=$margin w=$window）")
            }
        }
    }

    @Test
    fun `退化留白不小于内容宽一半时不崩且列数至少1`() {
        val degenerate = resolve(windowWidthPx = 0, marginPx = CONTENT_WIDTH_PX / 2)
        assertTrue(degenerate.columns >= 1)
        assertEquals(0, degenerate.layoutWidthPx, "去留白宽已被扣空 ⇒ 版面宽 0")
        // 生产侧 View 把比例夹在 0.5 内、偏好层白名单夹在 0.12，这里只钉解算点自身不炸。
        val extreme = resolve(windowWidthPx = 300, marginPx = CONTENT_WIDTH_PX)
        assertTrue(extreme.columns >= 1)
    }
}
