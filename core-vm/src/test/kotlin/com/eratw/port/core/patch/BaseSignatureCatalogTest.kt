package com.eratw.port.core.patch

import com.eratw.port.core.code.OverrideSignatureException
import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RT2 M2-5 读侧定向单测（CONTRACT-RT2-M2-5 §3.2 判据 1/2/3/4/5）。
 *
 * 判据 3/4 复用核心既有 fail-closed 闸，不重造：用
 * [LinkCandidateAssembler.baseCandidates] 装 base 候选、[SymbolCatalog.build] 在
 * MODERN + declaredOverride 下比较两侧签名指纹（空指纹或不等 ⇒ OverrideSignatureException）。
 *
 * 反向证明②：把 [BaseSignatureCatalog.signatureHashOf] 改回恒空串 ⇒
 * `签名相等才准覆盖_相等放行_任一要素不等则拒` 的「相等放行」必红。
 */
class BaseSignatureCatalogTest {

    private val key = "FOO"

    private fun enc(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** 五要素齐全的规范串（与 erbcc 写侧 SignatureExport.render 同一格式）。 */
    private val good: String = buildString {
        append("SIG1|I,L").append(enc("2")).append(",R,0")
        append("|S,N").append(enc("C_YELLOW")).append(",r,2")
        append("|F,-,r,1")
        append("|U,L").append(enc("a b|,\"quote\"")).append(",r,0")
        append("|ret:S")
    }

    /** 只差一个字面量默认值（2 → 3）的规范串，其余逐字相同。 */
    private val different: String = buildString {
        append("SIG1|I,L").append(enc("3")).append(",R,0")
        append("|S,N").append(enc("C_YELLOW")).append(",r,2")
        append("|F,-,r,1")
        append("|U,L").append(enc("a b|,\"quote\"")).append(",r,0")
        append("|ret:S")
    }

    /** 不带签名段的旧侧车文本（BootMetadata 自身产出，保证主体段序合法）。 */
    private fun oldMetaText(): String = BootMetadata(
        functions = listOf(FuncMeta(index = 0, key = "MAIN")),
    ).withHeader(engineTag = "erbcc test", programSha256 = null).encodeText()

    /** 在旧档 `end` 之后追加尾部签名段。 */
    private fun withSection(vararg records: String): String = buildString {
        append(oldMetaText())
        append("signatures ").append(records.size).append('\n')
        for (r in records) append(r).append('\n')
    }

    private fun record(canonical: String, k: String = key): String = "signature $k $canonical"

    // ---- 闸驱动（复用核心既有 fail-closed，不重造）----

    private fun baseOf(query: (String) -> String) = LinkCandidateAssembler.baseCandidates(
        listOf(FuncMeta(index = 0, key = key)),
        "·base·",
        query,
    )

    private fun modOf(hash: String) = SymbolCandidate(
        meta = FuncMeta(index = 1, key = key),
        declaredOverride = true,
        ownerPackageId = "mod.pkg",
        signatureHash = hash,
    )

    private fun build(query: (String) -> String, modHash: String): SymbolCatalog =
        SymbolCatalog.build(baseOf(query) + modOf(modHash), ResolverPolicy.MODERN)

    /** 断言空指纹被拒，且报告可定位（函数名 + 「签名信息不完整」）。 */
    private fun assertRejected(table: Map<String, String>, modHash: String) {
        val ex = assertFailsWith<OverrideSignatureException> {
            build(BaseSignatureCatalog.signatureHashOf(table), modHash)
        }
        assertEquals(key, ex.key, "拒绝报告要点名函数键")
        assertTrue(ex.message!!.contains("签名信息不完整"), "消息可定位，实际: ${ex.message}")
    }

    // ------------------------------------------------------------------
    // 判据 1：读侧五要素逐项解析
    // ------------------------------------------------------------------

    @Test
    fun `解析五要素逐项相等`() {
        val table = BaseSignatureCatalog.parse(withSection(record(good)))
        assertEquals(1, table.size)
        val sig = BaseSignatureCatalog.decode(table.getValue(key))
        assertNotNull(sig, "规范串须解回五要素")
        assertEquals(4, sig.params.size)

        assertEquals('I', sig.params[0].type, "参数类型 INT")
        assertEquals('L', sig.params[0].default.kind, "字面量默认值类别")
        assertEquals("2", sig.params[0].default.text, "字面量默认值")
        assertTrue(sig.params[0].isRef, "REF 位真")
        assertEquals(0, sig.params[0].dims, "维度 0")

        assertEquals('S', sig.params[1].type, "参数类型 STR")
        assertEquals('N', sig.params[1].default.kind, "非字面量默认值类别")
        assertEquals("C_YELLOW", sig.params[1].default.text, "非字面量按 param-defaults 口径登记原文")
        assertFalse(sig.params[1].isRef, "REF 位假")
        assertEquals(2, sig.params[1].dims, "维度 2")

        assertEquals('F', sig.params[2].type, "参数类型 FLOAT")
        assertEquals('-', sig.params[2].default.kind, "无默认值类别")
        assertEquals(1, sig.params[2].dims, "维度 1")

        assertEquals('U', sig.params[3].type, "参数类型 UNKNOWN")
        assertEquals("a b|,\"quote\"", sig.params[3].default.text, "含特殊字符的字面量经 Base64 往返相等")
        assertEquals('S', sig.returnChannel, "返回通道 STR")
    }

    // ------------------------------------------------------------------
    // 判据 2：查询真值非空 / 归一 / 确定性
    // ------------------------------------------------------------------

    @Test
    fun `查询函数真值非空_归一_确定性`() {
        val table = BaseSignatureCatalog.parse(withSection(record(good, "foo")))
        assertEquals(1, table.size)
        assertTrue(key in table, "段内键须大写归一")
        val query = BaseSignatureCatalog.signatureHashOf(table)
        val hash = query(key)
        assertTrue(hash.isNotEmpty(), "真值非空")
        assertEquals(64, hash.length, "SHA-256 十六进制长度")
        assertEquals(hash, query("foo"), "小写键须归一命中")
        assertEquals(hash, BaseSignatureCatalog.fingerprint(good), "指纹 = 规范串 sha256")
        assertEquals(hash, query(key), "同内容两次查询相同")
        assertTrue(query("MISSING").isEmpty(), "未知键 fail-closed 返回空串")
    }

    // ------------------------------------------------------------------
    // 判据 3：签名不完整即拒覆盖（缺段 / 旧档 / 缺任一要素，逐条）
    // ------------------------------------------------------------------

    @Test
    fun `签名不完整即拒覆盖_缺段_旧档_缺要素逐条`() {
        val modHash = BaseSignatureCatalog.fingerprint(good)

        // 缺段（空文本）与旧档（无签名段）⇒ 读侧空表 ⇒ 空指纹 ⇒ 闸拒。
        assertRejected(emptyMap(), modHash)
        assertRejected(BaseSignatureCatalog.parse(oldMetaText()), modHash)

        // 缺任一要素 / 坏档形态的规范串 ⇒ 该条不进表 ⇒ 空指纹 ⇒ 闸拒，逐条断言。
        val badCanonicals = listOf(
            "SIG1|I,-,r,0",                 // 缺返回通道段
            "SIG1|I,-,r|ret:S",             // 形参段缺一个字段
            "SIG1|X,-,r,0|ret:S",           // 非法参数类型码
            "SIG1|I,-,x,0|ret:S",           // 非法 REF 位
            "SIG1|I,-,r,-1|ret:S",          // 负维度
            "SIG1|I,L!!!,r,0|ret:S",        // 坏 Base64
            "SIG2|I,-,r,0|ret:S",           // 版本不对
            "SIG1|I,-,r,0|ret:Q",           // 非法返回通道码
        )
        for (bad in badCanonicals) {
            val table = BaseSignatureCatalog.parse(withSection(record(bad)))
            assertTrue(table.isEmpty(), "坏规范串不得进表: $bad")
            assertRejected(table, modHash)
        }

        // 段头计数非法 / 重复键 ⇒ 整段空表 ⇒ 闸拒。
        val badHeader = oldMetaText() + "signatures NaN\nsignature FOO $good\n"
        assertRejected(BaseSignatureCatalog.parse(badHeader), modHash)
        val dup = withSection(record(good), record(good))
        assertRejected(BaseSignatureCatalog.parse(dup), modHash)
    }

    // ------------------------------------------------------------------
    // 判据 4：完整且两侧逐字相等才放行；任一要素不等即拒
    // ------------------------------------------------------------------

    @Test
    fun `签名相等才准覆盖_相等放行_任一要素不等则拒`() {
        val table = BaseSignatureCatalog.parse(withSection(record(good)))
        val query = BaseSignatureCatalog.signatureHashOf(table)
        val goodHash = query(key)

        // 相等 ⇒ 闸放行，胜者 = Mod 侧物理下标 1，super 链回指 base。
        val catalog = build(query, goodHash)
        assertEquals(1, catalog.winnerIndexOf(key), "相等须放行且胜者为覆盖者")
        assertTrue(catalog.hasSuper(key), "覆盖者 super 链回指 base")

        // 任一要素不等（哪怕只差一个默认值）⇒ 拒，且消息可定位「签名不一致」。
        val otherHash = BaseSignatureCatalog.fingerprint(different)
        assertNotEquals(goodHash, otherHash, "差一个默认值 ⇒ 指纹不同")
        val ex = assertFailsWith<OverrideSignatureException> { build(query, otherHash) }
        assertEquals(key, ex.key)
        assertTrue(ex.message!!.contains("签名不一致"), "消息可定位，实际: ${ex.message}")
    }

    // ------------------------------------------------------------------
    // 判据 5：关态 / 旧档 / strip 还原
    // ------------------------------------------------------------------

    @Test
    fun `关态旧档parse空表_strip还原可经BootMetadata读`() {
        val old = oldMetaText()
        assertTrue(BaseSignatureCatalog.parse(old).isEmpty(), "旧档无段 ⇒ 空表")
        assertEquals(old, BaseSignatureCatalog.stripSignatureSection(old), "无段原样返回")

        val withSeg = withSection(record(good))
        assertEquals(old, BaseSignatureCatalog.stripSignatureSection(withSeg), "剥离尾块须逐字节还原主体")
        assertNotNull(BootMetadata.parse(BaseSignatureCatalog.stripSignatureSection(withSeg)), "剥离后可经 BootMetadata 读")
        assertNotNull(BootMetadata.parse(old), "旧档本身仍可读")
    }
}
