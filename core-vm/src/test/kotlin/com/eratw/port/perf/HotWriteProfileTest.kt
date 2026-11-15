package com.eratw.port.perf

import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.save.SaveCodec
import com.eratw.port.core.vars.VarWriteProbe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * 真热函数 `GMF_METHOD_UPDATE` 的**写变量名普查**：确认 2D 角色变量
 * （`CFLAG/TFLAG/...`）在真实调用里是不是主要写目标，而不是只看语料静态条数。
 *
 * `-Deratw.perf.writeProfile=true` + `ERATW_ERBC_DIR` 才跑；默认跳过。
 */
class HotWriteProfileTest {

    @Tag("realArtifacts")
    @Test
    fun `热函数写变量名普查`() {
        assumeTrue(
            java.lang.Boolean.getBoolean("eratw.perf.writeProfile"),
            "设置 -Deratw.perf.writeProfile=true 才跑",
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
            val counts = HashMap<String, Int>()
            session.store.writeProbe = object : VarWriteProbe {
                override fun watches(name: String): Boolean = true
                override fun onWriteInt(name: String, indices: LongArray, value: Long) {
                    counts.merge(name.uppercase(), 1, Int::plus)
                }
                override fun onWriteStr(name: String, indices: LongArray, value: String) {
                    counts.merge(name.uppercase(), 1, Int::plus)
                }
                override fun onWriteFloat(name: String, indices: LongArray, value: Double) {
                    counts.merge(name.uppercase(), 1, Int::plus)
                }
            }
            repeat(20) {
                session.runtime.invokeUserMethod("GMF_METHOD_UPDATE", listOf(Value.IntV(1L)))
            }
            val total = counts.values.sum()
            println("HOT_WRITE total=$total distinct=${counts.size}")
            counts.entries.sortedByDescending { it.value }.take(25).forEach { (n, c) ->
                println("HOT_WRITE ${"%.1f".format(100.0 * c / total)}% ($c) $n")
            }
        } finally {
            runCatching { session.close() }
        }
    }
}
