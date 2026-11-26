package com.eratw.port.core.proc

import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ConsoleBeginHost] 的两个游戏相位通知后端（`onNewGameStartedHook` / `onSaveLoadedHook`）。
 *
 * 这两条是 mod 侧 [com.eratw.port.modapi.EraMod.onNewGame] / `onSaveLoaded` 的唯一生产通路：
 * BEGIN 状态机（[BeginFlow.stepFirst] / [BeginFlow.stepEventLoad]）调宿主，宿主转发到
 * mod 运行时。本用例钉三件事：
 * 1. 缺省 null = 无 mod 运行时（总闸关）时完全无动作、不抛；
 * 2. 接上之后恰好转发一次；
 * 3. **后端抛错不许外泄** —— BEGIN 状态机的泵（`pumpUntilTerminal`）只 catch `CodeEE`，
 *    从这里漏出去的异常会直接变成启动失败。
 */
class ConsoleBeginHostLifecycleHookTest {

    private fun host() = ConsoleBeginHost(
        input = QueueInputSink(),
        console = LineBufferConsoleSink(clientWidth = 10),
    )

    @Test
    fun `缺省无后端时两个通知都是空操作`() {
        val host = host()
        assertNull(host.onNewGameStartedHook)
        assertNull(host.onSaveLoadedHook)
        // 不该抛。
        host.onNewGameStarted()
        host.onSaveLoaded()
    }

    @Test
    fun `接上后端后各转发一次`() {
        val host = host()
        var newGame = 0
        var saveLoaded = 0
        host.onNewGameStartedHook = { newGame++ }
        host.onSaveLoadedHook = { saveLoaded++ }

        host.onNewGameStarted()
        host.onSaveLoaded()

        assertEquals(1, newGame)
        assertEquals(1, saveLoaded)
    }

    @Test
    fun `后端抛错被本层吞掉不外泄`() {
        val host = host()
        host.onNewGameStartedHook = { throw IllegalStateException("新局通知故意抛错") }
        host.onSaveLoadedHook = { throw IllegalStateException("读档通知故意抛错") }

        // 都不该抛：抛到 BEGIN 状态机泵里就是启动失败。
        host.onNewGameStarted()
        host.onSaveLoaded()
    }
}
