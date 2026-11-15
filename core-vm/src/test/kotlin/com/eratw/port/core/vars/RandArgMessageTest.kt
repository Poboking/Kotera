package com.eratw.port.core.vars

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.clampWarnings
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RAND 伪变量上界非正的**钳制语义**（4.981 12.2.0，`CHANGELOG.md:105-112`）。
 *
 * 2026-11-12 版本漂移订正：本类原先钉的是 1.824 `VariableData.cs:846-851` 的
 * `num <= 0 → throw RandArgIsNegative`（`emuera-zhs.xml:376`）。复刻目标蛇版 4.981
 * 的 `VariableToken.cs:1413-1436` RandToken 已改成「钳制返回 0 + 首次 PrintError 一次，
 * 之后静默」，**不再中断脚本**。真机上报 `QOL_GATHERING_DRAWING 行69` /
 * `M_KOJO_EVENT_K70_31 行6087` 的 `RAND不能指定0以下的值(0)` 之所以变成玩家可见的
 * 报错弹窗，是移植侧仍停在 1.824 语义 —— 引擎侧缺陷，不是语料侧问题。
 *
 * ## 反向证明（本轮**不**改的两条）
 * * 解析期常量字面量 `RAND:0` 仍抛「RAND有一个参数为0」：4.981
 *   `VariableParser.cs:160-168` 保留该硬判（`Error.RandArgIsZero`），与运行期钳制是两件事；
 * * `CompatiRandToken`（`VariableToken.cs:1438+`）的归一化通道另有语义，见
 *   `CompatiRandKeyTest`，本轮不碰 `config.compatiRand` 分支。
 */
class RandArgMessageTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("G0", OperandType.INT, 1)
                .declareFunction("MAIN", OperandType.INT),
        )
        return lines.mapIndexedNotNull { i, text ->
            p.parseLogicalLine(
                when {
                    text.startsWith("@") -> LogicalLine(LineKind.FUNCTION_LABEL, "rand.erb", i + 1, text)
                    else -> LogicalLine(LineKind.CODE, "rand.erb", i + 1, text)
                },
            )
        }
    }

    /** 装一个只含 MAIN 的程序：`G0:0 = RAND:<expr>`，bound 表达式由 [boundExpr] 给出。 */
    private fun compileRand(boundExpr: String): Pair<ProcessRuntime, VariableStore> {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), parse("@MAIN", "G0:0 = RAND:$boundExpr"), setOf("MAIN"))
        val metas = listOf(
            FuncMeta(
                index = c.functions.first { it.sig.name.uppercase() == "MAIN" }.index,
                key = "MAIN", name = "MAIN", position = "rand.erb",
            ),
        )
        val store = VariableStore().apply {
            declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1)))
            randProvider = RandProvider { bound -> bound - 1 }
        }
        return ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()) to store
    }

    @Test
    fun `字面 RAND 0 仍在解析期被拒`() {
        // 反向证明：4.981 只放宽**运行期** RandToken，没动 VariableParser.cs:160-168 的常量闸。
        val e = runCatching { compileRand("0") }.exceptionOrNull()
        assertTrue(e is com.eratw.port.core.stmt.StmtParseException, "RAND:0 字面量必须在解析期报错，实际 ${e?.javaClass?.name}")
        // StmtParseException 的 message 带「语句解析错误(行…)」包装前缀，按既有测试口径 contains 断言。
        assertTrue(e.message!!.contains("RAND有一个参数为0"), e.message)
    }

    @Test
    fun `运行期 bound 0 钳制为零且首次留一条告警`() {
        // RESULT 是变量引用、解析期常量闸看不见它；初值 0 正好走运行期钳制路径。
        val (rt, store) = compileRand("RESULT")
        val t = runCatching { rt.execute() }.exceptionOrNull()
        assertEquals(null, t, "4.981 12.2.0：RAND:0 不再中断脚本，实际 ${t?.javaClass?.name}: ${t?.message}")
        assertEquals(0L, store.readInt("G0", longArrayOf(0)), "钳制返回下界 0")
        assertEquals(
            listOf("RAND不能指定0以下的值(0)（已钳制为 0，不再中断运行）"),
            store.aux.clampWarnings().lines(),
            "首次触发留一条官方原文 + 4.981 追加说明",
        )
    }

    @Test
    fun `运行期负上界同样钳制为零且告警带原始值`() {
        val (rt, store) = compileRand("G0")
        store.writeInt("G0", longArrayOf(0), -3)
        val t = runCatching { rt.execute() }.exceptionOrNull()
        assertEquals(null, t, "RAND:-3 不再中断脚本，实际 ${t?.javaClass?.name}: ${t?.message}")
        assertEquals(0L, store.readInt("G0", longArrayOf(0)), "负上界也钳制为 0，不是返回 bound 本身")
        assertEquals(
            listOf("RAND不能指定0以下的值(-3)（已钳制为 0，不再中断运行）"),
            store.aux.clampWarnings().lines(),
        )
    }

    @Test
    fun `非法上界不消耗随机数序列`() {
        // 与 Creator.Method.cs:3827 / VariableToken.cs:1432 同序：先判非法、直接 return，
        // 不调 GetNextRand —— 否则后续抽取整条序列漂移，是最容易写坏的隐形副作用。
        val (rt, store) = compileRand("RESULT")
        val calls = ArrayList<Long>()
        store.randProvider = RandProvider { bound -> calls.add(bound); bound - 1 }
        rt.execute()
        assertTrue(calls.isEmpty(), "钳制路径不得调 randProvider：${calls}")
    }

    @Test
    fun `RAND 正常上界不受钳制改动影响`() {
        val (rt, store) = compileRand("5")
        val t = runCatching { rt.execute() }.exceptionOrNull()
        assertEquals(null, t, "RAND:5 在桩（返回 bound-1）下应正常落 4，不抛")
        assertEquals(4L, store.readInt("G0", longArrayOf(0)))
        assertTrue(store.aux.clampWarnings().lines().isEmpty(), "合法上界不得产生告警")
    }
}
