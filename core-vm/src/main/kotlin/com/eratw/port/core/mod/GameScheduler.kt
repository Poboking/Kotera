package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.BuiltinPhases
import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredJobTemplate
import com.eratw.port.core.patch.DeclaredPhase
import com.eratw.port.core.patch.JobCatchUp
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.JobContext
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.PhaseContext
import com.eratw.port.modapi.PhaseHandler
import com.eratw.port.modapi.Registration
import java.util.Locale

/**
 * 游戏相位派发器（§20.2，ABI-10 Candidate；纯 JVM，无静态可变全局）。
 *
 * 相位名来自声明事务快照（引擎固定相位 + Mod 自定义相位）；运行期 [subscribe] 只把处理器
 * 绑到已声明相位。派发序 = 包链接序 + 包内注册序（确定性）；处理器失败按 Mod 隔离
 * （§18.4），不拖垮其余处理器。快照隔离：派发开始时锁定处理器列表，中途订阅/退订不影响本轮。
 */
class PhaseRegistry private constructor(
    private val phases: List<DeclaredPhase>,
    packageOrder: List<String>,
    private val diagnostics: RuntimeDiagnostics? = null,
) {
    private data class BindingKey(val phase: String, val packageId: String)

    private val packageRank: Map<String, Int> = buildMap {
        packageOrder.distinct().forEachIndexed { index, pkg -> put(pkg, index) }
    }

    private val handlers = LinkedHashMap<BindingKey, MutableList<PhaseHandler>>()

    /** 声明的 Mod 相位（链接序）。 */
    fun declared(): List<DeclaredPhase> = phases

    /** 某相位是否可用（引擎固定相位或本事务声明的相位）。 */
    fun isDeclared(name: String): Boolean {
        val key = canonical(name)
        return key in BuiltinPhases.ALL || phases.any { it.declaration.normalizedName() == key }
    }

    /**
     * 订阅一个相位。同一包对同一相位可注册多个处理器（按注册序执行）。
     *
     * @throws IllegalArgumentException 相位未声明
     */
    fun subscribe(phase: String, packageId: String, handler: PhaseHandler): Registration {
        val key = canonical(phase)
        require(isDeclared(key)) { "相位未声明：$phase（须为固定相位或 Manifest 声明的相位）" }
        val binding = BindingKey(key, packageId)
        val list = handlers.getOrPut(binding) { ArrayList() }
        list += handler
        return object : Registration {
            override fun unregister() {
                handlers[binding]?.remove(handler)
            }
        }
    }

    /**
     * 已绑定处理器的相位名（大写归一，空绑定的键不算）。
     *
     * 供回合边界读数区分两种 fired 同为 0 的情形：「本拍没到点的相位」
     * （宿主记 SKIPPED / PHASE_NOT_DUE）与「到点但没有处理器」（派发记 SKIPPED / NO_HANDLERS）。
     */
    fun boundPhases(): Set<String> =
        handlers.filterValues { it.isNotEmpty() }.keys.mapTo(LinkedHashSet()) { key -> key.phase }

    /**
     * 派发一个相位。[time.phase] 为空 = 无相位，不派发。
     *
     * 顺序 = 包链接序（[packageRank]）+ 包内注册序；本轮处理器列表是派发时的快照。
     */
    fun dispatch(time: GameTime): PhaseDispatchReport {
        val phaseKey = canonical(time.phase)
        if (phaseKey.isEmpty()) return PhaseDispatchReport(fired = 0, failures = emptyList())
        val keys = handlers.keys
            .filter { it.phase == phaseKey }
            .sortedWith(compareBy({ packageRank[it.packageId] ?: Int.MAX_VALUE }, { it.packageId }))
        val failures = ArrayList<PhaseFailure>()
        var fired = 0
        for (key in keys) {
            val snapshot = handlers[key]?.toList().orEmpty()
            for (handler in snapshot) {
                fired++
                try {
                    handler.onPhase(PhaseContext(key.packageId, phaseKey, time))
                } catch (t: Throwable) {
                    failures += PhaseFailure(key.packageId, phaseKey, t)
                    recordPhaseFailure(time, phaseKey, key.packageId, t)
                }
            }
        }
        recordPhaseDispatch(time, phaseKey, fired, failures.size)
        return PhaseDispatchReport(fired, failures)
    }

    /** 写一条相位处理器失败诊断；null diagnostics 直接旁路。 */
    private fun recordPhaseFailure(
        time: GameTime,
        phase: String,
        packageId: String,
        cause: Throwable,
    ) {
        val diagnostics = diagnostics ?: return
        diagnostics.record(
            day = time.day,
            phase = time.phase.ifBlank { null },
            domain = DiagnosticDomain.PHASE,
            packageId = packageId,
            key = phase,
            outcome = DiagnosticOutcome.FAILED,
            reasonCode = "HANDLER_THREW",
            detail = { cause.javaClass.simpleName.ifEmpty { cause.javaClass.name } },
        )
    }

    /** 每次派发一条汇总诊断：fired > 0 记 OK，否则记 SKIPPED。 */
    private fun recordPhaseDispatch(time: GameTime, phase: String, fired: Int, failureCount: Int) {
        val diagnostics = diagnostics ?: return
        diagnostics.record(
            day = time.day,
            phase = time.phase.ifBlank { null },
            domain = DiagnosticDomain.PHASE,
            packageId = null,
            key = phase,
            outcome = if (fired > 0) DiagnosticOutcome.OK else DiagnosticOutcome.SKIPPED,
            reasonCode = if (fired > 0) "OK" else "NO_HANDLERS",
            detail = { "fired=$fired;failures=$failureCount" },
        )
    }

    /** 一次相位派发的报告。 */
    class PhaseDispatchReport(
        val fired: Int,
        val failures: List<PhaseFailure>,
    )

    /** 一个相位处理器的失败记录。 */
    class PhaseFailure(
        val packageId: String,
        val phase: String,
        val cause: Throwable,
    )

    companion object {
        /**
         * 从声明事务快照的相位声明构建。[packageOrder] 缺省取声明里首次出现的包序。
         */
        fun of(
            declarations: List<DeclaredPhase>,
            packageOrder: List<String> = declarations.map { it.packageId }.distinct(),
            diagnostics: RuntimeDiagnostics? = null,
        ): PhaseRegistry = PhaseRegistry(declarations.toList(), packageOrder, diagnostics)

        private fun canonical(name: String): String = name.trim().uppercase(Locale.ROOT)
    }
}

/**
 * 定时/周期任务调度器（§20.2 / §21.1，ABI-10 Candidate；纯 JVM，无静态可变全局）。
 *
 * 两条任务形态共用一次派发：
 *
 * - **静态任务槽**来自声明事务快照（[DeclaredJob]）；运行期 [bind] 只把处理器绑到已声明槽。
 * - **动态任务模板**来自声明事务快照（[DeclaredJobTemplate]）；运行期 [scheduleOnce] 按模板
 *   创建实例，[cancel] / [reschedule] 幂等操作，[instances] 观察全部实例。
 *
 * 引擎负责：稳定排序（priority 升序 + 声明序）、任务态持久化（[snapshot]/[of] 往返，随
 * `.rt2` 由宿主接线）、失败隔离（§18.4）。任务业务全在 Mod 处理器里。
 *
 * ## 静态槽触发规则（逐字不变）
 *
 * - 一次性任务（`everyDays == null`）：到达 `firstDay` 后触发一次，随后标记完成
 *   （[DONE] 哨兵），快照往返后不会复活。
 * - 周期任务：触发后推进到「严格大于当前日」的下一个周期点；错过的周期**跳过不补跑**
 *   （防读档后风暴）。
 * - 相位匹配：[GameTime.phase] 为空时不限相位；否则只跑相位相同的任务。
 *
 * ## 动态实例触发规则（§21.1 补算三档）
 *
 * - 实例键 = `packageId::templateKey#序号`；序号按 `(packageId,templateKey)` 单调递增并
 *   持久化（[JobSchedulerSnapshot.nextSeqByTemplate]），读档后接续不前移。
 * - `MISS_ONCE`：`atDay <= now.day` 且相位匹配 ⇒ 触发一次，上下文 `time.day = now.day`，
 *   随后 DONE（读档跨多日也只补一次）。
 * - `MISS_SKIP`：`atDay < now.day` ⇒ 不触发、DONE 并计入 [JobRunReport.skipped]；
 *   `atDay == now.day` ⇒ 触发一次。
 * - `REPLAY`：逐错过日各触发一次，次数 = `min(now.day - atDay + 1, maxCatchUp)`；第 i 次
 *   （0 起）上下文 `time = GameTime(atDay + i, now.phase)`；超上限 ⇒ 记
 *   [CatchUpTruncation]（诊断 `reasonCode` 恒为 [CATCHUP_TRUNCATED]）并截断。
 * - 失败隔离：处理器抛异常 ⇒ 记入 [JobRunReport.failures]，实例照常推进到 DONE
 *   （不复活、不重试），其余实例继续。
 * - 无模板声明、无实例、无静态槽 ⇒ [runDue] 空报告（完全旁路，golden 零变化）。
 */
class JobScheduler private constructor(
    private val slots: List<JobSlot>,
    private val nextFire: MutableMap<String, Long>,
    private val templates: List<JobTemplateSlot>,
    private val instanceById: MutableMap<String, JobInstance>,
    private val nextSeq: MutableMap<String, Long>,
    private val diagnostics: RuntimeDiagnostics? = null,
) {
    private val handlers = HashMap<String, JobHandler>()
    private val templateHandlers = HashMap<String, JobHandler>()
    private val templateByScope: Map<String, JobTemplateSlot> = templates.associateBy { it.scopeKey }
    private val templateOrder: Map<String, Int> = templates.associate { it.scopeKey to it.order }

    /** 已声明的任务槽（确定性序：priority 升序 + 声明序）。 */
    fun slots(): List<JobSlot> = slots

    /** 已声明的任务模板（确定性序：priority 升序 + 声明序）。 */
    fun templates(): List<JobTemplateSlot> = templates

    /** 某任务槽的处理器绑定。未声明/重复绑定 = 拒绝。 */
    fun bind(packageId: String, jobId: String, handler: JobHandler): Registration {
        val key = keyOf(packageId, jobId)
        require(slots.any { it.key == key }) { "任务槽未声明：$packageId/$jobId（先经 Manifest 声明）" }
        require(key !in handlers) { "任务处理器重复绑定：$packageId/$jobId" }
        handlers[key] = handler
        return object : Registration {
            override fun unregister() {
                // 身份判据：只有当前映射仍是自己绑的处理器才移除。旧代 Registration 晚到
                // （重装后又触发一次 unregister）不得删掉新一代处理器。
                if (handlers[key] === handler) handlers.remove(key)
            }
        }
    }

    /**
     * 把处理器绑定到已声明的任务模板。
     *
     * @throws IllegalArgumentException 模板未声明、或同一模板重复绑定
     */
    fun bindTemplate(packageId: String, templateKey: String, handler: JobHandler): Registration {
        val scope = scopeOf(packageId, templateKey)
        require(scope in templateByScope) {
            "任务模板未声明：$packageId/$templateKey（先经 Manifest 声明）"
        }
        require(scope !in templateHandlers) { "任务模板处理器重复绑定：$packageId/$templateKey" }
        templateHandlers[scope] = handler
        return object : Registration {
            override fun unregister() {
                if (templateHandlers[scope] === handler) templateHandlers.remove(scope)
            }
        }
    }

    /**
     * 按已声明模板创建一个待触发实例。[seq] 从 0 起按 `(packageId,templateKey)` 单调递增。
     *
     * @return 实例键 `packageId::templateKey#seq`
     * @throws IllegalArgumentException 模板未声明，或 [atDay] 为负
     */
    fun scheduleOnce(
        packageId: String,
        templateKey: String,
        atDay: Long,
        payloadRef: EntityId? = null,
    ): String {
        require(atDay >= 0L) { "实例触发日不能为负：$atDay" }
        val scope = scopeOf(packageId, templateKey)
        require(scope in templateByScope) {
            "任务模板未声明：$packageId/$templateKey（先经 Manifest 声明）"
        }
        val seq = nextSeq[scope] ?: 0L
        nextSeq[scope] = seq + 1L
        val instance = JobInstance(packageId, templateKey, seq, atDay, payloadRef, JobState.PENDING)
        instanceById[instance.instanceKey] = instance
        return instance.instanceKey
    }

    /**
     * 取消一个待触发实例。不存在 / 已完成 / 已取消 ⇒ false（幂等，不抛异常）。
     */
    fun cancel(packageId: String, instanceKey: String): Boolean {
        val instance = instanceById[instanceKey] ?: return false
        if (instance.packageId != packageId) return false
        if (instance.state != JobState.PENDING) return false
        instanceById[instanceKey] = instance.copy(state = JobState.CANCELED)
        return true
    }

    /**
     * 改期一个待触发实例。不存在 / 已完成 / 已取消 ⇒ false；同 day ⇒ true 且不改。
     *
     * @throws IllegalArgumentException [atDay] 为负
     */
    fun reschedule(packageId: String, instanceKey: String, atDay: Long): Boolean {
        require(atDay >= 0L) { "实例触发日不能为负：$atDay" }
        val instance = instanceById[instanceKey] ?: return false
        if (instance.packageId != packageId) return false
        if (instance.state != JobState.PENDING) return false
        if (instance.atDay == atDay) return true
        instanceById[instanceKey] = instance.copy(atDay = atDay)
        return true
    }

    /** 全部实例（含 PENDING/DONE/CANCELED；确定性序：atDay 升序 + 实例键）。 */
    fun instances(): List<JobInstance> =
        instanceById.values.sortedWith(compareBy({ it.atDay }, { it.instanceKey }))

    /** 当前应触发的静态任务槽（确定性序；不推进任务态）。[activePackages] 非 null 时只取活包。 */
    fun dueJobs(now: GameTime, activePackages: Set<String>? = null): List<JobSlot> {
        if (slots.isEmpty()) return emptyList()
        val phaseKey = canonical(now.phase)
        val due = ArrayList<JobSlot>()
        for (slot in slots) {
            if (activePackages != null && slot.packageId !in activePackages) continue
            val next = nextFire[slot.key] ?: continue
            if (next == DONE || next > now.day) continue
            if (phaseKey.isNotEmpty() && slot.phase != phaseKey) continue
            due += slot
        }
        due.sortWith(compareBy({ it.priority }, { it.order }))
        return due
    }

    /**
     * 本拍到期的静态槽与动态实例统一推进（ABI-10：[activePackages] 非 null 时只推进活跃包）。
     *
     * 拆成 [runSlotHandler] / [runInstanceCatchUp] / [dueInstances] 三个私有步骤只为把本函数留在
     * detekt 阈值内，行为逐位不变：槽先按 priority 序推进，实例再按模板 priority 序补跑。
     */
    fun runDue(now: GameTime, activePackages: Set<String>? = null): JobRunReport {
        val due = dueJobs(now, activePackages)
        val instances = dueInstances(now, activePackages)
        if (due.isEmpty() && instances.isEmpty()) {
            return JobRunReport(fired = emptyList(), failures = emptyList())
        }
        val failures = ArrayList<JobRunFailure>()
        val fired = ArrayList<JobSlot>()
        val handlerSnapshot = handlers.toMap()
        for (slot in due) {
            nextFire[slot.key] = advance(slot, nextFire.getValue(slot.key), now.day)
            val handler = handlerSnapshot[slot.key] ?: continue
            fired += slot
            runSlotHandler(handler, slot, now, failures)
        }
        val tally = InstanceCatchUpTally()
        val templateHandlerSnapshot = templateHandlers.toMap()
        for (instance in instances) {
            val template = templateByScope[scopeOf(instance.packageId, instance.templateKey)] ?: continue
            runInstanceCatchUp(template, instance, templateHandlerSnapshot[template.scopeKey], now, tally)
        }
        return JobRunReport(
            fired,
            failures + tally.failures,
            tally.firedInstances,
            tally.skipped,
            tally.truncations,
        )
    }

    /** 单静态槽执行：成功与失败都记 JOB 诊断，异常只进 [failures]，不中断同拍其余槽。 */
    private fun runSlotHandler(
        handler: JobHandler,
        slot: JobSlot,
        now: GameTime,
        failures: MutableList<JobRunFailure>,
    ) {
        try {
            handler.run(JobContext(slot.packageId, slot.jobId, now))
            recordJob(now, slot.packageId, slot.key, DiagnosticOutcome.OK, "OK", null)
        } catch (t: Throwable) {
            failures += JobRunFailure(slot.packageId, slot.jobId, t)
            recordJob(now, slot.packageId, slot.key, DiagnosticOutcome.FAILED, "HANDLER_THREW", t)
        }
    }

    /** 动态实例补跑的累计槽（把 4 个累计容器收成一个参数，避开 detekt LongParameterList）。 */
    private class InstanceCatchUpTally {
        val firedInstances: MutableList<JobInstance> = ArrayList()
        val skipped: MutableList<String> = ArrayList()
        val truncations: MutableList<CatchUpTruncation> = ArrayList()
        val failures: MutableList<JobRunFailure> = ArrayList()
    }

    /** 单动态实例按 catchUp 策略补跑（MISS_ONCE / MISS_SKIP / REPLAY），末态统一置 DONE。 */
    private fun runInstanceCatchUp(
        template: JobTemplateSlot,
        instance: JobInstance,
        handler: JobHandler?,
        now: GameTime,
        tally: InstanceCatchUpTally,
    ) {
        when (template.catchUp) {
            JobCatchUp.MISS_ONCE -> {
                if (handler != null) {
                    tally.firedInstances += instance
                    runInstance(handler, instance, GameTime(now.day, now.phase), tally.failures)
                }
            }
            JobCatchUp.MISS_SKIP -> {
                if (instance.atDay < now.day) {
                    tally.skipped += instance.instanceKey
                } else if (handler != null) {
                    tally.firedInstances += instance
                    runInstance(handler, instance, GameTime(now.day, now.phase), tally.failures)
                }
            }
            JobCatchUp.REPLAY -> {
                val diff = now.day - instance.atDay
                val requested = if (diff == Long.MAX_VALUE) Long.MAX_VALUE else diff + 1L
                val limit = template.maxCatchUp ?: 0L
                val applied = if (requested > limit) limit else requested
                if (requested > limit) {
                    tally.truncations += CatchUpTruncation(instance.instanceKey, requested, applied)
                }
                if (handler != null) {
                    tally.firedInstances += instance
                    var i = 0L
                    while (i < applied) {
                        runInstance(handler, instance, GameTime(instance.atDay + i, now.phase), tally.failures)
                        i++
                    }
                }
            }
        }
        instanceById[instance.instanceKey] = instance.copy(state = JobState.DONE)
    }

    private fun dueInstances(now: GameTime, activePackages: Set<String>?): List<JobInstance> {
        if (instanceById.isEmpty()) return emptyList()
        val phaseKey = canonical(now.phase)
        return instanceById.values
            .filter { isInstanceDue(it, now.day, phaseKey, activePackages) }
            .sortedWith(instanceOrder)
    }

    /** 单实例到期判据：[activePackages] 为 null 表示不筛包（golden 口径）。 */
    private fun isInstanceDue(
        instance: JobInstance,
        day: Long,
        phaseKey: String,
        activePackages: Set<String>?,
    ): Boolean =
        (activePackages == null || instance.packageId in activePackages) &&
            instance.state == JobState.PENDING &&
            instance.atDay <= day &&
            templateByScope[scopeOf(instance.packageId, instance.templateKey)]
                ?.let { phaseKey.isEmpty() || it.phase == phaseKey } == true

    /** 实例排序：模板 priority 前置键 - 模板声明序 - 到期日 - 实例键。 */
    private val instanceOrder: Comparator<JobInstance> = compareBy(
        { templateByScope[scopeOf(it.packageId, it.templateKey)]?.priority ?: Int.MAX_VALUE },
        { templateOrder[scopeOf(it.packageId, it.templateKey)] ?: Int.MAX_VALUE },
        { it.atDay },
        { it.instanceKey },
    )

    private fun runInstance(
        handler: JobHandler,
        instance: JobInstance,
        time: GameTime,
        failures: MutableList<JobRunFailure>,
    ) {
        try {
            handler.run(
                JobContext(
                    packageId = instance.packageId,
                    jobId = instance.templateKey,
                    time = time,
                    templateKey = instance.templateKey,
                    instanceKey = instance.instanceKey,
                    payloadRef = instance.payloadRef,
                ),
            )
            recordJob(time, instance.packageId, instance.instanceKey, DiagnosticOutcome.OK, "OK", null)
        } catch (t: Throwable) {
            failures += JobRunFailure(
                instance.packageId,
                instance.templateKey,
                t,
                instance.instanceKey,
            )
            recordJob(
                time,
                instance.packageId,
                instance.instanceKey,
                DiagnosticOutcome.FAILED,
                "HANDLER_THREW",
                t,
            )
        }
    }

    /** 写一条 JOB 诊断；null diagnostics 直接旁路。 */
    private fun recordJob(
        time: GameTime,
        packageId: String,
        key: String,
        outcome: DiagnosticOutcome,
        reasonCode: String,
        cause: Throwable?,
    ) {
        val diagnostics = diagnostics ?: return
        if (cause == null) {
            diagnostics.record(
                day = time.day,
                phase = time.phase.ifBlank { null },
                domain = DiagnosticDomain.JOB,
                packageId = packageId,
                key = key,
                outcome = outcome,
                reasonCode = reasonCode,
            )
        } else {
            diagnostics.record(
                day = time.day,
                phase = time.phase.ifBlank { null },
                domain = DiagnosticDomain.JOB,
                packageId = packageId,
                key = key,
                outcome = outcome,
                reasonCode = reasonCode,
                detail = { cause.javaClass.simpleName.ifEmpty { cause.javaClass.name } },
            )
        }
    }

    /** 某任务的下次触发日；一次性任务完成后返回 [DONE]。未声明返回 null。 */
    fun nextFireDay(packageId: String, jobId: String): Long? =
        nextFire[keyOf(packageId, jobId)]

    /** 任务态快照（随 `.rt2` 持久化；静态 nextFire + 实例 + 序号表，均防读档复活/串号）。 */
    fun snapshot(): JobSchedulerSnapshot = JobSchedulerSnapshot(
        nextFireDays = nextFire.toMap(),
        instances = instanceById.values.sortedBy { it.instanceKey }.toList(),
        nextSeqByTemplate = nextSeq.toMap(),
    )

    /**
     * 用持久快照覆盖本调度器的任务态（W1-DSTATE 判据 3/5 的回灌缝；只加不删的接线面，
     * 语义与 [of] 的快照恢复口径逐条对齐，本类不改判定）。
     *
     * 覆盖语义 = 读档覆盖现态：静态槽下次触发日、动态实例、序号表三表整体替换为快照态
     * （现会话读档前已排的实例随覆盖丢弃——「载入存档 = 存档态就是全部真相」）。
     * 校验与 [of] 同源：
     * - 静态键：负触发日 fail-closed；未声明槽的键照 [of] 口径忽略（只按已声明槽取值）。
     * - 实例：引用未声明模板 / 键重复 / 负序号 / 负触发日 fail-closed。
     * - 序号表：负值 fail-closed；实例 seq 的下限提升照 [of] 口径（读档后新实例接续不前移）。
     *
     * 本方法只被读档缝（装配侧恢复）调用，不在任务运行中途调用；调用方保证同一调度器
     * 不并发恢复（写者与读者都在 VM 线程，见装配层线程模型）。
     *
     * @throws IllegalArgumentException 快照内容对本调度器的声明面非法（fail-closed，不返回半恢复态）
     */
    /** SaveService prepare 专用：完整校验任务态但不写调度器。 */
    fun validateRestore(snapshot: JobSchedulerSnapshot) {
        prepareRestore(snapshot)
    }

    fun restore(snapshot: JobSchedulerSnapshot) {
        val prepared = prepareRestore(snapshot)
        nextFire.clear()
        nextFire.putAll(prepared.nextFire)
        instanceById.clear()
        instanceById.putAll(prepared.instances)
        nextSeq.clear()
        nextSeq.putAll(prepared.nextSeq)
    }

    /** 装载失败回滚：只恢复本包动态任务态，静态槽与其它包不受影响。 */
    internal fun restorePackageState(packageId: String, snapshot: JobSchedulerSnapshot) {
        instanceById.keys.removeAll { it.startsWith("$packageId::") }
        snapshot.instances.forEach { instanceById[it.instanceKey] = it }
        nextSeq.keys.removeAll { it.startsWith("$packageId::") }
        nextSeq.putAll(snapshot.nextSeqByTemplate)
    }

    private data class PreparedJobRestore(
        val nextFire: Map<String, Long>,
        val instances: Map<String, JobInstance>,
        val nextSeq: Map<String, Long>,
    )

    private fun prepareRestore(snapshot: JobSchedulerSnapshot): PreparedJobRestore {
        val restoredNextFire = HashMap<String, Long>(slots.size * 2)
        for (slot in slots) {
            val next = snapshot.nextFireDays[slot.key]
            require(next == null || next >= 0L) {
                "任务态下次触发日不能为负: ${slot.key}=$next"
            }
            restoredNextFire[slot.key] = next ?: nextFire[slot.key] ?: slot.firstDay
        }
        val restoredInstances = LinkedHashMap<String, JobInstance>(snapshot.instances.size * 2)
        for (instance in snapshot.instances) {
            require(instance.seq >= 0L) { "任务实例序号不能为负：${instance.instanceKey}" }
            require(instance.atDay >= 0L) { "任务实例触发日不能为负：${instance.instanceKey}" }
            require(scopeOf(instance.packageId, instance.templateKey) in templateByScope) {
                "任务实例引用了未声明的模板：${instance.instanceKey}"
            }
            require(restoredInstances.put(instance.instanceKey, instance) == null) {
                "任务实例键重复：${instance.instanceKey}"
            }
        }
        val restoredSeq = HashMap<String, Long>()
        for ((scope, next) in snapshot.nextSeqByTemplate) {
            require(next >= 0L) { "任务实例序号表不能为负：$scope=$next" }
            restoredSeq[scope] = next
        }
        for (instance in restoredInstances.values) {
            val scope = scopeOf(instance.packageId, instance.templateKey)
            val floor = instance.seq + 1L
            if ((restoredSeq[scope] ?: 0L) < floor) restoredSeq[scope] = floor
        }
        return PreparedJobRestore(restoredNextFire, restoredInstances, restoredSeq)
    }

    /** 一次调度运行报告（静态槽 fired/failures 语义与波 E 逐字一致）。 */
    class JobRunReport(
        val fired: List<JobSlot>,
        val failures: List<JobRunFailure>,
        /** 至少调用过一次处理器的动态实例（未绑定处理器的实例照常推进但不计入）。 */
        val firedInstances: List<JobInstance> = emptyList(),
        /** `MISS_SKIP` 过期作废的实例键（确定性序）。 */
        val skipped: List<String> = emptyList(),
        /** `REPLAY` 超过 `maxCatchUp` 的截断记录（确定性序）。 */
        val truncations: List<CatchUpTruncation> = emptyList(),
    )

    /** 一个任务处理器的失败记录。动态实例失败时 [instanceKey] 非空、[jobId] = 模板键。 */
    class JobRunFailure(
        val packageId: String,
        val jobId: String,
        val cause: Throwable,
        val instanceKey: String? = null,
    )

    /** `REPLAY` 补算截断：requested = 理论次数，applied = 实际次数。 */
    class CatchUpTruncation(
        val instanceKey: String,
        val requested: Long,
        val applied: Long,
    ) {
        /** 诊断 reasonCode（契约字面量，不随实例变化）。 */
        val reasonCode: String get() = CATCHUP_TRUNCATED
    }

    /** 一条静态任务槽（确定性序：priority 升序 + 声明序）。 */
    data class JobSlot(
        val packageId: String,
        val jobId: String,
        val phase: String,
        val firstDay: Long,
        val everyDays: Long?,
        val priority: Int,
        val order: Int,
    ) {
        val key: String get() = keyOf(packageId, jobId)
    }

    /** 一条动态任务模板（确定性序：priority 升序 + 声明序）。 */
    data class JobTemplateSlot(
        val packageId: String,
        val templateKey: String,
        val phase: String,
        val priority: Int,
        val catchUp: JobCatchUp,
        val maxCatchUp: Long?,
        val order: Int,
    ) {
        val scopeKey: String get() = scopeOf(packageId, templateKey)
    }

    companion object {
        /** 一次性任务完成后的哨兵（快照往返保留，防读档复活）。 */
        const val DONE: Long = Long.MAX_VALUE

        /** `REPLAY` 截断诊断 reasonCode（契约字面量）。 */
        const val CATCHUP_TRUNCATED: String = "CATCHUP_TRUNCATED"

        /**
         * 从声明事务快照构建。[snapshot] 为 null 时静态槽从 [JobSlot.firstDay] 开始、无实例；
         * 否则按快照恢复静态下次触发日、动态实例与序号表。[templates] 是动态模板声明。
         *
         * fail-closed：恢复的实例若引用未声明模板、键重复、序号/触发日为负 ⇒ [IllegalArgumentException]。
         */
        fun of(
            declarations: List<DeclaredJob>,
            snapshot: JobSchedulerSnapshot? = null,
            templates: List<DeclaredJobTemplate> = emptyList(),
            diagnostics: RuntimeDiagnostics? = null,
        ): JobScheduler {
            val slots = declarations.mapIndexed { index, declared ->
                val d = declared.declaration
                JobSlot(
                    packageId = declared.packageId,
                    jobId = d.jobId,
                    phase = d.normalizedPhase(),
                    firstDay = d.firstDay,
                    everyDays = d.everyDays,
                    priority = d.priority,
                    order = index,
                )
            }.sortedWith(compareBy({ it.priority }, { it.order }))

            val nextFire = HashMap<String, Long>(slots.size * 2)
            for (slot in slots) {
                nextFire[slot.key] = snapshot?.nextFireDays?.get(slot.key) ?: slot.firstDay
            }

            val templateSlots = templates.mapIndexed { index, declared ->
                val t = declared.declaration
                JobTemplateSlot(
                    packageId = declared.packageId,
                    templateKey = t.templateKey,
                    phase = t.normalizedPhase(),
                    priority = t.priority,
                    catchUp = t.catchUp,
                    maxCatchUp = t.maxCatchUp,
                    order = index,
                )
            }.sortedWith(compareBy({ it.priority }, { it.order }))
            val templateScopes = HashSet<String>(templateSlots.size * 2)
            for (slot in templateSlots) {
                require(templateScopes.add(slot.scopeKey)) {
                    "任务模板重复声明：${slot.packageId}/${slot.templateKey}"
                }
            }

            val restored = LinkedHashMap<String, JobInstance>()
            for (instance in snapshot?.instances.orEmpty()) {
                require(instance.seq >= 0L) { "任务实例序号不能为负：${instance.instanceKey}" }
                require(instance.atDay >= 0L) { "任务实例触发日不能为负：${instance.instanceKey}" }
                require(scopeOf(instance.packageId, instance.templateKey) in templateScopes) {
                    "任务实例引用了未声明的模板：${instance.instanceKey}"
                }
                require(restored.put(instance.instanceKey, instance) == null) {
                    "任务实例键重复：${instance.instanceKey}"
                }
            }

            val seq = HashMap<String, Long>()
            for ((scope, next) in snapshot?.nextSeqByTemplate.orEmpty()) {
                require(next >= 0L) { "任务实例序号表不能为负：$scope=$next" }
                seq[scope] = next
            }
            for (instance in restored.values) {
                val scope = scopeOf(instance.packageId, instance.templateKey)
                val floor = instance.seq + 1L
                if ((seq[scope] ?: 0L) < floor) seq[scope] = floor
            }
            return JobScheduler(slots, nextFire, templateSlots, restored, seq, diagnostics)
        }

        private fun canonical(name: String): String = name.trim().uppercase(Locale.ROOT)

        private fun keyOf(packageId: String, jobId: String): String = "$packageId::$jobId"

        private fun scopeOf(packageId: String, templateKey: String): String =
            "$packageId::$templateKey"

        private fun advance(slot: JobSlot, current: Long, nowDay: Long): Long {
            val every = slot.everyDays ?: return DONE
            require(every > 0) { "everyDays 必须为正" }
            if (current == DONE) return DONE
            val missed = (nowDay - current) / every
            val steps = missed + 1
            if (steps > (Long.MAX_VALUE - current) / every) return DONE
            return current + steps * every
        }
    }
}

/** 动态任务实例状态（§21.1）。 */
enum class JobState { PENDING, DONE, CANCELED }

/**
 * 一条动态任务实例。
 *
 * [instanceKey] = `packageId::templateKey#seq`；[seq] 按 `(packageId,templateKey)` 单调递增
 * 并持久化（[JobSchedulerSnapshot.nextSeqByTemplate]），读档后新实例序号接续不前移。
 */
data class JobInstance(
    val packageId: String,
    val templateKey: String,
    val seq: Long,
    val atDay: Long,
    val payloadRef: EntityId?,
    val state: JobState,
) {
    val instanceKey: String get() = "$packageId::$templateKey#$seq"
}

/**
 * 任务态快照（§21.1 codec v2）：静态 `jobKey → 下次触发日` + 动态实例列表 + 实例序号表。
 *
 * 静态键 = `packageId::jobId`；序号表键 = `packageId::templateKey`（实例键去掉 `#seq`）。
 */
data class JobSchedulerSnapshot(
    val nextFireDays: Map<String, Long> = emptyMap(),
    val instances: List<JobInstance> = emptyList(),
    val nextSeqByTemplate: Map<String, Long> = emptyMap(),
)
