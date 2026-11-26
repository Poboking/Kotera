package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.modapi.HookContext
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookRegistration
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModCallArg
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.Registration
import java.util.Locale
import com.eratw.port.modapi.HookPhase as ModHookPhase

/**
 * 函数 Hook 执行引擎（§20.1，ABI-9 Candidate；纯 JVM，无静态可变全局）。
 *
 * ## 执行序
 *
 * ```
 * dispatch 目标（Override 胜者 ?: 原函数）
 *   ├── Prefix hooks（priority 升序 + 声明序）
 *   ├── 目标函数体（skipOriginal 时跳过）
 *   └── Postfix hooks（priority 升序 + 声明序）
 * ```
 *
 * 槽声明来自声明事务快照（[DeclaredHook]，链接序即声明序）；运行期 [bind] 只把处理器
 * 绑到已声明槽，未声明 = 拒绝（fail-closed，不在运行期改 dispatch 结构）。
 *
 * ## 结果协议
 *
 * - [HookResult.Continue]：继续；
 * - [HookResult.Replace]：立即以该值返回，跳过原函数与其余 hook（含 Postfix）；
 * - [HookResult.SkipOriginal]：跳过原函数体，Postfix 照常执行（Postfix 里无意义，按 Continue 处理）。
 *
 * ## 失败隔离（§18.4）
 *
 * 单个处理器抛异常 = 记入 [HookOutcome.failures]、跳过该处理器，原函数与其余处理器继续。
 * 原函数抛异常时：只跑声明了 `runOnFailure` 的 Postfix（其返回值被丢弃），随后原异常照常
 * 抛给调用方——引擎不吞原函数异常。
 *
 * ## 处理器失败后的槽撤销（§18.4 追加，`continueOnHandlerFailure=false`）
 *
 * 缺省 `continueOnHandlerFailure=true` = 与本节之外的历史行为逐位一致：处理器抛异常只记失败、
 * 继续跑后续处理器，槽保持绑定。声明 `false` 的槽**首次**处理器异常后撤销：记一条
 * `HANDLER_THREW_SLOT_RETIRED` 诊断（只记一次，不随每次派发重复），此后派发不再进入该槽。
 * 撤销是槽级状态：[isSlotRetired] 可查，不影响同包其它槽、不影响同目标另一相位，也不影响
 * 其它包；同一槽重新 [bind] 会解除撤销。它与 `runOnFailure` 是两根独立的轴（后者管被 hook 的
 * 函数抛错时本 hook 是否仍执行）。
 *
 * ## 修改权限
 *
 * 未声明 `mayMutateArgs` 的 hook 拿到实参副本（改不回流）；声明了的直接拿实参数组。
 * 未声明 `mayMutateReturn` 的 Postfix 对 `returnValue` 的修改被忽略（读取不受限）。
 *
 * ## 旁路
 *
 * 无声明槽或无已绑定处理器时，[dispatch] 直接调用原函数并原样返回（golden 零变化）。
 */
class HookPipeline private constructor(
    private val slotsByTarget: Map<String, List<HookSlot>>,
    private val diagnostics: RuntimeDiagnostics? = null,
) {
    private data class BindingKey(val targetKey: String, val phase: HookPhase, val packageId: String)

    /**
     * 会话级耗时账本（§18.5 诊断面扩展，RT2 P1 耗时归因）：装配侧装上后，每次处理器调用记一笔
     * `hook.<TARGETKEY>.PREFIX|POSTFIX`。为 null（缺省）时零开销直调 —— golden 与既有派发语义
     * 逐位不变。
     */
    internal var timingLedger: ModTimingLedger? = null

    /** [timingLedger] 的公开读写面（装配侧跨模块赋值；语义同 KDoc 上一条）。 */
    var timing: ModTimingLedger?
        get() = timingLedger
        set(value) {
            timingLedger = value
        }

    /**
     * 会话级单次耗时预算（§18.5 阈值诊断）：装配侧装上后，每次处理器调用按
     * `hook.<TARGETKEY>.PREFIX|POSTFIX` 计时，越 [ModHandlerBudget.thresholdNanos] 记一条诊断
     * 并计数；不中断、不改返回值。为 null（缺省）时零开销直调 —— 与既有派发逐位不变。
     */
    internal var handlerBudget: ModHandlerBudget? = null

    private val handlers = HashMap<BindingKey, HookHandler>()

    /** 已撤销的槽（`continueOnHandlerFailure=false` 的槽首次处理器异常后进入；重新 bind 移出）。 */
    private val retiredSlots = LinkedHashSet<BindingKey>()

    /**
     * 已撤销槽数（只读；见类 KDoc「处理器失败后的槽撤销」）。
     *
     * 用属性而非成员函数：本类成员函数数已抵 detekt TooManyFunctions 阈值（与
     * `EventRecordTable.knownEventKeysSnapshot` 的「不为读数新增函数」同一口径）；
     * 形状与台架镜像 `RecordingHooks.retiredSlotCount`（val）对齐。
     */
    val retiredSlotCount: Int get() = retiredSlots.size

    /**
     * 诊断只读：本包当前绑定数与已退槽数（`[0]` = 绑定，`[1]` = 退槽）。
     *
     * 合成一个函数返回两计数，是为了不把成员函数数推过 detekt `TooManyFunctions` 阈值；
     * 两遍小表遍历只累加，不构造快照、不改注册面。未知包返回 `[0, 0]`。
     */
    internal fun bindingCountsOf(packageId: String): IntArray {
        var bound = 0
        for (key in handlers.keys) {
            if (key.packageId == packageId) bound++
        }
        var retired = 0
        for (key in retiredSlots) {
            if (key.packageId == packageId) retired++
        }
        return intArrayOf(bound, retired)
    }

    /**
     * 某槽是否已被撤销（槽级只读查询）。撤销只影响本槽绑定：同包其它槽、同目标另一相位、
     * 其它包都不受影响；同一槽重新 [bind] 会解除撤销。
     */
    fun isSlotRetired(targetKey: String, phase: ModHookPhase, packageId: String): Boolean =
        BindingKey(canonical(targetKey), toEnginePhase(phase), packageId) in retiredSlots

    /** 该函数键是否有声明的 hook 槽（大写归一）。 */
    fun hasHooks(targetKey: String): Boolean = slotsOf(targetKey).isNotEmpty()

    /** 该函数键的全部槽（确定性序：priority 升序 + 声明序）。 */
    fun slotsOf(targetKey: String): List<HookSlot> =
        slotsByTarget[canonical(targetKey)].orEmpty()

    /**
     * 只读投影：全部已声明槽的快照，序 = functionKey 字典序 → priority 升序 → 声明序
     * （mod-api `HookApi.registrations` 的引擎侧单源）。
     *
     * 单源投影：直接拍平 [slotsByTarget] 槽表再按全序重排，**不另建登记表**；每目标内本就是
     * priority 升序 + 声明序（[of] 的排序点），全序只是把「同一函数内」扩到跨函数键。
     * 声明了但未绑定处理器的槽同样在投影里（它占执行序位置，[bind] 不改槽表）。
     * 返回独立列表，改它不影响槽表与派发。
     */
    fun registrations(): List<HookRegistration> =
        slotsByTarget.values
            .flatten()
            .sortedWith(compareBy({ it.targetKey }, { it.priority }, { it.order }))
            .map { slot ->
                HookRegistration(
                    functionKey = slot.targetKey,
                    kind = toModPhase(slot.phase),
                    priority = slot.priority,
                    packageId = slot.packageId,
                )
            }

    /**
     * 把处理器绑定到已声明槽。同一槽重复绑定 = 拒绝。
     *
     * @throws IllegalArgumentException 槽未声明或重复绑定
     */
    fun bind(
        targetKey: String,
        phase: ModHookPhase,
        packageId: String,
        handler: HookHandler,
    ): Registration {
        val enginePhase = toEnginePhase(phase)
        val slot = slotsOf(targetKey).firstOrNull {
            it.phase == enginePhase && it.packageId == packageId
        } ?: run {
            recordHook(
                targetKey = canonical(targetKey),
                packageId = packageId,
                outcome = DiagnosticOutcome.REJECTED,
                reasonCode = "UNREGISTERED_SLOT",
                detailText = "phase=$phase",
            )
            throw IllegalArgumentException(
                "hook 槽未声明：${canonical(targetKey)}/$phase/$packageId（先经 Manifest 声明）",
            )
        }
        val key = BindingKey(slot.targetKey, enginePhase, packageId)
        require(key !in handlers) { "hook 处理器重复绑定：${slot.targetKey}/$phase/$packageId" }
        handlers[key] = handler
        // 重新绑定 = 该槽重新进入可用状态（撤销只影响上一次绑定，不跨注册残留）。
        retiredSlots.remove(key)
        return object : Registration {
            override fun unregister() {
                handlers.remove(key)
                // 解绑即撤销槽状态：退槽是「上一次绑定」的状态，注册注销后不得残留读数与派发旁路
                // （否则卸载收口后 retiredHooks 恒非零、isSlotRetired 对已注销槽说谎）。
                retiredSlots.remove(key)
            }
        }
    }

    /**
     * 执行一次带 hook 的调用。[original] 是 dispatch 解析出的目标函数体。
     *
     * 无槽/无绑定 = 直接调用 [original]（完全旁路）。任何处理器失败都被隔离进
     * [HookOutcome.failures]；原函数异常不被吞。
     */
    fun dispatch(
        targetKey: String,
        args: Array<ModCallArg>,
        original: () -> ModValue?,
    ): HookOutcome {
        val slots = slotsOf(targetKey)
        if (slots.isEmpty()) {
            return HookOutcome(original(), originalRan = true, replaced = false, failures = emptyList())
        }
        val bound = ArrayList<Pair<HookSlot, HookHandler>>(slots.size)
        for (slot in slots) {
            val key = BindingKey(slot.targetKey, slot.phase, slot.packageId)
            if (key in retiredSlots) continue
            val handler = handlers[key] ?: continue
            bound += slot to handler
        }
        if (bound.isEmpty()) {
            return HookOutcome(original(), originalRan = true, replaced = false, failures = emptyList())
        }

        val failures = ArrayList<HookFailure>()

        // ---- Prefix ----
        var skipOriginal = false
        for ((slot, handler) in bound) {
            if (slot.phase != HookPhase.PREFIX) continue
            val context = HookContext(
                packageId = slot.packageId,
                targetKey = slot.targetKey,
                phase = ModHookPhase.PREFIX,
                args = isolatedArgs(slot, args),
                returnValue = null,
            )
            try {
                val result = invokeHandler(slot, handler, context, handlerBudget, timingLedger)
                recordHook(slot.targetKey, slot.packageId, DiagnosticOutcome.OK, "OK")
                when (result) {
                    HookResult.Continue -> Unit
                    is HookResult.Replace -> return HookOutcome(
                        returnValue = result.value,
                        originalRan = false,
                        replaced = true,
                        failures = failures,
                    )
                    HookResult.SkipOriginal -> skipOriginal = true
                }
            } catch (t: Throwable) {
                handleHandlerFailure(slot, t, failures)
            }
        }

        // ---- 原函数 ----
        var returnValue: ModValue? = null
        var originalRan = false
        if (!skipOriginal) {
            try {
                returnValue = original()
                originalRan = true
            } catch (t: Throwable) {
                runFailurePostfix(bound, args, failures)
                throw t
            }
        }

        // ---- Postfix ----
        var current = returnValue
        var replaced = false
        for ((slot, handler) in bound) {
            if (slot.phase != HookPhase.POSTFIX) continue
            val context = HookContext(
                packageId = slot.packageId,
                targetKey = slot.targetKey,
                phase = ModHookPhase.POSTFIX,
                args = isolatedArgs(slot, args),
                returnValue = current,
            )
            try {
                val result = invokeHandler(slot, handler, context, handlerBudget, timingLedger)
                recordHook(slot.targetKey, slot.packageId, DiagnosticOutcome.OK, "OK")
                when (result) {
                    HookResult.Continue -> if (slot.mayMutateReturn) current = context.returnValue
                    is HookResult.Replace -> {
                        current = result.value
                        replaced = true
                        break
                    }
                    HookResult.SkipOriginal -> Unit
                }
            } catch (t: Throwable) {
                handleHandlerFailure(slot, t, failures)
            }
        }
        return HookOutcome(current, originalRan, replaced, failures)
    }

    private fun runFailurePostfix(
        bound: List<Pair<HookSlot, HookHandler>>,
        args: Array<ModCallArg>,
        failures: MutableList<HookFailure>,
    ) {
        for ((slot, handler) in bound) {
            if (slot.phase != HookPhase.POSTFIX || !slot.runOnFailure) continue
            if (BindingKey(slot.targetKey, slot.phase, slot.packageId) in retiredSlots) continue
            val context = HookContext(
                packageId = slot.packageId,
                targetKey = slot.targetKey,
                phase = ModHookPhase.POSTFIX,
                args = isolatedArgs(slot, args),
                returnValue = null,
            )
            try {
                invokeHandler(slot, handler, context, handlerBudget, timingLedger)
                recordHook(slot.targetKey, slot.packageId, DiagnosticOutcome.OK, "OK")
            } catch (t: Throwable) {
                handleHandlerFailure(slot, t, failures)
            }
        }
    }

    /**
     * 处理器异常的统一收尾：记失败 + 记一条 `HANDLER_THREW` 诊断；声明
     * `continueOnHandlerFailure=false` 的槽在此撤销，撤销本身只记一条诊断（不随派发重复刷）。
     */
    private fun handleHandlerFailure(
        slot: HookSlot,
        cause: Throwable,
        failures: MutableList<HookFailure>,
    ) {
        failures += HookFailure(slot.packageId, slot.targetKey, slot.phase, cause)
        recordHook(slot.targetKey, slot.packageId, DiagnosticOutcome.FAILED, "HANDLER_THREW", cause = cause)
        if (slot.continueOnHandlerFailure) return
        val key = BindingKey(slot.targetKey, slot.phase, slot.packageId)
        if (!retiredSlots.add(key)) return
        // 撤销即解绑：本次绑定作废，同槽重新 bind 因而能成功（新绑定 = 恢复可用）。
        handlers.remove(key)
        recordHook(
            targetKey = slot.targetKey,
            packageId = slot.packageId,
            outcome = DiagnosticOutcome.REJECTED,
            reasonCode = "HANDLER_THREW_SLOT_RETIRED",
            detailText = "处理器首次抛异常，槽已撤销（continueOnHandlerFailure=false，后续派发不再进入）",
        )
    }

    /** 写一条 HOOK 诊断；null diagnostics 直接旁路，不构造 detail。 */
    private fun recordHook(
        targetKey: String,
        packageId: String,
        outcome: DiagnosticOutcome,
        reasonCode: String,
        cause: Throwable? = null,
        detailText: String? = null,
    ) {
        val diagnostics = diagnostics ?: return
        val detail = detailText ?: cause?.let { exceptionName(it) }
        if (detail == null) {
            diagnostics.record(
                day = null,
                phase = null,
                domain = DiagnosticDomain.HOOK,
                packageId = packageId,
                key = targetKey,
                outcome = outcome,
                reasonCode = reasonCode,
            )
        } else {
            diagnostics.record(
                day = null,
                phase = null,
                domain = DiagnosticDomain.HOOK,
                packageId = packageId,
                key = targetKey,
                outcome = outcome,
                reasonCode = reasonCode,
                detail = { detail },
            )
        }
    }

    /** 一条 hook 槽（确定性序：priority 升序 + 声明序）。 */
    data class HookSlot(
        val targetKey: String,
        val packageId: String,
        val phase: HookPhase,
        val priority: Int,
        val order: Int,
        val mayMutateArgs: Boolean,
        val mayMutateReturn: Boolean,
        val runOnFailure: Boolean,
        val continueOnHandlerFailure: Boolean,
    )

    /** 一个处理器的失败记录（PackageId 归属，供降级报告）。 */
    class HookFailure(
        val packageId: String,
        val targetKey: String,
        val phase: HookPhase,
        val cause: Throwable,
    )

    /** 一次带 hook 调用的终局。 */
    class HookOutcome(
        val returnValue: ModValue?,
        val originalRan: Boolean,
        val replaced: Boolean,
        val failures: List<HookFailure>,
    )

    companion object {
        /** 从声明事务快照的 hook 声明构建（链接序 = 声明序）。 */
        fun of(
            declarations: List<DeclaredHook>,
            diagnostics: RuntimeDiagnostics? = null,
        ): HookPipeline {
            val grouped = LinkedHashMap<String, MutableList<HookSlot>>()
            declarations.forEachIndexed { index, declared ->
                val d = declared.declaration
                val slot = HookSlot(
                    targetKey = d.normalizedTargetKey(),
                    packageId = declared.packageId,
                    phase = d.phase,
                    priority = d.priority,
                    order = index,
                    mayMutateArgs = d.mayMutateArgs,
                    mayMutateReturn = d.mayMutateReturn,
                    runOnFailure = d.runOnFailure,
                    continueOnHandlerFailure = d.continueOnHandlerFailure,
                )
                grouped.getOrPut(slot.targetKey) { ArrayList() } += slot
            }
            for (list in grouped.values) {
                list.sortWith(compareBy({ it.priority }, { it.order }))
            }
            return HookPipeline(grouped.mapValues { (_, v) -> v.toList() }, diagnostics)
        }

        private fun canonical(key: String): String = key.trim().uppercase(Locale.ROOT)

        private fun toEnginePhase(phase: ModHookPhase): HookPhase = when (phase) {
            ModHookPhase.PREFIX -> HookPhase.PREFIX
            ModHookPhase.POSTFIX -> HookPhase.POSTFIX
        }

        /** [toEnginePhase] 的反向映射（[registrations] 投影槽相位回 mod-api 枚举）。 */
        private fun toModPhase(phase: HookPhase): ModHookPhase = when (phase) {
            HookPhase.PREFIX -> ModHookPhase.PREFIX
            HookPhase.POSTFIX -> ModHookPhase.POSTFIX
        }
    }
}

/** 失败归因只记异常类名（空名回退全限定名），不拼栈。 */
private fun exceptionName(t: Throwable): String =
    t.javaClass.simpleName.ifEmpty { t.javaClass.name }

/** 未授权 `mayMutateArgs` 时逐槽克隆实参，避免处理器改到调用方数组。 */
private fun isolatedArgs(slot: HookPipeline.HookSlot, args: Array<ModCallArg>): Array<ModCallArg> =
    if (slot.mayMutateArgs) {
        args
    } else {
        Array(args.size) { i ->
            val a = args[i]
            ModCallArg(isString = a.isString, intValue = a.intValue, strValue = a.strValue)
        }
    }

/**
 * 调一次处理器：先过单次耗时预算（装了才计时），内层保留既有耗时账本测量。
 *
 * 异常照原样抛出（由调用点的既有失败隔离接住），耗时在 finally 里照记 —— 抛错的处理器
 * 一样消耗时间，不该从归因里消失。预算为 null 时直接走 [invokeMeasured]，不取时钟、
 * 不拼操作名字符串。
 */
private fun invokeHandler(
    slot: HookPipeline.HookSlot,
    handler: HookHandler,
    context: HookContext,
    budget: ModHandlerBudget?,
    timing: ModTimingLedger?,
): HookResult = if (budget == null) {
    invokeMeasured(slot, handler, context, timing)
} else {
    budget.run(slot.packageId, DiagnosticDomain.HOOK, slot.targetKey + "." + slot.phase.name) {
        invokeMeasured(slot, handler, context, timing)
    }
}

/**
 * 既有耗时账本测量（`hook.<TARGETKEY>.PREFIX|POSTFIX`）；无账本时零开销直调。
 *
 * 逻辑与预算接入前逐字相同，只抽出来给 [invokeHandler] 的两条路径共用。
 */
private fun invokeMeasured(
    slot: HookPipeline.HookSlot,
    handler: HookHandler,
    context: HookContext,
    timing: ModTimingLedger?,
): HookResult = if (timing == null) {
    handler.onHook(context)
} else {
    timing.measureValue(slot.packageId, "hook." + slot.targetKey + "." + slot.phase.name) {
        handler.onHook(context)
    }
}
