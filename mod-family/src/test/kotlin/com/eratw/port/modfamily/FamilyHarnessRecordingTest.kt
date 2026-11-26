package com.eratw.port.modfamily

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.FunctionKey
import com.eratw.port.modapi.HookOptions
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 示范用例：用 `:mod-testing` 的录制型夹具测「事件 / 钩子」而不启真引擎。
 *
 * 这两个用例是给 mod 作者的**照抄模板**：
 * - [eventHandlersRunInBucketOrderAndSingleTruncates] 演示「注册 → 单桶派发 → 四桶链派发」，
 *   钉住 `SINGLE` 只截断本桶、不跨桶；
 * - [hookPrefixMutatesArgsAndReplaceShortCircuits] 演示「多包阶段槽按 priority 跑 → 授权槽改实参 →
 *   `Replace` 短路余下 hook」，装配面是 `TestWorld.of(声明…)` + `runMod(…, FamilyDeclarations)`。
 *
 * 钩子槽必须先在 Manifest 里声明（生产 `HookPipeline` 与台架同一条 fail-closed）——
 * [FamilyDeclarations] 是真实载体，**不为测试改它的生产 manifest**，所以这里用测试本地声明
 * [FamilyHookSlots] 补一个与 family 同包名的槽，再用对端/尾包两个测试声明验证跨包排序。
 */
class FamilyHarnessRecordingTest {

    @Test
    fun eventHandlersRunInBucketOrderAndSingleTruncates() {
        val world = TestWorld.of(FamilyDeclarations)
        val session = runMod(FamilyMod(), world, FamilyDeclarations)
        val context = session.context

        val calls = mutableListOf<String>()
        context.events.register(EVENT_KEY, EventBucket.PRI) {
            calls += "pri#1"
            EventResult.CONTINUE
        }
        context.events.register(EVENT_KEY, EventBucket.PRI) {
            calls += "pri#2"
            EventResult.SINGLE
        }
        context.events.register(EVENT_KEY, EventBucket.PRI) {
            calls += "pri#3"
            EventResult.CONTINUE
        }
        context.events.register(EVENT_KEY, EventBucket.NORMAL) {
            calls += "normal#1"
            EventResult.CONTINUE
        }

        assertEquals(
            listOf(EventResult.CONTINUE, EventResult.SINGLE),
            context.dispatchEvent(EVENT_KEY, EventBucket.PRI),
        )
        assertEquals(listOf("pri#1", "pri#2"), calls)
        // SINGLE 只截断 PRI 桶：NORMAL 桶照跑。
        assertEquals(listOf(EventResult.CONTINUE), context.dispatchEvent(EVENT_KEY, EventBucket.NORMAL))
        // 四桶链：PRI 两位 + NORMAL 一位，SINGLE 不跨桶。
        assertEquals(
            listOf(EventResult.CONTINUE, EventResult.SINGLE, EventResult.CONTINUE),
            context.dispatchEvent(EVENT_KEY),
        )
        assertEquals(listOf(FamilyDeclarations.PACKAGE_ID), context.eventsRecorder.packagesFor(EVENT_KEY))
        session.disable()
    }

    @Test
    fun hookPrefixMutatesArgsAndReplaceShortCircuits() {
        val world = TestWorld.of(FamilyDeclarations, FamilyHookSlots, PeerHookSlots, TrailerHookSlots)
        val session = runMod(FamilyMod(), world, FamilyDeclarations)
        val peer = world.newContext(PeerHookSlots.packageId)
        val trailer = world.newContext(TrailerHookSlots.packageId)

        val order = mutableListOf<String>()
        val actual = arrayOf(ModCallArg(isString = true, strValue = "raw"))
        peer.hooks.register(HOOKED.name, HookPhase.PREFIX) { hookContext ->
            order += "peer"
            hookContext.args[0].strValue = "peer-edit"
            HookResult.Continue
        }
        session.context.hooks.register(HOOKED.name, HookPhase.PREFIX) { hookContext ->
            order += "family"
            // peer（priority 5）先跑且授权改实参 ⇒ family 看到的是改后值。
            assertEquals("peer-edit", hookContext.args[0].strValue)
            HookResult.Replace(ModValue.of("hooked"))
        }
        trailer.hooks.register(HOOKED.name, HookPhase.PREFIX) {
            order += "trailer"
            HookResult.Continue
        }

        val result = session.context.dispatchHook(HOOKED.name, args = actual)

        assertEquals(ModValue.of("hooked"), assertIs<HookResult.Replace>(result).value)
        // priority 5(peer) → 10(family) → 20(trailer)；family Replace 后 trailer 不该被调。
        assertEquals(listOf("peer", "family"), order)
        assertEquals(
            listOf(PeerHookSlots.packageId, FamilyDeclarations.PACKAGE_ID),
            session.context.hooksRecorder.calls.map { it.packageId },
        )
        assertEquals("peer-edit", actual[0].strValue)
        session.disable()
    }

    private companion object {
        const val EVENT_KEY: String = "family.harness.event"
        val HOOKED: FunctionKey = FunctionKey("FAMILY.HARNESS.HOOKED")
    }

    /** 与 family 同包名的槽声明：真实载体不声明 hook，测试本地补一个（packageId 必须同名才能绑定）。 */
    private object FamilyHookSlots : ModCarrierDeclaration {
        override val packageId: String get() = FamilyDeclarations.PACKAGE_ID
        override fun newMod(): EraMod = FamilyMod()
        override val manifest: ModManifest = modDeclarations(FamilyDeclarations.PACKAGE_ID, version = "0.1") {
            hook(HOOKED, HookPhase.PREFIX, HookOptions(priority = 10, mayMutateArgs = true))
        }
    }

    /** 对端包：priority 更小 ⇒ 先跑；授权改实参 ⇒ 改动对后续槽可见。 */
    private object PeerHookSlots : ModCarrierDeclaration {
        override val packageId: String = "eratw.family.peer"
        override fun newMod(): EraMod = FamilyMod()
        override val manifest: ModManifest = modDeclarations(packageId, version = "0.1") {
            hook(HOOKED, HookPhase.PREFIX, HookOptions(priority = 5, mayMutateArgs = true))
        }
    }

    /** 尾包：priority 最大，正常情况下最后跑；本例用来证明 Replace 短路到它之前。 */
    private object TrailerHookSlots : ModCarrierDeclaration {
        override val packageId: String = "eratw.family.trailer"
        override fun newMod(): EraMod = FamilyMod()
        override val manifest: ModManifest = modDeclarations(packageId, version = "0.1") {
            hook(HOOKED, HookPhase.PREFIX, HookOptions(priority = 20))
        }
    }
}
