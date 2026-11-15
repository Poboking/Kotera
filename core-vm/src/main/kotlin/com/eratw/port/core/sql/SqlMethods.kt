package com.eratw.port.core.sql

import com.eratw.port.core.diag.FileReadTrail
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinContext
import com.eratw.port.core.fn.BuiltinMethod
import com.eratw.port.core.fn.BuiltinRegistry
import com.eratw.port.core.proc.FileReadAttempt
import com.eratw.port.core.proc.FileRootReadout
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlParseException
import com.eratw.port.core.xml.XmlParser

/**
 * SQL 内建函数族（[registerSqlMethods]）。**文件所有者：M1（SQLite 迁移 CORE-METHODS）**。
 *
 * 上游 fork 自加的 SQL 内建（`画蛇又添足版自改emuera相关说明.txt:22-30`、`:249-256`）
 * 加 `SQL_ESCAPE`，**完整面 = 上游注册表 25 条**（`Creator.cs:408-432`）。原版 Emuera
 * 没有这组函数，`reference/emuera-cs` 里查不到对应实现（grep SQL_CONNECT / SQL_EXECUTE
 * 零命中），所以函数面口径按上游 fork 注册表 + `SqlManager.cs` 实现钉住：
 * - `SQL_CONNECTION_OPEN "name"`：在宿主 SQL 写根下开 `<name>.db`，重开同名连接，
 *   PRAGMA WAL/NORMAL，返回 1（`SqlManager.cs:17-56`）。
 * - `SQL_CONNECT "name"[, "Data Source=path"]`：建命名连接，返回 1。
 * - `SQL_DISCONNECT "name"`：关闭并移除连接，恒返回 1（未连接 no-op，`SqlManager.cs:86-94`）。
 * - `SQL_EXECUTE_NONQUERY "db", "SQL"` / `SQL_P_EXECUTE_NONQUERY "db", "SQL", @0...`：
 *   执行非查询语句，返回**受影响行数**（`specs/builtin-functions.yaml:2305-2312`；source
 *   `Creator.Method.cs:10263-10270`、`SqlManager.cs:96-121`，本机无 fork-src）；语料从不读该返回值。
 * - `SQL_EXECUTE_SCALAR_STRING "db", "SQL"` / `SQL_P_EXECUTE_SCALAR_STRING`：
 *   第一行第一列文本，无结果 `""`（qol_dish.ERB:59-62 的注释口径）。
 * - `SQL_EXECUTE_SCALAR_LONG "db", "SQL"` / `SQL_P_EXECUTE_SCALAR_LONG`：
 *   第一行第一列整数，无结果 `0`（qol_dish.ERB:64-67 的注释口径）。
 * - `SQL_EXECUTE_SCALAR_FLOAT "db", "SQL"` / `SQL_P_EXECUTE_SCALAR_FLOAT`：
 *   第一行第一列浮点，无结果 `0.0`（`SqlManager.cs:238-282`、`Creator.Method.cs:10743-10774`）。
 * - `SQL_EXECUTE_READER "db", "SQL"` / `SQL_P_EXECUTE_READER`：返回 reader 句柄。
 * - `SQL_READER_READ`(句柄)、`SQL_READER_GET_LONG`/`GET_FLOAT`/`GET_STRING`/`ISNULL`
 *   (句柄, 列下标)、`SQL_READER_CLOSE`（qol_dish.ERB:467-483、qol_graph_query.ERB:94-99 的用法）。
 *   `SQL_READER_READ` 对无效句柄返回 0（不抛错，`specs/builtin-functions.yaml:2321-2327`），
 *   而 `GET_LONG`/`GET_FLOAT`/`GET_STRING`/`ISNULL` 对无效句柄仍抛 CodeEE「无效的 Reader ID」
 *   （同文件 :2334/:2342；`SqlManager.cs:188-197`）。
 * - `SQL_IMPORT_MAP_XML "db", "mapName", "path"`：把 XML 的 k/v 对灌进 MAP 表
 *   （qol_db.ERB:37-38 的说明、:61-62 的调用）。失败口径同样按 spec（未连接返 0 不抛错、
 *   文件找不到抛 CodeEE、成功返回 1）。
 * - `SQL_IMPORT_DT_XML` / `SQL_EXPORT_DT_XML` / `SQL_EXPORT_MAP_XML` /
 *   `SQL_IMPORT_XML_CUSTOM`：DataTable XML 与通用映射的导入导出，实现见 `SqlXmlMethods.kt`。
 * - `SQL_ESCAPE "text"`：把单引号成对转义（`''`），用于拼接无参数化语句。
 *
 * **执行面全部走 [SqlDriver] 端口**（`CONTRACT-SQLITE-MIGRATION` §1/§2）：本文件不再解析
 * SQL、不再维护内存表、不再读 SQLite 文件。`SqlSession` 只做连接名/reader 句柄记账，
 * `prepare` / `step` / `totalChanges` 的真实语义由宿主注入的 bundled SQLite 驱动兑现。
 * 驱动抛出的 [SqlNativeException] 一律在本层包成 [CodeEE]：语句路径保留既有
 * `SQL 执行失败：…（语句：<原文>）` 文案，连接/读取路径给出带函数名的 CodeEE，玩家面
 * 文案与迁移前同族。
 *
 * 状态一律挂 `store.aux` 的 `"sql"` 槽（[SqlSession]）：铁律 4 禁静态可变全局，
 * `RuntimeAux.clear()` 即原版 `GlobalStatic.Reset()` 里的 `SqlManager.CloseAll()`
 * （自改说明 :258-259）。
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
internal fun registerSqlMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    fun intVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { c, a -> Value.IntV(fn(c, a)) })
    }

    fun strVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> String) {
        r.register(BuiltinMethod(name, S, min, max) { c, a -> Value.StrV(fn(c, a)) })
    }

    fun floatVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Double) {
        r.register(BuiltinMethod(name, OperandType.FLOAT, min, max) { c, a -> Value.FloatV(fn(c, a)) })
    }

    // ---- 连接 ----

    // 上游专有的「按名开沙箱库」：文件名落宿主 SQL 写根（生产 = gameRoot/sql，对应上游
    // SavDir/sql）。名字校验照抄 SqlManager.cs:19-20（空白、路径字符、`..` 一律拒绝），
    // 并把宿主路径闸的 IAE 一并折成 CodeEE。
    intVal("SQL_CONNECTION_OPEN", 1, 1) { c, a ->
        val fn = "SQL_CONNECTION_OPEN"
        val name = argText(a, 0, fn)
        if (isUnsafeDatabaseName(name)) {
            throw CodeEE("SQL_CONNECTION_OPEN: 无效或不安全的数据库名称 '$name'。名称不能包含路径字符或非法字符。")
        }
        try {
            sqlSession(c, fn).connectionOpen(name, "$name.db")
        } catch (e: SqlNativeException) {
            throw CodeEE("函数 $fn：数据库连接 \"$name\" 打开失败：${e.message}").apply { initCause(e) }
        } catch (e: IllegalArgumentException) {
            throw CodeEE("函数 $fn：数据库连接 \"$name\" 打开失败：${e.message}").apply { initCause(e) }
        }
        1L
    }

    // 语料里每个 @..._INIT 都重连一次（qol_item.ERB:64 等），必须幂等；
    // 第二参数缺省 = 沿用已有连接（qol_db.ERB:60 的 SQL_CONNECT "TR_DB"）。
    // 连接串原样交给宿主驱动（Data Source 的真实文件/沙箱映射由 adapter 决定），
    // 本层不读文件、不建内存库。
    intVal("SQL_CONNECT", 1, 2) { c, a ->
        val fn = "SQL_CONNECT"
        val name = argText(a, 0, fn)
        val connectionString = if (a.size > 1) argText(a, 1, fn) else null
        try {
            sqlSession(c, fn).connect(name, connectionString)
        } catch (e: SqlNativeException) {
            throw CodeEE("函数 $fn：数据库连接 \"$name\" 打开失败：${e.message}").apply { initCause(e) }
        } catch (e: IllegalArgumentException) {
            // adapter 的路径闸（绝对路径/`..` 出根）抛 IAE；连接边界统一包 CodeEE，
            // 不让宿主路径策略以裸异常穿透脚本层。
            throw CodeEE("函数 $fn：数据库连接 \"$name\" 打开失败：${e.message}").apply { initCause(e) }
        }
        1L
    }

    // 恒返回 1；未连接是 no-op（SqlManager.cs:86-94）。关闭失败仍按 CodeEE 上抛，
    // 不让宿主异常以裸形态穿透脚本层。
    intVal("SQL_DISCONNECT", 1, 1) { c, a ->
        val fn = "SQL_DISCONNECT"
        val name = argText(a, 0, fn)
        try {
            sqlSession(c, fn).disconnect(name)
        } catch (e: SqlNativeException) {
            throw CodeEE("函数 $fn：数据库连接 \"$name\" 关闭失败：${e.message}").apply { initCause(e) }
        }
        1L
    }

    // ---- 非查询 ----

    intVal("SQL_EXECUTE_NONQUERY", 2, 2) { c, a ->
        val fn = "SQL_EXECUTE_NONQUERY"
        executeNonQuery(
            c, fn, argText(a, 0, fn), argText(a, 1, fn), emptyList(),
        )
    }

    // 可变参数：qol_dish.ERB:99 / qol_fish.ERB:98 的 SQL_P_EXECUTE_* 带 0 个占位参数。
    intVal("SQL_P_EXECUTE_NONQUERY", 2, -1) { c, a ->
        val fn = "SQL_P_EXECUTE_NONQUERY"
        executeNonQuery(
            c, fn, argText(a, 0, fn), argText(a, 1, fn), sqlParams(a, 2),
        )
    }

    // ---- 单值查询 ----

    strVal("SQL_EXECUTE_SCALAR_STRING", 2, 2) { c, a ->
        val fn = "SQL_EXECUTE_SCALAR_STRING"
        executeScalar(
            c, fn, argText(a, 0, fn), argText(a, 1, fn), emptyList(),
        )?.asText() ?: ""
    }

    strVal("SQL_P_EXECUTE_SCALAR_STRING", 2, -1) { c, a ->
        val fn = "SQL_P_EXECUTE_SCALAR_STRING"
        executeScalar(
            c, fn, argText(a, 0, fn), argText(a, 1, fn), sqlParams(a, 2),
        )?.asText() ?: ""
    }

    intVal("SQL_EXECUTE_SCALAR_LONG", 2, 2) { c, a ->
        val fn = "SQL_EXECUTE_SCALAR_LONG"
        scalarLong(
            executeScalar(
                c, fn, argText(a, 0, fn), argText(a, 1, fn), emptyList(),
            ),
        )
    }

    intVal("SQL_P_EXECUTE_SCALAR_LONG", 2, -1) { c, a ->
        val fn = "SQL_P_EXECUTE_SCALAR_LONG"
        scalarLong(
            executeScalar(
                c, fn, argText(a, 0, fn), argText(a, 1, fn), sqlParams(a, 2),
            ),
        )
    }

    // 上游 `ExecuteScalar<double>`：NULL/无行返回 0.0，其余按 Convert.ToDouble 折算
    // （SqlManager.cs:238-282）。文本列不能数值化时上游落 catch(Exception) → CodeEE
    // 「SQL 标量查询错误」；本层保留同一文案族并带语句原文。
    floatVal("SQL_EXECUTE_SCALAR_FLOAT", 2, 2) { c, a ->
        val fn = "SQL_EXECUTE_SCALAR_FLOAT"
        val sql = argText(a, 1, fn)
        scalarDouble(
            executeScalar(c, fn, argText(a, 0, fn), sql, emptyList()),
            fn,
            sql,
        )
    }

    floatVal("SQL_P_EXECUTE_SCALAR_FLOAT", 2, -1) { c, a ->
        val fn = "SQL_P_EXECUTE_SCALAR_FLOAT"
        val sql = argText(a, 1, fn)
        scalarDouble(
            executeScalar(c, fn, argText(a, 0, fn), sql, sqlParams(a, 2)),
            fn,
            sql,
        )
    }

    // ---- reader ----

    intVal("SQL_EXECUTE_READER", 2, 2) { c, a ->
        val fn = "SQL_EXECUTE_READER"
        openReader(c, fn, argText(a, 0, fn), argText(a, 1, fn), emptyList())
    }

    intVal("SQL_P_EXECUTE_READER", 2, -1) { c, a ->
        val fn = "SQL_P_EXECUTE_READER"
        openReader(c, fn, argText(a, 0, fn), argText(a, 1, fn), sqlParams(a, 2))
    }

    // 推进一行；到末尾返回 0。句柄不存在/已关闭同样返回 0、不抛错
    // （specs/builtin-functions.yaml:2321-2327：「readerId 无效时返回 0（不抛错）」）。
    intVal("SQL_READER_READ", 1, 1) { c, a ->
        val fn = "SQL_READER_READ"
        val handle = argLong(a, 0, fn)
        val session = sqlSession(c, fn)
        val hasRow = try {
            session.readerRead(handle)
        } catch (e: SqlNativeException) {
            throw readerFailure(fn, e)
        }
        if (hasRow) 1L else 0L
    }

    intVal("SQL_READER_GET_LONG", 2, 2) { c, a ->
        val fn = "SQL_READER_GET_LONG"
        when (val v = readerCell(c, a, fn)) {
            is SqlValue.VInt -> v.v
            is SqlValue.VFloat -> doubleToLong(v.v)
            is SqlValue.VText -> v.v.toLongOrNull()
                ?: throw CodeEE("函数 $fn：第 ${cellIndex(a, fn) + 1} 列不是数值（\"${v.v}\"）")
            SqlValue.VNull -> 0L
        }
    }

    // 上游 ReaderGetFloat（SqlManager.cs:188-197）：NULL → 0.0，其余 GetDouble；
    // 无效句柄抛 CodeEE「无效的 Reader ID」。
    floatVal("SQL_READER_GET_FLOAT", 2, 2) { c, a ->
        val fn = "SQL_READER_GET_FLOAT"
        when (val v = readerCell(c, a, fn)) {
            is SqlValue.VInt -> v.v.toDouble()
            is SqlValue.VFloat -> v.v
            is SqlValue.VText -> v.v.toDoubleOrNull()
                ?: throw CodeEE("函数 $fn：第 ${cellIndex(a, fn) + 1} 列不是数值（\"${v.v}\"）")
            SqlValue.VNull -> 0.0
        }
    }

    strVal("SQL_READER_GET_STRING", 2, 2) { c, a ->
        readerCell(c, a, "SQL_READER_GET_STRING").asText()
    }

    intVal("SQL_READER_ISNULL", 2, 2) { c, a ->
        if (readerCell(c, a, "SQL_READER_ISNULL") === SqlValue.VNull) 1L else 0L
    }

    // 句柄不存在也返回 1：语料里 CLOSE 的位置固定（qol_graph_query.ERB:147-148 关两次），
    // 重复关闭不应中断脚本。
    intVal("SQL_READER_CLOSE", 1, 1) { c, a ->
        val fn = "SQL_READER_CLOSE"
        val handle = argLong(a, 0, fn)
        try {
            sqlSession(c, fn).closeReader(handle)
        } catch (e: SqlNativeException) {
            throw readerFailure(fn, e)
        }
        1L
    }

    // ---- MAP XML 导入 ----

    // 口径全部按 spec（specs/builtin-functions.yaml 的 SQL_IMPORT_MAP_XML 段，source
    // fork-src Creator.Method.cs:10343-10350、SqlManager.cs:285-339）：
    // - dbName 未连接 → 返回 0、**不抛错**（与 SQL_EXECUTE_* 族的「未连接抛」不同轴）；
    // - 文件路径找不到 → 抛 CodeEE（文案前缀「SQL_IMPORT_MAP_XML」）——4.981 随包
    //   plugins/tw_csv_chs.xml / tw_taste_chs.xml 恒在（qol_db.ERB:61-62 的两条调用无
    //   存在性检查，靠的是随包文件恒在），主语料两侧同值；
    // - XML 解析失败 → 抛 CodeEE（禁止静默空表）；
    // - 成功 → 返回 1（不是导入行数；语料按裸语句调用、从不读返回值）。
    intVal("SQL_IMPORT_MAP_XML", 3, 3) { c, a ->
        val fn = "SQL_IMPORT_MAP_XML"
        val dbName = argText(a, 0, fn)
        val tableName = argText(a, 1, fn)
        val path = argText(a, 2, fn)
        val session = sqlSession(c, fn)
        val db = session.find(dbName) ?: return@intVal 0L
        val fileOps = c.host.fileOps
            ?: throw CodeEE("函数 SQL_IMPORT_MAP_XML：宿主未提供文件读取能力")
        val text = fileOps.readText(path)
        if (text == null) {
            // 判读通道（2026-11-05 report-sqlroot）：**屏上文案一字不动**，把「试的哪个根、
            // 解析落点、是否出根被拒」记进 store.aux 的读轴留痕 + System.err（通道选型与
            // 隐私口径见 FileReadTrail 的 KDoc；官方口径不可核——fork 专有函数，ADR-005 不动原文）。
            recordFileReadFailure(c, "SQL_IMPORT_MAP_XML", path)
            throw CodeEE("SQL_IMPORT_MAP_XML：找不到文件 $path")
        }
        val pairs = parseMapXml(text, path)
        importKeyValueRows(session, db, tableName, pairs, fn)
        1L
    }

    // ---- DT / 通用 XML 导入导出（P1 补齐上游 9 缺口的 XML 面）----
    //
    // 语义锚：SqlManager.cs:284-420（ImportDtXml / ExportMapXml / ExportDtXml /
    // ImportXmlCustom）与 Creator.Method.cs:10395-10429、:10734-10774。
    // 未连接一律返回 0（四条上游函数都先 `TryGetValue` 再 `return 0`）；
    // 文件名缺失/解析/SQL 失败一律 CodeEE 并保留 cause。

    intVal("SQL_IMPORT_DT_XML", SQL_IMPORT_DT_ARGS, SQL_IMPORT_DT_ARGS) { c, a ->
        importDtXml(
            c, "SQL_IMPORT_DT_XML",
            argText(a, 0, "SQL_IMPORT_DT_XML"),
            argText(a, 1, "SQL_IMPORT_DT_XML"),
            argText(a, 2, "SQL_IMPORT_DT_XML"),
            argText(a, 3, "SQL_IMPORT_DT_XML"),
        )
    }

    intVal("SQL_EXPORT_MAP_XML", 3, 3) { c, a ->
        exportMapXml(
            c, "SQL_EXPORT_MAP_XML",
            argText(a, 0, "SQL_EXPORT_MAP_XML"),
            argText(a, 1, "SQL_EXPORT_MAP_XML"),
            argText(a, 2, "SQL_EXPORT_MAP_XML"),
        )
    }

    intVal("SQL_EXPORT_DT_XML", SQL_EXPORT_DT_ARGS, SQL_EXPORT_DT_ARGS) { c, a ->
        exportDtXml(
            c, "SQL_EXPORT_DT_XML",
            argText(a, 0, "SQL_EXPORT_DT_XML"),
            argText(a, 1, "SQL_EXPORT_DT_XML"),
            argText(a, 2, "SQL_EXPORT_DT_XML"),
            argText(a, 3, "SQL_EXPORT_DT_XML"),
        )
    }

    intVal("SQL_IMPORT_XML_CUSTOM", SQL_IMPORT_CUSTOM_ARGS, SQL_IMPORT_CUSTOM_ARGS) { c, a ->
        importXmlCustom(
            c, "SQL_IMPORT_XML_CUSTOM",
            argText(a, 0, "SQL_IMPORT_XML_CUSTOM"),
            argText(a, 1, "SQL_IMPORT_XML_CUSTOM"),
            argText(a, 2, "SQL_IMPORT_XML_CUSTOM"),
            argText(a, 3, "SQL_IMPORT_XML_CUSTOM"),
            argText(a, SQL_IMPORT_CUSTOM_ARGS - 1, "SQL_IMPORT_XML_CUSTOM"),
        )
    }

    // ---- 转义 ----

    strVal("SQL_ESCAPE", 1, 1) { _, a -> argText(a, 0, "SQL_ESCAPE").replace("'", "''") }
}

// ---------------------------------------------------------------------------
// 参数与结果
// ---------------------------------------------------------------------------

/**
 * 取本 VM 的 SQL 会话；宿主未注入 [BuiltinHost.sqlDriver] 时抛 CodeEE。
 *
 * 契约 §1：`null = 宿主未接线，SQL_* 必须抛 CodeEE，禁止静默建内存库`。先查 aux 槽再
 * 校验驱动：会话一旦按真实驱动建出来就原样复用（L1 的 close 链负责收口），不会因为
 * 同一 VM 内再次调用而重建。
 */
internal fun sqlSession(c: BuiltinContext, fn: String): SqlSession {
    c.store.aux.peek<SqlSession>(SQL_AUX_SLOT)?.let { return it }
    val driver = c.host.sqlDriver
        ?: throw CodeEE("函数 $fn：宿主未接线 SQLite 驱动（SQL_CONNECT 不可用）")
    return c.store.aux.slot(SQL_AUX_SLOT) { SqlSession(driver) }
}

/**
 * `SQL_CONNECTION_OPEN` 的库名校验（`SqlManager.cs:19-20`）：
 * 空白、含 `..`、或含 Windows 非法文件名字符/控制符一律拒绝。
 *
 * 额外收掉 `=`：连接串解析层把 `=` 当键值分隔符，放过去会让 `a=b.db` 在 adapter 里
 * 报「缺少 Data Source/Filename」而不是库名错误；这里给出与上游同族的早期 CodeEE。
 */
internal fun isUnsafeDatabaseName(name: String): Boolean =
    name.isBlank() ||
        name.contains("..") ||
        name.contains('=') ||
        name.any { it < ' ' || it in INVALID_DATABASE_NAME_CHARS }

private const val INVALID_DATABASE_NAME_CHARS = "\\/:*?\"<>|"

/** 上游 SQL_IMPORT_DT_XML 的实参数（db, table, schema, data）。 */
private const val SQL_IMPORT_DT_ARGS = 4

/** 上游 SQL_EXPORT_DT_XML 的实参数（db, table, schema, data）。 */
private const val SQL_EXPORT_DT_ARGS = 4

/** 上游 SQL_IMPORT_XML_CUSTOM 的实参数（db, table, path, rowXPath, mappings）。 */
private const val SQL_IMPORT_CUSTOM_ARGS = 5

/**
 * 记一次失败的宿主文件读（只判读、不改语义；永远不抛）。
 *
 * 读数来源：`fileOps as? FileRootReadout`（生产形态 WriteBlockedFileOps → RootedFileOps
 * 都实现了该只读能力位；自定义宿主桩拿不到根 ⇒ 记「无根读数」形状，仍比只报相对路径多
 * 「宿主未提供根读数」这一条判据）。落点：`store.aux` 的 [FileReadTrail] 槽
 * （铁律 4：状态随 VM，不挂静态）。宿主接进报告只需一行
 * `FileReadTrail.trailLines(session.store)` → [com.eratw.port.core.diag.ErrorReport] 的
 * `extraSectionLines`（接线归组长，见交付）。
 */
internal fun recordFileReadFailure(c: BuiltinContext, function: String, path: String) {
    runCatching {
        val attempt = (c.host.fileOps as? FileRootReadout)?.describeReadAttempt(path)
            ?: FileReadAttempt(
                request = path, root = null, resolved = null,
                rejectedOutOfRoot = false, regularFile = false, isDirectory = false,
            )
        c.store.aux.slot(FileReadTrail.AUX_KEY) { FileReadTrail() }.record(function, attempt)
    }
}

/** 语句路径的统一失败文案：保留迁移前的 `SQL 执行失败：…（语句：<原文>）` 形状。 */
private fun sqlFailure(fn: String, sql: String, cause: SqlNativeException): CodeEE =
    CodeEE("SQL 执行失败：$fn：${cause.message}（语句：${sql.trim()}）").apply { initCause(cause) }

/** reader 路径没有语句原文可带，按函数名给出同族 CodeEE。 */
private fun readerFailure(fn: String, cause: SqlNativeException): CodeEE =
    CodeEE("函数 $fn：SQLite 读取失败：${cause.message}").apply { initCause(cause) }

private fun executeNonQuery(
    c: BuiltinContext,
    fn: String,
    connection: String,
    sql: String,
    params: List<SqlBind>,
): Long {
    val session = sqlSession(c, fn)
    val db = session.require(connection, fn)
    return try {
        session.executeNonQuery(db, sql, params)
    } catch (e: SqlNativeException) {
        throw sqlFailure(fn, sql, e)
    }
}

private fun executeScalar(
    c: BuiltinContext,
    fn: String,
    connection: String,
    sql: String,
    params: List<SqlBind>,
): SqlValue? {
    val session = sqlSession(c, fn)
    val db = session.require(connection, fn)
    return try {
        session.executeScalar(db, sql, params)
    } catch (e: SqlNativeException) {
        throw sqlFailure(fn, sql, e)
    }
}

/**
 * 可选变参从下标 [start] 起按序绑定到 `@0..@N`。
 *
 * 绑定**全部按文本**：fork 的 `SQL_P_*` 形参表是
 * `ArgTypeList{ ArgTypes={String,String,VariadicString}, OmitStart=2 }`
 * （specs/verify-notes.md §23.2，source fork-src Creator.Method.cs:2653-2743）⇒
 * 首个 SQL 串之后的实参一律先 `GetStrValue` 转十进制串再绑 SQLite TEXT
 * （specs/builtin-functions.yaml 的 SQL_P_EXECUTE_NONQUERY 段明文「整数实参自动转十进制串」）。
 * 4.981 语料自身全部用 `TOSTR(...)` 包整数（qol_mushi.ERB:114、qol_graph_init.ERB:129 等），
 * 主语料两侧同值；裸整数实参只在第三方口上可达 —— 按 INTEGER 绑定会让
 * `WHERE k = '123'` 这类 SQL 字符串字面量比较对整数存储静默不命中（SQLite 跨型比较）。
 * 浮点（4.981 / u1-float 引入的第四种 Value）同样按十进制文本绑定。
 */
private fun sqlParams(args: List<Value?>, start: Int): List<SqlBind> =
    args.drop(start).map { v ->
        when (v) {
            is Value.IntV -> SqlBind.Text(v.v.toString())
            is Value.StrV -> SqlBind.Text(v.v)
            is Value.FloatV -> SqlBind.Text(v.v.toString())
            null -> SqlBind.Null
        }
    }

private fun scalarLong(value: SqlValue?): Long = when (value) {
    is SqlValue.VInt -> value.v
    is SqlValue.VFloat -> doubleToLong(value.v)
    is SqlValue.VText -> value.v.toLongOrNull() ?: 0L
    SqlValue.VNull, null -> 0L
}

/**
 * `Convert.ToInt64(double)` 的 C# 口径：四舍六入五成双（banker's rounding）。
 * `Math.rint` 正好是同一舍入模式；直接 `toLong()` 会截断，`roundToLong()` 是五入。
 */
private fun doubleToLong(value: Double): Long = Math.rint(value).toLong()

/**
 * `ExecuteScalar<double>` 的移植：NULL/无行 → 0.0；整数/浮点原样宽化；
 * 文本按 `Convert.ToDouble` 折算，折不动时按上游 catch(Exception) 的「SQL 标量查询错误」
 * 族给 CodeEE（保留语句原文）。
 */
private fun scalarDouble(value: SqlValue?, fn: String, sql: String): Double = when (value) {
    is SqlValue.VInt -> value.v.toDouble()
    is SqlValue.VFloat -> value.v
    is SqlValue.VText -> value.v.trim().toDoubleOrNull()
        ?: throw CodeEE("SQL 标量查询错误：$fn：首列不是数值（\"${value.v}\"）（语句：${sql.trim()}）")
    SqlValue.VNull, null -> 0.0
}

private fun openReader(
    c: BuiltinContext,
    fn: String,
    connection: String,
    sql: String,
    params: List<SqlBind>,
): Long {
    val session = sqlSession(c, fn)
    val db = session.require(connection, fn)
    val handle = try {
        session.openReader(db, sql, params)
    } catch (e: SqlNativeException) {
        throw sqlFailure(fn, sql, e)
    }
    return handle.toLong()
}

private fun readerCell(c: BuiltinContext, args: List<Value?>, fn: String): SqlValue {
    val session = sqlSession(c, fn)
    val handle = argLong(args, 0, fn)
    val index = cellIndex(args, fn)
    return try {
        session.readerValue(handle, index, fn)
    } catch (e: SqlNativeException) {
        throw readerFailure(fn, e)
    }
}

private fun cellIndex(args: List<Value?>, fn: String): Int = argLong(args, 1, fn).toInt()

/** 必填实参按文本取用；整数实参也接受（连接名/语句在语料里可能经 TOSTR 传来）。 */
private fun argText(args: List<Value?>, i: Int, fn: String): String {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn 缺少第 ${i + 1} 个参数")
    return when (v) {
        is Value.IntV -> v.v.toString()
        is Value.StrV -> v.v
        is Value.FloatV -> v.v.toString()
    }
}

private fun argLong(args: List<Value?>, i: Int, fn: String): Long {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn 缺少第 ${i + 1} 个参数")
    return (v as? Value.IntV)?.v
        ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是数值")
}

/**
 * `SQL_IMPORT_MAP_XML` 的真实 SQL 落地：建表（若缺）→ 清空 → 逐对 `INSERT OR REPLACE`。
 *
 * 表标识符按 SQLite 规则用双引号包、内部双引号成对转义，避免表名撞保留字/特殊字符。
 * 三步都走 [SqlSession.executeNonQuery]；[SqlNativeException] 由 [runNonQuery] 包成
 * CodeEE 并带语句原文，绝不吞成空表。
 */
private fun importKeyValueRows(
    session: SqlSession,
    db: SqlConnection,
    tableName: String,
    pairs: List<Pair<String, String>>,
    fn: String,
) {
    val quoted = "\"" + tableName.replace("\"", "\"\"") + "\""
    val create = "CREATE TABLE IF NOT EXISTS $quoted (k TEXT PRIMARY KEY, v TEXT)"
    val delete = "DELETE FROM $quoted"
    val insert = "INSERT OR REPLACE INTO $quoted (k, v) VALUES (@0, @1)"
    // 上游 `SqlManager.ImportMapXml`（`SqlManager.cs:299` 开、`:332` 提交）把建表、清表、
    // 逐对灌入整批包在一个**显式**事务里；迁移时这条边界被悄悄丢了，退化成每行一次自动
    // 提交。文件库上每次自动提交都要 fsync（P5 实测 36418 行自动提交 244.2 s / 单事务
    // 0.49 s，503×），而且半途失败会留下清了一半的表。这里恢复上游边界：事务由本函数
    // 显式开/关，不吞到调用方，也不把多次脚本调用伪装成一个事务。
    runNonQuery(session, db, fn, BEGIN_TRANSACTION, emptyList())
    var committed = false
    try {
        runNonQuery(session, db, fn, create, emptyList())
        runNonQuery(session, db, fn, delete, emptyList())
        for ((k, v) in pairs) {
            runNonQuery(session, db, fn, insert, listOf(SqlBind.Text(k), SqlBind.Text(v)))
        }
        runNonQuery(session, db, fn, COMMIT_TRANSACTION, emptyList())
        committed = true
    } finally {
        // 与上游 `using var trans` 的 Dispose 语义一致：没走到 COMMIT 就回滚整批；回滚
        // 自身失败不覆盖原始异常（玩家面报第一次错误，事务状态由连接关闭收口）。
        if (!committed) {
            runCatching { runNonQuery(session, db, fn, ROLLBACK_TRANSACTION, emptyList()) }
        }
    }
}

/** 语料/上游 SQLite 的事务控制语句字面量（`qol_graph_init.ERB:68/:85` 同形）。 */
private const val BEGIN_TRANSACTION = "BEGIN TRANSACTION"
private const val COMMIT_TRANSACTION = "COMMIT"
private const val ROLLBACK_TRANSACTION = "ROLLBACK"

private fun runNonQuery(
    session: SqlSession,
    db: SqlConnection,
    fn: String,
    sql: String,
    params: List<SqlBind>,
) {
    try {
        session.executeNonQuery(db, sql, params)
    } catch (e: SqlNativeException) {
        throw sqlFailure(fn, sql, e)
    }
}

/**
 * `SQL_IMPORT_MAP_XML` 的 XML 形态（`plugins/tw_csv_chs.xml`）：
 * `<map><p><k>键</k><v>值</v></p>...</map>`。
 *
 * k/v 取 `innerText`（与 XML_GET 的文本口径一致）。根节点缺失、或一个 k/v 对都没有时
 * 抛 CodeEE —— 硬约束是「不允许静默返回空结果」，空表会让后续 `SELECT v ... WHERE k = ...`
 * 全部悄悄查空。
 */
private fun parseMapXml(text: String, path: String): List<Pair<String, String>> {
    val doc = try {
        XmlParser.parse(text)
    } catch (e: XmlParseException) {
        throw CodeEE("函数 SQL_IMPORT_MAP_XML：XML 解析失败（$path）：${e.message}").apply { initCause(e) }
    }
    val root = doc.root
        ?: throw CodeEE("函数 SQL_IMPORT_MAP_XML：$path 没有根元素")
    val pairs = ArrayList<Pair<String, String>>()
    for (node in root.children) {
        if (node !is XmlElement) continue
        val k = node.children.filterIsInstance<XmlElement>().firstOrNull { it.name == "k" }?.innerText
        val v = node.children.filterIsInstance<XmlElement>().firstOrNull { it.name == "v" }?.innerText
        if (k != null && v != null) pairs.add(k to v)
    }
    if (pairs.isEmpty()) {
        throw CodeEE("函数 SQL_IMPORT_MAP_XML：$path 里没有找到 <p><k>..</k><v>..</v></p> 键值对")
    }
    return pairs
}
