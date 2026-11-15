package com.eratw.port.core.save

import com.eratw.port.core.save.SaveRecovery.CommitState
import com.eratw.port.core.save.SaveRecovery.Journal
import com.eratw.port.core.save.SaveRecovery.MemberJournal
import com.eratw.port.core.save.SaveRecovery.MemberObservation
import com.eratw.port.core.save.SaveRecovery.RecoveryPlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 存档事务编排器（ERA Runtime 2.0 第 8 节；M1-5 的事务日志层，关闭 12.1 的事务编排半边）。
 *
 * SaveRecovery.plan 是既有恢复判定真源，本类**只驱动它、不改它的判定**：负责按
 * SaveRecovery.kt 第 19 至 26 行的固定写序，把 PREPARED、BACKED_UP、COMMITTED 三态
 * journal 真正落到可恢复介质上，逐成员按三候选 SHA 对账，并在崩溃后把磁盘事实转成
 * MemberObservation 喂回 plan。恢复动作本身（换名、回滚、清理）只以 RecoveryPlan 形式
 * 交回宿主执行，本 lane 不建生产装配点，见交付「需组长接线」。
 *
 * 写序固定为，每一步都经 [SaveTransactionIo]：
 * 两个 .part → SHA 校验 → fsync → PREPARED journal → 写 .bak 对 → 按旧 SHA 校验 →
 * fsync → BACKED_UP journal → 定序替换目标 → 校验新配对 → COMMITTED → 清理。
 * 于是「任何目标被替换之前，完整旧 .bak 对已落盘并校验」成为恢复期可判定的事实。
 *
 * 空间预算（契约 3.1 落地第 3 点）：峰值 = journal 字节 + 各成员 .part 与 .bak 字节之和；
 * 在写第一份 .part 之前比对可用字节，不足即拒绝并带可定位文案，不产出半截事务。
 * 该口径是 RT2 自研（原版存档是单文件原地覆盖写，见反编译 C#：
 * VariableEvaluator.cs:2644 SaveTo、:2653 new FileStream(FileMode.Create)、
 * :2666-2668 catch 吞成 false、:2694 读档 FileMode.Open；
 * SavData 系 VariableData.cs:3980 SaveToStreamBinary；保存语句入口 EmueraConsole.cs:2221），
 * 原版无 sidecar、无 journal、无配对事务，故 journal 序列化形态与空间预算策略
 * 无 C# 锚：TODO(verify) 权威 = 蓝图第 8 节 + SaveRecovery.kt 顺序不变量，禁止脑补 C# 锚。
 * 既有 SaveRecovery.hasSpaceBudget 是第 8 节的 3N 粗粒度真源，本 lane 不修改、不调用它；
 * 恢复判定一律且仅由 plan 给出，空间预算按契约 3.1 的精确峰值在编排层放行。
 *
 * 本类纯 JVM：路径全由调用方注入、无墙钟、无设备 API、无静态可变全局。
 * 运行时写序记录见 [trace]，供「写序钉」双证；源码形状即下列 execute 的语句顺序。
 *
 * **事务身份闸（架构师验收补充）**：execute 只对**同一 transactionId** 的 COMMITTED
 * journal 走幂等；其它事务的未完成 journal ⇒ 拒绝（先 `recover`，不覆盖可恢复状态）；
 * 其它事务的 COMMITTED 残留 ⇒ 视为清理未做完，删 journal 后继续本次保存。
 * 回归闸 `SaveTransactionJournalScopeTest`。
 */
class SaveTransaction(
    private val io: SaveTransactionIo,
    private val journalPath: String,
) {
    /** 一次保存请求：逐成员的旧字节、新字节与三路径。 */
    class MemberSpec(
        val target: String,
        val part: String,
        val bak: String,
        val oldBytes: ByteArray,
        val newBytes: ByteArray,
    )

    class Request(
        val transactionId: String,
        val oldPairGeneration: Long,
        val newPairGeneration: Long,
        val members: List<MemberSpec>,
    ) {
        init {
            require(transactionId.isNotBlank()) { "transactionId 为空" }
            require(members.size in 1..2) { "存档事务成员数必须是 1 或 2，实为 ${members.size}" }
            require(newPairGeneration > oldPairGeneration) { "配对世代必须单调递增" }
            val targets = members.map { it.target }
            require(targets.distinct().size == targets.size) { "事务内 target 路径重复: $targets" }
            for (m in members) {
                require(m.target != m.part && m.target != m.bak && m.part != m.bak) {
                    "成员三路径必须互不相同: ${m.target}"
                }
            }
        }
    }

    /** execute 的收口结果。 */
    sealed interface ExecuteResult {
        /** 正常完成：COMMITTED 已写、清理已做。 */
        data object Committed : ExecuteResult

        /** 重入时磁盘已有 COMMITTED journal：不重复写 journal，只补做清理。 */
        data object AlreadyCommitted : ExecuteResult

        /** 空间预算不足：在任何写入之前拒绝。 */
        data class Rejected(val reason: String) : ExecuteResult
    }

    /** recover 的收口结果；NoJournal 明确表示根本没调用 plan。 */
    sealed interface RecoveryResult {
        data object NoJournal : RecoveryResult
        data class Decided(val journal: Journal, val plan: RecoveryPlan) : RecoveryResult
    }

    /** 运行时写序记录，事件名见各 EV_ 常量。 */
    private val events = ArrayList<String>(32)
    val trace: List<String> get() = events.toList()

    companion object {
        const val EV_BUDGET_OK = "BUDGET_OK"
        const val EV_WRITE_PART = "WRITE_PART"
        const val EV_VERIFY_PART = "VERIFY_PART"
        const val EV_FSYNC_PART = "FSYNC_PART"
        const val EV_JOURNAL_PREPARED = "JOURNAL_PREPARED"
        const val EV_WRITE_BAK = "WRITE_BAK"
        const val EV_VERIFY_BAK = "VERIFY_BAK"
        const val EV_FSYNC_BAK = "FSYNC_BAK"
        const val EV_JOURNAL_BACKED_UP = "JOURNAL_BACKED_UP"
        const val EV_RENAME = "RENAME"
        const val EV_VERIFY_TARGET = "VERIFY_TARGET"
        const val EV_JOURNAL_COMMITTED = "JOURNAL_COMMITTED"
        const val EV_CLEANUP = "CLEANUP"
        const val EV_PLAN_INVOKED = "PLAN_INVOKED"
        const val EV_IDEMPOTENT = "IDEMPOTENT"

        private const val JOURNAL_MAGIC = 0x4a525432
        private const val JOURNAL_VERSION = 1

        /** 把 Journal 确定性序列化为字节，RT2 自研、无 C# 锚、TODO(verify)。 */
        fun encodeJournal(journal: Journal): ByteArray {
            val buf = ByteArrayOutputStream()
            val out = DataOutputStream(buf)
            out.writeInt(JOURNAL_MAGIC)
            out.writeInt(JOURNAL_VERSION)
            out.writeUTF(journal.transactionId)
            out.writeLong(journal.oldPairGeneration)
            out.writeLong(journal.newPairGeneration)
            out.writeInt(journal.commitState.ordinal)
            out.writeInt(journal.members.size)
            for (m in journal.members) {
                out.writeUTF(m.target)
                out.writeUTF(m.part)
                out.writeUTF(m.bak)
                out.writeUTF(m.oldSha256)
                out.writeUTF(m.newSha256)
            }
            out.flush()
            return buf.toByteArray()
        }

        /** 反序列化；字节损坏或形态不符一律抛，恢复期 fail-closed，不静默当无 journal。 */
        fun decodeJournal(bytes: ByteArray): Journal {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == JOURNAL_MAGIC) { "journal 魔数不符" }
            require(input.readInt() == JOURNAL_VERSION) { "journal 版本不支持" }
            val tx = input.readUTF()
            val oldGen = input.readLong()
            val newGen = input.readLong()
            val stateOrd = input.readInt()
            require(stateOrd in CommitState.values().indices) { "journal commitState 越界: $stateOrd" }
            val state = CommitState.values()[stateOrd]
            val count = input.readInt()
            require(count in 1..2) { "journal 成员数越界: $count" }
            val members = ArrayList<MemberJournal>(count)
            repeat(count) {
                members += MemberJournal(
                    input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(),
                )
            }
            require(input.available() == 0) { "journal 尾部多余字节" }
            return Journal(tx, oldGen, newGen, members, state)
        }
    }

    /** 从磁盘读回当前 journal；不存在返回 null。 */
    fun readJournal(): Journal? = io.read(journalPath)?.let { decodeJournal(it) }

    /** 本次事务的峰值占用，空间预算口径 = journal + 各成员 .part 与 .bak 字节。 */
    fun requiredBytes(request: Request): Long {
        val members = memberJournals(request)
        val journalBytes = encodeJournal(journalOf(request, CommitState.PREPARED, members)).size.toLong()
        return journalBytes + request.members.sumOf { m ->
            m.newBytes.size.toLong() + m.oldBytes.size.toLong()
        }
    }

    /**
     * 按固定写序推进一次保存。崩溃由 [SaveTransactionIo] 在任意两步之间抛出，
     * 磁盘上留下的就是可喂 SaveRecovery.plan 的真实事实。
     */
    fun execute(request: Request): ExecuteResult {
        val members = memberJournals(request)

        // 1. 空间预算：写第一份 .part 之前的唯一放行门，不许先写再检查。
        val required = requiredBytes(request)
        val available = io.availableBytes()
        if (available < required) {
            return ExecuteResult.Rejected(
                "存档事务空间预算不足: transactionId=${request.transactionId} " +
                    "需要=${required}B(journal + 两 .part + .bak) " +
                    "可用=${available}B 逐成员新/旧字节=" +
                    request.members.joinToString { "${it.newBytes.size}/${it.oldBytes.size}" } +
                    "；已在任何写入之前拒绝，不产出半截事务",
            )
        }
        events += EV_BUDGET_OK

        // 2. 事务身份 / 前置闸：只对「同一事务的 COMMITTED」走幂等；其它残留 journal——
        //    已提交的视为清理残留（删 journal 后继续本次保存），未完成的必须先 recover，
        //    拒绝开始新事务（fail-closed，不覆盖可恢复的 journal）。
        val existing = readJournal()
        if (existing != null) {
            when {
                existing.commitState == CommitState.COMMITTED &&
                    existing.transactionId == request.transactionId -> {
                    events += EV_IDEMPOTENT
                    cleanup(request)
                    return ExecuteResult.AlreadyCommitted
                }
                existing.commitState == CommitState.COMMITTED -> {
                    // 其它事务已提交（目标已校验并落 COMMITTED），残留 journal 只是清理没做完。
                    io.delete(journalPath)
                }
                else -> {
                    return ExecuteResult.Rejected(
                        "磁盘存在未完成事务 journal：transactionId=${existing.transactionId} " +
                            "state=${existing.commitState}；必须先 recover 并由宿主执行恢复计划，" +
                            "再开始新的保存（拒绝覆盖可恢复状态）",
                    )
                }
            }
        }

        // 3. 两个 .part。
        for (m in request.members) {
            io.write(m.part, m.newBytes)
            events += EV_WRITE_PART
        }

        // 4. SHA 校验：写出的 .part 必须与 journal 的 newSha256 对得上。
        for ((m, j) in request.members.zip(members)) {
            val got = io.read(m.part)?.let { sha256Hex(it) }
            require(got == j.newSha256) { ".part 指纹不符: ${m.part} 期望 ${j.newSha256} 实测 $got" }
            events += EV_VERIFY_PART
        }

        // 5. fsync .part。
        for (m in request.members) {
            io.fsync(m.part)
            events += EV_FSYNC_PART
        }

        // 6. PREPARED journal：此刻目标一个都没动、.bak 对未必完整。
        writeJournal(journalOf(request, CommitState.PREPARED, members), EV_JOURNAL_PREPARED)

        // 7. 写 .bak 对，旧版本字节。
        for (m in request.members) {
            io.write(m.bak, m.oldBytes)
            events += EV_WRITE_BAK
        }

        // 8. 按旧 SHA 校验 .bak。
        for ((m, j) in request.members.zip(members)) {
            val got = io.read(m.bak)?.let { sha256Hex(it) }
            require(got == j.oldSha256) { ".bak 指纹不符: ${m.bak} 期望 ${j.oldSha256} 实测 $got" }
            events += EV_VERIFY_BAK
        }

        // 9. fsync .bak。
        for (m in request.members) {
            io.fsync(m.bak)
            events += EV_FSYNC_BAK
        }

        // 10. BACKED_UP journal：证明完整旧 .bak 对已落盘并校验，先于任何定序替换。
        writeJournal(journalOf(request, CommitState.BACKED_UP, members), EV_JOURNAL_BACKED_UP)

        // 11. 定序替换目标；断点 4 落在两次换名之间。
        for (m in request.members) {
            io.rename(m.part, m.target)
            events += EV_RENAME
        }

        // 12. 校验新配对，两个目标都必须是新版本。
        for ((m, j) in request.members.zip(members)) {
            val got = io.read(m.target)?.let { sha256Hex(it) }
            require(got == j.newSha256) { "目标新配对指纹不符: ${m.target} 期望 ${j.newSha256} 实测 $got" }
            events += EV_VERIFY_TARGET
        }

        // 13. COMMITTED，两件都校验通过之后。
        writeJournal(journalOf(request, CommitState.COMMITTED, members), EV_JOURNAL_COMMITTED)

        // 14. 清理。
        cleanup(request)
        return ExecuteResult.Committed
    }

    /**
     * 崩溃恢复的判定面：把磁盘上真实留下的 journal 与逐成员三候选
     * （.part、已替换的目标、.bak 旧版本）转成 MemberObservation，喂 SaveRecovery.plan。
     *
     * 无 journal 直接返回 NoJournal，**根本不调用 plan**（矩阵第 1 点，旧档原样）。
     * plan 为 Halt 时仅返回判定、不执行任何换名或删除：停止并报告，不静默回滚、
     * 不静默销毁旧档。动作的执行一律交宿主，本 lane 不建生产装配点。
     */
    fun recover(): RecoveryResult {
        val bytes = io.read(journalPath) ?: return RecoveryResult.NoJournal
        val journal = decodeJournal(bytes)
        val observations = journal.members.map { m ->
            MemberObservation(
                m.target,
                partSha256 = io.read(m.part)?.let { sha256Hex(it) },
                targetSha256 = io.read(m.target)?.let { sha256Hex(it) },
                bakSha256 = io.read(m.bak)?.let { sha256Hex(it) },
            )
        }
        events += EV_PLAN_INVOKED
        val plan = SaveRecovery.plan(journal, observations)
        return RecoveryResult.Decided(journal, plan)
    }

    private fun memberJournals(request: Request): List<MemberJournal> =
        request.members.map {
            MemberJournal(
                it.target, it.part, it.bak,
                sha256Hex(it.oldBytes), sha256Hex(it.newBytes),
            )
        }

    private fun journalOf(request: Request, state: CommitState, members: List<MemberJournal>): Journal =
        Journal(request.transactionId, request.oldPairGeneration, request.newPairGeneration, members, state)

    private fun writeJournal(journal: Journal, event: String) {
        io.write(journalPath, encodeJournal(journal))
        io.fsync(journalPath)
        events += event
    }

    /**
     * 正常完成后的清理：删 journal 与残 `.part`，随后**轮出 `.bak`**（M1-2）。
     *
     * `.bak` 是事务窗口内的回滚源，不是玩家面历史备份：era-arch §8 恢复矩阵
     * 「COMMITTED 已写、清理未完成 ⇒ 保留新版本、安全清理残留 `.part`/`.bak` 事务件」。
     * 四个出口自此一致（正常提交 / KeepOld / RollForward / RollBack 都不留残件），
     * 下一次保存从头写 `.bak`，不存在跨事务累积。
     *
     * 清序 = `.part` → journal → `.bak`：journal 先失效，半清理崩溃不会被下一轮误判成
     * 「仍有可恢复事务」；此时目标已是新配对且 COMMITTED 已落，`.bak` 只是残件。
     */
    private fun cleanup(request: Request) {
        for (m in request.members) io.delete(m.part)
        io.delete(journalPath)
        for (m in request.members) io.delete(m.bak)
        events += EV_CLEANUP
    }
}
