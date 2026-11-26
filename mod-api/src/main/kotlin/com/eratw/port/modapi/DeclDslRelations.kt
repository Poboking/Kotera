package com.eratw.port.modapi

/**
 * 声明 DSL 的关系 / 相位 / 任务段：`relation` / `phase` / `job` / `jobTemplate`。
 *
 * 继承链位置见 `DeclDsl.kt` 顶部「按域拆分」：只追加声明，校验在各 `*Decl` 的 `init` 与
 * core 的声明事务（例如 `autoInverse ⇒ inverseName 已声明` 是 freeze 硬闸）。
 */
open class ModManifestRelationsBuilder internal constructor(
    packageId: String,
    version: String,
) : ModManifestSchemaBuilder(packageId, version) {
    /**
     * 声明一个关系类型。
     *
     * [inverse] 非空即自动置 `autoInverse = true` 并回填 `inverseName` —— 这两件事必须
     * 同时发生，旧写法分两个参数纯属给人留坑。注意反向类型**自己也必须进声明**
     * （`DeclarationRegistry` freeze 对「autoInverse ⇒ inverseName 已声明」是硬闸）。
     */
    fun relation(
        name: String,
        inverse: String? = null,
        directed: Boolean = true,
        dedupeEdges: Boolean = true,
        cascadeOnDestroy: Boolean = true,
    ) {
        relationships += RelationshipDecl(
            name = name,
            directed = directed,
            dedupeEdges = dedupeEdges,
            cascadeOnDestroy = cascadeOnDestroy,
            autoInverse = inverse != null,
            inverseName = inverse,
        )
    }

    /** 声明一个关系类型（类型化键；[inverse] 语义同 String 重载）。 */
    fun relation(
        key: RelationKey,
        inverse: RelationKey? = null,
        directed: Boolean = true,
        dedupeEdges: Boolean = true,
        cascadeOnDestroy: Boolean = true,
    ) = relation(key.name, inverse?.name, directed, dedupeEdges, cascadeOnDestroy)

    /** 声明自定义相位（不得占用 [BuiltinPhase]）。 */
    fun phase(key: PhaseKey, priority: Int = 0) {
        phases += PhaseDecl(key.name, priority)
    }

    /** 声明周期任务槽。[everyDays] = null 即一次性；[phase] 须为固定相位或已声明相位。 */
    fun job(
        key: JobKey,
        phase: PhaseKey = BuiltinPhase.DAY_END,
        firstDay: Long = 0L,
        everyDays: Long? = null,
        priority: Int = 0,
    ) {
        jobs += JobDecl(key.name, phase.name, firstDay, everyDays, priority)
    }

    /** 声明动态任务模板（[JobApi.scheduleOnce] 的落点）。 */
    fun jobTemplate(
        key: JobTemplateKey,
        phase: PhaseKey = BuiltinPhase.DAY_END,
        priority: Int = 0,
        catchUp: JobCatchUp = JobCatchUp.MISS_ONCE,
        maxCatchUp: Long? = null,
    ) {
        jobTemplates += JobTemplateDecl(key.name, phase.name, priority, catchUp, maxCatchUp)
    }
}
