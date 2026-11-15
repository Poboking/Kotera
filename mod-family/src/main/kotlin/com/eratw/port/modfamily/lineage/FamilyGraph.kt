package com.eratw.port.modfamily.lineage

class FamilyGraph(nodes: Collection<LineageNode>, edges: Collection<ParentOf> = emptyList()) {
    private val ids = nodes.map { it.id }.sorted()
    private val idSet = ids.toSet()
    private val parentEdges = edges.distinct().sortedWith(compareBy(ParentOf::parentId, ParentOf::childId))
    private val parentIndex: Map<String, List<String>>
    private val childIndex: Map<String, List<String>>

    init {
        require(ids.size == idSet.size) { "Duplicate lineage ids" }
        val parents = ids.associateWith { mutableListOf<String>() }
        val children = ids.associateWith { mutableListOf<String>() }
        for (edge in parentEdges) {
            requireNode(edge.parentId)
            requireNode(edge.childId)
            parents.getValue(edge.childId).add(edge.parentId)
            children.getValue(edge.parentId).add(edge.childId)
        }
        parentIndex = parents.mapValues { (_, values) -> values.toList() }
        childIndex = children.mapValues { (_, values) -> values.toList() }
        topologicalOrder()
    }

    val nodeIds: List<String> get() = ids.toList()
    val nodes: List<LineageNode> get() = ids.map(::LineageNode)
    val edges: List<ParentOf> get() = parentEdges.toList()

    fun parentsOf(id: String): List<String> {
        requireNode(id)
        return parentIndex.getValue(id).toList()
    }

    fun childrenOf(id: String): List<String> {
        requireNode(id)
        return childIndex.getValue(id).toList()
    }

    /** 同胞须共享至少两位父母；共享一位父母仍可有亲等，但不列入同胞集合。 */
    fun siblingsOf(id: String): List<String> {
        val parents = parentsOf(id).toSet()
        return ids.filter { other ->
            other != id && parentIndex.getValue(other).count { it in parents } >= 2
        }
    }

    fun addParent(parentId: String, childId: String): FamilyGraph =
        FamilyGraph(nodes, parentEdges + ParentOf(parentId, childId))

    fun removeParent(parentId: String, childId: String): FamilyGraph {
        requireNode(parentId)
        requireNode(childId)
        return FamilyGraph(nodes, parentEdges.filterNot { it == ParentOf(parentId, childId) })
    }

    internal fun requireNode(id: String) {
        require(id in idSet) { "Unknown lineage id: $id" }
    }

    internal fun topologicalOrder(): List<String> {
        val completed = mutableSetOf<String>()
        val activePositions = mutableMapOf<String, Int>()
        val path = mutableListOf<String>()
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        val order = mutableListOf<String>()

        fun enter(id: String) {
            activePositions[id] = path.size
            path.add(id)
            stack.addLast(id to childIndex.getValue(id).iterator())
        }

        for (root in ids) {
            if (root in completed) continue
            enter(root)
            while (stack.isNotEmpty()) {
                val (id, children) = stack.last()
                if (!children.hasNext()) {
                    stack.removeLast()
                    path.removeAt(path.lastIndex)
                    activePositions.remove(id)
                    completed.add(id)
                    order.add(id)
                    continue
                }
                val child = children.next()
                val cycleStart = activePositions[child]
                if (cycleStart != null) {
                    throw LineageCycleException(path.drop(cycleStart) + child)
                }
                if (child !in completed) enter(child)
            }
        }
        return order.asReversed().toList()
    }
}
