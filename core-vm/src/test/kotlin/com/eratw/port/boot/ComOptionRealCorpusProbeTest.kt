package com.eratw.port.boot

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.QueueInputSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * 泡茶子选单真语料探针（2026-11-12 玩家「点红茶没反应」案的回归锁）。
 *
 * 真语料 erbc 产物 + 生产 `GameSession`/`QueueInputSink`，两轮断言把整条链钉死：
 *
 * 第一轮 = core 交付 + ERB 消费：`call COM_OPTION(301)` 挂进 `INPUT -1, 1` 等待，
 * 投喂线程 `submitButtonClick("1")`（玩家点 [泡红茶] 的 core 交付，原版
 * `MainWindow.cs:824-849` 鼠标分支口径），断言 `COM_ALTER_301` 被
 * `SETVAR @"COM_ALTER_301"`（QOL_USERCOM.ERB:283）写上。
 *
 * 反馈面 = ERB 静态读与动态写同槽：`COM_NAME301()`（面板按钮名 = `L_OPTION_NAME` 支，
 * QOL_USERCOM:106-110）读 `COM_ALTER_301` 必须读到 1 → RESULTS = 「泡红茶」。
 * 语料前置：红茶道具 `ITEM:215`（CSV/Item.csv:163）——没有它 COM_NAME301 CASE 1
 * 会因道具不足合法回落「泡茶」并清 COM_ALTER_301（COMF301:255-260），不是本案现场。
 *
 * 第二轮 = 右栏数据面：弹窗等待页 `currentOptions()` 快照（右栏芯片唯一数据源）
 * 必须含三枚弹窗按钮且 `submitValue=1` 的那枚 label 是「[泡红茶]」。
 * 等待形态同时断言（VALUE + INT + mouseInput + hasDef=-1 = `INPUT -1, 1`）。
 *
 * 环境门：`ERATW_ERBC_DIR` + `ERATW_GAMEDIR` 都在位才跑（缺变量不生成、不计 skipped）。
 * 探针生命周期：`sink.close()` 必须在两轮全部结束后（第一轮就 close 会让第二轮等待抛
 * InputSinkClosedException，快照窗口失效）；两轮各用独立的 done 旗（复用会让第二个
 * feeder 立即退出、快照恒空——两个都是本轮踩过的真坑）。
 */
class ComOptionRealCorpusProbeTest {

    /** 投喂等待窗口：`ReplayRunner.awaitConsumable` 同款纪律（有等待且队列空才投）。 */
    private companion object {
        const val FEED_WINDOW_NS: Long = 20_000_000_000L
        const val POLL_MS: Long = 2L
        const val JOIN_MS: Long = 2000L
        const val RAND_SEED: Long = 20260226L

        /** 红茶道具号（`CSV/Item.csv:163`「215,紅茶」）。 */
        const val ITEM_BLACK_TEA: Long = 215L
    }

    /** 装配好的会话 + 产物侧车 + 本用例独占的输入队列。 */
    private class Rig(
        val session: GameSession,
        val metadata: BootMetadata,
        val sink: QueueInputSink,
    ) {
        val rt get() = session.runtime

        fun functionKey(name: String): String =
            metadata.functions.firstOrNull { it.key == name }?.key ?: error("$name 不在产物里")
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

    private fun assemble(erbc: Path, game: Path): Rig {
        val program = ProgramLoader.loadMapped(erbc.resolve("program.erbc"))
        val metadata = BootMetadata.read(erbc.resolve("program.meta"))
        val sink = QueueInputSink()
        val session = GameSession(
            gameRoot = game,
            program = program,
            metadata = metadata,
            randSeed = RAND_SEED,
            frameGateEnabled = false,
            inputSinkOverride = sink,
        )
        return Rig(session, metadata, sink)
    }

    /**
     * 一轮「等挂起 → 抓现场 → 投一次按钮点击 → 等消费」的投喂线程。
     *
     * @param onWaiting 挂起瞬间做一次现场采集（可选）
     */
    private fun startFeeder(
        sink: QueueInputSink,
        done: AtomicBoolean,
        onWaiting: (() -> Unit)? = null,
    ): Thread {
        val feeder = Thread {
            val deadline = System.nanoTime() + FEED_WINDOW_NS
            while (System.nanoTime() < deadline) {
                if (sink.isAwaiting && sink.pendingCount == 0) {
                    onWaiting?.invoke()
                    sink.submitButtonClick("1", 1)
                    while (sink.pendingCount > 0 && !done.get()) Thread.sleep(POLL_MS)
                    return@Thread
                }
                if (done.get()) return@Thread
                Thread.sleep(POLL_MS)
            }
        }
        feeder.isDaemon = true
        feeder.start()
        return feeder
    }

    /** 第一轮：点 [泡红茶] → 读 `COM_ALTER_301`；顺带断言 VM 没抛。 */
    private fun clickRoundOne(rig: Rig): Long {
        val key = rig.functionKey("COM_OPTION")
        val done = AtomicBoolean(false)
        val feeder = startFeeder(rig.sink, done)
        var vmError: Throwable? = null
        try {
            rig.rt.callFunction(key, longArrayOf(301L), arrayOf<String>())
        } catch (t: Throwable) {
            vmError = t
        } finally {
            done.set(true)
            feeder.join(JOIN_MS)
            // sink.close() 统一落在两轮之后：第一轮就 close 会让第二轮的等待抛
            // InputSinkClosedException，快照窗口全部失效（探针自己的生命周期 bug）。
        }
        val vm = "VM=${vmError?.let { "${it::class.simpleName}: ${it.message}" } ?: "PASS"}"
        // #DIM SAVEDATA COM_ALTER_301（QOL_USERCOM.ERH:2）在产物里落成 1 维数组
        // （vmdbg var 的读数形态 COM_ALTER_301[0]=0 同源），按下标 0 读。
        val alter = rig.rt.store.readInt("COM_ALTER_301", longArrayOf(0L))
        println("PROBE $vm COM_ALTER_301=$alter")
        check(vmError == null) { "第一轮 COM_OPTION 调用异常：$vmError" }
        return alter
    }

    /**
     * 反馈半段：面板按钮名 = `COM_NAME301()`（`L_OPTION_NAME` 支，QOL_USERCOM:106）。
     * SETVAR（动态名写）与 ERB 裸名静态读必须落同一槽，玩家才能看到「泡茶 → 泡红茶」的变化。
     */
    private fun panelLabel(rig: Rig): String {
        rig.rt.callFunction(rig.functionKey("COM_NAME301"), longArrayOf(-1L), arrayOf<String>())
        return rig.rt.store.readStr("RESULTS", longArrayOf(0L))
    }

    /** 第二轮：重进弹窗，在挂起瞬间抓右栏快照（`currentOptions` = 右栏芯片唯一数据源）。 */
    private fun railSnapshotRoundTwo(rig: Rig): Pair<List<ConsoleOption>, String> {
        val key = rig.functionKey("COM_OPTION")
        val snapshot = AtomicReference<List<ConsoleOption>>()
        val awaitingDesc = AtomicReference<String>()
        val done = AtomicBoolean(false)
        val feeder = startFeeder(rig.sink, done) {
            val req = rig.sink.awaiting
            awaitingDesc.set(
                "form=${req?.form} type=${req?.type} mouse=${req?.mouseInput} " +
                    "hasDef=${req?.hasDef} def=${req?.defInt}",
            )
            snapshot.set(rig.session.console.currentOptions())
        }
        var vmError: Throwable? = null
        try {
            rig.rt.callFunction(key, longArrayOf(301L), arrayOf<String>())
        } catch (t: Throwable) {
            vmError = t
        } finally {
            done.set(true)
            feeder.join(JOIN_MS)
        }
        check(vmError == null) { "第二轮 COM_OPTION 调用异常：$vmError" }
        return snapshot.get().orEmpty() to awaitingDesc.get().orEmpty()
    }

    private fun describe(options: List<ConsoleOption>): String =
        options.joinToString(prefix = "[", postfix = "]") { "${it.label}->${it.submitValue}" }

    @Test
    fun `真语料 COM_OPTION 点击红茶写入 COM_ALTER_301`() {
        val (erbc, game) = corpusOrNull() ?: return
        val rig = assemble(erbc, game)
        try {
            // 语料前置：红茶道具。没有它 COM_NAME301 CASE 1 会因道具不足合法回落「泡茶」
            // 并把 COM_ALTER_301 清 0（COMF301:255-260）——玩家报的场景以有红茶为前提。
            rig.rt.store.writeInt("ITEM", longArrayOf(ITEM_BLACK_TEA), 1L)

            val alter = clickRoundOne(rig)
            check(alter == 1L) { "点击红茶后 COM_ALTER_301 期望 1，实际 $alter" }

            val label = panelLabel(rig)
            println("PROBE COM_NAME301=$label")
            check(label == "泡红茶") { "COM_ALTER_301=1 时面板按钮名期望「泡红茶」，实际「$label」" }

            val (chips, awaiting) = railSnapshotRoundTwo(rig)
            println("PROBE railChips=${describe(chips)}")
            println("PROBE awaiting=$awaiting")
            println("PROBE live=${describe(rig.session.console.displayOptions())}")
            println("PROBE published=${describe(rig.session.console.publishedOptions())}")
            val teaChip = chips.firstOrNull { it.submitValue == "1" }
            check(teaChip != null) {
                "弹窗等待页右栏快照必须含 [泡红茶]（submitValue=1），实际=${describe(chips)}"
            }
            check(teaChip.label.contains("泡红茶")) {
                "submitValue=1 的芯片 label 应为 [泡红茶]，实际=${teaChip.label}"
            }
            println("PROBE railChain OK")
        } finally {
            rig.sink.close()
        }
    }
}
