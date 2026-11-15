package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MIN/MAX 聚合的真实 SQLite 回归测试（SQLite 收尾路线 2）。
 *
 * 迁移前 `d00afc99c` 修的是**自研窄 SQL 解析器**把 `MAX(` 当列名吃掉、玩家面报
 * `SQL 执行失败：期望关键字 FROM，实际是 "("` 的问题（261111 语料 qol_dish.ERB:727 的
 * `SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0`，QOL_DISH_DECLARED_COUNT 声明枠数）。
 * 迁移到 [SqlSession] + [SqlDriver] 后解析层整体消失，但那条修复的语义断言没有跟过来；
 * 本文件用 [RealSqlDriver]（`androidx.sqlite:sqlite-bundled-jvm`，SQLite 3.46.0）把断言面
 * 重钉一遍。全部经 [SqlMethods] 注册的 SQL 内建入口执行，不直接摸 SQLite。
 *
 * 语义权威（fork `SqlManager.cs:238-257` 的 ExecuteScalar 直接透传真实 SQLite）：
 * - 聚合忽略 NULL 实参；空集 / 全 NULL 得 NULL 行；
 * - 标量入口把 NULL 折 0（[SqlMethods] 的 scalarLong，语料 `RETURN MAX(1, CNT, ROWS)` 依赖它）；
 * - GROUP BY 每组一行；裸列与聚合混排按 SQLite bare-column 宽松口径取组内行。
 *
 * 表形与数据沿用玩家现场：`QOL_DISH_DB(owner_no, slot)` = (5,3)(5,5)(5,NULL)(6,1)(7,NULL)(7,NULL)。
 */
class SqlMethodsAggregateTest {

    @Test
    fun `MAX MIN 过滤绑定参数并忽略 NULL 行`(@TempDir root: Path) {
        val env = seeded(root)

        // 语料 qol_dish.ERB:727 原句：SQL_P_* 实参按文本绑定，INTEGER 列亲和性把 '5' 折回数值。
        assertEquals(5L, scalarLongParam(env, "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("5")))
        assertEquals(1L, scalarLongParam(env, "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("6")))
        assertEquals(3L, scalarLongParam(env, "SELECT MIN(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("5")))
        // 无参字面量形态同值：不是参数绑定层兜出来的。
        assertEquals(5L, scalarLong(env, "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = 5"))
    }

    @Test
    fun `空集与全 NULL 的标量聚合折零`(@TempDir root: Path) {
        val env = seeded(root)

        // 空集（owner 999 无行）：聚合产出一行 NULL → 标量入口折 0。
        assertEquals(0L, scalarLongParam(env, "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("999")))
        assertEquals(0L, scalarLongParam(env, "SELECT MIN(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("999")))
        // 全 NULL（owner 7 有两行、slot 全 NULL）：忽略 NULL 后仍是 NULL → 0。
        assertEquals(0L, scalarLongParam(env, "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("7")))
        assertEquals(0L, scalarLongParam(env, "SELECT MIN(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("7")))

        // 证明折的是 NULL、不是列里存着 0：同一句在 reader 上 ISNULL 仍为 1。
        val handle = reader(env, "SELECT MAX(slot) FROM QOL_DISH_DB WHERE owner_no = @0", q("7"))
        assertEquals(1L, read(env, handle))
        assertEquals(1L, readerInt(env, "SQL_READER_ISNULL", handle, 0))
        assertEquals(0L, readerInt(env, "SQL_READER_GET_LONG", handle, 0))
        assertEquals(1L, closeReader(env, handle))
    }

    @Test
    fun `GROUP BY 逐行读 owner 与 MAX`(@TempDir root: Path) {
        val env = seeded(root)
        val handle = reader(
            env,
            "SELECT owner_no, MAX(slot) FROM QOL_DISH_DB GROUP BY owner_no ORDER BY owner_no",
        )

        assertEquals(1L, read(env, handle))
        assertEquals(5L, readerInt(env, "SQL_READER_GET_LONG", handle, 0))
        assertEquals(5L, readerInt(env, "SQL_READER_GET_LONG", handle, 1))

        assertEquals(1L, read(env, handle))
        assertEquals(6L, readerInt(env, "SQL_READER_GET_LONG", handle, 0))
        assertEquals(1L, readerInt(env, "SQL_READER_GET_LONG", handle, 1))

        // 第三组 owner 7 全 NULL：MAX 是 NULL（ISNULL=1），GET_LONG 对 NULL 返 0。
        assertEquals(1L, read(env, handle))
        assertEquals(7L, readerInt(env, "SQL_READER_GET_LONG", handle, 0))
        assertEquals(1L, readerInt(env, "SQL_READER_ISNULL", handle, 1))
        assertEquals(0L, readerInt(env, "SQL_READER_GET_LONG", handle, 1))

        assertEquals(0L, read(env, handle), "三组读完后必须返回 0")
        assertEquals(1L, closeReader(env, handle))
        assertEquals(0L, read(env, handle), "关闭后的句柄推进返回 0（spec 口径）")
    }

    @Test
    fun `裸列与 MAX 混排的 GROUP BY 不报错且行值正确`(@TempDir root: Path) {
        val env = seeded(root)
        // 无 ORDER BY：不锁定组的输出顺序，只要不报错且每组一行、值对。
        val handle = reader(env, "SELECT owner_no, MAX(slot) FROM QOL_DISH_DB GROUP BY owner_no")
        val rows = LinkedHashMap<Long, Long>()
        while (read(env, handle) == 1L) {
            rows[readerInt(env, "SQL_READER_GET_LONG", handle, 0)] =
                readerInt(env, "SQL_READER_GET_LONG", handle, 1)
        }

        assertEquals(3, rows.size, "三组各一行：$rows")
        assertEquals(mapOf(5L to 5L, 6L to 1L, 7L to 0L), rows)
        assertEquals(1L, closeReader(env, handle))
    }

    @Test
    fun `SqlSession 收口关闭全部连接与 reader 且旧句柄失效`(@TempDir root: Path) {
        val env = seeded(root)
        // DESC 把 NULL 排到最后：首行是最满的槽 5（升序时 SQLite 把 NULL 排最前）。
        val handle = reader(env, "SELECT slot FROM QOL_DISH_DB ORDER BY slot DESC")
        assertEquals(1L, read(env, handle))
        assertEquals(5L, readerInt(env, "SQL_READER_GET_LONG", handle, 0))
        assertNotNull(env.session(), "SQL 内建执行过一次后必须有会话槽")
        assertEquals(listOf(":memory:"), env.real.opened)

        // 生产收口路径：RuntimeAux.clear() 会逐个 close 实现 AutoCloseable 的槽（SQL 会话）。
        env.store.aux.clear()

        assertNull(env.session(), "清空后 sql 槽必须被摘除")
        assertEquals(env.driver.opened, env.driver.closedConnections, "连接必须全关（open == close）")
        assertEquals(env.driver.prepared, env.driver.closedStatements, "语句必须全关（prepare == close）")
        assertTrue(env.driver.opened > 0 && env.driver.prepared > 0, "计数面必须真的跑过：$env")
        assertEquals(0L, read(env, handle), "会话关闭后旧 reader 句柄失效，READ 返 0")
        val error = assertFailsWith<CodeEE> {
            scalarLong(env, "SELECT 1")
        }
        assertTrue(error.message!!.contains("不存在"), error.message!!)
    }
}

// ---------------------------------------------------------------------------
// harness：真实驱动 + 生产 SQL 内建入口
// ---------------------------------------------------------------------------

/** 建 `QOL_DISH_DB` 并灌入玩家现场的 6 行（两行 slot 为 NULL）。 */
private fun seeded(root: Path): AggregateSqlEnv {
    val env = AggregateSqlEnv(root)
    assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q("DB")))
    exec(env, "CREATE TABLE QOL_DISH_DB (owner_no INTEGER, slot INTEGER)")
    val insert = "INSERT INTO QOL_DISH_DB (owner_no, slot) VALUES (@0, @1)"
    exec(env, insert, q("5"), q("3"))
    exec(env, insert, q("5"), q("5"))
    exec(env, insert, q("5"), null)
    exec(env, insert, q("6"), q("1"))
    exec(env, insert, q("7"), null)
    exec(env, insert, q("7"), null)
    return env
}

private fun exec(env: AggregateSqlEnv, sql: String, vararg params: Value?) {
    env.ctx.callFunction("SQL_P_EXECUTE_NONQUERY", argsOf(sql, params))
}

private fun scalarLong(env: AggregateSqlEnv, sql: String): Long =
    intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q("DB"), q(sql))

private fun scalarLongParam(env: AggregateSqlEnv, sql: String, vararg params: Value?): Long =
    (env.ctx.callFunction("SQL_P_EXECUTE_SCALAR_LONG", argsOf(sql, params)) as Value.IntV).v

private fun reader(env: AggregateSqlEnv, sql: String, vararg params: Value?): Long =
    (env.ctx.callFunction("SQL_P_EXECUTE_READER", argsOf(sql, params)) as Value.IntV).v

private fun read(env: AggregateSqlEnv, handle: Long): Long =
    intOf(env.ctx, "SQL_READER_READ", n(handle))

private fun readerInt(env: AggregateSqlEnv, fn: String, handle: Long, column: Int): Long =
    intOf(env.ctx, fn, n(handle), n(column.toLong()))

private fun closeReader(env: AggregateSqlEnv, handle: Long): Long =
    intOf(env.ctx, "SQL_READER_CLOSE", n(handle))

/** `db, sql, @0...` 的实参表；内建层自己把 IntV/StrV 折成 SQL 文本绑定。 */
private fun argsOf(sql: String, params: Array<out Value?>): List<Value?> {
    val args = ArrayList<Value?>(params.size + 2)
    args.add(q("DB"))
    args.add(q(sql))
    args.addAll(params)
    return args
}

private fun intOf(c: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (c.callFunction(name, args.toList()) as Value.IntV).v

private fun q(s: String): Value = Value.StrV(s)

private fun n(v: Long): Value = Value.IntV(v)

private class AggregateSqlEnv(root: Path) {
    /** 真实 bundled 驱动；[driver] 只加计数包装，执行面仍是 SQLite 3.46.0。 */
    val real = RealSqlDriver(root)
    val driver = CountingSqlDriver(real)
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        val base = AggregateBaseCtx()
        base.variables = store
        ctx = BuiltinEvalContext(base, store, AggregateHost(RootedFileOps(root), driver))
    }

    /** 取 `store.aux` 的 `"sql"` 槽；没建过 / 被 clear 后为 null。 */
    fun session(): SqlSession? = store.aux.peek<SqlSession>(SQL_AUX_SLOT)
}

private class AggregateBaseCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("AggregateBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) = Unit
}

private class AggregateHost(
    override val fileOps: FileOps,
    override val sqlDriver: SqlDriver,
) : BuiltinHost

/**
 * 只计数、不改语义的驱动包装：证明 [SqlSession.close] 把连接与语句都关掉（open == close）。
 *
 * 不改生产码，也不改共享的 [RealSqlDriver]（本波只允许新增一个测试文件）。
 */
private class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver {
    var opened = 0
    var closedConnections = 0
    var prepared = 0
    var closedStatements = 0

    override fun open(connectionString: String): SqlConnection {
        opened += 1
        return CountingConnection(delegate.open(connectionString))
    }

    private inner class CountingConnection(private val inner: SqlConnection) : SqlConnection {
        private var closed = false

        override fun prepare(sql: String): SqlStatement {
            prepared += 1
            return CountingStatement(inner.prepare(sql))
        }

        override fun totalChanges(): Long = inner.totalChanges()

        override fun lastChanges(): Long = inner.lastChanges()

        override fun close() {
            if (closed) return
            closed = true
            closedConnections += 1
            inner.close()
        }
    }

    private inner class CountingStatement(private val inner: SqlStatement) : SqlStatement {
        private var closed = false

        override val columnCount: Int get() = inner.columnCount

        override fun bind(index: Int, value: SqlBind) = inner.bind(index, value)

        override fun step(): Boolean = inner.step()

        override fun reset() = inner.reset()

        override fun clearBindings() = inner.clearBindings()

        override fun columnName(index: Int): String = inner.columnName(index)

        override fun columnType(index: Int): SqlColumnType = inner.columnType(index)

        override fun isNull(index: Int): Boolean = inner.isNull(index)

        override fun getLong(index: Int): Long = inner.getLong(index)

        override fun getDouble(index: Int): Double = inner.getDouble(index)

        override fun getText(index: Int): String = inner.getText(index)

        override fun getBlob(index: Int): ByteArray = inner.getBlob(index)

        override fun close() {
            if (closed) return
            closed = true
            closedStatements += 1
            inner.close()
        }
    }
}
