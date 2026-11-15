package com.eratw.port.modmemory

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityRefCompareOp
import com.eratw.port.modapi.EntityRefFieldCmp
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.HasRelation
import com.eratw.port.modapi.IntCompareOp
import com.eratw.port.modapi.IntFieldCmp
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.queryPlan

/**
 * 记忆 Mod 入口 —— **首个记忆型业务 Mod 参考实现**（M9-1，era-arch-v61u.md §21.10 /
 * §21.2 知识/传闻参考模型；受验判据 = CONTRACT-RT2-M91 §1 判据 1–6）。
 *
 * ## 它存在的架构意义
 *
 * §21.2 的记忆模型（知识 ≈ fact 实体 + `owner --KNOWS--> fact` 有向边）此前只写在蓝图里、
 * 零参考实现。本类把模型落成可跑、可测、可照抄的 Mod：**只经 mod-api 公开面表达**
 * （[ModContext.entities] / [ModContext.relationships] / [ModContext.query] /
 * [ModContext.jobs] / [ModContext.storage]），main 源码 0 个 `com.eratw.port.core` import
 * —— 不是靠审查者盯，是靠 `mod-memory/build.gradle.kts` 的依赖方向在编译期拦着
 * （照 `mod-family/build.gradle.kts` 先例）。
 *
 * ## 职责切分（写路径在本类，读路径在 [MemoryQueries]）
 *
 * 本类持**写路径**（record / teach / forget / decay）与生命周期；只读查询与边值解读在
 * [queries]（onLoad 装配的实例级只读 facade）。两者都只 wrap [ModContext]，无静态可变
 * 全局（铁律 4）。
 *
 * ## 生命周期纪律（§18.4，照 FamilyMod 先例）
 *
 * [onLoad] 只拿入口、读存储计数，不改实体结构；[onEnable] 才绑每日衰减任务；
 * [onDisable] 把计数落进 mod 存储（禁用后数据必须还在，判据 6）；[onUnload] 归零实例级
 * 可读状态。全部可变状态都是实例级。
 *
 * ## record 的「create 第一步」多步写论证（照 FamilyMod.kt:94-115 先例）
 *
 * schema freeze 之后，[MemoryAccessors.write] / `addRelation` / `setValue` 的失败模式只剩
 * 「实体不存在 / 端点不存在」，而 fact 实体就在上一行刚建出来、owner 已在方法入口
 * fail-closed 验存（只读检查，发生在第一笔结构变更之前）⇒ **只要 create 成功，后续步骤
 * 结构上不会失败**，这笔多步写天然满足「提交阶段不可能失败」，不需要暂存、补偿或回滚。
 * `create` 自己是唯一会失败的（槽耗尽），放最前面 = 失败时整笔什么都没发生。
 *
 * clean 语义（§18.7）：本 mod 不复刻任何官方 bug；只经 API 边界参与，不执行 ERB 操作码。
 */
class MemoryMod : EraMod {

    private lateinit var context: ModContext
    private lateinit var accessors: MemoryAccessors

    /** 只读查询与边值解读面（onLoad 装配；判据 4 的 §21.5 查询全在这里）。 */
    lateinit var queries: MemoryQueries
        private set

    /** 装载以来推进过的衰减日数（禁用→重启用经 mod 存储对账，判据 6）。 */
    var decayTicks: Long = 0L
        private set

    /** 当前是否处于启用态（诊断与测试读数）。 */
    var isEnabled: Boolean = false
        private set

    override fun onLoad(context: ModContext) {
        this.context = context
        this.accessors = MemoryAccessors(context.entities)
        this.queries = MemoryQueries(context)
        // onLoad 只拿入口、不碰实体结构（§18.4）：衰减日计数从 mod 存储恢复。
        decayTicks = context.storage.get(MemoryDeclarations.STORAGE_DECAY_TICKS)?.toLongOrNull() ?: 0L
    }

    override fun onEnable() {
        isEnabled = true
        context.jobs.bind(MemoryDeclarations.JOB_DECAY) { decayOneDay() }
    }

    override fun onDisable() {
        isEnabled = false
        // 注册项由宿主统一撤销；这里只把自己那份计数落进 mod 存储（禁用后数据必须还在，判据 6）。
        context.storage.put(MemoryDeclarations.STORAGE_DECAY_TICKS, decayTicks.toString())
    }

    override fun onUnload() {
        // 实例级状态只有计数与几个 lateinit 引用；知识本体全在引擎侧（实体/边），无可清理项。
        decayTicks = 0L
        isEnabled = false
    }

    /**
     * 记录一条知识：建 fact 实体（**第一步**）→ 写五类字段 → 挂 `owner --KNOWS--> fact` 边
     * （边值 = [FactValues.provenance].code，owner 自己对这条知识的认知来源）。
     *
     * @param owner 知识持有者；不存在时抛 [IllegalArgumentException]（fail-closed 于
     *   第一笔变更之前，不留下「无主 fact」这种静默孤儿）。
     * @param values 五类字段全量（[FactValues] KDoc 有逐字段语义；provenance 描述
     *   **首录者**的入账来源，此后任何人经 [teach] 学得同一 fact，来源记在各自边上）。
     * @return 新 fact 的稳定身份（调用方只持久化 [EntityId]，不持久化任何瞬时行号）。
     */
    fun record(owner: EntityId, values: FactValues): EntityId {
        require(context.entities.exists(owner)) { "owner 不存在：$owner（不落无主知识）" }
        // create 必须是第一步（本类头 KDoc 的多步写论证）。
        val fact = context.entities.create(MemoryDeclarations.ARCHETYPE_FACT)
        accessors.write(fact, values)
        context.relationships.add(owner, MemoryDeclarations.RELATION_KNOWS, fact)
        context.relationships.setValue(
            owner, MemoryDeclarations.RELATION_KNOWS, fact, values.provenance.code,
        )
        return fact
    }

    /**
     * 让 [learner] 学得一条既有 fact：给 learner 挂自己的 `--KNOWS-->` 边（判据 3 的显式
     * 传播通道——**只有这条通道**，引擎不做任何自动传播）。
     *
     * 边值 = [provenance].code：学习者对这条知识的认知来源缺省 [Provenance.HEARSAY]
     * （听来的），除非有独立亲历依据；fact 字段上的首录来源不被改写。
     * 短路求值保证：fact 不存在时不挂边、挂边失败时不写边值（不隐式建边，§18.3）。
     *
     * @return false = fact 不存在（含已遗忘）或挂边失败；不静默成功。
     */
    fun teach(learner: EntityId, fact: EntityId, provenance: Provenance = Provenance.HEARSAY): Boolean =
        context.entities.exists(fact) &&
            context.relationships.add(learner, MemoryDeclarations.RELATION_KNOWS, fact) &&
            context.relationships.setValue(
                learner, MemoryDeclarations.RELATION_KNOWS, fact, provenance.code,
            )

    /**
     * 遗忘：摘 owner 的 KNOWS 边 + 删 fact 实体（判据 5 的 forget 语义）。
     *
     * 边不存在 = owner 本就不知道这条知识 ⇒ false，不删别人的知识。摘边成功后 destroy；
     * KNOWS 声明的 cascadeOnDestroy 兜底清掉其余悬挂边（[MemoryDeclarations.RELATION_KNOWS]
     * KDoc）——其他知道这条知识的人也一并遗忘，因为知识本体已不存在。
     *
     * @return true = 确实遗忘了一笔；false = owner 本不知道它。
     */
    fun forget(owner: EntityId, fact: EntityId): Boolean {
        if (!context.relationships.has(owner, MemoryDeclarations.RELATION_KNOWS, fact)) return false
        context.relationships.remove(owner, MemoryDeclarations.RELATION_KNOWS, fact)
        return context.entities.destroy(fact)
    }

    /**
     * 一个衰减日（判据 5 的 decay，Mod 规则）：全部 fact 按 [MemoryDeclarations.FIRSTHAND_DAILY_DECAY]
     * / [MemoryDeclarations.HEARSAY_DAILY_DECAY] 降 confidence、下限 0；只动 confidence，
     * 不删实体、不动边（衰减到 0 ≠ 遗忘）。日计数落 mod 存储（判据 6 的对账凭据）。
     *
     * 生产驱动 = [MemoryDeclarations.JOB_DECAY]（[onEnable] 绑定，每游戏日 DAY_END 派发）；
     * 测试与诊断可直调（照 FamilyMod.advanceOneDay 先例）。
     */
    fun decayOneDay() {
        decayTicks += 1L
        for (fact in queries.allFacts()) {
            val rate = when (accessors.provenance(fact)) {
                Provenance.HEARSAY.code -> MemoryDeclarations.HEARSAY_DAILY_DECAY
                else -> MemoryDeclarations.FIRSTHAND_DAILY_DECAY
            }
            accessors.setConfidence(fact, (accessors.confidence(fact) - rate).coerceAtLeast(0L))
        }
        context.storage.put(MemoryDeclarations.STORAGE_DECAY_TICKS, decayTicks.toString())
    }
}

/**
 * 记忆 Mod 的**只读查询面**（判据 3/4 的 §21.5 落点；写路径在 [MemoryMod]）。
 *
 * 全部查询走 `QueryApi.queryPlan` DSL：结果经 [com.eratw.port.modapi.QueryApi] 保证
 * EntityId 升序、独立快照。**知识可达性只由 KNOWS 边决定**（判据 3）：没有边的 fact
 * 不进任何结果。
 */
class MemoryQueries(private val context: ModContext) {

    /**
     * owner 的知识集 = §21.5 查询：`archetype(fact) + HasRelation(KNOWS, 入边, owner)`；
     * [minConfidence] 非空时再 AND 一条 `confidence >= minConfidence`（判据 4 的多谓词
     * 组合形状：HasRelation + IntFieldCmp 经 builder 摊平成 AndExpr）。
     */
    fun knowledgeOf(owner: EntityId, minConfidence: Long? = null): List<EntityId> =
        context.query.query(
            queryPlan {
                archetype(MemoryDeclarations.ARCHETYPE_FACT)
                where(HasRelation(MemoryDeclarations.RELATION_KNOWS, outgoing = false, other = owner))
                if (minConfidence != null) {
                    where(IntFieldCmp(MemoryDeclarations.FIELD_CONFIDENCE, IntCompareOp.GE, minConfidence))
                }
            },
        )

    /** 全知识库里「关于 [subject]」的事实（按 fact 的 subject 引用字段过滤）。 */
    fun factsAbout(subject: EntityId): List<EntityId> =
        context.query.query(
            queryPlan {
                archetype(MemoryDeclarations.ARCHETYPE_FACT)
                where(
                    EntityRefFieldCmp(
                        MemoryDeclarations.FIELD_SUBJECT, EntityRefCompareOp.EQ, subject,
                    ),
                )
            },
        )

    /** 全部 fact 实体（衰减日的遍历源；含暂无边界的实体，衰减是 fact 自身的属性）。 */
    fun allFacts(): List<EntityId> =
        context.query.query(queryPlan { archetype(MemoryDeclarations.ARCHETYPE_FACT) })

    /**
     * [owner] 持有 [fact] 的认知来源（读 KNOWS 边值，见 [MemoryDeclarations.RELATION_KNOWS]
     * KDoc 的边值约定）；无边（不知道）返回 null。fact 字段上的首录来源走
     * [MemoryAccessors.provenance]。
     */
    fun provenanceOf(owner: EntityId, fact: EntityId): Provenance? {
        val code = context.relationships.valueOf(owner, MemoryDeclarations.RELATION_KNOWS, fact)
        return Provenance.entries.firstOrNull { it.code == code }
    }
}
