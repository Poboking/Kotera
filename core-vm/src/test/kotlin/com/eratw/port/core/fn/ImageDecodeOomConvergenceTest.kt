package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * 解码收口 OOM 不对称的修复回归（stab-b 条目 3，RECON-STAB-B.md）。
 *
 * ## 病与修法
 *
 * `readImageFile` 的三处解码收口（宿主通道 `decoder.decode` / [com.eratw.port.core.image.PngDecoder]
 * / [com.eratw.port.core.image.WebpDecoder]）此前只有 `catch (Exception)`，而 Kotlin 的
 * `OutOfMemoryError` 属 `Error` —— 宿主解码通道（platform BitmapFactory 无尺寸预算闸，交叉面
 * 挂账）堆贴顶时 OOM 直接穿过收口带走 VM 线程 = 闪退。原版 `DoScript` 顶层 `catch (Exception)`
 * 在 .NET 里**含 OutOfMemoryException**，收下后红字继续跑（Process.cs:462-477）。
 * 修法照 `FileSystemOps.loadData` 的既有形状：Exception 分支保持原样（折 0），另加
 * `catch (e: Throwable)` 收敛成 CodeEE 红字 + initCause 挂因（ADR-005：更好处置，受控结局
 * 优于闪退）。
 *
 * ## 判据
 *
 * | # | 行为 | 用例 |
 * |---|---|---|
 * | 1 | 宿主解码抛 OutOfMemoryError → CodeEE 红字（不是裸穿），cause = 原 OOM | 「宿主解码抛 OutOfMemoryError_」 |
 * | 2 | 其它 Error 子类同样收敛 | 「宿主解码抛 Error 子类_」 |
 * | 3 | 非 OOM 的 Exception 行为一字不动（折 0 + trace 留名） | 「宿主解码抛 Exception_」 |
 * | 4 | core 自带解码器坏流（Exception 支）行为不变 | 「PNG 坏流_」「WEBP 坏流_」两条 |
 * | 5 | GLOAD 走同一收口（第三条调用面接线） | 「GLOAD 宿主解码抛 OutOfMemoryError_」 |
 * | 6 | 延迟物化面的 OOM 仍由 GraphicsBuffer.pixels 的 Throwable 收口折透明，不裸穿 | 「延迟装载面解码 OOM_」 |
 *
 * 反向证明：把三处收口的 `catch (e: Throwable)` 分支删掉（改回 Exception-only），上表 1/2/5/6
 * 立即红（OOM 裸穿成 OutOfMemoryError 而不是 CodeEE；用例 6 直接拿到的也是裸 Error）。
 *
 * 最小分配口径：收敛动作只用零插值静态消息（断言 message 恒等字面量）与静态 trace.reason
 * 字面量（`decode-error`，无 `:` 拼接尾巴）；用例本身不靠大分配触发 OOM。
 *
 * 装置与 `core/host/GraphicsDeferredFileTest` 同构：内存 FileOps + 可注入 readFileBytes +
 * 独立 VariableStore。假 decoder 只实现 decode（probe 用缺省 null = 无延迟能力），
 * GCREATEFROMFILE 因此走「立即解码」老路，正对 readImageFile 的三处收口。
 */
private class OomBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("OomBaseCtx 不应被直接调用：$name")

    // 非空语句体：本装置不接收红字，真被调到就响亮失败（既有装置的空体在各文件基线条目里，
    // 本文件不新增基线）。本批用例的路径（GCREATEFROMFILE / GLOAD / GGETCOLOR）不会走到这里。
    override fun printSystemLine(message: String) {
        check(message.isNotEmpty()) { "测试装置不应接收红字输出：$message" }
    }
}

/** 内存 FileOps：路径即键，写入成功恒 true。 */
private class OomOps : FileOps {
    val files = LinkedHashMap<String, String>()
    val bytes = LinkedHashMap<String, ByteArray>()
    override fun exists(path: String): Boolean = files.containsKey(path) || bytes.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class OomEnv(val ops: OomOps = OomOps()) {
    val store = VariableStore()
    private val base = OomBaseCtx().also { it.variables = store }
    val ctx: BuiltinEvalContext = BuiltinEvalContext(
        base,
        store,
        object : BuiltinHost {
            override val fileOps: FileOps? = ops
            override fun readFileBytes(path: String): ByteArray? = ops.bytes[path]
        },
    )

    fun install(decoder: ImageDecoderHost) {
        store.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) { decoder }
    }

    fun call(name: String, vararg args: Any): Long {
        val value = ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        )
        return (value as Value.IntV).v
    }
}

/** decode 按预置异常炸（probe 缺省 null ⇒ 走立即解码路径，正对 readImageFile 的收口）。 */
private class ThrowingDecoder(
    private val thrown: Throwable,
) : ImageDecoderHost {
    var decodeCalls = 0
        private set

    override fun decode(path: String, bytes: ByteArray): PngImage? {
        decodeCalls++
        throw thrown
    }
}

/** probe 能量尺寸、decode 必炸的假宿主（延迟装载面用例 6 用）。 */
private class DeferredThenThrowDecoder(
    private val thrown: Throwable,
) : ImageDecoderHost {
    override fun decode(path: String, bytes: ByteArray): PngImage? = throw thrown

    override fun probe(path: String, bytes: ByteArray): IntArray? = intArrayOf(4, 3)
}

/** 非 OOM 的 Error 子类：钉「收敛面是整个 Error 支，不只 OutOfMemoryError 一个类」。 */
private class FakeVmError(message: String) : Error(message)

/** PNG 签名（8 字节）+ 垃圾尾巴：isPng 真、decode 必抛 PngDecodeException（Exception 支）。 */
private val BROKEN_PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    1, 2, 3, 4,
)

/** RIFF/WEBP 容器头 + 截断位流：isWebp 真、decode 抛 WebpDecodeException（Exception 支）。 */
private val BROKEN_WEBP = byteArrayOf(
    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
    8, 0, 0, 0,
    'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
    'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
)

class ImageDecodeOomConvergenceTest {

    private fun envWith(path: String, bytes: ByteArray, decoder: ImageDecoderHost): OomEnv =
        OomEnv().apply {
            ops.bytes[path] = bytes
            install(decoder)
        }

    // ==================================================================
    // 1) 宿主解码通道 OOM → CodeEE 红字 + initCause（真实触发面：platform BitmapFactory
    //    无预算闸，大立绘堆贴顶；core-vm 侧唯一能注入的收口点）
    // ==================================================================

    @Test
    fun `宿主解码抛 OutOfMemoryError_收敛 CodeEE 红字并挂因`() {
        val oom = OutOfMemoryError("模拟 BitmapFactory 解 8000x4000 立绘堆贴顶")
        val e = envWith("resources/pic.webp", byteArrayOf(1, 2, 3, 4), ThrowingDecoder(oom))

        val codee = assertFailsWith<CodeEE> { e.call("GCREATEFROMFILE", 30, "pic.webp") }
        // 静态消息（零插值）—— 与生产面 MES_IMAGE_DECODE_ERROR 字面量逐字一致
        assertEquals("图片解码内存不足或虚拟机错误（Error）：该图未载入", codee.message)
        // initCause 补账：根因（真正的 OOM）必须挂在因果链上供取证面读取
        assertIs<OutOfMemoryError>(codee.cause)
        assertEquals(oom, codee.cause)
        // trace 留静态落点（无 `:` 拼接尾巴 = 收敛动作没做大字符串分配）
        val trace = e.store.aux.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)
        assertNotNull(trace)
        assertEquals("decode-error", trace.reason)
    }

    @Test
    fun `宿主解码抛 Error 子类_同样收敛 CodeEE 红字`() {
        val err = FakeVmError("模拟其它虚拟机错误")
        val e = envWith("resources/pic.webp", byteArrayOf(1, 2, 3, 4), ThrowingDecoder(err))

        val codee = assertFailsWith<CodeEE> { e.call("GCREATEFROMFILE", 31, "pic.webp") }
        assertIs<FakeVmError>(codee.cause)
        assertEquals(err, codee.cause)
        assertEquals("decode-error", e.store.aux.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)?.reason)
    }

    // ==================================================================
    // 2) 非 OOM 的既有 Exception 路径行为不变（判据 2）
    // ==================================================================

    @Test
    fun `宿主解码抛 Exception_既有行为不变折 0`() {
        val decoder = ThrowingDecoder(IllegalStateException("模拟 BitmapFactory 对畸形字节抛异常"))
        val e = envWith("resources/pic.webp", byteArrayOf(1, 2, 3, 4), decoder)

        // 原 Utils.LoadImage 空 catch 口径（Utils.cs:502-525）：折 0，不抛、不崩
        assertEquals(0L, e.call("GCREATEFROMFILE", 32, "pic.webp"))
        assertEquals(0L, e.call("GCREATED", 32))
        // 折 0 后整条回落链走到头（4 字节既非 PNG 也非 webp、无文本载体），
        // trace 最终落点是 no-decoder-chain —— 与改动前逐项一致
        val trace = e.store.aux.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)
        assertNotNull(trace)
        assertEquals("no-decoder-chain", trace.reason, "非 OOM Exception 的整条回落链结局不变")
    }

    @Test
    fun `PNG 坏流非 OOM 行为不变折 0`() {
        // 不装宿主：bytes 非 null 且 isPng ⇒ 直接走 PngDecoder 收口（第二处）
        val e = OomEnv().apply { ops.bytes["resources/broken.png"] = BROKEN_PNG }
        assertEquals(0L, e.call("GCREATEFROMFILE", 33, "broken.png"))
        assertEquals(0L, e.call("GCREATED", 33))
    }

    @Test
    fun `WEBP 坏流非 OOM 行为不变折 0`() {
        // 不装宿主：isWebp ⇒ 走 WebpDecoder 收口（第三处），坏位流抛 WebpDecodeException（Exception 支）
        val e = OomEnv().apply { ops.bytes["resources/broken.webp"] = BROKEN_WEBP }
        assertEquals(0L, e.call("GCREATEFROMFILE", 34, "broken.webp"))
        assertEquals(0L, e.call("GCREATED", 34))
        val trace = e.store.aux.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)
        assertNotNull(trace)
        assertEquals(true, trace.reason?.startsWith("webp-fail:"), "webp-fail 留名口径不变，实际 ${trace.reason}")
    }

    // ==================================================================
    // 3) GLOAD 与 GCREATEFROMFILE 共用同一收口（第三条调用面接线）
    // ==================================================================

    @Test
    fun `GLOAD 宿主解码抛 OutOfMemoryError_收敛 CodeEE 红字`() {
        val oom = OutOfMemoryError("模拟 GLOAD 大图堆贴顶")
        // GLOAD 不接 ContentDir 前缀，入参是存档图路径；本夹具未装 SaveWiring ⇒ SavDir = 根目录
        val e = envWith("img0000.png", byteArrayOf(1, 2, 3, 4), ThrowingDecoder(oom))

        val codee = assertFailsWith<CodeEE> { e.call("GLOAD", 40, 0) }
        assertIs<OutOfMemoryError>(codee.cause)
        assertEquals(oom, codee.cause)
    }

    // ==================================================================
    // 4) 延迟装载面：OOM 在 GraphicsBuffer.pixels 的既有 Throwable 收口里折透明（既有行为，
    //    钉住证明本笔改动在这条面无回归——CodeEE 不会裸穿 VM）
    // ==================================================================

    @Test
    fun `延迟装载面解码 OOM_按既有像素层降级折 0 不裸穿`() {
        val oom = OutOfMemoryError("模拟延迟物化时堆贴顶")
        val e = envWith("resources/pic.webp", byteArrayOf(1, 2, 3, 4), DeferredThenThrowDecoder(oom))

        // probe 能量出尺寸 ⇒ 延迟形态建缓冲成功（返回 1），此刻一个像素都没解
        assertEquals(1L, e.call("GCREATEFROMFILE", 41, "pic.webp"))
        assertEquals(1L, e.call("GCREATED", 41))
        assertEquals(4L, e.call("GWIDTH", 41))
        // 首像素访问触发物化：readImageFile 抛 CodeEE（本笔新行为），但 GraphicsBuffer.pixels
        // 的 Throwable 收口把它折成同尺寸透明图 —— GGETCOLOR 返回 0，不裸穿、不闪退
        assertEquals(0L, e.call("GGETCOLOR", 41, 0, 0))
    }
}
