package com.eratw.port.core.mod

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.modapi.EntityState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K1 原型闸：EntityApi/RelationshipApi → IEntityKernel 缝可用（era-arch-v61u.md §18.3/§18.8）。
 * 证 mod 经 mod-api 干净类型（EntityId/EntityState）操作真实体内核：创建/查询/字段读写/三态/
 * 关系边都穿过适配器落到 SoA 内核，失败哨兵翻成 mod 侧干净口径（null/0/""/false）。
 */
class ModEntityApiPrototypeTest {

    private fun newApi(): KernelEntityApi {
        val kernel = EntityKernel(
            schemas = listOf(
                ComponentSchema(
                    packageId = "demo.mod",
                    name = "Stats",
                    fields = listOf(
                        ComponentFieldSpec("hp", ComponentFieldType.INT),
                        ComponentFieldSpec("name", ComponentFieldType.STR),
                    ),
                ),
            ),
            relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
            archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
        )
        return KernelEntityApi(kernel, pkgShortId = 1)
    }

    @Test
    fun `创建查询与三态映射`() {
        val api = newApi()
        val id = api.create("Child")
        assertFalse(id.isNull)
        assertTrue(api.exists(id))
        assertEquals(EntityState.ACTIVE, api.stateOf(id))
        assertEquals(listOf(id), api.query("Child"))
        assertEquals(listOf(id), api.query("Child", EntityState.ACTIVE))
        assertTrue(api.query("Child", EntityState.RETIRED).isEmpty())
    }

    @Test
    fun `组件字段读写穿过适配器`() {
        val api = newApi()
        val id = api.create("Child")
        assertTrue(api.setInt(id, "hp", 42L))
        assertEquals(42L, api.getInt(id, "hp"))
        assertTrue(api.setStr(id, "name", "蕾米"))
        assertEquals("蕾米", api.getStr(id, "name"))
        // 未注册字段：干净失败哨兵（int 0 / str "" / set false）。
        assertEquals(0L, api.getInt(id, "missing"))
        assertEquals("", api.getStr(id, "missing"))
        assertFalse(api.setInt(id, "missing", 1L))
    }

    @Test
    fun `三态切换与销毁`() {
        val api = newApi()
        val id = api.create("Child")
        assertTrue(api.setState(id, EntityState.INACTIVE))
        assertEquals(EntityState.INACTIVE, api.stateOf(id))
        assertTrue(api.setState(id, EntityState.RETIRED))
        assertEquals(EntityState.RETIRED, api.stateOf(id))
        assertTrue(api.exists(id)) // RETIRED 仍算存在
        assertTrue(api.destroy(id))
        assertFalse(api.exists(id))
        assertNull(api.stateOf(id)) // 不存在 → null（干净口径，不暴露 0 哨兵）
        assertFalse(api.destroy(id))
    }

    @Test
    fun `关系边与边值穿过适配器`() {
        val api = newApi()
        val a = api.create("Child")
        val b = api.create("Child")
        assertTrue(api.hasKind("PARENT"))
        assertFalse(api.hasKind("UNKNOWN"))
        assertTrue(api.add(a, "PARENT", b))
        assertTrue(api.has(a, "PARENT", b))
        assertEquals(0L, api.valueOf(a, "PARENT", b))
        assertTrue(api.setValue(a, "PARENT", b, 100L))
        assertEquals(100L, api.valueOf(a, "PARENT", b))
        assertEquals(listOf(b), api.neighbors(a, "PARENT", outgoing = true))
        assertEquals(listOf(a), api.neighbors(b, "PARENT", outgoing = false))
        // 不隐式建边：改不存在的边返回 false。
        assertFalse(api.setValue(b, "PARENT", a, 1L))
        assertTrue(api.remove(a, "PARENT", b))
        assertFalse(api.has(a, "PARENT", b))
    }

    @Test
    fun `未注册原型创建拒绝`() {
        val api = newApi()
        assertThrows<IllegalArgumentException> { api.create("Ghost") }
    }

    @Test
    fun `destroy 级联清关系边`() {
        val api = newApi()
        val a = api.create("Child")
        val b = api.create("Child")
        api.add(a, "PARENT", b)
        assertTrue(api.destroy(b))
        assertFalse(api.has(a, "PARENT", b))
        assertTrue(api.neighbors(a, "PARENT", outgoing = true).isEmpty())
    }
}