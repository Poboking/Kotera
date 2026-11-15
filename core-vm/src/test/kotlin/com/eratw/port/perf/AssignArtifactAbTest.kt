package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.save.SaveCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * **通用同进程双产物交替 A/B**（最初为 PERF-ASSIGN，现也服务 PERF-LOOP）：
 * off/on 两套 `.erbc` 各建一局，逐轮交替调用 `GMF_METHOD_UPDATE`，比较**每次调用总墙钟**。
 * 逐对交替抵消 JIT/CPU 频率漂移；跨进程 median ns/instr 只作辅助。
 *
 * `ERATW_FLAT_OFF=1` 切对象树（Android 生产通道）；缺省 flat。
 *
 * 读数（2026-11-09，20 calls × 8 对）：
 * - PERF-ASSIGN `_EVAL_SET`：flat **0.899×**、object **0.986×** ⇒ 不赚，关闭。
 * - PERF-LOOP `_LT_JZ`：flat **1.073×**、object **1.033×** ⇒ 正收益候选，缺省关。
 *
 * 环境：`ERATW_GAMEDIR`、`ERATW_ERBC_DIR_OFF`、`ERATW_ERBC_DIR_ON`、`save03.sav`；
 * `ERATW_ASSIGN_AB_ROUNDS`（默认 6）、`ERATW_ASSIGN_AB_CALLS`（默认 20）。
 */
class AssignArtifactAbTest {

    private fun load(dir: Path): Program =
        ProgramLoader.loadMapped(
            dir.resolve("program.erbc"),
            flatStatements = System.getenv("ERATW_FLAT_OFF") != "1",
        ) { }

    private fun openSession(gameRoot: Path, dir: Path, save: Path): GameSession {
        val program = load(dir)
        val metadata = BootMetadata.read(dir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = 8_000_000L,
            randSeed = 20260226L,
        )
        val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
        data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
        return session
    }

    private fun round(session: GameSession, calls: Int): Long {
        val t0 = System.nanoTime()
        repeat(calls) {
            session.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L)))
        }
        return System.nanoTime() - t0
    }

    @Tag("realArtifacts")
    @Test
    fun `赋值融合产物同进程交替 A_B`() {
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val offDir = System.getenv("ERATW_ERBC_DIR_OFF")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val onDir = System.getenv("ERATW_ERBC_DIR_ON")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(
            gameRoot != null && offDir != null && onDir != null && Files.exists(save),
            "缺 ERATW_GAMEDIR / ERATW_ERBC_DIR_OFF / ERATW_ERBC_DIR_ON / save03.sav",
        )
        val rounds = System.getenv("ERATW_ASSIGN_AB_ROUNDS")?.toIntOrNull() ?: 6
        val calls = System.getenv("ERATW_ASSIGN_AB_CALLS")?.toIntOrNull() ?: 20
        val off = openSession(gameRoot!!, offDir!!, save)
        val on = openSession(gameRoot, onDir!!, save)
        try {
            repeat(3) {
                round(off, calls)
                round(on, calls)
            }
            val ratios = ArrayList<Double>(rounds)
            repeat(rounds) { i ->
                val firstOn = i % 2 == 0
                val a = if (firstOn) round(on, calls) else round(off, calls)
                val b = if (firstOn) round(off, calls) else round(on, calls)
                val nsOn = if (firstOn) a else b
                val nsOff = if (firstOn) b else a
                val ratio = nsOff.toDouble() / nsOn
                ratios += ratio
                println(
                    "ASSIGN_ARTIFACT_AB r$i offMs=${"%.3f".format(nsOff / 1_000_000.0)} " +
                        "onMs=${"%.3f".format(nsOn / 1_000_000.0)} off/on=${"%.3f".format(ratio)}×",
                )
            }
            val sorted = ratios.sorted()
            println(
                "ASSIGN_ARTIFACT_AB median=${"%.3f".format(sorted[sorted.size / 2])}× " +
                    "min=${"%.3f".format(sorted.first())}× max=${"%.3f".format(sorted.last())}× " +
                    "（>1 = 融合更快；calls=$calls rounds=$rounds）",
            )
        } finally {
            runCatching { off.close() }
            runCatching { on.close() }
        }
    }
}
