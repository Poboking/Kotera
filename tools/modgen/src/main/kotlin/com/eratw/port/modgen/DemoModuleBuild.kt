package com.eratw.port.modgen

import com.eratw.port.erbcc.compile.ErbModuleCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.jvm.JvmStatic

/**
 * RT2-ERBM：`eratw.demo` 随包两件套（`.erbm` 容器 + 点 manifest）的生产者。
 *
 * ## 它是什么
 *
 * 裁定②（CONTRACT-RT2-ERBM）：demo 模块**随包出**——一条最小 ERB 真源
 * （resources 下 erbm-demo 子目录的 DEMO.ERB：`@ERATW_DEMO_PING` + `RETURN 1`，
 * 加性、无参、无覆盖，手写最小文法件、非语料切片）经 erbcc 的零基线模块编译档
 * [ErbModuleCompiler.specFromErb] 切规格，`ErbModuleCompiler.write` 落成 `.erbm`；
 * 再取 `.erbm` 实际摘要钉进 [ManifestSource.artifactSha256]，由 [ManifestBuild.write]
 * 编成点 manifest。文件名照惯例 = packageId + 扩展名（`eratw.demo.erbm` /
 * `eratw.demo.manifest`），与 family 载体 manifest 同一个 mods/rt2 子目录。
 *
 * ## 单一真源
 *
 * 真源只有 resources 里的 DEMO.ERB 一份：本对象与 [DemoModuleAssetTest] 都从
 * classpath 读同一份资源（JavaExec 的 classpath = main runtimeClasspath，测试运行期
 * 也含 main resources），不存在第二份拷贝。真源改了就重跑 `generateDemoErbModule`，
 * 没重生的资产由 DemoModuleAssetTest 的逐字节闸钉成红；manifest 钉的摘要与 `.erbm`
 * 实际字节的对账由出包体检（pack_release.py 的 rt2_erbm_problems）再拿一道。
 *
 * 入口刻意是 object 内 `@JvmStatic main`（照 [BuiltinManifestBuild] 先例）：本包已有
 * 顶层 `main`（ManifestBuild.kt），再加顶层 main 会抢同包裸调用解析。
 */
object DemoModuleBuild {

    /** demo 包 id：资产文件名、manifest 的 meta 段、容器 packageId 三处同源这一个常量。 */
    const val PACKAGE_ID: String = "eratw.demo"

    /** 真源模块文件在容器里的名字（进 `FuncMeta.position`；改名 = `.erbm` 字节变）。 */
    const val MODULE_FILE_NAME: String = "DEMO.ERB"

    /** demo manifest 版本串；family 载体用「0.1」同款小版本口径。 */
    const val MANIFEST_VERSION: String = "0.1"

    /** 真源 ERB 在 modgen resources 里的类路径。 */
    private const val MODULE_RESOURCE = "/erbm-demo/DEMO.ERB"

    /** 容器资产文件名（点名单一真源）。 */
    val erbmFileName: String get() = "$PACKAGE_ID.erbm"

    /** manifest 资产文件名（与 family 载体同一扩展名口径）。 */
    val manifestFileName: String get() = PACKAGE_ID + BuiltinManifestBuild.MANIFEST_EXTENSION

    /**
     * 真源 ERB 文本（classpath 单路读取：JavaExec 与资产对账测试都走这一条，无第二份拷贝）。
     */
    fun moduleSourceText(): String =
        requireNotNull(DemoModuleBuild::class.java.getResourceAsStream(MODULE_RESOURCE)) {
            "找不到 demo 真源 $MODULE_RESOURCE —— modgen resources 没进 classpath？"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** 真源 → 待编译规格。测试 oracle 与生成入口共用这一条语义路径，不另设第二份。 */
    fun moduleSpec(): ErbModuleCompiler.ModuleSpec =
        ErbModuleCompiler.specFromErb(
            PACKAGE_ID,
            MODULE_FILE_NAME,
            moduleSourceText().split("\n"),
        )

    /**
     * manifest 声明源：裁定②把 `.erbm` 实际摘要钉进 artifact-sha256 段
     * （空串 = 未钉；demo 必钉，pack 体检据此对账）。
     */
    fun manifestSource(artifactSha256: String): ManifestSource = ManifestSource(
        packageId = PACKAGE_ID,
        version = MANIFEST_VERSION,
        artifactSha256 = artifactSha256,
    )

    /** 写出两件套到目录（文件名由 packageId 派生；父目录自动建）。 */
    fun write(outputDir: Path) {
        val target = outputDir.toAbsolutePath()
        Files.createDirectories(target)
        val erbmBytes = ErbModuleCompiler.write(target.resolve(erbmFileName), moduleSpec())
        ManifestBuild.write(manifestSource(sha256Hex(erbmBytes)), target.resolve(manifestFileName))
    }

    /**
     * Gradle `generateDemoErbModule` 的 JavaExec 入口（照 [BuiltinManifestBuild.main] 形态）：
     * 单参数 = 输出目录；缺省相对 :tools:modgen 的模块目录（Gradle task 一律传绝对路径）。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: demoErbModule [outputDir]" }
        val dir = if (args.size == 1) {
            Path.of(args[0])
        } else {
            Path.of("../../app/src/main/assets/mods/rt2")
        }
        write(dir)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and BYTE_MASK) }

    /** sha256Hex 的字节掩码（Byte 有符号扩展抹掉）。 */
    private const val BYTE_MASK = 0xff
}
