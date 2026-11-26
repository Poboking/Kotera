package com.eratw.port.core.mod

import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.OperandRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.CallDefaults
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModChainTrace
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.OverridePolicy
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M2-4 legacy `.patch` 进口转码器的定向判据（CONTRACT-RT2-M2-4 §1 判据 1/2/3/5）。
 *
 * 夹具纪律：base 走真 [ErbProgramCompiler]，补丁走真 [PatchCompiler]，转码产物走真
 * [ErbModuleContainer.encode] / [ManifestCodec.encode] / [ModChainAssembler.assemble] /
 * 真 `GameSession` —— 不手搓假字节自证（§23.5 第 11 条）。
 *
 * 断言面：
 * 1. 零基化：六个基线全 0；池引用按窗口基线下移（字符串取用回原文）；
 * 2. `_CALL` 内外分野：base 调用变导入哨兵 + imports 名表，模块内调用变 0 基下标；
 * 3. 合成事件入口退役：入口 meta 不进模块、成员 meta 保留 isEvent；
 * 4. 私有 #DIM 进 descriptor，并经 plan 进会话注册链（`privateDimFailures` 空）；
 * 5. 生产装配路：事件成员与 overlay 路同成员集/同序，真 VM 执行新函数与事件成员；
 * 6. 负控：base 目录缺名 / 空函数集；S9 改判：CALL 事件名放行为导入（不拒）。
 */
class PatchImportTranscoderTest {

    private val baseSource = """
        @A_ONE
        X = 1
        RETURN 0

        @A_TWO(N)
        CALL A_ONE
        RETURN 0

        @EVENTSHOP
        PRINTL BASE-EVT
        RETURN 0

        @A_METHOD
        #FUNCTION
        RETURNF 7
    """.trimIndent()

    private val patchSource = """
        @B_HELLO
        CALL A_ONE
        PRINTL PATCH-HELLO
        CALL B_LOCAL(3)
        RETURN 0

        @B_LOCAL(K)
        #DIM LOCAL_VAL, 2
        LOCAL_VAL:0 = K
        RETURN 0

        @EVENTSHOP
        PRINTL PATCH-EVT
        RETURN 0
    """.trimIndent()

    private val baseNames = setOf("A_ONE", "A_TWO", "EVENTSHOP", "A_METHOD")

    private val baseExprCalls = ExprCallTable(
        intMethods = setOf("A_METHOD"),
        nonMethodLabels = setOf("A_ONE", "A_TWO", "EVENTSHOP"),
    )

    private fun symbols(): SymbolTable = SymbolTable()
        .declareVariable("X", OperandType.INT, 1)
        .declareVariable("N", OperandType.INT, 1)
        .declareVariable("K", OperandType.INT, 1)

    /** 按 `@` 行切片顺序编译（与 PatchCompilerTest 的 mini 装置同口径）。 */
    private fun compileMini(
        files: List<Pair<String, String>>,
        sym: SymbolTable,
        heads: Map<String, Pair<Boolean, Boolean>>,
    ): Program {
        val parser = LineParser(sym, lexerConfig = LexerConfig())
        val groups = files.flatMap { (rel, text) -> collectGroups(rel, text, parser) }
        val compiler = ErbProgramCompiler(callDefaults = emptyMap())
        for ((name, stmts) in groups) {
            val label = stmts.filterIsInstance<FuncLabelStmt>().first()
            val (isMethod, returnsStr) = heads[name] ?: (false to false)
            compiler.compileFunction(
                FuncSig(name, label.paramNames.size, isMethod, returnsStr, label.paramNames),
                stmts, baseNames + heads.keys, baseExprCalls,
            )
        }
        return compiler.build(groups.first().first)
    }

    /** 单文件切组：`@` 函数头开新组，`#` 指令行丢弃，其余归当前组。 */
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

    /** base 编译 + 侧车元数据 + 窗口基线（供 PatchCompiler 续编同一份 base）。 */
    private fun compileBase(): Triple<Program, List<FuncMeta>, PatchCompiler.BaseCounts> {
        val heads = mapOf(
            "A_ONE" to (false to false),
            "A_TWO" to (false to false),
            "EVENTSHOP" to (false to false),
            "A_METHOD" to (true to false),
        )
        val p = compileMini(listOf("mini.ERB" to baseSource), symbols(), heads)
        val metas = p.functions.mapIndexed { i, f ->
            FuncMeta(
                index = i,
                key = f.name,
                name = f.name,
                isEvent = Symbols.isEventLabelName(f.name),
                isMethod = f.name == "A_METHOD",
                argIntCount = if (f.name == "A_TWO") 1 else 0,
                defIndex = i,
                position = "mini.ERB:${i + 1}",
            )
        }
        val counts = PatchCompiler.BaseCounts(
            instructionCount = p.instructionCount,
            functionCount = p.functions.size,
            statementCount = p.statementCount,
            stringCount = p.stringCount,
            longCount = p.constantCount,
            exprCount = p.exprCount,
        )
        return Triple(p, metas, counts)
    }

    private fun compilePatch(
        meta: BootMetadata,
        counts: PatchCompiler.BaseCounts,
        source: String = patchSource,
        entry: String = "B_HELLO",
    ): PatchCompiler.PatchResult = PatchCompiler(meta, counts, symbols()).compile(
        listOf(PatchCompiler.SourceFile("mods/patch.ERB", source)),
        entry,
    )

    private fun baseMeta(metas: List<FuncMeta>) = BootMetadata(functions = metas)

    private fun transcode(
        result: PatchCompiler.PatchResult,
        counts: PatchCompiler.BaseCounts,
        metas: List<FuncMeta>,
    ): PatchImportTranscoder.Transcoded = PatchImportTranscoder.transcode(
        result,
        counts,
        metas.associate { it.index to it.key },
        "kojo.test",
    )

    // ------------------------------------------------------------------
    // 判据 1/3：转码核心（零基化 + 调用分野 + 事件 + 私有 DIM）
    // ------------------------------------------------------------------

    @Test
    fun `转码_真实补丁产物零基化_调用分野_合成入口退役_私有DIM进descriptor`() {
        val (_, metas, counts) = compileBase()
        val result = compilePatch(baseMeta(metas), counts)
        val t = transcode(result, counts, metas)
        val p = t.module.program

        // ---- 零基线六项 ----
        assertEquals(0, p.codeBase, "codeBase 必须归零")
        assertEquals(0, p.functionBase, "functionBase 必须归零")
        assertEquals(0, p.statementBase, "statementBase 必须归零")
        assertEquals(0, p.stringBase, "stringBase 必须归零")
        assertEquals(0, p.longBase, "longBase 必须归零")
        assertEquals(0, p.exprBase, "exprBase 必须归零")

        // ---- 槽位：合成入口被截断，成员保留（含事件成员）----
        assertContentEquals(
            listOf("B_HELLO", "B_LOCAL", "EVENTSHOP"),
            t.module.functions.map { it.key },
        )
        t.module.functions.forEachIndexed { i, m -> assertEquals(i, m.index, "FuncMeta 稠密 0 基") }
        assertTrue(t.module.functions[2].isEvent, "事件成员必须保留 isEvent")
        assertContentEquals(listOf("EVENTSHOP"), t.droppedEventEntries)

        // ---- _CALL 内外分野：base → 导入哨兵 -1；模块内 → 0 基下标 1 ----
        val words = p.codeSource.toIntArray()
        val calls = ArrayList<Int>()
        var stmtFound = false
        for (pc in 0 until p.instructionCount) {
            val op = words[pc * Instruction.WORDS]
            if (op == Opcode._CALL) calls += words[pc * Instruction.WORDS + 1]
            val rel = OpcodeRelocation.of(op)
            if (rel !== OperandRelocation.EMPTY && rel.at(0) == PoolRef.STATEMENT) {
                val a = words[pc * Instruction.WORDS + 1]
                if (p.overlayPoolRef.statementAt(a).raw.contains("PATCH-HELLO")) stmtFound = true
            }
        }
        assertContentEquals(listOf(-1, 1), calls, "_CALL 导入哨兵 + 模块内下标")
        assertContentEquals(listOf("A_ONE"), t.imports, "imports 名表按首次引用序")
        assertTrue(stmtFound, "语句池 marker 的 a 操作数必须已下移并可取回原语句")

        // ---- 池计数与窗口基线差一致（下移量 = base 池计数）----
        assertEquals(result.program.statementCount - counts.statementCount, p.statementCount)
        assertEquals(result.program.stringCount - counts.stringCount, p.stringCount)

        // ---- 函数入口 rebase：首个函数从 0 起 ----
        assertEquals(0, p.functions[0].entryPc)
        assertTrue(p.functions[1].entryPc > 0, "成员入口按窗口口径下移")

        // ---- 私有 #DIM 进 descriptor ----
        assertEquals(setOf("B_LOCAL"), t.descriptor.privateDims.keys)
        val dim = t.descriptor.privateDims.getValue("B_LOCAL").single()
        assertEquals("LOCAL_VAL", dim.name)
        assertContentEquals(intArrayOf(2), dim.dims)
    }

    // ------------------------------------------------------------------
    // 判据 2/3：生产装配路 e2e（真 assembler + 真 GameSession）
    // ------------------------------------------------------------------

    @Test
    fun `生产装配路_事件成员与overlay同集同序_新函数与私有DIM在真会话可执行`() {
        val (base, metas, counts) = compileBase()
        val result = compilePatch(baseMeta(metas), counts)
        val t = transcode(result, counts, metas)

        val chain = ModChainAssembler.assemble(
            listOf(ManifestSource("kojo.test.manifest", ManifestCodec.encode(t.descriptor))),
            listOf(t.module.toCompiledModule()),
            base,
            metas,
        )
        val plan = assertNotNull(ModChainRuntimePlan.of(chain), "非旁路装配必须产出 plan")

        // 私有 DIM 经 plan 聚合（descriptor 唯一来源）
        assertEquals(1, plan.privateDims.size)
        assertEquals("B_LOCAL", plan.privateDims.single().functionKey)

        // 事件成员：base 成员 + 模块成员，同桶按装载序；与 overlay 路的顺序视图一致
        val linkedMembers = plan.eventRegistry.membersOf("EVENTSHOP").map { it.physicalIndex }
        assertEquals(listOf(2, metas.size + 2), linkedMembers, "base 成员在前、模块成员在后")
        val overlayOrder = assertNotNull(
            FunctionTable(metas + result.funcMetas).eventCallOrder("EVENTSHOP"),
        ).map { it.index }
        assertContentEquals(overlayOrder, linkedMembers, "模块链与 overlay 路事件成员集/序一致")

        // 真会话：模块新函数可调 + 私有 #DIM 注册成功
        val root = Files.createTempDirectory("m2-4-transcode").also { it.toFile().deleteOnExit() }
        val session = GameSession(root, base, baseMeta(metas), modChain = plan)
        assertEquals(emptyList(), session.privateDimFailures, "私有 #DIM 必须注册成功")
        assertTrue(session.privateDimDeclared >= 1, "会话私有变量注册链必须吃到模块声明")

        session.runtime.callFunction("B_HELLO")
        assertTrue(
            session.console.loggedLines().contains("PATCH-HELLO"),
            "模块新函数经真 VM 执行：${session.console.loggedLines()}",
        )

        // 事件派发：base 成员 + 模块成员按四桶序真执行
        val eventSession = GameSession(root, base, baseMeta(metas), modChain = plan)
        assertTrue(eventSession.runtime.executeEvent("EVENTSHOP"), "事件必须存在")
        assertContentEquals(
            listOf("BASE-EVT", "PATCH-EVT"),
            eventSession.console.loggedLines(),
            "事件成员按装载序真执行",
        )
    }

    // ------------------------------------------------------------------
    // 判据 4（R7-①）：撞 base 同名的补丁按**声明式覆盖**装载并在真会话顶掉原实现
    // ------------------------------------------------------------------

    /**
     * 覆盖用补丁：重新定义 base 的 `@A_ONE`（原版语义 = 后装载者胜出，spec:
     * LabelDictionary.cs:107-113 + ErbLoader.cs:466-475）。
     */
    private val overridePatchSource = """
        @A_ONE
        PRINTL OVR-ONE
        RETURN 0
    """.trimIndent()

    private fun compileOverridePatch(
        meta: BootMetadata,
        counts: PatchCompiler.BaseCounts,
    ): PatchCompiler.PatchResult = PatchCompiler(
        meta,
        counts,
        symbols(),
        // 2026-11-23 KI 改判：overrideDeclarable 判据参数已撤销（撞名一律覆盖装载）。
    ).compile(listOf(PatchCompiler.SourceFile("mods/kojo.ERB", overridePatchSource)), "A_ONE")

    @Test
    fun `覆盖声明_同名补丁不再整包拒_键进descriptor并导出补丁真实签名`() {
        val (_, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        // 融合方案：容器签名段写补丁真实签名（不是 base 签名）。正常覆盖 = 两侧 ABI 相同，
        // 故 base 侧也用同一指纹（模拟签名相符）；签名不符的负例由独立用例钉死。
        val patchSig = result.signatures.getValue("A_ONE")
        assertTrue(patchSig.length == 64, "补丁签名必须是 SHA-256 指纹，实际 $patchSig")

        val t = PatchImportTranscoder.transcode(
            result,
            counts,
            metas.associate { it.index to it.key },
            "kojo.override",
            { if (it == "A_ONE") patchSig else "" },
        )
        assertEquals(setOf("A_ONE"), t.descriptor.overrideFunctions)
        assertEquals(patchSig, t.module.signatures["A_ONE"], "模块指纹 = 补丁真实签名（签名单源）")
        assertTrue(t.descriptor.policyFor("A_ONE") == OverridePolicy.OVERWRITE, "声明即 OVERWRITE 策略")

        // 线格式往返：覆盖声明与指纹必须随 manifest / 容器一起存活（导入产物落盘再读回的同一条路）。
        val decoded = ManifestCodec.decode(ManifestCodec.encode(t.descriptor))
        assertEquals(setOf("A_ONE"), decoded.overrideFunctions, "override-functions 段过往返")
        val roundTrip = ErbModuleContainer.decode(ErbModuleContainer.encode(t.module))
        assertEquals(patchSig, roundTrip.signatures["A_ONE"], "容器签名段过往返")
    }

    @Test
    fun `KI-2 ABI不符不再拒装_容器仍持补丁真实签名_装配告警放行`() {
        // 融合方案数据面钉（2026-11-23 KI 改判后语义更新）：容器写的是补丁真实签名（不是
        // base 顶替值）——改判后 ABI 不符不再拒装（原抛 OverrideSignatureException），改为
        // 装配成功 + diagnostics 告警（last-wins 放行，SUPER 链照建）。本例钉：容器签名
        // 数据面不回退 + 装配链告警放行到位。
        val (base, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        val patchSig = result.signatures.getValue("A_ONE")
        val otherSig = "f".repeat(64) // 与补丁真实签名不等的 base 指纹 = ABI 不符
        val t = PatchImportTranscoder.transcode(
            result,
            counts,
            metas.associate { it.index to it.key },
            "kojo.override.abi-bad",
            { if (it == "A_ONE") otherSig else "" },
        )
        assertEquals(patchSig, t.module.signatures["A_ONE"], "容器必须持有补丁真实签名（不是 base 顶替值）")
        val diags = mutableListOf<String>()
        val chain = ModChainAssembler.assemble(
            listOf(ManifestSource("kojo.override.abi-bad.manifest", ManifestCodec.encode(t.descriptor))),
            listOf(t.module.toCompiledModule()),
            base,
            metas,
            baseSignatureOf = { if (it == "A_ONE") otherSig else "" },
            trace = ModChainTrace().apply { overrideDiagnostics = { diags += it } },
        )
        assertTrue(
            diags.any { it.contains("A_ONE") && it.contains("签名不一致") },
            "ABI 不符覆盖必须出装配告警: $diags",
        )
    }

    // ------------------------------------------------------------------
    // 可选收窄声明（pack.json `overrideFunctions`）：只约束事实，不提供名单
    // ------------------------------------------------------------------

    /** 覆盖场景转码：base 侧签名 = 补丁真实签名（模拟 ABI 相符），可带显式声明。 */
    private fun transcodeWithDeclared(
        result: PatchCompiler.PatchResult,
        counts: PatchCompiler.BaseCounts,
        metas: List<FuncMeta>,
        allowList: List<String>?,
    ): PatchImportTranscoder.Transcoded {
        // 加性补丁没有 A_ONE 切片 ⇒ 空串（无可覆盖键时签名口根本不会被查）。
        val sig = result.signatures["A_ONE"] ?: ""
        return PatchImportTranscoder.transcode(
            result,
            counts,
            metas.associate { it.index to it.key },
            "kojo.test.declared",
            PatchImportTranscoder.OverrideInputs({ if (it == "A_ONE") sig else "" }, allowList),
        )
    }

    @Test
    fun `显式声明与推导集一致即放行_小写键按大写归一`() {
        val (_, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        // 声明写小写 a_one：键按大写归一对齐模块侧口径，不因大小写误判成「多列」。
        val t = transcodeWithDeclared(result, counts, metas, listOf("a_one"))
        assertEquals(setOf("A_ONE"), t.descriptor.overrideFunctions, "名单仍由推导集给出")
    }

    @Test
    fun `显式声明多列不可覆盖目标即拒_不静默放宽`() {
        val (_, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        val e = assertFailsWith<IllegalArgumentException>("声明覆盖了实际覆盖不了的目标必须拒") {
            transcodeWithDeclared(result, counts, metas, listOf("A_ONE", "NOT_A_BASE_FN"))
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("NOT_A_BASE_FN"), "要点名多列的那个键：$msg")
        assertTrue(msg.contains("不可覆盖"), "要说清是「不可覆盖」而不是别的原因：$msg")
    }

    @Test
    fun `显式声明漏列同名函数即拒_防改源后意外多覆盖`() {
        val (_, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        // [] = 显式声明「本包不覆盖任何函数」，但补丁里 @A_ONE 与 base 同名 ⇒ 硬拒。
        val e = assertFailsWith<IllegalArgumentException>("空声明 + 撞名函数必须拒") {
            transcodeWithDeclared(result, counts, metas, emptyList())
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("A_ONE"), "要点名没声明的那个函数：$msg")
        assertTrue(msg.contains("显式声明不覆盖任何函数"), "要交代空声明的含义：$msg")
    }

    @Test
    fun `显式声明不覆盖_加性补丁无撞名时合法且零覆盖`() {
        val (_, metas, counts) = compileBase()
        val result = compilePatch(baseMeta(metas), counts)
        val t = transcodeWithDeclared(result, counts, metas, emptyList())
        assertTrue(t.descriptor.overrideFunctions.isEmpty(), "加性包声明不覆盖 = 合法（无撞名即无冲突）")
    }

    @Test
    fun `覆盖会话_base调用点一字不改也走覆盖者`() {
        val (base, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        // 正常覆盖 = 补丁签名与 base 签名相同（同一 SignatureExport 口径），故两侧同指纹。
        val patchSig = result.signatures.getValue("A_ONE")
        val t = PatchImportTranscoder.transcode(
            result,
            counts,
            metas.associate { it.index to it.key },
            "kojo.override",
            { if (it == "A_ONE") patchSig else "" },
        )
        val chain = ModChainAssembler.assemble(
            listOf(ManifestSource("kojo.override.manifest", ManifestCodec.encode(t.descriptor))),
            listOf(t.module.toCompiledModule()),
            base,
            metas,
            baseSignatureOf = { if (it == "A_ONE") patchSig else "" },
        )
        val plan = assertNotNull(ModChainRuntimePlan.of(chain), "覆盖必须产出非旁路 plan")
        assertTrue(plan.hasOverrides, "覆盖 ⇒ HAS_OVERRIDES 为真，dispatch 才交给 VM")

        val root = Files.createTempDirectory("m2-4-override").also { it.toFile().deleteOnExit() }
        val direct = GameSession(root, base, baseMeta(metas), modChain = plan)
        direct.runtime.callFunction("A_ONE")
        assertContentEquals(listOf("OVR-ONE"), direct.console.loggedLines(), "按名直调走覆盖者")

        // 判据的另一半：base 段里 `A_TWO → CALL A_ONE` 的调用点一字节没动（下方 linked 前的
        // base 产物就是 compileBase 的原实例），运行期靠 dispatch 把它带到覆盖者。
        val viaBaseCallSite = GameSession(root, base, baseMeta(metas), modChain = plan)
        viaBaseCallSite.runtime.callFunction("A_TWO")
        assertContentEquals(
            listOf("OVR-ONE"),
            viaBaseCallSite.console.loggedLines(),
            "base 调用点经 dispatch 也执行覆盖者（否则 684 处按名调用点仍打回原实现）",
        )
    }

    @Test
    fun `KI-2 缺base签名指纹转码不再拒_降级清单点名键`() {
        // 2026-11-23 KI 改判（BOARD KI-2）：base 指纹缺失的覆盖键照常声明（链接期
        // last-wins 放行 + ABI 未校验告警），键收进降级清单随 Transcoded 出口供
        // 导入告警面消费；不再抛 IllegalArgumentException 不落半成品。
        val (_, metas, counts) = compileBase()
        val result = compileOverridePatch(baseMeta(metas), counts)
        val t = PatchImportTranscoder.transcode(
            result,
            counts,
            metas.associate { it.index to it.key },
            "kojo.override",
            { "" },
        )
        assertEquals(setOf("A_ONE"), t.descriptor.overrideFunctions, "缺指纹键照常声明覆盖")
        assertEquals(listOf("A_ONE"), t.degradedOverrideKeys, "降级清单点名缺指纹键")
    }

    // ------------------------------------------------------------------
    // 判据 5：负控
    // ------------------------------------------------------------------

    @Test
    fun `S9改判_CALL事件名放行为导入不拒绝`() {
        val (_, metas, counts) = compileBase()
        val patched = compilePatch(
            baseMeta(metas),
            counts,
            source = """
                @B_BAD
                CALL EVENTSHOP
                RETURN 0
            """.trimIndent(),
            entry = "B_BAD",
        )
        // S9 改判 T1/T2：事件名进 imports（链接期缺名残桩，运行期按 CompatiCallEvent
        // 配置解析）；转码不再拒绝。
        val t = transcode(patched, counts, metas)
        assertTrue(t.imports.contains("EVENTSHOP"), t.imports.toString())
        assertTrue(t.descriptor.externalCalls.contains("EVENTSHOP"), t.descriptor.externalCalls.toString())
    }

    @Test
    fun `负控_base目录缺名拒绝`() {
        val (_, metas, counts) = compileBase()
        val result = compilePatch(baseMeta(metas), counts)
        val e = assertFailsWith<IllegalArgumentException> {
            PatchImportTranscoder.transcode(result, counts, emptyMap(), "kojo.test")
        }
        assertTrue(e.message!!.contains("没有名字"), e.message!!)
    }

    @Test
    fun `负控_空函数集拒绝`() {
        val (_, metas, counts) = compileBase()
        val result = compilePatch(baseMeta(metas), counts)
        val empty = PatchCompiler.PatchResult(
            result.program,
            result.functions,
            emptyList(),
            result.privateDims,
            result.sizeConsts,
            result.parseWarnings,
        )
        val e = assertFailsWith<IllegalArgumentException> { transcode(empty, counts, metas) }
        assertTrue(e.message!!.contains("空函数集"), e.message!!)
    }

    // ------------------------------------------------------------------
    // 判据 6：产物版本号的 core 侧默认值
    // ------------------------------------------------------------------

    @Test
    fun `版本号_转码默认读数恒为1_与改造前逐字一致`() {
        val (_, metas, counts) = compileBase()
        val result = compilePatch(baseMeta(metas), counts)
        val t = transcode(result, counts, metas)

        // core 层只给历史默认值 "1"；作者版本由 app 导入端读包元数据后覆盖 descriptor 再编码
        // （传导链见 ModImportPackMetaEndToEndTest）。这里守的是「不让 core 自己长出策略」。
        assertEquals("1", t.descriptor.version, "core 默认读数必须与改造前硬编码值逐字相同")
        assertEquals(
            "1",
            ManifestCodec.decode(ManifestCodec.encode(t.descriptor)).version,
            "manifest 编解码往返不丢默认版本号",
        )
    }
}
