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

/**
 * **实参多于形参 = 按位置静默丢弃**（蛇版 4.981 口径）—— 「婚礼策划 → 日期」闪退的引擎侧回归锁。
 *
 * ## 现场
 *
 * 玩家反馈「婚礼策划中的策划日期点了就会闪退」。点「日期」走
 * `ERB/fromEN/Addition/Marriage/Add_CUSTOM_COM14 Wedding Planning.ERB:209` 的
 * `CALL DATEPICKER(...)`，而 `ERB/fromEN/Addition/lib/DATEPICKER.ERB:56` 是
 * `MONTH_TEXT '= GET_MONTH(ACTIVE_MONTH, 1)` —— 两个实参打在
 * `ERB/日時天候管理.ERB:189` 的 `@GET_MONTH(ARG)`（**单形参**）上。
 * 端口此前按 1.824 的 `TooManyFuncArgs` 硬判抛 `GET_MONTH函数: 参数过多`（桌面 vmdbg 复现：
 * `call DATEPICKER --args -1,1` 在 steps=91 报错），而真机宿主照常出日历。
 *
 * ## 口径（复刻目标 = 蛇版 4.981，两条 C# 锚）
 *
 * * 4.981 的 `ConvertArg` **没有** 1.824 那句 `array.Length < srcArgs.Count → TooManyFuncArgs`：
 *   `reference/emuera-skia-4.981/Emuera/Runtime/Script/Process.CalledFunction.cs:212-357`
 *   只循环 `func.Arg.Length` 次，超出位的实参不转换、不求值、不写回。
 * * fork 更新记录写明这是有意删除：`reference/emuera-skia-4.981/CHANGELOG.md`
 *   的 2.0.0「Fixed：ConvertArg() 多余参数静默丢弃：移除 TooManyFuncArgs 报错」。
 * * 对照锚（**不再作为复刻依据**）：`reference/emuera-cs` 1.824
 *   `MinorShift.Emuera.Runtime.Script/CalledFunction.cs:137-142`。
 *
 * 本文件锁三条真实调用形态：语句位 `CALL`（VM 建帧）、表达式位 `#FUNCTIONS` 方法调用
 * （`_CALL` 家族的另一条编译面）、以及名字带插值的 `TRYCALLFORM`（运行期 `_CALL_BY_NAME`，
 * 口上侧函数派发走的通道）。宿主 API 通道（`callF` / `invokeUserMethod`）的同类用例
 * 见 `FuncArgConvertTest` 第 6 节。
 */
class ExtraArgToleratedTest {

    /** 待编译函数：行 = 含 `@行` 的完整切片；名字/arity/形参一律从 `@行` 解析结果取。 */
    private data class Fn(
        val name: String,
        val lines: Array<String>,
        /** `#FUNCTIONS`（str 返回方法）⇒ `returnsStr = true`。 */
        val returnsStr: Boolean = false,
        /** 进表达式位降级表（`METHOD_STR`）—— 表达式里 `NAME(...)` 才会降成 `_CALL`。 */
        val exprMethod: Boolean = false,
    )

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("PA", OperandType.INT, 1)
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("OUTS", OperandType.STR, 1)
            // 动态名通道的名字插值面（`TRYCALLFORM F%RESULTS%(…)`）需要一个可解析的串变量。
            .declareVariable("RESULTS", OperandType.STR, 1)
            // 表达式位用户方法必须在符号表里登记返回类型，否则降级表认不出它。
            .declareFunction("GET_MONTH", OperandType.STR),
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
            p.parseLogicalLine(LogicalLine(kind, "extraarg.erb", i + 1, text))
        }
    }

    private class Pb(
        val names: List<String>,
        val program: Program,
        val returnsStr: Map<String, Boolean>,
    ) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    /** 解析 → 采集默认值 → 编译（装置与 `DefaultArgTest` 同源）。 */
    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val collector = CallDefaultsCollector()
        for ((i, label) in labels.withIndex()) {
            collector.add(fns[i].name, label.paramDefaults)
        }
        val strMethods = fns.filter { it.exprMethod }.mapTo(HashSet()) { it.name.uppercase() }
        val exprCalls = if (strMethods.isEmpty()) null else ExprCallTable(strMethods = strMethods)
        val compiler = ErbProgramCompiler(callDefaults = collector.build())
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            val label = labels[i]
            compiler.compileFunction(
                FuncSig(
                    name = f.name,
                    arity = label.paramNames.size,
                    isMethod = f.returnsStr,
                    returnsStr = f.returnsStr,
                    paramNames = label.paramRefs,
                ),
                parsed[i],
                known,
                exprCalls,
            )
        }
        return Pb(
            fns.map { it.name },
            compiler.build(fns.first().name),
            fns.associate { it.name to it.returnsStr },
        )
    }

    private fun run(pb: Pb, store: VariableStore): ProcessRuntime {
        val metas = pb.names.map { name ->
            val index = pb.index(name)
            val isMethod = pb.returnsStr[name] == true
            FuncMeta(
                index = index, key = name,
                isMethod = isMethod,
                argIntCount = pb.program.functions[index].arity,
                argStrCount = if (isMethod) 1 else 0,
                returnsStr = isMethod,
                defIndex = index,
            )
        }
        val rt = ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig())
        rt.execute()
        return rt
    }

    private fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)

    private fun strVar(name: String) = DimSpec(name, true, intArrayOf(1), isPrivate = true)

    // ------------------------------------------------------------------
    // 1. 语句位 `CALL`：多传的实参丢弃，被调形参照常拿到第 1 个实参
    // ------------------------------------------------------------------

    /** `@F(PA)` 把收到的 `PA` 写进私有 `OUT`；`CALL F(7, 99)` 的第 2 个实参无位可落。 */
    @Test
    fun `语句位 CALL 多传一个实参静默丢弃`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL F(7, 99)", "RETURN")),
            Fn("F", arrayOf("@F(PA)", "#DIM OUT", "OUT = PA", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("PA"), "F")
        store.declareUserVar(intVar("OUT"), "F")
        run(pb, store)
        assertEquals(
            7L, store.userVar("OUT", "F")!!.readInt(0, 0),
            "第 1 个实参照常落到唯一形参；第 2 个实参丢弃（不抛「参数过多」）",
        )
        assertEquals(0, store.callDepth(), "帧必须全部退出")
    }

    // ------------------------------------------------------------------
    // 2. 表达式位 `#FUNCTIONS` 方法调用：DATEPICKER 现场的实形
    // ------------------------------------------------------------------

    /**
     * `@GET_MONTH(ARG)` 的合成形态（单形参、`#FUNCTIONS` 字符串返回）——
     * 对应语料 `日時天候管理.ERB:189`。
     */
    private val fGetMonth = Fn(
        "GET_MONTH",
        arrayOf(
            "@GET_MONTH(ARG)",
            "#FUNCTIONS",
            "IF ARG == 1",
            "\tRETURNF \"春之月\"",
            "ENDIF",
            "\tRETURNF \"?\"",
            "RETURN",
        ),
        returnsStr = true,
        exprMethod = true,
    )

    /**
     * `OUTS '= GET_MONTH(1, 1)` = `DATEPICKER.ERB:56` 的逐字形态（2 实参 / 1 形参）。
     * 修复前这里抛 `GET_MONTH函数: 参数过多`，玩家侧就是「点日期闪退」。
     */
    @Test
    fun `表达式位方法调用多传一个实参静默丢弃`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "OUTS '= GET_MONTH(1, 1)", "RETURN")),
            fGetMonth,
        )
        val store = VariableStore()
        store.declareUserVar(strVar("OUTS"), "MAIN")
        run(pb, store)
        assertEquals(
            "春之月", store.userVar("OUTS", "MAIN")!!.readStr(0, 0),
            "ARG 取第 1 个实参 1；多余的实参 1 丢弃",
        )
        assertEquals(0, store.callDepth(), "帧必须全部退出")
    }

    // ------------------------------------------------------------------
    // 3. 动态名通道（`TRYCALLFORM` ⇒ `_CALL_BY_NAME`）：语料里口上侧函数派发走的通道
    // ------------------------------------------------------------------

    /**
     * 玩家反馈形态（弾幕勝負口上）：调用点
     * `ERB/口上・メッセージ関連/KOJO_MESSAGE.ERB:769`
     * `TRYCALLFORM M_KOJO%RESULTS%_MESSAGE_COM_K{NO:TARGET}_DANMAKU(ARGS:1, ARG:1, ARG:4)`
     * —— 名字带插值，编译期不折叠成常量名，运行期才由 [_CALL_BY_NAME] 解析；被调
     * `ERB/口上・メッセージ関連/個人口上/051 Nitori [にとり]/Nitori/M_KOJO_K51_danmaku_match.ERB:5`
     * 只有 **2 个形参**（`ARGS` 与具名私有变量 `相手残機`），实参却给了 3 个。
     *
     * 上面两例钉的是静态 `CALL` 与表达式位；本例钉第三条通道 —— 建帧点与判据同一条
     * （`Vm.callInto` 的 `min(argcRaw, arity, slotCount)` + `ProcessRuntime.bindCallArgs`
     * 的按位绑定），但它经运行期名字解析，旧 arity 闸一旦回归只会在这里露出来。
     */
    @Test
    fun `动态名 TRYCALLFORM 实参多于形参静默丢弃`() {
        val pb = compile(
            Fn(
                "MAIN",
                arrayOf(
                    "@MAIN",
                    // 名字 = F + RESULTS（未赋值 ⇒ 空）= "F"；带插值就不进编译期常量折叠，
                    // 由运行期 _CALL_BY_NAME 解析 —— 与语料 KOJO_MESSAGE.ERB:769 同一形态。
                    "TRYCALLFORM F%RESULTS%(\"a\", 7, 99)",
                    "RETURN",
                ),
            ),
            Fn("F", arrayOf("@F(ARGS, PA)", "#DIM OUT", "OUTS '= ARGS", "OUT = PA", "RETURN")),
        )
        val store = VariableStore()
        store.declareUserVar(strVar("OUTS"), "F")
        store.declareUserVar(intVar("OUT"), "F")
        store.declareUserVar(intVar("PA"), "F")
        run(pb, store)
        assertEquals("a", store.userVar("OUTS", "F")!!.readStr(0, 0), "第 1 位照常落 ARGS")
        assertEquals(
            7L, store.userVar("OUT", "F")!!.readInt(0, 0),
            "第 2 位照常落具名形参 PA；第 3 个实参 99 丢弃（不抛「参数过多」）",
        )
        assertEquals(0, store.callDepth(), "帧必须全部退出")
    }
}
