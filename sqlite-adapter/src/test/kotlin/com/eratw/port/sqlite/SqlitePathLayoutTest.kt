package com.eratw.port.sqlite

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * [SqlitePathLayout] 的纯 JVM 定向钉：只碰临时目录，不依赖 Android / AndroidX / 真实 SQLite。
 *
 * 覆盖契约 §3 的可观察面：连接串分段与键大小写/空白、Windows 与 Unix 分隔符、纯路径、
 * target/seed 的根内落点、绝对路径与 `..` 出根拒绝、三种内存形态、seed 缺失/非普通文件。
 */
class SqlitePathLayoutTest {

    @Test
    fun `Data Source 键大小写与两侧空白不敏感`(@TempDir dir: Path) {
        assertEquals(emptySet(), DEFAULT_MUTABLE_DATA_SOURCES, "默认无可写库（取证见 DEFAULT_MUTABLE_DATA_SOURCES KDoc）")
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val seed = writeSeed(seedRoot, "plugins/qol_data.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        for (connection in listOf(
            "Data Source = plugins/qol_data.db",
            "data source=plugins/qol_data.db",
            "DATASOURCE = plugins/qol_data.db",
            "Data   Source=plugins/qol_data.db",
            "DATA SOURCE = plugins/qol_data.db",
            " FiLeNaMe = plugins/qol_data.db ",
        )) {
            val file = assertIs<SqliteOpenTarget.File>(layout.resolve(connection), connection)
            assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), file.target, connection)
            assertEquals(seed, file.seed, connection)
            assertFalse(file.mutable, "默认集合为空 ⇒ qol_data 不再命中 mutable，随 seed 更新刷新")
        }
    }

    @Test
    fun `注入 mutableDataSources 的库命中永不刷新语义`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        writeSeed(seedRoot, "plugins/private.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot, mutableDataSources = setOf("plugins/private.db"))

        val file = assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins/private.db"))
        assertTrue(file.mutable, "注入集合命中 ⇒ 保留「target 一旦存在永不回滚」的机制语义")
        assertFalse(
            assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins/other.db")).mutable,
            "未注入的库不受 mutable 保护",
        )
    }

    @Test
    fun `Windows 反斜杠与 Unix 斜杠归一到同一 target 和 seed`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val seed = writeSeed(seedRoot, "plugins/qol_data.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        val unix = assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins/qol_data.db"))
        val windows = assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins\\qol_data.db"))

        assertEquals(unix, windows)
        assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), windows.target)
        assertEquals(seed, windows.seed)
    }

    @Test
    fun `无等号整串按路径解析`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val seed = writeSeed(seedRoot, "plugins/qol_data.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        val file = assertIs<SqliteOpenTarget.File>(layout.resolve("plugins/qol_data.db"))
        assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), file.target)
        assertEquals(seed, file.seed)

        val windows = assertIs<SqliteOpenTarget.File>(layout.resolve("plugins\\qol_data.db"))
        assertEquals(file, windows)
    }

    @Test
    fun `分号分段只认 datasource 与 filename 且后值覆盖前值`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val seed = writeSeed(seedRoot, "plugins/qol_data.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        val withOptions = assertIs<SqliteOpenTarget.File>(
            layout.resolve("Mode=ReadWrite;Data Source=plugins/qol_data.db"),
        )
        assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), withOptions.target)
        assertEquals(seed, withOptions.seed)

        val lastWins = assertIs<SqliteOpenTarget.File>(
            layout.resolve("Data Source=plugins/other.db;Filename=plugins/qol_data.db"),
        )
        assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), lastWins.target)
        assertEquals(seed, lastWins.seed)
    }

    @Test
    fun `target 在 writeRoot 内 seed 在 seedRoot 内`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        writeSeed(seedRoot, "plugins/qol_data.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        val file = assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins/qol_data.db"))
        assertTrue(file.target.startsWith(writeRoot), "target 必须落在写根内：${file.target}")
        val seed = assertNotNull(file.seed, "种子文件存在时必须返回 seed")
        assertTrue(seed.startsWith(seedRoot), "seed 必须落在种子根内：$seed")
        assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), file.target)
        assertEquals(seedRoot.resolve("plugins/qol_data.db").normalize(), seed)
    }

    @Test
    fun `绝对路径与盘符路径一律拒绝`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        for (path in listOf(
            "/etc/qol_data.db",
            "C:\\temp\\qol_data.db",
            "C:/temp/qol_data.db",
            "\\\\server\\share\\qol_data.db",
        )) {
            assertFailsWith<IllegalArgumentException>(path) {
                layout.resolve("Data Source=$path")
            }
        }

        assertFailsWith<IllegalArgumentException>("纯路径同样不许绝对") {
            layout.resolve("/tmp/qol_data.db")
        }
    }

    @Test
    fun `相对路径跳出写根一律拒绝`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        for (path in listOf(
            "../escape.db",
            "plugins/../../escape.db",
            "plugins\\..\\..\\escape.db",
        )) {
            assertFailsWith<IllegalArgumentException>(path) {
                layout.resolve("Data Source=$path")
            }
        }
    }

    @Test
    fun `根内上跳归一后仍受写根约束`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val seed = writeSeed(seedRoot, "plugins/qol_data.db")
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        val file = assertIs<SqliteOpenTarget.File>(
            layout.resolve("Data Source=plugins/sub/../qol_data.db"),
        )
        assertEquals(writeRoot.resolve("plugins/qol_data.db").normalize(), file.target)
        assertEquals(seed, file.seed)
    }

    @Test
    fun `空串与内存形态返回 Memory`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        for (connection in listOf(
            "",
            "   ",
            ":memory:",
            "file::memory:",
            "Data Source=:memory:",
            "Filename=file::memory:",
            "Data Source=",
        )) {
            assertEquals(SqliteOpenTarget.Memory, layout.resolve(connection), connection)
        }
    }

    @Test
    fun `seed 缺失或不是普通文件时返回 null`(@TempDir dir: Path) {
        val seedRoot = Files.createDirectories(dir.resolve("seed"))
        val writeRoot = Files.createDirectories(dir.resolve("write"))
        Files.createDirectories(seedRoot.resolve("plugins/dir.db"))
        val layout = SqlitePathLayout(seedRoot, writeRoot)

        val missing = assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins/missing.db"))
        assertEquals(writeRoot.resolve("plugins/missing.db").normalize(), missing.target)
        assertNull(missing.seed)

        val directory = assertIs<SqliteOpenTarget.File>(layout.resolve("Data Source=plugins/dir.db"))
        assertEquals(writeRoot.resolve("plugins/dir.db").normalize(), directory.target)
        assertNull(directory.seed)
    }
}

private fun writeSeed(root: Path, relative: String): Path {
    val path = root.resolve(relative).normalize()
    Files.createDirectories(path.parent)
    Files.write(path, "seed".toByteArray())
    return path
}
