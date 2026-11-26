package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveRecovery.Journal
import com.eratw.port.core.save.SaveRecovery.Move
import com.eratw.port.core.save.SaveRecovery.RecoveryPlan

/**
 * 崩溃恢复的动作执行器（§8；SaveRecovery 只给判定，本类把判定落成磁盘动作）。
 *
 * SaveRecovery.plan 是**唯一判定真源**；本类只做动作、不再判断——不重读 SHA、不另判
 * 一套、不因执行结果改选计划。动作全部经 [SaveTransactionIo]，纯 JVM、可注入、
 * 无静态可变全局；生产 I/O 实现与调用点仍归宿主接线（M1-6/M4-3）。
 *
 * ## 各计划的崩溃安全执行序
 *
 * - [RecoveryPlan.KeepOld]：删残件 → 删 journal。中途崩溃 ⇒ journal 仍是 PREPARED，
 *   下轮重判 KeepOld（删除幂等）。
 * - [RecoveryPlan.RollForward]：先完成剩余换名（消费 `.part`）→ 成功后补写 COMMITTED
 *   （journal 已是 COMMITTED 则跳过）→ 删清理面 → 删 journal。.part 换名失败即抛出，
 *   journal 停在 BACKED_UP，下轮按磁盘事实重判。
 * - [RecoveryPlan.RollBack]：**先删全部 `.part`**（否则回滚途中崩溃，仍有效的 `.part`
 *   可能让下轮判定改走向前补完，逆转回滚决定）→ 用 `read + write + fsync` 从 `.bak`
 *   复制回目标并**保留 `.bak` 直到 journal 删除之后** → 删 journal → 删清理面。
 *   `.bak` 在回滚完成前始终在盘：恢复执行自身中途崩溃时，下轮仍能拿到回滚源。
 *   复制完成后按 journal 成员的 `oldSha256` 回读校验（[restoreCopy]）：静默半截写
 *   不抛异常，不校验就会带着半截目标「成功」并删光恢复源。
 * - [RecoveryPlan.Halt]：零 I/O，原样返回原因（journal 与残件一律不动，下一次启动
 *   继续报告，不静默销毁）。
 *
 * 本类不写新的 journal 状态（COMMITTED 之外没有可写态）；RollBack 完成后直接删 journal，
 * 旧对即恢复为「一次普通旧存档」，残 `.bak` 的轮转仍归 M1-2。
 */
class SavePlanExecutor(
    private val io: SaveTransactionIo,
    private val journalPath: String,
) {
    init {
        require(journalPath.isNotBlank()) { "journal 路径为空" }
    }

    /** 一次恢复执行的收口。 */
    sealed interface ExecutionResult {
        /** 盘上本来就没有 journal：不属于恢复管辖，旧档原样。 */
        data object NoJournal : ExecutionResult

        /** PREPARED 未越界：旧档原样，残件已清。 */
        data object KeptOld : ExecutionResult

        /** 向前补完：换名 [moved] 件、COMMITTED 已写、清理与 journal 删除完成。 */
        data class RolledForward(val moved: Int) : ExecutionResult

        /** 整对回滚：从 `.bak` 恢复 [restored] 件、清理与 journal 删除完成。 */
        data class RolledBack(val restored: Int) : ExecutionResult

        /** 介质损坏：零动作、原样报告。 */
        data class Halted(val reason: String) : ExecutionResult

        /**
         * journal 撕裂已隔离改名（`.torn` 后缀，取证保留）：目标一个不动、旧档原样放行，
         * 残件留给下一次事务自然轮转（安全论证见 [SaveTransaction.quarantineTornJournal]）。
         */
        data class TornQuarantined(val quarantinePath: String) : ExecutionResult
    }

    /**
     * 按 [plan] 执行磁盘动作。[journal] 必须就是产出该计划的 journal。
     *
     * 任何 I/O 异常直接向上抛：已落盘的 journal 状态仍可判定，下一次恢复按同一判定重做。
     */
    fun execute(journal: Journal, plan: RecoveryPlan): ExecutionResult = when (plan) {
        is RecoveryPlan.Halt -> ExecutionResult.Halted(plan.reason)

        is RecoveryPlan.KeepOld -> {
            for (path in plan.discard) io.delete(path)
            io.delete(journalPath)
            ExecutionResult.KeptOld
        }

        is RecoveryPlan.RollForward -> {
            for (move in plan.moves) io.rename(move.from, move.to)
            if (journal.commitState != CommitState.COMMITTED) writeCommitted(journal)
            for (path in plan.cleanup) io.delete(path)
            io.delete(journalPath)
            ExecutionResult.RolledForward(plan.moves.size)
        }

        is RecoveryPlan.RollBack -> {
            for (m in journal.members) io.delete(m.part)
            // 回滚期望指纹按目标路径对表：plan.restores 由 journal.members 产出
            // （SaveRecovery.kt:230 members.map { Move(it.bak, it.target) }），逐 move 可对回成员。
            val expectedShaByTarget = journal.members.associateBy({ it.target }) { it.oldSha256 }
            for (move in plan.restores) restoreCopy(move, expectedShaByTarget)
            io.delete(journalPath)
            for (path in plan.cleanup) io.delete(path)
            ExecutionResult.RolledBack(plan.restores.size)
        }
    }

    /**
     * 回滚源先复制、后随 journal 一起丢弃；复制期崩溃可由下轮从仍在的 `.bak` 重做。
     *
     * 写入后按 journal 成员的 `oldSha256` 回读校验：阻塞 FileChannel 的单次 `write`
     * 不保证写全缓冲，静默半截写不抛异常 —— 生产写面 [SaveTransactionIo] 的 NIO 实现
     * 曾一度就是单次 `channel.write`（2026-11-20 已改成
     * 「`while (buf.hasRemaining()) ch.write(buf)`」循环，与
     * [RootedSaveFileIo] 的 `SaveFileIo.kt:262` 同一条纪律；此处保留回读校验，
     * 因为任何实现都可能被替换成不守约的桩）。回滚目标是玩家旧档本体、写坏即无源可救：
     * 不校验就会带着半截目标「成功」，随后的 journal 与 `.bak` 删除把唯一恢复源一并销毁。
     * 校验不符即抛 —— journal 与 `.bak` 仍在盘，下一次恢复按同一判定重做（本类 KDoc
     * 「任何 I/O 异常直接向上抛」契约）。
     *
     * 无 C# 锚面：RT2 事务机为移植侧自研（SaveTransaction.kt:29-34 已登记 TODO(verify)，
     * 权威 = era-arch §8 蓝图与本文件 RollBack 写序不变量），本笔沿用该口径不另补 C# 锚。
     */
    private fun restoreCopy(move: Move, expectedShaByTarget: Map<String, String>) {
        val bytes = io.read(move.from)
            ?: throw IllegalStateException("回滚源缺失: ${move.from}（判定后介质变化，fail-closed）")
        io.write(move.to, bytes)
        io.fsync(move.to)
        val expected = expectedShaByTarget[move.to]
            ?: throw IllegalStateException("回滚目标不在 journal 成员表内: ${move.to}（fail-closed）")
        val written = io.read(move.to)
            ?: throw IllegalStateException("回滚写入后目标不可读: ${move.to}（fail-closed）")
        val got = sha256Hex(written)
        if (got != expected) {
            throw IllegalStateException(
                "回滚写入指纹不符: ${move.to} 期望 $expected 实测 $got" +
                    "（写入未完整落盘；journal 与 .bak 保留，下一次恢复按同一判定重做）",
            )
        }
    }

    private fun writeCommitted(journal: Journal) {
        val committed = Journal(
            journal.transactionId,
            journal.oldPairGeneration,
            journal.newPairGeneration,
            journal.members,
            CommitState.COMMITTED,
        )
        io.write(journalPath, SaveTransaction.encodeJournal(committed))
        io.fsync(journalPath)
    }

    companion object {
        /**
         * 一步完成「读 journal → 三候选观测 → plan 判定 → 执行」：
         * 无 journal 返回 [ExecutionResult.NoJournal]；Halt 原样返回、不动磁盘；
         * 撕裂 journal（解码失败）隔离改名后放行旧档（[ExecutionResult.TornQuarantined]），
         * 隔离失败同样按 Halt 收口。
         */
        fun recoverAndExecute(io: SaveTransactionIo, journalPath: String): ExecutionResult {
            val transaction = SaveTransaction(io, journalPath)
            return when (val recovered = transaction.recover()) {
                SaveTransaction.RecoveryResult.NoJournal -> ExecutionResult.NoJournal
                is SaveTransaction.RecoveryResult.TornJournal -> {
                    val quarantine = transaction.quarantineTornJournal("SAVETX")
                    if (quarantine == null) {
                        ExecutionResult.Halted("journal 撕裂且隔离失败: ${recovered.reason}")
                    } else {
                        ExecutionResult.TornQuarantined(quarantine)
                    }
                }
                is SaveTransaction.RecoveryResult.Decided ->
                    SavePlanExecutor(io, journalPath).execute(recovered.journal, recovered.plan)
            }
        }
    }
}
