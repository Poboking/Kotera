package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.stmt.ParamDefault
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * erbcc 签名导出（RT2 M2-5 写侧；era-arch-v61u.md §16.1 / §10 / §12.1）。
 *
 * 关闭的缺口 = §12.1「真实 Mod 不能覆盖」：链接器的 `baseSignatureOf` 目前只有缺省空串
 * 与 KDoc 接缝，编译档没有签名真值，任何 Override 都因签名不等被 fail-closed 拒绝。
 * 本对象从编译期模型抽出**完整调用签名指纹**的规范串，随 `program.meta` 的尾部可选段
 * 落盘，供装载侧 `BaseSignatureCatalog` 读成真查询函数。
 *
 * ## 五要素（原版语义，逐条 C# 锚见交付报告）
 *
 * 1. 参数类型：函数头形参按 `VariableCode.ARG`（整型）/ `ARGS`（字符串）分桶
 *    （ErbLoader.cs:703-713、UserDefinedFunctionArgument.cs:60-67）；4.981 fork 另有浮点通道；
 * 2. 默认值：函数头逐位 `SingleTerm[] Def`（FunctionLabelLine.cs:49、ErbLoader.cs:673-724），
 *    调用点省略实参时由 `topLabel.Def[i]` 顶替（CalledFunction.cs:167）。字面量可注入、
 *    非字面量（常量名/任意表达式）按既有 `param-defaults` 口径只登记原文 raw 绝不折值；
 * 3. REF 位：形参 `VariableTerm.Identifier.IsReference`（ErbLoader.cs:685、
 *    UserDefinedFunctionArgument.cs:31、ProcessState.cs:439-441）；
 * 4. 维度：`VariableTerm` 的元素下标与 `#DIM` 的维度尺寸
 *    （UserDefinedVariableData.cs:300-343、specs/erbc-format.md §5.9）；
 * 5. 返回通道：`FunctionLabelLine.MethodType` 的 `typeof(void)` / `long` / `string` 三态
 *    加 4.981 fork 的 `#FUNCTIONF` 浮点（FunctionLabelLine.cs:45、
 *    LogicalLineParser.cs:151-192、FunctionBytecode.kt:17-59）。
 *
 * ## 序列化形态（RT2 自研，TODO(verify)）
 *
 * 原版没有 Mod 覆盖，因而没有「签名指纹序列化」这一概念 —— 规范串的形态是 RT2 自研，
 * 权威 = 蓝图 §16.1「完整签名指纹与 base 侧相等」+ §10 ABI-7 Gate。TODO(verify) 卡点：
 * 原版无 Mod 覆盖可比，序列化形态只能由本波契约层单测自证，不可对拍 C#。
 *
 * 规范串是单行、不含空格、不含竖线与逗号冲突字符（默认值文本一律 Base64 URL-safe 无填充
 * 编码）：`SIG1` 后逐形参一段 `类型,默认值,REF,维度`，末段 `ret:返回通道`。
 * 渲染稳定序 = 形参声明序（不排序、不重排，位号就是形参序）；Map 段按键排序落盘，
 * 同一编译档两次导出逐字节相同，不含墙钟、主机名、绝对路径。
 */
object SignatureExport {

    /** 参数类型四态（与返回通道同记号，值域独立）。 */
    enum class ParamType(val code: Char) {
        INT('I'), STR('S'), FLOAT('F'), UNKNOWN('U'),
    }

    /** 返回通道 INT/STR/FLOAT/UNKNOWN 四态。 */
    enum class ReturnChannel(val code: Char) {
        INT('I'), STR('S'), FLOAT('F'), UNKNOWN('U'),
    }

    /** 默认值类别：无 / 字面量 / 非字面量。 */
    enum class DefaultKind { NONE, LITERAL, NON_LITERAL }

    /** 一位形参的默认值；[text] 在 [DefaultKind.NONE] 时恒空。 */
    data class DefaultValue(val kind: DefaultKind, val text: String = "") {
        init {
            require(kind != DefaultKind.NONE || text.isEmpty()) { "无默认值的 text 必须为空" }
        }
    }

    /** 一位形参的四要素（参数类型 / 默认值 / REF / 维度）。 */
    data class Param(
        val type: ParamType,
        val defaultValue: DefaultValue,
        val isRef: Boolean,
        val dims: Int,
    ) {
        init {
            require(dims >= 0) { "维度不得为负: $dims" }
        }
    }

    /** 一个函数的完整签名（形参按声明序 + 返回通道）。 */
    data class FunctionSignature(
        val params: List<Param>,
        val returnChannel: ReturnChannel,
    )

    /** 导出源：函数键 + 签名。键统一走大写归一（与 FuncMeta.key 同口径）。 */
    data class Source(val key: String, val signature: FunctionSignature)

    private const val VERSION = "SIG1"
    private const val SECTION_TAG = "signatures"
    private const val RECORD_TAG = "signature"
    private val B64_ENC = Base64.getUrlEncoder().withoutPadding()
    private val B64_DEC = Base64.getUrlDecoder()

    private fun enc(s: String): String = B64_ENC.encodeToString(s.toByteArray(Charsets.UTF_8))

    /** Base64 URL-safe 解码（读侧对拍用；坏串由调用方 runCatching 兜）。 */
    fun dec(s: String): String = String(B64_DEC.decode(s), Charsets.UTF_8)

    /**
     * 稳定渲染规范串（不含函数键，故 ABI 相同的函数得到同一指纹）。
     *
     * 例：`SIG1|I,L<...>,R,0|S,N<...>,r,2|ret:S`。默认值文本经 Base64 编码，
     * 保证串内不出现竖线/逗号/空格，段记录可按空格切成三个 token。
     */
    fun render(sig: FunctionSignature): String = buildString {
        append(VERSION)
        for (p in sig.params) {
            append('|').append(p.type.code).append(',')
            append(
                when (p.defaultValue.kind) {
                    DefaultKind.NONE -> "-"
                    DefaultKind.LITERAL -> "L" + enc(p.defaultValue.text)
                    DefaultKind.NON_LITERAL -> "N" + enc(p.defaultValue.text)
                },
            )
            append(',').append(if (p.isRef) 'R' else 'r')
            append(',').append(p.dims)
        }
        append("|ret:").append(sig.returnChannel.code)
    }

    /** SHA-256 十六进制小写（同 LinkFingerprint.kt:55-97 的口径）。 */
    fun fingerprint(canonical: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 写侧自回路：把规范串解回五要素（判据 1 往返；也是读侧格式的对拍基准）。
     * 任一要素缺失 / 字段数不对 / 记号非法 / Base64 坏 ⇒ null（不完整即不认）。
     */
    fun decode(canonical: String): FunctionSignature? {
        val parts = canonical.split('|')
        if (parts.size < 2 || parts[0] != VERSION) return null
        val retPart = parts.last()
        if (retPart.length != 5 || !retPart.startsWith("ret:")) return null
        val ret = ReturnChannel.values().firstOrNull { it.code == retPart[4] } ?: return null
        val params = ArrayList<Param>(parts.size - 2)
        for (i in 1 until parts.size - 1) {
            val seg = parts[i].split(',')
            if (seg.size != 4) return null
            val typeChar = seg[0].singleOrNull() ?: return null
            val type = ParamType.values().firstOrNull { it.code == typeChar } ?: return null
            val defToken = seg[1]
            val def = when {
                defToken == "-" -> DefaultValue(DefaultKind.NONE)
                defToken.startsWith("L") ->
                    DefaultValue(DefaultKind.LITERAL, decodeB64(defToken.substring(1)) ?: return null)
                defToken.startsWith("N") ->
                    DefaultValue(DefaultKind.NON_LITERAL, decodeB64(defToken.substring(1)) ?: return null)
                else -> return null
            }
            val isRef = when (seg[2]) {
                "R" -> true
                "r" -> false
                else -> return null
            }
            val dims = seg[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            params += Param(type, def, isRef, dims)
        }
        return FunctionSignature(params, ret)
    }

    private fun decodeB64(s: String): String? = runCatching { dec(s) }.getOrNull()

    /**
     * 导出：源清单 → 大写键 → 规范串（Map 键排序，确定性）。
     * 同内容两次调用结果逐字节相同；键不做大写归一时同内容会因大小写分叉（反向证明③）。
     */
    fun export(sources: List<Source>): Map<String, String> {
        val out = LinkedHashMap<String, String>(sources.size)
        for (s in sources) out[s.key.uppercase(Locale.ROOT)] = render(s.signature)
        return out.toSortedMap()
    }

    /**
     * 段文本（追加在 `program.meta` 的 `end` 之后，作为尾部旁路块）。
     *
     * **空表整段省略** ⇒ 返回空串 ⇒ `BootMetaWriter.text()` 与引入本段之前逐字节相同
     * （关态不变式，照 R7 波 0 / specs/erbc-format.md §5.9 的 omit_when_empty 口径）。
     * 段头 `signatures <N>` + 逐条 `signature <大写键> <规范串>`，键排序，无墙钟 / 绝对路径。
     */
    fun encodeSection(signatures: Map<String, String>): String {
        if (signatures.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(SECTION_TAG).append(' ').append(signatures.size).append('\n')
        for (k in signatures.keys.sorted()) {
            sb.append(RECORD_TAG).append(' ').append(k).append(' ')
                .append(signatures.getValue(k)).append('\n')
        }
        return sb.toString()
    }

    /** 段头关键字（读侧剥离用，与 [SECTION_TAG] 同源）。 */
    const val SECTION: String = SECTION_TAG
    /** 段记录关键字（读侧解析用）。 */
    const val RECORD: String = RECORD_TAG

    // ------------------------------------------------------------------
    // 编译期模型映射（供 CorpusCompile 的 meta 生成段接线）
    // ------------------------------------------------------------------

    /** 形参名 → 参数类型：ARGS = 字符串桶、ARGF = 浮点通道、ARG = 整型桶，其余 UNKNOWN。 */
    fun paramTypeOf(name: String): ParamType {
        val u = name.uppercase(Locale.ROOT)
        return when {
            u.startsWith("ARGS") -> ParamType.STR
            u.startsWith("ARGF") -> ParamType.FLOAT
            u.startsWith("ARG") -> ParamType.INT
            else -> ParamType.UNKNOWN
        }
    }

    /**
     * `ParamDefault` → 默认值三态：null = 无、expr 非 null = 字面量（确定性 toString）、
     * 否则 = 非字面量（按 param-defaults 口径只登记 raw，不折值、不猜）。
     */
    fun defaultOf(d: ParamDefault?): DefaultValue = when {
        d == null -> DefaultValue(DefaultKind.NONE)
        d.expr != null -> DefaultValue(DefaultKind.LITERAL, d.expr.toString())
        else -> DefaultValue(DefaultKind.NON_LITERAL, d.raw)
    }

    /** `scan.funcTypes` 的 OperandType → 返回通道四态。 */
    fun returnChannelOf(t: OperandType?): ReturnChannel = when (t) {
        OperandType.INT -> ReturnChannel.INT
        OperandType.STR -> ReturnChannel.STR
        OperandType.FLOAT -> ReturnChannel.FLOAT
        else -> ReturnChannel.UNKNOWN
    }

    /**
     * 从一位形参的编译期信息装配 Param。
     *
     * REF 位与维度取形参项原文（含 `:n` 下标）：带元素下标的形参项视为 REF 位真，
     * `:` 个数即维度。这是编译期模型当前可靠可得的映射（RT2 近似，TODO(verify)：
     * 真 REF 位应取 `VariableTerm.Identifier.IsReference`，见 UserDefinedFunctionArgument.cs:31；
     * 本 lane 的契约层单测只证五要素可导出/解析/驱动签名闸，不证真语料逐位绑定）。
     */
    fun paramOf(name: String, paramRef: String, d: ParamDefault?): Param = Param(
        type = paramTypeOf(name),
        defaultValue = defaultOf(d),
        isRef = paramRef.contains(':'),
        dims = paramRef.count { it == ':' },
    )
}
