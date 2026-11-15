package com.eratw.port.core.proc

import com.eratw.port.core.compat.EngineFamily
import com.eratw.port.core.compat.NicknameKeywordPolicy
import com.eratw.port.core.compat.StringCompareDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ENGC-2：构建期印章的三态与严格性。
 *
 * 判据（与 PLAN §2.4 的零干扰表逐条对应）：
 * - **缺失 = eraTW**：null ⇒ [EngineStampRead.Missing]，壳层据此恒取
 *   `LEGACY_ERATW_4981`；
 * - **合法 = 按档**：三档各装配出对应家族与方言，且 snake 档逐字段等于缺省档；
 * - **非法 = 判红**：未知档、引擎串/版本串不符、schema 不符、重复/未知缺字段、嵌套值、
 *   尾随内容全部 [EngineStampRead.Malformed]，**没有一条回落缺省档**（D14 取向：
 *   出包通道的错误不许伪装成兼容运行）。
 */
class EngineStampTest {

    private fun stamp(
        schema: String = "1",
        profile: String = "snake_4981",
        engine: String = "snake",
        version: String = "4.981",
    ): String = """{"schema": $schema, "profile": "$profile", "engine": "$engine", "version": "$version"}"""

    private fun present(text: String): EngineStampRead.Present =
        assertIs<EngineStampRead.Present>(EngineStamp.parse(text))

    private fun malformed(text: String): EngineStampRead.Malformed =
        assertIs<EngineStampRead.Malformed>(EngineStamp.parse(text))

    @Test
    fun `没有印章文件是 Missing`() {
        assertEquals(EngineStampRead.Missing, EngineStamp.parse(null))
    }

    @Test
    fun `合法 snake 印章装配成缺省档`() {
        val text = stamp()
        val read = present(text)
        assertEquals(RuntimeCompatibilityProfile.LEGACY_ERATW_4981, read.profile)
        assertEquals(text, read.raw)
    }

    @Test
    fun `合法 em_ee 与 v24 印章装配出各自方言`() {
        val em = present(
            stamp(profile = "em_ee_1824_v22", engine = "em_ee", version = "1824+v22+EMv18+EEv57"),
        )
        assertEquals(EngineFamily.EM_EE_1824_V22, em.profile.engineFamily)
        assertEquals(StringCompareDialect.FIXED, em.profile.stringCompare)
        assertEquals(NicknameKeywordPolicy.NAME_CALLNAME_ONLY, em.profile.nicknameKeywords)

        val v24 = present(stamp(profile = "v24_1824", engine = "v24", version = "1824+v24"))
        assertEquals(EngineFamily.V24_1824, v24.profile.engineFamily)
        assertEquals(StringCompareDialect.LEGACY_BOTH_LT, v24.profile.stringCompare)
        assertEquals(NicknameKeywordPolicy.WITH_NICKNAME_MASTERNAME, v24.profile.nicknameKeywords)
    }

    @Test
    fun `BOM 与首尾空白不影响解析且原文归一`() {
        val text = stamp()
        val read = present("\uFEFF \n" + text + "\n\t ")
        assertEquals(RuntimeCompatibilityProfile.LEGACY_ERATW_4981, read.profile)
        assertEquals(text, read.raw)
    }

    @Test
    fun `空串与纯空白是 Malformed`() {
        for (text in listOf("", "   ", "\n\t", "\uFEFF")) {
            val read = malformed(text)
            assertEquals("empty stamp", read.reason)
        }
    }

    @Test
    fun `非对象根与尾随内容判红`() {
        for (text in listOf(
            "[]",
            "\"snake_4981\"",
            "null",
            stamp() + "x",
            stamp() + stamp(),
        )) {
            assertTrue(malformed(text).reason.isNotBlank(), text)
        }
    }

    @Test
    fun `重复字段与未知字段判红`() {
        val duplicate = """{"schema":1,"schema":1,"profile":"snake_4981","engine":"snake","version":"4.981"}"""
        assertTrue(malformed(duplicate).reason.contains("duplicate key", ignoreCase = true))

        val unknown = """{"schema":1,"profile":"snake_4981","engine":"snake","version":"4.981","extra":1}"""
        val read = malformed(unknown)
        assertTrue(read.reason.contains("unknown key"))
        assertTrue(read.reason.contains("extra"))
    }

    @Test
    fun `缺任一必填字段判红`() {
        val fields = listOf(
            "schema" to "\"profile\":\"snake_4981\",\"engine\":\"snake\",\"version\":\"4.981\"",
            "profile" to "\"schema\":1,\"engine\":\"snake\",\"version\":\"4.981\"",
            "engine" to "\"schema\":1,\"profile\":\"snake_4981\",\"version\":\"4.981\"",
            "version" to "\"schema\":1,\"profile\":\"snake_4981\",\"engine\":\"snake\"",
        )
        for ((name, body) in fields) {
            val read = malformed("{$body}")
            assertTrue(read.reason.contains("missing field"), name)
            assertTrue(read.reason.contains(name), "缺的是 $name：${read.reason}")
        }
    }

    @Test
    fun `schema 非整数或版本不符判红`() {
        assertTrue(malformed(stamp(schema = "\"1\"")).reason.contains("integer"))
        assertTrue(malformed(stamp(schema = "1.0")).reason.contains("integer"))
        assertTrue(malformed(stamp(schema = "true")).reason.contains("only strings and integers"))
        assertTrue(malformed(stamp(schema = "2")).reason.contains("unsupported schema"))
    }

    @Test
    fun `未知档位判红并列出受支持档`() {
        val read = malformed(stamp(profile = "honke_1824"))
        assertTrue(read.reason.contains("unknown profile"))
        for (t in EngineProfiles.TARGETS) {
            assertTrue(read.reason.contains(t.profileId), "报错要给出支持表：${read.reason}")
        }
    }

    @Test
    fun `引擎串或版本串与档位不符判红`() {
        val engineMismatch = malformed(stamp(profile = "v24_1824", engine = "snake", version = "1824+v24"))
        assertTrue(engineMismatch.reason.contains("engine mismatch"))

        val versionMismatch = malformed(stamp(profile = "snake_4981", engine = "snake", version = "4.982"))
        assertTrue(versionMismatch.reason.contains("unsupported version"))
    }

    @Test
    fun `嵌套值与数组判红`() {
        for (value in listOf("{}", "[]", "[\"snake\"]")) {
            val text = """{"schema":1,"profile":$value,"engine":"snake","version":"4.981"}"""
            assertTrue(malformed(text).reason.contains("unsupported value"), text)
        }
    }

    @Test
    fun `字符串转义与边界`() {
        // 合法转义（\\u005f = `_`）解析后仍是同一档位串 ⇒ 按档装配（语法层不误杀）。
        val escaped = """{"schema":1,"profile":"snake\u005f4981","engine":"snake","version":"4.981"}"""
        assertEquals(RuntimeCompatibilityProfile.LEGACY_ERATW_4981, present(escaped).profile)

        assertTrue(malformed("""{"schema":1,"profile":"snake\q","engine":"snake","version":"4.981"}""")
            .reason.contains("unsupported escape"))
        assertTrue(
            malformed("{\"schema\":1,\"profile\":\"snake\t4981\",\"engine\":\"snake\",\"version\":\"4.981\"}")
                .reason.contains("control character"),
        )
        assertTrue(malformed("""{"schema":1,"profile":"snake_4981,"engine":"snake","version":"4.981"}""")
            .reason.isNotBlank())
    }

    @Test
    fun `非法印章保留原文不截断`() {
        val text = """{"schema":2,"profile":"snake_4981","engine":"snake","version":"4.981"}"""
        assertEquals(text, malformed(text).raw)
    }
}
