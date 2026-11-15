package com.eratw.port.modapi

import java.util.concurrent.CancellationException

/**
 * 行动候选与统一仲裁契约（RT2 Kotlin Mod SDK，era-arch-v61u.md §21.3 / ABI-12 Candidate）。
 *
 * 引擎不建行为树、不建领域 AI（§6.1 禁令保留）；本面只补两个**通用原语**：候选声明
 * （[ActionCandidate]）与统一仲裁（引擎侧 `com.eratw.port.core.mod.DecisionPipeline`）。
 * 「做哪一件」由引擎按固定序裁决，「做什么」全归 Mod。
 *
 * ## 数据契约要点（本契约裁定，与架构 §21.3 草案的差异写在这里）
 *
 * - 架构草案只给 `cooldownKey` 不给 TTL 长度，不可执行 ⇒ 补 [ActionCandidate.cooldownDays]；
 *   两者必须**同时为空或同时非空**，且 `cooldownDays > 0`（半套声明即拒绝）。
 * - [ActionCandidate.weight] `> 0`、[ActionCandidate.priority] `>= 0`、
 *   `candidateKey`/`handlerKey`/`providerPackage` 非空、`perDay`/`perActorPerDay` 非空时 `> 0`。
 * - [ActionCandidate.providerPackage] 必须与注册该 provider 的包身份一致；不一致 = **provider
 *   失败**（整条 provider 本轮候选全丢 + 失败记录），引擎**不**静默改写归属（防跨 Mod 冒名）。
 *
 * ## 边界（诚实声明）
 *
 * - 本模块零 :core-vm 依赖：这里只有数据形态与接口，裁判语义（收集序/约束/冷却/限额/预留/
 *   优先级/加权抽取/报告）全在引擎侧。
 * - 引擎不认识 `domain`/`constraints` 键/`cooldownKey`/`handlerKey` 里的任何业务含义。
 * - `cooldownKey` 的命名空间由引擎拼成 `packageId + "::" + cooldownKey`：v1 **不支持跨 Mod
 *   共享冷却**（同一字符串在不同包内互不相干）。
 * - 决策随机流（§21.3.5）不消费原版 RAND/RANDDATA 序列；Mod 不得假设本面提供的任何顺序
 *   与标准库随机源或原版抽号有关。
 *
 * clean 语义（§18.7）：本面取干净语义，不复刻官方 bug；改游戏状态仍经
 * [RuntimeScheduler.submit]（§18.5），本面不提供线程保证。
 */

/**
 * 资源预留键：引擎只保证**同 kind + 同 key 互斥**，不解释 kind/key 的业务含义。
 *
 * 行动中的角色不得被换出（§7）由本原语自然表达：`chara:<entityId>` 预留即占槽。
 */
data class ReservationKey(
    val kind: String,
    val key: String,
) {
    init {
        require(kind.isNotBlank()) { "预留键 kind 不能为空" }
        require(key.isNotBlank()) { "预留键 key 不能为空" }
    }
}

/**
 * 一条行动候选（§21.3 数据契约 + 本契约裁定的字段校验）。
 *
 * @property providerPackage 声明本候选的包身份；必须与注册该 provider 的包一致（防冒名）。
 * @property candidateKey 包内唯一键（冷却/限额/诊断的桶键）。
 * @property actor 行动者实体。
 * @property priority 硬分层（降序）；只有最高层参与加权抽取。
 * @property weight 仅在最高优先级层内参与加权抽取；必须为正。
 * @property constraints 约束键列表（本包作用域内注册的纯谓词）；按列表序短路求值。
 * @property cooldownKey 冷却桶键（引擎不解释含义）；与 [cooldownDays] 同生共死。
 * @property cooldownDays 冷却天数（天粒度，> 0）；只在 COMPLETED 后记账。
 * @property perDay 每游戏日执行尝试上限（含失败/取消，防失败重试死循环）。
 * @property perActorPerDay 每行动者每游戏日执行尝试上限。
 * @property reservationClaims 预留声明；选中后原子占用，结束/失败/取消即释放。
 * @property handlerKey 处理器键（本包作用域内注册）。
 * @property payloadRef Mod 组件承载的业务参数引用（引擎不解释）。
 */
data class ActionCandidate(
    val providerPackage: String,
    val candidateKey: String,
    val actor: EntityId,
    val priority: Int = 0,
    val weight: Int = 1,
    val constraints: List<String> = emptyList(),
    val cooldownKey: String? = null,
    val cooldownDays: Int? = null,
    val perDay: Int? = null,
    val perActorPerDay: Int? = null,
    val reservationClaims: List<ReservationKey> = emptyList(),
    val handlerKey: String,
    val payloadRef: EntityId? = null,
) {
    init {
        require(providerPackage.isNotBlank()) { "候选 providerPackage 不能为空" }
        require(candidateKey.isNotBlank()) { "候选 candidateKey 不能为空" }
        require(handlerKey.isNotBlank()) { "候选 handlerKey 不能为空" }
        require(priority >= 0) { "候选 priority 不能为负：$priority" }
        require(weight > 0) { "候选 weight 必须为正：$weight" }
        require((cooldownKey == null) == (cooldownDays == null)) {
            "候选 cooldownKey 与 cooldownDays 必须同时为空或同时非空" +
                "（cooldownKey=$cooldownKey, cooldownDays=$cooldownDays）"
        }
        require(cooldownKey == null || cooldownKey.isNotBlank()) { "候选 cooldownKey 不能为空串" }
        require(cooldownDays == null || cooldownDays > 0) { "候选 cooldownDays 必须为正：$cooldownDays" }
        require(perDay == null || perDay > 0) { "候选 perDay 必须为正：$perDay" }
        require(perActorPerDay == null || perActorPerDay > 0) { "候选 perActorPerDay 必须为正：$perActorPerDay" }
        require(constraints.none { it.isBlank() }) { "候选 constraints 不能含空键" }
    }
}

/**
 * 一次仲裁的只读选择上下文。
 *
 * [domain] 区分行动（ACTION）与事件（EVENT）等候选域（§21.4：事件复用同一仲裁器，不建第二套
 * 派发器）；引擎只收该 domain 下注册的 provider 的候选。
 * [metadata] 是 Mod 自定义只读载荷，引擎不解释其含义。
 */
data class ActionSelectionContext(
    val domain: String,
    val time: GameTime,
    val actor: EntityId? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(domain.isNotBlank()) { "选择上下文 domain 不能为空" }
    }
}

/** 约束求值上下文（只读；[packageId] 是**候选**的包身份，谓词按该包作用域解析）。 */
class ActionConstraintContext(
    val packageId: String,
    val candidate: ActionCandidate,
    val time: GameTime,
)

/** Mod 提供的纯谓词约束：返回 false = 该候选本轮淘汰（reasonCode `CONSTRAINT_FALSE`）。 */
fun interface ActionConstraint {
    fun test(context: ActionConstraintContext): Boolean
}

/** 候选提供者：按 [ActionSelectionContext] 生成本轮候选；抛异常 = 本 provider 本轮失败。 */
fun interface ActionProvider {
    fun provide(context: ActionSelectionContext): List<ActionCandidate>
}

/** 处理器执行上下文。[cancellation] 是本次仲裁的中断令牌（引擎在处理器边界检查）。 */
class ActionContext(
    val packageId: String,
    val candidate: ActionCandidate,
    val time: GameTime,
    val cancellation: CancellationToken,
)

/** Mod 处理器：一个候选最多被执行**一次**（引擎不内置重试策略，策略是业务）。 */
fun interface ActionHandler {
    fun execute(context: ActionContext)
}

/**
 * 决策中断令牌（引擎在处理器边界检查；§21.3 中断协议）。
 *
 * 中断请求在当前**原子段**结束后生效；引擎**不回滚已发生的组件写入**（vanilla 无通用回滚，
 * 需要原子性的 Mod 自备写前快照）。本类线程安全（标志位 volatile），是仲裁管线里唯一的
 * 共享可变点。
 */
class CancellationToken {
    /** 是否已请求取消。 */
    @Volatile
    var isCancelled: Boolean = false
        private set

    /** 请求取消（幂等）。 */
    fun requestCancel() {
        isCancelled = true
    }

    /**
     * 已请求取消则抛 [CancellationException]，否则什么也不做。
     *
     * @throws CancellationException 已请求取消
     */
    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("决策处理器已被取消")
    }
}

/**
 * 行动/事件统一仲裁面（引擎侧 `DecisionPipeline.actionApi(packageId)` 提供包作用域 facade）。
 *
 * 三个注册入口都按**包身份**注入（同 §20 facade 适配器口径）：provider 的候选、handler、
 * constraint 都只在注册包的作用域内被解析，跨包引用 = 未注册。
 */
interface ActionApi {
    /** 为 [domain] 注册一个候选提供者；返回可撤销注册（onDisable 用）。 */
    fun provide(domain: String, provider: ActionProvider): Registration

    /** 注册处理器 [handlerKey]（本包作用域）；返回可撤销注册。 */
    fun on(handlerKey: String, handler: ActionHandler): Registration

    /** 注册约束谓词 [key]（本包作用域）；返回可撤销注册。 */
    fun constraint(key: String, predicate: ActionConstraint): Registration
}