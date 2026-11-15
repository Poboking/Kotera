package com.eratw.port.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlNativeException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * T5 语句池 + 计数探针缓存的**语义保真**测试。
 *
 * [CountingDriver] 只包住真 [BundledSQLiteDriver] 数两个数（delegate prepare 次数、delegate
 * 真关次数），不改任何行为；因此这些用例断言的是「delegate 真的被复用 / 真的被逐出 / 真的被
 * 关闭」，而 SQL 语义一律跑真 SQLite。同时守住既有契约：close 后的守卫文案与 cause、
 * reset/clearBindings 复用、total_changes/changes 的微软口径（DDL 不计、OR IGNORE 计 0、
 * OR REPLACE 按 UPDATE 计 1）。
 */
class SqliteStatementPoolTest {

    @TempDir
    lateinit var tmp: Path

    private val counting = CountingDriver()

    private fun openDb(): SqlConnection {
        Files.createDirectories(tmp.resolve("seed"))
        Files.createDirectories(tmp.resolve("sandbox"))
        return AndroidXSqliteDriver(counting, tmp.resolve("seed"), tmp.resolve("sandbox"))
            .open("Data Source=pool.db")
    }

    @Test
    fun `同 SQL 两条同时打开各拿一个 delegate`() {
        val db = openDb()
        try {
            db.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)").use { assertFalse(it.step()) }
            val base = counting.prepared
            val first = db.prepare("INSERT INTO t (id, v) VALUES (?, ?)")
            val second = db.prepare("INSERT INTO t (id, v) VALUES (?, ?)")
            assertEquals(base + 2, counting.prepared, "两条未归还的同 SQL 语句必须各自 prepare")

            first.bind(1, SqlBind.Integer(1))
            first.bind(2, SqlBind.Text("甲"))
            second.bind(1, SqlBind.Integer(2))
            second.bind(2, SqlBind.Text("乙"))
            assertFalse(second.step())
            assertFalse(first.step())

            assertEquals(0, counting.closedStatements, "归还池不真关 delegate")
            first.close()
            second.close()
            assertEquals(0, counting.closedStatements)
            assertEquals(2L, db.totalChanges())
        } finally {
            db.close()
        }
    }

    @Test
    fun `顺序 prepare close 两次只 prepare 一次`() {
        val db = openDb()
        try {
            db.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY)").use { assertFalse(it.step()) }
            val base = counting.prepared
            db.prepare("INSERT INTO t (id) VALUES (?)").use { statement ->
                statement.bind(1, SqlBind.Integer(1))
                assertFalse(statement.step())
            }
            db.prepare("INSERT INTO t (id) VALUES (?)").use { statement ->
                statement.bind(1, SqlBind.Integer(2))
                assertFalse(statement.step())
            }
            assertEquals(base + 1, counting.prepared, "第二次 prepare 必须命中池")
            assertEquals(0, counting.closedStatements)
            assertEquals(2L, db.totalChanges())
        } finally {
            db.close()
        }
    }

    @Test
    fun `close 后守卫文案与 cause 不变`() {
        val db = openDb()
        try {
            val statement = db.prepare("SELECT 1")
            statement.close()
            statement.close()

            val failure = assertFailsWith<SqlNativeException> { statement.step() }
            assertEquals("SQLite 语句已关闭", failure.message)
            assertNull(failure.cause, "契约文案不得挂 cause")
            assertFailsWith<SqlNativeException> { statement.bind(1, SqlBind.Integer(1)) }
            assertFailsWith<SqlNativeException> { statement.reset() }
            assertFailsWith<SqlNativeException> { statement.clearBindings() }
            assertFailsWith<SqlNativeException> { statement.columnCount }

            // 池里的 delegate 仍可被下一次 prepare 复用，读数正确。
            db.prepare("SELECT 1").use { reused ->
                assertTrue(reused.step())
                assertEquals(1L, reused.getLong(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `池化复用不泄漏上一条语句的绑定`() {
        val db = openDb()
        try {
            db.prepare("CREATE TABLE t (a INTEGER, b TEXT)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO t (a, b) VALUES (?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(1))
                statement.bind(2, SqlBind.Text("残留"))
                assertFalse(statement.step())
            }
            db.prepare("INSERT INTO t (a, b) VALUES (?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(2))
                assertFalse(statement.step())
            }
            db.prepare("SELECT b FROM t WHERE a = 2").use { statement ->
                assertTrue(statement.step())
                assertTrue(statement.isNull(0), "recycle 必须 clearBindings，b 应为 NULL 而非上一条的绑定")
                assertFalse(statement.step())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `计数探针只 prepare 一次且口径不变`() {
        val db = openDb()
        try {
            db.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)").use { assertFalse(it.step()) }
            val base = counting.prepared

            assertEquals(0L, db.totalChanges())
            assertEquals(base + 1, counting.prepared, "total_changes 探针应懒 prepare 一次")
            repeat(50) { db.totalChanges() }
            assertEquals(base + 1, counting.prepared, "重复读数不得再 prepare")

            assertEquals(0L, db.lastChanges())
            assertEquals(base + 2, counting.prepared, "last_changes 探针应单独懒 prepare 一次")
            repeat(50) { db.lastChanges() }
            assertEquals(base + 2, counting.prepared)

            db.prepare("INSERT INTO t (id, v) VALUES (?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(1))
                statement.bind(2, SqlBind.Text("甲"))
                assertFalse(statement.step())
            }
            assertEquals(1L, db.totalChanges(), "DML 增长")
            assertEquals(1L, db.lastChanges())

            db.prepare("CREATE INDEX t_v ON t (v)").use { assertFalse(it.step()) }
            assertEquals(1L, db.totalChanges(), "DDL 不改 total_changes")
            assertEquals(1L, db.lastChanges(), "DDL 不重置 changes，仍保留上一条 DML 的读数")

            db.prepare("INSERT OR IGNORE INTO t (id, v) VALUES (?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(1))
                statement.bind(2, SqlBind.Text("乙"))
                assertFalse(statement.step())
            }
            assertEquals(1L, db.totalChanges(), "OR IGNORE 忽略行不计")
            assertEquals(0L, db.lastChanges())

            db.prepare("INSERT OR REPLACE INTO t (id, v) VALUES (?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(1))
                statement.bind(2, SqlBind.Text("丙"))
                assertFalse(statement.step())
            }
            assertEquals(2L, db.totalChanges(), "OR REPLACE 命中按 UPDATE 计 1")
            assertEquals(1L, db.lastChanges())

            assertFailsWith<SqlNativeException> {
                db.prepare("INSERT INTO t (id, v) VALUES (?, ?)").use { statement ->
                    statement.bind(1, SqlBind.Integer(1))
                    statement.bind(2, SqlBind.Text("丁"))
                    statement.step()
                }
            }
            assertEquals(2L, db.totalChanges(), "失败语句不得污染计数探针")
            assertTrue(db.lastChanges() >= 0L, "失败语句后探针仍须可读")
        } finally {
            db.close()
        }
    }

    @Test
    fun `计数探针读完释放游标不阻塞 VACUUM`() {
        val db = openDb()
        try {
            db.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO t (id) VALUES (1)").use { assertFalse(it.step()) }
            db.prepare("DELETE FROM t").use { assertFalse(it.step()) }
            assertEquals(2L, db.totalChanges())
            assertEquals(1L, db.lastChanges())

            // 常驻探针若停在 in-progress，VACUUM 会报 cannot VACUUM - SQL statements in progress
            // （eraTW 的 GRAPH_DB_INIT 就是事务外紧跟 VACUUM，见 qol_graph_init.ERB:89）。
            db.prepare("VACUUM").use { assertFalse(it.step()) }
            assertEquals(2L, db.totalChanges(), "VACUUM 后探针仍可读")
        } finally {
            db.close()
        }
    }

    @Test
    fun `池上限 64 条逐出最久未用`() {
        val db = openDb()
        try {
            val base = counting.prepared
            repeat(70) { index ->
                db.prepare("SELECT $index").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(index.toLong(), statement.getLong(0))
                }
            }
            assertEquals(base + 70, counting.prepared, "70 条不同 SQL 各 prepare 一次")
            assertEquals(6, counting.closedStatements, "超出上限的最久未用 6 条 delegate 必须真关")

            // 逐出后同文本仍可重新 prepare，读数不变。
            db.prepare("SELECT 0").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `连接 close 清空池并真关所有 delegate`() {
        val db = openDb()
        db.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY)").use { assertFalse(it.step()) }
        db.prepare("INSERT INTO t (id) VALUES (1)").use { assertFalse(it.step()) }
        db.totalChanges()
        db.lastChanges()

        val preparedBefore = counting.prepared
        assertEquals(0, counting.closedStatements)
        db.close()
        assertEquals(preparedBefore, counting.closedStatements, "连接关闭必须真关池内语句与计数探针")

        val failure = assertFailsWith<SqlNativeException> { db.prepare("SELECT 1") }
        assertEquals("SQLite 连接已关闭", failure.message)
        db.close()
    }

    @Test
    fun `池内同文本语句在 schema 变更后自动重编`() {
        val db = openDb()
        try {
            db.prepare("CREATE TABLE s (a INTEGER)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO s VALUES (1)").use { assertFalse(it.step()) }
            db.prepare("SELECT * FROM s").use { statement ->
                assertTrue(statement.step())
                assertEquals(1, statement.columnCount)
                assertEquals(1L, statement.getLong(0))
            }

            db.prepare("ALTER TABLE s ADD COLUMN b TEXT").use { assertFalse(it.step()) }
            val base = counting.prepared
            db.prepare("SELECT * FROM s").use { statement ->
                assertEquals(base, counting.prepared, "同文本必须命中池（不重新 prepare）")
                assertTrue(statement.step())
                assertEquals(2, statement.columnCount, "prepare-v2 必须让池内语句看到新列")
                assertTrue(statement.isNull(1))
            }
        } finally {
            db.close()
        }
    }
}

/**
 * 只计数、零行为改动的 [SQLiteDriver] 包装：delegate 全走真驱动。
 *
 * 计数点选在「delegate 被要求 prepare」与「delegate 真的 close」两处，正好能区分
 * 「池命中（不 prepare）」与「真复用（不复关）」。
 */
private class CountingDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver {

    /** delegate 被要求 prepare 的累计次数。 */
    var prepared = 0
        private set

    /** delegate 真被 close 的语句累计次数。 */
    var closedStatements = 0
        private set

    override fun open(fileName: String): SQLiteConnection = CountingConnection(delegate.open(fileName))

    private inner class CountingConnection(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            prepared++
            return CountingStatement(connection.prepare(sql))
        }
    }

    private inner class CountingStatement(
        private val statement: SQLiteStatement,
    ) : SQLiteStatement by statement {
        override fun close() {
            closedStatements++
            statement.close()
        }
    }
}
