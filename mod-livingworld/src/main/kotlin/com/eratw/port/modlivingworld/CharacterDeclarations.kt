package com.eratw.port.modlivingworld

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.StorageKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modlivingworld.generated.NpcKeys

/**
 * 角色 Mod（eratw.lw.character）的**全部声明，只此一份真源**（§21.9 判据 1/2 的声明面，
 * era-arch-v61u.md §21.9 / §21.10 M10-1；四逻辑包同住 `:mod-livingworld` 一个 Gradle 模块，
 * packageId 各自独立）。
 *
 * ## 模型
 *
 * 一个 NPC = 一个 [NpcKeys.archetype] 实体，挂两个组件：
 *
 * - [NpcKeys.persona]（性格）：[NpcKeys.warmth]（温和度 0..100，>= 50 偏社交）、
 *   [NpcKeys.grit]（坚韧度 0..100）。性格是 Mod 数据不是引擎枚举（§21.9 Demo 内容策略）。
 * - [NpcKeys.schedule]（日程）：[NpcKeys.eveningSlot]（0 = 工坊 / 1 = 散步，由
 *   [CharacterMod.advanceDay] 按性格确定性写入）、[NpcKeys.activity]（今晚已解析的活动，
 *   "" = 未解析）、[NpcKeys.craftStage]（长活的原子段读数：0 未开始 / 1 开工 / 2 收工，
 *   判据 2 的中断不回滚证据）。
 *
 * ## 字段名不在这里
 *
 * 组件、原型与五个字段的名字由 [NpcKeys] 提供 —— 那是 `:tools:modgen-accessors` 生成的，
 * 同时也是 [com.eratw.port.modlivingworld.generated.NpcView] 读写列时用的同一批键。于是
 * 「同一个字段名手抄三遍」被压到只剩生成源一处：声明、业务代码、生成代码全指着同一个
 * `FieldKey`，打错是编译错误而不是静默读回 0。
 *
 * ## 字段键命名空间
 *
 * 字段名是**全局一张表**（引擎按字段名寻址，组件名不参与；DeclarationRegistry 的
 * component-schemas 域跨包同名拒绝），全部 `lw` 前缀与 eratw.family（age/title/father）、
 * eratw.memory（subject/provenance 等）互不重名 —— 四包同装一个快照零冲突由
 * [LivingWorldDeclarationTest] 钉住。
 *
 * ## 声明序 = 线格式序
 *
 * 下面每一条的顺序都是已发布资产的字节序（manifest 逐字节落盘）：组件内字段序、组件序、
 * 原型挂载序、任务序一律按原声明排，**不许由于「看起来更整齐」而重排**。
 */
@ModCarrier
object CharacterDeclarations : ModCarrierDeclaration {

    const val PACKAGE_ID: String = "eratw.lw.character"

    /** [ModCarrierDeclaration] 的实现面；与 [PACKAGE_ID] 同值，保留常量给既有调用面。 */
    override val packageId: String get() = PACKAGE_ID

    /** 载体工厂：装配期逐会话新建实例（`onLoad` 会往里塞状态，不许复用）。 */
    override fun newMod(): EraMod = CharacterMod()

    /** 每日日程推进任务槽（判据 1「经 JobApi 驱动各自日程」）：缺省相位 DAY_END，每游戏日一次。 */
    val DAY_TICK: JobKey = JobKey("lw.char.dayTick")

    /** 存储键：日程推进过的天数（判据 6 禁用→重启用对账凭据）。 */
    val SCHEDULE_DAYS: StorageKey = StorageKey("scheduleDays")

    /** 仲裁域：傍晚日程（provider/handler/约束都注册在此域下；引擎不解释域名含义）。 */
    const val DOMAIN_EVENING: String = "LW_EVENING"

    /** 仲裁域：长活探针（判据 2 发起/中断/恢复的专用域，候选按 metadata 过滤到单 NPC）。 */
    const val DOMAIN_ACTION: String = "LW_ACTION"

    /** 候选选择上下文的 metadata 键：把候选过滤到单个 NPC（值为 EntityId.raw 的十进制串）。 */
    const val METADATA_NPC: String = "lw.npc"

    /** 仲裁域候选集选择键（事件 Mod 用；角色 Mod 不消费）。 */
    const val METADATA_SET: String = "lw.set"

    /** [NpcKeys.eveningSlot] 取值：工坊。 */
    const val SLOT_WORKSHOP: Long = 0L

    /** [NpcKeys.eveningSlot] 取值：散步。 */
    const val SLOT_STROLL: Long = 1L

    /** [NpcKeys.activity] 取值：工坊手艺（判据 1 的互斥资源消费者）。 */
    const val ACT_CRAFT: String = "craft"

    /** [NpcKeys.activity] 取值：散步（抢不到工坊时的回落）。 */
    const val ACT_STROLL: String = "stroll"

    /** 性格 → 日程的分界：warmth >= 50 偏社交（散步），否则偏手艺（工坊）。 */
    const val WARMTH_SOCIAL_THRESHOLD: Long = 50L

    /** 演示阵容 NPC-A 的性格（手写常量；偏手艺 ⇒ 工坊槽）。 */
    const val NPC_A_WARMTH: Long = 30L
    const val NPC_A_GRIT: Long = 80L

    /** 演示阵容 NPC-B 的性格（手写常量；判据 1 里经测试改 warmth 驱动独立日程变化）。 */
    const val NPC_B_WARMTH: Long = 40L
    const val NPC_B_GRIT: Long = 60L

    /** 互斥资源预留键（判据 1）：同 kind+key 互斥由引擎 DecisionPipeline 保证。 */
    const val RESOURCE_KIND: String = "lw.resource"

    /** 互斥资源键值：工坊当晚只容一人（恰一成功一淘汰的证据键）。 */
    const val RESOURCE_WORKSHOP: String = "workshop"

    /**
     * 本载体的全部声明。`override` 是 [ModCarrierDeclaration] 的实现面 —— 构建期注册表
     * 从各声明 object 直接取这份 manifest 编资产，不再由 `BuiltinManifestBuild` 逐个点名。
     */
    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = "0.1") {
        displayName("生活世界 · 角色")
        author("eratw-port")
        description("NPC 温度与毅力、晚间行程与工坊活动")
        component(NpcKeys.persona) {
            int(NpcKeys.warmth)
            int(NpcKeys.grit)
        }
        component(NpcKeys.schedule) {
            int(NpcKeys.eveningSlot)
            str(NpcKeys.activity)
            int(NpcKeys.craftStage)
        }
        archetype(NpcKeys.archetype) {
            uses(NpcKeys.persona)
            uses(NpcKeys.schedule)
        }
        // 每游戏日一次：判据 1 的日程驱动槽，语义见本 object 头 KDoc。
        job(DAY_TICK, firstDay = 0L, everyDays = 1L)
    }
}
