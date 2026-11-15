package com.eratw.port.core.mod

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RT2-KOJOTRANS 判据 4 的真源触发缺口最小复现（CALLFORM 动态名通道过转码链）。
 *
 * ## 真源触发面（第三方字节不入库，只引形状与行号）
 *
 * kojo-community 三包共 13 处 `CALLFORM`/`TRYCALLFORM`（摩卡橙 3 / 蕾米莉亚 7 /
 * 风神少女 3）；代表行：`蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日记（简易版）.ERB:94`
 * 的 `CALLFORM DIARY_色情特化_K16_{PAGENUM}` 与 `:43` 的
 * `TRYCALLFORM DIARY_色情特化_K16_{LOCAL}_HAPPEN`。PatchCompiler 经
 * [com.eratw.port.core.compile.ErbProgramCompiler] 对 FORM 调用发射
 * `_EVAL_STR` + `_CALL_BY_NAME` 动态名通道（ErbProgramCompiler.kt:178；**纯文本非 TRY
 * 也保持动态**——ArgBuilders.kt `foldTryConstFormName` 的排除面），转码器原以
 * 「名字槽操作数不在重定位分类表内」为由拒绝——与分类表事实不符：
 * `_CALL_BY_NAME`/`_JUMP_BY_NAME` 在 [com.eratw.port.core.code.OpcodeRelocation]
 * 全槽 NONE（`a` = 帧内名字槽地址，ErbProgramCompiler.kt:2995，非池下标、非静态 pc），
 * 零基化 = 逐字保留即语义正确（按名决议在运行期对链接产物成立）。
 *
 * ## 复现装置
 *
 * 夹具纪律与 [PatchImportTranscoderTest] 同款：base 走真 [ErbProgramCompiler]、补丁走真
 * [PatchCompiler]、转码产物走真 [ErbModuleContainer.encode] / [ManifestCodec.encode] /
 * [ModChainAssembler.assemble] / 真 [GameSession]——不手搓假字节自证。
 *
 * 断言面（RT2-KOJOTRANS 判据 3/4）：
 * 1. 含 `CALLFORM` 的补丁产物能过转码（不再触发名字槽拒绝）；
 * 2. 动态名调用不需要导入表（descriptor.externalCalls 空——名字在运行期对链接产物决议）；
 * 3. 真 VM 执行：`CALLFORM A_ONE` 命中 base 函数（屏上 BASE-A1），后续语句照常执行。
 */
class PatchImportTranscoderCallFormTest {

    /** mini base：一个可被按名调用的 base 函数（带屏上锚点）。 */
    private val baseSource = """
        @A_ONE
        PRINTL BASE-A1
        RETURN 0
    """.trimIndent()

    /** 补丁：CALLFORM 动态名调 base 函数（真源 :94 的形状，目标换成 mini base 函数名）。 */
    private val patchSource = """
        @B_FORM
        CALLFORM A_ONE
        PRINTL FORM-DONE
        RETURN 0
    """.trimIndent()

    private fun symbols(): SymbolTable = SymbolTable()

    /** 与 [PatchImportTranscoderTest] 的 mini 装置同口径：`@` 行切组逐函数编译。 */
    private fun compileMini(
        rel: String,
        text: String,
        names: Set<String>,
    ): Program {
        val parser = LineParser(symbols(), lexerConfig = LexerConfig())
        val groups = collectGroups(rel, text, parser)
        val compiler = ErbProgramCompiler(callDefaults = emptyMap())
        for ((name, stmts) in groups) {
            val label = stmts.filterIsInstance<FuncLabelStmt>().first()
            compiler.compileFunction(
                FuncSig(name, label.paramNames.size, false, false, label.paramNames),
                stmts, names, ExprCallTable(nonMethodLabels = names),
            )
        }
        return compiler.build(groups.first().first)
    }

    /** 单文件切组：`@` 函数头开新组，`#` 指令行丢弃，其余归当前组。 */
    private fun collectGroups(
        rel: String,
        text: String,
        parser: LineParser,
    ): List<Pair<String, List<Statement>>> {
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(text.toByteArray(Charsets.UTF_8)),
            rel,
            config = LexerConfig(),
        )
        val groups = ArrayList<Pair<String, List<Statement>>>()
        var cur: String? = null
        val body = ArrayList<Statement>()
        var line = reader.readLogicalLine()
        while (line != null) {
            val stmt = parser.parseLogicalLine(line)
            when {
                stmt == null -> Unit
                stmt is FuncLabelStmt -> {
                    cur?.let { groups += it to body.toList() }
                    body.clear()
                    cur = stmt.name.uppercase()
                    body.add(stmt)
                }
                stmt is SharpStmt -> Unit
                cur != null -> body.add(stmt)
            }
            line = reader.readLogicalLine()
        }
        cur?.let { groups += it to body.toList() }
        return groups
    }

    /** base 编译 + 侧车元数据 + 窗口基线（PatchCompiler 续编同一份 base）。 */
    private fun compileBase(): Triple<Program, List<FuncMeta>, PatchCompiler.BaseCounts> {
        val p = compileMini("mini.ERB", baseSource, setOf("A_ONE"))
        val metas = p.functions.mapIndexed { i, f ->
            FuncMeta(
                index = i,
                key = f.name,
                name = f.name,
                isEvent = Symbols.isEventLabelName(f.name),
                isMethod = false,
                defIndex = i,
                position = "mini.ERB:${i + 1}",
            )
        }
        val counts = PatchCompiler.BaseCounts(
            instructionCount = p.instructionCount,
            functionCount = p.functions.size,
            statementCount = p.statementCount,
            stringCount = p.stringCount,
            longCount = p.constantCount,
            exprCount = p.exprCount,
        )
        return Triple(p, metas, counts)
    }

    @Test
    fun `CALLFORM补丁过转码_动态名不需导入表_真VM按名命中base函数`() {
        val (base, metas, counts) = compileBase()
        val result = PatchCompiler(BootMetadata(functions = metas), counts, symbols()).compile(
            listOf(PatchCompiler.SourceFile("mods/callform.ERB", patchSource)),
            "B_FORM",
        )
        val t = PatchImportTranscoder.transcode(
            result,
            counts,
            metas.associate { it.index to it.key },
            "kojo.callform",
        )

        // 零基线 + 函数集（合成入口截断面无事件名，全保留）
        assertEquals(0, t.module.program.functionBase, "模块 functionBase 必须归零")
        assertEquals(0, t.module.program.codeBase, "模块 codeBase 必须归零")
        assertContentEquals(listOf("B_FORM"), t.module.functions.map { it.key })
        assertTrue(
            t.module.allowSuperCalls,
            "容器档位 = Mod 包编译档（生产装配闸只收该形状）；super 哨兵不存在由转码期" +
                "fail-closed 保证，与档位无关（RT2-KOJOTRANS 缺口②钉）",
        )
        assertEquals(
            emptyList(),
            t.imports,
            "动态名通道不产生导入：_CALL_BY_NAME 无静态函数下标，名字运行期对链接产物决议",
        )

        // 生产装配路 + 真 VM：CALLFORM 命中 base 函数（屏上锚点）+ 后续语句照常执行
        val chain = ModChainAssembler.assemble(
            listOf(ManifestSource("kojo.callform.manifest", ManifestCodec.encode(t.descriptor))),
            listOf(t.module.toCompiledModule()),
            base,
            metas,
        )
        val plan = assertNotNull(ModChainRuntimePlan.of(chain), "非旁路装配必须产出 plan")
        val root = Files.createTempDirectory("kojo-callform").also { it.toFile().deleteOnExit() }
        val session = GameSession(root, base, BootMetadata(functions = metas), modChain = plan)
        session.runtime.callFunction("B_FORM")
        assertContentEquals(
            listOf("BASE-A1", "FORM-DONE"),
            session.console.loggedLines(),
            "CALLFORM 动态名调用必须命中 base 的 A_ONE（BASE-A1）且补丁后续语句照常执行（FORM-DONE）",
        )
        assertTrue(session.privateDimFailures.isEmpty(), "无私有 DIM 声明 ⇒ 注册链零失败")
    }
}
