package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `ModStore.MAX_NAME_CHARS` 的字面量（文本哨兵用）。 */
private val MAX_NAME_CHARS_LITERAL = Regex("""MAX_NAME_CHARS = (\d+)""")

/**
 * 「pack-mod 打得进去」与「导入端/运行期收得下」两条安全判据的对拍表（本表是唯一口径）。
 *
 * ## 为什么是两段对拍而不是同进程 A/B
 *
 * `PackModZip` 是 `tools:modgen` 的 `internal object`，`app` 测试模块看不见它（且 `app` 的 build
 * 脚本不在本波写面，不能加测试依赖），所以：
 * - **pack 侧**：本文件直接调 [PackModZip] 的纯谓词/收集面，断言「打包闸 = 导入端收得下」；
 * - **app 侧**：`ModImportPackModContractTest` 用同形的夹具 zip 走真导入 + `ModResourceStore` 读回。
 *
 * 两段各自断言同一张表，任一侧漂移都会红。运行期真闸（`ModResourceStore.isValidResourceName`）
 * 与导入端逐字符同形（`ModImportAssetsTest` 已有源码文本哨兵），故资源名这一列不再重复。
 *
 * ## 对照表（`Y` = 收下，`N` = 拒/丢）
 *
 * | # | 形态（资源条目名 / 源条目名） | pack 整名旧口径 | 导入端实际 | 结论与处置 |
 * |---|---|---|---|---|
 * | 1 | `eratw.mymod.assets/ui/button.png` | Y | Y（前缀 + 相对名都合法） | 一致 |
 * | 2 | `eratw.mymod.assets/a b/c d.png` | Y | Y（空格在段内合法） | 一致 |
 * | 3 | `eratw.mymod.assets/中文/名字.png` | Y | Y（条目名 UTF-8，探测平手取 UTF-8） | 一致 |
 * | 4 | `.assets/x.png`（PACKAGE_ID 空白串） | Y | N（前缀为空段 ⇒ 每条 WARN 跳过） | **差异 R1**：pack 拒收 |
 * | 5 | `..assets/x.png`（PACKAGE_ID = `.`） | Y | N（前缀 `.` 非法段） | **差异 R1**：pack 拒收 |
 * | 6 | `...assets/x.png`（PACKAGE_ID = `..`） | Y | N（前缀 `..` 非法段） | **差异 R1**：pack 拒收 |
 * | 7 | `a/b.assets/x.png`（前缀含 `/`） | Y | N（嵌套前缀无法 rebase） | **差异 R1**：pack 拒收 |
 * | 8 | `eratw.mymod.assets/a/../b.png` | N | N（相对名 `..` 段） | 一致 |
 * | 9 | `eratw.mymod.assets/a//b.png` | N | N（空段） | 一致 |
 * | 10 | `eratw.mymod.assets/a\\b.png` | N | N（反斜杠） | 一致 |
 * | 11 | `eratw.mymod.assets/a:b.png` | N | N（盘符冒号） | 一致 |
 * | 12 | `eratw.mymod.assets/ctl\u0001.png` / `\u007f` | N | N（控制字符） | 一致 |
 * | 13 | `eratw.mymod.assets/a/b/`（尾随 `/`） | N | N（导入端 entriesWhere 先静默丢） | 一致（pack 根本不产出） |
 * | 14 | `eratw.mymod.assets/a*b.png`（Windows 保留字符） | Y | Y（Android 可写；运行期也收） | 一致（宿主 FS 差异，见下） |
 * | 15 | 源 `src/My.ERB`、`My.ERH` | Y | Y（`ModStore.validateName` 过） | 一致 |
 * | 16 | 源 `v1..2.ERB`（主名含 `..`） | Y | N（`ModStore` 抛错 ⇒ 整包 STORAGE 失败） | **差异 R2**：pack 拒收 |
 * | 17 | 源主名含 Windows 保留字符 `* ? " < > |` 之一 | Y | N（`ModStore` 拒） | **差异 R2**：pack 拒收 |
 * | 18 | 源 `<125 字符>.ERB`（整名含扩展名 >128） | Y | N（`ModStore` 超长） | **差异 R2**：pack 拒收 |
 * | 19 | 源 ` lead.ERB`（主名首空白） | Y | N（`ModStore` 首尾空白） | **差异 R2**：pack 拒收 |
 * | 20 | 源 `<子目录>/x.ERB` 与 `<另一子目录>/x.ERB`（同主名） | Y | Y 但**后到者去重跳过 + WARN** | 静默度低（导入成功页有 WARN），本波不收紧，TODO(verify) |
 * | 21 | 源主名**尾**空白（如 `x .ERB` 不算、`x.ERB ` 扩展名不成立） | — | — | 不可达：扩展名收尾，尾空白不成立 |
 *
 * ## 差异结论
 *
 * - **R1（资源面，真静默）**：pack 旧口径只校验整名 `isLegalResourceName`，而导入端还要求
 *   `<来源目录名>.assets/` 的**前缀是合法单段名**。`PACKAGE_ID` 为空白串/`.`/`..` 时整名仍合法，
 *   导入端却把该目录每条资源 WARN 跳过 ⇒ 资源全丢。处置：`PackModZip.collectResources` 改用
 *   [PackModZip.resourceUnsafeReason]（= 导入端条目谓词），不通过即拒收打包；`check-mod` 资源面
 *   对同样形态报 ERROR（必然失效，非假红）。
 * - **R2（源面，整包硬失败）**：导入端按**主名**落盘 `mods/` 并过 `ModStore.validateName`，pack 旧
 *   口径只看整名 ⇒ 主名含 `..`/Windows 保留字符/超长/首空白时导入整包 STORAGE 失败。处置：
 *   `PackModZip.collectSources` 改用 [PackModZip.sourceUnsafeReason]，拒收消息点名条目 + 判据 + 改法。
 * - 导入口径**不放宽**（`ModImportAssets`/`ModResourceStore` main 只读）：两侧冲突时以导入端为准，
 *   把打包侧收紧到一致。
 *
 * ## 宿主 FS 限制（测试口径）
 *
 * 测试宿主是 Windows：`* ? " < > |`、尾随点/空格的**资源名**在 Windows 上造不出文件，只能做纯谓词
 * 断言（Android 目标端可写，三处谓词都收）。R2 的源文件名在 Windows 上部分也造不出（`*?"<>|`），
 * 同样用谓词断言；`v1..2.ERB`、超长名、首空白名可造，走真实收集面。
 */
class PackModImportContractTest {

    @TempDir
    lateinit var tempDir: Path

    /** 表 1–3、14、15：两侧都收的形态（pack 谓词侧）。 */
    @Test
    fun legalFormsAreAcceptedByBothSides() {
        listOf(
            "eratw.mymod.assets/ui/button.png",
            "eratw.mymod.assets/a b/c d.png",
            "eratw.mymod.assets/中文/名字.png",
            "eratw.mymod.assets/a*b.png",
            "eratw.mymod.assets/a.b/c",
        ).forEach { name ->
            assertEquals(null, PackModZip.resourceUnsafeReason(name), "资源条目应被收下：[$name]")
        }
        listOf("My.ERB", "src/My.ERB", "deep/nested/Sub.ERH", "My Mod.ERB").forEach { name ->
            assertEquals(null, PackModZip.sourceUnsafeReason(name), "源条目应被收下：[$name]")
        }
    }

    /** 表 8–13：整名非法，两侧都拒。 */
    @Test
    fun illegalFormsAreRejectedByBothSides() {
        listOf(
            "eratw.mymod.assets/a/../b.png",
            "eratw.mymod.assets/a//b.png",
            "eratw.mymod.assets/a\\b.png",
            "eratw.mymod.assets/a:b.png",
            "eratw.mymod.assets/ctl\u0001.png",
            "eratw.mymod.assets/ctl\u007f.png",
            "eratw.mymod.assets/a/b/",
            "/eratw.mymod.assets/a.png",
        ).forEach { name ->
            assertNotNull(PackModZip.resourceUnsafeReason(name), "资源条目应被拒：[$name]")
            assertFalse(PackModZip.isSafeEntryName(name), "整名旧口径也该拒：[$name]")
        }
    }

    /**
     * 表 4–7：R1 反向证明 —— 这三条**旧口径收、导入端丢**，新口径必须拒，且原因点名来源前缀。
     * 这是本波的核心差异，`PACKAGE_ID` 为空白串 / `.` / `..` 时整目录资源会静默失效。
     */
    @Test
    fun resourcePrefixFormsThatImportSilentlySkipsAreRejected() {
        listOf(".assets/x.png", "..assets/x.png", "...assets/x.png", "a/b.assets/x.png").forEach { name ->
            assertTrue(PackModZip.isSafeEntryName(name), "旧口径（整名 isLegalResourceName）确实收：[$name]")
            val reason = assertNotNull(PackModZip.resourceUnsafeReason(name), "新口径必须拒：[$name]")
            assertTrue(reason.contains("来源目录名"), "拒收原因要点名前缀判据：[$name] -> $reason")
        }
        // 正常前缀不受影响（防收紧过头）。
        assertEquals(null, PackModZip.resourceUnsafeReason("eratw.mymod.assets/x.png"))
    }

    /** 表 16–19：R2 —— 主名过不了 `ModStore.validateName` 的形态，pack 必须拒并给出改法。 */
    @Test
    fun sourceMainNamesThatImportCannotSaveAreRejected() {
        listOf(
            "v1..2.ERB",
            "dir/v1..2.ERB",
            "a*b.ERB",
            "a?b.ERB",
            "a\"b.ERB",
            "a<b.ERB",
            "a>b.ERB",
            "a|b.ERB",
            " lead.ERB",
            "x".repeat(125) + ".ERB",
        ).forEach { name ->
            assertTrue(PackModZip.isSafeEntryName(name), "整名旧口径确实收：[$name]")
            val reason = assertNotNull(PackModZip.sourceUnsafeReason(name), "新口径必须拒：[$name]")
            assertTrue(reason.contains("主名"), "拒收原因要点名主名判据：[$name] -> $reason")
        }
        // 边界：整名（含扩展名）128 字符合法（`ModStore` 是 `> 128` 才拒）。
        assertEquals(null, PackModZip.sourceUnsafeReason("x".repeat(124) + ".ERB"))
    }

    /** 收集面：前缀非法目录下的资源进 `unsafe`（拒绝打包），不静默跳过。 */
    @Test
    fun collectRejectsResourceDirectoryWithBadPrefix() {
        val root = Files.createDirectories(tempDir.resolve("mod"))
        val assets = Files.createDirectories(root.resolve("..assets"))
        Files.writeString(assets.resolve("x.png"), "png")

        val plan = PackModZip.collect(root, listOf("."))

        assertEquals(0, plan.resourceCount, "前缀非法目录不该有资源进包")
        assertTrue(plan.unsafeNames.contains("..assets/x.png"), "unsafe=${plan.unsafeNames}")
        val unsafe = assertNotNull(plan.unsafe.firstOrNull { it.name == "..assets/x.png" })
        assertTrue(unsafe.reason.contains("来源目录名"), "reason=${unsafe.reason}")
        assertTrue(unsafe.hint.contains("PACKAGE_ID"), "hint 要给出改法：${unsafe.hint}")
    }

    /** 收集面：主名导入端收不下的源文件进 `unsafe`（拒绝打包），合法源照常进包。 */
    @Test
    fun collectRejectsSourceEntryThatImportCannotSave() {
        val root = Files.createDirectories(tempDir.resolve("mod"))
        Files.writeString(root.resolve("Good.ERB"), "@SYSTEM")
        Files.writeString(root.resolve("v1..2.ERB"), "@SYSTEM")

        val plan = PackModZip.collect(root, emptyList())

        assertEquals(1, plan.sourceCount, "合法源应进包")
        assertEquals(listOf("v1..2.ERB"), plan.unsafeNames)
        val unsafe = assertNotNull(plan.unsafe.singleOrNull())
        assertTrue(unsafe.reason.contains(".."), "reason=${unsafe.reason}")
        assertTrue(unsafe.hint.contains("改文件名"), "hint 要给出改法：${unsafe.hint}")
    }

    /** pack-mod CLI：拒收消息必须点名条目 + 判据 + 改法（作者不用猜）。 */
    @Test
    fun packCliRefusalNamesEntryReasonAndFix() {
        val root = Files.createDirectories(tempDir.resolve("mod"))
        Files.writeString(root.resolve("v1..2.ERB"), "@SYSTEM")
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val exit = java.io.PrintStream(out, true, "UTF-8").use { stdout ->
            java.io.PrintStream(err, true, "UTF-8").use { stderr ->
                PackMod.runCli(
                    arrayOf(root.toString(), "--out", tempDir.resolve("mod.zip").toString()),
                    stdout,
                    stderr,
                )
            }
        }
        val stderr = err.toString("UTF-8")
        assertEquals(1, exit, "应拒绝打包；stderr=$stderr")
        assertTrue(stderr.contains("v1..2.ERB"), "拒收消息要点名条目：$stderr")
        assertTrue(stderr.contains("含 .."), "拒收消息要点名判据：$stderr")
        assertTrue(stderr.contains("建议"), "拒收消息要给出改法：$stderr")
    }

    /** check-mod 资源面：R1 三种前缀形态报 ERROR（必然整目录失效，非假红）。 */
    @Test
    fun checkModReportsImportPrefixErrorForEveryBadPackageIdForm() {
        listOf("" to ".assets", "." to "..assets", ".." to "...assets").forEachIndexed { index, (packageId, dirName) ->
            val root = Files.createDirectories(tempDir.resolve("mod-prefix-$index"))
            val assets = Files.createDirectories(root.resolve(dirName))
            Files.writeString(assets.resolve("x.png"), "png")
            val findings = mutableListOf<Finding>()
            ModCheckResources.check(root, sourcesWithPackageId(root, packageId), findings)

            assertHasError(findings, "资源目录前缀非法")
            assertTrue(
                findings.any { it.severity == Severity.ERROR && it.file == dirName },
                "ERROR 要定位到该目录 $dirName：${findings.joinToString("; ") { it.file + "=" + it.message }}",
            )
        }
    }

    /** check-mod 资源面：合法前缀不误报（防收紧过头）。 */
    @Test
    fun checkModKeepsValidPrefixSilent() {
        val root = Files.createDirectories(tempDir.resolve("mod-ok"))
        val assets = Files.createDirectories(root.resolve("eratw.mymod.assets"))
        Files.writeString(assets.resolve("x.png"), "png")
        val findings = mutableListOf<Finding>()

        ModCheckResources.check(root, sourcesWithPackageId(root, "eratw.mymod"), findings)

        assertTrue(
            findings.none { it.message.contains("资源目录前缀非法") },
            "合法前缀不该报错：${findings.joinToString("; ") { it.message }}",
        )
    }

    /** 文本哨兵：R2 复制的是 `ModStore.validateName` 的真源判据，真源变了这里必须红。 */
    @Test
    fun sourceNameRulesStayInSyncWithModStoreSource() {
        val storeSource = shellSource("platform/src/main/kotlin/com/eratw/port/platform/storage/ModStore.kt")
        val text = storeSource.readText()
        val maxNameChars = assertNotNull(MAX_NAME_CHARS_LITERAL.find(text), "ModStore 里找不到 MAX_NAME_CHARS")
        assertEquals("128", maxNameChars.groupValues[1], "modgen 复制的长度上限与 ModStore 漂移：$storeSource")
        listOf(
            "private val FORBIDDEN_CHARS = charArrayOf(':', '*', '?', '\"', '<', '>', '|')",
            "if (name.contains(\"..\")) return traversalReason(name)",
            "if (name.length > MAX_NAME_CHARS) return tooLongReason(name)",
            "if (name.first().isWhitespace() || name.last().isWhitespace()) return blankEdgeReason(name)",
        ).forEach { fragment ->
            assertTrue(text.contains(fragment), "ModStore 判据漂移，modgen 复制面要跟着改：缺 [$fragment]")
        }
    }

    /** 造一份只含 `const val PACKAGE_ID` 字面量的源码（check-mod 资源面只需这份清单）。 */
    private fun sourcesWithPackageId(root: Path, packageId: String): List<SourceFile> {
        val src = Files.createDirectories(root.resolve("src/main/kotlin"))
        Files.writeString(src.resolve("Declarations.kt"), "const val PACKAGE_ID: String = \"$packageId\"\n")
        return ModCheckSources.scan(root, mutableListOf())
    }
}
