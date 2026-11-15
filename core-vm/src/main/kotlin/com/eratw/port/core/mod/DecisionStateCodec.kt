package com.eratw.port.core.mod

import com.eratw.port.modapi.ReservationKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * `DecisionPipeline` 决策态的二进制编解码（M6-2，§21.3.5 + §8；纯 JVM，无静态可变全局）。
 *
 * 决策态 = 决策种子 + 抽取序号 + 每日/每行动者计数 + 冷却 + 占用中的预留（[DecisionState]）。
 * 本编解码器把它落成确定性字节，供 `.rt2` 按 PackageId 隔离持久化；真正的 sidecar 分段
 * 装配归宿主接线（M4-3/K3），本对象只做纯编解码与状态往返。
 *
 * ## 格式（段序固定）
 *
 * ```
 * i32 MAGIC = 0x44533252
 * i32 VERSION = 2
 * i64 decisionSeed
 * i32 nextOrdinal（>= 0）
 * i32 dayCounts.count（0..2^20）
 *   重复：UTF packageId、UTF candidateKey、i64 day、i32 count（>= 0）
 * i32 actorCounts.count（0..2^20）
 *   重复：UTF packageId、UTF candidateKey、i64 day、i64 actorRaw、i32 count（>= 0）
 * i32 cooldownUntil.count（0..2^20）
 *   重复：UTF scopedKey、i64 untilDay
 * i32 heldReservations.count（0..2^20）
 *   重复：UTF kind、UTF key
 * ```
 *
 * 字符串一律 `writeUTF`（不超过 65535 字节）；Long/Int 定长。所有列表按确定性键排序：
 * dayCounts/actorCounts 按包、候选、日、行动者字典序，cooldown 按桶键，held 按 kind+key
 * ⇒ 同一状态的字节流确定。
 *
 * 解码 fail-closed：魔数/版本/条目数越界/空键/负计数/负 nextOrdinal/重复键/截断/尾部多余
 * 字节一律抛 [IllegalArgumentException]，不返回半成品。版本纪律：只接受 [VERSION] = 2，
 * 版本 1 与任何其它版本都明示拒载（不双版本读取）。
 *
 * TODO(verify)：原版无决策态 sidecar，本格式无 C# 锚；权威 = 蓝图 §21.3.5 + §8。
 */
object DecisionStateCodec {

    private const val MAGIC = 0x44533252
    private const val VERSION = 2
    private const val MAX_ENTRIES = 1 shl 20
    private const val MAX_UTF_BYTES = 65535

    /** 编码为确定性字节（列表一律按确定性键排序）。 */
    fun encode(state: DecisionState): ByteArray {
        require(state.nextOrdinal >= 0) { "决策态 nextOrdinal 不能为负：${state.nextOrdinal}" }
        val dayEntries = state.dayCounts.sortedWith(
            compareBy({ it.packageId }, { it.candidateKey }, { it.day }),
        )
        require(dayEntries.size <= MAX_ENTRIES) { "决策态 dayCounts 条目数越界：${dayEntries.size}" }
        val daySeen = HashSet<DayKey>(dayEntries.size * 2)
        for (entry in dayEntries) {
            requireKey(entry.packageId, "dayCounts 包名")
            requireKey(entry.candidateKey, "dayCounts 候选键")
            require(entry.count >= 0) { "决策态 dayCounts 计数不能为负：${entry.count}" }
            require(daySeen.add(DayKey(entry.packageId, entry.candidateKey, entry.day))) {
                "决策态 dayCounts 键重复：${entry.packageId}/${entry.candidateKey}@${entry.day}"
            }
        }
        val actorEntries = state.actorCounts.sortedWith(
            compareBy({ it.packageId }, { it.candidateKey }, { it.day }, { it.actorRaw }),
        )
        require(actorEntries.size <= MAX_ENTRIES) {
            "决策态 actorCounts 条目数越界：${actorEntries.size}"
        }
        val actorSeen = HashSet<ActorKey>(actorEntries.size * 2)
        for (entry in actorEntries) {
            requireKey(entry.packageId, "actorCounts 包名")
            requireKey(entry.candidateKey, "actorCounts 候选键")
            require(entry.count >= 0) { "决策态 actorCounts 计数不能为负：${entry.count}" }
            val key = ActorKey(entry.packageId, entry.candidateKey, entry.day, entry.actorRaw)
            require(actorSeen.add(key)) {
                "决策态 actorCounts 键重复：${entry.packageId}/${entry.candidateKey}" +
                    "@${entry.day}/${entry.actorRaw}"
            }
        }
        val cooldownEntries = state.cooldownUntil.sortedBy { it.scopedKey }
        require(cooldownEntries.size <= MAX_ENTRIES) {
            "决策态 cooldownUntil 条目数越界：${cooldownEntries.size}"
        }
        val cooldownSeen = HashSet<String>(cooldownEntries.size * 2)
        for (entry in cooldownEntries) {
            requireKey(entry.scopedKey, "cooldownUntil 桶键")
            require(cooldownSeen.add(entry.scopedKey)) {
                "决策态 cooldownUntil 键重复：${entry.scopedKey}"
            }
        }
        val heldEntries = state.heldReservations.sortedWith(compareBy({ it.kind }, { it.key }))
        require(heldEntries.size <= MAX_ENTRIES) {
            "决策态 heldReservations 条目数越界：${heldEntries.size}"
        }
        val heldSeen = HashSet<ReservationKey>(heldEntries.size * 2)
        for (entry in heldEntries) {
            requireKey(entry.kind, "heldReservations kind")
            requireKey(entry.key, "heldReservations key")
            require(heldSeen.add(entry)) {
                "决策态 heldReservations 键重复：${entry.kind}/${entry.key}"
            }
        }

        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(state.decisionSeed)
        out.writeInt(state.nextOrdinal)
        out.writeInt(dayEntries.size)
        for (entry in dayEntries) {
            out.writeUTF(entry.packageId)
            out.writeUTF(entry.candidateKey)
            out.writeLong(entry.day)
            out.writeInt(entry.count)
        }
        out.writeInt(actorEntries.size)
        for (entry in actorEntries) {
            out.writeUTF(entry.packageId)
            out.writeUTF(entry.candidateKey)
            out.writeLong(entry.day)
            out.writeLong(entry.actorRaw)
            out.writeInt(entry.count)
        }
        out.writeInt(cooldownEntries.size)
        for (entry in cooldownEntries) {
            out.writeUTF(entry.scopedKey)
            out.writeLong(entry.untilDay)
        }
        out.writeInt(heldEntries.size)
        for (entry in heldEntries) {
            out.writeUTF(entry.kind)
            out.writeUTF(entry.key)
        }
        out.flush()
        return buf.toByteArray()
    }
    /** 解码决策态；畸形字节一律 fail-closed。 */
    fun decode(bytes: ByteArray): DecisionState = try {
        decodeInner(bytes)
    } catch (e: EOFException) {
        throw IllegalArgumentException("决策态截断", e)
    } catch (e: IOException) {
        throw IllegalArgumentException("决策态字节流畸形", e)
    }

    private fun decodeInner(bytes: ByteArray): DecisionState {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "决策态魔数不符" }
        val version = input.readInt()
        require(version == VERSION) { "决策态只接受版本 2（读到版本 $version）" }
        val seed = input.readLong()
        val ordinal = input.readInt()
        require(ordinal >= 0) { "决策态 nextOrdinal 不能为负：$ordinal" }

        val dayCount = readCount(input, "dayCounts")
        val days = ArrayList<DayCountEntry>(dayCount)
        val daySeen = HashSet<DayKey>(dayCount * 2)
        repeat(dayCount) {
            val packageId = readKey(input, "dayCounts 包名")
            val candidateKey = readKey(input, "dayCounts 候选键")
            val day = input.readLong()
            val count = input.readInt()
            require(count >= 0) { "决策态 dayCounts 计数不能为负：$count" }
            require(daySeen.add(DayKey(packageId, candidateKey, day))) {
                "决策态 dayCounts 键重复：$packageId/$candidateKey@$day"
            }
            days += DayCountEntry(packageId, candidateKey, day, count)
        }

        val actorCount = readCount(input, "actorCounts")
        val actors = ArrayList<ActorCountEntry>(actorCount)
        val actorSeen = HashSet<ActorKey>(actorCount * 2)
        repeat(actorCount) {
            val packageId = readKey(input, "actorCounts 包名")
            val candidateKey = readKey(input, "actorCounts 候选键")
            val day = input.readLong()
            val actorRaw = input.readLong()
            val count = input.readInt()
            require(count >= 0) { "决策态 actorCounts 计数不能为负：$count" }
            require(actorSeen.add(ActorKey(packageId, candidateKey, day, actorRaw))) {
                "决策态 actorCounts 键重复：$packageId/$candidateKey@$day/$actorRaw"
            }
            actors += ActorCountEntry(packageId, candidateKey, day, actorRaw, count)
        }

        val cooldownCount = readCount(input, "cooldownUntil")
        val cooldowns = ArrayList<CooldownEntry>(cooldownCount)
        val cooldownSeen = HashSet<String>(cooldownCount * 2)
        repeat(cooldownCount) {
            val scopedKey = readKey(input, "cooldownUntil 桶键")
            val untilDay = input.readLong()
            require(cooldownSeen.add(scopedKey)) {
                "决策态 cooldownUntil 键重复：$scopedKey"
            }
            cooldowns += CooldownEntry(scopedKey, untilDay)
        }

        val heldCount = readCount(input, "heldReservations")
        val held = ArrayList<ReservationKey>(heldCount)
        val heldSeen = HashSet<ReservationKey>(heldCount * 2)
        repeat(heldCount) {
            val kind = readKey(input, "heldReservations kind")
            val key = readKey(input, "heldReservations key")
            val reservation = ReservationKey(kind, key)
            require(heldSeen.add(reservation)) {
                "决策态 heldReservations 键重复：$kind/$key"
            }
            held += reservation
        }

        require(input.available() == 0) { "决策态尾部多余字节" }
        return DecisionState(seed, ordinal, days, actors, cooldowns, held)
    }

    private fun readCount(input: DataInputStream, segment: String): Int {
        val count = input.readInt()
        require(count in 0..MAX_ENTRIES) { "决策态 $segment 条目数越界：$count" }
        return count
    }

    private fun readKey(input: DataInputStream, field: String): String {
        val value = input.readUTF()
        require(value.isNotBlank()) { "决策态 $field 键为空" }
        return value
    }

    private fun requireKey(value: String, field: String) {
        require(value.isNotBlank()) { "决策态 $field 键为空" }
        val length = utfLength(value)
        require(length <= MAX_UTF_BYTES) { "决策态 $field 键超长：$length 字节" }
    }

    /** `writeUTF` 的 modified UTF-8 长度（含 U+0000 的两字节与代理对的各三字节）。 */
    private fun utfLength(value: String): Int {
        var length = 0
        for (ch in value) {
            val code = ch.code
            length += when {
                code == 0 -> 2
                code < 0x80 -> 1
                code < 0x800 -> 2
                else -> 3
            }
        }
        return length
    }

    private data class DayKey(
        val packageId: String,
        val candidateKey: String,
        val day: Long,
    )

    private data class ActorKey(
        val packageId: String,
        val candidateKey: String,
        val day: Long,
        val actorRaw: Long,
    )
}
