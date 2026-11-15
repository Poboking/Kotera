package com.eratw.port.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlStatement
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
 * task-2 生产口径探针：只新增测试，不动产品代码。
 *
 * 与 `SqliteGraphDistanceWriteTest` / `SqlitePerformanceProbeTest` 的差别：那两个文件直接拿
 * [AndroidXSqliteDriver] 开库、全程默认 `journal_mode=delete` / `synchronous=FULL`，读数只代表
 * 「裸驱动」。本文件复刻 core-vm `SqlSession` 的逐句语义，因为那才是 `SQL_*` 内置函数真正走的链路：
 * - `PRAGMA journal_mode = WAL` + `PRAGMA synchronous = NORMAL`（SqlSession.kt:86-104 的
 *   `connectionOpen`）；
 * - `executeNonQuery` / `executeScalar`（SqlSession.kt:138-171）：**每次调用**都
 *   `driver.prepare(sql)`→bind→step→`use` 关闭，非查询语句另外查 2 次 `totalChanges()` 与
 *   1 次 `lastChanges()`。
 *
 * `SqlSession` 是 core-vm 的 `internal` 类（SqlSession.kt:51），:sqlite-adapter 的 test 源集引用
 * 不到，故这里按上面几行**逐句复刻**，输出里统一标注 `session_semantics=simulated`。
 *
 * 两个口径都量：
 * - **A 现行生产** = `SqlSession.connect`（SqlSession.kt:75-79）：只 `driver.open`，不执行 PRAGMA。
 *   261111 语料全量递归 grep **零** `SQL_CONNECTION_OPEN`，`SQL_CONNECT` 有十余处（qol_graph_init.ERB:37
 *   等），所以线上真跑的就是这一档：默认 delete / FULL，**每一次隐式提交都 fsync**。
 * - **B WAL 口径** = `connectionOpen`：WAL + NORMAL（WAL 下 NORMAL 不再每次提交 fsync）。
 *
 * 写库全部落在 `@TempDir` 沙箱（[AndroidXSqliteDriver] 的写根），不碰语料/仓库文件；读数同时
 * println 到 JUnit system-out 并落 `build/perf-probe/` 文本。
 */
@Suppress("LongMethod", "TooManyFunctions", "LargeClass")
class SqliteProdPathProbeTest {

    @TempDir
    lateinit var tmp: Path

    private val driver: AndroidXSqliteDriver by lazy {
        AndroidXSqliteDriver(
            BundledSQLiteDriver(),
            Files.createDirectories(tmp.resolve("seed")),
            Files.createDirectories(tmp.resolve("sandbox")),
        )
    }

    @Test
    fun `A 生产口径 connect 无 PRAGMA`() {
        report("prod-path-A-connect-no-pragma", measureMode(ProdMode.CONNECT_NO_PRAGMA))
    }

    @Test
    fun `B 生产口径 connectionOpen WAL NORMAL`() {
        report("prod-path-B-connection-open-wal", measureMode(ProdMode.CONNECTION_OPEN_WAL))
    }

    @Test
    fun `QOL_DISH_DB MAX slot 部分索引与全表扫`() {
        report("dish-max-slot-index", measureDish())
    }

    // ------------------------------------------------------------------ graph 库

    /** 必测项 1/2/3/4 读侧/5 一次跑齐：语料 `GRAPH_DB_REBUILD_ALL` 的真实顺序（BEGIN→36418 行→COMMIT→_meta→VACUUM）。 */
    private fun measureMode(mode: ProdMode): List<String> {
        val lines = ArrayList<String>()
        val db = openDb(mode, "${mode.tag}-graph-prod.db")
        try {
            lines += pragmaLines(mode, db)
            createGraphSchema(db)

            lines += batchLine(
                WriteVariant.PRODUCTION,
                runGraphBatch(db, WriteVariant.PRODUCTION),
            )
            assertEquals(ROWS.toLong(), scalarLong(db, "SELECT COUNT(*) FROM GRAPH_DISTANCE"))

            val controls = listOf(
                WriteVariant.FRESH_PREPARE_NO_COUNT,
                WriteVariant.REUSED_STMT_WITH_COUNT,
                WriteVariant.BARE,
            )
            controls.forEach { variant ->
                val ms = withDb(mode, "${mode.tag}-graph-${variant.tag}.db") { variantDb ->
                    createGraphSchema(variantDb)
                    runGraphBatch(variantDb, variant)
                }
                lines += batchLine(variant, ms)
            }

            lines += autocommitLines(mode)

            // 语料 VACUUM 之前先 DELETE 四表（qol_graph_init.ERB:70-73）：先造等量空闲页，
            // 让 VACUUM 读数包含「回收 36418 行留下的空页」，再按 :87 → :89 的真实顺序测。
            simulateFreedPages(db)
            lines += metaInsertLines(db)
            lines += vacuumLines(mode, db)

            lines += readLines(db)
            lines += prepareLines(db)
        } finally {
            db.close()
        }
        return lines
    }

    private fun pragmaLines(mode: ProdMode, db: SqlConnection): List<String> {
        val journalMode = scalarText(db, "PRAGMA journal_mode")
        val synchronous = scalarLong(db, "PRAGMA synchronous")
        if (mode == ProdMode.CONNECTION_OPEN_WAL) {
            assertEquals("wal", journalMode, "connectionOpen 口径应落到 journal_mode=wal")
            assertEquals(1L, synchronous, "connectionOpen 口径应落到 synchronous=NORMAL")
        } else {
            assertEquals("delete", journalMode, "connect 口径不应改 journal_mode")
            assertEquals(2L, synchronous, "connect 口径应保持 synchronous=FULL")
        }
        val fsyncPerCommit = journalMode != "wal" || synchronous >= 2
        return listOf(
            "mode=${mode.tag} (${mode.description})",
            "journal_mode=$journalMode synchronous=$synchronous fsync_per_commit=$fsyncPerCommit",
            "session_semantics=simulated from core-vm SqlSession (internal, test 源集不可直接引用)",
        )
    }

    /** 单事务写 [ROWS] 行；四个变体只在「是否每行 fresh prepare」「是否每行查变更计数」上不同。 */
    private fun runGraphBatch(db: SqlConnection, variant: WriteVariant): Double {
        val start = System.nanoTime()
        execute(db, "BEGIN")
        try {
            when (variant) {
                WriteVariant.PRODUCTION -> repeat(ROWS) { index ->
                    sessionExecuteNonQuery(db, INSERT_GRAPH_SQL, graphParams(index))
                }
                WriteVariant.FRESH_PREPARE_NO_COUNT -> repeat(ROWS) { index ->
                    db.prepare(INSERT_GRAPH_SQL).use { statement ->
                        bindAll(statement, graphParams(index))
                        assertFalse(statement.step())
                    }
                }
                WriteVariant.REUSED_STMT_WITH_COUNT -> db.prepare(INSERT_GRAPH_SQL).use { statement ->
                    repeat(ROWS) { index ->
                        resetAndBind(statement, graphParams(index))
                        val before = db.totalChanges()
                        assertFalse(statement.step())
                        val after = db.totalChanges()
                        if (after > before) db.lastChanges()
                    }
                }
                WriteVariant.BARE -> db.prepare(INSERT_GRAPH_SQL).use { statement ->
                    repeat(ROWS) { index ->
                        resetAndBind(statement, graphParams(index))
                        assertFalse(statement.step())
                    }
                }
            }
            execute(db, "COMMIT")
        } catch (e: Throwable) {
            runCatching { execute(db, "ROLLBACK") }
            throw e
        }
        return elapsedMs(start)
    }

    private fun batchLine(variant: WriteVariant, ms: Double): String =
        "${variant.tag}_${ROWS}x_single_tx_ms=${num(ms)} " +
            "us_per_row=${num(ms * 1000.0 / ROWS)} rows_per_s=${num(ROWS * 1000.0 / ms)}"

    /** 必测项 2 的对照：2000 条 autocommit（生产口径；任务明确不跑 36418 条 autocommit）。 */
    private fun autocommitLines(mode: ProdMode): List<String> =
        withDb(mode, "${mode.tag}-graph-autocommit.db") { db ->
            createGraphSchema(db)
            val start = System.nanoTime()
            repeat(AUTOCOMMIT_ROWS) { index ->
                sessionExecuteNonQuery(db, INSERT_GRAPH_SQL, graphParams(index))
            }
            val ms = elapsedMs(start)
            assertEquals(
                AUTOCOMMIT_ROWS.toLong(),
                scalarLong(db, "SELECT COUNT(*) FROM GRAPH_DISTANCE"),
            )
            listOf(
                "autocommit_${AUTOCOMMIT_ROWS}x_production_replica_ms=${num(ms)} " +
                    "us_per_row=${num(ms * 1000.0 / AUTOCOMMIT_ROWS)}",
            )
        }

    private fun metaInsertLines(db: SqlConnection): List<String> {
        val samples = ArrayList<Double>()
        repeat(META_SAMPLES) { index ->
            val start = System.nanoTime()
            val changes = sessionExecuteNonQuery(
                db,
                INSERT_META_SQL,
                listOf(SqlBind.Text("graph_schema_version"), SqlBind.Text("${NOW_VERSION + index}")),
            )
            samples += elapsedMs(start)
            assertTrue(changes >= 1L, "_meta 写入应报告变更行：$changes")
        }
        val median = samples.sorted()[samples.size / 2]
        return listOf(
            "meta_insert_outside_tx_${META_SAMPLES}x_ms_each=${samples.joinToString(",") { num(it) }} " +
                "median_ms=${num(median)}",
        )
    }

    private fun vacuumLines(mode: ProdMode, db: SqlConnection): List<String> {
        val file = sandboxFile("${mode.tag}-graph-prod.db")
        sessionExecuteNonQuery(db, "PRAGMA wal_checkpoint(TRUNCATE)", emptyList())
        val beforeBytes = Files.size(file)
        val start = System.nanoTime()
        sessionExecuteNonQuery(db, "VACUUM", emptyList())
        val ms = elapsedMs(start)
        sessionExecuteNonQuery(db, "PRAGMA wal_checkpoint(TRUNCATE)", emptyList())
        val afterBytes = Files.size(file)
        return listOf(
            "vacuum_outside_tx_ms=${num(ms)} db_bytes_before=$beforeBytes " +
                "db_bytes_after=$afterBytes reclaimed_bytes=${beforeBytes - afterBytes}",
        )
    }

    /** 必测项 4 读侧：36418 行全表读、主键点查 fresh/reuse、EQP。 */
    private fun readLines(db: SqlConnection): List<String> {
        val lines = ArrayList<String>()
        lines += fullTableReadLine(db)
        val key = firstGraphKey(db)
        val params = listOf(
            SqlBind.Integer(key.first),
            SqlBind.Integer(key.second),
            SqlBind.Integer(key.third),
        )
        repeat(WARMUP) { sessionExecuteScalar(db, SELECT_GRAPH_PK_SQL, params) }
        val freshMs = timed(ITERATIONS) { sessionExecuteScalar(db, SELECT_GRAPH_PK_SQL, params) }
        lines += "repeat_prepare_${ITERATIONS}x_graph_pk_lookup_ms=${num(freshMs)} " +
            "us_per_op=${num(freshMs * 1000.0 / ITERATIONS)}"
        val reusedMs = reusedLookupMs(db, SELECT_GRAPH_PK_SQL, params)
        lines += "reuse_statement_${ITERATIONS}x_graph_pk_lookup_ms=${num(reusedMs)} " +
            "us_per_op=${num(reusedMs * 1000.0 / ITERATIONS)}"
        lines += "query_plan_graph_pk_lookup=${queryPlan(db, SELECT_GRAPH_PK_SQL).joinToString(" | ")}"
        return lines
    }

    private fun fullTableReadLine(db: SqlConnection): String {
        val start = System.nanoTime()
        var rows = 0L
        var sink = 0L
        db.prepare(SELECT_GRAPH_ALL_SQL).use { statement ->
            while (statement.step()) {
                sink += statement.getLong(0) + statement.getLong(1) + statement.getLong(2) +
                    statement.getLong(3) + statement.getLong(4)
                rows++
            }
        }
        val ms = elapsedMs(start)
        assertEquals(ROWS.toLong(), rows, "全表行读数应为 $ROWS")
        return "full_table_read_${ROWS}_rows_ms=${num(ms)} us_per_row=${num(ms * 1000.0 / rows)} sink=$sink"
    }

    private fun firstGraphKey(db: SqlConnection): Triple<Long, Long, Long> =
        db.prepare("SELECT map_id, source, target FROM GRAPH_DISTANCE LIMIT 1").use { statement ->
            assertTrue(statement.step(), "GRAPH_DISTANCE 为空")
            Triple(statement.getLong(0), statement.getLong(1), statement.getLong(2))
        }

    /** 必测项 5：同一 SQL 文本反复 prepare+finalize 的 μs 均值，用来估「按 SQL 文本缓存语句」的上限收益。 */
    private fun prepareLines(db: SqlConnection): List<String> {
        val lines = ArrayList<String>()
        listOf(
            "graph_insert" to INSERT_GRAPH_SQL,
            "graph_pk_lookup" to SELECT_GRAPH_PK_SQL,
            "total_changes" to SELECT_TOTAL_CHANGES_SQL,
            "changes" to SELECT_CHANGES_SQL,
        ).forEach { (name, sql) ->
            repeat(WARMUP) { db.prepare(sql).close() }
            val start = System.nanoTime()
            repeat(ITERATIONS) { db.prepare(sql).close() }
            val ms = elapsedMs(start)
            lines += "prepare_finalize_only_${ITERATIONS}x_${name}_ms=${num(ms)} " +
                "us_per_prepare=${num(ms * 1000.0 / ITERATIONS)}"
        }
        // executeNonQuery 的非查询分支每行额外发 2 次 totalChanges() + 1 次 lastChanges()（SqlSession.kt:146-155）
        lines += trivialQueryLines(db)
        val countQueryUs = timed(ITERATIONS) { db.totalChanges() } * 1000.0 / ITERATIONS
        lines += "count_query_full_call_us_per_op=${num(countQueryUs)} " +
            "wrapper_3_count_queries_us_per_row=${num(countQueryUs * 3)}"
        return lines
    }

    // ------------------------------------------------------------------ dish 库

    /** 必测项 4 写侧：QOL_DISH_DB 2000 行 / 199 owner，部分唯一索引，MAX(slot) 1000 次 + EQP。 */
    @Suppress("LongMethod")
    private fun measureDish(): List<String> {
        val mode = ProdMode.CONNECTION_OPEN_WAL
        val db = openDb(mode, "B-dish-max-slot.db")
        try {
            val lines = ArrayList<String>()
            lines += pragmaLines(mode, db)
            createDishTable(db)
            insertDishRows(db)
            execute(db, CREATE_DISH_PARTIAL_INDEX_SQL)
            assertEquals(DISH_ROWS.toLong(), scalarLong(db, "SELECT COUNT(*) FROM QOL_DISH_DB"))
            assertEquals(
                DISH_OWNERS.toLong(),
                scalarLong(db, "SELECT COUNT(DISTINCT owner_no) FROM QOL_DISH_DB"),
                "owner 数应为 $DISH_OWNERS",
            )
            lines += "rows=$DISH_ROWS owners=$DISH_OWNERS index=partial_unique(owner_no,slot) WHERE owner_no>0"
            val expectedCycled = cycledMaxSlotSum(db)

            lines += eqpLine("partial_index_owner_eq_param", db, MAX_SLOT_SQL)
            lines += maxSlotFreshLine("partial_index_fresh_prepare", db, MAX_SLOT_SQL, expectedCycled)
            lines += maxSlotReusedLine("partial_index_reused_stmt", db, MAX_SLOT_SQL, expectedCycled)

            lines += eqpLine("partial_index_plus_redundant_pred", db, MAX_SLOT_SQL_GT)
            lines += maxSlotFreshLine("partial_index_plus_redundant_pred", db, MAX_SLOT_SQL_GT, expectedCycled)

            execute(db, "DROP INDEX idx_dish_owner_slot")
            lines += eqpLine("no_index_owner_eq_param", db, MAX_SLOT_SQL)
            lines += maxSlotFreshLine("no_index_full_scan_baseline", db, MAX_SLOT_SQL, expectedCycled)

            execute(db, CREATE_DISH_PLAIN_INDEX_SQL)
            lines += eqpLine("plain_index_owner_eq_param", db, MAX_SLOT_SQL)
            lines += maxSlotFreshLine("plain_index_fresh_prepare", db, MAX_SLOT_SQL, expectedCycled)
            lines += maxSlotReusedLine("plain_index_reused_stmt", db, MAX_SLOT_SQL, expectedCycled)

            lines += dishPkLines(db)
            lines += crossCheckModeA(expectedCycled)
            return lines
        } finally {
            db.close()
        }
    }

    /** 计时循环按 [ownerOf] 轮转 199 个 owner，先算出这一轮转序列的 MAX(slot) 期望和。 */
    private fun cycledMaxSlotSum(db: SqlConnection): Long {
        val maxByOwner = LongArray(DISH_OWNERS + 1)
        for (owner in 1..DISH_OWNERS) {
            maxByOwner[owner] =
                sessionExecuteScalar(db, MAX_SLOT_SQL, listOf(SqlBind.Integer(owner.toLong())))
        }
        var sum = 0L
        repeat(ITERATIONS) { sum += maxByOwner[ownerOf(it).toInt()] }
        return sum
    }

    private fun maxSlotFreshLine(
        label: String,
        db: SqlConnection,
        sql: String,
        expectedSum: Long,
    ): String {
        repeat(WARMUP) { sessionExecuteScalar(db, sql, listOf(SqlBind.Integer(ownerOf(it)))) }
        var sink = 0L
        val start = System.nanoTime()
        repeat(ITERATIONS) { sink += sessionExecuteScalar(db, sql, listOf(SqlBind.Integer(ownerOf(it)))) }
        val ms = elapsedMs(start)
        assertEquals(expectedSum, sink, "$label 的 MAX(slot) 累加值不对")
        return "fresh_prepare_${ITERATIONS}x_${label}_ms=${num(ms)} " +
            "us_per_op=${num(ms * 1000.0 / ITERATIONS)}"
    }

    private fun maxSlotReusedLine(
        label: String,
        db: SqlConnection,
        sql: String,
        expectedSum: Long,
    ): String {
        var sink = 0L
        val ms = db.prepare(sql).use { statement ->
            repeat(WARMUP) {
                resetAndBind(statement, listOf(SqlBind.Integer(ownerOf(it))))
                assertTrue(statement.step())
                statement.getLong(0)
            }
            val start = System.nanoTime()
            repeat(ITERATIONS) {
                resetAndBind(statement, listOf(SqlBind.Integer(ownerOf(it))))
                assertTrue(statement.step())
                sink += statement.getLong(0)
            }
            elapsedMs(start)
        }
        assertEquals(expectedSum, sink, "$label 的 MAX(slot) 累加值不对")
        return "reuse_statement_${ITERATIONS}x_${label}_ms=${num(ms)} " +
            "us_per_op=${num(ms * 1000.0 / ITERATIONS)}"
    }

    private fun ownerOf(iteration: Int): Long = (iteration % DISH_OWNERS + 1).toLong()

    private fun dishPkLines(db: SqlConnection): List<String> {
        val dishId = scalarLong(db, "SELECT MIN(dish_id) FROM QOL_DISH_DB")
        val params = listOf(SqlBind.Integer(dishId))
        repeat(WARMUP) { sessionExecuteScalar(db, DISH_PK_SQL, params) }
        val freshMs = timed(ITERATIONS) { sessionExecuteScalar(db, DISH_PK_SQL, params) }
        val reusedMs = reusedLookupMs(db, DISH_PK_SQL, params)
        return listOf(
            "repeat_prepare_${ITERATIONS}x_dish_pk_lookup_ms=${num(freshMs)} " +
                "us_per_op=${num(freshMs * 1000.0 / ITERATIONS)}",
            "reuse_statement_${ITERATIONS}x_dish_pk_lookup_ms=${num(reusedMs)} " +
                "us_per_op=${num(reusedMs * 1000.0 / ITERATIONS)}",
        )
    }

    /** 读路径对照：A 口径（生产 connect，无 PRAGMA）下同一 MAX(slot) 1000 次，验证 journal_mode 不影响读。 */
    private fun crossCheckModeA(expectedSum: Long): List<String> =
        withDb(ProdMode.CONNECT_NO_PRAGMA, "A-dish-crosscheck.db") { db ->
            val lines = ArrayList<String>()
            lines += pragmaLines(ProdMode.CONNECT_NO_PRAGMA, db).filter { it.startsWith("journal_mode") }
            createDishTable(db)
            insertDishRows(db)
            execute(db, CREATE_DISH_PARTIAL_INDEX_SQL)
            lines += maxSlotFreshLine("mode_A_partial_index_fresh_prepare", db, MAX_SLOT_SQL, expectedSum)
            lines
        }

    private fun createDishTable(db: SqlConnection) = execute(db, CREATE_DISH_SQL)

    private fun insertDishRows(db: SqlConnection) {
        execute(db, "BEGIN")
        try {
            db.prepare(INSERT_DISH_SQL).use { statement ->
                repeat(DISH_ROWS) { index ->
                    resetAndBind(
                        statement,
                        listOf(
                            SqlBind.Integer((index + 1).toLong()),
                            SqlBind.Text("dish-$index"),
                            SqlBind.Integer(ownerOf(index)),
                            SqlBind.Integer((index / DISH_OWNERS).toLong()),
                        ),
                    )
                    assertFalse(statement.step())
                }
            }
            execute(db, "COMMIT")
        } catch (e: Throwable) {
            runCatching { execute(db, "ROLLBACK") }
            throw e
        }
    }

    // ------------------------------------------------------------------ 公共

    private fun openDb(mode: ProdMode, fileName: String): SqlConnection {
        val db = driver.open("Data Source=$fileName")
        if (mode == ProdMode.CONNECTION_OPEN_WAL) {
            sessionExecuteNonQuery(db, "PRAGMA journal_mode = WAL", emptyList())
            sessionExecuteNonQuery(db, "PRAGMA synchronous = NORMAL", emptyList())
        }
        return db
    }

    private fun <T> withDb(mode: ProdMode, fileName: String, block: (SqlConnection) -> T): T {
        val db = openDb(mode, fileName)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun createGraphSchema(db: SqlConnection) {
        execute(db, CREATE_GRAPH_SQL)
        execute(db, CREATE_META_SQL)
    }

    /** 逐句复刻 core-vm SqlSession.executeNonQuery（SqlSession.kt:138-156）。 */
    private fun sessionExecuteNonQuery(
        db: SqlConnection,
        sql: String,
        params: List<SqlBind>,
    ): Long = db.prepare(sql).use { statement ->
        bindAll(statement, params)
        if (statement.columnCount > 0) {
            statement.step()
            -1L
        } else {
            val before = db.totalChanges()
            var more = statement.step()
            while (more) {
                more = statement.step()
            }
            val after = db.totalChanges()
            if (after > before) db.lastChanges() else 0L
        }
    }

    /** 逐句复刻 core-vm SqlSession.executeScalar（SqlSession.kt:164-171）。 */
    private fun sessionExecuteScalar(
        db: SqlConnection,
        sql: String,
        params: List<SqlBind>,
    ): Long = db.prepare(sql).use { statement ->
        bindAll(statement, params)
        assertTrue(statement.step(), "SQL 无结果：$sql")
        statement.getLong(0)
    }

    private fun reusedLookupMs(db: SqlConnection, sql: String, params: List<SqlBind>): Double =
        db.prepare(sql).use { statement ->
            repeat(WARMUP) {
                resetAndBind(statement, params)
                assertTrue(statement.step())
                statement.getLong(0)
            }
            val start = System.nanoTime()
            repeat(ITERATIONS) {
                resetAndBind(statement, params)
                assertTrue(statement.step())
                statement.getLong(0)
            }
            elapsedMs(start)
        }

    private inline fun timed(iterations: Int, block: () -> Unit): Double {
        val start = System.nanoTime()
        repeat(iterations) { block() }
        return elapsedMs(start)
    }

    /**
     * 单条查询的「每次自动提交事务」固定开销：`SELECT 1` 无绑定、无表访问，
     * 两口径的差只来自 journal_mode（delete 下每个读事务要碰 journal/文件锁，WAL 只碰内存 wal-index）。
     */
    private fun trivialQueryLines(db: SqlConnection): List<String> {
        val lines = ArrayList<String>()
        val reuseMs = db.prepare(SELECT_ONE_SQL).use { statement ->
            repeat(WARMUP) {
                statement.reset()
                assertTrue(statement.step())
                statement.getLong(0)
            }
            var sink = 0L
            val start = System.nanoTime()
            repeat(ITERATIONS) {
                statement.reset()
                assertTrue(statement.step())
                sink += statement.getLong(0)
            }
            val ms = elapsedMs(start)
            assertEquals(ITERATIONS.toLong(), sink, "SELECT 1 累加值不对")
            ms
        }
        val freshMs = timed(ITERATIONS) {
            db.prepare(SELECT_ONE_SQL).use { statement ->
                assertTrue(statement.step())
                statement.getLong(0)
            }
        }
        lines += "trivial_select1_reuse_statement_${ITERATIONS}x_us_per_op=" +
            num(reuseMs * 1000.0 / ITERATIONS)
        lines += "trivial_select1_fresh_prepare_${ITERATIONS}x_us_per_op=" +
            num(freshMs * 1000.0 / ITERATIONS)
        return lines
    }

    private fun bindAll(statement: SqlStatement, params: List<SqlBind>) {
        params.forEachIndexed { index, value -> statement.bind(index + 1, value) }
    }

    private fun resetAndBind(statement: SqlStatement, params: List<SqlBind>) {
        statement.reset()
        statement.clearBindings()
        bindAll(statement, params)
    }

    private fun execute(db: SqlConnection, sql: String) {
        db.prepare(sql).use { statement -> statement.step() }
    }

    private fun scalarLong(db: SqlConnection, sql: String): Long =
        db.prepare(sql).use { statement ->
            assertTrue(statement.step(), "SQL 无结果：$sql")
            statement.getLong(0)
        }

    private fun scalarText(db: SqlConnection, sql: String): String =
        db.prepare(sql).use { statement ->
            assertTrue(statement.step(), "SQL 无结果：$sql")
            statement.getText(0)
        }

    /** 只打印计划（SQLite `id,parent,notused,detail` 的 detail 列），不做跨版本断言。 */
    private fun queryPlan(db: SqlConnection, sql: String): List<String> =
        db.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
            val plan = ArrayList<String>()
            while (statement.step()) plan.add(statement.getText(3))
            plan
        }

    private fun eqpLine(label: String, db: SqlConnection, sql: String): String =
        "eqp_${label}=${queryPlan(db, sql).joinToString(" | ")}"

    private fun sandboxFile(fileName: String): Path =
        Files.createDirectories(tmp.resolve("sandbox")).resolve(fileName)

    private fun graphParams(index: Int): List<SqlBind> {
        val mapId = index / GRAPH_KEYS_PER_MAP
        val within = index % GRAPH_KEYS_PER_MAP
        val source = within / GRAPH_TARGETS_PER_SOURCE
        val target = within % GRAPH_TARGETS_PER_SOURCE
        return listOf(
            SqlBind.Integer(mapId.toLong()),
            SqlBind.Integer(source.toLong()),
            SqlBind.Integer(target.toLong()),
            SqlBind.Integer((index * 7 % 97).toLong()),
            SqlBind.Integer(if (target == 0) -1L else (target - 1).toLong()),
        )
    }

    private fun fillerParams(index: Int): List<SqlBind> =
        listOf(
            SqlBind.Integer(FILLER_MAP),
            SqlBind.Integer((index / GRAPH_TARGETS_PER_SOURCE).toLong()),
            SqlBind.Integer((index % GRAPH_TARGETS_PER_SOURCE).toLong()),
            SqlBind.Integer(1L),
            SqlBind.Integer(-1L),
        )

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0

    private fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    /** 读数既进 JUnit XML 的 system-out，也落一份独立文本；落盘失败不影响测试判据。 */
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

    /** 语料 VACUUM 前先 DELETE 四表：造等量空闲页，让 VACUUM 读数含空页回收。 */
    private fun simulateFreedPages(db: SqlConnection) {
        execute(db, "BEGIN")
        try {
            db.prepare(INSERT_GRAPH_SQL).use { statement ->
                repeat(ROWS) { index ->
                    resetAndBind(statement, fillerParams(index))
                    assertFalse(statement.step())
                }
            }
            execute(db, "DELETE FROM GRAPH_DISTANCE WHERE map_id = $FILLER_MAP")
            execute(db, "COMMIT")
        } catch (e: Throwable) {
            runCatching { execute(db, "ROLLBACK") }
            throw e
        }
    }

    /**
     * 两个生产口径。A = SqlSession.connect（无 PRAGMA），B = connectionOpen（WAL + NORMAL）。
     */
    private enum class ProdMode(val tag: String, val description: String) {
        CONNECT_NO_PRAGMA("A-connect-no-pragma", "SqlSession.connect: open only, no PRAGMA"),
        CONNECTION_OPEN_WAL(
            "B-connection-open-wal",
            "SqlSession.connectionOpen: journal_mode=WAL + synchronous=NORMAL",
        ),
    }

    /**
     * 36418 行写入的四个变体，唯一变量是「每行是否 fresh prepare」与「是否每行查变更计数」。
     */
    private enum class WriteVariant(val tag: String) {
        /** 生产复刻：每行一次 fresh prepare + 2×totalChanges + lastChanges。 */
        PRODUCTION("production-replica"),

        /** 只保留 fresh prepare，去掉变更计数。 */
        FRESH_PREPARE_NO_COUNT("fresh-prepare-no-count"),

        /** 复用语句，保留变更计数。 */
        REUSED_STMT_WITH_COUNT("reused-stmt-with-count"),

        /** 复用语句、不查计数 = 裸 SQLite 上限。 */
        BARE("bare-reused-stmt"),
    }

    private companion object {
        const val PROBE = "PROD-PATH-PROBE"
        const val ITERATIONS = 1_000
        const val WARMUP = 100
        const val ROWS = 36_418
        const val AUTOCOMMIT_ROWS = 2_000
        const val DISH_ROWS = 2_000
        const val DISH_OWNERS = 199
        const val META_SAMPLES = 5
        const val NOW_VERSION = 261_111
        const val FILLER_MAP = 999L
        const val GRAPH_KEYS_PER_MAP = 4_000
        const val GRAPH_TARGETS_PER_SOURCE = 20

        const val INSERT_GRAPH_SQL = "INSERT OR REPLACE INTO GRAPH_DISTANCE " +
            "(map_id, source, target, distance, previous) VALUES (@0, @1, @2, @3, @4)"
        const val SELECT_GRAPH_ALL_SQL =
            "SELECT map_id, source, target, distance, previous FROM GRAPH_DISTANCE"
        const val SELECT_GRAPH_PK_SQL = "SELECT distance FROM GRAPH_DISTANCE " +
            "WHERE map_id = @0 AND source = @1 AND target = @2"
        const val INSERT_META_SQL = "INSERT OR REPLACE INTO _meta (key, value) VALUES (@0, @1)"
        const val SELECT_TOTAL_CHANGES_SQL = "SELECT total_changes()"
        const val SELECT_CHANGES_SQL = "SELECT changes()"
        const val SELECT_ONE_SQL = "SELECT 1"
        const val MAX_SLOT_SQL = "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0"
        const val MAX_SLOT_SQL_GT =
            "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0 AND owner_no > 0"
        const val DISH_PK_SQL = "SELECT dish_id FROM QOL_DISH_DB WHERE dish_id = @0"

        /** qol_graph_init.ERB:41 / :47 的 DDL 原文。 */
        const val CREATE_GRAPH_SQL = "CREATE TABLE IF NOT EXISTS GRAPH_DISTANCE (" +
            "map_id INTEGER NOT NULL, source INTEGER NOT NULL, target INTEGER NOT NULL, " +
            "distance INTEGER NOT NULL, previous INTEGER NOT NULL DEFAULT -1, " +
            "PRIMARY KEY (map_id, source, target))"
        const val CREATE_META_SQL = "CREATE TABLE IF NOT EXISTS _meta (key TEXT PRIMARY KEY, value TEXT)"

        /** qol_dish.ERB:105 + :540-545 的列形态。 */
        const val CREATE_DISH_SQL = "CREATE TABLE IF NOT EXISTS QOL_DISH_DB (" +
            "dish_id INTEGER PRIMARY KEY, dish_name TEXT, dish_taste TEXT, dish_comment TEXT, " +
            "dish_ing TEXT, dish_time INTEGER DEFAULT 0, dish_maxlimit INTEGER DEFAULT 0, " +
            "typecheck TEXT, skill_lv INTEGER DEFAULT 0, has_dynamic INTEGER DEFAULT 0, " +
            "owner_no INTEGER DEFAULT 0, slot INTEGER DEFAULT 0, supply_cond TEXT DEFAULT '', " +
            "teach_difficulty INTEGER DEFAULT 0)"
        const val INSERT_DISH_SQL = "INSERT OR REPLACE INTO QOL_DISH_DB " +
            "(dish_id, dish_name, owner_no, slot) VALUES (@0, @1, @2, @3)"

        /** qol_dish.ERB:545 的原文。 */
        const val CREATE_DISH_PARTIAL_INDEX_SQL = "CREATE UNIQUE INDEX idx_dish_owner_slot " +
            "ON QOL_DISH_DB (owner_no, slot) WHERE owner_no > 0"
        const val CREATE_DISH_PLAIN_INDEX_SQL =
            "CREATE INDEX idx_dish_owner_slot_full ON QOL_DISH_DB (owner_no, slot)"
    }
}
