package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 装载期启动快照的**窗口判据**（`CONTRACT-RT2-CFG16B` / SPEC-RULING 序 16 收尾增量）。
 *
 * 钉的是「会话不再有第二次读盘」这一条因果：
 *
 * 1. 先按生产装配序建快照：`RootedBuiltinHost.applyChainTo(store)` →（`GameHost` 单点）
 *    `ConfigMethods.backfillWindowWidth` → 原始条目表 `configOverrides` → 度量两项；
 * 2. **改写 / 删除盘上 `emuera.config`**（字体、字号、行高、日志行数、`BUTTON WRAPPING`、
 *    存档二进制开关、窗口宽全部换值）；
 * 3. 用旧快照构造 `GameSession` ⇒ 会话生效值仍等于快照值，与盘上新值无关。
 *
 * 反证口径：把 `GameSession` 的快照分支摘掉（回到 `rooted.configOverrides` + 自建链），
 * 本类第 1、2 条用例即红 —— 它们断言的每一个键都只在**第二次读盘**时才会变成盘上新值。
 */
class GameSessionBootConfigSnapshotTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 最小产物：`@SYSTEM_TITLE` + 一句 `PRINTL`（同 GameSessionWindowBackfillTest 口径）。 */
    private fun session(
        root: Path,
        snapshot: BootViewMetrics?,
        windowWidthScriptPx: Long = 0L,
        externalStore: ConfigStore? = null,
    ): GameSession {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexed { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(
                LogicalLine(kind, "TEST.ERB", i + 1, text),
            )
        }.filterNotNull()
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program: Program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
        )
        return GameSession(
            gameRoot = root,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 1L,
            configStore = externalStore,
            windowWidthScriptPx = windowWidthScriptPx,
            bootViewMetrics = snapshot,
        )
    }

    /**
     * 生产装配序（`GameHost.applyBootFontMetrics` + `GameHost.start()`）：一次读盘同时掰出
     * 链后 store、原始条目表与度量两项；`WindowX` 按 View 实测脚本 px 回填，**单点**。
     */
    private fun snapshotOf(root: Path, windowWidthScriptPx: Long = 0L, backfill: Boolean = true): BootViewMetrics {
        val store = ConfigStore()
        val host = RootedBuiltinHost(root)
        host.applyChainTo(store)
        if (backfill) ConfigMethods.backfillWindowWidth(store, windowWidthScriptPx)
        val effective = EffectiveConfig.of(store)
        return BootViewMetrics(effective.fontSize, effective.lineHeight)
            .copy(configStore = store, rawEntries = host.configOverrides)
    }

    /** 盘上 `emuera.config` 的一组可切换值（快照前/改写后各写一份）。 */
    private data class Cfg(
        val fontSize: Int = 18,
        val lineHeight: Int = 19,
        val fontName: String = "SNAPSHOT_FONT",
        val maxLog: Int = 900,
        val buttonWrap: Boolean = false,
        val windowWidth: Int = 760,
        val binarySave: Boolean = false,
    )

    private fun writeConfig(root: Path, cfg: Cfg) {
        Files.writeString(
            root.resolve("emuera.config"),
            buildString {
                appendLine("FONT SIZE:${cfg.fontSize}")
                appendLine("LINE HEIGHT:${cfg.lineHeight}")
                appendLine("FONT NAME:${cfg.fontName}")
                appendLine("MAX HISTORY LOG LINES:${cfg.maxLog}")
                appendLine("BUTTON WRAPPING:" + if (cfg.buttonWrap) "YES" else "NO")
                appendLine("WINDOW WIDTH:${cfg.windowWidth}")
                appendLine("USE THE BINARY FORMAT FOR SAVING DATA:" + if (cfg.binarySave) "YES" else "NO")
            },
        )
    }

    // ------------------------------------------------------------------
    // 核心判据：窗口（快照 → 改写盘 → 会话仍用快照）
    // ------------------------------------------------------------------

    @Test
    fun `窗口判据 - 快照建成后改写盘上配置 会话生效值仍等于快照`() {
        writeConfig(
            dir,
            Cfg(fontSize = 31, lineHeight = 40, maxLog = 1234, buttonWrap = true, windowWidth = 900),
        )
        val snapshot = snapshotOf(dir, windowWidthScriptPx = 1500L)
        // 快照建好之后改写盘上 emuera.config：每个被会话消费的键都换成另一档值。
        writeConfig(
            dir,
            Cfg(
                fontSize = 12, lineHeight = 13, fontName = "DISK_POISON",
                maxLog = 777, windowWidth = 1234, binarySave = true,
            ),
        )

        val s = session(dir, snapshot, windowWidthScriptPx = 1500L)
        try {
            assertSame(snapshot.configStore, s.configStore, "会话条目表必须是快照里的同一份 store（不是同值的新表）")
            // 度量两项（A/L7 语义保留）
            assertEquals(31, s.scriptFontPixelSize, "字号取快照（View 排版同一份），盘上 12 不生效")
            assertEquals(31, s.console.scriptFontSize, "sink 的 HTML 折行尺 = 会话生效字号（快照 store 派生）")
            assertEquals(31L, s.builtinHost.fontSize, "GETCONFIG 尺与脚本尺同源（盘上 12 不生效）")
            // 原始条目表消费点（字体名 / 日志行数 / BUTTON WRAPPING）
            assertEquals("SNAPSHOT_FONT", s.console.defaultFontName, "字体名取快照原始条目表")
            assertEquals(1234, s.console.maxLines, "日志行数取快照原始条目表（盘上 777 不生效）")
            assertTrue(s.console.buttonWrap, "BUTTON WRAPPING:YES 取快照（盘上 NO 不生效）")
            // effective 从同一份 store 派生
            assertEquals(31, s.configStore.itemOf(ConfigKey.FontSize).int(), "生效字号来自快照 store")
            assertEquals(40, s.configStore.itemOf(ConfigKey.LineHeight).int(), "生效行高来自快照 store")
            assertEquals(
                false,
                s.store.config.systemSaveInBinary,
                "SystemSaveInBinary 同走快照原始条目表（盘上改 YES 不生效，Program.cs:95 单实例口径）",
            )
            // GameHost 单点回填的产物原样保留（会话不重复回填）
            assertEquals(1500, s.configStore.itemOf(ConfigKey.WindowX).int(), "WindowX = GameHost 用 View 实宽做的链后回填")
        } finally {
            s.close()
        }
    }

    @Test
    fun `有快照时零自读 - 盘上配置删除后仍用快照原始条目表`() {
        writeConfig(
            dir,
            Cfg(fontSize = 31, lineHeight = 40, maxLog = 1234, buttonWrap = true, windowWidth = 900),
        )
        val snapshot = snapshotOf(dir)
        // 会话构造前把盘上配置整个删掉：只要会话还自读一次 configOverrides，这些键就回落出厂缺省。
        Files.delete(dir.resolve("emuera.config"))

        val s = session(dir, snapshot)
        try {
            assertEquals("SNAPSHOT_FONT", s.console.defaultFontName, "文件已删 ⇒ 会话没再读盘（否则回落 ＭＳ ゴシック）")
            assertEquals(1234, s.console.maxLines, "文件已删 ⇒ 会话没再读盘（否则回落会话缺省 5000）")
            assertTrue(s.console.buttonWrap, "文件已删 ⇒ BUTTON WRAPPING 仍取快照")
            assertEquals(31, s.scriptFontPixelSize, "度量两项与 store 同源（文件已删不影响）")
            assertFalse(s.store.config.systemSaveInBinary, "存档二进制开关取快照原始条目表")
        } finally {
            s.close()
        }
    }

    @Test
    fun `快照 store 不再二次回填 - 会话侧保持宿主算出的 WindowX`() {
        writeConfig(dir, Cfg(windowWidth = 777))
        // 未回填的快照（模拟宿主还没接线）：会话侧不得借自己的 windowWidthScriptPx 补写。
        val snapshot = snapshotOf(dir, windowWidthScriptPx = 1500L, backfill = false)
        val s = session(dir, snapshot, windowWidthScriptPx = 1500L)
        try {
            assertEquals(
                777,
                s.configStore.itemOf(ConfigKey.WindowX).int(),
                "有快照 ⇒ 会话不回填（禁止双重回填；回填单点在 GameHost）",
            )
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 零变化：无快照 / 半快照 / 外部 store
    // ------------------------------------------------------------------

    @Test
    fun `无快照时保持既有自读与回填语义`() {
        writeConfig(
            dir,
            Cfg(fontSize = 12, lineHeight = 13, fontName = "DISK_FONT", maxLog = 777, binarySave = true),
        )
        val s = session(dir, snapshot = null, windowWidthScriptPx = 1309L)
        try {
            assertEquals(12, s.scriptFontPixelSize, "无快照 ⇒ 回落本会话 EffectiveConfig（headless / 装置口径不变）")
            assertEquals("DISK_FONT", s.console.defaultFontName, "无快照 ⇒ 自读盘上原始条目表")
            assertEquals(777, s.console.maxLines)
            assertEquals(
                1309,
                s.configStore.itemOf(ConfigKey.WindowX).int(),
                "无快照 ⇒ 会话在链后自行回填（既有语义逐位不变）",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `半快照只有度量两项时仍走缺省回落`() {
        writeConfig(
            dir,
            Cfg(fontSize = 12, lineHeight = 13, fontName = "DISK_FONT", maxLog = 777, binarySave = true),
        )
        val s = session(dir, BootViewMetrics(fontSize = 31, lineHeight = 40), windowWidthScriptPx = 1309L)
        try {
            assertEquals(31, s.scriptFontPixelSize, "A/L7 语义：度量两项取快照")
            assertEquals("DISK_FONT", s.console.defaultFontName, "没有 store ⇒ 条目表仍走会话自读（零变化）")
            assertEquals(1309, s.configStore.itemOf(ConfigKey.WindowX).int(), "没有 store ⇒ 会话自行回填（零变化）")
        } finally {
            s.close()
        }
    }

    @Test
    fun `显式传入 store 仍优先于快照且不回填`() {
        writeConfig(
            dir,
            Cfg(
                fontSize = 12, lineHeight = 13, fontName = "DISK_FONT",
                maxLog = 777, windowWidth = 900, binarySave = true,
            ),
        )
        val snapshot = snapshotOf(dir, windowWidthScriptPx = 1500L)
        val external = ConfigStore()
        val s = session(dir, snapshot, windowWidthScriptPx = 1500L, externalStore = external)
        try {
            assertSame(external, s.configStore, "显式装配者给的条目表是权威（K-CONFIGWIRE 既有语义）")
            assertEquals(
                760,
                s.configStore.itemOf(ConfigKey.WindowX).int(),
                "外部传入 store ⇒ 不回填（也不吃快照的 1500）",
            )
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // CONTRACT-RT2-CFG16C：第三次读盘（RootedBuiltinHost 构造器）也收成零
    // ------------------------------------------------------------------

    /** `_Replace.csv` 是 REPLACE 组（货币标签 / 位置）的唯一写入口（Process.cs:240-270）。 */
    private fun writeReplaceCsv(root: Path, moneyLabel: String, moneyFirst: Boolean) {
        val csv = Files.createDirectories(root.resolve("CSV"))
        Files.writeString(
            csv.resolve("_Replace.csv"),
            buildString {
                appendLine("お金の単位,$moneyLabel")
                appendLine("単位の位置," + if (moneyFirst) "YES" else "NO")
            },
        )
    }

    @Test
    fun `窗口判据延伸 - 快照后改写盘上配置 宿主的货币名与文件读数仍取快照`() {
        writeConfig(dir, Cfg(fontName = "SNAPSHOT_FONT", maxLog = 1234, windowWidth = 900))
        writeReplaceCsv(dir, "￥", moneyFirst = false)
        val snapshot = snapshotOf(dir)
        // 区分两种候选目录口径：根目录与 sav/ 各放一个不同槽位的存档。
        Files.writeString(dir.resolve("save00.sav"), "dummy")
        Files.createDirectories(dir.resolve("sav"))
        Files.writeString(dir.resolve("sav").resolve("save01.sav"), "dummy")
        // 快照之后改写盘上两份配置：emuera.config 与 _Replace.csv 全部换成反向值。
        Files.writeString(
            dir.resolve("emuera.config"),
            buildString {
                appendLine("FONT NAME:DISK_POISON")
                appendLine("MAX HISTORY LOG LINES:777")
                appendLine("USE SAV FOLDER:YES")
                appendLine("WINDOW WIDTH:1234")
            },
        )
        writeReplaceCsv(dir, "$", moneyFirst = true)

        val s = session(dir, snapshot)
        try {
            assertEquals("￥", s.builtinHost.moneyLabel, "货币名取快照（盘上 _Replace.csv 的 $ 不生效）")
            assertFalse(s.builtinHost.moneyLabelFirst, "货币位置取快照（盘上 YES 不生效）")
            assertEquals(
                snapshot.rawEntries,
                s.builtinHost.configOverrides,
                "宿主原始条目表 = 快照那一份（不再第三次读盘）",
            )
            assertEquals(1234, s.console.maxLines, "日志行数取快照原始条目表（盘上 777 不生效）")
            assertFalse(s.configStore.itemOf(ConfigKey.UseSaveFolder).bool(), "会话条目表取快照")
            // 文件宿主读数：候选目录由 UseSaveFolder 决定（Config.cs:471-481）——
            // 快照 false ⇒ 只探根目录；盘上 YES 不得让 sav/ 进入候选。
            assertNull(s.builtinHost.checkSaveData(0L, SaveFileKind.NORMAL), "快照 false ⇒ 根目录的 save00.sav 命中")
            assertEquals(
                EraDataState.FILENOTFOUND,
                s.builtinHost.checkSaveData(1L, SaveFileKind.NORMAL)?.state,
                "快照 false ⇒ 不探 sav/（盘上 YES 不生效）",
            )
        } finally {
            s.close()
        }
    }
}
