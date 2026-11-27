package com.eratw.port.core.save

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.EntityIdImportPlan
import com.eratw.port.core.entity.EntityIdRemapTable
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.EntityPayloadCodec
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RegistryConflictException
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.patch.CsvKeywordBinding
import com.eratw.port.core.patch.DeclaredEntityRef
import com.eratw.port.core.patch.EntityRefScope
import com.eratw.port.core.vars.CharIdentity
import com.eratw.port.core.vars.CharacterTable
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.CsvValue
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.ModCsvInjectionPlan
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.ModCsvValueKey
import com.eratw.port.core.vars.ModCsvValueRestorer
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EntityRefSaveProtocolTest {

    @TempDir
    lateinit var temp: Path

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

    private fun kernel(): EntityKernel = EntityKernel(
        listOf(
            ComponentSchema(
                "story.mod",
                "Node",
                listOf(ComponentFieldSpec("TARGET", isEntityRef = true)),
            ),
        ),
        listOf(RelationshipSchema("LINK")),
        listOf(EntityArchetype("NODE", listOf("Node"))),
    )

    private fun sourceRegistry(): PackageShortIdRegistry = PackageShortIdRegistry().apply {
        assertEquals(2, allocate("story.mod"))
    }

    private fun targetRegistry(): PackageShortIdRegistry = PackageShortIdRegistry().apply {
        assertEquals(2, allocate("local.mod"))
    }

    private fun declarations(): Map<String, Map<String, EntityRefScope>> = mapOf(
        "story.mod" to mapOf(
            "NREF" to EntityRefScope.NORMAL,
            "GLOBAL" to EntityRefScope.GLOBAL,
            "CREF" to EntityRefScope.CHARA,
        ),
    )

    private fun variableStore(): VariableStore = VariableStore(
        VariableRegistry(), VarConfig(systemSaveInBinary = true),
    ).apply {
        declareUserVar(DimParser.parse("#DIM", "SAVEDATA NREF, 2", isPrivate = false, DimParseConfig(systemSaveInBinary = true)))
        declareUserVar(DimParser.parse(
            "#DIM", "SAVEDATA CHARADATA CREF, 2", isPrivate = false,
            DimParseConfig(systemSaveInBinary = true),
        ))
        addPseudoCharacter()
    }

    private fun seedReferences(store: VariableStore, id: Long) {
        val otherId = EntityIdCodec.encode(1, 1, 0)
        store.writeInt("NREF", longArrayOf(0), id)
        store.writeInt("NREF", longArrayOf(1), otherId)
        store.writeInt("GLOBAL", longArrayOf(0), id)
        store.writeInt("CREF", longArrayOf(0, 0), id)
        store.writeInt("CREF", longArrayOf(0, 1), otherId)
    }

    private fun populatedSource(): Pair<PackageShortIdRegistry, EntityKernel> {
        val registry = sourceRegistry()
        val kernel = kernel()
        val id = kernel.create(2, "NODE")
        assertEquals(EntityIdCodec.encode(2, 0, 0), id)
        assertTrue(kernel.setInt(id, "TARGET", id))
        assertTrue(kernel.addRelation(id, "LINK", id))
        return registry to kernel
    }

    @Test
    fun `空声明段与有序声明段都能严格自解码`() {
        assertEquals(10, EntityRefDeclarationCodec.encode(emptyList()).size)
        assertTrue(EntityRefDeclarationCodec.decode(EntityRefDeclarationCodec.encode(emptyList())).isEmpty())

        val rows = listOf(
            DeclaredEntityRef("z.mod", "ZREF", EntityRefScope.NORMAL),
            DeclaredEntityRef("a.mod", "AREF", EntityRefScope.CHARA),
            DeclaredEntityRef("a.mod", "AREF", EntityRefScope.GLOBAL),
        )
        val encoded = EntityRefDeclarationCodec.encode(rows)
        assertEquals(rows.sortedWith(compareBy({ it.packageId }, { it.key }, { it.scope.ordinal })), EntityRefDeclarationCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> { EntityRefDeclarationCodec.decode(encoded.copyOfRange(0, encoded.size - 1)) }
        assertFailsWith<IllegalArgumentException> { EntityRefDeclarationCodec.decode(encoded + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> {
            EntityRefDeclarationCodec.encode(listOf(rows[1], rows[1]))
        }
    }

    @Test
    fun `同设备冲突拒绝而跨设备按包名字典序确定性重分配`() {
        val (source, _) = populatedSource()
        val target = targetRegistry()
        assertFailsWith<RegistryConflictException> {
            EntityIdImportPlan.of(target.snapshot(), source.encode(), crossDevice = false)
        }
        assertEquals(2, target.shortIdOf("local.mod"))

        val first = EntityIdImportPlan.of(target.snapshot(), source.encode(), crossDevice = true)
        val second = EntityIdImportPlan.of(target.snapshot(), source.encode(), crossDevice = true)
        assertContentEquals(first.registryBytes, second.registryBytes)
        assertEquals(3, first.mergedRegistry.entries.getValue("story.mod"))
        assertEquals(mapOf(2 to 3), first.remapTable.mappings)
        assertEquals(2, target.shortIdOf("local.mod"), "计划阶段不改活注册表")
    }

    @Test
    fun `恒等跨设备分配不伪装成需要声明段的重映射`() {
        val source = sourceRegistry()
        val target = PackageShortIdRegistry()
        val plan = EntityIdImportPlan.of(target.snapshot(), source.encode(), crossDevice = true)
        assertTrue(plan.remapTable.isEmpty)
        assertEquals(2, plan.mergedRegistry.entries.getValue("story.mod"))
        assertEquals(3, plan.mergedRegistry.nextId)
    }

    @Test
    fun `三面持久引用在prepare后整体重映射且普通值不误伤`() {
        val (sourceRegistry, sourceKernel) = populatedSource()
        val oldId = EntityIdCodec.encode(2, 0, 0)
        val newId = EntityIdCodec.encode(3, 0, 0)
        val sourceStore = variableStore().apply { seedReferences(this, oldId) }
        val sourceService = SaveService(sourceKernel, EmptyProjector, sourceRegistry)
        val bundle = sourceService.captureBundle(
            SidecarBinding(1, 2, 3),
            activeEntityRefs = declarations(),
        )
        assertTrue(bundle.entityRefDeclarationPayload.isNotEmpty())

        val targetRegistry = targetRegistry()
        val targetKernel = kernel()
        val targetStore = variableStore().apply { seedReferences(this, oldId) }
        val targetService = SaveService(targetKernel, EmptyProjector, targetRegistry, variableStore = targetStore)
        val prepared = targetService.prepareSlotRestore(
            bundle,
            bundle.binding,
            activeEntityRefs = declarations(),
            crossDevice = true,
        ) as SaveService.SlotRestorePreparation.Ready
        assertFalse(targetKernel.exists(newId), "prepare 不写内核")
        assertEquals(oldId, targetStore.readInt("NREF", longArrayOf(0)), "prepare 不写变量")
        assertEquals(2, targetRegistry.shortIdOf("local.mod"), "prepare 不写注册表")

        prepared.commit()
        assertEquals(3, targetRegistry.shortIdOf("story.mod"))
        assertTrue(targetKernel.exists(newId))
        assertFalse(targetKernel.exists(oldId))
        assertEquals(newId, targetKernel.getInt(newId, "TARGET"), "面① 组件 EntityRef")
        assertEquals(newId, targetKernel.queryRelations(newId, "LINK", outgoing = true).single(), "面② 关系端点")
        assertEquals(newId, targetStore.readInt("NREF", longArrayOf(0)), "面③ NORMAL")
        assertEquals(oldId, targetStore.readInt("GLOBAL", longArrayOf(0)), "GLOBAL 不由槽档跨设备重映射")
        assertEquals(newId, targetStore.readInt("CREF", longArrayOf(0, 0)), "面③ CHARA")
        val otherId = EntityIdCodec.encode(1, 1, 0)
        assertEquals(otherId, targetStore.readInt("NREF", longArrayOf(1)))
        assertEquals(otherId, targetStore.readInt("CREF", longArrayOf(0, 1)))
        assertEquals(SidecarBinding(1, 2, 3), bundle.binding)
    }

    @Test
    fun `声明缺失时跨设备恢复失败且内核注册表变量零写入`() {
        val (sourceRegistry, sourceKernel) = populatedSource()
        val bundle = SaveService(sourceKernel, EmptyProjector, sourceRegistry).captureBundle(
            SidecarBinding(1, 2, 3),
            activeEntityRefs = declarations(),
        )
        val targetRegistry = targetRegistry()
        val targetKernel = kernel()
        val targetStore = variableStore()
        val oldId = EntityIdCodec.encode(2, 0, 0)
        seedReferences(targetStore, oldId)
        val service = SaveService(targetKernel, EmptyProjector, targetRegistry, variableStore = targetStore)
        val broken = declarations().toMutableMap()
        broken["story.mod"] = broken.getValue("story.mod") + ("MISSING" to EntityRefScope.NORMAL)

        assertFailsWith<IllegalArgumentException> {
            service.prepareSlotRestore(bundle, bundle.binding, broken, crossDevice = true)
        }
        assertFalse(targetKernel.exists(oldId))
        assertEquals(2, targetRegistry.shortIdOf("local.mod"))
        assertEquals(oldId, targetStore.readInt("NREF", longArrayOf(0)))
    }

    @Test
    fun `v2无声明档在同设备可读而跨设备拒绝猜测`() {
        val (sourceRegistry, sourceKernel) = populatedSource()
        val v2 = SaveService(sourceKernel, EmptyProjector, sourceRegistry)
            .captureBundle(SidecarBinding(1, 2, 3))
        assertTrue(v2.entityRefDeclarationPayload.isEmpty())

        val sameDeviceKernel = kernel()
        val sameDeviceRegistry = PackageShortIdRegistry()
        SaveService(sameDeviceKernel, EmptyProjector, sameDeviceRegistry)
            .restoreBundle(v2, v2.binding)
        assertTrue(sameDeviceKernel.exists(EntityIdCodec.encode(2, 0, 0)))

        val targetKernel = kernel()
        val targetRegistry = targetRegistry()
        assertFailsWith<IllegalArgumentException> {
            SaveService(targetKernel, EmptyProjector, targetRegistry)
                .prepareSlotRestore(v2, v2.binding, crossDevice = true)
        }
        assertFalse(targetKernel.exists(EntityIdCodec.encode(3, 0, 0)))
        assertEquals(2, targetRegistry.shortIdOf("local.mod"))
    }

    @Test
    fun `孤儿声明随后续存档继承且重激活漂移在写入前拒绝`() {
        val (sourceRegistry, sourceKernel) = populatedSource()
        val bundle = SaveService(sourceKernel, EmptyProjector, sourceRegistry).captureBundle(
            SidecarBinding(1, 2, 3),
            activeEntityRefs = declarations(),
        )
        val targetRegistry = targetRegistry()
        val targetKernel = kernel()
        val targetService = SaveService(targetKernel, EmptyProjector, targetRegistry)
        (targetService.prepareSlotRestore(bundle, bundle.binding, crossDevice = true)
            as SaveService.SlotRestorePreparation.Ready).commit()

        val inherited = targetService.captureBundle(
            SidecarBinding(1, 2, 4),
            activeEntityRefs = emptyMap(),
        )
        val expected = declarations().flatMap { (packageId, scopes) ->
            scopes.map { (key, scope) -> DeclaredEntityRef(packageId, key, scope) }
        }.toSet()
        assertEquals(expected, inherited.entityRefDeclarations.toSet())

        val changed = declarations().toMutableMap()
        changed["story.mod"] = changed.getValue("story.mod") + ("NEWREF" to EntityRefScope.NORMAL)
        val registryBefore = targetRegistry.encode().toList()
        val graphBefore = EntityPayloadCodec.decode(inherited.entityGraphPayload)
        assertFailsWith<IllegalArgumentException> {
            targetService.prepareSlotRestore(inherited, inherited.binding, changed, crossDevice = true)
        }
        assertEquals(registryBefore, targetRegistry.encode().toList(), "声明漂移拒绝不得改注册表")
        assertContentEquals(
            EntityPayloadCodec.encode(graphBefore),
            EntityPayloadCodec.encode(EntityPayloadCodec.decode(
                targetService.captureBundle(SidecarBinding(1, 2, 5)).entityGraphPayload,
            )),
            "声明漂移拒绝不得改实体图",
        )
    }

    @Test
    fun `CSV注入整数只对显式EntityRef声明重映射`() {
        fun csvStore(): Pair<VariableStore, CharacterTable.LongCol> {
            val sizes = VariableSizes().apply { charaInt1D[9] = 600 }
            val store = VariableStore(registry = VariableRegistry(sizes = sizes))
            store.registerTemplate(CharacterTemplate(no = 1L, name = "c", callname = "c", csvNo = 101L))
            store.addCharacter(1L)
            return store to store.characters.int1[store.registry.def("CFLAG")!!.index]
        }
        val plan = ModCsvInjectionPlan.of(
            baseCapacities = mapOf("CFLAG" to 4),
            declared = mapOf(
                "CFLAG" to mapOf("KOJO_A" to CsvKeywordBinding("KOJO_A", 500, "story.mod", alias = false)),
            ),
            tableToVar = mapOf("CFLAG" to "CFLAG"),
        )
        val bag = ModCsvValueBag.of(
            listOf(
                ModCsvValueKey("story.mod", "CFLAG", "KOJO_A", CharIdentity.CsvNo(101)) to
                    CsvValue.IntValue(EntityIdCodec.encode(2, 0, 0)),
            ),
        )
        val remap = EntityIdRemapTable.of(mapOf(2 to 3))
        val refs = mapOf("story.mod" to mapOf("CFLAG" to EntityRefScope.CHARA))

        val (declaredStore, declaredCol) = csvStore()
        val prepared = ModCsvValueRestorer.prepare(declaredStore, plan, { _, _ -> 500 }, bag, { error(it) }, refs, remap)
        assertEquals(0L, declaredCol.get(0, 500), "prepare 阶段零写入")
        prepared.commit()
        assertEquals(EntityIdCodec.encode(3, 0, 0), declaredCol.get(0, 500))

        val (plainStore, plainCol) = csvStore()
        ModCsvValueRestorer.prepare(plainStore, plan, { _, _ -> 500 }, bag, { error(it) }).commit()
        assertEquals(EntityIdCodec.encode(2, 0, 0), plainCol.get(0, 500), "未声明整数不猜值")

        val hostilePlans = listOf(
            ModCsvInjectionPlan.of(
                baseCapacities = mapOf("CFLAG" to 4),
                declared = mapOf(
                    "CFLAG" to mapOf("KOJO_A" to CsvKeywordBinding("KOJO_A", 500, "other.mod", alias = false)),
                ),
                tableToVar = mapOf("CFLAG" to "CFLAG"),
            ) to 500,
            ModCsvInjectionPlan.of(
                baseCapacities = mapOf("CFLAG" to 4),
                declared = mapOf(
                    "CFLAG" to mapOf("KOJO_A" to CsvKeywordBinding("KOJO_A", 1, "story.mod", alias = false)),
                ),
                tableToVar = mapOf("CFLAG" to "CFLAG"),
            ) to 1,
            ModCsvInjectionPlan.of(
                baseCapacities = mapOf("CFLAG" to 4),
                declared = mapOf(
                    "CFLAG" to mapOf("KOJO_A" to CsvKeywordBinding("KOJO_A", 500, "story.mod", alias = true)),
                ),
                tableToVar = mapOf("CFLAG" to "CFLAG"),
            ) to 500,
        )
        for ((hostilePlan, index) in hostilePlans) {
            val (hostileStore, hostileCol) = csvStore()
            val warnings = mutableListOf<String>()
            ModCsvValueRestorer.prepare(
                hostileStore,
                hostilePlan,
                { _, _ -> index },
                bag,
                warnings::add,
                refs,
                remap,
            ).commit()
            assertEquals(0L, hostileCol.get(0, index), "失主/Base/alias 格不得接收存档值")
            assertTrue(warnings.any { "拥有" in it }, warnings.toString())
        }
    }

    @Test
    fun `同会话真实sav读档替换已有实体而非拒绝侧车`() {
        val registry = sourceRegistry()
        val entityKernel = kernel()
        val saved = entityKernel.create(2, "NODE")
        val service = SaveService(entityKernel, EmptyProjector, registry)
        val port = CrossDevicePort(service, emptyMap())
        val store = variableStore()
        SidecarSaveWiring.install(store, SidecarSaveWiring(port, temp))
        val fs = FileSystemOps(
            store,
            RootedSaveFileIo(temp),
            SaveConfig(systemSaveInBinary = true, zipSaveData = true),
        )
        assertTrue(fs.saveData(0, "same-session"))
        val stale = entityKernel.create(2, "NODE")
        registry.allocate("later.mod")

        fs.loadData(0)

        assertTrue(port.warnings.isEmpty(), port.warnings.toString())
        assertTrue(entityKernel.exists(saved))
        assertFalse(entityKernel.exists(stale), "旧槽实体必须在 sidecar 提交时替换")
        assertEquals(1, entityKernel.liveCount)
    }

    @Test
    fun `真实sav配对仅显式跨设备入口应用重映射且原档字节不变`() {
        val sourceRegistry = sourceRegistry()
        val sourceKernel = kernel()
        val sourceStore = variableStore()
        val oldId = sourceKernel.create(2, "NODE")
        seedReferences(sourceStore, oldId)
        val sourceService = SaveService(sourceKernel, EmptyProjector, sourceRegistry)
        val port = CrossDevicePort(sourceService, declarations())
        SidecarSaveWiring.install(sourceStore, SidecarSaveWiring(port, temp))
        val sourceFs = FileSystemOps(sourceStore, RootedSaveFileIo(temp), SaveConfig(systemSaveInBinary = true, zipSaveData = true))
        assertTrue(sourceFs.saveData(0, "story"))
        val savPath = temp.resolve("save00.sav")
        val rt2Path = temp.resolve("save00.sav.rt2")
        val savBytes = Files.readAllBytes(savPath)
        val rt2Bytes = Files.readAllBytes(rt2Path)

        val targetRegistry = targetRegistry()
        val targetKernel = kernel()
        val targetStore = variableStore()
        val targetService = SaveService(targetKernel, EmptyProjector, targetRegistry, variableStore = targetStore)
        val targetPort = CrossDevicePort(targetService, declarations())
        SidecarSaveWiring.install(targetStore, SidecarSaveWiring(targetPort, temp))
        val targetFs = FileSystemOps(targetStore, RootedSaveFileIo(temp), SaveConfig(systemSaveInBinary = true, zipSaveData = true))

        targetFs.loadData(0)
        assertTrue(targetPort.warnings.any { it.contains("短 ID 2 分裂") }, "同设备冲突必须 fail-closed")
        assertFalse(targetKernel.exists(oldId))
        assertEquals(2, targetRegistry.shortIdOf("local.mod"))

        targetFs.loadDataCrossDevice(0)
        val newId = EntityIdCodec.encode(3, 0, 0)
        assertTrue(targetKernel.exists(newId))
        assertEquals(newId, targetStore.readInt("NREF", longArrayOf(0)))
        assertTrue(targetPort.crossDeviceCalls == 1)
        assertContentEquals(savBytes, Files.readAllBytes(savPath))
        assertContentEquals(rt2Bytes, Files.readAllBytes(rt2Path))
    }

    private class CrossDevicePort(
        private val service: SaveService,
        private val refs: Map<String, Map<String, EntityRefScope>>,
    ) : CrossDeviceSidecarStoragePort {
        val warnings = mutableListOf<String>()
        var crossDeviceCalls = 0

        override fun captureChunks(csv: ModCsvValueBag): SidecarChunkGroups = SidecarChunkGroups(emptyList(), emptyList())

        override fun captureRt2Bytes(
            binding: SidecarBinding,
            activeChunks: List<PackageChunk>,
            orphanChunks: List<PackageChunk>,
        ): ByteArray = service.captureBundle(binding, activeChunks, orphanChunks, refs).encode()

        override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult =
            restore(bytes, binding, crossDevice = false)

        override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding, crossDevice: Boolean): SidecarRestoreResult =
            restore(bytes, binding, crossDevice)

        private fun restore(bytes: ByteArray, binding: SidecarBinding, crossDevice: Boolean): SidecarRestoreResult {
            if (crossDevice) crossDeviceCalls++
            val bundle = SidecarSaveBundle.decode(bytes)
            val prepared = service.prepareSlotRestore(bundle, binding, refs, crossDevice)
            if (prepared is SaveService.SlotRestorePreparation.BindingMismatch) return SidecarRestoreResult(false)
            (prepared as SaveService.SlotRestorePreparation.Ready).commit()
            return SidecarRestoreResult(true)
        }

        override fun warn(message: String) {
            warnings += message
        }
    }
}
