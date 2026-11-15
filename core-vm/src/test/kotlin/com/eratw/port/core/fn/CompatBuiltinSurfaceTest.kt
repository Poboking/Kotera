package com.eratw.port.core.fn

import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ENGC-3：函数面可见性门控（[EngineSurface] + [BuiltinEvalContext] 三条读点）。
 *
 * 判据：
 * 1. **缺省档零变化**：`surface = null` ⇒ 派发、解析期返回类型、`builtinNames` 全部走
 *    全量注册表（eraTW / 装置旧路径，I7）；
 * 2. **显式档按档裁**：V24 档下 snake-only 的 `SQL_CONNECT` 与「从未有过」的名字走同一条
 *    `未定义的函数` 路径；`ABS` 等共有名照常；
 * 3. **扩展白名单不裁**：`ENTITY_CREATE` 等 [EngineProfileData.PORT_EXTENSIONS] 在任何档可见；
 * 4. **用户函数不被门控**：同名 `#FUNCTION` 仍优先（原版两张表本来就分开）；
 * 5. **ENUMFUNC 候选表与派发同源**：EM_EE 档枚不到 `SQL_CONNECT`，SNAKE 档枚得到；
 *    V24 函数面本身没有 ENUMFUNC 家族（1.824 基线事实），枚举函数也一起裁。
 */
class CompatBuiltinSurfaceTest {

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {
            // 测试不需要系统行
        }
    }

    private fun ctx(
        family: EngineFamily? = null,
        userFunctions: Map<String, (List<Value?>) -> Value> = emptyMap(),
    ): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        val surface = family?.let { EngineSurface(it) }
        return BuiltinEvalContext(base, store, surface = surface, userFunctions = userFunctions) to store
    }

    private fun int(c: BuiltinEvalContext, name: String, vararg args: Value): Long =
        (c.callFunction(name, args.toList()) as Value.IntV).v

    @Test
    fun `缺省档三条读点都是全量注册表`() {
        val (c, _) = ctx()
        assertEquals(Builtins.registry.names(), c.builtinNames.toSet(), "ENUMFUNC 候选 = 全量注册表")
        assertEquals(OperandType.INT, c.resolveFunctionType("SQL_CONNECT"), "解析期照旧可见")
        assertSame(Builtins.registry["SQL_CONNECT"], EngineSurface(EngineFamily.SNAKE_4981).builtinLookup("SQL_CONNECT"))
    }

    @Test
    fun `V24 档里 snake 独有名字与从未存在的名字同一条错误路径`() {
        val (v24, _) = ctx(EngineFamily.V24_1824)
        val hidden = assertFailsWith<CodeEE> { v24.callFunction("SQL_CONNECT", listOf(Value.StrV("x"))) }
        val never = assertFailsWith<CodeEE> { v24.callFunction("NO_SUCH_FUNC", emptyList()) }
        assertEquals("未定义的函数 SQL_CONNECT", hidden.message)
        assertEquals("未定义的函数 NO_SUCH_FUNC", never.message)

        // 解析期也不可见 ⇒ 同一 CodeEE 文案的抛出点（D7：不新增语义分支）。
        assertNull(v24.resolveFunctionType("SQL_CONNECT"))
    }

    @Test
    fun `V24 档共有名照常可调`() {
        val (v24, _) = ctx(EngineFamily.V24_1824)
        assertEquals(5L, int(v24, "ABS", Value.IntV(-5)))
        assertNotNull(v24.resolveFunctionType("ABS"))
    }

    @Test
    fun `端口扩展白名单在任何档可见`() {
        for (family in EngineFamily.values()) {
            val surface = EngineSurface(family)
            for (name in EngineProfileData.PORT_EXTENSIONS) {
                assertTrue(surface.builtinVisible(name), "$family 应可见端口扩展 $name")
            }
        }
    }

    @Test
    fun `跨面名按函数面判据：SETANIMETIMER 在 SNAKE 函数面不可见`() {
        // 生成物事实：SETANIMETIMER 只出现在 em_ee/v24 的**函数**表，SNAKE 只在**命令**表。
        assertTrue("SETANIMETIMER" in EngineProfileData.commandsOf(EngineFamily.SNAKE_4981))
        assertTrue("SETANIMETIMER" !in EngineProfileData.functionsOf(EngineFamily.SNAKE_4981))
        assertTrue(EngineSurface(EngineFamily.SNAKE_4981).builtinVisible("SETANIMETIMER").not())
        assertTrue(EngineSurface(EngineFamily.EM_EE_1824_V22).builtinVisible("SETANIMETIMER"))
        assertTrue(EngineSurface(EngineFamily.V24_1824).builtinVisible("SETANIMETIMER"))
    }

    @Test
    fun `用户函数不被门控且优先于内建`() {
        val (v24, _) = ctx(
            EngineFamily.V24_1824,
            userFunctions = mapOf("SQL_CONNECT" to { _: List<Value?> -> Value.intV(7) }),
        )
        assertEquals(7L, int(v24, "SQL_CONNECT", Value.StrV("x")))
    }

    @Test
    fun `ENUMFUNC 候选表与派发可见性同源`() {
        val (snake, _) = ctx(EngineFamily.SNAKE_4981)
        // f2bf08be8 后注册表含 SQL_CONNECT 与 SQL_CONNECTION_OPEN 两条同前缀 ⇒ 2。
        assertEquals(2L, int(snake, "ENUMFUNCBEGINSWITH", Value.StrV("SQL_CONNECT")))
        assertTrue("SQL_CONNECT" in snake.builtinNames)

        // em_ee 有 ENUMFUNC 家族、没有 SQL_CONNECT：候选表裁到 0 = 与派发同源。
        val (em, _) = ctx(EngineFamily.EM_EE_1824_V22)
        assertEquals(0L, int(em, "ENUMFUNCBEGINSWITH", Value.StrV("SQL_CONNECT")))
        assertTrue("SQL_CONNECT" !in em.builtinNames)
    }

    @Test
    fun `V24 函数面无 ENUMFUNC 家族：枚举函数本身也走未定义路径`() {
        // 生成物事实（Creator.cs:15 methodList 无 ENUM*）：ENUMFUNC* 是 EM/EE 系新增，
        // 1.824 基线没有 ⇒ 目标档为 v24 时连枚举函数一起裁，不另开特例。
        assertTrue("ENUMFUNCBEGINSWITH" !in EngineProfileData.functionsOf(EngineFamily.V24_1824))
        assertTrue(EngineSurface(EngineFamily.SNAKE_4981).builtinVisible("ENUMFUNCBEGINSWITH"))
        assertTrue(EngineSurface(EngineFamily.EM_EE_1824_V22).builtinVisible("ENUMFUNCBEGINSWITH"))

        val (v24, _) = ctx(EngineFamily.V24_1824)
        assertFailsWith<CodeEE> {
            v24.callFunction("ENUMFUNCBEGINSWITH", listOf(Value.StrV("SQL_CONNECT")))
        }
    }
}
