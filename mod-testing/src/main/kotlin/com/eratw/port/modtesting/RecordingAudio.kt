package com.eratw.port.modtesting

import com.eratw.port.modapi.AudioApi
import com.eratw.port.modapi.ModResourceApi

/**
 * 录制型音频面（测试替身）。
 *
 * 口径与生产 `PackageAudioApi`（`:core-vm` 的 `ModAudioWiring.kt`，Lane AN）**逐条对齐**，唯一
 * 差别是把「把字节交给宿主播放器」换成「把这次受理调用记进 [plays]」：
 *
 * - 字节经本包 [ModResourceApi] 现读（台架 [TestWorld.resource] 落的盘）；名字规则 / 大小写 /
 *   单文件 4 MiB 与整包 16 MiB 上限全部复用引擎那份 `ModResourceStore`，台架不另写一套；
 * - 非法名 / 缺文件 / 空文件 / 超限 ⇒ **不抛**，落 WARN（文案与生产同一份）并返回 `false`；
 * - 音量 `0.0..1.0` 越界**夹取后**才记录（记录值 = 生产交给宿主汇的值）；
 * - 同一 context 重复 [play] = 后一条顶掉前一条（[current] 跟随最后一次受理，[plays] 保留全部
 *   受理历史）；[stop] 只停本 context 的那条声音且幂等。
 *
 * 台架**不**假装真的出声，也不模拟坏容器 / 音频焦点被抢：那些是宿主播放器侧语义，请在
 * `:core-vm` 的 `ModAudioWiringTest` / app 侧验证。与生产面的对拍用例见
 * `core-vm/src/test/kotlin/com/eratw/port/core/mod/ModAudioRecordingParityTest.kt`。
 *
 * @param packageId 包身份（WARN 文案与生产同口径点名它）。
 * @param resources 本包资源视图（`ModResourceStore.apiFor(packageId)` 的结果）。
 * @param warn 宿主日志面（缺资源 / 非法名等的 WARN 都经它落痕；[TestContext] 接到自己的日志表）。
 */
class RecordingAudio(
    val packageId: String,
    private val resources: ModResourceApi,
    private val warn: (String) -> Unit = {},
) : AudioApi {

    /** 一次受理成功的播放调用（只含作者可见面：资源名 / 循环 / 生效音量）。 */
    data class AudioPlay(
        val resourceName: String,
        val loop: Boolean,
        val volume: Float,
    )

    private val playsLog = mutableListOf<AudioPlay>()
    private val warningsLog = mutableListOf<String>()

    /** 受理成功的播放调用（按发生序；被后一条顶掉的也在内，最后一次即当前曲目）。 */
    val plays: List<AudioPlay> get() = playsLog.toList()

    /** 当前曲目 = 最后一次受理的 [play]；[stop] 后为 `null`（生产宿主「后一条顶掉前一条」）。 */
    var current: AudioPlay? = null
        private set

    /** [stop] 被调用次数（幂等：调几次记几次；作者断言「该停的时候真的调了停」）。 */
    var stopCount: Int = 0
        private set

    /** 替身落下的 WARN（缺资源 / 非法名 / 空文件 / 超限；文案与生产 `PackageAudioApi` 同一份）。 */
    val warnings: List<String> get() = warningsLog.toList()

    override fun play(resourceName: String, loop: Boolean, volume: Float): Boolean {
        if (loadAudioBytes(resources, packageId, resourceName, ::warnMessage) == null) return false
        val accepted = AudioPlay(resourceName, loop, volume.coerceIn(MIN_VOLUME, MAX_VOLUME))
        playsLog += accepted
        current = accepted
        return true
    }

    override fun stop() {
        stopCount += 1
        current = null
    }

    /** 清空全部录制结果（多次用例复用同一 world 时复位）。 */
    fun clear() {
        playsLog.clear()
        warningsLog.clear()
        current = null
        stopCount = 0
    }

    private fun warnMessage(message: String) {
        warningsLog += message
        warn(message)
    }
}

/** 线性音量下界（与生产 `ModAudioWiring.kt` 的 0..1 线性音量同口径）。 */
private const val MIN_VOLUME: Float = 0f

/** 线性音量上界。 */
private const val MAX_VOLUME: Float = 1f

/**
 * 读资源字节（降级集中点，照生产 `loadAudioBytes` 逐字对齐）：非法名 / 资源面未装配 /
 * 缺文件 / 空文件 ⇒ WARN + null；超限文件由 [ModResourceApi.open] 自己判（返回 null 且已落 WARN）。
 */
private fun loadAudioBytes(
    resources: ModResourceApi,
    packageId: String,
    resourceName: String,
    warn: (String) -> Unit,
): ByteArray? = try {
    val bytes = resources.open(resourceName)
    when {
        bytes == null -> {
            warn("mod.audio 资源不存在或不可读：$resourceName（$packageId）")
            null
        }
        bytes.isEmpty() -> {
            warn("mod.audio 资源为空：$resourceName（$packageId）")
            null
        }
        else -> bytes
    }
} catch (t: IllegalArgumentException) {
    warn("mod.audio 资源名非法：[$resourceName]（$packageId）cause=${t.message}")
    null
} catch (t: UnsupportedOperationException) {
    warn("mod.audio 资源面未装配：$packageId cause=${t.message}")
    null
}
