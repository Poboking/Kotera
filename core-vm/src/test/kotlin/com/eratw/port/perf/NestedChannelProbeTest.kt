package com.eratw.port.perf

import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.NestedChannelProbe
import com.eratw.port.core.save.SaveCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * TEMP(perf-probe) E0：嵌套 VM 通道的墙钟探针。
 *
 * 口径：`GMF_METHOD_UPDATE` ×N（save03，flatStatements=true，与 PERF-PROF 2D 同装法）。
 * 先 20 次预热，再 **交错 A/B**：每轮 baseline（探针 null）与 probe（探针非 null）各 10 次，
 * 奇偶轮翻转顺序，共 4 轮 ⇒ 8 个样本/侧，报 median。这样 JIT/状态漂移不会把「后跑的一侧」
 * 系统性地算快/算慢。探针逐段 nanoTime 自身开销用同 JVM 的 12 戳空转标定并给「已校正」列。
 */
class NestedChannelProbeTest {

    @Tag("realArtifacts")
    @Test
    fun `E0 嵌套通道墙钟`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("eratw.perf.nestedProbe"),
            "设置 -Deratw.perf.nestedProbe=true 才跑",
        )
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境或存档")

        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc"), flatStatements = true) { }
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot!!,
            program = program,
            metadata = metadata,
            maxStepsPerRun = 8_000_000L,
            randSeed = 20260226L,
        )
        try {
            val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
            data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)

            // 20 次预热：让 JIT 与变量池进入稳态，之后的 baseline/probe 交错才有可比性。
            repeat(20) { session.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L))) }

            val acc = HashMap<String, Acc>()
            val probe = NestedChannelProbe { name, argNs, refNs, toValueNs, scratchNs, totalNs, runNs, resetNs, enterNs, exitNs, retNs ->
                acc.getOrPut(name) { Acc() }.add(argNs, refNs, toValueNs, scratchNs, totalNs, runNs, resetNs, enterNs, exitNs, retNs)
            }
            val baselineSamples = ArrayList<Long>()
            val probeSamples = ArrayList<Long>()
            fun oneUpdate() = session.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L)))
            fun baselineBatch() {
                val t = System.nanoTime()
                repeat(10) { oneUpdate() }
                baselineSamples.add((System.nanoTime() - t) / 10)
            }
            fun probeBatch() {
                session.runtime.nestedChannelProbe = probe
                val t = System.nanoTime()
                repeat(10) { oneUpdate() }
                probeSamples.add((System.nanoTime() - t) / 10)
                session.runtime.nestedChannelProbe = null
            }
            repeat(4) { round ->
                if (round % 2 == 0) { baselineBatch(); probeBatch() } else { probeBatch(); baselineBatch() }
            }
            val baselineNs = baselineSamples.sorted()[baselineSamples.size / 2]
            val probeNs = probeSamples.sorted()[probeSamples.size / 2]

            // nanoTime 自身开销标定（本测试每次 CHK 调用 14 个时间戳）。
            var sink = 0L
            val calN = 200_000
            val calT0 = System.nanoTime()
            repeat(calN) {
                repeat(14) { sink += System.nanoTime() }
            }
            val calNs = System.nanoTime() - calT0
            val perTsNs = calNs.toDouble() / (calN * 14)

            val chk = acc["CHK_DATENOW"] ?: error("没有采到 CHK_DATENOW 调用")
            fun fmt(v: Double) = "%.1f".format(v)
            fun fmtMs(v: Double) = "%.3f".format(v)
            val perUpdate = 40.0
            val fullPerUpdate = chk.totalSum / perUpdate
            val channelLowerPerUpdate = (chk.argSum + chk.totalSum - chk.runSum + chk.resetSum + chk.enterSum + chk.exitSum + chk.retSum) / perUpdate
            val overheadPerCall = 14 * perTsNs
            val fullPerUpdateCorrected = fullPerUpdate - chk.count * overheadPerCall / perUpdate
            val channelLowerPerUpdateCorrected = channelLowerPerUpdate - chk.count * 14 * perTsNs / perUpdate
            val baselineUpdateNs = baselineNs.toDouble()
            val channelUpperPct = 100.0 * fullPerUpdateCorrected / baselineUpdateNs
            val channelLowerPct = 100.0 * channelLowerPerUpdateCorrected / baselineUpdateNs

            val lines = ArrayList<String>()
            fun out(s: String) { lines.add(s); println(s) }
            out("E0 baselineUpdateMs=${fmtMs(baselineNs / 1e6)} probeUpdateMs=${fmtMs(probeNs / 1e6)}")
            out("E0 baselineSamplesMs=" + baselineSamples.joinToString(",") { fmtMs(it / 1e6) })
            out("E0 probeSamplesMs=" + probeSamples.joinToString(",") { fmtMs(it / 1e6) })
            out("E0 calls total=${acc.values.sumOf { it.count }} perUpdate=${fmt(acc.values.sumOf { it.count } / perUpdate)} chk=${chk.count} chkPerUpdate=${fmt(chk.count / perUpdate)}")
            out("E0 perTsNs=${fmt(perTsNs)} overheadPerCallNs=${fmt(overheadPerCall)}")
            out("E0 CHK perCallNs arg=${fmt(chk.argSum.toDouble() / chk.count)} ref=${fmt(chk.refSum.toDouble() / chk.count)} toValue=${fmt(chk.toValueSum.toDouble() / chk.count)} scratch=${fmt(chk.scratchSum.toDouble() / chk.count)} total=${fmt(chk.totalSum.toDouble() / chk.count)} run=${fmt(chk.runSum.toDouble() / chk.count)} reset=${fmt(chk.resetSum.toDouble() / chk.count)} enter=${fmt(chk.enterSum.toDouble() / chk.count)} exit=${fmt(chk.exitSum.toDouble() / chk.count)} ret=${fmt(chk.retSum.toDouble() / chk.count)}")
            out("E0 CHK perCallNs prepBind=${fmt((chk.totalSum - chk.runSum - chk.retSum).toDouble() / chk.count)} frameBody=${fmt((chk.runSum - chk.resetSum - chk.enterSum - chk.exitSum).toDouble() / chk.count)}")
            out("E0 CHK perUpdateNs full=${fmt(fullPerUpdate)} fullCorrected=${fmt(fullPerUpdateCorrected)} channelLower=${fmt(channelLowerPerUpdate)} channelLowerCorrected=${fmt(channelLowerPerUpdateCorrected)}")
            out("E0 channel% upper=${fmt(channelUpperPct)}% lower=${fmt(channelLowerPct)}%")
            out("E0 resetPerCallNs=${fmt(chk.resetSum.toDouble() / chk.count)} resetPerUpdateNs=${fmt(chk.resetSum / perUpdate)} enterPerUpdateNs=${fmt(chk.enterSum / perUpdate)} exitPerUpdateNs=${fmt(chk.exitSum / perUpdate)}")

            val report = Path.of("..", "..", ".tmp", "e0-nested-probe.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, lines.joinToString(System.lineSeparator()) + System.lineSeparator())
            println("E0 report=${report.toAbsolutePath()}")
            check(sink != 0L)
        } finally {
            runCatching { session.runtime.nestedChannelProbe = null }
            runCatching { session.close() }
        }
    }

    private class Acc {
        var count = 0L
        var argSum = 0L
        var refSum = 0L
        var toValueSum = 0L
        var scratchSum = 0L
        var totalSum = 0L
        var runSum = 0L
        var resetSum = 0L
        var enterSum = 0L
        var exitSum = 0L
        var retSum = 0L
        fun add(arg: Long, ref: Long, toValue: Long, scratch: Long, total: Long, run: Long, reset: Long, enter: Long, exit: Long, ret: Long) {
            count++
            argSum += arg; refSum += ref; toValueSum += toValue; scratchSum += scratch
            totalSum += total; runSum += run; resetSum += reset
            enterSum += enter; exitSum += exit; retSum += ret
        }
    }
}
