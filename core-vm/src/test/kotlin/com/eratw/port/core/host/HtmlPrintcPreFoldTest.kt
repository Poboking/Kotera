package com.eratw.port.core.host

import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.FakeInputSink
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `HTML_PRINTC` / `HTML_PRINTLC` 的 **fork 逐项预折**回归
 * （玩家反馈：灵梦点「会话」后，会话及同一列的游戏选项整批消失）。
 *
 * ## 判据
 *
 * fork `EmueraConsole.Print.PrintHtmlC`（`reference/emuera-skia-4.981/.../EmueraConsole.Print.cs:521-574`）
 * 在补齐/追加每一项之前先读 `printBuffer.CurrentLineWidth`，判
 * `currentPx > 0 && currentPx + cellWidthPx > Config.DrawableWidth`（:529-539）：
 * 命中就 `Flush` + `addRangeDisplayLine` 把当前缓冲行落表，**再**写这一项。
 * 因此指令网格的每一显示行各自是一个 `IsLogicalLine=true` 的逻辑行。
 *
 * 移植侧旧实现把整张网格攒在 pending 里等 `PRINTL` 一次落表，组内只有首排是逻辑行：
 * - `LINECOUNT` 只 +1（同屏 `INFO.ERB:25` 的 `UI_STARTLINE = LINECOUNT`、
 *   `USERCOM.ERB:391` 的 `CLEARLINE LINECOUNT - UI_STARTLINE` 因此读到不同行数）；
 * - `CLEARLINE 1`（`QOL_USERCOM.ERB:270`、`USERCOM.ERB:389`）与引擎级 `deleteLine(1)/(2)`
 *   按逻辑行从表尾摘到组内首排，**一次把整张网格摘光** ——「会话 + 同一列整批消失」的结构性来源。
 *
 * 这里不针对「灵梦」「会话」「300」做任何特判：只钉引擎的通用行模型能力。
 * 夹具用 4.981 `QOL_USERCOM.ERB:108` 的逐项 `HTML_PRINTC` 形状，命令号取 `Train.csv` 的
 * 300 段，把「会话」放在第 5 列，同列另有 305 / 310 便于断言「一整列」。
 */
class HtmlPrintcPreFoldTest {

    private class NoGraphics : GraphicsSink {
        override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {}
        override fun printRect(params: List<Pair<Long, Boolean>>) {}
        override fun printSpace(params: List<Pair<Long, Boolean>>) {}
        override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
        override fun removeBgImage(name: String) {}
        override fun clearBgImages() {}
        override fun setTextBgColor(rgb: Long, opacity: Long) {}
    }

    /** 16 项：137 列 / 25 格列宽 ⇒ 5 + 5 + 5 + 1；索引 4/9/14 是同一列。 */
    private val items = listOf(
        403L to "休憩", 406L to "日记本", 410L to "扫除", 412L to "学习", 300L to "会话",
        301L to "泡茶", 302L to "身体接触", 303L to "道歉", 304L to "碰杯", 305L to "抚摸",
        306L to "亲吻", 307L to "拥抱", 308L to "闲聊", 309L to "冥想", 310L to "祈愿",
        311L to "结束",
    )

    private fun buttonHtml(value: Long, name: String) =
        "<font color='#64fa8c'><button value='$value'>$name[$value]</button></font>"

    /** 与 StatementExecutor.padHtmlColumn 同形的右对齐补齐（PRINTCLENGTH=25）。 */
    private fun pad(html: String): String {
        val cells = CellWidth.visibleHtmlCells(html, BuiltinHost.DEFAULT_FONT_SIZE)
        return " ".repeat((25 - cells).coerceAtLeast(0)) + html
    }

    /** 走真实语句层：4.981 的逐项 HTML_PRINTC 后 PRINTL 收尾。 */
    private fun grid(clientWidth: Int, code: FunctionCode = FunctionCode.HTML_PRINTC): LineBufferConsoleSink {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth, maxLines = 200)
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = sink,
            input = FakeInputSink(),
            graphics = NoGraphics(),
        )
        val executor = StatementExecutor()
        for ((value, name) in items) {
            executor.execute(
                instr(code, SpHtmlPrint(StrLiteral(buttonHtml(value, name)), null)),
                ctx,
            )
        }
        sink.newLine()
        return sink
    }

    private fun waitValue(sink: LineBufferConsoleSink) {
        sink.noteInputWait(
            InputRequest(type = InputRequest.InputType.INT, scriptLine = 42L),
        )
    }

    @Test
    fun `HTML_PRINTC 逐项预折 每个显示行各自是逻辑行`() {
        val sink = grid(137)
        val rows = sink.lines()
        assertEquals(
            listOf(5, 5, 5, 1),
            rows.map { it.segments.size },
            "显示行分布必须与 fork 一致（137 列 / 25 格 ⇒ 每行 5 项）；实际=${rows.map { it.segments.size }}",
        )
        assertEquals(
            listOf(true, true, true, true),
            rows.map { it.isLogicalLine },
            "fork 每次预折都落一张独立的逻辑行，折行续排不再是「组内非逻辑行」",
        )
        assertEquals(4L, sink.lineCount, "LINECOUNT 按显示行 +4，不再是整张网格 +1")
    }

    @Test
    fun `旧口径对拍 直接 printHtml 整张网格仍只算 1 个逻辑行`() {
        val sink = LineBufferConsoleSink(clientWidth = 137, maxLines = 200)
        for ((value, name) in items) sink.printHtml(pad(buttonHtml(value, name)), true)
        sink.newLine()
        val rows = sink.lines()
        assertEquals(listOf(5, 5, 5, 1), rows.map { it.segments.size }, "屏上行分布相同")
        assertEquals(listOf(true, false, false, false), rows.map { it.isLogicalLine }, "一次 flush 只有首排是逻辑行")
        assertEquals(1L, sink.lineCount, "这是旧口径：整张网格只 +1")
    }

    @Test
    fun `CLEARLINE 1 只摘一行 会话与同一列选项全部还在`() {
        val sink = grid(137)
        waitValue(sink)
        assertEquals(16, sink.currentOptions().size, "点击前 16 颗按钮全部可点")
        assertTrue(sink.currentOptions().any { it.value == 300L }, "会话[300] 在可点集合里")

        sink.deleteLine(1)
        val rows = sink.lines()
        assertEquals(3, rows.size, "按逻辑行删 1 行只能摘掉最后一张显示行；旧口径会把整张网格摘光")
        val visibleValues = rows.flatMap { it.htmlOptions }.map { it.value }
        assertEquals(15, visibleValues.size, "屏幕上还剩 15 颗按钮（16 - 末行 1 颗）")
        for (sameColumn in listOf(300L, 305L, 310L)) {
            assertTrue(sameColumn in visibleValues, "会话所在列的 $sameColumn 必须还在；实际=$visibleValues")
        }

        waitValue(sink)
        val clickable = sink.currentOptions().map { it.value }
        assertEquals(15, clickable.size, "重新冻结后应只剩 15 颗可点")
        for (sameColumn in listOf(300L, 305L, 310L)) {
            assertTrue(sameColumn in clickable, "会话所在列的 $sameColumn 必须仍可点；实际=$clickable")
        }
    }

    @Test
    fun `引擎级 deleteLine 2 只摘两行 不误删上方正文`() {
        val sink = LineBufferConsoleSink(clientWidth = 137, maxLines = 200)
        sink.printLine("上回指令：会话") // 网格上方的一行正文（对应 @SHOW_USERCOM 的收尾）
        for ((value, name) in items) sink.printHtmlC(pad(buttonHtml(value, name)), 25)
        sink.newLine()
        assertEquals(5, sink.lines().size, "正文 1 行 + 网格 4 行")

        sink.deleteLine(2)
        val rows = sink.lines()
        assertEquals(3, rows.size, "删掉网格末 2 行；正文行与网格前 2 行完好")
        assertEquals(
            "上回指令：会话",
            rows.first().segments.joinToString("") { it.text }.trim(),
            "上方正文不能被按逻辑行删行误删",
        )
        val visible = rows.flatMap { it.htmlOptions }.map { it.value }
        assertTrue(300L in visible && 305L in visible, "会话及同列仍可见；实际=$visible")
    }

    @Test
    fun `语料形态 两组网格夹一次 PRINTL 后删行不再抹掉会话整批`() {
        val sink = LineBufferConsoleSink(clientWidth = 137, maxLines = 200)
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = sink,
            input = FakeInputSink(),
            graphics = NoGraphics(),
        )
        val executor = StatementExecutor()
        fun emit(value: Long, name: String) {
            executor.execute(
                instr(FunctionCode.HTML_PRINTC, SpHtmlPrint(StrLiteral(buttonHtml(value, name)), null)),
                ctx,
            )
        }
        // QOL_USERCOM.ERB:13-116 的 FOR 循环，中间某处裸 PRINTL 把网格拆成两组
        (270L..281L).forEach { emit(it, "口上$it") }
        sink.newLine()
        listOf(300L to "会话").plus((301L..311L).map { it to "指令$it" }).forEach { (v, n) -> emit(v, n) }
        sink.newLine()
        waitValue(sink)
        assertEquals(24, sink.currentOptions().size, "两组共 24 颗按钮全部可点")

        sink.deleteLine(1)
        val visible = sink.lines().flatMap { it.htmlOptions }.map { it.value }
        val longs = visible.filterIsInstance<Long>()
        assertTrue(300L in longs, "会话[300] 必须还在；实际=$visible")
        assertTrue(
            longs.count { it >= 300L && it <= 311L } >= 10,
            "COM300+ 那一批不再被一次按逻辑行删行整批抹掉；实际=$visible",
        )
        assertEquals(12, longs.count { it >= 270L && it < 300L }, "上一组网格不受影响")
    }

    @Test
    fun `HTML_PRINTLC 同样走预折 左对齐列网格不塌成一条逻辑行`() {
        val sink = grid(137, FunctionCode.HTML_PRINTLC)
        val rows = sink.lines()
        assertEquals(listOf(5, 5, 5, 1), rows.map { it.segments.size })
        assertEquals(listOf(true, true, true, true), rows.map { it.isLogicalLine })
        assertEquals(4L, sink.lineCount)
    }

    @Test
    fun `宿主未接配置时 columnCells 为 0 退化为旧追加路径`() {
        val sink = LineBufferConsoleSink(clientWidth = 137, maxLines = 200)
        for ((value, name) in items) sink.printHtmlC(pad(buttonHtml(value, name)), 0)
        sink.newLine()
        val rows = sink.lines()
        assertEquals(listOf(5, 5, 5, 1), rows.map { it.segments.size }, "headless 装置的屏上形状不变")
        assertEquals(1L, sink.lineCount, "columnCells<=0 不判界、不预折")
    }
}
