package com.eratw.port.vmdbg

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopTimerSink
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.save.SaveFolderIo
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.sql.SqlDriver
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** 纳秒 → 毫秒的整数除（装配期分段计时用；命名常量避免裸魔数）。 */
private const val NS_PER_MS = 1_000_000L

/**
 * 会话装配（CONTRACT-DBG1 路2；所有子命令共用）。
 *
 * 装配链 = `ProgramLoader.loadMapped(program.erbc)`（ProgramLoader.kt:425）+
 * `BootMetadata.read(program.meta)`（BootMetadata.kt:273）+ `GameSession(...)`
 * （GameSession.kt:135）。`GameSession` 装配后不保留 metadata（GameSession.kt:107 的
 * 「侧车生命周期」），vmdbg 因此自己持有 [Assembled.metadata]，`sweep` 拿
 * `metadata.functions` 当清扫清单。
 *
 * ## 钉死的装配参数
 *
 * - `randSeed` = [Cli.RAND_SEED]（20260226，CONTRACT-DBG1 路2 钉死；与 GameBootSmoke.kt:91
 *   的装置常量同值）——确定性对拍需要会话可复现。
 * - `maxStepsPerRun` = `--steps N`（call/trace 缺省 20,000,000、sweep 缺省 2,000,000，
 *   见 [Cli]；call/trace 与产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN 同口径）。
 * - `maxRunMillis` = `--step-wall-ms N`（缺省 0 = 关闭，逐字保持既有行为）：**单段落墙钟闸**，
 *   段落 = 相邻两次让出点（输入等待返回 / `AWAIT`）之间，口径与 `--steps` 的段落计数同源
 *   （Vm.kt:41 的 [com.eratw.port.core.code.VmConfig.maxRunMillis]，检查点每 65536 条指令一次）。
 *   它把「一次提交后长时间不回等待点」的纯计算挂起**中断在同一条指令上**，抛点的
 *   函数名/行号/pc/调用链随 [com.eratw.port.core.code.StepLimitExceeded] 原样带走
 *   （`elapsedMs >= 0` 即时间闸触发，与步数闸同异常不同判据）——这是 obs-midhang 定标
 *   「中局卡死肇事函数」的装置面；步数闸仍是引导与生产判据，两者互不顶替。
 * - `writeRoot` 保持 null（GameSession.kt:165 的缺省）⇒ SAVETEXT / OUTPUTLOG 拒写，
 *   与 sweep 规格「SAVETEXT 拒写」一致。
 * - `frameGateEnabled = false`：headless 工具没有帧节拍的读者，控制台变更立即发布，
 *   `call` 的「控制台尾 10 行」不必等帧闸放行。只影响发布时机，不改行内容。
 * - `systemOps` 显式传 [DeferredSandboxSystemOps]（存档沙箱，见其 KDoc）——
 *   **语料只读铁律的落地点，所有子命令共用，不得省**。
 * - `sqlDriver` = `DesktopSqliteHost.create(gameRoot, sandboxDir.resolve("sql"))`
 *   （CONTRACT-SQLITE-MIGRATION §3/§4 W4）：读根 = 游戏根（seed）、写根 = 沙箱的 `sql/` 子树，
 *   与存档沙箱同一条「语料只读」口径 —— 所有 SQLite 落盘只进临时目录，语料根一个字节都不动。
 *   路径解析 / 出根拒绝 / seed 首次拷贝由 sqlite-adapter 单点负责，本文件不重复判路径。
 * - `--sandbox <目录>`（可选）：复用**固定**沙箱而不是每次 `createTempDirectory`。
 *   生产 GameHost 的写根是持久 `gameRoot`，第二会话读回同一库；桌面调试要验证
 *   「首次建库 / 二次会话不重建」就必须有同一个持久写根。该目录必须在 `--work` 下，
 *   否则一次误传 `--sandbox <游戏根>` 就能把语料写脏。
 * - `--pre "FN1;FN2"` / `--addchara <no[,no]>` / `--sets "NAME[i,j]=V;..."`：
 *   调用/轨迹前的**真实状态与启动链注入**。`--pre` 走 `runtime.callFunction`（与 ERB
 *   `CALL` 同一条入口，SQL_CONNECT 连接不跨进程，真启动链必须同会话先跑）；
 *   `--addchara` 逐号走 [com.eratw.port.core.vars.VariableStore.addCharacter]（与 ERB
 *   `ADDCHARA` 语句同一条实现，StatementExecutor.kt:853）；`--sets` 走 `CmdSet` 的公开
 *   写单点。三者都只经现有引擎 API，不造测试专用状态通道。
 *
 * ## 参数来源
 *
 * `--erbc` / `--game` 优先；缺省读 `ERATW_ERBC_DIR` / `ERATW_GAMEDIR`（与
 * core-vm/build.gradle.kts:196-209 的 `erbcBootProfile` 旋钮同名同义）。
 *
 * ## 产品兼容装载档与堆预检（obs-compat512，两个**显式请求**开关）
 *
 * - `--compat-load`（布尔）：把装载改成 `ProgramLoader.loadMapped(path, compat = true)`
 *   （解码期去重/校验串行、不起 `erbc-intern-seed` 并行线程），并照产品侧
 *   （GameHost.kt:2149-2152 的 `trace.forceGc("decode")`、LoadTrace.kt:128-131 的
 *   无条件全量 GC 口径）在**每个解码分段 mark 上收一次全量垃圾**；侧车交界与会话交界
 *   （产品同款 `forceGc("meta")` / `forceGc("session")`，GameHost.kt:2198/:2208）同样收。
 *   不传 = 与改前逐字相同（单参 `loadMapped(path)`、无 mark、无额外 GC）。
 * - `--load-precheck`（布尔）：**装载之前**打一行 `ProgramLoader.estimateHeap` 读数
 *   （`requiredBytes/requiredMiB/maxMemoryBytes/maxMemoryMiB/sufficient` + 产物/侧车/指令数）。
 *   这行同时是「挡位真的生效」的自证：`--xmx 512m` 时 `maxMemoryBytes=536870912`。
 *   [out] 为 null 时写 `System.out`（两个开关都是一次性的诊断请求，不改变常规子命令输出）。
 *
 * 两个开关缺省关；关着的时候本文件的输出顺序、参数与被拉起的进程行逐字不变。
 */
object Assembler {

    /** 装配结果：会话 + vmdbg 自留的侧车（清扫清单）+ 沙箱目录与输入 sink 句柄。 */
    class Assembled(
        val session: GameSession,
        val program: Program,
        val metadata: BootMetadata,
        val inputSink: ImmediateInputSink,
        val sandboxDir: Path,
    )

    /**
     * 路径上下文（`assemble` 与 `sql` 共用；`sql` 只读库、不需要装 50 MB 产物）。
     *
     * [sandboxDir] 是存档/内存库的写根；[sqlDir] 是其中 `sql/` 子树（SQLite 写根）。
     */
    class Context(val gameRoot: Path, val workDir: Path, val sandboxDir: Path) {
        val sqlDir: Path get() = sandboxDir.resolve("sql")
    }

    /**
     * 解析 `--game` / `--work` / `--sandbox` 并把沙箱钉在 `--work` 内。
     *
     * `--sandbox` 缺省 = `<work>/save-sandbox-<随机>`（每次进程独立）；显式给定时复用同一目录，
     * 用于验证「第二次会话读回同一库」。出 `--work` 根一律拒 —— 只读语料铁律不能靠调用方自觉。
     */
    fun resolvePaths(a: Args): Context {
        val gameRoot = resolveGameRoot(a)
        val workDir = ensureWorkDir(a)
        return Context(gameRoot, workDir, resolveSandbox(a, workDir))
    }

    /** `--game` / `ERATW_GAMEDIR` → 存在的游戏根。 */
    private fun resolveGameRoot(a: Args): Path {
        val raw = a.value("--game")
            ?: System.getenv("ERATW_GAMEDIR")
            ?: throw CliError("缺 --game <游戏根>，且环境变量 ERATW_GAMEDIR 未设置")
        val root = Path.of(raw).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) throw CliError("--game 不是目录: $root")
        return root
    }

    /** `--work`（缺省系统临时目录）→ 已建好的状态目录。 */
    private fun ensureWorkDir(a: Args): Path {
        val dir = Path.of(a.valueOr("--work", defaultWorkDir())).toAbsolutePath().normalize()
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            throw CliError("状态目录建不出来: $dir（$e）", e)
        }
        return dir
    }

    /** `--sandbox` 显式复用 / 缺省临时沙箱；显式路径必须落在 [workDir] 内。 */
    private fun resolveSandbox(a: Args, workDir: Path): Path {
        val raw = a.value("--sandbox") ?: return createTempSandbox(workDir)
        val sandbox = Path.of(raw).toAbsolutePath().normalize()
        if (sandbox == workDir || !sandbox.startsWith(workDir)) {
            throw CliError("--sandbox 必须是 --work 目录下的子目录: $sandbox（work=$workDir）")
        }
        return ensureSandboxDir(sandbox)
    }

    private fun createTempSandbox(workDir: Path): Path = try {
        Files.createTempDirectory(workDir, "save-sandbox-")
    } catch (e: Exception) {
        throw CliError("存档沙箱目录建不出来: $workDir（$e）", e)
    }

    private fun ensureSandboxDir(sandbox: Path): Path {
        try {
            Files.createDirectories(sandbox)
        } catch (e: Exception) {
            throw CliError("存档沙箱目录建不出来: $sandbox（$e）", e)
        }
        return sandbox
    }

    /**
     * 装配会话。任何文件层失败折成 [CliError]（退出码 1，不带堆栈）。
     *
     * 存档沙箱目录 = `<work>/save-sandbox-<进程毫秒>-<随机>`：每个进程一个，sweep 与
     * call 各自独立，互不串写。`--work` 目录同时承载 last-error.json（`where` 的数据源），
     * 缺省 `<系统临时目录>/eratw-vmdbg`。
     *
     * @param sinkOverride explore（CONTRACT-DBG6 E1）注入的自定义输入宿主；null（缺省）=
     *   既有行为零变化（[ImmediateInputSink]）。注入时语句输入走 override，[Assembled.inputSink]
     *   仍是缺省 sink 实例（既有消费者类型不变）——AWAIT 的墙钟兜底（[WallTimerSink]）接的
     *   就是缺省实例，explore 把同一 [WallBudget] 挂到两处（见 ExploreDriver.runEpisode）。
     * @param out `--load-precheck` 那行的落点（子命令的 stdout）；null = 写 `System.out`。
     *   只有 `--load-precheck` 打开时才会写，其余路径一个字节都不多。
     * @param observers 只观测回调组（T1 探针用）：装载分段 / SQL 驱动包装 / 装配分段 /
     *   会话构造内部分段。四项缺省全 null ⇒ 与改前逐字相同（不挂回调、不包驱动、不回调）。
     *   观察者不得在回调里改状态。
     */
    fun assemble(
        a: Args,
        sinkOverride: InputSink? = null,
        out: PrintStream? = null,
        observers: Observers = Observers(),
    ): Assembled {
        val erbcDir = Wiring.resolveErbcDir(a)
        val (programPath, metaPath) = Wiring.artifactFiles(erbcDir)
        val ctx = resolvePaths(a)
        val gameRoot = ctx.gameRoot
        val sandboxDir = ctx.sandboxDir

        // 产品兼容装载档 + 堆预检（obs-compat512）：两个开关缺省关，关着时下面每一行与改前逐字相同。
        val compatLoad = a.flag("--compat-load")
        val loadPrecheck = a.flag("--load-precheck")
        if (loadPrecheck) Wiring.printPrecheck(out, programPath, metaPath, compatLoad)
        // 兼容档 mark（逢段 GC）与观察者 mark 合并；两者都缺 = null（不挂任何回调）。
        val mark = mergeLoadMark(compatLoad, observers.loadMark)
        val tLoad = System.nanoTime()
        val program = Wiring.loadProgram(programPath, compatLoad, loadPrecheck, mark)
        emitPhase(observers.phaseMark, "loadMapped.total", tLoad)
        // 侧车交界（产品同款 forceGc("meta") 口径，GameHost.kt:2198）：49 MB 文本解析前收干净。
        if (compatLoad) Runtime.getRuntime().gc()
        val tMeta = System.nanoTime()
        val metadata = Wiring.readMetadata(metaPath)
        emitPhase(observers.phaseMark, "BootMetadata.read", tMeta)

        // 存档沙箱接线：DeferredSandboxSystemOps 在首次存档语句执行时才建真 FileSystemOps
        // （要引用会话的 store/saveConfig，而两者都在 GameSession 构造期才存在，
        // GameSession.kt:291-294/:536），构造期只把沙箱根与游戏根交给它。
        val sink = ImmediateInputSink()
        val effectiveSink: InputSink = sinkOverride ?: sink
        var session: GameSession? = null
        val systemOps = DeferredSandboxSystemOps(gameRoot, sandboxDir) {
            requireNotNull(session) { "会话尚未装配完成（存档语句不可能在装配期被调用）" }
        }
        val limits = Wiring.sessionLimits(a)
        val sqlDir = sandboxDir.resolve("sql")
        val tSqlDriver = System.nanoTime()
        val sqlDriver = Wiring.createSqlDriver(gameRoot, sqlDir, observers.sqlDriverWrapper)
        emitPhase(observers.phaseMark, "sqlDriver.create", tSqlDriver)
        // 会话交界（产品同款 forceGc("session") 口径，GameHost.kt:2208）：会话装配是本链
        // 第二大分配，兼容档在它前面把侧车解析的短命串收干净。
        if (compatLoad) Runtime.getRuntime().gc()
        val tSession = System.nanoTime()
        val s = Wiring.createSession(
            Wiring.SessionInputs(
                gameRoot = gameRoot,
                program = program,
                metadata = metadata,
                maxSteps = limits.maxSteps,
                stepWallMs = limits.stepWallMs,
                systemOps = systemOps,
                sink = effectiveSink,
                sessionStageMark = observers.sessionStageMark,
                sqlDriver = sqlDriver,
            ),
        )
        emitPhase(observers.phaseMark, "GameSession.assemble", tSession)
        session = s
        // AWAIT 族的墙钟兜底：AWAIT 是让出边界（重置步数闸，StatementExecutor.kt:1022）、
        // 又不经输入 sink —— 不挂这里，`AWAIT` 空转循环就是第三类无界点。call/trace 不装
        // WallBudget（sink.budget 为 null ⇒ 全部空操作），行为与缺省 NoopTimerSink 等价。
        s.runtime.execContext.timer = WallTimerSink(sink)
        return Assembled(s, program, metadata, sink, sandboxDir)
    }

    /** 只观测回调组（T1 探针用）：四项缺省全 null ⇒ 与改前逐字相同（不挂回调、不包驱动）。 */
    data class Observers(
        val loadMark: LoadStageMark? = null,
        val sqlDriverWrapper: ((SqlDriver) -> SqlDriver)? = null,
        val phaseMark: ((String, Long) -> Unit)? = null,
        val sessionStageMark: ((String) -> Unit)? = null,
    )

    private fun emitPhase(mark: ((String, Long) -> Unit)?, name: String, startNs: Long) {
        if (mark != null) mark(name, (System.nanoTime() - startNs) / NS_PER_MS)
    }

    /** 兼容档的 mark（产品侧逢段必收，GameHost.kt:2149-2152）与观察者 mark 合并；都缺 = null。 */
    private fun mergeLoadMark(compatLoad: Boolean, loadMark: LoadStageMark?): LoadStageMark? {
        val compatMark: LoadStageMark? = if (compatLoad) LoadStageMark { Runtime.getRuntime().gc() } else null
        return when {
            compatMark == null -> loadMark
            loadMark == null -> compatMark
            else -> LoadStageMark { stage ->
                compatMark.onStage(stage)
                loadMark.onStage(stage)
            }
        }
    }

    /**
     * 装配链的纯函数 helper（装载 / 侧车 / 驱动 / 会话四段的失败折损与构造）。
     * 单独成对象只为把 [Assembler] 的成员函数数留在 detekt 阈值内；无状态，全部纯转发。
     */
    private object Wiring {

    /** `--erbc` / `ERATW_ERBC_DIR` → 存在的 erbc.v1 目录。 */
    fun resolveErbcDir(a: Args): Path {
        val raw = a.value("--erbc")
            ?: System.getenv("ERATW_ERBC_DIR")
            ?: throw CliError("缺 --erbc <erbc.v1 目录>，且环境变量 ERATW_ERBC_DIR 未设置")
        val dir = Path.of(raw).toAbsolutePath().normalize()
        if (!Files.isDirectory(dir)) throw CliError("--erbc 不是目录: $dir")
        return dir
    }

    /** erbc.v1 目录下的产物 / 侧车文件（正式产物必须带 program.meta）。 */
    fun artifactFiles(erbcDir: Path): Pair<Path, Path> {
        val programPath = erbcDir.resolve("program.erbc")
        val metaPath = erbcDir.resolve("program.meta")
        if (!Files.isRegularFile(programPath)) throw CliError("产物缺失: $programPath")
        if (!Files.isRegularFile(metaPath)) {
            throw CliError("侧车缺失: $metaPath（正式产物必须带 program.meta）")
        }
        return programPath to metaPath
    }

    /** `--load-precheck`：装载前打一行堆预检（只读段表 + 侧车大小，产物正文一个字节都不进堆）。 */
    fun printPrecheck(out: PrintStream?, programPath: Path, metaPath: Path, compatLoad: Boolean) {
        // 与宿主同一条判据（GameHost.kt:1816 的 ProgramLoader.estimateHeap）；
        // 读数前不清垃圾 —— 这里的「上限」是硬阈值（maxMemory），余量见 maxMemoryBytes - used。
        val est = runCatching {
            ProgramLoader.estimateHeap(programPath, metaPath)
        }.getOrElse { e ->
            throw precheckFailure(programPath, e)
        }
        val sinkOut = out ?: System.out
        // 一行 key=value：既能 grep，也自证挡位（--xmx 512m 时 maxMemoryBytes=536870912）。
        sinkOut.println(
            "loadPrecheck: requiredBytes=" + est.requiredBytes +
                " requiredMiB=" + est.requiredMiB +
                " maxMemoryBytes=" + est.maxMemoryBytes +
                " maxMemoryMiB=" + est.maxMemoryMiB +
                " sufficient=" + est.sufficient +
                " artifactBytes=" + est.artifactBytes +
                " metaBytes=" + est.metaBytes +
                " instructions=" + est.instructionCount +
                " compat=" + compatLoad,
        )
        sinkOut.flush()
    }

    /** 预检的异常折成 CliError（堆 OOM 有独立文案；非 Throwable 边界的错误原样穿出）。 */
    private fun precheckFailure(programPath: Path, e: Throwable): Throwable = when {
        e is OutOfMemoryError -> CliError("堆预检失败（堆 OOM）: ${e.message}", e)
        e is Exception -> CliError("堆预检失败: $programPath（${e.message}）", e)
        else -> e
    }

    /** 兼容档的 mark 与观察者 mark 的合并在 [Assembler.mergeLoadMark]（本对象保持 ≤10 个函数）。 */

    /** 产物装载：缺省档让 OOM 穿出去（与改前逐字相同），开了观测开关才折成可读的装载层失败。 */
    fun loadProgram(
        programPath: Path,
        compatLoad: Boolean,
        loadPrecheck: Boolean,
        mark: LoadStageMark?,
    ): Program = runCatching {
        ProgramLoader.loadMapped(programPath, compat = compatLoad, mark = mark)
    }.getOrElse { e ->
        if (e is Exception || e is OutOfMemoryError) throw toLoadError(programPath, e, compatLoad, loadPrecheck)
        throw e
    }

    private fun toLoadError(
        programPath: Path,
        e: Throwable,
        compatLoad: Boolean,
        loadPrecheck: Boolean,
    ): Throwable = when {
        e !is OutOfMemoryError -> CliError("产物装载失败: $programPath（${e.message}）", e)
        !compatLoad && !loadPrecheck -> e
        else -> CliError("产物装载失败（堆 OOM，compat=$compatLoad precheck=$loadPrecheck）: ${e.message}", e)
    }

    fun readMetadata(metaPath: Path): BootMetadata = runCatching {
        BootMetadata.read(metaPath)
    }.getOrElse { e ->
        if (e is Exception) throw CliError("侧车读取失败: $metaPath（${e.message}）", e)
        throw e
    }

    /**
     * SQLite 驱动接线（CONTRACT-SQLITE-MIGRATION §3/§4，W4）：必须在 GameSession 构造前建好，
     * 会话把同一个实例喂给内置宿主（GameSession.kt:953/:1374）。读根 = 游戏根（只读 seed，
     * [DesktopSqliteHost.create] 不复制 seed），写根 = 沙箱 `sql/`（A1 首次打开时从 seed 拷种
     * 到 target，之后只读写 target）；路径解析、出根拒绝与拷种全部由 sqlite-adapter 单点负责。
     */
    fun createSqlDriver(
        gameRoot: Path,
        sqlDir: Path,
        wrapper: ((SqlDriver) -> SqlDriver)?,
    ): SqlDriver = runCatching {
        val created = DesktopSqliteHost.create(gameRoot, sqlDir)
        wrapper?.invoke(created) ?: created
    }.getOrElse { e ->
        if (e is Exception) throw CliError("SQLite 驱动建不出来: $sqlDir（${e.message}）", e)
        throw e
    }

    /** 步数闸 / 单段落墙钟闸（`--step-wall-ms` 缺省 0 = 关闭，与既有子命令逐字同行为）。 */
    class Limits(val maxSteps: Long, val stepWallMs: Long)

    fun sessionLimits(a: Args): Limits {
        val steps = a.longOr("--steps", -1L)
        // 步数闸缺省按子命令分流：sweep 规格 = 2,000,000/函数；call/trace = 20,000,000
        //（与产品 GameSession.DEFAULT_MAX_STEPS_PER_RUN 同口径，GameSession.kt:718）。
        val defaultSteps =
            if (a.positional.firstOrNull() == "sweep") Cli.DEFAULT_SWEEP_STEPS else Cli.DEFAULT_CALL_STEPS
        val stepWallMs = a.longOr("--step-wall-ms", 0L)
        if (stepWallMs < 0) throw CliError("--step-wall-ms 不得为负: $stepWallMs")
        return Limits(if (steps > 0) steps else defaultSteps, stepWallMs)
    }

    data class SessionInputs(
        val gameRoot: Path,
        val program: Program,
        val metadata: BootMetadata,
        val maxSteps: Long,
        val stepWallMs: Long,
        val systemOps: SystemOps,
        val sink: InputSink,
        val sessionStageMark: ((String) -> Unit)?,
        val sqlDriver: SqlDriver,
    )

    /** 会话构造（观察者 stageMark 直通 GameSession；null = 构造路径逐字不变）。 */
    fun createSession(inp: SessionInputs): GameSession = runCatching {
        GameSession(
            gameRoot = inp.gameRoot,
            program = inp.program,
            metadata = inp.metadata,
            maxStepsPerRun = inp.maxSteps,
            maxRunMillis = inp.stepWallMs,
            randSeed = Cli.RAND_SEED,
            systemOps = inp.systemOps,
            frameGateEnabled = false,
            inputSinkOverride = inp.sink,
            stageMark = inp.sessionStageMark,
            sqlDriver = inp.sqlDriver,
        )
    }.getOrElse { e ->
        if (e is Exception) throw CliError("会话装配失败: ${e.message}", e)
        throw e
    }
    }

    /** `--work` 缺省：系统临时目录下的固定名（跨进程持久，`where` 才读得到 `call` 的现场）。 */
    private fun defaultWorkDir(): String =
        Path.of(System.getProperty("java.io.tmpdir"), "eratw-vmdbg").toString()
}

/**
 * 存档沙箱的 [SaveFileIo]（CONTRACT-DBG1 路2 sweep 规格）：
 *
 * - **写/删全部落沙箱根**（`<work>/save-sandbox-*`）：SAVEDATA / SAVEGLOBAL / SAVECHARA /
 *   旧档迁移产出的每个字节都进临时目录，游戏根一个字节都不动 —— 语料只读铁律的落地点；
 * - **读与存在性照旧走游戏根**（`exists`/`read`/`isReadOnly` 委托给根 = 游戏根的
 *   [RootedSaveFileIo]）：随游戏发行的全局档等只读数据照常可读。
 *
 * `SaveFolderIo`（旧档迁移的目录面，SaveFolderMigration.kt:16）整体指向**沙箱**：
 * 迁移会 move 文件，绝不允许落在游戏根。
 *
 * 与 GameSession.kt:536 的生产构造（`RootedSaveFileIo(gameRoot, rooted.fileOps)`）同款口径，
 * 仅把「读写同一根」拆成「读根 = 游戏根 / 写根 = 沙箱」。
 */
class SandboxedSaveFileIo(
    gameRoot: Path,
    sandboxRoot: Path,
    readOps: FileOps? = null,
) : SaveFileIo, SaveFolderIo {

    private val readSide = RootedSaveFileIo(gameRoot, readOps)
    private val writeSide = RootedSaveFileIo(sandboxRoot)

    override fun exists(path: String): Boolean = readSide.exists(path)

    override fun read(path: String): ByteArray? = readSide.read(path)

    override fun write(path: String, bytes: ByteArray): Boolean = writeSide.write(path, bytes)

    override fun delete(path: String): Boolean = writeSide.delete(path)

    override fun isReadOnly(path: String): Boolean = readSide.isReadOnly(path)

    override fun saveFolderExists(): Boolean = writeSide.saveFolderExists()

    override fun createSaveFolder(): Boolean = writeSide.createSaveFolder()

    override fun rootSaveFileNames(): List<String> = writeSide.rootSaveFileNames()

    override fun moveToFile(fromRel: String, toRel: String): Boolean = writeSide.moveToFile(fromRel, toRel)
}

/**
 * 存档沙箱的 [SystemOps]：首次调用时才用会话的真实 `store` / `saveConfig` 建
 * [FileSystemOps]（GameSession.kt:536 同款构造，仅 [SandboxedSaveFileIo] 换根），
 * 全部方法逐一直转发。
 *
 * 为什么延迟：`FileSystemOps` 构造要 `VariableStore` 与 `SaveConfig`，两者是
 * `GameSession` 构造期的产物（GameSession.kt:291-294/:342/:477），而 `systemOps`
 * 又必须**在**构造期传进 `GameSession` —— 唯一的次序解是「先装配、首次使用时再建」。
 * 首次使用必然发生在脚本执行期（存档语句被执行时），此时会话早已装配完。
 * `lazy` 线程安全（默认 synchronized），VM 单线程执行不会竞争。
 */
class DeferredSandboxSystemOps(
    gameRoot: Path,
    sandboxDir: Path,
    sessionRef: () -> GameSession,
) : SystemOps {

    private val io = SandboxedSaveFileIo(gameRoot, sandboxDir, RootedFileOps(gameRoot))

    private val delegate: FileSystemOps by lazy {
        val s = sessionRef()
        FileSystemOps(s.store, io, s.saveConfig)
    }

    override fun saveData(no: Int, text: String): Boolean = delegate.saveData(no, text)

    override fun loadData(no: Int) = delegate.loadData(no)

    override fun delData(no: Int) = delegate.delData(no)

    override fun saveGame() = delegate.saveGame()

    override fun loadGame() = delegate.loadGame()

    override fun saveGlobal() = delegate.saveGlobal()

    override fun loadGlobal(): Boolean = delegate.loadGlobal()

    override fun saveChara(nos: List<Long>, filename: String, text: String) =
        delegate.saveChara(nos, filename, text)

    override fun loadChara(filename: String) = delegate.loadChara(filename)

    override fun updateCheck(): Long = delegate.updateCheck()

    override fun callTrain(no: Long) = delegate.callTrain(no)

    override fun stopCallTrain() = delegate.stopCallTrain()

    override fun doTrain(no: Long) = delegate.doTrain(no)

    override fun upcheck() = delegate.upcheck()

    override fun cupcheck(target: Long) = delegate.cupcheck(target)
}

/**
 * sweep 逐函数挂起检测的墙钟预算：游戏函数可能在「立即应答 sink」下永远拿得到输入，
 * 若循环体每圈都带输入等待，[com.eratw.port.core.code.VmConfig.maxStepsPerRun] 的
 * 段落计数会在每个让出点清零（Vm.kt:8-15 的口径）而永不触发 —— 墙钟兜底。
 *
 * 检查点挂在输入 sink 的两个等待入口（`waitInput`/`readAnyKey`）：预算超限时抛
 * [WallLimitExceeded]，沿语句层异常路径原样上抛（TRYC 家族只捕 CodeEE，不会吞它），
 * sweep/call 捕获后折成 `WALL_LIMIT` 状态。纯 CPU 死循环不经过输入口，由步数闸兜住。
 */
/**
 * AWAIT 族的墙钟兜底：`AWAIT` 语句走 [TimerSink.delay]（StatementExecutor AWAIT 分支），
 * 是让出边界（步数闸在此重算）又不经输入 sink，[WallBudget] 只挂输入侧时这里是漏风面。
 * 委托 [sink.budget] 现读——sweep 逐函数换装预算，本类跟着走到当前函数的预算上；
 * budget 为 null（call/trace）时与缺省 [NoopTimerSink] 等价（纯透传）。
 */
private class WallTimerSink(private val sink: ImmediateInputSink) : TimerSink {
    override fun delay(millis: Long) {
        sink.budget?.check()
    }
}

class WallBudget(private val wallMs: Long) {

    private val startNs = System.nanoTime()

    /** 期间应答的输入次数（诊断用：`WALL_LIMIT` 的 message 里带上）。 */
    var inputRequests: Long = 0
        private set

    /** 超预算抛 [WallLimitExceeded]；`wallMs <= 0` = 关闭墙钟检查。 */
    fun check() {
        inputRequests++
        if (wallMs <= 0) return
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        if (elapsedMs > wallMs) throw WallLimitExceeded(wallMs, elapsedMs, inputRequests)
    }
}

/** 墙钟预算超限（非 CodeEE：不能被脚本 TRYC 捕获吞掉）。 */
class WallLimitExceeded(val wallMs: Long, val elapsedMs: Long, val requests: Long) :
    RuntimeException("vmdbg 函数挂起保护：墙钟 ${elapsedMs}ms 超过预算 ${wallMs}ms（期间应答 $requests 次输入）")

/**
 * 立即应答输入 sink（CONTRACT-DBG1 路2；口径 = GameBootSmoke.kt:98 的 ScriptedInputSink
 * 去掉顺序脚本）：INT 请求有默认取默认、无默认取 0；STR 请求取默认或空串；任意键立即放行。
 * 不用阻塞队列 —— sweep/call 的输入永远不能等人。
 *
 * [budget] 由 sweep 逐函数换装（见 [WallBudget]）；call/trace 不装（进程生命周期即预算）。
 */
class ImmediateInputSink : InputSink {

    /** 当前墙钟预算；null = 不检查（call/trace 单函数场景）。 */
    @Volatile
    var budget: WallBudget? = null

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        budget?.check()
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        budget?.check()
        return when (request.type) {
            InputRequest.InputType.INT ->
                if (request.hasDef) InputOutcome(intValue = request.defInt) else InputOutcome(intValue = 0L)
            InputRequest.InputType.STR ->
                InputOutcome(strValue = if (request.hasDef) request.defStr else "")
        }
    }
}

/**
 * 单次 run 的已执行步数读取：`ProcessRuntime.vm` 是 private（ProcessRuntime.kt:231），
 * 步数计数 `executedSteps` 在 VM 上（Vm.kt:316，run 开头清零 Vm.kt:387）——
 * 反射读，口径与 GameBootSmoke.kt:85/:1111 相同（该装置注明「单次 run 的已执行步数
 * 未公开，靠反射读 ProcessRuntime.vm.executedSteps」）。取不到（字段改名/JPM 装饰）返回
 * null，输出写 -1，不阻塞主流程。
 */
object StepReader {

    private val vmField = runCatching {
        ProcessRuntime::class.java.getDeclaredField("vm").apply { isAccessible = true }
    }.getOrNull()

    /** 最近一次 [ProcessRuntime] 的单 run 步数；null = 反射不可用。 */
    fun executedSteps(rt: ProcessRuntime): Long? {
        val f = vmField ?: return null
        val vm = runCatching { f.get(rt) as? VirtualMachine }.getOrNull() ?: return null
        return vm.executedSteps
    }
}
