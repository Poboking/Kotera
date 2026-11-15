#!/usr/bin/env python3
"""生成 core-vm 的 CP932 可编码码点成员表（`Cp932Encodable.kt`，位图形态）。

为什么要有这张表
    地图/字符画语料是按 PC 原版用 ＭＳ ゴシック（CP932 系统字体）量宽排出来的：
    JIS 双字节字符（制表符 ━┃┼、希腊 Ψψ、数学 ∵∥、罗马数字 Ⅲ…）一律全角 2 格。
    随包替身字体（Sarasa Mono SC 等）的 hmtx 把这一批设计成半角 500，
    `FontWidthRuler` 按 sidecar 真实推进就会把整幅字符画压碎（2026-11-10 地灵殿地图报告）。
    裁决规则 = 替身正文族下「CP932 可编码的码点」按静态 CP932 网格量宽，
    表外字（组合记号 U+0300-036F、块元素 ▉ U+2589、emoji 等）才信 sidecar
    真实 advance（老虎机 ▉▂ 半角一格的读数仍来自 sidecar，不回归）。

「可编码」的权威
    Python 3 内置 `cp932` codec（源自 Microsoft CP932.TXT，与 JDK `Windows-31J`
    同源；U+E000-U+E757 的 PUA 段是官方映射自带的回环位，语料不出现，无害）。
    单字节命中 = 半角档，双字节命中 = 全角档，两者都属于「MS Gothic 自己有
    字形、PC 原版用字体本尊量过宽」的集合。

表示形态
    BMP 全扫 65,536 位 → 2,048 个 Int 的位图（CJK 汉字区在 Unicode 里不连续，
    区间表会碎成几千段，位图是唯一紧凑形态）。

用法
    python tools/fontsubset/gen_cp932_encodable.py
    输出写死到 core-vm/src/main/kotlin/com/eratw/port/core/host/Cp932Encodable.kt，
    文件头自带生成凭据；重跑幂等（同字节输出不落盘）。
"""

import io
import os
import sys

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "core-vm", "src", "main", "kotlin", "com", "eratw", "port", "core", "host",
    "Cp932Encodable.kt",
)


def encodable_bits():
    """bit i = 码点 i（BMP）是否 CP932 可编码；返回 2048 个 32 位字。"""
    words = [0] * 2048
    for cp in range(0x10000):
        if 0xD800 <= cp <= 0xDFFF:
            continue  # 代理项不是字符，codec 必拒，显式跳过保持幂等可读
        try:
            chr(cp).encode("cp932")
        except UnicodeEncodeError:
            continue
        words[cp >> 5] |= 1 << (cp & 31)
    return words


def fmt_word(w):
    """32 位字 → Kotlin Int 字面量：≥0x80000000 的位型写成负十六进制（位型不变）。"""
    return ("0x%08X" % w) if w < 0x80000000 else ("-0x%08X" % ((1 << 32) - w))


def render_kotlin(words):
    total = sum(bin(w).count("1") for w in words)
    py = sys.version_info
    lines = []
    ap = lines.append
    ap("package com.eratw.port.core.host")
    ap("")
    ap("/**")
    ap(" * CP932(Windows-31J) 可编码码点成员表 —— **生成文件，勿手改**。")
    ap(" *")
    ap(" * 生成器：`tools/fontsubset/gen_cp932_encodable.py`（Python %d.%d.%d 内置 cp932 codec，" % (py[0], py[1], py[2]))
    ap(" * 与 JDK `Windows-31J` 同源，均出自 Microsoft CP932 映射表）；BMP 全扫 **可编码 %d 个码点**，" % total)
    ap(" * 位图 65,536 位 = 2,048 个 Int（bit i = 码点 i；CJK 汉字区在 Unicode 里不连续，")
    ap(" * 区间表会碎成几千段，位图是唯一紧凑形态）。增补平面一律不可编码（CP932 只到 BMP）。")
    ap(" *")
    ap(" * ## 消费方与裁决规则")
    ap(" *")
    ap(" * `render` 的 `FontWidthRuler.compute`：**替身正文族**（Sarasa Mono SC 等顶替")
    ap(" * ＭＳ ゴシック/黑体的随包族，Saitamaar 除外 —— PC 原版对它量的是字体本尊）下，")
    ap(" * 本表命中的码点按静态 CP932 网格（`CellMetrics.cellsOf`）量宽；表外码点才走")
    ap(" * sidecar 真实 advance 链。缘由：字符画/地图语料按原版字体排布，JIS 双字节记号")
    ap(" * （制表符/希腊/数学/罗马数字）在原版字体里全角 2 格，替身字体 hmtx 却给半角")
    ap(" * 500 ⇒ 按 sidecar 推进会把整幅画压碎；而表外字原版走 GDI+ 回退（读数未定义），")
    ap(" * 随包真实 advance 是最好模型 —— 块元素 ▉▂（老虎机字符画）半角一格、组合记号")
    ap(" * 零推进两条既有读数都靠这条保住。")
    ap(" *")
    ap(" * ## 锚")
    ap(" *")
    ap(" * - 原版量宽走段字体本尊：`StringMeasure.cs:42-72`（GetDisplayLength）+")
    ap(" *   `ConsoleStyledString.cs:25,82`（GetFont(style.Fontname)）；")
    ap(" * - 黄金基准把 Ambiguous 符号钉成全角：`fixtures/golden/baseline/pc-golden-root.txt:61`")
    ap(" *   （99 个 `━` 填满 1797px 可绘制宽度）；")
    ap(" * - 语料受害现场：`COLOREDMAPS/DRAW_COLOREDMAP.ERB:125`（HTML_PRINT +")
    ap(" *   `<font face='ＭＳ ゴシック'>`）+ `COLOREDMAPS/COLOREDMAP_09.ERB` CHIREIDEN_1")
    ap(" *   （一阶画布 row07 全行 68 格，sidecar 口径只剩 45 格）。")
    ap(" */")
    ap("object Cp932Encodable {")
    ap("")
    ap("    /** bit i = 码点 i 是否 CP932 可编码；`word[i]` 覆盖码点 `[i*32, i*32+31]`，低位在低码点。 */")
    ap("    private val BITS = intArrayOf(")
    for i in range(0, len(words), 8):
        ap("        %s," % ", ".join(fmt_word(w) for w in words[i:i + 8]))
    ap("    )")
    ap("")
    ap("    /** 该码点（BMP）是否 CP932 可编码；增补平面/负值恒 false。 */")
    ap("    fun encodable(codePoint: Int): Boolean {")
    ap("        if (codePoint < 0 || codePoint > 0xFFFF) return false")
    ap("        val word = BITS[codePoint ushr 5]")
    ap("        return word and (1 shl (codePoint and 31)) != 0")
    ap("    }")
    ap("}")
    return "\n".join(lines) + "\n"


def main():
    words = encodable_bits()
    text = render_kotlin(words)
    out = os.path.normpath(OUT)
    if os.path.exists(out):
        with io.open(out, "r", encoding="utf-8", newline="") as f:
            if f.read() == text:
                print("unchanged:", out)
                return
    with io.open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    total = sum(bin(w).count("1") for w in words)
    print("written:", out, "codepoints:", total)


if __name__ == "__main__":
    main()
