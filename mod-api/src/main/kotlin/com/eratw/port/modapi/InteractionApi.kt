package com.eratw.port.modapi

/**
 * 多目标交互能力面（RT2 Kotlin Mod SDK，era-arch-v61u.md §20.3 / ABI-11 Candidate）。
 *
 * 原版互动是「玩家 → 单一 TARGET」；本面把参与者显式化成 [InteractionContext.targets]，
 * 引擎按五阶段执行序派发 Mod 钩子：
 *
 * ```
 * PRECONDITION → PRE → MAIN → POST → SETTLEMENT
 * ```
 *
 * **参与者解析归 Mod**（组件/关系追踪），引擎不认「母亲/子女」等业务角色，只给上下文结构
 * 与执行序。原版 TARGET 仍是兼容投影（§16 D-2 解析层），新 Mod 经 [InteractionContext.targets]
 * 访问参与者。
 *
 * ## 中断协议（clean 语义，§18.7）
 *
 * - [InteractionResult.CONTINUE]：继续；
 * - [InteractionResult.INTERRUPT]：中止本次互动管线（不跑 MAIN 及其后阶段）；
 * - [InteractionResult.REPLACE]：以 [InteractionHookContext.replacedResult] 替换主流程结果。
 *   PRE 阶段替换 ⇒ 跳过 MAIN 但继续 POST/SETTLEMENT；POST 阶段替换 ⇒ 改写结果供 SETTLEMENT。
 *
 * **不取消原版 ERB 逻辑**（ADR-005）：本面只作用于 Mod 自己的互动管线；原版流程替换经
 * Override + CALL SUPER（D-3）。
 *
 * ## 嵌套与失败恢复
 *
 * 互动中触发互动 = 上下文栈 push/pop（§19.4.2 契约）：内层帧独立、外层挂起保留；内层
 * 成功/异常返回后外层上下文逐字恢复；内层异常不泄漏内层 EntityId/PackageId。处理器失败
 * 按 Mod 隔离（§18.4），不拖垮外层与其他处理器。深度超限显式拒绝（不栈溢出）。
 */
interface InteractionApi {
    /**
     * 订阅一个交互阶段钩子（五阶段之一）。同一包对同一阶段可注册多个处理器（按注册序）。
     * 阶段须经本包 [ModManifest.interactionHooks] 声明（声明先于绑定，与 [HookApi.register]
     * 同纪律），未声明 = 拒绝。
     *
     * @throws IllegalArgumentException 该包对该阶段的钩子未声明
     */
    fun registerHook(phase: InteractionPhase, handler: InteractionHandler): Registration

    /**
     * 运行一次多目标互动：[main] 是主流程（通常是 Mod 口上/结算逻辑），返回最终结果。
     *
     * 当前 Mod 是本次互动的发起方；嵌套调用由引擎的上下文栈管理（§19.4.2）。
     */
    fun run(context: InteractionContext, main: () -> ModValue?): InteractionRunResult

    /**
     * 运行一段可挂起剧情。等待点复用引擎 INPUT/WAIT：构建后的剧情节点、已回答选择与恢复
     * 游标写入本包 `.rt2` 保留分片；读档后由宿主从该记录继续，只重印当前选择提示与选项，
     * 不重放已经输出的剧情前缀。
     *
     * 等待边界真源 = `core-vm` 的 `StatementExecutor.waitInput`（`StatementExecutor.kt:4166`）：
     * 与 INPUT 一样依次发布帧、冻结选项、阻塞于 [com.eratw.port.core.host.InputSink.waitInput]，
     * 交付后重新冻结帧；不另建轮询循环。
     *
     * 这是对 Kotlin 协程续体的显式可序列化替代：调用在玩家选择处阻塞，完成后才返回。
     * 作者不维护状态机，也不持有协程 scope；剧情文案统一经 [StringsApi] 声明。
     */
    fun runDurable(context: InteractionContext, story: DurableStory): InteractionRunResult =
        throw UnsupportedOperationException("当前交互宿主未装配可挂起剧情运行时")
}

/** 交互五阶段（映射引擎侧 `InteractionPhase`）。 */
enum class InteractionPhase { PRECONDITION, PRE, MAIN, POST, SETTLEMENT }

/** 交互钩子返回值（见接口 KDoc 的中断协议）。 */
enum class InteractionResult { CONTINUE, INTERRUPT, REPLACE }

/**
 * 一次多目标互动的上下文。[actor] 是发起者，[targets] 是显式参与者列表，
 * [primaryTarget] 是主目标（可为 null）；[metadata] 是 Mod 自定义的只读载荷
 * （引擎不解释其含义）。
 */
data class InteractionContext(
    val interactionId: String,
    val actor: EntityId,
    val targets: List<EntityId>,
    val primaryTarget: EntityId? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(interactionId.isNotBlank()) { "interactionId 不能为空" }
    }
}

/**
 * 交互钩子上下文。[replacedResult] 只在返回 [InteractionResult.REPLACE] 时被引擎读取；
 * 其余返回值下引擎忽略它。
 */
class InteractionHookContext(
    val packageId: String,
    val interaction: InteractionContext,
    val phase: InteractionPhase,
    var replacedResult: ModValue? = null,
)

/** 一个交互阶段处理器。 */
fun interface InteractionHandler {
    fun onInteraction(context: InteractionHookContext): InteractionResult
}

/**
 * 一次互动运行的结果。
 *
 * [failures] 是失败处理器归属的 PackageId 列表（按发生序，去重前）；失败按 Mod 隔离，
 * 不影响最终结果。
 */
class InteractionRunResult(
    val returnValue: ModValue?,
    val interrupted: Boolean,
    val replaced: Boolean,
    val failures: List<String>,
)
