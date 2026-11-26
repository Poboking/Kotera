package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityApi
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModLogger
import com.eratw.port.modapi.RandomApi
import com.eratw.port.modapi.SettingsApi
import com.eratw.port.modapi.StorageApi
import com.eratw.port.modapi.add
import com.eratw.port.modapi.bind
import com.eratw.port.modapi.get
import com.eratw.port.modapi.put
import com.eratw.port.modapi.query
import com.eratw.port.modfamily.generated.FamilyFacade
import com.eratw.port.modfamily.generated.FamilyKeys
import com.eratw.port.modfamily.generated.FamilyView
import com.eratw.port.modfamily.lineage.SuccessionCandidate
import com.eratw.port.modfamily.lineage.SuccessionRule
import com.eratw.port.modfamily.lineage.succession

/**
 * 千分比掷骰的上界：`rng.nextInt(1000) < mortalityPermille` 即「本拍这名候选死亡」。
 * 用「[0,1000) 与千分比比较」而不是浮点概率，边界不抖动；上界与
 * [FamilyDeclarations.MORTALITY_PERMILLE_KEY] 的声明范围 0..1000 同源。
 */
private const val MORTALITY_ROLL_BOUND = 1000

/**
 * 家族 Mod 入口 —— 本仓**第一个真实的 [EraMod] 实现**（§23.2 F1 的受验体，K5 的起点）。
 *
 * ## 它存在的架构意义
 *
 * 审计的首要事实是「RT2 全链生产 0 可达」，其中一个具体形状是：`EraMod` 实现类在 `src/main` 内
 * **0 个** —— 整台宿主从来没有被一个真实的 mod 驱动过。本类把这个 0 变成 1，
 * 并且当场验证 §23.0 追加裁定 1：**main 里只 import `com.eratw.port.modapi.*` 与本模块自己的包，
 * 一个 `com.eratw.port.core.*` 都不许出现**。引擎内部进不来，不是靠审查者盯，是靠
 * `mod-family/build.gradle.kts` 的依赖方向编译期拦着。
 *
 * ## 业务代码里看不到 `.raw`（P1-6 ② 的验收形状）
 *
 * 存一个父引用要走 `child.father = father`，而不是
 * `entities.setInt(child, "father", <EntityId 的原始 64 位>)`。位编码的包装/解包只发生在
 * 生成的视图内部 ⇒ mod 作者写的是类型，不是 Long。这正是 `:tools:modgen-accessors` 存在的理由，
 * 而 [FamilyView] / [FamilyFacade] 出现在这段业务代码里就是它的验收。
 * 生成视图自身也走 `EntityApi.getRef/setRef`，整条 mod 侧链 0 个 `.raw`。
 *
 * ## F2：经公开面上/下原版角色槽（E 车）
 *
 * [presentChild] / [withdrawChild] / [charIndexOfPresentedChild] / [rosterGeneration]
 * 全部只经 `context.roster`（[com.eratw.port.modapi.RosterProjectionApi]）表达，
 * 一个 `com.eratw.port.core.*` 都不 import。charIndex 是瞬时行号，**绝不写进 mod 存储**：
 * 本类只把 `EntityId → 世代快照` 留在实例级内存表里，用前经世代校验现换。
 *
 * ## 子嗣名单的持久真源（存档 → 读档不丢）
 *
 * 在册名单的**真源是 mod 存储键 [FamilyDeclarations.CHILD_IDS]**（逗号分隔的 [EntityId.raw]，按登记序）；
 * [children] 只是进程内写穿缓存：登记/退场时同步落存储，读档时由 [onSaveLoaded] 重建。
 * 重建时每个 id 都经 [EntityApi.exists] 校验 —— **EntityStore 是身份的权威源**，存储里残留的
 * 幽灵 id（实体已不存在）不进缓存，不会长出不存在的年龄、也不会被选成继任者。
 *
 * 时序上不能只靠 [onLoad]：宿主保证侧车（实体段与 mod 存储段）恢复发生在首轮 onLoad/onEnable
 * **之后**、onSaveLoaded **之前**，故 onLoad 那一刻读到的存储可能是空 —— 真正的还原点是 onSaveLoaded。
 *
 * ## 生命周期纪律（§18.4）
 *
 * [onLoad] 不得改实体结构；[onEnable] 才注册任务；[onDisable] / [onUnload] 的注册撤销交还给宿主
 * （生产宿主在 onDisable 之前经注册收集器批量撤销本包全部注册项；台架 `disable()` 只裸调
 * `onDisable` 不代撤 —— 要在台架断言「禁用后不派发」就自持 [onEnable] 返回的 Registration）。
 * 实例级可变状态只有三处（铁律 4 无静态可变全局）：成长计数、在册子嗣缓存（持久真源见上节）、
 * 登场世代快照；后两者随 [onUnload] 清空。
 *
 * clean 语义（§18.7）：本 mod 不复刻任何官方 bug；它只经 API 边界参与，不执行 ERB 操作码。
 *
 * ## per-mod 设置消费（RT2 配置面）
 *
 * 载体声明的四个设置键都在**读的那一刻现读** `context.settings`（VM 线程内，不在 [onLoad] 缓存）：
 * - [FamilyDeclarations.GROWTH_PER_DAY_KEY]：每拍每名在册子嗣的年龄增量（声明默认 1，范围 1..10），
 *   由 [advanceOneDay] 消费；
 * - [FamilyDeclarations.MORTALITY_PERMILLE_KEY]：每拍每名候选的死亡千分比（声明默认 0 = 关闭，
 *   范围 0..1000），由 [advanceOneDay] 经 [FamilyRosterView.sampleMortality] 消费 —— **0 时整段不执行、
 *   零随机数消耗**（默认配置不改变随机流），命中走 [markDeceased]（ADR-015 D6-C）；
 * - [FamilyDeclarations.SHOW_TITLES_KEY]：`false` 时 [adoptChild] 写**空串**标题（字段照写、不跳过写入，
 *   免得读档两端对同一孩子给出不同形状），由 [adoptChild] 消费；
 * - [FamilyDeclarations.SUCCESSION_MODE_KEY]：继任选择规则，由 [chooseSuccessor] 消费（`eldest` / `youngest`
 *   按年龄排序、`manual` 原样保留调用方给的现任）。真实自动调用点 = 引擎每游戏日跑的 `family.growth`
 *   任务（[onEnable] 绑定）：[advanceOneDay] 末尾的继任清扫把「父引用已 RETIRED」的直系子嗣按本设置
 *   选成新家督，并写回既有 `father` / `title` 字段；`manual` 被该清扫刻意跳过（人工继任）。引擎不会在
 *   [markDeceased] 当拍立刻选举 —— 死亡只记状态，继任在下一日拍结算。
 *
 * 宿主未在 [onLoad] 前注入按包设置快照时，`context.settings` 照 [com.eratw.port.modapi.SettingsApi]
 * 的口径整面抛 `UnsupportedOperationException`；本类**不做默认值兜底**：默认值属于声明/宿主快照，
 * 不属于载体，否则「没装配」会被伪装成「按缺省跑」。
 */
class FamilyMod : EraMod {

    private lateinit var context: ModContext
    private lateinit var family: FamilyFacade

    /**
     * 在册子嗣的进程内缓存（成长任务遍历它推进年龄、继任选择从它取候选）。
     * **持久真源是 [FamilyDeclarations.CHILD_IDS]**：登记/退场时写穿过去，读档时由 [onSaveLoaded] 重建。
     */
    private val children = mutableListOf<EntityId>()

    /**
     * 已登场子嗣的「投射世代快照」，键只存 [EntityId]（§7 Mod 引用铁律）。
     * **绝不存 charIndex**：行排列/删除随时令其失效；值只是自检凭据，不是行号。
     * 实例级、随 onUnload 清空（铁律 4 无静态可变全局）。
     */
    private val presented = mutableMapOf<EntityId, Long>()

    private var growthTicks = 0L
    private var enabled = false

    /** 诊断与测试用：本 mod 看到的生命周期计数（不暴露引擎内部类型）。 */
    val state: FamilyModState
        get() = FamilyModState(enabled, growthTicks, children.size)

    /** 当前已登场的子嗣数（只数在册的投射，不含已退场者）。 */
    val presentedChildren: Int get() = presented.size

    /** 经公开面读当前行排列世代；mod 只把它当缓存有效性凭据，不当业务时间。 */
    val rosterGeneration: Long get() = context.roster.rosterGeneration

    /**
     * 继任选择所需的只读状态快照，**内部接线面**：只给同文件顶层扩展 `chooseSuccessor` 使用。
     * 继任选择不读写本类的可变状态、只由「设置 + 在册子嗣 + 实体面」组合出来，故它是扩展函数而不是
     * 第 11 个成员函数（载体类的成员面已贴近 detekt 默认闸；这也是为了让 FamilyMod 保持
     * 生命周期 + 实体写路径这一件事）。`children` 复制一份，避免选择期间集合被并发改动。
     * 未 [onLoad] 前读它会抛 `UninitializedPropertyAccessException`，与本类其余方法一致。
     */
    internal val successionView: SuccessionView
        get() = SuccessionView(
            context.settings,
            children.toList(),
            context.entities,
            family,
            context.logger,
            context.storage,
        )

    /**
     * 作者面（ADR-015 S3）：手动继任的候选查询 + 显式指定/取消。
     *
     * 无状态的轻量视图（每次取新建，不缓存任何选择），选择本身持久在
     * [FamilyDeclarations.MANUAL_HEIR] 存储键里，随存档走。
     */
    val api: FamilyApi
        get() = FamilyApi(this)

    override fun onLoad(context: ModContext) {
        this.context = context
        this.family = FamilyFacade(context.entities)
        // onLoad 只拿入口、不碰实体结构（§18.4）。计数与名单先按此刻的存储试还原：
        // 新会话/同世界重启时它已在；生产读档缝里侧车恢复晚于本轮 ⇒ 真正的还原点是 onSaveLoaded，
        // 那里会把这两项随存档一并重新采纳（与生产 adopt-from-save 语义对齐）。
        growthTicks = context.storage.get(FamilyDeclarations.GROWTH_TICKS)?.toLongOrNull() ?: 0L
        children.clear()
        children += persistedChildren(context.entities, context.storage)
    }

    /**
     * 读档缝的还原点：此时存档的实体段与 mod 存储段都已就位（宿主保证侧车恢复晚于首轮
     * [onLoad] / [onEnable]、早于本回调），故 [onLoad] 读到的存储可能是空 —— **所有随存档走的
     * 实例状态都必须在这里重建**，不能只靠 [onLoad] 试还原一次。否则 [advanceOneDay] 会在下一拍
     * 把尚未还原的计数从 0 写回，覆盖存档真值（与生产 `adoptSeedFromSave` 的「从存档采纳」相反）。
     *
     * 名册重建逐 id 经 [EntityApi.exists] 校验：EntityStore 里已不存在的幽灵 id 不进缓存；
     * 计数直接由 [FamilyDeclarations.GROWTH_TICKS] 采纳，与名册同一口径、同一还原点。
     */
    override fun onSaveLoaded() {
        children.clear()
        children += persistedChildren(context.entities, context.storage)
        growthTicks = context.storage.get(FamilyDeclarations.GROWTH_TICKS)?.toLongOrNull() ?: 0L
    }

    override fun onEnable() {
        enabled = true
        context.jobs.bind(FamilyDeclarations.GROWTH) { advanceOneDay() }
    }

    override fun onDisable() {
        enabled = false
        // 注册项由宿主统一撤销（生产宿主经注册收集器在本方法之前批量撤销本包全部注册项；
        // 台架 disable() 只裸调 onDisable、不代撤 —— 要在台架断言禁用后不派发就自持 onEnable
        // 的 Registration 返回值）；这里只把自己那份计数落进 mod 存储（禁用后数据必须还在，§18.6）。
        context.storage.put(FamilyDeclarations.GROWTH_TICKS, growthTicks.toString())
    }

    override fun onUnload() {
        children.clear()
        presented.clear()
    }

    /**
     * 登记一个孩子：建实体 → 写身份 → 挂父边。**建实体必须是第一步。**
     *
     * 依据 §23.2 硬约束 3 与 P2-1 的裁定：schema freeze 之后，`setStr` / `setInt` / `addRelation`
     * 的失败模式只剩「实体不存在」，而实体就在上一行刚建出来 ⇒ **只要 create 成功，后续步骤结构上
     * 不会失败**，于是这笔多步写天然满足「提交阶段不可能失败」，不需要暂存、补偿或回滚机制。
     * `create` 自己是唯一会失败的（槽耗尽 / 世代耗尽退休），把它放最前面 = 失败时整笔什么都没发生。
     *
     * 标题写入受 per-mod 设置 [FamilyDeclarations.SHOW_TITLES_KEY] 控制（**每次调用现读**）：`false` 时
     * 写空串而不是跳过写入 —— 字段形状恒定，存档/读档两侧对同一孩子看到的值一致。设置读取放在
     * `create` 之前：设置面未装配时整笔连实体都不建，不留半个结构。
     *
     * @return 新孩子的稳定身份；调用方只该持久化这个，不该持久化 charIndex（§7 Mod 引用铁律）。
     */
    fun adoptChild(title: String, father: EntityId?): EntityId {
        val showTitles = context.settings.bool(FamilyDeclarations.SHOW_TITLES_KEY)
        val child = family.create()
        child.title = if (showTitles) title else ""
        child.age = 0L
        if (father != null && !father.isNull) {
            child.father = father
            // 反向边由声明的 autoInverse 生成（family.lineage），业务不手写第二遍。
            context.relationships.add(father, FamilyDeclarations.PARENT, child.id)
        }
        children += child.id
        persistChildren(context.storage, children)
        return child.id
    }

    /**
     * F2 登场：经 [ModContext.roster] 为已建好的孩子申请一个原版角色行。
     *
     * 调用序列严格按 §7：先读 [rosterGeneration] 快照世代，再 `RosterProjectionApi.project`；
     * 成功时**只把世代连同 [EntityId] 留在内存**，返回给调用方的 charIndex 是瞬时行号，
     * 落存储、写存档、当业务主键一律禁止。原版对应 `ADDVOIDCHARA` 的伪角色尾部追加。
     *
     * TODO(verify)：本方法是移植侧 RT2 自建业务调用点，原版 ERB 无同名函数可比；
     * 卡点 = 无官方 SDK 可比，只能以 §23.2 F2 调用场景与真引擎纵向切片作依据。
     *
     * @return 瞬时 charIndex；实体不存在 / 包短 ID 未注册时 -1。
     */
    fun presentChild(child: EntityId): Int {
        val generation = context.roster.rosterGeneration
        val row = context.roster.project(child)
        if (row >= 0) presented[child] = generation else presented.remove(child)
        return row
    }

    /**
     * F2 用前换 CHARA：拿 [presentChild] 时快照的世代经
     * `RosterProjectionApi.charIndexOfChecked` 现换当前行号。
     * 世代过期（行排列/删除过）或未登场一律返回 -1，调用方须重新投射，不得硬用旧行号。
     */
    fun charIndexOfPresentedChild(child: EntityId): Int {
        val generation = presented[child] ?: return -1
        return context.roster.charIndexOfChecked(child, generation)
    }

    /**
     * F2 退场：经 `RosterProjectionApi.unproject` 脱水并撤回角色行。
     * 成功即丢弃世代快照；删行会令此前的世代全部过期，这正是上面那条校验要挡的形态。
     */
    fun withdrawChild(child: EntityId): Boolean {
        val withdrawn = context.roster.unproject(child)
        if (withdrawn) presented.remove(child)
        return withdrawn
    }

    /**
     * 日拍写面视图：名册 + 实体段 + 存储写穿 + 父引用读取（时间驱动抽样要读 `father`）+ 日志。
     * 每次现取一个新的轻量视图，四个依赖都是同一个世界/同一份 [children] 缓存实例。
     */
    internal val rosterView: FamilyRosterView
        get() = FamilyRosterView(context.entities, context.storage, children, context.logger, family)

    /**
     * 一拍成长：先对齐「实体段已 RETIRED、名册还留着」的子嗣（ADR-015 D2/D7 的 reconcile 步），再按
     * [FamilyDeclarations.MORTALITY_PERMILLE_KEY] 做时间驱动死亡抽样（D6-C），最后给全体在册孩子
     * +[FamilyDeclarations.GROWTH_PER_DAY_KEY] 天，并把计数写进 mod 存储（存读档要对得上）。
     * 增量**每拍现读**设置（默认 1）：中途改设置下一拍立即生效，不做 onLoad 缓存。
     * 成长增量的读取放在改动账本/实体之前：设置面整面未装配时首读即抛，整拍不改任何状态；
     * 死亡千分比按接线口径在 reconcile 之后读（同一设置面，此刻已确认可用）。
     *
     * reconcile 必须排在成长**之前**：否则死者会在被摘除前多涨一天（「已死却在涨年龄」的中间态）。
     * 它只在确有摘除时写穿名册并记一条 INFO，普通成长拍零额外写盘（见 [FamilyRosterView.reconcileRetiredChildren]）。
     *
     * 时间驱动抽样（S6）固定在 reconcile **之后**、成长**之前**（ADR-015 D7 的日拍相位序）：
     * - reconcile 先跑 ⇒ 本拍刚被 ERB 标死的名册成员先摘掉，不会进抽样集合；
     * - 抽样先于成长 ⇒ 本拍被抽中的死者不再涨年龄（与 reconcile 摘除同一条理由）。
     * 抽中后**只经** [FamilyMod.markDeceased] 落地（A1 触发面唯一写路径，不在这里另写标死）：
     * 标 state、摘名册、写穿。`mortalityPermille <= 0`（缺省 0 = 关闭）时 [FamilyRosterView.sampleMortality]
     * 在收集候选前就返回 ⇒ **整拍零随机数消耗、零日志**（默认配置改变随机流会破坏所有 rng mod 的确定性，
     * 这是本片最重要的隐藏契约）。
     *
     * 末尾的继任清扫（`runSuccessionSweep`）也由这一拍驱动 —— 它是本载体被引擎每日自动调用的唯一相位，
     * 死亡（[markDeceased]，A1/A2/C 三级同此）只记状态，家督继承在死亡后的下一拍结算（见
     * [chooseSuccessor] 的接线说明）。
     */
    fun advanceOneDay() {
        val growthPerDay = context.settings.int(FamilyDeclarations.GROWTH_PER_DAY_KEY)
        val roster = rosterView
        roster.reconcileRetiredChildren()
        val mortalityPermille = context.settings.int(FamilyDeclarations.MORTALITY_PERMILLE_KEY).toInt()
        val doomed = roster.sampleMortality(mortalityPermille, context.rng)
        val deceased = doomed.count { markDeceased(it) }
        if (deceased > 0) {
            context.logger.info("mod.family mortality 时间驱动标死 n=$deceased")
        }
        growthTicks += 1L
        for (child in children) {
            if (context.entities.exists(child)) {
                val view = family.of(child)
                view.age = view.age + growthPerDay
            }
        }
        context.storage.put(FamilyDeclarations.GROWTH_TICKS, growthTicks.toString())
        runSuccessionSweep(this)
    }
}

/**
 * 死亡：改状态不删实体。§7 的 RETIRED = 身份有效、退出常规轮询、**关系与数据都留着** ⇒ 族谱不断，
 * 引擎零增量（不激活 `cascadeOnDestroy`、不建墓碑机制，见 §22.7 第 20 条）。
 * 名单的移出同步写穿到 [FamilyDeclarations.CHILD_IDS]，否则读档后死者会随存档退回在册。
 *
 * ## ADR-015 语义（D1–D7；三级触发共用本写路径）
 *
 * 本函数是 A1（载体直接标死）的入口，也是 A2（ERB `ENTITY_SET_STATE(id, 3)`）与 C（日拍
 * [FamilyMod.advanceOneDay] 的 `mortalityPermille` 抽样）**落地时的唯一写路径** —— 后两者只负责触发，
 * 标 state / 摘名册 / 写穿都回到这里，不允许另写一条标死链。
 *
 * - **D1**：死亡 ≡ 实体 `state = RETIRED`，不新增状态位、不建墓碑、不加存档字段。
 * - **D4/D5**：不删实体、不 unproject 角色行、不碰 `rosterGeneration`；PARENT/LINEAGE 关系边不断
 *   （断边只属于 `ENTITY_DESTROY` 的级联，**禁止**拿 `destroy` 当死亡用）。
 * - **D2**：实体段 state 与 mod 存储的名册是两份独立权威，这里显式对齐两者：先标 state，
 *   名册里若有就摘掉并写穿。
 * - **D7**：只记状态；家督继承在死亡后的下一日拍（[advanceOneDay] 末尾的清扫）结算，不在本函数里做。
 * - 死亡是**幂等**的：实体已不存在或已是 RETIRED 时直接返回 `false`，不写 state、不写盘（零副作用），
 *   重复调用不会把已摘掉的名册再写一遍。
 * - 不在名册但实体存在且 ACTIVE（例如直接 `entities.create` 出来的父辈）：照常标 RETIRED 并返回
 *   `true`，名册无变化就不写盘 —— 这是既有行为（`FamilyRosterPersistenceTest` 的
 *   `retiredFatherKeepsChildEnumerableAndGrowing` 依赖它）。
 *
 * @return `true` = 本次真的发生了 ACTIVE/INACTIVE → RETIRED 的转移；`false` = 目标不存在或已 RETIRED。
 */
fun FamilyMod.markDeceased(child: EntityId): Boolean {
    val view = rosterView
    if (!view.entities.exists(child) || view.entities.stateOf(child) == EntityState.RETIRED) return false
    view.entities.setState(child, EntityState.RETIRED)
    val removed = view.children.remove(child)
    if (removed) persistChildren(view.storage, view.children)
    return true
}

/**
 * 继任选择：从 [father] 的直系在册子嗣（或 [father] 为 null 时的全体在册子嗣）里，按 per-mod 设置
 * [FamilyDeclarations.SUCCESSION_MODE_KEY] 选出一名继任者。既有**真实自动调用点**（引擎每日跑的
 * `family.growth` 任务末尾的继任清扫，见 [advanceOneDay]），也供作者/上层在别的继任业务里显式调用；
 * 本函数自身只选人、不写状态 —— 需要落地时由调用方写回 `father` / `title`（自动路的写回口径见清扫处
 * KDoc）。
 *
 * 规则（每次调用现读设置，不在 `onLoad` 缓存）：
 * - [FamilyDeclarations.SUCCESSION_ELDEST]：年龄最大者（缺省）；
 * - [FamilyDeclarations.SUCCESSION_YOUNGEST]：年龄最小者；
 * - [FamilyDeclarations.SUCCESSION_MANUAL]：不自动选，原样返回 [incumbent]（人工继任 = 不改现状）；
 * - 其它值（坏配置）：回落声明默认 `eldest`，不抛 —— **生产面** [SettingsApi] 已把坏持久化值
 *   解析成声明默认，这里是第二道防线（测试替身等不校验候选的装配面也能安全跑）。
 *
 * 候选池排除两类在册项：实体已不存在、实体状态已是 RETIRED（已退场者不继承家督位）。
 *
 * 排序语义不重造：直接复用 lineage 包的纯函数 [succession]（长幼维度的消歧、tie-break 与
 * `SuccessionTest` 钉的既有口径完全一致）。本载体的实体没有「世代」字段可读（生成视图只有
 * age/title/father），单家族候选同世代 ⇒ 世代差恒 0，长幼用年龄映射成 birthOrder。
 *
 * 读取设置在选人之前：设置面未注入时整次调用抛
 * [UnsupportedOperationException]（真宿主）/ [IllegalArgumentException]（测试替身），不留半个结果。
 *
 * @param father 只在该父代的直系子嗣里选；null 或 EntityId.NULL 表示全体在册子嗣。
 * @param incumbent 现任继任者；只在 `manual` 模式下作为返回值（其余模式忽略）。
 * @return 选中的继任者；没有候选（或无现任的 `manual`）时 null。
 */
fun FamilyMod.chooseSuccessor(father: EntityId? = null, incumbent: EntityId? = null): EntityId? {
    val view = successionView
    val mode = view.settings.enumName(FamilyDeclarations.SUCCESSION_MODE_KEY)
    return if (mode == FamilyDeclarations.SUCCESSION_MANUAL) {
        incumbent
    } else {
        val candidates = lineageCandidates(view.children, father, view.entities, view.family)
        if (candidates.isEmpty()) null
        else EntityId(succession(candidates, successionRuleFor(mode)).first().id.toLong())
    }
}

/**
 * [chooseSuccessor] 与继任清扫的只读依赖快照，由 [FamilyMod.successionView] 产出，只在 mod-family 内部接线。
 *
 * [logger] 走既有的 `context.logger` 面（不是自建日志通道）：继任结算只记一条 INFO，测试用台架的
 * `logEntries` 断言，生产面进宿主统一日志。
 */
internal data class SuccessionView(
    val settings: SettingsApi,
    val children: List<EntityId>,
    val entities: EntityApi,
    val family: FamilyFacade,
    val logger: ModLogger,
    val storage: StorageApi,
)

/**
 * [FamilyMod.markDeceased] 与日拍两步写面（[reconcileRetiredChildren] / [sampleMortality]）的接线视图：
 * 实体面 + mod 存储 + 在册缓存（同一个 MutableList 实例）+ 宿主统一日志 + 父引用读取面。
 * 只在 mod-family 内部使用，让「死亡」这条写路径以扩展函数/文件级函数形式落在文件级
 * （成员面受 detekt 闸约束）。
 */
internal class FamilyRosterView(
    val entities: EntityApi,
    val storage: StorageApi,
    val children: MutableList<EntityId>,
    val logger: ModLogger,
    val family: FamilyFacade,
) {
    /**
     * 日拍第一步：把「实体段已是 RETIRED、名册里还留着」的子嗣摘掉，并把结果写穿一次
     * [FamilyDeclarations.CHILD_IDS]（ADR-015 D2/D7 的跨权威对齐步）。
     *
     * ## 为什么必须有这一步
     *
     * 死亡的第二条触发是 ERB 直调 `ENTITY_SET_STATE(id, 3)`（`core-vm` 的 `ENTITY_SET_STATE` 只改实体段
     * state，**不碰**本 mod 存储的名册）；实体段 state 与名册是两份独立权威（D2）。没有这一步，已标死的
     * 子嗣会随名册继续长年龄，读档后还会随 [FamilyDeclarations.CHILD_IDS] 退回在册。
     *
     * ## 口径
     *
     * - 判据 = 在册 + 实体真存在 + `stateOf == RETIRED`。实体已不存在（被销毁的悬空 id）**不动**：那不是
     *   死亡而是幽灵条目，交给读档路径的 `exists` 过滤（D3：旧档无 RETIRED 记录即活着）。
     * - 不删实体、不断 PARENT/LINEAGE 边、不碰 `rosterGeneration`（D4/D5）；只改名册这一份权威。
     * - **幂等**：本拍无摘除 ⇒ 不写盘、不记日志（普通成长拍零额外开销）；有摘除才写穿一次并记一条 INFO。
     * - 遍历对象是 [children]（名册真源 [FamilyDeclarations.CHILD_IDS] 的进程内写穿缓存），
     *   复杂度 O(在册子嗣数)，每项一次 `exists` + 一次 `stateOf`。
     *
     * 放在 [FamilyRosterView] 的成员位上而不是文件级：文件级函数数已顶着 detekt 的
     * `TooManyFunctions` 闸（FamilyMod.kt 上限 10），而这里要用的四个依赖本就是这个视图的字段。
     *
     * @return 本拍摘除的条数（0 = 名册与实体段本就一致）。
     */
    internal fun reconcileRetiredChildren(): Int {
        val retired = children.filter { entities.exists(it) && entities.stateOf(it) == EntityState.RETIRED }
        if (retired.isEmpty()) return 0
        children.removeAll(retired.toSet())
        persistChildren(storage, children)
        logger.info("mod.family reconcile 摘除已标死子嗣 n=${retired.size}")
        return retired.size
    }

    /**
     * 日拍第二步：时间驱动死亡抽样（ADR-015 D6 的 C 级触发，设置项
     * [FamilyDeclarations.MORTALITY_PERMILLE_KEY]）。本方法**只掷骰、不落地** —— 抽中的候选由调用方
     * [FamilyMod.advanceOneDay] 逐个交给 [FamilyMod.markDeceased]（A1 写路径唯一），这里不重复标死。
     *
     * ## 零消耗契约（本片最重要的隐藏契约）
     *
     * `permille <= 0`（声明缺省 0 = 关闭）时**在收集候选之前**直接返回空表：不读实体、不抽数、
     * 不记日志。默认配置下日拍的随机流位置必须与接线前逐位一致 —— `context.rng` 是各 mod 独立的派生流
     * （见 [RandomApi]），但改默认配置的消耗序同样会破坏 rng mod 的确定性复现，故这里不允许「抽了不用」。
     *
     * ## 候选集合（不扫全表、不加存储字段）
     *
     * 从在册名册（[children]，真源 [FamilyDeclarations.CHILD_IDS]）推导，来源只有两处：
     * 1. 每名在册子嗣的 `father` 引用 —— 父实体存在且**当前 ACTIVE** 才是候选（已 RETIRED 的不抽，
     *    下一拍由 [reconcileRetiredChildren] 收口）；
     * 2. `father` 为空（[EntityId.NULL]）的在册成员本身 —— 名册里的当主/父辈，视为「名册中的父辈」。
     *    有父引用者只是子嗣，不是本拍候选（子嗣的死亡不产生继任义务）。
     *
     * 两处来源合并后经 [LinkedHashSet] 去重（同一父辈可能被多名子嗣引用，当主也可能同时是别人的父辈），
     * 再 `sortedBy { it.raw }` 固定抽样顺序 ⇒ 同种子同结果、日志与写盘顺序稳定（测试可逐位断言）。
     * 复杂度 O(在册子嗣数)，每项一次 `exists` / `stateOf` / `father` 读取。
     *
     * ## 每候选恰好一次
     *
     * 每个候选都抽且只抽一次 `rng.nextInt(1000) < permille`（[MORTALITY_ROLL_BOUND]），
     * 与候选是否被抽中无关；候选表是抽样前拍下的快照，同拍后续候选的掷骰不受前面命中影响。
     * 返回「本拍应死」的 id（按抽样顺序）；实际是否真的发生转移由调用方的 [FamilyMod.markDeceased] 裁决
     * （幂等：目标已不存在/已 RETIRED 时返回 false，不会被计进日志条数）。
     *
     * @param permille 千分比 0..1000（生产快照已按声明范围夹取/回落，这里只做 `<= 0` 的关闭判定）。
     * @return 抽中的候选（按 raw 升序）；`permille <= 0` 或空候选集时为空表。
     */
    internal fun sampleMortality(permille: Int, rng: RandomApi): List<EntityId> {
        if (permille <= 0) return emptyList()
        val doomed = mutableListOf<EntityId>()
        for (candidate in mortalityCandidates()) {
            if (rng.nextInt(MORTALITY_ROLL_BOUND) < permille) doomed += candidate
        }
        return doomed
    }

    /**
     * [sampleMortality] 的候选推导：在册子嗣的父辈 ∪ `father` 为空的在册成员，按 [EntityId.raw] 升序去重。
     *
     * 只碰本 mod 自己的名册与这些实体的一次 `father` 读取，不扫 `CHARA_NUM` 全表、不遍历实体段
     * （父辈枚举面另有 [deceasedHeads] 的继任用途，那是死后发现，与这里的「活人抽样」不是一件事）。
     * 已 RETIRED / 已销毁的条目在收集阶段就过滤 ⇒ 不会被抽，也不占用随机流。
     */
    private fun mortalityCandidates(): List<EntityId> {
        val candidates = LinkedHashSet<EntityId>()
        for (child in children) {
            if (!entities.exists(child) || entities.stateOf(child) == EntityState.RETIRED) continue
            val father = family.of(child).father
            if (father.isNull) {
                candidates += child
            } else if (entities.exists(father) && entities.stateOf(father) != EntityState.RETIRED) {
                candidates += father
            }
        }
        return candidates.sortedBy { it.raw }
    }
}

/**
 * 在册子嗣 → lineage 候选：[father] 非空时只留其直系子嗣，悬空实体与**已退场（RETIRED）实体**跳过。
 *
 * 退场过滤的必要性：死亡走 [FamilyMod.markDeceased] 时会把死者从在册表摘掉，但同拍/外部装配面
 * 仍可能给出「在册但已 RETIRED」的候选（台架直接 `setState`、存档恢复顺序等）；已退场者不得继承
 * 家督位，否则会选出一个再也发动不了的角色。
 *
 * `id` 用 [EntityId.raw] 的十进制原文（[SuccessionCandidate.id] 只做稳定身份与 tie-break，
 * 不参与业务语义）；年龄越大 birthOrder 越小（lineage 口径：越小越年长）。年龄映射用
 * `Long.MAX_VALUE - age` 而不是取负，避免 age 取到极值时溢出。
 */
internal fun lineageCandidates(
    children: List<EntityId>,
    father: EntityId?,
    entities: EntityApi,
    family: FamilyFacade,
): List<SuccessionCandidate> {
    val fatherFilter = father?.takeUnless { it.isNull }
    return children.mapNotNull { child ->
        if (!entities.exists(child)) return@mapNotNull null
        if (entities.stateOf(child) == EntityState.RETIRED) return@mapNotNull null
        val view = family.of(child)
        if (fatherFilter != null && view.father != fatherFilter) return@mapNotNull null
        SuccessionCandidate(
            id = child.raw.toString(),
            generationDifference = 0,
            birthOrder = Long.MAX_VALUE - view.age.coerceAtLeast(0L),
        )
    }
}

/** 设置原文 → lineage 规则；未知值回落声明默认 `eldest`，不抛（坏配置不该让继任直接崩掉）。 */
private fun successionRuleFor(mode: String): SuccessionRule =
    if (mode == FamilyDeclarations.SUCCESSION_YOUNGEST) SuccessionRule.YOUNGEST_FIRST else SuccessionRule.ELDEST_FIRST

/**
 * 成长日拍末尾的继任清扫：把「直系子嗣的父引用已是 RETIRED」的家督位传给下一任。
 *
 * 真实调用点 = 引擎每游戏日跑的 `family.growth` 任务（[FamilyDeclarations.GROWTH] 声明
 * `everyDays = 1L`，[FamilyMod.onEnable] 绑定到 [FamilyMod.advanceOneDay]）—— 这是本载体唯一被
 * 引擎自动推进的相位，继任结算落在这里，而不是 [FamilyMod.markDeceased] 当拍：死亡只记状态，与
 * 「日拍结算」分层（引擎没有实体状态变更事件，见 mod-api EventApi/HookApi 面）。
 *
 * 写回只用既有字段：
 * - 新家督 `title` 继承死者的 `title`（[FamilyDeclarations.SHOW_TITLES_KEY] 为 true 且死者标题非空；
 *   关掉称号时整条标题面不写，与 [FamilyMod.adoptChild] 的现读口径一致）；
 * - 其余直系子嗣的 `father` 改挂新家督 —— 结算后不再有在册子嗣按 `father` 指死者 = 原父引用清理；
 * - 新家督自己的 `father` 清成 [EntityId.NULL]（他已不在任何人的子嗣位上）。
 *
 * **刻意不动 PARENT/LINEAGE 关系边**：§7 裁定 RETIRED 保留关系与数据（族谱不断），关系边记血缘
 * 历史，`father` 字段记「现任家督引用」；继任是照护关系转移，只改后者。
 *
 * `manual` 模式整拍不自动改（人工继任）；无候选时零写入（不发明人选、不崩、不记噪音日志）。
 *
 * 死者发现面见 [deceasedHeads]：除「在册子嗣的 father 引用」外还枚举实体段里 RETIRED 的家族实体，
 * 所以**子嗣已全部退场/从未有在册子嗣**的死者也会被结算义务覆盖。
 */
private fun runSuccessionSweep(mod: FamilyMod) {
    val view = mod.successionView
    // 先判「本拍有没有可结算的死者」：没有就零设置读取（普通成长拍不需要继任设置，也不该因此报错）。
    // [deceasedHeads] 会枚举出已结算完的历史死者（D1 的 RETIRED 永久有效），它们没有在册直系候选 ⇒
    // 不读设置、不做无写入的选举 —— 普通与已结算完的成长拍都保持零设置读取。
    val heads = deceasedHeads(view)
    val settleable = heads.filter { head ->
        lineageCandidates(view.children, head, view.entities, view.family).isNotEmpty()
    }
    if (settleable.isEmpty()) return
    val mode = view.settings.enumName(FamilyDeclarations.SUCCESSION_MODE_KEY)
    if (mode == FamilyDeclarations.SUCCESSION_MANUAL) {
        succeedPendingManualHeir(mod, view, settleable)
        return
    }
    val showTitles = view.settings.bool(FamilyDeclarations.SHOW_TITLES_KEY)
    settleable.forEach { head -> view.settleSuccession(mod, mode, showTitles, head) }
}

/**
 * 结算一支家族：选人 → 继承称号 → 清新家督原父引用 → 其余直系改挂 → 记一条 INFO。
 *
 * 挂在 [SuccessionView] 上当扩展而不是再加一个形参（[FamilyMod] 文件级函数已顶 detekt 闸，
 * 而 `view` 本就是结算的唯一数据面）：`mod` / `mode` / `showTitles` / `head` / `chosen` 五个形参。
 *
 * [chosen] 非空 = 手动继任（调用方已按该父的直系候选校验过），跳过 `chooseSuccessor` 的
 * 按模式选人；auto 路（eldest/youngest）传缺省 null，行为与接线前逐位一致。
 */
internal fun SuccessionView.settleSuccession(
    mod: FamilyMod,
    mode: String,
    showTitles: Boolean,
    head: EntityId,
    chosen: EntityId? = null,
) {
    val successor = chosen ?: mod.chooseSuccessor(father = head) ?: return
    val inheritedTitle = family.of(head).title
    if (showTitles && inheritedTitle.isNotBlank()) {
        family.of(successor).title = inheritedTitle
    }
    family.of(successor).father = EntityId.NULL
    reparentActiveChildren(this, head, successor)
    logger.info("family: 家督继任 mode=$mode 前任=${head.raw} 继任=${successor.raw}")
}

/** 其余在册直系子嗣改挂新家督；已销毁/已退场的条目不写（不动死者的字段）。 */
private fun reparentActiveChildren(view: SuccessionView, head: EntityId, successor: EntityId) {
    view.children
        .filter { it != successor }
        .filter { view.entities.exists(it) }
        .filter { view.entities.stateOf(it) != EntityState.RETIRED }
        .filter { view.family.of(it).father == head }
        .forEach { view.family.of(it).father = successor }
}

/**
 * 本拍要结算继任的死者：合并两处**真实可枚举**来源，按 raw 升序去重，保证多支家族同拍结算时
 * 日志/写回顺序稳定（测试可断言）。
 *
 * 1. **在册子嗣的 `father` 引用**：引用的父实体存在且状态为 RETIRED；
 * 2. **实体段按本 mod 原型枚举**：`entities.query(FamilyKeys.archetype, RETIRED)` —— 覆盖
 *    「子嗣已全部被摘除 / 从未有在册子嗣」的死者。只看子嗣反查的旧口径永远发现不了这类死者
 *    （子嗣被 [reconcileRetiredChildren] 摘掉后父引用面就空了），而 D1 的死亡状态是永久的、
 *    结算义务不该因子嗣退场而消失。
 *
 * 不扫全表 `CHARA_NUM`、不新增存储字段：`query` 是 [EntityApi] 的既有查询面（与引擎 `ENTITY_QUERY`
 * 同口径），原型过滤保证只碰本 mod 自己的实体。复杂度 O(在册子嗣数 + 本 mod 原型实体数)，
 * 每项一次 `exists`/`stateOf`；`father` 已销毁（`exists == false`）不算 —— 那不是死亡，是悬空引用，
 * 本拍不据此选人。
 */
internal fun deceasedHeads(view: SuccessionView): List<EntityId> {
    val heads = LinkedHashSet<EntityId>()
    for (child in view.children) {
        val father = view.family.of(child).father
        if (father.isNull || !view.entities.exists(father)) continue
        if (view.entities.stateOf(father) == EntityState.RETIRED) heads += father
    }
    heads += view.entities.query(FamilyKeys.archetype, EntityState.RETIRED)
    return heads.sortedBy { it.raw }
}

/**
 * 从 mod 存储重建在册子嗣：[FamilyDeclarations.CHILD_IDS] 的十进制原始位域按出现序去重后，
 * 逐个经 [EntityApi.exists] 交 EntityStore 校验 —— 身份的真源在 EntityStore，存储里残留的幽灵 id
 * （实体已不存在）不进名单。坏的/无法解析的片段直接跳过：读档不该被一条脏数据整面弄崩。
 * 仅供 [FamilyMod.onLoad] 与 [FamilyMod.onSaveLoaded] 共用（成员面已贴近 detekt 闸，故放文件级）。
 */
private fun persistedChildren(entities: EntityApi, storage: StorageApi): List<EntityId> {
    val payload = storage.get(FamilyDeclarations.CHILD_IDS) ?: return emptyList()
    val raws = LinkedHashSet<Long>()
    for (part in payload.split(',')) {
        val raw = part.trim().toLongOrNull() ?: continue
        raws += raw
    }
    return raws.map(::EntityId).filter { entities.exists(it) }
}

/** 把当前在册子嗣写回 [FamilyDeclarations.CHILD_IDS]（逗号分隔原始位域；空名单写空串，键保持存在）。 */
private fun persistChildren(storage: StorageApi, children: List<EntityId>) {
    storage.put(FamilyDeclarations.CHILD_IDS, children.joinToString(",") { it.raw.toString() })
}

/** [FamilyMod] 的可读状态快照（纯值，不含引擎类型）。 */
data class FamilyModState(
    val enabled: Boolean,
    val growthTicks: Long,
    val registeredChildren: Int,
)
