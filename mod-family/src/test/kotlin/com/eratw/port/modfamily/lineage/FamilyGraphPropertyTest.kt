package com.eratw.port.modfamily.lineage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FamilyGraphPropertyTest {
    private fun generatedGraph(seed: UInt, rooted: Boolean): FamilyGraph {
        var state = seed
        fun next(bound: Int): Int {
            state = state * 1664525u + 1013904223u
            return ((state shr 8) % bound.toUInt()).toInt()
        }
        val ids = (0 until 6 + next(9)).map { "node-$it" }
        val edges = mutableSetOf<ParentOf>()
        for (child in 1 until ids.size) {
            if (rooted) edges.add(ParentOf(ids[next(child)], ids[child]))
            for (parent in 0 until child) {
                if (next(4) == 0) edges.add(ParentOf(ids[parent], ids[child]))
            }
        }
        return FamilyGraph(ids.map(::LineageNode), edges)
    }

    private fun shortestPaths(graph: FamilyGraph): Array<IntArray> {
        val ids = graph.nodeIds
        val index = ids.withIndex().associate { it.value to it.index }
        val distance = Array(ids.size) { a -> IntArray(ids.size) { b -> if (a == b) 0 else ids.size + 1 } }
        for (edge in graph.edges) distance[index.getValue(edge.parentId)][index.getValue(edge.childId)] = 1
        for (via in ids.indices) {
            for (a in ids.indices) {
                for (b in ids.indices) {
                    distance[a][b] = minOf(distance[a][b], distance[a][via] + distance[via][b])
                }
            }
        }
        return distance
    }

    @Test
    fun fixedSeedGraphsMatchIndependentLcaAndDistanceOracle() {
        for (seed in 1..64) {
            val graph = generatedGraph(20261110u + seed.toUInt(), rooted = false)
            val ids = graph.nodeIds
            val distance = shortestPaths(graph)
            for (a in ids.indices) {
                assertEquals(0, kinship(graph, ids[a], ids[a]))
                for (b in ids.indices) {
                    val common = ids.indices.filter { distance[it][a] <= ids.size && distance[it][b] <= ids.size }
                    val lowest = common.filter { candidate ->
                        common.none { other -> other != candidate && distance[candidate][other] <= ids.size }
                    }
                    val expected = lowest.minOfOrNull { distance[it][a] + distance[it][b] }
                    val actual = kinship(graph, ids[a], ids[b])
                    assertEquals(lowest.map { ids[it] }.sorted(), lowestCommonAncestors(graph, ids[a], ids[b]))
                    assertEquals(expected, actual, "seed=$seed ${ids[a]}/${ids[b]}")
                    assertEquals(actual, kinship(graph, ids[b], ids[a]))
                    assertTrue(actual == null || actual >= 0)
                }
            }
        }
    }

    @Test
    fun rootedGraphsHaveLcaForEveryPair() {
        for (seed in 1..32) {
            val graph = generatedGraph(314159u + seed.toUInt(), rooted = true)
            for (a in graph.nodeIds) {
                for (b in graph.nodeIds) {
                    assertTrue(lowestCommonAncestors(graph, a, b).isNotEmpty())
                    assertNotNull(kinship(graph, a, b))
                }
            }
        }
    }

    @Test
    fun everyBackEdgeToAnAncestorIsRejectedWithARealClosedPath() {
        var checked = 0
        for (seed in 1..24) {
            val graph = generatedGraph(271828u + seed.toUInt(), rooted = true)
            val ids = graph.nodeIds
            val distance = shortestPaths(graph)
            for (a in ids.indices) {
                for (b in ids.indices) {
                    if (a == b || distance[a][b] > ids.size) continue
                    val backEdge = ParentOf(ids[b], ids[a])
                    val error = assertFailsWith<LineageCycleException> { graph.addParent(backEdge.parentId, backEdge.childId) }
                    val path = error.cyclePath
                    assertEquals(path.first(), path.last())
                    assertEquals(path.size - 1, path.dropLast(1).toSet().size)
                    val allEdges = graph.edges.toSet() + backEdge
                    assertTrue(path.zipWithNext().all { (parent, child) -> ParentOf(parent, child) in allEdges })
                    checked++
                }
            }
        }
        assertTrue(checked > 100)
    }

    @Test
    fun legalEdgeAdditionNeverDecreasesAnyGeneration() {
        var checked = 0
        for (seed in 1..16) {
            val graph = generatedGraph(161803u + seed.toUInt(), rooted = false)
            val ids = graph.nodeIds
            val distance = shortestPaths(graph)
            val before = generations(graph)
            val edges = graph.edges.toSet()
            for (parent in ids.indices) {
                for (child in ids.indices) {
                    if (parent == child || distance[child][parent] <= ids.size) continue
                    if (ParentOf(ids[parent], ids[child]) in edges) continue
                    val after = generations(graph.addParent(ids[parent], ids[child]))
                    for (id in ids) assertTrue(after.getValue(id) >= before.getValue(id))
                    checked++
                }
            }
            assertEquals(before, generations(graph))
        }
        assertTrue(checked > 100)
    }

    @Test
    fun graphQueriesDoNotDependOnNodeOrEdgeInsertionOrder() {
        for (seed in 1..24) {
            val original = generatedGraph(141421u + seed.toUInt(), rooted = false)
            val reversed = FamilyGraph(original.nodes.reversed(), original.edges.reversed())
            assertEquals(original.nodeIds, reversed.nodeIds)
            assertEquals(original.edges, reversed.edges)
            assertEquals(generations(original), generations(reversed))
            for (a in original.nodeIds) {
                assertEquals(original.siblingsOf(a), reversed.siblingsOf(a))
                for (b in original.nodeIds) {
                    assertEquals(lowestCommonAncestors(original, a, b), lowestCommonAncestors(reversed, a, b))
                    assertEquals(kinship(original, a, b), kinship(reversed, a, b))
                }
            }
        }
    }

    @Test
    fun graphOwnsSnapshotsAndNeverExposesItsMutableStorage() {
        val nodes = mutableListOf(LineageNode("p"), LineageNode("q"), LineageNode("child"))
        val edges = mutableListOf(ParentOf("p", "child"), ParentOf("q", "child"))
        val graph = FamilyGraph(nodes, edges)
        nodes.clear()
        edges.clear()
        assertEquals(listOf("child", "p", "q"), graph.nodeIds)
        assertEquals(listOf("p", "q"), graph.parentsOf("child"))
        (graph.nodeIds as MutableList<String>).clear()
        (graph.nodes as MutableList<LineageNode>).clear()
        (graph.edges as MutableList<ParentOf>).clear()
        (graph.parentsOf("child") as MutableList<String>).clear()
        assertEquals(listOf("child", "p", "q"), graph.nodeIds)
        assertEquals(2, graph.edges.size)
        assertEquals(listOf("p", "q"), graph.parentsOf("child"))
        assertEquals(1, generation(graph, "child"))
    }

    @Test
    fun cycleEvidenceIsDetachedFromTheCallerAndReturnedLists() {
        val path = mutableListOf("a", "b", "a")
        val error = LineageCycleException(path)
        path.clear()
        (error.cyclePath as MutableList<String>).clear()
        assertEquals(listOf("a", "b", "a"), error.cyclePath)
        assertTrue(error.message!!.contains("a -> b -> a"))
    }

    @Test
    fun generatorIsReproducibleFromItsSeed() {
        for (rooted in listOf(false, true)) {
            val first = generatedGraph(20261110u, rooted)
            val second = generatedGraph(20261110u, rooted)
            assertEquals(first.nodes, second.nodes)
            assertEquals(first.edges, second.edges)
        }
    }
}
