package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.eratw.port.core.save.SaveTransactionFixture as F

/**
 * SavePlanExecutor 契约闸（§8；RecoveryPlan 的动作半边）。
 *
 * 钉住：三条非 Halt 计划的磁盘终局、Halt 零 I/O、RollForward 先换名后写 COMMITTED、
 * RollBack 先删 `.part`、`.bak` 活到 journal 删除之后、执行器自身中途崩溃后重入仍按
 * 原判定收敛，以及「读 journal → plan → 执行」端到端。
 */
class SavePlanExecutorTest {

    private fun planFor(io: FakeTransactionIo, journal: SaveRecovery.Journal) =
        SaveRecovery.plan(journal, F.observeFromDisk(io, journal))

    @Test
    fun `KeepOld_旧档原样_残件与journal清除`() {
        val io = F.seededIo()
        io.disk[F.SAV_PART] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = F.RT2_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        val journal = F.journal(CommitState.PREPARED, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)
        assertIs<SaveRecovery.RecoveryPlan.KeepOld>(plan)

        assertEquals(SavePlanExecutor.ExecutionResult.KeptOld, SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan))
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertEquals(F.RT2_OLD.asList(), io.disk[F.RT2_TARGET]!!.asList())
        assertTrue(io.disk[F.SAV_PART] == null)
        assertTrue(io.disk[F.RT2_PART] == null)
        assertTrue(io.disk[F.SAV_BAK] == null)
        assertTrue(io.disk[F.RT2_BAK] == null)
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }

    @Test
    fun `RollForward_剩余换名_先换名后补写COMMITTED再清理`() {
        val io = F.seededIo()
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = F.RT2_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        val journal = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)
        assertIs<SaveRecovery.RecoveryPlan.RollForward>(plan)
        io.ops.clear()

        val result = SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan)
        assertEquals(SavePlanExecutor.ExecutionResult.RolledForward(1), result)
        assertEquals(sha256Hex(F.SAV_NEW), sha256Hex(io.disk[F.SAV_TARGET]!!))
        assertEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET]!!))
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
        val renameAt = io.ops.indexOf("RENAME:${F.RT2_PART}->${F.RT2_TARGET}")
        val commitAt = io.ops.indexOf("WRITE:${F.JOURNAL_PATH}")
        assertTrue(renameAt >= 0 && commitAt > renameAt, "换名必须先于 COMMITTED 写入: ${io.ops}")
    }

    @Test
    fun `RollForward_COMMITTED已写不重复写journal`() {
        val io = F.seededIo()
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_TARGET] = F.RT2_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        val journal = F.journal(CommitState.COMMITTED, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)
        assertIs<SaveRecovery.RecoveryPlan.RollForward>(plan)
        io.ops.clear()

        val result = SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan)
        assertEquals(SavePlanExecutor.ExecutionResult.RolledForward(0), result)
        assertTrue(io.ops.none { it == "WRITE:${F.JOURNAL_PATH}" })
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }

    @Test
    fun `RollForward_写完COMMITTED即断电_重入只清理`() {
        val io = F.seededIo()
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = F.RT2_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        val journal = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)

        // 清理面 4 次删除后、journal 删除前断电 ⇒ 盘上应留下 COMMITTED journal。
        io.crashPoint = CrashPoint(OpKind.DELETE, 5)
        assertThrows<SimulatedCrash> { SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan) }
        assertEquals(CommitState.COMMITTED, SaveTransaction(io, F.JOURNAL_PATH).readJournal()!!.commitState)

        io.crashPoint = null
        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)
        assertEquals(SavePlanExecutor.ExecutionResult.RolledForward(0), result)
        assertEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET]!!))
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }

    @Test
    fun `RollBack_整对回滚_先删part且bak活到journal之后`() {
        val io = F.seededIo()
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = "corrupt".toByteArray()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        val journal = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)
        assertIs<SaveRecovery.RecoveryPlan.RollBack>(plan)
        io.ops.clear()

        val result = SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan)
        assertEquals(SavePlanExecutor.ExecutionResult.RolledBack(2), result)
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertEquals(F.RT2_OLD.asList(), io.disk[F.RT2_TARGET]!!.asList())
        assertTrue(io.disk[F.SAV_PART] == null)
        assertTrue(io.disk[F.RT2_PART] == null)
        assertTrue(io.disk[F.SAV_BAK] == null)
        assertTrue(io.disk[F.RT2_BAK] == null)
        assertTrue(io.disk[F.JOURNAL_PATH] == null)

        val partDeleteAt = io.ops.indexOf("DELETE:${F.RT2_PART}")
        val firstRestoreAt = io.ops.indexOf("WRITE:${F.SAV_TARGET}")
        val journalDeleteAt = io.ops.indexOf("DELETE:${F.JOURNAL_PATH}")
        val bakDeleteAt = io.ops.indexOf("DELETE:${F.SAV_BAK}")
        assertTrue(partDeleteAt in 0 until firstRestoreAt, ".part 必须先于恢复删除: ${io.ops}")
        assertTrue(journalDeleteAt in 0 until bakDeleteAt, "journal 必须先于 .bak 删除: ${io.ops}")
    }

    @Test
    fun `RollBack执行中途断电_重入仍整对回滚`() {
        val io = F.seededIo()
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = "corrupt".toByteArray()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        val journal = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)

        io.crashPoint = CrashPoint(OpKind.FSYNC, 1)
        assertThrows<SimulatedCrash> { SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan) }
        assertTrue(io.disk[F.SAV_PART] == null && io.disk[F.RT2_PART] == null)
        assertTrue(io.disk[F.SAV_BAK] != null && io.disk[F.RT2_BAK] != null)
        assertTrue(io.disk[F.JOURNAL_PATH] != null)

        io.crashPoint = null
        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)
        assertEquals(SavePlanExecutor.ExecutionResult.RolledBack(2), result)
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertEquals(F.RT2_OLD.asList(), io.disk[F.RT2_TARGET]!!.asList())
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }

    @Test
    fun `Halt_零动作原样报告`() {
        val io = F.seededIo()
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = "corrupt".toByteArray()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        val journal = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        F.writeJournalToDisk(io, journal)
        val plan = planFor(io, journal)
        assertIs<SaveRecovery.RecoveryPlan.Halt>(plan)
        val before = io.disk.mapValues { it.value.copyOf() }
        io.ops.clear()

        val result = SavePlanExecutor(io, F.JOURNAL_PATH).execute(journal, plan)
        assertIs<SavePlanExecutor.ExecutionResult.Halted>(result)
        assertTrue(io.ops.isEmpty(), "Halt 不得有任何 I/O: ${io.ops}")
        assertEquals(before.keys, io.disk.keys)
        for ((path, bytes) in before) assertTrue(bytes.contentEquals(io.disk[path]))
    }

    @Test
    fun `无journal不进入恢复`() {
        val io = F.seededIo()
        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)
        assertEquals(SavePlanExecutor.ExecutionResult.NoJournal, result)
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertEquals(F.RT2_OLD.asList(), io.disk[F.RT2_TARGET]!!.asList())
    }

    @Test
    fun `真实写入断电_向前补完端到端`() {
        val (io, _) = F.crashedExecute(CrashPoint(OpKind.RENAME, 2))
        // 换名 #2 前断电 ⇒ sav 已替换、rt2 仍以 .part 待换名。
        assertNotEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET] ?: ByteArray(0)))

        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)
        assertEquals(SavePlanExecutor.ExecutionResult.RolledForward(1), result)
        assertEquals(sha256Hex(F.SAV_NEW), sha256Hex(io.disk[F.SAV_TARGET]!!))
        assertEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET]!!))
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }
}
