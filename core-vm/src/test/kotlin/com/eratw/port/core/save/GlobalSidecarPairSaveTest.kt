package com.eratw.port.core.save

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RegistryConflictException
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.mod.ModStorageSectionCodec
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * RT2 C-RES1 的落盘验收：`global.sav` + `global.sav.rt2` 侧车经 [SaveTransaction] 配对
 * 事务的端到端（`FileSystemOps.saveGlobal` / `loadGlobal` 消费 `GlobalSidecarSaveWiring`
 * 的生产路径）。
 *
 * 判据（CONTRACT-RT2-C-RES1 §3）：
 * - a. SAVEGLOBAL 配对：`global.sav` 落盘 ⇒ 同名 `.rt2` 配对落盘（mod GLOBAL 分片随行），
 *   journal = `global.sav.journal`，写序零自研（全走 [SaveTransaction.execute]）；
 * - b. LOADGLOBAL 交还：分片交还宿主；rt2 缺/坏/绑定失配 fail-closed 上警告，
 *   LOADGLOBAL 返回值与异常形状不变（原版从不抛，VariableEvaluator.cs:2513-2589）；
 * - c. 缺省路径字节零变化：无接线时 global.sav 写读与现状一致，不产 rt2/journal/bak；
 * - d. `.sav` 纯净：mod GLOBAL 值不漏进 global.sav（同池面字节一致 + 机制锚见
 *   [FileSystemOps.saveGlobalPair] KDoc）。
 */
class GlobalSidecarPairSaveTest {

    @TempDir
    lateinit var temp: Path

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 空投射器（global 档恒不脱水；projectedEntities 恒空）。 */
    private object EmptyProjector : ICharaSlotProjector {
        override fun project(entityId: Long): Int = -1
        override fun unproject(entityId: Long): Boolean = false
        override fun charIndexOf(entityId: Long): Int = -1
        override val rosterGeneration: Long get() = 0L
        override fun charIndexOfChecked(entityId: Long, expectedGeneration: Long): Int = -1
        override fun entityAt(charIndex: Int): Long = com.eratw.port.core.entity.EntityIdCodec.NULL
        override fun projectedEntities(): LongArray = LongArray(0)
        override fun syncFromCharaSlot(entityId: Long) = Unit
        override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) = Unit
        override fun onCharacterRowDeleted(deletedIndex: Int) = Unit
    }

    /**
     * 测试侧 global 端口：与生产 `ModSidecarStoragePort` 同一映射（每包一段 = 每包一片），
     * 但全部状态在本类内可断言；[corruptCapture] / [shiftBinding] / [throwOnCapture]
     * 是反向证明与异常形状用例的注入口。
     */
    private class FakeGlobalPort(
        private val saveService: SaveService,
        private val storage: () -> com.eratw.port.core.mod.ModStorageStore,
        private val adopt: (com.eratw.port.core.mod.ModStorageStore) -> Unit,
        private val activeIds: () -> Set<String>,
    ) : GlobalSidecarStoragePort {
        var corruptCapture = false
        var shiftBinding = false
        var throwOnCapture = false
        val warnings = mutableListOf<String>()
        var restoredSections: List<com.eratw.port.core.mod.ModStorageSection>? = null

        override fun captureGlobalChunks(): SidecarChunkGroups {
            if (throwOnCapture) error("端口抓取炸了（注入）")
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

        override fun captureGlobalRt2Bytes(
            binding: SidecarBinding,
            activeChunks: List<PackageChunk>,
            orphanChunks: List<PackageChunk>,
        ): ByteArray {
            val effective = if (shiftBinding) {
                SidecarBinding(binding.uniqueCode, binding.scriptVersion, binding.saveTextHash + 1)
            } else {
                binding
            }
            val bytes = saveService.captureGlobalBundle(effective, activeChunks, orphanChunks).encode()
            return if (corruptCapture) bytes.copyOfRange(0, bytes.size - 1) else bytes
        }

        override fun restoreGlobalRt2(bytes: ByteArray, binding: SidecarBinding): Boolean {
            val bundle = SidecarSaveBundle.decode(bytes)
            return when (val outcome = saveService.restoreGlobalBundle(bundle, binding)) {
                is SaveService.GlobalRestoreOutcome.Applied -> {
                    val sections = outcome.chunks.flatMap {
                        ModStorageSectionCodec.decode(it.payload, activeIds())
                    }
                    adopt(
                        com.eratw.port.core.mod.ModStorageStore.load(
                            ModStorageSectionCodec.encode(sections),
                            activeIds(),
                        ),
                    )
                    restoredSections = sections
                    true
                }
                is SaveService.GlobalRestoreOutcome.BindingMismatch -> {
                    warnings += "绑定失配 expected=${outcome.expected} actual=${outcome.actual}"
                    false
                }
            }
        }

        override fun warn(message: String) {
            warnings += message
        }
    }

    /** 带 mod GLOBAL 值的存储容器（一激活一孤儿，与槽档先例同构）。 */
    private fun populatedStorage(): com.eratw.port.core.mod.ModStorageStore =
        com.eratw.port.core.mod.ModStorageStore().apply {
            apiFor("school.mod").put("gold", "100")
            apiFor("school.mod").put("level", "3")
            apiFor("legacy.mod").put("old", "keep")
            deactivate("legacy.mod")
        }

    /** 带用户 `#DIM GLOBAL SAVEDATA` 值的变量池（global.sav 的 Base 侧内容）。 */
    private fun newStore(): VariableStore = VariableStore().apply {
        declareUserVar(
            DimParser.parse("#DIM", "GLOBAL SAVEDATA GUI, 2", isPrivate = false, DimParseConfig()),
        )
        writeInt("GLOBAL", longArrayOf(3), 77L)
        writeInt("GUI", longArrayOf(1), 88L)
    }

    private class Fixture(
        val store: VariableStore,
        val fs: FileSystemOps,
        val port: FakeGlobalPort,
        val root: Path,
    )

    private fun fixture(
        corruptCapture: Boolean = false,
        shiftBinding: Boolean = false,
        throwOnCapture: Boolean = false,
    ): Fixture {
        val store = newStore()
        val svc = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            EmptyProjector,
            PackageShortIdRegistry(),
        )
        val port = FakeGlobalPort(svc, { populatedStorage() }, { }, { setOf("school.mod") })
        port.corruptCapture = corruptCapture
        port.shiftBinding = shiftBinding
        port.throwOnCapture = throwOnCapture
        GlobalSidecarSaveWiring.install(store, GlobalSidecarSaveWiring(port, temp))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        return Fixture(store, fs, port, temp)
    }

    /** 无接线的对照装置（缺省路径）。 */
    private fun plainFixture(): FileSystemOps = FileSystemOps(
        newStore(),
        RootedSaveFileIo(temp),
        SaveConfig(systemSaveInBinary = true, zipSaveData = true),
    )

    // ------------------------------------------------------------------
    // 判据 a：SAVEGLOBAL 配对落盘
    // ------------------------------------------------------------------

    @Test
    fun `SAVEGLOBAL 配对落盘 - global 与 rt2 成对且无残件`() {
        val f = fixture()
        f.fs.saveGlobal()
        assertTrue(Files.isRegularFile(f.root.resolve("global.sav")))
        assertTrue(Files.isRegularFile(f.root.resolve("global.sav.rt2")))
        assertFalse(Files.exists(f.root.resolve("global.sav.part")))
        assertFalse(Files.exists(f.root.resolve("global.sav.rt2.part")))
        assertFalse(Files.exists(f.root.resolve("global.sav.journal")))
        // `.bak` 是事务窗口内的回滚源，COMMITTED 后即轮出（era-arch §8 清理矩阵；M1-2）。
        assertFalse(Files.exists(f.root.resolve("global.sav.bak")))
        assertFalse(Files.exists(f.root.resolve("global.sav.rt2.bak")))
        // global.sav 字节能被既有探测判 OK（原版语义不因配对事务改变）。
        val bytes = Files.readAllBytes(f.root.resolve("global.sav"))
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(bytes, SaveFileKind.GLOBAL, GameBaseData(), null).state,
        )
    }

    @Test
    fun `global 容器解码 - mod GLOBAL 分片随行且实体载荷恒空`() {
        val f = fixture()
        f.fs.saveGlobal()
        val bundle = SidecarSaveBundle.decode(Files.readAllBytes(f.root.resolve("global.sav.rt2")))
        assertTrue(bundle.entityGraphPayload.isEmpty(), "SAVEGLOBAL 不带实体载荷（实体按槽走）")
        assertTrue(bundle.virtualNoSnapshot.isEmpty(), "虚拟 NO 是每槽身份，global.rt2 恒不带该段")
        assertTrue(bundle.registrySnapshot.isNotEmpty(), "注册表快照随行")
        assertEquals(setOf("school.mod", "legacy.mod"), bundle.chunks.map { it.packageId }.toSet())
        val byId = bundle.chunks.associate { it.packageId to it.payload }
        val school = ModStorageSectionCodec.decode(byId.getValue("school.mod"), setOf("school.mod")).single()
        assertEquals(
            "100",
            (school.entries.getValue("gold") as com.eratw.port.modapi.ModValue.StrValue).value,
        )
        val legacy = ModStorageSectionCodec.decode(byId.getValue("legacy.mod"), emptySet()).single()
        assertEquals("keep", (legacy.entries["old"] as com.eratw.port.modapi.ModValue.StrValue).value)
    }

    // ------------------------------------------------------------------
    // 判据 b：LOADGLOBAL 交还 + fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `LOADGLOBAL - 分片交还宿主且 Base 全局值真往返`() {
        val f = fixture()
        f.fs.saveGlobal()
        f.store.writeInt("GLOBAL", longArrayOf(3), 0L)
        f.store.writeInt("GUI", longArrayOf(1), 0L)
        assertTrue(f.fs.loadGlobal(), "LOADGLOBAL 应成功")
        assertEquals(77L, f.store.readInt("GLOBAL", longArrayOf(3)), "global.sav 原版语义不受配对影响")
        assertEquals(88L, f.store.readInt("GUI", longArrayOf(1)))
        val restored = f.port.restoredSections
        assertTrue(restored != null, "LOADGLOBAL 应把 mod GLOBAL 分片交还宿主")
        assertEquals(setOf("school.mod", "legacy.mod"), restored!!.map { it.packageId }.toSet())
        assertTrue(f.port.warnings.isEmpty(), "正常往返不应有警告，实得 ${f.port.warnings}")
    }

    @Test
    fun `rt2 缺失 - LOADGLOBAL 仍 true 但上警告`() {
        val f = fixture()
        f.fs.saveGlobal()
        Files.delete(f.root.resolve("global.sav.rt2"))
        assertTrue(f.fs.loadGlobal(), "rt2 缺失不得翻转 LOADGLOBAL 的返回值（原版从不抛）")
        assertTrue(f.port.restoredSections == null, "缺失时不应有分片交还")
        assertTrue(f.port.warnings.any { it.contains("缺失") }, "应上缺失警告，实得 ${f.port.warnings}")
    }

    @Test
    fun `rt2 损坏 - LOADGLOBAL 仍 true 但上警告`() {
        val f = fixture()
        f.fs.saveGlobal()
        Files.write(f.root.resolve("global.sav.rt2"), byteArrayOf(1, 2, 3))
        assertTrue(f.fs.loadGlobal(), "rt2 损坏不得翻转 LOADGLOBAL 的返回值")
        assertTrue(f.port.restoredSections == null)
        assertTrue(f.port.warnings.any { it.contains("交还失败") }, "应上损坏警告，实得 ${f.port.warnings}")
    }

    @Test
    fun `绑定失配 - 不交还且上警告 LOADGLOBAL 仍 true`() {
        val f = fixture(shiftBinding = true)
        f.fs.saveGlobal()
        assertTrue(f.fs.loadGlobal(), "绑定失配不得翻转 LOADGLOBAL 的返回值")
        assertTrue(f.port.restoredSections == null, "绑定失配不应交还分片")
        assertTrue(f.port.warnings.any { it.contains("绑定失配") }, "应上失配警告，实得 ${f.port.warnings}")
    }

    @Test
    fun `SAVEGLOBAL 端口抓取异常收敛为全局保存错误`() {
        val f = fixture(throwOnCapture = true)
        // 原版抓取段在 catch(SystemException) 范围内（VariableEvaluator.cs:2476-2509）
        // ⇒ 端口抓取炸掉也必须收敛成 CodeEE(ErrorSavingGlobalData)，不能原样冒泡。
        val e = assertFailsWith<CodeEE> { f.fs.saveGlobal() }
        assertEquals(FileSystemOps.MES_SAVE_GLOBAL, e.message)
    }

    // ------------------------------------------------------------------
    // 判据 c：缺省路径字节零变化 + 判据 d：.sav 纯净
    // ------------------------------------------------------------------

    @Test
    fun `缺省路径（无接线）- 不写 rt2 不建 journal`() {
        val fs = plainFixture()
        fs.saveGlobal()
        assertTrue(Files.isRegularFile(temp.resolve("global.sav")))
        assertFalse(Files.exists(temp.resolve("global.sav.rt2")))
        assertFalse(Files.exists(temp.resolve("global.sav.journal")))
        assertFalse(Files.exists(temp.resolve("global.sav.bak")))
        assertTrue(fs.loadGlobal(), "无接线的 LOADGLOBAL 照常成功")
    }

    @Test
    fun `同池面同内容 - 配对路写出的 global 与缺省路逐字节一致（sav 纯净）`() {
        // 缺省路（无接线）。
        val plainFs = plainFixture()
        plainFs.saveGlobal()
        val plainBytes = Files.readAllBytes(temp.resolve("global.sav"))

        // 配对路（有接线；同一份池面 + mod 存储里**有**两包 GLOBAL 值）：
        // mod GLOBAL 状态若漏进 global.sav，这里的字节就会分叉 —— 本断言同时是判据 c
        // 与判据 d 的用例（判据 d 的机制锚见 saveGlobalPair KDoc：SaveCapture 的抓取面
        // 结构性接触不到 ModStorageStore）。
        val pairFs = fixture().fs
        pairFs.saveGlobal()
        val pairBytes = Files.readAllBytes(temp.resolve("global.sav"))

        assertTrue(plainBytes.contentEquals(pairBytes), "配对事务不得改 global.sav 的字节")
        assertTrue(Files.isRegularFile(temp.resolve("global.sav.rt2")), "配对面照常产 .rt2")
    }

    // ------------------------------------------------------------------
    // SaveService 层：global 恢复的独立形状（不走 restoreBundle）
    // ------------------------------------------------------------------

    @Test
    fun `槽档容器喂进 global 恢复口 - 当场拒绝（形状闸）`() {
        val svc = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            EmptyProjector,
            PackageShortIdRegistry(),
        )
        val binding = SidecarBinding(11L, 22L, 33)
        // 槽档束：实体载荷 = 空内核的编码（非空，含版本头与名表）。
        val slotBundle = svc.captureBundle(binding, orphanChunks = listOf(PackageChunk("old.mod", byteArrayOf(7))))
        assertTrue(slotBundle.entityGraphPayload.isNotEmpty())
        val e = assertFailsWith<IllegalArgumentException> { svc.restoreGlobalBundle(slotBundle, binding) }
        assertTrue("global.rt2" in e.message!!, e.message)
    }

    @Test
    fun `global 恢复不并注册表 - 撞号也不炸且分片原样交出`() {
        val senderReg = PackageShortIdRegistry().apply { allocate("mod.a") }
        val sender = SaveService(EntityKernel(emptyList(), emptyList(), emptyList()), EmptyProjector, senderReg)
        val binding = SidecarBinding(11L, 22L, 33)
        val global = sender.captureGlobalBundle(binding, orphanChunks = listOf(PackageChunk("old.mod", byteArrayOf(7))))

        // 接收方注册表与发送方快照撞号（短 ID 1 被不同的包占着）：若恢复路错误地并了
        // 注册表，mergeSnapshot 必抛 RegistryConflictException；照契约「不并注册表」
        // 则必须 Applied 且分片原样。
        val receiver = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            EmptyProjector,
            PackageShortIdRegistry().apply { allocate("mod.b") },
        )
        // 对照：同样的快照走 mergeSnapshot 必炸（证明「撞号」注入是真的，不是无效注入）。
        val receiverReg = PackageShortIdRegistry().apply { allocate("mod.b") }
        assertFailsWith<RegistryConflictException> { receiverReg.mergeSnapshot(global.registrySnapshot) }

        val outcome = receiver.restoreGlobalBundle(global, binding)
        assertTrue(outcome is SaveService.GlobalRestoreOutcome.Applied, "不并注册表 ⇒ 不撞号 ⇒ Applied，实得 $outcome")
        assertEquals(
            listOf("old.mod"),
            (outcome as SaveService.GlobalRestoreOutcome.Applied).chunks.map { it.packageId },
        )
    }

    // ------------------------------------------------------------------
    // 判据 a 附属：崩溃恢复
    // ------------------------------------------------------------------

    @Test
    fun `残留 journal 与残 part - 下次 SAVEGLOBAL 前被恢复机器结清`() {
        val f = fixture()
        // 手工种下一个 PREPARED 残留：journal + 不匹配新 SHA 的 .part（⇒ plan = KeepOld）。
        val journal = SaveRecovery.Journal(
            transactionId = "stale-global-tx",
            oldPairGeneration = 0L,
            newPairGeneration = 1L,
            members = listOf(
                SaveRecovery.MemberJournal(
                    "global.sav", "global.sav.part", "global.sav.bak",
                    sha256Hex(ByteArray(0)), sha256Hex("junk".toByteArray()),
                ),
            ),
            commitState = SaveRecovery.CommitState.PREPARED,
        )
        Files.write(f.root.resolve("global.sav.journal"), SaveTransaction.encodeJournal(journal))
        Files.write(f.root.resolve("global.sav.part"), "junk".toByteArray())

        f.fs.saveGlobal()
        assertFalse(Files.exists(f.root.resolve("global.sav.journal")))
        assertFalse(Files.exists(f.root.resolve("global.sav.part")))
        assertTrue(Files.isRegularFile(f.root.resolve("global.sav.rt2")))
    }
}
