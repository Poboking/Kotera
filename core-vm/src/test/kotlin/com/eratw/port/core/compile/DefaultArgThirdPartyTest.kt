package com.eratw.port.core.compile

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **第三方 Mod 作者面**的形参默认值：`ARGF` 浮点默认值、`#DIMF` 浮点私有形参、
 * 浮点常量名默认值、浮点实参位型借道 —— 共用 [DefaultArgFixtures] 的装置。
 *
 * 判词一律来自 4.981 桌面宿主（`--CliDebug`，`1824+v24+EMv18+EEv56+Skiav16`），
 * 逐条写在相应用例的注释里。
 */
class DefaultArgThirdPartyTest {
    // ------------------------------------------------------------------
    // 5. 第三方 Mod 面：浮点默认值（ARGF）+ 字符串常量名默认值（ARGS:1 = ERH CONST）
    //    原版 PC 实测（`1824+v24+EMv18+EEv56+Skiav16`，详见探针记录）：
    //    `@F(ARGF:1 = 2.5)` + `CALL F()` ⇒ defaultF=2.5；
    //    `@F(ARGS:1 = MYSTR)`（`#DIMS CONST MYSTR = "abc"` 在 ERH）⇒ defaultStr=[abc]。
    //    本侧三处落地：LineParser 折 FloatLiteral / strConsts 查表、
    //    padOmittedArgs 跳浮点位、ProcessRuntime ARGF 分支写浮点局部。
    // ------------------------------------------------------------------

    @Test
    fun `解析形参默认值 - 浮点字面量折成 FloatLiteral`() {
        val stmt = parse("@F(ARGF:1 = 2.5)", "RETURN")
            .filterIsInstance<FuncLabelStmt>().first()
        assertEquals(
            2.5, (stmt.paramDefaults.single()?.expr as? FloatLiteral)?.value,
            "浮点默认值经 Restructure 折成 FloatLiteral（进 param-defaults 侧车）",
        )
    }

    @Test
    fun `解析形参默认值 - 字符串常量名按 strConsts 折成 StrLiteral`() {
        val stmt = parse(
            listOf("@G(ARGS:1 = MYSTR)", "RETURN"),
            strConsts = mapOf("MYSTR" to "abc"),
        ).filterIsInstance<FuncLabelStmt>().first()
        assertEquals(
            "abc", (stmt.paramDefaults.single()?.expr as? StrLiteral)?.value,
            "ERH #DIMS CONST MYSTR 预扫进 strConsts 后折成字面量",
        )
    }

    @Test
    fun `解析形参默认值 - 字符串常量名未登记不注入`() {
        val stmt = parse("@G(ARGS:1 = MYSTR)", "RETURN")
            .filterIsInstance<FuncLabelStmt>().first()
        assertEquals(null, stmt.paramDefaults.single()?.expr, "strConsts 空表 = 字符串常量名不折")
        assertEquals("MYSTR", stmt.paramDefaults.single()!!.raw, "原文仍登记，由装载报告带出")
    }

    /** `@F_ARGF(ARG , ARGF:1 = 2.5)`：省略的浮点形参位乘 100 写进 OUT:1 便于整型读回。 */
    private val fArgf = Fn(
        "F_ARGF",
        arrayOf(
            "@F_ARGF(ARG , ARGF:1 = 2.5)",
            "#DIM OUT, 4",
            "OUT:1 = TOINT(ARGF:1 * 100)",
            "RETURN",
        ),
    )

    private fun runArgf(callLine: String, withDefaults: Boolean): Long {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")), fArgf)
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(4)))
        run(pb, store, if (withDefaults) pb.defaults else emptyMap())
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        return store.readInt("OUT", longArrayOf(1))
    }

    @Test
    fun `CALL 省略 ARGF 位 - 取浮点默认值二五`() {
        // 浮点默认值不在编译期补位（padOmittedArgs 跳过），运行期按被调 Def[1] 写 ARGF 局部槽。
        assertEquals(250L, runArgf("CALL F_ARGF(7)", withDefaults = true))
    }

    @Test
    fun `CALLFORM 省略 ARGF 位 - 运行期按浮点默认值补位`() {
        assertEquals(250L, runArgf("CALLFORM \"F_ARGF\" , 7", withDefaults = true))
    }

    @Test
    fun `ARGF 默认值负控 - 不交默认值表时停在 0`() {
        assertEquals(0L, runArgf("CALL F_ARGF(7)", withDefaults = false))
    }

    /** `@F_STR(ARG , ARGS:1 = MYSTR)`：省略的字符串常量名形参位写进 OUTS。 */
    private val fStr = Fn(
        "F_STR",
        arrayOf(
            "@F_STR(ARG , ARGS:1 = MYSTR)",
            "#DIMS OUTS",
            "OUTS '= ARGS:1",
            "RETURN",
        ),
    )

    private fun runStr(callLine: String, withDefaults: Boolean): String {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")),
            fStr,
            strConsts = mapOf("MYSTR" to "abc"),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("OUTS"), "F_STR")
        run(pb, store, if (withDefaults) pb.defaults else emptyMap())
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        return store.userVar("OUTS", "F_STR")!!.readStr(0, 0)
    }

    @Test
    fun `CALL 省略 ARGS 位 - 取字符串常量名默认值 abc`() {
        assertEquals("abc", runStr("CALL F_STR(7)", withDefaults = true))
    }

    @Test
    fun `CALLFORM 省略 ARGS 位 - 运行期按字符串常量名默认值补位`() {
        assertEquals("abc", runStr("CALLFORM \"F_STR\" , 7", withDefaults = true))
    }

    @Test
    fun `ARGS 字符串常量名默认值负控 - CALLFORM 不交默认值表时停在空串`() {
        // 静态 CALL 的默认值在编译期就由 padOmittedArgs 补进字节码，负控必须走 CALLFORM
        // （名字是运行期表达式、编译期补不了）——不交运行期表才停在空串。
        assertEquals("", runStr("CALLFORM \"F_STR\" , 7", withDefaults = false))
    }

    // ------------------------------------------------------------------
    // 6. 第三方 Mod 面：`#DIMF` 浮点私有形参
    //
    // 判词来自 4.981 桌面宿主（`--CliDebug`，宿主 `1824+v24+EMv18+EEv56+Skiav16`）：
    //   `@DIMF_F(ARG, PFLT = 1.5)` + `#DIMF PFLT` + `RETURN TOINT(PFLT * 10)`
    //   - `CALL DIMF_F, 0`      → 15（省略位取被调 `Def[i]` 的浮点默认值）
    //   - `CALL DIMF_F, 0, 2`   → 20（整型实参提升为 2.0）
    //   - `CALL DIMF_F, 0, 2.5` → 25（浮点实参直达浮点形参）
    //   - `CALL DIMF_H, 0, 2.5, 3` → 253（浮点位与整型位混合）
    // 浮点实参的落地方式：帧槽没有浮点列，编译期发 `_EVAL_FLOAT` 把值按
    // `doubleToRawLongBits` **位型**写整型临时槽，`CallArg.isFloat` 标记该桶位，
    // 绑定点由 `ProcessRuntime.floatArgValue` 重解释。失配（浮点实参 → int/str 形参、
    // ARG/ARGS）在原版是装载期错误，本侧在绑定点抛同样的 CodeEE。
    // 本组用例把乘数放大到 100 便于整型读回，期望值 = 上述判词 ×10。
    //
    // 原版依据：形参位写回的是 `Arg[i]` 这个 VariableTerm 所指的变量
    // （ProcessState.cs:435-452 / ErbLoader.cs:646-760），写值走 `Term.SetValue`
    // 的浮点重载；`#DIMF` 私有变量的存储列是 `dataFloat`（VariableData.cs 的
    // `#DIMF` 支，spec: variables.yaml:109-111）。
    // ------------------------------------------------------------------

    /** `@F_DIMF(ARG , PFLT = 1.5)` + `#DIMF PFLT`：形参乘 100 写进 `OUT:1`。 */
    private val fDimf = Fn(
        "F_DIMF",
        arrayOf(
            "@F_DIMF(ARG , PFLT = 1.5)",
            "#DIMF PFLT",
            "#DIM OUT, 4",
            "OUT:1 = TOINT(PFLT * 100)",
            "RETURN",
        ),
    )

    private fun runDimf(callLine: String, withDefaults: Boolean): Long {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")), fDimf)
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(4)), "F_DIMF")
        store.declareUserVar(floatVar("PFLT"), "F_DIMF")
        run(pb, store, if (withDefaults) pb.defaults else emptyMap())
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        return store.readInt("OUT", longArrayOf(1))
    }

    @Test
    fun `CALL 省略 #DIMF 形参位 - 取浮点默认值一五`() {
        // PC 判词 15（×10 口径）⇒ 本侧 150。
        assertEquals(150L, runDimf("CALL F_DIMF(7)", withDefaults = true))
    }

    @Test
    fun `CALL 显式传浮点实参给 #DIMF 形参 - 浮点实参位型借道整型桶`() {
        // PC 判词 25（×10 口径 ⇒ 250）：帧槽没有浮点列，值按 doubleToRawLongBits 位型
        // 走整型桶（`_EVAL_FLOAT` + `CallArg.isFloat`），绑定点重解释写进 `#DIMF` 形参。
        assertEquals(250L, runDimf("CALL F_DIMF(7 , 2.5)", withDefaults = true))
    }

    @Test
    fun `CALLFORM 显式传浮点实参给 #DIMF 形参 - 运行期实参表也支持浮点位`() {
        // 动态调用走 `ProcessRuntime.collectRuntimeArgs` 的 FLOAT 支（`RuntimeCallArg.floatValue`），
        // 与静态调用是同一条绑定路径，但入口不同（`_CALL_STR` 按位型写桶）。
        assertEquals(250L, runDimf("CALLFORM \"F_DIMF\" , 7 , 2.5", withDefaults = true))
    }

    @Test
    fun `CALL 显式传整数实参给 #DIMF 形参 - 提升为浮点`() {
        // PC 判词 20 ⇒ 本侧 200（原版 ConvertArg 允许 int → float 提升）。
        assertEquals(200L, runDimf("CALL F_DIMF(7 , 2)", withDefaults = true))
    }

    /** `@F_ARGFR(ARG , ARGF:1)`（无默认值）：浮点实参进 `ARGF` 系统浮点数组位。 */
    private val fArgfReceiving = Fn(
        "F_ARGFR",
        arrayOf(
            "@F_ARGFR(ARG , ARGF:1)",
            "#DIM OUT, 4",
            "OUT:1 = TOINT(ARGF:1 * 100)",
            "RETURN",
        ),
    )

    private fun runArgfReceiving(callLine: String): Long {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")), fArgfReceiving)
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(4)))
        run(pb, store, pb.defaults)
        return store.readInt("OUT", longArrayOf(1))
    }

    @Test
    fun `CALL 显式传浮点实参给 ARGF 形参 - 系统浮点数组位同一条通道`() {
        // PC 判词 `CALL F_ARGF2, 0, 2.5` ⇒ 25（×10 口径 ⇒ 250）。
        assertEquals(250L, runArgfReceiving("CALL F_ARGFR(7 , 2.5)"))
    }

    /** `@F_INTP(ARG , PINT)` + `#DIM PINT`：整型私有形参（浮点实参的失配负控）。 */
    private val fIntParam = Fn(
        "F_INTP",
        arrayOf("@F_INTP(ARG , PINT)", "#DIM PINT", "RETURN"),
    )

    /** `@F_STRP(ARG , PSTR)` + `#DIMS PSTR`：字符串私有形参（浮点实参的失配负控）。 */
    private val fStrParam = Fn(
        "F_STRP",
        arrayOf("@F_STRP(ARG , PSTR)", "#DIMS PSTR", "RETURN"),
    )

    private fun runParamTypeMismatch(callLine: String, fn: Fn, dim: DimSpec) {
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")), fn)
        val store = VariableStore()
        store.declareUserVar(dim, fn.name)
        run(pb, store, pb.defaults)
    }

    @Test
    fun `CALL 浮点实参给整型形参 - 绑定点按原版口径拒绝`() {
        // PC 判词：装载期即报「2番目の引数を浮動小数点型から整数型に変換できません」。
        val ex = assertFailsWith<CodeEE> {
            runParamTypeMismatch(
                "CALL F_INTP(7 , 2.5)",
                fIntParam,
                DimSpec("PINT", false, intArrayOf(1), isPrivate = true),
            )
        }
        assertTrue(ex.message!!.contains("从浮点型转换为整型"), "实际：${ex.message}")
    }

    @Test
    fun `CALL 浮点实参给字符串形参 - 绑定点按原版口径拒绝`() {
        val ex = assertFailsWith<CodeEE> {
            runParamTypeMismatch(
                "CALL F_STRP(7 , 2.5)",
                fStrParam,
                DimSpec("PSTR", true, intArrayOf(1), isPrivate = true),
            )
        }
        assertTrue(ex.message!!.contains("从浮点型转换为字符串型"), "实际：${ex.message}")
    }

    // ------------------------------------------------------------------
    // 7. 第三方 Mod 面：浮点默认值的**常量折叠**（负号 / 常量算术式）
    //
    // 判词来自同一台 4.981 桌面宿主（`--CliDebug`，`1824+v24+EMv18+EEv56+Skiav16`）：
    //   - `@F_NEG(ARG, PNEG = -1.5)` + `#DIMF PNEG` + `RETURN TOINT(PNEG * 10)`
    //     ⇒ `CALL F_NEG, 0` 得 -15（负数浮点默认值可用，不是「省略即 0」）
    //   - `@F_EXPR(ARG, PEXP = 1.5 + 1)` 同形 ⇒ 得 25（=2.5：默认值支持常量算术式）
    // 原版口径 = 装载期 `Restructure` 把默认值折成 `SingleFloatTerm`
    // （`ErbLoader.cs:724-752`），本侧对应 `LineParser.foldLiteralDefault` 的浮点折叠。
    // 折不出 `FloatLiteral` 时 `floatDefaultOf` 会回落 0.0 ⇒ 静默变 0，故必须折。
    // ------------------------------------------------------------------

    @Test
    fun `解析形参默认值 - 负数浮点常量折成 FloatLiteral`() {
        val stmt = parse("@F(ARGF:1 = -1.5)", "RETURN")
            .filterIsInstance<FuncLabelStmt>().first()
        assertEquals(
            -1.5, (stmt.paramDefaults.single()?.expr as? FloatLiteral)?.value,
            "负号作用于浮点字面量要折成 FloatLiteral(-1.5)，此前只认 IntLiteral 的操作数",
        )
    }

    @Test
    fun `解析形参默认值 - 浮点常量算术式折成 FloatLiteral`() {
        val stmt = parse("@F(ARGF:1 = 1.5 + 1)", "RETURN")
            .filterIsInstance<FuncLabelStmt>().first()
        assertEquals(
            2.5, (stmt.paramDefaults.single()?.expr as? FloatLiteral)?.value,
            "浮点常量算术式要按 double 折成 FloatLiteral(2.5)，此前直接不折",
        )
    }

    /** 以 [pname] 为 `#DIMF` 形参、`[pname] * 100` 写 `OUT:1` 的函数；默认值原文取自 [defaultText]。 */
    private fun runDimfDefault(
        pname: String,
        defaultText: String,
        callLine: String,
        floatConsts: Map<String, Double> = emptyMap(),
    ): Long {
        val fn = Fn(
            "F_DIMF_D",
            arrayOf(
                "@F_DIMF_D(ARG , $pname = $defaultText)",
                "#DIMF $pname",
                "#DIM OUT, 4",
                "OUT:1 = TOINT($pname * 100)",
                "RETURN",
            ),
        )
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")), fn, floatConsts = floatConsts)
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(4)), "F_DIMF_D")
        store.declareUserVar(floatVar(pname), "F_DIMF_D")
        run(pb, store, pb.defaults)
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        return store.readInt("OUT", longArrayOf(1))
    }

    @Test
    fun `CALL 省略 #DIMF 形参位 - 负数浮点默认值不静默变零`() {
        // PC 判词 -15（×10 口径）⇒ 本侧 ×100 口径 = -150。
        assertEquals(-150L, runDimfDefault("PNEG", "-1.5", "CALL F_DIMF_D(7)"))
    }

    @Test
    fun `CALL 省略 #DIMF 形参位 - 浮点常量算术默认值按 double 求值`() {
        // PC 判词 25（1.5 + 1 = 2.5，×10 口径）⇒ 本侧 ×100 口径 = 250。
        assertEquals(250L, runDimfDefault("PEXP", "1.5 + 1", "CALL F_DIMF_D(7)"))
    }

    // ------------------------------------------------------------------
    // 8. 第三方 Mod 面：**整型**形参默认值的常量折叠（负数 / 常量算术式）
    //
    // 同族缺口复查：浮点默认值修完后，确认整型支（`foldConstDefault` 的 INT 支走
    // `DimSizeEvaluator.eval` ⇒ `DimParser.evalDimExpr`）同样折得动。判词：
    //   - `@F_INEG(ARG, PINT = -7)`          ⇒ `CALL F_INEG, 0` 得 -7
    //   - `@F_CONSTARITH(ARG, PINT = 3 + 4)` ⇒ 得 7
    //   - `@F_MUL(ARG, PINT = 3 * 4)`        ⇒ 得 12
    // 口径差异：整型形参给浮点字面量（`PFLT = 7` 反向的同族形态）原版**装载期即错**
    // `'='の前後で型が一致しません`，本侧 `parseLabelDefault` 末尾的类型不匹配分支
    // 同样不注入（见 `foldLiteralDefault` 的 `folded.operandType != paramType`）。
    // ------------------------------------------------------------------

    /** 以 [pname] 为整型私有形参、`[pname] * 100` 写 `OUT:1` 的函数；默认值原文取自 [defaultText]。 */
    private fun runPintDefault(pname: String, defaultText: String, callLine: String): Long {
        val fn = Fn(
            "F_PINT_D",
            arrayOf(
                "@F_PINT_D(ARG , $pname = $defaultText)",
                "#DIM $pname",
                "#DIM OUT, 4",
                "OUT:1 = $pname * 100",
                "RETURN",
            ),
        )
        val pb = compile(Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")), fn)
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(4)), "F_PINT_D")
        store.declareUserVar(DimSpec(pname, false, intArrayOf(1), isPrivate = true), "F_PINT_D")
        run(pb, store, pb.defaults)
        assertEquals(0, store.callDepth(), "帧必须全部退出")
        return store.readInt("OUT", longArrayOf(1))
    }

    @Test
    fun `CALL 省略整型形参位 - 负数默认值不静默变零`() {
        // PC 判词 -7 ⇒ 本侧 ×100 口径 = -700。
        assertEquals(-700L, runPintDefault("PINT", "-7", "CALL F_PINT_D(7)"))
    }

    @Test
    fun `CALL 省略整型形参位 - 常量加法默认值折叠`() {
        // PC 判词 7（3 + 4）⇒ 本侧 ×100 口径 = 700。
        assertEquals(700L, runPintDefault("PINT", "3 + 4", "CALL F_PINT_D(7)"))
    }

    @Test
    fun `CALL 省略整型形参位 - 常量乘法默认值折叠`() {
        // PC 判词 12（3 * 4）⇒ 本侧 ×100 口径 = 1200。
        assertEquals(1200L, runPintDefault("PINT", "3 * 4", "CALL F_PINT_D(7)"))
    }

    // ------------------------------------------------------------------
    // 9. 第三方 Mod 面：`#DIMF CONST` **浮点常量名**当 `#DIMF` 形参默认值
    //
    // PC 判词（同一台 4.981 桌面宿主）：`ERB/*.erh` 写 `#DIMF CONST MYFLT = 1.5`，
    // 函数头 `@F_FCONSTD(ARG, PFLT = MYFLT)` + `CALL F_FCONSTD, 0` ⇒ 15（= 1.5）。
    // 即浮点常量名与整型/字符串常量名一样，装载期 `Restructure` 折成 `SingleFloatTerm`。
    // 本侧 `foldConstDefault` 原来只有 STR/INT 两支（FLOAT 落 `else -> null`）⇒ 折不出，
    // 运行期 `floatDefaultOf(null)` 回落 0.0 ⇒ **静默变 0**，与本条缺陷同族。
    // ------------------------------------------------------------------

    @Test
    fun `CALL 省略 #DIMF 形参位 - 浮点常量名默认值按表折叠`() {
        // PC 判词 15（1.5 ×10 口径）⇒ 本侧 ×100 口径 = 150。
        assertEquals(
            150L,
            runDimfDefault("PFLT", "MYFLT", "CALL F_DIMF_D(7)", floatConsts = mapOf("MYFLT" to 1.5)),
        )
    }

    @Test
    fun `CALL 省略 #DIMF 形参位 - 浮点常量名未登记不注入`() {
        // 负控：表里没有这个名字 ⇒ 不折（省略位停在帧槽默认 0.0），绝不按 0 静默顶替。
        assertEquals(0L, runDimfDefault("PFLT", "MYFLT", "CALL F_DIMF_D(7)"))
    }
}
