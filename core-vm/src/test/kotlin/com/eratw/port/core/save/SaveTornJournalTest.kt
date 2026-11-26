package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveTransactionFixture as F
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 撕裂 journal 的恢复语义（2026-11-19 SAVEGLOBAL 玩家报障引出的引擎侧根修）。
 *
 * journal 是原地截断写（NioSaveTransactionIo.write 无临时文件），进程被杀在 journal
 * 写入中途就留下半截字节。此前的口径：recover 解码失败直接抛 → 调用方折成一句通用
 * 存档错误且 journal 永留盘上 ⇒ 一次写入中断让该存档域此后**每次**存/读档都在同一处
 * 失败（与 vivo 后台 FORCE STOP 玩家的持久复现同形）。钉住新语义：解码失败 =
 * 隔离改名（取证保留）+ 目标原样放行，下一次事务整对重写自然治愈；隔离失败
 * （rename 被拒）仍 fail-closed 拒绝覆盖。
 */
class SaveTornJournalTest {

    /** 半截 journal：真 journal 的前一半字节（魔数对得上、后半缺失）。 */
    private val tornBytes: ByteArray = run {
        val full = SaveTransaction.encodeJournal(F.journal(CommitState.PREPARED, F.savMember(), F.rt2Member()))
        full.copyOfRange(0, full.size / 2)
    }

    private fun seedTorn(io: FakeTransactionIo) {
        io.disk[F.SAV_TARGET] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_TARGET] = F.RT2_OLD.copyOf()
        io.disk[F.JOURNAL_PATH] = tornBytes.copyOf()
    }

    /** rename 对 journal 路径拒绝的假体（隔离失败的注入口）；磁盘断言走 [inner]。 */
    private class RenameBlockedIo(val inner: FakeTransactionIo, val blockedPath: String) :
        SaveTransactionIo by inner {
        override fun rename(from: String, to: String) {
            if (from == blockedPath) error("rename 被拒")
            inner.rename(from, to)
        }
    }

    @Test
    fun `撕裂journal_恢复隔离改名_目标原样放行`() {
        val io = FakeTransactionIo()
        seedTorn(io)

        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)

        val quarantined = assertIs<SavePlanExecutor.ExecutionResult.TornQuarantined>(result)
        assertEquals(F.JOURNAL_PATH + ".torn", quarantined.quarantinePath)
        assertTrue(io.disk[F.JOURNAL_PATH] == null, "journal 原路径必须清空（已改名隔离）")
        assertEquals(
            tornBytes.asList(),
            io.disk[F.JOURNAL_PATH + ".torn"]!!.asList(),
            "隔离件必须原样保留供取证",
        )
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList(), "目标不许被动")
        assertEquals(F.RT2_OLD.asList(), io.disk[F.RT2_TARGET]!!.asList(), "目标不许被动")
        assertTrue(
            SaveDiagnostics.snapshot().any { it.contains("journal 不可解码") },
            "隔离必须留痕: ${SaveDiagnostics.snapshot()}",
        )
    }

    @Test
    fun `撕裂journal_隔离后恢复为无journal_本次保存照常提交`() {
        val io = FakeTransactionIo()
        seedTorn(io)
        assertIs<SavePlanExecutor.ExecutionResult.TornQuarantined>(
            SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH),
        )
        assertIs<SavePlanExecutor.ExecutionResult.NoJournal>(
            SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH),
        )

        val result = SaveTransaction(io, F.JOURNAL_PATH).execute(F.request())

        assertEquals(SaveTransaction.ExecuteResult.Committed, result)
        assertEquals(sha256Hex(F.SAV_NEW), sha256Hex(io.disk[F.SAV_TARGET]!!))
        assertEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET]!!))
    }

    @Test
    fun `撕裂journal_隔离失败_拒绝覆盖不可判定现场`() {
        val fake = FakeTransactionIo()
        seedTorn(fake)
        val io = RenameBlockedIo(fake, F.JOURNAL_PATH)

        val result = SaveTransaction(io, F.JOURNAL_PATH).execute(F.request())

        val rejected = assertIs<SaveTransaction.ExecuteResult.Rejected>(result)
        assertTrue(rejected.reason.contains("隔离失败"), "文案须点明隔离失败: ${rejected.reason}")
        assertTrue(fake.disk[F.SAV_TARGET]!!.contentEquals(F.SAV_OLD), "拒绝时目标不许被动过")
        assertTrue(fake.disk[F.RT2_TARGET]!!.contentEquals(F.RT2_OLD), "拒绝时目标不许被动过")
        assertTrue(fake.disk[F.JOURNAL_PATH] != null, "隔离失败时撕裂 journal 原样保留")
    }

    @Test
    fun `撕裂journal_隔离失败_恢复面按Halt收口不动磁盘`() {
        val fake = FakeTransactionIo()
        seedTorn(fake)
        val io = RenameBlockedIo(fake, F.JOURNAL_PATH)

        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)

        val halted = assertIs<SavePlanExecutor.ExecutionResult.Halted>(result)
        assertTrue(halted.reason.contains("隔离失败"), "文案须点明隔离失败: ${halted.reason}")
        assertTrue(fake.disk[F.JOURNAL_PATH] != null, "撕裂 journal 原样保留")
        assertEquals(F.SAV_OLD.asList(), fake.disk[F.SAV_TARGET]!!.asList())
    }

    @Test
    fun `重名隔离件依次加后缀_不覆盖旧取证`() {
        val io = FakeTransactionIo()
        seedTorn(io)
        io.disk[F.JOURNAL_PATH + ".torn"] = "旧取证".toByteArray()

        val result = SavePlanExecutor.recoverAndExecute(io, F.JOURNAL_PATH)

        val quarantined = assertIs<SavePlanExecutor.ExecutionResult.TornQuarantined>(result)
        assertEquals(F.JOURNAL_PATH + ".torn-1", quarantined.quarantinePath)
        assertTrue(io.disk[F.JOURNAL_PATH + ".torn"]!!.contentEquals("旧取证".toByteArray()))
        assertEquals(tornBytes.asList(), io.disk[F.JOURNAL_PATH + ".torn-1"]!!.asList())
    }
}
