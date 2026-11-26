package com.eratw.port.erbcc.compile

import com.eratw.port.core.frontend.SignatureExport
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.proc.FuncMeta
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RT2 M2-5 写侧定向单测（CONTRACT-RT2-M2-5 §3.2 判据 1/2/5）。
 *
 * 每条判据一个用例、逐条可单独退回变红：
 * - 反向证明①：把 SignatureExport.render 的 REF 位改成恒 `r` ⇒
 *   `五要素逐要素往返_导出解析逐项相等` 的 REF 断言必红；
 * - 反向证明③：把 export 的键大写归一去掉 ⇒
 *   `键大写归一_逐函数可查_查询真值非空` 必红；
 * - 反向证明④：把 BootMetaWriter.text 的空表判空去掉（空表也写段）⇒
 *   `关态逐字节不变_空签名表不写段` 必红。
 *
 * 跨模块对拍：erbcc 依赖 core-vm，本套件直接用 [BaseSignatureCatalog] 读回
 * [BootMetaWriter.text] 写出的签名段，证明「写侧渲染 = 读侧解析」同一格式。
 */
class SignatureExportTest {

    /** 最小可用的侧车模型（只为让文本非平凡；签名段内容不依赖它）。 */
    private fun meta(): BootMetadata = BootMetadata(
        functions = listOf(
            FuncMeta(index = 0, key = "MAIN", name = "MAIN", defIndex = 1, position = "main.ERB:1"),
        ),
    )

    /** 关态基线 = 引入签名参数之前 BootMetaWriter.text 的实现形态。 */
    private fun baseline(m: BootMetadata): String =
        m.withHeader(engineTag = BootMetaWriter.engineTag(), programSha256 = null).encodeText()

    /**
     * 五要素齐全的签名：INT/STR/FLOAT/UNKNOWN 四类参数类型、字面量/非字面量/无三类默认值、
     * REF 真与假、维度 0/1/2、返回通道由入参指定。第 4 位字面量含空格/竖线/逗号/引号，
     * 用来验证 Base64 转义后往返仍逐字节相等。
     */
    private fun fullSignature(
        ret: SignatureExport.ReturnChannel = SignatureExport.ReturnChannel.INT,
    ): SignatureExport.FunctionSignature = SignatureExport.FunctionSignature(
        params = listOf(
            SignatureExport.Param(
                SignatureExport.ParamType.INT,
                SignatureExport.DefaultValue(SignatureExport.DefaultKind.LITERAL, "2"),
                true,
                0,
            ),
            SignatureExport.Param(
                SignatureExport.ParamType.STR,
                SignatureExport.DefaultValue(SignatureExport.DefaultKind.NON_LITERAL, "C_YELLOW"),
                false,
                2,
            ),
            SignatureExport.Param(
                SignatureExport.ParamType.FLOAT,
                SignatureExport.DefaultValue(SignatureExport.DefaultKind.NONE),
                false,
                1,
            ),
            SignatureExport.Param(
                SignatureExport.ParamType.UNKNOWN,
                SignatureExport.DefaultValue(SignatureExport.DefaultKind.LITERAL, "a b|,\"quote\""),
                false,
                0,
            ),
        ),
        returnChannel = ret,
    )

    private fun kindChar(k: SignatureExport.DefaultKind): Char = when (k) {
        SignatureExport.DefaultKind.NONE -> '-'
        SignatureExport.DefaultKind.LITERAL -> 'L'
        SignatureExport.DefaultKind.NON_LITERAL -> 'N'
    }

    // ------------------------------------------------------------------
    // 判据 1：五要素逐要素往返
    // ------------------------------------------------------------------

    @Test
    fun `五要素逐要素往返_导出解析逐项相等`() {
        val sig = fullSignature(SignatureExport.ReturnChannel.STR)
        val canonical = SignatureExport.render(sig)

        // 写侧自回路：render → decode 逐项相等。
        val back = SignatureExport.decode(canonical)
        assertNotNull(back, "规范串必须可解回五要素")
        assertEquals(sig.params.size, back.params.size, "形参数")
        sig.params.forEachIndexed { i, p ->
            assertEquals(p.type, back.params[i].type, "形参 $i 参数类型")
            assertEquals(p.defaultValue, back.params[i].defaultValue, "形参 $i 默认值（字面量/非字面量/无）")
            assertEquals(p.isRef, back.params[i].isRef, "形参 $i REF 位（反向证明①的钉）")
            assertEquals(p.dims, back.params[i].dims, "形参 $i 维度")
        }
        assertEquals(sig.returnChannel, back.returnChannel, "返回通道")

        // 跨模块：写侧文本 → core-vm 读侧解析 → 逐项相等（证明两侧同一格式）。
        val signatures = SignatureExport.export(listOf(SignatureExport.Source("Foo", sig)))
        val text = BootMetaWriter.text(meta(), signatures = signatures)
        val table = BaseSignatureCatalog.parse(text)
        assertEquals(1, table.size, "读侧须解析出 1 条")
        val parsed = BaseSignatureCatalog.decode(table.getValue("FOO"))
        assertNotNull(parsed, "读侧须认写侧规范串")
        assertEquals(sig.params.size, parsed.params.size)
        sig.params.forEachIndexed { i, p ->
            assertEquals(p.type.code, parsed.params[i].type, "读侧形参 $i 参数类型")
            assertEquals(kindChar(p.defaultValue.kind), parsed.params[i].default.kind, "读侧形参 $i 默认值类别")
            assertEquals(p.defaultValue.text, parsed.params[i].default.text, "读侧形参 $i 默认值文本")
            assertEquals(p.isRef, parsed.params[i].isRef, "读侧形参 $i REF 位")
            assertEquals(p.dims, parsed.params[i].dims, "读侧形参 $i 维度")
        }
        assertEquals(sig.returnChannel.code, parsed.returnChannel, "读侧返回通道")
    }

    @Test
    fun `返回通道INT_STR_FLOAT_UNKNOWN四态各自往返且互不相同`() {
        val channels = listOf(
            SignatureExport.ReturnChannel.INT,
            SignatureExport.ReturnChannel.STR,
            SignatureExport.ReturnChannel.FLOAT,
            SignatureExport.ReturnChannel.UNKNOWN,
        )
        for (ch in channels) {
            val sig = fullSignature(ch)
            val back = SignatureExport.decode(SignatureExport.render(sig))
            assertNotNull(back)
            assertEquals(ch, back.returnChannel, "返回通道 $ch 往返")
        }
        val rendered = channels.map { SignatureExport.render(fullSignature(it)) }.toSet()
        assertEquals(4, rendered.size, "四态规范串必须两两不同")
    }

    // ------------------------------------------------------------------
    // 判据 2：确定性 / 归一 / 逐函数可查
    // ------------------------------------------------------------------

    @Test
    fun `同内容两次导出sha256相同_不同内容不同`() {
        val sig = fullSignature()
        val other = sig.copy(params = sig.params.dropLast(1))
        val c1 = SignatureExport.render(sig)
        val c2 = SignatureExport.render(fullSignature())
        assertEquals(c1, c2, "同内容渲染逐字节相同")
        assertEquals(SignatureExport.fingerprint(c1), SignatureExport.fingerprint(c2), "同内容 sha256 相同")
        assertNotEquals(c1, SignatureExport.render(other), "差一个形参 ⇒ 规范串不同")
        assertNotEquals(
            SignatureExport.fingerprint(c1),
            SignatureExport.fingerprint(SignatureExport.render(other)),
            "差一个形参 ⇒ sha256 不同",
        )
        val s1 = SignatureExport.export(
            listOf(SignatureExport.Source("A", sig), SignatureExport.Source("B", other)),
        )
        val s2 = SignatureExport.export(
            listOf(SignatureExport.Source("B", other), SignatureExport.Source("A", sig)),
        )
        assertEquals(s1, s2, "导出与源顺序无关（键排序）")
    }

    @Test
    fun `键大写归一_逐函数可查_查询真值非空`() {
        val signatures = SignatureExport.export(
            listOf(
                SignatureExport.Source("MiXeD", fullSignature(SignatureExport.ReturnChannel.INT)),
                SignatureExport.Source("other", fullSignature(SignatureExport.ReturnChannel.STR)),
            ),
        )
        assertTrue(signatures.keys.all { it == it.uppercase(Locale.ROOT) }, "导出键必须全大写")
        assertTrue("MIXED" in signatures, "混合大小写键须归一（反向证明③的钉）")
        assertTrue("OTHER" in signatures)

        val query = BaseSignatureCatalog.signatureHashOf(signatures)
        assertTrue(query("mixed").isNotEmpty(), "小写键查询须归一命中（反向证明③的钉）")
        assertEquals(query("MIXED"), query("mixed"), "归一前后同一真值")
        assertTrue(query("other").isNotEmpty(), "逐函数可查")
        assertNotEquals(query("MIXED"), query("other"), "不同签名不同真值")
        assertTrue(query("DOES_NOT_EXIST").isEmpty(), "未知键 fail-closed 返回空串")
    }

    // ------------------------------------------------------------------
    // 判据 5：关态逐字节不变 / 旧档仍可读
    // ------------------------------------------------------------------

    @Test
    fun `关态逐字节不变_空签名表不写段`() {
        val m = meta()
        val base = baseline(m)
        assertEquals(base, BootMetaWriter.text(m), "缺省参数（反向证明④的钉）")
        assertEquals(base, BootMetaWriter.text(m, signatures = emptyMap()), "显式空表")
        assertFalse(base.contains("signatures"), "关态不得出现签名段")
        // 旧读侧对关态文本仍逐字节闭环。
        assertEquals(base, BootMetadata.parse(base).encodeText(), "旧读侧兼容通道闭环")
    }

    @Test
    fun `非空签名表写出规范段_无墙钟无绝对路径`() {
        val m = meta()
        val base = baseline(m)
        val signatures = SignatureExport.export(listOf(SignatureExport.Source("Foo", fullSignature())))
        val expected = "signatures 1\nsignature FOO ${signatures.getValue("FOO")}\n"
        val text = BootMetaWriter.text(m, signatures = signatures)
        assertEquals(base + expected, text, "主体逐字节不变 + 规范签名段（精确锁定：无墙钟/无绝对路径）")
        assertTrue(text.endsWith("\n"), "文本必须以 LF 结尾")
        assertEquals(text, BootMetaWriter.text(m, signatures = signatures), "两次写出逐字节相同")
    }

    @Test
    fun `不带签名段的旧档仍可读_读侧返回空表`() {
        val old = baseline(meta())
        assertTrue(BaseSignatureCatalog.parse(old).isEmpty(), "旧档读侧返回空表 ⇒ fail-closed")
        assertTrue(BaseSignatureCatalog.signatureHashOf(emptyMap())("ANY").isEmpty())
        assertNotNull(BootMetadata.parse(old), "旧档仍可被 BootMetadata 读侧解析")
    }
}
