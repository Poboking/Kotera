package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.EXPR_TEMP_PREFIX
import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 运行期错误的**文件维度**（`CodeEE.scriptFile`）：
 * [com.eratw.port.core.code.VmHost.scriptFileOf] 在**抛点帧**盖章（MODERN-R8-A），语句层捕获点
 * [ProcessRuntime.stampPosition] 只给「无位置」的语句层自抛错误重建位置与文件，不再猜帧。
 * 供 `ErrorReport.scriptLocationOf` 在报告正文写出「函数名 文件 行N」。
 *
 * 屏上面不读这一维（`GameHost.scriptLocation` 自带 formatter），本测试只钉 core 的字段与通道：
 * 注入通道优先、帧下标通道兜底、合成入口不冒充文件、首写为准（内层帧先盖、外层不覆盖）、
 * 跨帧错误带**抛点帧**文件（抛点帧无文件时宁 null，不拿调用帧的文件顶替）。
 */
class ErrorFileStampTest {

    private fun runtime(
        program: Program,
        metas: List<FuncMeta> = emptyList(),
        sourceFileOf: ((String) -> String?)? = null,
    ): ProcessRuntime = ProcessRuntime(
        program,
        VariableStore(),
        FunctionTable(metas),
        VmConfig(),
        sourceFileOf = sourceFileOf,
    )

    /** 与 [ExprErrorStampTest] 同款：带 functionName/line/pc 的只读 StmtEnv（盖章素材）。 */
    private class Env(
        override val line: Int,
        override val functionKey: String = "UPDATE_MAIN_DRAWLINE_SECTION",
    ) : StmtEnv {
        override val pc: Int = 12
        override val functionName: String = functionKey
    }

    private fun printForm(line: Int, term: FormPart.Embedded): Statement {
        val spec = PrintSpec.parse(FunctionCode.PRINTFORM)
            ?: error("PRINTFORM 必须能解析出 PrintSpec")
        return PrintStmt(
            spec,
            ExpressionArgument(FormStringTerm(listOf(FormPart.Text("x"), term), line)),
            line,
            "PRINTFORM x",
        )
    }

    /** `PRINTFORM x%"*" * -3%` —— 内嵌字符串乘法，乘数为负（表达式层抛 ExprEvalException）。 */
    private fun badPrintForm(line: Int): Statement = printForm(
        line,
        FormPart.Embedded(
            Binary(OperatorCode.MULT, StrLiteral("*"), IntLiteral(-3), OperandType.STR, line),
            FormConversion.Percent(),
        ),
    )

    /** `PRINTFORM x{B()}` —— 表达式位调用户方法 B（求值点重入建帧）。 */
    private fun callMethodPrintForm(line: Int): Statement = printForm(
        line,
        FormPart.Embedded(FuncCall("B", emptyList(), OperandType.INT, line), FormConversion.Curly()),
    )

    @Test
    fun `注入通道给语句层错误盖源文件`() {
        val rt = runtime(
            Assembler.assemble("_HALT"),
            sourceFileOf = { key -> if (key == "UPDATE_MAIN_DRAWLINE_SECTION") "ERB/SUB/A.ERB" else null },
        )
        val e = assertFailsWith<CodeEE> { rt.executeStatement(badPrintForm(357), Env(357)) }
        // 文件维度是新增字段；既有三件套（函数名/0 基行/pc）与文案逐字不变。
        assertEquals("ERB/SUB/A.ERB", e.scriptFile)
        assertEquals("UPDATE_MAIN_DRAWLINE_SECTION", e.funcName)
        assertEquals(357, e.line)
        assertEquals(12, e.pc)
        assertEquals("字符串无法与负值(-3)进行相乘运算", e.message)
    }

    @Test
    fun `帧下标通道给语句层错误盖源文件`() {
        val program = Assembler.assemble(
            """
            .func MAIN slots=1
              PRINTFORM stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(badPrintForm(2)),
        )
        // 不注入文件表：走 FunctionTable.sourceFileOfIndex（帧下标 → meta.position）。
        val rt = runtime(program, listOf(FuncMeta(index = 0, key = "MAIN", position = "ERB/TITLE.ERB:5")))
        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("ERB/TITLE.ERB", e.scriptFile)
        assertEquals("MAIN", e.funcName)
    }

    @Test
    fun `合成入口与空串不冒充源文件`() {
        for (bogus in listOf("", "·event-entry·")) {
            val rt = runtime(
                Assembler.assemble("_HALT"),
                sourceFileOf = { _ -> bogus },
            )
            val e = assertFailsWith<CodeEE> { rt.executeStatement(badPrintForm(357), Env(357)) }
            assertNull(e.scriptFile, "「$bogus」不是源文件，不许盖上")
            assertEquals("UPDATE_MAIN_DRAWLINE_SECTION", e.funcName)
            assertEquals(357, e.line)
        }
    }

    @Test
    fun `首写为准 内层帧的文件不被外层帧覆盖`() {
        val program = Assembler.assemble(
            """
            .func MAIN slots=1
              PRINTFORM stmt#0
              _HALT
            .func B slots=1
              PRINTFORM stmt#1
              _RET
            """.trimIndent(),
            statements = listOf(callMethodPrintForm(2), badPrintForm(5)),
        )
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN", position = "ERB/A.ERB:1"),
            FuncMeta(index = 1, key = "B", name = "B", isMethod = true, position = "ERB/B.ERB:9"),
        )
        val rt = runtime(
            program,
            metas,
            sourceFileOf = { key ->
                when (key) {
                    "MAIN" -> "ERB/A.ERB"
                    "B" -> "ERB/B.ERB"
                    else -> null
                }
            },
        )
        val e = assertFailsWith<CodeEE> { rt.execute() }
        // B 内的语句错先在内层 executeCore 盖章；穿到 MAIN 的 PRINTFORM 捕获点时
        // e.scriptFile 已非 null ⇒ 不覆盖（若重建，funcName 会变 MAIN、文件会变 A）。
        assertEquals("B", e.funcName)
        assertEquals("ERB/B.ERB", e.scriptFile)
    }

    /**
     * 已带位置、且**抛点当场就带文件**的 VM 自抛错误（`Vm.kt` 的 `errorAt`，经
     * `VmHost.scriptFileOf` 取当前帧文件）：语句层捕获点不再补任何东西，字段原样透出。
     *
     * 触发方式：`PRINTFORM x%#EXPR_RET#5%`（返回值哨兵，`ProcessRuntime.kt:803-806`）在只有 1 个
     * 局部槽的帧里读 R5 ⇒ `Vm.getInt` 的槽越界守卫走 `errorAt`；此时帧就是 MAIN，文件由
     * 抛点直接挂上。MODERN-R8-A 之前这条靠语句层「同帧才补」的启发式补文件，断言值不变。
     */
    @Test
    fun `同帧的已定位VM自抛错误带抛点帧文件`() {
        val program = Assembler.assemble(
            """
            .func MAIN slots=1
              PRINTFORM stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(
                printForm(
                    2,
                    FormPart.Embedded(
                        FuncCall("${EXPR_TEMP_PREFIX}5", emptyList(), OperandType.INT, 2),
                        FormConversion.Percent(),
                    ),
                ),
            ),
        )
        val metas = listOf(FuncMeta(index = 0, key = "MAIN", position = "ERB/MAIN.ERB:1"))
        val e = assertFailsWith<CodeEE> { runtime(program, metas).execute() }
        assertEquals("MAIN", e.funcName)
        assertEquals(2, e.line)
        assert(e.pc >= 0) { "VM 自抛错应带 pc，实际 ${e.pc}" }
        assertEquals("ERB/MAIN.ERB", e.scriptFile)
        assertEquals(true, (e.message ?: "").contains("槽越界"), "应是槽越界守卫抛出：${e.message}")
    }

    /**
     * 跨帧形态：`_EVAL_INT` 的除算溢出（`MinValue / -1`）在**内层帧 B** 由 `Vm.errorAt`
     * 构造、不经 B 的语句层捕获（非语句 marker 指令），唯一捕获点是调用帧 MAIN 的 PRINTFORM。
     *
     * 2026-11-12 探针替换：原先借整数零除抛 `DivideByZero`，但 4.981
     * `SafeArithmetic.cs:67-77` 已把它改成钳制返回 0、不再抛错。改用**同通道仍会抛**的
     * `long.MinValue / -1`（.NET 唯一溢出整除，本轮未改），「VM 自抛错误补函数/文件现场」
     * 这条验收面因此完整保留，不是把用例删掉了事。
     *
     * MODERN-R8-A 起文件由**抛点帧 B** 自带（原版口径：位置挂在异常自己身上，`CodeEE.cs:5-8` /
     * `EmueraException.cs:7-13`，上屏时优先于捕获点那一行，`Process.cs:598-606`）：
     * 报告正文应是「B ERB/B.ERB 行N」，不是 MAIN 的 `ERB/A.ERB`。
     */
    @Test
    fun `跨帧的VM自抛错误带抛点帧的文件`() {
        val e = runCrossFrame(bPosition = "ERB/B.ERB:4")
        assertEquals("B", e.funcName)
        assertEquals("除算溢出：-9223372036854775808 / -1", e.message)
        assertEquals("ERB/B.ERB", e.scriptFile, "文件属于抛点帧 B，不是调用帧 MAIN 的 ERB/A.ERB")
        val location = assertNotNull(ErrorReport.scriptLocationOf(e))
        assertTrue(location.contains("B ERB/B.ERB 行"), "报告正文应是「B ERB/B.ERB 行N」：$location")
    }

    /**
     * 与上一例同装置，只把 B 的 `FuncMeta.position` 置空（合成入口 / 无侧车的形态）：
     * 抛点帧没有文件 ⇒ 宁 null，**不得**回落成调用帧 MAIN 的 `ERB/A.ERB`（那会写成错的文件）。
     */
    @Test
    fun `抛点帧没有文件时宁 null 不盖调用帧文件`() {
        val e = runCrossFrame(bPosition = "")
        assertEquals("B", e.funcName)
        assertEquals("除算溢出：-9223372036854775808 / -1", e.message)
        assertNull(e.scriptFile, "抛点帧 B 没有源文件 ⇒ null，不得拿调用帧 MAIN 的文件顶替")
    }

    /**
     * 跨帧装置的公共骨架：MAIN 的 `PRINTFORM` 在表达式位调方法 B，B 的 `_EVAL_INT`
     * 触发 `MinValue / -1` 除算溢出（4.981 后整数零除已钳制，见上方说明）。
     *
     * @param bPosition B 的 `FuncMeta.position`（`文件:行号`；空串 = 抛点帧没有文件）
     */
    private fun runCrossFrame(bPosition: String): CodeEE {
        val program = Assembler.assemble(
            """
            .func MAIN slots=1
              PRINTFORM stmt#0
              _HALT
            .func B slots=1
              _EVAL_INT ${'$'}0, R0
              _RET
            """.trimIndent(),
            exprs = listOf(
                Binary(
                    OperatorCode.DIV,
                    IntLiteral(Long.MIN_VALUE),
                    IntLiteral(-1L),
                    OperandType.INT,
                    3,
                ),
            ),
            statements = listOf(callMethodPrintForm(2)),
        )
        val metas = listOf(
            FuncMeta(index = 0, key = "MAIN", position = "ERB/A.ERB:1"),
            FuncMeta(index = 1, key = "B", name = "B", isMethod = true, position = bPosition),
        )
        return assertFailsWith<CodeEE> { runtime(program, metas).execute() }
    }
}
