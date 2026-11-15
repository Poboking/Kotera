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

    /** 本 mod 的日志面（按 PackageId 归属，不污染引擎日志）。 */
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