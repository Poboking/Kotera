package com.eratw.port.modgen

import java.util.Locale

/**
 * `modgen check-mod <mod目录> --json` 的机器可读输出（schema 版本 [SCHEMA]）。
 *
 * 只做序列化，不碰检查逻辑：输入就是 [ModCheck.check] 产出的 [Finding] 列表，人类可读面
 * （[ModCheck] 内部的单行 format + 末尾 summary）与这里互斥，两边共用同一份发现。
 *
 * 输出形状（字段序固定，便于 CI 逐字节比对）：
 * ```
 * {
 *   "schema": "1",
 *   "ok": true,
 *   "errors": 0,
 *   "warnings": 0,
 *   "findings": [
 *     {"severity": "ERROR", "file": "src/main/...", "line": 12, "message": "..."}
 *   ]
 * }
 * ```
 *
 * 确定性：发现先按 `file, line(空行记 -1), severity, message` 稳定排序再输出；`file` 用
 * [ModCheckSources.relativeLabel] 的相对标签（显式传入模块外的 schema 才可能是绝对路径，属输入
 * 本身）；不写时间戳/随机量/运行期环境，同一输入两次运行字节相同。
 *
 * `line` 为 `null` 表示整文件级发现；`severity` 取 [Severity] 的枚举名（ERROR / WARN），与人类
 * 可读面同一取值。转义只做 JSON 必需项（引号/反斜杠/控制字符），不转义非 ASCII，保持 UTF-8 可读。
 */
internal object ModCheckJson {
    /** JSON schema 版本号；字段或语义发生破坏性变更时递增。 */
    const val SCHEMA: String = "1"

    /** 没有行号的发现参与排序时的占位（与人类可读面的 `line ?: 0` 不同，这里让 null 排在最前）。 */
    private const val NO_LINE = -1

    /** 渲染整份 JSON 文档（末尾带一个换行；除 `--json` 外 stdout 不写任何其它行）。 */
    fun render(findings: List<Finding>): String = render(findings, null)

    /**
     * 渲染整份 JSON 文档。[fixes] 非 null 时在 findings 之后追加 `fixes` 数组 —— 只在 `--fix` /
     * `--dry-run` 下传列表（含空列表），普通检查传 null，保持既有五键形状逐字节不变。
     */
    fun render(findings: List<Finding>, fixes: List<FixResult>?): String {
        val ordered = findings.sortedWith(
            compareBy<Finding>({ it.file }, { it.line ?: NO_LINE }, { it.severity.name }, { it.message }),
        )
        val errors = ordered.count { it.severity == Severity.ERROR }
        return buildString {
            append("{\n")
            append("  \"schema\": ").append(quote(SCHEMA)).append(",\n")
            append("  \"ok\": ").append(errors == 0).append(",\n")
            append("  \"errors\": ").append(errors).append(",\n")
            append("  \"warnings\": ").append(ordered.size - errors).append(",\n")
            append("  \"findings\": ")
            appendFindings(this, ordered)
            if (fixes != null) {
                append(",\n  \"fixes\": ")
                appendFixes(this, fixes)
            }
            append("\n}\n")
        }
    }

    /** `fixes` 数组：字段序固定 file/status/detail（detail 无失败原因时为 null）。 */
    private fun appendFixes(json: StringBuilder, fixes: List<FixResult>) {
        if (fixes.isEmpty()) {
            json.append("[]")
            return
        }
        json.append("[\n")
        fixes.forEachIndexed { index, fix ->
            json.append("    {\"file\": ").append(quote(fix.label))
                .append(", \"status\": ").append(quote(fix.status.name))
                .append(", \"detail\": ").append(fix.detail?.let { quote(it) } ?: "null")
                .append('}')
            json.append(if (index < fixes.size - 1) ",\n" else "\n")
        }
        json.append("  ]")
    }

    private fun appendFindings(json: StringBuilder, findings: List<Finding>) {
        if (findings.isEmpty()) {
            json.append("[]")
            return
        }
        json.append("[\n")
        findings.forEachIndexed { index, finding ->
            json.append("    {\"severity\": ").append(quote(finding.severity.name))
                .append(", \"file\": ").append(quote(finding.file))
                .append(", \"line\": ").append(finding.line ?: "null")
                .append(", \"message\": ").append(quote(finding.message))
                .append('}')
            json.append(if (index < findings.size - 1) ",\n" else "\n")
        }
        json.append("  ]")
    }

    /** JSON 字符串转义（照 `EngineReport.writeJson` 的同一形状：引号/反斜杠/控制字符）。 */
    private fun quote(value: String): String {
        val json = StringBuilder("\"")
        for (character in value) {
            when (character) {
                '"' -> json.append("\\\"")
                '\\' -> json.append("\\\\")
                '\n' -> json.append("\\n")
                '\r' -> json.append("\\r")
                '\t' -> json.append("\\t")
                else -> if (character < ' ') {
                    json.append(String.format(Locale.ROOT, "\\u%04x", character.code))
                } else {
                    json.append(character)
                }
            }
        }
        return json.append('"').toString()
    }
}
