package com.eratw.port.modgen

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.modDeclarations
import com.eratw.port.modapi.registry.ModRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 路 A（Kotlin 载体 mod）作者面 minApi 的四段链路：
 * `modDeclarations(…, minApi = N)` → `ModManifest.minApi` → `ModuleDescriptor.minApi` →
 * `ManifestCodec.encode` 的版本选择（0 = 不升版 / 正数 = `/6` + `@min-api`）。
 *
 * 硬约束的反向证据也在这里：`minApi = 0` 时码点仍是 `/3` 且没有 `@min-api` 段 ⇒ 既有资产
 * 逐字节不变（资产真源由 `BuiltinManifestAssetTest` 逐字节钉住）。
 */
class MinApiAuthorFaceTest {

    private fun encoded(manifest: ModManifest): ByteArray =
        ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))

    private fun magicOf(manifest: ModManifest): String =
        String(encoded(manifest), Charsets.ISO_8859_1).lineSequence().first()

    @Test
    fun dslDefaultsToNoGate() {
        val manifest = modDeclarations("mod.dsl.default") { }

        assertEquals(0, manifest.minApi, "缺省 0 = 不设闸（与 ModuleDescriptor.minApi / pack 侧同口径）")
    }

    @Test
    fun dslMinApiReachesManifest() {
        val manifest = modDeclarations("mod.dsl.gate", minApi = 7) { }

        assertEquals(7, manifest.minApi, "DSL 形参应原样落进 ModManifest.minApi")
    }

    @Test
    fun dslNegativeMinApiRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException>("负数应在作者写的那一行当场抛") {
            modDeclarations("mod.dsl.bad", minApi = -1) { }
        }
    }

    @Test
    fun manifestConstructorRejectsNegativeMinApi() {
        assertFailsWith<IllegalArgumentException> {
            ModManifest(packageId = "mod.direct.bad", minApi = -2)
        }
    }

    @Test
    fun adapterCopiesMinApiIntoDescriptor() {
        assertEquals(9, ModDeclarationAdapter.toDescriptor(ModManifest(packageId = "mod.adapter", minApi = 9)).minApi)
        assertEquals(0, ModDeclarationAdapter.toDescriptor(ModManifest(packageId = "mod.adapter.plain")).minApi)
    }

    @Test
    fun noGateStaysOnSlashThreeBytes() {
        val manifest = modDeclarations("mod.dsl.nogate") { }
        val text = String(encoded(manifest), Charsets.ISO_8859_1)

        assertEquals("RT2MANIFEST/3", text.lineSequence().first(), "minApi = 0 时线格式版本不得升")
        assertFalse(text.contains("@min-api"), "minApi = 0 时不得写 min-api 段：$text")
    }

    @Test
    fun noGateBytesEqualLegacyShape() {
        val fromDsl = encoded(modDeclarations("mod.byte.same") { })
        val explicitZero = encoded(ModManifest(packageId = "mod.byte.same", minApi = 0))

        assertContentEquals(explicitZero, fromDsl, "0/缺省两条路径必须产出同一份字节")
    }

    @Test
    fun positiveMinApiRaisesToSlashSixAndWritesSection() {
        val manifest = ModManifest(packageId = "mod.gate", minApi = 12)
        val text = String(encoded(manifest), Charsets.ISO_8859_1)

        assertTrue(text.startsWith("RT2MANIFEST/6\n"), "minApi > 0 才升 /6：$text")
        assertTrue(text.contains("@min-api\nI12\n"), "应写 @min-api 段且值为 12：$text")
    }

    @Test
    fun positiveMinApiRoundTripsThroughDecoder() {
        val descriptor = ManifestCodec.decode(encoded(ModManifest(packageId = "mod.gate.rt", minApi = 5)))

        assertEquals(5, descriptor.minApi, "解码端应还原 min-api")
        assertEquals("mod.gate.rt", descriptor.packageId)
    }

    @Test
    fun builtinCarriersAllDeclareNoGate() {
        assertTrue(ModRegistry.carriers.isNotEmpty(), "注册表为空说明构建期扫描失效")
        ModRegistry.carriers.forEach { carrier ->
            assertEquals(0, carrier.manifest.minApi, "${carrier.packageId} 不设闸 ⇒ 资产字节不许变")
            assertFalse(
                magicOf(carrier.manifest).endsWith("/6"),
                "${carrier.packageId} 没有 min-api 声明，产物不得升 /6：${magicOf(carrier.manifest)}",
            )
        }
    }
}
