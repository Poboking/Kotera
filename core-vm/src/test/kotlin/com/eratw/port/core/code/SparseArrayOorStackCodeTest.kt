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
 * QB-A1 回归锁（tripwire）：`_STK_*VAR*` 栈码族对「动态下标 + 稀疏背板」**不走补径、
 * 照旧严格抛**——钉住现状，防两头的静默漂移。
 *
 * ## 范围与口径（verify-notes 2026-11-17 稀疏条「待接线三条」之一）
 *
 * 稀疏 1 维背板补径（`c45357a86` + `660e3ef61`）只接在**表达式通道**
 * （`VarRef` 五路 + `Unary` 两路，见 `core.expr`）；五个栈码
 * `_STK_PUSH_VAR_I` / `_STK_PUSH_VAR_S` / `_STK_PLUS_VAR_I` / `_STK_STORE_VAR_I` /
 * `_STK_STORE_VAR_S` 的实现（`Vm.kt:3107/:3127/:3145/:3172/:3201`）直呼
 * `readInt`/`readStr`/`plusInt`/`writeInt`/`writeStr`（及各自 H 口），catch 的又是
 * `ExprEvalException`——`CodeEE` 不是它的子类型，直接原样穿透 ⇒ 动态越界照抛。
 * 发射面只有 `.erbasm` 手写汇编（`Assembler.kt:441-467`），ERB 语料不可达。
 *
 * 「写明此族仅供 erbasm（KDoc）或补径」是**二选一的主代码裁决**，归组长（QB-A1 契约
 * 禁改主代码）；本文件先把**当前**口径钉住：
 * - 若组长裁决「补径」，这五条红 = 裁决落地的待办清单，逐条翻绿；
 * - 若裁决「仅供 erbasm」（维持现状），本文件就是那条裁决的回归面——任何人无意间
 *   改动栈码族的越界行为（无论变松还是变严）都会在此显式红。
 *
 * **反向证明（相对 `c45357a86`）**：本族从未接过补径，撤掉主补径本文件仍绿——
 * 它锁的不是补径本身，而是「栈码族 = 严格」的现状口径；正向对照用例
 * （界内存取回环）保证红/绿判据来自越界语义而不是夹具坏了。
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
    // Tripwire：动态下标越界照抛（五口逐一）
    // ------------------------------------------------------------------

    /** 读口（整数）。反向对照：表达式通道同形态返 0（SparseArrayOorTest 动态下标越界读返回默认值 0）。 */
    @Test
    fun `栈码 PUSH_VAR_I 动态越界照抛`() {
        val store = newStore()
        val e = assertFailsWith<CodeEE> {
            runOn(
                store,
                """
                .func main slots=4
                  _STK_PUSH_IMM -1
                  _STK_PUSH_VAR_I "ITEM", 1
                  _STK_POP_SLOT_I R0
                  _RET R0
                """,
            )
        }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message, "与玩家上报文案逐字同款")
    }

    /** 读口（字符串）。 */
    @Test
    fun `栈码 PUSH_VAR_S 动态越界照抛`() {
        val store = newStore()
        val e = assertFailsWith<CodeEE> {
            runOn(
                store,
                """
                .func main slots=4
                  _STK_PUSH_IMM -1
                  _STK_PUSH_VAR_S "STR", 1
                  _STK_POP_SLOT_S R0
                  _RETS R0
                """,
            )
        }
        assertEquals("数组变量\"STR\"的第1参数(-1)超出数组范围", e.message)
    }

    /** 读改写一体口（`+=`）。 */
    @Test
    fun `栈码 PLUS_VAR_I 动态越界照抛`() {
        val store = newStore()
        val e = assertFailsWith<CodeEE> {
            runOn(
                store,
                """
                .func main slots=4
                  _STK_PUSH_IMM -1
                  _STK_PLUS_VAR_I "ITEM", 1, 1
                  _STK_POP_SLOT_I R0
                  _RET R0
                """,
            )
        }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message)
    }

    /** 写口（整数；值在栈顶、下标在下的弹出次序见 Vm.storeVarInt KDoc）。 */
    @Test
    fun `栈码 STORE_VAR_I 动态越界照抛`() {
        val store = newStore()
        val e = assertFailsWith<CodeEE> {
            runOn(
                store,
                """
                .func main slots=4
                  _STK_PUSH_IMM -1
                  _STK_PUSH_IMM 7
                  _STK_STORE_VAR_I "ITEM", 1
                  _RET R0
                """,
            )
        }
        assertEquals("数组变量\"ITEM\"的第1参数(-1)超出数组范围", e.message)
    }

    /** 写口（字符串）。 */
    @Test
    fun `栈码 STORE_VAR_S 动态越界照抛`() {
        val store = newStore()
        val e = assertFailsWith<CodeEE> {
            runOn(
                store,
                """
                .func main slots=4
                  _STK_PUSH_IMM -1
                  _STK_PUSH_STR "x"
                  _STK_STORE_VAR_S "STR", 1
                  _RET R0
                """,
            )
        }
        assertEquals("数组变量\"STR\"的第1参数(-1)超出数组范围", e.message)
    }
}
