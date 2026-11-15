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
import kotlin.test.assertNotEquals

/**
 * E1 反向证明：`-Deratw.perf.inlineChkDatenowAny=true` 把**任意**单参 int 调用按 CHK 形状内联，
 * 状态摘要必须与关态产物**不同** —— 证明金标/状态摘要有判定力，不是「怎么改都绿」。
 */
class InlineChkDatenowReverseTest {

    @Tag("realArtifacts")
    @Test
    fun `E1 any-flag 必须让状态摘要红`() {
        assumeTrue(java.lang.Boolean.getBoolean("eratw.perf.e1Reverse"), "设置 -Deratw.perf.e1Reverse=true 才跑")
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val offDir = System.getenv("ERATW_ERBC_DIR_OFF")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-e0", "erbc.v1")
        val anyDir = System.getenv("ERATW_ERBC_DIR_ANY")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("..", "..", ".tmp", "erbc-e1any", "erbc.v1")
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && Files.exists(save), "缺环境或存档")
        assumeTrue(Files.exists(offDir.resolve("program.erbc")), "缺 off 产物: $offDir")
        assumeTrue(Files.exists(anyDir.resolve("program.erbc")), "缺 any 产物: $anyDir")

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
        val any = newSession(anyDir)
        try {
            fun one(s: GameSession) = s.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L)))
            repeat(5) { one(off) }
            repeat(5) { one(any) }
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
            val offD = digest(off)
            val anyD = digest(any)
            println("E1R offDigest=$offD anyDigest=$anyD offSteps=${off.runtime.lastTotalNestedStepsForTest()} anySteps=${any.runtime.lastTotalNestedStepsForTest()}")
            assertNotEquals(offD, anyD, "any-flag 未改变状态摘要 ⇒ 反向证明失败（门无判定力）")
        } finally {
            runCatching { off.close() }
            runCatching { any.close() }
        }
    }
}
