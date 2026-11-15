package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **SET 整型目的地的浮点右值 = 截断**（2026-11-14 语料换代 261111 校准；回归锁）。
 *
 * ## C# 锚（复刻目标 = 蛇版 4.981）
 *
 * * 解析期闸**只拒字符串**右值（`trerror.CanNotAssignStrToInt`，蛇版
 *   `ArgumentBuilder.cs:1147-1149`（单右值）/:1164-1166（批量）；浮点右值放行）；
 * * 执行期整型目的地 = `Term.GetIntValue(exm)`（`Instraction.Child.cs:696`），
 *   `SingleFloatTerm.GetIntValue` = `(long)fValue`（`Term.cs:117-120`，.NET cvttsd2si
 *   向零截断口径，移植侧统一走 `longFromScaledDouble`）；
 * * 双路径内建（`ROUND` 等带 `CanReturnFloat` 的）的 int 通道 = 本方法算完再转型，
 *   对本仓 compute-then-cast 面 ≡ 截断浮点结果。
 *
 * ## 语料现场
 *
 * `DLC/小游戏/锻税.ERB:3322`（261111 新增）：
 * `値 = ROUND(锻税_贡品基额 * POWER(1.4, ARG - 1))` —— `POWER(1.4, …)` 有浮点字面量
 * ⇒ `POWER` 按 `CanReturnFloat` 晋升 FLOAT ⇒ 乘式浮点 ⇒ `ROUND` 浮点实参返回
 * `FloatV` ⇒ 旧闸（`ArgBuilders` 的 `!term.isInteger()` 一刀切）在解析期判红、
 * 全量 strict 编译拒落盘。修复前 261017 语料 0 处此形态（编译 0/0），261111 起实算。
 *
 * 1.824 无浮点通道（`AExpression.GetIntValue` 直接 `return 0`），故本形态对 1.824
 * 不构成复刻义务；前波（浮点下标路）曾按「本仓红」处理语句位浮点右值，本波按蛇版
 * 4.981 语义校准为「放行 + 截断」，见 `Expr.kt` 浮点下标 KDoc 的订正段。
 */
class SetFloatRhsTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("IV", OperandType.INT, 1)
            .declareVariable("JW", OperandType.INT, 1)
            .declareVariable("IARR", OperandType.INT, 2),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "setfloat.erb", i + 1, text))
        }
    }

    /** 解析 → 编译 → 运行（装置与 `ExtraArgToleratedTest` 同源）。 */
    private fun runMain(lines: Array<String>, store: VariableStore) {
        val parsed = parse(*lines)
        val label = parsed.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        val collector = CallDefaultsCollector()
        collector.add("MAIN", label.paramDefaults)
        val compiler = ErbProgramCompiler(callDefaults = collector.build())
        compiler.compileFunction(
            FuncSig(name = "MAIN", arity = label.paramNames.size, paramNames = label.paramRefs),
            parsed,
            HashSet(),
            null,
        )
        val program: Program = compiler.build("MAIN")
        val metas = listOf(
            FuncMeta(
                index = 0, key = "MAIN", name = "MAIN",
                argIntCount = program.functions[0].arity,
            ),
        )
        val rt = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
        rt.execute()
    }

    private fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)

    /** 语料原句形态（锻税.ERB:3322 的数值替身）：`値 = ROUND(基额 * POWER(1.4, ARG-1))`。 */
    @Test
    fun `SET 整型目的地吃 ROUND 浮点返回按 C# 截断落库`() {
        val store = VariableStore()
        store.declareUserVar(intVar("IV"), "MAIN")
        runMain(arrayOf("@MAIN", "IV = ROUND(100 * POWER(1.4, 2))", "RETURN"), store)
        // 100 * 1.4^2 = 196.00000000000003 ⇒ ROUND = 196.0 ⇒ 整型通道截断 = 196
        assertEquals(196L, store.userVar("IV", "MAIN")!!.readInt(0, 0))
    }

    @Test
    fun `SET 浮点字面量右值截断含负数`() {
        val store = VariableStore()
        store.declareUserVar(intVar("IV"), "MAIN")
        runMain(arrayOf("@MAIN", "IV = 1.5", "IV = -1.5", "RETURN"), store)
        assertEquals(-1L, store.userVar("IV", "MAIN")!!.readInt(0, 0), "(long)(-1.5) = -1（向零截断）")
    }

    @Test
    fun `SET 字符串右值照旧判红`() {
        val store = VariableStore()
        store.declareUserVar(intVar("IV"), "MAIN")
        // SET 解析错误经 StmtParseException 包裹（语句层统一口径），病灶文案原样在内。
        val e = assertFailsWith<com.eratw.port.core.stmt.StmtParseException> {
            runMain(arrayOf("@MAIN", "IV = \"文字\"", "RETURN"), store)
        }
        assertTrue((e.message ?: "").contains("不能把字符串赋给整数变量"), e.message)
    }

    @Test
    fun `SET 批量右值表的浮点项同样截断`() {
        val store = VariableStore()
        store.declareUserVar(DimSpec("IARR", false, intArrayOf(2), isPrivate = true), "MAIN")
        runMain(arrayOf("@MAIN", "IARR = ROUND(0.5), 2.5", "RETURN"), store)
        // SpSetArrayArgument 批量形态：IARR:0 = ROUND(0.5)（AwayFromZero ⇒ 1），IARR:1 = (long)2.5 = 2
        assertEquals(1L, store.userVar("IARR", "MAIN")!!.readInt(0, 0), "ROUND(0.5) = 1（AwayFromZero）")
        // readInt(charPos, elem)：readInt(0, 1) = IARR:1
        assertEquals(2L, store.userVar("IARR", "MAIN")!!.readInt(0, 1), "(long)2.5 = 2（截断，非 ROUND）")
    }
}
