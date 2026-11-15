package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 堆内像素分配的**预算闸**（bug-oom lane，2026-11-09）。
 *
 * ## 这条 lane 治的残余
 *
 * 上报簇「启动失败 / 应用崩溃 `java.lang.OutOfMemoryError`、`target footprint 536870912`、
 * 栈顶 `GraphicsBuffer.create`」在 OOM-A/OOM-B 三笔（`2c9954df0` / `281fa11d9` / `525b079e9`）
 * 之后仍有一条缝：既有预算档 [GraphicsPool.evictDeferredPixelsWithinBudget] 量的是
 * **图形池驻留**，不是**进程堆**。headless 同形复现（`-Xmx128m`，池 19 MiB、像素预算 32 MiB、
 * 堆 125/128 MiB）：`pool.get()` 判「不需要动」，紧接着 `create(1000, 1000)` 当场
 * `OutOfMemoryError: Java heap space` —— 与真机报告逐字同形，只是分配器文案不同。
 *
 * ## 钉的四件事（契约 §4 判据逐条对应）
 *
 * 1. **预算闸边界**：`已用堆 + 本笔需求` 恰好落在安全线 ⇒ 放行；超 1 字节 ⇒ 余量预算扣减；
 *    需求大于堆上限 ⇒ 可诊断拒绝（[GraphicsBuffer.pixelAllocationRefusal]）；
 * 2. **逐出优先**：非像素堆越线时先按既有两档（可重解先退 → 画过的压 PNG）逐出，不抛错、
 *    不静默给假画布；`isCreated` / 宽高 / 像素逐字节不变；
 * 3. **降级通道**：mmap 载荷通道（[MappedPngPayloadStore]）可用时降级代 `retainedBytes == 0`
 *    （载荷出堆），读数与 [GraphicsPool.stats] 的 `pixelBytes` / `residentBytes()` 恒等口径不变；
 * 4. **无宿主 / 无 mmap**：`payloadStore == null`（纯 JVM 闸、语料对拍、桌面装置）时回落堆内
 *    闭包，载荷按真实字节入账 —— 与加本闸之前逐字相同。
 *
 * 全部尺寸都是 64×64 级的小缓冲，本文件不烧真尺寸图：判据是字节算式，行为与 122 MiB 工作表同形。
 *
 * ## 反向证明（实测读数，2026-11-09）
 *
 * 把 [GraphicsBuffer.HEAP_SAFETY_PERCENT] 从 80 改成 100（= 撤销本闸）后，本类 **8 例中 3 例红**：
 * - [堆余量预算_安全线上恰好放行_超一字节即扣减]（安全线断言 800 000 → 实测 1 000 000）；
 * - [mmap载荷通道_降级代驻留归零且像素逐字节复原]（驻留停在 65 536 = 四张像素阵列，一条都没退）；
 * - [无mmap时降级载荷仍按真实字节入账_像素逐字节复原]（一条都没进降级态）。
 *
 * 改回 80 后 8/0 逐字复绿。另两条「非像素堆越过安全线」用例的**场景**是按常量现算的
 * （`line = max × HEAP_SAFETY_PERCENT` ⇒ 恒在线上 64 KiB），撤闸时它们跟着漂、不构成反证，
 * 所以反证锚点只认上面那三例。
 */
class GraphicsHeapBudgetTest {

    private val side = 64
    private val sheetPixels = side * side.toLong()          // 4096
    private val sheetBytes = sheetPixels * 4L               // 16384

    @AfterTest
    fun clearOverride() {
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
    }

    /** 把固定像素预算挪开，只留本波新增的堆余量档可判（否则 maxMemory÷4 会先兜住，测不出新段）。 */
    private fun isolateHeapHeadroomGate() {
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, Long.MAX_VALUE.toString())
    }

    /** 建一张「文件源还在」的 G_ID 工作表并落地像素（可重解档的候选）。 */
    private fun materializedSheet(pool: GraphicsPool, id: Int): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(
            buffer.createDeferredFromFile(side, side, "resources/heap-sheet-$id.webp") {
                IntArray(side * side) { idx -> 0x01000000 or idx }
            },
        )
        buffer.pixels
        return buffer
    }

    /** 建一张「画过的」合成缓冲（降级档的候选，无可重解源）。 */
    private fun paintedCanvas(pool: GraphicsPool, id: Int): GraphicsBuffer {
        val buffer = pool.get(id)
        buffer.create(side, side)
        buffer.fillRect(0, 0, side, side, 0xFF336699.toInt(), false)
        return buffer
    }

    // ------------------------------------------------------------------
    // ① 预算闸边界
    // ------------------------------------------------------------------

    /**
     * 余量预算的**边界算术**（纯函数，注入堆读数，不依赖本机挡位）：
     * 非像素占用恰好等于安全线 ⇒ 余量预算 0；少 1 字节 ⇒ 预算 1；多 1 字节 ⇒ 已归零。
     *
     * 判据来源：契约「`已用堆 + 本笔需求 > maxMemory × 比例`」。把 `本池驻留` 记作
     * `需要放行的像素`，等价式就是 `非像素占用 + 驻留 ≤ 安全线` ⇔ `驻留 ≤ 余量预算`。
     */
    @Test
    fun `堆余量预算_安全线上恰好放行_超一字节即扣减`() {
        val max = 1_000_000L
        val line = max / 100L * GraphicsBuffer.HEAP_SAFETY_PERCENT
        assertEquals(800_000L, line, "安全线 = 80% × 堆上限")

        // 驻留 100 000，非像素 = 安全线 − 100 000 ⇒ 余量预算恰好 100 000 = 驻留 ⇒ 放行
        val resident = 100_000L
        assertEquals(
            resident,
            GraphicsBuffer.heapHeadroomPixelBudget(
                usedHeapBytes = line,
                residentPixelBytes = resident,
                maxMemoryBytes = max,
            ),
            "已用堆恰好落在安全线时，余量预算必须恰好容下当前驻留（放行，不逐出）",
        )
        // 已用堆超 1 字节 ⇒ 余量预算少 1 字节 ⇒ 驻留 1 字节即超预算（必须逐出）
        assertEquals(
            resident - 1L,
            GraphicsBuffer.heapHeadroomPixelBudget(
                usedHeapBytes = line + 1L,
                residentPixelBytes = resident,
                maxMemoryBytes = max,
            ),
            "超安全线 1 字节，余量预算就必须少 1 字节",
        )
    }

    /** 非像素占用独自越过安全线 ⇒ 余量预算归零（= 尽量退）；堆上限不可知 ⇒ 闸不生效。 */
    @Test
    fun `堆余量预算_非像素越线归零_堆上限不可知时永不生效`() {
        val max = 1_000_000L
        val nonPixel = max // 非像素一项就吃满上限
        assertEquals(
            0L,
            GraphicsBuffer.heapHeadroomPixelBudget(
                usedHeapBytes = nonPixel,
                residentPixelBytes = 0L,
                maxMemoryBytes = max,
            ),
            "非像素占用越过安全线时余量预算必须夹到 0（尽量退的自洽读法）",
        )
        assertEquals(
            Long.MAX_VALUE,
            GraphicsBuffer.heapHeadroomPixelBudget(usedHeapBytes = 9_999_999_999L, maxMemoryBytes = 0L),
            "堆上限不可知（0）时闸必须不生效 —— 绝不拿拍出来的绝对值截桌面装置",
        )
        assertEquals(
            Long.MAX_VALUE,
            GraphicsBuffer.heapHeadroomPixelBudget(
                usedHeapBytes = 9_999_999_999L,
                maxMemoryBytes = Long.MAX_VALUE,
            ),
            "无上限（Long.MAX_VALUE）同款回落",
        )
    }

    /** 分配前拒绝只认「物理上不可能」：需求 > 堆上限才拒，等于上限放行。 */
    @Test
    fun `分配前拒绝_需求大于堆上限才拒_等于上限放行`() {
        val max = 1_000_000L
        assertNull(
            GraphicsBuffer.pixelAllocationRefusal(needBytes = max, maxMemoryBytes = max),
            "需求恰好等于堆上限时放行（ART 会先 GC，判据不许提前开枪）",
        )
        val refused = GraphicsBuffer.pixelAllocationRefusal(needBytes = max + 1L, maxMemoryBytes = max)
        assertNotNull(refused, "需求超堆上限 1 字节必须拒")
        assertTrue(refused.contains("Java 堆上限"), "拒绝文案必须带堆读数口径：$refused")
        assertNull(
            GraphicsBuffer.pixelAllocationRefusal(needBytes = 1L shl 40, maxMemoryBytes = 0L),
            "堆上限不可知时不许拒",
        )
    }

    // ------------------------------------------------------------------
    // ② 逐出优先 + 分配点可诊断拒绝
    // ------------------------------------------------------------------

    /**
     * `create` 的**分配前闸**：需求大于堆上限时抛可诊断 [CodeEE]，且缓冲停在未创建态
     * （不留假画布）—— 与原版 `GDispose()` 先行、`new Bitmap` 抛出后停在未创建态同形
     * （GraphicsImage.cs:102-117 / :109）。
     */
    @Test
    fun `create_需求大于堆上限抛可诊断错误且不留假画布`() {
        val max = Runtime.getRuntime().maxMemory()
        // 需求 > 堆上限，同时 w*h 必须落进 Int（否则会先撞 NegativeArraySizeException，测不到本闸）
        val pixels = max / 4L + 4096L
        val bigSide = kotlin.math.ceil(kotlin.math.sqrt(pixels.toDouble())).toInt()
        val bigPixels = bigSide.toLong() * bigSide.toLong()
        assertTrue(bigPixels * 4L > max, "构造的这笔需求必须大于堆上限（$bigSide² × 4 > $max）")
        assertTrue(bigPixels <= Int.MAX_VALUE.toLong(), "w*h 必须仍能落进 Int，才能走到分配前闸")

        val buffer = GraphicsBuffer(1)
        buffer.create(8, 8)
        assertTrue(buffer.isCreated, "小画布照常建")
        assertEquals(8L * 8L * 4L, buffer.pixelBytes, "像素语义逐字不变")

        val thrown = assertFailsWith<CodeEE> { buffer.create(bigSide, bigSide) }
        assertTrue(
            thrown.message!!.contains("Java 堆上限"),
            "拒绝必须可诊断（带堆读数），实际：${thrown.message}",
        )
        assertFalse(buffer.isCreated, "拒绝之后不许留假画布：停在未创建态")
    }

    /**
     * **逐出优先**：非像素堆越过安全线时，先把可重解的退回未解码态、再把画过的压 PNG，
     * 而不是直接抛错；`isCreated` / 宽高 / 像素逐字节不变（`GraphicsImage.cs:44-54`、
     * `ASpriteSingle.cs:24-34` 的 IsCreated 只看位图在不在）。
     */
    @Test
    fun `堆余量档_先退可重解再降级_不抛错且读数逐字不变`() {
        isolateHeapHeadroomGate()
        val pool = GraphicsPool()
        val sheets = listOf(materializedSheet(pool, 1), materializedSheet(pool, 2))
        val canvases = listOf(paintedCanvas(pool, 3), paintedCanvas(pool, 4))
        val resident = pool.residentBytes()
        assertEquals(sheetBytes * 4, resident, "四张都驻留")

        // 预算 = 只容得下一张多一点 ⇒ 两档都必须动起来（可重解先退、画过的再降级）
        val budget = sheetBytes + 1024L
        val nonPixel = 1_000_000L / 100L * GraphicsBuffer.HEAP_SAFETY_PERCENT - budget
        // 降级档有 25 ms 单发时间帽（[GraphicsPool.DEMOTE_PASS_BUDGET_NS]）：照生产节律逐发推进，
        // 直到驻留不再下降为止 —— 判据是「闸最终回到预算内」。
        var freed = 0L
        var pass = 0
        while (pass++ < 64) {
            val before = pool.residentBytes()
            freed += pool.evictWithinHeapHeadroom(
                usedHeapBytes = before + nonPixel,
                maxMemoryBytes = 1_000_000L,
                payloadStore = null,
            )
            if (pool.residentBytes() == before) break
        }

        assertTrue(freed > 0, "必须真的退掉东西（freed=$freed）")
        // 读像素会晋升（[GraphicsBuffer.pixels] 的延迟解码 / 透明晋升），驻留读数必须在**那之前**
        // 取：本档要钉的是「闸退完当场回到预算内」，不是「重读之后还在预算内」。
        val residentAfter = pool.residentBytes()
        assertTrue(residentAfter <= budget, "逐出后必须当场回到预算内（$residentAfter ≤ $budget）")
        for ((i, sheet) in sheets.withIndex()) {
            assertEquals(0L, sheet.pixelBytes, "可重解的工作表必须先退回未解码态（#${i + 1}）")
            assertTrue(sheet.isCreated, "退回未解码态不改 isCreated")
            assertEquals(side, sheet.width)
            assertEquals(side, sheet.height)
        }
        assertTrue(canvases.any { it.isPixelsDemoted }, "可重解的退完仍超预算 ⇒ 画过的必须降级")
        for (buffer in sheets + canvases) {
            assertTrue(buffer.isCreated, "两档都不许把 isCreated 打掉")
            assertEquals(side, buffer.width, "宽不许变")
            assertEquals(side, buffer.height, "高不许变")
        }
        // 像素逐字节往返（降级 / 重解都是无损的）
        for (buffer in canvases) {
            val px = buffer.pixels
            assertEquals(side * side, px.size)
            for (v in px) assertEquals(0xFF336699.toInt(), v, "降级再晋升必须逐字节相同")
        }
        for (sheet in sheets) {
            val px = sheet.pixels
            for (idx in 0 until side * side) {
                assertEquals(0x01000000 or idx, px[idx], "重解像素第 $idx 格必须逐字相同")
            }
        }

    }

    /**
     * **非像素堆越过安全线 ⇒ 驻留必须退到零**（这条是残余 OOM 的直接反证：池读数在旧像素
     * 预算内时闸不动，堆却已经贴顶）。
     *
     * ## 它在反向证明里的位置
     *
     * 本用例的**场景**是按 [GraphicsBuffer.HEAP_SAFETY_PERCENT] 现算的（非像素 = 安全线 + 64 KiB），
     * 撤闸（比例改 100）时场景跟着漂、仍然在线上，所以它**不**充当「撤闸必红」的锚点 ——
     * 锚点是 [堆余量预算_安全线上恰好放行_超一字节即扣减] 与两条载荷通道用例（见类 KDoc）。
     * 本用例钉的是另一件事：非像素越线时旧「像素预算」口径下闸会判「不需要动」，新口径必须动。
     */
    @Test
    fun `堆余量档_非像素堆越过安全线时驻留必须退到零`() {
        isolateHeapHeadroomGate()
        val pool = GraphicsPool()
        repeat(8) { paintedCanvas(pool, 10 + it) }
        val resident = pool.residentBytes()
        assertEquals(sheetBytes * 8, resident, "8 张 64×64 画布都驻留")

        val max = 1_000_000L
        val line = max / 100L * GraphicsBuffer.HEAP_SAFETY_PERCENT
        // 非像素占用 = 安全线 + 64 KiB（模拟变量池 / 程序表那一段把堆推过线）
        val nonPixel = line + 64L * 1024L
        // 走 mmap 载荷通道：降级后的 PNG 出堆，池级驻留的地板才是真的 0；无载荷通道时地板是
        // 「堆内 PNG 载荷字节」（见 [无mmap时降级载荷仍按真实字节入账]），钉「退到零」会假红。
        val dir = Files.createTempDirectory("eratw-gfx-headroom")
        val store = MappedPngPayloadStore(dir)
        try {
            // 降级档有 25 ms 单发时间帽（[GraphicsPool.DEMOTE_PASS_BUDGET_NS]）：真机上是
            // 「每语句检查点推一发、逐发推进」，单发不保证一次退干净。这里照生产的节律循环，
            // 直到池级驻留不再下降为止 —— 判据是「闸最终能退到零」，不是「一发退到零」。
            var freed = 0L
            var pass = 0
            while (pool.residentBytes() > 0L && pass++ < 64) {
                val before = pool.residentBytes()
                freed += pool.evictWithinHeapHeadroom(
                    usedHeapBytes = before + nonPixel,
                    maxMemoryBytes = max,
                    payloadStore = store,
                )
                if (pool.residentBytes() == before) break
            }
            assertEquals(0L, pool.residentBytes(), "非像素越线时像素驻留必须退到零（尽量退）")
            assertEquals(resident / 4L, freed, "freed 按像素条数计（口径同 evictDeferredPixels）")
        } finally {
            store.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // ③ 降级通道（mmap 载荷）
    // ------------------------------------------------------------------

    /** mmap 载荷通道可用时：降级代 `retainedBytes == 0`（载荷出堆），像素仍逐字节可复原。 */
    @Test
    fun `mmap载荷通道_降级代驻留归零且像素逐字节复原`() {
        isolateHeapHeadroomGate()
        val dir = Files.createTempDirectory("eratw-gfx-heap-budget")
        val store = MappedPngPayloadStore(dir)
        try {
            val pool = GraphicsPool()
            val canvases = (0 until 4).map { paintedCanvas(pool, 100 + it) }
            assertEquals(sheetBytes * 4, pool.residentBytes(), "四张都驻留")
            assertTrue(canvases.all { it.retainedBytes == sheetBytes }, "降级前按像素阵列真实字节入账")

            // 同 [堆余量档_非像素堆越过安全线时驻留必须退到零]：照生产节律逐发推进（25 ms 时间帽）。
            var pass = 0
            while (pool.residentBytes() > 0L && pass++ < 64) {
                val before = pool.residentBytes()
                pool.evictWithinHeapHeadroom(
                    usedHeapBytes = 1_000_000L, // 非像素已越过安全线 ⇒ 余量预算 0
                    maxMemoryBytes = 1_000_000L,
                    payloadStore = store,
                )
                if (pool.residentBytes() == before) break
            }

            assertEquals(0L, pool.residentBytes(), "载荷出堆后池级驻留必须归零")
            assertEquals(pool.residentBytes(), pool.stats().pixelBytes, "stats 与原子读数同一把尺")
            for (buffer in canvases) {
                assertTrue(buffer.isPixelsDemoted, "必须处在降级态")
                assertEquals(0L, buffer.retainedBytes, "载荷在 mmap 文件里 ⇒ 不占 Java 堆字节")
                assertNotNull(buffer.demotedCredential(), "降级代必须给只读凭据（渲染侧按它判缓存）")
                val png = buffer.demotedPngBytes()
                assertNotNull(png, "堆外载荷的字节口必须能读出 PNG")
                assertTrue(png.isNotEmpty())
                val px = buffer.pixels // 晋升
                assertEquals(side * side, px.size)
                for (v in px) assertEquals(0xFF336699.toInt(), v, "mmap 载荷往返必须逐字节相同")
            }
        } finally {
            store.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    /** 无 mmap（headless / 语料对拍）：payloadStore == null 时回落堆内闭包，载荷按真实字节入账。 */
    @Test
    fun `无mmap时降级载荷仍按真实字节入账_像素逐字节复原`() {
        isolateHeapHeadroomGate()
        val pool = GraphicsPool()
        val canvases = (0 until 4).map { paintedCanvas(pool, 200 + it) }

        pool.evictWithinHeapHeadroom(
            usedHeapBytes = 1_000_000L,
            maxMemoryBytes = 1_000_000L,
            payloadStore = null,
        )

        assertTrue(canvases.all { it.isPixelsDemoted }, "无载荷通道时照样走降级档")
        val accounted = canvases.sumOf { it.retainedBytes }
        assertTrue(accounted > 0L, "堆内 PNG 载荷必须按真实字节入账（不许记 0）")
        assertEquals(accounted, pool.residentBytes(), "池级驻留必须等于逐条 retainedBytes 之和")
        assertEquals(pool.residentBytes(), pool.stats().pixelBytes, "stats 与原子读数同一把尺")
        for (buffer in canvases) {
            assertTrue(buffer.retainedBytes < sheetBytes, "压缩载荷必须小于原像素阵列")
            val px = buffer.pixels
            assertEquals(side * side, px.size)
            for (v in px) assertEquals(0xFF336699.toInt(), v, "堆内载荷往返必须逐字节相同")
        }
    }
}
