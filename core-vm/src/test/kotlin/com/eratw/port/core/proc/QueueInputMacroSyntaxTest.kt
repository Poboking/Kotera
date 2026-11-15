package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * era 指令集**括号重复语法**的回归闸（原版 `EmueraConsole.parseInput`，
 * `EmueraConsole.cs:1546-1623`；调用点 `:1425-1428`，在切段 `:1429` 之前）。
 *
 * 钉五件事：
 * 1. 官方手册的等价式：`(0\e\n)*3` ≡ `0\e\n0\e\n0\e\n`（`usage.html` §宏 原话）；
 * 2. 只有输入里出现过 `(` 才解析（不带括号时 `\e`/`\n` 走字面路径，逐字不变）；
 * 3. `\e` 自带一次分段、紧随其后的 `\n` 被吞（原版 flag）；
 * 4. 反斜杠转义表（`\`+其他 = 该字符本身）；
 * 5. 鼠标路不解析（原版 `changedByMouse: true` 整串一条，`:1406-1408`）。
 *
 * 自包含、不依赖语料。
 */
class QueueInputMacroSyntaxTest {

    private fun intRequest(): InputRequest = InputRequest(type = InputRequest.InputType.INT)

    private fun strRequest(): InputRequest = InputRequest(type = InputRequest.InputType.STR)

    @Test
    fun `手册等价式 括号重复与手写展开逐段一致`() {
        val bracketed = QueueInputSink()
        bracketed.submit("""(0\e\n)*3""")
        val literal = QueueInputSink()
        literal.submit("""0\e\n0\e\n0\e\n""")

        assertEquals(
            literal.pendingCount,
            bracketed.pendingCount,
            """手册：`(0\e\n)*3` 与 `0\e\n0\e\n0\e\n` 相同""",
        )
        repeat(3) { round ->
            assertEquals(0L, bracketed.waitInput(intRequest()).intValue, "第 ${round + 1} 段")
            assertEquals(0L, literal.waitInput(intRequest()).intValue, "第 ${round + 1} 段（手写）")
            assertTrue(bracketed.consumeMesSkip(), "每段都带跳过标记")
            assertTrue(literal.consumeMesSkip())
        }
        // 尾分隔符 ⇒ 多出一次空提交（原版 StringSplitOptions.None 的口径），两边一致。
        assertEquals("", bracketed.waitInput(strRequest()).strValue)
        assertEquals("", literal.waitInput(strRequest()).strValue)
    }

    @Test
    fun `不含括号时不解析 逐字走切段`() {
        val sink = QueueInputSink()
        sink.submit("""a\e\nb""")
        assertEquals(2, sink.pendingCount, "没有 '(' ⇒ 不叫 parseInput，直接按字面分隔符切段")
        assertEquals("a", sink.waitInput(strRequest()).strValue)
        assertTrue(sink.consumeMesSkip())
        assertEquals("b", sink.waitInput(strRequest()).strValue)
    }

    @Test
    fun `嵌套括号重复 先a后b 跑两轮`() {
        val sink = QueueInputSink()
        sink.submit("""((a\e\n)(b\e\n))*2""")
        // 4 段内容 + 尾分隔符的一次空提交。
        assertEquals(5, sink.pendingCount)
        assertEquals("a", sink.waitInput(strRequest()).strValue)
        assertEquals("b", sink.waitInput(strRequest()).strValue)
        assertEquals("a", sink.waitInput(strRequest()).strValue)
        assertEquals("b", sink.waitInput(strRequest()).strValue)
        assertEquals("", sink.waitInput(strRequest()).strValue)
    }

    @Test
    fun `跳过标记自带分段 紧随的分隔符被吞`() {
        val sink = QueueInputSink()
        // `\e` 之后紧跟的 `\n` 被吞（解析器 flag），再一个 `\n` 才是真分隔。
        sink.submit("""(x\e\n\n)""")
        assertEquals(3, sink.pendingCount, "x 段 + 一个空段 + 尾分隔符空段")
        assertEquals("x", sink.waitInput(strRequest()).strValue)
        assertTrue(sink.consumeMesSkip())
        assertEquals("", sink.waitInput(strRequest()).strValue)
    }

    @Test
    fun `反斜杠转义 反斜杠加其他字符取字符本身`() {
        val sink = QueueInputSink()
        // `\\` ⇒ 一个反斜杠字面量；末尾 `\e` 是跳过标记。
        sink.submit("""(a\\b\e)""")
        assertEquals("a\\b", sink.waitInput(strRequest()).strValue)
        assertTrue(sink.consumeMesSkip())
    }

    @Test
    fun `鼠标路整串不解析`() {
        val sink = QueueInputSink()
        // 若被当成指令集解析，`(0)*3` 会展开成 `000`；鼠标路必须原样一条。
        sink.submitButtonClick("(0)*3")
        assertEquals(1, sink.pendingCount, "changedByMouse: true 不做指令集解析（:1406-1408）")
        assertEquals("(0)*3", sink.waitInput(strRequest()).strValue, "原文照交")
        // 段内 `\e` 的剥离对鼠标路**同样生效**（C# 的段循环不分路径，:1435-1438），只是不切段。
        val withSkip = QueueInputSink()
        withSkip.submitButtonClick("""(0\e)*3""")
        assertEquals("(0)*3", withSkip.waitInput(strRequest()).strValue, "字面 \\e 被剥掉，但没有分段")
        assertTrue(withSkip.consumeMesSkip())
    }

    @Test
    fun `未闭合括号 按已解析内容收尾`() {
        val sink = QueueInputSink()
        sink.submit("(ab")
        assertEquals(1, sink.pendingCount)
        assertEquals("ab", sink.waitInput(strRequest()).strValue)
    }

    @Test
    fun `重复次数有安全上限`() {
        val sink = QueueInputSink()
        sink.submit("""(x\e\n)*9999""")
        // 上限 99 轮 ⇒ 99 段内容 + 尾分隔符空段。
        assertEquals(100, sink.pendingCount, "n 被夹到 SYNTAX_REPEAT_MAX，防手滑把队列灌爆")
    }
}
