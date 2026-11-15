package com.eratw.port.boot

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.ConsoleBeginHost
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SidecarTables
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.save.EmSaveSets
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * QOL 天堂之门 → 补丁页（OPTION_DLC）UI 覆盖块探针。
 *
 * 玩家反馈（0.2.45，真机横屏）：「补丁页面有时会有 ui 错误……调了一下窗口之类的，
 * 在调补丁时，有时候莫名其妙会出现」——截图上 QOL 设置面板（侧栏卡 + 内容窗体）
 * 压在补丁页正文之上。本探针按真机操作序（104 切到天堂之门 → 1 进补丁页 → 0 返回）
 * 在每个 INPUT 等待点抓整屏 + 每行的 div 覆盖块几何，用于定位覆盖块锚行/几何的偏差。
 *
 * 产物：`build/qol-patch-probe-<tag>.md`。
 * 环境变量：`ERATW_ERBC_4981`（缺省 tools/erbcc/build/erbc-4981/erbc.v1）、
 * `ERATW_PROBE_WIDTH`（缺省 136）、`ERATW_CORPUS`（4.981 ERB 目录）。
 *
 * 结构（2026-11-13 入库时按 detekt 拆段，语句逐条搬移未改语义）：场景参数解包
 * [parseScenario] → 变量池装配 [buildStore] → Runtime 装配 [buildRuntime] →
 * 喂食线程 [startFeeder]（快照格式化 [formatSnapshot]）→ 报告落盘 [writeReport]。
 */
class QolPatchPageProbeTest {

    /** 一次探针的场景参数（环境变量在 [parseScenario] 一次性解包，此后不再读环境）。 */
    private class Scenario(
        val scriptText: String,
        val reflow: Boolean,
        val resizeStep: Int,
        val resizeWidth: Int,
        val tag: String,
        val erbcDir: Path,
    )

    /** [buildStore] 与 [buildRuntime] 共用的装配上下文（避免逐参数透传越过 LongParameterList 阈）。 */
    private class ProbeAssembly(
        val gameRoot: Path,
        val metadata: BootMetadata,
        val store: VariableStore,
        val console: LineBufferConsoleSink,
        val effective: EffectiveConfig,
    )

    @Test
    fun probeQolTab4IntoDlcPage() {
        val scenario = parseScenario() ?: return
        val corpus = RealCorpus.load() ?: return
        val gameRoot = corpus.root.parent ?: corpus.root
        val program = ProgramLoader.loadMapped(scenario.erbcDir.resolve("program.erbc"))
        val metadata = BootMetadata.read(scenario.erbcDir.resolve("program.meta"))
        val bootOverrides = loadConfigOverrides(gameRoot)
        val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(bootOverrides)
        val console = buildConsole(scenario.reflow)
        val effective = EffectiveConfig.of(EmueraConfigParser.loadChainFromDisk(gameRoot).store)
        val store = buildStore(gameRoot, metadata, console, effective, saveInBinary)
        val inputSink = QueueInputSink()
        val rt = buildRuntime(program, ProbeAssembly(gameRoot, metadata, store, console, effective), inputSink)

        val snaps = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val script = java.util.ArrayDeque<Long>().apply {
            scenario.scriptText.split(',').mapNotNull { it.trim().toLongOrNull() }.forEach { addLast(it) }
        }
        val feeder = startFeeder(inputSink, console, snaps, script, scenario)
        feeder.start()

        var error: Throwable? = null
        try {
            repeat(10) {
                console.print("菜单垫行$it")
                console.newLine()
            }
            rt.callFunction("QOL_OPTION")
        } catch (t: Throwable) {
            error = t
        } finally {
            feeder.interrupt()
            feeder.join(5_000)
        }

        writeReport(scenario, corpus, console, error, snaps)
        error?.let { throw it }
    }

    /**
     * 场景参数解包（原内联段）。产物缺失 = 探针 prerequisites 不在，打印后返回 null
     * —— 动态用例约定：不生成、不计 skipped（见类头环境变量表）。
     */
    private fun parseScenario(): Scenario? {
        // 场景参数（默认 = 原基线）。真机复现用：
        //   ERATW_PROBE_SCRIPT=104,1,1002,0,999  （翻到补丁页第 2 页）
        //   ERATW_PROBE_REFLOW=1                  （LIVE-REFLOW 开）
        //   ERATW_PROBE_RESIZE=2:110              （第 2 个 INPUT 等待点前改宽到 110 格）
        val scriptText = System.getenv("ERATW_PROBE_SCRIPT")?.takeIf { it.isNotBlank() } ?: "104,1,0,999"
        val reflow = System.getenv("ERATW_PROBE_REFLOW")?.trim() == "1"
        val resizeSpec = System.getenv("ERATW_PROBE_RESIZE")?.trim()?.takeIf { it.isNotBlank() }
        val resizeStep = resizeSpec?.substringBefore(':')?.toIntOrNull() ?: -1
        val resizeWidth = resizeSpec?.substringAfter(':', "")?.toIntOrNull() ?: 0
        val tag = System.getenv("ERATW_PROBE_TAG")?.takeIf { it.isNotBlank() } ?: "base"
        val erbcDir = Path.of(
            System.getenv("ERATW_ERBC_4981")?.takeIf { it.isNotBlank() }
                ?: "../tools/erbcc/build/erbc-4981/erbc.v1",
        ).toAbsolutePath().normalize()
        if (!Files.isRegularFile(erbcDir.resolve("program.erbc")) ||
            !Files.isRegularFile(erbcDir.resolve("program.meta"))
        ) {
            System.err.println("[qol-probe] 产物缺失：$erbcDir")
            return null
        }
        return Scenario(scriptText, reflow, resizeStep, resizeWidth, tag, erbcDir)
    }

    /** 控制台 sink（宽度/历史行数可由环境旋钮改，[reflow] 接 LIVE-REFLOW 开关）。 */
    private fun buildConsole(reflow: Boolean): LineBufferConsoleSink = LineBufferConsoleSink(
        clientWidth = System.getenv("ERATW_PROBE_WIDTH")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: 136,
        printCWidth = 25,
        maxLines = System.getenv("ERATW_PROBE_MAXLINES")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: 900,
        reflowEnabled = reflow,
    )

    /** 变量池装配：CSV 装载 + 模板/角色 + 关键字折叠表 + 全局/私有声明引导（原 93-136 行搬移）。 */
    private fun buildStore(
        gameRoot: Path,
        metadata: BootMetadata,
        console: LineBufferConsoleSink,
        effective: EffectiveConfig,
        saveInBinary: Boolean,
    ): VariableStore {
        val gameData = GameDataLoader.load(gameRoot.resolve("CSV"))
        val store = VariableStore(
            gameData?.registry ?: VariableRegistry(),
            VarConfig(systemSaveInBinary = saveInBinary),
        )
        gameData?.let { data ->
            for (t in data.templates) store.registerTemplate(t)
            store.loadItemPrice(data.itemPrices)
            store.reloadStrNames()
        }
        if (gameData != null && store.template(0L) != null) store.addCharacter(0L)
        else repeat(4) { store.characters.addBare() }
        val foldKeywordTables: Map<String, Map<String, Int>> =
            (if (effective.bool(ConfigKey.UseERD)) {
                val systemNames = gameData?.registry?.all()
                    ?.mapTo(HashSet()) { it.name.uppercase() } ?: emptySet()
                runCatching { UserKeywordTables.foldSnapshot(gameRoot, systemNames) }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }) + (gameData?.registry?.systemKeywordTables() ?: emptyMap())
        val foldVarSizes = SystemVariableBootstrap.mergedVarSizes(
            base = gameData?.let { com.eratw.port.core.vars.DimVarSizes.ofRegistry(it.registry) }
                ?: com.eratw.port.core.vars.DimVarSizes.systemSnapshot(null),
            declarations = metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            config = metadata.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
            keywordTables = foldKeywordTables,
            consoleWidth = console.clientWidth,
        )
        SystemVariableBootstrap.install(
            store,
            metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            metadata.dimParseConfig
                .copy(systemSaveInBinary = saveInBinary)
                .withPureStaticDimFolders(
                    varSizes = foldVarSizes,
                    keywordTables = foldKeywordTables,
                    consoleWidth = console.clientWidth,
                ),
        )
        for (p in metadata.privateDims) {
            runCatching { store.declareUserVar(p.spec, p.functionKey) }
        }
        if (effective.bool(ConfigKey.UseERD)) UserKeywordTables.install(store, gameRoot)
        return store
    }

    /** Runtime 装配：存档接线 + [ProcessRuntime] + sidecar 表安装（原 137-173 行搬移）。 */
    private fun buildRuntime(
        program: Program,
        asm: ProbeAssembly,
        inputSink: QueueInputSink,
    ): ProcessRuntime {
        val gameRoot = asm.gameRoot
        val metadata = asm.metadata
        val store = asm.store
        val console = asm.console
        val effective = asm.effective
        val saveRoot = Path.of("build", "qol-patch-save").toAbsolutePath().normalize()
        runCatching { saveRoot.toFile().deleteRecursively() }
        Files.createDirectories(saveRoot)
        val saveRooted = RootedBuiltinHost(saveRoot)
        val saveConfig = SaveConfig(
            systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
            zipSaveData = effective.bool(ConfigKey.ZipSaveData),
            useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
        )
        SaveWiring.install(
            store,
            saveConfig,
            effective.saveDataNos,
            EmSaveSets.parse(gameRoot.resolve("CSV")),
        )
        val rt = ProcessRuntime(
            program,
            store,
            com.eratw.port.core.proc.FunctionTable(metadata.functions),
            VmConfig(maxStepsPerRun = 300_000),
            consoleSink = console,
            semConfig = SemConfig(printCPerLine = 5, saveDataNos = effective.saveDataNos),
            systemOps = FileSystemOps(store, RootedSaveFileIo(saveRoot, saveRooted.fileOps), saveConfig),
            builtinHost = ConsoleBuiltinHost(gameRoot, console),
            randSeed = 20261112L,
            beginHost = ConsoleBeginHost(inputSink, console),
            inputSink = inputSink,
        )
        val sidecar = SidecarTables(metadata.labels, metadata.dataSections)
        rt.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                sidecar.labelPc(functionKey.uppercase(), label.uppercase())
        })
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> sidecar.groupsOf(index) })
        if (!program.flatStatementsEnabled) {
            rt.installDataSectionStatementIndex(sidecar.dataSectionStatementIndex(program))
        }
        return rt
    }

    /**
     * 喂食线程（原 180-237 行搬移）：每个 INPUT 等待点抓一次快照入 [snaps]，
     * 再按 [Scenario.scriptText] 队列喂下一个值（空队列喂 0）。任何异常都是线程内收场
     * —— 打一行 stderr 留证后退出，绝不向上抛（探针主流程靠 [ProcessRuntime]
     * 的异常穿透报告失败，feeder 的死活不掩盖主流程结论）。
     */
    private fun startFeeder(
        inputSink: QueueInputSink,
        console: LineBufferConsoleSink,
        snaps: java.util.concurrent.ConcurrentLinkedQueue<String>,
        script: java.util.ArrayDeque<Long>,
        scenario: Scenario,
    ): Thread {
        var step = 0
        return Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (inputSink.isAwaiting && inputSink.pendingCount == 0) {
                        val req = inputSink.awaiting
                        val next = if (script.isEmpty()) 0L else script.pollFirst()!!
                        if (step == scenario.resizeStep && scenario.resizeWidth > 0) {
                            console.resize(scenario.resizeWidth)
                        }
                        val snap = console.snapshot()
                        val header = "### INPUT #${step++}  feed=$next " +
                            "LINECOUNT=${console.lineCount} rows=${snap.size}"
                        snaps.add(formatSnapshot(header, snap))
                        val text = when {
                            req == null || inputSink.awaitingAnyKey -> ""
                            req.type == com.eratw.port.core.host.InputRequest.InputType.INT -> next.toString()
                            else -> next.toString()
                        }
                        inputSink.submit(text)
                    }
                    Thread.sleep(5L)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    System.err.println("[qol-probe] feeder 异常退出：$t")
                    return@Thread
                }
            }
        }, "qol-patch-probe-feeder").apply { isDaemon = true }
    }

    /** 快照格式化（原 189-221 行搬移）：整屏行 + 每行 div 覆盖块几何。 */
    private fun formatSnapshot(
        header: String,
        snap: List<LineBufferConsoleSink.DisplayLine>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine(header)
        snap.forEachIndexed { i, l ->
            val text = l.segments.joinToString("") { it.text }
            sb.appendLine(
                "  [%3d] log=%s end=%s | %s".format(
                    i,
                    if (l.isLogicalLine) "Y" else "n",
                    if (l.isLineEnd) "Y" else "n",
                    text.replace("\t", "\\t").take(110),
                ),
            )
            l.divBlocks.forEachIndexed { k, b ->
                val shape = "div#$k tag=%s anchorCol=%d xPx=%d yPx=%d w=%d h=%d " +
                    "divX=%d depth=%d esc=%s rel=%s range=%s seg=%d"
                sb.appendLine(
                    "        " + shape.format(
                        b.altHeadTag.take(96),
                        b.anchorCol,
                        b.anchorPointXPx,
                        b.pointYPx,
                        b.widthPx,
                        b.heightPx,
                        b.divXOffsetPx,
                        b.depth,
                        b.isEscaped,
                        b.isRelative,
                        b.sourceRange,
                        b.sourceSegment,
                    ),
                )
            }
        }
        return sb.toString()
    }

    /** 报告落盘（原 253-265 行搬移）：`build/qol-patch-probe-<tag>.md`。 */
    private fun writeReport(
        scenario: Scenario,
        corpus: RealCorpus?,
        console: LineBufferConsoleSink,
        error: Throwable?,
        snaps: java.util.concurrent.ConcurrentLinkedQueue<String>,
    ) {
        Files.createDirectories(Path.of("build"))
        val report = buildString {
            appendLine("# QOL 天堂之门 → 补丁页 探针")
            appendLine()
            appendLine("- 产物: ${scenario.erbcDir}")
            appendLine("- 语料: $corpus")
            appendLine(
                "- 场景: script=${scenario.scriptText} reflow=${scenario.reflow} " +
                    "resize=${scenario.resizeStep}:${scenario.resizeWidth} width=${console.clientWidth}",
            )
            appendLine("- 收口: ${error?.let { "异常 ${it::class.simpleName}: ${it.message}" } ?: "正常返回"}")
            appendLine()
            snaps.forEach { appendLine(it) }
        }
        Files.writeString(Path.of("build", "qol-patch-probe-${scenario.tag}.md"), report)
        println("[qol-probe] wrote build/qol-patch-probe-${scenario.tag}.md (${snaps.size} snapshots)")
    }
}
