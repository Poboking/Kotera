package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** recheck 默认复核条数上限（一条 ≈ 全量装载，分钟级；防误全量跑爆）。 */
private const val RECHECK_DEFAULT_MAX = 20

/** 有 CONFIRMED 真线索时的退出码（同 call 失败码口径）。 */
private const val EXIT_HAS_CONFIRMED = 3

/** 对照表/摘要里 message 的截断宽度（可读性线，超长看 recheck.jsonl 原文）。 */
private const val MSG_TRUNC = 120

/**
 * `recheck` 子命令（CONTRACT-DBG-TOOLING §4）：sweep 失败条目的 fresh 单跑批量复核。
 *
 * 背景：sweep 同会话逐函数复用（状态串染）——sweep 的 PASS 不证明正确、失败也不证明是虫
 * （Sweep.kt 类头明示），真线索历来靠人工把失败条目摘出来逐条 `call` 单跑复核。本命令把
 * 这一步半自动化：读 sweep.jsonl 的失败条目 → 每条开**独立 fresh 会话**单跑 → 输出对照表
 * 与结论分类，人工只需要读 recheck.md 的结论清单。
 *
 * 结论三分：
 * - `CONFIRMED`：fresh 单跑仍失败且 status 与 sweep 相同 —— 与串染无关，最可能是真虫；
 * - `CLEAR`：fresh 单跑 PASS —— sweep 失败是状态串染实锤，登记/判读时按噪声降权；
 * - `SHIFTED`：fresh 仍失败但 status 不同（如 CODEEE→STEP_LIMIT）—— 行为随前置状态变化，
 *   需人工判读，不算确认也不算洗清；
 * - `NOT_FOUND`：当前产物函数表里没有该 key（sweep 之后产物换过）—— 无法复核。
 *
 * 复核内核直接走 [CmdCall.invokeAndReport]（与 call 同一条实现：状态分类/步数/last-error
 * 落盘全复用，不另抄一份口径），输出重定向进内存缓冲再按行协议解析——这也是与 sweep
 * 实参口径逐字一致的结构性保证（口径漂移 = 单点改 invokeAndReport 时两侧同步变）。
 * 每条复核都重新 [Assembler.assemble]（fresh 口径，装载成本每条都要付）。
 */
internal object CmdRecheck {

    /** 一条复核结果（recheck.jsonl 一行）。 */
    private data class Row(
        val rec: SweptRecord,
        val fresh: FreshOutcome,
        val verdict: String,
    )

    /** 一次 fresh 单跑的结论（解析 invokeAndReport 的行协议所得）。 */
    private data class FreshOutcome(
        val status: String,
        val message: String,
        val steps: Long,
    )

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val statusFilter = a.value("--status")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val max = a.value("--max")?.toIntOrNull()?.takeIf { it > 0 } ?: RECHECK_DEFAULT_MAX
        val source = readCandidates(a, statusFilter)
        val todo = source.second.take(max)
        out.println(
            "pending=${source.first} selected=${todo.size} " +
                "max=$max statusFilter=${statusFilter?.joinToString(",") ?: "-"}",
        )
        val rows = todo.mapIndexed { i, rec ->
            val fresh = runOne(a, rec, err)
            val verdict = verdictOf(rec, fresh)
            out.println(
                "[${i + 1}/${todo.size}] key=${rec.key} sweep=${rec.status} " +
                    "fresh=${fresh.status} verdict=$verdict msg=${fresh.message.take(MSG_TRUNC)}",
            )
            Row(rec, fresh, verdict)
        }
        val confirmed = rows.count { it.verdict == "CONFIRMED" }
        out.println(
            "verdict: CONFIRMED=$confirmed CLEAR=${rows.count { it.verdict == "CLEAR" }} " +
                "SHIFTED=${rows.count { it.verdict == "SHIFTED" }} " +
                "NOT_FOUND=${rows.count { it.verdict == "NOT_FOUND" }}",
        )
        writeArtifacts(a, rows)
        return if (confirmed == 0) 0 else EXIT_HAS_CONFIRMED
    }

    /** 读 sweep.jsonl → 过滤失败态（--status 收窄）→ 按 key 去重（同 sweep 语义：同 key 保最后一条）。 */
    private fun readCandidates(a: Args, statusFilter: Set<String>?): Pair<Int, List<SweptRecord>> {
        val fromPath = a.value("--from")
            ?: throw CliError(
                "用法: recheck --erbc <dir> --game <root> --from <sweep.jsonl> " +
                    "[--status CODEEE,...] [--max N] [--out <目录>]",
            )
        val byKey = LinkedHashMap<String, SweptRecord>()
        Files.newBufferedReader(Path.of(fromPath), StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val rec = parseCandidate(line, statusFilter) ?: continue
                byKey[rec.key] = rec
            }
        }
        return byKey.size to byKey.values.toList()
    }

    /** 单行 → 候选记录（RUNNING 占位/无 key/PASS/过滤不命中都算非候选）。 */
    private fun parseCandidate(line: String, statusFilter: Set<String>?): SweptRecord? =
        if (line.isBlank()) {
            null
        } else {
            Jsonl.parseLine(line)
                ?.let { SweptRecord.fromFields(it) }
                ?.takeIf { it.failed }
                ?.takeIf { statusFilter == null || it.status in statusFilter }
        }

    /** 复核一条：独立 fresh 装配 + invokeAndReport（同 call 内核）+ 解析其行协议。 */
    private fun runOne(a: Args, rec: SweptRecord, err: PrintStream): FreshOutcome {
        val assembled = try {
            Assembler.assemble(a, out = null)
        } catch (e: CliError) {
            err.println("assemble 失败（key=${rec.key}）：${e.message}")
            return FreshOutcome("NOT_FOUND", e.message ?: "", -1L)
        }
        val buf = ByteArrayOutputStream()
        CmdCall.invokeAndReport(
            assembled, rec.fn, LongArray(0), emptyArray(), a,
            out = PrintStream(buf, true, StandardCharsets.UTF_8),
        )
        return parseOutcome(buf.toString(StandardCharsets.UTF_8))
    }

    /** invokeAndReport 的行协议 → 结论（status= / message= / steps=；缺 steps 记 -1）。 */
    private fun parseOutcome(text: String): FreshOutcome {
        val fields = text.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        return FreshOutcome(
            status = fields["status"] ?: "ERROR",
            message = fields["message"] ?: "",
            steps = fields["steps"]?.toLongOrNull() ?: -1L,
        )
    }

    private fun verdictOf(rec: SweptRecord, fresh: FreshOutcome): String = when {
        fresh.status == "PASS" -> "CLEAR"
        fresh.status == rec.status -> "CONFIRMED"
        else -> "SHIFTED"
    }

    /** --out 给了就写 recheck.jsonl + recheck.md（确定性输出：无时间戳、排序稳定）。 */
    private fun writeArtifacts(a: Args, rows: List<Row>) {
        val outDir = a.value("--out")?.let { Path.of(it) } ?: return
        Files.createDirectories(outDir)
        Files.writeString(
            outDir.resolve("recheck.jsonl"),
            rows.joinToString("") { it.toJson() + "\n" },
            StandardCharsets.UTF_8,
        )
        Files.writeString(outDir.resolve("recheck.md"), renderSummary(rows), StandardCharsets.UTF_8)
    }

    private fun Row.toJson(): String = Jsonl.obj(
        listOf(
            "fn" to rec.fn,
            "key" to rec.key,
            "status" to rec.status,
            "cls" to rec.cls,
            "line" to rec.line,
            "pc" to rec.pc,
            "msg" to rec.msg,
            "sweepSteps" to rec.steps,
            "freshStatus" to fresh.status,
            "freshMsg" to fresh.message,
            "freshSteps" to fresh.steps,
            "verdict" to verdict,
        ),
    )

    /** recheck.md 渲染：verdict 计数表 + CONFIRMED/SHIFTED 两个需人工的清单。 */
    private fun renderSummary(rows: List<Row>): String {
        val sb = StringBuilder()
        sb.append("# vmdbg recheck summary\n\n")
        sb.append("> 对照口径 = sweep 同款裸跑（invokeAndReport 复用 call 内核）；CONFIRMED 才是真线索。\n\n")
        val counts = rows.groupingBy { it.verdict }.eachCount()
        for (v in listOf("CONFIRMED", "CLEAR", "SHIFTED", "NOT_FOUND")) {
            sb.append("- $v: ${counts[v] ?: 0}\n")
        }
        val sections = listOf(
            "需登记的真线索（CONFIRMED）" to "CONFIRMED",
            "状态翻转（SHIFTED，需人工判读）" to "SHIFTED",
        )
        for ((title, verdict) in sections) {
            val list = rows.filter { it.verdict == verdict }
            if (list.isEmpty()) continue
            sb.append("\n## $title\n\n")
            sb.append("| 函数 | sweep | fresh | fresh message |\n")
            sb.append("|---|---|---|---|\n")
            for (r in list) {
                sb.append(
                    "| ${r.rec.fn} | ${r.rec.status} | ${r.fresh.status} | " +
                        r.fresh.message.take(MSG_TRUNC) + " |\n",
                )
            }
        }
        return sb.toString()
    }
}
