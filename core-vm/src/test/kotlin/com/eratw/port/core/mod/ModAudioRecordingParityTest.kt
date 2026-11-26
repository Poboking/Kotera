package com.eratw.port.core.mod

import com.eratw.port.modtesting.RecordingAudio
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 台架音频替身 × 生产音频面的**语义对拍**（Lane AX）。
 *
 * 左边 = `:core-vm` 的 [PackageAudioApi]（`ModAudioWiring.kt`，真宿主面，交 [ModAudioSink]）；
 * 右边 = `:mod-testing` 的 [RecordingAudio]（作者单测用的录制替身）。两边吃**同一份**盘上资源、
 * 同一个 `packageId`、同一个 [ModResourceStore]；用例断言「受理/拒绝、生效音量、WARN 文案」逐项
 * 一致 —— 台架替身一旦漂移，这里先红。
 *
 * 不覆盖：宿主播放汇抛异常（台架没有汇，见 `ModAudioWiringTest`）、真出声 / 音频焦点（platform 侧）。
 */
class ModAudioRecordingParityTest {

    private lateinit var assetsRoot: File

    @BeforeTest
    fun setUp() {
        assetsRoot = Files.createTempDirectory("mod-audio-parity").toFile()
    }

    @AfterTest
    fun tearDown() {
        assetsRoot.deleteRecursively()
    }

    @Test
    fun `资源在盘上时两面都受理且包身份字节loop音量逐项一致`() {
        val bench = Bench()
        putResource("audio/beep.ogg", byteArrayOf(1, 2, 3))

        assertTrue(bench.production.play("audio/beep.ogg", loop = true, volume = 0.5f))
        assertTrue(bench.recorder.play("audio/beep.ogg", loop = true, volume = 0.5f))

        val played = bench.sink.plays.single()
        val recorded = bench.recorder.plays.single()
        assertEquals(PACKAGE_ID, played.packageId, "生产面应把本包身份交给宿主汇")
        assertEquals("audio/beep.ogg", recorded.resourceName)
        assertContentEquals(byteArrayOf(1, 2, 3), played.bytes, "生产面交给宿主汇的字节应与盘上逐字一致")
        assertEquals(played.loop, recorded.loop)
        assertEquals(played.volume, recorded.volume, "两面生效音量必须一致")
        assertTrue(played.loop, "loop 应透传")
        assertEquals(0.5f, played.volume)
        assertEquals(recorded, bench.recorder.current, "current 应指向最后受理的这条")
        assertTrue(bench.productionWarnings.isEmpty(), "有资源时生产面不该 WARN：${bench.productionWarnings}")
        assertTrue(bench.recorderWarnings.isEmpty(), "有资源时台架不该 WARN：${bench.recorderWarnings}")
    }

    @Test
    fun `音量越界时两面按同一区间夹取`() {
        val bench = Bench()
        putResource("audio/beep.ogg", byteArrayOf(1, 2, 3))
        val requests = listOf(-2f, 0f, 0.37f, 1f, 9f)

        requests.forEach { volume ->
            assertTrue(bench.production.play("audio/beep.ogg", loop = false, volume = volume))
            assertTrue(bench.recorder.play("audio/beep.ogg", loop = false, volume = volume))
        }

        assertEquals(listOf(0f, 0f, 0.37f, 1f, 1f), bench.sink.plays.map { it.volume }, "生产面音量夹取区间应为 0..1")
        assertEquals(bench.sink.plays.map { it.volume }, bench.recorder.plays.map { it.volume }, "台架记录值必须与生产交给汇的值一致")
    }

    @Test
    fun `缺资源时两面都返false且WARN文案逐字一致`() {
        val bench = Bench()

        assertFalse(bench.production.play("audio/missing.ogg", loop = false, volume = 1f))
        assertFalse(bench.recorder.play("audio/missing.ogg", loop = false, volume = 1f))

        assertEquals(bench.productionWarnings, bench.recorderWarnings, "缺资源的 WARN 文案必须逐字一致")
        assertEquals(
            "mod.audio 资源不存在或不可读：audio/missing.ogg（$PACKAGE_ID）",
            bench.recorderWarnings.single(),
        )
        assertTrue(bench.sink.plays.isEmpty(), "拒播不得惊动宿主汇")
        assertTrue(bench.recorder.plays.isEmpty(), "拒播不得产生受理记录")
        assertNull(bench.recorder.current, "拒播不得改变当前曲目")
    }

    @Test
    fun `非法资源名时两面都不抛返false且WARN文案逐字一致`() {
        val names = listOf("../escape.ogg", "/abs.ogg", "a//b.ogg", "a\\b.ogg", "C:/x.ogg", "audio/../../x.ogg")

        names.forEach { name ->
            val bench = Bench()
            val prodAccepted = bench.production.play(name, loop = false, volume = 1f)
            val benchAccepted = bench.recorder.play(name, loop = false, volume = 1f)

            assertEquals(prodAccepted, benchAccepted, "同一资源名两面受理结果必须一致：[$name]")
            assertFalse(prodAccepted, "非法名不应被生产面受理：[$name]")
            assertEquals(bench.productionWarnings, bench.recorderWarnings, "非法名的 WARN 文案必须逐字一致：[$name]")
            assertTrue(
                bench.recorderWarnings.single().startsWith("mod.audio 资源名非法：[$name]（$PACKAGE_ID）cause="),
                "WARN 应点名资源名与包身份：${bench.recorderWarnings.single()}",
            )
        }
    }

    @Test
    fun `空文件时两面都返false且WARN文案逐字一致`() {
        val bench = Bench()
        putResource("audio/empty.ogg", ByteArray(0))

        assertFalse(bench.production.play("audio/empty.ogg", loop = false, volume = 1f))
        assertFalse(bench.recorder.play("audio/empty.ogg", loop = false, volume = 1f))

        assertEquals(bench.productionWarnings, bench.recorderWarnings)
        assertEquals("mod.audio 资源为空：audio/empty.ogg（$PACKAGE_ID）", bench.recorderWarnings.single())
        assertTrue(bench.recorder.plays.isEmpty())
    }

    @Test
    fun `超单文件上限时两面都返false且WARN口径一致`() {
        val bench = Bench(maxFileBytes = 4L)
        putResource("audio/big.ogg", ByteArray(5))

        assertFalse(bench.production.play("audio/big.ogg", loop = false, volume = 1f))
        assertFalse(bench.recorder.play("audio/big.ogg", loop = false, volume = 1f))

        assertEquals(bench.productionWarnings, bench.recorderWarnings)
        assertEquals(
            "mod.audio 资源不存在或不可读：audio/big.ogg（$PACKAGE_ID）",
            bench.recorderWarnings.single(),
            "超限文件由资源面判成不可用（scan 期已 WARN 跳过），音频面只统一转 false",
        )
        assertTrue(bench.resourceWarnings.isNotEmpty(), "资源面自身应留下「超单文件上限跳过」的 WARN")
        assertTrue(
            bench.resourceWarnings.all { it.contains("超过单文件上限") },
            "资源面 WARN 应点名上限跳过：${bench.resourceWarnings}",
        )
        assertTrue(bench.recorder.plays.isEmpty())    }

    @Test
    fun `重复play时两面都逐条受理且台架current跟随最后一条`() {
        val bench = Bench()
        putResource("audio/one.ogg", byteArrayOf(1))
        putResource("audio/two.ogg", byteArrayOf(2))

        assertTrue(bench.production.play("audio/one.ogg", loop = true, volume = 0.2f))
        assertTrue(bench.recorder.play("audio/one.ogg", loop = true, volume = 0.2f))
        assertTrue(bench.production.play("audio/two.ogg", loop = false, volume = 0.9f))
        assertTrue(bench.recorder.play("audio/two.ogg", loop = false, volume = 0.9f))

        assertEquals(2, bench.sink.plays.size, "生产面应两次都交宿主汇（后一条由宿主顶掉前一条）")
        assertEquals(listOf("audio/one.ogg", "audio/two.ogg"), bench.recorder.plays.map { it.resourceName })
        assertEquals(bench.recorder.plays.last(), bench.recorder.current)
        assertEquals("audio/two.ogg", bench.recorder.current?.resourceName)
        assertEquals(bench.sink.plays.map { it.loop }, bench.recorder.plays.map { it.loop })
        assertEquals(bench.sink.plays.map { it.volume }, bench.recorder.plays.map { it.volume })
    }

    @Test
    fun `stop时两面都逐次计数且台架current清空`() {
        val bench = Bench()
        putResource("audio/beep.ogg", byteArrayOf(1, 2, 3))
        assertTrue(bench.production.play("audio/beep.ogg", loop = true, volume = 1f))
        assertTrue(bench.recorder.play("audio/beep.ogg", loop = true, volume = 1f))

        bench.production.stop()
        bench.recorder.stop()
        bench.production.stop()
        bench.recorder.stop()

        assertEquals(bench.sink.stopCount, bench.recorder.stopCount, "stop 调用次数必须一致（幂等，调几次记几次）")
        assertEquals(2, bench.recorder.stopCount)
        assertNull(bench.recorder.current, "stop 后台架当前曲目应为空")
        assertTrue(bench.productionWarnings.isEmpty(), "正常 stop 不该 WARN：${bench.productionWarnings}")
        assertTrue(bench.recorderWarnings.isEmpty())
    }

    /**
     * 同一份盘上资源喂两面：左边生产 [PackageAudioApi]，右边台架 [RecordingAudio]。
     * 两侧各收自己的 WARN 清单；[sink] 只接生产面的受理（台架没有宿主汇）。
     */
    private inner class Bench(
        packageId: String = PACKAGE_ID,
        maxFileBytes: Long = ModResourceStore.MAX_RESOURCE_FILE_BYTES,
    ) {
        val resourceWarnings = mutableListOf<String>()
        val productionWarnings = mutableListOf<String>()
        val recorderWarnings = mutableListOf<String>()
        val sink = CapturingSink()
        private val store = ModResourceStore(assetsRoot, { resourceWarnings += it }, maxFileBytes = maxFileBytes)
        private val resourceApi = store.apiFor(packageId)
        val production: PackageAudioApi = PackageAudioApi(packageId, resourceApi, sink) { productionWarnings += it }
        val recorder: RecordingAudio = RecordingAudio(packageId, resourceApi) { recorderWarnings += it }
    }

    /** 生产面交给宿主汇的一次播放（与 app 侧 `ModAudioSinkImpl` 收的字段同形）。 */
    private class CapturedPlay(
        val packageId: String,
        val bytes: ByteArray,
        val loop: Boolean,
        val volume: Float,
    )

    /** 录制宿主汇：记录生产面透传的全部字段，恒受理。 */
    private class CapturingSink : ModAudioSink {

        val plays = mutableListOf<CapturedPlay>()

        var stopCount: Int = 0
            private set

        override fun play(packageId: String, bytes: ByteArray, loop: Boolean, volume: Float): Boolean {
            plays += CapturedPlay(packageId, bytes, loop, volume)
            return true
        }

        override fun stop() {
            stopCount += 1
        }
    }

    private companion object {

        /** 与台架 `TestWorld.DEFAULT_PACKAGE_ID` 解耦的独立包名（本文件直接装 store，不经台架）。 */
        const val PACKAGE_ID: String = "parity.mod"
    }

    /** 按引擎布局落一个资源：`<temp>/mods/rt2/<packageId>.assets/<name>`。 */
    private fun putResource(name: String, bytes: ByteArray) {
        val assetsDir = File(assetsRoot, PACKAGE_ID + ModResourceStore.ASSET_DIR_SUFFIX)
        val file = File(assetsDir, name)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }
}
