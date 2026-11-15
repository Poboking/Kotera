package com.eratw.port.erbcc

import com.eratw.port.core.proc.EngineProfiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `--engine-profile` / `--engine-report` 的参数面判据（B6 ENGC-3b，契约 ENGCOMPAT §8.0.2/8.0.3）。
 *
 * 错误参数路径在 CLI 层走 `err()`（exitProcess 会带测试 JVM 一起退出，不能在进程内断言），
 * 因此判据钉在单点解析函数 [parseEngineProfileArg] / [engineReportArgError] 上：CLI 各命令
 * 的参数循环都经它们，返回值形状即 CLI 的报错正文与退出码档位（Bad → 退出码 2）。
 */
class EngineProfileArgsTest {

    // ==================================================================
    // 1) parseEngineProfileArg：三档映射 / 未知 id 支持表 / Absent 零过滤
    // ==================================================================

    @Test
    fun `三个合法 profileId 解析到 EngineProfiles 权威档位`() {
        val cases = mapOf(
            "snake_4981" to com.eratw.port.core.compat.EngineFamily.SNAKE_4981,
            "em_ee_1824_v22" to com.eratw.port.core.compat.EngineFamily.EM_EE_1824_V22,
            "v24_1824" to com.eratw.port.core.compat.EngineFamily.V24_1824,
        )
        for ((id, family) in cases) {
            val arg = parseEngineProfileArg(id)
            assertTrue(arg is EngineProfileArg.Present, "合法 id '$id' 必须解析成功")
            assertEquals(family, arg.view.family, "profileId '$id' 的家族映射必须来自 EngineProfiles.TARGETS")
            assertEquals(id, arg.view.target.profileId)
        }
    }

    @Test
    fun `未知 profileId 返回 Bad 且正文列出完整支持表`() {
        val arg = parseEngineProfileArg("honke")
        assertTrue(arg is EngineProfileArg.Bad, "未知 id 必须返回 Bad")
        val msg = arg.message
        // 支持表三行（与 EngineProfiles.TARGETS 同序），工装面文案允许直排。
        assertEquals("未知的 --engine-profile 档位: 'honke'（支持表:）", msg.substringBefore("\n"))
        EngineProfiles.TARGETS.forEach { t ->
            assertTrue(t.profileId in msg, "支持表必须含 ${t.profileId}")
            assertTrue(t.engine in msg, "支持表必须含 engine=${t.engine}")
            assertTrue(t.version in msg, "支持表必须含 version=${t.version}")
        }
    }

    @Test
    fun `未给 flag 解析为 Absent 即零过滤档`() {
        assertEquals(EngineProfileArg.Absent, parseEngineProfileArg(null))
    }

    // ==================================================================
    // 1b) help 正文的档位名单：从 EngineProfiles.TARGETS 渲染（ENGCPL E1 的 erbcc 面）
    // ==================================================================

    @Test
    fun `帮助正文列出 EngineProfiles_TARGETS 的每个 profileId`() {
        val help = usageText()
        // 加一家引擎/一档 = 只改 input yaml + 重生成；本用例让「加档不用改 Main.kt」
        // 成为可判事实：缺一个档的 id，这里立刻红。
        assertTrue(EngineProfiles.TARGETS.isNotEmpty(), "EngineProfiles.TARGETS 不许为空")
        for (t in EngineProfiles.TARGETS) {
            assertTrue(t.profileId in help, "help 正文必须含档位 ${t.profileId}：\n$help")
        }
        // 名单是运行时渲染而不是手抄一段：整行必须恰是 TARGETS 的 join，"snake_4981 / …"。
        val expected = "EngineProfiles.TARGETS（" +
            EngineProfiles.TARGETS.joinToString(" / ") { it.profileId } + "）"
        assertTrue(expected in help, "help 的 profile_id 行必须由 TARGETS 渲染：期望含 '$expected'\n$help")
    }

    // ==================================================================
    // 2) engineReportArgError：报告必须与档位同用
    // ==================================================================

    @Test
    fun `report 无 profile 是参数错误`() {
        val msg = engineReportArgError(reportGiven = true, profileGiven = false)
        assertTrue(msg != null, "--engine-report 不带 --engine-profile 必须报错")
        assertTrue("--engine-profile" in msg!!, "报错正文要点名配套 flag")
    }

    @Test
    fun `report 与 profile 同用或缺省组合都合法`() {
        assertEquals(null, engineReportArgError(reportGiven = true, profileGiven = true))
        assertEquals(null, engineReportArgError(reportGiven = false, profileGiven = true))
        assertEquals(null, engineReportArgError(reportGiven = false, profileGiven = false))
    }

    // ==================================================================
    // 3) EngineReport.writeJson：§8.0.3 形状逐字段
    // ==================================================================

    @Test
    fun `报告 JSON 符合 8_0_3 契约形状`(@TempDir dir: Path) {
        val report = EngineReport(
            profile = "v24_1824",
            engine = "v24",
            version = "1824+v24",
            corpusRoot = Path("C:/game/root").toAbsolutePath().normalize(),
            files = 2,
            compiled = 2,
            parseFailures = 1,
            unknownCommands = listOf(EngineReportRow("SETBGIMAGE", "ERB/snake_only.erb", 2)),
            unknownFunctions = emptyList(),
        )
        val out = dir.resolve("engine-report.json")
        report.writeJson(out)
        val json = out.readText()
        // 字段名与嵌套以此为准（§8.0.3），渲染器按此消费。
        assertTrue("\"schema\": 1" in json, "schema 必须是 1：\n$json")
        assertTrue("\"profile\": \"v24_1824\"" in json, "profile 字段：\n$json")
        assertTrue("\"engine\": \"v24\"" in json, "engine 字段：\n$json")
        assertTrue("\"version\": \"1824+v24\"" in json, "version 字段：\n$json")
        assertTrue("\"corpus\": " in json, "corpus 字段：\n$json")
        assertTrue(
            "\"counts\": {\"files\": 2, \"compiled\": 2, \"parseFailures\": 1, " +
                "\"unknownCommands\": 1, \"unknownFunctions\": 0}" in json,
            "counts 五项必须齐全且与数组条数一致：\n$json",
        )
        assertTrue(
            "\"name\": \"SETBGIMAGE\", \"file\": \"ERB/snake_only.erb\", \"line\": 2" in json,
            "unknownCommands 条目形状 name+file+line：\n$json",
        )
        assertTrue("\"unknownFunctions\": [" in json, "unknownFunctions 数组恒在：\n$json")
    }

    @Test
    fun `报告 JSON 的空数组与相对路径正斜杠口径`(@TempDir dir: Path) {
        val report = EngineReport(
            profile = "snake_4981",
            engine = "snake",
            version = "4.981",
            corpusRoot = Path("/corpus").toAbsolutePath().normalize(),
            files = 1,
            compiled = 1,
            parseFailures = 0,
            unknownCommands = emptyList(),
            unknownFunctions = listOf(EngineReportRow("FOO_BAR", "ERB\\back.erb", 7)),
        )
        val out = dir.resolve("sub").resolve("report.json")
        report.writeJson(out)
        assertTrue(Files.isRegularFile(out), "父目录不存在时必须自动创建")
        val json = out.readText()
        assertTrue("\"unknownCommands\": []" in json, "空 unknownCommands 数组：\n$json")
        assertTrue("\"file\": \"ERB/back.erb\"" in json, "相对路径必须归一为正斜杠：\n$json")
    }
}
