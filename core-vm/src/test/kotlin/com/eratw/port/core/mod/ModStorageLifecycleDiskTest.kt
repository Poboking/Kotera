package com.eratw.port.core.mod

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.PackageChunk
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarChunkGroups
import com.eratw.port.core.save.SidecarRestoreResult
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.save.SidecarStoragePort
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * M4-3 判据「禁用/启用无损盘面」的验证面（CONTRACT-RT2-C-RES2 §3-b）：mod.a 写 storage →
 * 停用 → 配对存档（[FileSystemOps.saveData] 内部的配对写路真落盘 .sav + .sav.rt2）
 * → 重启用 → 读档（[FileSystemOps.loadData] 内部的侧车恢复路）→ **原值回来**。
 *
 * 与既有两套用例的分工（本文件是两者的**盘面合流**，不重复其断言面）：
 * - ModStorageStoreTest 钉的是**内存版**语义（禁用不等于删除、孤儿段无损）——本文件把同一
 *   生命周期经真盘 .rt2 往返再走一遍；
 * - SidecarPairSaveTest 钉的是配对事务本身（journal/.bak/fail-closed）——本文件直调其装置
 *   形状（真 [SidecarStoragePort] + 临时目录真盘），但判据点在**禁用段的分类与无损**；
 * - 容器级交还先例 = app 侧 ModSidecarWireTest（读档后 `restored.apiFor(...)` 拿回原值），
 *   core 层没有装配链，本文件用同形端口 + adopt 回调把新容器接回持有者，语义等价。
 *
 * 判据点（契约 §3-b）：
 * - 停用期的段在抓取时分类为**孤儿片**（照 ModStorageStore.sectionsSnapshot 的 orphan 口径），
 *   在盘上容器里仍在（激活组在前、孤儿组在后），读档后整段保留为非激活；
 * - 读档恢复后**激活集与孤儿集**逐包断言（重启用的包回激活集，从未启用的包留孤儿集）；
 * - 恢复值必须来自**盘面字节**而不是内存残值：读档前先污染内存值，盘上旧值要赢回来；
 * - 孤儿段逐字节透传：存前分片字节与盘上、读档后再编码三方一致，重启用拿回原值。
 */
class ModStorageLifecycleDiskTest {

    @TempDir
    lateinit var temp: Path

    /** 空投射器（F 车真装置未落，契约 §5.5 的许口径；形状同 SidecarPairSaveTest）。 */
    private object DiskProjector : ICharaSlotProjector {
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

    /** 存储容器持有者：adopt 回调把恢复出的新容器接进来（读档后 current 换新实例）。 */
    private class StorageHolder {
        var current: ModStorageStore = ModStorageStore()
    }

    /**
     * 测试侧端口：与生产 ModSidecarStoragePort（app 装配侧）同一映射
     * （每包一段 = 每包一片），全部状态在本类内可断言。
     */
    private class StoragePort(
        private val saveService: SaveService,
        private val holder: StorageHolder,
        private val activeIds: () -> Set<String>,
    ) : SidecarStoragePort {
        val warnings = mutableListOf<String>()
        var restoredSections: List<ModStorageSection>? = null

        override fun captureChunks(csv: ModCsvValueBag): SidecarChunkGroups {
            val active = ArrayList<PackageChunk>()
            val orphan = ArrayList<PackageChunk>()
            for (section in holder.current.sectionsSnapshot()) {
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
        ): ByteArray = saveService.captureBundle(binding, activeChunks, orphanChunks).encode()

        override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult {
            val bundle = SidecarSaveBundle.decode(bytes)
            return when (val outcome = saveService.restoreBundle(bundle, binding)) {
                is SaveService.RestoreOutcome.Applied -> {
                    val sections = bundle.chunks.flatMap {
                        ModStorageSectionCodec.decode(it.payload, activeIds())
                    }
                    holder.current = ModStorageStore.load(
                        ModStorageSectionCodec.encode(sections),
                        activeIds(),
                    )
                    restoredSections = sections
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

    private class DiskFixture(
        val fs: FileSystemOps,
        val port: StoragePort,
        val holder: StorageHolder,
        val root: Path,
    )

    /** 存好三包数据的容器：mod.a / mod.b 激活，legacy.mod 从未启用（孤儿对照组）。 */
    private fun seededStorage(): ModStorageStore = ModStorageStore().apply {
        apiFor("mod.a").put("gold", "100")
        apiFor("mod.a").put("level", "3")
        apiFor("mod.b").put("y", "2")
        apiFor("legacy.mod").put("heirloom", "keep")
        deactivate("legacy.mod")
    }

    private fun newStore(): VariableStore = VariableStore().apply {
        registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)))
        addCharacter(1L)
        writeInt("DAY", longArrayOf(0L), 7L)
    }

    private fun fixture(): DiskFixture {
        val store = newStore()
        val holder = StorageHolder()
        holder.current = seededStorage()
        val svc = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            DiskProjector,
            PackageShortIdRegistry(),
        )
        val port = StoragePort(
            svc,
            holder,
            { holder.current.packages().filterTo(HashSet()) { holder.current.isActive(it) } },
        )
        SidecarSaveWiring.install(store, SidecarSaveWiring(port, temp))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        return DiskFixture(fs, port, holder, temp)
    }

    @Test
    fun `停用后配对存档重启用读档原值回来且激活集与孤儿集正确`() {
        val fx = fixture()
        val storage = fx.holder.current
        // ① mod.a 写 storage（seededStorage 已写 gold/level），此刻是激活段。
        assertTrue(storage.isActive("mod.a"))
        assertFalse(storage.isActive("legacy.mod"))

        // ② 停用 mod.a：段留在容器里（禁用不等于删除），但分片分类翻成孤儿。
        storage.deactivate("mod.a")
        assertFalse(storage.isActive("mod.a"))
        assertEquals(setOf("mod.a", "legacy.mod"), storage.inactivePackages(), "停用期段 = 非激活保留")
        assertEquals(setOf("mod.b"), storage.packages().filterTo(HashSet()) { storage.isActive(it) })
        val groups = fx.port.captureChunks()
        assertEquals(listOf("mod.b"), groups.activeChunks.map { it.packageId }, "激活组只剩 mod.b")
        assertEquals(listOf("mod.a", "legacy.mod"), groups.orphanChunks.map { it.packageId }, "停用段进孤儿组")

        // ③ 配对存档：.sav + .rt2 真落盘，无事务残件。
        assertTrue(fx.fs.saveData(0, "test"), "SAVEDATA 应成功")
        assertTrue(Files.isRegularFile(fx.root.resolve("save00.sav")))
        assertTrue(Files.isRegularFile(fx.root.resolve("save00.sav.rt2")))
        assertFalse(Files.exists(fx.root.resolve("save00.sav.part")))
        assertFalse(Files.exists(fx.root.resolve("save00.sav.rt2.part")))
        assertFalse(Files.exists(fx.root.resolve("save00.sav.journal")))

        // ④ 重启用（读档前），并故意污染内存值：恢复值必须来自盘面字节。
        storage.activate("mod.a")
        assertTrue(storage.isActive("mod.a"))
        storage.apiFor("mod.a").put("gold", "999-post-disk")
        storage.apiFor("mod.a").put("level", "999")

        // ⑤ 读档：restoreSidecarIfWired → 端口 restoreRt2 → adopt 换新容器。
        fx.fs.loadData(0)
        assertTrue(fx.port.warnings.isEmpty(), "正常往返不应有警告，实得 ${fx.port.warnings}")
        val restored = fx.holder.current
        assertNotSame(storage, restored, "adopt 必须接回恢复出的新容器")

        // ⑥ 激活集与孤儿集（契约 §3-b 点名断言）。
        assertTrue(restored.isActive("mod.a"), "重启用的包回激活集")
        assertTrue(restored.isActive("mod.b"), "全程激活的包保持激活")
        assertEquals(setOf("legacy.mod"), restored.inactivePackages(), "从未启用的包留孤儿集")

        // 原值回来（盘上旧值赢过读档前的内存污染值）。
        assertEquals("100", restored.apiFor("mod.a").get("gold"), "盘面旧值必须赢回内存污染值")
        assertEquals("3", restored.apiFor("mod.a").get("level"), "盘面旧值必须赢回内存污染值")
        assertEquals("2", restored.apiFor("mod.b").get("y"), "无关包不受牵连")

        // 孤儿重启用拿回原值（禁用/启用无损闭环的最后一跳）。
        restored.activate("legacy.mod")
        assertEquals("keep", restored.apiFor("legacy.mod").get("heirloom"))
    }

    @Test
    fun `停用段经盘面往返逐字节无损且容器内激活组在前孤儿组在后`() {
        val fx = fixture()
        val storage = fx.holder.current
        storage.deactivate("mod.a")

        // 存前孤儿段分片字节（legacy 从未启用；mod.a 停用段同口径）。
        val preSaveGroups = fx.port.captureChunks()
        val preSaveLegacyChunk = preSaveGroups.orphanChunks.first { it.packageId == "legacy.mod" }.payload
        val preSaveModAChunk = preSaveGroups.orphanChunks.first { it.packageId == "mod.a" }.payload

        assertTrue(fx.fs.saveData(0, "test"))
        val bundle = SidecarSaveBundle.decode(Files.readAllBytes(fx.root.resolve("save00.sav.rt2")))
        // 容器分片序 = 激活组在前、孤儿组在后（captureBundle 合并口径）。
        assertEquals(
            listOf("mod.b", "mod.a", "legacy.mod"),
            bundle.chunks.map { it.packageId },
            "激活组在前、孤儿组在后",
        )
        // 盘上孤儿段与存前分片逐字节一致（透传不解释内容）。
        assertContentEquals(
            preSaveLegacyChunk,
            bundle.chunks.first { it.packageId == "legacy.mod" }.payload,
            "legacy 盘上逐字节",
        )
        assertContentEquals(preSaveModAChunk, bundle.chunks.first { it.packageId == "mod.a" }.payload, "mod.a 盘上逐字节")

        fx.fs.loadData(0)
        val restored = fx.holder.current
        assertTrue(fx.port.warnings.isEmpty(), "正常往返不应有警告，实得 ${fx.port.warnings}")
        // 读档后的孤儿段再编码与存前分片逐字节一致（orphan 是派生元数据，不落格式）。
        val restoredLegacy = restored.sectionsSnapshot().first { it.packageId == "legacy.mod" }
        assertTrue(restoredLegacy.orphan, "读档后未装的包留孤儿段")
        assertContentEquals(
            preSaveLegacyChunk,
            ModStorageSectionCodec.encode(listOf(restoredLegacy)),
            "读档后孤儿段再编码与存前逐字节一致",
        )
        // 重启用拿回原值。
        restored.activate("legacy.mod")
        assertEquals("keep", restored.apiFor("legacy.mod").get("heirloom"))
    }
}
