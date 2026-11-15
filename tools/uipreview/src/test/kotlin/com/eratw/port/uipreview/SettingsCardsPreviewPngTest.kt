package com.eratw.port.uipreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.android.MacroDraft
import com.eratw.port.android.MacroEditorBody
import com.eratw.port.android.MacroEditorViewState
import com.eratw.port.android.RailFilterActions
import com.eratw.port.android.RailFilterCard
import com.eratw.port.android.RailOptionRules
import com.eratw.port.android.RailPinnedActions
import com.eratw.port.android.RailPinnedCard
import com.eratw.port.android.RailQuickMacroCard
import com.eratw.port.android.SettingsLanguage
import com.eratw.port.android.SettingsStrings
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 设置页三张长名单卡的真像素预览：24 隐藏 + 8 置顶 + 20 历史 + 6 宏。 */
class SettingsCardsPreviewPngTest {

    private fun render(
        name: String,
        widthDp: Int = 420,
        heightDp: Int = 1280,
        content: @Composable () -> Unit,
    ) {
        val density = 2f
        val scene = ImageComposeScene(
            width = (widthDp * density).toInt(),
            height = (heightDp * density).toInt(),
            density = Density(density),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF080808)).padding(12.dp)) { content() }
            }
        }
        try {
            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            val file = File(File("build/settings-preview").apply { mkdirs() }, name)
            file.writeBytes(data.bytes)
            assertTrue(file.length() > 0L)
            println("[settings-preview] $file (${file.length()} B)")
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderLongListsCollapsed() {
        val text = SettingsStrings.of(SettingsLanguage.ZH)
        val history = (1..20).map { RailOptionRules.RailHistoryEntry("$it", "最近指令 $it") }
        val macros = (1..6).map { index ->
            RailOptionRules.QuickMacro(listOf(RailOptionRules.MacroStep("$index", "会话 $index")))
        }
        render("settings-long-lists.png") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("右栏过滤：24 条隐藏，收起态", color = Color(0xFFBDBDBD), fontSize = 11.sp)
                RailFilterCard(
                    text = text,
                    hideUnnumbered = true,
                    actions = RailFilterActions(
                        onHideUnnumbered = {},
                        onRestore = {},
                        onClearHidden = {},
                    ),
                    hiddenLabels = (1..24).map { "隐藏按钮 $it" }.toSet(),
                )
                Spacer(Modifier.height(10.dp))
                Text("常用置顶：8 枚置顶 + 20 条历史，收起态", color = Color(0xFFBDBDBD), fontSize = 11.sp)
                RailPinnedCard(
                    text = text,
                    pinned = (1..8).map { it.toString() },
                    history = history,
                    pinnedCap = 8,
                    actions = RailPinnedActions(
                        onUnpin = {},
                        onPin = {},
                        onClearPinned = {},
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Text("快捷宏：6 条，收起态只露 3 条", color = Color(0xFFBDBDBD), fontSize = 11.sp)
                RailQuickMacroCard(
                    text = text,
                    macros = macros,
                    recent = history.take(6),
                    onAdd = { true },
                    onUpdate = { _, _ -> },
                    onDelete = {},
                    onClear = {},
                )
            }
        }
    }
    @Test
    fun renderMacroEditorWizard() {
        val text = SettingsStrings.of(SettingsLanguage.ZH)
        val history = listOf(
            RailOptionRules.RailHistoryEntry("31", "会话"),
            RailOptionRules.RailHistoryEntry("32", "道具"),
            RailOptionRules.RailHistoryEntry("33", "休息"),
            RailOptionRules.RailHistoryEntry("34", "出门"),
            RailOptionRules.RailHistoryEntry("35", "吃饭"),
            RailOptionRules.RailHistoryEntry("36", "洗澡"),
        )
        val draft = MacroDraft()
            .addStep("31", "会话")
            .updateStepTimes(index = 0, times = 5)
            .addStep("32", "道具")
            .copy(repeat = "3")
        render("settings-macro-editor.png", widthDp = 420, heightDp = 980) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF2A2A2A),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("新增快捷宏", color = Color(0xFFEDEDED), fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    MacroEditorBody(
                        text = text,
                        state = MacroEditorViewState(draft = draft, issueText = null, staleEdit = false),
                        recent = history,
                        onDraftChange = {},
                    )
                }
            }
        }
    }
}
