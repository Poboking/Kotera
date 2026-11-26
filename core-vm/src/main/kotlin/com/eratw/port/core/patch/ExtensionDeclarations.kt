package com.eratw.port.core.patch

import java.util.Locale

/**
 * 扩展能力声明（§20，ABI-9/10/11）—— 进声明事务的引擎侧权威形态（era-arch-v61u.md §20.4）。
 *
 * mod-api 侧有对应的干净声明形态（`HookDecl`/`InteractionHookDecl`/`PhaseDecl`/`JobDecl`），
 * 由 `ModDeclarationAdapter` 翻译到本文件；完整校验（重复、命名空间、相位引用可解析）
 * 由 `DeclarationRegistry.freeze` 承担。本文件只承载数据结构与逐项边界校验。
 */

/** 引擎侧 hook 相位（映射 mod-api `HookPhase`）。 */
enum class HookPhase { PREFIX, POSTFIX }

/** 引擎侧交互阶段（映射 mod-api `InteractionPhase`）。 */
enum class InteractionPhase { PRECONDITION, PRE, MAIN, POST, SETTLEMENT }

/**
 * 引擎固定相位名（§6.1 Game Phase Hooks）。
 *
 * Mod 自定义相位与它们同全局命名空间，但不得占用这些名字（声明事务拒绝）。
 */
object BuiltinPhases {
    const val DAY_START: String = "DAY_START"
    const val DAY_END: String = "DAY_END"
    const val ACTION: String = "ACTION"

    val ALL: Set<String> = setOf(DAY_START, DAY_END, ACTION)

    fun canonical(name: String): String = name.trim().uppercase(Locale.ROOT)
}

/**
 * 函数 hook 槽声明（§20.1，ABI-9）。
 *
 * [targetKey] 是函数键（查表时按大写归一）；[priority] 升序 + 声明序 = 确定性执行序。
 * [continueOnHandlerFailure] 是**本槽自己的处理器**抛异常后的处置：`true`（缺省，与新增字段前
 * 逐位一致）继续保持绑定；`false` 首次抛异常后撤销该槽。它不写进 manifest 文本格式（旧 manifest
 * 解析取缺省），也不是 [runOnFailure]（后者管被 hook 的函数抛错时本 hook 仍执行）。
 */
data class HookDeclaration(
    val targetKey: String,
    val phase: HookPhase,
    val priority: Int = 0,
    val mayMutateArgs: Boolean = false,
    val mayMutateReturn: Boolean = false,
    val runOnFailure: Boolean = false,
    val continueOnHandlerFailure: Boolean = true,
) {
    init {
        require(targetKey.isNotBlank()) { "hook 目标函数键不能为空" }
        require(priority >= 0) { "hook priority 不能为负：$priority" }
    }

    fun normalizedTargetKey(): String = targetKey.trim().uppercase(Locale.ROOT)
}

/** 交互阶段钩子声明（§20.3，ABI-11）。 */
data class InteractionHookDeclaration(
    val phase: InteractionPhase,
    val priority: Int = 0,
) {
    init {
        require(priority >= 0) { "interaction hook priority 不能为负：$priority" }
    }
}

/** 自定义游戏相位声明（§20.2，ABI-10）。名字全局唯一。 */
data class PhaseDeclaration(
    val name: String,
    val priority: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "相位名不能为空" }
        require(priority >= 0) { "相位 priority 不能为负：$priority" }
    }

    fun normalizedName(): String = BuiltinPhases.canonical(name)
}

/**
 * 定时/周期任务槽声明（§20.2，ABI-10）。[everyDays] 为 null = 一次性任务；
 * 否则每 [everyDays] 个游戏日触发一次。[phase] 必须是固定相位或已声明相位。
 */
data class JobDeclaration(
    val jobId: String,
    val phase: String = BuiltinPhases.DAY_END,
    val firstDay: Long = 0L,
    val everyDays: Long? = null,
    val priority: Int = 0,
) {
    init {
        require(jobId.isNotBlank()) { "jobId 不能为空" }
        require(phase.isNotBlank()) { "任务相位不能为空" }
        require(firstDay >= 0L) { "firstDay 不能为负：$firstDay" }
        require(everyDays == null || everyDays > 0L) { "everyDays 必须为正（null = 一次性）：$everyDays" }
        require(priority >= 0) { "任务 priority 不能为负：$priority" }
    }

    fun normalizedPhase(): String = BuiltinPhases.canonical(phase)
}

/** 动态任务实例的补算策略（§21.1，ABI-10）。 */
enum class JobCatchUp {
    /** 目标日补触发一次（默认）。 */
    MISS_ONCE,

    /** 错过即作废（`atDay < now.day` 不触发）。 */
    MISS_SKIP,

    /** 逐错过日各触发一次，必须同时声明 [JobTemplateDeclaration.maxCatchUp]。 */
    REPLAY,
}

/**
 * 动态任务模板声明（§21.1，ABI-10）——phase-jobs 声明域的第三种形态（Manifest 判别 tag B2）。
 *
 * 声明期逐项判死：`REPLAY` 必须 `maxCatchUp != null && > 0`；非 `REPLAY` 必须
 * `maxCatchUp == null`。相位引用可解析性（固定相位或本事务声明的相位）由声明事务 /
 * 适配器承担，本类型只做结构不变量。
 */
data class JobTemplateDeclaration(
    val templateKey: String,
    val phase: String = BuiltinPhases.DAY_END,
    val priority: Int = 0,
    val catchUp: JobCatchUp = JobCatchUp.MISS_ONCE,
    val maxCatchUp: Long? = null,
) {
    init {
        require(templateKey.isNotBlank()) { "任务模板键不能为空" }
        require(phase.isNotBlank()) { "任务模板相位不能为空" }
        require(priority >= 0) { "任务模板 priority 不能为负：$priority" }
        require((catchUp == JobCatchUp.REPLAY) == (maxCatchUp != null)) {
            if (catchUp == JobCatchUp.REPLAY) {
                "REPLAY 必须声明 maxCatchUp（正数上限）"
            } else {
                "非 REPLAY 不得声明 maxCatchUp：$maxCatchUp"
            }
        }
        require(maxCatchUp == null || maxCatchUp > 0L) { "maxCatchUp 必须为正：$maxCatchUp" }
    }

    fun normalizedPhase(): String = BuiltinPhases.canonical(phase)
}

/**
 * 带包身份的动态任务模板（调度器输入形态）。
 *
 * [DeclaredJob] 由 `DeclarationRegistry` 提供；本波 F2 不允许改声明事务文件，故模板包装
 * 落在本文件，形状与 [DeclaredJob] 同构（packageId + declaration）。等 DeclarationRegistry
 * 接入 jobTemplateDeclarations 后由组长切换为快照来源。
 */
data class DeclaredJobTemplate(
    val packageId: String,
    val declaration: JobTemplateDeclaration,
) {
    init {
        require(packageId.isNotBlank()) { "任务模板 packageId 不能为空" }
    }
}
