package com.eratw.port.modfamily.lineage

data class SuccessionCandidate(val id: String, val generationDifference: Int, val birthOrder: Long)

enum class SuccessionRule { ELDEST_FIRST, YOUNGEST_FIRST }

/** 顺位按有符号世代差升序、长幼序、原始 id 字典序；birthOrder 越小越年长，rule 只控制长幼方向。 */
fun succession(candidates: Collection<SuccessionCandidate>, rule: SuccessionRule): List<SuccessionCandidate> {
    require(candidates.map { it.id }.toSet().size == candidates.size) { "Duplicate succession candidate ids" }
    val byGeneration = compareBy(SuccessionCandidate::generationDifference)
    val bySeniority = when (rule) {
        SuccessionRule.ELDEST_FIRST -> byGeneration.thenBy(SuccessionCandidate::birthOrder)
        SuccessionRule.YOUNGEST_FIRST -> byGeneration.thenByDescending(SuccessionCandidate::birthOrder)
    }
    return candidates.sortedWith(bySeniority.thenBy(SuccessionCandidate::id))
}
