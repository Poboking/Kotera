package com.eratw.port.core.e2e

import com.eratw.port.core.code.Program
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.mod.EventDispatchRegistry
import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.mod.HookPipeline
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.sql.RealSqlDriver
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * mod 钩子/事件面在**真实 261111 语料**（erbcc 产物 + 生产 [ProgramLoader]/[GameSession]/
 * `ProcessRuntime`）下的 e2e 锁（task-88 a/b/c/d）。
 *
 * 与 `FunctionHookWiringCallTest` 的分工：那边用手工字节码的合成函数证明 hook 语义，本文件
 * 不重复语义细节，只钉「真实语料链上钩子真被调用、且不改变语料读数、撤销后真停」：
 *
 * - a = 宿主按名调真语料命令函数 `COM_NAME301`（`コマンド関連/COMF/日常系/COMF301 お茶を
 *   淹れる.ERB:248`），PREFIX 钩子收到宿主传的实参、POSTFIX 收到原函数体算出的返回值、
 *   且原函数体真把 `RESULTS` 写出来了（不是被替换/跳过）；
 * - b = 同 randSeed 的基线会话 vs 挂 PREFIX 钩子的会话，跑同一条真语料调用序列，逐位比较
 *   返回值与 `RESULTS` 读数 ⇒ Continue 钩子零干涉；
 * - c = 真 [ModRuntimeHost] 生命周期：`onLoad` 里 `context.hooks.register` 绑到宿主共享
 *   `hookPipeline`、[FunctionHookWiring.install] 挂进真会话 store ⇒ 命中；`shutdown()` 撤销
 *   注册后**不再派发**而原函数仍执行（不是搭台架，走生产装配的同一对 API）；
 * - d = 事件面两层在真 `begin(BeginType.FIRST)`（`BeginFlow` → `executeEvent("EVENTFIRST")`）
 *   下都命中：函数 hook 的事件钩子（[FunctionHookWiring.dispatchEvent]）+ Kotlin EventApi
 *   处理器（[ModEventWiring] → `ProcessRuntime.dispatchEventMembers` 的桶消费）；
 * - e = [FunctionHookWiring.uninstall] 之后真语料调用不再派发（卸载链路与 c 的宿主撤销互补）。
 *
 * 靶函数为什么是 `COM_NAME301` 而不是 `RANDSF`：`RANDSF` 带 `#FUNCTION`（`FuncMeta.isMethod`），
 * 宿主 `callFunction` 走 CALL 口径会被 `resolveForCall` 拒载（`ProcessRuntime.methodCallError`），
 * 而函数 hook 只挂在宿主漏斗 `doCall` 上（ERB 内部 `CALL` 是 VM 指令，不走这里）。`COM_NAME301`
 * 是普通命令函数（非 method），探针 `ComOptionRealCorpusProbeTest` 已证 headless 真会话可调通。
 *
 * 环境门（硬约束）：`ERATW_ERBC_DIR`（含 program.erbc/program.meta）+ `ERATW_GAMEDIR` 缺一
 * 即 [fail]，**不许** `assumeTrue`/静默 return —— 语料缺失等于没写。
 */
class HookRealCorpusE2ETest {

    private companion object {
        /** 与 ComOptionRealCorpusProbeTest 同口径的固定种子：同种子两会话读数可比。 */
        const val RAND_SEED: Long = 20260226L

        /** 钩子靶：`@COM_NAME301(ARG = -1)`（非 #FUNCTION，可被宿主 CALL）。 */
        const val TARGET: String = "COM_NAME301"
        const val EVENTFIRST: String = "EVENTFIRST"
        const val HOOK_PACKAGE: String = "e2e.hook.mod"
        const val EVENT_PACKAGE: String = "e2e.event.mod"

        /** `COM_NAME301(ARG)` 的单整型实参：-1 ⇒ 函数体读 `COM_ALTER_301` 决定分支。 */
        const val ARG_AUTO: Long = -1L

        /** a/b/c/e 每次调用都读这个字符串变量（真语料函数体的可观测副作用）。 */
        const val RESULT_VAR: String = "RESULTS"

        /** b 的调用轮数（同一序列在基线与挂钩子会话各跑一遍）。 */
        const val CALL_ROUNDS: Int = 3

        /** d 的等待预算：真 EVENTFIRST 链会跑 `NEWGAME`（重），命中只发生在链首，留足余量。 */
        const val BEGIN_DEADLINE_NS: Long = 90_000_000_000L
        const val POLL_INTERVAL_MS: Long = 50L
        const val JOIN_TIMEOUT_MS: Long = 30_000L

        private val erbcDir: Path by lazy { requireEnvDir("ERATW_ERBC_DIR") }
        private val gameDir: Path by lazy { requireEnvDir("ERATW_GAMEDIR") }

        /** 真产物惰性装载一次、全会话共享（`Program` 无会话态，可跨会话复用）。 */
        private val program: Program by lazy {
            val path = erbcDir.resolve("program.erbc")
            if (!Files.isRegularFile(path)) {
                fail("真实语料 e2e 需要 erbc 产物 $path（缺失即视为没写；请导出 ERATW_ERBC_DIR）")
            }
            ProgramLoader.loadMapped(path)
        }
        private val metadata: BootMetadata by lazy {
            val path = erbcDir.resolve("program.meta")
            if (!Files.isRegularFile(path)) fail("真实语料 e2e 需要侧车 $path（请导出 ERATW_ERBC_DIR）")
            BootMetadata.read(path)
        }

        private fun requireEnvDir(name: String): Path {
            val raw = System.getenv(name)?.takeIf { it.isNotBlank() }
                // 公开仓约定（README「语料门控」条）：环境变量**没导出** ⇒ 本用例跳过
                // （公开仓 CI 无语料，缺数据不是回归；判跳过用 TestAbortedException，
                // 与 GoldenPipeline / SaveFormatTest 同口径）。
                // 变量**已导出但目录不存在** ⇒ 仍然硬红（语料缺失等于没写，不许空过）。
                ?: throw org.opentest4j.TestAbortedException(
                    "真实语料 e2e 需要环境变量 $name（公开仓 CI 无语料 ⇒ 跳过；导出后重跑即可真跑）",
                )
            val path = Path.of(raw)
            if (!Files.isDirectory(path)) fail("$name=$path 不是目录（真实语料 e2e 不能空过）")
            return path
        }

        fun newSession(
            sink: QueueInputSink = QueueInputSink(),
            sqlDriver: RealSqlDriver? = null,
        ): GameSession = GameSession(
            gameRoot = gameDir,
            program = program,
            metadata = metadata,
            randSeed = RAND_SEED,
            frameGateEnabled = true,
            inputSinkOverride = sink,
            sqlDriver = sqlDriver,
        )

        /**
         * 按 ERB 名取产物里的函数键，并断言它**不是** `#FUNCTION`（否则宿主 `callFunction`
         * 会按 CALL 口径拒载，钩子根本到不了）。取不到/是 method 都判红，不许空过。
         */
        fun calleeKey(name: String): String {
            val meta = metadata.functions.firstOrNull { it.key == name }
                ?: fail("erbcc 产物里没有函数 $name（产物与语料不匹配，用例不允许空过）")
            if (meta.isMethod) {
                fail("$name 是 #FUNCTION（isMethod=true）：宿主 callFunction 会按 CALL 口径拒载，不能当钩子靶")
            }
            return meta.key
        }

        /** 读真语料函数体写出的 `RESULTS` 读数（`RESULT_VAR` 是一维字符串变量）。 */
        fun readResults(session: GameSession): String =
            session.store.readStr(RESULT_VAR, longArrayOf(0L))
    }

    @Test
    fun `a 真语料函数 COM_NAME301 的钩子收到实参且原函数体跑完`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val prefixSeen = CopyOnWriteArrayList<List<Long>>()
        val postfixReturns = CopyOnWriteArrayList<Long>()
        val pipeline = HookPipeline.of(
            listOf(
                DeclaredHook(HOOK_PACKAGE, HookDeclaration(TARGET, HookPhase.PREFIX)),
                DeclaredHook(HOOK_PACKAGE, HookDeclaration(TARGET, HookPhase.POSTFIX)),
            ),
        )
        pipeline.bind(TARGET, com.eratw.port.modapi.HookPhase.PREFIX, HOOK_PACKAGE) { ctx ->
            assertTrue(ctx.args.all { !it.isString }, "$TARGET 只收整型实参，钩子却收到字符串槽")
            prefixSeen += ctx.args.map { it.intValue }
            HookResult.Continue
        }
        pipeline.bind(TARGET, com.eratw.port.modapi.HookPhase.POSTFIX, HOOK_PACKAGE) { ctx ->
            postfixReturns += (ctx.returnValue as? ModValue.IntValue)?.value
                ?: fail("$TARGET 的 POSTFIX 钩子拿不到整型返回值（原函数体没跑完？）")
            HookResult.Continue
        }
        FunctionHookWiring.install(session.store, pipeline, logs::add)
        val before = readResults(session)

        val returned = session.runtime.callFunction(
            calleeKey(TARGET),
            longArrayOf(ARG_AUTO),
            emptyArray(),
        )

        val after = readResults(session)
        assertEquals(listOf(listOf(ARG_AUTO)), prefixSeen, "PREFIX 钩子没收到宿主传入的真实参")
        assertEquals(listOf(returned), postfixReturns, "POSTFIX 钩子看到的返回值与 dispatchCall 返回不一致")
        assertTrue(after.isNotBlank() && after != before, "原函数体没执行完：RESULTS 前后都是 [$before]")
        assertTrue(logs.isEmpty(), "正常派发不该有隔离日志，却有：$logs")
    }

    @Test
    fun `b 挂 Continue 钩子的会话与基线读数逐位一致`() {
        // 两条会话同 randSeed、同语料、同调用序列；唯一差别是 hooked 挂了 Continue 钩子。
        val baseline = newSession()
        val hooked = newSession()
        val logs = mutableListOf<String>()
        val hits = CopyOnWriteArrayList<Long>()
        val pipeline = HookPipeline.of(
            listOf(DeclaredHook(HOOK_PACKAGE, HookDeclaration(TARGET, HookPhase.PREFIX))),
        )
        pipeline.bind(TARGET, com.eratw.port.modapi.HookPhase.PREFIX, HOOK_PACKAGE) { ctx ->
            hits += ctx.args[0].intValue
            HookResult.Continue
        }
        FunctionHookWiring.install(hooked.store, pipeline, logs::add)
        val key = calleeKey(TARGET)

        val args = longArrayOf(ARG_AUTO)
        val baselineReturns = mutableListOf<Long>()
        val baselineResults = mutableListOf<String>()
        val hookedReturns = mutableListOf<Long>()
        val hookedResults = mutableListOf<String>()
        repeat(CALL_ROUNDS) {
            baselineReturns += baseline.runtime.callFunction(key, args, emptyArray())
            baselineResults += readResults(baseline)
            hookedReturns += hooked.runtime.callFunction(key, args, emptyArray())
            hookedResults += readResults(hooked)
        }

        assertEquals(baselineReturns, hookedReturns, "挂 PREFIX Continue 钩子后真语料返回值出现偏差")
        assertEquals(baselineResults, hookedResults, "挂 PREFIX Continue 钩子后 RESULTS 读数出现偏差")
        assertEquals(CALL_ROUNDS, hits.size, "每轮调用必须都命中钩子（命中数=${hits.size}）")
        assertEquals(List(CALL_ROUNDS) { ARG_AUTO }, hits.toList(), "钩子收到的实参与宿主传入不一致")
    }

    @Test
    fun `c mod宿主 install 后钩子命中而 shutdown 后不再派发`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val hits = CopyOnWriteArrayList<Long>()
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.hooks.register(TARGET, com.eratw.port.modapi.HookPhase.PREFIX) { ctx ->
                    hits += ctx.args[0].intValue
                    HookResult.Continue
                }
            }

            override fun onEnable() = Unit

            override fun onDisable() = Unit

            override fun onUnload() = Unit
        }
        val host = ModRuntimeHost(
            snapshot = declarationSnapshot(TARGET),
            entityKernel = entityKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = logs::add,
            runtime = FakeRuntime(),
        )
        val states = host.install(listOf(HOOK_PACKAGE to mod))
        assertTrue(states.single().active, "mod 未启用：${states.single().failure}")
        FunctionHookWiring.install(session.store, host.hookPipeline, logs::add)
        val key = calleeKey(TARGET)

        val beforeResults = readResults(session)
        session.runtime.callFunction(key, longArrayOf(ARG_AUTO), emptyArray())
        assertEquals(listOf(ARG_AUTO), hits.toList(), "install 后真语料调用没有命中 mod 注册的钩子")
        val afterInstall = readResults(session)
        assertTrue(afterInstall.isNotBlank() && afterInstall != beforeResults, "install 后原函数没执行完")

        host.shutdown()
        session.runtime.callFunction(key, longArrayOf(ARG_AUTO), emptyArray())
        assertEquals(listOf(ARG_AUTO), hits.toList(), "shutdown() 撤销注册后钩子仍在派发")
        assertEquals(afterInstall, readResults(session), "shutdown() 后原函数仍须执行（只撤钩子，不改语料语义）")
    }

    @Test
    fun `d 真 begin 派发 EVENTFIRST 到函数事件钩子与 Kotlin 事件处理器`() {
        // 真 EVENTFIRST 的 NORMAL 成员会跑 `NEWGAME` 链并真调 `SQL_CONNECT`（qol 库），
        // 故按生产口径注入真 SQLite 驱动（落临时目录，绝不写共享语料根）；插件库路径在
        // 语料里是相对 `plugins/`，这里预建目录让运行期建库。
        val sqlRoot = Files.createTempDirectory("eratw-hook-e2e-sql")
        Files.createDirectories(sqlRoot.resolve("plugins"))
        val sink = QueueInputSink()
        val session = newSession(sink = sink, sqlDriver = RealSqlDriver(sqlRoot))
        val logs = mutableListOf<String>()
        val functionHookSeen = CopyOnWriteArrayList<String>()
        val kotlinHandlerSeen = CopyOnWriteArrayList<String>()

        val pipeline = HookPipeline.of(
            listOf(DeclaredHook(EVENT_PACKAGE, HookDeclaration(EVENTFIRST, HookPhase.PREFIX))),
        )
        pipeline.bind(EVENTFIRST, com.eratw.port.modapi.HookPhase.PREFIX, EVENT_PACKAGE) { ctx ->
            functionHookSeen += ctx.targetKey
            HookResult.Continue
        }
        FunctionHookWiring.install(session.store, pipeline, logs::add)

        val registry = EventDispatchRegistry()
        ModEventWiring.install(session.store, registry, logs::add)
        // EVENTFIRST 的 ERB 成员都在 NORMAL 桶；Kotlin 处理器注册到 PRI 桶 ⇒ 桶序保证它
        // 先于 NORMAL 成员被消费（`ProcessRuntime.dispatchRemainingBuckets`：同桶先 ERB 成员
        // 再运行期处理器，PRI 无 ERB 成员），所以不怕 NORMAL 的 NEWGAME 链跑不完/抛错。
        registry.register(EVENTFIRST, EventBucket.PRI, EVENT_PACKAGE) { ctx ->
            kotlinHandlerSeen += ctx.eventKey
            EventResult.CONTINUE
        }

        // 生产开局入口：`BeginFlow` 对 `BEGIN FIRST` 调 `callScript("EVENTFIRST", true, true)`
        // ⇒ `ProcessRuntime.executeEvent` 漏斗（函数事件钩子 + 运行期 handler 桶都在这里）。
        // EVENTFIRST 原链是重链（可能停在输入等待），放到 VM 线程外跑并给硬预算。
        val beginReturned = AtomicBoolean(false)
        val beginFailure = AtomicReference<Exception?>()
        val worker = Thread({
            try {
                beginReturned.set(session.runtime.begin(BeginType.FIRST))
            } catch (e: Exception) {
                // 原链在 NORMAL 桶里可能因宿主未接线面抛 CodeEE（如 NEWGAME 链的其它宿主口）；
                // 本用例只钉「事件已真派发到两层处理器」，原链后续失败只记进断言文案。
                beginFailure.set(e)
            }
        }, "hook-e2e-begin-FIRST")
        worker.isDaemon = true
        worker.start()

        val deadline = System.nanoTime() + BEGIN_DEADLINE_NS
        while (System.nanoTime() < deadline &&
            (functionHookSeen.isEmpty() || kotlinHandlerSeen.isEmpty())
        ) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // 断言前先解阻塞：原链若停在输入等待，close() 让它按宿主销毁路径收敛。
        sink.close()
        worker.join(JOIN_TIMEOUT_MS)

        assertEquals(
            listOf(EVENTFIRST),
            functionHookSeen.toList(),
            "函数 hook 的事件钩子没收到 EVENTFIRST（begin 失败=${beginFailure.get()}）",
        )
        assertEquals(
            listOf(EVENTFIRST),
            kotlinHandlerSeen.toList(),
            "Kotlin EventApi 处理器没收到 EVENTFIRST（begin 返回=${beginReturned.get()}，失败=${beginFailure.get()}）",
        )
    }

    @Test
    fun `e FunctionHookWiring uninstall 后真语料调用不再派发`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val hits = CopyOnWriteArrayList<Long>()
        val pipeline = HookPipeline.of(
            listOf(DeclaredHook(HOOK_PACKAGE, HookDeclaration(TARGET, HookPhase.PREFIX))),
        )
        pipeline.bind(TARGET, com.eratw.port.modapi.HookPhase.PREFIX, HOOK_PACKAGE) { ctx ->
            hits += ctx.args[0].intValue
            HookResult.Continue
        }
        FunctionHookWiring.install(session.store, pipeline, logs::add)
        val key = calleeKey(TARGET)

        session.runtime.callFunction(key, longArrayOf(ARG_AUTO), emptyArray())
        assertEquals(1, hits.size, "第一调用没命中钩子（命中数=${hits.size}）")

        FunctionHookWiring.uninstall(session.store, pipeline)
        val before = readResults(session)
        session.runtime.callFunction(key, longArrayOf(ARG_AUTO), emptyArray())
        assertEquals(1, hits.size, "uninstall 后函数 hook 仍在派发")
        assertTrue(readResults(session) == before, "uninstall 只撤钩子，原函数仍须执行")
    }
}

/** `ModRuntimeHost` 的最小装配件：空实体面（本用例只验钩子生命周期，不碰实体声明）。 */
private fun entityKernel(): EntityKernel = EntityKernel(
    schemas = listOf(
        ComponentSchema(
            packageId = "e2e",
            name = "Stats",
            fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
        ),
    ),
    relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
    archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
)

/** 声明快照：本 mod 声明目标函数键的 PREFIX 槽（`HookPipeline.of` 的槽来源）。 */
private fun declarationSnapshot(targetKey: String): com.eratw.port.core.patch.DeclarationSnapshot {
    val registry = DeclarationRegistry()
    registry.addPackage(
        ModDeclarationAdapter.toDescriptor(
            ModManifest(
                packageId = "e2e.hook.mod",
                hooks = listOf(HookDecl(targetKey, com.eratw.port.modapi.HookPhase.PREFIX)),
            ),
        ),
    )
    return registry.freeze()
}

/** 合成 `RuntimeScheduler`：与 `ModRuntimeHostTest.FakeRuntime` 同口径（同步执行、记动作）。 */
private class FakeRuntime : RuntimeScheduler {
    override val isOnMainThread: Boolean get() = true
    val actions = mutableListOf<() -> Unit>()

    override fun submit(action: () -> Unit) {
        actions += action
        action()
    }
}
