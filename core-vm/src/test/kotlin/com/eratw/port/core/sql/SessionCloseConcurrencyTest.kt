package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C2 修复批（stab-c，组长单点授权）：[SqlSession] 的**语句边界互斥**判据 —— 宿主 close
 * （UI 线程经 `GameSession.close` 进来）与 VM 语句执行在此构成确定序：close 让到在途语句
 * 边界，不再与语句执行交错。修复前 close 与语句执行并发读写两张表与语句池，是无锁结构
 * 竞态（UI 线程硬挂形状）；原版单线程无对应物（`MainWindow.cs:1085-1087` 脚本运行期窗口
 * 直接拒绝操作），判据自立 = 移植自有资源面不得比单线程原版多出 UB。
 *
 * 反向证明：把 [SqlSession] 各入口与 [SqlSession.close] 的 `boundaryLock` synchronized
 * 拆掉 ⇒ [close 让到在途语句边界_先收语句再返回] 红（close 不等语句收尾即返回，先后序
 * 翻转 + UI 线程提前存活断言失败），[并发 close 与 reader 循环交错_无异常逃逸且资源全收]
 * 的界断言失去互斥依据。
 *
 * 全部用自写 fake 驱动（与 `SqlSessionPortTest` 同口径）：不依赖 Android、AndroidX、
 * 真实 SQLite 文件；fake 语句的 step 可挂闸模拟「在途长语句」。
 */
class SessionCloseConcurrencyTest {

    @Test
    fun `close 让到在途语句边界_先收语句再返回`() {
        val session = SqlSession(BoundaryFakeDriver())
        session.connect("DB", null)
        val db = session.require("DB", "test") as BoundaryFakeConnection

        // 在途语句：第一步挂在 gate 上（模拟语句执行中），放行后记录收尾时刻再结束。
        val statementEntered = CountDownLatch(1)
        val statementGate = CountDownLatch(1)
        val statementFinishedAt = AtomicLong(0)
        db.onPrepare = {
            BoundaryFakeStatement().apply {
                onStep = {
                    statementEntered.countDown()
                    statementGate.await()
                    statementFinishedAt.set(System.nanoTime())
                }
            }
        }

        val vmError = AtomicReference<Throwable?>()
        val vm = Thread {
            runCatching { session.executeNonQuery(db, "SELECT 1") }
                .onFailure { vmError.set(it) }
        }
        vm.start()
        assertTrue(statementEntered.await(JOIN_BOUND_MS, TimeUnit.MILLISECONDS), "在途语句未进入执行")

        val closeReturnedAt = AtomicLong(0)
        val closeError = AtomicReference<Throwable?>()
        val ui = Thread {
            runCatching { session.close() }.onFailure { closeError.set(it) }
            closeReturnedAt.set(System.nanoTime())
        }
        ui.start()
        Thread.sleep(STATEMENT_BLOCK_PROBE_MS)
        assertTrue(ui.isAlive, "close 在在途语句收尾前就返回了（语句边界互斥失效）")

        statementGate.countDown()
        ui.join(JOIN_BOUND_MS)
        vm.join(JOIN_BOUND_MS)
        assertFalse(ui.isAlive, "close 未在语句收尾后返回")
        assertFalse(vm.isAlive, "VM 线程未在语句收尾后结束")
        assertNull(vmError.get(), "VM 语句执行不应抛异常")
        assertNull(closeError.get(), "close 不应抛异常")
        assertTrue(
            statementFinishedAt.get() < closeReturnedAt.get(),
            "close 先于语句收尾返回（语句边界互斥失效）",
        )
        assertEquals(1, db.closeCalls, "连接只关一次")
        val statement = db.statements.single()
        assertEquals(1, statement.closeCalls, "语句只关一次（use 归还）")
    }

    @Test
    fun `并发 close 与 reader 循环交错_无异常逃逸且资源全收`() {
        val session = SqlSession(BoundaryFakeDriver())
        session.connect("DB", null)
        val db = session.require("DB", "test") as BoundaryFakeConnection
        db.onPrepare = { BoundaryFakeStatement(rows = READER_ROWS_TOTAL) }
        val handle = session.openReader(db, "SELECT v FROM CSV_MAP")

        val rowsRead = AtomicLong(0)
        val vmError = AtomicReference<Throwable?>()
        val vm = Thread {
            runCatching {
                // 逐行推进（每行一次边界取锁）；close 并发进来后 reader 表清空，
                // 下一行 readerRead 返回 false，循环确定性收口（语句边界让出语义）。
                while (session.readerRead(handle.toLong())) {
                    rowsRead.incrementAndGet()
                }
            }.onFailure { vmError.set(it) }
        }
        vm.start()
        waitUntil(JOIN_BOUND_MS, "reader 循环未开始读") { rowsRead.get() > 0 }

        val closeStartedAt = System.nanoTime()
        session.close()
        val closeReturnedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedAt)

        vm.join(JOIN_BOUND_MS)
        assertFalse(vm.isAlive, "VM 线程未在 close 后收口")
        assertNull(vmError.get(), "并发 close 期间 VM 循环不应抛异常")
        assertTrue(
            rowsRead.get() < READER_ROWS_TOTAL,
            "close 没有打断 reader 循环（读满全表 = 交错判据失去观察点）",
        )
        assertTrue(closeReturnedMs < JOIN_BOUND_MS, "close 等待超过上界（语句边界让出不应久等）")
        assertEquals(1, db.closeCalls, "连接只关一次")
        assertEquals(1, db.statements.single().closeCalls, "reader 语句只关一次")

        // close 幂等：并发双跑（QUIT 与 UI close 同瞬）下二次调用 no-op。
        session.close()
        assertEquals(1, db.closeCalls, "二次 close 后连接仍只关一次")
    }

    @Test
    fun `close 之后 VM 再撞 SQL 是确定序 CodeEE 而非竞态`() {
        val session = SqlSession(BoundaryFakeDriver())
        session.connect("DB", null)
        session.close()

        // 表已清空：VM 侧下一发走既有「未连接」口径上浮（被宿主 closed 首查静默），
        // 与 close 不再交错 —— 这是 Kotlin 层确定异常，不是半关资源上的 UB。
        val failure = kotlin.test.assertFailsWith<CodeEE> {
            session.require("DB", "SQL_EXECUTE_NONQUERY")
        }
        assertEquals(
            "函数 SQL_EXECUTE_NONQUERY：数据库连接 \"DB\" 不存在（先执行 SQL_CONNECT \"DB\"）",
            failure.message,
        )
        assertFalse(session.readerRead(1L), "close 后句柄表已清：readerRead 返回 false")
    }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        assertTrue(cond(), "$what：${timeoutMs}ms 内未成立")
    }

    /** 语句级等待与线程 join 的共同上界（判据的「不无限等」口径，毫秒）。 */
    private companion object {
        const val JOIN_BOUND_MS = 5_000L

        /** 留给「close 应当仍堵在语句边界」的观察窗口（毫秒）。 */
        const val STATEMENT_BLOCK_PROBE_MS = 100L

        /** reader 循环的单次轮询间隔（毫秒）。 */
        const val PROBE_INTERVAL_MS = 5L

        /** 交错观察用 reader 总行数（远大于轮询+close 落地前的可读行数）。 */
        const val READER_ROWS_TOTAL = 500_000
    }
}

// ---------------------------------------------------------------------------
// fakes（与 SqlSessionPortTest 同形状、本文件私有：只覆盖本判据用到的面）
// ---------------------------------------------------------------------------

private class BoundaryFakeDriver : SqlDriver {
    override fun open(connectionString: String): SqlConnection = BoundaryFakeConnection()
}

private class BoundaryFakeConnection : SqlConnection {
    var closeCalls = 0
    var onPrepare: (() -> BoundaryFakeStatement)? = null
    val statements = mutableListOf<BoundaryFakeStatement>()

    override fun prepare(sql: String): SqlStatement =
        (onPrepare?.invoke() ?: BoundaryFakeStatement()).also { statements += it }

    override fun totalChanges(): Long = 0L
    override fun lastChanges(): Long = 0L
    override fun close() {
        closeCalls += 1
    }
}

/** 语句 fake：单步结束（rows=0）或指定行数后结束；step 可挂回调模拟在途长语句。 */
private class BoundaryFakeStatement(
    private val rows: Int = 0,
) : SqlStatement {
    override val columnCount: Int = 0
    var closeCalls = 0
    var onStep: (() -> Unit)? = null

    private var position = -1

    override fun bind(index: Int, value: SqlBind) = Unit

    override fun step(): Boolean {
        onStep?.invoke()
        position += 1
        return position < rows
    }

    override fun reset() {
        position = -1
    }

    override fun clearBindings() = Unit
    override fun columnName(index: Int): String = "c$index"
    override fun columnType(index: Int): SqlColumnType = SqlColumnType.NULL
    override fun isNull(index: Int): Boolean = true
    override fun getLong(index: Int): Long = 0L
    override fun getDouble(index: Int): Double = 0.0
    override fun getText(index: Int): String = ""
    override fun getBlob(index: Int): ByteArray = ByteArray(0)

    override fun close() {
        closeCalls += 1
    }
}
