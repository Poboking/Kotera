package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.save.SaveCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * **同一份 `.erbc` 产物、只换语句表示**的同进程交替 A/B：flat 语句池 vs 对象树。
 *
 * 与 `AssignArtifactAbTest` 的区别：那个比的是**两份不同产物**（PERF 融合开/关），本类在
 * **同一份产物**上用 [ProgramLoader.loadMapped] 的 `flatStatements` 参数分别装载两次 ——
 * 执行的指令流逐字节相同，唯一变量是 VM marker 走扁平流还是走 `Statement` 对象树。
 * 因此比值只归因于「表示」这一层，不混入编译期变换。
 *
 * ## 口径（判据只认同进程交替比值）
 *
 * - A = `flatStatements = true`（扁平语句池）、B = `false`（对象树）；两份 `Program` 各自
 *   建一个 [GameSession] 并读同一份 `save03.sav`、同一 `randSeed`。
 * - 预热 [warmupPairs] 对（缺省 3）后跑 [rounds] 对（缺省 12），每对内部 A/B 顺序按奇偶
 *   翻转，逐对算 `objectMs / flatMs`（**> 1 = flat 更快**），报 median/min/max。
 * - 逐对同时用 `ThreadMXBean.getThreadAllocatedBytes` 量线程分配，折算 bytes/call。
 * - 另报两个**诊断**比值（不参与判据）：线程 CPU 时间比（受时钟粒度限制，约 0.5 ms
 *   量化，只用来识别整段被别的进程抢占）与单次调用耗时的中位数比（一次 GC 长暂停只污染
 *   少数样本，不拖偏整轮）。正式判据仍是逐对整轮墙钟比。
 * - 指令数用 `Vm.globalStepCounter`（主 VM + 派生 VM 同一坐标系）。**两侧步数必须逐轮
 *   相等**，不等即说明两条装载路径不等价，直接失败 —— 此时任何比值都不可信。
 *
 * 跨窗口 / 跨进程 ns 与 ms 一律不作判据（本机有别的 agent 在跑 Gradle，频率与 GC 相位
 * 不可控）。
 *
 * ## 常驻内存口径（为什么分两趟）
 *
 * 两份 `Program` 同时驻留会让读数互相污染（尤其 flat 侧按需建片会把对象树那份一起算进
 * 高水位）。所以常驻单独一条用例，**同一 JVM 内一次只让一份 Program + GameSession 活着**：
 *
 * 1. 先 `HeapMeter.settle()` 取装载前基线（多轮 `System.gc()` 后取最小值 = 活物估计）；
 * 2. 装载一份、装配 `GameSession`、读档、预热若干次调用（让 flat 侧真的建出被走到的片）；
 * 3. 再 `HeapMeter.settle()` 取装载后读数；**装后 − 装前 = 该表示的常驻增量**；
 * 4. `session.close()` 释放映射，局部变量离开作用域后进入下一趟，下一趟的基线会把上一趟
 *    的垃圾清掉。
 *
 * 顺序取 flat → object → object → flat（ABBA），同一份代码在两种顺序下各出现一次，
 * 用来识别「后跑的一趟系统性偏高」这类漂移。读数单位 MiB，桌面 HotSpot/G1 口径，
 * **不是** ART 口径；与真机 `+114 MiB` 只能比方向与量级。
 *
 * 环境变量（缺一个就 `assumeTrue` 跳过）：
 * - `ERATW_GAMEDIR`：游戏根（CSV/#DIM 来源）；
 * - `ERATW_FLAT_AB_DIR`：产物目录（无缺省；公开仓不带本机产物路径）；目录下若有
 *   `erbc.v1/` 自动下钻。**产物必须由当前编译器重编**：`_LT_JZ/_GT_JZ` 已编号为 247/248，
 *   旧 251/252 产物会在装载期被 `ErbcDecodeException` 显式拒载（不会静默错读）；
 * - `fixtures/saves/save03.sav`（仓内已跟踪）。
 *
 * 旋钮：`ERATW_FLAT_AB_CALLS`（缺省 20）、`ERATW_FLAT_AB_ROUNDS`（缺省 12）、
 * `ERATW_FLAT_AB_WARMUP`（缺省 3）、`ERATW_FLAT_AB_TIMING=0` / `ERATW_FLAT_AB_RESIDENT=0`
 * 分别跳过两条用例（长调用复跑时用）。
 */
class FlatVsObjectAbTest {

    private data class PairSample(
        val index: Int,
        val flatNs: Long,
        val objectNs: Long,
        val flatCpuNs: Long,
        val objectCpuNs: Long,
        val flatCallMedianNs: Long,
        val objectCallMedianNs: Long,
        val flatAllocBytes: Long,
        val objectAllocBytes: Long,
        val flatSteps: Long,
        val objectSteps: Long,
    ) {
        /** > 1 = flat 更快（整轮墙钟）。 */
        val ratio: Double get() = objectNs.toDouble() / flatNs.toDouble()

        /** 同一对换成线程 CPU 时间：剔除被别的 agent 抢占/调度噪声。 */
        val cpuRatio: Double get() = objectCpuNs.toDouble() / flatCpuNs.toDouble()

        /** 单次调用中位数之比：剔除偶发 GC 长暂停。 */
        val callMedianRatio: Double get() = objectCallMedianNs.toDouble() / flatCallMedianNs.toDouble()
    }

    private data class ResidentPass(
        val label: String,
        val baselineBytes: Long,
        val residentBytes: Long,
        val steps: Long,
        val flatSegmentLimit: Int,
        val residentSegments: Int,
        val builtSegments: Int,
    ) {
        val deltaBytes: Long get() = residentBytes - baselineBytes
        fun deltaMiB(): Double = deltaBytes / 1_048_576.0
    }

    // ------------------------------------------------------------------
    // 环境 / 装载
    // ------------------------------------------------------------------

    private fun artifactDir(): Path? {
        val raw = System.getenv("ERATW_FLAT_AB_DIR")?.takeIf { it.isNotBlank() } ?: return null
        val root = Path.of(raw)
        val nested = root.resolve("erbc.v1")
        return when {
            Files.exists(nested.resolve("program.erbc")) -> nested
            Files.exists(root.resolve("program.erbc")) -> root
            else -> null
        }
    }

    private fun requireEnv(): Triple<Path, Path, Path>? {
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val dir = artifactDir()
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        return if (gameRoot != null && dir != null && Files.exists(save)) {
            Triple(gameRoot, dir, save)
        } else {
            null
        }
    }

    private fun load(dir: Path, flat: Boolean): Program =
        ProgramLoader.loadMapped(dir.resolve("program.erbc"), flatStatements = flat) { }

    private fun openSession(
        gameRoot: Path,
        dir: Path,
        save: Path,
        flat: Boolean,
        maxStepsPerRun: Long = 8_000_000L,
        applySave: Boolean = true,
    ): GameSession {
        val program = load(dir, flat)
        val metadata = BootMetadata.read(dir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = maxStepsPerRun,
            randSeed = 20260226L,
        )
        if (applySave) applySave(session, save)
        return session
    }

    private fun applySave(session: GameSession, save: Path) {
        val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
        data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
    }

    private fun hasFunction(program: Program, name: String): Boolean =
        program.functions.any { it.name.equals(name, ignoreCase = true) }

    /** 挂上跨派生 VM 的全局指令计数器；必须在第一次 `invokeUserMethod` 之前调用。 */
    private fun attachStepCounter(session: GameSession): LongArray {
        val counter = LongArray(1)
        val vm = vmOf(session.runtime) ?: error("拿不到 ProcessRuntime.vm，无法挂指令计数器")
        setVmField(vm, "globalStepCounter", counter)
        return counter
    }

    private fun vmOf(rt: ProcessRuntime): Any? = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        f.get(rt)
    }.getOrNull()

    private fun setVmField(vm: Any?, field: String, value: Any?) {
        val target = vm ?: return
        target.javaClass.getField(field).set(target, value)
    }

    // ------------------------------------------------------------------
    // 单轮
    // ------------------------------------------------------------------

    private data class RoundReading(
        val ns: Long,
        val cpuNs: Long,
        val callMedianNs: Long,
        val allocBytes: Long,
        val steps: Long,
    )

    private fun round(session: GameSession, counter: LongArray, calls: Int): RoundReading {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        counter[0] = 0L
        // 探针自己的 perCall 数组先分配，别把装置开销算进 bytes/call。
        val perCall = LongArray(calls)
        val alloc0 = mx.getThreadAllocatedBytes(tid)
        val cpu0 = mx.getCurrentThreadCpuTime()
        val t0 = System.nanoTime()
        var tPrev = t0
        repeat(calls) { i ->
            val ret = session.runtime.invokeUserMethod(
                "GMF_METHOD_UPDATE",
                listOf(Value.IntV(1L)),
            )
            check(ret != null) { "invokeUserMethod(GMF_METHOD_UPDATE) 返回 null（不是用户方法？）" }
            val now = System.nanoTime()
            perCall[i] = now - tPrev
            tPrev = now
        }
        val ns = System.nanoTime() - t0
        val cpuNs = mx.getCurrentThreadCpuTime() - cpu0
        val alloc = mx.getThreadAllocatedBytes(tid) - alloc0
        // 单次调用的中位数：一次 GC 暂停只会污染少数样本，不会把整轮拖偏。
        perCall.sort()
        val callMedian = if (calls % 2 == 1) perCall[calls / 2]
        else (perCall[calls / 2 - 1] + perCall[calls / 2]) / 2
        return RoundReading(
            ns = ns,
            cpuNs = cpuNs,
            callMedianNs = callMedian,
            allocBytes = alloc,
            steps = counter[0],
        )
    }

    // ------------------------------------------------------------------
    // 用例 1：同进程交替计时 A/B
    // ------------------------------------------------------------------

    @Tag("realArtifacts")
    @Test
    fun `flat 语句池 vs 对象树 同产物交替 A_B`() {
        assumeTrue(System.getenv("ERATW_FLAT_AB_TIMING") != "0", "ERATW_FLAT_AB_TIMING=0，跳过计时用例")
        val env = requireEnv()
        assumeTrue(env != null, "缺 ERATW_GAMEDIR / 产物目录 / save03.sav，跳过")
        val (gameRoot, dir, save) = env!!

        val rounds = System.getenv("ERATW_FLAT_AB_ROUNDS")?.toIntOrNull() ?: 12
        val calls = System.getenv("ERATW_FLAT_AB_CALLS")?.toIntOrNull() ?: 20
        val warmupPairs = System.getenv("ERATW_FLAT_AB_WARMUP")?.toIntOrNull() ?: 3
        println(
            "FLAT_VS_OBJECT dir=$dir rounds=$rounds calls=$calls warmupPairs=$warmupPairs " +
                "jvm=${HeapMeter.jvmName()} heapMaxMiB=${HeapMeter.max() / 1_048_576}",
        )

        val flat = openSession(gameRoot, dir, save, flat = true)
        val objectTree = openSession(gameRoot, dir, save, flat = false)
        val flatCounter = attachStepCounter(flat)
        val objectCounter = attachStepCounter(objectTree)
        try {
            repeat(warmupPairs) {
                round(flat, flatCounter, calls)
                round(objectTree, objectCounter, calls)
            }

            val samples = ArrayList<PairSample>(rounds)
            repeat(rounds) { i ->
                // 每对内部顺序按奇偶翻转：偶数对 flat 先，奇数对 object 先。
                val flatFirst = i % 2 == 0
                val first = if (flatFirst) round(flat, flatCounter, calls)
                else round(objectTree, objectCounter, calls)
                val second = if (flatFirst) round(objectTree, objectCounter, calls)
                else round(flat, flatCounter, calls)
                val flatRead = if (flatFirst) first else second
                val objectRead = if (flatFirst) second else first

                val sample = PairSample(
                    index = i,
                    flatNs = flatRead.ns,
                    objectNs = objectRead.ns,
                    flatCpuNs = flatRead.cpuNs,
                    objectCpuNs = objectRead.cpuNs,
                    flatCallMedianNs = flatRead.callMedianNs,
                    objectCallMedianNs = objectRead.callMedianNs,
                    flatAllocBytes = flatRead.allocBytes,
                    objectAllocBytes = objectRead.allocBytes,
                    flatSteps = flatRead.steps,
                    objectSteps = objectRead.steps,
                )
                samples += sample
                println(
                    "FLAT_VS_OBJECT pair=$i order=${if (flatFirst) "flat→object" else "object→flat"} " +
                        "flatMs=${"%.3f".format(sample.flatNs / 1_000_000.0)} " +
                        "objectMs=${"%.3f".format(sample.objectNs / 1_000_000.0)} " +
                        "object/flat=${"%.3f".format(sample.ratio)}× " +
                        "cpu=${"%.3f".format(sample.cpuRatio)}× " +
                        "callMed=${"%.3f".format(sample.callMedianRatio)}× " +
                        "flatB/call=${"%.1f".format(sample.flatAllocBytes.toDouble() / calls)} " +
                        "objectB/call=${"%.1f".format(sample.objectAllocBytes.toDouble() / calls)} " +
                        "steps=${sample.flatSteps}/${sample.objectSteps}",
                )
            }

            val stepMismatches = samples.filter { it.flatSteps != it.objectSteps }
            check(stepMismatches.isEmpty()) {
                "装载路径不等价：${stepMismatches.size}/$rounds 对 flat/object 指令数不同 —— " +
                    stepMismatches.joinToString("; ") {
                        "pair=${it.index} flat=${it.flatSteps} object=${it.objectSteps}"
                    } +
                    "。比值不可信，先停下报告。"
            }

            val ratios = samples.map { it.ratio }.sorted()
            val cpuRatios = samples.map { it.cpuRatio }.sorted()
            val callMedianRatios = samples.map { it.callMedianRatio }.sorted()
            val flatAllocPerCall = samples.map { it.flatAllocBytes.toDouble() / calls }.sorted()
            val objectAllocPerCall = samples.map { it.objectAllocBytes.toDouble() / calls }.sorted()
            val flatMs = samples.map { it.flatNs / 1_000_000.0 }.sorted()
            val objectMs = samples.map { it.objectNs / 1_000_000.0 }.sorted()
            println(
                "FLAT_VS_OBJECT SUMMARY n=$rounds calls=$calls " +
                    "object/flat median=${"%.3f".format(median(ratios))}× " +
                    "min=${"%.3f".format(ratios.first())}× max=${"%.3f".format(ratios.last())}× | " +
                    "cpuRatio median=${"%.3f".format(median(cpuRatios))}× " +
                    "min=${"%.3f".format(cpuRatios.first())}× max=${"%.3f".format(cpuRatios.last())}× | " +
                    "callMedRatio median=${"%.3f".format(median(callMedianRatios))}× " +
                    "min=${"%.3f".format(callMedianRatios.first())}× max=${"%.3f".format(callMedianRatios.last())}× | " +
                    "flatMs median=${"%.3f".format(median(flatMs))} range=${"%.3f".format(flatMs.first())}..${"%.3f".format(flatMs.last())} | " +
                    "objectMs median=${"%.3f".format(median(objectMs))} range=${"%.3f".format(objectMs.first())}..${"%.3f".format(objectMs.last())} | " +
                    "flatB/call median=${"%.1f".format(median(flatAllocPerCall))} | " +
                    "objectB/call median=${"%.1f".format(median(objectAllocPerCall))} | " +
                    "steps/round=${samples.first().flatSteps}（两侧逐对相等）",
            )
        } finally {
            runCatching { flat.close() }
            runCatching { objectTree.close() }
        }
    }

    // ------------------------------------------------------------------
    // 用例 2：常驻内存分趟（一次只活一份 Program）
    // ------------------------------------------------------------------

    @Tag("realArtifacts")
    @Test
    fun `flat vs 对象树 常驻内存分趟`() {
        assumeTrue(System.getenv("ERATW_FLAT_AB_RESIDENT") != "0", "ERATW_FLAT_AB_RESIDENT=0，跳过常驻用例")
        val env = requireEnv()
        assumeTrue(env != null, "缺 ERATW_GAMEDIR / 产物目录 / save03.sav，跳过")
        val (gameRoot, dir, save) = env!!

        val warmCalls = System.getenv("ERATW_FLAT_AB_CALLS")?.toIntOrNull() ?: 20
        val warmPairs = System.getenv("ERATW_FLAT_AB_WARMUP")?.toIntOrNull() ?: 3
        println(
            "FLAT_VS_OBJECT_RESIDENT dir=$dir warmCalls=$warmCalls warmPairs=$warmPairs " +
                "gc=${HeapMeter.gcNames()} heapMaxMiB=${HeapMeter.max() / 1_048_576}",
        )

        // ABBA：同一份代码在两种顺序下各跑一次，识别「后跑一趟系统性偏高」的漂移。
        val order = listOf(true, false, false, true)
        val passes = ArrayList<ResidentPass>(order.size)
        for (flat in order) {
            passes += measureResidentPass(gameRoot, dir, save, flat, warmCalls * warmPairs)
        }

        for (p in passes) {
            println(
                "FLAT_VS_OBJECT_RESIDENT pass=${p.label} " +
                    "baselineMiB=${"%.1f".format(p.baselineBytes / 1_048_576.0)} " +
                    "residentMiB=${"%.1f".format(p.residentBytes / 1_048_576.0)} " +
                    "deltaMiB=${"%.1f".format(p.deltaMiB())} " +
                    "flatSegmentLimit=${p.flatSegmentLimit} " +
                    "flatSegments=${p.residentSegments}/${p.builtSegments}(resident/built) steps=${p.steps}",
            )
        }
        val flatPasses = passes.filter { it.label == "flat" }
        val objectPasses = passes.filter { it.label == "object" }
        val flatMedian = median(flatPasses.map { it.deltaMiB() }.sorted())
        val objectMedian = median(objectPasses.map { it.deltaMiB() }.sorted())
        println(
            "FLAT_VS_OBJECT_RESIDENT SUMMARY " +
                "flatDeltaMiB median=${"%.1f".format(flatMedian)} " +
                "(runs=${flatPasses.joinToString(",") { "%.1f".format(it.deltaMiB()) }}) " +
                "objectDeltaMiB median=${"%.1f".format(objectMedian)} " +
                "(runs=${objectPasses.joinToString(",") { "%.1f".format(it.deltaMiB()) }}) " +
                "flat-object=${"%.1f".format(flatMedian - objectMedian)} MiB " +
                "（桌面 HotSpot/G1 口径；真机 ART 增量 +114 MiB 只比方向/量级）",
        )
    }

    /**
     * 一趟常驻读数：GC 基线 → 装载/装配/读档/预热 → GC 静置读数 → 关闭。
     *
     * 方法返回后 [GameSession] 与 [Program] 都成为不可达局部量，下一趟的基线 GC 会把它们
     * 收掉。**不要**在调用方持有本方法创建的 Program —— 那正是要避免的同时驻留。
     */
    private fun measureResidentPass(
        gameRoot: Path,
        dir: Path,
        save: Path,
        flat: Boolean,
        warmCalls: Int,
    ): ResidentPass {
        val baseline = HeapMeter.settle().first
        val session = openSession(gameRoot, dir, save, flat)
        try {
            val counter = attachStepCounter(session)
            repeat(warmCalls) {
                session.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L)))
            }
            val resident = HeapMeter.settle().first
            return ResidentPass(
                label = if (flat) "flat" else "object",
                baselineBytes = baseline,
                residentBytes = resident,
                steps = counter[0],
                flatSegmentLimit = session.program.flatSegmentLimit,
                residentSegments = session.program.flatResidentSegmentCount,
                builtSegments = session.program.flatBuiltSegmentCount,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // 用例 3：常驻内存分趟（CHARA_MOVEMENT 重载荷，可选）
    // ------------------------------------------------------------------

    /**
     * 用 `CHARA_MOVEMENT`（≈1,200 万条指令，口径同 `TurnLatencyProbeTest`）把工作集铺到
     * 几十上百个语句片上，再量常驻。目的：短载荷只走到少数片，flat/object 差值被压到
     * 1~2 MiB，无法回答「真机 +114 MiB 的量级是不是被桌面口径漏掉了」。
     *
     * 默认关；`ERATW_FLAT_AB_RESIDENT_CHARA=1` 打开。装配顺序对齐 `TurnLatencyProbeTest`：
     * `@QOL_DB_INIT` → 读档 → `@GMF_SETBIT("幻想乡大地图")` → `CHARA_MOVEMENT`。
     */
    @Tag("realArtifacts")
    @Test
    fun `flat vs 对象树 常驻内存分趟 CHARA重载荷`() {
        assumeTrue(
            System.getenv("ERATW_FLAT_AB_RESIDENT_CHARA") == "1",
            "ERATW_FLAT_AB_RESIDENT_CHARA != 1，跳过重载荷常驻用例",
        )
        val env = requireEnv()
        assumeTrue(env != null, "缺 ERATW_GAMEDIR / 产物目录 / save03.sav，跳过")
        val (gameRoot, dir, save) = env!!
        println("FLAT_VS_OBJECT_RESIDENT_CHARA dir=$dir heapMaxMiB=${HeapMeter.max() / 1_048_576}")

        val order = listOf(true, false, false, true)
        val passes = ArrayList<ResidentPass>(order.size)
        for (flat in order) {
            passes += measureCharaResidentPass(gameRoot, dir, save, flat)
        }
        for (p in passes) {
            println(
                "FLAT_VS_OBJECT_RESIDENT_CHARA pass=${p.label} " +
                    "baselineMiB=${"%.1f".format(p.baselineBytes / 1_048_576.0)} " +
                    "residentMiB=${"%.1f".format(p.residentBytes / 1_048_576.0)} " +
                    "deltaMiB=${"%.1f".format(p.deltaMiB())} " +
                    "flatSegmentLimit=${p.flatSegmentLimit} " +
                    "flatSegments=${p.residentSegments}/${p.builtSegments}(resident/built) steps=${p.steps}",
            )
        }
        val flatPasses = passes.filter { it.label == "flat" }
        val objectPasses = passes.filter { it.label == "object" }
        val flatMedian = median(flatPasses.map { it.deltaMiB() }.sorted())
        val objectMedian = median(objectPasses.map { it.deltaMiB() }.sorted())
        println(
            "FLAT_VS_OBJECT_RESIDENT_CHARA SUMMARY " +
                "flatDeltaMiB median=${"%.1f".format(flatMedian)} " +
                "(runs=${flatPasses.joinToString(",") { "%.1f".format(it.deltaMiB()) }}) " +
                "objectDeltaMiB median=${"%.1f".format(objectMedian)} " +
                "(runs=${objectPasses.joinToString(",") { "%.1f".format(it.deltaMiB()) }}) " +
                "flat-object=${"%.1f".format(flatMedian - objectMedian)} MiB",
        )
    }

    private fun measureCharaResidentPass(
        gameRoot: Path,
        dir: Path,
        save: Path,
        flat: Boolean,
    ): ResidentPass {
        val baseline = HeapMeter.settle().first
        val session = openSession(
            gameRoot, dir, save, flat,
            maxStepsPerRun = 12_500_000L,
            applySave = false,
        )
        try {
            val counter = attachStepCounter(session)
            if (hasFunction(session.program, "QOL_DB_INIT")) {
                session.runtime.callFunction("QOL_DB_INIT")
            }
            applySave(session, save)
            if (hasFunction(session.program, "GMF_SETBIT")) {
                session.runtime.callFunction("GMF_SETBIT", LongArray(0), arrayOf("幻想乡大地图"))
            }
            val error = try {
                session.runtime.callFunction("CHARA_MOVEMENT")
                null
            } catch (e: StepLimitExceeded) {
                "StepLimitExceeded(${e.steps})"
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
            if (error != null) println("FLAT_VS_OBJECT_RESIDENT_CHARA pass=${if (flat) "flat" else "object"} 异常=$error")
            val resident = HeapMeter.settle().first
            return ResidentPass(
                label = if (flat) "flat" else "object",
                baselineBytes = baseline,
                residentBytes = resident,
                steps = counter[0],
                flatSegmentLimit = session.program.flatSegmentLimit,
                residentSegments = session.program.flatResidentSegmentCount,
                builtSegments = session.program.flatBuiltSegmentCount,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun median(sorted: List<Double>): Double =
        if (sorted.isEmpty()) Double.NaN
        else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
}





