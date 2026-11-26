package com.eratw.port.modapi

/**
 * 声明 DSL：把 `ModManifest` 那 20 个具名参数的构造收成一个有结构的块（2026-11-16）。
 *
 * ## 为什么值得专门一层
 *
 * 旧写法每加一个字段/关系/任务都要在一坨具名参数里找位置，而且**默认值本身就是坑**：
 * `RelationshipDecl(autoInverse = true)` 忘了同时写 `inverseName` 就整包 freeze 拒绝，
 * `cascadeOnDestroy` 吃缺省 true 会把族谱在一次 destroy 里抹掉。DSL 把这些收进带意图的
 * 参数名（`inverse = ...` 自动置 `autoInverse`），并让组件字段走 [FieldKey]，把
 * 「同一个字段名手抄三遍」压到只剩生成器一处。
 *
 * ## 用法
 *
 * ```
 * val manifest = modDeclarations("eratw.family", version = "0.1") {
 *     component("family") {
 *         int(ChildKeys.age)
 *         str(ChildKeys.title)
 *         ref(ChildKeys.father)
 *     }
 *     archetype("family.child") { uses("family") }
 *     relation("family.parent", inverse = "family.lineage", cascadeOnDestroy = false)
 *     relation("family.lineage", cascadeOnDestroy = false)
 *     job("family.growth") { everyDays = 1 }
 *     charaRoutes(CharaVar.ABL, CharaVar.EXP, CharaVar.JUEL, CharaVar.CFLAG)
 * }
 * ```
 *
 * DSL 只是糖：产出仍是同一份 [ModManifest]，适配器照旧翻译、声明事务照旧 freeze。
 *
 * ## 按域拆分（2026-11-16 第二次拆分，治 TooManyFunctions）
 *
 * `ModManifestBuilder` 的 25 个成员函数按域摊到一条继承链上：[ModManifestBaseBuilder]
 * （累积状态 + 加载顺序 / 跨包 / 产物元信息，本文件）→ [ModManifestSchemaBuilder]
 * （组件与原型，`DeclDslSchema.kt`）→ [ModManifestRelationsBuilder]（关系 / 相位 / 任务，
 * `DeclDslRelations.kt`）→ [ModManifestDataBuilder]（变量 / CSV / 角色变量路由 / 导出宏，
 * `DeclDslData.kt`）→ 公开入口 `ModManifestBuilder`（hook，`DeclDslHooks.kt`）。detekt 的
 * TooManyFunctions 类级阈值只数类体内**直接声明**的函数，继承来的不算，故每段都能压到 11 以下。
 *
 * 为什么不用「同包 extension function 分散到多个文件」这种更轻的切法：`mod-family` /
 * `mod-memory` / `mod-livingworld` 的调用方只 import 了 `modDeclarations` 和键类型，**没有
 * `import com.eratw.port.modapi.*` 通配**；Kotlin 的同包 extension 只对同包源文件免 import，
 * 出了包就得逐个 import，而这些调用方文件不在本次可写范围内（硬约束：只动 mod-api）。继承链
 * 让 25 个函数仍是成员函数，调用形态一个字符都不用改，下游零 import。
 */

/**
 * 用 DSL 构造 [ModManifest]。
 *
 * [minApi] = 本 mod 要求的最低引擎作者面版本（`ModApi.VERSION` 口径），与 `pack-mod --min-api`
 * 的元数据语义对齐；缺省 0 = 不设闸（产物 byte-for-byte 不变，仍写既有 manifest 线格式版本）。
 * 只有正数才把 manifest 升到 `/6` 并写 `min-api` 段，装配期由 `ModuleDescriptor.minApi` 整链拒载
 * 低于该版本的引擎。负数在构造期就抛（装配期同样的 `require(minApi >= 0)` 在打包/装载时才炸，
 * 提前到作者写的那一行）。
 */
fun modDeclarations(
    packageId: String,
    version: String = "",
    minApi: Int = 0,
    block: ModManifestBuilder.() -> Unit,
): ModManifest {
    require(minApi >= 0) { "minApi 不能为负：$minApi（0 = 不设闸）" }
    val builder = ModManifestBuilder(packageId, version)
    builder.minApi = minApi
    return builder.apply(block).build()
}

/**
 * 声明 DSL 的基段：全部累积容器 + 加载顺序 / 跨包 / 产物元信息，[build] 在这里统一收口。
 *
 * 子类只按域追加、不重复持有状态；容器是 `internal` 的，供同包各域段读写，不对模块外暴露。
 */
open class ModManifestBaseBuilder internal constructor(
    private val packageId: String,
    private val version: String,
) {
    // ---- 累积状态（各域段追加，build 统一收口）----
    internal val requires = mutableListOf<String>()
    internal val loadAfter = mutableListOf<String>()
    internal val entityRefs = mutableSetOf<String>()
    internal val globalDims = mutableListOf<GlobalDimDecl>()
    internal val csvKeywords = mutableListOf<CsvKeywordDecl>()
    internal val csvValues = mutableListOf<CsvValueDecl>()
    internal val components = mutableListOf<ComponentDecl>()
    internal val archetypes = mutableListOf<ArchetypeDecl>()
    internal val relationships = mutableListOf<RelationshipDecl>()
    internal val hooks = mutableListOf<HookDecl>()
    internal val interactionHooks = mutableListOf<InteractionHookDecl>()
    internal val phases = mutableListOf<PhaseDecl>()
    internal val jobs = mutableListOf<JobDecl>()
    internal val jobTemplates = mutableListOf<JobTemplateDecl>()
    internal val externalCalls = mutableListOf<String>()
    internal val overrideFunctions = mutableSetOf<String>()
    internal val privateDims = LinkedHashMap<String, MutableList<PrivateDimDecl>>()
    internal val exportedMacros = LinkedHashMap<String, String>()
    internal val charaRoutes = mutableListOf<CharaRouteDecl>()
    internal val settings = mutableListOf<SettingDecl>()
    internal val writeGrants = mutableListOf<WriteGrantDecl>()
    internal var artifactSha256: String = ""
    internal var displayName: String = ""
    internal var author: String = ""
    internal var description: String = ""

    /**
     * 本 mod 要求的最低引擎作者面版本（[modDeclarations] 的 `minApi` 实参落点）。
     *
     * 用可变字段而不是构造器参数：继承链上还有 Schema/Relations/Data/Hooks 四段构造器，加一个
     * 参数要同步改五个文件的签名（且都在本 lane 授权面之外）；DSL 入口在 `apply(block)` 之前
     * 赋值，语义等价、调用点零改动。
     */
    internal var minApi: Int = 0

    // ---- 加载顺序 ----

    /** 依赖的包（必须先装载）。 */
    fun requires(vararg packageIds: String) {
        requires += packageIds
    }

    /** 同批装载时排在这些包之后（不断言依赖，只定序）。 */
    fun loadAfter(vararg packageIds: String) {
        loadAfter += packageIds
    }

    /** 声明「这组整型变量槽持久保存 EntityId」（跨设备重映射遍历面）。 */
    fun entityRefs(vararg variableKeys: String) {
        entityRefs += variableKeys
    }

    // ---- 跨包 ----

    /** 声明跨包导入的函数名（**顺序有语义**，列表序 = 导入哨兵下标序，不得排序）。 */
    fun externalCall(vararg functionKeys: String) {
        externalCalls += functionKeys
    }

    /** 声明允许覆盖的同名前实现。 */
    fun overrideFunction(vararg functionKeys: String) {
        overrideFunctions += functionKeys
    }

    /** 本包产物 `.erbm` 的 sha256（64 位小写十六进制）。 */
    fun artifactSha256(sha256: String) {
        artifactSha256 = sha256
    }

    // ---- 展示元数据（RT2MANIFEST/4）----

    /** 玩家可见的显示名（模组列表用；不写就回落 packageId）。 */
    fun displayName(name: String) {
        displayName = name
    }

    /** 作者 / 维护者（纯展示）。 */
    fun author(name: String) {
        author = name
    }

    /** 一句话说明（纯展示）。 */
    fun description(text: String) {
        description = text
    }

    internal fun build(): ModManifest = ModManifest(
        packageId = packageId,
        version = version,
        minApi = minApi,
        displayName = displayName,
        author = author,
        description = description,
        requires = requires.toList(),
        loadAfter = loadAfter.toList(),
        entityRefs = entityRefs.toSet(),
        globalDims = globalDims.toList(),
        csvKeywords = csvKeywords.toList(),
        csvValues = csvValues.toList(),
        components = components.toList(),
        archetypes = archetypes.toList(),
        relationships = relationships.toList(),
        hooks = hooks.toList(),
        interactionHooks = interactionHooks.toList(),
        phases = phases.toList(),
        jobs = jobs.toList(),
        jobTemplates = jobTemplates.toList(),
        externalCalls = externalCalls.toList(),
        overrideFunctions = overrideFunctions.toSet(),
        privateDims = privateDims.mapValues { it.value.toList() },
        exportedMacros = exportedMacros.toMap(),
        artifactSha256 = artifactSha256,
        charaRoutes = charaRoutes.toList(),
        settings = settings.toList(),
        writeGrants = writeGrants.toList(),
    )
}
