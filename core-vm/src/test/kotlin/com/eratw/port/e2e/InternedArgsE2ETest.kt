package com.eratw.port.e2e

import com.eratw.port.boot.CorpusProfile
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语句参数子树规范化去重（`ArgInterner`）的**端到端等价**钉死。
 *
 * 判据：同一份金标管线产物（`fixtures/golden`，15 节 / 610 行）在
 * 「去重前」「去重后」两次执行里，控制台原文**逐字节相同**；且去重后的原文与
 * PC 基准 `pc-golden-root.txt` 逐行一致（不是只跟自己去重前比 —— 那样两边一起错也绿）。
 *
 * 为什么单独一个装置而不是改 `E2EGoldenTest`：那条闸跑的是「未去重」路径，
 * 本条是它的差分对照；两条都绿才说明去重没有改变任何可观测行为。
 *
 * 与 `PerfGoldenInternedEquivalenceTest` 共用 [PcGoldenColumn] 的**按根分列**：
 * `pc-golden-root.txt` 钉的是 4.981 / 261017 的 PC 采集读数，而当前闸跑 261111
 * （`specs/engine-profiles.yaml` 的 `V4_981`）与 4.980 两种根 —— 语料派生行按列取定值
 * （定值，不是放宽），其余行仍逐字严格。
 */
class InternedArgsE2ETest {

    private val fixtureDir: Path =
        sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
            .first { it.isDirectory }.toPath().toAbsolutePath().normalize()

    private fun runAll(model: GoldenModel): String {
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(model, console, stepCap = 20_000_000)
        rt.execute()
        return console.capturedText()
    }

    @Test
    fun `去重前后全量执行输出逐字节相同`() {
        GoldenPipeline.requireGameCsvDir()
        val model = GoldenPipeline.build(fixtureDir)
        assertEquals(0, model.compileFailures.size, "切片编译失败: ${model.compileFailures}")

        val before = runAll(model)
        val statementsBefore = model.program.statementCount
        val stats = model.program.internStatementArgs()
        val after = runAll(model)

        assertEquals(before, after, "去重前后控制台原文必须逐字节相同（长度 ${before.length} vs ${after.length}）")
        assertEquals(statementsBefore, model.program.statementCount, "语句池下标/条目数不得变化")
        assertTrue(stats.merged > 0, "金标产物里也应存在可合并的同形结点（实测 ${stats.merged}）")

        // 去重后的输出直接对 PC 基准（610 行）—— 反向证明不是「两边一起错」。
        // 基准按语料根两列取（见 [PcGoldenColumn]）：本机 §5 闸喂 4.980 根，而
        // pc-golden-root.txt 钉的是 4.981 读数，三行语料派生行按根替换后仍逐行严格。
        val baseline = fixtureDir.resolve("baseline").resolve(GoldenNormalizer.PC_BASELINE)
        assertTrue(baseline.toFile().exists(), "PC 基准缺失: $baseline")
        val expected = PcGoldenColumn.forCurrentRoot(GoldenNormalizer.readBaseline(baseline.toFile()))
        val actual = GoldenNormalizer.normalize(after)
        val diff = GoldenNormalizer.diff(expected, actual)
        assertTrue(
            diff.identical,
            "去重后输出与 PC 基准不一致（${PcGoldenColumn.describeCurrentRoot()}；基准 ${expected.size} 行 / " +
                "实际 ${actual.size} 行），首差 ${diff.firstMismatch}:\n${diff.preview}",
        )
    }
}

/**
 * PC 基准 `pc-golden-root.txt` 的**按根分列**（语料根口径差；corpusroot 波路线①，不是放宽断言）。
 *
 * 为什么需要：基准文件在 `db76d203`（2026-11-01，u981-golden，组长决策「(a) 接受并白名单登记」）
 * 随 4.981 / **261017** 语料重落，其中几行是**运行期从语料读入**的内容，不由引擎算法决定。
 * 2026-11-14 语料换代到 261111 后，又多了三行语料派生行（见下），故本表从「两列」扩成
 * 「三列」：基准原文（261017 采集）/ 261111 列 / 4.980 列。
 *
 * 4.980 列的三行（两代 4.981 上都同值）：
 *
 * - 第 348 行 `Ｃ感觉LV5 技巧LV2 `（归一化口径；物理第 427 行）——`PRINT_ABL` 打印
 *   `CSV/Abl.csv` 第 0 项名：4.980 文件第 2 行 `0,Ｃ感覚`、4.981 同位置 `0,Ｃ感觉`
 *   （语句实现 [com.eratw.port.core.sem.PrintCharaData.characterDataString]，名字表经
 *   `store.registry.csvTable("ABL")` 由 [GoldenPipeline.gameCsvDir] 指向的根装载）；
 * - 第 349 行 `[性别]`（物理第 428 行）——`PRINT_TALENT` 打印 `CSV/Talent.csv` 第 2 项：
 *   4.980 `2,性別`、4.981 `2,性别`；
 * - 第 454 行 `ENUMVARENDSWITH=92`（物理第 544 行）——`ENUMVAR*` 的 VarKeys 候选名：
 *   系统 31 个 + 游戏 ERH 顶层 `NAME` 结尾 DIM（4.980 实测 52 名 ⇒ 83；4.981 61 名 ⇒ 92，
 *   九名增量逐个取证见 [GoldenPipeline.buildEnumVarNameTable] KDoc）。
 *
 * 261111 列（Palam 属性名简体化 + ERH 名表 +1；261017 列是 PC 采集时的原文）：
 *
 * - 第 353–355 行 —— `PRINT_PALAM` 打印 `CSV/Palam.csv` 的属性名表。261111 把
 *   `潤滑/恭順/欲情/習得/恥情/優越/抑鬱/眠姦` 换成 `润滑/恭顺/情欲/习得/羞耻/优越/抑郁/眠奸`
 *   （8 个键名，逐条 diff 见 `CSV/Palam.csv` 两代对照），其余 26 个名两代同字；
 * - 第 454 行 —— `ENUMVARENDSWITH` 的候选取自 ERH 顶层 `NAME` 结尾 DIM：261111 比 261017
 *   多 1 名（61 → 62）⇒ 92 → 93（见 [GoldenPipeline.buildEnumVarNameTable]）。
 *
 * 这些定值都是**语料文件里的原文**，不是我们算出来的 — 基准冻结在 261017 的 PC 采集面，
 * 而闸按出货语料（261111）跑，两边必然在这些行上分叉。取列前一律核对基准该行仍是
 * 261017 原文，不一致即抛 AssertionError ⇒ 基准被重钉而本表没跟上时立刻红，不会被静默吞掉；
 * 任何表外行漂移照常红（逐行严格对拍）。
 *
 * 重采基准（用 261111 的 PC 宿主重跑一遍）仍是组长的独立立项；在那之前这三列是唯一的
 * 「出货口径」判据，跳掉就同时失去「610 行硬对拍」这层覆盖。
 *
 * 口径变更登记：BOARD「报红·L1 面待修」那一行仍写着「golden 四类必须把 ERATW_GAMEDIR
 * 指到 4.981」，与本表并存 —— 两者语义等价（都只认 4.981 的严格列），差别在于
 * 本表让 4.980 / 261111 两种根下也对拍到底而不是跳过。基准文件本身的归属仍是组长。
 */
internal object PcGoldenColumn {

    /** 一行两列：基准行号（1 起，[GoldenNormalizer.normalize] 口径）+ 基准原文 + 该列替换值。 */
    private class Row(val lineNo: Int, val baseline: String, val column: String)

    /**
     * 4.980 列定值。值取自语料实文件（[GoldenPipeline] 装载同一份 CSV / ERH），
     * 行号取 [GoldenNormalizer] 归一化后的下标（RNG- 段整段剔除后与物理行号不同：
     * 427/428/544 物理 → 348/349/454 归一化）。
     */
    private val ROWS_4_980 = listOf(
        Row(348, "Ｃ感觉LV5 技巧LV2 ", "Ｃ感覚LV5 技巧LV2 "),
        Row(349, "[性别]", "[性別]"),
        Row(454, "ENUMVARENDSWITH=92", "ENUMVARENDSWITH=83"),
    )

    /** 261111 列定值：`CSV/Palam.csv` 简体化后的三行属性名表（原文逐字来自语料文件）+ ERH 名表增量。 */
    private val ROWS_4_981_261111 = listOf(
        Row(
            353,
            "   潤滑[..........]     0   恭順[..........]     0   欲情[..........]     0" +
                "   屈服[..........]     0   習得[..........]     0",
            "   润滑[..........]     0   恭顺[..........]     0   情欲[..........]     0" +
                "   屈服[..........]     0   习得[..........]     0",
        ),
        Row(
            354,
            "   恥情[..........]     0   苦痛[..........]     0   恐怖[..........]     0" +
                "   好意[..........]     0   優越[..........]     0",
            "   羞耻[..........]     0   苦痛[..........]     0   恐怖[..........]     0" +
                "   好意[..........]     0   优越[..........]     0",
        ),
        Row(
            355,
            "   反感[..........]     0   不快[..........]     0   抑鬱[..........]     0" +
                "   眠姦[..........]     0",
            "   反感[..........]     0   不快[..........]     0   抑郁[..........]     0" +
                "   眠奸[..........]     0",
        ),
        // 261111 的 ERH 顶层 `NAME` 结尾 DIM 多 1 名（61 → 62）⇒ ENUMVAR 候选 92 → 93。
        Row(454, "ENUMVARENDSWITH=92", "ENUMVARENDSWITH=93"),
    )

    /** 当前语料根档；判不出根（CI 无游戏数据 / 未登记版本，如退役的 261017）⇒ null，按基准原文逐行严格。 */
    private fun profileOrNull(): CorpusProfile? =
        runCatching { CorpusProfile.detect(GoldenPipeline.gameCsvDir()) }.getOrNull()

    /**
     * 取当前语料根对应的期望列。4.980 ⇒ 三行换成 4.980 列；4.981（= 261111）⇒ 三行换成
     * 261111 列；无根 / 退役根 ⇒ 原样返回基准（逐行严格）。
     * 换前逐行核对基准仍是 261017 原文，不一致即抛 AssertionError（基准重钉必须同步本表）。
     */
    fun forCurrentRoot(expected: List<String>): List<String> = when (profileOrNull()) {
        CorpusProfile.V4_980 -> applyColumn(expected, ROWS_4_980, "4.980")
        CorpusProfile.V4_981 -> applyColumn(expected, ROWS_4_981_261111, "4.981（261111）")
        null -> expected
    }

    private fun applyColumn(expected: List<String>, rows: List<Row>, label: String): List<String> {
        val out = expected.toMutableList()
        for (row in rows) {
            val i = row.lineNo - 1
            if (i !in out.indices) {
                throw AssertionError(
                    "基准只有 ${expected.size} 行，$label 列第 ${row.lineNo} 行越界：基准结构已变，本表须重导（不许静默放过）",
                )
            }
            if (out[i] != row.baseline) {
                throw AssertionError(
                    "基准第 ${row.lineNo} 行已不是 261017 列原文（实际「${out[i]}」，本表记「${row.baseline}」）：" +
                        "基准被重钉而 $label 列表没跟上，先重导列值再放闸",
                )
            }
            out[i] = row.column
        }
        return out
    }

    /** 断言消息里的列口径自述（出红时一眼看出跑的是哪一列）。 */
    fun describeCurrentRoot(): String = when (profileOrNull()) {
        CorpusProfile.V4_980 -> "语料根 4.980 列：348/349/454 三行按 4.980 语料定值，其余逐行严格"
        CorpusProfile.V4_981 -> "语料根 4.981（261111）列：353/354/355/454 四行按 261111 语料定值（Palam.csv 简体化 / ERH 名表 +1），其余逐行严格"
        null -> "未判出语料根（无游戏数据 / 未登记版本 / 退役的 261017）：基准原文逐行严格"
    }
}
