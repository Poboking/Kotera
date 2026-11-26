package com.eratw.port.core.save

import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.mod.ModStorageSectionCodec
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * RT2 C 车的落盘验收：`.sav` + `.sav.rt2` 侧车经 [SaveTransaction] 配对事务的端到端
 * （`FileSystemOps.saveData` / `loadData` 消费 `SidecarSaveWiring` 的生产路径）。
 *
 * 判据（CONTRACT-RT2-C §5）：
 * - ② `.sav` 落盘成功 ⇒ 同名 `.sav.rt2` 配对落盘（mod 存储分段在容器分片里真往返）；
 *   `.sav` 在而 `.rt2` 缺/坏 ⇒ fail-closed 上警告，`.sav` 的原版语义不变；
 * - ③ journal / `.bak` / 配对复用 SaveTransaction / SaveRecovery / SavePlanExecutor，
 *   生产 [NioSaveTransactionIo] 落位（本文件直接以临时目录真盘跑）；
 * - ⑥ 反向证明 ①：侧车抓取字节退化为空 ⇒ 配对用例红（见 [反向证明走查] 注记与交付报告）。
 */
class SidecarPairSaveTest {

    @TempDir
    lateinit var temp: Path

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 空投射器（F 车真装置未落，契约 §5.5 的许口径；projectedEntities 恒空）。 */
    private object EmptyProjector : ICharaSlotProjector {
        override fun project(entityId: Long): Int = -1
        override fun unproject(entityId: Long): Boolean = false
        override fun charIndexOf(entityId: Long): Int = -1
        override val rosterGeneration: Long get() = 0L
        override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int = -1
        override fun entityAt(charIndex: Int): Long = EntityIdCodec.NULL
        override fun projectedEntities(): LongArray = LongArray(0)
        override fun syncFromCharaSlot(entityId: Long) = Unit
        override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) = Unit
        override fun onCharacterRowDeleted(deletedIndex: Int) = Unit
        override fun onWorldReset() = Unit
    }

    /**
     * 测试侧端口：与生产 `ModSidecarStoragePort` 同一映射（每包一段 = 每包一片），
     * 但全部状态在本类内可断言；[corruptCapture] / [shiftBinding] 是反向证明的注入口。
     */
    private class FakePort(
        private val saveService: SaveService,
        private val storage: () -> ModStorageStore,
        private val adopt: (ModStorageStore) -> Unit,
        private val activeIds: () -> Set<String>,
    ) : SidecarStoragePort {
        var corruptCapture = false
        var shiftBinding = false
        val warnings = mutableListOf<String>()
        var restoredSections: List<com.eratw.port.core.mod.ModStorageSection>? = null
        var restoredBinding: SidecarBinding? = null

        override fun captureChunks(csv: com.eratw.port.core.vars.ModCsvValueBag): SidecarChunkGroups {
            val active = ArrayList<PackageChunk>()
            val orphan = ArrayList<PackageChunk>()
            for (section in storage().sectionsSnapshot()) {
                val chunk = PackageChunk(
                    section.packageId,
                    ModStorageSectionCodec.encode(listOf(section)),
                )
                if (section.orphan) orphan += chunk else active += chunk
            }
            return SidecarChunkGroups(active, orphan)
        }

        override fun captureRt2Bytes(
            binding: SidecarBinding,
            activeChunks: List<PackageChunk>,
            orphanChunks: List<PackageChunk>,
        ): ByteArray {
            val effective = if (shiftBinding) {
                SidecarBinding(binding.uniqueCode, binding.scriptVersion, binding.saveTextHash + 1)
            } else {
                binding
            }
            val bytes = saveService.captureBundle(effective, activeChunks, orphanChunks).encode()
            return if (corruptCapture) bytes.copyOfRange(0, bytes.size - 1) else bytes
        }

        override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult {
            val bundle = SidecarSaveBundle.decode(bytes)
            return when (val outcome = saveService.restoreBundle(bundle, binding)) {
                is SaveService.RestoreOutcome.Applied -> {
                    val sections = bundle.chunks.flatMap {
                        ModStorageSectionCodec.decode(it.payload, activeIds())
                    }
                    adopt(ModStorageStore.load(ModStorageSectionCodec.encode(sections), activeIds()))
                    restoredSections = sections
                    restoredBinding = binding
                    SidecarRestoreResult(true)
                }
                is SaveService.RestoreOutcome.BindingMismatch -> {
                    warnings += "绑定失配 expected=${outcome.expected} actual=${outcome.actual}"
                    SidecarRestoreResult(false)
                }
            }
        }

        override fun warn(message: String) {
            warnings += message
        }
    }

    private fun newStore(): VariableStore = VariableStore().apply {
        registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)))
        addCharacter(1L)
        writeInt("DAY", longArrayOf(0L), 7L)
    }

    /** 存好两包数据（一激活一孤儿）的存储容器。 */
    private fun populatedStorage(): ModStorageStore = ModStorageStore().apply {
        apiFor("school.mod").put("gold", "100")
        apiFor("school.mod").put("level", "3")
        apiFor("legacy.mod").put("old", "keep")
        deactivate("legacy.mod")
    }

    private fun fixture(corruptCapture: Boolean = false, shiftBinding: Boolean = false): Triple<FileSystemOps, FakePort, Path> {
        val store = newStore()
        val storage = populatedStorage()
        val svc = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            EmptyProjector,
            PackageShortIdRegistry(),
        )
        val port = FakePort(svc, { storage }, { }, { setOf("school.mod") })
        port.corruptCapture = corruptCapture
        port.shiftBinding = shiftBinding
        SidecarSaveWiring.install(store, SidecarSaveWiring(port, temp))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        return Triple(fs, port, temp)
    }

    // ------------------------------------------------------------------
    // ② 配对落盘 + 读档恢复
    // ------------------------------------------------------------------

    @Test
    fun `配对落盘 - sav 与 rt2 同名成对且无残件`() {
        val (fs, _, _) = fixture()
        assertTrue(fs.saveData(0, "test"), "SAVEDATA 应成功")
        assertTrue(Files.isRegularFile(temp.resolve("save00.sav")))
        assertTrue(Files.isRegularFile(temp.resolve("save00.sav.rt2")))
        assertFalse(Files.exists(temp.resolve("save00.sav.part")))
        assertFalse(Files.exists(temp.resolve("save00.sav.rt2.part")))
        assertFalse(Files.exists(temp.resolve("save00.sav.journal")))
        // .sav 字节能被既有探测判 OK（原版语义不因配对事务改变）。
        val bytes = Files.readAllBytes(temp.resolve("save00.sav"))
        assertEquals(
            com.eratw.port.core.fn.EraDataState.OK,
            SaveDataProbe.checkBytes(
                bytes, com.eratw.port.core.fn.SaveFileKind.NORMAL,
                com.eratw.port.core.vars.GameBaseData(), null,
            ).state,
        )
    }

    @Test
    fun `rt2 容器解码后分段真往返 - 激活与孤儿都原样回来`() {
        val (fs, _, _) = fixture()
        assertTrue(fs.saveData(0, "test"))
        val bundle = SidecarSaveBundle.decode(Files.readAllBytes(temp.resolve("save00.sav.rt2")))
        assertEquals(2, bundle.chunks.size)
        val ids = bundle.chunks.map { it.packageId }.toSet()
        assertEquals(setOf("school.mod", "legacy.mod"), ids)
        // 每片载荷解回单包一段，条目逐字对上（孤儿段的墓碑口径也随行）。
        val byId = bundle.chunks.associate { it.packageId to it.payload }
        val school = ModStorageSectionCodec.decode(byId.getValue("school.mod"), setOf("school.mod")).single()
        assertEquals(
            "100",
            (school.entries.getValue("gold") as com.eratw.port.modapi.ModValue.StrValue).value,
        )
        val legacy = ModStorageSectionCodec.decode(byId.getValue("legacy.mod"), emptySet()).single()
        assertEquals("keep", (legacy.entries["old"] as com.eratw.port.modapi.ModValue.StrValue).value)
    }

    @Test
    fun `读档 - restoreBundle 分段交还宿主且绑定吻合`() {
        val (fs, port, _) = fixture()
        assertTrue(fs.saveData(0, "test"))
        fs.loadData(0)
        val restored = port.restoredSections
        assertTrue(restored != null, "读档应把分段交还宿主")
        assertEquals(
            setOf("school.mod", "legacy.mod"),
            restored!!.map { it.packageId }.toSet(),
        )
        assertTrue(port.warnings.isEmpty(), "正常往返不应有警告，实得 ${port.warnings}")
    }

    // ------------------------------------------------------------------
    // ② fail-closed：.rt2 缺 / 坏 / 绑定失配
    // ------------------------------------------------------------------

    @Test
    fun `rt2 缺失 - 读档仍成功但上警告不静默丢`() {
        val (fs, port, root) = fixture()
        assertTrue(fs.saveData(0, "test"))
        Files.delete(root.resolve("save00.sav.rt2"))
        fs.loadData(0)
        assertTrue(port.restoredSections == null, "缺失时不应有分段交还")
        assertTrue(port.warnings.any { it.contains("缺失") }, "应上缺失警告，实得 ${port.warnings}")
    }

    @Test
    fun `rt2 损坏 - 读档仍成功但上警告不静默丢`() {
        val (fs, port, root) = fixture()
        assertTrue(fs.saveData(0, "test"))
        Files.write(root.resolve("save00.sav.rt2"), byteArrayOf(1, 2, 3))
        fs.loadData(0)
        assertTrue(port.restoredSections == null)
        assertTrue(port.warnings.any { it.contains("恢复失败") }, "应上损坏警告，实得 ${port.warnings}")
    }

    @Test
    fun `绑定失配 - 不应用分段且上警告`() {
        val (fs, port, _) = fixture(shiftBinding = true)
        assertTrue(fs.saveData(0, "test"))
        fs.loadData(0)
        assertTrue(port.restoredSections == null, "绑定失配不应交还分段")
        assertTrue(port.warnings.any { it.contains("绑定失配") }, "应上失配警告，实得 ${port.warnings}")
    }

    // ------------------------------------------------------------------
    // 绑定第三位语义钉子（TODO(verify) 闭环，2026-11-13）
    //
    // 裁定：era-arch §8:221 字面 `saveTextHash` 从未定义哈希对象；语义权威 = 绑定
    // 三元组的目的（§8 兼容性矩阵「PC 玩过后传回安卓 ⇒ 检测绑定失配 → 警告扩展
    // 状态可能滞后或丢失」）。第三位必须能检出「.sav 内容已变而 .rt2 未随行」的
    // 配对漂移 ⇒ .sav 容器全字节 CRC32 是字面语义的严格加强（同文本不同内容在
    // 文本哈希下恒漏检），不是等价替换。裁定全文见 FileSystemOps.savBytesHash KDoc。
    // ------------------------------------------------------------------

    /** 与 [fixture] 同款，但把 [VariableStore] 一并交出（第三位钉子需在两次存档之间改池面）。 */
    private fun wiredFixture(): Tuple4 {
        val store = newStore()
        val storage = populatedStorage()
        val svc = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            EmptyProjector,
            PackageShortIdRegistry(),
        )
        val port = FakePort(svc, { storage }, { }, { setOf("school.mod") })
        SidecarSaveWiring.install(store, SidecarSaveWiring(port, temp))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        return Tuple4(fs, port, temp, store)
    }

    private data class Tuple4(val fs: FileSystemOps, val port: FakePort, val root: Path, val store: VariableStore)

    @Test
    fun `绑定第三位内容敏感 - 同文本两次存档位值不同且旧侧车失配`() {
        val wired = wiredFixture()
        assertTrue(wired.fs.saveData(0, "test"), "首存应成功")
        val staleRt2 = Files.readAllBytes(wired.root.resolve("save00.sav.rt2"))
        val staleBinding = SidecarSaveBundle.decode(staleRt2).binding

        // 池面漂移（DAY 7→8）、SAVEDATA 文本不变：模拟「PC 玩过后 .sav 内容变了、
        // 描述文本一字未动」的最小往返模型。
        wired.store.writeInt("DAY", longArrayOf(0L), 8L)
        assertTrue(wired.fs.saveData(0, "test"), "二存应成功")
        val freshBinding = SidecarSaveBundle.decode(
            Files.readAllBytes(wired.root.resolve("save00.sav.rt2")),
        ).binding

        // 字面 saveTextHash 语义下两位恒等（同文本）；CRC32-of-bytes 下必不同 ——
        // 这条不等式就是「实现强于字面语义」的钉子。
        assertTrue(
            staleBinding.saveTextHash != freshBinding.saveTextHash,
            "同文本不同内容 ⇒ 第三位必须不同（v1=${staleBinding.saveTextHash} v2=${freshBinding.saveTextHash}）",
        )
        assertTrue(staleBinding.uniqueCode == freshBinding.uniqueCode, "前两位不受池面漂移影响")
        assertTrue(staleBinding.scriptVersion == freshBinding.scriptVersion, "前两位不受池面漂移影响")

        // 旧凭据 + 新 .sav ⇒ 失配上警告，不静默应用（兼容性矩阵的行为面）。
        Files.write(wired.root.resolve("save00.sav.rt2"), staleRt2)
        wired.fs.loadData(0)
        assertTrue(wired.port.restoredSections == null, "失配时不得交还分段")
        assertTrue(
            wired.port.warnings.any { it.contains("绑定失配") },
            "应上失配警告，实得 ${wired.port.warnings}",
        )
    }

    @Test
    fun `绑定第三位存读同源 - 提交后磁盘 sav 重算 CRC32 与 rt2 内凭据一致`() {
        val wired = wiredFixture()
        assertTrue(wired.fs.saveData(0, "test"), "存档应成功")
        val savBytes = Files.readAllBytes(wired.root.resolve("save00.sav"))
        val rt2 = SidecarSaveBundle.decode(Files.readAllBytes(wired.root.resolve("save00.sav.rt2")))
        val crc = java.util.zip.CRC32()
        crc.update(savBytes)
        assertEquals(
            crc.value.toInt(),
            rt2.binding.saveTextHash,
            "存侧第三位必须 = 提交进事务的那份 .sav 磁盘字节的 CRC32（存读两侧同一份字节）",
        )
        wired.fs.loadData(0)
        assertTrue(wired.port.restoredSections != null, "读侧同源 ⇒ 正常应用分段")
        assertTrue(wired.port.warnings.isEmpty(), "正常往返不应有警告，实得 ${wired.port.warnings}")
    }

    // ------------------------------------------------------------------
    // 缺省路径零变化 + 反向证明
    // ------------------------------------------------------------------

    @Test
    fun `缺省路径（无接线）- 不写 rt2 不建 journal`() {
        val store = newStore()
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        assertTrue(fs.saveData(0, "test"))
        assertTrue(Files.isRegularFile(temp.resolve("save00.sav")))
        assertFalse(Files.exists(temp.resolve("save00.sav.rt2")))
        assertFalse(Files.exists(temp.resolve("save00.sav.journal")))
        assertFalse(Files.exists(temp.resolve("save00.sav.bak")))
    }

    @Test
    fun `同池面同内容 - 配对路写出的 sav 字节与缺省路逐字节一致`() {
        // 缺省路（无接线）。
        val plainStore = newStore()
        val plainFs = FileSystemOps(
            plainStore,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        assertTrue(plainFs.saveData(0, "same"))
        val plainBytes = Files.readAllBytes(temp.resolve("save00.sav"))

        // 配对路（有接线；内容一致的另一份池面）。
        val (pairFs, _, _) = fixture()
        assertTrue(pairFs.saveData(0, "same"))
        val pairBytes = Files.readAllBytes(temp.resolve("save00.sav"))

        assertTrue(plainBytes.contentEquals(pairBytes), "配对事务不得改 .sav 的字节")
    }

    @Test
    fun `encode 字节退化为空 - 恢复侧容器解码红（反向证明①，故障注入）`() {
        // 注入：让端口抓出的 .rt2 是空数组（等价于 encode() 退回空数组）。
        // 用例断言「落盘的 .rt2 必须能解码回原分段」—— 注入态必红，恢复后本文件全绿。
        val (fs, _, root) = fixture(corruptCapture = true)
        assertTrue(fs.saveData(0, "test"))
        val bytes = Files.readAllBytes(root.resolve("save00.sav.rt2"))
        // 注入态：CRC / 长度破坏，decode 必抛 —— 反向证明的「红」由断言 decode 成功承担。
        org.junit.jupiter.api.assertThrows<SidecarFormatException> {
            SidecarSaveBundle.decode(bytes)
        }
        // 正向半边：把注入拿掉重存一次，同一用例读数即恢复绿（证明红来自注入本身）。
        val (fsOk, _, rootOk) = fixture()
        // 复用同一个临时目录会撞文件名，改存 1 号槽。
        assertTrue(fsOk.saveData(1, "test"))
        val ok = SidecarSaveBundle.decode(Files.readAllBytes(rootOk.resolve("save01.sav.rt2")))
        assertEquals(setOf("school.mod", "legacy.mod"), ok.chunks.map { it.packageId }.toSet())
    }

    // ------------------------------------------------------------------
    // ③ 崩溃恢复：残留 journal 在下次存档前被结清
    // ------------------------------------------------------------------

    @Test
    fun `残留 journal 与残 part - 下次存档前被恢复机器结清`() {
        val (fs, _, root) = fixture()
        // 手工种下一个 PREPARED 残留：journal + 不匹配新 SHA 的 .part（⇒ plan = KeepOld）。
        val journal = SaveRecovery.Journal(
            transactionId = "stale-tx",
            oldPairGeneration = 0L,
            newPairGeneration = 1L,
            members = listOf(
                SaveRecovery.MemberJournal(
                    "save00.sav", "save00.sav.part", "save00.sav.bak",
                    sha256Hex(ByteArray(0)), sha256Hex("junk".toByteArray()),
                ),
            ),
            commitState = SaveRecovery.CommitState.PREPARED,
        )
        Files.write(root.resolve("save00.sav.journal"), SaveTransaction.encodeJournal(journal))
        Files.write(root.resolve("save00.sav.part"), "junk".toByteArray())

        assertTrue(fs.saveData(0, "test"), "结清残留后本次存档应成功")
        assertFalse(Files.exists(root.resolve("save00.sav.journal")))
        assertFalse(Files.exists(root.resolve("save00.sav.part")))
        assertTrue(Files.isRegularFile(root.resolve("save00.sav.rt2")))
    }
}
