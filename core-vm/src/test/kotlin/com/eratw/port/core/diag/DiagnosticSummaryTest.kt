package com.eratw.port.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 失败归因摘要查询的契约闸（CONTRACT-MODDX-3 §2 Lane DX3-B）。
 *
 * 钉住四件事：空环为空、只收 FAILED / REJECTED（反向证明锚点）、
 * 三元组全等才合并且 count 降序 + 同数 key 字典序、对 snapshot / exportJsonLines 零改写。
 *
 * 非 bug 修复：新增可观测查询面，根因层断言「不适用 §3.1」。
 */
class DiagnosticSummaryTest {

    private fun RuntimeDiagnostics.put(
        domain: DiagnosticDomain = DiagnosticDomain.PHASE,
        packageId: String? = null,
        key: String = "k",
        outcome: DiagnosticOutcome = DiagnosticOutcome.OK,
    ) = record(
        day = null,
        phase = null,
        domain = domain,
        packageId = packageId,
        key = key,
        outcome = outcome,
        reasonCode = "R",
    )

    @Test
    fun `空环与关闭态摘要均为空`() {
        assertTrue(RuntimeDiagnostics().failureSummary().isEmpty(), "空环摘要为空")
        assertTrue(RuntimeDiagnostics.disabled().failureSummary().isEmpty(), "关闭态摘要为空")
    }

    @Test
    fun `只统计FAILED与REJECTED_OK与SKIPPED不上摘要`() {
        val d = RuntimeDiagnostics()
        d.put(outcome = DiagnosticOutcome.OK)
        d.put(outcome = DiagnosticOutcome.SKIPPED)
        d.put(domain = DiagnosticDomain.JOB, packageId = "com.a", outcome = DiagnosticOutcome.FAILED)
        d.put(domain = DiagnosticDomain.PHASE, outcome = DiagnosticOutcome.REJECTED)
        val s = d.failureSummary()
        assertEquals(2, s.size, "OK/SKIPPED 不上摘要；摘掉聚合过滤这条即红")
        assertEquals(
            setOf(DiagnosticOutcome.FAILED, DiagnosticOutcome.REJECTED),
            s.map { it.outcome }.toSet(),
        )
    }

    @Test
    fun `三元组全等才合并计数`() {
        val d = RuntimeDiagnostics()
        repeat(2) { d.put(domain = DiagnosticDomain.JOB, packageId = "com.a", outcome = DiagnosticOutcome.FAILED) }
        d.put(domain = DiagnosticDomain.JOB, packageId = "com.a", outcome = DiagnosticOutcome.REJECTED)
        d.put(domain = DiagnosticDomain.JOB, packageId = "com.b", outcome = DiagnosticOutcome.FAILED)
        d.put(domain = DiagnosticDomain.PHASE, packageId = "com.a", outcome = DiagnosticOutcome.FAILED)
        d.put(domain = DiagnosticDomain.PHASE, packageId = null, outcome = DiagnosticOutcome.FAILED)
        val s = d.failureSummary()
        assertEquals(5, s.size, "outcome / package / domain 任一不同都另起一组")
        val byKey = s.associate { Triple(it.domain, it.packageId, it.outcome) to it.count }
        assertEquals(2, byKey[Triple(DiagnosticDomain.JOB, "com.a", DiagnosticOutcome.FAILED)])
        assertEquals(1, byKey[Triple(DiagnosticDomain.JOB, "com.a", DiagnosticOutcome.REJECTED)])
        assertEquals(1, byKey[Triple(DiagnosticDomain.JOB, "com.b", DiagnosticOutcome.FAILED)])
        assertEquals(1, byKey[Triple(DiagnosticDomain.PHASE, "com.a", DiagnosticOutcome.FAILED)])
        assertEquals(1, byKey[Triple(DiagnosticDomain.PHASE, null, DiagnosticOutcome.FAILED)])
    }

    @Test
    fun `count降序且同数按key字典序`() {
        val d = RuntimeDiagnostics()
        repeat(3) { d.put(domain = DiagnosticDomain.EVENT, packageId = "z", outcome = DiagnosticOutcome.FAILED) }
        d.put(domain = DiagnosticDomain.JOB, packageId = null, outcome = DiagnosticOutcome.FAILED)
        d.put(domain = DiagnosticDomain.JOB, packageId = "a", outcome = DiagnosticOutcome.FAILED)
        d.put(domain = DiagnosticDomain.JOB, packageId = "a", outcome = DiagnosticOutcome.REJECTED)
        val s = d.failureSummary()
        // 先按 count：EVENT 组 3 条在最前；同数的三组按 key 串字典序）
        // JOB|<engine>|FAILED < JOB|a|FAILED < JOB|a|REJECTED
        assertEquals(
            listOf(
                Triple(DiagnosticDomain.EVENT, "z", DiagnosticOutcome.FAILED),
                Triple(DiagnosticDomain.JOB, null, DiagnosticOutcome.FAILED),
                Triple(DiagnosticDomain.JOB, "a", DiagnosticOutcome.FAILED),
                Triple(DiagnosticDomain.JOB, "a", DiagnosticOutcome.REJECTED),
            ),
            s.map { Triple(it.domain, it.packageId, it.outcome) },
        )
        assertEquals(listOf(3, 1, 1, 1), s.map { it.count })
    }

    @Test
    fun `摘要查询对snapshot与exportJsonLines零改写`() {
        val d = RuntimeDiagnostics()
        d.put(domain = DiagnosticDomain.JOB, packageId = "com.a", outcome = DiagnosticOutcome.FAILED)
        d.put(outcome = DiagnosticOutcome.OK)
        d.put(domain = DiagnosticDomain.PHASE, outcome = DiagnosticOutcome.REJECTED)
        val snapBefore = d.snapshot()
        val jsonBefore = d.exportJsonLines()
        repeat(3) { d.failureSummary() }
        assertEquals(snapBefore, d.snapshot(), "摘要查询不改环")
        assertEquals(jsonBefore, d.exportJsonLines(), "摘要查询不改导出（逐字节）")
        d.put(outcome = DiagnosticOutcome.FAILED)
        assertEquals(4L, d.snapshot().last().seq, "摘要查询不推进 seq")
    }
}
