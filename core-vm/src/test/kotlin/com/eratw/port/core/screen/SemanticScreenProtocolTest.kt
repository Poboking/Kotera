package com.eratw.port.core.screen

import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语义拦截协议（HYB-1 前置装置）的判据。
 *
 * 这一组测试钉的是**协议本身的纪律**，不是某个界面的观感：
 * 归约只做确定性判定、无损、确定性、世代校验、提交真值而非标签。
 */
class SemanticScreenProtocolTest {

    private fun valueRequest(): ScreenRequest = ScreenRequest(
        form = ScreenForm.VALUE,
        valueType = ScreenValueType.INT,
    )

    private fun scope(): ScreenScope = ScreenScope("CUSTOM_TERMINAL")

    private fun control(label: String, value: String, id: String = "b1"): SemanticControl =
        SemanticControl(id, label, value)

    private fun page(vararg lines: SemanticSourceLine): SemanticPage =
        SemanticPage(scope(), valueRequest(), lines.toList())

    @Test
    fun `步进器_同行减键加键对归约成步进器`() {
        val screen = SemanticReducer.reduce(
            page(
                SemanticSourceLine(
                    serial = 1,
                    text = "体力  100",
                    controls = listOf(control("－", "101", "b1"), control("＋", "102", "b2")),
                ),
            ),
        )
        val stepper = assertIs<SemanticStepper>(screen.elements.single())
        assertEquals("体力", stepper.label)
        assertEquals("100", stepper.value)
        assertEquals("b1", stepper.decrease?.controlId)
        assertEquals("b2", stepper.increase?.controlId)
    }

    @Test
    fun `步进器_三颗以上控件不判定为步进器`() {
        val screen = SemanticReducer.reduce(
            page(
                SemanticSourceLine(
                    serial = 1,
                    text = "体力  100 － ＋ 确定",
                    controls = listOf(
                        control("－", "101", "b1"),
                        control("＋", "102", "b2"),
                        control("确定", "9", "b3"),
                    ),
                ),
            ),
        )
        // 判不准就不判：原样落行，三颗控件一颗不少。
        val row = assertIs<SemanticRow>(screen.elements.single())
        assertEquals(3, row.controls.size)
    }

    @Test
    fun `键值行_冒号与双空白两种形态`() {
        val screen = SemanticReducer.reduce(
            page(
                SemanticSourceLine(1, "名前：ホイホイ"),
                SemanticSourceLine(2, "体力　　100"),
            ),
        )
        val keyValues = screen.elements.filterIsInstance<SemanticKeyValue>()
        assertEquals(2, keyValues.size)
        assertEquals(SemanticKeyValue("名前", "ホイホイ"), keyValues[0])
        assertEquals(SemanticKeyValue("体力", "100"), keyValues[1])
    }

    @Test
    fun `键值行_无控件才判定_带按钮的行落行`() {
        val screen = SemanticReducer.reduce(
            page(
                SemanticSourceLine(1, "名前：ホイホイ", listOf(control("编辑", "5", "b9"))),
            ),
        )
        val row = assertIs<SemanticRow>(screen.elements.single())
        assertEquals(1, row.controls.size)
    }

    @Test
    fun `无损_每行原文与每颗按钮提交值都进规范文本`() {
        val screen = SemanticReducer.reduce(
            page(
                SemanticSourceLine(1, "名前：ホイホイ"),
                SemanticSourceLine(
                    2,
                    "体力  100",
                    listOf(control("－", "101", "b1"), control("＋", "102", "b2")),
                ),
                SemanticSourceLine(3, "[27] 示例", listOf(control("[27] 示例", "227", "b3"))),
            ),
        )
        val canonical = screen.canonicalText()
        for (expected in listOf("名前", "ホイホイ", "体力", "100", "[27] 示例", "227", "101", "102")) {
            assertTrue(canonical.contains(expected), "规范文本缺 $expected：$canonical")
        }
        // 平铺控件表与元素同一份事实：步进器两颗 + 按钮行一颗 = 3 颗。
        assertEquals(3, screen.controls.size)
        assertEquals(listOf("b1", "b2", "b3"), screen.controls.map { it.controlId }.distinct())
    }

    @Test
    fun `确定性_同输入同指纹_改一字指纹即变`() {
        fun build(text: String): SemanticScreen = SemanticReducer.reduce(
            page(SemanticSourceLine(1, text, listOf(control("开始", "0", "b1")))),
        )
        assertEquals(build("开始游戏").fingerprint, build("开始游戏").fingerprint)
        // 行尾空白不是语义（归约前已 trimEnd）：界面不该因为排版尾空白刷新一帧。
        assertEquals(build("开始游戏").fingerprint, build("开始游戏  ").fingerprint)
        // 真改了字才换页。
        assertTrue(build("开始游戏").fingerprint != build("开始游侠").fingerprint)
    }

    @Test
    fun `空行不产元素_但正文行一条不少`() {
        val screen = SemanticReducer.reduce(
            page(
                SemanticSourceLine(1, ""),
                SemanticSourceLine(2, "正文"),
                SemanticSourceLine(3, ""),
            ),
        )
        assertEquals(listOf("正文"), screen.elements.filterIsInstance<SemanticRow>().map { it.text })
    }

    @Test
    fun `宿主_同指纹重复发布不推进世代`() {
        val host = SemanticScreenHost()
        val screen = SemanticReducer.reduce(page(SemanticSourceLine(1, "正文")))
        val first = assertNotNull(host.offer(screen))
        assertEquals(1L, first.epoch)
        assertNull(host.offer(screen.copy(epoch = 999)), "同内容必须视为同一屏")
        assertEquals(1L, assertNotNull(host.latest()).epoch)
    }

    @Test
    fun `宿主_内容变化才换页且推进世代`() {
        val host = SemanticScreenHost()
        val a = assertNotNull(host.offer(SemanticReducer.reduce(page(SemanticSourceLine(1, "第一页")))))
        val b = assertNotNull(host.offer(SemanticReducer.reduce(page(SemanticSourceLine(2, "第二页")))))
        assertEquals(a.epoch + 1, b.epoch)
    }

    @Test
    fun `宿主_陈旧世代点按被拒`() {
        val submitted = ArrayList<String>()
        val host = SemanticScreenHost { submitted += it; true }
        val first = assertNotNull(
            host.offer(
                SemanticReducer.reduce(
                    page(SemanticSourceLine(1, "第一页", listOf(control("确定", "1", "b1")))),
                ),
            ),
        )
        assertNotNull(host.offer(SemanticReducer.reduce(page(SemanticSourceLine(2, "第二页")))))
        val stale = host.press("b1", first.epoch)
        assertIs<ScreenPressResult.Stale>(stale)
        assertEquals(first.epoch, stale.expectedEpoch)
        assertTrue(submitted.isEmpty(), "陈旧帧不许写进新页")
    }

    @Test
    fun `宿主_非取值等待任何控件都不可点`() {
        val host = SemanticScreenHost { true }
        assertNotNull(
            host.offer(
                SemanticReducer.reduce(
                    SemanticPage(
                        scope(),
                        ScreenRequest(ScreenForm.ANY_KEY),
                        listOf(SemanticSourceLine(1, "按任意键", listOf(control("确定", "1", "b1")))),
                    ),
                ),
            ),
        )
        val screen = assertNotNull(host.latest())
        assertIs<ScreenPressResult.NotInteractive>(host.press("b1", screen.epoch))
    }

    @Test
    fun `宿主_点按提交按钮真值而不是标签`() {
        val submitted = ArrayList<String>()
        val host = SemanticScreenHost { submitted += it; true }
        // 原版允许印 [27] 提交 227（地图 MAIN_MAP*100+LOCAL），标签不是提交值。
        val screen = assertNotNull(
            host.offer(
                SemanticReducer.reduce(
                    page(SemanticSourceLine(1, "[27] 示例", listOf(control("[27] 示例", "227", "b7")))),
                ),
            ),
        )
        val result = assertIs<ScreenPressResult.Accepted>(host.press("b7", screen.epoch))
        assertEquals("227", result.submitValue)
        assertEquals(listOf("227"), submitted.toList())
    }

    @Test
    fun `宿主_未知控件与未接提交器分别拒绝`() {
        val noSubmitter = SemanticScreenHost(null)
        val screen = assertNotNull(
            noSubmitter.offer(
                SemanticReducer.reduce(page(SemanticSourceLine(1, "页", listOf(control("确定", "1", "b1"))))),
            ),
        )
        assertIs<ScreenPressResult.UnknownControl>(noSubmitter.press("nope", screen.epoch))
        assertIs<ScreenPressResult.Rejected>(noSubmitter.press("b1", screen.epoch))
    }

    @Test
    fun `未知作用域仍归约_但作用域键为空`() {
        val screen = SemanticReducer.reduce(
            SemanticPage(ScreenScope.UNSCOPED, valueRequest(), listOf(SemanticSourceLine(1, "正文"))),
        )
        assertEquals("", screen.scope.key)
        assertEquals(1, screen.elements.size)
    }

    @Test
    fun `feed_从控制台真实取帧并点回原值`() {
        val sink = LineBufferConsoleSink(clientWidth = 40, maxLines = 200)
        sink.printLine("名前：ホイホイ")
        sink.printLine("体力　　100")
        sink.printButton("[0] 开始游戏", 0L)
        sink.noteInputWait(
            InputRequest(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VALUE,
                scriptLine = 1L,
            ),
        )

        val submitted = ArrayList<String>()
        val host = SemanticScreenHost { submitted += it; true }
        val feed = SemanticScreenFeed(sink, host, scopeOf = { scope() })

        val screen = assertNotNull(feed.refresh(), "等待点之后必须能装配出一页")
        assertEquals("CUSTOM_TERMINAL", screen.scope.key)
        assertIs<ScreenRequest>(screen.request)
        assertEquals(ScreenForm.VALUE, screen.request.form)
        // 正文行必须来自已发布帧（键值归约说明 textOf 真的读到了行文本，而不是空壳）。
        assertTrue(
            screen.elements.filterIsInstance<SemanticKeyValue>().any { it.key == "名前" },
            "帧内正文应被归约：${screen.canonicalText()}",
        )
        val button = assertNotNull(screen.controls.firstOrNull(), "打印过的按钮必须出现在本页控件里")
        val result = assertIs<ScreenPressResult.Accepted>(host.press(button.controlId, screen.epoch))
        assertEquals(listOf(button.submitValue), submitted.toList())
        assertEquals("0", result.submitValue)
    }

    @Test
    fun `feed_不在等待时清空当前页`() {
        val sink = LineBufferConsoleSink(clientWidth = 40, maxLines = 200)
        sink.printButton("[0] 开始游戏", 0L)
        sink.noteInputWait(
            InputRequest(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VALUE,
                scriptLine = 1L,
            ),
        )
        val host = SemanticScreenHost { true }
        assertNotNull(SemanticScreenFeed(sink, host).refresh())
        assertNull(SemanticScreenFeed(sink, host, isAwaiting = { false }).refresh())
        assertNull(host.latest())
    }
}
