package com.eratw.port.core.sem

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 读档语句的**静默**口径（2026-11-15 退役玩家面「未认领键」告知后的回归锁）。
 *
 * ## 原版语义
 *
 * `LOADDATA` 对「存档里有、当前游戏没声明」的键是**读完即丢、不告警**：
 * - `VariableData.LoadVariableBinary` 的 Int 分支（`VariableData.cs:1418-1421`）：
 *   `vToken == null` ⇒ `reader.ReadInt()`（源码注释「該当変数なし、or型不一致なら読み捨てる」）；
 * - Str 分支 `:1424-1426` 同理 `reader.ReadString()`；
 * - 数组/浮点分支 `:1430-1500` 同理 `ReadIntArray(null, true)` 一类；
 * - 全分支没有任何 `ParserMediator.Warn` / 控制台输出；`LOADGLOBAL`（`:1291` 同循环）同口径。
 *
 * ## 为什么退役玩家面告知
 *
 * 2026-11-15 玩家事故：261017 旧档含 `DLC/魔法DLC/魔法相关参数.ERH` 的 5 个
 * `#DIM SAVEDATA`（该文件里是**无条件声明**，与 DLC 开关无关），261111 上游把这 5 条注释成
 * `;#DIM` 退役（DLC 改由 `M_FLAG` 位承载）⇒ 新引擎读旧档时它们必然成为「未认领键」。
 * 移植侧 M1-2 的玩家面告知（`SaveLoadHandoff.restoreRenderer` + `SaveRestoreCopy` +
 * `StatementExecutor.printRestoreNotice`）会每次读档都喊这 5 个键，玩家据此认定
 * 「老存档加载不了」——原版从来不会说这句话。告知与渲染表已整体退役，本用例钉住：
 * **读档成功只改状态，控制台零输出**；源码面也不许再长出第二套上屏通道。
 */
class LoadStatementSilenceTest {

    private class Ops : SystemOps {
        var loadGlobalCalls = 0
        var loadDataCalls = 0

        override fun loadData(no: Int) {
            loadDataCalls++
        }

        override fun loadGlobal(): Boolean {
            loadGlobalCalls++
            return true
        }
    }

    private fun env(): Pair<FakeConsoleSink, ExecContext> {
        val store = com.eratw.port.core.vars.VariableStore()
        val console = FakeConsoleSink()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = console,
            systemOps = Ops(),
        )
        return console to ctx
    }

    @Test
    fun `LOADDATA 成功只结束本帧_控制台零输出`() {
        val (console, ctx) = env()
        val executor = StatementExecutor()

        val result = executor.execute(
            instr(FunctionCode.LOADDATA, ExpressionArgument(IntLiteral(0L))),
            ctx,
        )

        assertEquals(ExecResult.Quit(), result, "整栈丢弃语义不变（Process.cs:1860-1862）")
        assertEquals(1, (ctx.systemOps as Ops).loadDataCalls)
        assertFalse(
            console.events.any { it.startsWith("printLine(") || it.startsWith("printError(") },
            "原版读档不告警；控制台零输出：${console.events}",
        )
    }

    @Test
    fun `LOADGLOBAL 成功写 RESULT_控制台零输出`() {
        val (console, ctx) = env()
        val executor = StatementExecutor()

        executor.execute(instr(FunctionCode.LOADGLOBAL), ctx)

        assertEquals(1, (ctx.systemOps as Ops).loadGlobalCalls)
        assertEquals(
            1L,
            ctx.variables.readInt("RESULT", longArrayOf(0)),
            "LOADGLOBAL 的 RESULT 语义不变（FunctionIdentifier.cs:1815-1834）",
        )
        assertFalse(
            console.events.any { it.startsWith("printLine(") || it.startsWith("printError(") },
            "LOADGLOBAL 同口径零输出：${console.events}",
        )
    }

    // ------------------------------------------------------------------
    // 源码哨兵：上屏通道整体退役，不许在别的层长出第二套
    // ------------------------------------------------------------------

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/$relative"),
            File("core-vm/src/main/kotlin/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `语句层与交接层不再有玩家面上屏通道`() {
        val executor = sourceOf("com/eratw/port/core/sem/StatementExecutor.kt")
        assertFalse(executor.contains("printRestoreNotice"), "语句层不许再有未认领键上屏函数")
        assertFalse(executor.contains("takeRestoreNotice"), "语句层不许再取上屏文案")

        val handoff = sourceOf("com/eratw/port/core/host/SaveLoadHandoff.kt")
        assertFalse(handoff.contains("restoreRenderer"), "交接层不许再有渲染器注入位")
        assertFalse(handoff.contains("takeRestoreNotice"), "交接层不许再有上屏取件通道")
        assertFalse(handoff.contains("recordRestore("), "交接层不许再有读档报告记账入口")
    }
}
