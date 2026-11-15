package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.modapi.ActionApi
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionConstraint
import com.eratw.port.modapi.ActionConstraintContext
import com.eratw.port.modapi.ActionContext
import com.eratw.port.modapi.ActionHandler
import com.eratw.port.modapi.ActionProvider
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.CancellationToken
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.ReservationKey
import java.security.MessageDigest

/**
 * 决策抽取键（§21.3.5 可复现性口径）：同 seed + 同键 ⇒ 同抽取结果。
 *
 * [candidateKeys] 是最高优先级层**已定序**的候选键列表；[nth] 是同层第 nth 次抽取
 * （生产路径由管线自持计数，测试路径由 [DecisionPipeline.of] 的 `arbitrationOrdinal` 供给）。
 */
data class DecisionDrawKey(
    val day: Long,
    val phase: String,
    val domain: String,
    val candidateKeys: List<String>,
    val nth: Int,
)

/** 决策随机流注入点：生产缺省 [Sha256DecisionRandom]，测试可注入 spy 断言派生键。 */
interface DecisionRandomSource {
    /**
     * 在 [bound] 上抽一个值（必须落在 0 到 bound 减一）。
     *
     * @throws IllegalArgumentException bound 非正
     */
    fun draw(seed: Long, key: DecisionDrawKey, bound: Long): Long
}

/**
 * SHA-256 派生 + **每次新建** 独立 SFMT19937 实例的决策随机流（§21.3.5）。
 *
 * 派生材料 = `decisionSeed`、`day`、`phase`、`domain`、`nth`、`candidateKeys`（按已定序），
 * 逐段用 U+001F 分隔拼成 UTF-8 串，取 SHA-256 摘要**前 4 字节按大端**解释为 UInt32
 * （0..4294967295，Long 承载），再以之播种一个**新的** [Sfmt19937] 并取 `next(bound)`。
 *
 * 铁律（§21.3.5 决策随机流独立性）：
 *
 * - 每次 [draw] 都 `new Sfmt19937`，**不持有、不复用**实例——复用会让「同一键」在不同调用
 *   次序下出不同值，可复现性当场失效；
 * - 不消费原版 `RAND`/`RANDDATA` 序列，也不共享任何 `RandProvider` 实例（ADR-005 对拍口径）。
 *
 * 本对象无状态（无实例字段、无可变全局），故可安全作为缺省值。
 */
object Sha256DecisionRandom : DecisionRandomSource {
    /** 派生材料分段符（U+001F，Unit Separator）。 */
    private const val SEPARATOR = '\u001F'

    override fun draw(seed: Long, key: DecisionDrawKey, bound: Long): Long {
        require(bound > 0L) { "决策抽取上界必须为正：$bound" }
        val material = buildString {
            append(seed)
            append(SEPARATOR)
            append(key.day)
            append(SEPARATOR)
            append(key.phase)
            append(SEPARATOR)
            append(key.domain)
            append(SEPARATOR)
            append(key.nth)
            for (candidateKey in key.candidateKeys) {
                append(SEPARATOR)
                append(candidateKey)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        var seed32 = 0L
        for (i in 0 until 4) seed32 = (seed32 shl 8) or (digest[i].toLong() and 0xFFL)
        // 每次调用新建实例：不得持有或复用（见本对象 KDoc 铁律）。
        return Sfmt19937(seed32).next(bound)
    }
}

/** 淘汰/中断原因码（§21.6 诊断面口径；取值 = 同名字符串，不本地化、不加前缀）。 */
object DecisionReasonCodes {
    const val CONSTRAINT_FALSE: String = "CONSTRAINT_FALSE"
    const val COOLDOWN: String = "COOLDOWN"
    const val LIMIT_DAY: String = "LIMIT_DAY"
    const val LIMIT_ACTOR: String = "LIMIT_ACTOR"
    const val RESERVATION_CONFLICT: String = "RESERVATION_CONFLICT"
    const val PRIORITY_LOST: String = "PRIORITY_LOST"
    const val HANDLER_FAILED: String = "HANDLER_FAILED"
    const val CANCELED: String = "CANCELED"

    /** 没有可执行候选时的终局原因（契约 G2 指定字面量）。 */
    const val NO_CANDIDATES: String = "NO_CANDIDATES"
}

/** 一条候选的淘汰记录（[reasonCode] 取 [DecisionReasonCodes]；[detail] 是可选诊断细节）。 */
class DecisionElimination(
    val packageId: String,
    val candidateKey: String,
    val reasonCode: String,
    val detail: String? = null,
)

/** 失败发生的阶段（provider 收集 / 约束求值 / 处理器执行）。 */
enum class DecisionFailurePhase { PROVIDER, CONSTRAINT, HANDLER }

/** 一次失败记录（PackageId 归属 + 阶段 + 原始异常，供失败隔离与诊断）。 */
class DecisionFailure(
    val packageId: String,
    val key: String,
    val phase: DecisionFailurePhase,
    val cause: Throwable,
)

/** 一次仲裁的执行终局。 */
enum class ActionExecutionOutcome { NONE, COMPLETED, CANCELED, FAILED }

/**
 * 一次仲裁报告（§21.6 可解释性：每条被淘汰候选都有原因码）。
 *
 * @property evaluated 进入仲裁的候选条数（provider 归属校验通过后、去重前）。
 * @property eliminations 淘汰记录（顺序 = 仲裁阶段序 + 阶段内候选收集序）。
 * @property chosen 选中候选；无候选或全部淘汰时为 null。
 * @property chosenPackageId 选中候选的包身份；与 [chosen] 同空同非空。
 * @property outcome 执行终局；[ActionExecutionOutcome.NONE] = 没有执行任何处理器。
 * @property failures 失败记录（provider/约束/处理器，按发生序）。
 * @property reservationsReleased 本轮实际释放的预留键（保序；无预留时为空）。
 */
class DecisionReport(
    val domain: String,
    val evaluated: Int,
    val eliminations: List<DecisionElimination>,
    val chosen: ActionCandidate?,
    val chosenPackageId: String?,
    val outcome: ActionExecutionOutcome,
    val failures: List<DecisionFailure>,
    val reservationsReleased: List<ReservationKey>,
)
/**
 * 统一决策/仲裁核心（era-arch-v61u.md §21.3 / ABI-12 Candidate；纯 JVM，无静态可变全局）。
 *
 * 事件（§21.4）与行动共用同一仲裁器：`context.domain` 区分候选域，引擎不建第二套派发器、
 * 不建事件队列。
 *
 * ## 固定仲裁序（不得重排；淘汰顺序 = 本序逐阶段追加）
 *
 * ```
 * 收集候选（包链接序 + 包内注册序；只收 context.domain）
 *   → 去重（同 packageId+candidateKey 只留首个；后者 PRIORITY_LOST + detail=duplicate）
 *   → 约束过滤（按 constraints 列表序短路）
 *   → 冷却 → 每日限额 → 每行动者每日限额
 *   → 预留可行性 → 处理器已注册（未注册 = HANDLER_FAILED，不占预留）
 *   → 优先级分层（仅最高层参与加权抽取；非最高层 PRIORITY_LOST）
 *   → 最高层内按 weight 加权抽取（决策随机流 §21.3.5）
 *   → 执行处理器（唯一一次）→ 释放本轮预留 → 记账（限额、冷却）
 * ```
 *
 * 同一条候选每阶段最多一条淘汰记录（短路）；`eliminations` 顺序 = 上述阶段序 + 各阶段内的
 * 候选收集序。
 *
 * ## 记账口径
 *
 * - `perDay`/`perActorPerDay` 统计**执行尝试**（COMPLETED / FAILED / CANCELED 都计入，防
 *   失败重试死循环）；按 `time.day` 分桶，换日自动失效（旧日桶保留不清理，清理与持久化
 *   归 M6-2）。
 * - `cooldownKey` 只在 **COMPLETED** 后置为 `time.day + cooldownDays`；`day < cooldownUntil`
 *   即淘汰（COOLDOWN）。
 * - 冷却键命名空间 = `packageId + "::" + cooldownKey`；引擎不解释含义，v1 **不支持跨 Mod
 *   共享冷却**。
 *
 * ## 预留
 *
 * 选中后**原子地**把全部 claims 加入 held（去重、保序）；无论 COMPLETED / FAILED / CANCELED
 * 都在 finally 释放。[heldReservations] 是只读快照（M6-2 持久化入口）。两个候选在**同一轮**
 * 抢同一键不互斥——只有 held 集合里的键算占用。
 *
 * ## 中断
 *
 * [run] 每次创建 [CancellationToken] 放进 [ActionContext]；处理器返回后若已请求取消 ⇒
 * `outcome=CANCELED`（引擎只在处理器边界检查，不抢占原子段，**不回滚已发生的组件写入**）。
 * 处理器抛异常 ⇒ `outcome=FAILED` + [DecisionFailurePhase.HANDLER] 失败记录 + 释放预留 +
 * 限额照记。
 *
 * ## 旁路与线程
 *
 * 无 provider / 无候选 / 全部淘汰 ⇒ `chosen=null`、`outcome=NONE`、不调处理器、不建任何
 * 预留（「无候选完全旁路，golden 零变化」的引擎侧判据）。实例**非线程安全**（§18.5 主执行
 * 线程单线程）；[CancellationToken] 除外。状态全挂实例，无静态可变全局。
 *
 * M6-2 已落：决策种子派生（[DecisionSeed]）、生产路径抽取序号自持、限额/冷却/预留的
 * 状态快照与恢复（[snapshotState] / [restoreState]），二进制编解码见 [DecisionStateCodec]；
 * `.rt2` sidecar 分段装配仍归宿主接线（M4-3/K3）。W2-SEED 增补：种子随档恢复的受控
 * 供给缝 [supplySeed]（一次性 + 仅未消费状态，fail-closed），相等闸语义不动。
 */

/** 一条每日执行计数（[DecisionState] 的持久化单元；键 = 包 + 候选 + 游戏日）。 */
data class DayCountEntry(
    val packageId: String,
    val candidateKey: String,
    val day: Long,
    val count: Int,
)

/** 一条每行动者每日执行计数（[DecisionState] 的持久化单元）。 */
data class ActorCountEntry(
    val packageId: String,
    val candidateKey: String,
    val day: Long,
    val actorRaw: Long,
    val count: Int,
)

/** 一条冷却记录；[scopedKey] 是引擎内部桶键（已带包命名空间），[untilDay] 为到期日。 */
data class CooldownEntry(
    val scopedKey: String,
    val untilDay: Long,
)

/**
 * 决策管线可持久化状态（M6-2；§21.3.5 决策随机流 + 预留/冷却/限额记账）。
 *
 * 字段与 [DecisionStateCodec] 的段序一一对应；列表是只读快照，确定性排序由编解码器统一保证。
 */
data class DecisionState(
    val decisionSeed: Long,
    val nextOrdinal: Int,
    val dayCounts: List<DayCountEntry>,
    val actorCounts: List<ActorCountEntry>,
    val cooldownUntil: List<CooldownEntry>,
    val heldReservations: List<ReservationKey>,
)

/** [DecisionPipeline.restoreState] 的报告：被回收的陈旧预留键（保序、去重）。 */
class DecisionRestoreReport(
    val droppedStaleReservations: List<ReservationKey>,
)

/**
 * 决策种子派生（§21.3.5）：对宿主熵取 SHA-256，取前 8 字节按大端解释为 Long；0 记为 1。
 *
 * 纯函数、无静态可变全局；同一熵必得同一种子（跨存读档可复现）。
 */
object DecisionSeed {
    fun derive(entropy: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
        var seed = 0L
        for (i in 0 until 8) seed = (seed shl 8) or (digest[i].toLong() and 0xFFL)
        return if (seed == 0L) 1L else seed
    }
}

class DecisionPipeline private constructor(
    private val packageOrder: List<String>,
    /**
     * 决策种子（W2-SEED 起为 var）：构造期由 `DecisionSeed.derive(宿主熵)` 定初值，此后只有
     * [supplySeed] 这一条受控改写缝（一次性 + 仅未消费状态，fail-closed）；[restoreState]
     * 的种子相等闸语义不变（对照当前值 fail-closed）。
     */
    private var decisionSeed: Long,
    private val random: DecisionRandomSource,
    private val arbitrationOrdinal: (() -> Int)?,
    private val diagnostics: RuntimeDiagnostics? = null,
) {
    /** 一条 provider 注册（[seq] = 包内注册序，决定收集序）。 */
    private class ProviderEntry(
        val packageId: String,
        val domain: String,
        val provider: ActionProvider,
        val seq: Int,
    )

    /** 一条已收集候选（[collectIndex] = 收集序，定序与诊断的稳定基准）。 */
    private class Collected(
        val packageId: String,
        val candidate: ActionCandidate,
        val collectIndex: Int,
    )

    /** 一条通过可行性阶段且已解析到处理器的执行单元。 */
    private class Executable(
        val collected: Collected,
        val handler: ActionHandler,
    )

    /** 每日计数桶键：包 + 候选 + 游戏日（结构化键，不拼串，避免分隔符撞车）。 */
    private data class DayCountKey(
        val packageId: String,
        val candidateKey: String,
        val day: Long,
    )

    /** 每行动者计数桶键：包 + 候选 + 游戏日 + 行动者实体原值。 */
    private data class ActorCountKey(
        val packageId: String,
        val candidateKey: String,
        val day: Long,
        val actorRaw: Long,
    )

    /**
     * 生产路径（[arbitrationOrdinal] 为 null）自持的抽取序号：每次最高层加权抽取前取当前值再 +1。
     *
     * 测试注入 lambda 时不推进（波 E 语义原样），快照里原样返回。
     */
    private var nextOrdinal: Int = 0

    /**
     * 档内种子是否已被 [supplySeed] 采纳过（W2-SEED 一次性闸）：一个管线实例只许采纳一次，
     * 防止「采纳 A 档种子 → 回落空态 → 再采纳 B 档种子」的种子漂移。
     */
    private var seedSupplied: Boolean = false

    private val packageRank: Map<String, Int> =
        packageOrder.withIndex().associate { (index, pkg) -> pkg to index }

    private val providers = ArrayList<ProviderEntry>()
    private val handlers = LinkedHashMap<String, ActionHandler>()
    private val constraints = LinkedHashMap<String, ActionConstraint>()
    private val nextProviderSeq = HashMap<String, Int>()

    private val held = LinkedHashSet<ReservationKey>()
    private val cooldownUntil = HashMap<String, Long>()
    private val dayCounts = HashMap<DayCountKey, Int>()
    private val actorCounts = HashMap<ActorCountKey, Int>()

    /**
     * 本包作用域的 [ActionApi] facade（编译期形状闸 + 运行期按包隔离注册）。
     *
     * 三个注册入口都绑定 [packageId]：候选归属、handler 解析、constraint 解析都只在该包作用域
     * 内生效（跨包引用 = 未注册，防跨 Mod 冒名）。
     *
     * @throws IllegalArgumentException packageId/domain/handlerKey/约束键为空，或 handler/约束
     *         在同包内重复注册
     */
    fun actionApi(packageId: String): ActionApi {
        require(packageId.isNotBlank()) { "actionApi 的 packageId 不能为空" }
        return object : ActionApi {
            override fun provide(domain: String, provider: ActionProvider): Registration {
                require(domain.isNotBlank()) { "provider 的 domain 不能为空（$packageId）" }
                val seq = nextProviderSeq[packageId] ?: 0
                nextProviderSeq[packageId] = seq + 1
                val entry = ProviderEntry(packageId, domain, provider, seq)
                providers += entry
                return registration { providers.remove(entry) }
            }

            override fun on(handlerKey: String, handler: ActionHandler): Registration {
                require(handlerKey.isNotBlank()) { "handlerKey 不能为空（$packageId）" }
                val scoped = registryKey(packageId, handlerKey)
                require(scoped !in handlers) { "handler 重复注册：$packageId/$handlerKey" }
                handlers[scoped] = handler
                return registration { handlers.remove(scoped) }
            }

            override fun constraint(key: String, predicate: ActionConstraint): Registration {
                require(key.isNotBlank()) { "约束键不能为空（$packageId）" }
                val scoped = registryKey(packageId, key)
                require(scoped !in constraints) { "约束重复注册：$packageId/$key" }
                constraints[scoped] = predicate
                return registration { constraints.remove(scoped) }
            }
        }
    }

    /** 当前占用中的预留键只读快照（保序；M6-2 持久化入口）。 */
    fun heldReservations(): Set<ReservationKey> = LinkedHashSet(held)

    /** 当前决策种子读数（W2-SEED：读档缝用它判「档内种子 ≠ 管线种子」再决定是否供给）。 */
    val currentDecisionSeed: Long get() = decisionSeed

    /**
     * 受控种子供给（W2-SEED 判据 2「种子随档」的管线侧缝）：读档缝在 [restoreState] **之前**
     * 把档内决策段的 `decisionSeed` 采纳进管线，使相等闸自然通过。仲裁算法与 [restoreState]
     * 的相等闸语义都不动——错的是供给序，不是闸。
     *
     * fail-closed 三闸（任一不满足原样抛 [IllegalArgumentException]，读档缝既有 catch 收警告）：
     *
     * - **一次性**：本实例已采纳过（[seedSupplied]）⇒ 拒。种子是随机流的根身份，二次改写
     *   会让「同档重放逐位一致」（§21.3.5）失去锚点；
     * - **仅未消费状态**：抽取序号已推进 / 计数 / 冷却 / 预留任一非空 ⇒ 拒——在已消费的
     *   随机流上换种子 = 状态与种子错配。供给成功后 [restoreState] 才写入持久态，两者
     *   之间没有消费点（供给前实例必为全空 ⇒ [restoreState] 中途失败也不会留下
     *   「新种子 + 旧计数」的中间态）；
     * - 档内种子与当前种子相等时**不调本方法**（调用方先经 [currentDecisionSeed] 判等，
     *   相等走原 restoreState 闸放行，本方法不承担 no-op 语义）。
     *
     * 生产调用点唯一：app 装配层读档缝（ModSidecarStoragePort.restoreRt2 ← ModDecisionJobState
     * 的采纳序）；测试可直调本方法做闸面定向。
     *
     * @throws IllegalArgumentException 已采纳过 / 状态已消费
     */
    fun supplySeed(seed: Long) {
        require(!seedSupplied) {
            "决策种子已随档采纳过（一次性），拒绝再供给：现=$decisionSeed 请求=$seed（fail-closed）"
        }
        require(decisionStateUnconsumed()) {
            "决策态已消费（抽取序号/计数/冷却/预留非空），拒绝改种子：现=$decisionSeed 请求=$seed（fail-closed）"
        }
        decisionSeed = seed
        seedSupplied = true
    }

    /** 未消费判定（[supplySeed] 的闸面）：抽取序号未推进且计数/冷却/预留四桶全空。 */
    private fun decisionStateUnconsumed(): Boolean =
        nextOrdinal == 0 &&
            dayCounts.isEmpty() &&
            actorCounts.isEmpty() &&
            cooldownUntil.isEmpty() &&
            held.isEmpty()

    /**
     * 当前决策态快照（M6-2 持久化入口；交给 [DecisionStateCodec] 编成 `.rt2` 段）。
     *
     * 列表一律深拷贝并按确定性键排序（计数按包/候选/日，冷却按桶键，预留按 kind+key）；快照与
     * 实例隔离：之后继续 [run] 或 [restoreState] 都不会改动已返回的快照。
     */
    fun snapshotState(): DecisionState = DecisionState(
        decisionSeed = decisionSeed,
        nextOrdinal = nextOrdinal,
        dayCounts = dayCounts.entries
            .map { (key, count) -> DayCountEntry(key.packageId, key.candidateKey, key.day, count) }
            .sortedWith(compareBy({ it.packageId }, { it.candidateKey }, { it.day })),
        actorCounts = actorCounts.entries
            .map { (key, count) ->
                ActorCountEntry(key.packageId, key.candidateKey, key.day, key.actorRaw, count)
            }
            .sortedWith(
                compareBy({ it.packageId }, { it.candidateKey }, { it.day }, { it.actorRaw }),
            ),
        cooldownUntil = cooldownUntil.entries
            .map { (key, until) -> CooldownEntry(key, until) }
            .sortedBy { it.scopedKey },
        heldReservations = held.sortedWith(compareBy({ it.kind }, { it.key })),
    )

    /**
     * 用持久态覆盖实例内的计数/冷却/预留/抽取序号（种子必须相等，不等即 fail-closed）。
     *
     * [reservationAlive] 由宿主提供实体存活判定：返回 false 的预留键被丢弃并进
     * [DecisionRestoreReport.droppedStaleReservations]（陈旧预留回收；实体校验不归本层）。
     *
     * @throws IllegalArgumentException 种子不符、nextOrdinal 为负、计数为负、键为空或重复
     */
    fun restoreState(
        state: DecisionState,
        reservationAlive: (ReservationKey) -> Boolean = { true },
    ): DecisionRestoreReport {
        require(state.decisionSeed == decisionSeed) {
            "决策态种子不符：实例=$decisionSeed 快照=${state.decisionSeed}（fail-closed）"
        }
        require(state.nextOrdinal >= 0) { "决策态 nextOrdinal 不能为负：${state.nextOrdinal}" }
        val restoredDayCounts = LinkedHashMap<DayCountKey, Int>(state.dayCounts.size * 2)
        for (entry in state.dayCounts) {
            require(entry.packageId.isNotBlank()) { "决策态 dayCounts 包名为空" }
            require(entry.candidateKey.isNotBlank()) { "决策态 dayCounts 候选键为空" }
            require(entry.count >= 0) { "决策态 dayCounts 计数不能为负：${entry.count}" }
            val key = DayCountKey(entry.packageId, entry.candidateKey, entry.day)
            require(restoredDayCounts.put(key, entry.count) == null) {
                "决策态 dayCounts 键重复：${entry.packageId}/${entry.candidateKey}@${entry.day}"
            }
        }
        val restoredActorCounts = LinkedHashMap<ActorCountKey, Int>(state.actorCounts.size * 2)
        for (entry in state.actorCounts) {
            require(entry.packageId.isNotBlank()) { "决策态 actorCounts 包名为空" }
            require(entry.candidateKey.isNotBlank()) { "决策态 actorCounts 候选键为空" }
            require(entry.count >= 0) { "决策态 actorCounts 计数不能为负：${entry.count}" }
            val key = ActorCountKey(entry.packageId, entry.candidateKey, entry.day, entry.actorRaw)
            require(restoredActorCounts.put(key, entry.count) == null) {
                "决策态 actorCounts 键重复：${entry.packageId}/${entry.candidateKey}" +
                    "@${entry.day}/${entry.actorRaw}"
            }
        }
        val restoredCooldown = LinkedHashMap<String, Long>(state.cooldownUntil.size * 2)
        for (entry in state.cooldownUntil) {
            require(entry.scopedKey.isNotBlank()) { "决策态冷却键为空" }
            require(restoredCooldown.put(entry.scopedKey, entry.untilDay) == null) {
                "决策态冷却键重复：${entry.scopedKey}"
            }
        }
        val restoredHeld = LinkedHashSet<ReservationKey>()
        val dropped = ArrayList<ReservationKey>()
        for (key in state.heldReservations) {
            if (reservationAlive(key)) {
                restoredHeld += key
            } else if (key !in dropped) {
                dropped += key
            }
        }
        dayCounts.clear()
        dayCounts.putAll(restoredDayCounts)
        actorCounts.clear()
        actorCounts.putAll(restoredActorCounts)
        cooldownUntil.clear()
        cooldownUntil.putAll(restoredCooldown)
        held.clear()
        held.addAll(restoredHeld)
        nextOrdinal = state.nextOrdinal
        return DecisionRestoreReport(dropped.toList())
    }

    /**
     * 跑一轮仲裁并返回报告。
     *
     * 失败隔离：单个 provider 抛异常、单个约束抛异常、单个 handler 抛异常都只进报告的
     * `failures`，不拖垮同轮其余候选/处理器。
     *
     * @throws IllegalStateException 注入的 [DecisionRandomSource] 返回越界值（fail-closed）
     */
    fun run(context: ActionSelectionContext): DecisionReport {
        val eliminations = ArrayList<DecisionElimination>()
        val failures = ArrayList<DecisionFailure>()

        val collected = collect(context, failures)
        val evaluated = collected.size

        var survivors = dedupe(collected, eliminations)
        survivors = applyConstraints(survivors, context, eliminations, failures)
        survivors = applyCooldown(survivors, context.time, eliminations)
        survivors = applyDayLimit(survivors, context.time, eliminations)
        survivors = applyActorLimit(survivors, context.time, eliminations)
        survivors = applyReservationFeasibility(survivors, eliminations)
        val executable = resolveHandlers(survivors, eliminations, failures)

        if (executable.isEmpty()) {
            return report(context, evaluated, eliminations, null, ActionExecutionOutcome.NONE, failures, emptyList())
        }

        val maxPriority = executable.maxOf { it.collected.candidate.priority }
        val tier = executable
            .filter { it.collected.candidate.priority == maxPriority }
            .sortedWith(
                compareBy(
                    { rankOf(it.collected.packageId) },
                    { it.collected.packageId },
                    { it.collected.collectIndex },
                    { it.collected.candidate.candidateKey },
                ),
            )
        for (unit in executable) {
            if (unit.collected.candidate.priority >= maxPriority) continue
            eliminations += DecisionElimination(
                unit.collected.packageId,
                unit.collected.candidate.candidateKey,
                DecisionReasonCodes.PRIORITY_LOST,
            )
        }

        val totalWeight = tier.sumOf { it.collected.candidate.weight.toLong() }
        val drawKey = DecisionDrawKey(
            day = context.time.day,
            phase = context.time.phase,
            domain = context.domain,
            candidateKeys = tier.map { it.collected.candidate.candidateKey },
            nth = nextArbitrationOrdinal(),
        )
        val draw = random.draw(decisionSeed, drawKey, totalWeight)
        if (draw < 0L || draw >= totalWeight) {
            throw IllegalStateException("决策随机源越界：draw=$draw 不在 0..${totalWeight - 1}（fail-closed）")
        }
        var cumulative = 0L
        var winner: Executable? = null
        for (unit in tier) {
            cumulative += unit.collected.candidate.weight.toLong()
            if (draw < cumulative) {
                winner = unit
                break
            }
        }
        val chosenUnit = winner
            ?: throw IllegalStateException("决策随机源抽取未命中任何候选（draw=$draw, total=$totalWeight）")
        for (unit in tier) {
            if (unit === chosenUnit) continue
            eliminations += DecisionElimination(
                unit.collected.packageId,
                unit.collected.candidate.candidateKey,
                DecisionReasonCodes.PRIORITY_LOST,
            )
        }

        val chosen = chosenUnit.collected
        val candidate = chosen.candidate
        val claims = LinkedHashSet(candidate.reservationClaims)
        val token = CancellationToken()
        val released = ArrayList<ReservationKey>()
        var outcome = ActionExecutionOutcome.FAILED
        try {
            for (claim in claims) held += claim
            try {
                chosenUnit.handler.execute(ActionContext(chosen.packageId, candidate, context.time, token))
                outcome = if (token.isCancelled) {
                    ActionExecutionOutcome.CANCELED
                } else {
                    ActionExecutionOutcome.COMPLETED
                }
            } catch (t: Throwable) {
                failures += DecisionFailure(
                    chosen.packageId,
                    candidate.candidateKey,
                    DecisionFailurePhase.HANDLER,
                    t,
                )
                outcome = ActionExecutionOutcome.FAILED
            }
        } finally {
            for (claim in claims) {
                if (held.remove(claim)) released += claim
            }
        }
        recordAttempt(chosen, context.time)
        val completedCooldownKey = candidate.cooldownKey
        if (outcome == ActionExecutionOutcome.COMPLETED && completedCooldownKey != null) {
            val days = candidate.cooldownDays ?: 0
            cooldownUntil[cooldownBucket(chosen.packageId, completedCooldownKey)] = context.time.day + days
        }
        return report(context, evaluated, eliminations, chosenUnit, outcome, failures, released)
    }
    private fun collect(
        context: ActionSelectionContext,
        failures: MutableList<DecisionFailure>,
    ): List<Collected> {
        val snapshot = providers
            .filter { it.domain == context.domain }
            .sortedWith(compareBy({ rankOf(it.packageId) }, { it.packageId }, { it.seq }))
        val collected = ArrayList<Collected>()
        for (entry in snapshot) {
            val produced = try {
                entry.provider.provide(context)
            } catch (t: Throwable) {
                failures += DecisionFailure(entry.packageId, entry.domain, DecisionFailurePhase.PROVIDER, t)
                continue
            }
            val spoofed = produced.firstOrNull { it.providerPackage != entry.packageId }
            if (spoofed != null) {
                failures += DecisionFailure(
                    entry.packageId,
                    entry.domain,
                    DecisionFailurePhase.PROVIDER,
                    IllegalArgumentException(
                        "候选归属冒名：包 ${entry.packageId} 的 provider 返回了 " +
                            "providerPackage=${spoofed.providerPackage} 的候选 ${spoofed.candidateKey}" +
                            "（整条 provider 本轮丢弃）",
                    ),
                )
                continue
            }
            for (candidate in produced) {
                collected += Collected(entry.packageId, candidate, collected.size)
            }
        }
        return collected
    }

    private fun dedupe(
        collected: List<Collected>,
        eliminations: MutableList<DecisionElimination>,
    ): List<Collected> {
        val seen = HashSet<String>()
        val kept = ArrayList<Collected>(collected.size)
        for (unit in collected) {
            val scoped = registryKey(unit.packageId, unit.candidate.candidateKey)
            if (seen.add(scoped)) {
                kept += unit
            } else {
                eliminations += DecisionElimination(
                    unit.packageId,
                    unit.candidate.candidateKey,
                    DecisionReasonCodes.PRIORITY_LOST,
                    "duplicate",
                )
            }
        }
        return kept
    }

    private fun applyConstraints(
        candidates: List<Collected>,
        context: ActionSelectionContext,
        eliminations: MutableList<DecisionElimination>,
        failures: MutableList<DecisionFailure>,
    ): List<Collected> {
        val kept = ArrayList<Collected>(candidates.size)
        for (unit in candidates) {
            var eliminated = false
            for (constraintKey in unit.candidate.constraints) {
                val predicate = constraints[registryKey(unit.packageId, constraintKey)]
                if (predicate == null) {
                    eliminations += DecisionElimination(
                        unit.packageId,
                        unit.candidate.candidateKey,
                        DecisionReasonCodes.CONSTRAINT_FALSE,
                        "unregistered constraint: $constraintKey",
                    )
                    eliminated = true
                    break
                }
                val passed = try {
                    predicate.test(ActionConstraintContext(unit.packageId, unit.candidate, context.time))
                } catch (t: Throwable) {
                    failures += DecisionFailure(
                        unit.packageId,
                        unit.candidate.candidateKey,
                        DecisionFailurePhase.CONSTRAINT,
                        t,
                    )
                    eliminations += DecisionElimination(
                        unit.packageId,
                        unit.candidate.candidateKey,
                        DecisionReasonCodes.CONSTRAINT_FALSE,
                    )
                    eliminated = true
                    break
                }
                if (!passed) {
                    eliminations += DecisionElimination(
                        unit.packageId,
                        unit.candidate.candidateKey,
                        DecisionReasonCodes.CONSTRAINT_FALSE,
                    )
                    eliminated = true
                    break
                }
            }
            if (!eliminated) kept += unit
        }
        return kept
    }
    private fun applyCooldown(
        candidates: List<Collected>,
        time: GameTime,
        eliminations: MutableList<DecisionElimination>,
    ): List<Collected> {
        val kept = ArrayList<Collected>(candidates.size)
        for (unit in candidates) {
            val cooldownKey = unit.candidate.cooldownKey
            val until = cooldownKey?.let { cooldownUntil[cooldownBucket(unit.packageId, it)] }
            if (until != null && time.day < until) {
                eliminations += DecisionElimination(
                    unit.packageId,
                    unit.candidate.candidateKey,
                    DecisionReasonCodes.COOLDOWN,
                    "cooldown until day $until",
                )
            } else {
                kept += unit
            }
        }
        return kept
    }

    private fun applyDayLimit(
        candidates: List<Collected>,
        time: GameTime,
        eliminations: MutableList<DecisionElimination>,
    ): List<Collected> {
        val kept = ArrayList<Collected>(candidates.size)
        for (unit in candidates) {
            val limit = unit.candidate.perDay
            val used = dayCount(unit, time.day)
            if (limit != null && used >= limit) {
                eliminations += DecisionElimination(
                    unit.packageId,
                    unit.candidate.candidateKey,
                    DecisionReasonCodes.LIMIT_DAY,
                    "perDay=$limit used=$used day=${time.day}",
                )
            } else {
                kept += unit
            }
        }
        return kept
    }

    private fun applyActorLimit(
        candidates: List<Collected>,
        time: GameTime,
        eliminations: MutableList<DecisionElimination>,
    ): List<Collected> {
        val kept = ArrayList<Collected>(candidates.size)
        for (unit in candidates) {
            val limit = unit.candidate.perActorPerDay
            val used = actorCount(unit, time.day)
            if (limit != null && used >= limit) {
                eliminations += DecisionElimination(
                    unit.packageId,
                    unit.candidate.candidateKey,
                    DecisionReasonCodes.LIMIT_ACTOR,
                    "perActorPerDay=$limit used=$used day=${time.day}",
                )
            } else {
                kept += unit
            }
        }
        return kept
    }

    private fun applyReservationFeasibility(
        candidates: List<Collected>,
        eliminations: MutableList<DecisionElimination>,
    ): List<Collected> {
        val kept = ArrayList<Collected>(candidates.size)
        for (unit in candidates) {
            val conflict = unit.candidate.reservationClaims.firstOrNull { it in held }
            if (conflict != null) {
                eliminations += DecisionElimination(
                    unit.packageId,
                    unit.candidate.candidateKey,
                    DecisionReasonCodes.RESERVATION_CONFLICT,
                    "held: ${conflict.kind}/${conflict.key}",
                )
            } else {
                kept += unit
            }
        }
        return kept
    }

    private fun resolveHandlers(
        candidates: List<Collected>,
        eliminations: MutableList<DecisionElimination>,
        failures: MutableList<DecisionFailure>,
    ): List<Executable> {
        val kept = ArrayList<Executable>(candidates.size)
        for (unit in candidates) {
            val handlerKey = unit.candidate.handlerKey
            val handler = handlers[registryKey(unit.packageId, handlerKey)]
            if (handler == null) {
                eliminations += DecisionElimination(
                    unit.packageId,
                    unit.candidate.candidateKey,
                    DecisionReasonCodes.HANDLER_FAILED,
                    "unregistered handler: $handlerKey",
                )
                failures += DecisionFailure(
                    unit.packageId,
                    handlerKey,
                    DecisionFailurePhase.HANDLER,
                    IllegalStateException("未注册的处理器：${unit.packageId}/$handlerKey"),
                )
            } else {
                kept += Executable(unit, handler)
            }
        }
        return kept
    }
    private fun dayCount(unit: Collected, day: Long): Int =
        dayCounts[DayCountKey(unit.packageId, unit.candidate.candidateKey, day)] ?: 0

    private fun actorCount(unit: Collected, day: Long): Int =
        actorCounts[
            ActorCountKey(unit.packageId, unit.candidate.candidateKey, day, unit.candidate.actor.raw)
        ] ?: 0

    /** 记一次**执行尝试**（COMPLETED / FAILED / CANCELED 都记，防失败重试死循环）。 */
    private fun recordAttempt(unit: Collected, time: GameTime) {
        val dayKey = DayCountKey(unit.packageId, unit.candidate.candidateKey, time.day)
        dayCounts[dayKey] = (dayCounts[dayKey] ?: 0) + 1
        val actorKey =
            ActorCountKey(unit.packageId, unit.candidate.candidateKey, time.day, unit.candidate.actor.raw)
        actorCounts[actorKey] = (actorCounts[actorKey] ?: 0) + 1
    }

    /**
     * 取本轮抽取序号：注入 lambda 时每轮调用且不推进内部计数（测试路径）；
     * 生产路径自持 [nextOrdinal]，先取当前值再 +1（跨存读档延续，不归零）。
     */
    private fun nextArbitrationOrdinal(): Int {
        val injected = arbitrationOrdinal
        if (injected != null) return injected()
        val current = nextOrdinal
        nextOrdinal = current + 1
        return current
    }

    private fun report(
        context: ActionSelectionContext,
        evaluated: Int,
        eliminations: List<DecisionElimination>,
        chosen: Executable?,
        outcome: ActionExecutionOutcome,
        failures: List<DecisionFailure>,
        released: List<ReservationKey>,
    ): DecisionReport {
        recordDecisionDiagnostics(context, eliminations, chosen, outcome, failures)
        return DecisionReport(
            domain = context.domain,
            evaluated = evaluated,
            eliminations = eliminations.toList(),
            chosen = chosen?.collected?.candidate,
            chosenPackageId = chosen?.collected?.packageId,
            outcome = outcome,
            failures = failures.toList(),
            reservationsReleased = released.toList(),
        )
    }

    /**
     * 把本轮淘汰逐条灌入 DECISION 域，再写一条终局记录。
     *
     * 淘汰记录直接复用 [DecisionElimination.reasonCode]；终局原因码取
     * [DecisionReasonCodes.HANDLER_FAILED] / [DecisionReasonCodes.CANCELED] /
     * [DecisionReasonCodes.NO_CANDIDATES]。null diagnostics 完全旁路。
     */
    private fun recordDecisionDiagnostics(
        context: ActionSelectionContext,
        eliminations: List<DecisionElimination>,
        chosen: Executable?,
        outcome: ActionExecutionOutcome,
        failures: List<DecisionFailure>,
    ) {
        val diagnostics = diagnostics ?: return
        for (elimination in eliminations) {
            val detail = elimination.detail
            if (detail == null) {
                diagnostics.record(
                    day = context.time.day,
                    phase = context.time.phase.ifBlank { null },
                    domain = DiagnosticDomain.DECISION,
                    packageId = elimination.packageId,
                    key = context.domain,
                    outcome = DiagnosticOutcome.REJECTED,
                    reasonCode = elimination.reasonCode,
                )
            } else {
                diagnostics.record(
                    day = context.time.day,
                    phase = context.time.phase.ifBlank { null },
                    domain = DiagnosticDomain.DECISION,
                    packageId = elimination.packageId,
                    key = context.domain,
                    outcome = DiagnosticOutcome.REJECTED,
                    reasonCode = elimination.reasonCode,
                    detail = { detail },
                )
            }
        }
        val chosenPackageId = chosen?.collected?.packageId
        val finalOutcome = when {
            chosen == null -> DiagnosticOutcome.SKIPPED
            outcome == ActionExecutionOutcome.COMPLETED -> DiagnosticOutcome.OK
            outcome == ActionExecutionOutcome.FAILED -> DiagnosticOutcome.FAILED
            else -> DiagnosticOutcome.SKIPPED
        }
        val reasonCode = when {
            chosen == null -> DecisionReasonCodes.NO_CANDIDATES
            outcome == ActionExecutionOutcome.COMPLETED -> "OK"
            outcome == ActionExecutionOutcome.FAILED -> DecisionReasonCodes.HANDLER_FAILED
            outcome == ActionExecutionOutcome.CANCELED -> DecisionReasonCodes.CANCELED
            else -> DecisionReasonCodes.NO_CANDIDATES
        }
        val failureDetail = if (outcome == ActionExecutionOutcome.FAILED) {
            failures.lastOrNull { it.phase == DecisionFailurePhase.HANDLER }?.cause?.let {
                it.javaClass.simpleName.ifEmpty { it.javaClass.name }
            }
        } else {
            null
        }
        if (failureDetail == null) {
            diagnostics.record(
                day = context.time.day,
                phase = context.time.phase.ifBlank { null },
                domain = DiagnosticDomain.DECISION,
                packageId = chosenPackageId,
                key = context.domain,
                outcome = finalOutcome,
                reasonCode = reasonCode,
            )
        } else {
            diagnostics.record(
                day = context.time.day,
                phase = context.time.phase.ifBlank { null },
                domain = DiagnosticDomain.DECISION,
                packageId = chosenPackageId,
                key = context.domain,
                outcome = finalOutcome,
                reasonCode = reasonCode,
                detail = { failureDetail },
            )
        }
    }

    private fun registration(remove: () -> Unit): Registration =
        object : Registration {
            override fun unregister() {
                remove()
            }
        }

    // 冷却桶命名空间：packageId + 双冒号 + cooldownKey（v1 不支持跨 Mod 共享冷却）。
    private fun cooldownBucket(packageId: String, cooldownKey: String): String =
        packageId + "::" + cooldownKey

    private fun rankOf(packageId: String): Int = packageRank[packageId] ?: Int.MAX_VALUE

    private fun registryKey(packageId: String, key: String): String = packageId + SEP + key

    companion object {
        /** 注册表/计数桶的内部键分隔符（U+001F，避免与包名/候选键拼串混淆）。 */
        private const val SEP = '\u001F'

        /**
         * 创建仲裁器。
         *
         * @param packageOrder 包链接序（决定候选收集序与同层定序的 packageRank；不得含空名/重名）
         * @param decisionSeed 决策种子（生命周期归 M6-2；生产路径建议用 [DecisionSeed.derive]）
         * @param random 决策随机流（缺省 [Sha256DecisionRandom]）
         * @param arbitrationOrdinal 抽取序号注入点：缺省 null = 生产路径由管线自持 [nextOrdinal]
         *        并逐轮 +1；非 null = 测试路径，每轮调用 lambda 且不推进内部计数（波 E 语义原样）。
         *        两种路径都**不得**接全局/静态状态。
         */
        fun of(
            packageOrder: List<String>,
            decisionSeed: Long,
            random: DecisionRandomSource = Sha256DecisionRandom,
            arbitrationOrdinal: (() -> Int)? = null,
            diagnostics: RuntimeDiagnostics? = null,
        ): DecisionPipeline {
            requireValidPackageOrder(packageOrder)
            return DecisionPipeline(
                packageOrder.toList(),
                decisionSeed,
                random,
                arbitrationOrdinal,
                diagnostics,
            )
        }

        /**
         * 从持久态恢复仲裁器：种子/抽取序号/计数/冷却/预留全量还原（§21.3.5 跨存读档延续）。
         *
         * 预留按宿主谓词全保留（等价于 `reservationAlive` 恒 true）；需要回收陈旧预留时先构造
         * 空管线再调 [restoreState]。
         *
         * @param state 由 [snapshotState] 生成、可经 [DecisionStateCodec] 往返的快照
         * @throws IllegalArgumentException 快照内容畸形（fail-closed）
         */
        fun of(
            packageOrder: List<String>,
            state: DecisionState,
            random: DecisionRandomSource = Sha256DecisionRandom,
            diagnostics: RuntimeDiagnostics? = null,
        ): DecisionPipeline {
            requireValidPackageOrder(packageOrder)
            val pipeline = DecisionPipeline(
                packageOrder.toList(),
                state.decisionSeed,
                random,
                null,
                diagnostics,
            )
            pipeline.restoreState(state)
            return pipeline
        }

        private fun requireValidPackageOrder(packageOrder: List<String>) {
            require(packageOrder.none { it.isBlank() }) { "packageOrder 不能含空包名：$packageOrder" }
            require(packageOrder.distinct().size == packageOrder.size) {
                "packageOrder 不能有重复包名：$packageOrder"
            }
        }
    }
}