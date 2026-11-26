package com.eratw.port.perf

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.StringHost
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.diag.LiveSetCensus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * lane-p：派生 VM 池（`ProcessRuntime.nestedVmPool`）常驻字节读数 [LiveSetCensus.nestedVmResident]
 * 的定向用例（EP-heap 波，只测不改）。
 *
 * ## 断言的是哪几条「算式」
 * 1. **单台底座**：`VmConfig()` 缺省 `maxCallDepth = 10_000`（`Vm.kt:106`），派生 VM 构造即分满
 *    7×`IntArray(10_001)` + 2×`BooleanArray(10_001)` + `LongArray(1_024)` + `arrayOfNulls(1_024)`
 *    + 64 槽两对 ⇒ 表和的 313,598 B。实测 **313,610 B**（+12 B 零头）。这里钉「≥ 表和的
 *    313,598」且「≤ 表和的 + 64」—— 下界钉死算式不被改小，上界钉住「新建 VM 不带 handle 表」
 *    （`varHandleTables` 是执行期懒建，`Vm.kt:791`/`:3151`）。
 * 2. **线性**：N 台 = N × 单台（池的字节账没有常数项、没有去重/共享漏算）⇒
 *    **上界 = 池上限 × 单台**，池上限由 `METHOD_STACK_LIMIT = 100`（`ProcessRuntime.kt:5028`）给出。
 * 3. **哨兵**：null → -1（宿主没接线，旧形状逐字不变）、空池 → 0（池确实空）。
 * 4. **census 接线**：`nestedVmPool = null` 时 `Census.nestedVmResident` 必须是 -1（不许把「没接线」
 *    写成 0 = 「池空」），传池时等于现算值。
 *
 * 不搭真语料：`Assembler.assemble` 造一个只有 `_HALT` 的 Program 就够 —— 派生 VM 的常驻字节
 * 与程序大小无关（只看 `maxCallDepth`/`OP_STACK_DEPTH`/`INITIAL_SLOTS`/`STMT_MEMO_SLOTS`）。
 */
class NestedVmPoolResidentTest {

    /** 单台底座下界：见类 KDoc 第 1 条的表，逐项相加（不含对象头，与本类其余读数同口径）。 */
    // 第一条 7 项：retPc/frameBaseStack/frameSlotStack/retDstStack/funcStack/opBaseI/opBaseS
    private val baseFloorBytes = 7L * 10_001L * 4L +
        2L * 10_001L * 1L + // retStrStack/frameExited
        1_024L * 8L + // opStackI
        1_024L * 4L + // opStackS（引用宽 4 B，压缩 oops）
        64L * 8L + // frameInts
        64L * 4L + // frameStrs
        64L * 4L + // stmtMemoKey
        64L * 4L // stmtMemoVal

    /**
     * 单台上界 = 下界 + 零头。实测（本用例 2026-11-17 跑出）**313,610 B**，比表和的 313,598 B
     * 多 **12 B**（`Vm.kt` 里还有一两枚几十字节的小数组没进这张表，量级可忽略）。
     * 这里留 64 B 容差而不是写死 313,610：底座一旦因为 `maxCallDepth`/`OP_STACK_DEPTH`/
     * `INITIAL_SLOTS`/新增栈数组而变化，这条就得红——那正是「池上界算式」要跟着改的信号。
     */
    private val baseCeilingBytes = baseFloorBytes + 64L

    private fun program() = Assembler.assemble(
        """
        .func main slots=2
          _HALT
        """.trimIndent(),
    )

    /** 真派生路径：`VirtualMachine.deriveNestedVm`（`Vm.kt:1528`），与 `ProcessRuntime.kt:3908` 同一条。 */
    private fun derivedVms(n: Int): List<VirtualMachine> {
        val root = VirtualMachine(program())
        return (1..n).map { root.deriveNestedVm(StringHost(), VmConfig()) }
    }

    @Test
    fun `单台派生 VM 底座 = 313598 B 起，且不带 handle 表`() {
        val one = LiveSetCensus.nestedVmResidentBytes(derivedVms(1))
        // 读数原值落 XML（system-out）：报账时要能直接引用「真机口径单台多少字节」。
        println("[lane-p] perVmBytes=$one baseFloor=$baseFloorBytes")
        assertTrue(
            one >= baseFloorBytes,
            "单台底座小于算式下界：实际 $one < $baseFloorBytes（有字段没被数进去）",
        )
        assertTrue(
            one <= baseCeilingBytes,
            "单台底座超出上界：实际 $one > $baseCeilingBytes（新建 VM 不该带 varHandleTables）",
        )
    }

    @Test
    fun `池字节线性可加 —— 上界 = 池上限 × 单台`() {
        val one = LiveSetCensus.nestedVmResidentBytes(derivedVms(1))
        val two = LiveSetCensus.nestedVmResidentBytes(derivedVms(2))
        assertEquals(one * 2L, two, "两台不等于单台×2：读数里混了常数项或漏算共享")
        // 池的事实上界 = METHOD_STACK_LIMIT(100)（ProcessRuntime.kt:5028）：
        // 100 × 单台就是这一簇的整池上界，读出来给报告侧当判式用。
        val hundred = LiveSetCensus.nestedVmResidentBytes(derivedVms(100))
        println("[lane-p] pool100Bytes=$hundred (${hundred / 1024L} KiB)")
        assertEquals(one * 100L, hundred, "100 台不等于单台×100")
        assertTrue(
            hundred < 50L * 1024L * 1024L,
            "整池上界应远低于 50 MiB，实际 $hundred B（算式或池上限变了，回头核 METHOD_STACK_LIMIT）",
        )
        // 实测 31,361,000 B ≈ 29.9 MiB（纯底座 100 台）；每台再带 handle 表时
        // +functionCount×4（真语料 118,534 函数 = 474,136 B）⇒ 全池上界 ≈ 75 MiB。
        assertTrue(hundred > 25L * 1024L * 1024L, "100 台底座读数偏小，怀疑漏数字段：$hundred B")
    }

    @Test
    fun `哨兵 —— null 给 -1（没接线）、空池给 0（确实空）`() {
        assertEquals(-1L, LiveSetCensus.nestedVmResidentBytes(null), "null 必须给 -1")
        assertEquals(0L, LiveSetCensus.nestedVmResidentBytes(emptyList<Any>()), "空池必须给 0")
        assertEquals(0L, LiveSetCensus.nestedVmResidentBytes(listOf<Any?>(null)), "池内 null 元素按 0 计")
    }

    @Test
    fun `census 接线 —— 不传池给 -1，传池给现算值`() {
        val bare = LiveSetCensus.census(
            program = null,
            store = null,
            console = null,
            gfxPool = null,
            spritePool = null,
            fullScan = false,
        )
        assertEquals(-1L, bare.nestedVmResident, "缺省不传池必须是 -1，不能写成 0")

        val vms = derivedVms(2)
        val wired = LiveSetCensus.census(
            program = null,
            store = null,
            console = null,
            gfxPool = null,
            spritePool = null,
            fullScan = false,
            nestedVmPool = vms,
        )
        assertEquals(
            LiveSetCensus.nestedVmResidentBytes(vms),
            wired.nestedVmResident,
            "census 里的读数与现算面不一致",
        )
    }

    /**
     * 读数必须**进成文面**才算数：真机报告只搬 `renderReportLines()` 的文本，
     * 光有 `Census` 字段等于没报（这是 lane-p 交付时自己漏掉的最后一环）。
     */
    @Test
    fun `成文面 —— 派生池读写进代码缓存段尾`() {
        val vms = derivedVms(3)
        val wired = LiveSetCensus.census(
            program = null,
            store = null,
            console = null,
            gfxPool = null,
            spritePool = null,
            fullScan = false,
            nestedVmPool = vms,
        )
        val bytes = LiveSetCensus.nestedVmResidentBytes(vms)
        val expect = " 派生池 ${bytes / (1024L * 1024L)}MiB"
        val text = wired.renderReportLines().joinToString("\n")
        assertTrue(text.contains(expect), "成文面缺派生池读数（应当出现 `$expect`）：$text")
    }
}
