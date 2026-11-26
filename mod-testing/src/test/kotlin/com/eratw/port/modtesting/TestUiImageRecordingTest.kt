package com.eratw.port.modtesting

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.modUiImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 台架 `ui.registerImage` 的录制面自测：作者用例要能断言「我真的声明了这张图」。
 *
 * `ui.registerInteraction` 原先在本台架是 fail-closed（担心静默 no-op 造成假绿）；现已升级为
 * **录制型**（[RecordingUi]）：生产 `InstanceUiRegistry` 本来就支持交互项登记与可见性谓词，
 * 台架此前是缺口。旧用例「不许静默吞调用」的意图由新断言承接 —— 登记后必须能在
 * [RecordingUi.elements] / [RecordingUi.visibleKeys] 里看到，点击必须真的驱动回调。
 *
 * 声明期校验与生产同口径：非法资源名 / 越界边长的 [IllegalArgumentException] 在
 * `onLoad` 抛出，不被台架吞掉。
 */
class TestUiImageRecordingTest {

    private class DeclaringMod(private val body: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = body(context)

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newWorld(): TestWorld = TestWorld.ofManifests(ModManifest(packageId = TestWorld.DEFAULT_PACKAGE_ID))

    @Test
    fun `声明的图片元素在台架可见且字段保真`() {
        val world = newWorld()
        try {
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerImage(
                        resourceName = IMAGE.resourceName,
                        altText = IMAGE.altText,
                        maxSidePx = IMAGE.maxSidePx,
                    )
                },
                world,
            )
            assertEquals(listOf(IMAGE), session.context.uiImages)
        } finally {
            world.close()
        }
    }

    @Test
    fun `撤销注册后图片元素立即消失`() {
        val world = newWorld()
        val registrations = mutableListOf<Registration>()
        try {
            val session = runMod(
                DeclaringMod { context ->
                    registrations += context.ui.registerImage(IMAGE.resourceName, IMAGE.altText, IMAGE.maxSidePx)
                },
                world,
            )
            assertEquals(1, session.context.uiImages.size)
            registrations.single().unregister()
            assertTrue(session.context.uiImages.isEmpty())
        } finally {
            world.close()
        }
    }

    @Test
    fun `非法资源名在声明期抛 IllegalArgumentException`() {
        val world = newWorld()
        try {
            assertFailsWith<IllegalArgumentException> {
                runMod(DeclaringMod { context -> context.ui.registerImage("../escape.png") }, world)
            }
        } finally {
            world.close()
        }
    }

    @Test
    fun `越界最大边长在声明期抛 IllegalArgumentException`() {
        val world = newWorld()
        try {
            assertFailsWith<IllegalArgumentException> {
                runMod(
                    DeclaringMod { context ->
                        context.ui.registerImage(
                            IMAGE.resourceName,
                            maxSidePx = ModUiElement.Image.MAX_SIDE_PX + 1,
                        )
                    },
                    world,
                )
            }
        } finally {
            world.close()
        }
    }

    @Test
    fun `registerInteraction 从 fail-closed 变为录制型而图片声明不受影响`() {
        val world = newWorld()
        try {
            var hits = 0
            val session = runMod(
                DeclaringMod { context ->
                    context.ui.registerImage(IMAGE.resourceName)
                    context.ui.registerInteraction("BTN") { hits++ }
                },
                world,
            )
            val recorder = session.context.uiRecorder
            // 旧意图「不许静默吞调用」= 登记后必须能在观察面看到，而不是抛 UOE。
            // 观察面顺序：交互项先按登记序，图片再按登记序。
            assertEquals(listOf("BTN", IMAGE.resourceName), recorder.elements().map { element -> element.id })
            assertEquals(setOf("BTN"), recorder.visibleKeys())
            assertTrue(recorder.click("BTN").handled)
            assertEquals(1, hits)
            assertEquals(1, session.context.uiImages.size)
        } finally {
            world.close()
        }
    }

    private companion object {
        val IMAGE: ModUiElement.Image =
            modUiImage("images/bg01.png", altText = "替代文本", maxSidePx = 256)
    }
}
