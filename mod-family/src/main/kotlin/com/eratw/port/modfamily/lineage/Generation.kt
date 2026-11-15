package com.eratw.port.modfamily.lineage

/** 无父母的根为 0；其余节点取所有父母的最大世代加 1，即从任一根出发的最长路径。 */
fun generations(graph: FamilyGraph): Map<String, Int> {
    val values = mutableMapOf<String, Int>()
    for (id in graph.topologicalOrder()) {
        values[id] = (graph.parentsOf(id).maxOfOrNull { values.getValue(it) } ?: -1) + 1
    }
    return graph.nodeIds.associateWith { values.getValue(it) }
}

fun generation(graph: FamilyGraph, id: String): Int {
    graph.requireNode(id)
    return generations(graph).getValue(id)
}
