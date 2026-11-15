package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.fn.EngineSurface
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.stmt.VoidArgument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 启动期命令面审计（ENGC-3 B4）。
 *
 * 钉三件事：
 * 1. **合法集的数据面**：snake 覆盖全部 `FunctionCode`（scan 短路因此可证等价）、
 *    EM_EE / V24 的拒绝面与生成表逐条一致、方法表项与结构码在三档都合法；
 * 2. **扫描行为**：命中按语句池序给出代码名 + 行号、snake 短路、消息折叠；
 * 3. **不物化**：惰性产物（`loadMapped`）上扫描，`materializedStatementCount` 前后为 0
 *    —— 这是「审计不许把 171.7 万条语句拖进堆」的判据。
 */
class CompatCommandAuditTest {

    // ==================================================================
    // 合法集（数据面）
    // ==================================================================

    @Test
    fun `声明全覆盖的档 合法集覆盖全部 FunctionCode`() {
        // 短路依据 = 数据事实（input yaml `full_statement_coverage`），不再认家族名：
        // 凡声明为真的档都必须真的覆盖全部枚举，否则 scan 的提前返回不再等价。
        val covering = EngineProfiles.TARGETS.filter { it.fullStatementCoverage }
        assertTrue(covering.isNotEmpty(), "没有任何档声明全覆盖 ⇒ 本用例空转、scan 短路失去依据")
        for (t in covering) {
            val allowed = CompatCommandAudit.allowedNames(t.family)
            val missing = FunctionCode.values().map { it.name }.filter { it !in allowed }
            assertTrue(missing.isEmpty(), "${t.profileId} 声明全覆盖却缺这些枚举 ⇒ scan 短路不再等价：$missing")
        }
    }

    @Test
    fun `缺省档必须声明语句面全覆盖`() {
        // I1 护闸：无印章 / 无 flag 的出包产物都落缺省档，它若没声明全覆盖，
        // 启动期就要白扫一趟 171.7 万条记录头（PLAN §B4 的短路成本判据）。
        val default = EngineProfiles.DEFAULT_TARGET
        assertEquals(EngineProfileData.DEFAULT_TARGET_PROFILE_ID, default.profileId)
        assertTrue(
            default.fullStatementCoverage,
            "缺省档 '${default.profileId}' 未声明 full_statement_coverage ⇒ 无印章启动多扫一趟",
        )
    }

    @Test
    fun `EM_EE 与 V24 的拒绝面与生成表逐条一致`() {
        // 名单本体归 EngineProfileData（B1 生成物）；这里只钉「审计的并集口径」不把
        // 方法表/端口扩展漏进拒绝面 —— 名单若变动，这条用例是第二道闸。
        assertEquals(
            setOf(
                "DISABLE_INPUT_MACRO", "ENABLE_INPUT_MACRO", "HTML_PRINTC", "HTML_PRINTLC",
                "SEQUENCEINPUT", "SETIMAGELAYERL", "TEXT_BGC_OFF", "TEXT_BGC_ON",
                "TINPUTNF", "TINPUTSNF", "TRYCALLSTR", "TRYCCALLSTR",
            ),
            rejectedCodes(EngineFamily.EM_EE_1824_V22),
        )
        assertEquals(
            setOf(
                "BINPUT", "BINPUTS", "CALLSHARP", "CLEARBGIMAGE", "DISABLE_INPUT_MACRO",
                "DT_COLUMN_OPTIONS", "ENABLE_INPUT_MACRO", "FORCE_BEGIN", "FORCE_QUIT",
                "FORCE_QUIT_AND_RESTART", "HTML_PRINTC", "HTML_PRINTLC",
                "HTML_PRINT_ISLAND", "HTML_PRINT_ISLAND_CLEAR", "INPUTANY", "ONEBINPUT",
                "ONEBINPUTS", "PLAYBGM", "PLAYSOUND", "PRINTFORMN", "PRINTFORMSN", "PRINTN",
                "PRINTSN", "PRINTVN", "QUIT_AND_RESTART", "REMOVEBGIMAGE", "SEQUENCEINPUT",
                "SETBGIMAGE", "SETBGMVOLUME", "SETIMAGELAYERL", "SETSOUNDVOLUME", "SKIPLOG",
                "STOPBGM", "STOPSOUND", "TEXT_BGC_OFF", "TEXT_BGC_ON", "TINPUTNF",
                "TINPUTSNF", "TOOLTIP_CUSTOM", "TOOLTIP_FORMAT", "TOOLTIP_IMG",
                "TOOLTIP_SETFONT", "TOOLTIP_SETFONTSIZE", "TRYCALLF", "TRYCALLFORMF",
                "TRYCALLSTR", "TRYCCALLSTR", "UPDATECHECK", "VARI", "VARS",
            ),
            rejectedCodes(EngineFamily.V24_1824),
        )
    }

    @Test
    fun `端口扩展与结构码在三档都合法`() {
        for (family in EngineFamily.values()) {
            val allowed = CompatCommandAudit.allowedNames(family)
            val missingPort = EngineProfileData.PORT_EXTENSIONS.filter { it !in allowed }
            assertTrue(missingPort.isEmpty(), "$family 漏了端口扩展：$missingPort")
            for (structural in listOf("__NULL__", "SET", "METHOD")) {
                assertTrue(structural in allowed, "$family 漏了结构码 $structural")
            }
        }
    }

    @Test
    fun `方法表项在语句首按档位区分（SEQUENCEINPUT 是 snake 方法不是命令）`() {
        assertTrue("SEQUENCEINPUT" in EngineProfileData.functionsOf(EngineFamily.SNAKE_4981))
        assertTrue("SEQUENCEINPUT" !in EngineProfileData.commandsOf(EngineFamily.SNAKE_4981))
        assertTrue(
            "SEQUENCEINPUT" in CompatCommandAudit.allowedNames(EngineFamily.SNAKE_4981),
            "snake 的函数表项必须能出现在语句首（FunctionIdentifier.cs:4135-4142 把方法表并进命令字典）",
        )
        assertTrue("SEQUENCEINPUT" !in CompatCommandAudit.allowedNames(EngineFamily.EM_EE_1824_V22))
        assertTrue("SEQUENCEINPUT" !in CompatCommandAudit.allowedNames(EngineFamily.V24_1824))
    }

    // ==================================================================
    // 扫描行为
    // ==================================================================

    @Test
    fun `V24 档按语句池序给出目标外命令的代码名与行号`() {
        val program = programWith(
            stmt(FunctionCode.PRINTL, line = 10),
            stmt(FunctionCode.SETBGIMAGE, line = 120),
            stmt(FunctionCode.CLEARLINE, line = 130),
            stmt(FunctionCode.PLAYBGM, line = 7),
            stmt(FunctionCode.SETIMAGELAYERL, line = 55),
        )
        assertEquals(
            listOf(
                CompatCommandAudit.Hit("SETBGIMAGE", 120),
                CompatCommandAudit.Hit("PLAYBGM", 7),
                CompatCommandAudit.Hit("SETIMAGELAYERL", 55),
            ),
            CompatCommandAudit.scan(program, EngineFamily.V24_1824),
        )
    }

    @Test
    fun `语句首的方法名折叠为结构码 METHOD 不参与命令审计`() {
        // PLAN §B4 的跨面名 SETANIMETIMER：snake 命令表里有它、函数面没有（B3 门控面）。
        // 语句层它被解析成 MethodStmt，记录的 code 是 FunctionCode.METHOD（ErbcWriter.kt:1201
        // 写的就是 s.code.name）⇒ 命令审计三档都看不见方法名，函数面归 EngineSurface，
        // 两层各管各的，不会把「方法在命令表里」误读成「命令面合法」。
        val program = programWith(MethodStmt("SETANIMETIMER", VoidArgument(), 5, "SETANIMETIMER 100"))
        for (family in EngineFamily.values()) {
            assertEquals(emptyList(), CompatCommandAudit.scan(program, family), "$family")
        }
    }

    @Test
    fun `V24 档放过公认命令与结构码`() {
        val program = programWith(
            stmt(FunctionCode.PRINTL, line = 1),
            stmt(FunctionCode.CLEARLINE, line = 2),
            stmt(FunctionCode.IF, line = 3),
        )
        assertEquals(emptyList(), CompatCommandAudit.scan(program, EngineFamily.V24_1824))
    }

    @Test
    fun `缺省档对同一产物短路为空（依据是数据事实不是家族名）`() {
        assertTrue(EngineProfiles.DEFAULT_TARGET.fullStatementCoverage, "前置：缺省档声明全覆盖")
        val program = programWith(stmt(FunctionCode.SETBGIMAGE, line = 120))
        assertEquals(
            emptyList(),
            CompatCommandAudit.scan(program, EngineProfiles.DEFAULT_TARGET.family),
            "缺省档的短路必须等价于「合法集覆盖全部枚举」",
        )
    }

    @Test
    fun `scan 的短路只认 fullStatementCoverage 数据事实`() {
        // 全枚举程序：任一档真扫时，未声明全覆盖的档必有命中；声明全覆盖的档恒空。
        val all = programWith(
            *FunctionCode.values().map { stmt(it, line = it.ordinal + 1) }.toTypedArray(),
        )
        for (t in EngineProfiles.TARGETS) {
            val hits = CompatCommandAudit.scan(all, t.family)
            if (t.fullStatementCoverage) {
                assertEquals(emptyList(), hits, "${t.profileId} 声明全覆盖 ⇒ 全枚举程序必须零命中")
            } else {
                assertTrue(
                    hits.isNotEmpty(),
                    "${t.profileId} 未声明全覆盖却对全枚举程序零命中：声明与实测不一致" +
                        "（要么补 full_statement_coverage，要么查合法集）",
                )
            }
        }
    }

    @Test
    fun `消息最多 8 条明细 其余折叠为计数`() {
        val hits = (1..12).map { CompatCommandAudit.Hit("SETBGIMAGE", it) }
        val message = CompatProfileMismatchException(EngineFamily.V24_1824, hits).message!!
        assertTrue("共 12 处" in message, message)
        assertEquals(8, Regex("第 \\d+ 行").findAll(message).count(), message)
        assertTrue("另有 4 处" in message, message)
        assertTrue("v24 1824+v24（v24_1824）" in message, "消息必须点出目标档：$message")
    }

    // ==================================================================
    // 惰性产物：扫描不物化
    // ==================================================================

    @Test
    fun `惰性池扫描不物化语句`(@TempDir dir: Path) {
        val program = programWith(
            stmt(FunctionCode.PRINTL, line = 1),
            stmt(FunctionCode.SETBGIMAGE, line = 42),
        )
        val path = dir.resolve("program.erbc")
        Files.write(path, ErbcWriter.encode(program))
        val loaded = ProgramLoader.loadMapped(path, flatStatements = false)
        try {
            assertTrue(loaded.holdsLazyStatementPool, "前置：本用例钉的就是惰性装载路")
            assertEquals(0, loaded.materializedStatementCount, "前置：装载期零物化")
            assertEquals(
                listOf(CompatCommandAudit.Hit("SETBGIMAGE", 42)),
                CompatCommandAudit.scan(loaded, EngineFamily.V24_1824),
            )
            assertEquals(0, loaded.materializedStatementCount, "扫描必须只读记录头，不许物化语句")
        } finally {
            loaded.releaseMmap()
        }
    }

    // ==================================================================
    // GameSession 接线
    // ==================================================================

    @Test
    fun `显式目标档装配期硬拒 无门控不扫描`(@TempDir dir: Path) {
        val program = compiled(texts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN", "SETBGIMAGE \"bg\""))
        val metadata = metadataOf()

        val e = assertFailsWith<CompatProfileMismatchException> {
            GameSession(
                gameRoot = dir,
                program = program,
                metadata = metadata,
                consoleWidth = 199,
                randSeed = 1L,
                engineSurface = EngineSurface(EngineFamily.V24_1824),
            )
        }
        assertEquals(EngineFamily.V24_1824, e.family)
        assertEquals(listOf(CompatCommandAudit.Hit("SETBGIMAGE", 3)), e.hits)
        assertTrue("SETBGIMAGE" in e.message.orEmpty() && "第 3 行" in e.message.orEmpty())

        // 无门控（缺省 engineSurface = null）⇒ 旧路径不扫描，同一产物照常装配。
        GameSession(
            gameRoot = dir,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 1L,
        )
    }

    // ==================================================================
    // 真实产物：预算读数（ERATW_ERBC 动态门）
    // ==================================================================

    /**
     * PLAN §B4 的成本判据：全量语料的一趟记录头扫描要远低于 200 ms（超过则回落
     * ENGC-3b 的构建期记账）。冷/热各扫一次；同时把「4.981 语料在 V24 档的命中面」
     * 打出来作交付读数。门控口径同 `ProgramMmapReleaseTest` 的真实产物用例。
     */
    @Test
    fun `真实产物：命令面扫描耗时与命中面（ERATW_ERBC 动态门）`() {
        val path = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        org.junit.jupiter.api.Assumptions.assumeTrue(
            path != null && Files.isReadable(path),
            "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例",
        )
        val loaded = ProgramLoader.loadMapped(path!!, flatStatements = false)
        try {
            assertTrue(loaded.holdsLazyStatementPool, "全量产物必须走惰性档")
            assertEquals(0, loaded.materializedStatementCount, "装载期零物化")
            val coldStart = System.nanoTime()
            val hits = CompatCommandAudit.scan(loaded, EngineFamily.V24_1824)
            val coldMs = (System.nanoTime() - coldStart) / 1_000_000
            val hotStart = System.nanoTime()
            val again = CompatCommandAudit.scan(loaded, EngineFamily.V24_1824)
            val hotMs = (System.nanoTime() - hotStart) / 1_000_000
            assertEquals(hits, again, "同一产物两次扫描必须逐条相同")
            assertEquals(0, loaded.materializedStatementCount, "扫描必须只读记录头，不许物化语句")
            println(
                "ENGC-3 B4 扫描读数：cold=$coldMs ms hot=$hotMs ms " +
                    "hits=${hits.size} 条 / ${hits.map { it.command }.toSet().size} 种 " +
                    hits.map { it.command }.toSet().sorted(),
            )
            assertTrue(coldMs < 1_000, "扫描 $coldMs ms 超预算（PLAN §B4：>200 ms 要回落 ENGC-3b）")
        } finally {
            loaded.releaseMmap()
        }
    }

    // ==================================================================
    // 样例
    // ==================================================================

    private fun rejectedCodes(family: EngineFamily): Set<String> {
        val allowed = CompatCommandAudit.allowedNames(family)
        return FunctionCode.values().map { it.name }.filter { it !in allowed }.toSet()
    }

    private fun stmt(code: FunctionCode, line: Int): Statement =
        InstructionStmt(code = code, args = VoidArgument(), line = line, raw = code.name)

    private fun programWith(vararg stmts: Statement): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        var pc = 0
        for (s in stmts) {
            val index = b.statement(s)
            b.emit(Opcode.PRINT, index, 0, SlotAddr.NONE, pc++)
        }
        b.emit(Opcode._HALT, 0, 0, 0, pc)
        return b.build(0)
    }

    private fun compiled(texts: List<String>): Program {
        val compiler = ErbProgramCompiler()
        val stmts = texts.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }
        val fb: FunctionBytecode = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), stmts, setOf("SYSTEM_TITLE"))
        check(fb.index == 0)
        return compiler.build("SYSTEM_TITLE")
    }

    private fun metadataOf(): BootMetadata = BootMetadata(
        functions = listOf(
            FuncMeta(
                index = 0, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                defIndex = 0, position = "TEST.ERB:1",
            ),
        ),
    )
}
