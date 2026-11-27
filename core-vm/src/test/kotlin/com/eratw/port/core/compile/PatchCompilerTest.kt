package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.frontend.DimKeyword
import com.eratw.port.core.frontend.DimSpec
import com.eratw.port.core.frontend.LabelTable
import com.eratw.port.core.frontend.labelTableSizeConsts
import com.eratw.port.core.frontend.sizeConsts as deviceSizeConsts
import com.erbcc.bridge.sizeConsts as desktopSizeConsts
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.patch.PATCH_FORMAT_VERSION_2
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R7-C1 设备端补丁编译器的出口判据（CONTRACT-R7-C §2/C1 ①–④）。
 *
 * ## 判据与用例对照
 *
 * 1. **A1 同尺对拍**（[A1 同尺对拍——补丁编译的 B 段与全量编译逐字节一致]）：
 *    「全量编译 A+B」vs「全量编译 A 后用 [PatchCompiler] 以 A 的产物计数 + 侧车为上下文
 *    续编 B」——补丁 Program 的新增指令/语句/常量/表达式与全量档 B 段逐字节一致，
 *    函数表尾段（name/entryPc/arity/paramNames/标签表）与调用点表（pc 键绝对）逐项一致。
 *    夹具口径：B 组引用的常量/字符串与 A 组**池自包含**——全量编译跨 A/B 去重池条目，
 *    补丁编译拿不到 base 池内容、必然新增一份副本；这是「共享 vs 新增」的本质差异
 *    （补丁落盘独立于 base，装载期 overlay 拼接后功能等价），对拍判据的「新增条目」
 *    只在这条自包含边界内可判，用例注释逐处标注。
 * 2. **param-defaults 补位**（[param-defaults 补位——侧车有默认值时省略实参编译期补位]）：
 *    侧车带默认值 → 补位成功（调用位 3 位 present + argc=3）；空侧车同场景 → 编译期拒绝
 *    并点名函数（I6-④，[I6 四——空侧车下补丁省略实参编译期拒绝并点名]）。
 * 3. **sizeConsts 两端同解**（三个用例组）：同一份侧车输入，桌面
 *    `com.erbcc.bridge.sizeConsts` 与设备 `sizeConsts` 逐键相等；宏赢语义两端一致；
 *    [LabelTable.build] 的注入形态（[labelTableSizeConsts]）与桌面求值器对同一份
 *    ERH 收集物同解。
 * 4. **I6 四条负向**（I6 一/二/二之二/三/四）：保留事件函数 / 全局 `#DIM`（含函数体内
 *    全局侧变体）/ base 同名函数 / 空侧车省略实参——全部「可定位」（错误消息带函数名 +
 *    文件:行），命中即编译期拒绝（I5 全或无，不落盘）。
 *
 * 另有一个端到端产物链用例（[补丁 Program 经 ErbcWriter 与 PatchArtifacts 落盘读回逐字节往返]）：
 * 编译产物 → `ErbcWriter.encode`（L1 的基线窗口编码）→ `PatchArtifacts.of`（I8 写侧闸）
 * → `PatchArtifacts.read` 逐字节往返。
 *
 * ## R7-D2 补记（CONTRACT-R7-D §2/D2，C1 TODO ①②③ 的出口判据）
 *
 * 5. **宏展开接线**（三个用例组）：补丁源正文引用侧车 `dim-config` 段宏（整数体/
 *    表达式体）与全量编译逐字节一致（A1 同尺模式，两侧走 core/lexer 的 expandMacros
 *    单源通道）；未定义宏引用明确拒绝并点名；空体宏引用展开为空 → 解析失败
 *    （桌面同向，spec: ErhLoader.cs:167-171）。
 * 6. **localSize 透传**（两个用例）：补丁函数的 `#LOCALSIZE`/`#LOCALSSIZE` 声明经
 *    LineParser 的 dimConsts 折常量后进 FuncMeta（与全量链路 D.8.1 求值器同源），
 *    读数与侧车 function 段同字段口径逐位相等，零缺省保持 0；宏体形态同解。
 * 7. **短路位 I6-④ 等价判据**（三个用例）：空侧车 + `&&` 右侧 base 方法省略实参
 *    → 编译期拒绝并点名（形态可达性的证据钉：这类调用点不产生 callArgs，R7-D2 之前
 *    的 callRefs 闸不可见）；非空侧车同场景补位落进保持节点（语句池 RHS 可读）；
 *    补全实参放行。
 *
 * 语义证据（C# / 侧车行号）见 [PatchCompiler] 的类 KDoc 与各用例内注释。
 */
class PatchCompilerTest {

    // ------------------------------------------------------------------
    // 夹具：mini base（A 组）+ mini 补丁（B 组）
    // ------------------------------------------------------------------

    /**
     * A 组：A_ONE/A_TWO（普通函数，B 会调）、A_SUM（`#FUNCTION` 方法，B 表达式位调用）、
     * A_DEF（带默认值形参，param-defaults 场景）。B 组不引用 A 组的池条目（常量 1 只在
     * A 组出现；B 组用 3/5/6/42），保证「新增条目」对拍边界自包含。
     */
    private val baseSource = """
        @A_ONE
        CALL A_TWO(1)
        RETURN 0

        @A_TWO(N)
        X = N
        RETURN 0

        @A_SUM
        #FUNCTION
        RETURNF ARG:0 + ARG:1

        @A_DEF(A, B = 7, C = 9)
        X = A
        RETURN 0
    """.trimIndent()

    /**
     * B 组：B_HELLO（调 base 函数）、B_SUM（`#FUNCTION`，调 base 方法 + 补丁内方法）、
     * B_CONST（`#FUNCTION`，被 B_SUM 表达式位调用——补丁内方法必须进 exprCalls 才能和
     * 全量编译走同一条降级路径，见 [PatchCompiler] 步骤 7）。
     */
    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0

        @B_SUM
        #FUNCTION
        RETURNF A_SUM(5, 6) + B_CONST()

        @B_CONST
        #FUNCTION
        RETURNF 42
    """.trimIndent()

    /** 补丁调用 A_DEF 并省略后两位实参（param-defaults 场景）。 */
    private val patchCallDefSource = """
        @B_CALLDEF
        CALL A_DEF(9)
        RETURN 0
    """.trimIndent()

    /**
     * 侧车 dim-config 段宏表（compileBase 与宏对拍用例共用同一实例内容，防字面量漂移）：
     * M_BASE = 整数体、M_CHAIN = 宏→宏表达式体（R7-D2 判据①的两种可展开形态）。
     */
    private val sidecarMacros = linkedMapOf("M_BASE" to "10", "M_CHAIN" to "M_BASE + 5")

    /** A 组/B 组全部函数名（两侧 knownFunctions 必须同集，TRY 系判定与降级面才同源）。 */
    private val allNames = setOf(
        "A_ONE", "A_TWO", "A_SUM", "A_DEF", "B_HELLO", "B_SUM", "B_CONST",
    )

    /** 表达式位方法分类（两侧同表：A_SUM 与 B_CONST 都是 `#FUNCTION` 整型方法）。 */
    private val allExprCalls = ExprCallTable(
        intMethods = setOf("A_SUM", "B_CONST"),
        nonMethodLabels = setOf("A_ONE", "A_TWO", "A_DEF"),
    )

    /** 形参名与用到的用户变量（解析期 SymbolTable；ARG/X 系统变量走 BuiltinSymbols）。
     *  表达式位方法调用（`A_SUM(5, 6)`）在解析期需要返回类型 → base/补丁方法名预声明
     *  （生产口径：导入链用 GameDataLoader/侧车 methodTypes 灌同一张表）。 */
    private fun patchSymbols(): SymbolTable = SymbolTable()
        .declareVariable("X", OperandType.INT, 1)
        .declareVariable("N", OperandType.INT, 1)
        .declareVariable("A", OperandType.INT, 1)
        .declareVariable("B", OperandType.INT, 1)
        .declareVariable("C", OperandType.INT, 1)
        .declareFunction("A_SUM", OperandType.INT)
        .declareFunction("B_CONST", OperandType.INT)

    /** A 组内各函数的 (isMethod, returnsStr)（`#FUNCTION` 声明的测试侧替身）。 */
    private val baseHeads = mapOf(
        "A_ONE" to (false to false),
        "A_TWO" to (false to false),
        "A_SUM" to (true to false),
        "A_DEF" to (false to false),
    )

    private val patchHeads = mapOf(
        "B_HELLO" to (false to false),
        "B_SUM" to (true to false),
        "B_CONST" to (true to false),
    )

    // ------------------------------------------------------------------
    // mini 装置：切片 + ErbProgramCompiler（A 组全量路径；与 RealCorpusCompile 同口径的简化版）
    // ------------------------------------------------------------------

    /**
     * 按 `@` 行切片并顺序编译（SharpStmt 不进函数体；FuncLabelStmt 留在体内供默认值收集）。
     * 多文件输入（文件序 = 装载序）——与 [PatchCompiler] 的 sources 口径一致，行号各自
     * 从文件的 0 基起算，A1 对拍的行号表才逐条可比。
     *
     * [lexerConfig]（R7-D2 宏对拍）：erbcc 全量 CorpusCompile.kt:313-320 的同源口径——
     * 同一份配置同时喂 LineParser 与 LogicalLineReader（后者附宏名表）。缺省无宏 = 既有
     * 12 例的装置行为逐位不变。
     */
    private fun compileMini(
        files: List<Pair<String, String>>,
        sym: SymbolTable,
        known: Set<String>,
        exprCalls: ExprCallTable?,
        callDefaults: CallDefaults,
        heads: Map<String, Pair<Boolean, Boolean>>,
        lexerConfig: LexerConfig = LexerConfig(),
    ): Program {
        val parser = LineParser(sym, lexerConfig = lexerConfig)
        val groups = ArrayList<Pair<String, List<Statement>>>()
        for ((rel, text) in files) {
            val reader = LogicalLineReader(
                LogicalLineReader.readAllLines(text.toByteArray(Charsets.UTF_8)),
                rel,
                config = lexerConfig,
                macroNames = lexerConfig.macros.keys,
            )
            var cur: String? = null
            val body = ArrayList<Statement>()
            fun flush() {
                val key = cur ?: return
                groups.add(key to body.toList())
                body.clear()
            }
            while (true) {
                val line = reader.readLogicalLine() ?: break
                val stmt = parser.parseLogicalLine(line) ?: continue
                when {
                    stmt is FuncLabelStmt -> {
                        flush()
                        cur = stmt.name.uppercase()
                        body.add(stmt)
                    }
                    stmt is SharpStmt -> Unit
                    cur != null -> body.add(stmt)
                }
            }
            flush()
        }
        val compiler = ErbProgramCompiler(callDefaults = callDefaults)
        for ((name, stmts) in groups) {
            val label = stmts.filterIsInstance<FuncLabelStmt>().first()
            val (isMethod, returnsStr) = heads[name] ?: (false to false)
            compiler.compileFunction(
                FuncSig(name, label.paramNames.size, isMethod, returnsStr, label.paramNames),
                stmts, known, exprCalls,
            )
        }
        return compiler.build(groups.first().first)
    }

    /** A 组编译 + 侧车元数据装配（FuncMeta 按编译序定绝对下标；`method-types` 只记方法）。
     *  [extraMacros]（R7-D2 空体宏用例）：并入侧车 dim-config 段宏表的追加条目，缺省空 =
     *  既有用例行为不变。 */
    private fun compileBase(
        paramDefaults: CallDefaults = emptyMap(),
        sym: SymbolTable = patchSymbols(),
        extraMacros: Map<String, String> = emptyMap(),
    ): Triple<Program, BootMetadata, PatchCompiler.BaseCounts> {
        val p = compileMini(
            listOf("mini.ERB" to baseSource), sym, allNames, allExprCalls, paramDefaults, baseHeads,
        )
        // 侧车 function 段的形参数（argIntCount + argStrCount = arity；A_TWO(N) 1 个、
        // A_DEF(A,B,C) 3 个、其余 0）——I6-④ 的省略判定用它当 base arity。
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        val meta = BootMetadata(
            functions = p.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i,
                    key = f.name,
                    isMethod = f.name == "A_SUM",
                    argIntCount = argCounts[f.name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
            paramDefaults = paramDefaults,
            dimParseConfig = DimParseConfig(
                macros = sidecarMacros + extraMacros,
                intConstants = linkedMapOf("C_GREEN" to 0x00FF00L),
            ),
        )
        val counts = PatchCompiler.BaseCounts(
            instructionCount = p.instructionCount,
            functionCount = p.functions.size,
            statementCount = p.statementCount,
            stringCount = p.stringCount,
            longCount = p.constantCount,
            exprCount = p.exprCount,
        )
        return Triple(p, meta, counts)
    }

    private fun patchCompile(
        meta: BootMetadata,
        counts: PatchCompiler.BaseCounts,
        sym: SymbolTable,
        source: String = patchSource,
        entry: String = "B_HELLO",
        /** 常量关键字折叠的数据面（生产 = 导入链按设备 CSV/ERD 构造；null = 旧行为）。 */
        constantKeywordLookup: ((String, Int, String) -> Long?)? = null,
    ): PatchCompiler.PatchResult =
        PatchCompiler(meta, counts, sym, constantKeywordLookup = constantKeywordLookup).compile(
            listOf(PatchCompiler.SourceFile("mods/patch.ERB", source)),
            entry,
        )

    /** 显式钉告警门（原版 `Config.WarnNormalFunctionOverloading`）：三处用例都用 [patchSymbols]。 */
    private fun patchCompileGated(
        meta: BootMetadata,
        counts: PatchCompiler.BaseCounts,
        source: String,
        entry: String,
        gated: Boolean,
    ): PatchCompiler.PatchResult =
        PatchCompiler(meta, counts, patchSymbols(), warnNormalFunctionOverloading = gated).compile(
            listOf(PatchCompiler.SourceFile("mods/patch.ERB", source)),
            entry,
        )

    // ------------------------------------------------------------------
    // 判据 1：A1 同尺对拍
    // ------------------------------------------------------------------

    @Test
    fun `A1 同尺对拍——补丁编译的 B 段与全量编译逐字节一致`() {
        val sym = patchSymbols()
        val (pA, meta, counts) = compileBase(sym = sym)
        // 全量档：A+B 一次性编译（既有路径，基线 0；B 文件的行号 = 补丁源自己的行）。
        val pFull = compileMini(
            listOf("mini.ERB" to baseSource, "mods/patch.ERB" to patchSource),
            sym, allNames, allExprCalls, emptyMap(),
            baseHeads + patchHeads,
        )
        // 补丁档：PatchCompiler 以 pA 的计数 + 侧车续编 B。
        val result = patchCompile(meta, counts, sym)
        val pPatch = result.program

        // ---- 基线读数：绝对口径 = base + 新增（L1 出口判据②的补丁侧形态）----
        assertEquals(counts.instructionCount, pPatch.instructionBase)
        assertEquals(counts.functionCount, pPatch.functionBase)
        assertEquals(counts.statementCount, pPatch.statementBase)
        assertEquals(counts.stringCount, pPatch.stringBase)
        assertEquals(counts.longCount, pPatch.longBase)
        assertEquals(counts.exprCount, pPatch.exprBase)

        // ---- 指令流逐字节：pFull 的 B 段切片 vs pPatch 段内本体 ----
        val aWords = pA.code.size
        assertContentEquals(
            pFull.code.copyOfRange(aWords, pFull.code.size),
            pPatch.code,
            "补丁段指令流必须与全量档 B 段逐字节相同（含全部绝对下标操作数）",
        )
        assertContentEquals(
            pFull.lines.copyOfRange(aWords / Instruction_WORDS, pFull.lines.size),
            pPatch.lines,
            "行号表逐条相同（B 文件行号口径两端一致：都是补丁源自己的行）",
        )

        // ---- 常量池逐条（B 组新增 3/5/6/42；A 组只有 1，池自包含）----
        assertEquals(pFull.constantCount, pPatch.constantCount)
        for (i in counts.longCount until pFull.constantCount) {
            assertEquals(pFull.constant(i), pPatch.constant(i), "常量池下标 $i")
        }

        // ---- 表达式池逐条（B 组新增一条 Binary；Expr 是 data class，逐位 equals）----
        assertEquals(pFull.exprCount, pPatch.exprCount)
        for (i in counts.exprCount until pFull.exprCount) {
            assertEquals(pFull.expr(i), pPatch.expr(i), "表达式池下标 $i")
        }

        // ---- 语句池逐条（Statement 无值语义，按 类型 + 行 + 原文 断言）----
        assertEquals(pFull.statementCount, pPatch.statementCount)
        for (i in counts.statementCount until pFull.statementCount) {
            val f = pFull.statement(i)
            val g = pPatch.statement(i)
            assertEquals(f::class, g::class, "语句池下标 $i 的类型")
            assertEquals(f.line, g.line, "语句池下标 $i 的行号")
            assertEquals(f.raw, g.raw, "语句池下标 $i 的原文")
        }

        // ---- 函数表尾段：name/entryPc/arity/slotCount/paramNames/标签表逐项一致 ----
        assertEquals(pA.functions.size, pFull.functions.size - pPatch.functions.size)
        for (i in pPatch.functions.indices) {
            val pf = pPatch.functions[i]
            val ff = pFull.functions[pA.functions.size + i]
            assertEquals(ff.name, pf.name, "补丁函数 $i 的名字")
            assertEquals(ff.entryPc, pf.entryPc, "补丁函数 ${pf.name} 的入口 pc（绝对）")
            assertEquals(ff.arity, pf.arity, "补丁函数 ${pf.name} 的形参数")
            assertEquals(ff.slotCount, pf.slotCount, "补丁函数 ${pf.name} 的帧槽数")
            assertEquals(ff.paramNames, pf.paramNames, "补丁函数 ${pf.name} 的形参名")
            assertEquals(ff.labelNames.toList(), pf.labelNames.toList(), "补丁函数 ${pf.name} 的标签名")
            assertContentEquals(ff.labelPcs, pf.labelPcs, "补丁函数 ${pf.name} 的标签 pc（绝对）")
        }

        // ---- 调用点表：pc 键绝对且与全量档逐位一致（I2 的数据面前提）----
        val fullPatchCallPcs = pFull.callArgs.keys.filter { it >= counts.instructionCount }.sorted()
        assertEquals(fullPatchCallPcs, pPatch.callArgs.keys.sorted(), "B 段调用点的 pc 键集")
        for ((pc, args) in pPatch.callArgs) {
            assertEquals(pFull.callArgsAt(pc), args, "调用点 $pc 的逐位实参元数据")
        }

        // ---- FuncMeta：绝对下标从 base 函数数起算，装载序恒在 base 之后（铁律 1）----
        for ((i, m) in result.funcMetas.withIndex()) {
            assertEquals(counts.functionCount + i, m.index, "补丁函数表绝对下标")
            assertEquals(counts.functionCount + i, m.defIndex, "装载序号恒接在 base 之后")
        }
        assertEquals(counts.functionCount, result.funcMetas.first().index)
    }

    // ------------------------------------------------------------------
    // 判据 2：param-defaults 补位
    // ------------------------------------------------------------------

    @Test
    fun `param-defaults 补位——侧车有默认值时省略实参编译期补位`() {
        // A 组编译时同款采集（CallDefaultsCollector 只收字面量默认值；A_DEF 的 7 与 "d" 都是）。
        val collector = CallDefaultsCollector()
        val symA = patchSymbols()
        val parser = LineParser(symA)
        val reader = LogicalLineReader(LogicalLineReader.readAllLines(baseSource.toByteArray(Charsets.UTF_8)), "mini.ERB")
        while (true) {
            val line = reader.readLogicalLine() ?: break
            val stmt = parser.parseLogicalLine(line) ?: continue
            if (stmt is FuncLabelStmt && stmt.name.uppercase() == "A_DEF") {
                collector.add("A_DEF", stmt.paramDefaults)
            }
        }
        val sidecar = collector.build()
        val aDefDefs = sidecar["A_DEF"]
        assertEquals(3, aDefDefs?.size, "侧车表与形参逐位等长")
        assertEquals(null, aDefDefs?.get(0), "A 位无默认值")
        assertEquals(7L, (aDefDefs?.get(1) as? IntLiteral)?.value, "B 位默认值 = 7")
        assertEquals(9L, (aDefDefs?.get(2) as? IntLiteral)?.value, "C 位默认值 = 9")
        val sym = patchSymbols()
        val (pA, meta, counts) = compileBase(paramDefaults = sidecar, sym = sym)
        val result = patchCompile(meta, counts, sym, source = patchCallDefSource, entry = "B_CALLDEF")
        val pPatch = result.program

        // 找到 B 段的 _CALL A_DEF（a = A_DEF 的 base 绝对下标 3）。
        val aDefIndex = pA.functions.indexOfFirst { it.name == "A_DEF" }
        var callPc = -1
        for (pc in pPatch.instructionBase until pPatch.instructionCount) {
            if (pPatch.op(pc) == Opcode._CALL && pPatch.a(pc) == aDefIndex) {
                callPc = pc
                break
            }
        }
        assertTrue(callPc >= 0, "补丁段里必须有 CALL A_DEF")
        val args = pPatch.callArgsAt(callPc)
        assertEquals(3, args?.size, "省略位被默认值补齐到形参个数（spec: CalledFunction.cs:167 Def[i] 编译期顶替）")
        assertTrue(args?.all { it.present } == true, "补位后三位全 present（无省略位残留）")
    }

    // ------------------------------------------------------------------
    // R7-D2 判据 ①：#DEFINE 宏展开接线（侧车 dim-config 宏表 → lexer 单源通道）
    // ------------------------------------------------------------------

    @Test
    fun `宏展开接线——补丁源引用侧车宏与全量编译逐字节一致`() {
        // 整数体（M_BASE = 10）与表达式体（M_CHAIN = M_BASE + 5，宏→宏链）两种形态：
        // 全量档与补丁档走同一条 lexer 展开通道（ErbLexer.analyse 末尾 expandMacros，
        // spec: LexicalAnalyzer.cs:942-1006）——erbcc 全量 CorpusCompile.kt:313-320 的
        // 同源口径（同一份 LexerConfig 喂 LineParser 与 LogicalLineReader）。
        val sym = patchSymbols().declareVariable("Y", OperandType.INT, 1)
        val macroPatchSource = """
            @B_MACRO
            X = M_BASE
            Y = M_CHAIN
            RETURN 0
        """.trimIndent()
        val (pA, meta, counts) = compileBase(sym = sym)
        val pFull = compileMini(
            listOf("mini.ERB" to baseSource, "mods/patch.ERB" to macroPatchSource),
            sym, allNames + "B_MACRO", allExprCalls, emptyMap(),
            baseHeads + mapOf("B_MACRO" to (false to false)),
            lexerConfig = LexerConfig(macros = sidecarMacros),
        )
        // 补丁档：PatchCompiler 以侧车 dim-config 宏表（metadata.dimParseConfig.macros
        // = sidecarMacros 同表）续编 B。
        val result = patchCompile(meta, counts, sym, source = macroPatchSource, entry = "B_MACRO")
        val pPatch = result.program

        val aWords = pA.code.size
        assertContentEquals(
            pFull.code.copyOfRange(aWords, pFull.code.size),
            pPatch.code,
            "补丁段指令流必须与全量档 B 段逐字节相同（宏展开两侧同一条 lexer 通道）",
        )
        assertContentEquals(
            pFull.lines.copyOfRange(aWords / Instruction_WORDS, pFull.lines.size),
            pPatch.lines,
            "行号表逐条相同",
        )
        // 展开产物：M_BASE → IntLiteral(10)；M_CHAIN → Binary(10 + 5)（宏→宏链在
        // expandMacros 的迭代循环里二次展开）。两侧表达式池逐条同构。
        assertEquals(pFull.exprCount, pPatch.exprCount)
        for (i in counts.exprCount until pFull.exprCount) {
            assertEquals(pFull.expr(i), pPatch.expr(i), "表达式池下标 $i")
        }
        assertEquals(pFull.statementCount, pPatch.statementCount)
        for (i in counts.statementCount until pFull.statementCount) {
            assertEquals(pFull.statement(i)::class, pPatch.statement(i)::class, "语句池下标 $i 的类型")
            assertEquals(pFull.statement(i).raw, pPatch.statement(i).raw, "语句池下标 $i 的原文")
        }
    }

    @Test
    fun `宏展开——未定义宏引用明确拒绝并点名`() {
        // 宏表没有的名字保持 Identifier token → 表达式解析失败（「未声明名 → 解析失败」
        // 的明确失败方向，不静默当 0）。切片层的失败消息携带原文行（PatchCompiler.slice
        // 的 catch 形态），M_NOPE 可定位。
        val (_, meta, counts) = compileBase()
        val src = "@B_NOMAC\nX = M_NOPE\nRETURN 0\n"
        val ex = assertFailsWith<PatchCompiler.PatchCompileException>("未定义宏引用必须明确拒绝") {
            patchCompile(meta, counts, patchSymbols(), source = src, entry = "B_NOMAC")
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("M_NOPE"), "错误消息必须点名未定义名，实际: $msg")
        assertTrue(msg.contains("mods/patch.ERB:2"), "错误消息必须带文件:行，实际: $msg")
    }

    @Test
    fun `宏展开——空体宏引用与桌面同向明确失败`() {
        // 空体宏（`#DEFINE 名` 无替换式）按符号层收集口径以体 = 空串进侧车宏表
        // （Symbols.kt:1080，spec: ErhLoader.cs:167-171 DefineMacro.IsNull）——词法展开为
        // 空 token 流，随后的解析失败方向与桌面一致（不能当数值用）。尺寸求值侧的
        // 「空体不进表」由 foldSizeConstants 负责（sizeConsts 两端同解用例已钉），两表各司其职。
        val (_, meta, counts) = compileBase(extraMacros = linkedMapOf("M_EMPTY" to ""))
        val src = "@B_EMAC\nX = M_EMPTY\nRETURN 0\n"
        val ex = assertFailsWith<PatchCompiler.PatchCompileException>("空体宏引用必须明确失败（桌面同向）") {
            patchCompile(meta, counts, patchSymbols(), source = src, entry = "B_EMAC")
        }
        val msg = ex.message ?: ""
        // 失败形态（实测）：展开后 RHS 为空 → 「SET命令:无法读取右值」，切片层消息带
        // 文件:行 + 原样文案（`| X = M_EMPTY`）——定位到使用点行，与桌面「展开为空后
        // 该行报错」的方向一致。
        assertTrue(msg.contains("X = M_EMPTY"), "失败消息必须带使用点原样文案，实际: $msg")
        assertTrue(msg.contains("mods/patch.ERB:2"), "失败消息必须带文件:行，实际: $msg")
    }

    // ------------------------------------------------------------------
    // R7-D2 判据 ②：#LOCALSIZE/#LOCALSSIZE 透传补丁 FuncMeta
    // ------------------------------------------------------------------

    /** localSize 夹具的补丁源：B_LOCAL 带两条声明，B_PLAIN 不带（零缺省对照）。 */
    private val localSizePatchSource = """
        @B_LOCAL
        #LOCALSIZE 8
        #LOCALSSIZE 3
        X = 1
        RETURN 0

        @B_PLAIN
        X = 2
        RETURN 0
    """.trimIndent()

    /**
     * localSize 夹具的 base 装配：侧车 function 段带**非零 localSize 的 base 函数**
     * （A_DEF = 12）——全量链路把这个值写进侧车 function 段（CorpusCompile.kt:684 同字段、
     * BootMetadata.kt:596 落盘格式），这里模拟真产物侧车的形态。
     */
    private fun compileLocalSizeBase(sym: SymbolTable): Triple<Program, BootMetadata, PatchCompiler.BaseCounts> {
        val p = compileMini(
            listOf("mini.ERB" to baseSource), sym, allNames, allExprCalls, emptyMap(), baseHeads,
        )
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        val meta = BootMetadata(
            functions = p.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i,
                    key = f.name,
                    isMethod = f.name == "A_SUM",
                    argIntCount = argCounts[f.name] ?: 0,
                    localSize = if (f.name == "A_DEF") 12 else 0,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
            dimParseConfig = DimParseConfig(macros = sidecarMacros),
        )
        val counts = PatchCompiler.BaseCounts(
            instructionCount = p.instructionCount,
            functionCount = p.functions.size,
            statementCount = p.statementCount,
            stringCount = p.stringCount,
            longCount = p.constantCount,
            exprCount = p.exprCount,
        )
        return Triple(p, meta, counts)
    }

    @Test
    fun `localSize 透传——补丁函数的 LOCALSIZE 声明进 FuncMeta 读数与侧车值同源`() {
        val sym = patchSymbols()
        val (_, meta, counts) = compileLocalSizeBase(sym)
        val result = patchCompile(meta, counts, sym, source = localSizePatchSource, entry = "B_LOCAL")
        val byKey = result.funcMetas.associateBy { it.key }
        // 全量参照：全量编译链路对同一声明的 FuncMeta 写侧形态（CorpusCompile.kt:684 从
        // 符号层 LabelDef.localSize 取值；采集求值与补丁侧的 LineParser dimConsts 通道
        // 同为 D.8.1 求值器真源）——补丁续编产物的读数必须与侧车值逐字段相等。
        val fullRef = FuncMeta(
            index = counts.functionCount,
            key = "B_LOCAL",
            localSize = 8,
            localsSize = 3,
            defIndex = counts.functionCount,
            position = "mods/patch.ERB:1",
        )
        val bLocal = byKey.getValue("B_LOCAL")
        assertEquals(fullRef.localSize, bLocal.localSize, "#LOCALSIZE 8 → FuncMeta.localSize（= 侧车值）")
        assertEquals(fullRef.localsSize, bLocal.localsSize, "#LOCALSSIZE 3 → FuncMeta.localsSize（= 侧车值）")
        // 零缺省行为不回退：未声明函数的读数保持 0（运行期局部长度走注册表默认）。
        val bPlain = byKey.getValue("B_PLAIN")
        assertEquals(0, bPlain.localSize, "未声明 #LOCALSIZE 的函数读数保持 0")
        assertEquals(0, bPlain.localsSize, "未声明 #LOCALSSIZE 的函数读数保持 0")
        // base 侧带非零 localSize 的函数（A_DEF = 12）在补丁编译全程不被扰动。
        assertEquals(12, meta.functions.first { it.key == "A_DEF" }.localSize)
        // 透传的落盘面：补丁 funcMetas 并进侧车后经 function 段格式往返，读数保真
        // （BootMetadata.kt:596 写出、:1585-1610 读回的同字段）。
        val merged = BootMetadata(
            functions = meta.functions + result.funcMetas,
            methodTypes = meta.methodTypes,
            dimParseConfig = meta.dimParseConfig,
        )
        val readBack = BootMetadata.parse(merged.encodeText())
        val rtLocal = readBack.functions.first { it.key == "B_LOCAL" }
        assertEquals(8, rtLocal.localSize, "侧车 function 段往返后 localSize 保真")
        assertEquals(3, rtLocal.localsSize, "侧车 function 段往返后 localsSize 保真")
    }

    @Test
    fun `localSize 折常量吃侧车宏表——宏体形态与全量链路同源`() {
        // `#LOCALSIZE M_BASE`：LineParser 的 dimConsts（= sizeConsts(侧车宏表, 常量)）折出
        // 10。全量链路的符号层扫描（Symbols.kt:962-969 evalSharpInt）吃同一份 constValues
        // （D.8.1 求值器同源）——同一声明两侧同一读数；折不出 = 该行不生效保持 0
        // （spec: LogicalLineParser.cs:236-239）。
        val sym = patchSymbols()
        val (_, meta, counts) = compileLocalSizeBase(sym)
        val src = "@B_LSMAC\n#LOCALSIZE M_BASE\nX = 1\nRETURN 0\n"
        val result = patchCompile(meta, counts, sym, source = src, entry = "B_LSMAC")
        assertEquals(10, result.funcMetas.single().localSize, "#LOCALSIZE 宏体经 dimConsts 折出侧车宏值")
    }

    // ------------------------------------------------------------------
    // R7-D2 判据 ③：短路位（&& 右侧等）省略实参 + 空侧车的 I6-④ 等价判据
    // ------------------------------------------------------------------

    /** 短路口夹具的 base 源：带默认值形参的 `#FUNCTION` 方法（表达式位调用目标）。 */
    private val shortCircuitBaseSource = """
        @A_M(A, B = 7)
        #FUNCTION
        RETURNF A + B
    """.trimIndent()

    /**
     * 短路口夹具的 base 装配：A_M 是 base 方法（methodTypes = INT，arity 2）。
     * [paramDefaults] 传空表 = 空侧车（I6-④ 场景）；传 A_M 默认值 = 非空侧车（补位场景）。
     */
    private fun compileShortCircuitBase(
        sym: SymbolTable,
        paramDefaults: CallDefaults,
    ): Triple<Program, BootMetadata, PatchCompiler.BaseCounts> {
        val p = compileMini(
            listOf("mini.ERB" to shortCircuitBaseSource), sym,
            setOf("A_M", "B_SHORT"),
            ExprCallTable(intMethods = setOf("A_M")),
            paramDefaults,
            mapOf("A_M" to (true to false)),
        )
        val meta = BootMetadata(
            functions = listOf(
                FuncMeta(index = 0, key = "A_M", isMethod = true, argIntCount = 2, defIndex = 0, position = "mini.ERB:1"),
            ),
            methodTypes = mapOf("A_M" to BootMetadata.MethodType.INT),
            paramDefaults = paramDefaults,
        )
        val counts = PatchCompiler.BaseCounts(
            instructionCount = p.instructionCount,
            functionCount = p.functions.size,
            statementCount = p.statementCount,
            stringCount = p.stringCount,
            longCount = p.constantCount,
            exprCount = p.exprCount,
        )
        return Triple(p, meta, counts)
    }

    /** 递归收集表达式树里的 FuncCall（测试断言用；遍历序与 Expr 求值序无关）。 */
    private fun collectFuncCalls(e: Expr?, out: MutableList<FuncCall>) {
        when (e) {
            null -> Unit
            is FuncCall -> {
                out.add(e)
                for (a in e.args) collectFuncCalls(a, out)
            }
            is Binary -> {
                collectFuncCalls(e.left, out)
                collectFuncCalls(e.right, out)
            }
            is Ternary -> {
                collectFuncCalls(e.cond, out)
                collectFuncCalls(e.ifTrue, out)
                collectFuncCalls(e.ifFalse, out)
            }
            is Unary -> collectFuncCalls(e.operand, out)
            is UnaryAfter -> collectFuncCalls(e.operand, out)
            is VarRef -> for (i in e.indices) collectFuncCalls(i, out)
            else -> Unit
        }
    }

    /**
     * 收集补丁产物里保持节点的 FuncCall：赋值 RHS（SpSetArgument.term）与 IF 条件随
     * 语句实例进**语句池**，求值指令的树进**表达式池**——两个面都要扫（本夹具的
     * `X = 0 && A_M(1)` 落语句池面）。
     */
    private fun collectKeptCalls(
        p: Program,
        stmtFrom: Int,
        exprFrom: Int,
        out: MutableList<FuncCall>,
    ) {
        for (i in stmtFrom until p.statementCount) {
            when (val s = p.statement(i)) {
                is AssignStmt -> collectFuncCalls((s.args as? SpSetArgument)?.term, out)
                is IfStmt -> collectFuncCalls(s.condition, out)
                else -> Unit
            }
        }
        for (i in exprFrom until p.exprCount) collectFuncCalls(p.expr(i), out)
    }

    @Test
    fun `I6 四之短路口——空侧车下短路位省略实参编译期拒绝并点名`() {
        // 形态可达性证据：`X = 0 && A_M(1)`（A_M 是 base 方法 arity 2）在 && 右侧保持
        // FuncCall 节点（ErbProgramCompiler.lowerBinary 的短路保序口径），实参省略不产生
        // callArgs 记录 —— R7-D2 之前的 I6-④ 只扫 callRefs，这道闸对它不可见；运行期
        // 重入绑定按原版 Arguments[i] == null 跳过（spec: ProcessState.cs:437）＝静默错值。
        val sym = SymbolTable()
            .declareVariable("X", OperandType.INT, 1)
            .declareFunction("A_M", OperandType.INT)
        val (_, meta, counts) = compileShortCircuitBase(sym, paramDefaults = emptyMap())
        val src = "@B_SHORT\nX = 0 && A_M(1)\nRETURN 0\n"
        val ex = assertFailsWith<PatchCompiler.PatchCompileException>(
            "短路位省略实参 + 空侧车必须编译期拒绝（I6-④ 等价判据）",
        ) {
            patchCompile(meta, counts, sym, source = src, entry = "B_SHORT")
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("A_M"), "错误消息必须点名被调函数，实际: $msg")
        assertTrue(msg.contains("B_SHORT"), "错误消息必须点名调用方函数，实际: $msg")
        assertTrue(msg.contains("mods/patch.ERB:2"), "错误消息必须带文件:行，实际: $msg")
    }

    @Test
    fun `I6 四之短路口——非空侧车把默认值顶替进调用节点不拒绝`() {
        // 非空侧车（paramDefaults 有 A_M）同场景：padRuntimeCalls 把 Def[i] 顶替进
        // FuncCall.args（CMP-27），补位后的节点无省略形态 → 放行，与全量编译同行为。
        val sym = SymbolTable()
            .declareVariable("X", OperandType.INT, 1)
            .declareFunction("A_M", OperandType.INT)
        val sidecar = mapOf<String, List<Expr?>>("A_M" to listOf<Expr?>(null, IntLiteral(7)))
        val (_, meta, counts) = compileShortCircuitBase(sym, paramDefaults = sidecar)
        val src = "@B_SHORT\nX = 0 && A_M(1)\nRETURN 0\n"
        val result = patchCompile(meta, counts, sym, source = src, entry = "B_SHORT")
        // 补位落进保持节点：语句池新段的赋值 RHS（SpSetArgument.term）里 FuncCall(A_M)
        // 的实参补到 2 位（保持节点不产生 callArgs，只能从池里读节点验证）。
        val calls = ArrayList<FuncCall>()
        collectKeptCalls(result.program, counts.statementCount, counts.exprCount, calls)
        val aMCall = calls.lastOrNull { it.name.uppercase() == "A_M" }
        assertTrue(aMCall != null, "语句池的赋值 RHS 里必须有 A_M 调用节点")
        assertEquals(2, aMCall.args.size, "省略位被默认值补齐（padOmittedArgs 补到最后一个默认值位）")
        assertEquals(7L, (aMCall.args[1] as? IntLiteral)?.value, "第 2 位补默认值 7")
    }

    @Test
    fun `I6 四之短路口——空侧车下补全实参的短路位调用放行`() {
        // 空侧车但实参补满（size == arity 且无空参位）＝无省略形态 → 不属于 I6-④ 面，放行。
        val sym = SymbolTable()
            .declareVariable("X", OperandType.INT, 1)
            .declareFunction("A_M", OperandType.INT)
        val (_, meta, counts) = compileShortCircuitBase(sym, paramDefaults = emptyMap())
        val src = "@B_FULL\nX = 0 && A_M(1, 2)\nRETURN 0\n"
        val result = patchCompile(meta, counts, sym, source = src, entry = "B_FULL")
        assertEquals(1, result.program.functions.size, "放行后补丁段函数表 = 1（绝对下标由 functionBase 表达）")
    }

    // ------------------------------------------------------------------
    // 判据 3：sizeConsts 两端同解
    // ------------------------------------------------------------------

    @Test
    fun `sizeConsts 两端同解——同一份侧车输入与桌面求值器逐键相等`() {
        val macros = linkedMapOf(
            "M_BASE" to "10",
            "M_CHAIN" to "M_BASE + 5",          // 宏→宏链
            "M_HEX" to "0x10 * 2",              // 0x 十六进制（spec: LexicalAnalyzer.cs:36-45）
            "M_DEAD" to "VARSIZE(X)",            // 不可求值 → 两端都不纳入
            "M_BLANK" to "",                     // 空白体 → 两端都不进表
        )
        val presets = linkedMapOf("C_GREEN" to 0x00FF00L, "C_NEG" to -3L)
        // 桌面侧：已求值常量以 `#DIM CONST 名 = 十进制字面量` 的原文形态进同一求值口径。
        val desktop = desktopSizeConsts(
            macros,
            presets.map { (k, v) ->
                DimSpec(k, false, emptyList(), setOf(DimKeyword.CONST), initializer = v.toString())
            },
        )
        val device = deviceSizeConsts(macros, presets)
        assertEquals(desktop.keys, device.keys, "两端键集逐键相等（不可求值项两端同弃）")
        for (k in desktop.keys) {
            assertEquals(desktop[k], device[k], "常量 $k 的值两端同解")
        }
        assertTrue("M_DEAD" !in device && "M_BLANK" !in device, "不可求值与空白体不进表（桌面同向）")
        assertEquals(15L, device["M_CHAIN"], "宏→宏链定点折出")
        assertEquals(32L, device["M_HEX"])
        assertEquals(0x00FF00L, device["C_GREEN"])
    }

    @Test
    fun `sizeConsts 宏赢语义两端一致——重名时宏先占`() {
        val macros = linkedMapOf("WINS" to "1")
        val presets = linkedMapOf("WINS" to 2L)
        val desktop = desktopSizeConsts(
            macros,
            listOf(DimSpec("WINS", false, emptyList(), setOf(DimKeyword.CONST), initializer = "2")),
        )
        val device = deviceSizeConsts(macros, presets)
        assertEquals(desktop, device, "重名时宏先占（spec: IdentifierDictionary.cs:283-285 次序）")
        assertEquals(1L, device["WINS"])
    }

    @Test
    fun `labelTable build 注入同解——设备求值器替换桌面 sizeConsts 的注入位`() {
        val dir = Files.createTempDirectory("patch-size-consts")
        dir.resolve("M.ERH").toFile().writeText(
            "#DEFINE M_BASE 10\n#DIM CONST C_GREEN = 0x00FF00\n#DIMS CONST C_NAME = \"x\"\n",
            Charsets.UTF_8,
        )
        // 设备求值器注入 LabelTable.build 的 sizeConsts 参数（Symbols.kt:716 的注入点形态）。
        val table = LabelTable.build(dir, sizeConsts = ::labelTableSizeConsts)
        // 桌面求值器对**同一份收集物**（宏表 + ERH #DIM CONST 原文）求值 → 逐键相等。
        val desktop = desktopSizeConsts(table.config.macros, table.erhDimConsts)
        assertEquals(desktop.keys, table.config.constValues.keys, "注入产出的键集与桌面逐键相等")
        for (k in desktop.keys) {
            assertEquals(desktop[k], table.config.constValues[k], "注入产出的 $k 两端同解")
        }
        assertEquals(10L, table.config.constValues["M_BASE"])
        assertEquals(0x00FF00L, table.config.constValues["C_GREEN"])
        assertTrue("C_NAME" !in table.config.constValues, "字符串常量不进整型尺寸表（桌面同向）")
    }

    // ------------------------------------------------------------------
    // 判据 4：I6 四条负向（全部编译期拒绝 + 点名）
    // ------------------------------------------------------------------

    @Test
    fun `事件成员放行——补丁定义 EVENTTRAIN 编出成员与合成入口`() {
        // wave/mod-import-event-members：原 I6-①（保留事件函数一票否决）撤销。同名事件
        // 函数是叠加语义（全部执行，spec: CalledFunction.cs:228-243），补丁成员编成
        // `<事件名>#<绝对下标>`、另发一个裸事件名合成入口接管按名派发位。
        val (_, meta, counts) = compileBase()
        val src = "@EVENTTRAIN\nPRINTL T\nRETURN 0\n"
        val r = patchCompile(meta, counts, patchSymbols(), source = src, entry = "EVENTTRAIN")
        val memberIndex = counts.functionCount
        // 成员：Program 名带 #绝对下标；meta 键 = 裸事件名、isEvent = true。
        assertTrue(
            r.program.functions.any { it.name == "EVENTTRAIN#$memberIndex" },
            "成员应以 EVENTTRAIN#$memberIndex 进 Program，实际: ${r.program.functions.map { it.name }}",
        )
        val memberMeta = r.funcMetas.singleOrNull { it.isEvent }
        assertNotNull(memberMeta, "成员 meta 必须带 isEvent=true，实际: ${r.funcMetas.map { it.key to it.isEvent }}")
        assertEquals("EVENTTRAIN", memberMeta.key, "成员 meta 键必须是裸事件名（eventMembersByKey 按键聚合）")
        // 合成入口：Program 名 = 裸事件名，meta isEvent=false、position 带合成标记。
        assertTrue(
            r.program.functions.any { it.name == "EVENTTRAIN" },
            "合成入口应以裸事件名进 Program，实际: ${r.program.functions.map { it.name }}",
        )
        val entryMeta = r.funcMetas.singleOrNull { it.position == "·event-entry·" }
        assertNotNull(entryMeta, "入口 meta 必须带合成 position（与全量路 CorpusCompile 同款标记）")
        assertEquals(false, entryMeta.isEvent, "入口走 nonEventByKey 派发位，不是事件成员")
        assertTrue(entryMeta.index > memberMeta.index, "入口 defIndex/index 必须续接在成员之后")
    }

    @Test
    fun `包内同名覆盖——后定义者生效且出告警，非胜者定义整段废弃`() {
        // 原版对同名非事件函数是「后装载者留下」（spec: LabelDictionary.cs:107-113 直接赋值，
        // ErbLoader.cs:466 只告警不拒绝，WarnNormalFunctionOverloading 键缺省 false
        // ConfigData.cs:99）——社区口上包重度依赖该语义（蕾米莉亚色情特化 v1.3 包内 77 组）。
        // 告警门显式传 true（本仓出厂 emuera.config 第 48 行 = YES）；false 分支见下一例。
        val (_, meta, counts) = compileBase()
        val src = "@B_ONE\n#DIM LOCAL_P, 2\nPRINTL FIRST\nRETURN 0\n\n" +
            "@B_ONE\nPRINTL SECOND\nRETURN 0\n"
        val r = patchCompileGated(meta, counts, src, "B_ONE", gated = true)
        assertEquals(
            1, r.program.functions.count { it.upperKey == "B_ONE" },
            "同键只留胜者（后定义者）一个函数",
        )
        assertTrue(
            r.parseWarnings.any { it.contains("B_ONE") && it.contains("后定义者生效") },
            "覆盖必须出 level-1 告警并点名函数，实际: ${r.parseWarnings}",
        )
        // 被覆盖定义的私有 #DIM 随其 FuncHead 一并废弃（步骤 3.5 只解析胜者定义）。
        assertTrue(
            r.privateDims.none { it.functionKey == "B_ONE" && it.spec.name == "LOCAL_P" },
            "非胜者定义的私有 #DIM 不得进 privateDims，实际: ${r.privateDims.map { it.functionKey to it.spec.name }}",
        )
    }

    @Test
    fun `门控关闭——同名非事件覆盖零告警且覆盖结果逐位不变`() {
        // 原版门 `!label.IsEvent && (Config.WarnNormalFunctionOverloading || Program.AnalysisMode)`
        // （spec: ErbLoader.cs:466）：设备侧补丁预扫不是 AnalysisMode，配置关时该告警必须静默，
        // 但覆盖语义（非事件后定义者胜出，LabelDictionary.cs:107-113）与装载结果逐位不变。
        val (_, meta, counts) = compileBase()
        val src = "@B_ONE\n#DIM LOCAL_P, 2\nPRINTL FIRST\nRETURN 0\n\n" +
            "@B_ONE\nPRINTL SECOND\nRETURN 0\n"
        val on = patchCompileGated(meta, counts, src, "B_ONE", gated = true)
        val off = patchCompileGated(meta, counts, src, "B_ONE", gated = false)
        assertEquals(
            1, on.parseWarnings.count { it.contains("B_ONE") && it.contains("后定义者生效") },
            "门开时必须出且只出一条同名覆盖告警，实际: ${on.parseWarnings}",
        )
        assertTrue(
            off.parseWarnings.none { it.contains("B_ONE") && it.contains("后定义者生效") },
            "门关时同一批源必须零告警（不许降级成别的话术），实际: ${off.parseWarnings}",
        )
        assertEquals(
            1, off.program.functions.count { it.upperKey == "B_ONE" },
            "门关不改变覆盖结果：同键仍只留胜者一个函数",
        )
        assertContentEquals(
            on.program.code, off.program.code,
            "告警门只改「发不发」，补丁指令流必须逐字相同",
        )
        assertEquals(
            on.functions.map { it.sig.name }, off.functions.map { it.sig.name },
            "告警门不改变补丁函数表序与命名",
        )
        assertTrue(
            off.privateDims.none { it.functionKey == "B_ONE" && it.spec.name == "LOCAL_P" },
            "门关时非胜者定义的私有 #DIM 同样不得进 privateDims",
        )
    }

    @Test
    fun `同名事件重复定义恒不告警——原版 !IsEvent 门`() {
        // 原版 `!label.IsEvent && …`（spec: ErbLoader.cs:466）：事件名不是覆盖关系（多成员
        // 并存），不受 `WarnNormalFunctionOverloading` 约束 —— 配置开也不许出「重复定义」。
        // 设备侧同名事件仍是「最后成员胜出」（类尾 TODO(verify) 4a），本波只关告警，不改覆盖。
        val (_, meta, counts) = compileBase()
        val src = "@EVENTTRAIN\nPRINTL A\nRETURN 0\n" +
            "@EVENTTRAIN\nPRINTL B\nRETURN 0\n" +
            "@B_ANY\nRETURN 0\n"
        val r = patchCompileGated(meta, counts, src, "B_ANY", gated = true)
        assertTrue(
            r.parseWarnings.none { it.contains("重复定义") },
            "事件名重复不得产生「同名非事件函数重复定义」告警，实际: ${r.parseWarnings}",
        )
        assertEquals(
            1, r.program.functions.count { it.upperKey.startsWith("EVENTTRAIN#") },
            "同名事件成员的既有覆盖形态不变（最后成员胜出），实际: ${r.program.functions.map { it.name }}",
        )
        assertTrue(
            r.program.functions.any { it.name == "EVENTTRAIN" },
            "事件名本身仍留给合成入口，实际: ${r.program.functions.map { it.name }}",
        )
    }

    @Test
    fun `函数级容错——坏行只带走所在函数，同文件其余函数照常装载`() {
        // 蕾米莉亚包实测：原版废弃的粒度是**函数**（逐 label try/catch 标 ErrMes，其余照常
        // 装载，spec: ErbLoader.cs:995-1013 ParseFunctionWithCatch）。此前按整文件作废，
        // 依頼.ERB 26099 行只因 3 行引用已废弃的 IRAI_STR 被整包丢掉 = 兼容性黑洞。
        val (_, meta, counts) = compileBase()
        val src = "@B_GOOD_A\nCALL A_ONE(3)\nRETURN 0\n" +
            "@B_TEMPLATE\nCULPRIT = Add_CULPRIT\nRETURN 0\n" +
            "@B_GOOD_B\nPRINTL OK\nRETURN 0\n"
        val result = PatchCompiler(meta, counts, patchSymbols()).compile(
            listOf(PatchCompiler.SourceFile("mods/mixed.ERB", src)),
            "B_GOOD_A",
        )
        val names = result.program.functions.map { it.upperKey }
        assertTrue("B_GOOD_A" in names && "B_GOOD_B" in names, "同文件其余函数必须留下，实际: $names")
        assertTrue("B_TEMPLATE" !in names, "坏函数不留半截，实际: $names")
        assertTrue(
            result.parseWarnings.any {
                it.contains("已跳过解析失败的函数 @B_TEMPLATE") &&
                    it.contains("mods/mixed.ERB:5") && it.contains("Add_CULPRIT")
            },
            "告警必须点名函数、文件:行与首错（I7 可定位），实际: ${result.parseWarnings}",
        )
        assertTrue(
            result.parseWarnings.none { it.contains("已跳过解析失败的源") },
            "函数级能吃下的坏行不许退化成整文件作废，实际: ${result.parseWarnings}",
        )
    }

    @Test
    fun `函数级容错的级联——调用被作废函数的函数连带作废`() {
        // 作废函数的名字从解析面摘除 ⇒ 引用它的调用点解析失败，与原版「未定义标识符」同形。
        // 反向引用（调用者定义在被调者之前）靠重切轮次推进，不靠运气。
        val (_, meta, counts) = compileBase()
        val caller = "@B_CALLER\nIF B_GONE()\nPRINTL X\nENDIF\nRETURN 0\n"
        val gone = "@B_GONE\nCULPRIT = Add_CULPRIT\nRETURN 0\n"
        val result = PatchCompiler(meta, counts, patchSymbols()).compile(
            listOf(
                PatchCompiler.SourceFile("mods/a_caller.ERB", caller),
                PatchCompiler.SourceFile("mods/b_gone.ERB", gone),
                // 留一个好函数：两个坏函数把面清空时抛的是「空面」硬失败，测不到级联归因。
                PatchCompiler.SourceFile("mods/c_good.ERB", "@B_SURVIVOR\nPRINTL OK\nRETURN 0\n"),
            ),
            "B_CALLER",
        )
        val names = result.program.functions.map { it.upperKey }
        assertTrue("B_SURVIVOR" in names, "无关好函数不受级联影响，实际: $names")
        assertTrue("B_GONE" !in names, "被调函数本身作废，实际: $names")
        assertTrue("B_CALLER" !in names, "引用作废函数的调用方连带作废（不留半截调用），实际: $names")
        assertTrue(
            result.parseWarnings.any {
                it.contains("已跳过解析失败的函数 @B_CALLER") && it.contains("B_GONE")
            },
            "级联作废也要点名（不许静默少一块），实际: ${result.parseWarnings}",
        )
    }

    @Test
    fun `CALL 语句命中被作废函数——行级错误，调用方与整包照常装载`() {
        // KI-POISON-CASCADE（2026-11-27，因幡帝海外版 V8 真包撞出）：@M_KOJO_EGG_MESSAGE_
        // CUSTOM_SCOM_K53_12 的 `CALL K53_DOUBLE_HANDJOB` 目标在同文件 8 行后被步骤 8 的
        // poison 环摘除，但编译面仍拿**预扫全集**当 knownFunctions ⇒ 发出指向不存在函数的
        // _CALL ⇒ `build()` 抛「未定义的函数」⇒ 整包 UNEXPECTED。
        // 原版对「label 在、装载失败」的粒度是**行**：SetJumpTo 把该 CALL 行本身标 IsError +
        // ErrMes（spec: FunctionIdentifier.cs:3545-3551），调用方照常装载，只有执行到那一行
        // 才抛 CodeEE（spec: Process.cs:1017-1019）；文案 = CalledFailedFunc
        //（lang.emuera-zhs.xml:567）。这里同粒度：CALL 行编 THROW 哨兵，函数与整包都留下。
        val (_, meta, counts) = compileBase()
        // 调用者在前、被调者在后 —— 真包形态（同文件前向引用，一轮切片内先解析后中毒）。
        val src = "@B_CALLER\nCALL B_GONE\nPRINTL AFTER\nRETURN 0\n" +
            "@B_GONE\nCULPRIT = Add_CULPRIT\nRETURN 0\n" +
            "@B_SURVIVOR\nPRINTL OK\nRETURN 0\n"
        val result = PatchCompiler(meta, counts, patchSymbols()).compile(
            listOf(PatchCompiler.SourceFile("mods/same_file.ERB", src)),
            "B_SURVIVOR",
        )
        val names = result.program.functions.map { it.upperKey }
        assertTrue("B_SURVIVOR" in names, "无关函数不受影响，实际: $names")
        assertTrue("B_CALLER" in names, "CALL 行是行级错误，调用方必须留下，实际: $names")
        assertTrue("B_GONE" !in names, "被毒函数本身不装载，实际: $names")
        assertTrue(
            result.parseWarnings.any { it.contains("已跳过解析失败的函数 @B_GONE") },
            "作废必须逐条告警（I7），实际: ${result.parseWarnings}",
        )
        assertEquals(
            listOf("调用了一个加载时解析失败的函数\"@B_GONE\""),
            throwMarkers(result.program),
            "CALL 行应降级成行级 THROW 哨兵（原版 IsError 行），而不是指向不存在函数的 _CALL",
        )
    }

    @Test
    fun `函数内 #DIM 声明的形参——注释紧贴标识符也必须可见`() {
        // 因幡帝海外版 V8 真包（2026-11-27）：`@M_KOJO_EGG_IRAI_1_K53(ROLE, SCENE, CLIENT_ID)`
        // 的三个形参由紧随其后的 `#DIMS ROLE` / `#DIMS SCENE` / `#DIM  CLIENT_ID;伝聞者ID…`
        // 在函数体内声明，第三个的注释紧贴标识符（无空格）⇒ 若注释未被剥净，
        // 声明名会带尾巴、形参判定「未定义的标识符」⇒ 整文件作废（该文件 12 个函数一起没了）。
        val (_, meta, counts) = compileBase()
        val src = "@B_P(ROLE, SCENE, CLIENT_ID)\n" +
            "#DIMS ROLE\n" +
            "#DIMS SCENE\n" +
            "#DIM  CLIENT_ID;伝聞者ID（コメント）\n" +
            "RETURN 0\n"
        val result = PatchCompiler(meta, counts, patchSymbols()).compile(
            listOf(PatchCompiler.SourceFile("mods/req.ERB", src)),
            "B_P",
        )
        assertTrue(
            "B_P" in result.program.functions.map { it.upperKey },
            "B_P 必须装载（形参由函数内 #DIM 声明），实际: ${result.program.functions.map { it.upperKey }}",
        )
        assertTrue(result.parseWarnings.isEmpty(), "不应有作废告警，实际: ${result.parseWarnings}")
    }

    /** 产物里全部 THROW marker 的文案（顺序 = 指令序；只扫本段窗口，base 区不可物化）。 */
    private fun throwMarkers(p: Program): List<String> =
        (p.instructionBase until p.instructionCount)
            .map { p.instructionAt(it) }
            .filter { it.op == Opcode.THROW }
            .mapNotNull { ins ->
                ((p.statement(ins.a).args as? ExpressionArgument)?.term as? StrLiteral)?.value
            }

    @Test
    fun `文件级容错——函数体外的坏行按整文件作废`() {
        // 归因不到函数（第一个 @ 行之前的正文）时退到文件粒度：整文件不进函数表。
        val (_, meta, counts) = compileBase()
        val good = "@B_GOOD\nCALL A_ONE(3)\nRETURN 0\n"
        val template = "CULPRIT = Add_CULPRIT\n@B_TEMPLATE\nRETURN 0\n"
        val result = PatchCompiler(meta, counts, patchSymbols()).compile(
            listOf(
                PatchCompiler.SourceFile("mods/a_good.ERB", good),
                PatchCompiler.SourceFile("mods/z_template.ERB", template),
            ),
            "B_GOOD",
        )
        assertTrue(
            result.program.functions.any { it.upperKey == "B_GOOD" },
            "好文件的函数必须编出来，实际: ${result.program.functions.map { it.name }}",
        )
        assertTrue(
            result.program.functions.none { it.upperKey == "B_TEMPLATE" },
            "坏文件整体不进函数表（整文件粒度，不留半截）",
        )
        assertTrue(
            result.parseWarnings.any {
                it.contains("已跳过解析失败的源") && it.contains("mods/z_template.ERB") && it.contains("Add_CULPRIT")
            },
            "告警必须点名文件与首错（I7 可定位），实际: ${result.parseWarnings}",
        )
    }

    @Test
    fun `KI-2 撞名不再属于容错边界外——同批好坏文件都装载并逐条告警`() {
        // 2026-11-23 KI 改判：撞 base 同名不再是错误（覆盖装载 + 告警），原「容错不吞
        // I6-②」的钉桩随语义反转——两个文件都编译成功，撞名告警逐条出。
        val (_, meta, counts) = compileBase()
        PatchCompiler(meta, counts, patchSymbols()).compile(
            listOf(
                PatchCompiler.SourceFile("mods/a_good.ERB", "@B_GOOD\nRETURN 0\n"),
                PatchCompiler.SourceFile("mods/z_dup.ERB", "@A_ONE\nPRINTL DUP\nRETURN 0\n"),
            ),
            "B_GOOD",
        ).let { r ->
            assertEquals(2, r.program.functions.size, "好文件与撞名文件都进函数表")
            assertTrue(
                r.parseWarnings.any { it.contains("A_ONE") && it.contains("覆盖 base 同名函数") },
                "撞名告警照出: ${r.parseWarnings}",
            )
        }
    }

    /** R7-①（2026-11-23 KI 改判后）：撞名一律覆盖装载，判据参数已撤销。 */
    private fun patchCompileOverride(
        meta: BootMetadata,
        counts: PatchCompiler.BaseCounts,
        source: String,
        entry: String,
    ): PatchCompiler.PatchResult = PatchCompiler(
        meta,
        counts,
        patchSymbols(),
    ).compile(listOf(PatchCompiler.SourceFile("mods/kojo.ERB", source)), entry)

    @Test
    fun `R7-① 判据为真——撞名函数编成声明式覆盖并逐条告警`() {
        val (_, meta, counts) = compileBase()
        val src = "@A_ONE\nPRINTL OVR\nRETURN 0\n\n@B_NEW\nRETURN 0\n"
        val r = patchCompileOverride(meta, counts, src, "A_ONE")
        assertEquals(
            1,
            r.parseWarnings.count { it.contains("A_ONE") && it.contains("覆盖 base 同名函数") },
            "覆盖告警必须逐条点名，实得: ${r.parseWarnings}",
        )
        assertEquals(
            1, r.parseWarnings.count { it.contains("A_ONE") && it.contains("覆盖 base 同名函数") },
            "门开时逐条出覆盖告警（原版 level 1 告警的对应物），实际: ${r.parseWarnings}",
        )
        // 覆盖函数与加性函数同批装载：整包不再因一个撞名就死。
        assertEquals(2, r.program.functions.size, "覆盖函数照常进补丁段")
    }

    @Test
    fun `R7-① 告警门关掉——覆盖装载照旧但零告警`() {
        val (_, meta, counts) = compileBase()
        val src = "@A_ONE\nPRINTL OVR\nRETURN 0\n"
        val on = patchCompileOverride(meta, counts, src, "A_ONE")
        val off = PatchCompiler(
            meta,
            counts,
            patchSymbols(),
            warnNormalFunctionOverloading = false,
        ).compile(listOf(PatchCompiler.SourceFile("mods/kojo.ERB", src)), "A_ONE")
        assertEquals(on.program.functions.size, off.program.functions.size, "告警门不改装载面")
        assertContentEquals(on.program.code, off.program.code, "告警门只改「发不发」，指令流逐字相同")
        assertTrue(off.parseWarnings.none { it.contains("覆盖 base 同名函数") }, "门关零告警：${off.parseWarnings}")
    }

    /**
     * 2026-11-23 KI 改判（BOARD KI-2）：原「判 false 的键维持硬拒」用例反转——撞名一律
     * 覆盖装载，不再有判据分档。两个撞名键都放行 + 逐条告警（原版 ErbLoader.cs:466-475）。
     */
    @Test
    fun `KI-2 撞名一律覆盖装载——不再有判据分档硬拒`() {
        val (_, meta, counts) = compileBase()
        val src = "@A_ONE\nPRINTL OVR1\nRETURN 0\n\n@A_TWO(N)\nRETURN 0\n"
        val r = patchCompileOverride(meta, counts, src, "A_ONE")
        assertEquals(
            2,
            r.parseWarnings.count { it.contains("覆盖 base 同名函数") },
            "两个撞名键都放行并逐条告警，实得: ${r.parseWarnings}",
        )
        assertEquals(2, r.program.functions.size, "两个撞名函数都照常进补丁段")
    }

    /**
     * 大小写折叠虫（2026-11-17 真包整包首撞，风神少女 `@K29_FORCESubscribe`）：引擎按
     * `Config.StrComper` = IGNORE CASE 比较函数名（spec: IdentifierDictionary.cs:48），而旧
     * 步骤 5 拿 `FuncHead.name` 的**源码原样大小写**去比侧车大写键 ⇒ 混合大小写的撞名既逃过
     * I6-②、又不会被声明成覆盖，产物落盘后在链接期抛 SymbolConflictException——
     * 「导入成功但带这个包开不了局」。判据必须走归一键。
     */
    @Test
    fun `R7-① 大小写混写也要认出撞名——按 IGNORE CASE 归一比较`() {
        val (_, meta, counts) = compileBase()
        val r = patchCompileOverride(meta, counts, "@a_one\nPRINTL OVR\nRETURN 0\n", "A_ONE")
        assertEquals(
            1, r.parseWarnings.count { it.contains("覆盖 base 同名函数") },
            "小写书写的同名函数也要认出撞名并出覆盖告警，实得: " + r.parseWarnings,
        )
    }

    // ------------------------------------------------------------------
    // R7-① 余项（RECON-R7-NOFINGERPRINT）：无指纹 base 标签的收录判据
    // ------------------------------------------------------------------

    // 生产判据形状的纯 JVM 替身（原 `declarableFromSignatureTable`）已随 2026-11-23 KI 改判
    // 删除：签名指纹可得性不再决定放行/拒载（撞名一律覆盖装载，BOARD KI-2），判据替身无消费面。

    /**
     * 261111 侧车实测形状：base labelset 有名（118,195 条）而签名段无键（118,159 条），
     * 差集 36 个（RECON-R7-NOFINGERPRINT.md §2 附表）。撞上这批键的口上包（如覆盖禁载
     * 目录 DLC/AI对话补丁 空体标签的包）：**2026-11-23 KI 改判（BOARD KI-2）后不再整包
     * 硬拒**——按覆盖装载放行 + 覆盖告警（链接期三态拒载同步改判为 diagnostics 告警
     * 放行，spec: SymbolCatalog.kt build），导入不再被这批键挡住。
     */
    @Test
    fun `KI-2 无指纹键撞名——按覆盖装载放行不再整包硬拒`() {
        val (_, meta, counts) = compileBase()
        // A_ONE 不在任何签名表里 = base 侧车给不出该键的非空签名指纹（36 个无指纹标签形状），
        // 改判后照样放行（原判 assertFailsWith<PatchCompileException> 反转为编译成功断言）。
        val r = patchCompileOverride(meta, counts, "@A_ONE\nPRINTL OVR\nRETURN 0\n", "A_ONE")
        assertEquals(
            1,
            r.parseWarnings.count { it.contains("A_ONE") && it.contains("覆盖 base 同名函数") },
            "无指纹撞名键照样出覆盖告警，实得: ${r.parseWarnings}",
        )
        assertEquals(1, r.program.functions.size, "无指纹撞名函数照常进补丁段")
    }

    /**
     * 与上一例同夹具同补丁。反向证明面（2026-11-23 KI 改判后语义更新）：签名段有无指纹
 * 不再决定「放行/拒载」（撞名一律放行），本例改钉**融合方案的数据面**：覆盖键的补丁真实
     * 签名随 [PatchCompiler.PatchResult.signatures] 导出（步骤 9.5），供 Transcoder 写容器
     * 签名段（链接期 diagnostics 告警面用）。
     */
    @Test
    fun `KI-2 覆盖键补丁真实签名照常导出——容器签名段数据面不回退`() {
        val (_, meta, counts) = compileBase()
        val r = patchCompileOverride(
            meta, counts, "@A_ONE\nPRINTL OVR\nRETURN 0\n", "A_ONE",
        )
        assertEquals(
            1, r.parseWarnings.count { it.contains("A_ONE") && it.contains("覆盖 base 同名函数") },
            "覆盖告警逐条点名: ${r.parseWarnings}",
        )
        assertEquals(1, r.program.functions.size, "覆盖函数照常进补丁段（本源只定义 A_ONE 一个函数）")
        val fp = r.signatures["A_ONE"]
        assertTrue(
            !fp.isNullOrEmpty() && fp.length == 64,
            "覆盖键的补丁真实指纹必须非空（SHA-256 十六进制），实得: ${r.signatures}",
        )
    }

    /**
     * 36 个无指纹标签里 8 个是事件族（7 个合成事件入口 + 1 个禁载文件里的成员
     * `EVENTCOMEND#1096`，RECON-R7-NOFINGERPRINT.md §2）：事件名在步骤 5 恒放行
     * （同名事件 = 叠加语义，spec: CalledFunction.cs:228-243），签名指纹判据对事件键
     * 不生效——口上给事件名加成员不会因 base 侧查不到指纹被拒（261111 的事件键全部
     * 无指纹，本例钉住这批键不构成导入障碍）。
     */
    @Test
    fun `R7-① 事件族不受无指纹闸影响——base 事件键无指纹仍放行叠加`() {
        val (_, meta, counts) = compileBase()
        val withEvent = BootMetadata(
            functions = meta.functions + FuncMeta(
                index = counts.functionCount,
                key = "EVENTTRAIN",
                isEvent = true,
                defIndex = counts.functionCount,
                position = "mini.ERB:99",
            ),
            methodTypes = meta.methodTypes,
            paramDefaults = meta.paramDefaults,
            dimParseConfig = meta.dimParseConfig,
        )
        val r = patchCompileOverride(
            withEvent, counts, "@EVENTTRAIN\nPRINTL T\nRETURN 0\n", "EVENTTRAIN",
        )
        assertTrue(
            r.program.functions.any { it.name.startsWith("EVENTTRAIN#") },
            "事件成员照常编出: ${r.program.functions.map { it.name }}",
        )
        assertTrue(
            r.program.functions.any { it.name == "EVENTTRAIN" },
            "合成入口照常编出: ${r.program.functions.map { it.name }}",
        )
    }

    @Test
    fun `入口名回退——entry 落在被跳过区间时改用首个编译出的函数`() {
        // entry-function 只是补丁头的定位信息（入口恒为 base 的，铁律），不该毒化整包。
        val (_, meta, counts) = compileBase()
        val result = patchCompile(meta, counts, patchSymbols(), entry = "B_NOT_THERE")
        assertEquals("B_HELLO", result.entryFunction, "应回退到装载序首个编译出的函数")
        assertTrue(
            result.parseWarnings.any { it.contains("入口函数 B_NOT_THERE") },
            "回退必须出告警，实际: ${result.parseWarnings}",
        )
    }

    @Test
    fun `KI-GLOBAL-DIM——补丁函数外全局 DIM 收集进 globalDims 且编译成功（旧 I6 二反转）`() {
        val (_, meta, counts) = compileBase()
        // 函数外 `#DIM` = 任何 `@` 行之前的声明（`@` 行之后的 `#` 行原版归属该函数私有面）。
        // 2026-11-26 改判：这是 PC 原版 ERH 头文件的合法形态（因幡帝包），不再整包拒。
        val src = "#DIM G_BIG, 10\n\n@B_TOP\nPRINTL T\nRETURN 0\n"
        val result = patchCompile(meta, counts, patchSymbols(), source = src, entry = "B_TOP")
        assertTrue(result.funcMetas.any { it.key == "B_TOP" }, "补丁仍须编出函数")
        assertTrue(
            result.globalDims.any {
                it.name.equals("G_BIG", ignoreCase = true) && it.dimension == 1 && it.dims[0] == 10
            },
            "函数外全局 #DIM 必须进 PatchResult.globalDims 供下游 declare，实际: ${result.globalDims}",
        )
    }

    @Test
    fun `KI-GLOBAL-DIM——补丁函数外 CONST 折叠进 sizeConsts 并进 globalDims`() {
        val (_, meta, counts) = compileBase()
        // 因幡帝包 M_KOJO_K53_DIM.ERH 的形态：#DIM CONST 后接尺寸引用。
        val src = "#DIM CONST K53C_TEST = 42\n#DIM G_SIZED, K53C_TEST\n\n@B_TOP\nPRINTL T\nRETURN 0\n"
        val result = patchCompile(meta, counts, patchSymbols(), source = src, entry = "B_TOP")
        assertEquals(42L, result.sizeConsts["K53C_TEST"], "补丁 CONST 必须折叠进常量表")
        assertTrue(
            result.globalDims.any { it.name.equals("K53C_TEST", ignoreCase = true) && it.isConst },
            "CONST 声明也应带进 globalDims 供对账",
        )
        val sized = result.globalDims.firstOrNull { it.name.equals("G_SIZED", ignoreCase = true) }
        assertNotNull(sized, "尺寸引用补丁 CONST 的全局 #DIM 必须解析成功")
        assertEquals(42, sized!!.dims[0])
    }

    @Test
    fun `KI-GLOBAL-DIM——函数体内全局侧 DIM 仍拒绝（原版装载错误，不随本波放宽）`() {
        val (_, meta, counts) = compileBase()
        val src = "@B_TOP\n#DIM GLOBAL G_SIDE, 1\nPRINTL T\nRETURN 0\n"
        val ex = assertFailsWith<PatchCompiler.PatchCompileException>("函数体内 GLOBAL 侧声明必须拒绝（原版装载错误）") {
            patchCompile(meta, counts, patchSymbols(), source = src, entry = "B_TOP")
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("GLOBAL"), "错误消息必须点名全局侧关键字，实际: $msg")
        assertTrue(msg.contains("mods/patch.ERB:2"), "错误消息必须带文件:行，实际: $msg")
        assertTrue(
            msg.contains("函数内") || msg.contains("ERH"),
            "错误消息须点名函数内非法（DimParser isPrivate 闸或 :512），实际: $msg",
        )
    }

    // 「I6 三——补丁定义 base 同名函数编译期拒绝并点名」已随 2026-11-23 KI 改判（BOARD KI-2）
    // 删除：撞名不再编译期拒绝，其行为由「R7-① 判据为真——撞名函数编成声明式覆盖并逐条告警」
    // 与「KI-2 撞名一律覆盖装载——不再有判据分档硬拒」「KI-2 无指纹键撞名——按覆盖装载放行
    // 不再整包硬拒」三例共同钉住。

    @Test
    fun `I6 四——空侧车下补丁省略实参编译期拒绝并点名`() {
        // 空侧车（param-defaults 段缺失）+ CALL A_DEF(9) 省略后两位 ⇒ 编译期拒绝并点名。
        val (_, meta, counts) = compileBase(paramDefaults = emptyMap())
        val ex = assertFailsWith<PatchCompiler.PatchCompileException>("空侧车省略实参必须拒绝（不变量 I6）") {
            patchCompile(meta, counts, patchSymbols(), source = patchCallDefSource, entry = "B_CALLDEF")
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("A_DEF"), "错误消息必须点名被调函数，实际: $msg")
        assertTrue(msg.contains("B_CALLDEF"), "错误消息必须点名调用方函数，实际: $msg")
        assertTrue(msg.contains("mods/patch.ERB"), "错误消息必须带补丁文件，实际: $msg")
        assertTrue(msg.contains(":2"), "错误消息必须带行号，实际: $msg")
    }

    @Test
    fun `I6 四之补——非空侧车但该函数无默认值数据的省略与全量行为同源不拒绝`() {
        // 侧车非空（别的函数有默认值），但 A_TWO（无默认值函数）被省略调用：
        // 全量编译对它的省略同样「留 null 运行期跳过」（CallDefaults 只登记有默认值的函数），
        // 补丁与全量行为一致 → 不属于 I6 的静默错值面，放行。
        val sidecar = mapOf<String, List<Expr?>>("A_DEF" to listOf<Expr?>(null, IntLiteral(7), StrLiteral("d")))
        val (_, meta, counts) = compileBase(paramDefaults = sidecar)
        val src = "@B_OMIT\nCALL A_TWO()\nRETURN 0\n"
        val result = patchCompile(meta, counts, patchSymbols(), source = src, entry = "B_OMIT")
        // Program.functions 只含本段新增条目（L1 段内窗口语义）：放行后 = 1 条补丁函数。
        assertEquals(1, result.program.functions.size, "放行后补丁段函数表 = 1（绝对下标由 functionBase 表达）")
    }

    // ------------------------------------------------------------------
    // 端到端产物链（I8 演示）
    // ------------------------------------------------------------------

    @Test
    fun `补丁 Program 经 ErbcWriter 与 PatchArtifacts 落盘读回逐字节往返`() {
        val (_, meta, counts) = compileBase()
        val result = patchCompile(meta, counts, patchSymbols())
        val p = result.program
        // L1 的窗口编码：.erbc 只装本段字节（ErbcWriter KDoc 的 segmentInstructions 口径）。
        val body = ErbcWriter.encode(p)
        val header = PatchHeader(
            baseInstructionCount = counts.instructionCount,
            baseFunctionCount = counts.functionCount,
            baseStatementCount = counts.statementCount,
            baseStringCount = counts.stringCount,
            baseLongCount = counts.longCount,
            baseExprCount = counts.exprCount,
            baseSha256 = "a".repeat(64),
            compiler = PatchArtifacts.compilerSummary(),
            patchFunctionCount = p.functions.size,
            patchStatementCount = p.statementCount - counts.statementCount,
            patchStringCount = p.stringCount - counts.stringCount,
            patchLongCount = p.constantCount - counts.longCount,
            patchExprCount = p.exprCount - counts.exprCount,
            entryFunction = "B_HELLO",
            formatVersion = PATCH_FORMAT_VERSION_2,
        )
        val artifact = PatchArtifacts.of(header, body)   // I8 写侧闸：构造期容器校验
        val bytes = artifact.write()
        val readBack = PatchArtifacts.read(bytes)
        assertContentEquals(bytes, readBack.write(), "写读往返逐字节相同（不变量 I8）")
        assertEquals(header.entryFunction, readBack.header.entryFunction)
    }

    // ------------------------------------------------------------------
    // R7-E1：写头口径（headerOf）与私有 #DIM 载体
    // ------------------------------------------------------------------

    /** 私有 `#DIM` 的补丁源：标量（`#DIM PCOUNT`）+ 数组（`#DIM PTBL, 3`）各一条。 */
    private val patchDimSource = """
        @B_DIM
        #DIM PCOUNT
        #DIM PTBL, 3
        PCOUNT = 7
        PTBL:1 = 42
        RETURN 0
    """.trimIndent()

    @Test
    fun `headerOf 写头——私有 DIM 标量与数组进可选段`() {
        val (_, meta, counts) = compileBase()
        val result = patchCompile(meta, counts, patchSymbols(), source = patchDimSource, entry = "B_DIM")
        // C1 产出的私有 #DIM:两条、函数键 = 补丁函数、数组条的尺寸折到 3。
        assertEquals(2, result.privateDims.size, "标量 + 数组各一条: ${result.privateDims}")
        assertTrue(
            result.privateDims.all { it.functionKey == "B_DIM" },
            "函数键必须 = 补丁函数键: ${result.privateDims.map { it.functionKey }}",
        )
        assertEquals("PCOUNT", result.privateDims[0].spec.name)
        assertEquals("PTBL", result.privateDims[1].spec.name)
        assertContentEquals(intArrayOf(3), result.privateDims[1].spec.dims, "数组维尺寸折常量 3")

        val header = PatchCompiler.headerOf(result, counts, "a".repeat(64), entryFunction = "B_DIM")
        // 透传:头里的私有 #DIM 与 PatchResult 同源（同引用,不复制不裁剪）。
        assertTrue(
            header.privateDims === result.privateDims,
            "headerOf 必须原样透传 PatchResult.privateDims",
        )
        // 可选段进头文本（非空才写段）;整份头可读回且条数保真（I8 含新段的形态）。
        val text = header.encodeText()
        assertTrue(text.contains("private-dims \""), "非空可选段必须进头文本：$text")
        val readBack = PatchArtifacts.read(
            PatchArtifacts.of(header, ErbcWriter.encode(result.program)).write(),
        )
        assertEquals(2, readBack.header.privateDims.size, "读回条数保真")
        assertEquals("PTBL", readBack.header.privateDims[1].spec.name, "读回字段保真")
        assertContentEquals(
            intArrayOf(3), readBack.header.privateDims[1].spec.dims, "读回维度保真",
        )
    }

    @Test
    fun `headerOf 空 privateDims——15 字段 + function-metas 段`() {
        val (_, meta, counts) = compileBase()
        val result = patchCompile(meta, counts, patchSymbols())   // patchSource 无 #DIM
        assertTrue(result.privateDims.isEmpty(), "无私有声明 = 空表")
        val header = PatchCompiler.headerOf(result, counts, "a".repeat(64), entryFunction = "B_HELLO")
        val text = header.encodeText()
        assertFalse(text.contains("private-dims"), "空表整段省略：$text")
        // patchSource 的 B_SUM 带 #FUNCTION ⇒ function-metas 段非空（空表才整段省略）。
        // 行数 = 15 必需字段 + function-metas 1 行 + `---` + 尾空行（lines() 含末尾空串）。
        assertEquals(18, text.lines().size, "15 项字段 + function-metas + --- + 尾空行")
        assertTrue(text.lines()[15].startsWith("function-metas "), "第 16 行=function-metas 段: ${text.lines()[15]}")
        assertEquals("---", text.lines()[16], "第 17 行=头结束标记")
    }

    // ------------------------------------------------------------------
    // 常量关键字下标的解析期折叠（装载相位；与 erbcc 全量编译同一数据面）
    // ------------------------------------------------------------------

    /**
     * 设备端补丁编译是**运行期唯一还在解析脚本**的入口（[PatchCompiler] KDoc）：
     * 注入查表回调后，`ITEM:药` 这类常量下标必须在解析期折成整数，产物自带下标、
     * 运行期不再按名查盘上 CSV 表（spec: `VariableStrArgTerm.cs:57-69`）。
     * 反向用例锁「未注入 = 旧行为」——保留 [KeywordIndexArg]，报错时机与文案不变。
     */
    @Test
    fun `常量关键字下标——注入查表回调折成整数，未注入保留运行期查表结点`() {
        val sym = patchSymbols()
        val (_, meta, counts) = compileBase(sym = sym)
        val foldSource = """
            @B_FOLD
            ITEM:药 = 7
            RETURN 0
        """.trimIndent()
        val lookup: (String, Int, String) -> Long? =
            { v, d, k -> if (v.equals("ITEM", true) && d == 0 && k == "药") 3L else null }

        val folded = patchCompile(
            meta, counts, sym, source = foldSource, entry = "B_FOLD",
            constantKeywordLookup = lookup,
        )
        val foldedIndices = (counts.statementCount until folded.program.statementCount)
            .map { folded.program.statement(it) }
            .filterIsInstance<AssignStmt>()
            .first { it.raw.contains("ITEM") }
            .dest.indices
        assertTrue(
            foldedIndices.any { it is IntLiteral && it.value == 3L },
            "常量关键字下标必须在解析期折成整数 3：$foldedIndices",
        )
        assertTrue(
            foldedIndices.none { it is KeywordIndexArg },
            "折叠后产物里不能再留关键字名结点：$foldedIndices",
        )

        val unfolded = patchCompile(meta, counts, sym, source = foldSource, entry = "B_FOLD")
        val unfoldedIndices = (counts.statementCount until unfolded.program.statementCount)
            .map { unfolded.program.statement(it) }
            .filterIsInstance<AssignStmt>()
            .first { it.raw.contains("ITEM") }
            .dest.indices
        assertIs<KeywordIndexArg>(unfoldedIndices.last(), "未注入 = 旧行为（运行期按名查表）")
    }

    private companion object {
        /** [Instruction.WORDS] 的镜像（避免 import 歧义的别名；口径 = 4 字一条指令）。 */
        private const val Instruction_WORDS = 4
    }
}