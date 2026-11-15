package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
