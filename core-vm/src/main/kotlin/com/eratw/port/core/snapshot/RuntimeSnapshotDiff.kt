package com.eratw.port.core.snapshot

import com.eratw.port.core.snapshot.RuntimeSnapshot.Section
import com.eratw.port.core.snapshot.RuntimeSnapshot.Value

/**
 * 两份 v1 快照的**首个分歧**定位（契约 S2 的 `diff`：首差 section + 字段路径）。
 *
 * ## 纯函数
 *
 * 不碰 IO、不看时间、不依赖容器以外的任何状态：调用方给两份 [RuntimeSnapshot.Snapshot]，
 * 这里按**确定的顺序**产出全部分歧，第一条就是「首个分歧」。顺序：
 *
 * 1. 头部标量（`buildStamp` / `createdAt` / `saveIdentity` / `truncated`）；
 * 2. 头部项目身份（键按字典序；「缺失」也算一次分歧）；
 * 3. 节表：按 **a 的节序**逐节比对，节缺席即一次分歧；之后补 b 独有的节；
 * 4. 节内字段：按 a 的字段序逐个比对，字段缺席即一次分歧；之后补 b 独有的字段；
 *    列表按元素下标比（`state[17]`），长度差另记 `${field}.length`；字节块按字节比
 *    （首个不同字节记 `bytes[1234]`，长度差记 `bytes.length`）。
 *
 * 顺序固定是硬要求：「取末个分歧」不能冒充首差（反向证明 B 就钉这一条）。
 *
 * ## 值的表示
 *
 * [Divergence.a] / [Divergence.b] 是给 CLI 打一行的**转义后短文本**（换行折成 `\n`、
 * 超过 [MAX_VALUE_CHARS] 截断加省略号）。字节块给 `bytes=N sha256=?` 而不是原文 ——
 * 变量节动辄几百 KB，diff 报告要的是「哪一格、差多少」，不是把存档字帖打到屏上。
 */
object RuntimeSnapshotDiff {

    /** 单值展示上限（字符）：超过即截断，只保留可定位的前缀。 */
    const val MAX_VALUE_CHARS: Int = 120

    /** 一处分歧：节名 + 字段路径 + 两侧展示值。 */
    data class Divergence(val section: String, val path: String, val a: String, val b: String)

    /**
     * diff 结果。
     *
     * @param same 两份快照逐节逐字段完全一致（含头部）
     * @param divergences 全部分歧，**按确定顺序**排列（[first] = 第一条）
     */
    data class Result(val same: Boolean, val divergences: List<Divergence>) {
        /** 首个分歧；相同 = null。 */
        val first: Divergence? get() = divergences.firstOrNull()
    }

    /** 头部比对用的伪节名（CLI 原样打出，便于一眼看出「是版本/时间戳不同」而不是状态不同）。 */
    const val HEADER: String = "header"

    /** 比对两份快照。 */
    fun diff(a: RuntimeSnapshot.Snapshot, b: RuntimeSnapshot.Snapshot): Result {
        val out = ArrayList<Divergence>()
        diffHeader(a, b, out)
        diffSections(a, b, out)
        return Result(out.isEmpty(), out)
    }

    // ------------------------------------------------------------------
    // 头部
    // ------------------------------------------------------------------

    private fun diffHeader(a: RuntimeSnapshot.Snapshot, b: RuntimeSnapshot.Snapshot, out: MutableList<Divergence>) {
        val ha = a.header
        val hb = b.header
        addIfDiffers(out, "buildStamp", ha.buildStamp, hb.buildStamp)
        addIfDiffers(out, "createdAt", ha.createdAt, hb.createdAt)
        addIfDiffers(out, "saveIdentity", ha.saveIdentity, hb.saveIdentity)
        addIfDiffers(out, "truncated", a.truncated.toString(), b.truncated.toString())
        val keys = (ha.projectIdentity.keys + hb.projectIdentity.keys).sorted()
        for (key in keys) {
            val va = ha.projectIdentity[key]
            val vb = hb.projectIdentity[key]
            if (va != vb) out += Divergence(HEADER, "project.$key", renderNullable(va), renderNullable(vb))
        }
    }

    // ------------------------------------------------------------------
    // 节
    // ------------------------------------------------------------------

    private fun diffSections(
        a: RuntimeSnapshot.Snapshot,
        b: RuntimeSnapshot.Snapshot,
        out: MutableList<Divergence>,
    ) {
        val bByName = b.sections.associateBy { it.name }
        val seen = HashSet<String>()
        for (section in a.sections) {
            seen += section.name
            val other = bByName[section.name]
            if (other == null) {
                out += Divergence(section.name, "(section)", "present", "absent")
                continue
            }
            diffSection(section, other, out)
        }
        for (section in b.sections) {
            if (section.name !in seen) out += Divergence(section.name, "(section)", "absent", "present")
        }
    }

    private fun diffSection(a: Section, b: Section, out: MutableList<Divergence>) {
        for ((name, valueA) in a.fields) {
            val valueB = b.fields[name]
            if (valueB == null) {
                out += Divergence(a.name, name, render(valueA), "absent")
                continue
            }
            diffValue(a.name, name, valueA, valueB, out)
        }
        for (name in b.fields.keys) {
            if (name !in a.fields) out += Divergence(a.name, name, "absent", render(b.fields.getValue(name)))
        }
    }

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    private fun diffValue(section: String, path: String, a: Value, b: Value, out: MutableList<Divergence>) {
        when {
            a is Value.Text && b is Value.Text -> addIfDiffers(out, path, a.value, b.value, section)

            a is Value.Int64 && b is Value.Int64 -> addIfDiffers(out, path, a.value.toString(), b.value.toString(), section)

            a is Value.Bool && b is Value.Bool -> addIfDiffers(out, path, a.value.toString(), b.value.toString(), section)

            a is Value.TextList && b is Value.TextList -> diffList(section, path, a.items, b.items, out)

            a is Value.IntList && b is Value.IntList ->
                diffList(section, path, a.items.map { it.toString() }, b.items.map { it.toString() }, out)

            a is Value.Blob && b is Value.Blob -> diffBlob(section, path, a.bytes, b.bytes, out)

            else -> out += Divergence(section, path, label(a), label(b))
        }
    }

    private fun diffList(
        section: String,
        path: String,
        a: List<String>,
        b: List<String>,
        out: MutableList<Divergence>,
    ) {
        val limit = minOf(a.size, b.size)
        for (i in 0 until limit) {
            if (a[i] != b[i]) {
                out += Divergence(section, "$path[$i]", render(a[i]), render(b[i]))
            }
        }
        if (a.size != b.size) {
            out += Divergence(section, "$path.length", "${a.size}", "${b.size}")
        }
    }

    private fun diffBlob(section: String, path: String, a: ByteArray, b: ByteArray, out: MutableList<Divergence>) {
        val limit = minOf(a.size, b.size)
        var at = -1
        for (i in 0 until limit) {
            if (a[i] != b[i]) {
                at = i
                break
            }
        }
        if (at >= 0) {
            out += Divergence(
                section,
                "$path[$at]",
                "0x%02x".format(a[at]),
                "0x%02x".format(b[at]),
            )
        }
        if (a.size != b.size) {
            out += Divergence(section, "$path.length", "${a.size}", "${b.size}")
        }
    }

    private fun addIfDiffers(out: MutableList<Divergence>, path: String, a: String, b: String, section: String = HEADER) {
        if (a != b) out += Divergence(section, path, render(a), render(b))
    }

    private fun label(value: Value): String = when (value) {
        is Value.Text -> "text"
        is Value.Int64 -> "int"
        is Value.Bool -> "bool"
        is Value.TextList -> "textList"
        is Value.IntList -> "intList"
        is Value.Blob -> "blob"
    }

    private fun render(value: Value?): String = when (value) {
        null -> "absent"
        is Value.Text -> render(value.value)
        is Value.Int64 -> value.value.toString()
        is Value.Bool -> value.value.toString()
        is Value.TextList -> "textList(len=${value.items.size})"
        is Value.IntList -> "intList(len=${value.items.size})"
        is Value.Blob -> render(value.bytes)
    }

    /** 可空字符串的展示（头部项目身份的「一侧缺失」走这里）。 */
    private fun renderNullable(raw: String?): String = if (raw == null) "absent" else render(raw)

    private fun render(raw: String): String {        val oneLine = raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return if (oneLine.length <= MAX_VALUE_CHARS) {
            oneLine
        } else {
            oneLine.substring(0, MAX_VALUE_CHARS) + "…(len=${oneLine.length})"
        }
    }

    /** 字节块只给长度与摘要（原文可能几百 KB，且含玩家正文，不该整段打到终端上）。 */
    private fun render(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        return "bytes=${bytes.size} sha256=$hex…"
    }
}
