package com.eratw.port.modgen

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * pack 侧导入端闸（[PackLimits]）的判据与接线测试。
 *
 * 分三层验：
 * 1. 纯判据（[PackLimits] 的四个 `…Unsafe`）：边界值「恰好等于上限放行、超 1 拒绝」，
 *    与 guard 的 `>` / `entries >= MAX_ENTRIES` 口径逐条对齐；
 * 2. 收集接线（[PackModZip.collect]）：磁盘上的稀疏文件真的被按解压字节数计入单条目/整包闸，
 *    并在 [PackPlan.unsafe] 里点名；
 * 3. 成包接线（[PackModZip.build]）：压缩比确实在拿到 zip 字节数之后才判，且
 *    小包（解压量不越过 `RATIO_MIN_BYTES`）不误杀。
 */
class PackModLimitsGateTest {

    @TempDir
    lateinit var tempDir: Path

    // ---- 1. 条目数闸 ----

    @Test
    fun entryCountAtExactLimitPasses() {
        val moduleEntries = fakeEntries(PackLimits.MAX_ENTRIES - 1)
        assertNull(
            PackLimits.entryCountUnsafe(moduleEntries, fakeGenerated()),
            "模块条目 ${moduleEntries.size} + 元数据 1 条 = 上限 ${PackLimits.MAX_ENTRIES}，不该拒",
        )
    }

    @Test
    fun entryCountWithoutMetadataAtExactLimitPasses() {
        val moduleEntries = fakeEntries(PackLimits.MAX_ENTRIES)
        assertNull(
            PackLimits.entryCountUnsafe(moduleEntries, emptyList()),
            "正好 ${PackLimits.MAX_ENTRIES} 条模块条目（无元数据）是 guard 的上限，不该拒",
        )
    }

    @Test
    fun entryCountOverLimitRejectsAndNamesFirstOverflowEntry() {
        val moduleEntries = fakeEntries(PackLimits.MAX_ENTRIES)
        val unsafe = assertNotNull(
            PackLimits.entryCountUnsafe(moduleEntries, fakeGenerated()),
            "模块条目 ${PackLimits.MAX_ENTRIES} + 元数据 1 条 = 超上限，必须拒",
        )
        assertEquals(moduleEntries.last().name, unsafe.name, "应点名第一条放不进预算的模块条目")
        assertTrue(unsafe.reason.contains("条目数 ${PackLimits.MAX_ENTRIES + 1}"), "reason=${unsafe.reason}")
        assertTrue(unsafe.reason.contains("上限 ${PackLimits.MAX_ENTRIES}"), "reason=${unsafe.reason}")
    }

    // ---- 2. 单条目 / 整包闸 ----

    @Test
    fun entrySizeAtExactLimitPassesAndOneByteOverRejects() {
        assertNull(PackLimits.entrySizeUnsafe("big.ERB", PackLimits.MAX_ENTRY_BYTES))
        val unsafe = assertNotNull(
            PackLimits.entrySizeUnsafe("big.ERB", PackLimits.MAX_ENTRY_BYTES + 1),
            "超单条目上限 1 字节就必须拒",
        )
        assertEquals("big.ERB", unsafe.name)
        assertTrue(unsafe.reason.contains("单条目上限"), "reason=${unsafe.reason}")
        assertTrue(unsafe.reason.contains("${PackLimits.MAX_ENTRY_BYTES + 1} 字节"), "reason=${unsafe.reason}")
    }

    @Test
    fun totalBytesAtExactLimitPassesAndOneByteOverRejects() {
        assertNull(PackLimits.totalBytesUnsafe(PackLimits.MAX_TOTAL_BYTES))
        val unsafe = assertNotNull(
            PackLimits.totalBytesUnsafe(PackLimits.MAX_TOTAL_BYTES + 1),
            "超整包上限 1 字节就必须拒",
        )
        assertTrue(unsafe.reason.contains("整包解压合计"), "reason=${unsafe.reason}")
        assertTrue(unsafe.reason.contains("上限 ${PackLimits.MAX_TOTAL_BYTES} 字节"), "reason=${unsafe.reason}")
        assertTrue(unsafe.hint.contains("MiB"), "hint=${unsafe.hint}")
    }

    @Test
    fun collectCountsSparseDiskFilesTowardSingleEntryGate() {
        val root = tempDir.resolve("mod-big")
        writeSparseFile(root.resolve("src/big.ERB"), PackLimits.MAX_ENTRY_BYTES + 1)
        val plan = PackModZip.collect(root, emptyList())
        val unsafe = plan.unsafe.singleOrNull()
        assertNotNull(unsafe, "unsafe=${plan.unsafe.map { it.name + "/" + it.reason }}")
        assertEquals("src/big.ERB", unsafe.name, "collect 必须按磁盘解压字节数点名该条目")
        assertTrue(unsafe.reason.contains("单条目上限"), "reason=${unsafe.reason}")
    }

    @Test
    fun collectAcceptsSparseFilesAtCrossedLimits() {
        val root = tempDir.resolve("mod-edge")
        // 4 × 8 MiB = 32 MiB 整包上限：单条目各等于上限、总量恰好等于上限，一个都不该拒。
        repeat(FOUR) { index ->
            writeSparseFile(root.resolve("src/f$index.ERB"), PackLimits.MAX_ENTRY_BYTES)
        }
        val plan = PackModZip.collect(root, emptyList())
        assertTrue(plan.unsafe.isEmpty(), "unsafe=${plan.unsafe.map { it.name + "/" + it.reason }}")
        assertEquals(FOUR, plan.sourceCount)
    }

    @Test
    fun collectRejectsSparseFilesOverTotalLimit() {
        val root = tempDir.resolve("mod-bomb")
        repeat(FIVE) { index ->
            writeSparseFile(root.resolve("src/g$index.ERB"), PackLimits.MAX_ENTRY_BYTES)
        }
        val plan = PackModZip.collect(root, emptyList())
        val unsafe = assertNotNull(plan.unsafe.singleOrNull(), "应有且只有一条整包超限")
        assertTrue(unsafe.reason.contains("整包解压合计"), "reason=${unsafe.reason}")
        assertTrue(unsafe.reason.contains("上限 ${PackLimits.MAX_TOTAL_BYTES} 字节"), "reason=${unsafe.reason}")
    }

    // ---- 3. 压缩比闸（成包后） ----

    @Test
    fun buildRejectsCompressiblePayloadOverRatioMinimum() {
        val build = PackModZip.build(emptyList(), listOf(generated(zeros(TWO_MIB))))
        val unsafe = assertNotNull(
            build.unsafe.singleOrNull(),
            "2 MiB 全零压成几 KB，压缩比必然超上限，必须拒",
        )
        assertTrue(unsafe.reason.contains("压缩比上限 ${PackLimits.MAX_COMPRESSION_RATIO} 倍"), "reason=${unsafe.reason}")
        assertTrue(build.bytes.isNotEmpty(), "拒绝也必须先把成包字节算出来（内存内、不落盘）")
    }

    @Test
    fun buildAcceptsIncompressiblePayloadOverRatioMinimum() {
        val build = PackModZip.build(emptyList(), listOf(generated(incompressible(TWO_MIB))))
        assertTrue(build.unsafe.isEmpty(), "unsafe=${build.unsafe.map { it.reason }}")
    }

    @Test
    fun buildSkipsRatioForPayloadBelowGuardMinimum() {
        // 解压量不越过 RATIO_MIN_BYTES：压缩比天然虚高，但 guard 不判，pack 也不许误杀。
        val build = PackModZip.build(emptyList(), listOf(generated(zeros(PackLimits.RATIO_MIN_BYTES / 2))))
        assertTrue(build.unsafe.isEmpty(), "unsafe=${build.unsafe.map { it.reason }}")
    }

    private fun fakeEntries(count: Int): List<PackEntry> =
        (0 until count).map { index -> PackEntry("src/f%03d.ERB".format(index), tempDir.resolve("f.erb")) }

    private fun fakeGenerated(): List<GeneratedEntry> = listOf(generated(ByteArray(0)))

    private fun generated(bytes: ByteArray): GeneratedEntry = GeneratedEntry(PACK_META_ENTRY_NAME, bytes)

    private fun zeros(size: Long): ByteArray = ByteArray(size.toInt())

    /** 固定种子的伪随机字节：几乎不可压，用来验「正常包不误杀」。 */
    private fun incompressible(size: Long): ByteArray =
        ByteArray(size.toInt()).also { bytes -> java.util.Random(SEED).nextBytes(bytes) }

    private fun writeSparseFile(path: Path, size: Long) {
        Files.createDirectories(path.parent)
        RandomAccessFile(path.toFile(), "rw").use { file -> file.setLength(size) }
    }

    private companion object {
        const val ONE_MIB = 1024L * 1024L
        const val TWO_MIB = 2L * ONE_MIB
        const val FOUR = 4
        const val FIVE = 5
        const val SEED = 42L
    }
}
