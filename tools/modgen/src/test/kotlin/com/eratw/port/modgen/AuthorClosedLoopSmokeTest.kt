package com.eratw.port.modgen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 「作者从零到玩家可装」的真闭环烟测（第一段：出包面）。
 *
 * 链路（每一环都走真实实现，不自搭等价物）：
 * 1. `NewModScaffold.runCli`（`new-mod` 的真实实现，`ManifestBuild.kt:46-57` 的 CLI 分派落到它）；
 * 2. 作者动作：接生成器写 `src/generated`（`AccessorEmit`，与入库生成物同口径）、写一个 `.ERB` 源、
 *    往 `<PACKAGE_ID>.assets/` 放一张图；
 * 3. `ModCheck.runCli`（`check-mod` 真实实现）⇒ 0 error；
 * 4. `PackMod.runCli`（`pack-mod` 真实实现）出包两次 ⇒ 字节完全相同，且与冻结夹具
 *    [FIXTURE_BASE64] 逐字节相同（产物含打包器写的包级元数据 `eratw-pack.json`，它不计入
 *    摘要的「条目 N（源 X / 资源 Y）」，另有独立摘要行）；
 * 5. 反向：`PACKAGE_ID` 改成空白串（资源目录同步成 `.assets`，即 check-mod 文案要求作者做的
 *    「同步重命名」）⇒ `pack-mod` 退出码 1 拒包，消息点名条目 `.assets` 与判据「来源目录名」。
 *
 * ## 为什么「两段接力」而不是同进程 A/B
 *
 * 第二段（app 侧导入 + 运行期 `ModResourceStore` 读回）在 `:app` 测试源集，
 * `PackMod`/`PackModZip` 是 `internal object` 且 app 未依赖 `:tools:modgen`（`app/build.gradle.kts`
 * 无该依赖），本 lane 授权写面也不含 `tools/modgen/build.gradle.kts`，无法同进程真跑 pack-mod。
 * ⇒ 第一段本文件把真产物字节冻成 [FIXTURE_BASE64] 并断言行间一致，第二段
 * `app/src/test/kotlin/com/eratw/port/android/ModImportAuthorPackEndToEndTest.kt` 内嵌**同一份**
 * Base64 夹具做真导入 —— 两段由同一份字节钉住，模板/打包合同漂移会在两段各红一次。
 * 本文件不读任何别的测试的临时文件。
 *
 * ## 退出码怎么接
 *
 * `ManifestBuild.kt:46-57` 的 `main` 对三个子命令都是 `exitProcess(runCli(...))`，测试 JVM 内直调
 * `main` 会杀掉 Gradle test worker ⇒ 与既有 `PackModCliTest`/`ModCheckCliTest` 同口径，直调
 * `runCli(args, out, err)` 拿返回的 `Int` 当进程退出码断言（CLI 契约本体就在 `runCli` 里，
 * `main` 只剩把返回值交给 `exitProcess`）。
 */
class AuthorClosedLoopSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `作者闭环——new-mod→check-mod→pack-mod两次同字节且等于冻结夹具`() {
        // 1. new-mod 生成骨架（真实实现）。
        val module = tempDir.resolve("mod-mymod")
        val newMod = runNewMod(
            arrayOf("new-mod", "--package", TEST_PACKAGE_ID, "--name", "My", "--out", module.toString()),
        )
        assertEquals(0, newMod.exit, "new-mod 应成功：out=${newMod.out} err=${newMod.err}")
        assertScaffold(newMod.out, module)
        // 2. 作者动作：接生成器、写 ERB 源、往资源目录放一张图。
        authorContent(module)
        // 3. check-mod 真入口：生成物自己必须过自己的检查面。
        assertCheckModClean(module)
        // 4. pack-mod 连打两次 ⇒ 字节完全相同，且内容/字节符合合同。
        val zipA = outZip(tempDir, "out/a.zip")
        val zipB = outZip(tempDir, "out/b.zip")
        val packA = packOk(module, zipA)
        val packB = packOk(module, zipB)
        assertSameBytesTwice(packA, packB, zipA, zipB)
        assertPackContent(module, zipA)
        assertPackMeta(module, zipA)
        // 5. 冻结夹具：第二段 app 导入用同一份字节；漂移即红。
        assertFixture(Files.readAllBytes(zipA))
    }

    @Test
    fun `反向——坏PACKAGE_ID（空白串）pack-mod 拒包且点名条目与判据`() {
        val module = tempDir.resolve("mod-badid")
        val newMod = runNewMod(
            arrayOf("new-mod", "--package", TEST_PACKAGE_ID, "--name", "My", "--out", module.toString()),
        )
        assertEquals(0, newMod.exit, "new-mod 应成功：out=${newMod.out} err=${newMod.err}")
        authorContent(module)
        breakPackageIdentity(module)
        assertBadIdentityCaughtByCheckMod(module)
        assertBadIdentityRefusedByPackMod(module)
    }

    // ------------------------------------------------------------------
    // 闭环各环的断言（拆开是为了让每个函数都短于 detekt LongMethod 阈值）
    // ------------------------------------------------------------------

    /** new-mod 骨架面：计划行、五件套、以及「不带 ERB 源」这条作者约束。 */
    private fun assertScaffold(planOut: String, module: Path) {
        assertTrue(planOut.contains("PLAN: 7 个条目"), "new-mod 计划行：$planOut")
        assertTrue(Files.isRegularFile(declarationsFile(module)), "declarations 应生成")
        assertTrue(Files.isRegularFile(schemaFile(module)), "schema 应生成")
        assertTrue(Files.isRegularFile(module.resolve(TEST_ASSET_README)), "assets/README.txt 应生成")
        val fileNames = Files.walk(module).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
        }
        assertTrue(
            fileNames.none { it.endsWith(".ERB") || it.endsWith(".ERH") },
            "骨架不应带 ERB 源（作者自己写）：$fileNames",
        )
    }

    /** 作者侧动作：接生成器、写 ERB 源、往 `<PACKAGE_ID>.assets/` 放一张图。 */
    private fun authorContent(module: Path) {
        writeGenerated(module)
        writeModuleFile(module, TEST_SOURCE, AUTHOR_ERB)
        val assetPath = module.resolve(TEST_ASSET_DIR).resolve(AUTHOR_ASSET_REL)
        Files.createDirectories(assetPath.parent)
        Files.write(assetPath, AUTHOR_ASSET)
    }

    /** check-mod 真入口：0 error 且汇总行可读。 */
    private fun assertCheckModClean(module: Path) {
        val check = runCheckCli(module.toString())
        assertEquals(0, check.exit, "check-mod 应 0 error：out=${check.out} err=${check.err}")
        assertTrue(check.out.contains("check-mod: 0 error(s)"), "check-mod 汇总行：${check.out}")
    }

    /** pack-mod 连打两次（不同 `--out`）⇒ 字节完全相同（确定性合同）。 */
    private fun assertSameBytesTwice(packA: PackRun, packB: PackRun, zipA: Path, zipB: Path) {
        assertTrue(packA.out.contains("pack-mod: 条目 3（源 1 / 资源 2）"), "条目计数：${packA.out}")
        assertTrue(
            packA.out.contains(
                "pack-mod: 元数据 $PACK_META_ENTRY_NAME" +
                    "（packageId=$TEST_PACKAGE_ID / version=$SCAFFOLD_VERSION）",
            ),
            "包元数据摘要行：${packA.out}",
        )
        assertContentEquals(Files.readAllBytes(zipA), Files.readAllBytes(zipB), "同输入两次打包字节必须完全相同")
        assertEquals(sha256Of(packA), sha256Of(packB), "两次 sha256 摘要行应一致")
    }

    /** 内容面：源与资源的路径/字节按 pack-mod 合同落进 zip。 */
    private fun assertPackContent(module: Path, zip: Path) {
        assertEquals(
            listOf(PACK_META_ENTRY_NAME, TEST_ASSET_README, AUTHOR_ASSET_ENTRY, TEST_SOURCE),
            entryNames(zip),
            "条目按路径字典序（元数据条目在 zip 根，排最前）",
        )
        assertContentEquals(AUTHOR_ERB.toByteArray(Charsets.UTF_8), entryBytes(zip, TEST_SOURCE), "ERB 源原样")
        assertContentEquals(AUTHOR_ASSET, entryBytes(zip, AUTHOR_ASSET_ENTRY), "作者资源原字节")
        val readme = entryBytes(zip, TEST_ASSET_README)
        assertContentEquals(
            Files.readAllBytes(module.resolve(TEST_ASSET_README)),
            readme,
            "骨架资源字节与落盘一致",
        )
        assertTrue(
            String(readme, Charsets.UTF_8).contains("日志面（context.logger"),
            "骨架 README 应含 CE 补的「日志面」一节（别把 CE 的模板正文改回去）",
        )
    }

    /**
     * 包级元数据面：条目内容必须与骨架自述身份一致（schema 所有权版本 / packageId / version）。
     *
     * 期望值不从生产实现（`PackModMeta.json`）取，而是从**生成物源码**取 version、用字面量钉住
     * JSON 键序与 schema —— 生产实现与测试互证而不是自证。
     */
    private fun assertPackMeta(module: Path, zip: Path) {
        val declarations = Files.readString(declarationsFile(module))
        assertTrue(
            declarations.contains("const val VERSION: String = \"$SCAFFOLD_VERSION\""),
            "骨架自述版本应为 $SCAFFOLD_VERSION：$declarations",
        )
        assertTrue(declarations.contains("version = VERSION"), "manifest 应引用 VERSION：$declarations")
        val expected = "{\"schema\":1,\"packageId\":\"$TEST_PACKAGE_ID\",\"version\":\"$SCAFFOLD_VERSION\"}"
        assertContentEquals(
            expected.toByteArray(Charsets.UTF_8),
            entryBytes(zip, PACK_META_ENTRY_NAME),
            "元数据条目内容（固定键序 / 无空白 / UTF-8）",
        )
    }

    /** 冻结夹具：第二段 app 导入用同一份字节；带刷新旋钮时改走自刷新（见 [FIXTURE_BASE64] KDoc）。 */
    private fun assertFixture(bytes: ByteArray) {
        if (refreshRequested()) {
            refreshPackFixtureSources(bytes)
            return
        }
        if (FIXTURE_BASE64.isNotEmpty()) {
            // 比**条目内容**（名 + 未压缩字节），不比 zip 的压缩字节流：压缩流随 JDK 自带的
            // zlib 版本而变，同一份内容在不同 JDK 上压出来的字节可能不同（2026-11-27 在
            // Linux CI 上实测：Windows 本机与 CI 的 zlib 不同 ⇒ 原始字节比对判红，而内容
            // 逐字节相同）。冻结夹具要冻的是**包内容**这条合同，不是压缩实现的副产品。
            val expected = zipEntries(fixtureZip())
            val actual = zipEntries(bytes)
            assertEquals(expected.keys.toList(), actual.keys.toList(), FIXTURE_MISMATCH_HINT)
            for (name in expected.keys) {
                assertContentEquals(expected.getValue(name), actual.getValue(name), "$FIXTURE_MISMATCH_HINT 条目=$name")
            }
            return
        }
        val dump = repoRoot().resolve("build/author-loop-fresh.b64")
        Files.createDirectories(dump.parent)
        Files.write(dump, Base64.getEncoder().encode(bytes))
    }

    /** 坏状态：包身份改成空白串，资源目录同步成 `.assets`（check-mod 文案要求的同步重命名）。 */
    private fun breakPackageIdentity(module: Path) {
        replaceOnce(
            declarationsFile(module),
            "const val PACKAGE_ID: String = \"$TEST_PACKAGE_ID\"",
            "const val PACKAGE_ID: String = \"\"",
        )
        Files.move(module.resolve(TEST_ASSET_DIR), module.resolve(BAD_ASSET_DIR))
    }

    /** check-mod 面：空白前缀 ERROR 点名条目（`.assets`）与判据（来源目录名）。 */
    private fun assertBadIdentityCaughtByCheckMod(module: Path) {
        val check = runCheckCli(module.toString())
        assertEquals(1, check.exit, "check-mod 应报 1 error：out=${check.out} err=${check.err}")
        assertTrue(
            check.out.contains("资源目录前缀非法：$BAD_ASSET_DIR"),
            "应点名条目 $BAD_ASSET_DIR：${check.out}",
        )
        assertTrue(check.out.contains("来源目录名"), "应点名判据（来源目录名）：${check.out}")
    }

    /** pack-mod 面：真入口拒包（退出码 1），且不得留下半截 zip。 */
    private fun assertBadIdentityRefusedByPackMod(module: Path) {
        val zip = outZip(tempDir, "out/bad.zip")
        val pack = runPack(module.toString(), "--out", zip.toString())
        assertEquals(1, pack.exit, "坏 PACKAGE_ID 必须拒包：out=${pack.out} err=${pack.err}")
        assertTrue(
            pack.err.contains("pack-mod: 拒绝打包：check-mod 有 1 个 ERROR"),
            "stderr 拒包文案：${pack.err}",
        )
        assertTrue(
            pack.out.contains("pack-mod: check-mod 1 error(s), 0 warning(s)"),
            "汇总行：${pack.out}",
        )
        assertTrue(
            pack.out.contains("资源目录前缀非法：$BAD_ASSET_DIR"),
            "stdout 复述 findings：${pack.out}",
        )
        assertFalse(Files.exists(zip), "拒包不得写出 zip：$zip")
    }

    // ------------------------------------------------------------------
    // CLI 直调捕获（口径同 PackModTestSupport.runPack / ModCheckCliTest）
    // ------------------------------------------------------------------

    private fun runNewMod(args: Array<String>): PackRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = PrintStream(out, true, "UTF-8").use { outStream ->
            PrintStream(err, true, "UTF-8").use { errStream ->
                NewModScaffold.runCli(args, outStream, errStream)
            }
        }
        return PackRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun runCheckCli(vararg args: String): PackRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = PrintStream(out, true, "UTF-8").use { outStream ->
            PrintStream(err, true, "UTF-8").use { errStream ->
                ModCheck.runCli(arrayOf(*args), outStream, errStream)
            }
        }
        return PackRun(exit, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun fixtureZip(): ByteArray = Base64.getDecoder().decode(FIXTURE_BASE64)

    /** zip 条目内容（名 → 未压缩字节），按条目名排序；与压缩实现无关。 */
    private fun zipEntries(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out[entry.name] = zis.readBytes()
            }
        }
        return out.toSortedMap()
    }

    private companion object {
        /** 作者写的、可编译的最小补丁源（app 侧导入段用配套的 mini base 夹具编译）。 */
        private const val AUTHOR_ERB = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 骨架自述版本（NewModTemplates.kt 的 const val VERSION；元数据条目校验用）。 */
        private const val SCAFFOLD_VERSION = "0.1"

        /** 作者放进去的资源相对路径（相对 `<PACKAGE_ID>.assets/`）。 */
        private const val AUTHOR_ASSET_REL = "img/logo.bin"

        /** 包里该资源的完整条目名。 */
        private val AUTHOR_ASSET_ENTRY: String = "$TEST_ASSET_DIR/$AUTHOR_ASSET_REL"

        /** 坏包身份下资源目录同步成的前缀（空+`.assets`）。 */
        private const val BAD_ASSET_DIR = ".assets"

        /** 「一张图」的合成字节（读取侧不校验图片格式）。 */
        private val AUTHOR_ASSET = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

        /** 夹具漂移提示（两段接力共用一份字节；模板改正文属预期漂移，别当 bug 排查）。 */
        private const val FIXTURE_MISMATCH_HINT =
            "pack-mod 产物与冻结夹具不一致：骨架模板或打包合同改了？属预期漂移，一条命令重钉：" +
                "给测试 JVM 置位 -Deratw.refreshPackFixture=true（或环境变量 " +
                "ERATW_REFRESH_PACK_FIXTURE=true）后单跑本类，两份源文件的锚点区会被一起重写；" +
                "详见本文件 FIXTURE_BASE64 的 §刷新夹具"

        /**
         * 真 `pack-mod` 产物（zip 字节，Base64）——第二段 app 导入测试内嵌同一份常量。
         * 内容（条目字典序、时间戳 1980-01-01）：`eratw-pack.json`（打包器写的包级元数据，
         * `{"schema":1,"packageId":"eratw.mymod","version":"0.1"}`）+ `main.ERB`（作者源）+
         * `<PACKAGE_ID>.assets/README.txt`（骨架自带，含 `NewModTemplates.assetsReadmeFaces()`
         * 的设置/服务/耗时/日志四面正文）+ `<PACKAGE_ID>.assets/img/logo.bin`（作者资源）。
         *
         * 本夹具逐字节冻结 scaffold 全部生成物（含 `<pkg>.assets/README.txt`），任何修改
         * `NewModTemplates` 正文的波次都必须同步刷新它（CA 加根条目、CE 补日志面各撞过一次）。
         *
         * §刷新夹具：夹具常量正文夹在两行锚点注释 `// @eratw-pack-fixture-begin` /
         * `// @eratw-pack-fixture-end` 之间。给测试 JVM 置位刷新旋钮 `-Deratw.refreshPackFixture=true`
         * （或环境变量 `ERATW_REFRESH_PACK_FIXTURE=true`；Gradle 命令行 `-D` 默认不进 fork 的
         * test worker）后单跑本类 ⇒ 跳过逐字节比对，把真产物按 96 列重写本文件与 app 侧
         * `ModImportAuthorPackEndToEndTest.kt` 的锚点区（两份逐字节相同），再不带旋钮跑一次确认。
         * 清空常量仍会落盘 `<eratw-android>/build/author-loop-fresh.b64`（手工排障用）。
         */
        // @eratw-pack-fixture-begin
        private const val FIXTURE_BASE64: String =
            "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAPAAkAZXJhdHctcGFjay5qc29uVVQFAAGANc4Sq1YqTs5IzU1UsjLUUSpITM5O" +
            "TE/1TFGyUkotSiwp18utzM1PUdJRKkstKs7MzwOKG+gZKtUCAFBLBwhOTLDRNwAAADYAAABQSwMEFAAICAgAAAAhAAAAAAAA" +
            "AAAAAAAAAB0ACQBlcmF0dy5teW1vZC5hc3NldHMvUkVBRE1FLnR4dFVUBQABgDXOEq1bbVMbR7b+rl/RxSeTayQ7m03tTXld" +
            "hY2SsAHsCyS7e+tWuWQY20qEREnCG28qVQJbIIGEeH8TGIR58wsSjgkWAsF/idUzo0/7F+5z+syMhMHevbc2tbUezXSfPn1e" +
            "n3O60cK+6N/cfY/6Qr3/OF6qLmVkKm7++kQvTRjZvCzPGhulysmYy6Uvv+IXGCIwWGDcP44Tf/f3/+M4WSnGzF/fysSwzEyC" +
            "Sr+v54cmGqOPjsr8Y312v1Ke1mcOzNOsmpUyxp/L/IEsHMv4pj5/YG4/k4k9vVjUExlj6YkLUyOecPRTzzUeYrws6Psnxswq" +
            "PhJ7pZeVo/J1ty8S0aIRj6gUx8AJD3U+GzOv5XBc5g/ldEIWUzz/XWxQltfl1pD4IfR9qOmq79O7v+v5DBtPuSrFtP7LqTGz" +
            "g43qOzm5Mo/FBO3Ed19r7RW/xWbwPyETL/XUiL6cZWHIibSxm6yUxoVWL8fku9iQeTph5lL68iqEI0/GjKOMqycUjGo/Rt1h" +
            "LRIaCPdoEaGnkka2KAuHIGQWjrAND5YYri7NgAjYAlfm1qA+dmw8GzSyC+bbgjx5gl3w+8rJivnrnMMJjT8Zw+6gApdLiHPL" +
            "uUP9WvBSQ6e3uaXd647+GG1oFEJ4PAJLy915cxQzs8ZxSZ8bqRxBWzMy+1QEBwKBC4lpP/oj0ch75EDsfQL3fIGIdiGFH7RH" +
            "kUvEQv1/oCDjO9XHO45gSLnpEVnKQCYX0on4/65dtC3ekz6796FtucztJzKxCHnJzAszXxS29KF7Xp4lTvO3BmUyDSPzyL0T" +
            "+IA+t6yvrEEVxvOSnv+VHoYO8eBxuwX/lpm0M0gf35aJA/BjvNqiodkFPMjhKZl5C1NxyY1tuZeRw4tkD0rTcnwVO6/GJjBH" +
            "pg6rcZJCc7A3HPL3wuBH9dmM/mSVLU2mZ3lrZBUHcfN0RHwm2v03YA9wPXhE7f3Vz+kDLaln942jYezTLOxWDpPm9qBjsKI9" +
            "1NtpCbcrGgprglzhm5ZQD0106clpRXNcHs/q0+PwvuriBLjr8web+/1YVGYK1ViSlFU9WjDzG7R8/qQ6X4QYIWe5vAM3g8Ob" +
            "J4/1uT0KJk2+fr8wH5flaFYZ9wymkNMuZ/X5bV7GfBanbc8f6NDCsz19YdxxyeUd17UOX592vUXrCfjgh/5QEMYVVWyDev1r" +
            "IfNPZWaoOpKSo2tysgwh8xvB7Is/imsd17GbDnFdXBFYDDHM1ecL+u9pkaggO0zsic7uT9ubO1q/9HZ1ez4X8vAAqiMCah/Q" +
            "PtubsTwIEn8UdftPVUrbRvI5GyZEiO0ZyQSFz5F95fHKcd/j+dLt5pvfNH/lvdPaclk81MIRvATZ77ydXa23Oi7XOP+8UfwE" +
            "qWyJn10uOVKCliAvOZGSsePK8ZpwwnJTk80r7xXcc2iWcQTrPT2dV5JjstWZvOIsCw3ADGEfrBAXrATf3RYbolIepzjYIThI" +
            "w/Kq0yf62JRZLmMJPTOBTPIulqouxyiiZfdJ9b7+/nexNFtxzwPNYo/cbn8VjEBM1ZX16khGH910eTs7b3WSWxUOqy8XsJQs" +
            "T8MjxXts0OrKPDBH/Lm5swOLV9efGLOL5F5vZsmyEhvVmUUOsFfg8hzBmRy0gQRAyWB0U1l7pZiEh8C/9FwCpB5evUKGzWYs" +
            "QsG2kK9XkDWpNVXEjumvcsbRYrU8If77a4+3w/OnZphAUs89g1ODEImSXQ3GDH9XuRAGYpTzWMncicnMnD3yTByPRMP+4P2I" +
            "u1fZBiJeXc5x3w9rWhSfGy6Lvz+ALTRUymtys4yfGhlLw9daIBDCr+999Ovd4PS7oel3gy/fDebeDRYaGi9YJnTvAyvY0RVs" +
            "KplZXNub05dfWBqA8eHl2lvEW2VFKSXX7AVrPfBFPrwY1oJOyKYWCnJii4kjmpmFTZC9gFrAF7w/gLR9LrecSzR1/Btrg4Rn" +
            "HsAgIDEPBEWKPpMh6lRu6ztbr1l5Gq/mjuotAKGsuvIUKYK8bOKJuPZH8furnwpOBOxW2AInH84A+KQX0h62fs8dj9vTRPCk" +
            "OnsqS1s0//PP3ncWeFXongciFIirldJwpTzsgBj9l5yw7AWeBufAcnWeoY+WlUPUXE3uva6uLCJws7rk4IhRWmcPRVDJnhjJ" +
            "EUQJY3+NZv+al6+HzkR8awTHt8NVfebEAY4kAiAvfx80E/Hc9QWDWtjdH7zPjkiAc22QPCljmQ+F5t158gEVEb/1t9LMSw3n" +
            "CMCqfYFoNyyATJsBK7PG3DQ01qm8patNGG/mQLOdaHoDWp8WjLqZtg0pKJsQrfNLNZ6xH/3pk2psTS7ukAwmyPfl5ry+lqu+" +
            "SLkcwEER5nntGfagzxT01KC+diyPM3gD+Iuty43X5v4mGVr+tFI80ou/6KvTzsb0xJxMr1XKaT03jHxj5vcsFZKSKpi0/1wB" +
            "CTUXZiBu+KN9vv4vfT1I4I+sJaCB2x1fYcU/3fbSP3/W7t6m6CeLReR/EIe8mCs2TXlUMvN58+QQ1lfDEDDg/h9psZoRggHe" +
            "taM5ym224nnr7Lx2qkvUxEP7oLiu1E3SeTMrVLC3rM7BJfqbHTmcIjNSahX/ISqljUppqpo7hBywA8EwAIq1s38o6A367gY0" +
            "K7fWQsWAH/jxPgCsFv5/mxUyr+/HLn+vdvtHofy68QML4E0YikDaPhvkYHkDoPLQH/GDR9D4SUT9PT/cDA0EowQ/2sTPtYzu" +
            "7JX2FfDd1QLfaI9QQIzIiUkOj/WpSsXbBECrvruOOsgcnLFqrkwBgrLTGSnZXl0vZBC29NVNs7BujO/p2GbsWHl9dfVNdX1S" +
            "Jb+rCtWp3x/zcd9Arz/kiT6Ab7lD9/+ph2erL3b0pwdULao3oEihFvuLuLu/9rZ77zR/29J6i+KgrVCBegpmSwHreBb2biyv" +
            "mnuP8XBWzYoRd3/A9+jS+9Qui0Ao1A+hR8MDGpQQCgwop7/i/sM9Tjr0AS/Yp+TbX2Rm3pgYPkc9Eg31fzzTfCD5HA7B90gw" +
            "v+T05SR2rE8Biq/KfBIFkA0xvmsXRunU2Bmzt5dg+MUvyUk5XIyOiv6w1o8474lEfWFEjNQLiusT4zKzx55UH5GcmgFOzdWC" +
            "8LjkSoYK5sUd4VE1BgryqV1SxuKOuf4S8YflywVtfXVHENicKNeFJN1C8mljbY4Le4I9+RO5MULpZfUNwAHtFeb4uKzPr8Eg" +
            "yNbYEDCNGxHsdLb9LQEyWqX5q1ecZc23k4K0K5B6jScUYyyGEBswYCVns2SeLlIA2pw0Sqi74pQIF9NGaZuZxCcgQrNQAJMu" +
            "iLLewrF/gOMzFg5EPZG+wMhJ2CoLIF4hj8ImWX4fCJaK80sNbrcbaQUcffKJk4XxqDzh3xRGaVv13kIGkFnATzzoY8f6+GYN" +
            "CNTyf0LmUcQW9GPC3+zDgO5m/rk58kJ/FjOyBUi1UnzBS2IiJMdWSlA7MeHsRkVxBXDxG3T7tbCSRESLEsaLKOAAW/9IFWcX" +
            "YA7UZv0TS0w49yu4kqM7qHtQbEMg5BqxYwBsAo6pwcphXKbmsAobYeVkm8KJ+XZH7i4AwFEaxvPrWOV0BbVCrX8DjI7AOJZG" +
            "SOQGzQfyTIvPH3jUjehdK+P8wWgX75CDT5e3u7u146s7397+qrO5xXvn5tfNHTe9l4HP7vkGApRlfn+l7bIIA72qQNTmdl+9" +
            "cqWNssrdUCjwUVrejuYbbd6WemIUvmgukkzfhXM7b7W13Wm/1XKGBTXC+XKns7mj5Vb7ZdHjC/b6e31RLYIxAaS1W/cuwXAb" +
            "aQHA7gvpd3v/0l1PuqHhgl4PpaLlV6xEcqzy+pmSx7IQN2T5MSHWIzyn/iDg9fpIPh2jMPRqy0DNSYaevYg+CVhtCJOojnCe" +
            "SXqkbfXCVTNjK2gDsMc3ZeYZVG4lWGVhbJH6DAEThdKB8Ml6stQ/RJqLHRvH07A8KruR76hjkKLSJrGIObYwxuFt1ZE0Cmgd" +
            "hekMKuIEN2CW03I0x64Eo1Xd2G9C0YA/KPgTV6iTsvhYnzu0KxbVi31KmT01RN3WcWLbls8o1W0HCXP/EDPIFdvrHA6J0tfb" +
            "fA8gxqrDwJq45w/2vl+fauGHfurH9Q/chYk8uNT+6M+hcKC3i99/8QX8OhJxf+976LuMMqA/cNmZ6vRZyZ4e+gLCogWrOUed" +
            "Vv4Iaas6hX4qpRJL4UzLD0bGu+aqC3GEQpgtLYp6G0u0R34zf6B2CumhUFtEmjOGqCUpMyk1WKHkdBEqopCaT5rP4ozjsZ5+" +
            "tCTTb/SpjepMjKmpMHxhWodeHfFTHD6Zp2hUoJ9QPPWpJneAgIHDCQejdlhOsqo5niuzMGPzYNcyi8IRUiCsE3b18OrvBHXn" +
            "hxcR9AivLRQq5WUzFldDk1bNQEwFoPKeRz1AVx4FRPHPg1CI/tEeokgSMoty9wUkgOIWURHPyvY48KIkMfe3qCJWxLEQMjsG" +
            "0te3rxEkK8UlMAxbN3ObGKxy6Rn7ifr74IvuiA+moXFjmBSZSopoKOoLdPiCoYhA2lad4Cw7IDaKUpkVjCrLzBFCQgTgZhX5" +
            "02j2bNvYWgWlnha1YButUoxTkabMm2mCDmmi/FpOg85Lq5f4AVo9oXDvpQbfQPRBKAxwT5kByL8f4D5IXDeeD3vJMZYZ7M94" +
            "NXOeZp/miwxQk+c8UaoJekNdoT4t+oCCbqP4mQEllQAxDiZQOJA/a8blMo7UKcT6YnVqVXHU7QfWhf+r3jYAzUuYoSxtUS14" +
            "Qq1dQWibjDvSE+rXqH41FsnECS1e/fQPVgODXuwzwYjlIZRUgS3BiuCp1MhI7IlPf/+5QNigph+/hx8oyALkVjZmN7k1bCyP" +
            "UV9YWQzBP+pOky447M1vytN5tm9bWAEALy1MkFMZnT4+hQJGodQUw7oLqx4XN21gfmfpIMvcC9mAzCP+5gsHa7+0cDgU5p+U" +
            "DgciWiNhau7eFVA1HVHrEZDxV+h0FGzSSdaZkow4T8GUZO4pIUq1HSIxs1opbYvbHALbFCcItEliciVn5GCSc5hYXfgFGrX2" +
            "qebKxFsjMYzofL/HF1VIbk6PrzO+sVyrSQH9lZzd/r9y5YrTfFrSEy/0ud1KsURt1LpPiBzvYilUnZd4RKPq0abUnqnbbs07" +
            "HCOFKvRIgY0WYztBJNMHAaUT/siNgC/4Awy0UpwByGexMUBikFkprsvjx1arKb1GgGt2r7q+Alu0aVqBxtIUBBNDgGZ2afju" +
            "M9V1TJkHo9XFCeJIvWHy6hTmBSoTLgZk/LGcHDV3t1zkhWAQ22RR1ssHA0mBdKaBnDxuHO1yyrDtzwLHhFpyKZJCMo3iUB+d" +
            "lIkD0UHTVbmVtptmejJWOdq01tkaZGxMK0PFpzlICgiZTGdvEg8gydK2Tv64Eg4FvwxTS8ojGIwi2MFhCQuXp/FgFmbpUCat" +
            "/OQLOk8BTiNcAy/bQz3FZxHV2ATJgDuy5VlwS7uYU71P5Xd2B1c1+8HoBoFetk4F9MkcgfKxoHOS+cAX7tWCbLOXGrkewVIy" +
            "Q6duSN6WbZNcPWxhVsyhYkIOp3l5UeeG3mA07Nci3HsvTSF8scf7a02UiNNZ+RrZiY6TVZn40QaQoKB25iCovqxQMUH1V4xs" +
            "sVJOV6eey10YU4pEFU/Y3YpxuwO19KF6hKeretNuWiXTnAetFrZNwSoQ7D3RTi7VNYpuP/BFNPftTm/jv9pOqJ1RKX6YE327" +
            "XJdaPijDD6z8E6f/putnFrqgH0dIaoM6JHVLc6ak/+qId2oR1ALum7c6AOK/9f4fOybONKrJcym4Q2tHN2rcb293Q7+7CI94" +
            "1em93dZ8E2MyE1ApWPiZ8t+kUxUwd+qk9yUfVeH/ISc6C1guqb5Fyuq1LRwgmbItONOVd2bZAfWZA8ptrWfVeJOl7TprtK29" +
            "WFJ1Ywlc+cL3tWgET/1hf58v/KhbvaDfJH38G9b6A74erZcFZvVvFOdAPy5oB7Joae2mE6/fhqcEXqh/25tbrRe3urrVA9VL" +
            "bd52b0e3A80Igg4u6fOD5luK0hyZSCCoMsp5fWwLAZ5qp6NpIz+Pl3r2VE+vs8+61AoU9Tbm6JBcjeEjZAgNZs7dKeJWOSV5" +
            "GTFneRRS/H7BzOeAaElhhLI+3ri14sCY1TtXVIg3W4mVoyOnFvr3hAFFEPaA3GGpfrtcfVymMIBIfN7xL3L2SQah1jHYNiEn" +
            "FHIUUI8mZX7JMbVar+D70F2ub1uaW9v+eqe79eY3lwl1hx+1+B5RzX21rfFfcxLOl9xzg2o5LmEFcmVe4utbt765093c+ZUX" +
            "5TlZrOPyX7b+hd/c6ueTZ5hnKOyPPiIGrjRaiNwRSz14xwZQRVNp9t42KIx4PlGJC9wo6YpPPARZL+KeBGTHEUcVdcuoQqQW" +
            "vC41eL+DaX/Z2tnVDVjmpa83Bnp+0KLujlud7c1ttLp6+37k+bmOKImmjub7QnIHYU3nJUWk6Z1NGcT8wQHtDGXlzhF3ZOBu" +
            "pCfsv6tdujHgD2AY02lp/uudLqzBK9iCmt80Tw6N2ZSS0r/5yOJfOKFQu0e8+PZOW/MNb9s/P54Az1QUjxwxw5a3UocUmbyP" +
            "ytCNbeqTlKeoBkHOX920bo6ogVbJNnZM9bXtGCi0q4tPZGyRjxXpPgUHP8o069WFON3tOiq5HAnCw+jZ29GCp+abFBipDEYl" +
            "kV9CbOLZBGrsbE599+VX+pt1kFSNHKJnJU38VAzq+yco3BExLSgHJEeFt0p7CAlWW6FTCZHjCaEahdda/BEVbxAmuPgSA0Fb" +
            "2hSqZlYBAqkLrRreZPKQ0GbZxoyqwZ2kZsCROhtUd+0QrsUNxAvaVXFLqJaHRtcBfjxzBKsv5quxJWG1ncCw8HbeICRs4ZRx" +
            "vt5HGJOJx3fospzaMYtBNN9uVfnzJEtQkJYVZv55pbxGHV67nrd7TkzFperlmILq9YwluZNv3w0k8VhLcaf8NGvsjEFK5ttJ" +
            "usqUf+zcGeKOl3NbkNCwKpo5+VeKG/ouHZe6fptcEwR/j2PI3OcFh4Stx47U7Rs6FSO5EGduamqp0tGCrPE3bGi/TeZEfRPv" +
            "Q0RlOge2XdSkUcgOPkBDc3ni/jSH4IVB2BIzShlkcEnmIfM0t/XAL/nD6xgljWdxs1ymJtCbHf1Jhlsb+i+LRv41HVAcrlrF" +
            "7Ngr4+UYi4x2zuzQTazSDHXdgVdXHqsqKGUPSsn953rpMZ0QF7dQmtKFMCypsoNzgUq1pKDdSmnY3H6mr2zCPPimlb0R2yRj" +
            "LHPjeK5eyy46UrAvp3W1fKOSau8fqOVYu015PGcWJvn6E7QF6xfKPpxLbV1ROk9RUylI9goeCxlTz2o56bIWFNZ9x0yBG7py" +
            "boi9BuRUVlaXMOjsBW4ct5qc3Le39LhrHWZww5VlT9ulGz9U6FEtCicGPQX2VH6+Vre96567xGBTNBQKRDzX+MrQdQ823NQU" +
            "8N99byxwXPReKNwX8fj4ddPvPrMf3d/7wuJ/LJjc1BQaiPYPRIWi7qHt8JO1cc8Pyt88fT5/kKKskuRCoTbcU6cSsqXlVxxd" +
            "COXaghBf/hcFRkcSXA6qjol1Ql5/QN1OTSdWfSnFqqc4hSDxekgvxmV61gFE7u8joaBLTqXqDUPwxWDWN08T6sYwQsUGoZQV" +
            "VEx58g3IGx+EvnZolLZZn2coC+paFjf1tWPxpy6K7SkXXZwpZYz4Nt1AXFmv5kqqMZiwb8gBbvf7v7N+kLksD2Ja3TuQ5Gs2" +
            "7Pi1u76WCalrYa4zaJHCOSFa50IeKAJchB/1h/zUILUuAI078qXRTU11Q/hKJxKNOvtVtvWTMoAGZ/2GL0T9NYGGy/zd2hd9" +
            "veK+ar+tkX5vGiuvwSqAapa/xDcD+VLNfS1YuxZ4zfJU5V/X2R7FNYx1QzX0uyfUq+GFo8frdbZbt8XzFnTmQrgzXbDxoYoi" +
            "69iYs1+zEZDkaEG3mqcMhXwWwCA2Uc0d8hGqauFZY35LbvBJNkKm66zxqE9X/vNz60YwBtSUTUesat98b5xPVjGiXq3ldWP0" +
            "oFIsua5Zl7ToDCR93X2NoQ2dJ+CnUBdXx6m2OdpAiqpXNeU2FD9Hi7YYKcGpX7V1CAYl5mQmbZ6OIAjDKmGhFFv1N7MAR+re" +
            "MEW8aiwGHVIVqi64U7iaSMAz+D0ygriKj9Y6fEGESlz7irDcXYC71QZ/qi4O2QGC0izd6VriwyLhDfugQbrCDCChDnZ6BJ3X" +
            "Z4acy0/EmJJAdWuOr6+xWOgQVQVXPhJBDrIOT+K7xnYaNSRkTS7Aa7OQrYNrdXJXi8NCnedQTjy7srqnQJnRYlPUXckiFHk8" +
            "JItFdedqUQcASqzI7TGZmnM+8aW29AjMjcxgfApidukzh/bdWqra9Lk9zl2cEAb5FTtR7Sf/8UH6DeIM5XxOzByiU8n6P0SA" +
            "9I0sXa4jCIBZEynrK4rDmR31Zwggt/5UQd4FuoWRHzNmF0mB6q4wXQeYeW0lreVVOmeaOaCbtqkknwUhLjuCxwBzbFMmCE7J" +
            "kWHCCNMvCKUdlcmX8oP62BQZYmqZfiotuejmeXYNgkcyJFyqDrXIGZUZpGViHoidFUlx/ISQCOVNxSzbltxYonMiZnbpSb00" +
            "TBBNxTl3oEIm60Q9bB1i2S1TxmukkQJhRwYhMA7S4T9DZvrWkLmdYHxmtabo4DWprxXVVUk6KHsWd/5e5LfhKcaZeEA8MQuD" +
            "BKGdeEUugq1TVCKJnmbtuyxL9VyYOZTZdLuuXR0QMWULIsaSnPFSVHkoZqrrTxSHg4wVqa2YTLOJmRNlZRx0TPiMxFkcQj1E" +
            "4KVuNX5J5NlvaJ8pRp8Mg50B6i9zxmAH3OcCB9YmAMqWSzxMlqad6fVbxHQX8CKjdGr8qk3B0PjEQiHzJeuUDeARz4UV9j3+" +
            "kxe6SxbbBuBFbKdaSzXBmQifHLxTyvyYkRG8hjPKiYIcXHaGk5uzdVeOF7AldYnHQuMYDSdWMEAVaOeAts058Me4Tcu+5+EU" +
            "CuSTVps6a9kiog/I0IVm/oMkyJNjr4LmTqWCMWr+OLeO31ufrdu2sjMWdIYRszCqz+3yplyODauzjALChGrcJ43lMYtbKDz+" +
            "RgXoV1hEnCHFt4XsuwODLGdq9U0U+BoQCKvYUDLzOYAhSl/qPkzlZNssbxHM+piGnJBeH+fZ2LBT+5oPb1TJaYflQZfITlco" +
            "WEyMM9uwUzKaMUuWLgbsZJP2hYtaePhfUEsHCNTL7v3nGgAAszYAAFBLAwQUAAgICAAAACEAAAAAAAAAAAAAAAAAHwAJAGVy" +
            "YXR3Lm15bW9kLmFzc2V0cy9pbWcvbG9nby5iaW5VVAUAAYA1zhLrDPBz5+WS4mJkYgYAUEsHCDFba8QNAAAACwAAAFBLAwQU" +
            "AAgICAAAACEAAAAAAAAAAAAAAAAACAAJAG1haW4uRVJCVVQFAAGANc4Sc3CK93D18fHncnb08VFwjPf3c9Uw1uQKCPL0C/FR" +
            "AEvFBziGOHtwBbmGhAb5KRhwAQBQSwcI+gV0szIAAAAzAAAAUEsBAhQAFAAICAgAAAAhAE5MsNE3AAAANgAAAA8ACQAAAAAA" +
            "AAAAAAAAAAAAAGVyYXR3LXBhY2suanNvblVUBQABgDXOElBLAQIUABQACAgIAAAAIQDUy+795xoAALM2AAAdAAkAAAAAAAAA" +
            "AAAAAH0AAABlcmF0dy5teW1vZC5hc3NldHMvUkVBRE1FLnR4dFVUBQABgDXOElBLAQIUABQACAgIAAAAIQAxW2vEDQAAAAsA" +
            "AAAfAAkAAAAAAAAAAAAAALgbAABlcmF0dy5teW1vZC5hc3NldHMvaW1nL2xvZ28uYmluVVQFAAGANc4SUEsBAhQAFAAICAgA" +
            "AAAhAPoFdLMyAAAAMwAAAAgACQAAAAAAAAAAAAAAGxwAAG1haW4uRVJCVVQFAAGANc4SUEsFBgAAAAAEAAQALwEAAIwcAAAA" +
            "AA=="
        // @eratw-pack-fixture-end

        /** 刷新旋钮的系统属性名（`-Deratw.refreshPackFixture=true`）。 */
        private const val REFRESH_FLAG = "eratw.refreshPackFixture"

        /** 刷新旋钮的等价环境变量名（Gradle 命令行 `-D` 默认不进 fork 的 test worker）。 */
        private const val REFRESH_ENV = "ERATW_REFRESH_PACK_FIXTURE"

        /** 夹具常量区起始锚点行（含行首缩进与换行：KDoc 散文里的同名字样不会被误认）。 */
        private const val FIXTURE_ANCHOR_LINE_BEGIN = "\n        // @eratw-pack-fixture-begin\n"

        /** 夹具常量区结束锚点行，形式同 [FIXTURE_ANCHOR_LINE_BEGIN]。 */
        private const val FIXTURE_ANCHOR_LINE_END = "\n        // @eratw-pack-fixture-end\n"

        /** 重写夹具常量的每行 Base64 字符数（`"…" +` 拼接线口径）。 */
        private const val FIXTURE_BASE64_LINE_WIDTH = 96

        /** 本文件里夹具常量区所在源文件（相对 `<worktree>/eratw-android`）。 */
        private const val MODGEN_FIXTURE_SOURCE =
            "tools/modgen/src/test/kotlin/com/eratw/port/modgen/AuthorClosedLoopSmokeTest.kt"

        /** app 侧同一份夹具常量区所在源文件（相对 `<worktree>/eratw-android`）。 */
        private const val APP_FIXTURE_SOURCE =
            "app/src/test/kotlin/com/eratw/port/android/ModImportAuthorPackEndToEndTest.kt"

        /**
         * 刷新旋钮：JVM 见到 `-Deratw.refreshPackFixture=true`（系统属性优先）或
         * `ERATW_REFRESH_PACK_FIXTURE=true`（环境变量等价入口）时置位；缺省不置位。
         */
        private fun refreshRequested(): Boolean =
            System.getProperty(REFRESH_FLAG) == "true" || System.getenv(REFRESH_ENV) == "true"

        /**
         * 把真产物 [bytes] 的 Base64 重写进两份源文件的锚点区（本文件 + app 侧），两处逐字节相同。
         * 内容未变时不落盘 ⇒ 连续两次带旋钮跑不再产生 diff（幂等）。
         */
        private fun refreshPackFixtureSources(bytes: ByteArray) {
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val block = fixtureBlock(base64)
            rewriteFixtureRegion(repoRoot().resolve(MODGEN_FIXTURE_SOURCE), block)
            rewriteFixtureRegion(repoRoot().resolve(APP_FIXTURE_SOURCE), block)
            println(
                "pack-mod 夹具已按真产物刷新（本文件 + $APP_FIXTURE_SOURCE，${base64.length} 字符、" +
                    "两份逐字节相同）；请去掉 $REFRESH_FLAG 重跑确认",
            )
        }

        /** 渲染锚点区正文：常量声明 + 每行 96 字符的 `"…" +`（末行不带 `+`）。 */
        private fun fixtureBlock(base64: String): String {
            val chunks = base64.chunked(FIXTURE_BASE64_LINE_WIDTH)
            val body = chunks.mapIndexed { index, chunk ->
                val suffix = if (index == chunks.lastIndex) "" else " +"
                "            \"$chunk\"$suffix\n"
            }
            return "        private const val FIXTURE_BASE64: String =\n" + body.joinToString("")
        }

        /** 把 [file] 两个锚点行之间的正文换成 [block]；内容相同不落盘，换行符沿用原文件。 */
        private fun rewriteFixtureRegion(file: Path, block: String) {
            val raw = Files.readString(file)
            val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
            val text = raw.replace("\r\n", "\n")
            val beginAt = text.indexOf(FIXTURE_ANCHOR_LINE_BEGIN)
            require(beginAt >= 0) { "夹具起始锚点缺失：$FIXTURE_ANCHOR_LINE_BEGIN @ $file" }
            val bodyStart = beginAt + FIXTURE_ANCHOR_LINE_BEGIN.length
            val endAt = text.indexOf(FIXTURE_ANCHOR_LINE_END, bodyStart)
            require(endAt >= 0) { "夹具结束锚点缺失：$FIXTURE_ANCHOR_LINE_END @ $file" }
            val updated = (text.substring(0, bodyStart) + block + text.substring(endAt + 1))
                .replace("\n", newline)
            if (updated != raw) Files.writeString(file, updated)
        }

    }
}
