package com.eratw.port.core.sem

import com.eratw.port.core.err.clampWarnings
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpTimesArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TIMES 整数溢出（4.981 `Instraction.Child.cs:1435-1460`，2026-11-20 族②）。
 *
 * 从 [VariableStatementsTest] 拆出：语义归属不变，只因本笔新增用例把那个类推过了
 * detekt LargeClass 闸（不改阈值、不动 baseline）。
 *
 * 钉三件事：
 * 1. 两条路径越界都 `PrintWarning("TIMES整数溢出: {d}")`（4.981 :1447 / :1457）；
 * 2. 饱和方向按 `d > 0 ? MAX : MIN` —— 正溢出落 **MAX**（旧实现静默折 MIN，方向反了）；
 * 3. 域内不告警、行为不变。
 */
class TimesOverflowTest {

    private fun runAssign(env: SemTestEnv, dest: VarRef, v: Long) {
        env.run(
            AssignStmt(
                dest = dest,
                op = OperatorCode.ASSIGNMENT,
                args = SpSetArgument(iVar("DAY", 0), IntLiteral(v)),
                line = 1,
                raw = "",
            ),
        )
    }

    private fun eramakerEnv(): SemTestEnv {
        val env = SemTestEnv()
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            input = env.input,
            config = SemConfig(timesNotRigorousCalculation = true),
        )
        return env
    }

    /**
     * 默认（decimal）路：越界 ⇒ `PrintWarning("TIMES整数溢出: {d}")` + 按符号饱和。
     * 此前移植侧**正溢出也折成 Long.MIN_VALUE**、且零告警 —— 双重偏离。
     */
    @Test
    fun `TIMES 默认路正溢出告警并饱和到 MAX`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 3), Long.MAX_VALUE)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 2.0)))
        assertEquals(Long.MAX_VALUE, env.store.readInt("DAY", longArrayOf(3)))
        assertEquals(
            "TIMES整数溢出: 18446744073709551614",
            env.console.events.filter { it.startsWith("printError(") }.single()
                .removeSurrounding("printError(", ")"),
        )
        assertEquals(
            listOf("TIMES整数溢出: 18446744073709551614"),
            env.store.aux.clampWarnings().overflowLines(),
            "溢出一律落 overflow 桶供报告取证",
        )
    }

    @Test
    fun `TIMES 默认路负溢出告警并饱和到 MIN`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 3), Long.MIN_VALUE)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 2.0)))
        assertEquals(Long.MIN_VALUE, env.store.readInt("DAY", longArrayOf(3)))
        assertTrue(
            env.console.events.any { it.contains("TIMES整数溢出: -18446744073709551616") },
            "负溢出同样告警: ${env.console.events}",
        )
    }

    /**
     * eramaker 模拟路：`checked { (long)d }` 越界/NaN ⇒ OverflowException ⇒ 同句告警 + 饱和。
     * 正溢出（d > 0）落 MAX —— 旧实现特判成 MIN，方向反了。
     */
    @Test
    fun `TIMES eramaker 路正溢出告警并饱和到 MAX`() {
        val env = eramakerEnv()
        runAssign(env, iVar("DAY", 3), Long.MAX_VALUE)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 2.0)))
        assertEquals(Long.MAX_VALUE, env.store.readInt("DAY", longArrayOf(3)))
        assertTrue(
            env.console.events.any { it.contains("TIMES整数溢出: 1.8446744073709552E+19") },
            "double 路的告警按 .NET G 格式: ${env.console.events}",
        )
    }

    @Test
    fun `TIMES eramaker 路 NaN 告警并落 MIN`() {
        val env = eramakerEnv()
        runAssign(env, iVar("DAY", 3), 10L)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), Double.NaN)))
        // C# 的 `d > 0 ? MAX : MIN` 对 NaN 取 else 支 ⇒ MIN。
        assertEquals(Long.MIN_VALUE, env.store.readInt("DAY", longArrayOf(3)))
        assertTrue(env.console.events.any { it.contains("TIMES整数溢出: NaN") }, "${env.console.events}")
    }

    @Test
    fun `TIMES 域内不告警且行为不变`() {
        val env = SemTestEnv()
        runAssign(env, iVar("DAY", 3), 7L)
        env.run(instr(FunctionCode.TIMES, SpTimesArgument(iVar("DAY", 3), 0.5)))
        assertEquals(3L, env.store.readInt("DAY", longArrayOf(3)))
        assertTrue(env.console.events.none { it.contains("TIMES整数溢出") }, "域内不许告警: ${env.console.events}")
        assertTrue(env.store.aux.clampWarnings().overflowLines().isEmpty())
    }
}
