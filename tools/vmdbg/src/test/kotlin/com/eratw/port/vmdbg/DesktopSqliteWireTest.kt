package com.eratw.port.vmdbg

import com.eratw.port.core.sql.SqlConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * vmdbg 沙箱 SQLite 接线（CONTRACT-SQLITE-MIGRATION §3/§4 W4）。
 *
 * 断言的是 [Assembler.assemble] 的三条接线事实，不依赖真语料与 ERATW_* 环境变量：
 *
 * 1. [com.eratw.port.core.proc.GameSession.builtinHost] 的 `sqlDriver` 非 null —— W4 的传参
 *    真的落到了会话上（W2 工厂自证不算）；
 * 2. 写根 = `<存档沙箱>/sql` —— `Data Source=plugins/qol_data.db` 落成
 *    `<sandboxDir>/sql/plugins/qol_data.db`，且 `--work` 目录下没有第二个库文件，
 *    语料根也没有被建出 `plugins/`；
 * 3. 读根 = 游戏根且**只读** —— 随包 seed 被拷成沙箱副本（seed 行可读、新行只进副本），
 *    语料根 seed 的 sha256 与整棵语料树快照都不变。
 *
 * 前置：W2 `DesktopSqliteHost`（含 `sqlite-bundled-jvm` 依赖）+ A1 `AndroidXSqliteDriver` +
 * A2 `SqlitePathLayout` + L1 `GameSession(sqlDriver = …)`。M1（SqlMethods 改走 SqlSession）
 * 不是本用例的前置：这里直接驱动 `SqlDriver` 端口，验「Assembler 把哪个驱动、用哪两个根
 * 交给了会话」，与 SQL_* 内建走哪条实现无关。
 *
 * 语料根 = [MiniCorpus] 的临时目录 fixture；第 2 个用例会往里放一份真实 SQLite seed，
 * 与真语料（只读）无关。
 */
class DesktopSqliteWireTest {

    @Test
    fun assembleWiresSandboxSqlDriver() {
        val tmp = Files.createTempDirectory("vmdbg-sql-wire")
        try {
            val built = MiniCorpus.build(tmp)
            val work = tmp.resolve("work")
            val assembled = assemble(built, work)
            val driver = assertNotNull(
                assembled.session.builtinHost.sqlDriver,
                "Assembler 必须把 DesktopSqliteHost 的驱动传进 GameSession（null = 未接线）",
            )
            val db = assembled.sandboxDir.resolve("sql").resolve("plugins").resolve("qol_data.db")
            assertFalse(Files.exists(db), "SQL_CONNECT 之前不得有库文件：$db")

            driver.open("Data Source=plugins/qol_data.db").use { c ->
                exec(c, "CREATE TABLE t(x INTEGER)")
                exec(c, "INSERT INTO t(x) VALUES (42)")
            }
            assertTrue(Files.isRegularFile(db), "库文件必须落在存档沙箱 sql/ 下：$db")

            // 真实 SQLite 往返：重新打开同一个 target 读到的是沙箱副本，不是重新拷种。
            driver.open("Data Source=plugins/qol_data.db").use { c ->
                c.prepare("SELECT x FROM t").use { st ->
                    assertTrue(st.step(), "SELECT 应有行")
                    assertEquals(42L, st.getLong(0))
                }
            }

            // 整个 --work 目录里只允许沙箱 sql/ 出现 .db（存档沙箱本身不产 .db）。
            val dbFiles = Files.walk(work).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".db") }
                    .map { it.toAbsolutePath().normalize() }
                    .toList()
            }
            assertEquals(
                listOf(db.toAbsolutePath().normalize()),
                dbFiles,
                "work 下只应有沙箱 sql/ 里的库文件",
            )
            assertFalse(Files.exists(built.gameRoot.resolve("plugins")), "不得在语料根建 plugins/")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun seedIsCopiedIntoSandboxAndNeverWrittenThrough() {
        val tmp = Files.createTempDirectory("vmdbg-sql-seed")
        try {
            val built = MiniCorpus.build(tmp)
            val work = tmp.resolve("work")
            // 用同一个 W2 工厂造一份真实 SQLite seed：先写到独立临时写根，再拷成语料根里的 seed。
            val seedWrite = tmp.resolve("seed-write")
            DesktopSqliteHost.create(tmp.resolve("seed-empty"), seedWrite)
                .open("Data Source=plugins/qol_data.db").use { c ->
                    exec(c, "CREATE TABLE seed_t(x INTEGER)")
                    exec(c, "INSERT INTO seed_t(x) VALUES (7)")
                }
            val seed = built.gameRoot.resolve("plugins").resolve("qol_data.db")
            Files.createDirectories(seed.parent)
            Files.copy(seedWrite.resolve("plugins").resolve("qol_data.db"), seed)
            val seedSha = sha256(seed)
            val treeBefore = built.treeSnapshot()

            val assembled = assemble(built, work)
            val driver = assertNotNull(assembled.session.builtinHost.sqlDriver)
            driver.open("Data Source=plugins/qol_data.db").use { c ->
                // seed 行可读 ⇒ 首开确实从语料根拷了种。
                c.prepare("SELECT x FROM seed_t").use { st ->
                    assertTrue(st.step(), "首开必须从语料根的 seed 拷种")
                    assertEquals(7L, st.getLong(0))
                }
                exec(c, "INSERT INTO seed_t(x) VALUES (8)")
            }

            // 写穿证明：沙箱副本有新行，语料根 seed 一字节没动、整棵语料树快照不变。
            assertEquals(seedSha, sha256(seed), "写根必须是沙箱副本，语料根的 seed 不得被写")
            assertEquals(treeBefore, built.treeSnapshot(), "语料树快照不得变化")
            driver.open("Data Source=plugins/qol_data.db").use { c ->
                c.prepare("SELECT COUNT(*) FROM seed_t").use { st ->
                    assertTrue(st.step(), "COUNT 应有结果")
                    assertEquals(2L, st.getLong(0), "新行只落在沙箱副本")
                }
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** 装配一个只跑 mini 语料 `MINI_PASS` 的会话（不执行，只验装配期接线）。 */
    private fun assemble(built: MiniCorpus.Built, work: Path): Assembler.Assembled =
        Assembler.assemble(Args.parse(MiniCorpus.args(built, work, "call", "MINI_PASS").toList()))

    private fun exec(c: SqlConnection, sql: String) {
        c.prepare(sql).use { st -> st.step() }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
