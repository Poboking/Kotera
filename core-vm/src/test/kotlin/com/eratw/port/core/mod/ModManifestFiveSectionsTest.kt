package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.OverridePolicy
import com.eratw.port.modapi.DimType
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PrivateDimDecl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-6 ③：`ModManifest` 五节声明入口（`external-calls` / `override-functions` / `private-dims` /
 * `exported-macros` / `artifact-sha256`）的端到端证明。
 *
 * 走的是**真实生产链**：`ModManifest` → `ModDeclarationAdapter.toDescriptor`（真适配器）→
 * `ManifestCodec.encode`（真 codec）→ `ManifestCodec.decode` → `DeclarationRegistry.freeze`
 * （真声明事务）。不用 fake 造键，所以「写错键也全绿」这个失败模式为 0：
 * 本文件任何一条红了，都说明五节里至少一节没真正到达它该到的消费面。
 *
 * 五节各自的消费面（本文件逐条钉住）：
 * - `override-functions` → `ModuleDescriptor.policyFor`（MODERN 档 `HAS_OVERRIDES` 判据，
 *   `LinkCandidateAssembler.kt:100`）；
 * - `private-dims` → `DeclarationRegistry.snapshot().privateDimsOf`（声明事务的包私有变量表）；
 * - `external-calls` / `exported-macros` / `artifact-sha256` → 解码后逐值原样（三者分别喂
 *   `ExternalImportTable`、`LinkFingerprint` 的 macros 行与 artifact 行）。
 */
class ModManifestFiveSectionsTest {

    private val sha = "0123456789abcdef".repeat(4)

    private fun fullManifest(): ModManifest = ModManifest(
        packageId = "mod.five",
        version = "1.0.0",
        externalCalls = listOf("KOJO_BASE", "SHOW_SHOP"),
        overrideFunctions = setOf("kojo_train", "KOJO_END"),
        privateDims = mapOf(
            "KOJO_TRAIN" to listOf(
                PrivateDimDecl("LOCAL_TMP", DimType.INT, listOf(4)),
                PrivateDimDecl("LOCAL_NAME", DimType.STR),
            ),
        ),
        exportedMacros = mapOf("FAMILY_LIMIT" to "8", "FAMILY_TAG" to "@家族"),
        artifactSha256 = sha,
    )

    @Test
    fun `五节经真实编解码逐值往返`() {
        val descriptor = ModDeclarationAdapter.toDescriptor(fullManifest())
        val decoded = ManifestCodec.decode(ManifestCodec.encode(descriptor))

        assertEquals(listOf("KOJO_BASE", "SHOW_SHOP"), decoded.externalCalls, "导入序有语义，必须原样保序")
        assertEquals(setOf("kojo_train", "KOJO_END"), decoded.overrideFunctions, "覆盖声明按原样往返（归一在读侧）")
        assertEquals(mapOf("FAMILY_LIMIT" to "8", "FAMILY_TAG" to "@家族"), decoded.exportedMacros)
        assertEquals(sha, decoded.artifactSha256)

        val dims = decoded.privateDims.getValue("KOJO_TRAIN")
        assertEquals(2, dims.size)
        assertEquals("LOCAL_TMP", dims[0].name)
        assertEquals(4, dims[0].dims.single())
        assertFalse(dims[0].isStr)
        assertFalse(dims[0].isGlobal, "私有 #DIM 必须是包私有命名空间")
        assertFalse(dims[0].isSavedata, "声明式私有变量不进存档")
        assertEquals("LOCAL_NAME", dims[1].name)
        assertTrue(dims[1].isStr)
        assertTrue(dims[1].dims.isEmpty(), "空维长 = 标量")
    }

    @Test
    fun `覆盖声明归一后可判 OVERWRITE 未声明则 FORBID`() {
        val decoded = ManifestCodec.decode(
            ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(fullManifest())),
        )
        assertEquals(OverridePolicy.OVERWRITE, decoded.policyFor("KOJO_TRAIN"), "大小写不敏感")
        assertEquals(OverridePolicy.OVERWRITE, decoded.policyFor("kojo_end"))
        assertEquals(OverridePolicy.FORBID, decoded.policyFor("KOJO_UNLISTED"), "没声明就拒绝，不猜意图")
    }

    @Test
    fun `私有 DIM 进真声明事务并按函数键可取`() {
        val descriptor = ModDeclarationAdapter.toDescriptor(fullManifest())
        val snapshot = DeclarationRegistry(
            builtinVariableNames = emptySet(),
            csvTableCapacities = emptyMap(),
            baseCsvKeywords = emptyMap(),
        ).addPackage(descriptor).freeze()

        val dims = snapshot.privateDimsOf("mod.five", "kojo_train")
        assertEquals(2, dims.size, "函数键按大写归一后仍可取到（读侧传小写）")
        assertEquals(listOf("LOCAL_TMP", "LOCAL_NAME"), dims.map { it.name })
        assertTrue(dims.none { it.isGlobal })
        assertTrue(snapshot.privateDimsOf("mod.five", "OTHER_FUNC").isEmpty())
    }

    @Test
    fun `产物 sha256 格式闸在翻译期拒绝`() {
        val bad = listOf(
            sha.uppercase() to "大写十六进制",
            sha.dropLast(1) to "63 位",
            sha.dropLast(1) + "z" to "非十六进制字符",
        )
        for ((value, why) in bad) {
            val ex = assertFailsWith<IllegalArgumentException>(why) {
                ModDeclarationAdapter.toDescriptor(fullManifest().copy(artifactSha256 = value))
            }
            assertTrue(
                ex.message?.contains("artifact-sha256") == true,
                "拒绝消息必须点名节名（实际：${ex.message}）",
            )
        }
        // 空串 = 未提供，合法（内置载体没有 .erbm 产物）。
        val empty = ModDeclarationAdapter.toDescriptor(fullManifest().copy(artifactSha256 = ""))
        assertEquals("", empty.artifactSha256)
    }

    @Test
    fun `确定性：两次编码与写读写逐字节一致`() {
        val descriptor = ModDeclarationAdapter.toDescriptor(fullManifest())
        val first = ManifestCodec.encode(descriptor)
        val second = ManifestCodec.encode(descriptor)
        assertTrue(first.contentEquals(second), "同一 descriptor 两次编码必须逐字节相同")

        val rewritten = ManifestCodec.encode(ManifestCodec.decode(first))
        assertTrue(first.contentEquals(rewritten), "写→读→写必须逐字节相同")
    }
}
