package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeEE
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **抛点 ERB 帧栈导出**（`ProcessRuntime.erbFrameStack`）的回归锁。
 *
 * ## 缺口（2026-11-08/09 上报端点）
 *
 * 报告 `[ERB 调用栈与 VM 现场]` 的 ERB 帧位此前只有函数名（`funcStackAtThrow` 镜像），
 * 没有行号/pc/调用点；而 `CodeEE.line` 是 **0 基**行下标（物理行 = line + 1，
 * 见 `CodeEE` 类头与 `RandZeroArgChainAuditTest`），报告里不标注就会被当成物理行误读。
 *
 * ## 本类钉的四件事
 *
 * 1. 两个函数相套时导出 **>= 2 帧**，最内帧在前，函数名与调用关系对得上；
 * 2. 每帧的 **0 基行**来自真实编译出来的行号表：`RESULT = LOCAL:10`（局部长度 = 4，
 *    运行期越界读 CodeEE）写在数组下标 1 的那一行 ⇒ 导出 `line = 1`（物理第 2 行），
 *    与 `CodeEE.line = 1` 同口径；
 * 3. 调用点：最内帧的调用落在 `CALLER` 的 `CALL CALLEE` 行（0 基 1），调用方函数名 = CALLER；
 * 4. 正常返回 / 从未执行 / 非 VM 线程式普通异常 ⇒ 空表，导出口**绝不抛**。
 *
 * 反向证明：把 [ProcessRuntime.erbFrameStack] 的返回改成 `emptyList()`，本类前两条
 * 用例当场红（`size == 2` 与帧名断言）。
 */
class ErbFrameStackExportTest {

    /** 行号按**生产口径**给 0 基（LogicalLineReader.curNo 从 0 起），不用测试汇编器的 1 基。 */
    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "erbframe.erb", i, text))
        }
    }

    private data class Fn(val name: String, val lines: Array<String>)

    private fun compile(vararg fns: Fn): Pair<ErbProgramCompiler, Program> {
        val c = ErbProgramCompiler()
        val names = fns.map { it.name }.toSet()
        for (f in fns) c.compileFunction(FuncSig(f.name, 0), parse(*f.lines), names)
        return c to c.build(fns.first().name)
    }

    private fun runtime(c: ErbProgramCompiler, program: Program, fns: List<Fn>): ProcessRuntime {
        val metas = fns.map { f ->
            val idx = c.functions.first { it.sig.name == f.name }.index
            FuncMeta(index = idx, key = f.name, argIntCount = 0, argStrCount = 0, defIndex = idx)
        }
        return ProcessRuntime(program, VariableStore().apply {
            fns.forEach { defineLocalLengths(it.name, VariableStore.LocalLengths(local = 4)) }
        }, FunctionTable(metas), VmConfig())
    }

    /**
     * 0 基行号自证：
     * CALLER[0]=@CALLER、[1]=CALL CALLEE；CALLEE[0]=@CALLEE、[1]=RESULT = LOCAL:10。
     * 抛点在 CALLEE 的 0 基第 1 行 ⇒ 物理第 2 行。
     *
     * 原钉 `RESULT = RAND(0)`：4.981 `VariableToken.cs:1425-1434` 已把 RAND 非法上界改成
     * 钳制 0（76a360ec0），不再抛；改用运行期必抛的越界局部变量读（每函数 local 长度 = 4），
     * 帧形/行号口径不变。
     */
    private val throwing = arrayOf(
        Fn("CALLER", arrayOf("@CALLER", "CALL CALLEE", "RETURN")),
        Fn("CALLEE", arrayOf("@CALLEE", "RESULT = LOCAL:10")),
    )

    @Test
    fun `抛 CodeEE 时导出两层帧且函数名与调用关系对得上`() {
        val (c, program) = compile(*throwing)
        val rt = runtime(c, program, throwing.toList())

        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertTrue("LOCAL" in e.message.orEmpty(), "报错串应保持原样，实际：${e.message}")

        val frames = rt.erbFrameStack()
        assertEquals(2, frames.size, "至少导出调用者/被调者两帧，实际：$frames")
        assertEquals("CALLEE", frames[0].function, "最内帧在前")
        assertEquals("CALLER", frames[1].function)
        assertEquals("CALLER", frames[0].callSiteFunction, "最内帧的调用方是 CALLER")
        assertTrue(frames[0].pc >= 0, "最内帧必须带抛错指令 pc，实际：${frames[0]}")
        assertTrue(frames[1].pc >= 0, "外层帧必须带挂起调用点 pc，实际：${frames[1]}")
    }

    @Test
    fun `帧行号是零基且物理行为加一`() {
        val (c, program) = compile(*throwing)
        val rt = runtime(c, program, throwing.toList())

        val e = assertFailsWith<CodeEE> { rt.execute() }
        val frames = rt.erbFrameStack()
        assertTrue(frames.isNotEmpty(), "抛点必须留下帧链")

        // 最内帧 = RESULT = LOCAL:10，源码数组下标 1 ⇒ 0 基 1 / 物理 2。
        assertEquals(1, frames[0].line, "最内帧 0 基行")
        assertEquals(e.line, frames[0].line, "导出帧行号必须与 CodeEE.line 同口径（0 基）")
        assertEquals(2, frames[0].line + 1, "物理行 = 0 基行 + 1")

        // 外层帧此刻挂在 CALL CALLEE（CALLER 的 0 基第 1 行），调用点也落在同一个 0 基行。
        assertEquals(1, frames[1].line, "外层帧挂起行")
        assertEquals(1, frames[0].callSiteLine, "最内帧的调用点 0 基行")
        assertEquals(frames[1].line, frames[0].callSiteLine, "调用点行必须等于调用方本帧挂起行")
    }

    @Test
    fun `正常返回与从未执行都导出空表且不抛`() {
        val ok = arrayOf(Fn("OK", arrayOf("@OK", "RETURN")))
        val (c, program) = compile(*ok)
        val rt = runtime(c, program, ok.toList())

        assertTrue(rt.erbFrameStack().isEmpty(), "还没跑过 ⇒ 没有抛点镜像")
        rt.execute()
        assertTrue(rt.erbFrameStack().isEmpty(), "正常返回不得留下轨迹（新一次 run 起跑清零）")
    }

    @Test
    fun `重复读取与无异常路径都安全降级`() {
        val (c, program) = compile(*throwing)
        val rt = runtime(c, program, throwing.toList())
        assertFailsWith<CodeEE> { rt.execute() }

        val first = rt.erbFrameStack()
        val second = rt.erbFrameStack()
        assertNotNull(first)
        assertEquals(first.size, second.size, "只读取证口可重复读，不消耗状态")
        assertEquals(
            first.map { it.function to it.line },
            second.map { it.function to it.line },
            "两次读数逐帧一致",
        )
    }
}