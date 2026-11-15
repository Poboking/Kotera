package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PressEnterKey` 多段切分（族指令集语义）的回归闸（`EmueraConsole.cs:146` + `:1429-1433`）。
 *
 * 原版把一次键盘提交按 `spliter = { "\\n", "\r\n", "\n", "\r" }` 切成多段
 * （首项是**字面**反斜杠 + `n`，不是 LF），再逐段 `RunEmueraProgram` ⇒ 一次回车可跨
 * 多处等待连推。鼠标提交（`changedByMouse: true`）整串不切。
 *
 * 本闸钉住移植端的落点：`QueueInputSink.submit` 入队时切段、每段一条条目；
 * `submitButtonClick` 整串不切。自包含、不依赖语料。
 */
class QueueInputSegmentsTest {

    private fun intRequest(): InputRequest = InputRequest(type = InputRequest.InputType.INT)

    private fun strRequest(): InputRequest = InputRequest(type = InputRequest.InputType.STR)

    @Test
    fun `字面反斜杠+n 多次提交跨多处等待逐段消费`() {
        val sink = QueueInputSink()
        assertTrue(sink.submit("""1\e\n2\e\n"""), "整串一次入队")
        assertEquals(3, sink.pendingCount, """两段内容 + 末尾分隔符后的空段 = 三条条目（split 的 None 语义）""")
        assertEquals(1L, sink.waitInput(intRequest()).intValue, "第一段按指令集串解析")
        assertTrue(sink.consumeMesSkip(), "段内跳过标记逐段生效")
        assertEquals(2L, sink.waitInput(intRequest()).intValue, "第二段紧跟着被下一次等待吃掉")
        assertTrue(sink.consumeMesSkip())
        assertEquals("", sink.waitInput(strRequest()).strValue, "末尾空段 = 一次空回车（原版 RunEmueraProgram(空串)）")
        assertEquals(0, sink.pendingCount)
    }

    @Test
    fun `四种分隔符各自切段`() {
        val literalNewline = QueueInputSink()
        literalNewline.submit("""a\nb""")
        assertEquals(2, literalNewline.pendingCount, "字面反斜杠+n 是首项分隔符（:146）")
        assertEquals("a", literalNewline.waitInput(strRequest()).strValue)
        assertEquals("b", literalNewline.waitInput(strRequest()).strValue)

        val crlf = QueueInputSink()
        crlf.submit("a\r\nb")
        assertEquals(2, crlf.pendingCount, "CRLF 必须先于单字符 CR/LF 匹配，否则会多切一段")
        assertEquals("a", crlf.waitInput(strRequest()).strValue)
        assertEquals("b", crlf.waitInput(strRequest()).strValue)

        val lf = QueueInputSink()
        lf.submit("a\nb")
        assertEquals(2, lf.pendingCount)
        assertEquals("a", lf.waitInput(strRequest()).strValue)
        assertEquals("b", lf.waitInput(strRequest()).strValue)

        val cr = QueueInputSink()
        cr.submit("a\rb")
        assertEquals(2, cr.pendingCount)
        assertEquals("a", cr.waitInput(strRequest()).strValue)
        assertEquals("b", cr.waitInput(strRequest()).strValue)
    }

    @Test
    fun `空段按 StringSplitOptions None 保留`() {
        val sink = QueueInputSink()
        sink.submit("a\n\nb")
        assertEquals(3, sink.pendingCount, "连续分隔符切出中间空段，原版同样逐段推进")
        assertEquals("a", sink.waitInput(strRequest()).strValue)
        assertEquals("", sink.waitInput(strRequest()).strValue)
        assertEquals("b", sink.waitInput(strRequest()).strValue)
    }

    @Test
    fun `不含分隔符的提交仍是单段`() {
        val sink = QueueInputSink()
        sink.submit("plain")
        assertEquals(1, sink.pendingCount, "零分隔符路径不改变既有单条语义")
        assertEquals("plain", sink.waitInput(strRequest()).strValue)
    }

    @Test
    fun `鼠标路整串不切`() {
        val sink = QueueInputSink()
        assertTrue(sink.submitButtonClick("a\nb"))
        assertEquals(1, sink.pendingCount, "changedByMouse: true 整串一次（MainWindow.cs:1421-1423）")
        assertEquals("a\nb", sink.waitInput(strRequest()).strValue, "鼠标提交不做指令集切分")
    }

    @Test
    fun `跳过标记与切分叠加 各段自带标记`() {
        val sink = QueueInputSink()
        sink.submit("""0\e\n1""")
        assertEquals(0L, sink.waitInput(intRequest()).intValue, """首段剥掉字面反斜杠+e 后是 0""")
        assertTrue(sink.consumeMesSkip(), """首段命中字面反斜杠+e ⇒ MesSkip（:1435-1438）""")
        assertEquals(1L, sink.waitInput(intRequest()).intValue, """次段无标记、照常解析""")
        assertFalse(sink.consumeMesSkip(), "次段既无 keySkip 也无标记 ⇒ 不置位（逐段独立判定，:1432）")
    }

    @Test
    fun `keySkip 只作用于首段`() {
        val sink = QueueInputSink()
        sink.submit("a\nb", mesSkip = true)
        assertEquals("a", sink.waitInput(strRequest()).strValue)
        assertTrue(sink.consumeMesSkip(), "首段带 keySkip（:1373 循环前置一次）")
        assertEquals("b", sink.waitInput(strRequest()).strValue)
        assertFalse(sink.consumeMesSkip(), "次段已复位（:1456 每段末尾 MesSkip = false）")
    }

    @Test
    fun `readAnyKey 逐段消费`() {
        val sink = QueueInputSink()
        sink.submit("x\ny")
        assertEquals(2, sink.pendingCount)
        sink.readAnyKey()
        assertEquals(1, sink.pendingCount, "WAIT 家族一次只吃一段")
        sink.readAnyKey()
        assertEquals(0, sink.pendingCount)
    }
}
