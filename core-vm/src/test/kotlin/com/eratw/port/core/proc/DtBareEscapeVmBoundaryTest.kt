package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RECON-STAB-A A2 的「顶层捕获」逃逸形态钉桩：DT 族（MapDataTableMethods）6 处分歧 C 裸异常
 * 映射点抛出的裸 JVM 异常，必须**原样穿出 VM 边界**（语句层 → ProcessRuntime.execute →
 * VirtualMachine.run 一路无折转），由壳层收口（GameHost `runtimeFaultAction` →
 * RUNTIME_FAULT → `handleRuntimeFault` 红字上屏 + 会话保留，stab-c A1 批 bb21e0f1a）。
 *
 * 原版行为链（两棵树逐行复读）：方法表包装无 try（`FunctionMethodTerm.cs:22`；skia :57）、
 * ERB 无通用 try 块（TRYC 家族只在自己语句里查 JumpToEndCatch：`FunctionIdentifier.cs:3284`、
 * `ErbLoader.cs:1341-1348`，verify-notes §13.3 已核）⇒ 裸 CLR 异常直穿 DoScript 顶层宽捕获
 * （`Process.cs:462-477`；skia :365）→ `handleException` 意外分支（`Process.cs:651-660`；
 * skia :693-701）：类型 + 消息 + 堆栈红字上屏、会话保留。**原版走意外通道、不是 CodeEE
 * 正常通道**——把抛面收敛成 CodeEE 反而偏离原版形态（RECON-STAB-A A2 的建议修法口径，
 * `specs/verify-notes.md` §13.2 分歧 C/G/J15 的已冻结登记同向）。
 *
 * 本文件钉的是 fn 层既有裸形态钉桩（MapDataTableMethodsTest / MapDataAuditTest /
 * DtColumnNamesTest）盖不到的一段：**语句执行到 VM 边界之间不存在把裸异常折成 CodeEE
 * 或改型的中间层**。谁在这里插入收敛/包装层（例如把抛面改成带位置 CodeEE），本用例红。
 */
class DtBareEscapeVmBoundaryTest {

    private fun parseErbLines(vararg lines: String): List<Statement> {
        // 赋值行解析按符号表查变量（CharaListDtLazyCellSetTest 同款预声明）。
        val p = LineParser(
            SymbolTable().declareVariable("L_DTNAME", OperandType.STR, 1),
        )
        return lines.mapIndexedNotNull { i, raw ->
            val text = raw.trimStart()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "bare-escape-test.erb", i + 1, text))
        }
    }

    private fun build(mainLines: List<String>): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("SYSTEM_TITLE", 0, isMethod = false, paramNames = emptyList()),
            parseErbLines(*mainLines.toTypedArray()),
            emptySet(),
            ExprCallTable(),
        )
        return c.build("SYSTEM_TITLE")
    }

    private fun newStore(): VariableStore = VariableStore().also {
        it.declareUserVar(DimParser.parse("#DIMS", "L_DTNAME", isPrivate = false))
    }

    private fun run(mainLines: List<String>, store: VariableStore): List<String> {
        val program = build(mainLines)
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i,
                    key = f.name.uppercase(),
                    isMethod = false,
                    returnsStr = false,
                    argIntCount = f.arity,
                    argStrCount = f.arity,
                )
            },
        )
        val out = StringBuilder()
        ProcessRuntime(
            program, store, table, VmConfig(maxStepsPerRun = 200_000L),
            printSink = { out.append(it).append('\n') },
        ).execute()
        return out.lines().filter { it.isNotBlank() }
    }

    /** 表 + 不可空整数列（空表上加列不抛，探针 B4）+ 带**值**的一行（行加显式赋 n，过 Rows.Add 边界）。 */
    private fun dtSetup() = listOf(
        "@SYSTEM_TITLE",
        "#DIMS L_DTNAME",
        "L_DTNAME = \"T\"",
        "DT_CREATE L_DTNAME",
        "DT_COLUMN_ADD L_DTNAME , \"n\" , \"int64\" , 0",
        "DT_ROW_ADD L_DTNAME , \"n\" , 7",
    )

    @Test
    fun `脚本位 DT_CELL_SET 写 DBNull 到不可空列的裸异常原样穿出 VM 边界`() {
        val store = newStore()
        val e = assertFailsWith<IllegalStateException> {
            run(dtSetup() + listOf("DT_CELL_SET L_DTNAME , 0 , \"n\""), store)
        }
        // 反向证明锚：原版链路无任何把裸异常转 CodeEE 的 catch（FunctionMethodTerm.cs:22 无 try、
        // ProcessRuntime.executeCore 只盖 CodeEE/ExprEvalException 位置章、Vm.run 只快照重抛）。
        // 收敛成带位置 CodeEE = 偏离原版意外通道形态（RECON-STAB-A A2 以报告为准的口径）。
        assertTrue(e !is CodeEE, "裸异常不许在语句层与 VM 边界之间折成 CodeEE")
        assertTrue(
            e.message!!.contains("NoNullAllowedException"),
            "抛面 message 应保留 .NET 异常形态标记，实际：${e.message}",
        )
    }
}
