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
 * **FORM 系动态名表达式内层用户函数调用不得污染外层实参暂存区** —— 真机
 * `CALLNAME(15101)` 角色登记编号越界的引擎侧根因回归锁。
 *
 * ## 现场（玩家真机 0.2.45 / 0.2.47 连续上报）
 *
 * 屏上原文 `角色数组变量"CALLNAME"的第1个变量(15101)超出角色登记编号的最大范围`，
 * ERB 栈 `#0 IRAI_固有15 行96（物理行97）` ← `IRAI_CARRYOUT 行263（物理行264）`
 * ← `IRAI_VANISH 行228（物理行229）` ← `BEFORETRAIN 行436（物理行437）`。
 *
 * * `CTRL_IRAI.ERB:246` 的 `@IRAI_CARRYOUT(CHARA, IRAI_ID, SCENE)` 函数体内**无** `CHARA =`
 *   赋值，`:264` 是 `TRYCCALLFORM IRAI_固有{IRAI_ID_TO_CLIENT(IRAI_ID)}(CHARA, IRAI_ID, SCENE)`。
 * * 栈顶 `#0 IRAI_固有15` 说明**函数名算对了**（`IRAI_SLOT_TO_CLIENT` 得到 15），
 *   而被调第 1 形参 `CHARA` 却是 `15101` —— 恰好是名字表达式里那次内层调用的**实参**
 *   (`IRAI_ID = 15101`)，不是它的返回值(15)。
 * * 故「函数名算对了、外层实参被内层实参写脏」：这是**同一次求值**里的暂存区重叠，不是语料 bug。
 *
 * ## 机制（本文件要钉死的东西）
 *
 * `ErbProgramCompiler` 的 FORM 系发射序是
 * `stageArgs(外层实参)` → 求值名表达式：
 *
 * * `stageArgs`（`ErbProgramCompiler.kt:1978`）把外层实参求值到临时槽后 `_MOV_*` 进
 *   **当前帧**的实参桶 `R0..R(argc-1)`（桶区在切帧时才被 `callInto` 复制进被调帧）；
 * * 紧接的 `evalTemp(nameTerm)`（`:3018` / `:3164`）对名字表达式做 `lowerExpr`，其中的
 *   用户函数调用被降级成 `stageArgs(内层实参)` + `_CALL`（`:2610-2612`）——**内层实参同样
 *   落在当前帧的同一个 `R0..` 桶区**，于是把刚 `_MOV` 好的外层实参覆盖掉。
 *
 * 原版没有这条通道：`FunctionIdentifier.cs:3572-3576` 先用 `FuncnameTerm.GetStrValue` 定名，
 * 命中后才 `state.IntoFunction(...)`（`:3589-3598`）把各 term 求值进**被调帧**的 `Arg[]`
 * （`Process.State.cs:598` / `:648` 的 `if (srcArgs != null)`），外层实参在定名阶段根本还没
 * 落任何共享暂存位。修复方向 = 让发射序与之一致（先定名、后暂存实参）。
 *
 * 装置照抄 `ExtraArgToleratedTest`（`FuncLabelStmt` 取 arity/形参、`#DIM` 走私有用户变量），
 * 因为 `FuncMeta` 只带 `argIntCount`、不带形参名，从零搭会撞 `VariableStore` 的
 * `未定义的变量`。
 */
class FormNameNestedCallStageTest {

    /** 待编译函数：行 = 含 `@行` 的完整切片。 */
    private data class Fn(
        val name: String,
        val lines: Array<String>,
        /** `#FUNCTION`（int 返回方法）/ `#FUNCTIONS`（str 返回方法）。 */
        val isMethod: Boolean = false,
        val returnsStr: Boolean = false,
        /** 进表达式位降级表 —— 表达式里 `NAME(...)` 才会降成 `_CALL`。 */
        val exprInt: Boolean = false,
        val exprStr: Boolean = false,
    )

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("PA", OperandType.INT, 1)
            .declareVariable("OUT", OperandType.INT, 1)
            // 名字插值面（`F%RESULTS%`）需要一个可解析的串变量（状态同 ExtraArgToleratedTest）。
            .declareVariable("RESULTS", OperandType.STR, 1)
            // 表达式位用户方法必须登记返回类型，降级表才认得出它是 METHOD_INT。
            .declareFunction("A", OperandType.INT)
            .declareFunction("NM", OperandType.INT)
            .declareFunction("AG", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "formname.erb", i + 1, text))
        }
    }

    private class Pb(
        val names: List<String>,
        val program: Program,
        val metas: Map<String, FuncMeta>,
    ) {
        fun index(name: String): Int = metas.getValue(name).index
    }

    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val collector = CallDefaultsCollector()
        for ((i, label) in labels.withIndex()) collector.add(fns[i].name, label.paramDefaults)
        val intMethods = fns.filter { it.exprInt }.mapTo(HashSet()) { it.name.uppercase() }
        val strMethods = fns.filter { it.exprStr }.mapTo(HashSet()) { it.name.uppercase() }
        val exprCalls = if (intMethods.isEmpty() && strMethods.isEmpty()) {
            null
        } else {
            ExprCallTable(intMethods = intMethods, strMethods = strMethods)
        }
        val compiler = ErbProgramCompiler(callDefaults = collector.build())
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            compiler.compileFunction(
                FuncSig(
                    name = f.name,
                    arity = labels[i].paramNames.size,
                    isMethod = f.isMethod,
                    returnsStr = f.returnsStr,
                    paramNames = labels[i].paramRefs,
                ),
                parsed[i],
                known,
                exprCalls,
            )
        }
        val program = compiler.build(fns.first().name)
        val metas = fns.associate { f ->
            val index = program.functions.indices.first {
                program.functions[it].name.equals(f.name, ignoreCase = true)
            }
            f.name to FuncMeta(
                index = index,
                key = f.name,
                isMethod = f.isMethod,
                argIntCount = program.functions[index].arity,
                argStrCount = if (f.returnsStr) 1 else 0,
                returnsStr = f.returnsStr,
                defIndex = index,
            )
        }
        return Pb(fns.map { it.name }, program, metas)
    }

    private fun run(pb: Pb, store: VariableStore): ProcessRuntime {
        val rt = ProcessRuntime(
            pb.program, store,
            FunctionTable(pb.names.map { pb.metas.getValue(it) }),
            VmConfig(maxStepsPerRun = 50_000L),
        )
        rt.execute()
        return rt
    }

    private fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)

    // ------------------------------------------------------------------
    // 语料同形的两个被调/内层函数
    // ------------------------------------------------------------------

    /** `IRAI_ID_TO_CLIENT` 的合成形态：单形参、`#FUNCTION` int 返回、回传实参本身。 */
    private val fA = Fn(
        "A",
        arrayOf("@A(PA)", "#FUNCTION", "RETURNF PA", "RETURN"),
        isMethod = true,
        exprInt = true,
    )

    /** 被调方：`@IRAI_固有15(CHARA, …)` 的合成形态 —— 只把收到的第 1 形参记下来。 */
    private fun callee(name: String) = Fn(
        name,
        arrayOf("@$name(PA)", "#DIM OUT", "OUT = PA", "RETURN"),
    )

    /** 名字 = `F` + `A(7)` = `F7`（内层实参 7 ≠ 外层实参 15，两者可区分）。 */
    private val mainCallFormBody = arrayOf("@MAIN", "CALLFORM F{A(7)}(15)", "RETURN")

    /** 方法形态的被调方（`CALLFORMF` 族只认 `#FUNCTION`/`#FUNCTIONS` 方法）。 */
    private fun calleeMethod(name: String) = Fn(
        name,
        arrayOf("@$name(PA)", "#FUNCTION", "#DIM OUT", "OUT = PA", "RETURNF 0", "RETURN"),
        isMethod = true,
        exprInt = true,
    )

    /** 通用断言：被调第 1 形参必须是**外层**实参 15（读到 7 = 内层实参覆盖了 `R0`）。 */
    private fun assertCalleeGot15(code: String, mainLines: Array<String>, calleeFn: Fn) {
        val pb = compile(Fn("MAIN", mainLines), calleeFn, fA)
        val store = VariableStore()
        store.declareUserVar(intVar("PA"), calleeFn.name)
        store.declareUserVar(intVar("OUT"), calleeFn.name)
        store.declareUserVar(intVar("PA"), "A")
        run(pb, store)
        assertEquals(
            15L, store.userVar("OUT", calleeFn.name)!!.readInt(0, 0),
            "$code：被调第 1 形参必须是**外层**实参 15；" +
                "读到 7 = 名字表达式里 A(7) 的内层实参覆盖了外层暂存槽 R0",
        )
        assertEquals(0, store.callDepth(), "帧必须全部退出")
    }

    private fun assertFirstParamIs15(code: String, lines: Array<String>) {
        assertCalleeGot15(code, lines, callee("F7"))
    }

    // ------------------------------------------------------------------
    // 1. 主复现：CALLFORM（非 TRY，走 emitCallByName）
    // ------------------------------------------------------------------

    @Test
    fun `CALLFORM 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertFirstParamIs15("CALLFORM", mainCallFormBody)
    }

    // ------------------------------------------------------------------
    // 2. TRYCALLFORM（纯 TRY，同样走 emitCallByName）
    // ------------------------------------------------------------------

    @Test
    fun `TRYCALLFORM 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertFirstParamIs15(
            "TRYCALLFORM",
            // `TRYCALLFORM` 只在 `TRY_CODES`（查不到 ⇒ 跳下一行），**不在** `TRYC_CODES`
            // （块开），故它后面**不能**跟 `CATCH`；块开形态是 `TRYCCALLFORM`（见用例 3）。
            arrayOf("@MAIN", "TRYCALLFORM F{A(7)}(15)", "RETURN"),
        )
    }

    // ------------------------------------------------------------------
    // 3. TRYCCALLFORM（TRYC 两步式：stageArgs → evalTemp(name) → _CALL_BY_NAME c=hitFlag）
    //    真机 IRAI_CARRYOUT:264 走的正是这条
    // ------------------------------------------------------------------

    @Test
    fun `TRYCCALLFORM 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertFirstParamIs15(
            "TRYCCALLFORM",
            // `TRYC*` 语句本身就是块开语句（本引擎无独立的 `TRYC` 码，见 FunctionCode 表），
            // 紧跟 `CATCH` / `ENDCATCH` 即完整块。
            arrayOf(
                "@MAIN",
                "TRYCCALLFORM F{A(7)}(15)",
                "CATCH",
                "PRINTL HITCATCH",
                "ENDCATCH",
                "RETURN",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 4. 对照：名字表达式**不含**用户函数调用时，今日本来就对，修完不许变
    //    （`IRAI_一般{IRAI_ID % 1000}` 那种纯算术插值形态）
    // ------------------------------------------------------------------

    @Test
    fun `对照 名字表达式不含函数调用 外层实参照常到位`() {
        val pb = compile(
            // 名字 = "F" + RESULTS（未赋值 ⇒ 空）= "F"；纯插值不含任何用户函数调用。
            Fn("MAIN", arrayOf("@MAIN", "TRYCALLFORM F%RESULTS%(15)", "RETURN")),
            callee("F"),
        )
        val store = VariableStore()
        store.declareUserVar(intVar("PA"), "F")
        store.declareUserVar(intVar("OUT"), "F")
        run(pb, store)
        assertEquals(
            15L, store.userVar("OUT", "F")!!.readInt(0, 0),
            "纯插值名字（无内层用户函数调用）的实参通道本来就正确，修复不得改变",
        )
        assertEquals(0, store.callDepth(), "帧必须全部退出")
    }

    // ------------------------------------------------------------------
    // 5. 对照：内层调用**不在名字里**（在外层实参位）时，暂存相位本来就安全
    // ------------------------------------------------------------------

    @Test
    fun `对照 实参位含函数调用 名字为纯插值 实参照常到位`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "TRYCALLFORM F%RESULTS%(A(7))", "RETURN")),
            callee("F"),
            fA,
        )
        val store = VariableStore()
        store.declareUserVar(intVar("PA"), "F")
        store.declareUserVar(intVar("OUT"), "F")
        store.declareUserVar(intVar("PA"), "A")
        run(pb, store)
        assertEquals(
            7L, store.userVar("OUT", "F")!!.readInt(0, 0),
            "实参位的内层调用（stageArgs 先全求值到临时槽、再统一 MOV 进桶区）本来就安全",
        )
    }

    // ------------------------------------------------------------------
    // 6. 求值序锁死：**先定名、后求值实参**（与原版
    //    `FunctionIdentifier.cs:3572-3576` → `:3589-3598` 同序）。
    //    两个表达式各自有可观察副作用，只断言结果值抓不住顺序。
    // ------------------------------------------------------------------

    @Test
    fun `求值序 先名字后实参`() {
        // `NM()` 打印 "NM" 并返回 7 ⇒ 名字 = "F7"；`AG()` 打印 "AG" 并返回 15。
        val fNm = Fn(
            "NM", arrayOf("@NM", "#FUNCTION", "PRINTL NM", "RETURNF 7", "RETURN"),
            isMethod = true, exprInt = true,
        )
        val fAg = Fn(
            "AG", arrayOf("@AG", "#FUNCTION", "PRINTL AG", "RETURNF 15", "RETURN"),
            isMethod = true, exprInt = true,
        )
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALLFORM F{NM()}(AG())", "RETURN")),
            callee("F7"), fNm, fAg,
        )
        val store = VariableStore()
        store.declareUserVar(intVar("PA"), "F7")
        store.declareUserVar(intVar("OUT"), "F7")
        val rt = run(pb, store)
        // 名字先算 ⇒ 先打印 NM；实参后算 ⇒ 再打印 AG。反过来（旧发射序）会得到 AG、NM。
        assertEquals(
            "NM\nAG\n", rt.output.toString(),
            "FORM 系的求值序必须是「先定名、后求值实参」（原版 FunctionIdentifier.cs 同序）；" +
                "读到 AG、NM = 仍在先暂存实参、后求值名字",
        )
        // 顺序对了，语义也对：被调拿到的是外层实参 15，不是 NM 的返回值 7 覆盖。
        assertEquals(15L, store.userVar("OUT", "F7")!!.readInt(0, 0), "外层实参照常到位")
    }

    // ------------------------------------------------------------------
    // 7–9. 跳转族同形（JUMPFORM / TRYJUMPFORM / TRYCJUMPFORM）：
    //      与 CALLFORM 族复用同一发射点（compileJumpLike 的 FORM 分支），
    //      区别只有 bit30（VM 走 jumpInto 尾跳）。尾跳后被调 RETURN 直接结束整条链。
    // ------------------------------------------------------------------

    @Test
    fun `JUMPFORM 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertFirstParamIs15(
            "JUMPFORM",
            arrayOf("@MAIN", "JUMPFORM F{A(7)}(15)", "RETURN"),
        )
    }

    @Test
    fun `TRYJUMPFORM 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertFirstParamIs15(
            "TRYJUMPFORM",
            arrayOf("@MAIN", "TRYJUMPFORM F{A(7)}(15)", "RETURN"),
        )
    }

    @Test
    fun `TRYCJUMPFORM 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertFirstParamIs15(
            "TRYCJUMPFORM",
            arrayOf(
                "@MAIN",
                "TRYCJUMPFORM F{A(7)}(15)",
                "CATCH",
                "PRINTL HITCATCH",
                "ENDCATCH",
                "RETURN",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 10–11. CALLFORMF / TRYCALLFORMF：**独立发射点**在 compileCallF 的
    //        动态名分支，额外置 bit29 走 resolveMethodIndex（只认方法）。
    // ------------------------------------------------------------------

    @Test
    fun `CALLFORMF 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        assertCalleeGot15(
            "CALLFORMF",
            arrayOf("@MAIN", "CALLFORMF F{A(7)}(15)", "RETURN"),
            calleeMethod("F7"),
        )
    }

    @Test
    fun `TRYCALLFORMF 名字表达式含用户函数调用 外层实参不被内层实参覆盖`() {
        // 注意：`TRYCALLFORMF` 只在 `TRY_CODES`（目标缺失 ⇒ 跳过本行），**不在**
        // `TRYC_CODES`（块开）—— 它后面不能跟 `CATCH`（跟了会报「CATCH 与所属块不匹配」），
        // 与 `TRYCCALLFORM`（TRYC 块头，必须配 `CATCH`/`ENDCATCH`）不同形。
        assertCalleeGot15(
            "TRYCALLFORMF",
            arrayOf("@MAIN", "TRYCALLFORMF F{A(7)}(15)", "RETURN"),
            calleeMethod("F7"),
        )
    }
}
