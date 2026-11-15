package com.eratw.port.core.entity

import com.eratw.port.modapi.AndExpr
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityRefCompareOp
import com.eratw.port.modapi.EntityRefFieldCmp
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.HasRelation
import com.eratw.port.modapi.IntCompareOp
import com.eratw.port.modapi.IntFieldCmp
import com.eratw.port.modapi.OrExpr
import com.eratw.port.modapi.QueryPlan
import com.eratw.port.modapi.StrCompareOp
import com.eratw.port.modapi.StrFieldCmp
import com.eratw.port.modapi.queryPlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueryPlanExecutor] 的判据（M7-1，era-arch-v61u.md §21.5）。
 *
 * 断言面：候选集来源（单/多 archetype、states 三态）、六种 int 比较、str EQ/NE、EntityRef
 * EQ/NE 与 aliveOnly 悬空归一、关系存在性（出/入/指定对端/省略对端）、And/Or 组合、
 * EntityId 数值升序（不是内核稠密槽序）、候选集快照隔离（谓词求值期间零 query 调用）、
 * 返回独立副本、未注册原型异常原样传播、构建期 fail-closed、[KernelQueryApi] 顺序一致、
 * 查询零副作用（前后逐值对拍）。
 *
 * 测试装置：真实 [EntityKernel]（不 mock 内核语义）+ 一个只计数/注入副作用的委托包装
 * （[CountingKernel]），后者专用于快照与副作用面。
 */
class QueryPlanTest {

    private fun kernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                "pkg",
                "Profile",
                listOf(
                    ComponentFieldSpec("level"),
                    ComponentFieldSpec("name", ComponentFieldType.STR),
                    ComponentFieldSpec("friend", isEntityRef = true),
                ),
            ),
        ),
        relationshipSchemas = listOf(
            RelationshipSchema("KNOWS"),
            RelationshipSchema("BOND", directed = false),
        ),
        archetypes = listOf(
            EntityArchetype("PERSON", listOf("Profile")),
            EntityArchetype("THING", listOf("Profile")),
        ),
        initialCapacity = 8,
    )

    /** 只计数 [IEntityKernel.query] 调用、并可在 [getInt] 首次求值时注入副作用的委托包装。 */
    private class CountingKernel(private val delegate: IEntityKernel) : IEntityKernel by delegate {
        var queryCalls: Int = 0
            private set
        var onFirstGetInt: (() -> Unit)? = null

        override fun query(archetype: String, state: Int): LongArray {
            queryCalls++
            return delegate.query(archetype, state)
        }

        override fun getInt(id: Long, field: String): Long {
            onFirstGetInt?.let {
                onFirstGetInt = null
                it()
            }
            return delegate.getInt(id, field)
        }
    }

    private fun execute(k: IEntityKernel, plan: QueryPlan): List<Long> =
        QueryPlanExecutor.execute(k, plan).toList()

    // ------------------------------------------------------------------
    // 1. 候选集：单原型 / 多原型 / states
    // ------------------------------------------------------------------

    @Test
    fun `单原型无条件返回全量且按数值升序`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val c = k.create(1, "PERSON")
        val plan = queryPlan { archetype("PERSON") }
        val result = QueryPlanExecutor.execute(k, plan)
        assertContentEquals(longArrayOf(a, b, c).sortedArray(), result)
    }

    @Test
    fun `多原型并集去重且按EntityId跨包数值升序而非槽序`() {
        val k = kernel()
        // 先建 pkg2（槽 0），再建 pkg1（槽 1）：内核 query 的稠密槽序 = [pkg2, pkg1]，
        // 而 EntityId 数值序 = [pkg1, pkg2]（pkgShortId 在高位段）。执行器必须自己排序。
        val pkg2First = k.create(2, "PERSON")
        val pkg1Second = k.create(1, "THING")
        val pkg2Second = k.create(2, "THING")
        assertTrue(pkg2First > pkg1Second, "前置：跨包时槽序与数值序必须相反")

        val plan = queryPlan {
            archetype("PERSON")
            archetype("THING")
        }
        val result = QueryPlanExecutor.execute(k, plan)
        val expected = longArrayOf(pkg1Second, pkg2First, pkg2Second).sortedArray()
        assertContentEquals(expected, result)
        // 反证：内核原样拼接的槽序与本结果不同（证明排序确实发生）。
        val rawSlotOrder = k.query("PERSON", 0) + k.query("THING", 0)
        assertFalse(rawSlotOrder.contentEquals(result), "结果不得是内核稠密槽序原样输出")
        // 去重：重复声明同一 archetype 不影响结果条数。
        val dedup = QueryPlanExecutor.execute(k, queryPlan { archetype("THING"); archetype("THING") })
        assertContentEquals(longArrayOf(pkg1Second, pkg2Second).sortedArray(), dedup)
    }

    @Test
    fun `states单状态过滤`() {
        val k = kernel()
        val active = k.create(1, "PERSON")
        val inactive = k.create(1, "PERSON")
        val retired = k.create(1, "PERSON")
        k.setState(inactive, EntityLifecycle.INACTIVE)
        k.setState(retired, EntityLifecycle.RETIRED)
        val result = execute(k, queryPlan { archetype("PERSON"); state(EntityState.ACTIVE) })
        assertEquals(listOf(active), result)
    }

    @Test
    fun `states多状态过滤`() {
        val k = kernel()
        val active = k.create(1, "PERSON")
        val inactive = k.create(1, "PERSON")
        val retired = k.create(1, "PERSON")
        k.setState(inactive, EntityLifecycle.INACTIVE)
        k.setState(retired, EntityLifecycle.RETIRED)
        val result = execute(
            k,
            queryPlan {
                archetype("PERSON")
                state(EntityState.RETIRED)
                state(EntityState.INACTIVE)
            },
        )
        assertContentEquals(longArrayOf(inactive, retired).sortedArray(), result.toLongArray())
        assertFalse(result.contains(active))
    }

    @Test
    fun `states为空表示不限状态`() {
        val k = kernel()
        val active = k.create(1, "PERSON")
        val inactive = k.create(1, "PERSON")
        val retired = k.create(1, "PERSON")
        k.setState(inactive, EntityLifecycle.INACTIVE)
        k.setState(retired, EntityLifecycle.RETIRED)
        val plan = queryPlan { archetype("PERSON") }
        assertTrue(plan.states.isEmpty())
        assertEquals(3, QueryPlanExecutor.execute(k, plan).size)
        assertTrue(active != inactive && inactive != retired)
    }

    // ------------------------------------------------------------------
    // 2. 字段谓词：int / str / entity-ref
    // ------------------------------------------------------------------

    @Test
    fun `Int六种比较符逐一钉住且含边界相等`() {
        val k = kernel()
        val lo = k.create(1, "PERSON")
        val mid = k.create(1, "PERSON")
        val hi = k.create(1, "PERSON")
        k.setInt(lo, "level", 10)
        k.setInt(mid, "level", 20)
        k.setInt(hi, "level", 30)

        fun run(op: IntCompareOp, v: Long): List<Long> =
            execute(k, queryPlan { archetype("PERSON"); where(IntFieldCmp("level", op, v)) })

        assertEquals(listOf(mid), run(IntCompareOp.EQ, 20))
        assertEquals(listOf(lo, hi), run(IntCompareOp.NE, 20))
        assertEquals(listOf(lo), run(IntCompareOp.LT, 20))
        assertEquals(listOf(lo, mid), run(IntCompareOp.LE, 20))
        assertEquals(listOf(hi), run(IntCompareOp.GT, 20))
        assertEquals(listOf(mid, hi), run(IntCompareOp.GE, 20))
    }

    @Test
    fun `未注册字段按内核口径读零`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        k.setInt(a, "level", 7)
        // QueryPlan 不校验 schema：未注册字段读 0（§21.5 诚实边界，fail-closed 归内核声明事务）。
        val zero = execute(k, queryPlan { archetype("PERSON"); where(IntFieldCmp("no_such", IntCompareOp.EQ, 0)) })
        assertEquals(listOf(a, b), zero)
        val seven = execute(k, queryPlan { archetype("PERSON"); where(IntFieldCmp("no_such", IntCompareOp.EQ, 7)) })
        assertTrue(seven.isEmpty())
    }

    @Test
    fun `Str相等与不等含空串默认`() {
        val k = kernel()
        val alice = k.create(1, "PERSON")
        val bob = k.create(1, "PERSON")
        val unnamed = k.create(1, "PERSON")
        k.setStr(alice, "name", "alice")
        k.setStr(bob, "name", "bob")

        val eq = execute(k, queryPlan { archetype("PERSON"); where(StrFieldCmp("name", StrCompareOp.EQ, "alice")) })
        assertEquals(listOf(alice), eq)
        val ne = execute(k, queryPlan { archetype("PERSON"); where(StrFieldCmp("name", StrCompareOp.NE, "alice")) })
        assertContentEquals(longArrayOf(bob, unnamed).sortedArray(), ne.toLongArray())
        // 未写字段读空串：EQ 空串命中「从未赋值」的实体。
        val blank = execute(k, queryPlan { archetype("PERSON"); where(StrFieldCmp("name", StrCompareOp.EQ, "")) })
        assertEquals(listOf(unnamed), blank)
    }

    @Test
    fun `EntityRef的aliveOnly把悬空引用当NULL`() {
        val k = kernel()
        val live = k.create(1, "PERSON")
        val holder = k.create(1, "PERSON")
        k.setInt(holder, "friend", live)
        val danglingOwner = k.create(1, "PERSON")
        val dead = k.create(1, "PERSON")
        k.setInt(danglingOwner, "friend", dead)
        assertTrue(k.destroy(dead))
        assertFalse(k.exists(dead))
        val nullRef = k.create(1, "PERSON")
        assertEquals(dead, k.getInt(danglingOwner, "friend"), "前置：悬空值仍留在字段里且非 0")

        val aliveOnlyNull = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(EntityRefFieldCmp("friend", EntityRefCompareOp.EQ, EntityId.NULL, aliveOnly = true))
            },
        )
        assertContentEquals(longArrayOf(live, danglingOwner, nullRef).sortedArray(), aliveOnlyNull.toLongArray())
        assertFalse(aliveOnlyNull.contains(holder), "活引用不等于 NULL")

        // 同一谓词用 aliveOnly=false：悬空原值非 0，不命中 NULL（对照组）。
        val rawNull = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(EntityRefFieldCmp("friend", EntityRefCompareOp.EQ, EntityId.NULL, aliveOnly = false))
            },
        )
        assertEquals(listOf(live, nullRef), rawNull)

        val aliveOnlyLive = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(EntityRefFieldCmp("friend", EntityRefCompareOp.EQ, EntityId(live), aliveOnly = true))
            },
        )
        assertEquals(listOf(holder), aliveOnlyLive)
    }

    @Test
    fun `EntityRef的aliveOnly为假按存储原值比较`() {
        val k = kernel()
        val owner = k.create(1, "PERSON")
        val dead = k.create(1, "PERSON")
        k.setInt(owner, "friend", dead)
        assertTrue(k.destroy(dead))
        val raw = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(EntityRefFieldCmp("friend", EntityRefCompareOp.EQ, EntityId(dead), aliveOnly = false))
            },
        )
        assertEquals(listOf(owner), raw)
        // 原值模式 NE NULL 命中（引用非 0）；aliveOnly 模式 EQ 原悬空值不再命中。
        val ne = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(EntityRefFieldCmp("friend", EntityRefCompareOp.NE, EntityId.NULL, aliveOnly = false))
            },
        )
        assertTrue(ne.contains(owner))
        val aliveOnlyEqDead = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(EntityRefFieldCmp("friend", EntityRefCompareOp.EQ, EntityId(dead), aliveOnly = true))
            },
        )
        assertTrue(aliveOnlyEqDead.isEmpty(), "aliveOnly 把悬空当 NULL 后不再等于原悬空值")
    }

    // ------------------------------------------------------------------
    // 3. 关系谓词
    // ------------------------------------------------------------------

    @Test
    fun `HasRelation出边入边与指定对端`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val c = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "KNOWS", b))
        assertTrue(k.addRelation(c, "KNOWS", a))

        val outToB = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(HasRelation("KNOWS", outgoing = true, other = EntityId(b)))
            },
        )
        assertEquals(listOf(a), outToB)
        val intoA = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(HasRelation("KNOWS", outgoing = false, other = EntityId(c)))
            },
        )
        assertEquals(listOf(a), intoA)
        val noEdge = execute(
            k,
            queryPlan {
                archetype("PERSON")
                where(HasRelation("KNOWS", outgoing = true, other = EntityId(c)))
            },
        )
        assertTrue(noEdge.isEmpty())
    }

    @Test
    fun `HasRelation省略对端查任一邻居`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val c = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "KNOWS", b))
        assertTrue(k.addRelation(c, "KNOWS", a))
        val outgoingAny = execute(k, queryPlan { archetype("PERSON"); where(HasRelation("KNOWS", outgoing = true)) })
        assertContentEquals(longArrayOf(a, c).sortedArray(), outgoingAny.toLongArray())
        val incomingAny = execute(k, queryPlan { archetype("PERSON"); where(HasRelation("KNOWS", outgoing = false)) })
        assertEquals(listOf(a, b), incomingAny)
        val otherKind = execute(k, queryPlan { archetype("PERSON"); where(HasRelation("BOND", outgoing = true)) })
        assertTrue(otherKind.isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. 组合谓词
    // ------------------------------------------------------------------

    @Test
    fun `And与Or嵌套组合`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val c = k.create(1, "PERSON")
        k.setInt(a, "level", 5)
        k.setInt(b, "level", 50)
        k.setInt(c, "level", 500)
        k.setStr(a, "name", "x")
        k.setStr(c, "name", "x")
        // (level < 10 OR level > 100) AND name == x  => a, c
        val plan = queryPlan {
            archetype("PERSON")
            where(
                AndExpr(
                    listOf(
                        OrExpr(
                            listOf(
                                IntFieldCmp("level", IntCompareOp.LT, 10),
                                IntFieldCmp("level", IntCompareOp.GT, 100),
                            ),
                        ),
                        StrFieldCmp("name", StrCompareOp.EQ, "x"),
                    ),
                ),
            )
        }
        assertContentEquals(longArrayOf(a, c).sortedArray(), QueryPlanExecutor.execute(k, plan))
        // OR 里嵌 AND：不同型嵌套合法（只禁止同型直接嵌套）。
        val orOfAnd = queryPlan {
            archetype("PERSON")
            where(
                OrExpr(
                    listOf(
                        AndExpr(listOf(IntFieldCmp("level", IntCompareOp.EQ, 5), StrFieldCmp("name", StrCompareOp.EQ, "x"))),
                        IntFieldCmp("level", IntCompareOp.GE, 500),
                    ),
                ),
            )
        }
        assertContentEquals(longArrayOf(a, c).sortedArray(), QueryPlanExecutor.execute(k, orOfAnd))
        assertFalse(QueryPlanExecutor.execute(k, orOfAnd).contains(b))
    }

    @Test
    fun `where为null全通过`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val plan = QueryPlan(archetypes = setOf("PERSON"), states = emptySet(), where = null)
        assertContentEquals(longArrayOf(a, b).sortedArray(), QueryPlanExecutor.execute(k, plan))
    }

    // ------------------------------------------------------------------
    // 5. 快照隔离 / 副本 / 无副作用
    // ------------------------------------------------------------------

    @Test
    fun `快照隔离谓词求值期间不再调用query`() {
        val k = CountingKernel(kernel())
        k.create(1, "PERSON")
        k.create(1, "PERSON")
        k.create(1, "THING")
        val plan = queryPlan {
            archetype("PERSON")
            archetype("THING")
            state(EntityState.ACTIVE)
            state(EntityState.INACTIVE)
            where(IntFieldCmp("level", IntCompareOp.GE, 0))
        }
        val result = QueryPlanExecutor.execute(k, plan)
        assertEquals(3, result.size)
        // archetype x state = 2 x 2 = 4 次 query；谓词求值期间一次都不许多调。
        assertEquals(4, k.queryCalls)
    }

    @Test
    fun `谓词求值中新建的实体不进本轮候选`() {
        val delegate = kernel()
        val existing = delegate.create(1, "PERSON")
        val k = CountingKernel(delegate)
        var createdMidFlight = EntityIdCodec.NULL
        k.onFirstGetInt = {
            createdMidFlight = delegate.create(1, "PERSON")
        }
        val plan = queryPlan { archetype("PERSON"); where(IntFieldCmp("level", IntCompareOp.GE, 0)) }
        val result = QueryPlanExecutor.execute(k, plan).toList()
        assertEquals(listOf(existing), result)
        assertFalse(result.contains(createdMidFlight), "候选集必须在谓词求值前快照")
        assertEquals(1, k.queryCalls, "求值期间不得重新查询候选集")
    }

    @Test
    fun `返回独立副本且执行后改内核不影响结果`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val plan = queryPlan { archetype("PERSON") }
        val first = QueryPlanExecutor.execute(k, plan)
        val second = QueryPlanExecutor.execute(k, plan)
        assertFalse(first === second, "每次执行必须返回新数组（不得暴露内核内部数组）")
        assertContentEquals(first, second)
        // 执行后改内核：已返回数组逐位不变。
        assertTrue(k.destroy(a))
        assertTrue(k.destroy(b))
        assertContentEquals(longArrayOf(a, b).sortedArray(), first)
        assertTrue(QueryPlanExecutor.execute(k, plan).isEmpty())
    }

    @Test
    fun `查询无副作用前后逐值对拍`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "THING")
        k.setInt(a, "level", 3)
        k.setStr(a, "name", "alice")
        k.setInt(b, "level", 4)
        k.setInt(a, "friend", b)
        assertTrue(k.addRelation(a, "KNOWS", b))
        assertTrue(k.addRelation(b, "BOND", a))
        val intFields = listOf("level", "friend")
        val strFields = listOf("name")
        val kinds = listOf("KNOWS", "BOND")
        val before = dump(k, listOf(a, b), intFields, strFields, kinds)
        QueryPlanExecutor.execute(
            k,
            queryPlan {
                archetype("PERSON")
                archetype("THING")
                where(
                    AndExpr(
                        listOf(
                            IntFieldCmp("level", IntCompareOp.GE, 0),
                            HasRelation("KNOWS", outgoing = true),
                        ),
                    ),
                )
            },
        )
        val after = dump(k, listOf(a, b), intFields, strFields, kinds)
        assertEquals(before, after, "查询不得改字段/状态/关系")
        assertEquals(2, k.liveCount)
    }

    private fun dump(
        k: IEntityKernel,
        ids: List<Long>,
        intFields: List<String>,
        strFields: List<String>,
        kinds: List<String>,
    ): String = ids.joinToString(separator = "|") { id ->
        val sb = StringBuilder()
        sb.append(id).append(":st=").append(k.stateOf(id))
        for (f in intFields) sb.append(":i.").append(f).append("=").append(k.getInt(id, f))
        for (f in strFields) sb.append(":s.").append(f).append("=").append(k.getStr(id, f))
        for (kind in kinds) {
            sb.append(":out.").append(kind).append("=").append(k.queryRelations(id, kind, true).joinToString(separator = "+"))
            sb.append(":in.").append(kind).append("=").append(k.queryRelations(id, kind, false).joinToString(separator = "+"))
        }
        sb.toString()
    }

    // ------------------------------------------------------------------
    // 6. 错误传播与构建期 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `未注册原型异常原样传播`() {
        val k = kernel()
        k.create(1, "PERSON")
        val plan = queryPlan { archetype("NO_SUCH_ARCHETYPE") }
        val ex = assertFailsWith<IllegalArgumentException> { QueryPlanExecutor.execute(k, plan) }
        assertTrue(ex.message.orEmpty().contains("原型未注册"))
    }

    @Test
    fun `构建期拒绝非法形状`() {
        assertFailsWith<IllegalArgumentException> { QueryPlan(archetypes = emptySet()) }
        assertFailsWith<IllegalArgumentException> { QueryPlan(archetypes = setOf(" ")) }
        assertFailsWith<IllegalArgumentException> { AndExpr(emptyList()) }
        assertFailsWith<IllegalArgumentException> { OrExpr(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            AndExpr(listOf(AndExpr(listOf(IntFieldCmp("level", IntCompareOp.EQ, 1)))))
        }
        assertFailsWith<IllegalArgumentException> {
            OrExpr(listOf(OrExpr(listOf(IntFieldCmp("level", IntCompareOp.EQ, 1)))))
        }
        assertFailsWith<IllegalArgumentException> { IntFieldCmp("", IntCompareOp.EQ, 0) }
        assertFailsWith<IllegalArgumentException> { StrFieldCmp(" ", StrCompareOp.EQ, "x") }
        assertFailsWith<IllegalArgumentException> { EntityRefFieldCmp("", EntityRefCompareOp.EQ, EntityId.NULL) }
        assertFailsWith<IllegalArgumentException> { HasRelation("", outgoing = true) }
        // 空串值合法（字段值可以是空串）：不得误拒。
        StrFieldCmp("name", StrCompareOp.EQ, "")
    }

    @Test
    fun `builder与直接构造等价且摊平And`() {
        val a = IntFieldCmp("level", IntCompareOp.GE, 1)
        val b = StrFieldCmp("name", StrCompareOp.EQ, "x")
        val c = HasRelation("KNOWS", outgoing = true)
        val built = queryPlan {
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
        // 单谓词不额外包一层 And。
        val single = queryPlan { archetype("PERSON"); where(a) }
        assertEquals(a, single.where)
        // 无 where = null。
        assertEquals(null, queryPlan { archetype("PERSON") }.where)
        // 无 archetype 构建期拒绝。
        assertFailsWith<IllegalArgumentException> { queryPlan { state(EntityState.ACTIVE) } }
    }

    // ------------------------------------------------------------------
    // 7. facade
    // ------------------------------------------------------------------

    @Test
    fun `KernelQueryApi返回List且顺序与execute一致`() {
        val k = kernel()
        val pkg2 = k.create(2, "PERSON")
        val pkg1 = k.create(1, "PERSON")
        val plan = queryPlan { archetype("PERSON") }
        val raw = QueryPlanExecutor.execute(k, plan)
        val viaApi = KernelQueryApi(k).query(plan)
        assertContentEquals(raw.toList(), viaApi.map { it.raw })
        assertContentEquals(longArrayOf(pkg1, pkg2).sortedArray(), raw)
        assertEquals(2, viaApi.size)
    }
}
