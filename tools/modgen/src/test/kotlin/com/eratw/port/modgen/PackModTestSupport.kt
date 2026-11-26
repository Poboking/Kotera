package com.eratw.port.modgen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 测试骨架用的 packageId（与 [scaffoldModule] 的 `--package` 一致）。 */
internal const val TEST_PACKAGE_ID = "eratw.mymod"

/** 测试骨架代码入口类 FQN（`--entrypoint` 与内层清单 `entrypoint` 同值）。 */
internal const val TEST_ENTRYPOINT = "eratw.mymod.MyMod"

/** 骨架自带资源目录名（= packageId + 运行期后缀）。 */
internal const val TEST_ASSET_DIR = "eratw.mymod.assets"

/** 骨架自带的那一份资源文件。 */
internal const val TEST_ASSET_README = "eratw.mymod.assets/README.txt"

/** 打包时临时造的源文件名（干净模块固定带这一个）。 */
internal const val TEST_SOURCE = "main.ERB"

/** pack-mod 进程内直调的捕获结果。 */
internal class PackRun(val exit: Int, val out: String, val err: String)

/** 进程内直调 [PackMod.runCli]，UTF-8 捕获两路输出（口径同 ModCheckCliTest）。 */
internal fun runPack(vararg args: String): PackRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = PrintStream(out, true, "UTF-8").use { outStream ->
        PrintStream(err, true, "UTF-8").use { errStream ->
            PackMod.runCli(arrayOf(*args), outStream, errStream)
        }
    }
    return PackRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
}

/** 断言 exit 为 0 并返回捕获结果（失败信息带两路输出，便于定位）。 */
internal fun packOk(module: Path, zip: Path): PackRun {
    val run = runPack(module.toString(), "--out", zip.toString())
    assertTrue(run.exit == 0, "expected exit 0 but got ${run.exit}; out=${run.out} err=${run.err}")
    return run
}

/** 在模块目录里写一个文件（自动建父目录），返回写好的路径。 */
internal fun writeModuleFile(module: Path, relative: String, text: String): Path {
    val target = module.resolve(relative)
    val parent = target.parent
    if (parent != null) Files.createDirectories(parent)
    Files.writeString(target, text)
    return target
}

/** 从摘要行里取出 sha256（两次打包对拍用）。 */
internal fun sha256Of(run: PackRun): String {
    val match = Regex("pack-mod: sha256 ([0-9a-f]{64})").find(run.out)
    assertNotNull(match, "no sha256 line in stdout=${run.out}")
    return match.groupValues[1]
}

/** 读 zip 中央目录的条目名，保持写盘顺序。 */
/**
 * zip 内容摘要（跨机可比）。
 *
 * 只取**条目名 + 未压缩字节**：压缩流随 JDK 自带的 zlib 版本而变，同一份内容在不同
 * JDK 上压出来的原始字节可能不同（2026-11-27 在 Linux CI 上实测判红，而解包后的
 * 条目内容逐字节相同）。要冻的是**包内容**这条合同，不是压缩实现的副产品。
 */
internal fun zipContentDigest(zipBytes: ByteArray): String {
    val perEntry = sortedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            perEntry[entry.name] = zis.readBytes()
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    for ((name, content) in perEntry) {
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(MessageDigest.getInstance("SHA-256").digest(content))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun entryNames(zip: Path): List<String> =
    ZipFile(zip.toFile()).use { file -> file.entries().asSequence().map { it.name }.toList() }

/** 读 zip 里单个条目的原始字节。 */
internal fun entryBytes(zip: Path, name: String): ByteArray =
    ZipFile(zip.toFile()).use { file ->
        val entry = file.getEntry(name)
        assertNotNull(entry, "entry not found: $name")
        file.getInputStream(entry).use { stream -> stream.readBytes() }
    }

/** 出包路径（相对 temp 根；父目录故意不存在，交给 pack-mod 自己建）。 */
internal fun outZip(root: Path, relative: String = "out/pkg.zip"): Path = root.resolve(relative)

/** 干净可打包的模块：new-mod 骨架 + generated 生成物 + 一个 ERB 源（check-mod 零 error）。 */
internal fun packableModule(root: Path): Path {
    val module = scaffoldModule(root)
    writeGenerated(module)
    writeModuleFile(module, TEST_SOURCE, "@SYSTEM\nPRINTL hello\n")
    return module
}
