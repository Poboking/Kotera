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
import com.eratw.port.core.mod.InteractionPipeline
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 事件处理器与**交互装配链**在真实 261111 语料（erbcc 产物 + 生产 [ProgramLoader]/[GameSession]/
 * `ProcessRuntime`）下的 e2e 锁（task-104）。
 *
 * 与 `HookRealCorpusE2ETest` 的分工：那边钉函数 hook 漏斗；本文件不重复 hook 语义，只钉
 * 「事件面 / 交互面被真实执行路径调用」，以及桶序、事件名闸、收口对称：
 *
 * - a = 真 `begin(BeginType.AFTERTRAIN)`（`BeginFlow.kt:1616` 的 `callScript("EVENTEND", true, true)`
 *   ⇒ `ProcessRuntime.executeEvent`，`ProcessRuntime.kt:4309`）把 `EVENTEND` 派发到运行期
 *   PRI/NORMAL 两个桶，上下文带真事件名与包名；同注册表上的 `EVENTTURNEND` 处理器必须 0 命中
 *   （负向：本条 begin 链只触发 EVENTEND）；
 * - b = 同一事件同一桶两个运行期处理器，第一个返回 `EventResult.SINGLE` ⇒ NORMAL 桶余下处理器
 *   不跑（`ModEventWiring.runBucket` 的 SINGLE 断点，`ProcessRuntime.kt:4375-4386` 的本桶口径）。
 *   **跨桶不在此断言**：EVENTEND 的 LATER 桶有两个真语料 ERB 成员（`Add_Misc.ERB:1449`、
 *   `AFTERTRA.ERB:4`），它们先于运行期 LATER 处理器执行且可能停在输入等待上，观测不稳定；
 * - c = 事件名闸：`ModRuntimeHost.knownEventKeys` 灌真语料事件集（`ModRuntimeAssembly.kt:462`
 *   的生产口径）⇒ onLoad 里注册拼错名当场 IAE 并给最近候选；不灌（空集 = 台架口径）⇒ 放行；
 * - d = 生产装配链：`ModRuntimeHost.install` → `adoptSharedFaces` → `context.events.register` →
 *   `host.eventHandlers` → `ModEventWiring.install`，真 begin 派发命中；
 * - e = 交互装配链：同一 mod 在 `EVENTEND` 处理器里经 `context.interactions.run(...)`
 *   （`InteractionApiAdapter.run` → `ModRuntimeHost.interactionPipeline`）驱动共享
 *   `InteractionPipeline`，PRE/POST 阶段钩子在**真执行路径内**按五阶段序跑到；
 * - f = 收口对称：`host.shutdown()` 撤销注册后重跑一轮，卸载的事件处理器不再命中、交互
 *   `handlerCountOf` 归零；同注册表上的「哨兵」处理器（不归 host 生命周期）证明第二轮 begin
 *   确实又派发了 EVENTEND —— 计数不增长是撤销生效，不是没跑。
 *
 * 语料证据（261111）：
 * - `@EVENTEND` 的 ERB 成员：`fromEN/Addition/Banquets/Add_Banquet.ERB:931`（#PRI）、
 *   `fromEN/Addition/Marriage/Add_Marriage_Concubinage.ERB:2`（#PRI）、
 *   `fromEN/Addition/Add_Misc.ERB:1449`（#LATER）、`イベント関連/AFTERTRA.ERB:4`（#LATER）——
 *   **无 NORMAL ERB 成员**，故 NORMAL 桶的运行期处理器必定是本桶第一个被消费者。
 * - 引擎侧没有 `CALLVENT`/`CALLEVENT` 语句（语料 0 命中）；事件全部由 Kotlin `BeginFlow` 的
 *   `callScript("EVENT…")` 触发（`BeginFlow.kt:1168/1199/1217/1461/1616/1644/1797/2012/2137`）。
 *
 * ## 交互面可达性（实测结论，非假设）
 *
 * `ModInteractionWire` 属 `:app`（`app/src/main/kotlin/com/eratw/port/android/ModInteractionWire.kt`），
 * 而 :core-vm 的 test 源集只依赖 `:mod-api` / `:tools:erbcc` / `:mod-testing`（`core-vm/build.gradle.kts`
 * dependencies），**编译面看不到 :app**。app 侧唯一的驱动点是
 * `app/src/main/kotlin/com/eratw/port/android/ModRuntimeAssembly.kt:1597`
 * `seams.interactionWire.consumeOnBoundary()`（页边界），宿主输入采集在 `GameHost.kt:1741/1752/1812`。
 * 因此本文件不装 `ModInteractionWire`（也不造同名假件假装它跑过），而是钉住它下游的**同一条**
 * 共享管线 [InteractionPipeline] 在真执行路径内被调用；字面用例的卡点见交付报告。
 *
 * 环境门（硬约束）：`ERATW_ERBC_DIR`（program.erbc/program.meta）+ `ERATW_GAMEDIR` 缺一即
 * [fail]，**不许** `assumeTrue`/静默 return —— 语料缺失等于没写。
 */
class EventInteractionRealCorpusE2ETest {

    private companion object {
        /** 与 CQ 波同口径的固定种子（真语料读数可复现）。 */
        const val RAND_SEED: Long = 20260226L

        /** 引擎真实派发的事件名（`BeginType.AFTERTRAIN` 的目标，`BeginType.kt:38`）。 */
        const val EVENTEND: String = "EVENTEND"

        /** 本轮 begin 链不该触发的对照事件（`BeginType.TURNEND` 才调它）。 */
        const val EVENTTURNEND: String = "EVENTTURNEND"

        /** 拼错的事件名：与 EVENTTURNEND 编辑距离 1，最近候选必须命中它。 */
        const val TYPO_EVENT: String = "EVENTTUREND"

        const val PKG: String = "e2e.evente2e.mod"
        const val SENTINEL_PKG: String = "e2e.sentinel"

        /** 交互 id：与 app 侧 `ModInteractionWire.INTERACTION_ID` 不同 —— 这里由 mod 自报。 */
        const val INTERACTION_ID: String = "e2e.interaction"

        /** 真 begin 链预算（EVENTEND 的 PRI/LATER 桶含真语料循环，留足余量）。 */
        const val BEGIN_DEADLINE_NS: Long = 120_000_000_000L
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

        fun newSession(): GameSession = GameSession(
            gameRoot = gameDir,
            program = program,
            metadata = metadata,
            randSeed = RAND_SEED,
            frameGateEnabled = true,
            inputSinkOverride = QueueInputSink(),
        )

        /**
         * 构建期真实事件名集合：`BootMetadata.functions` 里 `isEvent` 的函数键。这是
         * `SymbolCatalog.events.keys` 的同一来源（链接期按 `FuncMeta.isEvent` 分流），
         * 生产装配灌进 `ModRuntimeHost.knownEventKeys` 的就是它（`ModRuntimeAssembly.kt:462`）。
         */
        fun realEventKeys(): Set<String> = metadata.functions
            .asSequence()
            .filter { it.isEvent }
            .map { it.key }
            .toSet()

        /**
         * 在 VM 线程外跑一次真 [BeginType]（原链可能停在输入等待），按 [done] 轮询到命中/超时
         * 后 close 掉输入 sink 解阻塞；返回 begin 抛出的异常（若有），供断言文案使用。
         */
        fun beginUntil(session: GameSession, type: BeginType, done: () -> Boolean): Exception? {
            val failure = AtomicReference<Exception?>()
            val worker = Thread({
                try {
                    session.runtime.begin(type)
                } catch (e: Exception) {
                    // 原链后续（LATER 桶/宿主未接线面）可能抛 CodeEE；本文件只钉「事件处理器
                    // 已真被派发」，异常只进断言文案，不让测试因无关后续失败而红。
                    failure.set(e)
                }
            }, "evente2e-begin-${type.name}")
            worker.isDaemon = true
            worker.start()

            val deadline = System.nanoTime() + BEGIN_DEADLINE_NS
            while (System.nanoTime() < deadline && worker.isAlive && !done()) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            session.inputSink.close()
            worker.join(JOIN_TIMEOUT_MS)
            return failure.get()
        }
    }

    @Test
    fun `a 真 begin AFTERTRAIN 派发 EVENTEND 到 PRI 与 NORMAL 运行期处理器`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val seen = CopyOnWriteArrayList<String>()
        val otherEventSeen = CopyOnWriteArrayList<String>()
        val registry = EventDispatchRegistry()
        ModEventWiring.install(session.store, registry, logs::add)
        registry.register(EVENTEND, EventBucket.PRI, PKG) { ctx ->
            seen += "PRI:${ctx.eventKey}:${ctx.packageId}"
            EventResult.CONTINUE
        }
        registry.register(EVENTEND, EventBucket.NORMAL, PKG) { ctx ->
            seen += "NORMAL:${ctx.eventKey}:${ctx.packageId}"
            EventResult.CONTINUE
        }
        // 负向对照：同注册表挂了 EVENTTURNEND 的处理器，但本轮 begin(AFTERTRAIN) 不该派发它。
        registry.register(EVENTTURNEND, EventBucket.PRI, PKG) { ctx ->
            otherEventSeen += ctx.eventKey
            EventResult.CONTINUE
        }

        val failure = beginUntil(session, BeginType.AFTERTRAIN) { seen.size >= 2 }

        assertEquals(
            listOf("PRI:$EVENTEND:$PKG", "NORMAL:$EVENTEND:$PKG"),
            seen.toList(),
            "EVENTEND 的运行期处理器没被真 begin 派发（命中=$seen，begin 失败=$failure）",
        )
        assertEquals(emptyList(), otherEventSeen.toList(), "没被触发的事件 EVENTTURNEND 却命中处理器：$otherEventSeen")
        assertEquals(emptyList(), logs.toList(), "正常派发不该有隔离日志：$logs")
    }

    @Test
    fun `b SINGLE 截断本桶余下运行期处理器`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val seen = CopyOnWriteArrayList<String>()
        val registry = EventDispatchRegistry()
        ModEventWiring.install(session.store, registry, logs::add)
        registry.register(EVENTEND, EventBucket.PRI, PKG) { seen += "PRI"; EventResult.CONTINUE }
        registry.register(EVENTEND, EventBucket.NORMAL, PKG) { seen += "NORMAL1"; EventResult.SINGLE }
        registry.register(EVENTEND, EventBucket.NORMAL, PKG) { seen += "NORMAL2"; EventResult.CONTINUE }

        val failure = beginUntil(session, BeginType.AFTERTRAIN) { seen.size >= 2 }

        assertEquals(
            listOf("PRI", "NORMAL1"),
            seen.toList(),
            "SINGLE 没截断本桶余下运行期处理器（NORMAL2 必须不跑；begin 失败=$failure）",
        )
        assertEquals(emptyList(), logs.toList(), "正常派发不该有隔离日志：$logs")
    }

    @Test
    fun `c 真语料事件名集合下拼错名拒载并给最近候选，空集合放行`() {
        val known = realEventKeys()
        assertTrue(
            EVENTEND in known,
            "erbcc 产物的构建期事件表没有 $EVENTEND（共 ${known.size} 个事件）：语料/产物不匹配，用例不能空过",
        )

        // 生产装配路径：knownEventKeys 在 install 之前赋值（同 app 侧 ModRuntimeAssembly.kt:462）。
        val gatedHost = newHost()
        gatedHost.knownEventKeys = known
        val gatedStates = gatedHost.install(listOf(PKG to typoEventMod(TYPO_EVENT)))
        val failure = gatedStates.single().failure
        assertFalse(gatedStates.single().active, "拼错事件名应拒载该 mod，却被启用")
        assertTrue(failure is IllegalArgumentException, "拼错事件名应由 register 抛 IAE，实际=$failure")
        val message = failure.message.orEmpty()
        assertTrue(TYPO_EVENT in message, "拒绝消息应含作者写的原始名 $TYPO_EVENT：$message")
        assertTrue(EVENTTURNEND in message, "拒绝消息应含最近候选 $EVENTTURNEND：$message")

        // 未注入（空集 = 台架 / 旧装配口径）：同名注册放行，不校验。
        val openHost = newHost()
        val openStates = openHost.install(listOf(PKG to typoEventMod(TYPO_EVENT)))
        assertTrue(openStates.single().active, "空事件集合下拼错名不该拒载：${openStates.single().failure}")
    }

    @Test
    fun `d 生产装配链 ModRuntimeHost 注册的事件处理器在真 begin 派发里命中`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val hits = CopyOnWriteArrayList<String>()
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.events.register(EVENTEND, EventBucket.PRI) { ctx ->
                    hits += "${ctx.eventKey}|${ctx.packageId}"
                    EventResult.CONTINUE
                }
            }

            override fun onEnable() = Unit

            override fun onDisable() = Unit

            override fun onUnload() = Unit
        }
        val host = newHost()
        val states = host.install(listOf(PKG to mod))
        assertTrue(states.single().active, "mod 未启用：${states.single().failure}")
        ModEventWiring.install(session.store, host.eventHandlers, logs::add)

        val failure = beginUntil(session, BeginType.AFTERTRAIN) { hits.isNotEmpty() }

        assertEquals(
            listOf("$EVENTEND|$PKG"),
            hits.toList(),
            "装配链（host.eventHandlers → ModEventWiring → ProcessRuntime）没把真派发送到 mod 处理器（begin 失败=$failure）",
        )
        assertEquals(emptyList(), logs.toList(), "正常派发不该有隔离日志：$logs")
    }

    @Test
    fun `e 真 begin 派发内 mod 经 context interactions run 驱动共享 InteractionPipeline`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val phases = CopyOnWriteArrayList<String>()
        val eventHits = CopyOnWriteArrayList<String>()
        val lastResult = AtomicReference<com.eratw.port.modapi.InteractionRunResult?>()
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.interactions.registerHook(InteractionPhase.PRE) { ctx ->
                    phases += "PRE:${ctx.interaction.interactionId}:${ctx.packageId}"
                    InteractionResult.CONTINUE
                }
                context.interactions.registerHook(InteractionPhase.POST) { ctx ->
                    phases += "POST:${ctx.interaction.interactionId}:${ctx.packageId}"
                    InteractionResult.CONTINUE
                }
                context.events.register(EVENTEND, EventBucket.PRI) { ctx ->
                    eventHits += ctx.eventKey
                    lastResult.set(
                        context.interactions.run(
                            InteractionContext(
                                interactionId = INTERACTION_ID,
                                actor = EntityId.NULL,
                                targets = emptyList(),
                            ),
                        ) { ModValue.IntValue(42L) },
                    )
                    EventResult.CONTINUE
                }
            }

            override fun onEnable() = Unit

            override fun onDisable() = Unit

            override fun onUnload() = Unit
        }
        val host = newHost(interactionSnapshot(PKG))
        val states = host.install(listOf(PKG to mod))
        assertTrue(states.single().active, "mod 未启用：${states.single().failure}")
        assertEquals(2, host.interactionPipeline.handlerCountOf(PKG), "PRE/POST 钩子没落到 host 共享管线")
        ModEventWiring.install(session.store, host.eventHandlers, logs::add)

        val failure = beginUntil(session, BeginType.AFTERTRAIN) { phases.size >= 2 && eventHits.isNotEmpty() }

        assertEquals(listOf(EVENTEND), eventHits.toList(), "EVENTEND 处理器没跑，交互面无从谈起（begin 失败=$failure）")
        assertEquals(
            listOf("PRE:$INTERACTION_ID:$PKG", "POST:$INTERACTION_ID:$PKG"),
            phases.toList(),
            "共享 InteractionPipeline 没按五阶段序在真执行路径内跑（begin 失败=$failure）",
        )
        val result = lastResult.get() ?: fail("context.interactions.run 没有返回结果")
        assertEquals(ModValue.IntValue(42L), result.returnValue, "交互主流程返回值被管线弄丢")
        assertFalse(result.interrupted, "CONTINUE 钩子不该中断交互")
        assertEquals(emptyList(), logs.toList(), "正常派发不该有隔离日志：$logs")
    }

    @Test
    fun `f shutdown 后再跑一轮：事件处理器不再命中、交互处理器计数归零`() {
        val session = newSession()
        val logs = mutableListOf<String>()
        val phases = CopyOnWriteArrayList<String>()
        val eventHits = CopyOnWriteArrayList<String>()
        val mod = object : EraMod {
            override fun onLoad(context: ModContext) {
                context.interactions.registerHook(InteractionPhase.PRE) { ctx ->
                    phases += ctx.phase.name
                    InteractionResult.CONTINUE
                }
                context.events.register(EVENTEND, EventBucket.PRI) { ctx ->
                    eventHits += ctx.eventKey
                    context.interactions.run(
                        InteractionContext(INTERACTION_ID, EntityId.NULL, emptyList()),
                    ) { ModValue.IntValue(0L) }
                    EventResult.CONTINUE
                }
            }

            override fun onEnable() = Unit

            override fun onDisable() = Unit

            override fun onUnload() = Unit
        }
        val host = newHost(interactionSnapshot(PKG))
        val states = host.install(listOf(PKG to mod))
        assertTrue(states.single().active, "mod 未启用：${states.single().failure}")
        ModEventWiring.install(session.store, host.eventHandlers, logs::add)

        val firstFailure = beginUntil(session, BeginType.AFTERTRAIN) { phases.isNotEmpty() && eventHits.isNotEmpty() }
        assertEquals(1, eventHits.size, "第一轮 EVENTEND 没命中 mod 处理器（begin 失败=$firstFailure）")
        assertEquals(listOf("PRE"), phases.toList(), "第一轮交互 PRE 钩子没跑到（begin 失败=$firstFailure）")

        host.shutdown()
        assertEquals(0, host.interactionPipeline.handlerCountOf(PKG), "shutdown 后交互处理器未撤销")

        // 哨兵：脱离 host 生命周期直接注册到同一注册表，证明第二轮 begin 确实又派发了 EVENTEND。
        val secondSession = newSession()
        val sentinel = CopyOnWriteArrayList<String>()
        host.eventHandlers.register(EVENTEND, EventBucket.PRI, SENTINEL_PKG) { ctx ->
            sentinel += ctx.eventKey
            EventResult.CONTINUE
        }
        ModEventWiring.install(secondSession.store, host.eventHandlers, logs::add)
        val secondFailure = beginUntil(secondSession, BeginType.AFTERTRAIN) { sentinel.isNotEmpty() }

        assertEquals(listOf(EVENTEND), sentinel.toList(), "第二轮 begin 没有再次派发 EVENTEND（begin 失败=$secondFailure）")
        assertEquals(1, eventHits.size, "shutdown 后已撤销的事件处理器仍在派发")
        assertEquals(listOf("PRE"), phases.toList(), "shutdown 后已撤销的交互钩子仍被调用")
        assertEquals(0, host.interactionPipeline.handlerCountOf(PKG), "shutdown 后交互处理器计数又增长")
    }

    /** 拼错事件名的 mod：onLoad 里注册 [TYPO_EVENT]，用来验事件名闸（不跑任何 begin）。 */
    private fun typoEventMod(eventKey: String): EraMod = object : EraMod {
        override fun onLoad(context: ModContext) {
            context.events.register(eventKey, EventBucket.PRI) { EventResult.CONTINUE }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(snapshot: DeclarationSnapshot = packageSnapshot(PKG)): ModRuntimeHost =
        ModRuntimeHost(
            snapshot = snapshot,
            entityKernel = e2eEntityKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { },
            runtime = SyncRuntime(),
        )
}

/**
 * 装配快照：只声明 [packageId] 这个包（无 hooks / 无交互阶段）。
 * `ModRuntimeHost.install` 对不在快照里的 packageId 是 fail-closed 的（不返回半装配 ModContext）。
 */
private fun packageSnapshot(packageId: String): DeclarationSnapshot {
    val registry = DeclarationRegistry()
    registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
    return registry.freeze()
}

/**
 * 装配快照：本 mod 声明 [packageId] 的 PRE/POST 交互阶段钩子（`InteractionPipeline.of` 的
 * 声明槽来源；`registerHook` 未声明阶段会当场 IAE）。
 */
private fun interactionSnapshot(packageId: String): DeclarationSnapshot {
    val registry = DeclarationRegistry()
    registry.addPackage(
        ModDeclarationAdapter.toDescriptor(
            ModManifest(
                packageId = packageId,
                interactionHooks = listOf(
                    InteractionHookDecl(InteractionPhase.PRE),
                    InteractionHookDecl(InteractionPhase.POST),
                ),
            ),
        ),
    )
    return registry.freeze()
}

/** `ModRuntimeHost` 的最小装配件：空实体面（本文件只验事件/交互生命周期，不碰实体声明）。 */
private fun e2eEntityKernel(): EntityKernel = EntityKernel(
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

/** 合成 `RuntimeScheduler`：同步执行（与 `HookRealCorpusE2ETest.FakeRuntime` 同口径）。 */
private class SyncRuntime : RuntimeScheduler {
    override val isOnMainThread: Boolean get() = true

    override fun submit(action: () -> Unit) = action()
}
