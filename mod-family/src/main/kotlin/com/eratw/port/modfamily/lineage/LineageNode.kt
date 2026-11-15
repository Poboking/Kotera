package com.eratw.port.modfamily.lineage

data class LineageNode(val id: String)

data class ParentOf(val parentId: String, val childId: String)

class LineageCycleException(path: List<String>) : IllegalArgumentException("Lineage cycle: ${path.joinToString(" -> ")}") {
    private val pathSnapshot = path.toList()
    val cyclePath: List<String> get() = pathSnapshot.toList()
}
