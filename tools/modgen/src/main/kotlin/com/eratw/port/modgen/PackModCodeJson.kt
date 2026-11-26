package com.eratw.port.modgen

/**
 * `code.mod` 里 `manifest.json` 的**严格 JSON 解析面**：只服务 [PackModCode] 的内容校验，不做 schema。
 *
 * 语法按 RFC 8259 收全（对象 / 数组 / 字符串含短转义与 `\uXXXX` / 严格数字：无前导零、小数位与
 * 指数位必须有位 / `true` / `false` / `null` / 四种空白）。解析结果只区分「顶层对象里各键的值类型」
 * 与字符串值，嵌套结构递归校验后即丢弃（本校验只要 `packageId`），故不做 AST 保留、不做重复键判据。
 *
 * 拆成 [CodeModJsonCursor]（词法：索引推进 + 标量/字符串/数字）与 [CodeModJsonParser]（结构：对象/数组）
 * 两个类，只为让每个类的函数数留在 detekt `TooManyFunctions` 阈值内；两者的边界是「游标只管读字符，
 * 结构只管嵌套」，不承载业务语义。
 */
internal fun parseStrictJson(text: String): JsonValue? =
    try {
        CodeModJsonParser(CodeModJsonCursor(text)).document()
    } catch (ignored: JsonSyntaxException) {
        // 语法错 = 不是合法 JSON ⇒ 上层按 INVALID_JSON_REASON 拒（不区分具体语法点）。
        null
    }

/** 词法游标：只做「读一个字符 / 跳空白 / 读标量」，不碰对象与数组结构。 */
internal class CodeModJsonCursor(private val text: String) {

    private var index = 0

    /** 当前位置的字符；越界 = null。 */
    fun peek(): Char? = text.getOrNull(index)

    /** 吃掉当前字符并返回；越界即语法错。 */
    fun take(): Char {
        val char = peek() ?: fail("JSON 意外结束")
        index += 1
        return char
    }

    fun skipWhitespace() {
        while (peek()?.let { it in JSON_WHITESPACE } == true) index += 1
    }

    /** 当前位置必须是 `"`：读出字符串值（含转义）。 */
    fun readString(): String {
        expect('"', "字符串必须以引号开始")
        val sink = StringBuilder()
        while (true) {
            val char = take()
            when {
                char == '"' -> return sink.toString()
                char == '\\' -> sink.append(readEscape())
                char.code < JSON_CONTROL_LIMIT -> fail("字符串里有未转义控制字符")
                else -> sink.append(char)
            }
        }
    }

    /** 数字按 JSON 语法严格校验（只判形状，不计算值）。 */
    fun readNumber(): JsonValue {
        if (peek() == '-') index += 1
        when {
            peek() == '0' -> index += 1
            peek()?.let { it in JSON_NONZERO_DIGITS } == true -> {
                index += 1
                digits()
            }
            else -> fail("数字整数部分非法")
        }
        if (peek() == '.') {
            index += 1
            if (digits() == 0) fail("数字的小数部分缺位")
        }
        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            if (digits() == 0) fail("数字的指数部分缺位")
        }
        return JsonNumberValue
    }

    /** 当前位置必须是 [char]；否则语法错。 */
    fun expect(char: Char, message: String) {
        if (take() != char) fail(message)
    }

    private fun readEscape(): Char = when (val escaped = take()) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> readUnicodeEscape()
        else -> fail("非法转义：$escaped")
    }

    private fun readUnicodeEscape(): Char {
        var code = 0
        repeat(HEX_QUAD_DIGITS) {
            val digit = Character.digit(take(), HEX_RADIX)
            if (digit < 0) fail("\\u 转义含非十六进制字符")
            code = code * HEX_RADIX + digit
        }
        return Char(code)
    }

    /** 吃掉连续十进制位，返回吃掉的数量（0 = 当前位置不是数字位）。 */
    private fun digits(): Int {
        val start = index
        while (peek()?.let { it in JSON_DIGITS } == true) index += 1
        return index - start
    }

    private fun fail(message: String): Nothing = throw JsonSyntaxException(message)
}

/** 结构解析：对象 / 数组 / 文档边界；值类型只按 JSON 语法归类（见 [parseStrictJson] 的 KDoc）。 */
internal class CodeModJsonParser(private val cursor: CodeModJsonCursor) {

    fun document(): JsonValue {
        cursor.skipWhitespace()
        val value = parseValue()
        cursor.skipWhitespace()
        if (cursor.peek() != null) fail("JSON 末尾有多余内容")
        return value
    }

    private fun parseValue(): JsonValue {
        val char = cursor.peek() ?: fail("JSON 意外结束")
        return when {
            char == '{' -> parseObject()
            char == '[' -> parseArray()
            char == '"' -> JsonString(cursor.readString())
            char == 't' -> readLiteral("true", JsonTrueValue)
            char == 'f' -> readLiteral("false", JsonFalseValue)
            char == 'n' -> readLiteral("null", JsonNullValue)
            char == '-' || char in JSON_DIGITS -> cursor.readNumber()
            else -> fail("非法字符")
        }
    }

    private fun parseObject(): JsonValue {
        cursor.take()
        cursor.skipWhitespace()
        val fields = LinkedHashMap<String, JsonValue>()
        if (cursor.peek() == '}') {
            cursor.take()
            return JsonObject(fields)
        }
        while (true) {
            cursor.skipWhitespace()
            if (cursor.peek() != '"') fail("对象键必须是字符串")
            val key = cursor.readString()
            cursor.skipWhitespace()
            cursor.expect(':', "对象键值之间缺少冒号")
            cursor.skipWhitespace()
            fields[key] = parseValue()
            cursor.skipWhitespace()
            when (cursor.take()) {
                ',' -> Unit
                '}' -> return JsonObject(fields)
                else -> fail("对象成员之间缺少逗号，或对象未闭合")
            }
        }
    }

    private fun parseArray(): JsonValue {
        cursor.take()
        cursor.skipWhitespace()
        if (cursor.peek() == ']') {
            cursor.take()
            return JsonArrayValue
        }
        while (true) {
            cursor.skipWhitespace()
            parseValue()
            cursor.skipWhitespace()
            when (cursor.take()) {
                ',' -> Unit
                ']' -> return JsonArrayValue
                else -> fail("数组元素之间缺少逗号，或数组未闭合")
            }
        }
    }

    /** 读一个固定字面量（`true` / `false` / `null`）；不匹配即语法错。 */
    private fun readLiteral(word: String, value: JsonValue): JsonValue {
        word.forEach { expected -> if (cursor.take() != expected) fail("非法字面量") }
        return value
    }

    private fun fail(message: String): Nothing = throw JsonSyntaxException(message)
}

/** JSON 语法错误：只在本文件的严格解析面里用，**不是** [IllegalArgumentException]（那条路是退出码 2）。 */
internal class JsonSyntaxException(message: String) : Exception(message)

/** 本校验需要的最简 JSON 值：只区分顶层对象键的值类型与字符串值。 */
internal sealed interface JsonValue

/** 顶层对象（嵌套对象递归校验后同样落成这个类型，内部字段不参与本校验）。 */
internal class JsonObject(val fields: Map<String, JsonValue>) : JsonValue

/** JSON 字符串：`manifest.json` 的 `packageId` 只接受这一种值类型。 */
internal class JsonString(val value: String) : JsonValue

/** 数组 / 数字 / 布尔 / null 的占位值：只说明「语法合法且类型不是字符串」。 */
internal object JsonArrayValue : JsonValue

internal object JsonNumberValue : JsonValue

internal object JsonTrueValue : JsonValue

internal object JsonFalseValue : JsonValue

internal object JsonNullValue : JsonValue

/**
 * 生成内层 `manifest.json` 文本：读侧 [parseStrictJson] 的逆操作（测试与未来的产物生成工具共用）。
 *
 * 字段定序**固定**为 `packageId` → 可选 `version` → `entrypoint` → 可选 `apiVersion`（省缺 = 不写该键），
 * 只为让「同输入两次生成字节相同」可测；读侧（[PackModCode] 与 app 的 `ModCodeCarrierProvider`）
 * 不依赖字段顺序，只按键取值，未知键原样透传。
 *
 * 值只做 JSON 字符串最小转义（引号/反斜杠）；形态合法性由调用方按 packageId / entrypoint 的
 * 形状闸保证，本函数不做校验。
 */
internal fun encodeInnerManifest(
    packageId: String,
    entrypoint: String,
    version: String? = null,
    apiVersion: Int? = null,
): String {
    val fields = mutableListOf("\"packageId\":${jsonStringLiteral(packageId)}")
    if (version != null) fields += "\"version\":${jsonStringLiteral(version)}"
    fields += "\"${CODE_ENTRYPOINT_KEY}\":${jsonStringLiteral(entrypoint)}"
    if (apiVersion != null) fields += "\"apiVersion\":$apiVersion"
    return fields.joinToString(separator = ",", prefix = "{", postfix = "}")
}

/** JSON 字符串字面量：只转义引号与反斜杠（作者面取值形态已限制，控制字符不在合同内）。 */
private fun jsonStringLiteral(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}

/** 数字后续位的全部十进制字符（整数部分首位另有「禁前导零」判据）。 */
private val JSON_DIGITS = '0'..'9'

/** JSON 允许的四种空白。 */
private const val JSON_WHITESPACE = " \t\n\r"

/** 数字整数部分可用的非零首位。 */
private val JSON_NONZERO_DIGITS = '1'..'9'

/** JSON 字符串里必须转义的最小字符码。 */
private const val JSON_CONTROL_LIMIT = 0x20

/** `\uXXXX` 的十六进制位宽。 */
private const val HEX_QUAD_DIGITS = 4

/** 十六进制基数。 */
private const val HEX_RADIX = 16
