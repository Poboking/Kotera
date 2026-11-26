package com.eratw.port.modtesting

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.Registration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 台架 `ui` 录制面自测：登记项可观察、点击可模拟、点击隔离与生产宿主派发层同口径。
 *
 * 断言口径（与 `RecordingUi` KDoc 对应）：
 * 1. `registerInteraction` / `registerImage` 登记后能在 `elements()` / `find` / `visibleKeys()` 看到；
 * 2. `click` 真的驱动回调并落 `clickOutcomes`；不可见 / 不存在 = 不命中且不跑回调；
 * 3. 可见性谓词现求值，抛异常按不可见降级且 WARN 原文与生产 `ModContextImpl` 逐字一致；
 * 4. 回调异常被隔离（`handled = false` + `failure` 记录 + 宿主派发层 WARN），不炸台架；
 * 5. 同键覆盖 / 撤销 / `clear()` 的登记表语义与生产 `InstanceUiRegistry` 同口径。
 */
class RecordingUiTest {

    private class DeclaringMod(private val body: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = body(context)

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newWorld(): TestWorld = TestWorld.ofManifests(ModManifest(packageId = TestWorld.DEFAULT_PACKAGE_ID))

    @Test
    fun `registerInteraction 登记后可在 elements 与 find 中观测`() {
        val world = newWorld()
        try {
            var hits = 0
            val session = runMod(
                DeclaringMod { context -> context.ui.registerInteraction(BTN) { hits++ } },
                world,
            )
            val recorder = session.context.uiRecorder

            assertEquals(listOf(BTN), recorder.elements().map { element -> element.id })
            val record = assertNotNull(recorder.find(BTN))
            assertEquals(RecordingUi.ElementKind.INTERACTION, record.kind)
            assertNotNull(record.onClick)
            assertNull(record.visiblePredicate)
            assertEquals(setOf(BTN), recorder.visibleKeys())

            assertTrue(recorder.click(BTN).handled)
            assertEquals(1, hits)
        } finally {
            world.close()
        }
    }

    @Test
    fun `模拟点击记录 handled 且驱动回调`() {
        val world = newWorld()
        try {
            var hits = 0
            val session = runMod(
                DeclaringMod { context -> context.ui.registerInteraction(BTN) { hits++ } },
                world,
            )
            val recorder = session.context.uiRecorder

            val hit = recorder.click(BTN)
            assertTrue(hit.handled)
            assertNull(hit.failure)
            val miss = recorder.click("NO_SUCH_KEY")
            assertFalse(miss.handled)
            assertNull(miss.failure)

            assertEquals(2, recorder.clickOutcomes.size)
            assertSame(miss, recorder.lastClickOutcome)
            assertEquals(1, hits)
        } finally {
            world.close()
        }
    }

    @Test
    fun `可见性谓词在查询与点击时现求值`() {
        val world = newWorld()
        try {
            var shown = false
            var hits = 0
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerInteraction(BTN, visible = { shown }) { hits++ }
                },
                world,
            )
            val recorder = session.context.uiRecorder
            assertEquals(emptySet(), recorder.visibleKeys())
            assertFalse(recorder.click(BTN).handled)
            assertEquals(0, hits)

            shown = true
            assertEquals(setOf(BTN), recorder.visibleKeys())
            assertTrue(recorder.click(BTN).handled)
            assertEquals(1, hits)
            // 隐藏 ≠ 未登记：诊断超集里始终看得到这一项。
            assertEquals(listOf(BTN), recorder.elements().map { element -> element.id })
        } finally {
            world.close()
        }
    }

    @Test
    fun `谓词异常按不可见降级且 WARN 与生产文案逐字一致`() {
        val world = newWorld()
        try {
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerInteraction(BTN, visible = { error("boom") }) { }
                },
                world,
            )
            val recorder = session.context.uiRecorder
            assertEquals(emptySet(), recorder.visibleKeys())
            val outcome = recorder.click(BTN)
            assertFalse(outcome.handled)
            // 谓词异常不是「回调失败」：outcome 只记不可见，不记 failure。
            assertNull(outcome.failure)

            val expected = "[ui][WARN] 交互项可见性谓词抛异常，按不可见降级 " +
                "pkg=${TestWorld.DEFAULT_PACKAGE_ID} key=$BTN error=java.lang.IllegalStateException: boom"
            assertEquals(listOf(expected, expected), recorder.warnings)
            assertEquals(2, session.context.logEntries.count { entry -> entry.message == expected })
        } finally {
            world.close()
        }
    }

    @Test
    fun `点击回调异常被隔离记录 failure 而不是变成 handled`() {
        val world = newWorld()
        try {
            var explode = true
            var hits = 0
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerInteraction(BTN) {
                        hits++
                        if (explode) error("boom")
                    }
                },
                world,
            )
            val recorder = session.context.uiRecorder

            val failed = recorder.click(BTN)
            assertFalse(failed.handled)
            val failure = assertNotNull(failed.failure)
            assertIs<IllegalStateException>(failure)
            assertEquals(1, hits)
            val expected = "mod.ui 点击失败已隔离 pkg=${TestWorld.DEFAULT_PACKAGE_ID} key=$BTN：" +
                "java.lang.IllegalStateException: boom"
            assertEquals(listOf(expected), recorder.warnings)

            // 隔离后登记表仍可用：回调恢复正常时下一次点击命中。
            explode = false
            assertTrue(recorder.click(BTN).handled)
            assertEquals(2, hits)
        } finally {
            world.close()
        }
    }

    @Test
    fun `同键后声明覆盖后旧 registration 撤销不误删新项`() {
        val world = newWorld()
        val registrations = mutableListOf<Registration>()
        try {
            var firstHits = 0
            var secondHits = 0
            val session = runMod(
                DeclaringMod { context ->
                    registrations += context.ui.registerInteraction(BTN) { firstHits++ }
                    registrations += context.ui.registerInteraction(BTN) { secondHits++ }
                },
                world,
            )
            val recorder = session.context.uiRecorder
            assertEquals(1, recorder.elements().size)

            registrations.first().unregister()
            assertEquals(1, recorder.elements().size)
            assertTrue(recorder.click(BTN).handled)
            assertEquals(0, firstHits)
            assertEquals(1, secondHits)

            registrations.last().unregister()
            assertTrue(recorder.elements().isEmpty())
            assertTrue(recorder.visibleKeys().isEmpty())
        } finally {
            world.close()
        }
    }

    @Test
    fun `撤销后元素消失且点击不命中`() {
        val world = newWorld()
        val registrations = mutableListOf<Registration>()
        try {
            var hits = 0
            val session = runMod(
                DeclaringMod { context ->
                    registrations += context.ui.registerInteraction(BTN) { hits++ }
                },
                world,
            )
            val recorder = session.context.uiRecorder
            registrations.single().unregister()

            assertTrue(recorder.elements().isEmpty())
            assertNull(recorder.find(BTN))
            assertFalse(recorder.click(BTN).handled)
            assertEquals(0, hits)
        } finally {
            world.close()
        }
    }

    @Test
    fun `clear 后 elements uiImages 与点击账本全归零`() {
        val world = newWorld()
        try {
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerInteraction(BTN) { error("boom") }
                    context.ui.registerImage(ICON, altText = ALT)
                },
                world,
            )
            val recorder = session.context.uiRecorder
            recorder.click(BTN)
            assertEquals(1, recorder.clickOutcomes.size)
            assertEquals(1, recorder.warnings.size)

            recorder.clear()
            assertTrue(recorder.elements().isEmpty())
            assertTrue(session.context.uiImages.isEmpty())
            assertTrue(recorder.clickOutcomes.isEmpty())
            assertTrue(recorder.warnings.isEmpty())
            assertNull(recorder.lastClickOutcome)

            // 清空后旧账不复活：再点不命中、不产生 failure。
            val afterClear = recorder.click(BTN)
            assertFalse(afterClear.handled)
            assertNull(afterClear.failure)
        } finally {
            world.close()
        }
    }

    @Test
    fun `示范用例 runMod 里登记两项 点击一项并读资源`() {
        val world = newWorld()
        try {
            world.resourceText(GREETING_FILE, GREETING_TEXT)
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerInteraction(BTN) { context.logger.info("clicked") }
                    context.ui.registerImage(ICON, altText = ALT)
                },
                world,
            )
            val context = session.context
            assertEquals(setOf(BTN), context.uiRecorder.visibleKeys())
            assertEquals(1, context.uiImages.size)
            assertTrue(context.uiRecorder.click(BTN).handled)
            assertTrue(context.logEntries.any { entry -> entry.message == "clicked" })

            assertEquals(GREETING_TEXT, context.resources.open(GREETING_FILE)?.toString(Charsets.UTF_8))
            assertEquals(RecordingResources.Access.OPEN, context.resourcesRecorder.calls.last().access)
        } finally {
            world.close()
        }
    }

    private companion object {
        const val BTN: String = "BTN"
        const val ICON: String = "icons/hello.png"
        const val ALT: String = "打招呼"
        const val GREETING_FILE: String = "greetings/hello.txt"
        const val GREETING_TEXT: String = "台架资源：こんにちは"
    }
}
