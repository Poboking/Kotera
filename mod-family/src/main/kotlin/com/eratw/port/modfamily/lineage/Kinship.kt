package com.eratw.port.modfamily.lineage

/** LCA 是不存在更低共同祖先的全部共同祖先（祖先含自身）；多解按原始 id 升序，无解返回空表。 */
fun lowestCommonAncestors(graph: FamilyGraph, a: String, b: String): List<String> =
    lowestAncestors(graph, ancestorDistances(graph, a).keys intersect ancestorDistances(graph, b).keys)

/**
 * 亲等 = min(c 属于 LCA(a,b)) [d(c,a) + d(c,b)]；d 为父→子最短边数，无共同祖先返回 null。
 *
 * 口径边界（判例测试 lowestAncestorTakesPrecedenceOverOlderShortcut 钉住的刻意设计）：LCA 按
 * [lowestCommonAncestors] 的结构口径取——有更低共同祖先即出局——不按边距离取。良构家谱里
 * 「结构更低」与「辈分更近」同向，两种口径同值；家谱成环（某共同祖先同时是另一共同祖先的
 * 晚辈）时，距离更近但结构更高的共同祖先会被排除，本函数给出的亲等**大于**「对全部共同祖先
 * 取 min」的民法口径，isTooClose 在该形态下偏向放行。需要民法口径的调用方对共同祖先全集
 * 自行取 min（本库刻意保持结构口径，不在此处改语义）。
 */
fun kinship(graph: FamilyGraph, a: String, b: String): Int? {
    val first = ancestorDistances(graph, a)
    val second = ancestorDistances(graph, b)
    return lowestAncestors(graph, first.keys intersect second.keys)
        .minOfOrNull { first.getValue(it) + second.getValue(it) }
}

/**
 * threshold 为业务可调的包含式亲等上界，非引擎约束；无共同祖先时返回 false。
 * 亲等的结构 LCA 口径在环状家谱下会高估（见 [kinship]）⇒ 本闸在该形态下偏放行（漏检方向）。
 */
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
