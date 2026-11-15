package com.eratw.port.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * P5：`qol_graph_init.ERB` 的 36418 行图距离写入吞吐与事务语义。
 *
 * 语料形状（261111 `qol_graph_init.ERB:68-85`）：`GRAPH_DB_REBUILD_ALL` 在显式
 * `BEGIN TRANSACTION` / `COMMIT` 之间执行 36418 次 `SQL_P_EXECUTE_NONQUERY`，连接名
 * `GRAPH_DB_NAME` = `"QOL_DISH_DB"`（`qol_graph.ERH:21`），落在同一文件库
 * `plugins/qol_data.db`。本测试把同一条链拆成四个可判定的面：
 *
 * 1. 事务外逐条（自动提交）：每行一次隐式提交，量化每次提交的 fsync 成本；
 * 2. 事务内批量：与语料同形，量化一个事务摊薄后的行/秒；
 * 3. 事务可见性：未提交行对其他连接不可见、提交后可见，钉住「没有把多次调用伪装成一个事务」；
 * 4. 异常回滚边界：事务内语句失败不替玩家自动提交，`ROLLBACK` 后数据与提交前一致。
 *
 * 写入调用刻意复刻 `SqlSession.executeNonQuery` 的完整形状（prepare → bind →
 * `columnCount == 0` → 前后 `totalChanges` 边界 → step），所以读数就是引擎真实路径，
 * 不是绕过参数绑定/变更计数的裸驱动微基准。
 *
 * 自动提交组在未开 WAL 的文件库上按行付 fsync，36418 行是分钟级；读数打印到 stdout 并
 * 落 `build/perf-probe/graph-write-*.txt`。测试只做数量级兜底，不锁精确预算。
 */
@Suppress("LongMethod", "MagicNumber", "TooManyFunctions")
class SqliteGraphDistanceWriteTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `事务外逐条写入 36418 行`() {
        val db = openFileDb("autocommit.db")
        try {
            createGraphDistance(db)
            val elapsed = writeGraphDistance(db, inTransaction = false)
            assertEquals(GRAPH_ROWS.toLong(), countRows(db), "自动提交写入行数必须完整")
            report(
                "graph-write-autocommit",
                listOf(
                    "rows=$GRAPH_ROWS",
                    "in_transaction=false",
                    "elapsed_ms=${num(elapsed)}",
                    "ms_per_row=${num(elapsed / GRAPH_ROWS)}",
                    "rows_per_second=${num(rowsPerSecond(elapsed))}",
                    "journal_mode=${pragma(db, "journal_mode")}",
                    "synchronous=${pragma(db, "synchronous")}",
                ),
            )
            assertTrue(elapsed < AUTOCOMMIT_BOUND_MS, "自动提交 36418 行超过数量级兜底：${num(elapsed)} ms")
        } finally {
            db.close()
        }
    }

    @Test
    fun `事务内批量写入 36418 行`() {
        val db = openFileDb("transaction.db")
        try {
            createGraphDistance(db)
            val elapsed = writeGraphDistance(db, inTransaction = true)
            assertEquals(GRAPH_ROWS.toLong(), countRows(db), "单事务写入行数必须完整")
            report(
                "graph-write-transaction",
                listOf(
                    "rows=$GRAPH_ROWS",
                    "in_transaction=true",
                    "elapsed_ms=${num(elapsed)}",
                    "ms_per_row=${num(elapsed / GRAPH_ROWS)}",
                    "rows_per_second=${num(rowsPerSecond(elapsed))}",
                    "journal_mode=${pragma(db, "journal_mode")}",
                    "synchronous=${pragma(db, "synchronous")}",
                ),
            )
            assertTrue(elapsed < TRANSACTION_BOUND_MS, "单事务 36418 行超过数量级兜底：${num(elapsed)} ms")
        } finally {
            db.close()
        }
    }

    @Test
    fun `事务未提交行对其他连接不可见提交后可见`() {
        val writer = openFileDb("visibility.db")
        val observer = openFileDb("visibility.db")
        try {
            createGraphDistance(writer)
            executeNonQuery(writer, "BEGIN TRANSACTION")
            insertRow(writer, mapId = 0, source = 1, target = 2)
            assertEquals(1L, countRows(writer), "同一连接在事务内必须读到自己写入的行")
            assertEquals(
                0L,
                countRows(observer),
                "未提交行不得被另一个连接看到——否则就是把多次调用伪装成了一个事务",
            )
            executeNonQuery(writer, "COMMIT")
            assertEquals(1L, countRows(observer), "COMMIT 后另一个连接必须看到该行")
        } finally {
            observer.close()
            writer.close()
        }
    }

    @Test
    fun `事务内异常回滚后不留下半批数据`() {
        val db = openFileDb("rollback.db")
        try {
            createGraphDistance(db)
            executeNonQuery(db, "BEGIN TRANSACTION")
            repeat(10) { i -> insertRow(db, mapId = 0, source = i.toLong(), target = 99) }
            assertEquals(10L, countRows(db), "回滚前事务内应可见 10 行")
            val failure = assertFailsWith<Exception> {
                // 同一主键重复 INSERT（非 OR REPLACE）必须抛，且不能顺手提交前面 10 行。
                insertRow(db, mapId = 0, source = 0, target = 99, orReplace = false)
            }
            assertTrue(
                failure.message?.contains("UNIQUE", ignoreCase = true) == true,
                "期望主键冲突，实际：${failure.message}",
            )
            assertEquals(10L, countRows(db), "失败的语句不得提交已写入的行")
            executeNonQuery(db, "ROLLBACK")
            assertEquals(0L, countRows(db), "ROLLBACK 后整批必须回滚干净")
        } finally {
            db.close()
        }
    }

    @Test
    fun `关闭重开后提交的数据仍在`() {
        val first = openFileDb("reopen.db")
        try {
            createGraphDistance(first)
            writeGraphDistance(first, inTransaction = true, rows = REOPEN_ROWS)
            assertEquals(REOPEN_ROWS.toLong(), countRows(first))
        } finally {
            first.close()
        }
        val second = openFileDb("reopen.db")
        try {
            assertEquals(
                REOPEN_ROWS.toLong(),
                countRows(second),
                "关闭重开后单事务写入的数据必须持久化",
            )
            assertEquals(
                REOPEN_ROWS.toLong(),
                scalarLong(second, "SELECT COUNT(*) FROM GRAPH_DISTANCE WHERE distance = $DISTANCE"),
                "关闭重开后列值必须原样保留",
            )
        } finally {
            second.close()
        }
    }

    /**
     * 与 `SqlSession.executeNonQuery` 同形：prepare → bind → `columnCount == 0` 路径 →
     * `totalChanges` 前后差判 DML。返回墙钟毫秒。
     */
    private fun writeGraphDistance(
        db: SqlConnection,
        inTransaction: Boolean,
        rows: Int = GRAPH_ROWS,
    ): Double {
        if (inTransaction) executeNonQuery(db, "BEGIN TRANSACTION")
        val start = System.nanoTime()
        try {
            for (i in 0 until rows) {
                val mapId = (i / 10_000).toLong()
                val source = ((i / 100) % 100).toLong()
                val target = (i % 100).toLong()
                insertRow(db, mapId, source, target)
            }
            if (inTransaction) executeNonQuery(db, "COMMIT")
        } catch (e: Exception) {
            if (inTransaction) runCatching { executeNonQuery(db, "ROLLBACK") }
            throw e
        }
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private fun insertRow(
        db: SqlConnection,
        mapId: Long,
        source: Long,
        target: Long,
        orReplace: Boolean = true,
    ) {
        val verb = if (orReplace) "INSERT OR REPLACE" else "INSERT"
        executeNonQuery(
            db,
            "$verb INTO GRAPH_DISTANCE (map_id, source, target, distance, previous) " +
                "VALUES (@0, @1, @2, @3, @4)",
            listOf(
                SqlBind.Integer(mapId),
                SqlBind.Integer(source),
                SqlBind.Integer(target),
                SqlBind.Integer(DISTANCE),
                SqlBind.Integer(PREVIOUS),
            ),
        )
    }

    /** engine 形态的非查询执行：与 `SqlSession.executeNonQuery` 逐句对应。 */
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

    private fun createGraphDistance(db: SqlConnection) {
        executeNonQuery(
            db,
            "CREATE TABLE IF NOT EXISTS GRAPH_DISTANCE (" +
                "map_id INTEGER NOT NULL, source INTEGER NOT NULL, target INTEGER NOT NULL, " +
                "distance INTEGER NOT NULL, previous INTEGER NOT NULL DEFAULT -1, " +
                "PRIMARY KEY (map_id, source, target))",
        )
    }

    private fun openFileDb(file: String): SqlConnection {
        val seedRoot = Files.createDirectories(tmp.resolve("seed"))
        val writeRoot = Files.createDirectories(tmp.resolve("write"))
        return AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, writeRoot)
            .open("Data Source=$file")
    }

    private fun countRows(db: SqlConnection): Long =
        scalarLong(db, "SELECT COUNT(*) FROM GRAPH_DISTANCE")

    private fun scalarLong(db: SqlConnection, sql: String): Long =
        db.prepare(sql).use { statement ->
            assertTrue(statement.step(), "查询无结果：$sql")
            statement.getLong(0)
        }

    private fun pragma(db: SqlConnection, name: String): String =
        db.prepare("PRAGMA $name").use { statement ->
            assertTrue(statement.step(), "PRAGMA $name 无结果")
            statement.getText(0)
        }

    private fun rowsPerSecond(elapsedMs: Double): Double =
        if (elapsedMs <= 0.0) Double.POSITIVE_INFINITY else GRAPH_ROWS * 1000.0 / elapsedMs

    private fun report(id: String, lines: List<String>) {
        lines.forEach { println("[$PROBE] $it") }
        val out = Path.of(System.getProperty("user.dir")).resolve("build/perf-probe/$id.txt")
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
        const val PROBE = "P5-WRITE-PROBE"
        const val GRAPH_ROWS = 36_418
        const val REOPEN_ROWS = 1_000
        const val DISTANCE = 7L
        const val PREVIOUS = -1L

        /** 数量级兜底：自动提交在不开 WAL 的文件库上按行 fsync，仍应低于分钟级。 */
        const val AUTOCOMMIT_BOUND_MS = 600_000.0
        const val TRANSACTION_BOUND_MS = 60_000.0
    }
}
