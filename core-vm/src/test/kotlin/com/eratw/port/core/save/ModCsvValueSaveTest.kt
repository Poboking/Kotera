package com.eratw.port.core.save

import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.mod.ModStorageSectionCodec
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.CsvKeywordBinding
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.CharIdentity
import com.eratw.port.core.vars.CsvValue
import com.eratw.port.core.vars.ModCsvInjectionPlan
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.ModCsvValueKey
import com.eratw.port.core.vars.ModCsvValueRestorer
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * RT2 M1-4 阶段一的存读档验收（`.sav` 角色块 + `.rt2` CSV 段的配对语义）。
 *
 * 装置用合成 [VariableSizes]（CFLAG 物理列 600、Base 容量 4）把「Base 段」和「注入位」
 * 分开，注入位取 500/503 这种远位以证明「按关键字重映射」而不是「按 Base 长度顺延」。
 * 端口实现照抄生产 `ModSidecarStoragePort` 的编解码管道（同一份 [ModCsvValueCodec]），
 * 但激活包集固定为 `school.mod`，孤儿包 `legacy.mod` 用于透传对照。
 */
class ModCsvValueSaveTest {

    @TempDir
    lateinit var temp: Path

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

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
    }

    /** 生产端口的最小镜像：载荷包装/拆包与激活包过滤都走生产编解码器。 */
    private class CodecPort(
        private val saveService: SaveService,
        private val storage: () -> ModStorageStore,
        private val adopt: (ModStorageStore) -> Unit,
        private val activeIds: () -> Set<String>,
    ) : SidecarStoragePort {
        val warnings = mutableListOf<String>()

        override fun captureChunks(csv: ModCsvValueBag): SidecarChunkGroups {
            val byPackage = csv.byPackage()
            val active = ArrayList<PackageChunk>()
            val orphan = ArrayList<PackageChunk>()
            for (section in storage().sectionsSnapshot()) {
                val bytes = ModStorageSectionCodec.encode(listOf(section))
                val payload = if (section.orphan) {
                    bytes
                } else {
                    ModCsvValueCodec.encode(bytes, byPackage[section.packageId] ?: ModCsvValueBag.EMPTY)
                }
                val chunk = PackageChunk(section.packageId, payload)
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
                    val sections = bundle.chunks.flatMap { chunk ->
                        ModStorageSectionCodec.decode(
                            ModCsvValueCodec.decode(chunk.payload).storage,
                            activeIds(),
                        )
                    }
                    adopt(ModStorageStore.load(ModStorageSectionCodec.encode(sections), activeIds()))
                    SidecarRestoreResult(true, ModCsvValueCodec.mergeApplied(bundle.chunks, activeIds()))
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

    private class Harness(
        val fs: FileSystemOps,
        val store: VariableStore,
        val port: CodecPort,
        val storage: ModStorageStore,
    )

    private fun planOf(index: Int, keyword: String = "KOJO_A"): ModCsvInjectionPlan = ModCsvInjectionPlan.of(
        baseCapacities = mapOf("CFLAG" to BASE_CFLAG),
        declared = mapOf(
            "CFLAG" to mapOf(keyword to CsvKeywordBinding(keyword, index, "school.mod", alias = false)),
        ),
        tableToVar = mapOf("CFLAG" to "CFLAG"),
    )

    private fun harness(
        csvNos: List<Int>,
        plan: ModCsvInjectionPlan?,
        activeIds: Set<String> = setOf("school.mod"),
        keywordIndex: ((String, String) -> Int?)? = null,
    ): Harness {
        val store = newStore(csvNos)
        val storage = ModStorageStore().apply {
            apiFor("school.mod").put("gold", "100")
            apiFor("legacy.mod").put("old", "keep")
            deactivate("legacy.mod")
        }
        var holder = storage
        val service = SaveService(
            EntityKernel(emptyList(), emptyList(), emptyList()),
            EmptyProjector,
            PackageShortIdRegistry(),
        )
        val port = CodecPort(service, { holder }, { holder = it }, { activeIds })
        SidecarSaveWiring.install(store, SidecarSaveWiring(port, temp, plan, keywordIndex))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        return Harness(fs, store, port, holder)
    }

    private fun newStore(csvNos: List<Int>): VariableStore {
        val sizes = VariableSizes().apply { charaInt1D[9] = CFLAG_COLUMN }
        val store = VariableStore(registry = VariableRegistry(sizes = sizes))
        csvNos.forEachIndexed { i, csvNo ->
            store.registerTemplate(
                CharacterTemplate(no = i + 1L, name = "c$csvNo", callname = "c$csvNo", csvNo = csvNo.toLong()),
            )
        }
        for (i in csvNos.indices) store.addCharacter(i + 1L)
        return store
    }

    private fun cflag(store: VariableStore): com.eratw.port.core.vars.CharacterTable.LongCol =
        store.characters.int1[store.registry.def("CFLAG")!!.index]

    // ------------------------------------------------------------------
    // ① 纯 Base：空计划与 null 计划逐字节一致
    // ------------------------------------------------------------------

    @Test
    fun `纯 Base - 空计划与 null 计划的 sav 与 rt2 逐字节一致`() {
        val base = harness(listOf(101, 102), plan = null)
        cflag(base.store).set(0, 1, 3)
        assertTrue(base.fs.saveData(0, "t"))
        val savNull = Files.readAllBytes(temp.resolve("save00.sav"))
        val rt2Null = Files.readAllBytes(temp.resolve("save00.sav.rt2"))

        val emptyPlan = harness(listOf(101, 102), plan = ModCsvInjectionPlan.EMPTY)
        cflag(emptyPlan.store).set(0, 1, 3)
        assertTrue(emptyPlan.fs.saveData(0, "t"))
        assertContentEquals(savNull, Files.readAllBytes(temp.resolve("save00.sav")))
        assertContentEquals(rt2Null, Files.readAllBytes(temp.resolve("save00.sav.rt2")))
    }

    // ------------------------------------------------------------------
    // ② 注入位值经 .rt2 往返
    // ------------------------------------------------------------------

    @Test
    fun `往返 - 三角色注入位值按 CSV 番号落回`() {
        val saver = harness(listOf(101, 102, 103), planOf(500)) { _, _ -> 500 }
        for (pos in 0 until 3) cflag(saver.store).set(pos, 500, (pos + 1) * 11L)
        assertTrue(saver.fs.saveData(0, "t"))

        val loader = harness(listOf(101, 102, 103), planOf(500)) { _, _ -> 500 }
        loader.fs.loadData(0)
        assertEquals(11, cflag(loader.store).get(0, 500))
        assertEquals(22, cflag(loader.store).get(1, 500))
        assertEquals(33, cflag(loader.store).get(2, 500))
        assertEquals(0, cflag(loader.store).get(0, 4), "注入位不落 Base 段")
    }

    // ------------------------------------------------------------------
    // ③ DELCHARA 中间角色后仍按 CSV 番号对齐
    // ------------------------------------------------------------------

    @Test
    fun `DELCHARA - 中间角色被删后剩余角色值按 CSV 番号对齐而非注册下标`() {
        val saver = harness(listOf(101, 102, 103), planOf(500)) { _, _ -> 500 }
        for (pos in 0 until 3) cflag(saver.store).set(pos, 500, (pos + 1) * 11L)
        assertTrue(saver.fs.saveData(0, "t"))
        val bag = ModCsvValueCodec.mergeApplied(
            SidecarSaveBundle.decode(Files.readAllBytes(temp.resolve("save00.sav.rt2"))).chunks,
            setOf("school.mod"),
        )
        assertEquals(3, bag.size, "三个角色各一注入位值")

        // 交错名册后删掉中间角色：CSV 101 离场，行序 [103, 102] —— 与存档期位置完全不同。
        val store2 = newStore(listOf(103, 101, 102))
        store2.deleteCharacter(1L)
        assertEquals(2, store2.characters.count)
        val warnings = mutableListOf<String>()
        ModCsvValueRestorer.apply(store2, planOf(500), { _, _ -> 500 }, bag, warnings::add)

        assertEquals(33, cflag(store2).get(0, 500), "CSV 番号 103")
        assertEquals(22, cflag(store2).get(1, 500), "CSV 番号 102")
        assertTrue(warnings.any { it.contains("101") }, "离场角色逐条上警告: $warnings")
    }

    // ------------------------------------------------------------------
    // ④ 注入位平移（500 → 503）
    // ------------------------------------------------------------------

    @Test
    fun `平移 - 关键字重映射到 503 后旧档值落到新下标`() {
        val saver = harness(listOf(101), planOf(500)) { _, _ -> 500 }
        cflag(saver.store).set(0, 500, 42)
        assertTrue(saver.fs.saveData(0, "t"))

        val loader = harness(listOf(101), planOf(503)) { _, _ -> 503 }
        loader.fs.loadData(0)
        assertEquals(42, cflag(loader.store).get(0, 503), "按关键字重映射到当下注册表下标")
        assertEquals(0, cflag(loader.store).get(0, 500), "旧下标不再写")
    }

    // ------------------------------------------------------------------
    // ⑤ 孤儿包透传 + 值不并回
    // ------------------------------------------------------------------

    @Test
    fun `孤儿包 - 分片载荷不包装 CSV 且值不并回变量池`() {
        val h = harness(listOf(101), planOf(500)) { _, _ -> 500 }
        cflag(h.store).set(0, 500, 42)
        assertTrue(h.fs.saveData(0, "t"))

        val bundle = SidecarSaveBundle.decode(Files.readAllBytes(temp.resolve("save00.sav.rt2")))
        val byId = bundle.chunks.associate { it.packageId to it.payload }
        assertTrue(ModCsvValueCodec.decode(byId.getValue("legacy.mod")).csv.isEmpty, "孤儿片不包 CSV 段")
        val legacySection = h.storage.sectionsSnapshot().single { it.packageId == "legacy.mod" }
        assertContentEquals(
            ModStorageSectionCodec.encode(listOf(legacySection)),
            byId.getValue("legacy.mod"),
            "孤儿片逐字节透传",
        )
        assertEquals(1, ModCsvValueCodec.decode(byId.getValue("school.mod")).csv.size)

        val orphanOnly = listOf(
            PackageChunk(
                "legacy.mod",
                ModCsvValueCodec.encode(
                    byId.getValue("legacy.mod"),
                    ModCsvValueBag.of(
                        listOf(
                            ModCsvValueKey("legacy.mod", "CFLAG", "Old", CharIdentity.CsvNo(101)) to
                                CsvValue.IntValue(77),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(
            ModCsvValueCodec.mergeApplied(orphanOnly, setOf("school.mod")).isEmpty,
            "未激活包的注入值不进变量池",
        )
    }

    private companion object {
        /** 合成 Base 容量：CFLAG 的「Base CSV 自己声明」的格子数。 */
        const val BASE_CFLAG: Int = 4

        /** 合成物理列宽：远大于 Base 容量，容纳 500/503 这类注入位。 */
        const val CFLAG_COLUMN: Int = 600
    }
}
