package com.eratw.port.modapi

/**
 * Kotlin mod 的声明 bundle（RT2 Kotlin Mod SDK K2，era-arch-v61u.md §18.6 / §5）。
 *
 * mod 作者用本文件类型声明实体原型/组件/关系类型/CSV 关键字与值补丁/全局变量。引擎侧适配器
 * （`com.eratw.port.core.mod.ModDeclarationAdapter`）把它翻译成 `ModuleDescriptor`，进
 * **同一个** `DeclarationRegistry` 五域事务（§5）：统一声明、冲突检测、冻结流程，不绕过得
 * 两个互不知情的扩展系统。双 mod 抢号 = 整包拒绝（无部分注册），复用 M1 判据。
 *
 * ## 为什么 mod 侧另有一套声明类型（不复用 core 类型）
 *
 * §18.2 铁边界：mod 作者只依赖 :mod-api，不依赖 :core-vm。故声明形态在 facade 侧独立定义
 * （干净语义），由适配器翻译进 core 权威类型（`ComponentSchema`/`EntityArchetype`/
 * `RelationshipSchema`/`CsvKeywordPatch`/`CsvValuePatch`/`DimSpec`）。完整校验（字段去重、
 * entityRef⇒整型、op/value 配对、autoInverse 反向边可解析、CSV 两阶段动态 ID）由 core 类型
 * 与声明事务在翻译/提交期承担；本侧只做最基础的非空校验，给 mod 作者早期干净报错。
 *
 * clean 语义（§18.7）：声明取干净语义，不复刻官方 bug；命名空间冲突口径与引擎一致
 * （标识符大写归一、CSV 关键字区分大小写，见引擎 `NamePolicy`）。
 */
data class ModManifest(
    val packageId: String,
    val version: String = "",
    val requires: List<String> = emptyList(),
    val loadAfter: List<String> = emptyList(),
    /**
     * 显式声明「持久保存 EntityId 的整型变量键」集合（§7 持久实体引用三面之一）。
     * 跨设备重映射时引擎只遍历声明过的引用槽；普通 Long 变量不被自动当实体引用（诚实边界）。
     */
    val entityRefs: Set<String> = emptySet(),
    val globalDims: List<GlobalDimDecl> = emptyList(),
    val csvKeywords: List<CsvKeywordDecl> = emptyList(),
    val csvValues: List<CsvValueDecl> = emptyList(),
    val components: List<ComponentDecl> = emptyList(),
    val archetypes: List<ArchetypeDecl> = emptyList(),
    val relationships: List<RelationshipDecl> = emptyList(),
    /** §20.1 hook 槽声明（目标函数键 + 相位 + priority + 修改权限）。 */
    val hooks: List<HookDecl> = emptyList(),
    /** §20.3 交互阶段钩子声明（五阶段之一 + priority）。 */
    val interactionHooks: List<InteractionHookDecl> = emptyList(),
    /** §20.2 自定义相位名声明（全局命名空间，不得占用引擎固定相位名）。 */
    val phases: List<PhaseDecl> = emptyList(),
    /** §20.2 定时/周期任务槽声明（jobId 包内唯一，相位须为固定或已声明相位）。 */
    val jobs: List<JobDecl> = emptyList(),
    /**
     * §21.1 动态任务模板声明（templateKey 包内唯一，与 [jobs] 的 jobId 不共享命名空间）。
     * 模板只声明补算策略；运行期实例经 [JobApi.scheduleOnce] 创建。
     */
    val jobTemplates: List<JobTemplateDecl> = emptyList(),
    /**
     * 跨包导入声明（`external-calls` 域）：本包 `_CALL` 引用的外部函数名。
     *
     * 列表序 = 链接期导入哨兵下标序（core `ExternalImportTable` 按该序把每个导入回填成
     * LSC 胜者的物理下标）⇒ **顺序有语义，不得排序**。同一名字忽略大小写重复 = 一个名字
     * 两个哨兵槽，链接期拒绝。
     */
    val externalCalls: List<String> = emptyList(),
    /**
     * 显式覆盖声明（`override-functions` 域）：允许覆盖同名前实现的函数键集合。
     *
     * MODERN 链接档据此判定「本次候选是否声明了覆盖」(`SymbolCandidate.declaredOverride`)；
     * 未声明的同名函数在 MODERN 档链接期拒绝（fail-closed，不猜作者意图）。键按大写归一后
     * 比较（`Config.StrComper` 的 IGNORE CASE 口径），声明时大小写不敏感。
     */
    val overrideFunctions: Set<String> = emptySet(),
    /**
     * 函数私有 `#DIM` 声明（`private-dims` 域）：函数键 → 该函数的局部变量表。
     *
     * 包私有命名空间（跨包同名合法）；同一函数内重名由声明事务拒绝。声明式私有变量
     * 每次调用重新初始化、不进存档（`SAVEDATA` 与 `REF` 是补丁编译期的语义，不从 Manifest 声明）。
     */
    val privateDims: Map<String, List<PrivateDimDecl>> = emptyMap(),
    /**
     * 导出的 `.ERH` `#DEFINE` 宏（`exported-macros` 域）：宏名 → 展开文本。
     *
     * 跨包依赖在编译期可见；同时进 `LinkFingerprint`（ABI-7 的链路冻结证据面）——
     * 宏改名/改值必须让指纹变化，否则「同一 ABI」的结论会被静默改坏。
     */
    val exportedMacros: Map<String, String> = emptyMap(),
    /**
     * 本包产物（`.erbm` 容器）的 sha256：64 位小写十六进制，空串 = 未提供。
     *
     * `LinkFingerprint`（ABI-7 链路冻结闸）的输入之一。格式在**翻译期**校验
     * （`ModDeclarationAdapter`），不吃到链接期才变成「指纹对不上」的静默失配。
     */
    val artifactSha256: String = "",
    /**
     * 角色变量路由声明（M5-4 刀 1，chara-field-routes 域）：本包要求随实体存活的角色
     * 变量族（RECON-RT2-M54 方案 B）。声明不携带 isStr 与镜像字段名 —— isStr 由引擎
     * freeze 期按系统族派生（用户变量 attach 期以 def 类型终审）、镜像字段 `族名:格号`
     * 由 freeze 合成。freeze 期拒载名单（RELATION / NO / CDFLAG / 0 维族）与同族重名
     * 由声明事务裁决（整包拒绝）。
     */
    val charaRoutes: List<CharaRouteDecl> = emptyList(),
) {
    init {
        require(packageId.isNotBlank()) { "packageId 不能为空" }
    }
}

/** 组件字段类型（映射引擎 `ComponentFieldType`）。 */
enum class FieldType { INT, STR }

/**
 * 组件字段声明。[isEntityRef] 只对整型字段合法（跨设备重映射按 schema 遍历该字段改写 EntityId）；
 * 翻译进 core `ComponentFieldSpec` 时由 core 校验「entityRef⇒整型」。
 */
data class FieldDecl(
    val name: String,
    val type: FieldType = FieldType.INT,
    val isEntityRef: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "组件字段名不能为空" }
    }
}

/** 组件 Schema 声明（component-schemas 域）。字段按名字注册，归一后不区分大小写。 */
data class ComponentDecl(
    val name: String,
    val fields: List<FieldDecl> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "组件名不能为空" }
    }
}

/** 实体原型声明（entity-archetypes 域）。[components] 引用组件 Schema 名（全局命名空间）。 */
data class ArchetypeDecl(
    val name: String,
    val components: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "原型名不能为空" }
    }
}

/**
 * 关系类型声明（relationship-schemas 域）。方向/去重/级联/自动反向边都是 mod 声明的业务含义（§7）。
 * 缺省 = 有向、去重、destroy 级联清边、不自动建反向边（最保守一组）。翻译进 core
 * `RelationshipSchema` 时由 core 校验 autoInverse⇒inverseName 等不变量。
 */
data class RelationshipDecl(
    val name: String,
    val directed: Boolean = true,
    val dedupeEdges: Boolean = true,
    val cascadeOnDestroy: Boolean = true,
    val autoInverse: Boolean = false,
    val inverseName: String? = null,
) {
    init {
        require(name.isNotBlank()) { "关系类型名不能为空" }
    }
}

/**
 * CSV 关键字注入声明（csv-patches 域的关键字形）。[index] = -1 请求自动分配（事务提交期尾部
 * 追加空闲位）；[isAlias] = true 是 .als 别名语义（只写反查表，不占命名位）。关键字区分大小写。
 */
data class CsvKeywordDecl(
    val table: String,
    val index: Int,
    val keyword: String,
    val isAlias: Boolean = false,
) {
    init {
        require(table.isNotBlank()) { "csv 表名不能为空" }
        require(keyword.isNotEmpty()) { "csv 关键字不能为空（$table）" }
    }
}

/** CSV 值补丁操作（映射引擎 `CsvPatchOp`）。 */
enum class CsvOp { ADD, REPLACE, REMOVE }

/**
 * CSV 值补丁声明（csv-patches 域的值操作形）。同格按链接序叠加、末位生效。行身份用字符串
 * （稳定身份键，不固化物理行号）。翻译进 core `CsvValuePatch` 时由 core 校验 op/value 配对。
 */
data class CsvValueDecl(
    val table: String,
    val row: String,
    val column: String,
    val op: CsvOp,
    val value: String? = null,
) {
    init {
        require(table.isNotBlank()) { "csv 值补丁表名不能为空" }
        require(row.isNotBlank()) { "csv 值补丁行身份不能为空（$table）" }
        require(column.isNotBlank()) { "csv 值补丁列关键字不能为空（$table/$row）" }
    }
}

/** 全局变量类型（映射引擎 DimSpec 的 isStr；K1 取 INT/STR 最小集）。 */
enum class DimType { INT, STR }

/**
 * 全局变量声明（global-dims 域）。mod GLOBAL 变量持久化进 `.rt2`（[isSaved]）。
 * [dims] 为空 = 标量。翻译进 core `DimSpec`（isGlobal=true）；命名空间冲突由声明事务裁决。
 */
data class GlobalDimDecl(
    val name: String,
    val type: DimType = DimType.INT,
    val dims: List<Int> = emptyList(),
    val isSaved: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "全局变量名不能为空" }
    }
}

/**
 * 函数私有 `#DIM` 声明（`private-dims` 域）。
 *
 * 只描述声明面（名字 / 类型 / 维长）：[dims] 空 = 标量。翻译进 core `DimSpec` 时
 * `isGlobal=false`、`isSavedata=false` —— 声明式私有变量是每调用重新初始化的局部量，
 * 不进存档；`REF` 与 `SAVEDATA` 是补丁编译期（从 ERB 原文解析）的语义，不从 Manifest 声明。
 */
data class PrivateDimDecl(
    val name: String,
    val type: DimType = DimType.INT,
    val dims: List<Int> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "私有变量名不能为空" }
    }
}

/**
 * 函数 hook 槽声明（§20.1，ABI-9）。[targetKey] 是函数键（大写归一）；
 * [priority] 升序 + 声明序 = 确定性执行序。[mayMutateArgs] / [mayMutateReturn] /
 * [runOnFailure] 逐 hook 显式声明，不留实现期解释。
 */
data class HookDecl(
    val targetKey: String,
    val phase: HookPhase,
    val priority: Int = 0,
    val mayMutateArgs: Boolean = false,
    val mayMutateReturn: Boolean = false,
    val runOnFailure: Boolean = false,
) {
    init {
        require(targetKey.isNotBlank()) { "hook 目标函数键不能为空" }
        require(priority >= 0) { "hook priority 不能为负：$priority" }
    }
}

/** 交互阶段钩子声明（§20.3，ABI-11）。 */
data class InteractionHookDecl(
    val phase: InteractionPhase,
    val priority: Int = 0,
) {
    init {
        require(priority >= 0) { "interaction hook priority 不能为负：$priority" }
    }
}

/** 自定义游戏相位声明（§20.2，ABI-10）。名字全局唯一，且不得占用 [BuiltinPhase]。 */
data class PhaseDecl(
    val name: String,
    val priority: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "相位名不能为空" }
        require(priority >= 0) { "相位 priority 不能为负：$priority" }
    }
}

/**
 * 定时/周期任务槽声明（§20.2，ABI-10）。[everyDays] 为 null = 一次性任务；
 * 否则每 [everyDays] 个游戏日触发一次，错过的周期跳过不补跑。[phase] 必须是
 * 引擎固定相位或已声明相位（声明事务校验）。
 */
data class JobDecl(
    val jobId: String,
    val phase: String = BuiltinPhase.DAY_END,
    val firstDay: Long = 0L,
    val everyDays: Long? = null,
    val priority: Int = 0,
) {
    init {
        require(jobId.isNotBlank()) { "jobId 不能为空" }
        require(phase.isNotBlank()) { "任务相位不能为空" }
        require(firstDay >= 0L) { "firstDay 不能为负：$firstDay" }
        require(everyDays == null || everyDays > 0L) { "everyDays 必须为正（null = 一次性）：$everyDays" }
        require(priority >= 0) { "任务 priority 不能为负：$priority" }
    }
}

/** 动态任务实例的补算策略（§21.1，ABI-10）：错过目标日后的处理方式。 */
enum class JobCatchUp {
    /** 目标日补触发一次（默认）：读档跨多日也只补一次。 */
    MISS_ONCE,

    /** 错过即作废：`atDay < now.day` 不触发，仅报告 skipped。 */
    MISS_SKIP,

    /** 逐错过日各触发一次，必须同时声明 [JobTemplateDecl.maxCatchUp] 上限。 */
    REPLAY,
}

/**
 * 动态任务模板声明（§21.1，ABI-10）。[templateKey] 包内唯一，且与 jobId 不共享命名空间；
 * 声明只定义默认相位 / priority / 补算策略，运行期实例由 `JobApi.scheduleOnce` 创建。
 *
 * 不变量：`REPLAY` 必须同时声明正数 [maxCatchUp]；非 `REPLAY` 必须不声明 [maxCatchUp]
 * （声明期拒绝，不留到运行期猜）。
 */
data class JobTemplateDecl(
    val templateKey: String,
    val phase: String = BuiltinPhase.DAY_END,
    val priority: Int = 0,
    val catchUp: JobCatchUp = JobCatchUp.MISS_ONCE,
    val maxCatchUp: Long? = null,
) {
    init {
        require(templateKey.isNotBlank()) { "模板键不能为空" }
        require(phase.isNotBlank()) { "模板相位不能为空" }
        require(priority >= 0) { "模板 priority 不能为负：$priority" }
        require((catchUp == JobCatchUp.REPLAY) == (maxCatchUp != null)) {
            if (catchUp == JobCatchUp.REPLAY) {
                "REPLAY 必须声明 maxCatchUp（正数上限）"
            } else {
                "非 REPLAY 不得声明 maxCatchUp：$maxCatchUp"
            }
        }
        require(maxCatchUp == null || maxCatchUp > 0L) { "maxCatchUp 必须为正：$maxCatchUp" }
    }
}

/**
 * 角色变量路由声明（M5-4 刀 1，chara-field-routes 域）：把一个角色变量族的格子声明为
 * 「随实体存活」（era-arch-v61u.md:1807：全量域清单由声明事务喂，宿主不得硬编码业务
 * schema）。族级两档粒度（RECON-RT2-M54 方案 B）：[cells] 空 = **全族全跨度**（引擎
 * freeze 期以出厂尺寸展开）；非空 = **显式格**（格级逐格派生）。
 *
 * 不写 isStr、不写镜像字段名：isStr 由引擎按系统族派生（用户变量 attach 期以 def 类型
 * 终审）；镜像字段 `族名:格号` 由 freeze 合成 —— Mod 声明量 O(族数) 不是 O(格数)。
 * 拒载名单（RELATION / NO / CDFLAG / 0 维族 NAME 等）与同族重名由声明事务裁决，
 * 未注册变量名 freeze 期不查存在性（attach 期终审）。
 */
data class CharaRouteDecl(
    val variable: String,
    val cells: List<Int> = emptyList(),
) {
    init {
        require(variable.isNotBlank()) { "路由变量族名不能为空" }
        require(cells.all { it >= 0 }) { "路由格下标必须非负：$cells（变量 $variable）" }
        require(cells.toSet().size == cells.size) { "路由格下标重复：$cells（变量 $variable）" }
    }
}