package com.eratw.port.modgen

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 包级元数据写出面（pack-mod 侧）：条目内容、确定性、降级面、JSON 转义。
 *
 * 读侧契约在 app 的 `ModImportPackMetaEndToEndTest`（跨 Gradle 模块无法共享常量，两侧 KDoc
 * 互相点名 [PACK_META_ENTRY_NAME] / [PackModMeta]）。本文件只用真 CLI 入口 `PackMod.runCli`
 * 与真 zip 产物断言，不手搓元数据字节自证。
 */
class PackModMetaTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `打包写元数据条目_身份取自ModCarrier声明`() {
        val module = packableModule(tmp)
        val zip = outZip(tmp)

        val run = packOk(module, zip)

        assertEquals(
            "pack-mod: 元数据 $PACK_META_ENTRY_NAME（packageId=$TEST_PACKAGE_ID / version=0.1）",
            run.out.lines().first { it.startsWith("pack-mod: 元数据") },
            "摘要另起一行报元数据",
        )
        assertTrue(PACK_META_ENTRY_NAME in entryNames(zip), "元数据条目在 zip 根：${entryNames(zip)}")
        assertContentEquals(
            expectedJson(TEST_PACKAGE_ID, "0.1"),
            entryBytes(zip, PACK_META_ENTRY_NAME),
        )
        // 摘要的「条目 N」仍只数模块内容（源 1 / 资源 1 = 骨架 README），元数据不掺进计数。
        assertTrue(run.out.contains("pack-mod: 条目 2（源 1 / 资源 1）"), run.out)
    }

    @Test
    fun `两次打包_含元数据条目后字节仍完全相同`() {
        val module = packableModule(tmp)
        val first = outZip(tmp, "out/a.zip")
        val second = outZip(tmp, "out/b.zip")

        assertEquals(sha256Of(packOk(module, first)), sha256Of(packOk(module, second)), "同输入两次打包必须逐字节相同")
        assertContentEquals(
            entryBytes(first, PACK_META_ENTRY_NAME),
            entryBytes(second, PACK_META_ENTRY_NAME),
            "元数据条目本身必须确定",
        )
    }

    @Test
    fun `自述身份抽取_与check同一套声明解析`() {
        val module = packableModule(tmp)
        val sources = ModCheckSources.scan(module, mutableListOf())

        val meta = assertTrueNotNull(ModCheckDeclarations.declaredPackMeta(sources))

        assertEquals(TEST_PACKAGE_ID, meta.packageId)
        assertEquals("0.1", meta.version)
    }

    @Test
    fun `没有carrier声明_不写元数据`() {
        assertNull(ModCheckDeclarations.declaredPackMeta(emptyList()), "没有可解析的 @ModCarrier ⇒ 不写元数据")
    }

    @Test
    fun `版本形态闸_与导入端接纳面同界`() {
        assertTrue(PackModMeta.versionAccepted("0.1"))
        assertTrue(PackModMeta.versionAccepted("2.0-beta"))
        assertFalse(PackModMeta.versionAccepted(""), "空版本号非法")
        assertFalse(PackModMeta.versionAccepted("v".repeat(PACK_META_MAX_VERSION_LENGTH + 1)), "超长非法")
        assertFalse(PackModMeta.versionAccepted("2.0\u0001"), "控制字符非法")
        assertFalse(PackModMeta.versionAccepted("2.0\u007F"), "DEL 非法")
    }

    @Test
    fun `元数据JSON_固定键序且转义引号反斜杠控制字符`() {
        assertEquals(
            "{\"schema\":1,\"packageId\":\"eratw.mymod\",\"version\":\"1.0\"}",
            PackModMeta.json("eratw.mymod", "1.0").toString(Charsets.UTF_8),
        )
        assertEquals(
            "{\"schema\":1,\"packageId\":\"eratw.mymod\",\"version\":\"a\\\"b\\\\c\"}",
            PackModMeta.json("eratw.mymod", "a\"b\\c").toString(Charsets.UTF_8),
            "引号/反斜杠必须转义，否则写出坏 JSON",
        )
        assertEquals(
            "{\"schema\":1,\"packageId\":\"eratw.mymod\",\"version\":\"\\u0001\"}",
            PackModMeta.json("eratw.mymod", "\u0001").toString(Charsets.UTF_8),
            "控制字符走 \\u00XX 转义（不进裸控制字符）",
        )
    }

    private fun expectedJson(packageId: String, version: String): ByteArray =
        "{\"schema\":1,\"packageId\":\"$packageId\",\"version\":\"$version\"}".toByteArray(Charsets.UTF_8)

    private fun <T : Any> assertTrueNotNull(value: T?): T {
        assertTrue(value != null, "应能抽到自述身份")
        return value!!
    }
}
