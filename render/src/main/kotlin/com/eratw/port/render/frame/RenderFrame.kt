package com.eratw.port.render.frame

import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenScene

/**
 * 一帧的**完整交接件**：引擎这一帧想让屏幕显示的全部东西（渲染接管契约，SKIN-1 前置）。
 *
 * ## 它解决的是什么问题
 *
 * 移植侧今天有两个绘制消费者吃同一份输入：
 * - `ScreenComposer`（纯 JVM，产 [com.eratw.port.render.sprite.Raster]，给 headless 对拍与预览用）；
 * - `CanvasConsoleView`（Android `View.onDraw`，给真机用）。
 *
 * 两个消费者吃的是「[ConsoleScreen] + [ScreenScene] + 几何」，但**没有任何一处表达「这一帧归谁画」**。
 * 于是「换一套渲染器接管整块画面」只能靠改壳层、拆 View、逐个槽位打补丁 —— 那不是接管，
 * 那是重写。本类型把那一跳抽成一个值：谁拿到 [RenderFrame]，谁就拥有这一帧的绘制权。
 *
 * ## 契约边界（判据）
 *
 * 1. **完整**：帧里必须有画出整屏所需的一切 —— 保留列表行的绘制命令（文本/字形引用/内联
 *    位图/色块）、行几何、div 浮层命中、按钮命中、滚动位置、背景与立绘、CBG 图层、HTML 浮岛、
 *    指针态。少一样，接管方就得回头去摸引擎内部对象，「接管」就退化成「顺着引擎内部写 UI」。
 *    本契约的做法是**直接把装配体交出去**（[screen] / [scene] 是活对象，只许读）：
 *    它们已经是那两份消费者唯一的输入源，再复制一份字段必然与引擎分叉。
 * 2. **只读**：接管方**不得**调任何写入口（`submit` 以外的状态修改；`updateBackground` /
 *    `setDivPointer` 这类会改引擎状态的也不许）。绘制是被动面；点按必须回到
 *    `ButtonLayer.hit` / 输入队列那条既有路（[ScreenSurface] 不提供提交方法，这是刻意的：
 *    渲染权与输入权分开授，才能保证「接管一次显示」不会顺手改坏一次状态）。
 * 3. **不新增第二真源**：帧不缓存行、不缓存位图、不缓存命中表。它是「这一帧交给谁」的凭据，
 *    不是另一份屏幕状态。
 * 4. **可丢弃**：接管方可以整帧丢弃（比如只认某些作用域的屏），后果只是这一帧没人画 ——
 *    由 [ScreenSurfaceRegistry] 的失败口径决定是回退还是空屏，绝不允许静默黑屏（见其 KDoc）。
 *
 * ## 线程
 *
 * UI 线程构建、UI 线程交付（与 `CanvasConsoleView.onDraw` 同一线程）；[screen]/[scene] 的读
 * 必须在该线程（ADR-003）。[epoch] 由 [ScreenFramePump] 单调递增，接管方据此丢弃迟到帧。
 *
 * @property epoch 帧世代：全局单调递增，**只增不减**。接管方必须按它丢弃迟到帧（异步渲染管线
 *   里这是唯一能防「旧帧覆盖新帧」的东西）。
 * @property clockMs 动画时钟（毫秒）。CBG 动画、立绘眨眼这类时间驱动的绘制读它，而不是自己取
 *   时钟 —— 否则 headless 对拍无法复现同一帧。
 * @property pointing 指针位置（列, 行）或 null。焦点/按下态由它决定，不是由接管方自己记。
 */
data class RenderFrame(
    val epoch: Long,
    val screen: ConsoleScreen,
    val scene: ScreenScene,
    val clockMs: Long,
    val pointing: Pair<Int, Int>? = null,
) {
    /** 本帧的行数（保留列表窗口行数）。接管方按它分配行几何，别再问引擎。 */
    val windowRows: Int get() = screen.list.rowCount

    /** 本帧的空指针态帧（同屏同场景，去掉指针）。测试与对拍用。 */
    fun withoutPointer(): RenderFrame = if (pointing == null) this else copy(pointing = null)
}

/**
 * 屏幕表面的接管口：**唯一一个方法**，因为接管方要的正是「整块画面」而不是若干个槽。
 *
 * 实现方（将来的现代化渲染器、录屏器、诊断栅格器）只做一件事：把这一帧画出来。
 * 画不出来就抛异常 —— [ScreenSurfaceRegistry] 会立刻摘掉它并回退原版绘制（死手开关），
 * **不要**自己吞异常画个空白交差：那等于静默黑屏。
 *
 * 生命周期：[ScreenSurfaceRegistry.claim] 之后开始收到帧，[ScreenSurfaceRegistry.release]
 * 之后不再收到。实现方不得假设两帧之间屏幕状态不变（引擎可能丢帧）。
 */
fun interface ScreenSurface {
    /**
     * 交付一帧。返回 Unit —— 绘制结果不参与逻辑，接管方不许在这里回写引擎状态。
     *
     * 抛 [Throwable] 的后果：本帧这一笔失败、接管被摘、原版绘制恢复（见 registry 失败口径）。
     */
    fun present(frame: RenderFrame)
}
