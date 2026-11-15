package com.eratw.port.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlColumnType
import com.eratw.port.core.sql.SqlConnection
import com.eratw.port.core.sql.SqlNativeException
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

/**
 * P6「错误面」验收：真 bundled SQLite（3.46.0）经 [AndroidXSqliteDriver] 暴露给 core 的每一条
 * 失败路径，都必须是 [SqlNativeException]，且 message 里保留 SQLite 原文而不是被折成空结果或
 * 成功返回值。
 *
 * 覆盖：
 * - prepare 期语法错误（`SELECT FROM`）；
 * - 执行期缺表、UNIQUE 冲突、NOT NULL 冲突；
 * - `ALTER TABLE ... ADD COLUMN ... UNIQUE` 的 prepare 期拒绝（`Cannot add a UNIQUE column`）
 *   与表级 `UNIQUE(owner_no, slot)` 冲突（列级 UNIQUE 的另一半错误面，见 `479e9ffd9`）；
 * - 五种 [SqlBind]（Integer/Float/Text/Blob/Null）的绑定面：正常往返不误报，STRICT 表类型不符
 *   与越界参数号必须归一；
 * - prepare 后 close 再 step 的安全边界。
 *
 * 每个失败样本在最后统一打印（`SqlNativeException.message` = M1 包 `CodeEE` 前的玩家面原文），
 * 供 M1 核对包装文案；测试本身只断言类与关键词，不改写 adapter 文案。
 *
 * 内存库 + bundled 驱动，不依赖真实语料；一个连接内的表名互不冲突。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteErrorSurfaceTest {

    @TempDir
    lateinit var tmp: Path

    private val driver: AndroidXSqliteDriver by lazy {
        AndroidXSqliteDriver(BundledSQLiteDriver(), tmp.resolve("seed"), tmp.resolve("sandbox"))
    }

    private val samples = mutableListOf<ErrorSample>()

    private data class ErrorSample(
        val label: String,
        val playerMessage: String,
        val cause: String?,
    )

    private companion object {
        const val ANSWER = 42L
        const val SCORE = 1.25
        const val NON_INTEGER = 1.5
        const val TOLERANCE = 1e-9
        const val OWNER_NO = 7L
        const val SLOT = 3L
    }

    private fun withMemoryDb(block: (SqlConnection) -> Unit) {
        driver.open(":memory:").use(block)
    }

    /**
     * 断言失败已被归一成 [SqlNativeException] 并记录样本；[keyword] 非 null 时再核平台错误面：
     * message 含 SQLite 原文关键词、保留原始 cause、且顶层 message 逐字包含 cause.message
     * （原文可读，未被替换成别的文案）。[keyword] 为 null 用于 adapter 自己的 closed 闸。
     */
    private fun expectFailure(
        label: String,
        keyword: String? = null,
        block: () -> Unit,
    ): SqlNativeException {
        val failure = assertFailsWith<SqlNativeException>(label, block)
        val message = failure.message.orEmpty()
        assertTrue(message.isNotBlank(), "$label：SqlNativeException.message 不能为空")
        samples += ErrorSample(
            label = label,
            playerMessage = message,
            cause = failure.cause?.let { "${it::class.qualifiedName}: ${it.message}" },
        )
        if (keyword != null) {
            assertContains(message, keyword, ignoreCase = false, message = "$label：message 缺关键词 $keyword")
            val cause = assertNotNull(failure.cause, "$label：平台错误必须保留原始 cause")
            assertContains(cause::class.qualifiedName.orEmpty(), "SQLite", message = "$label：cause 不是 SQLite 异常")
            val causeMessage = assertNotNull(cause.message, "$label：原始 cause.message 不能为空")
            assertContains(message, causeMessage, message = "$label：顶层 message 未保留 SQLite 原文")
        }
        return failure
    }

    @Test
    fun `语法错误在 prepare 阶段归一为 SqlNativeException`() {
        withMemoryDb { db ->
            expectFailure("syntax-error", "syntax error") {
                db.prepare("SELECT FROM")
            }

            // 语法错误不能把连接带坏：同一连接上后续合法语句仍可执行。
            db.prepare("SELECT 1").use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
        }
    }

    @Test
    fun `缺表在 prepare 阶段归一为 SqlNativeException 且不返回空结果`() {
        withMemoryDb { db ->
            // SQLite 在 prepare 解析表名时就报 no such table；若被处理成「无行」而返回
            // 空结果，这里拿不到异常，测试即失败。
            val failure = expectFailure("missing-table", "no such table") {
                db.prepare("SELECT * FROM missing_table")
            }
            assertContains(failure.message.orEmpty(), "missing_table")
        }
    }

    @Test
    fun `UNIQUE 冲突归一且不静默成功`() {
        withMemoryDb { db ->
            db.prepare("CREATE TABLE u (id INTEGER PRIMARY KEY, name TEXT UNIQUE)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO u (name) VALUES (?)").use { statement ->
                statement.bind(1, SqlBind.Text("炖菜"))
                assertFalse(statement.step())
            }
            assertEquals(1L, db.totalChanges())

            expectFailure("unique-conflict", "UNIQUE constraint failed") {
                db.prepare("INSERT INTO u (name) VALUES (?)").use { statement ->
                    statement.bind(1, SqlBind.Text("炖菜"))
                    statement.step()
                }
            }

            // 失败插入既不能算成功，也不能把行数悄悄改掉。
            assertEquals(1L, db.totalChanges())
            db.prepare("SELECT COUNT(*) FROM u").use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
        }
    }

    /**
     * 列级 UNIQUE 在 `CREATE TABLE` 里合法，但 SQLite 不允许
     * `ALTER TABLE ... ADD COLUMN ... UNIQUE`（表级约束要用 `CREATE UNIQUE INDEX`）。
     *
     * 迁移前 `479e9ffd9` 修的是「列级 UNIQUE 让 qol_card.db 整表跳过」，本条钉的是另一半：
     * 驱动层遇到这条语句必须原样报错，不能被 adapter 折成成功或空结果，也不能真把列加上
     * ——失败后表结构必须原封不动，留给建表期而不是 ALTER 期发现。
     */
    @Test
    fun `ALTER TABLE ADD COLUMN UNIQUE 归一为 SqlNativeException 且不加列`() {
        withMemoryDb { db ->
            db.prepare("CREATE TABLE alter_u (id INTEGER PRIMARY KEY, tag TEXT)").use { assertFalse(it.step()) }

            // SQLite 3.46.0 实际文案（2026-11-15 实测，bundled 驱动在 prepare 期抛出，errno=1）：
            //   SQLite 准备语句失败：Error code: 1, message: Cannot add a UNIQUE column
            // 断言钉完整原文；若未来 SQLite 改文案，会先在这里判红而不是让 ALTER 静默生效。
            val failure = expectFailure("alter-add-unique-column", "Cannot add a UNIQUE column") {
                db.prepare("ALTER TABLE alter_u ADD COLUMN extra TEXT UNIQUE").use { assertFalse(it.step()) }
            }
            println("[error-surface] alter-add-unique-column message=${failure.message}")
            println(
                "[error-surface] alter-add-unique-column cause=" +
                    failure.cause?.let { "${it::class.qualifiedName}: ${it.message}" },
            )

            // 反向证明：语句失败后表结构原样（extra 不在），不能是「静默加列」。
            val columns = mutableListOf<String>()
            db.prepare("PRAGMA table_info(alter_u)").use { statement ->
                while (statement.step()) columns += statement.getText(1)
            }
            assertEquals(listOf("id", "tag"), columns)
        }
    }

    /** 表级 `UNIQUE(owner_no, slot)` 与列级 UNIQUE 报的是同一族错误面，冲突时不得写半行。 */
    @Test
    fun `表级 UNIQUE owner_no slot 冲突归一且不写半行`() {
        withMemoryDb { db ->
            db.prepare(
                "CREATE TABLE slot_owner (id INTEGER PRIMARY KEY, owner_no INTEGER, slot INTEGER, " +
                    "UNIQUE(owner_no, slot))",
            ).use { assertFalse(it.step()) }
            insertSlot(db, OWNER_NO, SLOT)
            assertEquals(1L, db.totalChanges())

            // 3.46.0 实际文案（2026-11-15 实测）：
            //   SQLite 执行语句 失败：Error code: 19, message: UNIQUE constraint failed:
            //   slot_owner.owner_no, slot_owner.slot
            val failure = expectFailure("table-unique-conflict", "UNIQUE constraint failed") {
                insertSlot(db, OWNER_NO, SLOT)
            }
            assertContains(failure.message.orEmpty(), "slot_owner.owner_no, slot_owner.slot")

            db.prepare("SELECT COUNT(*) FROM slot_owner").use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0), "冲突插入不得留下第二行")
            }
        }
    }

    private fun insertSlot(db: SqlConnection, ownerNo: Long, slot: Long) {
        db.prepare("INSERT INTO slot_owner (owner_no, slot) VALUES (?, ?)").use { statement ->
            statement.bind(1, SqlBind.Integer(ownerNo))
            statement.bind(2, SqlBind.Integer(slot))
            assertFalse(statement.step())
        }
    }

    @Test
    fun `NOT NULL 冲突归一且不写入空行`() {
        withMemoryDb { db ->
            db.prepare("CREATE TABLE nn (id INTEGER PRIMARY KEY, note TEXT NOT NULL)").use { assertFalse(it.step()) }

            val failure = expectFailure("not-null-conflict", "NOT NULL constraint failed") {
                db.prepare("INSERT INTO nn (note) VALUES (?)").use { statement ->
                    statement.bind(1, SqlBind.Null)
                    statement.step()
                }
            }
            assertContains(failure.message.orEmpty(), "nn.note")

            db.prepare("SELECT COUNT(*) FROM nn").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun `五种绑定类型往返可读且不误报`() {
        withMemoryDb { db ->
            db.prepare("CREATE TABLE v (i INTEGER, f REAL, t TEXT, b BLOB, n)").use { assertFalse(it.step()) }
            db.prepare("INSERT INTO v (i, f, t, b, n) VALUES (?, ?, ?, ?, ?)").use { statement ->
                statement.bind(1, SqlBind.Integer(ANSWER))
                statement.bind(2, SqlBind.Float(SCORE))
                statement.bind(3, SqlBind.Text("炖菜"))
                statement.bind(4, SqlBind.Blob(byteArrayOf(0, 1, 2, -1)))
                statement.bind(5, SqlBind.Null)
                assertFalse(statement.step())
            }

            db.prepare("SELECT i, f, t, b, n FROM v").use { statement ->
                assertTrue(statement.step())
                assertEquals(SqlColumnType.INTEGER, statement.columnType(0))
                assertEquals(SqlColumnType.FLOAT, statement.columnType(1))
                assertEquals(SqlColumnType.TEXT, statement.columnType(2))
                assertEquals(SqlColumnType.BLOB, statement.columnType(3))
                assertEquals(SqlColumnType.NULL, statement.columnType(4))
                assertEquals(ANSWER, statement.getLong(0))
                assertEquals(SCORE, statement.getDouble(1), TOLERANCE)
                assertEquals("炖菜", statement.getText(2))
                assertContentEquals(byteArrayOf(0, 1, 2, -1), statement.getBlob(3))
                assertTrue(statement.isNull(4))
                assertFalse(statement.step())
            }
        }
    }

    @Test
    fun `STRICT 表拒绝错误绑定类型并给出可读错误`() {
        withMemoryDb { db ->
            db.prepare("CREATE TABLE strict_v (v INTEGER) STRICT").use { assertFalse(it.step()) }
            val cases = listOf(
                Triple("strict-text", SqlBind.Text("不是数字"), "cannot store TEXT value in INTEGER column"),
                Triple("strict-float", SqlBind.Float(NON_INTEGER), "cannot store REAL value in INTEGER column"),
                Triple("strict-blob", SqlBind.Blob(byteArrayOf(1, 2)), "cannot store BLOB value in INTEGER column"),
            )
            for ((label, value, keyword) in cases) {
                expectFailure(label, keyword) {
                    db.prepare("INSERT INTO strict_v (v) VALUES (?)").use { statement ->
                        statement.bind(1, value)
                        statement.step()
                    }
                }
            }

            // 类型不符的绑定不得留下任何一行。
            db.prepare("SELECT COUNT(*) FROM strict_v").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
    }

    @Test
    fun `越界绑定参数号归一为 SqlNativeException`() {
        withMemoryDb { db ->
            db.prepare("CREATE TABLE params (v INTEGER)").use { assertFalse(it.step()) }
            for (index in listOf(0, 2)) {
                db.prepare("INSERT INTO params (v) VALUES (?)").use { statement ->
                    val failure = expectFailure("bind-range-$index", "column index out of range") {
                        statement.bind(index, SqlBind.Integer(1))
                    }
                    assertContains(failure.message.orEmpty(), "column index out of range")
                }
            }
        }
    }

    @Test
    fun `prepare 后 close 再 step 抛 SqlNativeException 而非 no-op`() {
        withMemoryDb { db ->
            val statement = db.prepare("SELECT 1")
            statement.close()
            statement.close()

            // adapter 的 closed 闸先于驱动：明确抛 SqlNativeException，不是静默 no-op。
            val failure = expectFailure("step-after-close") { statement.step() }
            assertEquals("SQLite 语句已关闭", failure.message)
            assertNull(failure.cause, "closed 闸是 adapter 自己拦的，没有平台 cause")

            // 其余语句入口同样走 closed 闸，不能漏到驱动层。
            expectFailure("bind-after-close") { statement.bind(1, SqlBind.Integer(1)) }
            expectFailure("columnCount-after-close") { statement.columnCount }
            expectFailure("reset-after-close") { statement.reset() }
            expectFailure("clearBindings-after-close") { statement.clearBindings() }
        }
    }

    @AfterAll
    fun printPlayerFacingSamples() {
        assertTrue(samples.isNotEmpty(), "没有采集到任何错误样本")
        val report = buildString {
            appendLine()
            appendLine("=== P6 SQLite 错误面样本（player-facing = SqlNativeException.message，M1 CodeEE 包装前）===")
            for (sample in samples) {
                appendLine("[${sample.label}] ${sample.playerMessage}")
                appendLine("    原始 cause: ${sample.cause}")
            }
        }
        println(report)
    }
}
