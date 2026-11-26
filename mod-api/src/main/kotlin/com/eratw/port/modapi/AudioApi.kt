package com.eratw.port.modapi

/**
 * mod 音频面（RT2 资源注入第二刀；Lane AN）：作者让自己的 mod 放出 BGM / 音效。
 *
 * ## 字节从哪来
 *
 * 一律经 [ModContext.resources] 的同一条资源面（`mods/rt2/<packageId>.assets/`）按名字读：
 * mod **不接触文件路径**、不打开设备文件、不拿 Android 对象。名字语义、大小写规则、4 MiB /
 * 16 MiB 上限全部沿用 [ModResourceApi]；本面只多一件事 = 把读到的字节交给宿主播放器。
 *
 * ## 线程
 *
 * 只在 **VM 线程**调用（宿主播放器的 prepare/start 可能阻塞，app 侧实现负责挪到后台线程，
 * 调用方只入队）。从别的线程调不保证行为。
 *
 * ## 降级纪律（宿主不保证出声）
 *
 * 缺文件 / 字节超限 / 坏容器 / 上游播放器被占用 —— 一律返回 `false` 并落 WARN 日志面，
 * **绝不抛、绝不砖游戏**。`true` 只表示「宿主已受理这次播放」，不承诺真的能听见：
 * 设备静音、音频焦点被别的 app 抢走、ROM 不支持该格式都仍然可能无声。
 *
 * ## 边界（本刀不做）
 *
 * - 不做音频焦点申请（沿用引擎既有的焦点会话，mod 音频与引擎 BGM/SE 共存、不抢通道）；
 * - 不做 `.erbm` 内嵌音频抽取（资源仍只从包资源目录读）；
 * - 不做混音总线 / 多路管理：同一 mod 重复 [play] 的既有声音按宿主实现处理（当前 = 后一条
 *   顶掉前一条，见 app 侧 `ModAudioSinkImpl` 的 KDoc）。
 */
interface AudioApi {

    /**
     * 播放本包资源目录里的一个音频文件。
     *
     * @param resourceName 资源名（相对包资源目录的 `/` 分隔路径，与 [ModResourceApi.open] 同规则）。
     * @param loop `true` = 无限循环，直到下一次 [play] 或 [stop]。
     * @param volume 线性音量 0.0..1.0（越界自动夹取）；默认 1.0。
     * @return `true` = 宿主已受理；`false` = 立即失败（缺文件 / 非法名 / 超限 / 播放器异常，
     *   均已落 WARN 日志面）。不抛异常。
     */
    fun play(resourceName: String, loop: Boolean = false, volume: Float = 1.0f): Boolean

    /** 停掉本 mod 当前由 [play] 起的声音；幂等，没有在播也安全。 */
    fun stop()
}
