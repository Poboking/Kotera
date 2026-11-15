package com.eratw.port.vmdbg

import com.eratw.port.core.sql.SqlColumnType
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlNativeException
import com.eratw.port.core.sql.SqlStatement
import java.io.PrintStream

/**
 * `sql <连接串> <语句> [--max N]`：在 vmdbg 的固定沙箱上读/写真实 SQLite 库。
 *
 * 这是 `--sandbox` 的配套读回面：`call` 只报 ERB 执行状态，无法回答「二次会话后
 * GRAPH_DISTANCE 到底有多少行、MAX(slot) 是多少」。本命令**不装配 GameSession**，
 * 但库路径与写根和 [Assembler] 完全同源（[Assembler.resolvePaths] + [DesktopSqliteHost]），
 * 所以读到的就是 `call` 刚写出的那个库，不存在第二套路径策略。
 *
 * 连接串原样交给 `AndroidXSqliteDriver.open`（与 `SQL_CONNECT "Data Source=..."` 同一条
 * 适配器入口）：seed 首次拷种、出根拒绝、`:memory:` 直通都在适配器内，本命令不重复实现。
 */
object CmdSql {

    private const val DEFAULT_MAX_ROWS = 100L

    private const val EXIT_SQL_ERROR = 3

    fun run(a: Args, out: PrintStream): Int {
        val dsn = a.positional.getOrNull(1)
        val statement = a.positional.getOrNull(2)
        if (dsn == null || statement == null) {
            throw CliError("用法: sql <连接串> <语句> [--max N]")
        }
        val maxRows = a.longOr("--max", DEFAULT_MAX_ROWS)
        if (maxRows <= 0) throw CliError("--max 必须为正整数")

        val ctx = Assembler.resolvePaths(a)
        val driver = DesktopSqliteHost.create(ctx.gameRoot, ctx.sqlDir)
        return try {
            driver.open(dsn).use { connection -> execute(connection, statement, maxRows, out) }
            0
        } catch (e: SqlNativeException) {
            out.println("status=ERROR")
            out.println("message=${e.message}")
            EXIT_SQL_ERROR
        } catch (e: IllegalArgumentException) {
            // 路径布局拒绝（绝对路径 / .. 出根）是调用方的用法错误，不是 SQLite 执行错误。
            out.println("status=ERROR")
            out.println("message=连接串被适配器拒绝: $dsn（${e.message}）")
            EXIT_SQL_ERROR
        }
    }

    private fun execute(connection: SqlConnection, statement: String, maxRows: Long, out: PrintStream) {
        val before = connection.totalChanges()
        connection.prepare(statement).use { st ->
            if (st.columnCount == 0) {
                printNonQuery(connection, st, before, out)
            } else {
                printRows(st, maxRows, out)
            }
        }
    }

    private fun printNonQuery(
        connection: SqlConnection,
        st: SqlStatement,
        before: Long,
        out: PrintStream,
    ) {
        var steps = 0L
        while (st.step()) steps++
        out.println("status=PASS")
        out.println("columns=0")
        out.println("rows=$steps")
        out.println("changes=${connection.totalChanges() - before}")
    }

    private fun printRows(st: SqlStatement, maxRows: Long, out: PrintStream) {
        val columns = st.columnCount
        out.println("status=PASS")
        out.println("columns=" + (0 until columns).joinToString(",") { st.columnName(it) })
        var rows = 0
        while (rows < maxRows && st.step()) {
            out.println("row[$rows]=" + values(st, columns).joinToString("|"))
            rows++
        }
        val truncated = rows.toLong() >= maxRows && st.step()
        out.println("rows=$rows")
        if (truncated) out.println("truncated=true")
    }

    /** 一行各列的可读值；BLOB 只报长度（agent 台账不搬二进制）。 */
    private fun values(st: SqlStatement, columns: Int): List<String> =
        (0 until columns).map { i ->
            when (st.columnType(i)) {
                SqlColumnType.INTEGER -> st.getLong(i).toString()
                SqlColumnType.FLOAT -> st.getDouble(i).toString()
                SqlColumnType.TEXT -> st.getText(i)
                SqlColumnType.BLOB -> "<blob ${st.getBlob(i).size}B>"
                SqlColumnType.NULL -> "NULL"
            }
        }
}
