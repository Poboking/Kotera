package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.RuntimeCallResolution
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RT2 G 车（P1-5）：`functionDispatch` 透传哨兵 —— `ProcessRuntime(functionDispatch = …)`
 * 参数必须真的流进 [com.eratw.port.core.code.VirtualMachine] 构造期（`Vm.kt:703-706`
 * 的表长 require 自检）。`vm` 是 private，不做反射直断言；改用**构造期失败形状**做哨兵：
 * 表长不匹配 = VM 构造期 require 抛 ⇒ 参数已进 VM；表长匹配恒等表 / 缺省 null 构造成功
 * ⇒ 直调快路径语义不变（golden 零变化的前提，`VmDispatchTest` 7 例锁 VM 层查表语义）。
 *
 * 生产接线（G 车本批）：`GameSession(functionDispatch = …)` → 本参数 → VM；erbcc 模块
 * 编译余量落地前生产恒 null。
 */
class ProcessRuntimeDispatchWireTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexed { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            p.parseLogicalLine(LogicalLine(kind, "dispatch.erb", i + 1, text))
        }.filterNotNull()
    }

    private class Model(val program: Program, val metas: List<FuncMeta>, val identity: IntArray) {
        fun runtime(dispatch: IntArray?): ProcessRuntime = ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(metas),
            VmConfig(),
            functionDispatch = dispatch,
        )
    }

    /** 两函数产物（MAIN + F）：恒等表 = 编译器侧函数绝对下标升序。 */
    private fun build(): Model {
        val c = ErbProgramCompiler()
        val tbl = ExprCallTable(setOf("F"), emptySet(), emptySet())
        val known = setOf("MAIN", "F")
        c.compileFunction(FuncSig("MAIN", 0), parse("@MAIN", "LOCAL:0 = 1"), known, tbl)
        c.compileFunction(FuncSig("F", 0), parse("@F", "LOCAL:0 = 2"), known, tbl)
        val metas = listOf("MAIN", "F").map { name ->
            FuncMeta(
                index = c.functions.first { it.sig.name.equals(name, ignoreCase = true) }.index,
                key = name, name = name, localSize = 20, position = "dispatch.erb",
            )
        }
        val identity = metas.sortedBy { it.index }.map { it.index }.toIntArray()
        return Model(c.build("MAIN"), metas, identity)
    }

    @Test
    fun `表长不匹配_构造期整包拒绝_证明参数真进了VM`() {
        val model = build()
        val e = assertFailsWith<IllegalArgumentException> {
            model.runtime(intArrayOf(0))
        }
        assertTrue(
            e.message!!.contains("functionDispatch 长度"),
            "异常须点名 functionDispatch 表长自检，实得：${e.message}",
        )
    }

    @Test
    fun `恒等表构造成功_直调语义零行为差`() {
        val model = build()
        val rt = model.runtime(model.identity)
        assertTrue(rt != null, "恒等表 = baseline 直调快路径，会话必须正常建出")
    }

    @Test
    fun `缺省null快路径_与既有装配逐位一致`() {
        val model = build()
        val rt = model.runtime(null)
        assertTrue(rt != null, "缺省 null = 无 Mod 链产物快路径，会话必须正常建出")
    }
}

class ProcessRuntimeCatalogNameChannelTest {
    private data class Fixture(val program: Program, val metas: List<FuncMeta>, val catalog: SymbolCatalog)

    private fun build(): Fixture {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", b.here(), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)
        fun function(name: String, value: Long): Int {
            val pc = b.here()
            val fn = b.declareFunction(name, pc, 0, 1)
            b.noteGlobalSlot(0)
            b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(value), 0, 1)
            b.emit(Opcode._SET_INT, SlotAddr.global(0), b.constant(value), 0, 1)
            b.emit(Opcode._PRINT_INT, SlotAddr.global(0), 1, 0, 2)
            b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 3)
            b.setFunctionSlots(fn, 1)
            return fn
        }
        function("WRAP", 22)
        function("BASE", 11)
        function("METHOD_WRAP", 44)
        function("METHOD_BASE", 33)
        val program = b.build(0)
        val metas = listOf(
            FuncMeta(0, "MAIN", "MAIN", defIndex = 0),
            FuncMeta(1, "FOO", "WRAP", defIndex = 1),
            FuncMeta(2, "FOO", "BASE", defIndex = 2),
            FuncMeta(3, "MF", "METHOD_WRAP", isMethod = true, defIndex = 3),
            FuncMeta(4, "MF", "METHOD_BASE", isMethod = true, defIndex = 4),
        )
        val catalog = SymbolCatalog.build(
            metas.map { SymbolCandidate(it) }, ResolverPolicy.LEGACY_LAST_WINS,
            aspectHeads = mapOf("FOO" to 1, "MF" to 3),
        )
        return Fixture(program, metas, catalog)
    }

    @Test
    fun `四类名字通道均返回并执行catalog最终入口`() {
        val f = build()
        val store = VariableStore()
        val rt = ProcessRuntime(f.program, store, FunctionTable(f.metas), symbolCatalog = f.catalog)
        assertEquals(1, f.catalog.entryIndexOf("foo"))
        assertEquals(1, rt.resolveFunctionIndex("foo"))
        assertEquals(3, rt.resolveMethodIndex("mf"))
        assertEquals(22L, rt.callFunction("FOO"))
        rt.tryCall("foo")
        rt.callForm("FoO")
        val dynamic = rt.resolveRuntimeCall("FOO()")
        assertEquals(1, (dynamic as RuntimeCallResolution.Ready).targetIndex)
        rt.callF("MF")
        assertEquals(Value.IntV(44L), rt.invokeUserMethod("mf", emptyList()))
        assertEquals(Value.IntV(44L), rt.evalContext.callFunction("MF", emptyList()))
        assertEquals("22\n22\n22\n44\n44\n44\n", rt.output.toString())
    }

    @Test
    fun `真实CALL_BY_NAME字节码消费最终入口且不二次分派`() {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", b.here(), 0, 1)
        b.noteGlobalSlot(0)
        b.noteGlobalSlot(1)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("FOO"), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), 0, SlotAddr.NONE, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("MF"), 0, 3)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), 0x20000000, SlotAddr.NONE, 4)
        b.emit(Opcode._PRINT_INT, SlotAddr.global(0), 1, 0, 5)
        b.emit(Opcode._PRINT_INT, SlotAddr.global(1), 1, 0, 6)
        b.emit(Opcode._HALT, 0, 0, 0, 7)
        b.setFunctionSlots(main, 1)
        fun function(name: String, global: Int, value: Long) {
            val pc = b.here()
            val fn = b.declareFunction(name, pc, 0, 1)
            b.emit(Opcode._SET_INT, SlotAddr.global(global), b.constant(value), 0, b.here() + 1)
            b.emit(Opcode._RET, 0, 0, 0, b.here() + 1)
            b.setFunctionSlots(fn, 1)
        }
        function("WRAP", 0, 22L)
        function("BASE", 0, 11L)
        function("METHOD_WRAP", 1, 44L)
        function("METHOD_BASE", 1, 33L)
        val program = b.build(0)
        val metas = listOf(
            FuncMeta(0, "MAIN", "MAIN", defIndex = 0),
            FuncMeta(1, "FOO", "WRAP", defIndex = 1),
            FuncMeta(2, "FOO", "BASE", defIndex = 2),
            FuncMeta(3, "MF", "METHOD_WRAP", isMethod = true, defIndex = 3),
            FuncMeta(4, "MF", "METHOD_BASE", isMethod = true, defIndex = 4),
        )
        val catalog = SymbolCatalog.build(
            metas.map { SymbolCandidate(it) }, ResolverPolicy.LEGACY_LAST_WINS,
            aspectHeads = mapOf("FOO" to 1, "MF" to 3),
        )
        val store = VariableStore()
        val runtime = ProcessRuntime(program, store, FunctionTable(metas), symbolCatalog = catalog)
        runtime.execute()
        assertEquals("22\n44\n", runtime.output.toString())
    }

    @Test
    fun `屏蔽判据跟随最终分派入口而非逻辑胜者`() {
        val f = build()
        val winnerDisabled = ProcessRuntime(
            f.program, VariableStore(), FunctionTable(f.metas), symbolCatalog = f.catalog,
            disabledFunctions = setOf("BASE", "METHOD_BASE"),
        )
        assertEquals(22L, winnerDisabled.callFunction("FOO"))
        winnerDisabled.callF("MF")
        assertEquals(Value.IntV(44L), winnerDisabled.invokeUserMethod("MF", emptyList()))
        assertEquals("22\n44\n44\n", winnerDisabled.output.toString())

        val entryDisabled = ProcessRuntime(
            f.program, VariableStore(), FunctionTable(f.metas), symbolCatalog = f.catalog,
            disabledFunctions = setOf("WRAP", "METHOD_WRAP"),
        )
        assertEquals(0L, entryDisabled.callFunction("FOO"))
        entryDisabled.callF("MF")
        assertEquals(Value.IntV(0L), entryDisabled.invokeUserMethod("MF", emptyList()))
        assertEquals("", entryDisabled.output.toString())
    }

    @Test
    fun `catalog事件一对多不被合成非事件入口遮蔽`() {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", b.here(), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)
        fun event(name: String, isEvent: Boolean): Int {
            val pc = b.here()
            val fn = b.declareFunction(name, pc, 0, 1)
            b.emit(Opcode._HALT, 0, 0, 0, 1)
            b.setFunctionSlots(fn, 1)
            return fn
        }
        event("EVENT_A_FIRST", true)
        event("EVENT_A_SECOND", true)
        event("EVENT_A_PLAIN", false)
        val program = b.build(0)
        val metas = listOf(
            FuncMeta(0, "MAIN", "MAIN"),
            FuncMeta(1, "EVENT_A", "EVENT_A_FIRST", isEvent = true),
            FuncMeta(2, "EVENT_A", "EVENT_A_SECOND", isEvent = true),
            FuncMeta(3, "EVENT_A", "EVENT_A_PLAIN", position = "synthetic-event-entry"),
        )
        val catalog = SymbolCatalog.build(metas.map { SymbolCandidate(it) }, ResolverPolicy.LEGACY_LAST_WINS)
        assertEquals(listOf(1, 2), catalog.eventMembersOf("event_a").map { it.physicalIndex })
        assertEquals(3, catalog.winnerIndexOf("EVENT_A"))
        val runtime = ProcessRuntime(program, VariableStore(), FunctionTable(metas), symbolCatalog = catalog)
        assertFailsWith<CodeEE> { runtime.resolveFunctionIndex("EVENT_A") }
        assertNull(runtime.resolveMethodIndex("EVENT_A"))
    }

    @Test
    fun `无catalog时名字通道仍消费同一dispatch表`() {
        val f = build()
        val rt = ProcessRuntime(f.program, VariableStore(), FunctionTable(f.metas), functionDispatch = f.catalog.dispatch)
        assertEquals(22L, rt.callFunction("FOO"))
        assertEquals("22\n", rt.output.toString())
        val physical = ProcessRuntime(f.program, VariableStore(), FunctionTable(f.metas))
        assertEquals(11L, physical.callFunction("FOO"))
        assertEquals("11\n", physical.output.toString())
    }
}
