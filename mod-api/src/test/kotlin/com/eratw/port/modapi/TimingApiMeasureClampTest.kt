package com.eratw.port.modapi

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TimingApi.measure] 时钟回拨归一化的定向钉子（code-review Minor：TimingApi.kt:93）。
 *
 * ## 为什么是「源码形态闸 + 运行时断言」组合
 *
 * [TimingApi.measure] 是 inline 扩展，起止直接取 `System.nanoTime()`，无法注入假时钟；
 * 本模块只依赖 kotlin-test + junit-jupiter（无 MockK/Mockito），运行时构造不了负 delta ——
 * 这也正是审查报告把它降级为 Minor 的原因。因此：
 *  - 形态闸直接钉住「差值先 `coerceAtLeast(0L)` 再交给 record」这句语义：修复前红、修复后绿；
 *  - 运行时断言验证正常路径仍把非负耗时交给 record，且 block 的返回值/异常原样透传。
 *
 * 源码文本闸口径沿用本目录 ModApiSurfaceSupport 与项目内 BusyIndicatorWiringTest /
 * CanvasContentInsetSingleSourceTest 的做法：剥掉注释后按函数体断言，不让 KDoc 散文被误判为代码。
 */
class TimingApiMeasureClampTest {

    private val timingSource: String by lazy {
        Files.readString(ModApiSurfaceSupport.moduleRoot().resolve(TIMING_API_RELATIVE))
    }

    /** 只取 measure 函数体，避免 KDoc 里「回拨夹到 0」的措辞被误判为「已夹取」。 */
    private fun measureBody(): String {
        val marker = "inline fun <T> TimingApi.measure"
        assertTrue(timingSource.contains(marker), "找不到 TimingApi.measure 声明")
        return timingSource.substringAfter(marker).substringBefore("\n}\n")
    }

    @Test
    fun `测量路径把时钟回拨夹到0再落账`() {
        val body = measureBody()
        assertFalse(
            body.contains("record(scope, System.nanoTime() - started)"),
            "不得把未夹取、可能为负的 nanoTime 差值直接交给 record",
        )
        assertTrue(
            body.contains("(System.nanoTime() - started).coerceAtLeast(0L)"),
            "measure 必须把差值先 coerceAtLeast(0L) 再交给 record" +
                "（与 ModTimingLedger.measureValue / RecordingTiming.measure 同口径）",
        )
    }

    @Test
    fun `measure正常路径落账非负值并透传返回值与异常`() {
        val recorded = mutableListOf<Long>()
        val api = object : TimingApi {
            override fun samples(): List<TimingSample> = emptyList()
            override fun reset() = Unit
            override fun record(scope: String, nanos: Long) {
                assertEquals("author.demo.work", scope)
                recorded += nanos
            }
        }

        assertEquals(42, api.measure("author.demo.work") { 42 })
        assertEquals(1, recorded.size)
        assertTrue(recorded[0] >= 0L, "落账耗时不得为负：${recorded[0]}")

        val boom = IllegalStateException("boom")
        val thrown = runCatching { api.measure("author.demo.work") { throw boom } }.exceptionOrNull()
        assertTrue(thrown === boom, "block 异常应原样透传，不被记账吞掉")
        assertEquals(2, recorded.size)
        assertTrue(recorded[1] >= 0L, "失败路径落账耗时也不得为负：${recorded[1]}")
    }

    private companion object {
        const val TIMING_API_RELATIVE = "src/main/kotlin/com/eratw/port/modapi/TimingApi.kt"
    }
}
