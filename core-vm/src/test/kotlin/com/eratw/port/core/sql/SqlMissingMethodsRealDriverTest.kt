package com.eratw.port.core.sql

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P1 缺失 SQL 内建的**真实 SQLite**定向测试。
 *
 * 驱动是 [RealSqlDriver]（`androidx.sqlite:sqlite-bundled-jvm`，SQLite 3.46.0），不是
 * `SqlMethodsTest` 里的 fake 端口回放；文件能力用生产 `RootedFileOps`，所以
 * `SQL_IMPORT_DT_XML` / `SQL_EXPORT_*` / `SQL_IMPORT_XML_CUSTOM` 真的落盘再读回。
 *
 * 覆盖矩阵：
 * - 成功：9 个新函数各至少一条端到端路径（DT 导出→回灌、MAP 导出、custom 导入）；
 * - 无效连接：Execute/Scalar 抛 CodeEE，Import/Export 返 0（上游 `SqlManager` 口径）；
 * - 无效 reader：`SQL_READER_GET_FLOAT` 抛 CodeEE「无效的 Reader ID」；
 * - 文件缺失：DT 抛 CodeEE，通用导入返 0；
 * - 错误 SQL：scalar / MAP 导出 / DT 导出 / DT 导入都包 CodeEE 并保留 cause。
 */
class SqlMissingMethodsRealDriverTest {

    @Test
    fun `SQL_CONNECTION_OPEN 落盘重开并执行 WAL 且 SQL_DISCONNECT 移除连接`(@TempDir root: Path) {
        val env = RealSqlEnv(root)

        assertEquals(1L, intOf(env.ctx, "SQL_CONNECTION_OPEN", q("CONN")))
        assertTrue(Files.isRegularFile(root.resolve("CONN.db")), "库文件应落在宿主写根")
        assertEquals("wal", scalarString(env, "CONN", "PRAGMA journal_mode"))
        exec(env, "CONN", "CREATE TABLE T (v INTEGER)")
        exec(env, "CONN", "INSERT INTO T (v) VALUES (@0)", n(7))

        // 上游 ConnectionOpen 对同名连接先关再开；数据要能跨重开读回。
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECTION_OPEN", q("CONN")))
        assertEquals(2, env.driver.opened.size, "同名连接必须重开：${env.driver.opened}")
        assertEquals(7L, scalarLong(env, "CONN", "SELECT v FROM T"))

        assertEquals(1L, intOf(env.ctx, "SQL_DISCONNECT", q("CONN")))
        assertEquals(1L, intOf(env.ctx, "SQL_DISCONNECT", q("CONN")), "重复断开是 no-op、恒返回 1")
        val error = assertFailsWith<CodeEE> {
            scalarLong(env, "CONN", "SELECT 1")
        }
        assertTrue(error.message!!.contains("不存在"), error.message!!)
    }

    @Test
    fun `SQL_CONNECTION_OPEN 拒绝路径名与双点 且打开失败包 CodeEE 保留 cause`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        for (bad in listOf("", " ", "../escape", "a/b", "a:b", "a=b", "a\\b")) {
            val error = assertFailsWith<CodeEE> {
                intOf(env.ctx, "SQL_CONNECTION_OPEN", q(bad))
            }
            assertTrue(error.message!!.contains("无效或不安全的数据库名称"), "$bad -> ${error.message}")
        }
        assertTrue(env.driver.opened.isEmpty(), "非法名字不得触碰驱动")

        // 目录占住 <name>.db，真实 bundled 驱动打开失败 → CodeEE 且 cause 是 SqlNativeException。
        Files.createDirectories(root.resolve("BLOCKED.db"))
        val error = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_CONNECTION_OPEN", q("BLOCKED"))
        }
        assertTrue(error.message!!.contains("SQL_CONNECTION_OPEN"), error.message!!)
        assertTrue(error.cause is SqlNativeException, "cause=${error.cause}")
    }

    @Suppress("LongMethod")
    @Test
    fun `SCALAR_FLOAT 与参数化 SCALAR_FLOAT 读真实 REAL 文本与 NULL`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        connect(env, "DB")
        exec(env, "DB", "CREATE TABLE F (id INTEGER PRIMARY KEY, v REAL, t TEXT)")
        exec(env, "DB", "INSERT INTO F (id, v, t) VALUES (@0, @1, @2)", n(1), q("1.5"), null)
        exec(env, "DB", "INSERT INTO F (id, v, t) VALUES (@0, @1, @2)", n(2), null, q("2.25"))

        assertEquals(
            1.5,
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELECT v FROM F WHERE id = 1")),
            1e-9,
        )
        assertEquals(
            42.0,
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELECT 42")),
            1e-9,
        )
        assertEquals(
            2.25,
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELECT t FROM F WHERE id = 2")),
            1e-9,
        )
        assertEquals(
            0.0,
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELECT v FROM F WHERE id = 2")),
            1e-9,
        )
        assertEquals(
            0.0,
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELECT v FROM F WHERE id = 99")),
            1e-9,
        )
        assertEquals(
            1.5,
            floatOf(
                env.ctx, "SQL_P_EXECUTE_SCALAR_FLOAT", q("DB"),
                q("SELECT v FROM F WHERE id = @0"), n(1),
            ),
            1e-9,
        )

        val syntax = assertFailsWith<CodeEE> {
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELEC 1"))
        }
        assertTrue(syntax.message!!.contains("SELEC 1"), syntax.message!!)
        assertTrue(syntax.cause is SqlNativeException, "cause=${syntax.cause}")

        val badText = assertFailsWith<CodeEE> {
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELECT 'abc'"))
        }
        assertTrue(badText.message!!.contains("首列不是数值"), badText.message!!)

        val unconnected = assertFailsWith<CodeEE> {
            floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q("ABSENT"), q("SELECT 1"))
        }
        assertTrue(unconnected.message!!.contains("不存在"), unconnected.message!!)

        assertFailsWith<CodeEE> {
            floatOf(env.ctx, "SQL_P_EXECUTE_SCALAR_FLOAT", q("ABSENT"), q("SELECT @0"), n(1))
        }
        val paramSyntax = assertFailsWith<CodeEE> {
            floatOf(env.ctx, "SQL_P_EXECUTE_SCALAR_FLOAT", q("DB"), q("SELEC @0"), n(1))
        }
        assertTrue(paramSyntax.message!!.contains("SELEC @0"), paramSyntax.message!!)
        assertTrue(paramSyntax.cause is SqlNativeException, "cause=${paramSyntax.cause}")
    }

    @Test
    fun `SQL_READER_GET_FLOAT 读 REAL 整数文本与 NULL 且无效 reader 抛 CodeEE`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        connect(env, "DB")
        exec(env, "DB", "CREATE TABLE R (id INTEGER PRIMARY KEY, v REAL, t TEXT)")
        exec(env, "DB", "INSERT INTO R (id, v, t) VALUES (@0, @1, @2)", n(1), q("1.5"), q("3.25"))
        exec(env, "DB", "INSERT INTO R (id, v, t) VALUES (@0, @1, @2)", n(2), null, null)

        val handle = intOf(env.ctx, "SQL_EXECUTE_READER", q("DB"), q("SELECT id, v, t FROM R ORDER BY id"))
        assertEquals(1L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        assertEquals(1.0, floatOf(env.ctx, "SQL_READER_GET_FLOAT", n(handle), n(0)), 1e-9)
        assertEquals(1.5, floatOf(env.ctx, "SQL_READER_GET_FLOAT", n(handle), n(1)), 1e-9)
        assertEquals(3.25, floatOf(env.ctx, "SQL_READER_GET_FLOAT", n(handle), n(2)), 1e-9)
        assertEquals("1.5", strOf(env.ctx, "SQL_READER_GET_STRING", n(handle), n(1)))

        assertEquals(1L, intOf(env.ctx, "SQL_READER_READ", n(handle)))
        assertEquals(0.0, floatOf(env.ctx, "SQL_READER_GET_FLOAT", n(handle), n(1)), 1e-9)
        assertEquals(0.0, floatOf(env.ctx, "SQL_READER_GET_FLOAT", n(handle), n(2)), 1e-9)
        assertEquals(0L, intOf(env.ctx, "SQL_READER_READ", n(handle)))

        val invalid = assertFailsWith<CodeEE> {
            floatOf(env.ctx, "SQL_READER_GET_FLOAT", n(999), n(0))
        }
        assertTrue(invalid.message!!.contains("无效的 Reader ID：999"), invalid.message!!)
        assertEquals(1L, intOf(env.ctx, "SQL_READER_CLOSE", n(handle)))
    }

    @Test
    fun `SQL_EXPORT_MAP_XML 导出 k 转义与 v 原文并可回读`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        connect(env, "DB")
        exec(env, "DB", "CREATE TABLE MAP (k TEXT PRIMARY KEY, v TEXT)")
        exec(env, "DB", "INSERT INTO MAP (k, v) VALUES (@0, @1)", q("A&<"), q("<b>x</b>"))
        exec(env, "DB", "INSERT INTO MAP (k, v) VALUES (@0, @1)", q("B"), null)

        assertEquals(
            1L,
            intOf(env.ctx, "SQL_EXPORT_MAP_XML", q("DB"), q("MAP"), q("out/map.xml")),
        )
        val text = Files.readString(root.resolve("out/map.xml"))
        assertTrue(text.contains("<k>A&amp;&lt;</k><v><b>x</b></v>"), text)
        assertTrue(text.contains("<k>B</k><v></v>"), text)

        assertEquals(0L, intOf(env.ctx, "SQL_EXPORT_MAP_XML", q("ABSENT"), q("MAP"), q("out/x.xml")))
        val missing = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_EXPORT_MAP_XML", q("DB"), q("NO_TABLE"), q("out/x.xml"))
        }
        assertTrue(missing.message!!.contains("导出 MAP XML 失败"), missing.message!!)
        assertTrue(missing.cause is SqlNativeException, "cause=${missing.cause}")
    }

    @Test
    fun `SQL_EXPORT_DT_XML 与 SQL_IMPORT_DT_XML 真实 schema 与数据回灌`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        connect(env, "DB")
        exec(env, "DB", "CREATE TABLE DOC (id INTEGER PRIMARY KEY, name TEXT, score REAL)")
        exec(env, "DB", "INSERT INTO DOC (id, name, score) VALUES (@0, @1, @2)", n(1), q("甲"), q("1.5"))
        exec(env, "DB", "INSERT INTO DOC (id, name, score) VALUES (@0, @1, @2)", n(2), q("乙"), null)

        assertEquals(
            1L,
            intOf(
                env.ctx, "SQL_EXPORT_DT_XML", q("DB"), q("DOC"),
                q("out/schema.xsd"), q("out/data.xml"),
            ),
        )
        val schema = Files.readString(root.resolve("out/schema.xsd"))
        val data = Files.readString(root.resolve("out/data.xml"))
        assertTrue(schema.contains("name=\"DOC\""), schema)
        assertTrue(schema.contains("name=\"id\""), schema)
        assertTrue(schema.contains("msdata:DataType=\"System.Int64\""), schema)
        assertTrue(data.contains("<DOC>"), data)
        assertTrue(data.contains("<name>甲</name>"), data)

        connect(env, "IMP")
        assertEquals(
            1L,
            intOf(
                env.ctx, "SQL_IMPORT_DT_XML", q("IMP"), q("DOC"),
                q("out/schema.xsd"), q("out/data.xml"),
            ),
        )
        assertEquals(2L, scalarLong(env, "IMP", "SELECT COUNT(*) FROM DOC"))
        assertEquals("甲", scalarString(env, "IMP", "SELECT name FROM DOC WHERE id = 1"))
        assertEquals(1.5, scalarFloat(env, "IMP", "SELECT score FROM DOC WHERE id = 1"), 1e-9)
        assertEquals(0.0, scalarFloat(env, "IMP", "SELECT score FROM DOC WHERE id = 2"), 1e-9)

        assertEquals(0L, intOf(env.ctx, "SQL_EXPORT_DT_XML", q("ABSENT"), q("DOC"), q("a.xsd"), q("a.xml")))
        assertEquals(0L, intOf(env.ctx, "SQL_IMPORT_DT_XML", q("ABSENT"), q("DOC"), q("a.xsd"), q("a.xml")))
        val missingTable = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_EXPORT_DT_XML", q("DB"), q("NO_TABLE"), q("a.xsd"), q("a.xml"))
        }
        assertTrue(missingTable.message!!.contains("导出 DT XML 失败"), missingTable.message!!)
    }

    @Test
    fun `SQL_IMPORT_DT_XML 文件缺失坏 XML 空 schema 与 SQL 失败都包 CodeEE`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        connect(env, "DB")
        Files.createDirectories(root.resolve("in"))
        Files.writeString(root.resolve("in/bad.xsd"), "<xs:schema")
        Files.writeString(root.resolve("in/empty.xsd"), "<xs:schema />")
        Files.writeString(
            root.resolve("in/good.xsd"),
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="DOC">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="id" type="xs:long" />
                    <xs:element name="name" type="xs:string" />
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """.trimIndent(),
        )
        Files.writeString(root.resolve("in/bad-data.xml"), "<DocumentElement><DOC>")

        val schemaMissing = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_DT_XML", q("DB"), q("DOC"), q("in/none.xsd"), q("in/none.xml"))
        }
        assertTrue(schemaMissing.message!!.contains("找不到架构文件"), schemaMissing.message!!)

        val dataMissing = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_DT_XML", q("DB"), q("DOC"), q("in/good.xsd"), q("in/none.xml"))
        }
        assertTrue(dataMissing.message!!.contains("找不到数据文件"), dataMissing.message!!)

        val badSchema = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_DT_XML", q("DB"), q("DOC"), q("in/bad.xsd"), q("in/bad-data.xml"))
        }
        assertTrue(badSchema.message!!.contains("XML 解析失败"), badSchema.message!!)

        val emptySchema = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_DT_XML", q("DB"), q("DOC"), q("in/empty.xsd"), q("in/bad-data.xml"))
        }
        assertTrue(emptySchema.message!!.contains("没有找到列定义"), emptySchema.message!!)

        val badData = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_DT_XML", q("DB"), q("DOC"), q("in/good.xsd"), q("in/bad-data.xml"))
        }
        assertTrue(badData.message!!.contains("XML 解析失败"), badData.message!!)

        // 预置一张带 CHECK 的表，让 CREATE IF NOT EXISTS 通过、INSERT OR REPLACE 真失败。
        exec(env, "DB", "CREATE TABLE DOC (id INTEGER PRIMARY KEY, name TEXT CHECK (name <> 'bad'))")
        Files.writeString(
            root.resolve("in/bad-value.xml"),
            "<DocumentElement><DOC><id>1</id><name>bad</name></DOC></DocumentElement>",
        )
        val sqlError = assertFailsWith<CodeEE> {
            intOf(env.ctx, "SQL_IMPORT_DT_XML", q("DB"), q("DOC"), q("in/good.xsd"), q("in/bad-value.xml"))
        }
        assertTrue(sqlError.message!!.contains("流式导入 DT XML 失败"), sqlError.message!!)
        assertTrue(sqlError.cause is SqlNativeException, "cause=${sqlError.cause}")
    }

    @Suppress("LongMethod")
    @Test
    fun `SQL_IMPORT_XML_CUSTOM 导入属性文本与 innerXml 且缺文件返零`(@TempDir root: Path) {
        val env = RealSqlEnv(root)
        connect(env, "DB")
        Files.writeString(
            root.resolve("custom.xml"),
            """
            <root>
              <row id="7"><name>甲</name><payload><b>x</b></payload></row>
              <row id="8"><name>乙</name><payload /></row>
            </root>
            """.trimIndent(),
        )

        assertEquals(
            1L,
            intOf(
                env.ctx, "SQL_IMPORT_XML_CUSTOM", q("DB"), q("CUSTOM"), q("custom.xml"),
                q("/root/row"), q("id=@id,name=name,payload=payload(xml)"),
            ),
        )
        assertEquals("7", scalarString(env, "DB", "SELECT id FROM CUSTOM WHERE name = '甲'"))
        assertEquals("<b>x</b>", scalarString(env, "DB", "SELECT payload FROM CUSTOM WHERE name = '甲'"))
        assertEquals("8", scalarString(env, "DB", "SELECT id FROM CUSTOM WHERE name = '乙'"))

        assertEquals(
            0L,
            intOf(
                env.ctx, "SQL_IMPORT_XML_CUSTOM", q("DB"), q("CUSTOM"), q("none.xml"),
                q("/root/row"), q("id=@id"),
            ),
        )
        assertEquals(
            0L,
            intOf(
                env.ctx, "SQL_IMPORT_XML_CUSTOM", q("ABSENT"), q("CUSTOM"), q("custom.xml"),
                q("/root/row"), q("id=@id"),
            ),
        )

        val badMapping = assertFailsWith<CodeEE> {
            intOf(
                env.ctx, "SQL_IMPORT_XML_CUSTOM", q("DB"), q("CUSTOM"), q("custom.xml"),
                q("/root/row"), q("id"),
            )
        }
        assertTrue(badMapping.message!!.contains("列映射"), badMapping.message!!)

        Files.writeString(root.resolve("bad-custom.xml"), "<root><row>")
        val badXml = assertFailsWith<CodeEE> {
            intOf(
                env.ctx, "SQL_IMPORT_XML_CUSTOM", q("DB"), q("CUSTOM"), q("bad-custom.xml"),
                q("/root/row"), q("id=@id"),
            )
        }
        assertTrue(badXml.message!!.contains("XML 解析失败"), badXml.message!!)

        // 预置带 CHECK 的表：CREATE IF NOT EXISTS 通过，INSERT 真失败 → CodeEE + cause。
        exec(
            env, "DB",
            "CREATE TABLE CUSTOM_CHK (id TEXT, name TEXT CHECK (name <> 'bad'), payload TEXT)",
        )
        Files.writeString(
            root.resolve("bad-value-custom.xml"),
            "<root><row id=\"9\"><name>bad</name><payload /></row></root>",
        )
        val sqlError = assertFailsWith<CodeEE> {
            intOf(
                env.ctx, "SQL_IMPORT_XML_CUSTOM", q("DB"), q("CUSTOM_CHK"),
                q("bad-value-custom.xml"), q("/root/row"), q("id=@id,name=name,payload=payload(xml)"),
            )
        }
        assertTrue(sqlError.message!!.contains("自定义 XML 导入失败"), sqlError.message!!)
        assertTrue(sqlError.cause is SqlNativeException, "cause=${sqlError.cause}")
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    private fun connect(env: RealSqlEnv, name: String) {
        assertEquals(1L, intOf(env.ctx, "SQL_CONNECT", q(name)))
    }

    private fun exec(env: RealSqlEnv, db: String, sql: String, vararg params: Value?) {
        val args = ArrayList<Value?>(params.size + 2)
        args.add(q(db))
        args.add(q(sql))
        args.addAll(params)
        env.ctx.callFunction("SQL_P_EXECUTE_NONQUERY", args)
    }

    private fun scalarLong(env: RealSqlEnv, db: String, sql: String): Long =
        intOf(env.ctx, "SQL_EXECUTE_SCALAR_LONG", q(db), q(sql))

    private fun scalarString(env: RealSqlEnv, db: String, sql: String): String =
        strOf(env.ctx, "SQL_EXECUTE_SCALAR_STRING", q(db), q(sql))

    private fun scalarFloat(env: RealSqlEnv, db: String, sql: String): Double =
        floatOf(env.ctx, "SQL_EXECUTE_SCALAR_FLOAT", q(db), q(sql))
}

private class RealSqlEnv(root: Path) {
    val driver = RealSqlDriver(root)
    private val base = RealBaseCtx()
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(
            base,
            store,
            RealHost(RootedFileOps(root), driver),
        )
    }
}

private class RealBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("RealBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

private class RealHost(
    override val fileOps: FileOps,
    override val sqlDriver: SqlDriver,
) : BuiltinHost

private fun intOf(c: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (c.callFunction(name, args.toList()) as Value.IntV).v

private fun floatOf(c: BuiltinEvalContext, name: String, vararg args: Value): Double =
    (c.callFunction(name, args.toList()) as Value.FloatV).v

private fun strOf(c: BuiltinEvalContext, name: String, vararg args: Value): String =
    (c.callFunction(name, args.toList()) as Value.StrV).v

private fun q(s: String): Value = Value.StrV(s)

private fun n(v: Long): Value = Value.IntV(v)
