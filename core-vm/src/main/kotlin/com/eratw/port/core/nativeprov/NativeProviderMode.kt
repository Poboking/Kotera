package com.eratw.port.core.nativeprov

/**
 * 原生 provider 的三档选择模式（ADR-010 §3 :213-216 的取值域 `off|auto|force`，
 * 三档语义的权威表述在 §2 :205-207）。
 *
 * - [OFF]：缺省档（ADR-010 §3 :221「首版全部缺省 off」）。选择器对 [NativeKind] **短路**：
 *   不调自检探针、不产告警、零日志（连 debug sink 也不投递）——因为 Kotlin 实现是完整默认路径
 *   （ADR-010 §2 :205「Kotlin 默认实现永远是完整可用的」），OFF 就是「这条子系统不加速」。
 * - [AUTO]：能力自检探针失败（返回 false，或探针抛 Exception/LinkageError——加载失败
 *   `UnsatisfiedLinkError` 是 LinkageError 子类，同算自检失败）→ 记**恰一条**告警 → 回落 KOTLIN
 *   （ADR-010 §2 :206「auto 加载失败/自检失败 → 记一行 warning → 回落 Kotlin」）；
 *   探针成功 → resolved=NATIVE。
 * - [FORCE]：只用于 A/B 与验收（ADR-010 §2 :207「force 只用于 A/B 与验收」）。候选不可用即
 *   **显式失败**（[NativeProviders.selection] 抛 [IllegalStateException]），**绝不静默回落
 *   KOTLIN**——静默回落会让 A/B 测到的是 Kotlin 的时间却当作原生收益写进验收。
 */
enum class NativeProviderMode {
    /** 缺省：不加速，全走 Kotlin（零探针调用、零日志）。 */
    OFF,

    /** 加速候选可用则用，自检/加载失败记恰一条告警并回落 Kotlin。 */
    AUTO,

    /** 只用于 A/B 与验收：原生不可用即显式失败，绝不回落。 */
    FORCE,
    ;
}
