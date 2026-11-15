package com.eratw.port.core.proc

/**
 * 构建期引擎印章的**纯解析器**（ENGC-2）。
 *
 * 出包通道（B6）在 staging 时往 APK assets 写一份 `engine-profile.json`：
 *
 * ```
 * { "schema": 1, "profile": "snake_4981", "engine": "snake", "version": "4.981" }
 * ```
 *
 * 运行期只在 `GameHost` 构造期（UI 线程）读一次，解析结果交给 [EngineProfiles] 装成
 * [RuntimeCompatibilityProfile]。本对象不碰 Android、不读文件：读字节的一侧在 app 的
 * `EngineProfileStamp`，所以三档映射与全部非法形态都能在普通 JVM 单测里钉死。
 *
 * ## 三态，不静默回落
 *
 * - [EngineStampRead.Missing]：APK 里没有印章 = eraTW 包 ⇒ 恒
 *   [RuntimeCompatibilityProfile.LEGACY_ERATW_4981]（零干扰）；
 * - [EngineStampRead.Present]：四字段自洽 ⇒ 按 [EngineProfiles.TARGETS] 装配；
 * - [EngineStampRead.Malformed]：**包造错了**（未知档 / 换档漏改引擎串 / 手改产物），
 *   由 `GameHost` 走既有装载失败面并带原样值 —— 不回落缺省档，否则出包通道的错误会
 *   变成「玩家跑着另一家引擎却看不出」的假兼容（D14 的同一取向）。
 *
 * ## 严格性
 *
 * 只接受**扁平对象**，字段恰好四个且不重复；`schema` 必须是整数 [SCHEMA_VERSION]；
 * 字符串值只接受标准 JSON 转义。未知字段、重复字段、嵌套值、浮点/布尔/null、
 * 尾随内容一律 [EngineStampRead.Malformed]。理由串是给开发者看的（英文），空串与
 * BOM 按「空印章」/ 无 BOM 处理，BOM 不交给键名。
 */
sealed interface EngineStampRead {

    /** APK 里没有 `engine-profile.json`：eraTW 包，恒缺省档。 */
    data object Missing : EngineStampRead

    /** 合法印章：装配出的档案 + 原文（失败面与日志取证用）。 */
    data class Present(val profile: RuntimeCompatibilityProfile, val raw: String) : EngineStampRead

    /**
     * 非法印章：原文 + 开发者面理由（英文）。
     *
     * [raw] 是去掉 BOM 与首尾空白后的全文（不截断）；玩家面展示截断由调用方做 ——
     * 解析器不擅自改证据。
     */
    data class Malformed(val raw: String, val reason: String) : EngineStampRead
}

/** 印章的文件名与 schema 版本；常量单点，B6 写侧对拍同一份值。 */
object EngineStamp {

    const val ASSET_NAME: String = "engine-profile.json"

    const val SCHEMA_VERSION: Int = 1

    private val REQUIRED_KEYS: List<String> = listOf("schema", "profile", "engine", "version")

    /**
     * 解析印章全文。
     *
     * @param text APK asset 的 UTF-8 全文；null = 文件不存在（⇒ [EngineStampRead.Missing]）
     */
    fun parse(text: String?): EngineStampRead {
        if (text == null) return EngineStampRead.Missing
        val raw = text.removePrefix("\uFEFF").trim()
        if (raw.isEmpty()) return EngineStampRead.Malformed(raw, "empty stamp")
        val fields = try {
            StampJsonParser(raw).parseObject()
        } catch (e: StampJsonException) {
            return EngineStampRead.Malformed(raw, e.message ?: "malformed JSON")
        }

        val unknown = fields.keys.filterNot { it in REQUIRED_KEYS }
        if (unknown.isNotEmpty()) {
            return EngineStampRead.Malformed(raw, "unknown key(s): " + unknown.joinToString(", "))
        }
        val missing = REQUIRED_KEYS.filterNot { it in fields }
        if (missing.isNotEmpty()) {
            return EngineStampRead.Malformed(raw, "missing field(s): " + missing.joinToString(", "))
        }

        val schema = fields.getValue("schema")
        if (schema !is StampValue.Number) {
            return EngineStampRead.Malformed(raw, "schema must be an integer")
        }
        if (schema.value != SCHEMA_VERSION.toLong()) {
            return EngineStampRead.Malformed(raw, "unsupported schema: ${schema.value}")
        }

        val profileId = fields.getValue("profile").textOrNull()
            ?: return EngineStampRead.Malformed(raw, "profile must be a string")
        val engine = fields.getValue("engine").textOrNull()
            ?: return EngineStampRead.Malformed(raw, "engine must be a string")
        val version = fields.getValue("version").textOrNull()
            ?: return EngineStampRead.Malformed(raw, "version must be a string")

        val target = EngineProfiles.target(profileId)
            ?: return EngineStampRead.Malformed(
                raw,
                "unknown profile: '$profileId' (supported: " +
                    EngineProfiles.TARGETS.joinToString(", ") { it.profileId } + ")",
            )
        if (engine != target.engine) {
            return EngineStampRead.Malformed(
                raw,
                "engine mismatch: profile '$profileId' requires '${target.engine}', got '$engine'",
            )
        }
        if (version != target.version) {
            return EngineStampRead.Malformed(
                raw,
                "unsupported version for '$profileId': '$version' " +
                    "(supported: '${target.version}')",
            )
        }
        return EngineStampRead.Present(
            EngineProfiles.profileFor(target.family, target.corpusVersion),
            raw,
        )
    }

    private fun StampValue.textOrNull(): String? = (this as? StampValue.Text)?.value
}

/** 印章 JSON 的两种标量值：字符串与整数（`schema` 只吃后者）。 */
internal sealed interface StampValue {
    data class Text(val value: String) : StampValue
    data class Number(val value: Long) : StampValue
}

private class StampJsonException(message: String) : Exception(message)

/**
 * 扁平 JSON 对象的严格解析器（递归下降，只收 `{ "key": "value" | integer, ... }`）。
 *
 * 不引入 JSON 库：core-vm 要保持纯 JVM 且不带额外依赖，而印章只有四个平坦字段 ——
 * 一个 90 行的解析器比引一个依赖或放宽到「正则捞字段」更可审计（未知/重复/嵌套值
 * 都必须判红，正则做不到）。
 */
private class StampJsonParser(private val text: String) {

    private var index = 0

    fun parseObject(): Map<String, StampValue> {
        expect('{')
        val fields = LinkedHashMap<String, StampValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            finish()
            return fields
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            if (fields.containsKey(key)) throw StampJsonException("duplicate key: '$key'")
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    break
                }
                else -> throw StampJsonException("expected ',' or '}' at offset $index")
            }
        }
        finish()
        return fields
    }

    private fun parseValue(): StampValue {
        val c = peek()
        return when {
            c == '"' -> StampValue.Text(parseString())
            c == '-' || c in '0'..'9' -> StampValue.Number(parseInteger())
            else -> throw StampJsonException(
                "unsupported value at offset $index (only strings and integers)",
            )
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (index >= text.length) throw StampJsonException("unterminated string")
            val c = text[index++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> out.append(parseEscape())
                c.code < 0x20 -> throw StampJsonException(
                    "control character in string at offset ${index - 1}",
                )
                else -> out.append(c)
            }
        }
    }

    private fun parseEscape(): Char {
        if (index >= text.length) throw StampJsonException("unterminated escape")
        return when (val e = text[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) throw StampJsonException("bad unicode escape")
                val hex = text.substring(index, index + 4)
                val code = hex.toIntOrNull(16)
                    ?: throw StampJsonException("bad unicode escape: '$hex'")
                index += 4
                code.toChar()
            }
            else -> throw StampJsonException("unsupported escape '\\$e'")
        }
    }

    private fun parseInteger(): Long {
        val start = index
        if (peek() == '-') index++
        val digitsStart = index
        while (index < text.length && text[index] in '0'..'9') index++
        if (index == digitsStart) throw StampJsonException("expected digit at offset $index")
        if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E')) {
            throw StampJsonException("only integers are supported (offset $index)")
        }
        val literal = text.substring(start, index)
        val negative = literal.startsWith("-")
        val digits = if (negative) literal.substring(1) else literal
        if (digits.length > 1 && digits[0] == '0') {
            throw StampJsonException("leading zero in number at offset $start")
        }
        return literal.toLongOrNull() ?: throw StampJsonException("number out of range: $literal")
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
            index++
        }
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun expect(c: Char) {
        if (index >= text.length || text[index] != c) {
            throw StampJsonException("expected '$c' at offset $index")
        }
        index++
    }

    private fun finish() {
        skipWhitespace()
        if (index != text.length) throw StampJsonException("trailing content at offset $index")
    }
}
