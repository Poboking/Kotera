package com.eratw.port.modgen

import com.eratw.port.modapi.ModApi
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON 契约冻结测试的共享基建：严格 JSON 解析器、夹具构造、进程内/进程外运行器。
 *
 * 解析器刻意做得很严：保留对象键序、重复键直接判红、拒绝未转义控制字符，这样"字段集合恰好"
 * 与"转义正确"这类断言不是靠宽松解析器自我印证。
 */

/** JSON 值：契约测试只关心对象/数组/字符串/整数/布尔/null 六种。 */
internal sealed interface ContractJsonValue

/** 对象：用 LinkedHashMap 保留键序，重复键在解析期即判红。 */
internal data class ContractJsonObject(val entries: LinkedHashMap<String, ContractJsonValue>) : ContractJsonValue {
    fun keys(): List<String> = entries.keys.toList()

    fun raw(key: String): ContractJsonValue =
        checkNotNull(entries[key]) { "缺少字段 '$key'，实际字段=${keys()}" }

    fun string(key: String): String {
        val value = raw(key)
        check(value is ContractJsonString) { "字段 '$key' 应为字符串，实际=${value.describe()}" }
        return value.value
    }

    fun int(key: String): Int {
        val value = raw(key)
        check(value is ContractJsonNumber) { "字段 '$key' 应为整数，实际=${value.describe()}" }
        return value.value
    }

    fun bool(key: String): Boolean {
        val value = raw(key)
        check(value is ContractJsonBoolean) { "字段 '$key' 应为布尔，实际=${value.describe()}" }
        return value.value
    }

    fun array(key: String): List<ContractJsonValue> {
        val value = raw(key)
        check(value is ContractJsonArray) { "字段 '$key' 应为数组，实际=${value.describe()}" }
        return value.items
    }

    fun objects(key: String): List<ContractJsonObject> = array(key).map {
        check(it is ContractJsonObject) { "字段 '$key' 的元素应为对象，实际=${it.describe()}" }
        it
    }
}

internal data class ContractJsonArray(val items: List<ContractJsonValue>) : ContractJsonValue

internal data class ContractJsonString(val value: String) : ContractJsonValue

internal data class ContractJsonNumber(val value: Int) : ContractJsonValue

internal data class ContractJsonBoolean(val value: Boolean) : ContractJsonValue

internal data object ContractJsonNull : ContractJsonValue

private fun ContractJsonValue.describe(): String = when (this) {
    is ContractJsonObject -> "object"
    is ContractJsonArray -> "array"
    is ContractJsonString -> "string"
    is ContractJsonNumber -> "number"
    is ContractJsonBoolean -> "boolean"
    ContractJsonNull -> "null"
}

/** 最小严格 JSON 解析器：只为契约测试服务，不做数字科学计数法等超集。 */
internal class ContractJsonParser(private val text: String) {

    private var pos = 0

    fun parseDocument(): ContractJsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        check(pos == text.length) { "JSON 尾部有多余内容：位置 $pos，剩余='${text.substring(pos)}'" }
        return value
    }

    private fun parseValue(): ContractJsonValue {
        skipWhitespace()
        check(pos < text.length) { "JSON 在位置 $pos 意外结束" }
        val c = text[pos]
        return when (c) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> ContractJsonString(parseString())
            't' -> parseLiteral("true", ContractJsonBoolean(true))
            'f' -> parseLiteral("false", ContractJsonBoolean(false))
            'n' -> parseLiteral("null", ContractJsonNull)
            else -> if (c == '-' || c.isDigit()) parseNumber() else error("位置 $pos 出现非法字符 '$c'")
        }
    }

    private fun parseObject(): ContractJsonObject {
        expect('{')
        val entries = LinkedHashMap<String, ContractJsonValue>()
        parseObjectBody(entries)
        return ContractJsonObject(entries)
    }

    private fun parseObjectBody(entries: MutableMap<String, ContractJsonValue>) {
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return
        }
        val key = parseString()
        check(!entries.containsKey(key)) { "JSON 对象出现重复键 '$key'（位置 $pos）" }
        skipWhitespace()
        expect(':')
        skipWhitespace()
        entries[key] = parseValue()
        skipWhitespace()
        val separator = next()
        check(separator == ',' || separator == '}') { "位置 ${pos - 1} 期望 ',' 或 '}'，实际 '$separator'" }
        if (separator == ',') parseObjectBody(entries)
    }

    private fun parseArray(): ContractJsonArray {
        expect('[')
        val items = mutableListOf<ContractJsonValue>()
        parseArrayBody(items)
        return ContractJsonArray(items)
    }

    private fun parseArrayBody(items: MutableList<ContractJsonValue>) {
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return
        }
        items.add(parseValue())
        skipWhitespace()
        val separator = next()
        check(separator == ',' || separator == ']') { "位置 ${pos - 1} 期望 ',' 或 ']'，实际 '$separator'" }
        if (separator == ',') parseArrayBody(items)
    }

    private fun parseString(): String {
        expect('"')
        return parseStringBody(StringBuilder())
    }

    private fun parseStringBody(builder: StringBuilder): String {
        val c = next()
        if (c == '"') return builder.toString()
        if (c == '\\') {
            builder.append(parseEscape())
        } else {
            check(c.code >= 0x20) { "字符串里出现未转义控制字符 U+${c.code.toString(16)}（位置 ${pos - 1}）" }
            builder.append(c)
        }
        return parseStringBody(builder)
    }

    private fun parseEscape(): Char = when (val escaped = next()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> parseUnicodeEscape()
        else -> error("位置 ${pos - 1} 出现非法转义 '\\$escaped'")
    }

    private fun parseUnicodeEscape(): Char {
        check(pos + 4 <= text.length) { "\\u 转义不完整（位置 $pos）" }
        val hex = text.substring(pos, pos + 4)
        check(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) { "位置 $pos 出现非法 \\u 转义 '$hex'" }
        pos += 4
        return hex.toInt(16).toChar()
    }

    private fun parseNumber(): ContractJsonNumber {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        val literal = text.substring(start, pos)
        check(literal.isNotEmpty() && literal != "-") { "位置 $start 处不是合法整数：'$literal'" }
        check(pos == text.length || isSeparator(text[pos])) { "整数后紧跟非法字符 '${text[pos]}'（位置 $pos）" }
        return ContractJsonNumber(literal.toInt())
    }

    private fun parseLiteral(token: String, value: ContractJsonValue): ContractJsonValue {
        check(text.startsWith(token, pos)) { "位置 $pos 期望字面量 '$token'" }
        pos += token.length
        return value
    }

    private fun isSeparator(c: Char): Boolean = c == ',' || c == '}' || c == ']' || c.isWhitespace()

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char {
        check(pos < text.length) { "JSON 在位置 $pos 意外结束" }
        return text[pos]
    }

    private fun next(): Char {
        val c = peek()
        pos++
        return c
    }

    private fun expect(expected: Char) {
        check(next() == expected) { "位置 ${pos - 1} 期望 '$expected'" }
    }
}

internal fun parseContractJson(text: String): ContractJsonObject {
    val value = ContractJsonParser(text).parseDocument()
    val actual = if (value is ContractJsonArray) "array" else "标量"
    check(value is ContractJsonObject) { "--json 顶层必须是 JSON 对象，实际=$actual" }
    return value
}

/** 一次 check-mod 调用的观测结果。 */
internal data class ContractRun(val exit: Int, val out: String, val err: String)

/** 进程内跑 check-mod：直接喂 PrintStream，拿到 stdout/stderr 与返回码。 */
internal fun runContractCheck(vararg args: String): ContractRun {
    val outBytes = ByteArrayOutputStream()
    val errBytes = ByteArrayOutputStream()
    val exit = PrintStream(outBytes, true, Charsets.UTF_8).use { outStream ->
        PrintStream(errBytes, true, Charsets.UTF_8).use { errStream ->
            ModCheck.runCli(arrayOf(*args), outStream, errStream)
        }
    }
    return ContractRun(exit, outBytes.toString("UTF-8"), errBytes.toString("UTF-8"))
}

internal fun jsonContractRun(module: Path): ContractRun = runContractCheck(module.toString(), "--json")

private const val ON_MIGRATE_WITHOUT_DATA_VERSION =
    "override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {}"

internal fun contractModFile(module: Path): Path = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt")

internal fun healthyContractModule(root: Path): Path {
    val module = scaffoldModule(root)
    writeGenerated(module)
    return module
}

internal fun insertContractMembers(module: Path, vararg members: String) {
    val block = members.joinToString(separator = "\n", postfix = "\n") { "    $it" }
    val file = contractModFile(module)
    val text = Files.readString(file)
    val anchor = "class MyMod : EraMod {\n"
    check(text.contains(anchor)) { "脚手架载体类缺少插入锚点：$anchor" }
    Files.writeString(file, text.replace(anchor, anchor + block))
}

/** 仅 WARN：add onMigrate 但不声明 dataVersion。 */
internal fun warnOnlyContractModule(root: Path): Path {
    val module = healthyContractModule(root)
    insertContractMembers(module, ON_MIGRATE_WITHOUT_DATA_VERSION)
    return module
}

/** 有 ERROR：删掉生成器产出的访问器文件。 */
internal fun errorContractModule(root: Path): Path {
    val module = healthyContractModule(root)
    Files.delete(generatedFile(module))
    return module
}

/** 多 finding（2 ERROR + 1 WARN）：apiVersion 超前 + onMigrate 缺 dataVersion + 生成物缺失。 */
internal fun multiFindingContractModule(root: Path): Path {
    val module = healthyContractModule(root)
    insertContractMembers(
        module,
        "override val apiVersion: Int = ${ModApi.VERSION + 1}",
        ON_MIGRATE_WITHOUT_DATA_VERSION,
    )
    Files.delete(generatedFile(module))
    return module
}

/** 真进程跑 ManifestBuild check-mod，用来验证 exitProcess 分派的退出码。 */
internal fun runContractProcess(workDir: Path, vararg args: String): ContractRun {
    val outFile = Files.createTempFile(workDir, "contract-stdout", ".txt")
    val errFile = Files.createTempFile(workDir, "contract-stderr", ".txt")
    val command = mutableListOf(
        javaExecutable().toString(),
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Duser.language=en",
        "-Duser.country=US",
        "-cp",
        System.getProperty("java.class.path"),
        "com.eratw.port.modgen.ManifestBuildKt",
    )
    command.addAll(args)
    val process = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectOutput(outFile.toFile())
        .redirectError(errFile.toFile())
        .start()
    val exit = process.waitFor()
    return ContractRun(exit, Files.readString(outFile), Files.readString(errFile))
}

private fun javaExecutable(): Path {
    val bin = Path.of(System.getProperty("java.home"), "bin")
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    val java = bin.resolve(name)
    check(Files.isExecutable(java)) { "找不到 java 可执行文件：$java" }
    return java
}
