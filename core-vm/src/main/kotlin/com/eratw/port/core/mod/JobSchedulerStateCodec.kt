package com.eratw.port.core.mod

import com.eratw.port.modapi.EntityId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * `JobScheduler` 任务态的二进制编解码（§20.2 / §21.1，ABI-10 Candidate；纯 JVM，无静态可变全局）。
 *
 * 任务态 = [JobSchedulerSnapshot]：静态槽「jobKey → 下次触发日」+ 动态实例列表 + 实例序号表。
 * 本编解码器把它落成确定性字节，供 `.rt2` 按 PackageId 隔离持久化（§8）；真正的 sidecar
 * 分段装配归宿主接线（K3/M4-3）。
 *
 * ## 格式（VERSION 2）
 *
 * ```
 * i32 MAGIC = 0x4A533452
 * i32 VERSION = 2
 * i32 staticCount（0..2^20）
 * 重复 staticCount 次：
 *   UTF jobKey（非空、<= 65535 字节）
 *   i64 nextFireDay（>= 0；Long.MAX_VALUE = 一次性任务已完成）
 * i32 instanceCount（0..2^20）
 * 重复 instanceCount 次：
 *   UTF packageId（非空）
 *   UTF templateKey（非空）
 *   i64 seq（>= 0）
 *   i64 atDay（>= 0）
 *   B payload 是否存在；存在则 i64 payloadRef.raw
 *   UTF state（PENDING / DONE / CANCELED，精确匹配）
 * i32 seqCount（0..2^20）
 * 重复 seqCount 次：
 *   UTF scopeKey（packageId::templateKey，非空）
 *   i64 nextSeq（>= 0）
 * ```
 *
 * 编码按静态键、实例键、序号表键字典序排序 ⇒ 同一快照的字节流确定。解码 fail-closed：
 * 魔数/版本/条目数/空键/负触发日/负序号/重复键/非法状态/截断/尾部多余字节一律抛
 * [IllegalArgumentException]，不返回半成品。版本纪律：只接受 VERSION = 2，`VERSION = 1`
 * 及任何其它版本明示拒载（消息点名「任务态只接受版本 2」），不做双版本读取。
 *
 * TODO(verify)：原版无 sidecar/job 调度器，本格式无 C# 锚；权威 = 蓝图 §8 + §20.2 + §21.1。
 */
object JobSchedulerStateCodec {

    private const val MAGIC = 0x4A533452
    private const val VERSION = 2
    private const val MAX_ENTRIES = 1 shl 20
    private const val MAX_UTF_BYTES = 65535
    private val STATES: Map<String, JobState> = enumValues<JobState>().associateBy { it.name }

    /** 编码为确定性字节（静态键 / 实例键 / 序号表键各自字典序）。 */
    fun encode(snapshot: JobSchedulerSnapshot): ByteArray {
        val staticEntries = snapshot.nextFireDays.entries.sortedBy { it.key }
        val instanceEntries = snapshot.instances.sortedBy { it.instanceKey }
        val seqEntries = snapshot.nextSeqByTemplate.entries.sortedBy { it.key }
        require(staticEntries.size <= MAX_ENTRIES) { "任务态静态条目数越界: ${staticEntries.size}" }
        require(instanceEntries.size <= MAX_ENTRIES) { "任务态实例数越界: ${instanceEntries.size}" }
        require(seqEntries.size <= MAX_ENTRIES) { "任务态序号表条目数越界: ${seqEntries.size}" }
        for ((key, next) in staticEntries) {
            require(key.isNotBlank()) { "任务态键为空" }
            require(next >= 0L) { "任务态下次触发日不能为负: $key=$next" }
            requireUtf(key, "任务态键")
        }
        val instanceKeys = HashSet<String>(instanceEntries.size * 2)
        for (instance in instanceEntries) {
            require(instance.packageId.isNotBlank()) { "任务态实例 packageId 为空" }
            require(instance.templateKey.isNotBlank()) { "任务态实例 templateKey 为空" }
            require(instance.seq >= 0L) { "任务态实例序号不能为负: ${instance.instanceKey}" }
            require(instance.atDay >= 0L) { "任务态实例触发日不能为负: ${instance.instanceKey}" }
            require(instanceKeys.add(instance.instanceKey)) {
                "任务态实例键重复: ${instance.instanceKey}"
            }
            requireUtf(instance.packageId, "任务态实例 packageId")
            requireUtf(instance.templateKey, "任务态实例 templateKey")
            require(instance.state in STATES.values) { "任务态实例状态非法: ${instance.state}" }
        }
        for ((scope, next) in seqEntries) {
            require(scope.isNotBlank()) { "任务态序号键为空" }
            require(next >= 0L) { "任务态序号不能为负: $scope=$next" }
            requireUtf(scope, "任务态序号键")
        }

        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(staticEntries.size)
        for ((key, next) in staticEntries) {
            out.writeUTF(key)
            out.writeLong(next)
        }
        out.writeInt(instanceEntries.size)
        for (instance in instanceEntries) {
            out.writeUTF(instance.packageId)
            out.writeUTF(instance.templateKey)
            out.writeLong(instance.seq)
            out.writeLong(instance.atDay)
            val payload = instance.payloadRef
            out.writeBoolean(payload != null)
            if (payload != null) out.writeLong(payload.raw)
            out.writeUTF(instance.state.name)
        }
        out.writeInt(seqEntries.size)
        for ((scope, next) in seqEntries) {
            out.writeUTF(scope)
            out.writeLong(next)
        }
        out.flush()
        return buf.toByteArray()
    }

    /** 解码任务态；畸形字节一律 fail-closed。 */
    fun decode(bytes: ByteArray): JobSchedulerSnapshot = try {
        decodeInner(bytes)
    } catch (e: EOFException) {
        throw IllegalArgumentException("任务态截断", e)
    }

    private fun decodeInner(bytes: ByteArray): JobSchedulerSnapshot {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "任务态魔数不符" }
        val version = input.readInt()
        require(version == VERSION) {
            "任务态只接受版本 2（实际 $version）；旧 v1 不双版本读取，请用 v2 编码器重出任务态"
        }

        val staticCount = input.readInt()
        require(staticCount in 0..MAX_ENTRIES) { "任务态条目数越界: $staticCount" }
        val nextFireDays = LinkedHashMap<String, Long>(staticCount * 2)
        repeat(staticCount) {
            val key = input.readUTF()
            val next = input.readLong()
            require(key.isNotBlank()) { "任务态键为空" }
            require(next >= 0L) { "任务态下次触发日不能为负: $key=$next" }
            require(nextFireDays.put(key, next) == null) { "任务态键重复: $key" }
        }

        val instanceCount = input.readInt()
        require(instanceCount in 0..MAX_ENTRIES) { "任务态实例数越界: $instanceCount" }
        val instances = ArrayList<JobInstance>(instanceCount)
        val instanceKeys = HashSet<String>(instanceCount * 2)
        repeat(instanceCount) {
            val packageId = input.readUTF()
            val templateKey = input.readUTF()
            val seq = input.readLong()
            val atDay = input.readLong()
            val hasPayload = input.readBoolean()
            val payload = if (hasPayload) EntityId(input.readLong()) else null
            val stateName = input.readUTF()
            val state = STATES[stateName]
                ?: throw IllegalArgumentException("任务态实例状态非法: $stateName")
            require(packageId.isNotBlank()) { "任务态实例 packageId 为空" }
            require(templateKey.isNotBlank()) { "任务态实例 templateKey 为空" }
            require(seq >= 0L) { "任务态实例序号不能为负: $packageId::$templateKey#$seq" }
            require(atDay >= 0L) { "任务态实例触发日不能为负: $packageId::$templateKey#$seq" }
            val instance = JobInstance(packageId, templateKey, seq, atDay, payload, state)
            require(instanceKeys.add(instance.instanceKey)) {
                "任务态实例键重复: ${instance.instanceKey}"
            }
            instances += instance
        }

        val seqCount = input.readInt()
        require(seqCount in 0..MAX_ENTRIES) { "任务态序号表条目数越界: $seqCount" }
        val nextSeq = LinkedHashMap<String, Long>(seqCount * 2)
        repeat(seqCount) {
            val scope = input.readUTF()
            val next = input.readLong()
            require(scope.isNotBlank()) { "任务态序号键为空" }
            require(next >= 0L) { "任务态序号不能为负: $scope=$next" }
            require(nextSeq.put(scope, next) == null) { "任务态序号键重复: $scope" }
        }

        require(input.available() == 0) { "任务态尾部多余字节" }
        return JobSchedulerSnapshot(nextFireDays, instances, nextSeq)
    }

    private fun requireUtf(value: String, label: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_UTF_BYTES) { "$label 超长: $value" }
    }
}
