package com.eratw.port.modlivingworld

import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionContext
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ReservationKey

/**
 * 角色 Mod 入口（§21.9 判据 1「独立日程 + 互斥不双占」与判据 2「发起/中断/恢复」的落点）。
 *
 * ## 架构意义
 *
 * §21.9 的第一个消费者：引擎不加恋爱专用代码、Mod 不自带调度仲裁 —— 日程是本 Mod 的数据
 * 与规则（[advanceDay] 按性格确定性写槽位），「做哪一件」交给引擎侧统一仲裁器（测试侧经
 * `ModRuntimeHost(decisionPipeline = …)` 注入真 `DecisionPipeline`，本类只经
 * `context.actions` 的包作用域 facade 注册 provider/handler）。main 源码 0 个
 * `com.eratw.port.core` import，依赖方向由本模块 build.gradle.kts 在编译期拦着。
 *
 * ## 仲裁参与的降级口径（诚实边界，不是静默兜底）
 *
 * 生产宿主**缺省不注入** `DecisionPipeline`（ModRuntimeAssembly.kt:91 注释明写
 * `decisionPipeline` 维持 null，归 §21.3 仲裁车接线）；此时 `context.actions` 是显式
 * fail-closed 桩（每入口抛 UnsupportedOperationException 并点名 packageId）。本类在
 * [onEnable] 注册仲裁参与时**捕获该异常并留 WARN 日志 + [arbitrationDegraded] 读数**，
 * 装载继续 —— 其余能力（日程任务、实体、存档）照常。这不是「把错误藏起来」：桩的异常
 * 原文（含 packageId 与 §12.1 指引）原样进日志面，降级是可观测、可审计的状态。
 *
 * ## 嵌套仲裁驱动缝（判据 1 的 RESERVATION_CONFLICT 从哪来）
 *
 * 引擎的预留互斥只对 **held 集合**生效：同轮两候选抢同键不互斥（输家吃 PRIORITY_LOST），
 * 只有「胜者 handler 执行期间（预留仍 held）再跑一轮仲裁」才会给同键候选出
 * RESERVATION_CONFLICT —— 这是引擎唯一给出该原因码的路径（core DecisionPipelineTest 用例
 * 12 同形状）。跑仲裁是宿主职责（mod 侧无 run 入口），故本类留 [decisionDriver] 注入缝：
 * 测试侧传真管线驱动，生产接线归组长（见交付「需组长接线」）。未注入时嵌套解析跳过
 * （候选留给下一轮），互斥仍由「同轮同键只有一个胜者」兜底，不双占不变。
 *
 * ## 职责切分
 *
 * 写路径（日程推进/长活原子段）与生命周期在本类；只读查询在 [queries]
 * （onLoad 装配的实例级只读 facade，照 MemoryMod / MemoryQueries 先例）。两者都只 wrap
 * [ModContext]，无静态可变全局（铁律 4）。
 */
class CharacterMod : EraMod {

    /** 宿主注入的嵌套仲裁驱动缝（KDoc 见类头）；null = 未接线，嵌套解析跳过。 */
    fun interface DecisionDriver {
        /** 宿主跑一轮 [domain] 域仲裁（metadata 透传），报告由宿主自行消费。 */
        fun resolve(domain: String, metadata: Map<String, String>)
    }

    private lateinit var context: ModContext

    /** 只读查询面（onLoad 装配；日程/活动/长活读数都在这里）。 */
    lateinit var queries: CharacterQueries
        private set

    /** 请求中断的 NPC 集（判据 2）：下一次长活原子段边界消费，消费即移除（一次性）。 */
    private val interruptRequested = LinkedHashSet<EntityId>()

    /** 日程推进过的天数（判据 6 对账凭据，经 mod 存储落盘）。 */
    var scheduleDays: Long = 0L
        private set

    /** 仲裁参与是否因宿主未注入管线而降级（类头 KDoc；false = 参与已注册）。 */
    var arbitrationDegraded: Boolean = false
        private set

    /** 嵌套仲裁驱动缝（测试侧注入；生产归组长接线）。 */
    var decisionDriver: DecisionDriver? = null

    override fun onLoad(context: ModContext) {
        this.context = context
        this.queries = CharacterQueries(context)
        scheduleDays = context.storage.get(CharacterDeclarations.STORAGE_SCHEDULE_DAYS)?.toLongOrNull() ?: 0L
    }

    override fun onEnable() {
        // 演示阵容：两个 NPC，各自性格组件（§21.9：性格是 Mod 数据）。已有阵容不重建
        // （读档恢复会话里实体随内核快照回来，EntityId 恒等）。
        if (queries.npcs().isEmpty()) {
            val first = context.entities.create(CharacterDeclarations.ARCHETYPE_NPC)
            context.entities.setInt(first, CharacterDeclarations.FIELD_WARMTH, CharacterDeclarations.NPC_A_WARMTH)
            context.entities.setInt(first, CharacterDeclarations.FIELD_GRIT, CharacterDeclarations.NPC_A_GRIT)
            val second = context.entities.create(CharacterDeclarations.ARCHETYPE_NPC)
            context.entities.setInt(second, CharacterDeclarations.FIELD_WARMTH, CharacterDeclarations.NPC_B_WARMTH)
            context.entities.setInt(second, CharacterDeclarations.FIELD_GRIT, CharacterDeclarations.NPC_B_GRIT)
        }
        // 判据 1：日程由 JobApi 驱动（静态任务槽，声明见 CharacterDeclarations.JOB_DAY_TICK）。
        context.jobs.bind(CharacterDeclarations.JOB_DAY_TICK) { advanceDay() }
        registerArbitrationParticipation()
    }

    override fun onDisable() {
        // 计数落 mod 存储（判据 6：禁用后数据必须还在；实体/边在内核侧，不走本存储）。
        context.storage.put(CharacterDeclarations.STORAGE_SCHEDULE_DAYS, scheduleDays.toString())
    }

    override fun onUnload() {
        decisionDriver = null
        interruptRequested.clear()
        scheduleDays = 0L
        arbitrationDegraded = false
    }

    /**
     * 一个游戏日的日程推进（判据 1；生产驱动 = [CharacterDeclarations.JOB_DAY_TICK]
     * 绑定槽，测试与诊断可直调，照 FamilyMod.advanceOneDay / MemoryMod.decayOneDay 先例）。
     *
     * 独立日程 = 每个 NPC 按自己的性格字段确定自己的槽位：warmth >= 阈值 → 散步，
     * 否则 → 工坊。全确定性、无随机；同时把今晚的活动读数清空（"" = 未解析）。
     */
    fun advanceDay() {
        scheduleDays += 1L
        for (npc in queries.npcs()) {
            val social = context.entities.getInt(npc, CharacterDeclarations.FIELD_WARMTH) >=
                CharacterDeclarations.WARMTH_SOCIAL_THRESHOLD
            context.entities.setInt(
                npc,
                CharacterDeclarations.FIELD_EVENING_SLOT,
                if (social) CharacterDeclarations.SLOT_STROLL else CharacterDeclarations.SLOT_WORKSHOP,
            )
            context.entities.setStr(npc, CharacterDeclarations.FIELD_ACTIVITY, "")
        }
        context.storage.put(CharacterDeclarations.STORAGE_SCHEDULE_DAYS, scheduleDays.toString())
    }

    /**
     * 请求中断一个 NPC 的长活（判据 2 的外部中断入口；生产由事件/交互链调用）。
     * 一次性：下一次长活原子段边界消费，令牌生效返回 CANCELED 后需重新请求才能再中断。
     */
    fun requestInterrupt(npc: EntityId) {
        interruptRequested += npc
    }

    /** 仲裁参与注册（两个域各自 fail-closed 拒绝时同口径降级；类头 KDoc）。 */
    private fun registerArbitrationParticipation() {
        try {
            context.actions.provide(CharacterDeclarations.DOMAIN_EVENING, ::eveningCandidates)
            context.actions.on(CharacterMod.HANDLER_CRAFT, ::craftExecuted)
            context.actions.on(CharacterMod.HANDLER_STROLL) {
                context.entities.setStr(
                    it.candidate.actor, CharacterDeclarations.FIELD_ACTIVITY, CharacterDeclarations.ACT_STROLL,
                )
            }
        } catch (expected: UnsupportedOperationException) {
            arbitrationDegraded = true
            context.logger.warn("决策管线未装配，仲裁参与降级：${expected.message}")
        }
        try {
            // 判据 2 的长活域：按 metadata 过滤到单 NPC（缺省全部 NPC 各一个）。
            context.actions.provide(CharacterDeclarations.DOMAIN_ACTION) { selection ->
                val only = selection.metadata[CharacterDeclarations.METADATA_NPC]
                queries.npcs()
                    .filter { only == null || it.raw.toString() == only }
                    .map { npc ->
                        ActionCandidate(
                            providerPackage = CharacterDeclarations.PACKAGE_ID,
                            candidateKey = "${CharacterMod.HANDLER_LONG_CRAFT}:${npc.raw}",
                            actor = npc,
                            handlerKey = CharacterMod.HANDLER_LONG_CRAFT,
                        )
                    }
            }
            context.actions.on(CharacterMod.HANDLER_LONG_CRAFT, ::longCraftExecuted)
        } catch (expected: UnsupportedOperationException) {
            arbitrationDegraded = true
            context.logger.warn("决策管线未装配，仲裁参与降级：${expected.message}")
        }
    }

    /**
     * 傍晚候选（判据 1）：每个「槽位 = 工坊」的 NPC 出两个候选 —— 工坊手艺（高层、带
     * [CharacterDeclarations.RESOURCE_KIND]/[CharacterDeclarations.RESOURCE_WORKSHOP] 预留）
     * 与散步回落（低层、无预留）；「槽位 = 散步」的 NPC 只出散步。metadata
     * [CharacterDeclarations.METADATA_NPC] 非空时过滤到该 NPC（嵌套解析轮的形状）。
     */
    private fun eveningCandidates(selection: ActionSelectionContext): List<ActionCandidate> {
        val only = selection.metadata[CharacterDeclarations.METADATA_NPC]
        val candidates = ArrayList<ActionCandidate>()
        for (npc in queries.npcs().filter { only == null || it.raw.toString() == only }) {
            if (queries.eveningSlotOf(npc) == CharacterDeclarations.SLOT_WORKSHOP) {
                candidates += ActionCandidate(
                    providerPackage = CharacterDeclarations.PACKAGE_ID,
                    candidateKey = "${CharacterMod.HANDLER_CRAFT}:${npc.raw}",
                    actor = npc,
                    priority = CharacterMod.PRIORITY_RESOURCE,
                    weight = 1,
                    reservationClaims = listOf(
                        ReservationKey(
                            CharacterDeclarations.RESOURCE_KIND,
                            CharacterDeclarations.RESOURCE_WORKSHOP,
                        ),
                    ),
                    handlerKey = CharacterMod.HANDLER_CRAFT,
                )
            }
            candidates += ActionCandidate(
                providerPackage = CharacterDeclarations.PACKAGE_ID,
                candidateKey = "${CharacterMod.HANDLER_STROLL}:${npc.raw}",
                actor = npc,
                priority = CharacterMod.PRIORITY_FALLBACK,
                handlerKey = CharacterMod.HANDLER_STROLL,
            )
        }
        return candidates
    }

    /**
     * 工坊手艺 handler（判据 1 的胜者路径）：解析自己的活动，然后经 [decisionDriver] 驱动
     * 一轮**嵌套仲裁**解析其余未决 NPC —— 本轮预留键此刻仍 held，嵌套轮里同键候选吃
     * RESERVATION_CONFLICT、回落到散步（类头 KDoc「嵌套仲裁驱动缝」）。
     */
    private fun craftExecuted(action: ActionContext) {
        context.entities.setStr(
            action.candidate.actor, CharacterDeclarations.FIELD_ACTIVITY, CharacterDeclarations.ACT_CRAFT,
        )
        val driver = decisionDriver ?: return
        val actor = action.candidate.actor
        val next = queries.npcs().firstOrNull {
            it != actor &&
                queries.eveningSlotOf(it) == CharacterDeclarations.SLOT_WORKSHOP &&
                queries.activityOf(it).isEmpty()
        } ?: return
        driver.resolve(
            CharacterDeclarations.DOMAIN_EVENING,
            mapOf(CharacterDeclarations.METADATA_NPC to next.raw.toString()),
        )
    }

    /**
     * 长活 handler（判据 2 的发起/中断/恢复）：原子段一（开工写数）→ 中断请求在**原子段
     * 边界**生效（令牌置位、本 handler 原样返回 ⇒ 引擎判 CANCELED，段一写入不回滚）→
     * 未中断则原子段二（收工）。Mod 每轮重新生成该候选（provider 无状态），中断清除后
     * 下一轮仲裁即为「恢复」。
     */
    private fun longCraftExecuted(action: ActionContext) {
        val npc = action.candidate.actor
        // 原子段一：开工。取消令牌只在原子段边界生效，段内写入不回滚（引擎不回滚已发生的
        // 组件写入，§21.3；恢复语义 = 重跑一轮原子段一 + 段二）。
        context.entities.setInt(npc, CharacterDeclarations.FIELD_CRAFT_STAGE, 1L)
        context.entities.setStr(npc, CharacterDeclarations.FIELD_ACTIVITY, CharacterMod.ACT_LONG_CRAFT_STAGE1)
        if (npc in interruptRequested) {
            interruptRequested.remove(npc)
            action.cancellation.requestCancel()
            return
        }
        // 原子段二：收工。
        context.entities.setInt(npc, CharacterDeclarations.FIELD_CRAFT_STAGE, 2L)
        context.entities.setStr(npc, CharacterDeclarations.FIELD_ACTIVITY, CharacterMod.ACT_LONG_CRAFT_DONE)
    }

    companion object {
        /** handler 键：工坊手艺（本包作用域注册）。 */
        const val HANDLER_CRAFT: String = "lw.char.craft"

        /** handler 键：散步回落。 */
        const val HANDLER_STROLL: String = "lw.char.stroll"

        /** handler 键：长活（判据 2 的中断载体）。 */
        const val HANDLER_LONG_CRAFT: String = "lw.char.longcraft"

        /** [CharacterDeclarations.FIELD_ACTIVITY] 取值：长活开工（原子段一后的读数）。 */
        const val ACT_LONG_CRAFT_STAGE1: String = "longcraft:stage1"

        /** [CharacterDeclarations.FIELD_ACTIVITY] 取值：长活收工（原子段二后的读数）。 */
        const val ACT_LONG_CRAFT_DONE: String = "longcraft:done"

        /** 候选优先级：资源层（抢工坊的手艺）。 */
        const val PRIORITY_RESOURCE: Int = 1

        /** 候选优先级：回落层（散步；只有资源层全灭时才轮到）。 */
        const val PRIORITY_FALLBACK: Int = 0
    }
}

/**
 * 角色 Mod 的**只读查询面**（判据 1 的日程读数；写路径在 [CharacterMod]）。
 * 独立日程 = 每名 NPC 自己的字段（槽位/活动/长活段读数），查询只是按实体取列。
 */
class CharacterQueries(private val context: ModContext) {

    /** 全部 NPC（EntityId 升序快照）。 */
    fun npcs(): List<EntityId> = context.entities.query(CharacterDeclarations.ARCHETYPE_NPC)

    /** 读一个 NPC 今晚的槽位（0 = 工坊 / 1 = 散步）。 */
    fun eveningSlotOf(npc: EntityId): Long =
        context.entities.getInt(npc, CharacterDeclarations.FIELD_EVENING_SLOT)

    /** 读一个 NPC 今晚已解析的活动（"" = 未解析）。 */
    fun activityOf(npc: EntityId): String =
        context.entities.getStr(npc, CharacterDeclarations.FIELD_ACTIVITY)

    /** 读长活原子段读数（0 未开始 / 1 开工 / 2 收工）。 */
    fun craftStageOf(npc: EntityId): Long =
        context.entities.getInt(npc, CharacterDeclarations.FIELD_CRAFT_STAGE)
}
