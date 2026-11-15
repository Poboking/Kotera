@file:Suppress("TooGenericExceptionCaught")
package com.eratw.port.sqlite

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlColumnType
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlDriver
import com.eratw.port.core.sql.SqlNativeException
import com.eratw.port.core.sql.SqlStatement
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `SqlDriver` 的 AndroidX SQLite 适配器：core 只认识 [SqlDriver] 端口，平台侧把
 * bundled（Android / JVM）或 framework 驱动实例注入到这里。
 *
 * 路径与沙箱策略不在本类重复实现：[SqlitePathLayout] 决定连接串落到 `:memory:` 还是
 * 写根目录下的稳定 target；本类只负责把随包 seed 变成可写副本：
 * - target 不存在：复制 seed（有 seed 时）；
 * - [SqliteOpenTarget.File.mutable]（`plugins/qol_data.db`）：target 已存在就保留玩家写入，
 *   即使随包 seed 更新也不回滚；
 * - 其余 seed 库：seed 比 target 新时原子刷新，否则保留 target 里的 ERB 写入。
 *
 * 刷新先写同目录临时文件再 atomic move，复制/替换失败只留旧库，不会出现半截 target。
 * **target 路径**才交给 AndroidX（原 `Data Source=` 串绝不透传）。
 *
 * 原生驱动的异常（SQLite 错误、文件 IO）统一折成 [SqlNativeException]；连接串本身的
 * 合法性（空串除外、`..` 或绝对路径出根）由 [SqlitePathLayout] 抛 `IllegalArgumentException`，
 * 本类不改写那条契约。
 *
 * 线程口径与 [SqlDriver] 相同：连接/语句只在创建它的 VM 线程使用。
 */
class AndroidXSqliteDriver(
    private val driver: SQLiteDriver,
    seedRoot: Path,
    writeRoot: Path,
) : SqlDriver {

    private val layout = SqlitePathLayout(seedRoot, writeRoot)

    override fun open(connectionString: String): SqlConnection {
        return when (val target = layout.resolve(connectionString)) {
            SqliteOpenTarget.Memory -> openNative(":memory:")
            is SqliteOpenTarget.File -> {
                prepareTarget(target)
                openNative(target.target.toString())
            }
        }
    }

    /**
     * 按 seed 策略准备 target：
     * - 父目录先建好；
     * - target 不存在且确有 seed：复制；
     * - target 已存在：mutable 库直接保留；非 mutable 库只在 seed 比 target 新时刷新；
     * - seed 复制先落同目录临时文件再 atomic move，失败清理临时文件、旧 target 原样保留。
     */
    private fun prepareTarget(file: SqliteOpenTarget.File) {
        val target = file.target
        val seed = file.seed
        try {
            target.parent?.let { Files.createDirectories(it) }
            if (seed == null || !Files.isRegularFile(seed)) return
            if (Files.exists(target)) {
                if (file.mutable || !seedIsNewer(seed, target)) return
            }
            copySeedAtomically(seed, target)
        } catch (e: Exception) {
            throw sqliteFailure("准备 ${target.fileName} 失败", e)
        }
    }

    private fun seedIsNewer(seed: Path, target: Path): Boolean =
        Files.getLastModifiedTime(seed) > Files.getLastModifiedTime(target)

    /**
     * 复制 seed 到 target：先复制进同目录临时文件，再 atomic move 覆盖 target。
     *
     * 复制阶段失败时旧 target 一个字节都没动；atomic move 失败时旧 target 也保持原样，
     * 临时文件在 finally 里清掉。临时文件继承 seed 的修改时间，刷新后 target 与 seed 同龄，
     * 下次打开不会因时间戳比较再刷一遍（也避免 seed 时间戳在未来时反复重刷）。
     */
    private fun copySeedAtomically(seed: Path, target: Path) {
        val parent = requireNotNull(target.parent) { "SQLite target 没有父目录：$target" }
        val seedModified = Files.getLastModifiedTime(seed)
        val temp = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
        try {
            Files.copy(seed, temp, StandardCopyOption.REPLACE_EXISTING)
            runCatching { Files.setLastModifiedTime(temp, seedModified) }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (ignored: AtomicMoveNotSupportedException) {
                // 文件系统不支持原子替换时退到同目录 rename 级替换，仍不经过半截 target。
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun openNative(fileName: String): SqlConnection {
        return try {
            Connection(driver.open(fileName))
        } catch (e: Exception) {
            throw sqliteFailure("打开 $fileName 失败", e)
        }
    }

    /** 把平台异常折成 [SqlNativeException]；已经归一过的不再套一层。 */
    private fun sqliteFailure(action: String, cause: Exception): SqlNativeException =
        cause as? SqlNativeException ?: SqlNativeException("SQLite $action：${cause.message}", cause)

    private class Connection(private val delegate: SQLiteConnection) : SqlConnection {
        private var closed = false

        /** 空闲语句池：同 SQL 文本的 delegate 复用，上限 [MAX_POOLED_STATEMENTS] 条，LRU 逐出。 */
        private val pool = StatementPool(MAX_POOLED_STATEMENTS)

        /** 计数探针：各一条懒创建、`reset()` 复用的语句，省掉每次 DML 的两次 prepare。 */
        private var totalChangesProbe: SQLiteStatement? = null
        private var lastChangesProbe: SQLiteStatement? = null

        override fun prepare(sql: String): SqlStatement {
            ensureOpen()
            return Statement(sql, acquire(sql), this)
        }

        /** 池命中则复用（再 reset 一次防御半路停下的语句），否则真 prepare；错误折成既有文案。 */
        private fun acquire(sql: String): SQLiteStatement {
            val pooled = pool.take(sql)
            if (pooled != null) {
                if (runCatching { pooled.reset() }.isSuccess) return pooled
                runCatching { pooled.close() }
            }
            return try {
                delegate.prepare(sql)
            } catch (e: Exception) {
                throw SqlNativeException("SQLite 准备语句失败：${e.message}", e)
            }
        }

        /** [Statement.close] 的归还路径：delegate 只在连接关闭或池满逐出时真关。 */
        fun recycle(sql: String, statement: SQLiteStatement) {
            if (closed) {
                runCatching { statement.close() }
                return
            }
            val reset = runCatching {
                statement.reset()
                statement.clearBindings()
            }
            if (reset.isFailure) {
                runCatching { statement.close() }
                return
            }
            pool.put(sql, statement)
        }

        override fun totalChanges(): Long = queryProbe(
            cached = totalChangesProbe,
            sql = TOTAL_CHANGES_SQL,
            store = { totalChangesProbe = it },
            discard = { totalChangesProbe = null },
        )

        override fun lastChanges(): Long = queryProbe(
            cached = lastChangesProbe,
            sql = LAST_CHANGES_SQL,
            store = { lastChangesProbe = it },
            discard = { lastChangesProbe = null },
        )

        /**
         * 复用探针语句取计数；语句出错（reset/step/get 抛）时关掉并从缓存摘除，下次重新 prepare，
         * 保证一次坏语句不会永久污染连接。
         */
        private fun queryProbe(
            cached: SQLiteStatement?,
            sql: String,
            store: (SQLiteStatement) -> Unit,
            discard: () -> Unit,
        ): Long {
            ensureOpen()
            val statement = openProbe(cached, sql, store)
            return try {
                statement.reset()
                if (!statement.step()) {
                    throw SqlNativeException("SQLite 查询无结果：$sql")
                }
                val changes = statement.getLong(0)
                // 读完立即 reset 释放读游标：探针是常驻语句，留着会让 sqlite3_stmt_busy 为真，
                // 从而在同一连接上执行 VACUUM 时报 cannot VACUUM - SQL statements in progress。
                runCatching { statement.reset() }
                changes
            } catch (e: Exception) {
                discard()
                runCatching { statement.close() }
                throw e as? SqlNativeException
                    ?: SqlNativeException("SQLite 读取变更计数失败：${e.message}", e)
            }
        }

        /** 缓存命中直接返回，否则真 prepare 并存入缓存；错误折成既有文案。 */
        private fun openProbe(
            cached: SQLiteStatement?,
            sql: String,
            store: (SQLiteStatement) -> Unit,
        ): SQLiteStatement {
            if (cached != null) return cached
            return try {
                delegate.prepare(sql).also(store)
            } catch (e: Exception) {
                throw SqlNativeException("SQLite 准备语句失败：${e.message}", e)
            }
        }

        private fun ensureOpen() {
            if (closed) throw SqlNativeException("SQLite 连接已关闭")
        }

        /** 先关池内全部 delegate 与两条计数探针，再关连接；任一关闭失败折成既有文案。 */
        override fun close() {
            if (closed) return
            closed = true
            var failure: Exception? = null
            val open = listOfNotNull(totalChangesProbe, lastChangesProbe)
            totalChangesProbe = null
            lastChangesProbe = null
            for (statement in open + pool.drain()) {
                try {
                    statement.close()
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
            }
            try {
                delegate.close()
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
            failure?.let {
                throw it as? SqlNativeException
                    ?: SqlNativeException("SQLite 关闭连接失败：${it.message}", it)
            }
        }
    }

    /**
     * 语句包装：`closed` 闸语义与原先逐字一致（关闭后任何入口仍抛 `SQLite 语句已关闭`，
     * cause 为 null）；区别只在 [close] 不真关 delegate，而是交给连接归还语句池复用。
     */
    @Suppress("TooManyFunctions")
    private class Statement(
        private val sql: String,
        private val delegate: SQLiteStatement,
        private val owner: Connection,
    ) : SqlStatement {
        private var closed = false

        override val columnCount: Int
            get() = guard("读取列数") { delegate.getColumnCount() }

        override fun bind(index: Int, value: SqlBind) {
            guard("绑定参数 $index") {
                when (value) {
                    is SqlBind.Integer -> delegate.bindLong(index, value.value)
                    is SqlBind.Float -> delegate.bindDouble(index, value.value)
                    is SqlBind.Text -> delegate.bindText(index, value.value)
                    is SqlBind.Blob -> delegate.bindBlob(index, value.value)
                    SqlBind.Null -> delegate.bindNull(index)
                }
            }
        }

        override fun step(): Boolean = guard("执行语句") { delegate.step() }

        override fun reset() {
            guard("重置语句") { delegate.reset() }
        }

        override fun clearBindings() {
            guard("清空绑定") { delegate.clearBindings() }
        }

        override fun columnName(index: Int): String =
            guard("读取列名 $index") { delegate.getColumnName(index) }

        override fun columnType(index: Int): SqlColumnType =
            guard("读取列类型 $index") { toColumnType(delegate.getColumnType(index)) }

        override fun isNull(index: Int): Boolean =
            guard("判断列 $index 是否为空") { delegate.isNull(index) }

        override fun getLong(index: Int): Long =
            guard("读取列 $index 的整数") { delegate.getLong(index) }

        override fun getDouble(index: Int): Double =
            guard("读取列 $index 的浮点") { delegate.getDouble(index) }

        override fun getText(index: Int): String =
            guard("读取列 $index 的文本") { delegate.getText(index) }

        override fun getBlob(index: Int): ByteArray =
            guard("读取列 $index 的二进制") { delegate.getBlob(index) }

        override fun close() {
            if (closed) return
            closed = true
            owner.recycle(sql, delegate)
        }

        private fun <T> guard(action: String, block: () -> T): T {
            ensureOpen()
            return try {
                block()
            } catch (e: Exception) {
                throw e as? SqlNativeException
                    ?: SqlNativeException("SQLite $action 失败：${e.message}", e)
            }
        }

        private fun ensureOpen() {
            if (closed) throw SqlNativeException("SQLite 语句已关闭")
        }

        private fun toColumnType(raw: Int): SqlColumnType = when (raw) {
            SQLITE_DATA_INTEGER -> SqlColumnType.INTEGER
            SQLITE_DATA_FLOAT -> SqlColumnType.FLOAT
            SQLITE_DATA_TEXT -> SqlColumnType.TEXT
            SQLITE_DATA_BLOB -> SqlColumnType.BLOB
            SQLITE_DATA_NULL -> SqlColumnType.NULL
            else -> throw SqlNativeException("SQLite 未知列类型：$raw")
        }
    }

    /**
     * 单连接空闲语句池：key = SQL 文本，value = 空闲 delegate 队列（同一 SQL 可能同时有多条
     * 未归还的语句，池里一次只取一条，取走即离开池，两条并发包装永远不共享同一 delegate）。
     *
     * 上限 [maxSize] 条空闲 delegate；超出后关掉最久未访问 SQL 的一条。归还的 delegate 一定
     * 已 `reset()` + `clearBindings()`，与「每条 prepare 都是全新语句」的可观察语义一致
     * （schema 变更后由 SQLite 的 prepare-v2 自动重编，池不影响正确性）。
     */
    private class StatementPool(private val maxSize: Int) {
        private val idle = LinkedHashMap<String, ArrayDeque<SQLiteStatement>>(
            INITIAL_CAPACITY,
            LOAD_FACTOR,
            true,
        )
        private var size = 0

        fun take(sql: String): SQLiteStatement? {
            val queue = idle[sql] ?: return null
            val statement = queue.removeFirstOrNull()
            if (statement != null) {
                size--
                if (queue.isEmpty()) idle.remove(sql)
            } else {
                idle.remove(sql)
            }
            return statement
        }

        fun put(sql: String, statement: SQLiteStatement) {
            idle.getOrPut(sql) { ArrayDeque() }.addLast(statement)
            size++
            if (size > maxSize) evictOldest()
        }

        /** 连接关闭用：摘走全部空闲 delegate（调用方负责真关）。 */
        fun drain(): List<SQLiteStatement> {
            val statements = idle.values.flatten()
            idle.clear()
            size = 0
            return statements
        }

        private fun evictOldest() {
            val iterator = idle.entries.iterator()
            while (iterator.hasNext()) {
                val queue = iterator.next().value
                val victim = queue.removeFirstOrNull()
                if (victim == null) {
                    iterator.remove()
                    continue
                }
                size--
                if (queue.isEmpty()) iterator.remove()
                runCatching { victim.close() }
                return
            }
        }

        private companion object {
            const val INITIAL_CAPACITY = 16
            const val LOAD_FACTOR = 0.75f
        }
    }

    private companion object {
        /** 单连接空闲语句上限；eraTW 实际并发 SQL 形状远低于此值，池满只发生在长尾扫描里。 */
        const val MAX_POOLED_STATEMENTS = 64
        const val TOTAL_CHANGES_SQL = "SELECT total_changes()"
        const val LAST_CHANGES_SQL = "SELECT changes()"
    }
}
