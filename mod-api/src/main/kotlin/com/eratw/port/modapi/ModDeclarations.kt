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
 *
 * ## 手写这份声明？不要
 *
 * 20 个具名参数的 data class 构造是本文件唯一被诟病过的地方（2026-11-16）。**用
 * [modDeclarations] 的 DSL 写**：
 *
 * ```
 * val manifest = modDeclarations("eratw.family", version = "0.1") {
 *     component("family") {
 *         int(ChildKeys.age)
 *         str(ChildKeys.title)
 *         ref(ChildKeys.father, inverse = ...)
 *     }
 *     archetype("family.child") { uses("family") }
 *     relation("family.parent", inverse = "family.lineage")
 *     job("family.growth") { everyDays = 1 }
 * }
 * ```
 *
 * data class 本身保留 public 构造器有两个理由：适配器与测试要能直接造单条声明做反向证明；
 * DSL 只是糖，不垄断真值。
 */
data class ModManifest(
    val packageId: String,
    val version: String = "",
    /**
     * 本 mod 要求的最低引擎作者面版本（`ModApi.VERSION` 口径）：0 = 不设闸（缺省）。
     *
     * 只有正数才进 manifest 线格式：编码器把 `/3`..`/5` 升到 `RT2MANIFEST/6` 并写 `min-api` 段，
     * 装配期 `ModuleDescriptor.minApi` 对低于该版本的引擎整链拒载。语义与路 B（导入 ERB 包）的
     * `pack-mod --min-api` 对齐；0 时产物字节与不支持该参数的引擎时期逐字相同。
     */
    val minApi: Int = 0,
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
     *
     * 校验口径：键非空在本类 [init] 拒（手写构造路径）；同包键唯一在 DSL 收集处
     * （[modDeclarations] 的 `exportedMacro`）拒 —— Map 语义使重复信息到不了本类，
     * 翻译期对宏域透传、生产代码零编译期消费者，唯一闸位就是这两处声明构造期。
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
    /**
     * 玩家可见的显示名（RT2MANIFEST/4 元数据域）。空串 = 未提供，宿主回落到 [packageId]。
     *
     * 三个元数据字段（[displayName] / [author] / [description]）**全空时不写进线格式**：
     * 编码器仍出 `/3`，既有资产字节不变；任一非空才升 `/4`（解码器两版都收）。
     * 语义上只是「给人看」，不参与链接、不参与版本裁决、不影响任何声明域。
     */
    val displayName: String = "",
    /** 作者/维护者（纯展示；空串 = 未提供）。 */
    val author: String = "",
    /** 一句话说明（纯展示；空串 = 未提供）。不得塞超长文本：它是 manifest 的一行 hex 串。 */
    val description: String = "",
    /**
     * 作者声明的设置项（RT2 per-mod 配置；声明顺序 = 设置页渲染顺序）。
     *
     * 每个键在本包内唯一、类型固定（[IntSettingDecl] / [StrSettingDecl] / [BoolSettingDecl] /
     * [EnumSettingDecl]），声明存在与否决定**线格式版本**：空清单仍写 `/3` 或 `/4`（既有资产
     * 字节不变），非空才升 `RT2MANIFEST/5`。设置值本身不进 manifest（那是设备侧按包配置文件的事）。
     */
    val settings: List<SettingDecl> = emptyList(),
    /**
     * 作者显式声明的逐条声明源位置（`RT2MANIFEST/7` 的 `declaration-positions` 可选段，
     * ModDX-3 DX3-C）：声明原文 → `文件:行号`。空表 = 不升版本、产物字节与 `/3`–`/6` 逐字相同。
     *
     * 值由作者填，引擎不解析、不猜、不从 Kotlin 反射造行号；仅用于声明冲突诊断时回显定位。
     * 写侧由 [com.eratw.port.modgen.ManifestFactory] 透传，读侧翻译进
     * `ModuleDescriptor.declarationPositions` 后由 `DeclarationRegistry` 的冲突消息消费。
     */
    val declarationPositions: Map<String, String> = emptyMap(),
    /**
     * 作者显式声明的**原版全局变量受控写授权**（`RT2MANIFEST/8` 的 `write-grants` 可选段，
     * ModDX4-B1）：键级白名单，fail-closed —— 未在 [writeGrants] 声明的原版键，
     * [GameStateApi.setIntVar] / [GameStateApi.setStrVar] 运行期当场抛
     * [IllegalArgumentException]（文案对齐 [SettingsApi] 的未声明键口径）。
     *
     * 两条边界（v1）：
     * - **本包 [globalDims] 声明的变量不需要授权**：自家声明的变量经同一写 API 直接写；
     * - **角色变量写不在本域**（charaRoutes 读已通；写面另立后续波）。
     *
     * 键在本包内唯一（原文判重；大小写归一后的冲突由引擎声明事务按其命名空间口径裁决）。
     * 空表 = 不升版本、产物字节与 `/3`–`/7` 逐字相同。声明期（装载/导入）校验键存在性与
     * 类型：坏 grant 整包拒，不静默降级。
     */
    val writeGrants: List<WriteGrantDecl> = emptyList(),
) {
    init {
        require(packageId.isNotBlank()) { "packageId 不能为空" }
        require(minApi >= 0) { "minApi 不能为负：$minApi（0 = 不设闸）" }
        require(settings.map { it.key }.toSet().size == settings.size) {
            "设置项键在本包内必须唯一：${settings.map { it.key }}"
        }
        require(writeGrants.map { it.name }.toSet().size == writeGrants.size) {
            "writeGrants 键在本包内必须唯一：${writeGrants.map { it.name }}"
        }
        // 手写构造路径的空白宏键闸：DSL 层（exportedMacro）已拒，但 Map 收集后重复信息消失、
        // 翻译期（ModDeclarationAdapter）对宏域透传无校验、生产代码零编译期消费者 ——
        // 不在此拒 = 空名宏静默进线格式与 LinkFingerprint。
        require(exportedMacros.keys.all { it.isNotBlank() }) {
            "导出宏名不能为空：${exportedMacros.keys.filter { it.isBlank() }}"
        }
    }
}

/**
 * 原版全局变量受控写授权声明（`write-grants` 域，ModDX4-B1）。
 *
 * [name] = 原版全局变量键（引擎按大写归一寻址，声明时大小写不敏感）；[type] 必须与该键
 * 在引擎里的实际类型一致（INT/STR），类型不符 = 坏 grant，声明期整包拒。声明的是**单键
 * 标量/首格**授权：0 维标量与 1 维非角色数组（裸引用同形的 0 号元素）可写；角色变量与
 * 2 维以上不可按名寻址，声明期即拒，不留到运行期猜。
 */
data class WriteGrantDecl(
    val name: String,
    val type: DimType = DimType.INT,
) {
    init {
        require(name.isNotBlank()) { "writeGrant 键不能为空" }
    }
}

/** 组件字段类型（映射引擎 `ComponentFieldType`）。 */
enum class FieldType { INT, STR }

/**
 * 组件字段声明。[isEntityRef] 只对整型字段合法（跨设备重映射按 schema 遍历该字段改写 EntityId）；
 * 翻译进 core `ComponentFieldSpec` 时由 core 校验「entityRef⇒整型」。
 *
 * [name] 用 [FieldKey] 而不是裸 String：字段键是**手抄三遍**的元凶（声明一份、常量一份、
 * 访问器一份），任一份打错都静默。类型化键 + 生成的 View（`:tools:modgen-accessors`）
 * 把字符串收敛成唯一一份真源。
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

/**
 * 从整型字段键造 [FieldDecl]。
 *
 * 为什么不给 [FieldDecl] 加 `FieldKey` 重载构造器：`FieldKey<Long>` 与 `FieldKey<String>`
 * 擦除后 JVM 描述符完全相同，两个单参重载会撞平台声明。工厂函数换名即可，且调用点
 * （DSL 的 `int(..)` / 生成器的 `FooKeys`）本来就不必手写这个类型名。
 */
fun intField(key: FieldKey<Long>): FieldDecl = FieldDecl(key.name, FieldType.INT, false)

/** 从字符串字段键造 [FieldDecl]。 */
fun strField(key: FieldKey<String>): FieldDecl = FieldDecl(key.name, FieldType.STR, false)

/** 从实体引用字段键造 [FieldDecl]（整型列，跨设备重映射按 schema 改写）。 */
fun refField(key: FieldKey<EntityId>): FieldDecl = FieldDecl(key.name, FieldType.INT, true)

/** 组件 Schema 声明（component-schemas 域）。字段按名字注册，归一后不区分大小写。 */
data class ComponentDecl(
    val name: String,
    val fields: List<FieldDecl> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "组件名不能为空" }
    }

    constructor(key: ComponentKey, fields: List<FieldDecl>) : this(key.name, fields)
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

    constructor(key: RelationKey) : this(key.name)
}

/**
 * CSV 关键字注入声明（csv-patches 域的关键字形）。[index] = -1 请求自动分配（事务提交期尾部
 * 追加空闲位），其余取值须为非负下标（-1 以外的负数构造期即拒，与 core `CsvKeywordPatch`
 * 同判据）；[isAlias] = true 是 .als 别名语义（只写反查表，不占命名位）。关键字区分大小写。
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
        require(index >= -1) { "csv 关键字 index 只能是 -1（自动分配）或非负下标：$index（$table）" }
    }

    constructor(table: CsvTable, index: Int, keyword: String, isAlias: Boolean = false) :
        this(table.name, index, keyword, isAlias)
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

    constructor(table: CsvTable, row: String, column: String, op: CsvOp, value: String? = null) :
        this(table.name, row, column, op, value)
}

/** 全局变量类型（映射引擎 DimSpec 的 isStr；K1 取 INT/STR 最小集）。 */
enum class DimType { INT, STR }

/**
 * 全局变量声明（global-dims 域）。mod GLOBAL 变量持久化进 `.rt2`（[isSaved]）。
 * [dims] 为空 = 标量；各维长度必须为正（负/零在构造期即拒 —— 与引擎分配守卫
 * `dimProduct` 的负维拒绝同判据，且 manifest global dims 当前零生产消费者，
 * 不在构造期拒 = 坏声明静默进冻结快照）。翻译进 core `DimSpec`（isGlobal=true）；
 * 命名空间冲突由声明事务裁决。
 */
data class GlobalDimDecl(
    val name: String,
    val type: DimType = DimType.INT,
    val dims: List<Int> = emptyList(),
    val isSaved: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "全局变量名不能为空" }
        require(dims.all { it > 0 }) { "全局变量维长必须为正（0 = 标量请在 dims 留空）：$dims（变量 $name）" }
    }

    constructor(key: GlobalKey) : this(key.name)
}

/**
 * 函数私有 `#DIM` 声明（`private-dims` 域）。
 *
 * 只描述声明面（名字 / 类型 / 维长）：[dims] 空 = 标量；各维长度必须为正（负/零在构造期
 * 即拒 —— 装配期 `declareUserVar` 的分配守卫同判据，早拒把失败从「进失败清单、运行到使用点
 * 才暴露」提前到声明构造期）。翻译进 core `DimSpec` 时
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
        require(dims.all { it > 0 }) { "私有变量维长必须为正（0 = 标量请在 dims 留空）：$dims（变量 $name）" }
    }
}

/**
 * 函数 hook 槽声明（§20.1，ABI-9）。[targetKey] 是函数键（大写归一）；
 * [priority] 升序 + 声明序 = 确定性执行序。[mayMutateArgs] / [mayMutateReturn] /
 * [runOnFailure] / [continueOnHandlerFailure] 逐 hook 显式声明，不留实现期解释。
 *
 * 两个失败轴互不相同：[runOnFailure] 管「被 hook 的函数抛错时本 hook 是否仍执行」；
 * [continueOnHandlerFailure] 管「本 hook 自己的处理器抛错后本槽是否继续存在」。
 */
data class HookDecl(
    val targetKey: String,
    val phase: HookPhase,
    val priority: Int = 0,
    val mayMutateArgs: Boolean = false,
    val mayMutateReturn: Boolean = false,
    val runOnFailure: Boolean = false,
    val continueOnHandlerFailure: Boolean = true,
) {
    init {
        require(targetKey.isNotBlank()) { "hook 目标函数键不能为空" }
        require(priority >= 0) { "hook priority 不能为负：$priority" }
    }

    constructor(
        target: FunctionKey,
        phase: HookPhase,
        priority: Int = 0,
        mayMutateArgs: Boolean = false,
        mayMutateReturn: Boolean = false,
        runOnFailure: Boolean = false,
        continueOnHandlerFailure: Boolean = true,
    ) : this(
        target.name,
        phase,
        priority,
        mayMutateArgs,
        mayMutateReturn,
        runOnFailure,
        continueOnHandlerFailure,
    )
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

    constructor(phase: PhaseKey, priority: Int = 0) : this(phase.name, priority)
}

/**
 * 定时/周期任务槽声明（§20.2，ABI-10）。[everyDays] 为 null = 一次性任务；
 * 否则每 [everyDays] 个游戏日触发一次，错过的周期跳过不补跑。[phase] 必须是
 * 引擎固定相位或已声明相位（声明事务校验）。
 */
data class JobDecl(
    val jobId: String,
    val phase: String = BuiltinPhase.DAY_END.name,
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

    constructor(
        job: JobKey,
        phase: PhaseKey = BuiltinPhase.DAY_END,
        firstDay: Long = 0L,
        everyDays: Long? = null,
        priority: Int = 0,
    ) : this(job.name, phase.name, firstDay, everyDays, priority)
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
    val phase: String = BuiltinPhase.DAY_END.name,
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

    constructor(
        key: JobTemplateKey,
        phase: PhaseKey = BuiltinPhase.DAY_END,
        priority: Int = 0,
        catchUp: JobCatchUp = JobCatchUp.MISS_ONCE,
        maxCatchUp: Long? = null,
    ) : this(key.name, phase.name, priority, catchUp, maxCatchUp)
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
 *
 * [variable] 取 [CharaVar] 而不是裸 String：这是本域唯一真正会静默出错的输入面 ——
 * 族名拼错既编译得过，也躲得过 freeze 的拒载闸（存在性在 attach 期才查），表现只是
 * 「这族格子没随实体走」，一路静默到玩家发现数据丢。
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

    constructor(variable: CharaVar, cells: List<Int> = emptyList()) : this(variable.name, cells)
}
