package com.eratw.port.modtesting

import com.eratw.port.modapi.AndExpr
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityRefCompareOp
import com.eratw.port.modapi.EntityRefFieldCmp
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
 * 内存查询实现：`QueryPlan` 的求值秩序照生产（先 archetype/state 过滤，再 where，最后按
 * EntityId 数值升序返回快照）。
 *
 * 两个容易抄错的语义点，这里与生产 mod-api 契约保持同口径：
 * - 字符串比较走 `String.compareTo` 序（不是本地化序）；
 * - [EntityRefFieldCmp.aliveOnly] 为真时，实体字段读出的「非 0 但实体不存在」悬空引用按 `EntityId.NULL`
 *   参与比较；查询字面量 [EntityRefFieldCmp.other] 恒按原值比较（与生产 QueryPlanExecutor 同口径）。
 */
internal class MemoryQuery(
    private val store: EntityStore,
    private val relationships: MemoryRelationships,
) : QueryApi {

    override fun query(plan: QueryPlan): List<EntityId> {
        val archetypes = plan.archetypes.map { archetype -> normalize(archetype) }.toSet()
        return store.snapshot.entries.asSequence()
            .filter { entry -> entry.value.archetype in archetypes }
            .filter { entry -> plan.states.isEmpty() || entry.value.state in plan.states }
            .filter { entry ->
                val where = plan.where
                where == null || matches(EntityId(entry.key), where)
            }
            .map { entry -> EntityId(entry.key) }
            .sortedBy { id -> id.raw }
            .toList()
    }

    private fun matches(entity: EntityId, expr: QueryExpr): Boolean = when (expr) {
        is IntFieldCmp -> compareInt(store.getInt(entity, expr.field), expr.op, expr.value)
        is StrFieldCmp -> compareStr(store.getStr(entity, expr.field), expr.op, expr.value)
        is EntityRefFieldCmp -> matchesRef(entity, expr)
        is HasRelation -> matchesRelation(entity, expr)
        is AndExpr -> expr.terms.all { term -> matches(entity, term) }
        is OrExpr -> expr.terms.any { term -> matches(entity, term) }
    }

    private fun compareInt(actual: Long, op: IntCompareOp, expected: Long): Boolean = when (op) {
        IntCompareOp.EQ -> actual == expected
        IntCompareOp.NE -> actual != expected
        IntCompareOp.LT -> actual < expected
        IntCompareOp.LE -> actual <= expected
        IntCompareOp.GT -> actual > expected
        IntCompareOp.GE -> actual >= expected
    }

    private fun compareStr(actual: String, op: StrCompareOp, expected: String): Boolean = when (op) {
        StrCompareOp.EQ -> actual == expected
        StrCompareOp.NE -> actual != expected
    }

    private fun matchesRef(entity: EntityId, expr: EntityRefFieldCmp): Boolean {
        // aliveOnly 只归一实体字段读数；other.raw 是查询字面量，照生产 QueryPlanExecutor 按原值比较。
        val actual = resolveRef(store.getInt(entity, expr.field), expr.aliveOnly)
        return when (expr.op) {
            EntityRefCompareOp.EQ -> actual == expr.other.raw
            EntityRefCompareOp.NE -> actual != expr.other.raw
        }
    }

    private fun resolveRef(raw: Long, aliveOnly: Boolean): Long {
        if (!aliveOnly || raw == 0L) return raw
        return if (store.exists(EntityId(raw))) raw else 0L
    }

    private fun matchesRelation(entity: EntityId, expr: HasRelation): Boolean {
        val other = expr.other
        if (other == null) {
            return relationships.neighbors(entity, expr.kind, expr.outgoing).isNotEmpty()
        }
        return if (expr.outgoing) {
            relationships.has(entity, expr.kind, other)
        } else {
            relationships.has(other, expr.kind, entity)
        }
    }
}
