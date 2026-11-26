package com.eratw.port.modtemplate

import com.eratw.port.modapi.BuiltinPhase
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue
import com.eratw.port.modtesting.RecordingResources.Access
import com.eratw.port.modtesting.TestWorld
import com.eratw.port.modtesting.runMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 能力矩阵示范的端到端读数：每个用例真跑 [TemplateCapabilityMod] 的注册面，
 * 用 `:mod-testing` 的录制型替身（eventsRecorder / hooksRecorder / interactionsRecorder /
 * uiRecorder / timingRecorder / settingsRecorder / resourcesRecorder）取观测证据。
 */
class TemplateCapabilityMatrixTest {

    @Test
    fun dailyJobDispatchesAndRecordsDay() {
        val world = TestWorld.of(TemplateCapabilityDeclarations, decisionSeed = 7L)
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context

        assertTrue(
            context.boundJobIds.contains(TemplateCapabilityDeclarations.DAILY_PULSE.name.uppercase()),
            "onEnable 里 jobs.bind 的槽应进 boundJobIds（任务名按大写归一）",
        )
        assertTrue(context.fireJob(TemplateCapabilityDeclarations.DAILY_PULSE), "声明过且 bind 过的任务应可派发")
        assertEquals(1L, mod.dailyPulses)
        assertEquals(1L, mod.lastPulseDay, "JobContext.time 来自派发方（台架缺省 day=1）")
    }

    @Test
    fun eventsRegisterAndFireInBucketOrderAndUnregisterOnDisable() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context

        val recorded = context.eventsRecorder.registrations
            .filter { it.eventKey == TemplateCapabilityDeclarations.PULSE_EVENT }
        assertEquals(listOf(EventBucket.NORMAL, EventBucket.LATER), recorded.map { it.bucket })
        assertEquals(
            listOf(TemplateCapabilityDeclarations.PACKAGE_ID, TemplateCapabilityDeclarations.PACKAGE_ID),
            recorded.map { it.packageId },
        )

        assertEquals(listOf(EventResult.CONTINUE, EventResult.CONTINUE), context.dispatchEvent(
            TemplateCapabilityDeclarations.PULSE_EVENT,
        ))
        assertEquals(1, mod.normalEventHits)
        assertEquals(1, mod.lateEventHits)
        assertEquals(TemplateCapabilityDeclarations.PACKAGE_ID, mod.lastEventPackage)

        assertEquals(1, context.dispatchEvent(
            TemplateCapabilityDeclarations.PULSE_EVENT,
            EventBucket.LATER,
        ).size)
        assertEquals(1, mod.normalEventHits)
        assertEquals(2, mod.lateEventHits)

        session.disable()
        assertTrue(context.dispatchEvent(TemplateCapabilityDeclarations.PULSE_EVENT).isEmpty())
        assertFalse(context.fireJob(TemplateCapabilityDeclarations.DAILY_PULSE))
    }

    @Test
    fun phaseSubscriptionsFireAtBuiltinAndCustomBoundaries() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context

        assertEquals(1, context.firePhase(BuiltinPhase.DAY_END.name))
        assertEquals(1, mod.dayEndHits)
        assertEquals(1L, mod.lastPhaseDay)
        assertEquals(1, context.firePhase(TemplateCapabilityDeclarations.ACTION_PHASE.name))
        assertEquals(1, mod.actionHits)
        assertEquals(0, context.firePhase("TEMPLATE_MATRIX_NOT_SUBSCRIBED"), "没订阅的相位派发数为 0")

        session.disable()
        assertEquals(0, context.firePhase(BuiltinPhase.DAY_END.name))
    }

    @Test
    fun hooksMutateReturnMutateArgsAndShortCircuitOriginal() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context

        val chain = context.hooksRecorder.dispatchChain(
            TemplateCapabilityDeclarations.ECHO_TARGET.name,
            HookPhase.POSTFIX,
            returnValue = ModValue.of("orig"),
        )
        assertEquals(HookResult.Continue, chain.result)
        assertEquals("orig|matrix", (chain.returnValue as ModValue.StrValue).value)
        assertEquals(1, mod.postfixHookHits)

        val args = arrayOf(
            ModCallArg(isString = true, strValue = "first"),
            ModCallArg(isString = true, strValue = "second"),
        )
        assertEquals(
            HookResult.Continue,
            context.hooksRecorder.dispatch(
                TemplateCapabilityDeclarations.ARG_TARGET.name,
                HookPhase.PREFIX,
                args,
            ),
        )
        assertEquals("second!", args[1].strValue, "mayMutateArgs 槽改的是原数组")
        assertEquals("first", args[0].strValue, "没指定的实参保持原样")
        assertEquals(1, mod.prefixHookHits)

        var originalRan = false
        val blocked = context.hooksRecorder.dispatchWithOriginal(
            TemplateCapabilityDeclarations.BLOCK_TARGET.name,
        ) {
            originalRan = true
            ModValue.of("original")
        }
        assertTrue(blocked.replaced)
        assertFalse(blocked.originalRan)
        assertFalse(originalRan, "Replace 短路时原函数体不得执行")
        assertEquals("matrix.blocked", (blocked.returnValue as ModValue.StrValue).value)
        assertEquals(1, mod.blockHookHits)
        assertEquals(
            listOf(
                TemplateCapabilityDeclarations.ECHO_TARGET.name,
                TemplateCapabilityDeclarations.ARG_TARGET.name,
                TemplateCapabilityDeclarations.BLOCK_TARGET.name,
            ),
            context.hooksRecorder.calls.map { it.targetKey },
        )
    }

    @Test
    fun interactionPhasesRunInOrderAndReplaceSkipsMain() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context
        val actor = context.entities.create(TemplateCapabilityDeclarations.ACTOR.name)

        assertEquals(
            listOf(InteractionPhase.PRE, InteractionPhase.POST),
            context.interactionsRecorder.registeredPhases,
        )
        val outcome = context.interactionsRecorder.runOnce(
            actor = actor,
            targets = listOf(actor),
            interactionId = TemplateCapabilityDeclarations.INTERACTION_CHAT,
        ) { ModValue.of("main-result") }
        assertEquals("main-result", (outcome.returnValue as ModValue.StrValue).value)
        assertFalse(outcome.interrupted)
        assertEquals(
            listOf(
                InteractionPhase.PRECONDITION,
                InteractionPhase.PRE,
                InteractionPhase.MAIN,
                InteractionPhase.POST,
                InteractionPhase.SETTLEMENT,
            ),
            context.interactionsRecorder.lastPhaseOrder,
        )
        assertEquals(1, mod.preHookHits)
        assertEquals(1, mod.postHookHits)

        var mainRan = false
        val skipped = context.interactionsRecorder.runOnce(
            actor = actor,
            targets = listOf(actor),
            interactionId = TemplateCapabilityDeclarations.INTERACTION_SKIP,
        ) {
            mainRan = true
            ModValue.of("main-ran")
        }
        assertTrue(skipped.replaced)
        assertFalse(mainRan, "PRE 的 REPLACE 必须跳过 MAIN 原交互体")
        assertEquals("matrix.skipped", (skipped.returnValue as ModValue.StrValue).value)
        assertEquals(2, mod.preHookHits)
        assertEquals(2, mod.postHookHits, "REPLACE 之后 POST 相位照跑")
    }

    @Test
    fun uiRegistersImageVisibilityFollowsSettingsAndClickFailureIsIsolated() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        world.resource(
            TemplateCapabilityDeclarations.PACKAGE_ID,
            TemplateCapabilityDeclarations.UI_BANNER_RESOURCE,
            byteArrayOf(1, 2, 3),
        )
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context
        val ui = context.uiRecorder

        val images = ui.images
        assertEquals(1, images.size)
        assertEquals(TemplateCapabilityDeclarations.UI_BANNER_RESOURCE, images.single().resourceName)
        assertEquals("模板能力矩阵横幅", images.single().altText)
        assertEquals(3, mod.bannerBytes())
        assertEquals(
            1,
            context.resourcesRecorder.count(Access.OPEN, TemplateCapabilityDeclarations.UI_BANNER_RESOURCE),
        )

        assertFalse(ui.visibleKeys().contains(TemplateCapabilityMod.UI_BUTTON_KEY))
        context.settingsRecorder.change(TemplateCapabilityDeclarations.FLAG_KEY, "true")
        assertEquals(
            setOf(TemplateCapabilityMod.UI_BUTTON_KEY, TemplateCapabilityMod.UI_POISON_KEY),
            ui.visibleKeys(),
        )

        val click = ui.click(TemplateCapabilityMod.UI_BUTTON_KEY)
        assertTrue(click.handled)
        assertNotNull(ui.lastClickOutcome)
        assertEquals(1, mod.buttonClicks)

        val poison = ui.click(TemplateCapabilityMod.UI_POISON_KEY)
        assertFalse(poison.handled, "回调抛异常不能让宿主当成交付成功")
        assertNotNull(poison.failure)
        assertTrue(ui.warnings.any { warning -> warning.contains("点击失败已隔离") })
        assertEquals(1, mod.buttonClicks, "失败回调不计入成功点击")
    }

    @Test
    fun randomIsReproduciblePerDecisionSeed() {
        val first = rollWithSeed(42L)
        val second = rollWithSeed(42L)
        val other = rollWithSeed(43L)

        assertEquals(8, first.size)
        assertTrue(first.all { die -> die in 1..6 })
        assertEquals(first, second, "同决策种子 + 同调用序必须逐位复现")
        assertTrue(first != other, "不同决策种子应给出不同抽样")

        val boundaryContext = runMod(
            TemplateCapabilityMod(),
            TestWorld.of(TemplateCapabilityDeclarations),
            TemplateCapabilityDeclarations,
        ).context
        assertFalse(boundaryContext.rng.chance(0), "chance(0) 恒 false")
        assertTrue(boundaryContext.rng.chance(100), "chance(100) 恒 true")
    }

    @Test
    fun timingRecordsScopeAndSettingsChangeBehavior() {
        val world = TestWorld.of(TemplateCapabilityDeclarations)
        val mod = TemplateCapabilityMod()
        val session = runMod(mod, world, TemplateCapabilityDeclarations)
        val context = session.context

        assertEquals(1L, mod.measureWork())
        assertEquals(2L, mod.measureWork())
        val sample = context.timingRecorder.samples()
            .single { it.scope == TemplateCapabilityDeclarations.TIMING_SCOPE }
        assertEquals(2L, sample.count)
        assertTrue(sample.totalNanos >= sample.maxNanos, "聚合总耗时不得小于单次最大值")
        assertTrue(context.timingRecorder.scopeNames().contains(TemplateCapabilityDeclarations.TIMING_SCOPE))
        assertEquals(2L, mod.workCalls)

        assertEquals(TemplateCapabilityMod.FAST_REWARD + DEFAULT_BONUS, mod.reward())
        context.settingsRecorder.change(
            TemplateCapabilityDeclarations.MODE_KEY,
            TemplateCapabilityDeclarations.MODE_SLOW,
        )
        context.settingsRecorder.change(TemplateCapabilityDeclarations.BONUS_KEY, "5")
        assertEquals(TemplateCapabilityMod.SLOW_REWARD + 5L, mod.reward())
        assertTrue(context.settingsRecorder.keys.contains(TemplateCapabilityDeclarations.FLAG_KEY))
    }

    private fun rollWithSeed(seed: Long): List<Int> {
        val world = TestWorld.of(TemplateCapabilityDeclarations, decisionSeed = seed)
        val mod = TemplateCapabilityMod()
        runMod(mod, world, TemplateCapabilityDeclarations)
        return mod.rollChallenge(8)
    }

    private companion object {
        const val DEFAULT_BONUS: Long = 3L
    }
}
