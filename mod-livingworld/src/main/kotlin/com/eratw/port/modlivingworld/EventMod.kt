package com.eratw.port.modlivingworld

import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionContext
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.add
import com.eratw.port.modapi.get
import com.eratw.port.modapi.put
import com.eratw.port.modapi.query
import com.eratw.port.modapi.setValue
import com.eratw.port.modapi.valueOf
import com.eratw.port.modlivingworld.generated.LwEventFacade
import com.eratw.port.modlivingworld.generated.NpcKeys

/**
 * 社交事件 Mod 入口（§21.9 判据 5「多候选确定性 + 可解释淘汰 + 决策随机流独立性」与
 * 判据 7「错误可定位到 package/candidate/condition」的落点）。
 *
 * ## 候选集（手写常量，Demo 内容策略）
 *
 * provider 按 metadata（[CharacterDeclarations.METADATA_SET]）出**手写的**候选集：
 *
 * - [EventDeclarations.SET_TIER]：茶会（高层 weight 3，约束 = 好感为正）、闲话（高层
 *   weight 1）、恶作剧（高层，恒假约束 ⇒ CONSTRAINT_FALSE 淘汰）、打盹（低层 ⇒
 *   PRIORITY_LOST 淘汰）—— 同层多候选按确定性序 + 加权抽取，淘汰逐候选带原因码。
 * - [EventDeclarations.SET_COOLDOWN]：闹鬼（cooldownKey 钉 3 日）—— 首轮 COMPLETED 后
 *   同日再抽吃 COOLDOWN 淘汰。
 * - [EventDeclarations.SET_FAILURE]：引用未注册约束的候选（淘汰 detail 点名）+ 点名
 *   抛异常的约束谓词 + 点名抛异常的 provider（metadata 开关）—— 失败记录带
 *   packageId / candidateKey / 阶段，判据 7 的定位面。
 *
 * 降级口径与角色 Mod 相同：生产**开态**注入真 `DecisionPipeline`（总闸玩家面缺省开 =
 * RT2-GATE-OPEN，ModRuntimeAssembly.kt:383 `buildDecisionPipeline` 开态构造、:448 注入位；
 * 类头 :141 明文该口径自 W1-DSTATE 起翻转，取代旧说「生产缺省不注入」）——生产装载成功的
 * mod 不走 UOE 降级分支；仅当宿主未注入管线（关态 = mod 根本不装载，生产不可达；台架/测试
 * 构造缺省 null）时 `context.actions` 是显式 fail-closed 桩 —— 桩异常原文进日志面 +
 * [arbitrationDegraded] 读数，装载继续。
 *
 * 事件读数走生成物 [LwEventFacade]：列名由生成物一处提供（本包不声明原型，读数挂在宿主
 * 实体上，故 Facade 只有 `of` 没有 `create`）。
 */
class EventMod : EraMod {

    private lateinit var context: ModContext

    /** 类型化读写面（onLoad 装配）。 */
    private lateinit var event: LwEventFacade

    /** 仲裁参与是否因宿主未注入管线而降级（与角色 Mod 同口径）。 */
    var arbitrationDegraded: Boolean = false
        private set

    /** provider 被收集过的轮数（判据 6 的 mod 存储对账凭据）。 */
    var eventRounds: Long = 0L
        private set

    override fun onLoad(context: ModContext) {
        this.context = context
        this.event = LwEventFacade(context.entities)
        eventRounds = context.storage.get(EventDeclarations.EVENT_ROUNDS)?.toLongOrNull() ?: 0L
    }

    override fun onEnable() {
        registerArbitrationParticipation()
    }

    override fun onDisable() {
        context.storage.put(EventDeclarations.EVENT_ROUNDS, eventRounds.toString())
    }

    override fun onUnload() {
        eventRounds = 0L
        arbitrationDegraded = false
    }

    /** 读一个实体的「事件发生过几次」读数（判据 4「各自字段」的读路径）。 */
    fun eventSeenOf(entity: EntityId): Long = event.of(entity).seen

    /** 读单向紧张度（本包自己的边型；与关系 Mod 的好感边互不覆盖）。 */
    fun tensionOf(from: EntityId, to: EntityId): Long = context.relationships.valueOf(
        from, EventDeclarations.TENSION, to,
    )

    /** 仲裁参与注册（provider + 约束 + handler；降级口径见类头 KDoc）。 */
    private fun registerArbitrationParticipation() {
        try {
            context.actions.provide(EventDeclarations.DOMAIN_EVENT, ::eventCandidates)
            // 茶会约束：主办方对宾客的好感（关系 Mod 的边）为正（跨包数据面组合的演示）。
            context.actions.constraint(EventDeclarations.CONSTRAINT_AFFINITY_POSITIVE) { constraintContext ->
                val guest = constraintContext.candidate.payloadRef ?: return@constraint false
                context.relationships.valueOf(
                    constraintContext.candidate.actor,
                    RelationshipDeclarations.AFFINITY,
                    guest,
                ) > 0L
            }
            context.actions.constraint(EventDeclarations.CONSTRAINT_ALWAYS_FALSE) { false }
            context.actions.constraint(EventDeclarations.CONSTRAINT_THROWING) {
                error("事件约束炸点（判据 7 定位演示）")
            }
            context.actions.on(EventMod.HANDLER_TEA) { applyOutcome(it, EventMod.TENSION_TEA) }
            context.actions.on(EventMod.HANDLER_GOSSIP) { applyOutcome(it, EventMod.TENSION_GOSSIP) }
            context.actions.on(EventMod.HANDLER_HAUNT) { applyOutcome(it, EventMod.TENSION_HAUNT) }
            // 打盹无后果写数；handler 存在性本身是「已注册处理器」闸的前提（未注册 = HANDLER_FAILED）。
            context.actions.on(EventMod.HANDLER_NAP) { }
        } catch (expected: UnsupportedOperationException) {
            // 与角色 Mod 同口径：fail-closed 桩异常原文进日志面，可观测降级。
            arbitrationDegraded = true
            context.logger.warn("决策管线未装配，仲裁参与降级：${expected.message}")
        }
    }

    /**
     * 事件候选集（类头 KDoc；全部手写常量，不做程序化内容生成）。候选带参与者：
     * actor = 主办方（固定取第一名 NPC），payloadRef = 宾客（第二名 NPC）。
     */
    private fun eventCandidates(selection: ActionSelectionContext): List<ActionCandidate> {
        eventRounds += 1L
        // 判据 7 的 provider 失败演示：metadata 点名时整轮 provider 失败（其余包不受影响）。
        if (selection.metadata[EventMod.METADATA_PROVIDER_BOOM] == "1") {
            error("事件 provider 炸点（判据 7 定位演示）")
        }
        val actors = context.entities.query(NpcKeys.archetype)
        if (actors.size < 2) return emptyList()
        val host = actors.first()
        val guest = actors.last()
        return when (selection.metadata[CharacterDeclarations.METADATA_SET]) {
            EventDeclarations.SET_COOLDOWN -> listOf(
                candidate(EventMod.HANDLER_HAUNT, host, guest)
                    .copy(cooldownKey = EventMod.COOLDOWN_HAUNT, cooldownDays = EventMod.HAUNT_COOLDOWN_DAYS),
            )
            EventDeclarations.SET_FAILURE -> listOf(
                candidate(EventMod.HANDLER_BOOM, host, guest)
                    .copy(constraints = listOf(EventDeclarations.CONSTRAINT_THROWING)),
                candidate(EventMod.HANDLER_GHOST, host, guest)
                    .copy(constraints = listOf(EventDeclarations.CONSTRAINT_MISSING)),
            )
            else -> listOf(
                candidate(EventMod.HANDLER_TEA, host, guest).copy(
                    weight = EventMod.WEIGHT_TEA,
                    priority = EventMod.PRIORITY_MAIN,
                    constraints = listOf(EventDeclarations.CONSTRAINT_AFFINITY_POSITIVE),
                ),
                candidate(EventMod.HANDLER_GOSSIP, host, guest).copy(priority = EventMod.PRIORITY_MAIN),
                candidate(EventMod.HANDLER_PRANK, host, guest).copy(
                    priority = EventMod.PRIORITY_MAIN,
                    constraints = listOf(EventDeclarations.CONSTRAINT_ALWAYS_FALSE),
                ),
                candidate(EventMod.HANDLER_NAP, host, guest).copy(priority = EventMod.PRIORITY_IDLE),
            )
        }
    }

    /** 手写候选构造（键即候选身份；变体经 ActionCandidate.copy 在调用点声明）。 */
    private fun candidate(handlerKey: String, host: EntityId, guest: EntityId): ActionCandidate =
        ActionCandidate(
            providerPackage = EventDeclarations.PACKAGE_ID,
            candidateKey = handlerKey,
            actor = host,
            payloadRef = guest,
            handlerKey = handlerKey,
        )

    /** 事件后果：主宾紧张度 +delta、主办方事件读数 +1（各自字段，不动关系 Mod 的边）。 */
    private fun applyOutcome(action: ActionContext, tensionDelta: Long) {
        val host = action.candidate.actor
        val guest = action.candidate.payloadRef
        val view = event.of(host)
        view.seen = view.seen + 1L
        if (guest != null && tensionDelta > 0L) {
            context.relationships.add(host, EventDeclarations.TENSION, guest)
            context.relationships.setValue(
                host, EventDeclarations.TENSION, guest,
                tensionOf(host, guest) + tensionDelta,
            )
        }
    }

    companion object {
        const val HANDLER_TEA: String = "lw.event.tea"
        const val HANDLER_GOSSIP: String = "lw.event.gossip"
        const val HANDLER_PRANK: String = "lw.event.prank"
        const val HANDLER_NAP: String = "lw.event.nap"
        const val HANDLER_HAUNT: String = "lw.event.haunt"
        const val HANDLER_BOOM: String = "lw.event.boom"
        const val HANDLER_GHOST: String = "lw.event.ghost"

        /** metadata 键：provider 失败演示开关（判据 7）。 */
        const val METADATA_PROVIDER_BOOM: String = "lw.providerBoom"

        /** 闹鬼的冷却桶键（引擎拼包命名空间；v1 不跨 Mod 共享冷却）。 */
        const val COOLDOWN_HAUNT: String = "lw.event.haunt"

        /** 闹鬼冷却天数。 */
        const val HAUNT_COOLDOWN_DAYS: Int = 3

        /** 茶会权重（同层加权抽取的确定性演示：3:1 对闲话）。 */
        const val WEIGHT_TEA: Int = 3

        /** 事件主层优先级。 */
        const val PRIORITY_MAIN: Int = 1

        /** 事件低层优先级（PRIORITY_LOST 演示）。 */
        const val PRIORITY_IDLE: Int = 0

        /** 各事件的单次紧张度增量（手写常量）。 */
        const val TENSION_TEA: Long = 1L
        const val TENSION_GOSSIP: Long = 2L
        const val TENSION_HAUNT: Long = 5L
    }
}
