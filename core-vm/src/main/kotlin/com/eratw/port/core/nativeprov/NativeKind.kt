package com.eratw.port.core.nativeprov

/**
 * 原生加速层的四类子系统（ADR-010 §2 架构图，`docs/eratw-架构决策记录.md:196-202`）。
 *
 * 与架构图的对应：
 * - [IMAGE]   → ImageCodec（图片解码/编码；KotlinImageCodec 现状 = BitmapFactory/ImageDecoder + mmap）
 * - [AUDIO]   → AudioSink（音频输出/混音/重采样）
 * - [GLYPH]   → GlyphRasterizer（字形栅格 + glyph atlas；与 `:render` 有耦合，ADR-010 §1 范围表 :188）
 * - [KERNELS] → BulkKernels（批量内核：SFMT、字符串/数组 bulk、校验和，ADR-010 §1 范围表 :189）
 *
 * 每类对应一条独立旋钮 `eratw.native.<key>`（[key]，键面全表见 ADR-010 §3 :213-217），
 * 相互独立、互不牵连（ADR-010 §2 :208「每个子系统独立开关」）。
 *
 * **边界**（ADR-010 §1 :191「明确不做」）：VM 解释器、语句层（StatementExecutor/execAssign）、
 * ERB 语义、变量模型、存档格式、编译器**不在**本枚举覆盖范围——它们永远纯 Kotlin，
 * 不存在 native 候选，本选择器对它们无话可说。
 */
enum class NativeKind(
    /** 旋钮键尾段：系统性质/构建档占位的完整键 = `eratw.native.<key>`（ADR-010 §3）。 */
    val key: String,
) {
    /** 图片解码/编码（PNG 优先——语料主格式；JPEG/WebP 次之，ADR-010 §1 范围表 :186）。 */
    IMAGE("image"),

    /** 音频（Oboe/AAudio、混音/重采样；仅当音频成为掉帧/延迟瓶颈才启用，ADR-010 §1 :187）。 */
    AUDIO("audio"),

    /** 字形栅格（FreeType/HarfBuzz、glyph atlas；可选，与 `:render` 有耦合，ADR-010 §1 :188）。 */
    GLYPH("glyph"),

    /** 批量内核（必须批 ABI、禁止逐元素 JNI——ADR-010 §4 :225 的边界纪律在此先钉）。 */
    KERNELS("kernels"),
    ;
}
