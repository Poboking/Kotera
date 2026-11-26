package com.eratw.port.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlNativeException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * P2 随包 seed 刷新策略的定向钉：用 [BundledSQLiteDriver] 真跑 SQLite，不 mock 驱动/文件系统。
 *
 * 策略契约：
 * - target 不存在：复制 seed；
 * - [DEFAULT_MUTABLE_DATA_SOURCES] 命中的库（默认空集，机制经构造参数注入测试）：target 已存在
 *   就永不刷新，seed 更新也不覆盖 —— 供未来真正存玩家私有状态的库使用；
 * - 其余 seed 库（含 `plugins/qol_data.db`，2026-11-17 起退出 mutable：其 12 张表全部由启动
 *   注册 `QOL_DB_INIT` 从语料常量全量重建，无玩家私有数据，取证见 `specs/verify-notes.md`
 *   2026-11-17 章节）：seed 的 lastModified 比 target 新时原子刷新，否则保留 ERB 写入；
 * - 刷新失败（如 target 只读）必须保留旧库，不能出现半截文件。
 */
class SqliteSeedPolicyTest {

    @TempDir
    lateinit var tmp: Path

    private val seedRoot: Path get() = tmp.resolve("seed")

    private val writeRoot: Path get() = tmp.resolve("sandbox")

    private fun newDriver(): AndroidXSqliteDriver {
        Files.createDirectories(seedRoot)
        Files.createDirectories(writeRoot)
        return AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, writeRoot)
    }

    @Test
    fun `首次打开把 seed 复制到稳定 target`() {
        val seed = createSeed(
            "plugins/graph.db",
            "CREATE TABLE node (id INTEGER PRIMARY KEY, name TEXT)",
            "INSERT INTO node (id, name) VALUES (1, 'seed-v1')",
        )
        val target = writeRoot.resolve("plugins/graph.db")
        assertFalse(Files.exists(target))

        newDriver().open("Data Source=plugins/graph.db").use { db ->
            assertEquals("seed-v1", db.text("SELECT name FROM node WHERE id = 1"))
        }

        assertTrue(Files.isRegularFile(target), "target 必须落在写根里的稳定路径")
        assertEquals("seed-v1", readSeed(seed, "SELECT name FROM node WHERE id = 1"))
    }

    @Test
    fun `seed 更新后重开读到新行`() {
        val seed = createSeed(
            "plugins/graph.db",
            "CREATE TABLE node (id INTEGER PRIMARY KEY, name TEXT)",
            "INSERT INTO node (id, name) VALUES (1, 'seed-v1')",
        )
        val driver = newDriver()
        driver.open("Data Source=plugins/graph.db").use { db ->
            assertEquals(1L, db.scalarLong("SELECT COUNT(*) FROM node"))
        }

        executeSeed(seed, "INSERT INTO node (id, name) VALUES (2, 'seed-v2')")
        bumpModified(seed)

        driver.open("Data Source=plugins/graph.db").use { db ->
            assertEquals(2L, db.scalarLong("SELECT COUNT(*) FROM node"))
            assertEquals("seed-v2", db.text("SELECT name FROM node WHERE id = 2"))
        }
    }

    @Test
    fun `qol_data 语料派生库随 seed 更新刷新`() {
        // 2026-11-17 退出 mutable 的行为钉：qol_data.db 12 张表全部由启动注册从语料常量
        // 全量重建（无玩家私有数据），新 APK 带的新 seed 必须能覆盖老设备旧库。
        val seed = createSeed(
            "plugins/qol_data.db",
            "CREATE TABLE qol (id INTEGER PRIMARY KEY, v TEXT)",
            "INSERT INTO qol (id, v) VALUES (1, 'seed-v1')",
        )
        val driver = newDriver()
        driver.open("Data Source=plugins/qol_data.db").use { db ->
            assertEquals(1L, db.scalarLong("SELECT COUNT(*) FROM qol"))
        }

        executeSeed(seed, "INSERT INTO qol (id, v) VALUES (2, 'seed-v2')")
        bumpModified(seed)

        driver.open("Data Source=plugins/qol_data.db").use { db ->
            assertEquals(2L, db.scalarLong("SELECT COUNT(*) FROM qol"), "新 seed 必须覆盖旧库")
            assertEquals("seed-v2", db.text("SELECT v FROM qol WHERE id = 2"))
        }
    }

    @Test
    fun `注入 mutable 集合的库保留写入且不随 seed 更新刷新`() {
        // 机制级钉：mutableDataSources 注入命中后「永不刷新」语义仍在（默认空集只是没有
        // 生产消费者），防止未来加回玩家可写库时机制已被静默删掉。
        val seed = createSeed(
            "plugins/private.db",
            "CREATE TABLE qol (id INTEGER PRIMARY KEY, v TEXT)",
            "INSERT INTO qol (id, v) VALUES (1, 'seed')",
        )
        val driver = AndroidXSqliteDriver(
            BundledSQLiteDriver(),
            seedRoot,
            writeRoot,
            mutableDataSources = setOf("plugins/private.db"),
        )
        driver.open("Data Source=plugins/private.db").use { db ->
            db.execute("INSERT INTO qol (id, v) VALUES (100, 'player')")
        }

        executeSeed(seed, "INSERT INTO qol (id, v) VALUES (2, 'seed-new')")
        bumpModified(seed)

        driver.open("Data Source=plugins/private.db").use { db ->
            assertEquals("player", db.text("SELECT v FROM qol WHERE id = 100"))
            assertEquals(0L, db.scalarLong("SELECT COUNT(*) FROM qol WHERE id = 2"))
            assertEquals(2L, db.scalarLong("SELECT COUNT(*) FROM qol"))
        }
    }

    @Test
    fun `seed 未更新时保留 ERB 写入`() {
        createSeed(
            "plugins/graph.db",
            "CREATE TABLE node (id INTEGER PRIMARY KEY, name TEXT)",
            "INSERT INTO node (id, name) VALUES (1, 'seed-v1')",
        )
        val driver = newDriver()
        driver.open("Data Source=plugins/graph.db").use { db ->
            db.execute("INSERT INTO node (id, name) VALUES (100, 'player')")
        }

        driver.open("Data Source=plugins/graph.db").use { db ->
            assertEquals("player", db.text("SELECT name FROM node WHERE id = 100"))
            assertEquals(2L, db.scalarLong("SELECT COUNT(*) FROM node"))
        }
    }

    @Test
    fun `copy 失败不毁旧库`() {
        val seed = createSeed(
            "plugins/graph.db",
            "CREATE TABLE node (id INTEGER PRIMARY KEY, name TEXT)",
            "INSERT INTO node (id, name) VALUES (1, 'seed-v1')",
        )
        val target = writeRoot.resolve("plugins/graph.db")
        val driver = newDriver()
        driver.open("Data Source=plugins/graph.db").use { db ->
            db.execute("INSERT INTO node (id, name) VALUES (100, 'player')")
        }
        val before = Files.readAllBytes(target)

        executeSeed(seed, "INSERT INTO node (id, name) VALUES (2, 'seed-new')")
        bumpModified(seed)

        val restore = blockReplacement(target)
        try {
            assertFailsWith<SqlNativeException> {
                driver.open("Data Source=plugins/graph.db")
            }
        } finally {
            restore()
        }

        assertContentEquals(before, Files.readAllBytes(target), "刷新失败后旧库必须原样保留")
        BundledSQLiteDriver().open(target.toString()).use { connection ->
            connection.prepare("SELECT name FROM node WHERE id = 100").use { statement ->
                assertTrue(statement.step())
                assertEquals("player", statement.getText(0))
            }
            connection.prepare("SELECT COUNT(*) FROM node WHERE id = 2").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0), "失败刷新不得把新 seed 行带进来")
            }
        }
    }

    private fun createSeed(relative: String, vararg statements: String): Path {
        Files.createDirectories(seedRoot)
        val seed = seedRoot.resolve(relative).normalize()
        Files.createDirectories(seed.parent)
        BundledSQLiteDriver().open(seed.toString()).use { connection ->
            for (sql in statements) {
                connection.execute(sql)
            }
        }
        return seed
    }

    private fun executeSeed(seed: Path, sql: String) {
        BundledSQLiteDriver().open(seed.toString()).use { connection ->
            connection.execute(sql)
        }
    }

    private fun readSeed(seed: Path, sql: String): String =
        BundledSQLiteDriver().open(seed.toString()).use { connection ->
            connection.prepare(sql).use { statement ->
                assertTrue(statement.step())
                statement.getText(0)
            }
        }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { statement -> assertFalse(statement.step()) }
    }

    private fun SqlConnection.execute(sql: String) {
        prepare(sql).use { statement -> assertFalse(statement.step()) }
    }

    private fun SqlConnection.scalarLong(sql: String): Long =
        prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getLong(0)
        }

    private fun SqlConnection.text(sql: String): String =
        prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getText(0)
        }

    /** 让 seed 的 lastModified 严格晚于 target，避免文件系统时间戳粒度导致比较相等。 */
    private fun bumpModified(path: Path) {
        val base = maxOf(System.currentTimeMillis(), Files.getLastModifiedTime(path).toMillis())
        Files.setLastModifiedTime(path, FileTime.fromMillis(base + TimeUnit.SECONDS.toMillis(5)))
    }

    /**
     * 注入「替换 target 必失败」：Windows 用 target 的只读属性挡住 atomic move；
     * 类 Unix 用父目录去写权限挡住临时文件创建。返回恢复动作。
     */
    private fun blockReplacement(target: Path): () -> Unit {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            Files.setAttribute(target, "dos:readonly", true)
            return { Files.setAttribute(target, "dos:readonly", false) }
        }
        val parent = requireNotNull(target.parent)
        val original = Files.getPosixFilePermissions(parent)
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        return { Files.setPosixFilePermissions(parent, original) }
    }
}
