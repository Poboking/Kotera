package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId

/**
 * 内存关系图：关系边只存一份「正向方向」，无向语义在查询期做对称判定。
 *
 * 与 `EntityKernel` 的对齐点：
 * - 自动反向边由声明驱动，在 [MemoryRelationships.add] 里落成**另一条 kind 的边**（同生产）；
 * - `dedupeEdges` 命中时 `add` 返回 false（不静默覆盖边值）；
 * - 边值只存一份，`setValue` 只改既有边（不隐式建边由调用方保证）；
 * - 邻接查询按 EntityId 数值升序去重。
 */
internal class EdgeGraph {

    /** 一条边：`from --kind--> to`，恒带一个 Long 边值（关系度）。 */
    internal class Edge(val from: Long, val kind: String, val to: Long, var value: Long)

    private val edges = mutableListOf<Edge>()

    /** 找边；[directed] = false 时反方向命中也算同一条边。 */
    fun find(from: Long, kind: String, to: Long, directed: Boolean): Edge? {
        val direct = edges.firstOrNull { edge -> edge.kind == kind && edge.from == from && edge.to == to }
        if (direct != null || directed) return direct
        return edges.firstOrNull { edge -> edge.kind == kind && edge.from == to && edge.to == from }
    }

    /** 建一条边（调用方已做去重/存在性校验）；边值缺省 0。 */
    fun write(from: Long, kind: String, to: Long) {
        edges += Edge(from, kind, to, 0L)
    }

    /** 删一条边；不存在返回 false（幂等删除由调用方按返回值决定是否再摘反向边）。 */
    fun remove(from: Long, kind: String, to: Long, directed: Boolean): Boolean {
        val edge = find(from, kind, to, directed) ?: return false
        edges.remove(edge)
        return true
    }

    /** 摘掉与某实体相连的全部边（`destroy` 的级联清边；v1 生产内核无条件清所有 kind）。 */
    fun removeTouching(raw: Long) {
        edges.removeAll { edge -> edge.from == raw || edge.to == raw }
    }

    /** 邻接查询：出边 / 入边 / 无向两端，结果按对端 EntityId 数值升序去重。 */
    fun neighborsOf(raw: Long, kind: String, outgoing: Boolean, directed: Boolean): List<EntityId> {
        return edges.asSequence()
            .filter { edge -> edge.kind == kind && touches(edge, raw, outgoing, directed) }
            .map { edge -> if (edge.from == raw) edge.to else edge.from }
            .distinct()
            .sorted()
            .map { other -> EntityId(other) }
            .toList()
    }

    private fun touches(edge: Edge, raw: Long, outgoing: Boolean, directed: Boolean): Boolean =
        if (directed) {
            if (outgoing) edge.from == raw else edge.to == raw
        } else {
            edge.from == raw || edge.to == raw
        }
}
