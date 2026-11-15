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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * P3 PERF-PROBE：SQLite 迁移的引擎侧最小性能探针。
 *
 * 只量化 0.2.46 玩家低效反馈里最痛的三条，全部走真 [BundledSQLiteDriver]（SQLite 3.46.0）
 * + [AndroidXSqliteDriver]，不经任何 fake：
 * 1. 带 GRAPH_DISTANCE 的真实 qol_data.db（当前只在 261017 退役留档根，36418 行）从
 *    seed 拷进沙箱后的首查、全表行读、1000 次「重新 prepare + 主键点查」；没有合格库
 *    时本组用 `assumeTrue` 跳过，临时库组不受影响、必须跑；
 * 2. 1000 次带参数 `SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0`（261111
 *    qol_dish.ERB:727 的商用形态）；
 * 3. 批量 `INSERT OR REPLACE` 1000 行：自动提交 vs 单事务。
 *
 * 口径：System.nanoTime 墙钟、单进程单线程；真实库组先测「拷贝 + 打开 + 首查」，不做
 * 预热（首查就是拷贝后的第一条业务 SQL），其余每段先预热再计时。断言只设**数量级兜底**
 * （秒级），不锁精确预算——本文件的价值是给 0.2.46 低效反馈留引擎侧证据，不是回归预算闸。
 * 读数同时 println 与写入 build/perf-probe 下的独立文本，JUnit XML 的 system-out 也留一份。
 */
@Suppress("LongMethod")
class SqlitePerformanceProbeTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `真实 qol_data_db GRAPH_DISTANCE 36418 行首查与重复 prepare`() {
        val seedDb = locateRealQolDb()
        assumeTrue(
            seedDb != null,
            "候选里没有带 ≥$MIN_GRAPH_ROWS 行 GRAPH_DISTANCE 的 qol_data.db：真实库组跳过；临时库组仍会跑",
        )
        val seedRoot = seedDb!!.parent.parent
        val sandbox = tmp.resolve("real-sandbox")
        val driver = AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, sandbox)

        val openStart = System.nanoTime()
        val db = driver.open("Data Source=plugins/qol_data.db")
        val copyOpenMs = elapsedMs(openStart)
        try {
            val unreachable = SqlBind.Integer(2_147_483_647L)
            val countSql = "SELECT COUNT(*) FROM GRAPH_DISTANCE WHERE distance != @0"

            // 首查：拷贝后第一条碰 GRAPH_DISTANCE 的语句，全表 36418 行扫描，不预热。
            val firstStart = System.nanoTime()
            val reachable = scalarLong(db, countSql, unreachable)
            val firstQueryMs = elapsedMs(firstStart)

            val totalRows = scalarLong(db, "SELECT COUNT(*) FROM GRAPH_DISTANCE")
            val version = scalarText(db, "SELECT sqlite_version()")

            val warmStart = System.nanoTime()
            repeat(WARMUP) { assertEquals(reachable, scalarLong(db, countSql, unreachable)) }
            val warmAvgMs = elapsedMs(warmStart) / WARMUP

            val rowsStart = System.nanoTime()
            var rows = 0L
            var sink = 0L
            db.prepare("SELECT map_id, source, target, distance, previous FROM GRAPH_DISTANCE").use { statement ->
                while (statement.step()) {
                    sink += statement.getLong(0) + statement.getLong(1) + statement.getLong(2) +
                        statement.getLong(3) + statement.getLong(4)
                    rows++
                }
            }
            val rowReadMs = elapsedMs(rowsStart)

            // 真实语料 qol_graph_query.ERB:42 的主键点查形态：map_id/source/target 三参。
            val lookupSql =
                "SELECT distance FROM GRAPH_DISTANCE WHERE map_id = @0 AND source = @1 AND target = @2"
            val (mapId, source, target) = db.prepare("SELECT map_id, source, target FROM GRAPH_DISTANCE LIMIT 1")
                .use { statement ->
                    assertTrue(statement.step(), "GRAPH_DISTANCE 为空")
                    Triple(statement.getLong(0), statement.getLong(1), statement.getLong(2))
                }

            fun lookupFresh(): Long = db.prepare(lookupSql).use { statement ->
                statement.bind(1, SqlBind.Integer(mapId))
                statement.bind(2, SqlBind.Integer(source))
                statement.bind(3, SqlBind.Integer(target))
                assertTrue(statement.step(), "GRAPH_DISTANCE 主键点查无结果")
                statement.getLong(0)
            }
            repeat(WARMUP) { sink += lookupFresh() }
            val freshStart = System.nanoTime()
            repeat(ITERATIONS) { sink += lookupFresh() }
            val freshMs = elapsedMs(freshStart)

            val reusedMs = db.prepare(lookupSql).use { statement ->
                repeat(WARMUP) {
                    statement.reset()
                    statement.clearBindings()
                    statement.bind(1, SqlBind.Integer(mapId))
                    statement.bind(2, SqlBind.Integer(source))
                    statement.bind(3, SqlBind.Integer(target))
                    assertTrue(statement.step())
                    sink += statement.getLong(0)
                }
                val reuseStart = System.nanoTime()
                repeat(ITERATIONS) {
                    statement.reset()
                    statement.clearBindings()
                    statement.bind(1, SqlBind.Integer(mapId))
                    statement.bind(2, SqlBind.Integer(source))
                    statement.bind(3, SqlBind.Integer(target))
                    assertTrue(statement.step())
                    sink += statement.getLong(0)
                }
                elapsedMs(reuseStart)
            }

            val lines = listOf(
                "sqlite_version=$version",
                "seed=${seedDb.toAbsolutePath()} seed_bytes=${Files.size(seedDb)}",
                "sandbox=${sandbox.resolve("plugins/qol_data.db").toAbsolutePath()}",
                "seed_copy_plus_open_ms=${num(copyOpenMs)}",
                "graph_distance_rows=$totalRows",
                "first_query_full_scan_ms=${num(firstQueryMs)} reachable_rows=$reachable",
                "repeat_same_query_${WARMUP}x_avg_ms=${num(warmAvgMs)}",
                "full_table_row_read_ms=${num(rowReadMs)} rows=$rows cols=5",
                "repeat_prepare_${ITERATIONS}x_pk_lookup_ms=${num(freshMs)} us_per_op=${num(usPerOp(freshMs))}",
                "reuse_statement_${ITERATIONS}x_pk_lookup_ms=${num(reusedMs)} us_per_op=${num(usPerOp(reusedMs))}",
                "query_plan_pk_lookup=${queryPlan(db, lookupSql).joinToString(" | ")}",
                "sink=$sink",
            )
            report("graph-distance", lines)

            assertEquals(totalRows, rows, "全表行读行数应与 COUNT(*) 一致")
            assertTrue(totalRows >= MIN_GRAPH_ROWS, "GRAPH_DISTANCE 行数低于探针判据：$totalRows")
            assertTrue(firstQueryMs < QUERY_BOUND_MS, "首查超过兜底上限：${num(firstQueryMs)} ms")
            assertTrue(freshMs < QUERY_BOUND_MS, "1000 次重复 prepare 点查超过兜底上限：${num(freshMs)} ms")
            assertTrue(reusedMs < QUERY_BOUND_MS, "1000 次复用语句点查超过兜底上限：${num(reusedMs)} ms")
            assertTrue(rowReadMs < QUERY_BOUND_MS, "36418 行全表行读超过兜底上限：${num(rowReadMs)} ms")
        } finally {
            db.close()
        }
    }

    @Test
    fun `临时库 1000 次参数化 SELECT MAX slot`() {
        val db = newDriver().open("Data Source=maxslot.db")
        try {
            execute(db, CREATE_DISH_SQL)
            execute(
                db,
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_dish_owner_slot " +
                    "ON QOL_DISH_DB (owner_no, slot) WHERE owner_no > 0",
            )
            execute(db, "BEGIN")
            try {
                db.prepare(
                    "INSERT OR REPLACE INTO QOL_DISH_DB (dish_id, dish_name, owner_no, slot) " +
                        "VALUES (@0, @1, @2, @3)",
                ).use { statement ->
                    repeat(2_000) { i ->
                        statement.reset()
                        statement.clearBindings()
                        statement.bind(1, SqlBind.Integer(i + 1L))
                        statement.bind(2, SqlBind.Text("dish-$i"))
                        statement.bind(3, SqlBind.Integer((i % 200).toLong()))
                        statement.bind(4, SqlBind.Integer((i / 200).toLong()))
                        assertFalse(statement.step())
                    }
                }
                execute(db, "COMMIT")
            } catch (e: Throwable) {
                runCatching { execute(db, "ROLLBACK") }
                throw e
            }
            assertEquals(2_000L, scalarLong(db, "SELECT COUNT(*) FROM QOL_DISH_DB"))

            val maxSlotSql = "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0"
            // owner_no 1..199 各有 slot 0..9 十行，MAX(slot) 恒为 9；0 号 owner 不在计时样本里。
            fun maxSlot(owner: Long): Long = db.prepare(maxSlotSql).use { statement ->
                statement.bind(1, SqlBind.Integer(owner))
                assertTrue(statement.step(), "SELECT MAX(slot) 无结果行")
                statement.getLong(0)
            }
            repeat(WARMUP) { maxSlot((it % 199 + 1).toLong()) }

            val freshStart = System.nanoTime()
            var sink = 0L
            repeat(ITERATIONS) { sink += maxSlot((it % 199 + 1).toLong()) }
            val freshMs = elapsedMs(freshStart)
            assertEquals(9L * ITERATIONS, sink)

            val reusedMs = db.prepare(maxSlotSql).use { statement ->
                repeat(WARMUP) {
                    statement.reset()
                    statement.clearBindings()
                    statement.bind(1, SqlBind.Integer((it % 199 + 1).toLong()))
                    assertTrue(statement.step())
                    statement.getLong(0)
                }
                val reuseStart = System.nanoTime()
                repeat(ITERATIONS) {
                    statement.reset()
                    statement.clearBindings()
                    statement.bind(1, SqlBind.Integer((it % 199 + 1).toLong()))
                    assertTrue(statement.step())
                    statement.getLong(0)
                }
                elapsedMs(reuseStart)
            }

            val lines = listOf(
                "sqlite_version=${scalarText(db, "SELECT sqlite_version()")}",
                "rows=2000 owners=199 slots_per_owner=10",
                "repeat_prepare_${ITERATIONS}x_select_max_slot_ms=${num(freshMs)} us_per_op=${num(usPerOp(freshMs))}",
                "reuse_statement_${ITERATIONS}x_select_max_slot_ms=${num(reusedMs)} " +
                    "us_per_op=${num(usPerOp(reusedMs))}",
                "query_plan_max_slot=${queryPlan(db, maxSlotSql).joinToString(" | ")}",
            )
            report("max-slot-1000", lines)
            assertTrue(freshMs < QUERY_BOUND_MS, "1000 次 SELECT MAX(slot) 超过兜底上限：${num(freshMs)} ms")
        } finally {
            db.close()
        }
    }

    @Test
    fun `临时库 1000 行 INSERT OR REPLACE 自动提交与单事务`() {
        val driver = newDriver()
        val autoMs = batchUpsert(driver, "batch-auto.db", inTransaction = false)
        val txMs = batchUpsert(driver, "batch-tx.db", inTransaction = true)

        val lines = listOf(
            "rows=$BATCH_ROWS",
            "autocommit_${BATCH_ROWS}x_insert_or_replace_ms=${num(autoMs)} " +
                "us_per_row=${num(autoMs * 1000.0 / BATCH_ROWS)}",
            "single_transaction_${BATCH_ROWS}x_insert_or_replace_ms=${num(txMs)} " +
                "us_per_row=${num(txMs * 1000.0 / BATCH_ROWS)}",
            "speedup=${num(autoMs / txMs)}x",
            "bounds_ms: autocommit<$AUTOCOMMIT_BOUND_MS transaction<$TRANSACTION_BOUND_MS",
        )
        report("batch-upsert", lines)
        assertTrue(autoMs < AUTOCOMMIT_BOUND_MS, "1000 行自动提交超过兜底上限：${num(autoMs)} ms")
        assertTrue(txMs < TRANSACTION_BOUND_MS, "1000 行单事务超过兜底上限：${num(txMs)} ms")
    }

    /**
     * 换代后默认定位的回归闸：0 字节占位库（当前随包根实况）与「有效但无图表」的库
     * 都必须被跳过，只有真带 ≥[MIN_GRAPH_ROWS] 行 GRAPH_DISTANCE 的库入选；全部
     * 不合格 ⇒ null（沿用 `assumeTrue` 跳过语义）。用例自建临时库，不依赖共享语料根，
     * 三个候选的摆放形态与真实候选一致（都在 `<根>/plugins/qol_data.db`）。
     */
    @Test
    fun `候选库按 GRAPH_DISTANCE 数据取列 空库与无图表库必须跳过`() {
        val empty = Files.createDirectories(tmp.resolve("empty/plugins")).resolve("qol_data.db")
        Files.write(empty, byteArrayOf())
        val blank = Files.createDirectories(tmp.resolve("blank/plugins")).resolve("qol_data.db")
        BundledSQLiteDriver().open(blank.toString()).use { connection ->
            connection.prepare("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)").use { it.step() }
        }
        val real = Files.createDirectories(tmp.resolve("real/plugins")).resolve("qol_data.db")
        BundledSQLiteDriver().open(real.toString()).use { connection ->
            connection.prepare(CREATE_GRAPH_SQL).use { it.step() }
            connection
                .prepare(
                    "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < $MIN_GRAPH_ROWS) " +
                        "INSERT INTO GRAPH_DISTANCE SELECT x, x, x, 1, 0 FROM n",
                )
                .use { it.step() }
        }

        assertEquals(MIN_GRAPH_ROWS, graphDistanceRows(real), "回归夹具自身应带满额图表行")
        assertEquals(0L, graphDistanceRows(empty), "0 字节占位库不得交给 SQLite 探表")
        assertEquals(real, selectUsableQolDb(sequenceOf(empty, blank, real)))
        assertNull(selectUsableQolDb(sequenceOf(empty, blank)))
    }

    /** 两张库各写 1000 行；唯一变量是「每行一次隐式提交」还是「整批一次显式提交」。 */
    private fun batchUpsert(driver: AndroidXSqliteDriver, file: String, inTransaction: Boolean): Double {
        driver.open("Data Source=$file").use { db ->
            execute(db, CREATE_EDGE_SQL)
            val batchStart = System.nanoTime()
            if (inTransaction) execute(db, "BEGIN")
            try {
                db.prepare(
                    "INSERT OR REPLACE INTO perf_edge (map_id, from_node, to_node, can_move, can_see) " +
                        "VALUES (@0, @1, @2, @3, @4)",
                ).use { statement ->
                    repeat(BATCH_ROWS) { i ->
                        statement.reset()
                        statement.clearBindings()
                        statement.bind(1, SqlBind.Integer((i / 100).toLong()))
                        statement.bind(2, SqlBind.Integer((i % 100).toLong()))
                        statement.bind(3, SqlBind.Integer(i.toLong()))
                        statement.bind(4, SqlBind.Integer(1L))
                        statement.bind(5, SqlBind.Integer(0L))
                        assertFalse(statement.step())
                    }
                }
                if (inTransaction) execute(db, "COMMIT")
            } catch (e: Throwable) {
                if (inTransaction) runCatching { execute(db, "ROLLBACK") }
                throw e
            }
            val ms = elapsedMs(batchStart)
            assertEquals(BATCH_ROWS.toLong(), scalarLong(db, "SELECT COUNT(*) FROM perf_edge"))
            return ms
        }
    }

    private fun newDriver(): AndroidXSqliteDriver {
        val seedRoot = Files.createDirectories(tmp.resolve("seed"))
        val writeRoot = Files.createDirectories(tmp.resolve("sandbox"))
        return AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, writeRoot)
    }

    private fun execute(db: SqlConnection, sql: String, vararg params: SqlBind) {
        db.prepare(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.bind(index + 1, value) }
            statement.step()
        }
    }

    private fun scalarLong(db: SqlConnection, sql: String, vararg params: SqlBind): Long =
        db.prepare(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.bind(index + 1, value) }
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

    /**
     * 真实 qol_data.db 的候选枚举：显式属性/环境变量最高优先（尊重调用者意图，给什么用
     * 什么、不校验不改选），其余按「共享根约定 + 当前测试工作目录祖先」做确定性别名，
     * 不扫全盘。候选是否合格由 [selectUsableQolDb] 按 GRAPH_DISTANCE 数据判定，
     * 不按目录名/版本分支挑库。
     */
    private fun locateRealQolDb(): Path? {
        listOfNotNull(
            System.getProperty("eratw.qol.db"),
            System.getenv("ERATW_QOL_DATA_DB"),
        ).map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }
            ?.let { return it }

        val gameRoots = ArrayList<Path>()
        listOfNotNull(System.getenv("ERATW_GAMEDIR"), System.getenv("ERATW_QOL_DB_ROOT"))
            .mapTo(gameRoots) { Path.of(it) }
        System.getenv("ERATW_CORPUS")?.let { gameRoots.add(Path.of(it).parent) }
        System.getenv("ERATW_ROOT")?.let {
            gameRoots.add(Path.of(it).resolve("eraTW4.981-261017-EAGLOV3"))
        }

        val userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        generateSequence(userDir) { it.parent }.take(6).forEach { dir ->
            gameRoots.add(dir.resolve("eraTW4.981-261017-EAGLOV3"))
            gameRoots.add(dir.resolve("eratw-安卓移植计划").resolve("eraTW4.981-261017-EAGLOV3"))
        }
        return selectUsableQolDb(
            gameRoots.asSequence().distinct().map { it.resolve("plugins/qol_data.db") },
        )
    }

    /**
     * 候选按「档案」取列：只认真的带 ≥[MIN_GRAPH_ROWS] 行 GRAPH_DISTANCE 的库。
     *
     * 换代 261017→261111 后，当前随包根的 `plugins/qol_data.db` 是 **0 字节占位**
     * （0 表，图数据只留在退役留档根），旧实现 `firstOrNull { Files.isRegularFile(it) }`
     * 只看「文件在不在」，排第一的 `ERATW_GAMEDIR` 空库直接截胡、真库永远轮不到
     * ⇒ `no such table: GRAPH_DISTANCE`（本次修复的根因）。改成按数据可用性筛选后：
     * 候选顺序与显式变量语义不变，当前根哪天补齐图数据会自然优先命中；一个合格库都
     * 没有时返回 null，沿用 `assumeTrue` 跳过语义（临时库组照跑），不伪造数据、不静默改选。
     */
    private fun selectUsableQolDb(candidates: Sequence<Path>): Path? =
        candidates.firstOrNull { graphDistanceRows(it) >= MIN_GRAPH_ROWS }

    /**
     * 只读探表：同 `SqlitePluginDbInventoryTest.withDirect` 的口径，用 bundled 驱动对
     * seed 直接 SELECT，不写语料根。0 字节占位文件先挡掉（不交给 SQLite 初始化）；
     * 打不开、不是 SQLite、没有 GRAPH_DISTANCE 一律当 0 行，继续看下一个候选。
     */
    private fun graphDistanceRows(seed: Path): Long {
        if (!Files.isRegularFile(seed) || Files.size(seed) == 0L) return 0L
        return runCatching {
            BundledSQLiteDriver().open(seed.toString()).use { connection ->
                connection.prepare("SELECT COUNT(*) FROM GRAPH_DISTANCE").use { statement ->
                    if (statement.step()) statement.getLong(0) else 0L
                }
            }
        }.onFailure {
            println("[$PROBE] 候选库无 GRAPH_DISTANCE：${seed.toAbsolutePath()} → ${it.message}")
        }.getOrElse { 0L }
    }

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

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0

    private fun usPerOp(ms: Double): Double = ms * 1000.0 / ITERATIONS

    private fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val PROBE = "PERF-PROBE"
        const val ITERATIONS = 1_000
        const val WARMUP = 100
        const val BATCH_ROWS = 1_000

        /** 秒级兜底：只挡「数量级灾难」，不是性能预算；实测读数在报告里。 */
        const val QUERY_BOUND_MS = 5_000.0
        const val AUTOCOMMIT_BOUND_MS = 30_000.0
        const val TRANSACTION_BOUND_MS = 10_000.0

        /**
         * 真实库入选判据：GRAPH_DISTANCE 行数下限（当前随包真库 = 261017 的 36418 行）。
         * 与上面第一组用例的断言共用一个常量，保证「定位筛选」与「行数判据」同源。
         */
        const val MIN_GRAPH_ROWS = 30_000L

        /** 回归夹具建表：与 261017 随包真库同名同列序（探表只看表名与行数）。 */
        const val CREATE_GRAPH_SQL = "CREATE TABLE IF NOT EXISTS GRAPH_DISTANCE (" +
            "map_id INTEGER NOT NULL, source INTEGER NOT NULL, target INTEGER NOT NULL, " +
            "distance INTEGER NOT NULL DEFAULT 0, previous INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (map_id, source, target))"

        const val CREATE_DISH_SQL = "CREATE TABLE IF NOT EXISTS QOL_DISH_DB (" +
            "dish_id INTEGER PRIMARY KEY, dish_name TEXT, dish_taste TEXT, dish_comment TEXT, " +
            "dish_ing TEXT, dish_time INTEGER DEFAULT 0, dish_maxlimit INTEGER DEFAULT 0, " +
            "typecheck TEXT, skill_lv INTEGER DEFAULT 0, has_dynamic INTEGER DEFAULT 0, " +
            "owner_no INTEGER DEFAULT 0, slot INTEGER DEFAULT 0, supply_cond TEXT DEFAULT '', " +
            "teach_difficulty INTEGER DEFAULT 0)"

        const val CREATE_EDGE_SQL = "CREATE TABLE IF NOT EXISTS perf_edge (" +
            "map_id INTEGER NOT NULL, from_node INTEGER NOT NULL, to_node INTEGER NOT NULL, " +
            "can_move INTEGER NOT NULL DEFAULT 0, can_see INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (map_id, from_node, to_node))"
    }
}
