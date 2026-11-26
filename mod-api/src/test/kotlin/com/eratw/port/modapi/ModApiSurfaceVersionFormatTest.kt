package com.eratw.port.modapi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 格式同源哨兵（文本级，不依赖 tools/modgen 上 classpath）。
 *
 * `:mod-api` 的测试不该反向依赖 `:tools:modgen`（工具层依赖作者面、不是反过来），
 * 但两处解析同一份 `ModApi.kt` 的 VERSION 常量与 KDoc 版本表，正则一旦各自漂移，
 * check-mod 与作者面快照闸就会对同一份源码给出不同结论。故用「读源码文本 + 找同字面量」
 * 的哨兵钉住：改任一侧的 `VERSION_CONST` / `VERSION_ENTRY` 正则，本用例先红。
 */
class ModApiSurfaceVersionFormatTest {

    @Test
    fun `哨兵 版本常量正则与 check-mod 的 ModCheckApiSurface 逐字同源`() {
        val modgen = readModgenApiSurface()
        val expected = ModApiSurfaceGate.regexDeclarationLine(
            "VERSION_CONST", ModApiSurfaceGate.VERSION_CONST_PATTERN
        )
        assertTrue(modgen.contains(expected), "ModCheckApiSurface 的 VERSION_CONST 与 mod-api 闸不同源：$expected")
    }

    @Test
    fun `哨兵 版本表段正则与 check-mod 的 ModCheckApiSurface 逐字同源`() {
        val modgen = readModgenApiSurface()
        val expected = ModApiSurfaceGate.regexDeclarationLine(
            "VERSION_ENTRY", ModApiSurfaceGate.VERSION_ENTRY_PATTERN
        )
        assertTrue(modgen.contains(expected), "ModCheckApiSurface 的 VERSION_ENTRY 与 mod-api 闸不同源：$expected")
    }

    @Test
    fun `哨兵 KDoc 行清洗与 check-mod 的星号口径一致`() {
        val modgen = readModgenApiSurface()
        val starDeclaration = "KDOC_STAR = \"" + "*" + "\""
        assertTrue(modgen.contains(starDeclaration), "ModCheckApiSurface 的 KDoc 星号常量变了：$starDeclaration")
        assertEquals("`12` = something", ModApiSurfaceGate.cleanKdocLine("   * `12` = something"))
    }

    @Test
    fun `哨兵 快照头生成与解析互逆且与任务约定格式一致`() {
        val version = ModApi.VERSION
        val header = ModApiSurfaceGate.headerLine(version)
        assertEquals("// surface v" + version, header)
        assertEquals(version, ModApiSurfaceGate.headerVersion(header))
        assertEquals(version, ModApiSurfaceGate.headerVersion(ModApiSurfaceSupport.renderSurface()))
    }

    private fun readModgenApiSurface(): String =
        Files.readString(ModApiSurfaceSupport.repoRoot().resolve(MODGEN_API_SURFACE))

    private companion object {
        private const val MODGEN_API_SURFACE =
            "tools/modgen/src/main/kotlin/com/eratw/port/modgen/ModCheckApiSurface.kt"
    }
}
