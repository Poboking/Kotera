package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.ParamConsts
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **用户函数缺省实参默认值**（`@F(A, B = 2, C = "x")`）—— FIRST 素质屏「素质名全空」的回归锁。
 *
 * ## 现场（本测试要锁的产品缺陷）
 *
 * `ERB/ステータス表示関連/PRINT_STATE.ERB:474` 的
 * `LOCALS '= GET_TALENTNAME(LOCAL, TALENT:C_ID:LOCAL)` 只传 2 个实参，而定义是
 * `@GET_TALENTNAME(ARG , ARG:1 , ARG_ALL_GET = 0 , ARG_SW = ",")`
 * （`ERB/ステータス表示関連/PRINT_TALENTNAME.ERB:18`）。省略位拿不到默认值时，
 * `ARG_SW` 停在 `#DIMS ARG_SW` 的 `""`，函数体内的
 * `SPLIT L_TNAME , ARG_SW , L_SPLITSTR`（同文件 :369-375）整串切成 1 段，
 * `FINDELEMENT(...)` 找不到下标 ⇒ `RETURNF ""` ⇒ 素质名全空。
 *
 * ## 原版口径（逐条 `文件:行号`）
 *
 * * 装载期：每个形参项 = `[VariableTerm, SingleTerm 默认值]` 对
 *   （`ErbLoader.cs:646-760`；`label.Def = array2` 在 :752），默认值必须是常量
 *   （`Restructure` 后不是 `SingleTerm` ⇒ `ArgCanOnlyConst`，:764-767），
 *   且类型与形参一致（`:770-773`）。`= ` 后无表达式 = `NullTerm` ⇒ 按形参类型折成
 *   `0` / `""`（`:717-726`）。`FunctionLabelLine.Def` 字段声明在 `FunctionLabelLine.cs:49`。
 * * 调用期：`CalledFunction.ConvertArg` 先把实参表补到 `TopLabel.Arg.Length`（`:137`），
 *   逐位「缺失即取 `Def[i]`」（`:167`），无默认值且 `CompatiFuncArgOptional` 关才报
 *   `CanNotOmitArg`（`:168-172`）；随后 `UserDefinedFunctionArgument.cs:35-69` 在**调用方帧**
 *   按序求值全部实参，`ProcessState.cs:435-452` 逐位写回被调 `ARG`/`ARGS`。
 *
 * ## 本侧的落地形态（为什么不改 `.erbc`）
 *
 * 默认值在**编译期**补进实参表（[ErbProgramCompiler.callDefaults]），字节码里看到的
 * 就是完整实参 —— 容器无需新增段/版本位，运行期 `bindCallArgs` 的
 * `present` 位也因此为真，写回路径与「显式传了该实参」逐字相同。
 *
 * 本文件锁三件事：
 * 1. `@行` 解析出的逐位默认值（[FuncLabelStmt.paramDefaults]）：字面量收下、
 *    非字面量登记为不可注入；
 * 2. 语句位 `CALL` 家族的补位（1/2/3 个实参与显式空位 `,,`）；
 * 3. 表达式位用户方法调用（`#FUNCTIONS` 字符串通道）的补位 —— 即 FIRST 素质屏那条路。
 */
class DefaultArgTest {
    // ------------------------------------------------------------------
    // 1. 解析面：`@F(A, B = 2, C = "x")` 的逐位默认值
    // ------------------------------------------------------------------

    @Test
    fun `解析形参默认值 - 字面量收下非字面量登记不注入`() {
        val stmt = parse(
            "@F(PA , PB = 2 , PC = C_YELLOW)",
            "RETURN",
        ).filterIsInstance<FuncLabelStmt>().first()
        assertEquals(listOf("PA", "PB", "PC"), stmt.paramNames, "形参名（下标与默认值都要剥掉）")
        assertEquals(3, stmt.paramDefaults.size, "逐位默认值与形参等长")
        assertEquals(null, stmt.paramDefaults[0], "无 `=` 的位 = 无默认值")
        assertEquals(2L, (stmt.paramDefaults[1]!!.expr as? com.eratw.port.core.expr.IntLiteral)?.value)
        assertEquals(null, stmt.paramDefaults[2]!!.expr, "常量名不在 dimConsts 表里 → 不注入（登记 raw；在册的折叠见下一条用例）")
        assertEquals("C_YELLOW", stmt.paramDefaults[2]!!.raw.uppercase(), "原文供报告")

        val collector = CallDefaultsCollector()
        collector.add("F", stmt.paramDefaults)
        assertEquals(1, collector.unsupported.size, "非字面量默认值必须登记")
        val table = collector.build()["F"]!!
        assertEquals(3, table.size, "默认值表与形参等长")
        assertEquals(null, table[0], "无默认值的位不补")
        assertEquals(2L, (table[1] as? com.eratw.port.core.expr.IntLiteral)?.value, "可注入位 = 字面量常量")
        assertEquals(null, table[2], "非字面量位不注入（宁可不补也不猜）")
    }

    @Test
    fun `解析形参默认值 - 负数与十六进制`() {
        val stmt = parse(
            "@H(PA = -1 , PB = 0x10 , PY = \"x\")",
            "RETURN",
        ).filterIsInstance<FuncLabelStmt>().first()
        val values = stmt.paramDefaults.map { (it?.expr as? com.eratw.port.core.expr.IntLiteral)?.value }
        assertEquals(listOf(-1L, 16L, null), values, "一元负号/十六进制折叠成整数字面量；字符串走另一支")
        assertEquals("x", (stmt.paramDefaults[2]!!.expr as? com.eratw.port.core.expr.StrLiteral)?.value)
    }

    @Test
    fun `解析形参默认值 - 与形参类型不符时不注入`() {
        // PC 是整型形参、默认值给字符串：原版装载期即 NotMatchTypeArgAndInitialValue
        // （ErbLoader.cs:770-773）；本侧只登记不注入（补进去运行期也会被按位绑定跳过）。
        val stmt = parse("@T(PC = \"x\")", "RETURN").filterIsInstance<FuncLabelStmt>().first()
        assertEquals(null, stmt.paramDefaults.single()!!.expr)
    }

    @Test
    fun `解析形参默认值 - 常量名按 dimConsts 折叠，表里没有才不注入`() {
        // 原版 `def = term.Restructure(exm) as SingleTerm`（ErbLoader.cs:724-727）：
        // `#DIM CONST` 名在装载期就折成它的值，不是「非字面量一律不注入」。
        val consts = mapOf("MATD_空" to -1L, "MATD_泛用" to -2L)
        val stmt = parse(
            listOf("@F(PA = MATD_空 , PB = MATD_泛用 , PC = MATD_未在册)", "RETURN"),
            consts,
        ).filterIsInstance<FuncLabelStmt>().first()
        val values = stmt.paramDefaults.map { (it?.expr as? com.eratw.port.core.expr.IntLiteral)?.value }
        assertEquals(
            listOf(-1L, -2L, null), values,
            "在册常量名折成其值；不在册的保持不注入（原版此处判错，本侧宁缺不猜）",
        )
        assertEquals("MATD_未在册", stmt.paramDefaults[2]!!.raw, "折不开的原文仍登记，由装载报告带出去")
    }

    @Test
    fun `解析形参默认值 - 常量名的算术式同样折叠`() {
        val consts = mapOf("MATD_空" to -1L, "BASE" to 100L)
        val stmt = parse(
            listOf("@F(PA = BASE + MATD_空 , PB = -MATD_空)", "RETURN"),
            consts,
        ).filterIsInstance<FuncLabelStmt>().first()
        val values = stmt.paramDefaults.map { (it?.expr as? com.eratw.port.core.expr.IntLiteral)?.value }
        assertEquals(listOf(99L, 1L), values, "算术式经 DimSizeEvaluator（与 #DIM 尺寸同一真源）折叠")
    }

    // ------------------------------------------------------------------
    // 1b. 语料现场：`ARG:n = <#DIM CONST>` 的省略位（2026-11-16「做菜报错」）
    // ------------------------------------------------------------------

    /**
     * 语料 `@材料设定D(ARG:1 = MATD_未指定, ARG:2..10 = MATD_空)` 的最小件
     * （`ERB/コマンド関連/COMF/日常系/DISHDATA.ERB:1534` + `ERB/Headers/MATERIAL_TAGS.ERH`）。
     * 把 ARG:1..3 抄进全局 `OUT`，读回即见省略位到底拿到什么。
     */
    private val fMatd = Fn(
        "F_MATD",
        arrayOf(
            "@F_MATD(ARG:1 = MATD_未指定 , ARG:2 = MATD_空 , ARG:3 = MATD_空)",
            "#DIM OUT, 4",
            "OUT:1 = ARG:1",
            "OUT:2 = ARG:2",
            "OUT:3 = ARG:3",
            "RETURN",
        ),
    )

    private fun runMatd(callLine: String): List<Long> {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")),
            fMatd,
            dimConsts = mapOf("MATD_未指定" to -7L, "MATD_空" to -1L),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(4)))
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        return (1L..3L).map { store.readInt("OUT", longArrayOf(it)) }
    }

    @Test
    fun `CALL 省略 ARGn 位 - 取 #DIM CONST 默认值而不是 0`() {
        // 0 在道具数组里是**真实道具号**（跳蛋），把哨兵折成 0 会一路污染 DISH_ING：
        // 材料判定按「持有跳蛋」成立 → SELECT_ING 被 COM413 的「0 番はローターなので」
        // 修整复位成 -1 → `ITEM[-1]` 抛「数组变量"ITEM"的第1参数(-1)超出数组范围」。
        assertEquals(listOf(193L, -1L, -1L), runMatd("CALL F_MATD(193)"))
    }

    @Test
    fun `CALL 显式传负数 - 默认值不得覆盖实参`() {
        assertEquals(listOf(-2L, -1L, 5L), runMatd("CALL F_MATD(-2 , , 5)"))
    }

    // ------------------------------------------------------------------
    // 2. 语句位 `CALL`：1 / 2 / 3 个实参 + 显式空位
    // ------------------------------------------------------------------

    /** `@F(PA, PB = 2, PC = 3)` 把 `PA*100+PB*10+PC` 写进私有 `OUT`。 */
    private val fInt = Fn(
        "F",
        arrayOf(
            "@F(PA , PB = 2 , PC = 3)",
            "#DIM OUT",
            "OUT = PA * 100 + PB * 10 + PC",
            "RETURN",
        ),
    )

    private fun runCall(callLine: String): Long {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")),
            fInt,
        )
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        return store.userVar("OUT", "F")!!.readInt(0, 0)
    }

    @Test
    fun `CALL 省略两个实参 - 被省略位取默认值`() {
        assertEquals(123L, runCall("CALL F(1)"), "PA=1、PB=2、PC=3 全部到位（CalledFunction.cs:167）")
    }

    @Test
    fun `CALL 省略一个实参 - 只有末位取默认值`() {
        assertEquals(153L, runCall("CALL F(1 , 5)"), "PA=1、PB=5、PC=默认 3")
    }

    @Test
    fun `CALL 全给实参 - 默认值不覆盖显式值`() {
        assertEquals(157L, runCall("CALL F(1 , 5 , 7)"), "三位都是显式值")
    }

    @Test
    fun `CALL 显式空位 - 空位取默认值其余位不受影响`() {
        assertEquals(123L, runCall("CALL F(1 , , 3)"), "PB 空位取默认 2（原版 Def[i] 顶替后再按位写回）")
    }

    // ------------------------------------------------------------------
    // 3. 表达式位用户方法调用（`#FUNCTIONS` 字符串通道）= FIRST 素质屏那条路
    // ------------------------------------------------------------------

    /** `@GET_TALENTNAME` 的合成形态：默认 `ARG_SW = ","`，实参不足两位时返回它。 */
    private val fTalentName = Fn(
        "GET_TALENTNAME",
        arrayOf(
            "@GET_TALENTNAME(ARG , ARG:1 , ARG_ALL_GET = 0 , ARG_SW = \",\")",
            "#FUNCTIONS",
            "#DIM ARG_ALL_GET",
            "#DIMS ARG_SW",
            "IF ARG_ALL_GET != 0",
            "\tRETURNF \"BAD\"",
            "ENDIF",
            "RETURNF ARG_SW",
        ),
        returnsStr = true,
        exprMethod = true,
    )

    private fun runTalentName(expr: String): String {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "OUTS '= $expr", "RETURN")),
            fTalentName,
        )
        val store = VariableStore()
        store.declareUserVar(strVar("OUTS"), "MAIN")
        store.declareUserVar(intVar("ARG_ALL_GET"), "GET_TALENTNAME")
        store.declareUserVar(strVar("ARG_SW"), "GET_TALENTNAME")
        run(pb, store)
        return store.userVar("OUTS", "MAIN")!!.readStr(0, 0)
    }

    @Test
    fun `表达式位方法调用省略实参 - ARG_SW 取默认逗号`() {
        assertEquals(
            ",", runTalentName("GET_TALENTNAME(1 , 2)"),
            "FIRST 素质屏现场：SPLIT 依赖 ARG_SW 的默认值（PRINT_TALENTNAME.ERB:18）",
        )
    }

    @Test
    fun `表达式位方法调用省略末位 - 中间的默认值同样生效`() {
        assertEquals(",", runTalentName("GET_TALENTNAME(1 , 2 , 0)"), "ARG_ALL_GET 显式 0、ARG_SW 省略")
    }

    @Test
    fun `表达式位方法调用全给实参 - 默认值不覆盖显式值`() {
        assertEquals(";", runTalentName("GET_TALENTNAME(1 , 2 , 0 , \";\")"), "第 4 位显式分号")
        assertEquals("BAD", runTalentName("GET_TALENTNAME(1 , 2 , 2)"), "第 3 位显式 2 → 走非 0 分支")
    }

    // ------------------------------------------------------------------
    // 4. 动态调用（CALLFORM / TRYCALLFORM / TRYCCALLFORM）：名字是运行期表达式
    // ------------------------------------------------------------------

    @Test
    fun `CALLFORM 带引号名字后的逗号实参必须解析出来`() {
        // 2026-11-17 实测缺陷：带引号的名字分支补闭引号后 `st` 停在空白上，terminator 判成
        // 非 `,` ⇒ 整段实参不解析、调用点 argc=0（静默丢实参）。原版走词法 token 层，
        // 空白在 ShiftNext 之前已被吞（ArgumentParser.cs:802-875 的 SP_CALLFORM 分支）。
        val stmts = parse("@MAIN", "CALLFORM \"F\" , 1 , 5 , 7", "RETURN")
        val st = stmts.filterIsInstance<com.eratw.port.core.stmt.CallStmt>().first()
        val arg = st.args as com.eratw.port.core.stmt.SpCallArgument
        assertEquals(3, arg.rowArgs.size, "引号名字后的三个实参都要进 rowArgs")
    }

    @Test
    fun `CALLFORM 显式三位 - 实参全部到位（引号名字后的逗号形）`() {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", "CALLFORM \"F\" , 1 , 5 , 7", "RETURN")), fInt)
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store, emptyMap())
        assertEquals(157L, store.userVar("OUT", "F")!!.readInt(0, 0), "PA=1、PB=5、PC=7")
    }

    @Test
    fun `CALLFORM 全省略实参 - 运行期按被调定义补默认值`() {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", "CALLFORM \"F\"", "RETURN")), fInt)
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store, pb.defaults)
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        assertEquals(
            23L, store.userVar("OUT", "F")!!.readInt(0, 0),
            "PA 无默认值 ⇒ 隐式 0；PB=2、PC=3：原版按被调 CalledFunction.Def[i] 逐位顶替 " +
                "（CalledFunction.cs:167），与调用点是静态名还是 CALLFORM 无关",
        )
    }

    @Test
    fun `CALLFORM 负控 - 不交默认值表时省略位仍停在 0`() {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", "CALLFORM \"F\"", "RETURN")), fInt)
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store, emptyMap())
        assertEquals(0L, store.userVar("OUT", "F")!!.readInt(0, 0), "空表 = 旧行为（全 0）")
    }

    @Test
    fun `CALLFORM 部分省略 - 已给实参不被覆盖`() {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", "CALLFORM \"F\" , 1 , 5", "RETURN")), fInt)
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store, pb.defaults)
        assertEquals(153L, store.userVar("OUT", "F")!!.readInt(0, 0), "PA=1、PB=5、PC=默认 3")
    }

    @Test
    fun `TRYCCALLFORM 省略实参 - 同样取默认值`() {
        val pb = compile(
            Fn(
                "MAIN",
                arrayOf(
                    "@MAIN",
                    "TRYCCALLFORM \"F\" , 1",
                    "CATCH",
                    "PRINTL MISS",
                    "ENDCATCH",
                    "RETURN",
                ),
            ),
            fInt,
        )
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store, pb.defaults)
        assertEquals(123L, store.userVar("OUT", "F")!!.readInt(0, 0), "PA=1、PB=默认 2、PC=默认 3")
    }

    @Test
    fun `CALLFORM 无引号格式化名字 - 语料真实形态的逗号实参不受影响`() {
        // 语料真实形态（`TRYCCALLFORM %L_ELEMENT:0% , CHRLIST:CNT_CHR`、`CALLFORM NAME_%X%(…)`）：
        // 名字走 analyseFormattedString，游标本来就会停到 `,`/`(` ⇒ 我加的 skipWhiteSpace 必须中性。
        val st = parse("@MAIN", "TRYCCALLFORM %FNAME% , 1 , 5", "CATCH", "ENDCATCH", "RETURN")
            .filterIsInstance<com.eratw.port.core.stmt.CallStmt>().first()
        val arg = st.args as com.eratw.port.core.stmt.SpCallArgument
        assertEquals(2, arg.rowArgs.size, "格式化名字后的两个逗号实参仍要进 rowArgs")
        assertEquals(0, arg.subNames.size, "没有 `[` 子名段")
    }

    @Test
    fun `CALLFORM 字符串默认值位 - 被调定义里的 ARGS 默认值已折成字面量`() {
        // 运行期字符串绑定与整型走同一条 [bindOmittedArgDefault] 通道（上面整型用例已覆盖），
        // 这里锁「默认值表里字符串位确实带着 StrLiteral」这一半。
        val stmt = parse(
            "@GS(ARG:1 , ARGS:1 = \",\")",
            "RETURN",
        ).filterIsInstance<FuncLabelStmt>().first()
        assertEquals(
            ",", (stmt.paramDefaults[1]?.expr as? com.eratw.port.core.expr.StrLiteral)?.value,
            "ARGS 默认值折成字面量才会进 param-defaults 侧车",
        )
    }
}
