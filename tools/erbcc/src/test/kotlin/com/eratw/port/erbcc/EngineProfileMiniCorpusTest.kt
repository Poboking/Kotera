package com.eratw.port.erbcc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * mini 语料 × `--engine-profile` 的编译/扫描面判据（B6 ENGC-3b，契约 ENGCOMPAT §8.1）。
 *
 * 夹具：test resources `minicorpus-engine/ERB/` 两个 ERB —— `safe.erb`（全 PRINTL/PRINTV/
 * RETURN 类三档通用语句）与 `snake_only.erb`（含 `SETBGIMAGE` / `PLAYBGM` 两条 snake-only
 * 命令，v24/em_ee 档应被裁）。
 *
 * ## 基线对拍口径
 *
 * 无 flag 的三件语义产物（`program.erbc` / `program.meta` / `program.stmt.idx`）sha256
 * 在本 lane **动工前**用未改动代码实测（2026-11-12，读数见各用例常量注释）；`manifest.txt`
 * 不参与逐字节对拍 —— 它含 `compiler-digest`（erbcc/core-vm 源码树摘要）与 `corpus-root`
 * 绝对路径，本 lane 改 CLI 面必然变前者、@TempDir 必然变后者（确定性判据另由
 * `DeterministicCompileTest` 钉住）。
 *
 * ## 三档过滤均已生效（组长接线 main@579240a3e 后复跑）
 *
 * 本 lane 动工时 core-vm 的编译期查表缝（`CompileBuiltinView` / `EngineProfiles.legalStatementNames`）
 * 尚未解锁，用例 3/4 当时是**诚实红**（带 profile 零过滤）。组长接线合流后复跑：4/0 全绿。
 * 反向证明三条（组长实测 2026-11-12，摘掉即红、恢复即绿）：
 * ① `CorpusCompile` 行首合法集置 null ⇒ 用例 3 红（期望退出 1 实得 0）；
 * ② `Main.cmdScan` 行首集退回静态 568 项表 ⇒ 用例 4 红（snake_only.erb 错误数 0）；
 * ③ 行首过滤泄漏进无 flag 路（`headLexer` 恒建 + 无 profile 也喂 v24 合法集）⇒ 用例 1、2 红
 *    （基线 sha 不再复现）——即 I7 零干扰闸不是空转。
 */
class EngineProfileMiniCorpusTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 把 resources 夹具复制到临时语料根（`ERB/` 子树，与真语料同形）。 */
    private fun copyFixture(corpus: Path) {
        val src = EngineProfileMiniCorpusTest::class.java.getResource("/minicorpus-engine/ERB")
            ?: error("夹具缺失 /minicorpus-engine/ERB")
        val root = Path.of(src.toURI())
        val erbDir = corpus.resolve("ERB")
        Files.createDirectories(erbDir)
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { f ->
                val rel = root.relativize(f)
                f.copyTo(erbDir.resolve(rel), overwrite = true)
            }
        }
    }

    /** 三件语义产物：名字 → sha256。 */
    private fun artifactShas(out: Path): Map<String, String> {
        val dir = out.resolve("erbc.v1")
        return ARTIFACTS.associateWith { name ->
            val p = dir.resolve(name)
            assertTrue(Files.isRegularFile(p), "产物缺失 $name（out=$out）")
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))
                .joinToString("") { "%02x".format(it) }
        }
    }

    // ------------------------------------------------------------------
    // 1) 无 flag：与动工前基线逐字节一致（I7）
    // ------------------------------------------------------------------

    @Test
    fun `无 flag 编译与动工前基线逐字节一致`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        copyFixture(corpus)
        val result = com.eratw.port.erbcc.compile.CorpusCompile.compile(corpus)
        assertTrue(result.ok, "mini 语料必须编译通过：${result.failures.take(5)}")
        val out = dir.resolve("out")
        com.eratw.port.erbcc.compile.ErbcArtifacts.write(
            outDir = out,
            program = assertNotNull(result.program),
            corpusRoot = corpus,
            sourceFiles = result.sourceFiles,
            stats = result.stats,
            bootMetadata = result.bootMetadata,
        )
        assertEquals(
            BASELINE_SHAS,
            artifactShas(out) - "manifest.txt",
            "无 flag 三件产物必须与动工前基线（2026-11-12 实测读数）逐字节一致（I7 零过滤）",
        )
    }

    // ------------------------------------------------------------------
    // 2) 显式 snake_4981：全通用语句语料的产物与无 flag 一致
    // ------------------------------------------------------------------

    @Test
    fun `snake_4981 档产物与无 flag 逐字节一致`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        copyFixture(corpus)
        val outNoFlag = dir.resolve("out-noflag")
        // cmdCompile 收到的是已剥掉 "compile" 子命令的参数表（main 里 args.drop(1)）。
        val codeNoFlag = cmdCompile(
            listOf("--corpus", corpus.toString(), "--out", outNoFlag.toString()),
        )
        assertEquals(0, codeNoFlag, "无 flag 编译应退出 0")
        val outSnake = dir.resolve("out-snake")
        val codeSnake = cmdCompile(
            listOf(
                "--corpus", corpus.toString(), "--out", outSnake.toString(),
                "--engine-profile", "snake_4981",
            ),
        )
        assertEquals(0, codeSnake, "snake_4981 编译应退出 0")
        val noFlag = artifactShas(outNoFlag)
        val snake = artifactShas(outSnake)
        // manifest 不比（corpus-root 相同、compiler-digest 同源码树应也相同，但口径上不依赖它）
        assertEquals(noFlag - "manifest.txt", snake - "manifest.txt", "snake_4981 档产物必须与无 flag 一致")
    }

    // ------------------------------------------------------------------
    // 3) v24 档：snake-only 命令走既有拒绝路径 + 报告命中（反向证明 ① 摘掉行首集即红）
    // ------------------------------------------------------------------

    @Test
    fun `v24 档编 snake-only 语料走拒绝路径且报告列出行号`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        copyFixture(corpus)
        val out = dir.resolve("out")
        val report = dir.resolve("engine-report.json")
        val code = cmdCompile(
            listOf(
                "--corpus", corpus.toString(), "--out", out.toString(),
                "--engine-profile", "v24_1824", "--engine-report", report.toString(),
            ),
        )
        // 期望：strict 下编译退出 1（既有拒绝路径，不新增文案），报告完整写出。
        assertEquals(1, code, "v24 档编 snake-only 命令必须走既有拒绝路径退出 1")
        assertTrue(Files.isRegularFile(report), "编译失败也要先写出报告（先收集后退出）")
        val json = report.readText()
        assertTrue("\"profile\": \"v24_1824\"" in json, "报告必须钉住档位：\n$json")
        for ((name, line) in listOf("SETBGIMAGE" to 2, "PLAYBGM" to 3)) {
            assertTrue(
                "\"name\": \"$name\", \"file\": \"ERB/snake_only.erb\", \"line\": $line" in json,
                "报告必须列出 $name（ERB/snake_only.erb:$line）：\n$json",
            )
        }
        assertTrue("\"unknownCommands\": 2" in json, "counts.unknownCommands = 2：\n$json")
    }

    // ------------------------------------------------------------------
    // 4) v24 档 scan：snake-only 行首走既有 InvalidLine/解析失败路径（反向证明 ② 退回静态表即红）
    // ------------------------------------------------------------------

    @Test
    fun `v24 档 scan 对 snake-only 行首走解析失败路径`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        copyFixture(corpus)
        val reportDir = dir.resolve("scan-report")
        cmdScan(
            listOf(
                "--corpus", corpus.toString(), "--out", reportDir.toString(),
                "--engine-profile", "v24_1824",
            ),
        )
        val json = reportDir.resolve("report.json").readText()
        // snake_only.erb 的两条 snake-only 命令必须落入既有解析失败路径（赋值两段式失败 →
        // InvalidLine 同款），不新增报错文案（I4）；safe.erb 必须零错误。
        // 取「files」明细段（errorGroups 里也有同名 file，不能直接 substringAfter 首次出现）；
        // 词法序 safe.erb 在前、snake_only.erb 在后，块边界用相邻文件名与数组收尾切分。
        val filesJson = json.substringAfter("\"files\": [")
        val snakeBlock = filesJson
            .substringAfter("\"file\": \"ERB/snake_only.erb\"")
            .substringBefore("\n  ]")
        val safeBlock = filesJson
            .substringAfter("\"file\": \"ERB/safe.erb\"")
            .substringBefore("\"file\": \"ERB/snake_only.erb\"")
        assertTrue("\"errors\": 2" in snakeBlock, "snake_only.erb 必须报 2 处解析失败：\n$snakeBlock")
        // scan 报告的 line 是 0 基口径（Diag 直传 LogicalLine.lineNo）：SETBGIMAGE=1、PLAYBGM=2。
        for (line in 1..2) {
            assertTrue("\"line\": $line" in snakeBlock, "第 ${line + 1} 行（1 基）必须有诊断：\n$snakeBlock")
        }
        assertTrue("\"errors\": 0" in safeBlock, "safe.erb 全通用语句必须零错误：\n$safeBlock")
    }

    // ------------------------------------------------------------------
    // 5) 行首预识别不误杀赋值行/自定义方法行（2026-11-12 eratohoReverse 实测抓到的缺陷：
    //    修复前带任意 --engine-profile 时所有变量赋值行被记 unknownCommands 并毒化切片，
    //    eraTW 自家语料 20 文件即 2240 处失败 —— 共享夹具全为命令行首故此前漏网）
    // ------------------------------------------------------------------

    /** 内联夹具：赋值行 + 复合赋值 + 后置自增 + 空 RHS（行首全是变量名）。 */
    private fun writeAssignFixture(corpus: Path) {
        val erb = corpus.resolve("ERB")
        Files.createDirectories(erb)
        erb.resolve("assign.erb").writeText(
            """
            @ASSIGN_TEST
            #LOCALSIZE 2
            	LOCAL = 1
            	LOCAL += 1
            	LOCAL *= 2
            	LOCAL++
            	LOCALS:0 = abc
            	PRINTV LOCAL
            	RETURN 0
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `snake_4981 档下赋值行不被行首预识别误杀`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeAssignFixture(corpus)
        val out = dir.resolve("out")
        val code = cmdCompile(
            listOf(
                "--corpus", corpus.toString(), "--out", out.toString(),
                "--engine-profile", "snake_4981",
            ),
        )
        assertEquals(0, code, "赋值行（变量名行首）必须放行给 parser：")
    }

    @Test
    fun `v24_1824 档下赋值行同样放行`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeAssignFixture(corpus)
        val out = dir.resolve("out")
        val report = dir.resolve("engine-report.json")
        val code = cmdCompile(
            listOf(
                "--corpus", corpus.toString(), "--out", out.toString(),
                "--engine-profile", "v24_1824", "--engine-report", report.toString(),
            ),
        )
        assertEquals(0, code, "非缺省档下赋值行同样必须放行：")
        val json = report.readText()
        assertTrue("\"unknownCommands\": 0" in json, "夹具无未知命令头，报告必须为 0：\n$json")
    }

    @Test
    fun `非赋值的未知行首仍被预识别拒绝并进报告`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        val erb = corpus.resolve("ERB")
        Files.createDirectories(erb)
        erb.resolve("unknown_head.erb").writeText(
            """
            @U
            	FOOBAR 1
            	PRINTL done
            	RETURN 0
            """.trimIndent() + "\n",
        )
        val out = dir.resolve("out")
        val report = dir.resolve("engine-report.json")
        val code = cmdCompile(
            listOf(
                "--corpus", corpus.toString(), "--out", out.toString(),
                "--engine-profile", "snake_4981", "--engine-report", report.toString(),
            ),
        )
        assertEquals(1, code, "非赋值未知行首必须走既有拒绝路径退出 1")
        val json = report.readText()
        assertTrue(
            "\"name\": \"FOOBAR\"" in json,
            "报告必须点名未知行首 FOOBAR（与 parseCodeLine 同构：非命令/非方法且读不出赋值运算符）：\n$json",
        )
    }

    private companion object {
        /** 参与逐字节对拍的三件语义产物（manifest.txt 因 compiler-digest/corpus-root 不在内）。 */
        val ARTIFACTS = listOf("program.erbc", "program.meta", "program.stmt.idx")

        /**
         * 动工前基线（2026-11-12，未改动代码 + 本夹具实测；探针跑法 = [CorpusCompile.compile]
         * 缺省参数，与用例 1 同口径）。
         *
         * 口径演进：首测夹具误平铺到语料根（无 `ERB/` 子目录）后修正为与真语料同构 ——
         * `program.meta` 唯一携带源清单相对路径故随之更新（1fdb0eaf…）；`program.erbc` /
         * `program.stmt.idx` 与首测逐字节同值（5f7c8a01… / 311e20ca…），即容器面在两次夹具
         * 形态与两轮代码状态下都零漂移，交叉验证无 flag 路径零干扰（I7）。
         */
        val BASELINE_SHAS = mapOf(
            "program.erbc" to "5f7c8a01ec987cabcd9c7c4e6b9323dfcc5170311ec109f7d0a1135bc2836e65",
            "program.meta" to "1fdb0eaf16068f0008aa613f9b73de63433ed2e79150a09e044ab9cba9fcef31",
            "program.stmt.idx" to "311e20ca4b0e4009299c5dae6ac86a0e95d1956eabf1709a3c4c1a350d1d0053",
        )
    }
}
