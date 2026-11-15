package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SqlSession] 端口的定向钉：全部用自写的 fake `SqlDriver` / `SqlConnection` / `SqlStatement`
 * 驱动，不依赖 Android、AndroidX、真实 SQLite 文件或旧自研引擎。
 *
 * 覆盖面 = `CONTRACT-SQLITE-MIGRATION` §2 的硬语义：连接名大小写不敏感且同名幂等、缺省内存库、
 * reader 句柄单调递增与无效句柄口径、executeNonQuery 的 SELECT / DML / DDL 三档返回、
 * executeScalar 五类列映射、close 顺序与幂等。
 */
class SqlSessionPortTest {

    @Test
    fun `连接名大小写不敏感且同名不重开不改绑`() {
        val driver = FakeDriver()
        val session = SqlSession(driver)

        session.connect("QOL_DISH_DB", "Data Source=plugins/qol_data.db")
        session.connect("qol_dish_db", "Data Source=plugins/other.db")
        session.connect("Qol_Dish_Db", null)

        assertEquals(listOf("Data Source=plugins/qol_data.db"), driver.opened)
        assertSame(driver.connections.single(), session.find("QOL_DISH_DB"))
        assertSame(session.find("QOL_DISH_DB"), session.find("qol_dish_db"))
        session.close()
    }

    @Test
    fun `连接串 null 走内存库且非空串原样透传驱动`() {
        val driver = FakeDriver()
        val session = SqlSession(driver)

        session.connect("MEM", null)
        session.connect("FILE", "Data Source=plugins/qol_data.db")

        assertEquals(listOf(":memory:", "Data Source=plugins/qol_data.db"), driver.opened)
        session.close()
    }

    @Test
    fun `未连接时 require 抛 CodeEE 而 find 返回 null`() {
        val session = SqlSession(FakeDriver())

        assertNull(session.find("ABSENT"))
        val failure = assertFailsWith<CodeEE> {
            session.require("ABSENT", "SQL_EXECUTE_NONQUERY")
        }
        assertEquals(
            "函数 SQL_EXECUTE_NONQUERY：数据库连接 \"ABSENT\" 不存在（先执行 SQL_CONNECT \"ABSENT\"）",
            failure.message,
        )
    }

    @Test
    fun `连接打开失败不登记且可重试`() {
        val connection = FakeConnection()
        var attempts = 0
        val driver = FakeDriver().apply {
            onOpen = {
                attempts += 1
                if (attempts == 1) throw SqlNativeException("open failed") else connection
            }
        }
        val session = SqlSession(driver)

        assertFailsWith<SqlNativeException> { session.connect("DB", "Data Source=x.db") }
        assertNull(session.find("db"), "打开失败的连接不得登记")
        session.connect("Db", "Data Source=x.db")
        assertSame(connection, session.find("db"))
        assertEquals(2, attempts)
        session.close()
    }

    @Test
    fun `executeNonQuery 对结果集只推进一步并返回 -1`() {
        val statement = FakeStatement(columnCount = 1)
        val connection = FakeConnection().apply { onPrepare = { statement } }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_NONQUERY")

        assertEquals(-1L, session.executeNonQuery(db, "SELECT 1"))
        assertEquals(1, statement.stepCalls)
        assertEquals(1, statement.closeCalls)
        assertEquals(0, connection.totalChangesCalls, "结果集分支不读 totalChanges")
        session.close()
    }

    @Test
    fun `executeNonQuery 用 totalChanges 前后差取受影响行数`() {
        val connection = FakeConnection().apply {
            totalChangesValue = 5
            lastChangesValue = 3
        }
        val statement = FakeStatement(columnCount = 0).apply {
            onStep = {
                connection.totalChangesValue += 3
                connection.lastChangesValue = 3
            }
        }
        connection.onPrepare = { statement }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_NONQUERY")

        assertEquals(3L, session.executeNonQuery(db, "UPDATE t SET x = 1"))
        assertEquals(2, connection.totalChangesCalls)
        assertEquals(1, connection.lastChangesCalls)
        assertEquals(1, statement.stepCalls)
        assertEquals(1, statement.closeCalls)
        session.close()
    }

    @Test
    fun `executeNonQuery 的 DDL 不串上一次 DML 的 lastChanges`() {
        val connection = FakeConnection().apply {
            totalChangesValue = 9
            lastChangesValue = 7
        }
        val statement = FakeStatement(columnCount = 0)
        connection.onPrepare = { statement }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_NONQUERY")

        assertEquals(0L, session.executeNonQuery(db, "CREATE TABLE t (x INTEGER)"))
        assertEquals(2, connection.totalChangesCalls)
        assertEquals(0, connection.lastChangesCalls, "读数未增长时不得读 lastChanges")
        session.close()
    }

    @Test
    fun `executeNonQuery 在 totalChanges 未增长时返回 0`() {
        val connection = FakeConnection().apply {
            totalChangesValue = 4
            lastChangesValue = 6
        }
        val statement = FakeStatement(columnCount = 0)
        connection.onPrepare = { statement }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_NONQUERY")

        assertEquals(0L, session.executeNonQuery(db, "UPDATE t SET x = 1 WHERE 0"))
        assertEquals(0, connection.lastChangesCalls)
        session.close()
    }

    @Test
    fun `executeScalar 按首列类型映射且无行返回 null`() {
        val cases = listOf(
            FakeCell.IntCell(42L) to SqlValue.VInt(42L),
            FakeCell.TextCell("hello") to SqlValue.VText("hello"),
            FakeCell.NullCell to SqlValue.VNull,
            FakeCell.FloatCell(1.5) to SqlValue.VFloat(1.5),
            FakeCell.BlobCell("blob".toByteArray()) to SqlValue.VText("blob"),
        )
        for ((cell, expected) in cases) {
            val statement = FakeStatement(columnCount = 1, rows = listOf(listOf(cell)))
            val connection = FakeConnection().apply { onPrepare = { statement } }
            val session = connectedSession(connection)
            val db = session.require("DB", "SQL_EXECUTE_SCALAR_STRING")

            assertEquals(expected, session.executeScalar(db, "SELECT v"), "cell=$cell")
            assertEquals(1, statement.stepCalls)
            session.close()
        }

        val empty = FakeStatement(columnCount = 1)
        val connection = FakeConnection().apply { onPrepare = { empty } }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_SCALAR_STRING")
        assertNull(session.executeScalar(db, "SELECT v"))
        session.close()
    }

    @Test
    fun `reader 句柄从 1 递增且关闭后不复用`() {
        val connection = FakeConnection().apply {
            onPrepare = { FakeStatement(columnCount = 1, rows = listOf(listOf(FakeCell.IntCell(1L)))) }
        }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_READER")

        val first = session.openReader(db, "SELECT 1")
        val second = session.openReader(db, "SELECT 1")
        assertEquals(1, first)
        assertEquals(2, second)
        session.closeReader(first.toLong())
        assertEquals(1, connection.statements[0].closeCalls)

        val third = session.openReader(db, "SELECT 1")
        assertEquals(3, third, "关闭的句柄不得复用")
        session.close()
    }

    @Test
    fun `无效 reader 句柄的口径`() {
        val connection = FakeConnection().apply {
            onPrepare = { FakeStatement(columnCount = 1, rows = listOf(listOf(FakeCell.IntCell(1L)))) }
        }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_EXECUTE_READER")

        assertEquals(false, session.readerRead(404L))
        val invalid = assertFailsWith<CodeEE> {
            session.readerValue(404L, 0, "SQL_READER_GET_LONG")
        }
        assertEquals("函数 SQL_READER_GET_LONG：无效的 Reader ID：404", invalid.message)
        session.closeReader(404L)

        val handle = session.openReader(db, "SELECT 1")
        session.closeReader(handle.toLong())
        assertEquals(false, session.readerRead(handle.toLong()), "已关闭句柄按无效处理")
        assertFailsWith<CodeEE> {
            session.readerValue(handle.toLong(), 0, "SQL_READER_ISNULL")
        }
        session.close()
    }

    @Test
    fun `readerRead 逐行推进且 readerValue 读到当前行`() {
        val statement = FakeStatement(
            columnCount = 2,
            rows = listOf(
                listOf(FakeCell.IntCell(1L), FakeCell.TextCell("a")),
                listOf(FakeCell.IntCell(2L), FakeCell.TextCell("b")),
            ),
        )
        val connection = FakeConnection().apply { onPrepare = { statement } }
        val session = connectedSession(connection)
        val handle = session
            .openReader(session.require("DB", "SQL_EXECUTE_READER"), "SELECT")
            .toLong()

        assertTrue(session.readerRead(handle))
        assertEquals(SqlValue.VInt(1L), session.readerValue(handle, 0, "SQL_READER_GET_LONG"))
        assertEquals(SqlValue.VText("a"), session.readerValue(handle, 1, "SQL_READER_GET_STRING"))
        assertTrue(session.readerRead(handle))
        assertEquals(SqlValue.VInt(2L), session.readerValue(handle, 0, "SQL_READER_GET_LONG"))
        assertEquals(false, session.readerRead(handle))
        session.close()
    }

    @Test
    fun `readerValue 在尚未 READ 到行时抛 CodeEE`() {
        val statement = FakeStatement(columnCount = 1, rows = listOf(listOf(FakeCell.IntCell(1L))))
        val connection = FakeConnection().apply { onPrepare = { statement } }
        val session = connectedSession(connection)
        val handle = session
            .openReader(session.require("DB", "SQL_EXECUTE_READER"), "SELECT")
            .toLong()

        val failure = assertFailsWith<CodeEE> {
            session.readerValue(handle, 0, "SQL_READER_GET_LONG")
        }
        assertTrue(failure.message.orEmpty().contains("尚未 READ"))
        session.close()
    }

    @Test
    fun `close 先关 readers 再关 connections 且幂等`() {
        val order = mutableListOf<String>()
        val statement = FakeStatement(columnCount = 1, rows = listOf(listOf(FakeCell.IntCell(1L))))
            .apply { onClose = { order += "reader" } }
        val connection = FakeConnection().apply {
            onPrepare = { statement }
            onClose = { order += "connection" }
        }
        val session = connectedSession(connection)
        session.openReader(session.require("DB", "SQL_EXECUTE_READER"), "SELECT")

        session.close()
        assertEquals(listOf("reader", "connection"), order)
        assertEquals(1, statement.closeCalls)
        assertEquals(1, connection.closeCalls)

        session.close()
        assertEquals(listOf("reader", "connection"), order, "close 必须幂等")
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun `close 遇首个异常仍关完其余并抛出首个失败`() {
        val order = mutableListOf<String>()
        val statement = FakeStatement(columnCount = 1, rows = listOf(listOf(FakeCell.IntCell(1L))))
            .apply {
                onClose = {
                    order += "reader"
                    error("reader close failed")
                }
            }
        val connection = FakeConnection().apply {
            onPrepare = { statement }
            onClose = {
                order += "connection"
                throw IllegalArgumentException("connection close failed")
            }
        }
        val session = connectedSession(connection)
        session.openReader(session.require("DB", "SQL_EXECUTE_READER"), "SELECT")

        val failure = assertFailsWith<IllegalStateException> { session.close() }
        assertEquals("reader close failed", failure.message)
        assertEquals(listOf("reader", "connection"), order, "首个失败后仍须关连接")
        assertEquals(1, connection.closeCalls)

        session.close()
        assertEquals(1, connection.closeCalls, "抛过异常的 close 也不得二次关闭")
    }

    @Test
    fun `executeNonQuery 绑定参数按 1 基下标顺序传入`() {
        val statement = FakeStatement(columnCount = 1)
        val connection = FakeConnection().apply { onPrepare = { statement } }
        val session = connectedSession(connection)
        val db = session.require("DB", "SQL_P_EXECUTE_NONQUERY")

        session.executeNonQuery(
            db,
            "SELECT ?, ?, ?",
            listOf(SqlBind.Text("a"), SqlBind.Integer(2L), SqlBind.Null),
        )
        assertEquals(
            mapOf(1 to SqlBind.Text("a"), 2 to SqlBind.Integer(2L), 3 to SqlBind.Null),
            statement.bound,
        )
        session.close()
    }

    @Test
    fun `辅助槽名保持 sql`() {
        assertEquals("sql", SQL_AUX_SLOT)
    }

    private fun connectedSession(connection: FakeConnection): SqlSession {
        val session = SqlSession(FakeDriver().apply { onOpen = { connection } })
        session.connect("DB", null)
        return session
    }
}

private class FakeDriver : SqlDriver {
    val opened = mutableListOf<String>()
    val connections = mutableListOf<FakeConnection>()
    var onOpen: (String) -> FakeConnection = { FakeConnection() }

    override fun open(connectionString: String): SqlConnection {
        opened += connectionString
        return onOpen(connectionString).also { connections += it }
    }
}

private class FakeConnection : SqlConnection {
    var totalChangesValue = 0L
    var lastChangesValue = 0L
    var totalChangesCalls = 0
    var lastChangesCalls = 0
    var closeCalls = 0
    var onPrepare: ((String) -> FakeStatement)? = null
    var onClose: (() -> Unit)? = null
    val statements = mutableListOf<FakeStatement>()

    override fun prepare(sql: String): SqlStatement {
        val statement = onPrepare?.invoke(sql) ?: FakeStatement(columnCount = 0)
        statements += statement
        return statement
    }

    override fun totalChanges(): Long {
        totalChangesCalls += 1
        return totalChangesValue
    }

    override fun lastChanges(): Long {
        lastChangesCalls += 1
        return lastChangesValue
    }

    override fun close() {
        closeCalls += 1
        onClose?.invoke()
    }
}

private class FakeStatement(
    override val columnCount: Int,
    private val rows: List<List<FakeCell>> = emptyList(),
) : SqlStatement {
    val bound = LinkedHashMap<Int, SqlBind>()
    var stepCalls = 0
    var closeCalls = 0
    var onStep: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var position = -1
    private var currentRow: List<FakeCell>? = null

    override fun bind(index: Int, value: SqlBind) {
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

    override fun columnType(index: Int): SqlColumnType = cell(index).type()

    override fun isNull(index: Int): Boolean = cell(index) is FakeCell.NullCell

    override fun getLong(index: Int): Long = (cell(index) as FakeCell.IntCell).v

    override fun getDouble(index: Int): Double = when (val value = cell(index)) {
        is FakeCell.FloatCell -> value.v
        is FakeCell.IntCell -> value.v.toDouble()
        else -> error("不是数值单元格：$value")
    }

    override fun getText(index: Int): String = when (val value = cell(index)) {
        is FakeCell.TextCell -> value.v
        is FakeCell.BlobCell -> String(value.v, Charsets.UTF_8)
        is FakeCell.IntCell -> value.v.toString()
        is FakeCell.FloatCell -> value.v.toString()
        FakeCell.NullCell -> error("NULL 单元格没有文本")
    }

    override fun getBlob(index: Int): ByteArray = when (val value = cell(index)) {
        is FakeCell.BlobCell -> value.v
        is FakeCell.TextCell -> value.v.toByteArray()
        else -> error("不是二进制单元格：$value")
    }

    override fun close() {
        closeCalls += 1
        onClose?.invoke()
    }

    private fun cell(index: Int): FakeCell =
        currentRow?.getOrNull(index) ?: error("没有当前行或列下标越界：$index")
}

private sealed interface FakeCell {
    data class IntCell(val v: Long) : FakeCell
    data class TextCell(val v: String) : FakeCell
    data class FloatCell(val v: Double) : FakeCell
    data class BlobCell(val v: ByteArray) : FakeCell
    data object NullCell : FakeCell
}

private fun FakeCell.type(): SqlColumnType = when (this) {
    is FakeCell.IntCell -> SqlColumnType.INTEGER
    is FakeCell.TextCell -> SqlColumnType.TEXT
    is FakeCell.FloatCell -> SqlColumnType.FLOAT
    is FakeCell.BlobCell -> SqlColumnType.BLOB
    FakeCell.NullCell -> SqlColumnType.NULL
}
