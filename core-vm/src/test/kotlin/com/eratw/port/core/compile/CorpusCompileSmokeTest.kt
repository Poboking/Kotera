package com.eratw.port.core.compile

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag

/**
 * 全量语料编译冒烟（默认跳过；设置 `ERATW_CORPUS` 后运行）。
 *
 * 闸的两个入口都跑**出包字节**：`:core-vm:corpusCompileGate`（Test，6g 堆）与
 * `:core-vm:corpusSmoke`（JavaExec，3g 堆）。两者都由构建脚本先按 `devtools/corpus-patches` 把
 * 语料根 staging 成硬链接副本、套 CB-* 补丁，再把 `ERATW_CORPUS`/`ERATW_GAMEDIR` 指向副本
 * （与出包链 `:tools:erbcc:compileCorpus` 吃同一批字节）⇒ 本用例判的是**玩家包里的字节**。
 *
 * 为什么不在通用 `:core-vm:test` 上跑：金标准对拍（`E2EGoldenTest` / `L3HashChainTest` /
 * `PerfGoldenInternedEquivalenceTest` / `Xml*` 系列）的 oracle 是 PC Emuera 在**真语料原文**上跑
 * 出来的，把全域语料换成补丁字节会让那 20+ 条对拍全差（2026-11-16 实测 8237 tests / 28 failed，
 * 红的清一色是基准对拍）⇒ 通用 test 保持原文口径。本用例判的是补丁后的字节，所以打
 * `@Tag("corpus-gate")`、由通用 test 任务 `excludeTags` 划出（口径不同不是「这条会红所以藏起来」：
 * 原文口径下 CB-005 的 4 行 `[[油炸跳蛋]]` rename 键必然解析失败，原版 `LexicalAnalyzer.cs:808-826`
 * 同拒，那不是回归）。同理也没必要设 `ERATW_ALLOW_ORIGINAL_REJECTS=1` 在原文上凑绿。
 *
 * ```
 * ERATW_CORPUS=<游戏根>/ERB ./gradlew --configure-on-demand :core-vm:corpusCompileGate
 * ```
 */
@Tag("corpus-gate")
class CorpusCompileSmokeTest {

    @Test
    fun fullCorpusCompile() {
        val corpus = System.getenv("ERATW_CORPUS")
        // CMP-68：缺 env 必须走 assumption（计入 skipped），不许 println+return 静默 PASS ——
        // 静默 PASS 会让「按 tests 数缩水抓门控失效」失效。
        Assumptions.assumeTrue(
            !corpus.isNullOrBlank(),
            "未设置 ERATW_CORPUS：全量语料回归按假设跳过（计 skipped，不再静默 PASS）",
        )
        val root: Path = Path(requireNotNull(corpus))
        val limit = System.getenv("ERATW_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
        // 排除今日混入语料根的 GOLDEN_* 采集副本（非原版语料，基线 3,971 文件口径）
        val files = CorpusCompileValidator.listCorpusFiles(root).take(limit)
        val r = CorpusCompileValidator.compile(files, root)
        r.runScope = if (limit == Int.MAX_VALUE) "FULL" else "LIMITED(${files.size})"
        println(
            "语料编译冒烟：文件 ${r.files} 切片 ${r.slicesTotal + r.parseFailures}（成功 ${r.functions} / 失败 ${r.failures.size} / " +
                "解析失败跳过 ${r.parseFailures}） 事件入口 ${r.eventEntries} " +
                "指令 ${r.instructions} 全局槽 ${r.globalSlots} 耗时 ${r.elapsedMs}ms",
        )
        println(
            "基线对比：切片 ${r.slicesTotal}/104,725 指令 ${r.instructions}/~3,970,000 耗时 ${r.elapsedMs}ms/~41,000ms " +
                "池条目 ${r.statementPoolEntries} marker ${r.markerInstructions}",
        )
        val out = System.getenv("ERATW_COMPILE_REPORT") ?: "build/compile-report"
        val abs = CorpusCompileValidator.writeReport(r, root, Path(out))
        println("报告: $abs/summary.md")
        r.failures.take(20).forEach { println("  失败 ${it.file}:${it.line} [${it.function}] ${it.stmtCode ?: ""} ${it.message}") }
        assertTrue(r.functions > 0, "未编译出任何函数")

        // 残值归因：任何解析失败签名都必须落入 R9 两栏之一，「未归类」直接红
        val unclassified = r.parseGroups.values.filter { it.verdict == CorpusCompileValidator.VERDICT_UNCLASSIFIED }
        assertTrue(
            unclassified.isEmpty(),
            "解析失败残值存在未归类组（不允许）：" + unclassified.joinToString("; ") { "${it.signature} ×${it.lines}" },
        )
        // GATE（CONTRACT-M2-E2E §2「不回退」+ §5 R8）：基线 = 0 编译失败 / 0 解析失败切片 / 0 解析失败行。
        // 不许跳过失败切片或缩小语料集来凑绿灯——失败逐条进 failures.tsv 归因。
        // 子集运行（设了 ERATW_LIMIT）时 prescan 符号表不完整，解析失败属伪影，只判编译失败；
        // 判闸以 :core-vm:corpusSmoke 全量（FULL）为准。
        val subset = System.getenv("ERATW_LIMIT")?.toIntOrNull() != null
        // 退化语料档（第三方口上整合包）：允许**已证明「原版也拒」**的残值，但 GAP（原版接受
        // 我们仍拒）与未归类照旧红。典型输入 = 4.981 自带的某个第三方口上引用了 4.981
        // `CSV/_Rename.csv` 里已删掉的 rename 键 ⇒ 原版 `LexicalAnalyzer.cs:808-826` 同样装载失败。
        // **基线 4.980 不开这个档**：它的 0/0 判闸保持不变。
        val allowOriginalRejects = System.getenv("ERATW_ALLOW_ORIGINAL_REJECTS") == "1"
        if (!subset) {
            if (allowOriginalRejects) {
                val gaps = r.parseGroups.values.filter {
                    it.verdict == CorpusCompileValidator.VERDICT_GAP ||
                        it.verdict == CorpusCompileValidator.VERDICT_UNCLASSIFIED
                }
                assertTrue(
                    gaps.isEmpty(),
                    "退化语料档只允许「原版也拒」的残值，出现 GAP/未归类：${
                        gaps.joinToString("; ") { "${it.signature} ×${it.lines}" }
                    }（归因见 $abs/summary.md §7）",
                )
            } else {
                // 契约 R8：parseFailures 与 parseFailureLines 双双入闸（基线 0/0）
                assertTrue(
                    r.parseFailures == 0 && r.parseFailureLines == 0,
                    "解析失败切片 ${r.parseFailures}（${r.parseFailureLines} 行，基线 0/0），归因见 $abs/summary.md §7、§8；" +
                        "第三方整合包语料可设 ERATW_ALLOW_ORIGINAL_REJECTS=1 走退化档（只放行「原版也拒」）",
                )
            }
        }
        assertTrue(
            r.failures.isEmpty(),
            "编译失败 ${r.failures.size} 切片（基线 0，全部属本轮改造引入），分栏见 $abs/summary.md §3、复现见 §5",
        )
    }
}
