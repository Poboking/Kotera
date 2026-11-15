package com.eratw.port.core.host

import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.vars.DimFoldArg
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 0.2.46 玩家反馈「战斗能力不显示升级进度条」的定向探针。
 *
 * 链路：`SOURCE.ERB:741` → `Qol_SkillProgressBar(LOCAL, 74)` →
 * `CASE GETNUM(EXP, "戦闘経験")` → `Qol_PrintBar` → `PRINTLEVELBAR` →
 * `COLOR_BAR`（`UI_Components_&_Tables.ERB:1223`，语料里唯一走 `HTML_PRINT …,1` 的
 * `<shape>` 矩形条）→ `HTML_RECT_BAR`。
 *
 * 本闸钉三处：`exp.als` 别名 → 装载期折叠表 → 运行期 GETNUM；
 * `HTML_PRINT …,1` 的纯 `<shape>` 段在 `PRINTFORM` 混排行里是否原样存活；
 * 以及**折行预算与渲染展平同一把尺**（0.2.46 的根修，见 [CellWidth.visibleHtmlCells]
 * 的「连续条带特例」）。别名与缓冲两闸是排除性证据：问题不在 GETNUM / 输出侧。
 */
class ExpBarBufferProbeTest {

    private fun csvDir(): Path? {
        val candidates = mutableListOf<Path>()
        System.getenv("ERATW_CORPUS")?.takeIf { it.isNotBlank() }?.let { candidates.add(Path.of(it)) }
        var cur: Path? = Path.of("").toAbsolutePath()
        repeat(4) {
            cur?.let { candidates.add(it) }
            cur = cur?.parent
        }
        for (c in candidates) {
            val named = listOf(
                c.resolve("eraTW4.981-261111-EAGLOV3/CSV"),
                c.resolve("CSV"),
            )
            for (p in named) {
                if (p.isDirectory() && Files.isRegularFile(p.resolve("exp.csv"))) return p
            }
        }
        return null
    }

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw com.eratw.port.core.err.CodeEE("BaseCtx 不应被调用：$name")
        override fun printSystemLine(message: String) = Unit
    }

    @Test
    fun `真语料 EXP 别名 折叠 运行期 全链同值 74`() {
        val dir = csvDir()
        Assumptions.assumeTrue(dir != null, "跳过：找不到真语料 CSV 目录")
        val data = GameDataLoader.load(dir!!)!!
        val tables = data.registry.systemKeywordTables()
        val expTable = tables["EXP"]
        println("PROBE exp.als 别名 戦闘経験 = ${expTable?.get("戦闘経験")}, 中文 战斗经验 = ${expTable?.get("战斗经验")}")
        println("PROBE registry.keywordIndex(EXP,1,戦闘経験) = ${data.registry.keywordIndex("EXP", 1, "戦闘経験")}")

        val folded = DimFunctionFolders.pureStatic(keywordTables = tables)
            .foldInt("GETNUM", listOf(DimFoldArg.IdentArg("EXP"), DimFoldArg.StrArg("戦闘経験")))
        println("PROBE 装载期折叠 GETNUM(EXP,戦闘経験) = $folded")

        val store = VariableStore(registry = data.registry)
        val base = BaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store)
        val runtime = (
            ctx.callFunctionRef(
                "GETNUM",
                listOf(
                    VarRef("EXP", listOf(IntLiteral(0)), operandType = OperandType.INT),
                    StrLiteral("戦闘経験"),
                ),
            ) as Value.IntV
        ).v
        println("PROBE 运行期 GETNUM(EXP,戦闘経験) = $runtime")

        assertEquals(74, expTable?.get("戦闘経験"))
        assertEquals(74L, folded)
        assertEquals(74L, runtime)
    }

    /** `HTML_RECT_BAR` 的渐变分支实际形态：上百个 4 参 rect + 一个空槽 rect。 */
    private fun barHtml(): String = buildString {
        repeat(60) { append("<shape type='rect' param='0,37,2px,63' color='#6DE1FF'>") }
        append("<shape type='rect' param='0,37,60px,63' color='#112233'>")
    }

    @Test
    fun `HTML_PRINT 1 的纯 shape 段在混排行里保留原文`() {
        val html = barHtml()
        val s = LineBufferConsoleSink(clientWidth = 139, scriptFontSize = 18)
        s.print("Lv2 ")
        s.printHtml(html, true)
        s.print(" Lv3")
        s.newLine()

        val rows = s.snapshot()
        println("PROBE 行数 = ${rows.size}")
        for ((i, row) in rows.withIndex()) {
            val detail = row.segments.map {
                "${it.kind}/${it.htmlOption}/${it.text.take(48)}/${it.text.length}"
            }
            println("PROBE 行 $i: $detail")
        }
        val shapeSegs = rows.flatMap { it.segments }.filter {
            it.kind == LineBufferConsoleSink.Kind.HTML && it.text.contains("<shape")
        }
        assertEquals(1, shapeSegs.size, "形状 HTML 段必须原样留在同一显示行里")
        assertEquals(html, shapeSegs[0].text)
        assertTrue(shapeSegs[0].htmlOption)
    }

    /** 真语料满进度条：`HTML_RECT_BAR` 会把 180px 切成 180 个 `1px` 的小矩形。 */
    private fun fullBarHtml(): String = buildString {
        repeat(180) { append("<shape type='rect' param='0,37,1px,63' color='#6DE1FF'>") }
    }

    /**
     * 0.2.46 回归：`HTML_RECT_BAR` 的渐变分支把 180px 切成 180 个 `1px` 块。
     * 折行预算原先把每块各计 1 格（180 格），真机 136 列下 `Lv2 [条] Lv3`
     * 被折成三行（条与两个等级标签分家）；渲染侧 `HtmlRunFlatten.flushRectBands`
     * 却把它们合成一个 20 格矩形。两侧必须同一把尺。
     */
    @Test
    fun `连续 shape 条带按合并后的像素宽计一次格 且不折行`() {
        val html = fullBarHtml()
        val cells = CellWidth.visibleHtmlCells(html, 18)
        println("PROBE fullbar visibleHtmlCells=$cells")
        assertEquals(20, cells, "180 × 1px = 180px ⇒ ceil(180 / 9.36) = 20 格，与渲染合并后的条同宽")

        val s = LineBufferConsoleSink(clientWidth = 136, scriptFontSize = 18)
        s.print("Lv2 ")
        s.printHtml(html, true)
        s.print(" Lv3")
        s.newLine()
        val rows = s.snapshot()
        for ((i, row) in rows.withIndex()) {
            println("PROBE fullbar 行 $i: ${row.segments.map { "${it.kind}/len=${it.text.length}" }}")
        }
        assertEquals(1, rows.size, "整行必须仍是一条显示行，不许把条与 Lv 标签折散")
        assertEquals(3, rows[0].segments.size, "Lv2 / 条 / Lv3 三段同排")
        assertEquals(html, rows[0].segments[1].text)
    }

    /** 单块四参 rect 仍按 w 逐像素计格（既有读数不能被合并逻辑改掉）。 */
    @Test
    fun `单块四参矩形宽度不变`() {
        assertEquals(
            24,
            CellWidth.visibleHtmlCells("<shape type='rect' param='0px, 30px, 216px, 64px'>", 18L),
        )
    }

    /**
     * 同一根源的第三把尺：sink 的 [LineBufferConsoleSink] 行内游标（div 锚点、按钮列位）
     * 必须与折行预算、渲染展平同走一份条带扫描。修折行预算时若漏了这里，含进度条的
     * 状态屏 div 覆盖块会按 180 格定位、图形却只占 20 格（命中列位整体偏右）。
     */
    @Test
    fun `条带的折行预算与 div 锚点行内游标同一把尺`() {
        val html = fullBarHtml()
        val budget = CellWidth.visibleHtmlCells(html, 18)
        val card = "<div xpos='0px' ypos='0px' width='100px' height='20px'>台词</div>"
        val sink = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)
        sink.printHtml("<nobr>" + html + card + "</nobr>", false)
        val block = sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single()
        println("PROBE band budget=$budget anchorCol=${block.anchorCol} anchorPx=${block.anchorPointXPx}")
        assertEquals(20, budget)
        assertEquals(budget, block.anchorCol, "div 锚点列必须等于折行预算（不许逐块各计 1 格）")
        assertEquals(180, block.anchorPointXPx, "像素投影 = 条带脚本像素总宽 180px")
    }

    /** y/h 同值异写（100% 与 18px）在渲染侧会合并，core 的合并判据也必须按折算值。 */
    @Test
    fun `y 与 h 按折算像素判同 不按原始参数文本判`() {
        val mixed = "<shape type='rect' param='0,100,3px,63' color='#110000'>" +
            "<shape type='rect' param='0,18px,3px,63' color='#220000'>"
        assertEquals(1, CellWidth.visibleHtmlCells(mixed, 18L), "两块共 6px ⇒ 合并后折 1 格")
        assertEquals(2, CellWidth.rectBandRun(mixed, 0, 18L)?.count, "两块并进同一条带")
    }
}
