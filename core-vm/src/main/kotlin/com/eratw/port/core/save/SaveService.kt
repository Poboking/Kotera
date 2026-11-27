package com.eratw.port.core.save

import com.eratw.port.core.entity.EntityIdImportPlan
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.EntityPayloadCodec
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import com.eratw.port.core.patch.DeclaredEntityRef
import com.eratw.port.core.patch.EntityRefScope
import com.eratw.port.core.vars.EntityRefVariableChange
import com.eratw.port.core.vars.EntityRefVariablePlanner
import com.eratw.port.core.vars.ModCsvInjectionPlan
import com.eratw.port.core.vars.VariableStore

/**
 * 一个逻辑存档单元的**抓取/恢复编排核心**（ERA Runtime 2.0 §8；M4-1 的 SaveService 面）。
 *
 * `saveNN.sav` + `saveNN.sav.rt2` = 一个逻辑存档。本类只管 .rt2 侧的**顺序不变量**与
 * 配对完整性判定；.sav 侧沿用既有 [SaveCapture]/[SaveFileIo]，事务日志落盘/fsync/换名/
 * .bak 轮转/三语 RestoreReport 属 M1-5 脏活，恢复判定一律走 [SaveRecovery.plan]。
 *
 * ## 三条顺序不变量（本类存在的理由）
 *
 * 1. **先脱水后抓取**（§7 存档前同步）：抓 .rt2 前必须对**全部**投射实体调
 *    [ICharaSlotProjector.syncFromCharaSlot]，再 [EntityKernel.exportSnapshot]。
 *    顺序反了 = 快照滞后于活行数据，玩家读档后「孩子身上的改动没了」。枚举入口是
 *    [ICharaSlotProjector.projectedEntities]，不靠调用方自己记得有哪些实体。
 * 2. **prepare 全量预检后一次 commit**：恢复时先校验绑定、容器形态、声明、注册表导入计划、
 *    重映射后的实体快照、虚拟 NO 快照与变量槽，再由 [SlotRestorePreparation.Ready.commit]
 *    按变量槽 → 宿主复位回调 → 实体图 → 虚拟 NO → 注册表的顺序提交。显式跨设备模式按源包名
 *    字典序分配目标短 ID；同设备模式严格并集，冲突零写入。准备失败不得留下半恢复状态。
 * 3. **绑定校验先于任何写入**：三元组（boundUniqueCode + scriptVersion + saveTextHash）
 *    不符 ⇒ 不碰注册表、不碰内核，返回 [RestoreOutcome.BindingMismatch] 交宿主上三语警告
 *    （§8 兼容性矩阵：PC 玩过后传回安卓 = 警告「扩展状态可能滞后或丢失」，不是静默应用）。
 *
 * ## 孤儿片原样透传
 *
 * 未激活 Mod 的分片必须逐字节回写（§8 .rt2 容器 / §7 孤儿锁定），否则「禁用 school.mod
 * 读存再启用无损」这条 M4 Gate 直接破。本类把 active 与 orphan 两组合并，并**拒绝同一
 * packageId 同时出现在两组**——一个包要么激活（重新抓取）要么孤儿（透传），不能既新又旧；
 * 出现重复即宿主分类错了，当场报错而不是让后写入的分片静默盖掉前一个。
 *
 * ## 全局档不带实体
 *
 * [captureGlobalBundle] 的实体载荷恒空（§8：SAVEGLOBAL 不带实体载荷，实体按槽走）。
 * 分成两个入口而不是一个布尔开关，是为了让「实体永不进 global.rt2」在调用点显式可见。
 * 恢复侧同理分两个入口：槽档 [restoreBundle]（并注册表 + 灌实体）与 global
 * [restoreGlobalBundle]（交还分片并恢复 GLOBAL 声明引用槽）——global 束喂进槽恢复口
 * （或反之）都会当场拒绝。
 *
 * 本类无文件 IO、无可变全局；内核/注册表/投射器由宿主构造注入（AGENTS §2.4）。
 */
class SaveService(
    private val kernel: EntityKernel,
    private val projector: ICharaSlotProjector,
    private val pkgRegistry: PackageShortIdRegistry,
    /**
     * 虚拟 NO 注册表（§16 方案 D）。null = 无投射能力的档位：抓取写**空段**，
     * 恢复遇到非空段直接报错（fail-closed，不静默丢身份映射）。
     *
     * 虚拟 NO ↔ EntityId 是**每槽身份**：本类在抓取时写入 `.rt2`，恢复时先完成重映射
     * 快照与变量槽的全量预检，再在实体图之后提交（§16 第 6 点）；global.rt2 恒不带该段。
     */
    private val virtualNoRegistry: VirtualCharaNoRegistry? = null,
    private val variableStore: VariableStore? = null,
) {
    private var inheritedSlotDeclarations: Map<String, Map<String, EntityRefScope>> = emptyMap()
    private var inheritedGlobalDeclarations: Map<String, Map<String, EntityRefScope>> = emptyMap()

    /** CSV 截断后的真实角色发射长度由装配层注入；默认 null = 无注入截断。 */
    var entityRefCsvPlan: ModCsvInjectionPlan? = null

    /** 恢复结果。失配不是异常——宿主要据此上警告并继续以 Base 状态运行。 */
    sealed interface RestoreOutcome {
        /** 注册表已并、实体已灌。 */
        data object Applied : RestoreOutcome

        /** 三元组失配：未做任何写入，宿主上三语警告（扩展状态可能滞后或丢失）。 */
        data class BindingMismatch(val expected: SidecarBinding, val actual: SidecarBinding) : RestoreOutcome
    }

    /**
     * 抓取实体图载荷：**先对全部投射实体脱水**，再导出快照、再编码。
     * 顺序即不变量 1，不可重排。
     */
    fun captureEntityPayload(): ByteArray {
        for (id in projector.projectedEntities()) {
            projector.syncFromCharaSlot(id)
        }
        return EntityPayloadCodec.encode(kernel.exportSnapshot())
    }

    /**
     * 抓取一个槽的 .rt2 逻辑内容（实体图 + 注册表快照 + 分片）。
     *
     * @param activeChunks 本次激活 Package 的新分片（由 M1-4/M4-3 抽离面产出）
     * @param orphanChunks 未激活 Package 的旧分片（逐字节透传，本类不解释内容）
     * @throws IllegalArgumentException 同一 packageId 同时出现在两组，或任一组内重复
     */
    fun captureBundle(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk> = emptyList(),
        orphanChunks: List<PackageChunk> = emptyList(),
        activeEntityRefs: Map<String, Map<String, EntityRefScope>> = emptyMap(),
    ): SidecarSaveBundle = SidecarSaveBundle(
        binding = binding,
        registrySnapshot = pkgRegistry.encode(),
        entityGraphPayload = captureEntityPayload(),
        chunks = mergeChunks(activeChunks, orphanChunks),
        virtualNoSnapshot = virtualNoRegistry?.encode() ?: ByteArray(0),
        entityRefDeclarationPayload = encodeDeclarations(
            declarationsForSave(activeEntityRefs, inheritedSlotDeclarations),
        ),
    )

    /**
     * 抓取 global.rt2（跨槽共享，与 global.sav 配对）：**实体载荷恒空**，只带注册表快照
     * 与 Mod GLOBAL 变量分片。写触发 = SAVEGLOBAL（见 PersistenceDomains 路由）。
     */
    fun captureGlobalBundle(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk> = emptyList(),
        orphanChunks: List<PackageChunk> = emptyList(),
        activeEntityRefs: Map<String, Map<String, EntityRefScope>> = emptyMap(),
    ): SidecarSaveBundle = SidecarSaveBundle(
        binding = binding,
        registrySnapshot = pkgRegistry.encode(),
        entityGraphPayload = ByteArray(0),
        chunks = mergeChunks(activeChunks, orphanChunks),
        // 虚拟 NO 是每槽身份（§7 唯一性范围）：global.rt2 不带该段。
        virtualNoSnapshot = ByteArray(0),
        entityRefDeclarationPayload = encodeDeclarations(
            declarationsForSave(
                activeEntityRefs.mapValues { (_, scopes) -> scopes.filterValues { it == EntityRefScope.GLOBAL } },
                inheritedGlobalDeclarations,
            ),
        ),
    )

    /**
     * 恢复一个**槽** .rt2：绑定与全部恢复面预检后一次提交（顺序即不变量 2/3）。
     *
     * 只用于 saveNN.sav.rt2；global.rt2 的实体载荷恒空（[captureGlobalBundle]），
     * 误喂进来当场拒绝而不是让 codec 抛一句看不懂的「字节不足」——LOADGLOBAL 只读
     * Mod GLOBAL 分片与 GLOBAL 声明引用槽，实体永不跨槽。判据成立的前提：空内核经
     * [EntityPayloadCodec.encode] 也会写版本头与名表（≥8 字节），故「零长载荷」唯一地
     * 标识全局档。
     *
     * 全部面预检通过后，按变量槽 → 投影复位 → 实体图替换 → 虚拟 NO → 包注册表提交；
     * 因此同会话读档也不会保留上一槽实体或把旧身份并进新槽。
     *
     * @throws com.eratw.port.core.entity.RegistryConflictException 注册表并集冲突（内核未动）
     * @throws IllegalArgumentException 载荷损坏/快照声明未注册（内核预校验先于写入，半灌不可达）
     */
    fun restoreBundle(bundle: SidecarSaveBundle, expectedBinding: SidecarBinding): RestoreOutcome {
        val prepared = prepareSlotRestore(bundle, expectedBinding)
        if (prepared is SlotRestorePreparation.BindingMismatch) {
            return RestoreOutcome.BindingMismatch(prepared.expected, prepared.actual)
        }
        (prepared as SlotRestorePreparation.Ready).commit()
        return RestoreOutcome.Applied
    }

    /** 槽档 prepare/commit：所有容器、注册表、实体图、声明与变量槽校验先于任何写入。 */
    fun prepareSlotRestore(
        bundle: SidecarSaveBundle,
        expectedBinding: SidecarBinding,
        activeEntityRefs: Map<String, Map<String, EntityRefScope>> = emptyMap(),
        crossDevice: Boolean = false,
    ): SlotRestorePreparation {
        if (!bundle.matchesBinding(expectedBinding)) {
            return SlotRestorePreparation.BindingMismatch(expectedBinding, bundle.binding)
        }
        require(bundle.entityGraphPayload.isNotEmpty()) {
            "global.rt2 不带实体载荷，不能用 restoreBundle 恢复实体（LOADGLOBAL 只读 Mod GLOBAL 分片）"
        }
        val savedDeclarations = bundle.entityRefDeclarations.groupBy({ it.packageId }, { it.key to it.scope })
            .mapValues { (_, pairs) -> pairs.toMap() }
        reconcileActiveDeclarations(savedDeclarations, activeEntityRefs)
        val importPlan = EntityIdImportPlan.of(pkgRegistry.snapshot(), bundle.registrySnapshot, crossDevice)
        require(savedDeclarations.keys.all { it in importPlan.sourcePackageIds }) {
            "entity-ref 声明包不在快照注册表中：${savedDeclarations.keys - importPlan.sourcePackageIds}"
        }
        if (bundle.entityRefDeclarationPayload.isEmpty() && !importPlan.remapTable.isEmpty) {
            throw IllegalArgumentException("v2 sidecar 无 entity-ref 声明，无法证明跨设备变量槽重映射安全")
        }
        val snapshot = importPlan.remapped(EntityPayloadCodec.decode(bundle.entityGraphPayload))
        kernel.validateRestore(snapshot)
        val preparedVirtual = if (bundle.virtualNoSnapshot.isEmpty()) {
            virtualNoRegistry?.prepareEmptySnapshot()
        } else {
            val virtualRegistry = requireNotNull(virtualNoRegistry) {
                "sidecar 带虚拟 NO 段，但宿主未注入 VirtualCharaNoRegistry"
            }
            val incoming = VirtualCharaNoRegistry.decode(bundle.virtualNoSnapshot)
            virtualRegistry.prepareSnapshot(importPlan.remappedVirtualNos(incoming.snapshot()))
        }
        val declarations = savedDeclarations
            .filterKeys { it in activeEntityRefs }
            .flatMap { (pkg, scopes) ->
                scopes.entries.filter { it.value in SLOT_ENTITY_REF_SCOPES }
                    .map { (key, scope) -> DeclaredEntityRef(pkg, key, scope) }
            }
        val store = variableStore
        val changes = if (declarations.isEmpty()) {
            emptyList()
        } else {
            requireNotNull(store) { "sidecar 带 entity-ref 声明，但宿主未注入 VariableStore" }
            EntityRefVariablePlanner.plan(
                store,
                declarations,
                importPlan.remapTable,
                scopes = SLOT_ENTITY_REF_SCOPES,
                csvPlan = entityRefCsvPlan,
                strict = true,
            )
        }
        return SlotRestorePreparation.Ready(
            remapTable = importPlan.remapTable,
            entityRefDeclarations = savedDeclarations,
            commitAction = { commitBeforeRegistry ->
                if (store != null) EntityRefVariablePlanner.apply(store, changes, entityRefCsvPlan)
                commitBeforeRegistry()
                projector.onWorldReset()
                kernel.replaceRestore(snapshot)
                if (preparedVirtual != null) {
                    requireNotNull(virtualNoRegistry).adoptSnapshot(preparedVirtual)
                }
                pkgRegistry.replaceSnapshot(importPlan.registryBytes)
                inheritedSlotDeclarations = savedDeclarations
            },
        )
    }

    sealed interface SlotRestorePreparation {
        class Ready(
            val remapTable: com.eratw.port.core.entity.EntityIdRemapTable,
            val entityRefDeclarations: Map<String, Map<String, EntityRefScope>>,
            internal val commitAction: (() -> Unit) -> Unit,
        ) : SlotRestorePreparation {
            private var committed = false
            fun commit(beforeRegistry: () -> Unit = {}) {
                check(!committed) { "同一 restore prepare 不允许重复提交" }
                commitAction(beforeRegistry)
                committed = true
            }
        }

        data class BindingMismatch(val expected: SidecarBinding, val actual: SidecarBinding) : SlotRestorePreparation
    }

    /**
     * global.rt2 的恢复读数（LOADGLOBAL 侧车交还专用）。与槽档 [RestoreOutcome] 分开成
     * 第二个 sealed 而不是复用它，是因为「Applied」在两条路上**做过的写**不同：
     * 槽档 Applied = 注册表已并 + 实体已灌；global Applied = 分片交还宿主且 GLOBAL
     * 声明引用槽已恢复，注册表与内核一字未动。复用同名会让读数方把「没灌」误读成「灌了」。
     */
    sealed interface GlobalRestoreOutcome {
        /** 绑定吻合且容器是 global 形状：分片交还宿主，GLOBAL 声明引用槽已恢复。 */
        data class Applied(val chunks: List<PackageChunk>) : GlobalRestoreOutcome

        /** 三元组失配：未做任何交还，宿主上警告（扩展状态可能滞后或丢失）。 */
        data class BindingMismatch(val expected: SidecarBinding, val actual: SidecarBinding) : GlobalRestoreOutcome
    }

    /**
     * 恢复 global.rt2：**只验绑定与 global 形状，恢复 GLOBAL 声明引用槽并原样交出分片**
     * ——不并注册表、不灌内核、不碰虚拟 NO 注册表（LOADGLOBAL 只读 Mod GLOBAL 分片与
     * GLOBAL 声明引用槽，era-arch §8：实体按槽走、虚拟 NO 是每槽身份）。
     *
     * 为什么不走 [restoreBundle]：它对零长实体载荷当场拒绝（`SaveServiceTest` 的
     * 「全局档不可喂进槽恢复入口」钉住），而 global.rt2 的实体载荷恒空——两条路在
     * 「要不要灌内核」上是互斥的，不是一个布尔开关。
     *
     * 为什么不并注册表：global 束里的 [SidecarSaveBundle.registrySnapshot] 是 SAVEGLOBAL
     * 时刻的注册表视图，而 LOADGLOBAL 发生在**活会话**里——装载期并集与（若先做了
     * LOADGAME 的）槽档快照都已并入，此刻再把旧快照灌回去，最好情形是 no-op，最坏情形
     * 与活会话的新增包撞号（RegistryConflictException 会把 mod 分片交还整个打断）。
     * 全局注册表 bin 的装载面（era-arch §8「rt2_pkg_registry.bin」）是独立波次，不在本路。
     *
     * 反向形状闸：实体图 / 虚拟 NO 段非空 = 槽档容器被喂进了 global 恢复口，当场拒绝
     * 而不是静默只取分片（那会丢掉身份映射还自以为恢复成功）。
     */
    fun restoreGlobalBundle(bundle: SidecarSaveBundle, expectedBinding: SidecarBinding): GlobalRestoreOutcome {
        val prepared = prepareGlobalRestore(bundle, expectedBinding)
        if (prepared is GlobalRestorePreparation.BindingMismatch) {
            return GlobalRestoreOutcome.BindingMismatch(prepared.expected, prepared.actual)
        }
        val ready = prepared as GlobalRestorePreparation.Ready
        ready.commit()
        return GlobalRestoreOutcome.Applied(ready.chunks)
    }

    /** global prepare/commit：只允许 GLOBAL 声明槽，不读/不写 registry、kernel、虚拟 NO。 */
    fun prepareGlobalRestore(
        bundle: SidecarSaveBundle,
        expectedBinding: SidecarBinding,
        activeEntityRefs: Map<String, Map<String, EntityRefScope>> = emptyMap(),
    ): GlobalRestorePreparation {
        if (!bundle.matchesBinding(expectedBinding)) {
            return GlobalRestorePreparation.BindingMismatch(expectedBinding, bundle.binding)
        }
        require(bundle.entityGraphPayload.isEmpty()) {
            "global.rt2 不带实体载荷：实体图非空说明这是槽档容器，LOADGLOBAL 不灌内核（实体按槽走）"
        }
        require(bundle.virtualNoSnapshot.isEmpty()) {
            "global.rt2 恒不带虚拟 NO 注册表段：非空说明这是槽档容器，LOADGLOBAL 不并身份映射"
        }
        val declarations = bundle.entityRefDeclarations
        require(declarations.all { it.scope == EntityRefScope.GLOBAL }) {
            "global.rt2 只允许 GLOBAL entity-ref 声明槽"
        }
        val saved = declarations.groupBy({ it.packageId }, { it.key to it.scope })
            .mapValues { (_, pairs) -> pairs.toMap() }
        val activeGlobal = activeEntityRefs.mapValues { (_, scopes) ->
            scopes.filterValues { it == EntityRefScope.GLOBAL }
        }
        reconcileActiveDeclarations(saved, activeGlobal)
        val activeDeclarations = saved.filterKeys { it in activeGlobal }
            .flatMap { (pkg, scopes) -> scopes.map { (key, scope) -> DeclaredEntityRef(pkg, key, scope) } }
        val store = variableStore
        val changes = if (activeDeclarations.isEmpty()) {
            emptyList()
        } else {
            requireNotNull(store) { "global.rt2 带 entity-ref 声明，但宿主未注入 VariableStore" }
            EntityRefVariablePlanner.plan(
                store,
                activeDeclarations,
                com.eratw.port.core.entity.EntityIdRemapTable.IDENTITY,
                scopes = setOf(EntityRefScope.GLOBAL),
                csvPlan = entityRefCsvPlan,
                strict = true,
            )
        }
        return GlobalRestorePreparation.Ready(bundle.chunks, saved) {
            if (store != null) EntityRefVariablePlanner.apply(store, changes, entityRefCsvPlan)
            inheritedGlobalDeclarations = saved
        }
    }

    sealed interface GlobalRestorePreparation {
        class Ready(
            val chunks: List<PackageChunk>,
            val entityRefDeclarations: Map<String, Map<String, EntityRefScope>>,
            internal val commitAction: () -> Unit,
        ) : GlobalRestorePreparation {
            private var committed = false
            fun commit() {
                check(!committed) { "同一 global restore prepare 不允许重复提交" }
                commitAction()
                committed = true
            }
        }

        data class BindingMismatch(val expected: SidecarBinding, val actual: SidecarBinding) : GlobalRestorePreparation
    }

    /**
     * 只并注册表快照（孤儿片/全局 bin 的装载面用；不灌实体）。
     * 并集独立成立，故与 [restoreBundle] 分开暴露。
     */
    fun mergeRegistrySnapshot(bytes: ByteArray) {
        pkgRegistry.mergeSnapshot(bytes)
    }

    private fun declarationsForSave(
        active: Map<String, Map<String, EntityRefScope>>,
        inherited: Map<String, Map<String, EntityRefScope>>,
    ): Map<String, Map<String, EntityRefScope>> {
        if (active.isEmpty()) return inherited
        for ((packageId, refs) in active) {
            val old = inherited[packageId]
            if (old != null) require(old == refs) {
                "包 [$packageId] 重激活 entity-ref 声明不一致：旧=$old 当前=$refs"
            }
        }
        return inherited + active
    }

    private fun encodeDeclarations(declarations: Map<String, Map<String, EntityRefScope>>): ByteArray {
        if (declarations.isEmpty()) return ByteArray(0)
        val rows = declarations.flatMap { (packageId, scopes) ->
            scopes.map { (key, scope) -> DeclaredEntityRef(packageId, key, scope) }
        }
        return EntityRefDeclarationCodec.encode(rows)
    }

    private fun reconcileActiveDeclarations(
        saved: Map<String, Map<String, EntityRefScope>>,
        active: Map<String, Map<String, EntityRefScope>>,
    ) {
        if (active.isEmpty()) return
        for ((packageId, current) in active) {
            val old = saved[packageId]
            if (current.isNotEmpty() && old == null) {
                throw IllegalArgumentException("激活包 [$packageId] 缺少 entity-ref 声明，拒绝恢复")
            }
            if (old != null) require(old == current) {
                "激活包 [$packageId] entity-ref 声明不一致：存档=$old 当前=$current"
            }
        }
    }

    private fun mergeChunks(
        active: List<PackageChunk>,
        orphan: List<PackageChunk>,
    ): List<PackageChunk> {
        val activeIds = HashSet<String>(active.size * 2)
        for (chunk in active) {
            require(activeIds.add(chunk.packageId)) { "激活分片 packageId 重复: ${chunk.packageId}" }
        }
        val orphanIds = HashSet<String>(orphan.size * 2)
        for (chunk in orphan) {
            require(orphanIds.add(chunk.packageId)) { "孤儿分片 packageId 重复: ${chunk.packageId}" }
            require(chunk.packageId !in activeIds) {
                "packageId [${chunk.packageId}] 同时在激活组与孤儿组——一个包要么重新抓取要么原样透传，不能既新又旧"
            }
        }
        return active + orphan
    }

    private companion object {
        val SLOT_ENTITY_REF_SCOPES = setOf(EntityRefScope.NORMAL, EntityRefScope.CHARA)
    }
}