package com.eratw.port.modmemory

import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationshipDecl

/**
 * 记忆 Mod 的**全部声明，只此一份真源**（M9-1 参考实现，era-arch-v61u.md §21.10 M9-1 / §21.2）。
 *
 * ## 模型（§21.2 参考：知识 ≈ fact 实体 + `owner --KNOWS--> fact` 有向边）
 *
 * 一条「知识」= 一个 fact 实体（携带这条事实的全部元数据）+ 一条从知道它的人指向它的
 * [RELATION_KNOWS] 有向边。**知识只经 KNOWS 边可达**：引擎不做任何自动传播，「A 亲历的事实
 * 不会自动成为 B 的知识」由 [MemoryModTest] 锁定——B 想知道，必须有人显式调
 * [MemoryMod.teach] 给 B 挂上自己的边。
 *
 * ## fact 字段的五类语义（判据 2）
 *
 * 字段键 = **字段名本身**（引擎的字段命名空间是全局一张表，组件名不参与寻址；口径与
 * `FamilyDeclarations` KDoc 相同）。五类齐备：
 *
 * 1. **实体引用三类**（`isEntityRef = true` 的整型字段，跨设备重映射按 schema 改写）：
 *    [FIELD_SUBJECT]（这条事实关于谁）、[FIELD_OBJECT]（事实的宾语/对象侧，可为 NULL）、
 *    [FIELD_SOURCE]（知识来源：讲述者；owner 自己亲历时 = owner 自身）。
 * 2. **文本两类**（STR）：[FIELD_PREDICATE]（谓词：这条事实断言什么，如「结识」「赠予」）、
 *    [FIELD_TAGS]（自由标签，多值用半角逗号分隔，约定不进引擎）。
 * 3. **游戏日两类**（INT）：[FIELD_OCCURRED_AT]（事实发生的游戏日）、
 *    [FIELD_ACQUIRED_AT]（owner 获知的游戏日）。
 * 4. **枚举四类**（INT，取值 = 下方三个枚举 + confidence 区间）：[FIELD_PROVENANCE] =
 *    [Provenance].code、[FIELD_VISIBILITY] = [Visibility].code、
 *    [FIELD_IMPORTANCE] = [Importance].code；[FIELD_CONFIDENCE] 是 0..100 的闭区间度量
 *    （0 = 全然不信，100 = 深信不疑），不是枚举——四类里它以「取值区间」承担同一职责。
 * 5. 读写一律走 [MemoryAccessors]（mod 业务代码里看不到 `.raw`，照 `EntityApi.getRef` KDoc）。
 *
 * ## 衰减语义（判据 5，Mod 规则，引擎零新增）
 *
 * [JOB_DECAY] 每游戏日（DAY_END，[JobDecl] 缺省相位）触发一次：对全部 fact 实体按来源降
 * [FIELD_CONFIDENCE]——HEARSAY 每日 -2、FIRSTHAND 每日 -1，下限 0（不复负、不删实体、不动边）。
 * 衰减只动 confidence 一个字段；遗忘是显式 [MemoryMod.forget]，衰减到 0 ≠ 遗忘。
 */
object MemoryDeclarations {

    const val PACKAGE_ID: String = "eratw.memory"

    /** 知识持有者原型（测试/演示用最小形状；owner 也可以是任何既有实体，原型不约束）。 */
    const val ARCHETYPE_PERSON: String = "memory.person"

    /** 知识（fact）原型：[COMPONENT_FACT] 是它唯一挂载的组件。 */
    const val ARCHETYPE_FACT: String = "memory.fact"

    /** 组件名：只用于原型挂载与声明分组，**不参与字段寻址**。 */
    const val COMPONENT_FACT: String = "memory.factData"

    // 五类字段键（全局命名空间，与 eratw.family 的 age/title/father 等互不重名）。
    const val FIELD_SUBJECT: String = "subject"
    const val FIELD_OBJECT: String = "object"
    const val FIELD_SOURCE: String = "source"
    const val FIELD_PREDICATE: String = "predicate"
    const val FIELD_TAGS: String = "tags"
    const val FIELD_OCCURRED_AT: String = "occurredAt"
    const val FIELD_ACQUIRED_AT: String = "acquiredAt"
    const val FIELD_PROVENANCE: String = "provenance"
    const val FIELD_CONFIDENCE: String = "confidence"
    const val FIELD_VISIBILITY: String = "visibility"
    const val FIELD_IMPORTANCE: String = "importance"

    /**
     * 知识边：`owner --KNOWS--> fact`，有向、无自动反向边。
     *
     * - **方向语义**：出边 = 「这条边的主语知道 fact」；从 fact 视角查 owner 用入边
     *   （`QueryApi` 的 `HasRelation(outgoing = false, other = owner)`）。
     * - **不设 autoInverse**：知识不是双向关系——A 知道 f 不蕴含 f「被 A 知道」之外任何方向
     *   需要独立边型；查询面用入边方向即可，多一条反向边只会让「知识可达」出现第二张账本。
     * - **cascadeOnDestroy 吃缺省 true**：fact 实体被 destroy（显式遗忘）时 KNOWS 边随之清掉
     *   是本模型的正确语义（指向已不存在的事实的知识 = 悬空垃圾）；与 family 族谱的
     *   「历史不因 destroy 变形」裁定相反，因为知识本体就是这条边 + 这个实体，实体没了
     *   「知道它」不成立。
     * - **边值（关系度）= 该 owner 持有这条知识的 [Provenance].code**：同一条 fact 可以被
     *   多人分别 KNOWS（亲历者边值 FIRSTHAND、经 [MemoryMod.teach] 听闻者边值 HEARSAY），
     *   每人自己的认知来源挂在各自的边上，fact 字段上的 [FIELD_PROVENANCE] 只记首录者的
     *   来源（§7 关系投影协议：边恒带 Long 值，这里给它业务含义的是 Mod，不是引擎）。
     */
    const val RELATION_KNOWS: String = "memory.knows"

    /** 每日衰减任务槽（判据 5）：相位吃 [JobDecl] 缺省 DAY_END，firstDay=0 起每游戏日一次。 */
    const val JOB_DECAY: String = "memory.decay"

    /** 存储键：记「本 mod 装着期间推进过多少个衰减日」，禁用→重启用后的值对账用（判据 6）。 */
    const val STORAGE_DECAY_TICKS: String = "decayTicks"

    /** [FIELD_CONFIDENCE] 的每日衰减率（Mod 规则，判据 5）：FIRSTHAND 每日 -1。 */
    const val FIRSTHAND_DAILY_DECAY: Long = 1L

    /** [FIELD_CONFIDENCE] 的每日衰减率（Mod 规则，判据 5）：HEARSAY 每日 -2（听闻更易淡忘）。 */
    const val HEARSAY_DAILY_DECAY: Long = 2L

    val manifest: ModManifest = ModManifest(
        packageId = PACKAGE_ID,
        version = "0.1",
        components = listOf(
            ComponentDecl(
                name = COMPONENT_FACT,
                fields = listOf(
                    // isEntityRef 只对整型字段合法：引用存的是 EntityId，跨设备重映射要按 schema 改它。
                    FieldDecl(FIELD_SUBJECT, FieldType.INT, isEntityRef = true),
                    FieldDecl(FIELD_OBJECT, FieldType.INT, isEntityRef = true),
                    FieldDecl(FIELD_SOURCE, FieldType.INT, isEntityRef = true),
                    FieldDecl(FIELD_PREDICATE, FieldType.STR),
                    FieldDecl(FIELD_TAGS, FieldType.STR),
                    FieldDecl(FIELD_OCCURRED_AT, FieldType.INT),
                    FieldDecl(FIELD_ACQUIRED_AT, FieldType.INT),
                    FieldDecl(FIELD_PROVENANCE, FieldType.INT),
                    FieldDecl(FIELD_CONFIDENCE, FieldType.INT),
                    FieldDecl(FIELD_VISIBILITY, FieldType.INT),
                    FieldDecl(FIELD_IMPORTANCE, FieldType.INT),
                ),
            ),
        ),
        archetypes = listOf(
            ArchetypeDecl(ARCHETYPE_PERSON),
            ArchetypeDecl(ARCHETYPE_FACT, listOf(COMPONENT_FACT)),
        ),
        relationships = listOf(
            RelationshipDecl(
                name = RELATION_KNOWS,
                directed = true,
                // dedupeEdges / cascadeOnDestroy 吃缺省（true / true），理由见 RELATION_KNOWS KDoc；
                // autoInverse 不启用，理由同上。
            ),
        ),
        jobs = listOf(
            // 每游戏日一次：判据 5 的衰减驱动槽，语义见本 object 头 KDoc「衰减语义」。
            JobDecl(jobId = JOB_DECAY, firstDay = 0L, everyDays = 1L),
        ),
    )
}

/**
 * [MemoryDeclarations.FIELD_PROVENANCE] 与 KNOWS 边值的取值枚举（判据 3）。
 *
 * 至少含 FIRSTHAND / HEARSAY 两档（判据 3 冻结口径）；码值进 INT 字段与关系边值，
 * 一经发布即固化，只增不改。
 */
enum class Provenance(val code: Long) {
    /** owner 亲历（亲眼所见/亲手所为）：只有首录者可以以此档入账。 */
    FIRSTHAND(1L),

    /** 听闻（他人转述）：经 [MemoryMod.teach] 学来的知识对学习者默认落此档。 */
    HEARSAY(2L),
}

/** [MemoryDeclarations.FIELD_VISIBILITY] 的取值枚举：谁知道这条知识的存在。 */
enum class Visibility(val code: Long) {
    /** 只有 owner 自己可提（缺省）。 */
    PRIVATE(0L),

    /** owner 愿意在同伙间提起。 */
    SHARED(1L),

    /** 公开事实，任何人可从 owner 处打听到。 */
    PUBLIC(2L),
}

/** [MemoryDeclarations.FIELD_IMPORTANCE] 的取值枚举：对 owner 的主观分量（衰减不改它）。 */
enum class Importance(val code: Long) {
    TRIVIAL(0L),
    LOW(1L),
    NORMAL(2L),
    HIGH(NORMAL.code + 1L),
    CRITICAL(HIGH.code + 1L),
}
