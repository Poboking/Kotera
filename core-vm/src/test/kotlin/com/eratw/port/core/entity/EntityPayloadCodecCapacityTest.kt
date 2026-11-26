package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 实体段编码的**容量自洽**：编码器不许假设「估算足够」。
 *
 * ## 判例（2026-11-20 真机 Redmi Turbo 3）
 *
 * `SAVEDATA` 在 Mod 多的档上稳定失败，屏上现场 = `stage=capture+encode` +
 * `BufferOverflowException`（message 为 null）。根因：`EntityPayloadCodec.encode`
 * 用 `estimateSize` 预分配 `ByteBuffer`，而该估算每个**字段**少算 8 字节
 * （writeName 的 u16 长度前缀 2 + 类型 1 + entityRef 1 + intCount 4），字段数 ≳ 10
 * 时实际字节数越过预分配 ⇒ `ByteBuffer.put` 抛 `BufferOverflowException`。
 *
 * 该异常在桌面单测里从不出现：既有用例的实体内核只有两三个字段，估算的 64 字节余量
 * 足够掩盖这段欠账；只有装了多个组件 Mod 的真机档才会把字段数推过阈值。
 */
class EntityPayloadCodecCapacityTest {

    /** 12 个 INT 字段 + 4 个 STR 字段 ⇒ 估算欠账 16×8=128 字节，远超 64 字节余量。 */
    private fun wideSnapshot(): EntitySnapshot {
        val slots = (0 until 6).map { EntitySnapshot.SlotSnapshot(it, it + 1, 7, 1, 0) }
        val fields = (0 until 12).map { i ->
            EntitySnapshot.FieldSnapshot(
                name = "ZZCAP_INT_$i",
                type = ComponentFieldType.INT,
                isEntityRef = false,
                intValues = (0 until 4).map { EntitySnapshot.IntLongPair(it, (i * 10 + it).toLong()) },
            )
        } + (0 until 4).map { i ->
            EntitySnapshot.FieldSnapshot(
                name = "ZZCAP_STR_$i",
                type = ComponentFieldType.STR,
                isEntityRef = false,
                strValues = (0 until 3).map { EntitySnapshot.IntStringPair(it, "値-$i-$it") },
            )
        }
        val edges = (0 until 5).map { i ->
            EntitySnapshot.EdgeSnapshot(
                EntityIdCodec.encode(7, i, i),
                0,
                EntityIdCodec.encode(7, i + 1, i + 1),
                100L,
            )
        }
        return EntitySnapshot(slots, listOf("ZZCAP_ARCH"), fields, listOf("ZZCAP_REL"), edges)
    }

    @Test
    fun `字段多到越过容量估算时不得抛 BufferOverflowException`() {
        val snapshot = wideSnapshot()
        val bytes = EntityPayloadCodec.encode(snapshot)
        val decoded = EntityPayloadCodec.decode(bytes)
        assertEquals(snapshot.fields.size, decoded.fields.size, "字段数必须在往返后不变")
        assertEquals(snapshot.fields.sumOf { it.intValues.size }, decoded.fields.sumOf { it.intValues.size })
        assertEquals(snapshot.fields.sumOf { it.strValues.size }, decoded.fields.sumOf { it.strValues.size })
        assertEquals(snapshot.edges.size, decoded.edges.size)
    }
}
