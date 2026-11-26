package com.eratw.port.core.mod

import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.AudioApi
import com.eratw.port.modapi.ModResourceApi

/**
 * 宿主侧 mod 音频播放汇（Lane AN）：core-vm 不认识 Android，真正的播放执行器由 app 侧实现并注入。
 *
 * 契约只有两条：
 * - [play] 收**字节**（不是路径），返回宿主是否受理；抛出即视为不受理（[ModAudioWiring] 会隔离）；
 * - [stop] 停掉该汇当前在播的声音，幂等。
 */
interface ModAudioSink {
    fun play(packageId: String, bytes: ByteArray, loop: Boolean, volume: Float): Boolean

    fun stop()
}

/**
 * mod 音频面的宿主接线（Lane AN；RT2 资源注入第二刀）。
 *
 * ## 分层
 *
 * - [ModAudioSink] = 宿主播放汇（app 侧 `ModAudioSinkImpl` 用 platform 的 MediaPlayer 封装实现）；
 * - 本对象 = 会话级接线：把汇挂进 `store.aux`（[AUX_SLOT]），让会话收口能停声、让旁路调用点
 *   能按包播放；
 * - [PackageAudioApi] = 每个 mod context 的 `context.audio`（读字节的降级纪律全在这里）。
 *
 * ## 线程
 *
 * 命令侧由 VM 线程调（`context.audio.play` / `play(store, …)`）；字节读取是同步的（资源面本来
 * 就是每次现读盘），真正的 prepare/start 由宿主汇挪到后台线程 —— 本层不做线程切换。
 *
 * ## 降级纪律
 *
 * 非法名 / 缺文件 / 空文件 / 超限 / 未装配资源面 / 宿主汇抛异常 —— 一律 WARN + `false`，
 * 不抛、不砖游戏。WARN 走装配日志面（`logSink`），不是玩家可见文案。
 */
object ModAudioWiring {

    /** `store.aux` 槽名（家族前缀 `mod.`，全小写；与 `FunctionHookWiring.AUX_SLOT` 同范式）。 */
    const val AUX_SLOT: String = "mod.audio.sink"

    /** 槽内容：可替换的播放汇 + 资源存储 + 日志面（`RuntimeAux` 没有删槽 API，用可变 holder）。 */
    internal class Handle(
        var sink: ModAudioSink? = null,
        var resources: ModResourceStore? = null,
        var logSink: (String) -> Unit = {},
    )

    /** 把本会话的播放汇挂到 [store]；重复安装 = 覆盖（读档后重装同形）。 */
    fun install(
        store: VariableStore,
        sink: ModAudioSink,
        resources: ModResourceStore,
        logSink: (String) -> Unit,
    ) {
        val handle = store.aux.slot(AUX_SLOT) { Handle() }
        handle.sink = sink
        handle.resources = resources
        handle.logSink = logSink
    }

    /**
     * 解绑本会话的播放汇：先 [ModAudioSink.stop] 停声（幂等、异常隔离），再清引用。
     *
     * 会话收口（`ModRuntimeSession.shutdown`）在 lifecyle 之后调；槽不删除（`RuntimeAux` 无删槽
     * API，与 `FunctionHookWiring` 同范式），置空后 `play(store, …)` 显式返回 false。
     */
    fun uninstall(store: VariableStore) {
        val handle: Handle = store.aux.peek(AUX_SLOT) ?: return
        handle.sink?.let { sink -> runCatching { sink.stop() } }
        handle.sink = null
        handle.resources = null
        // logSink 保留：解绑后的晚到 play 要靠它落「播放汇未装配」WARN（取证面不能静默）。
    }

    /** 现读本会话播放汇；未安装 / 已解绑 ⇒ null。 */
    fun sinkOf(store: VariableStore): ModAudioSink? = store.aux.peek<Handle>(AUX_SLOT)?.sink

    /**
     * 旁路播放（无 [com.eratw.port.modapi.ModContext] 的宿主侧调用点）：按 [store] 上现装的槽
     * 取汇与资源存储，再走与 `context.audio` 完全相同的降级链。
     *
     * 槽缺席 / 已解绑 ⇒ false + WARN（不抛：调用点可能是收口后的晚到播放）。
     */
    fun play(store: VariableStore, packageId: String, resourceName: String, loop: Boolean, volume: Float): Boolean {
        val handle: Handle? = store.aux.peek(AUX_SLOT)
        val sink = handle?.sink
        val resources = handle?.resources
        return if (sink == null || resources == null) {
            handle?.logSink?.invoke("mod.audio 播放汇未装配，忽略 play(pkg=$packageId name=$resourceName)")
            false
        } else {
            val packageApi = PackageAudioApi(packageId, resources.apiFor(packageId), sink, handle.logSink)
            packageApi.play(resourceName, loop, volume)
        }
    }
}

/**
 * 本包音频视图（[AudioApi] 的 core 实现）：读字节 + 降级 + 转交宿主汇。
 *
 * 每次 [play] 都重新经 [ModResourceApi] 读盘（资源面无缓存，作者热替换资源后立即生效）；
 * [stop] 只停本包这条汇当前的声音，不碰引擎 BGM / SE 声道（共存策略）。
 */
internal class PackageAudioApi(
    private val packageId: String,
    private val resources: ModResourceApi,
    private val sink: ModAudioSink,
    private val warn: (String) -> Unit,
) : AudioApi {

    override fun play(resourceName: String, loop: Boolean, volume: Float): Boolean {
        val bytes = loadAudioBytes(resources, packageId, resourceName, warn) ?: return false
        val clamped = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        return runCatching { sink.play(packageId, bytes, loop, clamped) }.getOrElse { t ->
            warn("mod.audio 宿主播放汇异常已隔离：${t::class.java.simpleName}: ${t.message}")
            false
        }
    }

    override fun stop() {
        runCatching { sink.stop() }.onFailure { t ->
            warn("mod.audio stop 异常已隔离：${t::class.java.simpleName}: ${t.message}")
        }
    }
}

/**
 * 未装配音频面时的显式 fail-closed 桩（照 `FailClosedResourceApi` 的形状）：
 * 每个入口抛 [UnsupportedOperationException]，不返回静默 false 兜底。
 */
internal class FailClosedAudioApi(private val packageId: String) : AudioApi {

    override fun play(resourceName: String, loop: Boolean, volume: Float): Boolean = fail("play($resourceName)")

    override fun stop() = fail("stop()")

    private fun fail(call: String): Nothing = throw UnsupportedOperationException(
        "音频面未装配：$packageId（宿主未注入 AudioApi；本会话/旧装配路径不提供音频）；" +
            "$call 不提供静默默认值兜底",
    )
}

/** 线性音量下界（与 platform 播放器的 0..1 线性音量同口径）。 */
private const val MIN_VOLUME: Float = 0f

/** 线性音量上界。 */
private const val MAX_VOLUME: Float = 1f

/**
 * 读资源字节（降级集中点）：非法名 / 资源面未装配 / 缺文件 / 空文件 ⇒ WARN + null。
 *
 * 超限文件由 [ModResourceApi.open] 自己判（返回 null 并已落 WARN），这里只统一转 false。
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
