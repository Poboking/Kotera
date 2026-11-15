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
 * MAPID（`GET_MAPID` 形状内联）同进程双产物 A/B + 逐函数步数归因。
 *
 * - off 产物 = `ERATW_ERBC_DIR_OFF`（默认 `.tmp/erbc-mapid-off/erbc.v1`，E1 默认开、MAPID 关）
 * - on  产物 = `ERATW_ERBC_DIR_ON`（默认 `.tmp/erbc-mapid-on/erbc.v1`，MAPID 开）
 * - 20 次预热 + 10 对 × 10 次 `GMF_METHOD_UPDATE`（奇偶翻转）
 * - 归因：一次调用内 `name -> [calls, rootSteps]`，逐函数 `off-on`，断言总差 = 各函数归因之和
 */
class GetMapIdAbTest {

    @Tag("realArtifacts")
    @Test
    fun `MAPID GET_MAPID 形状内联 双产物 A-B 与逐函数归因`() {
        assumeTrue(java.lang.Boolean.getBoolean("eratw.perf.mapidAb"), "设置 -Deratw.perf.mapidAb=true 才跑")
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val offDir = System.getenv("ERATW_ERBC_DIR_OFF")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-mapid-off", "erbc.v1")
        val onDir = System.getenv("ERATW_ERBC_DIR_ON")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-mapid-on", "erbc.v1")
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
            assertEquals(digest(off), digest(on), "预热后状态摘要不等 ⇒ MAPID 内联语义不对")

            // 归因：单次调用，验证 sum(rootSteps) == lastTotalNestedSteps（账闭合）。
            fun attribution(s: GameSession): Pair<Map<String, LongArray>, Long> {
                s.runtime.resetStepAttribution()
                s.runtime.stepAttributionEnabled = true
                try { one(s) } finally { s.runtime.stepAttributionEnabled = false }
                val snap = s.runtime.stepAttributionSnapshot()
                val sum = snap.values.sumOf { it[1] }
                return snap to sum
            }
            val (offAttr, offSum) = attribution(off)
            val (onAttr, onSum) = attribution(on)
            assertEquals(off.runtime.lastTotalNestedStepsForTest(), offSum, "off 归因和 != lastTotalNestedSteps")
            assertEquals(on.runtime.lastTotalNestedStepsForTest(), onSum, "on 归因和 != lastTotalNestedSteps")

            val offTotal = offSum
            val onTotal = onSum
            val names = (offAttr.keys + onAttr.keys).sorted()
            val deltas = names.associateWith { n ->
                (offAttr[n]?.get(1) ?: 0L) - (onAttr[n]?.get(1) ?: 0L)
            }
            assertEquals(offTotal - onTotal, deltas.values.sum(), "总差 != 各函数归因之和")

            val offG = offAttr["GET_MAPID"] ?: LongArray(2)
            val onG = onAttr["GET_MAPID"] ?: LongArray(2)
            val offCalls = offG[0]
            val offSteps = offG[1]
            val onCalls = onG[0]
            val onSteps = onG[1]
            val perCall = if (offCalls > 0) offSteps.toDouble() / offCalls else Double.NaN
            val extrapolated = perCall * 787_474.0

            // 时间 A/B（10 对 × 10 次）
            fun batch(s: GameSession): Long {
                val t = System.nanoTime()
                repeat(10) { one(s) }
                return (System.nanoTime() - t) / 10
            }
            val offSamples = ArrayList<Long>()
            val onSamples = ArrayList<Long>()
            val ratios = ArrayList<Double>()
            repeat(10) { pair ->
                val a: Long
                val b: Long
                if (pair % 2 == 0) { a = batch(off); b = batch(on) } else { b = batch(on); a = batch(off) }
                offSamples.add(a); onSamples.add(b); ratios.add(a.toDouble() / b)
                assertEquals(digest(off), digest(on), "第 $pair 对状态摘要不等")
            }
            val sorted = ratios.sorted()
            val median = sorted[sorted.size / 2]
            fun fmt(v: Double) = "%.3f".format(v)
            fun fmtMs(v: Long) = "%.3f".format(v / 1e6)
            val lines = ArrayList<String>()
            fun out(s: String) { lines.add(s); println(s) }
            out("MAPID perCall GET_MAPID calls=$offCalls steps=$offSteps perCall=${fmt(perCall)}（on calls=$onCalls steps=$onSteps）")
            out("MAPID 外推 787474 calls ≈ ${"%.0f".format(extrapolated)} steps（目标 0.79M–1.57M）")
            out("MAPID total off=$offTotal on=$onTotal delta=${offTotal - onTotal}")
            out("MAPID 逐函数归因（off-on，负=省）:")
            deltas.entries.sortedBy { it.value }.forEach { (n, d) -> out("  $n: $d") }
            out("MAPID ratios(off/on, >1=内联快)=" + ratios.joinToString(",") { fmt(it) })
            out("MAPID ratio median=${fmt(median)} offMedianMs=${fmtMs(offSamples.sorted()[offSamples.size / 2])} onMedianMs=${fmtMs(onSamples.sorted()[onSamples.size / 2])}")
            val report = Path.of("..", "..", ".tmp", "mapid-ab.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, lines.joinToString(System.lineSeparator()) + System.lineSeparator())
            println("MAPID report=${report.toAbsolutePath()}")
            assertTrue(offCalls > 0 && onCalls == 0L, "GET_MAPID 未在 off 被调用 / on 未内联（off=$offCalls on=$onCalls）")
            assertTrue(extrapolated in 790_000.0..1_580_000.0, "外推 ${"%.0f".format(extrapolated)} 不在 0.79M–1.57M")
        } finally {
            runCatching { off.close() }
            runCatching { on.close() }
        }
    }
}
