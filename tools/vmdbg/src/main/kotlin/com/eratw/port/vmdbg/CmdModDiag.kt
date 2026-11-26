package com.eratw.port.vmdbg

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.DiagnosticRecord
import com.eratw.port.core.diag.diagnosticSummariesOf
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `moddiag <jsonl 文件>`：只读的失败归因摘要命令（契约 §2 Lane DX3-B）。
 *
 * 输入是 [com.eratw.port.core.diag.RuntimeDiagnostics.exportJsonLines] 的落盘文件（九键恒在、
 * 一条一行）。本命令把每行还原成 [DiagnosticRecord]，再调 [diagnosticSummariesOf] 出摘要表 ——
 * 聚合口径（只 FAILED / REJECTED、三元组分组、count 降序 + key 字典序）与引擎侧**同一个函数**，
 * 不另写一套判据，避免工具面与引擎面漂移。
 *
 * 只读铁律：只 [Files.readString] 一个入参文件，不装配会话、不读 --erbc / --game、不写任何文件，
 * 也不碰 VM 执行路径。坏路径 / 坏行 / 缺字段 / 非法枚举一律折成 [CliError]（退出码 1 + 清晰中文
 * 错误，含行号），不把堆栈甩给 agent。空文件 = records=0、groups=0，正常退出 0。
 *
 * 输出：头三行 source / records / groups，表头之后每行一条摘要：
 * `mod.diag.summary count=<N> domain=<D> package=<P> outcome=<O>`（null 包 id 显示 `<engine>`）。
 * `mod.diag.summary` 前缀与 shutdown 日志的摘要行同形，独立可筛；不改既有 mod.diag 逐条行。
 */
object CmdModDiag {

    /** exportJsonLines 的九个键，顺序与写入侧一致（缺任意一个 = 文件损坏）。 */
    private val REQUIRED_KEYS = listOf(
        "seq", "day", "phase", "domain", "package", "key", "outcome", "reason", "detail",
    )

    fun run(a: Args, out: PrintStream): Int {
        val raw = a.positional.getOrNull(1)
            ?: throw CliError("用法: moddiag <jsonl 文件>（exportJsonLines 的落盘路径）")
        val path = try {
            Path.of(raw)
        } catch (e: InvalidPathException) {
            throw CliError("非法文件路径: $raw", e)
        }
        if (!Files.isRegularFile(path)) throw CliError("JSONL 文件不存在或不是普通文件: $path")
        val text = try {
            Files.readString(path, Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            throw CliError("读取 JSONL 文件失败: $path（${e.message ?: e.javaClass.simpleName}）", e)
        }

        val records = ArrayList<DiagnosticRecord>()
        var lineNo = 0
        for (line in text.lineSequence()) {
            lineNo++
            if (line.isBlank()) continue
            records += parseRecord(line, lineNo, path.toString())
        }

        val summary = diagnosticSummariesOf(records)
        out.println("source=$path")
        out.println("records=${records.size}")
        out.println("groups=${summary.size}")
        out.println("--- mod.diag.summary (count desc, key asc) ---")
        for (s in summary) {
            out.println(
                "mod.diag.summary count=${s.count} domain=${s.domain.name} " +
                    "package=${s.packageId ?: "<engine>"} outcome=${s.outcome.name}",
            )
        }
        return 0
    }

    /**
     * 解析一行成 [DiagnosticRecord]；任何损坏都折 [CliError]（含行号与路径），不抛给 agent。
     * 独立成函数让 [run] 只管 I/O 与输出，圈复杂度不随字段校验增长。
     */
    private fun parseRecord(line: String, lineNo: Int, path: String): DiagnosticRecord {
        val row = Jsonl.parseLine(line)
            ?: throw CliError("JSONL 第 $lineNo 行不是可解析的平面 JSON 对象: $path")
        for (key in REQUIRED_KEYS) {
            if (row[key] == null) {
                throw CliError("JSONL 第 $lineNo 行缺字段 $key（exportJsonLines 的九键必须恒在）")
            }
        }
        val domainName = row.getValue("domain")
        val outcomeName = row.getValue("outcome")
        val domain = enumValues<DiagnosticDomain>().firstOrNull { it.name == domainName }
            ?: throw CliError("JSONL 第 $lineNo 行 domain 非法: $domainName")
        val outcome = enumValues<DiagnosticOutcome>().firstOrNull { it.name == outcomeName }
            ?: throw CliError("JSONL 第 $lineNo 行 outcome 非法: $outcomeName")
        return DiagnosticRecord(
            seq = row.getValue("seq").toLongOrNull() ?: 0L,
            day = row.getValue("day").toLongOrNull(),
            phase = nullable(row.getValue("phase")),
            domain = domain,
            packageId = nullable(row.getValue("package")),
            key = row.getValue("key"),
            outcome = outcome,
            reasonCode = row.getValue("reason"),
            detail = nullable(row.getValue("detail")),
        )
    }

    /** JSON 的 null 被 [Jsonl.parseLine] 读成字面串 `null`，这里还原成 Kotlin null。 */
    private fun nullable(v: String): String? = if (v == "null") null else v
}
