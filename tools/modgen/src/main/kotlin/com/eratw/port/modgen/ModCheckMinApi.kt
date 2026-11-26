package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path

/** 仓库根标记：从 mod 根向上找它，才能定位 mod-api 的 `ModApi.VERSION` 真源。 */
private const val ROOT_MARKER = "settings.gradle.kts"

/** mod-api 作者面真源文件名（[ModCheckApiSurface.loadSources] 的键以它结尾）。 */
private const val API_FILE = "ModApi.kt"

/** `minApi == 0` 表示「不设闸」（与 `ModuleDescriptor.minApi` 的缺省同值），不是「要求 v0」。 */
private const val NO_GATE = 0

private const val VALUE_GROUP = 1

/** `const val VERSION = N` 的取值；与 [ModCheckApiSurface] 同一抽取口径，本面只关心数值。 */
private val VERSION_CONST = Regex("""\bconst\s+val\s+VERSION\s*(?::\s*Int\s*)?=\s*(\d+)""")

/** 作者面最低版本声明的头部（**主判据**）：`modDeclarations(` 调用的开头。 */
private val DSL_CALL = Regex("""(?<!fun )\bmodDeclarations\s*\(""")

/** 调用实参表里的 `minApi = <表达式>`；表达式截到逗号 / 换行 / 闭括号前。 */
private val DSL_MIN_API = Regex("""\bminApi\s*=\s*([^,\n)]*)""")

/** 纯整数字面量（负号可带）；抽不到就整面静默。 */
private val INT_LITERAL = Regex("""^-?\d+$""")

/** 兼容扫描的旧形态头部：`override val minApi`（作者面从未提供该成员，扫它只做兼容）。 */
private val MIN_API_DECL = Regex("""\boverride\s+val\s+minApi\b""")

/** [MIN_API_DECL] 之后的同行剩余部分：`: Int = <整数字面量>`（`Int` 可省，尾分号可省）。 */
private val MIN_API_LITERAL = Regex("""^\s*(?::\s*Int\s*)?=\s*(-?\d+)\s*;?\s*$""")

/** 一处 min-api 声明；[value] 为 null 表示抽不到字面量（⇒ 整面静默）。 */
private class MinApiDeclaration(val file: String, val line: Int, val value: Int?)

/** 一个 `modDeclarations(…)` 调用的实参表文本 + 闭括号所在行下标（跨行调用定位用）。 */
private class CallArguments(val text: String, val closeLine: Int)

/**
 * `check-mod` 的 min-api 声明面：载体源码里 `modDeclarations(…, minApi = <字面量>) { … }` 命名实参
 * 的自洽检查（兼容扫描 `override val minApi` 旧形态；作者面实际入口是 DSL 形参）。
 *
 * ## 这条面管什么
 *
 * `pack-mod --min-api <N>` 会把「本 mod 要求的最低作者面版本」写进包元数据的 `minApi` 键；真正的
 * 闸在设备装配期 —— core 的 `ModuleDescriptor` 对 `minApi` 有 `require(minApi >= 0)`，负数当场抛、
 * 整链拒载；`minApi > ModApi.VERSION` 的包在低于 N 的引擎上装不进来。两者以前的报错都只在
 * 「pack 之后 / 装配之时」才露出来，作者跑 `check-mod` 看不到。本面把**源码里能静态判的**那部分
 * 提前到 CI：
 *
 * - `minApi` 字面量为负 ⇒ ERROR。装配期 `require(minApi >= 0)` 必抛 ⇒ mod 载不进来，必然失效，
 *   没有任何「以后会好」的余地，只能改源码。
 * - `minApi` 高于当前 `ModApi.VERSION` ⇒ WARN。这是**预留**：作者可以照未来版本写，引擎升到
 *   v$N 之后就能装（要等引擎升到声明的版本），当前装不进来属「时间未到」而不是「写错了」，
 *   因此不判 ERROR —— 报成必须改的红，只会逼作者把预留改成当前版本、丢掉声明意图。
 * - `minApi == 0`（不设闸，与 [PackModMeta] 的「0 = 不写键」同口径）与 `minApi == VERSION`
 *   （等于当前作者面，缺省值）都不报。
 *
 * ## 什么**不**检查（否则就是假红）
 *
 * 源码声明与 `pack-mod --min-api` 的元数据值是否一致：一个是源码、一个是命令行实参，静态面拿不到
 * 命令行，判不了；而且 pack 侧自己也只做 `require(minApi >= 0)`，不比对源码 —— 这条留给打包参数
 * 的评审，不在 check-mod 报。（作者面的 `apiVersion` 契约版本协商是**另一条闸**，见
 * [ModCheckVersion]：那个成员真在作者面里，`> VERSION` 会被宿主直接拒载，所以它判 ERROR。）
 *
 * ## 静默口径：宁可漏报，不许假红
 *
 * 只做**文本抽取**，不做 Kotlin 语法分析：
 * - 命中 `minApi = …` 但右侧不是整数字面量（变量 / 表达式 / 拼接 / getter 形式 / 跨行表达式）
 *   ⇒ **整面静默**。抽不到的值不能当证据：`minApi = target` 里的 `target` 完全可能是合法值，
 *   报任何结论都可能是假红。
 * - `minApi` 写成**位置实参**（`modDeclarations("pkg", "1.0", 5) { … }`）⇒ 不抽（按名字才认）。
 * - 向上找不到仓库根、读不到 mod-api 源码、`const val VERSION` 抽不出来 ⇒ 整面静默：没有「当前
 *   引擎版本」这个真源，`> VERSION` 这条判据无从谈起。
 * - 源码里根本没有 `modDeclarations(…, minApi = …)` 声明 ⇒ 什么都不报。
 *
 * 当前引擎版本从 mod-api 源码文本抽（复用 [ModCheckApiSurface.loadSources]，与版本登记面同一份
 * 真源），**不写死数字**：写死就会在 `VERSION` 递增后静默判错。抽取口径的漂移由测试文本哨兵兜底
 * （照 [ModStringsLimits] 的约定：modgen 只读源码、不依赖 mod-api 编译产物）。
 */
internal object ModCheckMinApi {

    /** 扫描 [sources] 的 min-api 声明面，发现追加进 [findings]。 */
    fun check(root: Path, sources: List<SourceFile>, findings: MutableList<Finding>) {
        val engineVersion = engineVersion(root) ?: return
        val declarations = sources.flatMap { source -> declarationsIn(source) }
        if (declarations.any { declaration -> declaration.value == null }) return
        declarations.forEach { declaration -> checkDeclaration(declaration, engineVersion, findings) }
    }

    /** 从 [root] 向上找仓库根，再读 mod-api 源码抽 `ModApi.VERSION`；任一环节缺失返回 null（整面静默）。 */
    private fun engineVersion(root: Path): Int? = repoRootOf(root)
        ?.let { repoRoot -> ModCheckApiSurface.loadSources(repoRoot) }
        ?.entries
        ?.firstOrNull { (label, _) -> label.endsWith("/$API_FILE") }
        ?.value
        ?.let { apiText -> VERSION_CONST.find(apiText)?.groupValues?.get(VALUE_GROUP)?.toIntOrNull() }

    /** 从 [root] 向上找含 `settings.gradle.kts` 的仓库根；找不到返回 null。 */
    private fun repoRootOf(root: Path): Path? =
        generateSequence(root.toAbsolutePath().normalize()) { path -> path.parent }
            .firstOrNull { path -> Files.isRegularFile(path.resolve(ROOT_MARKER)) }

    /** 单个源文件里的全部 min-api 声明：DSL 命名实参（主判据）+ `override val minApi`（兼容）。 */
    private fun declarationsIn(source: SourceFile): List<MinApiDeclaration> =
        overrideDeclarationsIn(source) + dslDeclarationsIn(source)

    /** 兼容形态：`override val minApi: Int = <字面量>`（注释已被 [ModCheckSources] 剥掉）。 */
    private fun overrideDeclarationsIn(source: SourceFile): List<MinApiDeclaration> =
        source.code.mapNotNull { line ->
            val match = MIN_API_DECL.find(line.text) ?: return@mapNotNull null
            val rest = line.text.substring(match.range.last + 1)
            MinApiDeclaration(
                file = source.label,
                line = line.number,
                value = MIN_API_LITERAL.find(rest)?.groupValues?.get(VALUE_GROUP)?.toIntOrNull(),
            )
        }

    /**
     * 主形态：逐个 `modDeclarations(` 调用配平到闭括号、把实参表拼成文本，抽 `minApi = <字面量>`。
     *
     * 跨行调用也认（行号按实参文本里 `minApi` 之前出现的换行数回推）；一个调用里没有该命名实参
     * 就不产生声明（= 作者没设闸）；有命名实参但右侧不是字面量则产生 value=null（⇒ 整面静默）。
     */
    private fun dslDeclarationsIn(source: SourceFile): List<MinApiDeclaration> {
        val code = source.code
        val declarations = mutableListOf<MinApiDeclaration>()
        var index = 0
        while (index < code.size) {
            val call = DSL_CALL.find(code[index].text)
            val arguments = call?.let { match -> argumentsOf(code, index, match.range.last + 1) }
            if (arguments == null) {
                index += 1
                continue
            }
            val argument = DSL_MIN_API.find(arguments.text)
            if (argument != null) {
                val offset = arguments.text.substring(0, argument.range.first).count { it == '\n' }
                declarations += MinApiDeclaration(
                    file = source.label,
                    line = code[index + offset].number,
                    value = INT_LITERAL.find(argument.groupValues[VALUE_GROUP].trim())
                        ?.value
                        ?.toIntOrNull(),
                )
            }
            index = arguments.closeLine + 1
        }
        return declarations
    }

    /**
     * 从 [openLine] 的 [openColumn]（指向调用 `(` 之后）逐字符走，配平地拼出实参表文本。
     *
     * 返回文本 + 闭括号所在行下标；到文件尾还没配平返回 null（语法残缺的源码，本面不报）。
     */
    private fun argumentsOf(code: List<CodeLine>, openLine: Int, openColumn: Int): CallArguments? {
        val text = StringBuilder()
        var depth = 1
        var lineIndex = openLine
        var column = openColumn
        while (lineIndex < code.size) {
            val source = code[lineIndex].text
            while (column < source.length) {
                val ch = source[column]
                if (ch == '(') depth += 1
                if (ch == ')') depth -= 1
                if (depth == 0) return CallArguments(text.toString(), lineIndex)
                text.append(ch)
                column += 1
            }
            text.append('\n')
            lineIndex += 1
            column = 0
        }
        return null
    }

    /** 逐条判据分派：负数 ⇒ ERROR，高于引擎版本 ⇒ WARN，其余（0 / == VERSION / 更低）不报。 */
    private fun checkDeclaration(
        declaration: MinApiDeclaration,
        engineVersion: Int,
        findings: MutableList<Finding>,
    ) {
        val value = declaration.value ?: return
        when {
            value < NO_GATE -> findings += negative(declaration, value)
            value > engineVersion -> findings += future(declaration, value, engineVersion)
        }
    }

    /** 负的 min-api：装配期 `require(minApi >= 0)` 当场抛，整个 mod 载不进来，必然失效。 */
    private fun negative(declaration: MinApiDeclaration, value: Int): Finding = Finding(
        severity = Severity.ERROR,
        file = declaration.file,
        line = declaration.line,
        message = "min-api 声明为负数：$value（装配期 ModuleDescriptor 的 require(minApi >= 0) 当场抛，" +
            "整个 mod 载不进来，必然失效；0 = 不设闸，正整数 = 要求的最低作者面版本）",
    )

    /** 高于当前引擎的 min-api：当前装不进来，等引擎升到该版本即可，属预留 ⇒ 只 WARN。 */
    private fun future(declaration: MinApiDeclaration, value: Int, engineVersion: Int): Finding = Finding(
        severity = Severity.WARN,
        file = declaration.file,
        line = declaration.line,
        message = "min-api 高于当前引擎：minApi = $value，ModApi.VERSION = $engineVersion" +
            "（本模块要求引擎作者面 ≥ v$value，当前引擎装不进来；要等引擎升到 v$value 才能装载，" +
            "故只 WARN；若不是有意预留，改成 ≤ v$engineVersion）",
    )
}
