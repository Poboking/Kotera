package com.eratw.port.erbcc.compile

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * RT2 G2 波（P1-5 余量二）：erbcc **零基线模块编译档**。
 *
 * ## 为什么单独一个编译档
 *
 * 标准 `CorpusCompile` / `Main.cmdCompile` 是 **base 编译档**：`allowSuperCalls`
 * 恒 false（`ErbProgramCompiler.kt:315` 缺省），产物 `.erbc` 六基线按全量语料铺开。
 * Mod 包必须按 **base=0 编译**（`LinkableModule.validate` 要求六基线全 0，链接期才
 * 由 `ModuleLinker` 分配函数窗口并回填指令操作数），且要保留 `CALL SUPER` 表面语法
 * 编成 super 哨兵（D-3 kojo 派发覆盖契约）。两者是**不同编译形态**，不能靠给
 * `CorpusCompile` 加开关实现 —— 那会让出厂 base 产线的字节面随开关漂移。
 *
 * ⇒ 本对象另起一条独立产线：调用方给出**结构化的函数规格**（函数名 / 形数 / 方法位 /
 * 形参名 / ERB 逻辑行 / 完整签名规范串），本对象用**真 `ErbProgramCompiler` +
 * 真 `LineParser`** 编译成零基线 `Program` + `FuncMeta` 表 + 签名指纹表，
 * 再经 [ErbModuleContainer] 落成 `.erbm`。标准产线（[CorpusCompile] /
 * [ErbcArtifacts] / [BootMetaWriter] / `Main.cmdCompile`）**本波零改动**，
 * 这就是 §3 判据 5「`compileCorpus` 出厂 no-op」的证据面。
 *
 * ## 档位
 *
 * `ErbProgramCompiler(allowSuperCalls = true)` 是 `allowSuperCalls` 的**第一个生产构造点**
 * （此前只有 `KojoOverrideContractTest.kt:100` 的测试构造点）。base 档恒 false；
 * `PatchCompiler.kt` 的 R7 legacy 补丁档**裁定不开 SUPER**（R7 补丁不是 Mod 包，
 * 与 `era-arch-v61u.md §23.4` G 行「两构造点」原文的偏差已在 G 车第一批回执登记）。
 *
 * ## 明确未落（不许误读为「任意 ERB 子树可直接编成 Mod」）
 *
 * 本档吃的是**已切好片的函数规格**，不自带 ERB 预处理 / 符号预扫 / `#DIM` 声明事务 /
 * CSV-ERH 宏。把 `CorpusCompile` 的 prescan + slice 面复用成「一个 Mod ERB 子树 →
 * 一组 FunctionSpec」属编译/链接车余量，登记不落。签名规范串由调用方从
 * [SignatureExport] 产出（与 base 侧 boot meta 的 `signatures` 段同一格式），
 * 本对象只做 `BaseSignatureCatalog.decode` 形状校验 + `fingerprint` 摘要，不重造签名语义。
 *
 * TODO(verify)：`.erbm` 与模块编译档是 RT2 自研（原版 Emuera 无 Mod 代码容器可比），
 * 语义权威 = 蓝图 §16.1 / §10 / §12.1 与 `LinkableModule` 契约。
 */
object ErbModuleCompiler {

    /** 一个待编译的 @函数。`body` 是**已去掉 `@` 头**的 ERB 逻辑行（保留缩进）。 */
    class FunctionSpec(
        val name: String,
        val body: List<String>,
        val arity: Int = 0,
        val isMethod: Boolean = false,
        val returnsStr: Boolean = false,
        val paramNames: List<String> = emptyList(),
        /** [SignatureExport.render] 产出的五要素规范串；null = 不导出该函数签名。 */
        val signatureCanonical: String? = null,
    )

    /** 一个 Mod 包。文件名升序由宿主决定链接序，本对象不排序也不改序。 */
    class ModuleSpec(
        val packageId: String,
        val fileName: String,
        val functions: List<FunctionSpec>,
    )

    /**
     * 最小 ERB 前端：把 Mod ERB 源文件切成 [ModuleSpec]（供 `compile-module` 子命令）。
     *
     * 只认本波需要的四种行：`@NAME` / `@NAME(A, B$)` 开函数、`#FUNCTION` /
     * `#FUNCTIONS` 标注下一个函数为方法、其余行进函数体；空行跳过。
     * **预处理 / 宏 / `#DIM` 声明事务 / CSV-ERH 符号预扫不在本档**（见类 KDoc 未落项），
     * 遇到不认识的 `#` 指令原样保留给 `LineParser` 判形状，不猜语义。
     */
    fun specFromErb(
        packageId: String,
        fileName: String,
        lines: List<String>,
        canonicalByKey: Map<String, String> = emptyMap(),
    ): ModuleSpec {
        val functions = ArrayList<FunctionSpec>()
        var name: String? = null
        var arity = 0
        var paramNames = emptyList<String>()
        var isMethod = false
        var returnsStr = false
        var body = ArrayList<String>()

        fun flush() {
            val n = name ?: return
            functions += FunctionSpec(
                name = n,
                body = body.toList(),
                arity = arity,
                isMethod = isMethod,
                returnsStr = returnsStr,
                paramNames = paramNames,
                signatureCanonical = canonicalByKey[n.uppercase(Locale.ROOT)],
            )
            name = null
            arity = 0
            paramNames = emptyList()
            isMethod = false
            returnsStr = false
            body = ArrayList()
        }

        for (raw in lines) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (text.startsWith("#FUNCTIONS", ignoreCase = true)) {
                isMethod = true
                returnsStr = true
                continue
            }
            if (text.startsWith("#FUNCTION", ignoreCase = true)) {
                isMethod = true
                returnsStr = false
                continue
            }
            if (text.startsWith("@")) {
                flush()
                val header = text.substring(1).trim()
                val open = header.indexOf('(')
                name = if (open < 0) header.takeWhile { !it.isWhitespace() && it != ',' }
                else header.substring(0, open).trim()
                paramNames = if (open < 0) emptyList()
                else header.substring(open + 1).substringBefore(')')
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                arity = paramNames.size
                continue
            }
            require(name != null) {
                "Mod ERB [$fileName] 在第一个 @ 函数头之前出现代码行 —— 整包拒绝"
            }
            body += text
        }
        flush()
        require(functions.isNotEmpty()) { "Mod ERB [$fileName] 没有 @ 函数 —— 整包拒绝" }
        return ModuleSpec(packageId, fileName, functions)
    }

    /** 编译结果：可直接喂 `ModChainAssembler.assemble` 的 `CompiledModule`。 */
    fun compile(spec: ModuleSpec): CompiledModule {
        require(spec.packageId.isNotBlank()) { "模块 packageId 不得为空 —— 整包拒绝" }
        require(spec.functions.isNotEmpty()) { "模块 [${spec.packageId}] 没有函数 —— 整包拒绝" }
        val keys = ArrayList<String>(spec.functions.size)
        val seen = HashSet<String>(spec.functions.size)
        for (f in spec.functions) {
            val key = f.name.uppercase(Locale.ROOT)
            require(key.isNotEmpty()) { "模块 [${spec.packageId}] 有空函数名 —— 整包拒绝" }
            // 判重用 Set.add 的返回值；List.add 恒 true，会让同名函数漏到编译器层
            // 才以 CompileException 拒绝，写侧的 fail-closed 就形同虚设。
            require(seen.add(key)) { "模块 [${spec.packageId}] 函数名重复：$key —— 整包拒绝" }
            keys += key
            require(f.arity >= 0) { "模块 [${spec.packageId}] 的 [$key] 形参数为负 —— 整包拒绝" }
            if (f.paramNames.isNotEmpty()) {
                require(f.paramNames.size == f.arity) {
                    "模块 [${spec.packageId}] 的 [$key] paramNames 与 arity 不等长 —— 整包拒绝"
                }
            }
        }
        val known = keys.toHashSet()

        // 唯一档位构造点：Mod 包才开 SUPER。base / R7 legacy 补丁档一律不开。
        val compiler = ErbProgramCompiler(allowSuperCalls = true)
        val symbols = SymbolTable().declareFunction("SUPER", OperandType.INT)
        for ((i, f) in spec.functions.withIndex()) {
            if (f.isMethod) symbols.declareMethod(f.name) else symbols.declareFunction(f.name, OperandType.INT)
            val parser = LineParser(symbols)
            val statements = parseBody(parser, spec.fileName, f.body)
            compiler.compileFunction(
                FuncSig(
                    name = keys[i],
                    arity = f.arity,
                    isMethod = f.isMethod,
                    returnsStr = f.returnsStr,
                    paramNames = f.paramNames,
                ),
                statements,
                knownFunctions = known,
            )
        }
        val program = compiler.build(keys.first())

        val signatures = LinkedHashMap<String, String>()
        val metas = ArrayList<FuncMeta>(program.functions.size)
        val byKey = spec.functions.associateBy { it.name.uppercase(Locale.ROOT) }
        for (i in program.functions.indices) {
            val info = program.functions[i]
            val key = info.name.uppercase(Locale.ROOT)
            val f = requireNotNull(byKey[key]) {
                "编译产物函数表与规格不符：[$key] 没有对应 FunctionSpec —— 整包拒绝"
            }
            val strArgs = f.paramNames.count { it.endsWith("$") }
            metas += FuncMeta(
                index = i,
                key = key,
                name = info.name,
                isMethod = f.isMethod,
                argIntCount = f.arity - strArgs,
                argStrCount = strArgs,
                returnsStr = f.returnsStr,
                defIndex = i,
                position = "${spec.fileName}:${i + 1}",
            )
            val canonical = f.signatureCanonical
            if (canonical != null) {
                require(BaseSignatureCatalog.decode(canonical) != null) {
                    "模块 [${spec.packageId}] 的 [$key] 签名规范串不完整 —— 整包拒绝"
                }
                signatures[key] = BaseSignatureCatalog.fingerprint(canonical)
            }
        }
        return CompiledModule(spec.packageId, program, metas, signatures)
    }

    /** 产 `.erbm`（覆写式落盘；调用方负责原子性/目录准备，本对象不碰标准产线目录）。 */
    fun write(path: Path, spec: ModuleSpec): ByteArray {
        val module = compile(spec)
        val bytes = ErbModuleContainer.encode(module, allowSuperCalls = true)
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, bytes)
        return bytes
    }

    /** 读 `.erbm`（fail-closed 全在 [ErbModuleContainer.decode]）。 */
    fun read(path: Path): ErbModuleContainer.ErbModule =
        ErbModuleContainer.decode(Files.readAllBytes(path))

    private fun parseBody(
        parser: LineParser,
        fileName: String,
        body: List<String>,
    ): List<Statement> = body.mapIndexedNotNull { i, raw ->
        val text = raw.trimStart()
        if (text.isEmpty()) return@mapIndexedNotNull null
        val kind = when {
            text.startsWith("@") -> LineKind.FUNCTION_LABEL
            text.startsWith("$") -> LineKind.GOTO_LABEL
            text.startsWith("#") -> LineKind.SHARP
            else -> LineKind.CODE
        }
        // 解析器对纯注释 / 空语义行返回 null 是既有口径（同 KojoOverrideContractTest.parseErb）。
        parser.parseLogicalLine(LogicalLine(kind, fileName, i + 1, text))
    }
}
