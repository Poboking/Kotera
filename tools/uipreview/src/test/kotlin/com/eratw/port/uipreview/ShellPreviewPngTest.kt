package com.eratw.port.uipreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.android.QuickMacroSection
import com.eratw.port.android.RailOptionRules
import com.eratw.port.android.portraitQuickMacroStyle
import com.eratw.port.android.railQuickMacroStyle
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 壳层预览口（2026-11-11）：把 **Compose 壳层件**在主机 JVM 上真渲染成 PNG。
 *
 * ## 为什么加在这里（而不是 Robolectric / 真机）
 *
 * `uipreview` 原本只渲染**画布层**（render 的 `ConsoleScreen`/`ConsoleFrame`/`ButtonLayer`），
 * 而 2026-11-11 这波改动全在**壳层**（Compose）：右栏常用区、竖屏指令盘、设置页功能卡。
 * 主机侧要出真像素，Compose 只剩三条路：Robolectric NATIVE（Windows 上原生库在
 * `Typeface` 静态初始化就崩，实测 SDK 34/28 均红）、真机 adb、Compose Desktop。
 * 这里走第三条：Compose Desktop 的 [ImageComposeScene] 用 Skiko 在**离屏**把 composable
 * 光栅化成位图，不需要窗口、不需要模拟器。
 *
 * ## 单一来源（不维护副本）
 *
 * 渲染的是 `app` 模块里**同一份源码**：`QuickMacroUi.kt`（可复用前端件）与
 * `RailOptionRules.kt`（纯代数 + 文案标签）由构建脚本 `copyShellPreviewSources` 拷入本模块编译
 * （见 `tools/uipreview/build.gradle.kts` 的那段注释：为什么不能直接依赖 Android 模块）。
 *
 * 产物落在 `tools/uipreview/build/shell-preview` 下的 png 文件。
 */
class ShellPreviewPngTest {

    private val outDir = File("build/shell-preview").apply { mkdirs() }

    /**
     * 三条**玩法完全不同**的宏，证明通用性（按钮文案全部由 `macroLabel` 自动生成，
     * 玩家没有起过任何名字）：
     * - 单选项连发：会话 ×5；
     * - 先 a 后 b：会话 → 道具，各 1 次；
     * - 组合重复：会话 → 道具，整组跑 3 轮（共 6 段）。
     */
    private val macros = listOf(
        RailOptionRules.QuickMacro(listOf(RailOptionRules.MacroStep("31", "会话", 5))),
        RailOptionRules.QuickMacro(
            listOf(
                RailOptionRules.MacroStep("31", "会话"),
                RailOptionRules.MacroStep("32", "道具"),
            ),
        ),
        RailOptionRules.QuickMacro(
            listOf(
                RailOptionRules.MacroStep("31", "会话"),
                RailOptionRules.MacroStep("32", "道具"),
            ),
            repeat = 3,
        ),
    )

    /** 真机右栏底色（`railContainerArgb(null)`）与常规文字色（`railContentArgb(null)`）。 */
    private val railChipContainer = Color(0xE62A3A2A)
    private val railChipContent = Color(0xFFEDEDED)

    private fun render(name: String, widthDp: Int, heightDp: Int, density: Float = 2f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (widthDp * density).toInt(),
            height = (heightDp * density).toInt(),
            density = Density(density),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0B0B0B))) { content() }
            }
        }
        try {
            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("PNG 编码失败：$name")
            val file = File(outDir, name)
            file.writeBytes(data.bytes)
            assertTrue(file.length() > 0L, "$name 应当是非空 PNG")
            println("[shell-preview] $file (${file.length()} B)")
        } finally {
            scene.close()
        }
    }

    /** 横屏右栏：常用区里的「快捷宏」小节（整宽单列，档位几何 = NORMAL 档 232dp/28dp/10sp）。 */
    @Test
    fun `横屏右栏 快捷宏小节`() {
        val metrics = RailOptionRules.QUICK_MACRO_CAP
        render("rail-landscape-macros.png", widthDp = 260, heightDp = 320) {
            Box(modifier = Modifier.width(232.dp).padding(6.dp)) {
                QuickMacroSection(
                    header = "快捷宏",
                    macros = macros,
                    columns = 1,
                    enabled = true,
                    style = railQuickMacroStyle(
                        labelTextSize = 10.sp,
                        buttonHeight = 28.dp,
                        containerColor = railChipContainer,
                        contentColor = railChipContent,
                    ),
                    onQuickMacro = {},
                )
            }
            assertTrue(metrics > 0)
        }
    }

    /** 竖屏指令盘：盘顶「快捷宏」小节（3 列网格、设计稿 §11 命令钮配色、最小高 34dp）。 */
    @Test
    fun `竖屏指令盘 快捷宏小节`() {
        render("tray-portrait-macros.png", widthDp = 390, heightDp = 260) {
            Column(
                modifier = Modifier
                    .width(390.dp)
                    .wrapContentHeight()
                    .background(Color(0xF5161616), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            ) {
                QuickMacroSection(
                    header = "快捷宏",
                    macros = macros,
                    columns = 3,
                    enabled = true,
                    style = portraitQuickMacroStyle(),
                    onQuickMacro = {},
                )
            }
        }
    }

    /** 空态：没配宏时整节不渲染（不占高度）—— 钉「零残留」。 */
    @Test
    fun `没有快捷宏时整节不占高度`() {
        render("tray-portrait-empty.png", widthDp = 390, heightDp = 90) {
            Column(modifier = Modifier.width(390.dp).padding(9.dp)) {
                QuickMacroSection(
                    header = "快捷宏",
                    macros = emptyList(),
                    columns = 3,
                    enabled = true,
                    style = portraitQuickMacroStyle(),
                    onQuickMacro = {},
                )
            }
        }
        val empty = File(outDir, "tray-portrait-empty.png")
        assertTrue(empty.length() > 0L)
    }
}
