package com.eratw.port.core.proc

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * 真机「启用幻想乡地图重修后选完居所 → GRAPH_DB_BFS_MAP 无限循环」的**引擎侧回归锁**。
 *
 * 0.2.44+20（`4c3d92131`）的现场：`SQL_CONNECT "GRAPH_DB_NAME", "Data Source=plugins/qol_data.db"`
 * 被旧 `SqlSession` 只当成内存库共享键 ⇒ 从未读随包 SQLite 文件 ⇒ `_meta` 恒空 ⇒
 * `GRAPH_DB_INIT` 每次都进 `GRAPH_DB_REBUILD_ALL` 全源 BFS。四张表 × 12 据点 × 100 源点
 * 的重建在真机上超过 `InfiniteLoopAlertTime`，玩家选「退出」后表现为
 * `StepLimitExceeded`（`LoopGuardCause.PlayerChoseExit`）。
 *
 * 引擎修复：连接串（`Data Source=plugins/qol_data.db`）经宿主 `SqlDriver` 打开真实 SQLite，
 * 随包库版本匹配时 `GRAPH_DB_INIT` 只执行建表/版本查询（桌面读数 11 步/1.6 s），不再重建。
 *
 * 本用例用真产物 + 真 4.981 游戏根，钉住「小步数完成」这条用户可感判据；缺
 * `ERATW_GAMEDIR` / `ERATW_ERBC_DIR` 或随包库时跳过（`realArtifacts` 家）。
 */
@Tag("realArtifacts")
class GraphDbInitRealCorpusTest {

    @Test
    fun `GRAPH_DB_INIT 命中随包 qol_data_db 时不跑全源 BFS`() {
        val gameRootEnv = envPath("ERATW_GAMEDIR")
        val erbcDirEnv = envPath("ERATW_ERBC_DIR")
        val presetDb = gameRootEnv?.resolve("plugins/qol_data.db")
        assumeTrue(
            gameRootEnv != null && erbcDirEnv != null && presetDb != null && Files.isRegularFile(presetDb),
            "缺 ERATW_GAMEDIR / ERATW_ERBC_DIR / plugins/qol_data.db，跳过",
        )
        val gameRoot = gameRootEnv!!
        val erbcDir = erbcDirEnv!!
        val programFile = erbcDir.resolve("program.erbc")
        assumeTrue(Files.isRegularFile(programFile), "缺 $programFile，跳过")

        val program = ProgramLoader.loadMapped(programFile) { }
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = 20_000_000L,
            randSeed = 20260226L,
        )
        try {
            session.runtime.callFunction("GRAPH_DB_INIT")
            val steps = executedSteps(session.runtime)
            if (steps > 10_000L) {
                fail(
                    "GRAPH_DB_INIT 仍走了全源 BFS 重建：steps=$steps（命中随包 qol_data.db 应为 11 步级）。" +
                        "检查宿主 SqlDriver 是否按 Data Source 打开真实 SQLite。",
                )
            }
            assertTrue(steps in 1..10_000L, "GRAPH_DB_INIT 步数异常：$steps")
        } finally {
            session.close()
        }
    }

    /**
     * 幻想乡地图重修「大地图开关」/「地图优化开关」/「返回」三处直接调 `GRAPH_DB_REBUILD_ALL`
     * （`幻想乡地图重修.ERB:500/:533/:543`），不等同冷启动的 `GRAPH_DB_INIT`。本用例先走
     * `QOL_DB_INIT` 建连，再直接重建，钉住「合法全源 BFS 会终止且步数有界」——防止引擎
     * 把这条重链误判成死循环，也防止重建本身退化成不懂终止的状态机。
     */
    @Test
    fun `GMF 开关切换触发的直接重建能终止且步数有界`() {
        val gameRootEnv = envPath("ERATW_GAMEDIR")
        val erbcDirEnv = envPath("ERATW_ERBC_DIR")
        assumeTrue(
            gameRootEnv != null && erbcDirEnv != null,
            "缺 ERATW_GAMEDIR / ERATW_ERBC_DIR，跳过",
        )
        val erbcDir = erbcDirEnv!!
        val programFile = erbcDir.resolve("program.erbc")
        assumeTrue(Files.isRegularFile(programFile), "缺 $programFile，跳过")

        val program = ProgramLoader.loadMapped(programFile) { }
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRootEnv!!,
            program = program,
            metadata = metadata,
            maxStepsPerRun = 20_000_000L,
            randSeed = 20260226L,
        )
        try {
            session.runtime.callFunction("QOL_DB_INIT")
            session.runtime.callFunction("GRAPH_DB_REBUILD_ALL")
            val steps = executedSteps(session.runtime)
            println("GRAPH_DB_REBUILD_ALL steps=$steps")
            assertTrue(steps in 1..20_000_000L, "直接重建应终止且落在步数闸内：steps=$steps")
        } finally {
            session.close()
        }
    }

    private fun envPath(name: String): Path? =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    /** 反射读 `ProcessRuntime.vm.executedSteps`（同 `RedKCharaMovementReproTest` 的装置口径）。 */
    private fun executedSteps(rt: ProcessRuntime): Long = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        val vm = f.get(rt) ?: return@runCatching 0L
        val getter = vm.javaClass.getMethod("getExecutedSteps")
        (getter.invoke(vm) as Number).toLong()
    }.getOrDefault(0L)
}
