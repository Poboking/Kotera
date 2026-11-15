package com.eratw.port.erbcc

import com.eratw.port.core.fn.EngineSurface
import com.eratw.port.core.proc.EngineProfiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * erbcc 侧的目标引擎档位视图（B6 ENGC-3b）。
 *
 * 职责：把 `--engine-profile &lt;profile_id&gt;` 的值解析到 core-vm 权威档位表
 * [EngineProfiles.TARGETS]（profileId 唯一事实出处，erbcc 不抄档位表），并持有该档
 * **函数面**的可见性视图 [surface]（复用运行期同源的 [EngineSurface]：可见名 =
 * `functionsOf` ∪ PORT_EXTENSIONS 再与实际 registry 取交集，未实装名不枚举）。
 *
 * ## 命令面的接线缝（TODO(wiring)：需组长在 core-vm 侧解锁）
 *
 * scan 行首过滤与 `--engine-report` 的 unknownCommands 收集需要「目标档行首合法集」=
 * 目标档命令集 ∪ 函数集 ∪ PORT_EXTENSIONS ∪ 结构码（与 B4 审计同一口径，判据见契约
 * ENGCOMPAT §8.0.6）。名单唯一来源是生成物 `EngineProfileData`，但它是 core-vm 的
 * `internal object`（跨模块不可见，2026-11-12 探针编译实测：erbcc 引用即报
 * "it is internal in file"）。**已接线**（组长落 main@579240a3e）：[EngineProfiles.legalStatementNames]
 * 是 B6 公开门面（= `CompatCommandAudit.allowedNames` 唯一委托，运行期 B4 审计与编译期
 * erbcc 扫描同源），[statementAllowedNames] 直接消费它，不复制名单。
 */
class EngineProfileView private constructor(val target: EngineProfiles.Target) {

    /** 目标引擎家族（视图的构造参数，函数面按它查询）。 */
    val family get() = target.family

    /**
     * 函数面可见性（ENGC-3 语义，与运行期同源）：[EngineSurface.builtinVisible] 只认
     * 目标档声明且已实装的名字；被裁名走调用方的「未定义函数」既有路径。
     */
    val surface = EngineSurface(target.family)

    /**
     * 目标档**行首合法集**（大写口径）：命令集 ∪ 函数集 ∪ PORT_EXTENSIONS ∪
     * 结构码（`__NULL__` / `SET` / `METHOD`），单源 = [EngineProfiles.legalStatementNames]
     * （B6 公开门面，与 B4 运行期审计同一判据）。行首标识符不在此集 ⇒ 走既有
     * InvalidLine / 解析失败路径（I4），不新增报错文案。
     */
    val statementAllowedNames: Set<String> get() = EngineProfiles.legalStatementNames(target.family)

    companion object {

        /** 按 profileId 查权威档位表；未知 id 返回 null（调用方给支持表报错，退出码 2 档）。 */
        fun of(profileId: String): EngineProfileView? =
            EngineProfiles.target(profileId)?.let { EngineProfileView(it) }

        /**
         * 未知 profileId 的 stderr 工具面报错正文（不含 `错误: ` 前缀）：点名收到的值
         * 并列出支持表（三行 profileId / engine / version，与 [EngineProfiles.TARGETS]
         * 同序）。这是构建工具面文案，不是玩家 UI 文案，允许直排字面量。
         */
        fun unknownProfileMessage(profileId: String): String = buildString {
            append("未知的 --engine-profile 档位: '$profileId'（支持表:）")
            for (t in EngineProfiles.TARGETS) {
                append("\n  ").append(t.profileId).append("  （engine=").append(t.engine)
                    .append("  version=").append(t.version).append("）")
            }
        }
    }
}

/**
 * `--engine-profile` 参数的解析结果（CLI 面与测试面的共同判据）。
 *
 * [Absent] 是 I7 的关键形状：未给 flag = 零过滤，行为与产物必须和今天逐字节一致，
 * 不许有「缺省档过滤」。[Bad] 的正文来自 [EngineProfileView.unknownProfileMessage]，
 * CLI 层打 stderr 后走退出码 2（`err()` 同档）。
 */
internal sealed interface EngineProfileArg {

    /** 未给 flag：零过滤。 */
    data object Absent : EngineProfileArg

    /** 合法档位：持有视图。 */
    data class Present(val view: EngineProfileView) : EngineProfileArg

    /** 未知 profileId：正文含支持表，CLI 层按参数错误（退出码 2）处理。 */
    data class Bad(val message: String) : EngineProfileArg
}

/**
 * 解析 `--engine-profile` 的值：`null`（未给）→ [EngineProfileArg.Absent]；
 * 在 [EngineProfiles.TARGETS] 内 → [EngineProfileArg.Present]；否则
 * [EngineProfileArg.Bad]（正文含支持表）。
 *
 * internal 供测试源集逐字钉住三档映射与支持表文案；CLI 各命令的参数循环都经此单点。
 */
internal fun parseEngineProfileArg(raw: String?): EngineProfileArg = when (raw) {
    null -> EngineProfileArg.Absent
    else -> {
        val view = EngineProfileView.of(raw)
        if (view == null) EngineProfileArg.Bad(EngineProfileView.unknownProfileMessage(raw))
        else EngineProfileArg.Present(view)
    }
}

/**
 * `--engine-report` 的配对校验：报告只在 compile 且与 `--engine-profile` 同用时合法。
 * 返回 null = 校验通过；非 null = 参数错误正文（CLI 层走退出码 2）。
 */
internal fun engineReportArgError(reportGiven: Boolean, profileGiven: Boolean): String? =
    if (reportGiven && !profileGiven) {
        "--engine-report 需要配合 --engine-profile 使用（报告描述的是某一目标档的缺口；" +
            "不带档位时请去掉 --engine-report）"
    } else {
        null
    }

/** 报告里一条 unknown 命中：名字 + 相对路径（正斜杠口径）+ 行号。 */
class EngineReportRow(val name: String, val file: String, val line: Int)

/**
 * `--engine-report` 的 JSON 报告（形状契约见 ENGCOMPAT §8.0.3，字段名与嵌套不许改；
 * 另一路 worker 的报告渲染器按此消费）：
 *
 * ```
 * {"schema":1,"profile":"v24_1824","engine":"v24","version":"1824+v24","corpus":"<绝对路径>",
 *  "counts":{"files":N,"compiled":N,"parseFailures":N,"unknownCommands":N,"unknownFunctions":N},
 *  "unknownCommands":[{"name":"SETBGIMAGE","file":"<相对路径>","line":120}],
 *  "unknownFunctions":[...]}
 * ```
 *
 * 语义：即使 compile 最终按 strict 模式解析失败退出非零，报告也要完整写出已收集的
 * 全部 unknown 命中（先写报告、后取退出码）；unknown 数组的计数以数组实际条数为准。
 */
class EngineReport(
    val profile: String,
    val engine: String,
    val version: String,
    val corpusRoot: Path,
    val files: Int,
    val compiled: Int,
    val parseFailures: Int,
    val unknownCommands: List<EngineReportRow>,
    val unknownFunctions: List<EngineReportRow>,
) {

    /** 写出 JSON（UTF-8，紧凑两空格缩进的形状与 §8.0.3 示例一致的字段序）。 */
    fun writeJson(path: Path) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": 1,\n")
        sb.append("  \"profile\": ").append(quote(profile)).append(",\n")
        sb.append("  \"engine\": ").append(quote(engine)).append(",\n")
        sb.append("  \"version\": ").append(quote(version)).append(",\n")
        sb.append("  \"corpus\": ").append(quote(corpusRoot.toString())).append(",\n")
        sb.append("  \"counts\": {\"files\": $files, \"compiled\": $compiled, ")
            .append("\"parseFailures\": $parseFailures, ")
            .append("\"unknownCommands\": ${unknownCommands.size}, ")
            .append("\"unknownFunctions\": ${unknownFunctions.size}},\n")
        sb.append("  \"unknownCommands\": [")
        appendRows(sb, unknownCommands)
        sb.append("],\n")
        sb.append("  \"unknownFunctions\": [")
        appendRows(sb, unknownFunctions)
        sb.append("]\n")
        sb.append("}\n")
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, sb.toString())
    }

    private fun appendRows(sb: StringBuilder, rows: List<EngineReportRow>) {
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(",")
            sb.append("\n    {\"name\": ").append(quote(r.name))
                .append(", \"file\": ").append(quote(r.file.replace('\\', '/')))
                .append(", \"line\": ").append(r.line).append("}")
        }
        if (rows.isNotEmpty()) sb.append("\n  ")
    }

    /** JSON 字符串转义（与 Main.kt 的 CLI 报告转义同一形状：引号/反斜杠/控制字符）。 */
    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
