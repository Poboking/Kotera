package com.eratw.port.perf

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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E1（定向 `CHK_DATENOW` 内联）同进程双产物 A/B。
 *
 * - off 产物 = `ERATW_ERBC_DIR_OFF`（默认 `.tmp/erbc-e0/erbc.v1`，E1 前）
 * - on  产物 = `ERATW_ERBC_DIR_ON`（默认 `.tmp/erbc-e1on/erbc.v1`，E1 后）
 * - 20 次预热 + 10 对 × 10 次 UPDATE（奇偶翻转）；逐对报 off/on（>1 = 内联更快）。
 * - 步数硬断言：`offSteps - onSteps == 501`（rootSteps 3403 → 2902）。
 * - 状态摘要逐对相等（MAIN_MAP/MAXROOM/ENTRANCE/MAP_PAGES + 167 角色 × 2 CFLAG 列）。
 */
class InlineChkDatenowAbTest {

    @Tag("realArtifacts")
    @Test
    fun `E1 CHK_DATENOW 内联 双产物 A-B`() {
        assumeTrue(java.lang.Boolean.getBoolean("eratw.perf.e1Ab"), "设置 -Deratw.perf.e1Ab=true 才跑")
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val offDir = System.getenv("ERATW_ERBC_DIR_OFF")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-e0", "erbc.v1")
        val onDir = System.getenv("ERATW_ERBC_DIR_ON")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-e1on", "erbc.v1")
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && Files.exists(save), "缺环境或存档")
        assumeTrue(Files.exists(offDir.resolve("program.erbc")), "缺 off 产物: $offDir")
        assumeTrue(Files.exists(onDir.resolve("program.erbc")), "缺 on 产物: $onDir")

        fun newSession(dir: Path): GameSession {
            val program = ProgramLoader.loadMapped(dir.resolve("program.erbc"), flatStatements = true) { }
            val metadata = BootMetadata.read(dir.resolve("program.meta"))
            val s = GameSession(
                gameRoot = gameRoot!!,
                program = program,
                metadata = metadata,
                maxStepsPerRun = 8_000_000L,
                randSeed = 20260226L,
            )
            val data = SaveCodec.read(Files.readAllBytes(save), s.store.registry, SaveFileKind.NORMAL)
            data.applyTo(s.store, restoreCharacters = true, setLastLoad = true)
            return s
        }
        val off = newSession(offDir)
        val on = newSession(onDir)
        try {
            fun one(s: GameSession) = s.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L)))
            repeat(20) { one(off); one(on) }

            fun digest(s: GameSession): Long {
                var h = -3750763034362895579L
                fun mix(v: Long) { h = (h xor v) * 1099511628211L }
                mix(s.store.readInt("MAIN_MAP", longArrayOf(0)))
                mix(s.store.readInt("MAXROOM", longArrayOf(0)))
                mix(s.store.readInt("ENTRANCE", longArrayOf(0)))
                mix(s.store.readInt("MAP_PAGES", longArrayOf(0)))
                val kw1 = s.store.keywordToInteger("CFLAG", 1, "GMF_约会中同行转换")
                val kw2 = s.store.keywordToInteger("CFLAG", 1, "约会中")
                val n = s.store.readInt("CHARANUM", LongArray(0)).toInt()
                for (c in 1 until n) {
                    mix(s.store.readInt("CFLAG", longArrayOf(c.toLong(), kw1)))
                    mix(s.store.readInt("CFLAG", longArrayOf(c.toLong(), kw2)))
                }
                return h
            }

            fun batch(s: GameSession): Long {
                val t = System.nanoTime()
                repeat(10) { one(s) }
                return (System.nanoTime() - t) / 10
            }
            val offSamples = ArrayList<Long>()
            val onSamples = ArrayList<Long>()
            val ratios = ArrayList<Double>()
            var offSteps = 0L
            var onSteps = 0L
            repeat(10) { pair ->
                val a: Long
                val b: Long
                if (pair % 2 == 0) { a = batch(off); b = batch(on) } else { b = batch(on); a = batch(off) }
                offSamples.add(a); onSamples.add(b); ratios.add(a.toDouble() / b)
                offSteps = off.runtime.lastTotalNestedStepsForTest()
                onSteps = on.runtime.lastTotalNestedStepsForTest()
                assertEquals(501L, offSteps - onSteps, "第 $pair 对 executedSteps 差 != 501（off=$offSteps on=$onSteps）")
                assertEquals(digest(off), digest(on), "第 $pair 对状态摘要不等")
            }
            val sorted = ratios.sorted()
            val median = sorted[sorted.size / 2]
            val min = sorted.first()
            val max = sorted.last()
            fun fmt(v: Double) = "%.3f".format(v)
            fun fmtMs(v: Long) = "%.3f".format(v / 1e6)
            val lines = ArrayList<String>()
            fun out(s: String) { lines.add(s); println(s) }
            out("E1 steps off=$offSteps on=$onSteps diff=${offSteps - onSteps}（应 501）")
            out("E1 offSamplesMs=" + offSamples.joinToString(",") { fmtMs(it) })
            out("E1 onSamplesMs=" + onSamples.joinToString(",") { fmtMs(it) })
            out("E1 ratios(off/on, >1=内联快)=" + ratios.joinToString(",") { fmt(it) })
            out("E1 ratio median=${fmt(median)} min=${fmt(min)} max=${fmt(max)}")
            out("E1 offMedianMs=${fmtMs(offSamples.sorted()[offSamples.size / 2])} onMedianMs=${fmtMs(onSamples.sorted()[onSamples.size / 2])}")
            val report = Path.of("..", "..", ".tmp", "e1-inline-ab.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, lines.joinToString(System.lineSeparator()) + System.lineSeparator())
            println("E1 report=${report.toAbsolutePath()}")
            assertTrue(median >= 1.03, "E1 median ${fmt(median)} < 1.03，判死")
        } finally {
            runCatching { off.close() }
            runCatching { on.close() }
        }
    }
}
