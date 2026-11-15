package com.eratw.port.core.mod

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PrivateDimDecl
import com.eratw.port.modapi.RuntimeScheduler
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 波次 L7（PRIVDIM）：私有 `#DIM` 消费线的**真引擎纵向用例**。
 *
 * 上游缺口（`DELIVERY-RT2-LEAD-AUDIT.md` 序 04-⑤）：`ModChainRuntimePlan.privateDims` →
 * `GameSession.allPrivateDims` 单边已有 e2e，但与 **Kotlin Mod 声明事务面**
 * （`ModManifest` → `ModDeclarationAdapter` → `DeclarationRegistry.freeze` →
 * `ModRuntimeHost`）汇合后的语义没有实证。本文件用一条纵向链把它们接起来：
 *
 * ① Kotlin Mod 的 `ModManifest.privateDims` 经真适配器转 `ModuleDescriptor`，与
 *    ERB 模块的 descriptor 一起进**同一个** `ModChainAssembler.assemble`；
 * ② 装配器第二步的冻结快照（`AssembledChain.snapshot`）同时含两个包的私有声明，
 *    `ModRuntimeHost` 用**同一份快照**装配 Kotlin provider 的 `ModContext`；
 * ③ `ModChainRuntimePlan.of(chain)` 把两份声明聚合进 `plan.privateDims`，
 *    `GameSession` 装配第 5 步经**同一条注册链**（`store.declareUserVar`）逐条注册；
 * ④ 真 VM 执行两个函数，各自读写自己函数键下的同名私有变量（维度 2 vs 3），
 *    证明不串号、不越界；声明事务的拒绝面（同包同函数重名）照常 fail-closed。
 *
 * 夹具纪律：base 走真 [ErbProgramCompiler]，模块走真 [PatchCompiler] +
 * [PatchImportTranscoder]（零基线），descriptor 走真 [ManifestCodec] 编解码，装配走真
 * [ModChainAssembler]，会话走真 [GameSession]，宿主走真 [ModRuntimeHost]；不手搓假字节。
 */
class PrivateDimVerticalTest {

    // ------------------------------------------------------------------
    // 夹具：真编译 → 真转码 → 真装配 → 真会话
    // ------------------------------------------------------------------

    /** 两个包同名私有变量：`LOC_PRIV`。跨包按函数键隔离是声明事务的既有口径。 */
    private class Fixture(
        val chain: AssembledChain,
        val plan: ModChainRuntimePlan,
        val session: GameSession,
    )

    private val baseSource = """
        @ENTRY
        RETURN 0
    """.trimIndent()

    /**
     * ERB 模块（普通 ERB Mod 资产）：私有数组 `LOC_PRIV, 2`，写满两个合法下标再回读。
     * 声明来源 = [PatchCompiler] 预扫 `#DIM` → 转码 descriptor（与生产同路）。
     */
    private val erbModuleSource = """
        @B_LOCAL
        #DIM LOC_PRIV, 2
        LOC_PRIV:0 = 7
        LOC_PRIV:1 = 11
        PRINTFORML B={LOC_PRIV:0},{LOC_PRIV:1}
        RETURN 0
    """.trimIndent()

    /**
     * Kotlin Mod 附带的模块资产：同名私有数组但维度是 3，写 `:0` 与 `:2`（末位合法）。
     * 若会话把两个包的声明串成一份（共享数组）或把维度串错，`:2` 会越界。
     */
    private val kotModuleSource = """
        @KOT_ENTRY
        #DIM LOC_PRIV, 3
        LOC_PRIV:0 = 21
        LOC_PRIV:2 = 33
        PRINTFORML K={LOC_PRIV:0},{LOC_PRIV:2}
        RETURN 0
    """.trimIndent()

    /**
     * Kotlin Mod 的声明面：包 `eratw.kotmod` 的私有 `#DIM` 只在 manifest 里声明，
     * 经真适配器进声明事务——它就是「Kotlin Mod 声明事务面」这一侧的权威输入。
     */
    private val kotManifest = ModManifest(
        packageId = "eratw.kotmod",
        privateDims = mapOf(
            "KOT_ENTRY" to listOf(PrivateDimDecl("LOC_PRIV", DimType.INT, listOf(3))),
        ),
    )

    /** 单文件按 `@` 函数头切组：与 PatchImportTranscoderTest 的 mini 装置同口径。 */
    private fun collectGroups(
        rel: String,
        text: String,
        parser: LineParser,
    ): List<Pair<String, List<Statement>>> {
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(text.toByteArray(Charsets.UTF_8)),
            rel,
            config = LexerConfig(),
        )
        val groups = ArrayList<Pair<String, List<Statement>>>()
        var cur: String? = null
        val body = ArrayList<Statement>()
        var line = reader.readLogicalLine()
        while (line != null) {
            val stmt = parser.parseLogicalLine(line)
            when {
                stmt == null -> Unit
                stmt is FuncLabelStmt -> {
                    cur?.let { groups += it to body.toList() }
                    body.clear()
                    cur = stmt.name.uppercase()
                    body.add(stmt)
                }
                stmt is SharpStmt -> Unit
                cur != null -> body.add(stmt)
            }
            line = reader.readLogicalLine()
        }
        cur?.let { groups += it to body.toList() }
        return groups
    }

    /** base：只有 `@ENTRY`，函数表与侧车逐下标配对。 */
    private fun compileBase(): Triple<Program, List<FuncMeta>, PatchCompiler.BaseCounts> {
        val parser = LineParser(SymbolTable(), lexerConfig = LexerConfig())
        val groups = collectGroups("base.ERB", baseSource, parser)
        val compiler = ErbProgramCompiler(callDefaults = emptyMap())
        for ((name, stmts) in groups) {
            val label = stmts.filterIsInstance<FuncLabelStmt>().first()
            compiler.compileFunction(
                FuncSig(name, label.paramNames.size, false, false, label.paramNames),
                stmts,
                setOf("ENTRY"),
                ExprCallTable(),
            )
        }
        val program = compiler.build(groups.first().first)
        val metas = program.functions.mapIndexed { i, f ->
            FuncMeta(index = i, key = f.name, name = f.name, defIndex = i, position = "base.ERB:${i + 1}")
        }
        val counts = PatchCompiler.BaseCounts(
            instructionCount = program.instructionCount,
            functionCount = program.functions.size,
            statementCount = program.statementCount,
            stringCount = program.stringCount,
            longCount = program.constantCount,
            exprCount = program.exprCount,
        )
        return Triple(program, metas, counts)
    }

    /** 模块源走真 PatchCompiler（预扫私有 `#DIM`）→ 真转码器（零基线 + descriptor）。 */
    private fun compileModule(
        packageId: String,
        entry: String,
        source: String,
        baseMetas: List<FuncMeta>,
        counts: PatchCompiler.BaseCounts,
    ): PatchImportTranscoder.Transcoded {
        val result = PatchCompiler(
            BootMetadata(functions = baseMetas),
            counts,
            SymbolTable(),
        ).compile(
            listOf(PatchCompiler.SourceFile("mods/$packageId.ERB", source)),
            entry,
        )
        return PatchImportTranscoder.transcode(
            result,
            counts,
            baseMetas.associate { it.index to it.key },
            packageId,
        )
    }

    private fun fixture(dir: Path): Fixture {
        val (program, metas, counts) = compileBase()
        val erb = compileModule("eratw.erbmod", "B_LOCAL", erbModuleSource, metas, counts)
        val kotModule = compileModule("eratw.kotmod", "KOT_ENTRY", kotModuleSource, metas, counts)
        val kotDescriptor = ModDeclarationAdapter.toDescriptor(kotManifest)

        // 两包进同一个装配器、同一份声明事务：ERB 模块的 descriptor 来自真转码器，
        // Kotlin Mod 的 descriptor 来自真 manifest 适配器。sources 序 = 声明的链接序。
        val sources = listOf(
            ManifestSource("eratw.erbmod.manifest", ManifestCodec.encode(erb.descriptor)),
            ManifestSource("eratw.kotmod.manifest", ManifestCodec.encode(kotDescriptor)),
        )
        val chain = ModChainAssembler.assemble(
            sources = sources,
            compiledModules = listOf(erb.module.toCompiledModule(), kotModule.module.toCompiledModule()),
            base = program,
            baseMetas = metas,
        )
        val plan = assertNotNull(ModChainRuntimePlan.of(chain), "非旁路装配必须产出运行期计划")
        val session = GameSession(
            gameRoot = dir,
            program = program,
            metadata = BootMetadata(functions = metas),
            modChain = plan,
        )
        return Fixture(chain, plan, session)
    }

    // ------------------------------------------------------------------
    // 判据 1：同一份冻结声明 + 同一条会话注册链
    // ------------------------------------------------------------------

    @Test
    fun `mod链私有DIM与KotlinMod声明事务在同一快照与同一条注册链汇合`(@TempDir dir: Path) {
        val f = fixture(dir)
        try {
            // ① 声明事务：两个包都在同一份冻结快照里，私有声明按包 + 函数键各归各。
            val snapshot = assertNotNull(f.chain.snapshot, "非旁路装配必须带冻结声明快照")
            assertTrue("eratw.erbmod" in snapshot.packageIds, "ERB 模块包必须进声明事务")
            assertTrue("eratw.kotmod" in snapshot.packageIds, "Kotlin Mod 包必须进声明事务")
            val erbDecl = snapshot.privateDimsOf("eratw.erbmod", "b_local")
            val kotDecl = snapshot.privateDimsOf("eratw.kotmod", "kot_entry")
            assertEquals(listOf("LOC_PRIV"), erbDecl.map { it.name }, "ERB 模块的私有声明")
            assertEquals(listOf(2), erbDecl.single().dims.toList())
            assertEquals(listOf("LOC_PRIV"), kotDecl.map { it.name }, "Kotlin Mod 的私有声明")
            assertEquals(listOf(3), kotDecl.single().dims.toList())
            assertTrue(kotDecl.single().isPrivate, "Kotlin Mod 的 private-dims 必须带 isPrivate")
            assertFalse(kotDecl.single().isGlobal, "private-dims 不得落成全局变量")
            assertFalse(kotDecl.single().isSavedata, "声明式私有变量不进存档")

            // ② 运行期计划：两份 descriptor 的声明聚合进同一张表，函数键不丢、不串。
            assertEquals(
                setOf("B_LOCAL", "KOT_ENTRY"),
                f.plan.privateDims.map { it.functionKey }.toSet(),
                "plan.privateDims 必须是两包声明的并集：${f.plan.privateDims}",
            )
            assertEquals(
                mapOf("B_LOCAL/LOC_PRIV" to 2, "KOT_ENTRY/LOC_PRIV" to 3),
                f.plan.privateDims.associate { "${it.functionKey}/${it.spec.name}" to it.spec.dims.single() },
                "同名私有变量按函数键各带自己的维度",
            )

            // ③ 会话注册：第 5 步的 allPrivateDims 是同一条链，失败清单必须为空。
            assertEquals(emptyList(), f.session.privateDimFailures, "私有 #DIM 必须全部注册成功")
            assertEquals(f.plan.privateDims.size, f.session.privateDimDeclared, "成功条数 = 计划条数")

            // ④ 注册落点：按函数键取到的实例是两个不同对象，维度各归各（无串号、无越界）。
            val erbVar = assertNotNull(
                f.session.store.userVar("LOC_PRIV", "B_LOCAL"),
                "B_LOCAL 的私有变量必须按函数键可取",
            )
            val kotVar = assertNotNull(
                f.session.store.userVar("LOC_PRIV", "KOT_ENTRY"),
                "KOT_ENTRY 的私有变量必须按函数键可取",
            )
            assertNotSame(erbVar, kotVar, "同名私有变量跨函数键不得共享实例（串号）")
            assertContentEquals(intArrayOf(2), erbVar.def.dims, "B_LOCAL 的私有数组维度 2")
            assertContentEquals(intArrayOf(3), kotVar.def.dims, "KOT_ENTRY 的私有数组维度 3")
        } finally {
            f.session.close()
        }
    }

    // ------------------------------------------------------------------
    // 判据 2：真 VM 执行两条函数，各自读写自己的私有帧
    // ------------------------------------------------------------------

    @Test
    fun `真VM执行两包函数_同名私有DIM各读各写且末位下标不越界`(@TempDir dir: Path) {
        val f = fixture(dir)
        try {
            f.session.runtime.callFunction("B_LOCAL")
            f.session.runtime.callFunction("KOT_ENTRY")
            assertEquals(
                listOf("B=7,11", "K=21,33"),
                f.session.console.loggedLines(),
                "两个函数必须各自解析到自己的私有数组：维度 2 写 :1、维度 3 写 :2",
            )

            // 再跑一遍证明私有帧按调用重建、互不残留：上一轮的 KOT 值不会漏进 ERB 帧。
            f.session.runtime.callFunction("B_LOCAL")
            assertEquals("B=7,11", f.session.console.loggedLines().last())
        } finally {
            f.session.close()
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：ModRuntimeHost 消费同一份声明快照（Kotlin provider 声明事务面）
    // ------------------------------------------------------------------

    @Test
    fun `ModRuntimeHost用同一份冻结快照装配Kotlin provider并将快照外包拒装`(@TempDir dir: Path) {
        val f = fixture(dir)
        try {
            val snapshot = assertNotNull(f.chain.snapshot)
            val logs = mutableListOf<String>()
            val host = ModRuntimeHost(
                snapshot = snapshot,
                entityKernel = newKernel(),
                nativeMethods = NativeMethodRegistry(),
                logSink = { logs += it },
                runtime = FakeRuntime(),
            )
            val trace = mutableListOf<String>()
            val kotMod = TraceMod(trace, "kotmod")
            val states = host.install(listOf("eratw.kotmod" to kotMod))

            assertEquals(1, states.size)
            assertTrue(states.single().active, "快照内的 Kotlin provider 必须安装成功：$logs")
            assertNull(states.single().failure)
            assertEquals(
                listOf("kotmod:onLoad(eratw.kotmod)", "kotmod:onEnable"),
                trace,
                "同一份声明快照必须驱动 Kotlin provider 的声明事务面生命周期",
            )
            assertNotNull(host.contextOf("eratw.kotmod"), "context 必须按包可取")
            host.shutdown()

            // 拒绝面：不在冻结快照里的包 = 构造段 fail-closed（W3-FAILCLOSE）：产出失败报告项
            // （active=false + 原因点名快照不一致）、原因进日志面，循环继续不整链 abort，
            // 也不静默装成半装配 context。
            val rejectTrace = mutableListOf<String>()
            val rejectStates = host.install(listOf("eratw.absent" to TraceMod(rejectTrace, "absent")))
            assertEquals(1, rejectStates.size, "快照外包必须产出失败报告项而不是抛穿 install")
            assertEquals("eratw.absent", rejectStates.single().packageId)
            assertFalse(rejectStates.single().active, "快照外包必须逐 mod fail-closed，不得装成启用态")
            val revoked = assertNotNull(rejectStates.single().failure, "失败报告项必须携带原因")
            assertTrue(
                revoked is IllegalArgumentException &&
                    revoked.message.orEmpty().contains("声明快照不含 packageId=eratw.absent"),
                "拒装原因必须点名快照不一致，实得：${revoked.message}",
            )
            assertTrue(
                logs.any { it.contains("[eratw.absent][ERROR]") && it.contains("装配失败（构造段）") },
                "拒装原因必须进日志面，实得：$logs",
            )
            assertTrue(rejectTrace.isEmpty(), "拒装不得回调 provider 生命周期")
            assertNull(host.contextOf("eratw.absent"), "拒装不得返回半装配 context")
        } finally {
            f.session.close()
        }
    }

    // ------------------------------------------------------------------
    // 判据 4：声明事务拒绝面照常 fail-closed（同包同函数重名）
    // ------------------------------------------------------------------

    @Test
    fun `声明事务拒绝同包同函数的重复私有DIM声明`() {
        val bad = kotManifest.copy(
            privateDims = mapOf(
                "KOT_ENTRY" to listOf(
                    PrivateDimDecl("LOC_PRIV", DimType.INT, listOf(3)),
                    PrivateDimDecl("loc_priv", DimType.INT, listOf(4)),
                ),
            ),
        )
        val ex = assertFailsWith<DeclarationConflictException>("同名跨大小写必须归一后拒绝") {
            DeclarationRegistry()
                .addPackage(ModDeclarationAdapter.toDescriptor(bad))
                .freeze()
        }
        assertTrue(
            ex.conflicts.any { it.key == "LOC_PRIV" && it.reason.contains("重名") },
            "冲突必须点名归一后的私有变量键：${ex.message}",
        )
    }

    // ------------------------------------------------------------------
    // 辅助：最小真实内核 / 生命周期探针
    // ------------------------------------------------------------------

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "eratw.kotmod",
                name = "PrivStats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PRIV_PARENT")),
        archetypes = listOf(EntityArchetype("PrivChild", listOf("PrivStats"))),
    )

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    private class TraceMod(
        private val trace: MutableList<String>,
        private val label: String,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            trace += "$label:onLoad(${context.packageId})"
        }

        override fun onEnable() {
            trace += "$label:onEnable"
        }

        override fun onDisable() {
            trace += "$label:onDisable"
        }

        override fun onUnload() {
            trace += "$label:onUnload"
        }
    }
}
