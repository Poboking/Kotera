package com.eratw.port.core.fn

import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.proc.EngineProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B6 组长接线的钉子（ENGC-3b 编译期注入面）：
 *
 * 1. [FullRegistryView] 必须与 `Builtins.registry` 直查逐位相同 —— 这是「缺省路径
 *    逐字节不变」（I7）在 core-vm 侧的自证；erbcc 侧另有产物 sha256 对拍。
 * 2. [EngineSurface] 按 [CompileBuiltinView] 语义门控：被裁名字（snake-only 的
 *    `SQL_CONNECT`，B3 回执锚）一律 null/false，不回落全量表（D7/I4）。
 * 3. [EngineProfiles.legalStatementNames] 与 B4 审计同源：结构码恒在、目标外命令
 *    （`SETBGIMAGE`，B4 真产物命中清单成员）按档进出。
 */
class CompileBuiltinViewTest {

    @Test
    fun 全量视图与registry直查逐位相同() {
        val reg = Builtins.registry
        for (name in listOf("ABS", "SQL_CONNECT", "NOT_A_REAL_FUNC_XYZ")) {
            assertEquals(reg.returnType(name), FullRegistryView.returnType(name), "returnType($name)")
            assertEquals(reg.needsExprArgs(name), FullRegistryView.needsExprArgs(name), "needsExprArgs($name)")
            assertEquals(reg[name] != null, FullRegistryView.method(name) != null, "method($name)")
            assertEquals(reg.isPlaceholder(name), FullRegistryView.isPlaceholder(name), "isPlaceholder($name)")
        }
    }

    @Test
    fun 被裁名字在目标档视图下判null不回落全量表() {
        val v24 = EngineSurface(EngineFamily.V24_1824)
        // B3 回执锚：SQL_CONNECT 是 snake-only（v24 函数面无此名），但 registry 里已注册
        // ——「已注册但本档不可见」正是门控与直查必须分叉的唯一形态。
        assertNotNull(Builtins.registry["SQL_CONNECT"])
        assertFalse(v24.builtinVisible("SQL_CONNECT"))
        assertNull(v24.method("SQL_CONNECT"))
        assertNull(v24.returnType("SQL_CONNECT"))
        assertFalse(v24.needsExprArgs("SQL_CONNECT"))
    }

    @Test
    fun 可见名字在目标档视图下与registry同值() {
        val v24 = EngineSurface(EngineFamily.V24_1824)
        val visible = v24.builtinNames().firstOrNull { Builtins.registry[it] != null }
        assertNotNull(visible, "目标档视图里应存在已注册的可见名")
        assertEquals(Builtins.registry[visible], v24.method(visible))
        assertEquals(Builtins.registry.returnType(visible), v24.returnType(visible))
        assertEquals(Builtins.registry.needsExprArgs(visible), v24.needsExprArgs(visible))
    }

    @Test
    fun 合法集门面与B4审计同源结构码恒在() {
        val structural = setOf("__NULL__", "SET", "METHOD")
        for (family in EngineFamily.entries) {
            val legal = EngineProfiles.legalStatementNames(family)
            assertTrue(legal.containsAll(structural), "$family 缺结构码")
        }
    }

    @Test
    fun 目标外命令按档进出合法集() {
        // B4 真产物读数锚：SETBGIMAGE 在 snake 与 em_ee 命令表（EngineProfileData.kt:605/:1195）、
        // 不在 v24 表 ⇒ V24 档审计命中（目标外）。
        assertTrue(EngineProfiles.legalStatementNames(EngineFamily.SNAKE_4981).contains("SETBGIMAGE"))
        assertTrue(EngineProfiles.legalStatementNames(EngineFamily.EM_EE_1824_V22).contains("SETBGIMAGE"))
        assertFalse(EngineProfiles.legalStatementNames(EngineFamily.V24_1824).contains("SETBGIMAGE"))
    }
}
