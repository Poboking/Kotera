package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **VM 抛点帧的文件维度**（MODERN-R8-A）：[CodeEE.scriptFile] 由
 * [VmHost.scriptFileOf] 在抛点当场挂上，不经语句层捕获点。
 *
 * 原版口径（为什么必须是抛点帧）：错误位置挂在**异常自己身上**
 * （`CodeEE.cs:5-8` 的 `CodeEE(errormes, ScriptPosition? position)`、
 * `EmueraException.cs:7-13` 的 `Position` 字段），上屏时它优先于「捕获点那一行」
 * （`Process.cs:598-606`：`ex.Position` 命中就不看 `current`），文件名由此进
 * `Lang.Error.ErrorFileAndLine`（`Lang.cs:2954`）、无行号时进 `Lang.Error.ErrorFile`
 * （`Lang.cs:2958`，zhs `lang.emuera-zhs.xml:693/694` 同形）。
 *
 * 本测试是纯 VM 层（不经过 [com.eratw.port.core.proc.ProcessRuntime]）：
 * * 覆写 [VmHost.scriptFileOf] 的宿主 ⇒ 抛点错误带该文件；
 * * **不覆写**的宿主（缺省 null）⇒ 同一错误的 `scriptFile` 为 null，而
 *   message / funcName / line / pc 两项读数逐字一致（§0 铁律 6：默认宿主行为零变化）；
 * * 守卫异常（[CallStackOverflow] / [StepLimitExceeded]）同通道带文件，其余字段不动。
 */
class VmErrorFileStampTest {

    /** 记录通道调用、按固定名作答的宿主；[file] = null 时等价于「有通道但没有这份信息」。 */
    private class FileHost(private val file: String?) : VmHost {
        /** 每次 `scriptFileOf` 的实参（帧下标, 函数键），用于钉通道协议。 */
        val calls = ArrayList<Pair<Int, String>>()
        override val evalContext: EvalContext? = null

        override fun print(text: String) {}

        override fun scriptFileOf(functionIndex: Int, functionKey: String): String? {
            calls += functionIndex to functionKey
            return file
        }
    }

    /** VM 的缺省宿主（不覆写 [VmHost.scriptFileOf]）—— 行为必须与加通道之前逐字一致。 */
    private fun defaultHost(): VmHost = StringHost()

    /**
     * 帧内槽越界：`_SET_INT R5, 7` 而 MAIN 只声明 1 槽 ⇒ `Vm.frameSlotIndex` 的守卫走 `errorAt`。
     *
     * 用 [CodeBuilder] 直接发（文本汇编器会把 `slots=` 按用到的最大槽号自动撑大，写不出越界形态，
     * 口径同 [VmSlotBoundsTest] 的 `frameWriteProgram`）。
     */
    private fun slotBoundsProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("MAIN", 0, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.local(5), b.constant(7L), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        return b.build(main)
    }

    @Test
    fun `errorAt 把抛点帧的文件挂到错误上`() {
        val host = FileHost("ERB/MAIN.ERB")
        val e = assertFailsWith<CodeEE> { VirtualMachine(slotBoundsProgram(), host).run() }
        assertTrue((e.message ?: "").contains("槽越界"), "应是槽越界守卫抛出：${e.message}")
        assertEquals("MAIN", e.funcName)
        assertEquals("ERB/MAIN.ERB", e.scriptFile)
        // 通道协议：帧下标（funcStack[depth]）+ 大写函数键。
        assertEquals(listOf(0 to "MAIN"), host.calls)
    }

    @Test
    fun `缺省宿主不覆写通道时同一错误不带文件且其余字段逐字相同`() {
        val program = slotBoundsProgram()
        val withFile = assertFailsWith<CodeEE> { VirtualMachine(program, FileHost("ERB/MAIN.ERB")).run() }
        val noFile = assertFailsWith<CodeEE> { VirtualMachine(program, defaultHost()).run() }
        assertEquals("ERB/MAIN.ERB", withFile.scriptFile)
        assertNull(noFile.scriptFile, "缺省宿主没有文件表 ⇒ 留 null，不编造文件名")
        // 其余字段逐字一致（加通道不许动既有读数）。
        assertEquals(withFile.message, noFile.message)
        assertEquals(withFile.funcName, noFile.funcName)
        assertEquals(withFile.line, noFile.line)
        assertEquals(withFile.pc, noFile.pc)
    }

    @Test
    fun `宿主答 null 时不留文件`() {
        val e = assertFailsWith<CodeEE> { VirtualMachine(slotBoundsProgram(), FileHost(null)).run() }
        assertNull(e.scriptFile)
        assertEquals("MAIN", e.funcName)
    }

    /**
     * 调用栈溢出：`maxCallDepth = 1` 时 B 里的第二个 `_CALL` 被拦
     * （`Vm.callInto` 的 `depth + 1 > config.maxCallDepth`）。
     * 断言文件与 `depth` / `callChain` 同时保留（新字段不许挤掉既有字段）。
     */
    @Test
    fun `调用栈溢出带抛点帧文件并保留深度与调用链`() {
        val program = Assembler.assemble(
            """
            .func MAIN slots=1
              _CALL B, 0, none
              _HALT
            .func B slots=1
              _CALL B, 0, none
              _RET
            """.trimIndent(),
        )
        val host = FileHost("ERB/B.ERB")
        val e = assertFailsWith<CallStackOverflow> {
            VirtualMachine(program, host, VmConfig(maxCallDepth = 1)).run()
        }
        assertEquals(1, e.depth)
        assertEquals("MAIN → B", e.callChain)
        assertEquals("B", e.funcName)
        assertEquals("ERB/B.ERB", e.scriptFile)
        assertTrue(e.pc >= 0)
        // 抛点在 B 的帧上：通道拿到的是 B 的下标与键。
        assertEquals(listOf(1 to "B"), host.calls)
    }

    /**
     * 装置预算闸（[LoopGuardCause.DeviceBudget]）：`maxStepsPerRun = 1` 时第二条指令前撞线。
     * 文件与 `steps` / `guardCause` 同时保留，message 逐字不变（本波不动文案）。
     */
    @Test
    fun `步数预算闸带抛点帧文件并保留步数与成因`() {
        val program = Assembler.assemble(
            """
            .func MAIN slots=1
              _SET_INT R0, 1
              _HALT
            """.trimIndent(),
        )
        val host = FileHost("ERB/MAIN.ERB")
        val e = assertFailsWith<StepLimitExceeded> {
            VirtualMachine(program, host, VmConfig(maxStepsPerRun = 1L)).run()
        }
        assertEquals(LoopGuardCause.DeviceBudget, e.guardCause)
        assertEquals(2L, e.steps)
        assertEquals("MAIN", e.funcName)
        assertEquals("ERB/MAIN.ERB", e.scriptFile)
        assertEquals("执行步数超过上限(2)，疑似死循环 @ MAIN", e.message)
    }
}
