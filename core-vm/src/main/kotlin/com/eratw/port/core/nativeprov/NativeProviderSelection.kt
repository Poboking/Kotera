package com.eratw.port.core.nativeprov

/**
 * 一次选择最终选中的实现路（ADR-010 §2 架构图的 Kotlin/Native 双列）。
 *
 * [KOTLIN] 永远完整可用（ADR-010 §2 :205「Kotlin 默认实现永远是完整可用的」）；
 * [NATIVE] 只在自检探针通过后才出现。没有 FAILED 档——FORCE 下候选不可用是
 * [IllegalStateException] 显式失败（不产结果对象），不是一种 resolved。
 */
enum class ResolvedProvider {
    /** 走 Kotlin 实现（缺省路径；OFF 与 AUTO 回落都落这里）。 */
    KOTLIN,

    /** 走原生实现（AUTO/FORCE 且自检探针通过）。 */
    NATIVE,
    ;
}

/**
 * 一次 provider 选择的**结果值对象**（[NativeProviders.selection] 的返回值）：
 * 哪类子系统（[kind]）、按哪档（[mode]）、最终走了哪条路（[resolved]）、
 * AUTO 回落时的**恰一条**告警文案（[warning]，其余档恒 null）。
 *
 * 值对象不可变、可等值比较（data class）——判据「同 config 同 selection」与
 * 「多次 selection 结果一致」直接建立在它的 equals 上。告警不外设全局日志面：
 * 文案随结果返回（调用方拿到的是值，不是副作用），debug=1 时同一文案再经
 * [NativeProviderDebugSink] 投一份（可观测投影，ADR-010 §4 :232）。
 *
 * ## 四子系统 Kotlin/Native 接口的未来落点（本波一律不落，契约铁律 2）
 *
 * ADR-010 §2 架构图（`docs/eratw-架构决策记录.md:196-202`）把四个 Kotlin/Native
 * 双实现接口归在「Kotlin 接口层（core-vm / app）」，选择器只认**接口**、不认具体类
 * （:206）。逐项落点（§2 图 + §1 范围表 :186-189 推导；模块归位中凡图面没写死的
 * 属设备波定案，标 TODO(verify)，卡点=ADR-010 §2 只画了「core-vm / app」两级、
 * 未逐接口指模块）：
 *
 * - ImageCodec：接口落 core-vm 或 app 装配面；KotlinImageCodec = BitmapFactory/ImageDecoder
 *   + mmap（Android 面，现状由 platform/app 承担），NativeImageCodec = JNI → liberatwaccel.so
 *   （ADR-010 §2 :197-198）。TODO(verify)：接口落 core-vm 还是 app。
 * - AudioSink：接口落 app（Oboe/AAudio 是 Android 面）；KotlinAudioSink 现状走
 *   core-vm 的 audio 适配层（ADR-010 §2 :199）。TODO(verify)：接口模块归位。
 * - GlyphRasterizer：与 `:render` 有耦合（ADR-010 §1 :188），接口落 render 或 app，
 *   Native 实现走 FreeType/HarfBuzz。TODO(verify)：接口模块归位。
 * - BulkKernels：纯 JVM 可表（SFMT/字符串与数组 bulk/校验和），接口落 core-vm
 *   （挨现有 rand 等热点），Native 侧必须批 ABI（ADR-010 §1 :189、§4 :225）。
 *   TODO(verify)：具体挂接点。
 * - NativeProviders（本包）：选择器本体，core-vm 纯 JVM——**本波已落**（三档协议 +
 *   自检 + 回落 + 计时，ADR-010 §2 :202）。
 *
 * 四接口落定后，[NativeProviders.selection] 的候选抽象从「自检探针 λ」升级为
 * 「接口 + 自检」，三档语义、恰一条告警、FORCE 显式失败这些协议不变。
 */
data class NativeProviderSelection(
    /** 哪类子系统。 */
    val kind: NativeKind,

    /** 配置要求的选择档（[NativeProvidersConfig.modes] 里该键的原值）。 */
    val mode: NativeProviderMode,

    /** 最终选中的实现路。 */
    val resolved: ResolvedProvider,

    /**
     * AUTO 回落告警文案（**恰一条**：一次 selection 要么没有、要么一条，不叠加）；
     * OFF 与成功路径恒 null。文案确定性可等值断言（含 kind 键名与失败原因）。
     */
    val warning: String?,
)
