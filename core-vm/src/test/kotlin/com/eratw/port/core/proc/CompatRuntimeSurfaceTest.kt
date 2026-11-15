package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.fn.EngineSurface
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ENGC-3：运行时接线（[GameSession] / [ProcessRuntime] 的 `engineSurface` 形参
 * 是否真的落到 [BuiltinEvalContext] 的三条读点）。
 *
 * 与 `CompatBuiltinSurfaceTest` 的分工：那份钉门控语义本身（直接造语境），
 * 这份钉**装配链**——`ProcessRuntime(engineSurface = ...)` 传不进 = 门控在真流程里形同虚设。
 */
class CompatRuntimeSurfaceTest {

    private fun runtime(family: EngineFamily? = null): ProcessRuntime = ProcessRuntime(
        Assembler.assemble("_HALT"),
        VariableStore(),
        FunctionTable(emptyList()),
        VmConfig(),
        engineSurface = family?.let { EngineSurface(it) },
    )

    private fun ctx(rt: ProcessRuntime): BuiltinEvalContext = rt.evalContext as BuiltinEvalContext

    private fun int(rt: ProcessRuntime, name: String, vararg args: Value): Long =
        (rt.evalContext.callFunction(name, args.toList()) as Value.IntV).v

    @Test
    fun `不传面（eraTW 缺省）三条读点仍是全量注册表`() {
        val rt = runtime()
        val ctx = ctx(rt)
        assertEquals(Builtins.registry.names(), ctx.builtinNames.toSet(), "ENUMFUNC 候选 = 全量注册表")
        assertEquals(OperandType.INT, ctx.resolveFunctionType("SQL_CONNECT"), "snake-only 名照旧可解析")
    }

    @Test
    fun `V24 面穿过运行时：snake-only 名进未定义路径`() {
        val rt = runtime(EngineFamily.V24_1824)
        val ctx = ctx(rt)
        assertNull(ctx.resolveFunctionType("SQL_CONNECT"))
        assertTrue("SQL_CONNECT" !in ctx.builtinNames)

        val hidden = assertFailsWith<CodeEE> {
            rt.evalContext.callFunction("SQL_CONNECT", listOf(Value.StrV("x")))
        }
        assertEquals("未定义的函数 SQL_CONNECT", hidden.message)
    }

    @Test
    fun `SNAKE 面穿过运行时：同名同对象、照常可解析`() {
        val ctx = ctx(runtime(EngineFamily.SNAKE_4981))
        assertEquals(OperandType.INT, ctx.resolveFunctionType("SQL_CONNECT"))
        assertSame(
            Builtins.registry["SQL_CONNECT"],
            EngineSurface(EngineFamily.SNAKE_4981).builtinLookup("SQL_CONNECT"),
        )
        assertTrue("SQL_CONNECT" in ctx.builtinNames)
    }

    @Test
    fun `共有名在任意面照常求值`() {
        for (family in listOf(null) + EngineFamily.values().toList()) {
            assertEquals(5L, int(runtime(family), "ABS", Value.IntV(-5)), "$family")
        }
    }

    @Test
    fun `用户函数不进档位门控：EM_EE 面下 ENUMFUNC 照旧枚举到用户标签`() {
        // 真实装配里 host.userFunctions 非空（FunctionTable 投影），ENUMFUNC 走用户表，
        // 不经过内建可见性；档位只裁内建面（EM_EE 面裁掉了 SQL_CONNECT，但用户标签照旧）。
        val rt = ProcessRuntime(
            Assembler.assemble("_HALT"),
            VariableStore(),
            FunctionTable(listOf(FuncMeta(index = 0, key = "MYFUNC", defIndex = 0))),
            VmConfig(),
            engineSurface = EngineSurface(EngineFamily.EM_EE_1824_V22),
        )
        assertEquals(1L, int(rt, "ENUMFUNCBEGINSWITH", Value.StrV("MYFUNC")))
    }
}
