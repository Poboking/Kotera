package com.eratw.port.core.mod

import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.FunctionKey
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookOptions
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modtesting.LogEntry
import com.eratw.port.modtesting.RecordingHooks
import com.eratw.port.modtesting.TestContext
import com.eratw.port.modtesting.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eratw.port.core.patch.HookPhase as EngineHookPhase
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * Lane AI：台架失败面收尾（Lane AD 遗留三条缺口的对拍归零）。
 *
 * Lane AD 已让台架长齐 `runOnFailure` / `continueOnHandlerFailure` 两轴语义，但留下三处
 * 只做了单侧、未对拍的边界：
 *
 * 1. **撤销 WARN 文案边界**：生产 `FunctionHookWiring.reportFailures` 的日志面只有「隔离行」，
 *    撤销写在 `RuntimeDiagnostics`（`HANDLER_THREW_SLOT_RETIRED`）；台架日志面多一条「撤销行」。
 *    本文件钉死：隔离行必须与生产逐字一致（台架只少 `[packageId][WARN] ` 前缀），撤销行台架独有，
 *    两条 WARN 各自存在、不互相冒充；
 * 2. **相位级 `dispatchChain` 的补跑段**：生产 `HookPipeline.runFailurePostfix` 在原函数抛异常后
 *    只跑 POSTFIX 且声明 `runOnFailure` 的槽；台架 `dispatchChain(originalThrew = true)` 必须同构；
 * 3. **撤销诊断与日志面联动**：引擎两条诊断（`HANDLER_THREW` + `HANDLER_THREW_SLOT_RETIRED`）各一条、
 *    台架两条 WARN（隔离 + 撤销）各一条，且撤销后二次派发两侧都不再产生新记录。
 *
 * 对拍口径同 `ModTestingParityTest`：同一编排分别跑真引擎（`HookPipeline` + `FunctionHookWiring`）
 * 与真台架（`TestWorld.newContext` → `hooksRecorder`），断言执行序 / 字符串 / 计数一致。
 */
class HookFailureLogParityTest {
    @Test
    fun `hooks 处理器失败 隔离 WARN 文案 引擎与台架逐字一致`() {
        val declarations = listOf(prefixHook("pkgA"))
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        engine.bind("pkgA", handler = boomHandler())
        bench.bind("pkgA", handler = boomHandler())

        assertEquals(7L, engine.dispatchCall())
        val outcome = bench.dispatchWithOriginal { ModValue.of(7L) }

        assertEquals(listOf("pkgA"), outcome.failures)
        val entry = bench.logEntries.single()
        assertEquals("WARN", entry.level)
        assertEquals("pkgA", entry.packageId)
        assertEquals(
            "hook 处理器异常已隔离 key=$TARGET_KEY phase=PREFIX cause=IllegalStateException: runtime-boom",
            entry.message,
        )
        // 生产日志面 = `[packageId][WARN] ` + 台架 message，逐字一致（只多前缀）。
        assertEquals("[pkgA][WARN] ${entry.message}", engine.logs.single())
    }

    @Test
    fun `hooks 撤销 WARN 与隔离 WARN 各自存在 不互相冒充`() {
        val declarations = listOf(retiredPrefixHook("pkgA"))
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        engine.bind("pkgA", handler = boomHandler())
        bench.bind("pkgA", handler = boomHandler())

        engine.dispatchCall()
        engine.dispatchCall()
        val first = bench.dispatchWithOriginal { ModValue.of(7L) }
        val second = bench.dispatchWithOriginal { ModValue.of(7L) }

        assertEquals(listOf("pkgA"), first.failures)
        assertEquals(emptyList(), second.failures, "撤销后二次派发不再进入处理器")
        assertEquals(2, bench.logEntries.size, "台架日志面：一条隔离行 + 一条撤销行")
        val isolation = bench.logEntries.single { it.message.contains("已隔离") }
        val retire = bench.logEntries.single { it.message.contains("槽已撤销") }
        assertEquals("[pkgA][WARN] ${isolation.message}", engine.logs.single())
        assertTrue(retire.message.contains("continueOnHandlerFailure=false"))
        assertFalse(retire.message.contains("已隔离"), "撤销行不许冒充隔离行")
        assertTrue(engine.logs.none { it.contains("槽已撤销") }, "生产日志面无撤销行（只在诊断环）")
    }

    @Test
    fun `hooks 相位级补跑段 只跑 runOnFailure 的 POSTFIX 槽 引擎与台架一致`() {
        val declarations = listOf(
            postfixHook("pkgA", runOnFailure = true),
            postfixHook("pkgB", runOnFailure = false),
        )
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        val engineRan = mutableListOf<String>()
        val benchRan = mutableListOf<String>()
        engine.bind("pkgA", EngineHookPhase.POSTFIX) { engineRan += "A"; HookResult.Continue }
        engine.bind("pkgB", EngineHookPhase.POSTFIX) { engineRan += "B"; HookResult.Continue }
        bench.bind("pkgA", EngineHookPhase.POSTFIX) { benchRan += "A"; HookResult.Continue }
        bench.bind("pkgB", EngineHookPhase.POSTFIX) { benchRan += "B"; HookResult.Continue }

        val engineError = assertFailsWith<IllegalStateException> {
            engine.dispatchCall { error("original-boom") }
        }
        val chain = bench.dispatchChainPostfix(ModValue.of(7L), originalThrew = true)

        assertEquals("original-boom", engineError.message)
        assertEquals(listOf("A"), engineRan, "只有 runOnFailure=true 的槽进补跑段")
        assertEquals(engineRan, benchRan)
        assertEquals(HookResult.Continue, chain.result)
        assertNull(chain.returnValue, "补跑段结局返回值恒为 null")
        assertFalse(chain.replaced, "补跑段的 Replace 不生效")
    }

    @Test
    fun `hooks 相位级只跑 POSTFIX 引擎与台架派发序一致`() {
        val declarations = listOf(
            postfixHook("pkgA", runOnFailure = true),
            postfixHook("pkgB", runOnFailure = false),
        )
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        val engineRan = mutableListOf<String>()
        val benchRan = mutableListOf<String>()
        engine.bind("pkgA", EngineHookPhase.POSTFIX) { engineRan += "A"; HookResult.Continue }
        engine.bind("pkgB", EngineHookPhase.POSTFIX) { engineRan += "B"; HookResult.Continue }
        bench.bind("pkgA", EngineHookPhase.POSTFIX) { benchRan += "A"; HookResult.Continue }
        bench.bind("pkgB", EngineHookPhase.POSTFIX) { benchRan += "B"; HookResult.Continue }

        assertEquals(7L, engine.dispatchCall())
        val chain = bench.dispatchChainPostfix(ModValue.of(7L), originalThrew = false)

        // 正常路径下 runOnFailure 不生效：所有 POSTFIX 槽照跑。
        assertEquals(listOf("A", "B"), engineRan)
        assertEquals(engineRan, benchRan)
        assertEquals(ModValue.of(7L), chain.returnValue)
    }

    @Test
    fun `hooks 原函数异常照穿 引擎与台架一致`() {
        val declarations = listOf(prefixHook("pkgA"))
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        engine.bind("pkgA") { HookResult.Continue }
        bench.bind("pkgA") { HookResult.Continue }

        val engineError = assertFailsWith<IllegalStateException> {
            engine.dispatchCall { error("original-boom") }
        }
        val benchError = assertFailsWith<IllegalStateException> {
            bench.dispatchWithOriginal { error("original-boom") }
        }

        assertEquals("original-boom", engineError.message)
        assertEquals(engineError.message, benchError.message)
    }

    @Test
    fun `hooks 槽撤销 诊断与日志面联动 引擎与台架一致`() {
        val declarations = listOf(retiredPrefixHook("pkgA"))
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        engine.bind("pkgA", handler = boomHandler())
        bench.bind("pkgA", handler = boomHandler())

        engine.dispatchCall()
        engine.dispatchCall()
        bench.dispatchWithOriginal { ModValue.of(7L) }
        bench.dispatchWithOriginal { ModValue.of(7L) }

        val codes = engine.reasonCodes()
        assertEquals(1, codes.count { it == "HANDLER_THREW" })
        assertEquals(1, codes.count { it == "HANDLER_THREW_SLOT_RETIRED" })
        assertEquals(1, engine.logs.size, "生产日志面只有隔离行，撤销不落日志")
        assertEquals(1, engine.retiredSlotCount)
        assertTrue(engine.isSlotRetired("pkgA"))
        assertEquals(engine.retiredSlotCount, bench.retiredSlotCount)
        assertTrue(bench.isSlotRetired("pkgA"))
        assertEquals(2, bench.logEntries.size)
        assertEquals(1, bench.logEntries.count { it.message.contains("已隔离") })
        assertEquals(1, bench.logEntries.count { it.message.contains("槽已撤销") })
    }

    @Test
    fun `hooks 补跑段的 Replace 结果被丢弃`() {
        val declarations = listOf(postfixHook("pkgA", runOnFailure = true))
        val engine = EngineHooks(declarations)
        val bench = BenchHooks(declarations)
        val engineRan = mutableListOf<String>()
        val benchRan = mutableListOf<String>()
        val replace: HookHandler = HookHandler {
            HookResult.Replace(ModValue.of(99L))
        }
        engine.bind("pkgA", EngineHookPhase.POSTFIX) { engineRan += "A"; replace.onHook(it) }
        bench.bind("pkgA", EngineHookPhase.POSTFIX) { benchRan += "A"; replace.onHook(it) }

        assertFailsWith<IllegalStateException> {
            engine.dispatchCall { error("original-boom") }
        }
        val chain = bench.dispatchChainPostfix(ModValue.of(7L), originalThrew = true)

        assertEquals(listOf("A"), engineRan)
        assertEquals(engineRan, benchRan)
        assertEquals(HookResult.Continue, chain.result, "补跑段固定 Continue，Replace 不短路")
        assertNull(chain.returnValue)
        assertFalse(chain.replaced)
    }
}

private const val TARGET_KEY = "COM300"

/** 失败轴开关：`runOnFailure` 管原函数异常补跑、`continueOnHandlerFailure` 管槽撤销。 */
private data class FailureSwitches(
    val runOnFailure: Boolean = false,
    val continueOnHandlerFailure: Boolean = true,
)

private fun prefixHook(packageId: String): DeclaredHook = declaredHook(packageId, EngineHookPhase.PREFIX)

private fun retiredPrefixHook(packageId: String): DeclaredHook = declaredHook(
    packageId,
    EngineHookPhase.PREFIX,
    FailureSwitches(continueOnHandlerFailure = false),
)

private fun postfixHook(packageId: String, runOnFailure: Boolean): DeclaredHook = declaredHook(
    packageId,
    EngineHookPhase.POSTFIX,
    FailureSwitches(runOnFailure = runOnFailure),
)

private fun declaredHook(
    packageId: String,
    phase: EngineHookPhase,
    switches: FailureSwitches = FailureSwitches(),
): DeclaredHook = DeclaredHook(
    packageId = packageId,
    declaration = HookDeclaration(
        targetKey = TARGET_KEY,
        phase = phase,
        runOnFailure = switches.runOnFailure,
        continueOnHandlerFailure = switches.continueOnHandlerFailure,
    ),
)

private fun boomHandler(): HookHandler = HookHandler { error("runtime-boom") }

/** 引擎侧：真 `HookPipeline` + `FunctionHookWiring`（日志面 WARN 由后者投影）。 */
private class EngineHooks(declarations: List<DeclaredHook>) {
    private val diagnostics = RuntimeDiagnostics(capacity = 16)
    private val pipeline = HookPipeline.of(declarations, diagnostics)
    private val store = VariableStore()
    val logs: MutableList<String> = mutableListOf()

    init {
        FunctionHookWiring.install(store, pipeline) { message -> logs += message }
    }

    fun bind(pkg: String, phase: EngineHookPhase = EngineHookPhase.PREFIX, handler: HookHandler) {
        pipeline.bind(TARGET_KEY, toModHookPhase(phase), pkg, handler)
    }

    fun dispatchCall(original: () -> Long = { 7L }): Long {
        val value = FunctionHookWiring.dispatchCall(
            store, TARGET_KEY, LongArray(0), emptyArray(), strRet = false,
        ) { _, _ -> ModValue.IntValue(original()) }
        return (value as ModValue.IntValue).value
    }

    fun reasonCodes(): List<String> = diagnostics.snapshot().map { it.reasonCode }

    val retiredSlotCount: Int get() = pipeline.retiredSlotCount

    fun isSlotRetired(pkg: String, phase: EngineHookPhase = EngineHookPhase.PREFIX): Boolean =
        pipeline.isSlotRetired(TARGET_KEY, toModHookPhase(phase), pkg)
}

/** 台架侧：真 `TestWorld` + `hooksRecorder`（日志面 WARN 落会话日志）。 */
private class BenchHooks(declarations: List<DeclaredHook>) {
    private val world = TestWorld.ofManifests(*benchManifests(declarations).toTypedArray())
    private val dispatcherPackageId = declarations.first().packageId
    private val contexts = LinkedHashMap<String, TestContext>()
    val logEntries: List<LogEntry> get() = world.logEntries
    val retiredSlotCount: Int get() = recorder().retiredSlotCount

    private fun contextOf(packageId: String): TestContext =
        contexts.getOrPut(packageId) { world.newContext(packageId) }

    private fun recorder(): RecordingHooks = contextOf(dispatcherPackageId).hooksRecorder

    fun bind(pkg: String, phase: EngineHookPhase = EngineHookPhase.PREFIX, handler: HookHandler) {
        contextOf(pkg).hooks.register(TARGET_KEY, toModHookPhase(phase), handler)
    }

    fun dispatchWithOriginal(original: () -> ModValue?): RecordingHooks.HookChainOutcome =
        recorder().dispatchWithOriginal(TARGET_KEY, emptyArray(), original)

    fun dispatchChainPostfix(returnValue: ModValue?, originalThrew: Boolean): RecordingHooks.HookChainResult =
        recorder().dispatchChain(TARGET_KEY, ModHookPhase.POSTFIX, emptyArray(), returnValue, originalThrew)

    fun isSlotRetired(pkg: String, phase: EngineHookPhase = EngineHookPhase.PREFIX): Boolean =
        recorder().isSlotRetired(TARGET_KEY, toModHookPhase(phase), pkg)
}

/** 引擎声明 → 台架 `ModManifest`（按包分组；两轴开关逐条透传）。 */
private fun benchManifests(declarations: List<DeclaredHook>): List<ModManifest> =
    declarations.groupBy { it.packageId }.map { (packageId, declaredList) ->
        modDeclarations(packageId) {
            for (declared in declaredList) {
                val declaration = declared.declaration
                hook(
                    FunctionKey(declaration.targetKey),
                    toModHookPhase(declaration.phase),
                    HookOptions(
                        priority = declaration.priority,
                        mayMutateArgs = declaration.mayMutateArgs,
                        mayMutateReturn = declaration.mayMutateReturn,
                        runOnFailure = declaration.runOnFailure,
                        continueOnHandlerFailure = declaration.continueOnHandlerFailure,
                    ),
                )
            }
        }
    }
