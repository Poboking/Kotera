package com.eratw.port.erbcc.audit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 可达性基线对拍面（MODERN-R8-B）。
 *
 * 病灶：`audit --reachability` 的六项读数（根数 / 可达数 / 动态调用 / 可移植性 / 持久化 + 全表
 * 指纹）自 R6-B / R6-C 起只能在人的记忆里，编译器或审计器一改就可能静默漂移 —— 读数没人钉，
 * 漂了就漂了。本类把读数固化成一份可复跑的 ASCII 基线（[render] / [write] / [read]），
 * 再给出逐字段的对拍（[compare]），让「漂移」变成一行确定性的 stderr 与退出码 1。
 *
 * 钉的口径：
 * - 基线是纯 ASCII `key=value`（`version=1` 起头，六个字段键序固定，无注释，LF 行尾）；
 * - `tsvSha256` = 对 [ReachabilityReport.tsvText] 的 UTF-8 字节取 SHA-256，小写十六进制；
 *   与 `writeTsv` 落盘内容**同源**（同一份构造），所以指纹一变就说明 TSV 有任何一位变了；
 * - 基线不含绝对路径 / 时间戳 / 主机名 / 耗时 ⇒ 同一语料两次运行、两台主机逐字节相同；
 * - 对拍只比字段，不改任何审计判定（本类不参与扫描，只消费 [ReachabilityReport]）。
 *
 * 报错一律抛 [IllegalStateException]（消息确定性、不含 JVM 内部异常名），由 CLI 侧映射成
 * 入参/环境档退出码 2 —— 「基线文件坏了」不是「报告红了」，两档不能混。
 */
class ReachabilityExpect(
    val roots: Int,
    val reachable: Int,
    val dynamicCalls: Int,
    val portability: Int,
    val persist: Int,
    /** 全表 TSV 的 SHA-256（64 位小写十六进制）。 */
    val tsvSha256: String,
) {

    companion object {
        /** 基线格式版本；读到别的值一律拒绝（不做「猜一个新格式」）。 */
        const val VERSION: Int = 1

        /** 对拍字段与报告顺序（[compare] 的逐行输出按这个顺序，缺一不可、不许多）。 */
        val FIELD_NAMES: List<String> =
            listOf("roots", "reachable", "dynamicCalls", "portability", "persist", "tsvSha256")

        /** 基线文件的全部键（`version` 在前，其余按 [FIELD_NAMES]）。 */
        private val KEYS: List<String> = listOf("version") + FIELD_NAMES

        private const val HEX = "0123456789abcdef"

        /** 一个字节的高/低四位各占的位数与掩码（[sha256Hex] 专用；写成常量免得散落魔数）。 */
        private const val NIBBLE_BITS = 4

        private const val NIBBLE_MASK = 0x0F

        private const val BYTE_MASK = 0xFF

        private val SHA_HEX = Regex("^[0-9a-f]{64}$")

        /** 从一份报告取当前读数（sha 对 [ReachabilityReport.tsvText] 的 UTF-8 字节取）。 */
        fun of(report: ReachabilityReport): ReachabilityExpect = ReachabilityExpect(
            roots = report.roots,
            reachable = report.reachable,
            dynamicCalls = report.dynamicCalls,
            portability = report.portability,
            persist = report.persist,
            tsvSha256 = sha256Hex(report.tsvText()),
        )

        /** `text` 的 UTF-8 字节的 SHA-256，小写十六进制（不依赖 Locale，不自造编码）。 */
        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and BYTE_MASK
                sb.append(HEX[v ushr NIBBLE_BITS]).append(HEX[v and NIBBLE_MASK])
            }
            return sb.toString()
        }

        /** 基线正文（固定键序、LF 行尾、结尾一个 LF；无注释、无绝对路径）。 */
        fun render(expect: ReachabilityExpect): String = buildString {
            append("version=").append(VERSION).append('\n')
            append("roots=").append(expect.roots).append('\n')
            append("reachable=").append(expect.reachable).append('\n')
            append("dynamicCalls=").append(expect.dynamicCalls).append('\n')
            append("portability=").append(expect.portability).append('\n')
            append("persist=").append(expect.persist).append('\n')
            append("tsvSha256=").append(expect.tsvSha256).append('\n')
        }

        /**
         * 把基线写到 [path]（父目录不存在则创建），返回落点的绝对归一路径
         * （CLI 那行「已写入」打印的就是它；相对路径按 JVM 工作目录解析，与 `--out` 同口径）。
         */
        fun write(path: Path, expect: ReachabilityExpect): Path {
            val target = path.toAbsolutePath().normalize()
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, render(expect).toByteArray(Charsets.UTF_8))
            return target
        }

        /**
         * 读基线。容忍行尾空白与空行、容忍 CRLF；以下一律拒绝（抛 [IllegalStateException]）：
         * 不是普通文件 / 读不出 / 行不是 `key=value` / 未知键 / 重复键 / 缺键 /
         * `version != 1` / 计数字段不是整数 / `tsvSha256` 不是 64 位小写十六进制。
         *
         * 缺键按 [KEYS] 的固定顺序先报 `version` 再按 [FIELD_NAMES]，报出来的永远是同一个字段。
         */
        fun read(path: Path): ReachabilityExpect {
            val file = path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(file)) error("可达性基线不是文件: $file")
            return decode(file, parseEntries(file, loadText(file)))
        }

        /** 读字节并按 UTF-8 解码（坏字节按 REPLACE 落成 U+FFFD，随后的键名校验会把它挡下）。 */
        private fun loadText(file: Path): String = try {
            String(Files.readAllBytes(file), Charsets.UTF_8)
        } catch (e: IOException) {
            throw IllegalStateException("可达性基线读不了: $file", e)
        }

        /** `key=value` 解析：容忍行尾空白与空行；格式坏 / 未知键 / 重复键一律拒绝。 */
        private fun parseEntries(file: Path, text: String): Map<String, String> {
            val seen = LinkedHashMap<String, String>()
            for ((idx, raw) in text.split('\n').withIndex()) {
                val line = raw.trimEnd()
                if (line.isEmpty()) continue
                val at = line.indexOf('=')
                if (at <= 0) error("可达性基线第 ${idx + 1} 行不是 key=value: $file")
                val key = line.substring(0, at)
                if (key !in KEYS) error("可达性基线有不认识的字段 $key（第 ${idx + 1} 行）: $file")
                if (seen.put(key, line.substring(at + 1)) != null) {
                    error("可达性基线字段重复 $key（第 ${idx + 1} 行）: $file")
                }
            }
            return seen
        }

        /** 逐字段取值与校验（报错按 `version` 优先、其后 [FIELD_NAMES] 的固定顺序点名）。 */
        private fun decode(file: Path, entries: Map<String, String>): ReachabilityExpect {
            val version = entries["version"] ?: error("可达性基线缺字段 version: $file")
            if (version != VERSION.toString()) {
                error("可达性基线 version 不认识（期望 $VERSION，实际 $version）: $file")
            }
            fun number(key: String): Int {
                val raw = entries[key] ?: error("可达性基线缺字段 $key: $file")
                return raw.toIntOrNull() ?: error("可达性基线 $key 不是整数（实际 <$raw>）: $file")
            }
            val roots = number("roots")
            val reachable = number("reachable")
            val dynamicCalls = number("dynamicCalls")
            val portability = number("portability")
            val persist = number("persist")
            val sha = entries["tsvSha256"] ?: error("可达性基线缺字段 tsvSha256: $file")
            if (!SHA_HEX.matches(sha)) {
                error("可达性基线 tsvSha256 不是 64 位小写十六进制（实际 <$sha>）: $file")
            }
            return ReachabilityExpect(roots, reachable, dynamicCalls, portability, persist, sha)
        }

        /**
         * 逐字段对拍，返回漂移行（**已成形**的 stderr 正文，按 [FIELD_NAMES] 固定顺序）；
         * 空列表 = 完全一致。每行形如
         * `reachability 基线不一致：roots 期望 695 实际 696` —— 一条漂移一行，绝不合并。
         */
        fun compare(expected: ReachabilityExpect, actual: ReachabilityExpect): List<String> {
            val lhs = listOf(
                expected.roots.toString(),
                expected.reachable.toString(),
                expected.dynamicCalls.toString(),
                expected.portability.toString(),
                expected.persist.toString(),
                expected.tsvSha256,
            )
            val rhs = listOf(
                actual.roots.toString(),
                actual.reachable.toString(),
                actual.dynamicCalls.toString(),
                actual.portability.toString(),
                actual.persist.toString(),
                actual.tsvSha256,
            )
            val out = ArrayList<String>(FIELD_NAMES.size)
            for (i in FIELD_NAMES.indices) {
                if (lhs[i] == rhs[i]) continue
                out.add("reachability 基线不一致：${FIELD_NAMES[i]} 期望 ${lhs[i]} 实际 ${rhs[i]}")
            }
            return out
        }
    }
}
