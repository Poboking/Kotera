package com.eratw.port.core.mod

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.EventRegistry
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.DeclaredInteractionHook
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.FunctionKey
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookOptions
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modtesting.LogEntry
import com.eratw.port.modtesting.RecordingHooks
import com.eratw.port.modtesting.RecordingInteractions
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eratw.port.core.patch.HookPhase as EngineHookPhase
import com.eratw.port.core.patch.InteractionPhase as EngineInteractionPhase
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * 台架漂移哨兵（Lane T）。
 *
 * `mod-testing` 的 `RecordingEvents` / `RecordingHooks` / `RecordingInteractions` 是作者写 Mod 时的
 * **测试台架**；它们自己手写了一套桶位 / 优先级 / 相位语义，而不是直接复用引擎。台架与引擎一旦
 * 悄悄分叉，作者在台上绿的测试就不代表生产行为。本文件就是这条边界的哨兵：
 *
 * - 逐项对拍：同一编排分别跑**真引擎**（`ProcessRuntime.executeEvent` + `ModEventWiring`、
 *   `HookPipeline.dispatch`、`InteractionPipeline.run`）与**真台架**（`TestWorld.newContext` 发
 *   `TestContext`，再经 `eventsRecorder` / `hooksRecorder` / `interactionsRecorder` 驱动），断言
 *   执行序 / 截断点 / 返回值一致。台架经 `core-vm/build.gradle.kts` 的
 *   `testImplementation(project(":mod-testing"))` 直接编进测试源集，调用点即签名锁。
 * - 台架入口本来就都是 public（`TestWorld.ofManifests` / `newContext` / 三面 recorder 的
 *   `register` / `dispatch` / `fireAll` / `depth`），`internal` 的只有 `MemorySchema`、
 *   `Normalize` 与各 `*RecordTable` 实现类；对拍只做行为观测，不需要直接摸这些内部件，故本 lane
 *   没有为对拍新增任何公开门面、也没有放宽既有 `internal` 语义。
 * - 原「源码文本哨兵」用例已删除：镜像转写已不存在，台架类现在直接参与编译，签名/可见性由编译器
 *   兜底，再读源码文本只会制造第二套易漂移的判据。
 * - **差异登记**：下面是本文件断言「当前确实不一致」的用例（`TODO(verify)`）。这类用例只记录
 *   现状、不改生产代码；台架或引擎一侧修好后它们会变红，提醒同步删差异、补对拍。
 *
 * 已登记差异（证据见各用例 KDoc）：
 * 1. events：台架不建模 ERB 成员链（ERB 的 SINGLE 截断本桶时引擎连该桶处理器一起跳过）——
 *    `RecordingEvents` KDoc 已声明是设计边界，不算缺陷。
 *
 * 已收敛（不再登记差异）：
 * - Lane Z：事件名归一 trim+大写（`Normalize`）、事件处理器异常隔离 + WARN（`RecordingEvents`）、
 *   hooks POSTFIX 返回值通道（`RecordingHooks.dispatchChain`）、POSTFIX 的 `SkipOriginal` 按 Continue。
 * - Lane AB：hooks 完整串联链（`RecordingHooks.dispatchWithOriginal`，语义对齐
 *   `HookPipeline.dispatch`：PREFIX → 原函数 → POSTFIX，Replace 短路 / SkipOriginal 只跳原函数 /
 *   `mayMutateReturn` 只在 POSTFIX 生效 / 未授权实参不回流）、interactions 任意 Throwable 隔离
 *   （`InteractionPipeline.invoke` 同口径）、events 事件名存在性闸（`adoptKnownEvents` 注入后
 *   拒绝拼错名并给最近 3 候选，未注入 = 不拦，与引擎缺省同口径）。收敛证据见各一致性用例 KDoc。
 * - Lane AD：hooks `runOnFailure` 声明位（`MemorySchema.HookSlotSpec` 补齐，原函数异常时补跑
 *   POSTFIX 槽、返回值丢弃、原异常照穿）与 `continueOnHandlerFailure` 槽撤销（缺省 true = 历史
 *   行为；false = 首次处理器异常后撤销本槽、撤销 WARN 只一条、可读出、重新 bind 解除）。
 *
 * 维护约定：台架录制面新增能力（新桶、新相位、新返回值通道）必须同步加对拍，否则本哨兵失效。
 */
class ModTestingParityTest {

    // ------------------------------------------------------------------
    // events：真引擎夹具 / 真台架
    // ------------------------------------------------------------------

    @Test
    fun `events 桶位序与桶内注册序 引擎与台架一致`() {
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val registry = harness.handlers()
        val bench = EventBench()
        val benchCalls = ArrayList<String>()
        // 逆序注册：若任何一侧按注册序跑，断言立刻红（正确序是 PRI → NORMAL → LATER）。
        registry.register(EVENT_KEY, EventBucket.LATER, "mod.a", harness.handler("later"))
        registry.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", harness.handler("normal"))
        registry.register(EVENT_KEY, EventBucket.PRI, "mod.a", harness.handler("pri"))
        bench.register(EVENT_KEY, EventBucket.LATER, "mod.a", recordTo(benchCalls, "later"))
        bench.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", recordTo(benchCalls, "normal"))
        bench.register(EVENT_KEY, EventBucket.PRI, "mod.a", recordTo(benchCalls, "pri"))
        harness.attach(registry)

        assertTrue(harness.runEvent())
        bench.fireAll(EVENT_KEY)

        assertEquals(listOf("pri", "normal", "later"), harness.calls)
        assertEquals(harness.calls, benchCalls)
    }

    @Test
    fun `events SINGLE 只截断本桶 引擎与台架一致`() {
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val registry = harness.handlers()
        val bench = EventBench()
        val benchCalls = ArrayList<String>()
        registry.register(EVENT_KEY, EventBucket.PRI, "mod.a", harness.handler("a1", EventResult.SINGLE))
        registry.register(EVENT_KEY, EventBucket.PRI, "mod.b", harness.handler("a2"))
        registry.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", harness.handler("n"))
        bench.register(EVENT_KEY, EventBucket.PRI, "mod.a", recordTo(benchCalls, "a1", EventResult.SINGLE))
        bench.register(EVENT_KEY, EventBucket.PRI, "mod.b", recordTo(benchCalls, "a2"))
        bench.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", recordTo(benchCalls, "n"))
        harness.attach(registry)

        assertTrue(harness.runEvent())
        bench.fireAll(EVENT_KEY)

        assertEquals(listOf("a1", "n"), harness.calls)
        assertEquals(harness.calls, benchCalls)
    }

    @Test
    fun `events ONLY 桶运行期注册 两边都硬拒`() {
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val bench = EventBench()
        val engineError = assertFailsWith<IllegalArgumentException> {
            harness.handlers().register(EVENT_KEY, EventBucket.ONLY, "mod.a", harness.handler("only"))
        }
        val benchError = assertFailsWith<IllegalArgumentException> {
            bench.register(EVENT_KEY, EventBucket.ONLY, "mod.a", recordTo(ArrayList(), "only"))
        }
        assertTrue(engineError.message.orEmpty().contains("ONLY"), "引擎报错要点名 ONLY：${engineError.message}")
        assertTrue(benchError.message.orEmpty().contains("ONLY"), "台架报错要点名 ONLY：${benchError.message}")
    }

    // ------------------------------------------------------------------
    // events：归一与异常隔离（Lane Z 已在台架侧收敛，断言一致）
    // ------------------------------------------------------------------

    /**
     * 已收敛（原差异 2）：事件键归一 = **trim + uppercase(Locale.ROOT)**，注册侧与派发侧同口径。
     *
     * 引擎证据：`EventDispatchRegistry.kt:120`（`canonical = key.trim().uppercase(Locale.ROOT)`）；
     * 台架证据：`mod-testing/.../Normalize.kt`（`normalize = name.trim().uppercase(Locale.ROOT)`，
     * 事件 register / fire / fireAll 全走它）。
     */
    @Test
    fun `events 事件名归一 trim 加大写 引擎与台架一致`() {
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val registry = harness.handlers()
        registry.register("  evt  ", EventBucket.NORMAL, "mod.a", harness.handler("n"))
        assertTrue(registry.hasHandlers(EVENT_KEY), "引擎：注册侧 trim + 大写，EVT 命中")
        harness.attach(registry)

        val bench = EventBench()
        val benchCalls = ArrayList<String>()
        bench.register("  evt  ", EventBucket.NORMAL, "mod.a", recordTo(benchCalls, "n"))

        assertTrue(harness.runEvent())
        assertEquals(listOf("n"), harness.calls, "引擎：EVT 派发命中带空白的注册")

        bench.fireAll(EVENT_KEY)
        assertEquals(harness.calls, benchCalls, "台架：派发侧 trim + 大写，EVT 命中带空白注册")
        bench.fireAll("  evt  ")
        assertEquals(listOf("n", "n"), benchCalls, "台架：派发键带空白也同样归一命中")
    }

    /**
     * 已收敛（原差异 3）：事件处理器抛异常只隔离该处理器，后续处理器照跑，并落 WARN 点名肇事包。
     *
     * 引擎证据：`ModEventWiring.kt:96-107`（每处理器 runCatching、隔离后按 `EventResult.CONTINUE`、
     * `[pkg][WARN] 事件处理器异常已隔离 ...`）；台架证据：`RecordingEvents.fire` 同口径，
     * WARN 经 `TestWorld.logEntries` 收（字段 `level`/`packageId`/`message`）。
     */
    @Test
    fun `events 处理器异常隔离且后续照跑 引擎与台架一致`() {
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val registry = harness.handlers()
        registry.register(EVENT_KEY, EventBucket.NORMAL, "mod.boom", EventHandler { error("boom") })
        registry.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", harness.handler("after"))
        harness.attach(registry)

        assertTrue(harness.runEvent(), "引擎：处理器炸掉不拖垮事件派发")
        assertEquals(listOf("after"), harness.calls, "引擎：后续处理器照跑")
        val engineLog = harness.logs.single()
        assertTrue(engineLog.contains("mod.boom"), "引擎：WARN 点名肇事包")
        assertTrue(engineLog.contains("已隔离"), "引擎：WARN 表明是隔离")

        val bench = EventBench()
        val benchCalls = ArrayList<String>()
        bench.register(EVENT_KEY, EventBucket.NORMAL, "mod.boom", EventHandler { error("boom") })
        bench.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", recordTo(benchCalls, "after"))
        val benchResults = bench.fireAll(EVENT_KEY)
        assertEquals(listOf("after"), benchCalls, "台架：前一处理器异常被隔离，后续照跑")
        assertEquals(
            listOf(EventResult.CONTINUE, EventResult.CONTINUE),
            benchResults,
            "台架：隔离后按 Continue 计入结果（与引擎 runBucket 同口径）",
        )
        val benchLog = bench.logEntries.single()
        assertEquals("WARN", benchLog.level)
        assertEquals("mod.boom", benchLog.packageId, "台架：WARN 点名肇事包")
        assertTrue(benchLog.message.contains("已隔离"), "台架：WARN 表明是隔离")
    }

    // ------------------------------------------------------------------
    // events：事件名存在性闸（Lane AB 已在台架侧收敛，断言一致）
    // ------------------------------------------------------------------

    /**
     * 一致性：`RecordingEvents.adoptKnownEvents` 注入构建期事件名清单后，拼错名在注册当场被拒，
     * 且文案与引擎 `EventDispatchRegistry.register` 逐字一致（含最近 3 候选提示）。
     *
     * 证据：引擎 `EventDispatchRegistry.kt:78-103`（known 非空时 require)、`:124-168`
     * （`unknownEventMessage` / `nearestEventNames` / `editDistance`）；台架 `RecordingEvents`
     * 的同名实现（文案逐字抄自引擎）。
     */
    @Test
    fun `events 存在性闸 拼错名两侧同样硬拒且候选一致`() {
        val knownEvents = setOf(EVENT_KEY, "EVE", "EVX", TARGET)
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val registry = harness.handlers()
        registry.adoptKnownEvents(knownEvents)
        val bench = EventBench()
        bench.adoptKnownEvents(knownEvents)

        val engineError = assertFailsWith<IllegalArgumentException>("引擎：注入已知事件后拼错名拒绝注册") {
            registry.register("EVT1", EventBucket.NORMAL, "mod.a", harness.handler("typo"))
        }
        val benchError = assertFailsWith<IllegalArgumentException>("台架：注入已知事件后拼错名拒绝注册") {
            bench.register("EVT1", EventBucket.NORMAL, "mod.a", recordTo(ArrayList(), "typo"))
        }
        assertEquals(engineError.message, benchError.message, "两侧拒绝文案逐字一致")
        assertTrue(
            engineError.message.orEmpty().contains("最接近的候选：EVT, EVE, EVX"),
            "最近 3 候选按编辑距离 → 名字序：$engineError",
        )

        harness.attach(registry)
        assertFalse(harness.runEvent("GHOST"), "引擎：拼错的事件名没有成员，不会派发")
        assertTrue(harness.calls.isEmpty())
        assertTrue(bench.fireAll("GHOST").isEmpty(), "台架：拒绝注册后表里没有处理器，派发为空")
    }

    /**
     * 一致性：未注入已知事件清单时两侧都不拦（引擎缺省 `knownEvents = emptySet()` 不校验，
     * 台架缺省同样放行）。这是「台架开箱即用 / 生产真实会话注入」的同口径。
     *
     * 证据：引擎 `EventDispatchRegistry.kt:57-67`（缺省空集）；台架 `RecordingEvents.adoptKnownEvents`。
     */
    @Test
    fun `events 存在性闸 未注入时两侧都不拦`() {
        val harness = EventEngineHarness(mixedMarkers, mixedMetas())
        val registry = harness.handlers()
        registry.register("GHOST", EventBucket.NORMAL, "mod.a", harness.handler("ghost"))
        val bench = EventBench()
        val benchCalls = ArrayList<String>()
        bench.register("GHOST", EventBucket.NORMAL, "mod.a", recordTo(benchCalls, "ghost"))

        harness.attach(registry)
        // 2026-11-26 A1 语义：引擎对无 ERB 成员的键不再「视为不存在」——有运行期处理器就
        // 派发（四桶只跑 Kotlin 成员）。旧断言「runEvent false / calls 空」是引擎与台架的
        // 对拍破裂（台架 fireAll 本就派发），A1 恰好修复；两侧现在同派发、同读数。
        assertTrue(harness.runEvent("GHOST"), "引擎：无 ERB 成员但有运行期处理器 ⇒ 照常派发（A1）")
        assertEquals(listOf("ghost"), harness.calls, "引擎：Kotlin 处理器真跑（与台架同读数）")
        bench.fireAll("GHOST")
        assertEquals(listOf("ghost"), benchCalls, "台架：未注入清单不拦，派发照常（两侧现已同读数）")
    }

    // ------------------------------------------------------------------
    // events：差异登记（尚未收敛）
    // ------------------------------------------------------------------

    /**
     * 差异 1（原差异 2，设计边界）：台架不建模 ERB 事件成员链，因此无法表达「ERB 的 SINGLE
     * 截断本桶时该桶运行期处理器也跳过」。`RecordingEvents` KDoc 已声明此边界。
     *
     * 证据：引擎 `ProcessRuntime.kt:4133-4200`（dispatchEventMembers / dispatchBucketMembers 先跑 ERB
     * 成员、被 SINGLE 截断则跳过该桶 runBucket）；台架无 ERB 成员概念。
     *
     * TODO(verify)：若台架后续接入成员链模型，本用例改为对拍而非记录差异。
     */
    @Test
    fun `差异登记 events 台架不建模 ERB 成员链`() {
        val harness = EventEngineHarness(
            markers = listOf("p1", "p2", "n0"),
            metas = listOf(
                mainMeta(),
                eventMeta(1, isPri = true, isSingle = true),
                eventMeta(2, isPri = true),
                eventMeta(3),
            ),
            singleAt = setOf(0),
        )
        val registry = harness.handlers()
        registry.register(EVENT_KEY, EventBucket.PRI, "mod.a", harness.handler("pri"))
        registry.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", harness.handler("n"))
        harness.attach(registry)

        assertTrue(harness.runEvent())
        assertEquals("p1\nn0\n", harness.output, "引擎：PRI 桶 p2 被 ERB 的 SINGLE 跳过")
        assertEquals(listOf("n"), harness.calls, "引擎：被截断桶的运行期处理器也跳过；下一桶照跑")

        val bench = EventBench()
        val benchCalls = ArrayList<String>()
        bench.register(EVENT_KEY, EventBucket.PRI, "mod.a", recordTo(benchCalls, "pri"))
        bench.register(EVENT_KEY, EventBucket.NORMAL, "mod.a", recordTo(benchCalls, "n"))
        bench.fireAll(EVENT_KEY)
        assertEquals(listOf("pri", "n"), benchCalls, "台架：无成员链，PRI 处理器照跑（设计边界）")
    }
}

/**
 * hooks 录制面哨兵：同一编排分别跑真引擎 `HookPipeline.dispatch` 与真台架 `RecordingHooks`，
 * 断言优先级/声明序、Replace 短路、SkipOriginal、实参回流、postfix 返回值通道与串联链一致。
 * 语义背景与差异登记见 `ModTestingParityTest` 的文件级 KDoc。
 */
class ModTestingHooksParityTest {

    // ------------------------------------------------------------------
    // hooks：真引擎 / 真台架对拍
    // ------------------------------------------------------------------

    @Test
    fun `hooks priority 与声明序 引擎与台架一致`() {
        val declarations = listOf(
            hookDecl("pkgA", priority = 10),
            hookDecl("pkgB", priority = 0),
            hookDecl("pkgC", priority = 0),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        for (pkg in listOf("pkgA", "pkgB", "pkgC")) {
            pipeline.bind(TARGET, ModHookPhase.PREFIX, pkg) {
                engineOrder += pkg
                HookResult.Continue
            }
            bench.register(TARGET, ModHookPhase.PREFIX, pkg, recordHook(benchOrder, pkg))
        }

        val outcome = pipeline.dispatch(TARGET, emptyArray()) {
            engineOrder += "ORIGINAL"
            ModValue.of(1L)
        }
        val benchResult = bench.dispatch(TARGET, ModHookPhase.PREFIX, emptyArray())

        assertEquals(listOf("pkgB", "pkgC", "pkgA"), benchOrder, "台架：priority 升序 + 声明序")
        assertEquals(engineOrder.dropLast(1), benchOrder, "引擎与台架同序（引擎末尾多一次原函数）")
        assertEquals(listOf("pkgB", "pkgC", "pkgA", "ORIGINAL"), engineOrder)
        assertTrue(outcome.originalRan)
        assertEquals(HookResult.Continue, benchResult)
    }

    @Test
    fun `hooks prefix Replace 短路 引擎与台架一致`() {
        val declarations = listOf(
            hookDecl("pkgA", priority = 0),
            hookDecl("pkgB", priority = 1),
            hookDecl("pkgC", phase = EngineHookPhase.POSTFIX),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineOrder += "A"
            HookResult.Replace(ModValue.of(99L))
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgB") {
            engineOrder += "B"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgC") {
            engineOrder += "C"
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", hookReplace(benchOrder, "A", ModValue.of(99L)))
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgB", recordHook(benchOrder, "B"))
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgC", recordHook(benchOrder, "C"))

        val outcome = pipeline.dispatch(TARGET, emptyArray()) {
            engineOrder += "ORIGINAL"
            ModValue.of(1L)
        }
        val benchResult = bench.dispatch(TARGET, ModHookPhase.PREFIX, emptyArray())

        assertEquals(listOf("A"), benchOrder, "台架：prefix Replace 短路余下 prefix hook")
        assertEquals(engineOrder, benchOrder, "引擎同样短路（原函数与 postfix 都不跑）")
        assertTrue(benchResult is HookResult.Replace, "台架：Replace 原样返回")
        assertEquals(ModValue.of(99L), (benchResult as HookResult.Replace).value)
        assertEquals(ModValue.of(99L), outcome.returnValue)
        assertFalse(outcome.originalRan)
        assertTrue(outcome.replaced)
    }

    @Test
    fun `hooks prefix SkipOriginal 引擎与台架一致`() {
        val declarations = listOf(
            hookDecl("pkgA", phase = EngineHookPhase.PREFIX),
            hookDecl("pkgA", phase = EngineHookPhase.POSTFIX),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineOrder += "PRE"
            HookResult.SkipOriginal
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgA") {
            engineOrder += "POST"
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", hookSkip(benchOrder, "PRE"))
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgA", recordHook(benchOrder, "POST"))

        val outcome = pipeline.dispatch(TARGET, emptyArray()) {
            engineOrder += "ORIGINAL"
            ModValue.of(1L)
        }
        // 台架一次 dispatch 只跑一个相位，与引擎的单次串联不同；两次调用才等价（差异 3）。
        val prefixResult = bench.dispatch(TARGET, ModHookPhase.PREFIX, emptyArray())
        val postfixResult = bench.dispatch(TARGET, ModHookPhase.POSTFIX, emptyArray())

        assertEquals(listOf("PRE", "POST"), benchOrder)
        assertEquals(engineOrder, benchOrder, "引擎：原函数被跳过、postfix 照跑")
        assertEquals(HookResult.SkipOriginal, prefixResult, "台架：prefix 相位返回 SkipOriginal")
        assertEquals(HookResult.Continue, postfixResult)
        assertFalse(outcome.originalRan)
        assertNull(outcome.returnValue)
    }

    @Test
    fun `hooks 未授权实参不回流 授权时回流 两边一致`() {
        val declarations = listOf(
            hookDecl("pkgNo", priority = 0, mayMutateArgs = false),
            hookDecl("pkgYes", priority = 1, mayMutateArgs = true),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgNo") { ctx ->
            ctx.args[0].intValue = 1L
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgYes") { ctx ->
            ctx.args[0].intValue = 42L
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgNo", mutateArgHandler(1L))
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgYes", mutateArgHandler(42L))

        val engineArgs = arrayOf(ModCallArg(intValue = 7L))
        pipeline.dispatch(TARGET, engineArgs) { ModValue.of(0L) }
        assertEquals(42L, engineArgs[0].intValue, "引擎：只有授权 hook 的修改回流")

        val benchArgs = arrayOf(ModCallArg(intValue = 7L))
        bench.dispatch(TARGET, ModHookPhase.PREFIX, benchArgs)
        assertEquals(42L, benchArgs[0].intValue, "台架：授权面与引擎一致")

        val onlyNo = listOf(hookDecl("pkgNo", mayMutateArgs = false))
        val engineOnlyNo = HookPipeline.of(onlyNo)
        engineOnlyNo.bind(TARGET, ModHookPhase.PREFIX, "pkgNo", mutateArgHandler(1L))
        val engineArgs2 = arrayOf(ModCallArg(intValue = 7L))
        engineOnlyNo.dispatch(TARGET, engineArgs2) { ModValue.of(0L) }
        assertEquals(7L, engineArgs2[0].intValue, "引擎：未授权改实参不回流")

        val benchOnlyNo = HookBench(onlyNo)
        benchOnlyNo.register(TARGET, ModHookPhase.PREFIX, "pkgNo", mutateArgHandler(1L))
        val benchArgs2 = arrayOf(ModCallArg(intValue = 7L))
        benchOnlyNo.dispatch(TARGET, ModHookPhase.PREFIX, benchArgs2)
        assertEquals(7L, benchArgs2[0].intValue, "台架：未授权改实参不回流")
    }

    @Test
    fun `hooks 未声明槽绑定 两边都拒绝`() {
        val pipeline = HookPipeline.of(emptyList())
        assertFailsWith<IllegalArgumentException> {
            pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") { HookResult.Continue }
        }
        val bench = HookBench(emptyList())
        assertFailsWith<IllegalArgumentException> {
            bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler { HookResult.Continue })
        }
    }

    // ------------------------------------------------------------------
    // hooks：postfix 返回值通道与 SkipOriginal（Lane Z 已在台架侧收敛，断言一致）
    // ------------------------------------------------------------------

    /**
     * 已收敛（原差异 5）：POSTFIX 的返回值通道与生产一致 —— 上下文看到**当前返回值**，授权
     * `mayMutateReturn` 的槽返回 Continue 后写回，结局带回最终值。
     *
     * 引擎证据：`HookPipeline.kt:205-235`（postfix 构造 `HookContext(returnValue = current)`，
     * `HookResult.Continue -> if (slot.mayMutateReturn) current = context.returnValue`）；
     * 台架证据：`RecordingHooks.dispatchChain`（同口径，最终值经 `HookChainResult.returnValue` 带回）。
     */
    @Test
    fun `hooks postfix 返回值通道 引擎与台架一致`() {
        val declarations = listOf(hookDecl("pkgYes", phase = EngineHookPhase.POSTFIX, mayMutateReturn = true))
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgYes") { ctx ->
            ctx.returnValue = ModValue.of(200L)
            HookResult.Continue
        }
        var benchSawInitial: ModValue? = null
        bench.register(
            TARGET,
            ModHookPhase.POSTFIX,
            "pkgYes",
            HookHandler { ctx ->
                benchSawInitial = ctx.returnValue
                ctx.returnValue = ModValue.of(200L)
                HookResult.Continue
            },
        )

        val outcome = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        assertEquals(ModValue.of(200L), outcome.returnValue, "引擎：授权 hook 改返回值生效")

        val benchResult = bench.dispatchChain(TARGET, ModHookPhase.POSTFIX, emptyArray(), ModValue.of(1L))
        assertEquals(ModValue.of(1L), benchSawInitial, "台架：postfix 上下文拿到原返回值")
        assertEquals(outcome.returnValue, benchResult.returnValue, "引擎与台架改值结果一致")
        assertEquals(HookResult.Continue, benchResult.result)
        assertFalse(benchResult.replaced, "台架：Continue 改值不算替换")
    }

    /**
     * 已收敛（原差异 6）：POSTFIX 的 `SkipOriginal` 在两侧都**按 Continue 处理**、返回值不变
     * （postfix 阶段没有「原函数」可跳，生产 `HookPipeline` 明确忽略）。
     *
     * 引擎证据：`HookPipeline.kt:222`（postfix 的 `HookResult.SkipOriginal -> Unit`）；
     * 台架证据：`RecordingHooks.dispatchChain`（POSTFIX 阶段的 SkipOriginal 不改结局与返回值）。
     */
    @Test
    fun `hooks postfix 的 SkipOriginal 按 Continue 处理 引擎与台架一致`() {
        val declarations = listOf(hookDecl("pkgA", phase = EngineHookPhase.POSTFIX))
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgA") { HookResult.SkipOriginal }
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgA", HookHandler { HookResult.SkipOriginal })

        val outcome = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        assertEquals(ModValue.of(1L), outcome.returnValue, "引擎：postfix 的 SkipOriginal 被忽略，返回值不变")
        assertTrue(outcome.originalRan, "引擎：postfix 阶段原函数已跑过")

        val benchResult = bench.dispatchChain(TARGET, ModHookPhase.POSTFIX, emptyArray(), ModValue.of(1L))
        assertEquals(outcome.returnValue, benchResult.returnValue, "引擎与台架都忽略 postfix 的 SkipOriginal")
        assertEquals(HookResult.Continue, benchResult.result, "台架：postfix 的 SkipOriginal 按 Continue 处理")
        assertFalse(benchResult.replaced)
    }

    /**
     * 已收敛（原差异 2 在 hooks 侧的同一口径）：hook target 归一 = **trim + uppercase(ROOT)**，
     * 声明、绑定、派发三处都吃同一份归一，带空白的键与无空白键等价。
     *
     * 引擎证据：`HookPipeline.kt:405`（`canonical = key.trim().uppercase(Locale.ROOT)`）+
     * `HookDeclaration.normalizedTargetKey()`（声明侧归一）；
     * 台架证据：`MemorySchema.HookSlotSpec.targetKey = normalize(targetKey)` 与
     * `RecordingHooks.dispatch/register` 的 `normalize(...)`（同 `Normalize.kt`）。
     */
    @Test
    fun `hooks 目标键归一 trim 加大写 引擎与台架一致`() {
        val declarations = listOf(hookDecl("pkgA"))
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        pipeline.bind("  com300  ", ModHookPhase.PREFIX, "pkgA") {
            engineOrder += "pkgA"
            HookResult.Continue
        }
        val benchCalls = ArrayList<String>()
        bench.register("  com300  ", ModHookPhase.PREFIX, "pkgA") {
            benchCalls += "pkgA"
            HookResult.Continue
        }

        assertTrue(pipeline.hasHooks("  com300  "), "引擎：声明侧已归一大写，带空白键仍能查到槽")
        pipeline.dispatch("  com300  ", emptyArray()) { ModValue.of(1L) }
        assertEquals(listOf("pkgA"), engineOrder, "引擎：派发侧归一后命中断言槽")

        val benchResult = bench.dispatch("  com300  ", ModHookPhase.PREFIX, emptyArray())
        assertEquals(engineOrder, benchCalls, "台架：声明/绑定/派发同归一，命中同一槽")
        assertEquals(HookResult.Continue, benchResult)
    }

    /**
     * 已收敛（原差异 5 的否定面）：POSTFIX 改值只在槽被授权 `mayMutateReturn` 时写回；
     * 未授权槽改 `ctx.returnValue` 在两侧都被丢弃，结局仍是 Continue、返回值不变。
     *
     * 引擎证据：`HookPipeline.kt:205-235`（`Continue -> if (slot.mayMutateReturn) current = context.returnValue`）；
     * 台架证据：`RecordingHooks.dispatchChain`（`if (phase == POSTFIX && slot.mayMutateReturn)` 才写回）。
     */
    @Test
    fun `hooks postfix 未授权槽改值不生效 引擎与台架一致`() {
        val declarations = listOf(hookDecl("pkgNo", phase = EngineHookPhase.POSTFIX, mayMutateReturn = false))
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgNo") { ctx ->
            ctx.returnValue = ModValue.of(200L)
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgNo", HookHandler { ctx ->
            ctx.returnValue = ModValue.of(200L)
            HookResult.Continue
        })

        val outcome = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        assertEquals(ModValue.of(1L), outcome.returnValue, "引擎：未授权槽改返回值被丢弃")

        val benchResult = bench.dispatchChain(TARGET, ModHookPhase.POSTFIX, emptyArray(), ModValue.of(1L))
        assertEquals(outcome.returnValue, benchResult.returnValue, "台架：未授权槽同样丢弃改值")
        assertEquals(HookResult.Continue, benchResult.result)
        assertFalse(benchResult.replaced)
    }
    // ------------------------------------------------------------------
    // hooks：相位串联链（Lane AB 已在台架侧收敛，断言一致）
    // ------------------------------------------------------------------

    /**
     * 一致性：一次串联 dispatch 跑 PREFIX → 原函数 → POSTFIX，两侧执行序、实参回流与返回值一致。
     *
     * 语义逐条：PREFIX 按 priority/声明序；未授权 `mayMutateArgs` 的槽改实参不回流；
     * `mayMutateArgs` 槽改值回流给原函数；原函数返回值作为 POSTFIX 初值；`mayMutateReturn` 槽
     * Continue 后的改值写回终局，未授权槽丢弃。
     *
     * 证据：引擎 `HookPipeline.kt:131-236`（dispatch 链）、`:340-348`（isolatedArgs）；
     * 台架 `RecordingHooks.dispatchWithOriginal`。
     */
    @Test
    fun `hooks 串联链 相位序与实参返回值 引擎与台架一致`() {
        val fixture = HookChainFixture()
        val outcome = fixture.dispatchEngine()
        val benchOutcome = fixture.dispatchBench()

        assertEquals(listOf("A", "B", "ORIGINAL", "C", "D"), fixture.engineOrder)
        assertEquals(fixture.engineOrder, fixture.benchOrder, "引擎与台架串联链序一致")
        assertEquals(fixture.engineSeen, fixture.benchSeen, "未授权槽的实参改动不回流；POSTFIX 看到原函数返回值")
        assertEquals(
            listOf<ModValue?>(ModValue.of(7L), ModValue.of(7L)),
            fixture.engineSeen,
            "pkgA 未授权改值被丢弃；pkgC 看到初值 7",
        )
        assertEquals(ModValue.of(42L), outcome.returnValue, "引擎：授权 postfix 槽写回 42；未授权 pkgD 丢弃")
        assertEquals(outcome.returnValue, benchOutcome.returnValue, "台架：终局返回值一致")
        assertTrue(outcome.originalRan)
        assertTrue(benchOutcome.originalRan)
        assertFalse(outcome.replaced)
        assertFalse(benchOutcome.replaced)
        assertEquals(emptyList(), benchOutcome.failures)
        assertEquals(42L, fixture.engineArgValue(), "引擎：授权槽改的实参定稿为 42")
        assertEquals(fixture.engineArgValue(), fixture.benchArgValue(), "台架：实参终态一致")
    }

    /**
     * 一致性：PREFIX 阶段 `Replace` 短路整条链——原函数与 POSTFIX 都不跑，终局就是替换值。
     *
     * 证据：引擎 `HookPipeline.kt:167-172`（`is HookResult.Replace -> return HookOutcome(...)`）；
     * 台架 `RecordingHooks.dispatchWithOriginal` 的 PREFIX 段同口径。
     */
    @Test
    fun `hooks 串联链 PREFIX Replace 短路 引擎与台架一致`() {
        val declarations = listOf(
            hookDecl("pkgA", priority = 0),
            hookDecl("pkgB", priority = 1),
            hookDecl("pkgC", phase = EngineHookPhase.POSTFIX),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineOrder += "A"
            HookResult.Replace(ModValue.of(99L))
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgB") {
            engineOrder += "B"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgC") {
            engineOrder += "C"
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler {
            benchOrder += "A"
            HookResult.Replace(ModValue.of(99L))
        })
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgB", HookHandler {
            benchOrder += "B"
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgC", HookHandler {
            benchOrder += "C"
            HookResult.Continue
        })

        val outcome = pipeline.dispatch(TARGET, emptyArray()) {
            engineOrder += "ORIGINAL"
            ModValue.of(1L)
        }
        val benchOutcome = bench.dispatchWithOriginal(TARGET, emptyArray()) {
            benchOrder += "ORIGINAL"
            ModValue.of(1L)
        }

        assertEquals(listOf("A"), engineOrder)
        assertEquals(engineOrder, benchOrder, "引擎与台架都在 A 处短路")
        assertEquals(ModValue.of(99L), outcome.returnValue)
        assertEquals(outcome.returnValue, benchOutcome.returnValue)
        assertFalse(outcome.originalRan)
        assertFalse(benchOutcome.originalRan)
        assertTrue(outcome.replaced)
        assertTrue(benchOutcome.replaced)
    }

    /**
     * 一致性：PREFIX 的 `SkipOriginal` 只跳过原函数，后续 PREFIX 槽与 POSTFIX 槽照跑；
     * POSTFIX 初值为 null（原函数没跑），POSTFIX 的 `SkipOriginal` 无副作用。
     *
     * 证据：引擎 `HookPipeline.kt:181-233`（skipOriginal 标记 + postfix 从 current=null 起步）；
     * 台架 `RecordingHooks.dispatchWithOriginal`。
     */
    @Test
    fun `hooks 串联链 SkipOriginal 只跳原函数 引擎与台架一致`() {
        val declarations = listOf(
            hookDecl("pkgA", priority = 0),
            hookDecl("pkgB", priority = 1),
            hookDecl("pkgC", phase = EngineHookPhase.POSTFIX),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        val engineSeen = ArrayList<ModValue?>()
        val benchSeen = ArrayList<ModValue?>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineOrder += "A"
            HookResult.SkipOriginal
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgB") {
            engineOrder += "B"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgC") {
            engineOrder += "C"
            engineSeen += it.returnValue
            HookResult.SkipOriginal
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler {
            benchOrder += "A"
            HookResult.SkipOriginal
        })
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgB", HookHandler {
            benchOrder += "B"
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgC", HookHandler {
            benchOrder += "C"
            benchSeen += it.returnValue
            HookResult.SkipOriginal
        })

        val outcome = pipeline.dispatch(TARGET, emptyArray()) {
            engineOrder += "ORIGINAL"
            ModValue.of(1L)
        }
        val benchOutcome = bench.dispatchWithOriginal(TARGET, emptyArray()) {
            benchOrder += "ORIGINAL"
            ModValue.of(1L)
        }

        assertEquals(listOf("A", "B", "C"), engineOrder)
        assertEquals(engineOrder, benchOrder, "跳过原函数但后续相位照跑")
        assertEquals(listOf<ModValue?>(null), engineSeen, "POSTFIX 初值为 null（原函数没跑）")
        assertEquals(engineSeen, benchSeen)
        assertNull(outcome.returnValue)
        assertEquals(outcome.returnValue, benchOutcome.returnValue)
        assertFalse(outcome.originalRan)
        assertFalse(benchOutcome.originalRan)
        assertFalse(benchOutcome.replaced)
    }

    /**
     * 一致性：某个处理器抛异常只在两侧被隔离——失败记包名、链继续，原函数与后续相位照跑。
     *
     * 证据：引擎 `HookPipeline.kt:175-184`（`invoke` 的 `catch (t: Throwable)` → failures + Continue）；
     * 台架 `RecordingHooks.invokeIsolated` 同口径。
     */
    @Test
    fun `hooks 串联链 处理器异常隔离 引擎与台架一致`() {
        val declarations = listOf(
            hookDecl("pkgA", priority = 0),
            hookDecl("pkgB", priority = 1),
            hookDecl("pkgC", phase = EngineHookPhase.POSTFIX),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            throw ParityRuntimeBoomException()
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgB") {
            engineOrder += "B"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgC") {
            engineOrder += "C"
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler {
            throw ParityRuntimeBoomException()
        })
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgB", HookHandler {
            benchOrder += "B"
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgC", HookHandler {
            benchOrder += "C"
            HookResult.Continue
        })

        val outcome = pipeline.dispatch(TARGET, emptyArray()) {
            engineOrder += "ORIGINAL"
            ModValue.of(1L)
        }
        val benchOutcome = bench.dispatchWithOriginal(TARGET, emptyArray()) {
            benchOrder += "ORIGINAL"
            ModValue.of(1L)
        }

        assertEquals(listOf("B", "ORIGINAL", "C"), engineOrder)
        assertEquals(engineOrder, benchOrder)
        assertEquals(listOf("pkgA"), outcome.failures.map { it.packageId })
        assertEquals(outcome.failures.map { it.packageId }, benchOutcome.failures, "两侧都点名被隔离的包")
        assertEquals(outcome.returnValue, benchOutcome.returnValue)
    }

    /**
     * 一致性：原函数抛异常时两侧都把它原样重抛，不会被隔离成 CONTINUE。
     *
     * 生产在这一步会先补跑 `runOnFailure` 的 POSTFIX 槽（`HookPipeline.kt:272-300`），台架
     * `dispatchWithOriginal` 同口径（Lane AD 补齐）；本用例无 runOnFailure 槽，补跑段为空，
     * 正好锁住「原函数异常不被吞」；带补跑的对比见下一个用例。
     */
    @Test
    fun `hooks 串联链 原函数异常两侧都重抛`() {
        val declarations = listOf(hookDecl("pkgA"))
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") { HookResult.Continue }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler { HookResult.Continue })

        val engineError = assertFailsWith<IllegalStateException> {
            pipeline.dispatch(TARGET, emptyArray()) { throw IllegalStateException("original-boom") }
        }
        val benchError = assertFailsWith<IllegalStateException> {
            bench.dispatchWithOriginal(TARGET, emptyArray()) { throw IllegalStateException("original-boom") }
        }
        assertEquals("original-boom", engineError.message)
        assertEquals(engineError.message, benchError.message, "原函数异常两侧原样透出")
    }

    /**
     * 一致性：原函数抛异常时，`runOnFailure=true` 的 POSTFIX 槽被补跑、`runOnFailure=false` 的不进，
     * 之后原异常照穿。
     *
     * 证据：引擎 `HookPipeline.runFailurePostfix`（`HookPipeline.kt:272-300`，过滤
     * `phase != POSTFIX || !runOnFailure`）；台架 `RecordingHooks.runFailurePostfix` 同口径。
     */
    @Test
    fun `hooks 原函数异常 runOnFailure 槽补跑 引擎与台架一致`() {
        val declarations = listOf(
            failureHookDecl(
                "pkgRun",
                phase = EngineHookPhase.POSTFIX,
                options = HookFailureOptions(runOnFailure = true),
            ),
            hookDecl("pkgSkip", phase = EngineHookPhase.POSTFIX, priority = 1),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgRun") {
            engineOrder += "RUN"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgSkip") {
            engineOrder += "SKIP"
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgRun", HookHandler {
            benchOrder += "RUN"
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgSkip", HookHandler {
            benchOrder += "SKIP"
            HookResult.Continue
        })

        val engineError = assertFailsWith<IllegalStateException> {
            pipeline.dispatch(TARGET, emptyArray()) { throw IllegalStateException("original-boom") }
        }
        val benchError = assertFailsWith<IllegalStateException> {
            bench.dispatchWithOriginal(TARGET, emptyArray()) { throw IllegalStateException("original-boom") }
        }

        assertEquals(listOf("RUN"), engineOrder, "只有 runOnFailure 槽参加补跑段")
        assertEquals(engineOrder, benchOrder)
        assertEquals(engineError.message, benchError.message)
    }

    /**
     * 一致性：`continueOnHandlerFailure=false` 的槽首次抛异常后被撤销——只记一条失败、只留一条
     * 撤销 WARN，此后派发不再进入；引擎与台架同口径。
     *
     * 证据：引擎 `HookPipeline.handleHandlerFailure`（`HANDLER_THREW` + `HANDLER_THREW_SLOT_RETIRED`）；
     * 台架 `HookRecordTable.retireIfNeeded`（失败进 failures、撤销 WARN 进会话日志）。
     */
    @Test
    fun `hooks 处理器失败 撤销槽 引擎与台架一致`() {
        val declarations = listOf(
            failureHookDecl("pkgA", options = HookFailureOptions(continueOnHandlerFailure = false)),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        var engineCalls = 0
        var benchCalls = 0
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineCalls += 1
            throw ParityRuntimeBoomException()
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler {
            benchCalls += 1
            throw ParityRuntimeBoomException()
        })

        val engineFirst = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        val benchFirst = bench.dispatchWithOriginal(TARGET, emptyArray()) { ModValue.of(1L) }
        val engineSecond = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        val benchSecond = bench.dispatchWithOriginal(TARGET, emptyArray()) { ModValue.of(1L) }

        assertEquals(1, engineCalls)
        assertEquals(engineCalls, benchCalls, "撤销后两侧都不再进入处理器")
        assertEquals(listOf("pkgA"), engineFirst.failures.map { it.packageId })
        assertEquals(engineFirst.failures.map { it.packageId }, benchFirst.failures)
        assertTrue(engineSecond.failures.isEmpty())
        assertTrue(benchSecond.failures.isEmpty())
        assertEquals(1, pipeline.retiredSlotCount)
        assertEquals(pipeline.retiredSlotCount, bench.retiredSlotCount)
        assertTrue(bench.isSlotRetired(TARGET, ModHookPhase.PREFIX, "pkgA"))
        assertEquals(
            1,
            bench.logEntries.count { it.message.contains("hook 槽已撤销") },
            "撤销 WARN 只落一条",
        )
    }

    /**
     * 一致性：缺省 `continueOnHandlerFailure=true` 不改行为——处理器每次派发都进、每次都记失败，
     * 不撤销、不落撤销 WARN。
     */
    @Test
    fun `hooks 缺省 continueOnHandlerFailure 不撤销 引擎与台架一致`() {
        val declarations = listOf(hookDecl("pkgA"))
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        var engineCalls = 0
        var benchCalls = 0
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineCalls += 1
            throw ParityRuntimeBoomException()
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler {
            benchCalls += 1
            throw ParityRuntimeBoomException()
        })

        val engineFirst = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        val benchFirst = bench.dispatchWithOriginal(TARGET, emptyArray()) { ModValue.of(1L) }
        val engineSecond = pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        val benchSecond = bench.dispatchWithOriginal(TARGET, emptyArray()) { ModValue.of(1L) }

        assertEquals(2, engineCalls)
        assertEquals(engineCalls, benchCalls)
        assertEquals(1, engineFirst.failures.size)
        assertEquals(engineFirst.failures.size, benchFirst.failures.size)
        assertEquals(1, engineSecond.failures.size, "缺省 true 不撤销：第二次仍记失败")
        assertEquals(engineSecond.failures.size, benchSecond.failures.size)
        assertEquals(0, pipeline.retiredSlotCount)
        assertEquals(0, bench.retiredSlotCount)
        assertTrue(bench.logEntries.none { it.message.contains("槽已撤销") }, "缺省 true 不落撤销 WARN")
        assertEquals(2, bench.logEntries.size, "两次失败各落一条隔离 WARN")
        assertTrue(bench.logEntries.all { it.message.contains("已隔离") }, "隔离 WARN 与撤销 WARN 不互相冒充")
    }

    /** 一致性：撤销只影响本槽——同包另一相位与其它包照跑，不扩散。 */
    @Test
    fun `hooks 撤销只影响本槽 引擎与台架一致`() {
        val declarations = listOf(
            failureHookDecl("pkgA", options = HookFailureOptions(continueOnHandlerFailure = false)),
            hookDecl("pkgA", phase = EngineHookPhase.POSTFIX),
            hookDecl("pkgB"),
        )
        val pipeline = HookPipeline.of(declarations)
        val bench = HookBench(declarations)
        val engineSeen = ArrayList<String>()
        val benchSeen = ArrayList<String>()
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") { throw ParityRuntimeBoomException() }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgA") {
            engineSeen += "post-A"
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgB") {
            engineSeen += "prefix-B"
            HookResult.Continue
        }
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler { throw ParityRuntimeBoomException() })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgA", HookHandler {
            benchSeen += "post-A"
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgB", HookHandler {
            benchSeen += "prefix-B"
            HookResult.Continue
        })

        pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        bench.dispatchWithOriginal(TARGET, emptyArray()) { ModValue.of(1L) }
        engineSeen.clear()
        benchSeen.clear()
        pipeline.dispatch(TARGET, emptyArray()) { ModValue.of(1L) }
        bench.dispatchWithOriginal(TARGET, emptyArray()) { ModValue.of(1L) }

        assertEquals(listOf("prefix-B", "post-A"), engineSeen)
        assertEquals(engineSeen, benchSeen)
        assertTrue(bench.isSlotRetired(TARGET, ModHookPhase.PREFIX, "pkgA"))
        assertFalse(bench.isSlotRetired(TARGET, ModHookPhase.POSTFIX, "pkgA"))
        assertFalse(bench.isSlotRetired(TARGET, ModHookPhase.PREFIX, "pkgB"))
    }
}

class ModTestingInteractionsParityTest {

    // ------------------------------------------------------------------
    // interactions：真引擎 / 真台架对拍
    // ------------------------------------------------------------------

    @Test
    fun `interactions 五相位执行序 引擎与台架一致`() {
        val declarations = ModInteractionPhase.entries.map {
            interactionHookDecl("pkgA", toEngineInteractionPhase(it))
        }
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        for (phase in ModInteractionPhase.entries) {
            pipeline.registerHook(phase, "pkgA") {
                engineOrder += phase.name
                InteractionResult.CONTINUE
            }
            bench.register(phase, "pkgA", InteractionHandler {
                benchOrder += phase.name
                InteractionResult.CONTINUE
            })
        }

        val engineResult = pipeline.run(interactionContext()) {
            engineOrder += "MAIN_BODY"
            ModValue.of(1L)
        }
        val benchResult = bench.run(interactionContext()) {
            benchOrder += "MAIN_BODY"
            ModValue.of(1L)
        }

        val expected = listOf("PRECONDITION", "PRE", "MAIN", "MAIN_BODY", "POST", "SETTLEMENT")
        assertEquals(expected, engineOrder)
        assertEquals(engineOrder, benchOrder, "引擎与台架相位序一致")
        assertEquals(engineResult.returnValue, benchResult.returnValue)
        assertFalse(engineResult.interrupted)
        assertFalse(benchResult.interrupted)
    }

    @Test
    fun `interactions INTERRUPT 中止后续 引擎与台架一致`() {
        val declarations = listOf(
            interactionHookDecl("pkgA", EngineInteractionPhase.PRECONDITION),
            interactionHookDecl("pkgA", EngineInteractionPhase.POST),
        )
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.registerHook(ModInteractionPhase.PRECONDITION, "pkgA") {
            engineOrder += "PRE"
            InteractionResult.INTERRUPT
        }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") {
            engineOrder += "POST"
            InteractionResult.CONTINUE
        }
        bench.register(ModInteractionPhase.PRECONDITION, "pkgA", InteractionHandler {
            benchOrder += "PRE"
            InteractionResult.INTERRUPT
        })
        bench.register(ModInteractionPhase.POST, "pkgA", InteractionHandler {
            benchOrder += "POST"
            InteractionResult.CONTINUE
        })

        val engineResult = pipeline.run(interactionContext()) {
            engineOrder += "MAIN_BODY"
            ModValue.of(1L)
        }
        val benchResult = bench.run(interactionContext()) {
            benchOrder += "MAIN_BODY"
            ModValue.of(1L)
        }

        assertEquals(listOf("PRE"), engineOrder)
        assertEquals(engineOrder, benchOrder)
        assertTrue(engineResult.interrupted)
        assertTrue(benchResult.interrupted)
        assertNull(engineResult.returnValue)
        assertNull(benchResult.returnValue)
    }

    @Test
    fun `interactions PRE REPLACE 跳过主流程但跑 POST 与 SETTLEMENT 引擎与台架一致`() {
        val declarations = listOf(
            interactionHookDecl("pkgA", EngineInteractionPhase.PRE),
            interactionHookDecl("pkgA", EngineInteractionPhase.MAIN),
            interactionHookDecl("pkgA", EngineInteractionPhase.POST),
            interactionHookDecl("pkgA", EngineInteractionPhase.SETTLEMENT),
        )
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { ctx ->
            ctx.replacedResult = ModValue.of(42L)
            InteractionResult.REPLACE
        }
        pipeline.registerHook(ModInteractionPhase.MAIN, "pkgA") {
            engineOrder += "MAIN_HOOK"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") {
            engineOrder += "POST"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.SETTLEMENT, "pkgA") {
            engineOrder += "SETTLE"
            InteractionResult.CONTINUE
        }
        bench.register(ModInteractionPhase.PRE, "pkgA", InteractionHandler { ctx ->
            ctx.replacedResult = ModValue.of(42L)
            InteractionResult.REPLACE
        })
        bench.register(ModInteractionPhase.MAIN, "pkgA", InteractionHandler {
            benchOrder += "MAIN_HOOK"
            InteractionResult.CONTINUE
        })
        bench.register(ModInteractionPhase.POST, "pkgA", InteractionHandler {
            benchOrder += "POST"
            InteractionResult.CONTINUE
        })
        bench.register(ModInteractionPhase.SETTLEMENT, "pkgA", InteractionHandler {
            benchOrder += "SETTLE"
            InteractionResult.CONTINUE
        })

        val engineResult = pipeline.run(interactionContext()) {
            engineOrder += "MAIN_BODY"
            ModValue.of(1L)
        }
        val benchResult = bench.run(interactionContext()) {
            benchOrder += "MAIN_BODY"
            ModValue.of(1L)
        }

        assertEquals(listOf("POST", "SETTLE"), engineOrder)
        assertEquals(engineOrder, benchOrder)
        assertEquals(ModValue.of(42L), engineResult.returnValue)
        assertEquals(engineResult.returnValue, benchResult.returnValue)
        assertTrue(engineResult.replaced)
        assertTrue(benchResult.replaced)
    }

    @Test
    fun `interactions POST REPLACE 改写结果 引擎与台架一致`() {
        val declarations = listOf(interactionHookDecl("pkgA", EngineInteractionPhase.POST))
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") { ctx ->
            ctx.replacedResult = ModValue.of(88L)
            InteractionResult.REPLACE
        }
        bench.register(ModInteractionPhase.POST, "pkgA", InteractionHandler { ctx ->
            ctx.replacedResult = ModValue.of(88L)
            InteractionResult.REPLACE
        })

        val engineResult = pipeline.run(interactionContext()) { ModValue.of(1L) }
        val benchResult = bench.run(interactionContext()) { ModValue.of(1L) }

        assertEquals(ModValue.of(88L), engineResult.returnValue)
        assertEquals(engineResult.returnValue, benchResult.returnValue)
        assertTrue(engineResult.replaced)
        assertTrue(benchResult.replaced)
    }

    @Test
    fun `interactions 深度上限 引擎与台架都拒绝且栈恢复`() {
        lateinit var pipeline: InteractionPipeline
        pipeline = InteractionPipeline.of(listOf(interactionHookDecl("pkgA", EngineInteractionPhase.PRE)))
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            pipeline.run(interactionContext("DEPTH-${pipeline.depth()}")) { ModValue.of(0L) }
            InteractionResult.CONTINUE
        }
        val engineError = assertFailsWith<IllegalStateException> {
            pipeline.run(interactionContext("ROOT")) { ModValue.of(1L) }
        }
        assertEquals(0, pipeline.depth(), "引擎：超限异常后栈恢复")
        assertNull(pipeline.current())

        val bench = InteractionBench(listOf(interactionHookDecl("pkgA", EngineInteractionPhase.PRE)))
        bench.register(ModInteractionPhase.PRE, "pkgA", InteractionHandler {
            bench.run(interactionContext("DEPTH-${bench.depth}")) { ModValue.of(0L) }
            InteractionResult.CONTINUE
        })
        val benchError = assertFailsWith<IllegalStateException> {
            bench.run(interactionContext("ROOT")) { ModValue.of(1L) }
        }
        assertEquals(0, bench.depth, "台架：超限异常后栈恢复")
        assertEquals("互动嵌套深度超限", engineError.message.orEmpty().substringBefore("："), "引擎深度闸文案")
        assertTrue(benchError.message.orEmpty().contains("32"), "台架：深度闸报出上限 ${benchError.message}")
    }

    @Test
    fun `interactions ISE 与 IAE 隔离 引擎与台架一致`() {
        val declarations = listOf(
            interactionHookDecl("pkgA", EngineInteractionPhase.POST),
            interactionHookDecl("pkgB", EngineInteractionPhase.POST),
        )
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") { error("boom") }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgB") {
            engineOrder += "pkgB"
            InteractionResult.CONTINUE
        }
        bench.register(ModInteractionPhase.POST, "pkgA", InteractionHandler { error("boom") })
        bench.register(ModInteractionPhase.POST, "pkgB", InteractionHandler {
            benchOrder += "pkgB"
            InteractionResult.CONTINUE
        })

        val engineResult = pipeline.run(interactionContext()) { ModValue.of(5L) }
        val benchResult = bench.run(interactionContext()) { ModValue.of(5L) }

        assertEquals(listOf("pkgB"), engineOrder)
        assertEquals(engineOrder, benchOrder)
        assertEquals(listOf("pkgA"), engineResult.failures)
        assertEquals(engineResult.failures, benchResult.failures)
        assertEquals(ModValue.of(5L), engineResult.returnValue)
        assertEquals(engineResult.returnValue, benchResult.returnValue)
    }

    @Test
    fun `interactions 跨包同相位按声明序 引擎与台架一致`() {
        // pkgB 先声明、pkgA 后声明；注册顺序反过来，用来证「按声明序而非注册序」。
        val declarations = listOf(
            interactionHookDecl("pkgB", EngineInteractionPhase.PRE),
            interactionHookDecl("pkgA", EngineInteractionPhase.PRE),
        )
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") {
            engineOrder += "pkgA"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgB") {
            engineOrder += "pkgB"
            InteractionResult.CONTINUE
        }
        bench.register(ModInteractionPhase.PRE, "pkgA", InteractionHandler {
            benchOrder += "pkgA"
            InteractionResult.CONTINUE
        })
        bench.register(ModInteractionPhase.PRE, "pkgB", InteractionHandler {
            benchOrder += "pkgB"
            InteractionResult.CONTINUE
        })

        pipeline.run(interactionContext()) { ModValue.of(1L) }
        bench.run(interactionContext()) { ModValue.of(1L) }

        assertEquals(listOf("pkgB", "pkgA"), engineOrder, "引擎：同相位跨包按声明序")
        assertEquals(engineOrder, benchOrder)
    }

    // ------------------------------------------------------------------
    // interactions：异常隔离面（Lane AB 已在台架侧收敛，断言一致）
    // ------------------------------------------------------------------

    /**
     * 一致性：`RuntimeException` 也按 Mod 隔离——失败记包名、不中断后续处理器，返回值照常结算。
     *
     * 证据：引擎 `InteractionPipeline.kt:167-190`（非深度超限的 Throwable → failures + CONTINUE）；
     * 台架 `RecordingInteractions` 的 `PhaseRunner.invoke` 同口径（任意 Throwable）。
     */
    @Test
    fun `interactions 任意 RuntimeException 隔离 引擎与台架一致`() {
        val declarations = listOf(
            interactionHookDecl("pkgA", EngineInteractionPhase.POST),
            interactionHookDecl("pkgB", EngineInteractionPhase.POST),
        )
        val pipeline = InteractionPipeline.of(declarations)
        pipeline.registerHook(ModInteractionPhase.POST, "pkgA") { throw ParityRuntimeBoomException() }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgB") { InteractionResult.CONTINUE }
        val engineResult = pipeline.run(interactionContext()) { ModValue.of(5L) }
        assertEquals(listOf("pkgA"), engineResult.failures, "引擎：RuntimeException 也按 Mod 隔离")
        assertEquals(ModValue.of(5L), engineResult.returnValue)

        val bench = InteractionBench(declarations)
        val benchOrder = ArrayList<String>()
        bench.register(ModInteractionPhase.POST, "pkgA", InteractionHandler { throw ParityRuntimeBoomException() })
        bench.register(ModInteractionPhase.POST, "pkgB", InteractionHandler {
            benchOrder += "pkgB"
            InteractionResult.CONTINUE
        })
        val benchResult = bench.run(interactionContext()) { ModValue.of(5L) }
        assertEquals(engineResult.failures, benchResult.failures, "台架：同样点名被隔离的包")
        assertEquals(listOf("pkgB"), benchOrder, "台架：隔离后后续处理器照跑")
        assertEquals(engineResult.returnValue, benchResult.returnValue)
        assertFalse(benchResult.interrupted)
    }

    /**
     * 一致性：`Error`（非 Exception）也被隔离——隔离面是 `Throwable`，且失败不中断后续相位。
     *
     * 证据：引擎 `InteractionPipeline.kt:186-190`（`catch (t: Throwable)`）；台架同口径。
     */
    @Test
    fun `interactions Error 也隔离且不中断后续相位 引擎与台架一致`() {
        val declarations = listOf(
            interactionHookDecl("pkgA", EngineInteractionPhase.PRE),
            interactionHookDecl("pkgB", EngineInteractionPhase.MAIN),
            interactionHookDecl("pkgC", EngineInteractionPhase.POST),
        )
        val pipeline = InteractionPipeline.of(declarations)
        val bench = InteractionBench(declarations)
        val engineOrder = ArrayList<String>()
        val benchOrder = ArrayList<String>()
        pipeline.registerHook(ModInteractionPhase.PRE, "pkgA") { throw AssertionError("error-boom") }
        pipeline.registerHook(ModInteractionPhase.MAIN, "pkgB") {
            engineOrder += "MAIN"
            InteractionResult.CONTINUE
        }
        pipeline.registerHook(ModInteractionPhase.POST, "pkgC") {
            engineOrder += "POST"
            InteractionResult.CONTINUE
        }
        bench.register(ModInteractionPhase.PRE, "pkgA", InteractionHandler { throw AssertionError("error-boom") })
        bench.register(ModInteractionPhase.MAIN, "pkgB", InteractionHandler {
            benchOrder += "MAIN"
            InteractionResult.CONTINUE
        })
        bench.register(ModInteractionPhase.POST, "pkgC", InteractionHandler {
            benchOrder += "POST"
            InteractionResult.CONTINUE
        })

        val engineResult = pipeline.run(interactionContext()) {
            engineOrder += "MAIN_BODY"
            ModValue.of(5L)
        }
        val benchResult = bench.run(interactionContext()) {
            benchOrder += "MAIN_BODY"
            ModValue.of(5L)
        }

        assertEquals(listOf("MAIN", "MAIN_BODY", "POST"), engineOrder, "引擎：Error 不中断后续相位")
        assertEquals(engineOrder, benchOrder, "台架：隔离面同为 Throwable")
        assertEquals(listOf("pkgA"), engineResult.failures)
        assertEquals(engineResult.failures, benchResult.failures)
        assertEquals(engineResult.returnValue, benchResult.returnValue)
    }
}

// ------------------------------------------------------------------
// 夹具辅助
// ------------------------------------------------------------------

private fun mainMeta(): FuncMeta = FuncMeta(index = 0, key = "MAIN", name = "main")

private fun eventMeta(
    index: Int,
    isPri: Boolean = false,
    isLater: Boolean = false,
    isSingle: Boolean = false,
): FuncMeta = FuncMeta(
    index = index,
    key = EVENT_KEY,
    name = "ev$index",
    isEvent = true,
    isPri = isPri,
    isLater = isLater,
    isSingle = isSingle,
    defIndex = index,
)

private fun mixedMetas(): List<FuncMeta> = listOf(
    mainMeta(),
    eventMeta(1, isPri = true),
    eventMeta(2, isPri = true),
    eventMeta(3),
    eventMeta(4, isLater = true),
)

private fun recordTo(
    record: MutableList<String>,
    label: String,
    result: EventResult = EventResult.CONTINUE,
): EventHandler = EventHandler {
    record += label
    result
}

private fun recordHook(record: MutableList<String>, pkg: String): HookHandler = HookHandler {
    record += pkg
    HookResult.Continue
}

private fun hookReplace(record: MutableList<String>, pkg: String, value: ModValue): HookHandler = HookHandler {
    record += pkg
    HookResult.Replace(value)
}

private fun hookSkip(record: MutableList<String>, pkg: String): HookHandler = HookHandler {
    record += pkg
    HookResult.SkipOriginal
}

private fun mutateArgHandler(value: Long): HookHandler = HookHandler { ctx ->
    ctx.args[0].intValue = value
    HookResult.Continue
}

private fun hookDecl(
    pkg: String,
    phase: EngineHookPhase = EngineHookPhase.PREFIX,
    priority: Int = 0,
    mayMutateArgs: Boolean = false,
    mayMutateReturn: Boolean = false,
): DeclaredHook = DeclaredHook(
    packageId = pkg,
    declaration = HookDeclaration(
        targetKey = TARGET,
        phase = phase,
        priority = priority,
        mayMutateArgs = mayMutateArgs,
        mayMutateReturn = mayMutateReturn,
    ),
)

/** 失败轴开关（detekt 参数上限 6）：`runOnFailure` 管原函数异常补跑、`continueOnHandlerFailure` 管槽撤销。 */
private data class HookFailureOptions(
    val runOnFailure: Boolean = false,
    val continueOnHandlerFailure: Boolean = true,
)

private fun failureHookDecl(
    pkg: String,
    phase: EngineHookPhase = EngineHookPhase.PREFIX,
    priority: Int = 0,
    options: HookFailureOptions = HookFailureOptions(),
): DeclaredHook = DeclaredHook(
    packageId = pkg,
    declaration = HookDeclaration(
        targetKey = TARGET,
        phase = phase,
        priority = priority,
        runOnFailure = options.runOnFailure,
        continueOnHandlerFailure = options.continueOnHandlerFailure,
    ),
)

private fun interactionHookDecl(
    pkg: String,
    phase: EngineInteractionPhase,
    priority: Int = 0,
): DeclaredInteractionHook = DeclaredInteractionHook(
    packageId = pkg,
    declaration = InteractionHookDeclaration(phase = phase, priority = priority),
)

private fun interactionContext(id: String = "INT-1"): InteractionContext = InteractionContext(
    interactionId = id,
    actor = EntityId(1L),
    targets = listOf(EntityId(2L), EntityId(3L)),
    primaryTarget = EntityId(2L),
)

private const val TARGET = "COM300"
private const val EVENT_KEY = "EVT"

private val mixedMarkers = listOf("p0", "p1", "n0", "l0")

fun toModHookPhase(phase: EngineHookPhase): ModHookPhase = when (phase) {
    EngineHookPhase.PREFIX -> ModHookPhase.PREFIX
    EngineHookPhase.POSTFIX -> ModHookPhase.POSTFIX
}

fun toModInteractionPhase(phase: EngineInteractionPhase): ModInteractionPhase = when (phase) {
    EngineInteractionPhase.PRECONDITION -> ModInteractionPhase.PRECONDITION
    EngineInteractionPhase.PRE -> ModInteractionPhase.PRE
    EngineInteractionPhase.MAIN -> ModInteractionPhase.MAIN
    EngineInteractionPhase.POST -> ModInteractionPhase.POST
    EngineInteractionPhase.SETTLEMENT -> ModInteractionPhase.SETTLEMENT
}

fun toEngineInteractionPhase(phase: ModInteractionPhase): EngineInteractionPhase = when (phase) {
    ModInteractionPhase.PRECONDITION -> EngineInteractionPhase.PRECONDITION
    ModInteractionPhase.PRE -> EngineInteractionPhase.PRE
    ModInteractionPhase.MAIN -> EngineInteractionPhase.MAIN
    ModInteractionPhase.POST -> EngineInteractionPhase.POST
    ModInteractionPhase.SETTLEMENT -> EngineInteractionPhase.SETTLEMENT
}

/**
 * 台架侧「非 ISE/IAE」的那类异常：用具体异常类型代替 `RuntimeException`（detekt 禁泛型异常）。
 * Lane AB 用它证明隔离面已扩到任意 Throwable：hooks 串联链与 interactions 两侧都点名隔离它。
 */
private class ParityRuntimeBoomException : RuntimeException("runtime-boom")

// ----------------------------------------------------------------------
// 真台架夹具：TestWorld + 三面 recorder（替代原先的 Mirror* 逐行转写）
// ----------------------------------------------------------------------

/**
 * events 台架：`RecordingEvents` 的桶表由 `TestWorld` 持有、跨 context 共享，这里按包名发 context
 * 只为复刻「处理器的 packageId 归属」。
 */
private class EventBench {

    private val world: TestWorld = TestWorld.ofManifests()
    private val contexts = LinkedHashMap<String, TestContext>()
    private val dispatcherPackageId = "mod.a"

    private fun contextOf(packageId: String): TestContext =
        contexts.getOrPut(packageId) { world.newContext(packageId) }

    fun register(eventKey: String, bucket: EventBucket, packageId: String, handler: EventHandler) {
        contextOf(packageId).events.register(eventKey, bucket, handler)
    }

    /**
     * 注入构建期事件名清单（引擎 `EventDispatchRegistry.adoptKnownEvents` 同口径）；注入后拼错名
     * 在注册当场被拒并给最近候选。未注入 = 不拦（引擎缺省空集）。
     */
    fun adoptKnownEvents(keys: Set<String>) {
        contextOf(dispatcherPackageId).eventsRecorder.adoptKnownEvents(keys)
    }

    fun fireAll(eventKey: String): List<EventResult> = contextOf(dispatcherPackageId).dispatchEvent(eventKey)

    /**
     * 会话级诊断日志（`TestWorld.logEntries`）：异常隔离的 WARN 落在这里，跨 context 共享，
     * 与生产会话 logSink 同位（按包日志仍走 `TestContext.logEntries`）。
     */
    val logEntries: List<LogEntry> get() = world.logEntries
}

/**
 * hooks 台架：按引擎侧 [DeclaredHook] 逐条投影成 mod-api manifest 的 `hook(...)` 声明，再建
 * [TestWorld]，用 `hooksRecorder.dispatch` 驱动。
 *
 * 声明序口径：`MemorySchema` 的 `declarationOrder` = manifest 迭代序 + manifest 内 hook 序，因此这里
 * 按「包首次出现序」分组建 manifest；同包内声明序逐条保留。对拍用例的声明本就按包连续排列，
 * 跨包交错的同相位声明不在当前覆盖面内（后续若要覆盖，需台架 schema 支持全局序入参）。
 */
private class HookBench(declarations: List<DeclaredHook>) {

    private val world: TestWorld = TestWorld.ofManifests(*hookManifestsOf(declarations).toTypedArray())
    private val contexts = LinkedHashMap<String, TestContext>()
    private val dispatcherPackageId = declarations.firstOrNull()?.packageId ?: BENCH_EMPTY_PACKAGE_ID

    private fun contextOf(packageId: String): TestContext =
        contexts.getOrPut(packageId) { world.newContext(packageId) }

    fun register(targetKey: String, phase: ModHookPhase, packageId: String, handler: HookHandler) {
        contextOf(packageId).hooks.register(targetKey, phase, handler)
    }

    fun dispatch(targetKey: String, phase: ModHookPhase, args: Array<ModCallArg>): HookResult =
        contextOf(dispatcherPackageId).hooksRecorder.dispatch(targetKey, phase, args)

    /** 带初始返回值的相位级派发（postfix 改值面）；同引擎 `HookPipeline.dispatch` 的 after 段。 */
    fun dispatchChain(
        targetKey: String,
        phase: ModHookPhase,
        args: Array<ModCallArg>,
        returnValue: ModValue?,
    ): RecordingHooks.HookChainResult =
        contextOf(dispatcherPackageId).hooksRecorder.dispatchChain(targetKey, phase, args, returnValue)

    /**
     * 完整串联链（同引擎 `HookPipeline.dispatch`）：PREFIX → 原函数 → POSTFIX，Replace 短路、
     * SkipOriginal 只跳原函数、`mayMutateReturn` 只在 POSTFIX 生效。
     */
    fun dispatchWithOriginal(
        targetKey: String,
        args: Array<ModCallArg>,
        original: () -> ModValue?,
    ): RecordingHooks.HookChainOutcome =
        contextOf(dispatcherPackageId).hooksRecorder.dispatchWithOriginal(targetKey, args, original)

    /** 会话级诊断日志（撤销 WARN 等），同 `TestWorld.logEntries`。 */
    val logEntries: List<LogEntry> get() = world.logEntries

    /** 已撤销槽计数（对应引擎 `HookPipeline.retiredSlotCount`）。 */
    val retiredSlotCount: Int get() = contextOf(dispatcherPackageId).retiredHookSlots

    fun isSlotRetired(targetKey: String, phase: ModHookPhase, packageId: String): Boolean =
        contextOf(packageId).isHookSlotRetired(targetKey, phase, packageId)
}

/**
 * hooks 串联链夹具：声明 2 个 PREFIX + 2 个 POSTFIX 槽（覆盖授权/未授权实参与返回值），
 * 引擎与台架各注册一组同名处理器；记录执行序、处理器观察值与原函数看到的实参。
 */
private class HookChainFixture {

    private val declarations = listOf(
        hookDecl("pkgA", priority = 0),
        hookDecl("pkgB", priority = 1, mayMutateArgs = true),
        hookDecl("pkgC", phase = EngineHookPhase.POSTFIX, priority = 0, mayMutateReturn = true),
        hookDecl("pkgD", phase = EngineHookPhase.POSTFIX, priority = 1),
    )

    private val pipeline: HookPipeline = HookPipeline.of(declarations)
    private val bench: HookBench = HookBench(declarations)
    val engineOrder = ArrayList<String>()
    val benchOrder = ArrayList<String>()
    val engineSeen = ArrayList<ModValue?>()
    val benchSeen = ArrayList<ModValue?>()
    private val engineArgs = arrayOf(ModCallArg(isString = false, intValue = 7L))
    private val benchArgs = arrayOf(ModCallArg(isString = false, intValue = 7L))

    init {
        bindEngine()
        bindBench()
    }

    private fun bindEngine() {
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgA") {
            engineOrder += "A"
            it.args[0].intValue = 1L
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.PREFIX, "pkgB") {
            engineOrder += "B"
            engineSeen += ModValue.of(it.args[0].intValue)
            it.args[0].intValue = 42L
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgC") {
            engineOrder += "C"
            engineSeen += it.returnValue
            it.returnValue = ModValue.of(42L)
            HookResult.Continue
        }
        pipeline.bind(TARGET, ModHookPhase.POSTFIX, "pkgD") {
            engineOrder += "D"
            it.returnValue = ModValue.of(999L)
            HookResult.Continue
        }
    }

    private fun bindBench() {
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgA", HookHandler {
            benchOrder += "A"
            it.args[0].intValue = 1L
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.PREFIX, "pkgB", HookHandler {
            benchOrder += "B"
            benchSeen += ModValue.of(it.args[0].intValue)
            it.args[0].intValue = 42L
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgC", HookHandler {
            benchOrder += "C"
            benchSeen += it.returnValue
            it.returnValue = ModValue.of(42L)
            HookResult.Continue
        })
        bench.register(TARGET, ModHookPhase.POSTFIX, "pkgD", HookHandler {
            benchOrder += "D"
            it.returnValue = ModValue.of(999L)
            HookResult.Continue
        })
    }

    fun dispatchEngine(): HookPipeline.HookOutcome = pipeline.dispatch(TARGET, engineArgs) {
        engineOrder += "ORIGINAL"
        assertEquals(42L, engineArgs[0].intValue, "引擎：授权槽改的实参回传给原函数")
        ModValue.of(7L)
    }

    fun dispatchBench(): RecordingHooks.HookChainOutcome = bench.dispatchWithOriginal(TARGET, benchArgs) {
        benchOrder += "ORIGINAL"
        assertEquals(42L, benchArgs[0].intValue, "台架：授权槽改的实参回传给原函数")
        ModValue.of(7L)
    }

    fun engineArgValue(): Long = engineArgs[0].intValue

    fun benchArgValue(): Long = benchArgs[0].intValue
}

private fun hookManifestsOf(declarations: List<DeclaredHook>): List<ModManifest> {
    val byPackage = LinkedHashMap<String, MutableList<DeclaredHook>>()
    for (declared in declarations) byPackage.getOrPut(declared.packageId) { mutableListOf() } += declared
    return byPackage.map { (packageId, hooks) ->
        modDeclarations(packageId) {
            for (declared in hooks) {
                hook(
                    FunctionKey(declared.declaration.targetKey),
                    toModHookPhase(declared.declaration.phase),
                    HookOptions(
                        priority = declared.declaration.priority,
                        mayMutateArgs = declared.declaration.mayMutateArgs,
                        mayMutateReturn = declared.declaration.mayMutateReturn,
                        runOnFailure = declared.declaration.runOnFailure,
                        continueOnHandlerFailure = declared.declaration.continueOnHandlerFailure,
                    ),
                )
            }
        }
    }
}

/** interactions 台架：同 [HookBench]，声明面换成 `interactionHook(...)`。 */
private class InteractionBench(declarations: List<DeclaredInteractionHook>) {

    private val world: TestWorld = TestWorld.ofManifests(*interactionManifestsOf(declarations).toTypedArray())
    private val contexts = LinkedHashMap<String, TestContext>()
    private val dispatcherPackageId = declarations.first().packageId

    private val recorder: RecordingInteractions
        get() = contextOf(dispatcherPackageId).interactionsRecorder

    val depth: Int get() = recorder.depth

    private fun contextOf(packageId: String): TestContext =
        contexts.getOrPut(packageId) { world.newContext(packageId) }

    fun register(phase: ModInteractionPhase, packageId: String, handler: InteractionHandler) {
        contextOf(packageId).interactions.registerHook(phase, handler)
    }

    fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult = recorder.run(context, main)
}

private fun interactionManifestsOf(declarations: List<DeclaredInteractionHook>): List<ModManifest> {
    val byPackage = LinkedHashMap<String, MutableList<DeclaredInteractionHook>>()
    for (declared in declarations) byPackage.getOrPut(declared.packageId) { mutableListOf() } += declared
    return byPackage.map { (packageId, hooks) ->
        modDeclarations(packageId) {
            for (declared in hooks) {
                interactionHook(
                    toModInteractionPhase(declared.declaration.phase),
                    declared.declaration.priority,
                )
            }
        }
    }
}

private const val BENCH_EMPTY_PACKAGE_ID = "bench.empty"

// ----------------------------------------------------------------------
// 真引擎 events 夹具（照 core/proc/EventWiringTest.kt 的 WiringHarness 转写：
// 真 VariableStore + Assembler 剧本 + 生产 LSC 派生四桶表 + 真 ProcessRuntime）
// ----------------------------------------------------------------------

private class EventEngineHarness(
    markers: List<String>,
    metas: List<FuncMeta>,
    singleAt: Set<Int> = emptySet(),
) {

    val store = VariableStore()
    val logs = ArrayList<String>()
    val calls = ArrayList<String>()

    private val program: Program = Assembler.assemble(sourceOf(markers, singleAt))

    private val registry: EventRegistry = EventRegistry.of(
        SymbolCatalog.build(
            metas.map { SymbolCandidate(meta = it, ownerPackageId = BASE_OWNER) },
            ResolverPolicy.LEGACY_LAST_WINS,
        ),
    )

    val runtime: ProcessRuntime = ProcessRuntime(
        program,
        store,
        FunctionTable(metas),
        VmConfig(),
        eventRegistry = registry,
    )

    val output: String get() = runtime.output.toString()

    fun handlers(): EventDispatchRegistry = EventDispatchRegistry()

    fun attach(registry: EventDispatchRegistry) = ModEventWiring.install(store, registry, logs::add)

    fun runEvent(key: String = EVENT_KEY): Boolean = runtime.executeEvent(key)

    fun handler(label: String, result: EventResult = EventResult.CONTINUE): EventHandler = EventHandler {
        calls += label
        result
    }

    private companion object {
        const val EVENT_KEY = "EVT"
        const val BASE_OWNER = "__base__"

        fun sourceOf(markers: List<String>, singleAt: Set<Int>): String = buildString {
            append(".func main slots=1\n")
            append("  _HALT\n")
            for ((i, marker) in markers.withIndex()) {
                append(".func ev${i + 1} slots=2\n")
                append("  _PRINT_LITERAL \"$marker\", 1\n")
                if (i in singleAt) {
                    append("  _SET_INT R0, 1\n")
                    append("  _RET R0\n")
                } else {
                    append("  _RET\n")
                }
            }
        }
    }
}
