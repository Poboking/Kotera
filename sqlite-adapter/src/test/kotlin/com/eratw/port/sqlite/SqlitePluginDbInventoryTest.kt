package com.eratw.port.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlBind
import com.eratw.port.core.sql.SqlConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * P4 PLUGIN-DB-INVENTORY：261111 随包插件库的真实读数。
 *
 * 目标不是复刻插件 SQL，而是给迁移后的真实 SQLite 驱动留一组可复算的存量证据：
 * - 直接用 [BundledSQLiteDriver] 只读打开 `ERATW_ROOT/eraTW4.981-261111-EAGLOV3/plugins`
 *   下的随包库，每张表用 `SELECT COUNT(*)` 实算；
 * - 再经 [AndroidXSqliteDriver] 把 seed 复制到 `@TempDir` 写根，首次打开与**重开**各查一次，
 *   三份读数必须一致 —— 同时覆盖 A1 的 seed copy 与 A2 的路径落点；
 * - 真实语料根不在位时 `assumeTrue` 跳过并写明找过的根，不伪造读数。
 *
 * 行数钉的是 261111 随包库本身（2026-11-14 换代档）；语料更新时应显式判红，而不是让
 * 库存账悄悄漂移。
 */
class SqlitePluginDbInventoryTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `img_index db 的 _meta 与 chara_img_priority 行数`() {
        val root = gameRootOrSkip()
        var metaKey = ""
        var metaValue = ""

        val read = inventory(
            root = root,
            relative = "plugins/img_index.db",
            tables = listOf("_meta", "chara_img_priority"),
            inspectDirect = { connection ->
                connection.prepare("SELECT key, value FROM _meta").use { statement ->
                    assertTrue(statement.step(), "_meta 没有行")
                    metaKey = statement.getText(0)
                    metaValue = statement.getText(1)
                    assertFalse(statement.step(), "_meta 应恰好一行")
                }
            },
            inspectSandbox = { connection ->
                assertEquals("20261109", readMeta(connection, "img_index_version"))
            },
        )

        assertEquals(mapOf("_meta" to 1L, "chara_img_priority" to 1848L), read.direct)
        assertEquals("img_index_version", metaKey)
        assertEquals("20261109", metaValue)
        report("plugins/img_index.db", read)
    }

    @Test
    fun `portrait_map db 的 PORTRAIT_MAP 行数`() {
        val root = gameRootOrSkip()
        val read = inventory(root, "plugins/portrait_map.db", listOf("PORTRAIT_MAP"))

        assertEquals(mapOf("PORTRAIT_MAP" to 2537L), read.direct)
        report("plugins/portrait_map.db", read)
    }

    @Test
    fun `qol_card db 的卡牌与效果表行数`() {
        val root = gameRootOrSkip()
        var directSchema = ""
        var directCardKey = ""
        var seedSchema = ""
        var seedCardKey = ""
        val read = inventory(
            root,
            "plugins/qol_card.db",
            listOf("QOL_CARD_DB", "QOL_CARD_EFFECT_DB"),
            inspectDirect = { connection ->
                directSchema = metaValueDirect(connection, CARD_SCHEMA_KEY).orEmpty()
                directCardKey = cardKeyDirect(connection, CARD_NO).orEmpty()
                println(
                    "[plugin-db] qol_card.db direct _meta.$CARD_SCHEMA_KEY=$directSchema " +
                        "card_no=$CARD_NO card_key=$directCardKey",
                )
            },
            inspectSandbox = { connection ->
                seedSchema = readMeta(connection, CARD_SCHEMA_KEY).orEmpty()
                seedCardKey = cardKey(connection, CARD_NO).orEmpty()
                println(
                    "[plugin-db] qol_card.db seed-copy _meta.$CARD_SCHEMA_KEY=$seedSchema " +
                        "card_no=$CARD_NO card_key=$seedCardKey",
                )
            },
        )

        assertEquals(mapOf("QOL_CARD_DB" to 34L, "QOL_CARD_EFFECT_DB" to 3L), read.direct)
        // 实测值（2026-11-15，bundled SQLite 3.46.0 直读 261111 随包 qol_card.db 打印后钉入）：
        // _meta 是 key/value 形态（key TEXT PRIMARY KEY + value TEXT），版本号按 key 查；
        // card_schema_version=20261025-eaf6914c，card_no=1 的 card_key=YINYANG_ORB。
        // 这两个值必须一起钉：只查行数会漏掉 schema 代号漂移与卡 key 改名（479e9ffd9 修的是整表跳过）。
        assertEquals(CARD_SCHEMA_VERSION, directSchema, "随包 qol_card.db 的 _meta.$CARD_SCHEMA_KEY 漂移")
        assertEquals(CARD_KEY_CARD_NO_1, directCardKey, "随包 qol_card.db 的 card_no=$CARD_NO 的 card_key 漂移")
        assertEquals(directSchema, seedSchema, "seed copy 首开读到的 card_schema_version 应与随包库一致")
        assertEquals(directCardKey, seedCardKey, "seed copy 首开读到的 card_key 应与随包库一致")
        report("plugins/qol_card.db", read)
    }

    @Test
    fun `card_key TEXT UNIQUE 的 INSERT OR REPLACE 与 ON CONFLICT 都生效`() {
        val seedRoot = Files.createDirectories(tmp.resolve("seed"))
        val writeRoot = Files.createDirectories(tmp.resolve("sandbox"))
        val driver = AndroidXSqliteDriver(BundledSQLiteDriver(), seedRoot, writeRoot)
        val connectionString = "Data Source=card_upsert.db"
        val start = System.nanoTime()

        driver.open(connectionString).use { db ->
            db.prepare(
                "CREATE TABLE card (card_no INTEGER PRIMARY KEY, card_key TEXT UNIQUE, name TEXT)",
            ).use { assertFalse(it.step()) }

            replaceCard(db, "k1", "v1")
            replaceCard(db, "k1", "v2")
            assertEquals(1L, countPort(db, "card"))
            assertEquals("v2", cardName(db, "k1"))

            upsertCard(db, "k2", "a")
            upsertCard(db, "k2", "b")
            assertEquals(2L, countPort(db, "card"))
            assertEquals("b", cardName(db, "k2"))
            assertEquals("v2", cardName(db, "k1"))

            db.prepare(
                "INSERT INTO card (card_key, name) VALUES (?, ?) ON CONFLICT(card_key) DO NOTHING",
            ).use { statement ->
                statement.bind(1, SqlBind.Text("k2"))
                statement.bind(2, SqlBind.Text("ignored"))
                assertFalse(statement.step())
            }
            assertEquals(2L, countPort(db, "card"))
            assertEquals("b", cardName(db, "k2"))
        }

        val target = writeRoot.resolve("card_upsert.db")
        assertTrue(Files.isRegularFile(target), "临时库未落盘：$target")
        driver.open(connectionString).use { db ->
            assertEquals(2L, countPort(db, "card"))
            assertEquals("b", cardName(db, "k2"))
        }

        println(
            "[plugin-db] card_upsert.db rows=2 (INSERT OR REPLACE + ON CONFLICT DO UPDATE + " +
                "DO NOTHING) elapsed=${formatMs(elapsedMs(start))}ms target=$target",
        )
    }

    /**
     * 一次盘点 = 随包库直读 + seed copy 首次打开 + 沙箱重开；三份读数由 [report] 对账。
     *
     * 随包库只做 SELECT：`journal_mode` 是 delete，读取不会产生伴生文件，语料保持只读。
     */
    private fun inventory(
        root: Path,
        relative: String,
        tables: List<String>,
        inspectDirect: (SQLiteConnection) -> Unit = {},
        inspectSandbox: (SqlConnection) -> Unit = {},
    ): InventoryRead {
        val seed = root.resolve(relative)
        assertTrue(Files.isRegularFile(seed), "261111 随包库缺失：$seed")

        val directStart = System.nanoTime()
        val direct = withDirect(seed) { connection ->
            inspectDirect(connection)
            tables.associateWith { countDirect(connection, it) }
        }
        val directMs = elapsedMs(directStart)

        val writeRoot = Files.createDirectories(tmp.resolve("sandbox"))
        val driver = AndroidXSqliteDriver(BundledSQLiteDriver(), root, writeRoot)
        val connectionString = "Data Source=$relative"

        val firstStart = System.nanoTime()
        val firstOpen = driver.open(connectionString).use { connection ->
            inspectSandbox(connection)
            tables.associateWith { countPort(connection, it) }
        }
        val firstOpenMs = elapsedMs(firstStart)

        val target = writeRoot.resolve(relative)
        assertTrue(Files.isRegularFile(target), "seed 未复制到写根：$target")
        assertEquals(Files.size(seed), Files.size(target), "seed copy 与随包库大小不一致：$target")

        val reopenStart = System.nanoTime()
        val reopened = driver.open(connectionString).use { connection ->
            tables.associateWith { countPort(connection, it) }
        }
        val reopenMs = elapsedMs(reopenStart)

        return InventoryRead(
            direct = direct,
            firstOpen = firstOpen,
            reopened = reopened,
            directMs = directMs,
            firstOpenMs = firstOpenMs,
            reopenMs = reopenMs,
            seedBytes = Files.size(seed),
        )
    }

    /** 对账三份读数并打出真实行数/耗时；任一不一致直接判红。 */
    private fun report(relative: String, read: InventoryRead) {
        assertEquals(read.direct, read.firstOpen, "$relative：seed copy 首次打开读数与随包库不一致")
        assertEquals(read.firstOpen, read.reopened, "$relative：沙箱重开读数漂移")
        println(
            "[plugin-db] $relative direct=${read.direct} seedBytes=${read.seedBytes} " +
                "elapsed=${formatMs(read.directMs)}ms",
        )
        println(
            "[plugin-db] $relative seed-copy-first-open=${read.firstOpen} " +
                "elapsed=${formatMs(read.firstOpenMs)}ms sandbox-reopen=${read.reopened} " +
                "elapsed=${formatMs(read.reopenMs)}ms",
        )
    }

    private fun <T> withDirect(seed: Path, block: (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(seed.toString()).use(block)

    private fun countDirect(connection: SQLiteConnection, table: String): Long =
        connection.prepare("SELECT COUNT(*) FROM $table").use { statement ->
            assertTrue(statement.step(), "SELECT COUNT(*) FROM $table 无结果")
            statement.getLong(0)
        }

    private fun countPort(connection: SqlConnection, table: String): Long =
        connection.prepare("SELECT COUNT(*) FROM $table").use { statement ->
            assertTrue(statement.step(), "SELECT COUNT(*) FROM $table 无结果")
            statement.getLong(0)
        }

    private fun readMeta(connection: SqlConnection, key: String): String? =
        connection.prepare("SELECT value FROM _meta WHERE key = ?").use { statement ->
            statement.bind(1, SqlBind.Text(key))
            if (statement.step()) statement.getText(0) else null
        }

    /**
     * 随包库直读的 `_meta` 取值：`qol_card.db` 的 `_meta` 是 key/value 形态
     * （`PRAGMA table_info(_meta)` = `key TEXT PRIMARY KEY` + `value TEXT`，不是单行宽表），
     * 所以版本号按 key 查而不是按列位置取。
     */
    private fun metaValueDirect(connection: SQLiteConnection, key: String): String? =
        connection.prepare("SELECT value FROM _meta WHERE key = ?").use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }

    private fun cardKeyDirect(connection: SQLiteConnection, cardNo: Long): String? =
        connection.prepare("SELECT card_key FROM QOL_CARD_DB WHERE card_no = ?").use { statement ->
            statement.bindLong(1, cardNo)
            if (statement.step()) statement.getText(0) else null
        }

    private fun cardKey(connection: SqlConnection, cardNo: Long): String? =
        connection.prepare("SELECT card_key FROM QOL_CARD_DB WHERE card_no = ?").use { statement ->
            statement.bind(1, SqlBind.Integer(cardNo))
            if (statement.step()) statement.getText(0) else null
        }

    private fun replaceCard(db: SqlConnection, key: String, name: String) {
        db.prepare("INSERT OR REPLACE INTO card (card_key, name) VALUES (?, ?)").use { statement ->
            statement.bind(1, SqlBind.Text(key))
            statement.bind(2, SqlBind.Text(name))
            assertFalse(statement.step())
        }
    }

    private fun upsertCard(db: SqlConnection, key: String, name: String) {
        db.prepare(
            "INSERT INTO card (card_key, name) VALUES (?, ?) " +
                "ON CONFLICT(card_key) DO UPDATE SET name = excluded.name",
        ).use { statement ->
            statement.bind(1, SqlBind.Text(key))
            statement.bind(2, SqlBind.Text(name))
            assertFalse(statement.step())
        }
    }

    private fun cardName(db: SqlConnection, key: String): String? =
        db.prepare("SELECT name FROM card WHERE card_key = ?").use { statement ->
            statement.bind(1, SqlBind.Text(key))
            if (statement.step()) statement.getText(0) else null
        }

    /**
     * 共享根：`ERATW_ROOT` 显式指定（公开仓不带主机路径）；只认含
     * 261111 随包 `plugins/img_index.db` 的候选。找不到即 skip，不猜、不造假。
     */
    private fun gameRootOrSkip(): Path {
        val candidates = listOfNotNull(
            System.getenv("ERATW_ROOT")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) },
        )
        val root = candidates
            .map { it.resolve(GAME_DIR) }
            .firstOrNull { Files.isRegularFile(it.resolve("plugins/img_index.db")) }
        assumeTrue(
            root != null,
            "缺 261111 随包语料根（ERATW_ROOT 或主机已知共享根下没有 " +
                "$GAME_DIR/plugins/img_index.db），跳过插件库清点",
        )
        return root!!
    }

    private fun elapsedMs(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / 1_000_000.0

    private fun formatMs(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private data class InventoryRead(
        val direct: Map<String, Long>,
        val firstOpen: Map<String, Long>,
        val reopened: Map<String, Long>,
        val directMs: Double,
        val firstOpenMs: Double,
        val reopenMs: Double,
        val seedBytes: Long,
    )

    private companion object {
        const val GAME_DIR = "eraTW4.981-261111-EAGLOV3"

        /** `_meta` 里记录卡牌库 schema 代号的 key（2026-11-14 换代档）。 */
        const val CARD_SCHEMA_KEY = "card_schema_version"
        const val CARD_NO = 1L

        /** 261111 随包 qol_card.db 实测：`_meta.card_schema_version`。 */
        const val CARD_SCHEMA_VERSION = "20261025-eaf6914c"

        /** 261111 随包 qol_card.db 实测：`QOL_CARD_DB WHERE card_no=1` 的 `card_key`。 */
        const val CARD_KEY_CARD_NO_1 = "YINYANG_ORB"
    }
}
