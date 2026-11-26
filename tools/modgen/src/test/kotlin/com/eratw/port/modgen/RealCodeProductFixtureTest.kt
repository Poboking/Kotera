package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 跨模块 e2e 的真产物夹具（第一段）：用真实 `pack-mod --code` 打一个带代码产物的第三方口上包，
 * 把产出的真 zip 字节冻结成 Base64 常量，供 `:app` 侧 `ModImportRealCodeProductEndToEndTest`
 * 内嵌同一份字节、走真 `ModImportController` 导入。
 *
 * 为什么两段接力而不是同进程 A→B：`pack-mod` 的入口是 `internal object PackMod`，CLI
 * `main` 对 pack-mod 分支直接 `exitProcess`（`ManifestBuild.kt`），且 `:app` 测试源集不依赖
 * `:tools:modgen`（本 lane 的授权面也不含 `app/build.gradle.kts`）—— 所以只能在 modgen 测试侧
 * 真跑一次、把字节冻结成常量，app 侧解码同一份常量。与既有 `AuthorClosedLoopSmokeTest` ↔
 * `ModImportAuthorPackEndToEndTest` 同一套接力口径。
 *
 * 包身份是本次 relay 的关键：app 端 import 的 packageId 不由包内自述决定，而是按**源文件 diskName
 * 集合**派生（`ModImportController.packageIdFor` = `kojo-` + sha256(排序后名字 join '\n') 前 8 位十六进制）。
 * 因此夹具模块的 `PACKAGE_ID` 必须**预先写成该派生值**，内层 `.mod` 的 `manifest.json.packageId`
 * 与它同值，`pack-mod` 的 packageId 校验与 app 端的 identity 校验才能同时通过 —— 这也是真实作者
 * 流程里「模块自述 id ≠ 导入端派生 id」这一产品缺口的证据（见交付报告的 TODO(verify)）。
 *
 * 确定性：`PackModZip` 固定条目时间戳 1980-01-01、按名字排序、`BEST_COMPRESSION`；内层 `.mod`
 * 也用同一固定时间戳现造 ⇒ 同一输入两次打包逐字节相同，Base64 才能冻结。改动本类夹具或
 * `NewModTemplates`/`PackModZip` 格式的波次必须同步刷新 [REAL_PACK_BASE64] 与 app 侧同名常量
 * （本类每次运行都会把最新 Base64 写到 `tools/modgen/build/code-e2e-fixture.txt`，一条命令可重取）。
 *
 * 跨模块哨兵（entrymanifest 波之后，内层清单带 `entrypoint`）：[FIXTURE_ZIP_CONTENT_SHA]（包内容摘要）
 * 与 [SHA16_HEX]（内层 `code.mod` sha256 前 16 位）在 app 侧
 * `ModImportRealCodeProductEndToEndTest` / `ModCodeCarrierAssemblyEndToEndTest` 有同值常量，两侧
 * 各自对本侧解码字节复算后断言；只重钉一侧 Base64 或只改一侧 digest 都会在那一侧红。
 */
class RealCodeProductFixtureTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `real pack-mod product with code artifact is frozen as cross-module fixture`() {
        val module = tempDir.resolve("module")
        val packageId = appDerivedPackageId()
        assertEquals("kojo-224af7e0", packageId, "源文件名集合变了？packageIdFor 的派生口径要以 app 侧为准")

        writeModuleFile(module, "main.ERB", PATCH_SOURCE)
        writeModuleFile(module, "$packageId.assets/README.txt", ASSET_README_TEXT)
        writeModuleFile(module, DECLARATIONS_PATH, declarationsSource(packageId))

        val codeMod = tempDir.resolve("author/code.mod")
        writeDeterministicCodeMod(codeMod, packageId)

        val first = outZip(tempDir, "out/first.zip")
        val firstRun = runPack(
            module.toString(),
            "--out",
            first.toString(),
            "--code",
            codeMod.toString(),
            "--entrypoint",
            ENTRYPOINT,
        )
        assertEquals(0, firstRun.exit, "out=${firstRun.out} err=${firstRun.err}")

        // 同一模块连打两次必须逐字节相同（时间戳/排序/压缩级别都固定），否则夹具无法冻结。
        val second = outZip(tempDir, "out/second.zip")
        val secondRun = runPack(
            module.toString(),
            "--out",
            second.toString(),
            "--code",
            codeMod.toString(),
            "--entrypoint",
            ENTRYPOINT,
        )
        assertEquals(0, secondRun.exit, "out=${secondRun.out} err=${secondRun.err}")
        assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second), "pack-mod 产物必须确定性")

        // 真产物布局：源 + 资源 + 打包器写的包级元数据 + 代码产物，全部按名字字典序。
        assertEquals(
            listOf(CODE_MOD_ENTRY, PACK_META_ENTRY_NAME, "$packageId.assets/README.txt", "main.ERB"),
            entryNames(first),
        )
        assertContentEquals(Files.readAllBytes(codeMod), entryBytes(first, CODE_MOD_ENTRY), "code.mod 必须原样字节")
        val meta = String(entryBytes(first, PACK_META_ENTRY_NAME), Charsets.UTF_8)
        assertTrue("\"packageId\":\"$packageId\"" in meta, "meta=$meta")
        assertTrue("\"version\":\"$PACK_META_VERSION\"" in meta, "meta=$meta")

        // 内容寻址名 = 代码产物 sha256 前 16 位十六进制：app 侧 `ModCodeStore` 用它命名文件，
        // 这里先钉住，app 侧再按同一字节独立复算核对（两段不共享实现）。
        val codeBytes = entryBytes(first, CODE_MOD_ENTRY)
        assertEquals(SHA16_HEX, sha256Hex(codeBytes).take(SHA16_CHARS))

        val base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(first))
        // app 侧对本侧解码字节复算同一整包 sha256（跨模块哨兵），只重钉一侧时那一侧必红。
        // app 侧对本侧解码字节复算同一**内容摘要**（跨模块哨兵），只重钉一侧时那一侧必红。
        // 比内容摘要而非整包 sha256：压缩流随 JDK 的 zlib 版本而变，跨机不可比（2026-11-27
        // 在 Linux CI 上实测判红，解包后的条目内容逐字节相同）。
        assertEquals(FIXTURE_ZIP_CONTENT_SHA, zipContentDigest(Files.readAllBytes(first)))
        // 内层清单键序/入口的写侧 pin：app 侧装载面靠 `entrypoint` 解析入口类，键序由
        // `encodeInnerManifest` 固定（改序 = 同步重钉两侧的显式契约，不是读侧依赖）。
        assertEquals(
            """{"packageId":"$packageId","version":"1","entrypoint":"$ENTRYPOINT","apiVersion":1}""",
            String(innerManifestOf(packageId), Charsets.UTF_8),
            "内层 manifest 的键序与值（packageId → version → entrypoint → apiVersion）",
        )
        val snapshot = repoRoot().resolve(FIXTURE_SNAPSHOT_PATH)
        Files.createDirectories(snapshot.parent)
        Files.writeString(snapshot, base64)
        assertEquals(
            REAL_PACK_BASE64,
            base64,
            "真 pack-mod 产物字节变了 —— 请把 $FIXTURE_SNAPSHOT_PATH 的最新 Base64 同步到本类与" +
                " app 侧 ModImportRealCodeProductEndToEndTest 的 FIXTURE_BASE64（两处同源，勿手改单侧）",
        )
    }

    private fun writeDeterministicCodeMod(target: Path, packageId: String) {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            putFixedEntry(zip, "classes.dex", FAKE_DEX)
            putFixedEntry(zip, "manifest.json", innerManifestOf(packageId))
        }
    }

    private fun putFixedEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        // STORED（不压缩）：条目字节 = 内容原样 ⇒ 内层 code.mod 的 sha256 **跨机稳定**。
        // DEFLATE 的压缩流随 JDK 自带的 zlib 版本而变 ⇒ 内层 sha 会漂 ⇒ 连带改掉内容寻址
        // 文件名 ⇒ 连带改掉外层包内容 ⇒ 冻结夹具在别的 JDK 上判红（2026-11-27 在 Linux CI
        // 上实测）。这里要的是**跨机稳定的内容寻址**，不是压缩率。
        val entry = zipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private companion object {
        /** 与 `ModImportController.PACKAGE_ID_BYTES = 4` 同口径的十六进制位数（4 字节 = 8 字符）。 */
        const val SHA16_CHARS = 16

        /** 源文件名集合（单个根 ERB）：app 端 `packageIdFor(listOf("main.ERB"))` 的输入。 */
        const val ROOT_SOURCE_NAME = "main.ERB"

        /** 可编译的最小补丁源（与既有作者包 e2e 同源）。 */
        const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 随包资源内容（app 侧导入后应落 `rt2/<pkg>.assets/README.txt`）。 */
        const val ASSET_README_TEXT = "code-e2e asset\n"

        /** 载体声明相对路径（`ModCheckSources` 只扫 `src/main/kotlin` / `src/generated` 里的 `.kt`）。 */
        const val DECLARATIONS_PATH = "src/main/kotlin/com/eratw/codee2e/CodeE2eDeclarations.kt"

        /** 硬编码契约：源文件名 = "main.ERB" ⇒ sha256("main.ERB") 前 4 字节 = `224af7e0`。 */
        const val EXPECTED_DERIVED_PACKAGE_ID = "kojo-224af7e0"

        /** 包级元数据组合口径（pack-mod 写入 `eratw-pack.json`）。 */
        const val PACK_META_ENTRY_NAME = "eratw-pack.json"

        /** 夹具模块自述版本（写进 `eratw-pack.json`，app 侧落 manifest 后回读）。 */
        const val PACK_META_VERSION = "1.0"

        /** 内层 `.mod` 的假 dex（本闸不解析 dex 内容；dex ABI 闸在 app 侧 ModDexGate）。 */
        val FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)

        /** 固定条目时间戳（DOS 时间下限）：与 `PackModZip.FIXED_ENTRY_TIMESTAMP` 同值。 */
        val FIXED_ENTRY_TIMESTAMP: LocalDateTime = LocalDateTime.of(LocalDate.of(1980, 1, 1), LocalTime.MIDNIGHT)

        /** 每次运行落盘的最新 Base64（重钉夹具时从这里取，相对 `<worktree>/eratw-android`）。 */
        const val FIXTURE_SNAPSHOT_PATH = "tools/modgen/build/code-e2e-fixture.txt"

        /** 真产物 Base64（与 app 侧 `ModImportRealCodeProductEndToEndTest.FIXTURE_BASE64` 同源）。 */
        const val REAL_PACK_BASE64 =
        "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAY29kZS5tb2RVVAUAAYA1zhIL8GZm4WJg4GAAAkWGnU9+G4KYIMzNwMmQ" +
        "nJNYXJxarJeSWhEawsrA2GB6TgjI5jIwNmUIQNG59cWNPWlAFgjzAnXmJuZlpqUWl+hlFefnwfVWKxUkJmcnpqd6pihZKWXn" +
        "Z+XrGhmZJKaZpxoo6SiVpRYVZ+bnAWUMgbzUvJKiyoL8zLwSoEByfq5ealFiSblecn5KaqpRqp4zkHY1SvXNTwGqTSzIDINp" +
        "NqwN8GZk4mLA7StUgM2P6Cag+w4BrIAYu18DvFnZQCqYgLANSF8BqwcAUEsHCFFqHi7TAAAAcAEAAFBLAwQUAAgICAAAACEA" +
        "AAAAAAAAAAAAAAAADwAJAGVyYXR3LXBhY2suanNvblVUBQABgDXOEqtWKk7OSM1NVLIy1FEqSEzOTkxP9UxRslLKzs/K1zUy" +
        "MklMM081UNJRKkstKs7MzwPKGOoZKNUCAFBLBwiVgbsdOQAAADgAAABQSwMEFAAICAgAAAAhAAAAAAAAAAAAAAAAAB8ACQBr" +
        "b2pvLTIyNGFmN2UwLmFzc2V0cy9SRUFETUUudHh0VVQFAAGANc4SS85PSdVNNUpVSCwuTi3hAgBQSwcINNsf7BEAAAAPAAAA" +
        "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAbWFpbi5FUkJVVAUAAYA1zhJzcIr3cPXx8edydvTxUXCM9/dz1TDW5AoI" +
        "8vQL8VEAS8UHOIY4e3AFuYaEBvkpGHABAFBLBwj6BXSzMgAAADMAAABQSwECFAAUAAgICAAAACEAUWoeLtMAAABwAQAACAAJ" +
        "AAAAAAAAAAAAAAAAAAAAY29kZS5tb2RVVAUAAYA1zhJQSwECFAAUAAgICAAAACEAlYG7HTkAAAA4AAAADwAJAAAAAAAAAAAA" +
        "AAASAQAAZXJhdHctcGFjay5qc29uVVQFAAGANc4SUEsBAhQAFAAICAgAAAAhADTbH+wRAAAADwAAAB8ACQAAAAAAAAAAAAAA" +
        "kQEAAGtvam8tMjI0YWY3ZTAuYXNzZXRzL1JFQURNRS50eHRVVAUAAYA1zhJQSwECFAAUAAgICAAAACEA+gV0szIAAAAzAAAA" +
        "CAAJAAAAAAAAAAAAAAD4AQAAbWFpbi5FUkJVVAUAAYA1zhJQSwUGAAAAAAQABAAaAQAAaQIAAAAA"

        /** 代码产物 sha256 前 16 位十六进制（app 侧 `<sha16>.mod` 的文件名主体）。 */
        const val SHA16_HEX = "dcdcd9e5844cefed"

        /** 整包 sha256（跨模块哨兵，与 app 侧两个 e2e 测试的同名常量同值）。 */
        /** 真产物 zip 的**内容摘要**（与 app 侧 `FIXTURE_ZIP_CONTENT_SHA` 同值；跨机可比）。 */
        const val FIXTURE_ZIP_CONTENT_SHA =
            "444ad36fd4beb5d9d1dd6fdd7c1c885ded8d7c52ac6e58e543112143c2cb0e80"

        fun zipEntry(name: String): ZipEntry = ZipEntry(name).apply { setTimeLocal(FIXED_ENTRY_TIMESTAMP) }

        /**
         * 与 app 端 `ModImportController.packageIdFor` 同算法（单元素时 join 无分隔符）：
         * 排序（单元素平凡）→ 名字 join '\n' → SHA-256 → 前 `PACKAGE_ID_BYTES`(4) 字节小写十六进制 → `kojo-` 前缀。
         */
        fun appDerivedPackageId(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(ROOT_SOURCE_NAME.toByteArray(Charsets.UTF_8))
            val hex = digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
            return "kojo-$hex"
        }

        /**
         * 内层清单的入口类 FQN（`pack-mod --entrypoint` 与 `manifest.json.entrypoint` 必须逐字相等；
         * 形态须满足 `<小写包名>.<大写类名>`）。夹具的假 dex 不被真正装载，故只钉契约不建类。
         */
        const val ENTRYPOINT = "com.eratw.codee2e.CodeE2eMod"

        fun innerManifestOf(packageId: String): ByteArray =
            encodeInnerManifest(packageId, ENTRYPOINT, version = "1", apiVersion = 1).toByteArray(Charsets.UTF_8)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

        /** 夹具模块的载体声明正文（文本面满足 `ModCheckDeclarations` 的身份三处同值口径）。 */
        fun declarationsSource(packageId: String): String = """
            |package com.eratw.codee2e
            |
            |import com.eratw.port.modapi.EraMod
            |import com.eratw.port.modapi.ModCarrier
            |import com.eratw.port.modapi.ModCarrierDeclaration
            |import com.eratw.port.modapi.ModManifest
            |import com.eratw.port.modapi.modDeclarations
            |
            |/** 跨模块代码产物 e2e 夹具载体：包身份 = app 端 import 派生 id（内容寻址口径）。 */
            |@ModCarrier
            |object CodeE2eDeclarations : ModCarrierDeclaration {
            |
            |    const val PACKAGE_ID: String = "$packageId"
            |
            |    const val VERSION: String = "$PACK_META_VERSION"
            |
            |    override val packageId: String get() = PACKAGE_ID
            |
            |    override fun newMod(): EraMod = CodeE2eMod()
            |
            |    override val manifest: ModManifest = modDeclarations(PACKAGE_ID, version = VERSION) {
            |        displayName("CodeE2e")
            |    }
            |}
            |
        """.trimMargin()
    }
}
