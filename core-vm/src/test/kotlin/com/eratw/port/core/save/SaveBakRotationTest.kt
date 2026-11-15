package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.eratw.port.core.save.SaveTransactionFixture as F

/**
 * M1-2：`.bak` 轮转（era-arch §8 恢复矩阵）。
 *
 * 判据（CONTRACT-RT2-M12 决定 1）：
 * - `.bak` 只是事务窗口内的回滚源，不是玩家面历史备份；COMMITTED 后即随 cleanup 删除，
 *   清序 `.part` → journal → `.bak`（journal 先失效，半清理崩溃不会被误判为可恢复事务）；
 * - 四个出口一致：正常提交（本文件）、KeepOld / RollForward / RollBack（SavePlanExecutorTest
 *   既有用例已钉 `.bak` 终局删除）；幂等重入 `AlreadyCommitted` 也走同一 cleanup；
 * - 连续保存不累积 `.bak`，下一次保存从头写。
 */
class SaveBakRotationTest {

    @Test
    fun `提交成功_bak轮出且清序part先于journal先于bak`() {
        val io = newIo()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.ExecuteResult.Committed>(tx.execute(request()))
        assertTrue(io.disk[F.SAV_BAK] == null)
        assertTrue(io.disk[F.RT2_BAK] == null)
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
        assertTrue(io.disk[F.SAV_PART] == null)

        val savPartAt = io.ops.indexOf("DELETE:${F.SAV_PART}")
        val journalAt = io.ops.indexOf("DELETE:${F.JOURNAL_PATH}")
        val bakAt = io.ops.indexOf("DELETE:${F.SAV_BAK}")
        assertTrue(savPartAt >= 0 && journalAt > savPartAt, "journal 必须晚于残 .part: ${io.ops}")
        assertTrue(bakAt > journalAt, "journal 必须先失效、.bak 最后删: ${io.ops}")
    }

    @Test
    fun `连续两次保存_第二事务从头写bak且不累积`() {
        val io = newIo()
        val first = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.ExecuteResult.Committed>(first.execute(request()))

        val second = SaveTransaction.Request(
            "tx-m12-second", 8, 9,
            listOf(
                SaveTransaction.MemberSpec(
                    F.SAV_TARGET, F.SAV_PART, F.SAV_BAK,
                    F.SAV_NEW, "sav-new-v3".toByteArray(),
                ),
                SaveTransaction.MemberSpec(
                    F.RT2_TARGET, F.RT2_PART, F.RT2_BAK,
                    F.RT2_NEW, "rt2-new-v3".toByteArray(),
                ),
            ),
        )
        assertIs<SaveTransaction.ExecuteResult.Committed>(
            SaveTransaction(io, F.JOURNAL_PATH).execute(second),
        )
        assertTrue(io.disk[F.SAV_BAK] == null, "第二次提交后同样不得留 .bak")
        assertTrue(io.disk[F.RT2_BAK] == null)
        assertEquals("sav-new-v3", io.disk[F.SAV_TARGET]!!.decodeToString())
        assertEquals("rt2-new-v3", io.disk[F.RT2_TARGET]!!.decodeToString())
    }

    @Test
    fun `幂等重入_AlreadyCommitted也清掉残留bak`() {
        // 清理半途断电：清序 .part → journal → .bak ⇒ 断在 journal 删除前，
        // 盘上是 COMMITTED journal + 完整 .bak（残 .part 已被换名消费）。
        val (io, _) = F.crashedExecute(CrashPoint(OpKind.DELETE, 3))
        assertEquals(CommitState.COMMITTED, SaveTransaction(io, F.JOURNAL_PATH).readJournal()!!.commitState)
        assertTrue(io.disk[F.SAV_BAK] != null, "断点处 .bak 应仍在盘")
        assertTrue(io.disk[F.RT2_BAK] != null)

        io.crashPoint = null
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.ExecuteResult.AlreadyCommitted>(tx.execute(request()))
        assertTrue(io.disk[F.SAV_BAK] == null, "幂等重入补做 cleanup，残 .bak 同样轮出")
        assertTrue(io.disk[F.RT2_BAK] == null)
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }

    private fun newIo(): FakeTransactionIo = F.seededIo()

    private fun request(): SaveTransaction.Request = F.request()
}
