package com.eratw.port.core.patch

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * base 侧完整签名指纹目录（RT2 M2-5 读侧；era-arch-v61u.md §16.1 / §10 / §12.1）。
 *
 * 关闭 §12.1「真实 Mod 不能覆盖」：`LinkCandidateAssembler.baseCandidates` 与
 * `ModuleLinker.link` 的 `signatureHashOf` / `baseSignatureOf` 接缝此前只有缺省空串
 * （fail-closed：签名信息缺失时覆盖 base 一律拒，不许拿 arity 猜）。本对象把 erbcc
 * 随 `program.meta` 导出的签名段读成 `(String) -> String` 真查询函数，供宿主注入该接缝。
 *
 * ## 根因层：装载/编译相位
 *
 * 缺陷落点 = 编译档缺签名导出、装载侧缺真查询函数，覆盖在运行期之前就被 fail-closed 拒。
 * 读侧与写侧同层，不是更上层的桩/白名单/兜底 —— **不放宽签名闸**：
 * 缺段 / 坏档 / 签名不完整 / 未知键一律返回空串，由核心既有
 * `SymbolCatalog.build` 的 `OverrideSignatureException` 拒绝覆盖（不重造闸）。
 *
 * ## 载体与只读约束
 *
 * 签名段是 `program.meta` 文本的**尾部旁路块**（`end` 行之后），由本对象按行独立扫描，
 * **不进** `BootMetadata` 的 BootMetaCodec 记录流。生产装载串联（P15S 已接）：
 * `BootMetadata.read` 两趟流各包一层剥离过滤器，`end` 行之后的下一行是段头时把
 * 旁路块从记录流里剥掉（严格相邻，fail-closed 边界见过滤器 KDoc）；本对象的
 * [stripSignatureSection] 仍保留给整文消费方（如测试）做文本级剥离。
 * 不带签名段的旧档：本对象返回空表（fail-closed），`BootMetadata.read` 逐字节照旧可读。
 *
 * ## 五要素（原版语义，C# 锚见交付报告）
 *
 * 参数类型（ErbLoader.cs:703-713）、默认值（FunctionLabelLine.cs:49、
 * CalledFunction.cs:167）、REF 位（UserDefinedFunctionArgument.cs:31）、
 * 维度（UserDefinedVariableData.cs:300-343）、返回通道四态
 * （LogicalLineParser.cs:151-192、FunctionLabelLine.cs:45）。
 * 序列化形态是 RT2 自研（TODO(verify)：原版无 Mod 覆盖可比，权威 = 蓝图 §16.1 + §10）。
 *
 * core 层纯 JVM：仅用 JDK 的 MessageDigest / Base64 / Locale，禁 Android API、
 * 禁 kotlin.random、禁静态可变全局（本对象无可变状态）。
 */
object BaseSignatureCatalog {

    private const val VERSION = "SIG1"

    /**
     * 尾部旁路块段头关键字（`signatures <N>` 段头行）。
     *
     * 消费方两处：本对象的 [parse]（有界尾读扫描）与装载侧 `BootMetadata.read` 的
     * 流式剥离过滤器（P15S 接线，只认 `end` 行之后的下一行）。与 erbcc 写侧
     * `SignatureExport.SECTION` 是同一关键字的两侧独立拼写（core-vm 不依赖 erbcc），
     * 一致性由两模块定向单测对拍锁死。
     */
    const val SECTION = "signatures"

    /** 段记录行关键字（`signature <KEY> <canonical>`；写侧 = erbcc `SignatureExport.RECORD` 同源）。 */
    const val RECORD = "signature"
    private val B64_DEC = Base64.getUrlDecoder()

    /** 一位形参的默认值：kind 为 '-'（无）/ 'L'（字面量）/ 'N'（非字面量原文）。 */
    data class SigDefault(val kind: Char, val text: String)

    /** 一位形参的四要素：类型码 I/S/F/U、默认值、REF 位、维度。 */
    data class SigParam(val type: Char, val default: SigDefault, val isRef: Boolean, val dims: Int)

    /** 一个函数的五要素签名（形参声明序 + 返回通道码 I/S/F/U）。 */
    data class Signature(val params: List<SigParam>, val returnChannel: Char)

    /**
     * 解析 `program.meta` 文本里的尾部签名段 → 大写键 → 规范串。
     *
     * fail-closed（任一坏档形态都不会让不完整签名混进表）：
     * - 无 `signatures <N>` 段头（缺段 / 旧档）⇒ 空表；
     * - 段头计数非法 / 重复键 ⇒ 整段空表；
     * - 单条记录 token 不全、键空、规范串 [decode] 不认（缺任一要素）⇒ 该条跳过不进表。
     *
     * 段头之后只吃连续的 `signature ` 行，遇到第一条别的行即停，不越界吞并其它记录。
     */
    fun parse(metaText: String): Map<String, String> {
        val lines = metaText.split('\n')
        val headerIdx = lines.indexOfFirst { it.startsWith("$SECTION ") }
        if (headerIdx < 0) return emptyMap()
        val declared = lines[headerIdx].removePrefix("$SECTION ").trim().toIntOrNull()
            ?: return emptyMap()
        if (declared < 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = headerIdx + 1
        var seen = 0
        while (i < lines.size && seen < declared) {
            val line = lines[i]
            if (!line.startsWith("$RECORD ")) break
            val body = line.removePrefix("$RECORD ")
            val sp = body.indexOf(' ')
            if (sp > 0) {
                val key = body.substring(0, sp)
                val canonical = body.substring(sp + 1)
                if (key.isNotEmpty() && canonical.isNotEmpty() && decode(canonical) != null) {
                    val norm = key.uppercase(Locale.ROOT)
                    if (out.put(norm, canonical) != null) return emptyMap()
                }
            }
            seen++
            i++
        }
        return out
    }

    /**
     * 规范串 → 五要素（逐项，供判据 1 导出→解析往返）。任一要素缺失 / 字段数不对 /
     * 记号非法 / Base64 坏 / 维度为负 ⇒ null（不完整即不认，绝不折缺省）。
     *
     * 与 erbcc 写侧 `SignatureExport.render` 是同一格式的独立实现（core-vm 不依赖 erbcc），
     * 两侧一致性由两模块的定向单测对拍证明。
     */
    fun decode(canonical: String): Signature? {
        val parts = canonical.split('|')
        if (parts.size < 2 || parts[0] != VERSION) return null
        val retPart = parts.last()
        if (retPart.length != 5 || !retPart.startsWith("ret:")) return null
        val ret = retPart[4]
        if (ret !in "ISFU") return null
        val params = ArrayList<SigParam>(parts.size - 2)
        for (i in 1 until parts.size - 1) {
            val seg = parts[i].split(',')
            if (seg.size != 4) return null
            val type = seg[0].singleOrNull() ?: return null
            if (type !in "ISFU") return null
            val defToken = seg[1]
            val def = when {
                defToken == "-" -> SigDefault('-', "")
                defToken.startsWith("L") ->
                    SigDefault('L', decodeB64(defToken.substring(1)) ?: return null)
                defToken.startsWith("N") ->
                    SigDefault('N', decodeB64(defToken.substring(1)) ?: return null)
                else -> return null
            }
            val isRef = when (seg[2]) {
                "R" -> true
                "r" -> false
                else -> return null
            }
            val dims = seg[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            params += SigParam(type, def, isRef, dims)
        }
        return Signature(params, ret)
    }

    private fun decodeB64(s: String): String? =
        runCatching { String(B64_DEC.decode(s), Charsets.UTF_8) }.getOrNull()

    /**
     * 签名指纹 = 规范串的 SHA-256 十六进制小写（同 LinkFingerprint.kt:55-97 口径）。
     * 两侧对同一规范串必得同一指纹；任一要素不等 ⇒ 规范串不等 ⇒ 指纹不等。
     */
    fun fingerprint(canonical: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 生产查询函数：暴露 `(String) -> String`，可直接喂给
     * `ModuleLinker.link(..., baseSignatureOf)` 或
     * `LinkCandidateAssembler.baseCandidates(..., signatureHashOf)`。
     *
     * - 键大小写归一（`FuncMeta.key` 口径，`uppercase(Locale.ROOT)`）；
     * - 命中且规范串 [decode] 完整 ⇒ 返回非空指纹（真值）；
     * - 未知键 / 空表 / 缺段 / 坏档 / 签名不完整 ⇒ **空串**（fail-closed，
     *   MODERN 下显式覆盖被核心既有签名闸拒绝，不重造闸、不加白名单、不拿 arity 猜）。
     */
    fun signatureHashOf(table: Map<String, String>): (String) -> String = { key ->
        val canonical = table[key.uppercase(Locale.ROOT)]
        if (canonical.isNullOrEmpty() || decode(canonical) == null) "" else fingerprint(canonical)
    }

    /**
     * 剥掉尾部签名段，还原纯 BootMetaCodec 文本（供生产装载侧接线，见类 KDoc）。
     * 无段时原样返回；有段时返回 `signatures ` 段头之前的全部文本（含 `end` 行）。
     */
    fun stripSignatureSection(text: String): String {
        var pos = 0
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos)
            if (text.startsWith("$SECTION ", pos)) return text.substring(0, pos)
            if (nl < 0) break
            pos = nl + 1
        }
        return text
    }
}
