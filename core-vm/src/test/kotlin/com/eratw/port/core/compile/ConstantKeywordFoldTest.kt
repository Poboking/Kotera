package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.vars.SystemLayout
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 常量字符串下标的**解析期 Restructure** 行为锁（spec: `VariableStrArgTerm.cs:57-69`）。
 *
 * 原版装载期把 `ITEM:药` 折成 `SingleLongTerm`，产物里只有整数下标；移植侧此前把关键字
 * 名字编进 `.erbc`、运行期按名查设备盘上的 CSV 表，导致编译产物与数据面耦合。
 * 这里同时锁四件事：
 * - 解析产物里常量下标变成整数常量（[常量字符串下标在解析期折成整数]）；
 * - 撤掉查表回调即回到运行期查表结点（[撤掉解析器则保留运行期查表结点]，反向证明）；
 * - 动态下标不被折叠（[动态字符串下标不被折叠]）；
 * - 折叠后的程序在**没有关键字表**的变量池上也能跑（[折叠后运行期不再依赖盘上关键字表]），
 *   这正是 V2551A「`M_CFLAG.csv` 未定义关键字」不会再出现的根因。
 */
class ConstantKeywordFoldTest {

    /** 编译期表：`ITEM` 名字表第 3 位 = "药"。 */
    private fun registryWithItem(): VariableRegistry {
        val registry = VariableRegistry()
        val spec = SystemLayout.keywords.getValue("ITEM")
        val slot = SystemLayout.csvFiles.indexOfFirst {
            it.substringBefore('.').equals(spec.table, ignoreCase = true)
        }
        assertTrue(slot >= 0, "ITEM 表槽位必须在 SystemLayout.csvFiles 里")
        registry.csvTables[slot].set(3, "药")
        return registry
    }

    private fun lookup(resolver: ConstantKeywordResolver?): ((String, Int, String) -> Long?)? =
        resolver?.let { it::resolve }

    private fun parseAssign(source: String, resolver: ConstantKeywordResolver?): AssignStmt =
        assertIs(
            LineParser(config = ParserConfig(constantKeywordLookup = lookup(resolver)))
                .parseCodeLine(source, 1),
        )

    private fun compile(source: String, resolver: ConstantKeywordResolver?): Program {
        val parser = LineParser(config = ParserConfig(constantKeywordLookup = lookup(resolver)))
        val compiler = ErbProgramCompiler()
        compiler.compileFunction(FuncSig("MAIN", 0), listOf(parser.parseCodeLine(source, 1)))
        return compiler.build("MAIN")
    }

    // ------------------------------------------------------------------
    // 解析器（ConstantKeywordResolver）
    // ------------------------------------------------------------------

    @Test
    fun `系统关键字变量走运行期同一个查表函数`() {
        val r = ConstantKeywordResolver(registryWithItem(), emptyMap())
        assertEquals(3L, r.resolve("ITEM", 0, "药"))
        assertEquals(3L, r.resolve("item", 0, "药"), "变量名大小写不敏感")
        assertNull(r.resolve("ITEM", 0, "不存在的药"), "未定义关键字不折")
        assertNull(r.resolve("ITEM", 1, "药"), "dim 不合法不折（registry.keywordIndex 同判）")
    }

    @Test
    fun `ERD 用户变量按 install 的键口径折`() {
        // 运行期 install 的键 = NAME（1 维） / NAME@n（多维）；这里两种形态都验到。
        val tables = mapOf(
            "M_CFLAG" to mapOf("后宫居住标记" to 4),
            "M_ITEM@2" to mapOf("大角虫养成外骨骼" to 403),
        )
        val r = ConstantKeywordResolver(null, tables)
        assertEquals(4L, r.resolve("M_CFLAG", 0, "后宫居住标记"))
        assertEquals(4L, r.resolve("M_CFLAG", 1, "后宫居住标记"), "CHARADATA 1 维声明的 dim=1 同键 NAME")
        assertEquals(403L, r.resolve("M_ITEM", 1, "大角虫养成外骨骼"))
        assertNull(r.resolve("M_CFLAG", 0, "没这个词"))
    }

    @Test
    fun `候选表冲突或缺表时不折`() {
        val conflict = mapOf(
            "M_CFLAG" to mapOf("后宫居住标记" to 4),
            "M_CFLAG@1" to mapOf("后宫居住标记" to 9),
        )
        val r = ConstantKeywordResolver(null, conflict)
        assertNull(r.resolve("M_CFLAG", 0, "后宫居住标记"), "两个候选给出不同下标 ⇒ 绝不猜")
        assertNull(ConstantKeywordResolver(null, emptyMap()).resolve("M_CFLAG", 0, "后宫居住标记"))
    }

    @Test
    fun `RELATION 与空关键字保持运行期语义`() {
        val r = ConstantKeywordResolver(registryWithItem(), mapOf("RELATION" to mapOf("咲夜" to 7)))
        assertNull(r.resolve("RELATION", 1, "咲夜"), "RELATION 走角色模板表，编译期不折")
        assertNull(r.resolve("ITEM", 0, ""), "空关键字运行期抛 KeywordCanNotEmpty，不折")
    }

    @Test
    fun `candidateTableKeys 与 install 的键生成同口径`() {
        assertEquals(listOf("FOO", "FOO@1"), UserKeywordTables.candidateTableKeys("FOO", 0))
        assertEquals(listOf("FOO", "FOO@1", "FOO@2"), UserKeywordTables.candidateTableKeys("FOO", 1))
        assertEquals("FOO@2", UserKeywordTables.tableKey("FOO", 2))
    }

    // ------------------------------------------------------------------
    // 解析产物 + 运行期行为
    // ------------------------------------------------------------------

    @Test
    fun `常量字符串下标在解析期折成整数`() {
        val stmt = parseAssign("ITEM:药 = 7", ConstantKeywordResolver(registryWithItem(), emptyMap()))
        val index = assertIs<IntLiteral>(stmt.dest.indices.single())
        assertEquals(3L, index.value, "产物里只该留下整数下标，不再有关键字名")
    }

    @Test
    fun `撤掉解析器则保留运行期查表结点（反向证明）`() {
        val stmt = parseAssign("ITEM:药 = 7", null)
        assertIs<KeywordIndexArg>(stmt.dest.indices.single())
    }

    @Test
    fun `动态字符串下标不被折叠`() {
        // 下标是字符串变量而非字面量 ⇒ 原版同样保留运行期查表（Restructure 的 SingleTerm 判据）。
        val stmt = parseAssign(
            "ITEM:LOCALS = 7",
            ConstantKeywordResolver(registryWithItem(), emptyMap()),
        )
        assertIs<KeywordIndexArg>(stmt.dest.indices.single())
    }

    @Test
    fun `折叠后运行期不再依赖盘上关键字表`() {
        // 变量池的 registry 是空的（模拟设备盘上 CSV 残缺）：若未折叠，运行期
        // keywordToInteger("ITEM", 0, "药") 会抛「ITEM 中未定义关键字」；折成整数后
        // 直接读 ITEM[3]，不查表 ⇒ 不抛且读到预置值。
        val p = compile("RESULT = ITEM:药", ConstantKeywordResolver(registryWithItem(), emptyMap()))
        val store = VariableStore()
        // 折叠生效的判据 = 这一行在空表上**执行到底而不抛**：未折叠时运行期必然调
        // keywordToInteger("ITEM", 0, "药") 抛 CodeEE（同一装置的反向证明见下面）。
        // 折成的整数下标值由 [常量字符串下标在解析期折成整数] 的 AST 断言单独锁定。
        ProcessRuntime(
            p,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "MAIN", defIndex = 0))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()

        // 反向：不折叠的同一行在这个空表变量池上必然抛「未定义关键字」。
        val old = compile("RESULT = ITEM:药", null)
        val empty = VariableStore()
        assertFailsWith<com.eratw.port.core.err.CodeEE> {
            ProcessRuntime(
                old,
                empty,
                FunctionTable(listOf(FuncMeta(index = 0, key = "MAIN", defIndex = 0))),
                VmConfig(maxStepsPerRun = 50_000L),
            ).execute()
        }
    }
}
