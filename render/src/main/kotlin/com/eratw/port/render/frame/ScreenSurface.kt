package com.eratw.port.render.frame

import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenScene

/**
 * 接管权的登记表：某一时刻**至多一个** [ScreenSurface] 拥有整块画面。
 *
 * ## 为什么是「至多一个」而不是注册表式多播
 *
 * 多播会让「谁真的画了屏」变成不可判定：两个渲染器都画、都以为自己赢了，出问题无从归因，
 * 回退时也无法保证「原版一定在画」。整块画面的接管本质是**排他**的：要么原版画，要么接管方画。
 * 需要旁路观察（录屏、诊断栅格）就包一层 [ScreenSurface] 自己再分发，不在本类里做。
 *
 * ## 失败口径（死手开关，硬判据）
 *
 * 接管方 [ScreenSurface.present] 抛任何 `Throwable` ⇒ 本帧这一笔算失败、**立刻摘掉接管并计数**，
 * 且本帧返回 false（原版继续画）。宁可多画一帧原版，也绝不允许「接管方挂了 → 玩家黑屏」。
 * 这条与 SKIN-1 判据②「回退是默认路径而非异常路径」是同一件事的两个面。
 *
 * ## 线程
 *
 * UI 线程（与绘制同线程）。`present` 在锁外调用：接管方在本方法内回调 `release` / `claim`
 * 不会自锁（重入由 [owner] 与 [surface] 的原子快照承担）。异常路径的摘除重新拿锁并**核对
 * owner 未变**，避免把「回调里已经换过人」的新接管方误摘。
 */
class ScreenSurfaceRegistry {

    private val lock = Any()
    private var surface: ScreenSurface? = null
    private var owner: String? = null
    private var delivered: Long = 0L
    private var failures: Long = 0L

    /**
     * 申请接管。成功 = true（此后每帧交付给它）。
     *
     * 已被别的 token 占用 ⇒ false（不抢占、不排队：接管是产品级决定，不是竞态问题）。
     * 同一 token 重复 claim = 换实现（幂等），便于热替换渲染器。
     */
    fun claim(token: String, candidate: ScreenSurface): Boolean = synchronized(lock) {
        val current = owner
        if (current != null && current != token) {
            false
        } else {
            owner = token
            surface = candidate
            failures = 0L
            true
        }
    }

    /**
     * 交还接管。只有当前 owner 能交还；非 owner 调用返回 false 且不改任何状态
     * （否则任何一处误调都能把别人的画面拔掉）。
     */
    fun release(token: String): Boolean = synchronized(lock) {
        if (owner == token) {
            owner = null
            surface = null
            true
        } else {
            false
        }
    }

    /** 当前接管方 token；null = 原版绘制（默认态）。 */
    fun claimant(): String? = synchronized(lock) { owner }

    /** 当前接管方；null = 原版绘制。 */
    fun current(): ScreenSurface? = synchronized(lock) { surface }

    /** 已交付帧数 / 失败摘除数（诊断面）。 */
    fun stats(): SurfaceStats = synchronized(lock) { SurfaceStats(delivered, failures) }

    /**
     * 交付一帧。返回 true = 接管方已接手（**调用方必须跳过后面的原版绘制**）；
     * false = 没有接管方 / 接管方抛异常 ⇒ 调用方照常走原版绘制。
     */
    fun deliver(frame: RenderFrame): Boolean {
        // 锁内只取「交给谁」的快照并记账，present 在锁外调（接管方可以在自己的
        // present 里回调 release/claim，不能自锁）。
        val claim = synchronized(lock) {
            val target = surface
            val currentOwner = owner
            if (target == null || currentOwner == null) {
                null
            } else {
                delivered += 1
                Claim(target, currentOwner)
            }
        }
        if (claim == null) return false
        val ok = runCatching { claim.target.present(frame) }.isSuccess
        if (!ok) dropFailed(claim.owner)
        return ok
    }

    /** 锁内快照：本帧交给谁、以及失败时要摘的是谁（owner 可能已被回调换人）。 */
    private class Claim(val target: ScreenSurface, val owner: String)

    /** 接管方失败：摘掉并计数；核对 owner 未变（见类文档的线程段）。 */
    private fun dropFailed(failedOwner: String) {
        synchronized(lock) {
            if (owner == failedOwner) {
                owner = null
                surface = null
            }
            failures += 1
        }
    }
}

/** 接管面读数（诊断/设置页用）。 */
data class SurfaceStats(
    val delivered: Long,
    val failures: Long,
)

/**
 * 帧泵：把「这一帧归谁画」的判定收成一次调用（渲染接管契约的保存者）。
 *
 * 典型接法（壳层每帧一次，与今天 `pumpAndInvalidateIfNeeded()` 同处）：
 *
 * ```kotlin
 * val takenOver = pump.pump(screen, scene, pointing)
 * if (!takenOver) view.invalidate()   // 没人接手 ⇒ 原版照常画
 * ```
 *
 * **零成本保证**：没有接管方时只读一次 [ScreenSurfaceRegistry.current] 就返回，
 * 不建 [RenderFrame]、不取时钟、不推进世代 —— 关态与今天逐位相同。
 *
 * 世代（[RenderFrame.epoch]）只在实际交付时推进：接管方拿到的一定是单调序号，
 * 用它丢弃迟到帧。接管方被摘除后世代**不回退**（重新接管从更大号继续）。
 */
class ScreenFramePump(
    private val registry: ScreenSurfaceRegistry,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var epoch: Long = 0L

    /** 已交付的最大世代（诊断面：接管方看到的最新号）。 */
    fun epoch(): Long = epoch

    /**
     * 泵一帧。返回 true = 已交给接管方（调用方跳原版绘制）；false = 原版照常画。
     *
     * 帧是**在交付前一刻**从活对象现取的（[screen] / [scene] 不复制），所以接管方看到的是
     * 与本帧原版绘制完全相同的输入 —— 这正是「像素等价接管」能成立的前提。
     */
    fun pump(
        screen: ConsoleScreen,
        scene: ScreenScene = ScreenScene(),
        pointing: Pair<Int, Int>? = null,
    ): Boolean {
        if (registry.current() == null) return false
        epoch += 1
        return registry.deliver(RenderFrame(epoch, screen, scene, clockMs(), pointing))
    }
}
