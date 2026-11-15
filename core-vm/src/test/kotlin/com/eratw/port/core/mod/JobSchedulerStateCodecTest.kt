package com.eratw.port.core.mod

import com.eratw.port.core.patch.DeclaredJob
import com.eratw.port.core.patch.DeclaredJobTemplate
import com.eratw.port.core.patch.JobDeclaration
import com.eratw.port.core.patch.JobTemplateDeclaration
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.GameTime
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JobSchedulerStateCodec 契约闸（§20.2 / §21.1，ABI-10 Candidate）。
 *
 * 钉住：空态与满态（静态 nextFire + 动态实例 + 序号表）往返、字节确定性（与插入序无关）、
 * DONE 哨兵往返、调度器快照端到端往返与序号接续，以及魔数 / 版本 / 条目数 / 空键 /
 * 负触发日 / 负序号 / 重复键 / 非法状态 / 截断 / 尾部多余字节一律 fail-closed。
 *
 * 版本纪律：只接受 VERSION=2；VERSION=1 明示拒载（消息点名「任务态只接受版本 2」）。
 */
class JobSchedulerStateCodecTest {

    private fun snapshot(vararg pairs: Pair<String, Long>): JobSchedulerSnapshot =
        JobSchedulerSnapshot(mapOf(*pairs))

    @Test
    fun `空态往返`() {
        val decoded = JobSchedulerStateCodec.decode(JobSchedulerStateCodec.encode(snapshot()))
        assertTrue(decoded.nextFireDays.isEmpty())
        assertTrue(decoded.instances.isEmpty())
        assertTrue(decoded.nextSeqByTemplate.isEmpty())
    }

    @Test
    fun `字节确定性_与插入序无关`() {
        val a = snapshot("pkgB::j2" to 15L, "pkgA::j1" to 10L)
        val b = snapshot("pkgA::j1" to 10L, "pkgB::j2" to 15L)
        assertContentEquals(JobSchedulerStateCodec.encode(a), JobSchedulerStateCodec.encode(b))
        val decoded = JobSchedulerStateCodec.decode(JobSchedulerStateCodec.encode(a))
        assertEquals(mapOf("pkgA::j1" to 10L, "pkgB::j2" to 15L), decoded.nextFireDays)
    }

    @Test
    fun `DONE哨兵往返不复活`() {
        val decoded = JobSchedulerStateCodec.decode(
            JobSchedulerStateCodec.encode(snapshot("pkgA::once" to JobScheduler.DONE)),
        )
        assertEquals(JobScheduler.DONE, decoded.nextFireDays["pkgA::once"])
    }

    @Test
    fun `满状态往返_静态加实例加序号表且字节确定`() {
        val snap = JobSchedulerSnapshot(
            nextFireDays = mapOf("pkgA::j1" to JobScheduler.DONE, "pkgB::j2" to 15L),
            instances = listOf(
                JobInstance("pkgA", "tpl", 0L, 10L, EntityId(42L), JobState.PENDING),
                JobInstance("pkgA", "tpl", 1L, 5L, null, JobState.DONE),
                JobInstance("pkgB", "tpl2", 7L, 3L, EntityId.NULL, JobState.CANCELED),
            ),
            nextSeqByTemplate = mapOf("pkgA::tpl" to 2L, "pkgB::tpl2" to 8L),
        )
        val b1 = JobSchedulerStateCodec.encode(snap)
        val b2 = JobSchedulerStateCodec.encode(snap)
        assertContentEquals(b1, b2)
        val decoded = JobSchedulerStateCodec.decode(b1)
        assertEquals(snap, decoded)
        assertContentEquals(b1, JobSchedulerStateCodec.encode(decoded))

        // 实例插入序不同 ⇒ 字节相同（编码按实例键排序）。
        val reordered = snap.copy(instances = snap.instances.reversed())
        assertContentEquals(b1, JobSchedulerStateCodec.encode(reordered))
    }

    @Test
    fun `调度器快照端到端往返_实例状态与载荷保真`() {
        val declarations = listOf(
            DeclaredJob("pkgA", JobDeclaration("once", "DAY_END", 10L, null, 0)),
            DeclaredJob("pkgA", JobDeclaration("periodic", "DAY_END", 5L, 3L, 0)),
        )
        val templates = listOf(
            DeclaredJobTemplate("pkgA", JobTemplateDeclaration("tpl", "DAY_END")),
        )
        val scheduler = JobScheduler.of(declarations, null, templates)
        scheduler.bind("pkgA", "once") { }
        scheduler.bind("pkgA", "periodic") { }
        var instanceRuns = 0
        scheduler.bindTemplate("pkgA", "tpl") { instanceRuns++ }
        val key = scheduler.scheduleOnce("pkgA", "tpl", 10L, EntityId(77L))
        assertEquals("pkgA::tpl#0", key)
        scheduler.runDue(GameTime(10, "DAY_END"))
        assertEquals(1, instanceRuns)

        val bytes = JobSchedulerStateCodec.encode(scheduler.snapshot())
        val restored = JobScheduler.of(declarations, JobSchedulerStateCodec.decode(bytes), templates)
        assertEquals(JobScheduler.DONE, restored.nextFireDay("pkgA", "once"))
        assertEquals(11L, restored.nextFireDay("pkgA", "periodic"))
        assertTrue(restored.runDue(GameTime(10, "DAY_END")).fired.isEmpty())
        val restoredInstance = restored.instances().single()
        assertEquals(JobState.DONE, restoredInstance.state)
        assertEquals(EntityId(77L), restoredInstance.payloadRef)
        assertEquals(0L, restoredInstance.seq)

        // 序号表恢复后新实例号接续（不前移）。
        assertEquals("pkgA::tpl#1", restored.scheduleOnce("pkgA", "tpl", 12L))
    }

    @Test
    fun `版本1明示拒载`() {
        val bytes = JobSchedulerStateCodec.encode(snapshot())
        bytes[7] = 1
        val e = assertThrows<IllegalArgumentException> { JobSchedulerStateCodec.decode(bytes) }
        assertTrue(e.message!!.contains("任务态只接受版本 2"))
    }

    @Test
    fun `魔数不符拒绝`() {
        val bytes = JobSchedulerStateCodec.encode(snapshot())
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val e = assertThrows<IllegalArgumentException> { JobSchedulerStateCodec.decode(bytes) }
        assertTrue(e.message!!.contains("魔数不符"))
    }

    @Test
    fun `条目数越界拒绝`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x4A533452)
            writeInt(2)
            writeInt((1 shl 20) + 1)
            flush()
        }
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(out.toByteArray())
        }
        assertTrue(e.message!!.contains("条目数越界"))
    }

    @Test
    fun `空键拒绝_编码与解码双侧`() {
        assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.encode(snapshot(" " to 1L))
        }
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(static = listOf(" " to 1L)))
        }
        assertTrue(e.message!!.contains("键为空"))
    }

    @Test
    fun `负触发日拒绝_编码与解码双侧`() {
        assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.encode(snapshot("pkgA::j1" to -1L))
        }
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(static = listOf("pkgA::j1" to -1L)))
        }
        assertTrue(e.message!!.contains("不能为负"))
    }

    @Test
    fun `实例负日与负序号拒绝`() {
        val negDay = raw("pkgA", "tpl", 0L, -1L, null, "PENDING")
        val e1 = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(rawInstances = listOf(negDay)))
        }
        assertTrue(e1.message!!.contains("触发日不能为负"))

        val negSeq = raw("pkgA", "tpl", -1L, 0L, null, "PENDING")
        val e2 = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(rawInstances = listOf(negSeq)))
        }
        assertTrue(e2.message!!.contains("序号不能为负"))
    }

    @Test
    fun `重复键拒绝`() {
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(
                handcrafted(static = listOf("pkgA::j1" to 1L, "pkgA::j1" to 2L)),
            )
        }
        assertTrue(e.message!!.contains("键重复"))
    }

    @Test
    fun `重复实例键拒绝`() {
        val one = raw("pkgA", "tpl", 3L, 1L, null, "PENDING")
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(rawInstances = listOf(one, one)))
        }
        assertTrue(e.message!!.contains("实例键重复"))
    }

    @Test
    fun `非法实例状态拒绝`() {
        val bad = raw("pkgA", "tpl", 0L, 1L, null, "NOPE")
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(rawInstances = listOf(bad)))
        }
        assertTrue(e.message!!.contains("实例状态非法"))
    }

    @Test
    fun `序号表负值拒绝`() {
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(handcrafted(seq = listOf("pkgA::tpl" to -1L)))
        }
        assertTrue(e.message!!.contains("序号不能为负"))
    }

    @Test
    fun `截断拒绝`() {
        val bytes = JobSchedulerStateCodec.encode(snapshot("pkgA::j1" to 10L))
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(bytes.copyOf(bytes.size - 1))
        }
        assertTrue(e.message!!.contains("截断"))
    }

    @Test
    fun `尾部多余字节拒绝`() {
        val bytes = JobSchedulerStateCodec.encode(snapshot("pkgA::j1" to 10L))
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.decode(bytes + byteArrayOf(0))
        }
        assertTrue(e.message!!.contains("尾部多余字节"))
    }

    @Test
    fun `超长键编码拒绝`() {
        val longKey = "p".repeat(65536)
        val e = assertThrows<IllegalArgumentException> {
            JobSchedulerStateCodec.encode(snapshot(longKey to 1L))
        }
        assertTrue(e.message!!.contains("超长"))
    }

    private data class RawInstance(
        val packageId: String,
        val templateKey: String,
        val seq: Long,
        val atDay: Long,
        val payload: EntityId?,
        val state: String,
    )

    private fun raw(
        packageId: String,
        templateKey: String,
        seq: Long,
        atDay: Long,
        payload: EntityId?,
        state: String,
    ): RawInstance = RawInstance(packageId, templateKey, seq, atDay, payload, state)

    /** 手工构造 VERSION=2 字节流，绕过 encode 的校验以测 decode 的 fail-closed。 */
    private fun handcrafted(
        static: List<Pair<String, Long>> = emptyList(),
        rawInstances: List<RawInstance> = emptyList(),
        seq: List<Pair<String, Long>> = emptyList(),
        version: Int = 2,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x4A533452)
            writeInt(version)
            writeInt(static.size)
            for ((key, next) in static) {
                writeUTF(key)
                writeLong(next)
            }
            writeInt(rawInstances.size)
            for (instance in rawInstances) {
                writeUTF(instance.packageId)
                writeUTF(instance.templateKey)
                writeLong(instance.seq)
                writeLong(instance.atDay)
                writeBoolean(instance.payload != null)
                if (instance.payload != null) writeLong(instance.payload.raw)
                writeUTF(instance.state)
            }
            writeInt(seq.size)
            for ((key, next) in seq) {
                writeUTF(key)
                writeLong(next)
            }
            flush()
        }
        return out.toByteArray()
    }
}
