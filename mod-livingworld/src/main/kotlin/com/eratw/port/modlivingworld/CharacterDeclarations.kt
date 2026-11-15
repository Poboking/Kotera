package com.eratw.port.modlivingworld

import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.FieldType
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModManifest

/**
 * 角色 Mod（eratw.lw.character）的**全部声明，只此一份真源**（§21.9 判据 1/2 的声明面，
 * era-arch-v61u.md §21.9 / §21.10 M10-1；四逻辑包同住 `:mod-livingworld` 一个 Gradle 模块，
 * packageId 各自独立）。
 *
 * ## 模型
 *
 * 一个 NPC = 一个 [ARCHETYPE_NPC] 实体，挂两个组件：
 *
 * - [COMPONENT_PERSONA]（性格）：[FIELD_WARMTH]（温和度 0..100，>= 50 偏社交）、
 *   [FIELD_GRIT]（坚韧度 0..100）。性格是 Mod 数据不是引擎枚举（§21.9 Demo 内容策略）。
 * - [COMPONENT_SCHEDULE]（日程）：[FIELD_EVENING_SLOT]（0 = 工坊 / 1 = 散步，由
 *   [CharacterMod.advanceDay] 按性格确定性写入）、[FIELD_ACTIVITY]（今晚已解析的活动，
 *   "" = 未解析）、[FIELD_CRAFT_STAGE]（长活的原子段读数：0 未开始 / 1 开工 / 2 收工，
 *   判据 2 的中断不回滚证据）。
 *
 * ## 字段键命名空间
 *
 * 字段名是**全局一张表**（引擎按字段名寻址，组件名不参与；DeclarationRegistry 的
 * component-schemas 域跨包同名拒绝），全部 `lw` 前缀与 eratw.family（age/title/father）、
 * eratw.memory（subject/provenance 等）互不重名 —— 四包同装一个快照零冲突由
 * [LivingWorldDeclarationTest] 钉住。
 */
object CharacterDeclarations {

    const val PACKAGE_ID: String = "eratw.lw.character"

    /** NPC 原型：挂 [COMPONENT_PERSONA] 与 [COMPONENT_SCHEDULE] 两个组件。 */
    const val ARCHETYPE_NPC: String = "lw.char.npc"

    const val COMPONENT_PERSONA: String = "lw.char.persona"
    const val COMPONENT_SCHEDULE: String = "lw.char.schedule"

    const val FIELD_WARMTH: String = "lwWarmth"
    const val FIELD_GRIT: String = "lwGrit"
    const val FIELD_EVENING_SLOT: String = "lwEveningSlot"
    const val FIELD_ACTIVITY: String = "lwActivity"
    const val FIELD_CRAFT_STAGE: String = "lwCraftStage"

    /** 每日日程推进任务槽（判据 1「经 JobApi 驱动各自日程」）：缺省相位 DAY_END，每游戏日一次。 */
    const val JOB_DAY_TICK: String = "lw.char.dayTick"

    /** 存储键：日程推进过的天数（判据 6 禁用→重启用对账凭据）。 */
    const val STORAGE_SCHEDULE_DAYS: String = "scheduleDays"

    /** 仲裁域：傍晚日程（provider/handler/约束都注册在此域下；引擎不解释域名含义）。 */
    const val DOMAIN_EVENING: String = "LW_EVENING"

    /** 仲裁域：长活探针（判据 2 发起/中断/恢复的专用域，候选按 metadata 过滤到单 NPC）。 */
    const val DOMAIN_ACTION: String = "LW_ACTION"

    /** 候选选择上下文的 metadata 键：把候选过滤到单个 NPC（值为 EntityId.raw 的十进制串）。 */
    const val METADATA_NPC: String = "lw.npc"

    /** 仲裁域候选集选择键（事件 Mod 用；角色 Mod 不消费）。 */
    const val METADATA_SET: String = "lw.set"

    /** [FIELD_EVENING_SLOT] 取值：工坊。 */
    const val SLOT_WORKSHOP: Long = 0L

    /** [FIELD_EVENING_SLOT] 取值：散步。 */
    const val SLOT_STROLL: Long = 1L

    /** [FIELD_ACTIVITY] 取值：工坊手艺（判据 1 的互斥资源消费者）。 */
    const val ACT_CRAFT: String = "craft"

    /** [FIELD_ACTIVITY] 取值：散步（抢不到工坊时的回落）。 */
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

    val manifest: ModManifest = ModManifest(
        packageId = PACKAGE_ID,
        version = "0.1",
        components = listOf(
            ComponentDecl(
                name = COMPONENT_PERSONA,
                fields = listOf(
                    FieldDecl(FIELD_WARMTH, FieldType.INT),
                    FieldDecl(FIELD_GRIT, FieldType.INT),
                ),
            ),
            ComponentDecl(
                name = COMPONENT_SCHEDULE,
                fields = listOf(
                    FieldDecl(FIELD_EVENING_SLOT, FieldType.INT),
                    FieldDecl(FIELD_ACTIVITY, FieldType.STR),
                    FieldDecl(FIELD_CRAFT_STAGE, FieldType.INT),
                ),
            ),
        ),
        archetypes = listOf(
            ArchetypeDecl(ARCHETYPE_NPC, listOf(COMPONENT_PERSONA, COMPONENT_SCHEDULE)),
        ),
        jobs = listOf(
            // 每游戏日一次：判据 1 的日程驱动槽，语义见本 object 头 KDoc。
            JobDecl(jobId = JOB_DAY_TICK, firstDay = 0L, everyDays = 1L),
        ),
    )
}
