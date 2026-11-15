package com.eratw.port.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlColumnType
import com.eratw.port.core.sql.SqlNativeException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A1 的真实驱动测试：用 [BundledSQLiteDriver] 跑真 SQLite，不经任何 fake。
 *
 * 路径相关用例只经 [AndroidXSqliteDriver] 的构造与 `open` 间接依赖 A2 的
 * [SqlitePathLayout] 契约（不另写一套解析），A2 落地后这些用例即为跨 lane 验收。
 */
class AndroidXSqliteDriverTest {

    @TempDir
    lateinit var tmp: Path

    private val writeRoot: Path get() = tmp.resolve("sandbox")

    private fun newDriver(): AndroidXSqliteDriver {
        val seedRoot = Files.createDirectories(tmp.resolve("seed"))
        Files.createDirectories(writeRoot)
        return AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, writeRoot)
    }

    @Test
    fun `CREATE INSERT UPDATE 读回与 affected rows`() {
        val db = newDriver().open(":memory:")
        try {
            val createSql = "CREATE TABLE dish (" +
                "id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB, memo TEXT)"
            db.prepare(createSql).use { statement ->
                assertEquals(0, statement.columnCount)
                assertFalse(statement.step())
            }

            db.prepare("INSERT INTO dish (id, name, score, data, memo) VALUES (?, ?, ?, ?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(7))
                statement.bind(2, SqlBind.Text("炖菜"))
                statement.bind(3, SqlBind.Float(1.5))
                statement.bind(4, SqlBind.Blob(byteArrayOf(1, 2, 3)))
                statement.bind(5, SqlBind.Null)
                assertFalse(statement.step())
            }
            assertEquals(1L, db.totalChanges())
            assertEquals(1L, db.lastChanges())

            db.prepare("UPDATE dish SET score = ? WHERE id = ?").use { statement ->
                statement.bind(1, SqlBind.Float(2.5))
                statement.bind(2, SqlBind.Integer(7))
                assertFalse(statement.step())
            }
            assertEquals(2L, db.totalChanges())
            assertEquals(1L, db.lastChanges())

            db.prepare("CREATE INDEX dish_name ON dish (name)").use { statement ->
                assertEquals(0, statement.columnCount)
                assertFalse(statement.step())
            }
            assertEquals(2L, db.totalChanges())

            db.prepare("SELECT id, name, score, data, memo FROM dish WHERE id = ?").use { statement ->
                statement.bind(1, SqlBind.Integer(7))
                assertTrue(statement.step())
                assertEquals(5, statement.columnCount)
                assertEquals("id", statement.columnName(0))
                assertEquals("memo", statement.columnName(4))
                assertEquals(SqlColumnType.INTEGER, statement.columnType(0))
                assertEquals(SqlColumnType.TEXT, statement.columnType(1))
                assertEquals(SqlColumnType.FLOAT, statement.columnType(2))
                assertEquals(SqlColumnType.BLOB, statement.columnType(3))
                assertEquals(SqlColumnType.NULL, statement.columnType(4))
                assertEquals(7L, statement.getLong(0))
                assertEquals("炖菜", statement.getText(1))
                assertEquals(2.5, statement.getDouble(2), 1e-9)
                assertContentEquals(byteArrayOf(1, 2, 3), statement.getBlob(3))
                assertTrue(statement.isNull(4))
                assertFalse(statement.step())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `reset 与 clearBindings 复用同一条语句`() {
        val db = newDriver().open(":memory:")
        try {
            db.prepare("CREATE TABLE t (id INTEGER)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO t (id) VALUES (?)").use { statement ->
                statement.bind(1, SqlBind.Integer(10))
                assertFalse(statement.step())
                statement.reset()
                statement.bind(1, SqlBind.Integer(20))
                assertFalse(statement.step())
                statement.reset()
                statement.clearBindings()
            }
            db.prepare("SELECT COUNT(*) FROM t").use { statement ->
                assertTrue(statement.step())
                assertEquals(2L, statement.getLong(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `文件库关闭后重开仍持久化`() {
        val driver = newDriver()
        driver.open("Data Source=persist/app.db").use { db ->
            db.prepare("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO kv (k, v) VALUES (?, ?)").use { statement ->
                statement.bind(1, SqlBind.Text("料理"))
                statement.bind(2, SqlBind.Text("炖菜"))
                assertFalse(statement.step())
            }
        }
        assertTrue(Files.isRegularFile(writeRoot.resolve("persist/app.db")))

        driver.open("Data Source=persist/app.db").use { db ->
            db.prepare("SELECT v FROM kv WHERE k = ?").use { statement ->
                statement.bind(1, SqlBind.Text("料理"))
                assertTrue(statement.step())
                assertEquals("炖菜", statement.getText(0))
                assertFalse(statement.step())
            }
        }
    }

    @Test
    fun `seed 库首次打开复制到沙箱且写入不回灌 seed`() {
        val seedRoot = Files.createDirectories(tmp.resolve("seed"))
        val seed = seedRoot.resolve("plugins/qol_data.db")
        Files.createDirectories(seed.parent)
        BundledSQLiteDriver().open(seed.toString()).use { connection ->
            connection.prepare("CREATE TABLE seed_data (id INTEGER PRIMARY KEY, name TEXT)").use {
                assertFalse(it.step())
            }
            connection.prepare("INSERT INTO seed_data (id, name) VALUES (1, 'seed')").use {
                assertFalse(it.step())
            }
        }
        val target = writeRoot.resolve("plugins/qol_data.db")
        assertFalse(Files.exists(target))

        val driver = newDriver()
        driver.open("Data Source=plugins/qol_data.db").use { db ->
            db.prepare("SELECT name FROM seed_data WHERE id = 1").use { statement ->
                assertTrue(statement.step())
                assertEquals("seed", statement.getText(0))
            }
            db.prepare("INSERT INTO seed_data (id, name) VALUES (2, 'sandbox')").use {
                assertFalse(it.step())
            }
        }
        assertTrue(Files.isRegularFile(target))

        BundledSQLiteDriver().open(seed.toString()).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM seed_data").use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
        }
        driver.open("Data Source=plugins/qol_data.db").use { db ->
            db.prepare("SELECT COUNT(*) FROM seed_data").use { statement ->
                assertTrue(statement.step())
                assertEquals(2L, statement.getLong(0))
            }
        }
    }

    @Test
    fun `Data Source 与裸路径都解析进写根目录`() {
        val driver = newDriver()
        driver.open("Data Source=nested/dir/first.db").use { db ->
            db.prepare("CREATE TABLE a (x INTEGER)").use { assertFalse(it.step()) }
        }
        driver.open("nested/dir/second.db").use { db ->
            db.prepare("CREATE TABLE b (x INTEGER)").use { assertFalse(it.step()) }
        }
        assertTrue(Files.isRegularFile(writeRoot.resolve("nested/dir/first.db")))
        assertTrue(Files.isRegularFile(writeRoot.resolve("nested/dir/second.db")))
    }

    @Test
    fun `出根路径被拒绝且不落文件`() {
        val driver = newDriver()
        assertFailsWith<IllegalArgumentException> { driver.open("Data Source=../escape.db") }
        assertFailsWith<IllegalArgumentException> { driver.open(tmp.resolve("outside.db").toString()) }
        assertFalse(Files.exists(tmp.resolve("escape.db")))
        assertFalse(Files.exists(tmp.resolve("outside.db")))
    }

    @Test
    fun `Memory 三种写法都不落文件`() {
        val driver = newDriver()
        for (connectionString in listOf("", ":memory:", "file::memory:")) {
            driver.open(connectionString).use { db ->
                db.prepare("CREATE TABLE mem_only (x INTEGER)").use { assertFalse(it.step()) }
                db.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'mem_only'")
                    .use { statement ->
                        assertTrue(statement.step())
                        assertEquals(1L, statement.getLong(0))
                    }
            }
        }
        assertEquals(0L, Files.list(writeRoot).use { it.count() })
    }

    @Test
    fun `连接与语句重复 close 安全且关闭后拒绝使用`() {
        val db = newDriver().open(":memory:")
        val statement = db.prepare("CREATE TABLE t (x INTEGER)")
        statement.close()
        statement.close()
        db.close()
        db.close()
        assertFailsWith<SqlNativeException> { db.prepare("SELECT 1") }
    }

    @Test
    fun `原生 SQL 错误归一为 SqlNativeException`() {
        val db = newDriver().open(":memory:")
        try {
            assertFailsWith<SqlNativeException> { db.prepare("SELECT * FROM missing_table") }
        } finally {
            db.close()
        }
    }
}
