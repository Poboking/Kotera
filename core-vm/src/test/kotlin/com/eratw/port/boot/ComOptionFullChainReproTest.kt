package com.eratw.port.boot

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.QueueInputSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 泡茶、演奏「+」子选单**全玩家链**回归锁（QB-A10，2026-11-20）。
 *
 * ## 本锁补的验收缺口
 *
 * 玩家案「点 + 弹出子选单后点选项没反应、或有黄框但无变化」在 0.2.44-0.2.45 三个版本
 * 反复复发。此前的回归面各盖半段，没有任何一处把**玩家实际走的那条链**从头到尾钉住：
 * - `ComOptionRealCorpusProbeTest`：从 `COM_OPTION(301)` **直调**起测 —— 跳过了面板页
 *   （点「+」的 INPUT 等待页）与「+」按钮自身的点击交付；
 * - `ComOptionPopupHitTest`、`ComOptionPopupDeviceGeometryTest`：只钉渲染命中与几何半段；
 * - `QueueInputSinkMouseButtonTest`、`InputStatementsTest`：只钉交付与回写半段。
 * 面板页与弹窗页之间的世代交接（点「+」后引擎的重印、CLEARLINE、世代前移）因此
 * 长期没有判据 —— 三轮修复里任何一轮把它修坏都不会有测试红。
 *
 * ## 全链时序（真语料 + 生产对象图，两页一次跑完）
 *
 * 1. **面板页**：命令按钮行上屏（`BUILD_COM_BUTTON_HTML` 的真实产物形状，
 *    `HTML_PRINT_Components.ERB:136-146`：`[+]` = `<button value='-301' title='点击切换指令'>`）。
 *    等待复刻 `ConsoleBeginHost.waitSystemInteger`（`ConsoleBeginHost.kt:405-423`）：
 *    `publishFrame` → `noteInputWait(INT, VALUE, 无鼠标)` → 阻塞取值。
 * 2. **点「+」**：真机点击通道 = `QueueInputSink.submitButtonClick("-301", 1)`
 *    （壳层 `handleConsoleTap` → `submitButtonValue` → `GameHost.submitButton`，
 *    原版 `MainWindow.cs:824-856` 分支②的 changedByMouse 路；面板等待无鼠标位，
 *    附加槽不写 —— 原版分支④ `MainWindow.cs:898-905` 对左键同样不写 RESULT:1）。
 *    面板等待消费后读数必须 = -301（负值是 `USERCOM.ERB:388` 的子选单派发判据）。
 * 3. **派发**：`USERCOM.ERB:389-391` —— `CLEARLINE 1` → `TRYCALLFORM COM_OPTION(ABS(RESULT))`
 *    → `RETURN -1`。本锁复刻 `CLEARLINE 1` 后直调真函数 `COM_OPTION(301)`（直调与
 *    `TRYCALLFORM` 走同一 `callFunction` 面，排除的是面板页遗留世代对弹窗页的污染）。
 * 4. **弹窗页**（真语料 `QOL_USERCOM.ERB`）：`:287 CLEARLINE 1` → `:288 HTML_PRINT HTML, 1`
 *    （缓冲）→ `:291 INPUT -1, 1`（INT + 默认 -1 + 鼠标）→ 玩家点 [泡红茶]（value=1）→
 *    `:293 MOUSE_BTN = RESULT:1` → `:296-298` 的 `MOUSE_BTN == 1 ∧ INRANGE(值, 0, OPTIONNUM)`
 *    才 `SETVAR COM_ALTER_301`。**RESULT:1 就是 0.2.44 那轮修复补的鼠标槽**
 *    （原版 `MainWindow.cs:834` 的 `InputString(1, selectedString)` 与 `:847` 的
 *    `InputInteger(1, 1)`，闸口 `EmueraConsole.cs:286-293` 的 IsWaintingInputWithMouse）。
 * 5. **反馈面**：`COM_NAME301()` 读 `COM_ALTER_301`（QOL_USERCOM.ERB:106-110 的
 *    `L_OPTION_NAME` 支）⇒ 面板按钮名变「泡红茶」——玩家看到的「点了有变化」。
 *
 * ## 每条断言锁哪道闸
 *
 * - 面板页 `isCurrentGeneration("-301")` = 真：主菜单（非子菜单）点击路径的世代闸
 *   （原版 `EmueraConsole.cs:2439` 的 `Generation != lastButtonGeneration ⇒ continue`）
 *   —— 反向证明锚：修子菜单不许把主菜单点坏。
 * - 面板页右栏快照含 `[+]`(-301) 与命令钮(301)：等待页身份与右栏芯片同步
 *   （`RailScanGate` 所治的「换页 250ms 滞后」案的数据面）。
 * - 点「+」读数 = -301：点击通道进**无鼠标**的 INT 等待照常按文本交付
 *   （`applyMouseSlots` 的形态闸不放行附加槽 = 原版分支④口径）。
 * - 弹窗页 `isCurrentGeneration("1")` = 真：**本案玩家症状的直接闸** —— 判假即
 *   「点了没反应」（壳层 `handleConsoleTap` 命中后被世代闸静默吞）。
 * - 弹窗页右栏快照 = 弹窗三钮：换页后右栏当帧跟手（2b8821243 案的数据面）。
 * - `COM_ALTER_301 == 1` 且 `COM_NAME301 == "泡红茶"`：值回填 + 鼠标槽 + SETVAR 全链，
 *   任何一环断（值丢、RESULT:1 丢、MOUSE_BTN 闸假、INRANGE 假）这条就红。
 *
 * 环境门：`ERATW_ERBC_DIR` + `ERATW_GAMEDIR`（缺变量不生成、不计 skipped，同探针口径）。
 * 生命周期：两页各用独立 feeder 与 done 旗，`sink.close()` 统一在最后
 * （提前 close 会让后一轮等待抛 InputSinkClosedException，见探针文件的踩坑记录）。
 */
class ComOptionFullChainReproTest {

    private companion object {
        const val FEED_WINDOW_NS: Long = 20_000_000_000L
        const val POLL_MS: Long = 2L
        const val JOIN_MS: Long = 2000L
        const val RAND_SEED: Long = 20260226L

        /** 红茶道具号（`CSV/Item.csv:163`「215,紅茶」）—— COM_NAME301 CASE 1 的语料前置。 */
        const val ITEM_BLACK_TEA: Long = 215L

        /** 面板等待的脚本行身份位（真机由 `ConsoleBeginHost.waitScriptLine` 现算，恒非负）。 */
        const val PANEL_LINE_TOKEN: Long = 90_001L

        /**
         * 面板按钮行的真实产物形状（`BUILD_COM_BUTTON_HTML`，
         * `HTML_PRINT_Components.ERB:136-146`）：`[+]` 钮 value = -COM_VAL
         * （`title='点击切换指令'`）+ 命令钮 value = COM_VAL。
         * `HTML_PRINTC` 与 `HTML_PRINT …, 1` 同走打印缓冲，等待入口 flush 上屏。
         */
        const val PANEL_HTML =
            "<button value='-301' title='点击切换指令'>[+]</button>" +
                "<button value='301'>泡茶[301]</button>"
    }

    /** 装配好的会话 + 产物侧车 + 本用例独占的输入队列（对象图同探针）。 */
    private class Rig(
        val session: GameSession,
        val metadata: BootMetadata,
        val sink: QueueInputSink,
    ) {
        val rt get() = session.runtime

        fun functionKey(name: String): String =
            metadata.functions.firstOrNull { it.key == name }?.key ?: error("$name 不在产物里")
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun corpusOrNull(): Pair<Path, Path>? {
        val erbcDir = env("ERATW_ERBC_DIR")
        val gameRoot = env("ERATW_GAMEDIR")
        if (erbcDir == null || gameRoot == null) return null
        val erbc = Path.of(erbcDir)
        val usable = Files.isDirectory(erbc) && Files.isRegularFile(erbc.resolve("program.erbc"))
        return if (usable) erbc to Path.of(gameRoot) else null
    }

    private fun assemble(erbc: Path, game: Path): Rig {
        val program = ProgramLoader.loadMapped(erbc.resolve("program.erbc"))
        val metadata = BootMetadata.read(erbc.resolve("program.meta"))
        val sink = QueueInputSink()
        val session = GameSession(
            gameRoot = game,
            program = program,
            metadata = metadata,
            randSeed = RAND_SEED,
            // 生产口径：`GameHost` 建会话恒传 true（一屏一帧），世代闸压在帧口径快照上。
            frameGateEnabled = true,
            inputSinkOverride = sink,
        )
        return Rig(session, metadata, sink)
    }

    /** 一轮「等挂起 → 抓现场 → 投一次按钮点击」的现场采集（等待入口时刻的同帧读数）。 */
    private class RoundReadout(
        val gate: String?,
        val chips: List<ConsoleOption>,
    )

    /** 弹窗等待入口的现场（请求真身 + 世代闸读数 + 右栏快照）。 */
    private class PopupReadout(
        val request: InputRequest?,
        val gate: String?,
        val chips: List<ConsoleOption>,
    )

    /** 一轮「等挂起 → 抓现场 → 投一次按钮点击 → 等消费」的投喂线程（探针同款纪律）。 */
    private fun startClickFeeder(
        sink: QueueInputSink,
        done: AtomicBoolean,
        value: String,
        onWaiting: () -> Unit,
    ): Thread {
        val feeder = Thread {
            val deadline = System.nanoTime() + FEED_WINDOW_NS
            while (System.nanoTime() < deadline) {
                if (sink.isAwaiting && sink.pendingCount == 0) {
                    onWaiting()
                    sink.submitButtonClick(value, 1)
                    while (sink.pendingCount > 0 && !done.get()) Thread.sleep(POLL_MS)
                    return@Thread
                }
                if (done.get()) return@Thread
                Thread.sleep(POLL_MS)
            }
        }
        feeder.isDaemon = true
        feeder.start()
        return feeder
    }

    private fun describe(options: List<ConsoleOption>): String =
        options.joinToString(prefix = "[", postfix = "]") { "${it.label}->${it.submitValue}" }

    /**
     * 第 1 页：命令面板（主菜单路径）。打印 → `waitSystemInteger` 三步复刻 →
     * 点击通道投「+」→ 收现场与消费读数。
     */
    private fun panelRound(rig: Rig): Pair<Long, RoundReadout> {
        rig.session.console.printHtml(PANEL_HTML, true)
        rig.session.console.printLine("")
        rig.session.console.publishFrame()
        val panelReq = InputRequest(type = InputRequest.InputType.INT, scriptLine = PANEL_LINE_TOKEN)
        rig.session.console.noteInputWait(panelReq)
        val readout = AtomicReference<RoundReadout>()
        val done = AtomicBoolean(false)
        val feeder = startClickFeeder(rig.sink, done, "-301") {
            val console = rig.session.console
            readout.set(
                RoundReadout(
                    gate = "plus301=${console.isCurrentGeneration("-301")}" +
                        " com301=${console.isCurrentGeneration("301")}",
                    chips = console.currentOptions(),
                ),
            )
        }
        val intValue = rig.sink.waitInput(panelReq).intValue
        done.set(true)
        feeder.join(JOIN_MS)
        return intValue to (readout.get() ?: RoundReadout(null, emptyList()))
    }

    /**
     * 第 2 页：弹窗（真语料全程）。派发前删一行（`USERCOM.ERB:389` CLEARLINE 1 复刻）→
     * 直调真函数 `COM_OPTION(301)`（其内 `QOL_USERCOM.ERB:287-291` 的 CLEARLINE/
     * HTML_PRINT/INPUT -1,1 全部真跑）→ 点击通道投「泡红茶」→ 收现场与 VM 结果。
     */
    private fun popupRound(rig: Rig): Triple<Throwable?, PopupReadout, Long> {
        rig.session.console.deleteLine(1)
        val readout = AtomicReference<PopupReadout>()
        val done = AtomicBoolean(false)
        val feeder = startClickFeeder(rig.sink, done, "1") {
            val console = rig.session.console
            readout.set(
                PopupReadout(
                    request = rig.sink.awaiting,
                    gate = "gen1=${console.isCurrentGeneration("1")}" +
                        " gen99=${console.isCurrentGeneration("99")}",
                    chips = console.currentOptions(),
                ),
            )
        }
        var vmError: Throwable? = null
        try {
            rig.rt.callFunction(rig.functionKey("COM_OPTION"), longArrayOf(301L), arrayOf<String>())
        } catch (t: Throwable) {
            vmError = t
        } finally {
            done.set(true)
            feeder.join(JOIN_MS)
        }
        val alter = rig.rt.store.readInt("COM_ALTER_301", longArrayOf(0L))
        return Triple(vmError, readout.get() ?: PopupReadout(null, null, emptyList()), alter)
    }

    /** 反馈面：面板按钮名按变体读回（`COM_NAME301` → RESULTS，SETVAR 与裸名静态读同槽）。 */
    private fun panelLabel(rig: Rig): String {
        rig.rt.callFunction(rig.functionKey("COM_NAME301"), longArrayOf(-1L), arrayOf<String>())
        return rig.rt.store.readStr("RESULTS", longArrayOf(0L))
    }

    @Test
    fun `全链 面板点加号进弹窗点泡红茶回填 COM_ALTER`() {
        val (erbc, game) = corpusOrNull() ?: return
        val rig = assemble(erbc, game)
        try {
            // 语料前置：红茶道具（COM_NAME301 CASE 1 会因道具不足合法回落「泡茶」并清变体，
            // 玩家报的场景以有红茶为前提，同探针）。
            rig.rt.store.writeInt("ITEM", longArrayOf(ITEM_BLACK_TEA), 1L)

            // ---- 第 1 页：面板（主菜单路径，反向证明锚） ----
            val (plusValue, panel) = panelRound(rig)
            assertEquals(-301L, plusValue, "面板等待必须吃下「+」按钮的点击值 -301")
            assertTrue(
                panel.gate?.contains("plus301=true") == true,
                "面板页世代闸必须放行 [+](值 -301) —— 主菜单点击路径的反向证明锚，现场=${panel.gate}",
            )
            assertTrue(
                panel.chips.any { it.submitValue == "-301" } &&
                    panel.chips.any { it.submitValue == "301" },
                "面板页右栏快照必须含 [+](−301) 与命令钮(301)，实际=${describe(panel.chips)}",
            )

            // ---- 第 2 页：弹窗（真语料 QOL_USERCOM.ERB 全程） ----
            val (vmError, popup, alter) = popupRound(rig)
            val req = popup.request
            assertTrue(
                req?.form == InputRequest.RequestForm.VALUE &&
                    req.type == InputRequest.InputType.INT &&
                    req.mouseInput && req.hasDef && req.defInt == -1L,
                "弹窗等待必须是 INPUT -1, 1 的真身（VALUE+INT+鼠标+默认-1），实际=$req",
            )
            assertTrue(
                popup.gate?.contains("gen1=true") == true,
                "弹窗页世代闸必须放行 [泡红茶](值 1) —— 判假即玩家「点了没反应」，现场=${popup.gate}",
            )
            assertEquals(
                listOf(0L, 1L, 99L),
                popup.chips.mapNotNull { (it.value as? Long) }.sorted(),
                "弹窗页右栏快照 = 弹窗三钮（换页当帧跟手），实际=${describe(popup.chips)}",
            )
            val vmTag = vmError?.let { "${it::class.simpleName}: ${it.message?.take(200)}" } ?: "PASS"
            println("PROBE panel=${panel.gate} chips=${describe(panel.chips)}")
            println("PROBE popupGate=${popup.gate} popupChips=${describe(popup.chips)}")
            println("PROBE VM=$vmTag COM_ALTER_301=$alter")
            assertEquals(
                1L,
                alter,
                "点 [泡红茶] 后 COM_ALTER_301 期望 1（MOUSE_BTN 闸 + INRANGE + SETVAR 全链），" +
                    "实际 $alter；VM=$vmTag",
            )

            // 反馈面：面板按钮名按变体读回。
            val label = panelLabel(rig)
            println("PROBE COM_NAME301=$label")
            assertEquals("泡红茶", label, "COM_ALTER_301=1 时面板按钮名期望「泡红茶」，实际「$label」")
        } finally {
            rig.sink.close()
        }
    }
}
