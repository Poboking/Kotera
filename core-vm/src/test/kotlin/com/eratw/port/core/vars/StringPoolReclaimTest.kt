package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 运行期串表回收（[VariableStore.reclaimStringPoolIfOver]）：触发上界、迟滞、载体下标改写。
 *
 * 复位链那半边（[VariableStore.resetData] 里的压实）另有既有用例，本文件只管**运行期**这条：
 * 中后局一直往 STR 里写长串、拿不到复位点的会话（2026-11-25 真机 OOM 报告）。
 */
class StringPoolReclaimTest {

    private fun idx(vararg v: Long) = v

    private fun dims(body: String, isPrivate: Boolean = false) =
        DimParser.parse("#DIMS", body, isPrivate)

    @Test
    fun `未到上界不回收也不扫载体`() {
        val s = VariableStore()
        s.declareUserVar(dims("TMP, 4"))
        repeat(20) { i -> s.writeStr("TMP", idx((i % 4).toLong()), "短串$i") }
        val before = s.pool.charsTotal
        assertNull(s.reclaimStringPoolIfOver(1_000_000L), "远未到上界 ⇒ 直接返回，不扫载体")
        assertEquals(before, s.pool.charsTotal, "未触发时池不许有任何变化")
        assertEquals("短串19", s.readStr("TMP", idx(3)))
    }

    @Test
    fun `空池回收返回 null`() {
        // 池里只有空串：预算再小也收不出东西，读数缺席（调用方按「没发生」处理）。
        assertNull(VariableStore().reclaimStringPoolIfOver(0L))
    }

    @Test
    fun `超上界回收：无主串归还、活串逐字不变（用户 + 局部 + 角色三类载体）`() {
        val s = VariableStore()
        s.declareUserVar(dims("TMP, 2"))
        s.declareUserVar(dims("CHARADATA CS, 2"))
        s.addPseudoCharacter()
        // 活串先落：覆盖写只会骂到 CHURN 那两格，下面这批必须原样活过压实。
        s.writeStr("TMP", idx(0), "用户活串")
        s.writeStr("GLOBALS", idx(3), "系统活串")
        s.writeStr("CS", idx(0, 0), "角色活串")
        s.enterFunction("F")
        s.writeStr("LOCALS", idx(0), "局部活串")
        // 无主串：同一格反复覆盖 ⇒ 只剩最后一条有主（复位链用例同款手法）。
        s.declareUserVar(dims("CHURN, 2"))
        repeat(300) { i -> s.writeStr("CHURN", idx((i % 2).toLong()), "无主串$i") }

        val r = assertNotNull(s.reclaimStringPoolIfOver(0L), "越界必须真收回读数")
        assertTrue(r.freedEntries > 0, "无主条目应整体归还（条 $r.distinctBefore → $r.distinctAfter）")
        assertTrue(r.freedChars > 0, "无主串的载荷字符应归还（$r.freedChars）")
        assertEquals(r.distinctAfter, s.pool.distinctCount, "读数与池实际一致（条）")
        assertEquals(r.charsAfter, s.pool.charsTotal, "读数与池实际一致（字）")
        assertNull(s.pool.idOf("无主串0"), "无主条目连同反查项一起离开池")

        // 可观测语义：全部载体读回的串逐字不变（漏登记载体 = 读 -1 响 CodeEE，刻意失败方向）。
        assertEquals("用户活串", s.readStr("TMP", idx(0)))
        assertEquals("系统活串", s.readStr("GLOBALS", idx(3)))
        assertEquals("角色活串", s.readStr("CS", idx(0, 0)))
        assertEquals("局部活串", s.readStr("LOCALS", idx(0)))
        assertEquals("无主串299", s.readStr("CHURN", idx(1)))
        s.leaveFunction()
    }

    @Test
    fun `收后带迟滞：紧接着再调不重收，越过新触发点才收第二趟`() {
        val s = VariableStore()
        s.declareUserVar(dims("TMP, 2"))
        val live = "活".repeat(200)
        s.writeStr("TMP", idx(0), live)
        repeat(200) { i -> s.writeStr("TMP", idx(1), "无主串$i") }

        val kept = live.length + "无主串199".length // 活串 + 最后一格仍被 TMP[1] 持有的那条
        val first = assertNotNull(s.reclaimStringPoolIfOver(0L))
        assertEquals(kept.toLong(), first.charsAfter, "收后池里只剩仍被持有的 $kept 字")
        assertTrue(first.freedChars > 0, "199 条被覆盖掉的串必须归还")
        assertNull(s.reclaimStringPoolIfOver(0L), "迟滞：收后立刻再来一次不重收（收后触发点抬到 +25%）")

        // 灌到「收后字符 +25%」以下仍不触发（$kept → 触发点 ≈ $kept*1.25），越过去才触发。
        s.writeStr("TMP", idx(1), "填".repeat(10))
        assertNull(s.reclaimStringPoolIfOver(0L), "live+10 字 < 触发点 ⇒ 不重收")
        s.writeStr("TMP", idx(1), "灌".repeat(60))
        val second = assertNotNull(s.reclaimStringPoolIfOver(0L), "越过触发点必须再收")
        assertEquals(live.length + 60L, second.charsAfter, "第二趟收完只剩活串与新写的 60 字")
        assertEquals(live, s.readStr("TMP", idx(0)))
        assertEquals("灌".repeat(60), s.readStr("TMP", idx(1)))
    }

    @Test
    fun `回收后新写接在压实后的小下标且去重照旧`() {
        val s = VariableStore()
        s.declareUserVar(dims("TMP, 2"))
        s.writeStr("TMP", idx(0), "活串")
        repeat(200) { i -> s.writeStr("TMP", idx(1), "无主串$i") }
        val r = assertNotNull(s.reclaimStringPoolIfOver(0L))

        s.writeStr("TMP", idx(1), "回收后新串")
        val id = assertNotNull(s.pool.idOf("回收后新串"), "新写的串必须可反查")
        assertTrue(id < r.distinctAfter + 2, "新驻留下标接在存活项之后（$id vs 压实后 ${r.distinctAfter} 条）")
        assertEquals("回收后新串", s.readStr("TMP", idx(1)))
        s.writeStr("TMP", idx(1), "回收后新串")
        assertEquals(id, s.pool.idOf("回收后新串"), "同内容仍同一格（压实不破坏驻留去重）")
    }

    @Test
    fun `运行期回收不打断 REF 的数组本体绑定`() {
        val s = VariableStore()
        s.declareUserVar(DimParser.parse("#DIMS", "DYNAMIC PV, 2", true), "F")
        s.declareUserVar(DimParser.parse("#DIMS", "REF SR, 0", true), "F")
        s.enterFunction("F")
        val target = s.userVar("PV", "F")!!.rawArray() as IntArray
        target[0] = s.pool.intern("REF 看得见的串")
        (s.userVar("SR", "F") as RefUserVar).bindStr(target)
        repeat(200) { i -> s.writeStr("PV", idx(1), "无主 REF$i") }

        val r = assertNotNull(s.reclaimStringPoolIfOver(0L), "运行期上界同样要收")

        assertTrue(r.freedChars > 0)
        val ref = s.userVar("SR", "F") as RefUserVar
        assertSame(target, ref.targetStr, "REF 绑的是数组对象本体 ⇒ 压实只改元素（spec: ReferenceToken.cs:125-128）")
        assertEquals("REF 看得见的串", s.readStr("SR", idx(0)), "REF 目标的串经载体③改写后逐字不变")
        s.writeStr("SR", idx(0), "经 REF 写回")
        assertEquals("经 REF 写回", s.readStr("PV", idx(0)), "别名双向照旧")
        s.leaveFunction()
    }

    @Test
    fun `运行期回收改写静态串变量的 DEFAULT 下标（其后复位仍读得到原串）`() {
        val s = VariableStore()
        s.declareUserVar(dims("US, 3 = \"a\", \"b\""))
        assertEquals("a", s.readStr("US", idx(0)), "首次读触发 data + defaults 两份下标数组")
        repeat(300) { i -> s.writeStr("US", idx(0), "无主第二次$i") }

        assertNotNull(s.reclaimStringPoolIfOver(0L), "运行期回收收掉 300 条无主串")
        s.resetData()
        assertEquals("a", s.readStr("US", idx(0)), "defaults 没改写下标的话复位会抄回悬空值（CodeEE / 错串）")
        assertEquals("b", s.readStr("US", idx(1)))
    }

    @Test
    fun `条目上界独立触发：串短条多时按条数收，迟滞按条数算`() {
        val s = VariableStore()
        s.declareUserVar(dims("TMP, 2"))
        s.writeStr("TMP", idx(0), "活")
        repeat(400) { i -> s.writeStr("TMP", idx(1), "无主短串${i}") }
        val entries = s.pool.distinctCount
        val chars = s.pool.charsTotal
        // 字符预算故意给到远未触及，这条用例只让条目上界生效。
        assertNull(
            s.reclaimStringPoolIfOver(1_000_000L, entryBudget = entries + 1),
            "条目未到上界、字符远未到 ⇒ 不收",
        )
        assertEquals(chars, s.pool.charsTotal, "未触发时池不许有任何变化")

        val r = assertNotNull(
            s.reclaimStringPoolIfOver(1_000_000L, entryBudget = entries),
            "条目到上界必须收，哪怕字符预算还很宽",
        )
        assertTrue(r.freedEntries >= 398, "400 条无主短串应整体归还（条 ${r.distinctBefore} → ${r.distinctAfter}）")
        assertEquals("活", s.readStr("TMP", idx(0)))
        assertEquals("无主短串399", s.readStr("TMP", idx(1)))
        // 迟滞按条目算：收后条目跌到上界之下，紧接着再调不重收。
        assertNull(s.reclaimStringPoolIfOver(1_000_000L, entryBudget = entries), "收后条目远低于上界 ⇒ 不重收")
    }
}
