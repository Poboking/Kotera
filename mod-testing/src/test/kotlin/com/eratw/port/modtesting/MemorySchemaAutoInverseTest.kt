package com.eratw.port.modtesting

import com.eratw.port.modapi.RelationKey
import com.eratw.port.modapi.modDeclarations
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 钉子：autoInverse 派生反边不得吞掉「排在主声明之后」的显式反边声明。
 *
 * 与生产 `EntityKernel.kt:66-93` 的两趟语义对齐：先收齐显式声明，再补派生反边，
 * 显式声明了同名 schema 时保留用户版。
 */
class MemorySchemaAutoInverseTest {

    private val master = RelationKey("TEST.ORDER.MASTER")
    private val inverse = RelationKey("TEST.ORDER.INVERSE")

    @Test
    fun explicitInverseDeclaredAfterMasterKeepsItsOwnSpec() {
        val manifest = modDeclarations("test.order") {
            // 主声明在前：派生 inverseSpecOf 会是 (directed=true, dedupeEdges=true, cascadeOnDestroy=false)
            relation(master, inverse = inverse, cascadeOnDestroy = false)
            // 显式反边在后：三个语义位全部与派生版不同，确保被吞掉时断言必失败
            relation(inverse, directed = false, dedupeEdges = false, cascadeOnDestroy = true)
        }

        val schema = MemorySchema.of(listOf(manifest))

        // 修复前单趟循环：主声明处理时 :144 已把派生版写进 inverseKey，
        // 后到的显式声明 putIfAbsent 被吞 → 拿到派生版（断言失败）。
        // 修复后两趟注册：第一趟先收显式声明 → 第二趟 putIfAbsent 不覆盖 → 拿到用户版。
        assertEquals(
            MemorySchema.RelationSpec(
                directed = false,
                dedupeEdges = false,
                cascadeOnDestroy = true,
            ),
            schema.relationships[inverse.name],
            "显式反边排在主声明之后时，必须保留用户显式声明的语义位",
        )
        // 两趟注册不得弄丢反向映射
        assertEquals(inverse.name, schema.inverseOf[master.name])
    }
}
