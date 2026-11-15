@file:Suppress("TooManyFunctions", "LongParameterList")
package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.BuiltinContext
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlParseException
import com.eratw.port.core.xml.XmlParser
import com.eratw.port.core.xml.XmlText

/**
 * SQL 内建函数族的 XML 导入/导出扩展（P1 补齐上游 9 缺口，`SqlMethods.kt` 只做注册）。
 *
 * 语义锚全部在上游 fork 源码（只读）：
 * - `SQL_IMPORT_DT_XML`：`SqlManager.cs:340-420`（`DataTable.ReadXmlSchema` + `XmlReader`）；
 * - `SQL_EXPORT_MAP_XML`：`SqlManager.cs:422-459`（`SELECT k, v` + `WriteElementString`/`WriteRaw`）；
 * - `SQL_EXPORT_DT_XML`：`SqlManager.cs:461-515`（`SELECT * ... LIMIT 0` 的 schema 面在本移植
 *   走 `PRAGMA table_info`，数据面仍流式写）；
 * - `SQL_IMPORT_XML_CUSTOM`：`SqlManager.cs:516-603`（列映射 + `@attr` / `innerText` / `innerXml`）。
 *
 * 共同口径：
 * - 库未连接一律返回 0（四条上游函数都先 `TryGetValue` 再 `return 0`），不抛错；
 * - 文件缺失：DT 两条抛 CodeEE（上游 `File.Exists` 分支），通用导入返回 0
 *   （`SqlManager.cs:522` 的显式 `return 0`）；导出功能写文件，写失败抛 CodeEE；
 * - SQL/XML 失败统一 CodeEE 并保留 cause，禁止静默空表。
 *
 * 路径由 [FileOps] 解释；core 不假定工作目录（宿主根映射见 `FileOps` 的 KDoc）。
 * 文件路径先按 WorkingDir 再按 ExeDir 查找的上游双根语义在宿主侧单根实现，双根接线
 * 归宿主；本层只发相对路径。
 */
internal fun importDtXml(
    c: BuiltinContext,
    fn: String,
    dbName: String,
    tableName: String,
    schemaPath: String,
    dataPath: String,
): Long {
    val session = sqlSession(c, fn)
    val db = session.find(dbName) ?: return 0L
    val files = requireFileOps(c, fn)
    val schemaText = files.readText(schemaPath) ?: throw missingDtFile(c, fn, "架构文件", schemaPath)
    val dataText = files.readText(dataPath) ?: throw missingDtFile(c, fn, "数据文件", dataPath)
    val columns = parseDtSchema(schemaText, schemaPath, fn)
    val rows = parseDtRows(dataText, dataPath, tableName, columns, fn)
    val quotedTable = quoteIdentifier(tableName)
    val create = buildCreateTableSql(quotedTable, columns)
    val insert = buildInsertSql(quotedTable, columns)
    runImportTransaction(session, db, fn, "流式导入 DT XML 失败") {
        runQuery(session, db, create, emptyList())
        for (row in rows) {
            runQuery(session, db, insert, row.map { if (it == null) SqlBind.Null else SqlBind.Text(it) })
        }
    }
    return 1L
}

/**
 * `SQL_EXPORT_MAP_XML`：把 `SELECT k, v FROM <表>` 写成 `<map><p><k>…</k><v>raw</v></p></map>`。
 *
 * `v` 按上游 `WriteRaw` 原样落盘（允许值里带 XML 片段），`k` 按文本转义。
 */
internal fun exportMapXml(
    c: BuiltinContext,
    fn: String,
    dbName: String,
    tableName: String,
    path: String,
): Long {
    val session = sqlSession(c, fn)
    val db = session.find(dbName) ?: return 0L
    val files = requireFileOps(c, fn)
    val quotedTable = quoteIdentifier(tableName)
    val text = StringBuilder()
    text.append(XML_DECLARATION).append('\n').append("<map>\n")
    val handle = try {
        session.openReader(db, "SELECT k, v FROM $quotedTable").toLong()
    } catch (e: SqlNativeException) {
        throw exportFailure("导出 MAP XML 失败", fn, e)
    }
    try {
        while (session.readerRead(handle)) {
            val key = session.readerValue(handle, K_COLUMN, fn)
            val value = session.readerValue(handle, V_COLUMN, fn)
            text.append("  <p><k>").append(escapeXmlText(key.asText())).append("</k><v>")
            if (value !== SqlValue.VNull) text.append(value.asText())
            text.append("</v></p>\n")
        }
    } catch (e: SqlNativeException) {
        throw exportFailure("导出 MAP XML 失败", fn, e)
    } finally {
        runCatching { session.closeReader(handle) }
    }
    text.append("</map>\n")
    writeXmlFile(files, path, text.toString(), "导出 MAP XML 失败", fn)
    return 1L
}

/**
 * `SQL_EXPORT_DT_XML`：先用 `PRAGMA table_info` 取列名/声明类型（对应上游
 * `SELECT * FROM <表> LIMIT 0` + `DataTable.Load` 的 schema 面），写 DataTable 风格的
 * schema（`xs:schema` + 列 `xs:element`），再流式写 `<DocumentElement><表>…</表></DocumentElement>`。
 *
 * 整数列写 `xs:long` + `msdata:DataType="System.Int64"`；其余一律 `xs:string`
 * （上游 `SqlManager.cs:364` 只把 long/int 认成 INTEGER，`:383` 同口径）。
 */
internal fun exportDtXml(
    c: BuiltinContext,
    fn: String,
    dbName: String,
    tableName: String,
    schemaPath: String,
    dataPath: String,
): Long {
    val session = sqlSession(c, fn)
    val db = session.find(dbName) ?: return 0L
    val files = requireFileOps(c, fn)
    val quotedTable = quoteIdentifier(tableName)
    val columns = tableColumns(session, db, tableName, quotedTable, fn)
    val data = StringBuilder()
    data.append(XML_DECLARATION).append('\n').append("<DocumentElement>\n")
    val handle = try {
        session.openReader(db, "SELECT * FROM $quotedTable").toLong()
    } catch (e: SqlNativeException) {
        throw exportFailure("导出 DT XML 失败", fn, e)
    }
    try {
        appendDtRows(data, session, handle, columns, tableName, fn)
    } catch (e: SqlNativeException) {
        throw exportFailure("导出 DT XML 失败", fn, e)
    } finally {
        runCatching { session.closeReader(handle) }
    }
    data.append("</DocumentElement>\n")
    writeXmlFile(files, schemaPath, buildDtSchema(tableName, columns), "导出 DT XML 失败", fn)
    writeXmlFile(files, dataPath, data.toString(), "导出 DT XML 失败", fn)
    return 1L
}

private fun appendDtRows(
    data: StringBuilder,
    session: SqlSession,
    handle: Long,
    columns: List<DtColumn>,
    tableName: String,
    fn: String,
) {
    while (session.readerRead(handle)) {
        data.append("  <").append(tableName).append(">\n")
        appendDtRowCells(data, session, handle, columns, fn)
        data.append("  </").append(tableName).append(">\n")
    }
}

private fun appendDtRowCells(
    data: StringBuilder,
    session: SqlSession,
    handle: Long,
    columns: List<DtColumn>,
    fn: String,
) {
    for (i in columns.indices) {
        val value = session.readerValue(handle, i, fn)
        if (value === SqlValue.VNull) continue
        val name = columns[i].name
        data.append("    <").append(name).append('>')
            .append(escapeXmlText(value.asText()))
            .append("</").append(name).append(">\n")
    }
}

/**
 * `SQL_IMPORT_XML_CUSTOM`：按 `rowXPath` 最后一段找行元素，按 `columnMappings` 逐列取值。
 *
 * 映射表达式与上游一致（`SqlManager.cs:569-589`）：
 * - `@attr` → 行元素属性；
 * - `node(xml)` → 选中节点的 `InnerXml`；
 * - 其余 → 选中节点的 `InnerText`。
 *
 * 文件缺失返回 0（上游 :522）；XML/映射/SQL 失败抛 CodeEE。
 */
@Suppress("ReturnCount")
internal fun importXmlCustom(
    c: BuiltinContext,
    fn: String,
    dbName: String,
    tableName: String,
    filePath: String,
    rowXPath: String,
    columnMappings: String,
): Long {
    val session = sqlSession(c, fn)
    val db = session.find(dbName) ?: return 0L
    val files = requireFileOps(c, fn)
    val text = files.readText(filePath) ?: return 0L
    val mapping = parseMappings(columnMappings, fn)
    val target = rowXPath.split('/').lastOrNull { it.isNotBlank() }
        ?: throw CodeEE("自定义 XML 导入失败：$fn：rowXPath 为空")
    val doc = parseXml(text, filePath, fn, "自定义 XML 导入失败")
    val rows = ArrayList<XmlElement>()
    for (node in doc.children) {
        if (node is XmlElement) collectNamed(node, target, rows)
    }
    val quotedTable = quoteIdentifier(tableName)
    val create = "CREATE TABLE IF NOT EXISTS $quotedTable (" +
        mapping.keys.joinToString(", ") { "${quoteIdentifier(it)} TEXT" } + ")"
    val insert = "INSERT INTO $quotedTable (" +
        mapping.keys.joinToString(", ") { quoteIdentifier(it) } +
        ") VALUES (" + mapping.keys.indices.joinToString(", ") { "@$it" } + ")"
    runImportTransaction(session, db, fn, "自定义 XML 导入失败") {
        runQuery(session, db, create, emptyList())
        for (row in rows) {
            val params = mapping.values.map { expression ->
                val value = evaluateMapping(row, expression)
                if (value == null) SqlBind.Null else SqlBind.Text(value)
            }
            runQuery(session, db, insert, params)
        }
    }
    return 1L
}

// ---------------------------------------------------------------------------
// DT schema / row parsing
// ---------------------------------------------------------------------------

/** 一列 DT 形状：列名、是否 INTEGER、是否 `id` 主键（`SqlManager.cs:362-367`）。 */
private class DtColumn(val name: String, val integer: Boolean, val primaryKey: Boolean)

private fun parseDtSchema(text: String, path: String, fn: String): List<DtColumn> {
    val doc = parseXml(text, path, fn, "流式导入 DT XML 失败", preserveWhitespace = true)
    val elements = ArrayList<XmlElement>()
    for (node in doc.children) {
        if (node is XmlElement) collectElements(node, elements)
    }
    val columns = ArrayList<DtColumn>()
    val seen = HashSet<String>()
    for (element in elements) {
        val column = toDtColumn(element) ?: continue
        if (seen.add(column.name)) columns.add(column)
    }
    if (columns.isEmpty()) {
        throw CodeEE("流式导入 DT XML 失败：$fn：架构文件 $path 没有找到列定义")
    }
    return columns
}

/** 从 XSD 的 `xs:element` 折出一列；没有 `type` / `msdata:DataType` 的是表/数据集节点，跳过。 */
private fun toDtColumn(element: XmlElement): DtColumn? {
    val name = element.attribute("name")?.value
    val type = element.attribute("type")?.value
    val msType = element.attribute("msdata:DataType")?.value
    val isColumn = element.name.substringAfter(':') == "element" &&
        name != null && (type != null || msType != null)
    return if (isColumn) {
        DtColumn(name!!, isIntegerColumn(type, msType), name.equals("id", ignoreCase = true))
    } else {
        null
    }
}

private fun parseDtRows(
    text: String,
    path: String,
    tableName: String,
    columns: List<DtColumn>,
    fn: String,
): List<List<String?>> {
    val doc = parseXml(text, path, fn, "流式导入 DT XML 失败", preserveWhitespace = true)
    val rows = ArrayList<XmlElement>()
    for (node in doc.children) {
        if (node is XmlElement) collectNamed(node, tableName, rows)
    }
    return rows.map { row ->
        columns.map { column ->
            val cell = row.children.filterIsInstance<XmlElement>()
                .firstOrNull { it.name == column.name }
                ?: row.children.filterIsInstance<XmlElement>()
                    .firstOrNull { it.name.equals(column.name, ignoreCase = true) }
            cell?.innerText
        }
    }
}

private fun isIntegerColumn(type: String?, msDataType: String?): Boolean {
    if (msDataType == "System.Int64" || msDataType == "System.Int32") return true
    return type == "xs:long" || type == "xs:int" ||
        type == "xs:integer" || type == "xs:short" || type == "xs:byte"
}

// ---------------------------------------------------------------------------
// schema / SQL text
// ---------------------------------------------------------------------------

@Suppress("ThrowsCount")
private fun tableColumns(
    session: SqlSession,
    db: SqlConnection,
    tableName: String,
    quotedTable: String,
    fn: String,
): List<DtColumn> {
    // 用 PRAGMA table_info 取声明类型：sqlite3_column_type 在未 step 的 SELECT 上恒为 NULL，
    // 不能拿它判 INTEGER/REAL（上游 DataTable.Load 读的是 schema，不是首行值）。
    val handle = try {
        session.openReader(db, "PRAGMA table_info($quotedTable)").toLong()
    } catch (e: SqlNativeException) {
        throw exportFailure("导出 DT XML 失败", fn, e)
    }
    val columns = ArrayList<DtColumn>()
    try {
        while (session.readerRead(handle)) {
            val name = session.readerValue(handle, DT_NAME_COLUMN, fn).asText()
            if (name.isEmpty()) continue
            val declaredType = session.readerValue(handle, DT_TYPE_COLUMN, fn).asText()
            columns.add(
                DtColumn(
                    name = name,
                    integer = declaredType.contains("INT", ignoreCase = true),
                    // 上游只按列名 id 判主键，不读真实 PK 元数据（SqlManager.cs:365）；
                    // 复合主键表因此在导出 schema 里不带 PRIMARY KEY，与上游一致。
                    primaryKey = name.equals("id", ignoreCase = true),
                ),
            )
        }
    } catch (e: SqlNativeException) {
        throw exportFailure("导出 DT XML 失败", fn, e)
    } finally {
        runCatching { session.closeReader(handle) }
    }
    if (columns.isEmpty()) {
        throw CodeEE("导出 DT XML 失败：$fn：表 $tableName 不存在或没有列")
    }
    return columns
}

private fun buildCreateTableSql(quotedTable: String, columns: List<DtColumn>): String =
    "CREATE TABLE IF NOT EXISTS $quotedTable (" +
        columns.joinToString(", ") { column ->
            val type = if (column.integer) "INTEGER" else "TEXT"
            val pk = if (column.primaryKey) " PRIMARY KEY" else ""
            "${quoteIdentifier(column.name)} $type$pk"
        } + ")"

private fun buildInsertSql(quotedTable: String, columns: List<DtColumn>): String =
    "INSERT OR REPLACE INTO $quotedTable (" +
        columns.joinToString(", ") { quoteIdentifier(it.name) } +
        ") VALUES (" + columns.indices.joinToString(", ") { "@$it" } + ")"

private fun buildDtSchema(tableName: String, columns: List<DtColumn>): String {
    val table = escapeXmlAttribute(tableName)
    val text = StringBuilder()
    text.append(XML_DECLARATION).append('\n')
    text.append("<xs:schema id=\"").append(table).append("\" xmlns=\"\" ")
        .append("xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" ")
        .append("xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">\n")
    text.append("  <xs:element name=\"").append(table).append("\" msdata:IsDataSet=\"true\">\n")
    text.append("    <xs:complexType>\n")
    text.append("      <xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">\n")
    text.append("        <xs:element name=\"").append(table).append("\">\n")
    text.append("          <xs:complexType>\n")
    text.append("            <xs:sequence>\n")
    for (column in columns) {
        val name = escapeXmlAttribute(column.name)
        text.append("              <xs:element name=\"").append(name).append('"')
        if (column.integer) {
            text.append(" msdata:DataType=\"System.Int64\" type=\"xs:long\"")
        } else {
            text.append(" type=\"xs:string\"")
        }
        text.append(" minOccurs=\"0\" />\n")
    }
    text.append("            </xs:sequence>\n")
    text.append("          </xs:complexType>\n")
    text.append("        </xs:element>\n")
    text.append("      </xs:choice>\n")
    text.append("    </xs:complexType>\n")
    text.append("  </xs:element>\n")
    text.append("</xs:schema>\n")
    return text.toString()
}

// ---------------------------------------------------------------------------
// 通用导入映射
// ---------------------------------------------------------------------------

@Suppress("ThrowsCount")
private fun parseMappings(raw: String, fn: String): LinkedHashMap<String, String> {
    val mapping = LinkedHashMap<String, String>()
    for (entry in raw.split(',')) {
        val eq = entry.indexOf('=')
        if (eq <= 0 || eq == entry.lastIndex) {
            throw CodeEE("自定义 XML 导入失败：$fn：列映射 \"$raw\" 不是 Col=表达式 形态")
        }
        val key = entry.substring(0, eq).trim()
        val expression = entry.substring(eq + 1).trim()
        if (key.isEmpty() || expression.isEmpty()) {
            throw CodeEE("自定义 XML 导入失败：$fn：列映射 \"$raw\" 含空列名或空表达式")
        }
        mapping[key] = expression
    }
    if (mapping.isEmpty()) throw CodeEE("自定义 XML 导入失败：$fn：列映射为空")
    return mapping
}

private fun evaluateMapping(row: XmlElement, expression: String): String? = when {
    expression.startsWith('@') -> row.attribute(expression.substring(1))?.value
    expression.endsWith(XML_SUFFIX) -> selectNode(row, expression.removeSuffix(XML_SUFFIX))?.innerXml()
    else -> selectNode(row, expression)?.innerText
}

/** 简单相对路径选择（`a/b`、`./a`）；不实现完整 XPath，语料/上游样例只用单层或路径。 */
private fun selectNode(start: XmlElement, path: String): XmlElement? {
    var current = start
    for (segment in path.split('/')) {
        val name = segment.trim()
        if (name.isEmpty() || name == ".") continue
        current = current.children.filterIsInstance<XmlElement>()
            .firstOrNull { it.name == name } ?: return null
    }
    return current
}

private fun collectNamed(element: XmlElement, name: String, out: MutableList<XmlElement>) {
    if (element.name == name) out.add(element)
    for (child in element.children) {
        if (child is XmlElement) collectNamed(child, name, out)
    }
}

private fun collectElements(element: XmlElement, out: MutableList<XmlElement>) {
    out.add(element)
    for (child in element.children) {
        if (child is XmlElement) collectElements(child, out)
    }
}

// ---------------------------------------------------------------------------
// 文件 / 事务 / 错误
// ---------------------------------------------------------------------------

private fun requireFileOps(c: BuiltinContext, fn: String): FileOps =
    c.host.fileOps ?: throw CodeEE("函数 $fn：宿主未提供文件读写能力")

private fun missingDtFile(c: BuiltinContext, fn: String, kind: String, path: String): CodeEE {
    recordFileReadFailure(c, fn, path)
    return CodeEE("SQL_IMPORT_DT_XML: 找不到$kind '$path'")
}

private fun writeXmlFile(files: FileOps, path: String, text: String, prefix: String, fn: String) {
    if (!files.writeText(path, text, append = false)) {
        throw CodeEE("$prefix：$fn：写入文件失败 $path")
    }
}

@Suppress("TooGenericExceptionCaught")
private fun runImportTransaction(
    session: SqlSession,
    db: SqlConnection,
    fn: String,
    prefix: String,
    block: () -> Unit,
) {
    try {
        session.executeNonQuery(db, "BEGIN")
        try {
            block()
            session.executeNonQuery(db, "COMMIT")
        } catch (e: Throwable) {
            runCatching { session.executeNonQuery(db, "ROLLBACK") }
            throw e
        }
    } catch (e: Throwable) {
        throw CodeEE("$prefix：$fn：${e.message}").apply { initCause(e) }
    }
}

private fun runQuery(
    session: SqlSession,
    db: SqlConnection,
    sql: String,
    params: List<SqlBind>,
) {
    session.executeNonQuery(db, sql, params)
}

private fun parseXml(
    text: String,
    path: String,
    fn: String,
    prefix: String,
    preserveWhitespace: Boolean = false,
) = try {
    XmlParser.parse(text, preserveWhitespace)
} catch (e: XmlParseException) {
    throw CodeEE("$prefix：$fn：XML 解析失败（$path）：${e.message}").apply { initCause(e) }
}

private fun exportFailure(prefix: String, fn: String, cause: SqlNativeException): CodeEE =
    CodeEE("$prefix：$fn：${cause.message}").apply { initCause(cause) }

/** SQLite 标识符：双引号包、内部双引号成对转义。 */
private fun quoteIdentifier(raw: String): String = "\"" + raw.replace("\"", "\"\"") + "\""

/** 文本节点转义（复用 XML 家族的 .NET 口径，`>` 也转义）。 */
private fun escapeXmlText(raw: String): String = XmlText(raw).outerXml()

/** 属性值转义：文本规则 + 双引号。 */
private fun escapeXmlAttribute(raw: String): String =
    escapeXmlText(raw).replace("\"", "&quot;")

private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
private const val XML_SUFFIX = "(xml)"
private const val K_COLUMN = 0
private const val V_COLUMN = 1
private const val DT_NAME_COLUMN = 1
private const val DT_TYPE_COLUMN = 2
