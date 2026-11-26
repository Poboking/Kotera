package com.eratw.port.modtemplate

import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modtesting.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模板 mod 的「作者面真跑」闭环：三语文案（v10）、浮层图片（v8）、音频（v11）都不是注释示例，
 * 而是 [TemplateMod] 生命周期里真跑的调用 —— 用例在 `:mod-testing` 台架上走一遍并断言落点。
 *
 * 音频面走台架的**录制型**替身：[TestWorld.newContext] 拿到的 `context.audio` 与生产
 * `PackageAudioApi` 同一套降级链（资源从台架资源目录现读；缺资源 / 非法名 ⇒ WARN + `false`），
 * 受理成功的调用落 `context.audioRecorder`。所以这里先 `world.resource(...)` 把随包 BGM 放进去，
 * 再断言「播了哪条 / loop / 音量 / 停了几次」—— 作者照抄这段即可给自己的 mod 测音频调用。
 */
class TemplateShowcaseTest {

    @Test
    fun onLoadDeclaresThreeLanguageGreeting() {
        val world = TestWorld.of(TemplateDeclarations)
        val context = world.newContext(TemplateDeclarations.PACKAGE_ID)

        TemplateMod().onLoad(context)

        assertEquals(
            setOf(TemplateMod.GREETING_KEY),
            context.stringsRecorder.keys(),
            "onLoad 应声明三语文案键（v10）",
        )
        assertEquals("模板 Mod 已装上：按 D 打开调试面板", context.stringsRecorder.text(TemplateMod.GREETING_KEY, "zh"))
        assertEquals(
            "Template mod loaded: press D to open the debug panel",
            context.stringsRecorder.text(TemplateMod.GREETING_KEY, "en"),
        )
        assertEquals(
            "テンプレート Mod を読み込みました：D でデバッグパネルを開く",
            context.stringsRecorder.text(TemplateMod.GREETING_KEY, "ja"),
        )

        context.stringsRecorder.languageCode = "en"
        assertEquals(
            "Template mod loaded: press D to open the debug panel",
            context.strings.of(TemplateMod.GREETING_KEY),
            "of() 应跟随当前语言取原文（v10）",
        )
    }

    @Test
    fun onEnableRegistersBannerImageWithLocalizedAltText() {
        val world = TestWorld.of(TemplateDeclarations)
        val context = world.newContext(TemplateDeclarations.PACKAGE_ID)
        val mod = TemplateMod()

        mod.onLoad(context)
        mod.onEnable()

        val images = context.uiImages
        assertEquals(1, images.size, "onEnable 应登记一张浮层图片（v8）：$images")
        val image = images.single()
        assertEquals(TemplateMod.BANNER_RESOURCE, image.resourceName)
        assertEquals(ModUiElement.Image.SUGGESTED_SIDE_PX, image.maxSidePx)
        assertEquals(
            context.stringsRecorder.text(TemplateMod.GREETING_KEY, "zh"),
            image.altText,
            "altText 应取三语文案面（v8 × v10 联动）",
        )

        mod.onDisable()
        assertTrue(context.uiImages.isEmpty(), "onDisable 应撤销图片登记（Registration 退回）")
    }

    @Test
    fun onEnablePlaysBgmAndOnDisableStopsIt() {
        val world = TestWorld.of(TemplateDeclarations)
        // 台架资源目录按真引擎布局落盘；缺了它 v11 的 play 会按生产语义 WARN + 返 false。
        world.resource(TemplateDeclarations.PACKAGE_ID, TemplateMod.BGM_RESOURCE, byteArrayOf(1, 2, 3, 4))
        try {
            val context = world.newContext(TemplateDeclarations.PACKAGE_ID)
            val mod = TemplateMod()

            mod.onLoad(context)
            mod.onEnable()

            val audio = context.audioRecorder
            assertEquals(1, audio.plays.size, "onEnable 应真播一次随包 BGM（v11）")
            val play = audio.plays.single()
            assertEquals(TemplateMod.BGM_RESOURCE, play.resourceName)
            assertTrue(play.loop, "BGM 应循环播放")
            assertEquals(TemplateMod.BGM_VOLUME, play.volume)
            assertEquals(0, audio.stopCount, "onEnable 阶段不该停")
            assertEquals(play, audio.current, "当前曲目应是 onEnable 播的这条（后一条顶掉前一条）")
            assertTrue(audio.warnings.isEmpty(), "资源在盘上时不该落降级 WARN：${audio.warnings}")

            mod.onDisable()
            assertEquals(1, audio.stopCount, "onDisable 应收掉 BGM（v11：停播放是 mod 自己的责任）")
            assertNull(audio.current, "stop 后当前曲目应为空")
        } finally {
            world.close()
        }
    }
}
