package com.eratw.port.core.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * RT2 M4-3：DELDATA 的「一个逻辑存档单元」删除相位验收
 * （CONTRACT-RT2-M4-3 §3；era-arch-v61u.md §8 :204 / :222）。
 *
 * C 车把 `.sav` + `.sav.rt2` 做成配对事务，但删除动作仍只删 `.sav`：
 * 孤儿侧车会被导出收走，残留 journal 会在下次存/读档把删掉的档「复活」。
 * 本组用例钉死 [FileSystemOps.delData] 的成对清理、原版异常形状与缺省不变式；
 * 导出面 / CHKDATA 只做**回归锁**，一行生产代码都不在 platform / fn 侧改。
 *
 * 装置沿用 C 车 [SidecarPairSaveTest] 的真临时目录口径，但 IO 换成可注入
 * 「只读 / 删除失败」的 [ProbeIo]：原版异常形状必须用可控假体逐条钉死，
 * 不能靠 Windows 文件属性碰运气。
 */
class SidecarLifecyclePairTest {

    @TempDir
    lateinit var temp: Path

    private val bodyName = "save00.sav"

    /** 与 C 车 `captureAndWritePair` 的 MemberSpec / journal 完全同一套路径口径。 */
    private val companionNames = listOf(
        "save00.sav.rt2",
        "save00.sav.journal",
        "save00.sav.part",
        "save00.sav.bak",
        "save00.sav.rt2.part",
        "save00.sav.rt2.bak",
    )

    /**
     * `platform/storage/SaveExport.kt:62` 的槽侧车正则镜像（core-vm 不依赖 platform）。
     * 只用于断言「DELDATA 后目录里没有会被导出收走的孤儿侧车」，不复制导出逻辑。
     */
    private val exportSlotSidecarName = Regex("(?i)save\\d+\\.sav\\.rt2")

    private val seedBytes = "companion".toByteArray()

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 真临时目录的 [SaveFileIo]：可注入只读与删除失败，并记录每一次成功删除。 */
    private class ProbeIo(private val root: Path) : SaveFileIo {
        val readOnly = HashSet<String>()
        val undeletable = HashSet<String>()
        val deletions = mutableListOf<String>()

        override fun exists(path: String): Boolean = Files.isRegularFile(root.resolve(path))

        override fun read(path: String): ByteArray? =
            if (!exists(path)) null else runCatching { Files.readAllBytes(root.resolve(path)) }.getOrNull()

        override fun write(path: String, bytes: ByteArray): Boolean {
            Files.write(root.resolve(path), bytes)
            return true
        }

        override fun delete(path: String): Boolean {
            if (path in undeletable) return false
            Files.deleteIfExists(root.resolve(path))
            deletions += path
            return true
        }

        override fun isReadOnly(path: String): Boolean = path in readOnly

        fun put(path: String) {
            Files.write(root.resolve(path), "companion".toByteArray())
        }
    }

    /** 只吃 [SidecarStoragePort.warn] 的最小端口：删除相位不需要抓取/恢复能力。 */
    private class WarnPort : SidecarStoragePort {
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

    private data class Fixture(
        val fs: FileSystemOps,
        val io: ProbeIo,
        val port: WarnPort,
        val store: VariableStore,
    )

    private fun fixture(withWiring: Boolean = true, seedBody: Boolean = true): Fixture {
        val store = VariableStore()
        val io = ProbeIo(temp)
        val port = WarnPort()
        if (withWiring) SidecarSaveWiring.install(store, SidecarSaveWiring(port, temp))
        if (seedBody) io.put(bodyName)
        for (name in companionNames) io.put(name)
        val fs = FileSystemOps(store, io, SaveConfig(systemSaveInBinary = true, zipSaveData = true))
        return Fixture(fs, io, port, store)
    }

    /** [gone] = true 断言伴生全清；false 断言伴生原样保留（原版 no-op / 异常形状回归）。 */
    private fun assertCompanions(gone: Boolean) {
        for (name in companionNames) {
            val exists = Files.exists(temp.resolve(name))
            if (gone) assertFalse(exists, "$name 应随本体一起清掉")
            else assertTrue(exists, "$name 应原样保留")
        }
    }

    // ------------------------------------------------------------------
    // 判据 1：删除全配对
    // ------------------------------------------------------------------

    @Test
    fun `DELDATA 全配对 - 侧车 journal 与两成员残件一并清除`() {
        val f = fixture()
        f.fs.delData(0)
        assertFalse(Files.exists(temp.resolve(bodyName)), "本体应被删")
        assertCompanions(gone = true)
        assertTrue(f.port.warnings.isEmpty(), "正常清理不应有警告，实得 ${f.port.warnings}")
    }

    @Test
    fun `DELDATA 只清本槽 - 相邻槽的侧车与残件不动`() {
        val f = fixture()
        f.io.put("save01.sav")
        f.io.put("save01.sav.rt2")
        f.io.put("save01.sav.journal")
        f.fs.delData(0)
        assertTrue(Files.isRegularFile(temp.resolve("save01.sav")))
        assertTrue(Files.isRegularFile(temp.resolve("save01.sav.rt2")))
        assertTrue(Files.isRegularFile(temp.resolve("save01.sav.journal")))
    }

    // ------------------------------------------------------------------
    // 判据 4：生命周期终态可见（导出 / CHKDATA 只做回归锁）
    // ------------------------------------------------------------------

    @Test
    fun `DELDATA 后目录无孤儿侧车且槽命名未变`() {
        val f = fixture()
        f.fs.delData(0)
        val names = temp.toFile().listFiles().orEmpty().map { it.name }
        assertTrue(
            names.none { exportSlotSidecarName.matches(it) },
            "导出侧车正则不应再命中任何文件，实得 $names",
        )
        // CHKDATA 的候选路径由 saveName 生成，本波不许改它；本体不在即天然空槽。
        assertEquals("save00.sav", SaveDataProbe.saveName(0L))
        assertFalse(Files.exists(temp.resolve(SaveDataProbe.saveName(0L))))
        assertTrue(f.port.warnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 判据 2：原版异常形状不变
    // ------------------------------------------------------------------

    @Test
    fun `本体不存在 - no-op 且伴生文件原样保留`() {
        val f = fixture(seedBody = false)
        f.fs.delData(0) // 不抛
        assertCompanions(gone = false)
        assertTrue(f.io.deletions.isEmpty(), "no-op 不应发起任何删除，实得 ${f.io.deletions}")
        assertTrue(f.port.warnings.isEmpty())
    }

    @Test
    fun `本体只读 - 仍抛 CodeEE 且不误删伴生文件`() {
        val f = fixture()
        f.io.readOnly += bodyName
        assertFailsWith<CodeEE> { f.fs.delData(0) }
        assertTrue(Files.isRegularFile(temp.resolve(bodyName)))
        assertCompanions(gone = false)
        assertTrue(f.io.deletions.isEmpty(), "只读取消在删本体之前，清理块不得进入")
    }

    @Test
    fun `本体删除失败 - 仍抛 CodeEE 且不进入清理块`() {
        val f = fixture()
        f.io.undeletable += bodyName
        assertFailsWith<CodeEE> { f.fs.delData(0) }
        assertTrue(Files.isRegularFile(temp.resolve(bodyName)))
        assertCompanions(gone = false)
        assertTrue(f.port.warnings.isEmpty())
    }

    @Test
    fun `伴生文件删除失败 - 只上 warn 不新增异常形状`() {
        val f = fixture()
        f.io.undeletable += "save00.sav.journal"
        f.fs.delData(0) // 不抛：DELDATA 成败只由本体决定
        assertFalse(Files.exists(temp.resolve(bodyName)))
        assertTrue(Files.isRegularFile(temp.resolve("save00.sav.journal")))
        for (name in companionNames - "save00.sav.journal") {
            assertFalse(Files.exists(temp.resolve(name)), "$name 仍应被清掉")
        }
        assertEquals(1, f.port.warnings.size, "只在失败的一项上留痕，实得 ${f.port.warnings}")
        assertTrue(f.port.warnings.single().contains("清理失败"))
        assertTrue(f.port.warnings.single().contains("save00.sav.journal"))
    }

    // ------------------------------------------------------------------
    // 判据 3：缺省不变式 + 现读语义
    // ------------------------------------------------------------------

    @Test
    fun `关态无接线 - 只删本体且伴生文件逐字节不变`() {
        val f = fixture(withWiring = false)
        f.fs.delData(0)
        assertFalse(Files.exists(temp.resolve(bodyName)))
        assertEquals(listOf(bodyName), f.io.deletions, "关态一行都不进清理块")
        for (name in companionNames) {
            assertTrue(Files.isRegularFile(temp.resolve(name)), "$name 应原样保留")
            assertTrue(
                seedBytes.contentEquals(Files.readAllBytes(temp.resolve(name))),
                "$name 字节应逐位不变",
            )
        }
    }

    @Test
    fun `接线晚装 - 语句层现读仍让 DELDATA 成对删除`() {
        val f = fixture(withWiring = false)
        SidecarSaveWiring.install(f.store, SidecarSaveWiring(f.port, temp))
        f.fs.delData(0)
        assertCompanions(gone = true)
        assertTrue(f.port.warnings.isEmpty())
    }
}
