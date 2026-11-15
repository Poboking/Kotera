package com.eratw.port.modfamily

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modfamily.generated.FamilyAccessors

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
 * 存一个父引用要走 `accessors.setFather(child, father)`，而不是
 * `entities.setInt(child, "father", <EntityId 的原始 64 位>)`。位编码的包装/解包只发生在
 * 生成的访问器内部 ⇒ mod 作者写的是类型，不是 Long。这正是 `:tools:modgen-accessors` 存在的理由，
 * 而 [FamilyAccessors] 出现在这段业务代码里就是它的验收。
 * E 车之后生成访问器自身也走 `EntityApi.getRef/setRef`，整条 mod 侧链 0 个 `.raw`。
 *
 * ## F2：经公开面上/下原版角色槽（E 车）
 *
 * [presentChild] / [withdrawChild] / [charIndexOfPresentedChild] / [rosterGeneration]
 * 全部只经 `context.roster`（[com.eratw.port.modapi.RosterProjectionApi]）表达，
 * 一个 `com.eratw.port.core.*` 都不 import。charIndex 是瞬时行号，**绝不写进 mod 存储**：
 * 本类只把 `EntityId → 世代快照` 留在实例级内存表里，用前经世代校验现换。
 *
 * ## 生命周期纪律（§18.4）
 *
 * [onLoad] 不得改实体结构；[onEnable] 才注册任务；[onDisable] / [onUnload] 交还给宿主撤销注册
 * （本类不自己攒跨回调的可变集合，只有两个计数与一个上下文引用，都是实例级 —— 铁律 4 无静态可变全局）。
 *
 * clean 语义（§18.7）：本 mod 不复刻任何官方 bug；它只经 API 边界参与，不执行 ERB 操作码。
 */
class FamilyMod : EraMod {

    private lateinit var context: ModContext
    private lateinit var accessors: FamilyAccessors

    /** 本会话里已登记的子嗣；成长任务遍历它推进年龄。实例级，不跨会话。 */
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

    override fun onLoad(context: ModContext) {
        this.context = context
        this.accessors = FamilyAccessors(context.entities)
        // onLoad 只拿入口、不碰实体结构（§18.4）：恢复已有子嗣放在 onEnable 之后由业务自己触发，
        // 因为此时宿主还没放行转移边界。
        growthTicks = context.storage.get(FamilyDeclarations.STORAGE_GROWTH_TICKS)?.toLongOrNull() ?: 0L
    }

    override fun onEnable() {
        enabled = true
        context.jobs.bind(FamilyDeclarations.JOB_GROWTH) { advanceOneDay() }
    }

    override fun onDisable() {
        enabled = false
        // 注册项由宿主统一撤销；这里只把自己那份计数落进 mod 存储（禁用后数据必须还在，§18.6）。
        context.storage.put(FamilyDeclarations.STORAGE_GROWTH_TICKS, growthTicks.toString())
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
     * @return 新孩子的稳定身份；调用方只该持久化这个，不该持久化 charIndex（§7 Mod 引用铁律）。
     */
    fun adoptChild(title: String, father: EntityId?): EntityId {
        val child = context.entities.create(FamilyDeclarations.ARCHETYPE_CHILD)
        accessors.setTitle(child, title)
        accessors.setAge(child, 0L)
        if (father != null && !father.isNull) {
            accessors.setFather(child, father)
            // 反向边由声明的 autoInverse 生成（family.lineage），业务不手写第二遍。
            context.relationships.add(father, FamilyDeclarations.RELATION_PARENT, child)
        }
        children += child
        return child
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
     * 死亡：改状态不删实体。§7 的 RETIRED = 身份有效、退出常规轮询、**关系与数据都留着** ⇒ 族谱不断，
     * 引擎零增量（不激活 `cascadeOnDestroy`、不建墓碑机制，见 §22.7 第 20 条）。
     */
    fun markDeceased(child: EntityId) {
        context.entities.setState(child, EntityState.RETIRED)
        children -= child
    }

    /** 一拍成长：全体在册孩子 +1 天，并把计数写进 mod 存储（存读档要对得上）。 */
    fun advanceOneDay() {
        growthTicks += 1L
        for (child in children) {
            if (context.entities.exists(child)) {
                accessors.setAge(child, accessors.age(child) + 1L)
            }
        }
        context.storage.put(FamilyDeclarations.STORAGE_GROWTH_TICKS, growthTicks.toString())
    }
}

/** [FamilyMod] 的可读状态快照（纯值，不含引擎类型）。 */
data class FamilyModState(
    val enabled: Boolean,
    val growthTicks: Long,
    val registeredChildren: Int,
)
