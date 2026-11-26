package com.eratw.port.modtesting

import com.eratw.port.modapi.BuiltinPhase
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl

/**
 * 内存版声明 schema：把若干份 [ModManifest] 摊成「字段表 + 原型表 + 关系表 + 任务/相位/钩子槽表」。
 *
 * 形状照 `DeclarationRegistry.freeze()` 的产物，但**只建台架需要的部分**（不做 CSV 补丁、
 * 变量槽、跨包导入这些与实体/关系单测无关的域）。
 *
 * 两条与生产一致的关键规则：
 * - 字段名 / 原型名 / 关系名全局按**大写**归一（`EntityKernel` 同口径）；
 * - 字段跨组件同名但类型冲突时 `require` 抛（生产内核在组合 schema 时同样拒绝）。
 */
internal class MemorySchema private constructor(manifests: List<ModManifest>) {

    private val fieldTable = LinkedHashMap<String, FieldType>()
    private val archetypeSet = LinkedHashSet<String>()
    private val relationTable = LinkedHashMap<String, RelationSpec>()
    private val inverseTable = LinkedHashMap<String, String>()
    private val jobSet = LinkedHashSet<String>()
    private val phaseSet = LinkedHashSet(BuiltinPhase.ALL)
    private val hookSet = LinkedHashSet<String>()
    private val hookSlotList = mutableListOf<HookSlotSpec>()
    private val interactionSlotList = mutableListOf<InteractionSlotSpec>()

    init {
        // 与生产 EntityKernel.kt:66-93 同口径：两趟注册，消除声明顺序依赖。
        // 第一趟只收全部显式声明；第二趟才补 autoInverse 派生反边，且 putIfAbsent
        // 不覆盖已存在的显式声明（生产同理：显式声明了同名 schema 时保留用户版）。
        // 若挤在同一趟里，主声明在前、显式反边在后时派生版会先占坑、吞掉用户版。
        val relations = manifests.flatMap { it.relationships }
        relations.forEach { declaration ->
            relationTable.putIfAbsent(normalize(declaration.name), specOf(declaration))
        }
        relations.forEach { declaration ->
            if (!declaration.autoInverse) return@forEach
            val key = normalize(declaration.name)
            val inverseName = requireNotNull(declaration.inverseName) {
                "关系 $key 声明了 autoInverse 但没有 inverseName"
            }
            val inverseKey = normalize(inverseName)
            relationTable.putIfAbsent(inverseKey, inverseSpecOf(declaration))
            inverseTable[key] = inverseKey
        }

        for (manifest in manifests) {
            manifest.components.forEach { component ->
                component.fields.forEach { field -> putField(field.name, field.type) }
            }
            manifest.archetypes.forEach { archetype -> archetypeSet += normalize(archetype.name) }
            manifest.jobs.forEach { job -> jobSet += normalize(job.jobId) }
            manifest.phases.forEach { phase -> phaseSet += normalize(phase.name) }
            manifest.hooks.forEach { hook ->
                hookSet += normalize(hook.targetKey)
                hookSlotList += HookSlotSpec(
                    packageId = manifest.packageId,
                    targetKey = normalize(hook.targetKey),
                    phase = hook.phase,
                    priority = hook.priority,
                    mayMutateArgs = hook.mayMutateArgs,
                    mayMutateReturn = hook.mayMutateReturn,
                    runOnFailure = hook.runOnFailure,
                    continueOnHandlerFailure = hook.continueOnHandlerFailure,
                    declarationOrder = hookSlotList.size,
                )
            }
            manifest.interactionHooks.forEach { interaction ->
                interactionSlotList += InteractionSlotSpec(
                    packageId = manifest.packageId,
                    phase = interaction.phase,
                    priority = interaction.priority,
                    declarationOrder = interactionSlotList.size,
                )
            }
        }
    }

    /** 字段名（大写）→ 列类型。 */
    val fieldTypes: Map<String, FieldType> get() = fieldTable

    /** 已注册原型名（大写）。 */
    val archetypes: Set<String> get() = archetypeSet

    /** 关系类型名（大写）→ 运行期语义。 */
    val relationships: Map<String, RelationSpec> get() = relationTable

    /** 声明了 autoInverse 的正向关系（大写）→ 它的反向类型（大写）。 */
    val inverseOf: Map<String, String> get() = inverseTable

    /** 静态任务槽 id（大写）。 */
    val jobs: Set<String> get() = jobSet

    /** 可订阅相位名（大写，含三个内置相位）。 */
    val phases: Set<String> get() = phaseSet

    /** 已声明函数钩子槽的目标函数键（大写）。 */
    val hooks: Set<String> get() = hookSet

    /**
     * 函数钩子槽全表（按 Manifest 声明序）：一个包一个 target+phase 一条，携带派发要用的
     * priority / mayMutateArgs / mayMutateReturn，形状照生产 `HookPipeline.HookSlot`。
     */
    val hookSlots: List<HookSlotSpec> get() = hookSlotList

    /** 交互阶段钩子槽全表（按 Manifest 声明序），形状照生产 `InteractionPipeline` 的声明表。 */
    val interactionSlots: List<InteractionSlotSpec> get() = interactionSlotList

    /**
     * 一条函数钩子槽声明（台架派发要消费的语义位）。
     *
     * [runOnFailure] / [continueOnHandlerFailure] 是两根独立的失败轴，与生产 `HookSlot` 同口径：
     * 前者管「被 hook 的函数抛错时本 hook 仍执行」，后者管「本 hook 的处理器抛错后本槽是否撤销」。
     */
    data class HookSlotSpec(
        val packageId: String,
        val targetKey: String,
        val phase: HookPhase,
        val priority: Int,
        val mayMutateArgs: Boolean,
        val mayMutateReturn: Boolean,
        val runOnFailure: Boolean,
        val continueOnHandlerFailure: Boolean,
        val declarationOrder: Int,
    )

    /** 一条交互阶段钩子槽声明（台架相位机要消费的语义位）。 */
    data class InteractionSlotSpec(
        val packageId: String,
        val phase: InteractionPhase,
        val priority: Int,
        val declarationOrder: Int,
    )

    /** 一条关系声明里台架要消费的三个语义位（与 `RelationshipDecl` 的缺省一致）。 */
    data class RelationSpec(
        val directed: Boolean,
        val dedupeEdges: Boolean,
        val cascadeOnDestroy: Boolean,
    )

    private fun putField(name: String, type: FieldType) {
        val key = normalize(name)
        val existing = fieldTable.putIfAbsent(key, type)
        require(existing == null || existing == type) {
            "字段 $key 的列类型声明冲突：$existing vs $type（与 EntityKernel 组合 schema 同口径拒绝）"
        }
    }

    private fun specOf(declaration: RelationshipDecl): RelationSpec = RelationSpec(
        directed = declaration.directed,
        dedupeEdges = declaration.dedupeEdges,
        cascadeOnDestroy = declaration.cascadeOnDestroy,
    )

    private fun inverseSpecOf(declaration: RelationshipDecl): RelationSpec = RelationSpec(
        directed = true,
        dedupeEdges = declaration.dedupeEdges,
        cascadeOnDestroy = declaration.cascadeOnDestroy,
    )

    companion object {

        /** 由任意份 manifest 并集建 schema。 */
        fun of(manifests: List<ModManifest>): MemorySchema = MemorySchema(manifests)
    }
}
