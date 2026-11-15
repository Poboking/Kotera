package com.eratw.port.modapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * :mod-api 查询 facade 契约形状闸（M7-1，era-arch-v61u.md §21.5）。
 *
 * 本模块零 core 依赖，闸只验 facade 侧**有逻辑**的面：比较符枚举顺序、各表达式的非空/同型
 * 嵌套校验、[QueryPlan] 默认值与候选集来源必填、builder 摊平与直接构造等价、[QueryApi]
 * 可实现性。执行语义（升序、快照、aliveOnly）由 :core-vm 侧 QueryPlanTest 咬住。
 */
class QueryApiContractTest {

    @Test
    fun `比较符枚举顺序即契约序`() {
        assertEquals(listOf("EQ", "NE", "LT", "LE", "GT", "GE"), IntCompareOp.entries.map { it.name })
        assertEquals(listOf("EQ", "NE"), StrCompareOp.entries.map { it.name })
        assertEquals(listOf("EQ", "NE"), EntityRefCompareOp.entries.map { it.name })
    }

    @Test
    fun `逻辑组合非空且禁同型直接嵌套`() {
        val leaf = IntFieldCmp("level", IntCompareOp.GE, 0)
        assertFailsWith<IllegalArgumentException> { AndExpr(emptyList()) }
        assertFailsWith<IllegalArgumentException> { OrExpr(emptyList()) }
        assertFailsWith<IllegalArgumentException> { AndExpr(listOf(AndExpr(listOf(leaf)))) }
        assertFailsWith<IllegalArgumentException> { OrExpr(listOf(OrExpr(listOf(leaf)))) }
        // 不同型嵌套合法（And 里放 Or、Or 里放 And）。
        AndExpr(listOf(OrExpr(listOf(leaf))))
        OrExpr(listOf(AndExpr(listOf(leaf))))
    }

    @Test
    fun `原子谓词字段名与关系名非空`() {
        assertFailsWith<IllegalArgumentException> { IntFieldCmp("", IntCompareOp.EQ, 0) }
        assertFailsWith<IllegalArgumentException> { IntFieldCmp("  ", IntCompareOp.EQ, 0) }
        assertFailsWith<IllegalArgumentException> { StrFieldCmp("", StrCompareOp.EQ, "v") }
        assertFailsWith<IllegalArgumentException> { EntityRefFieldCmp("", EntityRefCompareOp.EQ, EntityId.NULL) }
        assertFailsWith<IllegalArgumentException> { HasRelation("", outgoing = true) }
        assertFailsWith<IllegalArgumentException> { HasRelation(" ", outgoing = false, other = EntityId(7)) }
        // 空串是合法字段值；EntityId.NULL 是合法比较目标（查「引用为空」）。
        val cmp = StrFieldCmp("name", StrCompareOp.EQ, "")
        assertEquals("", cmp.value)
        val refCmp = EntityRefFieldCmp("friend", EntityRefCompareOp.NE, EntityId.NULL)
        assertEquals(EntityId.NULL, refCmp.other)
        assertEquals(false, refCmp.aliveOnly)
    }

    @Test
    fun `QueryPlan默认值与非空原型校验`() {
        val plan = QueryPlan(archetypes = setOf("PERSON"))
        assertTrue(plan.states.isEmpty(), "states 缺省 = 不限状态")
        assertNull(plan.where, "where 缺省 = 只按原型/状态过滤")
        assertNull(QueryPlan(archetypes = setOf("PERSON"), states = setOf(EntityState.ACTIVE)).where)
        assertFailsWith<IllegalArgumentException> { QueryPlan(archetypes = emptySet()) }
        assertFailsWith<IllegalArgumentException> { QueryPlan(archetypes = setOf("")) }
        assertFailsWith<IllegalArgumentException> { QueryPlan(archetypes = setOf("PERSON", " ")) }
    }

    @Test
    fun `builder摊平And且单谓词不包壳`() {
        val a = IntFieldCmp("level", IntCompareOp.GE, 1)
        val b = StrFieldCmp("name", StrCompareOp.EQ, "x")
        val c = HasRelation("KNOWS", outgoing = true)
        val built = queryPlan {
            archetype("PERSON")
            archetype("PERSON")
            state(EntityState.ACTIVE)
            where(AndExpr(listOf(a, b)))
            where(c)
        }
        val expected = QueryPlan(
            archetypes = setOf("PERSON"),
            states = setOf(EntityState.ACTIVE),
            where = AndExpr(listOf(a, b, c)),
        )
        assertEquals(expected, built)
        assertEquals(a, queryPlan { archetype("PERSON"); where(a) }.where)
        assertNull(queryPlan { archetype("PERSON") }.where)
    }

    @Test
    fun `builder拒绝空原型与空名`() {
        assertFailsWith<IllegalArgumentException> { queryPlan { state(EntityState.ACTIVE) } }
        assertFailsWith<IllegalArgumentException> { queryPlan { archetype("") } }
        assertFailsWith<IllegalArgumentException> { queryPlan { archetype("  ") } }
    }

    @Test
    fun `QueryApi可实现且原样收到计划`() {
        val seen = ArrayList<QueryPlan>()
        val api = object : QueryApi {
            override fun query(plan: QueryPlan): List<EntityId> {
                seen += plan
                return plan.archetypes.sorted().mapIndexed { index, _ -> EntityId((index + 1).toLong()) }
            }
        }
        val plan = queryPlan { archetype("THING"); archetype("PERSON") }
        val result = api.query(plan)
        assertSame(plan, seen.single(), "facade 必须把计划原样交给实现")
        assertEquals(listOf(EntityId(1), EntityId(2)), result)
        assertTrue(result.all { !it.isNull })
    }
}