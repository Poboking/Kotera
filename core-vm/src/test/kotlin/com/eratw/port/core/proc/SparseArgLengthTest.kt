package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.CallDefaultsCollector
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **稀疏形参下标的 ARG/ARGS 声明长** —— `数组变量"ARG"的第1参数(3)超出数组范围` 的回归锁。
 *
 * ## 现场（2026-11-12 真机 eratohoReverse / v24_1824 档）
 *
 * 第三方语料 `PANIMATION.ERB:905` 的 `@FADEOUT(ARG = 8, ARGS = "VOID", …, ARG:3)`
 * 是稀疏形参（整型下标只有 0 与 3），`CHARA_MAKER.ERB:47` 的 `CALL BGM_CUTIN(...)`
 * 经 `PANIMATION_TOHO.ERB:60` 的 `CALL FADEOUT(8, …)` 调进来时抛
 * `com.eratw.port.core.err.CodeEE: 数组变量"ARG"的第1参数(3)超出数组范围`。
 *
 * ## 原版口径
 *
 * 函数内 `ARG`/`ARGS` 的长度是 `max(下标 + 1)`，不是形参**个数**：
 * - `ErbLoader.cs:565-576`：`if (maxArg < vTerm.getEl1forArg + 1) maxArg = …`（ARGS 同段）；
 * - `ErbLoader.cs:612-613`：`label.ArgLength = maxArg; label.ArgsLength = maxArgs;`
 * - `VariableLocal.cs:37-58`：`newSize > 0` 时 ARG/ARGS 取 `max(newSize, VARIABLESIZE.CSV 档)`。
 *
 * 移植侧旧实现把 [FuncMeta.argIntCount]（形参个数）当声明长：`@FADEOUT` 的 ARG 得 2、
 * 原版是 4。eraTW 4.981 的 `VariableSize.csv` 里 `ARG,10` 恰好盖住这个少算；
 * 第三方语料把 `ARG,1` 缩到底 ⇒ 省略位写回 `ARG[3]` 直接越界。
 *
 * 本文件锁两条通路（都会逐位写省略位）：
 * 1. 有实参的 `CALL`（`bindCallArgs` 的省略位分支）；
 * 2. 零实参 `CALL`（`bindZeroArgImplicitDefaults`）。
 */
class SparseArgLengthTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("OUTS", OperandType.STR, 1),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "sparsearg.erb", i + 1, text))
        }
    }

    private class Pb(val names: List<String>, val program: Program) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    /** 解析 → 采集默认值 → 编译（口径与装载装置一致，见 `DefaultArgTest` 的同名装置）。 */
    private fun compile(vararg slices: Pair<String, Array<String>>): Pb {
        val parsed = slices.map { parse(*it.second) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val collector = CallDefaultsCollector()
        for ((i, label) in labels.withIndex()) collector.add(slices[i].first, label.paramDefaults)
        val compiler = ErbProgramCompiler(callDefaults = collector.build())
        val known = slices.mapTo(HashSet()) { it.first.uppercase() }
        for ((i, slice) in slices.withIndex()) {
            val label = labels[i]
            compiler.compileFunction(
                FuncSig(
                    name = slice.first,
                    arity = label.paramNames.size,
                    isMethod = false,
                    returnsStr = false,
                    paramNames = label.paramRefs,
                ),
                parsed[i],
                known,
                null,
            )
        }
        return Pb(slices.map { it.first }, compiler.build(slices.first().first))
    }

    /** 元数据按 erbcc 分桶口径：`ARGS*` 名字算字符串桶，其余算整型桶。 */
    private fun metas(pb: Pb): List<FuncMeta> = pb.names.map { name ->
        val refs = pb.program.paramNames(pb.index(name))
        val str = refs.count { it.substringBefore(':').startsWith("ARGS", ignoreCase = true) }
        FuncMeta(
            index = pb.index(name),
            key = name,
            argIntCount = refs.size - str,
            argStrCount = str,
            defIndex = pb.index(name),
        )
    }

    /** 第三方现场的最小复刻：`VariableSize.csv` 把 ARG/ARGS 的档位缩到 1。 */
    private fun smallStore(): VariableStore {
        val sizes = VariableSizes().apply {
            applySize(VariableCode.ARG, intArrayOf(1))
            applySize(VariableCode.ARGS, intArrayOf(1))
        }
        return VariableStore(VariableRegistry(sizes))
    }

    private fun run(pb: Pb, store: VariableStore) {
        val rt = ProcessRuntime(pb.program, store, FunctionTable(metas(pb)), VmConfig())
        rt.execute()
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
    }

    private fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)

    /** `@F(ARG = 8, ARGS = "VOID", ARGS:1 = "VOID", ARG:3)`：整型下标 {0, 3}。 */
    private val sparseArg = arrayOf(
        "@F(ARG = 8 , ARGS = \"VOID\" , ARGS:1 = \"VOID\" , ARG:3)",
        "#DIM OUT",
        "OUT = ARG * 10 + ARG:3",
        "RETURN",
    )

    private fun runSparse(callLine: String): Long {
        val pb = compile("MAIN" to arrayOf("@MAIN", callLine, "RETURN"), "F" to sparseArg)
        val store = smallStore()
        store.declareUserVar(intVar("OUT"), "F")
        run(pb, store)
        return store.userVar("OUT", "F")!!.readInt(0, 0)
    }

    @Test
    fun `有实参时省略位写回稀疏下标不越界`() {
        assertEquals(
            10L, runSparse("CALL F(1)"),
            "ARG=1、ARG:3 省略位写 0（原版 ConvertArg：Def[3]=0）⇒ 10；旧口径 ARG 长 2 只会在这里抛 CodeEE",
        )
        assertEquals(
            17L, runSparse("CALL F(1 , \"a\" , \"b\" , 7)"),
            "四位全给：ARG=1、ARG:3=7 ⇒ 17（下标逐位而非按序写）",
        )
    }

    /** 声明长本身：ARG = `max(下标 + 1)` = 4（不是形参个数 2），ARGS 稠密 = 2。 */
    @Test
    fun `稀疏ARG的声明长按最大下标加一`() {
        val pb = compile("MAIN" to arrayOf("@MAIN", "CALL F(1)", "RETURN"), "F" to sparseArg)
        val store = smallStore()
        store.declareUserVar(intVar("OUT"), "F")
        run(pb, store)
        store.enterFunction("F")
        try {
            assertEquals(4, store.getLength("ARG"), "整型下标 {0, 3} ⇒ 长 4（ErbLoader.cs:565-576,612）")
            assertEquals(2, store.getLength("ARGS"), "ARGS/ARGS:1 稠密 ⇒ 2")
            assertEquals(0L, store.readInt("ARG", longArrayOf(3)), "省略位写回 ARG[3] = 0")
        } finally {
            store.leaveFunction()
        }
    }

    @Test
    fun `零实参时逐位省略同样写稀疏下标`() {
        assertEquals(
            80L, runSparse("CALL F"),
            "零实参调用点逐位取 Def（CalledFunction.cs:133-200）：ARG=装载期默认 8、ARG:3=0 ⇒ 80",
        )
    }

    /** 字符串桶同一病型：`@H(ARGS:5)` 的 ARGS 声明长是 6 而不是 1。 */
    @Test
    fun `稀疏ARGS下标同样按max下标加一开长`() {
        val pb = compile(
            "MAIN" to arrayOf("@MAIN", "CALL H(\"abc\")", "RETURN"),
            "H" to arrayOf("@H(ARGS:5)", "RETURN"),
        )
        val store = smallStore()
        run(pb, store)
        store.enterFunction("H")
        try {
            assertEquals(6, store.getLength("ARGS"), "下标 5 ⇒ 长 6（不是形参个数 1）")
            assertEquals("abc", store.readStr("ARGS", longArrayOf(5)), "实参逐位落到 ARGS[5]（不是 ARGS[0]）")
        } finally {
            store.leaveFunction()
        }
    }

    /**
     * 无段 8（`.erbc` v1）时声明长只能回落元数据里的形参个数 —— 与改造前逐字同值，
     * 保证旧产物读数不漂移。
     */
    @Test
    fun `无形参名表时回落形参个数`() {
        assertEquals(2, VariableStore.LocalLengths.declaredLength(emptyList(), "ARG", 2), "v1 容器只有个数")
        assertEquals(0, VariableStore.LocalLengths.declaredLength(emptyList(), "ARG", 0), "无个数的空函数")
    }
}
