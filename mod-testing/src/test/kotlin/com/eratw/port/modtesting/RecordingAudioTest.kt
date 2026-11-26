package com.eratw.port.modtesting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 台架音频替身 [RecordingAudio] 与 `TestContext` 接线自测（Lane AX）：
 *
 * - `context.audio` 就是 `context.audioRecorder`（同一实例，作者两个入口拿到同一份录制）；
 * - 资源和台架 `world.resource` 落盘目录同源 ⇒ 台架替身能读到真字节；
 * - 降级（缺资源 / 非法名）不抛、WARN 同时进 `context.logEntries` 与 `audioRecorder.warnings`；
 * - 音量夹取 / 重复 play 覆盖 / stop 幂等 / `clear()` 复位。
 *
 * 与生产 `PackageAudioApi` 的**逐条对拍**在 `:core-vm` 的 `ModAudioRecordingParityTest`（本文件只验
 * 台架自己的接线与可见面，不重复对拍）。
 */
class RecordingAudioTest {

    @Test
    fun `audio 与 audioRecorder 是同一个录制替身`() {
        val world = TestSchema.world()
        try {
            val context = world.newContext(TestSchema.PACKAGE_ID)

            assertSame(context.audioRecorder, context.audio)
            assertTrue(context.audioRecorder.plays.isEmpty())
            assertEquals(0, context.audioRecorder.stopCount)
        } finally {
            world.close()
        }
    }

    @Test
    fun `盘上资源可播且字段与音量夹取落进录制面`() {
        val world = TestSchema.world()
        try {
            val context = world.newContext(TestSchema.PACKAGE_ID)
            world.resource(TestSchema.PACKAGE_ID, "audio/theme.ogg", byteArrayOf(1, 2, 3))
            val audio = context.audioRecorder

            assertTrue(context.audio.play("audio/theme.ogg", loop = true, volume = 1.5f), "盘上资源应受理")

            val play = audio.plays.single()
            assertEquals("audio/theme.ogg", play.resourceName)
            assertTrue(play.loop, "loop 应透传")
            assertEquals(1f, play.volume, "音量应夹到上界 1.0")
            assertEquals(play, audio.current, "current 应指向受理的这条")
            assertTrue(audio.warnings.isEmpty(), "受理成功不该落 WARN：${audio.warnings}")
            assertTrue(context.logEntries.isEmpty(), "受理成功不该有日志：${context.logEntries}")

            assertTrue(context.audio.play("audio/theme.ogg", loop = false, volume = -1f), "越界低音量也应受理")
            assertEquals(2, audio.plays.size, "重复 play 逐条记录（后一条顶掉前一条）")
            assertEquals(0f, audio.plays.last().volume, "音量应夹到下界 0.0")
            assertEquals(audio.plays.last(), audio.current)
        } finally {
            world.close()
        }
    }

    @Test
    fun `缺资源返false且WARN同时进日志面与录制面`() {
        val world = TestSchema.world()
        try {
            val context = world.newContext(TestSchema.PACKAGE_ID)

            assertFalse(context.audio.play("audio/missing.ogg", loop = false, volume = 1f))

            val entry = context.logEntries.single()
            assertEquals("WARN", entry.level)
            assertEquals(TestSchema.PACKAGE_ID, entry.packageId)
            assertEquals(
                "mod.audio 资源不存在或不可读：audio/missing.ogg（${TestSchema.PACKAGE_ID}）",
                entry.message,
            )
            assertEquals(listOf(entry.message), context.audioRecorder.warnings, "WARN 文案应与生产同一份")
            assertTrue(context.audioRecorder.plays.isEmpty(), "拒播不得产生受理记录")
            assertNull(context.audioRecorder.current)
        } finally {
            world.close()
        }
    }

    @Test
    fun `非法名不抛返false且WARN点名资源名与包身份`() {
        val world = TestSchema.world()
        try {
            val context = world.newContext(TestSchema.PACKAGE_ID)

            assertFalse(context.audio.play("../escape.ogg", loop = false, volume = 1f))

            assertTrue(
                context.audioRecorder.warnings.single()
                    .startsWith("mod.audio 资源名非法：[../escape.ogg]（${TestSchema.PACKAGE_ID}）cause="),
                "WARN 应点名资源名与包身份：${context.audioRecorder.warnings}",
            )
            assertEquals(context.audioRecorder.warnings, context.logEntries.map { it.message })
        } finally {
            world.close()
        }
    }

    @Test
    fun `stop幂等计数且clear复位全部录制结果`() {
        val world = TestSchema.world()
        try {
            val context = world.newContext(TestSchema.PACKAGE_ID)
            world.resource(TestSchema.PACKAGE_ID, "audio/theme.ogg", byteArrayOf(1, 2, 3))
            val audio = context.audioRecorder

            assertTrue(context.audio.play("audio/theme.ogg", loop = true, volume = 0.8f))
            context.audio.stop()
            context.audio.stop()
            assertEquals(2, audio.stopCount, "stop 幂等：调几次记几次")
            assertNull(audio.current, "stop 后当前曲目应为空")

            audio.clear()
            assertTrue(audio.plays.isEmpty())
            assertTrue(audio.warnings.isEmpty())
            assertEquals(0, audio.stopCount)
            assertNull(audio.current)
        } finally {
            world.close()
        }
    }
}
