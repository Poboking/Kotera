package com.eratw.port.core.sql

import com.eratw.port.core.diag.FileReadTrail
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SQL 内建函数族的**端口层**定向测试（M1 / SQLite 迁移 CORE-METHODS）。
 *
 * 装置全部用自写的 fake `SqlDriver` / `SqlConnection` / `SqlStatement`：不依赖 Android、
 * AndroidX、真实 SQLite 文件，也不依赖任何自研 SQL 解析器/B-tree reader（旧引擎已删除）。
 * 断言分两层：
 * - **函数面语义**：返回值、空结果回落、reader 生命周期、未连接/缺文件/XML 失败口径；
 * - **端口调用形态**：SQL 原文、1 基参数绑定、建表/清表/逐对灌入的真实 SQL 文本。
 *
 * 旧解析器专属断言（`ALTER RENAME` / `JOIN` / `PRAGMA` 不支持、`SELECT` 列表算术拒绝、
 * 预置 SQLite 文件装载等）已随根因层迁移删除；这些行为现在由真实 SQLite 驱动兑现，
 * 由 adapter 的集成测试与语料审计闸覆盖，不再在函数层钉解析器子集。
 */
class SqlMethodsTest {

    // ---- 连接口径 ----

    @Test
    fun `SQL_CONNECT 把连接串原样交给驱动且同名幂等不重开`() {
        val env = MethodsEnv()

        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("TR_DB")))
        assertEquals(listOf(":memory:"), env.driver.opened)

        // 同名（大小写不敏感）第二次带不同 Data Source：不重开、不改绑（S0 契约）
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("tr_db"), q("Data Source=plugins/other.db")))
        assertEquals(listOf(":memory:"), env.driver.opened)

        // 新连接名：连接串原串透传，不再由函数层读文件/还原预置库
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("FILE"), q("Data Source=plugins/qol_data.db")))
        assertEquals(
            listOf(":memory:", "Data Source=plugins/qol_data.db"),
            env.driver.opened,
        )
        assertEquals(2, env.driver.connections.size)
    }

    @Test
    fun `宿主未接线驱动时 SQL 函数抛 CodeEE 且不建内存库`() {
        val env = MethodsEnv(driverPresent = false)

        val connect = assertFailsWith<CodeEE> { intOf(env.ctx, "SQL_CONNECT", q("DB")) }
        assertTrue(connect.message!!.contains("宿主未接线 SQLite 驱动"), connect.message!!)
        assertTrue(
            env.store.aux.slotNames().none { it == SQL_AUX_SLOT },
            "未接线驱动不得创建 SQL 会话槽：${env.store.aux.slotNames()}",
        )

        assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_EXECUTE_NONQUERY", q("DB"), q("SELECT 1"))
        }
        assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("DB"), q("CSV_MAP"), q("plugins/x.xml"))
        }
    }

    @Test
    fun `连接打开失败包成 CodeEE 且保留函数名与驱动文案`() {
        val env = MethodsEnv()
        env.driver.openFailure = SqlNativeException("disk I/O error")

        val e = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_CONNECT", q("DB"), q("Data Source=plugins/broken.db"))
        }
        assertTrue(e.message!!.contains("SQL_CONNECT"), e.message!!)
        assertTrue(e.message!!.contains("disk I/O error"), e.message!!)
        assertEquals(1, env.driver.opened.size)
        assertEquals(0, env.driver.connections.size, "打开失败的连接不得登记")
    }

    // ---- 非查询 ----

    @Test
    fun `参数化非查询透传 SQL 与文本参数并返回受影响行数`() {
        val env = MethodsEnv()
        val conn = connected(env)
        conn.totalChangesValue = 10
        val statement = RecordingStatement(0).apply {
            onStep = {
                conn.totalChangesValue += 2
                conn.lastChangesValue = 2
            }
        }
        conn.onPrepare = { statement }

        val sql = "INSERT OR REPLACE INTO T (id, name, tone) VALUES (@0, @1, @2)"
        val affected = intOf(
            env.ctx, "SQL_P_EXECUTE_NONQUERY", q("DB"), q(sql),
            n(7), q("苹果"), Value.FloatV(1.5),
        )

        assertEquals(2L, affected)
        assertEquals(sql, conn.preparedSql.single())
        assertEquals(
            mapOf<Int, SqlBind>(
                1 to SqlBind.Text("7"),
                2 to SqlBind.Text("苹果"),
                3 to SqlBind.Text("1.5"),
            ),
            statement.bound,
        )
    }

    @Test
    fun `非查询的 DDL 返回零而不串上一次 DML rows 且 SELECT 返回负一`() {
        val env = MethodsEnv()
        val conn = connected(env)
        conn.totalChangesValue = 4
        conn.lastChangesValue = 6
        conn.onPrepare = { RecordingStatement(0) }
        assertEquals(
            0L,
            intOf(env.ctx, "SQL_EXECUTE_NONQUERY", q("DB"), q("CREATE TABLE T (id INTEGER)")),
        )

        val select = RecordingStatement(1, singleRow(CellValue.IntCell(1L)))
        conn.onPrepare = { select }
        assertEquals(
            -1L,
            intOf(env.ctx, "SQL_EXECUTE_NONQUERY", q("DB"), q("SELECT 1")),
            "columnCount > 0 时按 Microsoft.Data.Sqlite 的 ExecuteNonQuery 口径返回 -1",
        )
        assertEquals(1, select.stepCalls)
    }

    @Test
    fun `nullable 实参绑成 SqlBind Null`() {
        val env = MethodsEnv()
        val conn = connected(env)
        val statement = RecordingStatement(0)
        conn.onPrepare = { statement }

        val result = env.ctx.callFunction(
            "SQL_P_EXECUTE_NONQUERY",
            listOf(q("DB"), q("INSERT INTO T (k) VALUES (@0)"), null),
        )

        assertEquals(0L, (result as Value.IntV).v)
        assertEquals(mapOf<Int, SqlBind>(1 to SqlBind.Null), statement.bound)
    }

    // ---- 单值查询 ----

    @Test
    fun `SCALAR 族按首列类型映射且空结果回落`() {
        val env = MethodsEnv()
        val conn = connected(env)
        conn.onPrepare = { sql ->
            when (sql) {
                "SELECT text" -> RecordingStatement(1, singleRow(CellValue.TextCell("梨")))
                "SELECT num" -> RecordingStatement(1, singleRow(CellValue.IntCell(7L)))
                "SELECT numeric-text" -> RecordingStatement(1, singleRow(CellValue.TextCell("12")))
                "SELECT bad-text" -> RecordingStatement(1, singleRow(CellValue.TextCell("abc")))
                "SELECT null" -> RecordingStatement(1, singleRow(CellValue.NullCell))
                else -> RecordingStatement(1)
            }
        }

        assertEquals("梨", strOf(env.ctx, "SQL_EXECUTE_SCALAR_STRING", q("DB"), q("SELECT text")))
        assertEquals(7L, intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q("DB"), q("SELECT num")))
        // 文本数值列按 toLongOrNull 折算（fork Convert.ToInt64 的语料可达形态）
        assertEquals(12L, intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q("DB"), q("SELECT numeric-text")))
        // 非数值文本折 0、NULL 折 0/""、无行折 0/""（既有玩家面口径）
        assertEquals(0L, intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q("DB"), q("SELECT bad-text")))
        assertEquals(0L, intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q("DB"), q("SELECT null")))
        assertEquals("", strOf(env.ctx, "SQL_EXECUTE_SCALAR_STRING", q("DB"), q("SELECT null")))
        assertEquals(0L, intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q("DB"), q("SELECT no-rows")))
        assertEquals("", strOf(env.ctx, "SQL_EXECUTE_SCALAR_STRING", q("DB"), q("SELECT no-rows")))
    }

    @Test
    fun `参数化 SCALAR 绑文本参数`() {
        val env = MethodsEnv()
        val conn = connected(env)
        val statement = RecordingStatement(1, singleRow(CellValue.TextCell("苹果")))
        conn.onPrepare = { statement }

        assertEquals(
            "苹果",
            strOf(
                env.ctx, "SQL_P_EXECUTE_SCALAR_STRING", q("DB"),
                q("SELECT name FROM T WHERE id = @0"), n(5),
            ),
        )
        assertEquals(mapOf<Int, SqlBind>(1 to SqlBind.Text("5")), statement.bound)
    }

    // ---- reader ----

    @Test
    fun `reader 句柄推进取值与重复关闭`() {
        val env = MethodsEnv()
        val conn = connected(env)
        val statement = RecordingStatement(
            2,
            listOf(
                listOf(CellValue.IntCell(1L), CellValue.TextCell("甲")),
                listOf(CellValue.IntCell(2L), CellValue.NullCell),
            ),
        )
        conn.onPrepare = { statement }

        val handle = intOf(
            env.ctx, "SQL_EXECUTE_READER", q("DB"), q("SELECT id, name FROM R ORDER BY id"),
        )
        assertEquals(1L, handle)

        assertEquals(1L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        assertEquals(1L, intOf(env.ctx, "SQL_READER_GET_LONG", n(handle), n(0)))
        assertEquals("甲", strOf(env.ctx, "SQL_READER_GET_STRING", n(handle), n(1)))
        assertEquals(0L, intOf(env.ctx, "SQL_READER_ISNULL", n(handle), n(1)))

        assertEquals(1L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        assertEquals(2L, intOf(env.ctx, "SQL_READER_GET_LONG", n(handle), n(0)))
        assertEquals(1L, intOf(env.ctx, "SQL_READER_ISNULL", n(handle), n(1)))
        assertEquals("", strOf(env.ctx, "SQL_READER_GET_STRING", n(handle), n(1)))
        assertEquals(0L, intOf(env.ctx, "SQL_READER_GET_LONG", n(handle), n(1)))

        assertEquals(0L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        assertEquals(1L, intOf(env.ctx, "SQL_READER_CLOSE", n(handle)))
        assertEquals(1L, intOf(env.ctx, "SQL_READER_CLOSE", n(handle)))
        assertEquals(1, statement.closeCalls, "重复关闭不得二次关闭语句")
        assertEquals(0L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        assertFailsWith<CodeEE> { intOf(env.ctx, "SQL_READER_GET_LONG", n(handle), n(0)) }
    }

    @Test
    fun `reader 未 READ 与文本列取整数的错误口径`() {
        val env = MethodsEnv()
        val conn = connected(env)
        conn.onPrepare = {
            RecordingStatement(1, singleRow(CellValue.TextCell("abc")))
        }
        val handle = intOf(env.ctx, "SQL_EXECUTE_READER", q("DB"), q("SELECT v FROM T"))

        val notRead = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_READER_GET_LONG", n(handle), n(0))
        }
        assertTrue(notRead.message!!.contains("尚未 READ"), notRead.message!!)

        assertEquals(1L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        val notNumber = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_READER_GET_LONG", n(handle), n(0))
        }
        assertTrue(notNumber.message!!.contains("不是数值"), notNumber.message!!)

        val invalid = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_READER_GET_STRING", n(999), n(0))
        }
        assertTrue(invalid.message!!.contains("无效的 Reader ID：999"), invalid.message!!)
    }

    // ---- MAP XML 导入 ----

    @Test
    fun `SQL_IMPORT_MAP_XML 建表清表逐对参数化灌入`() {
        val env = MethodsEnv(
            files = mutableMapOf(
                "plugins/tw_csv_chs.xml" to
                    "<map><p><k>A</k><v>甲</v></p><p><k>B</k><v>乙</v></p></map>",
            ),
        )
        val conn = connected(env, "TR_DB")
        conn.onPrepare = { RecordingStatement(0) }

        assertEquals(
            1L,
            intOf(
                env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"),
                q("plugins/tw_csv_chs.xml"),
            ),
        )

        assertEquals(
            listOf(
                "BEGIN TRANSACTION",
                "CREATE TABLE IF NOT EXISTS \"CSV_MAP\" (k TEXT PRIMARY KEY, v TEXT)",
                "DELETE FROM \"CSV_MAP\"",
                "INSERT OR REPLACE INTO \"CSV_MAP\" (k, v) VALUES (@0, @1)",
                "INSERT OR REPLACE INTO \"CSV_MAP\" (k, v) VALUES (@0, @1)",
                "COMMIT",
            ),
            conn.preparedSql,
        )
        assertEquals(
            mapOf<Int, SqlBind>(1 to SqlBind.Text("A"), 2 to SqlBind.Text("甲")),
            conn.statements[3].bound,
        )
        assertEquals(
            mapOf<Int, SqlBind>(1 to SqlBind.Text("B"), 2 to SqlBind.Text("乙")),
            conn.statements[4].bound,
        )
        assertTrue(
            FileReadTrail.trailLines(env.store).isEmpty(),
            "成功读不得留痕（只记失败）",
        )
    }

    @Test
    fun `SQL_IMPORT_MAP_XML 中途失败回滚整批并保留首个错误`() {
        val env = MethodsEnv(
            files = mutableMapOf(
                "plugins/tw_csv_chs.xml" to
                    "<map><p><k>A</k><v>甲</v></p><p><k>B</k><v>乙</v></p></map>",
            ),
        )
        val conn = connected(env, "TR_DB")
        conn.onPrepare = { sql ->
            if (sql.startsWith("INSERT")) throw SqlNativeException("disk I/O error")
            RecordingStatement(0)
        }

        val failure = assertFailsWith<CodeEE> {
            intOf(
                env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"),
                q("plugins/tw_csv_chs.xml"),
            )
        }
        assertTrue(failure.message!!.contains("disk I/O error"), failure.message!!)
        assertEquals("BEGIN TRANSACTION", conn.preparedSql.first(), "整批必须先开显式事务")
        assertEquals("ROLLBACK", conn.preparedSql.last(), "中途失败必须回滚整批，不能留下半张表")
    }

    @Test
    fun `SQL_IMPORT_MAP_XML 未连接返回零且不碰驱动`() {
        val env = MethodsEnv(
            files = mutableMapOf("plugins/tw_csv_chs.xml" to "<map><p><k>A</k><v>甲</v></p></map>"),
        )

        assertEquals(
            0L,
            intOf(
                env.ctx, "SQL_IMPORT_MAP_XML", q("ABSENT"), q("CSV_MAP"),
                q("plugins/tw_csv_chs.xml"),
            ),
        )
        assertTrue(env.driver.opened.isEmpty())
        assertTrue(env.driver.connections.isEmpty())
    }

    @Test
    fun `SQL_IMPORT_MAP_XML 缺文件保留原文案并留失败读痕`() {
        val env = MethodsEnv()
        connected(env, "TR_DB")

        val e = assertFailsWith<CodeEE> {
            intOf(
                env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"),
                q("plugins/missing.xml"),
            )
        }
        assertEquals("SQL_IMPORT_MAP_XML：找不到文件 plugins/missing.xml", e.message)

        val lines = FileReadTrail.trailLines(env.store)
        assertEquals(2, lines.size, "说明一行 + 读数一行：$lines")
        assertTrue(lines[1].contains("SQL_IMPORT_MAP_XML"), lines[1])
        assertTrue(lines[1].contains(FileReadTrail.NO_ROOT_MARK), lines[1])
        assertTrue(lines[1].contains("请求=plugins/missing.xml"), lines[1])
    }

    @Test
    fun `SQL_IMPORT_MAP_XML 的 XML 与 SQL 失败都包 CodeEE`() {
        val env = MethodsEnv(files = mutableMapOf("bad.xml" to "<map><p>"))
        val conn = connected(env, "TR_DB")

        val xmlError = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("bad.xml"))
        }
        assertTrue(xmlError.message!!.contains("XML 解析失败"), xmlError.message!!)

        env.files["good.xml"] = "<map><p><k>A</k><v>甲</v></p></map>"
        // 只让建表语句失败：BEGIN 必须先成功，才能验到「失败语句原文」与后续回滚。
        conn.onPrepare = { sql ->
            if (sql.startsWith("CREATE TABLE")) throw SqlNativeException("no such table: CSV_MAP")
            RecordingStatement(0)
        }
        val sqlError = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_MAP_XML", q("TR_DB"), q("CSV_MAP"), q("good.xml"))
        }
        val message = sqlError.message!!
        assertTrue(message.startsWith("SQL 执行失败：SQL_IMPORT_MAP_XML："), message)
        assertTrue(message.contains("no such table: CSV_MAP"), message)
        assertTrue(
            message.contains("（语句：CREATE TABLE IF NOT EXISTS \"CSV_MAP\" (k TEXT PRIMARY KEY, v TEXT)）"),
            message,
        )
        assertEquals("ROLLBACK", conn.preparedSql.last(), "建表失败也必须回滚显式事务")
    }

    // ---- 错误包装 ----

    @Test
    fun `SQL 执行失败包 CodeEE 且带函数名与语句原文`() {
        val env = MethodsEnv()
        val conn = connected(env)

        conn.prepareFailure = SqlNativeException("no such table: T")
        val sql = "UPDATE T SET x = 1"
        val prepareError = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_EXECUTE_NONQUERY", q("DB"), q(sql))
        }
        assertEquals(
            "SQL 执行失败：SQL_EXECUTE_NONQUERY：no such table: T（语句：$sql）",
            prepareError.message,
        )

        conn.prepareFailure = null
        conn.onPrepare = {
            RecordingStatement(0).apply {
                onStep = { throw SqlNativeException("database is locked") }
            }
        }
        val stepError = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_P_EXECUTE_NONQUERY", q("DB"), q("DELETE FROM T"), n(1))
        }
        assertTrue(stepError.message!!.contains("database is locked"), stepError.message!!)
        assertTrue(stepError.message!!.contains("（语句：DELETE FROM T）"), stepError.message!!)

        conn.onPrepare = {
            RecordingStatement(1, singleRow(CellValue.IntCell(1L))).apply {
                bindFailure = SqlNativeException("bind failed")
            }
        }
        val bindError = assertFailsWith<CodeEE> {
            intOf(
                env.ctx, "SQL_P_EXECUTE_READER", q("DB"),
                q("SELECT id FROM T WHERE id = @0"), n(1),
            )
        }
        assertTrue(bindError.message!!.contains("bind failed"), bindError.message!!)
        assertTrue(bindError.message!!.contains("SELECT id FROM T WHERE id = @0"), bindError.message!!)
    }

    // ---- 转义（玩家面语义不变） ----

    @Test
    fun `SQL_ESCAPE 成对转义且不依赖驱动`() {
        val env = MethodsEnv(driverPresent = false)
        assertEquals(
            "他说：''你好, 世界''",
            strOf(env.ctx, "SQL_ESCAPE", q("他说：'你好, 世界'")),
        )
    }
}

// ---------------------------------------------------------------------------
// 装置（harness）
// ---------------------------------------------------------------------------

private class MethodsBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("MethodsBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

private class MethodsFiles(private val files: MutableMap<String, String>) : FileOps {
    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class MethodsHost(
    override val fileOps: FileOps? = null,
    override val sqlDriver: SqlDriver? = null,
) : BuiltinHost

private class MethodsEnv(
    val driver: RecordingDriver = RecordingDriver(),
    val files: MutableMap<String, String> = mutableMapOf(),
    driverPresent: Boolean = true,
) {
    private val base = MethodsBaseCtx()
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(
            base,
            store,
            MethodsHost(MethodsFiles(files), if (driverPresent) driver else null),
        )
    }
}

private fun intOf(c: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (c.callFunction(name, args.toList()) as Value.IntV).v

private fun strOf(c: BuiltinEvalContext, name: String, vararg args: Value): String =
    (c.callFunction(name, args.toList()) as Value.StrV).v

private fun q(s: String): Value = Value.StrV(s)

private fun n(v: Long): Value = Value.IntV(v)

private fun connected(env: MethodsEnv, name: String = "DB"): RecordingConnection {
    assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q(name)))
    return env.driver.connections.last()
}

private fun singleRow(vararg cells: CellValue): List<List<CellValue>> = listOf(cells.toList())

// ---------------------------------------------------------------------------
// fake 驱动：只记账 / 按配置回放，不实现 SQL 语义（SQL 语义归真实 adapter）
// ---------------------------------------------------------------------------

private class RecordingDriver : SqlDriver {
    val opened = mutableListOf<String>()
    val connections = mutableListOf<RecordingConnection>()
    var openFailure: SqlNativeException? = null
    var onOpen: (String) -> RecordingConnection = { RecordingConnection() }

    override fun open(connectionString: String): SqlConnection {
        opened += connectionString
        openFailure?.let { throw it }
        return onOpen(connectionString).also { connections += it }
    }
}

private class RecordingConnection : SqlConnection {
    var totalChangesValue = 0L
    var lastChangesValue = 0L
    var onPrepare: (String) -> RecordingStatement = { RecordingStatement(0) }
    var prepareFailure: SqlNativeException? = null
    val preparedSql = mutableListOf<String>()
    val statements = mutableListOf<RecordingStatement>()
    var closeCalls = 0

    override fun prepare(sql: String): SqlStatement {
        preparedSql += sql
        prepareFailure?.let { throw it }
        return onPrepare(sql).also { statements += it }
    }

    override fun totalChanges(): Long = totalChangesValue
    override fun lastChanges(): Long = lastChangesValue
    override fun close() {
        closeCalls += 1
    }
}

private class RecordingStatement(
    override val columnCount: Int,
    private val rows: List<List<CellValue>> = emptyList(),
) : SqlStatement {
    val bound = LinkedHashMap<Int, SqlBind>()
    var stepCalls = 0
    var closeCalls = 0
    var onStep: (() -> Unit)? = null
    var bindFailure: SqlNativeException? = null

    private var position = -1
    private var currentRow: List<CellValue>? = null

    override fun bind(index: Int, value: SqlBind) {
        bindFailure?.let { throw it }
        bound[index] = value
    }

    override fun step(): Boolean {
        stepCalls += 1
        onStep?.invoke()
        position += 1
        currentRow = rows.getOrNull(position)
        return currentRow != null
    }

    override fun reset() {
        position = -1
        currentRow = null
    }

    override fun clearBindings() {
        bound.clear()
    }

    override fun columnName(index: Int): String = "c$index"

    override fun columnType(index: Int): SqlColumnType = when (cell(index)) {
        is CellValue.IntCell -> SqlColumnType.INTEGER
        is CellValue.TextCell -> SqlColumnType.TEXT
        is CellValue.FloatCell -> SqlColumnType.FLOAT
        CellValue.NullCell -> SqlColumnType.NULL
    }

    override fun isNull(index: Int): Boolean = cell(index) === CellValue.NullCell

    override fun getLong(index: Int): Long = when (val value = cell(index)) {
        is CellValue.IntCell -> value.v
        is CellValue.FloatCell -> value.v.toLong()
        else -> error("不是整数单元格：$value")
    }

    override fun getDouble(index: Int): Double = when (val value = cell(index)) {
        is CellValue.FloatCell -> value.v
        is CellValue.IntCell -> value.v.toDouble()
        else -> error("不是数值单元格：$value")
    }

    override fun getText(index: Int): String = when (val value = cell(index)) {
        is CellValue.TextCell -> value.v
        is CellValue.IntCell -> value.v.toString()
        is CellValue.FloatCell -> value.v.toString()
        CellValue.NullCell -> error("NULL 单元格没有文本")
    }

    override fun getBlob(index: Int): ByteArray = when (val value = cell(index)) {
        is CellValue.TextCell -> value.v.toByteArray()
        else -> error("不是二进制单元格：$value")
    }

    override fun close() {
        closeCalls += 1
    }

    private fun cell(index: Int): CellValue =
        currentRow?.getOrNull(index) ?: error("没有当前行或列下标越界：$index")
}

private sealed interface CellValue {
    data class IntCell(val v: Long) : CellValue
    data class TextCell(val v: String) : CellValue
    data class FloatCell(val v: Double) : CellValue
    object NullCell : CellValue
}
