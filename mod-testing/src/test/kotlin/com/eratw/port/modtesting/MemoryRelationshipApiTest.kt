package com.eratw.port.modtesting

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.add
import com.eratw.port.modapi.create
import com.eratw.port.modapi.has
import com.eratw.port.modapi.neighbors
import com.eratw.port.modapi.remove
import com.eratw.port.modapi.setValue
import com.eratw.port.modapi.valueOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 内存 [com.eratw.port.modapi.RelationshipApi]：自动反向边、去重、边值、失败哨兵、销毁级联。 */
class MemoryRelationshipApiTest {

    private val world = TestSchema.world()

    private fun newEntity(): EntityId = world.entities.create(TestSchema.ARCHETYPE)

    @Test
    fun addAndQueryDirectedEdge() {
        val from = newEntity()
        val to = newEntity()
        assertTrue(world.relationships.add(from, TestSchema.KNOWS, to))
        assertTrue(world.relationships.has(from, TestSchema.KNOWS, to))
        assertFalse(world.relationships.has(to, TestSchema.KNOWS, from), "有向关系反向不成立")
        assertEquals(0L, world.relationships.valueOf(from, TestSchema.KNOWS, to), "缺省边值为 0")
        assertTrue(world.relationships.setValue(from, TestSchema.KNOWS, to, 5L))
        assertEquals(5L, world.relationships.valueOf(from, TestSchema.KNOWS, to))
        assertEquals(listOf(to), world.relationships.neighbors(from, TestSchema.KNOWS))
        assertEquals(
            listOf(from),
            world.relationships.neighbors(to, TestSchema.KNOWS, outgoing = false),
        )
    }

    @Test
    fun setValueDoesNotCreateMissingEdge() {
        val from = newEntity()
        val to = newEntity()
        assertFalse(world.relationships.setValue(from, TestSchema.KNOWS, to, 9L))
        assertFalse(world.relationships.has(from, TestSchema.KNOWS, to))
        assertEquals(0L, world.relationships.valueOf(from, TestSchema.KNOWS, to))
    }

    @Test
    fun autoInverseRelationMirrorsEdge() {
        val mentor = newEntity()
        val junior = newEntity()
        assertTrue(world.relationships.add(mentor, TestSchema.MENTORS, junior))
        assertTrue(world.relationships.has(junior, TestSchema.MENTORED_BY, mentor))
        assertEquals(
            listOf(junior),
            world.relationships.neighbors(mentor, TestSchema.MENTORS),
        )
    }

    @Test
    fun duplicateAddIsRejectedWhenDedupeEnabled() {
        val from = newEntity()
        val to = newEntity()
        assertTrue(world.relationships.add(from, TestSchema.KNOWS, to))
        assertFalse(world.relationships.add(from, TestSchema.KNOWS, to))
    }

    @Test
    fun undirectedRelationMatchesEitherDirection() {
        val left = newEntity()
        val right = newEntity()
        assertTrue(world.relationships.add(left, TestSchema.MENTIONED, right))
        assertTrue(world.relationships.has(right, TestSchema.MENTIONED, left))
        assertEquals(
            listOf(left),
            world.relationships.neighbors(right, TestSchema.MENTIONED),
        )
    }

    @Test
    fun unregisteredKindIsRejected() {
        val from = newEntity()
        val to = newEntity()
        assertFalse(world.relationships.hasKind("no.such.kind"))
        assertFalse(world.relationships.add(from, "no.such.kind", to))
        assertEquals(emptyList(), world.relationships.neighbors(from, "no.such.kind", outgoing = true))
    }

    @Test
    fun addRejectsMissingEndpoint() {
        val from = newEntity()
        assertFalse(world.relationships.add(from, TestSchema.KNOWS, EntityId(9999L)))
    }

    @Test
    fun removeDropsEdgeAndMirror() {
        val mentor = newEntity()
        val junior = newEntity()
        world.relationships.add(mentor, TestSchema.MENTORS, junior)
        assertTrue(world.relationships.remove(mentor, TestSchema.MENTORS, junior))
        assertFalse(world.relationships.has(mentor, TestSchema.MENTORS, junior))
        assertFalse(world.relationships.has(junior, TestSchema.MENTORED_BY, mentor))
        assertFalse(world.relationships.remove(mentor, TestSchema.MENTORS, junior))
    }

    @Test
    fun neighborsAreSortedByEntityId() {
        val center = newEntity()
        val earlier = newEntity()
        val later = newEntity()
        // 插入序与 id 序相反：先建 later 的边、再建 earlier 的边。
        world.relationships.add(center, TestSchema.KNOWS, later)
        world.relationships.add(center, TestSchema.KNOWS, earlier)
        assertEquals(
            listOf(earlier, later),
            world.relationships.neighbors(center, TestSchema.KNOWS),
        )
    }

    @Test
    fun destroyCascadesEdgesOfDestroyedEntity() {
        val left = newEntity()
        val right = newEntity()
        world.relationships.add(left, TestSchema.KNOWS, right)
        assertTrue(world.entities.destroy(right))
        assertFalse(world.relationships.has(left, TestSchema.KNOWS, right))
        assertEquals(emptyList(), world.relationships.neighbors(left, TestSchema.KNOWS))
    }
}
