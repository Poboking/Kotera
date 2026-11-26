package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `check-mod --json` 的机器可读输出契约：单文档、字段形状、确定性、与 `--schema`/`--human` 的组合。
 *
 * 断言用本文件末尾的最小 JSON 读取器真的解析 stdout —— 只做字符串包含不足以证明「stdout 只有
 * 一个 JSON 文档」（缺一个括号或混进人类可读 summary 行都能混过去）。
 */
class ModCheckJsonTest {

    @TempDir
    lateinit var tempDir: Path

    private fun healthyModule(): Path {
        val module = scaffoldModule(tempDir)
        writeGenerated(module)
        return module
    }

    private fun modFile(module: Path): Path = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt")

    /** 在 `class MyMod : EraMod {` 后插入成员（每个元素一行，统一缩进 4 空格）。 */
    private fun insertMembers(module: Path, vararg members: String) {
        val block = members.joinToString(separator = "\n", postfix = "\n") { "    $it" }
        replaceOnce(modFile(module), "class MyMod : EraMod {\n", "class MyMod : EraMod {\n$block")
    }

    private fun lineOf(module: Path, fragment: String): Int {
        val index = modFile(module).readLines().indexOfFirst { it.contains(fragment) }
        assertTrue(index >= 0, "fragment not found in ${modFile(module)}: $fragment")
        return index + 1
    }

    @Test
    fun `clean module json reports ok with zero counts`() {
        val run = runCheck(healthyModule().toString(), "--json")
        assertEquals(0, run.exit, "stderr=${run.err}")
        assertEquals("", run.err)
        val doc = parseJson(run.out)
        assertEquals("1", doc["schema"])
        assertEquals(true, doc["ok"])
        assertEquals(0, doc["errors"])
        assertEquals(0, doc["warnings"])
        assertTrue(findingsOfJson(doc).isEmpty(), "findings=${doc["findings"]}")
        assertTrue(run.out.endsWith("}\n"), "JSON document must end with a single newline: ${run.out}")
        assertTrue(!run.out.contains("check-mod:"), "stdout must not contain the human summary: ${run.out}")
    }

    @Test
    fun `json surfaces code entry findings without changing document shape`() {
        val module = healthyModule()
        val codeDir = Files.createDirectories(module.resolve("code"))
        Files.writeString(codeDir.resolve("manifest.json"), """{"packageId":"eratw.mymod"}""")
        val run = runCheck(module.toString(), "--json")
        assertEquals(1, run.exit, "stderr=${run.err}")
        val doc = parseJson(run.out)
        assertEquals(setOf("schema", "ok", "errors", "warnings", "findings"), doc.keys)
        assertEquals(false, doc["ok"])
        assertEquals(doc["errors"], findingsOfJson(doc).count { it["severity"] == "ERROR" })
        val finding = findingsOfJson(doc).single { (it["message"] as String).contains("CODE_ENTRYPOINT_MISSING") }
        assertEquals("ERROR", finding["severity"])
        assertEquals("code/manifest.json", finding["file"])
        assertEquals(null, finding["line"])
    }

    @Test
    fun `json reports error finding with null line and exits one`() {
        val module = healthyModule()
        Files.delete(generatedFile(module))
        val run = runCheck(module.toString(), "--json")
        assertEquals(1, run.exit, "stderr=${run.err}")
        val doc = parseJson(run.out)
        assertEquals(false, doc["ok"])
        assertEquals(doc["errors"], findingsOfJson(doc).count { it["severity"] == "ERROR" })
        assertEquals(true, (doc["errors"] as Int) >= 1, "errors=${doc["errors"]}")
        val finding = findingsOfJson(doc).single { it["severity"] == "ERROR" }
        assertEquals("schemas/My.schema.tsv", finding["file"])
        assertEquals(null, finding["line"])
        assertTrue((finding["message"] as String).contains("生成物缺失"), "message=${finding["message"]}")
        assertTrue(!run.out.contains("check-mod:"), "stdout must not contain the human summary: ${run.out}")
    }

    @Test
    fun `json error carries integer line for source findings`() {
        val module = healthyModule()
        insertMembers(module, "override val apiVersion: Int = ${ModApi.VERSION + 1}")
        val run = runCheck(module.toString(), "--json")
        assertEquals(1, run.exit, "stderr=${run.err}")
        val finding = findingsOfJson(parseJson(run.out))
            .single { it["severity"] == "ERROR" && (it["message"] as String).contains("apiVersion") }
        assertEquals("src/main/kotlin/eratw/mymod/MyMod.kt", finding["file"])
        assertEquals(lineOf(module, "override val apiVersion"), finding["line"])
        assertTrue((finding["message"] as String).contains("比当前引擎新"), "message=${finding["message"]}")
    }

    @Test
    fun `warn only json reports ok true and exits zero`() {
        val module = healthyModule()
        insertMembers(module, "override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}")
        val run = runCheck(module.toString(), "--json")
        assertEquals(0, run.exit, "stderr=${run.err}")
        val doc = parseJson(run.out)
        assertEquals(true, doc["ok"])
        assertEquals(0, doc["errors"])
        assertEquals(1, doc["warnings"])
        val finding = findingsOfJson(doc).single()
        assertEquals("WARN", finding["severity"])
        assertEquals("src/main/kotlin/eratw/mymod/MyMod.kt", finding["file"])
        assertTrue((finding["message"] as String).contains("dataVersion 仍是缺省 0"), "message=${finding["message"]}")
    }

    @Test
    fun `json output is byte identical across runs`() {
        val module = healthyModule()
        insertMembers(
            module,
            "override val apiVersion: Int = ${ModApi.VERSION + 1}",
            "override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}",
        )
        val first = runCheck(module.toString(), "--json")
        val second = runCheck(module.toString(), "--json")
        assertEquals(first.exit, second.exit)
        assertEquals(first.out, second.out)
        assertTrue(first.out.isNotEmpty(), "expected a JSON document")
    }

    @Test
    fun `json combines with explicit schema flag`() {
        val module = healthyModule()
        val schema = schemaFile(module).toString()
        val run = runCheck(module.toString(), "--schema", schema, "--json")
        assertEquals(0, run.exit, "stderr=${run.err}")
        val doc = parseJson(run.out)
        assertEquals("1", doc["schema"])
        assertEquals(true, doc["ok"])
        assertTrue(findingsOfJson(doc).isEmpty(), "findings=${doc["findings"]}")
    }

    @Test
    fun `render escapes quotes backslashes newlines and control characters`() {
        val message = "期望 \"引号\" 与 C:\\mod\\x 以及\n第二行\t和回车\r还有控制符\u0001"
        val rendered = ModCheckJson.render(listOf(Finding(Severity.ERROR, "src/a.kt", 3, message)))
        val finding = findingsOfJson(parseJson(rendered)).single()
        assertEquals(message, finding["message"])
        assertEquals("ERROR", finding["severity"])
        assertEquals(3, finding["line"])
        assertTrue(rendered.contains("\\\"引号\\\""), rendered)
        assertTrue(rendered.contains("C:\\\\mod\\\\x"), rendered)
        assertTrue(rendered.contains("\\n第二行"), rendered)
        assertTrue(rendered.contains("\\t"), rendered)
        assertTrue(rendered.contains("\\r"), rendered)
        assertTrue(rendered.contains("\\u0001"), rendered)
    }

    @Test
    fun `bad directory with json exits two and writes no stdout`() {
        val missing = tempDir.resolve("no-such-mod")
        val run = runCheck(missing.toString(), "--json")
        assertEquals(2, run.exit)
        assertEquals("", run.out)
        assertTrue(run.err.contains("mod directory not found"), "stderr=${run.err}")
    }

    @Test
    fun `json and human together is a usage error`() {
        val module = healthyModule()
        val run = runCheck(module.toString(), "--json", "--human")
        assertEquals(2, run.exit)
        assertEquals("", run.out)
        assertTrue(run.err.contains("--json and --human are mutually exclusive"), "stderr=${run.err}")
        assertTrue(run.err.contains("Usage: modgen check-mod"), "stderr=${run.err}")
    }

    @Test
    fun `human mode output stays byte identical when json flag is absent`() {
        val module = healthyModule()
        insertMembers(module, "override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}")
        val finding = findingsOf(module).single()
        // println 用平台行分隔符，这里按同一口径拼期望值（其余部分逐字节恒定）。
        val separator = System.lineSeparator()
        val expected = "${finding.file}:${finding.line}: ${finding.severity}: ${finding.message}$separator" +
            "check-mod: 0 error(s), 1 warning(s)$separator"
        val plain = runCheck(module.toString())
        assertEquals(0, plain.exit, "stderr=${plain.err}")
        assertEquals(expected, plain.out)
        assertTrue(!plain.out.contains("\"schema\""), "human output must not contain JSON fields: ${plain.out}")
        val explicit = runCheck(module.toString(), "--human")
        assertEquals(plain.out, explicit.out)
        assertEquals(plain.exit, explicit.exit)
    }
}

private data class JsonRun(val exit: Int, val out: String, val err: String)

private fun runCheck(vararg args: String): JsonRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = PrintStream(out, true, "UTF-8").use { outStream ->
        PrintStream(err, true, "UTF-8").use { errStream ->
            ModCheck.runCli(arrayOf(*args), outStream, errStream)
        }
    }
    return JsonRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
}

private fun parseJson(text: String): Map<String, Any?> {
    val root = JsonCursor(text).parseDocument()
    val map = root as? Map<*, *> ?: error("top-level JSON value is not an object")
    return map.entries.associate { entry -> entry.key as String to entry.value }
}

private fun findingsOfJson(doc: Map<String, Any?>): List<Map<String, Any?>> {
    val list = doc["findings"] as? List<*> ?: error("findings is not a JSON array")
    return list.map { item ->
        val map = item as? Map<*, *> ?: error("finding is not a JSON object")
        map.entries.associate { entry -> entry.key as String to entry.value }
    }
}

/** 测试用最小 JSON 读取器：只认本产出用到的子集（对象/数组/字符串/整数/true/false/null）。 */
private class JsonCursor(private val text: String) {
    private var index = 0

    fun parseDocument(): Any? {
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) fail("trailing content at $index")
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= text.length) fail("unexpected end of JSON")
        val character = text[index]
        return when {
            character == '{' -> parseObject()
            character == '[' -> parseArray()
            character == '"' -> parseString()
            character == 't' -> parseLiteral("true", true)
            character == 'f' -> parseLiteral("false", false)
            character == 'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            index += 1
            return result
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            when (val separator = peek()) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return result
                }
                else -> fail("expected ',' or '}' at $index but got '$separator'")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            index += 1
            return result
        }
        while (true) {
            result += parseValue()
            skipWhitespace()
            when (val separator = peek()) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return result
                }
                else -> fail("expected ',' or ']' at $index but got '$separator'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            when (val character = text[index]) {
                '"' -> {
                    index += 1
                    return result.toString()
                }
                '\\' -> {
                    index += 1
                    result.append(parseEscape())
                }
                else -> {
                    result.append(character)
                    index += 1
                }
            }
        }
    }

    private fun parseEscape(): Char {
        if (index >= text.length) fail("unterminated escape")
        val character = text[index]
        index += 1
        return when (character) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail("invalid escape sequence at ${index - 1}")
        }
    }

    private fun parseUnicodeEscape(): Char {
        val end = index + UNICODE_ESCAPE_DIGITS
        if (end > text.length) fail("truncated unicode escape at $index")
        val digits = text.substring(index, end)
        index = end
        return digits.toIntOrNull(UNICODE_ESCAPE_RADIX)?.toChar() ?: fail("invalid unicode escape: $digits")
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, index)) fail("invalid literal at $index")
        index += literal.length
        return value
    }

    private fun parseNumber(): Int {
        val start = index
        if (index < text.length && text[index] == '-') index += 1
        while (index < text.length && text[index].isDigit()) index += 1
        if (start == index) fail("invalid number at $index")
        return text.substring(start, index).toInt()
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }

    private fun expect(character: Char) {
        skipWhitespace()
        if (peek() != character) fail("expected '$character' at $index")
        index += 1
    }

    private fun peek(): Char {
        if (index >= text.length) fail("unexpected end of JSON")
        return text[index]
    }

    private fun fail(message: String): Nothing = error("$message in: $text")
}

private const val UNICODE_ESCAPE_DIGITS = 4
private const val UNICODE_ESCAPE_RADIX = 16
