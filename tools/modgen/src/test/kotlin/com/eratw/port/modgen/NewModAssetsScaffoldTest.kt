package com.eratw.port.modgen

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 一次 new-mod 调用的观测面（本文件的私有副本，避免与 NewModScaffoldTest 的私有顶层声明撞名）。 */
private class AssetsCliResult(val exit: Int, val out: String, val err: String)

private fun runAssetsCli(vararg args: String): AssetsCliResult {
    val capturedOut = ByteArrayOutputStream()
    val capturedErr = ByteArrayOutputStream()
    val exit = PrintStream(capturedOut, true, "UTF-8").use { out ->
        PrintStream(capturedErr, true, "UTF-8").use { err ->
            NewModScaffold.runCli(arrayOf(*args), out, err)
        }
    }
    return AssetsCliResult(exit, capturedOut.toString("UTF-8"), capturedErr.toString("UTF-8"))
}

private fun scaffoldWithAssets(moduleDir: Path, vararg extra: String): AssetsCliResult =
    runAssetsCli("new-mod", "--package", "eratw.mymod", "--name", "My", "--out", moduleDir.toString(), *extra)

/**
 * new-mod 的资源包骨架：`<packageId>.assets/` 目录 + README 示例 + Mod.kt 里**可直接运行**的
 * `resources` / `strings` 用法（v10 三语文案面）；
 * 新增目录同样进 `--check` 预览与整体冲突判定（目录本身不算冲突，目录里的目标文件算）。
 */
class NewModAssetsScaffoldTest {

    @TempDir
    lateinit var tempDir: Path

    private fun moduleDir(): Path = tempDir.resolve("mod-mymod")

    private fun assetsDir(module: Path): Path = module.resolve("eratw.mymod.assets")

    @Test
    fun generatesAssetsDirectoryReadmeAndRunnableUsage() {
        val module = moduleDir()
        val result = scaffoldWithAssets(module)

        assertEquals(0, result.exit, "stderr=${result.err}")
        assertTrue(Files.isDirectory(assetsDir(module)), "缺资源包目录：${assetsDir(module)}")
        val readme = assetsDir(module).resolve("README.txt")
        assertTrue(Files.isRegularFile(readme), "缺示例资源 README.txt")
        val readmeText = readme.readText()
        assertTrue(readmeText.contains("mods/rt2/"), "README 应说明运行期落点")
        assertTrue(readmeText.contains("导入端派生的包身份"), "README 应说明导入后目录名由导入端派生")
        assertTrue(readmeText.contains("context.resources.open(\"README.txt\")"), "README 应演示按名读取")
        assertTrue(readmeText.contains("minApi"), "README 应说明打包与引擎下限（minApi）")
        assertTrue(readmeText.contains("--min-api"), "README 应说明 pack-mod --min-api")
        assertTrue(readmeText.contains("三语文案（v10）"), "README 应说明三语文案面")
        assertTrue(readmeText.contains("context.strings.of(\"eratw.mymod.greeting\")"), "README 应演示 strings.of")
        assertTrue(readmeText.contains("4 MiB"), "README 应写名单文件上限")
        assertTrue(readmeText.contains("16 MiB"), "README 应写整包上限")
        assertTrue(readmeText.contains("事件 / 钩子 / 相位 / 任务面"), "README 应说明事件/钩子/相位/任务面")
        assertTrue(readmeText.contains("context.events.register("), "README 应演示事件注册")
        assertTrue(readmeText.contains("context.hooks.register("), "README 应演示钩子绑定")
        assertTrue(readmeText.contains("context.phases.subscribe("), "README 应演示相位订阅")
        assertTrue(readmeText.contains("context.ui.registerInteraction("), "README 应演示交互项注册")
        assertTrue(
            readmeText.contains("第三方代码包（路 B：自带 classes.dex，可选）"),
            "README 应说明第三方代码包（路 B）怎么走",
        )
        assertTrue(readmeText.contains("--code <内层 zip>"), "README 应演示 pack-mod --code")
        assertTrue(
            readmeText.contains("--entrypoint eratw.mymod.MyMod"),
            "README 应给出本骨架可直接照抄的入口类 FQN",
        )
        assertTrue(
            readmeText.contains("\"entrypoint\": \"eratw.mymod.MyMod\""),
            "README 应给出内层 manifest.json 的可照抄示例",
        )
        assertTrue(readmeText.contains("缺省关"), "README 应诚实说明代码包总闸缺省关、需玩家放行")

        val mod = module.resolve("src/main/kotlin/eratw/mymod/MyMod.kt").readText()
        assertTrue(mod.contains("if (context.resources.exists(\"README.txt\"))"), "Mod.kt 缺真实资源读取示例")
        assertTrue(mod.contains("context.resources.keys().forEach"), "Mod.kt 缺 keys 示例")
        assertTrue(mod.contains("context.strings.declare("), "Mod.kt 缺三语文案声明示例")
        assertTrue(mod.contains("context.strings.of(\"eratw.mymod.greeting\")"), "Mod.kt 缺 strings.of 取用示例")
    }

    @Test
    fun checkModeListsAssetsEntriesAndNeverTouchesDisk() {
        val module = moduleDir()
        val result = scaffoldWithAssets(module, "--check")

        assertEquals(0, result.exit, "stderr=${result.err}")
        assertTrue(result.out.contains("WOULD WRITE: eratw.mymod.assets/"), "预览缺资源目录：${result.out}")
        assertTrue(
            result.out.contains("WOULD WRITE: eratw.mymod.assets/README.txt"),
            "预览缺示例资源：${result.out}",
        )
        assertFalse(Files.exists(module), "--check 不得建目录")
    }

    @Test
    fun assetsDirectoryPathOccupiedByFileRefusesWholeSet() {
        val module = moduleDir()
        Files.createDirectories(module)
        Files.writeString(assetsDir(module), "not-a-directory")

        val result = scaffoldWithAssets(module)

        assertEquals(1, result.exit)
        assertTrue(result.err.contains("CONFLICT: eratw.mymod.assets/"), "stderr=${result.err}")
        assertTrue(result.err.contains("REFUSED: "), "stderr=${result.err}")
        assertFalse(result.out.contains("WROTE:"), "拒绝时不得写任何条目：${result.out}")
        assertFalse(Files.exists(module.resolve("build.gradle.kts")), "整体拒绝时一个文件都不写")
        assertEquals("not-a-directory", assetsDir(module).readText())
    }

    @Test
    fun existingReadmeInsideAssetsRefusesWithoutForce() {
        val module = moduleDir()
        val readme = assetsDir(module).resolve("README.txt")
        Files.createDirectories(readme.parent)
        Files.writeString(readme, "sentinel-readme")

        val result = scaffoldWithAssets(module)

        assertEquals(1, result.exit)
        assertTrue(result.err.contains("CONFLICT: eratw.mymod.assets/README.txt"), "stderr=${result.err}")
        assertFalse(result.out.contains("WROTE:"), "拒绝时不得写盘：${result.out}")
        assertEquals("sentinel-readme", readme.readText())
        assertFalse(Files.exists(module.resolve("build.gradle.kts")), "整体拒绝时一个文件都不写")
    }

    @Test
    fun existingAssetsDirectoryIsReusedAndAuthorFilesSurviveForce() {
        val module = moduleDir()
        val keep = assetsDir(module).resolve("keep.bin")
        Files.createDirectories(keep.parent)
        Files.writeString(keep, "author-asset")

        val result = scaffoldWithAssets(module, "--force")

        assertEquals(0, result.exit, "stderr=${result.err}")
        assertTrue(Files.isRegularFile(keep), "--force 不得清掉作者已有资源")
        assertEquals("author-asset", keep.readText())
        assertTrue(Files.isRegularFile(assetsDir(module).resolve("README.txt")), "--force 应补齐示例资源")
    }
}
