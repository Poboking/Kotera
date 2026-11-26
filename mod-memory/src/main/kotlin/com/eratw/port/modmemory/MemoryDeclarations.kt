package com.eratw.port.modmemory

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.StorageKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modmemory.generated.MemoryKeys

/**
 * 记忆 Mod 的**全部声明，只此一份真源**（M9-1 参考实现，era-arch-v61u.md §21.10 M9-1 / §21.2）。
 *
 * ## 模型（§21.2 参考：知识 ≈ fact 实体 + `owner --KNOWS--> fact` 有向边）
 *
 * 一条「知识」= 一个 fact 实体（携带这条事实的全部元数据）+ 一条从知道它的人指向它的
 * [KNOWS] 有向边。**知识只经 KNOWS 边可达**：引擎不做任何自动传播，「A 亲历的事实
 * 不会自动成为 B 的知识」由 `MemoryModTest` 锁定——B 想知道，必须有人显式调
 * [MemoryMod.teach] 给 B 挂上自己的边。
 *
 * 社交面另有一张人物↔人物账：[ACQUAINTANCE]（「双方已认识」无向边，MODDX4-B4 接线），
 * 是 [MemoryMod.teach] 的「传授需先认识」门的真值来源。它与 [KNOWS] 的知识可达账互不掺合
 * ——同名会撞声明闸（`DeclarationRegistry` freeze 对同名关系类型判冲突），故另立名
 * `memory.acquaintance`；语义差异见 [ACQUAINTANCE] KDoc。
 *
 * ## 字段键不是这里手写的
 *
 * 字段键（`subject` / `object` / `source` / `predicate` / `tags` / `occurredAt` /
 * `acquiredAt` / `provenance` / `confidence` / `visibility` / `importance`）全部由
 * [MemoryKeys] 提供，源头是 `tools/modgen-accessors/schemas/memory.schema.tsv`。
 * 以前这里躺着 11 个 `const val FIELD_XXX: String`，加上声明里的 `FieldDecl(FIELD_XXX)`、
 * 加上访问器里的 `getInt(e, FIELD_XXX)` —— 同一串字段名手抄三遍，任一处打错都是**静默**
 * 失败哨兵（读 0 / 读空串 / 写 false），编译器一句话都不会说。现在只有 TSV 一份，
 * `checkMemoryAccessors` 的 `--check` 闸会拦住「TSV 改了没重跑生成器」。
 *
 * ## fact 字段的五类语义（判据 2）
 *
 * 1. **实体引用三类**（`isEntityRef = true` 的整型字段，跨设备重映射按 schema 改写）：
 *    [MemoryKeys.subject]（这条事实关于谁）、[MemoryKeys.obj]（事实的宾语/对象侧，可为
 *    NULL）、[MemoryKeys.source]（知识来源：讲述者；owner 自己亲历时 = owner 自身）。
 *    注意 `object` 这个字段名是 Kotlin 硬关键字，所以**属性叫 `obj`**（schema TSV 第 5 列
 *    覆盖属性名），而字段键仍是线格式的 `object` —— 线格式一字节没动。
 * 2. **文本两类**（STR）：[MemoryKeys.predicate]（谓词：这条事实断言什么，如「结识」「赠予」）、
 *    [MemoryKeys.tags]（自由标签，多值用半角逗号分隔，约定不进引擎）。
 * 3. **游戏日两类**（INT）：[MemoryKeys.occurredAt]（事实发生的游戏日）、
 *    [MemoryKeys.acquiredAt]（owner 获知的游戏日）。
 * 4. **枚举四类**（INT，取值 = 下方三个枚举 + confidence 区间）：[MemoryKeys.provenance] =
 *    [Provenance].code、[MemoryKeys.visibility] = [Visibility].code、
 *    [MemoryKeys.importance] = [Importance].code；[MemoryKeys.confidence] 是 0..100 的闭区间
 *    度量（0 = 全然不信，100 = 深信不疑），不是枚举——四类里它以「取值区间」承担同一职责。
 * 5. 读写一律走 [com.eratw.port.modmemory.generated.MemoryView] 的属性（`view.confidence`），
 *    业务代码里看不到字符串键、也看不到 `.raw`。
 *
 * ## 衰减语义（判据 5，Mod 规则，引擎零新增）
 *
 * [DECAY] 每游戏日（[com.eratw.port.modapi.BuiltinPhase.DAY_END]，`job` 的缺省相位）触发一次：
 * 对全部 fact 实体按来源降 [MemoryKeys.confidence]——HEARSAY 每日 -2、FIRSTHAND 每日 -1，
 * 下限 0（不复负、不删实体、不动边）。衰减只动 confidence 一个字段；遗忘是显式
 * [MemoryMod.forget]，衰减到 0 ≠ 遗忘。
 */
@ModCarrier
object MemoryDeclarations : ModCarrierDeclaration {

    const val PACKAGE_ID: String = "eratw.memory"

    /** [ModCarrierDeclaration] 的实现面；与 [PACKAGE_ID] 同值，保留常量给既有调用面。 */
    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = MemoryMod()

    /** 知识持有者原型（测试/演示用最小形状；owner 也可以是任何既有实体，原型不约束）。 */
    val PERSON: ArchetypeKey = ArchetypeKey("memory.person")

    /** 知识（fact）原型：只挂 [MemoryKeys.component] 一个组件，建实体走 `MemoryFacade.create()`。 */
    val FACT: ArchetypeKey = MemoryKeys.archetype

    /**
     * 知识边：`owner --KNOWS--> fact`，有向、无自动反向边。
     *
     * - **方向语义**：出边 = 「这条边的主语知道 fact」；从 fact 视角查 owner 用入边
     *   （`QueryApi` 的 `HasRelation(KNOWS, outgoing = false, other = owner)`）。
     * - **不设 autoInverse**：知识不是双向关系——A 知道 f 不蕴含 f「被 A 知道」之外任何方向
     *   需要独立边型；查询面用入边方向即可，多一条反向边只会让「知识可达」出现第二张账本。
     * - **cascadeOnDestroy 吃缺省 true**：fact 实体被 destroy（显式遗忘）时 KNOWS 边随之清掉
     *   是本模型的正确语义（指向已不存在的事实的知识 = 悬空垃圾）；与 family 族谱的
     *   「历史不因 destroy 变形」裁定相反，因为知识本体就是这条边 + 这个实体，实体没了
     *   「知道它」不成立。
     * - **边值（关系度）= 该 owner 持有这条知识的 [Provenance].code**：同一条 fact 可以被
     *   多人分别 KNOWS（亲历者边值 FIRSTHAND、经 [MemoryMod.teach] 听闻者边值 HEARSAY），
     *   每人自己的认知来源挂在各自的边上，fact 字段上的 [MemoryKeys.provenance] 只记首录者的
     *   来源（§7 关系投影协议：边恒带 Long 值，这里给它业务含义的是 Mod，不是引擎）。
     */
    val KNOWS: RelationKey = RelationKey("memory.knows")

    /**
     * 人物↔人物的「双方已认识」关系（MODDX4-B4 组长裁决 1/3：双向边）。
     *
     * - **语义分工**：[KNOWS] = 「谁持有哪条知识」（owner→fact 有向边，fact 可达性）；
     *   [ACQUAINTANCE] = 「两个人互相认识」（person↔person 社交面）。A 认识 B 不经任何
     *   fact 实体，也不代表 A 知道关于 B 的任何 fact——两张账各管各的。不与 [KNOWS] 同名：
     *   同名关系类型在 `DeclarationRegistry` freeze 期判冲突（同名关系按名字寻址会歧义），
     *   另立名 `memory.acquaintance`。
     * - **无向（directed = false）**：「双方已认识」天然对称，内核对无向边单向存储、两端
     *   对称判真（`EntityKernel.hasRelation` 的无向反查分支，EntityKernel.kt:347-356）：A 方向
     *   `add` 之后，B 方向的 `has` / `valueOf` / `remove` 全部命中同一条边。选无向而非
     *   「directed = true + autoInverse 成对声明」是裁决 1 给的两个选项里实现更顺的一种：
     *   无向不需要第二张反向账本，也少一对 inverse 声明要维护。
     * - **dedupeEdges 吃缺省 true**：重复结识 `add` 返回 `false`；无向语义下反方向重复也算
     *   同一条边（EntityKernel.kt:300-306）。
     * - **cascadeOnDestroy 吃缺省 true**：一方实体被 destroy 时认识边随之清掉——指向已不
     *   存在实体的「认识」是悬空垃圾（与 [KNOWS] 的级联同理）。
     * - **边值（关系度）暂无业务含义**：恒为缺省 0（§7 关系投影协议边恒带 Long 值，业务含义
     *   由 Mod 给；本 mod 未给本边派值，也不在任何业务判定里读它——调用方经 `setValue` 写入
     *   的值内核原样保存，仅作保留位）。
     */
    val ACQUAINTANCE: RelationKey = RelationKey("memory.acquaintance")

    /** 每日衰减任务槽（判据 5）：相位吃 `job` 的缺省 DAY_END，firstDay=0 起每游戏日一次。 */
    val DECAY: JobKey = JobKey("memory.decay")

    /** 存储键：记「本 mod 装着期间推进过多少个衰减日」，禁用→重启用后的值对账用（判据 6）。 */
    val DECAY_TICKS: StorageKey = StorageKey("decayTicks")

    /** [MemoryKeys.confidence] 的每日衰减率（Mod 规则，判据 5）：FIRSTHAND 每日 -1。 */
    const val FIRSTHAND_DAILY_DECAY: Long = 1L

    /** [MemoryKeys.confidence] 的每日衰减率（Mod 规则，判据 5）：HEARSAY 每日 -2（听闻更易淡忘）。 */
    const val HEARSAY_DAILY_DECAY: Long = 2L

    /** per-mod 设置项键：每日衰减强度倍率 0–10（INT，缺省 1 = 既有行为；0 = 暂停衰减）。 */
    const val DECAY_RATE_KEY: String = "memory.decayRate"

    /**
     * per-mod 设置项键：传授是否要求双方已认识（BOOL，缺省关 = 既有行为）。
     *
     * 开启时 [MemoryMod.teach] 的门查 [ACQUAINTANCE] 边真值（teacher 与 learner 之间，MODDX4-B4
     * 真值化接线）；关闭时不查认识边，行为与接线前逐位一致。玩家可见文案（下方 `description`）
     * 「双方已认识」自声明起就是这个语义——本波补的只是实现，文案与线格式一字节未动。
     */
    const val TEACH_REQUIRES_KNOWS_KEY: String = "memory.teachRequiresKnows"

    /**
     * 声明本体。**声明序必须是线格式序**：`RT2MANIFEST/3` 的 `@entity-archetypes` 等段按
     * 声明序逐条落盘，改动顺序 = 改已发布资产的字节，会被
     * `:tools:modgen:generateBuiltinRt2Manifest` 的逐字节比对拦下来。
     */
    /**
     * 本载体的全部声明。`override` 是 [ModCarrierDeclaration] 的实现面 —— 构建期注册表
     * 从各声明 object 直接取这份 manifest 编资产，不再由 `BuiltinManifestBuild` 逐个点名。
     */
    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("记忆系统")
        author("eratw-port")
        description("事实记忆的记录、传授、遗忘与逐日衰减")
        component(MemoryKeys.component) {
            // isEntityRef 只对整型字段合法：引用存的是 EntityId，跨设备重映射要按 schema 改它。
            ref(MemoryKeys.subject)
            ref(MemoryKeys.obj)
            ref(MemoryKeys.source)
            str(MemoryKeys.predicate)
            str(MemoryKeys.tags)
            int(MemoryKeys.occurredAt)
            int(MemoryKeys.acquiredAt)
            int(MemoryKeys.provenance)
            int(MemoryKeys.confidence)
            int(MemoryKeys.visibility)
            int(MemoryKeys.importance)
        }
        archetype(PERSON)
        archetype(FACT) { uses(MemoryKeys.component) }
        relation(KNOWS)
        // 人物↔人物「双方已认识」：无向、去重、destroy 级联清边（后两者吃缺省 true），语义见 [ACQUAINTANCE]。
        relation(ACQUAINTANCE, directed = false)
        // 每游戏日一次：判据 5 的衰减驱动槽，语义见本 object 头 KDoc「衰减语义」。
        job(DECAY, everyDays = 1L)
        // per-mod 设置项（RT2 per-mod 配置）：声明一次 ⇒ 设置页自动出控件、值按包存在设备侧
        // （mods/rt2/eratw.memory.settings），mod 侧经 context.settings 读；本包 manifest 升
        // RT2MANIFEST/5（无声明时仍是 /4）。
        intSetting(
            key = DECAY_RATE_KEY,
            default = 1L,
            range = 0L..10L,
            title = "每日衰减强度",
            description = "自信任度每日衰减的倍率（0 = 暂停衰减，1 = 既有行为）。",
        )
        boolSetting(
            key = TEACH_REQUIRES_KNOWS_KEY,
            default = false,
            title = "传授需先认识",
            description = "开启后只有双方已认识才能传授知识；缺省关（与既有行为一致）。",
        )
    }
}

/**
 * [MemoryKeys.provenance] 与 KNOWS 边值的取值枚举（判据 3）。
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

/** [MemoryKeys.visibility] 的取值枚举：谁知道这条知识的存在。 */
enum class Visibility(val code: Long) {
    /** 只有 owner 自己可提（缺省）。 */
    PRIVATE(0L),

    /** owner 愿意在同伙间提起。 */
    SHARED(1L),

    /** 公开事实，任何人可从 owner 处打听到。 */
    PUBLIC(2L),
}

/** [MemoryKeys.importance] 的取值枚举：对 owner 的主观分量（衰减不改它）。 */
enum class Importance(val code: Long) {
    TRIVIAL(0L),
    LOW(1L),
    NORMAL(2L),
    HIGH(NORMAL.code + 1L),
    CRITICAL(HIGH.code + 1L),
}
