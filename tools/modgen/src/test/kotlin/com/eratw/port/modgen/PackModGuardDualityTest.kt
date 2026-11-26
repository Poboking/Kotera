package com.eratw.port.modgen

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `pack-mod` 产出 ↔ app 侧 `ModZipGuard` 接受：对偶契约的 pack 侧半段。
 *
 * ## 为什么是两段而不是同进程 A/B
 *
 * `PackMod`/`PackModZip` 是 `tools:modgen` 的 internal，`:app` 测试源集看不见；反过来 app 侧的
 * `ModZipGuard` 也不在 modgen 的测试类路径上。故契约拆两段接力，靠**同一份 zip 字节**焊住：
 *
 * - 本类（pack 侧）：真跑 [PackMod.runCli] 出 zip，逐项度量条目数 / 单条目解压字节 / 总解压字节 /
 *   压缩比 / 重名，对照从 `app/src/main/kotlin/com/eratw/port/android/ModZipGuard.kt` 源码里
 *   **现场解析**出的 `MAX_*` 常量（不是复制数值）；guard 一侧收紧 ⇒ 本类先红；
 * - `ModImportPackModGuardDualityTest`（app 侧）：把本类产出的**同一份字节**（sha256 常量
 *   [PACKED_FIXTURE_CONTENT_SHA] 互相点名）喂真 `ModZipGuard`/`ModImportController` 走通导入。
 *
 * 两侧都绿 = 「pack-mod 产出的正常包必被 guard 接受」这条对偶契约成立。
 */
class PackModGuardDualityTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `scaffolded module pack output is within every guard limit`() {
        val module = patchableModule(tempDir)
        val zip = outZip(tempDir)
        val run = packOk(module, zip)

        val limits = GuardLimits.fromGuardSource(shellSource(GUARD_SOURCE))
        val metrics = zipMetrics(zip)
        assertEquals(sha256Of(run), metrics.sha256Hex, "摘要行的 sha256 必须等于实际字节的 sha256")

        assertTrue(
            metrics.entryCount <= limits.maxEntries,
            "pack-mod 产出 ${metrics.entryCount} 条目，超过 guard 上限 ${limits.maxEntries}",
        )
        assertTrue(
            metrics.maxEntryBytes <= limits.maxEntryBytes,
            "最大条目 ${metrics.maxEntryBytes} 字节，超过 guard 单条目上限 ${limits.maxEntryBytes}",
        )
        assertTrue(
            metrics.totalBytes <= limits.maxTotalBytes,
            "总解压 ${metrics.totalBytes} 字节，超过 guard 总量上限 ${limits.maxTotalBytes}",
        )
        assertTrue(
            metrics.totalBytes <= limits.ratioMinBytes || metrics.totalBytes <= limits.maxRatio * metrics.archiveBytes,
            "压缩比 ${metrics.totalBytes}/${metrics.archiveBytes} 超过 guard 上限 ${limits.maxRatio}" +
                "（总解压已过 ${limits.ratioMinBytes} 字节的压缩比闸门槛）",
        )
        assertEquals(metrics.entryCount, metrics.distinctEntryCount, "pack-mod 产出的条目名不得重名")
    }

    @Test
    fun `packed fixture bytes are exactly the app side fixture`() {
        val module = patchableModule(tempDir)
        val zip = outZip(tempDir)
        val run = packOk(module, zip)
        // 比**包内容摘要**（条目名 + 未压缩字节），不比 zip 原始字节：压缩流随 JDK 自带的
        // zlib 版本而变，同一份内容在不同 JDK 上压出来的字节可能不同（2026-11-27 在 Linux CI
        // 上实测判红，而解包后的条目内容逐字节相同）。要冻的是包内容这条合同。
        assertEquals(
            PACKED_FIXTURE_CONTENT_SHA,
            zipContentDigest(Files.readAllBytes(zip)),
            "pack-mod 产出的**包内容**漂移了：内容摘要（$PACKED_FIXTURE_CONTENT_SHA）必须与冻结夹具一致；" +
                "若确实是 pack-mod 合同的有意变更，请用 dumpFixture 重新生成 app 侧夹具并同步两侧常量",
        )
        assertTrue(
            Files.readAllBytes(zip).size <= 8 * 1024,
            "夹具 zip 应保持小体积以便内嵌：实为 ${Files.size(zip)} 字节",
        )
    }

    @Test
    fun `module near the entry count boundary still packs within guard limits`() {
        val module = patchableModule(tempDir)
        // 边界：条目数接近 guard 上限（任务书口径 500 条），保证正常量级的真实模块不会被误杀。
        repeat(ENTRY_BOUNDARY - 2) { index -> writeModuleFile(module, "src/f%03d.ERB".format(index), "RETURN 0\n") }
        val zip = outZip(tempDir)
        packOk(module, zip)
        val limits = GuardLimits.fromGuardSource(shellSource(GUARD_SOURCE))
        val metrics = zipMetrics(zip)
        assertTrue(metrics.entryCount >= ENTRY_BOUNDARY - 1, "应接近条目数边界：${metrics.entryCount}")
        assertTrue(metrics.entryCount <= limits.maxEntries, "接近边界的模块包仍须在 guard 条目上限内：${metrics.entryCount}")
        assertTrue(metrics.totalBytes <= limits.maxTotalBytes, "接近边界的模块包仍须在 guard 总量上限内")
    }

    @Test
    fun `module exactly at the entry count limit including metadata still packs and fits guard`() {
        val limits = GuardLimits.fromGuardSource(shellSource(GUARD_SOURCE))
        val module = patchableModule(tempDir)
        // 模块条目 = main.ERB + README（2 条）+ 补的源；包元数据另占 1 条 ⇒ 总数顶到 guard 上限。
        val extraSources = limits.maxEntries - METADATA_ENTRIES - BASE_MODULE_ENTRIES
        repeat(extraSources) { index -> writeModuleFile(module, "src/f%03d.ERB".format(index), "RETURN 0\n") }
        val zip = outZip(tempDir)
        packOk(module, zip)
        val metrics = zipMetrics(zip)
        assertEquals(
            limits.maxEntries,
            metrics.entryCount,
            "恰好顶到 guard 条目上限（含元数据）的包必须照常出包且不超限：${metrics.entryCount}",
        )
        assertTrue(metrics.totalBytes <= limits.maxTotalBytes, "顶到条目数上限的包仍须在 guard 总量上限内")
    }

    @Test
    fun `pack refuses an over-limit module instead of emitting a guard rejected package`() {
        // 本波把原先的 characterization 缺口翻成正向契约：guard 在第 513 条拒收（`entries >=
        // MAX_ENTRIES`），所以 pack 侧必须在出包前就拒，且拒绝文案点名判据（条目数 / 上限）与
        // 第一条放不进预算的模块条目——不能只报「太大了」让作者自己数。
        val limits = GuardLimits.fromGuardSource(shellSource(GUARD_SOURCE))
        val module = packableModule(tempDir)
        repeat(KNOWN_GAP_ENTRIES) { index -> writeModuleFile(module, "src/g%03d.ERB".format(index), "RETURN 0\n") }
        val zip = outZip(tempDir)
        val run = runPack(module.toString(), "--out", zip.toString())
        assertEquals(1, run.exit, "超条目数上限的模块必须被拒（退出码 1）；out=${run.out} err=${run.err}")
        assertTrue(run.err.contains("拒绝打包"), "stderr 必须走统一拒绝出口；err=${run.err}")
        assertTrue(run.err.contains("条目数"), "拒绝文案必须点名判据「条目数」；err=${run.err}")
        assertTrue(
            run.err.contains("上限 ${limits.maxEntries}"),
            "拒绝文案必须给出上限 ${limits.maxEntries}；err=${run.err}",
        )
        assertTrue(
            run.err.contains("src/g"),
            "拒绝文案必须点名第一条放不进预算的模块条目；err=${run.err}",
        )
        assertTrue(run.err.contains("建议"), "拒绝文案必须带改法建议；err=${run.err}")
        assertTrue(!Files.exists(zip), "拒包不得留下半成品 zip：${zip}")
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** new-mod 骨架 + generated 生成物 + 可编译的最小补丁源（与 app 侧导入夹具同款）。 */
    private fun patchableModule(root: Path): Path {
        val module = scaffoldModule(root)
        writeGenerated(module)
        writeModuleFile(module, TEST_SOURCE, PATCH_SOURCE)
        return module
    }

    private class ZipMetrics(
        val archiveBytes: Long,
        val entryCount: Int,
        val distinctEntryCount: Int,
        val maxEntryBytes: Long,
        val totalBytes: Long,
        val sha256Hex: String,
    )

    /** 逐条读解压字节，口径与 `ModZipGuard.Progress.onRead` 一致（不信任中央目录的 size 字段）。 */
    private fun zipMetrics(zip: Path): ZipMetrics {
        val archiveBytes = Files.size(zip)
        val names = ArrayList<String>()
        var maxEntryBytes = 0L
        var totalBytes = 0L
        ZipFile(zip.toFile()).use { file ->
            for (entry in file.entries()) {
                if (entry.isDirectory) continue
                names += entry.name
                val bytes = file.getInputStream(entry).use { it.readBytes() }.size.toLong()
                maxEntryBytes = maxOf(maxEntryBytes, bytes)
                totalBytes += bytes
            }
        }
        return ZipMetrics(
            archiveBytes = archiveBytes,
            entryCount = names.size,
            distinctEntryCount = names.distinct().size,
            maxEntryBytes = maxEntryBytes,
            totalBytes = totalBytes,
            sha256Hex = PackModZipHash.of(Files.readAllBytes(zip)),
        )
    }

    /** 从 ModZipGuard.kt 源码解析出的闸门常量（数值同源，不复制）。 */
    private class GuardLimits(
        val maxEntries: Int,
        val maxEntryBytes: Long,
        val maxTotalBytes: Long,
        val maxRatio: Long,
        val ratioMinBytes: Long,
    ) {
        companion object {
            fun fromGuardSource(guardSource: Path): GuardLimits {
                val text = guardSource.readText()
                val limits = GuardLimits(
                    maxEntries = numericConst(text, "MAX_ENTRIES").toInt(),
                    maxEntryBytes = numericConst(text, "MAX_ENTRY_BYTES"),
                    maxTotalBytes = numericConst(text, "MAX_TOTAL_BYTES"),
                    maxRatio = numericConst(text, "MAX_COMPRESSION_RATIO"),
                    ratioMinBytes = numericConst(text, "RATIO_MIN_BYTES"),
                )
                // 解析自检：常量缺失或形态变了要立刻红，不允许静默取到 0 后全部放行。
                assertEquals(512, limits.maxEntries, "guard 条目数上限解析异常：${limits.maxEntries}")
                assertEquals(8L * 1024 * 1024, limits.maxEntryBytes, "guard 单条目上限解析异常")
                assertEquals(32L * 1024 * 1024, limits.maxTotalBytes, "guard 总量上限解析异常")
                assertEquals(120L, limits.maxRatio, "guard 压缩比上限解析异常")
                assertEquals(1L * 1024 * 1024, limits.ratioMinBytes, "guard 压缩比闸门槛解析异常")
                return limits
            }

            /** 解析 `const val NAME = 8L * 1024 * 1024` 这类算式（支持 `L` 后缀与 `*` 连乘）。 */
            private fun numericConst(text: String, name: String): Long {
                val match = Regex("""const val $name = ([0-9L*\s]+)""").find(text)
                assertTrue(match != null, "在 ModZipGuard.kt 里找不到 const val $name")
                val expression = match.groupValues[1].trim()
                return expression.split('*').fold(1L) { acc, part ->
                    val token = part.trim().removeSuffix("L").trim()
                    assertTrue(token.isNotEmpty() && token.all { it.isDigit() }, "无法解析 $name 的算式：$expression")
                    acc * token.toLong()
                }
            }
        }
    }

    private companion object {
        /** app 侧 guard 源码（数值判据同源的单据）。 */
        const val GUARD_SOURCE = "app/src/main/kotlin/com/eratw/port/android/ModZipGuard.kt"

        /** app 侧测试内嵌的同一份字节的 sha256（两段接力焊点，改夹具必须两侧同步改）。 */
        const val PACKED_FIXTURE_CONTENT_SHA =
            "aff02e06425a394ff7876c504c659e8bb2de8857b7631134824202243b5b5af4"

        /** 任务书口径的条目数边界。 */
        const val ENTRY_BOUNDARY = 500

        /** 已知缺口的条目数：guard 上限 512 之上（本波起 pack 侧必须拒）。 */
        const val KNOWN_GAP_ENTRIES = 513

        /** 打包器自己写的包元数据条目数（scaffold 夹具带 @ModCarrier，故为 1）。 */
        const val METADATA_ENTRIES = 1

        /** scaffold 夹具的模块内容条目数：main.ERB + `<packageId>.assets/README.txt`。 */
        const val BASE_MODULE_ENTRIES = 2

        /** 补丁源必须可被 app 侧 PatchCompiler 接受（与 ModImportPackModEndToEndTest 同款）。 */
        const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"
    }
}

/** sha256 小工具（modgen 测试侧自足，避免依赖 platform 内部）。 */
private object PackModZipHash {
    fun of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
