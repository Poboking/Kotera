package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--sandbox` / `--pre` / `--sets` / `sql` 的定向回归（CONTRACT-SQLITE-MIGRATION T2 真链路）。
 *
 * 断言的是「同一固定沙箱跨进程读回同一真实 SQLite 库」这条 T2 判据的装置面：
 * 1. 第一次 `call` 建表写库，第二次 `call` 在**另一个进程**里读回 `MAX(v)=42`；
 * 2. `sql` 用与 GameSession 同一个 [DesktopSqliteHost] 读回同一行；
 * 3. `--sets` 在调用前生效（`MINI_TOTAL` 打印注入后的 `GLOBAL_TOTAL`）；
 * 4. `--sandbox` 出 `--work` 根必须拒绝 —— 语料只读铁律不能靠调用方自觉。
 *
 * 本用例不碰真语料/真产物：MiniCorpus 的 `ERB/mini.erb` 里三条 SQL 语句走的是
 * `SQL_CONNECT` / `SQL_EXECUTE_NONQUERY` / `SQL_P_EXECUTE_SCALAR_LONG` 的真实内建路径。
 */
class FixedSandboxSqlTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-fixed-sandbox")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val err = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, err)
        return code to buf.toString(Charsets.UTF_8)
    }

    private fun runWithErr(vararg args: String): Triple<Int, String, String> {
        val buf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        val err = PrintStream(errBuf, true, Charsets.UTF_8)
        val code = Cli.run(arrayOf(*args), out, err)
        return Triple(code, buf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    @Test
    fun fixedSandboxPersistsAcrossProcessesAndSqlReadsBackSameDatabase() {
        val sandbox = work.resolve("sb")
        val (initCode, initOut) = run(
            *MiniCorpus.args(built, work, "call", "MINI_DB_INIT", "--sandbox", sandbox.toString()),
        )
        assertEquals(0, initCode, initOut)
        assertTrue(Files.isRegularFile(sandbox.resolve("sql/plugins/mini.db")), "库必须落在固定沙箱: $initOut")

        val (maxCode, maxOut) = run(
            *MiniCorpus.args(built, work, "call", "MINI_DB_MAX_CALL", "--sandbox", sandbox.toString()),
        )
        assertEquals(0, maxCode, maxOut)
        assertTrue("status=PASS" in maxOut && "target=MINI_DB_MAX_CALL" in maxOut, "第二次进程必须能重连同一库: $maxOut")

        val (sqlCode, sqlOut) = run(
            *MiniCorpus.args(
                built, work, "sql", "Data Source=plugins/mini.db",
                "SELECT id, v FROM MINI_T", "--sandbox", sandbox.toString(),
            ),
        )
        assertEquals(0, sqlCode, sqlOut)
        assertTrue("row[0]=1|42" in sqlOut, "sql 必须读回同一库同一行: $sqlOut")

        // 固定沙箱 = 不建临时 save-sandbox-*；否则第二次进程会读到空库。
        val tempSandboxes = Files.list(work).use { s ->
            s.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("save-sandbox-") }.count()
        }
        assertEquals(0, tempSandboxes, "显式 --sandbox 不得再建临时沙箱")
    }

    @Test
    fun preCallRunsStartupChainInSameSession() {
        val sandbox = work.resolve("sb-pre")
        val (code, text) = run(
            *MiniCorpus.args(
                built, work, "call", "MINI_DB_MAX_CALL",
                "--pre", "MINI_DB_INIT", "--sandbox", sandbox.toString(),
            ),
        )
        assertEquals(0, code, text)
        assertTrue("preCall[0]=MINI_DB_INIT status=PASS" in text, "前置链必须在同会话先跑: $text")
        assertTrue("status=PASS" in text && "target=MINI_DB_MAX_CALL" in text, text)
        assertTrue(Files.isRegularFile(sandbox.resolve("sql/plugins/mini.db")), "库必须落在固定沙箱")
    }

    @Test
    fun setsApplyBeforeCall() {
        val (setCode, setOut) = run(
            *MiniCorpus.args(built, work, "call", "MINI_TOTAL", "--sets", "GLOBAL_TOTAL=7"),
        )
        assertEquals(0, setCode, setOut)
        assertTrue(setOut.lines().any { it.trim() == "7" }, "--sets 必须在调用前写入 GLOBAL_TOTAL: $setOut")
    }

    @Test
    fun sandboxOutsideWorkIsRejected() {
        val outside = tmp.resolve("outside")
        val (code, _, err) = runWithErr(
            *MiniCorpus.args(
                built, work, "call", "MINI_PASS", "--sandbox", outside.toString(),
            ),
        )
        assertEquals(1, code, err)
        assertTrue("--sandbox 必须是 --work 目录下的子目录" in err, "出 --work 根必须拒绝: $err")
        assertTrue(!Files.exists(outside), "拒绝时不得创建出根目录: $outside")
    }
}
