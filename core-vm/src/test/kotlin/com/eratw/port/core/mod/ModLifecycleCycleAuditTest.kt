package com.eratw.port.core.mod

import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.modDeclarations
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROUNDS = 20
private const val TICKS_PER_ROUND = 2
private const val SLOW_THRESHOLD_NANOS = 1L
private const val CLOCK_STEP_NANOS = 10L
private const val NANOS_PER_MILLI = 1_000_000L
private const val AUDIO_VOLUME = 0.5f
private const val AUDIO_ASSET = "beep.ogg"
private const val HOOK_KEY = "COM300"
private const val RETIRING_HOOK_KEY = "COM400"
private const val EVENT_KEY = "EVENT"
private const val ORIGINAL_RETURN = 7L
private const val ORIGINAL_BOOL = 1L
private const val VOLUME_DEFAULT = 5L
private const val VOLUME_MAX = 10L
private const val PKG = "mod.a"

/** 收口后日志里绝不允许出现的（装配/撤销失败类）文案片段。 */
private val MANDATORY_FAILURE_MARKERS = listOf("装配失败", "卸载失败", "异常已隔离", "onDisable 异常", "onUnload 异常")

private interface ProbeService

private class RigRuntime : RuntimeScheduler {
    override val isOnMainThread: Boolean get() = true

    override fun submit(action: () -> Unit) = action()
}

private class ScriptedMod(private val script: (ModContext) -> Unit) : EraMod {
    override fun onLoad(context: ModContext) = script(context)

    override fun onEnable() = Unit

    override fun onDisable() = Unit

    override fun onUnload() = Unit
}

/** 记录 play/stop 次数的宿主播放汇（真 stop 语义：解绑即停声、重复解绑幂等）。 */
private class RecordingAudioSink : ModAudioSink {
    var playCount = 0
    var stopCount = 0

    override fun play(packageId: String, bytes: ByteArray, loop: Boolean, volume: Float): Boolean {
        playCount++
        return true
    }

    override fun stop() {
        stopCount++
    }
}

/** 记录主动触发派发的引擎派发目标替身（闸门语义与生产目标同形）。 */
private class RecordingTriggerTarget : ModEventTriggerTarget {
    var inEventDispatch = false
    var inVmRun = false
    val triggered = mutableListOf<String>()

    override fun isOnVmThread(): Boolean = true

    override fun isInEventDispatch(): Boolean = inEventDispatch

    override fun isInVmRun(): Boolean = inVmRun

    override fun triggerEvent(eventKey: String): Boolean {
        triggered += eventKey
        return true
    }
}

private fun newKernel(): EntityKernel = EntityKernel(
    schemas = listOf(
        ComponentSchema(
            packageId = "demo",
            name = "Stats",
            fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
        ),
    ),
    relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
    archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
)

/** 三 hook 槽（COM400 = 处理器失败即退槽）+ 一个交互阶段，够覆盖注册面全部真读数。 */
private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
    val registry = DeclarationRegistry()
    for (pkg in packages) {
        registry.addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = pkg,
                    hooks = listOf(
                        HookDecl(HOOK_KEY, HookPhase.PREFIX),
                        HookDecl("COM500", HookPhase.PREFIX),
                        HookDecl(RETIRING_HOOK_KEY, HookPhase.PREFIX, continueOnHandlerFailure = false),
                    ),
                    interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                ),
            ),
        )
    }
    return registry.freeze()
}

private fun settingsSnapshotOf(packageId: String): ModSettingsSnapshot = ModSettingsSnapshot(
    packageId,
    ModDeclarationAdapter.toDescriptor(
        modDeclarations(packageId, version = "1.0") {
            intSetting(key = "cfg.volume", default = VOLUME_DEFAULT, range = 0L..VOLUME_MAX, title = "音量")
            strSetting(key = "cfg.label", default = "默认", title = "标签")
        },
    ).settings,
    emptyMap(),
)

/** 装配脚本：注册面八项各落一条（hooks/events 三桶/interactions/services/strings/images）。 */
private fun defaultModScript(context: ModContext) {
    context.hooks.register(HOOK_KEY, HookPhase.PREFIX) { HookResult.Continue }
    context.events.register(EVENT_KEY, EventBucket.PRI) { EventResult.CONTINUE }
    context.events.register(EVENT_KEY, EventBucket.NORMAL) { EventResult.CONTINUE }
    context.events.register(EVENT_KEY, EventBucket.LATER) { EventResult.CONTINUE }
    context.interactions.registerHook(InteractionPhase.PRE) { InteractionResult.CONTINUE }
    context.services.publish(ProbeService::class.java, object : ProbeService {}, context.packageId)
    context.strings.declare("greet", "你好", "hello", "こんにちは")
    context.ui.registerImage("res/hint.png", "提示", null)
}

/**
 * 一个装配—派发—收口周期的夹具（等价于 app `ModRuntimeSession.attach` + `shutdown` 的 core 投影）。
 *
 * 接线序照生产：`host.install` → `FunctionHookWiring` / `ModEventWiring` / `ModEventTriggerWiring` /
 * `ModAudioWiring` install；收口序照 app `ModRuntimeSession.shutdown`（先 host.shutdown 再逐槽 uninstall）。
 */
private class AuditRig(vararg pkgIds: String, val store: VariableStore = VariableStore()) {
    val packages: List<String> = pkgIds.toList()
    private var clockNanos = 0L
    private var budgetNanos = 0L
    val logs = mutableListOf<String>()
    val diagnostics = RuntimeDiagnostics()
    val timing: ModTimingLedger = ModTimingLedger(
        clock = { clockNanos += CLOCK_STEP_NANOS; clockNanos },
        logSink = { logs += it },
    )
    val budget: ModHandlerBudget = ModHandlerBudget(
        clock = { budgetNanos += CLOCK_STEP_NANOS; budgetNanos },
        thresholdNanos = SLOW_THRESHOLD_NANOS,
        diagnostics = diagnostics,
    )
    val audio = RecordingAudioSink()
    val triggerTarget = RecordingTriggerTarget()
    val resourceRoot: File = Files.createTempDirectory("eratw-lifecycle-audit").toFile()
    private val resources: ModResourceStore = ModResourceStore(rt2Dir = resourceRoot)
    val host: ModRuntimeHost

    init {
        val assetDir = File(resourceRoot, packages.first() + ModResourceStore.ASSET_DIR_SUFFIX)
        assetDir.mkdirs()
        File(assetDir, AUDIO_ASSET).writeBytes("beep".toByteArray())
        host = ModRuntimeHost(
            snapshot = newSnapshot(*pkgIds),
            entityKernel = newKernel(),
            nativeMethods = NativeMethodRegistry(),
            logSink = { logs += it },
            runtime = RigRuntime(),
        )
        host.timing = timing
        host.hookPipeline.timing = timing
        host.handlerBudget = budget
        host.hookPipeline.handlerBudget = budget
        host.settings = pkgIds.associateWith { settingsSnapshotOf(it) }
        host.resources = resources
        host.audioSink = audio
    }

    fun install(): List<ModRuntimeState> = host.install(packages.map { it to ScriptedMod(::defaultModScript) })

    fun wire() {
        FunctionHookWiring.install(store, host.hookPipeline) { logs += it }
        ModEventWiring.install(store, host.eventHandlers, { logs += it }, timing, budget)
        ModEventTriggerWiring.install(store, triggerTarget)
        ModAudioWiring.install(store, audio, resources, { logs += it })
    }

    /** 一拍：hook 派发 → 事件桶派发 → 节拍通知 → 按名播放 → 主动触发。 */
    fun tick() {
        FunctionHookWiring.dispatchCall(store, HOOK_KEY, LongArray(0), emptyArray(), strRet = false) { _, _ ->
            ModValue.IntValue(ORIGINAL_RETURN)
        }.let { (it as ModValue.IntValue).value }
        ModEventWiring.runBucket(store, EVENT_KEY, EventBucket.NORMAL.ordinal)
        host.notifyTurnBoundary()
        ModAudioWiring.play(store, packages.first(), AUDIO_ASSET, false, AUDIO_VOLUME)
        contextOfFirst().events.trigger(EVENT_KEY)
    }

    /** 生产收口序：先撤生命周期，再逐槽解绑。 */
    fun teardown() {
        host.shutdown()
        FunctionHookWiring.uninstall(store, host.hookPipeline)
        ModEventWiring.uninstall(store, host.eventHandlers)
        ModAudioWiring.uninstall(store)
        ModEventTriggerWiring.uninstall(store, triggerTarget)
    }

    /** 收口后的晚到派发（旧调用点持有 store/context 的合法路径；必须全部旁路或显式 fail-closed）。 */
    fun staleAfterTeardown() {
        FunctionHookWiring.dispatchCall(store, HOOK_KEY, LongArray(0), emptyArray<String>(), strRet = false) { _, _ ->
            ModValue.IntValue(ORIGINAL_RETURN)
        }.let { (it as ModValue.IntValue).value }
        ModEventWiring.runBucket(store, EVENT_KEY, EventBucket.NORMAL.ordinal)
        host.notifyTurnBoundary()
        ModAudioWiring.play(store, packages.first(), AUDIO_ASSET, false, AUDIO_VOLUME)
        runCatching { contextOfFirst().events.trigger(EVENT_KEY) }
    }

    fun faces(pkg: String = packages.first()): RegisteredFaces = host.registeredFacesOf(pkg)

    fun contextOfFirst(): ModContext = assertNotNull(host.contextOf(packages.first()), "夹具包必须已装配")

    fun cleanup() {
        resourceRoot.deleteRecursively()
    }

    /** 收口判据：注册面读数全零 + 四个 AUX 槽全部解绑。 */
    fun assertTeardownZero(round: String) {
        assertEquals(RegisteredFaces.EMPTY, faces(), "$round 收口后注册面读数必须全零")
        assertNull(
            store.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT)?.pipeline,
            "$round 收口后 mod.functionhooks 槽必须解绑",
        )
        assertFalse(ModEventWiring.hasHandlers(store, EVENT_KEY), "$round 收口后事件注册表必须解绑")
        assertNull(
            store.aux.peek<ModEventTriggerWiring.Handle>(ModEventTriggerWiring.AUX_SLOT)?.target,
            "$round 收口后 mod.events.trigger 槽必须解绑",
        )
        assertNull(ModAudioWiring.sinkOf(store), "$round 收口后 mod.audio.sink 槽必须解绑")
    }
}

/**
 * 生命周期循环审计（Lane CL / task-83）：同一批 mod 反复 install → 派发 → shutdown 多轮，
 * 收口后注册面八项、四个 `store.aux` 槽、诊断账本、耗时账本、日志都必须回到「好像从未装过」。
 *
 * 反向证明（交付里贴红例名与行号）：把 [AuditRig.teardown] 里
 * `FunctionHookWiring.uninstall(store, host.hookPipeline)` 注掉 ⇒
 * `二十轮装配卸载循环后注册面与存储槽全部归零` 在 `mod.functionhooks 槽必须解绑` 处立红。
 */
class ModLifecycleCycleAuditTest {

    @Test
    fun `二十轮装配卸载循环后注册面与存储槽全部归零`() {
        val started = System.nanoTime()
        repeat(ROUNDS) { index ->
            val round = "第 ${index + 1} 轮"
            val rig = AuditRig(PKG)
            try {
                assertTrue(rig.install().single().active, "$round 装配必须成功")
                rig.wire()
                repeat(TICKS_PER_ROUND) { rig.tick() }
                assertLiveState(rig, round)

                rig.teardown()
                val recordsAfterTeardown = rig.diagnostics.snapshot().count { it.packageId == PKG }
                rig.assertTeardownZero(round)
                assertSettledState(rig, round, recordsAfterTeardown)
            } finally {
                rig.cleanup()
            }
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("[ModLifecycleCycleAuditTest] $ROUNDS 轮装配卸载循环耗时 = $elapsedMs ms")
    }

    /** 装配期 + 头两拍之后的活状态判据（注册面八项、耗时 scope、触发、慢处理器诊断）。 */
    private fun assertLiveState(rig: AuditRig, round: String) {
        val live = rig.faces()
        assertEquals(1, live.hooks, "$round 装配期 hook 读数")
        assertEquals(mapOf("PRI" to 1, "NORMAL" to 1, "LATER" to 1, "ONLY" to 0), live.events)
        assertEquals(1, live.interactions)
        assertEquals(1, live.services)
        assertEquals(1, live.strings)
        assertEquals(2, live.settings)
        assertEquals(1, live.images)
        assertEquals(TICKS_PER_ROUND, rig.audio.playCount, "$round 每拍按名播放透传到宿主汇")

        val timingScopes = rig.timing.forPackage(PKG).map { it.scope }.toSet()
        assertTrue(
            timingScopes.containsAll(
                listOf(
                    "lifecycle.onLoad",
                    "lifecycle.onEnable",
                    "lifecycle.onTurnBoundary",
                    "hook.$HOOK_KEY.PREFIX",
                    "event.$EVENT_KEY.NORMAL",
                ),
            ),
            "$round 装配/派发 scope 必须在拍内落账：$timingScopes",
        )
        assertEquals(TICKS_PER_ROUND, rig.triggerTarget.triggered.count { it == EVENT_KEY })
        val recordsBeforeTeardown = rig.diagnostics.snapshot().count { it.packageId == PKG }
        assertTrue(recordsBeforeTeardown > 0, "$round 慢处理器预算必须把该包记进诊断账本")
    }

    /** 收口后判据（四个 AUX 槽已由 [AuditRig.assertTeardownZero] 断言，这里查读数/账本/日志不复活）。 */
    private fun assertSettledState(rig: AuditRig, round: String, recordsAfterTeardown: Int) {
        val lifecycleScopes = rig.timing.forPackage(PKG).map { it.scope }.toSet()
        assertTrue(
            lifecycleScopes.containsAll(
                listOf("lifecycle.onLoad", "lifecycle.onEnable", "lifecycle.onDisable", "lifecycle.onUnload"),
            ),
            "$round 生命周期四个 scope 都必须落账：$lifecycleScopes",
        )
        rig.staleAfterTeardown()
        assertEquals(
            recordsAfterTeardown,
            rig.diagnostics.snapshot().count { it.packageId == PKG },
            "$round 收口后晚到派发不得新增该包诊断条目",
        )
        assertEquals(RegisteredFaces.EMPTY, rig.faces(), "$round 晚到派发不得复活注册面")
        assertEquals(TICKS_PER_ROUND, rig.audio.playCount, "$round 收口后 play 必须被解绑槽拒绝")
        assertEquals(1, rig.audio.stopCount, "$round 卸载必须停声一次")
        assertEquals(1, rig.triggerTarget.triggered.count { it == EVENT_KEY } - 1, "收口后触发不得派发")

        assertTrue(rig.timing.forPackage(PKG).isNotEmpty(), "$round 收口前账本有该包样本")
        rig.timing.resetPackage(PKG)
        assertTrue(rig.timing.forPackage(PKG).isEmpty(), "$round resetPackage 后该包账本清空")

        val failures = rig.logs.filter { line -> MANDATORY_FAILURE_MARKERS.any { line.contains(it) } }
        assertTrue(failures.isEmpty(), "$round 正常循环不得出现装配/撤销失败类日志：$failures")
    }

    @Test
    fun `处理器失败退槽的槽在卸载后不残留退槽读数`() {
        val rig = AuditRig(PKG)
        try {
            rig.host.install(
                listOf(
                    PKG to ScriptedMod { context ->
                        context.hooks.register(RETIRING_HOOK_KEY, HookPhase.PREFIX) {
                            throw IllegalStateException("故意在处理器里抛")
                        }
                    },
                ),
            )
            assertEquals(1, rig.faces().hooks, "夹具前置：退槽前绑定 1 条")
            rig.host.hookPipeline.dispatch(RETIRING_HOOK_KEY, emptyArray()) { ModValue.of(ORIGINAL_BOOL) }
            assertEquals(1, rig.faces().retiredHooks, "夹具前置：失败退槽读数涨到 1")

            rig.host.shutdown()

            assertEquals(RegisteredFaces.EMPTY, rig.faces(), "卸载撤销后不允许残留退槽读数（槽级状态随注册一并撤销）")
            assertFalse(
                rig.host.hookPipeline.isSlotRetired(RETIRING_HOOK_KEY, HookPhase.PREFIX, PKG),
                "解绑后该槽不得仍处于「已撤销」态",
            )
        } finally {
            rig.cleanup()
        }
    }

    @Test
    fun `设置项读数随卸载归零且不冒充活注册面`() {
        val rig = AuditRig(PKG)
        try {
            rig.install()
            assertEquals(2, rig.faces().settings, "夹具前置：注入两条设置声明")
            rig.host.shutdown()
            assertEquals(0, rig.faces().settings, "已停用包的设置项读数必须归零（声明面随生命周期收口）")
            assertEquals(RegisteredFaces.EMPTY, rig.faces())
        } finally {
            rig.cleanup()
        }
    }

    @Test
    fun `收口后晚到派发不复活注册面且不重复停声`() {
        val rig = AuditRig(PKG)
        try {
            rig.install()
            rig.wire()
            repeat(TICKS_PER_ROUND) { rig.tick() }
            rig.teardown()
            val facesAfterTeardown = rig.faces()

            rig.staleAfterTeardown()

            assertEquals(facesAfterTeardown, rig.faces(), "晚到派发不得改变任何注册面读数")
            assertEquals(RegisteredFaces.EMPTY, rig.faces())
            assertEquals(TICKS_PER_ROUND, rig.audio.playCount, "解绑后的晚到 play 必须 fail-closed 不触达宿主汇")
            assertEquals(1, rig.audio.stopCount, "晚到派发不得二次 stop")
            assertEquals(TICKS_PER_ROUND, rig.triggerTarget.triggered.size, "解绑后的主动触发必须显式拒绝")
        } finally {
            rig.cleanup()
        }
    }

    @Test
    fun `同一store重复装配覆盖旧接线且旧实例解绑no_op`() {
        val sharedStore = VariableStore()
        val oldRig = AuditRig(PKG, store = sharedStore)
        val newRig = AuditRig(PKG, store = sharedStore)
        try {
            oldRig.install()
            oldRig.wire()
            newRig.install()
            newRig.wire()

            FunctionHookWiring.uninstall(sharedStore, oldRig.host.hookPipeline)
            ModEventWiring.uninstall(sharedStore, oldRig.host.eventHandlers)
            ModEventTriggerWiring.uninstall(sharedStore, oldRig.triggerTarget)

            assertEquals(
                ORIGINAL_RETURN,
                FunctionHookWiring.dispatchCall(
                    sharedStore, HOOK_KEY, LongArray(0), emptyArray<String>(), strRet = false,
                ) { _, _ -> ModValue.IntValue(ORIGINAL_RETURN) }.let { (it as ModValue.IntValue).value },
                "旧实例解绑不得摘掉新实例的 hook 接线",
            )
            assertTrue(ModEventWiring.hasHandlers(sharedStore, EVENT_KEY), "旧实例解绑不得摘掉新实例的事件注册表")
            assertNotNull(
                sharedStore.aux.peek<ModEventTriggerWiring.Handle>(ModEventTriggerWiring.AUX_SLOT)?.target,
                "旧实例解绑不得摘掉新实例的主动触发目标",
            )

            FunctionHookWiring.uninstall(sharedStore, newRig.host.hookPipeline)
            ModEventWiring.uninstall(sharedStore, newRig.host.eventHandlers)
            ModEventTriggerWiring.uninstall(sharedStore, newRig.triggerTarget)
            assertNull(sharedStore.aux.peek<FunctionHookWiring.Handle>(FunctionHookWiring.AUX_SLOT)?.pipeline)
            assertFalse(ModEventWiring.hasHandlers(sharedStore, EVENT_KEY))
        } finally {
            oldRig.cleanup()
            newRig.cleanup()
        }
    }

    @Test
    fun `音频槽解绑停声且重复解绑幂等`() {
        val rig = AuditRig(PKG)
        try {
            rig.install()
            rig.wire()
            assertTrue(ModAudioWiring.play(rig.store, PKG, AUDIO_ASSET, false, AUDIO_VOLUME), "解绑前必须能播")
            assertEquals(1, rig.audio.playCount)

            ModAudioWiring.uninstall(rig.store)

            assertNull(ModAudioWiring.sinkOf(rig.store), "解绑后槽读数必须归 null")
            assertEquals(1, rig.audio.stopCount, "解绑必须停声")
            ModAudioWiring.uninstall(rig.store)
            assertEquals(1, rig.audio.stopCount, "重复解绑不得二次 stop")
            assertFalse(ModAudioWiring.play(rig.store, PKG, AUDIO_ASSET, false, AUDIO_VOLUME), "解绑后 play 显式 false")
            assertEquals(1, rig.audio.playCount, "解绑后 play 不得触达宿主汇")
        } finally {
            rig.cleanup()
        }
    }

    @Test
    fun `主动触发槽按实例解绑且解绑时清空延迟队列`() {
        val rig = AuditRig(PKG)
        try {
            rig.install()
            ModEventWiring.install(rig.store, rig.host.eventHandlers, {})
            ModEventTriggerWiring.install(rig.store, rig.triggerTarget)

            rig.contextOfFirst().events.trigger(EVENT_KEY)
            assertEquals(listOf(EVENT_KEY), rig.triggerTarget.triggered, "run 外触发必须当刻派发")

            rig.triggerTarget.inVmRun = true
            rig.contextOfFirst().events.trigger(EVENT_KEY)
            assertEquals(1, ModEventTriggerWiring.deferredPendingCount(rig.store), "run 内触发必须延迟入队")

            ModEventTriggerWiring.uninstall(rig.store, rig.triggerTarget)

            assertEquals(0, ModEventTriggerWiring.deferredPendingCount(rig.store), "解绑必须清空延迟队列")
            assertNull(rig.store.aux.peek<ModEventTriggerWiring.Handle>(ModEventTriggerWiring.AUX_SLOT)?.target)
            assertFailsWith<UnsupportedOperationException> {
                ModEventTriggerWiring.trigger(rig.store, EVENT_KEY)
            }
        } finally {
            rig.cleanup()
        }
    }
}
