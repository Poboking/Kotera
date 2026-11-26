package com.eratw.port.modtemplate

import com.eratw.port.modapi.ArchetypeKey
import com.eratw.port.modapi.BuiltinPhase
import com.eratw.port.modapi.ComponentKey
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FunctionKey
import com.eratw.port.modapi.HookOptions
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobKey
import com.eratw.port.modapi.ModCarrier
import com.eratw.port.modapi.ModCarrierDeclaration
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseKey
import com.eratw.port.modapi.intKey
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modapi.strKey

/**
 * 能力矩阵示范的载体声明：一个面一条最小声明，供 [TemplateCapabilityMod] 在运行期真跑。
 *
 * 与 [TemplateDeclarations] 分开成独立包身份（[PACKAGE_ID] 以 `.matrix` 结尾），因为能力矩阵的
 * 槽位（自定义相位 / hook 目标 / 交互相位 / 任务 / 设置）都是**声明期冻结**的：拆成两个载体才能
 * 让作者看清「先声明、运行期才允许注册」这条硬闸，且不会污染既有五段生命周期示范的声明面。
 *
 * 抄写要点：
 * - 运行期 `hooks.register` 的目标必须是本清单里 `hook(...)` 声明过的 `(目标名, 相位)`；
 * - 运行期 `interactions.registerHook` 的相位必须是本清单里 `interactionHook(...)` 声明过的；
 * - 运行期 `phases.subscribe` 的相位必须是 [BuiltinPhase.ALL] 之一或本清单 `phase(...)` 声明过的；
 * - 运行期 `jobs.bind` 的任务必须是本清单 `job(...)` 声明过的。
 */
@ModCarrier
object TemplateCapabilityDeclarations : ModCarrierDeclaration {

    /** 本清单的包身份；与模板主示范的 `eratw.template` 分开，读日志/存档分片时一眼可辨。 */
    const val PACKAGE_ID: String = "eratw.template.matrix"

    /** manifest 版本号（纯展示 / 兼容判定用）。 */
    const val VERSION: String = "0.1"

    override val packageId: String get() = PACKAGE_ID

    override fun newMod(): EraMod = TemplateCapabilityMod()

    /** 组件：能力矩阵的演员档案。 */
    val PROFILE: ComponentKey = ComponentKey("template.matrix.profile")

    /** 原型：引用 [PROFILE] 的演员。 */
    val ACTOR: ArchetypeKey = ArchetypeKey("template.matrix.actor")

    /** 组件字段：累计得分。 */
    val SCORE = intKey("template.matrix.score")

    /** 组件字段：最近一次备注。 */
    val NOTE = strKey("template.matrix.note")

    /** 周期任务槽：每游戏日一拍。 */
    val DAILY_PULSE: JobKey = JobKey("template.matrix.dailyPulse")

    /** 自定义相位：除三个内置相位之外，作者也可以声明自己的相位并在边界被派发。 */
    val ACTION_PHASE: PhaseKey = PhaseKey("TEMPLATE_MATRIX_ACTION")

    /**
     * 事件名：运行期 `events.register` 的键。**台架演示自造键** —— 宿主事件表（链接期
     * `SymbolCatalog.events`，即链接语料里定义过的事件函数名）里没有它：灌入了已知事件集的
     * 生产会话注册它当场 IAE、整 mod 被回滚禁用（TemplateEventGateTest 钉住此症状）；台架缺省
     * 空集不拦，所以模板单测全绿。真 mod 要挂事件就注册 `context.events.knownEventKeys()`
     * 里的真事件名。
     */
    const val PULSE_EVENT: String = "TEMPLATE.MATRIX_PULSE"

    /** hook 目标：POSTFIX + `mayMutateReturn`，演示「改返回值」。 */
    val ECHO_TARGET: FunctionKey = FunctionKey("TEMPLATE.MATRIX_ECHO")

    /** hook 目标：PREFIX + `mayMutateArgs`，演示「改入参」。 */
    val ARG_TARGET: FunctionKey = FunctionKey("TEMPLATE.MATRIX_ARG")

    /** hook 目标：PREFIX，演示「Replace 短路原函数」。 */
    val BLOCK_TARGET: FunctionKey = FunctionKey("TEMPLATE.MATRIX_BLOCK")

    /** UI 文案键：可点击交互项。 */
    const val UI_BUTTON_KEY: String = "template.matrix.ui.button"

    /** UI 文案键：故意抛异常的交互项（演示宿主侧失败隔离）。 */
    const val UI_POISON_KEY: String = "template.matrix.ui.poison"

    /** UI 图片资源名（相对 `<packageId>.assets/`）。 */
    const val UI_BANNER_RESOURCE: String = "images/template-matrix-banner.png"

    /** 枚举设置：`fast` / `slow`，改变业务行为的读值示范。 */
    const val MODE_KEY: String = "template.matrix.mode"

    /** [MODE_KEY] 的候选值之一。 */
    const val MODE_FAST: String = "fast"

    /** [MODE_KEY] 的候选值之一。 */
    const val MODE_SLOW: String = "slow"

    /** 整数设置：奖励基数（范围 1..5）。 */
    const val BONUS_KEY: String = "template.matrix.bonus"

    /** 布尔设置：是否显示「附加说明」按钮（驱动 UI 可见性谓词）。 */
    const val FLAG_KEY: String = "template.matrix.flag"

    /** 交互 ID：正常跑完全相位。 */
    const val INTERACTION_CHAT: String = "TEMPLATE_MATRIX_CHAT"

    /** 交互 ID：PRE 相位被 REPLACE 短路。 */
    const val INTERACTION_SKIP: String = "TEMPLATE_MATRIX_SKIP"

    /** 耗时 scope：作者自报业务段的稳定名字（不要用运行时拼接结果）。 */
    const val TIMING_SCOPE: String = "author.template.matrix.work"

    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = VERSION) {
        displayName("模板能力矩阵")
        author("eratw-port")
        description("mod-template 能力矩阵：events/hooks/interactions/phases/jobs/ui/rng/timing/settings")
        component(PROFILE) {
            int(SCORE)
            str(NOTE)
        }
        archetype(ACTOR) {
            uses(PROFILE)
        }
        phase(ACTION_PHASE)
        job(DAILY_PULSE, phase = BuiltinPhase.DAY_END, everyDays = 1L)
        hook(ECHO_TARGET, HookPhase.POSTFIX, HookOptions(mayMutateReturn = true))
        hook(ARG_TARGET, HookPhase.PREFIX, HookOptions(mayMutateArgs = true))
        hook(BLOCK_TARGET, HookPhase.PREFIX)
        interactionHook(InteractionPhase.PRE)
        interactionHook(InteractionPhase.POST)
        enumSetting(MODE_KEY, default = MODE_FAST, candidates = listOf(MODE_FAST, MODE_SLOW))
        intSetting(BONUS_KEY, default = 3L, range = 1L..5L)
        boolSetting(FLAG_KEY, default = false)
    }
}
