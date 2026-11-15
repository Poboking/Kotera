package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveRecovery.Journal
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * SaveTransaction 的事务身份 / 前置闸回归（架构师验收补充，M1-5 契约 3.2-6 的边界）。
 *
 * 契约只钉了「同一事务重入幂等」；本闸补两条真实风险：
 * ① 磁盘残留**其它事务**的 COMMITTED journal 时，新事务不得被当成重入而静默跳过；
 * ② 磁盘残留**未完成**（PREPARED/BACKED_UP）的其它事务 journal 时，新事务必须
 *    fail-closed 拒绝，先 recover，不得覆盖 journal 破坏可恢复性。
 */
class SaveTransactionJournalScopeTest {

    private val newerSav = "sav-newer-v3".toByteArray()
    private val newerRt2 = "rt2-newer-v3".toByteArray()

    private fun newerRequest(): SaveTransaction.Request = SaveTransaction.Request(
        "tx-new", 9, 10,
        listOf(
            SaveTransaction.MemberSpec(
                SaveTransactionFixture.SAV_TARGET,
                SaveTransactionFixture.SAV_PART,
                SaveTransactionFixture.SAV_BAK,
                SaveTransactionFixture.SAV_NEW,
                newerSav,
            ),
            SaveTransaction.MemberSpec(
                SaveTransactionFixture.RT2_TARGET,
                SaveTransactionFixture.RT2_PART,
                SaveTransactionFixture.RT2_BAK,
                SaveTransactionFixture.RT2_NEW,
                newerRt2,
            ),
        ),
    )

    @Test
    fun `其它事务的COMMITTED残留_不得让新事务静默跳过`() {
        val io = SaveTransactionFixture.seededIo()
        io.disk[SaveTransactionFixture.SAV_TARGET] = SaveTransactionFixture.SAV_NEW.copyOf()
        io.disk[SaveTransactionFixture.RT2_TARGET] = SaveTransactionFixture.RT2_NEW.copyOf()
        val other = Journal(
            "tx-other", 5, 6,
            listOf(SaveTransactionFixture.savMember(), SaveTransactionFixture.rt2Member()),
            CommitState.COMMITTED,
        )
        SaveTransactionFixture.writeJournalToDisk(io, other)

        val tx = SaveTransaction(io, SaveTransactionFixture.JOURNAL_PATH)
        val result = tx.execute(newerRequest())

        assertIs<SaveTransaction.ExecuteResult.Committed>(result)
        assertContentEquals(newerSav, io.disk[SaveTransactionFixture.SAV_TARGET])
        assertContentEquals(newerRt2, io.disk[SaveTransactionFixture.RT2_TARGET])
    }

    @Test
    fun `其它事务的未完成journal_必须拒绝新事务且不覆盖journal`() {
        val io = SaveTransactionFixture.seededIo()
        val other = Journal(
            "tx-other", 5, 6,
            listOf(SaveTransactionFixture.savMember(), SaveTransactionFixture.rt2Member()),
            CommitState.BACKED_UP,
        )
        SaveTransactionFixture.writeJournalToDisk(io, other)
        val journalBefore = io.disk[SaveTransactionFixture.JOURNAL_PATH]!!.copyOf()

        val tx = SaveTransaction(io, SaveTransactionFixture.JOURNAL_PATH)
        val result = tx.execute(SaveTransactionFixture.request())

        assertIs<SaveTransaction.ExecuteResult.Rejected>(result)
        assertContentEquals(journalBefore, io.disk[SaveTransactionFixture.JOURNAL_PATH])
        assertContentEquals(SaveTransactionFixture.SAV_OLD, io.disk[SaveTransactionFixture.SAV_TARGET])
        assertContentEquals(SaveTransactionFixture.RT2_OLD, io.disk[SaveTransactionFixture.RT2_TARGET])
    }
}
