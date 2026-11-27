package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 稀疏 1 维背板在**语句层**的继承口径（QB-A1 第二步/第三步的回归锁）。
 *
 * 与 `vars/SparseArrayOorTest` 的分工：那边钉 `VarRef` 五路存取本身；
 * 这边钉「语句执行器把 SET / `+=` / `++` / 字符串写读交给 `VarRef` 五路」这一层接线。
 *
 * 反向证明（每条用例都成立）：把 `sem/StatementExecutor` 的赋值分支改回
 * `ctx.variables.writeInt(name, idx, value)`（绕过 `VarRef.setIntValue`），
 * 或撤掉 `expr/Expr.kt` 的 `lastIndexIsDynamic` 补径，本文件立刻变红 —— 报错文案
 * 与玩家 0.2.47 真机逐字一致（`数组变量"ITEM"的第1参数(-1)超出数组范围`）。
 *
 * 语义权威：`SparseArray.cs:25-40`（缺席读默认值、写落字典）、
 * `VariableTerm.cs:316-347`（解析期只对常量实参 CheckElement）——
 * 与 `SparseArrayOorTest` 的 KDoc 同源，不重复展开。
 */
class SparseArrayOorStatementTest {

    /**
     * `STR` 在 [com.eratw.port.core.stmt.LineParser] 里是保留字（`STR(...)` 转换函数，`LineParser.kt:1097`），
     * 不能当变量名；字符串通道改用同为内置 1 维的 `RESULTS`（`BuiltinSymbols.kt:161`）。
     */
    private fun parser() = LineParser(SymbolTable())

    private fun SemTestEnv.runLine(p: LineParser, text: String) {
        run(p.parseCodeLine(text, 1)!!)
    }

    /** 同一行在指定通道执行（`flat = true` 走 `execute(source, index, ctx)` 扁平通道）。 */
    private fun SemTestEnv.runOn(p: LineParser, flat: Boolean, text: String) {
        val stmt = p.parseCodeLine(text, 1)!!
        if (flat) runFlat(stmt) else run(stmt)
    }

    /** `DAY` 是 0 维内置标量：先写成 [value]，再拿它当动态下标（与 SparseArrayOorTest 同口径）。 */
    private fun SemTestEnv.setDay(p: LineParser, value: Long) {
        runLine(p, "DAY = $value")
    }

    // ---------------- SET 写 → 读回 ----------------

    @Test
    fun `语句 SET 动态越界写落覆盖层并可读回`() {
        val env = SemTestEnv()
        val p = parser()
        env.setDay(p, -1)
        env.runLine(p, "ITEM:DAY = 7")
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(7L, env.store.readInt("RESULT", longArrayOf(0)), "PC 实测 `ITEM:X = 7`（X=-1）后读回 7")
    }

    @Test
    fun `语句常量增量在越界槽上按缺省 0 累加`() {
        val env = SemTestEnv()
        val p = parser()
        env.setDay(p, -1)
        env.runLine(p, "ITEM:DAY += 5")
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(5L, env.store.readInt("RESULT", longArrayOf(0)), "缺省 0 + 5")
        env.runLine(p, "ITEM:DAY += 3")
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(8L, env.store.readInt("RESULT", longArrayOf(0)), "覆盖层是读后写回，不是 no-op")
    }

    @Test
    fun `语句自增自减在越界槽上折算正负一`() {
        val env = SemTestEnv()
        val p = parser()
        env.setDay(p, -1)
        env.runLine(p, "ITEM:DAY++")
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(1L, env.store.readInt("RESULT", longArrayOf(0)))
        env.runLine(p, "ITEM:DAY--")
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    // ---------------- 读路径 ----------------

    @Test
    fun `语句读路径动态越界返默认 0`() {
        val env = SemTestEnv()
        val p = parser()
        env.setDay(p, -1)
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)), "SparseArray 缺席读 = 默认值")
        env.setDay(p, 99999)
        env.runLine(p, "RESULT:0 = ITEM:DAY")
        assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    // ---------------- 字符串通道 ----------------

    @Test
    fun `语句字符串越界写能读回`() {
        val env = SemTestEnv()
        val p = parser()
        env.setDay(p, -1)
        env.runLine(p, "RESULTS:DAY = ab")
        env.runLine(p, "RESULTS:0 = %RESULTS:DAY%")
        assertEquals("ab", env.store.readStr("RESULTS", longArrayOf(0)), "PC 实测 `STR:X = \"ab\"` 后读回 ab")
        env.runLine(p, "RESULTS:DAY =")
        env.runLine(p, "RESULTS:0 = %RESULTS:DAY%")
        assertEquals("", env.store.readStr("RESULTS", longArrayOf(0)), "写默认值 = 删键")
    }

    // ---------------- 读改写类语句（QB-A1 遗留缺口：此前直读直写绕过 VarRef） ----------------

    /** 一段脚本在两条通道各跑一遍：末行把 `ITEM:DAY`（DAY = -1）读进 `RESULT:0`，其值须等于 [expected]。 */
    private fun bothChannelsOnSparseItem(script: List<String>, expected: Long) {
        for (flat in listOf(false, true)) {
            val env = SemTestEnv()
            val p = parser()
            env.setDay(p, -1)
            for (line in script) env.runOn(p, flat, line)
            env.runOn(p, flat, "RESULT:0 = ITEM:DAY")
            assertEquals(
                expected,
                env.store.readInt("RESULT", longArrayOf(0)),
                "$script on flat=$flat",
            )
        }
    }

    @Test
    fun `SETBIT 动态越界下标按缺省 0 置位`() {
        // C# 侧 SETBIT 经 `varTerm.GetIntValue/SetValue`（Instraction.Child.cs:778-807）⇒
        // 1 维 SparseArray 背板同样容忍运行期越界下标。第 2 参数是**位号**（0 基）。
        bothChannelsOnSparseItem(listOf("SETBIT ITEM:DAY, 0"), 1L)
        bothChannelsOnSparseItem(listOf("SETBIT ITEM:DAY, 0", "CLEARBIT ITEM:DAY, 0"), 0L)
        bothChannelsOnSparseItem(listOf("SETBIT ITEM:DAY, 1", "INVERTBIT ITEM:DAY, 0"), 3L)
    }

    @Test
    fun `SWAP 动态越界下标按稀疏背板互换`() {
        // ITEM:0 = 5，越界槽缺省 0 ⇒ 互换后越界槽得 5、ITEM:0 回 0。
        bothChannelsOnSparseItem(listOf("ITEM:0 = 5", "SWAP ITEM:DAY, ITEM:0"), 5L)
        for (flat in listOf(false, true)) {
            val env = SemTestEnv()
            val p = parser()
            env.setDay(p, -1)
            env.runOn(p, flat, "ITEM:0 = 5")
            env.runOn(p, flat, "SWAP ITEM:DAY, ITEM:0")
            env.runOn(p, flat, "RESULT:0 = ITEM:0")
            assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)), "被换回的槽写默认值 on flat=$flat")
        }
    }

    @Test
    fun `SWAP 字符串通道动态越界下标同样互换`() {
        for (flat in listOf(false, true)) {
            val env = SemTestEnv()
            val p = parser()
            env.runOn(p, flat, "RESULTS:0 = zz")
            env.setDay(p, -1)
            env.runOn(p, flat, "SWAP RESULTS:DAY, RESULTS:0")
            env.runOn(p, flat, "RESULTS:9 = %RESULTS:DAY%")
            assertEquals("zz", env.store.readStr("RESULTS", longArrayOf(9)), "越界槽读到被换入的 zz on flat=$flat")
        }
    }

    @Test
    fun `TIMES 动态越界下标按缺省 0 倍数落覆盖层`() {
        // 4 * 0.5 = 2（decimal 路径），落 ITEM:-1。
        bothChannelsOnSparseItem(listOf("ITEM:DAY = 4", "TIMES ITEM:DAY, 0.5"), 2L)
        bothChannelsOnSparseItem(listOf("TIMES ITEM:DAY, 3.0"), 0L)
    }

    // ---------------- 常量下标：语句层同样硬抛 ----------------

    @Test
    fun `语句常量下标越界仍抛`() {
        val env = SemTestEnv()
        val p = parser()
        val e = assertFailsWith<CodeEE> { env.runLine(p, "ITEM:99999 = 7") }
        assertEquals(
            "数组变量\"ITEM\"的第1参数(99999)超出数组范围",
            e.message,
            "常量下标在解析期就拦，语句层不得被稀疏补径吞掉",
        )
        assertFailsWith<CodeEE> { env.runLine(p, "RESULT:0 = ITEM:99999") }
    }

    // ---------------- 角色 1 维（与全局同背板） ----------------

    @Test
    fun `语句角色 1 维越界元素同样容忍`() {
        val env = SemTestEnv()
        val p = parser()
        env.store.characters.addBare()
        env.setDay(p, -1)
        env.runLine(p, "CFLAG:0:DAY = 5")
        env.runLine(p, "RESULT:0 = CFLAG:0:DAY")
        assertEquals(5L, env.store.readInt("RESULT", longArrayOf(0)), "CharacterData.cs:25 每角色 1 维同为 SparseArray")
    }
}
