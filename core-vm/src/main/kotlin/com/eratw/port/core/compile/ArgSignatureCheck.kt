package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.fn.CompileBuiltinView
import com.eratw.port.core.fn.FullRegistryView
import com.eratw.port.core.lexer.LangKey
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.ParamDefault
import com.eratw.port.core.stmt.Statement
import java.lang.reflect.Field

/**
 * 装载期实参签名校验（CORE-05）：调用位实参**个数 / 类型** vs 被调形参。
 *
 * ## 修的问题
 *
 * 本移植此前没有任何装载期实参校验：`CALL F(1)` 打到 `@F(A, B)` 上，被省略的位
 * 静默按 `0` / `""` 执行（`stageArgs` 的 null 位 `_SET_INT 0`，运行期
 * `bindCallArgs` 对 `present=false` 的位不写）。原版在装载期就红：
 *
 * | 检查 | C# 证据 |
 * |---|---|
 * | 形参省略且无默认值（`CompatiFuncArgOptional=NO`） | `CalledFunction.cs:167-172`（`CanNotOmitArgWithMessage`） |
 * | 实参与形参类型不符 | `CalledFunction.cs:174-199`（`CanNotConvertStrToInt` :178 / `CanNotConvertIntToStr` :183） |
 * | 表达式位内建函数个数/类型 | `IdentifierDictionary.cs:576-585`（回落 `methodDic` + `CheckArgumentType`，:580-584 抛 CodeEE）；个数分支实现 `FunctionMethod.cs:302-330`（`ArgsCountNotMatches` :311 / `ArgsNotNeeded` :314 / `ArgIsNotInt` :327 / `ArgIsNotStr` :329） |
 *
 * **实参过多不在此表**：蛇版 4.981 的 `ConvertArg` 已删除 1.824 的
 * `array.Length < srcArgs.Count → TooManyFuncArgs` 硬判，多余实参静默丢弃
 * （`reference/emuera-skia-4.981/Emuera/Runtime/Script/Process.CalledFunction.cs:212-357`
 * 只循环 `func.Arg.Length` 次；fork 更新记录 CHANGELOG.md「ConvertArg() 多余参数静默丢弃」）。
 * 照 1.824 报「参数过多」会把真机宿主上正常的多参写法误判成缺陷
 * （现场：`fromEN/Addition/lib/DATEPICKER.ERB:56` `GET_MONTH(ACTIVE_MONTH, 1)` 打在
 * `@GET_MONTH(ARG)` 上，见 [com.eratw.port.core.proc.ProcessRuntime] 的「实参多于形参」注释块）。
 *
 * ## 数据面（为什么能零改动接到编译产物上）
 *
 * 全部信息都在冻结后的 [Program] 里，不需要编译器新增任何登记：
 *
 * - **用户函数调用位** = `_CALL` 指令：`a` = 被调函数下标、`b` = 实参数（bit31/30/29/28 是
 *   TRY/JUMP/CALLF/STR_RET 标志，见 [ARG_MASK]），被调形参数/形参名在
 *   [Program.functions]（`FunctionInfo.arity` / `paramNames`），逐位实参元数据
 *   （[Program.callArgsAt] 的 `present` / `isStr`）由 `stageArgs` 全量登记。
 *   `CALLFORM` 家族（`_CALL_BY_NAME`，名字是运行期表达式）静态不可判，跳过。
 * - **内建函数调用位** = 表达式池根（[Program.expressions]）+ 语句池
 *   （[Program.statements]）里的 [FuncCall] 节点。名字命中
 *   [Builtins.registry] 且**不是用户函数**（用户函数遮蔽内建，口径 =
 *   `IdentifierDictionary.cs:548-565` 先查 labelDic）时，按
 *   [BuiltinMethod.minArgs]/[maxArgs]/[omitStart] 判个数 ——
 *   `PureMethods` 等处的 `int("NAME", min, max)` DSL 最终就落成这三个字段，
 *   读取面即 [BuiltinMethod.checkArity] 的同源口径（`FunctionMethod.cs:182-283`）。
 *   声明 [BuiltinMethod.variadicGroup] 的再判**成对变参组**（ENGFIX3 B4 遗留①：
 *   表达式位接线；判据与 erbcc 语句位 `pairedArgLoadWarning` 同源，只读
 *   [VariadicGroup.violates] 单一声明源。原版两个调用点同走 `CheckArgumentTypeEx`：
 *   语句位 `ArgumentParser.cs:1119-1127`、表达式位 `IdentifierDictionary.cs:576-585`，
 *   违例文案 `Lang.Error.ArgsNotFitExpr`，Lang.cs:3958）。
 *   `CALLFORM` 家族动态名与成对判据**有锚地不适用**：原版 CALL/CALLFORM 名字解析只查
 *   用户标签表（`CalledFunction.cs:87-115` `GetNonEventLabel`，命中 `IsMethod` 还抛
 *   `CallToUserFunc`），内建方法（成对判据的声明域）不可达 —— 非静态不可判，是域外。
 *
 * ## 报告通道（归邻波，本类不新造）
 *
 * 默认**宽松模式**（[Options.strict] = false）：只收集不抛，全量语料编译不可能因此变红。
 * 收集结果经 [checkProgram] 的**返回值**交调用方显式接住，由邻波接线到装载返回值 /
 * `BootMetadata` 的既有通道（`loader` 包归邻波，本路只消费不新造）。
 * [Options.strict] = true 时 [checkProgram] 聚合全部不符项抛 [CompileException]
 * （逐条文案，供测试与小脚本）。
 *
 * ## 无静态可变状态（铁律 4）
 *
 * 本类曾是全 core-vm 主源集唯一持静态可变全局的点（object 上 `strict` /
 * `compatiFuncArgOptional` / `lastWarnings` 三个 var，2026-10-24 架构审查收口）。
 * 现口径经 [Options] 不可变入参进 [checkProgram]、告警经返回值出去、调用方显式接住
 * ——object 本体不再有任何 var；仅存的 [fieldCache] 是键决定值的反射缓存，
 * 内容与调用序列无关，非行为状态。
 *
 * ## 已知边界（都标了 TODO(verify)，不静默）
 *
 * - 语句池节点被 `ExprCanonicalizer` 把 line/col 归一成 -1，故语句位内建告警的定位
 *   退回**外层语句行**（[Statement.line]）；表达式池根未被折叠，行列号是真的。
 * - 用户函数形参**类型**只有 `$` 后缀一个信号源（`@F(A, B$)` → paramNames `[A, B$]`，
 *   见 `FuncSig.paramNames` KDoc），且只能抓「整型实参 → `$` 形参」一个方向；
 *   反方向（字符串实参 → 无 `$` 形参）在本语料会误报（str 形参有不带 `$` 的写法，
 *   如 `@GET_TALENTNAME(..., ARG_SW = ",")`），不判。
 *   **当前词法层产不出 `$` 形参**（原版同口径拒绝 '$'，spec: LexicalAnalyzer.cs:916-918；
 *   语料 0 条 `$` 形参）⇒ 该分支是前向钩子，只能由「调用方直接构造 FuncSig」触发。
 *   TODO(verify)：可靠类型源是 `DimSpecLayout`/`LocalDim.isStr`，属 erbcc 侧。
 * - 内建**占位**（[BuiltinRegistry.isPlaceholder]）的 arity 元数据是家族标签
 *   （`registerUnimplemented(name, ret, 0, 4)` 的 4 不是真上限），跳过不判。
 * - 用户函数位**不再有「实参过多」这一类**（TOO_MANY 只剩内建表达式位；4.981 容忍多余实参，
 *   见类头表下注释）。装载装置给「未定义 CALL 目标」补的 0 参空体桩也不再因此报红。
 */
object ArgSignatureCheck {

    /**
     * `_CALL` / `_CALL_BY_NAME` 的 `b` 操作数里实参数的掩码。
     *
     * 高位标志（`ErbProgramCompiler.kt:2503-2519`）：bit31 = TRY、bit30 = JUMP、
     * bit29 = CALLF、bit28 = STR_RET。掩掉后剩下的就是实参数。
     */
    private const val ARG_MASK = 0x0FFFFFFF

    /** 表达式树反射遍历的深度上限（防御病态嵌套；真实 ERB 表达式远低于此）。 */
    private const val MAX_WALK_DEPTH = 64

    /**
     * 校验口径（不可变入参，[checkProgram] 每次调用显式给；缺省值 = 生产钩子的现行为）。
     *
     * @property strict 严格模式开关：true = [checkProgram] 聚合全部不符项抛
     *   [CompileException]；false（默认）= 只收集、经返回值交回。
     *   全量语料门必须跑默认值 —— 那条判据是「0 失败、指令数不变」，严格模式是**选择加入**的。
     * @property compatiFuncArgOptional `CompatiFuncArgOptional` 口径（spec: `CalledFunction.cs:168-172`）。
     *   false（默认）= 原版默认口径 NO：形参位没有实参、也没有可注入默认值 ⇒ 不符。
     *   true = 兼容档 YES：缺参合法（运行期不写该位），不产生 TOO_FEW 告警。
     */
    data class Options(
        val strict: Boolean = false,
        val compatiFuncArgOptional: Boolean = false,
    )

    // ======================================================================
    // 入口
    // ======================================================================

    /**
     * 校验一份冻结的 [Program]（[ErbProgramCompiler.build] 的唯一钩子点）。
     *
     * 幂等纯净：不修改 [program] 任何字节（指令数、语句池、函数表逐字不变），
     * 只读 + 产告警。宽松模式永不抛；严格模式在有不符项时抛 [CompileException]
     * （全部不符项已聚合进异常文案——抛出即无返回值可接）。
     *
     * @param options 校验口径（缺省 = 宽松 + 原版默认兼容档；生产钩子显式传缺省值）
     * @return 本次全部告警（调用方显式接住；同输入重复调用产出逐条相同）
     */
    fun checkProgram(
        program: Program,
        options: Options = Options(),
        /**
         * 内建查表视图（ENGC-3b，B6 组长接线）：缺省 [FullRegistryView] = 直查 registry，
         * 行为与改前逐位相同；erbcc `--engine-profile` 通道传目标档视图，被裁名字在本类
         * 的内建调用位扫描里判 null。
         */
        builtinView: CompileBuiltinView = FullRegistryView,
        /**
         * 目标档不可见内建名收集钩（`--engine-report` 数据面）：视图判 null 且非用户函数、
         * 非降级哨兵的 [FuncCall] 上报 `(名字, 行)`；行可为 -1（池折叠抹行号），消费方取舍。
         * 缺省 null = 零开销零行为差（I7）。
         *
         * 本扫描位只是**报告数据面**，不改变判决：被裁名照旧走既有「未定义的函数」路径
         * （I4/D7，不新增文案），sink 只是把命中处 `(名字, 行)` 抄给 `--engine-report`。
         * 卡点：表达式里调用被裁内建可能在更早的解析期（`ExprCallTable.classify`）就被拒、
         * 走不到这里 ⇒ `unknownFunctions` 半边在某些夹具下恒空，属已知现象，其真实可达性由
         * B7-E2E 定量（见 CONTRACT §8.5 留验），不许在此处为凑命中放宽门控。
         */
        builtinUnknownSink: ((String, Int) -> Unit)? = null,
    ): List<ArgSignatureWarning> {
        // 基线折回（R7-C1 最小注入，组长授权 2026-11-01）：带基线的补丁段（codeBase /
        // functionBase 非零，L1 的窗口语义）按 Program.instructionBase / functionBase 折回
        // 扫描面——扫描只落在本段窗口（[instructionBase, instructionCount)），函数下标按
        // 绝对下标减 functionBase 折回本段表；base 区间的 `_CALL`（a < functionBase）折回
        // 为负 → getOrNull 返回 null → 跳过（base 的签名校验归 base 装载面，补丁编译期
        // 不重扫）。零基线路径（instructionBase == 0 && functionBase == 0）折回量恒 0，
        // 与改前的无基线扫描逐位相同（erbcc 全量产物指纹不变的自证口径）。
        val out = ArrayList<ArgSignatureWarning>()
        checkUserCalls(program, options, out)
        checkBuiltinCalls(program, out, builtinView, builtinUnknownSink)
        if (options.strict && out.isNotEmpty()) {
            val first = out.first()
            val detail = buildString {
                append("实参签名校验失败 ").append(out.size).append(" 处（严格模式）:\n")
                for (w in out) append("  [").append(w.kind).append("] ").append(w.message).append('\n')
            }
            throw CompileException(first.caller, first.line, detail)
        }
        return out
    }

    // ======================================================================
    // ① 用户函数调用位（`_CALL` 指令扫描）
    // ======================================================================

    /**
     * 扫全部 `_CALL`：实参数 vs 被调 [com.eratw.port.core.code.FunctionInfo.arity]，
     * 逐位 `present` 判省略（[com.eratw.port.core.code.CallArg.present] = false 即
     * 「无实参且无默认值注入」，原版 `Arguments[i] == null` 且 `Def[i] == null`），
     * `$` 后缀形参判类型。
     *
     * 调用方归属靠入口 pc 二分（[Program.functions] 按声明序 = 入口序；防御性排序一遍）。
     */
    private fun checkUserCalls(program: Program, options: Options, out: MutableList<ArgSignatureWarning>) {
        val fns = program.functions
        if (fns.isEmpty()) return
        val byEntry = fns.sortedBy { it.entryPc }
        val entryPcs = IntArray(byEntry.size) { byEntry[it].entryPc }
        val n = program.instructionCount
        var pc = program.instructionBase                // 带基线时只扫本段窗口（base 归 base 装载面）
        while (pc < n) {
            if (program.op(pc) == Opcode._CALL) {
                // a 是绝对函数下标；折回本段表（base 区间的下标折回为负 → null → 跳过）。
                val fi = fns.getOrNull(program.a(pc) - program.functionBase)
                if (fi != null) {
                    checkOneUserCall(program, pc, fi, program.b(pc) and ARG_MASK, entryPcs, byEntry, options, out)
                }
            }
            pc++
        }
    }

    private fun checkOneUserCall(
        program: Program,
        pc: Int,
        fi: com.eratw.port.core.code.FunctionInfo,
        argc: Int,
        entryPcs: IntArray,
        byEntry: List<com.eratw.port.core.code.FunctionInfo>,
        options: Options,
        out: MutableList<ArgSignatureWarning>,
    ) {
        val caller = callerOf(pc, entryPcs, byEntry)
        val line = program.lineOf(pc)
        val callee = fi.name
        val where = "调用方 ${caller ?: "?"}:$line"
        // 实参多于形参**不告警**：4.981 的 ConvertArg 静默丢弃多余实参（类头「实参过多不在此表」），
        // 运行期绑定点也只写被调形参覆盖的位。此处只继续判省略位与类型。
        val callArgs = program.callArgsAt(pc)
        // 逐位省略判定：callArgs 与实参位一一对应（stageArgs 每位置一条）；
        // 事件入口的 _CALL 不登记 callArgs，退回裸 argc 比较。
        var missing = -1
        if (callArgs != null) {
            for (i in 0 until fi.arity) {
                val ca = callArgs.getOrNull(i)
                if (ca == null || !ca.present) {
                    missing = i
                    break
                }
            }
        } else if (argc < fi.arity) {
            missing = argc
        }
        if (missing >= 0 && !options.compatiFuncArgOptional) {
            // spec: CalledFunction.cs:167-172（CanNotOmitArgWithMessage；CompatibleArgOptional=NO）
            out.add(
                ArgSignatureWarning(
                    kind = ArgSignatureWarningKind.TOO_FEW,
                    caller = caller, line = line, callee = callee, position = missing + 1,
                    argc = argc, arity = fi.arity,
                    message = "@$callee 的第 ${missing + 1} 个参数不能省略" +
                        "（$where：实参 $argc 个，形参 ${fi.arity} 个）",
                ),
            )
        }
        // 类型：`$` 后缀形参期望字符串通道（FuncSig.paramNames KDoc 的口径）。
        // 只判「整型实参 → `$` 形参」一个方向，理由见类 KDoc「已知边界」。
        // TODO(verify): 当前词法层（原版同口径，LexicalAnalyzer.cs:916-918）拒绝 '$'，
        // 解析器产不出 `$` 形参名 ⇒ 本分支今天只能由「调用方直接构造 FuncSig」触发
        // （测试即这么写）；可靠类型源是 DimSpecLayout/LocalDim.isStr，属 erbcc 侧接线。
        if (callArgs != null) {
            val names = fi.paramNames
            for (i in callArgs.indices) {
                if (i >= fi.arity) break
                val ca = callArgs[i]
                if (!ca.present) continue
                if (names.getOrNull(i)?.endsWith("$") == true && !ca.isStr) {
                    out.add(
                        ArgSignatureWarning(
                            kind = ArgSignatureWarningKind.TYPE_MISMATCH,
                            caller = caller, line = line, callee = callee, position = i + 1,
                            argc = argc, arity = fi.arity,
                            message = "不能将\"@$callee\"的第 ${i + 1} 个参数从整型转换为字符串型" +
                                "（$where：形参声明为字符串型）",
                        ),
                    )
                }
            }
        }
    }

    /** pc → 所在函数名（入口 pc 升序表上二分；声明序即入口序）。 */
    private fun callerOf(
        pc: Int,
        entryPcs: IntArray,
        byEntry: List<com.eratw.port.core.code.FunctionInfo>,
    ): String? {
        var lo = 0
        var hi = entryPcs.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entryPcs[mid] <= pc) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (ans >= 0) byEntry[ans].name else null
    }

    // ======================================================================
    // ② 内建函数调用位（表达式池根 + 语句池的 FuncCall 节点）
    // ======================================================================

    /**
     * 遍历表达式池根与语句池，对 [FuncCall] 节点按 [Builtins.registry] 的 arity 元数据判个数、
     * 按 [BuiltinMethod.variadicGroup] 判成对变参组（判据见类 KDoc 数据面②）。
     *
     * 名字解析口径 = `IdentifierDictionary.cs:548-585`：**用户函数先于内建**
     * （program.functions 命中即跳过，`_CALL` 路径已判或运行期重入），
     * 表达式位降级哨兵（`EXPR_TEMP_PREFIX`，`#` 开头）不是真函数名。
     */
    private fun checkBuiltinCalls(
        program: Program,
        out: MutableList<ArgSignatureWarning>,
        builtinView: CompileBuiltinView,
        unknownSink: ((String, Int) -> Unit)?,
    ) {
        val userNames = HashSet<String>(program.functions.size * 2)
        for (f in program.functions) userNames.add(f.name.uppercase())
        val seen = HashSet<String>()
        val visit: (FuncCall, Int) -> Unit = { fc, line ->
            checkOneBuiltin(fc, line, userNames, seen, out, builtinView, unknownSink)
        }
        for (e in program.expressions()) {
            walk(e, 0, -1, visit)
        }
        for (s in program.statements()) {
            // 语句池节点被规范折叠抹掉 line/col（-1），定位退回外层语句行
            walk(s, 0, s.line, visit)
        }
    }

    private fun checkOneBuiltin(
        fc: FuncCall,
        line: Int,
        userNames: Set<String>,
        seen: MutableSet<String>,
        out: MutableList<ArgSignatureWarning>,
        builtinView: CompileBuiltinView,
        unknownSink: ((String, Int) -> Unit)?,
    ) {
        val key = fc.name.uppercase()
        if (key in userNames) return
        if (key.startsWith("#")) return   // 表达式位方法调用降级哨兵，不是函数名
        val m = builtinView.method(fc.name) ?: run {
            // ENGC-3b：目标档视图判 null（被裁或未注册）⇒ 上报缺口后走既有「未定义」路径。
            // 缺省视图 + 缺省 sink 时与改前逐位相同（纯 return，无上报）。
            unknownSink?.invoke(fc.name, line)
            return
        }
        // 占位的 min/max 是家族标签不是真签名（registerUnimplemented(name, ret, 0, 4)）
        if (builtinView.isPlaceholder(key)) return
        val n = fc.args.size
        // 规范折叠让同一 FuncCall 实例被多条语句共享：按 (名字, 个数, 行) 去重
        if (!seen.add("$key|$n|$line")) return
        // ENGFIX3 B4 遗留①（表达式位接线）：成对变参组判据，与 erbcc 语句位
        // pairedArgLoadWarning 同源（只读 VariadicGroup.violates 单一声明源）。
        // 命中即收一条、不再下探个数闸：原版 CheckArgumentTypeEx 对该形态记下
        // ArgsNotFitExpr 后 continue，最终按首个非空错误返回（FunctionMethod.cs:186-193），
        // 个数闸与成对闸同形态不并报。
        val vg = m.variadicGroup
        if (vg != null && vg.violates(n)) {
            out.add(
                ArgSignatureWarning(
                    kind = ArgSignatureWarningKind.VARIADIC_GROUP,
                    caller = null, line = line, callee = fc.name, position = 0,
                    argc = n, arity = vg.fixedPrefix,
                    // 官方基串 = ArgsNotFitExpr（`{0}函数: 参数数量({1})不满足{2}+{3}n`，
                    // spec: FunctionMethod.cs:191 的实参表 name / count / 组起点 / 组长）；
                    // 数组形态的另一条合法签名是移植侧读数，作取证后缀留在括号里。
                    message = LangKey.ArgsNotFitExpr.renderZhs(fc.name, n, vg.fixedPrefix, vg.groupSize) +
                        "（:$line：数组形态另可给 ${vg.exactFormArgs} 个）",
                ),
            )
            return
        }
        val kind: ArgSignatureWarningKind
        val message: String
        when {
            m.omitStart >= 0 -> {
                // 有省略位：个数下界 = OmitStart，上界 = 槽位数（BuiltinMethod.checkArity 同口径）
                if (m.maxArgs >= 0 && n > m.maxArgs) {
                    kind = ArgSignatureWarningKind.TOO_MANY
                    message = LangKey.TooManyFuncArgs.renderZhs(fc.name) +
                        "（:$line：最多 ${m.maxArgs} 个，实参 $n 个）"
                } else if (n < m.omitStart) {
                    kind = ArgSignatureWarningKind.TOO_FEW
                    message = LangKey.NotEnoughArgs.renderZhs(fc.name, m.omitStart) +
                        "（:$line：实参 $n 个）"
                } else return
            }
            m.maxArgs >= 0 -> {
                if (n !in m.minArgs..m.maxArgs) {
                    kind = if (n > m.maxArgs) ArgSignatureWarningKind.TOO_MANY else ArgSignatureWarningKind.TOO_FEW
                    // spec: FunctionMethod.cs:307-318 —— 形参表长度为 0（`ArgsNotNeeded` 的
                    // 触发条件 `argumentTypeArray.Length == 0`）且实参非空时，官方文案是
                    // 独立的「不需要参数」，不是通用「个数不匹配」（ArgsCountNotMatches）。
                    message = if (m.maxArgs == 0 && m.minArgs == 0 && n > 0) {
                        LangKey.ArgsNotNeeded.renderZhs(fc.name) + "（:$line：实参 $n 个）"
                    } else {
                        LangKey.ArgsCountNotMatches.renderZhs(fc.name, m.minArgs, n) + "（:$line）"
                    }
                } else return
            }
            else -> {
                if (n < m.minArgs) {
                    kind = ArgSignatureWarningKind.TOO_FEW
                    message = LangKey.NotEnoughArgs.renderZhs(fc.name, m.minArgs) +
                        "（:$line：实参 $n 个）"
                } else return
            }
        }
        out.add(
            ArgSignatureWarning(
                kind = kind,
                caller = null, line = line, callee = fc.name, position = 0,
                argc = n, arity = m.minArgs, message = message,
            ),
        )
    }

    // ======================================================================
    // 表达式树反射遍历（语句/参数的形态有上百种，逐个 when 不如反射一次走完）
    // ======================================================================

    /**
     * 每类一份「可下降字段」缓存（排除原语/String/枚举/数组/静态；isAccessible 是 Java 1.1 API）。
     *
     * 静态但**非行为状态**（铁律 4 口径）：键（Class）唯一决定值，内容不随调用序列变化，
     * 只省重复反射——去掉它只变慢，不改任何读数；object 上已无可变行为状态（见类 KDoc）。
     */
    private val fieldCache = HashMap<Class<*>, Array<Field>>()

    private fun fieldsOf(c: Class<*>): Array<Field> {
        fieldCache[c]?.let { return it }
        val out = ArrayList<Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                val t = f.type
                if (t.isPrimitive || t == String::class.java || t.isEnum || t.isArray) continue
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                // 语句原文槽（CharArray）已随 isArray 排除；VarRef 的数组视图不是表达式来源
                if (f.name == "indexArray") continue
                // 运期缓存不属于表达式子图（`VarRef` 的访问点绑定缓存，见其 `@Transient` 段）：
                // `bindReader` 是 `Any?`，不排除就会把整个 `VariableStore` 交给 [walk]。
                // walk 虽然会因类型不认而立刻返回，但那是「碰巧无害」，不是设计。
                if (java.lang.reflect.Modifier.isTransient(f.modifiers)) continue
                f.isAccessible = true
                out.add(f)
            }
            k = k.superclass
        }
        val arr = out.toTypedArray()
        fieldCache[c] = arr
        return arr
    }

    /**
     * 递归下降。[line] 是定位兜底（外层语句行；表达式池根传 -1，用节点自己的行列号）。
     * 只认表达式树家族（[Expr]/[Argument]/[Statement]/[FormPart]/[FormConversion]/
     * [CaseExpression]/[MixedIntegerExprTerm]/[ParamDefault]/List），其余形态直接略过 ——
     * 枚举/字符串/装箱值没有可遍历的表达式子节点（`ArgInterner` 同款边界）。
     */
    private fun walk(node: Any?, depth: Int, line: Int, visit: (FuncCall, Int) -> Unit) {
        if (node == null || depth > MAX_WALK_DEPTH) return
        when (node) {
            is FuncCall -> {
                visit(node, if (node.line >= 0) node.line else line)
                walkFields(node, depth, line, visit)
            }
            is Expr -> walkFields(node, depth, line, visit)
            is Argument -> walkFields(node, depth, line, visit)
            is Statement -> walkFields(node, depth, line, visit)
            is FormPart -> walkFields(node, depth, line, visit)
            is FormConversion -> walkFields(node, depth, line, visit)
            is CaseExpression -> walkFields(node, depth, line, visit)
            is MixedIntegerExprTerm -> walkFields(node, depth, line, visit)
            is ParamDefault -> walkFields(node, depth, line, visit)
            is List<*> -> for (e in node) walk(e, depth + 1, line, visit)
            else -> {}
        }
    }

    private fun walkFields(owner: Any, depth: Int, line: Int, visit: (FuncCall, Int) -> Unit) {
        if (depth > MAX_WALK_DEPTH) return
        for (f in fieldsOf(owner.javaClass)) {
            walk(f.get(owner), depth + 1, line, visit)
        }
    }
}

/** 告警类别（与 C# Lang 文案一一对应）。 */
enum class ArgSignatureWarningKind {
    /**
     * 内建函数实参多于槽位（`TooManyFuncArgs`，4.981 仍保留：
     * `reference/emuera-skia-4.981/Emuera/Runtime/Script/Statements/Function/FunctionMethod.cs:263`、
     * `Creator.Method.cs:3381`）。用户函数位不产这一类 —— 4.981 的多余实参静默丢弃。
     */
    TOO_MANY,

    /** 形参位无实参且无默认值（`CanNotOmitArgWithMessage`，spec: CalledFunction.cs:167-172）。 */
    TOO_FEW,

    /** 实参类型与形参不符（`CanNotConvert*`，spec: CalledFunction.cs:174-199）。 */
    TYPE_MISMATCH,

    /**
     * 成对变参组不满足（`ArgsNotFitExpr`「引数の数が 前置+n ではありません」，
     * spec: FunctionMethod.cs:186-193、Lang.cs:3958）。判据域 = 声明了
     * [com.eratw.port.core.fn.VariadicGroup] 的内建方法；ENGFIX3 B4 遗留①起
     * 同时覆盖语句池与表达式池的 [FuncCall]（表达式位接线）。
     */
    VARIADIC_GROUP,
}

/**
 * 一条实参签名告警（默认报告通道的条目）。
 *
 * @property kind 类别
 * @property caller 调用方函数名（用户函数调用位）；内建表达式位 = null（语句池不记函数归属）
 * @property line 源码行号（-1 = 无定位）
 * @property callee 被调名（用户函数名 / 内建函数名，原样）
 * @property position 人读位号（1 基；个数类告警 = 0）
 * @property argc 实参数（内建 = FuncCall 的 args.size）
 * @property arity 形参数（内建 = minArgs 口径）
 * @property message 完整文案（C# Lang 中文口径 + 定位上下文）
 */
data class ArgSignatureWarning(
    val kind: ArgSignatureWarningKind,
    val caller: String?,
    val line: Int,
    val callee: String,
    val position: Int,
    val argc: Int,
    val arity: Int,
    val message: String,
)
