package com.eratw.port.modtemplate

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.TimingSample
import com.eratw.port.modapi.add
import com.eratw.port.modapi.bind
import com.eratw.port.modapi.get
import com.eratw.port.modapi.put
import com.eratw.port.modapi.query

/**
 * 模板 mod 入口：四段生命周期各有实义，外加 `rng` / `events` / `hooks` 各一处调用。
 *
 * 作者面覆盖（本模板就是各面的「可直接抄」清单，改包身份即可）：
 * - 实体 / 关系 / 查询 / 存储 / 任务 / 事件 / 钩子：见 [onLoad] / [onEnable] / [onDailyTick]；
 * - 浮层图片（v8 `context.ui.registerImage`）、三语文案（v10 `context.strings`）、
 *   音频（v11 `context.audio`）：见 [onLoad] / [onEnable] / [onDisable]；
 * - 耗时读写（v13 `context.timing.record` / `context.timing.measure` 的注释示范）：见 [slowestScope]；
 * - 诊断日志（`context.logger`）：三档 `info` / `warn` / `error(message, cause)` 的实调见 [onEnable]。
 *
 * ## 日志面（作者排查用，不是玩家可见文案）
 *
 * 生产 `PackageLogger` 对作者日志有三条硬化，防止一个手滑的 `onFrame` 日志把设备刷卡：
 * 单条超 **1000 字符**截断并追加「…(截断)」（cause 在截断之后拼接）；**空白消息**（`isBlank()`
 * 且无 cause）静默丢弃、不占行数；每个 context 最多 **1000 行**正文，越限后只落一条元告警
 * （「日志超过 1000 条上限…本行之前已抑制 N 条」）。所以日志要写「有信息量的少量行」，不要当
 * 轮询/调试打印通道；玩家可见文案走 [onLoad] 的三语文案面。
 *
 * `:mod-testing` 的日志台架**缺省不硬化**（作者断言看到自己写的原文），要复现生产这三条就调
 * `context.hardenLogger()`（缺省参数 = 生产 1000/1000 口径），写法见台架 `TestContext` 的 KDoc。
 *
 * 台架注意：`:mod-testing` 的 `TestContext.audio` 是**录制型**（`RecordingAudio`），与生产
 * `PackageAudioApi` 同一套降级链（缺资源 / 非法名 ⇒ WARN + `false`，音量夹取）。作者单测先用
 * `world.resource(...)` 把随包音频放进台架资源目录，再断言 `context.audioRecorder.plays` /
 * `stopCount` 即「我播了什么」；写法见 `TemplateShowcaseTest`。
 *
 * ## 抄写模板时改这里
 *
 * - [onLoad]：只拿 `context`、恢复 `storage` 里的自持久化数据；**不要**在这里改实体结构
 *   （此时宿主还没放行转移边界，§18.4）。
 * - [onEnable]：注册任务 / 事件 / 钩子，把返回的 [Registration] 攒起来。
 * - [onDisable]：撤销注册（生产宿主在 onDisable 之前本就会自动撤销本包全部注册项，这里手动撤
 *   是给台架单测断言「禁用后不再命中」用的，两处幂等不冲突）、把内存状态写回 `context.storage`
 *   （禁用后数据必须还在，§18.6）。
 * - [onUnload]：清实例级可变集合。**铁律 4：无静态可变全局**，状态一律挂实例。
 *
 * 业务代码一律写类型化视图（[TemplateView]），不自己碰 [EntityId.raw]；
 * 这样字段拼错是编译错误，而不是静默读回失败哨兵。
 */
class TemplateMod : EraMod {

    private lateinit var context: ModContext
    private lateinit var actors: TemplateFacade

    /** 本会话注册的任务 / 事件 / 钩子，`onDisable` 统一撤销。 */
    private val registrations = mutableListOf<Registration>()

    private var tickCount = 0L
    private var enabled = false

    /** 诊断 / 测试用状态（纯值，不暴露引擎内部类型）。 */
    val state: TemplateModState
        get() = TemplateModState(enabled, tickCount)

    override fun onLoad(context: ModContext) {
        this.context = context
        this.actors = TemplateFacade(context.entities)
        tickCount = context.storage.get(TemplateDeclarations.TICK_COUNT)?.toLongOrNull() ?: 0L
        // 三语文案面（v10）：只在 onLoad 声明、一次给齐 ZH/EN/JA（键只许小写字母/数字/_/./-，<= 64）。
        // 未声明的键 of() 原样回键（不抛），declare 与 of 的键必须逐字一致（check-mod 会对不上报 WARN）。
        context.strings.declare(
            GREETING_KEY,
            zh = "模板 Mod 已装上：按 D 打开调试面板",
            en = "Template mod loaded: press D to open the debug panel",
            ja = "テンプレート Mod を読み込みました：D でデバッグパネルを開く",
        )
    }

    override fun onEnable() {
        enabled = true
        // 日志面：正常流程走 info，一条说清「谁启用了什么」。日志只给作者排查，玩家可见文案走上面的
        // 三语文案面 —— 别把玩家该读的字塞进 logcat。
        context.logger.info("模板 Mod 已启用：任务/事件/钩子已注册，示例图片与背景音乐已请求加载。")
        // context.jobs：把处理器绑到 Manifest 声明过的任务槽（未声明 / 重复绑定会抛 IAE）。
        registrations += context.jobs.bind(TemplateDeclarations.DAILY_TICK) { onDailyTick() }
        // context.events：四桶事件；ONLY 桶运行期不可注册，要用 hooks 做前后拦截。
        // 事件键必须是宿主事件表（链接语料定义过的事件函数名）里的名字，不是自由命名：生产会话
        // 灌入了已知事件集，注册不在集内的键（包括本示范的自造键 EVENT_DAY_TICK）当场 IAE、
        // 整个 mod 被回滚禁用；台架缺省不灌事件表所以单测拦不住（TemplateEventGateTest 钉此
        // 症状）。真 mod 注册前用 context.events.exists / knownEventKeys 自查。
        registrations += context.events.register(EVENT_DAY_TICK, EventBucket.NORMAL) {
            context.logger.info("模板事件收到：$EVENT_DAY_TICK")
            EventResult.CONTINUE
        }
        // context.hooks：只能绑 Manifest 声明过的槽；这里演示只读 Prefix 观察。
        registrations += context.hooks.register(
            TemplateDeclarations.PING_TARGET.name,
            HookPhase.PREFIX,
        ) {
            HookResult.Continue
        }
        // 跨 mod 自查「谁在钩同一函数、什么顺序、priority 撞没撞」：context.hooks.registrations() 返回
        // 全部已声明槽的只读快照（functionKey 字典序 → priority 升序 → 声明序，同函数相邻两条槽
        // 且包名不同 = 撞车嫌疑）；抛 UOE = 宿主未接线，不是「没钩子」—— 没钩子答空表、不抛。
        // 浮层图片（v8）：只声明资源名 + 替代文本 + 期望最大边长；缺图 / 坏图由宿主降级渲染 altText
        // 或占位框并记 WARN，不会崩。替代文本走上面的三语文案面（v10），两个面可以一起用。
        registrations += context.ui.registerImage(
            BANNER_RESOURCE,
            altText = context.strings.of(GREETING_KEY),
            maxSidePx = ModUiElement.Image.SUGGESTED_SIDE_PX,
        )
        // 音频（v11）：播随包 BGM（loop = true 持续到下一次 play / stop）；字节从本包资源目录按名读。
        // 宿主是否真的出声不保证（缺文件 / 坏容器 / 播放器被占用 ⇒ false + WARN，不抛、不砖游戏）。
        // 日志面：可降级的失败要自己记一条 WARN，别让「我播了但没声」变成无痕悬案。
        val playing = context.audio.play(BGM_RESOURCE, loop = true, volume = BGM_VOLUME)
        if (!playing) {
            context.logger.warn(
                "示例背景音乐未随包提供或不可播放，已跳过播放；把音频放到 <packageId>.assets/audio/ 即可启用。",
            )
        }
    }

    override fun onDisable() {
        enabled = false
        // 音频（v11）：播放起过 BGM 就必须在这里收；stop() 幂等，没有在播也安全。
        context.audio.stop()
        registrations.forEach { registration -> registration.unregister() }
        registrations.clear()
        context.storage.put(TemplateDeclarations.TICK_COUNT, tickCount.toString())
    }

    override fun onUnload() {
        registrations.clear()
        actors = TemplateFacade(context.entities)
        tickCount = 0L
    }

    /**
     * 骨架业务：建一个演员并（可选）挂师承边。真实 mod 换成你自己的业务动作。
     *
     * `create` 放第一步（freeze 之后唯一的失败模式是实体不存在）；后续写字段在实体存在的前提下
     * 结构上不会失败，因此不需要暂存 / 补偿 / 回滚。
     */
    fun spawnActor(nickname: String, mentor: EntityId?): EntityId {
        val view = actors.create()
        view.nickname = nickname
        view.level = INITIAL_LEVEL
        if (mentor != null && !mentor.isNull) {
            view.mentor = mentor
            // 反向边（MENTORED_BY）由声明的 autoInverse 自动生成，业务不手写第二遍。
            context.relationships.add(mentor, TemplateDeclarations.MENTORS, view.id)
        }
        return view.id
    }

    /**
     * 每游戏日一拍：`context.rng` 决定今天是否全体升级（演示随机面的确定性情用法）。
     *
     * `context.rng` 与 ERB 的 `RAND` 是两条独立流：本方法抽数不会让引擎侧已校准的随机消费序漂移。
     */
    fun onDailyTick() {
        tickCount += 1L
        val lucky = context.rng.chance(LUCKY_CHANCE_PERCENT)
        for (actor in context.entities.query(TemplateKeys.archetype)) {
            val view = actors.of(actor)
            if (lucky) {
                view.level = view.level + 1L
            }
        }
        context.storage.put(TemplateDeclarations.TICK_COUNT, tickCount.toString())
    }

    /**
     * 发布一条跨 mod 服务（`context.services.publish`）：真实 mod 在 [onLoad] 末尾调一次。
     *
     * 契约：同一接口类型全链**只许一个发布方**；同一包重复发布 = 幂等替换，
     * 两个包发布同一接口 ⇒ 消费方 `find` 抛 [IllegalStateException] 并点名双方。
     * 禁用 / 卸载自动撤销，消费方随即 `find` 到 null —— 别缓存服务实例，每次现查。
     */
    fun installGreetingService() {
        context.services.publish(
            TemplateGreetingService::class.java,
            object : TemplateGreetingService {
                override fun greeting(mode: String): String = "hello from ${context.packageId} ($mode)"
            },
            context.packageId,
        )
    }

    /**
     * 演示另一处 `context.services.find`：**找不到就是 null**，不等待、不重试。
     *
     * 依赖别的包发布的服务时，靠 manifest 的 `loadAfter` 保证装配顺序；
     * 服务是可选能力，消费方请把 null 当「未安装」处理，不要抛给玩家。
     */
    fun greetingFromService(): String? =
        context.services.find(TemplateGreetingService::class.java)?.greeting("service")

    /**
     * 按 per-mod 设置产出问候（`context.settings` **每次调用现读**，改设置后下一拍即生效）。
     *
     * 设置是设备级、跨存档的（不属于存档数据）：换存档不重置；读未声明的键会抛
     * [IllegalArgumentException]。玩家可见文案本身仍走 v10 的 `context.strings`（[GREETING_KEY]）；
     * [TemplateDeclarations.GREETING_MODE_FORMAL] 只在句末补一个 ` [包身份]` 落款作行为差异演示。
     */
    fun greetingLines(): List<String> {
        val mode = context.settings.enumName(TemplateDeclarations.GREETING_MODE_KEY)
        val count = context.settings.int(TemplateDeclarations.GREET_COUNT_KEY).toInt().coerceAtLeast(1)
        if (context.settings.bool(TemplateDeclarations.VERBOSE_KEY)) {
            context.logger.info("模板问候：mode=$mode count=$count")
        }
        val greeting = if (mode == TemplateDeclarations.GREETING_MODE_FORMAL) {
            context.strings.of(GREETING_KEY) + " [" + context.packageId + "]"
        } else {
            context.strings.of(GREETING_KEY)
        }
        return List(count) { greeting }
    }

    /**
     * 耗时读数：取本包全部 scope（引擎四相位 / tick / hook / event，以及作者自报的段）里最慢的一档。
     *
     * 框架自动给已知测量点记账；v13 起作者面也有**写侧**，可把自定义业务段记进同一张表
     * （只记账、不改行为，`samples()` 里与引擎 scope 一起按总耗时降序）。下面只作注释示范，
     * 模板本身不写读数（保持 `check-mod` 的 0 发现基线）：
     *
     * ```kotlin
     * // 手动记一笔：scope 建议 author.<模块>.<动作>（空白 / 超 128 字符 / 负 nanos ⇒ IllegalArgumentException）。
     * val started = System.nanoTime()
     * doSlowThing()
     * context.timing.record("author.template.slowThing", System.nanoTime() - started)
     *
     * // 或用 measure 包一段：自动起止 + try/finally，block 抛异常也照记一笔（异常原样透传）。
     * val lines = context.timing.measure("author.template.greeting") { greetingLines() }
     * ```
     *
     * 别用运行时拼接的结果当 scope（单包 scope 数上限 256，到顶后新 scope 只记 WARN 并忽略）。
     * 作者自测口径：`context.timing.reset()` → 跑你要测的业务 → `samples()` 看归因；
     * 台架 `:mod-testing` 的 timing 是录制型替身（`RecordingTiming`）：单测里用
     * `context.timingRecorder.record(scope, nanos)` / `measure(scope) { … }` 记账，再读
     * `samples()` 断言；作者不必自己再写替身。
     */
    fun slowestScope(): TimingSample? =
        context.timing.samples().maxByOrNull { it.totalNanos }

    /**
     * 演示面用到的键 / 资源名：真实 mod 换成你自己的。
     *
     * 图片与音频资源名都相对包资源目录 `mods/rt2/<packageId>.assets/`。模板本身不随包发布，
     * 所以这里没有对应文件；抄到自己 mod 时把 `images/`、`audio/` 放进去，`modgen check-mod` 会校验
     * 「声明的名字 ↔ 真有文件」（缺文件 WARN、名字非法 ERROR）。
     */
    companion object {
        /** 三语文案键（v10）：declare 与 of 必须逐字一致。 */
        const val GREETING_KEY: String = "template.greeting"

        /** 浮层图片资源名（v8）：包资源目录下 `images/` 里的相对路径。 */
        const val BANNER_RESOURCE: String = "images/template-banner.png"

        /** 背景音乐资源名（v11）：包资源目录下 `audio/` 里的相对路径。 */
        const val BGM_RESOURCE: String = "audio/template-theme.ogg"

        /** 背景音乐线性音量 0.0..1.0（越界由宿主夹取）。 */
        const val BGM_VOLUME: Float = 0.8f

        /** 每游戏日全体升级概率（百分比）。 */
        private const val LUCKY_CHANCE_PERCENT: Int = 50

        /**
         * 事件示范键：**台架专用的自造键，不是宿主事件表里的真事件**。生产会话（已知事件集
         * 非空）注册它会当场 IAE、整 mod 被回滚禁用 —— TemplateEventGateTest 钉住这个生产症状；
         * 真 mod 要挂事件就注册 `context.events.knownEventKeys()` 里的真事件名。
         */
        const val EVENT_DAY_TICK: String = "TEMPLATE.DAY_TICK"
        private const val INITIAL_LEVEL: Long = 1L
    }
}

/** [TemplateMod] 的可读状态快照（纯值，不含引擎类型）。 */
data class TemplateModState(
    val enabled: Boolean,
    val tickCount: Long,
)

/**
 * 演示用跨 mod 服务接口：**接口类型就是服务的身份**（`context.services` 按 `Class<T>` 查）。
 *
 * 发布：`context.services.publish(TemplateGreetingService::class.java, 实现, 你的 packageId)`
 * （真实 mod 在自己 `onLoad` 末尾做一次，见 [TemplateMod.installGreetingService]）；
 * 消费：`context.services.find(TemplateGreetingService::class.java)`，null = 没人发布或发布方已卸载。
 * 同一接口类型全链只允许一个发布方：第二个包再发布 ⇒ 消费方 `find` 抛 [IllegalStateException]；
 * 同包重复发布只是幂等替换，不抛。
 */
interface TemplateGreetingService {
    /** 返回一句问候；`mode` 由发布方自行解释（本模板只区分 brief / formal）。 */
    fun greeting(mode: String): String
}
