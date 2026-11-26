package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE

/** `RuntimeAux` 里 SQL 会话的槽名（原版 `GlobalStatic.SqlManager` 的对应物）。 */
internal const val SQL_AUX_SLOT = "sql"

/** `SQL_EXECUTE_NONQUERY` 对结果集（`columnCount > 0`）的返回值：Microsoft.Data.Sqlite 口径。 */
private const val SELECT_NONQUERY_RESULT = -1L

/**
 * SQL 单元格的四态值。真实 SQLite 的存储类经 [SqlConnection] 端口折成这四档：
 * `INTEGER` 进 [VInt]，`FLOAT` 进 [VFloat]，`TEXT` 进 [VText]，`NULL` 进 [VNull]。
 *
 * `BLOB` 本波仍按驱动文本折进 [VText]（`CONTRACT-SQLITE-MIGRATION` §2；blob 波再扩类型）。
 * 四态是**不可变**的，reader 每次读列都新建一个实例。
 */
internal sealed class SqlValue {
    data class VInt(val v: Long) : SqlValue()
    data class VFloat(val v: Double) : SqlValue()
    data class VText(val v: String) : SqlValue()
    object VNull : SqlValue()

    /** 文本形态（`GET_STRING` / `SCALAR_STRING` 用；null 折成空串）。 */
    fun asText(): String = when (this) {
        is VInt -> v.toString()
        is VFloat -> v.toString()
        is VText -> v
        VNull -> ""
    }
}

/**
 * 每 VM 一份的 SQL 会话：连接名表 + reader 句柄表，执行面全部走 [SqlDriver] 端口。
 *
 * 本类**不解析 SQL、不读 SQLite 文件**：`prepare` / `step` / `totalChanges` 等语义由注入的
 * 平台驱动（Android bundled SQLite / 桌面 JVM SQLite）兑现。旧自研引擎状态一律不经本类。
 *
 * 状态由 `BuiltinContext.store.aux` 的 [SQL_AUX_SLOT] 槽持有（铁律 4：禁静态可变全局）；
 * `RuntimeAux.clear()` 即原版 `GlobalStatic.Reset()` 里的 `SqlManager.CloseAll()`。
 *
 * ## 硬语义（契约 §2）
 * - 连接名**大小写不敏感**；同名已存在时幂等返回，不重开、不改绑（上游 `SqlManager` 口径）。
 * - 新连接 [connectionString] 为 null 时开 `:memory:`，否则把原串透传给驱动。
 * - reader 句柄从 1 单调递增、关闭后**不复用**。
 * - [executeNonQuery] 按 `totalChanges` 前后差取受影响行数，DDL 不得串用上一次 DML 的
 *   `lastChanges`。
 * - [close] 先关 reader 再关 connection，幂等；首个失败在所有关闭尝试后抛出。
 */
@Suppress("TooManyFunctions")
internal class SqlSession(private val driver: SqlDriver) : AutoCloseable {
    /** 连接名（小写）→ 连接。同名连接只开一次，见 [connect]。 */
    private val connections = HashMap<String, SqlConnection>()

    /** 句柄 → reader。句柄单调递增，关闭即删除。 */
    private val readers = LinkedHashMap<Long, Reader>()

    private var nextHandle = 1L
    private var closed = false

    /**
     * 语句边界互斥锁（C2 修复批，组长单点授权）：VM 线程的每一条语句/行操作与宿主 close
     * 在此单点互斥，**不动任何 SQL 语义、连接池策略与查询实现**。
     *
     * ## 为什么要有这把锁
     *
     * 驱动按单线程契约设计（`AndroidXSqliteDriver.kt:42`「连接/语句只在创建它的 VM 线程
     * 使用」），而宿主 [close] 从 UI 线程进来（`GameSession.close` → aux 槽）——close 与
     * VM 语句执行并发时，本类的两张表、连接的空闲语句池（access-order LinkedHashMap）与
     * `closed` 标志全部无锁：并发 put/drain 是结构性竞态，最坏把遍历方钉成死循环（落在
     * UI 线程 = ANR 硬挂），native 半边在非 serialized 编译档下更是 UB。原版无此面对应物
     * ——WinForms 单线程，脚本运行期窗口直接拒绝操作（`MainWindow.cs:1085-1087`），
     * 退出从不与脚本并发；判据自立：**移植自有资源面不得比单线程原版多出 UB**。
     *
     * ## 互斥序（「确定序」的含义）
     *
     * - VM 侧每个入口持锁到**语句边界**（一条语句跑完 / 一次行推进完成）才放锁；
     * - [close] 取同一把锁独占 ⇒ close 等到的是「在途语句收尾」，不是 VM 整条链——
     *   实测最坏合法链 2.9 亿指令 ≈130s 是纯计算形态，从不持本锁；语料语句（点查
     *   199/324、全表扫描均为小表）ms 级，UI 等待上界 = 当前在途语句的剩余时长；
     * - close 之后 VM 再撞 SQL：表已清空，走「连接不存在 / 无效 Reader ID」的既有
     *   CodeEE 口径上浮，被宿主 closed 首查静默（`GameHost.kt` 关闭协议第 2 条）——
     *   与现状唯一差别是这些撞击与 close **不再交错**。
     *
     * 监视器可重入：[connectionOpen] 内部经 [executeNonQuery] 执行 PRAGMA，同线程嵌套取锁无害。
     */
    private val boundaryLock = Any()

    /** 一条已 prepare 的 reader 语句；[hasRow] 记录最近一次 [readerRead] 是否落在有效行。 */
    private class Reader(val statement: SqlStatement) {
        var hasRow = false
    }

    /**
     * `SQL_CONNECT name[, "Data Source=path"]`。
     *
     * 同名（忽略大小写）已存在时直接返回，**不**重开、**不**改绑 —— 语料里每个
     * `@..._INIT` 都会重连一次，重复 SQL_CONNECT 必须幂等；带不同 Data Source 的重连
     * 也按上游 `SqlManager` 的先到先得口径处理。
     *
     * 驱动打开失败（[SqlNativeException]）不吞、不登记：S0 让它上抛，M1 负责包成 CodeEE。
     */
    fun connect(name: String, connectionString: String?) {
        synchronized(boundaryLock) {
            val key = name.lowercase()
            if (connections.containsKey(key)) return
            connections[key] = driver.open(connectionString ?: MEMORY_DATABASE)
        }
    }

    /**
     * `SQL_CONNECTION_OPEN name`：在宿主 SQL 写根下重开 `<name>.db`。
     *
     * 与 [connect] 的幂等口径相反：同名连接已存在时**先关再开**（上游
     * `SqlManager.ConnectionOpen` 的 `existingConn.Close()` 分支，`SqlManager.cs:26-31`）。
     * `PRAGMA journal_mode = WAL` / `PRAGMA synchronous = NORMAL` 在登记前执行；任一步失败
     * 都关闭新连接并把它从表里摘掉，避免留下半开连接（`SqlManager.cs:38-55`）。
     *
     * @param connectionString 已由函数层按宿主写根拼好的连接串（相对文件名）。
     */
    @Suppress("TooGenericExceptionCaught")
    fun connectionOpen(name: String, connectionString: String) {
        synchronized(boundaryLock) {
            val key = name.lowercase()
            connections.remove(key)?.close()
            val database = driver.open(connectionString)
            try {
                executeNonQuery(database, "PRAGMA journal_mode = WAL")
                executeNonQuery(database, "PRAGMA synchronous = NORMAL")
            } catch (e: Throwable) {
                runCatching { database.close() }
                throw e
            }
            connections[key] = database
        }
    }

    /**
     * `SQL_DISCONNECT name`：关闭并移除命名连接；不存在时 no-op（`SqlManager.cs:86-94`）。
     *
     * reader 表不动：上游 `Disconnect` 只关连接，reader 的 Command/Reader 仍留在全局表里，
     * 直到脚本显式 CLOSE 或 `CloseAll`（[close]）。
     */
    fun disconnect(name: String) {
        synchronized(boundaryLock) { connections.remove(name.lowercase())?.close() }
    }

    /** 按名取连接；不存在抛 CodeEE（`SQL_EXECUTE_*` 族的「未连接」口径）。 */
    fun require(name: String, fn: String): SqlConnection =
        synchronized(boundaryLock) {
            connections[name.lowercase()]
                ?: throw CodeEE("函数 $fn：数据库连接 \"$name\" 不存在（先执行 SQL_CONNECT \"$name\"）")
        }

    /**
     * 按名取连接；不存在返回 null（不抛错）。
     *
     * 唯一消费者是 `SQL_IMPORT_MAP_XML`：spec 明文「dbName 未连接返回 0（不抛错）」，
     * 与 `SQL_EXECUTE_*` 族的「未连接抛 CodeEE」不同轴。
     */
    fun find(name: String): SqlConnection? = synchronized(boundaryLock) { connections[name.lowercase()] }

    /**
     * 执行一条非查询语句并返回受影响行数。
     *
     * - `columnCount > 0`（误把 SELECT 交给 NONQUERY）：执行一步后返回 -1
     *   （Microsoft.Data.Sqlite 的 `ExecuteNonQuery` 口径）；
     * - 否则先读 [SqlConnection.totalChanges]，`step` 到结束再读一次：
     *   只有读数**增长**时才取 [SqlConnection.lastChanges]。DDL 不改变 `total_changes`，
     *   于是返回 0，不会把上一条 DML 的 `sqlite3_changes` 串进来。
     */
    fun executeNonQuery(
        db: SqlConnection,
        sql: String,
        params: List<SqlBind> = emptyList(),
    ): Long = synchronized(boundaryLock) {
        db.prepare(sql).use { statement ->
            bindAll(statement, params)
            if (statement.columnCount > 0) {
                statement.step()
                SELECT_NONQUERY_RESULT
            } else {
                val before = db.totalChanges()
                var hasNext = statement.step()
                while (hasNext) {
                    hasNext = statement.step()
                }
                val after = db.totalChanges()
                if (after > before) db.lastChanges() else 0L
            }
        }
    }

    /**
     * 执行一条单值查询：`step` 一行，无行返回 null；首列按 [SqlColumnType] 映射成 [SqlValue]。
     *
     * `FLOAT` 进 [SqlValue.VFloat]；`BLOB` 本波用驱动文本折进 [SqlValue.VText]，等后续
     * blob 波再扩类型（契约 §2 的显式口径）。
     */
    fun executeScalar(
        db: SqlConnection,
        sql: String,
        params: List<SqlBind> = emptyList(),
    ): SqlValue? = synchronized(boundaryLock) {
        db.prepare(sql).use { statement ->
            bindAll(statement, params)
            if (statement.step()) readColumn(statement, 0) else null
        }
    }

    /**
     * 准备一条 reader 语句并登记句柄；句柄从 1 递增，关闭后不复用。
     *
     * 这里**不**推进第一行：`SQL_READER_READ` 才调 [readerRead]，与 fork 的
     * `SQLiteDataReader.Read()` 逐次推进口径一致。
     */
    fun openReader(
        db: SqlConnection,
        sql: String,
        params: List<SqlBind> = emptyList(),
    ): Int {
        synchronized(boundaryLock) {
            val statement = db.prepare(sql)
            val bindFailure = runCatching { bindAll(statement, params) }.exceptionOrNull()
            if (bindFailure != null) {
                runCatching { statement.close() }
                throw bindFailure
            }
            val handle = nextHandle
            nextHandle += 1
            readers[handle] = Reader(statement)
            return handle.toInt()
        }
    }

    /** 推进 reader 一行；句柄不存在/已关闭返回 false（`SQL_READER_READ` 的 0 口径）。 */
    fun readerRead(handle: Long): Boolean {
        synchronized(boundaryLock) {
            val reader = readers[handle] ?: return false
            val hasRow = reader.statement.step()
            reader.hasRow = hasRow
            return hasRow
        }
    }

    /**
     * 读当前行的第 [index] 列（0 基）。
     *
     * 无效句柄抛 CodeEE「无效的 Reader ID」（`GET_*` / `ISNULL` 的 spec 口径）；
     * 有效句柄尚未 READ 到有效行时也抛 CodeEE —— 与原版 `SQLiteDataReader` 在 Read
     * 之前取列即抛 `InvalidOperationException` 的形状一致，不静默返回 0/null。
     */
    fun readerValue(handle: Long, index: Int, fn: String): SqlValue {
        synchronized(boundaryLock) {
            val reader = readers[handle]
                ?: throw CodeEE("函数 $fn：无效的 Reader ID：$handle")
            if (!reader.hasRow) {
                throw CodeEE("函数 $fn：Reader $handle 尚未 READ 到有效行")
            }
            return readColumn(reader.statement, index)
        }
    }

    /** 关闭并移除 reader；句柄不存在时 no-op（`SQL_READER_CLOSE` 重复关闭不中断脚本）。 */
    fun closeReader(handle: Long) {
        synchronized(boundaryLock) {
            val reader = readers.remove(handle) ?: return
            reader.statement.close()
        }
    }

    /**
     * 关闭全部 reader，再关闭全部连接；幂等。
     *
     * 单个 close 抛异常不阻断其余关闭：所有对象都尝试关完后，抛**第一个**失败
     * （`close()` 的契约：异常按首个失败抛，但其余仍尝试关闭）。
     *
     * 取 [boundaryLock]：等到在途语句收尾（语句边界）才动手，与 VM 侧使用面构成确定序
     * ——本等待不等于「等 VM 线程退出」（合法长链是纯计算形态，从不持该锁）。
     */
    override fun close() {
        synchronized(boundaryLock) {
            if (closed) return
            closed = true

            var firstFailure: Throwable? = null
            val openReaders = readers.values.toList()
            readers.clear()
            for (reader in openReaders) {
                val failure = runCatching { reader.statement.close() }.exceptionOrNull()
                if (firstFailure == null) firstFailure = failure
            }

            val openConnections = connections.values.toList()
            connections.clear()
            for (connection in openConnections) {
                val failure = runCatching { connection.close() }.exceptionOrNull()
                if (firstFailure == null) firstFailure = failure
            }

            val firstThrown = firstFailure
            if (firstThrown != null) throw firstThrown
        }
    }

    private fun bindAll(statement: SqlStatement, params: List<SqlBind>) {
        for (i in params.indices) {
            statement.bind(i + 1, params[i])
        }
    }

    /** 首列/单元格按存储类映射；NULL 优先于列类型判读（SQLite 的 NULL 无类型）。 */
    private fun readColumn(statement: SqlStatement, index: Int): SqlValue {
        if (statement.isNull(index)) return SqlValue.VNull
        return when (statement.columnType(index)) {
            SqlColumnType.INTEGER -> SqlValue.VInt(statement.getLong(index))
            SqlColumnType.TEXT -> SqlValue.VText(statement.getText(index))
            SqlColumnType.FLOAT -> SqlValue.VFloat(statement.getDouble(index))
            SqlColumnType.BLOB -> SqlValue.VText(statement.getText(index))
            SqlColumnType.NULL -> SqlValue.VNull
        }
    }

    private companion object {
        /** 无连接串时开的库（契约 §2：driver.open(":memory:")）。 */
        const val MEMORY_DATABASE = ":memory:"
    }
}
