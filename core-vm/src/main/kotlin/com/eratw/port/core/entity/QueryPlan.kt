package com.eratw.port.core.entity

import com.eratw.port.modapi.AndExpr
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityRefCompareOp
import com.eratw.port.modapi.EntityRefFieldCmp
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.HasRelation
import com.eratw.port.modapi.IntCompareOp
import com.eratw.port.modapi.IntFieldCmp
import com.eratw.port.modapi.OrExpr
import com.eratw.port.modapi.QueryApi
import com.eratw.port.modapi.QueryExpr
import com.eratw.port.modapi.QueryPlan
import com.eratw.port.modapi.StrCompareOp
import com.eratw.port.modapi.StrFieldCmp

/**
 * QueryPlan 执行器（M7-1，era-arch-v61u.md §21.5；**P1 工程契约，非 ABI**）。
 *
 * 引擎侧唯一执行入口：把 mod-api 的 [QueryPlan] 翻成对 [IEntityKernel] 的读数，
 * 产出按 **EntityId 原始 Long 数值升序**的结果数组。本对象无状态、无缓存、无静态可变全局
 * （core 纯 JVM 铁律）。
 *
 * ## 三条硬语义（§21.5 / 契约 2.3）
 *
 * 1. **候选集先快照**：先把 [IEntityKernel.query] 的全部结果收进本执行器自己的数组，
 *    谓词求值期间**不再调用** `query`；遍历中新增/销毁实体不改变本轮候选集。
 * 2. **结果统一排序**：内核 `query` 返回的是**稠密槽序**，跨 package（pkgShortId 段）时
 *    槽序不等于 EntityId 数值序，故本执行器必须自己排序——不得把内核数组原样当结果。
 * 3. **EntityRef 的 aliveOnly**：悬空引用（非 0 且 `exists` 判否）按 NULL（0）参与比较，
 *    与「悬空 = 不存在」的引擎口径一致，不把 generation 失效的旧值当真实邻居。
 *
 * ## 复杂度上界（诚实边界）
 *
 * 单遍求值，最多 `O(候选数 × 谓词数)`；**无 join、无索引、无任意路径**。高频查询由 Mod
 * 自建索引组件（§21.5「引擎不建通用索引」）。未注册 archetype 由内核 `query` 抛
 * [IllegalArgumentException]，本执行器**原样传播**（不吞不降级）。
 */
object QueryPlanExecutor {

    /**
     * 执行 [plan]：候选集 = 各 archetype × state 的并集（去重），再按 [QueryPlan.where] 过滤，
     * 最后按 EntityId 数值升序返回**独立数组**（调用方可自由持有）。
     *
     * 调用序是确定的：archetype 按名字升序、state 按 [EntityState] 枚举序逐个查询——
     * 同一 plan + 同一内核状态 ⇒ 同一组 `kernel.query` 调用序列（诊断可复现）。
     */
    fun execute(kernel: IEntityKernel, plan: QueryPlan): LongArray {
        val candidates = collectCandidates(kernel, plan)
        val where = plan.where ?: return sortedAscending(candidates)
        val kept = ArrayList<Long>(candidates.size)
        for (id in candidates) {
            if (evaluate(kernel, id, where)) kept += id
        }
        return sortedAscending(kept)
    }

    /**
     * 候选集快照：对每个 archetype（名字升序）与每个 state 码调用一次内核 `query`，
     * 并集去重后拷入本对象的新数组。**此后不再触碰 `query`**（快照隔离）。
     */
    private fun collectCandidates(kernel: IEntityKernel, plan: QueryPlan): LongArray {
        val stateCodes = stateCodesOf(plan)
        val seen = HashSet<Long>(64)
        val out = ArrayList<Long>(64)
        for (archetype in plan.archetypes.sorted()) {
            for (stateCode in stateCodes) {
                for (id in kernel.query(archetype, stateCode)) {
                    if (seen.add(id)) out += id
                }
            }
        }
        return out.toLongArray()
    }

    /**
     * `states` 为空 = 不限状态（单次 `query(archetype, 0)`）；否则按 [EntityState] 枚举序
     * 逐状态查询（`plan.states` 是集合，枚举序保证调用序与集合实现无关）。
     */
    private fun stateCodesOf(plan: QueryPlan): List<Int> {
        if (plan.states.isEmpty()) return listOf(0)
        return EntityState.entries.filter { it in plan.states }.map { lifecycleCode(it) }
    }

    private fun lifecycleCode(state: EntityState): Int = when (state) {
        EntityState.ACTIVE -> EntityLifecycle.ACTIVE
        EntityState.INACTIVE -> EntityLifecycle.INACTIVE
        EntityState.RETIRED -> EntityLifecycle.RETIRED
    }

    /** 数值升序（EntityId 符号位恒 0，有符号 `Long` 比较即无符号语义，§2.3 第 3 条）。 */
    private fun sortedAscending(ids: LongArray): LongArray {
        val arr = ids.copyOf()
        arr.sort()
        return arr
    }

    private fun sortedAscending(ids: Collection<Long>): LongArray {
        val arr = ids.toLongArray()
        arr.sort()
        return arr
    }

    /** 单实体谓词求值（纯函数：只读内核，不写、不查询候选集）。 */
    private fun evaluate(kernel: IEntityKernel, id: Long, expr: QueryExpr): Boolean = when (expr) {
        is AndExpr -> expr.terms.all { evaluate(kernel, id, it) }
        is OrExpr -> expr.terms.any { evaluate(kernel, id, it) }
        is IntFieldCmp -> intMatches(kernel.getInt(id, expr.field), expr.op, expr.value)
        is StrFieldCmp -> strMatches(kernel.getStr(id, expr.field), expr.op, expr.value)
        is EntityRefFieldCmp -> entityRefMatches(kernel, id, expr)
        is HasRelation -> relationMatches(kernel, id, expr)
    }

    private fun intMatches(actual: Long, op: IntCompareOp, expected: Long): Boolean = when (op) {
        IntCompareOp.EQ -> actual == expected
        IntCompareOp.NE -> actual != expected
        IntCompareOp.LT -> actual < expected
        IntCompareOp.LE -> actual <= expected
        IntCompareOp.GT -> actual > expected
        IntCompareOp.GE -> actual >= expected
    }

    /** 按 [String.compareTo] 序比较（EQ/NE；空串是合法值，与内核未写读默认同口径）。 */
    private fun strMatches(actual: String, op: StrCompareOp, expected: String): Boolean {
        val cmp = actual.compareTo(expected)
        return when (op) {
            StrCompareOp.EQ -> cmp == 0
            StrCompareOp.NE -> cmp != 0
        }
    }

    /**
     * EntityRef 字段比较：读 `getInt` 当引用值。
     *
     * [EntityRefFieldCmp.aliveOnly] 为真时把「非 0 且 `exists` 判否」的悬空引用一律当 NULL（0）；
     * 为假时按存储原值比较（§21.5 两种模式都保留：悬空 + 自检 vs 原值审计）。
     */
    private fun entityRefMatches(kernel: IEntityKernel, id: Long, expr: EntityRefFieldCmp): Boolean {
        var raw = kernel.getInt(id, expr.field)
        if (expr.aliveOnly && raw != EntityIdCodec.NULL && !kernel.exists(raw)) {
            raw = EntityIdCodec.NULL
        }
        return when (expr.op) {
            EntityRefCompareOp.EQ -> raw == expr.other.raw
            EntityRefCompareOp.NE -> raw != expr.other.raw
        }
    }

    /**
     * 关系存在性：指定 [HasRelation.other] 时查「是否恰有这条边」（出边 `id -> other`、
     * 入边 `other -> id`）；省略时查任一邻居（`queryRelations` 非空即真）。
     *
     * 省略对端走 [IEntityKernel.queryRelations]（邻接枚举），**不是**候选集查询——
     * 不违反「谓词求值期间不再调 `query`」的快照口径。
     */
    private fun relationMatches(kernel: IEntityKernel, id: Long, expr: HasRelation): Boolean {
        val other = expr.other ?: return kernel.queryRelations(id, expr.kind, expr.outgoing).isNotEmpty()
        return if (expr.outgoing) {
            kernel.hasRelation(id, expr.kind, other.raw)
        } else {
            kernel.hasRelation(other.raw, expr.kind, id)
        }
    }
}

/**
 * [QueryApi] 的引擎侧适配器（§21.5 facade）：wrap [IEntityKernel]，把 [QueryPlan] 交给
 * [QueryPlanExecutor.execute]，再把 Long 位域包成 mod 侧的 [EntityId]。
 *
 * 顺序口径与执行器一致（EntityId 数值升序），返回 [List] 是快照拷贝；本类无状态，
 * 不持有候选集或结果缓存（§2.3 第 7 条：无静态可变全局、无缓存）。
 */
class KernelQueryApi(private val kernel: IEntityKernel) : QueryApi {

    override fun query(plan: QueryPlan): List<EntityId> =
        QueryPlanExecutor.execute(kernel, plan).map { EntityId(it) }
}