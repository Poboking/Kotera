package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityRefCompareOp
import com.eratw.port.modapi.EntityRefFieldCmp
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.HasRelation
import com.eratw.port.modapi.IntCompareOp
import com.eratw.port.modapi.IntFieldCmp
import com.eratw.port.modapi.add
import com.eratw.port.modapi.create
import com.eratw.port.modapi.query
import com.eratw.port.modapi.queryPlan
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setRef
import com.eratw.port.modapi.setStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 内存 [com.eratw.port.modapi.QueryApi]：archetype/state 过滤 + where 各类表达式。 */
class MemoryQueryApiTest {

    private val world = TestSchema.world()

    private fun newEntity(age: Long, name: String): EntityId {
        val id = world.entities.create(TestSchema.ARCHETYPE)
        world.entities.setInt(id, TestSchema.AGE, age)
        world.entities.setStr(id, TestSchema.NAME, name)
        return id
    }

    @Test
    fun filtersByStateAndIntField() {
        val young = newEntity(3L, "小")
        val old = newEntity(30L, "老")
        world.entities.setState(old, EntityState.INACTIVE)
        val plan = queryPlan {
            archetype(TestSchema.ARCHETYPE)
            state(EntityState.ACTIVE)
            where(IntFieldCmp(TestSchema.AGE, IntCompareOp.GE, 3L))
        }
        assertEquals(listOf(young), world.query.query(plan))
    }

    @Test
    fun filtersByRelation() {
        val mentor = newEntity(40L, "师")
        val junior = newEntity(10L, "徒")
        world.relationships.add(mentor, TestSchema.MENTORS, junior)
        val plan = queryPlan {
            archetype(TestSchema.ARCHETYPE)
            where(HasRelation(TestSchema.MENTORS, outgoing = true))
        }
        assertEquals(listOf(mentor), world.query.query(plan))
    }

    @Test
    fun entityRefAliveOnlyTreatsDanglingReferenceAsNull() {
        val holder = newEntity(20L, "持有者")
        val doomed = newEntity(1L, "将被删")
        world.entities.setRef(holder, TestSchema.MENTOR, doomed)
        world.entities.destroy(doomed)
        val aliveOnly = queryPlan {
            archetype(TestSchema.ARCHETYPE)
            where(EntityRefFieldCmp(TestSchema.MENTOR, EntityRefCompareOp.EQ, EntityId.NULL, aliveOnly = true))
        }
        assertTrue(holder in world.query.query(aliveOnly))
        val raw = queryPlan {
            archetype(TestSchema.ARCHETYPE)
            where(EntityRefFieldCmp(TestSchema.MENTOR, EntityRefCompareOp.EQ, EntityId.NULL))
        }
        // 不做存活投影时，悬空引用原值 ≠ NULL，不应命中。
        assertEquals(false, holder in world.query.query(raw))
    }

    @Test
    fun `aliveOnly不得归一查询目标other的悬空原值`() {
        val holder = newEntity(20L, "持有者")
        val doomed = newEntity(1L, "将被删")
        world.entities.setRef(holder, TestSchema.MENTOR, doomed)
        world.entities.destroy(doomed) // doomed 成为悬空 ID

        // 左侧字段读数归一为 0，右侧 other.raw 保留悬空原值：EQ 不应命中（修复前两侧皆 0 → 假阳性）。
        val eq = queryPlan {
            archetype(TestSchema.ARCHETYPE)
            where(EntityRefFieldCmp(TestSchema.MENTOR, EntityRefCompareOp.EQ, EntityId(doomed.raw), aliveOnly = true))
        }
        assertTrue(world.query.query(eq).isEmpty(), "左侧归一为 0、右侧保留悬空原值，EQ 不应命中")

        // NE 应命中（修复前两侧皆 0 → 假阴性）。
        val ne = queryPlan {
            archetype(TestSchema.ARCHETYPE)
            where(EntityRefFieldCmp(TestSchema.MENTOR, EntityRefCompareOp.NE, EntityId(doomed.raw), aliveOnly = true))
        }
        assertEquals(listOf(holder), world.query.query(ne), "左侧归一为 0、右侧保留悬空原值，NE 应命中")
    }

    @Test
    fun resultsAreAscendingEntityIds() {
        val first = newEntity(1L, "a")
        val second = newEntity(2L, "b")
        val plan = queryPlan { archetype(TestSchema.ARCHETYPE) }
        assertEquals(listOf(first, second), world.query.query(plan))
    }
}
