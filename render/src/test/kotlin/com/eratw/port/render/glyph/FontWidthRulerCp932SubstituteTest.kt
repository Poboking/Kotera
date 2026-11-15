package com.eratw.port.render.glyph

import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.Cp932Encodable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「地灵殿地图碎裂」修复的定向闸：替身正文族的 CP932 已知码位按静态网格量宽。
 *
 * 病灶：`COLOREDMAPS/DRAW_COLOREDMAP.ERB:125` 用 `<font face='ＭＳ ゴシック'>` 逐行
 * HTML_PRINT 地图字符画（`COLOREDMAPS/COLOREDMAP_09.ERB` 的 CHIREIDEN_1），画布按 PC
 * 原版 ＭＳ ゴシック（CP932 系统字体，`StringMeasure.cs:42-72` 用段字体本尊量宽）的全角
 * 口径排布 —— JIS 制表符 ━┃┼、希腊 Ψψ、数学 ∵∥、罗马数字 Ⅲ 一律 2 格。2026-11-08 起
 * 真机宽度走 sidecar 真实 advance，Sarasa Mono SC（ＭＳ ゴシック 的随包替身）把这一批
 * 设计成半角 500：CHIREIDEN_1 row07 前段全行 68 格只剩 45 格，且各行压缩量随行内记号数
 * 浮动 ⇒ 边框竖带碎成几条错位带（0.2.43 正常，之后推送的包碎裂 —— 桌面 headless 缺表
 * 退化到静态网格所以单测一直绿，典型「桌面绿真机炸」）。
 *
 * 裁决规则见 [FontWidthRuler.compute] 内注与 [Cp932Encodable] KDoc；读数手算自
 * sarasa-mono-sc-advance.txt（upem 1000）× `CellMetrics.DEFAULT`（字号 18，半格 9.36px）：
 * 500 → 0.9615 格吸附 1，1000 → 1.923 吸附 2，0（组合记号）→ 0。
 */
class FontWidthRulerCp932SubstituteTest {

    private val ruler = FontWidthRuler()

    /** 地图画布的受害字符：替身正文族下必须回到静态全角 2 格（sidecar 给 1）。 */
    @Test
    fun `地灵殿地图的制表符希腊数学罗马数字回到全角`() {
        // 全部在 sarasa-mono-sc-advance.txt 里是 500/1000（半角），而在 CP932 表内是全角档。
        val victims = intArrayOf(
            0x2501, 0x2503, 0x250C, 0x2510, 0x2518, 0x2523, 0x2533, 0x253C, // ━ ┃ ┌ ┐ ┘ ┣ ┳ ┼
            0x03A8, 0x03C8, 0x03C1, 0x039E, // Ψ ψ ρ Ξ（希腊）
            0x2234, 0x2235, 0x2225, 0x222B, 0x2261, // ∴ ∵ ∥ ∫ ≡（数学）
            0x2161, 0x2162, // Ⅱ Ⅲ（罗马数字）
            0x043E, 0x043F, // о п（西里尔）
            0x2640, // ♀（CHIREIDEN_2 的女性记号）
        )
        for (cp in victims) {
            assertTrue(Cp932Encodable.encodable(cp), "U+${cp.toString(16)} 应在 CP932 表内")
            assertEquals(
                2f, ruler.advanceCells(cp, "ＭＳ ゴシック"),
                "U+${cp.toString(16)} 地图画布按原版字体是全角 2 格",
            )
        }
    }

    /** 整行不变量：画布行宽必须与静态 CP932 口径（[CellWidth.displayCells]）同值。 */
    @Test
    fun `地图画布行宽与静态口径同值`() {
        // CHIREIDEN_1 row07 前段（去零宽空格）：一阶画布，静态口径 68 格。
        val row07 = "Ψ□Ψ∵∵■┌―┘┼┼■┼┼┼┼13┼┼┼┼┼■┼■阿燐■∵∵Ψ□Ψ∥"
        assertEquals(68, ruler.widthRoundedCells(row07, "ＭＳ ゴシック"), "PC 原版口径 68 格")
        assertEquals(
            CellWidth.displayCells(row07),
            ruler.widthRoundedCells(row07, "ＭＳ ゴシック"),
            "整行宽必须与静态 CP932 口径同值（sidecar 口径只剩 45 格）",
        )
    }

    /** 「阿燐」在 row07 的起点列：sidecar 口径漂到 32 格，正确口径 50 格。 */
    @Test
    fun `阿燐在row07的起点列与原版一致`() {
        val prefix = "Ψ□Ψ∵∵■┌―┘┼┼■┼┼┼┼13┼┼┼┼┼■┼■"
        assertEquals(50, ruler.widthRoundedCells(prefix, "ＭＳ ゴシック"))
    }

    /** 表外字保 sidecar：老虎机块元素半角 1 格不回归（2026-11-08 修复的正确读数）。 */
    @Test
    fun `块元素仍按随包字体真实半角`() {
        for (cp in intArrayOf(0x2581, 0x2582, 0x2589)) {
            assertEquals(1f, ruler.advanceCells(cp, "MS Gothic"), "U+${cp.toString(16)} 表外字走 sidecar")
        }
    }

    /** 组合记号零推进不回归：被静态网格接管成 2 格的话，附点音符会把正文推开 2 格。 */
    @Test
    fun `组合记号保持零推进`() {
        assertEquals(0f, ruler.advanceCells(0x0301, "ＭＳ ゴシック"))
    }

    /** Saitamaar 豁免：PC 原版对它量的是字体本尊，比例 advance 不许被静态网格吃掉。 */
    @Test
    fun `Saitamaar的比例advance不受裁决影响`() {
        assertTrue(ruler.advanceCells(0x3A, "Saitamaar") in 0.35f..0.37f, "冒号真实 0.36 格")
    }

    /** 缺表装置退化路径逐值不变（headless / 黄金基准口径；与 [FontWidthRulerTest] 同值）。 */
    @Test
    fun `缺表退化读数不变`() {
        val degraded = FontWidthRuler(metrics = TextMetrics.gridOnly())
        assertEquals(2f, degraded.advanceCells(0x2503, "ＭＳ ゴシック"))
        // 表外字在缺表装置上没有 sidecar 可问，落静态网格 2 格（表外字⇒全角的既有口径）。
        assertEquals(2f, degraded.advanceCells(0x2589, "MS Gothic"))
    }
}
