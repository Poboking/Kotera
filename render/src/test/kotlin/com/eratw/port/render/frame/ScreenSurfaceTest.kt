package com.eratw.port.render.frame

import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 渲染接管契约（SKIN-1 前置：整块画面交接）的判据。
 *
 * 这组用例只钉**交接语义**，不钉像素：像素等价由既有 `ScreenComposer` 对拍族负责，
 * 本组要保证的是「谁画」这件事不可含糊 —— 排他、可交还、失败必回退、世代单调、帧完整。
 */
class ScreenSurfaceTest {

    private fun screen(): ConsoleScreen = ConsoleScreen(clientWidth = 40, maxLog = 200)

    private fun recording(): Pair<ScreenSurface, MutableList<RenderFrame>> {
        val frames = ArrayList<RenderFrame>()
        return ScreenSurface { frames += it } to frames
    }

    @Test
    fun `未接管时泵不出帧且世代不推进`() {
        val registry = ScreenSurfaceRegistry()
        val pump = ScreenFramePump(registry) { 1234L }
        assertFalse(pump.pump(screen()))
        assertEquals(0L, pump.epoch(), "关态不许推进世代，更不许建帧")
        assertEquals(SurfaceStats(0, 0), registry.stats())
    }

    @Test
    fun `接管后每帧交付且世代单调`() {
        val registry = ScreenSurfaceRegistry()
        val pump = ScreenFramePump(registry) { 500L }
        val (surface, frames) = recording()
        assertTrue(registry.claim("skin", surface))

        assertTrue(pump.pump(screen()))
        assertTrue(pump.pump(screen()))
        assertEquals(2, frames.size)
        assertEquals(listOf(1L, 2L), frames.map { it.epoch })
        assertEquals(2L, pump.epoch())
        assertEquals(SurfaceStats(2, 0), registry.stats())
    }

    @Test
    fun `帧完整_窗口行数与场景时钟指针原样交接`() {
        val registry = ScreenSurfaceRegistry()
        val scene = ScreenScene(backgroundArgb = 0xFF102030.toInt())
        val pump = ScreenFramePump(registry) { 777L }
        val (surface, frames) = recording()
        registry.claim("skin", surface)

        val console = screen()
        assertTrue(pump.pump(console, scene, pointing = 3 to 5))
        val frame = frames.single()
        assertSame(console, frame.screen, "必须是活装配体本身：复制一份必然与引擎分叉")
        assertSame(scene, frame.scene)
        assertEquals(777L, frame.clockMs)
        assertEquals(3 to 5, frame.pointing)
        assertEquals(console.list.rowCount, frame.windowRows)
        val noPointer = frame.withoutPointer()
        assertNull(noPointer.pointing)
        assertEquals(frame.epoch, noPointer.epoch)
        assertSame(frame.screen, noPointer.screen)
    }

    @Test
    fun `排他_二手接管被拒且不影响现任`() {
        val registry = ScreenSurfaceRegistry()
        val (first, firstFrames) = recording()
        val (second, secondFrames) = recording()
        assertTrue(registry.claim("a", first))
        assertFalse(registry.claim("b", second), "整块画面是排他的，不许抢占")

        val pump = ScreenFramePump(registry)
        assertTrue(pump.pump(screen()))
        assertEquals(1, firstFrames.size)
        assertEquals(0, secondFrames.size)
        assertEquals("a", registry.claimant())
    }

    @Test
    fun `同 token 重复 claim 等于热替换实现`() {
        val registry = ScreenSurfaceRegistry()
        val (oldSurface, oldFrames) = recording()
        val (newSurface, newFrames) = recording()
        assertTrue(registry.claim("a", oldSurface))
        assertTrue(registry.claim("a", newSurface))

        val pump = ScreenFramePump(registry)
        assertTrue(pump.pump(screen()))
        assertEquals(0, oldFrames.size)
        assertEquals(1, newFrames.size)
    }

    @Test
    fun `非现任 token 交还无效`() {
        val registry = ScreenSurfaceRegistry()
        val (surface, _) = recording()
        assertTrue(registry.claim("a", surface))
        assertFalse(registry.release("b"))
        assertNotNull(registry.current(), "误调别人的 release 不许把画面拔掉")
        assertTrue(registry.release("a"))
        assertNull(registry.current())
    }

    @Test
    fun `接管方抛异常_本帧回退原版且立刻摘除`() {
        val registry = ScreenSurfaceRegistry()
        var calls = 0
        val broken = ScreenSurface {
            calls += 1
            error("渲染器炸了")
        }
        registry.claim("skin", broken)
        val pump = ScreenFramePump(registry)

        assertFalse(pump.pump(screen()), "失败必须交给原版，不许黑屏")
        assertEquals(1, calls)
        assertNull(registry.claimant(), "异常后接管必须被摘掉")
        assertEquals(1L, registry.stats().failures)
        // 摘除之后：不再被调用，泵恒返回 false。
        assertFalse(pump.pump(screen()))
        assertEquals(1, calls)
    }

    @Test
    fun `失败摘除后重新接管_世代继续前进不回退`() {
        val registry = ScreenSurfaceRegistry()
        val pump = ScreenFramePump(registry)
        registry.claim("a", ScreenSurface { error("x") })
        assertFalse(pump.pump(screen()))
        val afterFailure = pump.epoch()
        assertTrue(afterFailure >= 1L)

        val (healthy, frames) = recording()
        assertTrue(registry.claim("a", healthy))
        assertTrue(pump.pump(screen()))
        assertEquals(afterFailure + 1, frames.single().epoch)
    }

    @Test
    fun `交付期间接管方回调 release 不自锁且本帧仍算已交付`() {
        val registry = ScreenSurfaceRegistry()
        val frames = ArrayList<RenderFrame>()
        // 接管方在自己的 present 里交还接管：允许（锁外调用），本帧视为已交付。
        registry.claim("a", ScreenSurface { frame ->
            frames += frame
            registry.release("a")
        })
        val pump = ScreenFramePump(registry)
        assertTrue(pump.pump(screen()))
        assertEquals(1, frames.size)
        assertNull(registry.claimant())
        assertFalse(pump.pump(screen()), "已交还 ⇒ 下一帧回原版")
    }
}
