package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveRecovery.Journal
import com.eratw.port.core.save.SaveRecovery.MemberJournal
import com.eratw.port.core.save.SaveRecovery.MemberObservation

/** 崩溃/断电注入点：第 [n] 次（1-based）[kind] 类 I/O 操作执行之前抛 [SimulatedCrash]。 */
data class CrashPoint(val kind: OpKind, val n: Int)

enum class OpKind { WRITE_JOURNAL, WRITE_PART, WRITE_BAK, FSYNC, RENAME, DELETE }

class SimulatedCrash(message: String) : RuntimeException(message)

/**
 * 内存假磁盘。所有写类操作执行前经 [hit] 计数，命中 [crashPoint] 即抛 [SimulatedCrash]
 * 且该操作不执行——于是磁盘上留下的就是崩溃瞬间真实的部分状态，可直接转 MemberObservation。
 * read 与 availableBytes 不构成崩溃落点，只记日志。
 */
class FakeTransactionIo(
    var freeBytes: Long = Long.MAX_VALUE / 4,
    var crashPoint: CrashPoint? = null,
) : SaveTransactionIo {
    val disk = HashMap<String, ByteArray>()
    val ops = ArrayList<String>()
    private val counters = HashMap<OpKind, Int>()

    private fun kindOfWrite(path: String): OpKind = when {
        path.endsWith(".part") -> OpKind.WRITE_PART
        path.endsWith(".bak") -> OpKind.WRITE_BAK
        else -> OpKind.WRITE_JOURNAL
    }

    private fun hit(kind: OpKind) {
        val n = (counters[kind] ?: 0) + 1
        counters[kind] = n
        val c = crashPoint
        if (c != null && c.kind == kind && c.n == n) {
            throw SimulatedCrash("断电注入：$kind 第 $n 次操作执行前")
        }
    }

    override fun read(path: String): ByteArray? {
        ops += "READ:$path"
        return disk[path]?.copyOf()
    }

    override fun write(path: String, bytes: ByteArray) {
        hit(kindOfWrite(path))
        ops += "WRITE:$path"
        disk[path] = bytes.copyOf()
    }

    override fun fsync(path: String) {
        hit(OpKind.FSYNC)
        ops += "FSYNC:$path"
    }

    override fun rename(from: String, to: String) {
        hit(OpKind.RENAME)
        ops += "RENAME:$from->$to"
        val b = disk.remove(from) ?: throw IllegalStateException("换名源不存在: $from")
        disk[to] = b
    }

    override fun delete(path: String) {
        hit(OpKind.DELETE)
        ops += "DELETE:$path"
        disk.remove(path)
    }

    override fun availableBytes(): Long {
        ops += "AVAILABLE"
        return freeBytes
    }
}

/** 六点矩阵装置：可控 fake I/O、断点注入、journal 与观测构造器。 */
object SaveTransactionFixture {
    const val JOURNAL_PATH = "save01.sav.tx"
    const val SAV_TARGET = "save01.sav"
    const val SAV_PART = "save01.sav.part"
    const val SAV_BAK = "save01.sav.bak"
    const val RT2_TARGET = "save01.sav.rt2"
    const val RT2_PART = "save01.sav.rt2.part"
    const val RT2_BAK = "save01.sav.rt2.bak"

    val SAV_OLD = "sav-old-v1".toByteArray()
    val SAV_NEW = "sav-new-v2".toByteArray()
    val RT2_OLD = "rt2-old-v1".toByteArray()
    val RT2_NEW = "rt2-new-v2".toByteArray()

    fun savSpec(): SaveTransaction.MemberSpec =
        SaveTransaction.MemberSpec(SAV_TARGET, SAV_PART, SAV_BAK, SAV_OLD, SAV_NEW)

    fun rt2Spec(): SaveTransaction.MemberSpec =
        SaveTransaction.MemberSpec(RT2_TARGET, RT2_PART, RT2_BAK, RT2_OLD, RT2_NEW)

    fun request(members: Int = 2): SaveTransaction.Request = SaveTransaction.Request(
        "tx-m15", 7, 8,
        if (members == 2) listOf(savSpec(), rt2Spec()) else listOf(savSpec()),
    )

    fun savMember(): MemberJournal = MemberJournal(
        SAV_TARGET, SAV_PART, SAV_BAK, sha256Hex(SAV_OLD), sha256Hex(SAV_NEW),
    )

    fun rt2Member(): MemberJournal = MemberJournal(
        RT2_TARGET, RT2_PART, RT2_BAK, sha256Hex(RT2_OLD), sha256Hex(RT2_NEW),
    )

    fun journal(state: CommitState, vararg members: MemberJournal): Journal =
        Journal("tx-m15", 7, 8, members.toList(), state)

    fun obs(m: MemberJournal, part: String?, target: String?, bak: String?): MemberObservation =
        MemberObservation(m.target, part, target, bak)

    /** 预置磁盘上的旧目标对，模拟一次保存开始前磁盘已有的旧档。 */
    fun seededIo(
        crashPoint: CrashPoint? = null,
        members: Int = 2,
        freeBytes: Long = Long.MAX_VALUE / 4,
    ): FakeTransactionIo {
        val io = FakeTransactionIo(freeBytes, crashPoint)
        io.disk[SAV_TARGET] = SAV_OLD.copyOf()
        if (members == 2) io.disk[RT2_TARGET] = RT2_OLD.copyOf()
        return io
    }

    /** 直接把 journal 放到磁盘上，不经 io.write 以免触发崩溃注入。 */
    fun writeJournalToDisk(io: FakeTransactionIo, journal: Journal) {
        io.disk[JOURNAL_PATH] = SaveTransaction.encodeJournal(journal)
    }

    /** 把磁盘三候选实测 SHA 转成 MemberObservation，供三候选对账的交叉验证。 */
    fun observeFromDisk(io: FakeTransactionIo, journal: Journal): List<MemberObservation> =
        journal.members.map { m ->
            MemberObservation(
                m.target,
                io.disk[m.part]?.let { sha256Hex(it) },
                io.disk[m.target]?.let { sha256Hex(it) },
                io.disk[m.bak]?.let { sha256Hex(it) },
            )
        }

    /** 在指定断点注入崩溃后，返回 fake 磁盘与承载写序记录的编排器。 */
    fun crashedExecute(point: CrashPoint, members: Int = 2): Pair<FakeTransactionIo, SaveTransaction> {
        val io = seededIo(point, members)
        val tx = SaveTransaction(io, JOURNAL_PATH)
        try {
            tx.execute(request(members))
        } catch (_: SimulatedCrash) {
            // 崩溃落在断点，磁盘保留部分状态
        }
        return io to tx
    }
}
