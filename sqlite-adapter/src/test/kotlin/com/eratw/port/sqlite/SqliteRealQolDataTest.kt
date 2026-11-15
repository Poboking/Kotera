package com.eratw.port.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlColumnType
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlNativeException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * T2 真实数据验收：用 [BundledSQLiteDriver] + [AndroidXSqliteDriver] 对退役留档
 * 261017 的随包 `plugins/qol_data.db` 做「seed 只读、沙箱写副本」的迁移演练。
 *
 * 真实库用例依赖环境变量 `ERATW_ROOT`（Gradle 测试任务会透传 `ERATW_` 前缀变量）；
 * 变量缺失或文件不存在时用 JUnit assumption 跳过，不伪造数据、不落内存兜底。
 *
 * 另一组用例只建临时内存库，覆盖 ON CONFLICT DO UPDATE、带 WHERE 的部分唯一索引，
 * 以及 SQLite 的 DDL/DML changes 口径（DDL 不得串用上一条 DML 的 changes）。
 */
@Suppress("LongMethod")
class SqliteRealQolDataTest {

    @TempDir
    lateinit var tmp: Path

    private fun memoryDriver(): AndroidXSqliteDriver = AndroidXSqliteDriver(
        BundledSQLiteDriver(),
        Files.createDirectories(tmp.resolve("seed")),
        Files.createDirectories(tmp.resolve("write")),
    )

    @Test
    fun `真实 261017 qol_data 沙箱 copy 迁移验收`() {
        val sharedRoot = System.getenv("ERATW_ROOT")?.takeIf { it.isNotBlank() }
        if (sharedRoot == null) {
            assumeTrue(false, "缺少 ERATW_ROOT，跳过真实 qol_data.db 验收")
            return
        }
        val gameRoot = Path.of(sharedRoot).resolve(GAME_DIR)
        val seed = gameRoot.resolve(QOL_DB)
        assumeTrue(Files.isRegularFile(seed), "缺少 $seed，跳过真实 qol_data.db 验收")

        val seedShaBefore = sha256(seed)
        val writeRoot = Files.createDirectories(tmp.resolve("real-write"))
        val target = writeRoot.resolve(QOL_DB)
        val driver = AndroidXSqliteDriver(BundledSQLiteDriver(), gameRoot, writeRoot)

        driver.open("Data Source=$QOL_DB").use { db ->
            assertTrue(target.startsWith(writeRoot), "target 必须落在临时写根内：$target")
            assertTrue(Files.isRegularFile(target), "seed 未复制到写根：$target")
            assertEquals(seedShaBefore, sha256(target), "首次复制必须与 seed 逐字节一致")

            val sqliteVersion = db.queryText("SELECT sqlite_version()")
            val metaRows = db.queryLong("SELECT COUNT(*) FROM _meta")
            val graphRows = db.queryLong("SELECT COUNT(*) FROM GRAPH_DISTANCE")
            val dishRows = db.queryLong("SELECT COUNT(*) FROM QOL_DISH_DB")
            println(
                "[real-qol] sqlite=$sqliteVersion meta=$metaRows graph=$graphRows dish=$dishRows",
            )

            assertEquals(8L, metaRows, "_meta 必须仍是随包 8 行")
            assertEquals(
                "20261017",
                db.queryText("SELECT value FROM _meta WHERE key = 'item_schema_version'"),
            )
            assertEquals(
                "20261017",
                db.queryText("SELECT value FROM _meta WHERE key = 'dish_schema_version'"),
            )
            assertEquals(
                "20261017",
                db.queryText("SELECT value FROM _meta WHERE key = 'graph_schema_version'"),
            )
            assertEquals(
                "20261017",
                db.queryText("SELECT value FROM _meta WHERE key = 'fish_data_version'"),
            )
            assertEquals(36418L, graphRows, "GRAPH_DISTANCE 必须仍是随包 36418 行")
            assertEquals(343L, dishRows, "QOL_DISH_DB 必须仍是随包 343 行")

            val changesBeforeDdl = db.totalChanges()
            db.prepare("ALTER TABLE QOL_DISH_DB ADD COLUMN owner_no INTEGER").use { statement ->
                assertEquals(0, statement.columnCount)
                assertFalse(statement.step())
            }
            db.prepare("ALTER TABLE QOL_DISH_DB ADD COLUMN slot INTEGER").use { statement ->
                assertEquals(0, statement.columnCount)
                assertFalse(statement.step())
            }
            assertEquals(
                changesBeforeDdl,
                db.totalChanges(),
                "ALTER TABLE 不得改动 total_changes",
            )

            db.prepare("UPDATE QOL_DISH_DB SET owner_no = 5, slot = dish_id").use { statement ->
                assertFalse(statement.step())
            }
            val backfilled = db.lastChanges()
            assertEquals(343L, backfilled, "回填 affected rows 必须是真实 343")
            assertEquals(
                343L,
                db.totalChanges() - changesBeforeDdl,
                "UPDATE 必须让 total_changes 精确增加 343",
            )
            assertEquals(
                343L,
                db.queryLong("SELECT COUNT(*) FROM QOL_DISH_DB WHERE owner_no = 5"),
            )

            val changesBeforeIndex = db.totalChanges()
            db.prepare(
                "CREATE UNIQUE INDEX idx_qol_dish_owner_slot ON QOL_DISH_DB(owner_no, slot) " +
                    "WHERE owner_no IS NOT NULL",
            ).use { statement ->
                assertEquals(0, statement.columnCount)
                assertFalse(statement.step())
            }
            assertEquals(
                changesBeforeIndex,
                db.totalChanges(),
                "CREATE INDEX 不得改动 total_changes",
            )

            db.prepare("SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = 5").use { statement ->
                assertEquals(1, statement.columnCount)
                assertTrue(statement.step(), "MAX(slot) 必须返回一行")
                assertEquals(SqlColumnType.INTEGER, statement.columnType(0))
                assertFalse(statement.isNull(0), "MAX(slot) 不得是 NULL")
                val maxSlot = statement.getLong(0)
                assertEquals(560L, maxSlot, "真实库里 owner_no = 5 的 MAX(slot) 必须是 560")
                assertFalse(statement.step(), "MAX(slot) 只能返回一行")
                println("[real-qol] backfilled=$backfilled maxSlot(owner=5)=$maxSlot")
            }
        }

        assertEquals(seedShaBefore, sha256(seed), "真实语料 seed 必须保持只读（sha256 回读一致）")

        driver.open("Data Source=$QOL_DB").use { reopened ->
            val columns = reopened.columnNames("QOL_DISH_DB")
            assertTrue("owner_no" in columns, "重开后缺 owner_no 列：$columns")
            assertTrue("slot" in columns, "重开后缺 slot 列：$columns")
            assertEquals(
                1L,
                reopened.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'idx_qol_dish_owner_slot'",
                ),
                "重开后部分唯一索引必须仍在",
            )
            assertEquals(
                343L,
                reopened.queryLong("SELECT COUNT(*) FROM QOL_DISH_DB WHERE owner_no = 5"),
                "重开后回填数据必须仍在",
            )
            assertEquals(
                560L,
                reopened.queryLong("SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = 5"),
                "重开后 MAX(slot) 必须仍是 560",
            )
            assertEquals(
                560L,
                reopened.queryLong(
                    "SELECT slot FROM QOL_DISH_DB WHERE dish_id = 560 AND owner_no = 5",
                ),
                "重开后 dish_id = 560 的 slot 必须仍是 560",
            )
            println("[real-qol] reopened maxSlot(owner=5)=560")
        }

        assertEquals(seedShaBefore, sha256(seed), "关闭重开后 seed 仍必须逐字节不变")
    }

    @Test
    fun `纯临时库 ON CONFLICT DO UPDATE 命中部分唯一索引`() {
        memoryDriver().open(":memory:").use { db ->
            db.prepare(
                "CREATE TABLE dish (id INTEGER PRIMARY KEY, owner_no INTEGER, slot INTEGER, name TEXT)",
            ).use { assertFalse(it.step()) }
            db.prepare(
                "CREATE UNIQUE INDEX idx_dish_owner_slot ON dish (owner_no, slot) " +
                    "WHERE owner_no IS NOT NULL",
            ).use { assertFalse(it.step()) }

            // 部分索引不约束 owner_no IS NULL：两条无主菜可以占同一个 slot。
            db.prepare(
                "INSERT INTO dish (id, owner_no, slot, name) VALUES " +
                    "(1, NULL, 1, '无主甲'), (2, NULL, 1, '无主乙')",
            ).use { assertFalse(it.step()) }
            assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM dish"))

            val upsert = "INSERT INTO dish (id, owner_no, slot, name) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(owner_no, slot) WHERE owner_no IS NOT NULL " +
                "DO UPDATE SET name = excluded.name"
            db.prepare(upsert).use { statement ->
                statement.bind(1, SqlBind.Integer(3))
                statement.bind(2, SqlBind.Integer(5))
                statement.bind(3, SqlBind.Integer(1))
                statement.bind(4, SqlBind.Text("初版"))
                assertFalse(statement.step())
            }
            assertEquals(3L, db.queryLong("SELECT COUNT(*) FROM dish"))
            assertEquals("初版", db.queryText("SELECT name FROM dish WHERE id = 3"))

            db.prepare(upsert).use { statement ->
                statement.bind(1, SqlBind.Integer(4))
                statement.bind(2, SqlBind.Integer(5))
                statement.bind(3, SqlBind.Integer(1))
                statement.bind(4, SqlBind.Text("改版"))
                assertFalse(statement.step())
            }
            assertEquals(1L, db.lastChanges(), "UPSERT 命中时必须按 UPDATE 计 1 行")
            assertEquals(3L, db.queryLong("SELECT COUNT(*) FROM dish"))
            assertEquals("改版", db.queryText("SELECT name FROM dish WHERE id = 3"))
            assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM dish WHERE id = 4"))

            // 复合键不同（另一个 owner）可以复用同一个 slot。
            db.prepare(
                "INSERT INTO dish (id, owner_no, slot, name) VALUES (7, 6, 1, '另一主人')",
            ).use { assertFalse(it.step()) }
            assertEquals(4L, db.queryLong("SELECT COUNT(*) FROM dish"))

            val failure = assertFailsWith<SqlNativeException> {
                db.prepare(
                    "INSERT INTO dish (id, owner_no, slot, name) VALUES (9, 5, 1, '重复')",
                ).use { assertFalse(it.step()) }
            }
            assertTrue(
                failure.message?.contains("UNIQUE") == true,
                "期望 UNIQUE 约束错误，实际：${failure.message}",
            )
            assertEquals(4L, db.queryLong("SELECT COUNT(*) FROM dish"))
        }
    }

    @Test
    fun `纯临时库 DDL 不串用 DML changes`() {
        memoryDriver().open(":memory:").use { db ->
            assertEquals(0L, db.totalChanges())
            db.prepare("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)").use {
                assertFalse(it.step())
            }
            assertEquals(0L, db.totalChanges(), "CREATE TABLE 不得改 total_changes")

            db.prepare("INSERT INTO t (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')").use {
                assertFalse(it.step())
            }
            assertEquals(3L, db.totalChanges())
            assertEquals(3L, db.lastChanges())

            db.prepare("UPDATE t SET v = 'x' WHERE id <= 2").use { assertFalse(it.step()) }
            assertEquals(5L, db.totalChanges())
            assertEquals(2L, db.lastChanges())

            db.prepare("UPDATE t SET v = 'y' WHERE id = 99").use { assertFalse(it.step()) }
            assertEquals(5L, db.totalChanges(), "0 行 UPDATE 不得改 total_changes")
            assertEquals(0L, db.lastChanges(), "0 行 UPDATE 的 changes() 必须为 0")

            db.prepare("CREATE INDEX t_v ON t (v)").use { assertFalse(it.step()) }
            assertEquals(5L, db.totalChanges(), "CREATE INDEX 不得改 total_changes")

            db.prepare("ALTER TABLE t ADD COLUMN w INTEGER").use { assertFalse(it.step()) }
            assertEquals(5L, db.totalChanges(), "ALTER TABLE 不得改 total_changes")

            db.prepare("INSERT OR IGNORE INTO t (id, v) VALUES (1, '冲突')").use {
                assertFalse(it.step())
            }
            assertEquals(5L, db.totalChanges(), "INSERT OR IGNORE 被忽略时不得改 total_changes")
            assertEquals(0L, db.lastChanges(), "被忽略的插入 changes() 必须为 0")

            db.prepare("DELETE FROM t WHERE id = 1").use { assertFalse(it.step()) }
            assertEquals(6L, db.totalChanges())
            assertEquals(1L, db.lastChanges())
        }
    }

    private fun SqlConnection.queryLong(sql: String): Long =
        prepare(sql).use { statement ->
            assertTrue(statement.step(), "查询无结果：$sql")
            val value = statement.getLong(0)
            assertFalse(statement.step(), "查询返回多行：$sql")
            value
        }

    private fun SqlConnection.queryText(sql: String): String =
        prepare(sql).use { statement ->
            assertTrue(statement.step(), "查询无结果：$sql")
            val value = statement.getText(0)
            assertFalse(statement.step(), "查询返回多行：$sql")
            value
        }

    private fun SqlConnection.columnNames(table: String): Set<String> =
        prepare("SELECT name FROM pragma_table_info('$table')").use { statement ->
            val names = linkedSetOf<String>()
            while (statement.step()) {
                names += statement.getText(0)
            }
            names
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val GAME_DIR = "eraTW4.981-261017-EAGLOV3"
        const val QOL_DB = "plugins/qol_data.db"
    }
}
