package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * QB-A1 回归锁：`_STK_*VAR*` 栈码族对运行期越界下标**接稀疏补径**（2026-11-27 落地）。
 *
 * ## 裁决与理由（此前版本是「照旧严格抛」的 tripwire）
 *
 * 稀疏 1 维背板补径（`c45357a86` + `660e3ef61`）在表达式通道由 `VarRef` 五路承接；
 * 五个栈码 `_STK_PUSH_VAR_I` / `_STK_PUSH_VAR_S` / `_STK_PLUS_VAR_I` /
 * `_STK_STORE_VAR_I` / `_STK_STORE_VAR_S` 原先直呼 `readInt`/`readStr`/`plusInt`/
 * `writeInt`/`writeStr`（及各自 H 口），catch 的只有 `ExprEvalException`——`CodeEE`
 * 不是其子类型，越界原样穿透。发射面只有 `.erbasm` 手写汇编（`Assembler.kt:441-467`），
 * ERB 语料不可达。
 *
 * 裁决为**补径**而不是「写明仅供 erbasm」：栈面下标全部是运行期值（`.erbasm` 没有
 * 运算符常量层，`_STK_PUSH_IMM -1` 与算出来的 -1 在 VM 眼里同形）⇒ 与 `VarRef` 的
 * 「常量下标抛、运行期下标容忍」判据同档。容忍语义只在数据层（`SparseArray.cs:25-40`），
 * 非 1 维变量的补径口仍回落严格通道、抛同一条 `CodeEE`（见下图二维对照）。
 *
 * **反向证明**：撤掉 `Vm.readVarInt` 等五个口的 `catch (CodeEE)` 段，五条越界用例立刻
 * 回到 `数组变量"ITEM"的第1参数(-1)超出数组范围` 而红；正向对照（界内存取回环）保证
 * 红/绿来自越界语义而不是夹具坏了。
 */
class SparseArrayOorStackCodeTest {

    /** 把变量池的作用域栈挂到 VM 的帧生命周期上（同 VmVarBindingTest.StoreHost 的最小形态）。 */
    private class StoreHost(
        private val store: VariableStore,
        private val functionNames: List<String>,
    ) : VmHost {
        override val evalContext: EvalContext = object : EvalContext {
            override val variables: VariableReader get() = store

            override fun callFunction(name: String, args: List<Value?>): Value =
                throw ExprEvalException("测试未绑定函数 $name")
        }

        override fun onFunctionEnter(funcIndex: Int) {
            store.enterFunction(functionNames[funcIndex])
        }

        override fun onFunctionExit(funcIndex: Int) {
            store.leaveFunction()
        }

        override fun print(text: String) = Unit
    }

    private fun newStore(): VariableStore = VariableStore().apply {
        // ADR-001：RNG 必须走注入的 provider（禁止 kotlin.random 兜底）。
        randProvider = com.eratw.port.core.rand.Sfmt19937(0L)
    }

    private fun runOn(store: VariableStore, src: String): Long {
        val program = Assembler.assemble(src)
        val host = StoreHost(store, program.functions.map { it.name })
        return VirtualMachine(program, host).run()
    }

    // ------------------------------------------------------------------
    // 正向对照：界内存取回环（证明红/绿判据来自越界语义，不是夹具）
    // ------------------------------------------------------------------

    @Test
    fun `栈码族界内存取回环正常`() {
        val store = newStore()
        val ret = runOn(
            store,
            """
            .func main slots=4
              _STK_PUSH_IMM 5
              _STK_PUSH_IMM 7
              _STK_STORE_VAR_I "ITEM", 1
              _STK_PUSH_IMM 5
              _STK_PUSH_VAR_I "ITEM", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        assertEquals(7L, ret, "STORE 后 PUSH 读回同槽")
        assertEquals(7L, store.readInt("ITEM", longArrayOf(5)), "落点 = 真实变量池的 ITEM:5")
    }

    // ------------------------------------------------------------------
    // 补径：运行期下标越界按 1 维稀疏背板容忍（五口逐一）
    // ------------------------------------------------------------------

    /** 读口（整数）：越界读缺省 0（PC 实测 `ITEM:-1` 读 0）。 */
    @Test
    fun `栈码 PUSH_VAR_I 动态越界读返 0`() {
        val store = newStore()
        val ret = runOn(
            store,
            """
            .func main slots=4
              _STK_PUSH_IMM -1
              _STK_PUSH_VAR_I "ITEM", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        assertEquals(0L, ret)
        assertEquals(0L, store.readSparseIntOor("ITEM", longArrayOf(-1)), "越界读不落层，只返默认值")
    }

    /** 读口（字符串）：越界读空串（回填进 RESULTS:0 读回，`run()` 只返回 Long）。 */
    @Test
    fun `栈码 PUSH_VAR_S 动态越界读返空串`() {
        val store = newStore()
        runOn(
            store,
            """
            .func main slots=4
              _STK_PUSH_IMM -1
              _STK_PUSH_VAR_S "STR", 1
              _STK_PUSH_IMM 0
              _STK_STORE_VAR_S "RESULTS", 1
              _RET R0
            """,
        )
        assertEquals("", store.readStr("RESULTS", longArrayOf(0)))
    }

    /** 读改写一体口（`+=`）：缺省 0 + 1 = 1，并落覆盖层（第二次读到 1）。 */
    @Test
    fun `栈码 PLUS_VAR_I 动态越界按缺省 0 累加`() {
        val store = newStore()
        val ret = runOn(
            store,
            """
            .func main slots=4
              _STK_PUSH_IMM -1
              _STK_PLUS_VAR_I "ITEM", 1, 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        assertEquals(1L, ret)
        assertEquals(1L, store.readSparseIntOor("ITEM", longArrayOf(-1)), "`+=` 结果落覆盖层")
    }

    /** 写口（整数）：写 7 落覆盖层，随后同槽读回 7。 */
    @Test
    fun `栈码 STORE_VAR_I 动态越界写可读回`() {
        val store = newStore()
        val ret = runOn(
            store,
            """
            .func main slots=4
              _STK_PUSH_IMM -1
              _STK_PUSH_IMM 7
              _STK_STORE_VAR_I "ITEM", 1
              _STK_PUSH_IMM -1
              _STK_PUSH_VAR_I "ITEM", 1
              _STK_POP_SLOT_I R0
              _RET R0
            """,
        )
        assertEquals(7L, ret)
        assertEquals(7L, store.readSparseIntOor("ITEM", longArrayOf(-1)), "落点 = 覆盖层，可按同下标读回")
    }

    /** 写口（字符串）：写 "x" 落覆盖层，随后同槽读回 "x"（回填 RESULTS:0）。 */
    @Test
    fun `栈码 STORE_VAR_S 动态越界写可读回`() {
        val store = newStore()
        runOn(
            store,
            """
            .func main slots=4
              _STK_PUSH_IMM -1
              _STK_PUSH_STR "x"
              _STK_STORE_VAR_S "STR", 1
              _STK_PUSH_IMM -1
              _STK_PUSH_VAR_S "STR", 1
              _STK_PUSH_IMM 0
              _STK_STORE_VAR_S "RESULTS", 1
              _RET R0
            """,
        )
        assertEquals("x", store.readStr("RESULTS", longArrayOf(0)), "落点 = 覆盖层，可按同下标读回")
        assertEquals("x", store.readSparseStrOor("STR", longArrayOf(-1)), "落点 = 覆盖层")
    }

    // ------------------------------------------------------------------
    // 补径边界：非 1 维变量仍严格抛（补径不得放宽到稠密背板）
    // ------------------------------------------------------------------

    /** 二维内置数组没有 SparseArray 背板：动态越界照旧抛，且文案是玩家上报同款。 */
    @Test
    fun `栈码 PUSH_VAR_I 二维越界仍抛`() {
        val store = newStore()
        val e = assertFailsWith<CodeEE> {
            runOn(
                store,
                """
                .func main slots=4
                  _STK_PUSH_IMM -1
                  _STK_PUSH_IMM 0
                  _STK_PUSH_VAR_I "DITEMTYPE", 2
                  _STK_POP_SLOT_I R0
                  _RET R0
                """,
            )
        }
        assertEquals("数组变量\"DITEMTYPE\"的第1参数(-1)超出数组范围", e.message)
    }
}
