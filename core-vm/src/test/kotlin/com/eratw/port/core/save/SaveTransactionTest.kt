package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveRecovery.Move
import com.eratw.port.core.save.SaveRecovery.RecoveryPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SaveTransaction 事务日志层判据（CONTRACT-RT2-M1-5 第 3.2 节逐条一个用例）。
 *
 * 断言基准：SaveRecovery.kt 第 47 至 52 行六断电点矩阵、第 19 至 26 行固定写序、
 * 每成员三候选（第 30 至 41 行）、plan 的 KeepOld / RollForward / RollBack / Halt 四产出。
 * 六点矩阵通过 FakeTransactionIo 在六个 I/O 断点注入崩溃，把磁盘上真实残留的事实
 * 经 SaveTransaction.recover 转成 MemberObservation 后喂 SaveRecovery.plan 逐点核对。
 */
class SaveTransactionTest {

    private val F = SaveTransactionFixture

    private fun newIo(
        crashPoint: CrashPoint? = null,
        members: Int = 2,
        freeBytes: Long = Long.MAX_VALUE / 4,
    ): FakeTransactionIo = F.seededIo(crashPoint, members, freeBytes)

    private fun request(members: Int = 2): SaveTransaction.Request = F.request(members)

    private fun crashedExecute(point: CrashPoint, members: Int = 2): Pair<FakeTransactionIo, SaveTransaction> =
        F.crashedExecute(point, members)

    private fun planOf(io: FakeTransactionIo): RecoveryPlan =
        (SaveTransaction(io, F.JOURNAL_PATH).recover() as SaveTransaction.RecoveryResult.Decided).plan

    private fun recoveryOf(io: FakeTransactionIo): SaveTransaction.RecoveryResult =
        SaveTransaction(io, F.JOURNAL_PATH).recover()

    // ------------------------------------------------------------------
    // 判据 1 + 六点矩阵逐点注入（SaveRecovery.kt:47-52）
    // ------------------------------------------------------------------

    @Test
    fun `断点1_两part完成PREPARED未写_无journal根本不调用plan`() {
        val (io, _) = crashedExecute(CrashPoint(OpKind.WRITE_JOURNAL, 1))
        assertTrue(io.disk[F.SAV_PART] != null)
        assertTrue(io.disk[F.RT2_PART] != null)
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertEquals(F.RT2_OLD.asList(), io.disk[F.RT2_TARGET]!!.asList())
        val rec = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.RecoveryResult.NoJournal>(rec.recover())
        assertFalse(SaveTransaction.EV_PLAN_INVOKED in rec.trace)
    }

    @Test
    fun `断点2_PREPARED已写BACKED_UP未写_KeepOld`() {
        val (io, tx) = crashedExecute(CrashPoint(OpKind.WRITE_BAK, 1))
        assertEquals(CommitState.PREPARED, tx.readJournal()!!.commitState)
        assertTrue(io.disk[F.SAV_BAK] == null)
        val plan = assertIs<RecoveryPlan.KeepOld>(planOf(io))
        assertEquals(listOf(F.SAV_PART, F.SAV_BAK, F.RT2_PART, F.RT2_BAK), plan.discard)
        assertTrue(SaveTransaction.EV_JOURNAL_PREPARED in tx.trace)
        assertFalse(SaveTransaction.EV_WRITE_BAK in tx.trace)
    }

    @Test
    fun `断点3_BACKED_UP已写未替换_RollForward两part`() {
        val (io, tx) = crashedExecute(CrashPoint(OpKind.RENAME, 1))
        assertEquals(CommitState.BACKED_UP, tx.readJournal()!!.commitState)
        assertTrue(io.disk[F.SAV_BAK] != null)
        assertTrue(io.disk[F.RT2_BAK] != null)
        val plan = assertIs<RecoveryPlan.RollForward>(planOf(io))
        assertEquals(
            listOf(Move(F.SAV_PART, F.SAV_TARGET), Move(F.RT2_PART, F.RT2_TARGET)),
            plan.moves,
        )
        assertTrue(SaveTransaction.EV_JOURNAL_BACKED_UP in tx.trace)
        assertFalse(SaveTransaction.EV_RENAME in tx.trace)
    }

    @Test
    fun `断点4_sav已替换rt2未替换_RollForward只换一件`() {
        val (io, tx) = crashedExecute(CrashPoint(OpKind.RENAME, 2))
        assertEquals(CommitState.BACKED_UP, tx.readJournal()!!.commitState)
        assertEquals(sha256Hex(F.SAV_NEW), sha256Hex(io.disk[F.SAV_TARGET]!!))
        assertTrue(io.disk[F.SAV_PART] == null)
        assertTrue(io.disk[F.RT2_PART] != null)
        val plan = assertIs<RecoveryPlan.RollForward>(planOf(io))
        assertEquals(listOf(Move(F.RT2_PART, F.RT2_TARGET)), plan.moves)
        assertTrue(SaveTransaction.EV_JOURNAL_BACKED_UP in tx.trace)
    }

    @Test
    fun `断点5_双替换完成COMMITTED未写_RollForward_moves空`() {
        val (io, tx) = crashedExecute(CrashPoint(OpKind.WRITE_JOURNAL, 3))
        assertEquals(CommitState.BACKED_UP, tx.readJournal()!!.commitState)
        val plan = assertIs<RecoveryPlan.RollForward>(planOf(io))
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf(F.SAV_PART, F.SAV_BAK, F.RT2_PART, F.RT2_BAK), plan.cleanup)
        val tr = tx.trace
        assertTrue(tr.indexOf(SaveTransaction.EV_JOURNAL_BACKED_UP) < tr.indexOf(SaveTransaction.EV_RENAME))
        assertTrue(tr.indexOf(SaveTransaction.EV_RENAME) < tr.lastIndexOf(SaveTransaction.EV_VERIFY_TARGET))
        assertFalse(SaveTransaction.EV_JOURNAL_COMMITTED in tr)
    }

    @Test
    fun `断点6_COMMITTED已写清理未完成_RollForward只清理`() {
        val (io, tx) = crashedExecute(CrashPoint(OpKind.DELETE, 1))
        assertEquals(CommitState.COMMITTED, tx.readJournal()!!.commitState)
        // 定序替换是换名：双替换完成后 .part 已被消费，清理未完成留下的是 journal 与 .bak。
        assertTrue(io.disk[F.JOURNAL_PATH] != null)
        assertTrue(io.disk[F.SAV_BAK] != null)
        assertTrue(io.disk[F.RT2_BAK] != null)
        assertTrue(io.disk[F.SAV_PART] == null)
        assertTrue(io.disk[F.RT2_PART] == null)
        val plan = assertIs<RecoveryPlan.RollForward>(planOf(io))
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf(F.SAV_PART, F.SAV_BAK, F.RT2_PART, F.RT2_BAK), plan.cleanup)
        assertTrue(SaveTransaction.EV_JOURNAL_COMMITTED in tx.trace)
        assertFalse(SaveTransaction.EV_CLEANUP in tx.trace)
    }

    // ------------------------------------------------------------------
    // 判据 2：写序钉（源码形状 = execute 语句顺序；运行时记录 = trace）
    // ------------------------------------------------------------------

    @Test
    fun `写序钉_PREPARED先于bak_BACKED_UP先于替换_COMMITTED在双校验后`() {
        val io = newIo()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.ExecuteResult.Committed>(tx.execute(request()))
        val tr = tx.trace
        fun first(ev: String): Int = tr.indexOf(ev).also { assertTrue(it >= 0, "缺事件 $ev") }
        fun last(ev: String): Int = tr.lastIndexOf(ev).also { assertTrue(it >= 0, "缺事件 $ev") }
        assertTrue(first(SaveTransaction.EV_FSYNC_PART) < first(SaveTransaction.EV_JOURNAL_PREPARED))
        assertTrue(first(SaveTransaction.EV_JOURNAL_PREPARED) < first(SaveTransaction.EV_WRITE_BAK))
        assertTrue(last(SaveTransaction.EV_FSYNC_BAK) < first(SaveTransaction.EV_JOURNAL_BACKED_UP))
        assertTrue(first(SaveTransaction.EV_JOURNAL_BACKED_UP) < first(SaveTransaction.EV_RENAME))
        assertTrue(last(SaveTransaction.EV_VERIFY_TARGET) < first(SaveTransaction.EV_JOURNAL_COMMITTED))
        assertTrue(first(SaveTransaction.EV_JOURNAL_COMMITTED) < first(SaveTransaction.EV_CLEANUP))
    }

    // ------------------------------------------------------------------
    // 判据 3：每成员三候选，成员数 1 与 2 两档
    // ------------------------------------------------------------------

    @Test
    fun `三候选_新版本只在part_成员数2_SHA对账选part`() {
        val io = newIo()
        val j = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        io.disk[F.SAV_PART] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = F.RT2_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        F.writeJournalToDisk(io, j)
        val obs = F.observeFromDisk(io, j)
        assertEquals(sha256Hex(F.SAV_NEW), obs[0].partSha)
        assertEquals(sha256Hex(F.SAV_OLD), obs[0].targetSha)
        assertEquals(sha256Hex(F.SAV_OLD), obs[0].bakSha)
        val byCore = assertIs<RecoveryPlan.RollForward>(SaveRecovery.plan(j, obs))
        assertEquals(
            listOf(Move(F.SAV_PART, F.SAV_TARGET), Move(F.RT2_PART, F.RT2_TARGET)),
            byCore.moves,
        )
        assertEquals(byCore, planOf(io))
    }

    @Test
    fun `三候选_新版本已在目标_成员数2_选target不再换该件`() {
        val io = newIo()
        val j = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        io.disk[F.SAV_TARGET] = F.SAV_NEW.copyOf()
        io.disk[F.RT2_PART] = F.RT2_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = F.RT2_OLD.copyOf()
        F.writeJournalToDisk(io, j)
        val obs = F.observeFromDisk(io, j)
        assertNull(obs[0].partSha)
        assertEquals(sha256Hex(F.SAV_NEW), obs[0].targetSha)
        val byCore = assertIs<RecoveryPlan.RollForward>(SaveRecovery.plan(j, obs))
        assertEquals(listOf(Move(F.RT2_PART, F.RT2_TARGET)), byCore.moves)
        assertEquals(byCore, planOf(io))
    }

    @Test
    fun `三候选_bak旧版本_成员数1_无新副本整对从bak回滚`() {
        val io = newIo(members = 1)
        val j = F.journal(CommitState.BACKED_UP, F.savMember())
        io.disk[F.SAV_PART] = "corrupt".toByteArray()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        F.writeJournalToDisk(io, j)
        val obs = F.observeFromDisk(io, j)
        assertEquals(sha256Hex(F.SAV_OLD), obs[0].bakSha)
        assertTrue(obs[0].partSha != sha256Hex(F.SAV_NEW))
        assertTrue(obs[0].targetSha != sha256Hex(F.SAV_NEW))
        val byCore = assertIs<RecoveryPlan.RollBack>(SaveRecovery.plan(j, obs))
        assertEquals(listOf(Move(F.SAV_BAK, F.SAV_TARGET)), byCore.restores)
        assertEquals(byCore, planOf(io))
    }

    @Test
    fun `三候选_成员数1_同一协议向前补完`() {
        val io = newIo(members = 1)
        val j = F.journal(CommitState.BACKED_UP, F.savMember())
        io.disk[F.SAV_PART] = F.SAV_NEW.copyOf()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        F.writeJournalToDisk(io, j)
        val byCore = assertIs<RecoveryPlan.RollForward>(SaveRecovery.plan(j, F.observeFromDisk(io, j)))
        assertEquals(listOf(Move(F.SAV_PART, F.SAV_TARGET)), byCore.moves)
        assertEquals(listOf(F.SAV_PART, F.SAV_BAK), byCore.cleanup)
        assertEquals(byCore, planOf(io))
    }

    // ------------------------------------------------------------------
    // 判据 4：空间预算（写前拒绝 / 刚好等于放行 / 不许先写再检查）
    // ------------------------------------------------------------------

    @Test
    fun `空间预算_超预算在写前拒绝且文案可定位`() {
        val io = newIo()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        val req = request()
        val required = tx.requiredBytes(req)
        io.freeBytes = required - 1
        val result = assertIs<SaveTransaction.ExecuteResult.Rejected>(tx.execute(req))
        assertTrue(result.reason.contains("tx-m15"))
        assertTrue(result.reason.contains("${required}B"))
        assertTrue(result.reason.contains("${required - 1}B"))
        assertTrue(result.reason.contains("任何写入之前"))
        assertTrue(
            io.ops.none {
                it.startsWith("WRITE") || it.startsWith("FSYNC") ||
                    it.startsWith("RENAME") || it.startsWith("DELETE")
            },
        )
        assertTrue(io.disk[F.SAV_PART] == null)
    }

    @Test
    fun `空间预算_刚好等于上限放行`() {
        val io = newIo()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        val req = request()
        io.freeBytes = tx.requiredBytes(req)
        assertIs<SaveTransaction.ExecuteResult.Committed>(tx.execute(req))
        assertEquals(sha256Hex(F.SAV_NEW), sha256Hex(io.disk[F.SAV_TARGET]!!))
        assertEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET]!!))
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
    }

    @Test
    fun `空间预算_预算门是execute第一个动作不许先写再检查`() {
        val io = newIo()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        val req = request()
        io.freeBytes = tx.requiredBytes(req) - 1
        assertIs<SaveTransaction.ExecuteResult.Rejected>(tx.execute(req))
        assertTrue(tx.trace.isEmpty())
        assertEquals("AVAILABLE", io.ops.first())
    }

    // ------------------------------------------------------------------
    // 判据 5：介质损坏 fail-closed（停止并报告，不静默回滚、不静默销毁旧档）
    // ------------------------------------------------------------------

    @Test
    fun `介质损坏_缺bak_停止并报告不静默销毁`() {
        val io = newIo()
        val j = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        io.disk[F.SAV_PART] = "corrupt-sav".toByteArray()
        io.disk[F.RT2_PART] = "corrupt-rt2".toByteArray()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        F.writeJournalToDisk(io, j)
        val before = io.ops.size
        val plan = assertIs<RecoveryPlan.Halt>(planOf(io))
        assertTrue(plan.reason.contains(F.RT2_BAK))
        assertTrue(plan.reason.contains("缺失"))
        assertTrue(
            io.ops.drop(before).none {
                it.startsWith("WRITE") || it.startsWith("RENAME") || it.startsWith("DELETE")
            },
        )
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertTrue(io.disk[F.SAV_BAK] != null)
    }

    @Test
    fun `介质损坏_bak指纹不符_Halt`() {
        val io = newIo()
        val j = F.journal(CommitState.BACKED_UP, F.savMember(), F.rt2Member())
        io.disk[F.SAV_PART] = "corrupt".toByteArray()
        io.disk[F.RT2_PART] = "corrupt".toByteArray()
        io.disk[F.SAV_BAK] = F.SAV_OLD.copyOf()
        io.disk[F.RT2_BAK] = "tampered-bak".toByteArray()
        F.writeJournalToDisk(io, j)
        val plan = assertIs<RecoveryPlan.Halt>(planOf(io))
        assertTrue(plan.reason.contains("指纹不符"))
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
    }

    @Test
    fun `介质损坏_journal声明COMMITTED但磁盘事实矛盾_Halt`() {
        val io = newIo()
        val j = F.journal(CommitState.COMMITTED, F.savMember(), F.rt2Member())
        io.disk[F.SAV_PART] = "tampered-part".toByteArray()
        io.disk[F.RT2_PART] = "tampered-part".toByteArray()
        io.disk[F.SAV_BAK] = "tampered-bak".toByteArray()
        io.disk[F.RT2_BAK] = "tampered-bak".toByteArray()
        F.writeJournalToDisk(io, j)
        val plan = assertIs<RecoveryPlan.Halt>(planOf(io))
        assertTrue(plan.reason.contains("指纹不符"))
        assertEquals(F.SAV_OLD.asList(), io.disk[F.SAV_TARGET]!!.asList())
        assertTrue(io.disk[F.SAV_BAK] != null)
    }

    // ------------------------------------------------------------------
    // 判据 6：幂等与清理
    // ------------------------------------------------------------------

    @Test
    fun `幂等_COMMITTED已写重入不重复写journal`() {
        val (io, _) = crashedExecute(CrashPoint(OpKind.DELETE, 1))
        assertEquals(CommitState.COMMITTED, SaveTransaction(io, F.JOURNAL_PATH).readJournal()!!.commitState)
        io.crashPoint = null
        io.ops.clear()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.ExecuteResult.AlreadyCommitted>(tx.execute(request()))
        assertTrue(io.ops.none { it == "WRITE:${F.JOURNAL_PATH}" })
        assertTrue(
            tx.trace.none {
                it == SaveTransaction.EV_JOURNAL_PREPARED ||
                    it == SaveTransaction.EV_JOURNAL_BACKED_UP ||
                    it == SaveTransaction.EV_JOURNAL_COMMITTED
            },
        )
        assertTrue(SaveTransaction.EV_IDEMPOTENT in tx.trace)
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
        assertTrue(io.disk[F.SAV_PART] == null)
        assertTrue(io.disk[F.RT2_PART] == null)
    }

    @Test
    fun `清理_完成后journal与残part消失_bak也轮出`() {
        val io = newIo()
        val tx = SaveTransaction(io, F.JOURNAL_PATH)
        assertIs<SaveTransaction.ExecuteResult.Committed>(tx.execute(request()))
        assertTrue(io.disk[F.JOURNAL_PATH] == null)
        assertTrue(io.disk[F.SAV_PART] == null)
        assertTrue(io.disk[F.RT2_PART] == null)
        // M1-2：`.bak` 只是事务窗口内的回滚源，COMMITTED 后即轮出（era-arch §8 清理矩阵）。
        assertTrue(io.disk[F.SAV_BAK] == null)
        assertTrue(io.disk[F.RT2_BAK] == null)
        assertEquals(sha256Hex(F.SAV_NEW), sha256Hex(io.disk[F.SAV_TARGET]!!))
        assertEquals(sha256Hex(F.RT2_NEW), sha256Hex(io.disk[F.RT2_TARGET]!!))
    }

    // ------------------------------------------------------------------
    // 判据 7：关态哨兵（既有 plan 判定未被编排器改写）
    // ------------------------------------------------------------------

    @Test
    fun `关态_既有SaveRecovery_plan判定未被编排器改写`() {
        val s = F.savMember()
        val r = F.rt2Member()
        val j = F.journal(CommitState.PREPARED, s, r)
        val plan = SaveRecovery.plan(
            j,
            listOf(
                F.obs(s, sha256Hex(F.SAV_NEW), sha256Hex(F.SAV_OLD), sha256Hex(F.SAV_OLD)),
                F.obs(r, sha256Hex(F.RT2_NEW), sha256Hex(F.RT2_OLD), sha256Hex(F.RT2_OLD)),
            ),
        )
        assertIs<RecoveryPlan.KeepOld>(plan)
    }
}
