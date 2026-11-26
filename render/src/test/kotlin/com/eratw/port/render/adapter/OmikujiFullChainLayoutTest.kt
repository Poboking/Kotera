package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * QB-A12 探针：真语料驱动「幻存神签」打印，dump 渲染链上游形态。
 */
class OmikujiFullChainLayoutTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun corpusOrNull(): Pair<Path, Path>? {
        val erbcDir = env("ERATW_ERBC_DIR")
        val gameRoot = env("ERATW_GAMEDIR")
        if (erbcDir == null || gameRoot == null) return null
        val erbc = Path.of(erbcDir)
        val usable = Files.isDirectory(erbc) && Files.isRegularFile(erbc.resolve("program.erbc"))
        return if (usable) erbc to Path.of(gameRoot) else null
    }

    @Test
    fun `probe 神签打印真语料驱动 dump`() {
        val (erbc, game) = corpusOrNull() ?: return
        val program = ProgramLoader.loadMapped(erbc.resolve("program.erbc"))
        val metadata = BootMetadata.read(erbc.resolve("program.meta"))
        val session = GameSession(
            gameRoot = game,
            program = program,
            metadata = metadata,
            randSeed = 20260226L,
            frameGateEnabled = true,
        )
        val key = metadata.functions.firstOrNull { it.key == "神签打印" }?.key
            ?: error("神签打印 不在产物里")
        println("PROBE 神签打印 key=$key")
        // 神签TOID:1 = 12（神签.ERH）：QOL_KOJO_COLOR 是角色变量、按**登记序**寻址
        // （VariableStore.requireSparseCharaRow 的 pos < characters.count）——
        // eraTW 开局全量 ADDCHARA 后 登记位 == NO，这里按 NO 升序补齐 0..12 同形。
        for (no in 0L..12L) {
            if (session.store.template(no) != null) session.store.addCharacter(no)
        }
        println("PROBE charaCount=${session.store.characters.count}")
        session.runtime.callFunction(key, longArrayOf(1L), arrayOf())
        val sprite = session.store.aux.peek<SpritePool>(SPRITE_SLOT)?.find("神签_1")
        println("PROBE sprite 神签_1 = ${sprite?.width}x${sprite?.height} created=${sprite?.isCreated}")
        val snap = session.console.snapshot()
        println("PROBE lines=${snap.size}")
        for ((i, dl) in snap.withIndex()) {
            val segs = dl.segments.joinToString("/") { "k=${it.kind}:«${it.text.take(60)}»" }
            val divs = dl.divBlocks.joinToString("/") {
                "w=${it.widthPx} h=${it.heightPx} sdw=${it.subDivisionWidthPx} " +
                    "rel=${it.isRelative} esc=${it.isEscaped} x=${it.xOffsetPx} y=${it.pointYPx} " +
                    "children=${it.children.size} src=${it.sourceRange}"
            }
            println(
                "PROBE [$i] log=${dl.isLogicalLine} end=${dl.isLineEnd} " +
                    "align=${dl.alignment} divs=${dl.divBlocks.size} $divs segs=$segs",
            )
        }
    }
}
