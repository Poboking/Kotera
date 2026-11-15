package com.eratw.port.core.save

import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * RT2 M43R：迁移面的「一个逻辑存档单元」验收（CONTRACT-RT2-M43R §0 裁定 1/2/3；
 * 权威 = era-arch-v61u.md:204/:222 + Config.cs:562-585 的原版迁移面）。
 *
 * 三条裁定在本文件的钉法：
 * 1. **配对随迁**：本体集合（global 精确名 + save 通配，`SaveFolderMigrationTest` 钉死、
 *    本文件不再重复）内每个本体搬进 sav 时，六件伴生（[sidecarCompanionPaths]，与 M4-3
 *    删除相位同一份清单）一并随迁；**孤儿（本体缺失而伴生件在场）不随迁、不删除、原地不动**。
 * 2. **global 侧车**：global.sav 在原版迁移面内（Config.cs:562 的 File.Exists + :575-578
 *    精确名搬运；`SaveFolderMigrationTest` 的「global 走精确名」判据）⇒ 其侧车随迁。
 * 3. **孤儿 GC post-step**：执行点 = migrate 的四条出口（Completed / MoveFailed /
 *    FolderAlreadyExists / NothingToMove，MODGC2 裁定 2/3 扩面），对旧目录即游戏根；
 *    判据 = 本体在根**与**在生效目录（`sav/`）都缺失 && 伴生件在根在场；删除走
 *    [SaveFileIo.delete]（M4-3 同一原语）、关态门与 delData 同一开关语义
 *    （生产 = `SidecarSaveWiring.of(store) != null` 现读）；删除动作经 [SaveDiagnostics] 留痕。
 *
 * 装置用真临时目录 + [RootedSaveFileIo]（生产实现，含 [SaveFolderIo.rootFileNames] 枚举面），
 * 搬运失败注入用一层薄包装（原版异常形状已由 `SaveFolderMigrationTest` 钉死，这里不重复）。
 */
class SidecarMigrationGcTest {

    @TempDir
    lateinit var temp: Path

    /** save00 的六件伴生（与 `SidecarLifecyclePairTest.companionNames` 同一套路径口径）。 */
    private val companions00 = listOf(
        "save00.sav.rt2",
        "save00.sav.journal",
        "save00.sav.part",
        "save00.sav.bak",
        "save00.sav.rt2.part",
        "save00.sav.rt2.bak",
    )

    private val cfg: SaveConfig = SaveConfig(useSaveFolder = true)

    private var rootSeq: Int = 0

    /** 每次调用给一个**新**子目录（同一用例内多轮迁移各自独立，幂等守卫不串场）。 */
    private fun root(): Path = Files.createDirectories(temp.resolve("root-${++rootSeq}"))

    private fun write(root: Path, rel: String, content: String) {
        val p = root.resolve(rel)
        p.parent?.let { Files.createDirectories(it) }
        Files.writeString(p, content)
    }

    private fun text(root: Path, rel: String): String = Files.readString(root.resolve(rel))

    /** 搬运失败注入 + 动作记录（其余全部委托生产实现）。 */
    private class RecordingIo(private val delegate: RootedSaveFileIo) : SaveFileIo, SaveFolderIo {
        val moves = mutableListOf<Pair<String, String>>()
        var failMovesOf: Set<String> = emptySet()

        override fun exists(path: String): Boolean = delegate.exists(path)
        override fun read(path: String): ByteArray? = delegate.read(path)
        override fun write(path: String, bytes: ByteArray): Boolean = delegate.write(path, bytes)
        override fun delete(path: String): Boolean = delegate.delete(path)
        override fun isReadOnly(path: String): Boolean = delegate.isReadOnly(path)
        override fun saveFolderExists(): Boolean = delegate.saveFolderExists()
        override fun createSaveFolder(): Boolean = delegate.createSaveFolder()
        override fun rootSaveFileNames(): List<String> = delegate.rootSaveFileNames()
        override fun rootFileNames(): List<String> = delegate.rootFileNames()

        override fun moveToFile(fromRel: String, toRel: String): Boolean {
            if (fromRel.replace('\\', '/') in failMovesOf) return false
            val ok = delegate.moveToFile(fromRel, toRel)
            if (ok) moves += fromRel.replace('\\', '/') to toRel.replace('\\', '/')
            return ok
        }
    }

    // ------------------------------------------------------------------
    // 裁定 1：配对随迁（孤儿不随迁不删）
    // ------------------------------------------------------------------

    @Test
    fun `配对随迁 - 六件伴生与 global 侧车一并进 sav 且清单与删除相位同一份`() {
        val root = root()
        write(root, "save00.sav", "槽零")
        for (name in companions00) write(root, name, "伴生-$name")
        write(root, "global.sav", "全局")
        write(root, "global.sav.rt2", "全局侧车")
        write(root, "global.sav.journal", "全局journal")
        write(root, "data.txt", "非候选")
        write(root, "notes.sav.bak", "非本体形状的 bak")

        // 清单单源：迁移随迁的名单与 M4-3 删除相位（SidecarLifecyclePairTest）是同一份原语；
        // 顺序 = 删除相位的清序（journal 先失效，.rt2 收尾）。
        assertEquals(
            listOf(
                "save00.sav.journal",
                "save00.sav.part",
                "save00.sav.bak",
                "save00.sav.rt2.part",
                "save00.sav.rt2.bak",
                "save00.sav.rt2",
            ),
            sidecarCompanionPaths("save00.sav"),
            "伴生件清单必须与删除相位逐名一致（禁止第二份清单）",
        )

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg)

        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        // moved 是原版搬运清单：只有本体，伴生随迁不混入（movedCount 语义不被放大）。
        assertEquals(listOf("sav/global.sav", "sav/save00.sav"), r.moved)
        // 逐件落位：本体 + 六件伴生 + global 两件全部在 sav，根下不再有。
        val expectedInSav = companions00 + listOf("save00.sav", "global.sav", "global.sav.rt2", "global.sav.journal")
        for (name in expectedInSav) {
            assertTrue(Files.isRegularFile(root.resolve("sav/$name")), "sav/$name 应在")
            assertFalse(Files.exists(root.resolve(name)), "根下不应残留 $name")
        }
        assertEquals("伴生-save00.sav.rt2", text(root, "sav/save00.sav.rt2"), "伴生字节原样随迁")
        // 非候选与形状不合规的 bak 一个都不碰。
        assertEquals("非候选", text(root, "data.txt"))
        assertEquals("非本体形状的 bak", text(root, "notes.sav.bak"))
    }

    @Test
    fun `本体先于伴生落位且 global 仍第一个搬（原版次序不动）`() {
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save00.sav.rt2", "侧车")
        write(root, "global.sav", "全局")
        val io = RecordingIo(RootedSaveFileIo(root))

        val r = SaveFolderMigration.migrate(io, cfg)

        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertEquals("global.sav" to "sav/global.sav", io.moves.first(), "Config.cs:575-578 先搬 global.sav")
        val save00BodyIndex = io.moves.indexOfFirst { it.first == "save00.sav" }
        val save00SidecarIndex = io.moves.indexOfFirst { it.first == "save00.sav.rt2" }
        assertTrue(save00BodyIndex in 0 until save00SidecarIndex, "本体必须先于其伴生（次序 $io.moves）")
    }

    @Test
    fun `孤儿不随迁不删 - 本体缺失的伴生件在迁移后原地逐字节不动`() {
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save05.sav.rt2", "孤儿侧车")
        write(root, "save05.sav.journal", "孤儿journal")
        write(root, "save05.sav.rt2.bak", "孤儿bak")

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg) // 不传门 = 关态

        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")))
        for (orphan in listOf("save05.sav.rt2", "save05.sav.journal", "save05.sav.rt2.bak")) {
            assertTrue(Files.isRegularFile(root.resolve(orphan)), "孤儿 $orphan 不随迁不删")
        }
        assertEquals("孤儿侧车", text(root, "save05.sav.rt2"), "孤儿字节逐字节不动（原版语义零干扰）")
    }

    // ------------------------------------------------------------------
    // 裁定 3：孤儿 GC post-step（同一关态门）
    // ------------------------------------------------------------------

    @Test
    fun `GC 开态 - 迁移完成后旧目录孤儿伴生被清除并逐件留痕`() {
        SaveDiagnostics.clear()
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save05.sav.rt2", "孤儿侧车")
        write(root, "save05.sav.journal", "孤儿journal")
        write(root, "global.sav.rt2", "全局孤儿")
        write(root, "notes.sav.bak", "形状不合规不删")

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg, sidecarGcGate = { true })

        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")), "本体照常随迁")
        for (orphan in listOf("save05.sav.rt2", "save05.sav.journal", "global.sav.rt2")) {
            assertFalse(Files.exists(root.resolve(orphan)), "孤儿 $orphan 应被 GC（本体缺失）")
        }
        assertTrue(Files.isRegularFile(root.resolve("notes.sav.bak")), "本体形状不合规的 bak 不是伴生件，不删")
        val log = SaveDiagnostics.snapshot().joinToString("\n")
        for (orphan in listOf("save05.sav.rt2", "save05.sav.journal", "global.sav.rt2")) {
            assertTrue(log.contains("SIDECAR_GC") && log.contains(orphan), "FINE 留痕应含 $orphan，实得\n$log")
        }
    }

    @Test
    fun `GC 关态 - null 门与 false 门都一个伴生件都不碰`() {
        SaveDiagnostics.clear() // 环形缓冲是进程级静态，先清场再断言「无 GC 留痕」
        val rootA = root()
        write(rootA, "save00.sav", "槽零")
        write(rootA, "save05.sav.rt2", "孤儿侧车")
        SaveFolderMigration.migrate(RootedSaveFileIo(rootA), cfg) // 缺省 null = 关态
        assertTrue(Files.isRegularFile(rootA.resolve("save05.sav.rt2")), "null 门 ⇒ 孤儿原样")

        val rootB = root()
        write(rootB, "save00.sav", "槽零")
        write(rootB, "save05.sav.rt2", "孤儿侧车")
        val r = SaveFolderMigration.migrate(RootedSaveFileIo(rootB), cfg, sidecarGcGate = { false })
        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertTrue(Files.isRegularFile(rootB.resolve("save05.sav.rt2")), "false 门 ⇒ 孤儿原样")
        assertTrue(SaveDiagnostics.snapshot().none { it.contains("SIDECAR_GC") }, "关态零接触 ⇒ 无 GC 留痕")
    }

    @Test
    fun `搬一半失败 - 失败本体与其伴生保留、已成功槽伴生已随迁、孤儿仍被 GC`() {
        SaveDiagnostics.clear()
        val root = root()
        val io = RecordingIo(RootedSaveFileIo(root)).apply { failMovesOf = setOf("save01.sav") }
        write(root, "save00.sav", "槽零")
        write(root, "save00.sav.rt2", "槽零侧车")
        write(root, "save01.sav", "槽一")
        write(root, "save01.sav.rt2", "槽一侧车")
        write(root, "save07.sav.rt2", "孤儿侧车")

        val r = SaveFolderMigration.migrate(io, cfg, sidecarGcGate = { true })

        assertEquals(SaveMigrationOutcome.MoveFailed, r.outcome)
        assertEquals("save01.sav", r.failedFile)
        // 已成功槽：本体与伴生一起在 sav（配对随迁不因后面失败而回滚）。
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")))
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav.rt2")))
        // 失败槽：本体还在根 ⇒ 其伴生不是孤儿，原样保留。
        assertTrue(Files.isRegularFile(root.resolve("save01.sav")))
        assertTrue(Files.isRegularFile(root.resolve("save01.sav.rt2")))
        // 孤儿：本体缺失 ⇒ GC 清掉（MoveFailed 也是搬运相位跑完的出口）。
        assertFalse(Files.exists(root.resolve("save07.sav.rt2")), "孤儿伴生应被 GC")
        assertTrue(SaveDiagnostics.snapshot().any { it.contains("SIDECAR_GC") && it.contains("save07.sav.rt2") })
    }

    @Test
    fun `GC 只挂四条出口 - NothingToMove 也清纯孤儿而 SwitchOff 一个伴生件都不碰`() {
        SaveDiagnostics.clear()
        val rootA = root()
        write(rootA, "save05.sav.rt2", "孤儿侧车")
        val nothing = SaveFolderMigration.migrate(RootedSaveFileIo(rootA), cfg, sidecarGcGate = { true })
        assertEquals(SaveMigrationOutcome.NothingToMove, nothing.outcome)
        assertFalse(Files.exists(rootA.resolve("save05.sav.rt2")), "MODGC2 裁定 2：无本体可搬也清纯孤儿")

        // 开关为 NO：Config.cs:478-481 一个字节都不动，门开着也不能越权。
        SaveDiagnostics.clear() // 上一段 NothingToMove 已留 GC 痕；这里要证明 SwitchOff 零接触
        val rootB = root()
        write(rootB, "save00.sav", "槽零")
        write(rootB, "save05.sav.rt2", "孤儿侧车")
        val off = SaveConfig(systemSaveInBinary = true, useSaveFolder = false)
        val r = SaveFolderMigration.migrate(RootedSaveFileIo(rootB), off, sidecarGcGate = { true })
        assertEquals(SaveMigrationOutcome.SwitchOff, r.outcome)
        assertTrue(Files.isRegularFile(rootB.resolve("save05.sav.rt2")))
        assertEquals("孤儿侧车", text(rootB, "save05.sav.rt2"))
        assertFalse(Files.exists(rootB.resolve("sav")), "NO 时原版不建 sav 文件夹")
        assertTrue(SaveDiagnostics.snapshot().none { it.contains("SIDECAR_GC") }, "五条不挂 GC 的出口零接触")
    }

    @Test
    fun `GC 出口面 - FolderAlreadyExists 清纯孤儿但保留已迁进 sav 的配对半边`() {
        SaveDiagnostics.clear()
        val root = root()
        Files.createDirectories(root.resolve("sav"))
        // 本体已迁进 sav、侧车随迁失败留在根 ⇒ 仍有本体可配，**不是**孤儿（MODGC2 裁定 2 的收紧判据）。
        write(root, "sav/save01.sav", "槽一")
        write(root, "save01.sav.rt2", "随迁失败的侧车")
        // 纯孤儿：根与生效目录都没有本体。
        write(root, "save05.sav.rt2", "孤儿侧车")
        write(root, "global.sav.journal", "全局孤儿")

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg, sidecarGcGate = { true })

        assertEquals(SaveMigrationOutcome.FolderAlreadyExists, r.outcome)
        assertTrue(Files.isRegularFile(root.resolve("save01.sav.rt2")), "生效目录有本体 ⇒ 不删")
        assertEquals("随迁失败的侧车", text(root, "save01.sav.rt2"), "配对半边逐字节不动")
        for (orphan in listOf("save05.sav.rt2", "global.sav.journal")) {
            assertFalse(Files.exists(root.resolve(orphan)), "两处都无本体 ⇒ 纯孤儿 $orphan 应清")
        }
        val log = SaveDiagnostics.snapshot().joinToString("\n")
        assertTrue(
            log.contains("SIDECAR_GC") && log.contains("save05.sav.rt2"),
            "常态出口的 GC 也留痕，实得\n$log",
        )
    }

    @Test
    fun `GC 出口面 - FolderAlreadyExists 关态一个伴生件都不碰`() {
        SaveDiagnostics.clear()
        val root = root()
        Files.createDirectories(root.resolve("sav"))
        write(root, "save05.sav.rt2", "孤儿侧车")

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg, sidecarGcGate = { false })

        assertEquals(SaveMigrationOutcome.FolderAlreadyExists, r.outcome)
        assertTrue(Files.isRegularFile(root.resolve("save05.sav.rt2")), "false 门 ⇒ 孤儿原样")
        assertTrue(SaveDiagnostics.snapshot().none { it.contains("SIDECAR_GC") })
    }

    // ------------------------------------------------------------------
    // 生产装配面（FileSystemOps 构造期 = GameSession 第 8 步同形）
    // ------------------------------------------------------------------

    @Test
    fun `FileSystemOps 接线在位 - 构造期随迁伴生且 GC 现读闸生效`() {
        SaveDiagnostics.clear()
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save00.sav.rt2", "槽零侧车")
        write(root, "save01.sav", "槽一")
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore()
        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))

        val ops = FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.Completed, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")))
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav.rt2")), "伴生随本体随迁")
        assertTrue(Files.isRegularFile(root.resolve("sav/save01.sav")))
        assertFalse(Files.exists(root.resolve("save09.sav.rt2")), "接线在位 ⇒ 孤儿被 GC")
        assertTrue(SaveDiagnostics.snapshot().any { it.contains("SIDECAR_GC") && it.contains("save09.sav.rt2") })
    }

    @Test
    fun `FileSystemOps 关态未接线 - 随迁照裁定一照做、孤儿零接触`() {
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save00.sav.rt2", "槽零侧车")
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore() // 不装接线 = 关态

        val ops = FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.Completed, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav.rt2")), "随迁不挂关态门（裁定 1 无门）")
        assertTrue(Files.isRegularFile(root.resolve("save09.sav.rt2")), "关态 ⇒ 孤儿零接触（裁定 3）")
        assertNull(SidecarSaveWiring.of(store))
    }

    @Test
    fun `GC 可达性 - 先构造后接线：构造期孤儿保留且接线安装时一次性清除`() {
        SaveDiagnostics.clear()
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore() // 构造时接线未装（生产装配序同形）

        val ops = FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.Completed, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("save09.sav.rt2")), "构造期接线未装 ⇒ 孤儿原样（关态门恒 false）")
        assertTrue(SaveDiagnostics.snapshot().none { it.contains("SIDECAR_GC") }, "构造期不得有 GC 留痕")

        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))

        assertFalse(Files.exists(root.resolve("save09.sav.rt2")), "接线安装后待办执行：孤儿被清")
        assertTrue(
            SaveDiagnostics.snapshot().any { it.contains("SIDECAR_GC") && it.contains("save09.sav.rt2") },
            "GC 留痕应含 save09.sav.rt2",
        )
    }

    @Test
    fun `GC 可达性 - MoveFailed 出口也登记待办`() {
        val root = root()
        val io = RecordingIo(RootedSaveFileIo(root)).apply { failMovesOf = setOf("save01.sav") }
        write(root, "save00.sav", "槽零")
        write(root, "save01.sav", "槽一")
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore()

        val ops = FileSystemOps(store, io, SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.MoveFailed, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("save09.sav.rt2")), "构造期孤儿原样")
        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))
        assertFalse(Files.exists(root.resolve("save09.sav.rt2")), "MoveFailed 的搬运相位已跑完 ⇒ 接线后孤儿应被清")
    }

    @Test
    fun `GC 可达性 - 待办一次性：第二次 install 不再扫盘`() {
        val root = root()
        write(root, "save00.sav", "槽零")
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore()
        FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))
        assertFalse(Files.exists(root.resolve("save09.sav.rt2")), "首装执行待办")

        // 安装之后再出现的孤儿件不是迁移期残留，第二次 install 不得再扫（否则误删新档）。
        write(root, "save10.sav.rt2", "安装后放回")
        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))
        assertTrue(Files.isRegularFile(root.resolve("save10.sav.rt2")), "一次性待办已耗尽 ⇒ 不得二次扫盘")
    }

    @Test
    fun `GC 可达性 - NothingToMove 也登记待办（常态出口回收孤儿）`() {
        val root = root()
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore()

        val ops = FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.NothingToMove, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("save09.sav.rt2")), "构造期接线未装 ⇒ 孤儿原样")
        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))
        assertFalse(Files.exists(root.resolve("save09.sav.rt2")), "MODGC2 裁定 3：NothingToMove 也登记待办")
    }

    @Test
    fun `GC 可达性 - FolderAlreadyExists 也登记待办（最常见的常态出口）`() {
        val root = root()
        Files.createDirectories(root.resolve("sav"))
        write(root, "save09.sav.rt2", "孤儿侧车")
        val store = VariableStore()

        val ops = FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.FolderAlreadyExists, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("save09.sav.rt2")), "构造期接线未装 ⇒ 孤儿原样")
        SidecarSaveWiring.install(store, SidecarSaveWiring(NoopPort(), root))
        assertFalse(Files.exists(root.resolve("save09.sav.rt2")), "sav 已存在也登记待办 ⇒ 接线后清")
    }

    @Test
    fun `伴生随迁失败 - 留痕并原地保留，不影响迁移结局`() {
        SaveDiagnostics.clear()
        val root = root()
        write(root, "save00.sav", "槽零")
        Files.createDirectories(root.resolve("save00.sav.rt2")) // 同名目录占位：moveToFile 必失败

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg)

        assertEquals(SaveMigrationOutcome.Completed, r.outcome, "随迁失败不改九出口（成败只由本体决定）")
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")), "本体照常随迁")
        assertTrue(Files.isDirectory(root.resolve("save00.sav.rt2")), "随迁失败的伴生件原地保留")
        assertTrue(SaveDiagnostics.snapshot().any { it.contains("SIDECAR_MOVE") && it.contains("save00.sav.rt2") })
    }

    // ------------------------------------------------------------------
    // 形状判定（纯函数面）
    // ------------------------------------------------------------------

    @Test
    fun `伴生件后缀反推本体 - 长后缀先命中且非伴生形状不误判`() {
        assertEquals("save00.sav", SaveFolderMigration.sidecarCompanionBodyOf("save00.sav.rt2"))
        assertEquals("save00.sav", SaveFolderMigration.sidecarCompanionBodyOf("save00.sav.rt2.bak"))
        assertEquals("save00.sav", SaveFolderMigration.sidecarCompanionBodyOf("save00.sav.rt2.part"))
        assertEquals("save00.sav", SaveFolderMigration.sidecarCompanionBodyOf("save00.sav.journal"))
        assertEquals("global.sav", SaveFolderMigration.sidecarCompanionBodyOf("global.sav.journal"))
        assertEquals("notes.sav", SaveFolderMigration.sidecarCompanionBodyOf("notes.sav.bak"), "本体形状由 GC 判据另行把关")
        assertNull(SaveFolderMigration.sidecarCompanionBodyOf("save00.sav"), "本体名不是伴生件")
        assertNull(SaveFolderMigration.sidecarCompanionBodyOf("save00.sav.tmp"))
        assertNull(SaveFolderMigration.sidecarCompanionBodyOf("save.sav.bak.tmp"))
        assertNull(SaveFolderMigration.sidecarCompanionBodyOf(""))
    }

    /** 只吃端口形状的最小假体（本文件不触发抓取与恢复；warn 留痕给断言用）。 */
    private class NoopPort : SidecarStoragePort {
        val warnings = mutableListOf<String>()
        override fun captureChunks(csv: com.eratw.port.core.vars.ModCsvValueBag): SidecarChunkGroups =
            SidecarChunkGroups(emptyList(), emptyList())
        override fun captureRt2Bytes(
            binding: SidecarBinding,
            activeChunks: List<PackageChunk>,
            orphanChunks: List<PackageChunk>,
        ): ByteArray = ByteArray(0)
        override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult =
            SidecarRestoreResult(false)
        override fun warn(message: String) {
            warnings += message
        }
    }
}
