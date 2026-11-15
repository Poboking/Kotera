package com.eratw.port.erbcc

import java.nio.file.Files
import java.nio.file.Path

/**
 * erbcc 侧语料版本档（corpusroot 波，2026-11-05；与 core-vm 测试源集的
 * com.eratw.port.boot.CorpusProfile 同源复制 —— 测试源集互不可见，两模块各留一份，
 * 新增根/换列时两处一起改）。
 *
 * 判根：读语料根下 DIM.ERH 的 `#DIM CONST NOW_VERSION` 值（两根实测：
 * 4.980=20260326、4.981=20261111（2026-11-14 换代；261017 时代的 20261017 已退役）。判不出/不在册 ⇒ AssertionError 判红：
 * 语料被换/被改必须让闸报红，不许退成静默 skip。
 *
 * 钉位出处：
 * - 标签基线：specs/loader.md 附录 D.3 + SymbolsTest KDoc 的两根实测
 *   （4.980=110,066/110,042/6 组；4.981=118,527/118,497/7 组）。
 *   4.981 列于 2026-11-14（L34 PINS-REFRESH-1）在 4.981 根重测：**同一 `NOW_VERSION=20261017`
 *   下语料又加了口上包**（ERB/ERH 4,258→4,324 文件），标签数随之 +1,472；
 *   本列此前登记的是 2026-11-05 的 4.981 读数（117,055/117,025），故 4.981 语料根曾恒红。
 *   7 组重名逐组与 2026-11-05 相同（EVENTFIRST 2 / EVENTLOAD 8 / EVENTTURNEND 2 / EVENTEND 2 /
 *   EVENTCOMEND 10 / EVENTTRAIN 9 / EVENTCOM 4），只有总数与去重名字数变；
 * - PRINT_COLORBAR 生效行：两根 魔改内容/PRINT系/UI_Components_&_Tables.ERB 实读（959 / 1010）；
 * - NOW_VERSION 折叠读数：DimErhFamilyConstTest 两根实跑（其余 ERH 常量两根同值）。
 */
enum class ErbccCorpusProfile(
    val nowVersion: Long,
    val labelsTotal: Int,
    val labelsUnique: Int,
    val dupGroups: Int,
    val dupGroupCounts: Map<String, Int>,
    val colorBarLine: Int,
) {
    V4_980(
        nowVersion = 20260326L,
        labelsTotal = 110_066,
        labelsUnique = 110_042,
        dupGroups = 6,
        dupGroupCounts = mapOf(
            "EVENTFIRST" to 2,
            "EVENTLOAD" to 9,
            "EVENTTURNEND" to 2,
            "EVENTCOMEND" to 6,
            "EVENTTRAIN" to 7,
            "EVENTCOM" to 4,
        ),
        colorBarLine = 959,
    ),
    V4_981(
        // 2026-11-14 语料换代 261017→261111：NOW_VERSION 随根重锚；标签数列待本机 261111 实测回填。
        nowVersion = 20261111L,
        labelsTotal = 119_156, // 2026-11-14 语料换代 261111 实测（261017=118_527；与 corpusSmoke 切片总数 119_156 互证）
        labelsUnique = 119_125, // 2026-11-14 语料换代 261111 实测（261017=118_497）
        dupGroups = 7,
        dupGroupCounts = mapOf(
            "EVENTFIRST" to 2,
            "EVENTLOAD" to 9, // 2026-11-14 语料换代 261111：261017=8（其余组不变）
            "EVENTTURNEND" to 2,
            "EVENTEND" to 2,
            "EVENTCOMEND" to 10,
            "EVENTTRAIN" to 9,
            "EVENTCOM" to 4,
        ),
        colorBarLine = 1010,
    );

    companion object {
        private val NOW_VERSION_LINE = Regex("#DIM\\s+CONST\\s+NOW_VERSION\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

        fun fromNowVersion(v: Long): ErbccCorpusProfile? = entries.firstOrNull { it.nowVersion == v }

        /** 从语料目录（ERB 或游戏根，向上至多 4 段）就近找 DIM.ERH 判根；判不了即 AssertionError。 */
        fun detect(start: Path?): ErbccCorpusProfile {
            val dir = start?.toAbsolutePath()?.normalize()
                ?: throw AssertionError("拿不到语料根：无法判定语料版本")
            var cur: Path? = dir
            repeat(4) {
                val d = cur ?: return@repeat
                for (cand in listOf(d.resolve("DIM.ERH"), d.resolve("ERB").resolve("DIM.ERH"))) {
                    if (Files.isRegularFile(cand)) {
                        val text = String(Files.readAllBytes(cand), Charsets.ISO_8859_1)
                        val v = NOW_VERSION_LINE.find(text)?.groupValues?.get(1)?.toLongOrNull()
                            ?: throw AssertionError("$cand 里读不到 NOW_VERSION 常量：语料根形态不符 eraTW 规约")
                        return fromNowVersion(v)
                            ?: throw AssertionError(
                                "未登记的语料版本 NOW_VERSION=$v（$cand）：先给 ErbccCorpusProfile 新增一列并按波契约重落，再放闸",
                            )
                    }
                }
                cur = d.parent
            }
            throw AssertionError("$dir 就近（含上溯 4 段）找不到 DIM.ERH：语料根不完整，判红不降级")
        }
    }
}
