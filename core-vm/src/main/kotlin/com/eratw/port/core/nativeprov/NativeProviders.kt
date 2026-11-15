package com.eratw.port.core.nativeprov

/**
 * debug=1 时一次选择的观察记录（ADR-010 §3 :217 的 debug 旋钮、§4 :232「native 计时/
 * 字节数进现有 DevTools/RunTrail」的**桌面投影**——真机侧将来接 DevTools/RunTrail，
 * 桌面 A/B 先经注入 sink 收形）。字段与 [NativeProviderSelection] 同源：kind/mode/
 * resolved/warning 是同一结果的投影，[probeNanos] 是本波新增的计时面。
 *
 * @param kind 哪类子系统
 * @param mode 配置档
 * @param resolved 最终实现路
 * @param probeNanos 自检探针耗时（System.nanoTime 差值，纳秒，恒 ≥0）；本波只有带探针的
 *   返回路径产记录（FORCE 失败走异常、OFF 走短路，都不产记录——见 [NativeProviders] 类 KDoc）
 * @param warning AUTO 回落告警文案（与 [NativeProviderSelection.warning] 同一条；其他路径 null）
 */
class NativeProviderDebugRecord(
    val kind: NativeKind,
    val mode: NativeProviderMode,
    val resolved: ResolvedProvider,
    val probeNanos: Long,
    val warning: String?,
)

/**
 * debug 观察投递口（ADR-010 §3 :217 计时/日志的**可注入**出口；缺省不注入 = 零输出）。
 *
 * 回调纪律与 `RngProbe.onDraw`（core/rand/Sfmt19937.kt）同款：实现**不得抛、不得阻塞**——
 * 观察面坏了不该带崩选择本身；本选择器不吞 sink 异常（实现违约即现场炸出，不留暗账）。
 */
fun interface NativeProviderDebugSink {
    /** 投递一条观察记录；同一 selection 至多一条（OFF 不投）。 */
    fun onRecord(record: NativeProviderDebugRecord)
}

/**
 * 原生 provider 选择器（ADR-010 §2 架构图 :202「NativeProviders off|auto|force 选择 +
 * 能力自检 + 失败回落 + 计时」）。
 *
 * 三档协议（权威表述 ADR-010 §2 :205-207）：
 * - **OFF**：短路 KOTLIN——不调探针、不产告警、零日志（debug=1 也不投记录，:205
 *   「Kotlin 默认实现永远是完整可用的」，无话可说才零话）。
 * - **AUTO**：探针失败（返回 false，或抛 Exception/LinkageError——加载失败
 *   `UnsatisfiedLinkError` 是 LinkageError 子类，同算自检失败）⇒ **恰一条**告警 +
 *   回落 KOTLIN；探针成功 ⇒ NATIVE（:206）。
 * - **FORCE**：探针失败 ⇒ **显式失败**——抛 [IllegalStateException]（探针返回 false 时
 *   无 cause；探针抛 Exception/LinkageError 时原异常挂 [Throwable.cause]，原栈不丢不吞），
 *   **绝不静默回落 KOTLIN**（:207「force 只用于 A/B 与验收」）；探针成功 ⇒ NATIVE。
 *
 * 每类子系统的档位独立取自 [NativeProvidersConfig]，互不牵连（:208）。
 *
 * ## 候选抽象与将来升级
 *
 * 选择器只认**接口**、不认具体类（:206）——本波的接口就是 [probe] 这个能力自检 λ
 * （最小形态）；四子系统真实接口落定后（落点见 [NativeProviderSelection] 类 KDoc），
 * 候选升级为「接口 + 自检」，本类三档协议不变。
 *
 * ## 纯度与状态
 *
 * - 实例化装置、配置注入（[config] / [debugSink] 都是 val），**零静态可变全局**；
 *   每次调用无状态（不缓存探针结果）——A/B 与验收需要同一选择器反复探测，
 *   缓存属于将来宿主装配面的事。
 * - 计时用 [System.nanoTime]（纯 JVM），差值只进 debug 记录，不参与语义。
 * - 探针异常只接 [Exception] 与 [LinkageError]（按 AUTO 口径算自检失败）；其余
 *   [Throwable]（OOM 等）原样穿透——不吞机器级的错。
 * - FORCE 失败的抛出路径**不产 debug 记录**：异常本身就是显式失败的凭证（含原因），
 *   观察面不替验收记账。
 *
 * @param config 注入的配置（[NativeProvidersConfig.parse] 的产物）
 * @param debugSink debug=1 时的观察投递口；null = 计时/日志不外投（零输出）。
 *   debug=false 时即使注入了 sink 也**不投**——开关在 debug 位，sink 只是出口。
 */
class NativeProviders(
    private val config: NativeProvidersConfig,
    private val debugSink: NativeProviderDebugSink? = null,
) {
    /**
     * 对一类子系统做选择。
     *
     * @param kind 子系统（档位独立取 [config] 中该键）
     * @param probe 能力自检探针：true = 原生候选可用；false/Exception/LinkageError = 不可用。
     *   本方法对每次调用恰好调它一次（OFF 档零次）
     * @return 结果值对象（AUTO 回落时带**恰一条**告警文案）
     * @throws IllegalStateException FORCE 档探针返回 false（显式失败，不回落）
     */
    fun selection(kind: NativeKind, probe: () -> Boolean): NativeProviderSelection {
        val mode = config.mode(kind)
        return when (mode) {
            NativeProviderMode.OFF -> NativeProviderSelection(
                kind = kind,
                mode = mode,
                resolved = ResolvedProvider.KOTLIN,
                warning = null,
            )

            NativeProviderMode.AUTO -> {
                val outcome = runProbe(probe)
                val failure = outcome.failure
                if (failure == null) {
                    emitDebug(kind, mode, ResolvedProvider.NATIVE, outcome.probeNanos, warning = null)
                    NativeProviderSelection(kind, mode, ResolvedProvider.NATIVE, warning = null)
                } else {
                    val warning = fallbackWarning(kind, failure.text)
                    emitDebug(kind, mode, ResolvedProvider.KOTLIN, outcome.probeNanos, warning)
                    NativeProviderSelection(kind, mode, ResolvedProvider.KOTLIN, warning)
                }
            }

            NativeProviderMode.FORCE -> {
                val outcome = runProbe(probe)
                val failure = outcome.failure
                if (failure == null) {
                    emitDebug(kind, mode, ResolvedProvider.NATIVE, outcome.probeNanos, warning = null)
                    NativeProviderSelection(kind, mode, ResolvedProvider.NATIVE, warning = null)
                } else {
                    // 显式失败统一为 IllegalStateException；探针原异常挂 cause，原栈不丢。
                    throw IllegalStateException(
                        "eratw.native.${kind.key}=force：原生候选不可用（${failure.text}），" +
                            "显式失败、不回落 KOTLIN（ADR-010 §2 :207：force 只用于 A/B 与验收）；" +
                            "请改 auto/off 或修复原生层",
                        failure.cause,
                    )
                }
            }
        }
    }

    /** 探针执行 + 计时 + 失败归因（false 归因为固定文案，异常归因为「类名: 消息」+原异常）。 */
    private fun runProbe(probe: () -> Boolean): ProbeOutcome {
        val start = System.nanoTime()
        val failure: ProbeFailure? = try {
            if (probe()) null else ProbeFailure(PROBE_FALSE_TEXT, cause = null)
        } catch (e: Exception) {
            ProbeFailure(describeFailure(e), cause = e)
        } catch (e: LinkageError) {
            ProbeFailure(describeFailure(e), cause = e)
        }
        return ProbeOutcome(
            failure = failure,
            probeNanos = System.nanoTime() - start,
        )
    }

    private fun describeFailure(e: Throwable): String {
        val message = e.message
        return if (message.isNullOrEmpty()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message"
    }

    /** AUTO 回落的恰一条告警文案（确定性：同 kind 同原因 ⇒ 同文案，可等值断言）。 */
    private fun fallbackWarning(kind: NativeKind, failure: String): String =
        "eratw.native.${kind.key}=auto：原生候选自检失败（$failure），已回落 KOTLIN"

    /** debug 投递：开关在 config.debug，出口在 debugSink；两者都开才有一条记录。 */
    private fun emitDebug(
        kind: NativeKind,
        mode: NativeProviderMode,
        resolved: ResolvedProvider,
        probeNanos: Long,
        warning: String?,
    ) {
        if (!config.debug) {
            return
        }
        val sink = debugSink ?: return
        sink.onRecord(NativeProviderDebugRecord(kind, mode, resolved, probeNanos, warning))
    }

    /** 探针一次执行的归因（[failure] null = 原生可用）。 */
    private class ProbeOutcome(
        val failure: ProbeFailure?,
        val probeNanos: Long,
    )

    /** 一次自检失败的归因：[text] 进告警/异常消息，[cause] 供 FORCE 显式失败挂原栈。 */
    private class ProbeFailure(
        val text: String,
        val cause: Throwable?,
    )

    private companion object {
        /** 探针返回 false（无异常）时的归因文案。 */
        const val PROBE_FALSE_TEXT: String = "probe 返回 false"
    }
}
