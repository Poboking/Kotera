package com.eratw.port.core.screen

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink

/**
 * 语义页装配器（HYB-1 前置装置的**唯一生产入口**）。
 *
 * 拉取式：调用方（UI 轮询 / 等待点回调）调 [refresh] 一次，本类从控制台现取三样真源 ——
 * 已发布帧 `frameSnapshot()`、等待点冻结的可点集合 `publishedOptions()`、等待请求
 * `currentWait()` —— 归约成 [SemanticScreen] 交给 [host]。
 *
 * ## 为什么是拉取而不是拦截打印
 *
 * 打印路径是热路径（一回合几千次 `print`），在它上面做归约会把每一页的重排成本压进 VM
 * 线程，而且一旦归约抛异常就会污染语句执行。拉取式把成本放在界面的刷新节拍上（与既有
 * 右侧选项栏的 `OPTION_SCAN_MS` 轮询同一处），出问题只影响这一屏的结构化投影，原版文本流
 * 一个字节都不动 —— 这就是「兜底等价」判据的落地方式。
 *
 * ## 失败口径：静默降级到「没有语义页」
 *
 * 任何一步读数或归约抛异常 ⇒ 返回 null 并清掉当前页（界面退回纯文本渲染），**不**上抛、
 * 不打印、不改任何原版状态。语义拦截是增强面，不是关键路径。
 *
 * @param isAwaiting 输入队列是否正在等待。false 时清空当前页 —— 本类不知道队列状态，
 *   也不去猜（`currentWait()` 只记「最近一次」，不是「正在进行中」）。
 */
class SemanticScreenFeed(
    private val sink: LineBufferConsoleSink,
    private val host: SemanticScreenHost,
    private val scopeOf: () -> ScreenScope = { ScreenScope.UNSCOPED },
    private val isAwaiting: () -> Boolean = { true },
) {
    /**
     * 重取一次并发布。返回**盖章后的新页**（世代已推进）或 null（没有等待 / 内容未变 /
     * 读数失败）。调用方拿返回值决定是否刷新界面，拿 null 时什么也不做。
     */
    fun refresh(): SemanticScreen? {
        if (!isAwaiting()) {
            host.clear()
            return null
        }
        return runCatching {
            val wait = sink.currentWait() ?: return@runCatching null
            val page = SemanticPage(scopeOf(), wait.toScreenRequest(), sourceLines())
            host.offer(SemanticReducer.reduce(page))
        }.getOrElse {
            // 增强面的读数失败不得影响原版：清掉当前页，界面自然会退回文本流。
            host.clear()
            null
        }
    }

    /**
     * 把「已发布帧 + 本页可点集合」合成为归约原料。
     *
     * 正文取 [LineBufferConsoleSink.frameSnapshot]（与玩家眼前那一帧同源，不是 `lines()`
     * 的活表：`lines()` 会在帧冻结期间继续变，拿它归约会得到半新半旧的页）。
     * 可点集合取 `publishedOptions()`（世代 + 取值类型过滤后），按 `lineSerial` 归行。
     *
     * 帧里没有对应行的选项（HTML 浮岛上的按钮）单独成一行、原文为空 —— 宁可多一行，
     * 不许丢一颗按钮（无损判据）。
     */
    private fun sourceLines(): List<SemanticSourceLine> {
        val frame = sink.frameSnapshot()
        val options = sink.publishedOptions()
        if (frame.isEmpty()) {
            return options.groupBy { it.lineSerial }
                .map { (serial, list) -> SemanticSourceLine(serial, "", list.toControls()) }
        }
        val bySerial = options.groupBy { it.lineSerial }
        val frameSerials = HashSet<Long>(frame.size * 2)
        val out = ArrayList<SemanticSourceLine>(frame.size + bySerial.size)
        for (line in frame) {
            frameSerials += line.serial
            out += SemanticSourceLine(
                serial = line.serial,
                text = sink.textOf(line),
                controls = bySerial[line.serial].orEmpty().toControls(),
            )
        }
        for ((serial, list) in bySerial) {
            if (serial in frameSerials) continue
            out += SemanticSourceLine(serial, "", list.toControls())
        }
        return out
    }

    private fun List<ConsoleOption>.toControls(): List<SemanticControl> =
        map { it.toSemanticControl() }.distinctBy { it.controlId }
}

/**
 * 按钮快照 → 协议控件。
 *
 * `controlId` 用**源按钮身份**（`groupId`）而不是值或下标：同一颗按钮折行成多行时仍是一个
 * id（界面只画一次），两次 `PRINTBUTTON` 即使值相同也是两个 id（界面画两次，与原版一致）。
 * `groupId` 未知（手工构造的快照）时退化为 `v<提交值>`，并靠 `distinctBy` 去重。
 */
internal fun ConsoleOption.toSemanticControl(): SemanticControl = SemanticControl(
    controlId = if (groupId >= 0) "b$groupId" else "v:$submitValue",
    label = label,
    submitValue = submitValue,
    ordinal = ordinal,
    colorRgb = colorRgb,
    swatch = isColorSwatch,
)

/**
 * 等待请求 → 协议描述。
 *
 * 形态与取值通道逐个显式映射（不按 `name` 字符串转），这样语句层枚举增项时编译期就会
 * 报错，而不是在运行时静默落进某个 default 分支。默认值：`hasDef == false` 时是 null
 * （无默认值 ≠ 默认空串，见 [ScreenRequest.defaultText]）。
 */
internal fun InputRequest.toScreenRequest(): ScreenRequest = ScreenRequest(
    form = when (form) {
        InputRequest.RequestForm.VALUE -> ScreenForm.VALUE
        InputRequest.RequestForm.ENTER_KEY -> ScreenForm.ENTER_KEY
        InputRequest.RequestForm.ANY_KEY -> ScreenForm.ANY_KEY
        InputRequest.RequestForm.VOID -> ScreenForm.VOID
        InputRequest.RequestForm.ANY_VALUE -> ScreenForm.ANY_VALUE
        InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY -> ScreenForm.PRIMITIVE_MOUSE_KEY
    },
    valueType = when (type) {
        InputRequest.InputType.INT -> ScreenValueType.INT
        InputRequest.InputType.STR -> ScreenValueType.STR
    },
    oneInput = oneInput,
    defaultText = when {
        !hasDef -> null
        type == InputRequest.InputType.INT -> defInt.toString()
        else -> defStr
    },
    timeLimitMs = timeLimit,
    acceptsMouse = mouseInput,
)
