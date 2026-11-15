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
 * E1c：REF 形参判定复用 `ParamPlan.isRef` 缓存 —— 等价性 + 同进程 A/B。
 *
 * - 等价：`-Deratw.perf.checkParamPlanRef=true` 时每个实参同时算缓存值与 `refUserParamAt` 现算值，
 *   累计 `refDetectionChecks/Mismatches`；本测跑 2×10 次 UPDATE（167 实参/次）⇒ ≥3000 次对拍、0 不一致。
 * - A/B：flag off（`useParamPlanRef=false`）vs on（true），10 对 × 10 次 UPDATE，奇偶轮翻转顺序；
 *   报逐对 `offMs/onMs`（>1 = 缓存版更快）+ median/min/max。
 * - 零语义：每批断言 `lastNestedStepsForTest()` 相等（3906/UPDATE），并比对 MAIN_MAP/CFLAG 状态摘要。
 */
class ParamPlanRefAbTest {

    @Tag("realArtifacts")
    @Test
    fun `E1c REF 判定缓存 等价与 A-B`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("eratw.perf.e1cAb"),
            "设置 -Deratw.perf.e1cAb=true 才跑",
        )
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境或存档")

        val metadata = BootMetadata.read(erbcDir!!.resolve("program.meta"))
        fun newSession(): GameSession {
            val program = ProgramLoader.loadMapped(erbcDir.resolve("program.erbc"), flatStatements = true) { }
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
        val off = newSession()
        val on = newSession()
        try {
            off.runtime.useParamPlanRef = false
            on.runtime.useParamPlanRef = true
            off.runtime.checkParamPlanRef = false
            on.runtime.checkParamPlanRef = false

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

            // ---- 1. 等价性：同一次调用里缓存值 vs 现算值 ----
            on.runtime.checkParamPlanRef = true
            on.runtime.resetRefDetectionCounters()
            repeat(10) { one(on) }
            val checksOn = on.runtime.refDetectionChecks
            val mismOn = on.runtime.refDetectionMismatches
            off.runtime.checkParamPlanRef = true
            off.runtime.resetRefDetectionCounters()
            repeat(10) { one(off) }
            val checksOff = off.runtime.refDetectionChecks
            val mismOff = off.runtime.refDetectionMismatches
            off.runtime.checkParamPlanRef = false
            on.runtime.checkParamPlanRef = false
            assertTrue(checksOn + checksOff >= 3000, "对拍次数不足: ${checksOn + checksOff}")
            assertEquals(0L, mismOn + mismOff, "isRef 缓存与现算不一致")

            // ---- 2. 同进程交替 A/B ----
            fun batch(s: GameSession): Long {
                val t = System.nanoTime()
                repeat(10) { one(s) }
                return (System.nanoTime() - t) / 10
            }
            val offSamples = ArrayList<Long>()
            val onSamples = ArrayList<Long>()
            val ratios = ArrayList<Double>()
            var stepsOff = 0L
            var stepsOn = 0L
            repeat(10) { pair ->
                val a: Long
                val b: Long
                if (pair % 2 == 0) { a = batch(off); b = batch(on) } else { b = batch(on); a = batch(off) }
                offSamples.add(a); onSamples.add(b); ratios.add(a.toDouble() / b)
                stepsOff = off.runtime.lastNestedStepsForTest()
                stepsOn = on.runtime.lastNestedStepsForTest()
                assertEquals(stepsOff, stepsOn, "第 $pair 对 executedSteps 不等")
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
            out("E1c checks=${checksOn + checksOff} mismatches=${mismOn + mismOff}")
            out("E1c stepsPerUpdate=$stepsOff（两侧相等）")
            out("E1c offSamplesMs=" + offSamples.joinToString(",") { fmtMs(it) })
            out("E1c onSamplesMs=" + onSamples.joinToString(",") { fmtMs(it) })
            out("E1c ratios(off/on, >1=缓存快)=" + ratios.joinToString(",") { fmt(it) })
            out("E1c ratio median=${fmt(median)} min=${fmt(min)} max=${fmt(max)}")
            out("E1c offMedianMs=${fmtMs(offSamples.sorted()[offSamples.size / 2])} onMedianMs=${fmtMs(onSamples.sorted()[onSamples.size / 2])}")
            val report = Path.of("..", "..", ".tmp", "e1c-paramplan-ref.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, lines.joinToString(System.lineSeparator()) + System.lineSeparator())
            println("E1c report=${report.toAbsolutePath()}")
        } finally {
            runCatching { off.close() }
            runCatching { on.close() }
        }
    }
}
