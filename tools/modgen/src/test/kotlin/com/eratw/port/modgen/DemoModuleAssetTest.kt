package com.eratw.port.modgen

import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.erbcc.compile.ErbModuleCompiler
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RT2-ERBM demo 两件套（`.erbm` + 点 manifest）的**单一真源闸**（照 BuiltinManifestAssetTest 口径）。
 *
 * 资产是入库的静态文件（app 模块 assets 目录下 mods 子树的 rt2 子目录），真源是 modgen
 * resources 里的 DEMO.ERB。两者必须逐字节一致：真源改了却没重跑 `generateDemoErbModule`，
 * 这里立刻红 —— 不让「包里装的是旧模块」这种静默错数据混进出包体检与真机。
 * manifest 的 artifact-sha256 与 `.erbm` 实际摘要的对账在这里、出包体检（rt2_erbm_problems）
 * 各拿一道。
 */
class DemoModuleAssetTest {

    private fun assetDir(): File {
        val candidates = listOf(
            File("../app/src/main/assets/mods/rt2"),
            File("../../app/src/main/assets/mods/rt2"),
            // 公开分仓：app 资产在 pocket 仓（单体布局作回落）；单仓检出（CI）无资产 ⇒ 计 skipped。
            File("../../../eratw-pocket/app/src/main/assets/mods/rt2"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
        Assumptions.assumeTrue(
            dir != null,
            "找不到内置 RT2 资产目录（试了 ${candidates.joinToString { it.path }}）；" +
                "pocket 仓同级检出后此闸生效，单仓检出计 skipped",
        )
        return dir!! // assumeTrue 拦住 null；此处不可达 null
    }

    private fun erbmAsset(): File {
        val f = File(assetDir(), DemoModuleBuild.erbmFileName)
        assertTrue(f.isFile, "找不到 ${DemoModuleBuild.erbmFileName}；先跑 generateDemoErbModule")
        return f
    }

    private fun manifestAsset(): File {
        val f = File(assetDir(), DemoModuleBuild.manifestFileName)
        assertTrue(f.isFile, "找不到 ${DemoModuleBuild.manifestFileName}；先跑 generateDemoErbModule")
        return f
    }

    /** 与生成入口同一条语义路径的重编 oracle：真源 spec → ErbModuleCompiler.write。 */
    private fun recompiledErbm(outputDir: File): ByteArray =
        ErbModuleCompiler.write(
            outputDir.resolve(DemoModuleBuild.erbmFileName).toPath(),
            DemoModuleBuild.moduleSpec(),
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("demo-module-asset").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `入库两件套与真源重编字节逐字节一致`() {
        withTempDir { tmp ->
            val oracleErbm = recompiledErbm(tmp)
            assertContentEquals(
                oracleErbm,
                erbmAsset().readBytes(),
                "demo 真源改了必须重跑 generateDemoErbModule 并把两件新字节一起提交",
            )
            // manifest oracle：钉住 .erbm 实际摘要（裁定②），与生成入口同一 ManifestBuild 路径。
            val oracleManifest = ManifestBuild.build(
                DemoModuleBuild.manifestSource(sha256Hex(oracleErbm)),
            )
            assertContentEquals(oracleManifest, manifestAsset().readBytes())
        }
    }

    @Test
    fun `资产文件名与包名一致且回读同包名同档位`() {
        val erbm = erbmAsset()
        val manifest = manifestAsset()
        assertEquals(DemoModuleBuild.PACKAGE_ID + ".erbm", erbm.name)
        assertEquals(DemoModuleBuild.PACKAGE_ID + BuiltinManifestBuild.MANIFEST_EXTENSION, manifest.name)
        val container = ErbModuleContainer.decode(erbm.readBytes())
        assertEquals(DemoModuleBuild.PACKAGE_ID, container.packageId, "容器回读包名必须与真源常量同源")
        assertTrue(container.allowSuperCalls, "Mod 包编译档（allowSuperCalls=true）")
        val descriptor = ManifestCodec.decode(manifest.readBytes())
        assertEquals(DemoModuleBuild.PACKAGE_ID, descriptor.packageId)
        assertEquals(DemoModuleBuild.MANIFEST_VERSION, descriptor.version)
    }

    @Test
    fun `manifest钉的artifact_sha256与erbm实际摘要一致`() {
        val descriptor = ManifestCodec.decode(manifestAsset().readBytes())
        assertTrue(descriptor.artifactSha256.isNotEmpty(), "demo manifest 必须钉摘要（空串 = 未钉）")
        assertEquals(
            sha256Hex(erbmAsset().readBytes()),
            descriptor.artifactSha256,
            "钉值必须等于 .erbm 实际摘要（pack 体检 rt2_erbm_problems 同判据）",
        )
    }

    @Test
    fun `反向证明 真源改一格时重编字节必须不同`() {
        // 真源 RETURN 1 → RETURN 2：重编产物必须与入库资产不同——字节闸真绑住真源内容。
        withTempDir { tmp ->
            val shifted = ErbModuleCompiler.specFromErb(
                DemoModuleBuild.PACKAGE_ID,
                DemoModuleBuild.MODULE_FILE_NAME,
                DemoModuleBuild.moduleSourceText().replace("RETURN 1", "RETURN 2").split("\n"),
            )
            val shiftedBytes = ErbModuleCompiler.write(
                tmp.resolve(DemoModuleBuild.erbmFileName).toPath(),
                shifted,
            )
            assertFalse(
                shiftedBytes.contentEquals(erbmAsset().readBytes()),
                "真源改动进不了 .erbm 字节 = 资产没绑住真源内容",
            )
        }
    }

    @Test
    fun `反向证明 坏档解码当场拒绝不静默兼容`() {
        val broken = erbmAsset().readBytes().copyOf()
        broken[broken.lastIndex] = (broken[broken.lastIndex] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { ErbModuleContainer.decode(broken) }
    }
}
