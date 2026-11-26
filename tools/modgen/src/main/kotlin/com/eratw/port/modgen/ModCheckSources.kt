package com.eratw.port.modgen

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** 去注释后的一行代码：1 基行号 + 该行代码文本。 */
internal data class CodeLine(val number: Int, val text: String)

/** 一个参与检查的 Kotlin 源文件：原始文本用于生成物逐字节比对，代码行用于正则扫描。 */
internal class SourceFile(
    val path: Path,
    val label: String,
    val raw: String,
    val code: List<CodeLine>,
)

/** 声明里的一处字段键调用（intKey/strKey/refKey 的字面量参数）。 */
internal data class KeyDecl(val name: String, val file: String, val line: Int)

/** 生成物文件相对 mod 根的路径前缀（与 [ModCheckSources.GENERATED_DIR] 同源）。 */
private const val GENERATED_PREFIX = "src/generated/"

/** 生成物里主键对象的名字形状：`object <Base>Keys`。 */
private val KEYS_OBJECT = Regex("""object\s+(\w+)Keys\b""")

/**
 * 源文件面：扫描 mod 模块的手写源码与生成物，并把注释剥掉（避免 KDoc 里的示例被当成声明）。
 *
 * 只认两个根：`src/main/kotlin`（手写声明/业务）与 `src/generated`（accessor 生成物）。
 */
internal object ModCheckSources {
    /** 生成物根目录（相对 mod 根）。 */
    const val GENERATED_DIR = "src/generated"

    private const val MAIN_KOTLIN = "src/main/kotlin"

    private val FIELD_KEY_CALL = Regex("""\b(?:intKey|strKey|refKey)\s*\(\s*"([^"]*)"""")

    fun scan(root: Path, findings: MutableList<Finding>): List<SourceFile> =
        discover(root).mapNotNull { read(root, it, findings) }

    private fun discover(root: Path): List<Path> =
        listOf(MAIN_KOTLIN, GENERATED_DIR)
            .flatMap { relative -> discoverDir(root.resolve(relative)) }
            .sorted()

    private fun discoverDir(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val stream = Files.walk(dir)
        try {
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun read(root: Path, path: Path, findings: MutableList<Finding>): SourceFile? {
        val label = relativeLabel(root, path)
        return try {
            val raw = Files.readString(path)
            SourceFile(path, label, raw, stripComments(raw))
        } catch (error: IOException) {
            findings += Finding(Severity.WARN, label, null, "无法读取源文件：${error.message}")
            null
        }
    }

    /** 文件相对 mod 根的标签（显式传入的模块外 schema 保留绝对路径）。 */
    fun relativeLabel(root: Path, path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        val relative = if (normalized.startsWith(root)) {
            root.relativize(normalized).toString()
        } else {
            normalized.toString()
        }
        return relative.replace('\\', '/')
    }

    /**
     * 剥掉 `//` 行注释与块注释（含 KDoc），保留行数：每个换行仍产出一行，行号与原始文件一致。
     *
     * 字符串里的 `//` 会被误判成注释起点 —— 检查面只做正则扫描，不要求字符串字面量精确；
     * 真需要精确的生成物比对走 [SourceFile.raw]。
     */
    fun stripComments(text: String): List<CodeLine> {
        val result = mutableListOf<CodeLine>()
        var inBlockComment = false
        text.split('\n').forEachIndexed { index, rawLine ->
            val (code, nextBlock) = stripLine(rawLine.removeSuffix("\r"), inBlockComment)
            inBlockComment = nextBlock
            result += CodeLine(index + 1, code)
        }
        return result
    }

    /** 单行注释剥离；返回剥离后的代码与「行尾是否仍在块注释里」的状态。 */
    private fun stripLine(line: String, blockComment: Boolean): Pair<String, Boolean> {
        val builder = StringBuilder()
        var inBlock = blockComment
        var index = 0
        while (index < line.length) {
            val char = line[index]
            val next = line.getOrNull(index + 1)
            when {
                inBlock && char == '*' && next == '/' -> {
                    inBlock = false
                    index += 2
                }
                inBlock -> index += 1
                char == '/' && next == '/' -> index = line.length
                char == '/' && next == '*' -> {
                    inBlock = true
                    index += 2
                }
                else -> {
                    builder.append(char)
                    index += 1
                }
            }
        }
        return builder.toString() to inBlock
    }

    /** 全部字段键调用，带文件标签与行号（用于「键多余」与「字段缺键」两个方向）。 */
    fun fieldKeys(sources: List<SourceFile>): List<KeyDecl> =
        sources.flatMap { source ->
            source.code.flatMap { line ->
                FIELD_KEY_CALL.findAll(line.text).map { KeyDecl(it.groupValues[1], source.label, line.number) }
            }
        }

    /** 生成物里 `object <Base>Keys` 的 Base → 源文件（按 schema 基名对号）。 */
    fun generatedByBase(sources: List<SourceFile>): Map<String, SourceFile> {
        val byBase = mutableMapOf<String, SourceFile>()
        sources.filter { it.isGenerated() }.forEach { source ->
            source.keysBase()?.let { byBase[it] = source }
        }
        return byBase
    }
}

/** [SourceFile] 是否位于 `src/generated` 生成物根下（反方向孤儿检查用）。 */
internal fun SourceFile.isGenerated(): Boolean = label.startsWith(GENERATED_PREFIX)

/** 生成物文件里声明的主键对象基名（`object <Base>Keys`）；没有则返回 null。 */
internal fun SourceFile.keysBase(): String? = KEYS_OBJECT.find(raw)?.groupValues[1]
