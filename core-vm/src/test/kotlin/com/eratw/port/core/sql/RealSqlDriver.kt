package com.eratw.port.core.sql

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

/**
 * P1 真实 SQLite 测试驱动：把 [SqlDriver] 端口接到 `androidx.sqlite:sqlite-bundled-jvm`
 * （SQLite 3.46.0，与 Android 侧 `sqlite-bundled` 同版本、同 native 实现）。
 *
 * 只用于 test 源集：core-vm 主源集仍零 AndroidX（`AndroidApiCompatTest` 只扫 main 字节码）。
 * 路径解析只做测试需要的最小形态：`Data Source=` / 裸路径按 [root] 落盘，`:memory:` 直通。
 * 生产路径策略不在本类复刻——那是 `sqlite-adapter` 的 `SqlitePathLayout` 的职责。
 */
internal class RealSqlDriver(private val root: Path) : SqlDriver {
    private val driver: SQLiteDriver = BundledSQLiteDriver()

    /** 每次 `open` 的原始连接串，供 `SQL_CONNECTION_OPEN` 路径断言用。 */
    val opened = mutableListOf<String>()

    override fun open(connectionString: String): SqlConnection {
        opened += connectionString
        val fileName = resolve(connectionString)
        return try {
            RealConnection(driver.open(fileName))
        } catch (e: Exception) {
            throw SqlNativeException("打开 $fileName 失败：${e.message}", e)
        }
    }

    private fun resolve(connectionString: String): String {
        val raw = if ('=' in connectionString) {
            connectionString.substringAfter('=').substringBefore(';').trim()
        } else {
            connectionString.trim()
        }
        if (raw.isEmpty() || raw.equals(":memory:", ignoreCase = true) ||
            raw.equals("file::memory:", ignoreCase = true)
        ) {
            return ":memory:"
        }
        return root.resolve(raw).normalize().toString()
    }

    private inner class RealConnection(private val delegate: SQLiteConnection) : SqlConnection {
        private var closed = false

        override fun prepare(sql: String): SqlStatement {
            ensureOpen()
            return try {
                RealStatement(delegate.prepare(sql))
            } catch (e: Exception) {
                throw SqlNativeException("准备语句失败：${e.message}", e)
            }
        }

        override fun totalChanges(): Long = scalarLong("SELECT total_changes()")

        override fun lastChanges(): Long = scalarLong("SELECT changes()")

        private fun scalarLong(sql: String): Long = try {
            prepare(sql).use { statement ->
                if (!statement.step()) throw SqlNativeException("查询无结果：$sql")
                statement.getLong(0)
            }
        } catch (e: SqlNativeException) {
            throw e
        } catch (e: Exception) {
            throw SqlNativeException("读取变更计数失败：${e.message}", e)
        }

        private fun ensureOpen() {
            if (closed) throw SqlNativeException("连接已关闭")
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
            } catch (e: Exception) {
                throw SqlNativeException("关闭连接失败：${e.message}", e)
            }
        }
    }

    private class RealStatement(private val delegate: SQLiteStatement) : SqlStatement {
        private var closed = false

        override val columnCount: Int
            get() = guard("读取列数") { delegate.getColumnCount() }

        override fun bind(index: Int, value: SqlBind) {
            guardUnit("绑定参数 $index") {
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

        override fun reset() = guardUnit("重置语句") { delegate.reset() }

        override fun clearBindings() = guardUnit("清空绑定") { delegate.clearBindings() }

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
            try {
                delegate.close()
            } catch (e: Exception) {
                throw SqlNativeException("关闭语句失败：${e.message}", e)
            }
        }

        private fun <T> guard(action: String, block: () -> T): T {
            if (closed) throw SqlNativeException("语句已关闭")
            return try {
                block()
            } catch (e: Exception) {
                throw e as? SqlNativeException ?: SqlNativeException("$action 失败：${e.message}", e)
            }
        }

        private fun guardUnit(action: String, block: () -> Unit) {
            guard(action) { block() }
        }

        private fun toColumnType(raw: Int): SqlColumnType = when (raw) {
            SQLITE_DATA_INTEGER -> SqlColumnType.INTEGER
            SQLITE_DATA_FLOAT -> SqlColumnType.FLOAT
            SQLITE_DATA_TEXT -> SqlColumnType.TEXT
            SQLITE_DATA_BLOB -> SqlColumnType.BLOB
            SQLITE_DATA_NULL -> SqlColumnType.NULL
            else -> throw SqlNativeException("未知列类型：$raw")
        }
    }
}
