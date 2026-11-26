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
import com.eratw.port.core.stmt.ParamConsts
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore

/**
 * [DefaultArgTest] / [DefaultArgThirdPartyTest] 共用的缺省实参装置：
 * 解析（[parser] / [parse]）→ 采集默认值并编译（[compile]）→ 运行（[run]）。
 *
 * 口径与装载装置（`RealCorpusCompile` / `GoldenPipeline` / `CorpusCompile`）逐字一致：
 * 默认值来自 `@行` 的 [FuncLabelStmt.paramDefaults]，键 = 函数名大写，
 * 形参名 = `paramRefs`（含 `:n` 下标，逐位绑定用）。
 */

/** 待编译函数：行 = 含 `@行` 的完整切片；名字/arity/形参一律从 `@行` 解析结果取。 */
internal data class Fn(
    val name: String,
    val lines: Array<String>,
    /** `#FUNCTIONS`（str 返回方法）⇒ `returnsStr = true`。 */
    val returnsStr: Boolean = false,
    /** 进表达式位降级表（`METHOD_STR`）—— 表达式里 `NAME(...)` 才会降成 `_CALL`。 */
    val exprMethod: Boolean = false,
)

internal fun parser(
    dimConsts: Map<String, Long> = emptyMap(),
    strConsts: Map<String, String> = emptyMap(),
    floatConsts: Map<String, Double> = emptyMap(),
): LineParser = LineParser(
    SymbolTable()
        .declareVariable("PA", OperandType.INT, 1)
        .declareVariable("PB", OperandType.INT, 1)
        .declareVariable("PC", OperandType.INT, 1)
        .declareVariable("PY", OperandType.STR, 1)
        .declareVariable("OUT", OperandType.INT, 1)
        .declareVariable("OUTS", OperandType.STR, 1)
        .declareVariable("ARG_ALL_GET", OperandType.INT, 1)
        .declareVariable("ARG_SW", OperandType.STR, 1)
        .declareVariable("FNAME", OperandType.STR, 1)
        // 第 6 节 `#DIMF` 浮点形参：解析期要知道 PFLT 是浮点，`PFLT * 100` 才走浮点通道。
        .declareVariable("PFLT", OperandType.FLOAT, 1)
        // 第 7 节浮点默认值的负数 / 常量算术式折叠（`PNEG = -1.5`、`PEXP = 1.5 + 1`）。
        .declareVariable("PNEG", OperandType.FLOAT, 1)
        .declareVariable("PEXP", OperandType.FLOAT, 1)
        // 第 6 节浮点实参失配负控用的整型/字符串私有形参（同上，形参名要先在册）。
        .declareVariable("PINT", OperandType.INT, 1)
        .declareVariable("PSTR", OperandType.STR, 1)
        .declareFunction("GET_TALENTNAME", OperandType.STR),
    consts = ParamConsts(ints = dimConsts, strs = strConsts, floats = floatConsts),
)

internal fun parse(vararg lines: String): List<Statement> = parse(lines.toList())

internal fun parse(
    lines: List<String>,
    dimConsts: Map<String, Long> = emptyMap(),
    strConsts: Map<String, String> = emptyMap(),
    floatConsts: Map<String, Double> = emptyMap(),
): List<Statement> {
    val p = parser(dimConsts, strConsts, floatConsts)
    return lines.mapIndexedNotNull { i, text ->
        val kind = when {
            text.startsWith("@") -> LineKind.FUNCTION_LABEL
            text.startsWith("$") -> LineKind.GOTO_LABEL
            text.startsWith("#") -> LineKind.SHARP
            else -> LineKind.CODE
        }
        p.parseLogicalLine(LogicalLine(kind, "defaultarg.erb", i + 1, text))
    }
}

/** 编译产物（entry = 第一个函数）+ 函数下标查询。 */
internal class Pb(
    val names: List<String>,
    val program: Program,
    /** 函数名 → 是否 `#FUNCTIONS`（FuncMeta.returnsStr / isMethod 口径）。 */
    val returnsStr: Map<String, Boolean>,
    /** 编译期默认值表（= 容器 `param-defaults` 段；运行期 `CALLFORM` 族补位要它）。 */
    val defaults: CallDefaults = emptyMap(),
) {
    fun index(name: String): Int =
        program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
}

/**
 * 解析 → 采集默认值 → 编译。
 *
 * 采集口径与装载装置（`RealCorpusCompile` / `GoldenPipeline` / `CorpusCompile`）逐字一致：
 * 默认值来自 `@行` 的 [FuncLabelStmt.paramDefaults]，键 = 函数名大写，
 * 形参名 = `paramRefs`（含 `:n` 下标，逐位绑定用）。
 */
internal fun compile(
    vararg fns: Fn,
    dimConsts: Map<String, Long> = emptyMap(),
    strConsts: Map<String, String> = emptyMap(),
    floatConsts: Map<String, Double> = emptyMap(),
): Pb {
    val parsed = fns.map { parse(it.lines.toList(), dimConsts, strConsts, floatConsts) }
    val labels = parsed.map { stmts ->
        stmts.filterIsInstance<FuncLabelStmt>().firstOrNull()
            ?: error("切片缺 @行")
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
        collector.build(),
    )
}

internal fun run(pb: Pb, store: VariableStore): ProcessRuntime = run(pb, store, emptyMap())

/**
 * 同上，但把「被调侧默认值表」交给运行期 —— 容器里这份表来自侧车
 * `param-defaults` 段（[com.eratw.port.core.loader.BootMetadata.paramDefaults]），
 * 编译期 `padOmittedArgs` 补不了 `CALLFORM` 族（名字是表达式），只能运行期补。
 */
internal fun run(pb: Pb, store: VariableStore, callDefaults: CallDefaults): ProcessRuntime {
    val metas = pb.names.map { name ->
        val arity = pb.program.functions[pb.index(name)].arity
        val isMethod = pb.returnsStr[name] == true
        FuncMeta(
            index = pb.index(name), key = name,
            isMethod = isMethod,
            argIntCount = arity, argStrCount = if (isMethod) 1 else 0,
            returnsStr = isMethod,
            defIndex = pb.index(name),
        )
    }
    val rt = ProcessRuntime(
        pb.program, store, FunctionTable(metas), VmConfig(),
        callDefaults = callDefaults,
    )
    rt.execute()
    return rt
}

internal fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)

internal fun strVar(name: String) = DimSpec(name, true, intArrayOf(1), isPrivate = true)

/** `#DIMF` 私有形参（4.981 浮点声明；spec: variables.yaml 的 float_vars）。 */
internal fun floatVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true, isFloat = true)
