package com.eratw.port.render.button

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * div 子行按钮的**像素命中表**与焦点/按下（CONTRACT-DIV-HIT2 B 路）。
 *
 * 断言的原版语义（相对 reference/emuera-cs/）：
 * - 命中几何 `ConsoleDivPart.TestChildHitbox`（CDP :134-172）：盒矩形宽用脚本 `width`
 *   （**不是**绘制侧 `width + 2`，CDP :140 对 :178）、矩形 y 是内容盒顶（:140 的
 *   `relPointY + PointY + yOffset`）、子行行条顶 = 内容盒顶 + 子行号 × 行高（:169）、
 *   子行左沿 = 内容盒左沿（:134 的 `ShiftChildrenX(PointX + xOffset + divXOffset)`）；
 * - 命中扫描优先级（`EmueraConsole.cs:2343` 的 `Array.Sort` + :2347-2424）：
 *   depth < 0（升序）→ 正文行（:2350-2396 的 depth 0 槽）→ depth == 0 → depth > 0（升序）；
 * - **B9 定案（判词 7「优先更好更优雅的一方」）**：原版那趟正文行扫描只在**首个非负 depth 键**
 *   处触发一次（1.824 `EmueraConsole.cs:2350` 的 `if (!flag3 && num7 >= 0)`、蛇版 4.981
 *   `Emuera/UI/Game/EmueraConsole.cs:2612` 的 `if (!zeroTested && depth >= 0)`），
 *   键表全负时整趟跳过 ⇒ 正文链接点不动。移植侧**不跟这个 bug**，正文行无条件参与扫描
 *   （`ButtonLayer.kt:836` / `:865-869`）⇒ 键全负也仍可点。锁在 ⑥ 节。
 * - div 子行按钮不进 `displayLineList` 各行的 Buttons（EmueraConsole.cs:2358-2361），
 *   所以格坐标命中表 [ButtonLayer.all] 不受影响。
 */
class ButtonLayerDivHitTest {

    private val metrics = CellMetrics(18, 18)

    private val planner = DivOverlayPlanner(metrics, 0, HtmlRunFlatten(metrics))

    private val canvasHeightPx = 10 * 18

    private val rowCount = 10

    private fun geometry(hitRowOffset: Int = 0) = ButtonLayer.DivHitGeometry(
        metrics = metrics,
        rowCount = rowCount,
        canvasHeightPx = canvasHeightPx,
        hitRowOffset = hitRowOffset,
    )

    /**
     * 块几何时位：relative 形下 `yPx` 进 `pointYPx`（块顶 = anchorTopPx + yPx，同时改登记行区间），
     * `xPx` 进 `xOffsetPx`（块左移）。合成一个形参而不是摊成两个，是为了压住 detekt 的
     * LongParameterList 阈值（本 fixture 已带 4 个默认值形参，闸门不改阈值、不加 Suppress）。
     */
    private data class At(val yPx: Int = 0, val xPx: Int = 0)

    private fun block(
        children: List<String>,
        widthPx: Int = 100,
        heightPx: Int = 30,
        depth: Int = 0,
        at: At = At(),
    ) = DisplayDivBlock(
        altHeadTag = "<div>",
        anchorCol = 0,
        anchorPointXPx = 0,
        xOffsetPx = at.xPx,
        divXOffsetPx = 0,
        pointYPx = at.yPx,
        yOffsetPx = 0,
        widthPx = widthPx,
        heightPx = heightPx,
        subDivisionWidthPx = widthPx,
        depth = depth,
        backgroundColor = null,
        margin = emptyList(),
        padding = emptyList(),
        border = emptyList(),
        radius = emptyList(),
        borderColors = emptyList(),
        isRelative = true,
        isEscaped = true,
        children = children.map { DisplayDivChildLine(it) },
    )

    private fun plan(
        block: DisplayDivBlock,
        anchorTopPx: Int,
        anchorPointXPx: Int = 0,
    ): DivOverlayPlanner.Plan = planner.plan(
        blocks = listOf(block),
        anchorTopPx = anchorTopPx,
        anchorPointXPx = anchorPointXPx,
        canvasHeightPx = canvasHeightPx,
        visibleTopLine = 0,
        visibleBottomLine = 100,
        anchorLineNo = 0,
    )

    private fun row(vararg runs: TextRun, indent: Int = 0): DisplayRow {
        val cells = runs.sumOf { run ->
            val graphic = run.graphic
            if (graphic != null) graphic.widthCells else Grid.cells(run.text)
        }
        return DisplayRow(
            runs = runs.toList(),
            cells = cells,
            isLogicalLine = true,
            isLineEnd = true,
            indentCells = indent,
        )
    }

    private fun button(text: String, value: Long) = TextRun(
        text = text,
        fore = 0xC0C0C0,
        back = null,
        styleMask = 0,
        fontName = "黑体",
        kind = RunKind.BUTTON,
        buttonValue = value,
    )

    // ---- ① 逐项几何 ----

    @Test
    fun `divTargets 逐项给出子行按钮的像素几何`() {
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(
                block(listOf("<button value='7'>OK</button>")),
                anchorTopPx = 40,
                anchorPointXPx = 18,
            ),
            geometry = geometry(),
        )
        val hit = layer.divTargets().single()
        assertEquals(0, hit.blockIndex, "behind 空、depth 0 落 front ⇒ Plan.all 下标 0")
        assertEquals(0, hit.childRow)
        assertEquals(0, hit.depth)
        assertEquals(0, hit.colStart, "'OK' 从子行列 0 起")
        assertEquals(2, hit.colEnd)
        assertEquals(7L, hit.value, "数值按钮值（HtmlRunFlatten :498 的 toLongOrNull）")
        assertEquals("OK", hit.label)
        // 内容盒 x = PointX(18) + xOffset(0)（CDP :134/:140），y = 行顶 40 + 0（内容盒顶）。
        assertEquals(18f, hit.xPx)
        assertEquals(40f, hit.yPx)
        assertEquals(18f, hit.widthPx, "2 格 × 9px 半角步进")
        assertEquals(18f, hit.heightPx, "文本节点纵带 = Config.FontSize（AConsoleDisplayNode.cs:29-31）")
    }

    @Test
    fun `divTargets 的盒矩形支持负的块左沿`() {
        // ALIGNMENT 的整行位移可以把 PointX 推成负值（ConsoleDisplayLine.cs:80-83），
        // 命中几何与绘制几何同样允许负 x：块内可见部分可点，画布外（负像素）不命中。
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(
                block(listOf("<button value='7'>OK</button>")),
                anchorTopPx = 40,
                anchorPointXPx = -9,
            ),
            geometry = geometry(),
        )
        val hit = layer.divTargets().single()
        assertEquals(-9f, hit.xPx)
        assertEquals(18f, hit.widthPx)
        assertNull(layer.hitAt(-1, 45), "画布外（负像素）不命中")
        layer.setPointerPx(5, 45, pressed = false)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.focusedDiv(), "负左沿之后可见的那一半仍可点")
    }

    // ---- ② 焦点盒内 / 盒外 ----

    @Test
    fun `focusedDiv 命中盒内 盒外为 null`() {
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 40, anchorPointXPx = 18),
            geometry = geometry(),
        )
        layer.setPointerPx(20, 45, pressed = false)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.focusedDiv())
        assertNull(layer.pressedDiv(), "未按下不产生按下态")
        layer.setPointerPx(300, 45, pressed = true)
        assertNull(layer.focusedDiv(), "块外不命中")
        assertNull(layer.pressedDiv(), "按下但没有命中目标")
    }

    @Test
    fun `按下态在命中时成立 抬起即灭`() {
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 40, anchorPointXPx = 0),
            geometry = geometry(),
        )
        layer.setPointerPx(5, 45, pressed = true)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.pressedDiv())
        layer.setPointerPx(5, 45, pressed = false)
        assertNull(layer.pressedDiv())
    }

    // ---- ③ 与格坐标表的坐标系一致性 ----

    @Test
    fun `divTargets 的像素列与格坐标表的列同尺`() {
        val layer = ButtonLayer()
        // 正文行：缩进 2 格后是按钮 [2,4)。
        layer.attach(listOf(row(button("OK", 7L), indent = 2)))
        val target = layer.hit(0, 2)
        assertEquals(2, target?.colStart)
        // div 块的锚列与正文行缩进同宽：内容盒 x = cellsToPixels(2) = 18。
        layer.attachDiv(
            plan = plan(
                block(listOf("<button value='7'>OK</button>")),
                anchorTopPx = 0,
                anchorPointXPx = metrics.cellsToPixels(2),
            ),
            geometry = geometry(),
        )
        val hit = layer.divTargets().single()
        assertEquals(0, hit.colStart, "子行内的列号从 0 起（对齐偏移不折进列号）")
        assertEquals(
            metrics.cellsToPixels(target?.colStart ?: -1),
            hit.xPx.toInt(),
            "div 命中 x = 内容盒左沿 + cellsToPixels(列)，与绘制/正文行同一把尺",
        )
        assertEquals(
            metrics.cellsToPixels(2) + metrics.cellsToPixels(hit.colStart),
            hit.xPx.toInt(),
        )
    }

    // ---- ④ 优先级：正文行盖 div ----

    @Test
    fun `正文行按钮盖住 depth 0 的 div`() {
        val layer = ButtonLayer()
        // 第 2 行行条顶 = 180 - (10 - 2) * 18 = 36；div 内容盒顶也放在 36。
        layer.attach(listOf(row(), row(), row(button("OK", 7L))))
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 36),
            geometry = geometry(),
        )
        assertTrue(layer.divTargets().isNotEmpty())
        assertNull(layer.hitAt(5, 45), "正文行按钮按 depth 0 槽先扫（EmueraConsole.cs:2350-2396）⇒ div 输")
        assertEquals(7L, layer.hit(2, 0)?.value, "正文行照旧能命中")
    }

    @Test
    fun `负 depth 的 div 盖住正文行`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(), row(), row(button("OK", 7L))))
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>"), depth = -1), anchorTopPx = 36),
            geometry = geometry(),
        )
        val hit = layer.hitAt(5, 45)
        assertEquals(-1, hit?.depth, "负 depth 先于正文行扫（EmueraConsole.cs:2343 的键升序）")
        layer.setPointerPx(5, 45, pressed = false)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.focusedDiv())
    }

    // ---- ⑤ 格坐标表行为不变 ----

    @Test
    fun `attachDiv 不改格坐标命中表`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("OK", 7L)), row(button("EXIT", 0L), indent = 2)))
        val before = layer.all()
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 0),
            geometry = geometry(),
        )
        assertEquals(before, layer.all(), "div 子行按钮不进 displayLineList 的命中扫描（EmueraConsole.cs:2358-2361）")
        assertTrue(layer.divTargets().isNotEmpty(), "div 命中项在另一张表")
    }

    // ---- ⑥ B9 定案锁：HTMLDIV 负 depth 不跟官方 bug（判词 7「优先更好更优雅的一方」） ----

    /** 多块同帧（混合 depth 键表）；几何位移走块自带的 `xOffsetPx` / `pointYPx`，不改 fixture 语义。 */
    private fun planAll(
        blocks: List<DisplayDivBlock>,
        anchorTopPx: Int,
        anchorPointXPx: Int = 0,
    ): DivOverlayPlanner.Plan = planner.plan(
        blocks = blocks,
        anchorTopPx = anchorTopPx,
        anchorPointXPx = anchorPointXPx,
        canvasHeightPx = canvasHeightPx,
        visibleTopLine = 0,
        visibleBottomLine = 100,
        anchorLineNo = 0,
    )

    /** 正文行的不可点文本段（语料 `<nonbutton title='…'>` 同形，`HtmlManager.cs:841-847`）。 */
    private fun titled(text: String, tip: String) = TextRun(
        text = text,
        fore = 0xC0C0C0,
        back = null,
        styleMask = 0,
        fontName = "黑体",
        tooltip = tip,
    )

    /**
     * **键全负 ⇒ 正文行仍可点**（本笔定案的正面锁）。
     *
     * 官方：`EmueraConsole.cs:2342-2343` 把 `escapedParts` 的 depth 键摊平升序，正文行那趟扫描只在
     * **首个非负键**处插一次（1.824 `:2350` 的 `flag3`、蛇版 4.981
     * `Emuera/UI/Game/EmueraConsole.cs:2612` 的 `zeroTested`）⇒ 一帧里只有负 depth 块时整趟正文扫描
     * 不执行，`HtmlManager.cs:723-739` 登记的正文链接与 `PRINTBUTTON` 全点不动。移植侧
     * `ButtonLayer.kt:836` 的正文行扫描**无此门闸**：该像素回 null，调用方（`ConsoleScreen.click`、
     * `CanvasConsoleView.hitOnCurrentView` 的 :1473 半段）落回像素→格→`ButtonLayer.hit`（`:462`）
     * ⇒ 正文行恒可点。语料触发形 = `UI_Container_Framework.ERB:275` 的 `STYLE_POPUP`
     * （@STYLE_RESOLVE 的 popup 支，`depth='-1'`）与 `QOL_USERCOM.ERB:284` 的 ATR 行。
     */
    @Test
    fun `键全负的一帧里正文行按钮仍可点`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(), row(), row(button("OK", 7L))))
        // 整帧只有一个 depth=-1 的键（语料 popup 就是这个形状）：块盒压第 0-1 行带，正文按钮在第 2 行，
        // 探针像素 y=45 既不在块盒内也不在任何子行矩形内。
        layer.attachDiv(
            plan = plan(block(listOf("<button value='9'>POP</button>"), depth = -1), anchorTopPx = 0),
            geometry = geometry(),
        )
        assertEquals(
            listOf(-1),
            layer.divTargets().map { it.depth }.distinct(),
            "本帧 depth 键表全负 —— 官方在此整趟跳过正文行扫描",
        )
        assertNull(layer.hitAt(5, 45), "定案：负键表全负也要把正文行像素让回格表，不跟官方 bug")
        assertEquals(7L, layer.hit(2, 0)?.value, "让位之后正文行按钮照旧命中")
    }

    /**
     * 键全负 ⇒ 正文行的**不可点文本提示**仍查得到（B9 门闸在 hitAt/tooltipAt 面上唯一能测出红的落点）。
     *
     * `ButtonLayer.kt:865-869` 以 `rowHit != null || rowTip != null` 收尾，与 :836 同属一条无门闸的正文行
     * 扫描。把这两处按官方形补上「存在非负键」门闸（1.824 `EmueraConsole.cs:2350` / 4.981 `:2612`），
     * 本用例即由「素质说明」翻成 null ⇒ 本笔定案的反向证明（突变探针 P1，读数见交付报告）。
     * 同一门闸加在 :836 的 hitAt 上测不出红：键全负时 :829-834 的负键循环要么已返回、要么该像素本就
     * 不在块内，:837 起遍历的非负键集合为空，改与不改同解 —— 等价性分析写在交付根因层②。
     */
    @Test
    fun `键全负的一帧里正文行提示仍查得到`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(), row(), row(titled("素质", "素质说明"))))
        layer.attachDiv(
            plan = plan(block(listOf("<button value='9'>POP</button>"), depth = -1), anchorTopPx = 0),
            geometry = geometry(),
        )
        assertEquals(
            "素质说明",
            layer.tooltipAt(5, 45),
            "正文行提示无条件参与（:865-869）；官方门闸形在此整趟跳过，该处无提示",
        )
    }

    /**
     * 混合正负键（-1 / 0 / 2 同帧）⇒ 命中链按「负键 → 正文行 → 零键 → 正键」落位，
     * 与官方在**有非负键**时的顺序一致（1.824 `:2343`+`:2350` / 4.981 `:2605`+`:2612`）。
     */
    @Test
    fun `混合正负键的一帧按负键到正文行到零键到正键落位`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(), row(), row(button("OK", 7L)), row(button("EXIT", 0L))))
        layer.attachDiv(
            planAll(
                listOf(
                    // 负键：块盒压第 2 行，正好盖住正文 OK
                    block(listOf("<button value='9'>NEG</button>"), depth = -1),
                    // 零键：x 偏到第 6 格起，避开正文按钮占的列
                    block(listOf("<button value='8'>ZERO</button>"), depth = 0, at = At(xPx = 54)),
                    // 正键：向下挪一行，盖住第 3 行的正文 EXIT
                    block(listOf("<button value='6'>POS</button>"), depth = 2, at = At(yPx = 18)),
                ),
                anchorTopPx = 36,
            ),
            geometry = geometry(),
        )
        assertEquals(
            listOf(-1, 0, 2),
            layer.divTargets().map { it.depth },
            "命中表按 depth 升序摊平（attachDiv :776）",
        )
        // ① 负键盖正文行 ⇒ div 赢（官方同样先扫负键：升序键表里负数在前）
        val neg = layer.hitAt(5, 45)
        assertEquals(-1, neg?.depth, "负 depth 键先于正文行扫描")
        assertEquals(9L, neg?.value)
        // ② 正文行盖正 depth 块 ⇒ 正文行赢（删掉 :836 此处分道）
        assertNull(layer.hitAt(5, 60), "第 3 行 EXIT 按 depth 0 槽先扫 ⇒ depth=2 的块输")
        assertEquals(0L, layer.hit(3, 0)?.value)
        // ③ 正文行该列无可点件 ⇒ 零键块照常命中
        assertEquals(8L, layer.hitAt(60, 45)?.value, "空正文列不触发让位，depth 0 块命中")
    }
}
