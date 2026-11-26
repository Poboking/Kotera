package com.eratw.port.modtesting

import com.eratw.port.modapi.ModManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 台架日志硬化模拟（[TestContext.hardenLogger]）的自测。
 *
 * 分两层：缺省必须**逐条原样**（作者断言看到自己写的原文，行为与硬化能力引入前逐位相同）；
 * 开启后按生产 `PackageLogger` 三条口径落行 —— 与生产的**逐字对拍**在 `:core-vm` 的
 * `LoggerHardeningParityTest`，本文件只钉台架自己的开关语义与小上限边界。
 */
class TestLoggerHardeningTest {

    @Test
    fun `缺省不硬化时超长空白洪水都逐条原样录制`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = PKG))
        try {
            val context = world.newContext(PKG)
            assertFalse(context.isLoggerHardened, "缺省必须是关，保现状逐位不变")

            val longMessage = "x".repeat(LONG_MESSAGE_CHARS)
            context.logger.info(longMessage)
            context.logger.info("")
            repeat(FLOOD_COUNT) { context.logger.info("n$it") }

            val entries = context.logEntries
            assertEquals(FLOOD_COUNT + 2, entries.size, "缺省时洪水不抑制，逐条都落")
            assertEquals(longMessage, entries[0].message, "缺省时超长不截断")
            assertEquals("", entries[1].message, "缺省时空白也原样录制")
            assertEquals(PKG, entries[0].packageId)
            assertEquals("INFO", entries[0].level)
            assertEquals(0, context.suppressedLogLineCount)
        } finally {
            world.close()
        }
    }

    @Test
    fun `硬化后超长消息按上限截断并追加截断标记`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = PKG))
        try {
            val context = world.newContext(PKG)
            context.hardenLogger()
            assertTrue(context.isLoggerHardened)

            context.logger.info("x".repeat(LONG_MESSAGE_CHARS))
            val entry = context.logEntries.single()
            assertEquals("INFO", entry.level)
            assertEquals("x".repeat(MESSAGE_LIMIT) + TRUNCATION_MARK, entry.message)

            // cause 在截断之后由渲染面拼接：正文仍是 1000 字符 + 标记。
            context.logger.error("z".repeat(LONG_MESSAGE_CHARS), IllegalStateException("bad"))
            val errorEntry = context.logEntries.last()
            assertEquals("ERROR", errorEntry.level)
            assertEquals("z".repeat(MESSAGE_LIMIT) + TRUNCATION_MARK, errorEntry.message)
            assertTrue(errorEntry.cause is IllegalStateException)
        } finally {
            world.close()
        }
    }

    @Test
    fun `硬化后空白消息静默丢弃且不占行数额度`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = PKG))
        try {
            val context = world.newContext(PKG)
            context.hardenLogger(maxLinesPerContext = SMALL_LINE_LIMIT)

            context.logger.info("")
            context.logger.info("   ")
            context.logger.info("\n\t ")
            assertTrue(context.logEntries.isEmpty(), "空白消息不该落：${context.logEntries}")

            // 空白没吃掉额度：小上限内仍能落满。
            repeat(SMALL_LINE_LIMIT) { context.logger.info("line$it") }
            assertEquals(SMALL_LINE_LIMIT, context.logEntries.size)
        } finally {
            world.close()
        }
    }

    @Test
    fun `硬化后 1200 条洪水只落 1000 行加一条元告警`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = PKG))
        try {
            val context = world.newContext(PKG)
            context.hardenLogger()
            repeat(FLOOD_COUNT) { context.logger.info("n$it") }

            val entries = context.logEntries
            assertEquals(LINE_LIMIT + 1, entries.size, "1000 行正文 + 1 条元告警")
            assertEquals("WARN", entries.last().level)
            assertEquals(
                "日志超过 $LINE_LIMIT 条上限，后续同类日志静默（本行之前已抑制 1 条）",
                entries.last().message,
            )
            assertEquals(FLOOD_COUNT - LINE_LIMIT, context.suppressedLogLineCount, "抑制总数口径")
        } finally {
            world.close()
        }
    }

    @Test
    fun `小上限参数可用少量日志逼近边界且元告警文案随参数变`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = PKG))
        try {
            val context = world.newContext(PKG)
            context.hardenLogger(maxMessageLength = SMALL_MESSAGE_LIMIT, maxLinesPerContext = SMALL_LINE_LIMIT)

            context.logger.info("0123456789abc")
            assertEquals("0123456789$TRUNCATION_MARK", context.logEntries.single().message)

            repeat(SMALL_LINE_LIMIT) { context.logger.info("ok$it") }
            assertEquals(
                "日志超过 $SMALL_LINE_LIMIT 条上限，后续同类日志静默（本行之前已抑制 1 条）",
                context.logEntries.last().message,
            )
        } finally {
            world.close()
        }
    }

    @Test
    fun `降级链 WARN 与作者日志走同一条硬化通道`() {
        val world = TestWorld.ofManifests(ModManifest(packageId = PKG))
        try {
            val context = world.newContext(PKG)
            context.hardenLogger(maxLinesPerContext = 1)
            context.logger.info("占满唯一一行")
            // 音频缺资源 ⇒ 生产/台架都走 context.logger.warn：应被行数上限抑制而不是另开一路。
            assertFalse(context.audio.play("missing.ogg", loop = false, volume = 1f))
            assertEquals(2, context.logEntries.size, "一条正文 + 一条元告警")
            assertEquals("WARN", context.logEntries.last().level)
            assertTrue(context.logEntries.last().message.contains("日志超过 1 条上限"))
            assertEquals(1, context.suppressedLogLineCount, "音频降级 WARN 也吃同一份额度")
        } finally {
            world.close()
        }
    }
}

private const val PKG = "bench.logger"
private const val MESSAGE_LIMIT = 1000
private const val LINE_LIMIT = 1000
private const val LONG_MESSAGE_CHARS = 5000
private const val FLOOD_COUNT = 1200
private const val SMALL_MESSAGE_LIMIT = 10
private const val SMALL_LINE_LIMIT = 3
private const val TRUNCATION_MARK = "…(截断)"
