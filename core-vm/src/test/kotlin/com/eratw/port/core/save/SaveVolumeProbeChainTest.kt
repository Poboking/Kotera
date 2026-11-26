package com.eratw.port.core.save

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/**
 * 「可用空间探测失败」不许升级成「存档失败」的行为钉（bug② SAVEDATA 全失败，2026-11-19 lane savedata）。
 *
 * ## 真机命中链（本条是它的桌面等价物）
 *
 * ① Android 把 `sun.nio.fs.UnixFileSystemProvider.getFileStore(Path)` 改成**无条件抛**
 * （AOSP `platform/libcore`，`ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java`，
 * 方法体 `:373-388`，抛出点 `:387` `throw new SecurityException("getFileStore");`，
 * 上方 `:375` 的 `BEGIN Android-changed: getFileStore(Path) always throws SecurityException.`）
 * ⇒ `java.nio.file.Files.getFileStore(..)` 在 Android 上恒抛，桌面 JVM 恒不抛。
 * Linux 实现侧同样堵死：`ojluni/src/main/java/sun/nio/fs/LinuxFileSystemProvider.java:48-53`
 * 把内部重载 `getFileStore(UnixPath)` 也改成直接 `throw new SecurityException("getFileStore")`
 * （`:51` 原 `return new LinuxFileStore(path)` 被注掉）—— 没有「换一个重载就能拿到 FileStore」的后门。
 * ② 生产实现 `NioSaveTransactionIo.kt:72` `availableBytes() = Files.getFileStore(base).usableSpace`。
 * ③ 空间预算门 `SaveTransaction.kt:202` `execute()` 的 `:207` `val available = io.availableBytes()`
 * 是裸调（无 try/catch），异常照原样漏出 `execute`。
 * ④ 生产调用点 `FileSystemOps.kt:507` `SaveTransaction(txIo, sidecarJournalPathOf(path)).execute(request)`
 * （配对落盘路径 `captureAndWritePair`；`txIo` 在 `:435` 建成）。
 * ⑤ 上抛到 `FileSystemOps.kt:297-300` 的 `catch (e: Throwable)` ⇒ `SaveDiagnostics.report(...)` +
 * `return false` ⇒ `StatementExecutor.kt:1219`/`:3860` 打印「SAVEDATA命令在存档时发生了意料之外的错误」。
 *
 * 时间线：`.rt2` 配对路径进真实存档 = `e6cdcc642`（11-11）；Mod 总闸缺省开 = `0f0727a7b`（11-17 15:36，
 * 晚于 0.2.47 的 `7766266dc`）⇒ 0.2.48 是第一版「全员走配对事务」的包，与玩家「最新包才开始坏」吻合。
 *
 * ## 桌面怎么复现同一处失败点
 *
 * 桌面 JVM 的 `getFileStore` 不抛 SecurityException，所以本文件用两个等价形态：
 * - 用例一：假 [SaveTransactionIo]，只有 `availableBytes()` 抛 `SecurityException("getFileStore")`
 *   —— 逐字复刻 Android 的异常形状，其余方法委托真盘实现；
 * - 用例二：把配对事务的根指到**尚不存在**的目录（生产里该根 = 游戏根，必然存在；此处只为让桌面
 *   的 `getFileStore` 在同一行抛 `NoSuchFileException`）走完 `FileSystemOps.saveData` 全链。
 *
 * 两种形态抛的都是**同一个调用点的同一种事**：探测不到可用空间。断言的是语义而非异常类型 ——
 * 「探测不到 ≠ 没空间」，因为真正的盘满会在写入相位以带路径的 `IOException` 现形（事务本身
 * 逐相位 fail-closed），把一次探测失败升级成拒绝存档，会让所有探测不到的机型永远存不上档。
 * 同仓已有同口径判例：`platform/src/main/kotlin/com/eratw/port/platform/storage/GameAssetInstaller.kt:1974-1987`
 * 的 `freeBytes(root: File)`（沿父链找最近存在祖先 + 0 视同探测失败 + fail-open `Long.MAX_VALUE`，
 * KDoc `:1952-1973`）。
 *
 * **当前状态 = 红**（这两条钉的就是还没修的行为）。修法在共享事务/探测层（saveglobal lane 落
 * `NioSaveTransactionIo.availableBytes` 与 `SaveTransaction.execute` 预算门容错），本 lane 不碰
 * 那三个文件；合流后本文件应转绿。
 *
 * 已实测双向（2026-11-19，本机 JVM 17 + 桌面盘）：
 * - HEAD 态（`NioSaveTransactionIo.kt:72` 仍是 `Files.getFileStore`）：`2 failed`；
 * - 临时把那两处换成 `GameAssetInstaller.freeBytes` 口径（沿父链探 + fail-open）+ 预算门
 *   `try/catch`：`2 passed`、`failures="0"`；
 * - `git checkout --` 回退那两文件后再次 `2 failed` —— 用例对修复敏感、不是恒红也不是恒绿。
 */
class SaveVolumeProbeChainTest {

    @TempDir
    lateinit var temp: Path

    /** 逐字复刻 Android 探测形状的端口：只有可用空间探测抛，其余全部委托真盘实现。 */
    private class AndroidGetFileStoreIo(private val delegate: SaveTransactionIo) : SaveTransactionIo by delegate {
        var probeCalls = 0

        override fun availableBytes(): Long {
            probeCalls++
            throw SecurityException("getFileStore")
        }
    }

    /**
     * 用例一：预算门的探测抛 `SecurityException("getFileStore")`（Android 恒抛形状）时，
     * `SaveTransaction.execute` 不许把异常漏给调用方，且必须照常写完两个成员。
     *
     * 断言的是 `SaveTransaction.kt:207` 那条裸调的行为边界：探测失败 = 「未知」，不是「没空间」
     * （`ExecuteResult.Rejected` 只归「测得可用 < 需要」）。若修法只在探测实现里 fail-open、
     * 不给 `execute` 容错，本条仍红 —— 那是纵深缺口，不是用例问题。
     */
    @Test
    fun `预算门探测抛 SecurityException 时 execute 不许把异常漏给调用方`() {
        val io = AndroidGetFileStoreIo(NioSaveTransactionIo(temp))
        val request = SaveTransaction.Request(
            transactionId = "0123456789abcdef",
            oldPairGeneration = 0L,
            newPairGeneration = 1L,
            members = listOf(
                SaveTransaction.MemberSpec(
                    target = "save00.sav",
                    part = "save00.sav.part",
                    bak = "save00.sav.bak",
                    oldBytes = ByteArray(0),
                    newBytes = byteArrayOf(1, 2, 3),
                ),
                SaveTransaction.MemberSpec(
                    target = "save00.sav.rt2",
                    part = "save00.sav.rt2.part",
                    bak = "save00.sav.rt2.bak",
                    oldBytes = ByteArray(0),
                    newBytes = byteArrayOf(4, 5),
                ),
            ),
        )
        val result = try {
            SaveTransaction(io, "save00.sav.journal").execute(request)
        } catch (e: Throwable) {
            fail(
                "空间预算门把探测异常漏给了调用方（Android 上即 Files.getFileStore 的 SecurityException）：" +
                    "$e。命中链见 NioSaveTransactionIo.kt:72 → SaveTransaction.kt:207 → FileSystemOps.kt:507 " +
                    "→ FileSystemOps.kt:297-300 ⇒ saveData 返回 false ⇒ SAVEDATA 报「意料之外的错误」。",
            )
        }
        assertTrue(io.probeCalls >= 1, "预算门没有真正咨询过可用空间探针，本用例没有钉到热路径")
        assertEquals(
            SaveTransaction.ExecuteResult.Committed, result,
            "探测失败不许折算成空间不足（Rejected）；磁盘真满由写入相位的 IOException 现形",
        )
        assertTrue(Files.isRegularFile(temp.resolve("save00.sav")), "成员一未落盘")
        assertTrue(Files.isRegularFile(temp.resolve("save00.sav.rt2")), "成员二未落盘")
    }

    /** 最小侧车端口：分片恒空、`.rt2` 恒三字节；本条只关心事务预算门，不关心容器内容。 */
    private class EmptySidecarPort : SidecarStoragePort {
        override fun captureChunks(csv: ModCsvValueBag): SidecarChunkGroups =
            SidecarChunkGroups(emptyList(), emptyList())

        override fun captureRt2Bytes(
            binding: SidecarBinding,
            activeChunks: List<PackageChunk>,
            orphanChunks: List<PackageChunk>,
        ): ByteArray = byteArrayOf(0x52, 0x54, 0x32)

        override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult =
            SidecarRestoreResult(false)

        override fun warn(message: String) = Unit
    }

    /**
     * 用例二：配对事务的根尚不可探测（桌面上 `getFileStore(不存在的目录)` 抛 `NoSuchFileException`，
     * 与 Android 恒抛落在**同一个调用点** `NioSaveTransactionIo.kt:72`）时，`FileSystemOps.saveData`
     * 仍须成功落盘。
     *
     * 这条走的是真生产链：`SidecarSaveWiring` 装好（等价 Mod 总闸开）⇒ `saveData` 走
     * `captureAndWritePair`（`FileSystemOps.kt:435` 建 `NioSaveTransactionIo(sidecar.transactionRoot)`
     * → `:507` execute）。修复前这里返回 false（就是玩家屏上那句错误），修复后必须 true 且产物成对。
     */
    @Test
    fun `事务根尚不可探测时 saveData 仍须落盘成功`() {
        val store = VariableStore().apply {
            registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)))
            addCharacter(1L)
            writeInt("DAY", longArrayOf(0L), 7L)
        }
        // 生产里 transactionRoot = 游戏根（ModRuntimeAssembly.kt:679/:687），必然存在；
        // 这里造一个「尚不存在」的根，只为让桌面 getFileStore 在 :72 抛出等价异常。
        val root = temp.resolve("root-not-created-yet")
        SidecarSaveWiring.install(store, SidecarSaveWiring(EmptySidecarPort(), root))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )

        val saved = fs.saveData(0, "test")
        assertTrue(
            saved,
            "saveData 返回 false（屏上 =「SAVEDATA命令在存档时发生了意料之外的错误」）：" +
                "可用空间探测失败被折算成存档失败。命中链见 NioSaveTransactionIo.kt:72 → " +
                "SaveTransaction.kt:207 → FileSystemOps.kt:507 → FileSystemOps.kt:297-300。",
        )
        assertTrue(Files.isRegularFile(root.resolve("save00.sav")), "`.sav` 未落盘")
        assertTrue(Files.isRegularFile(root.resolve("save00.sav.rt2")), "`.rt2` 未配对落盘")
        assertTrue(Files.notExists(root.resolve("save00.sav.part")), "`.part` 残件")
        assertTrue(Files.notExists(root.resolve("save00.sav.journal")), "journal 残件")
    }
}
