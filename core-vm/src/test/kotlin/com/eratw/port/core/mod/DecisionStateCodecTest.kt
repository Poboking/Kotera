package com.eratw.port.core.mod

import com.eratw.port.modapi.ReservationKey
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DecisionStateCodec 契约闸（M6-2，§21.3.5 + §8）。
 *
 * 钉住：空态与满态往返、字节确定性（与插入序无关）、段序与种子/序号定长、DecisionSeed 派生
 * （SHA-256 前 8 字节大端、0 记为 1），以及魔数 / 版本 / 条目数 / 空键 / 负计数 / 负序号 /
 * 重复键 / 截断 / 尾部多余 / 超长键逐条 fail-closed。
 *
 * 本车是功能新增（不是 bug 修复），根因层断言不适用 AGENTS §3.1。
 */
class DecisionStateCodecTest {

    private fun fullState(): DecisionState = DecisionState(
        decisionSeed = -4242424242L,
        nextOrdinal = 17,
        dayCounts = listOf(
            DayCountEntry("pkg.b", "z", 12L, 3),
            DayCountEntry("pkg.a", "k", 10L, 1),
            DayCountEntry("pkg.a", "k", 11L, 2),
        ),
        actorCounts = listOf(
            ActorCountEntry("pkg.b", "z", 12L, 9L, 2),
            ActorCountEntry("pkg.a", "k", 10L, 7L, 1),
            ActorCountEntry("pkg.a", "k", 5L, 700L, 1),
        ),
        cooldownUntil = listOf(
            CooldownEntry("pkg.b::burn", 14L),
            CooldownEntry("pkg.a::cd", 13L),
        ),
        heldReservations = listOf(
            ReservationKey("slot", "2"),
            ReservationKey("chara", "1"),
        ),
    )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun craft(
        magic: Int = 0x44533252,
        version: Int = 2,
        seed: Long = 1L,
        ordinal: Int = 0,
        days: List<DayCountEntry> = emptyList(),
        actors: List<ActorCountEntry> = emptyList(),
        cooldowns: List<CooldownEntry> = emptyList(),
        held: List<Pair<String, String>> = emptyList(),
        dayCountOverride: Int? = null,
        tail: ByteArray = byteArrayOf(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(magic)
            writeInt(version)
            writeLong(seed)
            writeInt(ordinal)
            writeInt(dayCountOverride ?: days.size)
            for (entry in days) {
                writeUTF(entry.packageId)
                writeUTF(entry.candidateKey)
                writeLong(entry.day)
                writeInt(entry.count)
            }
            writeInt(actors.size)
            for (entry in actors) {
                writeUTF(entry.packageId)
                writeUTF(entry.candidateKey)
                writeLong(entry.day)
                writeLong(entry.actorRaw)
                writeInt(entry.count)
            }
            writeInt(cooldowns.size)
            for (entry in cooldowns) {
                writeUTF(entry.scopedKey)
                writeLong(entry.untilDay)
            }
            writeInt(held.size)
            for ((kind, key) in held) {
                writeUTF(kind)
                writeUTF(key)
            }
            write(tail)
            flush()
        }
        return out.toByteArray()
    }

    @Test
    fun `01 空状态往返逐字节确定`() {
        val empty = DecisionState(0L, 0, emptyList(), emptyList(), emptyList(), emptyList())
        val first = DecisionStateCodec.encode(empty)
        val second = DecisionStateCodec.encode(empty)
        assertContentEquals(first, second)
        assertEquals(sha(first), sha(second))
        assertEquals(empty, DecisionStateCodec.decode(first))
    }

    @Test
    fun `02 满状态往返逐字段相等且字节确定`() {
        val state = fullState()
        val first = DecisionStateCodec.encode(state)
        val second = DecisionStateCodec.encode(fullState())
        assertContentEquals(first, second)
        assertEquals(sha(first), sha(second))

        val decoded = DecisionStateCodec.decode(first)
        assertEquals(state.decisionSeed, decoded.decisionSeed)
        assertEquals(state.nextOrdinal, decoded.nextOrdinal)
        assertEquals(
            state.dayCounts.sortedWith(compareBy({ it.packageId }, { it.candidateKey }, { it.day })),
            decoded.dayCounts,
        )
        assertEquals(
            state.actorCounts.sortedWith(
                compareBy({ it.packageId }, { it.candidateKey }, { it.day }, { it.actorRaw }),
            ),
            decoded.actorCounts,
        )
        assertEquals(state.cooldownUntil.sortedBy { it.scopedKey }, decoded.cooldownUntil)
        assertEquals(
            state.heldReservations.sortedWith(compareBy({ it.kind }, { it.key })),
            decoded.heldReservations,
        )
        assertContentEquals(first, DecisionStateCodec.encode(decoded))
    }

    @Test
    fun `03 列表排序与插入序无关`() {
        val ordered = DecisionState(
            decisionSeed = 7L,
            nextOrdinal = 2,
            dayCounts = listOf(
                DayCountEntry("pkg.a", "a", 1L, 1),
                DayCountEntry("pkg.a", "b", 1L, 2),
                DayCountEntry("pkg.b", "a", 1L, 3),
            ),
            actorCounts = listOf(
                ActorCountEntry("pkg.a", "a", 1L, 1L, 1),
                ActorCountEntry("pkg.a", "a", 1L, 2L, 2),
            ),
            cooldownUntil = listOf(
                CooldownEntry("pkg.a::a", 9L),
                CooldownEntry("pkg.a::b", 8L),
            ),
            heldReservations = listOf(
                ReservationKey("chara", "1"),
                ReservationKey("slot", "2"),
            ),
        )
        val shuffled = ordered.copy(
            dayCounts = ordered.dayCounts.reversed(),
            actorCounts = ordered.actorCounts.reversed(),
            cooldownUntil = ordered.cooldownUntil.reversed(),
            heldReservations = ordered.heldReservations.reversed(),
        )
        val orderedBytes = DecisionStateCodec.encode(ordered)
        assertContentEquals(orderedBytes, DecisionStateCodec.encode(shuffled))
        assertEquals(ordered, DecisionStateCodec.decode(orderedBytes))
    }

    @Test
    fun `04 版本1明示拒载`() {
        val bytes = craft(version = 1)
        val error = assertThrows<IllegalArgumentException> { DecisionStateCodec.decode(bytes) }
        assertTrue(error.message!!.contains("决策态只接受版本 2"), error.message)
    }

    @Test
    fun `05 未知版本明示拒载`() {
        for (version in listOf(0, 3, 99, -1)) {
            val error = assertThrows<IllegalArgumentException> {
                DecisionStateCodec.decode(craft(version = version))
            }
            assertTrue(error.message!!.contains("决策态只接受版本 2"), error.message)
        }
    }

    @Test
    fun `06 坏魔数拒载`() {
        val bytes = DecisionStateCodec.encode(fullState())
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val error = assertThrows<IllegalArgumentException> { DecisionStateCodec.decode(bytes) }
        assertTrue(error.message!!.contains("魔数不符"), error.message)
    }

    @Test
    fun `07 截断拒载`() {
        val bytes = DecisionStateCodec.encode(fullState())
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(bytes.copyOf(bytes.size - 1))
        }
        assertTrue(error.message!!.contains("截断"), error.message)
        assertThrows<IllegalArgumentException> { DecisionStateCodec.decode(ByteArray(0)) }
        assertThrows<IllegalArgumentException> { DecisionStateCodec.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `08 尾部多余字节拒载`() {
        val bytes = DecisionStateCodec.encode(fullState())
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(bytes + byteArrayOf(0))
        }
        assertTrue(error.message!!.contains("尾部多余字节"), error.message)
    }

    @Test
    fun `09 重复 dayCounts 键拒载`() {
        val duplicated = DayCountEntry("pkg.a", "k", 10L, 1)
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(1L, 0, listOf(duplicated, duplicated), emptyList(), emptyList(), emptyList()),
            )
        }
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(days = listOf(duplicated, duplicated)))
        }
        assertTrue(error.message!!.contains("键重复"), error.message)
    }

    @Test
    fun `10 重复 actorCounts 键拒载`() {
        val duplicated = ActorCountEntry("pkg.a", "k", 10L, 7L, 1)
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(1L, 0, emptyList(), listOf(duplicated, duplicated), emptyList(), emptyList()),
            )
        }
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(actors = listOf(duplicated, duplicated)))
        }
        assertTrue(error.message!!.contains("键重复"), error.message)
    }

    @Test
    fun `11 重复冷却与预留键拒载`() {
        val cooldown = CooldownEntry("pkg.a::cd", 9L)
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(cooldowns = listOf(cooldown, cooldown)))
        }
        assertTrue(error.message!!.contains("键重复"), error.message)
        val heldError = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(held = listOf("chara" to "1", "chara" to "1")))
        }
        assertTrue(heldError.message!!.contains("键重复"), heldError.message)
        val reservation = ReservationKey("chara", "1")
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(1L, 0, emptyList(), emptyList(), emptyList(), listOf(reservation, reservation)),
            )
        }
    }

    @Test
    fun `12 空键拒载`() {
        val blankDay = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(days = listOf(DayCountEntry("", "k", 1L, 1))))
        }
        assertTrue(blankDay.message!!.contains("键为空"), blankDay.message)
        val blankCandidate = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(days = listOf(DayCountEntry("pkg.a", " ", 1L, 1))))
        }
        assertTrue(blankCandidate.message!!.contains("键为空"), blankCandidate.message)
        val blankCooldown = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(cooldowns = listOf(CooldownEntry("", 1L))))
        }
        assertTrue(blankCooldown.message!!.contains("键为空"), blankCooldown.message)
        val blankHeld = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(held = listOf("" to "1")))
        }
        assertTrue(blankHeld.message!!.contains("键为空"), blankHeld.message)
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(1L, 0, listOf(DayCountEntry("", "k", 1L, 1)), emptyList(), emptyList(), emptyList()),
            )
        }
    }

    @Test
    fun `13 超长键编码拒载`() {
        val longKey = "p".repeat(65536)
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(
                    1L,
                    0,
                    listOf(DayCountEntry(longKey, "k", 1L, 1)),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
            )
        }
        assertTrue(error.message!!.contains("键超长"), error.message)
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(
                    1L,
                    0,
                    emptyList(),
                    emptyList(),
                    listOf(CooldownEntry("pkg.a::" + longKey, 1L)),
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `14 负计数拒载`() {
        val dayError = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(days = listOf(DayCountEntry("pkg.a", "k", 1L, -1))))
        }
        assertTrue(dayError.message!!.contains("计数不能为负"), dayError.message)
        val actorError = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(actors = listOf(ActorCountEntry("pkg.a", "k", 1L, 7L, -2))))
        }
        assertTrue(actorError.message!!.contains("计数不能为负"), actorError.message)
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(
                    1L,
                    0,
                    listOf(DayCountEntry("pkg.a", "k", 1L, -1)),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `15 负 nextOrdinal 拒载`() {
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(ordinal = -1))
        }
        assertTrue(error.message!!.contains("nextOrdinal 不能为负"), error.message)
        assertThrows<IllegalArgumentException> {
            DecisionStateCodec.encode(
                DecisionState(1L, -1, emptyList(), emptyList(), emptyList(), emptyList()),
            )
        }
    }

    @Test
    fun `16 条目数越界拒载`() {
        val error = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(dayCountOverride = (1 shl 20) + 1))
        }
        assertTrue(error.message!!.contains("条目数越界"), error.message)
        val negative = assertThrows<IllegalArgumentException> {
            DecisionStateCodec.decode(craft(dayCountOverride = -1))
        }
        assertTrue(negative.message!!.contains("条目数越界"), negative.message)
    }

    @Test
    fun `17 DecisionSeed 派生确定且改一字节变值`() {
        val entropy = byteArrayOf(1, 2, 3, 4, 5)
        val first = DecisionSeed.derive(entropy)
        val second = DecisionSeed.derive(entropy.copyOf())
        assertEquals(first, second)
        assertNotEquals(0L, first)
        assertNotEquals(first, DecisionSeed.derive(byteArrayOf(1, 2, 3, 4, 6)))

        val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
        var expected = 0L
        for (i in 0 until 8) expected = (expected shl 8) or (digest[i].toLong() and 0xFFL)
        assertEquals(if (expected == 0L) 1L else expected, first)

        val empty = DecisionSeed.derive(ByteArray(0))
        assertNotEquals(0L, empty)
        assertEquals(empty, DecisionSeed.derive(ByteArray(0)))
    }

    @Test
    fun `18 种子大值与冷却哨兵往返`() {
        val state = DecisionState(
            decisionSeed = Long.MIN_VALUE,
            nextOrdinal = Int.MAX_VALUE,
            dayCounts = emptyList(),
            actorCounts = emptyList(),
            cooldownUntil = listOf(CooldownEntry("pkg.a::forever", Long.MAX_VALUE)),
            heldReservations = emptyList(),
        )
        val decoded = DecisionStateCodec.decode(DecisionStateCodec.encode(state))
        assertEquals(Long.MIN_VALUE, decoded.decisionSeed)
        assertEquals(Int.MAX_VALUE, decoded.nextOrdinal)
        assertEquals(listOf(CooldownEntry("pkg.a::forever", Long.MAX_VALUE)), decoded.cooldownUntil)
    }
}
