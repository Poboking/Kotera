package com.eratw.port.core.fn

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 策略缺席位的机制钉（PLUGIN-POLICY，2026-11-07，主人指令）。
 *
 * 目录（宿主策略，app `PluginPolicyCatalog`）内已知名打
 * [NativeMethodRegistry.markPolicyAbsent] 后的契约：
 * - **装载趟**（[prefillSharpLinks]）：不收警告、不落裁决（行保持未裁决态）；
 * - **执行期**（`StatementExecutor` 的 CALLSHARP 分支）：整句静默空转 —— 不抛、
 *   不求实参、不回写，出参变量保持原值；
 * - **目录外未知名字**：装载期仍收官方警告；执行期走 origin/main 的通用策略
 *   （2026-11-09：打一行官方文案后跳过该语句，不抛）—— 策略位只吞目录内名字，
 *   这是对 ADR-005 的收窄而非废除（负控在本文件第 4 例）。
 *
 * 夹具与 `proc/AiChatSharpStubTest` 同源：4.981 AI对话补丁的 CALLSHARP 形状
 * （`对话调用.erb:35-36`，全语料仅有的可达调用点），flat 生产链。该类的
 * 「无标记 = 官方拒载」钉与本文件的「有标记 = 静默空转」钉互为正反两面。
 */
class PluginPolicyAbsentTest {

    /** 与 AiChatSharpStubTest 同源的最小编译夹具（源文本 → 语句 → flat Program）。 */
    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("模型API", OperandType.STR, 0)
                .declareVariable("模型Key", OperandType.STR, 0)
                .declareVariable("模型名称", OperandType.STR, 0)
                .declareVariable("AI对话返回信息", OperandType.STR, 0)
                .declareVariable("返回对话", OperandType.STR, 0),
        )
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "对话调用.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("CHAT_ACTION_LIKE", 0),
            parse("@CHAT_ACTION_LIKE", *lines),
            setOf(),
            ExprCallTable(),
        )
        return c.build("CHAT_ACTION_LIKE")
    }

    /** 语料侧这 5 个变量是 `AI返回参数.ERH` 的全局 `#DIMS`；运行期 store 需同名可解析。 */
    private fun freshStore(): VariableStore {
        val store = VariableStore()
        for (name in listOf("模型API", "模型Key", "模型名称", "AI对话返回信息", "返回对话")) {
            store.declareUserVar(DimSpec(name, isStr = true, dims = IntArray(0), isGlobal = true))
        }
        return store
    }

    private fun runtimeWith(program: Program, store: VariableStore, console: LineBufferConsoleSink) =
        ProcessRuntime(
            program,
            store,
            FunctionTable(emptyList()),
            VmConfig(maxStepsPerRun = 100_000),
            consoleSink = console,
        )

    @Test
    fun `策略缺席名字 装载趟不警告不落裁决`() {
        val program = compile(
            "CALLSHARP ModelSet(模型API, 模型Key, 模型名称)",
            "CALLSHARP Chat(AI对话返回信息, 返回对话)",
        )
        assertTrue(program.flatStatementsEnabled, "本钉必须走 flat 生产链")
        val store = freshStore()
        nativeMethods(store.aux).markPolicyAbsent(listOf("ModelSet", "Chat"))
        // 装载趟：目录内名字零警告（对比 AiChatSharpStubTest 无标记时两句官方文案）。
        assertEquals(emptyList(), prefillSharpLinks(store.aux, program), "缺席名字不产生装载警告")
        // 且不落裁决：行保持未裁决态（执行期每执行现查策略位）。
        val firstSharp = program.sharpStatementIndices().first()
        val links = sharpLinks(store.aux)
        assertNull(links.link(firstSharp.toLong()), "缺席行不落 flat 行键裁决")
        assertNull(links.link(program.statement(firstSharp).args!!), "缺席行不落实参身份行键裁决")
    }

    @Test
    fun `策略缺席名字 执行整句空转 不抛不回写`() {
        val program = compile(
            "CALLSHARP ModelSet(模型API, 模型Key, 模型名称)",
            "CALLSHARP Chat(AI对话返回信息, 返回对话)",
        )
        val store = freshStore()
        nativeMethods(store.aux).markPolicyAbsent(listOf("ModelSet", "Chat"))
        // 出参变量预置非空原值：空转不得回写（回写同值也算越权 —— 缺席 = 语句没发生过）。
        // 本夹具的变量是 0 维标量（dims = IntArray(0)），读写用空下标。
        store.writeStr("返回对话", longArrayOf(), "原值哨兵")
        val console = LineBufferConsoleSink(clientWidth = 136, printCWidth = 25, maxLines = 900)
        runtimeWith(program, store, console).execute() // 不抛 = 官方 CodeEE 已被策略位拦截
        assertEquals("原值哨兵", store.readStr("返回对话", longArrayOf()), "空转不回写出参")
        val printed = console.lines().map { console.textOf(it) }
        assertTrue(printed.all { it.isBlank() }, "屏面零噪声，实得：$printed")
    }

    @Test
    fun `缺席位大小写口径与注册表一致`() {
        val registry = NativeMethodRegistry()
        registry.markPolicyAbsent(listOf("chat"))
        assertTrue(registry.isPolicyAbsent("Chat"), "键大写化同 register/find 口径")
        assertTrue(registry.isPolicyAbsent("CHAT"))
        assertFalse(registry.isPolicyAbsent("ModelSet"), "未打位的名字不受影响")
        assertEquals(listOf("CHAT"), registry.absentNames())
    }

    @Test
    fun `目录外未注册名字仍官方提示 负控`() {
        val program = compile("CALLSHARP UnknownPlugin(AI对话返回信息, 返回对话)")
        val store = freshStore()
        nativeMethods(store.aux).markPolicyAbsent(listOf("ModelSet", "Chat"))
        // 装载趟：未知名字照旧收官方警告 —— 策略位只吞目录内名字。
        assertEquals(
            listOf("No native method UnknownPlugin found"),
            prefillSharpLinks(store.aux, program),
            "目录外未注册名字仍走官方缺插件形态",
        )
        // 执行期：origin/main 的通用策略（2026-11-09）—— 打一行官方文案后跳过，不抛。
        val console = LineBufferConsoleSink(136, 25, 900)
        runtimeWith(program, store, console).execute()
        val errors = console.snapshot().flatMap { it.segments }
            .filter { it.kind == LineBufferConsoleSink.Kind.ERROR }
            .map { it.text }
        assertEquals(
            listOf("No native method UnknownPlugin found"),
            errors,
            "执行期官方文案逐字不变（通用策略：提示一次后跳过）",
        )
    }
}
