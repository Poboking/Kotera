package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 负号段（CSV 资源表父图）逐出闸的**堆压力判据**（heap-oom lane，2026-11-17）。
 *
 * ## 钉的是哪条残余
 *
 * 本类的 [`SpritePool.evictDeferredPixelsWithinSharedBudget`] 此前只量「共用档剩余 =
 * `GraphicsPool.sharedPixelBudgetBytes() − gfxResidentBytes`」，看不见非像素那一段（程序表 /
 * 变量池 / 行缓冲）。上报簇 `2026-11-08 08:39:57`（0.2.39、HUAWEI AL-AL00）的形状：
 * **负号段驻留 48 MiB 落在 128 MiB 共用档之内** ⇒ 闸判「不需要动」，而进程堆已到 512 MiB 挡的
 * 100%，下一次分配当场 `OutOfMemoryError`。判据加在
 * [`SpritePool.evictDeferredPixelsWithinBudget`]：`usedHeapBytes > heapLineBytes` 时**即使池
 * 账本没超预算**也逐，逐到 `已退字节 ≥ usedHeapBytes − heapLineBytes` 即停；安全线复用
 * [`GraphicsBuffer.HEAP_SAFETY_PERCENT`]（80，与正号段
 * [`GraphicsPool.evictWithinHeapHeadroom`] 同一个常量，不新增旋钮）。
 *
 * ## 装置口径（core 层纯 JVM：读数只从参数进，不挂静态可变全局）
 *
 * 两条读数都显式注入：`maxMemoryBytes = MAX`（⇒ 安全线 = `MAX × 80%`），`usedHeapBytes` 按例
 * 取「安全线上 / 线下」。真机要到 0.8 × 512 MiB 才成立，headless 造不出那个形状，所以不许拿
 * `Runtime` 的真读数写断言（那只会写出一条看运气绿的用例）。
 *
 * ## 用例覆盖面
 *
 * ① 低压（堆在安全线下）⇒ 账本没超预算就**一张都不许退**；② 高压（堆越线）⇒ 账本没超预算
 * 也**必须退**，且**只退到够**为止；③ 逐出后同一个 sprite 再取像素**逐字节等价**（重解判据
 * 没被放宽，也没把内存问题换成显示问题）；④ 护持者（`protect`）在高压下仍不退；⑤ 不可重解
 * 的缓冲（真解失败、`deferredSource == null`）在高压下也不退；⑥ 堆上限不可知 ⇒ 判据不生效
 * （桌面装置与既有调用点逐字不变）；⑦ 共用档入口把注入的读数**真的传下去**（接线证明 ——
 * 少了它，判据可能只存在于内层函数而宿主那条路一格都没吃到）。
 *
 * 全部走 [ResourceContents.load] 的真实装载路（假宿主、真解析），64×64 / 16 KiB 一张。
 */
class SpritePoolHeapPressureGateTest {

    private val side = 64
    private val sheetPixels = side * side.toLong()          // 4096
    private val sheetBytes = sheetPixels * 4L               // 16384

    /** 注入用的「堆上限」：安全线 = 800_000 字节（= 1_000_000 × 80%）。 */
    private val maxBytes = 1_000_000L
    private val lineBytes = maxBytes / 100L * GraphicsBuffer.HEAP_SAFETY_PERCENT  // 800_000

    @AfterTest
    fun clearOverride() {
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
    }

    /** 假宿主：CSV 逐行登记「name,file.webp」，尺寸从 image 表给，像素由确定性函数给出。 */
    private class FakeHost(
        private val sizes: MutableMap<String, IntArray> = LinkedHashMap(),
        private val seeds: MutableMap<String, Int> = LinkedHashMap(),
        private val broken: MutableSet<String> = LinkedHashSet(),
    ) : ResourceFileHost {

        val decodeCalls = HashMap<String, Int>()
        private val csVs = ArrayList<String>()
        private val rows = LinkedHashMap<String, String>()

        fun csv(path: String, body: String) {
            csVs.add(path)
            rows[path] = body
        }

        fun image(path: String, w: Int, h: Int, seed: Int) {
            sizes[path] = intArrayOf(w, h)
            seeds[path] = seed
        }

        fun brokenImage(path: String, w: Int, h: Int) {
            sizes[path] = intArrayOf(w, h)
            broken.add(path)
        }

        override fun listCsvFiles(): List<String> = csVs

        override fun readBytes(path: String): ByteArray? =
            rows[path]?.toByteArray(Charsets.UTF_8) ?: if (sizes.containsKey(path)) ByteArray(0) else null

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? = sizes[path]?.copyOf()

        override fun decode(path: String, bytes: ByteArray): PngImage? {
            decodeCalls[path] = (decodeCalls[path] ?: 0) + 1
            if (path in broken) return null
            val size = sizes[path] ?: return null
            val seed = seeds[path] ?: 0
            return PngImage(size[0], size[1], synthPixels(seed, size[0] * size[1]))
        }
    }

    /** 装载 n 张独立父图（每张恰好被一个整图 sprite 引用），返回池与宿主。 */
    private fun loadSheets(count: Int): Pair<SpritePool, FakeHost> {
        val host = FakeHost()
        val body = StringBuilder()
        for (i in 1..count) {
            body.append("S$i,s$i.webp\n")
            host.image("resources/heap/s$i.webp", side, side, seed = 2000 + i)
        }
        host.csv("resources/heap/heap.csv", body.toString())
        val pool = SpritePool()
        val report = ResourceContents.load(pool, host)
        assertTrue(report.warnings.isEmpty(), "装载不该有告警：${report.warnings}")
        assertEquals(count, report.spriteCount)
        return pool to host
    }

    /** 首访问落地一张父图的像素（真机上由 GDRAWSPRITE 合成读触发）。 */
    private fun materialize(pool: SpritePool, name: String): GraphicsBuffer {
        val buffer = pool.find(name)!!.buffer!!
        buffer.pixels
        return buffer
    }

    // ------------------------------------------------------------------
    // ①② 低压不退 / 高压必退（同一形状只差 `usedHeapBytes`，成对自证判据在做事）
    // ------------------------------------------------------------------

    @Test
    fun `堆在安全线下且账本未超预算时一张都不退`() {
        val (pool, host) = loadSheets(3)
        materialize(pool, "S1"); materialize(pool, "S2"); materialize(pool, "S3")
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes(), "前置：三张都驻留")

        // 账本额度恰好容得下三张（不支持账本判据），堆恰好落在安全线上（不触发压力判据）。
        val freed = pool.evictDeferredPixelsWithinBudget(
            budgetBytes = sheetBytes * 3,
            usedHeapBytes = lineBytes,
            heapLineBytes = lineBytes,
        )
        assertEquals(0L, freed, "堆 = 安全线（不越线）+ 账本未超 ⇒ 一像素都不许退")
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes())
        assertEquals(3, host.decodeCalls.size, "零动作 ⇒ 一次重解都不该发生")
    }

    @Test
    fun `堆越过安全线时账本未超预算也必须退且只退够`() {
        val (pool, host) = loadSheets(3)
        materialize(pool, "S1"); materialize(pool, "S2"); materialize(pool, "S3")
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes(), "前置：三张都驻留")

        // 越线 12_288 字节 ⇒ 退一张（16_384）就够，第二张必须留着（「退到够」不是「全退」）。
        val decodesBefore = host.decodeCalls.toMap()
        val used = lineBytes + 12_288L
        val freed = pool.evictDeferredPixelsWithinBudget(
            budgetBytes = sheetBytes * 3,   // 账本判据恒不成立：本例只让堆压力说话
            usedHeapBytes = used,
            heapLineBytes = lineBytes,
        )
        assertEquals(sheetPixels, freed, "越线 12_288 字节、单张 16_384 字节 ⇒ 只退一张")
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes(), "三张剩两张")
        assertEquals(decodesBefore, host.decodeCalls.toMap(), "逐出只丢引用，一次解码都不许多付")

        // 对照臂：同一形状把堆读数压回线下 ⇒ 同一张必须不动（自证这一退是堆判据给的，
        // 不是「闸本来就会退」）。
        materialize(pool, "S1")
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes())
        assertEquals(
            0L,
            pool.evictDeferredPixelsWithinBudget(
                budgetBytes = sheetBytes * 3,
                usedHeapBytes = lineBytes - 1L,
                heapLineBytes = lineBytes,
            ),
            "堆在安全线下 1 字节 ⇒ 不许退",
        )
        assertEquals(sheetBytes * 3, pool.resourceResidentPixelBytes())
    }

    // ------------------------------------------------------------------
    // ③ 逐出后重解：像素逐字节等价（内存问题不许换成显示问题）
    // ------------------------------------------------------------------

    @Test
    fun `高压逐出后同一个sprite再取像素逐字节等价`() {
        val (pool, host) = loadSheets(2)
        val first = materialize(pool, "S1")
        materialize(pool, "S2")
        val before = first.pixels.copyOf()
        val beforeBytes = first.pixelBytes
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes())

        // 越线远超两张：两张全退（候选穷尽即停）。
        val freed = pool.evictDeferredPixelsWithinBudget(
            budgetBytes = sheetBytes * 2,
            usedHeapBytes = lineBytes + sheetBytes * 10L,
            heapLineBytes = lineBytes,
        )
        assertEquals(sheetPixels * 2, freed, "压力远超驻留 ⇒ 可重解的整段退完")
        assertEquals(0L, pool.resourceResidentPixelBytes(), "退完即零驻留")

        // 再取：同一 crop/sprite 名、同一缓冲对象，像素逐字节相同（重解必得同一张图）。
        val again = pool.find("S1")!!.buffer!!
        assertSame(first, again, "退的是像素，不是表条目：同一 sprite 仍指向同一缓冲")
        assertContentEquals(before, again.pixels, "重解出的像素必须与逐出前逐字节一致")
        assertEquals(beforeBytes, again.pixelBytes, "尺寸/占用读数复原")
        assertEquals(2, host.decodeCalls.size, "重解走的就是装载期那条 decode 路")
        assertTrue(host.decodeCalls["resources/heap/s1.webp"]!! >= 2, "S1 至少解了两次（首访 + 重解）")
    }

    // ------------------------------------------------------------------
    // ④ 护持者：高压下也不许动
    // ------------------------------------------------------------------

    @Test
    fun `高压下护持者不被逐出而其余照退`() {
        val (pool, _) = loadSheets(3)
        val pinned = materialize(pool, "S1")
        materialize(pool, "S2"); materialize(pool, "S3")

        val freed = pool.evictDeferredPixelsWithinBudget(
            budgetBytes = sheetBytes * 3,
            protect = pinned,
            usedHeapBytes = lineBytes + sheetBytes * 10L,
            heapLineBytes = lineBytes,
        )
        assertEquals(sheetPixels * 2, freed, "除护持者外的两张照退")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes(), "护持者那一张必须还在")
        assertEquals(sheetBytes, pinned.pixelBytes, "护持者像素一格不动")
    }

    // ------------------------------------------------------------------
    // ⑤ 可退集不许放宽：不可重解的缓冲在高压下也不退
    // ------------------------------------------------------------------

    @Test
    fun `高压下不可重解的缓冲仍不在候选集`() {
        val host = FakeHost()
        host.csv("resources/heap2/h2.csv", "OK,ok.png\nBAD,bad.png\n")
        host.image("resources/heap2/ok.png", side, side, seed = 7)
        host.brokenImage("resources/heap2/bad.png", side, side)
        val pool = SpritePool()
        ResourceContents.load(pool, host)

        val bad = materialize(pool, "BAD")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes(), "真解失败的透明兜底图照样占驻留")

        val freed = pool.evictDeferredPixelsWithinBudget(
            budgetBytes = Long.MAX_VALUE,
            usedHeapBytes = lineBytes + sheetBytes * 10L,
            heapLineBytes = lineBytes,
        )
        assertEquals(0L, freed, "解码失败 ⇒ 重解不保证同图 ⇒ 堆压力再大也不许退")
        assertEquals(sheetBytes, pool.resourceResidentPixelBytes())
        assertEquals(sheetBytes, bad.pixelBytes)
    }

    // ------------------------------------------------------------------
    // ⑥ 堆上限不可知：判据不生效（桌面装置 / 既有调用点逐字不变）
    // ------------------------------------------------------------------

    @Test
    fun `堆上限不可知时压力判据永不生效`() {
        val (pool, _) = loadSheets(2)
        materialize(pool, "S1"); materialize(pool, "S2")

        // 安全线不可知 ⇒ `heapSafetyLineBytes` 回 Long.MAX_VALUE ⇒ 压力判据恒不成立，
        // 只剩池账本（本例给一个大额度）⇒ 零动作。
        assertEquals(
            0L,
            pool.evictDeferredPixelsWithinBudget(
                budgetBytes = sheetBytes * 2,
                usedHeapBytes = 1L shl 40,
                heapLineBytes = GraphicsBuffer.heapSafetyLineBytes(Long.MAX_VALUE),
            ),
            "堆上限不可知 ⇒ 不许拿拍出来的绝对值截桌面装置",
        )
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes())
    }

    // ------------------------------------------------------------------
    // ⑦ 接线证明：共用档入口把注入的读数传下去
    // ------------------------------------------------------------------

    @Test
    fun `共用档入口把堆读数传给内层判据`() {
        // 账本侧关掉（共用档 = Long.MAX_VALUE）⇒ 若入口没把读数传下去，这一发必然退回 0。
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, Long.MAX_VALUE.toString())
        val (pool, _) = loadSheets(3)
        materialize(pool, "S1"); materialize(pool, "S2"); materialize(pool, "S3")

        val used = lineBytes + 12_288L
        val freed = pool.evictDeferredPixelsWithinSharedBudget(
            gfxResidentBytes = 0L,
            usedHeapBytes = used,
            maxMemoryBytes = maxBytes,
        )
        assertEquals(sheetPixels, freed, "账本关掉后仍退一张 ⇒ 读数确实进了内层判据")
        assertEquals(sheetBytes * 2, pool.resourceResidentPixelBytes())

        // 同一入口、同一账本，只把堆读数压回线下 ⇒ 必须不退（第二条对照）。
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, Long.MAX_VALUE.toString())
        val (pool2, _) = loadSheets(3)
        materialize(pool2, "S1"); materialize(pool2, "S2"); materialize(pool2, "S3")
        assertEquals(
            0L,
            pool2.evictDeferredPixelsWithinSharedBudget(
                gfxResidentBytes = 0L,
                usedHeapBytes = lineBytes,
                maxMemoryBytes = maxBytes,
            ),
            "堆在安全线上（不越线）⇒ 共用档入口零动作",
        )
        assertEquals(sheetBytes * 3, pool2.resourceResidentPixelBytes())
    }
}

/** 确定性合成像素：同一 seed 重解逐字同图（`evictDeferred` 判据的装置化）。 */
private fun synthPixels(seed: Int, count: Int): IntArray =
    IntArray(count) { idx -> 0xFF000000.toInt() or ((idx * 31 + seed) and 0x00FFFFFF) }
