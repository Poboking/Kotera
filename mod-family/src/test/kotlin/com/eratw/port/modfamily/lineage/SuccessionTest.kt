package com.eratw.port.modfamily.lineage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class SuccessionTest {
    @Test
    fun emptyAndSingletonCollectionsWorkForBothRules() {
        val only = SuccessionCandidate("only", -2, 7L)

        for (rule in SuccessionRule.values()) {
            assertEquals(emptyList<SuccessionCandidate>(), succession(emptyList(), rule))
            assertEquals(listOf(only), succession(listOf(only), rule))
        }
    }

    @Test
    fun eldestFirstOrdersBySignedGenerationThenAscendingBirthOrder() {
        val candidates = ordinaryCandidates()

        val result = succession(candidates, SuccessionRule.ELDEST_FIRST)

        assertEquals(
            listOf("ancestor-old", "ancestor-young", "peer-old", "peer-young", "descendant-old", "descendant-young"),
            result.map { it.id },
        )
        assertEquals(candidates.size, result.size)
        assertEquals(candidates.toSet(), result.toSet())
    }

    @Test
    fun youngestFirstReversesBirthOrderButNotGenerationOrder() {
        val candidates = ordinaryCandidates()

        val result = succession(candidates, SuccessionRule.YOUNGEST_FIRST)

        assertEquals(
            listOf("ancestor-young", "ancestor-old", "peer-young", "peer-old", "descendant-young", "descendant-old"),
            result.map { it.id },
        )
        assertEquals(candidates.size, result.size)
        assertEquals(candidates.toSet(), result.toSet())
    }

    @Test
    fun rawIdIsTheAscendingFinalTieBreakerForBothRules() {
        val candidates = listOf("a", "2", "\u03b1", "10", "Z", "\u00c4", "A")
            .map { SuccessionCandidate(it, 0, 10L) }
        val expected = listOf("10", "2", "A", "Z", "a", "\u00c4", "\u03b1")

        for (rule in SuccessionRule.values()) {
            assertEquals(expected, succession(candidates, rule).map { it.id }, rule.name)
            assertEquals(expected, succession(candidates.reversed(), rule).map { it.id }, rule.name)
        }
    }

    @Test
    fun generationExtremesAreComparedWithoutSubtractionOverflow() {
        val candidates = listOf(
            SuccessionCandidate("maximum", Int.MAX_VALUE, Long.MIN_VALUE),
            SuccessionCandidate("zero", 0, 0L),
            SuccessionCandidate("minimum", Int.MIN_VALUE, Long.MAX_VALUE),
            SuccessionCandidate("positive", 1, -1L),
            SuccessionCandidate("negative", -1, 1L),
        )
        val expected = listOf("minimum", "negative", "zero", "positive", "maximum")

        for (rule in SuccessionRule.values()) {
            assertEquals(expected, succession(candidates, rule).map { it.id }, rule.name)
            assertEquals(expected, succession(candidates.reversed(), rule).map { it.id }, rule.name)
        }
    }

    @Test
    fun birthOrderExtremesAreComparedWithoutSubtractionOrNegationOverflow() {
        val candidates = listOf(
            SuccessionCandidate("maximum", 0, Long.MAX_VALUE),
            SuccessionCandidate("negative", 0, -1L),
            SuccessionCandidate("minimum", 0, Long.MIN_VALUE),
            SuccessionCandidate("positive", 0, 1L),
            SuccessionCandidate("zero", 0, 0L),
        )

        assertEquals(
            listOf("minimum", "negative", "zero", "positive", "maximum"),
            succession(candidates, SuccessionRule.ELDEST_FIRST).map { it.id },
        )
        assertEquals(
            listOf("maximum", "positive", "zero", "negative", "minimum"),
            succession(candidates, SuccessionRule.YOUNGEST_FIRST).map { it.id },
        )
    }

    @Test
    fun duplicateIdsAreRejectedEvenWhenOtherFieldsDiffer() {
        val candidate = SuccessionCandidate("duplicate", 0, 0L)
        val conflicting = SuccessionCandidate("duplicate", Int.MAX_VALUE, Long.MIN_VALUE)

        for (rule in SuccessionRule.values()) {
            for (duplicate in listOf(candidate, conflicting)) {
                val input = mutableListOf(candidate, SuccessionCandidate("distinct", -1, 4L), duplicate)
                val before = input.toList()

                assertFailsWith<IllegalArgumentException> { succession(input, rule) }
                assertEquals(before, input)
            }
        }
    }

    @Test
    fun sortingDoesNotMutateTheInputOrReturnAnInputBackedList() {
        for (rule in SuccessionRule.values()) {
            val input = ordinaryCandidates().toMutableList()
            val before = input.toList()

            val result = succession(input, rule)

            assertEquals(before, input, rule.name)
            assertNotSame(input, result)
            assertEquals(before.size, result.size)
            assertEquals(before.toSet(), result.toSet())
            val resultSnapshot = result.toList()
            input.clear()
            input.add(SuccessionCandidate("replacement", 42, 42L))
            assertEquals(resultSnapshot, result)
        }
    }

    @Test
    fun twentyFixedSeedFisherYatesPermutationsHaveIdenticalSuccession() {
        val candidates = listOf(
            SuccessionCandidate("min-young", Int.MIN_VALUE, Long.MAX_VALUE),
            SuccessionCandidate("root-a", 0, 0L),
            SuccessionCandidate("max-old", Int.MAX_VALUE, Long.MIN_VALUE),
            SuccessionCandidate("root-first", 0, Long.MIN_VALUE),
            SuccessionCandidate("older", -1, 100L),
            SuccessionCandidate("max-young", Int.MAX_VALUE, Long.MAX_VALUE),
            SuccessionCandidate("root-Z", 0, 0L),
            SuccessionCandidate("next", 1, Long.MIN_VALUE),
            SuccessionCandidate("min-old", Int.MIN_VALUE, Long.MIN_VALUE),
            SuccessionCandidate("root-last", 0, Long.MAX_VALUE),
        )
        val expectedEldest = listOf(
            "min-old", "min-young", "older", "root-first", "root-Z",
            "root-a", "root-last", "next", "max-old", "max-young",
        )
        val expectedYoungest = listOf(
            "min-young", "min-old", "older", "root-last", "root-Z",
            "root-a", "root-first", "next", "max-young", "max-old",
        )
        var state = 0x00C0FFEEu

        repeat(20) { iteration ->
            val shuffled = candidates.toMutableList()
            for (index in shuffled.lastIndex downTo 1) {
                state = state * 1_664_525u + 1_013_904_223u
                val other = (state % (index + 1).toUInt()).toInt()
                val temporary = shuffled[index]
                shuffled[index] = shuffled[other]
                shuffled[other] = temporary
            }
            val before = shuffled.toList()
            assertEquals(candidates.size, shuffled.size)
            assertEquals(candidates.toSet(), shuffled.toSet())

            for (rule in SuccessionRule.values()) {
                val expected = when (rule) {
                    SuccessionRule.ELDEST_FIRST -> expectedEldest
                    SuccessionRule.YOUNGEST_FIRST -> expectedYoungest
                }
                val result = succession(shuffled, rule)

                assertEquals(expected, result.map { it.id }, "Permutation $iteration, $rule")
                assertEquals(candidates.size, result.size)
                assertEquals(candidates.toSet(), result.toSet())
                assertEquals(before, shuffled, "Input changed for permutation $iteration, $rule")
            }
        }
    }

    private fun ordinaryCandidates(): List<SuccessionCandidate> = listOf(
        SuccessionCandidate("descendant-young", 3, 2L),
        SuccessionCandidate("peer-young", 0, 9L),
        SuccessionCandidate("ancestor-young", -2, 200L),
        SuccessionCandidate("descendant-old", 3, 1L),
        SuccessionCandidate("ancestor-old", -2, 100L),
        SuccessionCandidate("peer-old", 0, 3L),
    )
}
