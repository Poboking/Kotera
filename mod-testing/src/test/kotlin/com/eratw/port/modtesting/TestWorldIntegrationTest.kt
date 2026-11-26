package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.JobContext
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.bind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `runMod` + 录制型能力面的集成自测：生命周期、任务/事件/钩子触发、fail-closed 拒绝。 */
class TestWorldIntegrationTest {

    private val world = TestSchema.world()

    @Test
    fun runModLoadsAndEnablesMod() {
        val mod = RecordingMod()
        val session = runMod(mod, world, TestSchema.PACKAGE_ID)
        assertTrue(mod.loaded)
        assertTrue(mod.enabled)
        assertEquals(TestSchema.PACKAGE_ID, session.context.packageId)
        assertEquals(setOf(normalize(TestSchema.TICK.name)), session.context.boundJobIds)
    }

    @Test
    fun fireJobDispatchesToBoundHandler() {
        val mod = RecordingMod()
        val session = runMod(mod, world, TestSchema.PACKAGE_ID)
        assertTrue(session.context.fireJob(TestSchema.TICK))
        assertEquals(1, mod.ticks)
        assertFalse(session.context.fireJob("test.not.bound"))
    }

    @Test
    fun dispatchEventAndHookReachRegistrations() {
        val mod = RecordingMod()
        val session = runMod(mod, world, TestSchema.PACKAGE_ID)
        assertEquals(listOf(EventResult.CONTINUE), session.context.dispatchEvent(RecordingMod.EVENT_KEY))
        assertEquals(HookResult.Continue, session.context.dispatchHook(TestSchema.PING.name))
        assertTrue(session.context.logEntries.isEmpty())
    }

    @Test
    fun bindingUndeclaredJobIsRejected() {
        val mod = RecordingMod()
        val session = runMod(mod, world, TestSchema.PACKAGE_ID)
        assertFailsWith<IllegalArgumentException> {
            session.context.jobs.bind("test.not.declared") {}
        }
    }

    @Test
    fun registerEventInOnlyBucketIsRejected() {
        val mod = RecordingMod()
        val session = runMod(mod, world, TestSchema.PACKAGE_ID)
        assertFailsWith<IllegalArgumentException> {
            session.context.events.register("TEST.EVENT", EventBucket.ONLY) { EventResult.CONTINUE }
        }
    }

    @Test
    fun disableAndUnloadFireLifecycle() {
        val mod = RecordingMod()
        val session = runMod(mod, world, TestSchema.PACKAGE_ID)
        session.disable()
        session.unload()
        assertTrue(mod.disabled)
        assertTrue(mod.unloaded)
        session.newGame()
        session.saveLoaded()
        assertTrue(mod.newGameSeen)
        assertTrue(mod.saveLoadedSeen)
    }

    private class RecordingMod : EraMod {

        lateinit var context: ModContext

        var loaded = false
        var enabled = false
        var disabled = false
        var unloaded = false
        var newGameSeen = false
        var saveLoadedSeen = false
        var ticks = 0

        override fun onLoad(context: ModContext) {
            this.context = context
            loaded = true
        }

        override fun onEnable() {
            enabled = true
            context.jobs.bind(TestSchema.TICK) { ticks += 1 }
            context.events.register(EVENT_KEY, EventBucket.NORMAL) { EventResult.CONTINUE }
            context.hooks.register(TestSchema.PING.name, HookPhase.PREFIX) { HookResult.Continue }
        }

        override fun onDisable() {
            disabled = true
        }

        override fun onUnload() {
            unloaded = true
        }

        override fun onNewGame() {
            newGameSeen = true
        }

        override fun onSaveLoaded() {
            saveLoadedSeen = true
        }

        companion object {
            const val EVENT_KEY: String = "TEST.EVENT"
        }
    }
}
