package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path

/**
 * 「声明包身份 ↔ 实际资源目录名」一致性判据（check-mod 与 pack-mod 同源）。
 *
 * 模块根下存在 `.assets` 目录、却没有任何一个等于声明的 `<PACKAGE_ID>.assets` 时，打包面把该
 * 目录整目录当外来资源跳过、导入端也收不到 —— 资源全部静默丢失，属确定性失效。
 *
 * 三种放行：源码里没有 `PACKAGE_ID` 字面量（无声明就无从比对，缺声明归声明面与 `pack-mod`
 * 的 NO_PACKAGE_ID_WARN 管）、根下没有资源目录（资源面可选）、至少有一个目录匹配（多载体允许
 * 额外外来目录，既有行为仍是跳过并计数）。
 *
 * 独立成 object（而不是并进 [ModCheckResources]）以控制单个 object 的函数数。
 */
internal object ModResourceDirConsistency {

    /** 模块根下全部 `.assets` 目录名（只取文件名、排序）。 */
    fun assetDirectoryNames(root: Path): List<String> {
        val stream = Files.list(root)
        return try {
            stream
                .filter { path ->
                    Files.isDirectory(path) &&
                        path.fileName.toString().endsWith(ModResourceLimits.ASSET_DIR_SUFFIX)
                }
                .map { path -> path.fileName.toString() }
                .sorted()
                .toList()
        } finally {
            stream.close()
        }
    }

    /** 不一致时返回 ERROR 文案；放行形态见类 KDoc。 */
    fun mismatch(existingDirNames: List<String>, packageIds: List<String>): String? {
        val expected = packageIds.map { packageId -> packageId + ModResourceLimits.ASSET_DIR_SUFFIX }
        return when {
            packageIds.isEmpty() || existingDirNames.isEmpty() -> null
            existingDirNames.any { name -> name in expected } -> null
            else -> mismatchMessage(existingDirNames, packageIds)
        }
    }

    private fun mismatchMessage(existingDirNames: List<String>, packageIds: List<String>): String {
        val actual = existingDirNames.joinToString("、")
        val declared = packageIds.joinToString("、") { packageId ->
            if (packageId.isEmpty()) "<空白>" else packageId
        }
        return "资源目录名与 PACKAGE_ID 不一致：模块根下有 .assets 目录「$actual」，" +
            "但源码声明的 PACKAGE_ID 是「$declared」，没有任何目录等于「<PACKAGE_ID>.assets」—— " +
            "pack-mod 会把这些目录整目录跳过、导入端也收不到，资源会全部静默丢失。" +
            "把 const val PACKAGE_ID 改成与实际目录名前缀一致，或把目录重命名为「<PACKAGE_ID>.assets」；" +
            "不需要随包资源就删掉该目录。"
    }
}
