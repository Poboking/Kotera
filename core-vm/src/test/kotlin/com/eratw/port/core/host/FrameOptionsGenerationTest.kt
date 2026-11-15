package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 帧口径可点集合与按钮世代**必须同步失效**的钉子（0.2.45 收件箱
 * `ABLNAME:900/1150`、`nCharaUpdate:70`、`CFLAG:9999` 同一族的引擎侧根因）。
 *
 * ## 真机现场
 *
 * 11-12 两份 `5fd00b0-dirty` 报告（`eratw-report-20261112-003029` / `-002822`）
 * 的屏上末尾是「能力编辑」子菜单（`[9999] 返回`），但滚屏历史里还留着主设定菜单
 * 的 `[2000] 能力编辑` / `[2500] 种族编辑` / `[3000] 素质编辑` / `[9999] 设定完毕`。
 * 崩溃值 `ABLNAME:1150` / `:900` 恰好 = `(2500-200)/2` / `(2000-200)/2`
 * （`NEWGAME_CUSTOM.ERB` 的 `CUSTOM_ABL`），`ABLNAME` 容量 100
 * （4.981 `VariableSize.csv:76`）。也就是说：玩家点到了**上一页的按钮**，值却落进了
 * 这一页的 `INPUT`。越界本身是官方同形（`VariableToken.cs:1354-1357` 抛 `OoRVarArg`），
 * 引擎缺陷在「旧世代按钮为什么还能被点中并交值」。
 *
 * ## 原版三道判据，判的都是当下的 `lastButtonGeneration`
 *
 * - 鼠标命中：`EmueraConsole.cs:2724`、`:2740` 对 `Generation != lastButtonGeneration`
 *   的按钮 `continue` / `canSelect = false`（滚屏历史里留着也不算命中）；
 * - 选中态：`:1823` 把失配的 `selectingButton` 清空；
 * - 提交：`IntButton`/`StrButton` 在 `:1188`/`:1246` 按世代反查按钮，查不到 `return false`。
 *
 * 原版没有「缓存一张可点按钮表」这件事，所以 `newGeneration()`（`:597-635`）在等待入口
 * 一前移 `lastButtonGeneration`，旧按钮当场全部作废。移植侧把表提前算成
 * [LineBufferConsoleSink.publishedOptions] 的 [LineBufferConsoleSink.frameOptions]，
 * 而它只在 [LineBufferConsoleSink.publishOptionsIfDirty] 按 `optionsDirty` 重算：
 * `publishFrame`（AWAIT / REDRAW 让出边界）常先于 `noteInputWait` 到达并清脏，
 * 随后的世代前移不置脏 ⇒ 快照里留着整批已作废的按钮，壳层世代闸照值比对一路放行。
 *
 * ## 反向证明
 *
 * 把 [LineBufferConsoleSink.moveLastButtonGeneration] 里的 `optionsDirty = true`
 * 摘掉，本类第 1、3、4 条用例必红（旧值仍判成当前世代）；连同
 * [LineBufferConsoleSink.isCurrentGeneration] 退回「空表放行」形态，第 4 条必红。
 */
class FrameOptionsGenerationTest {

    private fun sink(frameGate: Boolean = true): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = 120, maxLines = 200, frameGateEnabled = frameGate)

    /** [line] = 原版 `process.getCurrentLine` 的身份位，逐页推进。 */
    private fun note(s: LineBufferConsoleSink, line: Long) {
        s.noteInputWait(
            InputRequest(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VALUE,
                scriptLine = line,
            ),
        )
    }

    /**
     * 真机时序复刻：主设定菜单 → `publishFrame` → `INPUT` → 点 2000 进子菜单 →
     * 子菜单只印文字 → `publishFrame` → 子菜单 `INPUT`。
     *
     * 关键在最后一步：`publishFrame` 已经把脏清掉，世代前移发生在它之后。
     */
    @Test
    fun `新页只印文字时帧快照随世代作废_旧菜单按钮不再可点`() {
        val s = sink()
        s.printLine("[2000] 能力编辑")
        s.printLine("[2500] 种族编辑　[3000] 素质编辑")
        s.publishFrame() // AWAIT / REDRAW 让出边界：整帧上屏，快照按旧世代算好
        note(s, 100L)
        assertEquals(
            setOf("2000", "2500", "3000"),
            s.publishedOptions().map { it.submitValue }.toSet(),
            "主设定菜单页：三颗按钮都该是可点的",
        )
        assertTrue(s.isCurrentGeneration("2500"), "本页按钮必须放行")

        // 玩家点 2000 进入「能力编辑」子菜单：这一屏只有正文，没有新的按钮对象。
        s.printLine("点击想要更改的能力并直接输入目标等级，或者点击按钮。")
        s.publishFrame() // 这个让出边界仍按旧世代算快照（此时还没进 INPUT，与原版一致）
        assertTrue(s.isCurrentGeneration("2500"), "等待入口之前世代未动，旧页按钮尚未作废")

        // 子菜单 INPUT：newGeneration 按脚本行身份前移世代。
        note(s, 200L)
        assertTrue(s.currentOptions().isEmpty(), "新页没有当前世代按钮，右侧栏应空")
        assertTrue(s.publishedOptions().isEmpty(), "帧快照必须跟着世代一起作废")
        assertFalse(s.isCurrentGeneration("2500"), "旧按钮 2500 不得再交值（真实崩溃 -003029）")
        assertFalse(s.isCurrentGeneration("2000"), "旧按钮 2000 不得再交值（真实崩溃 -002822）")
    }

    /**
     * 同值跨屏：新页屏上照样出现 `[9999] 返回`，但它是 `PRINTPLAIN` 的纯文本，
     * 不是按钮对象。按**值**比对的旧闸会把旧页的 `[9999] 设定完毕` 当成新页那颗放行；
     * 按按钮世代判据则必须拒绝 —— 判据问的是「点中的是哪颗按钮」，不是「这个数字见过没」。
     */
    @Test
    fun `新页出现同值文本时旧页按钮仍按世代拒绝放行`() {
        val s = sink()
        s.printLine("[9999] 设定完毕")
        s.publishFrame()
        note(s, 10L)
        assertTrue(s.isCurrentGeneration("9999"))

        s.printPlain("[9999] 返回") // 屏上有同值字样，但不产生按钮对象
        s.newLine()
        s.publishFrame()
        note(s, 20L)
        assertTrue(s.currentOptions().isEmpty(), "新页没有按钮对象，旧页整批作废")
        assertFalse(s.isCurrentGeneration("9999"), "值相同也不能借旧页按钮放行")
    }

    /**
     * 不能为了修跨屏串值就把正常页误杀：同一条 `INPUT` 行重等（非法输入重显）
     * 世代不动，帧快照必须仍在（`GenerationRedisplayTest` 的同一条语义）。
     */
    @Test
    fun `同一脚本行重试不推进世代_当前页按钮仍可点`() {
        val s = sink()
        s.printLine("[1] 买　　[2] 卖　　[99] 退出")
        s.publishFrame()
        note(s, 77L)
        assertTrue(s.isCurrentGeneration("2"))

        s.printLine("买不起") // 提示行，未重印按钮
        s.publishFrame()
        note(s, 77L) // 同一条 INPUT 行重等
        assertTrue(s.isCurrentGeneration("2"), "重等不能把屏上按钮的世代推走")
        assertTrue(s.isCurrentGeneration("99"))
        assertEquals(3, s.publishedOptions().size, "帧快照不该被无谓清空")
    }

    /**
     * 单独隔离「空表 fail-open」：当前页一颗按钮都没有时，原版
     * `EmueraConsole.cs:2724` 逐颗 `continue` 后 `canSelect = false`，点空白只走
     * `PressEnterKey`（`MainWindow.cs:806-820`），不会把任何按钮值交出去。
     * 旧壳层 `options.isEmpty() || ...` 的短路恰好在这里放行了任意值。
     */
    @Test
    fun `当前页没有任何按钮时任何按钮值都不放行`() {
        val s = sink()
        s.printPlain("请输入数字：")
        s.newLine()
        s.publishFrame()
        note(s, 1L)
        assertTrue(s.publishedOptions().isEmpty(), "本页确实没有按钮")
        assertFalse(s.isCurrentGeneration("9999"), "空表不是放行凭证")
        assertFalse(s.isCurrentGeneration("0"))
    }

    /**
     * 弹窗形态（与 `ComOptionPopupHitTest` T4 同源）：`HTML_PRINT …, 1` 的按钮段
     * 在 `noteInputWait` 的 `flushPending` 才上屏，收尾那次发布必须仍把它们算进快照。
     */
    @Test
    fun `等待入口落下的缓冲按钮仍进当前世代快照`() {
        val s = sink()
        s.printButtonC("[+]", -301L, false)
        s.printButtonC("泡茶", 301L, true)
        s.printLine("")
        s.deleteLine(1)
        s.printHtml("<button value='1'>泡红茶</button><button value='99'>返回</button>", true)
        // HTML_PRINT …, 1 的按钮段在 noteInputWait 的 flushPending 才上屏（与
        // ComOptionPopupHitTest T4 同一时序），收尾那次 publishOptionsIfDirty 必须补算。
        note(s, 88L)
        val values = s.publishedOptions().map { it.submitValue }.toSet()
        assertTrue("1" in values && "99" in values, "弹窗按钮不能因世代脏标记被误杀：$values")
        assertTrue(s.isCurrentGeneration("1"), "弹窗按钮必须可点")
    }

    /**
     * 逃生阀（`frameGateEnabled = false`）走活表读数，同样按当前世代过滤，
     * 不因为切了口径就复活旧按钮。
     */
    @Test
    fun `关掉一屏一帧时活表读数同样不复活旧世代`() {
        val s = sink(frameGate = false)
        s.printLine("[7000] 旧页")
        s.publishFrame()
        note(s, 1L)
        assertTrue(s.isCurrentGeneration("7000"))

        s.printLine("新页正文")
        s.publishFrame()
        note(s, 2L)
        assertFalse(s.isCurrentGeneration("7000"), "活表也必须按世代过滤")
    }
}
