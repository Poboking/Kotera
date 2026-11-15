package com.eratw.port.modapi

/**
 * 整型组件字段比较符（M7-1 通用查询，era-arch-v61u.md §21.5）。
 *
 * 六个原子比较逐一映射内核 `getInt(id, field)` 的结果；边界相等由执行器按 Long 数值比较，
 * 不存在隐式类型转换。枚举顺序即契约顺序，不随实现漂移。
 */
enum class IntCompareOp {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
}

/** 字符串组件字段比较符：只承诺相等/不等（排序语义不进查询契约）。 */
enum class StrCompareOp {
    EQ,
    NE,
}

/** EntityRef 字段比较符：只承诺相等/不等（引用按 EntityId 原始值比较）。 */
enum class EntityRefCompareOp {
    EQ,
    NE,
}

/**
 * 查询谓词表达式（M7-1，§21.5）。
 *
 * 组合仅 AND / OR（括号由构造嵌套表达）；**无 join、无 SQL、无任意路径、无隐式索引承诺**。
 * 全部实现类在构造期 fail-closed 校验（空字段名、空关系名、空 terms、同型直接嵌套），
 * 使非法谓词在构建期暴露而不是执行期静默返回空集。
 */
sealed interface QueryExpr

/**
 * 合取：全部 [terms] 为真才为真。
 *
 * [terms] 非空；**禁止直接嵌套同型**（`AndExpr` 的 term 不得是 `AndExpr`）——直接构造嵌套
 * 与 builder 摊平后的形状会产生两种等价但不同的树，语义上虽等价、诊断与序列化上却有歧义，
 * 故 fail-closed。builder 负责把嵌套 AND 摊平成单层。
 */
data class AndExpr(val terms: List<QueryExpr>) : QueryExpr {
    init {
        require(terms.isNotEmpty()) { "AndExpr 的 terms 不能为空" }
        for (t in terms) {
            require(t !is AndExpr) { "AndExpr 不能直接嵌套 AndExpr（用 builder 摊平）" }
        }
    }
}

/**
 * 析取：任一 [terms] 为真即为真。
 *
 * [terms] 非空；**禁止直接嵌套同型**（`OrExpr` 的 term 不得是 `OrExpr`），理由同 [AndExpr]。
 */
data class OrExpr(val terms: List<QueryExpr>) : QueryExpr {
    init {
        require(terms.isNotEmpty()) { "OrExpr 的 terms 不能为空" }
        for (t in terms) {
            require(t !is OrExpr) { "OrExpr 不能直接嵌套 OrExpr（用 builder 摊平）" }
        }
    }
}

/** 整型组件字段比较：`kernel.getInt(id, field) op value`。 */
data class IntFieldCmp(
    val field: String,
    val op: IntCompareOp,
    val value: Long,
) : QueryExpr {
    init {
        require(field.isNotBlank()) { "IntFieldCmp 的字段名不能为空" }
    }
}

/**
 * 字符串组件字段比较：`kernel.getStr(id, field) op value`。
 *
 * 比较按 `String.compareTo` 序；[value] 允许空串（与内核「未写读默认空串」口径一致）。
 */
data class StrFieldCmp(
    val field: String,
    val op: StrCompareOp,
    val value: String,
) : QueryExpr {
    init {
        require(field.isNotBlank()) { "StrFieldCmp 的字段名不能为空" }
    }
}

/**
 * EntityRef 字段比较：把 `kernel.getInt(id, field)` 当引用值与 [other] 比较。
 *
 * [aliveOnly] 为真时，非 0 且 `kernel.exists` 判否的悬空引用一律当 0（NULL）参与比较；
 * 为假时按存储原值比较（§21.5「EntityRef 字段 == != 可带 aliveOnly」）。
 * [other] 允许 [EntityId.NULL]（= 0，用于「引用为空」的查询）。
 */
data class EntityRefFieldCmp(
    val field: String,
    val op: EntityRefCompareOp,
    val other: EntityId,
    val aliveOnly: Boolean = false,
) : QueryExpr {
    init {
        require(field.isNotBlank()) { "EntityRefFieldCmp 的字段名不能为空" }
    }
}

/**
 * 关系存在性谓词（§21.5 `has(kind, dir, other?)`）。
 *
 * [other] 非空 ⇒ 指定对端存在性（出边查 `id --kind--> other`，入边查 `other --kind--> id`）；
 * [other] 为空 ⇒ 任一邻居即真（`kernel.queryRelations(id, kind, outgoing).isNotEmpty()`）。
 */
data class HasRelation(
    val kind: String,
    val outgoing: Boolean,
    val other: EntityId? = null,
) : QueryExpr {
    init {
        require(kind.isNotBlank()) { "HasRelation 的关系类型名不能为空" }
    }
}

/**
 * 查询计划（M7-1 builder 产物，§21.5）。
 *
 * [archetypes] 是候选集**必填**来源（非空、每项非空）；[states] 为空表示不限状态；
 * [where] 为空表示只按 archetype/state 过滤。本类型只做形状校验，不做 schema 校验——
 * 未注册字段按内核口径读 0 / 空串（声明先行由内核 `require` 承担，§21.5 诚实边界）。
 */
data class QueryPlan(
    val archetypes: Set<String>,
    val states: Set<EntityState> = emptySet(),
    val where: QueryExpr? = null,
) {
    init {
        require(archetypes.isNotEmpty()) { "QueryPlan 的 archetypes 不能为空" }
        for (a in archetypes) {
            require(a.isNotBlank()) { "QueryPlan 的 archetype 名不能为空" }
        }
    }
}

/**
 * [QueryPlan] 的构建器（§21.5「括号由 builder 表达」）。
 *
 * [where] 可重复调用：全部谓词按 **AND** 合取；[AndExpr] 参数会被**摊平**（其 terms 逐个收下），
 * 从而避免 `And(And(...))` 这种歧义形状（直接构造仍被 [AndExpr] 构造期拒绝）。
 * 只用单例集合语义去重 archetype/state，不改变判定。
 */
class QueryPlanBuilder {

    private val archetypes: MutableSet<String> = LinkedHashSet()
    private val states: MutableSet<EntityState> = LinkedHashSet()
    private val terms: MutableList<QueryExpr> = ArrayList()

    /** 追加候选原型（重复声明去重，保持插入序；空名当场拒绝）。 */
    fun archetype(name: String) {
        require(name.isNotBlank()) { "archetype 名不能为空" }
        archetypes += name
    }

    /** 追加生命周期状态（重复声明去重；不调用 = 不限状态）。 */
    fun state(state: EntityState) {
        states += state
    }

    /** 追加谓词；AND 语义，[AndExpr] 摊平后逐个收下。 */
    fun where(expr: QueryExpr) {
        if (expr is AndExpr) {
            terms += expr.terms
        } else {
            terms += expr
        }
    }

    /** 产出不可变 [QueryPlan]；无 archetype 时拒绝（候选集来源必填）。 */
    fun build(): QueryPlan {
        val where: QueryExpr? = when (terms.size) {
            0 -> null
            1 -> terms[0]
            else -> AndExpr(terms.toList())
        }
        return QueryPlan(archetypes.toSet(), states.toSet(), where)
    }
}

/** DSL 入口：[queryPlan] { archetype("X"); where(IntFieldCmp("hp", GT, 0)) }。 */
fun queryPlan(block: QueryPlanBuilder.() -> Unit): QueryPlan = QueryPlanBuilder().apply(block).build()

/**
 * 组件 / 关系谓词查询 facade（§21.5，**P1 工程契约，非 ABI**）。
 *
 * 引擎侧实现是 `com.eratw.port.core.entity.KernelQueryApi`（wrap `IEntityKernel`）；mod 只依赖
 * 本接口与 [QueryPlan]，不接触内核。结果顺序 = EntityId 原始 Long 数值升序（§3 确定性口径），
 * 且是**独立快照列表**——调用方可以自由持有，不受后续内核变更影响。
 *
 * ERB 侧仍只有 `ENTITY_QUERY_COUNT/FILL(archetype,state)`，**不扩字符串谓词 DSL**
 * （避免 stringly-typed 与注入面）；本接口只对 Kotlin Mod 开放。
 */
interface QueryApi {
    fun query(plan: QueryPlan): List<EntityId>
}