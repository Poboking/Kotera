package com.eratw.port.modapi

/**
 * mod 的身份与服务入口（RT2 Kotlin Mod SDK，era-arch-v61u.md §18.3 的 ModContext）。
 *
 * 引擎在 [EraMod.onLoad] 时注入。mod 经本入口访问 Runtime 能力，**不持有** `Vm`/`Program`/
 * 内部 `VariableStore` 实现对象（§18.2 铁边界）——各 API 都是 facade，wrap 引擎内部、藏实现细节。
 *
 * @property packageId 本 mod 的包身份（声明事务/存档分片/注册隔离都按它隔离，§5/§8）。
 */
interface ModContext {
    val packageId: String

    /**
     * 本 mod 的日志面（按 PackageId 归属，不污染引擎日志）。
     *
     * 落行形如 `[<packageId>][INFO] 消息`（`warn` = `[WARN]`、`error` = `[ERROR]` 并追加
     * ` cause=<简单类名>:<message>`，不打堆栈）。三条硬化保证长跑不拖慢玩家：
     * 单条消息超 1000 个字符截断并追加 `…(截断)`；空白消息静默丢弃；每个 packageId 一次装配最多
     * 落 1000 行，之后只打一条元告警再静默。**日志不是游戏状态**，可在任意线程调用。
     */
    val logger: ModLogger

    /** 主执行调度器（§18.5 线程模型）：改游戏状态一律经它排队。 */
    val runtime: RuntimeScheduler

    /** 实体创建/查询/修改/销毁（wrap IEntityKernel）。 */
    val entities: EntityApi

    /** 亲属/师徒/从属等有向关系（wrap IEntityKernel 关系面 + 关系投影协议）。 */
    val relationships: RelationshipApi

    /**
     * 原版角色槽投射面（RT2 车次表 E 车 / P1-6 ①，era-arch-v61u.md §22.5 / §23.2 F2）。
     *
     * 家族类 mod 经本面把扩展实体投射进原版角色表（登场）与撤回（退场），并读
     * [RosterProjectionApi.rosterGeneration] 自检缓存行号是否过期；这是 F2 调用序列的
     * 唯一公开入口，mod 不持有 `ICharaSlotProjector`、不直接碰 `VariableStore`（§18.2 铁边界）。
     *
     * 装配状态：宿主注入真投射器时由引擎侧适配器转发；**未注入时本面是显式 fail-closed** ——
     * 四个入口一律抛 [UnsupportedOperationException] 点名 F 车装配缺口，不返回静默的
     * -1 / false / 0（与 [actions] 的未注入桩同口径）。真装置属 F 车（MR-2b-2），不是本面职责。
     *
     * TODO(verify)：本属性是移植侧 RT2 自建公开面，原版 Emuera 无对应 SDK 属性可比；
     * 卡点 = 无官方 SDK 可比，只能以 §23.2 F2 调用场景与真引擎纵向切片作依据。
     */
    val roster: RosterProjectionApi

    /** 事件处理器/游戏相位钩子（§6.1 贡献点；运行期钩子接线属 K3）。 */
    val events: EventApi

    /** 函数 Prefix/Postfix 钩子（§20.1，ABI-9；槽声明在 Manifest，运行期只绑定处理器）。 */
    val hooks: HookApi

    /** 自定义游戏相位订阅（§20.2，ABI-10；相位名在 Manifest 声明）。 */
    val phases: PhaseApi

    /** 定时/周期任务绑定（§20.2，ABI-10；任务槽在 Manifest 声明，任务态随 .rt2 持久化）。 */
    val jobs: JobApi

    /** 多目标交互阶段钩子与运行入口（§20.3，ABI-11）。 */
    val interactions: InteractionApi

    /**
     * 统一决策/仲裁 facade（§21.3，ABI-12 Candidate）：候选 provider、处理器、约束谓词都按
     * [packageId] 包作用域注册，跨包互不可见（同包候选才被收集，跨包引用 = 未注册）。
     *
     * 装配状态：宿主注入 `DecisionPipeline` 时委托其包作用域 `actionApi(packageId)`；
     * 宿主未注入时本属性是**显式 fail-closed** 桩——`provide`/`on`/`constraint` 三个注册入口
     * 一律抛 [UnsupportedOperationException]（点名 packageId 与 §12.1），不返回静默 no-op 注册。
     */
    val actions: ActionApi

    /**
     * 组件/关系谓词查询 facade（§21.5，**P1 工程契约，非 ABI**）：只读查询，结果按
     * [EntityId] 原始 Long 数值升序、是独立快照列表。
     *
     * 装配状态：恒可用——引擎侧实现 `KernelQueryApi` 只 wrap context 上的实体内核，
     * 与决策管线无关（未注入 `DecisionPipeline` 时照样可查）。
     */
    val query: QueryApi

    /** 注册可被 ERB 调的函数（wrap NativeMethodRegistry / CALLSHARP）。 */
    val functions: FunctionApi

    /** mod 自持久化数据（wrap SaveService → .rt2，按 PackageId 隔离）。 */
    val storage: StorageApi

    /** 读受授权游戏状态（VariableStore 读侧 curated facade）。 */
    val gameState: GameStateApi

    /**
     * 确定性随机数面（§21.6，**P1 工程契约，非 ABI**）。
     *
     * **与 ERB 的 `RAND` 是两条独立流**：本面不消费原版随机流、也不影响 `RANDDATA`
     * （细则见 [RandomApi]）。种子由宿主按「会话决策种子 + packageId」派生成独立
     * SFMT19937，因此同一存档 + 同一操作序列可复现，且不会让引擎侧已校准的随机消费序漂移。
     *
     * 装配状态：宿主装配时注入派生流；未注入时本属性是**显式 fail-closed** 桩 ——
     * 五个抽数方法一律抛 [UnsupportedOperationException]（点名 packageId 与 §12.1），
     * 不返回静默的 0 / false 兜底（那会让 mod 作者以为随机生效了）。
     */
    val rng: RandomApi

    /**
     * 耗时归因面（§18.5 诊断面扩展；RT2 P1 工程契约）。
     *
     * 框架在 lifecycle / tick / hook / event 四个点给**每个 mod 单独**记账；作者也可经
     * [TimingApi.record] / [TimingApi.measure] 把自己的代码段记进**同一张表**，据此找出自己慢在
     * 哪一段（作用域表与口径见 [TimingApi]）。
     *
     * 装配状态：宿主装配时注入会话账本；未注入（直接构造的 context、旧装配路径）时本属性是
     * **显式 fail-closed** 桩 —— [TimingApi.samples] / [TimingApi.record] 都抛
     * [UnsupportedOperationException]，读不返回静默空表、写不静默丢弃（那会让作者以为自己的 mod
     * 不耗时 / 计时生效了）。
     *
     * 只读本包：拿不到别的 mod 的读数。只在 VM 线程调用。
     */
    val timing: TimingApi

    /**
     * per-mod 设置项读数（RT2 per-mod 配置面；作者在 Manifest 里声明一次，设置页自动出，
     * 值按包持久化在设备侧，跨存档/跨局保留）。
     *
     * 装配状态：宿主在 `onLoad` 之前注入本包设置快照；**未注入时本属性是显式 fail-closed 桩** ——
     * 五个读取入口一律抛 [UnsupportedOperationException]（点名 packageId），不返回声明默认值
     * （静默默认值会让作者以为玩家改的值生效了）。语义与写面见 [SettingsApi]。
     */
    val settings: SettingsApi

    /**
     * 跨 mod Kotlin 服务注册面（RT2 P1 工程契约；会话级一份，全链共享）。
     *
     * 发布方在 `onLoad` 里 `publish(SomeApi::class.java, impl)`，消费方在**自己的 `onLoad`
     * 里晚于对方** `find(SomeApi::class.java)`；装载顺序经 Manifest 的 `loadAfter` 声明。
     * 四条契约边界（线程 / 顺序 / 同类型唯一发布方 / 卸载即撤销）见 [ModServiceRegistry]。
     *
     * 装配状态：宿主在 `onLoad` 之前注入会话级注册表；**未装配时本属性是显式 fail-closed 桩** ——
     * 四个入口一律抛 [UnsupportedOperationException]（点名 packageId），不返回静默空注册表
     * （空表会让作者以为服务没发出去，也掩盖装配错误）。
     *
     * 缺省实现只为「不装配的测试台架 / 旧 [ModContext] 实现」不破编译，生产上下文覆盖它；
     * 需要读取服务的实现类不应依赖缺省值。
     */
    val services: ModServiceRegistry
        get() = throw UnsupportedOperationException(
            "服务注册面未装配（$packageId）：宿主应在 onLoad 之前注入会话级 ModServiceRegistry",
        )

    /**
     * mod 自带资源面（RT2 资源注入第一刀；P1 工程契约）。
     *
     * 作者把随包资源放在设备侧 `mods/rt2/<packageId>.assets/`，经本面按名字只读取字节
     * （按包隔离、只读、不缓存）。名字语义、大小写结论、上限与「不做渲染/音频接线、
     * 不做 `.erbm` 内嵌资源抽取」的边界见 [ModResourceApi]。
     *
     * 装配状态：宿主在 `onLoad` 之前注入本包视图；**未注入时本属性是显式 fail-closed 桩** ——
     * 本属性的缺省实现直接抛 [UnsupportedOperationException]（点名 packageId），
     * 不返回空表 / null 兜底（静默空资源会让作者以为自己的资源打进包了）。
     */
    val resources: ModResourceApi
        get() = throw UnsupportedOperationException(
            "资源面未装配：$packageId（宿主未注入 ModResourceApi；本会话/旧装配路径不提供资源）。" +
                "不返回空表兜底。",
        )

    /**
     * mod 自带三语文案面（RT2：作者文案不必再挤宿主表键）。
     *
     * 作者在 `onLoad` 里 `declare(key, zh, en, ja)` 声明，宿主按当前设置语言解析并上屏
     * （交互贡献项标签优先取本包文案，未声明才回落宿主既有呈现）。键形态、重复声明、
     * 回退链与语言来源见 [StringsApi]。
     *
     * 装配状态：宿主在 `onLoad` 之前注入本包视图；**未注入时本属性是显式 fail-closed 桩** ——
     * 四个入口一律抛 [UnsupportedOperationException]（点名 packageId），不返回空表 / 键本身兜底
     * （静默兜底会让作者以为文案声明生效了）。
     */
    val strings: StringsApi
        get() = throw UnsupportedOperationException(
            "文案面未装配：$packageId（宿主未注入 StringsApi；本会话/旧装配路径不提供 mod 自带文案）。" +
                "不返回空表兜底。",
        )

    /**
     * mod 音频面（RT2 资源注入第二刀；Lane AN）：播放本包资源目录里的 BGM / 音效。
     *
     * 字节来源、名字规则、大小上限与降级纪律见 [AudioApi]；只在 VM 线程调用。宿主是否真的
     * 出声不保证（缺文件 / 坏容器 / 播放器被占用 ⇒ `false` + WARN，不抛、不砖游戏）。
     *
     * 装配状态：宿主在 `onLoad` 之前注入本包播放适配器；**未注入时本属性是显式 fail-closed
     * 桩** —— 缺省实现直接抛 [UnsupportedOperationException]（点名 packageId），不返回静默
     * `false` 兜底（静默 false 会让作者以为播放成功只是手机没声音）。
     */
    val audio: AudioApi
        get() = throw UnsupportedOperationException(
            "音频面未装配：$packageId（宿主未注入 AudioApi；本会话/旧装配路径不提供音频）。" +
                "不返回静默 false 兜底。",
        )

    /** 菜单/交互项/界面贡献（§6.1 互动贡献 + render；接线属 K3）。 */
    val ui: UiApi
}

/**
 * mod 日志面（clean 语义；不继承引擎错误码怪癖）。
 *
 * 后台线程/崩溃路径的文案取用照壳层既有约束（AGENTS.md §6）：语言只经壳层单路取，
 * 非主线程不直接读 SharedPreferences、不自判 Locale。本接口只负责把 mod 自己的诊断
 * 文案交给引擎归属到 PackageId，不做玩家可见 UI 文案（那走三语文案表）。
 */
interface ModLogger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, cause: Throwable? = null)
}

/**
 * 主执行调度器（§18.5 线程模型）。
 *
 * 首版**所有改游戏状态的 Kotlin mod 操作经主执行调度器**：[submit] 把动作排到 ERB 主执行
 * 线程的转移边界放行。后台线程可计算、读不可变快照，**不得直接改游戏状态**——防重新引入
 * Android 卡死/数据竞争/状态不一致。
 *
 * clean 语义：调度器保证动作在 ERB 不在执行的转移边界运行（§7 Mutation Boundary）；
 * mod 不得创建永不结束的后台线程（§18.4）。
 */
interface RuntimeScheduler {
    /** 当前是否在主执行线程（在该线程上可直接改状态，否则必须 [submit]）。 */
    val isOnMainThread: Boolean

    /** 把改游戏状态的动作排到主执行调度器的转移边界。 */
    fun submit(action: () -> Unit)
}