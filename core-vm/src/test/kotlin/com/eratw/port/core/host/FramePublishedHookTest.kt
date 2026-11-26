package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals


/**
 * 帧发布回调（[LineBufferConsoleSink.onFramePublished]）的接线钉子（PLAY-INPUT-LATE · 2026-11-23）。
 *
 * 宿主拿这个回调当「原版 `DoEvents` 时机」用：脚本与消息泵同线程时，执行期点的那一下
 * 躺在 Windows 消息队列里，到脚本跑进 `WaitInput` 的那轮 `DoEvents`（`EmueraConsole.cs:1040-1082`）
 * 才被取出并**按当刻版面**解释。移植侧的等价时刻 = 输入等待入口
 * （`noteInputWait` 收尾：行表已 flush、按钮世代已定、帧口径选项快照已换新），
 * 以及页中保活真的发了一帧（`publishProgressFrame`）。
 *
 * 钉三件事：每个等待入口**恰好一次**、回调时选项快照已是**当刻**那一页、页中保活各算一次。
 */
class FramePublishedHookTest {

    private fun sink(): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = 40, maxLines = 200, frameGateEnabled = true)

    private fun intValueWait(line: Long = 1L): InputRequest =
        InputRequest(type = InputRequest.InputType.INT, scriptLine = line)

    /** 每个等待入口一次，且回调那一刻帧口径选项集已经是这一页的（不是上一页的）。 */
    @Test
    fun `每个等待入口恰好一次且选项已换成当刻那一页`() {
        val s = sink()
        var fires = 0
        var optionsAtFire: List<String> = emptyList()
        var sizeAtFire = -1
        s.onFramePublished = {
            fires++
            optionsAtFire = s.publishedOptions().map { it.submitValue }
            sizeAtFire = s.frameSnapshot().size
        }

        // 第一页：两个按钮 + 一行文字，等一次取值。
        s.printButton("[A]", "100")
        s.newLine()
        s.printButton("[B]", "200")
        s.newLine()
        s.publishFrame()
        assertEquals(0, fires, "publishFrame 本身不是回放时机（那时选项集还是空的）")
        s.noteInputWait(intValueWait(1L))
        assertEquals(1, fires, "输入等待入口必须通知一次")
        assertEquals(listOf("100", "200"), optionsAtFire, "回调时选项集必须已是这一页的")
        assertEquals(2, sizeAtFire, "回调时行表已 flush 上屏")

        // 第二页：换一屏按钮，再等一次 —— 仍然一次，且读到的是新一页。
        s.holdFrame()
        s.deleteLine(2)
        s.printButton("[C]", "300")
        s.newLine()
        s.publishFrame()
        s.noteInputWait(intValueWait(2L))
        assertEquals(2, fires, "第二个等待入口再通知一次")
        assertEquals(listOf("300"), optionsAtFire, "必须是当刻那一页（不是上一页的 [A]/[B]）")

        // 任意键等待也算一次（非取值等待：选项集清空，回放只看文本那一路）。
        s.holdFrame()
        s.printLine("story")
        s.publishFrame()
        s.noteInputWait(
            InputRequest(
                type = InputRequest.InputType.STR,
                form = InputRequest.RequestForm.ENTER_KEY,
            ),
        )
        assertEquals(3, fires, "回车等待同样是一次 DoEvents 时机")
    }

    /** 页中保活真的发布了一帧时也算一次（长计算段的 150ms 静默 + 500ms 节流路径）。 */
    @Test
    fun `页中保活发布一帧时通知一次`() {
        var now = 10_000L
        val s = LineBufferConsoleSink(
            clientWidth = 40,
            maxLines = 200,
            frameGateEnabled = true,
            progressClockMs = { now },
        )
        var fires = 0
        s.onFramePublished = { fires++ }

        s.printLine("a")
        s.publishFrame()
        s.noteInputWait(intValueWait())
        assertEquals(1, fires)
        s.holdFrame()
        s.printLine("b")
        now += 200L // 越过 PROGRESS_QUIET_MS
        s.publishProgressFrame()
        assertEquals(2, fires, "页中保活发布一帧 ⇒ 宿主多一个回放时机")

        // 节流窗内不再重复发布 ⇒ 也不该多通知。
        now += 10L
        s.printLine("c")
        s.publishProgressFrame()
        assertEquals(2, fires, "节流窗内的保活不发帧、不通知")
    }
}
