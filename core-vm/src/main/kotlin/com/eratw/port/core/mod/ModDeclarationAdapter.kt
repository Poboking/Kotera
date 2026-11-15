package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.patch.BuiltinPhases
import com.eratw.port.core.patch.CharaRouteDeclaration
import com.eratw.port.core.patch.CsvKeywordPatch
import com.eratw.port.core.patch.CsvPatchOp
import com.eratw.port.core.patch.CsvValuePatch
import com.eratw.port.core.patch.HookDeclaration
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.core.patch.InteractionHookDeclaration
import com.eratw.port.core.patch.InteractionPhase
import com.eratw.port.core.patch.JobCatchUp
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.JobTemplateDeclaration
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.PhaseDeclaration
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.CharaRouteDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.CsvKeywordDecl
import com.eratw.port.modapi.CsvOp
import com.eratw.port.modapi.CsvValueDecl
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.GlobalDimDecl
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.JobCatchUp as ModJobCatchUp
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseDecl
import com.eratw.port.modapi.PrivateDimDecl
import com.eratw.port.modapi.RelationshipDecl
import com.eratw.port.modapi.HookPhase as ModHookPhase
import com.eratw.port.modapi.InteractionPhase as ModInteractionPhase

/**
 * Kotlin mod 声明 → 引擎五域声明事务的适配器（K2，era-arch-v61u.md §18.6/§18.8/§5）。
 *
 * 把 mod 侧 [ModManifest]（:mod-api 干净声明形态）翻译成引擎权威 [ModuleDescriptor]，喂进
 * **同一个** `DeclarationRegistry`（五域：global-dims / csv-patches / component-schemas /
 * entity-archetypes / relationship-schemas）。mod 声明不绕过 DeclarationRegistry 另搞一套——
 * 统一声明、冲突检测、冻结流程（§5）；双 mod 抢号 = 整包拒绝（无部分注册），复用 M1 判据。
 *
 * ## 翻译即校验边界
 *
 * mod 侧只做最基础非空校验；完整不变量（字段去重、entityRef⇒整型、op/value 配对、autoInverse
 * 反向边可解析、CSV 两阶段动态 ID、命名空间冲突）由 core 类型构造期与 [DeclarationRegistry.freeze]
 * 承担。本适配器逐域直译，不另造校验逻辑（避免两套真相）。
 *
 * ## 宿主接线边界
 *
 * 本对象只做翻译；[DeclarationRegistry] 的构造（builtinVariableNames / csvTableCapacities /
 * baseCsvKeywords）与冻结时序由宿主掌握（K3 把本适配器接进 mod 装载生命周期）。用法：
 * ```
 * registry.addPackage(ModDeclarationAdapter.toDescriptor(manifest), source).freeze()
 * ```
 *
 * clean 语义（§18.7）：声明取干净语义；命名空间口径与引擎一致（标识符大写归一、CSV 关键字
 * 区分大小写，由引擎 NamePolicy 承担），不复刻官方 bug。
 */
object ModDeclarationAdapter {
    /** artifact-sha256 的十六进制长度（小写 64 位，见 [validateArtifactSha256]）。 */
    private const val ARTIFACT_SHA256_HEX_LENGTH = 64


    /** 把一个 mod 的声明 bundle 翻译成引擎 [ModuleDescriptor]（进 DeclarationRegistry 五域）。 */
    fun toDescriptor(manifest: ModManifest): ModuleDescriptor {
        validateJobTemplatePhases(manifest)
        validateArtifactSha256(manifest)
        return toDescriptorUnchecked(manifest)
    }

    /**
     * 动态任务模板的相位引用校验（§21.1，同 JobDeclaration 口径）：相位必须是引擎固定相位
     * 或本 Manifest 声明的相位；未声明 ⇒ 翻译期 fail-closed。
     *
     * 说明：完整声明事务的相位表跨包合流，故多包相位引用在 DeclarationRegistry 接入
     * jobTemplateDeclarations 后由其统一裁决（本波属组长接线）；本检查先在本包内拦住
     * 「明明没声明却引用」的写错。
     */
    private fun validateJobTemplatePhases(manifest: ModManifest) {
        val declared = manifest.phases.mapTo(HashSet()) { BuiltinPhases.canonical(it.name) }
        for (template in manifest.jobTemplates) {
            val key = BuiltinPhases.canonical(template.phase)
            require(key in BuiltinPhases.ALL || key in declared) {
                "任务模板引用了未声明的相位：${template.phase}" +
                    "（须为固定相位或本 Manifest 声明的相位）"
            }
        }
    }

    private fun toDescriptorUnchecked(manifest: ModManifest): ModuleDescriptor = ModuleDescriptor(
        packageId = manifest.packageId,
        version = manifest.version,
        requires = manifest.requires,
        loadAfter = manifest.loadAfter,
        entityRefs = manifest.entityRefs,
        globalDims = manifest.globalDims.map { toDimSpec(it) },
        csvPatches = manifest.csvKeywords.map { toCsvKeyword(it) },
        csvValuePatches = manifest.csvValues.map { toCsvValue(it) },
        componentSchemas = manifest.components.map { toComponentSchema(manifest.packageId, it) },
        entityArchetypes = manifest.archetypes.map { toArchetype(it) },
        relationshipSchemas = manifest.relationships.map { toRelationship(it) },
        hookDeclarations = manifest.hooks.map { toHookDeclaration(it) },
        interactionHookDeclarations = manifest.interactionHooks.map { toInteractionHookDeclaration(it) },
        phaseDeclarations = manifest.phases.map { toPhaseDeclaration(it) },
        jobDeclarations = manifest.jobs.map { toJobDeclaration(it) },
        jobTemplateDeclarations = manifest.jobTemplates.map { toJobTemplateDeclaration(it) },
        externalCalls = manifest.externalCalls,
        overrideFunctions = manifest.overrideFunctions,
        privateDims = manifest.privateDims.mapValues { (_, dims) -> dims.map { toPrivateDimSpec(it) } },
        exportedMacros = manifest.exportedMacros,
        artifactSha256 = manifest.artifactSha256,
        charaRoutes = manifest.charaRoutes.map { toCharaRouteDeclaration(it) },
    )

    /**
     * 产物 sha256 的格式闸（`artifact-sha256` 域）：空串 = 未提供；否则必须是小写 64 位十六进制。
     *
     * 在翻译期拒的理由：这个值只喂 [com.eratw.port.core.patch.LinkFingerprint]，大写或短串
     * 进了链接期只会变成「指纹对不上」的静默失配；在入口点名原因才符合 fail-closed。
     */
    private fun validateArtifactSha256(manifest: ModManifest) {
        val v = manifest.artifactSha256
        if (v.isEmpty()) return
        require(v.length == ARTIFACT_SHA256_HEX_LENGTH && v.all { it in '0'..'9' || it in 'a'..'f' }) {
            "artifact-sha256 必须是 64 位小写十六进制（当前 ${v.length} 字符：$v）"
        }
    }

    private fun toComponentSchema(packageId: String, decl: ComponentDecl): ComponentSchema =
        ComponentSchema(
            packageId = packageId,
            name = decl.name,
            fields = decl.fields.map {
                ComponentFieldSpec(
                    name = it.name,
                    type = when (it.type) {
                        FieldType.INT -> ComponentFieldType.INT
                        FieldType.STR -> ComponentFieldType.STR
                    },
                    isEntityRef = it.isEntityRef,
                )
            },
        )

    private fun toArchetype(decl: ArchetypeDecl): EntityArchetype =
        EntityArchetype(name = decl.name, components = decl.components)

    private fun toRelationship(decl: RelationshipDecl): RelationshipSchema =
        RelationshipSchema(
            name = decl.name,
            directed = decl.directed,
            dedupeEdges = decl.dedupeEdges,
            cascadeOnDestroy = decl.cascadeOnDestroy,
            autoInverse = decl.autoInverse,
            inverseName = decl.inverseName,
        )

    private fun toCsvKeyword(decl: CsvKeywordDecl): CsvKeywordPatch =
        CsvKeywordPatch(
            tableName = decl.table,
            index = decl.index,
            keyword = decl.keyword,
            isAlias = decl.isAlias,
        )

    private fun toCsvValue(decl: CsvValueDecl): CsvValuePatch =
        CsvValuePatch(
            table = decl.table,
            row = decl.row,
            column = decl.column,
            op = when (decl.op) {
                CsvOp.ADD -> CsvPatchOp.ADD
                CsvOp.REPLACE -> CsvPatchOp.REPLACE
                CsvOp.REMOVE -> CsvPatchOp.REMOVE
            },
            value = decl.value,
        )

    private fun toDimSpec(decl: GlobalDimDecl): DimSpec =
        DimSpec(
            name = decl.name,
            isStr = decl.type == DimType.STR,
            dims = decl.dims.toIntArray(),
            isGlobal = true,
            isSavedata = decl.isSaved,
        )

    /** 私有 `#DIM`：`isPrivate=true`（函数私有，与 ERB 内 `#DIM` 同口径）、`isGlobal=false`、`isSavedata=false`。 */
    private fun toPrivateDimSpec(decl: PrivateDimDecl): DimSpec =
        DimSpec(
            name = decl.name,
            isStr = decl.type == DimType.STR,
            dims = decl.dims.toIntArray(),
            isGlobal = false,
            isSavedata = false,
            isPrivate = true,
        )

    private fun toHookDeclaration(decl: HookDecl): HookDeclaration =
        HookDeclaration(
            targetKey = decl.targetKey,
            phase = when (decl.phase) {
                ModHookPhase.PREFIX -> HookPhase.PREFIX
                ModHookPhase.POSTFIX -> HookPhase.POSTFIX
            },
            priority = decl.priority,
            mayMutateArgs = decl.mayMutateArgs,
            mayMutateReturn = decl.mayMutateReturn,
            runOnFailure = decl.runOnFailure,
        )

    private fun toInteractionHookDeclaration(decl: InteractionHookDecl): InteractionHookDeclaration =
        InteractionHookDeclaration(
            phase = when (decl.phase) {
                ModInteractionPhase.PRECONDITION -> InteractionPhase.PRECONDITION
                ModInteractionPhase.PRE -> InteractionPhase.PRE
                ModInteractionPhase.MAIN -> InteractionPhase.MAIN
                ModInteractionPhase.POST -> InteractionPhase.POST
                ModInteractionPhase.SETTLEMENT -> InteractionPhase.SETTLEMENT
            },
            priority = decl.priority,
        )

    private fun toPhaseDeclaration(decl: PhaseDecl): PhaseDeclaration =
        PhaseDeclaration(name = decl.name, priority = decl.priority)

    private fun toJobDeclaration(decl: JobDecl): JobDeclaration =
        JobDeclaration(
            jobId = decl.jobId,
            phase = decl.phase,
            firstDay = decl.firstDay,
            everyDays = decl.everyDays,
            priority = decl.priority,
        )

    private fun toJobTemplateDeclaration(decl: JobTemplateDecl): JobTemplateDeclaration =
        JobTemplateDeclaration(
            templateKey = decl.templateKey,
            phase = decl.phase,
            priority = decl.priority,
            catchUp = when (decl.catchUp) {
                ModJobCatchUp.MISS_ONCE -> JobCatchUp.MISS_ONCE
                ModJobCatchUp.MISS_SKIP -> JobCatchUp.MISS_SKIP
                ModJobCatchUp.REPLAY -> JobCatchUp.REPLAY
            },
            maxCatchUp = decl.maxCatchUp,
        )

    /**
     * 角色变量路由直译（M5-4 刀 1）：变量族原文与格下标原样过桥，大小写归一、拒载名单、
     * 全跨度展开与镜像派生都归 [DeclarationRegistry.freeze]（chara-field-routes 域）。
     */
    private fun toCharaRouteDeclaration(decl: CharaRouteDecl): CharaRouteDeclaration =
        CharaRouteDeclaration(variable = decl.variable, cells = decl.cells)
}