package com.eratw.port.vmdbg

import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.StateStep
import com.eratw.port.core.proc.StatementProbe
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlColumnType
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlDriver
import com.eratw.port.core.sql.SqlStatement
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** 纳秒 → 毫秒（整数除；命名常量避免裸魔数）。 */
private const val NS_PER_MS = 1_000_000L

/** 语句层异常取证最多记多少条（超出的只计数不落表）。 */
private const val MAX_STMT_ERRORS = 20

/** qol_data.db 建图段的三张主表（SQL 分类名，见 [sqlCategory]）。 */
private val GRAPH_TABLE_ROWS = listOf(
    "INSERT OR REPLACE GRAPH_DISTANCE",
    "INSERT OR REPLACE GRAPH_EDGE",
    "INSERT OR REPLACE GRAPH_NODE_ATTR",
)

/**
 * 桌面冷启链路分段量测探针（task-1，`perf-before-boot.md` 的读源）。
 *
 * 与 `:core-vm:erbcBootProfile` 的关键差别：这里走 [Assembler.assemble] 真链路 ——
 * 真 SQLite（[DesktopSqliteHost]，沙箱 `sql/` 拷种）与真 `InputSink` 装配都在，
 * 因此 `TITLE.ERB` 里 `@SYSTEM_TITLE` → `CALL QOL_DB_INIT` → `GRAPH_DB_INIT`
 * 的整条建图链（含 36,418 行 `GRAPH_DISTANCE` 写入）会被真实执行并计入读数。
 *
 * 量测口径（全部毫秒）：
 * 1. 装配层：`ProgramLoader.loadMapped` 的 [`LoadStageMark`] 逐段 + 侧车读取 + 驱动创建 + 会话装配；
 * 2. 启动层：`begin(TITLE)` 之后 `resumeSystem()` 泵把状态机推到每个输入等待点，等待点时刻
 *    由 `InputSink`（脚本输入）与 `QueueInputSink.onWaitEntered`（系统输入）两条路径记录；
 * 3. SQL 层：包装 [SqlDriver]，按语句分类计时 prepare/bind/step/close，并记录事务与 VACUUM 时间线。
 *
 * 铁律：语料与 erbc 产物只读；写侧只有 `--work` 下的临时沙箱（缺省 `build/bootchain-profile`）。
 * 环境变量：
 * - `ERATW_ERBC_DIR` / `ERATW_GAMEDIR`：必填（产物目录 / 游戏根）；
 * - `ERATW_BCP_WORK`：工作目录，缺省 `<cwd>/build/bootchain-profile`；
 * - `ERATW_BCP_SANDBOX`：沙箱目录，缺省 `<work>/sandbox`；`ERATW_BCP_FRESH=1` 先递归清空；
 * - `ERATW_BCP_WALL_MS`：启动推进墙钟上限，缺省 180000；
 * - `ERATW_BCP_MAX_HITS`：最多采集多少个等待点，缺省 300；
 * - `ERATW_BCP_STOP_AFTER`：泵到第几个等待点即停，缺省 1（TITLE 链）。
 *
 * 读数基准：桌面 JVM 的绝对值 ≠ ART 真机，**段间比例才是判据**。
 */
fun main() {
    try {
        probe()
    } catch (t: Throwable) {
        System.err.println("bootChainProfile 失败: " + t)
        for (frame in t.stackTrace) System.err.println("    at " + frame)
        exitProcess(1)
    }
    exitProcess(0)
}

/** 探针配置（环境变量 → 路径/开关；缺省值与 KDoc 同口径）。 */
private data class ProbeConfig(
    val erbcPath: Path,
    val gameRoot: Path,
    val work: Path,
    val sandbox: Path,
    val fresh: Boolean,
    val wallMs: Long,
    val maxHits: Int,
    val stopAfterHits: Int,
) {
    companion object {
        private const val DEFAULT_WALL_MS = 180_000L
        private const val DEFAULT_MAX_HITS = 300
        private const val HITS_CAP = 4000

        fun fromEnv(): ProbeConfig {
            val erbcDir = env("ERATW_ERBC_DIR") ?: error("缺 ERATW_ERBC_DIR（erbc.v1 目录）")
            val gameRootRaw = env("ERATW_GAMEDIR") ?: error("缺 ERATW_GAMEDIR（游戏根）")
            val work = env("ERATW_BCP_WORK")?.let { Path.of(it) }?.toAbsolutePath()?.normalize()
                ?: Path.of("build", "bootchain-profile").toAbsolutePath()
            val sandbox = env("ERATW_BCP_SANDBOX")?.let { Path.of(it) }?.toAbsolutePath()?.normalize()
                ?: work.resolve("sandbox")
            val erbcPath = Path.of(erbcDir).toAbsolutePath().normalize()
            val gameRoot = Path.of(gameRootRaw).toAbsolutePath().normalize()
            return ProbeConfig(
                erbcPath = erbcPath,
                gameRoot = gameRoot,
                work = work,
                sandbox = sandbox,
                fresh = env("ERATW_BCP_FRESH") == "1",
                wallMs = env("ERATW_BCP_WALL_MS")?.toLongOrNull() ?: DEFAULT_WALL_MS,
                maxHits = (env("ERATW_BCP_MAX_HITS")?.toIntOrNull() ?: DEFAULT_MAX_HITS).coerceIn(1, HITS_CAP),
                stopAfterHits = (env("ERATW_BCP_STOP_AFTER")?.toIntOrNull() ?: 1).coerceIn(1, HITS_CAP),
            )
        }
    }
}

/** 装配期分段采集（loadMark / phaseMark / session.stageMark 三条观测流）。 */
private class LoadTimings {
    val loadStages = LinkedHashMap<String, Long>()
    val phaseMs = LinkedHashMap<String, Long>()

    /** GameSession 构造内部的 session.* 分段（stageMark 逐条「距上一段」）。 */
    val sessionStages = LinkedHashMap<String, Long>()

    private var lastStageNs = System.nanoTime()

    // 起点取 `sqlDriver.create` 回调时刻（它之后紧接 GameSession 构造，中间只有兼容档 gc）。
    private var sessionLastNs = 0L

    val loadMark: LoadStageMark = LoadStageMark { stage ->
        val now = System.nanoTime()
        loadStages[stage] = (now - lastStageNs) / NS_PER_MS
        lastStageNs = now
    }

    fun onPhase(name: String, ms: Long) {
        phaseMs[name] = ms
        if (name == "sqlDriver.create") sessionLastNs = System.nanoTime()
    }

    fun onSessionStage(stage: String) {
        val now = System.nanoTime()
        if (sessionLastNs != 0L) {
            sessionStages[stage] = (now - sessionLastNs) / NS_PER_MS
        }
        sessionLastNs = now
    }
}

private fun probe() {
    val cfg = ProbeConfig.fromEnv()
    if (cfg.fresh) deleteRecursively(cfg.sandbox)
    val prof = SqlProfiler()
    val timings = LoadTimings()
    val startedNs = System.nanoTime()
    val assembled = assembleSession(cfg, prof, timings)
    val assembleWallMs = (System.nanoTime() - startedNs) / NS_PER_MS
    val session = assembled.session
    val funcKeys = assembled.metadata.functions.associate { it.index to it.key }
    val recorder = Recorder(cfg.maxHits)
    recorder.stackTop = { StackRead.read(session.runtime, funcKeys) }
    recorderHolder.set(recorder)
    session.inputSink.onWaitEntered = { recorder.hit("queue", "系统输入等待点（QueueInputSink）") }
    // 语句层异常取证：脚本报错被系统 proc 的错误路径吞进控制台后函数栈会被清空，
    // 只有语句探针能在上抛前拿到原始 CodeEE（函数键/行号/文案）。
    val stmtErrors = ArrayList<String>()
    session.runtime.statementProbe = statementErrors(stmtErrors)
    val boot = runBoot(session, funcKeys, recorder, cfg)
    writeReport(ReportData(cfg, timings, assembleWallMs, boot, recorder, prof, session, stmtErrors))
    runCatching { session.close() }
}

private fun assembleSession(cfg: ProbeConfig, prof: SqlProfiler, timings: LoadTimings): Assembler.Assembled =
    Assembler.assemble(
        buildArgs(cfg),
        sinkOverride = RecSink(recorderHolder),
        observers = Assembler.Observers(
            loadMark = timings.loadMark,
            sqlDriverWrapper = { driver -> ProfilingSqlDriver(driver, prof) },
            phaseMark = timings::onPhase,
            sessionStageMark = timings::onSessionStage,
        ),
    )

private fun buildArgs(cfg: ProbeConfig): Args {
    val tokens = ArrayList<String>()
    fun opt(name: String, value: String) {
        tokens += name
        tokens += value
    }
    opt("--erbc", cfg.erbcPath.toString())
    opt("--game", cfg.gameRoot.toString())
    opt("--work", cfg.work.toString())
    opt("--sandbox", cfg.sandbox.toString())
    env("ERATW_BCP_STEPS")?.let { opt("--steps", it) }
    tokens += "boot-chain" // 位置参数：非 sweep ⇒ 装置缺省 20,000,000 步
    return Args.parse(tokens)
}

private fun statementErrors(errors: MutableList<String>): StatementProbe =
    StatementProbe { op, _, env, _, error, _ ->
        if (error != null && errors.size < MAX_STMT_ERRORS) {
            errors += env.functionKey + ":" + env.line + " op=" + op + " → " +
                error.javaClass.simpleName + ": " + (error.message ?: "")
        }
    }

/** 启动推进的读数（[runBoot] 产物）。 */
private data class BootResult(
    val beginNote: String,
    val beginOk: Boolean,
    val firstWaitMs: Long,
    val bootWallMs: Long,
    val steps: Long,
    val stopNote: String,
    val sampler: FrameSampler,
)

/**
 * 启动推进：begin(TITLE) → 泵到第 [ProbeConfig.stopAfterHits] 个等待点即停。
 *
 * 缺省停在首个等待点（TITLE 链 = QOL_DB_INIT 全链 + 标题 INPUT）：继续泵下去会进入
 * First_Begin/@EVENTFIRST（游戏开场初始化，单次 resumeSystem 可能连跑十分钟且中途无等待点，
 * 墙钟闸拦不住）。需要量「进入游戏」链时设 ERATW_BCP_STOP_AFTER。
 */
private fun runBoot(
    session: GameSession,
    funcKeys: Map<Int, String>,
    recorder: Recorder,
    cfg: ProbeConfig,
): BootResult {
    val feederOn = AtomicBoolean(true)
    val feeder = startFeeder(session, feederOn)
    val sampler = FrameSampler()
    sampler.start(Thread.currentThread(), { StackRead.read(session.runtime, funcKeys) }) {
        StackRead.readAll(session.runtime, funcKeys)
    }
    recorder.start()
    val begin = beginTitle(session)
    val pump = pumpUntilWaiting(session, recorder, cfg)
    sampler.stop()
    feederOn.set(false)
    runCatching { feeder.join(2000) }
    val defaultNote = "达到等待点上限 " + cfg.stopAfterHits + "（停在 TITLE 链）"
    return BootResult(
        beginNote = begin.note,
        beginOk = begin.ok,
        firstWaitMs = recorder.hits.firstOrNull()?.cumMs ?: -1L,
        bootWallMs = recorder.elapsedMs(),
        steps = pump.steps,
        stopNote = pump.stopNote ?: begin.stopNote ?: defaultNote,
        sampler = sampler,
    )
}

private fun startFeeder(session: GameSession, feederOn: AtomicBoolean): Thread = Thread({
    val q = session.inputSink
    while (feederOn.get()) {
        try {
            if (q.isAwaiting) {
                q.submit("0")
                var guard = 0
                while (feederOn.get() && q.isAwaiting && guard < 3000) {
                    Thread.sleep(1)
                    guard++
                }
            } else {
                Thread.sleep(1)
            }
        } catch (_: Throwable) {
            feederOn.set(false)
        }
    }
}, "boot-chain-feeder").apply {
    isDaemon = true
    start()
}

/** beginStep(TITLE) 的原始回执（[describeBegin] 文案）与是否进入脚本。 */
private class BeginState(val note: String, val ok: Boolean, val stopNote: String?)

private fun beginTitle(session: GameSession): BeginState {
    var note = "beginStep(TITLE) 未执行"
    var ok = false
    var stopNote: String? = null
    // 用 beginStep 而不是 begin：前者把状态机这一步的 from/handler/to/outcome 原样交出来，
    // 「begin 返回 false」到底是 AWAIT_INPUT 还是调度失败可当场落字。
    try {
        val first = session.runtime.beginStep(BeginType.TITLE)
        note = describeBegin(first)
        ok = first.outcome == StepOutcome.CALLED || first.outcome == StepOutcome.REENTERED
        if (first.outcome == StepOutcome.BLOCKED || first.outcome == StepOutcome.FAILED) {
            stopNote = "beginStep 即终态: " + note
        }
    } catch (t: Throwable) {
        note = "beginStep(TITLE) 抛异常: " + t
        stopNote = note
    }
    return BeginState(note, ok, stopNote)
}

private fun describeBegin(first: StateStep): String =
    "beginStep(TITLE) → outcome=" + first.outcome + " from=" + first.from +
        " handler=" + first.handler + " to=" + first.to +
        " called=" + first.calledFunction +
        (first.blocker?.let { " blocker=" + it.kind + ":" + it.message } ?: "")

private class PumpResult(val steps: Long, val stopNote: String?)

private class PumpProgress(val steps: Long, val stopNote: String?)

private fun pumpUntilWaiting(session: GameSession, recorder: Recorder, cfg: ProbeConfig): PumpResult {
    var progress = PumpProgress(0L, null)
    try {
        while (progress.stopNote == null && recorder.hits.size < cfg.stopAfterHits) {
            progress = pumpAdvance(session, recorder, cfg, progress.steps)
        }
    } catch (t: Throwable) {
        return PumpResult(progress.steps, "启动推进抛异常: " + t)
    }
    return PumpResult(progress.steps, progress.stopNote)
}

private fun pumpAdvance(session: GameSession, recorder: Recorder, cfg: ProbeConfig, steps: Long): PumpProgress {
    val reason = pumpStopReason(session, recorder, cfg)
    if (reason != null) return PumpProgress(steps, reason)
    val step = session.resumeSystem()
    return PumpProgress(steps + 1, terminalStepNote(step))
}

private fun pumpStopReason(session: GameSession, recorder: Recorder, cfg: ProbeConfig): String? = when {
    session.runtime.quitRequested -> "quitRequested（脚本要求退出）"
    recorder.elapsedMs() > cfg.wallMs -> "墙钟上限 " + cfg.wallMs + "ms"
    else -> null
}

private fun terminalStepNote(step: StateStep): String? {
    if (step.outcome != StepOutcome.BLOCKED && step.outcome != StepOutcome.FAILED) return null
    return "状态机终态: " + step.outcome.name + " from=" + step.from +
        " to=" + step.to + " handler=" + step.handler +
        (step.blocker?.let { " blocker=" + it.kind + ":" + (it.message ?: "") } ?: "")
}

/** 报表所需的全部读数（打包传参，避免长参数表）。 */
private data class ReportData(
    val cfg: ProbeConfig,
    val timings: LoadTimings,
    val assembleWallMs: Long,
    val boot: BootResult,
    val recorder: Recorder,
    val prof: SqlProfiler,
    val session: GameSession,
    val stmtErrors: List<String>,
)

private fun writeReport(data: ReportData) {
    // 同时写一份 UTF-8 报告文件：pwsh 的 `*>` 会把探针 stdout 按控制台编码再转一次，
    // 控制台尾部的中文/日文文案在日志里会变 锟斤拷；文件这份是原文。
    val out = openReport(data.cfg)
    reportHeader(out, data.cfg)
    reportLoadSection(out, data.timings, data.assembleWallMs)
    reportBootSection(out, data.boot, data.recorder, data.session, data.stmtErrors)
    reportByFunc(out, data.recorder)
    printSamplerSection(out, data.boot.sampler)
    out.println()
    profileSqlSection(out, data.prof, data.recorder.startNs)
    out.println()
    reportFooter(out, data.session)
}

private fun openReport(cfg: ProbeConfig): PrintStream {
    val reportFile = cfg.work.resolve("boot-chain-profile.txt")
    return runCatching {
        val fileOut = java.io.FileOutputStream(reportFile.toFile())
        object : java.io.PrintStream(java.io.BufferedOutputStream(fileOut), false, "UTF-8") {
            override fun println(x: String?) {
                super.println(x)
                System.out.println(x)
            }

            override fun println() {
                super.println()
                System.out.println()
            }

            override fun flush() {
                super.flush()
                System.out.flush()
            }
        }
    }.getOrElse {
        System.out.println("（报告文件写入失败: $it，只打 stdout）")
        System.out
    }
}

private fun reportHeader(out: PrintStream, cfg: ProbeConfig) {
    out.println("## 桌面冷启链路分段读数（before）")
    out.println()
    out.println("- jvm: " + System.getProperty("java.version") + " / " + System.getProperty("java.vm.name"))
    out.println("- maxHeap: " + Runtime.getRuntime().maxMemory() / MIB + " MiB")
    out.println(
        "- erbc: " + cfg.erbcPath + "（program.erbc=" + sizeOf(cfg.erbcPath.resolve("program.erbc")) +
            "，program.meta=" + sizeOf(cfg.erbcPath.resolve("program.meta")) + "）",
    )
    out.println("- gameRoot: " + cfg.gameRoot)
    out.println("- work: " + cfg.work)
    out.println("- sandbox: " + cfg.sandbox + if (cfg.fresh) "（本次清空 ⇒ 首启建库）" else "（复用）")
    out.println("- 语料 qol_data.db 现状: " + describeGraphDb(cfg.gameRoot.resolve("plugins/qol_data.db")))
    out.println()
}

private fun reportLoadSection(out: PrintStream, timings: LoadTimings, assembleWallMs: Long) {
    out.println("### 装配分段")
    out.println()
    out.println("| 段 | ms | 占装配已测合计 | 备注 |")
    out.println("|---|---:|---:|---|")
    val loadTotalMs = timings.phaseMs["loadMapped.total"] ?: timings.loadStages.values.sum()
    var stageSum = 0L
    for ((stage, ms) in timings.loadStages) {
        stageSum += ms
        out.println(row(stage, ms, pct(ms, loadTotalMs), "loadMapped 内部段"))
    }
    if (loadTotalMs > stageSum) {
        out.println(
            row(
                "loadMapped 段间余量",
                loadTotalMs - stageSum,
                pct(loadTotalMs - stageSum, loadTotalMs),
                "回调未覆盖的收尾",
            ),
        )
    }
    val phaseOrder = listOf("loadMapped.total", "BootMetadata.read", "sqlDriver.create", "GameSession.assemble")
    var measuredSum = 0L
    for (name in phaseOrder) {
        val ms = timings.phaseMs[name] ?: continue
        measuredSum += ms
        out.println(row(name, ms, pct(ms, assembleWallMs), ""))
    }
    out.println(
        row(
            "**装配已测合计**",
            measuredSum,
            pct(measuredSum, assembleWallMs),
            "其余 =" + (assembleWallMs - measuredSum) + "ms 未被观察",
        ),
    )
    out.println(row("装配墙钟（含未观察）", assembleWallMs, 100, ""))
    out.println()
    reportSessionStages(out, timings)
}

private fun reportSessionStages(out: PrintStream, timings: LoadTimings) {
    if (timings.sessionStages.isEmpty()) return
    out.println("#### GameSession 构造内部分段（stageMark 逐段，距上一段）")
    out.println()
    out.println("| 内部段 | ms |")
    out.println("|---|---:|")
    var sessSum = 0L
    for ((name, ms) in timings.sessionStages) {
        sessSum += ms
        out.println(row(name, ms, ""))
    }
    val assemble = timings.phaseMs["GameSession.assemble"] ?: -1L
    out.println(row("**已观察合计**", sessSum, "GameSession.assemble=" + assemble + "ms，差值为未埋点段"))
    out.println()
}

private fun reportBootSection(
    out: PrintStream,
    boot: BootResult,
    recorder: Recorder,
    session: GameSession,
    stmtErrors: List<String>,
) {
    out.println("### 启动等待点推进（begin TITLE 之后）")
    out.println()
    out.println("- " + boot.beginNote)
    out.println("- begin 视为进入脚本: " + boot.beginOk + "（false 常见于一步一停停在 AWAIT_INPUT，不等于失败）")
    out.println(
        "- 首个等待点: " + (if (boot.firstWaitMs >= 0) boot.firstWaitMs.toString() + "ms" else "未命中") +
            "（begin→首等待，含 QOL_DB_INIT 全链）",
    )
    out.println("- 启动推进墙钟: " + boot.bootWallMs + "ms，resumeSystem 步数 " + boot.steps + "，等待点 " + recorder.hits.size)
    out.println("- 停止原因: " + boot.stopNote)
    out.println()
    reportStmtErrors(out, stmtErrors)
    reportConsoleTail(out, session)
    reportHits(out, recorder)
}

private fun reportStmtErrors(out: PrintStream, stmtErrors: List<String>) {
    out.println("### 语句层异常取证（StatementProbe 在 CodeEE 上抛前抓取）")
    out.println()
    if (stmtErrors.isEmpty()) {
        out.println("- 无异常语句（TITLE 链未在上抛前失败）")
    } else {
        out.println("| # | 函数:行 | op | 异常与文案 |")
        out.println("|---:|---|---:|---|")
        for ((i, e) in stmtErrors.withIndex()) {
            val parts = e.split(" op=", limit = 2)
            val rest = if (parts.size > 1) "op=" + parts[1] else e
            out.println(row(i, escapeCell(parts[0]), "", escapeCell(rest)))
        }
    }
    out.println()
}

private fun reportConsoleTail(out: PrintStream, session: GameSession) {
    out.println("### 控制台尾部（LineBufferConsoleSink.tailText(12)）")
    out.println()
    runCatching {
        val tail = session.console.tailText(12)
        if (tail.isEmpty()) out.println("- （控制台为空）") else for (line in tail) out.println("  > " + line)
    }.onFailure { out.println("- tailText 失败: " + it) }
    out.println()
}

private fun reportHits(out: PrintStream, recorder: Recorder) {
    out.println("| # | 距上次 ms | 累计 ms | 类型 | 细节 | 栈顶 函数:pc |")
    out.println("|---:|---:|---:|---|---|---|")
    for (h in recorder.hits) {
        out.println(row(h.idx, h.deltaMs, h.cumMs, h.kind, escapeCell(h.detail), escapeCell(h.stack)))
    }
    out.println()
}

private fun reportByFunc(out: PrintStream, recorder: Recorder) {
    val byFunc = LinkedHashMap<String, LongArray>()
    for (h in recorder.hits) {
        val fn = if (h.stack.isEmpty()) "(无栈顶)" else h.stack.substringBefore(':')
        val acc = byFunc.getOrPut(fn) { LongArray(2) }
        acc[0]++
        acc[1] += h.deltaMs
    }
    out.println("### 按 ERB 函数聚合等待耗时（delta 归属到等待点的栈顶，近似）")
    out.println()
    out.println("| 函数 | 命中 | delta 合计 ms |")
    out.println("|---|---:|---:|")
    for ((fn, acc) in byFunc.entries.sortedByDescending { it.value[1] }.take(25)) {
        out.println(row(escapeCell(fn), acc[0], acc[1], ""))
    }
    out.println()
}

private fun reportFooter(out: PrintStream, session: GameSession) {
    out.println("### 收尾")
    out.println()
    out.println("- 控制台行数: " + session.console.loggedLines().size)
    for (line in session.console.loggedLines().takeLast(5)) out.println("  > " + line)
    out.println()
    out.println(
        "口径：桌面 JVM 绝对值 ≠ ART 真机，**段间比例才是判据**；SQL 计时在 core SqlDriver 边界，" +
            "不含 AndroidX 底层 native 归因。",
    )
    out.flush()
}

// ---------------------------------------------------------------- 等待点采集

private val recorderHolder = AtomicReference<Recorder>()

private class Recorder(private val maxHits: Int) {
    class Hit(
        val idx: Int,
        val cumMs: Long,
        val deltaMs: Long,
        val kind: String,
        val detail: String,
        val stack: String,
    )

    val hits = ArrayList<Hit>()
    var startNs = 0L
        private set
    private var lastNs = 0L
    var stackTop: () -> String = { "" }

    fun start() {
        startNs = System.nanoTime()
        lastNs = startNs
    }

    fun elapsedMs(): Long = (System.nanoTime() - startNs) / NS_PER_MS

    fun hit(kind: String, detail: String) {
        if (hits.size >= maxHits) return
        val now = System.nanoTime()
        hits += Hit(
            idx = hits.size + 1,
            cumMs = (now - startNs) / NS_PER_MS,
            deltaMs = (now - lastNs) / NS_PER_MS,
            kind = kind,
            detail = detail,
            stack = runCatching { stackTop() }.getOrDefault(""),
        )
        lastNs = now
    }
}

private class RecSink(private val holder: AtomicReference<Recorder>) : InputSink {
    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        holder.get()?.hit("anykey", "readAnyKey(anyKey=$anyKey)")
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        holder.get()?.hit(
            "input",
            "type=" + request.type.name + " form=" + request.form.name +
                " hasDef=" + request.hasDef + " line=" + request.scriptLine,
        )
        return InputOutcome(
            intValue = if (request.hasDef) request.defInt else 0L,
            strValue = if (request.hasDef) request.defStr else "",
        )
    }
}

/**
 * TITLE 段热帧采样：25ms 抽一次泵线程的 Java 栈（叶子帧）与 VM 函数栈（ERB 栈顶 + 建图链是否在栈上）。
 * 采样本身开销远小于解释器，读数是「相对占比」判据。
 */
private class FrameSampler {
    private val javaLeaf = LinkedHashMap<String, Int>()
    private val erbTop = LinkedHashMap<String, Int>()
    private val graphPresence = LinkedHashMap<String, Int>()

    @Volatile
    var samples = 0
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val interesting = listOf(
        "QOL_DB_INIT", "GRAPH_DB_INIT", "GRAPH_DB_REBUILD", "BFS_MAP", "BUILD_MAP",
        "BUILD_CROSS_MAP", "BUILD_ATTR", "IMG_SQL_INIT", "QOL_ITEM_INIT",
    )

    fun start(pump: Thread, vmTop: () -> String, vmStack: () -> List<String>) {
        running = true
        thread = Thread({
            while (running) {
                runCatching {
                    val st = pump.stackTrace
                    val leaf = st.firstOrNull { it.className.startsWith("com.eratw.port.core") }
                    if (leaf != null) {
                        val key = leaf.className.removePrefix("com.eratw.port.core.") + "." +
                            leaf.methodName + ":" + leaf.lineNumber
                        synchronized(javaLeaf) { javaLeaf[key] = (javaLeaf[key] ?: 0) + 1 }
                    }
                    val top = vmTop()
                    if (top.isNotEmpty()) {
                        val fn = top.substringBefore(':')
                        synchronized(erbTop) { erbTop[fn] = (erbTop[fn] ?: 0) + 1 }
                    }
                    for (k in vmStack()) {
                        val hit = interesting.firstOrNull { k.contains(it) } ?: continue
                        synchronized(graphPresence) { graphPresence[hit] = (graphPresence[hit] ?: 0) + 1 }
                    }
                    samples++
                }
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "boot-chain-sampler").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
    }

    fun javaLeafTop(n: Int): List<Pair<String, Int>> = synchronized(javaLeaf) {
        javaLeaf.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    fun erbTopTop(n: Int): List<Pair<String, Int>> = synchronized(erbTop) {
        erbTop.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    fun presenceOf(): List<Pair<String, Int>> = synchronized(graphPresence) {
        graphPresence.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

/** 栈顶「函数名:pc」：工具侧反射先例见 `Explore.kt` 的 VmStackReader。 */
private object StackRead {
    private val vmField = runCatching {
        ProcessRuntime::class.java.getDeclaredField("vm").apply { isAccessible = true }
    }.getOrNull()
    private val funcStackField = runCatching {
        VirtualMachine::class.java.getDeclaredField("funcStack").apply { isAccessible = true }
    }.getOrNull()
    private val depthField = runCatching {
        VirtualMachine::class.java.getDeclaredField("depth").apply { isAccessible = true }
    }.getOrNull()

    fun read(rt: ProcessRuntime?, keys: Map<Int, String>): String {
        val vm = vmOf(rt)
        val stack = funcStackOf(vm)
        val depth = depthOf(vm)
        val idx = if (stack != null && depth in 0 until stack.size) stack[depth] else -1
        return when {
            vm == null || idx < 0 -> ""
            else -> (keys[idx] ?: ("#" + idx)) + ":" + vm.lastPc
        }
    }

    /** 自底向顶的整条 ERB 函数栈（采样用；只读，允许读到正在变化的栈，越界项忽略）。 */
    fun readAll(rt: ProcessRuntime?, keys: Map<Int, String>): List<String> {
        val vm = vmOf(rt)
        val stack = funcStackOf(vm)
        val depth = depthOf(vm)
        val count = if (stack == null || depth < 0) 0 else minOf(depth + 1, stack.size)
        val out = ArrayList<String>(count)
        val frames = stack ?: return out
        for (i in 0 until count) {
            val idx = frames[i]
            out += keys[idx] ?: ("#" + idx)
        }
        return out
    }

    private fun vmOf(rt: ProcessRuntime?): VirtualMachine? {
        val field = vmField ?: return null
        return if (rt == null) null else runCatching { field.get(rt) as? VirtualMachine }.getOrNull()
    }

    private fun funcStackOf(vm: VirtualMachine?): IntArray? {
        val field = funcStackField ?: return null
        return if (vm == null) null else runCatching { field.get(vm) as? IntArray }.getOrNull()
    }

    private fun depthOf(vm: VirtualMachine?): Int {
        val field = depthField ?: return -1
        return if (vm == null) -1 else runCatching { field.getInt(vm) }.getOrDefault(-1)
    }
}

// ---------------------------------------------------------------- SQL 计时包装

private class SqlCat {
    var count = 0L
    var openNs = 0L
    var prepareNs = 0L
    var bindNs = 0L
    var stepNs = 0L
    var closeNs = 0L

    fun totalNs(): Long = openNs + prepareNs + bindNs + stepNs + closeNs
}

private class SqlProfiler {
    val cats = LinkedHashMap<String, SqlCat>()

    /** 事务/BFS 时间线（原始 nanoTime）。 */
    var txnStartNs = 0L
    var txnEndNs = 0L
    var txnSqlNs = 0L
    var txnOpen = false
    var vacuumStartNs = 0L
    var vacuumEndNs = 0L
    var graphOpenNs = 0L
    var graphOpenEndNs = 0L

    fun cat(name: String): SqlCat = cats.getOrPut(name) { SqlCat() }

    fun onOpen(connectionString: String, startNs: Long, endNs: Long) {
        if (connectionString.contains("qol_data.db", ignoreCase = true) && graphOpenNs == 0L) {
            graphOpenNs = startNs
            graphOpenEndNs = endNs
        }
    }

    fun onPrepare(catName: String, startNs: Long) {
        when (catName) {
            CAT_BEGIN -> {
                txnStartNs = startNs
                txnOpen = true
            }
            CAT_VACUUM -> if (vacuumStartNs == 0L) vacuumStartNs = startNs
        }
    }

    fun onStepDone(catName: String, endNs: Long) {
        when (catName) {
            CAT_BEGIN -> txnStartNs = endNs
            CAT_COMMIT -> {
                txnEndNs = endNs
                txnOpen = false
            }
            CAT_VACUUM -> vacuumEndNs = endNs
        }
    }

    fun onStatementClosed(catName: String, totalNs: Long) {
        if (txnOpen && catName != CAT_BEGIN && catName != CAT_COMMIT) txnSqlNs += totalNs
    }

    fun totalNs(): Long = cats.values.sumOf { it.totalNs() }

    companion object {
        const val CAT_BEGIN = "BEGIN(事务)"
        const val CAT_COMMIT = "COMMIT(事务)"
        const val CAT_VACUUM = "VACUUM"
    }
}

private class ProfilingSqlDriver(private val delegate: SqlDriver, private val prof: SqlProfiler) : SqlDriver {
    override fun open(connectionString: String): SqlConnection {
        val t = System.nanoTime()
        val conn = delegate.open(connectionString)
        val now = System.nanoTime()
        val cat = prof.cat("OPEN " + shortCs(connectionString))
        cat.count++
        cat.openNs += now - t
        prof.onOpen(connectionString, t, now)
        return ProfilingConnection(conn, prof)
    }
}

private class ProfilingConnection(private val d: SqlConnection, private val prof: SqlProfiler) : SqlConnection {
    override fun prepare(sql: String): SqlStatement {
        val catName = sqlCategory(sql)
        val cat = prof.cat(catName)
        val t = System.nanoTime()
        val st = d.prepare(sql)
        val prepareNs = System.nanoTime() - t
        cat.count++
        cat.prepareNs += prepareNs
        prof.onPrepare(catName, t)
        return ProfilingStatement(st, cat, prof, catName, prepareNs)
    }

    override fun totalChanges(): Long = d.totalChanges()

    override fun lastChanges(): Long = d.lastChanges()

    override fun close() = d.close()
}

private class ProfilingStatement(
    private val s: SqlStatement,
    private val cat: SqlCat,
    private val prof: SqlProfiler,
    private val catName: String,
    prepareNs: Long,
) : SqlStatement {
    private var totalNs: Long = prepareNs

    override val columnCount: Int get() = s.columnCount

    override fun bind(index: Int, value: SqlBind) {
        val t = System.nanoTime()
        s.bind(index, value)
        val d = System.nanoTime() - t
        cat.bindNs += d
        totalNs += d
    }

    override fun step(): Boolean {
        val t = System.nanoTime()
        val r = s.step()
        val now = System.nanoTime()
        cat.stepNs += now - t
        totalNs += now - t
        prof.onStepDone(catName, now)
        return r
    }

    override fun reset() = s.reset()

    override fun clearBindings() = s.clearBindings()

    override fun columnName(index: Int): String = s.columnName(index)

    override fun columnType(index: Int) = s.columnType(index)

    override fun isNull(index: Int): Boolean = s.isNull(index)

    override fun getLong(index: Int): Long = s.getLong(index)

    override fun getDouble(index: Int): Double = s.getDouble(index)

    override fun getText(index: Int): String = s.getText(index)

    override fun getBlob(index: Int): ByteArray = s.getBlob(index)

    override fun close() {
        val t = System.nanoTime()
        s.close()
        val d = System.nanoTime() - t
        cat.closeNs += d
        totalNs += d
        prof.onStatementClosed(catName, totalNs)
    }
}

/** SQL 文本 → 统计分类（只按语句头与目标表归类，不改语句本身）。 */
private fun sqlCategory(sql: String): String {
    val t = sql.trim().uppercase()
    return fixedCategory(t) ?: verbCategory(t) ?: t.substringBefore(' ').substringBefore('(').take(24)
}

/** 无目标表的固定分类（建表/事务/PRAGMA/VACUUM 等）。 */
private fun fixedCategory(t: String): String? = when {
    t.startsWith("CREATE TABLE") -> "CREATE TABLE"
    t.startsWith("CREATE UNIQUE INDEX") -> "CREATE INDEX"
    t.startsWith("CREATE INDEX") -> "CREATE INDEX"
    t.startsWith("BEGIN") -> SqlProfiler.CAT_BEGIN
    t.startsWith("COMMIT") -> SqlProfiler.CAT_COMMIT
    t.startsWith("ROLLBACK") -> "ROLLBACK(事务)"
    t.startsWith("VACUUM") -> SqlProfiler.CAT_VACUUM
    t.startsWith("PRAGMA") -> "PRAGMA " + t.removePrefix("PRAGMA").trim().substringBefore('=').trim()
    else -> null
}

/** 带目标表的动词分类（INSERT/REPLACE/DELETE/UPDATE/SELECT）。 */
private fun verbCategory(t: String): String? = when {
    t.startsWith("INSERT OR REPLACE INTO") -> "INSERT OR REPLACE " + tableAfter(t, 22)
    t.startsWith("INSERT OR IGNORE INTO") -> "INSERT OR IGNORE " + tableAfter(t, 22)
    t.startsWith("INSERT INTO") -> "INSERT " + tableAfter(t, 11)
    t.startsWith("REPLACE INTO") -> "REPLACE " + tableAfter(t, 12)
    t.startsWith("DELETE FROM") -> "DELETE " + tableAfter(t, 11)
    t.startsWith("UPDATE") -> "UPDATE " + tableAfter(t, 6)
    t.startsWith("SELECT") -> selectCategory(t)
    else -> null
}

private fun selectCategory(t: String): String {
    val from = t.indexOf(" FROM ")
    return if (from < 0) "SELECT" else "SELECT " + tableAfter(t, from + 6)
}

/** 跳过 [keywordLen] 个字符后的语句头，取目标表名（引号/括号截断，兜底 "?"）。 */
private fun tableAfter(t: String, keywordLen: Int): String {
    if (t.length <= keywordLen) return "?"
    val rest = t.substring(keywordLen).trim()
    val tok = rest.substringBefore(' ').substringBefore('(').trim()
    return tok.trim('"', '\'', '`', '[', ']')
}

private fun shortCs(connectionString: String): String {
    val s = connectionString.trim().removePrefix("Data Source=").removePrefix("data source=")
    return if (s.length <= 48) s else s.take(45) + "..."
}

// ---------------------------------------------------------------- 输出辅助

private const val MIB = 1024L * 1024L

private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun row(vararg cells: Any?): String = "| " + cells.joinToString(" | ") { it?.toString() ?: "" } + " |"

private fun pct(part: Long, total: Long): String =
    if (total <= 0L) "-" else String.format(Locale.ROOT, "%.1f%%", part * 100.0 / total)

private fun escapeCell(s: String): String = s.replace("|", "\\|").replace("\n", " ")

private fun sizeOf(p: Path): String =
    if (Files.isRegularFile(p)) (Files.size(p) / MIB).toString() + " MiB" else "缺失"

private fun describeGraphDb(p: Path): String =
    if (Files.isRegularFile(p)) Files.size(p).toString() + " B" else "不存在（首启从零建）"

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    runCatching {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p -> runCatching { Files.deleteIfExists(p) } }
        }
    }
}

private fun printSamplerSection(out: PrintStream, s: FrameSampler) {
    out.println("### TITLE 段热帧（25ms 采样，共 " + s.samples + " 次）")
    out.println()
    if (s.samples <= 0) {
        out.println("- 无采样（TITLE 链在 25ms 内跑完或采样线程未启动）")
        out.println()
        return
    }
    out.println("| Java 叶子帧（core 包内最内层） | 采样 | 占比 |")
    out.println("|---|---:|---:|")
    for ((frame, c) in s.javaLeafTop(12)) {
        out.println(row(escapeCell(frame), c, pct(c.toLong(), s.samples.toLong())))
    }
    out.println()
    out.println("| ERB 栈顶函数 | 采样 | 占比 |")
    out.println("|---|---:|---:|")
    for ((fn, c) in s.erbTopTop(12)) {
        out.println(row(escapeCell(fn), c, pct(c.toLong(), s.samples.toLong())))
    }
    out.println()
    val presence = s.presenceOf()
    if (presence.isEmpty()) {
        out.println("- 建图链函数未在采样栈上出现（QOL_DB_INIT / GRAPH_DB_INIT 等）")
    } else {
        out.println("- 建图链函数在 ERB 栈上的采样数（同一次采样可计多个函数）：")
        for ((fn, c) in presence) {
            out.println("  - " + fn + "：" + c + " 次（" + pct(c.toLong(), s.samples.toLong()) + "）")
        }
    }
    out.println()
}

private fun profileSqlSection(out: PrintStream, prof: SqlProfiler, startNs: Long) {
    reportSqlTable(out, prof)
    reportGraphEvents(out, prof, startNs)
    reportGraphTables(out, prof)
}

private fun reportSqlTable(out: PrintStream, prof: SqlProfiler) {
    out.println("### SQL 驱动分段（core SqlDriver 边界）")
    out.println()
    out.println("| 分类 | 次数 | open ms | prepare ms | bind ms | step ms | close ms | 合计 ms |")
    out.println("|---|---:|---:|---:|---:|---:|---:|---:|")
    for ((name, c) in prof.cats.entries.sortedByDescending { it.value.totalNs() }) {
        out.println(
            row(
                escapeCell(name),
                c.count,
                ns2ms(c.openNs),
                ns2ms(c.prepareNs),
                ns2ms(c.bindNs),
                ns2ms(c.stepNs),
                ns2ms(c.closeNs),
                ns2ms(c.totalNs()),
            ),
        )
    }
    val sqlTotalNs = prof.totalNs()
    out.println(row("**SQL 合计**", prof.cats.values.sumOf { it.count }, "-", "-", "-", "-", "-", ns2ms(sqlTotalNs)))
    out.println()
}

private fun reportGraphEvents(out: PrintStream, prof: SqlProfiler, startNs: Long) {
    out.println("### 建图链时间线（qol_data.db，相对启动开始）")
    out.println()
    if (prof.graphOpenNs == 0L) {
        out.println("- 未观察到 qol_data.db 打开（TITLE 链未走到 GRAPH_DB_INIT？）")
        return
    }
    out.println(
        "- 打开 qol_data.db: " + relMs(prof.graphOpenNs, startNs) + "ms（耗时 " +
            ns2ms(prof.graphOpenEndNs - prof.graphOpenNs) + "ms）",
    )
    if (prof.txnStartNs != 0L) {
        out.println("- BEGIN 完成: " + relMs(prof.txnStartNs, startNs) + "ms")
    }
    if (prof.txnEndNs != 0L) {
        out.println(
            "- COMMIT 完成: " + relMs(prof.txnEndNs, startNs) + "ms（事务跨度 " +
                ns2ms(prof.txnEndNs - prof.txnStartNs) + "ms）",
        )
    }
    if (prof.vacuumStartNs != 0L) {
        out.println("- VACUUM 开始: " + relMs(prof.vacuumStartNs, startNs) + "ms")
    }
    if (prof.vacuumEndNs != 0L) {
        out.println(
            "- VACUUM 结束: " + relMs(prof.vacuumEndNs, startNs) + "ms（耗时 " +
                ns2ms(prof.vacuumEndNs - prof.vacuumStartNs) + "ms）",
        )
    }
    val endNs = when {
        prof.vacuumEndNs != 0L -> prof.vacuumEndNs
        prof.txnEndNs != 0L -> prof.txnEndNs
        else -> prof.graphOpenEndNs
    }
    out.println("- 建图跨度（open→VACUUM 结束）: " + ns2ms(endNs - prof.graphOpenNs) + "ms")
    out.println(
        "- 事务内 SQL 合计: " + ns2ms(prof.txnSqlNs) + "ms（占建图跨度 " +
            pct(prof.txnSqlNs, endNs - prof.graphOpenNs) + "）",
    )
    out.println("- 建图 SQL 合计（qol_data.db 相关语句 dt 之和无法按连接归因，见上表）")
}

private fun reportGraphTables(out: PrintStream, prof: SqlProfiler) {
    for (name in GRAPH_TABLE_ROWS) {
        val c = prof.cats[name] ?: continue
        val perRow = if (c.count > 0) {
            String.format(Locale.ROOT, "%.3f", c.totalNs() / NS_PER_MS.toDouble() / c.count)
        } else {
            "-"
        }
        out.println(
            "- " + name + ": " + c.count + " 条，" + ns2ms(c.totalNs()) + "ms（prepare " +
                ns2ms(c.prepareNs) + " / step " + ns2ms(c.stepNs) + " / close " + ns2ms(c.closeNs) +
                "，单条 " + perRow + "ms）",
        )
    }
}

private fun ns2ms(ns: Long): Long = ns / NS_PER_MS

private fun relMs(ns: Long, startNs: Long): Long = if (ns == 0L) -1L else (ns - startNs) / NS_PER_MS
