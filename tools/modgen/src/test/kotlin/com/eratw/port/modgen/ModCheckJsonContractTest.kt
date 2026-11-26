package com.eratw.port.modgen

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/**
 * `modgen check-mod --json` 的机器可读契约冻结测试。
 *
 * 覆盖四类契约：
 * 1. 形状与字节：固定夹具的 JSON 输出逐字节冻结（三态 + 排序），字段集合/顺序恰好；
 * 2. 语义恒等式：`ok == (errors == 0)`，`errors/warnings` 等于 findings 按 severity 计数，`schema` 固定；
 * 3. 确定性与排序：同输入两次逐字节相同；findings 按 file → line → severity → message 排序，
 *    line 为 null 视作最小；同一行内 severity 名升序（ERROR 在 WARN 前）；
 * 4. 退出码与转义：0/0/1/2 矩阵、退出码 2 时 stdout 不带 JSON，以及引号/反斜杠/换行/制表符/中日文
 *    的转义往返。
 */
class ModCheckJsonContractTest {

    @TempDir
    lateinit var tempDir: Path

    private val contractTopLevelFields = listOf("schema", "ok", "errors", "warnings", "findings")

    private val contractFindingFields = listOf("severity", "file", "line", "message")

    private val scrambledFindings = listOf(
        Finding(Severity.WARN, "b.kt", 2, "zz"),
        Finding(Severity.ERROR, "a.kt", null, "no line"),
        Finding(Severity.ERROR, "b.kt", 2, "bb"),
        Finding(Severity.WARN, "b.kt", 2, "aa"),
        Finding(Severity.ERROR, "b.kt", 2, "aa"),
        Finding(Severity.WARN, "a.kt", 10, "x"),
        Finding(Severity.ERROR, "a.kt", 5, "y"),
    )

    private val scrambledExpectedOrder = listOf(
        "ERROR|a.kt|NULL|no line",
        "ERROR|a.kt|5|y",
        "WARN|a.kt|10|x",
        "ERROR|b.kt|2|aa",
        "ERROR|b.kt|2|bb",
        "WARN|b.kt|2|aa",
        "WARN|b.kt|2|zz",
    )

    // ---- 快照：三态逐字节冻结 ----------------------------------------------------------------

    @Test
    fun `clean json output is frozen byte for byte`() {
        val module = healthyContractModule(tempDir.resolve("clean-snapshot"))
        val run = jsonContractRun(module)
        assertFrozenSnapshot(CLEAN_JSON_CONTRACT_SNAPSHOT, run.out, "无 finding 状态")
        assertEquals(0, run.exit, "无 finding 必须退出 0")
        assertEquals("", run.err, "成功路径不应往 stderr 写东西")
    }

    @Test
    fun `warn only json output is frozen byte for byte`() {
        val module = warnOnlyContractModule(tempDir.resolve("warn-snapshot"))
        val run = jsonContractRun(module)
        assertFrozenSnapshot(WARN_ONLY_JSON_CONTRACT_SNAPSHOT, run.out, "仅 WARN 状态")
        assertEquals(0, run.exit, "只有 WARN 必须退出 0")
        assertEquals("", run.err, "WARN 不是错误，不应写 stderr")
    }

    @Test
    fun `error json output is frozen byte for byte`() {
        val module = errorContractModule(tempDir.resolve("error-snapshot"))
        val run = jsonContractRun(module)
        assertFrozenSnapshot(ERROR_JSON_CONTRACT_SNAPSHOT, run.out, "有 ERROR 状态")
        assertEquals(1, run.exit, "有 ERROR 必须退出 1")
        assertEquals("", run.err, "findings 的正文只走 stdout，stderr 保持干净")
    }

    @Test
    fun `scrambled findings render is frozen byte for byte`() {
        assertFrozenSnapshot(SCRAMBLED_SORT_JSON_CONTRACT_SNAPSHOT, ModCheckJson.render(scrambledFindings), "乱序渲染")
    }

    // ---- 形状：字段集合与顺序 ----------------------------------------------------------------

    @Test
    fun `top level fields are exactly the frozen contract in order`() {
        for ((label, module) in threeStateModules()) {
            val doc = parseContractJson(jsonContractRun(module).out)
            assertEquals(contractTopLevelFields, doc.keys(), "$label 顶层字段集合或顺序漂移")
        }
    }

    @Test
    fun `every finding has exactly severity file line message in order`() {
        for ((label, module) in threeStateModules()) {
            val doc = parseContractJson(jsonContractRun(module).out)
            for (finding in doc.objects("findings")) {
                assertEquals(contractFindingFields, finding.keys(), "$label finding 字段集合或顺序漂移")
            }
        }
    }

    // ---- 语义恒等式 --------------------------------------------------------------------------

    @Test
    fun `ok errors and warnings stay consistent with findings`() {
        val states = linkedMapOf(
            "clean" to healthyContractModule(tempDir.resolve("consistent-clean")),
            "warn" to warnOnlyContractModule(tempDir.resolve("consistent-warn")),
            "error" to errorContractModule(tempDir.resolve("consistent-error")),
            "multi" to multiFindingContractModule(tempDir.resolve("consistent-multi")),
        )
        for ((label, module) in states) {
            val doc = parseContractJson(jsonContractRun(module).out)
            val findings = doc.objects("findings")
            val errors = findings.count { it.string("severity") == "ERROR" }
            val warnings = findings.count { it.string("severity") == "WARN" }
            assertEquals(errors, doc.int("errors"), "$label 的 errors 必须等于 ERROR findings 数")
            assertEquals(warnings, doc.int("warnings"), "$label 的 warnings 必须等于 WARN findings 数")
            assertEquals(errors == 0, doc.bool("ok"), "$label 的 ok 必须恒等于 errors == 0")
            assertEquals(findings.size, errors + warnings, "$label findings 的 severity 只能有 ERROR/WARN")
        }
    }

    @Test
    fun `multi finding module still follows the counting contract`() {
        val module = multiFindingContractModule(tempDir.resolve("multi-contract"))
        val doc = parseContractJson(jsonContractRun(module).out)
        assertEquals(2, doc.int("errors"), "multi 夹具应恰好 2 个 ERROR")
        assertEquals(1, doc.int("warnings"), "multi 夹具应恰好 1 个 WARN")
        assertEquals(3, doc.objects("findings").size, "multi 夹具应恰好 3 条 finding")
        assertFalse(doc.bool("ok"), "有 ERROR 时 ok 必须为 false")
    }

    @Test
    fun `schema is frozen at version one across all states`() {
        for ((label, module) in threeStateModules()) {
            val doc = parseContractJson(jsonContractRun(module).out)
            assertEquals("1", doc.string("schema"), "$label 的 schema 版本被改动，契约需要显式升版")
        }
    }

    @Test
    fun `missing artifact finding renders line as json null`() {
        val module = errorContractModule(tempDir.resolve("null-line"))
        val run = jsonContractRun(module)
        val finding = parseContractJson(run.out).objects("findings").single()
        assertTrue(finding.raw("line") is ContractJsonNull, "缺失生成物的 finding 没有源行，必须是 JSON null")
        assertTrue(run.out.contains("\"line\": null"), "线号必须逐字渲染成 null")
        assertFalse(run.out.contains("\"line\": -1"), "内部占位 -1 不许泄漏到 JSON 里")
    }

    // ---- 排序与确定性 ------------------------------------------------------------------------

    @Test
    fun `scrambled findings sort by file then line then severity then message`() {
        val rendered = ModCheckJson.render(scrambledFindings)
        val signatures = signaturesOf(parseContractJson(rendered))
        assertEquals(scrambledExpectedOrder, signatures, "findings 排序键必须是 file → line → severity → message")
    }

    @Test
    fun `null line sorts before zero and severity breaks same line ties`() {
        val findings = listOf(
            Finding(Severity.WARN, "f.kt", 0, "with zero line"),
            Finding(Severity.ERROR, "f.kt", null, "no line"),
            Finding(Severity.WARN, "f.kt", null, "no line"),
            Finding(Severity.ERROR, "f.kt", 0, "with zero line"),
        )
        val doc = parseContractJson(ModCheckJson.render(findings))
        val expectedOrder = listOf(
            "ERROR|f.kt|NULL|no line",
            "WARN|f.kt|NULL|no line",
            "ERROR|f.kt|0|with zero line",
            "WARN|f.kt|0|with zero line",
        )
        assertEquals(
            expectedOrder,
            signaturesOf(doc),
            "null 行号必须排在 0 之前；同 file+line 内 severity 名升序（ERROR 在 WARN 前）",
        )
    }

    @Test
    fun `same multi finding input is byte identical on repeated runs`() {
        val module = multiFindingContractModule(tempDir.resolve("deterministic"))
        val first = jsonContractRun(module)
        val second = jsonContractRun(module)
        assertEquals(first.out, second.out, "同一输入两次 check-mod --json 必须逐字节相同")
        assertEquals(first.err, second.err, "stderr 也必须稳定")
        assertEquals(first.exit, second.exit, "退出码也必须稳定")
        assertEquals(
            ModCheckJson.render(scrambledFindings),
            ModCheckJson.render(scrambledFindings),
            "直接渲染同一 findings 列表也必须逐字节相同",
        )
    }

    // ---- 退出码矩阵 --------------------------------------------------------------------------

    @Test
    fun `exit codes follow findings matrix`() {
        val clean = jsonContractRun(healthyContractModule(tempDir.resolve("exit-clean")))
        val warnOnly = jsonContractRun(warnOnlyContractModule(tempDir.resolve("exit-warn")))
        val withError = jsonContractRun(errorContractModule(tempDir.resolve("exit-error")))
        val multi = jsonContractRun(multiFindingContractModule(tempDir.resolve("exit-multi")))
        assertEquals(0, clean.exit, "0 finding ⇒ 0")
        assertEquals(0, warnOnly.exit, "仅 WARN ⇒ 0")
        assertEquals(1, withError.exit, "有 ERROR ⇒ 1")
        assertEquals(1, multi.exit, "多个 ERROR ⇒ 1")
        for ((label, run) in listOf("clean" to clean, "warn" to warnOnly, "error" to withError, "multi" to multi)) {
            assertEquals("", run.err, "$label 的 findings 不应写 stderr")
            assertTrue(run.out.startsWith("{\n"), "$label 的 stdout 必须是 JSON 对象")
        }
    }

    @Test
    fun `usage errors exit two with empty stdout and diagnostics on stderr`() {
        val module = healthyContractModule(tempDir.resolve("usage-matrix"))
        val cases = listOf(
            arrayOf(module.toString(), "--bogus") to "unknown option",
            arrayOf(module.toString(), "--json", "--human") to "mutually exclusive",
            arrayOf("--json") to "missing <mod dir>",
        )
        for ((args, marker) in cases) {
            val run = runContractCheck(*args)
            assertEquals(2, run.exit, "用法错必须退出 2：${args.toList()}")
            assertEquals("", run.out, "用法错时 stdout 必须是空串：${args.toList()}")
            assertTrue(run.err.contains(marker), "stderr 应包含 '$marker'，实际='${run.err}'")
        }
    }

    @Test
    fun `json mode usage failure never prints json to stdout`() {
        val module = healthyContractModule(tempDir.resolve("usage-json"))
        val run = runContractCheck("--json", "--bogus", module.toString())
        assertEquals(2, run.exit, "--json 下的用法错仍是 2")
        assertEquals("", run.out, "--json 模式用法错时 stdout 不许出现半截 JSON")
        assertTrue(run.err.contains("unknown option: --bogus"), "诊断只走 stderr，实际='${run.err}'")
    }

    // ---- JSON 转义 ---------------------------------------------------------------------------

    @Test
    fun `message with quotes backslashes newlines tabs and unicode round trips`() {
        val trickyFile = "路径\\带\"引号\"\t和制表符.kt"
        val trickyMessage = "含\"双引号\"、反斜杠\\、换行\n、回车\r、制表符\t、中文与日文：生成物缺失"
        val findings = listOf(
            Finding(Severity.ERROR, trickyFile, 7, trickyMessage),
            Finding(Severity.WARN, "通用/警告.kt", null, "第一行\n第二行"),
        )
        val rendered = ModCheckJson.render(findings)
        val parsed = parseContractJson(rendered).objects("findings")
        assertEquals(2, parsed.size)
        assertEquals(trickyFile, parsed.first { it.string("message") == trickyMessage }.string("file"))
        assertEquals(trickyMessage, parsed.first { it.string("file") == trickyFile }.string("message"))
        assertEquals("第一行\n第二行", parsed.first { it.string("file") == "通用/警告.kt" }.string("message"))
        assertTrue(rendered.contains("\\\""), "双引号必须渲染成 \\\"")
        assertTrue(rendered.contains("\\\\"), "反斜杠必须渲染成 \\\\")
        assertTrue(rendered.contains("\\n"), "换行必须渲染成 \\n")
        assertTrue(rendered.contains("\\t"), "制表符必须渲染成 \\t")
        assertTrue(rendered.contains("\\r"), "回车必须渲染成 \\r")
    }

    @Test
    fun `rendered json keeps unicode raw and never emits unescaped control characters`() {
        val message = "中文与日文：生成物缺失\n下一行\t结束"
        val rendered = ModCheckJson.render(listOf(Finding(Severity.ERROR, "文件.kt", null, message)))
        assertTrue(rendered.endsWith("\n"), "JSON 输出必须以换行结尾")
        assertTrue(
            rendered.lines().all { line -> line.none { it.code < 0x20 } },
            "JSON 正文里禁止未转义控制字符（字符串内的换行/制表符必须转义成两个字符）",
        )
        assertTrue(rendered.contains("\\n"), "message 里的换行必须渲染成 \\n")
        assertTrue(rendered.contains("\\t"), "message 里的制表符必须渲染成 \\t")
        assertTrue(rendered.contains("生成物缺失"), "非 ASCII 按 UTF-8 原样保留，便于编辑器和人读")
        assertFalse(rendered.contains("\\u751f"), "非 ASCII 不该被降级成 \\u 转义")
    }

    // ---- 真进程：exitProcess 分派 -------------------------------------------------------------

    @Test
    fun `real process exits zero and prints the frozen json`() {
        val module = healthyContractModule(tempDir.resolve("process-clean"))
        val run = runContractProcess(tempDir, "check-mod", module.toString(), "--json")
        assertEquals(0, run.exit, "进程退出码应为 0，stderr='${run.err}'")
        assertEquals(CLEAN_JSON_CONTRACT_SNAPSHOT, run.out, "进程 stdout 必须与冻结快照逐字节相同")
    }

    @Test
    fun `real process usage error exits two without json on stdout`() {
        val module = healthyContractModule(tempDir.resolve("process-usage"))
        val run = runContractProcess(tempDir, "check-mod", module.toString(), "--bogus")
        assertEquals(2, run.exit, "用法错在真进程里也必须退出 2")
        assertEquals("", run.out, "用法错时真进程 stdout 必须是空串")
        assertTrue(run.err.contains("unknown option"), "用法诊断应走 stderr，实际='${run.err}'")
    }

    // ---- 辅助 --------------------------------------------------------------------------------

    private fun threeStateModules(): Map<String, Path> = linkedMapOf(
        "clean" to healthyContractModule(tempDir.resolve("three-clean")),
        "warn" to warnOnlyContractModule(tempDir.resolve("three-warn")),
        "error" to errorContractModule(tempDir.resolve("three-error")),
    )

    private fun assertFrozenSnapshot(expected: String, actual: String, label: String) {
        if (expected == actual) return
        fail(
            buildString {
                appendLine("$label 的 --json 输出与冻结快照不一致（期望 ${expected.length} 字符 / 实际 ${actual.length} 字符）")
                appendLine("--- 期望 ---")
                appendLine(visible(expected))
                appendLine("--- 实际 ---")
                appendLine(visible(actual))
            },
        )
    }

    private fun signaturesOf(doc: ContractJsonObject): List<String> = doc.objects("findings").map { finding ->
        val line = when (val value = finding.raw("line")) {
            is ContractJsonNumber -> value.value.toString()
            ContractJsonNull -> "NULL"
            else -> fail("line 字段类型非法：$value")
        }
        "${finding.string("severity")}|${finding.string("file")}|$line|${finding.string("message")}"
    }

    private fun visible(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
