package com.eratw.port.modgen

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.modapi.registry.ModRegistry
import com.eratw.port.modfamily.FamilyDeclarations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.jvm.JvmStatic

/**
 * 内置 RT2 载体的 manifest 生产者（RT2-B③ 资产投递 / K4-min；RT2-M91 起扩为**双真源**）。
 *
 * 真源 = 各 Kotlin 载体模块里的声明 object，经 [ModRegistry] 自动发现：载体在自己模块里
 * 声明的全部组件、原型、关系与任务，经 [ModDeclarationAdapter.toDescriptor] 转 descriptor
 * 后由 [ManifestCodec.encode] 编成 RT2MANIFEST/3 字节。本文件不重写第二份声明，也不点名
 * 任何载体 —— 声明改了就重跑 `generateBuiltinRt2Manifest`，没重生的资产由资产对账用例钉成红。
 *
 * 产出形态 = 随包静态资产：app 模块 assets 目录下 mods 子树的 rt2 子目录里每个载体一个点
 * manifest 文件，文件名 = packageId 加扩展名。装配侧按文件名升序链接；设备侧由
 * GameAssetInstaller 的专用投递步骤（按 `.manifest` / `.erbm` 后缀点名，不按具体文件名）
 * 把它放进游戏根的同名目录（不进通用 DATA_DIRS，避免牵连玩家导入的 mod）。
 *
 * 本文件在 :tools:modgen（构建期工具）里，因此能同时看见各载体模块的声明与 :core-vm 的
 * codec，而不让 mod 侧生产代码依赖引擎内部（P1-6 的 Gradle 依赖方向判据）。
 */
object BuiltinManifestBuild {

    /** 资产扩展名，与 ModRuntimeAssembly.MANIFEST_EXTENSION 同源；对账用例负责钉住一致性。 */
    const val MANIFEST_EXTENSION: String = ".manifest"

    /**
     * 内置载体真源清单（packageId → 编码后的 manifest 字节），由 [ModRegistry] 展开。
     *
     * **这里不再点名任何载体**：名单来自各 `mod-*` 模块里打了 `@ModCarrier` 的声明 object
     * （注解自动发现，见 `:mod-registry`）。原先是「本文件 carriers 一行 + 设备侧
     * ModCarrierRegistry.providers() 一行」的双登记，漏一处就静默不生效；现在两处都只读
     * `ModRegistry.carriers`，登记面只剩声明 object 自身。
     */
    val carriers: List<Pair<String, ByteArray>> = ModRegistry.carriers.map { carrier ->
        carrier.packageId to
            ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(carrier.manifest))
    }

    /** 全部资产文件名（点名可审计：包名即文件名）。 */
    val assetFileNames: List<String> get() = carriers.map { it.first + MANIFEST_EXTENSION }

    /** 编码产物，逐位确定（同一份声明永远同一串字节）。 */
    fun bytesFor(packageId: String): ByteArray =
        carriers.single { it.first == packageId }.second

    /** family 载体资产文件名（既有调用面的兼容入口；全量名单看 [assetFileNames]）。 */
    val assetFileName: String get() = FamilyDeclarations.PACKAGE_ID + MANIFEST_EXTENSION

    /** family 载体编码产物（既有调用面的兼容入口；逐位同 [bytesFor]）。 */
    fun bytes(): ByteArray = bytesFor(FamilyDeclarations.PACKAGE_ID)

    /** 把全部载体资产写进指定目录（父目录自动建；调用方给绝对路径以免受工作目录影响）。 */
    fun writeAll(outputDir: Path) {
        val target = outputDir.toAbsolutePath()
        Files.createDirectories(target)
        for ((packageId, bytes) in carriers) {
            Files.write(target.resolve(packageId + MANIFEST_EXTENSION), bytes)
        }
    }

    /**
     * Gradle `generateBuiltinRt2Manifest` 的 JavaExec 入口。
     *
     * 刻意**不**用顶层 `main`：本包既有的 `ManifestBuild.kt` 已声明顶层 main，且
     * `ManifestProdChainTest` 在同包里直接调用裸 `main(...)`；再加一个顶层 main 会抢走
     * 调用解析（或造成重载歧义）。放进 object + @JvmStatic 后入口类仍是
     * `com.eratw.port.modgen.BuiltinManifestBuild`，但不再污染顶层命名空间。
     *
     * RT2-M91 起参数语义从「单文件」改为「输出目录」：一次跑出**全部**内置载体资产
     * （family + memory + M10-1 起的 eratw.lw 四包），杜绝「只重生了 family、memory
     * 旧字节静默入库」的半重生形态。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: builtinManifest [outputDir]" }
        val outputDir = if (args.size == 1) {
            Path.of(args[0])
        } else {
            // 手动运行时相对 :tools:modgen 的模块目录；Gradle task 一律传绝对路径。
            Path.of("../../app/src/main/assets/mods/rt2")
        }
        writeAll(outputDir)
    }
}
