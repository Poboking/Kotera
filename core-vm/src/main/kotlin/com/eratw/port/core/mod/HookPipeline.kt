package com.eratw.port.core.mod

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.patch.DeclaredHook
import com.eratw.port.core.patch.HookPhase
import com.eratw.port.modapi.HookContext
import com.eratw.port.modapi.HookHandler
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

    private val handlers = HashMap<BindingKey, HookHandler>()

    /** 该函数键是否有声明的 hook 槽（大写归一）。 */
    fun hasHooks(targetKey: String): Boolean = slotsOf(targetKey).isNotEmpty()

    /** 该函数键的全部槽（确定性序：priority 升序 + 声明序）。 */
    fun slotsOf(targetKey: String): List<HookSlot> =
        slotsByTarget[canonical(targetKey)].orEmpty()

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
        return object : Registration {
            override fun unregister() {
                handlers.remove(key)
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
            val handler = handlers[BindingKey(slot.targetKey, slot.phase, slot.packageId)] ?: continue
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
                val result = handler.onHook(context)
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
                failures += HookFailure(slot.packageId, slot.targetKey, slot.phase, t)
                recordHook(
                    slot.targetKey,
                    slot.packageId,
                    DiagnosticOutcome.FAILED,
                    "HANDLER_THREW",
                    cause = t,
                )
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
                val result = handler.onHook(context)
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
                failures += HookFailure(slot.packageId, slot.targetKey, slot.phase, t)
                recordHook(
                    slot.targetKey,
                    slot.packageId,
                    DiagnosticOutcome.FAILED,
                    "HANDLER_THREW",
                    cause = t,
                )
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
            val context = HookContext(
                packageId = slot.packageId,
                targetKey = slot.targetKey,
                phase = ModHookPhase.POSTFIX,
                args = isolatedArgs(slot, args),
                returnValue = null,
            )
            try {
                handler.onHook(context)
                recordHook(slot.targetKey, slot.packageId, DiagnosticOutcome.OK, "OK")
            } catch (t: Throwable) {
                failures += HookFailure(slot.packageId, slot.targetKey, slot.phase, t)
                recordHook(
                    slot.targetKey,
                    slot.packageId,
                    DiagnosticOutcome.FAILED,
                    "HANDLER_THREW",
                    cause = t,
                )
            }
        }
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

    private fun exceptionName(t: Throwable): String =
        t.javaClass.simpleName.ifEmpty { t.javaClass.name }

    private fun isolatedArgs(slot: HookSlot, args: Array<ModCallArg>): Array<ModCallArg> =
        if (slot.mayMutateArgs) {
            args
        } else {
            Array(args.size) { i ->
                val a = args[i]
                ModCallArg(isString = a.isString, intValue = a.intValue, strValue = a.strValue)
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
    }
}
