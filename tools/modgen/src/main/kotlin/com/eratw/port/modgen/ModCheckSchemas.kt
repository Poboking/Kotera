package com.eratw.port.modgen

import com.eratw.port.modgen.accessors.AccessorEmit
import com.eratw.port.modgen.accessors.SchemaDecl
import com.eratw.port.modgen.accessors.parseSchema
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** 一份已解析的 schema TSV + 各声明行的 1 基行号（供 ERROR 点名 `文件:行`）。 */
internal class LoadedSchema(
    val path: Path,
    val label: String,
    val decl: SchemaDecl,
    val componentLines: Map<String, Int>,
    val archetypeLine: Int?,
    val fieldLines: Map<String, Int>,
)

private class SchemaLines(
    val componentLines: Map<String, Int>,
    val archetypeLine: Int?,
    val fieldLines: Map<String, Int>,
)

/** schema 面：加载/解析 TSV、比对生成物、双向核对字段键。 */
internal object ModCheckSchemas {
    private const val SCHEMAS_DIR = "schemas"
    private const val SCHEMA_SUFFIX = ".schema.tsv"
    private const val COMPONENT_COLUMN_INDEX = 3
    private const val NAME_COLUMN_INDEX = 1
    private const val LINE_NUMBER_BASE = 1

    private val LINE_PREFIX = Regex("""line (\d+):""")

    fun load(root: Path, explicit: List<File>, findings: MutableList<Finding>): List<LoadedSchema> {
        val files = if (explicit.isEmpty()) discover(root) else explicit.map { it.toPath() }
        if (files.isEmpty()) {
            val message = "未发现 schema TSV（缺省扫描 $SCHEMAS_DIR 目录）；schema 相关检查跳过"
            findings += Finding(Severity.WARN, SCHEMAS_DIR, null, message)
            return emptyList()
        }
        return files.mapNotNull { parseOne(root, it, findings) }
    }

    /**
     * 生成物面：schema 基名对号 `object <Base>Keys`，逐字节比对唯一的 accessor 生成结果。
     *
     * 反方向也查一遍：`src/generated` 里多出来的生成物（没有 schema TSV 对号）报 WARN ——
     * schema 删掉而生成物留着不会让构建报错，却会让 `XxxKeys` 变成无源之物。
     */
    fun checkGenerated(
        root: Path,
        schemas: List<LoadedSchema>,
        sources: List<SourceFile>,
        findings: MutableList<Finding>,
    ) {
        if (schemas.isEmpty()) return
        if (!Files.isDirectory(root.resolve(ModCheckSources.GENERATED_DIR))) {
            val message = "未发现生成物目录；请先接入 accessor 生成器再跑 check-mod（本项跳过）"
            findings += Finding(Severity.WARN, ModCheckSources.GENERATED_DIR, null, message)
            return
        }
        val byBase = ModCheckSources.generatedByBase(sources)
        schemas.forEach { schema -> checkOneGenerated(schema, byBase[schema.decl.baseName], findings) }
        reportOrphans(schemas, sources, findings)
    }

    /** 键面：字段键 ↔ schema 字段双向齐全（多 schema 共享一张全局字段表，键按并集核对）。 */
    fun checkKeys(schemas: List<LoadedSchema>, sources: List<SourceFile>, findings: MutableList<Finding>) {
        if (schemas.isEmpty()) return
        val keys = ModCheckSources.fieldKeys(sources)
        val declaredFields = schemas.flatMap { it.decl.fields }.map { it.name }.toSet()
        keys.forEach { key ->
            if (key.name !in declaredFields) {
                val message = "字段键 ${key.name} 未在任何 schema 中声明；" +
                    "核对 schema TSV 的 field 行拼写是否与该键一致，缺就在 schema 里补一行 field"
                findings += Finding(Severity.ERROR, key.file, key.line, message)
            }
        }
        schemas.forEach { schema ->
            schema.decl.fields.forEach { field ->
                if (keys.none { it.name == field.name }) {
                    val message = "schema 字段 ${field.name} 缺少 intKey/strKey/refKey 声明；" +
                        "在 Declarations 里补 intKey(\"${field.name}\") / strKey / refKey 声明"
                    findings += Finding(Severity.ERROR, schema.label, schema.fieldLines[field.name], message)
                }
            }
        }
    }

    /** 缺省布局的 schema 发现（`<root>/schemas` 目录下的 schema TSV，按路径排序）；指纹计算复用它，口径只有一份。 */
    internal fun discover(root: Path): List<Path> {
        val dir = root.resolve(SCHEMAS_DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        val stream = Files.list(dir)
        try {
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(SCHEMA_SUFFIX) }
                .sorted()
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun parseOne(root: Path, path: Path, findings: MutableList<Finding>): LoadedSchema? {
        val label = ModCheckSources.relativeLabel(root, path)
        return try {
            val text = Files.readString(path)
            val decl = parseSchema(text)
            val lines = schemaLines(text)
            LoadedSchema(path, label, decl, lines.componentLines, lines.archetypeLine, lines.fieldLines)
        } catch (error: IllegalArgumentException) {
            findings += Finding(Severity.ERROR, label, errorLine(error.message), "schema 解析失败：${error.message}")
            null
        } catch (error: IOException) {
            findings += Finding(Severity.ERROR, label, null, "schema 读取失败：${error.message}")
            null
        }
    }

    /** 从原始 TSV 抽出声明行号：头行第 4 列的组件算第 1 行，`component`/`archetype`/`field` 行各记自己。 */
    private fun schemaLines(text: String): SchemaLines {
        val componentLines = mutableMapOf<String, Int>()
        val fieldLines = mutableMapOf<String, Int>()
        var archetypeLine: Int? = null
        text.removeSuffix("\n").removeSuffix("\r").split('\n').forEachIndexed { index, rawLine ->
            val columns = rawLine.removeSuffix("\r").split('\t')
            val number = index + LINE_NUMBER_BASE
            when {
                columns[0] == "schema" && columns.size > COMPONENT_COLUMN_INDEX -> {
                    componentLines[columns[COMPONENT_COLUMN_INDEX]] = number
                }
                columns[0] == "component" && columns.size > NAME_COLUMN_INDEX -> {
                    componentLines[columns[NAME_COLUMN_INDEX]] = number
                }
                columns[0] == "archetype" -> archetypeLine = number
                columns[0] == "field" && columns.size > NAME_COLUMN_INDEX -> {
                    fieldLines[columns[NAME_COLUMN_INDEX]] = number
                }
            }
        }
        return SchemaLines(componentLines, archetypeLine, fieldLines)
    }

    private fun emitExpected(schema: LoadedSchema, findings: MutableList<Finding>): String? =
        try {
            AccessorEmit.emit(schema.decl)
        } catch (error: IllegalArgumentException) {
            findings += Finding(Severity.ERROR, schema.label, null, "生成器拒绝该 schema：${error.message}")
            null
        }

    private fun checkOneGenerated(schema: LoadedSchema, generated: SourceFile?, findings: MutableList<Finding>) {
        if (generated == null) {
            val message = "生成物缺失：${ModCheckSources.GENERATED_DIR} 下没有 object ${schema.decl.baseName}Keys；" +
                "重跑 :tools:modgen-accessors 生成器产出它，或核对 schema 的 component 基名"
            findings += Finding(Severity.ERROR, schema.label, null, message)
        } else {
            val expected = emitExpected(schema, findings)
            val actual = generated.raw.replace("\r\n", "\n")
            if (expected != null && expected != actual) {
                val line = firstDiffLine(expected, actual)
                val message = "生成物过期：与 ${schema.label} 不一致；" +
                    "重跑 :tools:modgen-accessors 生成器刷新生成物，不要手改 ${ModCheckSources.GENERATED_DIR}"
                findings += Finding(Severity.ERROR, generated.label, line, message)
            }
        }
    }

    private fun firstDiffLine(expected: String, actual: String): Int {
        val expectedLines = expected.split('\n')
        val actualLines = actual.split('\n')
        val size = minOf(expectedLines.size, actualLines.size)
        val index = (0 until size).firstOrNull { expectedLines[it] != actualLines[it] } ?: size
        return index + LINE_NUMBER_BASE
    }

    private fun errorLine(message: String?): Int? =
        message?.let { LINE_PREFIX.find(it)?.groupValues?.get(NAME_COLUMN_INDEX)?.toIntOrNull() }
}

/** 多出的生成物：按 `object <Base>Keys` 的基名比对 schema 基名集合（反方向检查）。 */
private fun reportOrphans(schemas: List<LoadedSchema>, sources: List<SourceFile>, findings: MutableList<Finding>) {
    val bases = schemas.map { it.decl.baseName }.toSet()
    sources.filter { it.isGenerated() }.forEach { source ->
        val base = source.keysBase()
        when {
            base == null -> {
                val message = "生成物里没有 object <Base>Keys，无法对号 schema"
                findings += Finding(Severity.WARN, source.label, null, message)
            }
            base !in bases -> {
                val message = "生成物没有对应的 schema TSV：$base（生成物多出或 schema 已删）"
                findings += Finding(Severity.WARN, source.label, null, message)
            }
        }
    }
}
