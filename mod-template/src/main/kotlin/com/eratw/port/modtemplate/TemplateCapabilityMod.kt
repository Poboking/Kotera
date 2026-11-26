package com.eratw.port.modtemplate

import com.eratw.port.modapi.BuiltinPhase
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventHandler
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookHandler
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionHandler
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.PhaseHandler
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.measure

/**
 * 能力矩阵示范 mod：一个面一条**真注册**，运行时用 `:mod-testing` 的录制型替身逐面读数。
 *
 * 与 [TemplateDeclarations] 的骨架示范互补：那边只跑五段生命周期 + services/strings/resources，
 * 这边把作者面剩下的 events / hooks / interactions / phases / jobs / ui / rng / timing / settings
 * 各给一条最小可跑路径。每个注册都进 [registrations]，[onDisable] 逐条撤销 —— 这一步是给
 * 台架单测用的：生产宿主经 context 的注册收集器收走每个返回 Registration 的注册项，并在本包
 * [onDisable] 之前批量撤销（ModContextImpl 的 track 包装 + ModRuntimeHost 的先撤销后 onDisable
 * 序），返回值丢不丢都不泄漏；台架 `TestModSession.disable()` 只裸调 onDisable、不代撤
 * （RunMod.kt），要在台架断言「禁用后不再命中」就得像本类这样自持 Registration。
 *
 * 观测口径（台架）：
 * - events：`context.eventsRecorder.registrations` + `TestContext.dispatchEvent(...)` 返回值；
 * - hooks：`context.hooksRecorder.calls` + `TestContext.dispatchHook(...)` / `dispatchWithOriginal(...)`；
 * - interactions：`context.interactionsRecorder.registeredPhases` / `lastPhaseOrder`；
 * - phases：`TestContext.firePhase(...)` 返回派发数 + 本类计数器；
 * - jobs：`TestContext.fireJob(...)` + [dailyPulses]；
 * - ui：`context.uiRecorder.visibleKeys()` / `click(...)` + [buttonClicks]；
 * - rng / timing / settings：本类业务方法的返回值 + `timingRecorder.samples()`、`settingsRecorder`。
 *
 * 抄写提醒：hook 的 `Replace` 会短路原函数与同槽 POSTFIX；`SkipOriginal` 只在 PREFIX 有效；
 * POSTFIX 只有声明了 `mayMutateReturn` 的槽写 `context.returnValue` 才算数。
 */
class TemplateCapabilityMod : EraMod {

    private lateinit var context: ModContext
    private val registrations = mutableListOf<Registration>()

    /** `onEnable` 跑完为 true；`onDisable` 置回 false。 */
    var ready: Boolean = false
        private set

    /** 每日任务实际被派发的次数。 */
    var dailyPulses: Long = 0L
        private set

    /** 最近一次任务拿到的游戏日（验证 JobContext.time 真的来自派发方）。 */
    var lastPulseDay: Long = 0L
        private set

    /** 内置边界相位 `DAY_END` 的命中次数。 */
    var dayEndHits: Int = 0
        private set

    /** 最近一次相位回调拿到的游戏日（验证 PhaseContext.time 真的来自派发方）。 */
    var lastPhaseDay: Long = 0L
        private set

    /** 自定义相位 [TemplateCapabilityDeclarations.ACTION_PHASE] 的命中次数。 */
    var actionHits: Int = 0
        private set

    /** NORMAL 桶事件处理器命中次数。 */
    var normalEventHits: Int = 0
        private set

    /** LATER 桶事件处理器命中次数。 */
    var lateEventHits: Int = 0
        private set

    /** 最近一次事件回调看到的包身份（验证 EventContext 归属）。 */
    var lastEventPackage: String = ""
        private set

    /** PREFIX 入参钩子命中次数。 */
    var prefixHookHits: Int = 0
        private set

    /** POSTFIX 返回值钩子命中次数。 */
    var postfixHookHits: Int = 0
        private set

    /** 短路钩子命中次数。 */
    var blockHookHits: Int = 0
        private set

    /** 交互 PRE 相位命中次数。 */
    var preHookHits: Int = 0
        private set

    /** 交互 POST 相位命中次数。 */
    var postHookHits: Int = 0
        private set

    /** 正常交互项被点击的次数。 */
    var buttonClicks: Int = 0
        private set

    /** 自报耗时段的业务调用次数。 */
    var workCalls: Long = 0L
        private set

    // 处理器写成属性 lambda：onEnable 只剩注册清单，注册组的具体行为在下面逐条对应。
    // 这样每条示范一眼一个函数体，同时 onEnable 不会长到触发 detekt LongMethod。

    private val onDailyPulse: JobHandler = JobHandler { job ->
        dailyPulses += 1L
        lastPulseDay = job.time.day
    }

    private val onNormalEvent: EventHandler = EventHandler { event ->
        normalEventHits += 1
        lastEventPackage = event.packageId
        EventResult.CONTINUE
    }

    private val onLateEvent: EventHandler = EventHandler { _ ->
        lateEventHits += 1
        EventResult.CONTINUE
    }

    private val onDayEnd: PhaseHandler = PhaseHandler { phase ->
        dayEndHits += 1
        lastPhaseDay = phase.time.day
    }

    private val onActionPhase: PhaseHandler = PhaseHandler { _ ->
        actionHits += 1
    }

    private val onEchoPostfix: HookHandler = HookHandler { hook ->
        postfixHookHits += 1
        val current = hook.returnValue
        hook.returnValue = if (current is ModValue.StrValue) {
            ModValue.of(current.value + "|matrix")
        } else {
            ModValue.of("matrix")
        }
        HookResult.Continue
    }

    private val onArgPrefix: HookHandler = HookHandler { hook ->
        prefixHookHits += 1
        val second = hook.args.getOrNull(1)
        if (second != null && second.isString) {
            second.strValue = second.strValue + "!"
        }
        HookResult.Continue
    }

    private val onBlockPrefix: HookHandler = HookHandler { _ ->
        blockHookHits += 1
        HookResult.Replace(ModValue.of(BLOCKED_RESULT))
    }

    private val onInteractionPre: InteractionHandler = InteractionHandler { hook ->
        preHookHits += 1
        if (hook.interaction.interactionId == TemplateCapabilityDeclarations.INTERACTION_SKIP) {
            hook.replacedResult = ModValue.of(SKIPPED_RESULT)
            InteractionResult.REPLACE
        } else {
            InteractionResult.CONTINUE
        }
    }

    private val onInteractionPost: InteractionHandler = InteractionHandler { _ ->
        postHookHits += 1
        InteractionResult.CONTINUE
    }

    private val uiVisible: () -> Boolean = {
        context.settings.bool(TemplateCapabilityDeclarations.FLAG_KEY)
    }

    private val onButtonClick: () -> Unit = { buttonClicks += 1 }

    private val onPoisonClick: () -> Unit = {
        throw IllegalStateException(POISON_FAILURE_MESSAGE)
    }

    override fun onLoad(context: ModContext) {
        this.context = context
        context.strings.declare(
            UI_BUTTON_KEY,
            "按一下（能力矩阵）",
            "Tap (capability matrix)",
            "押す（能力行列）",
        )
        context.strings.declare(
            UI_POISON_KEY,
            "故意失败（隔离演示）",
            "Deliberate failure (isolation demo)",
            "わざと失敗（隔離デモ）",
        )
        context.strings.declare(
            UI_BANNER_ALT_KEY,
            "模板能力矩阵横幅",
            "Template capability matrix banner",
            "テンプレート能力行列バナー",
        )
    }

    override fun onEnable() {
        // 同一个声明对象的键密集出现，先取个短别名，注册清单更好读。
        val decl = TemplateCapabilityDeclarations
        track(context.jobs.bind(decl.DAILY_PULSE.name, onDailyPulse))
        // PULSE_EVENT 是台架演示自造键：宿主事件表（链接语料的事件函数名）里没有它，灌入了
        // 已知事件集的生产会话注册它当场 IAE（见 PULSE_EVENT 的 KDoc 与 TemplateEventGateTest）。
        track(context.events.register(decl.PULSE_EVENT, EventBucket.NORMAL, onNormalEvent))
        track(context.events.register(decl.PULSE_EVENT, EventBucket.LATER, onLateEvent))
        track(context.phases.subscribe(BuiltinPhase.DAY_END.name, onDayEnd))
        track(context.phases.subscribe(decl.ACTION_PHASE.name, onActionPhase))
        track(context.hooks.register(decl.ECHO_TARGET.name, HookPhase.POSTFIX, onEchoPostfix))
        track(context.hooks.register(decl.ARG_TARGET.name, HookPhase.PREFIX, onArgPrefix))
        track(context.hooks.register(decl.BLOCK_TARGET.name, HookPhase.PREFIX, onBlockPrefix))
        track(context.interactions.registerHook(InteractionPhase.PRE, onInteractionPre))
        track(context.interactions.registerHook(InteractionPhase.POST, onInteractionPost))
        track(
            context.ui.registerImage(
                decl.UI_BANNER_RESOURCE,
                altText = context.strings.of(UI_BANNER_ALT_KEY),
                maxSidePx = ModUiElement.Image.SUGGESTED_SIDE_PX,
            ),
        )
        track(
            context.ui.registerInteraction(
                decl.UI_BUTTON_KEY,
                visible = uiVisible,
                onClick = onButtonClick,
            ),
        )
        track(
            context.ui.registerInteraction(
                decl.UI_POISON_KEY,
                visible = uiVisible,
                onClick = onPoisonClick,
            ),
        )
        ready = true
    }

    override fun onDisable() {
        ready = false
        registrations.forEach { registration -> registration.unregister() }
        registrations.clear()
    }

    override fun onUnload() {
        registrations.clear()
        ready = false
    }

    private fun track(registration: Registration) {
        registrations += registration
    }

    /**
     * rng 面：同种子、同调用序 ⇒ 逐位可复现（`context.rng` 是作者面独立随机流，不动引擎 RAND）。
     *
     * 台架用 `TestWorld.of(..., decisionSeed = N)` 固定种子；生产按存档决策种子。
     */
    fun rollChallenge(dice: Int): List<Int> =
        List(dice.coerceAtLeast(1)) { context.rng.nextInt(1, DICE_FACES + 1) }

    /** rng 面的百分比入口：0 恒 false、100 恒 true、中间按流抽取。 */
    fun isLucky(): Boolean = context.rng.chance(LUCKY_PERCENT)

    /**
     * timing 面：把一段业务耗时记进本包账本（`measure` = 起止 + try/finally 落账）。
     *
     * scope 用固定名 [TemplateCapabilityDeclarations.TIMING_SCOPE]；不要用运行时拼接结果
     * （单包 scope 数上限 256，到顶后新 scope 只记 WARN 并忽略）。
     */
    fun measureWork(): Long = context.timing.measure(TemplateCapabilityDeclarations.TIMING_SCOPE) {
        workCalls += 1L
        workCalls
    }

    /** settings 面：每次现读，改设置后下一次调用即生效（不缓存）。 */
    fun reward(): Long {
        val base = if (context.settings.enumName(TemplateCapabilityDeclarations.MODE_KEY) ==
            TemplateCapabilityDeclarations.MODE_SLOW
        ) {
            SLOW_REWARD
        } else {
            FAST_REWARD
        }
        return base + context.settings.int(TemplateCapabilityDeclarations.BONUS_KEY)
    }

    /** resources 面：读本包随包资源的字节数（不存在返回 0，不抛）。 */
    fun bannerBytes(): Int =
        context.resources.open(TemplateCapabilityDeclarations.UI_BANNER_RESOURCE)?.size ?: 0

    companion object {
        /** UI 按钮文案键（`onLoad` 里三语声明）。 */
        const val UI_BUTTON_KEY: String = TemplateCapabilityDeclarations.UI_BUTTON_KEY

        /** 故意失败按钮文案键。 */
        const val UI_POISON_KEY: String = TemplateCapabilityDeclarations.UI_POISON_KEY

        /** 横幅 alt 文案键。 */
        const val UI_BANNER_ALT_KEY: String = "template.matrix.ui.banner_alt"

        /** 短路钩子交给宿主的替换值（测试与示范共用同一字面量）。 */
        const val BLOCKED_RESULT: String = "matrix.blocked"

        /** PRE 相位短路交互时交出去的替换结果。 */
        const val SKIPPED_RESULT: String = "matrix.skipped"

        /** 故意失败按钮的异常文案（台架会把它写进隔离日志）。 */
        const val POISON_FAILURE_MESSAGE: String = "模板能力矩阵：故意失败"

        /** 骰面数（`nextInt(1, DICE_FACES + 1)`）。 */
        const val DICE_FACES: Int = 6

        /** 幸运判定百分比。 */
        const val LUCKY_PERCENT: Int = 50

        /** `fast` 模式的基础奖励。 */
        const val FAST_REWARD: Long = 10L

        /** `slow` 模式的基础奖励。 */
        const val SLOW_REWARD: Long = 20L
    }
}
