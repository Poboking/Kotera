package com.eratw.port.core.proc

import com.eratw.port.core.compat.EngineProfileData
import com.eratw.port.core.compat.NicknameKeywordPolicy
import com.eratw.port.core.compat.RelationMasternamePolicy
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimFoldArg
import com.eratw.port.core.vars.DimFunctionFolders
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ENGC-4 关系关键字「装载期折叠 ↔ 运行期求值」同值闸（B5）。
 *
 * ## 为什么要有这一闸
 * `GETNUM` 是 `CanRestructure` 函数方法（v24 `FunctionMethodTerm.cs:35-56`，
 * `UniqueRestructure` 只折第二参 `GameData/Function/Creator.Method.cs:1664-1668`）：
 * 装载期 `#DIM X = GETNUM(NAME, "Alice")` 会直接折成常量写进侧车，运行期不再求值。
 * 若折叠面与运行期两套口径，同一行声明在 `.erbc` 侧车与源码直编两条路径上读出不同值，
 * 属于「玩家玩到用点才发现」的隐性分叉。故本闸对四引擎逐档对拍：
 * **能折就必须与运行期同值；运行期 -1 时绝不允许折出正数；也不允许拒绝折叠**
 * （原版路由缺席 = 字典 null ⇒ `TryKeywordToInteger` false ⇒ 折 -1 成功，
 * v24 `ConstantData.cs:666-680` + `Creator.Method.cs:1655-1662`）。
 *
 * ## C# 语义模型（本闸的期望值由这三点独立算出）
 * - 字典内容（relationDic）= name / callname / nickname，v24 与 em 另含 mastername
 *   （v24 `ConstantData.cs:634-641`，em `:705-712`，snake `:854-863` 无 mastername）。
 * - 路由面（GetKeywordDictionary）= NAME / CALLNAME / RELATION 四树都有；
 *   NICKNAME / MASTERNAME 只有 v24 有 case（v24 `ConstantData.cs:874-881`；
 *   em `:1075-1080`、snake `:1454-1459`、cs `:1091-1096` 只有前两名）。
 * - 命中整张字典的任一列 ⇒ 角色 NO（四名共用一个字典，v24 `ConstantData.cs:878`）；
 *   未命中或未路由 ⇒ -1。
 */
class KeywordFoldParityTest {
    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private val template = CharacterTemplate(
        no = 5L, name = "Alice", callname = "アリス", nickname = "アリ", mastername = "御主人様",
    )

    private fun storeOf(config: VarConfig): VariableStore =
        VariableStore(config = config).also { it.registerTemplate(template) }

    private fun ctxOf(store: VariableStore): BuiltinEvalContext {
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store)
    }

    private fun runtime(ctx: BuiltinEvalContext, varName: String, keyword: String): Long {
        val ref = VarRef(varName, listOf(IntLiteral(0)), operandType = OperandType.INT)
        return (ctx.callFunctionRef("GETNUM", listOf(ref, StrLiteral(keyword))) as Value.IntV).v
    }

    private fun fold(tables: Map<String, Map<String, Int>>, varName: String, keyword: String): Long? =
        DimFunctionFolders.pureStatic(keywordTables = tables)
            .foldInt("GETNUM", listOf(DimFoldArg.IdentArg(varName), DimFoldArg.StrArg(keyword)))

    private val names = listOf("NAME", "CALLNAME", "RELATION", "NICKNAME", "MASTERNAME")
    private val keywords = listOf("Alice", "アリス", "アリ", "御主人様", "存在しない")

    /** C# 模型：字典内容 × 路由面 ⇒ 期望值。 */
    private fun expected(
        nicknameKeywords: NicknameKeywordPolicy,
        relationMastername: RelationMasternamePolicy,
        varName: String,
        keyword: String,
    ): Long {
        val dict = mutableSetOf("Alice", "アリス", "アリ")
        if (relationMastername == RelationMasternamePolicy.INCLUDED) dict += "御主人様"
        val routed = varName in setOf("NAME", "CALLNAME", "RELATION") ||
            (varName in setOf("NICKNAME", "MASTERNAME") &&
                nicknameKeywords == NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME)
        return if (routed && keyword in dict) 5L else -1L
    }

    private fun assertParity(
        nicknameKeywords: NicknameKeywordPolicy,
        relationMastername: RelationMasternamePolicy,
    ) {
        val store = storeOf(VarConfig(nicknameKeywords = nicknameKeywords, relationMastername = relationMastername))
        val ctx = ctxOf(store)
        val tables = relationFoldTables(store, nicknameKeywords)
        for (varName in names) {
            for (keyword in keywords) {
                val want = expected(nicknameKeywords, relationMastername, varName, keyword)
                val rt = runtime(ctx, varName, keyword)
                val fd = fold(tables, varName, keyword)
                assertEquals(want, rt, "$varName(\"$keyword\") 运行期")
                assertEquals(want, fd, "$varName(\"$keyword\") 折叠面（必须与运行期同值，且不许拒绝折叠）")
            }
        }
    }

    @Test
    fun `snake 档 折叠与运行期逐格同值`() {
        assertParity(NicknameKeywordPolicy.NAME_CALLNAME_ONLY, RelationMasternamePolicy.EXCLUDED)
    }

    @Test
    fun `em 档 折叠与运行期逐格同值（mastername 只可经 RELATION 下标）`() {
        assertParity(NicknameKeywordPolicy.NAME_CALLNAME_ONLY, RelationMasternamePolicy.INCLUDED)
        // 点名行：em 的 relationDic 收 mastername（ConstantData.cs:711-712），但 GETNUM
        // 不路由 MASTERNAME（:1075-1080）⇒ 运行期 -1；RELATION 反查得 5。
        val store = storeOf(
            VarConfig(
                nicknameKeywords = NicknameKeywordPolicy.NAME_CALLNAME_ONLY,
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
        )
        val ctx = ctxOf(store)
        assertEquals(5L, runtime(ctx, "RELATION", "御主人様"))
        assertEquals(-1L, runtime(ctx, "MASTERNAME", "御主人様"))
    }

    @Test
    fun `v24 档 折叠与运行期逐格同值（四名共用一个字典）`() {
        assertParity(NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME, RelationMasternamePolicy.INCLUDED)
        val store = storeOf(
            VarConfig(
                nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
        )
        val ctx = ctxOf(store)
        // 四名共用 relationDic（ConstantData.cs:878）⇒ 跨列也命中。
        assertEquals(5L, runtime(ctx, "MASTERNAME", "Alice"))
        assertEquals(5L, runtime(ctx, "NICKNAME", "御主人様"))
    }

    @Test
    fun `未路由名字的折叠键必须登记为空表而不是整表或缺失`() {
        val store = storeOf(
            VarConfig(
                nicknameKeywords = NicknameKeywordPolicy.NAME_CALLNAME_ONLY,
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
        )
        val tables = relationFoldTables(store, NicknameKeywordPolicy.NAME_CALLNAME_ONLY)
        // 整表 = 折出正数（运行期 -1）；缺键 = 文件夹返回 null ⇒ 装载期拒绝折叠（原版是折 -1）。
        assertNotNull(tables["NICKNAME"])
        assertTrue(tables.getValue("NICKNAME").isEmpty(), "NICKNAME 未路由时必须登记空表")
        assertNotNull(tables["MASTERNAME"])
        assertTrue(tables.getValue("MASTERNAME").isEmpty(), "MASTERNAME 未路由时必须登记空表")
        // 已路由的名字拿整表（含 mastername 列，em 档）。
        assertEquals(5, tables.getValue("RELATION")["御主人様"])
        assertEquals(5, tables.getValue("NAME")["Alice"])
    }

    @Test
    fun `v24 档两个未路由名字外的表体与整表一致（反向证明闸不空转）`() {
        val store = storeOf(
            VarConfig(
                nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
        )
        val tables = relationFoldTables(store, NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME)
        // 若路由闸被误删（照抄整表给所有名字），snake 档就会折出 5 而运行期 -1；
        // 这里证明「闸开」时整表确实有 mastername 键，闸的裁剪不是空转。
        assertEquals(5, tables.getValue("NICKNAME")["御主人様"])
        assertEquals(5, tables.getValue("MASTERNAME")["Alice"])
        assertEquals(tables.getValue("NAME"), tables.getValue("MASTERNAME"))
    }

    // ==================================================================
    // ENGCPL-L2：两处关键字消费点同源（E4）
    // ==================================================================

    @Test
    fun `折叠表键集恰为生成物 routed 与 gated 的并集且运行期同源`() {
        // 名单单源 = input yaml `dialect.getnum_nickname_mastername.keywords` → 生成物常量：
        // 折叠面（本文件的 relationFoldTables）与运行期（VarMethods.keywordIndexOf）读同一对
        // 常量，任一侧改回字面表都会先在这里红。
        val routed = EngineProfileData.RELATION_ROUTED_KEYWORDS
        val gated = EngineProfileData.RELATION_GATED_KEYWORDS
        assertTrue(routed.isNotEmpty() && gated.isNotEmpty(), "生成物名单不得为空")
        assertEquals((routed + gated).toSet(), (routed.toSet() + gated.toSet()), "两组不得重叠")
        for (policy in NicknameKeywordPolicy.values()) {
            val store = storeOf(
                VarConfig(nicknameKeywords = policy, relationMastername = RelationMasternamePolicy.INCLUDED),
            )
            val ctx = ctxOf(store)
            val tables = relationFoldTables(store, policy)
            assertEquals(
                (routed + gated).toSet(),
                tables.keys,
                "$policy：折叠面的键集必须恰为 routed ∪ gated",
            )
            val gateOpen = policy == NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME
            for (name in routed) {
                assertEquals(tables.getValue("NAME"), tables.getValue(name), "$policy：$name 无条件路由")
                assertEquals(5L, runtime(ctx, name, "Alice"), "$policy：运行期 $name 必须路由")
            }
            for (name in gated) {
                // 无 NICKNAME 能力档登记空表（折 -1），有能力档登记整表；两处与运行期同值。
                assertEquals(gateOpen, tables.getValue(name).isNotEmpty(), "$policy：$name 的门控登记")
                assertEquals(
                    if (gateOpen) 5L else -1L,
                    runtime(ctx, name, "Alice"),
                    "$policy：运行期 $name 必须与折叠面的门控同向",
                )
            }
        }
    }

    @Test
    fun `v24 档 gated 名单有表 缺省档登记空表（名单来自生成物）`() {
        val v24 = relationFoldTables(
            storeOf(
                VarConfig(
                    nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
                    relationMastername = RelationMasternamePolicy.INCLUDED,
                ),
            ),
            NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
        )
        for (name in EngineProfileData.RELATION_GATED_KEYWORDS) {
            assertEquals(5, v24.getValue(name)["御主人様"], "v24 的 $name 必须收主名（ConstantData.cs:634-641）")
        }
        val snake = relationFoldTables(
            storeOf(
                VarConfig(
                    nicknameKeywords = NicknameKeywordPolicy.NAME_CALLNAME_ONLY,
                    relationMastername = RelationMasternamePolicy.EXCLUDED,
                ),
            ),
            NicknameKeywordPolicy.NAME_CALLNAME_ONLY,
        )
        for (name in EngineProfileData.RELATION_GATED_KEYWORDS) {
            assertTrue(snake.getValue(name).isEmpty(), "snake 的 $name 未路由 ⇒ 登记空表折 -1")
        }
    }
}
