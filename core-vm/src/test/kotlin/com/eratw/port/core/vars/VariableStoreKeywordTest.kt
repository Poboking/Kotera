package com.eratw.port.core.vars

import com.eratw.port.core.compat.NicknameKeywordPolicy
import com.eratw.port.core.compat.RelationMasternamePolicy
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.BuiltinEvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * B5 关键字面闸（ENGINE-COMPAT，CONTRACT-ENGCOMPAT §4.5；由 B0 基线钉翻面而来）。
 *
 * ## 原版事实（四树 C# 逐处取证）
 * - GETNUM 失败形状是 **返回 -1，不抛 CodeEE**：snake `Creator.Method.cs:5290-5294`
 *   （`TryKeywordToInteger` 失败分支），em/v24 同形（v24 `ConstantData.cs:666-681`）。
 * - GETNUM 关键字受理面：四树 `GetKeywordDictionary` 都把 NAME/CALLNAME/RELATION 映射到
 *   `relationDic`（v24 `ConstantData.cs:869-877`、em `:1070-1076`、snake `:1449-1455`、
 *   cs `:1086-1092`）；**只有 v24 额外受理 NICKNAME/MASTERNAME**（v24 `:876-877`）。
 * - `relationDic` 内容：snake/cs 收 name/callname/nickname（snake `ConstantData.cs:854-863`、
 *   cs `:743-753`，**无 mastername**）；em 与 v24 再多 mastername（em `:711-712`、v24 `:640-641`）。
 *   四名共用一个字典 ⇒ v24 的 `GETNUM(MASTERNAME, "Alice")` 命中 Name 列也返回编号。
 * - 字符串**下标**（`NAME:"x"` 形态）在原版一律 CodeEE：relationDic 对 NAME/CALLNAME 等
 *   allowIndex=-1（GETNUM 专用），index>=0 命中「配列変数…の要素を文字列で指定することはできません」
 *   （v24 `ConstantData.cs:884-894`）；RELATION 的 allowIndex=1 才许下标。
 *
 * ## 端口分档（B5 之后）
 * - 缺省档（snake）：NAME/CALLNAME/RELATION 查得 NO；NICKNAME/MASTERNAME 路由关闭 ⇒ -1；
 *   mastername 不进表 ⇒ `RELATION:MASTER:"御主人様"` CodeEE。
 * - v24 档（两条 facet 全开）：五个关键字都查得 NO，mastername 进表。
 * - em 档（INCLUDED + NAME_CALLNAME_ONLY）：mastername 进表 ⇒ RELATION 面可达，
 *   但 GETNUM 的 NICKNAME/MASTERNAME 仍 -1；`GETNUM(NAME, "アリ")` 仍命中（共用一个字典）。
 */
class VariableStoreKeywordTest {
    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun env(config: VarConfig = VarConfig()): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore(config = config)
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store) to store
    }

    private fun template(no: Long = 5L) = CharacterTemplate(
        no = no, name = "Alice", callname = "アリス", nickname = "アリ", mastername = "御主人様",
    )

    private fun getnum(ctx: BuiltinEvalContext, varName: String, keyword: String): Long {
        val ref = VarRef(varName, listOf(IntLiteral(0)), operandType = OperandType.INT)
        val v = ctx.callFunctionRef("GETNUM", listOf(ref, com.eratw.port.core.expr.StrLiteral(keyword)))
        return (v as Value.IntV).v
    }

    @Test
    fun `B5 GETNUM 缺省档 NAME、CALLNAME、RELATION 查得 NO 而 NICKNAME、MASTERNAME 仍 -1`() {
        val (ctx, store) = env()
        store.registerTemplate(template())
        // 原版 snake：GetKeywordDictionary 只有 NAME/CALLNAME 两个关系 case（:1454-1455）⇒ 后两名 -1。
        assertEquals(5L, getnum(ctx, "NAME", "Alice"))
        assertEquals(5L, getnum(ctx, "CALLNAME", "アリス"))
        assertEquals(5L, getnum(ctx, "RELATION", "Alice"))
        assertEquals(-1L, getnum(ctx, "NICKNAME", "アリ"))
        assertEquals(-1L, getnum(ctx, "MASTERNAME", "御主人様"))
    }

    @Test
    fun `B5 GETNUM v24 档五名全受理且共用一个字典`() {
        val (ctx, store) = env(
            VarConfig(
                nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
        )
        store.registerTemplate(template())
        assertEquals(5L, getnum(ctx, "NAME", "Alice"))
        assertEquals(5L, getnum(ctx, "CALLNAME", "アリス"))
        assertEquals(5L, getnum(ctx, "RELATION", "Alice"))
        assertEquals(5L, getnum(ctx, "NICKNAME", "アリ"))
        assertEquals(5L, getnum(ctx, "MASTERNAME", "御主人様"))
        // 四名共用 relationDic：MASTERNAME 关键字面命中 Name 列也返回编号（C# :874-877）。
        assertEquals(5L, getnum(ctx, "MASTERNAME", "Alice"))
        assertEquals(-1L, getnum(ctx, "MASTERNAME", "不存在"))
    }

    @Test
    fun `B5 GETNUM em 档 mastername 只经 RELATION 面可达`() {
        val (ctx, store) = env(
            VarConfig(relationMastername = RelationMasternamePolicy.INCLUDED),
        )
        store.registerTemplate(template())
        // em 的 relationDic 收 mastername（:711-712），但 GetKeywordDictionary 无
        // NICKNAME/MASTERNAME case（:1070-1076）⇒ GETNUM 面 -1。
        assertEquals(-1L, getnum(ctx, "MASTERNAME", "御主人様"))
        assertEquals(-1L, getnum(ctx, "NICKNAME", "アリ"))
        // 同一张表在 NAME 面可见（字典是整张，不按列过滤）。
        assertEquals(5L, getnum(ctx, "NAME", "アリ"))
        assertEquals(5L, store.keywordToInteger("RELATION", 1, "御主人様"))
    }

    @Test
    fun `B5 GETNUM 静态 CSV 关键字正控（防闸空转）`() {
        val (ctx, store) = env()
        store.registry.csvTable("ABL").set(2, "体力")
        assertEquals(2L, getnum(ctx, "ABL", "体力"))
        assertEquals(-1L, getnum(ctx, "ABL", "不存在"))
    }

    @Test
    fun `B5 字符串下标 NAME 抛 CodeEE 与原版同形（不是缺口）`() {
        val (_, store) = env(
            VarConfig(
                nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME,
                relationMastername = RelationMasternamePolicy.INCLUDED,
            ),
        )
        store.registerTemplate(template())
        // 原版 allowIndex=-1（GETNUM 专用）⇒ 下标形态抛 CanNotSpecifiedByString
        // （v24 ConstantData.cs:884-894）；端口 registry.keywordIndex 同形。
        assertFailsWith<CodeEE> { store.keywordToInteger("NAME", 0, "Alice") }
        assertFailsWith<CodeEE> { store.keywordToInteger("CALLNAME", 0, "アリス") }
        assertFailsWith<CodeEE> { store.keywordToInteger("NICKNAME", 0, "アリ") }
        assertFailsWith<CodeEE> { store.keywordToInteger("MASTERNAME", 0, "御主人様") }
    }

    @Test
    fun `B5 RELATION 下标走模板表且 dim 校验与原版 allowIndex=1 同形`() {
        val (_, store) = env()
        store.registerTemplate(template())
        assertEquals(5L, store.keywordToInteger("RELATION", 1, "Alice"))
        assertEquals(5L, store.keywordToInteger("RELATION", 1, "アリス"))
        assertEquals(5L, store.keywordToInteger("RELATION", 1, "アリ"))
        assertFailsWith<CodeEE> { store.keywordToInteger("RELATION", 0, "Alice") }
        assertFailsWith<CodeEE> { store.keywordToInteger("RELATION", 1, "不存在") }
    }

    @Test
    fun `B5 缺省档模板表不收 mastername 与 snake 同形`() {
        val (_, store) = env()
        store.registerTemplate(template())
        assertNotNull(store.templateByName("Alice"))
        assertNotNull(store.templateByName("アリス"))
        assertNotNull(store.templateByName("アリ"))
        // snake relationDic 无 mastername（ConstantData.cs:854-863）⇒ 缺省档不收。
        assertNull(store.templateByName("御主人様"))
        assertNull(store.relationIndex("御主人様"))
        assertFailsWith<CodeEE> { store.keywordToInteger("RELATION", 1, "御主人様") }
    }

    @Test
    fun `B5 INCLUDED 档模板表收 mastername 且先到先得`() {
        val (_, store) = env(VarConfig(relationMastername = RelationMasternamePolicy.INCLUDED))
        store.registerTemplate(template())
        assertEquals(5L, store.relationIndex("御主人様"))
        assertEquals(mapOf("Alice" to 5, "アリス" to 5, "アリ" to 5, "御主人様" to 5), store.relationKeywords())
        // 原版 relationDic 的 `ContainsKey` = 先到先得（ConstantData.cs:640-641）。
        store.registerTemplate(template(no = 6L))
        assertEquals(5L, store.relationIndex("御主人様"))
    }

    @Test
    fun `B5 反向证明 强制 WITH 但 EXCLUDED 时 mastername 仍不可达且不伪造编号`() {
        // 反例配置：路由打开但建表不收 mastername。GETNUM(MASTERNAME) 必须 -1（不是 0/5），
        // 证明两 facet 各自独立生效，而不是一个开关顺手带出另一个。
        val (ctx, store) = env(
            VarConfig(nicknameKeywords = NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME),
        )
        store.registerTemplate(template())
        assertEquals(-1L, getnum(ctx, "MASTERNAME", "御主人様"))
        assertEquals(5L, getnum(ctx, "MASTERNAME", "Alice"))
        assertFailsWith<CodeEE> { store.keywordToInteger("RELATION", 1, "御主人様") }
    }
}