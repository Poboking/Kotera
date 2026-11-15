package com.eratw.port.modfamily.lineage

/** LCA 是不存在更低共同祖先的全部共同祖先（祖先含自身）；多解按原始 id 升序，无解返回空表。 */
fun lowestCommonAncestors(graph: FamilyGraph, a: String, b: String): List<String> =
    lowestAncestors(graph, ancestorDistances(graph, a).keys intersect ancestorDistances(graph, b).keys)

/** 亲等 = min(c 属于 LCA(a,b)) [d(c,a) + d(c,b)]；d 为父→子最短边数，无共同祖先返回 null。 */
fun kinship(graph: FamilyGraph, a: String, b: String): Int? {
    val first = ancestorDistances(graph, a)
    val second = ancestorDistances(graph, b)
    return lowestAncestors(graph, first.keys intersect second.keys)
        .minOfOrNull { first.getValue(it) + second.getValue(it) }
}

/** threshold 为业务可调的包含式亲等上界，非引擎约束；无共同祖先时返回 false。 */
fun isTooClose(graph: FamilyGraph, a: String, b: String, threshold: Int): Boolean {
    require(threshold >= 0) { "Kinship threshold must be non-negative" }
    return kinship(graph, a, b)?.let { it <= threshold } ?: false
}

private fun ancestorDistances(graph: FamilyGraph, id: String): Map<String, Int> {
    graph.requireNode(id)
    val distances = mutableMapOf(id to 0)
    val pending = ArrayDeque<String>()
    pending.addLast(id)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        for (parent in graph.parentsOf(current)) {
            if (parent !in distances) {
                distances[parent] = distances.getValue(current) + 1
                pending.addLast(parent)
            }
        }
    }
    return distances
}

private fun lowestAncestors(graph: FamilyGraph, common: Set<String>): List<String> =
    common.filter { candidate -> graph.childrenOf(candidate).none { it in common } }.sorted()
