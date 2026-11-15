package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.erbcc.cmdCompileModule
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.CallSentinels
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RT2 G2 波（P1-5 余量二）：erbcc 零基线模块编译档的定向判据。
 *
 * 锁四件事：① 最小 ERB 前端把 `@` 头 / `#FUNCTION` / 形参切成结构化规格；
 * ② `compile` 用真 `ErbProgramCompiler(allowSuperCalls = true)` 产出零基线 Program
 * + 稠密 0 基 FuncMeta + 真签名指纹；③ `CALL SUPER` 编成 super 哨兵（档位真开的证据，
 * 不是「不抛异常」就算过）；④ `write/read` 与 `compile-module` CLI 走独立产线落 `.erbm`。
 *
 * 标准 `CorpusCompile` / `ErbcArtifacts` / `BootMetaWriter` / `cmdCompile` 零改动，
 * 由 diff 自证出厂 no-op（不在此处重复断言）。
 */
class ErbModuleCompilerTest {

    /** `SIG1|I,-,r,0|ret:I`：读侧 `BaseSignatureCatalog.decode` 认可的五要素规范串。 */
    private val canonical = "SIG1|I,-,r,0|ret:I"

    private fun tempDir(): java.nio.file.Path =
        Files.createTempDirectory("erbcc-module-test").also { it.toFile().deleteOnExit() }

    // ------------------------------------------------------------------
    // ① 最小 ERB 前端
    // ------------------------------------------------------------------

    @Test
    fun `specFromErb切出函数头形参与方法位`() {
        val spec = ErbModuleCompiler.specFromErb(
            packageId = "mod.test",
            fileName = "MOD.ERB",
            lines = listOf(
                "",
                "#FUNCTION",
                "@TARGET(ARG, ARGS\$)",
                "  CALL SUPER(1)",
                "  RETURN 0",
                "@OTHER",
                "RETURN 1",
            ),
        )
        assertEquals("mod.test", spec.packageId)
        assertEquals("MOD.ERB", spec.fileName)
        assertEquals(2, spec.functions.size)
        val first = spec.functions[0]
        assertEquals("TARGET", first.name)
        assertEquals(2, first.arity)
        assertEquals(listOf("ARG", "ARGS\$"), first.paramNames)
        assertTrue(first.isMethod)
        assertEquals(false, first.returnsStr)
        assertEquals(listOf("CALL SUPER(1)", "RETURN 0"), first.body)
        assertEquals("OTHER", spec.functions[1].name)
        assertEquals(0, spec.functions[1].arity)
    }

    @Test
    fun `第一个at头之前出现代码行整包拒`() {
        assertFailsWith<IllegalArgumentException> {
            ErbModuleCompiler.specFromErb("m", "M.ERB", listOf("RETURN 0"))
        }
    }

    @Test
    fun `没有at函数整包拒`() {
        assertFailsWith<IllegalArgumentException> {
            ErbModuleCompiler.specFromErb("m", "M.ERB", listOf("#FUNCTION", ""))
        }
    }

    // ------------------------------------------------------------------
    // ② 零基线编译产物
    // ------------------------------------------------------------------

    private fun spec(
        body: List<String> = listOf("RETURN 0"),
        canonical: String? = null,
    ) = ErbModuleCompiler.ModuleSpec(
        packageId = "mod.test",
        fileName = "MOD.ERB",
        functions = listOf(
            ErbModuleCompiler.FunctionSpec(
                name = "TARGET",
                body = body,
                signatureCanonical = canonical,
            ),
        ),
    )

    @Test
    fun `compile产出零基线Program与稠密FuncMeta`() {
        val cm = ErbModuleCompiler.compile(spec())
        assertEquals("mod.test", cm.packageId)
        val p = cm.program
        assertEquals(0, p.codeBase)
        assertEquals(0, p.statementBase)
        assertEquals(0, p.stringBase)
        assertEquals(0, p.longBase)
        assertEquals(0, p.exprBase)
        assertEquals(0, p.functionBase)
        assertEquals(p.functions.size, cm.functions.size)
        for (i in cm.functions.indices) {
            assertEquals(i, cm.functions[i].index, "meta[$i].index 稠密 0 基")
            assertTrue(cm.functions[i].key.isNotBlank())
        }
        assertEquals("TARGET", cm.functions[0].key)
    }

    @Test
    fun `签名规范串产出真指纹且坏串整包拒`() {
        assertTrue(BaseSignatureCatalog.decode(canonical) != null, "夹具规范串本身必须被读侧认可")
        val cm = ErbModuleCompiler.compile(spec(canonical = canonical))
        assertEquals(BaseSignatureCatalog.fingerprint(canonical), cm.signatures["TARGET"])
        assertFailsWith<IllegalArgumentException> {
            ErbModuleCompiler.compile(spec(canonical = "not-a-signature"))
        }
    }

    @Test
    fun `重复函数名与空函数名整包拒`() {
        val dup = ErbModuleCompiler.ModuleSpec(
            "m", "M.ERB",
            listOf(
                ErbModuleCompiler.FunctionSpec("TARGET", listOf("RETURN 0")),
                ErbModuleCompiler.FunctionSpec("TARGET", listOf("RETURN 1")),
            ),
        )
        assertFailsWith<IllegalArgumentException> { ErbModuleCompiler.compile(dup) }
        val blank = ErbModuleCompiler.ModuleSpec(
            "m", "M.ERB", listOf(ErbModuleCompiler.FunctionSpec("", listOf("RETURN 0"))),
        )
        assertFailsWith<IllegalArgumentException> { ErbModuleCompiler.compile(blank) }
    }

    // ------------------------------------------------------------------
    // ③ allowSuperCalls 档位（super 哨兵）
    // ------------------------------------------------------------------

    @Test
    fun `CALL SUPER编成super哨兵_证明档位真开`() {
        val cm = ErbModuleCompiler.compile(spec(body = listOf("CALL SUPER(1)", "RETURN 0")))
        val p = cm.program
        var found = false
        for (pc in 0 until p.instructionCount) {
            if (p.op(pc) == Opcode._CALL && p.a(pc) == CallSentinels.CALL_SUPER_SENTINEL) {
                found = true
                break
            }
        }
        assertTrue(
            found,
            "allowSuperCalls=true 的模块编译档必须把 CALL SUPER 编成 " +
                "CALL_SUPER_SENTINEL（同 KojoOverrideContractTest 口径）",
        )
    }

    // ------------------------------------------------------------------
    // ④ 容器落盘与 CLI
    // ------------------------------------------------------------------

    @Test
    fun `write与read往返_容器带Mod编译档位`() {
        val target = tempDir().resolve("out.erbm")
        val bytes = ErbModuleCompiler.write(target, spec(canonical = canonical))
        assertTrue(Files.size(target) == bytes.size.toLong())
        val module = ErbModuleCompiler.read(target)
        assertEquals("mod.test", module.packageId)
        assertTrue(module.allowSuperCalls)
        assertEquals(module.program.functions.size, module.functions.size)
        assertEquals(BaseSignatureCatalog.fingerprint(canonical), module.signatures["TARGET"])
        assertEquals("mod.test", module.toCompiledModule().packageId)
    }

    @Test
    fun `compile-module子命令落独立erbm且不碰标准产物目录`() {
        val dir = tempDir()
        val erb = dir.resolve("MOD.ERB")
        Files.write(
            erb,
            listOf("@TARGET", "RETURN 0").joinToString("\n").toByteArray(Charsets.UTF_8),
        )
        val out = dir.resolve("nested/mod.erbm")
        val code = cmdCompileModule(
            listOf(
                "--package", "cli.mod",
                "--out", out.toString(),
                "--signature", "TARGET=$canonical",
                erb.toString(),
            ),
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(out))
        val module = ErbModuleCompiler.read(out)
        assertEquals("cli.mod", module.packageId)
        assertTrue(module.allowSuperCalls)
        assertEquals("TARGET", module.functions[0].key)
        assertEquals(BaseSignatureCatalog.fingerprint(canonical), module.signatures["TARGET"])
        // 独立产线：不产 erbc.v1/ 目录、不产 program.meta、不产 manifest.txt。
        assertTrue(!Files.exists(dir.resolve("erbc.v1")))
        assertTrue(!Files.exists(dir.resolve("program.meta")))
        assertTrue(!Files.exists(dir.resolve("manifest.txt")))
    }

    // CLI 缺参数走 `Main.err`（`exitProcess(2)`），会终止测试 JVM ⇒ 不在单测里触发；
    // 参数解析的正路已由上一条 `compile-module` 用例覆盖。

    @Test
    fun `容器可由装配器直接消费_零基线形状自证`() {
        val cm = ErbModuleCompiler.compile(spec())
        val bytes = com.eratw.port.core.mod.ErbModuleContainer.encode(cm, allowSuperCalls = true)
        val decoded = com.eratw.port.core.mod.ErbModuleContainer.decode(bytes)
        assertNotNull(decoded.toCompiledModule())
        assertEquals(cm.program.functions.size, decoded.program.functions.size)
    }
}
