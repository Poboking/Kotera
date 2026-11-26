package com.eratw.port.modtesting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内存 [com.eratw.port.modapi.StorageApi]：缺键哨兵、覆盖写、键快照独立。 */
class MemoryStorageApiTest {

    private val world = TestSchema.world()

    @Test
    fun missingKeyReturnsNullAndRemoveReturnsFalse() {
        assertNull(world.storage.get("absent"))
        assertFalse(world.storage.remove("absent"))
        assertEquals(emptySet(), world.storage.keys())
    }

    @Test
    fun putGetOverwriteAndRemove() {
        world.storage.put("greeting", "hi")
        assertEquals("hi", world.storage.get("greeting"))
        world.storage.put("greeting", "hello")
        assertEquals("hello", world.storage.get("greeting"))
        assertTrue(world.storage.remove("greeting"))
        assertNull(world.storage.get("greeting"))
    }

    @Test
    fun keysIsIndependentSnapshot() {
        world.storage.put("a", "1")
        world.storage.put("b", "2")
        val snapshot = world.storage.keys()
        assertEquals(setOf("a", "b"), snapshot)
        world.storage.put("c", "3")
        assertEquals(setOf("a", "b"), snapshot, "keys() 返回的必须是独立快照")
        assertEquals(setOf("a", "b", "c"), world.storage.keys())
    }
}
