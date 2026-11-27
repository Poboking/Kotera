package com.eratw.port.core.proc

import com.eratw.port.core.fn.SkipStateSlot
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 系统输入边界的消息跳过态（原版 `Console.MesSkip`）口径。
 *
 * 病根（2026-11-26 玩家反馈）：双击快进后，下一回合每条指令的口上继续被跳过，原版
 * 「指令后等一次按键」形同失效。原版 `PressEnterKey` 的跳过循环只活到**撞上取值等待**
 * 为止，循环后无条件 `MesSkip = false`（`EmueraConsole.cs:1451-1456`）；商店/指令菜单
 * 这类系统输入是 `InputType.IntValue` ⇒ `NeedValue == true`（`InputRequest.cs:31-41`），
 * 正是这个边界。移植侧 sem 层只在脚本取值等待清标志（`StatementExecutor.waitInput`），
 * BEGIN 状态机的系统等待绕过了 sem 层 ⇒ 跳过态跨回合存活。
 *
 * 本类钉 [ConsoleBeginHost] 两个系统入口：
 * - [ConsoleBeginHost.awaitShopInput]（四类系统取值等待的共同实现）：进入前清零，
 *   进入后按本次提交的 keySkip 续上（宏串 `\e` 的快进不能被误伤）；
 * - [ConsoleBeginHost.readAnyKey]（指令收尾 `Process.cs:2450-2453` 的 `setWait()`）：
 *   原版 `NeedValue=false` ⇒ 跳过态下被跳过循环直接跑过，不阻塞。
 */
class SystemWaitSkipBoundaryTest {

    private fun host(slot: SkipStateSlot?, sink: QueueInputSink = QueueInputSink()): ConsoleBeginHost =
        ConsoleBeginHost(
            input = sink,
            console = LineBufferConsoleSink(clientWidth = 40),
        ).apply { skipStateSlot = slot }

    /** 系统取值等待（商店/指令/标题/ABLUP）进入前清零：一次快进不得跨回合存活。 */
    @Test
    fun `系统取值等待入口清零跳过态`() {
        val slot = SkipStateSlot().apply { mesSkip = true }
        val sink = QueueInputSink()
        val h = host(slot, sink)
        sink.submit("7")
        assertEquals(7L, h.awaitShopInput(), "等待仍照常交付商店/指令选择值")
        assertFalse(slot.mesSkip, "NeedValue 系统等待边界按原版 :1456 清零")
    }

    /** 本次提交带的 keySkip 照 sem 口径续上：宏串 `0\\e` 的快进语义不能丢。 */
    @Test
    fun `系统等待后按本次提交续上跳过态`() {
        val slot = SkipStateSlot()
        val sink = QueueInputSink()
        val h = host(slot, sink)
        sink.submit("7", mesSkip = true)
        h.awaitShopInput()
        assertTrue(slot.mesSkip, "提交本身带 keySkip ⇒ 下一段口上继续可被快进（原版 :1373/:1451-1455）")
    }

    /** 指令收尾的 `ReadAnyKey`（NeedValue=false）在跳过态下直接放行，且跳过态留到系统等待清零。 */
    @Test
    fun `跳过态下指令收尾等待不阻塞`() {
        val slot = SkipStateSlot().apply { mesSkip = true }
        val h = host(slot)
        val worker = Thread { h.readAnyKey() }
        worker.isDaemon = true
        worker.start()
        worker.join(1_000L)
        assertFalse(worker.isAlive, "原版 :1451-1455：MesSkip 时 ReadAnyKey 被空提交跑过，不得阻塞")
        assertTrue(slot.mesSkip, "清零点在 NeedValue 系统等待入口，不在这里")
    }

    /** 收尾等待上收到的跳过提交（双击/宏 `\\e`）要续上，供下一段事件脚本快进。 */
    @Test
    fun `指令收尾等待按本次提交续上跳过态`() {
        val slot = SkipStateSlot()
        val sink = QueueInputSink()
        val h = host(slot, sink)
        sink.submit("", mesSkip = true)
        h.readAnyKey()
        assertTrue(slot.mesSkip, "同 sem 层 readAnyKey 的 consumeMesSkip 口径")
    }
}
