package com.eratw.port.core.proc

import com.eratw.port.core.config.ConfigChainRole
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigLayerPresence
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import java.io.File
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
 * `RootedBuiltinHost` 的开局快照（`CONTRACT-RT2-CFG16C`）：第三次读盘收成零。
 *
 * A/L7 + L9B 已让 View 度量与会话配置段共用 `GameHost` 的一次读取，但 `GameSession`
 * 构造 `RootedBuiltinHost(gameRoot, …)` 时该宿主内部仍会自行 `loadChainFromDisk` +
 * `_Replace.csv`。本类钉的是**同一份快照供数**：
 *
 * 1. 快照建成后改写盘上 `emuera.config` / `CSV/_Replace.csv` ⇒ 宿主的货币名 /
 *    货币位置 / 原始条目表 / `UseSaveFolder`（文件宿主读数 = `checkSaveData` 的候选目录）
 *    仍取快照；
 * 2. `ConfigMethods.storeFromHost`（`GETCONFIG` 的播种通道）把快照 store 原样复制过去，
 *    整表等于快照，不重读、不重放文件层；
 * 3. 行为哨兵：根目录**不存在**时快照宿主照样给出快照读数 —— 有快照路径一次盘都不读；
 * 4. 源码哨兵：快照分支是唯一供数口，会话只构造一个 rooted 宿主。
 *
 * 反证口径（交付报告实跑）：① 摘 `RootedBuiltinHost` 的快照供数（宿主回自读）⇒ 本类第 1/2/3 条
 * 红（读到盘上 poison / 空盘回落注册默认）；② 摘 `GameSession` 的 `configSnapshot` 透传 ⇒
 * `GameSessionBootConfigSnapshotTest` 的 `builtinHost` 断言红。
 */
class RootedBuiltinHostConfigSnapshotTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun writeConfig(
        root: Path,
        fontName: String,
        maxLog: Int,
        useSavFolder: Boolean,
        windowWidth: Int = 760,
    ) {
        Files.writeString(
            root.resolve("emuera.config"),
            buildString {
                appendLine("FONT NAME:$fontName")
                appendLine("FONT SIZE:18")
                appendLine("MAX HISTORY LOG LINES:$maxLog")
                appendLine("USE SAV FOLDER:" + if (useSavFolder) "YES" else "NO")
                appendLine("WINDOW WIDTH:$windowWidth")
            },
        )
    }

    /** `_Replace.csv` 是 REPLACE 组（货币标签 / 位置）的唯一写入口（Process.cs:240-270）。 */
    private fun writeReplace(root: Path, moneyLabel: String, moneyFirst: Boolean) {
        val csv = Files.createDirectories(root.resolve("CSV"))
        Files.writeString(
            csv.resolve("_Replace.csv"),
            buildString {
                appendLine("お金の単位,$moneyLabel")
                appendLine("単位の位置," + if (moneyFirst) "YES" else "NO")
            },
        )
    }

    /**
     * 生产读取序（同 `GameHost.applyBootFontMetrics`）：一次读盘同时掰出链后 store 与
     * 原始条目表；两者就是本类交给 `RootedBuiltinHost` 的快照。
     */
    private fun readSnapshot(root: Path): RootedConfigSnapshot {
        val store = ConfigStore()
        val reader = RootedBuiltinHost(root)
        reader.applyChainTo(store)
        return RootedConfigSnapshot(reader.configOverrides, store)
    }

    // ------------------------------------------------------------------
    // 行为：快照供数
    // ------------------------------------------------------------------

    @Test
    fun `快照在位 - 改写盘上配置后宿主读数仍取快照`() {
        writeConfig(dir, "SNAPSHOT_FONT", maxLog = 1234, useSavFolder = false, windowWidth = 900)
        writeReplace(dir, "￥", moneyFirst = false)
        val snapshot = readSnapshot(dir)
        // 快照已定：根目录放 save00.sav、sav/ 放 save01.sav，用来区分两种候选目录口径。
        Files.writeString(dir.resolve("save00.sav"), "dummy")
        Files.createDirectories(dir.resolve("sav"))
        Files.writeString(dir.resolve("sav").resolve("save01.sav"), "dummy")
        // 快照之后改写盘上配置：每个被宿主消费的键都换成反向值。
        writeConfig(dir, "DISK_POISON", maxLog = 777, useSavFolder = true, windowWidth = 1234)
        writeReplace(dir, "$", moneyFirst = true)
        // 反证前半：盘上读数确实已换成反向值（否则下面的断言不具区分力）。
        val onDisk = RootedBuiltinHost(dir)
        assertEquals("$", onDisk.moneyLabel, "盘上 _Replace.csv 已改写为美元")
        assertTrue(onDisk.useSaveFolder, "盘上 emuera.config 已改写为 USE SAV FOLDER:YES")

        val host = RootedBuiltinHost(dir, configSnapshot = snapshot)

        assertEquals("￥", host.moneyLabel, "货币名取快照（盘上 $ 不生效）")
        assertFalse(host.moneyLabelFirst, "货币位置取快照（盘上 YES 不生效）")
        assertEquals(snapshot.rawEntries, host.configOverrides, "原始条目表 = 快照那一份（同一实例读数）")
        assertEquals("SNAPSHOT_FONT", host.configOverrides["FONT NAME"], "字体名取快照原始条目表")
        assertEquals("1234", host.configOverrides["MAX HISTORY LOG LINES"], "日志行数取快照原始条目表")
        assertFalse(host.useSaveFolder, "UseSaveFolder 取快照（盘上 YES 不生效）")
        assertSame(snapshot.store, host.configChain.store, "configChain.store 就是快照 store（同一份数据）")
        assertNull(host.configReplace, "有快照 ⇒ 不再开 _Replace.csv")
        assertNull(host.configDebug, "有快照 ⇒ 不再开 debug.config")
        assertEquals(
            ConfigLayerPresence.ABSENT,
            host.configChain.layer(ConfigChainRole.EMUERA).presence,
            "快照视图如实报「本宿主没读盘」，不冒充别人的层读数",
        )
        // 文件宿主读数：候选目录由 useSaveFolder 决定（Config.cs:471-481）——
        // 快照 false ⇒ 只探根目录；盘上 true 也不得让 sav/ 进入候选。
        assertNull(host.checkSaveData(0L, SaveFileKind.NORMAL), "快照 false ⇒ 根目录的 save00.sav 命中")
        assertEquals(
            EraDataState.FILENOTFOUND,
            host.checkSaveData(1L, SaveFileKind.NORMAL)?.state,
            "快照 false ⇒ 不探 sav/（盘上 YES 不生效）",
        )
    }

    @Test
    fun `快照在位 - storeFromHost 复制快照条目表而不是重读盘`() {
        writeConfig(dir, "SNAPSHOT_FONT", maxLog = 1234, useSavFolder = false, windowWidth = 900)
        writeReplace(dir, "￥", moneyFirst = false)
        val snapshot = readSnapshot(dir)
        writeConfig(dir, "DISK_POISON", maxLog = 777, useSavFolder = true, windowWidth = 1234)
        writeReplace(dir, "$", moneyFirst = true)

        val host = RootedBuiltinHost(dir, configSnapshot = snapshot)
        val vmStore = ConfigMethods.storeFromHost(host)

        assertEquals(
            snapshot.store.snapshot(),
            vmStore.snapshot(),
            "storeFromHost 的优先通道必须整体等于快照 store（不重放文件层、不读盘）",
        )
        assertEquals("￥", vmStore.itemOf(ConfigKey.MoneyLabel).string(), "GETCONFIG 的货币名同源")
        assertEquals(1234, vmStore.itemOf(ConfigKey.MaxLog).int(), "GETCONFIG 的日志行数同源")
        assertEquals("SNAPSHOT_FONT", vmStore.itemOf(ConfigKey.FontName).string(), "GETCONFIG 的字体名同源")
        assertFalse(vmStore.itemOf(ConfigKey.UseSaveFolder).bool(), "GETCONFIG 的存档目录开关同源")
    }

    @Test
    fun `行为哨兵 - 根目录不存在时快照宿主仍给出快照读数`() {
        writeConfig(dir, "SNAPSHOT_FONT", maxLog = 1234, useSavFolder = false)
        writeReplace(dir, "￥", moneyFirst = false)
        val snapshot = readSnapshot(dir)

        // 不创建这个目录：若构造器仍走 loadChainFromDisk + _Replace.csv，读数会回落注册默认。
        val host = RootedBuiltinHost(dir.resolve("not-a-game-root"), configSnapshot = snapshot)

        assertEquals("￥", host.moneyLabel, "无盘可读也必须给快照货币名（$ 就是自读痕迹）")
        assertFalse(host.moneyLabelFirst)
        assertEquals(snapshot.rawEntries, host.configOverrides, "原始条目表不依赖磁盘")
        assertFalse(host.useSaveFolder, "存档目录开关不依赖磁盘")
        assertSame(snapshot.store, host.configChain.store)
        assertEquals(0, host.configChain.results.size, "快照视图没有逐层记录（本实例没读盘）")
    }

    // ------------------------------------------------------------------
    // 源码哨兵：快照分支唯一供数 / 会话只构造一个 rooted 宿主
    // ------------------------------------------------------------------

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/core/$relative"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
            File("../../core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `源码哨兵 - 快照分支唯一供数且会话只构造一个 rooted 宿主`() {
        val boot = sourceOf("proc/BootHosts.kt")
        assertEquals(
            1,
            Regex("configSnapshot\\?\\.let \\{ snapshotChainOf\\(").findAll(boot).count(),
            "configChain 的快照分支只允许一处（构造器不再读盘）",
        )
        assertTrue(
            boot.contains("configSnapshot?.rawEntries ?: mergedOverrideMap(configChain, configReplace)"),
            "原始条目表必须直取快照（缺省才回落重摊平）",
        )
        assertTrue(
            boot.contains("configSnapshot != null) null else loadReplaceFor("),
            "Replace 层必须在快照在位时短路（不读 _Replace.csv）",
        )
        assertTrue(
            boot.contains("if (snapshot != null) {"),
            "applyChainTo 必须有快照分支（复制 store，不走 replayChainOn）",
        )
        assertEquals(
            2,
            Regex("EmueraConfigParser\\.loadChainFromDisk\\(").findAll(boot).count(),
            "BootHosts 的 loadChainFromDisk 只允许 rooted 缺省分支 + 无宿主形态 loadConfigOverrides 两处",
        )

        val session = sourceOf("proc/GameSession.kt")
        assertEquals(
            1,
            Regex("RootedBuiltinHost\\(").findAll(session).count(),
            "会话只允许构造一个 rooted 宿主",
        )
        assertTrue(
            session.contains("configSnapshot = bootConfig?.let {"),
            "会话必须把 L9B 快照的两半透传给宿主",
        )
        assertEquals(
            1,
            Regex("rooted\\.configOverrides").findAll(session).count(),
            "rooted.configOverrides 只允许缺省回落一处",
        )
    }
}
