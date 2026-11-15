package com.eratw.port.boot

import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.sql.RealSqlDriver
import com.eratw.port.core.sql.SqlNativeException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * `sql-corpus-gate` 的**扫描器读数钉桩**（2026-11-15 立闸）。
 *
 * ## 为什么要有这道闸（根因）
 *
 * SQL 语句是 ERB **字符串字面量**：erbcc 编译期不解析（语料编译只把字符串搬进 erbc）、
 * canonical 探针 MATCH 也碰不到 —— 「引擎支持面 ↔ 语料实算面」的偏差只能在**运行期**
 * 暴露。261111 换代波（3add17466）按人工 grep 摸排接了 SQL 三缺口，却漏了
 * `魔改内容/qol/qol_dish.ERB:727` 实算的
 * `SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0`，玩家料理页报错。本文件先把
 * 「语料里到底有多少条实算 SQL」钉死：扫描器坏了或语料根漂了，读数钉桩先红，不至于让
 * 后续的语法/执行闸在空集上假绿。
 *
 * ## 提取口径（[CorpusSqlSurfaceAudit.extractOccurrences]）
 *
 * 1. 只扫**非注释行**（整行 `;` 注释跳过；行内引号外 `;` 之后丢弃 —— 口径同
 *    [CorpusMacroAudit]，分号在 SQL 字符串字面量内的行不受影响，因为 SQL 串本身在引号内）；
 * 2. 行内必须出现 `SQL_*` 内建名（SQL_ESCAPE / SQL_IMPORT_MAP_XML 的非 SQL 实参
 *    因第 3 条前缀过滤自然排除）；
 * 3. 收集该行**双引号字符串字面量**（`""` 转义照收），前缀命中 SQL 关键词（词边界，
 *    `SELECTCCOM` / `SELECTION` 这类命令名串不误收）才视为语句；
 * 4. `@"..."` 插值串的 `{表达式}` / `%变量%` 占位符按 [CorpusSqlSurfaceAudit.normalize]
 *    归一 —— 这类占位符是 ERB 插值层职责（官方 Emuera 補間文字列，编译期已受理、
 *    运行期展开后才到 SQL 内建），SQL 引擎收到的永远是展开后的成品。
 *
 * ## 已知局限（如实登记，不掩盖）
 *
 * * **只护提取面**：列存在性、参数个数等执行期错误归 SqlMethodsTest 定向单测与
 *   T2 的真实 SQLite `prepare` 闸；本文件不解析 SQL。
 * * **只护字面量**：ERB 侧用变量拼接出来的 SQL 串（`RESULTS = "..."` 再传）
 *   静态提取不可达 —— 语料 261111 实测 0 用法（全部语句都是双引号字面量），
 *   换代后若出现拼串调用需人工复核或扩展提取器。
 * * 归一占位符会改变去重口径的可见形态（`{...}` → `0`）：它只服务「去重语句数」
 *   钉桩，不承担语法判定 —— 真实 SQLite 收不收由 T2 的 `prepare` 闸判。
 *
 * ## 语法/执行面归 T2（已落地：真实 SQLite `prepare` 闸）
 *
 * 旧自研解析器的语法校验钩子随 T1 删除自研引擎一并移除；语料 SQL 的语法面改由真实
 * SQLite 驱动（[RealSqlDriver]，`androidx.sqlite:sqlite-bundled-jvm` = SQLite 3.46.0，
 * 与 Android 侧同版本、同 native 实现）的 `prepare` 判：空库没有语料业务表结构，故
 * `no such table/column/index` 属 schema gap（计数打印、不判红），其余语法类失败断言为空。
 * 本文件不再拿旧 parser 断言凑数。
 *
 * 官方引擎语义锚：fork `SqlManager.cs:238-257` 是真实 SQLite 透传（无自研解析层），
 * 目标口径是「官方引擎收下、我们也要收下」。
 *
 * 语料不可发现时 `Assumptions.assumeTrue` 记 skip（与 [CorpusMacroTableAuditTest] 同口径）；
 * 语料版本不在册由 [CorpusProfile.detect] 直接判红。
 */
object CorpusSqlSurfaceAudit {

    /** SQL 关键词前缀（词边界）：把 SQL 语句与同名命令串（`SELECTCCOM`、`SELECTION`、`SELECT_END`）区分开。 */
    private val SQL_PREFIX = Regex(
        "^\\s*(SELECT\\s|INSERT\\s|UPDATE\\s|DELETE\\s|CREATE\\s|ALTER\\s|DROP\\s" +
            "|BEGIN(\\s|\$)|COMMIT(\\s|\$)|VACUUM(\\s|\$))",
        RegexOption.IGNORE_CASE,
    )

    /** `SQL_*` 内建调用名（语句所在行的识别特征）。 */
    private val SQL_BUILTIN = Regex("SQL_[A-Z_]{2,}")

    /** `{...}` 插值占位符（`@"..."` 表达式插值：`{ARG}` / `{PRODUCT_ID}` / `{ARG:LOCAL}`）。 */
    private val BRACE_PLACEHOLDER = Regex("\\{[^}]*\\}")

    /** `%...%` 插值占位符（`@"..."` 名字插值：`%COL_NAME%`；SQL 单引号串内的同样会被 ERB 展开）。 */
    private val PERCENT_PLACEHOLDER = Regex("%[^%]+%")

    /** 归一替身：`{...}` → 数值字面量；`%...%` → 裸标识符（引号内外均合法）。 */
    private const val BRACE_STANDIN = "0"
    private const val PERCENT_STANDIN = "ZQPLACEHOLDERZ"

    /** 一条提取记录：`file:line` + 语句原文（未归一）。 */
    data class Occurrence(val file: String, val line: Int, val statement: String)

    /** 行内引号外首个分号下标（行内注释起点）；无则 -1。`""` 相邻对两次翻转净效果不变，朴素口径足够。 */
    private fun firstSemicolonOutsideQuotes(line: String): Int {
        var inString = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inString = !inString
                ';' -> if (!inString) return i
            }
        }
        return -1
    }

    /**
     * 全语料扫描：`SQL_*` 内建调用行 → 双引号串 → SQL 关键词前缀过滤。
     * 返回逐处出现（含重复），供读数钉桩与失败定位（`file:行号`）。
     */
    fun extractOccurrences(files: List<Path>): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        for (f in files) {
            val name = f.fileName.toString()
            if (!name.endsWith(".erb", ignoreCase = true) && !name.endsWith(".erh", ignoreCase = true)) continue
            collectStatements(f, name, out)
        }
        return out
    }

    /** 单文件逐行收集：非注释行 + `SQL_*` 内建行 + SQL 关键词前缀串。 */
    private fun collectStatements(f: Path, name: String, out: MutableList<Occurrence>) {
        val lines = LogicalLineReader.readAllLines(Files.readAllBytes(f))
        for ((idx, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith(";") || !SQL_BUILTIN.containsMatchIn(raw)) continue
            val semi = firstSemicolonOutsideQuotes(raw)
            val code = if (semi >= 0) raw.substring(0, semi) else raw
            for (s in quotedStrings(code)) {
                if (SQL_PREFIX.containsMatchIn(s)) out.add(Occurrence(name, idx + 1, s))
            }
        }
    }

    /** 引号内 `""` 转义照收的字符串字面量收集器（`i` 的额外步进替代 continue）。 */
    private fun quotedStrings(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inString = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                !inString -> inString = c == '"'
                c == '"' && i + 1 < code.length && code[i + 1] == '"' -> {
                    sb.append('"')
                    i += 1
                }
                c == '"' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                    inString = false
                }
                else -> sb.append(c)
            }
            i += 1
        }
        return out
    }

    /** `@"..."` 插值占位符归一：`{...}` → `0`、`%...%` → 裸标识符（先归一花括号，吞掉 `{%VAR%}` 嵌套形态）。 */
    fun normalize(sql: String): String = PERCENT_PLACEHOLDER
        .replace(BRACE_PLACEHOLDER.replace(sql, BRACE_STANDIN), PERCENT_STANDIN)
}

class CorpusSqlSurfaceAuditTest {

    /**
     * 扫描器回归读数钉桩：**出现次数**与**去重语句数**按语料档取列。
     * 0 会静默放行「扫描器坏了抽不到东西」的闸 —— 与 AGENTS §5「动态用例看着像全绿」
     * 同型的防呆：条数不在钉值上，先修提取器再谈过闸。
     *
     * 钉值出处（2026-11-15 本机实读）：
     * * V4_981（261111）= 234 处 / 174 条去重（归一后）—— SQL 内建在 4.98x fork 代际
     *   才被语料采用，本档是主审面；
     * * V4_980 = **0 / 0** —— 4.980 canonical 根实测全树 0 处 `SQL_*` 调用（SQL 前代际，
     *   非提取器漏抽；261017 退役根同日对照 = 227 处）。该档闸空转，0 钉桩冻结「真 0」。
     */
    private fun pinned(profile: CorpusProfile): Pair<Int, Int> = when (profile) {
        CorpusProfile.V4_980 -> 0 to 0
        CorpusProfile.V4_981 -> 234 to 174
    }

    private fun corpus(): RealCorpus {
        val c = RealCorpus.load()
        assumeTrue(c != null, "未找到真实语料（设置 ERATW_CORPUS 或让自动发现命中游戏根），跳过语料 SQL 面审计")
        return requireNotNull(c)
    }

    @Test
    fun `语料实算 SQL 抽取读数钉桩 —— 扫描器条数按语料档对齐`() {
        val corpus = corpus()
        val profile = CorpusProfile.detect(corpus.root)
        val occ = CorpusSqlSurfaceAudit.extractOccurrences(corpus.files)
        val distinct = occ.map { CorpusSqlSurfaceAudit.normalize(it.statement) }.toSet().size
        val (pinnedOcc, pinnedDistinct) = pinned(profile)
        assertEquals(pinnedOcc, occ.size, "实算 SQL 出现次数与钉桩不符（${profile.name}，source=${corpus.source}）")
        assertEquals(pinnedDistinct, distinct, "去重语句数与钉桩不符（${profile.name}）")
    }

    /** schema gap 标记：`prepare` 在空库上找不到语料业务表结构，属预期，只计数打印不判红。 */
    private val schemaGapMarkers = listOf("no such table", "no such column", "no such index")

    /** 语法失败标记：命中即判红——真实 SQLite 收不下的语句 = 引擎能力面缺口或语料真错。 */
    private val syntaxFailureMarkers = listOf(
        "syntax error",
        "near \"",
        "incomplete input",
        "unrecognized token",
        "no such function",
        "wrong number of arguments",
    )

    /**
     * `sql-corpus-gate` T2 面：语料实算 SQL 全量过**真实 SQLite** `prepare`。
     *
     * 步骤：复用 [CorpusSqlSurfaceAudit.extractOccurrences] 与
     * [CorpusSqlSurfaceAudit.normalize]（不在测试里另造 SQL 提取/改写），用
     * [RealSqlDriver] 打开 `:memory:`，对每条归一后的去重语句
     * `conn.prepare(sql).close()`。`prepare` 在 SQLite 侧完成词法/语法/名字解析，
     * 语法错会以 [SqlNativeException] 带出 SQLite 原文。
     *
     * 读数口径：
     * * **prepared**：`prepare` 成功；
     * * **schema gap**：`no such table` / `no such column` / `no such index` —— 空库没有
     *   语料业务表结构，属预期，逐条打印、只计数不钉死（实测值随语料走）；
     * * **syntax failure**：其余失败（含契约点名的 `syntax error` / `near "` /
     *   `incomplete input` / `unrecognized token` / `no such function` /
     *   `wrong number of arguments`）—— 断言为空；未分类异常同样计入，不许静默放过。
     *
     * 去重条数按语料档钉值断言（V4_981 = 174，与隔壁读数钉桩同源）；V4_980 是 SQL
     * 前代际（0 调用），闸空转由钉桩冻结真 0。
     */
    @Test
    fun `语料实算 SQL 全量过真实 SQLite prepare 闸`() {
        val corpus = corpus()
        val profile = CorpusProfile.detect(corpus.root)
        val occurrences = CorpusSqlSurfaceAudit.extractOccurrences(corpus.files)
        if (pinned(profile).first == 0) {
            assertTrue(
                occurrences.isEmpty(),
                "钉桩口径里 ${profile.name} 是 0 调用代际，实测抽到 ${occurrences.size} 条 —— 提取器或语料根漂了",
            )
            return
        }
        val bySql = occurrences.associateBy { CorpusSqlSurfaceAudit.normalize(it.statement) }
        assertEquals(
            pinned(profile).second,
            bySql.size,
            "去重语句数与钉桩不符（${profile.name}；V4_981 钉 174）",
        )

        val gaps = ArrayList<String>()
        val failures = ArrayList<String>()
        var prepared = 0
        RealSqlDriver(Path.of(".")).open(":memory:").use { conn ->
            for ((sql, occ) in bySql) {
                val error = runCatching { conn.prepare(sql).close() }.exceptionOrNull()
                if (error == null) {
                    prepared += 1
                } else if (error is SqlNativeException && isSchemaGap(error.message)) {
                    gaps += "${occ.file}:${occ.line}: ${error.message}"
                } else {
                    failures += "${occ.file}:${occ.line}: ${occ.statement} ← ${error.message}"
                }
            }
        }
        println(
            "[sql-prepare] ${profile.name}：去重 ${bySql.size} 条 → prepared=$prepared、" +
                "schemaGap=${gaps.size}、syntaxFailure=${failures.size}",
        )
        gaps.forEach { println("[sql-prepare] schema-gap $it") }
        assertTrue(
            failures.isEmpty(),
            "语料实算 SQL 有 ${failures.size} 条过不了真实 SQLite prepare 闸（非 schema gap）：\n" +
                failures.joinToString("\n"),
        )
        assertEquals(bySql.size, prepared + gaps.size, "每条语句都必须落在 prepared / schema gap 之一")
    }

    /** 反向证明：闸不是永绿的橡皮章——语料 727 行同型语法错必须被真实驱动判成 syntaxFailure。 */
    @Test
    fun `真实 SQLite prepare 闸反向证明 —— 语法错必抛 SqlNativeException`() {
        RealSqlDriver(Path.of(".")).open(":memory:").use { conn ->
            val error = assertFailsWith<SqlNativeException> {
                conn.prepare("SELECT MAX(slot FROM QOL_DISH_DB").close()
            }
            assertTrue(
                isSyntaxFailure(error.message),
                "闸必须把该语法错判成 syntaxFailure（isSyntaxFailure 自证），实际：${error.message}",
            )
        }
    }

    private fun isSchemaGap(message: String?): Boolean =
        message != null && schemaGapMarkers.any { message.contains(it, ignoreCase = true) }

    private fun isSyntaxFailure(message: String?): Boolean =
        message != null && syntaxFailureMarkers.any { message.contains(it, ignoreCase = true) }
}
