package com.eratw.port.erbcc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 表达式位被裁内建 × `--engine-report.unknownFunctions` 的定性钉（B7-E2E，契约 ENGCOMPAT §8.6）。
 *
 * ## 本用例回答 §8.5 留验：`unknownFunctions` 不是「编译期不可达」的永空面
 *
 * B6 收口怀疑：表达式里调用被裁内建会在解析期被 [ExprCallTable.classify] 拒掉、
 * 走不到 [com.eratw.port.core.compile.ArgSignatureCheck] 的 `builtinUnknownSink` 扫描位。
 * 实测证伪该前提，三相证据（锚均为主源实码）：
 *
 * 1. **解析相位不拒**：表达式位 `NAME(...)` 的存在性来源是 `IdentifierResolver.resolveFunction`
 *   （[com.eratw.port.core.expr.Parser] 的 `functionCall`，core-vm expr/Parser.kt:556-564），
 *   erbcc/语句层实现查的是**全量静态符号表** [com.eratw.port.core.stmt.BuiltinSymbols]
 *   （core-vm stmt/ParseSupport.kt:117/:165-166），不随 `--engine-profile` 收窄 ⇒
 *   `SQL_CONNECT(...)` 在 v24 档照样解析成 [com.eratw.port.core.expr.FuncCall] 节点。
 * 2. **降级相位保留节点**：[ExprCallTable.classify]（ErbProgramCompiler.kt:3696-3704）对
 *   被裁名判 `UNKNOWN`，`lowerFuncCall`（ErbProgramCompiler.kt:2599-2607）对 `BUILTIN`/`UNKNOWN`
 *   **保持原节点**（运行期未定义路径），不抛 ⇒ FuncCall 进产物表达式池/语句池。
 * 3. **联结后扫描位发火**：`ErbProgramCompiler.build()` 末尾的
 *   [com.eratw.port.core.compile.ArgSignatureCheck.checkProgram]（ErbProgramCompiler.kt:609-615）
 *   经目标档视图查不到该方法名，`checkOneBuiltin` 的 sink 分支（ArgSignatureCheck.kt:350-354）
 *   上报 (名字, 行) ⇒ 报告 `unknownFunctions` 命中。
 *
 * B6 夹具恒空的真实原因更朴素：**夹具里没有一条表达式位被裁内建调用**——
 * `minicorpus-engine` 只有行首命令面的 snake-only 语句。
 *
 * ## 行号与 file 字段口径（实测钉，见各用例断言）
 *
 * - `unknownFunctions.line` = 调用行的**物理 1 基行号**（sink 给 0 基、CorpusCompile 的
 *   接线 +1，CorpusCompile.kt:1169-1173），与 `unknownCommands.line` 同口径；
 * - `unknownFunctions.file` = **空串**（编译期函数池统一编译，FuncCall 节点无源文件维度，
 *   CorpusCompile.kt:1158-1160 的接线注释明许）；报告消费方（渲染面）按「行号 + 名字」使用。
 *
 * ## I7 侧
 *
 * snake_4981 档该名字可见 ⇒ 零命中；无 flag 编译放行且产物与 snake_4981 逐字节一致。
 * v24/em_ee 档**编译不拒绝**（表达式位被裁名保持节点、运行期才红），报告是出包前的
 * 唯一静态取证面——这正是 D11「兼容性报告必填缺口」的存在理由，也被用例 1/2 钉住。
 */
class EngineProfileExprUnknownFunctionReportTest {

    /** 把 resources 夹具复制到临时语料根（`ERB/` 子树，与 [EngineProfileMiniCorpusTest] 同形）。 */
    private fun copyFixture(corpus: Path) {
        val src = EngineProfileExprUnknownFunctionReportTest::class.java
            .getResource("/minicorpus-engine-expr/ERB")
            ?: error("夹具缺失 /minicorpus-engine-expr/ERB")
        val root = Path.of(src.toURI())
        val erbDir = corpus.resolve("ERB")
        Files.createDirectories(erbDir)
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { f ->
                f.copyTo(erbDir.resolve(root.relativize(f)), overwrite = true)
            }
        }
    }

    private fun artifactShas(out: Path): Map<String, String> {
        val dir = out.resolve("erbc.v1")
        return ARTIFACTS.associateWith { name ->
            val p = dir.resolve(name)
            assertTrue(Files.isRegularFile(p), "产物缺失 $name（out=$out）")
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))
                .joinToString("") { "%02x".format(it) }
        }
    }

    /** 跑一次 compile（可选 profile + report），返回 (退出码, 报告文本或 null, 产物 sha 表或 null)。 */
    private fun compile(dir: Path, profile: String?): Triple<Int, String?, Map<String, String>?> {
        val corpus = dir.resolve("corpus")
        copyFixture(corpus)
        val out = dir.resolve("out-$profile")
        val report = dir.resolve("report-$profile.json")
        val args = mutableListOf("--corpus", corpus.toString(), "--out", out.toString())
        if (profile != null) {
            args += listOf("--engine-profile", profile, "--engine-report", report.toString())
        }
        val code = cmdCompile(args)
        return Triple(
            code,
            profile?.let { if (Files.isRegularFile(report)) report.readText() else null },
            if (Files.isDirectory(out.resolve("erbc.v1"))) artifactShas(out) else null,
        )
    }

    // ------------------------------------------------------------------
    // 1) v24 档：表达式位被裁内建 ⇒ unknownFunctions 命中、编译放行（§8.5 留验的正面回答）
    // ------------------------------------------------------------------

    @Test
    fun `v24 档表达式位被裁内建命中 unknownFunctions 且编译放行`(@TempDir dir: Path) {
        val (code, report, shas) = compile(dir, "v24_1824")
        assertEquals(0, code, "表达式位被裁名保持节点（不拒编），v24 档本夹具必须退出 0")
        assertNotNull(shas, "退出 0 必须落盘产物")
        val json = assertNotNull(report, "带 --engine-report 必须写出报告")
        // 命中形状：名字 + 空 file + 物理 1 基行号（IF 在夹具第 8 行）
        assertTrue(
            "{\"name\": \"SQL_CONNECT\", \"file\": \"\", \"line\": 8}" in json,
            "unknownFunctions 必须命中 SQL_CONNECT（第 8 行、file 空串口径）：\n$json",
        )
        assertTrue("\"unknownFunctions\": 1" in json, "counts.unknownFunctions = 1：\n$json")
        // 行首命令面本夹具零命中（本夹具没有 snake-only 行首命令）
        assertTrue("\"unknownCommands\": 0" in json, "counts.unknownCommands = 0：\n$json")
        assertTrue("\"parseFailures\": 0" in json, "被裁名不产生解析失败（D11：报告才是取证面）：\n$json")
    }

    // ------------------------------------------------------------------
    // 2) em_ee 档：SQL_CONNECT 同不在面 ⇒ 同命中（两非缺省档都发火，不是 v24 孤例）
    // ------------------------------------------------------------------

    @Test
    fun `em_ee 档同命中 SQL_CONNECT`(@TempDir dir: Path) {
        val (code, report, _) = compile(dir, "em_ee_1824_v22")
        assertEquals(0, code, "em_ee 档本夹具同样放行（表达式位不拒编）")
        val json = assertNotNull(report)
        assertTrue(
            "{\"name\": \"SQL_CONNECT\", \"file\": \"\", \"line\": 8}" in json,
            "em_ee 函数面无 SQL_CONNECT，必须同命中：\n$json",
        )
        assertTrue("\"unknownFunctions\": 1" in json, "counts.unknownFunctions = 1：\n$json")
    }

    // ------------------------------------------------------------------
    // 3) snake_4981 档：名字在本档可见 ⇒ 零命中（假阳性闸：sink 不是「有表达式调用就报」）
    // ------------------------------------------------------------------

    @Test
    fun `snake_4981 档零命中`(@TempDir dir: Path) {
        val (code, report, _) = compile(dir, "snake_4981")
        assertEquals(0, code, "snake_4981 全面必须退出 0")
        val json = assertNotNull(report)
        assertTrue("\"unknownFunctions\": 0" in json, "snake 档 SQL_CONNECT 可见，必须零命中：\n$json")
        assertTrue("\"unknownFunctions\": []" in json, "unknownFunctions 数组必须为空：\n$json")
    }

    // ------------------------------------------------------------------
    // 4) I7：无 flag 编译与显式 snake_4981 产物逐字节一致（本夹具上两路都不过滤出差异）
    // ------------------------------------------------------------------

    @Test
    fun `无 flag 与 snake_4981 产物逐字节一致`(@TempDir dir: Path) {
        val (codeNo, _, shasNo) = compile(dir, null)
        val (codeSnake, _, shasSnake) = compile(dir, "snake_4981")
        assertEquals(0, codeNo)
        assertEquals(0, codeSnake)
        val no = assertNotNull(shasNo)
        val snake = assertNotNull(shasSnake)
        // 无 flag 路不落报告（corpus 相同、两次 out 目录名不同 ⇒ manifest 不比，
        // program.erbc/meta/stmt.idx 只带相对路径可比）
        assertEquals(
            no - "manifest.txt", snake - "manifest.txt",
            "snake_4981 档产物必须与无 flag 逐字节一致（I7）",
        )
        assertNotNull(dir.resolve("out-null"), "无 flag 编译必须落产物")
        assertTrue(
            !Files.exists(dir.resolve("report-null.json")),
            "不带 --engine-profile 时不得出现报告文件（I7 零干扰）",
        )
    }

    private companion object {
        /** 参与逐字节对拍的产物（manifest.txt 含 corpus-root 绝对路径，不比）。 */
        val ARTIFACTS = listOf("program.erbc", "program.meta", "program.stmt.idx")
    }
}
