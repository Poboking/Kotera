package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EntityState
import com.eratw.port.modapi.create
import com.eratw.port.modapi.getInt
import com.eratw.port.modapi.getRef
import com.eratw.port.modapi.getStr
import com.eratw.port.modapi.query
import com.eratw.port.modapi.setInt
import com.eratw.port.modapi.setStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内存 [com.eratw.port.modapi.EntityApi]：读写往返、失败哨兵、销毁与查询序。 */
class MemoryEntityApiTest {

    private val world = TestSchema.world()

    @Test
    fun createAssignsAscendingIdsAndRoundTripsFields() {
        val first = world.entities.create(TestSchema.ARCHETYPE)
        val second = world.entities.create(TestSchema.ARCHETYPE)
        assertTrue(first.raw < second.raw, "create 必须按递增 EntityId 分配")
        assertTrue(world.entities.setInt(first, TestSchema.AGE, 7L))
        assertTrue(world.entities.setStr(first, TestSchema.NAME, "阿甲"))
        assertEquals(7L, world.entities.getInt(first, TestSchema.AGE))
        assertEquals("阿甲", world.entities.getStr(first, TestSchema.NAME))
        assertEquals(0L, world.entities.getInt(second, TestSchema.AGE), "不同实体的列互不影响")
    }

    @Test
    fun unknownFieldReturnsCleanSentinels() {
        val id = world.entities.create(TestSchema.ARCHETYPE)
        assertEquals(0L, world.entities.getInt(id, "no.such.field"))
        assertEquals("", world.entities.getStr(id, "no.such.field"))
        assertFalse(world.entities.setInt(id, "no.such.field", 1L))
        assertFalse(world.entities.setStr(id, "no.such.field", "x"))
    }

    @Test
    fun typeMismatchedAccessUsesSentinels() {
        val id = world.entities.create(TestSchema.ARCHETYPE)
        // AGE 是 INT 字段，按字符串读写必须走哨兵而非抛异常。
        assertEquals("", world.entities.getStr(id, TestSchema.AGE.name))
        assertFalse(world.entities.setStr(id, TestSchema.AGE.name, "x"))
    }

    @Test
    fun destroyRemovesEntityAndColumns() {
        val id = world.entities.create(TestSchema.ARCHETYPE)
        world.entities.setInt(id, TestSchema.AGE, 3L)
        assertTrue(world.entities.destroy(id))
        assertFalse(world.entities.exists(id))
        assertNull(world.entities.stateOf(id))
        assertEquals(0L, world.entities.getInt(id, TestSchema.AGE))
        assertFalse(world.entities.destroy(id), "重复 destroy 必须返回 false")
    }

    @Test
    fun createWithUndeclaredArchetypeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            world.entities.create("no.such.archetype")
        }
    }

    @Test
    fun queryFiltersByArchetypeAndStateInAscendingOrder() {
        val first = world.entities.create(TestSchema.ARCHETYPE)
        val second = world.entities.create(TestSchema.ARCHETYPE)
        val third = world.entities.create(TestSchema.ARCHETYPE)
        world.entities.setState(second, EntityState.RETIRED)
        assertEquals(
            listOf(first, third),
            world.entities.query(TestSchema.ARCHETYPE, EntityState.ACTIVE),
        )
        assertEquals(
            listOf(first, second, third),
            world.entities.query(TestSchema.ARCHETYPE),
        )
        assertEquals(listOf(second), world.entities.query(TestSchema.ARCHETYPE, EntityState.RETIRED))
    }

    @Test
    fun getRefDefaultsToNullEntityId() {
        val id = world.entities.create(TestSchema.ARCHETYPE)
        assertEquals(EntityId.NULL, world.entities.getRef(id, TestSchema.MENTOR))
    }
}
