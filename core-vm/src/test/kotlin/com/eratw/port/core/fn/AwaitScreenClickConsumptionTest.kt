package com.eratw.port.core.fn

import com.eratw.port.core.host.AndroidKeyCodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QOL AWAIT 轮询屏（`qol_MAP.ERB:43` 的 `SETTEXTBOX MOUSEB()` + `AWAIT_UI_INPUT`）上
 * **一次画面按钮点击只能被消费一次** 的凭证契约（玩家反馈：预览图切换/地图切换这类
 * toggle 按钮「点击回退」——翻过去又翻回来）。
 *
 * ## 缺陷形态（修复前的凭证组合）
 *
 * `GameHost.submitInternal` 旧实现给**每次**提交统一补 `VK_RETURN` 沿变。按钮点击于是
 * 同时留下两份独立凭证：
 * - 左键支：`VK_LBUTTON` 沿变 + `MOUSEB()` 指向串（触摸现场 `updatePointer` 已接线）；
 * - 回车支：`VK_RETURN` 沿变 + 镜像值——`AWAIT_UI_INPUT` 每轮循环顶的
 *   `SETTEXTBOX MOUSEB()` 会把指向串写进镜像，回车支 `GETTEXTBOX()` 读到的正是同一个值。
 *
 * `AWAIT_UI_INPUT`（`qol_MAP.ERB:1105-1139`）第一轮左键支命中 RETURN 后，`VK_RETURN`
 * 沿变还挂在槽里；下一轮回车支 `GETKEYTRIGGERED(13)` 报出、`GETTEXTBOX()` 读到同值 ——
 * toggle 型按钮（`[预览图切换]` 1997、`[地图切换]`）翻过去又翻回来。
 * [KeyEventInputStateClickIntentTest] 的意图闸只挡「补写制造第二次触发」，挡不住
 * 「镜像里已有的同值串被回车支读走」——沿变才是回车支的触发源。
 *
 * ## 修复后的凭证组合（本类钉住）
 *
 * 画面按钮点击不再补 `VK_RETURN` 沿变（app 侧形状闸
 * `MouseButtonWireGateTest` 钉守卫本体；原版按钮点击走 `MainWindow.cs:824-849` 的
 * `mainPicBox_MouseDown`，不经过 `PressEnterKey`、不产生回车）。左键支成为
 * AWAIT 屏按钮点击的**唯一**消费路；回车支（`GETKEYTRIGGERED(13)` + `GETTEXTBOX()`）
 * 只服务真实键盘键入（`submit` 路径照旧补沿变）。
 *
 * 这里不 mock app：直接在 [KeyEventInputState] 上重放两轮 `AWAIT_UI_INPUT` 的查询序
 * （沿变读数 → 意图读点），把「凭证组合 → 消费次数」的映射钉死。
 */
class AwaitScreenClickConsumptionTest {

    private class RecordingMirror(initial: String = "") : InputMirrorPort {
        var value: String = initial
        var writes: Int = 0

        override fun read(): String = value

        override fun write(text: String) {
            value = text
            writes++
        }
    }

    /** 一次「按下并立即抬起」= `GameHost.fireVirtualKeyEdge`。 */
    private fun edge(state: KeyEventInputState, vk: Int) {
        state.onKeyDown(vk)
        state.onKeyUp(vk)
    }

    /**
     * 修复后的按钮点击凭证：`VK_LBUTTON` 沿变（`handleConsoleTap` 开头）+ 单槽点击意图 +
     * 镜像值（`mirrorTextBox`）；**没有** `VK_RETURN` 沿变。`MOUSEB()` 的指向串由
     * `updatePointer` 写在渲染侧缓存，这里用「镜像同值」代替（`SETTEXTBOX MOUSEB()` 写入
     * 的正是它）。
     */
    @Test
    fun `按钮点击只被左键支消费 回车支无沿变不触发`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "")
        state.mirror = mirror
        edge(state, AndroidKeyCodes.VK_LBUTTON)
        state.noteClickIntent("1997")

        // 轮 1：SETTEXTBOX MOUSEB() 把指向串写进镜像（MOUSEB 已接线 ⇒ 与点击值同串）。
        mirror.value = "1997"
        assertEquals(
            -32767,
            state.keyState(AndroidKeyCodes.VK_LBUTTON),
            "左键沿变在 ⇒ 左键支 GETKEYTRIGGERED(1) 命中，MOUSEB() 读到点击值",
        )
        assertEquals(0, mirror.writes, "镜像已握着同值 ⇒ 左键读点只作废意图、不补写")

        // 轮 2（MAP_LOOP 重绘后）：SETTEXTBOX MOUSEB() 再次写镜像（指向残留）；
        // 回车支查询 GETKEYTRIGGERED(13) —— 按钮点击不补回车沿变 ⇒ 不触发。
        mirror.value = "1997"
        assertEquals(
            0,
            state.keyState(AndroidKeyCodes.VK_RETURN),
            "回车沿变必须缺席：它在的话回车支 GETTEXTBOX() 读到同值，toggle 按钮被翻第二次",
        )
        assertEquals(0, mirror.writes, "全程无补写：一次点击一份凭证、一份消费")
    }

    /**
     * 对照组（缺陷形态的可观测证据）：若按钮点击仍补回车沿变（修复前的统一补法），
     * 同一笔点击的第二轮回车支照样触发 —— 双消费成立。这条不钉行为、只钉
     * 「沿变是回车支唯一触发源」的判据本身，防止将来有人把触发源改成别的读数。
     */
    @Test
    fun `对照组 补了回车沿变回车支就会触发（缺陷形态）`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "1997")
        state.mirror = mirror
        edge(state, AndroidKeyCodes.VK_LBUTTON)
        edge(state, AndroidKeyCodes.VK_RETURN)
        state.noteClickIntent("1997")

        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_LBUTTON))
        assertEquals(
            -32767,
            state.keyState(AndroidKeyCodes.VK_RETURN),
            "沿变在 ⇒ 回车支触发、GETTEXTBOX() 读到镜像里的同值 —— 这就是双消费",
        )
    }

    /** 键盘键入路径不受影响：`submit` 照旧补 `VK_RETURN` 沿变，回车支消费键入值。 */
    @Test
    fun `键盘键入仍由回车支消费`() {
        val state = KeyEventInputState()
        val mirror = RecordingMirror(initial = "")
        state.mirror = mirror
        // submit("2003")：无 LBUTTON 沿变（不走 handleConsoleTap），有 RETURN 沿变 + 镜像值。
        edge(state, AndroidKeyCodes.VK_RETURN)
        mirror.value = "2003"

        assertEquals(
            0,
            state.keyState(AndroidKeyCodes.VK_LBUTTON),
            "键盘键入不产生左键沿变 ⇒ 左键支不命中",
        )
        assertEquals(-32767, state.keyState(AndroidKeyCodes.VK_RETURN))
        assertEquals(0, mirror.writes, "镜像已被 UI 写成键入值：意图槽为空，无补写")
    }
}
