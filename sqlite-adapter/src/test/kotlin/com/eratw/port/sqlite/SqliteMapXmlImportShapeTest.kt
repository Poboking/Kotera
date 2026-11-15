package com.eratw.port.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * P5：`SQL_IMPORT_MAP_XML` 的 11683 行导入形状（`tw_csv_chs.xml` 7721 + `tw_taste_chs.xml`
 * 3962，`qol_db.ERB:61-62` 每次 `QOL_DB_INIT` 都灌一遍）。
 *
 * 语料 `SQL_CONNECT "TR_DB"` 无连接串 ⇒ `:memory:`（上游 `Creator.Method.cs:10291` 的
 * 缺省 `Data Source=:memory:`），所以本测试也在内存库上量；两种形状只差一个显式
 * `BEGIN TRANSACTION` / `COMMIT`，逐行 prepare 与 `totalChanges` 边界两档完全同形。
 *
 * 上游 `SqlManager.ImportMapXml`（`SqlManager.cs:299-332`）把整批包在一个事务里；迁移
 * 前的实现逐行自动提交。文件库上这条差异是每行一次 fsync 的量级（P3 实测 9.11 ms/行），
 * 内存库上则体现为每行一次隐式提交的 journal 收口。读数打印并落
 * `build/perf-probe/map-xml-import-shape.txt`。
 */
@Suppress("MagicNumber")
class SqliteMapXmlImportShapeTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `11683 行 MAP XML 导入 逐行自动提交与单事务`() {
        val autocommit = importShape(inTransaction = false)
        val transaction = importShape(inTransaction = true)

        val lines = listOf(
            "rows=$IMPORT_ROWS",
            "autocommit_ms=${num(autocommit)}",
            "autocommit_rows_per_second=${num(rowsPerSecond(autocommit))}",
            "single_transaction_ms=${num(transaction)}",
            "single_transaction_rows_per_second=${num(rowsPerSecond(transaction))}",
            "speedup=${num(autocommit / transaction)}x",
        )
        report(lines)

        assertTrue(transaction < IMPORT_BOUND_MS, "单事务导入超过数量级兜底：${num(transaction)} ms")
        assertTrue(autocommit < IMPORT_BOUND_MS, "自动提交导入超过数量级兜底：${num(autocommit)} ms")
    }

    /**
     * `TR_DB` 生产形态 = `:memory:`。逐行 `prepare` + 参数绑定 + `totalChanges` 边界，
     * 与 `SqlMethods.importKeyValueRows` 的每行调用同形。
     */
    private fun importShape(inTransaction: Boolean): Double {
        val driver = AndroidXSqliteDriver(
            BundledSQLiteDriver(),
            Files.createDirectories(tmp.resolve("seed")),
            Files.createDirectories(tmp.resolve("write")),
        )
        driver.open(":memory:").use { db ->
            executeNonQuery(db, CREATE)
            val start = System.nanoTime()
            if (inTransaction) executeNonQuery(db, "BEGIN TRANSACTION")
            try {
                executeNonQuery(db, DELETE)
                repeat(IMPORT_ROWS) { i ->
                    executeNonQuery(
                        db,
                        INSERT,
                        listOf(SqlBind.Text("k$i"), SqlBind.Text("值$i")),
                    )
                }
                if (inTransaction) executeNonQuery(db, "COMMIT")
            } catch (e: Exception) {
                if (inTransaction) runCatching { executeNonQuery(db, "ROLLBACK") }
                throw e
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            val count = scalarLong(db, "SELECT COUNT(*) FROM \"CSV_MAP\"")
            assertEquals(IMPORT_ROWS.toLong(), count, "导入行数必须完整")
            return elapsed
        }
    }

    private fun executeNonQuery(db: SqlConnection, sql: String, params: List<SqlBind> = emptyList()) {
        db.prepare(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.bind(index + 1, value) }
            assertFalse(statement.columnCount > 0, "本用例只跑非查询语句：$sql")
            val before = db.totalChanges()
            var hasNext = statement.step()
            while (hasNext) hasNext = statement.step()
            val after = db.totalChanges()
            if (after > before) db.lastChanges()
        }
    }

    private fun scalarLong(db: SqlConnection, sql: String): Long =
        db.prepare(sql).use { statement ->
            assertTrue(statement.step(), "查询无结果：$sql")
            statement.getLong(0)
        }

    private fun rowsPerSecond(elapsedMs: Double): Double =
        if (elapsedMs <= 0.0) Double.POSITIVE_INFINITY else IMPORT_ROWS * 1000.0 / elapsedMs

    private fun report(lines: List<String>) {
        lines.forEach { println("[$PROBE] $it") }
        val out = Path.of(System.getProperty("user.dir")).resolve("build/perf-probe/map-xml-import-shape.txt")
        runCatching {
            Files.createDirectories(out.parent)
            Files.writeString(
                out,
                lines.joinToString(System.lineSeparator()) + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            println("[$PROBE] report=${out.toAbsolutePath()}")
        }.onFailure {
            println("[$PROBE] 报告落盘失败（读数已在 JUnit system-out）：$it")
        }
    }

    private fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val PROBE = "P5-IMPORT-PROBE"

        /** 261111 `plugins/tw_csv_chs.xml` 7721 对 + `tw_taste_chs.xml` 3962 对。 */
        const val IMPORT_ROWS = 11_683
        const val IMPORT_BOUND_MS = 120_000.0
        const val CREATE = "CREATE TABLE IF NOT EXISTS \"CSV_MAP\" (k TEXT PRIMARY KEY, v TEXT)"
        const val DELETE = "DELETE FROM \"CSV_MAP\""
        const val INSERT = "INSERT OR REPLACE INTO \"CSV_MAP\" (k, v) VALUES (@0, @1)"
    }
}
