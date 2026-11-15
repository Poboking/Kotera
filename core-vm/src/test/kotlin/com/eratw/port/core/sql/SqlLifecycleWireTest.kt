package com.eratw.port.core.sql

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.io.TempDir

/**
 * SQLite 驱动注入链（L1，`CONTRACT-SQLITE-MIGRATION` §1/§4）的定向判据：
 *
 * 1. [RootedBuiltinHost] 透出 `sqlDriver` 注入值；
 * 2. [ConsoleBuiltinHost] **自己的注入压过** `BuiltinHost by delegate`，未注入时回落 delegate；
 * 3. [GameSession] 把同一实例同时喂给 rooted 与 Console（Console 的**自有字段**也非空，
 *    证明不是靠 delegate 回落碰巧读到的），缺省仍是 null（未接线 = `SQL_*` 抛 CodeEE）；
 * 4. `GameSession.close` 关闭 `store.aux` 的 SQL 槽（契约 §2 槽名 `"sql"`；
 *    本树 S0 的 `SqlSession` 尚未合流，故用 `AutoCloseable` 桩验「关闭动作真的发生」，
 *    真实 `SqlSession.close()` 由 S0 合流后的同一代码路径覆盖）。
 *
 * 这里不 open 真实连接：驱动只作身份读数（[FakeDriver.open] 一旦被调用就炸），
 * 生命周期验证留给 S0 的 `SqlSessionPortTest` 与后续 M1。
 */
class SqlLifecycleWireTest {

    /** 只作身份读数的驱动：本用例不应触发 open。 */
    private class FakeDriver(private val label: String) : SqlDriver {
        override fun open(connectionString: String): SqlConnection =
            error("FakeDriver($label) 不应被 open：连接串=$connectionString")
    }

    /** 记录 close 次数的 SQL 会话桩（替代 S0 未合流的 `SqlSession`）。 */
    private class CountingCloseable : AutoCloseable {
        var closes: Int = 0
            private set

        override fun close() {
            closes += 1
        }
    }

    @Test
    fun `RootedBuiltinHost 透出注入的 sqlDriver`(@TempDir root: Path) {
        val driver = FakeDriver("rooted")
        val host = RootedBuiltinHost(root, sqlDriver = driver)

        assertSame(driver, host.sqlDriver, "RootedBuiltinHost 必须原样透出注入实例")
    }

    @Test
    fun `ConsoleBuiltinHost 自己的注入压过 delegate 并回落到 delegate`(@TempDir root: Path) {
        val rootedDriver = FakeDriver("rooted")
        val consoleDriver = FakeDriver("console")
        val rooted = RootedBuiltinHost(root, sqlDriver = rootedDriver)

        val own = ConsoleBuiltinHost(root, null, rooted, sqlDriver = consoleDriver)
        assertSame(consoleDriver, own.sqlDriver, "Console 自己的注入必须压过 BuiltinHost by delegate 的转发")

        val fallback = ConsoleBuiltinHost(root, null, rooted)
        assertSame(rootedDriver, fallback.sqlDriver, "未注入时必须回落到 delegate.sqlDriver")

        assertNull(ConsoleBuiltinHost(root).sqlDriver, "两层都没接线 ⇒ null，SQL_* 抛 CodeEE")
        assertNull(object : BuiltinHost {}.sqlDriver, "BuiltinHost 出厂缺省 = 未接线")
    }

    @Test
    fun `GameSession close 关闭 sql 槽的 AutoCloseable`(@TempDir root: Path) {
        val session = minimalSession(root, null)
        val spy = CountingCloseable()
        // 槽名与 S0 的 SqlSession.SQL_AUX_SLOT 同串（契约 §2 定值 "sql"）。
        session.store.aux.slot("sql") { spy }

        session.close()

        assertEquals(1, spy.closes, "GameSession.close 必须把 sql 槽的 SQL 会话关掉")
    }

    @Test
    fun `GameSession 把同一驱动喂给 Console 自有注入位 缺省仍为 null`(@TempDir root: Path) {
        val driver = FakeDriver("session")
        val wired = minimalSession(root, driver)
        try {
            assertSame(driver, wired.builtinHost.sqlDriver, "会话装配后 ConsoleBuiltinHost 必须读到驱动")
            assertSame(
                driver,
                consoleInjectedDriver(wired.builtinHost),
                "驱动必须落在 ConsoleBuiltinHost 自己的注入位（不是只靠 rooted 回落）",
            )
        } finally {
            wired.close()
        }

        val headless = minimalSession(root, null)
        try {
            assertNull(headless.builtinHost.sqlDriver, "未接线会话保持不变：sqlDriver = null")
            assertNull(consoleInjectedDriver(headless.builtinHost))
        } finally {
            headless.close()
        }
    }

    /**
     * 读 [ConsoleBuiltinHost] 的私有注入字段。`sqlDriver` getter 会回落 delegate，光看 getter
     * 分不清「Console 自己拿到」还是「rooted 拿到、Console 回落」，所以这里必须点查字段。
     */
    private fun consoleInjectedDriver(host: ConsoleBuiltinHost): SqlDriver? {
        val field = ConsoleBuiltinHost::class.java.getDeclaredField("injectedSqlDriver")
        field.isAccessible = true
        return field.get(host) as SqlDriver?
    }

    /** 最小真装配：`@SYSTEM_TITLE / PRINTL WIRE`，只依赖 core 自己的解析器 + 编译器。 */
    private fun minimalSession(root: Path, driver: SqlDriver?): GameSession {
        val parser = LineParser(SymbolTable())
        val statements = listOf(
            LogicalLine(LineKind.FUNCTION_LABEL, "TEST.ERB", 1, "@SYSTEM_TITLE"),
            LogicalLine(LineKind.CODE, "TEST.ERB", 2, "PRINTL WIRE"),
        ).mapNotNull { parser.parseLogicalLine(it) }
        val compiler = ErbProgramCompiler()
        val fn = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), statements, setOf("SYSTEM_TITLE"))
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fn.labels),
            functions = listOf(
                FuncMeta(
                    index = fn.index,
                    key = "SYSTEM_TITLE",
                    name = "SYSTEM_TITLE",
                    defIndex = 0,
                    position = "TEST.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TEST.ERB"),
        )
        return GameSession(
            gameRoot = root,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 20261115L,
            sqlDriver = driver,
        )
    }
}
