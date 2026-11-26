package com.eratw.port.modtesting

import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 录制型事件 / 钩子 / 交互夹具的自测：把 KDoc 里「与生产同口径」的每条边界钉成用例。
 *
 * 覆盖面：`SINGLE` 单桶截断、四桶序、ONLY 拒绝、跨 context 共享表、hook 槽 fail-closed 与重复绑定、
 * priority 排序、`Replace` 短路、`mayMutateArgs` 回流与未授权隔离、交互相位机（REPLACE 跳 MAIN、
 * INTERRUPT 中止、handler 异常隔离）。
 */
class RecordingFixturesTest {

    private val world = TestSchema.worldWithPeer()

    private val harness = world.newContext(TestSchema.PACKAGE_ID)

    private val peer = world.newContext(TestSchema.PEER_PACKAGE_ID)

    @Test
    fun singleTruncatesOnlyItsOwnBucket() {
        val seen = mutableListOf<String>()
        harness.events.register("TEST.EVENT", EventBucket.PRI) {
            seen += "pri1"
            EventResult.CONTINUE
        }
        harness.events.register("TEST.EVENT", EventBucket.PRI) {
            seen += "pri2"
            EventResult.SINGLE
        }
        harness.events.register("TEST.EVENT", EventBucket.PRI) {
            seen += "pri3"
            EventResult.CONTINUE
        }
        harness.events.register("TEST.EVENT", EventBucket.NORMAL) {
            seen += "normal1"
            EventResult.CONTINUE
        }

        assertEquals(
            listOf(EventResult.CONTINUE, EventResult.SINGLE),
            harness.dispatchEvent("TEST.EVENT", EventBucket.PRI),
        )
        assertEquals(listOf("pri1", "pri2"), seen)

        // SINGLE 只截断本桶：NORMAL 桶照跑，四桶链也照跑。
        assertEquals(listOf(EventResult.CONTINUE), harness.dispatchEvent("TEST.EVENT", EventBucket.NORMAL))
        assertEquals(
            listOf(EventResult.CONTINUE, EventResult.SINGLE, EventResult.CONTINUE),
            harness.dispatchEvent("TEST.EVENT"),
        )
        assertEquals(listOf("pri1", "pri2", "normal1", "pri1", "pri2", "normal1"), seen)
    }

    @Test
    fun onlyBucketAndBlankEventKeyAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            harness.events.register("TEST.EVENT", EventBucket.ONLY) { EventResult.CONTINUE }
        }
        assertFailsWith<IllegalArgumentException> {
            harness.events.register("  ", EventBucket.NORMAL) { EventResult.CONTINUE }
        }
    }

    @Test
    fun eventTableIsSharedAcrossPackageContexts() {
        harness.events.register("TEST.EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }
        peer.events.register("TEST.EVENT", EventBucket.NORMAL) { EventResult.CONTINUE }

        assertEquals(
            listOf(TestSchema.PACKAGE_ID, TestSchema.PEER_PACKAGE_ID),
            harness.eventsRecorder.packagesFor("TEST.EVENT"),
        )
        assertEquals(2, harness.eventsRecorder.handlerCount("TEST.EVENT"))
        assertEquals(listOf(EventResult.CONTINUE, EventResult.CONTINUE), harness.dispatchEvent("TEST.EVENT"))
    }

    @Test
    fun eventQueryFaceNormalizesSortsAndSnapshots() {
        val recorder = harness.eventsRecorder
        // 缺省未注入已知集：与生产同口径 ⇒ exists 恒 false、knownEventKeys 空表（fail-safe 不猜）。
        assertTrue(recorder.knownEventKeys().isEmpty())
        assertFalse(harness.events.exists("TEST.EVENT"))

        recorder.adoptKnownEvents(setOf("zeta", "ALPHA", "  beta "))
        assertFalse(harness.events.exists("EVENTTURNEND"))
        assertTrue(harness.events.exists("  alphA  "))
        assertTrue(harness.events.exists("beta"))
        assertFalse(harness.events.exists("GAMMA"))
        assertFalse(harness.events.exists("   "))
        assertEquals(listOf("ALPHA", "BETA", "ZETA"), harness.events.knownEventKeys())

        // 快照语义：清空调用方拿到的列表不影响台架表与后续调用（与生产同口径）。
        val first = harness.events.knownEventKeys()
        (first as java.util.Collection<*>).clear()
        assertEquals(listOf("ALPHA", "BETA", "ZETA"), harness.events.knownEventKeys())
        assertNotSame(first, harness.events.knownEventKeys())
    }

    @Test
    fun eventQueryFaceAgreesWithRegisterKnownGate() {
        harness.eventsRecorder.adoptKnownEvents(setOf("EVENTTURNEND"))

        // exist=true ⟺ register 不会被 known 闸判未知名。
        assertTrue(harness.events.exists("  eventturnend  "))
        harness.events.register("eventturnend", EventBucket.NORMAL) { EventResult.CONTINUE }

        assertFalse(harness.events.exists("EVENTTURNINT"))
        assertFailsWith<IllegalArgumentException> {
            harness.events.register("EVENTTURNINT", EventBucket.NORMAL) { EventResult.CONTINUE }
        }
    }

    @Test
    fun hookSlotMustBeDeclaredAndBindOnce() {
        assertFailsWith<IllegalArgumentException> {
            harness.hooks.register("TEST.UNDECLARED", HookPhase.PREFIX) { HookResult.Continue }
        }
        // peer 只声明了 PREFIX 槽，POSTFIX 未声明 ⇒ fail-closed。
        assertFailsWith<IllegalArgumentException> {
            peer.hooks.register(TestSchema.HOOKED.name, HookPhase.POSTFIX) { HookResult.Continue }
        }

        harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { HookResult.Continue }
        assertFailsWith<IllegalArgumentException> {
            harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { HookResult.Continue }
        }
    }

    @Test
    fun hookFunctionKeyGateRejectsUnknownTargetWhenAdopted() {
        // 注入已知函数键后（与生产 ModContextImpl.hooks 拼错闸同口径）：目录外目标键当场拒绝并给
        // 最近候选；目录表共享 ⇒ peer 的注册同样被闸。
        harness.hooksRecorder.adoptKnownFunctionKeys(setOf("TEST.PING", "TEST.HOOKED"))
        val failure = assertFailsWith<IllegalArgumentException> {
            peer.hooks.register("TEST.PONG", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(failure.message!!.contains("未知函数键 \"TEST.PONG\""), failure.message)
        assertTrue(failure.message!!.contains("最接近的候选：TEST.PING"), failure.message)

        // 目录内但槽未声明：闸放行，仍走既有「槽未声明」文案（漏声明 ≠ 拼错，不带候选）。
        val undeclared = assertFailsWith<IllegalArgumentException> {
            peer.hooks.register(TestSchema.PING.name, HookPhase.POSTFIX) { HookResult.Continue }
        }
        assertTrue(undeclared.message!!.contains("hook 槽未声明"), undeclared.message)
        assertFalse(undeclared.message!!.contains("候选"), undeclared.message)

        // 目录内 + 槽已声明 ⇒ 照常绑定，闸不误伤（大小写与空白归一后命中）：
        // 归一后命中同一槽，重复绑定被既有闸拒绝 ⇒ 第一次注册真的成功了。
        harness.hooks.register(" test.hooked ", HookPhase.PREFIX) { HookResult.Continue }
        assertFailsWith<IllegalArgumentException> {
            harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { HookResult.Continue }
        }
    }

    @Test
    fun hookFunctionKeyGateIsOffByDefault() {
        // 缺省空集 = 不校验（与生产缺省口径一致）：未声明键走既有「槽未声明」，不出现拼错闸文案。
        assertTrue(harness.hooksRecorder.knownFunctionKeys().isEmpty())
        val failure = assertFailsWith<IllegalArgumentException> {
            harness.hooks.register("TEST.UNDECLARED", HookPhase.PREFIX) { HookResult.Continue }
        }
        assertTrue(failure.message!!.contains("hook 槽未声明"), failure.message)
        assertFalse(failure.message!!.contains("未知函数键"), failure.message)
    }

    @Test
    fun hookFunctionKeysSnapshotNormalizesSortsAndSnapshots() {
        harness.hooksRecorder.adoptKnownFunctionKeys(setOf("zeta", "ALPHA", "  beta "))
        // Set 相等不钉序，用迭代序钉「字典序稳定排序 + 归一」。
        assertEquals(listOf("ALPHA", "BETA", "ZETA"), harness.hooksRecorder.knownFunctionKeys().toList())

        // 快照语义：就地清空调用方拿到的集合不影响台架表与后续调用（与生产同口径）。
        val first = harness.hooksRecorder.knownFunctionKeys()
        (first as MutableSet<*>).clear()
        assertEquals(listOf("ALPHA", "BETA", "ZETA"), harness.hooksRecorder.knownFunctionKeys().toList())
        assertNotSame(first, harness.hooksRecorder.knownFunctionKeys())

        // 传空集 = 关闸（缺省即关闭）。
        harness.hooksRecorder.adoptKnownFunctionKeys(emptySet())
        assertTrue(harness.hooksRecorder.knownFunctionKeys().isEmpty())
        assertFailsWith<IllegalArgumentException> {
            harness.hooks.register("TEST.UNDECLARED", HookPhase.PREFIX) { HookResult.Continue }
        }
    }

    @Test
    fun hookRegistrationsProjectsDeclaredSlotsAcrossPackagesInDeterministicOrder() {
        // worldWithPeer 的声明槽：harness(TEST.PING/PREFIX p0, TEST.HOOKED/PREFIX p10, TEST.HOOKED/POSTFIX p0)
        // + peer(TEST.HOOKED/PREFIX p5)。投影序 = functionKey 字典序 → priority → 声明序：
        // TEST.HOOKED 内 p0(harness POSTFIX) → p5(peer PREFIX) → p10(harness PREFIX)，再 TEST.PING。
        val rows = harness.hooksRecorder.registrations()
        assertEquals(
            listOf(
                Triple("TEST.HOOKED", 0, TestSchema.PACKAGE_ID),
                Triple("TEST.HOOKED", 5, TestSchema.PEER_PACKAGE_ID),
                Triple("TEST.HOOKED", 10, TestSchema.PACKAGE_ID),
                Triple("TEST.PING", 0, TestSchema.PACKAGE_ID),
            ),
            rows.map { Triple(it.functionKey, it.priority, it.packageId) },
        )
        // prefix-postfix 区分：kind 各归其位（同函数里 POSTFIX 与 PREFIX 都在）。
        assertEquals(
            listOf(HookPhase.POSTFIX, HookPhase.PREFIX, HookPhase.PREFIX, HookPhase.PREFIX),
            rows.map { it.kind },
        )
    }

    @Test
    fun hookRegistrationsReflectDeclaredSlotsNotBindings() {
        val snapshot = harness.hooksRecorder.registrations()
        assertTrue(snapshot.isNotEmpty())

        // 绑定只往已声明槽上挂处理器：注册前后投影一致（未绑定槽也在投影里，与生产同口径）。
        harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { HookResult.Continue }
        assertEquals(snapshot, harness.hooksRecorder.registrations())

        // 独立列表：就地清空返回值不影响台架槽表与后续读数。
        (snapshot as java.util.Collection<*>).clear()
        assertTrue(harness.hooksRecorder.registrations().isNotEmpty())
    }

    @Test
    fun dispatchRunsByPriorityThenRegistration() {
        val order = mutableListOf<String>()
        peer.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) {
            order += "peer"
            HookResult.Continue
        }
        harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) {
            order += "harness"
            HookResult.Continue
        }

        assertEquals(HookResult.Continue, harness.dispatchHook(TestSchema.HOOKED.name))
        // peer priority 5 < harness priority 10 ⇒ peer 先跑。
        assertEquals(listOf("peer", "harness"), order)
        assertEquals(
            listOf(TestSchema.PEER_PACKAGE_ID, TestSchema.PACKAGE_ID),
            harness.hooksRecorder.calls.map { it.packageId },
        )
    }

    @Test
    fun replaceShortCircuitsRemainingHooks() {
        val order = mutableListOf<String>()
        peer.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) {
            order += "peer"
            HookResult.Replace(ModValue.of("hooked"))
        }
        harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) {
            order += "harness"
            HookResult.Continue
        }

        val result = harness.dispatchHook(TestSchema.HOOKED.name)
        val replaced = assertIs<HookResult.Replace>(result)
        assertEquals(ModValue.of("hooked"), replaced.value)
        assertEquals(listOf("peer"), order)
    }

    @Test
    fun skipOriginalIsReportedWhenNoReplace() {
        peer.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { HookResult.SkipOriginal }
        harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { HookResult.Continue }

        assertEquals(HookResult.SkipOriginal, harness.dispatchHook(TestSchema.HOOKED.name))
    }

    @Test
    fun onlyAuthorizedSlotMutatesArgs() {
        val actual = arrayOf(ModCallArg(isString = true, strValue = "original"))
        peer.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { context ->
            assertEquals("original", context.args[0].strValue)
            context.args[0].strValue = "peer-mutated"
            HookResult.Continue
        }
        harness.hooks.register(TestSchema.HOOKED.name, HookPhase.PREFIX) { context ->
            assertEquals("original", context.args[0].strValue)
            context.args[0].strValue = "harness-mutated"
            HookResult.Continue
        }

        assertEquals(HookResult.Continue, harness.dispatchHook(TestSchema.HOOKED.name, args = actual))
        // peer 未授权 mayMutateArgs ⇒ 写的是克隆；harness 授权 ⇒ 写到真实实参。
        assertEquals("harness-mutated", actual[0].strValue)
    }

    @Test
    fun interactionPhasesRunInOrder() {
        val phases = mutableListOf<InteractionPhase>()
        for (phase in InteractionPhase.entries) {
            harness.interactions.registerHook(phase) {
                phases += it.phase
                InteractionResult.CONTINUE
            }
        }
        val actor = world.entities.create(TestSchema.ARCHETYPE.name)

        val result = harness.interactionsRecorder.runOnce(actor, listOf(actor)) { ModValue.of(7L) }

        assertEquals(
            listOf(
                InteractionPhase.PRECONDITION,
                InteractionPhase.PRE,
                InteractionPhase.MAIN,
                InteractionPhase.POST,
                InteractionPhase.SETTLEMENT,
            ),
            phases,
        )
        assertEquals(phases, harness.interactionsRecorder.lastPhaseOrder)
        assertEquals(ModValue.of(7L), result.returnValue)
        assertTrue(!result.replaced)
        assertTrue(!result.interrupted)
        assertTrue(result.failures.isEmpty())
        assertEquals(InteractionPhase.entries, harness.interactionsRecorder.registeredPhases)
    }

    @Test
    fun replaceBeforeMainSkipsMainButRunsPost() {
        var mainRan = false
        harness.interactions.registerHook(InteractionPhase.PRE) {
            it.replacedResult = ModValue.of("pre-replaced")
            InteractionResult.REPLACE
        }
        harness.interactions.registerHook(InteractionPhase.MAIN) { InteractionResult.REPLACE }
        harness.interactions.registerHook(InteractionPhase.SETTLEMENT) { InteractionResult.CONTINUE }
        val actor = world.entities.create(TestSchema.ARCHETYPE.name)

        val result = harness.interactionsRecorder.runOnce(actor, listOf(actor)) {
            mainRan = true
            ModValue.of(1L)
        }

        assertTrue(!mainRan)
        assertTrue(result.replaced)
        assertEquals(ModValue.of("pre-replaced"), result.returnValue)
        assertEquals(
            listOf(
                InteractionPhase.PRECONDITION,
                InteractionPhase.PRE,
                InteractionPhase.POST,
                InteractionPhase.SETTLEMENT,
            ),
            harness.interactionsRecorder.lastPhaseOrder,
        )
    }

    @Test
    fun interruptStopsInteraction() {
        harness.interactions.registerHook(InteractionPhase.POST) { InteractionResult.INTERRUPT }
        harness.interactions.registerHook(InteractionPhase.SETTLEMENT) { InteractionResult.REPLACE }
        val actor = world.entities.create(TestSchema.ARCHETYPE.name)

        val result = harness.interactionsRecorder.runOnce(actor, listOf(actor))
        assertTrue(result.interrupted)
        assertEquals(
            listOf(InteractionPhase.PRECONDITION, InteractionPhase.PRE, InteractionPhase.MAIN, InteractionPhase.POST),
            harness.interactionsRecorder.lastPhaseOrder,
        )
    }

    @Test
    fun handlerFailureIsIsolatedAndInteractionContinues() {
        harness.interactions.registerHook(InteractionPhase.PRE) { error("boom") }
        harness.interactions.registerHook(InteractionPhase.MAIN) { InteractionResult.CONTINUE }
        val actor = world.entities.create(TestSchema.ARCHETYPE.name)

        val result = harness.interactionsRecorder.runOnce(actor, listOf(actor)) { ModValue.of(3L) }

        assertEquals(listOf(TestSchema.PACKAGE_ID), result.failures)
        assertEquals(ModValue.of(3L), result.returnValue)
        assertTrue(!result.interrupted)
    }

    @Test
    fun interactionHookMustBeDeclared() {
        assertFailsWith<IllegalArgumentException> {
            peer.interactions.registerHook(InteractionPhase.MAIN) { InteractionResult.CONTINUE }
        }
        val registration = harness.interactions.registerHook(InteractionPhase.MAIN) { InteractionResult.CONTINUE }
        registration.unregister()
        assertEquals(emptyList(), harness.interactionsRecorder.registeredPhases)
    }

    @Test
    fun runOnceDefaultsToNullResultAndNoLastRunBeforeRun() {
        assertNull(harness.interactionsRecorder.lastRun)
        val actor = world.entities.create(TestSchema.ARCHETYPE.name)
        val result = harness.interactionsRecorder.runOnce(actor, emptyList())
        assertNull(result.returnValue)
        assertEquals(result, harness.interactionsRecorder.lastRun)
        assertEquals(0, harness.interactionsRecorder.depth)
    }
}
