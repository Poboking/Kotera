package com.eratw.port.modfamily.lineage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GenerationTest {
    @Test
    fun emptyGraphHasNoGenerations() {
        val graph = FamilyGraph(emptyList())

        assertEquals(emptyMap<String, Int>(), generations(graph))
        assertEquals(emptyList<String>(), graph.nodeIds)
        assertEquals(emptyList<LineageNode>(), graph.nodes)
        assertEquals(emptyList<ParentOf>(), graph.edges)
    }

    @Test
    fun disconnectedComponentsAndIsolatedNodesHaveIndependentRoots() {
        val graph = FamilyGraph(
            listOf("isolated", "root-b", "child-b", "root-a", "child-a", "grandchild-a")
                .map(::LineageNode),
            listOf(
                ParentOf("root-a", "child-a"),
                ParentOf("child-a", "grandchild-a"),
                ParentOf("root-b", "child-b"),
            ),
        )

        assertEquals(
            mapOf(
                "isolated" to 0, "root-a" to 0, "child-a" to 1,
                "grandchild-a" to 2, "root-b" to 0, "child-b" to 1,
            ),
            generations(graph),
        )
        assertEquals(0, generation(graph, "isolated"))
    }

    @Test
    fun chainAndForkIncrementEachChildGeneration() {
        val graph = FamilyGraph(
            listOf("root", "child", "left", "right", "leaf").map(::LineageNode),
            listOf(
                ParentOf("root", "child"),
                ParentOf("child", "left"),
                ParentOf("child", "right"),
                ParentOf("left", "leaf"),
            ),
        )
        val expected = mapOf("root" to 0, "child" to 1, "left" to 2, "right" to 2, "leaf" to 3)

        assertEquals(expected, generations(graph))
        for ((id, value) in expected) {
            assertEquals(value, generation(graph, id), id)
        }
    }

    @Test
    fun multipleParentsUseTheDeepestParentPlusOne() {
        val graph = FamilyGraph(
            listOf("z-root", "middle", "deep", "a-child", "leaf").map(::LineageNode),
            listOf(
                ParentOf("z-root", "middle"),
                ParentOf("middle", "deep"),
                ParentOf("z-root", "a-child"),
                ParentOf("middle", "a-child"),
                ParentOf("deep", "a-child"),
                ParentOf("a-child", "leaf"),
            ),
        )

        assertEquals(
            mapOf("z-root" to 0, "middle" to 1, "deep" to 2, "a-child" to 3, "leaf" to 4),
            generations(graph),
        )
        assertEquals(listOf("deep", "middle", "z-root"), graph.parentsOf("a-child"))
        assertEquals(3, generation(graph, "a-child"))
    }

    @Test
    fun selfCycleIsRejectedWithAnExactClosedPath() {
        assertCycle(listOf("a"), listOf(ParentOf("a", "a")), listOf("a", "a"))
    }

    @Test
    fun twoNodeCycleIsRejectedWithAnExactClosedPath() {
        assertCycle(
            listOf("b", "a"),
            listOf(ParentOf("b", "a"), ParentOf("a", "b")),
            listOf("a", "b", "a"),
        )
    }

    @Test
    fun threeNodeCyclePathFollowsDirectedEdgesRatherThanSortingThePath() {
        assertCycle(
            listOf("c", "b", "a"),
            listOf(ParentOf("b", "a"), ParentOf("c", "b"), ParentOf("a", "c")),
            listOf("a", "c", "b", "a"),
        )
    }

    @Test
    fun cycleAfterAnIsolatedRootExcludesTheAcyclicPrefix() {
        assertCycle(
            listOf("c", "b", "a", "0-isolated"),
            listOf(ParentOf("a", "b"), ParentOf("b", "c"), ParentOf("c", "b")),
            listOf("b", "c", "b"),
        )
    }

    @Test
    fun sortedTraversalSelectsTheSameCycleRegardlessOfInputOrder() {
        val ids = listOf("z", "y", "c", "b", "a")
        val edges = listOf(
            ParentOf("y", "z"), ParentOf("z", "y"),
            ParentOf("a", "c"), ParentOf("c", "a"),
            ParentOf("a", "b"), ParentOf("b", "a"),
        )

        for (nodeOrder in listOf(ids, ids.reversed())) {
            for (edgeOrder in listOf(edges, edges.reversed())) {
                assertCycle(nodeOrder, edgeOrder, listOf("a", "b", "a"))
            }
        }
    }

    @Test
    fun tenThousandNodeChainDoesNotRequireRecursiveTraversal() {
        val ids = List(10_000) { "n" + it.toString().padStart(5, '0') }
        val edges = (1 until ids.size).map { ParentOf(ids[it - 1], ids[it]) }
        val graph = FamilyGraph(ids.map(::LineageNode), edges)

        val actual = generations(graph)

        assertEquals(ids.size, actual.size)
        for ((index, id) in ids.withIndex()) {
            assertEquals(index, actual[id], id)
        }
        assertEquals(9_999, generation(graph, ids.last()))
    }

    @Test
    fun addingALegalEdgeCannotDecreaseAnyGenerationOrMutateTheOriginal() {
        val original = FamilyGraph(
            listOf("root", "middle", "deep", "other", "leaf").map(::LineageNode),
            listOf(ParentOf("root", "middle"), ParentOf("middle", "deep"), ParentOf("other", "leaf")),
        )
        val before = generations(original)
        val originalEdges = original.edges

        val extended = original.addParent("deep", "other")
        val after = generations(extended)

        assertNotSame(original, extended)
        assertEquals(before, generations(original))
        assertEquals(originalEdges, original.edges)
        assertEquals(original.nodeIds, extended.nodeIds)
        assertEquals(originalEdges.toSet() + ParentOf("deep", "other"), extended.edges.toSet())
        for (id in original.nodeIds) {
            assertTrue(after.getValue(id) >= before.getValue(id), id)
        }
        assertEquals(mapOf("root" to 0, "middle" to 1, "deep" to 2, "other" to 3, "leaf" to 4), after)
    }

    @Test
    fun removingAnEdgeCannotIncreaseAnyGenerationOrMutateTheOriginal() {
        val original = FamilyGraph(
            listOf("root", "middle", "deep", "other", "leaf").map(::LineageNode),
            listOf(
                ParentOf("root", "middle"), ParentOf("middle", "deep"),
                ParentOf("root", "other"), ParentOf("deep", "other"), ParentOf("other", "leaf"),
            ),
        )
        val before = generations(original)
        val originalEdges = original.edges

        val reduced = original.removeParent("deep", "other")
        val after = generations(reduced)

        assertNotSame(original, reduced)
        assertEquals(before, generations(original))
        assertEquals(originalEdges, original.edges)
        assertEquals(original.nodeIds, reduced.nodeIds)
        assertEquals(originalEdges.toSet() - ParentOf("deep", "other"), reduced.edges.toSet())
        for (id in original.nodeIds) {
            assertTrue(after.getValue(id) <= before.getValue(id), id)
        }
        assertEquals(mapOf("root" to 0, "middle" to 1, "deep" to 2, "other" to 1, "leaf" to 2), after)
        val unchanged = reduced.removeParent("deep", "other")
        assertEquals(after, generations(unchanged))
        assertEquals(reduced.edges, unchanged.edges)
    }

    @Test
    fun unknownIdsAreRejectedByQueriesAndEdgeUpdates() {
        val graph = FamilyGraph(listOf(LineageNode("known")))

        assertFailsWith<IllegalArgumentException> { generation(graph, "missing") }
        assertFailsWith<IllegalArgumentException> { generation(FamilyGraph(emptyList()), "missing") }
        assertFailsWith<IllegalArgumentException> { graph.parentsOf("missing") }
        assertFailsWith<IllegalArgumentException> { graph.childrenOf("missing") }
        assertFailsWith<IllegalArgumentException> { graph.addParent("missing", "known") }
        assertFailsWith<IllegalArgumentException> { graph.addParent("known", "missing") }
        assertFailsWith<IllegalArgumentException> { graph.removeParent("missing", "known") }
        assertFailsWith<IllegalArgumentException> { graph.removeParent("known", "missing") }
        assertEquals(mapOf("known" to 0), generations(graph))
        assertEquals(emptyList<ParentOf>(), graph.edges)
    }

    @Test
    fun duplicateNodeIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            FamilyGraph(listOf(LineageNode("same"), LineageNode("same")))
        }
    }

    @Test
    fun edgesWithMissingEndpointsAreRejected() {
        val nodes = listOf(LineageNode("known"))

        for (edge in listOf(
            ParentOf("missing", "known"),
            ParentOf("known", "missing"),
            ParentOf("missing-parent", "missing-child"),
        )) {
            assertFailsWith<IllegalArgumentException> { FamilyGraph(nodes, listOf(edge)) }
        }
    }

    @Test
    fun duplicateEdgesAreHarmlessDuringConstructionAndAddition() {
        val edge = ParentOf("parent", "child")
        val graph = FamilyGraph(
            listOf(LineageNode("child"), LineageNode("parent")),
            listOf(edge, edge, edge),
        )

        assertEquals(listOf(edge), graph.edges)
        assertEquals(listOf("parent"), graph.parentsOf("child"))
        assertEquals(listOf("child"), graph.childrenOf("parent"))
        assertEquals(mapOf("parent" to 0, "child" to 1), generations(graph))
        val unchanged = graph.addParent("parent", "child")
        assertNotSame(graph, unchanged)
        assertEquals(graph.edges, unchanged.edges)
        assertEquals(generations(graph), generations(unchanged))
    }

    @Test
    fun graphSnapshotsItsInputCollections() {
        val nodes = mutableListOf(LineageNode("child"), LineageNode("parent"))
        val edges = mutableListOf(ParentOf("parent", "child"))
        val graph = FamilyGraph(nodes, edges)

        nodes.clear()
        nodes.add(LineageNode("replacement"))
        edges.clear()
        edges.add(ParentOf("replacement", "missing"))

        assertEquals(listOf("child", "parent"), graph.nodeIds)
        assertEquals(listOf(LineageNode("child"), LineageNode("parent")), graph.nodes)
        assertEquals(listOf(ParentOf("parent", "child")), graph.edges)
        assertEquals(listOf("parent"), graph.parentsOf("child"))
        assertEquals(listOf("child"), graph.childrenOf("parent"))
        assertEquals(mapOf("parent" to 0, "child" to 1), generations(graph))
    }

    @Test
    fun addingACycleIsRejectedWithoutChangingTheOriginalGraph() {
        val graph = FamilyGraph(
            listOf("a", "b", "c").map(::LineageNode),
            listOf(ParentOf("a", "b"), ParentOf("b", "c")),
        )
        val beforeEdges = graph.edges
        val beforeGenerations = generations(graph)

        val failure = assertFailsWith<LineageCycleException> { graph.addParent("c", "a") }

        assertEquals(listOf("a", "b", "c", "a"), failure.cyclePath)
        assertEquals(beforeEdges, graph.edges)
        assertEquals(beforeGenerations, generations(graph))
    }

    private fun assertCycle(ids: List<String>, edges: List<ParentOf>, expected: List<String>) {
        val failure = assertFailsWith<LineageCycleException> {
            FamilyGraph(ids.map(::LineageNode), edges)
        }
        val path = failure.cyclePath

        assertEquals(expected, path)
        assertTrue(path.size >= 2)
        assertEquals(path.first(), path.last())
        for ((parent, child) in path.zipWithNext()) {
            assertTrue(ParentOf(parent, child) in edges, "$parent -> $child must be a directed edge")
        }
    }
}
