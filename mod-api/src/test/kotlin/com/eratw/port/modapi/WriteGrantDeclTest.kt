package com.eratw.port.modapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ModDX4-B1 声明域定向钉：`writeGrants`（原版全局变量受控写授权，`RT2MANIFEST/8` 声明面）。
 *
 * 钉四件事：
 * - DSL `writeGrant(key, type)` 落进 [ModManifest.writeGrants]（声明序保真、类型位原样）；
 * - 键空 = 构造期 IAE（作者写错在声明那一行就炸，不留到装载期）；
 * - 同键重复声明（原文判重）= [ModManifest] 构造期 IAE（整包声明失败，必然失效 —— 与
 *   settings 键唯一性同一构造期口径；大小写归一后的重复由 core `ModuleDescriptor` 终审）；
 * - [GameStateApi] 写方法的**缺省实现 fail-closed**：未接线的实现抛 UOE，不静默吞写。
 */
class WriteGrantDeclTest {

    @Test
    fun `dsl 声明落进 manifest 写授权清单 声明序保真`() {
        val manifest = modDeclarations("test.grant", version = "0.1") {
            writeGrant("DAY")
            writeGrant("PLACE", DimType.STR)
        }
        assertEquals(2, manifest.writeGrants.size)
        assertEquals(WriteGrantDecl("DAY", DimType.INT), manifest.writeGrants[0])
        assertEquals(WriteGrantDecl("PLACE", DimType.STR), manifest.writeGrants[1])
    }

    @Test
    fun `键空白构造期即拒`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            modDeclarations("test.grant") { writeGrant("  ") }
        }
        assertTrue(ex.message!!.contains("writeGrant 键不能为空"))
    }

    @Test
    fun `同键重复声明构造期整包拒`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            modDeclarations("test.grant") {
                writeGrant("DAY")
                writeGrant("DAY")
            }
        }
        assertTrue(ex.message!!.contains("writeGrants 键在本包内必须唯一"))
    }

    @Test
    fun `缺省空清单 既有声明产物不受影响`() {
        val manifest = modDeclarations("test.grant") { global(GlobalKey("MOD_STATE")) }
        assertTrue(manifest.writeGrants.isEmpty())
    }

    @Test
    fun `写方法缺省实现 fail-closed 抛 UOE 不静默吞写`() {
        val api = object : GameStateApi {
            override fun getIntVar(name: String): Long = 0L
            override fun getStrVar(name: String): String = ""
            override fun hasIntVar(name: String): Boolean = false
            override fun hasStrVar(name: String): Boolean = false
        }
        val intEx = assertFailsWith<UnsupportedOperationException> { api.setIntVar("DAY", 1L) }
        assertTrue(intEx.message!!.contains("gameState.setIntVar(DAY) 未接线"))
        val strEx = assertFailsWith<UnsupportedOperationException> { api.setStrVar("PLACE", "x") }
        assertTrue(strEx.message!!.contains("gameState.setStrVar(PLACE) 未接线"))
    }
}
