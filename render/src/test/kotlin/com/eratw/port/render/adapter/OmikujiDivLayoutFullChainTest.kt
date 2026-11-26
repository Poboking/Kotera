package com.eratw.port.render.adapter

import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「幻存神签」版面**全玩家链**回归锁（QB-A12，2026-11-20）。
 *
 * ## 本锁补的验收缺口
 *
 * 玩家案：银时君 11-07「每月神签出现档着图了」→ 0.2.44（11-10）宣称修复 →
 * **actifse 11-10 02:43 复验「幻存神签仍然错位」** → 0.2.45（11-11）「div 覆盖块锚行
 * 与塌缩预留区判据收口」→ 11-15 后全月零复发，但**没有任何回归锁**。此前的回归面各护
 * 一刀的半段，没有一处把玩家实际走的那条链（真语料 ERB → 控制台 → 适配 → 展平 →
 * 图块合并/塌缩 → 覆盖规划）从头到尾钉住：
 * - `ImageBandMergeTest`：合成行手工构造，只钉 bandOf / oversizedImageOf / 预留区三道
 *   守卫各自的单元行为；
 * - `DivOverlayTest` / `DivChildWrapTest`：只钉覆盖规划与子行折行的几何；
 * - 三道守卫若被下一次重构整体摘除而单测仍按旧构造喂行，单元锁全绿、真语料形态漏过。
 *
 * ## 病史（三刀提交，与本锁断言的对应）
 *
 * 1. `cc5972c4b`（0.2.44，11-08）：div 子行按 SubDivisionWidth 折行 —— 修「诗文被内容盒
 *    裁切」。**11-10 复验仍坏**：它治的是子行折行，玩家复验报的「错位」是另一条机制
 *    （见 2/3），两刀之间没有测试区分症状。
 * 2. `1adf1e021`（0.2.45，11-10 09:25）：bandOf / oversizedImageOf 跳过带 divBlocks 的行
 *    —— 神签 img（`神签.ERB:64`）与 div 面板（`:80`）同高（都 = `IMG_H_PX =
 *    CLIENTHEIGHT() - 8*一行の高さ`，`神签.ERB:35`）、同锚行；真机块高预算
 *    （`CanvasConsoleView.bandMaxHeightPx` = 内容高 × 0.6）装不下 img 时，旧代码把 img
 *    缩到预算、预留行塌缩上移，div 面板仍按脚本像素全尺寸画 —— 盒底悬进底部文字区，
 *    即玩家看到的「神签图与底部文字错位」。
 * 3. `4e7fb6c4e`（11-10 16:13）：塌缩预留区扫描的 isBlankLine 只看 runs 不查 divBlocks
 *    —— 只印 div 不印文字的行看起来「空白」，块随行消失、锚行序漂移。同一根因的另一条
 *    入口，0.2.45 一并收口。
 *
 * ## 原版语义（为什么守卫是「不更动版面」的唯一真解）
 *
 * 原版行推进是恒定行距（`EmueraConsole.cs:1855` 的 `num += Config.LineHeight`），img 按
 * 自己的 destRect 全尺寸画、溢出就压到后面内容上（`ConsoleImagePart.cs:82` 的
 * `destRect`），div 面板是 `ConsoleDivPart.cs:130` 的 `Height = MixedNum.ToPixel(height)`
 * 定值几何、`:66` `CanDivide => false`、`:175-187` 按锚行行条顶 + 定值矩形画 ——
 * **原版不存在任何「缩图/删行」**。本端口的竖带合并与超高图塌缩是移植侧排版优化
 * （`ImageBandMerge`），它一动锚行，与它共用锚行的 div 面板就脱锚 —— 所以判据只能是
 * 「带 div 覆盖块的行、预留区混带块的行，宁可漏合、一行不动」（`ImageBandMerge.kt`
 * 三道守卫），这与 QB-A10 案的「单一度量源」同一道理：覆盖块按脚本像素锚定所在行、
 * 定值几何，不跟版面重排。
 *
 * ## 全链时序（真语料 + 生产对象图）
 *
 * 1. 真语料 erbc 装配 `GameSession`，登记角色 0..12（`QOL_KOJO_COLOR` 是角色变量，按
 *    **登记序**寻址，`VariableStore.requireSparseCharaRow` 的 `pos < characters.count`；
 *    eraTW 开局全量 ADDCHARA 后登记位 == NO，`神签TOID:1 = 12` 见 神签.ERH）。
 * 2. `rt.callFunction("神签打印", 1)` 真跑：`生成图片`（魔改内容/Misc.ERB:141 的
 *    GCREATEFROMFILE + SPRITECREATE）→ `HTML_PRINT`（神签.ERB:98）→ 行 0 = img +
 *    div 覆盖块，行 1..17 = `IMG_H_PX/LINE_H` 条预留空行（神签.ERB:99-101）。
 * 3. 真机的 GCREATEFROMFILE 走宿主解码通道量尺寸（`GraphicsMethods.deferredImageFile`
 *    的 `ImageDecoderHost.probe`，只量尺寸不落像素）；本锁用 JVM 侧同接口探针复现
 *    同一后果（有损 webp 核心解码器不支持，真机尺寸来自宿主 probe ——
 *    神签_1.webp 实测 VP8X+ALPH+VP8 有损 686×970）。
 * 4. `ConsoleAdapter.adapt`（div 区间剔除 + divBlocks 搬运）→ `HtmlRunFlatten.flattenLine`
 *    （img 段按 sprite 纵横比落尺寸，`ConsoleImagePart.cs:67-71` 口径）→
 *    `ImageBandMerge.merge`（真机档：预算 648px = 1080×0.6）→ `DivOverlayPlanner.planFrame`。
 *
 * ## 每条断言锁哪道闸（摘守卫 = 红，见突变探针读数）
 *
 * - 形态前提：img 设备高（scriptPx(IMG_H_PX)）> 块高预算 —— 证明本锁真的站在
 *   「塌缩会盯上这一行」的形态上，断言不是真空转。
 * - `merged.size == lines.size` + 预留行逐条还在：塌缩删行 / 预留区误判「空白」的闸
 *   （1adf1e021 + 4e7fb6c4e 的直接面）。
 * - img `heightPx` 不缩、`rowSpan == 0`：oversizedImageOf 守卫的闸。
 * - divBlocks 原样留在锚行：锚行序漂移的闸。
 * - planFrame 面板底 == img 底、面板高 == img 高：玩家症状的直接判据 —— 盒底悬进
 *   底部文字区（错位）即红。
 *
 * 环境门：`ERATW_ERBC_DIR` + `ERATW_GAMEDIR`（缺变量不出用例，同 ComOptionFullChainReproTest 口径）。
 */
class OmikujiDivLayoutFullChainTest {

    private companion object {
        const val RAND_SEED = 20260226L

        /** 神签打印的实参（1..128 都印 img；1 = 露米娅签，语料第一个签文）。 */
        const val OMIKUJI_ID = 1L

        /**
         * 真机档渲染尺（HtmlGraphicFoldParityTest 的「真机档」同款）：生效字号 41 / 行高 43
         * （脚本字号 18 的放大档），块高预算 = 1080 屏 × 0.6 = 648
         * （`CanvasConsoleView.BAND_MAX_HEIGHT_SHARE`，CanvasConsoleView.kt:958/:5015）。
         */
        const val DEVICE_FONT_PX = 41
        const val DEVICE_LINE_PX = 43
        const val SCRIPT_FONT_PX = 18
        const val BAND_MAX_HEIGHT_PX = 648
        const val AVAIL_WIDTH_PX = 1080
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** 环境门：两个变量都在位且产物齐备才返回路径对，否则 null（缺变量不出用例）。 */
    private fun corpusOrNull(): Pair<Path, Path>? {
        val erbcDir = env("ERATW_ERBC_DIR")
        val gameRoot = env("ERATW_GAMEDIR")
        if (erbcDir == null || gameRoot == null) return null
        val erbc = Path.of(erbcDir)
        val usable = Files.isDirectory(erbc) && Files.isRegularFile(erbc.resolve("program.erbc"))
        return if (usable) erbc to Path.of(gameRoot) else null
    }

    /**
     * JVM 侧 `ImageDecoderHost`：只实现 [ImageDecoderHost.probe]（真机通道在本测试里的
     * 对应物），按 webp 头量宽高、一个像素不落 —— 与 `GraphicsMethods.deferredImageFile`
     * 的「量尺寸建延迟缓冲」同一后果。神签图是有损 webp（VP8X+ALPH+VP8），核心解码器
     * 明确不支持（WebpDecoder.kt:40），没有这一通道 SPRITECREATE 就不登记、img 会落
     * AltText 单出口（ConsoleImagePart.cs:60-66），整条链退化成文本行、锁不到守卫。
     */
    private class HeaderProbeDecoder : com.eratw.port.core.host.ImageDecoderHost {
        override fun probe(path: String, bytes: ByteArray): IntArray? {
            if (bytes.size < 30 || !bytes.decodeToString(0, 4).equals("RIFF") ||
                bytes.decodeToString(8, 12) != "WEBP"
            ) {
                return null
            }
            return when (bytes.decodeToString(12, 16)) {
                "VP8X" -> intArrayOf(
                    (bytes[24].toInt() and 0xFF) or
                        ((bytes[25].toInt() and 0xFF) shl 8) or
                        ((bytes[26].toInt() and 0xFF) shl 16) + 1,
                    (bytes[27].toInt() and 0xFF) or
                        ((bytes[28].toInt() and 0xFF) shl 8) or
                        ((bytes[29].toInt() and 0xFF) shl 16) + 1,
                )
                else -> null
            }
        }

        /**
         * 完整解码口：本锁只需要「量得出尺寸」，不需要像素 —— 返回 null 让装载侧按既有
         * 分支走（核心解码器对有损 webp 同样解不出），延迟缓冲链路与真机一致。
         */
        override fun decode(path: String, bytes: ByteArray): com.eratw.port.core.image.PngImage? = null
    }

    /** 真语料侧车：跑完「神签打印(1)」后的 sink 快照 + 真机档展平产物（adapt + flatten）。 */
    private class Chain(
        val adapted: List<com.eratw.port.render.RenderLine>,
        val flattened: List<com.eratw.port.render.RenderLine>,
        /** img 脚本像素高（= div 面板高，神签.ERB 让两者同值）。 */
        val imgScriptPx: Int,
        /** 锚行后随的预留空行条数（脚本 FOR 循环打印的 PRINTL 行）。 */
        val reservedRows: Int,
    )

    /** 真语料全链前半：装配 → 真跑神签打印 → sink → adapt → 展平（真机档尺）。 */
    private fun runChain(erbc: Path, game: Path): Chain {
        val program = ProgramLoader.loadMapped(erbc.resolve("program.erbc"))
        val metadata = BootMetadata.read(erbc.resolve("program.meta"))
        val session = GameSession(
            gameRoot = game,
            program = program,
            metadata = metadata,
            randSeed = RAND_SEED,
            // 生产口径：GameHost 建会话恒传 true（一屏一帧）。
            frameGateEnabled = true,
        )
        // 神签TOID:1 = 12（神签.ERH）→ QOL_KOJO_COLOR 按登记序寻址（VariableStore.kt:1265），
        // eraTW 开局全量 ADDCHARA 后登记位 == NO：按 NO 升序补齐 0..12 同形。
        for (no in 0L..12L) {
            if (session.store.template(no) != null) session.store.addCharacter(no)
        }
        // 真机宿主解码通道（量尺寸不落像素）：GraphicsMethods.deferredImageFile 的接口。
        session.store.aux.slot(IMAGE_DECODER_SLOT) { HeaderProbeDecoder() }

        val key = metadata.functions.firstOrNull { it.key == "神签打印" }?.key
            ?: error("神签打印 不在产物里（erbc 缺 DLC/神签）")
        session.runtime.callFunction(key, longArrayOf(OMIKUJI_ID), arrayOf())

        val snapshot = session.console.snapshot()
        val anchorIndex = snapshot.indexOfFirst { it.divBlocks.isNotEmpty() }
        assertTrue(anchorIndex >= 0, "真语料神签打印必须产出 div 覆盖块锚行（快照 ${snapshot.size} 行）")
        val anchorLine = snapshot[anchorIndex]
        val block = anchorLine.divBlocks.single()
        // img 脚本像素高：神签.ERB:64 的 height='{IMG_H_PX}px'。div 高度必须同值（:80 同一
        // IMG_H_PX，img/div/预留行是脚本排版的一件套 —— 1adf1e021 判据的语料事实）。
        val imgHtml = anchorLine.segments.joinToString("") { it.text }
        val imgScriptPx = Regex("height='(\\d+)px'").findAll(imgHtml)
            .firstOrNull()?.groupValues?.get(1)?.toInt()
            ?: error("神签锚行找不到 img height 属性：«${imgHtml.take(120)}»")
        assertEquals(
            block.heightPx,
            imgScriptPx,
            "语料形态：img 与 div 面板必须同高（神签.ERB:64/:80 同一 IMG_H_PX）",
        )
        assertTrue(block.isEscaped, "神签面板必须已登记逃逸（否则不进覆盖规划，DivOverlayPlanner.plan 的判据）")
        val reservedRows = snapshot.size - anchorIndex - 1
        assertTrue(reservedRows > 0, "神签锚行后必须有脚本预留的空行（神签.ERB:99-101），实际 0")

        val metrics = CellMetrics(DEVICE_FONT_PX, DEVICE_LINE_PX)
        val adapted = ConsoleAdapter.adapt(snapshot)
        // 尺寸源接 session 的 SpritePool（生产 = platform SpriteBitmapSource.destSize 的
        // 同一张表，SpriteBitmapSource.kt:824-826）：`<img>` 缺 width 按纵横比推宽。
        val sizeSource = HtmlRunFlatten.ImageSizeSource { resource ->
            session.store.aux.peek<SpritePool>(SPRITE_SLOT)?.find(resource)
                ?.let { (it.width shl 16) or it.height }
        }
        val flattener = HtmlRunFlatten(metrics, sizeSource, SCRIPT_FONT_PX)
        val flattened = adapted.map { flattener.flattenLine(it) }
        return Chain(adapted, flattened, imgScriptPx, reservedRows)
    }

    @Test
    fun `全链 真语料神签 img与div面板同锚行同底 预留区一行不动`() {
        val (erbc, game) = corpusOrNull() ?: return
        val chain = runChain(erbc, game)
        val metrics = CellMetrics(DEVICE_FONT_PX, DEVICE_LINE_PX)
        val imgDevicePx = metrics.scriptPixels(chain.imgScriptPx, SCRIPT_FONT_PX)
        assertShapePremises(chain, imgDevicePx)
        val merged = mergeAndAssertGates(chain, metrics, imgDevicePx)
        assertPanelBottomLine(chain, metrics, merged, imgDevicePx)
    }

    /** 形态前提：本锁站在「塌缩会盯上这一行」的形态上（img 设备高 > 真机档块高预算）。 */
    private fun assertShapePremises(chain: Chain, imgDevicePx: Int) {
        val anchorFlat = chain.flattened[chain.flattened.size - chain.reservedRows - 1]
        val imgRun = anchorFlat.runs.singleOrNull()?.graphic as? InlineGraphic.Image
        assertTrue(
            imgRun != null && imgRun.heightPx == imgDevicePx,
            "展平后锚行必须是全尺寸 img 段（heightPx=$imgDevicePx），实际 " +
                "${anchorFlat.runs.joinToString { "${it.kind}:${(it.graphic as? InlineGraphic.Image)?.heightPx}" }}",
        )
        assertTrue(
            imgDevicePx > BAND_MAX_HEIGHT_PX,
            "形态前提：img 设备高 $imgDevicePx 必须高过真机块预算 $BAND_MAX_HEIGHT_PX，" +
                "否则塌缩根本不盯上这行、锁变空转（语料/配置漂移时按漂移后形态修本前提）",
        )
    }

    /** 图块合并（真机档预算）⇒ 闸①行数保全、闸②img 全尺寸、闸③divBlocks 不漂、闸④预留区逐条存活；返回合并产物。 */
    private fun mergeAndAssertGates(
        chain: Chain,
        metrics: CellMetrics,
        imgDevicePx: Int,
    ): List<com.eratw.port.render.RenderLine> {
        val merged = ImageBandMerge.merge(
            lines = chain.flattened,
            lineHeightPx = DEVICE_LINE_PX,
            cellPixelWidth = metrics.cellPixelWidth,
            availWidthPx = AVAIL_WIDTH_PX,
            maxBlockHeightPx = BAND_MAX_HEIGHT_PX,
        )
        // 闸①（4e7fb6c4e 的面）：行数保全 —— 塌缩删行 / 合并折行都会让这里变短。
        assertEquals(
            chain.flattened.size,
            merged.size,
            "带 div 覆盖块的神签页必须一行不动（宁可漏合）：展平 ${chain.flattened.size} 行、" +
                "合并后 ${merged.size} 行",
        )
        // 闸②（1adf1e021 的面）：img 全尺寸 + 非塌缩产物。
        val mergedAnchor = merged[merged.size - chain.reservedRows - 1]
        val mergedImg = mergedAnchor.runs.singleOrNull()?.graphic as? InlineGraphic.Image
        assertTrue(
            mergedImg != null && mergedImg.heightPx == imgDevicePx && mergedImg.rowSpan == 0,
            "超高图塌缩不得缩带 div 覆盖块的 img：heightPx=${mergedImg?.heightPx}（期望 $imgDevicePx）" +
                "rowSpan=${mergedImg?.rowSpan}",
        )
        // 闸③：锚行与 divBlocks 的绑定不漂。
        assertTrue(
            mergedAnchor.divBlocks.isNotEmpty() &&
                mergedAnchor.divBlocks.single().heightPx == chain.imgScriptPx,
            "div 覆盖块必须原样留在锚行",
        )
        // 闸④（4e7fb6c4e 的面）：预留区逐条还在（只印 div 的空行不能被当「空白」删掉）。
        val mergedReserved = merged.drop(merged.size - chain.reservedRows)
        assertEquals(
            chain.reservedRows,
            mergedReserved.size,
            "脚本预留的 ${chain.reservedRows} 行必须逐条存活",
        )
        return merged
    }

    /** 玩家症状的直接判据：面板底 == img 底（盒底悬进底部文字区即错位）。 */
    private fun assertPanelBottomLine(
        chain: Chain,
        metrics: CellMetrics,
        merged: List<com.eratw.port.render.RenderLine>,
        imgDevicePx: Int,
    ) {
        // 锚行落在窗口顶（rowTop = 0）：窗口 34 行 × 行高 43 的真机档画布。
        val mergedAnchor = merged[merged.size - chain.reservedRows - 1]
        val rowCount = DEVICE_LINE_PX.let { 34 }
        val canvasHeightPx = rowCount * DEVICE_LINE_PX
        val planner = DivOverlayPlanner(
            metrics,
            SCRIPT_FONT_PX,
            HtmlRunFlatten(metrics, null, SCRIPT_FONT_PX),
            SCRIPT_FONT_PX,
        )
        val firstLineNo = 0L
        val frame = planner.planFrame(
            rows = listOf(
                DivOverlayRow(mergedAnchor.divBlocks, anchorTopPx = 0, lineNo = firstLineNo),
            ),
            canvasHeightPx = canvasHeightPx,
            visibleTopLine = firstLineNo,
            visibleBottomLine = firstLineNo + rowCount - 1,
        )
        val panel = (frame.behind + frame.front).single()
        assertEquals(0, panel.borderRectPx.y + panel.borderRectPx.height - imgDevicePx,
            "div 面板底必须与 img 底同线（面板高=scriptPx(IMG_H_PX)=$imgDevicePx）：面板底 " +
                "${panel.borderRectPx.y + panel.borderRectPx.height} vs img 底 $imgDevicePx —— " +
                "盒底悬进底部文字区就是玩家报的「幻存神签错位」")
        assertEquals(
            imgDevicePx,
            panel.borderRectPx.height,
            "面板高与 img 高必须同尺（不随塌缩变短）",
        )
        assertTrue(panel.children.isNotEmpty(), "面板子行必须进规划（诗文/运势的覆盖绘制面）")
        println(
            "PROBE omikuji imgScriptPx=${chain.imgScriptPx} imgDevicePx=$imgDevicePx " +
                "reserved=${chain.reservedRows} merged=${merged.size} " +
                "panel=${panel.borderRectPx} children=${panel.children.size}",
        )
    }
}
