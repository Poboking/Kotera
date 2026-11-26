package com.eratw.port.core.proc

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **方法栈深度配额的异常路径泄漏**（[ProcessRuntime.invokeUserMethod]）。
 *
 * ## 缺陷
 *
 * `methodDepth++` 原来落在 `try` **之外**，与 `try` 之间夹着三件事：
 * `store.currentFunction` 读、[ProcessRuntime] 内部的 `buildPendingRefBindings`
 * （走 `program.paramNames` / `refUserParamAt` / `resolveRefSource` 三层查表）与
 * `nestedVmPool.removeFirstOrNull() ?: vm.deriveNestedVm(this, vmConfig)`（**分配一台
 * 派生 VM**）。任何一处抛 —— 真机 512 MiB 堆上 `deriveNestedVm` 的 OOM 完全可能 ——
 * 就跳过了 `finally` 里的 `methodDepth--`，**配额被永久吃掉一个**。
 *
 * 后果不可自愈：`methodDepth` 由 `executeMethod` 与 `invokeUserMethod` **共用**，
 * 且 `GETMETH` / `GETMETHS` / `CALLF` 全走后者；泄漏累计到 `METHOD_STACK_LIMIT`（100）
 * ⇒ 本局所有方法调用永久抛 `OverflowFuncStack`，只能重开游戏。这与上报端点里
 * 「真机先 OOM 一次、之后方法全废」的现象吻合。
 *
 * ## 原版口径（C# 锚）
 *
 * `Process.cs:518-572` 的 `GetValue(SuperUserDefinedMethodTerm)`：
 * `methodStack++`（:520）→ 溢出检查 `if (methodStack > 100) throw`（:523-526）→
 * `state.currentMin` 赋值 / `updateRetAddress` / `hasPrivDynamicVar` / `CaptureCallState`
 * （:528-538）→ `try`（:539-545）→ `finally`（:546-570）里 `methodStack--`（:569）。
 * 即**原版同形**：`++` 在 `try` 之外，中间任一句抛同样漏一个；且原版把溢出检查放在
 * `++` **之后**、也在 `try` 之外 ⇒ 原版每抛一次 `OverflowFuncStack` 还额外漏一个
 * （本移植把检查放在 `++` 之前，没有复制这一半）。按 ADR-005 新口径，引擎侧缺陷
 * 直修为真确语义，不因「原版同形」保留。
 *
 * ## 用例怎么造出「窗口内抛」
 *
 * 真机触发点是 `deriveNestedVm` 的 OOM，单测无法注入 OOM，故改用**同一个窗口**里
 * 另一处必然可注入的读：[ProcessRuntime.invokeUserMethod] 的 `args` 是公开参数，
 * 传一个 `size` 就抛的 [List] ⇒ 异常在 `buildPendingRefBindings` 的 `args.indices`
 * 处抛出，位置与 OOM 完全一致（`methodDepth++` 之后、`try` 之前）。
 *
 * ## 判据是纯行为断言（不开测试可见读数）
 *
 * 配额漏没漏只能从**后续调用是否还活着**看出来：泄漏 k 次后 `methodDepth` 恒为 k，
 * 累计到 100 之后任何正常方法调用都抛 `OverflowFuncStack`。故用例「连抛 N 次
 * （与正常调用交错）后仍能拿到正确返回值」即等价于「配额回到 0」。
 * spec: Process.cs:502-506（`methodStack > 100` → `OverflowFuncStack`）。
 */
class MethodDepthLeakTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "depthleak.erb", i + 1, text))
        }
    }

    /**
     * 最小 `#FUNCTION ADD(ARG)`：`RETURNF ARG + 1`。形参名取系统数组 `ARG`，
     * 夹具不必额外声明用户变量（同 `MethodRootParamBindTest` 的 `DIVIDE` 对照组口径）。
     *
     * 形参名**非空**是关键：`buildPendingRefBindings` 在 `paramNames.isEmpty()` 时提前
     * 返回，就走不到 `args.indices` 这个注入点。
     */
    private fun runtime(): ProcessRuntime {
        val sig = FuncSig("ADD", arity = 1, isMethod = true, paramNames = listOf("ARG"))
        val c = ErbProgramCompiler()
        c.compileFunction(sig, parse("@ADD", "RETURNF ARG + 1"), setOf("ADD"))
        val idx = c.functions.first { it.sig.name == "ADD" }.index
        val meta = FuncMeta(index = idx, key = "ADD", name = "ADD", isMethod = true, argIntCount = 1)
        return ProcessRuntime(c.build("ADD"), VariableStore(), FunctionTable(listOf(meta)), VmConfig())
    }

    /**
     * `size` 一读就抛的实参表：模拟 `methodDepth++` 与 `try` 之间那一窗里任何一次
     * 分配/解析失败（真机是 `deriveNestedVm` 的 OOM）。异常**原样上抛**，不许被吞。
     */
    private class ThrowingArgsList : AbstractList<Value?>() {
        override val size: Int
            get() = throw IllegalStateException(INJECTED)
        override fun get(index: Int): Value? = throw IllegalStateException(INJECTED)
    }

    // ------------------------------------------------------------------
    // 例 1（真红）：异常路径不许吃配额
    // ------------------------------------------------------------------

    /**
     * 交错：抛一次 → 正常调一次，重复 `METHOD_STACK_LIMIT + 1` 轮。
     *
     * 旧形状下第 k 轮结束时 `methodDepth == k`（每轮漏 1），正常调用自身再 +1 ⇒
     * 第 100 轮那个 `assertEquals` 实际会抛 `CodeEE(OverflowFuncStack)`（配额已被吃光）。
     * 修复后每轮都回到 0，`METHOD_STACK_LIMIT` 之外的任何轮次都不该看见溢出。
     */
    @Test
    fun `异常路径不消耗方法栈配额 - 连抛 N 次后正常调用仍可执行`() {
        val rt = runtime()
        val rounds = METHOD_STACK_LIMIT + 1
        repeat(rounds) { round ->
            val failed = assertFailsWith<IllegalStateException>(
                "第 ${round + 1} 轮：窗口内异常必须原样上抛（不许 runCatching 吞掉）",
            ) {
                rt.invokeUserMethod("ADD", ThrowingArgsList(), listOf<VarRef?>(null))
            }
            assertEquals(INJECTED, failed.message, "异常本体与文案不许被替换")
            assertEquals(
                Value.IntV(42),
                rt.invokeUserMethod("ADD", listOf(Value.IntV(41))),
                "第 ${round + 1} 轮：抛出后 methodDepth 必须回到 0 —— 泄漏累计到 100 后" +
                    "本局所有方法调用永久抛 OverflowFuncStack（只能重开游戏）",
            )
        }
    }

    // ------------------------------------------------------------------
    // 例 2（正向）：成功路径的配对归还
    // ------------------------------------------------------------------

    /**
     * 连续 `METHOD_STACK_LIMIT + 50` 次成功调用全绿 ⇒ 成功路径每次把配额还到 0
     * （成功路径若漏，第 100 次就该抛 `OverflowFuncStack`）。
     */
    @Test
    fun `正常方法调用后配额每次归零`() {
        val rt = runtime()
        val store = rt.store
        repeat(METHOD_STACK_LIMIT + 50) { round ->
            assertEquals(
                Value.IntV(42),
                rt.invokeUserMethod("ADD", listOf(Value.IntV(41))),
                "第 ${round + 1} 次成功调用：返回值与配额都要正常",
            )
        }
        assertEquals(0, store.callDepth(), "方法帧必须全部配对退出")
    }
}

private const val INJECTED = "injected: 窗口内（methodDepth++ 之后 / try 之前）读取失败"

/**
 * [ProcessRuntime] 的 METHOD_STACK_LIMIT 声明在 **private companion object** 里
 * （ProcessRuntime.kt:5036-5037），测试拿不到符号，故照值复述；改上限必须同步这里。
 * spec: Process.cs:502-506（methodStack > 100 → OverflowFuncStack）。
 */
private const val METHOD_STACK_LIMIT = 100
