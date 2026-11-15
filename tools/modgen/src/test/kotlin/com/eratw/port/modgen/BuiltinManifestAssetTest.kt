package com.eratw.port.modgen

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modlivingworld.CharacterDeclarations
import com.eratw.port.modlivingworld.EventDeclarations
import com.eratw.port.modlivingworld.MemoryDeclarations as LwMemoryDeclarations
import com.eratw.port.modlivingworld.RelationshipDeclarations
import com.eratw.port.modmemory.MemoryDeclarations
import java.io.File
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RT2-B③ 内置资产的**单一真源闸**（RT2-M91 起盖住全部内置载体）。
 *
 * 资产是入库的静态文件（app 模块 assets 目录下 mods 子树的 rt2 子目录），声明在各载体
 * 模块（[FamilyDeclarations.manifest] / [MemoryDeclarations.manifest]）。两者必须逐字节
 * 一致：声明改了却没重跑 `generateBuiltinRt2Manifest`，这里立刻红 —— 不让「包里装的是
 * 旧声明」这种静默错数据混进出包体检与真机（§22.7 第 25 条那类失败形态）。
 */
class BuiltinManifestAssetTest {

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
            "找不到内置 RT2 manifest 资产目录（试了 ${candidates.joinToString { it.path }}）；" +
                "pocket 仓同级检出后此闸生效，单仓检出计 skipped",
        )
        return dir!! // assumeTrue 拦住 null；此处不可达 null
    }

    /** 全部内置载体逐个对账（真源清单 = BuiltinManifestBuild.carriers，不加第二份点名）。 */
    private fun assets(): List<Pair<String, File>> =
        BuiltinManifestBuild.carriers.map { (packageId, _) ->
            val file = File(assetDir(), packageId + BuiltinManifestBuild.MANIFEST_EXTENSION)
            packageId to file
        }

    @Test
    fun `入库资产与声明真源逐字节一致`() {
        for ((packageId, file) in assets()) {
            assertTrue(
                file.isFile,
                "内置 RT2 manifest 缺席：${file.path}（先跑 generateBuiltinRt2Manifest）",
            )
            assertContentEquals(
                BuiltinManifestBuild.bytesFor(packageId),
                file.readBytes(),
                "声明改了必须重跑 generateBuiltinRt2Manifest 并把新字节一起提交（$packageId）",
            )
        }
    }

    @Test
    fun `资产文件名与包名一致且解码回同一包名`() {
        assertEquals(
            BuiltinManifestBuild.carriers.map { it.first + BuiltinManifestBuild.MANIFEST_EXTENSION },
            BuiltinManifestBuild.assetFileNames,
            "资产文件名清单与真源清单同源",
        )
        for ((packageId, file) in assets()) {
            assertEquals(packageId + BuiltinManifestBuild.MANIFEST_EXTENSION, file.name)
            val descriptor = ManifestCodec.decode(file.readBytes())
            assertEquals(packageId, descriptor.packageId)
        }
    }

    @Test
    fun `反向证明 声明改一格时资产字节必须不同`() {
        for (manifest in listOf(
            FamilyDeclarations.manifest,
            MemoryDeclarations.manifest,
            CharacterDeclarations.manifest,
            RelationshipDeclarations.manifest,
            LwMemoryDeclarations.manifest,
            EventDeclarations.manifest,
        )) {
            val current = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
            val shifted = ManifestCodec.encode(
                ModDeclarationAdapter.toDescriptor(manifest.copy(version = "0.2")),
            )
            assertFalse(current.contentEquals(shifted), "version 进不了编码 = 资产没绑住声明内容")
            val file = File(assetDir(), manifest.packageId + BuiltinManifestBuild.MANIFEST_EXTENSION)
            assertFalse(file.readBytes().contentEquals(shifted), "入库资产必须与真源同步，不能是另一份声明")
        }
    }

    @Test
    fun `反向证明 坏档解码当场拒绝不静默兼容`() {
        for ((_, file) in assets()) {
            val broken = file.readBytes().copyOf()
            broken[broken.lastIndex] = 'X'.code.toByte()
            assertFailsWith<ManifestFormatException> { ManifestCodec.decode(broken) }
        }
    }
}
