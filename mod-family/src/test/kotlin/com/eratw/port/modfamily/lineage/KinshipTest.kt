package com.eratw.port.modfamily.lineage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KinshipTest {
    private fun branches(): FamilyGraph {
        val ids = listOf("root", "other-parent") + (1..4).flatMap { listOf("a$it", "b$it") }
        val edges = listOf(
            ParentOf("root", "a1"), ParentOf("root", "b1"),
            ParentOf("other-parent", "a1"), ParentOf("other-parent", "b1"),
        ) + (1..3).flatMap { listOf(ParentOf("a$it", "a${it + 1}"), ParentOf("b$it", "b${it + 1}")) }
        return FamilyGraph(ids.map(::LineageNode), edges)
    }

    @Test
    fun degreeTableUsesAncestorEdgeDistanceSumInBothDirections() {
        val graph = branches()
        val cases = listOf(
            Triple("root", "a1", 1),
            Triple("a1", "b1", 2),
            Triple("a1", "b2", 3),
            Triple("a2", "b2", 4),
            Triple("a2", "b3", 5),
            Triple("a3", "b3", 6),
            Triple("a4", "b4", 8),
        )
        for ((a, b, expected) in cases) {
            assertEquals(expected, kinship(graph, a, b), "$a/$b")
            assertEquals(expected, kinship(graph, b, a), "$b/$a")
        }
    }

    @Test
    fun selfAndDirectAncestorAreLowestCommonAncestors() {
        val graph = branches()
        assertEquals(listOf("a2"), lowestCommonAncestors(graph, "a2", "a2"))
        assertEquals(0, kinship(graph, "a2", "a2"))
        assertEquals(listOf("a1"), lowestCommonAncestors(graph, "a1", "a4"))
        assertEquals(3, kinship(graph, "a1", "a4"))
    }

    @Test
    fun allLowestAncestorsAreReturnedAndDistanceDoesNotUseFirstId() {
        val graph = FamilyGraph(
            listOf("a-ancestor", "z-ancestor", "left", "right", "l1", "r1", "r2").map(::LineageNode),
            listOf(
                ParentOf("a-ancestor", "l1"), ParentOf("l1", "left"),
                ParentOf("a-ancestor", "r1"), ParentOf("r1", "right"),
                ParentOf("z-ancestor", "left"), ParentOf("z-ancestor", "r2"), ParentOf("r2", "right"),
            ),
        )
        assertEquals(listOf("a-ancestor", "z-ancestor"), lowestCommonAncestors(graph, "left", "right"))
        assertEquals(3, kinship(graph, "left", "right"))
        assertEquals(3, kinship(graph, "right", "left"))
    }

    @Test
    fun lowestAncestorTakesPrecedenceOverOlderShortcut() {
        val graph = FamilyGraph(
            listOf("root", "lower", "l1", "r1", "left", "right").map(::LineageNode),
            listOf(
                ParentOf("root", "lower"),
                ParentOf("lower", "l1"), ParentOf("l1", "left"),
                ParentOf("lower", "r1"), ParentOf("r1", "right"),
                ParentOf("root", "left"), ParentOf("root", "right"),
            ),
        )
        assertEquals(listOf("lower"), lowestCommonAncestors(graph, "left", "right"))
        assertEquals(4, kinship(graph, "left", "right"))
    }

    @Test
    fun shortestAncestorDistanceIsNotGenerationDifference() {
        val graph = FamilyGraph(
            listOf("root", "p1", "p2", "child").map(::LineageNode),
            listOf(ParentOf("root", "p1"), ParentOf("p1", "p2"), ParentOf("p2", "child"), ParentOf("root", "child")),
        )
        assertEquals(3, generation(graph, "child"))
        assertEquals(1, kinship(graph, "root", "child"))
    }

    @Test
    fun weakConnectivityDoesNotImplyCommonAncestry() {
        val graph = FamilyGraph(
            listOf("father", "mother", "child", "isolated").map(::LineageNode),
            listOf(ParentOf("father", "child"), ParentOf("mother", "child")),
        )
        assertTrue(lowestCommonAncestors(graph, "father", "mother").isEmpty())
        assertNull(kinship(graph, "father", "mother"))
        assertNull(kinship(graph, "child", "isolated"))
        assertFalse(isTooClose(graph, "father", "mother", Int.MAX_VALUE))
    }

    @Test
    fun removingAnEdgeCanChangeLcaWhileStillConnected() {
        val graph = FamilyGraph(
            listOf("root", "lower", "left", "right").map(::LineageNode),
            listOf(
                ParentOf("root", "lower"), ParentOf("lower", "left"), ParentOf("lower", "right"),
                ParentOf("root", "left"), ParentOf("root", "right"),
            ),
        )
        val after = graph.removeParent("lower", "right")
        assertEquals(listOf("lower"), lowestCommonAncestors(graph, "left", "right"))
        assertEquals(listOf("root"), lowestCommonAncestors(after, "left", "right"))
        assertEquals(5, graph.edges.size)
        assertEquals(4, after.edges.size)
    }

    @Test
    fun siblingsAreDerivedFromTwoSharedParentsNotStoredEdges() {
        val graph = FamilyGraph(
            listOf("p", "q", "r", "s", "a", "b", "half").map(::LineageNode),
            listOf(
                ParentOf("p", "a"), ParentOf("q", "a"),
                ParentOf("p", "b"), ParentOf("q", "b"), ParentOf("r", "b"),
                ParentOf("p", "half"), ParentOf("s", "half"),
            ),
        )
        assertEquals(listOf("b"), graph.siblingsOf("a"))
        assertEquals(listOf("a"), graph.siblingsOf("b"))
        assertTrue(graph.siblingsOf("half").isEmpty())
        assertEquals(2, kinship(graph, "a", "half"))
    }

    @Test
    fun thresholdIsExplicitInclusiveAndBusinessAdjustable() {
        val graph = branches()
        assertFalse(isTooClose(graph, "a1", "b2", 2))
        assertTrue(isTooClose(graph, "a1", "b2", 3))
        assertTrue(isTooClose(graph, "a1", "b2", 10))
        assertTrue(isTooClose(graph, "a1", "a1", 0))
        assertFalse(isTooClose(graph, "a1", "b1", 0))
        assertFailsWith<IllegalArgumentException> { isTooClose(graph, "a1", "b1", -1) }
    }

    @Test
    fun identitiesAreOpaqueAndNeverNormalized() {
        val graph = FamilyGraph(
            listOf("A", "a", " child ").map(::LineageNode),
            listOf(ParentOf("A", " child "), ParentOf("a", " child ")),
        )
        assertEquals(listOf("A", "a"), graph.parentsOf(" child "))
        assertNull(kinship(graph, "A", "a"))
        assertEquals(1, kinship(graph, "A", " child "))
        assertFailsWith<IllegalArgumentException> { kinship(graph, "A", "child") }
    }

    @Test
    fun unknownIdsAreRejectedRatherThanTreatedAsUnrelated() {
        val graph = branches()
        assertFailsWith<IllegalArgumentException> { kinship(graph, "missing", "missing") }
        assertFailsWith<IllegalArgumentException> { kinship(graph, "a1", "missing") }
        assertFailsWith<IllegalArgumentException> { lowestCommonAncestors(graph, "missing", "a1") }
        assertFailsWith<IllegalArgumentException> { graph.siblingsOf("missing") }
    }
}
