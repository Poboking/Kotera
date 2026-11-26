package com.eratw.port.core.proc

import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 指令链收尾那次按键等待（`Process.cs:2450-2453` 的 `setWait()`）在**生产宿主**上的口径。
 *
 * 原版：凡走 `SOURCE_CHECK` 的指令都等一次（`Process.cs:2417` 无条件置位，`:2449-2455` 消费；
 * `RESULT == 0` 走 `endCallComXX` 的另一支、不经 `SOURCE_CHECK`，故不等）。移植侧照抄后手机上
 * 表现为「每条指令回菜单前多戳一下」—— 2026-11-02 曾把它收窄成「只清掃被拒」，2026-11-22
 * 玩家反馈「口上根本看不见、还得往上翻阅」（那行提示被下个回合的整帧重绘刷掉），于是按原版
 * 全量恢复，收窄降级成设置项「指令后等一次按键」的一档（缺省开 = 原版）。
 *
 * 本类钉的是宿主侧最终语义：总闸开着就**不看指令码、不看 `RESULT`** 一律等，关掉就一律不等。
 * 状态机侧「判定输入在 `endCallComXX` 那一刻取」的口径见 `BeginFlowTest`。
 */
class CommandEndWaitPolicyTest {

    private fun host(wait: Boolean = true) = ConsoleBeginHost(
        input = QueueInputSink(),
        console = LineBufferConsoleSink(clientWidth = 40),
        waitAfterCommand = wait,
    )

    /** 缺省 = 原版：成功、被拒、任何训练码都等一次。 */
    @Test
    fun `总闸缺省开着时每条指令都等一次`() {
        val h = host()
        assertTrue(h.shouldWaitAfterCommand(400, 1L), "移动成功也等：原版口径不看指令码")
        assertTrue(h.shouldWaitAfterCommand(410, -1L), "清掃被拒等：那行提示要钉在屏上")
        assertTrue(h.shouldWaitAfterCommand(82, -1L), "其余被拒指令同样等")
    }

    /** 玩家关掉设置项 = 整条链都不等（含清掃被拒那条老动机）。 */
    @Test
    fun `总闸关掉后谁都不等`() {
        val h = host(wait = false)
        assertFalse(h.shouldWaitAfterCommand(410, -1L))
        assertFalse(h.shouldWaitAfterCommand(400, 1L))
    }

    /** 判定与指令码、`RESULT` 都无关：开关是唯一的自变量。 */
    @Test
    fun `判定只看总闸不看指令码与返回值`() {
        val on = host()
        val off = host(wait = false)
        for (code in intArrayOf(400, 410, 82, 0, 9999)) {
            for (result in longArrayOf(-1L, 0L, 1L)) {
                assertTrue(on.shouldWaitAfterCommand(code, result), "开：$code / $result 要等")
                assertFalse(off.shouldWaitAfterCommand(code, result), "关：$code / $result 不等")
            }
        }
    }
}
