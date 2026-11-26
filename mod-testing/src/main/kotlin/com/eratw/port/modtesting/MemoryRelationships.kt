package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.RelationshipApi

/**
 * 内存关系实现：语义对齐 `EntityKernel` 的关系段（自动反向边、去重、边值单份、失败哨兵）。
 *
 * 与生产一致的关键行为：
 * - `add`：关系类型未声明 / 任一端实体不存在 / 去重命中 ⇒ `false`（不抛、不静默建）；
 * - autoInverse 的正向关系在建边时**另落一条反向 kind 的边**（与生产内核同口径）；
 * - `setValue` 只改既有边的边值，**不隐式建边**（作者用例靠它区分「没这条边」和「边值为 0」）；
 * - `neighbors` 结果按 EntityId 数值升序去重（生产 mod-api 层同样排序）。
 */
internal class MemoryRelationships(
    private val schema: MemorySchema,
    private val store: EntityStore,
) : RelationshipApi {

    private val graph = EdgeGraph()

    override fun hasKind(kind: String): Boolean = normalize(kind) in schema.relationships

    override fun add(from: EntityId, kind: String, to: EntityId): Boolean {
        val key = normalize(kind)
        val spec = schema.relationships[key]
        val appendable = spec != null &&
            store.exists(from) &&
            store.exists(to) &&
            !(spec.dedupeEdges && graph.find(from.raw, key, to.raw, spec.directed) != null)
        if (appendable) {
            graph.write(from.raw, key, to.raw)
            mirrorInverse(from, key, to)
        }
        return appendable
    }

    override fun remove(from: EntityId, kind: String, to: EntityId): Boolean {
        val key = normalize(kind)
        val spec = schema.relationships[key] ?: return false
        val removed = graph.remove(from.raw, key, to.raw, spec.directed)
        val inverseKind = schema.inverseOf[key]
        if (inverseKind != null) {
            val inverseSpec = schema.relationships.getValue(inverseKind)
            graph.remove(to.raw, inverseKind, from.raw, inverseSpec.directed)
        }
        return removed
    }

    override fun has(from: EntityId, kind: String, to: EntityId): Boolean {
        val key = normalize(kind)
        val spec = schema.relationships[key] ?: return false
        return graph.find(from.raw, key, to.raw, spec.directed) != null
    }

    override fun valueOf(from: EntityId, kind: String, to: EntityId): Long {
        val key = normalize(kind)
        val spec = schema.relationships[key]
        return spec?.let { relation -> graph.find(from.raw, key, to.raw, relation.directed)?.value } ?: 0L
    }

    override fun setValue(from: EntityId, kind: String, to: EntityId, value: Long): Boolean {
        val key = normalize(kind)
        val spec = schema.relationships[key]
        val edge = spec?.let { relation -> graph.find(from.raw, key, to.raw, relation.directed) }
        if (edge != null) edge.value = value
        return edge != null
    }

    override fun neighbors(id: EntityId, kind: String, outgoing: Boolean): List<EntityId> {
        val key = normalize(kind)
        val spec = schema.relationships[key] ?: return emptyList()
        return graph.neighborsOf(id.raw, key, outgoing, spec.directed)
    }

    /** `destroy` 级联清边：v1 生产内核无条件清该实体相关的**所有** kind（不看 cascadeOnDestroy）。 */
    fun removeAllEdgesOf(id: EntityId) {
        graph.removeTouching(id.raw)
    }

    private fun mirrorInverse(from: EntityId, key: String, to: EntityId) {
        val inverseKind = schema.inverseOf[key] ?: return
        val inverseSpec = schema.relationships.getValue(inverseKind)
        val duplicated = inverseSpec.dedupeEdges &&
            graph.find(to.raw, inverseKind, from.raw, inverseSpec.directed) != null
        if (!duplicated) graph.write(to.raw, inverseKind, from.raw)
    }
}
